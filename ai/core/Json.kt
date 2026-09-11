/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai.core

object Json {
    fun parse(text: String): Any? {
        require(text.length <= 64 * 1024 * 1024) { "JSON 超过 64 MiB 限制" }
        return Parser(text).parse()
    }
    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b")
                '\u000C' -> append("\\f"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
        is Boolean -> value.toString()
        is Number -> { require(value.toDouble().isFinite()); value.toString() }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
            require(it.key is String); stringify(it.key) + ":" + stringify(it.value)
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        else -> error("Unsupported JSON value: ${value.javaClass.name}")
    }
    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \r\n\t") i++ }
        fun parse(): Any? { val result = value(0); ws(); require(i == s.length) { "JSON 尾部含额外内容" }; return result }
        fun value(depth: Int): Any? {
            require(depth < 64) { "JSON 嵌套过深" }; ws(); require(i < s.length) { "JSON 被截断" }
            return when (s[i]) {
                '"' -> string()
                '{' -> {
                    i++; ws(); val m = linkedMapOf<String, Any?>()
                    if (eat('}')) m else {
                        while (true) {
                            ws(); val k = string(); ws(); require(eat(':')); require(!m.containsKey(k)) { "JSON 键重复" }
                            m[k] = value(depth + 1); ws(); if (eat('}')) break; require(eat(','))
                        }; m
                    }
                }
                '[' -> {
                    i++; ws(); val a = arrayListOf<Any?>()
                    if (eat(']')) a else {
                        while (true) { a.add(value(depth + 1)); ws(); if (eat(']')) break; require(eat(',')) }; a
                    }
                }
                't' -> literal("true", true); 'f' -> literal("false", false); 'n' -> literal("null", null)
                '-', in '0'..'9' -> number()
                else -> error("JSON 字符无效，位置 $i")
            }
        }
        fun eat(c: Char): Boolean { if (i < s.length && s[i] == c) { i++; return true }; return false }
        fun literal(word: String, result: Any?): Any? { require(s.startsWith(word, i)); i += word.length; return result }
        fun string(): String {
            require(eat('"')); val b = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return b.toString()
                require(c >= ' ') { "JSON 字符串含裸控制字符" }
                if (c != '\\') b.append(c) else {
                    require(i < s.length)
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> b.append(e); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                        'u' -> { require(i + 4 <= s.length); b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("JSON 转义无效")
                    }
                }
            }; error("JSON 字符串被截断")
        }
        fun number(): Number {
            val start = i; eat('-'); require(i < s.length)
            if (!eat('0')) { require(s[i] in '1'..'9'); while (i < s.length && s[i].isDigit()) i++ }
            if (eat('.')) { require(i < s.length && s[i].isDigit()); while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && s[i] in "eE") {
                i++; if (i < s.length && s[i] in "+-") i++
                require(i < s.length && s[i].isDigit()); while (i < s.length && s[i].isDigit()) i++
            }
            val str = s.substring(start, i)
            return str.toLongOrNull() ?: str.toDouble().also { require(it.isFinite()) }
        }
    }
}
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: error("JSON 对象类型不正确")
fun Any?.arr(): List<Any?> = this as? List<Any?> ?: error("JSON 数组类型不正确")
fun Map<String, Any?>.str(key: String, default: String = ""): String =
    if (!containsKey(key)) default else this[key] as? String ?: error("字段 $key 不是文本")
fun Map<String, Any?>.num(key: String, default: Long = 0): Long {
    if (!containsKey(key)) return default
    val n = this[key] as? Number ?: error("字段 $key 不是数值")
    require(n.toDouble() == n.toLong().toDouble()) { "字段 $key 必须为整数" }
    return n.toLong()
}
fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean =
    if (!containsKey(key)) default else this[key] as? Boolean ?: error("字段 $key 不是布尔值")
