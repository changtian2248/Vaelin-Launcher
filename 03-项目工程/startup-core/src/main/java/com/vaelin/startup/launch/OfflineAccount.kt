package com.vaelin.startup.launch

import java.util.UUID

/**
 * 离线账号（无微软认证）身份，用于展开 minecraftArguments / arguments.game 的占位符。
 *
 * 规范：`UUID.nameUUIDFromBytes("OfflinePlayer:<name>".toByteArray(UTF_8))`，
 * 生成的是 version=3（基于名字的 MD5）、variant=2（IETF）的 UUID。
 * 纯 JVM，可独立单测。
 */
object OfflineAccount {
    const val DEFAULT_PLAYER_NAME = "Steve"
    const val ACCESS_TOKEN = "0"
    const val USER_TYPE = "legacy"
    const val UUID_PREFIX = "OfflinePlayer:"
    const val AUTH_SESSION = "0"

    fun uuidForBytes(playerName: String): UUID =
        UUID.nameUUIDFromBytes((UUID_PREFIX + playerName).toByteArray(Charsets.UTF_8))

    fun uuidFor(playerName: String): String = uuidForBytes(playerName).toString()

    /** nameUUIDFromBytes 的特征：version=3、variant=2。 */
    fun isOfflineUuid(uuid: UUID): Boolean = uuid.version() == 3 && uuid.variant() == 2

    fun isOfflineUuid(text: String): Boolean =
        runCatching { isOfflineUuid(UUID.fromString(text)) }.getOrDefault(false)
}
