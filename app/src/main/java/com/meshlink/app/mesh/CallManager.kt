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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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

    private const val TAG = "MeshLinkCall"
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

    var onIncomingCall: ((CallUiState) -> Unit)? = null
    var onCallEnded: (() -> Unit)? = null

    private val timeout = Runnable {
        when (_state.value.phase) {
            CallPhase.DIALING -> finish("NO_ANSWER", "No answer")
            CallPhase.RINGING -> finish("MISSED", "Missed call", missed = true)
            else -> Unit
        }
    }

    // ------------------------------------------------------------- lifecycle

    fun attach(context: Context, mesh: MeshEngine) {
        appContext = context.applicationContext
        engine = mesh
        mesh.onControl = ::onControl
    }

    fun detach() {
        engine = null
    }

    /** Place an outgoing call over the mesh. Returns false when offline. */
    fun placeCall(peer: String, name: String): Boolean {
        val mesh = engine ?: return false
        val ctx = appContext ?: return false
        if (_state.value.phase != CallPhase.IDLE) return false
        if (!mesh.wifiHasGroup) return false

        val id = UUID.randomUUID().toString()
        _state.value = CallUiState(
            id = id,
            peer = peer,
            name = name.ifBlank { peer },
            outgoing = true,
            phase = CallPhase.DIALING,
            note = "Calling over Wi-Fi Direct…",
        )
        val sent = mesh.sendControl(peer, "invite", id, SessionStore.displayName(ctx))
        if (!sent) {
            _state.value = CallUiState()
            return false
        }
        scheduleTimeout()
        return true
    }

    /** Answer an incoming call. */
    fun accept() {
        val s = _state.value
        if (s.phase != CallPhase.RINGING) return
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
        muted = !muted
        _state.value = _state.value.copy(muted = muted)
    }

    fun toggleSpeaker() {
        val on = !_state.value.speakerOn
        _state.value = _state.value.copy(speakerOn = on)
        audioManager()?.let { am ->
            runCatching { am.isSpeakerphoneOn = on }
        }
    }

    fun clearEnded() {
        if (_state.value.phase == CallPhase.ENDED) _state.value = CallUiState()
    }

    // ------------------------------------------------------------- signalling

    private fun onControl(from: String, action: String, callId: String, name: String) {
        val s = _state.value
        when (action) {
            "invite" -> {
                if (s.phase != CallPhase.IDLE) {
                    engine?.sendControl(from, "reject", callId, nameForUs())
                    return
                }
                val peerName = ChatRepository.contactName(from)
                    ?: name.ifBlank { from }
                val state = CallUiState(
                    id = callId,
                    peer = from,
                    name = peerName,
                    outgoing = false,
                    phase = CallPhase.RINGING,
                    note = "Incoming call",
                )
                _state.value = state
                startRinging()
                scheduleTimeout()
                onIncomingCall?.invoke(state)
            }
            "accept" -> {
                if (s.id == callId && s.outgoing && s.phase == CallPhase.DIALING) {
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
        val mesh = engine ?: return
        val host = mesh.wifiIsGroupOwner
        val ownerIp = mesh.wifiGroupOwnerIp
        ended = false

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
                if (ended) {
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
                _state.value = _state.value.copy(
                    phase = CallPhase.ACTIVE,
                    connectedAt = System.currentTimeMillis(),
                    note = "",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "audio link failed", t)
                if (!ended) finish("FAILED", "Audio link failed: ${t.message ?: "radio busy"}")
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
                    play(data)
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

    private fun play(data: ByteArray) {
        val player = track ?: return
        runCatching { player.write(data, 0, data.size) }
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

    private fun finish(status: String, note: String, missed: Boolean = false) {
        if (ended && _state.value.phase == CallPhase.ENDED) return
        ended = true
        active = false
        handler.removeCallbacks(timeout)
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
                transport = "WIFI_DIRECT",
                timestamp = System.currentTimeMillis(),
                durationSec = duration,
                status = status,
            )
        }

        _state.value = s.copy(phase = CallPhase.ENDED, note = note)
        onCallEnded?.invoke()
        handler.postDelayed({
            if (_state.value.phase == CallPhase.ENDED) _state.value = CallUiState()
        }, 1_500)
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
        runCatching { socket?.close() }
        socket = null
        output = null

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
