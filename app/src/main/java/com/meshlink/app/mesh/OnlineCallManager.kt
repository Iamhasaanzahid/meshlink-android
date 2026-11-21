package com.meshlink.app.mesh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meshlink.app.CallActionReceiver
import com.meshlink.app.MainActivity
import com.meshlink.app.R
import com.meshlink.app.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.abs

/**
 * ONLINE CALLS — WhatsApp-style voice/video over the internet.
 *
 * Signalling travels through the encrypted online relay, and media uses
 * WebRTC with DTLS-SRTP end-to-end encryption. A direct peer-to-peer path is
 * preferred; when one phone is on mobile data and the other on Wi-Fi, ICE
 * falls back to Cloudflare's TURN relay with short-lived credentials.
 *
 * The old copy/paste offer/answer codes remain available as an offline
 * fallback, but normal 10-digit Mhzaly IDs ring automatically.
 */
object OnlineCallManager {

    private const val TAG = "MhzalyOnline"
    private const val CALL_CHANNEL_ID = "meshlink_online_calls"
    private const val CALL_NOTIFICATION_ID = 1009

    enum class Phase {
        IDLE,
        PREPARING,
        /** Outgoing internet call: ringing on the other phone. */
        DIALING,
        /** Incoming internet call: accept or decline. */
        RINGING,
        SHARE_CODE,
        CONNECTING,
        CONNECTED,
        FAILED,
        ENDED,
    }

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
        /** True when the call was signalled automatically over online mode. */
        val relayed: Boolean = false,
        val peer: String = "",
        val callId: String = "",
        /** Video call: remote + local camera are shown. */
        val video: Boolean = false,
        val cameraOn: Boolean = true,
        val frontCamera: Boolean = true,
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
    private var eglBase: EglBase? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var videoActive: Boolean = false
    private var frontCamera: Boolean = true
    private var timerJob: Job? = null
    private var exportRunnable: Runnable? = null
    private var ringTimeout: Runnable? = null
    /** Short grace period before restarting ICE after a network blip. */
    private var disconnectCheck: Runnable? = null
    /** Call IDs already seen, so replayed rings cannot ring the phone again. */
    private val seenCalls = mutableMapOf<String, Long>()
    private var pendingOffer: String? = null
    /** User accepted before the SDP offer arrived; finish as soon as it does. */
    private var pendingAccept = false
    private var factoryInitialized = false
    private var focusRequest: AudioFocusRequest? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    /** ICE candidates that arrive before the remote description is installed. */
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    /** Automatic ICE-restart attempts when a mobile network blocks the first path. */
    private var iceRestarts = 0
    /** Fresh Cloudflare TURN credentials, refreshed in the background. */
    @Volatile private var turnIce: List<PeerConnection.IceServer> = emptyList()
    @Volatile private var turnFetchedAt: Long = 0L
    private var turnJob: Job? = null

    /**
     * Set by MeshService: publishes one WebRTC signalling message to
     * [peer] through online mode.
     */
    var sendSignal: ((peer: String, message: String) -> Unit)? = null

    private fun context(): Context = appContext ?: throw IllegalStateException("OnlineCallManager not attached")

    fun attach(context: Context) {
        appContext = context.applicationContext
        // Pre-warm the TURN relay so the first mobile-data <-> Wi-Fi call has
        // a working relay candidate instead of relying on STUN alone.
        refreshTurnCredentials()
    }

    private fun micGranted(): Boolean {
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Signs a message, turning a missing relay into a visible failure. */
    private fun signal(peer: String, message: String, critical: Boolean = false): Boolean {
        val publish = sendSignal
        if (publish == null) {
            if (critical) fail("Call signalling is not ready. Wait for Online mode to connect.")
            return false
        }
        val ok = runCatching { publish(peer, message); true }.getOrDefault(false)
        if (!ok && critical) fail("Could not send call signalling. Check your internet connection.")
        return ok
    }

    // ------------------------------------------------------------------ setup

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        val ctx = context()
        if (!factoryInitialized) {
            // WebRTC only accepts one global initialization per process.
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ctx)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }
        val egl = EglBase.create()
        eglBase = egl
        val created = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(egl.eglBaseContext, true, true),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = created
        return created
    }

    private fun staticStun(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.services.mozilla.com:3478").createIceServer(),
    )

    /**
     * Cloudflare's public TURN relay is fetched with short-lived credentials.
     * It is the route that makes mobile-data <-> Wi-Fi calls work even when
     * both sides are behind CGNAT or a strict carrier firewall.
     */
    private fun iceServers(): List<PeerConnection.IceServer> {
        val turn = turnIce
        return if (turn.isEmpty()) staticStun() else turn + staticStun()
    }

    private fun refreshTurnCredentials(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && turnIce.isNotEmpty() && now - turnFetchedAt < 6 * 60 * 60 * 1000L) return
        if (turnJob?.isActive == true) return
        turnJob = scope.launch(Dispatchers.IO) {
            val fresh = runCatching { fetchCloudflareTurn() }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) {
                turnIce = fresh
                turnFetchedAt = System.currentTimeMillis()
            }
        }
    }

    private fun fetchCloudflareTurn(): List<PeerConnection.IceServer> {
        // Prefer our own worker so carrier DNS/firewalls cannot block the
        // speed-test domain. Fall back to Cloudflare directly.
        val sources = listOf(
            "https://mhzaly-ai.mhzaly.workers.dev/api/turn" to true,
            "https://speed.cloudflare.com/turn-creds" to false,
        )
        for ((url, native) in sources) {
            val servers = runCatching { fetchTurnSource(url, native) }.getOrDefault(emptyList())
            if (servers.isNotEmpty()) return servers
        }
        Log.w(TAG, "TURN credentials unavailable from all sources")
        return emptyList()
    }

    private fun fetchTurnSource(url: String, native: Boolean): List<PeerConnection.IceServer> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 4_000
            connection.readTimeout = 7_000
            connection.setRequestProperty("Origin", "https://speed.cloudflare.com")
            connection.setRequestProperty("Referer", "https://speed.cloudflare.com/")
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
            )
            if (native) connection.setRequestProperty("X-Mhzaly-Client", "android")
            if (connection.responseCode !in 200..299) return emptyList()
            val body = connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(body)
            if (json.has("ok") && !json.optBoolean("ok", false)) return emptyList()
            val username = json.optString("username")
            val credential = json.optString("credential")
            val urls = json.optJSONArray("urls") ?: return emptyList()
            if (username.isBlank() || credential.isBlank()) return emptyList()
            buildList {
                for (i in 0 until urls.length()) {
                    val candidate = urls.optString(i).trim()
                    if (candidate.isNotBlank()) {
                        add(
                            PeerConnection.IceServer.builder(candidate)
                                .setUsername(username)
                                .setPassword(credential)
                                .createIceServer(),
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    cancelDisconnectCheck()
                    onConnected()
                }

                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    // Mobile networks briefly drop ICE all the time. Give it a
                    // moment before forcing a fresh route instead of ending
                    // a perfectly good call.
                    val s = _state.value
                    if (s.relayed && s.phase == Phase.CONNECTED) armDisconnectCheck()
                }

                PeerConnection.IceConnectionState.FAILED -> {
                    val s = _state.value
                    if (s.relayed && iceRestarts < 3 && s.phase != Phase.ENDED &&
                        s.phase != Phase.FAILED
                    ) {
                        iceRestarts++
                        restartIceWithRenegotiation()
                    } else {
                        fail("Call connect nahi hua. Dobara try karein.")
                    }
                }

                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) {
            val s = _state.value
            if (candidate == null || !s.relayed) return
            if (s.peer.isBlank() || s.callId.isBlank()) return
            val json = runCatching {
                JSONObject()
                    .put("a", "ice")
                    .put("id", s.callId)
                    .put("cand", candidate.sdp)
                    .put("mid", candidate.sdpMid ?: "")
                    .put("mline", candidate.sdpMLineIndex)
                    .toString()
            }.getOrNull() ?: return
            signal(s.peer, json)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track()
            if (track is VideoTrack) {
                remoteVideoTrack = track
                remoteRenderer?.let { runCatching { track.addSink(it) } }
                Log.i(TAG, "remote video track attached")
            }
        }
    }

    /**
     * The caller can trickle ICE candidates before the callee has accepted the
     * offer. WebRTC cannot install them yet, so keep them until the remote
     * description is set instead of silently dropping the connection hints.
     */
    private fun queueOrAddIce(candidate: IceCandidate) {
        val pc = peerConnection
        if (pc == null || !remoteDescriptionSet) {
            pendingIce.add(candidate)
            return
        }
        runCatching { pc.addIceCandidate(candidate) }
    }

    private fun flushPendingIce() {
        val pc = peerConnection ?: return
        if (!remoteDescriptionSet) return
        pendingIce.forEach { runCatching { pc.addIceCandidate(it) } }
        pendingIce.clear()
    }

    private fun newPeerConnection(video: Boolean = false): PeerConnection {
        val f = ensureFactory()
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 2
        }
        val pc = f.createPeerConnection(config, observer)
            ?: throw IllegalStateException("WebRTC could not start")
        peerConnection = pc
        remoteDescriptionSet = false

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

        videoActive = false
        if (video) {
            val vTrack = ensureVideoTrack(f)
            if (vTrack != null) {
                pc.addTrack(vTrack, listOf("meshlink_stream"))
                videoActive = true
            } else {
                Log.w(TAG, "camera unavailable, continuing as an audio call")
            }
        }
        return pc
    }

    // ---------------------------------------------------------------- video

    /** EGL context for the two SurfaceViewRenderer views in the call UI. */
    fun eglContext(): EglBase.Context? = eglBase?.eglBaseContext

    fun bindRenderers(local: SurfaceViewRenderer, remote: SurfaceViewRenderer) {
        localRenderer = local
        remoteRenderer = remote
        runCatching { videoTrack?.addSink(local) }
        runCatching { remoteVideoTrack?.addSink(remote) }
    }

    fun unbindRenderers() {
        runCatching { videoTrack?.removeSink(localRenderer) }
        runCatching { remoteVideoTrack?.removeSink(remoteRenderer) }
        localRenderer = null
        remoteRenderer = null
    }

    fun setCameraEnabled(enabled: Boolean) {
        runCatching { videoTrack?.setEnabled(enabled) }
        _state.value = _state.value.copy(cameraOn = enabled)
    }

    fun switchCamera() {
        val capturer = videoCapturer ?: return
        runCatching { capturer.switchCamera(null) }
        frontCamera = !frontCamera
        _state.value = _state.value.copy(frontCamera = frontCamera)
    }

    private fun ensureVideoTrack(f: PeerConnectionFactory): VideoTrack? {
        videoTrack?.let { return it }
        val egl = eglBase ?: return null
        val ctx = context()
        val enumerator = runCatching { Camera2Enumerator(ctx) }.getOrNull() ?: return null
        val names = runCatching { enumerator.deviceNames }.getOrDefault(emptyArray())
        if (names.isEmpty()) return null
        val pick = names.firstOrNull { runCatching { enumerator.isFrontFacing(it) }.getOrDefault(false) }
            ?: names.first()
        val capturer = runCatching { enumerator.createCapturer(pick, null) }.getOrNull() ?: return null
        val helper = SurfaceTextureHelper.create("MhzalyCapture", egl.eglBaseContext)
        val source = f.createVideoSource(capturer.isScreencast)
        capturer.initialize(helper, ctx, source.capturerObserver)
        runCatching { capturer.startCapture(640, 480, 24) }
        val track = f.createVideoTrack("meshlink_video", source)
        track.setEnabled(true)
        localRenderer?.let { runCatching { track.addSink(it) } }
        videoCapturer = capturer
        videoSource = source
        videoTrack = track
        frontCamera = true
        return track
    }

    // --------------------------------------------------------------- caller

    /** Caller step 1: build an OFFER code that the other person pastes. */
    fun startAsCaller() {
        if (!micGranted()) {
            fail("Microphone permission is needed for a call. Allow it and try again.")
            return
        }
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
        if (!micGranted()) {
            fail("Microphone permission is needed for a call. Allow it and try again.")
            return
        }
        val pc = peerConnection ?: run { fail("Start a call first"); return }
        val parsed = parseCode(code) ?: run { fail("That is not a Mhzaly answer code"); return }
        if (parsed.type != SessionDescription.Type.ANSWER) {
            fail("This is an offer code. Paste the answer code the other person sent back.")
            return
        }
        _state.value = _state.value.copy(phase = Phase.CONNECTING, remoteName = parsed.name, message = null)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushPendingIce()
            }
            override fun onSetFailure(error: String?) = fail(error ?: "Could not use that answer code")
            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = Unit
        }, parsed.description)
    }

    // --------------------------------------------------------------- callee

    /** Callee step 2: paste the caller's OFFER code and get an ANSWER code. */
    fun joinWithOfferCode(code: String) {
        if (!micGranted()) {
            fail("Microphone permission is needed for a call. Allow it and try again.")
            return
        }
        reset(keepAudio = false)
        val parsed = parseCode(code) ?: run { fail("That is not a Mhzaly call code"); return }
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
                    remoteDescriptionSet = true
                    flushPendingIce()
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

    // ------------------------------------------------- relayed internet call

    /**
     * Starts a normal app-to-app internet call. The offer travels through
     * online mode (MQTT), the other phone rings itself, and WebRTC media
     * connects directly with DTLS-SRTP.
     */
    fun startRelayedCall(peer: String, name: String, video: Boolean = false) {
        val target = peer.trim()
        if (target.isBlank()) return
        if (!micGranted()) {
            fail("Microphone permission is needed for a call. Allow it and try again.")
            return
        }
        reset(keepAudio = false)
        refreshTurnCredentials()
        val id = UUID.randomUUID().toString().take(8)
        _state.value = Ui(
            phase = Phase.DIALING,
            role = Role.CALLER,
            remoteName = name.ifBlank { target },
            relayed = true,
            peer = target,
            callId = id,
            video = video,
        )
        enterAudioMode()
        armRingTimeout(id)
        // Only the real SDP offer may ring the other phone. A separate early
        // "ring" signal could be replayed later and caused phantom calls.
        try {
            val pc = newPeerConnection(video)
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) {
                        fail("Could not create the call")
                        return
                    }
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            val local = pc.localDescription ?: desc
                            signal(target, offerJson(id, local), critical = true)
                        }

                        override fun onSetFailure(error: String?) =
                            fail(error ?: "Call setup failed")

                        override fun onCreateSuccess(desc: SessionDescription?) = Unit
                        override fun onCreateFailure(error: String?) = Unit
                    }, desc)
                }

                override fun onCreateFailure(error: String?) =
                    fail(error ?: "Could not create the call")

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            }, MediaConstraints())
        } catch (t: Throwable) {
            Log.w(TAG, "relayed caller setup failed", t)
            fail(t.message ?: "WebRTC is not available on this phone")
        }
    }

    /** Answers a ringing internet call. */
    fun acceptRelayed() {
        val s = _state.value
        if (!s.relayed) return
        if (s.phase != Phase.RINGING && !(s.phase == Phase.CONNECTING && pendingAccept)) return
        val offer = pendingOffer
        if (offer.isNullOrBlank()) {
            // Ring arrived before SDP. Keep the call alive and continue as soon
            // as the offer lands, instead of showing a fake error.
            pendingAccept = true
            _state.value = s.copy(phase = Phase.CONNECTING, message = "Call connect ho raha hai…")
            return
        }
        pendingAccept = false
        if (!micGranted()) {
            signal(s.peer, simpleJson("reject", s.callId))
            fail("Microphone permission is needed to answer. Allow it and try again.")
            return
        }
        stopRinging()
        cancelIncomingNotification()
        cancelRingTimeout()
        refreshTurnCredentials()
        _state.value = s.copy(phase = Phase.CONNECTING, message = null)
        enterAudioMode()
        armRingTimeout(s.callId)
        try {
            val pc = newPeerConnection(s.video)
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    flushPendingIce()
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            if (desc == null) {
                                fail("Could not answer the call")
                                return
                            }
                            pc.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    val local = pc.localDescription ?: desc
                                    signal(s.peer, answerJson(s.callId, local), critical = true)
                                }

                                override fun onSetFailure(error: String?) =
                                    fail(error ?: "Call setup failed")

                                override fun onCreateSuccess(desc: SessionDescription?) = Unit
                                override fun onCreateFailure(error: String?) = Unit
                            }, desc)
                        }

                        override fun onCreateFailure(error: String?) =
                            fail(error ?: "Could not answer")

                        override fun onSetSuccess() = Unit
                        override fun onSetFailure(error: String?) = Unit
                    }, MediaConstraints())
                }

                override fun onSetFailure(error: String?) =
                    fail(error ?: "That call offer is not valid")

                override fun onCreateSuccess(desc: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = Unit
            }, SessionDescription(SessionDescription.Type.OFFER, offer))
        } catch (t: Throwable) {
            Log.w(TAG, "relayed callee setup failed", t)
            fail(t.message ?: "WebRTC is not available on this phone")
        }
    }

    /** Declines a ringing internet call and tells the caller. */
    fun declineRelayed() {
        val s = _state.value
        if (s.phase != Phase.RINGING) return
        if (s.peer.isNotBlank() && s.callId.isNotBlank()) {
            signal(s.peer, simpleJson("reject", s.callId))
        }
        stopRinging()
        cancelIncomingNotification()
        cancelRingTimeout()
        reset(keepAudio = false)
        _state.value = Ui(phase = Phase.ENDED)
    }

    /** A signalling message arrived through online mode. */
    fun onSignal(from: String, message: String) {
        handler.post {
            val json = runCatching { JSONObject(message) }.getOrNull() ?: return@post
            val action = json.optString("a")
            val id = json.optString("id")
            if (id.isBlank()) return@post
            val current = _state.value
            when (action) {
                "ring" -> {
                    // Legacy early-ring signals are ignored. Only a real SDP
                    // offer can start an incoming call, so replayed or fake
                    // rings can never reach the UI.
                    return@post
                }

                "reoffer" -> {
                    // Automatic ICE restart from the other phone. Keep the same
                    // call UI and just swap the new offer/answer.
                    if (current.callId != id ||
                        current.phase == Phase.IDLE || current.phase == Phase.ENDED ||
                        current.phase == Phase.FAILED
                    ) return@post
                    val sdp = json.optString("sdp")
                    val pc = peerConnection
                    if (sdp.isBlank() || pc == null) return@post
                    pc.setRemoteDescription(
                        object : SdpObserver {
                            override fun onSetSuccess() {
                                remoteDescriptionSet = true
                                flushPendingIce()
                                pc.createAnswer(
                                    object : SdpObserver {
                                        override fun onCreateSuccess(desc: SessionDescription?) {
                                            if (desc == null) return
                                            pc.setLocalDescription(
                                                object : SdpObserver {
                                                    override fun onSetSuccess() = Unit
                                                    override fun onSetFailure(error: String?) =
                                                        fail(error ?: "Call connect nahi hua. Dobara try karein.")
                                                    override fun onCreateSuccess(desc: SessionDescription?) = Unit
                                                    override fun onCreateFailure(error: String?) = Unit
                                                },
                                                desc,
                                            )
                                            signal(from, sdpJson("reanswer", id, desc.description), critical = true)
                                        }
                                        override fun onCreateFailure(error: String?) =
                                            fail(error ?: "Call connect nahi hua. Dobara try karein.")
                                        override fun onSetSuccess() = Unit
                                        override fun onSetFailure(error: String?) = Unit
                                    },
                                    MediaConstraints(),
                                )
                            }
                            override fun onSetFailure(error: String?) =
                                fail(error ?: "Call connect nahi hua. Dobara try karein.")
                            override fun onCreateSuccess(desc: SessionDescription?) = Unit
                            override fun onCreateFailure(error: String?) = Unit
                        },
                        SessionDescription(SessionDescription.Type.OFFER, sdp),
                    )
                }

                "reanswer" -> {
                    if (current.callId != id) return@post
                    val sdp = json.optString("sdp")
                    val pc = peerConnection
                    if (sdp.isBlank() || pc == null) return@post
                    pc.setRemoteDescription(
                        object : SdpObserver {
                            override fun onSetSuccess() {
                                remoteDescriptionSet = true
                                flushPendingIce()
                            }
                            override fun onSetFailure(error: String?) =
                                fail(error ?: "Call connect nahi hua. Dobara try karein.")
                            override fun onCreateSuccess(desc: SessionDescription?) = Unit
                            override fun onCreateFailure(error: String?) = Unit
                        },
                        SessionDescription(SessionDescription.Type.ANSWER, sdp),
                    )
                }

                "offer" -> {
                    // The offer itself is the only ring. Reject genuinely
                    // different calls only.
                    val samePending = current.relayed && current.callId == id &&
                        (current.phase == Phase.RINGING || current.phase == Phase.CONNECTING)
                    val busy = current.phase != Phase.IDLE &&
                        current.phase != Phase.ENDED &&
                        current.phase != Phase.FAILED &&
                        !samePending
                    if (busy) {
                        signal(from, simpleJson("reject", id))
                        return@post
                    }
                    val sdp = json.optString("sdp")
                    if (!isFresh(json) || sdp.isBlank()) return@post
                    if (seenCalls.containsKey(id)) return@post
                    seenCalls[id] = System.currentTimeMillis()
                    if (seenCalls.size > 32) {
                        seenCalls.entries.removeAll { System.currentTimeMillis() - it.value > 60_000L }
                    }
                    pendingOffer = sdp
                    if (!samePending) {
                        stopRinging()
                        val name = json.optString("n").ifBlank { from }
                        _state.value = Ui(
                            phase = Phase.RINGING,
                            role = Role.CALLEE,
                            remoteName = name,
                            relayed = true,
                            peer = from,
                            callId = id,
                            video = json.optBoolean("video", false),
                            message = "Call connect ho raha hai…",
                        )
                        startRinging()
                        showIncomingNotification(name, from, json.optBoolean("video", false))
                        armRingTimeout(id)
                    } else {
                        _state.value = current.copy(message = null)
                    }
                    // If the user already pressed Accept while SDP was in
                    // flight, finish the handshake now instead of failing.
                    if (pendingAccept) {
                        pendingAccept = false
                        acceptRelayed()
                    }
                }

                "answer" -> {
                    if (current.callId != id || current.role != Role.CALLER) return@post
                    if (current.phase != Phase.DIALING && current.phase != Phase.CONNECTING) return@post
                    val pc = peerConnection ?: return@post
                    val sdp = json.optString("sdp")
                    if (sdp.isBlank()) return@post
                    _state.value = current.copy(phase = Phase.CONNECTING, message = null)
                    pc.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            remoteDescriptionSet = true
                            flushPendingIce()
                        }
                        override fun onSetFailure(error: String?) =
                            fail(error ?: "Could not connect the call")

                        override fun onCreateSuccess(desc: SessionDescription?) = Unit
                        override fun onCreateFailure(error: String?) = Unit
                    }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }

                "ice" -> {
                    if (current.callId != id) return@post
                    val cand = json.optString("cand")
                    if (cand.isBlank()) return@post
                    queueOrAddIce(
                        IceCandidate(
                            json.optString("mid"),
                            json.optInt("mline"),
                            cand,
                        )
                    )
                }

                "reject" -> {
                    if (current.callId != id) return@post
                    stopRinging()
                    cancelIncomingNotification()
                    cancelRingTimeout()
                    reset(keepAudio = false)
                    _state.value = Ui(phase = Phase.FAILED, message = "Call declined")
                }

                "hangup" -> {
                    if (current.callId != id) return@post
                    stopRinging()
                    cancelIncomingNotification()
                    cancelRingTimeout()
                    reset(keepAudio = false)
                    _state.value = Ui(phase = Phase.ENDED)
                }
            }
        }
    }

    // --------------------------------------------------------------- ringing

    private fun startRinging() {
        val ctx = appContext ?: return
        stopRinging()
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

    /** Incoming internet calls need a notification too: the app may be closed. */
    private fun showIncomingNotification(name: String, peer: String, video: Boolean) {
        val ctx = appContext ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
                if (manager.getNotificationChannel(CALL_CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CALL_CHANNEL_ID,
                            "Online calls",
                            NotificationManager.IMPORTANCE_HIGH,
                        ).apply { description = "Incoming Mhzaly internet calls" }
                    )
                }
            }
            val intent = Intent(ctx, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                putExtra(CallActionReceiver.OPEN_ONLINE_CALL, true)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pending = PendingIntent.getActivity(ctx, CALL_NOTIFICATION_ID, intent, flags)
            val acceptPending = PendingIntent.getBroadcast(
                ctx,
                CALL_NOTIFICATION_ID + 1,
                Intent(ctx, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_ACCEPT),
                flags,
            )
            val declinePending = PendingIntent.getBroadcast(
                ctx,
                CALL_NOTIFICATION_ID + 2,
                Intent(ctx, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
                flags,
            )
            val notification = NotificationCompat.Builder(ctx, CALL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_meshlink_notification)
                .setContentTitle(if (video) "Incoming video call" else "Incoming Mhzaly call")
                .setContentText("${name.ifBlank { peer }} · tap to open")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .addAction(
                    NotificationCompat.Action.Builder(
                        android.R.drawable.ic_menu_call,
                        "Accept",
                        acceptPending,
                    ).build(),
                )
                .addAction(
                    NotificationCompat.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Decline",
                        declinePending,
                    ).build(),
                )
                .build()
            NotificationManagerCompat.from(ctx).notify(CALL_NOTIFICATION_ID, notification)
        }
    }

    private fun cancelIncomingNotification() {
        val ctx = appContext ?: return
        runCatching { NotificationManagerCompat.from(ctx).cancel(CALL_NOTIFICATION_ID) }
    }

    private fun offerJson(id: String, desc: SessionDescription): String =
        runCatching {
            JSONObject()
                .put("a", "offer")
                .put("id", id)
                .put("sdp", desc.description)
                .put("n", appContext?.let { SessionStore.displayName(it) } ?: "")
                .put("video", videoActive)
                .put("ts", System.currentTimeMillis())
                .toString()
        }.getOrDefault("")

    private fun answerJson(id: String, desc: SessionDescription): String =
        runCatching {
            JSONObject()
                .put("a", "answer")
                .put("id", id)
                .put("sdp", desc.description)
                .toString()
        }.getOrDefault("")

    private fun simpleJson(action: String, id: String): String =
        runCatching { JSONObject().put("a", action).put("id", id).toString() }
            .getOrDefault("{\"a\":\"$action\",\"id\":\"$id\"}")

    private fun sdpJson(action: String, id: String, description: String): String =
        runCatching {
            JSONObject()
                .put("a", action)
                .put("id", id)
                .put("sdp", description)
                .toString()
        }.getOrDefault("")

    /** Old queued/replayed signals must never ring a phone. */
    private fun isFresh(json: JSONObject): Boolean {
        val ts = json.optLong("ts", 0L)
        return ts > 0L && abs(System.currentTimeMillis() - ts) < 30_000L
    }

    /**
     * A mobile/CGNAT network can block the first ICE path. Restart ICE and
     * renegotiate on the same call id, preferring the TURN/TCP routes that work
     * on strict networks. The UI stays on "Connecting" instead of showing a
     * dead end.
     */
    private fun restartIceWithRenegotiation() {
        val pc = peerConnection
        val s = _state.value
        if (pc == null || !s.relayed || s.peer.isBlank() || s.callId.isBlank()) {
            fail("Call connect nahi hua. Dobara try karein.")
            return
        }
        _state.value = s.copy(
            phase = Phase.CONNECTING,
            message = "Network change karke dobara connect ho raha hai…",
        )
        runCatching { pc.restartIce() }
        pc.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) {
                        fail("Call connect nahi hua. Dobara try karein.")
                        return
                    }
                    pc.setLocalDescription(
                        object : SdpObserver {
                            override fun onSetSuccess() = Unit
                            override fun onSetFailure(error: String?) =
                                fail(error ?: "Call connect nahi hua. Dobara try karein.")
                            override fun onCreateSuccess(desc: SessionDescription?) = Unit
                            override fun onCreateFailure(error: String?) = Unit
                        },
                        desc,
                    )
                    signal(s.peer, sdpJson("reoffer", s.callId, desc.description), critical = true)
                }
                override fun onCreateFailure(error: String?) =
                    fail(error ?: "Call connect nahi hua. Dobara try karein.")
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            },
            MediaConstraints(),
        )
    }

    private fun armRingTimeout(id: String) {
        cancelRingTimeout()
        val runnable = Runnable {
            val s = _state.value
            if (s.callId == id && s.phase != Phase.CONNECTED) {
                if (s.phase == Phase.RINGING && s.role == Role.CALLEE) {
                    // The caller gave up; stop ringing quietly.
                    reset(keepAudio = false)
                    _state.value = Ui(phase = Phase.ENDED)
                } else {
                    fail("No answer. The other phone may be offline or on a very strict network.")
                }
            }
        }
        ringTimeout = runnable
        handler.postDelayed(runnable, 45_000)
    }

    private fun cancelRingTimeout() {
        ringTimeout?.let { handler.removeCallbacks(it) }
        ringTimeout = null
    }

    private fun armDisconnectCheck() {
        cancelDisconnectCheck()
        val runnable = Runnable {
            val s = _state.value
            if (s.relayed && s.phase == Phase.CONNECTED) {
                if (iceRestarts < 3) {
                    iceRestarts++
                    restartIceWithRenegotiation()
                } else {
                    fail("Call network kharab ho gaya. Dobara try karein.")
                }
            }
        }
        disconnectCheck = runnable
        handler.postDelayed(runnable, 3_000L)
    }

    private fun cancelDisconnectCheck() {
        disconnectCheck?.let { handler.removeCallbacks(it) }
        disconnectCheck = null
    }


    // ---------------------------------------------------------- code export

    private fun scheduleExport() {
        if (_state.value.relayed) return
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
        appContext?.getSystemService(AudioManager::class.java)?.let { am ->
            runCatching { am.isSpeakerphoneOn = speaker }
        }
        _state.value = _state.value.copy(speakerOn = speaker)
    }

    fun hangup() {
        val s = _state.value
        if (s.phase == Phase.ENDED || s.phase == Phase.FAILED) {
            reset(keepAudio = false)
            return
        }
        if (s.relayed && s.peer.isNotBlank() && s.callId.isNotBlank()) {
            signal(s.peer, simpleJson("hangup", s.callId))
        }
        if (s.peer.isNotBlank()) {
            val status = when (s.phase) {
                Phase.CONNECTED -> "COMPLETED"
                Phase.RINGING -> if (s.role == Role.CALLEE) "MISSED" else "NO_ANSWER"
                Phase.DIALING, Phase.CONNECTING, Phase.PREPARING -> "NO_ANSWER"
                else -> ""
            }
            if (status.isNotEmpty()) {
                com.meshlink.app.data.ChatRepository.logCall(
                    peer = s.peer,
                    name = s.remoteName,
                    outgoing = s.role == Role.CALLER,
                    missed = status == "MISSED" || (status == "NO_ANSWER" && s.role == Role.CALLER),
                    transport = "ONLINE",
                    timestamp = System.currentTimeMillis(),
                    durationSec = s.seconds,
                    status = status,
                )
            }
        }
        stopRinging()
        cancelIncomingNotification()
        cancelRingTimeout()
        reset(keepAudio = false)
        _state.value = Ui(phase = Phase.ENDED, relayed = s.relayed, remoteName = s.remoteName)
    }

    fun reset(keepAudio: Boolean = false) {
        timerJob?.cancel()
        timerJob = null
        stopRinging()
        cancelIncomingNotification()
        cancelExport()
        cancelRingTimeout()
        cancelDisconnectCheck()
        unbindRenderers()
        pendingOffer = null
        pendingAccept = false
        iceRestarts = 0
        pendingIce.clear()
        remoteDescriptionSet = false
        runCatching { audioTrack?.setEnabled(false) }
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        runCatching { videoTrack?.dispose() }
        videoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        remoteVideoTrack = null
        videoActive = false
        frontCamera = true
        if (!keepAudio) leaveAudioMode()
        if (!keepAudio) _state.value = Ui()
    }

    // -------------------------------------------------------------- internal

    private fun onConnected() {
        if (_state.value.phase == Phase.CONNECTED) return
        if (_state.value.phase == Phase.ENDED || _state.value.phase == Phase.FAILED) return
        cancelRingTimeout()
        cancelDisconnectCheck()
        iceRestarts = 0
        stopRinging()
        cancelIncomingNotification()
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
        val s = _state.value
        // Let the other phone leave its ringing screen instead of waiting for
        // the 45 s timeout when setup fails on this side.
        if (s.relayed && s.peer.isNotBlank() && s.callId.isNotBlank() &&
            s.phase != Phase.ENDED && s.phase != Phase.FAILED
        ) {
            signal(s.peer, simpleJson("hangup", s.callId))
        }
        stopRinging()
        cancelIncomingNotification()
        reset(keepAudio = false)
        _state.value = Ui(phase = Phase.FAILED, message = message)
    }

    private fun enterAudioMode() {
        val am = appContext?.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            @Suppress("DEPRECATION")
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = false
        }
        requestAudioFocus(am)
    }

    private fun leaveAudioMode() {
        val am = appContext?.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            am.isSpeakerphoneOn = false
            @Suppress("DEPRECATION")
            am.mode = AudioManager.MODE_NORMAL
        }
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
                val result = am.requestAudioFocus(request)
                // Android requires the same request instance when abandoning.
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    focusRequest = request
                }
                Unit
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        }
    }

    private fun abandonAudioFocus(am: AudioManager) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
        focusRequest = null
    }
}
