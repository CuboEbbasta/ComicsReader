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
import com.cubo.lumetto.data.FileAccess
import com.cubo.lumetto.data.HighlightStore
import com.cubo.lumetto.data.IncognitoStore
import com.cubo.lumetto.data.LibraryStore
import com.cubo.lumetto.data.LocalChanges
import com.cubo.lumetto.data.MetadataStore
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.data.SafWriter
import com.cubo.lumetto.data.TombstoneStore
import com.cubo.lumetto.data.appScope
import com.cubo.lumetto.data.onMain
import com.cubo.lumetto.model.nowMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
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

/** Errore leggibile durante un download (messaggio dell'altro dispositivo o della cartella locale). */
class SyncTransferException(message: String) : Exception(message)

/** Canale TCP a frame: lunghezza in 4 byte big-endian + contenuto (massimo 64 MB). */
private class Frames(val socket: Socket) {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())

    fun send(payload: ByteArray) {
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    fun receive(): ByteArray {
        val length = input.readInt()
        if (length <= 0 || length > SyncManager.MAX_FRAME) throw SyncException(SyncError.INVALID_DATA)
        val data = ByteArray(length)
        input.readFully(data)
        return data
    }
}

/**
 * Sincronizzazione tra dispositivi nella stessa "stanza", sulla stessa rete Wi-Fi, senza server
 * (SYNC_PROTOCOLLO.md, v2). Ogni connessione è una richiesta con risposta:
 *  - "sync": ci si scambiano i dati di lettura cifrati, li si unisce e si finisce identici;
 *  - "library": si chiede l'elenco dei fumetti dell'altro dispositivo;
 *  - "file": si scarica un fumetto dell'altro dispositivo, a blocchi cifrati, nella stessa sottocartella.
 * Compatibile al 100% con l'iPad (Lumetto 3.3.0).
 */
object SyncManager {
    const val SERVICE_TYPE = "_lumetto._tcp"
    const val PROTOCOL_VERSION = 2
    const val PLATFORM = "android"
    const val MAX_FRAME = 64 * 1024 * 1024
    private const val PASSWORD = "sync.roomPassword"

    data class Peer(val id: String, val name: String, val platform: String, val host: InetAddress, val port: Int, val serviceName: String)

    enum class Reason { DISCOVERED, CHANGE, MANUAL }

    sealed class DownloadState {
        object Queued : DownloadState()
        class Running(val fraction: Double) : DownloadState()
        class Failed(val message: String) : DownloadState()
        object Done : DownloadState()
    }

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

    /** Dispositivi conosciuti della stanza (anche quelli non in rete adesso), per le statistiche. */
    var knownDevices by mutableStateOf<Map<String, SyncDevice>>(emptyMap())
        private set

    /** Librerie degli altri dispositivi (ultima lettura) e download in corso, per percorso. */
    var remoteLibraries by mutableStateOf<Map<String, LibraryIndex>>(emptyMap())
        private set
    var loadingLibraryFor by mutableStateOf<Set<String>>(emptySet())
        private set
    var libraryErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var downloads by mutableStateOf<Map<String, DownloadState>>(emptyMap())
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
    private val downloadQueue = ArrayDeque<Pair<LibraryEntry, String>>()
    private var isDownloading = false

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
        knownDevices = runCatching {
            val o = JSONObject(p.getString("sync.devices", "{}") ?: "{}")
            val m = HashMap<String, SyncDevice>()
            o.keys().forEach { k ->
                val e = o.optJSONObject(k) ?: return@forEach
                m[k] = SyncDevice(e.optString("name", "Dispositivo"), e.optString("platform", ""), e.optDouble("lastSeen", 0.0))
            }
            m
        }.getOrDefault(emptyMap())
        nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        LocalChanges.addListener { localDataChanged() }
    }

    /** Nome da mostrare per un dispositivo (questo, uno in rete o uno conosciuto). */
    fun displayName(forDevice: String?): String {
        if (forDevice == null) return "Prima della sincronizzazione"
        if (forDevice == deviceId) return "$deviceName (questo)"
        return peers.firstOrNull { it.id == forDevice }?.name ?: knownDevices[forDevice]?.name ?: "Altro dispositivo"
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
        remoteLibraries = emptyMap()
        lastMessage = null
        lastError = null
    }

    fun renameDevice(name: String) {
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
            setAttribute("p", PLATFORM)
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
        if (attr(info, "r") != tag || attr(info, "v") != "$PROTOCOL_VERSION" || id == deviceId) return
        @Suppress("DEPRECATION")
        val host = info.host ?: return
        val peer = Peer(id, attr(info, "n") ?: "Dispositivo", attr(info, "p") ?: "", host, info.port, info.serviceName)
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

    private fun acceptLoop(socket: ServerSocket, gen: Int) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            appScope.launch(Dispatchers.IO) {
                val k = key
                if (k == null || !isActive || gen != generation) {
                    runCatching { client.close() }
                    return@launch
                }
                respond(client, k)
            }
        }
    }

    // --- richieste (chi apre la connessione) ---

    private fun myHello(op: SyncOp, tag: String) = SyncHello(PROTOCOL_VERSION, deviceId, deviceName, tag, PLATFORM, op.raw)

    private fun validate(data: ByteArray, tag: String): SyncHello {
        val hello = runCatching { SyncHello.fromJson(JSONObject(String(data, Charsets.UTF_8))) }.getOrNull()
            ?: throw SyncException(SyncError.INVALID_DATA)
        if (hello.v != PROTOCOL_VERSION) throw SyncException(SyncError.INCOMPATIBLE_VERSION)
        if (hello.room != tag) throw SyncException(SyncError.DIFFERENT_ROOM)
        return hello
    }

    /** Apre la connessione, manda saluto + richiesta cifrata, legge il saluto della risposta e poi lascia fare a [block]. */
    private suspend fun <T> request(op: SyncOp, peer: Peer, body: ByteArray, key: ByteArray, block: (Frames, SyncHello) -> T): T {
        val tag = SyncCrypto.roomTag(roomName ?: throw SyncException(SyncError.DIFFERENT_ROOM))
        return withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(peer.host, peer.port), 10_000)
                socket.soTimeout = 30_000
                socket.tcpNoDelay = true
                val frames = Frames(socket)
                frames.send(myHello(op, tag).toJson().toString().toByteArray(Charsets.UTF_8))
                frames.send(SyncCrypto.seal(body, key))
                val theirs = validate(frames.receive(), tag)
                block(frames, theirs)
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private fun parsePayload(plain: ByteArray): SyncPayload {
        val payload = runCatching { SyncPayload.fromJson(JSONObject(String(plain, Charsets.UTF_8))) }.getOrNull()
            ?: throw SyncException(SyncError.INVALID_DATA)
        if (payload.format != 2) throw SyncException(SyncError.INCOMPATIBLE_VERSION)
        return payload
    }

    private suspend fun sync(peer: Peer, reason: Reason) {
        val k = key ?: return
        if (!isActive || peer.id in syncingPeerIds) return
        syncingPeerIds = syncingPeerIds + peer.id
        try {
            val body = withContext(Dispatchers.Default) { currentPayload().toJson().toString().toByteArray(Charsets.UTF_8) }
            val (remote, hello) = request(SyncOp.SYNC, peer, body, k) { frames, h -> parsePayload(SyncCrypto.open(frames.receive(), k)) to h }
            finishSync(remote, hello)
        } catch (e: Throwable) {
            report(e, reason == Reason.MANUAL)
        } finally {
            syncingPeerIds = syncingPeerIds - peer.id
        }
    }

    /** Chiede l'elenco dei fumetti di un dispositivo. */
    fun loadLibrary(peerId: String) {
        val peer = peers.firstOrNull { it.id == peerId } ?: run {
            libraryErrors = libraryErrors + (peerId to "Il dispositivo non è in rete.")
            return
        }
        val k = key ?: return
        if (peerId in loadingLibraryFor) return
        loadingLibraryFor = loadingLibraryFor + peerId
        libraryErrors = libraryErrors - peerId
        appScope.launch {
            try {
                val index = request(SyncOp.LIBRARY, peer, """{"op":"library"}""".toByteArray(), k) { frames, _ ->
                    LibraryIndex.fromJson(JSONObject(String(SyncCrypto.open(frames.receive(), k), Charsets.UTF_8)))
                }
                remoteLibraries = remoteLibraries + (peerId to index)
            } catch (e: Throwable) {
                libraryErrors = libraryErrors + (peerId to describe(e))
            } finally {
                loadingLibraryFor = loadingLibraryFor - peerId
            }
        }
    }

    // --- download dei fumetti ---

    /** Mette in coda i download (uno alla volta). I file vanno nella stessa sottocartella. */
    fun download(entries: List<LibraryEntry>, peerId: String) {
        for (entry in entries) {
            val state = downloads[entry.path]
            if (state is DownloadState.Queued || state is DownloadState.Running) continue
            downloads = downloads + (entry.path to DownloadState.Queued)
            downloadQueue.addLast(entry to peerId)
        }
        processDownloads()
    }

    private fun setDownload(path: String, state: DownloadState) = onMain { downloads = downloads + (path to state) }

    private fun processDownloads() {
        if (isDownloading) return
        isDownloading = true
        appScope.launch {
            var installedAny = false
            while (true) {
                val (entry, peerId) = downloadQueue.removeFirstOrNull() ?: break
                val peer = peers.firstOrNull { it.id == peerId }
                if (peer == null) {
                    downloads = downloads + (entry.path to DownloadState.Failed("Il dispositivo non è più in rete."))
                    continue
                }
                if (downloadOne(entry, peer)) installedAny = true
            }
            isDownloading = false
            if (installedAny) LibraryStore.refresh()
        }
    }

    private fun partialFile(peerId: String, path: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$peerId|$path".toByteArray(Charsets.UTF_8))
        val dir = File(AppContext.context.cacheDir, "LumettoTransfers").apply { mkdirs() }
        return File(dir, SyncCrypto.hex(digest).take(32) + ".part")
    }

    private suspend fun downloadOne(entry: LibraryEntry, peer: Peer): Boolean {
        val k = key ?: return false
        if (!SyncFiles.isSafe(entry.path)) {
            downloads = downloads + (entry.path to DownloadState.Failed("Percorso non valido."))
            return false
        }
        val root = FileAccess.rootUri
        if (root == null) {
            downloads = downloads + (entry.path to DownloadState.Failed("Su questo dispositivo non è scelta una cartella dei fumetti."))
            return false
        }
        if (!FileAccess.canWrite) {
            downloads = downloads + (entry.path to DownloadState.Failed("Serve il permesso di scrittura: in Impostazioni scegli di nuovo la cartella dei fumetti."))
            return false
        }
        downloads = downloads + (entry.path to DownloadState.Running(0.0))
        val partial = partialFile(peer.id, entry.path)
        var offset = if (partial.exists()) partial.length() else 0L
        if (offset > entry.size) offset = 0L
        return try {
            val requestBody = FileRequest(entry.path, offset).toJson().toString().toByteArray(Charsets.UTF_8)
            request(SyncOp.FILE, peer, requestBody, k) { frames, _ ->
                receiveFile(frames, partial, entry.path, k) { setDownload(entry.path, DownloadState.Running(it)) }
            }
            withContext(Dispatchers.IO) { SafWriter.install(root, entry.path, partial) }
            downloads = downloads + (entry.path to DownloadState.Done)
            true
        } catch (e: Throwable) {
            downloads = downloads + (entry.path to DownloadState.Failed(describe(e)))
            false
        }
    }

    /** Lato di chi scarica: scrive nel file parziale, riprendendo da dove era arrivato. */
    private fun receiveFile(frames: Frames, partial: File, expectedPath: String, key: ByteArray, progress: (Double) -> Unit): File {
        val header = FileHeader.fromJson(JSONObject(String(SyncCrypto.open(frames.receive(), key), Charsets.UTF_8)))
        header.error?.let { throw SyncTransferException(it) }
        if (header.path != expectedPath) throw SyncException(SyncError.INVALID_DATA)
        RandomAccessFile(partial, "rw").use { raf ->
            raf.setLength(header.offset)
            raf.seek(header.offset)
            var written = header.offset
            var lastReport = 0L
            while (written < header.size) {
                val chunk = SyncCrypto.open(frames.receive(), key)
                raf.write(chunk)
                written += chunk.size
                val now = System.currentTimeMillis()
                if (now - lastReport > 200) {
                    lastReport = now
                    progress(if (header.size > 0) written.toDouble() / header.size else 1.0)
                }
            }
            if (written != header.size) throw SyncException(SyncError.INVALID_DATA)
        }
        progress(1.0)
        return partial
    }

    // --- risposte (chi riceve la connessione) ---

    private suspend fun respond(socket: Socket, key: ByteArray) {
        val tag = SyncCrypto.roomTag(roomName ?: run { runCatching { socket.close() }; return })
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            val frames = Frames(socket)
            val theirHello = validate(frames.receive(), tag)
            val sealedRequest = frames.receive()
            val op = SyncOp.from(theirHello.op) ?: SyncOp.SYNC
            frames.send(myHello(op, tag).toJson().toString().toByteArray(Charsets.UTF_8))

            val body = try {
                SyncCrypto.open(sealedRequest, key)
            } catch (e: SyncException) {
                // Password diversa: si risponde comunque con la nostra chiave, così l'altro lo scopre subito.
                runCatching { frames.send(SyncCrypto.seal("{}".toByteArray(), key)) }
                throw e
            }

            when (op) {
                SyncOp.SYNC -> {
                    val remote = parsePayload(body)
                    val mine = withContext(Dispatchers.Main) { currentPayload() }
                    frames.send(SyncCrypto.seal(mine.toJson().toString().toByteArray(Charsets.UTF_8), key))
                    withContext(Dispatchers.Main) { finishSync(remote, theirHello) }
                }
                SyncOp.LIBRARY -> {
                    val index = withContext(Dispatchers.Main) { localLibraryIndex() }
                    frames.send(SyncCrypto.seal(index.toJson().toString().toByteArray(Charsets.UTF_8), key))
                }
                SyncOp.FILE -> serveFile(FileRequest.fromJson(JSONObject(String(body, Charsets.UTF_8))), frames, key)
            }
        } catch (e: Throwable) {
            report(e, false)
        } finally {
            withContext(Dispatchers.IO) { runCatching { socket.close() } }
        }
    }

    /** Lato di chi ha il file: header + blocchi cifrati da 1 MiB. Solo file della propria libreria. */
    private fun serveFile(request: FileRequest, frames: Frames, key: ByteArray) {
        fun refuse(message: String) {
            frames.send(SyncCrypto.seal(FileHeader(request.path, 0, 0, message).toJson().toString().toByteArray(Charsets.UTF_8), key))
        }
        val comic = if (SyncFiles.isSafe(request.path)) LibraryStore.comicIncludingHidden(request.path) else null
        if (comic == null) return refuse("Il fumetto non è più nella libreria dell'altro dispositivo.")
        val pfd = try { FileAccess.openFd(comic.uri) } catch (e: Exception) { return refuse("File non leggibile sull'altro dispositivo.") }
        pfd.use {
            val size = pfd.statSize.takeIf { it >= 0 } ?: comic.fileSizeBytes
            val offset = if (request.offset in 0..size) request.offset else 0L
            FileInputStream(pfd.fileDescriptor).use { input ->
                input.channel.position(offset)
                frames.send(SyncCrypto.seal(FileHeader(request.path, size, offset, null).toJson().toString().toByteArray(Charsets.UTF_8), key))
                val buffer = ByteArray(SyncFiles.CHUNK_SIZE)
                var remaining = size - offset
                while (remaining > 0) {
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    var read = 0
                    while (read < want) {
                        val n = input.read(buffer, read, want - read)
                        if (n < 0) throw EOFException()
                        read += n
                    }
                    frames.send(SyncCrypto.seal(buffer.copyOf(want), key))
                    remaining -= want
                }
            }
        }
    }

    private fun localLibraryIndex(): LibraryIndex = LibraryIndex(
        deviceId, deviceName, PLATFORM, nowMs(),
        LibraryStore.allComicsIncludingHidden().map { LibraryEntry(it.relativePath, it.fileSizeBytes, it.modifiedMs.toDouble()) },
    )

    // --- unione dei dati ---

    private fun finishSync(remote: SyncPayload, hello: SyncHello) {
        apply(currentPayload().merged(remote))
        lastSyncByPeer = lastSyncByPeer + (hello.id to System.currentTimeMillis())
        val o = JSONObject()
        lastSyncByPeer.forEach { (k, v) -> o.put(k, v) }
        AppContext.prefs.edit().putString("sync.lastSyncByPeer", o.toString()).apply()
        lastMessage = "Sincronizzato con ${hello.name.ifEmpty { "un dispositivo" }}."
        lastError = null
    }

    private fun describe(e: Throwable): String = when (e) {
        is SyncException -> e.error.message
        is SyncTransferException -> e.message ?: "Download non riuscito."
        is kotlinx.coroutines.TimeoutCancellationException, is java.net.SocketTimeoutException -> SyncError.TIMEOUT.message
        is EOFException -> SyncError.CONNECTION_CLOSED.message
        is java.io.IOException -> e.message ?: SyncError.CONNECTION_CLOSED.message
        else -> "La connessione si è interrotta."
    }

    private fun report(e: Throwable, always: Boolean) {
        val error = (e as? SyncException)?.error
        val important = error == SyncError.WRONG_PASSWORD || error == SyncError.INCOMPATIBLE_VERSION || error == SyncError.DIFFERENT_ROOM
        if (always || important) onMain { lastError = describe(e) }
    }

    // --- dati locali ---

    private fun currentPayload(): SyncPayload {
        val edits = HashMap<String, StampedMetadata>()
        for ((path, value) in MetadataStore.edits) edits[path] = StampedMetadata(value, MetadataStore.editStamps[path] ?: 0.0)
        val devices = HashMap(knownDevices)
        devices[deviceId] = SyncDevice(deviceName, PLATFORM, nowMs())
        return SyncPayload(
            deviceId = deviceId, deviceName = deviceName, createdAt = nowMs(),
            progress = ProgressStore.progress, bookmarks = ProgressStore.bookmarks, sessions = ProgressStore.sessions,
            highlights = HighlightStore.all, metadataEdits = edits, hidden = IncognitoStore.hidden,
            tombstones = TombstoneStore.current, devices = devices,
        )
    }

    private fun apply(merged: SyncPayload) {
        if (merged.devices != knownDevices) {
            knownDevices = merged.devices
            val o = JSONObject()
            merged.devices.forEach { (id, d) -> o.put(id, JSONObject().put("name", d.name).put("platform", d.platform).put("lastSeen", d.lastSeen)) }
            AppContext.prefs.edit().putString("sync.devices", o.toString()).apply()
        }
        // L'orario "visto" di questo dispositivo cambia a ogni chiamata: si confronta senza.
        if (merged.copy(devices = emptyMap()).sameContent(currentPayload().copy(devices = emptyMap()))) return
        TombstoneStore.replace(merged.tombstones)
        ProgressStore.applySynced(merged.progress, merged.bookmarks, merged.sessions)
        HighlightStore.applySynced(merged.highlights)
        MetadataStore.applySynced(merged.metadataEdits.mapValues { it.value.metadata }, merged.metadataEdits.mapValues { it.value.modifiedAt })
        IncognitoStore.applySynced(merged.hidden)
    }
}
