package com.meshlink.app.mesh

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.meshlink.app.data.ChatRepository
import com.meshlink.app.data.SessionStore
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.concurrent.thread

enum class CallPhase {
    IDLE,
    /** Outgoing call: ringing on the other phone. */
    DIALING,
    /** Incoming call: this phone is ringing. */
    RINGING,
    /** Both sides accepted; opening the audio link. */
    CONNECTING,
    /** In call, audio flowing. */
    ACTIVE,
    /** Just ended (shown briefly, then cleared). */
    ENDED,
}

data class CallUiState(
    val id: String = "",
    val peer: String = "",
    val name: String = "",
    val outgoing: Boolean = true,
    val phase: CallPhase = CallPhase.IDLE,
    val muted: Boolean = false,
    val speakerOn: Boolean = true,
    val connectedAt: Long = 0L,
    val note: String = "",
    /** "Wi-Fi" (same network) or "Wi-Fi Direct" (phone-to-phone group). */
    val transport: String = "",
)

/**
 * Calls without internet.
 *
 * Signalling (ring / accept / reject / hang up) travels as mesh frames over
 * Wi-Fi Direct or Bluetooth LE. The voice itself is a direct 8 kHz audio link
 * between the two phones over the Wi-Fi Direct group — no router, no SIM and
 * no internet. When a Wi-Fi Direct group is not available the UI offers a
 * normal SIM call instead.
 */
object CallManager {

    private const val TAG = "MhzalyCall"
    private const val AUDIO_PORT = 8990
    private const val SAMPLE_RATE = 8_000
    private const val CHUNK_BYTES = 320 // 20 ms of 16-bit mono
    private const val MAX_FRAME = 4_096
    private const val RING_TIMEOUT_MS = 45_000L

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    @Volatile
    private var engine: MeshEngine? = null

    @Volatile
    private var appContext: Context? = null

    private val handler = Handler(Looper.getMainLooper())

    private var socket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    @Volatile private var peerAudioIp: String? = null
    private var output: DataOutputStream? = null
    private var serverSocket: ServerSocket? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var micThread: Thread? = null
    private var readThread: Thread? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    @Volatile private var muted = false
    @Volatile private var ended = false
    @Volatile private var active = false
    /** Invite IDs already handled, so replayed frames cannot ring again. */
    private val seenInvites = mutableMapOf<String, Long>()

    var onIncomingCall: ((CallUiState) -> Unit)? = null
    var onCallEnded: (() -> Unit)? = null

    private val timeout = Runnable {
        when (_state.value.phase) {
            CallPhase.DIALING -> finish("NO_ANSWER", "No answer")
            CallPhase.RINGING -> finish("MISSED", "Missed call", missed = true)
            else -> Unit
        }
    }

    /** Clears the short-lived "ended / failed" banner shown by the call UI. */
    private val clearEnded = Runnable {
        if (_state.value.phase == CallPhase.ENDED) _state.value = CallUiState()
    }

    // ------------------------------------------------------------- lifecycle

    fun attach(context: Context, mesh: MeshEngine) {
        appContext = context.applicationContext
        engine = mesh
        mesh.onControl = ::onControl
    }

    fun detach() {
        if (_state.value.phase != CallPhase.IDLE && _state.value.phase != CallPhase.ENDED) {
            finish("FAILED", "Call service stopped")
        }
        engine?.onControl = null
        engine = null
    }

    /** Place an outgoing call over the mesh. Returns false when offline. */
    fun placeCall(peer: String, name: String): Boolean {
        val phase = _state.value.phase
        if (phase != CallPhase.IDLE && phase != CallPhase.ENDED) return false
        val mesh = engine
        val ctx = appContext
        if (mesh == null || ctx == null) {
            showFailure("The call service is still starting. Try again in a moment.", peer, name)
            return false
        }
        if (!micGranted()) {
            showFailure("Microphone permission is needed for a call. Allow it and try again.", peer, name)
            return false
        }
        // A call needs some direct Wi-Fi path: either a Wi-Fi Direct group or
        // both phones on the same Wi-Fi network / hotspot.
        val overLan = mesh.lanIsActive && !mesh.localLanIp().isNullOrBlank()
        if (!mesh.wifiHasGroup && !overLan) {
            showFailure("No nearby call path. Move closer, or connect both phones to the same Wi-Fi.", peer, name)
            return false
        }

        peerAudioIp = null
        ended = false
        val id = UUID.randomUUID().toString().take(8)
        _state.value = CallUiState(
            id = id,
            peer = peer,
            name = name.ifBlank { peer },
            outgoing = true,
            phase = CallPhase.DIALING,
            note = if (overLan) "Calling over Wi-Fi…" else "Calling over Wi-Fi Direct…",
            transport = if (overLan) "Wi-Fi" else "Wi-Fi Direct",
        )
        val sent = runCatching {
            mesh.sendControl(peer, "invite", id, SessionStore.displayName(ctx))
        }.getOrDefault(false)
        if (!sent) {
            showFailure("Could not reach $peer over the local network.", peer, name)
            return false
        }
        scheduleTimeout()
        return true
    }

    /** Answer an incoming call. */
    fun accept() {
        val s = _state.value
        if (s.phase != CallPhase.RINGING) return
        if (!micGranted()) {
            engine?.sendControl(s.peer, "reject", s.id, nameForUs())
            finish("FAILED", "Microphone permission is needed to answer", missed = true)
            return
        }
        stopRinging()
        engine?.sendControl(s.peer, "accept", s.id, nameForUs())
        _state.value = s.copy(phase = CallPhase.CONNECTING, note = "Connecting…")
        openAudio()
    }

    /** Reject an incoming call. */
    fun decline() {
        val s = _state.value
        if (s.phase != CallPhase.RINGING) return
        engine?.sendControl(s.peer, "reject", s.id, nameForUs())
        finish("DECLINED", "Declined", missed = true)
    }

    /** Hang up or cancel. */
    fun hangUp() {
        val s = _state.value
        if (s.phase == CallPhase.IDLE || s.phase == CallPhase.ENDED) return
        engine?.sendControl(s.peer, "end", s.id, nameForUs())
        val status = when {
            s.phase == CallPhase.DIALING -> "NO_ANSWER"
            s.connectedAt > 0 -> "COMPLETED"
            else -> "CANCELLED"
        }
        finish(status, "Call ended")
    }

    fun toggleMute() {
        if (!callIsLive()) return
        muted = !muted
        _state.value = _state.value.copy(muted = muted)
    }

    fun toggleSpeaker() {
        // Never leave the speakerphone on after a call has ended.
        if (!callIsLive()) return
        val on = !_state.value.speakerOn
        _state.value = _state.value.copy(speakerOn = on)
        audioManager()?.let { am ->
            runCatching { am.isSpeakerphoneOn = on }
        }
    }

    fun clearEnded() {
        handler.removeCallbacks(clearEnded)
        if (_state.value.phase == CallPhase.ENDED) _state.value = CallUiState()
    }

    private fun callIsLive(): Boolean = when (_state.value.phase) {
        CallPhase.DIALING, CallPhase.RINGING, CallPhase.CONNECTING, CallPhase.ACTIVE -> true
        else -> false
    }

    private fun micGranted(): Boolean {
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * A call could not even start: show the reason inside the full-screen call
     * UI instead of silently returning false and leaving the user guessing.
     */
    private fun showFailure(message: String, peer: String, name: String, outgoing: Boolean = true) {
        ended = true
        active = false
        muted = false
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(clearEnded)
        stopRinging()
        _state.value = CallUiState(
            peer = peer,
            name = name.ifBlank { peer },
            outgoing = outgoing,
            phase = CallPhase.ENDED,
            note = message,
        )
        handler.postDelayed(clearEnded, 4_000)
    }

    // ------------------------------------------------------------- signalling

    private fun onControl(
        from: String,
        action: String,
        callId: String,
        name: String,
        peerIp: String,
        stamp: String,
    ) {
        val s = _state.value
        when (action) {
            "invite" -> {
                // Replayed/stale local invites were causing calls with nobody
                // on the other end. Only fresh, unseen invites may ring.
                val age = runCatching {
                    abs(System.currentTimeMillis() / 1000L - java.lang.Long.parseLong(stamp, 36))
                }.getOrDefault(Long.MAX_VALUE)
                if (age > 60L) return
                if (seenInvites.containsKey(callId)) return
                seenInvites[callId] = System.currentTimeMillis()
                if (seenInvites.size > 32) {
                    seenInvites.entries.removeAll { System.currentTimeMillis() - it.value > 120_000L }
                }
                // ENDED is a short-lived banner, not a busy call.
                if (s.phase != CallPhase.IDLE && s.phase != CallPhase.ENDED) {
                    engine?.sendControl(from, "reject", callId, nameForUs())
                    return
                }
                if (!micGranted()) {
                    // Do not ring for a call we cannot answer: tell the caller
                    // and show a visible reason on this phone too.
                    engine?.sendControl(from, "reject", callId, nameForUs())
                    showFailure(
                        "Missed call: microphone permission is needed. Allow it and try again.",
                        from,
                        name,
                        outgoing = false,
                    )
                    return
                }
                // Only copy the peer's audio address once we accept the invite,
                // otherwise a late/duplicate invite could overwrite the IP of
                // the call that is already in progress.
                peerAudioIp = peerIp.takeIf { it.isNotBlank() }
                ended = false
                val peerName = ChatRepository.contactName(from)
                    ?: name.ifBlank { from }
                val state = CallUiState(
                    id = callId,
                    peer = from,
                    name = peerName,
                    outgoing = false,
                    phase = CallPhase.RINGING,
                    note = "Incoming call",
                    transport = if (peerIp.isNotBlank()) "Wi-Fi" else "Wi-Fi Direct",
                )
                _state.value = state
                startRinging()
                scheduleTimeout()
                onIncomingCall?.invoke(state)
            }
            "accept" -> {
                if (s.id == callId && s.outgoing && s.phase == CallPhase.DIALING) {
                    peerAudioIp = peerIp.takeIf { it.isNotBlank() }
                    handler.removeCallbacks(timeout)
                    _state.value = s.copy(phase = CallPhase.CONNECTING, note = "Connecting…")
                    openAudio()
                }
            }
            "reject" -> {
                if (s.id == callId) finish("DECLINED", "Call declined")
            }
            "end" -> {
                if (s.id == callId) {
                    val status = if (s.connectedAt > 0) "COMPLETED" else if (s.outgoing) "NO_ANSWER" else "CANCELLED"
                    finish(status, "Call ended")
                }
            }
        }
    }

    private fun scheduleTimeout() {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, RING_TIMEOUT_MS)
    }

    // ----------------------------------------------------------------- audio

    private fun openAudio() {
        // A hangup can happen while the user is tapping Accept. Do not resurrect
        // sockets or audio for a call that is no longer connecting.
        if (ended || _state.value.phase != CallPhase.CONNECTING) return

        // Same Wi-Fi network: plain UDP in both directions. The IP came over
        // the call signalling, so no Wi-Fi Direct group is needed.
        val lanIp = peerAudioIp
        if (lanIp != null && lanIp.isNotBlank()) {
            openLanAudio(lanIp)
            return
        }

        val mesh = engine ?: run {
            finish("FAILED", "Call service is no longer running")
            return
        }
        val host = mesh.wifiIsGroupOwner
        val ownerIp = mesh.wifiGroupOwnerIp
        if (!host && ownerIp.isNullOrBlank()) {
            finish("FAILED", "Wi-Fi Direct group is not ready. Try the call again in a moment.")
            return
        }

        thread(name = "meshlink-call-audio", isDaemon = true) {
            try {
                val s = if (host) {
                    val ss = ServerSocket(AUDIO_PORT)
                    ss.soTimeout = 20_000
                    serverSocket = ss
                    ss.accept()
                } else {
                    connectWithRetry(ownerIp)
                }
                if (ended || _state.value.phase != CallPhase.CONNECTING || appContext == null) {
                    runCatching { s.close() }
                    return@thread
                }
                socket = s
                s.tcpNoDelay = true
                output = DataOutputStream(s.getOutputStream())
                active = true
                prepareAudio()
                startPlayback()
                startMic()
                startReader()
                markActive()
            } catch (t: Throwable) {
                Log.w(TAG, "audio link failed", t)
                if (!ended) {
                    finish(
                        "FAILED",
                        if (t.message.isNullOrBlank()) "Audio link failed: radio busy"
                        else "Audio link failed: ${t.message}",
                    )
                }
            }
        }
    }

    /** Moves CONNECTING -> ACTIVE only while this call is still the live one. */
    private fun markActive() {
        val s = _state.value
        if (ended || s.phase != CallPhase.CONNECTING) return
        _state.value = s.copy(
            phase = CallPhase.ACTIVE,
            connectedAt = System.currentTimeMillis(),
            note = "",
        )
    }

    /**
     * Voice over the local Wi-Fi network (router or hotspot).
     *
     * Both phones bind UDP 8990 and send 20 ms of 8 kHz audio to each other's
     * address. No handshake, no server: the first packet is the handshake.
     */
    private fun openLanAudio(peerIp: String) {
        prepareAudio()
        _state.value = _state.value.copy(phase = CallPhase.CONNECTING, note = "Connecting…")

        thread(name = "meshlink-call-lan", isDaemon = true) {
            try {
                if (ended || _state.value.phase != CallPhase.CONNECTING) return@thread
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(AUDIO_PORT))
                    soTimeout = 1_000
                }
                udpSocket = sock
                startPlayback()
                startLanMic(sock, peerIp)
                startLanReader(sock)

                // If no audio arrives in 10 seconds the network is blocking
                // phone-to-phone traffic (some public Wi-Fi does this).
                Thread.sleep(10_000)
                if (!ended && _state.value.phase != CallPhase.ACTIVE) {
                    finish(
                        "FAILED",
                        "No audio over Wi-Fi. This network may block phone-to-phone traffic.",
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "LAN audio failed", t)
                if (!ended) finish("FAILED", "Wi-Fi audio failed: ${t.message ?: "port busy"}")
            }
        }
    }

    private fun startLanMic(sock: DatagramSocket, peerIp: String) {
        val rec = buildRecord()
        record = rec
        rec.startRecording()
        val target = runCatching { InetAddress.getByName(peerIp) }.getOrNull()
        if (target == null) {
            finish("FAILED", "Bad call address")
            return
        }
        micThread = thread(name = "meshlink-call-lan-tx", isDaemon = true) {
            val buffer = ByteArray(CHUNK_BYTES)
            try {
                while (!ended && !sock.isClosed) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    if (muted) continue
                    runCatching {
                        sock.send(DatagramPacket(buffer, read, target, AUDIO_PORT))
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "LAN mic failed", t)
            }
        }
    }

    private fun startLanReader(sock: DatagramSocket) {
        readThread = thread(name = "meshlink-call-lan-rx", isDaemon = true) {
            val buffer = ByteArray(MAX_FRAME)
            var first = true
            while (!ended && !sock.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    if (packet.length > 0) {
                        play(packet.data, packet.length)
                        if (first) {
                            first = false
                            active = true
                            markActive()
                        }
                    }
                } catch (t: SocketTimeoutException) {
                    // Keep waiting; the watchdog in openLanAudio decides.
                } catch (t: Throwable) {
                    if (!ended) Log.d(TAG, "LAN audio rx ended: ${t.message}")
                    break
                }
            }
        }
    }

    private fun connectWithRetry(ownerIp: String?): Socket {
        if (ownerIp == null) throw IllegalStateException("No Wi-Fi Direct group")
        var last: Throwable? = null
        repeat(8) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ownerIp, AUDIO_PORT), 4_000)
                return s
            } catch (t: Throwable) {
                last = t
                Thread.sleep(400)
            }
        }
        throw last ?: IllegalStateException("Cannot reach $ownerIp")
    }

    private fun startReader() {
        val s = socket ?: return
        readThread = thread(name = "meshlink-call-rx", isDaemon = true) {
            try {
                val input = DataInputStream(s.getInputStream())
                while (!ended && !s.isClosed) {
                    val len = input.readUnsignedShort()
                    if (len <= 0 || len > MAX_FRAME) break
                    val data = ByteArray(len)
                    input.readFully(data, 0, len)
                    play(data, len)
                }
            } catch (t: Throwable) {
                if (!ended) Log.d(TAG, "call rx ended: ${t.message}")
            } finally {
                if (!ended) finish("COMPLETED", "Call ended")
            }
        }
    }

    private fun startMic() {
        val rec = buildRecord()
        record = rec
        rec.startRecording()
        micThread = thread(name = "meshlink-call-tx", isDaemon = true) {
            val buffer = ByteArray(CHUNK_BYTES)
            try {
                while (!ended) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    if (muted) continue
                    write(buffer, read)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "mic read failed", t)
            }
        }
    }

    private fun write(data: ByteArray, length: Int) {
        val out = output ?: return
        try {
            synchronized(this) {
                out.writeShort(length)
                out.write(data, 0, length)
                out.flush()
            }
        } catch (t: Throwable) {
            if (!ended) Log.d(TAG, "call tx ended: ${t.message}")
        }
    }

    private fun buildRecord(): AudioRecord {
        val ctx = appContext
        if (ctx != null && ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission missing")
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 8))
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        return recorder
    }

    private fun startPlayback() {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        player.play()
        track = player
    }

    private fun play(data: ByteArray, length: Int = data.size) {
        val player = track ?: return
        runCatching { player.write(data, 0, length) }
    }

    private fun prepareAudio() {
        val am = audioManager()
        runCatching {
            am?.mode = AudioManager.MODE_IN_COMMUNICATION
            am?.isSpeakerphoneOn = _state.value.speakerOn
        }
    }

    private fun restoreAudio() {
        runCatching {
            val am = audioManager() ?: return@runCatching
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun audioManager(): AudioManager? =
        appContext?.getSystemService(AudioManager::class.java)

    // -------------------------------------------------------------- ringtone

    private fun startRinging() {
        val ctx = appContext ?: return
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }
        runCatching {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            val v = vibrator
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(longArrayOf(0, 800, 800), 0)
                }
            }
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    // ---------------------------------------------------------------- finish

    @Synchronized
    private fun finish(status: String, note: String, missed: Boolean = false) {
        if (ended) return
        ended = true
        active = false
        muted = false
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(clearEnded)
        val s = _state.value

        stopRinging()
        closeAudio()
        restoreAudio()

        if (s.id.isNotEmpty() && s.phase != CallPhase.ENDED) {
            val duration = if (s.connectedAt > 0) {
                ((System.currentTimeMillis() - s.connectedAt) / 1000L).toInt()
            } else 0
            ChatRepository.logCall(
                peer = s.peer,
                name = s.name,
                outgoing = s.outgoing,
                missed = missed || (!s.outgoing && status != "COMPLETED"),
                transport = if (s.transport == "Wi-Fi") "WIFI_LAN" else "WIFI_DIRECT",
                timestamp = System.currentTimeMillis(),
                durationSec = duration,
                status = status,
            )
        }

        _state.value = s.copy(phase = CallPhase.ENDED, note = note, muted = false, speakerOn = true)
        onCallEnded?.invoke()
        handler.postDelayed(clearEnded, 2_500)
    }

    private fun closeAudio() {
        runCatching { micThread?.interrupt() }
        micThread = null
        runCatching {
            record?.stop()
            record?.release()
        }
        record = null

        runCatching { readThread?.interrupt() }
        readThread = null

        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        runCatching { socket?.close() }
        socket = null
        output = null
        peerAudioIp = null

        runCatching {
            track?.stop()
            track?.release()
        }
        track = null
    }

    private fun nameForUs(): String {
        val ctx = appContext ?: return ""
        return SessionStore.displayName(ctx)
    }
}
