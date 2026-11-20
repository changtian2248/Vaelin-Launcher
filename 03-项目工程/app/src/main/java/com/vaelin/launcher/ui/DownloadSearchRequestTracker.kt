/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.launcher.ui

/**
 * Tracks which search response is allowed to update the visible list.
 * A reset search supersedes any in-flight request; pagination is ignored while loading.
 */
internal class DownloadSearchRequestTracker {
    internal data class Ticket(val generation: Long)

    private var generation = 0L

    fun begin(reset: Boolean, loading: Boolean): Ticket? {
        if (!reset && loading) return null
        if (reset) generation += 1
        return Ticket(generation)
    }

    fun isCurrent(ticket: Ticket): Boolean = ticket.generation == generation
}
