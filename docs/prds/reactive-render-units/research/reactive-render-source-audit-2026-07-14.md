---
type: research
status: completed
tags: [research, web, database, flow]
---

# Reactive render source audit — 2026-07-14

## Conclusion

Seon has the correct transport and most of the correct read-observation
primitives, but it does not yet have one render-unit transition. Three owners
split one state machine:

- `seon.web.datastar` owns sockets, normalized subscriptions, gzip framing,
  coalescing, active-unit sets, lazy activation, and a generic whole-view
  observed transition;
- `seon.ui.agent-view` owns a second page-specific dependency map and
  transition whose declared-attribute gate can veto exact runtime reads; and
- `seon.web.view-unit` owns only canonical coordinate tokens, not unit
  lifecycle, observations, result reuse, output suppression, or cleanup.

Debug and `/data` now use the shared gzip feed, so the older duplicate SSE
registries are gone. They still use the generic whole-view transition while
agent/root pages use the page-specific transition. Root is still the ordinary
agent layout with `seon.render.system/system-view` as its canvas, so a system
projection is materialized into both expanded and compact faces rather than
compiled as independent fleet units.

The first implementation slice must make runtime observations the only live
correctness authority. Replay all active observations before adding a candidate
index. The immediate current gate is cheap to delete and unsound to preserve:
`seon.ui.agent-view/transition` intersects changed attributes with the
non-transitive `:seon.fn/read-attrs` projection before it asks whether captured
results changed. The exact captured helper reads are present but may never be
consulted.

Active-unit reuse needs no new library. Retain one plain-data derivation per
active normalized unit and delete it after the final consumer closes. Add
`lru-cache` only if reopen profiling later proves material value. No database,
entity, connection, SCI context, or mutable host value belongs in a key or
retained result.

## Scope and method

This audit reconciled current source and tests with the architecture target and
these earlier runtime-reliability reports:

- `reactive-ui-dependency-routing-2026-07-12.md`;
- `sci-render-cache-source-audit-2026-07-12.md`;
- `datastar-sse-render-allocation-profile-2026-07-12.md`;
- `eval-render-fanout-design-2026-07-13.md`;
- `clj-cljs-bounded-cache-library-audit-2026-07-14.md`; and
- `root-reactive-system-view-audit-2026-07-14.md`.

Read-only probes used only the default cluster. No database facts were written,
no feed was opened or closed, and ACME was not touched. The probe rendered the
current root view once, inspected immutable dependency data, and inspected the
already-open root debug subscription.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source read | Constraint for this PRD |
|---|---|---|---|
| Datahike | maintained git SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc` in both `:writer` and `:cljs` | `reference-code/datahike` at the same SHA; indexes/listeners in `src/datahike/core.cljc:166-224`; conservative query dependencies and bounded result cache in `src/datahike/query.cljc:2366-2594,4033-4064` | A db is an immutable value. Capture normalized request/result data, use effective datoms and index prefixes as optional conservative hints, and compare results for correctness. Do not expose or copy Datahike's private cache as the UI cache. |
| Konserve replica backing | maintained git SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve` at the same SHA | Each pod dereference may reconstitute a distinct immutable value. Snapshot once and thread it; identity mismatch currently makes debug reads foreign. |
| SCI | `org.babashka/sci` `0.13.53` | `reference-code/sci` tag `v0.13.53`, SHA `b4917436550c857a18b8f6a4a8b5b26356acc2c4`; `sci.core/init` and `fork` at `src/sci/core.cljc:263-281`, interpreter entry at `src/sci/impl/interpreter.cljc:70`, interrupts at `src/sci/interrupt.cljc` | Keep a fresh interpreter context per agent-authored render miss. Cache only successful plain output/read facts, never an SCI context, var, atom, deadline, or host value. Avoiding an invocation is the performance win; raising the 250 ms budget is not. |
| ClojureScript | selected `1.12.145` | current `reference-code/clojurescript` SHA `946d75f3483c0c8e784e6668bff2c71a25619a77` identifies itself as `1.12.41`; exact selected source is missing. Relevant unchanged paths are `cljs/analyzer.cljc`, `cljs/core.cljc:975-977`, and `cljs/js.cljs:724,1138` | The analyzer program graph may supply source/renderer digests and cold focus hints. Source-literal keyword reads are not a live dependency graph. Exact `1.12.145` source must be mirrored before analyzer-sensitive implementation. |
| Shadow CLJS | selected `3.4.10`; exact release commit is `d3c04691952aa9ea33f7287ffe9a2b3109c1e510` | `reference-code/shadow-cljs` contains that commit; its parent `2911c908…` is still `3.4.9`, and the working checkout is later SHA `8236315af7426ba505aad6102dea1c4ccb1fe412` | Shadow owns AOT build/analyzer state and hot reload. It is not a second dependency observer. Renderer/source changes must invalidate through the one program publication/digest transition. |
| Datastar and idiomorph | vendored client described by source as RC.7; `resources/public/js/datastar.js` SHA-256 `c9c8b99715d759df4543d4e01d6e6fe4b3940e4dee57ec9cde7eb344e86c61e2` | `reference-code/datastar` SHA `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` (`v1.0.0-RC.7-8`), especially `library/src/plugins/watchers/patchElements.ts`; Clojure SDK `reference-code/datastar-clojure` SHA `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2`, especially `libraries/sdk/src/main/starfederation/datastar/clojure/api/elements.clj` | One event may contain several complete stable-ID elements; default outer morph finds targets by id. Preserve this protocol. Lazy activation must return a Datastar event, not bare HTML. |
| Hiccup serialization | first-party `seon.ui.html`; no external Hiccup dependency | `src/seon/ui/html.cljc` and `test/seon/ui/html_test.cljc` | Serialization is deterministic because attributes are sorted and text is escaped. The unit cache retains the final serialized element, not a large hidden Hiccup forest. Attribute, escape, raw, void-element, sequence, and stable-order behavior already have direct tests. |
| gzip and SSE | Node `26.4.0` built-in `node:zlib`; no npm package | `src/seon/web/datastar.cljs:275-401,1004-1064`; Datastar Clojure gzip profiles in `reference-code/datastar-clojure/libraries/sdk/src/main/starfederation/datastar/clojure/adapter/common.clj` | Keep `Z_SYNC_FLUSH`, one heartbeat timer, error/close/abort cleanup, and latest-event-only backpressure per socket. Transport bounds queued output only; it cannot bound renderer allocation. |
| Token estimation | first-party `seon.ai.tokens`, four characters per estimated token | `src/seon/ai/tokens.cljc` | Human-visible output and cache weights use `estimate`, never raw characters. Any recent LRU is bounded by entry count, total estimated output tokens, and per-entry tokens. |
| Optional recent LRU | not selected or installed; candidate `lru-cache` `11.5.2`, SHA `16b3a916662ab449d496b7b4b4f04132565d1d28` | grounded by `clj-cljs-bounded-cache-library-audit-2026-07-14.md`; no local `reference-code/lru-cache` checkout and no package dependency today | Do not add it before active-only metrics prove reopen/cross-subscription value. JS object keys use identity, so a later wrapper must use a canonical scalar/digest plus collision-check data. |

The selected dependency source gap is recorded by correcting
`bootstrap-analyzer-api-emits-undeclared-var-warnings.md`: the existing issue
had incorrectly called the `1.12.41` checkout the exact `1.12.145` source.

## Current owners and tests

### Database read observation

`seon.db/capture-reads` runs a synchronous thunk and retains normalized
immutable operation/request/result facts. `read-observation-changed?` replays
query, index, reverse-index, installed-schema, pull, touched-entity, and basis
reads without recursively recording. Foreign, lazy, temporal, malformed,
unknown, or otherwise unsafe observations return changed conservatively.

This is the right correctness primitive. It intentionally is not a result
cache and does not retain a database handle. It currently lacks a public
runtime-derived conservative attribute/entity/index descriptor for candidate
selection; therefore replay-all is the safe first policy.

Direct coverage lives in `test/seon/web/datastar_test.cljs` under
`observed-transition-replays-real-read-results`. It proves replay and unchanged
suppression for a whole-view thunk, not shared replay across several units or
helper-indirected agent-view invalidation.

### Transport and subscription sharing

`seon.web.datastar/!feeds` has one map of socket-owning views and one map of
normalized subscriptions. A subscription key is the semantic feed key plus an
order-independent active-token fingerprint. Equivalent sockets share
`render-change`, dependencies, the initial full event, last emitted event, and
fan-out. Final detach removes the subscription. The coalescer retains earliest
`db-before`, latest `db`, effective datoms/attribute index, a normal 16 ms
settle, 300 ms structural settle, and a 500 ms maximum deadline. Running eval
record commits are held for the terminal semantic frame without delaying an
already scheduled domain frame.

The transport tests are strong and should stay in
`test/seon/web/datastar_test.cljs`: normalized sharing, first-paint sharing,
first-socket close, obsolete ownership, identical-event suppression, frozen
feeds, complete coalesced windows, maximum deadline, listener cleanup, gzip
heartbeat, latest-wins backpressure, reconnect/final close, event framing, and
guarded view rendering.

### Agent and root transition

`seon.ui.agent-view/render-agent-view` materializes every present surface once
and derives both compact and expanded faces. It captures each surface, system
header, and agent header separately, but packages them in a custom dependency
map containing:

- declared surface attributes;
- hand-maintained structural attributes;
- hand-maintained header and agent-state attributes; and
- runtime observations for each surface/header.

`transition` takes the wrong decision order. Structural attributes force a
whole shell. Otherwise a surface is considered only when changed attributes
intersect its declared source-literal set; exact replay follows that gate. Plan,
transcript, and `system-view` delegate to helpers, so their real observed reads
are wider than the wrapper's declared set. A fresh render is correct while an
already-open view may be stale.

Root uses `serve-root! -> write-agent-page! "root" -> /agent/root/feed ->
agent-view/transition`. `system-view` is root's selected canvas. The agent layout
serializes each surface into both primary and rail faces; CSS disclosure hides
one but does not save compute, serialization, transport, or DOM allocation.

`test/seon/ui/agent_view_test.cljs` thoroughly covers catalog selection,
materialization once, focus recency, pinning, scoping, missing content, and
status headers. It does not reproduce a helper-indirected plan/transcript/fleet
read on an already-open transition. General catalog/focus tests ultimately
belong with `seon.render.surface`; page composition tests remain with the agent
layout; custom dependency-map assertions should be deleted after cutover.

### Debug and data

Both pages now use the shared Datastar registry and gzip stream. Debug has a
cheap GET shell, a catalog of raw/HTML detail descriptors, and inactive stubs;
`/data` derives a bounded query projection and exact feed key. This is a real
improvement over the retired second SSE registry.

Both feeds still use `datastar/render-observed` and
`transition-observed` around a whole page. Debug's render thunk dereferences the
connection independently of the database passed to capture. The live open root
debug subscription consequently held 657 observations and all 657 were
non-replayable. That defect is recorded in
`debug-feed-captures-foreign-database-reads.md`.

Lazy activation has a separate correctness hole. `handle-view-unit!` invokes
the producer outside capture and rebinds the view while retaining the inactive
subscription's dependencies. A fact read only by the newly active producer may
not select the next whole-view render. That defect is recorded in
`lazy-view-unit-activation-drops-read-observations.md`.

Current tests prove closed debug producers do not run, active producers run
once during the activation response, exclusive activation, deactivation stubs,
catalog reconciliation, and unknown token refusal. They do not prove an
activated producer updates after its own helper-indirected fact changes.

### SCI and serialization

`seon.render.sci/invoke-bounded` reconstructs required namespaces, creates a
fresh SCI context, evaluates stored source, invokes the interpreted function,
and deep-realizes the result for every agent-authored render. Core functions are
compiled. This isolates interpreter mutable state and bounds interpreted
loops, but compiled host calls may still allocate or block beyond the
interpreter checkpoint.

The SCI tests cover unspecced helpers, own-namespace values, dynamic vars,
failure envelopes, warning suppression, and persisted require edges. Canvas
tests cover content precedence, configured default, compiled/literal/twin
rendering, SCI errors, malformed Hiccup, serializer backstop, and compact face
bounds. The unit engine should count avoided SCI invocations; it must not cache
or fork interpreter contexts.

## Live default baseline

The default cluster was ready with watcher, writer, and pod alive. The
read-only CLJS probes observed:

| Probe | Result |
|---|---|
| Database coordinate | basis `536870929`, 15,851 datoms |
| One `render-agent-view` for root | about `313.0 ms` |
| Root surfaces | 3: canvas, plan, transcript |
| Declared attribute counts | canvas 1; plan 2; transcript 2 |
| Captured observation counts | canvas 89; plan 15; transcript 9 |
| Header observations | system 74; agent 2 |
| Existing open root debug subscription | 1 view, 1 subscription, 657 observations |
| Debug replayability | 0 replayable, 657 non-replayable |

The debug operation distribution was 382 lazy entity, 129 query, 55 touched
entity, 44 installed-schema, 20 pull, 16 history, 6 basis, 4 reverse-index, and
1 index observation. This explains why the current whole-debug transition is
both always dirty and expensive. It does not prove that each operation is
individually necessary; unit decomposition and explicit bounded reads must
reduce the plan.

Earlier reproducible baselines remain relevant:

- ordinary transitions commonly cost 100–200 ms even while missing the
  intended helper-indirected surface;
- open debug renders have ranged from roughly 220 ms to 1,100 ms;
- the grown-store allocation profile saw repeated 134–208 ms broadcasts after
  one transcript bound, versus earlier 330–365 ms paths;
- plan, transcript, and canvas materialization measured about 77, 54, and 28
  ms in one profile, while the hidden expanded transcript constructed roughly
  93,986 estimated tokens to emit about 618; and
- RSS returned after idle GC, supporting transient eager allocation pressure
  rather than an established retained leak.

## Data-oriented unit state and coordinate

One active unit is ephemeral process data, not a database entity. Its identity
is a fully namespaced plain coordinate whose values are stable scalars. The
minimum state is:

```clojure
{:seon.web.view-unit/coordinate
 {:seon.route/name :seon.route/agent-feed
  :seon.agent/id "root"
  :seon.web.view-unit/name :seon.web.view-unit/system-header
  :seon.db/branch :db
  :seon.db/commit-id #uuid "6a56b364-bb11-56fb-bc1f-7b010e36c159"
  :seon.db/t 536870929}
 :seon.web.view-unit/consumers #{"view-id"}
 :seon.web.view-unit/active? true
 :seon.web.view-unit/renderer-digest "..."
 :seon.web.view-unit/input-data {...small plain data...}
 :seon.db/read-observations [...normalized immutable facts...]
 :seon.web.view-unit/serialized-element "<header id=...>...</header>"
 :seon.web.view-unit/output-tokens 412}

```

The exact schema belongs in `seon.web.view-unit`, not in this report. The
coordinate separates identity from state: activation is a property of the
open view, observations/results are replaceable derived state, and consumers
are socket references. Frozen historical units use a complete attachment
coordinate, never bare `t`. Current units change their attached coordinate as
the replica advances; a database value itself never enters the map.

The transition is one pure data step around bounded effects:

1. Compile demanded descriptors from route/page facts and plain inputs.
2. Activate demanded units; inactive descriptors remain cheap stubs.
3. Capture actual reads while producing one complete stable-ID element.
4. Index conservative request descriptors to candidate active units.
5. On a batch, replay each unique candidate request once against the new db.
6. Rerender only units with unequal results or broad/unsafe observations.
7. Replace observations/index entries and serialize the new element.
8. Suppress equal serialized output; fan changed elements in one event.
9. On deactivate or final close, delete observations, result, output, and
   consumer state.

No subscription, dependency, dirty flag, output, last-seen value, or cache row
is transacted. Focus recency remains a separate approximate policy: declared
renderer attributes plus agent/REPL provenance may rank a surface, but cannot
veto live invalidation.

## Ordered implementation slices

### Slice 1 — remove the false veto

- Add one failing regression for already-open plan, transcript, and fleet
  surfaces whose wrapper calls a helper that performs the real database read.
- Change the existing agent transition to replay all captured observations for
  active surfaces. Do not consult declared attributes for correctness.
- Thread one immutable db value through debug and data render thunks.
- Capture reads during lazy activation before rebinding the active fingerprint.
- Add counters for observations checked, unique replays, dirty units,
  renderer/SCI calls, serialized units, suppressed outputs, and estimated
  output tokens.

This slice restores correctness before a reverse index exists. The number of
checks is bounded by currently open active units.

### Slice 2 — move lifecycle into `view-unit`

- Define the one unit descriptor, active state, render result, and transition
  schemas in `seon.web.view-unit`.
- Move token/catalog/activation mechanics out of `seon.web.datastar` behind
  that owner.
- Add one transaction-local replay map so identical normalized observations
  across units/subscriptions execute once.
- Retain one serialized element and observation set per active normalized unit.
- Make final-consumer cleanup structural and directly testable.
- Keep `datastar` responsible only for sockets, gzip, heartbeat,
  backpressure, coalescing, framing, and fan-out.

### Slice 3 — compile conservative candidates

- Derive candidate descriptors from captured request data: query literals and
  rules, pull pattern, resolved entity/ref, index prefix/window, plus broad.
- Unknown, dynamic, wildcard, temporal, lazy, installed-schema, basis, or
  unsafe inputs remain broad until a sound narrower descriptor exists.
- Index attribute/entity/index descriptors to active unit coordinates.
- Prove with generated transactions that hinted selection is never narrower
  than replay-all. Result equality remains the final decision.
- Do not copy Datahike's private parser. Either expose/share its conservative
  dependency projection through the maintained fork or implement only the
  operation-level facts Seon's normalized request already carries.

### Slice 4 — migrate page composition

- Agent: keep layout and surface descriptor construction; remove custom
  dependencies/transition and materialize only demanded faces.
- Root: create a dedicated root layout over shared header, fleet aggregate,
  per-agent card, preview, recovery, and activity units. Do not render root as
  its own recursive card or ordinary agent rail.
- Debug: separate header/token summary, raw prompt, source body, and HTML twin
  units. Closed details have no producer, observations, token work, or source
  materialization.
- `/data`: separate navigator, bounded result window, and selected detail units
  using the same activation and read transition.
- Canvas/context: inherit the mechanism through ordinary surface descriptors;
  expose no cache instructions to agents.

### Slice 5 — profile and choose recent reuse

- Measure active sharing, reopen opportunities, avoided SCI/render time, and
  avoided estimated output tokens across real root, agent, debug, and data
  journeys.
- If active-only reuse captures nearly all value, add no library.
- If the gate passes, add `lru-cache` `11.5.2` explicitly and mirror its exact
  source. Use a canonical scalar key plus retained collision-check data; set
  entry, total-token, and per-entry-token bounds from observed distributions.
- Cache only successful plain data/serialized output. Any exception,
  malformed entry, digest mismatch, timeout, or eviction is a cold miss.

### Slice 6 — delete and graduate

- Delete every superseded page transition, manual attribute table, generic
  whole-view observed transition, and activation path in the same cutover.
- Move general focus/materializer tests to the surface owner and replace custom
  dependency assertions with unit lifecycle behavior.
- Run focused tests, full pod gate, operator gate, reset/restart, browser
  journeys, server-side gzip feeds, reconnect, and grown-store profiling.
- Archive the issue notes only after live proof names exact stable unit targets,
  zero unrelated work, and final-consumer cleanup.

## Deletion map

| Current owner | Delete after replacement |
|---|---|
| `seon.ui.agent-view` | `::dependencies`, all declared/manual attribute schemas and sets used for invalidation, `dependencies-for`, `reads-changed?`, and custom `transition` |
| `seon.web.datastar` | unit token/catalog/active-set lifecycle after it moves behind `view-unit`; `render-observed` and `transition-observed` after every page uses the unit transition; subscription-owned whole `::full-event` as render authority once unit output state supplies first paint composition |
| `seon.web.debug` | whole-debug and whole-data render-change closures; ambient re-dereference in render thunks; any producer execution outside activation capture |
| `seon.render.surface` and analyzer tee | use of `:seon.fn/read-attrs` as live invalidation input; retain only proven focus/recency consumers until that separate policy is deliberately replaced |
| tests | custom agent dependency-map fixtures and assertions; tests that prove only activation HTTP success without a subsequent relevant database transition |

Keep the one Datahike listener, coalescer, normalized socket subscriptions,
gzip/heartbeat/backpressure implementation, stable element-id morph protocol,
surface materializer, guarded render engine, SCI boundary, deterministic Hiccup
serializer, and token estimator.

## Falsifiable regression matrix

| Scenario | Required observation |
|---|---|
| Helper-indirected plan/message/run read | Already-open unit updates; no reload or fresh feed required |
| Declared set omits actual read | Runtime observation still selects and updates the unit |
| Broad or non-replayable read | Unit is conservatively checked; it never becomes stale |
| Unrelated attribute | Zero corresponding queries, renderer/SCI calls, serialization, and patch |
| Same attribute on another agent | Candidate replay may occur; equal scoped result skips renderer |
| Change then revert within one coalesced batch | Final result equals captured result; no render or patch |
| Two equivalent tabs | One producer execution and serialized element; two socket pushes |
| First equivalent tab closes | Remaining tab keeps the same unit authority and updates correctly |
| Lazy unit activates | Producer executes once under capture; later relevant fact morphs it |
| Lazy unit closes | Stub returns; reads/output are removed; later relevant fact does no work |
| Final consumer closes | Unit and subscription state release; no database/entity value remains reachable |
| Conditional unit disappears | Shell/removal fallback removes the old stable target |
| Renderer source changes | Digest/plan changes, observations recapture, next new read updates |
| Frozen historical view | No current broadcast work; complete coordinate reproduces output |
| Equal new serialization | No Datastar event despite a legitimate rerender |
| Error or SCI timeout | One error surface; siblings remain live; recent LRU does not retain it |
| Cache disabled/zero/evicted/corrupt | Same HTML and patch sequence as cold execution |
| Root agent change | Only affected card/preview plus genuinely changed aggregate/header units render |
| Closed debug/data detail | Zero query, SCI, Hiccup, serialization, and token work |
| `/data` unrelated transaction | Current result unit stays clean; matching window change updates |
| Continuous structural writes | Latest state renders within the 500 ms maximum bound |
| Reconnect | Immediate current first paint; no browser-side numeric replay cursor |

## Profiling gates

Record these ephemeral counters by unit coordinate and broadcast, not as
database facts:

- active units, demanded/inactive descriptors, subscriptions, and sockets;
- candidate units, broad units, unique observations replayed, shared replay
  hits, dirty units, and plan rebuilds;
- query/index time, SCI setup/body time, core renderer time, Hiccup construction,
  serialization, event framing, gzip write/flush, and total event-loop delay;
- renderer and SCI invocation counts, equal-read hits, equal-output
  suppressions, emitted target count, and estimated output tokens;
- heap before/after, RSS, GC movement, and retained output-token weight; and
- recent-cache opportunities, hits, misses, inserts, evictions, overlarge
  rejects, collision misses, and avoided renderer milliseconds if the optional
  layer is trialed.

The gate for candidate indexing is not “fewer checks”; it is soundness against
replay-all plus a measured reduction in replay cost. The gate for an LRU is a
meaningful reopen/cross-subscription hit rate and avoided work after active
sharing exists. The gate for graduation is bounded p95/p99 latency and memory
under grown-store multi-view journeys with no unrelated SCI interrupts and a
stable post-GC band. Gzip bytes alone are not a render-cost measure.

## Placement of older audits

Move these reports into this PRD when documentation history is reorganized:

- `reactive-ui-dependency-routing-2026-07-12.md` — primary dependency graph and
  deletion inventory;
- `sci-render-cache-source-audit-2026-07-12.md` — SCI isolation and original
  read-capture prerequisite; retain it as historical evidence even though the
  later cache audit supersedes its `cljs.cache` recommendation;
- `datastar-sse-render-allocation-profile-2026-07-12.md` — direct cost baseline
  and eager hidden-face evidence;
- `clj-cljs-bounded-cache-library-audit-2026-07-14.md` — current active-state
  and optional-LRU decision; and
- `root-reactive-system-view-audit-2026-07-14.md` — root layout and generic
  unit design are primarily this PRD's work.

Link rather than move `eval-render-fanout-design-2026-07-13.md`. Its semantic
coalescer hold is already implemented as prerequisite transport behavior; this
PRD must preserve and regression-test it but does not own its historical
delivery. The web-session/location/navigation sections of the root audit should
also be linked from the later root-workspace-sessions PRD; moving the whole
audit here must not make this PRD own session persistence or targeted
navigation.

## Uncertainties and explicit non-claims

- The exact ClojureScript `1.12.145` source is absent from `reference-code`.
  Analyzer-sensitive implementation is not grounded until it is mirrored.
- The Datastar client is a vendored static artifact with a file digest and RC.7
  code comment, not an npm-locked dependency. The checked-out source is
  RC.7 plus eight commits; distribution work must establish a mechanical
  client-source identity, but this unit can preserve the proven protocol.
- The current observer captures request/results but not a conservative
  candidate descriptor. Replay-all is correct; any narrower query parser needs
  generated soundness proof and maintained-source ownership.
- The live 313 ms root render and 657-observation debug snapshot are one current
  default-cluster sample, not p95 or a grown-store benchmark.
- The lazy activation stale path is source-proven but was not mutated live
  because this audit was explicitly read-only and an existing user debug view
  was open.
- A 250 ms SCI interrupt bounds interpreted checkpoints, not native Datahike,
  compiled ClojureScript, JavaScript, regex, serialization, or memory
  allocation. Runtime containment remains a separate agent-runtime unit.
- No evidence currently justifies `lru-cache`; it remains a gated option, not
  planned implementation.
