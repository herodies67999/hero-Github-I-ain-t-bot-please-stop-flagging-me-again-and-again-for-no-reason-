package app.yoru

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { YoruApp(model) }
    }

    override fun onResume() {
        super.onResume()
        if (::safeModel.isInitialized) model.reloadPlaybackProgress()
    }

    // Avoid constructing the ViewModel from onResume before the composition is installed.
    private lateinit var safeModel: AppModel

    override fun onStart() {
        super.onStart()
        safeModel = model
        model.reloadPlaybackProgress()
    }
}
