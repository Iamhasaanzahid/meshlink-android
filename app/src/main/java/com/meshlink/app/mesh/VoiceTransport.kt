package com.meshlink.app.mesh

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * VOICE OVER WI-FI DIRECT — push-to-talk audio with no SIM and no internet.
 *
 * Audio is 8 kHz, 16-bit mono PCM (the same band a phone call uses), sent in
 * small length-prefixed frames over the Wi-Fi Direct group:
 *
 *   host (group owner) : accepts clients, plays their audio and relays it to
 *                        every other client (multi-party).
 *   client             : connects to the host, plays host/peer audio and sends
 *                        its own microphone audio.
 *
 * Hold the talk button to transmit; release to listen. Works alongside the
 * text mesh on the same Wi-Fi Direct group.
 */
class VoiceTransport(private val context: Context) {

    companion object {
        private const val TAG = "MeshLinkVoice"
        const val PORT = 8989
        private const val SAMPLE_RATE = 8_000
        private const val CHUNK_BYTES = 640 // 40 ms of 16-bit mono audio
        private const val MAX_FRAME = 4_096
    }

    enum class State { IDLE, CONNECTING, READY, TALKING, ERROR }

    var onState: ((State, String) -> Unit)? = null

    private class Connection(val socket: Socket) {
        val out: DataOutputStream = DataOutputStream(socket.getOutputStream())
        val lock = Any()

        fun write(frame: ByteArray): Boolean = try {
            synchronized(lock) {
                out.writeShort(frame.size)
                out.write(frame)
                out.flush()
            }
            true
        } catch (t: Throwable) {
            false
        }

        fun close() = runCatching { socket.close() }
    }

    private val peers = CopyOnWriteArrayList<Connection>()
    private var serverSocket: ServerSocket? = null
    private var hostLink: Connection? = null

    @Volatile private var running = false
    @Volatile private var talking = false
    @Volatile private var isHost = false

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null

    val isActive: Boolean get() = running
    val isTalking: Boolean get() = talking

    // ------------------------------------------------------------- lifecycle

    fun start(asGroupOwner: Boolean, ownerIp: String?) {
        stop()
        isHost = asGroupOwner
        running = true
        emit(State.CONNECTING, if (asGroupOwner) "Starting voice group…" else "Joining voice group…")
        ensurePlayback()

        if (asGroupOwner) {
            startServer()
            emit(State.READY, "Voice ready · hold to talk")
        } else {
            if (ownerIp == null) {
                emit(State.ERROR, "No Wi-Fi Direct group. Connect a peer first.")
                running = false
                return
            }
            connectToHost(ownerIp)
        }
    }

    fun stop() {
        running = false
        talking = false
        stopCapture()
        runCatching { serverSocket?.close() }
        serverSocket = null
        hostLink?.close()
        hostLink = null
        peers.forEach { it.close() }
        peers.clear()
        releasePlayback()
        emit(State.IDLE, "Voice idle")
    }

    /** Hold-to-talk: starts the microphone and streams to the group. */
    fun startTalking(): Boolean {
        if (!running || talking) return false
        return try {
            val rec = buildRecord()
            record = rec
            rec.startRecording()
            talking = true
            emit(State.TALKING, "Transmitting…")
            captureThread = thread(name = "meshlink-voice-mic", isDaemon = true) {
                val buffer = ByteArray(CHUNK_BYTES)
                try {
                    while (running && talking) {
                        val read = rec.read(buffer, 0, buffer.size)
                        if (read > 0) sendAudio(buffer.copyOf(read))
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "microphone read failed", t)
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cannot start microphone", t)
            emit(State.ERROR, "Microphone unavailable (${t.message ?: "permission?"})")
            false
        }
    }

    fun stopTalking() {
        if (!talking) return
        talking = false
        stopCapture()
        if (running) emit(State.READY, "Voice ready · hold to talk")
    }

    // ---------------------------------------------------------------- server

    private fun startServer() {
        thread(name = "meshlink-voice-server", isDaemon = true) {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                while (running && !ss.isClosed) {
                    val socket = ss.accept()
                    val conn = Connection(socket)
                    peers.add(conn)
                    emit(State.READY, "Voice: ${peers.size} connected · hold to talk")
                    thread(name = "meshlink-voice-peer-${socket.port}", isDaemon = true) {
                        readLoop(conn) { data ->
                            play(data)
                            // Relay to every other client (not back to sender).
                            peers.forEach { if (it !== conn) it.write(data) }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "voice server stopped", t)
            }
        }
    }

    private fun connectToHost(ownerIp: String) {
        thread(name = "meshlink-voice-client", isDaemon = true) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ownerIp, PORT), 4000)
                val conn = Connection(socket)
                hostLink = conn
                emit(State.READY, "Voice connected · hold to talk")
                readLoop(conn) { data -> play(data) }
                if (running) emit(State.ERROR, "Voice link closed")
            } catch (t: Throwable) {
                Log.w(TAG, "voice connect failed", t)
                if (running) emit(State.ERROR, "Could not join voice group")
            }
        }
    }

    private fun readLoop(conn: Connection, onAudio: (ByteArray) -> Unit) {
        try {
            val input = DataInputStream(conn.socket.getInputStream())
            while (running && !conn.socket.isClosed) {
                val len = input.readUnsignedShort()
                if (len <= 0 || len > MAX_FRAME) break
                val data = ByteArray(len)
                input.readFully(data, 0, len)
                onAudio(data)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "voice read ended: ${t.message}")
        } finally {
            peers.remove(conn)
            if (hostLink === conn) hostLink = null
            conn.close()
        }
    }

    // ----------------------------------------------------------------- audio

    private fun sendAudio(data: ByteArray) {
        if (isHost) {
            peers.forEach { it.write(data) }
        } else {
            hostLink?.write(data)
        }
    }

    private fun buildRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, CHUNK_BYTES * 4)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialised" }
        return recorder
    }

    private fun ensurePlayback() {
        if (track != null) return
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
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
        } catch (t: Throwable) {
            Log.w(TAG, "playback unavailable", t)
            emit(State.ERROR, "Speaker unavailable")
        }
    }

    private fun play(data: ByteArray) {
        val player = track ?: return
        try {
            player.write(data, 0, data.size)
        } catch (t: Throwable) {
            Log.w(TAG, "playback write failed", t)
        }
    }

    private fun stopCapture() {
        talking = false
        captureThread?.interrupt()
        captureThread = null
        val rec = record
        record = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    private fun releasePlayback() {
        val player = track
        track = null
        if (player != null) {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    private fun emit(state: State, message: String) {
        onState?.invoke(state, message)
    }
}
