package noline.zhouzhou.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.outlined.*
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.ui.components.LocalNavAnimatedScope
import noline.zhouzhou.ui.components.LocalSharedTransitionScope
import noline.zhouzhou.ui.screens.admin.AdminScreen
import noline.zhouzhou.ui.screens.admin.CategoriesScreen
import noline.zhouzhou.ui.screens.admin.CommentsScreen
import noline.zhouzhou.ui.screens.admin.DuplicatesScreen
import noline.zhouzhou.ui.screens.admin.PagesScreen
import noline.zhouzhou.ui.screens.bills.BillReportScreen
import noline.zhouzhou.ui.screens.bills.BillsScreen
import noline.zhouzhou.ui.screens.btpanel.BtConsoleScreen
import noline.zhouzhou.ui.screens.btpanel.BtDatabaseDetailScreen
import noline.zhouzhou.ui.screens.btpanel.BtDatabasesScreen
import noline.zhouzhou.ui.screens.btpanel.BtCronScreen
import noline.zhouzhou.ui.screens.btpanel.BtFilesScreen
import noline.zhouzhou.ui.screens.btpanel.BtFtpScreen
import noline.zhouzhou.ui.screens.btpanel.BtMysqlToolsScreen
import noline.zhouzhou.ui.screens.btpanel.BtPanelScreen
import noline.zhouzhou.ui.screens.btpanel.BtSiteDetailScreen
import noline.zhouzhou.ui.screens.btpanel.BtSitesScreen
import noline.zhouzhou.ui.screens.diary.DiaryScreen
import noline.zhouzhou.ui.screens.drive.DriveScreen
import noline.zhouzhou.ui.screens.drive.ShareScreen
import noline.zhouzhou.ui.screens.editor.EditorScreen
import noline.zhouzhou.ui.screens.health.HealthScreen
import noline.zhouzhou.ui.screens.home.HomeScreen
import noline.zhouzhou.ui.screens.login.LoginScreen
import noline.zhouzhou.ui.screens.post.PostDetailScreen
import noline.zhouzhou.ui.screens.search.SearchScreen
import noline.zhouzhou.ui.screens.settings.SettingsScreen
import noline.zhouzhou.ui.screens.settings.StorageScreen

sealed class Screen(val route: String) {
    /** 首页带一个可选的分类参数，供详情页的 #话题 跳转复用。 */
    data object Home : Screen("home") {
        const val PATTERN = "home?category={category}"
        fun createRoute(category: String? = null) =
            "home?category=" + android.net.Uri.encode(category.orEmpty())
    }
    data object PostDetail : Screen("post/{postId}") {
        fun createRoute(postId: String) = "post/$postId"
    }
    data object Login : Screen("login")
    data object Settings : Screen("settings")
    data object Admin : Screen("admin")
    data object Diary : Screen("diary")
    data object Health : Screen("health")
    /** 全站搜索单独一页：带进首页搜索药丸的初始词，从历史里挑词时也用它。 */
    data object Search : Screen("search?query={query}") {
        fun createRoute(query: String? = null) = "search?query=" + android.net.Uri.encode(query.orEmpty())
    }
    data object Bills : Screen("bills")
    /** 月报按月份进：概览页的柱子、通知里那句超支提醒都能把它推上来。 */
    data object BillReport : Screen("bills/report/{month}") {
        fun createRoute(month: String) = "bills/report/$month"
    }
    data object Editor : Screen("editor?postId={postId}&isPage={isPage}") {
        fun createRoute(postId: String? = null, isPage: Boolean = false) =
            "editor?postId=${postId.orEmpty()}&isPage=$isPage"
    }
    data object Comments : Screen("comments")
    data object Storage : Screen("settings/storage")
    data object Pages : Screen("admin/pages")
    data object Categories : Screen("admin/categories")
    /** 重复文章清理：整站翻页扫一遍，删的是正文一模一样的副本。 */
    data object Duplicates : Screen("admin/duplicates")
    data object Drive : Screen("cloudreve/drive")
    data object Shares : Screen("cloudreve/shares")
    /** 宝塔面板管的是服务器，不是站点内容；管理员的标签少一个，这一格补在末位。 */
    data object BtPanel : Screen("settings/btpanel")

    /** 通用调用台：官方文档里那 329 条接口都从宝塔页这一格进去。 */
    data object BtConsole : Screen("settings/btpanel/console")

    /** 网站管理：站点列表 + 建/停/删。 */
    data object BtSites : Screen("settings/btpanel/sites")

    /**
     * 单个站点的各页签。域名和路径都要 encode：
     * 路径天生带斜杠，域名偶尔带端口，直接拼进路由会被切成额外的段。
     */
    data object BtSiteDetail : Screen("settings/btpanel/site/{siteId}/{siteName}/{sitePath}") {
        fun createRoute(siteId: String, siteName: String, sitePath: String) =
            "settings/btpanel/site/$siteId/" +
                android.net.Uri.encode(siteName) + "/" +
                android.net.Uri.encode(sitePath)
    }

    /** 数据库管理：库列表 + 建/备/删 + 单库详情。 */
    data object BtDatabases : Screen("settings/btpanel/databases")

    /** MySQL 管理：运行时读数 + 超时 / Binlog / 守护进程 / 账户。 */
    data object BtMysqlTools : Screen("settings/btpanel/mysql")

    /** FTP 管理：账户列表 + 建/停/改密码/限额/日志/删。 */
    data object BtFtp : Screen("settings/btpanel/ftp")

    /** 计划任务：任务列表 + 建/改/启停/执行/日志/删 + 批量 + 分类。 */
    data object BtCron : Screen("settings/btpanel/cron")

    /** 文件管理器：目录浏览 + 建/改名/移动/复制/软链/解压/上传/权限/删除 + 回收站 + 操作历史。 */
    data object BtFiles : Screen("settings/btpanel/files")

    /**
     * 单个库的详情页。库名和账号名都要 encode：
     * 面板允许点号和中划线，偶尔也有以数字打头的名字，路由本身不挑，但访问来源那一格带逗号要转义。
     */
    data object BtDatabaseDetail : Screen("settings/btpanel/db/{dbId}/{dbName}/{dbUser}/{dbSid}") {
        fun createRoute(dbId: String, dbName: String, dbUser: String, dbSid: String) =
            "settings/btpanel/db/$dbId/" +
                android.net.Uri.encode(dbName) + "/" +
                android.net.Uri.encode(dbUser) + "/" +
                dbSid
    }
}

private val HomeTab = TabSpec(
    route = Screen.Home.route,
    label = "首页",
    outlineIcon = Icons.Outlined.Home,
    filledIcon = Icons.Filled.Home,
    navRoute = Screen.Home.createRoute()
)
private val DiaryTab = TabSpec(
    Screen.Diary.route, "日记", Icons.Outlined.MenuBook, Icons.Filled.MenuBook
)
private val AdminTab = TabSpec(
    Screen.Admin.route, "管理", Icons.Outlined.Edit, Icons.Filled.Edit
)
private val HealthTab = TabSpec(
    Screen.Health.route, "健康", Icons.Outlined.ShowChart, Icons.Filled.ShowChart
)
private val SettingsTab = TabSpec(
    Screen.Settings.route, "设置", Icons.Outlined.Settings, Icons.Filled.Settings
)
private val BtPanelTab = TabSpec(
    Screen.BtPanel.route, "宝塔", Icons.Outlined.Dns, Icons.Filled.Dns
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalAnimationApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun TypechoNavHost(
    appContainer: AppContainer,
    navController: NavHostController = rememberNavController(),
    deepLink: String? = null,
    onDeepLinkShown: () -> Unit = {}
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    // 带参数注册的目标，destination.route 是「home?category={category}」这样的模板，
    // 直接比较会失配，故取出路径部分再比对。
    val currentRoute = navBackStackEntry?.destination?.route
        ?.substringBefore("?")?.substringBefore("/{")

    val loggedIn = appContainer.settingsRepository.isLoggedIn
        // null 表示 DataStore 还没发出第一个值。这时就定 startDestination 会把已登录的用户
        // 闪到登录页（冷启动几乎必现），所以宁可先空一帧。
        .collectAsState(initial = null).value
    val isAdmin by appContainer.settingsRepository.isAdmin.collectAsState(initial = false)

    if (loggedIn == null) return

    val showBottomBar = currentRoute in listOf(
        Screen.Home.route, Screen.Diary.route, Screen.Health.route,
        Screen.Admin.route, Screen.Settings.route, Screen.BtPanel.route
    ) && loggedIn

    // 健康数据是站主本人的，读者不需要这个标签。宝塔那一格补在末位，
    // 免得五格摆出来右边空着一块（非管理员三格，居中靠补位撑）。
    val tabs = remember(isAdmin) {
        if (isAdmin) listOf(HomeTab, DiaryTab, HealthTab, AdminTab, SettingsTab, BtPanelTab)
        else listOf(HomeTab, DiaryTab, SettingsTab)
    }

    // 切标签只有一种走法。页面里跳去设置页也必须走它：直接 navigate 会把设置页
    // 压在健康页之上，回头再点「健康」时弹栈和动画对不上，页面就卡在设置页不动了。
    val switchTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // 通知点进来落在「数据与存储」，超支那句落在记账页，小组件那半张表单落在记账面板，
    // 「记体重」落在健康页并当场拉开录入面板。
    // 放在 loggedIn 解析之后，是为了等图建好，否则 navigate 会打在一个还不存在的路由上。
    var billsPrefill by remember { mutableStateOf<BillPrefill?>(null) }
    var healthAdd by remember { mutableStateOf(false) }
    LaunchedEffect(deepLink) {
        val parsed = parseAppLink(deepLink) ?: return@LaunchedEffect
        billsPrefill = parsed.prefill
        healthAdd = parsed.openAdd
        // 落点是底部那几个标签之一时走 switchTab，弹栈形状要和用户亲手点那一下一致；
        // 直接 navigate 会把健康页压在当前标签之上，回头切标签就对不上。
        if (parsed.route in tabs.map { it.route }) switchTab(parsed.route)
        else navController.navigate(parsed.route) { launchSingleTop = true }
        onDeepLinkShown()
    }

    // 底栏是悬浮的，页面要从它底下滚过去，所以整块内容先被录成一层可采样的画面，
    // 底栏再拿这一层去模糊——玻璃要真透过去看见东西才算玻璃。
    val hazeState = remember { HazeState() }

    Box(modifier = Modifier.fillMaxSize()) {
        SharedTransitionLayout {
            // 共享元素要在同一个作用域里登记，页与页才知道谁和谁是同一块封面。
            val sharedScope: SharedTransitionScope = this@SharedTransitionLayout
            NavHost(
                navController = navController,
                startDestination = if (loggedIn) Screen.Home.createRoute() else Screen.Login.route,
                modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                enterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally { it / 4 } },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(300)) },
                popExitTransition = { fadeOut(animationSpec = tween(200)) + slideOutHorizontally { it / 4 } }
            ) {
                composable(Screen.Login.route) {
                    LoginScreen(
                        appContainer = appContainer,
                        onLoginSuccess = {
                            navController.navigate(Screen.Home.createRoute()) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        }
                    )
                }

                composable(
                    route = Screen.Home.PATTERN,
                    arguments = listOf(navArgument("category") {
                        type = NavType.StringType
                        defaultValue = ""
                    })
                ) { entry ->
                    SharedScope(sharedScope, this) {
                        HomeScreen(
                            appContainer = appContainer,
                            initialCategory = entry.arguments?.getString("category").orEmpty(),
                            onPostClick = { postId ->
                                navController.navigate(Screen.PostDetail.createRoute(postId))
                            },
                            onSearchClick = { navController.navigate(Screen.Search.createRoute()) }
                        )
                    }
                }

                composable(
                    route = Screen.Search.route,
                    arguments = listOf(navArgument("query") {
                        type = NavType.StringType
                        defaultValue = ""
                    })
                ) { entry ->
                    SharedScope(sharedScope, this) {
                        SearchScreen(
                            appContainer = appContainer,
                            initialQuery = entry.arguments?.getString("query").orEmpty(),
                            onBack = { navController.popBackStack() },
                            onPostClick = { postId ->
                                navController.navigate(Screen.PostDetail.createRoute(postId))
                            }
                        )
                    }
                }

                composable(Screen.Diary.route) {
                    SharedScope(sharedScope, this) {
                        DiaryScreen(
                            appContainer = appContainer,
                            onPostClick = { postId ->
                                navController.navigate(Screen.PostDetail.createRoute(postId))
                            }
                        )
                    }
                }

                composable(Screen.Health.route) {
                    HealthScreen(
                        appContainer = appContainer,
                        onOpenSettings = { switchTab(Screen.Settings.route) },
                        onOpenBills = { navController.navigate(Screen.Bills.route) },
                        initialAdd = healthAdd,
                        onPrefillConsumed = { healthAdd = false }
                    )
                }

                composable(Screen.Bills.route) {
                    BillsScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() },
                        onOpenSettings = { switchTab(Screen.Settings.route) },
                        onOpenReport = { month ->
                            navController.navigate(Screen.BillReport.createRoute(month))
                        },
                        initialAdd = billsPrefill,
                        onPrefillConsumed = { billsPrefill = null }
                    )
                }

                composable(
                    route = Screen.BillReport.route,
                    arguments = listOf(navArgument("month") { type = NavType.StringType })
                ) { entry ->
                    BillReportScreen(
                        appContainer = appContainer,
                        month = entry.arguments?.getString("month").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenSettings = { switchTab(Screen.Settings.route) }
                    )
                }

                composable(Screen.Storage.route) {
                    StorageScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.BtPanel.route) {
                    BtPanelScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() },
                        onOpenConsole = { navController.navigate(Screen.BtConsole.route) },
                        onOpenSites = { navController.navigate(Screen.BtSites.route) },
                        onOpenDatabases = { navController.navigate(Screen.BtDatabases.route) },
                        onOpenFtp = { navController.navigate(Screen.BtFtp.route) },
                        onOpenCron = { navController.navigate(Screen.BtCron.route) },
                        onOpenFiles = { navController.navigate(Screen.BtFiles.route) }
                    )
                }

                composable(Screen.BtSites.route) {
                    BtSitesScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() },
                        onOpenSite = { site ->
                            navController.navigate(
                                Screen.BtSiteDetail.createRoute(site.id, site.name, site.path)
                            )
                        }
                    )
                }

                composable(
                    route = Screen.BtSiteDetail.route,
                    arguments = listOf(
                        navArgument("siteId") { type = NavType.StringType },
                        navArgument("siteName") { type = NavType.StringType },
                        navArgument("sitePath") { type = NavType.StringType }
                    )
                ) { entry ->
                    BtSiteDetailScreen(
                        appContainer = appContainer,
                        siteId = entry.arguments?.getString("siteId").orEmpty(),
                        siteName = entry.arguments?.getString("siteName").orEmpty(),
                        sitePath = entry.arguments?.getString("sitePath").orEmpty(),
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.BtConsole.route) {
                    BtConsoleScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.BtDatabases.route) {
                    BtDatabasesScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() },
                        onOpenDb = { db ->
                            navController.navigate(
                                Screen.BtDatabaseDetail.createRoute(db.id, db.name, db.user, db.sid)
                            )
                        },
                        onOpenTools = { navController.navigate(Screen.BtMysqlTools.route) }
                    )
                }

                composable(
                    route = Screen.BtDatabaseDetail.route,
                    arguments = listOf(
                        navArgument("dbId") { type = NavType.StringType },
                        navArgument("dbName") { type = NavType.StringType },
                        navArgument("dbUser") { type = NavType.StringType },
                        navArgument("dbSid") { type = NavType.StringType }
                    )
                ) { entry ->
                    // 列表行上的十个字段只有这四个进得了路由；其余（访问来源、备注、占用）
                    // 详情页自己按库名再问一遍面板，不从列表那边假装带过来。
                    BtDatabaseDetailScreen(
                        appContainer = appContainer,
                        database = noline.zhouzhou.data.model.BtDatabase(
                            id = entry.arguments?.getString("dbId").orEmpty(),
                            name = entry.arguments?.getString("dbName").orEmpty(),
                            user = entry.arguments?.getString("dbUser").orEmpty(),
                            accept = "",
                            remark = "",
                            addTime = "",
                            sid = entry.arguments?.getString("dbSid").orEmpty(),
                            engine = "MySQL",
                            backupCount = null,
                            usedBytes = null
                        ),
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.BtMysqlTools.route) {
                    BtMysqlToolsScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.BtFtp.route) {
                    BtFtpScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.BtCron.route) {
                    BtCronScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.BtFiles.route) {
                    BtFilesScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Screen.PostDetail.route,
                    arguments = listOf(navArgument("postId") { type = NavType.StringType }),
                    // 详情页只做淡入淡出：封面已经由共享元素从卡片飞到首屏，
                    // 页面再横着滑会让同一块图分成两股劲儿。
                    enterTransition = { fadeIn(animationSpec = tween(300)) },
                    exitTransition = { fadeOut(animationSpec = tween(200)) },
                    popEnterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally { it / 4 } },
                    popExitTransition = { fadeOut(animationSpec = tween(200)) + slideOutHorizontally { it / 4 } }
                ) { backStackEntry ->
                    val postId = backStackEntry.arguments?.getString("postId") ?: ""
                    SharedScope(sharedScope, this) {
                        PostDetailScreen(
                            appContainer = appContainer,
                            postId = postId,
                            onBack = { navController.popBackStack() },
                            onEditPost = { navController.navigate(Screen.Editor.createRoute(postId)) },
                            onLoginRequired = { navController.navigate(Screen.Login.route) },
                            onCategoryClick = { category ->
                                navController.navigate(Screen.Home.createRoute(category)) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }

                composable(Screen.Admin.route) {
                    AdminScreen(
                        appContainer = appContainer,
                        onPostClick = { postId -> navController.navigate(Screen.PostDetail.createRoute(postId)) },
                        onNewPost = { navController.navigate(Screen.Editor.createRoute()) },
                        onEditPost = { postId -> navController.navigate(Screen.Editor.createRoute(postId)) },
                        onViewComments = { navController.navigate(Screen.Comments.route) },
                        onViewPages = { navController.navigate(Screen.Pages.route) },
                        onViewCategories = { navController.navigate(Screen.Categories.route) },
                        onViewDrive = { navController.navigate(Screen.Drive.route) },
                        onViewDuplicates = { navController.navigate(Screen.Duplicates.route) }
                    )
                }

                composable(Screen.Duplicates.route) {
                    DuplicatesScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() },
                        onPostClick = { postId -> navController.navigate(Screen.PostDetail.createRoute(postId)) }
                    )
                }

                composable(Screen.Drive.route) {
                    DriveScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() },
                        onOpenShares = { navController.navigate(Screen.Shares.route) }
                    )
                }

                composable(Screen.Shares.route) {
                    ShareScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.Pages.route) {
                    PagesScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() },
                        onNewPage = { navController.navigate(Screen.Editor.createRoute(isPage = true)) },
                        onEditPage = { pageId ->
                            navController.navigate(Screen.Editor.createRoute(pageId, isPage = true))
                        }
                    )
                }

                composable(Screen.Categories.route) {
                    CategoriesScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() },
                        // 首页的分类筛选就是按名字过滤已加载文章，直接复用它。
                        onOpenCategory = { name -> switchTab(Screen.Home.createRoute(name)) }
                    )
                }

                composable(Screen.Comments.route) {
                    CommentsScreen(
                        appContainer = appContainer,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Screen.Editor.route,
                    arguments = listOf(
                        navArgument("postId") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("isPage") {
                            type = NavType.BoolType
                            defaultValue = false
                        }
                    )
                ) { backStackEntry ->
                    val postId = backStackEntry.arguments?.getString("postId")?.takeIf { it.isNotBlank() }
                    val isPage = backStackEntry.arguments?.getBoolean("isPage") ?: false
                    EditorScreen(
                        appContainer = appContainer,
                        postId = postId,
                        isPage = isPage,
                        onBack = { navController.popBackStack() },
                        onSaved = { navController.popBackStack() }
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        appContainer = appContainer,
                        onOpenStorage = { navController.navigate(Screen.Storage.route) },
                        onOpenBtPanel = { switchTab(Screen.BtPanel.route) },
                        onLogout = {
                            navController.navigate(Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    )
                }
            }
        }

        if (showBottomBar) {
            TypechoTabBar(
                tabs = tabs,
                currentRoute = currentRoute,
                hazeState = hazeState,
                onTabSelected = { tab -> switchTab(tab.navRoute) },
                onCreateClick = { navController.navigate(Screen.Editor.createRoute()) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * 共享元素要同时知道「哪个 SharedTransitionLayout 登记它」和「这一页的进出动画作用域」，
 * 后者每个目的地各不相同，只能在这里逐个递给页面。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedScope(
    shared: SharedTransitionScope,
    animated: AnimatedContentScope,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalSharedTransitionScope provides shared,
        LocalNavAnimatedScope provides animated,
        content = content
    )
}
