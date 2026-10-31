package com.v2ray.ang.ui.logcat

import com.v2ray.ang.util.ConsoleEntry
import com.v2ray.ang.util.ConsoleLevel
import com.v2ray.ang.util.parseLogcatLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XrayConsoleTest {
    private fun entry(id: Long, level: ConsoleLevel, tag: String = "GoLog", message: String = "m") =
        ConsoleEntry(id, 1_700_000_000_000L, level, tag, message)

    @Test
    fun `logcat lines are parsed into level, tag and message`() {
        val parsed = parseLogcatLine("10-31 12:00:00.123 E/GoLog (1234): failed to dial")
        assertEquals(ConsoleLevel.ERROR, parsed?.level)
        assertEquals("GoLog", parsed?.tag)
        assertEquals("failed to dial", parsed?.message)
    }

    @Test
    fun `every logcat letter maps to a level`() {
        assertEquals(ConsoleLevel.DEBUG, parseLogcatLine("10-31 12:00:00.123 D/GoLog (1): d")?.level)
        assertEquals(ConsoleLevel.INFO, parseLogcatLine("10-31 12:00:00.123 I/GoLog (1): i")?.level)
        assertEquals(ConsoleLevel.WARNING, parseLogcatLine("10-31 12:00:00.123 W/GoLog (1): w")?.level)
        assertEquals(ConsoleLevel.ERROR, parseLogcatLine("10-31 12:00:00.123 F/GoLog (1): f")?.level)
    }

    @Test
    fun `stack trace lines are not parsed as entries`() {
        assertNull(parseLogcatLine("\tat com.example.Foo.bar(Foo.kt:42)"))
        assertNull(parseLogcatLine(""))
    }

    @Test
    fun `level filters keep only their level`() {
        val entries = ConsoleLevel.entries.mapIndexed { index, level -> entry(index.toLong(), level) }

        assertEquals(
            listOf(ConsoleLevel.DEBUG, ConsoleLevel.INFO),
            filterConsoleEntries(entries, ConsoleFilter.INFO, "").map { it.level },
        )
        assertEquals(
            listOf(ConsoleLevel.WARNING),
            filterConsoleEntries(entries, ConsoleFilter.WARNING, "").map { it.level },
        )
        assertEquals(
            listOf(ConsoleLevel.ERROR),
            filterConsoleEntries(entries, ConsoleFilter.ERROR, "").map { it.level },
        )
        assertEquals(
            listOf(ConsoleLevel.SYSTEM),
            filterConsoleEntries(entries, ConsoleFilter.SYSTEM, "").map { it.level },
        )
        assertEquals(entries, filterConsoleEntries(entries, ConsoleFilter.ALL, ""))
    }

    @Test
    fun `query matches message and tag case insensitively`() {
        val entries = listOf(
            entry(1, ConsoleLevel.ERROR, tag = "GoLog", message = "Failed to dial"),
            entry(2, ConsoleLevel.INFO, tag = "Network", message = "upstream lost"),
        )

        assertEquals(listOf(1L), filterConsoleEntries(entries, ConsoleFilter.ALL, "dial").map { it.id })
        assertEquals(listOf(2L), filterConsoleEntries(entries, ConsoleFilter.ALL, "netW").map { it.id })
        assertTrue(filterConsoleEntries(entries, ConsoleFilter.ALL, "missing").isEmpty())
    }

    @Test
    fun `exported text keeps the order and one line per entry`() {
        val entries = listOf(
            entry(1, ConsoleLevel.ERROR, "GoLog", "boom"),
            entry(2, ConsoleLevel.INFO, "Core", "started"),
        )

        val lines = formatConsoleEntries(entries).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("ERROR    GoLog  boom"))
        assertTrue(lines[1].endsWith("INFO     Core  started"))
    }
}
