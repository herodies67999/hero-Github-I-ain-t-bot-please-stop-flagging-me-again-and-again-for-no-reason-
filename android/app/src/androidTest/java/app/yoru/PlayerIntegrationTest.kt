package app.yoru

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerIntegrationTest {
    @Test fun nativePlayerDecodesVideoAndStoresResumePosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("title", "Playback verification")
            .putExtra("progressKey", "verification")
            .putExtra("stream", """{"url":"https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4","headers":{},"subtitles":[],"hls":false}""")
        val activity = instrumentation.startActivitySync(intent) as PlayerActivity
        var decoded = false
        var failure: String? = null
        try {
            val until = android.os.SystemClock.elapsedRealtime() + 120000
            while(!decoded && failure == null && android.os.SystemClock.elapsedRealtime() < until) {
                instrumentation.runOnMainSync {
                    val field = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
                    val player = field.get(activity) as? ExoPlayer
                    decoded = player != null && player.currentPosition > 500 && player.videoSize.width > 0
                    failure = player?.playerError?.errorCodeName
                }
                Thread.sleep(250)
            }
            assertTrue("Video must decode and advance: $failure", decoded)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
        assertTrue(context.getSharedPreferences("player_progress", 0).getLong("verification", 0) > 0)
    }
}
