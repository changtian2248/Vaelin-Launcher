package com.vaelin.launcher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSearchRequestTrackerTest {

    @Test
    fun resetSearchSupersedesInflightReset() {
        val tracker = DownloadSearchRequestTracker()
        val old = tracker.begin(reset = true, loading = false)!!

        val latest = tracker.begin(reset = true, loading = true)!!

        assertFalse(tracker.isCurrent(old))
        assertTrue(tracker.isCurrent(latest))
    }

    @Test
    fun resetSearchSupersedesInflightPagination() {
        val tracker = DownloadSearchRequestTracker()
        val firstPage = tracker.begin(reset = true, loading = false)!!
        val nextPage = tracker.begin(reset = false, loading = false)!!

        val changedFilter = tracker.begin(reset = true, loading = true)!!

        assertFalse(tracker.isCurrent(firstPage))
        assertFalse(tracker.isCurrent(nextPage))
        assertTrue(tracker.isCurrent(changedFilter))
    }

    @Test
    fun paginationIsIgnoredWhileRequestIsLoading() {
        val tracker = DownloadSearchRequestTracker()
        val reset = tracker.begin(reset = true, loading = false)

        val duplicatePage = tracker.begin(reset = false, loading = true)

        assertNotNull(reset)
        assertNull(duplicatePage)
        assertTrue(tracker.isCurrent(reset!!))
    }

    @Test
    fun paginationRemainsValidUntilNextReset() {
        val tracker = DownloadSearchRequestTracker()
        val firstPage = tracker.begin(reset = true, loading = false)!!
        val nextPage = tracker.begin(reset = false, loading = false)!!

        assertTrue(tracker.isCurrent(firstPage))
        assertTrue(tracker.isCurrent(nextPage))
    }
}
