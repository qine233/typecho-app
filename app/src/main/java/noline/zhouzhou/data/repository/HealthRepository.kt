package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.HealthOverview
import noline.zhouzhou.data.model.RecordInput
import noline.zhouzhou.data.remote.NolineApi
import noline.zhouzhou.data.remote.NolineException
import noline.zhouzhou.data.remote.NolineIssue
import noline.zhouzhou.data.remote.ProbeResult
import noline.zhouzhou.data.remote.UrlForm
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

sealed class WriteOutcome {
    /** 已提交到服务器。invalid 是被服务器拒掉的日期。 */
    data class Saved(val invalid: List<String> = emptyList()) : WriteOutcome()

    /** 没网或没密钥，已经存进本地队列，等下次自动补交。 */
    data class Queued(val pending: Int) : WriteOutcome()

    /** 服务器不收（只读模式 / 密钥错 / 接口关了…），队列保留，修好后可重试。 */
    data class Rejected(
        val issue: NolineIssue,
        val message: String,
        val invalid: List<String> = emptyList()
    ) : WriteOutcome()
}

data class PreflightError(val field: String, val message: String, val blocking: Boolean)

/**
 * 服务器对越界值的处理不一致：体重越界整条被拒，体脂越界只悄悄丢字段，备注超长直接截断。
 * 所以这些都得在客户端先说清楚，否则用户会以为都存进去了。
 */
object RecordPreflight {

    // STRICT 才不会被 SMART 把 2 月 30 日悄悄挪到 28 日——服务器用的是 checkdate，会直接拒。
    private val DateOnly = DateTimeFormatter.ofPattern("uuuu-MM-dd")
        .withResolverStyle(ResolverStyle.STRICT)

    fun validate(date: String, weightText: String, fatText: String, note: String): List<PreflightError> {
        val errors = mutableListOf<PreflightError>()
        val day = parseDate(date)
        if (day == null) {
            errors += PreflightError("date", "日期要用 YYYY-MM-DD 这种真实存在的日子", true)
        } else if (day.isAfter(LocalDate.now())) {
            errors += PreflightError("date", "未来的日期服务器不拦，但记上去没意义", true)
        }

        val weight = weightText.trim().toDoubleOrNull()
        if (weight == null) {
            errors += PreflightError("weight", "体重得是个数字", true)
        } else if (weight < 20.0 || weight > 400.0) {
            errors += PreflightError("weight", "体重要在 20 ~ 400 kg 之间，超了整条会被拒", true)
        }

        if (fatText.isNotBlank()) {
            val fat = fatText.trim().toDoubleOrNull()
            if (fat == null) {
                errors += PreflightError("fat", "体脂填数字，或者留空", true)
            } else if (fat < 1.0 || fat > 70.0) {
                errors += PreflightError("fat", "体脂要在 1 ~ 70 之间；越界服务器不报错，只会把这个字段丢掉", false)
            }
        }

        if (note.length > 200) {
            errors += PreflightError("note", "备注超过 200 字，服务器会截断", false)
        }
        return errors
    }

    fun today(): String = LocalDate.now().format(DateOnly)

    fun parseDate(date: String): LocalDate? =
        runCatching { LocalDate.parse(date.trim(), DateOnly) }.getOrNull()
}

class HealthRepository(
    private val api: NolineApi,
    private val settings: SettingsRepository,
    private val store: HealthLocalStore
) {

    private data class Ctx(val siteUrl: String, val key: String, val form: UrlForm)

    private suspend fun ctx(): Ctx = Ctx(
        siteUrl = settings.siteConfig.first().siteUrl.trim(),
        key = settings.nolineApiKey.first(),
        form = settings.nolineUrlForm.first()
    )

    suspend fun cachedOverview() = store.loadOverview()

    suspend fun pendingOps(): List<PendingOp> = store.loadQueue().ops

    suspend fun isConfigured(): Boolean = ctx().key.isNotBlank()

    suspend fun probe(): ProbeResult = api.probe(settings.siteConfig.first().siteUrl)

    /**
     * 设置页的「自检」：探 URL 形态 → 验密钥 → 试探写入是否开放。
     *
     * 写入这一项只能试出来：接口没有任何字段暴露「允许写入」开关，只读时是在写请求上回 403。
     * 这里用一条必然越界的记录（体重 0 kg）去撞 POST——服务器在校验阶段就整条拒掉并回 400，
     * 不会往数据文件里写任何东西，却能分清「能写」和「只读」。
     */
    suspend fun selfCheck(key: String): NolineReport {
        val siteUrl = settings.siteConfig.first().siteUrl.trim()
        if (siteUrl.isBlank()) {
            return NolineReport(false, null, "站点地址是空的，先填好博客地址。", null, false, "", "")
        }
        val probe = api.probe(siteUrl)
        if (probe is ProbeResult.Failed) {
            return NolineReport(false, probe.issue, probe.message, null, false, "", "")
        }
        val ready = probe as ProbeResult.Ready
        settings.setNolineUrlForm(ready.form)
        val base = NolineReport(
            readOk = true,
            issue = null,
            detail = "",
            form = ready.form,
            writeAllowed = false,
            siteName = ready.ping.name,
            plugin = ready.ping.plugin
        )
        if (key.isBlank()) return base.copy(readOk = false, issue = NolineIssue.NoKey)
        try {
            api.health(siteUrl, ready.form, key)
        } catch (e: NolineException) {
            return base.copy(readOk = false, issue = e.issue, detail = e.message)
        }
        // 读到东西了，再试写入；只读模式的提示就归到 issue 里。
        val write = apiWriteIssue(siteUrl, ready.form, key)
        return if (write == null) base.copy(writeAllowed = true)
        else base.copy(issue = write.issue, detail = write.message)
    }

    /** 写入试探的结论：null 表示开放，否则就是要告诉用户的毛病。 */
    private suspend fun apiWriteIssue(siteUrl: String, form: UrlForm, key: String): NolineException? =
        try {
            api.saveRecords(siteUrl, form, key, listOf(RecordInput(ProbeDate, ProbeWeight)))
            null
        } catch (e: NolineException) {
            // 400 = 校验挡下了试探，说明写入通道本来就是开的。
            if (e.issue == NolineIssue.BadInput) null else e
        }

    companion object {
        /** 1970-01-01 是真实存在的日期，日期校验会过，卡在体重范围上。 */
        private const val ProbeDate = "1970-01-01"
        private const val ProbeWeight = 0.0
    }

    suspend fun refresh(flushFirst: Boolean = true): Result<HealthOverview> {
        val c = ctx()
        if (c.siteUrl.isBlank()) {
            return Result.failure(NolineException(0, NolineIssue.NoKey, "还没有填写站点地址"))
        }
        if (flushFirst && store.loadQueue().ops.isNotEmpty()) flushWith(c)
        return try {
            val overview = api.health(c.siteUrl, c.form, c.key)
            store.saveOverview(overview)
            Result.success(overview)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 本地先落队列再尝试提交：写入是「按天整体覆盖」，重放安全。 */
    suspend fun saveRecord(input: RecordInput): WriteOutcome {
        store.enqueue(PendingOp(input.date, input, System.currentTimeMillis()))
        return flushAndSync()
    }

    suspend fun deleteRecord(date: String): WriteOutcome {
        store.enqueue(PendingOp(date, null, System.currentTimeMillis()))
        return flushAndSync()
    }

    /** 手动「立即同步」。 */
    suspend fun flushAndSync(): WriteOutcome {
        val c = ctx()
        val outcome = flushWith(c)
        if (outcome is WriteOutcome.Saved && store.loadQueue().ops.isEmpty()) {
            // 用服务器算好的 bmi/delta/category 覆盖本地视图，身高可能刚在后台被改过。
            refresh(flushFirst = false)
        }
        return outcome
    }

    private suspend fun flushWith(c: Ctx): WriteOutcome {
        val ops = store.loadQueue().ops
        if (ops.isEmpty()) return WriteOutcome.Saved()
        if (c.siteUrl.isBlank() || c.key.isBlank()) return WriteOutcome.Queued(ops.size)

        val writes = ops.mapNotNull { op -> op.record?.let { op.date to it } }
        val deletes = ops.filter { it.record == null }.map { it.date }

        var invalid: List<String> = emptyList()
        var done = mutableSetOf<String>()
        var offline = false
        var rejected: NolineException? = null

        if (writes.isNotEmpty()) {
            try {
                val result = api.saveRecords(c.siteUrl, c.form, c.key, writes.map { it.second })
                invalid = result.invalid
                done += writes.map { it.first }
            } catch (e: NolineException) {
                when {
                    // 400：整批都没通过，日期留着也没用，直接丢回给用户改。
                    e.code == 400 -> {
                        invalid = e.invalidDates.ifEmpty { writes.map { it.first } }
                        done += writes.map { it.first }
                    }
                    e.issue == NolineIssue.Network -> offline = true
                    else -> rejected = e
                }
            }
        }

        if (rejected == null) {
            for (date in deletes) {
                try {
                    api.deleteRecord(c.siteUrl, c.form, c.key, date)
                    done += date
                } catch (e: NolineException) {
                    when {
                        e.code == 404 -> done += date   // 本来就没有，等于删成功
                        e.issue == NolineIssue.Network -> offline = true
                        else -> { rejected = e; break }
                    }
                }
            }
        }

        if (done.isNotEmpty()) store.clearOps(done)
        val remaining = store.loadQueue().ops.size

        rejected?.let {
            return WriteOutcome.Rejected(it.issue, it.message, invalid)
        }
        return if (offline && remaining > 0) WriteOutcome.Queued(remaining)
        else WriteOutcome.Saved(invalid)
    }
}

/** 自检结果：读通没有、采用的形态、写入开没开、以及失败时该说哪一种话。 */
data class NolineReport(
    val readOk: Boolean,
    val issue: NolineIssue?,
    val detail: String,
    val form: UrlForm?,
    val writeAllowed: Boolean,
    val siteName: String,
    val plugin: String
)
