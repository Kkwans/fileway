package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.*
import org.junit.Test

class ShellCommandTest {
    @Test fun outputBufferIsBoundedAndAnsiColorIsOnlyDisplayFormatting() {
        val colored = appendShellOutput(ShellBuffer(), listOf("\u001B[31mowned\u001B[0m"))
        assertEquals(listOf("owned"), colored.lines)
        var value = ShellBuffer()
        repeat(100) { value = appendShellOutput(value, List(32) { "x".repeat(1000) }) }
        assertTrue(value.lines.sumOf { it.length + 1 } <= SHELL_OUTPUT_CHARACTERS)
        assertTrue(value.lines.size <= 2000)
        assertTrue(value.dropped > 0)
    }
    @Test fun oneLineCommandInputNeverRepairsInvalidCharactersOrAllowsUnboundedPayload() {
        assertNull(shellCommandError("echo \"中文 +%\""))
        for (value in listOf("", "echo one\necho two", "x\u0000y", "\uD800", "x".repeat(8193))) assertNotNull(shellCommandError(value))
    }
}
