package me.l1cked.contractscrolls

import java.util.Locale
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin

class VaultEconomyHook(
    private val plugin: JavaPlugin
) {
    private var provider: Economy? = null

    fun initialize(): Boolean {
        if (plugin.server.pluginManager.getPlugin("Vault") == null) {
            provider = null
            return false
        }

        val registration = plugin.server.servicesManager.getRegistration(Economy::class.java)
        provider = registration?.provider
        return provider != null
    }

    fun isAvailable(): Boolean = provider != null

    fun format(amount: Double): String {
        val economy = provider
        return if (economy != null) {
            economy.format(amount)
        } else {
            String.format(Locale.US, "%.2f", amount)
        }
    }

    fun has(player: OfflinePlayer, amount: Double): Boolean {
        if (amount <= 0.0) {
            return true
        }
        return provider?.has(player, amount) == true
    }

    fun withdraw(player: OfflinePlayer, amount: Double): EconomyResponse? {
        if (amount <= 0.0) {
            return null
        }
        return provider?.withdrawPlayer(player, amount)
    }

    fun deposit(player: OfflinePlayer, amount: Double): EconomyResponse? {
        if (amount <= 0.0) {
            return null
        }
        return provider?.depositPlayer(player, amount)
    }
}
