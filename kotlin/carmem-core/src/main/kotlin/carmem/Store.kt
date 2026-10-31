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
CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, scope TEXT, event TEXT NOT NULL, detail TEXT NOT NULL)
"""

class Store(private val db: SqlDriver) {
    private var factSeq = 0L

    init { db.executeScript(SCHEMA); factSeq = (db.query("SELECT COALESCE(MAX(seq),0) AS m FROM facts").first()["m"] as Number).toLong() }

    fun close() = db.close()

    // ---------------- persons & identity ----------------
    fun addPerson(p: Person) { db.execute("INSERT OR REPLACE INTO persons(id,name,roles) VALUES (?,?,?)", listOf(p.id, p.name, json.encodeToString(ListSerializer(String.serializer()), p.roles))) }
    fun getPerson(id: String): Person? = db.query("SELECT * FROM persons WHERE id=?", listOf(id)).firstOrNull()?.let {
        Person(it["id"] as String, it["name"] as String, json.decodeFromString(ListSerializer(String.serializer()), it["roles"] as String))
    }
    fun bindIdentity(kind: String, value: String, personId: String) { db.execute("INSERT OR REPLACE INTO identity_bindings(kind,value,person_id) VALUES (?,?,?)", listOf(kind, value, personId)) }
    fun lookupIdentity(kind: String, value: String): String? = db.query("SELECT person_id FROM identity_bindings WHERE kind=? AND value=?", listOf(kind, value)).firstOrNull()?.get("person_id") as String?

    // ---------------- episodes ----------------
    fun addEpisode(scope: String, kind: String, ts: LocalDateTime, text: String, payload: JsonElement, embedding: List<Double>?): Episode {
        db.execute("INSERT INTO episodes(scope,kind,ts,text,payload,embedding) VALUES (?,?,?,?,?,?)",
            listOf(scope, kind, iso(ts), text, payload.toString(), embedding?.let { json.encodeToString(ListSerializer(Double.serializer()), it) }))
        return Episode(db.lastInsertId(), scope, kind, ts, text, payload)
    }
    fun getEpisode(id: Long): Episode? = db.query("SELECT * FROM episodes WHERE id=?", listOf(id)).firstOrNull()?.let(::episode)
    fun episodes(scopes: List<String>, kinds: List<String>? = null, since: LocalDateTime? = null, limit: Int = 500, withEmbeddings: Boolean = false): List<Pair<Episode, List<Double>?>> {
        if (scopes.isEmpty()) return emptyList()
        var q = "SELECT * FROM episodes WHERE scope IN (${scopes.joinToString(",") { "?" }})"
        val args = mutableListOf<Any?>(); args.addAll(scopes)
        if (!kinds.isNullOrEmpty()) { q += " AND kind IN (${kinds.joinToString(",") { "?" }})"; args.addAll(kinds) }
        if (since != null) { q += " AND ts >= ?"; args += iso(since) }
        q += " ORDER BY ts DESC LIMIT ?"; args += limit
        return db.query(q, args).map { r ->
            episode(r) to if (withEmbeddings) (r["embedding"] as String?)?.let { json.decodeFromString(ListSerializer(Double.serializer()), it) } else null
        }
    }
    fun deleteEpisodesBefore(cutoff: LocalDateTime): Int = db.execute("DELETE FROM episodes WHERE ts < ?", listOf(iso(cutoff)))
    private fun episode(r: Map<String, Any?>) = Episode((r["id"] as Number).toLong(), r["scope"] as String, r["kind"] as String, parseIso(r["ts"] as String), r["text"] as String, json.parseToJsonElement(r["payload"] as String))

    // ---------------- facts ----------------
    fun upsertFact(f: Fact) {
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
    fun deleteFact(id: String) { db.execute("DELETE FROM facts WHERE id=?", listOf(id)) }
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
        db.execute("""INSERT OR REPLACE INTO routines(id,scope,signature,dow,minute_of_day,destination,origin,action,state,support,contradictions,demotion,consecutive_no,first_seen,last_seen,stats)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            listOf(rt.id, rt.scope, rt.signature, rt.dow, rt.minuteOfDay, rt.destination, rt.origin, JsonObject(rt.action).toString(), rt.state.wire,
                json.encodeToString(evList, rt.support), json.encodeToString(evList, rt.contradictions), rt.demotion, rt.consecutiveNo,
                rt.firstSeen, rt.lastSeen, JsonObject(rt.stats).toString()))
    }
    fun getRoutine(id: String): Routine? = db.query("SELECT * FROM routines WHERE id=?", listOf(id)).firstOrNull()?.let(::routine)
    fun routines(scopes: List<String>? = null, states: List<State>? = null): List<Routine> {
        var q = "SELECT * FROM routines WHERE 1=1"; val args = mutableListOf<Any?>()
        if (scopes != null) { if (scopes.isEmpty()) return emptyList(); q += " AND scope IN (${scopes.joinToString(",") { "?" }})"; args.addAll(scopes) }
        if (!states.isNullOrEmpty()) { q += " AND state IN (${states.joinToString(",") { "?" }})"; args.addAll(states.map { it.wire }) }
        q += " ORDER BY dow, minute_of_day"
        return db.query(q, args).map(::routine)
    }
    fun deleteRoutine(id: String) { db.execute("DELETE FROM routines WHERE id=?", listOf(id)) }
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
    fun isTombstoned(scope: String, signature: String, now: LocalDateTime): Boolean =
        db.query("SELECT expires FROM tombstones WHERE scope=? AND signature=?", listOf(scope, signature)).firstOrNull()
            ?.let { parseIso(it["expires"] as String).isAfter(now) } ?: false
    fun tombstones(scope: String? = null): List<Map<String, Any?>> =
        if (scope == null) db.query("SELECT * FROM tombstones") else db.query("SELECT * FROM tombstones WHERE scope=?", listOf(scope))
    fun purgeExpiredTombstones(now: LocalDateTime): Int = db.execute("DELETE FROM tombstones WHERE expires <= ?", listOf(iso(now)))

    // ---------------- scope-wide ----------------
    fun purgeScope(scope: String): Map<String, Int> =
        listOf("episodes", "facts", "routines", "tombstones").associateWith { db.execute("DELETE FROM $it WHERE scope=?", listOf(scope)) }
    fun scopesPresent(): Set<String> =
        listOf("episodes", "facts", "routines").flatMap { t -> db.query("SELECT DISTINCT scope AS s FROM $t").map { it["s"] as String } }.toSet()

    // ---------------- audit ----------------
    fun audit(ts: LocalDateTime, scope: String?, event: String, detail: JsonElement = JsonNull) {
        db.execute("INSERT INTO audit(ts,scope,event,detail) VALUES (?,?,?,?)", listOf(iso(ts), scope, event, detail.toString()))
    }
    fun auditLog(limit: Int = 100): List<Map<String, Any?>> = db.query("SELECT * FROM audit ORDER BY id DESC LIMIT ?", listOf(limit))
}
