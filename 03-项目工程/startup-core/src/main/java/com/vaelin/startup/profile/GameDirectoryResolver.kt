/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.profile

import java.io.File
import java.nio.file.Files

/**
 * Resolves the Minecraft working directory from the one launcher-wide ZL2-compatible mode.
 * Core assets, libraries, runtimes and version manifests stay rooted at sharedRoot; only
 * the game's mutable working directory changes to `versions/<profileId>` when isolated.
 */
object GameDirectoryResolver {
    private val safeProfileId = Regex("[A-Za-z0-9._-]+")

    fun resolve(sharedRoot: File, profile: VersionProfile, mode: GameDirectoryMode): File {
        val resolved = resolve(sharedRoot, profile.id, mode)
        if (mode == GameDirectoryMode.ISOLATED) {
            val collidingVersionArtifacts = listOf(
                File(resolved, "${profile.id}.json"),
                File(resolved, "${profile.id}.jar"),
                File(resolved, "natives"),
            )
            require(collidingVersionArtifacts.none { it.exists() }) {
                "Isolated profile id '${profile.id}' collides with a shared Minecraft version directory; rename the profile before enabling isolation"
            }
        }
        return resolved
    }

    fun resolve(sharedRoot: File, profileId: String, mode: GameDirectoryMode): File {
        val shared = sharedRoot.canonicalFile
        if (mode == GameDirectoryMode.SHARED) return shared
        require(profileId.isNotBlank() && profileId != "." && profileId != "..") {
            "Invalid profile id for isolated game directory: '$profileId'"
        }
        require(safeProfileId.matches(profileId)) {
            "Profile id contains unsafe path characters: '$profileId'"
        }
        val versionsPath = File(shared, "versions")
        require(!Files.isSymbolicLink(versionsPath.toPath())) { "Isolated versions root cannot be a symbolic link: $versionsPath" }
        val versionsRoot = versionsPath.canonicalFile
        require(versionsRoot.parentFile == shared) { "Isolated versions root escapes game home: $versionsRoot" }
        val profilePath = File(versionsRoot, profileId)
        require(!Files.isSymbolicLink(profilePath.toPath())) {
            "Isolated profile directory cannot be a symbolic link: $profilePath"
        }
        val resolved = profilePath.canonicalFile
        require(resolved.parentFile == versionsRoot) {
            "Resolved isolated game directory escapes its versions root: $resolved"
        }
        return resolved
    }
}
