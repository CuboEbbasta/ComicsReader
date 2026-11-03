package com.cubo.lumetto.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cubo.lumetto.data.AppContext
import com.cubo.lumetto.data.Debouncer
import com.cubo.lumetto.data.HighlightStore
import com.cubo.lumetto.data.IncognitoStore
import com.cubo.lumetto.data.LocalChanges
import com.cubo.lumetto.data.MetadataStore
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.data.TombstoneStore
import com.cubo.lumetto.data.appScope
import com.cubo.lumetto.data.onMain
import com.cubo.lumetto.model.nowMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Piccola cassaforte: cifra la password della stanza con una chiave dell'Android Keystore. */
private object KeystoreBox {
    private const val ALIAS = "lumetto-sync"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun save(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        AppContext.prefs.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun read(name: String): String? = runCatching {
        val raw = Base64.decode(AppContext.prefs.getString(name, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }.getOrNull()

    fun delete(name: String) = AppContext.prefs.edit().remove(name).apply()
}

/**
 * Sincronizzazione tra dispositivi nella stessa "stanza", sulla stessa rete Wi-Fi, senza server
 * (SYNC_PROTOCOLLO.md). Ogni dispositivo con Lumetto aperto si annuncia (mDNS "_lumetto._tcp")
 * e cerca gli altri della sua stanza; quando ne trova uno si scambiano i dati cifrati, li uniscono
 * e finiscono identici. Compatibile al 100% con l'iPad.
 */
object SyncManager {
    const val SERVICE_TYPE = "_lumetto._tcp"
    const val PROTOCOL_VERSION = 1
    private const val MAX_FRAME = 64 * 1024 * 1024
    private const val PASSWORD = "sync.roomPassword"

    data class Peer(val id: String, val name: String, val host: InetAddress, val port: Int, val serviceName: String)

    enum class Reason { DISCOVERED, CHANGE, MANUAL }

    var roomName by mutableStateOf<String?>(null)
        private set
    private var _enabled by mutableStateOf(true)
    var isEnabled: Boolean
        get() = _enabled
        set(v) {
            _enabled = v
            AppContext.prefs.edit().putBoolean("sync.enabled", v).apply()
            restart()
        }
    var deviceName by mutableStateOf("")
        private set
    var peers by mutableStateOf<List<Peer>>(emptyList())
        private set
    var syncingPeerIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var lastSyncByPeer by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    var lastMessage by mutableStateOf<String?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var isJoining by mutableStateOf(false)
        private set
    var isRunning by mutableStateOf(false)
        private set

    lateinit var deviceId: String
        private set

    private lateinit var nsd: NsdManager
    private var key: ByteArray? = null
    private var isActive = false
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val changeDebouncer = Debouncer(6000)
    private var generation = 0

    val isConfigured: Boolean get() = roomName != null

    fun init(context: Context) {
        val p = AppContext.prefs
        deviceId = p.getString("sync.deviceId", null) ?: java.util.UUID.randomUUID().toString().lowercase().also {
            p.edit().putString("sync.deviceId", it).apply()
        }
        deviceName = p.getString("sync.deviceName", null) ?: (Build.MODEL ?: "Android").take(40)
        roomName = p.getString("sync.roomName", null)
        _enabled = p.getBoolean("sync.enabled", true)
        lastSyncByPeer = runCatching {
            val o = JSONObject(p.getString("sync.lastSyncByPeer", "{}") ?: "{}")
            val m = HashMap<String, Long>()
            o.keys().forEach { k -> m[k] = o.optLong(k) }
            m
        }.getOrDefault(emptyMap())
        nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        LocalChanges.addListener { localDataChanged() }
    }

    // --- ciclo di vita ---

    /** Da chiamare quando l'app torna in primo piano o va in background. */
    fun sceneChanged(active: Boolean) {
        isActive = active
        if (active) prepareKeyThenStart() else stop()
    }

    private fun restart() {
        stop()
        prepareKeyThenStart()
    }

    private fun prepareKeyThenStart() {
        val room = roomName
        if (!isActive || !isEnabled || room == null) { stop(); return }
        if (key != null) { start(); return }
        val password = KeystoreBox.read(PASSWORD) ?: return
        appScope.launch {
            val derived = withContext(Dispatchers.Default) { runCatching { SyncCrypto.deriveKey(password, room) }.getOrNull() }
            if (roomName != room || derived == null) return@launch
            key = derived
            start()
        }
    }

    // --- stanza ---

    fun join(room: String, password: String) {
        val name = room.trim()
        if (name.isEmpty() || password.length < 6) return
        isJoining = true
        appScope.launch {
            val derived = withContext(Dispatchers.Default) { runCatching { SyncCrypto.deriveKey(password, name) }.getOrNull() }
            isJoining = false
            if (derived == null) {
                lastError = "Non è stato possibile preparare la chiave della stanza."
                return@launch
            }
            KeystoreBox.save(PASSWORD, password)
            AppContext.prefs.edit().putString("sync.roomName", name).apply()
            roomName = name
            key = derived
            lastError = null
            lastMessage = "Sei nella stanza «$name»."
            restart()
        }
    }

    fun leave() {
        stop()
        KeystoreBox.delete(PASSWORD)
        AppContext.prefs.edit().remove("sync.roomName").apply()
        roomName = null
        key = null
        lastMessage = null
        lastError = null
    }

    fun setDeviceName(name: String) {
        val trimmed = name.trim().take(40)
        if (trimmed.isEmpty() || trimmed == deviceName) return
        deviceName = trimmed
        AppContext.prefs.edit().putString("sync.deviceName", trimmed).apply()
        if (isRunning) restart()
    }

    // --- rete: annuncio e ricerca ---

    private fun start() {
        val room = roomName ?: return
        if (isRunning || !isActive || !isEnabled || key == null) return
        val tag = SyncCrypto.roomTag(room)
        val gen = ++generation

        val socket = try {
            ServerSocket(0)
        } catch (e: Exception) {
            lastError = "Impossibile avviare la sincronizzazione: ${e.message}"
            return
        }
        server = socket
        isRunning = true
        Thread({ acceptLoop(socket, gen) }, "lumetto-sync-server").start()

        runCatching {
            val wifi = AppContext.context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("lumetto-sync").apply { setReferenceCounted(false); acquire() }
        }

        val info = NsdServiceInfo().apply {
            serviceName = "Lumetto-${deviceId.take(8)}"
            serviceType = SERVICE_TYPE
            port = socket.localPort
            setAttribute("v", "$PROTOCOL_VERSION")
            setAttribute("r", tag)
            setAttribute("id", deviceId)
            setAttribute("n", deviceName.take(40))
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onMain { lastError = "Impossibile annunciarsi in rete (errore $errorCode)." }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = reg
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) }

        val disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onMain { lastError = "Ricerca dei dispositivi non riuscita (errore $errorCode)." }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == "Lumetto-${deviceId.take(8)}") return
                onMain { if (gen == generation) enqueueResolve(serviceInfo) }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onMain { if (gen == generation) peers = peers.filter { it.serviceName != serviceInfo.serviceName } }
            }
        }
        discovery = disc
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc) }
    }

    private fun stop() {
        generation++
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        runCatching { server?.close() }
        server = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        resolveQueue.clear()
        resolving = false
        peers = emptyList()
        isRunning = false
    }

    /** Prima di Android 14 si può risolvere un servizio alla volta: coda. */
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        resolveNext()
    }

    private fun resolveNext() {
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val gen = generation
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onMain {
                    resolving = false
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && gen == generation) {
                        mainHandlerDelay(300) { enqueueResolve(info) }
                    }
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                onMain {
                    resolving = false
                    if (gen == generation) resolved(serviceInfo)
                    resolveNext()
                }
            }
        })
    }

    private fun mainHandlerDelay(ms: Long, block: () -> Unit) {
        com.cubo.lumetto.data.mainHandler.postDelayed(block, ms)
    }

    private fun attr(info: NsdServiceInfo, key: String): String? =
        info.attributes[key]?.let { String(it, Charsets.UTF_8) }

    private fun resolved(info: NsdServiceInfo) {
        val room = roomName ?: return
        val tag = SyncCrypto.roomTag(room)
        val id = attr(info, "id") ?: return
        if (attr(info, "r") != tag || id == deviceId) return
        @Suppress("DEPRECATION")
        val host = info.host ?: return
        val peer = Peer(id, attr(info, "n") ?: "Dispositivo", host, info.port, info.serviceName)
        val isNew = peers.none { it.id == id }
        peers = (peers.filter { it.id != id } + peer).sortedWith(compareBy<Peer>({ it.name }, { it.id }))
        // Per non aprire due connessioni, all'arrivo di un dispositivo inizia quello con l'id minore.
        if (isNew && deviceId < id) {
            appScope.launch {
                delay(1000)
                sync(peer, Reason.DISCOVERED)
            }
        }
    }

    // --- quando sincronizzare ---

    private fun localDataChanged() {
        if (!isActive || key == null || peers.isEmpty()) return
        changeDebouncer.schedule { syncAll(Reason.CHANGE) }
    }

    fun syncNow() {
        lastError = null
        syncAll(Reason.MANUAL)
    }

    private fun syncAll(reason: Reason) {
        peers.forEach { p -> appScope.launch { sync(p, reason) } }
    }

    private suspend fun sync(peer: Peer, reason: Reason) {
        val k = key ?: return
        if (!isActive || peer.id in syncingPeerIds) return
        syncingPeerIds = syncingPeerIds + peer.id
        try {
            exchange(k, reason == Reason.MANUAL) {
                Socket().apply { connect(InetSocketAddress(peer.host, peer.port), 10_000) }
            }
        } finally {
            syncingPeerIds = syncingPeerIds - peer.id
        }
    }

    private fun acceptLoop(socket: ServerSocket, gen: Int) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            appScope.launch {
                val k = key
                if (k == null || !isActive || gen != generation) {
                    runCatching { client.close() }
                    return@launch
                }
                exchange(k, false) { client }
            }
        }
    }

    // --- lo scambio (§3) ---

    private fun writeFrame(out: DataOutputStream, payload: ByteArray) {
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length <= 0 || length > MAX_FRAME) throw SyncException(SyncError.INVALID_DATA)
        val data = ByteArray(length)
        input.readFully(data)
        return data
    }

    /**
     * Entrambi i lati fanno la stessa cosa: inviano saluto + dati cifrati, ricevono quelli
     * dell'altro (in parallelo), uniscono e applicano. Poi la connessione si chiude. Massimo 30 s.
     */
    private suspend fun exchange(key: ByteArray, reportAllErrors: Boolean, connect: () -> Socket) {
        val room = roomName ?: return
        val tag = SyncCrypto.roomTag(room)
        var socket: Socket? = null
        try {
            val local = currentPayload()
            val hello = JSONObject().put("v", PROTOCOL_VERSION).put("id", deviceId).put("name", deviceName).put("room", tag)
                .toString().toByteArray(Charsets.UTF_8)
            val sealed = withContext(Dispatchers.Default) { SyncCrypto.seal(local.toJson().toString().toByteArray(Charsets.UTF_8), key) }

            val (theirHelloData, theirSealed) = withTimeout(30_000) {
                withContext(Dispatchers.IO) {
                    val s = connect()
                    socket = s
                    s.soTimeout = 30_000
                    s.tcpNoDelay = true
                    val out = DataOutputStream(s.getOutputStream().buffered())
                    val input = DataInputStream(s.getInputStream().buffered())
                    coroutineScope {
                        val writer = async(Dispatchers.IO) {
                            writeFrame(out, hello)
                            writeFrame(out, sealed)
                        }
                        val h = readFrame(input)
                        val d = readFrame(input)
                        writer.await()
                        h to d
                    }
                }
            }

            val theirHello = runCatching { JSONObject(String(theirHelloData, Charsets.UTF_8)) }.getOrNull()
                ?: throw SyncException(SyncError.INVALID_DATA)
            if (theirHello.optInt("v", -1) != PROTOCOL_VERSION) throw SyncException(SyncError.INCOMPATIBLE_VERSION)
            if (theirHello.optString("room") != tag) throw SyncException(SyncError.DIFFERENT_ROOM)
            val plain = withContext(Dispatchers.Default) { SyncCrypto.open(theirSealed, key) }
            val remote = runCatching { SyncPayload.fromJson(JSONObject(String(plain, Charsets.UTF_8))) }.getOrNull()
                ?: throw SyncException(SyncError.INVALID_DATA)
            if (remote.format != 1) throw SyncException(SyncError.INCOMPATIBLE_VERSION)

            // Si riparte dai dati locali di adesso: potrebbero essere cambiati durante lo scambio.
            apply(currentPayload().merged(remote))

            val theirId = theirHello.optString("id")
            lastSyncByPeer = lastSyncByPeer + (theirId to System.currentTimeMillis())
            val o = JSONObject()
            lastSyncByPeer.forEach { (k, v) -> o.put(k, v) }
            AppContext.prefs.edit().putString("sync.lastSyncByPeer", o.toString()).apply()
            lastMessage = "Sincronizzato con ${theirHello.optString("name", "un dispositivo")}."
            lastError = null
        } catch (e: Throwable) {
            val error = (e as? SyncException)?.error
                ?: if (e is kotlinx.coroutines.TimeoutCancellationException || e is java.net.SocketTimeoutException) SyncError.TIMEOUT
                else if (e is EOFException) SyncError.CONNECTION_CLOSED else null
            val alwaysShown = error == SyncError.WRONG_PASSWORD || error == SyncError.INCOMPATIBLE_VERSION || error == SyncError.DIFFERENT_ROOM
            if (reportAllErrors || alwaysShown) lastError = error?.message ?: "La connessione si è interrotta."
        } finally {
            val s = socket
            if (s != null) withContext(Dispatchers.IO) { runCatching { s.close() } }
        }
    }

    // --- dati locali ---

    private fun currentPayload(): SyncPayload {
        val edits = HashMap<String, StampedMetadata>()
        for ((path, value) in MetadataStore.edits) edits[path] = StampedMetadata(value, MetadataStore.editStamps[path] ?: 0.0)
        return SyncPayload(
            deviceId = deviceId, deviceName = deviceName, createdAt = nowMs(),
            progress = ProgressStore.progress, bookmarks = ProgressStore.bookmarks, sessions = ProgressStore.sessions,
            highlights = HighlightStore.all, metadataEdits = edits, hidden = IncognitoStore.hidden,
            tombstones = TombstoneStore.current,
        )
    }

    private fun apply(merged: SyncPayload) {
        if (merged.sameContent(currentPayload())) return
        TombstoneStore.replace(merged.tombstones)
        ProgressStore.applySynced(merged.progress, merged.bookmarks, merged.sessions)
        HighlightStore.applySynced(merged.highlights)
        MetadataStore.applySynced(merged.metadataEdits.mapValues { it.value.metadata }, merged.metadataEdits.mapValues { it.value.modifiedAt })
        IncognitoStore.applySynced(merged.hidden)
    }
}
