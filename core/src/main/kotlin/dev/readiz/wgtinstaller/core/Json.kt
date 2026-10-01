// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.core

/** Small bounded JSON codec. Android and desktop tests use the same implementation. */
object Json {
    fun parse(text: String): Any? {
        require(text.length <= 4 * 1024 * 1024) { "JSON size limit" }
        return Reader(text).run { val result = value(0); whitespace(); require(pos == text.length) { "Trailing JSON" }; result }
    }
    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?> = parse(text) as? Map<String, Any?> ?: error("Expected JSON object")
    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
        is Boolean, is Int, is Long -> value.toString()
        is Double -> { require(value.isFinite()); value.toString() }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { require(it.key is String); stringify(it.key) + ":" + stringify(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        else -> error("Unsupported JSON type")
    }
    private class Reader(val text: String) {
        var pos = 0
        fun whitespace() { while (pos < text.length && text[pos] in " \r\n\t") pos++ }
        fun value(depth: Int): Any? {
            require(depth < 40) { "JSON depth limit" }; whitespace(); require(pos < text.length)
            return when (text[pos]) {
                '"' -> string()
                '{' -> {
                    pos++; whitespace(); val out = linkedMapOf<String, Any?>()
                    if (peek('}')) { pos++; out } else {
                        while (true) {
                            whitespace(); require(peek('"')); val key = string(); require(!out.containsKey(key)) { "Duplicate JSON key" }
                            whitespace(); require(peek(':')); pos++; out[key] = value(depth + 1); whitespace()
                            if (peek('}')) { pos++; break }; require(peek(',')); pos++
                        }; out
                    }
                }
                '[' -> {
                    pos++; whitespace(); val out = mutableListOf<Any?>()
                    if (peek(']')) { pos++; out } else {
                        while (true) { out.add(value(depth + 1)); whitespace(); if (peek(']')) { pos++; break }; require(peek(',')); pos++ }; out
                    }
                }
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val token = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, pos)
                    require(token != null && token.range.first == pos) { "Invalid JSON number" }; pos += token.value.length
                    token.value.toLongOrNull() ?: token.value.toDouble().also { require(it.isFinite()) }
                }
            }
        }
        fun peek(c: Char) = pos < text.length && text[pos] == c
        fun literal(s: String, result: Any?): Any? { require(text.startsWith(s, pos)); pos += s.length; return result }
        fun string(): String {
            require(peek('"')); pos++; val out = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]; if (c == '"') return out.toString()
                require(c >= ' ') { "Unescaped control character" }
                if (c != '\\') { out.append(c); continue }
                require(pos < text.length)
                when (val escaped = text[pos++]) {
                    '"', '\\', '/' -> out.append(escaped)
                    'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                    'u' -> { require(pos + 4 <= text.length); out.append(text.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                    else -> error("Invalid JSON escape")
                }
            }; error("Unclosed JSON string")
        }
    }
}

fun Map<String, Any?>.string(key: String): String = this[key] as? String ?: error("Missing JSON string: $key")
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objectAt(key: String): Map<String, Any?> = this[key] as? Map<String, Any?> ?: error("Missing JSON object: $key")
