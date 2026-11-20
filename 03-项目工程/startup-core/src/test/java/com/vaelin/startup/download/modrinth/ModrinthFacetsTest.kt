/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download.modrinth

import com.vaelin.startup.download.source.AddonKind
import com.vaelin.startup.download.source.AddonSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModrinthFacetsTest {

    @Test
    fun `empty query yields null facets`() {
        assertNull(ModrinthFacets.toFacetParam(AddonSearchQuery()))
    }

    @Test
    fun `kind only`() {
        val facets = ModrinthFacets.toFacetParam(AddonSearchQuery(kind = AddonKind.MOD))
        assertEquals("""[["project_type:mod"]]""", facets)
    }

    @Test
    fun `shader kind maps to shader project type`() {
        val facets = ModrinthFacets.toFacetParam(AddonSearchQuery(kind = AddonKind.SHADER))
        assertEquals("""[["project_type:shader"]]""", facets)
    }

    @Test
    fun `game version only`() {
        val facets = ModrinthFacets.toFacetParam(AddonSearchQuery(gameVersion = "1.20.1"))
        assertEquals("""[["versions:1.20.1"]]""", facets)
    }

    @Test
    fun `loader only and lowercased`() {
        val facets = ModrinthFacets.toFacetParam(AddonSearchQuery(loader = "Fabric"))
        assertEquals("""[["categories:fabric"]]""", facets)
    }

    @Test
    fun `combined groups are ANDed in kind-version-loader order`() {
        val facets = ModrinthFacets.toFacetParam(
            AddonSearchQuery(kind = AddonKind.RESOURCE_PACK, gameVersion = "1.20.1", loader = null)
        )
        assertEquals("""[["project_type:resourcepack"],["versions:1.20.1"]]""", facets)
    }
}
