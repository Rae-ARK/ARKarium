package com.arkarium.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The Notice Board index is a bottom-bar tab; its sub-pages (Pigeon Rally, Rae's
// Journal) are drill-downs and must not show the bar - see MainActivity's noticeBoard
// routes and isBottomBarRoute's doc comment.
class NoticeBoardRoutesTest {

    @Test
    fun noticeBoardIndexShowsTheBottomBarAndHighlightsItsTab() {
        assertTrue(isBottomBarRoute("noticeBoard"))
        assertTrue(AppDestination.NOTICE_BOARD.matchesRoute("noticeBoard"))
    }

    @Test
    fun noticeBoardSubPagesHideTheBottomBar() {
        assertFalse(isBottomBarRoute("noticeBoard/pigeonRally"))
        assertFalse(isBottomBarRoute("noticeBoard/journal"))
    }
}
