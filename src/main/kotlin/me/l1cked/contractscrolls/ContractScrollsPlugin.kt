package me.l1cked.contractscrolls

import me.l1cked.contractscrolls.contract.ContractStatus
import me.l1cked.contractscrolls.storage.ContractStore
import me.l1cked.contractscrolls.ContractMenus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class ContractScrollsPlugin : JavaPlugin(), Listener {
    private lateinit var pluginConfig: ContractScrollsConfig
    private lateinit var contractStore: ContractStore
    private lateinit var menus: ContractMenus
    private var expirySweepTask: BukkitTask? = null

    override fun onEnable() {
        saveDefaultConfig()
        pluginConfig = ContractScrollsConfig.from(config)
        contractStore = ContractStore(this, pluginConfig)
        contractStore.loadAll()
        menus = ContractMenus(contractStore, pluginConfig)
        menus.refreshCache()
        server.pluginManager.registerEvents(menus, this)
        ContractCommands(this, contractStore, pluginConfig, menus).register()
        server.pluginManager.registerEvents(this, this)
        runExpirySweep()
        startExpirySweepTask()
    }

    override fun onDisable() {
        expirySweepTask?.cancel()
        expirySweepTask = null
        contractStore.close()
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
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

    private fun isExpired(contract: me.l1cked.contractscrolls.contract.Contract, now: Long): Boolean {
        val expiresAt = contract.expiresAt ?: return false
        return now >= expiresAt
    }

    private fun startExpirySweepTask() {
        val periodTicks = (pluginConfig.expirationSweepSeconds.coerceAtLeast(15) * 20L)
        expirySweepTask = server.scheduler.runTaskTimer(this, Runnable { runExpirySweep() }, periodTicks, periodTicks)
    }

    private fun runExpirySweep() {
        val now = System.currentTimeMillis()
        val changed = hashSetOf<UUID>()
        for (contract in contractStore.getAll()) {
            if (isExpired(contract, now) &&
                (contract.status == ContractStatus.PROPOSED || contract.status == ContractStatus.SIGNED_BY_ONE)
            ) {
                contract.status = ContractStatus.EXPIRED
                contractStore.save(contract)
                contract.proposerUuid?.let { changed.add(it) }
                contract.counterpartyUuid?.let { changed.add(it) }
            }
        }
        for (playerId in changed) {
            menus.refreshCache(playerId)
        }
    }
}
