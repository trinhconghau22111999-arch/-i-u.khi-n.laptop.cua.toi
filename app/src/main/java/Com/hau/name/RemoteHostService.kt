package Com.hau.name

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase

/**
 * Chạy trên Máy B trong suốt thời gian màn hình đang được chia sẻ.
 *
 * Nguyên tắc bắt buộc: thông báo (notification) LUÔN hiện, không được ẩn,
 * và có nút "Ngắt kết nối" ngay trên thông báo để người dùng chủ động dừng
 * bất cứ lúc nào — đây là yêu cầu minh bạch cốt lõi của thiết kế.
 */
class RemoteHostService : Service() {

    private var mediaProjection: MediaProjection? = null
    private val database = FirebaseDatabase.getInstance().reference
    private var roomCode: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        roomCode = intent?.getStringExtra(EXTRA_ROOM_CODE)
        val projectionData = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

        startForeground(NOTIF_ID, buildNotification(), foregroundServiceType())

        if (projectionData != null) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(RESULT_OK_PLACEHOLDER, projectionData)
            // TODO: khởi tạo WebRTC VideoCapturer từ mediaProjection và bắt đầu
            // gửi offer lên Firebase (child("rooms").child(roomCode).child("offer"))
            // để Máy A nhận và hiển thị.
        }

        listenForConnectionStatus()
        return START_STICKY
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
                NotificationChannel(
                    channelId,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val stopIntent = Intent(this, RemoteHostService::class.java).apply {
            action = ACTION_STOP_SHARING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true) // Không cho phép người dùng vuốt để xóa ngầm định
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Ngắt kết nối", stopPendingIntent)
            .build()
    }

    private fun listenForConnectionStatus() {
        // TODO: lắng nghe roomCode để nhận offer/answer/ICE candidates
        // và cập nhật trạng thái "connected" khi Máy A ghép nối thành công.
    }

    override fun onDestroy() {
        roomCode?.let { code ->
            database.child("rooms").child(code).child("status").setValue("ended")
        }
        mediaProjection?.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROOM_CODE = "extra_room_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val ACTION_STOP_SHARING = "action_stop_sharing"
        private const val NOTIF_ID = 42
        // Lưu ý: dùng resultCode thật (RESULT_OK) nhận được từ onActivityResult,
        // không hardcode. Đây chỉ là placeholder để scaffold biên dịch được.
        private const val RESULT_OK_PLACEHOLDER = -1
    }
}
