package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.BillAggregate
import noline.zhouzhou.data.model.BillInput
import noline.zhouzhou.data.model.BillRecord
import noline.zhouzhou.data.model.BillRecordsPage
import noline.zhouzhou.data.model.BillUpdate
import noline.zhouzhou.data.model.BillsOverview
import noline.zhouzhou.data.remote.NolineApi
import noline.zhouzhou.data.remote.NolineException
import noline.zhouzhou.data.remote.NolineIssue
import noline.zhouzhou.data.remote.ProbeResult
import noline.zhouzhou.data.remote.UrlForm
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID

/**
 * 记账的预检。服务器的规矩和体重不一样：品名和金额越界是**整条拒**，
 * 而分类写错、店铺/备注超长都是**不报错、静默改**（归 other / 截断），
 * 所以后者必须由 App 自己说清楚，否则用户以为存进去的是他写的那个分类。
 */
object BillPreflight {

    const val MaxTitle = 60
    const val MaxShop = 40
    const val MaxNote = 200
    const val MinAmount = 0.01
    const val MaxAmount = 100000.0

    fun validate(title: String, amountText: String, date: String): List<PreflightError> {
        val errors = mutableListOf<PreflightError>()

        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            errors += PreflightError("title", "得写买了什么", true)
        } else if (trimmed.length > MaxTitle) {
            errors += PreflightError("title", "品名超过 $MaxTitle 字，服务器会截断", false)
        }

        val amount = amountText.trim().toDoubleOrNull()
        if (amount == null) {
            errors += PreflightError("amount", "金额得是个数字", true)
        } else if (amount < MinAmount || amount > MaxAmount) {
            errors += PreflightError("amount", "金额要在 $MinAmount ~ 100000 元之间，超了整条会被拒", true)
        } else if (Regex("^\\d+(\\.\\d{3,})?$").matches(amountText.trim())) {
            errors += PreflightError("amount", "超过 2 位小数会被四舍五入", false)
        }

        val day = RecordPreflight.parseDate(date)
        if (day == null) {
            errors += PreflightError("date", "日期要用 YYYY-MM-DD 这种真实存在的日子", true)
        } else if (day.isAfter(LocalDate.now())) {
            errors += PreflightError("date", "未来的日期服务器不拦，但记上去没意义", true)
        }
        return errors
    }

    /** 提交前把输入洗成接口要的形：金额 2 位、文本按上限截断、空可选字段不发。 */
    fun toInput(date: String, title: String, amountText: String, kind: String, shop: String, note: String): BillInput =
        BillInput(
            date = date,
            title = title.trim().take(MaxTitle),
            amount = kotlin.math.round(amountText.trim().toDouble() * 100) / 100,
            kind = if (kind.isBlank()) null else kind,
            shop = shop.trim().take(MaxShop).ifBlank { null },
            note = note.trim().take(MaxNote).ifBlank { null }
        )

    /** 整条覆盖式 PUT：没带的键服务器会沿用原值，但我们的面板本来就是全字段编辑。 */
    fun toUpdate(record: BillRecord, date: String, title: String, amountText: String, kind: String, shop: String, note: String): BillUpdate =
        BillUpdate(
            id = record.id,
            date = date,
            title = title.trim().take(MaxTitle),
            amount = kotlin.math.round(amountText.trim().toDouble() * 100) / 100,
            kind = kind,
            shop = shop.trim().take(MaxShop),
            note = note.trim().take(MaxNote)
        )
}

class BillsRepository(
    private val api: NolineApi,
    private val settings: SettingsRepository,
    private val store: BillsLocalStore
) {

    private data class Ctx(val siteUrl: String, val key: String, val form: UrlForm)

    private suspend fun ctx(): Ctx = Ctx(
        siteUrl = settings.siteConfig.first().siteUrl.trim(),
        key = settings.nolineApiKey.first(),
        form = settings.nolineUrlForm.first()
    )

    suspend fun cachedOverview(): BillsOverview? = store.loadOverview()

    suspend fun pendingOps(): List<PendingBill> = store.loadQueue().ops

    suspend fun titleHints(): List<TitleHint> = store.hints()

    suspend fun isConfigured(): Boolean = ctx().key.isNotBlank()

    suspend fun probe(): ProbeResult = api.probe(settings.siteConfig.first().siteUrl)

    /**
     * 拉概览。先把队列清一清（§7.8：离线攒着，联网后合并成一个批量 POST），
     * 然后用服务器结果覆盖本地缓存——本地只该留下服务器那边还没有的未提交项。
     */
    suspend fun refresh(flushFirst: Boolean = true): Result<BillsOverview> {
        val c = ctx()
        if (c.siteUrl.isBlank()) {
            return Result.failure(NolineException(0, NolineIssue.NoKey, "还没有填写站点地址"))
        }
        if (flushFirst && store.loadQueue().ops.isNotEmpty()) flushWith(c)
        return try {
            val overview = api.bills(c.siteUrl, c.form, c.key)
            store.saveOverview(overview)
            Result.success(overview)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 明细：合计直接用响应里的 amount，所以取整段时要把 limit 放到 0。 */
    suspend fun records(
        limit: Int? = null, from: String = "", to: String = "", kind: String = ""
    ): Result<BillRecordsPage> {
        val c = ctx()
        return try {
            val page = api.billRecords(c.siteUrl, c.form, c.key, limit, from, to, kind)
            store.learn(page.records)
            Result.success(page)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 一次买多样就一次提交：POST 是批量新增，别为三样菜发三个请求。 */
    suspend fun addBills(inputs: List<BillInput>): WriteOutcome {
        val now = System.currentTimeMillis()
        inputs.forEachIndexed { index, input ->
            store.enqueue(
                PendingBill(
                    uid = UUID.randomUUID().toString(),
                    op = BillOp.Add,
                    input = input,
                    queuedAt = now + index
                )
            )
        }
        return flushAndSync()
    }

    /**
     * 改一条。没有服务器 id 的记录（还没上传的新增）在这里被并回那条新增——
     * PUT 需要一个它从来没拿到过的 id，硬发只会被服务器拒掉。
     */
    suspend fun updateBill(serverId: String, update: BillUpdate): WriteOutcome {
        val pending = store.loadQueue().ops
        val asAdd = serverId.isBlank() ||
            pending.any { it.serverId.isBlank() && it.uid == serverId }
        store.enqueue(
            PendingBill(
                uid = if (asAdd) serverId else UUID.randomUUID().toString(),
                serverId = serverId,
                op = if (asAdd) BillOp.Add else BillOp.Update,
                input = BillInput(
                    date = update.date,
                    title = update.title,
                    amount = update.amount,
                    kind = update.kind,
                    shop = update.shop.ifBlank { null },
                    note = update.note.ifBlank { null }
                ),
                queuedAt = System.currentTimeMillis()
            )
        )
        return flushAndSync()
    }

    /**
     * 删一条。@param ref 可以是服务器 id，也可以是本地 uid——
     * 后者意味着那条还没上传过，直接把它从队列里抹掉就行。
     */
    suspend fun deleteBill(ref: String): WriteOutcome {
        val ops = store.loadQueue().ops
        val unsent = ops.firstOrNull { it.uid == ref && it.serverId.isBlank() }
        if (unsent != null) {
            store.dropUnsent(ref)
            return WriteOutcome.Saved()
        }
        store.enqueue(
            PendingBill(
                uid = UUID.randomUUID().toString(),
                serverId = ref,
                op = BillOp.Delete,
                queuedAt = System.currentTimeMillis()
            )
        )
        return flushAndSync()
    }

    /** 手动「立即同步」。 */
    suspend fun flushAndSync(): WriteOutcome {
        val c = ctx()
        val outcome = flushWith(c)
        if (outcome is WriteOutcome.Saved && store.loadQueue().ops.isEmpty()) {
            refresh(flushFirst = false)
        }
        return outcome
    }

    private suspend fun flushWith(c: Ctx): WriteOutcome {
        val ops = store.loadQueue().ops
        if (ops.isEmpty()) return WriteOutcome.Saved()
        if (c.siteUrl.isBlank() || c.key.isBlank()) return WriteOutcome.Queued(ops.size)

        var invalid: List<String> = emptyList()
        val done = mutableSetOf<String>()
        var offline = false
        var rejected: NolineException? = null

        // ── 1. 新增合并成一个批量 POST ──
        val adds = ops.filter { it.op == BillOp.Add && it.input != null }
        if (adds.isNotEmpty()) {
            try {
                val result = api.saveBills(c.siteUrl, c.form, c.key, adds.map { it.input!! })
                invalid = result.invalid
                claimIds(adds, result)
                done += adds.map { it.uid }
            } catch (e: NolineException) {
                when {
                    // 400 = 一条都没通过，全部退回给用户改；这些条目留在队列里也没用。
                    e.code == 400 -> {
                        invalid = e.invalid.ifEmpty { adds.map { it.input?.title.orEmpty() } }
                        done += adds.map { it.uid }
                    }
                    e.issue == NolineIssue.Network -> offline = true
                    else -> rejected = e
                }
            }
        }

        if (rejected == null) {
            // ── 2. 修改：PUT 幂等，同一个 body 重放两次结果一样，随便重试 ──
            for (op in ops.filter { it.op == BillOp.Update && it.serverId.isNotBlank() }) {
                val input = op.input ?: continue
                try {
                    api.updateBill(
                        c.siteUrl, c.form, c.key,
                        BillUpdate(
                            id = op.serverId,
                            date = input.date,
                            title = input.title,
                            amount = input.amount,
                            // 分类不许留空：留空服务器会当成「没传」以外的值，直接归 other。
                            kind = input.kind ?: "other",
                            shop = input.shop.orEmpty(),
                            note = input.note.orEmpty()
                        )
                    )
                    done += op.uid
                } catch (e: NolineException) {
                    when {
                        e.code == 404 -> done += op.uid        // 服务器上已经没这条了
                        e.issue == NolineIssue.Network -> offline = true
                        else -> { rejected = e; break }
                    }
                }
            }
        }

        if (rejected == null) {
            // ── 3. 删除：第二次就是 404，幂等意义上等于删成功 ──
            for (op in ops.filter { it.op == BillOp.Delete && it.serverId.isNotBlank() }) {
                try {
                    api.deleteBill(c.siteUrl, c.form, c.key, op.serverId)
                    done += op.uid
                } catch (e: NolineException) {
                    when {
                        e.code == 404 -> done += op.uid
                        e.issue == NolineIssue.Network -> offline = true
                        else -> { rejected = e; break }
                    }
                }
            }
        }

        if (done.isNotEmpty()) store.clearOps(done)
        val remaining = store.loadQueue().ops.size

        rejected?.let { return WriteOutcome.Rejected(it.issue, it.message, invalid) }
        return if (offline && remaining > 0) WriteOutcome.Queued(remaining)
        else WriteOutcome.Saved(invalid)
    }

    /**
     * 把 `ids[]` 按序认领回本地条目：ids 和**通过校验的**记录同序。
     *
     * 预检已经把会被拒的输入都拦住了，所以正常路径就是 `ids.size == adds.size`；
     * 万一服务器还是退回了几条（`invalid` 给的是品名、截到 20 字），
     * 就按品名顺序跳过被拒的那些再对齐——认不上的那条只能重新当新增发一次。
     */
    private suspend fun claimIds(adds: List<PendingBill>, result: noline.zhouzhou.data.model.BillSaveResult) {
        val rejectedTitles = result.invalid.toMutableList()
        val accepted = mutableListOf<PendingBill>()
        adds.forEach { op ->
            val title = op.input?.title.orEmpty().take(20)
            val index = if (title.isBlank()) rejectedTitles.indexOf("(无品名)") else rejectedTitles.indexOf(title)
            if (index >= 0) rejectedTitles.removeAt(index) else accepted += op
        }
        val mapping = accepted.mapIndexedNotNull { i, op ->
            result.ids.getOrNull(i)?.let { op.uid to it }
        }.toMap()
        if (mapping.isNotEmpty()) store.attachIds(mapping)
    }
}

/** 涨跌由客户端算（服务器不做差）。上期为空时返回 null，UI 画「—」。 */
fun billDelta(current: BillAggregate?, previous: BillAggregate?): Double? {
    if (current == null) return null
    if (previous == null || previous.count == 0) return null
    return current.amount - previous.amount
}
