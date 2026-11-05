package com.cubo.lumetto.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Piccolo server HTTP/1.1 per l'accesso AI. Una richiesta per connessione.
 *
 * Rotte:
 *  - GET  /health                 senza token: solo "c'è Lumetto" (serve a verificare l'indirizzo)
 *  - POST /mcp                    MCP (JSON-RPC) con token
 *  - GET  /api/tools              elenco strumenti con token
 *  - POST /api/tools/{nome}       esegue uno strumento (corpo = argomenti JSON) con token
 *  - GET  /openapi.json           descrizione OpenAPI con token
 *
 * Sicurezza: token Bearer obbligatorio (confronto a tempo costante), nessuna richiesta dal browser
 * (le richieste con intestazione Origin sono rifiutate), corpo e intestazioni limitati.
 */
class AiHttpServer(
    private val bindAddress: InetAddress?,
    private val port: Int,
    private val token: () -> String,
    private val host: ToolHost,
    private val protocol: McpProtocol,
    private val baseUrl: () -> String = { "http://localhost:$port" },
    private val onRequest: (String) -> Unit = {},
) {
    companion object {
        const val MAX_HEADER = 16 * 1024
        const val MAX_BODY = 1024 * 1024

        fun tokenMatches(expected: String, given: String): Boolean {
            if (expected.isEmpty()) return false
            return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))
        }
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "lumetto-ai-http").apply { isDaemon = true } }

    @Volatile
    private var running = false

    /** La porta realmente usata (utile con porta 0 nei test). */
    val boundPort: Int get() = server?.localPort ?: port

    fun start() {
        if (running) return
        val s = ServerSocket(port, 20, bindAddress)
        server = s
        running = true
        Thread({
            while (running) {
                val c = try { s.accept() } catch (e: Exception) { break }
                pool.execute { handle(c) }
            }
        }, "lumetto-ai-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 15_000
                val req = readRequest(sock.getInputStream()) ?: return
                val (status, type, payload) = route(req)
                val bytes = payload.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                val out = sock.getOutputStream()
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
            }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val headBytes = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            headBytes.write(b)
            if (headBytes.size() > MAX_HEADER) return null
            matched = if (b.toByte() == terminator[matched]) matched + 1 else if (b.toByte() == terminator[0]) 1 else 0
        }
        val lines = headBytes.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) return Request(first[0], "__too_large__", headers, ByteArray(0))
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return Request(first[0].uppercase(), first[1].substringBefore('?'), headers, body)
    }

    private fun route(req: Request): Triple<String, String, String> {
        val json = "application/json"
        fun err(status: String, message: String) = Triple(status, json, JSONObject().put("error", message).toString())

        if (req.path == "__too_large__") return err("413 Payload Too Large", "Richiesta troppo grande")
        if (req.method == "GET" && req.path == "/health") {
            return Triple("200 OK", json, JSONObject().put("app", "Lumetto").put("ai", true).toString())
        }
        // Le pagine web non devono poter parlare con il server (DNS rebinding / CSRF).
        if (req.headers.containsKey("origin")) return err("403 Forbidden", "Richieste dal browser non consentite")

        val auth = req.headers["authorization"] ?: ""
        val given = if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else ""
        if (!tokenMatches(token(), given)) {
            return Triple("401 Unauthorized", json, JSONObject().put("error", "Token mancante o errato").toString())
        }

        return when {
            req.method == "POST" && req.path == "/mcp" -> {
                onRequest("mcp")
                val answer = protocol.handle(String(req.body, Charsets.UTF_8))
                if (answer == null) Triple("202 Accepted", json, "") else Triple("200 OK", json, answer)
            }
            req.method == "GET" && req.path == "/api/tools" -> {
                val arr = JSONArray()
                for (s in host.specs) {
                    arr.put(
                        JSONObject().put("name", s.name).put("description", s.description)
                            .put("inputSchema", s.schema).put("readOnly", s.readOnly)
                    )
                }
                Triple("200 OK", json, JSONObject().put("tools", arr).toString())
            }
            req.method == "POST" && req.path.startsWith("/api/tools/") -> {
                val name = req.path.removePrefix("/api/tools/")
                if (host.specs.none { it.name == name }) return err("404 Not Found", "Strumento sconosciuto: $name")
                val args = try {
                    if (req.body.isEmpty()) JSONObject() else JSONObject(String(req.body, Charsets.UTF_8))
                } catch (e: org.json.JSONException) {
                    return err("400 Bad Request", "Corpo JSON non valido")
                }
                onRequest(name)
                val result = try {
                    host.call(name, args)
                } catch (e: Exception) {
                    ToolResult.error("Errore: ${e.message ?: e.javaClass.simpleName}")
                }
                Triple(if (result.isError) "400 Bad Request" else "200 OK", json, result.toJson().toString())
            }
            req.method == "GET" && req.path == "/openapi.json" ->
                Triple("200 OK", json, OpenApiDoc.build(host, baseUrl()).toString())
            else -> err("404 Not Found", "Percorso sconosciuto")
        }
    }
}
