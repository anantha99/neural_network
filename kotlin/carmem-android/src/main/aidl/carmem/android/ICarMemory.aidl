package carmem.android;

/**
 * Cross-process surface of the memory service. Every payload is JSON in the
 * carmem wire schema (fixtures/wire-schema.json) so an assistant written in
 * any language, and the cloud bridge, see the same shapes.
 */
interface ICarMemory {
    /** Process an utterance: driving-time intents first (no / yes / forget / why); extraction is queued in the background ("deferred": true). */
    String hear(String text, @nullable String speakerId);
    /** Context packet for the current driver. destination = "local" | "cloud" (masked). */
    String context(String query, String destination);
    /** Ranked facts and episodes for a query, scoped to the current driver. */
    String recall(String query, int k);
    /** What the car would do right now; location is the current place name if known. */
    String proactive(@nullable String location);
    /** Dispatch one of the six memory tools by name with JSON args. */
    String handleTool(String name, String argsJson);
    String explain(String recordId);
    String forget(String target);
    /** Semantic + procedural layers for sync; forCloud drops masked categories. */
    String snapshot(boolean forCloud);
    /** Replace the masked tokens in a cloud reply with their originals. */
    String unmask(String text);
    /**
     * Enroll a person with hard-signal bindings, JSON [{"kind":"seat_profile","value":"user:10"}, ...].
     * Until someone is enrolled every driver resolves as a guest, and guests never form memory.
     */
    String enroll(String personId, String name, String bindingsJson);

    // ---------------------------------------------------------------- demo surface

    /**
     * Replay a driver-log fixture (fixtures/household_seed7_w8.json) into the memory: enrolls its persons and
     * replays every event on the worker thread (a few seconds). Returns {events, proposals, persons, lastTs},
     * or {already_seeded: true, persons} when every fixture person is already enrolled.
     */
    String seed(String fixtureJson);
    /** Pin the memory's DemoClock to an ISO local time ("2026-10-12T07:38:00"); empty resets to the wall clock. Returns {now, pinned}. */
    String setClock(String isoOrEmpty);
    /**
     * Post one CarEvent through the session, as the platform adapters would. JSON {type, ts?, ...}:
     * ignition {on}, location {lat, lon, speed_mps?}, setting {key, value}, occupant {kind, value, confidence?, role?},
     * utterance {text, speaker?}, fault {key, value}, place {name}. ts defaults to the memory clock. Returns {ok, event, ts}.
     */
    String injectEvent(String eventJson);
    /**
     * The assistant: rule intents first, else the chat model with the context packet and the memory tools
     * (executed here). destination = "local" | "cloud" (masked out, un-masked back). Returns
     * {reply, raw_reply, packet_rendered, masked_count, tool_calls: [{name, args, result}], destination, intent, note}.
     */
    String chat(String text, String destination);
    /** {driver_id, name, is_guest, now, guest_scopes_remaining} for the current session, or {guest_scopes_remaining} when none. */
    String whoami();
}
