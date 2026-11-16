package com.meshlink.app.mesh

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meshlink.app.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * ONLINE CALLS — peer-to-peer voice over the internet, like WhatsApp calls.
 *
 * There is no MeshLink server. The two phones are connected by exchanging one
 * short code each way (copy/paste, or share through any messenger):
 *
 *   1. Caller  → "Start call" → app produces an OFFER code.
 *   2. Callee  → pastes the offer → app produces an ANSWER code.
 *   3. Caller  → pastes the answer → audio flows directly between the phones.
 *
 * Media is encrypted end to end with DTLS-SRTP, which WebRTC always enables.
 * No server ever sees or relays the audio: the code contains only the SDP
 * (connection details), and the audio path is direct P2P. Public STUN servers
 * are used only to discover the public address; they never carry audio.
 *
 * Limits, stated honestly: a few very strict mobile/CGNAT networks block
 * direct peer-to-peer UDP. Such networks need a TURN relay, which is not
 * free at scale and is not included. On those networks the call will fail
 * with a clear message; switching one phone to Wi-Fi or a hotspot usually
 * fixes it.
 */
object OnlineCallManager {

    private const val TAG = "MeshLinkOnline"

    enum class Phase { IDLE, PREPARING, SHARE_CODE, CONNECTING, CONNECTED, FAILED, ENDED }

    enum class Role { CALLER, CALLEE }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val role: Role? = null,
        val code: String = "",
        val remoteName: String = "",
        val muted: Boolean = false,
        val speakerOn: Boolean = false,
        val seconds: Int = 0,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var timerJob: Job? = null
    private var exportRunnable: Runnable? = null

    private fun context(): Context = appContext ?: throw IllegalStateException("OnlineCallManager not attached")

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    // ------------------------------------------------------------------ setup

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        val ctx = context()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val created = PeerConnectionFactory.builder().createPeerConnectionFactory()
        factory = created
        return created
    }

    private fun iceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> onConnected()

                PeerConnection.IceConnectionState.FAILED -> fail(
                    "Could not reach the other phone. Both phones must be online. " +
                        "Some strict mobile networks block direct calls — try Wi-Fi or a hotspot."
                )

                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    private fun newPeerConnection(): PeerConnection {
        val f = ensureFactory()
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = f.createPeerConnection(config, observer)
            ?: throw IllegalStateException("WebRTC could not start")
        peerConnection = pc

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val source = f.createAudioSource(constraints)
        val track = f.createAudioTrack("meshlink_audio", source)
        track.setEnabled(true)
        pc.addTrack(track, listOf("meshlink_stream"))
        audioSource = source
        audioTrack = track
        return pc
    }

    // --------------------------------------------------------------- caller

    /** Caller step 1: build an OFFER code that the other person pastes. */
    fun startAsCaller() {
        reset(keepAudio = false)
        _state.value = Ui(phase = Phase.PREPARING, role = Role.CALLER)
        enterAudioMode()
        try {
            val pc = newPeerConnection()
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) {
                        fail("Could not create the call")
                        return
                    }
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() = scheduleExport()
                        override fun onSetFailure(error: String?) = fail(error ?: "Call setup failed")
                        override fun onCreateSuccess(desc: SessionDescription?) = Unit
                        override fun onCreateFailure(error: String?) = Unit
                    }, desc)
                }

                override fun onCreateFailure(error: String?) = fail(error ?: "Could not create the call")
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            }, MediaConstraints())
        } catch (t: Throwable) {
            Log.w(TAG, "caller setup failed", t)
            fail(t.message ?: "WebRTC is not available on this phone")
        }
    }

    /** Caller step 3: paste the ANSWER code from the other person. */
    fun acceptAnswerCode(code: String) {
        val pc = peerConnection ?: run { fail("Start a call first"); return }
        val parsed = parseCode(code) ?: run { fail("That is not a MeshLink answer code"); return }
        if (parsed.type != SessionDescription.Type.ANSWER) {
            fail("This is an offer code. Paste the answer code the other person sent back.")
            return
        }
        _state.value = _state.value.copy(phase = Phase.CONNECTING, remoteName = parsed.name, message = null)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = fail(error ?: "Could not use that answer code")
            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = Unit
        }, parsed.description)
    }

    // --------------------------------------------------------------- callee

    /** Callee step 2: paste the caller's OFFER code and get an ANSWER code. */
    fun joinWithOfferCode(code: String) {
        reset(keepAudio = false)
        val parsed = parseCode(code) ?: run { fail("That is not a MeshLink call code"); return }
        if (parsed.type != SessionDescription.Type.OFFER) {
            fail("This is an answer code. Ask the caller for their call code.")
            return
        }
        _state.value = Ui(
            phase = Phase.PREPARING,
            role = Role.CALLEE,
            remoteName = parsed.name,
        )
        enterAudioMode()
        try {
            val pc = newPeerConnection()
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            if (desc == null) {
                                fail("Could not answer the call")
                                return
                            }
                            pc.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() = scheduleExport()
                                override fun onSetFailure(error: String?) = fail(error ?: "Call setup failed")
                                override fun onCreateSuccess(desc: SessionDescription?) = Unit
                                override fun onCreateFailure(error: String?) = Unit
                            }, desc)
                        }

                        override fun onCreateFailure(error: String?) = fail(error ?: "Could not answer")
                        override fun onSetSuccess() = Unit
                        override fun onSetFailure(error: String?) = Unit
                    }, MediaConstraints())
                }

                override fun onSetFailure(error: String?) = fail(error ?: "That call code is not valid")
                override fun onCreateSuccess(desc: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = Unit
            }, parsed.description)
        } catch (t: Throwable) {
            Log.w(TAG, "callee setup failed", t)
            fail(t.message ?: "WebRTC is not available on this phone")
        }
    }

    // ---------------------------------------------------------- code export

    private fun scheduleExport() {
        cancelExport()
        val started = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val pc = peerConnection ?: return
                val local = pc.localDescription
                val gathered = pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE
                if (local != null && (gathered || System.currentTimeMillis() - started > 6000)) {
                    exportCode(local)
                } else {
                    handler.postDelayed(this, 400)
                }
            }
        }
        exportRunnable = runnable
        handler.postDelayed(runnable, 600)
    }

    private fun cancelExport() {
        exportRunnable?.let { handler.removeCallbacks(it) }
        exportRunnable = null
    }

    private fun exportCode(description: SessionDescription) {
        val name = appContext?.let { SessionStore.displayName(it) } ?: ""
        val code = CallCodes.encode(
            type = description.type.canonicalForm(),
            sdp = description.description,
            name = name,
        )
        _state.value = _state.value.copy(phase = Phase.SHARE_CODE, code = code, message = null)
    }

    private data class ParsedCode(
        val type: SessionDescription.Type,
        val description: SessionDescription,
        val name: String,
    )

    private fun parseCode(raw: String): ParsedCode? {
        val decoded = CallCodes.decode(raw) ?: return null
        val type = SessionDescription.Type.fromCanonicalForm(decoded.type)
        return ParsedCode(type, SessionDescription(type, decoded.sdp), decoded.name)
    }

    // ------------------------------------------------------------- controls

    fun toggleMute() {
        val track = audioTrack ?: return
        val muted = !_state.value.muted
        track.setEnabled(!muted)
        _state.value = _state.value.copy(muted = muted)
    }

    fun toggleSpeaker() {
        val speaker = !_state.value.speakerOn
        val am = context().getSystemService(AudioManager::class.java)
        am?.isSpeakerphoneOn = speaker
        _state.value = _state.value.copy(speakerOn = speaker)
    }

    fun hangup() {
        reset(keepAudio = false)
        _state.value = Ui(phase = Phase.ENDED)
    }

    fun reset(keepAudio: Boolean = false) {
        timerJob?.cancel()
        timerJob = null
        cancelExport()
        runCatching { audioTrack?.setEnabled(false) }
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        if (!keepAudio) leaveAudioMode()
        if (!keepAudio) _state.value = Ui()
    }

    // -------------------------------------------------------------- internal

    private fun onConnected() {
        if (_state.value.phase == Phase.CONNECTED) return
        _state.value = _state.value.copy(phase = Phase.CONNECTED, message = null, seconds = 0)
        timerJob?.cancel()
        timerJob = scope.launch {
            var s = 0
            while (true) {
                delay(1000)
                s++
                _state.value = _state.value.copy(seconds = s)
            }
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, "online call failed: $message")
        reset(keepAudio = false)
        _state.value = Ui(phase = Phase.FAILED, message = message)
    }

    private fun enterAudioMode() {
        val am = context().getSystemService(AudioManager::class.java) ?: return
        @Suppress("DEPRECATION")
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = false
        requestAudioFocus(am)
    }

    private fun leaveAudioMode() {
        val am = appContext?.getSystemService(AudioManager::class.java) ?: return
        am.isSpeakerphoneOn = false
        @Suppress("DEPRECATION")
        am.mode = AudioManager.MODE_NORMAL
        abandonAudioFocus(am)
    }

    private fun requestAudioFocus(am: AudioManager) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .build()
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        }
    }

    private fun abandonAudioFocus(am: AudioManager) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .build()
                am.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }
}
