---
type: research
status: active
tags: [research, architecture, database]
---

# q21 committed-program acquisition grounding — sol read-only pass (2026-07-21 overnight)

Orchestrator-accepted. The recommendation (identity-attribute paging via
index-page + bounded pull-many inside acquire-committed-projection!,
same result shape, atomic admission preserved) is the q21 unit; risk-1
Transit sizing is its mandatory pre-implementation probe. Falsifier 5
spawns q22 (pod/host/web triplicate program acquisition convergence).

# q21 — committed-program acquisition grounding

No files or runtime state were changed.

## Dependency ledger

| Dependency / mechanism | Grounding |
|---|---|
| Maintained Datahike fork | The writer and pod resolve Datahike from `reference-code/datahike`; the pod overrides the published dependency with that same checkout. [deps.edn](/Users/sean/src/seon/deps.edn:23), [deps.edn](/Users/sean/src/seon/deps.edn:158) |
| Transit framing | JVM uses Transit-CLJ 1.0.333; the pod uses Transit-CLJS 0.8.280. [deps.edn](/Users/sean/src/seon/deps.edn:38), [deps.edn](/Users/sean/src/seon/deps.edn:142) |
| Pod acquisition owner | `seon.runtime.admission/acquire-committed-projection!`. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:232) |
| Pod database API | `seon.db/execute-many` sends bounded grouped reads through the retained database session. [db.cljs](/Users/sean/src/seon/src/seon/db.cljs:1108) |
| Writer serving path | `seon.db.writer/handle-request!` clamps reads and dispatches `execute-many`. [writer.clj](/Users/sean/src/seon/src/seon/db/writer.clj:4092) |
| Existing paging primitive | `seon.db/index-page`, backed by maintained Datahike’s cursor-based native-index page. [db.cljs](/Users/sean/src/seon/src/seon/db.cljs:1164), [index_page.cljc](/Users/sean/src/seon/reference-code/datahike/src/datahike/index_page.cljc:114) |

## 1. Owner and exact call chain

“Committed program acquisition” is the boot acquisition of the complete active Malli schema and function-contract projection—not the complete authored source corpus.

1. Cold boot opens the publication gate, then awaits `admission/publish-committed!`; failure is fatal to `start-runtime!`. [client.cljs](/Users/sean/src/seon/src/seon/client.cljs:2291), [client.cljs](/Users/sean/src/seon/src/seon/client.cljs:2327)

2. `publish-committed!` calls `prepare-committed!` and then `admit-prepared!`. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:411)

3. `prepare-committed!` calls `reconcile-committed!`; the first failure records a core fault and retries, while a second failure transitions admission to unavailable. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:313)

4. `reconcile-committed!` calls `acquire-committed-projection!`, builds the projection, reconciles instrumentation, activates it, and publishes its fingerprint as the generation. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:251)

5. `acquire-committed-projection!` freezes one immutable database value and sends one `execute-many` containing two query members. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:232)

6. The payload is exactly:

   - every `[schema-key, schema-form-string]` carrying both `:seon.schema/key` and `:seon.schema/form`;
   - every `[function-symbol, contract-form-string]` carrying both `:seon.fn/sym` and `:seon.fn/spec`.  
   [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:196), [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:202)

7. Each member allows 1,000,000 work units, 4,096 results, and 3 MiB shallow result weight; the aggregate allows 6 MiB. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:216)

8. `seon.db/execute-many` inserts the same frozen database value into every member, constructs the protocol request, sends it with a 60-second request deadline, and returns ordered results. [db.cljs](/Users/sean/src/seon/src/seon/db.cljs:1108), [protocol.cljc](/Users/sean/src/seon/src/seon/db/protocol.cljc:1415)

9. Writer-side, `handle-request!` applies read ceilings and dispatches to `start-execute-many-request!`; the writer plans and resolves all member database values before submitting the queries to Datahike. [writer.clj](/Users/sean/src/seon/src/seon/db/writer.clj:4092), [writer.clj](/Users/sean/src/seon/src/seon/db/writer.clj:3857), [writer.clj](/Users/sean/src/seon/src/seon/db/writer.clj:3255)

10. Query execution uses `d/acquire-q!`; completed member responses are assembled into one `::protocol/results` response. [writer.clj](/Users/sean/src/seon/src/seon/db/writer.clj:3075), [writer.clj](/Users/sean/src/seon/src/seon/db/writer.clj:3520)

11. The UDS transport Transit-encodes that entire response as one frame. If it exceeds the negotiated ceiling, it substitutes a bounded, correlated `frame-too-large` response using the original request ID. [uds.cljc](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:896)

### Why boot-mandatory

Admission starts closed and becomes available only after a verified generation is admitted. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:38), [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:177)

The acquisition compiles every schema and function contract against the complete candidate registry; partial acquisition cannot honestly produce the same projection. [schema.cljc](/Users/sean/src/seon/src/seon/schema.cljc:359), [schema.cljc](/Users/sean/src/seon/src/seon/schema.cljc:491)

The projection is instrumented before atomic activation, and the stable Malli registry subsequently reads only that active generation. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:251), [schema.cljc](/Users/sean/src/seon/src/seon/schema.cljc:167), [schema.cljc](/Users/sean/src/seon/src/seon/schema.cljc:563)

Readiness returns 200 only while admission is available; otherwise it returns 503. [serve.cljs](/Users/sean/src/seon/src/seon/web/serve.cljs:134)

## 2. Measurement and growth

### Current default cluster

Exact current response bytes and current row counts: **NOT GROUNDED**. Both permitted read-only MCP probes were canceled before returning results.

### Durable W1.5b evidence

The W1.5b cluster successfully booted with 867 instrumented contracts at the normal frame ceiling. Its instrumentation report contained no rejected symbols, and `n-instrumented` is the count of accepted projection contract symbols. [pod log](/Users/sean/src/seon/logs/operator-w15b-live3/pod/854ffdce-0569-4ffe-bedf-aa60829c6a75.log:7), [instrument.cljc](/Users/sean/src/seon/src/seon/instrument.cljc:950), [instrument.cljc](/Users/sean/src/seon/src/seon/instrument.cljc:987)

The later launch envelope set `maximum-frame-bytes` to 65,536, after which the correlated acquisition failed at basis transaction `536870919`. [launch envelope](/Users/sean/src/seon/tmp/seon-operator-w15b-live3/launch-envelope-1784678402433.edn:1), [pod log](/Users/sean/src/seon/logs/operator-w15b-live3/pod/854ffdce-0569-4ffe-bedf-aa60829c6a75.log:15)

Therefore the only exact grounded byte estimate is:

```text
Transit payload > 65,536 bytes
```

A tentative interval of 65,537–4,194,304 bytes follows if the successful and failed boots carried identical program rows, but that identity is **NOT GROUNDED** from the logs.

### What drives growth

Growth is the count and encoded length of current schema key/form rows and current specced-function symbol/spec rows. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:196)

`:seon.ns` source and require-edge facts, `:seon.fn/source`, tests, eval entities, and blob content are absent from both queries. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:196)

Eval history is excluded, but successful eval-created definitions can enlarge the current program corpus because detect-and-tee persists current function/schema entities. [agent.cljs](/Users/sean/src/seon/src/seon/agent.cljs:145)

A function contributes only when its Malli metadata parses successfully into `:seon.fn/spec`; missing or unparsable contracts are omitted. [analyzer_info.cljs](/Users/sean/src/seon/src/seon/analyzer_info.cljs:389), [agent.cljs](/Users/sean/src/seon/src/seon/agent.cljs:170)

## 3. Existing bounded-read mechanisms

### Writer default ceilings

W0.5’s writer defaults are 100,000,000 work units, 1,000,000 results, 3,000,000 shallow result weight, and a queue-wave-derived deadline. [writer.clj](/Users/sean/src/seon/src/seon/db/writer.clj:43)

The writer clamps every supplied or omitted read budget with `min(client, server)`, including both an `execute-many` aggregate and each member. [writer.clj](/Users/sean/src/seon/src/seon/db/writer.clj:871)

These govern Datahike work, row count, and shallow result weight—not Transit bytes. The transport encodes the complete response and checks its actual byte length afterward. [writer.clj](/Users/sean/src/seon/src/seon/db/writer.clj:860), [uds.cljc](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:227)

### Database paging

`index-page` is the existing resumable database-read mechanism. Requests carry native index, prefix, direction, limit 1–200, optional cursor, and result-weight cap; responses carry datoms, `complete?`, and an optional cursor. [protocol.cljc](/Users/sean/src/seon/src/seon/db/protocol.cljc:324), [protocol.cljc](/Users/sean/src/seon/src/seon/db/protocol.cljc:552), [protocol.cljc](/Users/sean/src/seon/src/seon/db/protocol.cljc:891)

Datahike seeks native index order, resumes strictly after the cursor, realizes only `limit + 1`, and certifies the page’s result weight. [index_page.cljc](/Users/sean/src/seon/reference-code/datahike/src/datahike/index_page.cljc:114)

Existing callers already use the intended composition: enumerate bounded entity IDs with `index-page`, then fetch bounded projections using `pull-many` at the same database value. [message.cljs](/Users/sean/src/seon/src/seon/agent/message.cljs:162), [transcript.cljs](/Users/sean/src/seon/src/seon/agent/ctx/transcript.cljs:933)

### Get-in/path value browser

The value browser is not a database paging path; it operates on an already-retained live value. [value.cljc](/Users/sean/src/seon/src/seon/render/value.cljc:1668)

Its relevant precedent is the bounding discipline:

- closed request validation before traversal;
- bounded path segments and encoded path size;
- `offset + page-size` capped before realization;
- only `page-size + 1` elements realized to prove `more?`;
- deep validation of the completed response, failing closed when exceeded.  
  [value.cljc](/Users/sean/src/seon/src/seon/render/value.cljc:1221), [value.cljc](/Users/sean/src/seon/src/seon/render/value.cljc:1309), [value.cljc](/Users/sean/src/seon/src/seon/render/value.cljc:1609), [value.cljc](/Users/sean/src/seon/src/seon/render/value.cljc:1668)

Those are the correct rules to carry into q21, but its offset model should not be copied; database acquisition already has stable cursors. [index_page.cljc](/Users/sean/src/seon/reference-code/datahike/src/datahike/index_page.cljc:91)

## 4. Decision-ready recommendation

Strengthen the existing `index-page` plus bounded-`pull-many` mechanism inside `acquire-committed-projection!`; preserve its current final return shape.

Recommended acquisition:

1. Freeze one database value once, as today. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:232)

2. Enumerate the small identity attributes through AEVT pages:

   - `[:seon.schema/key]`;
   - `[:seon.fn/sym]`.  
   Both are existing identity attributes. [agent.cljs](/Users/sean/src/seon/src/seon/agent.cljs:156), [agent.cljs](/Users/sean/src/seon/src/seon/agent.cljs:181)

3. Prefer identity-attribute enumeration over paging `:seon.fn/spec` or `:seon.schema/form`: an index cursor includes the datom value, so paging the potentially large form string would place that string in both page data and continuation cursors. [index_page.cljc](/Users/sean/src/seon/reference-code/datahike/src/datahike/index_page.cljc:91), [index_page.cljc](/Users/sean/src/seon/reference-code/datahike/src/datahike/index_page.cljc:110)

4. For each bounded ID page, perform a bounded `pull-many` for only the required pair. Omit functions lacking `:seon.fn/spec` so the final set exactly matches the current presence query. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:202)

5. Continue until both streams return `complete?`; do not impose a transcript-style maximum-page stop because boot requires completeness. [protocol.cljc](/Users/sean/src/seon/src/seon/db/protocol.cljc:891), [schema.cljc](/Users/sean/src/seon/src/seon/schema.cljc:491)

6. Reassemble the identical `{schema-rows, function-contract-rows}` map and invoke the unchanged projection compiler once. No downstream public shape change is required. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:208), [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:246)

7. Admit and activate only after every page is complete and the full projection passes existing validation/instrumentation. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:251), [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:374)

### Required qualification

Count paging alone cannot guarantee 64-KiB support: both stored contract fields are unconstrained strings, so one individual form may exceed a frame. [agent.cljs](/Users/sean/src/seon/src/seon/agent.cljs:170), [agent.cljs](/Users/sean/src/seon/src/seon/agent.cljs:181)

The implementation must therefore either:

- prove a conservative page/pull envelope under the supported 64-KiB floor and return a typed error for an individually oversized row; or
- strengthen the generic paged-read mechanism further so it produces transport-frame-safe pages.

The exact conversion between Datahike shallow weight and Transit byte size is **NOT GROUNDED** and must not be assumed.

### Alternatives

- **Compact one-shot projection:** rejected. The current payload already contains only the identities and form strings required to build the projection, so compaction changes the coefficient but remains unbounded. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:196), [schema.cljc](/Users/sean/src/seon/src/seon/schema.cljc:491)

- **Lazy/on-demand acquisition:** rejected. Projection compilation validates cross-schema and function-contract dependencies against the complete registry before admission. [schema.cljc](/Users/sean/src/seon/src/seon/schema.cljc:359), [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:251)

- **Multi-frame streaming:** record as the fallback if arbitrary single forms must exceed the minimum frame. It is materially broader because the current client settles and removes a pending request on the first correlated response, while multi-message handling is reserved for database-interest events. [uds.cljs](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:527), [uds.cljs](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:540)

- **Dedicated program-projection protocol operation:** reject unless measurement proves index-page plus pull is inadequate; otherwise it creates a second program-read path beside generic database paging. The repository already has duplicate semantic acquisitions in pod admission, JVM host context, and web value projection. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:196), [context.clj](/Users/sean/src/seon/src/seon/host/context.clj:1491), [web/value.cljs](/Users/sean/src/seon/src/seon/web/value.cljs:8)

## 5. Blast radius

The private acquisition result has one direct consumer: `reconcile-committed!`, through `committed-projection`. Internal paging that preserves the final row vectors does not break callers. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:208), [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:251)

Projection consumers require only one complete atomic projection:

- schema activation installs the entire projection at once; [schema.cljc](/Users/sean/src/seon/src/seon/schema.cljc:563)
- instrumentation consumes its registry and function-contract map; [instrument.cljc](/Users/sean/src/seon/src/seon/instrument.cljc:950)
- admission exposes only the verified generation, not pages. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:374)

The JVM SCI host separately repeats the same two-query acquisition and has a hard 4,096-row sentinel bound, so it shares the long-term scaling weakness even though it does not consume the pod’s private return map. [context.clj](/Users/sean/src/seon/src/seon/host/context.clj:1491), [context.clj](/Users/sean/src/seon/src/seon/host/context.clj:1503)

## 6. Ranked risks and cheapest falsifiers

1. **Actual Transit bytes do not track shallow result weight; one row may exceed the minimum frame.**  
   Cheapest falsifier: at one frozen database value, encode the exact response envelope for candidate page sizes 1, 2, 4, … and separately encode the largest single schema and contract row. Use Transit serialization followed by UTF-8 byte length—the same operation as framing. [uds.cljs](/Users/sean/src/seon/src/seon/db/transport/uds.cljs:178)

2. **Missing or duplicated rows across cursors would silently produce the wrong generation.**  
   Cheapest falsifier: compare sets from the proposed complete paged scan with today’s two queries at the same database value; assert identity uniqueness and exact equality before projection compilation. Datahike resumes only after a cursor that must still exist within the requested prefix. [index_page.cljc](/Users/sean/src/seon/reference-code/datahike/src/datahike/index_page.cljc:121), [schema.cljc](/Users/sean/src/seon/src/seon/schema.cljc:500)

3. **Enumerating all `:seon.fn/sym` rows could accidentally include unspecced functions.**  
   Cheapest falsifier: compare the final pulled-and-filtered symbol set with the existing `:seon.fn/spec` presence query. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:202), [agent.cljs](/Users/sean/src/seon/src/seon/agent.cljs:170)

4. **Paging could accidentally mix database generations.**  
   Cheapest falsifier: capture every outgoing page and pull request and assert the same complete `:seon.db/db` map is present in all of them. The current acquisition already freezes that value once. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:232), [protocol.cljc](/Users/sean/src/seon/src/seon/db/protocol.cljc:552)

5. **The pod, JVM host, and web projection readers can drift.**  
   Cheapest falsifier: inventory their query/result contracts and require one shared paging recipe or explicit convergence follow-up before calling q21 closed. Their current duplicated queries are source-identical in meaning. [admission.cljs](/Users/sean/src/seon/src/seon/runtime/admission.cljs:196), [context.clj](/Users/sean/src/seon/src/seon/host/context.clj:1491), [web/value.cljs](/Users/sean/src/seon/src/seon/web/value.cljs:8)

**Decision:** implement private cursor paging over the existing database read mechanism, preserve the complete projection and atomic admission contract, and treat exact Transit sizing—including a single-row test—as the mandatory pre-implementation falsifier.