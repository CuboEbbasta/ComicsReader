package com.cubo.lumetto.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

private class FakeHost : ToolHost {
    override val specs = listOf(
        ToolSpec("echo", "Ripete il testo", JSONObject().put("type", "object"), true),
        ToolSpec("boom", "Va in errore", JSONObject().put("type", "object"), false),
    )

    override fun call(name: String, args: JSONObject): ToolResult = when (name) {
        "echo" -> ToolResult.text(args.optString("text", ""))
        else -> throw IllegalStateException("esploso")
    }
}

class McpProtocolTest {
    private val mcp = McpProtocol(FakeHost(), "istruzioni")

    private fun ask(json: String) = JSONObject(mcp.handle(json)!!)

    @Test
    fun initializeEchoesSupportedVersionAndAdvertisesTools() {
        val r = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""")
        val result = r.getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
        assertEquals("istruzioni", result.getString("instructions"))
    }

    @Test
    fun unknownVersionFallsBackToDefault() {
        val r = ask("""{"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")
        assertEquals(McpProtocol.DEFAULT_VERSION, r.getJSONObject("result").getString("protocolVersion"))
        assertEquals("a", r.getString("id"))
    }

    @Test
    fun notificationsGetNoAnswer() {
        assertEquals(null, mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun toolsListReportsReadOnlyHint() {
        val tools = ask("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").getJSONObject("result").getJSONArray("tools")
        assertEquals(2, tools.length())
        assertTrue(tools.getJSONObject(0).getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertFalse(tools.getJSONObject(1).getJSONObject("annotations").getBoolean("readOnlyHint"))
    }

    @Test
    fun toolCallReturnsContent() {
        val r = ask("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"ciao"}}}""")
        val res = r.getJSONObject("result")
        assertFalse(res.getBoolean("isError"))
        assertEquals("ciao", res.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun toolExceptionBecomesErrorResultNotCrash() {
        val r = ask("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"boom"}}""")
        assertTrue(r.getJSONObject("result").getBoolean("isError"))
    }

    @Test
    fun unknownToolAndMethodAndBadJsonAreErrors() {
        assertEquals(-32602, ask("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope"}}""").getJSONObject("error").getInt("code"))
        assertEquals(-32601, ask("""{"jsonrpc":"2.0","id":6,"method":"foo"}""").getJSONObject("error").getInt("code"))
        assertEquals(-32700, ask("{non json").getJSONObject("error").getInt("code"))
    }

    @Test
    fun batchAnswersOnlyRequests() {
        val out = JSONArray(mcp.handle("""[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/x"}]""")!!)
        assertEquals(1, out.length())
    }

    @Test
    fun openApiListsEveryTool() {
        val doc = OpenApiDoc.build(FakeHost(), "http://x:1")
        assertTrue(doc.getJSONObject("paths").has("/api/tools/echo"))
        assertTrue(doc.getJSONObject("paths").has("/api/tools/boom"))
    }
}

class AiHttpServerTest {
    private val host = FakeHost()
    private lateinit var server: AiHttpServer

    @Before
    fun start() {
        server = AiHttpServer(InetAddress.getLoopbackAddress(), 0, { "segreto" }, host, McpProtocol(host, "i"))
        server.start()
    }

    @After
    fun stop() = server.stop()

    private fun request(method: String, path: String, body: String? = null, token: String? = "segreto", origin: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${server.boundPort}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        origin?.let { c.setRequestProperty("Origin", it) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        return code to (stream?.readBytes()?.toString(Charsets.UTF_8) ?: "")
    }

    @Test
    fun healthNeedsNoToken() {
        val (code, body) = request("GET", "/health", token = null)
        assertEquals(200, code)
        assertEquals("Lumetto", JSONObject(body).getString("app"))
    }

    @Test
    fun missingOrWrongTokenIs401() {
        assertEquals(401, request("GET", "/api/tools", token = null).first)
        assertEquals(401, request("GET", "/api/tools", token = "sbagliato").first)
        assertEquals(401, request("POST", "/mcp", "{}", token = null).first)
    }

    @Test
    fun browserRequestsAreRefused() {
        assertEquals(403, request("GET", "/api/tools", origin = "http://evil.example").first)
    }

    @Test
    fun restToolCallWorks() {
        val (code, body) = request("POST", "/api/tools/echo", """{"text":"ok"}""")
        assertEquals(200, code)
        assertEquals("ok", JSONObject(body).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun mcpOverHttpWorks() {
        val (code, body) = request("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(200, code)
        assertEquals(2, JSONObject(body).getJSONObject("result").getJSONArray("tools").length())
        assertEquals(202, request("POST", "/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""").first)
    }

    @Test
    fun badInputsAreRejected() {
        assertEquals(404, request("POST", "/api/tools/zzz", "{}").first)
        assertEquals(400, request("POST", "/api/tools/echo", "{rotto").first)
        assertEquals(404, request("GET", "/altro").first)
    }

    @Test
    fun tokenComparison() {
        assertTrue(AiHttpServer.tokenMatches("abc", "abc"))
        assertFalse(AiHttpServer.tokenMatches("abc", "abd"))
        assertFalse(AiHttpServer.tokenMatches("", ""))
    }
}
