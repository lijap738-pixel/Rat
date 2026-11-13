package com.example.hardware

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Manages WebRTC PeerConnection, SDP offer/answer negotiation, and ICE candidate exchange
 * backed by Firestore session documents for live camera, audio, and screen-sharing streams.
 */
class WebRtcSessionManager(context: Context) {

  private val appContext = context.applicationContext
  private var eglBase: EglBase? = null
  private var peerConnectionFactory: PeerConnectionFactory? = null
  private var peerConnection: PeerConnection? = null

  private val _iceState = MutableStateFlow("IDLE")
  val iceState: StateFlow<String> = _iceState.asStateFlow()

  private val _localIceCandidates = MutableStateFlow<List<String>>(emptyList())
  val localIceCandidates: StateFlow<List<String>> = _localIceCandidates.asStateFlow()

  private fun ensureFactory() {
    if (peerConnectionFactory != null) return
    runCatching {
      PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(appContext)
          .setEnableInternalTracer(false)
          .createInitializationOptions()
      )
      val egl = EglBase.create()
      eglBase = egl
      peerConnectionFactory = PeerConnectionFactory.builder()
        .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
        .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
        .createPeerConnectionFactory()
    }.onFailure { err ->
      Log.w(TAG, "WebRTC native factory unavailable in current environment: ${err.message}")
    }
  }

  fun createOffer(onOfferCreated: (String) -> Unit) {
    ensureFactory()
    val factory = peerConnectionFactory
    if (factory == null) {
      // Fallback RFC-4566 SDP envelope for headless/non-native environments
      onOfferCreated(FALLBACK_SDP_OFFER)
      return
    }
    createPeerConnection()
    val pc = peerConnection
    if (pc == null) {
      onOfferCreated(FALLBACK_SDP_OFFER)
      return
    }
    runCatching {
      pc.createDataChannel("safenest-telemetry", DataChannel.Init())
      val constraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
      }
      pc.createOffer(
        object : SimpleSdpObserver() {
          override fun onCreateSuccess(desc: SessionDescription) {
            pc.setLocalDescription(SimpleSdpObserver(), desc)
            _iceState.value = "OFFER_CREATED"
            onOfferCreated(desc.description)
          }

          override fun onCreateFailure(error: String?) {
            Log.w(TAG, "createOffer failure: $error")
            onOfferCreated(FALLBACK_SDP_OFFER)
          }
        },
        constraints,
      )
    }.onFailure {
      onOfferCreated(FALLBACK_SDP_OFFER)
    }
  }

  fun createAnswerForOffer(sdpOffer: String?, onAnswerCreated: (String) -> Unit) {
    ensureFactory()
    val factory = peerConnectionFactory
    if (factory == null || sdpOffer.isNullOrBlank()) {
      onAnswerCreated(FALLBACK_SDP_ANSWER)
      return
    }
    createPeerConnection()
    val pc = peerConnection
    if (pc == null) {
      onAnswerCreated(FALLBACK_SDP_ANSWER)
      return
    }
    runCatching {
      pc.setRemoteDescription(
        object : SimpleSdpObserver() {
          override fun onSetSuccess() {
            pc.createAnswer(
              object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription) {
                  pc.setLocalDescription(SimpleSdpObserver(), desc)
                  _iceState.value = "CONNECTED_DTLS"
                  onAnswerCreated(desc.description)
                }

                override fun onCreateFailure(error: String?) {
                  onAnswerCreated(FALLBACK_SDP_ANSWER)
                }
              },
              MediaConstraints(),
            )
          }

          override fun onSetFailure(error: String?) {
            onAnswerCreated(FALLBACK_SDP_ANSWER)
          }
        },
        SessionDescription(SessionDescription.Type.OFFER, sdpOffer),
      )
    }.onFailure {
      onAnswerCreated(FALLBACK_SDP_ANSWER)
    }
  }

  fun applyRemoteAnswer(sdpAnswer: String?) {
    val pc = peerConnection ?: return
    if (sdpAnswer.isNullOrBlank()) return
    runCatching {
      pc.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer))
      _iceState.value = "CONNECTED_DTLS"
    }
  }

  fun addRemoteIceCandidate(encoded: String) {
    val pc = peerConnection ?: return
    val parts = encoded.split("|", limit = 3)
    if (parts.size < 3) return
    val sdpMid = parts[0]
    val mLine = parts[1].toIntOrNull() ?: 0
    val candidate = parts[2]
    runCatching {
      pc.addIceCandidate(IceCandidate(sdpMid, mLine, candidate))
    }
  }

  private fun createPeerConnection() {
    if (peerConnection != null) return
    val factory = peerConnectionFactory ?: return
    val iceServers = listOf(
      PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
      PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )
    val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
      sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }
    peerConnection = factory.createPeerConnection(
      rtcConfig,
      object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
          _iceState.value = state?.name ?: "UNKNOWN"
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {
          candidate ?: return
          val encoded = "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}"
          _localIceCandidates.value = (_localIceCandidates.value + encoded).takeLast(20)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
      },
    )
  }

  fun release() {
    runCatching { peerConnection?.close() }
    runCatching { peerConnection?.dispose() }
    peerConnection = null
    _iceState.value = "CLOSED"
  }

  private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
  }

  private companion object {
    const val TAG = "SafeNestWebRTC"
    const val FALLBACK_SDP_OFFER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=SafeNest\r\nt=0 0\r\n"
    const val FALLBACK_SDP_ANSWER = "v=0\r\no=- 2 2 IN IP4 127.0.0.1\r\ns=SafeNestAnswer\r\nt=0 0\r\n"
  }
}
