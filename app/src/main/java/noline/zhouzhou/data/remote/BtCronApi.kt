package noline.zhouzhou.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import noline.zhouzhou.data.model.BtCronBatchOp
import noline.zhouzhou.data.model.btText

/**
 * 计划任务模块（`POST /crontab`）的有类型调用。
 *
 * 每一条都在正式面板 13.1.0 上实测过（建一个 `aa-test-cron` 一次性任务，跑完
 * 建→改名→改周期→暂停→启用→立即执行→清日志→删）。这里记四条只有真发过才知道的规矩：
 *
 * 1. **`modify_crond` 只认你发给它的那些格，没发的直接抹成 null。** 实测只发文档要求的
 *    `name/type/where1/sType/sBody/sName` 六格，改完 `backupTo`、`save`、`urladdress` 全没了，
 *    连 `addtime` 都被写成空格。所以 [modify] 的入参一律由仓库层从整行原样带回来再覆盖，
 *    也就是 [noline.zhouzhou.data.model.BtCronTask.editableBody] 存在的理由；
 * 2. **`name` 这格改不动。** 面板把它自己的表分成两列，改任务时写进去的新名字落在 `rname`，
 *    `name` 永远停在创建那一刻（实测：建 `aa-test-cron`，改名 `aa-test-cron-C`，
 *    回来 `name` 还是老值、`rname` 是新值）。列表和界面显示的是 `rname`；
 * 3. **`set_cron_status` 是开关（toggle），不是「设为某状态」**：`status` 参数发什么都不看，
 *    它就把当前状态翻一面（实测连发两次 `status=0`，任务从暂停翻成启用）。
 *    所以这里只提供 [toggleStatus]，界面上那颗按钮必须按「读到的现在状态」来翻，
 *    不能按「用户想要哪个」来发；
 * 4. **`set_cron_status_all` 反过来是真设置**（幂等：已启用再发 start 还是启用），
 *    可它逐条报的判决永远写着「开启设置失败」「停止设置失败」——状态改了，嘴上说没改。
 *    所以这一条的回包不能当成功判据，[batch] 把逐条原文带回去，仓库层发完必须重读列表。
 * 5. **「立即执行」不走 `set_execute_script`，走 `set_cron_status_all` 的 `exec`。**
 *    前者实测一律回一个裸的 `false`（`id` 之外再补 `name`/`echo`/`crond_id` 都一样），
 *    日志文件里一条都没多，任务停没停都这样；
 * 6. **时刻写进去叫 `hour`/`minute`，读出来叫 `where_hour`/`where_minute`。** 实测建任务时发
 *    `where_hour=5/where_minute=6`，那一格落回 `null`，列表里只剩「每天」；改发 `hour=5/minute=6`
 *    才存成 05:06。`modify_crond` 一样（发 `where_hour` 无效，发 `hour` 有效，两个都发时 `hour` 赢）。
 *    所以改任务时两个名字都盖成新值，见 `BtCronSpec.editOverrides`。后者回 `[{"aa-test-cron":"执行成功","status":true}]`，
 *    而且日志当场从 3 次跳到 4 次——**停用的任务它也照样跑一次**。
 *
 * 一批口子实测之后不能做成按钮，这里连方法都不编，理由跟着 README 那一节走：
 * · `set_auto_config`：**它不读 `status`**。实测对着一台没建过自动备份的面板发
 *   `name=mysql&status=0`，面板直接建了一条「自动备份mysql数据库[所有]」并**启用**
 *   （每天 04:00 备份全部库、保留 3 份）。想关反而被打开，这条我当场删掉还原了；
 * · `set_rotate_log_status`：同样是翻转开关，实测发 `status=0` 把日志切割**打开**了，
 *   并新建一条「[勿删]切割计划任务日志」；再发一次 `status=0` 才关掉，可那条任务留在
 *   列表里成了停用残留。两条我也都删干净了；
 * · `set_rotate_log`：它的前置条件（先开切割）只能用上面那个翻转开关满足，不敢在正式面板上凑；
 * · `set_task_top`：`id`/`list`/`ids`/`crontab_ids`/`top_list`/`id_list` 六种写法全试过，
 *   面板一律回 `{"list":[]}`，行里的 `sort` 纹丝不动——置顶这个口没给 API 开；
 * · `cancel_top`：回「取消置顶成功！」，可这台从来没能置顶过任何任务，一句没有对象的话；
 * · `get_crontab_service` / `repair_crontab_service`：前者实测读回 `status=0`（已停止），
 *   可这台的任务每分钟都在跑，读数不可信；既然读不准，「修复 crond」这种重启服务的写
 *   就没有值得按下去的时候；
 * · `download_logs` / `export_crontab_to_json`：只往服务器 `/tmp` 落文件，手机上取不到，
 *   还会留一份含任务正文的明文；
 * · `check_url_connecte`：面板代访问任意 URL 并把整页 HTML 回传，等于给它一个 SSRF 口子；
 * · `set_atuo_start_syssafe`：字面意思是「临时关掉系统加固」，关掉什么由面板决定，不做成按钮。
 *
 * `set_restart_project` / `get_restart_project_config` 留给 Java 项目那一版（v1.23.0），
 * 它们操作的是项目重启，跟计划任务本身是两件事。
 */
class BtCronApi(private val api: BtPanelApi) {

    private suspend fun get(action: String, params: Map<String, String> = emptyMap()): JsonElement =
        api.call(BtModule.Crontab, action, params).data

    /** 回包把数据放在 `msg` 位（`{status,msg:{…}}` / `{status,msg:[…]}`）的从这里取整棵树。 */
    private suspend fun getWhole(action: String, params: Map<String, String> = emptyMap()): JsonElement =
        api.call(BtModule.Crontab, action, params).root

    private suspend fun put(action: String, params: Map<String, String>): String {
        val reply = api.call(BtModule.Crontab, action, params)
        return reply.msg.ifBlank { "面板已收下这条指令" }
    }

    // ── 只读 ──

    /**
     * 任务列表。裸数组，一行四十来键（详见 [noline.zhouzhou.data.model.BtCronTask]）。
     * 面板这个口没有分页、也没有关键词，过滤只能在仓库层本地做。
     */
    suspend fun tasks(): JsonElement = get("GetCrontab")

    /** 单个任务的完整一行（裸对象）。注意它比列表少 `cycle`/`type_zh`/`user` 这些算出来的键。 */
    suspend fun find(id: String): JsonElement = get("get_crond_find", mapOf("id" to id))

    /**
     * 任务执行日志的正文（在 `msg` 位）。实测日志为空时面板回的是
     * `status=false`「当前日志为空!」，到这一层会抛 [noline.zhouzhou.data.remote.BtException]，
     * 由仓库层翻译成「还没有日志」，别当成调用失败。
     */
    suspend fun log(id: String): String {
        val reply = api.call(BtModule.Crontab, "GetLogs", mapOf("id" to id, "tail" to "off"))
        return reply.msg.ifBlank { btText(reply.data) }
    }

    /** 日志文件在服务器上的路径和大小（`msg` 位是对象；没日志时 `log_path` 写「无」）。 */
    suspend fun logPath(id: String): JsonElement = getWhole("get_log_path", mapOf("id" to id))

    /** 自定义分类（`msg` 位是数组）；实测这台一个都没建，回空数组。 */
    suspend fun categories(): JsonElement = getWhole("get_crontab_types")

    /** 站点域名列表。实测回的是带协议头的完整地址（`http://www.example.com`）。 */
    suspend fun domains(): JsonElement = get("get_domain")

    /** 系统用户列表，用来选「以哪个身份跑」。实测 `["root","www","nobody","mysql","redis"]`。 */
    suspend fun systemUsers(): JsonElement = get("get_system_user_list")

    /** 库 + 每张表的清单（裸数组，每项带 `table_list`），备份数据库任务选表用。 */
    suspend fun databases(): JsonElement = get("GetDatabases")

    /** 库名简化清单（`{status,msg:"ok",data:[…]}`）。 */
    suspend fun simpleDatabases(): JsonElement = get("get_databases")

    /**
     * 按类型取可操作对象，顺带 `orderOpt` —— 名字叫排序选项，实测它是**这台能用起来的备份目标**
     * （这台只列了「微软OneDrive」）。`type` 只认 `sites` / `databases`。
     */
    suspend fun pickList(type: String): JsonElement = get("GetDataList", mapOf("type" to type))

    /** 面板日志自己的切割配置（`msg` 位是对象）。 */
    suspend fun rotateLogConfig(): JsonElement = getWhole("get_rotate_log_config")

    /** 网站/数据库的自动备份任务配置；没建过的时候 `msg` 只有 `{status:0}`。 */
    suspend fun autoBackup(name: String): JsonElement = getWhole("get_auto_config", mapOf("name" to name))

    // ── 写 ──

    /**
     * 建任务。实测最小组合是 `name/type/where1/sType/sBody` 五格就回「添加成功」并给新 `id`；
     * 面板自己补上的其余三十来格（`echo`、`params`、`db_backup_path`…）由它决定。
     * 备份类要带 [target]（库名/站点名/目录）和 [keepBackups]/[backupTo]，
     * `toUrl` 要带 [urlAddress]，时刻发 [hour]/[minute]（读回来的那一行叫 `where_hour`/`where_minute`，
     * 但写入口只认前者——实测发 `where_hour` 建任务，那一格落成 null，界面只能显示「每天」）。
     */
    suspend fun add(
        name: String,
        cycle: String,
        where1: String,
        kind: String,
        script: String,
        target: String = "",
        urlAddress: String = "",
        hour: String = "",
        minute: String = "",
        keepBackups: String = "",
        backupTo: String = "",
        runAs: String = "",
        remark: String = ""
    ): String = put(
        "AddCrontab",
        buildMap {
            put("name", name)
            put("type", cycle)
            put("where1", where1)
            put("sType", kind)
            put("sBody", script)
            put("urladdress", urlAddress)
            put("sName", target)
            put("hour", hour)
            put("minute", minute)
            put("week", if (cycle == "week") where1 else "")
            put("save", keepBackups)
            put("backupTo", backupTo)
            if (runAs.isNotBlank()) put("user", runAs)
            if (remark.isNotBlank()) put("ps", remark)
        }
    )

    /**
     * 改任务。**必须整行发**：见类注释第 1 条，面板会把没发的格子抹平。
     * [fields] 由仓库层从 [find] 或列表那一行原样带回来、再覆盖用户改过的格子。
     * `id` 在这里补，调用方不用管。
     *
     * 还有一处只能靠这个方法名提醒：`name` 这一格发过去落进的是 `rname`，
     * 面板表里那个 `name` 列从建任务之后就不再变了。所以**发回去的 `name` 必须是界面上
     * 显示的那个名字**（[noline.zhouzhou.data.model.BtCronTask.displayName]），
     * 直接照抄原始行的 `name` 会把上一次改过的名字悄悄盖回建任务那一刻。
     */
    suspend fun modify(id: String, fields: Map<String, String>): String =
        put("modify_crond", fields + mapOf("id" to id))

    /** 删除任务。实测面板同时把 `/www/server/cron/<echo>` 脚本和它的日志文件一起删掉。 */
    suspend fun delete(id: String): String = put("DelCrontab", mapOf("id" to id))

    /**
     * 翻开关：启用 ↔ 暂停。**这一条不接受目标状态**（类注释第 3 条），
     * 所以调用方得先读到当前状态再决定按不按，按完以重读到的状态为准。
     */
    suspend fun toggleStatus(id: String): String = put("set_cron_status", mapOf("id" to id))

    /**
     * 批量启停/删除/立即执行。`type` 实测只认 `start`/`stop`/`del`/`exec`，
     * 别的回「参数错误」。
     *
     * 回包是裸数组 `[{"任务名":"停止设置失败","status":false}]`，逐条判决不可信
     * （类注释第 4 条），所以这里把原文拼成一句话带回，真正的成败由仓库层重读列表来定。
     */
    suspend fun batch(op: BtCronBatchOp, ids: List<String>): String {
        val reply = api.call(
            BtModule.Crontab,
            "set_cron_status_all",
            mapOf("type" to op.wire, "id_list" to ids.toIdListBody())
        )
        val verdict = verdicts(reply.data)
        return verdict.ifBlank { reply.msg.ifBlank { "${op.label}指令已发给面板" } }
    }

    /**
     * 立即跑一次。走的是批量口 `set_cron_status_all type=exec`，原因见类注释第 5 条：
     * 文档里那条 `set_execute_script` 实测只会回一个裸的 `false`，日志里一条都不多。
     * 这一条**不看启停状态**，暂停中的任务照样被跑掉一次，界面上要提前说清楚。
     */
    suspend fun run(id: String): String = batch(BtCronBatchOp.Exec, listOf(id))

    /** 清空这个任务的执行日志。实测 `DelLogs` 和 `clear_logs` 都收，这里用文档主名 `DelLogs`。 */
    suspend fun clearLogs(id: String): String = put("DelLogs", mapOf("id" to id))

    /** 新建分类。**面板没有删分类的接口**，建出来的只能去网页端删，界面上要写明白。 */
    suspend fun addCategory(name: String): String = put("add_crontab_type", mapOf("name" to name))

    /**
     * 把一批任务归到某个分类。`id` 是分类编号。
     * 实测**归不回默认分类**：`id=0` 面板直接拒，原话「不能设置为系统分类或者默认分类!」，
     * 所以界面上那个「默认分类」只能显示、不能选。
     */
    suspend fun setCategory(categoryId: String, ids: List<String>): String = put(
        "set_crontab_type",
        mapOf("id" to categoryId, "crontab_ids" to ids.toIdListBody())
    )

    // ── 内部 ──

    /** `[1,2]`；面板要的是 JSON 数组字符串。非纯数字的 id 引起来，免得拼成非法 JSON。 */
    private fun List<String>.toIdListBody(): String = joinToString(",", "[", "]") { id ->
        if (id.isNotEmpty() && id.all { it.isDigit() }) id else "\"${id.jsonEscaped()}\""
    }

    private fun String.jsonEscaped(): String = buildString {
        for (ch in this@jsonEscaped) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }

    /** 把 `[{"任务名":"…","status":false}]` 拼成人话；拼不出来就回空串。 */
    private fun verdicts(root: JsonElement): String {
        val items = root as? JsonArray ?: return ""
        val lines = items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val failed = (obj["status"] as? JsonPrimitive)?.booleanOrNull
            val text = obj.entries
                .filter { (key, _) -> key != "status" }
                .joinToString(" ") { (key, value) ->
                    "${if (key == "id") "" else "$key "}${(value as? JsonPrimitive)?.contentOrNull ?: btText(value)}"
                }
                .trim()
            when {
                text.isBlank() -> null
                failed == false -> "$text（面板原话，状态以重读为准）"
                else -> text
            }
        }
        return lines.joinToString("\n")
    }
}
