package carmem

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

data class Candidate(
    val category: String, val key: String, val value: JsonElement, val condition: String? = null,
    val kind: String = "stated", val about: String = "person",
)

data class Intent(val name: String, val target: String? = null, val raw: String = "")

private val REJECT = Regex("""^\s*(no|nope|nah|stop|don'?t|not now|wrong|cancel)\b""", RegexOption.IGNORE_CASE)
private val ACCEPT = Regex("""^\s*(yes|yeah|yep|ok(ay)?|sure|please do|go ahead|do it)\b""", RegexOption.IGNORE_CASE)
private val FORGET_THAT = Regex("""\bforget (that|this|it)\s*[.!?]*\s*$""", RegexOption.IGNORE_CASE)
private val FORGET_X = Regex("""\bforget (?:that |about )?(.+)""", RegexOption.IGNORE_CASE)
private val EXPLAIN = Regex("""\bwhy (do|did|would) you (think|say|assume|suggest)\b|^\s*why\??\s*$""", RegexOption.IGNORE_CASE)

fun detectIntent(text: String): Intent {
    if (REJECT.containsMatchIn(text)) return Intent("reject", raw = text)
    if (ACCEPT.containsMatchIn(text)) return Intent("accept", raw = text)
    if (FORGET_THAT.containsMatchIn(text)) return Intent("forget", null, text)
    FORGET_X.find(text)?.let { return Intent("forget", it.groupValues[1].trim(' ', '.', '!', '?'), text) }
    if (EXPLAIN.containsMatchIn(text)) return Intent("explain", raw = text)
    return Intent("none", raw = text)
}

fun slug(text: String): String = text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifEmpty { "note" }

interface Extractor { fun extract(text: String): List<Candidate> }

private val CHILD = Regex("""\b(?:child(?:ren)?|kids?|son|daughter|baby|toddler|infant|booster)\b|\bchild ?seat\b|\bcar ?seat\b""", RegexOption.IGNORE_CASE)
/** Free text about the speaker's children is sensitive and stays off the cloud; everything else is a plain note. */
fun noteCategory(text: String): String = if (CHILD.containsMatchIn(text)) "children" else "note"

/** Same patterns as carmem/extraction.py. */
class RuleExtractor : Extractor {
    private val I = RegexOption.IGNORE_CASE
    private val tempPref = Regex("""\bi (?:prefer|like|want)\b[^0-9]{0,40}?(\d{2}(?:\.\d)?)\s*(?:°|degrees|deg|c)?\b""", I)
    private val tempSet = Regex("""\b(?:set|make|put)\b[^0-9]{0,30}?(\d{2}(?:\.\d)?)\s*(?:°|degrees|deg|c)?\b""", I)
    private val school = Regex("""\bmy (?:kid'?s?|son'?s?|daughter'?s?|child'?s?|children'?s?) school is ([\w .'-]+)""", I)
    private val home = Regex("""\bmy home(?: address)? is ([\w .'-]+)""", I)
    private val work = Regex("""\b(?:my (?:work|office) is|i work at) ([\w .'-]+)""", I)
    private val noTolls = Regex("""\b(?:i )?(?:hate|dislike|avoid|don'?t (?:like|want))\b.{0,20}\btolls?\b""", I)
    private val tollsOk = Regex("""\b(?:tolls? (?:are|is) fine|don'?t mind tolls?|take (?:the )?tolls?)\b""", I)
    private val allergy = Regex("""\bi(?:'m| am) allergic to ([\w .'-]+)""", I)
    private val sensor = Regex("""\b(?:the |my )?(left|right) (front|rear) (?:parking )?sensor (?:is|seems|keeps|has been) ([\w ]+)""", I)
    private val tyre = Regex("""\b(?:tyre|tire) pressure (?:warning|light|alert)\b""", I)
    private val remember = Regex("""\bremember (?:that )?(.+)""", I)

    private fun String.clean() = trim(' ', '.')

    override fun extract(text: String): List<Candidate> {
        val t = text.trim(); val out = mutableListOf<Candidate>()
        tempPref.find(t)?.let { out += Candidate("climate", "cabin_temp", JsonPrimitive(it.groupValues[1].toDouble()), kind = "stated") }
            ?: tempSet.find(t)?.let { out += Candidate("climate", "cabin_temp", JsonPrimitive(it.groupValues[1].toDouble()), kind = "observed") }
        school.find(t)?.let { out += Candidate("school", "school_location", JsonPrimitive(it.groupValues[1].clean())) }
        home.find(t)?.let { out += Candidate("home_address", "home", JsonPrimitive(it.groupValues[1].clean())) }
        work.find(t)?.let { out += Candidate("note", "workplace", JsonPrimitive(it.groupValues[1].clean())) }
        if (noTolls.containsMatchIn(t)) out += Candidate("route_prefs", "avoid_tolls", JsonPrimitive(true))
        else if (tollsOk.containsMatchIn(t)) out += Candidate("route_prefs", "avoid_tolls", JsonPrimitive(false))
        allergy.find(t)?.let { out += Candidate("health", "allergy", JsonPrimitive(it.groupValues[1].clean())) }
        sensor.find(t)?.let { out += Candidate("vehicle_fault", "${it.groupValues[1].lowercase()}_${it.groupValues[2].lowercase()}_sensor", JsonPrimitive(it.groupValues[3].clean()), about = "vehicle") }
        if (tyre.containsMatchIn(t)) out += Candidate("vehicle_fault", "tyre_pressure", JsonPrimitive("warning reported"), about = "vehicle")
        if (out.isEmpty()) remember.find(t)?.let { val n = it.groupValues[1].clean(); out += Candidate(noteCategory(n), slug(n), JsonPrimitive(n)) }
        return out
    }
}

/** Any model, any vendor: return JSON matching `schema` for a system + user prompt. */
interface LlmClient { fun completeJson(system: String, user: String, schemaJson: String): String }

const val EXTRACTION_SYSTEM = "You extract durable memory from one utterance spoken to an in-car assistant. " +
    "Return only facts worth remembering across trips: preferences, places, people, health, or complaints about the car itself (about=vehicle). " +
    "A one-off command such as 'set it to 21' is kind=observed; a preference such as 'I like it at 21' is kind=stated. Use a short snake_case key. " +
    "Use these keys when they fit, so memories line up with what the car records: cabin_temp (climate, degrees C), " +
    "fan (climate), seat_position (seat) and media (media) only for a value the car can set directly; " +
    "avoid_tolls (route_prefs, true or false), home (home_address), school_location (school), allergy (health); anything about the speaker's children " +
    "(car seats, names, ages, pick-ups) is category children. " +
    "A like or dislike gets its own key, e.g. disliked_genre. " +
    "Put a condition (e.g. 'season=winter', 'daypart=morning') only when the speaker states one. Return an empty list when nothing durable was said."

const val EXTRACTION_SCHEMA = """{"type":"object","properties":{"candidates":{"type":"array","items":{"type":"object","properties":{
"category":{"type":"string","enum":["climate","seat","media","route_prefs","home_address","school","children","contacts","health","finance","note","vehicle_fault"]},
"key":{"type":"string"},"value":{"type":"string"},"condition":{"type":["string","null"]},"kind":{"type":"string","enum":["stated","observed"]},
"about":{"type":"string","enum":["person","vehicle"]}},"required":["category","key","value","condition","kind","about"],"additionalProperties":false}}},
"required":["candidates"],"additionalProperties":false}"""

/** Model-backed extraction with the rules as the safety net. */
class LlmExtractor(private val llm: LlmClient, private val fallback: RuleExtractor = RuleExtractor()) : Extractor {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    override fun extract(text: String): List<Candidate> = try {
        val obj = json.parseToJsonElement(llm.completeJson(EXTRACTION_SYSTEM, text, EXTRACTION_SCHEMA)) as kotlinx.serialization.json.JsonObject
        (obj["candidates"] as kotlinx.serialization.json.JsonArray).map { it as kotlinx.serialization.json.JsonObject }.map { it ->
            val cat = (it["category"] as JsonPrimitive).content
            val raw = (it["value"] as JsonPrimitive).content
            val value: JsonElement = when (cat) {
                "climate" -> raw.trimEnd('°', 'C', 'c', ' ').toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)
                "route_prefs" -> JsonPrimitive(raw.trim().lowercase() in setOf("true", "yes", "1"))
                else -> JsonPrimitive(raw)
            }
            Candidate(cat, slug((it["key"] as JsonPrimitive).content), value, (it["condition"] as? JsonPrimitive)?.takeIf { !it.isString || it.content.isNotEmpty() }?.let { c -> if (c is JsonPrimitive && c.isString) c.content else null },
                (it["kind"] as? JsonPrimitive)?.content ?: "stated", (it["about"] as? JsonPrimitive)?.content ?: "person")
        }
    } catch (e: Exception) { fallback.extract(text) }
}
