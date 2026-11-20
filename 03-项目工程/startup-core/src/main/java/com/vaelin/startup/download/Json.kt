/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现，不含 ZalithLauncher2 的代码。
 *
 * 为什么不用 org.json：startup-core 的单测跑在纯 JVM 上，android.jar 里的 org.json
 * 只是 stub（调用即抛 "not mocked"），用它就没法在本地验证解析逻辑；
 * 引 GSON / kotlinx.serialization 又要为「R1 只读版本清单与 version json」付一整套
 * 依赖或编译器插件。因此这里写一个只覆盖这两类文档用到的语法子集、零依赖的
 * 递归下降解析器，库代码与单测跑的是同一份实现，不存在 JVM/Android 行为差异。
 */

package com.vaelin.startup.download

/** JSON 值。数字保留原始文本，取值时再决定转 long 还是 double。 */
sealed class JsonValue {

    object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    data class Num(val raw: String) : JsonValue()

    data class Str(val value: String) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
}

/** JSON 语法错误。带偏移量，出错时能直接定位到 Mojang 文档里的位置。 */
class JsonParseException(
    message: String,
    val offset: Int
) : Exception("$message (offset=$offset)")

/**
 * 解析与取值工具。
 *
 * 取值一律用 nullable 访问器：Mojang 的文档字段是可选的历史产物（老版本没有
 * `javaVersion`，加载器版本可能没有 `downloads`），解析层不该因为少一个字段就崩，
 * 由调用方决定缺字段时是回退还是报错。
 */
object Json {

    /** 解析整篇文档。只接受单个顶层值，不允许尾随内容。 */
    fun parse(text: String): JsonValue = JsonParser(text).parseDocument()
}

/** 取字段；接收者为 null 或不是对象时返回 null。 */
fun JsonValue?.field(name: String): JsonValue? =
    (this as? JsonValue.Obj)?.fields?.get(name)

fun JsonValue?.stringField(name: String): String? = field(name).asString()

fun JsonValue?.longField(name: String): Long? = field(name).asLong()

fun JsonValue?.intField(name: String): Int? = field(name).asInt()

fun JsonValue?.arrayField(name: String): List<JsonValue>? = field(name).asArray()

fun JsonValue?.objectField(name: String): Map<String, JsonValue>? = field(name).asObject()

fun JsonValue?.asString(): String? = when (this) {
    is JsonValue.Str -> value
    else -> null
}

fun JsonValue?.asLong(): Long? = when (this) {
    is JsonValue.Num -> raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
    // 少数第三方文档把数字写成字符串，按数字读一次能省掉调用方的容错分支
    is JsonValue.Str -> value.toLongOrNull()
    else -> null
}

fun JsonValue?.asInt(): Int? = asLong()?.let { value ->
    if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
}

fun JsonValue?.asBoolean(): Boolean? = when (this) {
    is JsonValue.Bool -> value
    else -> null
}

fun JsonValue?.asArray(): List<JsonValue>? = when (this) {
    is JsonValue.Arr -> items
    else -> null
}

fun JsonValue?.asObject(): Map<String, JsonValue>? = when (this) {
    is JsonValue.Obj -> fields
    else -> null
}

/**
 * 递归下降解析器。
 *
 * 只实现 RFC 8259 里会被版本清单 / version json 用到的部分：对象、数组、字符串
 * （含 `\uXXXX`）、数字、true/false/null。不做注释、单引号、尾逗号这类非标准宽容，
 * 因为宽容只会把「Mojang 换了文档格式」这种问题推迟到更难查的地方。
 */
private class JsonParser(private val text: String) {

    private companion object {
        /** 版本 json 里 `arguments` 嵌套不超过 5 层，200 已经远超正常需要，只用于挡住恶意深嵌套 */
        const val MAX_DEPTH = 200
    }

    private var pos = 0
    private var depth = 0

    fun parseDocument(): JsonValue {
        skipWhitespace()
        if (pos >= text.length) fail("文档为空")
        val value = parseValue()
        skipWhitespace()
        if (pos != text.length) fail("顶层值之后还有多余内容")
        return value
    }

    private fun parseValue(): JsonValue {
        if (pos >= text.length) fail("期待一个值，但已到文档结尾")
        return when (val c = text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> {
                expectLiteral("true")
                JsonValue.Bool(true)
            }
            'f' -> {
                expectLiteral("false")
                JsonValue.Bool(false)
            }
            'n' -> {
                expectLiteral("null")
                JsonValue.Null
            }
            else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("非法字符 '$c'")
        }
    }

    private fun parseObject(): JsonValue {
        enterNested()
        expect('{')
        skipWhitespace()
        val fields = LinkedHashMap<String, JsonValue>()
        if (peek() == '}') {
            pos++
            leaveNested()
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("对象的键必须是字符串")
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            // 重复键后者覆盖前者：与 GSON / org.json 的默认行为一致
            fields[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    leaveNested()
                    return JsonValue.Obj(fields)
                }
                else -> fail("对象里期待 ',' 或 '}'")
            }
        }
    }

    private fun parseArray(): JsonValue {
        enterNested()
        expect('[')
        skipWhitespace()
        val items = ArrayList<JsonValue>()
        if (peek() == ']') {
            pos++
            leaveNested()
            return JsonValue.Arr(items)
        }
        while (true) {
            skipWhitespace()
            items.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    leaveNested()
                    return JsonValue.Arr(items)
                }
                else -> fail("数组里期待 ',' 或 ']'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("字符串没有闭合")
            val c = text[pos++]
            when {
                c == '"' -> return out.toString()

                c == '\\' -> {
                    if (pos >= text.length) fail("转义序列不完整")
                    when (val escape = text[pos++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> out.append(parseUnicodeEscape())
                        else -> fail("未知转义 \\$escape")
                    }
                }

                // 未转义的控制字符在 JSON 里非法，Mojang 的文档不会出现，出现说明数据坏了
                c < ' ' -> fail("字符串里出现未转义的控制字符 U+${c.code.toString(16)}")

                else -> out.append(c)
            }
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (pos + 4 > text.length) fail("\\u 转义不足 4 位")
        val hex = text.substring(pos, pos + 4)
        val code = hex.toIntOrNull(16) ?: fail("非法 \\u 转义: $hex")
        pos += 4
        return code.toChar()
    }

    private fun parseNumber(): JsonValue {
        val start = pos
        if (peek() == '-') pos++
        if (!peekIsDigit()) fail("数字缺少整数部分")
        if (peek() == '0') {
            pos++
        } else {
            while (peekIsDigit()) pos++
        }
        if (peek() == '.') {
            pos++
            if (!peekIsDigit()) fail("小数点后缺少数字")
            while (peekIsDigit()) pos++
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++
            if (peek() == '+' || peek() == '-') pos++
            if (!peekIsDigit()) fail("指数部分缺少数字")
            while (peekIsDigit()) pos++
        }
        return JsonValue.Num(text.substring(start, pos))
    }

    private fun expectLiteral(literal: String) {
        if (!text.startsWith(literal, pos)) fail("期待字面量 $literal")
        pos += literal.length
    }

    private fun expect(c: Char) {
        if (peek() != c) fail("期待 '$c'")
        pos++
    }

    private fun skipWhitespace() {
        while (pos < text.length) {
            when (text[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun peekIsDigit(): Boolean = peek() in '0'..'9'

    private fun enterNested() {
        if (++depth > MAX_DEPTH) fail("嵌套层级超过 $MAX_DEPTH")
    }

    private fun leaveNested() {
        depth--
    }

    private fun fail(message: String): Nothing = throw JsonParseException(message, pos)
}
