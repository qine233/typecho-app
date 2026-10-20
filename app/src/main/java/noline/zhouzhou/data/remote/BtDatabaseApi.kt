package noline.zhouzhou.data.remote

import kotlinx.serialization.json.JsonElement
import noline.zhouzhou.data.model.btText

/**
 * 数据库模块（`POST /database`）的有类型调用，外加列表要的 `/data` 那一跳。
 *
 * 这一层同样每一个 action 名和参数名都在正式面板 13.1.0 上实测过（建一个一次性库，
 * 跑完建→备份→加账户→删账户→删库再删干净）。四条实测结论决定了这里的写法：
 * 1. **列表口不在 `/database`**，只有 `/data?action=getData&table=databases` 给得到
 *    `backup_count` 和 `quota`；而且它的 `search.value` 是摆设，带任何关键词都回全表；
 * 2. 文档里画的那个 `view_database_types`（数据库分类）真发出去会回
 *    `'dict_obj' object has no attribute 'db_type'`，所以这里根本没有对应方法；
 * 3. `set_restart_task`（MySQL 守护进程）**完全不读 `status` 参数**：发 `status=0` 不是关掉，
 *    而是当场建一条每 5 分钟的计划任务并启用。所以关掉它只能发 `/crontab DelCrontab`，
 *    这两个方法在界面上一层、下一层，必须配对着用；
 * 4. `DelBackup` 面板永远回一句「备份已删除！」而**一个文件都不删**（拿自己刚建的备份验过，
 *    文件照样在列表里）。这一层就不提供删备份的方法，界面上也就没有那颗按钮。
 *
 * 还有一件事只有代码注释拦得住：`getData table=databases` 每行都带**明文 `password`**。
 * 这里的只读方法回原始 JSON，翻模型的事交给仓库层，但模型里根本没有能装下密码的字段。
 */
class BtDatabaseApi(private val api: BtPanelApi) {

    private suspend fun get(action: String, params: Map<String, String> = emptyMap()): JsonElement =
        api.call(BtModule.Database, action, params).data

    /** 回包没有 `data` 这一层的（平铺对象、`msg` 位装数据）从这里取整棵树。 */
    private suspend fun getWhole(action: String, params: Map<String, String> = emptyMap()): JsonElement =
        api.call(BtModule.Database, action, params).root

    private suspend fun put(action: String, params: Map<String, String>): String {
        val reply = api.call(BtModule.Database, action, params)
        return reply.msg.ifBlank { "面板已收下这条指令" }
    }

    // ── 列表 ──

    /** 数据库列表。关键词由**仓库层**本地过滤，这里的 `search.value` 发过去面板也不认。 */
    suspend fun databases(page: Int, limit: Int): BtReply = api.call(
        BtModule.Data, "getData",
        mapOf(
            "table" to "databases",
            "p" to page.toString(),
            "limit" to limit.toString(),
            "filtered" to "false"
        )
    )

    /** MySQL 原生 `SHOW DATABASES`（二维数组），比面板管理的清单多出一堆系统库。 */
    suspend fun nativeDatabases(sid: String): JsonElement = get(action = "GetDatabaseList", params = mapOf("id" to sid))

    /** 面板 `databases` 表里的库 + 授权范围。 */
    suspend fun panelGrants(sid: String): JsonElement = get("GetDatabasesList", mapOf("sid" to sid))

    /** 某个库的表和大小。只能查面板建的库，系统库（mysql/sys）会回一句错误。 */
    suspend fun info(dbName: String): JsonElement = getWhole("GetInfo", mapOf("db_name" to dbName))

    /** 某个库的表名清单（`SHOW TABLES`），二维数组。 */
    suspend fun tables(sid: String, dbName: String): JsonElement =
        get("get_database_table", mapOf("sid" to sid, "name" to dbName))

    // ── MySQL 账户 ──

    /** 各账户在各主机上的权限详情。 */
    suspend fun mysqlUsers(sid: String): JsonElement = get("GetMysqlUser", mapOf("sid" to sid))

    /** `user@host` 一行一个的清单；action 名就是面板自己少打了一个 t，照它抄。 */
    suspend fun userHosts(sid: String): JsonElement = get("GeUserHostList", mapOf("id" to sid))

    // ── 备份 ──

    /** 各库的备份文件。`GetBackup` 按库扫，`GetAllBackup` 扫整目录。 */
    suspend fun backups(page: Int = 1, limit: Int = 30): JsonElement =
        get("GetBackup", mapOf("p" to page.toString(), "limit" to limit.toString()))

    suspend fun allBackups(page: Int = 1, limit: Int = 30): JsonElement =
        get("GetAllBackup", mapOf("p" to page.toString(), "limit" to limit.toString()))

    // ── 服务器运行状况 ──

    /** `SHOW VARIABLES` 里的内存参数那一组（`{mem:{…}}`）。 */
    suspend fun memoryConfig(): JsonElement = getWhole("GetDbStatus")

    /** `SHOW GLOBAL STATUS` 摊平成一大坨计数器。 */
    suspend fun runStatus(): JsonElement = getWhole("GetRunStatus")

    /** 数据目录和监听端口。 */
    suspend fun serverInfo(): JsonElement = getWhole("GetMySQLInfo")

    /** 本地数据库装没装（一句话，`msg` 位）。 */
    suspend fun installStatus(): String = api.call(BtModule.Database, "get_mysql_status").let {
        it.msg.ifBlank { btText(it.root) }
    }

    /** 超时与密码有效期五格。 */
    suspend fun timeout(): JsonElement = getWhole("GetTimeOut")

    /** 错误日志。实测这台面板直接把整份 `.err` 原文回成一个 JSON 字符串，能到几百 KB。 */
    suspend fun errorLog(): JsonElement = getWhole("GetErrorLog")

    /** 慢查询日志：正文在 `msg` 位。 */
    suspend fun slowLogs(): JsonElement = getWhole("GetSlowLogs")

    /** Binlog 文件清单（这个 action 没进文档索引，实测可用）。 */
    suspend fun binlogs(): JsonElement = get("GetMySQLBinlogs")

    /** 登录失败处理插件（`connection_control`）。没装时 `status` 是字符串 `"off"`。 */
    suspend fun loginFailed(): JsonElement = getWhole("GetLoginFailed")

    /** 数据库服务器清单：本地 0 加已添加的远程实例。 */
    suspend fun servers(): JsonElement = getWhole("GetCloudServer")

    // ── 写 ──

    /**
     * 建库。面板这一步同时建库、建账号、授权，任何一半失败这句 `msg` 就不是「添加成功」。
     * `pid` 是关联站点编号，`"0"` 表示不关联。
     */
    suspend fun addDatabase(
        sid: String,
        name: String,
        user: String,
        password: String,
        address: String,
        charset: String,
        remark: String,
        pid: String = "0"
    ): String = put(
        "AddDatabase",
        mapOf(
            "sid" to sid,
            "name" to name,
            "db_user" to user,
            "password" to password,
            "address" to address,
            "codeing" to charset,
            "ps" to remark,
            "pid" to pid
        )
    )

    /** 删库。实测**不会**一起删掉 `/www/backup/database/mysql/<库名>/` 那个目录。 */
    suspend fun deleteDatabase(id: String, sid: String, name: String, user: String): String = put(
        "DeleteDatabase",
        mapOf("id" to id, "sid" to sid, "name" to name, "db_user" to user)
    )

    /** 立刻备份一次，产物落在 `/www/backup/database/mysql/<库名>/`。 */
    suspend fun backup(id: String, sid: String): String = put("ToBackup", mapOf("id" to id, "sid" to sid))

    /** 建一个纯账户（不绑库）。`address` 和 `host` 面板两个都要，值填一样的。 */
    suspend fun addMysqlUser(
        sid: String,
        username: String,
        password: String,
        address: String
    ): String = put(
        "AddMysqlUser",
        mapOf(
            "sid" to sid,
            "username" to username,
            // 这一格面板只进签名后的表单体，不落日志、不进模型。
            "password" to password,
            "address" to address,
            "db_user" to username,
            "host" to address
        )
    )

    suspend fun deleteMysqlUser(sid: String, username: String, host: String): String = put(
        "DelMysqlUser", mapOf("sid" to sid, "username" to username, "host" to host)
    )

    /**
     * 改超时那五格。**面板会按 `expire_logs_days` 连带重算 `binlog_expire_logs_seconds`**
     * （实测写 6 天就把 600000 秒改成了 518400 秒），所以界面上这一条必须把两格一起说清楚。
     */
    suspend fun setTimeout(
        interactiveSeconds: String,
        waitSeconds: String,
        expireLogDays: String,
        passwordLifetimeDays: String
    ): String = put(
        "SetTimeOut",
        mapOf(
            "interactive_timeout" to interactiveSeconds,
            "wait_timeout" to waitSeconds,
            "expire_logs_days" to expireLogDays,
            "default_password_lifetime" to passwordLifetimeDays
        )
    )

    /** 清理 N 天之前的 binlog。正在写的当前文件面板自己会跳过。 */
    suspend fun clearBinlogs(days: String): String = put("ClearMySQLBinlog", mapOf("days" to days))

    /**
     * 开启 MySQL 守护进程。上面第 3 条实测结论说得很清楚：**这一条是「开启」，不是「设为某状态」**，
     * 已经开着再发一次也只是重建设置。关闭走 [deleteGuardTask]。
     */
    suspend fun enableGuard(): String = put("set_restart_task", emptyMap())

    /**
     * 关闭守护进程 = 删掉它那条计划任务。`id` 从 [guardTask] 回包的 `msg.id` 里取。
     * 这一跳走 `/crontab`，所以不复用上面的 `put`。
     */
    suspend fun deleteGuardTask(id: String): String {
        val reply = api.call(BtModule.Crontab, "DelCrontab", mapOf("id" to id))
        return reply.msg.ifBlank { "守护任务已删除" }
    }

    /** 守护进程任务配置；没配过的时候面板只回 `{status:0}`。 */
    suspend fun guardTask(): JsonElement = getWhole("get_restart_task")
}
