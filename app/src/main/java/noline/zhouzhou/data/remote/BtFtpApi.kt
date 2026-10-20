package noline.zhouzhou.data.remote

import kotlinx.serialization.json.JsonElement
import noline.zhouzhou.data.model.btText

/**
 * FTP 模块（`POST /ftp`）的有类型调用。
 *
 * 每一条都在正式面板 13.1.0 上实测过（建一个一次性账户，跑完建→暂停→启用→改密码→限速→删）。
 * 面板在这块给的口子比它在界面上做的事少得多，实测出来的四条规矩：
 * 1. 列表同样只能走 `/data?action=getData&table=ftps`，`/ftp` 侧没有列表口；
 *    `find_ftp` 文档说能按 id 查单行，实测拿真实 id 发过去回的是 `msg: []`，所以这里不用它；
 * 2. **改不了备注、也改不了根目录**。`set_ftpRemark`、`SetFtpRemark`、`ModifyFtpUserPath`、
 *    `SetFtpPath`、`set_path`、`ModifyUser` 六个名字全试过，面板一律回「指定参数无效!」。
 *    面板网页上点两下就能改的事，接口没开门，所以这里连方法都不编；
 * 3. `ModifyFtpUserAccess`（限速 + 容量）回「修改成功」，可**没有任何只读口能把值读回来**：
 *    `ftps` 那一行的 `quota` 不跟着变，也没有 `get_ftp_access` 之类的查询。所以界面上写完
 *    只能照实说「面板收了这条，但这一格读不回来」，不能报「现在是 1MB」；
 * 4. `set_ftp_logs` 的开关也不作数：`exec_name=open` 和 `close` 都回「设置成功」，
 *    再查 `getlog` 状态纹丝不动（这台是 `stop`）。所以这里只把它当**只读**用。
 *
 * `setPort`（换 FTP 服务监听端口）不提供：它是纯写、没有读回的口，
 * 改完连「现在监听哪个端口」都确认不了，拿不到凭据的危险操作不该做成按钮。
 */
class BtFtpApi(private val api: BtPanelApi) {

    private suspend fun put(action: String, params: Map<String, String>): String {
        val reply = api.call(BtModule.Ftp, action, params)
        return reply.msg.ifBlank { "面板已收下这条指令" }
    }

    // ── 只读 ──

    /** FTP 账户列表；关键词同样得由仓库层本地过滤。 */
    suspend fun users(page: Int, limit: Int): BtReply = api.call(
        BtModule.Data, "getData",
        mapOf(
            "table" to "ftps",
            "p" to page.toString(),
            "limit" to limit.toString(),
            "filtered" to "false"
        )
    )

    /** 登录日志。回包是裸的 `{page,data}`，`page` 那串 HTML 里有总条数。 */
    suspend fun loginLogs(username: String, page: Int, limit: Int): BtReply = api.call(
        BtModule.Ftp, "get_login_logs",
        mapOf("user_name" to username, "p" to page.toString(), "limit" to limit.toString())
    )

    /** 操作日志：`data` 位按上传/下载/改名/删除分组。 */
    suspend fun actionLogs(username: String, page: Int, limit: Int): BtReply = api.call(
        BtModule.Ftp, "get_action_logs",
        mapOf("user_name" to username, "p" to page.toString(), "limit" to limit.toString())
    )

    /**
     * 日志记录开关的**当前状态**：`exec_name=getlog` 是查询，回 `stop` 就是没在记。
     * 上面第 4 条说过，切换那一半不生效，所以这里只发 `getlog`。
     */
    suspend fun logsState(): String {
        val reply = api.call(BtModule.Ftp, "set_ftp_logs", mapOf("exec_name" to "getlog"))
        return reply.msg.ifBlank { btText(reply.root) }
    }

    /** 账户绑的分类（`view_ftp_types`）；实测这台一个分类都没建，回的是空数组。 */
    suspend fun types(): JsonElement = api.call(BtModule.Ftp, "view_ftp_types").data

    // ── 写 ──

    /** 建账户。`path` 不存在时面板会**顺手把目录建出来**（实测），界面上要提前说这句。 */
    suspend fun addUser(username: String, password: String, path: String, remark: String): String = put(
        "AddUser",
        mapOf(
            "ftp_username" to username,
            // 密码只进签名后的表单体，不进日志、不进模型、不进任何回显。
            "ftp_password" to password,
            "path" to path,
            "ps" to remark
        )
    )

    /** 暂停/启用。这一格是真开关，`ftps` 行的 `status` 会跟着变（实测）。 */
    suspend fun setStatus(id: String, username: String, enabled: Boolean): String = put(
        "SetStatus",
        mapOf("id" to id, "username" to username, "status" to if (enabled) "1" else "0")
    )

    suspend fun setPassword(id: String, username: String, newPassword: String): String = put(
        "SetUserPassword",
        mapOf("id" to id, "ftp_username" to username, "new_password" to newPassword)
    )

    /**
     * 批量改密码。回包**没有信封**，是一个裸数组
     * `[{"ftp_username":"…","status":true},…]`，一个人一个人报成没成，
     * 所以这里把整棵树交回仓库层去数有几个 true，而不是只看 HTTP 通没通。
     */
    suspend fun batchSetPasswords(entries: List<Triple<String, String, String>>): JsonElement {
        val data = entries.joinToString(",") { (id, username, password) ->
            """{"id":${id.asWireNumber()},"ftp_username":"${username.jsonEscaped()}",""" +
                """"new_password":"${password.jsonEscaped()}"}"""
        }
        return api.call(BtModule.Ftp, "BatchSetUserPassword", mapOf("data" to "[$data]")).data
    }

    /** 面板要的是数字 id；万一带了别的字符就当字符串发，别拼出一个非法 JSON 让面板回一句语法错。 */
    private fun String.asWireNumber(): String = if (all { it.isDigit() } && isNotEmpty()) this else "\"${jsonEscaped()}\""

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

    /** 限速 + 容量。`0KB`/`0MB` 是「不限」。见类注释第 3 条：这一格写完读不回来。 */
    suspend fun setAccess(
        username: String,
        downloadBandwidth: String,
        uploadBandwidth: String,
        maxSize: String
    ): String = put(
        "ModifyFtpUserAccess",
        mapOf(
            "username" to username,
            "download_bandwidth" to downloadBandwidth,
            "upload_bandwidth" to uploadBandwidth,
            "max_size" to maxSize
        )
    )

    suspend fun deleteUser(id: String, username: String): String =
        put("DeleteUser", mapOf("id" to id, "username" to username))
}
