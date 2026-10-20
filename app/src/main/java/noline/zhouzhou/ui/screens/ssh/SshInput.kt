package noline.zhouzhou.ui.screens.ssh

/** 方向键四向，`applicationCursorKeys` 决定发哪一串。 */
enum class TermDirection { Up, Down, Right, Left }

/**
 * 键盘、快捷键、粘贴到终端里去的字节。
 *
 * 输入法给的是「文本变了什么」，终端要的是「敲了哪些键」，中间这层翻译单独拎出来，
 * 好让单测把退格、替换、回车转换这些细节钉死。
 */
object SshInput {
    const val Esc = 0x1B
    const val Enter = 0x0D
    const val Tab = 0x09
    const val Backspace = 0x7F

    /** Ctrl + 字母：Ctrl-C 是 0x03，Ctrl-D 是 0x04，走的是 A=1 那张表。 */
    fun ctrl(letter: Char): ByteArray {
        val upper = letter.uppercaseChar()
        return if (upper in 'A'..'Z') byteArrayOf((upper - 'A' + 1).toByte()) else ByteArray(0)
    }

    fun arrow(direction: TermDirection, applicationCursorKeys: Boolean): ByteArray {
        val tail = when (direction) {
            TermDirection.Up -> if (applicationCursorKeys) "OA" else "[A"
            TermDirection.Down -> if (applicationCursorKeys) "OB" else "[B"
            TermDirection.Right -> if (applicationCursorKeys) "OC" else "[C"
            TermDirection.Left -> if (applicationCursorKeys) "OD" else "[D"
        }
        return escSequence(tail)
    }

    /** Home / End / Delete 这类没有字母对应的键，全部走 CSI 或特殊串。 */
    fun functionKey(name: String): ByteArray = when (name) {
        "Esc" -> byteArrayOf(Esc.toByte())
        "Enter" -> byteArrayOf(Enter.toByte())
        "Tab" -> byteArrayOf(Tab.toByte())
        "Backspace" -> byteArrayOf(Backspace.toByte())
        "Delete" -> escSequence("[3~")
        "Home" -> escSequence("[H")
        "End" -> escSequence("[F")
        "PgUp" -> escSequence("[5~")
        "PgDn" -> escSequence("[6~")
        else -> ByteArray(0)
    }

    fun escSequence(tail: String): ByteArray =
        byteArrayOf(Esc.toByte()) + tail.toByteArray(Charsets.US_ASCII)

    /**
     * 粘贴：开了 bracketed paste（vim、nano 会开）要包上 `ESC[200~ … ESC[201~`，
     * 否则多行粘贴会被当成用户亲手敲了一串命令直接跑起来。
     */
    fun paste(text: String, bracketed: Boolean): ByteArray {
        val body = normalize(text)
        if (!bracketed) return body
        return escSequence("[200~") + body + escSequence("[201~")
    }

    /** 屏幕上敲进去的回车统一成 `\r`，`\t` 留着，其余控制字符丢掉。 */
    fun normalize(text: String): ByteArray {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\n' -> out.append('\r')
                ch == '\t' -> out.append(ch)
                ch.code < 0x20 || ch.code == 0x7F -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * 把「输入框从 [before] 变成 [after]」翻成该发给服务器的按键串。
     *
     * 删掉的部分发等量的退格，让远端的 readline 自己去改它那一行；
     * 输入法替换（自动纠错、候选词改选）也是这个走法：先退掉差异、再补上新的。
     */
    fun deltaBytes(before: String, after: String): ByteArray {
        if (before == after) return ByteArray(0)
        var prefix = 0
        while (prefix < before.length && prefix < after.length && before[prefix] == after[prefix]) {
            prefix++
        }
        var suffix = 0
        while (suffix < before.length - prefix && suffix < after.length - prefix &&
            before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
        ) {
            suffix++
        }
        val removed = before.length - prefix - suffix
        val added = after.substring(prefix, after.length - suffix)
        val backspaces = ByteArray(removed.coerceAtLeast(0)) { Backspace.toByte() }
        return backspaces + normalize(added)
    }

    /** 顶栏「退出」按钮发的就是这一串，让服务器自己把 shell 收掉。 */
    fun exitCommand(): ByteArray = normalize("exit\n")
}
