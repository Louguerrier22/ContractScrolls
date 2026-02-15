package me.l1cked.contractscrolls

import java.time.ZoneId
import java.util.Locale
import org.bukkit.configuration.file.FileConfiguration

class ContractScrollsConfig private constructor(
    val maxContractsPerPlayer: Int,
    val maxPendingProposals: Int,
    val proposalCooldownSeconds: Int,
    val contractExpiryHours: Int,
    val expirationSweepSeconds: Int,
    val allowThirdPartyWitness: Boolean,
    val receiptBookEnabled: Boolean,
    val stripColorCodes: Boolean,
    val zoneId: ZoneId,
    val storageMode: StorageMode,
    val mySql: MySqlConfig,
    val sqlite: SqliteConfig
) {
    enum class StorageMode {
        MYSQL,
        SQLITE
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
            val maxPendingProposals = config.getInt("maxPendingProposals", 5)
            val proposalCooldownSeconds = config.getInt("proposalCooldownSeconds", 60)
            val contractExpiryHours = config.getInt("contractExpiryHours", 0).coerceAtLeast(0)
            val expirationSweepSeconds = config.getInt("expirationSweepSeconds", 60).coerceAtLeast(15)
            val allowThirdPartyWitness = config.getBoolean("allowThirdPartyWitness", false)
            val receiptBookEnabled = config.getBoolean("receiptBookEnabled", true)
            val stripColorCodes = config.getBoolean("stripColorCodes", true)
            val timezoneDisplay = config.getString("timezoneDisplay", "SERVER")

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
                contractExpiryHours,
                expirationSweepSeconds,
                allowThirdPartyWitness,
                receiptBookEnabled,
                stripColorCodes,
                zoneId,
                storageMode,
                mySql,
                sqlite
            )
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
