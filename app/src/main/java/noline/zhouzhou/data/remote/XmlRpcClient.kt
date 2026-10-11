package noline.zhouzhou.data.remote

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

sealed class XmlRpcValue {
    data class IntValue(val value: Long) : XmlRpcValue()
    data class DoubleValue(val value: Double) : XmlRpcValue()
    data class StringValue(val value: String) : XmlRpcValue()
    data class BooleanValue(val value: Boolean) : XmlRpcValue()
    data class DateTimeValue(val value: String) : XmlRpcValue()
    data class Base64Value(val value: ByteArray) : XmlRpcValue()
    data class ArrayValue(val value: List<XmlRpcValue>) : XmlRpcValue()
    data class StructValue(val value: Map<String, XmlRpcValue>) : XmlRpcValue()
    data class NilValue(val unit: Unit = Unit) : XmlRpcValue()

    fun asString(): String = when (this) {
        is StringValue -> value
        is IntValue -> value.toString()
        is DoubleValue -> value.toString()
        is BooleanValue -> value.toString()
        is DateTimeValue -> value
        else -> ""
    }

    fun asLong(): Long = when (this) {
        is IntValue -> value
        is StringValue -> value.toLongOrNull() ?: 0L
        is DoubleValue -> value.toLong()
        else -> 0L
    }

    fun asInt(): Int = asLong().toInt()

    fun asBoolean(): Boolean = when (this) {
        is BooleanValue -> value
        is IntValue -> value != 0L
        is StringValue -> value == "1" || value.equals("true", ignoreCase = true)
        else -> false
    }

    fun asArray(): List<XmlRpcValue> = when (this) {
        is ArrayValue -> value
        else -> emptyList()
    }

    fun asStruct(): Map<String, XmlRpcValue> = when (this) {
        is StructValue -> value
        else -> emptyMap()
    }

    fun member(key: String): XmlRpcValue =
        asStruct()[key] ?: NilValue()

    fun element(index: Int): XmlRpcValue {
        val arr = asArray()
        return if (index < arr.size) arr[index] else NilValue()
    }
}

class XmlRpcFault(val code: Int, override val message: String) : Exception(message)

class XmlRpcClient {

    fun buildRequest(methodName: String, params: List<Any?>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<methodCall>\n")
        sb.append("  <methodName>").append(escapeXml(methodName)).append("</methodName>\n")
        if (params.isNotEmpty()) {
            sb.append("  <params>\n")
            for (param in params) {
                sb.append("    <param><value>")
                buildValue(sb, param)
                sb.append("</value></param>\n")
            }
            sb.append("  </params>\n")
        }
        sb.append("</methodCall>")
        return sb.toString()
    }

    private fun buildValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("<nil/>")
            is Int -> sb.append("<int>").append(value).append("</int>")
            is Long -> sb.append("<i8>").append(value).append("</i8>")
            is Double -> sb.append("<double>").append(value).append("</double>")
            is Float -> sb.append("<double>").append(value).append("</double>")
            is Boolean -> sb.append("<boolean>").append(if (value) "1" else "0").append("</boolean>")
            is String -> sb.append("<string>").append(escapeXml(value)).append("</string>")
            is Map<*, *> -> {
                sb.append("<struct>")
                for ((k, v) in value) {
                    sb.append("<member><name>").append(escapeXml(k.toString())).append("</name><value>")
                    buildValue(sb, v)
                    sb.append("</value></member>")
                }
                sb.append("</struct>")
            }
            is List<*> -> {
                sb.append("<array><data>")
                for (item in value) {
                    sb.append("<value>")
                    buildValue(sb, item)
                    sb.append("</value>")
                }
                sb.append("</data></array>")
            }
            is XmlRpcValue -> buildFromXmlRpcValue(sb, value)
            else -> sb.append("<string>").append(escapeXml(value.toString())).append("</string>")
        }
    }

    private fun buildFromXmlRpcValue(sb: StringBuilder, value: XmlRpcValue) {
        when (value) {
            is XmlRpcValue.IntValue -> sb.append("<int>").append(value.value).append("</int>")
            is XmlRpcValue.DoubleValue -> sb.append("<double>").append(value.value).append("</double>")
            is XmlRpcValue.StringValue -> sb.append("<string>").append(escapeXml(value.value)).append("</string>")
            is XmlRpcValue.BooleanValue -> sb.append("<boolean>").append(if (value.value) "1" else "0").append("</boolean>")
            is XmlRpcValue.DateTimeValue -> sb.append("<dateTime.iso8601>").append(value.value).append("</dateTime.iso8601>")
            is XmlRpcValue.NilValue -> sb.append("<nil/>")
            is XmlRpcValue.Base64Value -> sb.append("<base64>").append(android.util.Base64.encodeToString(value.value, android.util.Base64.NO_WRAP)).append("</base64>")
            is XmlRpcValue.ArrayValue -> {
                sb.append("<array><data>")
                for (item in value.value) {
                    sb.append("<value>")
                    buildFromXmlRpcValue(sb, item)
                    sb.append("</value>")
                }
                sb.append("</data></array>")
            }
            is XmlRpcValue.StructValue -> {
                sb.append("<struct>")
                for ((k, v) in value.value) {
                    sb.append("<member><name>").append(escapeXml(k)).append("</name><value>")
                    buildFromXmlRpcValue(sb, v)
                    sb.append("</value></member>")
                }
                sb.append("</struct>")
            }
        }
    }

    fun parseResponse(responseBody: String): XmlRpcValue {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(sanitizeResponse(responseBody)))

        var eventType = parser.eventType
        var inFault = false

        try {
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "fault" -> inFault = true
                        "params" -> {
                            if (!inFault) {
                                skipToNextTag(parser, "param")
                                skipToNextTag(parser, "value")
                                return parseValue(parser)
                            }
                        }
                        "value" -> {
                            if (inFault) {
                                val faultStruct = parseValue(parser)
                                val faultCode = faultStruct.member("faultCode").asInt()
                                val faultString = faultStruct.member("faultString").asString()
                                throw XmlRpcFault(faultCode, faultString)
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: XmlRpcFault) {
            throw e
        } catch (e: XmlPullParserException) {
            // Truncated response: some Typecho servers on PHP 8.4 emit deprecation
            // warnings into the output buffer, which shifts Content-Length so the XML
            // tail (closing tags) is cut off. The nested parsers already salvage what
            // they can; reaching here means even the top level was unusable.
        }

        return XmlRpcValue.NilValue()
    }

    private fun sanitizeResponse(body: String): String {
        var text = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val xmlStart = text.indexOf("<?xml")
        if (xmlStart > 0) {
            text = text.substring(xmlStart)
        } else {
            val mrStart = text.indexOf("<methodResponse")
            if (mrStart > 0) {
                text = text.substring(mrStart)
            }
        }
        return text
    }

    private fun skipToNextTag(parser: XmlPullParser, targetName: String) {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == targetName) return
            eventType = parser.next()
        }
    }

    private fun parseValue(parser: XmlPullParser): XmlRpcValue {
        try {
            var eventType = parser.next()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    return when (parser.name) {
                        "int", "i4", "i8" -> parseSimple(parser) { XmlRpcValue.IntValue(it.toLongOrNull() ?: 0L) }
                        "double" -> parseSimple(parser) { XmlRpcValue.DoubleValue(it.toDoubleOrNull() ?: 0.0) }
                        "string" -> parseSimple(parser) { XmlRpcValue.StringValue(it) }
                        "boolean" -> parseSimple(parser) { XmlRpcValue.BooleanValue(it == "1") }
                        "dateTime.iso8601" -> parseSimple(parser) { XmlRpcValue.DateTimeValue(it) }
                        "base64" -> parseSimple(parser) {
                            XmlRpcValue.Base64Value(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
                        }
                        "nil" -> XmlRpcValue.NilValue()
                        "array" -> parseArray(parser)
                        "struct" -> parseStruct(parser)
                        else -> {
                            eventType = parser.next()
                            continue
                        }
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        return XmlRpcValue.StringValue(text)
                    }
                } else if (eventType == XmlPullParser.END_TAG && parser.name == "value") {
                    return XmlRpcValue.StringValue("")
                }
                eventType = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // salvage: return whatever we have
        }

        return XmlRpcValue.NilValue()
    }

    private inline fun parseSimple(parser: XmlPullParser, transform: (String) -> XmlRpcValue): XmlRpcValue {
        val sb = StringBuilder()
        try {
            var eventType = parser.next()
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.TEXT) {
                    sb.append(parser.text ?: "")
                } else if (eventType == XmlPullParser.END_TAG) {
                    break
                }
                eventType = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // truncated value: use the text collected so far
        }
        return transform(sb.toString().trim())
    }

    private fun parseArray(parser: XmlPullParser): XmlRpcValue {
        val items = mutableListOf<XmlRpcValue>()
        try {
            var eventType = parser.next()
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "value") {
                    items.add(parseValue(parser))
                } else if (eventType == XmlPullParser.END_TAG && parser.name == "array") {
                    break
                }
                eventType = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // truncated array: return items collected so far
        }
        return XmlRpcValue.ArrayValue(items)
    }

    private fun parseStruct(parser: XmlPullParser): XmlRpcValue {
        val members = mutableMapOf<String, XmlRpcValue>()
        try {
            var eventType = parser.next()
            var pendingName = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "name" -> {
                            val sb = StringBuilder()
                            var t = parser.next()
                            while (t != XmlPullParser.END_DOCUMENT) {
                                if (t == XmlPullParser.TEXT) sb.append(parser.text ?: "")
                                if (t == XmlPullParser.END_TAG && parser.name == "name") break
                                t = parser.next()
                            }
                            pendingName = sb.toString().trim()
                            eventType = t
                        }
                        "value" -> {
                            // Add the member as soon as its value is parsed so a
                            // truncation right after it does not drop the field.
                            members[pendingName] = parseValue(parser)
                        }
                    }
                }
                if (eventType == XmlPullParser.END_TAG && parser.name == "struct") break
                eventType = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // truncated struct: return members collected so far
        }
        return XmlRpcValue.StructValue(members)
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
