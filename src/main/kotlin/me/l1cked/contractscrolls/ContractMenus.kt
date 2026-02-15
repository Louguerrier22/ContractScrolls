package me.l1cked.contractscrolls

import me.l1cked.contractscrolls.contract.Contract
import me.l1cked.contractscrolls.contract.ContractStatus
import me.l1cked.contractscrolls.util.TimeUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

class ContractMenus(
    private val store: me.l1cked.contractscrolls.storage.ContractStore,
    private val config: ContractScrollsConfig
) : Listener {

    private enum class ContractsFilter { ALL, ACCEPTED, DENIED }

    fun openMainMenu(player: Player) {
        val holder = ContractMenuHolder("main")
        val inv = Bukkit.createInventory(holder, 27, Component.text("ContractScrolls", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        inv.setItem(11, menuItem(Material.WRITABLE_BOOK, "Propose", listOf("Choose player in UI", "Uses signed book in hand")))
        inv.setItem(13, menuItem(Material.CLOCK, "Pending", listOf(pendingCountLine(player), "Accept or deny proposals")))
        inv.setItem(15, menuItem(Material.BOOK, "Contracts", listOf("Open your contracts", "Filter with comparator")))

        if (player.hasPermission("contractscrolls.staff")) {
            inv.setItem(22, menuItem(Material.COMPASS, "Admin Player Lookup", listOf("Open contracts for any player")))
        }

        player.openInventory(inv)
    }

    fun openProposeTargetMenu(player: Player, page: Int = 0) {
        val holder = ContractMenuHolder("propose-target")
        val inv = Bukkit.createInventory(holder, 54, Component.text("Choose Contract Target", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        val names = Bukkit.getOnlinePlayers()
            .asSequence()
            .filter { it.uniqueId != player.uniqueId }
            .map { it.name }
            .sorted()
            .toList()

        openNameSelectionPage(
            player = player,
            holder = holder,
            inv = inv,
            titleMaterial = Material.WRITTEN_BOOK,
            title = "Select Player",
            titleLine = "Click a name to propose",
            names = names,
            page = page
        )
    }

    fun openAdminLookupMenu(player: Player, page: Int = 0) {
        val holder = ContractMenuHolder("admin-target")
        val inv = Bukkit.createInventory(holder, 54, Component.text("Admin Player Lookup", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        val names = Bukkit.getOfflinePlayers()
            .asSequence()
            .mapNotNull { it.name }
            .distinct()
            .sorted()
            .toList()

        openNameSelectionPage(
            player = player,
            holder = holder,
            inv = inv,
            titleMaterial = Material.COMPASS,
            title = "Select Player",
            titleLine = "Click to open contracts UI",
            names = names,
            page = page
        )
    }

    private fun openNameSelectionPage(
        player: Player,
        holder: ContractMenuHolder,
        inv: Inventory,
        titleMaterial: Material,
        title: String,
        titleLine: String,
        names: List<String>,
        page: Int
    ) {
        val slots = pendingContractSlots()
        val pageSize = slots.size
        val pageCount = if (names.isEmpty()) 1 else ((names.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, pageCount - 1)
        holder.page = safePage
        holder.playerNamesBySlot.clear()

        inv.setItem(4, menuItem(titleMaterial, title, listOf("Page ${safePage + 1} / $pageCount", titleLine), NamedTextColor.GOLD))
        inv.setItem(45, menuItem(Material.ARROW, "Back", listOf("Return to main menu")))
        if (safePage > 0) {
            inv.setItem(47, menuItem(Material.ARROW, "Previous Page", listOf("Go to page ${safePage}")))
        }
        if (safePage < pageCount - 1) {
            inv.setItem(53, menuItem(Material.ARROW, "Next Page", listOf("Go to page ${safePage + 2}")))
        }

        if (names.isEmpty()) {
            inv.setItem(31, menuItem(Material.BARRIER, "No Players Available", listOf("No player data available.")))
            player.openInventory(inv)
            return
        }

        val pageItems = names.drop(safePage * pageSize).take(pageSize)
        for ((index, name) in pageItems.withIndex()) {
            val slot = slots[index]
            holder.playerNamesBySlot[slot] = name
            inv.setItem(slot, menuItem(Material.PAPER, name, listOf("Click to select")))
        }
        player.openInventory(inv)
    }

    fun openContractsMenu(
        viewer: Player,
        target: OfflinePlayer = viewer,
        page: Int = 0,
        filter: String = ContractsFilter.ALL.name
    ) {
        val targetId = target.uniqueId
        val parsedFilter = parseFilter(filter)
        val holder = ContractMenuHolder("contracts")
        holder.page = page
        holder.targetUuid = targetId
        holder.targetName = target.name ?: targetId.toString()
        holder.filterName = parsedFilter.name

        val title = if (targetId == viewer.uniqueId) "Your Contracts" else "${holder.targetName}'s Contracts"
        val inv = Bukkit.createInventory(holder, 54, Component.text(title, NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        val contracts = contractsForFilter(targetId, parsedFilter)
        val slots = pendingContractSlots()
        val pageSize = slots.size
        val pageCount = if (contracts.isEmpty()) 1 else ((contracts.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, pageCount - 1)
        holder.page = safePage
        holder.contractIdsBySlot.clear()

        inv.setItem(4, menuItem(Material.PAPER, "Contracts", listOf("Filter: ${filterLabel(parsedFilter)}", "Page ${safePage + 1} / $pageCount", "Click a contract to read it"), NamedTextColor.GOLD))
        inv.setItem(45, menuItem(Material.ARROW, "Back", listOf("Return to main menu")))
        inv.setItem(49, filterToggleItem(parsedFilter))
        if (safePage > 0) {
            inv.setItem(47, menuItem(Material.ARROW, "Previous Page", listOf("Go to page ${safePage}")))
        }
        if (safePage < pageCount - 1) {
            inv.setItem(53, menuItem(Material.ARROW, "Next Page", listOf("Go to page ${safePage + 2}")))
        }

        if (contracts.isEmpty()) {
            inv.setItem(31, menuItem(Material.BARRIER, "No Contracts", listOf("Nothing matched this filter.")))
            viewer.openInventory(inv)
            return
        }

        val pageItems = contracts.drop(safePage * pageSize).take(pageSize)
        for ((index, contract) in pageItems.withIndex()) {
            val slot = slots[index]
            val contractId = contract.contractId ?: continue
            holder.contractIdsBySlot[slot] = contractId
            inv.setItem(slot, browseContractItem(contract, targetId))
        }

        viewer.openInventory(inv)
    }

    fun openPendingMenu(player: Player, page: Int = 0) {
        val holder = ContractMenuHolder("pending")
        val inv = Bukkit.createInventory(holder, 54, Component.text("Pending Contracts", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        val pending = pendingContracts(player)
        val slots = pendingContractSlots()
        val pageSize = slots.size
        val pageCount = if (pending.isEmpty()) 1 else ((pending.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, pageCount - 1)
        holder.page = safePage
        holder.contractIdsBySlot.clear()

        inv.setItem(4, menuItem(Material.PAPER, "Pending Contracts", listOf("Page ${safePage + 1} / $pageCount", "Left-click accept, right-click deny"), NamedTextColor.GOLD))
        inv.setItem(45, menuItem(Material.ARROW, "Back", listOf("Return to main menu")))
        if (safePage > 0) {
            inv.setItem(47, menuItem(Material.ARROW, "Previous Page", listOf("Go to page ${safePage}")))
        }
        if (safePage < pageCount - 1) {
            inv.setItem(53, menuItem(Material.ARROW, "Next Page", listOf("Go to page ${safePage + 2}")))
        }

        if (pending.isEmpty()) {
            inv.setItem(31, menuItem(Material.BARRIER, "No Pending Contracts", listOf("You're clear for now.")))
            player.openInventory(inv)
            return
        }

        val pageItems = pending.drop(safePage * pageSize).take(pageSize)
        for ((index, contract) in pageItems.withIndex()) {
            val slot = slots[index]
            val contractId = contract.contractId ?: continue
            holder.contractIdsBySlot[slot] = contractId
            inv.setItem(slot, pendingContractItem(contract))
        }

        player.openInventory(inv)
    }

    private fun openDenyConfirmMenu(player: Player, contractId: String, returnPage: Int) {
        val holder = ContractMenuHolder("deny-confirm")
        holder.page = returnPage
        holder.selectedContractId = contractId

        val inv = Bukkit.createInventory(holder, 27, Component.text("Confirm Deny", NamedTextColor.RED))
        holder.attach(inv)
        fillBorder(inv)

        inv.setItem(18, menuItem(Material.ARROW, "Back", listOf("Return to pending list")))
        inv.setItem(11, menuItem(Material.LIME_DYE, "Cancel", listOf("Return to pending list")))
        inv.setItem(13, menuItem(Material.BOOK, "Contract $contractId", listOf("Read terms first")))
        inv.setItem(15, menuItem(Material.RED_DYE, "Confirm Deny", listOf("This action cannot be undone")))
        player.openInventory(inv)
    }

    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? ContractMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) {
            return
        }

        val player = event.whoClicked as? Player ?: return
        when (holder.id) {
            "main" -> handleMainMenuClick(player, event.rawSlot)
            "pending" -> handlePendingMenuClick(player, holder, event.rawSlot, event.isLeftClick, event.isRightClick)
            "deny-confirm" -> handleDenyConfirmClick(player, holder, event.rawSlot)
            "contracts" -> handleContractsMenuClick(player, holder, event.rawSlot)
            "propose-target" -> handleProposeTargetClick(player, holder, event.rawSlot)
            "admin-target" -> handleAdminTargetClick(player, holder, event.rawSlot)
        }
    }

    @EventHandler
    fun onMenuDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is ContractMenuHolder) {
            event.isCancelled = true
        }
    }

    private fun handleMainMenuClick(player: Player, rawSlot: Int) {
        when (rawSlot) {
            11 -> openProposeTargetMenu(player)
            13 -> openPendingMenu(player)
            15 -> openContractsMenu(player)
            22 -> if (player.hasPermission("contractscrolls.staff")) openAdminLookupMenu(player)
        }
    }

    private fun handleContractsMenuClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        val targetUuid = holder.targetUuid ?: player.uniqueId
        val target = Bukkit.getOfflinePlayer(targetUuid)
        val currentFilter = parseFilter(holder.filterName)
        when (rawSlot) {
            45 -> {
                openMainMenu(player)
                return
            }
            47 -> {
                if (holder.page > 0) {
                    openContractsMenu(player, target, holder.page - 1, currentFilter.name)
                }
                return
            }
            49 -> {
                openContractsMenu(player, target, 0, nextFilter(currentFilter).name)
                return
            }
            53 -> {
                openContractsMenu(player, target, holder.page + 1, currentFilter.name)
                return
            }
        }

        val contractId = holder.contractIdsBySlot[rawSlot] ?: return
        player.closeInventory()
        player.performCommand("contract view $contractId")
    }

    private fun handlePendingMenuClick(
        player: Player,
        holder: ContractMenuHolder,
        rawSlot: Int,
        isLeftClick: Boolean,
        isRightClick: Boolean
    ) {
        when (rawSlot) {
            45 -> {
                openMainMenu(player)
                return
            }
            47 -> {
                if (holder.page > 0) {
                    openPendingMenu(player, holder.page - 1)
                }
                return
            }
            53 -> {
                openPendingMenu(player, holder.page + 1)
                return
            }
        }

        val contractId = holder.contractIdsBySlot[rawSlot] ?: return
        when {
            isLeftClick -> {
                player.closeInventory()
                player.performCommand("contract accept $contractId")
            }
            isRightClick -> openDenyConfirmMenu(player, contractId, holder.page)
            else -> {
                player.closeInventory()
                player.performCommand("contract view $contractId")
            }
        }
    }

    private fun handleDenyConfirmClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        val contractId = holder.selectedContractId ?: run {
            openPendingMenu(player, holder.page)
            return
        }

        when (rawSlot) {
            18, 11 -> openPendingMenu(player, holder.page)
            13 -> {
                player.closeInventory()
                player.performCommand("contract view $contractId")
            }
            15 -> {
                player.closeInventory()
                player.performCommand("contract deny $contractId")
            }
        }
    }

    private fun handleProposeTargetClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        when (rawSlot) {
            45 -> {
                openMainMenu(player)
                return
            }
            47 -> {
                if (holder.page > 0) {
                    openProposeTargetMenu(player, holder.page - 1)
                }
                return
            }
            53 -> {
                openProposeTargetMenu(player, holder.page + 1)
                return
            }
        }

        val targetName = holder.playerNamesBySlot[rawSlot] ?: return
        player.closeInventory()
        player.performCommand("contract propose $targetName")
    }

    private fun handleAdminTargetClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        when (rawSlot) {
            45 -> {
                openMainMenu(player)
                return
            }
            47 -> {
                if (holder.page > 0) {
                    openAdminLookupMenu(player, holder.page - 1)
                }
                return
            }
            53 -> {
                openAdminLookupMenu(player, holder.page + 1)
                return
            }
        }

        val targetName = holder.playerNamesBySlot[rawSlot] ?: return
        player.closeInventory()
        player.performCommand("contract admin search $targetName")
    }

    private fun fillBorder(inv: Inventory) {
        val pane = menuItem(Material.GRAY_STAINED_GLASS_PANE, "", emptyList())
        val rows = inv.size / 9
        for (slot in 0 until inv.size) {
            val row = slot / 9
            val col = slot % 9
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                inv.setItem(slot, pane)
            }
        }
    }

    private fun pendingContractSlots(): List<Int> {
        return listOf(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        )
    }

    private fun pendingContractItem(contract: Contract): ItemStack {
        val contractId = contract.contractId ?: "Unknown"
        val lore = mutableListOf(
            "From: ${contract.proposerName ?: "Unknown"}",
            "Created: ${TimeUtil.format(contract.createdAt, config.zoneId)}",
            "Status: ${statusLabel(effectiveStatus(contract))}",
            expiryLine(contract),
            "",
            "Left-click: Accept + sign",
            "Right-click: Deny"
        ).filter { it.isNotBlank() }
        return menuItem(Material.WRITABLE_BOOK, contractId, lore, statusColor(effectiveStatus(contract)))
    }

    private fun browseContractItem(contract: Contract, targetUuid: UUID): ItemStack {
        val contractId = contract.contractId ?: "Unknown"
        val status = effectiveStatus(contract)
        val role = when (targetUuid) {
            contract.proposerUuid -> "Proposed to ${contract.counterpartyName ?: "Unknown"}"
            contract.counterpartyUuid -> "From ${contract.proposerName ?: "Unknown"}"
            else -> "${contract.proposerName ?: "Unknown"} <-> ${contract.counterpartyName ?: "Unknown"}"
        }
        val lore = mutableListOf(
            role,
            "Created: ${TimeUtil.format(contract.createdAt, config.zoneId)}",
            "Status: ${statusLabel(status)}",
            expiryLine(contract),
            "",
            "Click to read terms"
        ).filter { it.isNotBlank() }
        return menuItem(Material.BOOK, contractId, lore, statusColor(status))
    }

    private fun expiryLine(contract: Contract): String {
        val expiresAt = contract.expiresAt ?: return ""
        return "Expires in: ${TimeUtil.formatRemainingMillis(expiresAt - System.currentTimeMillis())}"
    }

    private fun pendingCountLine(player: Player): String {
        val pending = pendingContracts(player).size
        return if (pending == 0) "No pending offers" else "Pending: $pending"
    }

    private fun pendingContracts(player: Player): List<Contract> {
        val now = System.currentTimeMillis()
        return store.getByCounterparty(player.uniqueId).asSequence()
            .filter { it.counterpartySignedAt == null }
            .filter { it.status == ContractStatus.PROPOSED || it.status == ContractStatus.SIGNED_BY_ONE }
            .filter { !isExpired(it, now) }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    private fun isExpired(contract: Contract, now: Long = System.currentTimeMillis()): Boolean {
        val expiresAt = contract.expiresAt ?: return false
        return now >= expiresAt
    }

    private fun statusColor(status: ContractStatus): NamedTextColor {
        return when (status) {
            ContractStatus.DRAFT -> NamedTextColor.GRAY
            ContractStatus.PROPOSED -> NamedTextColor.YELLOW
            ContractStatus.SIGNED_BY_ONE -> NamedTextColor.GOLD
            ContractStatus.SIGNED_BY_BOTH -> NamedTextColor.GREEN
            ContractStatus.EXPIRED -> NamedTextColor.DARK_GRAY
            ContractStatus.DENIED -> NamedTextColor.RED
            ContractStatus.REVOKED -> NamedTextColor.RED
        }
    }

    private fun statusLabel(status: ContractStatus): String {
        return when (status) {
            ContractStatus.DRAFT -> "Draft"
            ContractStatus.PROPOSED -> "Proposed"
            ContractStatus.SIGNED_BY_ONE -> "Proposed"
            ContractStatus.SIGNED_BY_BOTH -> "Accepted"
            ContractStatus.EXPIRED -> "Expired"
            ContractStatus.DENIED -> "Denied"
            ContractStatus.REVOKED -> "Revoked"
        }
    }

    private fun effectiveStatus(contract: Contract, now: Long = System.currentTimeMillis()): ContractStatus {
        if (isExpired(contract, now) &&
            (contract.status == ContractStatus.PROPOSED || contract.status == ContractStatus.SIGNED_BY_ONE)
        ) {
            return ContractStatus.EXPIRED
        }
        if (contract.status == ContractStatus.SIGNED_BY_ONE) {
            return ContractStatus.PROPOSED
        }
        return contract.status
    }

    private fun contractsForFilter(targetUuid: UUID, filter: ContractsFilter): List<Contract> {
        val now = System.currentTimeMillis()
        return store.getByParty(targetUuid).asSequence()
            .filter { contract ->
                val status = effectiveStatus(contract, now)
                when (filter) {
                    ContractsFilter.ALL -> true
                    ContractsFilter.ACCEPTED -> status == ContractStatus.SIGNED_BY_BOTH
                    ContractsFilter.DENIED -> status == ContractStatus.DENIED
                }
            }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    private fun filterToggleItem(active: ContractsFilter): ItemStack {
        val next = nextFilter(active)
        return menuItem(Material.COMPARATOR, "Filter: ${filterLabel(active)}", listOf("Click to cycle filter", "Next: ${filterLabel(next)}"), NamedTextColor.GREEN)
    }

    private fun nextFilter(filter: ContractsFilter): ContractsFilter {
        return when (filter) {
            ContractsFilter.ALL -> ContractsFilter.ACCEPTED
            ContractsFilter.ACCEPTED -> ContractsFilter.DENIED
            ContractsFilter.DENIED -> ContractsFilter.ALL
        }
    }

    private fun filterLabel(filter: ContractsFilter): String {
        return when (filter) {
            ContractsFilter.ALL -> "All"
            ContractsFilter.ACCEPTED -> "Accepted"
            ContractsFilter.DENIED -> "Denied"
        }
    }

    private fun parseFilter(name: String?): ContractsFilter {
        if (name.isNullOrBlank()) {
            return ContractsFilter.ALL
        }
        return try {
            ContractsFilter.valueOf(name.uppercase())
        } catch (_: IllegalArgumentException) {
            ContractsFilter.ALL
        }
    }

    private fun menuItem(
        material: Material,
        title: String,
        lore: List<String>,
        titleColor: NamedTextColor = NamedTextColor.AQUA
    ): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta as ItemMeta
        meta.displayName(Component.text(title, titleColor))
        if (lore.isNotEmpty()) {
            meta.lore(lore.map { Component.text(it, NamedTextColor.GRAY) })
        }
        item.itemMeta = meta
        return item
    }

    @Suppress("UNUSED_PARAMETER")
    fun refreshCache(playerId: UUID? = null) = Unit

    private class ContractMenuHolder(
        val id: String,
        var page: Int = 0,
        var selectedContractId: String? = null,
        var filterName: String = ContractsFilter.ALL.name,
        var targetUuid: UUID? = null,
        var targetName: String? = null
    ) : InventoryHolder {
        private var inv: Inventory? = null
        val contractIdsBySlot: MutableMap<Int, String> = mutableMapOf()
        val playerNamesBySlot: MutableMap<Int, String> = mutableMapOf()

        fun attach(inventory: Inventory) {
            this.inv = inventory
        }

        override fun getInventory(): Inventory {
            return inv ?: Bukkit.createInventory(null, 9)
        }
    }
}
