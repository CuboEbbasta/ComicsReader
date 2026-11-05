package com.cubo.lumetto.ai

import org.json.JSONArray
import org.json.JSONObject

/** Descrizione di uno strumento esposto alle AI. [readOnly] = false se modifica dati o app. */
class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema degli argomenti (oggetto). */
    val schema: JSONObject,
    val readOnly: Boolean,
)

/** Risultato di uno strumento: blocchi di contenuto in stile MCP (testo o immagine). */
class ToolResult(val content: List<JSONObject>, val isError: Boolean = false) {
    companion object {
        fun text(s: String) = ToolResult(listOf(JSONObject().put("type", "text").put("text", s)))
        fun json(o: Any) = text(if (o is JSONObject) o.toString(2) else if (o is JSONArray) o.toString(2) else o.toString())
        fun error(s: String) = ToolResult(listOf(JSONObject().put("type", "text").put("text", s)), true)
        fun withImage(caption: String, base64: String, mime: String) = ToolResult(
            listOf(
                JSONObject().put("type", "text").put("text", caption),
                JSONObject().put("type", "image").put("data", base64).put("mimeType", mime),
            )
        )
    }

    fun toJson(): JSONObject = JSONObject().put("content", JSONArray(content)).put("isError", isError)
}

/** Chi sa eseguire gli strumenti (l'app, o un finto host nei test). */
interface ToolHost {
    val specs: List<ToolSpec>
    fun call(name: String, args: JSONObject): ToolResult
}

/**
 * Server MCP minimale (JSON-RPC 2.0): initialize, ping, tools/list, tools/call.
 * Nessuna dipendenza da Android, così si prova con i test.
 */
class McpProtocol(private val host: ToolHost, private val instructions: String) {
    companion object {
        const val DEFAULT_VERSION = "2025-03-26"
        private val SUPPORTED = setOf("2024-11-05", "2025-03-26", "2025-06-18")
    }

    /** Gestisce un messaggio o un batch. Restituisce null se non serve risposta (notifiche). */
    fun handle(body: String): String? {
        val trimmed = body.trim()
        return try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val out = JSONArray()
                for (i in 0 until arr.length()) handleOne(arr.optJSONObject(i))?.let { out.put(it) }
                if (out.length() == 0) null else out.toString()
            } else {
                handleOne(JSONObject(trimmed))?.toString()
            }
        } catch (e: org.json.JSONException) {
            error(JSONObject.NULL, -32700, "JSON non valido").toString()
        }
    }

    private fun handleOne(req: JSONObject?): JSONObject? {
        if (req == null) return error(JSONObject.NULL, -32600, "Richiesta non valida")
        val hasId = req.has("id") && !req.isNull("id")
        val id: Any = if (hasId) req.get("id") else JSONObject.NULL
        val method = req.optString("method", "")
        if (method.isEmpty()) return if (hasId) error(id, -32600, "Manca il metodo") else null
        if (!hasId) return null // notifica (es. notifications/initialized)
        val params = req.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> {
                val asked = params.optString("protocolVersion", "")
                val version = if (asked in SUPPORTED) asked else DEFAULT_VERSION
                ok(
                    id,
                    JSONObject()
                        .put("protocolVersion", version)
                        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                        .put("serverInfo", JSONObject().put("name", "lumetto").put("version", "1.0.0"))
                        .put("instructions", instructions)
                )
            }
            "ping" -> ok(id, JSONObject())
            "tools/list" -> {
                val tools = JSONArray()
                for (s in host.specs) {
                    tools.put(
                        JSONObject().put("name", s.name).put("description", s.description).put("inputSchema", s.schema)
                            .put("annotations", JSONObject().put("readOnlyHint", s.readOnly))
                    )
                }
                ok(id, JSONObject().put("tools", tools))
            }
            "tools/call" -> {
                val name = params.optString("name", "")
                if (host.specs.none { it.name == name }) return error(id, -32602, "Strumento sconosciuto: $name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val result = try {
                    host.call(name, args)
                } catch (e: Exception) {
                    ToolResult.error("Errore: ${e.message ?: e.javaClass.simpleName}")
                }
                ok(id, result.toJson())
            }
            else -> error(id, -32601, "Metodo non supportato: $method")
        }
    }

    private fun ok(id: Any, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun error(id: Any, code: Int, message: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message))
}

/** Descrizione OpenAPI 3 degli stessi strumenti, per le AI che parlano REST invece di MCP. */
object OpenApiDoc {
    fun build(host: ToolHost, baseUrl: String): JSONObject {
        val paths = JSONObject()
        for (s in host.specs) {
            val op = JSONObject()
                .put("operationId", s.name)
                .put("summary", s.description)
                .put("security", JSONArray().put(JSONObject().put("bearer", JSONArray())))
                .put(
                    "requestBody",
                    JSONObject().put("required", false).put(
                        "content", JSONObject().put("application/json", JSONObject().put("schema", s.schema))
                    )
                )
                .put("responses", JSONObject().put("200", JSONObject().put("description", "Risultato: { content: [...], isError }")))
            paths.put("/api/tools/${s.name}", JSONObject().put("post", op))
        }
        return JSONObject()
            .put("openapi", "3.0.3")
            .put("info", JSONObject().put("title", "Lumetto - accesso AI").put("version", "1.0.0"))
            .put("servers", JSONArray().put(JSONObject().put("url", baseUrl)))
            .put(
                "components",
                JSONObject().put("securitySchemes", JSONObject().put("bearer", JSONObject().put("type", "http").put("scheme", "bearer")))
            )
            .put("paths", paths)
    }
}
