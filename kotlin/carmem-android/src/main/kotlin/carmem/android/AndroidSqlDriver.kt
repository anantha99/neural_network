package carmem.android

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import carmem.SqlDriver

/**
 * carmem's SqlDriver over the AndroidX bundled SQLite driver, so the schema and
 * SQL in carmem-core run unchanged on the head unit against a known SQLite version.
 *
 * Keep the database in credential-encrypted, per-user storage
 * (context.noBackupFilesDir). For encryption at rest beyond file-based
 * encryption, swap this class for one over SQLCipher's driver; the core does not care.
 */
class AndroidSqlDriver(path: String) : SqlDriver {
    private val conn: SQLiteConnection = BundledSQLiteDriver().open(path).also {
        it.execSQL("PRAGMA journal_mode=WAL")
        // With a write-ahead log, NORMAL keeps the database consistent through a power cut and skips the fsync on
        // every commit, which on flash is most of a write's latency. A cut can lose the last few commits, not corrupt.
        it.execSQL("PRAGMA synchronous=NORMAL")
        it.execSQL("PRAGMA foreign_keys=ON")
    }

    private fun SQLiteStatement.bindAll(args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> bindNull(idx)
                is Int -> bindLong(idx, a.toLong())
                is Long -> bindLong(idx, a)
                is Double -> bindDouble(idx, a)
                is Float -> bindDouble(idx, a.toDouble())
                is Boolean -> bindLong(idx, if (a) 1 else 0)
                is ByteArray -> bindBlob(idx, a)
                else -> bindText(idx, a.toString())
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>): Int {
        conn.prepare(sql).use { st -> st.bindAll(args); while (st.step()) { /* no rows */ } }
        return conn.prepare("SELECT changes()").use { st -> st.step(); st.getLong(0).toInt() }
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = conn.prepare(sql).use { st ->
        st.bindAll(args)
        val names = (0 until st.getColumnCount()).map { st.getColumnName(it) }
        val out = mutableListOf<Map<String, Any?>>()
        while (st.step()) {
            out += names.indices.associate { i ->
                names[i] to when (st.getColumnType(i)) {
                    androidx.sqlite.SQLITE_DATA_INTEGER -> st.getLong(i)
                    androidx.sqlite.SQLITE_DATA_FLOAT -> st.getDouble(i)
                    androidx.sqlite.SQLITE_DATA_TEXT -> st.getText(i)
                    androidx.sqlite.SQLITE_DATA_BLOB -> st.getBlob(i)
                    else -> null
                }
            }
        }
        out
    }

    override fun lastInsertId(): Long = conn.prepare("SELECT last_insert_rowid()").use { st -> st.step(); st.getLong(0) }

    override fun executeScript(sql: String) {
        sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { conn.execSQL(it) }
    }

    override fun close() = conn.close()
}
