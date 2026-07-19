package me.l1cked.contractscrolls

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.WeakHashMap
import me.l1cked.contractscrolls.contract.Contract
import me.l1cked.contractscrolls.contract.ContractStatus
import me.l1cked.contractscrolls.storage.ContractStore
import me.l1cked.contractscrolls.util.TimeUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.inventory.view.AnvilView
import org.bukkit.persistence.PersistentDataType

class ContractMenus(
    private val plugin: ContractScrollsPlugin,
    private val store: ContractStore,
    private val economyHook: VaultEconomyHook
) : Listener {
    interface Actions {
        fun proposeTo(player: Player, targetId: UUID)
        fun confirmProposal(player: Player, targetId: UUID)
        fun applyTemplate(player: Player, templateId: String)
        fun viewContract(player: Player, contractId: String)
        fun acceptContract(player: Player, contractId: String)
        fun denyContract(player: Player, contractId: String)
        fun openAdminSearch(player: Player, primaryTargetId: UUID, secondaryTargetId: UUID? = null)
    }

    private val playerNameCacheLock = Any()
    private val adminInputPrompts = WeakHashMap<Inventory, AdminInputPromptState>()
    private val promptPaperKey = NamespacedKey(plugin, "admin_price_prompt_paper")
    private val knownPlayersById: MutableMap<UUID, String> = linkedMapOf()
    @Volatile
    private var knownPlayerNamesCache: List<String> = emptyList()
    private var actions: Actions? = null

    private val config: ContractScrollsConfig
        get() = plugin.currentConfig()

    private enum class ContractsFilter { ALL, PENDING, ACCEPTED, CLOSED, DENIED }

    private enum class AdminInputPromptKind {
        PROPOSAL_PRICE,
        MAX_PENDING_PROPOSALS
    }

    fun bindActions(actions: Actions) {
        this.actions = actions
    }

    fun openMainMenu(player: Player) {
        val holder = ContractMenuHolder("main")
        val inv = Bukkit.createInventory(holder, 27, Component.text("ContractScrolls", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        inv.setItem(
            10,
            menuItem(
                Material.WRITABLE_BOOK,
                "Propose Contract",
                buildList {
                    add("Start with the book in your main hand")
                    add("Choose a player, then send the draft")
                    proposalFeeLine(player)?.let { add(it) }
                }
            )
        )
        inv.setItem(
            12,
            menuItem(
                Material.CHISELED_BOOKSHELF,
                "Templates",
                buildList {
                    add(templateRequirementLine(player))
                    add(templateCountLine())
                    add("Load a saved draft")
                }
            )
        )
        inv.setItem(
            14,
            menuItem(
                Material.CLOCK,
                "Pending Contracts",
                listOf(pendingCountLine(player), "Review pending contracts")
            )
        )
        inv.setItem(
            16,
            menuItem(
                Material.LECTERN,
                "Contracts",
                listOf(contractCountLine(player), "Browse saved contracts", "Filter by status inside the contracts menu")
            )
        )

        if (player.hasPermission(STAFF_PERMISSION)) {
            inv.setItem(
                22,
                menuItem(
                    Material.RECOVERY_COMPASS,
                    "Admin Tools",
                    listOf("Search contracts", "Manage proposal rules", "Toggle requirement bypasses")
                )
            )
        }

        inv.setItem(
            4,
            menuItem(
                Material.NETHER_STAR,
                "ContractScrolls",
                listOf(
                    "Create, review, and track player contracts.",
                    "Open Admin Tools for staff actions."
                ),
                NamedTextColor.GOLD
            )
        )

        player.openInventory(inv)
    }

    fun openTemplatesMenu(player: Player, page: Int = 0) {
        val holder = ContractMenuHolder("templates")
        val inv = Bukkit.createInventory(holder, 54, Component.text("Templates", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        val templates = config.templates
        val slots = pendingContractSlots()
        val pageSize = slots.size
        val pageCount = if (templates.isEmpty()) 1 else ((templates.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, pageCount - 1)
        holder.page = safePage
        holder.templateIdsBySlot.clear()

        inv.setItem(
            4,
            menuItem(
                Material.CHISELED_BOOKSHELF,
                "Templates",
                listOf(
                    "Page ${safePage + 1} / $pageCount",
                    templateRequirementLine(player),
                    "Click one draft to load it into a book and quill"
                ),
                NamedTextColor.GOLD
            )
        )
        inv.setItem(backSlot(inv), menuItem(Material.ARROW, "Back", listOf("Return to the main menu")))
        if (safePage > 0) {
            inv.setItem(previousSlot(inv), menuItem(Material.ARROW, "Previous Page", listOf("Go to page $safePage")))
        }
        if (safePage < pageCount - 1) {
            inv.setItem(nextSlot(inv), menuItem(Material.ARROW, "Next Page", listOf("Go to page ${safePage + 2}")))
        }

        if (templates.isEmpty()) {
            inv.setItem(31, menuItem(Material.BARRIER, "No Templates", listOf("No templates are configured right now.")))
            player.openInventory(inv)
            return
        }

        val pageItems = templates.drop(safePage * pageSize).take(pageSize)
        for ((index, template) in pageItems.withIndex()) {
            val slot = slots[index]
            holder.templateIdsBySlot[slot] = template.id
            inv.setItem(
                slot,
                menuItem(
                    Material.WRITABLE_BOOK,
                    template.title,
                    buildList {
                        addAll(template.description)
                        add("Pages: ${template.pages.size}")
                        add("")
                        add("Click to load this draft")
                    }
                )
            )
        }

        player.openInventory(inv)
    }

    fun openAdminMenu(player: Player) {
        if (!requireStaffAccess(player)) {
            return
        }
        val holder = ContractMenuHolder("admin")
        val inv = Bukkit.createInventory(holder, 27, Component.text("Contract Admin", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        inv.setItem(4, menuItem(Material.RECOVERY_COMPASS, "Contract Admin", listOf("Staff-only contract tools"), NamedTextColor.GOLD))
        inv.setItem(10, menuItem(Material.COMPASS, "Search Contracts", listOf("Open one player's contracts", "Use Compare inside search for two players")))
        inv.setItem(
            13,
            menuItem(
                Material.HOPPER,
                "Proposal Settings",
                listOf(
                    "Price: ${economyHook.format(config.proposalRequirements.price)}",
                    "Max pending proposals: ${config.maxPendingProposals}",
                    "Template book required: ${toggleLabel(config.requireBookAndQuill)}"
                )
            )
        )
        inv.setItem(
            16,
            menuItem(
                Material.NAME_TAG,
                "Manage Bypasses",
                listOf(
                    "Bypass players: ${config.proposalRequirements.bypassPlayers.size}",
                    "Toggle requirement bypass per player"
                )
            )
        )
        inv.setItem(backSlot(inv), menuItem(Material.ARROW, "Back", listOf("Return to the main menu")))

        player.openInventory(inv)
    }

    fun openAdminSettingsMenu(player: Player) {
        if (!requireStaffAccess(player)) {
            return
        }
        val holder = ContractMenuHolder("admin-settings")
        val inv = Bukkit.createInventory(holder, 27, Component.text("Proposal Settings", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        val currentPrice = currentProposalPrice()
        inv.setItem(
            4,
            menuItem(
                Material.HOPPER,
                "Proposal Settings",
                listOf(
                    "Price: ${economyHook.format(currentPrice.toDouble())}",
                    "Max pending proposals: ${config.maxPendingProposals}",
                    "Template book required: ${toggleLabel(config.requireBookAndQuill)}",
                    "Bypass players: ${config.proposalRequirements.bypassPlayers.size}"
                ),
                NamedTextColor.GOLD
            )
        )
        inv.setItem(
            10,
            menuItem(
                Material.GOLD_INGOT,
                "Set Proposal Price",
                listOf(
                    "Current: ${economyHook.format(currentPrice.toDouble())}",
                    "Click to enter a new value",
                    "/contract admin settings price <amount>"
                )
            )
        )
        inv.setItem(
            12,
            menuItem(
                Material.CLOCK,
                "Set Max Pending Proposals",
                listOf(
                    "Current: ${config.maxPendingProposals}",
                    "Click to enter a new value",
                    "/contract admin settings pending <amount>"
                )
            )
        )
        inv.setItem(
            14,
            menuItem(
                Material.NAME_TAG,
                "Manage Bypasses",
                listOf(
                    "Current count: ${config.proposalRequirements.bypassPlayers.size}",
                    "Click to toggle players",
                    "/contract admin bypass <player> [on|off]"
                ),
                NamedTextColor.GOLD
            )
        )
        inv.setItem(
            16,
            menuItem(
                Material.WRITABLE_BOOK,
                "Toggle Template Book Requirement",
                listOf(
                    "Current: ${toggleLabel(config.requireBookAndQuill)}",
                    "Click to toggle",
                    "/contract admin settings book <on|off>"
                )
            )
        )
        inv.setItem(backSlot(inv), menuItem(Material.ARROW, "Back", listOf("Return to admin menu")))

        player.openInventory(inv)
    }

    fun openProposeTargetMenu(player: Player, page: Int = 0) {
        val holder = ContractMenuHolder("propose-target")
        val inv = Bukkit.createInventory(holder, 54, Component.text("Choose Contract Target", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        val choices = Bukkit.getOnlinePlayers()
            .asSequence()
            .filter { it.uniqueId != player.uniqueId }
            .map { PlayerChoice(it.uniqueId, it.name) }
            .sortedBy { it.name.lowercase() }
            .toList()

        openNameSelectionPage(
            player = player,
            holder = holder,
            inv = inv,
            titleMaterial = Material.WRITABLE_BOOK,
            title = "Select Player",
            titleLines = buildList {
                add("Click a name to propose a contract")
                proposalFeeLine(player)?.let { add(it) }
            },
            choices = choices,
            page = page
        )
    }

    fun openProposalConfirmMenu(player: Player, target: OfflinePlayer, proposalFee: Double, pageCount: Int) {
        val holder = ContractMenuHolder("propose-confirm")
        holder.targetUuid = target.uniqueId
        holder.targetName = target.name ?: target.uniqueId.toString()
        val inv = Bukkit.createInventory(holder, 27, Component.text("Confirm Proposal", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        inv.setItem(
            4,
            menuItem(
                Material.WRITABLE_BOOK,
                "Confirm Proposal",
                listOf("Review the target and charge before sending"),
                NamedTextColor.GOLD
            )
        )
        inv.setItem(
            13,
            playerHeadItem(
                target,
                holder.targetName ?: "Unknown",
                buildList {
                    add("Pages: $pageCount")
                    if (proposalFee > 0.0) {
                        add("Charge: ${economyHook.format(proposalFee)}")
                        add("You will pay when you confirm")
                    } else {
                        add("Charge: Free")
                    }
                }
            )
        )
        inv.setItem(11, menuItem(Material.RED_DYE, "Cancel", listOf("Do not send this proposal")))
        inv.setItem(15, menuItem(Material.LIME_DYE, "Confirm", listOf("Send the proposal now")))
        inv.setItem(backSlot(inv), menuItem(Material.ARROW, "Back", listOf("Choose a different player")))

        player.openInventory(inv)
    }

    fun openAdminLookupMenu(player: Player, page: Int = 0) {
        if (!requireStaffAccess(player)) {
            return
        }
        val holder = ContractMenuHolder("admin-target")
        val inv = Bukkit.createInventory(holder, 54, Component.text("Search Contracts", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        val choices = knownPlayerChoices()
        openNameSelectionPage(
            player = player,
            holder = holder,
            inv = inv,
            titleMaterial = Material.COMPASS,
            title = "Search Contracts",
            titleLines = listOf("Click one player to open their contracts", "Use Compare below to select two players"),
            choices = choices,
            page = page,
            extraItems = mapOf(
                49 to menuItem(
                    Material.NAME_TAG,
                    "Compare Two Players",
                    listOf("Pick two players and show shared contracts")
                )
            )
        )
    }

    fun openAdminCompareFirstMenu(player: Player, page: Int = 0) {
        if (!requireStaffAccess(player)) {
            return
        }
        val holder = ContractMenuHolder("admin-pair-first")
        val inv = Bukkit.createInventory(holder, 54, Component.text("Compare Players", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        openNameSelectionPage(
            player = player,
            holder = holder,
            inv = inv,
            titleMaterial = Material.NAME_TAG,
            title = "Select First Player",
            titleLines = listOf("Step 1 of 2", "Choose the first player"),
            choices = knownPlayerChoices(),
            page = page
        )
    }

    fun openAdminCompareSecondMenu(player: Player, firstTarget: OfflinePlayer, page: Int = 0) {
        if (!requireStaffAccess(player)) {
            return
        }
        val holder = ContractMenuHolder("admin-pair-second")
        holder.targetUuid = firstTarget.uniqueId
        holder.targetName = firstTarget.name ?: firstTarget.uniqueId.toString()
        val inv = Bukkit.createInventory(holder, 54, Component.text("Compare Players", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        openNameSelectionPage(
            player = player,
            holder = holder,
            inv = inv,
            titleMaterial = Material.NAME_TAG,
            title = "Select Second Player",
            titleLines = listOf("Step 2 of 2", "First: ${holder.targetName}", "Show only contracts shared by both players"),
            choices = knownPlayerChoices().filter { it.uniqueId != firstTarget.uniqueId },
            page = page
        )
    }

    fun openAdminBypassMenu(player: Player, page: Int = 0) {
        if (!requireStaffAccess(player)) {
            return
        }
        val holder = ContractMenuHolder("admin-bypass")
        val inv = Bukkit.createInventory(holder, 54, Component.text("Manage Bypasses", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        openNameSelectionPage(
            player = player,
            holder = holder,
            inv = inv,
            titleMaterial = Material.NAME_TAG,
            title = "Manage Bypasses",
            titleLines = listOf("Click a player to toggle requirement bypass"),
            choices = knownPlayerChoices(),
            page = page,
            itemLoreProvider = { choice ->
                val bypassed = isBypassed(choice.uniqueId)
                listOf("Bypass: ${toggleLabel(bypassed)}", "Click to ${if (bypassed) "disable" else "enable"}")
            }
        )
    }

    private fun openNameSelectionPage(
        player: Player,
        holder: ContractMenuHolder,
        inv: Inventory,
        titleMaterial: Material,
        title: String,
        titleLines: List<String>,
        choices: List<PlayerChoice>,
        page: Int,
        itemLoreProvider: (PlayerChoice) -> List<String> = { listOf("Click to select") },
        extraItems: Map<Int, ItemStack> = emptyMap()
    ) {
        val slots = pendingContractSlots()
        val pageSize = slots.size
        val pageCount = if (choices.isEmpty()) 1 else ((choices.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, pageCount - 1)
        holder.page = safePage
        holder.playerIdsBySlot.clear()

        inv.setItem(4, menuItem(titleMaterial, title, listOf("Page ${safePage + 1} / $pageCount") + titleLines, NamedTextColor.GOLD))
        inv.setItem(backSlot(inv), menuItem(Material.ARROW, "Back", listOf("Go back")))
        if (safePage > 0) {
            inv.setItem(previousSlot(inv), menuItem(Material.ARROW, "Previous Page", listOf("Go to page $safePage")))
        }
        if (safePage < pageCount - 1) {
            inv.setItem(nextSlot(inv), menuItem(Material.ARROW, "Next Page", listOf("Go to page ${safePage + 2}")))
        }

        if (choices.isEmpty()) {
            inv.setItem(31, menuItem(Material.BARRIER, "No Players Available", listOf("No player data is available.")))
            player.openInventory(inv)
            return
        }

        val pageItems = choices.drop(safePage * pageSize).take(pageSize)
        for ((index, choice) in pageItems.withIndex()) {
            val slot = slots[index]
            holder.playerIdsBySlot[slot] = choice.uniqueId
            val offline = Bukkit.getPlayer(choice.uniqueId) ?: Bukkit.getOfflinePlayer(choice.uniqueId)
            inv.setItem(slot, playerHeadItem(offline, choice.name, itemLoreProvider(choice)))
        }
        for ((slot, item) in extraItems) {
            inv.setItem(slot, item)
        }

        player.openInventory(inv)
    }

    fun openContractsMenu(
        viewer: Player,
        target: OfflinePlayer = viewer,
        page: Int = 0,
        filter: String = ContractsFilter.ALL.name
    ) {
        openContractsMenuInternal(viewer, target, null, page, filter)
    }

    fun openContractsMenu(
        viewer: Player,
        primaryTarget: OfflinePlayer,
        secondaryTarget: OfflinePlayer,
        page: Int = 0,
        filter: String = ContractsFilter.ALL.name
    ) {
        openContractsMenuInternal(viewer, primaryTarget, secondaryTarget, page, filter)
    }

    private fun openContractsMenuInternal(
        viewer: Player,
        primaryTarget: OfflinePlayer,
        secondaryTarget: OfflinePlayer?,
        page: Int,
        filter: String
    ) {
        val viewingOtherParty = secondaryTarget != null || primaryTarget.uniqueId != viewer.uniqueId
        if (viewingOtherParty && !viewer.hasPermission(STAFF_PERMISSION)) {
            viewer.sendMessage(Component.text("You do not have permission to view other players' contracts.", NamedTextColor.RED))
            openMainMenu(viewer)
            return
        }

        val targetId = primaryTarget.uniqueId
        val parsedFilter = parseFilter(filter)
        val holder = ContractMenuHolder("contracts")
        holder.page = page
        holder.targetUuid = targetId
        holder.targetName = primaryTarget.name ?: targetId.toString()
        holder.secondaryTargetUuid = secondaryTarget?.uniqueId
        holder.secondaryTargetName = secondaryTarget?.name ?: secondaryTarget?.uniqueId?.toString()
        holder.filterName = parsedFilter.name

        val title = when {
            secondaryTarget != null -> "${holder.targetName} + ${holder.secondaryTargetName}"
            targetId == viewer.uniqueId -> "Your Contracts"
            else -> "${holder.targetName}'s Contracts"
        }
        val inv = Bukkit.createInventory(holder, 54, Component.text(title, NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        val contracts = contractsForFilter(targetId, holder.secondaryTargetUuid, parsedFilter)
        val slots = pendingContractSlots()
        val pageSize = slots.size
        val pageCount = if (contracts.isEmpty()) 1 else ((contracts.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, pageCount - 1)
        holder.page = safePage
        holder.contractIdsBySlot.clear()

        val headerLore = mutableListOf("Page ${safePage + 1} / $pageCount")
        if (secondaryTarget != null) {
            headerLore += "Scope: ${holder.targetName} + ${holder.secondaryTargetName}"
        }
        headerLore += "Current filter: ${filterLabel(parsedFilter)}"
        headerLore += "Choose a status below"
        inv.setItem(4, menuItem(Material.PAPER, "Contracts", headerLore, NamedTextColor.GOLD))
        inv.setItem(backSlot(inv), menuItem(Material.ARROW, "Back", listOf("Go back")))
        inv.setItem(47, contractFilterItem(ContractsFilter.ALL, parsedFilter))
        inv.setItem(48, contractFilterItem(ContractsFilter.PENDING, parsedFilter))
        inv.setItem(49, contractFilterItem(ContractsFilter.ACCEPTED, parsedFilter))
        inv.setItem(50, contractFilterItem(ContractsFilter.CLOSED, parsedFilter))
        inv.setItem(51, contractFilterItem(ContractsFilter.DENIED, parsedFilter))
        if (safePage > 0) {
            inv.setItem(46, menuItem(Material.ARROW, "Previous Page", listOf("Go to page $safePage")))
        }
        if (safePage < pageCount - 1) {
            inv.setItem(52, menuItem(Material.ARROW, "Next Page", listOf("Go to page ${safePage + 2}")))
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
            inv.setItem(slot, browseContractItem(contract, targetId, holder.secondaryTargetUuid))
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

        inv.setItem(
            4,
            menuItem(
                Material.CLOCK,
                "Pending Contracts",
                listOf("Page ${safePage + 1} / $pageCount", "Click a contract to review it"),
                NamedTextColor.GOLD
            )
        )
        inv.setItem(backSlot(inv), menuItem(Material.ARROW, "Back", listOf("Return to the main menu")))
        if (safePage > 0) {
            inv.setItem(previousSlot(inv), menuItem(Material.ARROW, "Previous Page", listOf("Go to page $safePage")))
        }
        if (safePage < pageCount - 1) {
            inv.setItem(nextSlot(inv), menuItem(Material.ARROW, "Next Page", listOf("Go to page ${safePage + 2}")))
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

    private fun openPendingDetailMenu(player: Player, contractId: String, returnPage: Int) {
        val contract = store.get(contractId)
        if (contract == null || contract.counterpartyUuid != player.uniqueId || effectiveStatus(contract) != ContractStatus.PROPOSED) {
            player.sendMessage(Component.text("That pending contract is no longer available.", NamedTextColor.RED))
            openPendingMenu(player, returnPage)
            return
        }

        val holder = ContractMenuHolder("pending-detail")
        holder.page = returnPage
        holder.selectedContractId = contractId

        val inv = Bukkit.createInventory(holder, 45, Component.text("Review Contract", NamedTextColor.GOLD))
        holder.attach(inv)
        fillBorder(inv)

        inv.setItem(
            4,
            menuItem(
                Material.BOOK,
                "Review Contract",
                listOf("Read the terms before accepting or denying"),
                NamedTextColor.GOLD
            )
        )
        inv.setItem(
            13,
            playerHeadItem(
                contract.proposerUuid?.let(Bukkit::getOfflinePlayer),
                contract.proposerName ?: "Unknown",
                listOf(
                    "Contract: ${contract.contractId ?: "Unknown"}",
                    "Created: ${TimeUtil.format(contract.createdAt, config.zoneId)}",
                    expiryLine(contract)
                ).filter { it.isNotBlank() }
            )
        )
        inv.setItem(backSlot(inv), menuItem(Material.ARROW, "Back", listOf("Return to pending contracts")))
        inv.setItem(20, menuItem(Material.BOOK, "Read Terms", listOf("Open the contract book")))
        inv.setItem(22, menuItem(Material.LIME_DYE, "Accept And Sign", listOf("Accept this contract and record your signature now")))
        inv.setItem(24, menuItem(Material.RED_DYE, "Deny Contract", listOf("Deny this contract now")))
        player.openInventory(inv)
    }

    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val promptState = adminInputPrompts[event.view.topInventory]
        if (promptState != null && event.view is AnvilView) {
            handleAdminInputPromptClick(player, promptState, event)
            return
        }

        val holder = event.view.topInventory.holder as? ContractMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) {
            return
        }

        when (holder.id) {
            "main" -> handleMainMenuClick(player, event.rawSlot)
            "templates" -> handleTemplatesMenuClick(player, holder, event.rawSlot)
            "admin" -> handleAdminMenuClick(player, event.rawSlot)
            "admin-settings" -> handleAdminSettingsClick(player, event.rawSlot)
            "pending" -> handlePendingMenuClick(player, holder, event.rawSlot)
            "propose-confirm" -> handleProposalConfirmClick(player, holder, event.rawSlot)
            "pending-detail" -> handlePendingDetailClick(player, holder, event.rawSlot)
            "contracts" -> handleContractsMenuClick(player, holder, event.rawSlot)
            "propose-target" -> handleProposeTargetClick(player, holder, event.rawSlot)
            "admin-target" -> handleAdminTargetClick(player, holder, event.rawSlot)
            "admin-bypass" -> handleAdminBypassClick(player, holder, event.rawSlot)
            "admin-pair-first" -> handleAdminPairFirstClick(player, holder, event.rawSlot)
            "admin-pair-second" -> handleAdminPairSecondClick(player, holder, event.rawSlot)
        }
    }

    @EventHandler
    fun onMenuDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player
        if (player != null && adminInputPrompts.containsKey(event.view.topInventory) && event.view is AnvilView) {
            event.isCancelled = true
            return
        }
        if (event.view.topInventory.holder is ContractMenuHolder) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onProposalPricePromptPrepare(event: PrepareAnvilEvent) {
        val promptState = adminInputPrompts[event.inventory] ?: return
        val player = event.view.player as? Player ?: return
        if (player.uniqueId != promptState.viewer) {
            return
        }

        event.view.setRepairCost(0)
        event.view.setMaximumRepairCost(1)
        event.view.setRepairItemCountCost(0)

        event.result = when (promptState.kind) {
            AdminInputPromptKind.PROPOSAL_PRICE -> {
                val price = parseProposalPrice(event.view.renameText)
                if (price != null) {
                    promptPaperItem(
                        "Set Price",
                        listOf(
                            "New value: ${economyHook.format(price.toDouble())}",
                            "Click to save"
                        ),
                        NamedTextColor.GREEN
                    )
                } else {
                    menuItem(
                        Material.BARRIER,
                        "Invalid Price",
                        listOf("Enter a number like 0 or 12.50"),
                        NamedTextColor.RED
                    )
                }
            }
            AdminInputPromptKind.MAX_PENDING_PROPOSALS -> {
                val limit = parsePendingProposalLimit(event.view.renameText)
                if (limit != null) {
                    promptPaperItem(
                        "Set Pending Limit",
                        listOf(
                            "New value: $limit",
                            "Click to save"
                        ),
                        NamedTextColor.GREEN
                    )
                } else {
                    menuItem(
                        Material.BARRIER,
                        "Invalid Limit",
                        listOf("Enter a whole number like 0 or 5"),
                        NamedTextColor.RED
                    )
                }
            }
        }
    }

    @EventHandler
    fun onProposalPricePromptClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        if (event.view !is AnvilView) {
            return
        }
        val promptState = adminInputPrompts.remove(event.inventory) ?: return
        if (promptState.viewer != player.uniqueId || promptState.completed) {
            return
        }
        clearPromptInventory(event.inventory)
        plugin.server.scheduler.runTask(plugin, Runnable { purgePromptItems(player) })
    }

    private fun handleMainMenuClick(player: Player, rawSlot: Int) {
        when (rawSlot) {
            10 -> openProposeTargetMenu(player)
            12 -> openTemplatesMenu(player)
            14 -> openPendingMenu(player)
            16 -> openContractsMenu(player)
            22 -> if (player.hasPermission(STAFF_PERMISSION)) openAdminMenu(player)
        }
    }

    private fun handleTemplatesMenuClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        when (rawSlot) {
            45 -> {
                openMainMenu(player)
                return
            }
            47 -> {
                if (holder.page > 0) {
                    openTemplatesMenu(player, holder.page - 1)
                }
                return
            }
            51 -> {
                openTemplatesMenu(player, holder.page + 1)
                return
            }
        }

        val templateId = holder.templateIdsBySlot[rawSlot] ?: return
        player.closeInventory()
        actions?.applyTemplate(player, templateId)
    }

    private fun handleAdminMenuClick(player: Player, rawSlot: Int) {
        if (!requireStaffAccess(player)) {
            return
        }
        when (rawSlot) {
            10 -> openAdminLookupMenu(player)
            13 -> openAdminSettingsMenu(player)
            16 -> openAdminBypassMenu(player)
            18 -> openMainMenu(player)
        }
    }

    private fun handleAdminSettingsClick(player: Player, rawSlot: Int) {
        if (!requireStaffAccess(player)) {
            return
        }
        when (rawSlot) {
            10 -> openProposalPricePrompt(player)
            12 -> openPendingProposalLimitPrompt(player)
            14 -> openAdminBypassMenu(player)
            16 -> {
                val enabled = !config.requireBookAndQuill
                plugin.setRequireBookAndQuill(enabled)
                player.sendMessage(Component.text("Template book requirement: ${if (enabled) "enabled" else "disabled"}.", NamedTextColor.GREEN))
                openAdminSettingsMenu(player)
            }
            18 -> openAdminMenu(player)
        }
    }

    private fun handleContractsMenuClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        val targetUuid = holder.targetUuid ?: player.uniqueId
        val primaryTarget = Bukkit.getOfflinePlayer(targetUuid)
        val secondaryTarget = holder.secondaryTargetUuid?.let { Bukkit.getOfflinePlayer(it) }
        val currentFilter = parseFilter(holder.filterName)
        when (rawSlot) {
            45 -> {
                if ((secondaryTarget != null || targetUuid != player.uniqueId) && player.hasPermission(STAFF_PERMISSION)) {
                    openAdminMenu(player)
                } else {
                    openMainMenu(player)
                }
                return
            }
            46 -> {
                if (holder.page > 0) {
                    if (secondaryTarget != null) {
                        openContractsMenu(player, primaryTarget, secondaryTarget, holder.page - 1, currentFilter.name)
                    } else {
                        openContractsMenu(player, primaryTarget, holder.page - 1, currentFilter.name)
                    }
                }
                return
            }
            47 -> return openContractsMenuForFilter(player, primaryTarget, secondaryTarget, ContractsFilter.ALL)
            48 -> return openContractsMenuForFilter(player, primaryTarget, secondaryTarget, ContractsFilter.PENDING)
            49 -> return openContractsMenuForFilter(player, primaryTarget, secondaryTarget, ContractsFilter.ACCEPTED)
            50 -> return openContractsMenuForFilter(player, primaryTarget, secondaryTarget, ContractsFilter.CLOSED)
            51 -> return openContractsMenuForFilter(player, primaryTarget, secondaryTarget, ContractsFilter.DENIED)
            52 -> {
                if (secondaryTarget != null) {
                    openContractsMenu(player, primaryTarget, secondaryTarget, holder.page + 1, currentFilter.name)
                } else {
                    openContractsMenu(player, primaryTarget, holder.page + 1, currentFilter.name)
                }
                return
            }
        }

        val contractId = holder.contractIdsBySlot[rawSlot] ?: return
        player.closeInventory()
        actions?.viewContract(player, contractId)
    }

    private fun handlePendingMenuClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
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
            51 -> {
                openPendingMenu(player, holder.page + 1)
                return
            }
        }

        val contractId = holder.contractIdsBySlot[rawSlot] ?: return
        openPendingDetailMenu(player, contractId, holder.page)
    }

    private fun handlePendingDetailClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        val contractId = holder.selectedContractId ?: run {
            openPendingMenu(player, holder.page)
            return
        }

        when (rawSlot) {
            36 -> openPendingMenu(player, holder.page)
            20 -> {
                player.closeInventory()
                actions?.viewContract(player, contractId)
            }
            22 -> {
                player.closeInventory()
                actions?.acceptContract(player, contractId)
            }
            24 -> {
                player.closeInventory()
                actions?.denyContract(player, contractId)
            }
        }
    }

    private fun handleProposalConfirmClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        val targetUuid = holder.targetUuid ?: run {
            openProposeTargetMenu(player)
            return
        }

        when (rawSlot) {
            18 -> openProposeTargetMenu(player)
            11 -> player.closeInventory()
            15 -> {
                player.closeInventory()
                actions?.confirmProposal(player, targetUuid)
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
            51 -> {
                openProposeTargetMenu(player, holder.page + 1)
                return
            }
        }

        val targetId = holder.playerIdsBySlot[rawSlot] ?: return
        player.closeInventory()
        actions?.proposeTo(player, targetId)
    }

    private fun handleAdminTargetClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        if (!requireStaffAccess(player)) {
            return
        }
        when (rawSlot) {
            45 -> {
                openAdminMenu(player)
                return
            }
            47 -> {
                if (holder.page > 0) {
                    openAdminLookupMenu(player, holder.page - 1)
                }
                return
            }
            49 -> {
                openAdminCompareFirstMenu(player)
                return
            }
            51 -> {
                openAdminLookupMenu(player, holder.page + 1)
                return
            }
        }

        val targetId = holder.playerIdsBySlot[rawSlot] ?: return
        player.closeInventory()
        actions?.openAdminSearch(player, targetId)
    }

    private fun handleAdminPairFirstClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        if (!requireStaffAccess(player)) {
            return
        }
        when (rawSlot) {
            45 -> {
                openAdminLookupMenu(player)
                return
            }
            47 -> {
                if (holder.page > 0) {
                    openAdminCompareFirstMenu(player, holder.page - 1)
                }
                return
            }
            51 -> {
                openAdminCompareFirstMenu(player, holder.page + 1)
                return
            }
        }

        val targetId = holder.playerIdsBySlot[rawSlot] ?: return
        val firstTarget = Bukkit.getOfflinePlayer(targetId)
        openAdminCompareSecondMenu(player, firstTarget)
    }

    private fun handleAdminPairSecondClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        if (!requireStaffAccess(player)) {
            return
        }
        val firstUuid = holder.targetUuid ?: run {
            openAdminCompareFirstMenu(player)
            return
        }
        val firstTarget = Bukkit.getOfflinePlayer(firstUuid)

        when (rawSlot) {
            45 -> {
                openAdminCompareFirstMenu(player)
                return
            }
            47 -> {
                if (holder.page > 0) {
                    openAdminCompareSecondMenu(player, firstTarget, holder.page - 1)
                }
                return
            }
            51 -> {
                openAdminCompareSecondMenu(player, firstTarget, holder.page + 1)
                return
            }
        }

        val secondTargetId = holder.playerIdsBySlot[rawSlot] ?: return
        player.closeInventory()
        actions?.openAdminSearch(player, firstUuid, secondTargetId)
    }

    private fun handleAdminBypassClick(player: Player, holder: ContractMenuHolder, rawSlot: Int) {
        if (!requireStaffAccess(player)) {
            return
        }
        when (rawSlot) {
            45 -> {
                openAdminSettingsMenu(player)
                return
            }
            47 -> {
                if (holder.page > 0) {
                    openAdminBypassMenu(player, holder.page - 1)
                }
                return
            }
            51 -> {
                openAdminBypassMenu(player, holder.page + 1)
                return
            }
        }

        val targetId = holder.playerIdsBySlot[rawSlot] ?: return
        val target = Bukkit.getOfflinePlayer(targetId)
        val targetName = target.name ?: targetId.toString()
        val enabled = !config.proposalRequirements.isBypassed(target.uniqueId)
        plugin.setRequirementsBypass(target, enabled)
        refreshCache(target.uniqueId)
        player.sendMessage(Component.text("Proposal requirements bypass for ${target.name ?: targetName}: ${if (enabled) "enabled" else "disabled"}.", NamedTextColor.GREEN))
        openAdminBypassMenu(player, holder.page)
    }

    private fun openProposalPricePrompt(player: Player) {
        if (!requireStaffAccess(player)) {
            return
        }

        val view = MenuType.ANVIL.create(player, Component.text("Set Proposal Price", NamedTextColor.GOLD))
        view.topInventory.setItem(0, proposalPriceInputItem(currentProposalPrice()))
        view.setRepairCost(0)
        view.setMaximumRepairCost(1)
        view.setRepairItemCountCost(0)
        adminInputPrompts[view.topInventory] = AdminInputPromptState(player.uniqueId, AdminInputPromptKind.PROPOSAL_PRICE)
        player.openInventory(view)
    }

    private fun openPendingProposalLimitPrompt(player: Player) {
        if (!requireStaffAccess(player)) {
            return
        }

        val view = MenuType.ANVIL.create(player, Component.text("Set Pending Limit", NamedTextColor.GOLD))
        view.topInventory.setItem(0, pendingProposalLimitInputItem(config.maxPendingProposals))
        view.setRepairCost(0)
        view.setMaximumRepairCost(1)
        view.setRepairItemCountCost(0)
        adminInputPrompts[view.topInventory] = AdminInputPromptState(player.uniqueId, AdminInputPromptKind.MAX_PENDING_PROPOSALS)
        player.openInventory(view)
    }

    private fun handleAdminInputPromptClick(player: Player, promptState: AdminInputPromptState, event: InventoryClickEvent) {
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory || event.rawSlot != 2) {
            return
        }
        if (player.uniqueId != promptState.viewer) {
            return
        }

        val view = event.view as? AnvilView ?: return
        promptState.completed = true
        adminInputPrompts.remove(view.topInventory)
        clearPromptInventory(view.topInventory)
        player.closeInventory()
        purgePromptItems(player)
        when (promptState.kind) {
            AdminInputPromptKind.PROPOSAL_PRICE -> {
                val price = parseProposalPrice(view.renameText) ?: run {
                    player.sendMessage(Component.text("Price must be a number greater than or equal to 0.", NamedTextColor.RED))
                    return
                }
                plugin.setProposalRequirementPrice(price)
                player.sendMessage(Component.text("Proposal price set to ${economyHook.format(price.toDouble())}.", NamedTextColor.GREEN))
            }
            AdminInputPromptKind.MAX_PENDING_PROPOSALS -> {
                val limit = parsePendingProposalLimit(view.renameText) ?: run {
                    player.sendMessage(Component.text("Pending limit must be a whole number greater than or equal to 0.", NamedTextColor.RED))
                    return
                }
                plugin.setMaxPendingProposals(limit)
                player.sendMessage(Component.text("Max pending proposals set to $limit.", NamedTextColor.GREEN))
            }
        }
        plugin.server.scheduler.runTask(plugin, Runnable { openAdminSettingsMenu(player) })
    }

    fun knownPlayerNamesSnapshot(): List<String> {
        ensurePlayerCacheSeeded()
        synchronized(playerNameCacheLock) {
            return knownPlayerNamesCache
        }
    }

    fun resolveKnownPlayer(name: String): OfflinePlayer? {
        Bukkit.getPlayerExact(name)?.let { return it }
        synchronized(playerNameCacheLock) {
            knownPlayersById.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key?.let { playerId ->
                return Bukkit.getPlayer(playerId) ?: Bukkit.getOfflinePlayer(playerId)
            }
        }
        return Bukkit.getOfflinePlayerIfCached(name)
    }

    private fun knownPlayerChoices(): List<PlayerChoice> {
        ensurePlayerCacheSeeded()
        synchronized(playerNameCacheLock) {
            return knownPlayersById.entries.asSequence()
                .filter { it.value.isNotBlank() }
                .map { PlayerChoice(it.key, it.value) }
                .sortedBy { it.name.lowercase() }
                .toList()
        }
    }

    private fun fillBorder(inv: Inventory) {
        val frame = menuItem(Material.BLACK_STAINED_GLASS_PANE, "", emptyList(), NamedTextColor.BLACK)
        val accent = menuItem(Material.GRAY_STAINED_GLASS_PANE, "", emptyList(), NamedTextColor.GRAY)
        val highlight = menuItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "", emptyList(), NamedTextColor.GRAY)
        val rows = inv.size / 9
        for (column in 0..8) {
            inv.setItem(column, frame)
            inv.setItem(inv.size - 9 + column, frame)
        }
        for (row in 1 until rows - 1) {
            inv.setItem(row * 9, frame)
            inv.setItem(row * 9 + 8, frame)
        }
        for (slot in listOf(2, 3, 5, 6)) {
            if (slot < inv.size) {
                inv.setItem(slot, accent)
            }
        }
        val footer = footerSlot(inv)
        for (slot in listOf(footer - 2, footer - 1, footer + 1, footer + 2)) {
            if (slot in 0 until inv.size) {
                inv.setItem(slot, highlight)
            }
        }
        inv.setItem(footer, footerItem())
    }

    private fun pendingContractSlots(): List<Int> {
        return listOf(
            10, 12, 14, 16,
            19, 21, 23, 25,
            28, 30, 32, 34,
            37, 39, 41, 43
        )
    }

    private fun pendingContractItem(contract: Contract): ItemStack {
        val title = contract.proposerName ?: "Unknown"
        val status = effectiveStatus(contract)
        val lore = mutableListOf(
            "Contract: ${contract.contractId ?: "Unknown"}",
            "Created: ${TimeUtil.format(contract.createdAt, config.zoneId)}",
            "Status: ${statusLabel(status)}",
            expiryLine(contract),
            "",
            "Click to review"
        ).filter { it.isNotBlank() }
        return playerHeadItem(
            contract.proposerUuid?.let(Bukkit::getOfflinePlayer),
            title,
            lore,
            statusColor(status)
        )
    }

    private fun browseContractItem(contract: Contract, targetUuid: UUID, secondaryTargetUuid: UUID? = null): ItemStack {
        val status = effectiveStatus(contract)
        val (title, headOwner, role) = when {
            secondaryTargetUuid != null -> Triple(
                contract.contractId ?: "Shared Contract",
                contract.proposerUuid?.let(Bukkit::getOfflinePlayer),
                "${contract.proposerName ?: "Unknown"} <-> ${contract.counterpartyName ?: "Unknown"}"
            )
            targetUuid == contract.proposerUuid -> Triple(
                contract.counterpartyName ?: "Unknown",
                contract.counterpartyUuid?.let(Bukkit::getOfflinePlayer),
                "Proposed to ${contract.counterpartyName ?: "Unknown"}"
            )
            targetUuid == contract.counterpartyUuid -> Triple(
                contract.proposerName ?: "Unknown",
                contract.proposerUuid?.let(Bukkit::getOfflinePlayer),
                "From ${contract.proposerName ?: "Unknown"}"
            )
            else -> Triple(
                contract.contractId ?: "Contract",
                contract.proposerUuid?.let(Bukkit::getOfflinePlayer),
                "${contract.proposerName ?: "Unknown"} <-> ${contract.counterpartyName ?: "Unknown"}"
            )
        }
        val lore = mutableListOf(
            "Contract: ${contract.contractId ?: "Unknown"}",
            role,
            "Created: ${TimeUtil.format(contract.createdAt, config.zoneId)}",
            "Status: ${statusLabel(status)}",
            expiryLine(contract),
            "",
            "Click to read terms"
        ).filter { it.isNotBlank() }
        return if (headOwner != null) {
            playerHeadItem(headOwner, title, lore, statusColor(status))
        } else {
            menuItem(Material.BOOK, title, lore, statusColor(status))
        }
    }

    private fun expiryLine(contract: Contract): String {
        val expiresAt = contract.expiresAt ?: return ""
        return "Expires in: ${TimeUtil.formatRemainingMillis(expiresAt - System.currentTimeMillis())}"
    }

    private fun pendingCountLine(player: Player): String {
        val pending = pendingContracts(player).size
        return if (pending == 0) "No pending offers" else "Pending: $pending"
    }

    private fun contractCountLine(player: Player): String {
        val total = store.countByParty(player.uniqueId)
        return if (total == 0) "No stored contracts yet" else "Stored: $total"
    }

    private fun templateCountLine(): String {
        val total = config.templates.size
        return if (total == 0) "No templates configured" else "Templates: $total"
    }

    private fun templateRequirementLine(player: Player): String {
        return when {
            playerBypassesRequirements(player) -> "Requirements bypassed for you"
            config.requireBookAndQuill -> "Templates require an empty book & quill in hand"
            else -> "Can supply a fresh book & quill"
        }
    }

    private fun proposalFeeLine(player: Player): String? {
        if (playerBypassesRequirements(player)) {
            return null
        }
        val fee = config.proposalRequirements.price
        if (fee <= 0.0) {
            return null
        }
        return "Price: ${economyHook.format(fee)} per proposal"
    }

    private fun playerBypassesRequirements(player: Player): Boolean {
        return config.proposalRequirements.isBypassed(player.uniqueId)
    }

    private fun isBypassed(playerId: UUID): Boolean {
        return config.proposalRequirements.isBypassed(playerId)
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

    private fun contractsForFilter(targetUuid: UUID, secondaryTargetUuid: UUID?, filter: ContractsFilter): List<Contract> {
        val now = System.currentTimeMillis()
        return store.getByParty(targetUuid).asSequence()
            .filter { contract -> secondaryTargetUuid == null || contract.isParty(secondaryTargetUuid) }
            .filter { contract ->
                val status = effectiveStatus(contract, now)
                when (filter) {
                    ContractsFilter.ALL -> true
                    ContractsFilter.PENDING -> status == ContractStatus.PROPOSED
                    ContractsFilter.ACCEPTED -> status == ContractStatus.SIGNED_BY_BOTH
                    ContractsFilter.CLOSED -> {
                        status == ContractStatus.SIGNED_BY_BOTH ||
                            status == ContractStatus.DENIED ||
                            status == ContractStatus.REVOKED ||
                            status == ContractStatus.EXPIRED
                    }
                    ContractsFilter.DENIED -> status == ContractStatus.DENIED
                }
            }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    private fun contractFilterItem(filter: ContractsFilter, active: ContractsFilter): ItemStack {
        val isActive = filter == active
        val material = when (filter) {
            ContractsFilter.ALL -> Material.BOOK
            ContractsFilter.PENDING -> Material.CLOCK
            ContractsFilter.ACCEPTED -> Material.LIME_DYE
            ContractsFilter.CLOSED -> Material.CHEST
            ContractsFilter.DENIED -> Material.RED_DYE
        }
        return menuItem(
            material,
            filterLabel(filter),
            listOf(if (isActive) "Current filter" else "Click to filter"),
            if (isActive) NamedTextColor.GREEN else NamedTextColor.AQUA
        )
    }

    private fun openContractsMenuForFilter(
        viewer: Player,
        primaryTarget: OfflinePlayer,
        secondaryTarget: OfflinePlayer?,
        filter: ContractsFilter
    ) {
        if (secondaryTarget != null) {
            openContractsMenu(viewer, primaryTarget, secondaryTarget, 0, filter.name)
        } else {
            openContractsMenu(viewer, primaryTarget, 0, filter.name)
        }
    }

    private fun filterLabel(filter: ContractsFilter): String {
        return when (filter) {
            ContractsFilter.ALL -> "All"
            ContractsFilter.PENDING -> "Pending"
            ContractsFilter.ACCEPTED -> "Accepted"
            ContractsFilter.CLOSED -> "Closed"
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

    private fun currentProposalPrice(): BigDecimal {
        return BigDecimal.valueOf(config.proposalRequirements.price).setScale(2, RoundingMode.HALF_UP)
    }

    private fun parseProposalPrice(raw: String?): BigDecimal? {
        val normalized = raw?.trim()
            ?.removePrefix("$")
            ?.replace(",", "")
            .orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return runCatching { BigDecimal(normalized) }.getOrNull()
            ?.takeIf { it >= BigDecimal.ZERO }
            ?.setScale(2, RoundingMode.HALF_UP)
    }

    private fun parsePendingProposalLimit(raw: String?): Int? {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return normalized.toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun proposalPriceInputItem(price: BigDecimal): ItemStack {
        return promptPaperItem(
            price.toPlainString(),
            listOf("Edit the value and click the output slot"),
            NamedTextColor.GOLD
        )
    }

    private fun pendingProposalLimitInputItem(limit: Int): ItemStack {
        return promptPaperItem(
            limit.toString(),
            listOf("Edit the value and click the output slot"),
            NamedTextColor.GOLD
        )
    }

    private fun clearPromptInventory(inventory: Inventory) {
        for (slot in 0..2) {
            inventory.setItem(slot, null)
        }
    }

    private fun purgePromptItems(player: Player) {
        val contents = player.inventory.contents
        var changed = false
        for (index in contents.indices) {
            if (isPromptPaper(contents[index])) {
                contents[index] = null
                changed = true
            }
        }
        if (changed) {
            player.inventory.contents = contents
        }
        if (isPromptPaper(player.itemOnCursor)) {
            player.setItemOnCursor(null)
        }
        player.updateInventory()
    }

    private fun isPromptPaper(item: ItemStack?): Boolean {
        val meta = item?.itemMeta ?: return false
        return meta.persistentDataContainer.has(promptPaperKey, PersistentDataType.BYTE)
    }

    private fun promptPaperItem(
        title: String,
        lore: List<String>,
        titleColor: NamedTextColor = NamedTextColor.AQUA
    ): ItemStack {
        val item = menuItem(Material.PAPER, title, lore, titleColor)
        val meta = item.itemMeta
        meta.persistentDataContainer.set(promptPaperKey, PersistentDataType.BYTE, 1.toByte())
        item.itemMeta = meta
        return item
    }

    private fun toggleLabel(enabled: Boolean): String = if (enabled) "On" else "Off"

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

    private fun playerHeadItem(
        owner: OfflinePlayer?,
        title: String,
        lore: List<String>,
        titleColor: NamedTextColor = NamedTextColor.AQUA
    ): ItemStack {
        if (owner == null) {
            return menuItem(Material.PAPER, title, lore, titleColor)
        }
        val item = ItemStack(Material.PLAYER_HEAD)
        val meta = item.itemMeta as SkullMeta
        meta.setOwningPlayer(owner)
        meta.displayName(Component.text(title, titleColor))
        if (lore.isNotEmpty()) {
            meta.lore(lore.map { Component.text(it, NamedTextColor.GRAY) })
        }
        item.itemMeta = meta
        return item
    }

    private fun footerItem(): ItemStack {
        return menuItem(Material.GRAY_STAINED_GLASS_PANE, " ", emptyList(), NamedTextColor.DARK_GRAY)
    }

    private fun footerSlot(inv: Inventory): Int = inv.size - 5

    private fun backSlot(inv: Inventory): Int = inv.size - 9

    private fun previousSlot(inv: Inventory): Int = inv.size - 7

    private fun nextSlot(inv: Inventory): Int = inv.size - 3

    fun refreshCache(playerId: UUID? = null) {
        ensurePlayerCacheSeeded()
        synchronized(playerNameCacheLock) {
            Bukkit.getOnlinePlayers().forEach { registerKnownPlayer(it.uniqueId, it.name) }
            playerId?.let { Bukkit.getPlayer(it)?.name?.let { playerName -> registerKnownPlayer(it, playerName) } }
            rebuildKnownPlayerNames()
        }
    }

    private fun requireStaffAccess(player: Player): Boolean {
        if (player.hasPermission(STAFF_PERMISSION)) {
            return true
        }
        player.sendMessage(Component.text("You do not have permission to use admin contract tools.", NamedTextColor.RED))
        openMainMenu(player)
        return false
    }

    private class ContractMenuHolder(
        val id: String,
        var page: Int = 0,
        var selectedContractId: String? = null,
        var filterName: String = ContractsFilter.ALL.name,
        var targetUuid: UUID? = null,
        var targetName: String? = null,
        var secondaryTargetUuid: UUID? = null,
        var secondaryTargetName: String? = null
    ) : InventoryHolder {
        private var inv: Inventory? = null
        val contractIdsBySlot: MutableMap<Int, String> = mutableMapOf()
        val playerIdsBySlot: MutableMap<Int, UUID> = mutableMapOf()
        val templateIdsBySlot: MutableMap<Int, String> = mutableMapOf()

        fun attach(inventory: Inventory) {
            this.inv = inventory
        }

        override fun getInventory(): Inventory {
            return inv ?: Bukkit.createInventory(null, 9)
        }
    }

    private class AdminInputPromptState(
        val viewer: UUID,
        val kind: AdminInputPromptKind,
        var completed: Boolean = false
    )

    private data class PlayerChoice(
        val uniqueId: UUID,
        val name: String
    )

    companion object {
        private const val STAFF_PERMISSION = "contractscrolls.staff"
    }

    private fun ensurePlayerCacheSeeded() {
        synchronized(playerNameCacheLock) {
            if (knownPlayersById.isNotEmpty()) {
                return
            }
            Bukkit.getOnlinePlayers().forEach { registerKnownPlayer(it.uniqueId, it.name) }
            store.getAll().forEach { contract ->
                registerKnownPlayer(contract.proposerUuid, contract.proposerName)
                registerKnownPlayer(contract.counterpartyUuid, contract.counterpartyName)
            }
            rebuildKnownPlayerNames()
        }
    }

    private fun registerKnownPlayer(playerId: UUID?, playerName: String?) {
        if (playerId == null || playerName.isNullOrBlank()) {
            return
        }
        knownPlayersById[playerId] = playerName
    }

    private fun rebuildKnownPlayerNames() {
        knownPlayerNamesCache = knownPlayersById.values.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }
}
