/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.launch

import com.vaelin.startup.download.RuleEnvironment
import com.vaelin.startup.download.VersionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@Suppress("NonAsciiCharacters")
class ClasspathBuilderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val environment = RuleEnvironment.android("arm64-v8a")

    /** 同一坐标重复声明三次，另有 osx 专属库与只有 natives 的库，外加普通库。 */
    private val versionJson = """
        {
          "id": "1.20.1",
          "mainClass": "net.minecraft.client.main.Main",
          "libraries": [
            {"name":"org.lwjgl:lwjgl:3.3.1","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar","sha1":"a","size":1,"url":"https://example.invalid/a.jar"}}},
            {"name":"org.lwjgl:lwjgl:3.3.1","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar","sha1":"a","size":1,"url":"https://example.invalid/a.jar"}}},
            {"name":"org.lwjgl:lwjgl:3.3.1","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar","sha1":"a","size":1,"url":"https://example.invalid/a.jar"}}},
            {"name":"com.example:zeta:2.0","downloads":{"artifact":{"path":"com/example/zeta/2.0/zeta-2.0.jar","sha1":"z","size":2,"url":"https://example.invalid/z.jar"}}},
            {"name":"com.example:alpha:1.0","downloads":{"artifact":{"path":"com/example/alpha/1.0/alpha-1.0.jar","sha1":"b","size":3,"url":"https://example.invalid/b.jar"}}},
            {"name":"com.example:osx:1.0","rules":[{"action":"allow","os":{"name":"osx"}}],"downloads":{"artifact":{"path":"com/example/osx/1.0/osx-1.0.jar","sha1":"c","size":4,"url":"https://example.invalid/c.jar"}}},
            {"name":"com.example:nativeonly:1.0","natives":{"linux":"natives-linux"},"downloads":{"classifiers":{"natives-linux-arm64":{"path":"com/example/nativeonly/1.0/nativeonly-1.0-natives-linux-arm64.jar","sha1":"d","size":5,"url":"https://example.invalid/d.jar"}}}}
          ]
        }
    """.trimIndent()

    @Test
    fun `按 Maven 坐标去重并按坐标排序末尾追加 client jar`() {
        val librariesDir = File(temp.root, "libraries")
        val clientJar = File(temp.root, "versions/1.20.1/1.20.1.jar")
        val result = ClasspathBuilder.buildWithStats(
            VersionJson.parse(versionJson), librariesDir, clientJar, environment,
        )

        assertEquals(2, result.duplicateCount)
        assertEquals(
            listOf(
                "com/example/alpha/1.0/alpha-1.0.jar",
                "com/example/zeta/2.0/zeta-2.0.jar",
                "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar",
            ).map { File(librariesDir, it).absolutePath } + clientJar.absolutePath,
            result.classpath.map { it.absolutePath },
        )
    }

    @Test
    fun `osx 库被规则过滤只有 natives 的库不进 classpath`() {
        val result = ClasspathBuilder.buildWithStats(
            VersionJson.parse(versionJson), File(temp.root, "libraries"), null, environment,
        )

        assertEquals(3, result.classpath.size)
        assertTrue(result.classpath.none { it.name.contains("osx") })
        assertTrue(result.classpath.none { it.name.contains("natives") })
    }

    @Test
    fun `无 artifact 且无坐标时显式报错`() {
        val json = """
            {"id":"x","mainClass":"Main","libraries":[{"name":"not-a-coordinate"}]}
        """.trimIndent()

        val error = runCatching {
            ClasspathBuilder.build(VersionJson.parse(json), File(temp.root, "libraries"), null, environment)
        }.exceptionOrNull()

        assertTrue(error is LaunchException)
        assertTrue(error!!.message!!.contains("无法确定 jar 路径"))
    }
}
