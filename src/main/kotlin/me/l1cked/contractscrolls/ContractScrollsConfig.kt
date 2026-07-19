package me.l1cked.contractscrolls

import me.l1cked.contractscrolls.util.BookUtil
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import org.bukkit.configuration.file.FileConfiguration

class ContractScrollsConfig private constructor(
    val maxContractsPerPlayer: Int,
    val maxPendingProposals: Int,
    val proposalCooldownSeconds: Int,
    val proposalRequirements: ProposalRequirements,
    val contractExpiryHours: Int,
    val expirationSweepSeconds: Int,
    val stripColorCodes: Boolean,
    val zoneId: ZoneId,
    val templates: List<ContractTemplate>,
    val storageMode: StorageMode,
    val mySql: MySqlConfig,
    val sqlite: SqliteConfig
) {
    val proposalCreationFee: Double
        get() = proposalRequirements.price

    val requireBookAndQuill: Boolean
        get() = proposalRequirements.requireBookAndQuill

    enum class StorageMode {
        MYSQL,
        SQLITE
    }

    class ProposalRequirements(
        val price: Double,
        val requireBookAndQuill: Boolean,
        val bypassPlayers: Set<UUID>
    ) {
        fun isBypassed(playerId: UUID): Boolean = bypassPlayers.contains(playerId)
    }

    class MySqlConfig(
        val host: String,
        val port: Int,
        val database: String,
        val username: String,
        val password: String,
        val useSsl: Boolean,
        val table: String
    )

    class SqliteConfig(
        val file: String
    )

    companion object {
        fun from(config: FileConfiguration): ContractScrollsConfig {
            val maxContractsPerPlayer = config.getInt("maxContractsPerPlayer", 200)
            val maxPendingProposals = config.getInt("maxPendingProposals", 5).coerceAtLeast(0)
            val proposalCooldownSeconds = config.getInt("proposalCooldownSeconds", 60)
            val legacyProposalFee = config.getDouble("proposalCreationFee", 0.0).coerceAtLeast(0.0)
            val proposalRequirements = ProposalRequirements(
                price = config.getDouble("proposalRequirements.price", legacyProposalFee).coerceAtLeast(0.0),
                requireBookAndQuill = config.getBoolean("proposalRequirements.requireBookAndQuill", false),
                bypassPlayers = config.getStringList("proposalRequirements.bypassPlayers")
                    .mapNotNull { raw ->
                        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
                    }
                    .toSet()
            )
            val contractExpiryHours = config.getInt("contractExpiryHours", 0).coerceAtLeast(0)
            val expirationSweepSeconds = config.getInt("expirationSweepSeconds", 60).coerceAtLeast(15)
            val stripColorCodes = config.getBoolean("stripColorCodes", true)
            val timezoneDisplay = config.getString("timezoneDisplay", "SERVER")
            val templates = parseTemplates(config)

            val storageMode = parseStorageMode(config.getString("storage.mode", "SQLITE"))
            val mySql = MySqlConfig(
                host = config.getString("storage.mysql.host", "127.0.0.1") ?: "127.0.0.1",
                port = config.getInt("storage.mysql.port", 3306).coerceAtLeast(1),
                database = config.getString("storage.mysql.database", "contractscrolls") ?: "contractscrolls",
                username = config.getString("storage.mysql.username", "root") ?: "root",
                password = config.getString("storage.mysql.password", "") ?: "",
                useSsl = config.getBoolean("storage.mysql.useSsl", false),
                table = config.getString("storage.mysql.table", "contracts") ?: "contracts"
            )
            val sqlite = SqliteConfig(
                file = config.getString("storage.sqlite.file", "contracts.db") ?: "contracts.db"
            )

            var zoneId = ZoneId.systemDefault()
            if (timezoneDisplay != null && !timezoneDisplay.equals("SERVER", ignoreCase = true)) {
                zoneId = try {
                    ZoneId.of(timezoneDisplay)
                } catch (ex: Exception) {
                    ZoneId.systemDefault()
                }
            }

            return ContractScrollsConfig(
                maxContractsPerPlayer,
                maxPendingProposals,
                proposalCooldownSeconds,
                proposalRequirements,
                contractExpiryHours,
                expirationSweepSeconds,
                stripColorCodes,
                zoneId,
                templates,
                storageMode,
                mySql,
                sqlite
            )
        }

        private fun parseTemplates(config: FileConfiguration): List<ContractTemplate> {
            val section = config.getConfigurationSection("templates") ?: return emptyList()
            return section.getKeys(false).asSequence()
                .mapNotNull { key ->
                    val base = "templates.$key"
                    val pages = config.getStringList("$base.pages")
                        .take(BookUtil.MAX_TERMS_PAGES)
                        .map { it.trimEnd() }
                        .filter { it.isNotBlank() }
                    if (pages.isEmpty()) {
                        return@mapNotNull null
                    }
                    ContractTemplate(
                        id = key,
                        title = config.getString("$base.title", key.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) })
                            ?: key,
                        description = config.getStringList("$base.description")
                            .map { it.trim() }
                            .filter { it.isNotBlank() },
                        pages = pages
                    )
                }
                .sortedBy { it.title.lowercase(Locale.ROOT) }
                .toList()
        }

        private fun parseStorageMode(value: String?): StorageMode {
            if (value.isNullOrBlank()) {
                return StorageMode.SQLITE
            }
            return try {
                StorageMode.valueOf(value.trim().uppercase(Locale.ROOT))
            } catch (_: IllegalArgumentException) {
                StorageMode.SQLITE
            }
        }
    }
}
