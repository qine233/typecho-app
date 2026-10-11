package com.typecho.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject

/**
 * NoLine 健康数据接口（Typecho 插件）的数据结构。
 * 接口事实来源：APP-API-SPEC.md；可空字段一律按「服务器真的会给 null」处理。
 */

@Serializable
data class HealthRecord(
    val date: String = "",
    val weight: Double = 0.0,
    val delta: Double? = null,
    val bmi: Double? = null,
    val category: String = "",
    val fat: Double? = null,
    val note: String = ""
) {
    /** `category` 是稳定英文代码，文案由 App 自己出（服务器不给本地化）。 */
    val categoryLabel: String
        get() = when (category) {
            "thin" -> "偏瘦"
            "normal" -> "正常"
            "over" -> "超重"
            "obese" -> "肥胖"
            else -> ""
        }

    /** 持平要显式说「持平」，不能显示成 +0.0kg。 */
    val deltaLabel: String
        get() {
            val d = delta ?: return "第一条记录"
            return when {
                kotlin.math.abs(d) < 0.05 -> "持平"
                d < 0 -> "↓ ${absKg(d)} kg"
                else -> "↑ ${absKg(d)} kg"
            }
        }
}

/** 写入用的请求体；fat / note 为 null 时整个字段不发送（见 Json 的 explicitNulls=false）。 */
@Serializable
data class RecordInput(
    val date: String,
    val weight: Double,
    val fat: Double? = null,
    val note: String? = null
)

@Serializable
data class IdealRange(val low: Double = 0.0, val high: Double = 0.0)

@Serializable
data class BmiRanges(
    val thin: Double = 18.5,
    val normal: Double = 24.0,
    val obese: Double = 28.0
)

@Serializable
data class HealthProfile(
    val height: Double? = null,
    val gender: String = "male",
    @SerialName("target_weight") val targetWeight: Double? = null,
    val ideal: IdealRange? = null,
    @SerialName("bmi_ranges") val bmiRanges: BmiRanges = BmiRanges()
)

@Serializable
data class WeightStats(val min: Double = 0.0, val max: Double = 0.0, val avg: Double = 0.0)

@Serializable
data class GoalInfo(
    val target: Double = 0.0,
    val remaining: Double = 0.0,
    val done: Boolean = false
) {
    /** remaining 正数=还要减，负数=还要增。 */
    val remainingLabel: String
        get() = when {
            done -> "已达目标"
            remaining > 0 -> "还要减 ${absKg(remaining)} kg"
            else -> "还要增 ${absKg(remaining)} kg"
        }
}

@Serializable
data class ChangeInfo(
    val span: Int = 0,
    @SerialName("from_date") val fromDate: String? = null,
    val delta: Double? = null
)

@Serializable
data class HealthOverview(
    val profile: HealthProfile = HealthProfile(),
    val count: Int = 0,
    val first: String? = null,
    val last: String? = null,
    val latest: HealthRecord? = null,
    val stats: WeightStats? = null,
    val series: List<HealthRecord> = emptyList(),
    val goal: GoalInfo? = null,
    @Serializable(with = ChangesSerializer::class)
    val changes: Map<String, ChangeInfo> = emptyMap()
)

/**
 * 一条记录都没有时，服务器那个 PHP 空关联数组会序列化成 `[]` 而不是 `{}`，
 * 直接按 Map 解会抛「Expected JsonObject, but had JsonArray」。
 */
object ChangesSerializer : KSerializer<Map<String, ChangeInfo>> {

    private val delegate = MapSerializer(String.serializer(), ChangeInfo.serializer())

    override val descriptor: SerialDescriptor =
        SerialDescriptor("com.typecho.app.data.model.ChangesSerializer", delegate.descriptor)

    override fun deserialize(decoder: Decoder): Map<String, ChangeInfo> {
        if (decoder !is JsonDecoder) return delegate.deserialize(decoder)
        return when (val element = decoder.decodeJsonElement()) {
            is JsonObject -> decoder.json.decodeFromJsonElement(delegate, element)
            else -> emptyMap()
        }
    }

    override fun serialize(encoder: Encoder, value: Map<String, ChangeInfo>) =
        delegate.serialize(encoder, value)
}

@Serializable
data class RecordsPage(val total: Int = 0, val records: List<HealthRecord> = emptyList())

@Serializable
data class SaveResult(
    val saved: Int = 0,
    val total: Int = 0,
    val invalid: List<String> = emptyList()
)

@Serializable
data class DeleteResult(val deleted: String = "", val total: Int = 0)

@Serializable
data class PingInfo(
    val name: String = "",
    val url: String = "",
    val theme: String = "",
    val plugin: String = "",
    val time: String = ""
)

@Serializable
data class SiteFacade(
    val title: String = "",
    val description: String = "",
    val url: String = "",
    val theme: String = "",
    val lang: String = "",
    val timezone: Double = 8.0
)

/** 键名是博客历史遗留拼写（gtihubname、logoCss 其实指头像），按原样接，不要改名。 */
@Serializable
data class ThemeSettings(
    val logoCss: String = "",
    val logoName: String = "",
    val logobg: String = "",
    val logobgcolor: String = "",
    val accentColor: String = "",
    val diaryCategory: String = "",
    val navIcons: String = "",
    val notice: String = "",
    @SerialName("gtihubname") val githubName: String = "",
    @SerialName("gtihubsite") val githubSite: String = "",
    @SerialName("BliBliname") val biliName: String = "",
    @SerialName("BliBlisite") val biliSite: String = "",
    val logoFooter: String = ""
)

@Serializable
data class AccentInfo(
    val hex: String = "",
    val rgb: String = "",
    val luminance: Double = 0.0,
    val on: String = "",
    val dark: String = ""
)

@Serializable
data class ThemePayload(
    val site: SiteFacade = SiteFacade(),
    val theme: ThemeSettings = ThemeSettings(),
    val accent: AccentInfo = AccentInfo()
)

/** 涨跌/剩余量统一按 1 位小数显示，正负号由调用方的文案决定。 */
fun absKg(value: Double): String = "%.1f".format(kotlin.math.abs(value))
