package carmem

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One test per kind of memory a car assistant needs, and per problem a shared car adds. */
class UseCaseTest {
    private val fob = listOf(IdentitySignal("key_fob", "fob-a"))
    private val t0: LocalDateTime = LocalDateTime.of(2026, 9, 14, 8, 0)   // a Monday
    private fun make(policy: Policy = Policy()) = CarMemory(JdbcSqlDriver(), policy).also { it.enroll("asha", "Asha", listOf("key_fob" to "fob-a")) }
    private fun refs(o: JsonObject) = o["references"]!!.jsonArray.associate { it.jsonObject["phrase"]!!.jsonPrimitive.content to it.jsonObject["name"]!!.jsonPrimitive.content }

    // ---------------------------------------------------------------- conversation
    @Test fun `conversation - take me there and call him resolve to what was just talked about`() {
        val mem = make(); mem.ignitionOn(fob, t0)
        mem.hear("my boss is John", now = t0)
        mem.hear("find me an Italian restaurant", now = t0.plusMinutes(1))
        mem.said("The closest is Luigi's on Main Street.", t0.plusMinutes(1)); mem.mention("place", "Luigi's", t0.plusMinutes(1))
        mem.hear("did the boss call?", now = t0.plusMinutes(2))
        assertEquals(mapOf("there" to "Luigi's"), refs(mem.resolve("take me there", t0.plusMinutes(2))))
        assertEquals(mapOf("him" to "John"), refs(mem.resolve("call him", t0.plusMinutes(2))))
        val ctx = mem.context("", "local", t0.plusMinutes(2)).render()
        assertTrue("- driver: find me an Italian restaurant" in ctx && "- assistant: The closest is Luigi's on Main Street." in ctx, ctx)
        assertTrue("place = Luigi's" in ctx && "person = John" in ctx, ctx)
    }

    @Test fun `conversation - lasts five minutes or until the trip ends, and is never stored`() {
        val mem = make(); mem.ignitionOn(fob, t0)
        mem.hear("find me a pharmacy", now = t0); mem.mention("place", "Boots", t0)
        assertEquals(mapOf("there" to "Boots"), refs(mem.resolve("take me there", t0.plusMinutes(4))))
        assertTrue(refs(mem.resolve("take me there", t0.plusMinutes(6))).isEmpty())                 // five minutes later it is stale
        assertTrue(mem.conversation(t0.plusMinutes(6))["turns"]!!.jsonArray.isEmpty())
        mem.mention("place", "Boots", t0.plusMinutes(7)); mem.ignitionOff(t0.plusMinutes(8)); mem.ignitionOn(fob, t0.plusMinutes(9))
        assertTrue(refs(mem.resolve("take me there", t0.plusMinutes(9))).isEmpty())                 // a new drive starts clean
        assertEquals(0L, (mem.store.count("facts")))                                                 // "Boots" was never written anywhere
    }

    // ---------------------------------------------------------------- live vehicle state
    @Test fun `live state - shown to the assistant, never written to the database`() {
        val mem = make(); mem.ignitionOn(fob, t0)
        mem.setLive("location", "Riverside Car Park"); mem.setLive("cabin_temp", "21.5"); mem.setLive("seats_occupied", "2")
        val ctx = mem.context("", "local", t0).render()
        assertTrue("Right now (live, not stored):" in ctx && "- location: Riverside Car Park" in ctx && "- cabin temp: 21.5" in ctx, ctx)
        assertEquals(mapOf("here" to "Riverside Car Park"), refs(mem.resolve("how far is the station from here", t0)))
        val stored = listOf("episodes", "facts", "routines", "audit", "meta").joinToString { tbl -> mem.store.count(tbl).toString() }
        mem.setLive("location", null)
        assertTrue("Riverside" !in mem.export("asha").toString() && "Riverside" !in mem.context("", "local", t0).render(), stored)
    }

    // ---------------------------------------------------------------- preferences
    @Test fun `preferences - said or learned, and a dislike replaces a like`() {
        val mem = make(); mem.ignitionOn(fob, t0)
        assertTrue("climate/cabin_temp = 21.0" in mem.hear("I like it at 21 degrees", now = t0)["stored"].toString())
        assertTrue("route_prefs/avoid_tolls = True" in mem.hear("I hate tolls", now = t0)["stored"].toString())
        assertTrue("media/music_jazz = likes" in mem.hear("I really like jazz", now = t0)["stored"].toString())
        assertTrue("media/music_country = dislikes" in mem.hear("I can't stand country music", now = t0)["stored"].toString())
        mem.hear("I don't like jazz", now = t0.plusDays(1))
        val ctx = mem.context("music", "local", t0.plusDays(2)).render()
        assertTrue("media/music_jazz = dislikes" in ctx && "media/music_jazz = likes" !in ctx, ctx)
        // learned from what the driver does: the same station on three drives
        mem.ignitionOff(t0.plusHours(1))
        for (d in 3L..5L) { val t = t0.plusDays(d); mem.ignitionOn(fob, t); mem.recordTrip(Trip(t, t.plusMinutes(20), "home", "work", listOf("asha"), mapOf("media" to JsonPrimitive("Jazz FM"), "fan" to JsonPrimitive(2)))); mem.ignitionOff(t.plusHours(1)) }
        mem.ignitionOn(fob, t0.plusDays(6))
        val learned = mem.context("", "local", t0.plusDays(6)).render()
        assertTrue("media/media = Jazz FM [implicit, confirmed" in learned && "climate/fan = 2 [implicit, confirmed" in learned, learned)
    }

    // ---------------------------------------------------------------- named things
    @Test fun `named things - people and places get names the driver chose`() {
        val mem = make(); mem.ignitionOn(fob, t0)
        var here: String? = null
        mem.placeNamer = { name, _ -> here = name; name }; mem.knownPlaces = { listOfNotNull("home", here) }
        assertTrue("contacts/boss = John" in mem.hear("the boss is John", now = t0)["stored"].toString())
        assertTrue("contacts/dentist = Dr Patel" in mem.hear("Dr Patel is my dentist", now = t0)["stored"].toString())
        assertTrue("place/mom's house = 12 Elm Street" in mem.hear("mom's house is at 12 Elm Street", now = t0)["stored"].toString())
        // naming where the car is now goes to the car pipeline, which knows the coordinates
        val named = mem.hear("this is mom's house", now = t0)
        assertEquals("name_place", named["intent"]!!.jsonPrimitive.content); assertEquals("mom's house", here)
        assertEquals("the gym", mem.hear("save this place as the gym", now = t0)["place"]!!.jsonPrimitive.content)
        assertEquals("none", mem.hear("this is great", now = t0)["intent"]!!.jsonPrimitive.content)   // not a place
        // an alias in a later sentence becomes what "there" and "her" mean
        mem.hear("how long to mom's house", now = t0.plusMinutes(1)); mem.hear("has the boss emailed", now = t0.plusMinutes(1))
        assertEquals(mapOf("there" to "mom's house"), refs(mem.resolve("take me there", t0.plusMinutes(1))))
        assertEquals(mapOf("him" to "John"), refs(mem.resolve("call him", t0.plusMinutes(1))))
        // the cloud sees placeholders for every one of them
        val cloud = mem.context("boss mom", "cloud", t0.plusMinutes(10))
        val text = cloud.render()
        assertTrue(listOf("John", "Patel", "Elm", "gym", "mom").none { it in text } && "contacts/boss = <contacts_" in text && "<place_" in text, text)
        assertEquals("Call John", cloud.unmask("Call " + cloud.masked.entries.first { it.value == "John" }.key))
    }

    // ---------------------------------------------------------------- past events
    @Test fun `past events - the restaurant from last Friday`() {
        val mem = make()
        val friday = t0.minusDays(3).withHour(19)            // t0 is a Monday
        fun trip(start: LocalDateTime, to: String, kind: String? = null) { mem.ignitionOn(fob, start); mem.recordTrip(Trip(start, start.plusMinutes(20), "home", to, listOf("asha"), destinationKind = kind)); mem.ignitionOff(start.plusHours(1)) }
        trip(friday.minusDays(1), "Greenway Market", "supermarket")
        trip(friday, "Luigi's", "restaurant")
        trip(friday.plusDays(1), "Lotus Yoga", "gym")
        trip(friday.minusDays(7), "Sakura", "restaurant")     // a restaurant, but the Friday before
        mem.ignitionOn(fob, t0)
        val hit = mem.recall("what was the restaurant from last Friday", 3, t0)["episodes"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertEquals("trip home -> Luigi's (restaurant)", hit.first(), hit.toString())
        assertTrue(hit.none { "Sakura" in it || "Greenway" in it || "Lotus" in it }, hit.toString())
        // a question that shares no word with the trip still finds what happened that day
        val went = mem.recall("where did I go on Saturday", 3, t0)["episodes"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertEquals(listOf("trip home -> Lotus Yoga (gym)"), went)
        assertNull(timeRange("what do I do on Fridays", t0))  // a habit, not a day
        assertEquals(t0.minusDays(1).toLocalDate().atTime(17, 0) to t0.minusDays(1).toLocalDate().atTime(22, 0), timeRange("where was I yesterday evening", t0))
        assertEquals(t0.toLocalDate().minusDays(2).atStartOfDay() to t0.toLocalDate().minusDays(1).atStartOfDay(), timeRange("two days ago", t0))
    }

    // ---------------------------------------------------------------- several drivers, passengers, fleets
    @Test fun `passengers - what a passenger says is theirs, and an unknown passenger leaves nothing`() {
        val mem = make(); mem.enroll("ben", "Ben", listOf("phone" to "ben-phone"))
        mem.ignitionOn(fob + IdentitySignal("phone", "ben-phone", role = "passenger"), t0)
        mem.hear("I'm allergic to peanuts", "ben", t0)                    // Ben, a known passenger
        mem.hear("I'm allergic to shellfish", "rear-left", t0)            // someone the car cannot identify
        assertTrue(mem.store.facts(listOf("asha")).isEmpty())             // none of it became the driver's
        assertEquals(listOf("peanuts"), mem.store.facts(listOf("ben")).map { it.value.render() })
        val bens = mem.context("allergy", "local", t0, "ben").render(); val drivers = mem.context("allergy", "local", t0.plusMinutes(10)).render()
        assertTrue("health/allergy = peanuts" in bens && "peanuts" !in drivers && "shellfish" !in drivers, drivers)
        assertTrue("shellfish" in mem.context("allergy", "local", t0, "rear-left").render())      // theirs for this drive
        mem.ignitionOff(t0.plusHours(1))
        assertTrue(mem.store.scopesPresent().none(::isGuest))
        assertTrue("shellfish" !in mem.store.auditLog(1000).toString() && mem.store.episodes(listOf("ben")).any { "peanuts" in it.first.text })
    }

    @Test fun `face and key fob identify a driver, and a fleet car treats everyone as a guest`() {
        val mem = make(); mem.bind("face", "face-asha", "asha")
        assertEquals("asha", mem.ignitionOn(listOf(IdentitySignal("face", "face-asha")), t0).driverId)
        mem.ignitionOff(t0.plusHours(1))
        val fleet = make(Policy(guest_only = true))
        val occ = fleet.ignitionOn(fob, t0)
        assertTrue(occ.isGuest)
        fleet.hear("my home is 12 Elm Street", now = t0); fleet.recordTrip(Trip(t0, t0.plusMinutes(20), "depot", "airport"))
        fleet.ignitionOff(t0.plusHours(1))
        assertEquals(listOf(0L, 0L, 0L), listOf("episodes", "facts", "routines").map { fleet.store.count(it) })
        assertTrue("Elm" !in fleet.store.auditLog(1000).toString())
    }

    // ---------------------------------------------------------------- privacy
    @Test fun `forget removes the fact, its history, the words it came from and its audit trail`() {
        val mem = make(); mem.ignitionOn(fob, t0)
        mem.hear("my kid's school is Oakwood Primary", now = t0); mem.hear("my kid's school is Hillcrest Academy", now = t0.plusDays(1))
        mem.hear("I'm allergic to peanuts", now = t0.plusDays(1))
        mem.hear("forget that I'm allergic to peanuts", now = t0.plusDays(1).plusMinutes(1))
        mem.hear("forget my kid's school", now = t0.plusDays(1).plusMinutes(2))
        val everything = mem.export("asha").toString() + mem.store.auditLog(1000).toString() + mem.context("school allergy", "local", t0.plusDays(2)).render()
        assertTrue(listOf("peanut", "Oakwood", "Hillcrest").none { it in everything }, everything)
        // and it is not learned again from the same sentence until the driver says it afresh
        assertEquals(2, mem.export("asha")["forgotten"]!!.jsonArray.size)
    }

    @Test fun `a guest driver leaves nothing behind, in any table`() {
        val mem = make()
        mem.ignitionOn(listOf(IdentitySignal("voice", "valet", 0.9)), t0)
        mem.hear("my home is 12 Elm Street", now = t0); mem.hear("remember that the valet ticket is 4471", now = t0)
        mem.ignitionOff(t0.plusMinutes(30))
        val all = listOf("episodes", "facts", "routines", "tombstones", "audit").joinToString { tbl -> mem.store.count(tbl).toString() } + mem.store.auditLog(1000).toString()
        assertTrue("Elm" !in all && "4471" !in all, all)
    }

    @Test fun `the driver can see everything held about them, erase themselves, and the car can be wiped for resale`() {
        val mem = make(); mem.enroll("ben", "Ben", listOf("key_fob" to "fob-b"))
        var cleared = 0; mem.onErased = { cleared++ }
        mem.ignitionOn(fob, t0); mem.hear("my boss is John", now = t0); mem.recordTrip(Trip(t0, t0.plusMinutes(20), "home", "work", listOf("asha")))
        val mine = mem.export()
        assertEquals("Asha", mine["person"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(listOf("identity_bindings", "facts", "routines", "episodes", "forgotten", "audit", "places", "retention").all { it in mine })
        assertTrue("John" in mine["facts"].toString() && "trip home -> work" in mine["episodes"].toString() && "sensitive" in mine["facts"].toString())
        mem.ignitionOff(t0.plusHours(1)); mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-b")), t0.plusHours(2)); mem.hear("I prefer 19 degrees", now = t0.plusHours(2))
        // one person erased: the other is untouched, and the fob no longer identifies anyone
        mem.erasePerson("asha", t0.plusHours(3))
        assertTrue(mem.store.scopesPresent() == setOf("ben") && "John" !in mem.store.auditLog(1000).toString() && cleared == 0)
        mem.ignitionOff(t0.plusHours(4))
        assertTrue(mem.ignitionOn(fob, t0.plusHours(5)).isGuest)
        // resale: nothing is left, and the place list is told to go too
        mem.eraseAll()
        assertEquals(List(7) { 0L }, listOf("episodes", "facts", "routines", "tombstones", "audit", "identity_bindings", "persons").map { mem.store.count(it) })
        assertEquals(1, cleared)
        assertTrue(mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-b")), t0.plusHours(6)).isGuest)
    }

    @Test fun `what is not long term memory expires`() {
        val mem = make(); mem.ignitionOn(fob, t0)
        mem.hear("my kid's school is Oakwood Primary", now = t0); mem.hear("my kid's school is Hillcrest Academy", now = t0.plusDays(1))
        mem.recordTrip(Trip(t0, t0.plusMinutes(20), "home", "work", listOf("asha")))
        mem.ignitionOff(t0.plusDays(1).plusHours(1))
        mem.maintenance(t0.plusDays(200))
        assertEquals(listOf("Hillcrest Academy"), mem.store.facts(listOf("asha")).map { it.value.render() })   // the superseded value is gone
        assertEquals(0L, mem.store.count("episodes"))                                                      // six weeks
        assertTrue(mem.store.auditLog(1000).none { (it["ts"] as String) < iso(t0.plusDays(200 - 90)) })    // ninety days
        assertTrue(mem.store.routines().isEmpty())                                                           // a one-off trip, long retired
    }
}
