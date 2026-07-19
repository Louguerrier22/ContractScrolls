package me.l1cked.contractscrolls

import me.l1cked.contractscrolls.contract.Contract
import me.l1cked.contractscrolls.contract.ContractStatus
import me.l1cked.contractscrolls.storage.ContractStore
import java.math.BigDecimal
import java.math.RoundingMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

class ContractScrollsPlugin : JavaPlugin(), Listener {
    private lateinit var pluginConfig: ContractScrollsConfig
    private lateinit var contractStore: ContractStore
    private lateinit var menus: ContractMenus
    private lateinit var economyHook: VaultEconomyHook
    private lateinit var commands: ContractCommands
    private var expirySweepTask: BukkitTask? = null

    override fun onEnable() {
        saveDefaultConfig()
        pluginConfig = ContractScrollsConfig.from(config)
        economyHook = VaultEconomyHook(this)
        val economyAvailable = economyHook.initialize()
        contractStore = ContractStore(this, pluginConfig)
        contractStore.loadAll()
        menus = ContractMenus(this, contractStore, economyHook)
        menus.refreshCache()
        server.pluginManager.registerEvents(menus, this)
        commands = ContractCommands(this, contractStore, menus, economyHook)
        menus.bindActions(commands)
        commands.register()
        server.pluginManager.registerEvents(this, this)
        runExpirySweep()
        startExpirySweepTask()
        if (pluginConfig.proposalRequirements.price > 0.0 && !economyAvailable) {
            logger.warning("Proposal requirements price is enabled but no Vault economy provider is available. Proposals will be blocked until Vault is hooked or the price is disabled.")
        }
    }

    override fun onDisable() {
        expirySweepTask?.cancel()
        expirySweepTask = null
        contractStore.close()
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        menus.refreshCache(event.player.uniqueId)
        sendPendingContractsReminder(event.player)
    }

    private fun sendPendingContractsReminder(player: Player) {
        val now = System.currentTimeMillis()
        val pending = contractStore.getByCounterparty(player.uniqueId).asSequence()
            .filter { it.counterpartySignedAt == null }
            .filter { it.status == ContractStatus.PROPOSED || it.status == ContractStatus.SIGNED_BY_ONE }
            .filter { !isExpired(it, now) }
            .count()

        if (pending <= 0) {
            return
        }

        player.sendActionBar(
            Component.text("Pending contracts: $pending (use /contract pending)", NamedTextColor.GOLD)
        )
    }

    private fun isExpired(contract: Contract, now: Long): Boolean {
        val expiresAt = contract.expiresAt ?: return false
        return now >= expiresAt
    }

    fun currentConfig(): ContractScrollsConfig = pluginConfig

    fun updateRuntimeConfig(mutator: FileConfiguration.() -> Unit): ContractScrollsConfig {
        config.mutator()
        saveConfig()
        pluginConfig = ContractScrollsConfig.from(config)
        return pluginConfig
    }

    fun setProposalRequirementPrice(price: BigDecimal): ContractScrollsConfig {
        val normalized = price.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toDouble()
        return updateRuntimeConfig {
            set("proposalRequirements.price", normalized)
            set("proposalCreationFee", normalized)
        }
    }

    fun setMaxPendingProposals(limit: Int): ContractScrollsConfig {
        return updateRuntimeConfig {
            set("maxPendingProposals", limit.coerceAtLeast(0))
        }
    }

    fun setRequireBookAndQuill(enabled: Boolean): ContractScrollsConfig {
        return updateRuntimeConfig {
            set("proposalRequirements.requireBookAndQuill", enabled)
        }
    }

    fun setRequirementsBypass(target: OfflinePlayer, enabled: Boolean): ContractScrollsConfig {
        return updateRuntimeConfig {
            val existing = getStringList("proposalRequirements.bypassPlayers")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toMutableSet()
            val entry = target.uniqueId.toString()
            if (enabled) {
                existing += entry
            } else {
                existing -= entry
            }
            set("proposalRequirements.bypassPlayers", existing.sorted())
        }
    }

    private fun startExpirySweepTask() {
        val periodTicks = (pluginConfig.expirationSweepSeconds.coerceAtLeast(15) * 20L)
        expirySweepTask = server.scheduler.runTaskTimer(this, Runnable { runExpirySweep() }, periodTicks, periodTicks)
    }

    private fun runExpirySweep() {
        val now = System.currentTimeMillis()
        for (contract in contractStore.getAll()) {
            if (isExpired(contract, now) &&
                (contract.status == ContractStatus.PROPOSED || contract.status == ContractStatus.SIGNED_BY_ONE)
            ) {
                val previous = contractStore.snapshot(contract.contractId)
                contract.status = ContractStatus.EXPIRED
                contractStore.save(contract).whenComplete { persisted, throwable ->
                    server.scheduler.runTask(this, Runnable {
                        if (throwable != null || persisted != true) {
                            contractStore.revertIfCurrentRevision(contract.contractId, previous, contract.revision)
                            if (throwable != null) {
                                logger.warning("Failed to persist expired contract ${contract.contractId}: ${throwable.message}")
                            } else {
                                logger.warning("Failed to persist expired contract ${contract.contractId}.")
                            }
                            return@Runnable
                        }
                        contract.proposerUuid?.let { menus.refreshCache(it) }
                        contract.counterpartyUuid?.let { menus.refreshCache(it) }
                    })
                }
            }
        }
    }

}
