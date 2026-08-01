package Com.hau.name

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Nguồn DUY NHẤT để lấy kích thước màn hình vật lý thật (bao gồm cả vùng nav bar/status bar)
 * trên Máy B — đúng bằng độ phân giải mà MediaProjection thực sự capture.
 *
 * Lý do cần tách riêng: [RemoteHostService] (nơi capture) và [InputInjectionService] (nơi
 * dispatch gesture theo tọa độ Máy A gửi) đều cần quy đổi giữa tỷ lệ 0..1 và pixel, nên PHẢI
 * dùng chung một hệ quy chiếu kích thước màn hình. Trước đây 2 nơi gọi 2 API khác nhau
 * (`currentWindowMetrics.bounds` — bao gồm nav bar — ở nơi capture, và `resources.displayMetrics`
 * — loại trừ nav bar — ở nơi dispatch), khiến tọa độ chạm bị lệch đúng bằng chiều cao nav bar
 * khi thực thi trên Máy B, dù đã sửa phía hiển thị/chạm trên Máy A.
 */
object ScreenMetrics {
    fun realSize(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(it)
        }
        return metrics.widthPixels to metrics.heightPixels
    }
}
