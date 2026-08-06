---
type: research
status: complete
tags: [research, rendering, runtime, web]
---

# Unit 1G value-sampling transport readiness (2026-07-20)

## Decision

The dependency-ready unit after Unit 1F is **Unit 1G: value-sampling
transport**, not the HTTP path codec. Unit 1F must first freeze one public
`seon.render.value` descent-and-page producer over the Unit 1E
`drill-request`/`drill-result` population. Unit 1G then carries that already
decoded, already bounded request to the runtime that owns the live eval value
and returns only the same bounded ordinary-data result.

The path codec belongs later to the existing value-route handler in
`seon.web.serve`. It is not a renderer concern, an execution protocol concern,
or a new namespace. `seon.render.value` owns legal path values and bounded
descent; `seon.web.serve` owns raw URL framing, strict EDN parsing, canonical
printing, duplicate query fields, encoded-byte measurement, and HTTP refusal;
the serving runtime repeats scalar, segment, index, and realized-work checks
over decoded ordinary data but never parses HTTP.

There is one material correction to the older
[[execution-child-value-sampling-boundary-2026-07-20]] audit. Current source
has one lane-keyed dispatcher over two serving transports. An agent with
`:seon.execution.host/eval-socket-path` evaluates in the JVM `seon.host`, not
the Bun child. `seon.host` currently returns wire-safe eval values but retains
no eval-id-to-live-value slot after the invocation. A Bun-only Unit 1G would
therefore violate the universal-browser contract for host-tier agents. Before
implementation, the orchestrator must record this as a found problem and rule
one of two honest contracts:

1. **Recommended:** Unit 1G strengthens both existing protocol peers and the
   one lane-keyed dispatcher. Bun uses the existing `seon.eval/lookup-result`;
   JVM adds the equivalent bounded process-local eval-id slot beside its SCI
   context and samples locally. No raw value crosses merely to be sampled.
2. Temporarily refuse host-tier eval drill as unavailable and make Stage 1.5
   graduation explicitly exclude host-tier agents. This is honest but is not
   the roadmap's current universal claim and would require a deliberate
   product ruling.

This report uses option 1 for readiness and acceptance. It does not authorize
an implementation until Unit 1F is frozen and the cross-tier retention ruling
is durable.

## Dependency ledger

| Dependency or mechanism | Selected revision | Grounding | Required use |
|---|---|---|---|
| Bun | `d8ecf098572e2b8265b23e40c04efb4067e516cc` | `reference-code/bun/docs/runtime/child-process.mdx:232-284`; `src/seon/execution.cljs`; `src/seon/execution/host.cljs` | Existing bidirectional child IPC; Seon's Transit-string boundary remains narrower than native structured clone. |
| Transit CLJS | `3d8a2c49ff1911fd7adfacce2776c3a6b8cc1fce` (`com.cognitect/transit-cljs` `0.8.280`) | `reference-code/transit-cljs/`; `src/seon/execution.cljs:163-198` | The one message codec. Every new frame round-trips through it and satisfies `ordinary-wire-value?`. |
| Orchard inspect | `c462a25d97988f1af51e8181265c43ec9b7d3d6f` | `reference-code/orchard/src/orchard/inspect.clj:44,96-141,150-200` | Source precedent for path descent and head-plus-one paging. Unit 1F translates the mechanics once; Unit 1G does not reimplement them. |
| ClojureScript | `946d75f3483c0c8e784e6668bff2c71a25619a77` | `reference-code/clojurescript/`; `src/seon/eval.cljs:1523-1581` | Establishes child-local live identity and the existing bounded result slot. |
| SCI/JVM host | repository HEAD `0fee3873` | `src/seon/host.clj:225-285,316-455`; `src/seon/host/context.clj:930-970`; `docs/prds/sci-execution-runtime/roadmap.md` U4/U5 | Host-tier evals execute in a forked SCI context. Wire-safe values are returned, but no live eval-id slot exists yet. |
| One execution dispatcher | repository HEAD `0fee3873` | `src/seon/execution/host.cljs:600-820`; lane-keyed `::children`/`::host-sessions`, shared claim/settle/cancel queue | Sampling must use this owner and its current retained session; it must not add another process registry, queue, or transport selector. |
| Ordinary-data law | repository HEAD `0fee3873` | `src/seon/db/protocol.cljc:111-174`; `src/seon/execution.cljs:177-256` | Requests and results reject functions, lazy values, promises, handles, and host objects before and after encoding. |
| Public drill population | Unit 1E `c1618e22`, boot repair `dc968c35` + `0fee3873` | `src/seon/render/value.cljs:112-228`; `src/seon/config.cljs:1188-1238` | Closed producer-neutral request/result and the single effective-limit normalizer. No transport-local copies. |
| Descent/page producer | **Unit 1F, not yet frozen at this audit** | Must land in `src/seon/render/value.cljs` with focused tests | Sole owner of lookup, refusal, page realization, schema projection, and deep bounded validation. Unit 1G calls it directly. |
| HTTP codec ruling | `c932c9e1` | [[value-route-path-codec-boundary-2026-07-20]]; `src/seon/web/serve.cljs`; `test/seon/web/serve_test.cljs` | Later route unit only. No EDN reader or URL logic enters Unit 1G. |

The source revisions above are the checked-out vendored heads, not remembered
APIs. The repository was dirty in unrelated SCI/AI owners during this audit;
none of those paths are changed here.

## Current built state

- `seon.render.value` registers closed shallow `path`, `effective-limits`,
  `drill-request`, `drilled-projection`, and available/unavailable/failed
  result shapes. Scalar predicates reject non-finite numbers and negative
  zero; `seon.config/effective-value-drill-limits` monotonically narrows the
  32-segment, 4,096-byte, and 1,024-realized-item host caps.
- The Bun execution protocol is version 3 with closed invoke/cancel/shutdown
  and ready/result/error/stopped unions. `encode-message` and `decode-message`
  enforce eager ordinary data. Child dispatch has exactly one `::active`
  request and terminal settlement is token- and invocation-correlated.
- `seon.execution.host` has one per-agent FIFO and one lane-keyed child/session
  state mechanism. Result settlement checks generation, child id, artifact
  digest, pinned database value, and optional run fence.
- Bun child evals have `seon.eval/lookup-result`, which distinguishes a live
  result, a failed/nonexistent eval, and a prior-process or evicted slot.
- Host-tier evals now run in `seon.host`. Their raw wire-safe values exist
  during `eval-batch-result`, and durable rows receive bounded
  `:seon.eval/result-edn`, but the JVM process does not retain a raw value by
  eval id for later drill.

## Exact gap and public frames

After Unit 1F freezes its public producer, extend the existing version-3
protocol with three correlated frames. Names may be finalized in source, but
the data must translate directly rather than copy the public drill shapes:

```clojure
{:seon.execution/message :seon.execution.message/value-sample
 :seon.execution/protocol-version 3
 :seon.execution/agent-id "agent-id"
 :seon.execution/request-id "uuid"
 :seon.execution/eval-id "eval-id"
 :seon.render.value/path [...]
 :seon.render.value/offset 0
 :seon.render.value/effective-limits {...}}

{:seon.execution/message :seon.execution.message/value-sample-result
 :seon.execution/protocol-version 3
 :seon.execution/agent-id "agent-id"
 :seon.execution/request-id "uuid"
 :seon.render.value/result <one :seon.render.value/drill-result>}

{:seon.execution/message :seon.execution.message/value-sample-error
 :seon.execution/protocol-version 3
 :seon.execution/agent-id "agent-id"
 :seon.execution/request-id "uuid"
 :seon/error <closed bounded seon.render.value drill error>}

```

Do not overload compiled `invoke`: sampling is core runtime control, has no
authored function/source identity or database-program installation, and must
never pass the raw value as an invocation result. Do not reuse
`invocation-id` merely to satisfy current settlement code; add the concrete
`request-id` correlation named by this operation and strengthen the existing
active-request union and queue in place.

The parent-facing function belongs in `seon.execution.host`, accepts a closed
agent/eval/drill request, chooses only the already-retained serving lane, and
returns `drill-result` as data. It never spawns a process. The child/JVM peers
validate the same public drill request again, resolve the live value locally,
call the Unit 1F producer, validate the bounded result, then encode it.

## Work, refusal, error, and lifecycle laws

- Parent admission occurs before lane lookup or send: closed request, legal
  scalar path, segment cap, safe canonical offset, positive page size,
  monotone effective limits, and checked `offset + page-size <= max-realized`.
- Serving-runtime admission repeats every decoded-data and work check before
  `lookup-result`, path descent, or collection touch. A narrower runtime policy
  is an explicit bounded refusal, not silent reclamping that changes bytes.
- Unit 1F is the only descent/page implementation. A successful sequence/set
  page touches at most `offset + page-size + 1`; an excessive request touches
  zero. Arbitrary map tails remain honestly non-pageable with offset zero.
- The returned value is exactly the closed `drill-result`; it contains no raw
  eval value, lazy sequence, database value/handle, Promise, function, SCI var,
  JavaScript object, or throwable.
- Missing retained runtime, evicted value, prior process, retirement, restart,
  or mid-request exit settles one honest unavailable result. Sampling never
  spawns, retries on a fresh runtime, or falls back to persisted
  `result-edn` as though it were the live value.
- Sampling joins the existing per-agent FIFO and active request mechanism. It
  cannot overtake an invocation. Cancel, shutdown, timeout, malformed frame,
  send failure, and exit settle once and release the tail.
- Settlement remains guarded by agent id, request id, lane, generation,
  child/session id, and artifact digest. A stale response cannot settle a
  current request.
- The parent does not call `lookup-result` and the serving runtime does not
  authorize the route. Eval-to-agent authorization remains the later route
  owner's database join.
- A JVM retention slot is process-local, bounded by the same eviction policy
  as the existing Bun result slot, and keyed by the managed eval id. It is not
  durable state and must be cleared with its SCI context/session lifecycle.

## Shortest falsifiers

1. Transit-round-trip all three frame variants through the actual codec;
   reject unknown keys, unsupported path elements, lazy values, Promises,
   functions, host objects, and oversized/error data.
2. Spy on the Unit 1F producer. One valid request calls it exactly once with
   byte-identical effective limits; every malformed or over-work request calls
   it zero times in both parent and runtime.
3. Use a counter-backed infinite sequence at offsets zero and nonzero. Assert
   exact source touches never exceed `offset + page-size + 1`; poison the next
   element and prove it is untouched.
4. Request a missing child/session and prove zero launches and one unavailable
   result. Retire the serving runtime mid-request and prove one settlement, no
   retry, no stale acceptance, and the existing recomputation meaning.
5. Queue sample behind an active invocation and invocation behind a sample.
   Prove FIFO, cancel, timeout, shutdown, send failure, and process exit leave
   neither an active entry nor an invocation tail.
6. For a Bun-tier eval, make parent `lookup-result` throw if called and prove
   the child resolves the original process-local object identity.
7. For a JVM-host-tier eval, retain one raw SCI value by its managed eval id,
   drill it in that same host session, then park/replace the session and prove
   the old id is unavailable. Assert no `result-edn` reparse occurs.
8. Flip an agent's tier fact after an eval. The old eval must never be sampled
   from a freshly selected runtime merely because it is current; it is either
   addressed to its recorded retained owner or honestly unavailable. This is
   the shortest falsifier for the unresolved ownership-address problem.
9. Return an honestly narrower child/JVM limit set than the parent's admitted
   request and prove a deterministic refusal rather than divergent sampling.
10. Run the focused execution, host, JVM-host, eval-result, and renderer tests,
    then a live matrix: one Bun-tier large value and one JVM-tier large value,
    page both, retire both owners, and observe honest unavailable projections.

## Ownership and protected paths

Unit 1G implementation ownership, after explicit handoff and freeze:

- `src/seon/execution.cljs` and `test/seon/execution_test.cljs` — frame unions,
  runtime validation/dispatch, Bun-local lookup and sampling;
- `src/seon/execution/host.cljs` and
  `test/seon/execution/host_test.cljs` — one queued active-request transport,
  retained-lane addressability, correlation, retirement, and settlement;
- the minimum existing eval-result tests needed to prove
  `seon.eval/lookup-result` use, without changing eval semantics;
- `src/seon/host.clj`, its existing focused tests, and the existing JVM host
  context owner only if the recommended cross-tier ruling is accepted —
  process-local eval-id retention and the same request/result frames.

Protected from Unit 1G:

- `src/seon/render/value.cljs`, `src/seon/config.cljs`, and their tests: Unit
  1F must hand over frozen public behavior; transport does not alter it;
- `src/seon/web/serve.cljs`, route data, router tests, and all UI source: HTTP
  parsing, authorization, selector semantics, response headers, and controls
  are later units;
- database schema/query owners except reading already-frozen routing facts;
- AI, turn, retry, prompt rendering, program graph, and authored invocation
  semantics;
- any new codec, process registry, value store, paging helper, or compatibility
  namespace.

Because `execution.cljs`, `execution/host.cljs`, and `host.clj` are active SCI
runtime owners, this is a coordinated source-freeze unit. The primary
orchestrator must obtain explicit path handoff before edits and run the
cross-transport proof at one artifact digest.

## Dependency exit and next refill

Unit 1G may start only when Unit 1F has committed and proven:

- one public producer from decoded `drill-request` plus live value to closed
  `drill-result`;
- bounded deep validation and zero-touch refusals;
- exact path descent and map/vector distinction;
- honest sequence/set paging and non-pageable arbitrary-map omission;
- stable schema projection bytes and no reconstruction of display-only keys.

The earliest dependency-ready implementation boundary is then: **record and
rule the JVM-host live-value ownership gap, freeze the Unit 1F producer, and
add the three closed value-sample frames plus one shared queued settlement
path across the already-retained Bun/JVM serving lanes.**

After Unit 1G freezes, the next unit is the route seam: implement the strict
HTTP path codec privately in `seon.web.serve`, seed
`GET /agent/{id}/value`, authorize eval ownership from one acquired immutable
database value before host send, and implement the parent-owned entity branch.
