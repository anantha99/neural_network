package carmem.android;

/**
 * Cross-process surface of the memory service. Every payload is JSON in the
 * carmem wire schema (fixtures/wire-schema.json) so an assistant written in
 * any language, and the cloud bridge, see the same shapes.
 */
interface ICarMemory {
    /** Process an utterance: driving-time intents first (no / yes / forget / why), then extraction. */
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
    /**
     * Per-call latency measured inside the service since it started: for each call, count, p50/p95/p99/max in
     * milliseconds, the p99 of time spent queued behind other work, and how many calls went over the 50 ms budget.
     */
    String stats();
}
