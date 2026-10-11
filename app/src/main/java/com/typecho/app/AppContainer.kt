package com.typecho.app

import android.app.Application
import com.typecho.app.data.remote.NolineApi
import com.typecho.app.data.remote.TypechoApi
import com.typecho.app.data.repository.*

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
}

class TypechoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
