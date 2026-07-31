package Com.hau.name

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import Com.hau.name.webrtc.PeerConnectionManager
import Com.hau.name.webrtc.SignalingClient
import org.webrtc.EglBase
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

private const val TAG = "RemoteHostService"

/**
 * Foreground Service trên Máy B:
 * 1. Khởi tạo WebRTC PeerConnection (host/offer side).
 * 2. Tạo VideoSource từ ScreenCapturerAndroid (MediaProjection) để stream màn hình.
 * 3. Trao đổi offer/answer/ICE qua SignalingClient (Firebase).
 * 4. Lắng nghe lệnh điều khiển (tap/swipe) qua Firebase để AccessibilityService thực thi.
 */
class RemoteHostService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var signalingClient: SignalingClient? = null
    private var peerConnectionManager: PeerConnectionManager? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val eglBase: EglBase = EglBase.create()
    private var roomCode: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHARING) {
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }

        roomCode = intent?.getStringExtra(EXTRA_ROOM_CODE)
        val projectionData = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

        startForeground(NOTIF_ID, buildNotification(), foregroundServiceType())

        if (roomCode != null && projectionData != null) {
            initWebRTC(roomCode!!, projectionData)
        } else {
            Log.e(TAG, "Thiếu roomCode hoặc projectionData — không thể bắt đầu stream")
            stopSelf()
        }
        return START_STICKY
    }

    private fun initWebRTC(code: String, projectionData: Intent) {
        val sigClient = SignalingClient(
            roomCode = code,
            isHost = true,
            listener = object : SignalingClient.Listener {
                override fun onOfferReceived(sdp: String) {} // host không nhận offer
                override fun onAnswerReceived(sdp: String) {
                    peerConnectionManager?.handleAnswer(sdp)
                }
                override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    peerConnectionManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
                }
                override fun onRemoteDisconnected() {
                    Log.d(TAG, "Máy A ngắt kết nối — kết thúc phiên")
                    cleanup(); stopSelf()
                }
            }
        )
        signalingClient = sigClient

        val pcm = PeerConnectionManager(
            context = this,
            eglBase = eglBase,
            isHost = true,
            signalingClient = sigClient,
            remoteSink = null,
            onConnected = { Log.d(TAG, "WebRTC connected!") },
            onDisconnected = { Log.d(TAG, "WebRTC disconnected"); cleanup(); stopSelf() }
        )
        peerConnectionManager = pcm
        pcm.init()

        // Khởi tạo ScreenCapturer sau khi PeerConnection sẵn sàng
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (getSystemService(WINDOW_SERVICE) as WindowManager)
                .currentWindowMetrics.bounds.let {
                    metrics.widthPixels = it.width(); metrics.heightPixels = it.height()
                    metrics.densityDpi = resources.displayMetrics.densityDpi
                }
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = pcm.factory.createVideoSource(true)

        screenCapturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                cleanup(); stopSelf()
            }
        })
        screenCapturer!!.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
        screenCapturer!!.startCapture(metrics.widthPixels, metrics.heightPixels, 30)

        pcm.addVideoTrackAndOffer(videoSource!!)
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0
    }

    private fun buildNotification(): android.app.Notification {
        val channelId = "remote_assist_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RemoteHostService::class.java).apply { action = ACTION_STOP_SHARING },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Ngắt kết nối", stopPendingIntent)
            .build()
    }

    private fun cleanup() {
        screenCapturer?.stopCapture()
        screenCapturer?.dispose()
        screenCapturer = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        virtualDisplay?.release()
        virtualDisplay = null
        signalingClient?.markEnded()
        signalingClient?.release()
        signalingClient = null
        peerConnectionManager?.release()
        peerConnectionManager = null
        eglBase.release()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROOM_CODE = "extra_room_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val ACTION_STOP_SHARING = "action_stop_sharing"
        private const val NOTIF_ID = 42
    }
}
