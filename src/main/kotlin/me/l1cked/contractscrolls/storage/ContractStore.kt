package me.l1cked.contractscrolls.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.l1cked.contractscrolls.ContractScrollsConfig
import me.l1cked.contractscrolls.contract.Contract
import me.l1cked.contractscrolls.contract.ContractStatus
import me.l1cked.contractscrolls.contract.SignLocation
import java.io.File
import java.nio.charset.StandardCharsets
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.logging.Level
import org.bukkit.plugin.java.JavaPlugin

class ContractStore(
    private val plugin: JavaPlugin,
    private val pluginConfig: ContractScrollsConfig
) {
    private enum class ActiveBackend {
        MYSQL,
        SQLITE
    }

    private val contracts: MutableMap<String, Contract> = linkedMapOf()
    private val partyIndex: MutableMap<UUID, MutableSet<String>> = hashMapOf()
    private val proposerIndex: MutableMap<UUID, MutableSet<String>> = hashMapOf()
    private val counterpartyIndex: MutableMap<UUID, MutableSet<String>> = hashMapOf()
    @Volatile
    private var snapshot: List<Contract> = emptyList()
    @Volatile
    private var snapshotByCreatedDesc: List<Contract> = emptyList()
    private val lock = Any()

    private val tableName: String = sanitizeTableName(pluginConfig.mySql.table)
    private var dataSource: HikariDataSource? = null
    private var mySqlInitialized: Boolean = false
    private var sqliteInitialized: Boolean = false
    @Volatile
    private var activeBackend: ActiveBackend = ActiveBackend.SQLITE

    fun loadAll() {
        clearInMemory()
        val initialized = when (pluginConfig.storageMode) {
            ContractScrollsConfig.StorageMode.MYSQL -> {
                if (loadAllFromMySql()) {
                    activeBackend = ActiveBackend.MYSQL
                    true
                } else if (loadAllFromSqlite()) {
                    plugin.logger.warning("MySQL unavailable; using SQLite fallback backend.")
                    activeBackend = ActiveBackend.SQLITE
                    true
                } else {
                    false
                }
            }

            ContractScrollsConfig.StorageMode.SQLITE -> {
                if (loadAllFromSqlite()) {
                    activeBackend = ActiveBackend.SQLITE
                    true
                } else {
                    false
                }
            }
        }
        if (!initialized) {
            throw IllegalStateException(
                "Could not initialize ContractScrolls SQL storage. " +
                    "Requested mode=${pluginConfig.storageMode}."
            )
        }
        synchronized(lock) {
            rebuildSnapshot()
        }
    }

    fun close() {
        resetSqlDataSource()
        activeBackend = ActiveBackend.SQLITE
    }

    fun get(contractId: String?): Contract? {
        if (contractId.isNullOrBlank()) {
            return null
        }
        synchronized(lock) {
            return contracts[normalize(contractId)]
        }
    }

    fun exists(contractId: String?): Boolean {
        return get(contractId) != null
    }

    fun save(contract: Contract) {
        if (contract.contractId.isNullOrBlank()) {
            return
        }
        val persisted = when (activeBackend) {
            ActiveBackend.MYSQL -> {
                if (saveToMySql(contract)) {
                    true
                } else if (saveToSqlite(contract)) {
                    plugin.logger.warning("MySQL save failed; switched to SQLite fallback backend.")
                    activeBackend = ActiveBackend.SQLITE
                    true
                } else {
                    plugin.logger.warning("MySQL save failed and SQLite fallback is unavailable.")
                    false
                }
            }

            ActiveBackend.SQLITE -> {
                if (saveToSqlite(contract)) {
                    true
                } else {
                    plugin.logger.warning("SQLite save failed.")
                    false
                }
            }
        }
        if (!persisted) {
            return
        }
        synchronized(lock) {
            putIndexed(contract)
            rebuildSnapshot()
        }
    }

    fun getAll(): List<Contract> = snapshot

    fun getAllByCreatedDesc(): List<Contract> = snapshotByCreatedDesc

    fun getByParty(uuid: UUID?): List<Contract> {
        if (uuid == null) {
            return emptyList()
        }
        synchronized(lock) {
            return contractsFromIndex(partyIndex[uuid]).toList()
        }
    }

    fun getByProposer(uuid: UUID?): List<Contract> {
        if (uuid == null) {
            return emptyList()
        }
        synchronized(lock) {
            return contractsFromIndex(proposerIndex[uuid]).toList()
        }
    }

    fun getByCounterparty(uuid: UUID?): List<Contract> {
        if (uuid == null) {
            return emptyList()
        }
        synchronized(lock) {
            return contractsFromIndex(counterpartyIndex[uuid]).toList()
        }
    }

    fun countByParty(uuid: UUID?): Int {
        if (uuid == null) {
            return 0
        }
        synchronized(lock) {
            return partyIndex[uuid]?.size ?: 0
        }
    }

    private fun clearInMemory() {
        synchronized(lock) {
            contracts.clear()
            partyIndex.clear()
            proposerIndex.clear()
            counterpartyIndex.clear()
            snapshot = emptyList()
            snapshotByCreatedDesc = emptyList()
        }
    }

    private fun loadAllFromMySql(): Boolean {
        if (!ensureMySqlReady()) {
            return false
        }
        return loadAllFromSqlBackend("MySQL")
    }

    private fun loadAllFromSqlite(): Boolean {
        if (!ensureSqliteReady()) {
            return false
        }
        return loadAllFromSqlBackend("SQLite")
    }

    private fun loadAllFromSqlBackend(backendName: String): Boolean {
        val query = """
            SELECT contract_id, data_version, status, created_at, expires_at, server_name, plugin_version,
                   proposer_uuid, proposer_name, counterparty_uuid, counterparty_name,
                   proposer_signed_at, counterparty_signed_at, terms_hash, terms_nonce, terms_pages,
                   proposer_world, proposer_x, proposer_y, proposer_z,
                   counterparty_world, counterparty_x, counterparty_y, counterparty_z
            FROM `$tableName`
        """.trimIndent()
        return try {
            dataSource!!.connection.use { connection ->
                connection.prepareStatement(query).use { statement ->
                    statement.executeQuery().use { result ->
                        while (result.next()) {
                            val contract = readContractFromResultSet(result)
                            if (!contract.contractId.isNullOrBlank()) {
                                synchronized(lock) {
                                    putIndexed(contract)
                                }
                            }
                        }
                    }
                }
            }
            true
        } catch (ex: SQLException) {
            plugin.logger.log(Level.WARNING, "Failed to load contracts from $backendName.", ex)
            false
        }
    }

    private fun saveToMySql(contract: Contract): Boolean {
        if (!ensureMySqlReady()) {
            return false
        }
        val sql = """
            INSERT INTO `$tableName` (
                contract_id, data_version, status, created_at, expires_at, server_name, plugin_version,
                proposer_uuid, proposer_name, counterparty_uuid, counterparty_name,
                proposer_signed_at, counterparty_signed_at, terms_hash, terms_nonce, terms_pages,
                proposer_world, proposer_x, proposer_y, proposer_z,
                counterparty_world, counterparty_x, counterparty_y, counterparty_z
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?
            )
            ON DUPLICATE KEY UPDATE
                data_version = VALUES(data_version),
                status = VALUES(status),
                created_at = VALUES(created_at),
                expires_at = VALUES(expires_at),
                server_name = VALUES(server_name),
                plugin_version = VALUES(plugin_version),
                proposer_uuid = VALUES(proposer_uuid),
                proposer_name = VALUES(proposer_name),
                counterparty_uuid = VALUES(counterparty_uuid),
                counterparty_name = VALUES(counterparty_name),
                proposer_signed_at = VALUES(proposer_signed_at),
                counterparty_signed_at = VALUES(counterparty_signed_at),
                terms_hash = VALUES(terms_hash),
                terms_nonce = VALUES(terms_nonce),
                terms_pages = VALUES(terms_pages),
                proposer_world = VALUES(proposer_world),
                proposer_x = VALUES(proposer_x),
                proposer_y = VALUES(proposer_y),
                proposer_z = VALUES(proposer_z),
                counterparty_world = VALUES(counterparty_world),
                counterparty_x = VALUES(counterparty_x),
                counterparty_y = VALUES(counterparty_y),
                counterparty_z = VALUES(counterparty_z)
        """.trimIndent()

        return saveToSql(contract, sql, "MySQL")
    }

    private fun saveToSqlite(contract: Contract): Boolean {
        if (!ensureSqliteReady()) {
            return false
        }
        val sql = """
            INSERT INTO `$tableName` (
                contract_id, data_version, status, created_at, expires_at, server_name, plugin_version,
                proposer_uuid, proposer_name, counterparty_uuid, counterparty_name,
                proposer_signed_at, counterparty_signed_at, terms_hash, terms_nonce, terms_pages,
                proposer_world, proposer_x, proposer_y, proposer_z,
                counterparty_world, counterparty_x, counterparty_y, counterparty_z
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?
            )
            ON CONFLICT(contract_id) DO UPDATE SET
                data_version = excluded.data_version,
                status = excluded.status,
                created_at = excluded.created_at,
                expires_at = excluded.expires_at,
                server_name = excluded.server_name,
                plugin_version = excluded.plugin_version,
                proposer_uuid = excluded.proposer_uuid,
                proposer_name = excluded.proposer_name,
                counterparty_uuid = excluded.counterparty_uuid,
                counterparty_name = excluded.counterparty_name,
                proposer_signed_at = excluded.proposer_signed_at,
                counterparty_signed_at = excluded.counterparty_signed_at,
                terms_hash = excluded.terms_hash,
                terms_nonce = excluded.terms_nonce,
                terms_pages = excluded.terms_pages,
                proposer_world = excluded.proposer_world,
                proposer_x = excluded.proposer_x,
                proposer_y = excluded.proposer_y,
                proposer_z = excluded.proposer_z,
                counterparty_world = excluded.counterparty_world,
                counterparty_x = excluded.counterparty_x,
                counterparty_y = excluded.counterparty_y,
                counterparty_z = excluded.counterparty_z,
                updated_at = unixepoch()
        """.trimIndent()

        return saveToSql(contract, sql, "SQLite")
    }

    private fun saveToSql(contract: Contract, sql: String, backendName: String): Boolean {
        return try {
            dataSource!!.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    bindContract(statement, contract)
                    statement.executeUpdate()
                }
            }
            true
        } catch (ex: SQLException) {
            plugin.logger.log(Level.WARNING, "Failed to save contract ${contract.contractId} to $backendName.", ex)
            false
        }
    }

    private fun ensureMySqlReady(): Boolean {
        if (mySqlInitialized && dataSource != null) {
            return true
        }
        val cfg = pluginConfig.mySql
        return try {
            resetSqlDataSource()
            val hikari = HikariConfig().apply {
                jdbcUrl = buildJdbcUrl(cfg)
                username = cfg.username
                password = cfg.password
                driverClassName = "com.mysql.cj.jdbc.Driver"
                maximumPoolSize = 4
                minimumIdle = 1
                connectionTimeout = 10_000
                poolName = "ContractScrolls-MySQL"
                addDataSourceProperty("cachePrepStmts", "true")
                addDataSourceProperty("prepStmtCacheSize", "250")
                addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            }
            dataSource = HikariDataSource(hikari)
            createMySqlSchema()
            mySqlInitialized = true
            sqliteInitialized = false
            true
        } catch (ex: Exception) {
            plugin.logger.log(Level.WARNING, "Could not initialize MySQL storage.", ex)
            false
        }
    }

    private fun ensureSqliteReady(): Boolean {
        if (sqliteInitialized && dataSource != null) {
            return true
        }
        val sqliteFile = resolveSqliteFile(pluginConfig.sqlite.file)
        val parent = sqliteFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.logger.warning("Could not create SQLite directory: ${parent.absolutePath}")
            return false
        }

        return try {
            resetSqlDataSource()
            val hikari = HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${sqliteFile.absolutePath}"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 1
                minimumIdle = 1
                connectionTimeout = 10_000
                poolName = "ContractScrolls-SQLite"
            }
            dataSource = HikariDataSource(hikari)
            createSqliteSchema()
            sqliteInitialized = true
            mySqlInitialized = false
            true
        } catch (ex: Exception) {
            plugin.logger.log(Level.WARNING, "Could not initialize SQLite storage.", ex)
            false
        }
    }

    private fun resetSqlDataSource() {
        dataSource?.close()
        dataSource = null
        mySqlInitialized = false
        sqliteInitialized = false
    }

    private fun createMySqlSchema() {
        val createTable = """
            CREATE TABLE IF NOT EXISTS `$tableName` (
                contract_id VARCHAR(64) NOT NULL PRIMARY KEY,
                data_version INT NOT NULL,
                status VARCHAR(32) NOT NULL,
                created_at BIGINT NOT NULL,
                expires_at BIGINT NULL,
                server_name VARCHAR(128) NULL,
                plugin_version VARCHAR(64) NULL,
                proposer_uuid VARCHAR(36) NULL,
                proposer_name VARCHAR(32) NULL,
                counterparty_uuid VARCHAR(36) NULL,
                counterparty_name VARCHAR(32) NULL,
                proposer_signed_at BIGINT NULL,
                counterparty_signed_at BIGINT NULL,
                terms_hash VARCHAR(128) NULL,
                terms_nonce VARCHAR(128) NULL,
                terms_pages LONGTEXT NOT NULL,
                proposer_world VARCHAR(128) NULL,
                proposer_x DOUBLE NULL,
                proposer_y DOUBLE NULL,
                proposer_z DOUBLE NULL,
                counterparty_world VARCHAR(128) NULL,
                counterparty_x DOUBLE NULL,
                counterparty_y DOUBLE NULL,
                counterparty_z DOUBLE NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """.trimIndent()

        dataSource!!.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(createTable)
            }
        }
    }

    private fun createSqliteSchema() {
        val createTable = """
            CREATE TABLE IF NOT EXISTS `$tableName` (
                contract_id TEXT NOT NULL PRIMARY KEY,
                data_version INTEGER NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NULL,
                server_name TEXT NULL,
                plugin_version TEXT NULL,
                proposer_uuid TEXT NULL,
                proposer_name TEXT NULL,
                counterparty_uuid TEXT NULL,
                counterparty_name TEXT NULL,
                proposer_signed_at INTEGER NULL,
                counterparty_signed_at INTEGER NULL,
                terms_hash TEXT NULL,
                terms_nonce TEXT NULL,
                terms_pages TEXT NOT NULL,
                proposer_world TEXT NULL,
                proposer_x REAL NULL,
                proposer_y REAL NULL,
                proposer_z REAL NULL,
                counterparty_world TEXT NULL,
                counterparty_x REAL NULL,
                counterparty_y REAL NULL,
                counterparty_z REAL NULL,
                updated_at INTEGER NOT NULL DEFAULT (unixepoch())
            )
        """.trimIndent()

        dataSource!!.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(createTable)
            }
        }
    }

    private fun bindContract(statement: PreparedStatement, contract: Contract) {
        var idx = 1
        statement.setString(idx++, contract.contractId)
        statement.setInt(idx++, contract.dataVersion)
        statement.setString(idx++, contract.status.name)
        statement.setLong(idx++, contract.createdAt)
        statement.setNullableLong(idx++, contract.expiresAt)
        statement.setString(idx++, contract.serverName)
        statement.setString(idx++, contract.pluginVersion)
        statement.setString(idx++, contract.proposerUuid?.toString())
        statement.setString(idx++, contract.proposerName)
        statement.setString(idx++, contract.counterpartyUuid?.toString())
        statement.setString(idx++, contract.counterpartyName)
        statement.setNullableLong(idx++, contract.proposerSignedAt)
        statement.setNullableLong(idx++, contract.counterpartySignedAt)
        statement.setString(idx++, contract.hash)
        statement.setString(idx++, contract.nonce)
        statement.setString(idx++, encodePages(contract.pages))
        statement.setString(idx++, contract.proposerSignLocation?.world)
        statement.setNullableDouble(idx++, contract.proposerSignLocation?.x)
        statement.setNullableDouble(idx++, contract.proposerSignLocation?.y)
        statement.setNullableDouble(idx++, contract.proposerSignLocation?.z)
        statement.setString(idx++, contract.counterpartySignLocation?.world)
        statement.setNullableDouble(idx++, contract.counterpartySignLocation?.x)
        statement.setNullableDouble(idx++, contract.counterpartySignLocation?.y)
        statement.setNullableDouble(idx, contract.counterpartySignLocation?.z)
    }

    private fun readContractFromResultSet(result: ResultSet): Contract {
        val contract = Contract()
        contract.dataVersion = result.getInt("data_version")
        contract.contractId = result.getString("contract_id")
        contract.status = readStatus(result.getString("status"))
        contract.createdAt = result.getLong("created_at")
        contract.expiresAt = result.getNullableLong("expires_at")
        contract.serverName = result.getString("server_name")
        contract.pluginVersion = result.getString("plugin_version")
        contract.proposerUuid = readUuid(result.getString("proposer_uuid"))
        contract.proposerName = result.getString("proposer_name")
        contract.counterpartyUuid = readUuid(result.getString("counterparty_uuid"))
        contract.counterpartyName = result.getString("counterparty_name")
        contract.proposerSignedAt = result.getNullableLong("proposer_signed_at")
        contract.counterpartySignedAt = result.getNullableLong("counterparty_signed_at")
        contract.hash = result.getString("terms_hash")
        contract.nonce = result.getString("terms_nonce")
        contract.pages = decodePages(result.getString("terms_pages"))
        contract.proposerSignLocation = readLocationFromResult(result, "proposer")
        contract.counterpartySignLocation = readLocationFromResult(result, "counterparty")
        return contract
    }

    private fun readLocationFromResult(result: ResultSet, prefix: String): SignLocation? {
        val world = result.getString("${prefix}_world") ?: return null
        val x = result.getNullableDouble("${prefix}_x") ?: return null
        val y = result.getNullableDouble("${prefix}_y") ?: return null
        val z = result.getNullableDouble("${prefix}_z") ?: return null
        return SignLocation(world, x, y, z)
    }

    private fun encodePages(pages: List<String>): String {
        if (pages.isEmpty()) {
            return ""
        }
        val encoder = Base64.getEncoder()
        return pages.joinToString(PAGES_DELIMITER) {
            encoder.encodeToString(it.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun decodePages(encoded: String?): MutableList<String> {
        if (encoded.isNullOrBlank()) {
            return mutableListOf()
        }
        val decoder = Base64.getDecoder()
        return encoded.split(PAGES_DELIMITER)
            .mapNotNull { token ->
                try {
                    String(decoder.decode(token), StandardCharsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            .toMutableList()
    }

    private fun buildJdbcUrl(cfg: ContractScrollsConfig.MySqlConfig): String {
        val ssl = if (cfg.useSsl) "true" else "false"
        return "jdbc:mysql://${cfg.host}:${cfg.port}/${cfg.database}?useSSL=$ssl&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC"
    }

    private fun resolveSqliteFile(path: String): File {
        val configured = path.trim().ifBlank { "contracts.db" }
        val file = File(configured)
        return if (file.isAbsolute) file else File(plugin.dataFolder, configured)
    }

    private fun sanitizeTableName(raw: String): String {
        val cleaned = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "")
        return if (cleaned.isBlank()) "contracts" else cleaned
    }

    private fun readStatus(value: String?): ContractStatus {
        if (value.isNullOrBlank()) {
            return ContractStatus.DRAFT
        }
        return try {
            ContractStatus.valueOf(value.uppercase(Locale.ROOT))
        } catch (ex: IllegalArgumentException) {
            ContractStatus.DRAFT
        }
    }

    private fun readUuid(value: String?): UUID? {
        if (value.isNullOrBlank()) {
            return null
        }
        return try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun normalize(contractId: String): String = contractId.trim().uppercase(Locale.ROOT)

    private fun putIndexed(contract: Contract) {
        val rawId = contract.contractId ?: return
        val id = normalize(rawId)
        val previous = contracts.put(id, contract)
        if (previous != null) {
            unindexContract(id, previous)
        }
        indexContract(id, contract)
    }

    private fun contractsFromIndex(ids: Set<String>?): Sequence<Contract> {
        if (ids.isNullOrEmpty()) {
            return emptySequence()
        }
        return ids.asSequence().mapNotNull { contracts[it] }
    }

    private fun indexContract(id: String, contract: Contract) {
        contract.proposerUuid?.let { uuid ->
            partyIndex.computeIfAbsent(uuid) { linkedSetOf() }.add(id)
            proposerIndex.computeIfAbsent(uuid) { linkedSetOf() }.add(id)
        }
        contract.counterpartyUuid?.let { uuid ->
            partyIndex.computeIfAbsent(uuid) { linkedSetOf() }.add(id)
            counterpartyIndex.computeIfAbsent(uuid) { linkedSetOf() }.add(id)
        }
    }

    private fun unindexContract(id: String, contract: Contract) {
        contract.proposerUuid?.let { uuid ->
            partyIndex[uuid]?.remove(id)
            if (partyIndex[uuid].isNullOrEmpty()) {
                partyIndex.remove(uuid)
            }
            proposerIndex[uuid]?.remove(id)
            if (proposerIndex[uuid].isNullOrEmpty()) {
                proposerIndex.remove(uuid)
            }
        }
        contract.counterpartyUuid?.let { uuid ->
            partyIndex[uuid]?.remove(id)
            if (partyIndex[uuid].isNullOrEmpty()) {
                partyIndex.remove(uuid)
            }
            counterpartyIndex[uuid]?.remove(id)
            if (counterpartyIndex[uuid].isNullOrEmpty()) {
                counterpartyIndex.remove(uuid)
            }
        }
    }

    private fun rebuildSnapshot() {
        val values = contracts.values.toList()
        snapshot = values
        snapshotByCreatedDesc = values.sortedByDescending { it.createdAt }
    }

    private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) {
            setNull(index, Types.BIGINT)
        } else {
            setLong(index, value)
        }
    }

    private fun PreparedStatement.setNullableDouble(index: Int, value: Double?) {
        if (value == null) {
            setNull(index, Types.DOUBLE)
        } else {
            setDouble(index, value)
        }
    }

    private fun ResultSet.getNullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private fun ResultSet.getNullableDouble(column: String): Double? {
        val value = getDouble(column)
        return if (wasNull()) null else value
    }

    companion object {
        private const val PAGES_DELIMITER = "\u0000"
    }
}
