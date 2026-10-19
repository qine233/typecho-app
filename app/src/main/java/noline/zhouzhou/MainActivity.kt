package noline.zhouzhou

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import noline.zhouzhou.ui.navigation.TypechoNavHost
import noline.zhouzhou.ui.theme.TypechoTheme
import noline.zhouzhou.ui.widget.refreshBillsWidget
import noline.zhouzhou.ui.widget.refreshHealthWidget

class MainActivity : ComponentActivity() {

    private val appContainer by lazy { (application as TypechoApplication).container }

    /**
     * 通知带进来的目标。走 StateFlow 而不是让 NavHost 自己去读 Activity intent：
     * App 还活着时点通知只会有 onNewIntent，那时图已经建好了，没人会再看第二眼。
     */
    private val deepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLink.value = intent?.dataString

        setContent {
            val darkMode by appContainer.settingsRepository.darkMode.collectAsState(initial = "system")
            val fontScale by appContainer.settingsRepository.fontScale.collectAsState(initial = 1.0f)
            val target by deepLink.collectAsState()
            val navController = rememberNavController()

            val useDarkTheme = when (darkMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            TypechoTheme(darkTheme = useDarkTheme, fontScale = fontScale) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TypechoNavHost(
                        appContainer = appContainer,
                        navController = navController,
                        deepLink = target,
                        onDeepLinkShown = { deepLink.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = intent.dataString
    }

    /**
     * 桌面卡片只读本地缓存，重画一次不花钱。App 一开就是它们最该跟上的时刻——
     * 刚记完账、刚称完重、刚升完级（升级之后卡片会一直停在加载圈，等系统那半小时的轮询太久）。
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            refreshBillsWidget(this@MainActivity)
            refreshHealthWidget(this@MainActivity)
        }
    }
}
