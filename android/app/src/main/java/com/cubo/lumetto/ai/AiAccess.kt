package com.cubo.lumetto.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cubo.lumetto.data.AppContext
import com.cubo.lumetto.data.onMain
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Accesso AI: un server locale (MCP + REST) con cui un'AI legge e usa Lumetto.
 * Spento di default. Le modifiche ai dati richiedono un secondo consenso.
 */
object AiAccess {
    private val p get() = AppContext.prefs

    private var _enabled by mutableStateOf(false)
    var enabled: Boolean
        get() = _enabled
        set(v) { _enabled = v; p.edit().putBoolean("ai.enabled", v).apply(); restart() }

    private var _allowWrites by mutableStateOf(false)
    var allowWrites: Boolean
        get() = _allowWrites
        set(v) { _allowWrites = v; p.edit().putBoolean("ai.allowWrites", v).apply() }

    /** true = raggiungibile dalla rete di casa; false = solo da questo telefono (127.0.0.1). */
    private var _lan by mutableStateOf(true)
    var lanAccess: Boolean
        get() = _lan
        set(v) { _lan = v; p.edit().putBoolean("ai.lan", v).apply(); restart() }

    private var _port by mutableStateOf(DEFAULT_PORT)
    var port: Int
        get() = _port
        set(v) { _port = v.coerceIn(1024, 65535); p.edit().putInt("ai.port", _port).apply(); restart() }

    private var _token by mutableStateOf("")
    val token: String get() = _token

    var statusMessage by mutableStateOf("Spento")
        private set

    /** Ultime richieste ricevute (più recente in cima), per controllo dell'utente. */
    var log by mutableStateOf<List<String>>(emptyList())
        private set

    const val DEFAULT_PORT = 8765
    private var server: AiHttpServer? = null

    fun load() {
        _enabled = p.getBoolean("ai.enabled", false)
        _allowWrites = p.getBoolean("ai.allowWrites", false)
        _lan = p.getBoolean("ai.lan", true)
        _port = p.getInt("ai.port", DEFAULT_PORT)
        _token = p.getString("ai.token", null) ?: newToken().also { p.edit().putString("ai.token", it).apply() }
        restart()
    }

    fun regenerateToken() {
        _token = newToken()
        p.edit().putString("ai.token", _token).apply()
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return "lum_" + bytes.joinToString("") { "%02x".format(it) }
    }

    fun record(entry: String) {
        val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ITALY).format(java.util.Date())}  $entry"
        onMain { log = (listOf(line) + log).take(60) }
    }

    @Synchronized
    private fun restart() {
        server?.stop()
        server = null
        if (!_enabled) {
            statusMessage = "Spento"
            return
        }
        try {
            val bind: InetAddress? = if (_lan) null else InetAddress.getLoopbackAddress()
            val s = AiHttpServer(
                bindAddress = bind, port = _port, token = { _token },
                host = AiTools, protocol = McpProtocol(AiTools, AiTools.INSTRUCTIONS),
                baseUrl = { urls().firstOrNull() ?: "http://localhost:$_port" },
                onRequest = { record(it) },
            )
            s.start()
            server = s
            statusMessage = "Attivo su porta $_port"
        } catch (e: Exception) {
            statusMessage = "Non avviato: ${e.message ?: "errore"}"
        }
    }

    /** Indirizzi con cui raggiungere il telefono (rete locale, oppure solo localhost). */
    fun urls(): List<String> {
        if (!_lan) return listOf("http://127.0.0.1:$_port")
        val found = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) found.add("http://${a.hostAddress}:$_port")
            }
        } catch (_: Exception) {
        }
        return found
    }

    /** Configurazione pronta da incollare in un client MCP (formato "mcpServers"). */
    fun mcpConfig(): String {
        val url = (urls().firstOrNull() ?: "http://IP-DEL-TELEFONO:$_port") + "/mcp"
        return """{
  "mcpServers": {
    "lumetto": {
      "type": "http",
      "url": "$url",
      "headers": { "Authorization": "Bearer $_token" }
    }
  }
}"""
    }
}
