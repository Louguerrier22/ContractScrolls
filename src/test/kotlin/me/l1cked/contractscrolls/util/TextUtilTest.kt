package me.l1cked.contractscrolls.util

import kotlin.test.Test
import kotlin.test.assertEquals

class TextUtilTest {
    @Test
    fun `stripLegacyColors removes vanilla formatting sequences`() {
        assertEquals("Hello World", TextUtil.stripLegacyColors("§aHello §6World"))
    }

    @Test
    fun `stripLegacyColors returns empty string for null input`() {
        assertEquals("", TextUtil.stripLegacyColors(null))
    }
}
