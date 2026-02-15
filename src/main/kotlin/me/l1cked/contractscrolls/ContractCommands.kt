package me.l1cked.contractscrolls

import me.l1cked.contractscrolls.contract.Contract
import me.l1cked.contractscrolls.contract.ContractStatus
import me.l1cked.contractscrolls.contract.SignLocation
import me.l1cked.contractscrolls.storage.ContractStore
import me.l1cked.contractscrolls.util.BookUtil
import me.l1cked.contractscrolls.util.HashUtil
import me.l1cked.contractscrolls.util.IdUtil
import me.l1cked.contractscrolls.util.TextUtil
import me.l1cked.contractscrolls.util.TimeUtil
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.incendo.cloud.bukkit.CloudBukkitCapabilities
import org.incendo.cloud.description.CommandDescription
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.exception.ArgumentParseException
import org.incendo.cloud.exception.CommandExecutionException
import org.incendo.cloud.exception.InvalidCommandSenderException
import org.incendo.cloud.exception.InvalidSyntaxException
import org.incendo.cloud.exception.NoPermissionException
import org.incendo.cloud.exception.NoSuchCommandException
import org.incendo.cloud.paper.LegacyPaperCommandManager
import org.incendo.cloud.parser.standard.StringParser
import org.incendo.cloud.suggestion.SuggestionProvider

class ContractCommands(
    private val plugin: ContractScrollsPlugin,
    private val store: ContractStore,
    private var config: ContractScrollsConfig,
    private val menus: ContractMenus
) {
    private val proposalCooldowns = ConcurrentHashMap<UUID, Long>()
    private val plainSerializer = PlainTextComponentSerializer.plainText()

    fun register() {
        val manager = LegacyPaperCommandManager.createNative(
            plugin,
            ExecutionCoordinator.simpleCoordinator()
        )

        registerExceptionHandlers(manager)

        if (manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
            manager.registerBrigadier()
        }

        val root = manager.commandBuilder("contract")
            .permission("contractscrolls.use")
            .commandDescription(CommandDescription.commandDescription("Contract menu/help"))

        manager.command(root.handler { ctx ->
            val sender = ctx.sender()
            if (sender is Player) {
                menus.openMainMenu(sender)
            } else {
                sendHelp(sender)
            }
        })


        manager.command(root.literal("propose")
            .senderType(Player::class.java)
            .handler { ctx -> menus.openProposeTargetMenu(ctx.sender()) })

        manager.command(root.literal("propose")
            .senderType(Player::class.java)
            .required("player", StringParser.stringParser(), playerNameSuggestions())
            .handler { ctx -> handlePropose(ctx.sender(), ctx.get("player")) })

        manager.command(root.literal("sign")
            .senderType(Player::class.java)
            .required("contractId", StringParser.stringParser(), contractIdSuggestions())
            .handler { ctx -> handleSign(ctx.sender(), ctx.get("contractId")) })

        manager.command(root.literal("accept")
            .senderType(Player::class.java)
            .required("contractId", StringParser.stringParser(), incomingPendingContractIdSuggestions())
            .handler { ctx -> handleAccept(ctx.sender(), ctx.get("contractId")) })

        manager.command(root.literal("deny")
            .senderType(Player::class.java)
            .required("contractId", StringParser.stringParser(), incomingPendingContractIdSuggestions())
            .handler { ctx -> handleDeny(ctx.sender(), ctx.get("contractId")) })

        manager.command(root.literal("view")
            .senderType(Player::class.java)
            .handler { ctx -> menus.openContractsMenu(ctx.sender()) })

        manager.command(root.literal("view")
            .required("contractId", StringParser.stringParser(), contractIdSuggestions())
            .handler { ctx -> handleView(ctx.sender(), ctx.get("contractId")) })

        manager.command(root.literal("pending")
            .senderType(Player::class.java)
            .handler { ctx -> menus.openPendingMenu(ctx.sender()) })

        manager.command(root.literal("inbox")
            .senderType(Player::class.java)
            .handler { ctx -> menus.openPendingMenu(ctx.sender()) })

        manager.command(root.literal("list")
            .senderType(Player::class.java)
            .handler { ctx -> menus.openContractsMenu(ctx.sender()) })

        manager.command(root.literal("list")
            .senderType(Player::class.java)
            .permission("contractscrolls.staff")
            .required("player", StringParser.stringParser(), playerNameSuggestions(includeKnownParties = true))
            .handler { ctx -> handleList(ctx.sender(), ctx.get("player")) })

        manager.command(root.literal("revoke")
            .senderType(Player::class.java)
            .required("contractId", StringParser.stringParser(), ownProposedContractIdSuggestions())
            .handler { ctx -> handleRevoke(ctx.sender(), ctx.get("contractId")) })

        manager.command(root.literal("admin")
            .literal("search")
            .senderType(Player::class.java)
            .permission("contractscrolls.staff")
            .required("player", StringParser.stringParser(), playerNameSuggestions(includeKnownParties = true))
            .handler { ctx -> handleAdminSearch(ctx.sender(), ctx.get("player")) })

    }

    private fun registerExceptionHandlers(manager: LegacyPaperCommandManager<CommandSender>) {
        val controller = manager.exceptionController()

        controller.registerHandler(NoPermissionException::class.java) { ctx ->
            sendError(ctx.context().sender(), "You do not have permission to use that command.")
        }
        controller.registerHandler(NoSuchCommandException::class.java) { ctx ->
            sendError(ctx.context().sender(), "Unknown command. Use /contract for help.")
        }
        controller.registerHandler(InvalidSyntaxException::class.java) { ctx ->
            val sender = ctx.context().sender()
            val syntax = ctx.exception().correctSyntax()
            sendError(sender, "Invalid syntax. Usage: $syntax")
            sendSyntaxHints(sender, syntax)
        }
        controller.registerHandler(InvalidCommandSenderException::class.java) { ctx ->
            val required = ctx.exception().requiredSenderTypes()
            if (required.any { it == Player::class.java }) {
                sendError(ctx.context().sender(), "This command can only be used by players.")
            } else {
                sendError(ctx.context().sender(), ctx.exception().message ?: "Invalid command sender.")
            }
        }
        controller.registerHandler(ArgumentParseException::class.java) { ctx ->
            val throwable: Throwable? = ctx.exception().cause
            val cause = throwable?.message ?: "Invalid argument"
            sendError(ctx.context().sender(), "Invalid argument: $cause")
        }
        controller.registerHandler(CommandExecutionException::class.java) { ctx ->
            plugin.logger.log(Level.WARNING, "Exception executing command handler", ctx.exception().cause ?: ctx.exception())
            sendError(ctx.context().sender(), "An internal error occurred while executing this command.")
        }
        controller.registerHandler(Throwable::class.java) { ctx ->
            plugin.logger.log(Level.SEVERE, "Unhandled command exception", ctx.exception())
            sendError(ctx.context().sender(), "An internal error occurred while executing this command.")
        }
    }

    private fun sendSyntaxHints(sender: CommandSender, syntax: String) {
        val player = sender as? Player ?: return
        if (syntax.contains("<player>", ignoreCase = true)) {
            if (syntax.contains("admin search", ignoreCase = true) || syntax.contains("list", ignoreCase = true)) {
                if (player.hasPermission("contractscrolls.staff")) {
                    menus.openAdminLookupMenu(player)
                }
            } else {
                menus.openProposeTargetMenu(player)
            }
        } else if (syntax.contains("<contractId>", ignoreCase = true)) {
            menus.openContractsMenu(player)
        }
    }

    private fun sendOnlinePlayersHint(sender: CommandSender) {
        val player = sender as? Player
        if (player != null) {
            menus.openProposeTargetMenu(player)
        } else {
            sender.sendMessage(Component.text("Player target selection is UI-only. Use /contract propose <player>.", NamedTextColor.GRAY))
        }
    }

    private fun sendContractIdHints(sender: CommandSender) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(Component.text("Use /contract admin search <player> to open contracts UI.", NamedTextColor.GRAY))
            sender.sendMessage(Component.text("Example: /contract admin search Loumo", NamedTextColor.DARK_GRAY))
            return
        }
        menus.openContractsMenu(player)
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(Component.text("ContractScrolls", NamedTextColor.GOLD))
        sender.sendMessage(commandHelp("/contract", "Open contract menu/help."))
        sender.sendMessage(commandHelp("/contract propose <player>", "Propose a signed book."))
        sender.sendMessage(commandHelp("/contract pending", "Open pending contracts menu."))
        sender.sendMessage(commandHelp("/contract inbox", "Alias of /contract pending."))
        sender.sendMessage(commandHelp("/contract accept <contractId>", "Accept and sign a pending contract."))
        sender.sendMessage(commandHelp("/contract deny <contractId>", "Deny a pending contract sent to you."))
        sender.sendMessage(commandHelp("/contract sign <contractId>", "Sign a pending contract."))
        sender.sendMessage(commandHelp("/contract view", "Open your contracts browser UI."))
        sender.sendMessage(commandHelp("/contract view <contractId>", "Open a contract book directly."))
        sender.sendMessage(commandHelp("/contract list", "Alias of /contract view."))
        sender.sendMessage(commandHelp("/contract list <player>", "Open contracts UI for a player (staff)."))
        sender.sendMessage(commandHelp("/contract revoke <contractId>", "Revoke your proposal."))
        if (sender.hasPermission("contractscrolls.staff")) {
            sender.sendMessage(commandHelp("/contract admin search <player>", "Open player contracts UI (staff)."))
        }
    }

    private fun playerNameSuggestions(includeKnownParties: Boolean = false): SuggestionProvider<CommandSender> {
        return SuggestionProvider.blockingStrings { _, input ->
            val token = input.lastRemainingToken().trim().lowercase(Locale.ROOT)
            val names = linkedSetOf<String>()
            for (online in Bukkit.getOnlinePlayers()) {
                names += online.name
            }
            if (includeKnownParties) {
                for (contract in store.getAll()) {
                    val proposerName = contract.proposerName
                    if (!proposerName.isNullOrBlank()) {
                        names += proposerName
                    }
                    val counterpartyName = contract.counterpartyName
                    if (!counterpartyName.isNullOrBlank()) {
                        names += counterpartyName
                    }
                }
            }
            names.asSequence()
                .filter { token.isBlank() || it.lowercase(Locale.ROOT).startsWith(token) }
                .sorted()
                .take(50)
                .toList()
        }
    }

    private fun contractIdSuggestions(): SuggestionProvider<CommandSender> {
        return SuggestionProvider.blockingStrings { ctx, input ->
            val token = input.lastRemainingToken().trim().lowercase(Locale.ROOT)
            val sender = ctx.sender()
            val base = if (sender.hasPermission("contractscrolls.staff")) {
                store.getAllByCreatedDesc().asSequence()
            } else {
                val player = sender as? Player
                if (player == null) {
                    emptySequence()
                } else {
                    store.getByParty(player.uniqueId).asSequence()
                }
            }
            base
                .mapNotNull { it.contractId }
                .distinct()
                .filter { token.isBlank() || it.lowercase(Locale.ROOT).startsWith(token) }
                .take(50)
                .toList()
        }
    }

    private fun ownProposedContractIdSuggestions(): SuggestionProvider<CommandSender> {
        return SuggestionProvider.blockingStrings { ctx, input ->
            val sender = ctx.sender() as? Player ?: return@blockingStrings emptyList<String>()
            val token = input.lastRemainingToken().trim().lowercase(Locale.ROOT)
            store.getByProposer(sender.uniqueId).asSequence()
                .filter { it.proposerUuid == sender.uniqueId }
                .filter {
                    it.status != ContractStatus.REVOKED &&
                        it.status != ContractStatus.DENIED &&
                        it.status != ContractStatus.EXPIRED &&
                        !isExpired(it)
                }
                .sortedByDescending { it.createdAt }
                .mapNotNull { it.contractId }
                .distinct()
                .filter { token.isBlank() || it.lowercase(Locale.ROOT).startsWith(token) }
                .take(50)
                .toList()
        }
    }

    private fun incomingPendingContractIdSuggestions(): SuggestionProvider<CommandSender> {
        return SuggestionProvider.blockingStrings { ctx, input ->
            val sender = ctx.sender() as? Player ?: return@blockingStrings emptyList<String>()
            val token = input.lastRemainingToken().trim().lowercase(Locale.ROOT)
            store.getByCounterparty(sender.uniqueId).asSequence()
                .filter { it.counterpartyUuid == sender.uniqueId }
                .filter { it.counterpartySignedAt == null }
                .filter {
                    (it.status == ContractStatus.PROPOSED || it.status == ContractStatus.SIGNED_BY_ONE) && !isExpired(it)
                }
                .sortedByDescending { it.createdAt }
                .mapNotNull { it.contractId }
                .distinct()
                .filter { token.isBlank() || it.lowercase(Locale.ROOT).startsWith(token) }
                .take(50)
                .toList()
        }
    }

    private fun handlePropose(player: Player, targetName: String) {
        val now = System.currentTimeMillis()
        val cooldownMillis = config.proposalCooldownSeconds * 1000L
        val lastProposal = proposalCooldowns[player.uniqueId]
        if (lastProposal != null && now - lastProposal < cooldownMillis) {
            val remaining = (cooldownMillis - (now - lastProposal)) / 1000L
            sendError(player, "Please wait ${remaining}s before proposing another contract.")
            return
        }

        if (countContracts(player.uniqueId) >= config.maxContractsPerPlayer) {
            sendError(player, "You have reached the maximum number of stored contracts.")
            return
        }

        if (countPendingProposals(player.uniqueId) >= config.maxPendingProposals) {
            sendError(player, "You have too many pending proposals.")
            return
        }

        val inHand = player.inventory.itemInMainHand
        if (inHand.type != Material.WRITTEN_BOOK) {
            sendError(player, "Hold a signed book in your main hand to propose a contract.")
            return
        }
        val meta = inHand.itemMeta as? BookMeta
        if (meta == null) {
            sendError(player, "That book has no metadata.")
            return
        }

        val target = resolveOfflinePlayer(targetName)
        if (target == null) {
            sendError(player, "Player $targetName must be online or have joined before.")
            sendOnlinePlayersHint(player)
            return
        }
        if (target.uniqueId == player.uniqueId) {
            sendError(player, "You cannot propose a contract to yourself.")
            return
        }

        val componentPages = meta.pages()
        if (componentPages.isEmpty()) {
            sendError(player, "That book has no pages.")
            return
        }
        val plainPages = componentPages.map { plainSerializer.serialize(it) }
        val sanitized = sanitizePages(plainPages)
        if (sanitized.all { it.isBlank() }) {
            sendError(player, "That book has no usable terms.")
            return
        }
        if (sanitized.size > BookUtil.MAX_TERMS_PAGES) {
            sendError(player, "That book has too many pages. Maximum terms pages: ${BookUtil.MAX_TERMS_PAGES}.")
            return
        }

        val contract = Contract()
        contract.contractId = generateUniqueContractId()
        contract.proposerUuid = player.uniqueId
        contract.proposerName = player.name
        contract.counterpartyUuid = target.uniqueId
        contract.counterpartyName = target.name ?: targetName
        contract.createdAt = now
        contract.expiresAt = if (config.contractExpiryHours > 0) {
            now + (config.contractExpiryHours.toLong() * 3_600_000L)
        } else {
            null
        }
        contract.status = ContractStatus.PROPOSED
        contract.proposerSignedAt = now
        contract.proposerSignLocation = SignLocation(
            player.world.name,
            player.location.x,
            player.location.y,
            player.location.z
        )
        contract.pages = sanitized.toMutableList()
        contract.serverName = Bukkit.getServer().name
        contract.pluginVersion = plugin.pluginMeta.version
        contract.nonce = IdUtil.newNonce()
        contract.hash = HashUtil.computeContractHash(
            contract.proposerUuid,
            contract.counterpartyUuid,
            contract.pages,
            contract.createdAt,
            contract.nonce ?: ""
        )

        store.save(contract)
        val contractId = contract.contractId ?: "unknown"
        if (!store.exists(contract.contractId)) {
            sendError(player, "Failed to store contract $contractId. Try again and check server logs.")
            return
        }

        if (!consumeMainHandBook(player)) {
            plugin.logger.warning("Could not consume proposed contract book for ${player.name} on contract $contractId")
        }

        proposalCooldowns[player.uniqueId] = now
        menus.refreshCache(player.uniqueId)
        menus.refreshCache(target.uniqueId)

        sendInfo(player, "Proposed contract ${contract.contractId} to ${contract.counterpartyName}. Your signature is recorded.")
        val onlineTarget = target.player
        if (onlineTarget != null) {
            onlineTarget.sendMessage(Component.text("${player.name} has proposed contract ${contract.contractId}.", NamedTextColor.GOLD))
            onlineTarget.sendMessage(
                clickableAction("[View]", "/contract view ${contract.contractId}", NamedTextColor.YELLOW)
                    .append(Component.space())
                    .append(clickableAction("[Accept]", "/contract accept ${contract.contractId}", NamedTextColor.GREEN))
                    .append(Component.space())
                    .append(clickableAction("[Deny]", "/contract deny ${contract.contractId}", NamedTextColor.RED))
            )
        }
    }

    private fun handleSign(player: Player, contractId: String) {
        val contract = store.get(contractId)
        if (contract == null) {
            sendError(player, "Contract not found: $contractId.")
            sendContractIdHints(player)
            return
        }
        if (expireIfNeeded(contract)) {
            sendError(player, "This contract has expired and can no longer be signed.")
            return
        }
        if (!contract.isParty(player.uniqueId)) {
            sendError(player, "You are not a party to this contract.")
            return
        }
        if (
            contract.status == ContractStatus.REVOKED ||
            contract.status == ContractStatus.DRAFT ||
            contract.status == ContractStatus.DENIED ||
            contract.status == ContractStatus.EXPIRED
        ) {
            sendError(player, "This contract cannot be signed.")
            return
        }
        if (contract.status == ContractStatus.SIGNED_BY_BOTH) {
            sendError(player, "That contract is already fully signed.")
            return
        }
        val isProposer = player.uniqueId == contract.proposerUuid
        if (isProposer && contract.proposerSignedAt != null) {
            sendError(player, "You have already signed this contract.")
            return
        }
        if (!isProposer && contract.counterpartySignedAt != null) {
            sendError(player, "You have already signed this contract.")
            return
        }

        val now = System.currentTimeMillis()
        val location = SignLocation(
            player.world.name,
            player.location.x,
            player.location.y,
            player.location.z
        )
        if (isProposer) {
            contract.proposerSignedAt = now
            contract.proposerSignLocation = location
        } else {
            contract.counterpartySignedAt = now
            contract.counterpartySignLocation = location
        }

        if (contract.proposerSignedAt != null && contract.counterpartySignedAt != null) {
            contract.status = ContractStatus.SIGNED_BY_BOTH
            store.save(contract)
            menus.refreshCache(contract.proposerUuid)
            menus.refreshCache(contract.counterpartyUuid)
            sendInfo(player, "Contract fully signed: ${contract.contractId}.")
            notifyOtherParty(contract, player.uniqueId, "Contract ${contract.contractId} is now fully signed.")
        } else {
            contract.status = ContractStatus.SIGNED_BY_ONE
            store.save(contract)
            menus.refreshCache(contract.proposerUuid)
            menus.refreshCache(contract.counterpartyUuid)
            sendInfo(player, "Signature recorded for ${contract.contractId}.")
            notifyOtherParty(contract, player.uniqueId, "${player.name} signed contract ${contract.contractId}.")
        }
    }

    private fun handleAccept(player: Player, contractId: String) {
        val contract = store.get(contractId)
        if (contract == null) {
            sendError(player, "Contract not found: $contractId.")
            sendContractIdHints(player)
            return
        }
        if (expireIfNeeded(contract)) {
            sendError(player, "This contract has expired and can no longer be accepted.")
            return
        }
        if (player.uniqueId != contract.counterpartyUuid) {
            sendError(player, "Only the counterparty can accept this contract.")
            return
        }
        if (contract.counterpartySignedAt != null || contract.status == ContractStatus.SIGNED_BY_BOTH) {
            sendError(player, "That contract is already signed by you.")
            return
        }
        if (
            contract.status == ContractStatus.REVOKED ||
            contract.status == ContractStatus.DENIED ||
            contract.status == ContractStatus.DRAFT ||
            contract.status == ContractStatus.EXPIRED
        ) {
            sendError(player, "This contract cannot be accepted.")
            return
        }
        handleSign(player, contractId)
    }

    private fun handleDeny(player: Player, contractId: String) {
        val contract = store.get(contractId)
        if (contract == null) {
            sendError(player, "Contract not found: $contractId.")
            sendContractIdHints(player)
            return
        }
        if (expireIfNeeded(contract)) {
            sendError(player, "This contract has already expired.")
            return
        }
        if (player.uniqueId != contract.counterpartyUuid) {
            sendError(player, "Only the counterparty can deny this contract.")
            return
        }
        if (contract.counterpartySignedAt != null || contract.status == ContractStatus.SIGNED_BY_BOTH) {
            sendError(player, "You cannot deny a contract after signing it.")
            return
        }
        if (contract.status == ContractStatus.DENIED) {
            sendError(player, "That contract is already denied.")
            return
        }
        if (contract.status == ContractStatus.REVOKED || contract.status == ContractStatus.DRAFT || contract.status == ContractStatus.EXPIRED) {
            sendError(player, "This contract cannot be denied.")
            return
        }

        contract.status = ContractStatus.DENIED
        store.save(contract)
        menus.refreshCache(contract.proposerUuid)
        menus.refreshCache(contract.counterpartyUuid)
        sendInfo(player, "Denied contract ${contract.contractId}.")
        notifyOtherParty(contract, player.uniqueId, "${player.name} denied contract ${contract.contractId}.")
    }

    private fun handleView(sender: CommandSender, contractId: String) {
        val contract = store.get(contractId)
        if (contract == null) {
            sendError(sender, "Contract not found: $contractId.")
            sendContractIdHints(sender)
            return
        }
        if (!sender.hasPermission("contractscrolls.staff")) {
            val player = sender as? Player
            if (player == null) {
                sendError(sender, "Only players can view contracts.")
                return
            }
            if (!contract.isParty(player.uniqueId)) {
                sendError(sender, "You are not a party to this contract.")
                return
            }
        }

        if (!HashUtil.verify(contract) && sender.hasPermission("contractscrolls.staff")) {
            sender.sendMessage(
                Component.text("WARNING: Contract hash mismatch. Data may be tampered.", NamedTextColor.RED)
            )
        }

        if (sender is Player) {
            sender.openBook(BookUtil.createReviewBook(contract, config.zoneId))
        } else {
            sender.sendMessage("Contract ${contract.contractId} (${contract.status})")
            sender.sendMessage("Proposer: ${contract.proposerName} | Counterparty: ${contract.counterpartyName}")
            sender.sendMessage("Created: ${TimeUtil.format(contract.createdAt, config.zoneId)}")
            val expiresAt = contract.expiresAt
            if (expiresAt != null) {
                sender.sendMessage("Expires: ${TimeUtil.format(expiresAt, config.zoneId)}")
            }
        }
    }

    private fun handleList(sender: CommandSender, targetName: String?) {
        val player = sender as? Player
        if (player == null) {
            sendError(sender, "This command can only be used by players.")
            return
        }
        if (targetName == null) {
            menus.openContractsMenu(player)
            return
        }

        if (!player.hasPermission("contractscrolls.staff")) {
            sendError(sender, "You do not have permission to view other players' contracts.")
            return
        }
        val target = resolveOfflinePlayer(targetName) ?: run {
            sendError(sender, "Player $targetName must be online or have joined before.")
            return
        }
        menus.openContractsMenu(player, target)
    }

    private fun handleRevoke(player: Player, contractId: String) {
        val contract = store.get(contractId)
        if (contract == null) {
            sendError(player, "Contract not found: $contractId.")
            sendContractIdHints(player)
            return
        }
        if (expireIfNeeded(contract)) {
            sendError(player, "That contract is already expired.")
            return
        }
        if (player.uniqueId != contract.proposerUuid) {
            sendError(player, "Only the proposer can revoke this contract.")
            return
        }
        if (contract.status == ContractStatus.REVOKED) {
            sendError(player, "That contract is already revoked.")
            return
        }
        if (contract.status == ContractStatus.DENIED) {
            sendError(player, "That contract was already denied.")
            return
        }
        if (contract.status == ContractStatus.SIGNED_BY_BOTH) {
            sendError(player, "You cannot revoke a fully signed contract.")
            return
        }
        if (contract.status == ContractStatus.EXPIRED) {
            sendError(player, "That contract is already expired.")
            return
        }
        if (contract.counterpartySignedAt != null) {
            sendError(player, "You cannot revoke after the other party signs.")
            return
        }

        contract.status = ContractStatus.REVOKED
        store.save(contract)
        menus.refreshCache(contract.proposerUuid)
        menus.refreshCache(contract.counterpartyUuid)
        sendInfo(player, "Contract revoked: ${contract.contractId}.")
    }

    private fun handleAdminSearch(sender: CommandSender, playerName: String) {
        val player = sender as? Player
        if (player == null) {
            sendError(sender, "This command can only be used by players.")
            return
        }
        val target = resolveOfflinePlayer(playerName) ?: run {
            sendError(sender, "Player $playerName must be online or have joined before.")
            return
        }
        menus.openContractsMenu(player, target)
    }

    private fun resolveOfflinePlayer(name: String?): OfflinePlayer? {
        if (name.isNullOrBlank()) {
            return null
        }
        val online = Bukkit.getPlayerExact(name)
        if (online != null) {
            return online
        }
        return Bukkit.getOfflinePlayerIfCached(name)
    }

    private fun sanitizePages(pages: List<String>): List<String> {
        if (!config.stripColorCodes) {
            return pages
        }
        return pages.map { TextUtil.stripLegacyColors(it) }
    }

    private fun generateUniqueContractId(): String {
        repeat(25) {
            val id = IdUtil.newContractId(config.zoneId)
            if (!store.exists(id)) {
                return id
            }
        }
        return IdUtil.newContractId(config.zoneId)
    }

    private fun countContracts(uuid: UUID): Int {
        return store.countByParty(uuid)
    }

    private fun countPendingProposals(proposerUuid: UUID): Int {
        return store.getByProposer(proposerUuid)
            .asSequence()
            .filter { it.proposerUuid == proposerUuid }
            .filter {
                it.status == ContractStatus.PROPOSED || it.status == ContractStatus.SIGNED_BY_ONE
            }
            .filter { it.counterpartySignedAt == null }
            .filter { !isExpired(it) }
            .count()
    }

    private fun clickableAction(label: String, command: String, color: NamedTextColor): Component {
        return Component.text(label, color).clickEvent(ClickEvent.runCommand(command))
    }

    private fun isExpired(contract: Contract, now: Long = System.currentTimeMillis()): Boolean {
        val expiresAt = contract.expiresAt ?: return false
        return now >= expiresAt
    }

    private fun expireIfNeeded(contract: Contract, now: Long = System.currentTimeMillis()): Boolean {
        if (!isExpired(contract, now)) {
            return false
        }
        if (contract.status == ContractStatus.PROPOSED || contract.status == ContractStatus.SIGNED_BY_ONE) {
            contract.status = ContractStatus.EXPIRED
            store.save(contract)
            menus.refreshCache(contract.proposerUuid)
            menus.refreshCache(contract.counterpartyUuid)
        }
        return contract.status == ContractStatus.EXPIRED
    }

    private fun statusComponent(status: ContractStatus): Component {
        return Component.text(statusLabel(status), statusColor(status))
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

    private fun expirySummary(contract: Contract, now: Long = System.currentTimeMillis()): Component {
        val expiresAt = contract.expiresAt ?: return Component.empty()
        val remaining = expiresAt - now
        return Component.text(" | expires in ${TimeUtil.formatRemainingMillis(remaining)}", NamedTextColor.GRAY)
    }

    private fun notifyOtherParty(contract: Contract, actor: UUID, message: String) {
        val recipientUuid = when (actor) {
            contract.proposerUuid -> contract.counterpartyUuid
            contract.counterpartyUuid -> contract.proposerUuid
            else -> null
        } ?: return

        val recipient = Bukkit.getPlayer(recipientUuid) ?: return
        recipient.sendMessage(Component.text(message, NamedTextColor.YELLOW))
    }

    private fun consumeMainHandBook(player: Player): Boolean {
        val inHand = player.inventory.itemInMainHand
        if (inHand.type != Material.WRITTEN_BOOK) {
            return false
        }

        if (inHand.amount <= 1) {
            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
        } else {
            inHand.amount = inHand.amount - 1
            player.inventory.setItemInMainHand(inHand)
        }
        return true
    }

    private fun sendInfo(sender: CommandSender, message: String) {
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN))
    }

    private fun sendError(sender: CommandSender, message: String) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED))
    }

    private fun commandHelp(command: String, description: String): Component {
        val suggested = command.substringBefore(" <").substringBefore(" [")
        return Component.text(command, NamedTextColor.YELLOW)
            .clickEvent(ClickEvent.suggestCommand(suggested))
            .hoverEvent(HoverEvent.showText(Component.text("Click to suggest $suggested", NamedTextColor.GRAY)))
            .append(Component.text(" - $description", NamedTextColor.WHITE))
    }

}
