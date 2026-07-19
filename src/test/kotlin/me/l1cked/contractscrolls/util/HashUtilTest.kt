package me.l1cked.contractscrolls.util

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.l1cked.contractscrolls.contract.Contract

class HashUtilTest {
    @Test
    fun `verify succeeds for untouched contract`() {
        val proposer = UUID.randomUUID()
        val counterparty = UUID.randomUUID()
        val contract = Contract().apply {
            contractId = "TEST-001"
            proposerUuid = proposer
            counterpartyUuid = counterparty
            createdAt = 1_715_000_000_000
            nonce = "nonce"
            pages = mutableListOf("Page one", "Page two")
            hash = HashUtil.computeContractHash(proposer, counterparty, pages, createdAt, nonce ?: "")
        }

        assertTrue(HashUtil.verify(contract))
    }

    @Test
    fun `verify fails after terms change`() {
        val proposer = UUID.randomUUID()
        val counterparty = UUID.randomUUID()
        val contract = Contract().apply {
            contractId = "TEST-002"
            proposerUuid = proposer
            counterpartyUuid = counterparty
            createdAt = 1_715_000_000_000
            nonce = "nonce"
            pages = mutableListOf("Original terms")
            hash = HashUtil.computeContractHash(proposer, counterparty, pages, createdAt, nonce ?: "")
        }

        contract.pages[0] = "Changed terms"

        assertFalse(HashUtil.verify(contract))
    }
}
