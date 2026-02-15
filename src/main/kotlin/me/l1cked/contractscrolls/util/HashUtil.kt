package me.l1cked.contractscrolls.util

import me.l1cked.contractscrolls.contract.Contract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import java.util.UUID

object HashUtil {
    fun computeContractHash(
        proposerUuid: UUID?,
        counterpartyUuid: UUID?,
        pages: List<String>,
        createdAt: Long,
        nonce: String
    ): String {
        val builder = StringBuilder()
        builder.append(proposerUuid).append("|")
            .append(counterpartyUuid).append("|")
            .append(createdAt).append("|")
            .append(nonce).append("|")
        pages.forEachIndexed { index, page ->
            if (index > 0) {
                builder.append("\n")
            }
            builder.append(page)
        }
        return sha256Hex(builder.toString())
    }

    fun verify(contract: Contract): Boolean {
        val stored = contract.hash ?: return false
        val computed = computeContractHash(
            contract.proposerUuid,
            contract.counterpartyUuid,
            contract.pages,
            contract.createdAt,
            contract.nonce ?: ""
        )
        return stored.equals(computed, ignoreCase = true)
    }

    private fun sha256Hex(input: String): String {
        val digest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (ex: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 not available", ex)
        }
        val hashed = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(hashed)
    }
}
