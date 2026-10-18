package app.hatch.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserscriptHeaderTest {
    @Test
    fun `tampermonkey header with multiple values`() {
        val src = """
            // ==UserScript==
            // @name         YouTube desktop fixer
            // @namespace    https://hatch.app/
            // @version      1.2.3
            // @description  Removes the mobile interstitial
            // @author       Cory
            // @match        https://www.youtube.com/*
            // @match        https://m.youtube.com/*
            // @exclude      https://www.youtube.com/embed/*
            // @include      https://youtu.be/*
            // @require      https://cdn.example.com/lib.js
            // @require      https://cdn.example.com/other.js
            // @grant        GM_addStyle
            // @grant        GM_getValue
            // @run-at       document-start
            // ==/UserScript==
            (function () { 'use strict'; })();
        """.trimIndent()
        val m = assertNotNull(UserscriptHeader.parse(src))
        assertEquals("YouTube desktop fixer", m.name)
        assertEquals("1.2.3", m.version)
        assertEquals("Removes the mobile interstitial", m.description)
        assertEquals(listOf("https://www.youtube.com/*", "https://m.youtube.com/*"), m.matches)
        assertEquals(listOf("https://youtu.be/*"), m.includes)
        assertEquals(listOf("https://www.youtube.com/embed/*"), m.excludes)
        assertEquals(listOf("https://cdn.example.com/lib.js", "https://cdn.example.com/other.js"), m.requires)
        assertEquals(listOf("GM_addStyle", "GM_getValue"), m.grants)
        assertEquals("document-start", m.runAt)
    }

    @Test
    fun `violentmonkey header with CRLF, tabs and leading whitespace`() {
        val src = "  // ==UserScript==\r\n" +
            "\t// @name\tCRLF script\r\n" +
            "  //  @version   0.1\r\n" +
            "//@match\t*://*.example.com/*\r\n" +
            "// @exclude-match https://example.com/admin/*\r\n" +
            "// @grant none\r\n" +
            "// @run-at document-idle\r\n" +
            "  // ==/UserScript==\r\n" +
            "console.log('hi');\r\n"
        val m = assertNotNull(UserscriptHeader.parse(src))
        assertEquals("CRLF script", m.name)
        assertEquals("0.1", m.version)
        assertEquals(listOf("*://*.example.com/*"), m.matches)
        assertEquals(listOf("https://example.com/admin/*"), m.excludes)
        assertEquals(listOf("none"), m.grants)
        assertEquals("document-idle", m.runAt)
        assertNull(m.description)
        assertEquals(emptyList(), m.requires)
    }

    @Test
    fun `localized name falls back and unknown keys are ignored`() {
        val src = """
            // ==UserScript==
            // @name:en  English name
            // @name:de  Deutscher Name
            // @description:en Only localized
            // @icon https://example.com/i.png
            // @noframes
            // @match https://example.com/*
            // ==/UserScript==
        """.trimIndent()
        val m = assertNotNull(UserscriptHeader.parse(src))
        assertEquals("English name", m.name)
        assertEquals("Only localized", m.description)
        assertNull(m.version)
        assertNull(m.runAt)
        assertEquals(listOf("https://example.com/*"), m.matches)
    }

    @Test
    fun `plain name wins over localized and empty values are skipped`() {
        val src = """
            // ==UserScript==
            // @name:de  Deutscher Name
            // @name     Plain
            // @version
            // @grant
            // @grant    GM_log
            // @include  http://*/*
            // ==/UserScript==
        """.trimIndent()
        val m = assertNotNull(UserscriptHeader.parse(src))
        assertEquals("Plain", m.name)
        assertNull(m.version)
        assertEquals(listOf("GM_log"), m.grants)
        assertEquals(listOf("http://*/*"), m.includes)
        assertEquals(emptyList(), m.matches)
    }

    @Test
    fun `minimal header`() {
        val m = assertNotNull(UserscriptHeader.parse("// ==UserScript==\n// @name x\n// ==/UserScript==\nalert(1)"))
        assertEquals("x", m.name)
        assertEquals(emptyList(), m.matches)
    }

    @Test
    fun `no block or unterminated block returns null`() {
        assertNull(UserscriptHeader.parse("console.log('plain script');"))
        assertNull(UserscriptHeader.parse("// ==UserScript==\n// @name x\nconsole.log(1);"))
        assertNull(UserscriptHeader.parse("// @name x\n// ==/UserScript==\n"))
        assertNull(UserscriptHeader.parse(""))
    }
}
