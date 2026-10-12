package noline.zhouzhou

import android.app.Application
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import noline.zhouzhou.data.remote.CloudreveApi
import noline.zhouzhou.data.remote.NolineApi
import noline.zhouzhou.data.remote.TypechoApi
import noline.zhouzhou.data.repository.*

class AppContainer(val application: Application) {
    val api: TypechoApi by lazy { TypechoApi() }
    val nolineApi: NolineApi by lazy { NolineApi() }
    val cloudreveApi: CloudreveApi by lazy { CloudreveApi() }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(application) }
    val postRepository: PostRepository by lazy { PostRepository(api) }
    val postCache: PostCache by lazy { PostCache() }
    val postCacheStore: PostCacheStore by lazy { PostCacheStore(application) }
    val draftStore: DraftStore by lazy { DraftStore(application) }

    /**
     * 站点内容变了（存稿、发布、删文章、动分类）。列表页的 ViewModel 活在导航返回栈里，
     * 不会因为回到首页就重建，所以只能靠这条事件重取，否则新文章要退出 App 重进才看得见。
     */
    val contentChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    fun notifyContentChanged() {
        contentChanged.tryEmit(Unit)
    }

    val interactionRepository: InteractionRepository by lazy { InteractionRepository(application) }
    val commentRepository: CommentRepository by lazy { CommentRepository(api) }
    val userRepository: UserRepository by lazy { UserRepository(api) }
    val healthLocalStore: HealthLocalStore by lazy { HealthLocalStore(application) }
    val healthRepository: HealthRepository by lazy {
        HealthRepository(nolineApi, settingsRepository, healthLocalStore)
    }
    val updateRepository: UpdateRepository by lazy { UpdateRepository(application) }
    val cloudreveRepository: CloudreveRepository by lazy {
        CloudreveRepository(cloudreveApi) { settingsRepository.cloudreveConfig.first() }
    }
}

class TypechoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
