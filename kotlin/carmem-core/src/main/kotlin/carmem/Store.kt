package carmem

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import java.time.LocalDateTime

private val json = Json { encodeDefaults = true }
private val evList = ListSerializer(Evidence.serializer())

const val SCHEMA = """
CREATE TABLE IF NOT EXISTS persons (id TEXT PRIMARY KEY, name TEXT NOT NULL, roles TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS identity_bindings (kind TEXT NOT NULL, value TEXT NOT NULL, person_id TEXT NOT NULL, PRIMARY KEY (kind, value));
CREATE TABLE IF NOT EXISTS episodes (id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, kind TEXT NOT NULL, ts TEXT NOT NULL, text TEXT NOT NULL, payload TEXT NOT NULL, embedding TEXT);
CREATE INDEX IF NOT EXISTS episodes_scope_ts ON episodes(scope, ts);
CREATE TABLE IF NOT EXISTS facts (id TEXT PRIMARY KEY, scope TEXT NOT NULL, category TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, condition TEXT, state TEXT NOT NULL, source TEXT NOT NULL, sensitivity TEXT NOT NULL, support TEXT NOT NULL, contradictions TEXT NOT NULL, demotion INTEGER NOT NULL DEFAULT 0, consecutive_no INTEGER NOT NULL DEFAULT 0, created TEXT NOT NULL, updated TEXT NOT NULL, valid_from TEXT NOT NULL DEFAULT '', valid_until TEXT, seq INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS facts_scope ON facts(scope, category, key);
CREATE TABLE IF NOT EXISTS routines (id TEXT PRIMARY KEY, scope TEXT NOT NULL, signature TEXT NOT NULL, dow INTEGER NOT NULL, minute_of_day INTEGER NOT NULL, destination TEXT NOT NULL, origin TEXT NOT NULL DEFAULT '', action TEXT NOT NULL, state TEXT NOT NULL, support TEXT NOT NULL, contradictions TEXT NOT NULL, demotion INTEGER NOT NULL DEFAULT 0, consecutive_no INTEGER NOT NULL DEFAULT 0, first_seen TEXT NOT NULL, last_seen TEXT NOT NULL, stats TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS routines_scope ON routines(scope, dow);
CREATE TABLE IF NOT EXISTS tombstones (scope TEXT NOT NULL, signature TEXT NOT NULL, expires TEXT NOT NULL, reason TEXT NOT NULL, PRIMARY KEY (scope, signature));
CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, scope TEXT, event TEXT NOT NULL, detail TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS routines_signature ON routines(scope, signature)
"""

/** Bumped when a table or column changes shape; [Store] refuses a database written by a newer build. */
const val SCHEMA_VERSION = 2

class Store(private val db: SqlDriver) {
    private var factSeq = 0L
    private var txDepth = 0

    /** Changes whenever a person, fact or routine may have changed. Readers cache against it; episodes do not move it. */
    var semanticVersion = 0L; private set

    init {
        db.executeScript(SCHEMA)
        val found = getMeta("schema_version")?.toIntOrNull() ?: 0
        check(found <= SCHEMA_VERSION) { "database schema $found is newer than this build ($SCHEMA_VERSION)" }
        // 1 -> 2 added the meta table and an index, and new episodes store vectors as blobs; old rows are read as they are.
        if (found < SCHEMA_VERSION) putMeta("schema_version", SCHEMA_VERSION.toString())
        factSeq = (db.query("SELECT COALESCE(MAX(seq),0) AS m FROM facts").first()["m"] as Number).toLong()
        // SQLite folds the write-ahead log back into the database every 1,000 pages by default (about 300 writes
        // here), inside whichever commit crosses the line, and that fold is the one step that waits for the disk:
        // a 20 ms stall on a build machine, more on flash. Fold it at ignition off instead, when nobody is waiting
        // (see [checkpoint]), and keep SQLite's own trigger only as a backstop at 64 MB for a session that never ends.
        db.query("PRAGMA wal_autocheckpoint=16000")
    }

    fun close() = db.close()

    /** Fold the write-ahead log into the database file. Call when latency does not matter; a no-op inside a transaction. */
    fun checkpoint() { if (txDepth == 0) runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)") } }

    /**
     * Run [block] as one atomic write. One commit per call instead of one per statement is what keeps a write
     * inside the latency budget on flash storage, and a failure half way leaves nothing behind. Nested calls join.
     */
    fun <T> tx(block: () -> T): T {
        if (txDepth > 0) return block()
        db.execute("BEGIN IMMEDIATE"); txDepth = 1
        try {
            val r = block()
            db.execute("COMMIT")
            return r
        } catch (e: Throwable) {
            runCatching { db.execute("ROLLBACK") }
            semanticVersion++ // whatever a reader cached during this transaction never happened
            throw e
        } finally { txDepth = 0 }
    }

    // ---------------- meta ----------------
    fun getMeta(key: String): String? = db.query("SELECT value FROM meta WHERE key=?", listOf(key)).firstOrNull()?.get("value") as String?
    fun putMeta(key: String, value: String) { db.execute("INSERT OR REPLACE INTO meta(key,value) VALUES (?,?)", listOf(key, value)) }

    // ---------------- persons & identity ----------------
    fun addPerson(p: Person) { semanticVersion++; db.execute("INSERT OR REPLACE INTO persons(id,name,roles) VALUES (?,?,?)", listOf(p.id, p.name, json.encodeToString(ListSerializer(String.serializer()), p.roles))) }
    fun getPerson(id: String): Person? = db.query("SELECT * FROM persons WHERE id=?", listOf(id)).firstOrNull()?.let {
        Person(it["id"] as String, it["name"] as String, json.decodeFromString(ListSerializer(String.serializer()), it["roles"] as String))
    }
    fun bindIdentity(kind: String, value: String, personId: String) { db.execute("INSERT OR REPLACE INTO identity_bindings(kind,value,person_id) VALUES (?,?,?)", listOf(kind, value, personId)) }
    fun lookupIdentity(kind: String, value: String): String? = db.query("SELECT person_id FROM identity_bindings WHERE kind=? AND value=?", listOf(kind, value)).firstOrNull()?.get("person_id") as String?

    // ---------------- episodes ----------------
    fun addEpisode(scope: String, kind: String, ts: LocalDateTime, text: String, payload: JsonElement, embedding: DoubleArray?): Episode {
        db.execute("INSERT INTO episodes(scope,kind,ts,text,payload,embedding) VALUES (?,?,?,?,?,?)",
            listOf(scope, kind, iso(ts), text, payload.toString(), embedding?.let(::packVector)))
        return Episode(db.lastInsertId(), scope, kind, ts, text, payload)
    }
    fun getEpisode(id: Long): Episode? = db.query("SELECT * FROM episodes WHERE id=?", listOf(id)).firstOrNull()?.let(::episode)
    fun episodes(scopes: List<String>, kinds: List<String>? = null, since: LocalDateTime? = null, limit: Int = 500, withEmbeddings: Boolean = false): List<Pair<Episode, DoubleArray?>> {
        if (scopes.isEmpty()) return emptyList()
        var q = "SELECT * FROM episodes WHERE scope IN (${scopes.joinToString(",") { "?" }})"
        val args = mutableListOf<Any?>(); args.addAll(scopes)
        if (!kinds.isNullOrEmpty()) { q += " AND kind IN (${kinds.joinToString(",") { "?" }})"; args.addAll(kinds) }
        if (since != null) { q += " AND ts >= ?"; args += iso(since) }
        q += " ORDER BY ts DESC LIMIT ?"; args += limit
        return db.query(q, args).map { r ->
            episode(r) to if (withEmbeddings) vector(r["embedding"]) else null
        }
    }

    /** The newest episodes in scope as (id, timestamp): answered from the index alone, so ranking can use its cache. */
    fun episodeIndex(scopes: List<String>, since: LocalDateTime, limit: Int, until: LocalDateTime? = null): List<Pair<Long, LocalDateTime>> {
        if (scopes.isEmpty()) return emptyList()
        val args = mutableListOf<Any?>(); args.addAll(scopes); args += iso(since)
        if (until != null) args += iso(until)
        args += limit
        return db.query("SELECT id, ts FROM episodes WHERE scope IN (${scopes.joinToString(",") { "?" }}) AND ts >= ?${if (until != null) " AND ts < ?" else ""} ORDER BY ts DESC LIMIT ?", args)
            .map { r -> (r["id"] as Number).toLong() to parseIso(r["ts"] as String) }
    }
    /** Kind, text and vector for ranking; the payload is not read or parsed. */
    fun episodeVectors(ids: List<Long>): Map<Long, Triple<String, String, DoubleArray?>> = ids.chunked(500).flatMap { part ->
        db.query("SELECT id, kind, text, embedding FROM episodes WHERE id IN (${part.joinToString(",") { "?" }})", part)
            .map { r -> (r["id"] as Number).toLong() to Triple(r["kind"] as String, r["text"] as String, vector(r["embedding"])) }
    }.toMap()
    fun deleteEpisodes(ids: List<Long>): Int = ids.chunked(500).sumOf { part -> db.execute("DELETE FROM episodes WHERE id IN (${part.joinToString(",") { "?" }})", part) }
    /** Blob (current) or JSON text (written before schema 2). */
    private fun vector(v: Any?): DoubleArray? = when (v) {
        is ByteArray -> unpackVector(v)
        is String -> json.decodeFromString(ListSerializer(Double.serializer()), v).toDoubleArray()
        else -> null
    }
    fun episodesOfKind(kind: String): List<Episode> = db.query("SELECT * FROM episodes WHERE kind=? ORDER BY id", listOf(kind)).map(::episode)
    fun rewriteEpisode(id: Long, text: String, payload: JsonElement, embedding: DoubleArray?) {
        db.execute("UPDATE episodes SET text=?, payload=?, embedding=? WHERE id=?", listOf(text, payload.toString(), embedding?.let(::packVector), id))
    }
    fun deleteEpisodesBefore(cutoff: LocalDateTime): Int = db.execute("DELETE FROM episodes WHERE ts < ?", listOf(iso(cutoff)))
    private fun episode(r: Map<String, Any?>) = Episode((r["id"] as Number).toLong(), r["scope"] as String, r["kind"] as String, parseIso(r["ts"] as String), r["text"] as String, json.parseToJsonElement(r["payload"] as String))

    // ---------------- facts ----------------
    fun upsertFact(f: Fact) {
        semanticVersion++
        val existing = db.query("SELECT seq FROM facts WHERE id=?", listOf(f.id)).firstOrNull()
        val seq = (existing?.get("seq") as Number?)?.toLong() ?: ++factSeq
        db.execute("""INSERT OR REPLACE INTO facts(id,scope,category,key,value,condition,state,source,sensitivity,support,contradictions,demotion,consecutive_no,created,updated,valid_from,valid_until,seq)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            listOf(f.id, f.scope, f.category, f.key, f.value.toString(), f.condition, f.state.wire, f.source, f.sensitivity,
                json.encodeToString(evList, f.support), json.encodeToString(evList, f.contradictions), f.demotion, f.consecutiveNo,
                f.created, f.updated, f.validFrom.ifEmpty { f.created }, f.validUntil, seq))
    }
    fun getFact(id: String): Fact? = db.query("SELECT * FROM facts WHERE id=?", listOf(id)).firstOrNull()?.let(::fact)
    fun facts(scopes: List<String>? = null, category: String? = null, key: String? = null, states: List<State>? = null): List<Fact> {
        var q = "SELECT * FROM facts WHERE 1=1"; val args = mutableListOf<Any?>()
        if (scopes != null) { if (scopes.isEmpty()) return emptyList(); q += " AND scope IN (${scopes.joinToString(",") { "?" }})"; args.addAll(scopes) }
        if (category != null) { q += " AND category=?"; args += category }
        if (key != null) { q += " AND key=?"; args += key }
        if (!states.isNullOrEmpty()) { q += " AND state IN (${states.joinToString(",") { "?" }})"; args.addAll(states.map { it.wire }) }
        q += " ORDER BY updated DESC, seq DESC"
        return db.query(q, args).map(::fact)
    }
    fun deleteFact(id: String) { semanticVersion++; db.execute("DELETE FROM facts WHERE id=?", listOf(id)) }
    fun deleteRetiredBefore(cutoff: LocalDateTime): Int {
        semanticVersion++
        return db.execute("DELETE FROM facts WHERE state='retired' AND updated < ?", listOf(iso(cutoff))) +
            db.execute("DELETE FROM routines WHERE state='retired' AND last_seen < ?", listOf(iso(cutoff)))
    }
    fun factTimeline(scope: String, category: String, key: String): List<Fact> =
        db.query("SELECT * FROM facts WHERE scope=? AND category=? AND key=? ORDER BY valid_from, created, seq", listOf(scope, category, key)).map(::fact)
    private fun fact(r: Map<String, Any?>) = Fact(
        id = r["id"] as String, scope = r["scope"] as String, category = r["category"] as String, key = r["key"] as String,
        value = json.parseToJsonElement(r["value"] as String), condition = r["condition"] as String?, state = State.of(r["state"] as String),
        source = r["source"] as String, sensitivity = r["sensitivity"] as String,
        support = json.decodeFromString(evList, r["support"] as String).toMutableList(),
        contradictions = json.decodeFromString(evList, r["contradictions"] as String).toMutableList(),
        demotion = (r["demotion"] as Number).toInt(), consecutiveNo = (r["consecutive_no"] as Number).toInt(),
        created = r["created"] as String, updated = r["updated"] as String, validFrom = r["valid_from"] as String, validUntil = r["valid_until"] as String?,
    )

    // ---------------- routines ----------------
    fun upsertRoutine(rt: Routine) {
        semanticVersion++
        db.execute("""INSERT OR REPLACE INTO routines(id,scope,signature,dow,minute_of_day,destination,origin,action,state,support,contradictions,demotion,consecutive_no,first_seen,last_seen,stats)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            listOf(rt.id, rt.scope, rt.signature, rt.dow, rt.minuteOfDay, rt.destination, rt.origin, JsonObject(rt.action).toString(), rt.state.wire,
                json.encodeToString(evList, rt.support), json.encodeToString(evList, rt.contradictions), rt.demotion, rt.consecutiveNo,
                rt.firstSeen, rt.lastSeen, JsonObject(rt.stats).toString()))
    }
    fun getRoutine(id: String): Routine? = db.query("SELECT * FROM routines WHERE id=?", listOf(id)).firstOrNull()?.let(::routine)
    fun routines(scopes: List<String>? = null, states: List<State>? = null, dow: Int? = null, signature: String? = null): List<Routine> {
        var q = "SELECT * FROM routines WHERE 1=1"; val args = mutableListOf<Any?>()
        if (scopes != null) { if (scopes.isEmpty()) return emptyList(); q += " AND scope IN (${scopes.joinToString(",") { "?" }})"; args.addAll(scopes) }
        if (!states.isNullOrEmpty()) { q += " AND state IN (${states.joinToString(",") { "?" }})"; args.addAll(states.map { it.wire }) }
        if (dow != null) { q += " AND dow=?"; args += dow }
        if (signature != null) { q += " AND signature=?"; args += signature }
        q += " ORDER BY dow, minute_of_day"
        return db.query(q, args).map(::routine)
    }
    fun deleteRoutine(id: String) { semanticVersion++; db.execute("DELETE FROM routines WHERE id=?", listOf(id)) }
    private fun routine(r: Map<String, Any?>) = Routine(
        id = r["id"] as String, scope = r["scope"] as String, signature = r["signature"] as String, dow = (r["dow"] as Number).toInt(),
        minuteOfDay = (r["minute_of_day"] as Number).toInt(), destination = r["destination"] as String, origin = r["origin"] as String,
        action = (json.parseToJsonElement(r["action"] as String) as JsonObject).toMap(), state = State.of(r["state"] as String),
        support = json.decodeFromString(evList, r["support"] as String).toMutableList(),
        contradictions = json.decodeFromString(evList, r["contradictions"] as String).toMutableList(),
        demotion = (r["demotion"] as Number).toInt(), consecutiveNo = (r["consecutive_no"] as Number).toInt(),
        firstSeen = r["first_seen"] as String, lastSeen = r["last_seen"] as String,
        stats = (json.parseToJsonElement(r["stats"] as String) as JsonObject).toMap(),
    )

    // ---------------- tombstones ----------------
    fun addTombstone(scope: String, signature: String, expires: LocalDateTime, reason: String) {
        db.execute("INSERT OR REPLACE INTO tombstones(scope,signature,expires,reason) VALUES (?,?,?,?)", listOf(scope, signature, iso(expires), reason))
    }
    fun renameTombstones(old: String, new: String): Int = db.execute("UPDATE OR REPLACE tombstones SET signature=? WHERE signature=?", listOf(new, old))
    fun isTombstoned(scope: String, signature: String, now: LocalDateTime): Boolean =
        db.query("SELECT expires FROM tombstones WHERE scope=? AND signature=?", listOf(scope, signature)).firstOrNull()
            ?.let { parseIso(it["expires"] as String).isAfter(now) } ?: false
    fun tombstones(scope: String? = null): List<Map<String, Any?>> =
        if (scope == null) db.query("SELECT * FROM tombstones") else db.query("SELECT * FROM tombstones WHERE scope=?", listOf(scope))
    fun purgeExpiredTombstones(now: LocalDateTime): Int = db.execute("DELETE FROM tombstones WHERE expires <= ?", listOf(iso(now)))

    // ---------------- scope-wide ----------------
    /** Everything held under a scope, the audit trail included: after this the scope's words appear nowhere. */
    fun purgeScope(scope: String): Map<String, Int> {
        semanticVersion++
        val out = listOf("episodes", "facts", "routines", "tombstones").associateWith { db.execute("DELETE FROM $it WHERE scope=?", listOf(scope)) }
        db.execute("DELETE FROM audit WHERE scope=?", listOf(scope))
        return out
    }
    /** A person and everything about them: memory, identity bindings and the person record. */
    fun erasePerson(id: String): Map<String, Int> {
        val out = purgeScope(id).toMutableMap()
        out["bindings"] = db.execute("DELETE FROM identity_bindings WHERE person_id=?", listOf(id))
        out["person"] = db.execute("DELETE FROM persons WHERE id=?", listOf(id))
        return out
    }
    /** Resale, end of lease: every row of every table. The schema stays; nothing else does. */
    fun eraseAll(): Map<String, Int> {
        semanticVersion++
        val out = listOf("episodes", "facts", "routines", "tombstones", "audit", "identity_bindings", "persons").associateWith { db.execute("DELETE FROM $it") }
        db.execute("DELETE FROM meta WHERE key <> 'schema_version'")
        return out
    }
    fun persons(): List<Person> = db.query("SELECT id FROM persons ORDER BY id").mapNotNull { getPerson(it["id"] as String) }
    fun bindings(personId: String): List<Pair<String, String>> =
        db.query("SELECT kind, value FROM identity_bindings WHERE person_id=? ORDER BY kind, value", listOf(personId)).map { (it["kind"] as String) to (it["value"] as String) }
    fun count(table: String): Long = (db.query("SELECT COUNT(*) AS n FROM $table").first()["n"] as Number).toLong()
    fun scopesPresent(): Set<String> =
        listOf("episodes", "facts", "routines").flatMap { t -> db.query("SELECT DISTINCT scope AS s FROM $t").map { it["s"] as String } }.toSet()

    // ---------------- audit ----------------
    fun audit(ts: LocalDateTime, scope: String?, event: String, detail: JsonElement = JsonNull) {
        db.execute("INSERT INTO audit(ts,scope,event,detail) VALUES (?,?,?,?)", listOf(iso(ts), scope, event, detail.toString()))
    }
    fun auditLog(limit: Int = 100): List<Map<String, Any?>> = db.query("SELECT * FROM audit ORDER BY id DESC LIMIT ?", listOf(limit))
    fun auditFor(scope: String): List<Map<String, Any?>> = db.query("SELECT ts, event, detail FROM audit WHERE scope=? ORDER BY id", listOf(scope))
    /** Remove audit entries of a scope that carry `needle` (a record id), so a forgotten memory leaves no trail. */
    fun scrubAudit(scope: String, needle: String): Int = db.execute("DELETE FROM audit WHERE scope=? AND instr(detail, ?) > 0", listOf(scope, needle))
    fun deleteAuditBefore(cutoff: LocalDateTime): Int = db.execute("DELETE FROM audit WHERE ts < ?", listOf(iso(cutoff)))
}
