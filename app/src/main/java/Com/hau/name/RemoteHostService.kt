package Com.hau.name

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        if (roomCode != null && projectionData != null) {
            initWebRTC(roomCode!!, projectionData)
        } else {
            Log.e(TAG, "Thiếu roomCode hoặc projectionData — không thể bắt đầu stream")
            stopSelf()
        }
        return START_STICKY
    }

    private fun initWebRTC(code: String, projectionData: Intent) {
        val sigClient = SignalingClient(roomCode = code, isHost = true, listener = buildHostListener())
        signalingClient = sigClient

        val pcm = PeerConnectionManager(
            context = this,
            eglBase = eglBase,
            isHost = true,
            signalingClient = sigClient,
            remoteSink = null,
            onConnected = { Log.d(TAG, "WebRTC connected!") },
            onDisconnected = {
                // Trước đây: kết thúc hẳn phiên (cleanup + stopSelf) ngay khi mất kết nối.
                // Giờ: mã ghép nối đóng vai trò "số phòng" cố định — A ngắt kết nối (chủ động
                // bấm nút, tắt app, rớt mạng...) KHÔNG làm mất phòng, chỉ cần dựng lại
                // PeerConnection + offer mới rồi chờ A (hoặc 1 A khác) vào lại đúng mã này.
                // Phòng chỉ thật sự đóng khi B tự kết thúc (xem cleanup()/ACTION_STOP_SHARING).
                Log.d(TAG, "Máy A ngắt kết nối — chuẩn bị đón kết nối lại với mã $code")
                prepareForReconnect(code)
            }
        )
        peerConnectionManager = pcm
        pcm.init()

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = pcm.factory.createVideoSource(true)

        screenCapturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                cleanup(); stopSelf()
            }
        })
        screenCapturer!!.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
        // Capture ở độ phân giải gốc (vd. 1080x2400) và 30fps quá nặng cho encoder phần cứng
        // của nhiều máy tầm trung, đồng thời tạo luồng bitrate quá lớn so với băng thông thực tế
        // khi phải đi qua TURN relay trên mạng di động → gây lag/khựng khi Máy A nhận hình.
        // Vì tọa độ chạm gửi đi là TỶ LỆ (0..1), không phải pixel tuyệt đối, nên hạ độ phân giải
        // capture không ảnh hưởng độ chính xác điều khiển. Giữ đúng tỉ lệ khung hình gốc, chỉ
        // giảm kích thước tối đa cạnh dài xuống CAPTURE_MAX_DIMENSION và fps xuống CAPTURE_FPS.
        // Kích thước gốc lấy từ ScreenMetrics.realSize() — CÙNG một nguồn với InputInjectionService
        // dùng để quy đổi tọa độ chạm, đảm bảo 2 bên luôn khớp hệ quy chiếu pixel.
        val (rawWidth, rawHeight) = ScreenMetrics.realSize(this)
        val (captureWidth, captureHeight) = scaledCaptureSize(rawWidth, rawHeight)
        screenCapturer!!.startCapture(captureWidth, captureHeight, CAPTURE_FPS)

        pcm.addVideoTrackAndOffer(videoSource!!)
    }

    /** Tạo listener signaling dùng chung cho cả lần kết nối đầu tiên lẫn mỗi lần kết nối lại
     * ([prepareForReconnect]) — tránh lặp code, và đảm bảo hành vi luôn nhất quán. */
    private fun buildHostListener() = object : SignalingClient.Listener {
        override fun onOfferReceived(sdp: String) {} // host không nhận offer
        override fun onAnswerReceived(sdp: String) {
            peerConnectionManager?.handleAnswer(sdp)
        }
        override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
            peerConnectionManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
        }
        override fun onRemoteDisconnected() {
            // status=="ended" giờ chỉ do CHÍNH Máy B tự ghi khi thật sự kết thúc phiên (xem
            // cleanup()) — callback này gần như luôn là "tiếng vọng" từ chính B, không phải
            // tín hiệu từ A nữa (A không còn ghi "ended" khi ngắt kết nối). cleanup() đã có
            // chốt chặn gọi 2 lần (isCleanedUp) nên gọi lại ở đây vẫn an toàn.
            Log.d(TAG, "status=ended — kết thúc phiên")
            cleanup(); stopSelf()
        }
    }

    /**
     * Dựng lại PeerConnection + offer MỚI sau khi Máy A ngắt kết nối, GIỮ NGUYÊN mã ghép nối
     * và tiếp tục capture màn hình — để A (hoặc 1 A khác) có thể vào lại đúng mã này bất cứ
     * lúc nào, không cần B phải tạo mã mới. Đây là điểm khác biệt cốt lõi so với cleanup():
     * không đụng tới MediaProjection/screenCapturer/videoSource (vẫn đang chạy), không ghi
     * status="ended", không stopSelf().
     */
    private fun prepareForReconnect(code: String) {
        val vs = videoSource ?: run {
            Log.e(TAG, "prepareForReconnect: videoSource null — không thể tiếp tục, kết thúc phiên")
            cleanup(); stopSelf(); return
        }
        signalingClient?.clearForReconnect()
        signalingClient?.release()

        val newSignaling = SignalingClient(roomCode = code, isHost = true, listener = buildHostListener())
        signalingClient = newSignaling
        peerConnectionManager?.reinitForReconnect(newSignaling)
        peerConnectionManager?.addVideoTrackAndOffer(vs)
        newSignaling.setWaiting()
    }

    /**
     * Co kích thước capture theo đúng tỉ lệ khung hình gốc, giới hạn cạnh dài không vượt quá
     * [CAPTURE_MAX_DIMENSION]. Kích thước trả về luôn là số chẵn (bắt buộc với hầu hết encoder
     * phần cứng H264/VP8).
     */
    private fun scaledCaptureSize(rawWidth: Int, rawHeight: Int): Pair<Int, Int> {
        val longSide = maxOf(rawWidth, rawHeight)
        if (longSide <= CAPTURE_MAX_DIMENSION) {
            return (rawWidth and 1.inv()) to (rawHeight and 1.inv())
        }
        val scale = CAPTURE_MAX_DIMENSION.toFloat() / longSide.toFloat()
        val w = (rawWidth * scale).toInt() and 1.inv()
        val h = (rawHeight * scale).toInt() and 1.inv()
        return w to h
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

    private var isCleanedUp = false

    private fun cleanup() {
        // MediaProjection.Callback.onStop() và onDestroy() có thể cùng gọi cleanup() liên tiếp
        // trong 1 vòng đời (vd. hệ thống tự dừng chia sẻ màn hình -> cleanup()+stopSelf() ->
        // Android gọi onDestroy() -> cleanup() lần 2) — chặn double-release để tránh
        // eglBase.release() hoặc dispose() bị gọi 2 lần gây crash.
        if (isCleanedUp) return
        isCleanedUp = true

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

        // Cạnh dài tối đa khi capture (px) — 1280 vẫn đủ nét để đọc UI/văn bản trên điện thoại,
        // nhưng giảm đáng kể tải encoder so với capture full-res (thường 1080-1440 chiều ngắn).
        private const val CAPTURE_MAX_DIMENSION = 1280
        // Nội dung màn hình phần lớn tĩnh giữa các lần chạm — 20fps đủ mượt cho remote-control,
        // không cần 30fps như video call, giúp giảm tải mã hoá và băng thông cần thiết.
        private const val CAPTURE_FPS = 20
    }
}
