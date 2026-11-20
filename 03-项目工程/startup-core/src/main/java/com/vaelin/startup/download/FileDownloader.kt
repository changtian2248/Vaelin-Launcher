/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** 一个待下载文件。`expectedSha1` / `expectedSize` 缺失时退化为弱校验。 */
data class DownloadRequest(
    val url: String,
    val target: File,
    val expectedSha1: String? = null,
    val expectedSize: Long = -1L,
    /**
     * 允许从「已存在的半成品」续传。
     *
     * 默认 false：下载失败/进程被杀留下的文件不能被自动相信是完整前缀，
     * 先删掉重下更省事，也避免把两份数据首尾拼在一起。
     */
    val resume: Boolean = false,
)

sealed class DownloadResult {

    data class Success(
        val file: File,
        /** 本次真正从网络收到的字节数；命中本地缓存时为 0 */
        val bytes: Long,
        val skipped: Boolean,
    ) : DownloadResult()

    data class Failure(
        val reason: String,
        val cause: Throwable? = null,
    ) : DownloadResult()
}

/**
 * 单文件下载器：断点续传（HTTP Range）+ sha1 校验 + 失败重试。
 *
 * 重试次数按「首次失败后再试 N 次」算，默认 N=2；每次都从当前已落盘的字节数
 * 续传，所以一次被中途掐断的下载不会白费。
 */
class FileDownloader(
    private val transport: HttpTransport,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {

    fun download(request: DownloadRequest): DownloadResult {
        val target = request.target

        if (target.isFile) {
            if (isComplete(target, request)) {
                return DownloadResult.Success(target, 0L, skipped = true)
            }
            if (!request.resume) {
                target.delete()
            }
        }

        var lastFailure: DownloadResult.Failure = DownloadResult.Failure("未发起请求")
        for (attempt in 0..maxRetries) {
            // 上一轮可能是网络中断，留下一段可靠前缀，下一轮从断点继续；
            // 若是校验失败，transfer 已经把文件删了，这里自然回到 0
            val startOffset = if (target.isFile) target.length() else 0L
            lastFailure = try {
                when (val result = transfer(request, startOffset)) {
                    is DownloadResult.Success -> return result
                    is DownloadResult.Failure -> result
                }
            } catch (e: Exception) {
                DownloadResult.Failure(e.message ?: e::class.java.simpleName, e)
            }
        }
        return DownloadResult.Failure("${lastFailure.reason}（已重试 $maxRetries 次）", lastFailure.cause)
    }

    private fun transfer(request: DownloadRequest, startOffset: Long): DownloadResult {
        val target = request.target
        val response = transport.open(request.url, "GET", if (startOffset > 0) startOffset else -1L)

        response.use { resp ->
            if (resp.code == HTTP_RANGE_NOT_SATISFIABLE) {
                // 本地分片比服务端文件还长，说明它是坏的：删掉，交给下一轮重下
                target.delete()
                return DownloadResult.Failure("HTTP 416，服务端拒绝该 Range，已丢弃本地分片")
            }
            if (!resp.isSuccess) {
                return DownloadResult.Failure("HTTP ${resp.code}")
            }
            val body = resp.body ?: return DownloadResult.Failure("响应没有正文")

            // 只有 206 才代表服务端认可续传；返回 200 意味着 Range 被忽略，整份重写
            val append = startOffset > 0 && resp.code == HTTP_PARTIAL_CONTENT
            if (startOffset > 0 && !append) {
                target.delete()
            }
            target.parentFile?.mkdirs()

            var written = 0L
            FileOutputStream(target, append).use { output ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                }
            }

            val totalBytes = if (append) startOffset + written else written

            if (request.expectedSha1.isNullOrBlank()) {
                if (request.expectedSize > 0 && target.length() != request.expectedSize) {
                    val actual = target.length()
                    target.delete()
                    return DownloadResult.Failure("大小校验失败：期望 ${request.expectedSize}，实得 $actual")
                }
            } else {
                val actual = sha1Of(target)
                if (!actual.equals(request.expectedSha1, ignoreCase = true)) {
                    target.delete()
                    return DownloadResult.Failure("sha1 校验失败：期望 ${request.expectedSha1}，实得 $actual")
                }
            }

            return DownloadResult.Success(target, totalBytes, skipped = false)
        }
    }

    /** 本地文件是否已经是完整份。没有校验信息时只能比大小，比不过就当作不完整。 */
    private fun isComplete(file: File, request: DownloadRequest): Boolean = when {
        !file.isFile || file.length() <= 0L -> false
        !request.expectedSha1.isNullOrBlank() ->
            sha1Of(file).equals(request.expectedSha1, ignoreCase = true)
        request.expectedSize > 0L -> file.length() == request.expectedSize
        // 既无 sha1 也无 size 时无法判定完整性。之前返回 true（本地存在即信），
        // 会让残缺文件静默通过，违反「禁止弱校验静默成功」；改为显式报错。
        else -> throw IllegalArgumentException(
            "无法判定完整性：DownloadRequest 既无 expectedSha1 也无 expectedSize" +
                "（target=${request.target.absolutePath}, url=${request.url}）"
        )
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}

/** 整文件 sha1，小写十六进制。 */
fun sha1Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        value.toString(16).padStart(2, '0')
    }
}
