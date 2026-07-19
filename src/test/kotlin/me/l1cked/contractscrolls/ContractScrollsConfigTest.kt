package me.l1cked.contractscrolls

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.configuration.file.YamlConfiguration

class ContractScrollsConfigTest {
    @Test
    fun `config parses bypass players and proposal settings`() {
        val bypassId = UUID.randomUUID()
        val yaml = YamlConfiguration().apply {
            set("proposalCreationFee", 12.5)
            set("proposalRequirements.requireBookAndQuill", true)
            set("proposalRequirements.bypassPlayers", listOf(bypassId.toString(), "not-a-uuid"))
            set("storage.mode", "sqlite")
        }

        val config = ContractScrollsConfig.from(yaml)

        assertEquals(12.5, config.proposalRequirements.price)
        assertTrue(config.requireBookAndQuill)
        assertTrue(config.proposalRequirements.isBypassed(bypassId))
    }

    @Test
    fun `invalid timezone falls back to server default`() {
        val yaml = YamlConfiguration().apply {
            set("timezoneDisplay", "Definitely/NotAZone")
        }

        val config = ContractScrollsConfig.from(yaml)

        assertFalse(config.zoneId.id.isBlank())
    }
}
