---
type: prd
status: active
tags: [prd, architecture, database, agent, web]
---

# Source cleanup and vocabulary unification roadmap

This is the index of the source-cleanup PRD collection. The complete
decision surface across every detection source is [[register]]. Each domain PRD lays
out its problems, recommended solutions, acceptance, and the open owner
questions:

- [[async-facade]] — finish the async `seon.db` migration (B3-B5)
- [[config-through-aero]] — every knob through aero into database facts
- [[logging-unification]] — one line shape, agent-readable faults
- [[vocabulary-unification]] — pod retirement + remaining term rulings
- [[deletions-and-wiring]] — orphans: wire `ctx.usage`, delete the rest
- [[data-browser]] — one schema-aware rendering mechanism for every value

## Outcome

Finish the runtime-reliability refactor's deletion promise across the working
tree: every remaining synchronous consumer of the asynchronous `seon.db`
facade fixed in place, one logging surface per process, one config/default
owner per fact, the retired "pod" vocabulary gone from active source and
living docs, and dead namespaces deleted. No stage adds a mechanism; every
stage removes or unifies one, except the universal browser's required bounded
child-sampling protocol operation, which extends the existing execution IPC
rather than creating another value transport.

Evidence base (all dated 2026-07-20, committed):

- [[../database-authority-mesh/research/cleanup-audit-jvm-residue-2026-07-20]]
- [[../database-authority-mesh/research/cleanup-audit-duplicate-interfaces-2026-07-20]]
- [[../database-authority-mesh/research/cleanup-audit-logging-errors-2026-07-20]]
- [[../database-authority-mesh/research/cleanup-audit-vocabulary-2026-07-20]]
- [[../runtime-reliability/research/cleanup-audit-config-startup-2026-07-20]]
- [[../database-authority-mesh/research/pod-term-retirement-plan-2026-07-20]]
- [[research/fresh-source-cleanup-gaps-2026-07-20]]

## Live bug ledger

Open correctness defects, ordered by risk. A bug leaves this table only with
a commit plus behavioral or live proof; intermittents leave only after a
clean loop of the owning gate.

| # | Bug | Owner file(s) | State |
|---|---|---|---|
| B1 | `later-run?` booleans a Promise (always true); whole ns reads async facade synchronously | `src/seon/runtime/recovery.cljs` | **CLOSED** `a2b0c815`: ns fully async, regression tests, live `.then` proof |
| B2 | Agent-loop failure reports bypass `seon.log`; `seon.log/tail` blind to loop faults | `src/seon/agent/loop.cljs` + report list | **CLOSED** `2cbd1892`+`b109266e`: 29 sites routed; events-log emission proven; full suite 1284/5817 green |
| B3 | eval record fix + sync-read clusters | `src/seon/eval.cljs` | **CLOSED**: record fix `b109266e`; deletions `6bed4b12` (243 lines, rg-verified); migrations `b3c15696`; live transcript proof no Promise labels |
| B4 | `seon.warn` guidance named removed `*conn*`; ~15 sync facade reads | `src/seon/warn.cljs` | **CLOSED** `0887b1ea`: pure data plane, 14-site example audit (3 fixed incl. one beyond the PRD), live self-heal proof |
| B5 | remaining sync facade reads | listed files | **CLOSED** `aadc8f33`+`b3c15696`+`beeacff9`: handlers/message fallback deleted (pull-patterns already nested identity), my.skills/my.canvas/web.internal migrated; two live bugs found+fixed in proofs (pinned decode arg order; key-presence error guards -> string? contract) |
| B6 | Stray repo-root `locks/` from `cli_test` fixture running real `state/with-lock` with nil process-dir | `script/seon/dev/state.clj`, `test/seon/dev/cli_test.clj` | **CLOSED** `3d4aee61` + `a850b343` (relative env coordinates absolutized after the guard exposed `bin/acme`) |
| B7 | MCP/dev CLJS REPL cannot use `await`/`^:async`; Promises returned unresolved | `bin/mcp-server-cljs` path | **CLOSED** `8116ba1c`: transport bridge mirrors agent auto-await; five-point live proof; MCP clients must restart |
| B10 | Default client crash-loops on reload rehost: `:seon.runtime.admission/status :publishing` -> `on-core-error :crash`; required a default cluster reset on 2026-07-20 | `seon.runtime.admission` / reload path | **CLOSED** `1098c061`: publication-scoped mark-unavailable + refusal-value prepare; reproduced twice pre-fix, reload-storm survived post-fix; issue archived |
| B12 | dead `:seon.eval/record-error` warn check | `src/seon/warn.cljs` | **CLOSED** `0887b1ea`: check DELETED (reviewed deviation — fault datoms carry no structural discriminator, and a rewrite would duplicate `core-faults-block` as a second derived surface over the same datoms); issue archived |
| B13 | `bin/issues-index` blocked repo-wide by illegal issue `status`/`severity` values | `docs/seon/issues/` | **CLOSED AGAIN** `0d4169ed` after the regression: seven notes normalized, two resolved notes archived, index regenerated; `bin/issues-index --check` passes with 123 open / 362 archived |
| B11 | Operator intermittent: `contained-one-shot-drains-a-foreign-generation-without-overlap` fails order-dependently (containment-uncertain; leaked `sleep 300` workloads suspected via shared `tmp/seon-containment`) — 1 occurrence, green in isolation and on full rerun | `test/seon/dev/process_test.clj:503` | OPEN intermittent — track with B8; unrelated to the 2026-07-20 writer terminal-result race (operator gate, not exercised by the 6x `bin/test-writer` loop), no new occurrence |
| B8 | Writer gate intermittents: `writer-integration` release path + `query-admission` injected-release (1 occurrence each, order-dependent) | `src/seon/db/writer.clj` tests | task chip filed; 6x full-gate loop 2026-07-20: neither original recurred. The loop instead exposed a distinct race — TERM between socket bind and shutdown-hook registration lost the terminal-result publication (2/6; 20/20 in a targeted repro) — **CLOSED** `b34548b0`: hook registered before `start!`, awaits the started promise; repro 0/30, focused ns 10x green under load; note archived at `docs/seon/issues/archive/writer-terminal-result-lost-when-term-precedes-shutdown-hook.md`. Originals remain OPEN single sightings, separate in-process mechanism |
| B9 | `test/seon/agent/ctx/canvas_test.cljs` calls `datahike.api` `create-database` directly (boundary violation) | that test | stage 5 |

Non-bugs recorded to prevent re-diagnosis: default Meta-compatible provider
returns HTTP 402 (external credential state, not a runtime regression);
`:seon.error/kind` / `:seon.repl/kind` are closed value enums, not entity
taxonomies; konserve "store" and cljs.test `:type` keys are correct seam
names.

## Stages

Dependency-ordered; each stage is one coherent commit series with its own
gate, and stages 2-5 are safe to interleave with other program lanes only at
the named boundaries. One stage in progress at a time at the top level.

### Stage 0 — integrate the initial cleanup lanes (graduated)

B1, B2, B6, B7, B10, and B13 are integrated with their recorded proofs. B13's
regression is repaired by `0d4169ed` and its issue is archived again. B3's
remaining transaction-fault verification and sync reads belong to Stage 1.

### Stage 1 — finish the async-facade migration (B3-B5)

Fix every remaining synchronous consumer from the duplicate-interfaces
report per [[async-facade]]'s two-idiom split: async-plane consumers get
`^:async`/`await`; sync-render-plane sites (`seon.warn` checks,
`handlers.message` renderers) are fixed acquisition-side — no `^:async`
may escape into `seon.render/render` or `seon.warn/run-checks`. While in
`seon.warn`, collapse its dual acquisition path to the pre-acquired
`::data` branch and rewrite the `warn.cljs:1064` and `warn.cljs:720`
guidance to the current facade idiom. Reachability-gate every site first:
the caller-less superseded `seon.eval` cluster, `seon.render:684`, and
`testrun/latest-run` are deleted, not asyncified. Gate: full CLJS suite
plus one live cluster proof that a warn check, a render, and an eval each
round-trip through the authority; the report's inventory rechecked to
zero, including zero `^:async` fns reachable from `seon.render/render` or
`seon.warn/run-checks`.

### Stage 1.5 — universal data browser contract and transport

Implement [[data-browser]] after the stage-1 render/schema overlap is closed:
generic bounded rendering remains available when no schema matches; structural
diagnostic candidates are distinct from validated custom-render matches; live
eval drill requests address the owning execution child over the existing
Transit ordinary-data IPC; `/data` samples an acquired database value through
the same projection. Gate: invalid missing-key and wrong-type probes, ambiguous
valid matches, a no-schema value, a large child-owned value with paging, route
ownership refusal, and honest unavailable rendering after child retirement.

Readiness audit `2961b193` adds a mandatory Unit 0 before that migration:
`seon.render.value` currently bounds retained map keys only after recursively
sampling every entry, while opaque summaries can materialize complete printed
values before clipping. The new blocker issue
`data-browser-map-sampler-walks-unbounded-input` records the first mechanism.
Bounded traversal and non-materializing summaries must be proven before the
schema projection, UI, route, or protected child-sampling transport widens
this path's fanout.

Unit 0 is implemented by `d42a88de`: counted and uncounted million-entry map
fixtures prove candidate visits and recursive touches remain within the work
budget, the poisoned tail is untouched, omission is exact or honestly `:more`,
map metadata cannot collide with user keys, datom values share the child
sampler, and arbitrary opaque printers are never invoked. Focused sampler and
HTML gates passed 39 tests / 132 assertions and 6 tests / 25 assertions with
zero warnings. The issue is archived; Stage 1.5 may now advance to the
activated schema projection after Stage 1.6's frozen integration gate.
The post-freeze Unit 1A boundary is grounded durably in
[[research/activated-schema-projection-boundary-2026-07-20]] (`c952c793`):
only `schema.cljc` and `schema_test.cljs` derive and freeze the activated
required-attribute index plus `candidate-shapes`/`matching-shapes` APIs, keyed
by projection object identity. Render/value/web/execution consumers wait until
that two-file contract and its ambiguity/open-map/elision falsifiers pass.

Unit 1A is implemented by `284cbabf`. The activated projection now owns every
required-key row, its inverse index, deterministic `candidate-shapes` and
complete `matching-shapes`, plus the public activated `explain-shape` handoff.
The diagnostic cap is 32 actual index visits: a 400-schema common-key fixture
recorded exactly 32 visits rather than merely asserting output length, while
matching remains independent of that cap and returns every valid ambiguity.
Projection-object replacement rotates validator and explainer generations even
at an equal 32-bit fingerprint; unactivated registration/restoration remains
invisible. The focused gate passed 16 tests / 123 assertions with zero failures
or errors (one unrelated pre-existing `my.blob/crypto` compile warning). The
issue is archived; Unit 1B is dependency-ready, while Stage 1.5 graduation still
requires its projection/drill/transport/UI units and the integrated pure,
server, child-retirement, and real-browser gates.

Follow-up `882b2083` closes the missing input-work side of that bound. The first
Unit 1A proof capped schema-index references but eagerly sorted every input map
key; `candidate-shapes` now examines at most 32 map entries before extraction
and sort, separately from its 32 schema-reference cap. A logical million-entry
map visits exactly 32 entries and leaves a poisoned entry 33 untouched; equal
large persistent maps built in opposite orders and equal small array maps emit
identical ordered rows and printed bytes. `matching-shapes` remains complete
and independent of both diagnostic caps. The corrected focused gate passed 18
tests / 132 assertions with zero failures or errors (the same unrelated
pre-existing `my.blob/crypto` warning).

The remaining Stage 1.5 dependency spine is now grounded rather than left to
consumer inference. Unit 1B's plain-data projection contract is
[[research/schema-aware-value-projection-boundary-2026-07-20]] (`817a821f`):
`render.value` samples exactly once, preserves the existing strict
`:truncated?` completeness signal, validates every deterministically ordered
match only when complete, and emits bounded `:shape-only` candidates when
incomplete.

Implementation readiness is sharpened by
[[research/schema-aware-value-projection-implementation-readiness-2026-07-20]]
(`aa441dfe`): the projection visits at most 32 instrumented schema candidates,
million-entry traversal and capped-writer tests assert work rather than output
length, ordering and every omission marker are deterministic and honest, and
sampling happens once. Unit 1A must first freeze one public activated-projection
explainer API with argument, nil, and generation tests; Unit 1B may not invent
a second registry walk for invalid-value explanations.

Unit 1B is implemented by `cac9e660`. `render-html-data` now keeps its existing
single sampled skeleton and completeness fact while adding activated-only
ordered `:valid`, `:invalid`, and `:shape-only` schema rows plus invalid-only
Malli explanation projections. Its focused gate passed 44 tests / 176
assertions: the million-entry map bounded sampler and schema-input work, left
the poison value untouched, and visited exactly 32 schema references; every
partial marker suppressed validation and explanation; the existing opaque and
capped-writer poison falsifiers remained green. Downstream hiccup, dispatch,
route, drill, and child-transport consumers remain ordered after this plain-data
contract.

Before transport, close
[[../../seon/issues/projected-map-keys-are-not-drill-paths]] and
[[../../seon/issues/value-drill-has-no-total-work-bounds]]: drill paths must
retain original keys, and separate configured maxima must bound path segments,
encoded path bytes, and total realized page work in both parent and child.
The projection repair is grounded by
[[research/projected-map-key-drill-boundary-2026-07-20]] (`ddf2b5c2`): ordered
entry pairs remain unchanged while one ascending output-local
`non-drillable-key-indexes` vector identifies display-only keys; no unsafe
original key, opaque token, or mutable lookup registry crosses a boundary.
The work/config owner is
[[research/value-drill-budget-config-boundary-2026-07-20]] (`3d5943db`): three
independent singleton caps govern decoded segments, encoded bytes, and total
realized items; parent and child both reject before lookup or realization and
each page touches at most `offset + n + 1`. Concrete shipped defaults remain
an explicit owner ruling before that source unit starts. That ruling is now
[[research/value-drill-cap-default-ruling-2026-07-20]] (`38f24f39`): 32 decoded
path segments, 4,096 UTF-8 bytes of raw percent-encoded path, and a maximum
`offset + page-size` of 1,024 (therefore at most 1,025 touched items including
the honest tail sentinel). These are independent cardinality/amplification
bounds, not a latency claim for arbitrary lazy elements.
The exact path codec is
[[research/value-route-path-codec-boundary-2026-07-20]] (`c932c9e1`): decoded
text must be the canonical `pr-str` of one EDN vector; the initial closed
grammar admits nil, booleans, finite non-negative-zero numbers, strings,
keywords, and symbols; duplicate fields, tags, trailing forms, non-canonical
spellings, and malformed encoding refuse before any database or host work.
Numeric map keys retain their value, while vector descent separately requires
a non-negative safe integer.

The post-Unit1A implementation boundary is refreshed by
[[research/value-drill-projection-budget-implementation-readiness-2026-07-20]]
(`a31bcb8f`). After Unit 1B releases `render/value.cljs`, Unit 1C owns that same
source/test pair and replaces the projected-key aggregate with ascending
output-local non-drillable indexes plus original-key, shape-only, and poisoned
tail proof. Unit 1D then owns only config source, manifest, and tests for the
ruled 32/4,096/1,024 defaults. Transport waits for one explicit public drill
request/result/error schema and one effective-limit normalizer contract; route
and child may not infer different envelopes from prose.

Unit 1C's projection repair is implemented by `edd0d2e7` and `7aebb3bc`:
retained entries now
carry ascending final-output `non-drillable-key-indexes`, admitted scalar keys
remain the exact lookup values, and unsafe originals never enter the skeleton.
The focused gate passed 47 tests and 202 assertions, including the instrumented
million-entry work/poison boundary and Unit 1B shape-only propagation. The
projected-key issue remains open for the later route and UI no-request proofs;
those consumers may not recover a path from a display marker.

That public boundary is frozen by
[[research/value-drill-public-schema-ruling-2026-07-20]] (`ec86accb`): closed
producer-neutral request, effective limits, drilled projection, schema status
and explanation, unavailable/error result union, strict path grammar, and one
`seon.config` normalizer shared byte-for-byte by parent and child. The map-tail
contradiction is ruled conservatively: arbitrary map omission is honest but
non-pageable (`offset` remains zero) unless the producer already supplies an
ordered/indexed representation. Sequence and set paging retain the
`offset + page-size + 1` work bound. This preserves insertion-equivalent byte
identity and bounded work; no downstream transport or UI may relax either.

Implementation readiness
[[research/value-drill-schema-normalizer-implementation-readiness-2026-07-20]]
(`8f8ae9e9`) corrects the predicate placement without weakening the contract.
Registered public shapes remain closed pure EDN; deep sampled-tree,
explanation, and error-data validation runs at public producer/transport
function boundaries with explicit visit/depth/string/collection budgets. One
named closed `:seon.render.value/limit-normalization-request` owns the config
singleton plus optional operation limits, and `seon.config` references it
without a reverse require. Unit 1E lands shapes/predicates/normalizer before
Unit 1F changes descent and paging. Map byte identity is scoped to repeated
sampling of the same stable immutable concrete value and iteration order;
arbitrary equal-but-differently-implemented partial maps are never claimed to
be canonically sortable under bounded work.

Unit 1E is implemented by `c1618e22`: one pure-EDN public drill population,
three scalar admission predicates over the existing raw-value boundary, and
the single `seon.config/effective-value-drill-limits` normalizer. The focused
renderer/config gate passes 78 tests and 492 assertions. The proof covers
negative zero and unsafe integers, closed request/result maps, independent
monotone clamping, idempotence, same-policy parent/child byte identity, and an
honest narrower-child result reserved for the later frame-consistency refusal.
No descent, paging, lookup, transport, route, or UI behavior lands in this
unit; deep bounded validators remain owned by those public boundaries.

Unit 1F is implemented by `9c22de90`. The one `seon.render.value` producer now
performs exact scalar map/vector descent, sequence/set head-plus-one paging,
and honest non-pageable map windows. Rejected requests touch no live value;
accepted sequence pages touch at most `offset + page-size + 1` items, including
the exact shipped 1,025-touch ceiling, while a million-entry map touches five
entries for a four-entry page and never trusts or calls the source's count.
Hostile request maps, lookups, counts, lazy realization, marker trees, and
result envelopes are capped or become closed failure values. Deep result
validation is total and bounds path/scalar volume, schema rows at the owning
32-candidate cap, explanations, errors, and sampled marker structure. Schema
validation/explanation runs only for complete slices, and all three result
branches round-trip through the existing Transit codec. The focused gate was
independently rerun at 66 tests / 437 assertions with zero warnings, failures,
or errors; an adversarial review accepted the final diff with no P0/P1.

The dependency-ready transport boundary is grounded by
[[research/unit-1g-value-sampling-transport-implementation-readiness-2026-07-20]]
(`cfadf4e9`). Unit 1G extends the one lane-keyed execution dispatcher and both
existing serving tiers; it does not parse HTTP. Bun samples through its
existing child-local result slot. JVM-hosted evals currently retain no
addressable live value after invocation, so
[[../../seon/issues/retain-live-eval-values-in-the-owning-jvm-host]]
(`cb64b7a1`) records the blocker and ruling: add one bounded process-local
managed-eval-id slot inside the existing JVM host session lifecycle and sample
there. No raw value crosses to the parent, no persisted result is reparsed, and
retirement or tier mismatch returns honest unavailability. The later route
unit continues to own canonical EDN and exact raw percent-encoded byte checks.

Cross-tier review then exposed two prerequisites that prevent a Bun-only or
load-order-dependent implementation from claiming that boundary. First,
[[research/value-drill-portable-owner-boundary-2026-07-20]] (`64e19c31`)
rules that the existing `seon.render.value` namespace itself is the portable
owner; no host copy, raw-value fallback, or second kernel is permitted. The
truthful mechanical checkpoint `7c124879` atomically promotes it to `.cljc`,
passes the complete effective sampling policy as request data, and preserves
exact ordinary CLJ/CLJS result bytes. Focused CLJS renderer/config proof is
95 tests / 595 assertions and the JVM portability proof is 4 / 17. This
checkpoint deliberately does not claim schema-aware JVM map parity.

Second,
[[research/jvm-value-drill-schema-projection-admission-boundary-2026-07-20]]
(`6177ae2e`) proves the JVM host has neither the complete committed schema
projection nor a safe ambient fallback. Moving a convenient config subset
would leave browser truth dependent on namespace load order; globally
activating only database forms would delete JVM-private host schemas. The open
blocker [[../../seon/issues/archive/jvm-value-drill-lacks-committed-schema-projection]]
(`8a9283d7`) owned that ordered prerequisite. It is closed by `414b8137`: one
portable rows-to-projection transform rejects malformed, duplicate, unresolved,
and overflow populations; projection-explicit schema APIs share recursively
canonical CLJ/CLJS fingerprints; and the JVM host pins acquisition to one
immutable database value, retains the basis-fenced projection, and refuses
drill during pending or faulted refreshes. Independent proof passed 110 CLJS
tests / 706 assertions and 47 writer tests / 188 assertions. A fresh live query
confirmed the four projection schemas and new projection API contracts are
committed with their final source forms. The issue is archived by `e11243b4`;
Unit 1G is now the earliest unsettled contract.

Unit 1D's configuration leaves are implemented in the existing config owner:
the closed render policy and flat singleton now carry the ruled
32-segment/4,096-byte/1,024-item defaults, the shipped manifest states the same
values, and the focused config gate passes 27 tests / 149 assertions. The
effective-limit normalizer remains deliberately dependency-ordered with the
`seon.render.value` public `operation-limits` and `effective-limits` schemas
frozen by `ec86accb`; landing it earlier would require a loose `:map` contract
or duplicate schemas and would break the one-contract ruling.

Only after those contracts freeze does
[[research/execution-child-value-sampling-boundary-2026-07-20]] (`a568deef`)
extend the existing execution protocol with closed, correlated ordinary-data
request/result/error frames. The parent never performs child result lookup,
retries a retired child, or returns an unbounded value. Finally,
[[research/value-route-authorization-boundary-2026-07-20]] (`7b6e2243`)
adds the single read-only GET route: database entities sample from the same
acquired immutable database value in the parent; eval selectors join eval
ownership to the route agent before any host send; unauthorized and unknown
selectors are uniformly absent; retired results remain ordinary honest
unavailable projections. UI/custom dispatch and integrated browser proof are
consumers of that frozen chain, not alternate authorities.
The final consumer cut is grounded by
[[research/universal-data-browser-ui-migration-boundary-2026-07-20]]
(`e7cc6f94`). It extends the existing render dispatcher, migrates `/data` and
eval technical disclosure, and deletes duplicate raw rendering and route
authority. The plan proof first separates existing structural acquisition
from pure property renderers and deletes the HTML-only database query;
registering today's DB-acquiring `plan-block-html` unchanged would preserve a
second mechanism and cannot satisfy the gate.

### Stage 1.6 — corrective steering gaps

In progress (2026-07-20): root task `/root` owns the strict directive-error
unit B (G1/G2, the three maintained nilable registrations, transact-response
error union, and G8-G11 steering extensions). Protected execution/eval paths
remain outside this unit.

Checkpoint `0b991436` closes the source/test portion of G1/G2: `register!`
rejects top-level nilable registrations before candidate mutation through the
shared schema predicate; the three maintained registrations express
optionality at their function slots; and database failures return copyable
`schema/register!` or `seon.db/transact!` corrective forms. Focused schema,
database-remote, home, Datastar, and plan tests are green. Fresh-start/live
proof and the G8-G11 extensions remain before Stage 1.6 graduates.

The G2 closure audit
[[research/g2-nilable-registration-closure-audit-2026-07-20]] (`f72d7384`)
confirms the three registrations and registration gate are complete and found
the two-real-child integration driver still filtering top-level `:maybe` forms
before seeding. No semantic registration owner is reopened.

Commit `748410dd` implements the harness half: the filter is gone, complete
population parity is asserted, and the schema-row count is emitted for process
evidence. The first frozen run then exposed a stale pre-symbol-migration driver
artifact. Commit `95d94666` loads the missing production schema owner and adds
a direct canonical `:seon.ns/name` assertion before publication. A rebuilt
driver passed the selective writer gate at 1 test / 27 assertions with 1,807
unfiltered schema rows, two distinct real children, database advancement,
bounded stuck-child retirement, and replacement-process proof. The remaining
G1/G2 exit is the fresh-agent rejection/correction probe.

G8's non-canvas checkpoint is `d883bb05` with issue evidence in `f14219a3`:
the registry-derived audit covers 25 request maps across `my.blob`, `my.data`,
`my.kb`, `my.ns`, and `my.ui`; 19 formerly open public schemas are now closed
and all reject unknown keys through Malli's schema rule. The five owner suites
plus the audit passed 55 tests / 289 assertions. The issue remains open until
the ten `my.canvas` request maps, now free of the G11 ownership collision, pass
the same derived acceptance gate.

G8 is now closed by `94e38e15`+`ee6dde8c`: the ten canvas request maps use the
same Malli closed-map semantics, the registry-derived audit covers all 35
request maps, and the combined canvas/audit gate passed 9 tests / 104
assertions. No parallel runtime key guard was introduced.

Corrective-steering G6 is closed by `cd7ffdf0`+`5aae790b`: an incomplete eval
row now names its eval ID, states that no result was recorded, and directs the
agent to re-run the form instead of emitting the dead-end `<no result>`
placeholder. The focused context gate passed 11 tests / 44 assertions and the
issue is archived.

Frozen-source CLJS checkpoint at `286180f7` passes 1,331 tests / 6,151
assertions with zero failures, errors, or compiler warnings. This closes the
Stage-1.6 integrated code gate only. A status check immediately after readiness
observed all default processes absent because a second explicit lifecycle
reconciliation had entered its clean stop phase before publishing replacement
generations; logs falsify workload crash, control EOF, and record loss. The
frozen-turn-inputs I6/I7 later committed as `df78bb8d`; U4 source and lifecycle
later committed and released as `b7808e35` and `2821bb87`, with `u15` closed.
The replacement generation observed during those edits was intentionally not a
source-clean checkpoint. Fresh directive/narration/query-shape and G11 browser
proof remain uncounted until every current source owner releases the artifact.
The independent full writer gate also passes 259 tests /
1,997 assertions with zero failures and errors while the then-active dirty
paths were CLJS-only. This is useful
integration evidence, not the final twice-frozen graduation run.

The one-session frozen live procedure is now
[[research/stage1-6-live-graduation-runbook-2026-07-20]] (`82b5a824`). It
records one source/artifact digest, runs the focused gates and unfiltered
two-child count, then uses one fresh real agent for G1/G2 correction, exact-byte
G3/G9 narration, four native G10 shapes plus behavioral reuse, and the
agent-authored G11 canvas lifecycle. Narrow/wide browser observations are
paired with a server-side identity-encoded SSE feed, usage and rendered-turn
readback, explicit abort conditions, and issue-by-issue dispositions. U4 source,
drills, and `u15` are released. Independent verification
[[research/u4-integration-verification-2026-07-20]] (`f28863ea`) accepts the
receipt-before-run, terminal CAS/tee, managed allocation, replay, admission,
and host-provenance contracts; its retained-log and message-fixture caveats are
non-blocking and superseded by the final frozen writer/live gates. Stage 1.5
generic rendering and Stage 5 debug/result-union work remain explicitly outside
this proof.

Corrective-steering G10's record-time half is implemented by `418a3844`:
successful `db/query` results carry a deterministic readable-EDN comment that
distinguishes scalar, tuple, collection, and relation find shapes without
coercing the value. Independent focused receipt proof passed 18 tests / 75
assertions. The existing query-shape issue remains open for the Stage 1.5
generic-render consumer and behavioral agent probe.

From [[research/corrective-steering-audit-2026-07-20]] (all persist-time or
pure-render, single execution, byte-identity safe):

- **Directive error text unit (G1+G2)**: database error values render with
  the corrected next form, `seon.db.internal` thrower messages show the
  working idiom; `schema/register!` rejects banned shapes (`[:maybe X]`,
  stored nil, `:any`) at registration with a shared predicate and a
  directive message — the standing register!-accepted-banned-shape smell
  closes here.
- **CLOSED `8e008470` (G3)**: a deterministic narration line frozen at record
  time states how many complete forms after the first were not run and directs
  the agent to resend the next one. Independent focused proof passed 15 tests /
  40 assertions; provider reply/blob bytes remain untouched and the issue is
  archived.
- **Transact coercion contract (G4, ruled 2026-07-20)**: strict rejection
  with a directive message naming the canonical shape — never a silent
  coercion that teaches non-canonical calls, never ok-on-wrong-input.

### Stage 2 — pod-term retirement (atomic rename)

Execute [[../database-authority-mesh/research/pod-term-retirement-plan-2026-07-20]]
steps 1-4 as one orchestrator-owned unit during a lane freeze: code
identities (`client`/`cluster` mapping, `pod.js` -> `client.js`,
`pod-events.log` -> `client-events.log`, `:seon.dev.process/pod` ->
`/client`), then `acme`/`src-inspect-ai`, then living docs, localized
`AGENTS.md` authorities, and skills (resync adapters), then the sweep.
Prerequisite (plan's freeze gate 3): quiesce both clusters under PRE-rename
code (`bin/seon down`, `bin/acme down`) with recorded absence evidence —
the rename cannot cross persisted `:seon.dev.process/pod` records, restore
intents, or pre-rename release manifests. Gate: three suites, `bin/seon up`
from the quiesced state (never `restart` across the rename) with live
status/web-UI proof, one MCP `eval_cljs` round-trip after restarting the
MCP client, and a vendor-excluded sweep (`rg -vi runpod` — RunPod vendor
tokens are frozen) returning only `pod-host/`, dated research/history, and
dependency-owned terminology. No active authority may continue teaching
“pod” as the current process name.

Readiness re-audit (2026-07-20): Stage 2 is not freeze-ready. Active SCI/B2,
database, UDS, execution, REPL, and web owners still overlap the rename; the
default operator reports a retained branch source-intent mismatch; two named
branch clusters retain `processes/pod.edn`; ten worktrees need explicit
merge/discard/translate dispositions; and every existing `tmp/package-v*`
artifact predates the rename and must be invalidated. Recent SCI-host files
add active terminology absent from the dated inventory, so the exact sweep is
recomputed only at the recorded freeze HEAD. Skill sources are edited first,
then adapters regenerated with `bin/seon skills sync`/`skills check`; the MCP
client restarts before its renamed round-trip can count as proof.

The original delta is
[[research/stage2-freeze-readiness-delta-2026-07-20]] (`4fb2aa53`), refreshed
by [[research/stage2-freeze-readiness-refresh-2026-07-20]]
(`b455ce7e` + ownership correction `a5821c8f`). U4 and `u15` are resolved, and
the record count is now three: default, `kimi-k3-test`, and `reactive-proof`.
The observed `.cljs`→`.cljc` plus host-portability source owner was
unidentified, so the initial refresh correctly refused a freeze. That transient
diff subsequently disappeared before review; independent audit
[[research/portable-cljc-host-boundary-2026-07-20]] (`06e06a9f`) classifies it
as an incomplete SCI U5 fragment, not a source-cleanup unit. U5 waits for its
own U3 handoff and must land a dependency-ordered source loader, complete
registry provisioning, shims, and host restart proof rather than bare renames.
It is no longer a current dirty-tree blocker. The exact ten-worktree ledger is
[[research/stage2-worktree-disposition-ledger-2026-07-20]] (`2226c8cd`): the
primary checkout is merge-before-rename, seven legacy lanes are preserved
out-of-scope, and `plan-fix` needs an owner decision only if removal is desired.
`seon-stable` is the serious remaining ambiguity because its dirty ACME client
overlaps the terminology cut while its live processes and unique evidence stay
owner-controlled; explicit sequencing, quiescence, and preservation handoff
are mandatory. Ports 7980/7981 belong
to `/Users/sean/src/seon-stable`; that owner—not this checkout—must hand off and
stop them. Ten worktrees still need explicit dispositions, restore intent needs
database proof, and the terminology manifest is recomputed only at the final
stable HEAD. B2 caches remain protected through U11 and are neither proof nor
a clean-tree blocker.

### Stage 3 — one logging convention

Outcome (2026-07-20): logging source/test unit C is implemented by `51f28046`.
Timbre 6.5.0 is vendored at upstream `b72cc652`; the writer formatter guards
each value and the whole formatter, matches the client line structure, and all
bare `[database]` prints use Timbre. Focused proof passed 8 tests / 47
assertions and the full writer gate passed 232 / 1,896. The paired
frozen-source writer/client live lines, loop-fault tail, and safe shared-log
cleanup remain program integration proofs; protected turn/canvas round trips
remain assigned to their owning stage.

The exact remaining boundary is
[[research/logging-live-graduation-boundary-2026-07-20]] (`417c2b4f`). The
frozen proof selects current-generation logs from operator records, requires
one client and one writer line to satisfy the same byte-shape predicate, and
drives the real `seon.agent.loop/await-bounded` timeout through an ordinary
error value into `seon.log/tail`. Current managed logs live under the launch
descriptor's `logs/operator` root; old top-level `pod*.log` files cannot count.
Legacy probe output is moved only through a reviewed, NUL-delimited,
recoverable quarantine after protecting every live/retained owner—never a
filename deletion glob.

From the logging report's remaining plan: adopt the `seon.log/console!`
line shape on the JVM writer via a timbre output-fn; route the residual
non-agent console sites; decide the two value->throw->value round-trips
(`turn.cljs:622,931`, `ctx/canvas.cljs:342`) with the errors-as-values
contract; prune stray bench/probe/`.eval` files from `logs/` and gitignore
their patterns. Gate: writer + CLJS suites; one log line from each process
shows the same shape; `seon.log/tail` shows a loop fault end-to-end.

### Stage 4 — config single-owner collapse and reactive fold-in

Current source-grounded boundaries are recorded in
[[research/route-authority-readiness-reconciliation-2026-07-20]] (`c5900e74`)
and [[research/config-authority-readiness-reconciliation-2026-07-20]]
(`69ce49f9`). Route authority goes first as an atomic schema/seed, public Ring
handler, capability/admission, four-route bootstrap remainder, and `/sse`
crossing; its Reitit duplicate-path rollback proof is a hard pre-merge gate.
The reactive router replacement is a second bounded cut. Configuration work
begins with one per-operation pinned database-value + full-config ALS contract;
existing Aero reconciliation, high-natural limits, managed identities, and
additive home-requires are retained rather than rebuilt.

The exact operation boundary is grounded by
[[research/per-operation-config-boundary-2026-07-20]] (`c4d148d5`). One shared
database-owned acquisition returns the immutable database value and the full
decoded singleton from that same basis; every root enters both through
`.run` plus the existing error scope, while nested work inherits the pair.
Compiled Bun child calls acquire at invocation rather than program preparation,
and database-only nested overrides are removed or upgraded to a matching pair.
The post-U4 reread also makes JVM parity part of this contract: the parent sends
the certified pair, and the host binds ordinary immutable operation data so its
query/pull/transact wrappers use the invocation database and singleton limits
instead of resolving a fresh head per call. The boot `enterWith` installer is
deleted only after every root boundary is covered.

Home-requires readiness is grounded by
[[research/home-requires-merge-boundary-2026-07-20]] (`fa158957`). The additive
manifest/root merge is already implemented by `3c08c176`, and `e187284f`
already makes persisted require edges the compact-card selector. The owner
ruling distinguishes that projection from `:seon.fn/agent-facing?`, which
remains the function-menu/export selector; configuration merge is not rebuilt.
Commit `469c0f5b` closes the focused regression with namespaces 16 tests / 79
assertions, config 27 / 127, home 9 / 18, and auto-refer 7 / 31 green with zero
warnings. Only the frozen ACME proof remains for this row.

The later carrier convergence is grounded by
[[research/als-tx-meta-unification-boundary-2026-07-20]] (`5c140e9a`). Stage 4
first establishes the final per-operation database/config population. Stage 5
then collapses agent and transaction contexts into one operation-context ALS,
keeps read-evidence collection separate, and derives only registered durable
transaction metadata. The obsolete proposal to call the whole ambient map
`tx-meta` is rejected because Datahike persists every metadata entry and the
map contains process-local values. A post-U4 host/provenance inventory is an
implementation precondition.

From the config report: collapse duplicated defaults (7890, port files,
cluster dir) to one declaration consumed by `config.clj`, `launch.cljc`,
and `db/server.clj`; migrate runtime env gates (`SEON_WEB`, `SEON_SHELL`,
`SEON_RENDER_STRICT`, `SEON_BRAND_*`, `my/blob.cljs:200`,
`db/transport/uds.cljs:28`) to database facts or the launch descriptor;
deduplicate the `SEON_EMBED` scrub with `bin/acme`; absolutize env dir
coordinates in `config/load!`. Gate: operator suite, `bin/seon up` from a
clean checkout, acme cluster boot, config-apply idempotence proof. Before the
ambient configuration refresh is selected, prove that two already-created
independent async fibers observe the update; otherwise keep live acquisition
at the owning operation/session boundary. Collapse the second product-route
catalog in `seon.web.router/static-supplement`: route facts own product,
lifecycle, debug, and data-browser routes; launch capabilities own optional
operator doors; only proven pre-database assets/readiness may remain static.

### Stage 5 — deletions and small unifications

Outcome (2026-07-20): fragile-index H5 is implemented by `904cf4ab`.
Config reconciliation derives managed identity attributes from the registered
entity catalog and the desired population instead of a three-attribute
literal. Focused proof passed 11 tests / 39 assertions; a live synthetic
fourth family derived its identity while a registered absent family did not.

Outcome (2026-07-20): fragile-index H6 is implemented by `6246181b`.
`seon.schema-test` now derives every AI/HTML handler from the complete entity
catalog and requires each qualified symbol to resolve to a function. The live
default cluster resolved 12/12 handlers; the focused selector passed 25
assertions and the changed namespace passed 87 assertions. The one full CLJS
checkpoint ran 1,294 tests / 5,914 assertions with one unrelated failure:
`my.plan-test/generated-program-publication-no-ops-only-without-a-cause-linked-root`
expects max-results `[2 2]` while the concurrent uncommitted `src/my/plan.cljs`
change supplies `[2 4]`. H6 covers no issue note in the triage's COVERED list.

Outcome (2026-07-20): `4ac2902e` deletes the unreferenced
`seon.ui.components` namespace (278 lines), with a repository caller sweep at
zero. `ecb8b4b7` removes B9's direct Datahike/private-database test setup and
proves the canvas through the public `seon.db/execute-many` boundary (9 tests /
36 assertions). `87d415e8` gives CLJ, CLJS, and Babashka one portable
`seon.agent.ctx.ns-name` owner for hidden/test/included namespace predicates;
the Babashka gates passed 74 assertions and the CLJS consumers passed 265
assertions with zero warnings.

Outcome (2026-07-20): `5ea16b14` archives all 18 superseded namespace-UI
documents under `docs/prds/namespace-ui/archive/`, marks their frontmatter
archived, and repairs incoming links; `8dcf64c5` had already removed the
storage shootout and Integrant submodule. Markdown proof passed 22 tests / 341
assertions. The integration correction keeps maintained web/CSS authorities
pointing at `docs/seon/architecture/ui.md` and the live CSS tokens rather than
teaching an archived PRD as current authority.
Commit `bb5c8cd6` also repairs the hidden Datastar skill and synchronized
adapter that still cited the archived PRD as current authority; a hidden-file
sweep leaves only historical evidence references.

Outcome (2026-07-20): `b819df26` retains and wires `seon.agent.ctx.usage`.
Provider usage normalization now covers agreeing DeepSeek cache fields,
Muse's nested cache shape, Anthropic's additive input semantics, and explicitly
estimated stream-abort values; malformed, unknown, conflicting, or negative
counts produce diagnostics instead of plausible zeroes. The existing debug
turn projection and transcript HTML surface consume the one derived
projection. Independent focused proof passed usage 4 tests / 18 assertions,
debug 7 / 28, and transcript 12 / 38. Live agent-page proof waits for the
coordinated frozen client because the default operator currently reports a
retained source-intent ownership mismatch and port 7890 is down.

Outcome (2026-07-20): `3a0dbd31` gives `strip-code-fences` and `parse-forms`
precise Malli boundaries over the existing byte-identical parser behavior,
including closed discriminated form/read/comment entries and the one documented
polymorphic reader value. Focused CLJS proof passed 46 tests / 369 assertions.
Two requested fixture repairs were stale because their integration-test owners
were intentionally deleted; their issues are archived against maintained
receipt and repair-batch coverage. The pre-existing `bin/test-parser`
Babashka/Malli classpath failure is recorded separately and remains open.
Follow-up `58fb020d` closes that gate without widening the parser runtime: the
single transport-specific require/assertion is CLJS-only, while Babashka and
CLJS continue to share the same parser corpus. `bin/test-parser` passed 46
tests / 368 assertions and the CLJS selector passed 46 / 369.
The current re-audit
[[research/parse-forms-entry-boundary-2026-07-20]] (`84b35090`) confirms the
original entry-envelope issue is fully implemented and archives it with the
same current green counts plus frozen CLJS checkpoint `286180f7`. The adjacent
public options-map defect is subsequently closed by `f49268cd` + `f797a8ef`:
the parser schema/destructuring, every maintained source/portable caller, and
focused tests use only `:seon.repl/strip-fences?`, with no compatibility
branch. `bin/test-parser` independently reran 46 tests / 369 assertions green;
the implementation lane also passed focused CLJS parser 46 / 370, diffusion
consumers 21 / 149, and both worker-validator and portable-oracle smokes.

The two triaged test-hygiene FOLD rows are also reconciled by
[[research/triage-test-fixes-boundary-2026-07-20]] (`7086947d`). Their defective
pod-wide/embedded fixtures were deleted with superseded mechanisms by
`2884c41b` and `97654066`; both notes were already archived by `3a0dbd31`.
Current receipt and repair-batch tests prove the surviving exact transaction
and pure preflight seams without assuming an empty global schema corpus. No
test harness is restored; their next evidence is the ordinary frozen CLJS gate.

Fragile-index H2's safe consumer portion is implemented by `3ebc9e9b`:
stored eval guidance is no longer reparsed as a serialized error envelope and
debug reproduction reads EDN before selecting the qualified function symbol.
Focused proof passed 8 tests / 29 assertions and the coordinated full gates
passed pod 1,300 / 5,934, writer 232 / 1,896, and operator 289 / 1,624. The
remaining filesystem-denial match is correctly left open: its producer gives
denials and ordinary I/O failures the same keys, so the open issue owns the
required producer contract rather than adding another prose heuristic.

That remaining H2 producer contract is closed by `431ce8a7`: filesystem scope
denials carry the registered `:seon.agent.fs/denial :allowlist` attribute,
ordinary I/O failures do not, and `seon.warn` parses stored EDN and selects the
attribute rather than prose. Inverted-wording regressions prove the boundary;
the focused filesystem and warning gates passed 44 tests / 235 assertions, and
the issue is archived.

Corrective-steering G11 is implemented by `9778fa86` with evidence in
`bf0f4bef`: canvas controls use the existing Datastar indicator/fetch lifecycle
for stable pending, disabled, busy, and bounded failure states; automatic
transport retry is disabled for non-idempotent calls; and failed calls preserve
the standard structured error value through the HTTP response. Focused proof
passed 29 tests / 99 assertions. The issue remains open pending the coordinated
real-browser pending/success/failure/corrected-retry/rapid-submit gate on a
frozen ready client.

Corrective-steering G9 is implemented by `f84a9efc` and its issue is closed by
`147acef8`. Both transcript and technical eval rendering use the one
`quote-lines` owner, so every model-authored narration line remains byte-visible
behind a comment boundary even when it resembles message, masthead, box,
readline, or result scaffolding. Focused proof passed 13 tests / 47 assertions;
the full CLJS checkpoint's sole failure was the concurrent G8 inventory test.
The frozen live render remains part of the Stage-1.6 integration gate.

Additional stage-5 items from
[[research/bespoke-reactive-sweep-2026-07-20]] and
[[research/envelope-symbol-conformance-2026-07-20]]: replace the
`serve.cljs:1223-1290` 1500 ms run poll with a request-scoped registration
(preserve the done predicate and `:superseded` timeout close); after the
stage-4 router collapse lands, replace the `client.cljs:344-539`
advertisement machinery with one `observe!` over resumable agent ids and
call the never-called `reactive/close!` from `drain-runtime-owners!`;
converge the failure-payload key on `:seon.error/message` and the
unresolved-symbol semantics (one warning derivation; fix render.cljs
silent nil-vanish). **Ruling reconciled 2026-07-20:** message presence is not a
discriminator. Make the database error schema closed and use one schema-derived
predicate only where a fixed success schema is provably disjoint. Public reads
that return arbitrary user data require a closed outer explicit
`:seon.result/ok?` union; do not wrap every fixed-shape database operation or
infer failure from keys inside raw domain data. The migration waits for the
active database/UDS lane and proceeds by inventoried owner groups.

The exact inventory and atomic migration are grounded by
[[research/database-result-union-boundary-2026-07-20]] (`25c9fdf3`). Only
`query`, `pull`, and `entity` require the outer union; `pull-many` remains a
disjoint vector, and installed schema remains bare after its concrete map-of
schema is registered. One closed database error and one schema-derived
`db/error?` predicate own fixed-result discrimination. `my.canvas/state` is
the one identified arbitrary domain response needing its own outer union.
The facade and all direct consumers move in one frozen owner-group cut; U4's
database/host paths are released, while any new overlapping owner still
requires an explicit handoff.

The Stage-1.6 transaction-response acceptance folds into that same owner group
via [[research/transact-response-union-boundary-2026-07-20]] (`8862b604`).
`5e3edf01` already landed the bare fixed transaction-report/error output
union, but only literal schema membership is tested. The shared closed-error
cut must add an instrumented public-call writer refusal followed by a corrected
transaction, then a frozen same-child failure/`complete` survival proof. No
extra `:seon.result/ok?` envelope belongs around this disjoint fixed result.

The unresolved-symbol owner is grounded by
[[research/unresolved-render-symbol-boundary-2026-07-20]] (`4f4dbd95`). A
selected symbol must resolve to a function; absence and non-function values
produce the same visible standard error value in AI and HTML and never fall
through to generic rendering. After Stage 1.5 freezes property dispatch and
Stage 4 freezes route rows, Stage 5 replaces the canvas-only warning with one
derived `:unresolved-symbol` family. The same cut repairs the current
error-card schema mismatch by using required message/kind/data and registered
presentation fields inside error data.

The `/agents/run` polling row is closed by `6f157a3a`: one request-scoped
`seon.reactive` registration observes the unchanged completion predicate,
settles on the committing database value, preserves timeout closure as
`:superseded`, and releases unconditionally. Independent focused proof passed
serve 26 tests / 103 assertions and reactive 7 / 49; the issue is archived.

The remaining client advertisement and shutdown fold is grounded by
[[research/client-reactive-shutdown-boundary-2026-07-20]] (`33293b06`). The
observed value must include deterministic runtime controls as well as resumable
IDs: `wake?` and `paused-at` changes preserve membership and an IDs-only value
would be equality-suppressed. After the Stage-4 router cut, the client deletes
its direct listener/freshness machinery, reconciles only changed runtimes, and
awaits the one `reactive/close!` after hosts stop but before admission and the
database session close. Stale-resume ordering and quiesce/full-stop release are
hard focused/live gates.

The remaining debug-feed/turn-debug boundary is grounded by
[[research/debug-feed-live-graduation-boundary-2026-07-20]] (`4ab23c10`). Turn
ref projection (`bab67136`) and failed-query short-circuit (`0a15a116`) have
focused coverage but still need their frozen `/agents/run` proofs; the latter
also waits for the ruled result union. More importantly, debug prompt child
read evidence is currently dropped between `turn/render-prompt` and the
observed render, so prompt-only relevant commits may not reach the reactive
interest. Stage 5 must preserve the already transported evidence through that
one existing handoff after Stage 4 freezes reactive ownership, then run the
server-side unrelated/relevant/closed SSE matrix.

Stage-5 test-runner resolution is unified by `8aeadd3d`: test thunks, fixture
vars, and namespace enumeration all use ClojureScript's maintained
`find-ns-obj` owner instead of three direct Google-global lookups, without
introducing an eval/runner cycle or changing selectors. Independent focused
proof passed 17 tests / 59 assertions and the issue is archived.

Checkpoint cleanup `ab6831a8`+`a498882a` declares two forward fixture helpers
introduced by the concurrent dev-eval fault work. The canonical test build's
four undeclared-var warnings disappear; the focused error-record selector
passed 19 tests / 86 assertions with zero warnings and the issue is archived.

Collapse-hunt items (adversarial review 2026-07-20):

- **CLOSED `84ab7097`**: `src/seon/embed.clj:611-679` hand-rolled the complete `seon.retry`
  strategy stack (exponential base, jitter, cap, max-retries) with a
  drifted curve (embed jitters a post-cap value, so it can exceed its own
  30 s cap by 50%) and no `max-duration` bound. Fix: rename
  `src/seon/retry.cljs` -> `retry.cljc` (jitter via reader conditionals;
  keep the `^:async` `sleep!`/`with-retry!` executor CLJS-only); embed
  builds its delay seq from the shared combinators in turn.cljs's exact
  composition order plus `(retry/max-duration 60000)`, walking it in its
  existing `Thread/sleep` loop with the interrupt handling preserved
  verbatim; ground the JVM driver shape against
  `reference-code/again/src` before writing it. Verify: `bin/test-cljs`
  stays green (turn.cljs + diffusiongemma consume the promoted `.cljc`
  unchanged), `bin/test-writer` for embed, one writer REPL probe that the
  strategy seq realizes (~500/1000/2000/4000/8000 within jitter bounds).
- **CLOSED `6920227b`**: `seon.render/value-leaf` (render.cljs:414-443) and `pruned-marker`
  (:496-510) hand-mirror `seon.render.value/emit-leaf`'s marker token
  strings and have already drifted (leading-space `" ⟨"` vs `"⟨"`). Fix:
  extract the four emit-leaf branches into pure formatters
  (datom/opaque/clipped-string/pruned token fns) in `seon.render.value`;
  the html view wraps the exact returned strings in its styled spans; the
  compact no-leading-space `"⟨"` form is canonical (the ai token budget's
  shape) with the html gap restored via CSS; one render test pins that the
  html leaf's flattened text equals the corresponding emit-leaf string.
  The four canonical formatters now live in `seon.render.value`; both focused
  render suites passed 52 tests / 185 assertions.
- The stored-rows -> schema-projection decode is duplicated between
  `seon.runtime.admission/committed-projection`
  (admission.cljs:209-232) and the execution-child load path
  (eval.cljs:1019-1026), with a third single-form site at eval.cljs:2709.
  Fix: `seon.schema` gains one private `read-stored-form` (the single
  reader-table/decode-error policy for stored `:seon.schema/form` and
  `:seon.fn/spec` strings) and one public `rows->projection`; all three
  sites call it; query/transport stays with each caller — only the pure
  decode+build collapses. `typeahead.cljs:795` may follow (lowest
  priority, try-wrapped best-effort site).

  The implementation boundary is now grounded by
  [[research/stored-rows-schema-projection-boundary-2026-07-20]] (`784a3e01`).
  Because cross-namespace consumers must call the decoder, it is a public
  `^:no-doc`, non-agent-facing pure function rather than the earlier proposed
  private helper. It reads through a fixed local EDN tag table, rejects
  duplicate identities and trailing/corrupt data deterministically, and never
  depends on `cljs.reader`'s mutable global tag table. U4's host recorder may
  move the incremental caller, so implementation waits for its release and
  re-inventories every surviving stored-form decode before editing.

- **CLOSED `b819df26`**: retain and wire `src/seon/agent/ctx/usage.cljs` into the debug turn projection
and compact agent-page usage, with validated non-negative provider counts and
diagnostics for malformed/unknown shapes. Delete `src/seon/ui/components.cljc`
(dead parallel UI layer); fix B9 to go through
`seon.db`; extract the two namespace predicates `seon/dev/docstring.clj:193`
duplicates into the owning `.cljc`; rename test-only "tile"/"verbs" fixture
strings; resolve
[[../../seon/issues/deprecated-skill-render-functions-indexed]] by removing
false deprecation claims from canonical live render functions and deleting any
actually retired function after caller migration; delete
`dev/storage-shootout.js`, remove the `reference-code/integrant`
submodule and its `.gitmodules` entry, and archive `docs/prds/namespace-ui/` as
already ruled; downstream `bin/acme` gym naming remains downstream-owned. Gate:
three suites; require-graph re-scan shows no orphan regressions, and no
deprecated function remains eligible for the callable program index.

## Graduation

The requirement-by-requirement proof ledger is
[[research/program-graduation-matrix-2026-07-20]] (`e61e90ad` plus the
cap-ruling reconciliation now branch-visible in `e27ada04`). It projects the
authorized A-H spine, inherited open issues, successor dispositions, and exact
freeze order without narrowing this roadmap. Its earlier shared-tree snapshot
is superseded for ownership: frozen-turn-inputs committed `df78bb8d`; U4
committed/released its database/execution-host work and drills as
`b7808e35`/`2821bb87`, and closed `u15`.

All ledger rows closed with proof; every evidence report's fix plan either
executed or explicitly moved to a successor PRD; three suites green
twice consecutively (intermittents B8 included); one live cluster session
demonstrating: a warn check, a recovery decision on a real interrupted run,
an MCP `await` round-trip, and same-shape log lines from both processes.
