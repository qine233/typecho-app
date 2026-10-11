package com.typecho.app.ui.navigation

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
import com.typecho.app.AppContainer
import com.typecho.app.R
import com.typecho.app.ui.screens.admin.AdminScreen
import com.typecho.app.ui.screens.admin.CommentsScreen
import com.typecho.app.ui.screens.diary.DiaryScreen
import com.typecho.app.ui.screens.editor.EditorScreen
import com.typecho.app.ui.screens.health.HealthScreen
import com.typecho.app.ui.screens.home.HomeScreen
import com.typecho.app.ui.screens.login.LoginScreen
import com.typecho.app.ui.screens.post.PostDetailScreen
import com.typecho.app.ui.screens.settings.SettingsScreen

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
    data object Editor : Screen("editor?postId={postId}") {
        fun createRoute(postId: String? = null) =
            if (postId != null) "editor?postId=$postId" else "editor"
    }
    data object Comments : Screen("comments")
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

    val isLoggedIn by appContainer.settingsRepository.isLoggedIn.collectAsState(initial = false)
    val isAdmin by appContainer.settingsRepository.isAdmin.collectAsState(initial = false)

    val showBottomBar = currentRoute in listOf(
        Screen.Home.route, Screen.Diary.route, Screen.Health.route,
        Screen.Admin.route, Screen.Settings.route
    ) && isLoggedIn

    // 健康数据是站主本人的，读者不需要这个标签。
    val tabs = remember(isAdmin) {
        if (isAdmin) listOf(HomeTab, DiaryTab, HealthTab, AdminTab, SettingsTab)
        else listOf(HomeTab, DiaryTab, SettingsTab)
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
                    onTabSelected = { tab ->
                        navController.navigate(tab.navRoute) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onCreateClick = { navController.navigate(Screen.Editor.createRoute()) }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (isLoggedIn) Screen.Home.createRoute() else Screen.Login.route,
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
                    onOpenSettings = { navController.navigate(Screen.Settings.route) }
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
                    onViewComments = { navController.navigate(Screen.Comments.route) }
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
                arguments = listOf(navArgument("postId") {
                    type = NavType.StringType
                    defaultValue = ""
                })
            ) { backStackEntry ->
                val postId = backStackEntry.arguments?.getString("postId")?.takeIf { it.isNotBlank() }
                EditorScreen(
                    appContainer = appContainer,
                    postId = postId,
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
