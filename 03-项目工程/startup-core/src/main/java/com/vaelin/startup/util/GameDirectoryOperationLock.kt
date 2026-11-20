/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.util

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.Semaphore

/** Serializes filesystem mutations in-process and across :game / :jvm / main processes. */
object GameDirectoryOperationLock {
    private val processPermit = Semaphore(1, true)

    fun <T> withLock(lockFile: File, action: () -> T): T {
        processPermit.acquire()
        try {
            val parent = lockFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw IllegalStateException("Cannot create game-directory lock parent: $parent")
            }
            RandomAccessFile(lockFile, "rw").use { randomAccess ->
                val fileLock: FileLock = randomAccess.channel.lock()
                try {
                    return action()
                } finally {
                    fileLock.release()
                }
            }
        } finally {
            processPermit.release()
        }
    }
}
