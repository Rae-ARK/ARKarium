package com.arkarium.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// robustness.md F18: which taps stay inside the in-app WebView.
class WebViewLinkPolicyTest {
    private val start = "https://author.example.com/about"

    @Test
    fun samePageAndSamePath_stayInside() {
        assertTrue(isSameSite(start, "https://author.example.com/books"))
        assertTrue(isSameSite(start, "http://author.example.com/"))
    }

    @Test
    fun wwwPrefix_isIgnoredOnEitherSide() {
        assertTrue(isSameSite("https://example.com", "https://www.example.com/x"))
        assertTrue(isSameSite("https://www.example.com", "https://example.com/x"))
    }

    @Test
    fun hostCase_isIgnored() {
        assertTrue(isSameSite(start, "https://AUTHOR.example.com/x"))
    }

    @Test
    fun differentHost_goesOut() {
        assertFalse(isSameSite(start, "https://evil.example.org/"))
        assertFalse(isSameSite(start, "https://other.example.com/"))
    }

    @Test
    fun lookAlikeHost_isNotSameSite() {
        assertFalse(isSameSite(start, "https://author.example.com.evil.net/"))
        assertFalse(isSameSite(start, "https://xauthor.example.com/"))
    }

    @Test
    fun nonHttpSchemes_neverStayInside() {
        assertFalse(isSameSite(start, "mailto:me@author.example.com"))
        assertFalse(isSameSite(start, "intent://author.example.com/#Intent;end"))
        assertFalse(isSameSite(start, "javascript:alert(1)"))
        assertFalse(isSameSite(start, "file:///sdcard/x"))
    }

    @Test
    fun malformedTarget_isNotSameSite() {
        assertFalse(isSameSite(start, "ht tp://bad url"))
        assertFalse(isSameSite(start, ""))
    }
}
