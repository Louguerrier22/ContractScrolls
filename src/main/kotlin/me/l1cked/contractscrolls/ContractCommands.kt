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
import java.math.BigDecimal
import java.math.RoundingMode
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
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.incendo.cloud.bukkit.CloudBukkitCapabilities
import org.incendo.cloud.bukkit.BukkitCommandManager
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
    private val menus: ContractMenus,
    private val economyHook: VaultEconomyHook
) : ContractMenus.Actions {
    private val config: ContractScrollsConfig
        get() = plugin.currentConfig()

    private val proposalCooldowns = ConcurrentHashMap<UUID, Long>()
    private val pendingProposalCommits = ConcurrentHashMap.newKeySet<UUID>()

    fun register() {
        val manager = LegacyPaperCommandManager.createNative(
            plugin,
            ExecutionCoordinator.simpleCoordinator()
        )

        registerExceptionHandlers(manager)

        val canRegisterNativeBrigadier =
            manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER) &&
                manager.hasCapability(CloudBukkitCapabilities.BRIGADIER)

        if (canRegisterNativeBrigadier) {
            try {
                manager.registerBrigadier()
            } catch (ex: BukkitCommandManager.BrigadierInitializationException) {
                plugin.logger.log(
                    Level.WARNING,
                    "Brigadier registration failed; falling back to asynchronous completions if available.",
                    ex
                )
                registerAsyncCompletionsFallback(manager)
            }
        } else {
            registerAsyncCompletionsFallback(manager)
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

        manager.command(root.literal("help")
            .optional("topic", StringParser.greedyStringParser(), helpTopicSuggestions())
            .handler { ctx ->
                val topic = ctx.optional<String>("topic").orElse(null)
                sendHelp(ctx.sender(), topic)
            })


        manager.command(root.literal("propose")
            .senderType(Player::class.java)
            .handler { ctx -> menus.openProposeTargetMenu(ctx.sender()) })

        manager.command(root.literal("propose")
            .senderType(Player::class.java)
            .required("player", StringParser.stringParser(), playerNameSuggestions())
            .handler { ctx -> handlePropose(ctx.sender(), ctx.get<String>("player")) })

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

        manager.command(root.literal("contracts")
            .senderType(Player::class.java)
            .handler { ctx -> menus.openContractsMenu(ctx.sender()) })

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

        manager.command(root.literal("templates")
            .senderType(Player::class.java)
            .handler { ctx -> handleTemplatesList(ctx.sender()) })

        manager.command(root.literal("templates")
            .literal("apply")
            .senderType(Player::class.java)
            .required("templateId", StringParser.stringParser(), templateSuggestions())
            .handler { ctx -> handleTemplateApply(ctx.sender(), ctx.get("templateId")) })

        manager.command(root.literal("admin")
            .senderType(Player::class.java)
            .permission("contractscrolls.staff")
            .literal("search")
            .handler { ctx -> menus.openAdminLookupMenu(ctx.sender()) })

        manager.command(root.literal("admin")
            .senderType(Player::class.java)
            .permission("contractscrolls.staff")
            .literal("search")
            .required("player", StringParser.stringParser(), playerNameSuggestions(includeKnownParties = true))
            .optional("secondPlayer", StringParser.stringParser(), playerNameSuggestions(includeKnownParties = true))
            .handler { ctx ->
                val secondPlayer = ctx.optional<String>("secondPlayer").orElse(null)
                handleAdminSearch(ctx.sender(), ctx.get("player"), secondPlayer)
            })

        manager.command(root.literal("admin")
            .senderType(Player::class.java)
            .permission("contractscrolls.staff")
            .literal("compare")
            .handler { ctx -> menus.openAdminCompareFirstMenu(ctx.sender()) })

        manager.command(root.literal("admin")
            .senderType(Player::class.java)
            .permission("contractscrolls.staff")
            .literal("compare")
            .required("playerOne", StringParser.stringParser(), playerNameSuggestions(includeKnownParties = true))
            .required("playerTwo", StringParser.stringParser(), playerNameSuggestions(includeKnownParties = true))
            .handler { ctx -> handleAdminSearch(ctx.sender(), ctx.get<String>("playerOne"), ctx.get<String>("playerTwo")) })

        manager.command(root.literal("admin")
            .literal("menu")
            .senderType(Player::class.java)
            .permission("contractscrolls.staff")
            .handler { ctx -> menus.openAdminMenu(ctx.sender()) })

        manager.command(root.literal("admin")
            .literal("settings")
            .senderType(Player::class.java)
            .permission("contractscrolls.staff")
            .handler { ctx -> menus.openAdminSettingsMenu(ctx.sender()) })

        manager.command(root.literal("admin")
            .literal("settings")
            .permission("contractscrolls.staff")
            .literal("price")
            .required("desiredPrice", StringParser.stringParser())
            .handler { ctx -> handleAdminSettingsPrice(ctx.sender(), ctx.get("desiredPrice")) })

        manager.command(root.literal("admin")
            .literal("settings")
            .permission("contractscrolls.staff")
            .literal("pending")
            .required("desiredLimit", StringParser.stringParser())
            .handler { ctx -> handleAdminSettingsPending(ctx.sender(), ctx.get("desiredLimit")) })

        manager.command(root.literal("admin")
            .literal("settings")
            .permission("contractscrolls.staff")
            .literal("book")
            .required("enabled", StringParser.stringParser(), booleanWordSuggestions())
            .handler { ctx -> handleAdminSettingsBook(ctx.sender(), ctx.get("enabled")) })

        manager.command(root.literal("admin")
            .literal("bypass")
            .senderType(Player::class.java)
            .permission("contractscrolls.staff")
            .required("player", StringParser.stringParser(), playerNameSuggestions(includeKnownParties = true))
            .optional("enabled", StringParser.stringParser(), booleanWordSuggestions())
            .handler { ctx ->
                val enabled = ctx.optional<String>("enabled").orElse(null)
                handleAdminBypass(ctx.sender(), ctx.get("player"), enabled)
            })

    }

    override fun proposeTo(player: Player, targetId: UUID) {
        handlePropose(player, Bukkit.getOfflinePlayer(targetId))
    }

    override fun confirmProposal(player: Player, targetId: UUID) {
        handleProposeConfirm(player, Bukkit.getOfflinePlayer(targetId))
    }

    override fun applyTemplate(player: Player, templateId: String) {
        handleTemplateApply(player, templateId)
    }

    override fun viewContract(player: Player, contractId: String) {
        handleView(player, contractId)
    }

    override fun acceptContract(player: Player, contractId: String) {
        handleAccept(player, contractId)
    }

    override fun denyContract(player: Player, contractId: String) {
        handleDeny(player, contractId)
    }

    override fun openAdminSearch(player: Player, primaryTargetId: UUID, secondaryTargetId: UUID?) {
        val primaryTarget = Bukkit.getOfflinePlayer(primaryTargetId)
        val secondaryTarget = secondaryTargetId?.let(Bukkit::getOfflinePlayer)
        handleAdminSearch(player, primaryTarget, secondaryTarget)
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
        if (syntax.contains("admin ", ignoreCase = true) || syntax.contains(" list", ignoreCase = true)) {
            return
        }
        if (syntax.contains("<player>", ignoreCase = true) || syntax.contains("<playerone>", ignoreCase = true)) {
            menus.openProposeTargetMenu(player)
        } else if (syntax.contains("<contractId>", ignoreCase = true)) {
            menus.openContractsMenu(player)
        }
    }

    private fun registerAsyncCompletionsFallback(manager: LegacyPaperCommandManager<CommandSender>) {
        if (manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
            plugin.logger.info("Asynchronous command completions are disabled because ContractScrolls suggestions read Bukkit state.")
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

    private fun sendHelp(sender: CommandSender, rawTopic: String? = null) {
        val topic = rawTopic?.trim().orEmpty()
        if (topic.isNotBlank()) {
            val helpTopic = resolveHelpTopic(sender, topic)
            if (helpTopic == null) {
                sendError(sender, "Unknown help topic: $topic.")
                sender.sendMessage(Component.text("Use /contract help to view available commands.", NamedTextColor.GRAY))
                return
            }

            sender.sendMessage(Component.text(helpTopic.title, NamedTextColor.GOLD))
            sender.sendMessage(commandHelp(helpTopic.command, helpTopic.summary))
            helpTopic.details.firstOrNull()?.let { line ->
                sender.sendMessage(Component.text(line, NamedTextColor.GRAY))
            }
            return
        }

        sender.sendMessage(Component.text("ContractScrolls Help", NamedTextColor.GOLD))
        sender.sendMessage(Component.text("Use /contract help <command> for one command.", NamedTextColor.GRAY))
        helpTopicsFor(sender).forEach { topicInfo ->
            sender.sendMessage(commandHelp(topicInfo.command, topicInfo.summary))
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
                names += menus.knownPlayerNamesSnapshot()
            }
            names.asSequence()
                .filter { token.isBlank() || it.lowercase(Locale.ROOT).startsWith(token) }
                .sorted()
                .take(50)
                .toList()
        }
    }

    private fun templateSuggestions(): SuggestionProvider<CommandSender> {
        return SuggestionProvider.blockingStrings { _, input ->
            val token = input.lastRemainingToken().trim().lowercase(Locale.ROOT)
            config.templates.asSequence()
                .map { it.id }
                .filter { token.isBlank() || it.lowercase(Locale.ROOT).startsWith(token) }
                .take(50)
                .toList()
        }
    }

    private fun booleanWordSuggestions(): SuggestionProvider<CommandSender> {
        return SuggestionProvider.blockingStrings { _, input ->
            val token = input.lastRemainingToken().trim().lowercase(Locale.ROOT)
            sequenceOf("on", "off", "true", "false", "enabled", "disabled")
                .filter { token.isBlank() || it.startsWith(token) }
                .toList()
        }
    }

    private fun helpTopicSuggestions(): SuggestionProvider<CommandSender> {
        return SuggestionProvider.blockingStrings { ctx, input ->
            val token = input.remainingInput().trim().lowercase(Locale.ROOT)
            helpTopicsFor(ctx.sender()).asSequence()
                .map { canonicalHelpTopic(it) }
                .distinct()
                .filter { token.isBlank() || it.startsWith(token) }
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
        val target = resolveOfflinePlayer(targetName) ?: run {
            sendError(player, "Player $targetName must be online or have joined before.")
            sendOnlinePlayersHint(player)
            return
        }
        handlePropose(player, target)
    }

    private fun handlePropose(player: Player, target: OfflinePlayer) {
        val intent = resolveProposalIntent(player, target) ?: return
        menus.openProposalConfirmMenu(
            player = player,
            target = intent.target,
            proposalFee = intent.proposalFee,
            pageCount = intent.pages.size
        )
    }

    private fun handleProposeConfirm(player: Player, target: OfflinePlayer) {
        val intent = resolveProposalIntent(player, target) ?: return
        if (!pendingProposalCommits.add(player.uniqueId)) {
            sendError(player, "A contract proposal is already being stored. Try again in a moment.")
            return
        }
        val now = System.currentTimeMillis()
        val inHand = player.inventory.itemInMainHand
        val contract = Contract()
        contract.contractId = generateUniqueContractId()
        contract.proposerUuid = player.uniqueId
        contract.proposerName = player.name
        contract.counterpartyUuid = intent.target.uniqueId
        contract.counterpartyName = intent.target.name ?: intent.target.uniqueId.toString()
        contract.createdAt = now
        contract.expiresAt = if (config.contractExpiryHours > 0) {
            now + (config.contractExpiryHours.toLong() * 3_600_000L)
        } else {
            null
        }
        contract.status = ContractStatus.DRAFT
        contract.pages = intent.pages.toMutableList()
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

        val contractId = contract.contractId ?: "unknown"
        val consumedBook = inHand.asOne()
        persistContractUpdate(
            contract = contract,
            previous = null,
            onSuccess = {
                finalizePreparedProposal(player, intent, contract, consumedBook, now)
            },
            onFailure = {
                pendingProposalCommits.remove(player.uniqueId)
                sendError(player, "Failed to store contract $contractId. Try again and check server logs.")
            }
        )
    }

    private fun resolveProposalIntent(player: Player, target: OfflinePlayer): ProposalIntent? {
        val now = System.currentTimeMillis()
        val cooldownMillis = config.proposalCooldownSeconds * 1000L
        val lastProposal = proposalCooldowns[player.uniqueId]
        if (lastProposal != null && now - lastProposal < cooldownMillis) {
            val remaining = (cooldownMillis - (now - lastProposal)) / 1000L
            sendError(player, "Please wait ${remaining}s before proposing another contract.")
            return null
        }

        if (countContracts(player.uniqueId) >= config.maxContractsPerPlayer) {
            sendError(player, "You have reached the maximum number of stored contracts.")
            return null
        }
        if (countContracts(target.uniqueId) >= config.maxContractsPerPlayer) {
            sendError(player, "${target.name ?: "That player"} has reached the maximum number of stored contracts.")
            return null
        }

        if (countPendingProposals(player.uniqueId) >= config.maxPendingProposals) {
            sendError(player, "You have too many pending proposals.")
            return null
        }

        val inHand = player.inventory.itemInMainHand
        val proposalPages = BookUtil.extractProposalPages(inHand)
        if (proposalPages == null || (inHand.type != Material.WRITABLE_BOOK && inHand.type != Material.WRITTEN_BOOK)) {
            sendError(player, "Hold a writable or written book in your main hand to propose a contract.")
            return null
        }

        if (target.uniqueId == player.uniqueId) {
            sendError(player, "You cannot propose a contract to yourself.")
            return null
        }

        if (proposalPages.isEmpty()) {
            sendError(player, "That book has no pages.")
            return null
        }
        val sanitized = sanitizePages(proposalPages)
        if (sanitized.all { it.isBlank() }) {
            sendError(player, "That book has no usable terms.")
            return null
        }
        if (sanitized.size > BookUtil.MAX_TERMS_PAGES) {
            sendError(player, "That book has too many pages. Maximum terms pages: ${BookUtil.MAX_TERMS_PAGES}.")
            return null
        }

        val proposalFee = proposalFeeFor(player)
        if (proposalFee > 0.0) {
            if (!economyHook.isAvailable()) {
                sendError(player, "A proposal charge is configured, but Vault economy is unavailable.")
                return null
            }
            if (!economyHook.has(player, proposalFee)) {
                sendError(player, "You need ${economyHook.format(proposalFee)} to send this proposal.")
                return null
            }
        }

        return ProposalIntent(
            target = target,
            pages = sanitized,
            proposalFee = proposalFee
        )
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

        val previous = store.snapshot(contractId)
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
            persistContractUpdate(
                contract = contract,
                previous = previous,
                onSuccess = {
                    menus.refreshCache(contract.proposerUuid)
                    menus.refreshCache(contract.counterpartyUuid)
                    sendInfo(player, "Contract fully signed: ${contract.contractId}.")
                    notifyOtherParty(contract, player.uniqueId, "Contract ${contract.contractId} is now fully signed.")
                },
                onFailure = {
                    sendError(player, "Could not persist signature for ${contract.contractId}.")
                }
            )
        } else {
            contract.status = ContractStatus.SIGNED_BY_ONE
            persistContractUpdate(
                contract = contract,
                previous = previous,
                onSuccess = {
                    menus.refreshCache(contract.proposerUuid)
                    menus.refreshCache(contract.counterpartyUuid)
                    sendInfo(player, "Signature recorded for ${contract.contractId}.")
                    notifyOtherParty(contract, player.uniqueId, "${player.name} signed contract ${contract.contractId}.")
                },
                onFailure = {
                    sendError(player, "Could not persist signature for ${contract.contractId}.")
                }
            )
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

        val previous = store.snapshot(contractId)
        contract.status = ContractStatus.DENIED
        persistContractUpdate(
            contract = contract,
            previous = previous,
            onSuccess = {
                menus.refreshCache(contract.proposerUuid)
                menus.refreshCache(contract.counterpartyUuid)
                sendInfo(player, "Denied contract ${contract.contractId}.")
                notifyOtherParty(contract, player.uniqueId, "${player.name} denied contract ${contract.contractId}.")
            },
            onFailure = {
                sendError(player, "Could not persist denial for ${contract.contractId}.")
            }
        )
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

    private fun handleTemplatesList(player: Player) {
        if (config.templates.isEmpty()) {
            sendError(player, "No contract templates are configured.")
            return
        }

        menus.openTemplatesMenu(player)
    }

    private fun handleTemplateApply(player: Player, templateId: String) {
        val template = config.templates.firstOrNull { it.id.equals(templateId, ignoreCase = true) }
        if (template == null) {
            sendError(player, "Unknown template: $templateId.")
            return
        }

        if (prepareTemplateBook(player, template) == null) {
            return
        }

        player.updateInventory()
        player.closeInventory()
        val editorOpened = BookUtil.openWritableBookEditor(player)
        if (editorOpened) {
            sendInfo(player, "Loaded template '${template.title}' into your book and quill.")
        } else {
            sendInfo(player, "Loaded template '${template.title}' into your hand. Right-click the book and quill to edit it.")
        }
    }

    private fun prepareTemplateBook(player: Player, template: ContractTemplate): ItemStack? {
        val requiresHeldBook = playerRequiresBookAndQuill(player)
        val currentMainHand = player.inventory.itemInMainHand
        if (requiresHeldBook) {
            if (!BookUtil.isEmptyWritableBook(currentMainHand)) {
                sendError(player, "Hold an empty book and quill in your main hand to use templates.")
                return null
            }
            val updated = BookUtil.createTemplateBook(template)
            player.inventory.setItemInMainHand(updated)
            return updated
        }

        if (BookUtil.isEmptyWritableBook(currentMainHand)) {
            val updated = BookUtil.createTemplateBook(template)
            player.inventory.setItemInMainHand(updated)
            return updated
        }

        if (currentMainHand.type != Material.AIR) {
            val emptySlot = player.inventory.firstEmpty()
            if (emptySlot == -1) {
                sendError(player, "Free one inventory slot or hold an empty book and quill before loading a template.")
                return null
            }
            player.inventory.setItem(emptySlot, currentMainHand.clone())
        }

        val updated = BookUtil.createTemplateBook(template)
        player.inventory.setItemInMainHand(updated)
        return updated
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
        if (contract.status == ContractStatus.EXPIRED) {
            sendError(player, "That contract is already expired.")
            return
        }
        if (
            contract.status != ContractStatus.PROPOSED ||
            contract.proposerSignedAt == null ||
            contract.counterpartySignedAt != null
        ) {
            sendError(player, "You can only revoke a pending contract before it is accepted.")
            return
        }

        val previous = store.snapshot(contractId)
        contract.status = ContractStatus.REVOKED
        persistContractUpdate(
            contract = contract,
            previous = previous,
            onSuccess = {
                menus.refreshCache(contract.proposerUuid)
                menus.refreshCache(contract.counterpartyUuid)
                sendInfo(player, "Contract revoked: ${contract.contractId}.")
                notifyOtherParty(contract, player.uniqueId, "${player.name} revoked contract ${contract.contractId}.")
            },
            onFailure = {
                sendError(player, "Could not persist revocation for ${contract.contractId}.")
            }
        )
    }

    private fun handleAdminSearch(sender: CommandSender, playerName: String, secondPlayerName: String? = null) {
        val player = sender as? Player ?: run {
            sendError(sender, "This command can only be used by players.")
            return
        }
        val target = resolveOfflinePlayer(playerName) ?: run {
            sendError(sender, "Player $playerName must be online or have joined before.")
            return
        }

        if (secondPlayerName == null) {
            handleAdminSearch(player, target)
            return
        }

        val second = resolveOfflinePlayer(secondPlayerName) ?: run {
            sendError(sender, "Player $secondPlayerName must be online or have joined before.")
            return
        }
        handleAdminSearch(player, target, second)
    }

    private fun handleAdminSearch(player: Player, target: OfflinePlayer, second: OfflinePlayer? = null) {
        if (!player.hasPermission(STAFF_PERMISSION)) {
            sendError(player, "You do not have permission to view other players' contracts.")
            return
        }
        if (second == null) {
            menus.openContractsMenu(player, target)
            return
        }
        if (target.uniqueId == second.uniqueId) {
            sendError(player, "Choose two different players for a shared contract search.")
            return
        }
        menus.openContractsMenu(player, target, second)
    }

    private fun handleAdminBypass(sender: CommandSender, playerName: String, enabledValue: String?) {
        val target = resolveOfflinePlayer(playerName) ?: run {
            sendError(sender, "Player $playerName must be online or have joined before.")
            return
        }

        val current = config.proposalRequirements.isBypassed(target.uniqueId)
        val enabled = when (parseBooleanWord(enabledValue)) {
            null -> !current
            true -> true
            false -> false
        }

        plugin.setRequirementsBypass(target, enabled)
        sendInfo(sender, "Proposal requirements bypass for ${target.name ?: playerName}: ${if (enabled) "enabled" else "disabled"}.")
    }

    private fun handleAdminSettingsPrice(sender: CommandSender, rawPrice: String) {
        val normalized = parseProposalPrice(rawPrice) ?: run {
            sendError(sender, "Price must be a number greater than or equal to 0.")
            return
        }
        plugin.setProposalRequirementPrice(normalized)
        sendInfo(sender, "Proposal price set to ${economyHook.format(normalized.toDouble())}.")
    }

    private fun handleAdminSettingsPending(sender: CommandSender, rawLimit: String) {
        val normalized = parsePendingProposalLimit(rawLimit) ?: run {
            sendError(sender, "Pending limit must be a whole number greater than or equal to 0.")
            return
        }
        plugin.setMaxPendingProposals(normalized)
        sendInfo(sender, "Max pending proposals set to $normalized.")
    }

    private fun handleAdminSettingsBook(sender: CommandSender, enabledValue: String) {
        val enabled = parseBooleanWord(enabledValue)
        if (enabled == null) {
            sendError(sender, "Enabled must be on/off, true/false, or enabled/disabled.")
            return
        }
        plugin.setRequireBookAndQuill(enabled)
        sendInfo(sender, "Template book requirement: ${if (enabled) "enabled" else "disabled"}.")
    }

    private fun resolveOfflinePlayer(name: String?): OfflinePlayer? {
        if (name.isNullOrBlank()) {
            return null
        }
        return menus.resolveKnownPlayer(name)
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

    private fun configuredProposalFee(): Double = config.proposalRequirements.price.coerceAtLeast(0.0)

    private fun parseProposalPrice(raw: String): BigDecimal? {
        val normalized = raw.trim()
            .removePrefix("$")
            .replace(",", "")
        if (normalized.isBlank()) {
            return null
        }
        return runCatching { BigDecimal(normalized) }.getOrNull()
            ?.takeIf { it >= BigDecimal.ZERO }
            ?.setScale(2, RoundingMode.HALF_UP)
    }

    private fun parsePendingProposalLimit(raw: String): Int? {
        val normalized = raw.trim()
        if (normalized.isBlank()) {
            return null
        }
        return normalized.toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun parseBooleanWord(value: String?): Boolean? {
        return when (value?.trim()?.lowercase(Locale.ROOT)) {
            null -> null
            "on", "true", "enabled" -> true
            "off", "false", "disabled" -> false
            else -> null
        }
    }

    private fun proposalFeeFor(player: Player): Double {
        if (playerBypassesRequirements(player)) {
            return 0.0
        }
        return configuredProposalFee()
    }

    private fun playerBypassesRequirements(player: Player): Boolean {
        return config.proposalRequirements.isBypassed(player.uniqueId)
    }

    private fun playerRequiresBookAndQuill(player: Player): Boolean {
        if (playerBypassesRequirements(player)) {
            return false
        }
        return config.requireBookAndQuill
    }

    private fun finalizePreparedProposal(
        player: Player,
        intent: ProposalIntent,
        contract: Contract,
        consumedBook: ItemStack,
        preparedAt: Long
    ) {
        val contractId = contract.contractId ?: "unknown"
        if (!player.isOnline) {
            pendingProposalCommits.remove(player.uniqueId)
            revokePreparedProposal(contract, "Prepared contract $contractId was abandoned because ${player.name} disconnected before commit.")
            return
        }
        if (!currentProposalBookMatches(player, intent.pages)) {
            pendingProposalCommits.remove(player.uniqueId)
            revokePreparedProposal(contract, "Prepared contract $contractId was abandoned because ${player.name}'s proposal book changed before commit.")
            sendError(player, "Your proposal book changed before the contract was committed. No charge was taken.")
            return
        }

        if (intent.proposalFee > 0.0) {
            if (!economyHook.isAvailable()) {
                pendingProposalCommits.remove(player.uniqueId)
                revokePreparedProposal(contract, "Prepared contract $contractId was abandoned because Vault economy became unavailable before commit.")
                sendError(player, "Vault economy became unavailable before the proposal could be charged.")
                return
            }
            if (!economyHook.has(player, intent.proposalFee)) {
                pendingProposalCommits.remove(player.uniqueId)
                revokePreparedProposal(contract, "Prepared contract $contractId was abandoned because ${player.name} no longer had enough funds.")
                sendError(player, "You no longer have ${economyHook.format(intent.proposalFee)} to send this proposal.")
                return
            }
        }

        var feeWithdrawn = false
        if (intent.proposalFee > 0.0) {
            val response = economyHook.withdraw(player, intent.proposalFee)
            if (response == null || !response.transactionSuccess()) {
                pendingProposalCommits.remove(player.uniqueId)
                revokePreparedProposal(contract, "Prepared contract $contractId was abandoned because the proposal charge failed.")
                val reason = response?.errorMessage?.takeIf { it.isNotBlank() } ?: "Could not take the proposal charge."
                sendError(player, reason)
                return
            }
            feeWithdrawn = true
        }

        if (!consumeMainHandBook(player)) {
            pendingProposalCommits.remove(player.uniqueId)
            if (feeWithdrawn) {
                refundProposalFee(player, intent.proposalFee, contractId)
            }
            revokePreparedProposal(contract, "Prepared contract $contractId was abandoned because the proposal book could not be consumed.")
            sendError(player, "Your book could not be consumed. No contract was sent.")
            plugin.logger.warning("Could not consume proposed contract book for ${player.name} on contract $contractId")
            return
        }

        val previous = store.snapshot(contract.contractId)
        val committedAt = System.currentTimeMillis()
        contract.status = ContractStatus.PROPOSED
        contract.proposerSignedAt = committedAt
        contract.proposerSignLocation = SignLocation(
            player.world.name,
            player.location.x,
            player.location.y,
            player.location.z
        )
        persistContractUpdate(
            contract = contract,
            previous = previous,
            onSuccess = {
                pendingProposalCommits.remove(player.uniqueId)
                proposalCooldowns[player.uniqueId] = preparedAt
                menus.refreshCache(player.uniqueId)
                menus.refreshCache(intent.target.uniqueId)

                val feeSuffix = if (intent.proposalFee > 0.0) {
                    " Charged ${economyHook.format(intent.proposalFee)}."
                } else {
                    ""
                }
                sendInfo(player, "Proposed contract ${contract.contractId} to ${contract.counterpartyName}. Your signature is recorded.$feeSuffix")
                val onlineTarget = intent.target.player
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
            },
            onFailure = {
                pendingProposalCommits.remove(player.uniqueId)
                if (feeWithdrawn) {
                    refundProposalFee(player, intent.proposalFee, contractId)
                }
                restoreProposalBook(player, consumedBook)
                sendError(player, "Failed to commit contract $contractId. Your proposal was not sent.")
            }
        )
    }

    private fun currentProposalBookMatches(player: Player, expectedPages: List<String>): Boolean {
        val inHand = player.inventory.itemInMainHand
        if (inHand.type != Material.WRITABLE_BOOK && inHand.type != Material.WRITTEN_BOOK) {
            return false
        }
        val currentPages = BookUtil.extractProposalPages(inHand) ?: return false
        return sanitizePages(currentPages) == expectedPages
    }

    private fun revokePreparedProposal(contract: Contract, reason: String) {
        val previous = store.snapshot(contract.contractId)
        contract.status = ContractStatus.REVOKED
        persistContractUpdate(
            contract = contract,
            previous = previous,
            onSuccess = {
                menus.refreshCache(contract.proposerUuid)
                menus.refreshCache(contract.counterpartyUuid)
            },
            onFailure = {
                plugin.logger.warning("$reason Failed to persist the revoked prepared contract marker.")
            }
        )
    }

    private fun refundProposalFee(player: Player, proposalFee: Double, contractId: String) {
        if (proposalFee <= 0.0 || !economyHook.isAvailable()) {
            return
        }
        val response = economyHook.deposit(player, proposalFee)
        if (response == null || !response.transactionSuccess()) {
            plugin.logger.warning(
                "Failed to refund proposal fee ${economyHook.format(proposalFee)} to ${player.name} after contract save failure for $contractId."
            )
        }
    }

    private fun restoreProposalBook(player: Player, book: ItemStack) {
        val leftovers = player.inventory.addItem(book)
        if (leftovers.isNotEmpty()) {
            leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
    }

    private fun persistContractUpdate(
        contract: Contract,
        previous: Contract?,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        store.save(contract).whenComplete { persisted, throwable ->
            runOnMainThread {
                if (throwable != null) {
                    plugin.logger.log(Level.SEVERE, "Unhandled async contract save failure for ${contract.contractId}.", throwable)
                }
                if (persisted == true && throwable == null) {
                    onSuccess()
                } else {
                    store.revertIfCurrentRevision(contract.contractId, previous, contract.revision)
                    onFailure()
                }
            }
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        plugin.server.scheduler.runTask(plugin, Runnable(action))
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
            val previous = store.snapshot(contract.contractId)
            contract.status = ContractStatus.EXPIRED
            persistContractUpdate(
                contract = contract,
                previous = previous,
                onSuccess = {
                    menus.refreshCache(contract.proposerUuid)
                    menus.refreshCache(contract.counterpartyUuid)
                },
                onFailure = {
                    plugin.logger.warning("Failed to persist expired contract ${contract.contractId}.")
                }
            )
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
        if (inHand.type != Material.WRITTEN_BOOK && inHand.type != Material.WRITABLE_BOOK) {
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

    private fun helpTopicsFor(sender: CommandSender): List<HelpTopic> {
        return buildList {
            add(
                HelpTopic(
                    command = "/contract propose <player>",
                    title = "Propose Command",
                    summary = "Propose a contract to a player.",
                    details = listOf(
                        "Uses the book in your main hand and opens a confirmation screen."
                    ),
                    aliases = setOf("propose", "send")
                )
            )
            add(
                HelpTopic(
                    command = "/contract templates",
                    title = "Templates Command",
                    summary = "Open the templates menu.",
                    details = listOf(
                        "Apply a saved draft."
                    ),
                    aliases = setOf("templates", "drafts")
                )
            )
            add(
                HelpTopic(
                    command = "/contract templates apply <templateId>",
                    title = "Template Apply Command",
                    summary = "Apply one template to your book.",
                    details = listOf(
                        "Same action as clicking it in the templates menu."
                    ),
                    aliases = setOf("templates apply", "apply template", "template apply")
                )
            )
            add(
                HelpTopic(
                    command = "/contract pending",
                    title = "Pending Command",
                    summary = "Open your pending contracts.",
                    details = listOf(
                        "Review, accept, or deny contracts here."
                    ),
                    aliases = setOf("pending", "inbox")
                )
            )
            add(
                HelpTopic(
                    command = "/contract sign <contractId>",
                    title = "Sign Command",
                    summary = "Sign a contract.",
                    details = listOf(
                        "Use the contract ID."
                    ),
                    aliases = setOf("sign")
                )
            )
            add(
                HelpTopic(
                    command = "/contract accept <contractId>",
                    title = "Accept Command",
                    summary = "Accept a pending contract.",
                    details = listOf(
                        "Signs it at the same time."
                    ),
                    aliases = setOf("accept")
                )
            )
            add(
                HelpTopic(
                    command = "/contract deny <contractId>",
                    title = "Deny Command",
                    summary = "Deny a pending contract.",
                    details = listOf(
                        "Use the contract ID."
                    ),
                    aliases = setOf("deny", "decline")
                )
            )
            add(
                HelpTopic(
                    command = "/contract contracts",
                    title = "Contracts Command",
                    summary = "Open your contracts menu.",
                    details = listOf(
                        "Use the status buttons to filter the list."
                    ),
                    aliases = setOf("contracts", "view", "list")
                )
            )
            add(
                HelpTopic(
                    command = "/contract view <contractId>",
                    title = "View Command",
                    summary = "Open one contract by ID.",
                    details = listOf(
                        "Players can only view their own contracts."
                    ),
                    aliases = setOf("view <contractId>", "contract view")
                )
            )
            add(
                HelpTopic(
                    command = "/contract revoke <contractId>",
                    title = "Revoke Command",
                    summary = "Cancel your own proposal.",
                    details = listOf(
                        "Works before the other player signs."
                    ),
                    aliases = setOf("revoke", "cancel")
                )
            )
            if (sender.hasPermission(STAFF_PERMISSION)) {
                add(
                    HelpTopic(
                        command = "/contract admin search <player> [playerTwo]",
                        title = "Admin Search Command",
                        summary = "Open contracts for one player or a pair.",
                        details = listOf(
                            "Leave the second player blank to inspect one player."
                        ),
                        aliases = setOf("admin search", "search")
                    )
                )
                add(
                    HelpTopic(
                        command = "/contract admin settings price <amount>",
                        title = "Admin Price Command",
                        summary = "Set the proposal price.",
                        details = listOf(
                            "Use /contract admin settings to open the UI instead."
                        ),
                        aliases = setOf("admin settings price", "settings price")
                    )
                )
                add(
                    HelpTopic(
                        command = "/contract admin settings pending <amount>",
                        title = "Admin Pending Limit Command",
                        summary = "Set the max pending proposals per proposer.",
                        details = listOf(
                            "Use /contract admin settings to open the UI instead."
                        ),
                        aliases = setOf("admin settings pending", "settings pending")
                    )
                )
                add(
                    HelpTopic(
                        command = "/contract admin settings book <on|off>",
                        title = "Admin Book Command",
                        summary = "Set the template book requirement.",
                        details = listOf(
                            "Use /contract admin settings to open the UI instead."
                        ),
                        aliases = setOf("admin settings book", "settings book")
                    )
                )
                add(
                    HelpTopic(
                        command = "/contract admin bypass <player> [on|off]",
                        title = "Admin Bypass Command",
                        summary = "Toggle or set requirement bypass for one player.",
                        details = listOf(
                            "With no toggle value, the current bypass state flips."
                        ),
                        aliases = setOf("admin bypass", "bypass")
                    )
                )
            }
        }
    }

    private fun resolveHelpTopic(sender: CommandSender, rawTopic: String): HelpTopic? {
        val normalized = normalizeHelpTopic(rawTopic)
        return helpTopicsFor(sender).firstOrNull { topic ->
            normalizeHelpTopic(topic.command.removePrefix("/contract ")) == normalized ||
                topic.aliases.any { normalizeHelpTopic(it) == normalized }
        }
    }

    private fun normalizeHelpTopic(value: String): String {
        return value.lowercase(Locale.ROOT)
            .removePrefix("/contract ")
            .removePrefix("contract ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun canonicalHelpTopic(topic: HelpTopic): String {
        return topic.command
            .removePrefix("/contract ")
            .substringBefore(" <")
            .substringBefore(" [")
            .lowercase(Locale.ROOT)
            .trim()
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

    companion object {
        private const val STAFF_PERMISSION = "contractscrolls.staff"
    }

    private data class HelpTopic(
        val command: String,
        val title: String,
        val summary: String,
        val details: List<String>,
        val aliases: Set<String> = emptySet()
    )

    private data class ProposalIntent(
        val target: OfflinePlayer,
        val pages: List<String>,
        val proposalFee: Double
    )
}
