package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PortParserTest {

    @Test
    fun `parses url line`() {
        assertEquals(54451, PortParser.parsePort("dsh web: http://127.0.0.1:54451"))
    }

    @Test
    fun `parses url line with lan suffix`() {
        assertEquals(3080, PortParser.parsePort("dsh web: http://127.0.0.1:3080 (LAN: http://192.168.1.5:3080)"))
    }

    @Test
    fun `parses port even with leading log noise`() {
        assertEquals(8080, PortParser.parsePort("2026-01-01 10:00:00 INFO dsh web: http://127.0.0.1:8080"))
    }

    @Test
    fun `returns null for unrelated lines`() {
        assertNull(PortParser.parsePort("node:internal/modules/cjs/loader:123"))
        assertNull(PortParser.parsePort(""))
        assertNull(PortParser.parsePort("http://127.0.0.1:9999 without marker"))
    }

    // ---- parseUrl：0.1.5 起启动 URL 带 ?token= 鉴权参数 ----

    @Test
    fun `parseUrl keeps auth token query`() {
        val line = "dsh web: http://127.0.0.1:61664/?token=paYY-WbMDrAV0LdCB48gmgwKJJR6GY3738lP09dYHhM"
        assertEquals(
            "http://127.0.0.1:61664/?token=paYY-WbMDrAV0LdCB48gmgwKJJR6GY3738lP09dYHhM",
            PortParser.parseUrl(line)
        )
    }

    @Test
    fun `parseUrl returns plain base for legacy lines and stops before lan suffix`() {
        assertEquals("http://127.0.0.1:54451", PortParser.parseUrl("dsh web: http://127.0.0.1:54451"))
        assertEquals(
            "http://127.0.0.1:3080",
            PortParser.parseUrl("dsh web: http://127.0.0.1:3080 (LAN: http://192.168.1.5:3080)")
        )
        assertNull(PortParser.parseUrl("node:internal/modules/cjs/loader:123"))
    }
}
