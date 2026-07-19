package me.l1cked.contractscrolls.util

import me.l1cked.contractscrolls.ContractTemplate
import me.l1cked.contractscrolls.contract.Contract
import me.l1cked.contractscrolls.contract.ContractStatus
import me.l1cked.contractscrolls.contract.SignLocation
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.time.ZoneId
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.inventory.meta.WritableBookMeta

object BookUtil {
    const val MAX_BOOK_PAGES = 100
    const val MAX_TERMS_PAGES = MAX_BOOK_PAGES - 1
    const val MAX_PAGE_CHARACTERS = 1024
    private val plainTextSerializer: PlainTextComponentSerializer = PlainTextComponentSerializer.plainText()

    fun createReviewBook(contract: Contract, zoneId: ZoneId): ItemStack {
        return createWrittenBook(contract, zoneId, "Contract Review")
    }

    fun createTemplateBook(template: ContractTemplate): ItemStack {
        val book = ItemStack(Material.WRITABLE_BOOK)
        val meta = book.itemMeta as WritableBookMeta
        meta.displayName(Component.text(template.title))
        meta.setPages(sanitizePages(template.pages))
        book.itemMeta = meta
        return book
    }

    fun extractProposalPages(item: ItemStack): List<String>? {
        val pages = when (item.type) {
            Material.WRITABLE_BOOK -> (item.itemMeta as? WritableBookMeta)?.pages
            Material.WRITTEN_BOOK -> (item.itemMeta as? BookMeta)?.pages()?.map(plainTextSerializer::serialize)
            else -> null
        } ?: return null

        return sanitizePages(pages)
    }

    fun isEmptyWritableBook(item: ItemStack): Boolean {
        if (item.type != Material.WRITABLE_BOOK) {
            return false
        }
        val meta = item.itemMeta as? WritableBookMeta ?: return true
        return meta.pages.isEmpty() || meta.pages.all { it.isBlank() }
    }

    fun openWritableBookEditor(player: Player): Boolean {
        return try {
            val handle = player.javaClass.getMethod("getHandle").invoke(player)
            val craftItemStackClass = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack")
            val nmsItem = craftItemStackClass
                .getMethod("asNMSCopy", ItemStack::class.java)
                .invoke(null, player.inventory.itemInMainHand)
            val interactionHandClass = Class.forName("net.minecraft.world.InteractionHand")
            val mainHand = interactionHandClass.getField("MAIN_HAND").get(null)
            handle.javaClass
                .getMethod("openItemGui", nmsItem.javaClass, interactionHandClass)
                .invoke(handle, nmsItem, mainHand)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun sanitizePages(pages: List<String>): List<String> {
        return pages.take(MAX_TERMS_PAGES).map { it.take(MAX_PAGE_CHARACTERS).trimEnd() }
    }

    private fun createWrittenBook(contract: Contract, zoneId: ZoneId, title: String): ItemStack {
        val book = ItemStack(Material.WRITTEN_BOOK)
        val meta = book.itemMeta as BookMeta
        meta.title = title
        meta.author = "ContractScrolls"
        val pages = ArrayList(contract.pages.take(MAX_TERMS_PAGES))
        pages.add(buildSignaturePage(contract, zoneId))
        val componentPages = pages.map { Component.text(it) }
        meta.pages(componentPages)
        book.itemMeta = meta
        return book
    }

    private fun buildSignaturePage(contract: Contract, zoneId: ZoneId): String {
        val builder = StringBuilder()
        builder.append("Contract ID: ").append(contract.contractId ?: "Unknown").append("\n")
        builder.append("Status: ").append(statusLabel(contract.status)).append("\n")
        builder.append("Created: ").append(TimeUtil.format(contract.createdAt, zoneId)).append("\n\n")
        val expiresAt = contract.expiresAt
        if (expiresAt != null) {
            builder.append("Expires: ").append(TimeUtil.format(expiresAt, zoneId)).append("\n\n")
        }

        builder.append("Proposer: ").append(contract.proposerName ?: "Unknown").append("\n")
        val proposerSignedAt = contract.proposerSignedAt
        if (proposerSignedAt != null) {
            builder.append("Signed: ")
                .append(TimeUtil.format(proposerSignedAt, zoneId))
                .append("\n")
            appendLocation(builder, contract.proposerSignLocation)
        } else {
            builder.append("Signed: pending\n")
        }
        builder.append("\n")

        builder.append("Counterparty: ").append(contract.counterpartyName ?: "Unknown").append("\n")
        val counterpartySignedAt = contract.counterpartySignedAt
        if (counterpartySignedAt != null) {
            builder.append("Signed: ")
                .append(TimeUtil.format(counterpartySignedAt, zoneId))
                .append("\n")
            appendLocation(builder, contract.counterpartySignLocation)
        } else {
            builder.append("Signed: pending\n")
        }

        builder.append("\nHash: ").append(contract.hashPrefix())
        return builder.toString()
    }

    private fun appendLocation(builder: StringBuilder, location: SignLocation?) {
        if (location == null) {
            return
        }
        builder.append("Location: ")
            .append(location.world).append(" ")
            .append(String.format("%.1f", location.x)).append(" ")
            .append(String.format("%.1f", location.y)).append(" ")
            .append(String.format("%.1f", location.z))
            .append("\n")
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
}
