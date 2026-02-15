package me.l1cked.contractscrolls.contract

import java.util.UUID

class Contract {
    var dataVersion: Int = DATA_VERSION
    var contractId: String? = null
    var proposerUuid: UUID? = null
    var proposerName: String? = null
    var counterpartyUuid: UUID? = null
    var counterpartyName: String? = null
    var createdAt: Long = 0L
    var proposerSignedAt: Long? = null
    var counterpartySignedAt: Long? = null
    var expiresAt: Long? = null
    var serverName: String? = null
    var pluginVersion: String? = null
    var pages: MutableList<String> = mutableListOf()
    var hash: String? = null
    var nonce: String? = null
    var status: ContractStatus = ContractStatus.DRAFT
    var proposerSignLocation: SignLocation? = null
    var counterpartySignLocation: SignLocation? = null

    fun isParty(uuid: UUID?): Boolean {
        if (uuid == null) {
            return false
        }
        return uuid == proposerUuid || uuid == counterpartyUuid
    }

    fun otherPartyName(uuid: UUID?): String {
        if (uuid == null) {
            return "Unknown"
        }
        return when (uuid) {
            proposerUuid -> counterpartyName ?: "Unknown"
            counterpartyUuid -> proposerName ?: "Unknown"
            else -> "Unknown"
        }
    }

    fun hashPrefix(): String {
        val value = hash ?: return ""
        return if (value.length < 6) value else value.substring(0, 6)
    }

    companion object {
        const val DATA_VERSION = 1
    }
}
