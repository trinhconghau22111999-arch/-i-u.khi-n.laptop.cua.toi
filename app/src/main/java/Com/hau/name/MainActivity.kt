package Com.hau.name

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Màn hình đầu tiên: người dùng chọn vai trò của máy này.
 * - "Máy được điều khiển" (Máy B) -> ConsentActivity
 * - "Máy điều khiển" (Máy A) -> ControllerActivity
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<android.widget.Button>(R.id.btn_role_controlled).setOnClickListener {
            startActivity(Intent(this, ConsentActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.btn_role_controller).setOnClickListener {
            startActivity(Intent(this, ControllerActivity::class.java))
        }
    }
}
