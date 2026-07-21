---
type: research
status: complete
tags: [research, web, rendering, architecture]
---

# Value-route implementation readiness (2026-07-20)

## Decision

The Stage 1.5 route/auth unit is source-ready only after Unit 1G freezes its
parent-facing sampling function and four crossing questions below receive
explicit rulings. The route remains one database-seeded read-only
`GET /agent/{id}/value` handler in `seon.web.serve`. It does not add a static
route, middleware, query library, value registry, paging implementation, or
execution transport.

The handler translates two producers into the already-frozen
`seon.render.value/drill-result`:

- `eval=<eval-id>` joins the eval to the path agent in one immutable database
  value before calling the retained execution lane; and
- `entity=<positive-eid>` is accepted only under `/agent/root/value`, reads the
  entity from the parent-owned immutable database value, and never calls an
  execution host.

Missing and cross-agent evals have the same `404` response and perform zero
host sends. Syntax or budget failures are bounded `400` user-input values.
Available and honestly retired or evicted results are `200`; a distinct
transport or core failure is `503`. Every response is `Cache-Control: no-store`
and supplies no cross-origin resource-sharing header.

## Dependency ledger

| Dependency or mechanism | Selected revision and grounding | Existing Seon consumer | Required use |
|---|---|---|---|
| ClojureScript EDN reader and printer | `org.clojure/clojurescript` `1.12.145`, vendored `reference-code/clojurescript` at `946d75f3483c0c8e784e6668bff2c71a25619a77`; `src/main/cljs/cljs/reader.cljs:125-180`, `src/main/clojure/cljs/vendor/clojure/tools/reader/edn.clj:380-440`, and `src/main/cljs/cljs/core.cljs:10450-10480` | `src/seon/web/serve.cljs` already parses request URLs with WHATWG APIs | Use a pushback reader and `cljs.tools.reader.edn/read` twice with a private EOF sentinel, `:readers {}`, and no default. Compare decoded text exactly with `pr-str`; reject non-finite numbers and negative zero. Never use the mutable global tag table or one-form `cljs.reader/read-string`. |
| WHATWG URL parsing | Bun's request `URL` and `URLSearchParams`; current adapter in `src/seon/web/router.cljs:93-113`, query helpers in `src/seon/web/serve.cljs:190-218`, and tests in `test/seon/web/router_test.cljs:54-73` | The Ring adapter retains both the original Request and raw `:query-string` | Iterate decoded entries to detect aliases and duplicates. Scan raw framing only to validate `%HH`, correlate the one path component, and measure its UTF-8 bytes before decoding. Do not write another decoder. |
| Reitit route data | `metosin/reitit-ring` and `reitit-malli` `0.10.1`; vendored `reference-code/reitit` at `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab`; `modules/reitit-ring/src/reitit/ring.cljc:121-148,360-390` | `src/seon/route.cljs:43-113` owns seeded facts; `src/seon/web/router.cljs:161-227,451-486` compiles them and late-resolves Promise-capable handlers | Add one ordinary GET route row. The existing projection and late-binding path requires no static supplement or special dispatcher. |
| Transit CLJS | `com.cognitect/transit-cljs` `0.8.280`, vendored `reference-code/transit-cljs` at `3d8a2c49ff1911fd7adfacce2776c3a6b8cc1fce` | `src/seon/execution.cljs:163-205` owns `encode-message` and `decode-message`; `src/seon/db/protocol.cljc:111-174` owns ordinary-wire admission | Codec tests pass admitted path scalars through the actual execution codec. The route adds no Transit writer, reader, or handler registry. |
| Value drill request and result | Units 1E/1F and portability checkpoint `7c124879`; `src/seon/render/value.cljc:112-214,1417-1580` | `seon.render.value/drill-value` accepts an explicit schema projection, live value, and closed drill request; `seon.config/effective-value-drill-limits` owns normalization | Translate directly into `:seon.render.value/drill-request` and consume `:seon.render.value/drill-result`. Do not copy schemas, defaults, validators, descent, or paging. |
| Configuration policy | `src/seon/config.cljs:1188-1238`; shipped caps are 32 decoded segments, 4,096 raw encoded path bytes, and 1,024 total realized items | `seon.config/effective-value-drill-limits` monotonically narrows host policy with optional operation limits | One resolved effective-limit map must govern parent and retained runtime byte-for-byte. Query fields cannot widen it. |
| Committed schema projection | JVM/pod crossing `414b8137`; `src/seon/schema.cljc` owns `projection-from-rows`, projection-explicit candidate/match/explain, and explicit drill projection | `seon.render.value/drill-value` no longer accepts an ambient projection | Entity selection and schema interpretation must name one basis-consistent projection. The route may not infer a projection from namespace load order. |
| Execution sampling transport | Unit 1G, still active at this audit; prior boundary `unit-1g-value-sampling-transport-implementation-readiness-2026-07-20.md` | `src/seon/execution/host.cljs` is the one lane-keyed retained-runtime dispatcher | Consume the exact committed parent sampling function after handoff. Never spawn, retry, select a fresh tier, call `lookup-result` in the parent, or reparse stored result EDN. |

## Exact route and authorization seam

`src/seon/route.cljs` adds one row:

```clojure
{:seon.route/pattern "/agent/{id}/value"
 :seon.route/method :get
 :seon.route/name :seon.route/agent-value
 :seon.route/handler 'seon.web.serve/value!}
```

The final public symbol name may follow the owning namespace's established
handler naming, but the database row is the sole route authority. It carries
no `:seon.route/same-origin`, `:seon.route/loopback-peer`, admission,
autonomous-client, or `/call` capability gate. This GET performs no write;
the loopback bind and browser same-origin policy remain the read boundary.

The eval authorization query is exactly:

```clojure
[:find ?eval .
 :in $ ?eval-id ?agent-id
 :where
 [?eval :seon.eval/id ?eval-id]
 [?eval :seon.eval/agent ?agent]
 [?agent :seon.agent/id ?agent-id]]
```

No match covers both an unknown eval and an eval belonging to another agent.
It returns the same `404` before the retained-runtime sampling function is
called. Caller-supplied query data never supplies the trusted agent id.

The entity selector is a canonical positive base-10 safe integer. It is valid
only when the path agent is `root`; `root` is the route namespace, not invented
entity ownership. The same immutable database value supplies configuration,
entity existence/value, and any schema rows required by the selected
projection. An entity/database handle never crosses execution IPC.

The parser accepts exactly one `eval` or `entity`, plus optional `path` and
`offset`. `path` defaults to canonical `[]`; `offset` defaults to canonical
`0`. It rejects duplicate or unknown fields, both or neither selector,
malformed percent escapes, noncanonical or trailing EDN, tags, unsupported
segments, excessive encoded bytes or segments, noncanonical/negative/unsafe
offsets, checked-add overflow, and `offset + page-size` beyond the resolved
work maximum.

The initial path grammar is nil, booleans, finite non-negative-zero numbers,
strings, keywords, and symbols. A numeric map segment remains its exact key;
vector descent additionally requires a non-negative safe integer. Sequences
and sets are pageable views, not index-addressable paths. Display-projected
map keys never reconstruct request paths.

## Four required rulings

### 1. Freeze the Unit 1G handoff

At repository commit `d3f1ec6b`, Unit 1G had not committed a parent-facing
sampling entry point. The route must wait for the exact function, closed input,
result, unavailable, timeout, and retirement semantics from that unit. It must
not call a private execution registry or guess the eventual function name.

### 2. Reconcile configuration with zero-work refusal

The existing research requires effective limits from the database-owned
configuration singleton, but also says an over-limit request refuses before
database acquisition. There is no public ambient configuration accessor, and
a route-local/injected configuration cache is explicitly forbidden.

Recommended ruling: perform parameter multiplicity, raw percent-framing, and a
fixed absolute framing ceiling before acquisition; then acquire one immutable
database value, decode its configuration singleton once, and run configured
byte/segment/offset/work admission before selector query, host send, descent,
or realization. In acceptance language, “zero database lookup” means zero
selector/entity/program work after the one policy acquisition. If literal
zero acquisition is required instead, the existing configuration owner must
expose the already-installed immutable operation configuration; the route may
not invent that facility.

### 3. Pin the entity to its schema projection

`drill-value` requires an explicit projection, but `schema/current-projection`
does not expose the database basis it represents. Combining it with a newly
acquired entity can race schema publication and becomes especially ambiguous
while execution admission is unavailable, when entity reads must still work.

Recommended ruling: acquire the configuration, committed schema/function rows,
and entity or authorization selection at one database value, construct the
projection through the portable `schema/projection-from-rows`, and pass that
exact projection to `drill-value`. A cheaper basis-fenced active-projection
accessor is acceptable only if its public contract proves the exact database
basis. An ambient projection with no basis proof is not acceptable.

### 4. Freeze the HTTP representation

The authorization audit says the route returns the ordinary bounded
projection. The later UI audit says a click updates the existing view with
bounded markup and no new feed. Neither freezes a media type, serialization,
or Datastar patch response.

Before implementation, choose one crossing: either a closed ordinary EDN body
(`application/edn`) consumed and rendered by the later existing server/UI
mechanism, or the existing value-panel HTML carried in the established
Datastar patch format. The choice must name the exact body and tests. The route
unit and UI unit may not independently create two representations.

## Ownership

After the four rulings and Unit 1G handoff, the route unit owns only:

- `src/seon/route.cljs`;
- `src/seon/web/serve.cljs`;
- `test/seon/route_test.cljs`;
- `test/seon/web/serve_test.cljs`; and
- `src/seon/web/router.cljs` plus `test/seon/web/router_test.cljs` only if a
  focused dispatch assertion exposes an actual adapter gap. The seeded row
  should otherwise require no router-source edit.

Protected from this unit:

- `src/seon/execution.cljs`, `src/seon/execution/host.cljs`, `src/seon/eval.cljs`,
  `src/seon/host.clj`, and host context/tests owned by Unit 1G;
- `src/seon/render/value.cljc`, `src/seon/config.cljs`, `config/system.edn`, and
  their tests, whose request/result/limit contracts are frozen;
- `src/seon/schema.cljc` unless the orchestrator separately assigns the ruled
  basis-fenced accessor;
- `src/seon/render.cljs`, `src/seon/web/debug.cljs`,
  `src/seon/web/datastar.cljs`, `src/seon/handlers/eval.cljs`, and their UI
  tests, which belong to the later consumer cut; and
- AI, turn, retry, authored invocation, route-authority collapse, operator,
  retained-branch, and B2 artifact owners.

## Acceptance

### Focused commands

```bash
bin/test-cljs --test=seon.route-test
bin/test-cljs --test=seon.web.router-test
bin/test-cljs --test=seon.web.serve-test
```

At the integration freeze, rerun the focused renderer and execution-host gates
selected by Unit 1G, then the complete existing CLJS suite:

```bash
bin/test-cljs
```

No overlapping CLJS suite runs inside the live pod.

### Pure and server falsifiers

- Table-test canonical nil, boolean, number, string, keyword, and symbol paths;
  actual Transit round trips retain lookup equality. Reject aliases,
  whitespace, comments, commas, metadata, discards, tags, nested collections,
  unsafe numbers, non-finite numbers, and negative zero.
- Reject duplicate and percent-aliased names, unknown fields, malformed `%HH`,
  excessive raw bytes or segments, invalid offset, checked overflow, and total
  work crossing. Every refusal performs zero selector query, execution send,
  descent, and realization; policy acquisition follows ruling 2.
- Request agent A's eval through agent B and request a nonexistent eval. Both
  return the same `404` and the sampling spy remains exactly zero.
- Select an entity under `root`; prove configuration, entity selection, and
  schema projection use one immutable database value and the host-send spy is
  zero. Non-root entity selection and unknown entities are uniformly absent.
- Requests with absent and hostile `Origin` headers follow the same read result,
  emit `Cache-Control: no-store`, and emit no CORS header.
- Authorized page zero and nonzero requests return deterministic bounded bytes;
  work instrumentation proves at most `offset + page-size + 1` touches rather
  than merely checking output size.
- Retirement of the real owning execution lane returns one `200` unavailable
  projection with recomputation meaning, never a retry, spawn, raw-value
  crossing, or persisted-result reparse. A true transport/core failure remains
  `503`.

### Live and browser boundary

Before the UI cut, use server-side HTTP requests against one exact frozen pod
to prove canonical eval/entity reads, hostile refusals, paging, cross-agent
absence, and real lane retirement. Do not count a mocked eviction as the
retirement proof.

The later UI unit owns the real-browser evidence: nested eval paging morphs the
existing `#app-view`; `/data` drills an entity without an execution send;
projected keys display no drill control; and retired values render visibly
unavailable. Verify long-lived SSE server-side because the browser bridge may
return `503` for event streams.

## Dependency exit

This route unit becomes implementable when Unit 1G commits its exact sampling
handoff and the orchestrator records rulings for configuration admission order,
projection basis, and HTTP representation. Until then, source work would have
to invent a boundary and should not start.
