package noline.zhouzhou.ui.screens.btpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import noline.zhouzhou.AppContainer
import noline.zhouzhou.data.model.BtCronBatchOp
import noline.zhouzhou.data.model.BtCronForm
import noline.zhouzhou.data.model.BtCronTask
import noline.zhouzhou.data.model.BtSiteType
import noline.zhouzhou.data.model.CronBackupKinds
import noline.zhouzhou.data.repository.BtCronDetail
import noline.zhouzhou.data.repository.BtCronOptions
import noline.zhouzhou.data.repository.BtCronSettings
import noline.zhouzhou.data.repository.BtFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 表单上那十二格。名字、周期、种类是所有人都有的，其余按种类和周期才露出来。
 *
 * 这一格发回面板时**只发界面摆出来的那些**：改任务必须整行带回（面板会把没发的格子抹平，
 * 整行由仓库层从列表那一行原样取），这里给的只是覆盖在它上面的改动。
 */
data class BtCronSpec(
    val name: String = "",
    val cycle: String = "day",
    val where1: String = "",
    val kind: String = "toShell",
    val hour: String = "",
    val minute: String = "",
    val script: String = "",
    val urlAddress: String = "",
    val target: String = "",
    val keepBackups: String = "",
    val backupTo: String = "",
    val runAs: String = ""
) {
    val isBackupKind: Boolean get() = kind in CronBackupKinds
    val isUrlKind: Boolean get() = kind == "toUrl"
    val isScriptKind: Boolean get() = kind in setOf("toShell", "toPython", "toPhp")

    /**
     * 只覆盖界面摆出来的格子：备份类的三格、URL 的一格、脚本的一格，各按各的种类发。
     * 整行由仓库层从列表那一行带回来，这里发的只是盖在它上面的改动。
     */
    internal fun editOverrides(): Map<String, String> = buildMap {
        put("name", name.trim())
        put("type", cycle)
        put("where1", where1.trim())
        put("week", if (cycle == "week") where1.trim() else "")
        put("sType", kind)
        if (cycleNeedsHour(cycle)) putAll(clockOf("hour", hour))
        if (cycleNeedsMinute(cycle)) putAll(clockOf("minute", minute))
        when {
            isUrlKind -> put("urladdress", urlAddress.trim())
            isScriptKind -> put("sBody", script.trim())
            isBackupKind -> {
                put("sName", target.trim())
                if (keepBackups.isNotBlank()) put("save", keepBackups.trim())
                if (backupTo.isNotBlank()) put("backupTo", backupTo.trim())
            }
        }
        if (runAs.isNotBlank()) put("user", runAs.trim())
    }

    companion object {
        /**
         * 时刻格子要发两遍：面板的写入口只认 `hour`/`minute`，读回来的那一行却叫
         * `where_hour`/`where_minute`（实测发 `where_hour` 建/改，那一格直接落 null）。
         * 改任务时整行会原样带回去，那里躺着旧值，所以两个名字都盖成新值。
         */
        private fun clockOf(key: String, value: String): Map<String, String> {
            val v = value.trim()
            return mapOf(key to v, "where_$key" to v)
        }

        /** 从列表那一行回填：没写过的格子留空，别拿面板的占位符当用户输入。 */
        fun of(task: BtCronTask) = BtCronSpec(
            name = task.name,
            cycle = task.type,
            where1 = task.where1,
            kind = task.sType,
            hour = task.whereHour,
            minute = task.whereMinute,
            script = task.script,
            urlAddress = task.urlAddress,
            target = task.target,
            keepBackups = task.keepBackups?.toString() ?: "",
            backupTo = task.backupTo,
            runAs = task.runAs
        )
    }
}

/** 周期里那个「N」到底是几号、星期几还是每隔几个。 */
fun cycleNeedsCount(cycle: String): Boolean = cycle in setOf("minute-n", "hour-n", "day-n", "week", "month")

fun cycleNeedsHour(cycle: String): Boolean = cycle in setOf("day", "day-n", "week", "month")

fun cycleNeedsMinute(cycle: String): Boolean =
    cycle in setOf("hour", "hour-n", "day", "day-n", "week", "month")

fun cycleCountLabel(cycle: String): String = when (cycle) {
    "week" -> "星期几"
    "month" -> "几号"
    "minute-n" -> "间隔分钟"
    "hour-n" -> "间隔小时"
    "day-n" -> "间隔天数"
    else -> "N"
}

data class BtCronUiState(
    val configured: Boolean = false,
    val busy: Boolean = false,
    val tasks: List<BtCronTask> = emptyList(),
    val enabledCount: Int = 0,
    val pausedCount: Int = 0,
    val unknownCount: Int = 0,
    val keyword: String = "",
    val detailFor: String = "",
    val detail: BtCronDetail? = null,
    val loadingDetail: Boolean = false,
    /** 建/改表单。`editing` 是 null 就是新建；选项没读回来之前表单照样能填，只是少了下拉。 */
    val formFor: BtCronTask? = null,
    val formOpen: Boolean = false,
    val options: BtCronOptions? = null,
    val loadingOptions: Boolean = false,
    val settings: BtCronSettings? = null,
    val failure: BtFailure? = null,
    val note: String = ""
)

/**
 * 计划任务页。
 *
 * 这一页的写法被实测压出三条和别的页不一样的规矩：
 * 1. **写完一律重读列表再回话。** 面板的 `set_cron_status` 是翻转开关、`set_cron_status_all`
 *    逐条报的「设置失败」是假的，所以界面上那句「现在是启用中」来自重读，不来自面板措辞；
 * 2. **批量勾选按 id 认。** 面板那四颗按钮只收编号，读不到编号的行（`id` 空）勾上也是白勾，
 *    批量这一发会把它跳过并说明；
 * 3. **面板级那三块只读不写。** `set_auto_config`、`set_rotate_log_status` 实测会**凭空建出任务**
 *    （发 `status=0` 反而建了一条开着的每日全库备份），所以这一页只把读数摊开，按钮一颗不给。
 */
class BtCronViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.btCronRepository
    private val panel = container.btPanelRepository

    private val _uiState = MutableStateFlow(BtCronUiState())
    val uiState: StateFlow<BtCronUiState> = _uiState.asStateFlow()

    private var writeJob: Job? = null

    init {
        viewModelScope.launch {
            panel.configFlow.collect { config ->
                _uiState.update { it.copy(configured = config.isComplete) }
            }
        }
        refresh()
        loadSettings()
    }

    fun setKeyword(keyword: String) = _uiState.update { it.copy(keyword = keyword) }

    fun search() = load()

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null) }
            val result = repository.tasks(_uiState.value.keyword.trim())
            val page = result.getOrNull()
            _uiState.update {
                it.copy(
                    busy = false,
                    tasks = page?.rows.orEmpty(),
                    enabledCount = page?.enabledCount ?: it.enabledCount,
                    pausedCount = page?.pausedCount ?: it.pausedCount,
                    unknownCount = page?.unknownCount ?: it.unknownCount,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val result = repository.settings()
            _uiState.update {
                it.copy(
                    settings = result.getOrNull() ?: it.settings,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure) ?: it.failure
                )
            }
        }
    }

    private fun write(label: String, reloadsList: Boolean = false, block: suspend () -> Result<String>) {
        if (writeJob?.isActive == true) return
        writeJob = viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failure = null, note = "") }
            val result = block()
            _uiState.update {
                it.copy(
                    busy = false,
                    note = result.getOrNull()?.let { text -> "$label：$text" }.orEmpty(),
                    failure = result.exceptionOrNull()?.let(repository::describeFailure)
                )
            }
            if (reloadsList && result.isSuccess) load()
        }
    }

    // ── 详情 ──

    fun openDetail(task: BtCronTask) {
        _uiState.update { it.copy(detailFor = task.id, detail = null, loadingDetail = true) }
        viewModelScope.launch {
            val result = repository.detail(task.id)
            _uiState.update {
                it.copy(
                    loadingDetail = false,
                    detail = result.getOrNull() ?: it.detail,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure) ?: it.failure
                )
            }
        }
    }

    fun closeDetail() = _uiState.update { it.copy(detailFor = "", detail = null) }

    /** 详情里的「再读一次日志」：清完日志或者刚执行过一次之后用。 */
    fun reloadDetail() {
        val id = _uiState.value.detailFor
        if (id.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingDetail = true) }
            val result = repository.detail(id)
            _uiState.update {
                it.copy(
                    loadingDetail = false,
                    detail = result.getOrNull() ?: it.detail,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure) ?: it.failure
                )
            }
        }
    }

    // ── 表单 ──

    /** 打开建 / 改表单。顺手把六个下拉的来源拉一次，谁没读到界面上单独说。 */
    fun openForm(task: BtCronTask? = null) {
        _uiState.update { it.copy(formFor = task, formOpen = true) }
        if (_uiState.value.options == null) loadOptions()
    }

    /** 分类那一格也要这份名单，所以它单独也能拉。 */
    fun loadOptions() {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingOptions = true) }
            val result = repository.options()
            _uiState.update {
                it.copy(
                    loadingOptions = false,
                    options = result.getOrNull() ?: it.options,
                    failure = result.exceptionOrNull()?.let(repository::describeFailure) ?: it.failure
                )
            }
        }
    }

    fun closeForm() = _uiState.update { it.copy(formOpen = false, formFor = null) }

    fun submitForm(spec: BtCronSpec) {
        val problems = listOfNotNull(
            BtCronForm.nameError(spec.name),
            BtCronForm.where1Error(spec.cycle, spec.where1).takeIf { cycleNeedsCount(spec.cycle) },
            BtCronForm.clockError(spec.hour, "时", 23).takeIf { cycleNeedsHour(spec.cycle) },
            BtCronForm.clockError(spec.minute, "分", 59).takeIf { cycleNeedsMinute(spec.cycle) },
            when {
                spec.isUrlKind -> BtCronForm.urlError(spec.urlAddress)
                spec.isScriptKind -> BtCronForm.scriptError(spec.script)
                else -> null
            },
            BtCronForm.keepError(spec.keepBackups).takeIf { spec.isBackupKind },
            BtCronForm.pathError(spec.target).takeIf { spec.kind == "path" }
        )
        if (problems.isNotEmpty()) {
            _uiState.update {
                it.copy(failure = BtFailure("表单还差一点", problems.joinToString("；"), "这几格面板查得严，填歪了它整条都不收"))
            }
            return
        }
        val editing = _uiState.value.formFor
        closeForm()
        if (editing == null) {
            write("建 ${spec.name.trim()}", reloadsList = true) {
                repository.add(
                    name = spec.name.trim(),
                    cycle = spec.cycle,
                    where1 = spec.where1.trim(),
                    kind = spec.kind,
                    script = spec.script.trim(),
                    target = spec.target.trim(),
                    urlAddress = spec.urlAddress.trim(),
                    hour = spec.hour.trim(),
                    minute = spec.minute.trim(),
                    keepBackups = spec.keepBackups.trim(),
                    backupTo = spec.backupTo.trim(),
                    runAs = spec.runAs.trim()
                )
            }
        } else {
            write("改 ${editing.name}", reloadsList = true) {
                repository.save(editing, spec.editOverrides())
            }
        }
    }

    // ── 单条写 ──

    fun setStatus(task: BtCronTask, enabled: Boolean) =
        write(if (enabled) "启用" else "暂停", reloadsList = true) { repository.setStatus(task, enabled) }

    /** 立即执行。这一发不看启停状态，暂停中的任务也会被跑掉一次。 */
    fun run(task: BtCronTask) = write("执行 ${task.name}", reloadsList = true) { repository.run(task) }

    fun clearLogs(task: BtCronTask) =
        write("清空 ${task.name} 的日志") { repository.clearLogs(task) }.also { reloadDetail() }

    fun delete(task: BtCronTask) =
        write("删除 ${task.name}", reloadsList = true) { repository.delete(task) }

    // ── 批量 ──

    fun batch(op: BtCronBatchOp, tasks: List<BtCronTask>) =
        write("批量${op.label}", reloadsList = true) { repository.batch(op, tasks) }

    /** 归类：分类由界面选好，任务用当前勾选的那批。 */
    fun setCategory(category: BtSiteType, tasks: List<BtCronTask>) =
        write("归到「${category.name}」", reloadsList = true) { repository.setCategory(category, tasks) }

    fun addCategory(name: String) {
        val error = BtCronForm.categoryError(name)
        if (error != null) {
            _uiState.update { it.copy(failure = BtFailure("分类名不行", error, "面板那一格放不下太长，空白它也不收")) }
            return
        }
        write("建分类 $name") { repository.addCategory(name) }
    }

    fun consumeNote() = _uiState.update { it.copy(note = "") }
}
