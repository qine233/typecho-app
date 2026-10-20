package noline.zhouzhou

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import noline.zhouzhou.data.remote.CloudreveApi
import noline.zhouzhou.data.remote.NolineApi
import noline.zhouzhou.data.remote.SearchFeedApi
import noline.zhouzhou.data.remote.TypechoApi
import noline.zhouzhou.data.repository.*
import noline.zhouzhou.data.sync.StatusRepository
import noline.zhouzhou.data.sync.UpdateDownloadState

class AppContainer(val application: Application) {
    val api: TypechoApi by lazy { TypechoApi() }
    val nolineApi: NolineApi by lazy { NolineApi() }
    val cloudreveApi: CloudreveApi by lazy { CloudreveApi() }
    val searchFeedApi: SearchFeedApi by lazy { SearchFeedApi() }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(application) }
    val postRepository: PostRepository by lazy { PostRepository(api) }
    val postCache: PostCache by lazy { PostCache() }
    val postCacheStore: PostCacheStore by lazy { PostCacheStore(application) }
    val searchRepository: SearchRepository by lazy {
        SearchRepository(searchFeedApi, settingsRepository, postCache, postCacheStore)
    }
    val draftStore: DraftStore by lazy { DraftStore(application) }
    val storageRepository: StorageRepository by lazy { StorageRepository(application) }

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
    val billsLocalStore: BillsLocalStore by lazy { BillsLocalStore(application) }
    val billsRepository: BillsRepository by lazy {
        BillsRepository(nolineApi, settingsRepository, billsLocalStore)
    }
    val updateRepository: UpdateRepository by lazy { UpdateRepository(application) }

    /**
     * 更新包下载的中转状态：前台服务写、设置页读。放在这里是因为它必须活得比
     * ViewModel 久——人切去后台下载还在跑，回来那一页要能立刻接上进度。
     */
    val updateDownload = UpdateDownloadState()
    val cloudreveRepository: CloudreveRepository by lazy {
        CloudreveRepository(cloudreveApi) { settingsRepository.cloudreveConfig.first() }
    }
    val btPanelRepository: BtPanelRepository by lazy { BtPanelRepository(settingsRepository) }
    val btSiteRepository: BtSiteRepository by lazy { BtSiteRepository(btPanelRepository) }
    val btDatabaseRepository: BtDatabaseRepository by lazy { BtDatabaseRepository(btPanelRepository) }
    val btFtpRepository: BtFtpRepository by lazy { BtFtpRepository(btPanelRepository) }
    val btCronRepository: BtCronRepository by lazy { BtCronRepository(btPanelRepository) }
    val btCatalogStore: BtCatalogStore by lazy { BtCatalogStore(application) }
    val statusRepository: StatusRepository by lazy {
        StatusRepository(
            context = application,
            settings = settingsRepository,
            healthStore = healthLocalStore,
            billsStore = billsLocalStore,
            draftStore = draftStore,
            healthRepository = healthRepository,
            billsRepository = billsRepository
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 队列刚变过就让通知改口。周期任务最短 15 分钟一轮，等那一轮的话，
     * 用户刚离线记的一条会在通知里缺席很久。
     */
    fun refreshStatusNotice() {
        scope.launch { runCatching { statusRepository.publish() } }
    }

    /** 进程起来时对一遍账：开关开着就把周期任务补上，通知跟着当前队列刷新。 */
    fun resumeStatusNotice() {
        scope.launch { runCatching { statusRepository.resumeFromSettings() } }
    }
}

class TypechoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 进程可能被系统回收过，这期间队列早变了样，起来第一件事是把通知对回实际条数。
        container.resumeStatusNotice()
    }
}
