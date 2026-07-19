package me.l1cked.contractscrolls

data class ContractTemplate(
    val id: String,
    val title: String,
    val description: List<String>,
    val pages: List<String>
)
