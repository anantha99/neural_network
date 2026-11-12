package carmem

/**
 * The only thing the core needs from a database. JVM tests use JDBC; Android
 * implements this over the AndroidX bundled SQLite driver.
 */
interface SqlDriver {
    fun execute(sql: String, args: List<Any?> = emptyList()): Int
    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>>
    fun lastInsertId(): Long
    fun executeScript(sql: String)
    fun close()
}

/** JDBC implementation (org.xerial sqlite-jdbc on the classpath). */
class JdbcSqlDriver(url: String = "jdbc:sqlite::memory:") : SqlDriver {
    private val conn: java.sql.Connection = java.sql.DriverManager.getConnection(url).also { c ->
        // Same settings as the head unit's driver: a write-ahead log, and no fsync on every commit.
        c.createStatement().use { st -> st.execute("PRAGMA journal_mode=WAL"); st.execute("PRAGMA synchronous=NORMAL") }
    }

    override fun execute(sql: String, args: List<Any?>): Int = conn.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeUpdate()
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = conn.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs ->
            val meta = rs.metaData
            val cols = (1..meta.columnCount).map { meta.getColumnLabel(it) }
            val out = mutableListOf<Map<String, Any?>>()
            while (rs.next()) out += cols.associateWith { rs.getObject(it) }
            out
        }
    }

    override fun lastInsertId(): Long = conn.createStatement().use { st ->
        st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) }
    }

    override fun executeScript(sql: String) {
        conn.createStatement().use { st -> sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) } }
    }

    override fun close() = conn.close()
}
