# carmem-core (Kotlin)

The head-unit implementation of carmem. Pure Kotlin/JVM, no Android dependency,
so it builds and tests on CI in seconds. The Android module (bound service, car
signal adapters, LiteRT-LM extractor) sits on top of this and is the next phase.

```
cd kotlin
gradle :carmem-core:test
```

## Conformance

`ConformanceTest` replays `../fixtures/household_seed7_w8.json`, exported by the
Python reference with `python -m bench.export_fixtures`, using the protocol in
`../fixtures/wire-schema.json`, and asserts the same outcomes: the exact sequence of
90 proactive proposals with tiers, confirmed routines and facts per person, the "no"
counts, the tombstone left by "forget that", zero cross-person or guest leakage, every
QA item, and the cloud-masked field count. Change the policy or the consolidation
rules in Python, re-export, and this test tells you whether the port still agrees.

## Layout

| File | Mirrors |
|---|---|
| `Models.kt` | `carmem/models.py` |
| `Policy.kt` | `carmem/policy.py` (serialisable, loads the fixture's policy JSON) |
| `SqlDriver.kt` | the only database surface; `JdbcSqlDriver` for JVM, AndroidX bundled SQLite on device |
| `Store.kt` | `carmem/store.py` |
| `Identity.kt` | `carmem/identity.py` |
| `Extraction.kt` | `carmem/extraction.py` plus the `LlmClient` interface |
| `Reconcile.kt` | `carmem/reconcile.py` |
| `Consolidation.kt` | `carmem/consolidation.py` |
| `Context.kt` | `carmem/context.py` |
| `CarMemory.kt` | `carmem/memory.py` |
| `Tools.kt` | `carmem/llm/tools.py`, neutral + Anthropic + OpenAI shapes |

## Plugging in a model on device

Implement `LlmClient.completeJson(system, user, schemaJson)` over LiteRT-LM with
JSON-schema constrained decoding and pass it to `CarMemory`. Extraction and note
reconciliation then use the model; driving-time intents stay rule-based. Expose
`CarMemory.toolSpecs()` to the on-device assistant as a `ToolSet` and route calls to
`handleTool`. For the cloud agent, send `context(query, "cloud").render()` with the
request, execute its tool calls locally with `handleTool`, and `unmask` the reply.
