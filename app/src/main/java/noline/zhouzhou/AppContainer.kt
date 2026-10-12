package noline.zhouzhou

import android.app.Application
import noline.zhouzhou.data.remote.NolineApi
import noline.zhouzhou.data.remote.TypechoApi
import noline.zhouzhou.data.repository.*

class AppContainer(val application: Application) {
    val api: TypechoApi by lazy { TypechoApi() }
    val nolineApi: NolineApi by lazy { NolineApi() }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(application) }
    val postRepository: PostRepository by lazy { PostRepository(api) }
    val postCache: PostCache by lazy { PostCache() }
    val postCacheStore: PostCacheStore by lazy { PostCacheStore(application) }
    val interactionRepository: InteractionRepository by lazy { InteractionRepository(application) }
    val commentRepository: CommentRepository by lazy { CommentRepository(api) }
    val userRepository: UserRepository by lazy { UserRepository(api) }
    val healthLocalStore: HealthLocalStore by lazy { HealthLocalStore(application) }
    val healthRepository: HealthRepository by lazy {
        HealthRepository(nolineApi, settingsRepository, healthLocalStore)
    }
    val updateRepository: UpdateRepository by lazy { UpdateRepository(application) }
}

class TypechoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
