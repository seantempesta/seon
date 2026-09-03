---
type: prd
status: active
tags: [prd, agent, context, architecture]
---

# The working edge — context-generation program

*THE one live record of current state and ordering (owner ruling,
2026-08-29): write-through in the session it changes, path-limited
commits. The sci-execution-runtime `unsettled.md` is tombstoned and
historical. Dates are absolute; a stale claim here is a defect.*

## Current state (2026-09-02, afternoon)

**Owner reframe (2026-09-02, conversational, supersedes the reader-centric
spelling of 53–55):** the agent is dropped into a Clojure REPL in its own
namespace. Context = the data discovered from the agent's entity outward
+ the BEST render function for each value (priority chain: an inline
render on the value → a function in the agent's own namespace whose input
schema is the data's schema and whose output schema is `:seon.render/ai`
→ the family's schema-declared face → the floor) + the teaching needed to
explain what was shown, derived by walking back from the render/query
functions used: `doc` and `dir`, never prose walls. `doc` becomes
polymorphic — anything, or a list of anythings (namespace, function,
test, schema, value) — showing the relevant parts; we own every tool and
tailor it to the system while keeping its Clojure spirit. Queries stay
legit Datalog/pull with good examples: easy first query, then "what is
new" via `since`/`as-of`/tx-meta conventions. Every agent in every
namespace is tutorialized on ITS neighbourhood and encouraged to write
its own render functions, which also become HTML interfaces. Delta
mechanism: OPEN — probe in the REPL first (evidence:
[repl-first-probes-2026-09-02.md](../research/repl-first-probes-2026-09-02.md)).
Budget: compaction over evals (ruled). Sequencing: reds first, then the
generator concurrent with the bridge lane (ruled).

**Platform (derived 2026-09-02, `bin/test --all` at 43e5e2fff,
tmp/gate-all-2026-09-02.log):** platform tier 72 tests, 1 error —
`cohost-boot-test/a-second-cluster-boots-…` hit the 270 s exchange bound
under heavy load and passed its isolated confirmation (202 s); five
platform tests take 200–244 s each (slow-is-a-bug, unmeasured cause).
The bulk tier did not run. NEW DEFECTS this session, lanes launched (all
six first launches died to intermittent DNS failures reaching the Codex
endpoint; relaunched under `-2` names): bare `bin/test` cannot record
persistent results while any cluster holds the store AND the refusal
throws before the tally (`gate-evidence-2`); `runtime_status`
missing-projection (`mcp-status-2`); `/agent/<id>/debug` swallows the
prospective-context cause (`debug-page`, relaunch owed); two
`gen.loop-test` census errors (`gen-loop-2`); the two armed reds
(`armed-reds-2`); the attempt-traces blocker (`attempt-traces-2`). Filed
without a lane yet: `seon.db` reads rebuild the projection per call when
none is handed — 2.4 s vs 0.1 ms raw
([issue](../../../seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md),
class/p1, blocker for "context is queries"); `doc` contract lines print
schema bodies and flatten arities
([issue](../../../seon/issues/doc-contract-lines-print-schema-bodies-and-flatten-arity-alternatives.md),
now critical-path teaching work).

## Session resumed (2026-09-02, evening) — lane round 1 outcome, round 2 launched

**Landed (reviewed):** `3c162853f` armed-reds — both armed reds green; the
fault WAS committed as a fact all along: core.async.flow retains a proc's
pre-transition `:paused` status after a throwing transition
(`reference-code/core.async/.../flow/impl.clj:282`), so the test wedged in
teardown's armer-quiescence wait, not in the fact await; the boot-window
test now awaits the run whose trigger is the boot-window message.
`927229929` gen-loop — the fixture treated receipt-row presence after a
fixed drive loop as settlement; it now awaits the exact terminal census
through the bounded event boundary (production settlement is synchronous
and atomic, `loop.clj:682`/`run.clj:1108`; a writer race was REFUTED).
`fb3a61abe` gate-evidence — bare-gate evidence routes through the live
store holder's advertised prepl (offline when no holder), the tally
prints BEFORE persistence and the exit code derives from tests alone,
recording failure is one loud typed line. `ab9559929` attempt-traces —
the fixture's no-backup world made explicit; whether the seed is green at
HEAD is UNVERIFIED (lane round 2). `612122fa6` issue: the preflight sweep
race.

**New blockers found by the lanes:** the evidence write through the live
holder is REFUSED because instrumentation installs the SIBLING's contract
on `seon.schema.datahike/resolve-datahike-form`
([issue](../../../seon/issues/instrumentation-installs-sibling-contract-on-datahike-resolver.md),
class/p1 — stale-green stays UNKNOWN until fixed); `bin/test`'s preflight
sweep races concurrent invocations and aborts gates
([issue](../../../seon/issues/bin-test-preflight-sweep-races-concurrent-invocations.md)).
Diagnosed without landing: `runtime_status` (cluster.clj:509 lacks the
projection; fix pattern = `mcp-effective`), `/agent/<id>/debug` (page
renders only the message; suspected missing `:seon.render/profile`).

**Round 2 lanes (launched ~21:30Z, tree quiet):** `mcp-status-3`,
`debug-page-3`, `sibling-contract`, `sweep-race`, `db-projection`
(the class/p1 per-call projection rebuild), `attempt-traces-3`. Exhaust:
six returned lane roots swept; one orphan runner-exchange helper reaped;
`tmp/test-runs` holds 1.0 GB of retained roots (holderless; sweep after
the sweep-race lane lands).

**Design (2026-09-03):** the owner ruled the four forks (ledger 56) and set the phase rule — BEHAVIORS FIRST: [repl-first-behavior-2026-09-03.md](repl-first-behavior-2026-09-03.md) is the behavior spec under markup (B1–B12, 19 ❓ each with a recommendation); implementation talk only after every ❓ is gone. The `doc-polymorphic` lane was stopped for that reason (resume when B6 settles). Earlier text: the [design draft](repl-first-context-design-2026-09-02.md) §9 carried four forks for the owner (faces as contracted functions vs the
schema property; since-shaped delta vs revision diff; implicit printer vs
an explicit agent-callable; identities on `[*]` ref leaves). Generator
work starts only after his answers and the platform items above.

## 2026-09-03 (afternoon) — round 2 outcome, round 3, the behavior spec

**Landed:** `1b22034b6` sweep-race (claim-before-sweepable + vanished path
= success); `768c6a0e0` db-projection (projection cached by exact
committed identity: wrapper 0.220 → 0.048 ms; issue stays open only for
the literal 2× ratio — owner decision on a decoded-result cache);
`8fa146805` sibling-contract REFUTED (the 1-arg resolver's body calls the
2-arg sibling, which truthfully names itself; the real defect is the
projectionless naked call — owned by
`malli-form-predicate-resolves-the-declaration-population-itself`);
`98b6175be` attempt-traces closed (fixture-side, seed proof recorded);
`1ecd7054e` mcp-status landed by the orchestrator from lane -3's draft
(issue archived). Still running: `debug-page-3`, `parallel-paths-census`
(research: the refactor/merge/delete register the owner asked for),
`analyze-form-row` (BLOCKER: root's contracted defn never settled —
analyze-form returned `{:seon.ns/name nil}`; root burned a 44-form paid
run), `reap-nil-path`.

**Behavior spec:** [repl-first-behavior-2026-09-03.md](repl-first-behavior-2026-09-03.md)
now carries §G (the walk as forms), B4 with the MEASURED fit ladder
(strings halve to zero first — the dumb clipping), B5 with real diff
bytes and the `since` lookup-ref trap, G6 render provenance (cost fact
lacks the producer symbol). Rulings 56–57 sealed. Open ❓ listed at its
foot; the owner rules them, then implementation talk begins.

**Tool defects this round:** `get_value` elides large strings with no
paging ([issue](../../../seon/issues/mcp-get-value-elides-large-strings-with-no-way-to-page-them.md));
MCP door prints zero-character strings above the blob threshold (same
issue). `runtime_status` fix landed but the live `ctxprobe` JVM serves
pre-fix code until restart.

## 2026-09-03 (late afternoon) — round 3 landed, round 4 launched, ruling 58

**Landed:** `ee11cfa45`/`ba94d7b3c` analyze-form-row — a nil namespace pull
now returns a typed `:seon.fn/namespace-unresolvable` error, never a
partial row; the exact prose-prefixed defn settles at HEAD (real run-loop
regression added; the historical cause on ctxprobe is attributed to that
JVM's age, not the current path); `b136f574f` reap-nil-path — malformed
claims are named refusals in the reap result (the nil was NOT a root path,
see the new class below); `78cee3fea` debug-page-3 — debug requests carry
the agent profile and the page renders the diagnostic. Research landed:
[parallel-paths-register-2026-09-03.md](../research/parallel-paths-register-2026-09-03.md)
(three semantic context assemblers, two prompt glue paths; the
refactor/merge/delete register for the implementation phase).

**New class found by two lanes independently — instrumented Vars refuse
values their own declarations allow:** `seon.fs/delete-recursively!`'s
2-arity re-enters the wrapped 3-arity with nil options → every 2-arg
deletion refuses under instrumentation
([issue](../../../seon/issues/delete-recursively-two-arity-refuses-under-instrumentation.md));
`seon.context/message-custody` declares `[:maybe run-id]` and is refused
nil → the history walk fails for any message when the agent has no
current run — a context-generation blocker between runs
([issue](../../../seon/issues/instrumentation-rejects-message-custodys-declared-absent-run.md)).
Lane `instrument-absent-args` owns the class. Also: the operator
parked-collection regression no longer reaches its GC latch
([issue](../../../seon/issues/parked-collection-regression-no-longer-reaches-gc-latch.md),
lane `parked-collection`); lanes are not given the Seon MCP tools
([issue](../../../seon/issues/lane-toolset-omits-required-seon-mcp-tools.md)).

**Ruling 58 (owner):** `(help)` bootstraps generatively; render functions
accrete ACROSS agent namespaces with the whole order as one query; render
provenance rides the existing after-value comment; compaction = a fresh
session that loses nothing, and the system is developed for FULL
REGENERATION EVERY TURN first — incremental diffs (56b/B5) are a later
wave. The behavior spec's B1/B3/B5/B8 carry it.

## Previous state (2026-08-29, evening)

**Design track (owner still forming — NO implementation until he says):**
rulings 47–55 sealed in the [ledger](design-ideas-ledger-2026-08-13.md):
the population invariant + symbol identities (47), scalar identity +
result projections + the rename pass scope (48), keys-law as amended
(49), the full-parse bridge (50, design verified against kondo:
[full-parse-bridge-design-2026-08-29.md](full-parse-bridge-design-2026-08-29.md)),
graph closure + settled-form usages + derived self-improvement (51),
VIEW-1-ONLY stable regeneration + coverage-set help + backward
demand-driven generation (52/52a/52b), faces return forms (53), the
write door + missile rule + steward drive scenario (54), instance
args (55). The consolidation of 47–55 + the
[render-data plan](render-data-plan-2026-08-28.md) into one
implementable generator spec is OFFERED, awaiting the owner's go.

**Base-system track (active):** platform tier GREEN; bulk tier legible
(runner: serialized loads, bounded exchanges, one-retirement-one-
failure); walk acquisition 8.5 ms; hook feedback restored after a
15-day silent outage; fixture derivation primitive + 53-site sweep
landed; five graph-consequence regressions fixed; schema lifecycle
over persisted references repaired; the bare-remainder singletons
landed (`c5036aaa2`) — and their one refused red exposed the
25-minute curation replay storm, killed by the population-revision
prelude cache (`e8c8ea6d0`: the prelude derives once per program
population, not once per settled form). The masking meta-lesson:
four Aug-14 breakages hid behind the 12-day bulk blackout.

## The ordering (owner rulings, 2026-08-29 question round)

1. **Doomed-nine deletion pass** (owner: bare reads fully green before
   any rename): delete dead `walk/prose` + `effect/context-suffix` (+
   their tests); neutralize the 6 `render.web-test` + 3
   `render.value/ns-test` reds with wave-G/S2-F issue links — never
   polish, delete or park with a named replacement.
2. **Stale-green visibility lane** (before the freeze): persistent
   operator-owned bare-gate results branch; `bin/seon status` derives
   per-namespace "all current tests last known green; oldest proof
   basis T, N days ago"; unknown ≠ green. Bundles the dev-cache
   `ensure-cache` wiring (same never-stale-silently class).
3. **The atomic identity freeze** (orchestrator, quiet tree):
   `:seon.fn/sym`/`:seon.test/sym` string→symbol + the sym↔`/ns` drift
   regression (47/48a) + receipts→evals rename (48c). Retype + reset,
   never migrate. `bin/seon init` + full gates close it.
4. **The full-parse bridge lane** (50, born compliant on the clean
   identities) — then result projections at settlement (48b).
5. Then the generator work — gated on the owner's context-design go.

## The armed-boot regression round (2026-08-29 night, lane running)

The distance-2 bootstrap fix (28 s -> 1.2 s per advance, measured
live) unstarved the armed backstops and exposed three REAL
regressions from the day's landed work, now with the
`armed-regressions` lane: (1) BOOT SPENDS A MODEL CALL — the
no-model-at-boot gate broke (`booting-spends-no-model-call` red,
`:seon.ai/no-credential` where an injected kind belonged); (2) a
boot-window message's run opens with trigger `bootstrap-task:root`
instead of the message; (3) an agent's own def fails to resolve in
its live ctx across cohosted clusters. Evidence root:
`tmp/test-runs/run.FiH5MT`. ROUND OUTCOME: the armed-regressions lane
closed the model-call gate (bootstrap closes atomically before the
model boundary, `108b753ca`) and grounded the cross-cluster fixture
(`801347921`); the orchestrator fixed the backup-target expectation
(402-failover config), and the exchange-vs-watchdog horizon collision
(exchange bounds now fire strictly inside the silence horizon). THE
FREEZE REMAINDER IS EXACTLY THREE: (1)
`the-first-cluster-proc-fault-at-resume-becomes-a-fact` — the injected
first resume fault never reaches its terminal worker event (real
behavior question in the fault-at-resume path); (2)
`a-message-committed-during-boot-arming-is-conserved` — the test
conflates run opening with downstream provider progress and needs its
await reworked onto the run-open fact; (3) the standing seed-recorded
generated-attempt-traces blocker.

## Open blockers the edge tracks

- [generated-model-attempt-traces-diverge-from-durable-facts](../../../seon/issues/generated-model-attempt-traces-diverge-from-durable-facts.md)
  — exact seed `202607280402` + shrunk case recorded; the one
  legitimate red expected in bare until fixed.
- The 69-GB store-growth class (exclusive-sweep wave) and the
  dev-cache staleness issue (rides item 2).
- `effective-config` deferred census rows in lane-protected files
  (effect_test launcher rows 2–3) — sweep them when those files quiet.
- `seon.cluster.curate-test` re-verify after the visibility lane
  lands: its residual red ("first-party program namespace
  seon.dev.fresh-operator-test could not be loaded") coincides with
  that lane's in-flight edits to exactly that file — torn-snapshot
  suspicion, not yet attributed.

## Standing session-start line

Read the
[context-as-queries handoff](context-as-queries-handoff-2026-08-29.md)
first — it is the entry document for the next session (the goal, the
owner's "it's all queries" idea to explore WITHOUT rushing, the trials,
and the platform gate). Then THIS file end to end, then the ledger's
newest rulings, then `bin/seon status` + `git log --oneline -15`.
