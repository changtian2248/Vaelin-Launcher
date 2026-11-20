/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现（不含 ZalithLauncher2 代码），语义对照其运行期合并行为：
 * - game/version/installed/VersionInfoParser.kt:81-145  父子 manifest 合并与 libraries/arguments 拼接
 * - VersionInfoParser.kt:147-176（mergeField/mergeManifest） 子非空字段覆盖父
 * - game/download/game/GameJsonMerger.kt:132-137        合并产物去掉 inheritsFrom、id 写目录名
 */

package com.vaelin.startup.download

import com.vaelin.startup.launch.LaunchException

/**
 * 版本 JSON 的 `inheritsFrom` 运行期合并。
 *
 * Forge/Fabric 等安装产物是一份「子 JSON」：没有（或改写了）mainClass，带
 * `inheritsFrom` 指向 vanilla 父版本。启动前必须把继承链合成一份自包含的 JSON，
 * 再交给 [VersionJson.parse] 与 GameArgumentExpander / ClasspathBuilder——这两者
 * 只认 JSON 文本与解析结果，不感知继承链的存在，因此本类输入输出都是文本。
 *
 * 合并语义（逐条对照见文件头注释）：
 * - 子 JSON 的非空字段覆盖父（id/mainClass/assetIndex/assets/type/minecraftArguments/jar…）；
 *   子缺 mainClass 时用父的。空字符串不算非空（ZL2 mergeField 同款）。
 * - libraries = 父 + 子拼接，按 `groupId:artifactId:classifier` 三元组去重，
 *   同名坐标保留**子**的条目（子的版本优先；ZL2 的 GameJsonMerger 是保留更高版本，
 *   这里按运行期合并约定取子，去重后子版本不会被父的旧版本在 classpath 上遮蔽）。
 * - arguments.game：父的在前、子的追加；同名 `--flag` 跳过该 flag 及其紧随的值
 *   （ZL2 :98-133 同款），`--tweakClass` 开头的完整串豁免去重（Forge/OptiFine 双
 *   tweaker 都要保留）；带 rules 的对象条目按结构相等去重。
 * - arguments.jvm：父的在前、子的直接追加（不去重；`-cp`/classpath token 由
 *   GameArgumentExpander.buildJvmArgs 统一丢弃，重复无害）。
 * - `jar` 字段按通用字段规则：子有则用子（决定 client jar 文件名），无则继承父。
 * - 继承链递归解析：父 JSON 自己也可以带 inheritsFrom；超过 [MAX_INHERIT_DEPTH]
 *   层判定为环形继承，显式抛 [LaunchException]。
 * - 合并产物不含 `inheritsFrom`（自包含，ZL2 GameJsonMerger 同款），其余未知字段
 *   一并保留（父的字段全量保留，子的非空字段覆盖/追加）。
 */
object VersionJsonMerger {

    /** 继承链深度保护：正常产品链最多两三层，超过即认定环形继承。 */
    const val MAX_INHERIT_DEPTH = 10

    /**
     * 解析 [childText] 并沿 `inheritsFrom` 递归合并全部父 JSON，返回合并后的 JSON 文本。
     *
     * [resolveParent] 按 inheritsFrom 名字返回父 JSON 文本。父文件缺失/不可读/解析失败
     * 由调用方抛带文件路径的 [LaunchException]（本类不猜路径，原样上抛），禁止静默跳过继承。
     */
    fun merge(childText: String, resolveParent: (inheritsFrom: String) -> String): String {
        val root = Json.parse(childText)
        val child = root as? JsonValue.Obj
            ?: throw LaunchException("version json 顶层必须是 JSON 对象，无法合并继承链")
        return JsonValueWriter.write(mergeInherited(child, depth = 0, resolveParent))
    }

    /** 先自底向上合并完父链，再让子覆盖父。 */
    private fun mergeInherited(
        child: JsonValue.Obj,
        depth: Int,
        resolveParent: (String) -> String,
    ): JsonValue.Obj {
        val inherits = child.stringField("inheritsFrom")?.takeIf { it.isNotBlank() }
            ?: return child
        if (depth >= MAX_INHERIT_DEPTH) {
            throw LaunchException(
                "版本继承链超过 $MAX_INHERIT_DEPTH 层（当前解析 inheritsFrom='$inherits'），疑似环形继承，拒绝合并",
            )
        }
        val parentText = resolveParent(inherits)
        val parentRoot = Json.parse(parentText)
        val parentObj = parentRoot as? JsonValue.Obj
            ?: throw LaunchException("父版本 JSON（inheritsFrom='$inherits'）顶层不是 JSON 对象，拒绝合并")
        val mergedParent = mergeInherited(parentObj, depth + 1, resolveParent)
        return mergePair(mergedParent, child)
    }

    /** 一对父子合并：以父的全部字段为底座，子的非空字段覆盖，libraries/arguments 特殊拼接。 */
    private fun mergePair(parent: JsonValue.Obj, child: JsonValue.Obj): JsonValue.Obj {
        val merged = LinkedHashMap<String, JsonValue>(parent.fields)
        for ((key, value) in child.fields) {
            when (key) {
                // 合并产物自包含：不再携带继承引用（ZL2 GameJsonMerger.kt:132 同款）
                "inheritsFrom" -> Unit

                "libraries" -> {
                    val parentLibraries = arrayOrNull(parent, "libraries").orEmpty()
                    val childLibraries = arrayOrNull(child, "libraries").orEmpty()
                    val mergedLibraries = mergeLibraries(parentLibraries, childLibraries)
                    if (mergedLibraries.isEmpty()) {
                        merged.remove(key)
                    } else {
                        merged[key] = JsonValue.Arr(mergedLibraries)
                    }
                }

                "arguments" -> {
                    if (value is JsonValue.Null) continue
                    val mergedArguments = mergeArguments(parent.field("arguments"), value)
                    if (mergedArguments != null) merged[key] = mergedArguments
                }

                else -> if (overrides(value)) merged[key] = value
            }
        }
        return JsonValue.Obj(merged)
    }

    /**
     * 子字段能否覆盖父字段：非 null，且字符串非空（ZL2 VersionInfoParser.mergeField 同款：
     * 加载器偶尔写 `"type": ""` 这类空值，不能把父版本的有效值冲掉）。
     */
    private fun overrides(value: JsonValue): Boolean = when (value) {
        is JsonValue.Null -> false
        is JsonValue.Str -> value.value.isNotEmpty()
        else -> true
    }

    /**
     * libraries 合并：父在前、子在后（ZL2 VersionInfoParser:93-95 的拼接顺序），
     * 去重键 `groupId:artifactId:classifier`，同名坐标保留子的条目（子的版本优先）。
     * 去重后的条目沿用父条目的原位置（LinkedHashMap 覆盖键值不改变顺序），
     * 无同名坐标的相对顺序不变。
     */
    private fun mergeLibraries(parentLibraries: List<JsonValue>, childLibraries: List<JsonValue>): List<JsonValue> {
        val merged = LinkedHashMap<String, JsonValue>()
        for ((index, library) in parentLibraries.withIndex()) {
            merged.putIfAbsent(libraryKey(library, "parent-blank:$index"), library)
        }
        val fromChild = HashSet<String>()
        for ((index, library) in childLibraries.withIndex()) {
            val key = libraryKey(library, "child-blank:$index")
            // 子列表内部重复保留首个；同名坐标覆盖父的条目
            if (!fromChild.add(key)) continue
            merged[key] = library
        }
        return merged.values.toList()
    }

    /**
     * 去重键：`groupId:artifactId:classifier`（ZL2 GameJsonMerger.deduplicateLibraries 的三元组，
     * 方向相反：同名取子而非取高版本）。坐标解析不了的条目按原文判等；连 name 都没有的
     * 条目无法判等，用 [blankFallback]（带来源与位置的命名空间键）各自保留，不做静默丢弃。
     */
    private fun libraryKey(library: JsonValue, blankFallback: String): String {
        val name = library.stringField("name").orEmpty()
        if (name.isBlank()) return blankFallback
        val coordinate = MavenCoordinate.parse(name) ?: return "name:$name"
        return "${coordinate.groupId}:${coordinate.artifactId}:${coordinate.classifier.orEmpty()}"
    }

    /**
     * arguments 合并：game 按 token 去重拼接（父在前），jvm 直接父子拼接（子追加在后，
     * 不去重——JVM 段带 rules 的条目结构复杂，保守拼接，重复 token 无害）。
     */
    private fun mergeArguments(parent: JsonValue?, child: JsonValue): JsonValue? {
        val parentObj = when (parent) {
            null, is JsonValue.Null -> return child
            is JsonValue.Obj -> parent
            else -> throw LaunchException("version json 的 'arguments' 字段不是对象，拒绝合并继承链")
        }
        val childObj = child as? JsonValue.Obj
            ?: throw LaunchException("version json 的 'arguments' 字段不是对象，拒绝合并继承链")

        val merged = LinkedHashMap<String, JsonValue>(parentObj.fields)
        val parentGame = arrayOrNull(parentObj, "game")
        val childGame = arrayOrNull(childObj, "game")
        if (parentGame != null || childGame != null) {
            merged["game"] = JsonValue.Arr(mergeGameArguments(parentGame.orEmpty(), childGame.orEmpty()))
        }
        val parentJvm = arrayOrNull(parentObj, "jvm")
        val childJvm = arrayOrNull(childObj, "jvm")
        if (parentJvm != null || childJvm != null) {
            merged["jvm"] = JsonValue.Arr(parentJvm.orEmpty() + childJvm.orEmpty())
        }
        // arguments 下其余键（非标准字段，向前兼容）：子的非空值覆盖
        for ((key, value) in childObj.fields) {
            if (key != "game" && key != "jvm" && overrides(value)) merged[key] = value
        }
        return JsonValue.Obj(merged)
    }

    /**
     * game 参数拼接（ZL2 VersionInfoParser:98-133 同款 + tweakClass 豁免）：
     * - 父参数原序全保留；
     * - 子参数里与已有参数同名的 `--flag` 连同紧随其后的值一起跳过（父的值胜）；
     * - `--tweakClass` 开头的完整串（flag + tweaker 类名）不去重，保证多 tweaker 共存；
     * - 带 rules 的对象条目按结构相等去重，其余原样追加。
     */
    private fun mergeGameArguments(parentArguments: List<JsonValue>, childArguments: List<JsonValue>): List<JsonValue> {
        val total = ArrayList(parentArguments)
        var index = 0
        while (index < childArguments.size) {
            val argument = childArguments[index]
            val token = (argument as? JsonValue.Str)?.value
            if (token != null && token.startsWith("--tweakClass")) {
                // tweakClass 完整串不去重：Forge 与 OptiFine 的 tweaker 必须都保留
                total.add(argument)
                val next = childArguments.getOrNull(index + 1) as? JsonValue.Str
                if (next != null && !next.value.startsWith("--")) {
                    total.add(next)
                    index += 2
                } else {
                    index += 1
                }
                continue
            }
            if (token != null && token.startsWith("--") && total.contains(argument)) {
                // 与父（或已合并的子参数）同名：跳过该 flag 及其值，父的值胜（ZL2 同款）
                val next = childArguments.getOrNull(index + 1) as? JsonValue.Str
                index += if (next != null && !next.value.startsWith("--")) 2 else 1
                continue
            }
            if (!total.contains(argument)) total.add(argument)
            index += 1
        }
        return total
    }

    /** 字段存在且非 null 时必须是数组，否则显式报错；缺失/null 返回 null。 */
    private fun arrayOrNull(owner: JsonValue?, name: String): List<JsonValue>? {
        val value = owner.field(name) ?: return null
        if (value is JsonValue.Null) return null
        return value.asArray()
            ?: throw LaunchException("version json 的 '$name' 字段不是数组，拒绝合并继承链")
    }
}

/**
 * [JsonValue] → JSON 文本的最小序列化器（合并产物的输出端）。
 *
 * 数字保留解析时的原始文本，字符串按 RFC 8259 转义（引号、反斜杠、控制字符），
 * 非 ASCII 原样输出（写入时统一 UTF-8）。对象键序保持解析/合并时的插入序。
 */
internal object JsonValueWriter {

    fun write(value: JsonValue): String {
        val out = StringBuilder()
        writeValue(value, out)
        return out.toString()
    }

    private fun writeValue(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> out.append(value.raw)
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    writeValue(item, out)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                var index = 0
                for ((key, item) in value.fields) {
                    if (index++ > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    writeValue(item, out)
                }
                out.append('}')
            }
        }
    }

    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        for (ch in text) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\b' -> out.append("\\b")
                ch == '\u000C' -> out.append("\\f")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch < ' ' -> out.append("\\u").append(String.format("%04x", ch.code))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }
}
