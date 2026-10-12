package noline.zhouzhou.ui.screens.settings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import noline.zhouzhou.AppContainer
import noline.zhouzhou.BuildConfig
import noline.zhouzhou.R
import noline.zhouzhou.data.remote.NolineIssue
import noline.zhouzhou.data.repository.NolineReport
import noline.zhouzhou.ui.components.NolineGuide
import noline.zhouzhou.ui.theme.NoLineChip
import noline.zhouzhou.ui.theme.noLineFieldColors
import noline.zhouzhou.ui.typechoViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appContainer: AppContainer,
    onLogout: () -> Unit
) {
    val viewModel = typechoViewModel<SettingsViewModel>(appContainer) { SettingsViewModel(it) }
    val uiState by viewModel.uiState.collectAsState()

    // 用户可能刚在系统设置里放行「安装未知应用」，回到这里就把卡住的下载接上
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (uiState.showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showLogoutDialog(false) },
            title = { Text(stringResource(R.string.logout)) },
            text = { Text(stringResource(R.string.logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.logout { viewModel.showLogoutDialog(false); onLogout() }
                }) {
                    Text(stringResource(R.string.action_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showLogoutDialog(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Site info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoStories, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = uiState.blogName.ifBlank { stringResource(R.string.app_name) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = uiState.siteUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        if (uiState.username.isNotBlank()) {
                            Text(
                                text = "登录为: ${uiState.username}" +
                                    if (uiState.isAdmin) " (管理员)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Appearance section
            SettingsSectionHeader(stringResource(R.string.settings_appearance))

            // Dark mode
            SettingsItem(
                icon = Icons.Default.DarkMode,
                title = stringResource(R.string.settings_dark_mode),
                subtitle = when (uiState.darkMode) {
                    "light" -> stringResource(R.string.settings_dark_mode_light)
                    "dark" -> stringResource(R.string.settings_dark_mode_dark)
                    else -> stringResource(R.string.settings_dark_mode_system)
                }
            ) {
                DarkModeSelector(
                    current = uiState.darkMode,
                    onSelect = viewModel::setDarkMode
                )
            }

            // Font scale
            SettingsItem(
                icon = Icons.Default.TextFields,
                title = stringResource(R.string.settings_font_size),
                subtitle = "${(uiState.fontScale * 100).toInt()}%"
            ) {
                Column {
                    Slider(
                        value = uiState.fontScale,
                        onValueChange = viewModel::setFontScale,
                        valueRange = 0.8f..1.4f,
                        steps = 5
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("小", style = MaterialTheme.typography.labelSmall)
                        Text("标准", style = MaterialTheme.typography.labelSmall)
                        Text("大", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Site section
            SettingsSectionHeader(stringResource(R.string.settings_site_info))

            SettingsActionItem(
                icon = Icons.Default.NetworkCheck,
                title = stringResource(R.string.settings_test_connection),
                onClick = viewModel::testConnection
            )

            AnimatedVisibility(visible = uiState.testConnectionResult != null) {
                val ok = uiState.testConnectionResult == true
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (ok) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (ok) stringResource(R.string.settings_connection_ok)
                            else stringResource(R.string.settings_connection_fail),
                            color = if (ok) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // NoLine health API section
            SettingsSectionHeader("健康记录接口")
            NolineSection(uiState, viewModel)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Cloudreve image host
            SettingsSectionHeader("图片上传图床")
            CloudreveSection(uiState, viewModel)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // About section
            SettingsSectionHeader(stringResource(R.string.settings_about))

            SettingsInfoItem(
                icon = Icons.Default.Info,
                title = stringResource(R.string.settings_version),
                value = BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " · debug" else ""
            )

            UpdateCard(state = uiState.update, viewModel = viewModel)

            Spacer(Modifier.height(24.dp))

            // Logout button
            OutlinedButton(
                onClick = { viewModel.showLogoutDialog(true) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.logout))
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) { content() }
            }
        }
    }
}

@Composable
private fun SettingsActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun SettingsInfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(value, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DarkModeSelector(current: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "system" to "跟随系统" to Icons.Default.BrightnessAuto,
            "light" to "浅色" to Icons.Default.LightMode,
            "dark" to "深色" to Icons.Default.DarkMode
        ).forEach { (pair, icon) ->
            val (value, label) = pair
            NoLineChip(
                label = label,
                selected = current == value,
                onClick = { onSelect(value) },
                icon = icon
            )
        }
    }
}

/**
 * NoLine 健康接口：填密钥 + 一次自检。
 * 自检会把三种 URL 形态依次试通并记下采用的形态，再验密钥、探写入开关。
 */
@Composable
private fun NolineSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    var keyInput by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val insecureSite = state.siteUrl.startsWith("http://")

    LaunchedEffect(state.nolineToast) {
        if (state.nolineToast.isNotEmpty()) {
            delay(2600)
            viewModel.consumeNolineToast()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("接口密钥", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.nolineKeyConfigured) "已保存：${state.nolineKeyMasked}"
                        else "还没设置——在博客后台「插件 → NolineApi → 设置」里生成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (state.nolineKeyConfigured) "留空表示沿用已存的密钥" else "粘贴密钥") },
                visualTransformation = if (reveal) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                trailingIcon = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (reveal) "隐藏密钥" else "显示密钥",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )

            if (insecureSite) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "站点走的是 http，密钥会明文过网。后台的写入开关建议只在 HTTPS 下打开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { viewModel.saveAndCheck(keyInput) },
                    enabled = !state.nolineChecking && state.siteUrl.isNotBlank()
                ) {
                    if (state.nolineChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在自检…")
                    } else {
                        Text("保存并自检")
                    }
                }
                if (state.nolineKeyConfigured) {
                    TextButton(onClick = { viewModel.clearNolineKey() }) {
                        Text("清除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            AnimatedVisibility(visible = state.nolineToast.isNotEmpty()) {
                Text(
                    state.nolineToast,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            AnimatedVisibility(visible = state.nolineReport != null) {
                state.nolineReport?.let { NolineReportCard(it, state.nolineFormLabel) }
            }

            if (state.nolineReport == null && !state.nolineChecking) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "四步接通：① 后台开「对外接口」总开关 → ② 生成密钥并填到上面 → " +
                        "③ 后台打开「允许写入」（只想看不记就别开）→ ④ 回到这里自检。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

/**
 * Cloudreve 图床：地址 + 邮箱 + 密码，自检只试一次登录，不往云端写任何东西。
 * 配好之后编辑器插图改走 Cloudreve，正文里贴永久直链。
 */
@Composable
private fun CloudreveSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    var siteInput by remember { mutableStateOf(state.cloudreveSite) }
    var emailInput by remember { mutableStateOf(state.cloudreveEmail) }
    var passwordInput by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    // 清掉配置之后别把旧地址、旧密码留在框里，否则看着像没清成功。
    LaunchedEffect(state.cloudreveConfigured) {
        if (!state.cloudreveConfigured) {
            siteInput = ""
            emailInput = ""
            passwordInput = ""
        }
    }

    LaunchedEffect(state.cloudreveToast) {
        if (state.cloudreveToast.isNotEmpty()) {
            delay(2600)
            viewModel.consumeCloudreveToast()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cloudreve v4", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.cloudreveConfigured) "已保存：${state.cloudrevePasswordMasked}"
                        else "不配也能用，插图会走博客自己的附件上传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = siteInput,
                onValueChange = { siteInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Cloudreve 地址") },
                placeholder = { Text("https://pan.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = emailInput,
                onValueChange = { emailInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("登录邮箱") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = passwordInput,
                onValueChange = { passwordInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (state.cloudreveConfigured) "留空表示沿用已存的密码" else "登录密码") },
                visualTransformation = if (reveal) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (reveal) "隐藏密码" else "显示密码",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                shape = MaterialTheme.shapes.medium,
                colors = noLineFieldColors()
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { viewModel.saveAndCheckCloudreve(siteInput, emailInput, passwordInput) },
                    enabled = !state.cloudreveChecking && siteInput.isNotBlank() && emailInput.isNotBlank()
                ) {
                    if (state.cloudreveChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在登录…")
                    } else {
                        Text("保存并自检")
                    }
                }
                if (state.cloudreveConfigured) {
                    TextButton(onClick = { viewModel.clearCloudreve() }) {
                        Text("清除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            AnimatedVisibility(visible = state.cloudreveToast.isNotEmpty()) {
                Text(
                    state.cloudreveToast,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            AnimatedVisibility(visible = state.cloudreveCheck != null) {
                state.cloudreveCheck?.let { CloudreveCheckCard(it) }
            }

            if (state.cloudreveCheck == null && !state.cloudreveChecking) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "v4 没有「长期 API 密钥」这种东西，只能存邮箱密码、令牌一小时到期就自动重登。" +
                        "密码用系统密钥库加密后保存在本机，站点开了登录验证码或二步验证会登不进来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
private fun CloudreveCheckCard(check: CloudreveCheck) {
    val passed = check is CloudreveCheck.Passed
    val container = if (passed) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.errorContainer
    val onContainer = if (passed) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onErrorContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (passed) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (passed) "已连上 Cloudreve" else "连不上 Cloudreve",
                style = MaterialTheme.typography.titleSmall,
                color = onContainer
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when (check) {
                CloudreveCheck.Passed ->
                    "编辑器里的插图会存到 /blog/年/月/ 目录，正文贴的是游客也打得开的永久直链。"
                is CloudreveCheck.Failed -> check.message
            },
            style = MaterialTheme.typography.bodySmall,
            color = onContainer,
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun NolineReportCard(report: NolineReport, formLabel: String) {
    val ok = report.readOk && report.issue == null
    val container = if (ok) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.errorContainer
    val onContainer = if (ok) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onErrorContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .padding(14.dp)
    ) {
        val titleText = if (ok) "接口已连通" else NolineGuide.title(report.issue)
        val bodyText = if (ok)
            "读得到数据，也能写入。底部「健康」标签可以直接记体重了。"
        else NolineGuide.body(report.issue)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                titleText,
                style = MaterialTheme.typography.titleSmall,
                color = onContainer
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            bodyText,
            style = MaterialTheme.typography.bodySmall,
            color = onContainer,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(10.dp))
        ReportLine("读取", if (report.readOk) "正常" else "不通", report.readOk, onContainer)
        ReportLine(
            "写入",
            when {
                !report.readOk -> "未知"
                report.writeAllowed -> "开放"
                report.issue == NolineIssue.ReadOnly -> "只读（后台没开写入开关）"
                else -> "不通"
            },
            report.writeAllowed,
            onContainer
        )
        report.form?.let {
            ReportLine("接口地址", it.label, true, onContainer)
        } ?: run {
            if (formLabel.isNotBlank()) ReportLine("上次采用", formLabel, true, onContainer)
        }
        if (report.siteName.isNotBlank()) {
            ReportLine(
                "站点",
                "${report.siteName}" + if (report.plugin.isNotBlank()) " · 接口 ${report.plugin}" else "",
                true,
                onContainer
            )
        }
        if (report.detail.isNotBlank() && report.detail != bodyText) {
            Spacer(Modifier.height(6.dp))
            Text(
                (if (report.issue == NolineIssue.Unknown) "解析报错：" else "服务器原话：") + report.detail,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun ReportLine(label: String, value: String, good: Boolean, color: androidx.compose.ui.graphics.Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.7f))
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (good) color else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 检测更新：问 GitHub Releases 要最新版，有更新就下 apk 交给系统安装器。
 * 下载走缓存目录，所以中途取消不会留下占空间的半成品。
 */
@Composable
private fun UpdateCard(state: UpdateState?, viewModel: SettingsViewModel) {
    val subtitle = when (state) {
        null -> "当前 ${BuildConfig.VERSION_NAME} · 从 GitHub Releases 取最新版"
        is UpdateState.Checking -> "正在问 GitHub…"
        is UpdateState.UpToDate -> "这已经是在用的最新版"
        is UpdateState.Available ->
            "发现新版本 ${state.info.version} · ${formatApkSize(state.info.sizeBytes)}"
        is UpdateState.NoMatchingBuild ->
            "有新版本，但没挂本机这个渠道的包（debug 与正式签名不通用）"
        is UpdateState.NeedsPermission -> "系统还没允许本应用安装自己下载的包"
        is UpdateState.Downloading -> "下载中 ${(state.progress * 100).toInt()}%"
        is UpdateState.HandedToInstaller -> "安装界面已拉起，在弹窗里点继续"
        is UpdateState.Failed -> state.message
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("检测更新", style = MaterialTheme.typography.titleSmall)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
                if (state is UpdateState.Checking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            AnimatedVisibility(visible = state is UpdateState.Downloading) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { (state as? UpdateState.Downloading)?.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    is UpdateState.Available -> FilledTonalButton(onClick = { viewModel.downloadUpdate(state.info) }) {
                        Text("下载并安装")
                    }
                    is UpdateState.Downloading -> TextButton(onClick = viewModel::cancelUpdate) {
                        Text("取消")
                    }
                    is UpdateState.NeedsPermission -> {
                        FilledTonalButton(onClick = viewModel::openInstallPermission) { Text("去授权") }
                        TextButton(onClick = { viewModel.downloadUpdate(state.info) }) { Text("我允许了") }
                    }
                    is UpdateState.HandedToInstaller -> TextButton(onClick = viewModel::reinstall) {
                        Text("重新打开安装器")
                    }
                    else -> FilledTonalButton(
                        onClick = viewModel::checkUpdate,
                        enabled = state !is UpdateState.Checking
                    ) {
                        Text(if (state is UpdateState.UpToDate) "再查一次" else "检查更新")
                    }
                }
            }
        }
    }
}

private fun formatApkSize(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    else -> "${bytes / 1024} KB"
}
