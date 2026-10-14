package noline.zhouzhou.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
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
import noline.zhouzhou.AppContainer
import noline.zhouzhou.R
import noline.zhouzhou.ui.screens.admin.AdminScreen
import noline.zhouzhou.ui.screens.admin.CategoriesScreen
import noline.zhouzhou.ui.screens.admin.CommentsScreen
import noline.zhouzhou.ui.screens.admin.PagesScreen
import noline.zhouzhou.ui.screens.bills.BillsScreen
import noline.zhouzhou.ui.screens.diary.DiaryScreen
import noline.zhouzhou.ui.screens.drive.DriveScreen
import noline.zhouzhou.ui.screens.drive.ShareScreen
import noline.zhouzhou.ui.screens.editor.EditorScreen
import noline.zhouzhou.ui.screens.health.HealthScreen
import noline.zhouzhou.ui.screens.home.HomeScreen
import noline.zhouzhou.ui.screens.login.LoginScreen
import noline.zhouzhou.ui.screens.post.PostDetailScreen
import noline.zhouzhou.ui.screens.settings.SettingsScreen

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
    data object Bills : Screen("bills")
    data object Editor : Screen("editor?postId={postId}&isPage={isPage}") {
        fun createRoute(postId: String? = null, isPage: Boolean = false) =
            "editor?postId=${postId.orEmpty()}&isPage=$isPage"
    }
    data object Comments : Screen("comments")
    data object Pages : Screen("admin/pages")
    data object Categories : Screen("admin/categories")
    data object Drive : Screen("cloudreve/drive")
    data object Shares : Screen("cloudreve/shares")
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun TypechoNavHost(
    appContainer: AppContainer,
    navController: NavHostController = rememberNavController()
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
        Screen.Admin.route, Screen.Settings.route
    ) && loggedIn

    // 健康数据是站主本人的，读者不需要这个标签。
    val tabs = remember(isAdmin) {
        if (isAdmin) listOf(HomeTab, DiaryTab, HealthTab, AdminTab, SettingsTab)
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

    Scaffold(
        // 外层 Scaffold 仅负责底部导航栏；顶部/底部系统栏内距交给各页面自己的 Scaffold，
        // 否则状态栏内距会被重复计算，在标题栏上方留出一块空白。
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        bottomBar = {
            if (showBottomBar) {
                TypechoTabBar(
                    tabs = tabs,
                    currentRoute = currentRoute,
                    onTabSelected = { tab -> switchTab(tab.navRoute) },
                    onCreateClick = { navController.navigate(Screen.Editor.createRoute()) }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (loggedIn) Screen.Home.createRoute() else Screen.Login.route,
            modifier = Modifier.padding(innerPadding),
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
                HomeScreen(
                    appContainer = appContainer,
                    initialCategory = entry.arguments?.getString("category").orEmpty(),
                    onPostClick = { postId ->
                        navController.navigate(Screen.PostDetail.createRoute(postId))
                    }
                )
            }

            composable(Screen.Diary.route) {
                DiaryScreen(
                    appContainer = appContainer,
                    onPostClick = { postId ->
                        navController.navigate(Screen.PostDetail.createRoute(postId))
                    }
                )
            }

            composable(Screen.Health.route) {
                HealthScreen(
                    appContainer = appContainer,
                    onOpenSettings = { switchTab(Screen.Settings.route) },
                    onOpenBills = { navController.navigate(Screen.Bills.route) }
                )
            }

            composable(Screen.Bills.route) {
                BillsScreen(
                    appContainer = appContainer,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { switchTab(Screen.Settings.route) }
                )
            }

            composable(
                route = Screen.PostDetail.route,
                arguments = listOf(navArgument("postId") { type = NavType.StringType })
            ) { backStackEntry ->
                val postId = backStackEntry.arguments?.getString("postId") ?: ""
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

            composable(Screen.Admin.route) {
                AdminScreen(
                    appContainer = appContainer,
                    onPostClick = { postId -> navController.navigate(Screen.PostDetail.createRoute(postId)) },
                    onNewPost = { navController.navigate(Screen.Editor.createRoute()) },
                    onEditPost = { postId -> navController.navigate(Screen.Editor.createRoute(postId)) },
                    onViewComments = { navController.navigate(Screen.Comments.route) },
                    onViewPages = { navController.navigate(Screen.Pages.route) },
                    onViewCategories = { navController.navigate(Screen.Categories.route) },
                    onViewDrive = { navController.navigate(Screen.Drive.route) }
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
                    onLogout = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
