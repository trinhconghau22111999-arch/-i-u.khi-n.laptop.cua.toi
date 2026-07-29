package Com.hau.name

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase

/**
 * Máy B (máy sẽ bị/được điều khiển).
 *
 * Luồng bắt buộc:
 * 1. Người dùng phải tự tick vào ô đồng ý -> nút "Tạo mã" mới bật.
 * 2. Bấm tạo mã sẽ trigger hộp thoại quay màn hình chuẩn của Android
 *    (MediaProjection) - hộp thoại này do hệ điều hành vẽ, không thể tùy biến,
 *    người dùng luôn thấy rõ nội dung xin phép.
 * 3. Sau khi cấp quyền, tạo mã 6 số ngẫu nhiên và ghi lên Firebase.
 */
class ConsentActivity : AppCompatActivity() {

    private lateinit var checkboxConsent: CheckBox
    private lateinit var btnGenerateCode: Button
    private lateinit var layoutPairingCode: android.widget.LinearLayout
    private lateinit var textPairingCode: TextView
    private lateinit var btnEndSession: Button

    private val database = FirebaseDatabase.getInstance().reference
    private var roomCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consent)

        checkboxConsent = findViewById(R.id.checkbox_consent)
        btnGenerateCode = findViewById(R.id.btn_generate_code)
        layoutPairingCode = findViewById(R.id.layout_pairing_code)
        textPairingCode = findViewById(R.id.text_pairing_code)
        btnEndSession = findViewById(R.id.btn_end_session)

        // Nút tạo mã chỉ bật khi người dùng đã tick đồng ý — bắt buộc theo yêu cầu.
        btnGenerateCode.isEnabled = false
        checkboxConsent.setOnCheckedChangeListener { _, isChecked ->
            btnGenerateCode.isEnabled = isChecked
        }

        btnGenerateCode.setOnClickListener {
            requestNotificationPermissionIfNeeded()
            requestScreenCapturePermission()
        }

        btnEndSession.setOnClickListener {
            endSession()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }

    private fun requestScreenCapturePermission() {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Hộp thoại hệ thống — Android tự vẽ nội dung xin phép, không thể ẩn hay tùy biến.
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_CODE_SCREEN_CAPTURE
        )
    }

    @Deprecated("Dùng activity result API mới trong bản mở rộng nếu cần")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                generatePairingCodeAndStartService(data)
            } else {
                Toast.makeText(this, "Bạn đã từ chối cấp quyền chia sẻ màn hình", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generatePairingCodeAndStartService(projectionData: Intent) {
        val code = (100000..999999).random().toString()
        roomCode = code

        database.child("rooms").child(code).setValue(
            mapOf(
                "status" to "waiting",
                "consentGivenAt" to System.currentTimeMillis()
            )
        )

        // TODO: khởi động RemoteHostService thật, truyền kèm projectionData
        // để service dùng MediaProjection + WebRTC bắt đầu stream màn hình.
        val serviceIntent = Intent(this, RemoteHostService::class.java).apply {
            putExtra(RemoteHostService.EXTRA_ROOM_CODE, code)
            putExtra(RemoteHostService.EXTRA_PROJECTION_DATA, projectionData)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        textPairingCode.text = code
        layoutPairingCode.visibility = android.view.View.VISIBLE
    }

    private fun endSession() {
        roomCode?.let { code ->
            database.child("rooms").child(code).child("status").setValue("ended")
        }
        stopService(Intent(this, RemoteHostService::class.java))
        layoutPairingCode.visibility = android.view.View.GONE
        checkboxConsent.isChecked = false
    }

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1001
    }
}
