---
type: prd
status: active
tags: [prd, agent, runtime, architecture]
---

> **SUPERSEDED AS AN ORDERING (2026-07-26, owner ruling O17).** This file is
> EVIDENCE. The one ordering for this chunk lives in
> [roadmap.md](roadmap.md) under "THE ONE ORDERED LEDGER". Seven
> orderings across six files in five naming schemes is why the previous plan
> had no referent — do not sequence work from here.

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Unified plan — the all-JVM CLJC/sci runtime (2026-07-23)

One plan from here to the final system gate. Synthesizes the accepted designs
(capability seam · claim/lease loop · LLM/HTTP containment) and the
four convergence research reports (JVM concurrency+interpreter steps ·
web/SSE · writer throughput · render/ctx portability), under rulings 9–26.
The anchor (`program-synthesis-2026-07-21.md`) remains the state
ledger; THIS file is the implementation queue authority. Every unit
below is sol-dispatchable; specs derive from its row plus the cited
research sections. Breaking the Bun side during the port is
authorized (owner); each unit names what it breaks and what proves
it done.

## End-state process topology (Ruling 26)

Per cluster, supervised by the one operator:

1. **Cluster JVM** — the one process for one store. It owns transactions, the
   committed feed, the claim-native driver, model I/O, and every agent eval.
   Reads are pointers into immutable database values and writes are function
   calls. Scale by adding clusters, never by adding processes to one store.
2. **Web/render JVM** (owner ruling, 2026-07-23: rendering is
   decoupled — pure derivation over a replica; NOTHING agent-
   controlled runs here, so nothing can take the UI down): the
   web/SSE tier (http-kit — multi-threaded as the old server was —
   with the vendored datastar-clojure adapter, vthread per
   connection) serving derived views from its db.host replica
   session. Kill/restart/redeploy freely; it never runs agent code.
3. **Leaf runtimes** — DISPOSABLE package processes: serve
   seon.packages.js.* + the diffusion/typeahead worker over the
   capability wire. No durable state; kill/restart anytime; in-flight calls
   die as flat error values and recovery follows the effect class.
4. **Browser** — static assets only (verified: no browser cljs
   exists; morphed HTML + vendored datastar.js).

## Ruling 27 — limits are circuit breakers, never governors

Born of the writer-throttle lesson (a hidden implicit limit hit in
NORMAL operation): every guard in this plan obeys four laws.
(a) Every limit is an aero manifest entry resolved to a
`:seon.config` fact — full Malli schema + docstring stating unit,
default, what it protects, what firing means, and calibration
provenance. (b) Defaults are set from MEASUREMENT at ≥100× the
legitimate P99.9 — normal work never encounters a limit; only
genuine runaway trips it. (c) Firing is always LOUD: fault datom +
steering error value; never a silent slowdown or drop. (d) No
numeric limit literal in runtime code — limits flow through config
accessors only (the W1 config-facts sweep is the enforcement
vehicle and folds into U1/U8 acceptance). Applies to SCI `time-limit`,
bounded projections, `:compute` / `:io` capacity, heap bounds, beat cadence
(post-U3 a pure failover-UX dial), and every future dial.

## Units

### U1 — SCI interruption (S/M) — READY, no dependencies

Every SCI invocation installs the one `:interrupt-fn`, which SCI calls at
every `fn` body entrance. `time-limit` is the only execution limit and expiry
calls `interrupt!` uncatchably. `:seon.eval/fn-entries` is a diagnostic only.
SCI work uses `:compute`; blocking calls use `:io`. Falsifier: a hostile
`(loop [] (recur))` stops at its `time-limit`, returns the flat error value,
and leaves sibling work unaffected.

### U2 — Claim-native driver (L) — READY after U1; THE SPINE

Ruling 24 break-and-replace: the portable `.cljc` driver (claim →
render → LLM → parse → eval-dispatch → write-back → advance) built
once, claim/epoch/lease/phase-cursor native (loop design §1–3 +
rulings 22/23: `:seon.agent.run/process`, reacquire
nil→me + e→inc(e), retraction on close/pause). One vthread per
claimed run (`Thread/ofVirtual`), evals submitted to `:compute` under U1's
`:interrupt-fn`; `Future.get` parks the
vthread. The legacy Bun loop-driving path is DELETED in the same
change — no fence retrofit, no dual-driver compat. Runs in the Core
JVM app (topology above); the pod stops driving turns (BREAKS Bun
agent-driving until U5/U6 restore surfaces — authorized).
Falsifiers (all from the accepted designs): two-driver race,
pause/resume/reacquire, the five kill points, and **the U12 drill:
kill the cluster JVM mid-turn on the demo scenario, restart, the run
completes with zero lost/doubled effects**.
Grounding: `research/loop-cljc-sci-design-2026-07-23.md` (whole),
concurrency recipe §adoption. Gotchas pre-ruled: interrupt-during-
blocking UDS calls use `:io`; heap sizing is cluster-wide.

Corrective checkpoint `094e7a7e6` (2026-07-24) closes the attempt-timeout
acquisition split and the direct-phase-error lease wedge in this owner.
Focused writer proof is 22 tests/137 assertions; portable driver and config
proof is 16 tests/110 assertions. The isolated `claimantpath` drive proves an
epoch-2 JVM phase error now persists a fault and atomically closes/releases the
run instead of heartbeating forever. Commits `62cd2348b` + `356519dd0` then
fixed database-value identity allocation, and source-frozen claimant2 live
proof crossed the complete model boundary: host workload PID `50645`, DeepSeek
HTTP 200, durable `:success` attempt, and a 163-byte reply blob. Commit
`fdba88aad` also aligns reply-text and config-singleton acquisition.

The `planschema` checkpoint (`0ae0fda9e`, `f6dd94682`, `3fd9137f6`) closes
committed toolkit-schema lookup, active-turn timeout terminalization, and the
claimant's stale allocation wrapper. The isolated JVM claimant persisted a
nested `my.plan` root and a schema-backed memory fact, then read back
`CLAIMANT_MEMORY_ALIVE`; every turn settled and custody is absent. The final
single `seon.agent.lifecycle/complete` form still reproduces the separate
exact-execution-plan refusal.

The earliest unsettled U2 contract is now
[[../../seon/issues/jvm-claimant-rejects-visible-reply-without-exact-execution-plan]]:
the same valid reply is rejected before eval because no inspected tier yields
an exact execution plan. Its integrated proof is two terminal successful eval
receipts plus a completed run from the retained reply. The dependency-ready
parallel portfolio is
[[../../seon/issues/pod-republication-passes-nil-reusable-projection]] and
[[../../seon/issues/named-cluster-open-does-not-reconcile-jvm-host]]. The next
U2 refill is the execution-plan acquisition/enforcement owner; the final system gate
remains the rebuilt default-cluster real-work and database-memory redrive.

### U3 — Writer admission fix (M) — READY, parallel with U2

Remove the artificial ceiling: admit >1 in-flight `:mutation` per
database so Datahike's commit batching engages (est. 5–20×).
Requires the in-flight request-id cache (recovery must distinguish
outstanding mutations; today recover-current sees only committed
receipts) + the allocation-tx carve-out. Writer-owned files only;
agent code untouched. Grounding:
`research/writer-throughput-research-2026-07-23.md` §5.1 + probe
plan. Falsifier: probe A′ shows batching engaged (queue-depth
scaling); recovery regression proves crash with N in flight
resolves every receipt correctly. Probes A/B/C run as acceptance.

### U4 — Render purity + R0/R1/R2 (M) — READY, parallel

Fold: the 11 ambient-db fallback doors → `:seon.db/db` required
block input + loud failure (I8 precedent); host-timezone impurity;
I5 file fingerprints + SOUL env; vestigial dial deletion; the
my.canvas `:clj` nonexistent-fn defect; R0 in-pod driver move; R1
pure renames (my.ui 7 census rows); R2 date shim. Grounding:
`research/render-ctx-portability-research-2026-07-23.md` inventory
and ledger. Falsifier: same db value ⇒ byte-identical context render
across process restart (the stage-5 gate's core assertion, minus
the deliberately-ephemeral tail).

### U5 — JVM web/SSE tier (M then L) — READY, parallel

First slice: `/data` + `/data/feed` served from the **Core JVM
app** (NOT the writer — ruling 26; the db.host session provides the
replica read + feed subscription), http-kit as-channel + vendored
datastar-clojure adapter, vthread per connection, seon.reactive
registry kept (single-threaded invariants made explicit — the named
MEDIUM risk). Then the remaining ~7.8k near-verbatim move + the
~1.5k platform-seam rewrite; ~300 LOC Bun↔Ring translation deletes.
Never resurrect: Integrant, sse-flow, the old renderer. Grounding:
`research/jvm-web-sse-research-2026-07-23.md`. Falsifier (slice):
transact → second morph via server-side SSE client, gzip on.

### U6 — LLM I/O port (M) — HELD for the owner HTTP discussion

L1 attempt receipts (open/terminal, deletes the !attempts atom) +
the portable adapter core (builders/interpreters/retry decisions —
already ~80% stateless) ride now-ish; the JVM `java.net.http` leaf
shape is designed but its implementation timing is the owner
discussion (ruling 20c amended). Until it lands, cluster JVM call LLM
through the pod service. Grounding:
`research/llm-http-io-design-2026-07-23.md`.

### U7 — R3/R4 ctx port (M then L ~6.4k) — after U1 (door) + U5

R3 render core + canvas (11 census rows; canvas renders behind the
door); R4 the full ctx port with the one structural eval re-seam
(`lookup-value` → static trusted table vs `:interrupt-fn` via
`agent-authored-sym?`). Acquisition is already protocol-member
data; one portable executor makes blocks acquire→execute→format.

### U8 — Rolling portfolio (S–M each; fill idle slots always)

JVM leaves fs/shell/web/blob + host bindings (~28 census rows) ·
my.* host bindings (plan/kb/skills) · the 5 straggler ports ·
triage M items #8 (db result/error-shape), #6 (host session
errors), #10 (datastar stale render) · compat-residue migrations
(context.clj:202 callers → seon.db.host) · seon.packages.jvm.*
add-lib research → unit · NS-1b resume · NS-4/5 · normalize-5 ·
seam observability (receipt durations — also unlocks honest seam
perf numbers) · 11 triage probes.

### U9 — The great deletion (L) — after U2 + U5 + U7

W5: per-agent children, eval.cljs self-host, child bands in
execution*, child Shadow/operator plumbing, ~5.7k LOC of
child/self-host tests — per the deletion-audit inventory (its
consumers re-pointed by U2/U5/U7). Bun pod becomes the leaf host
(topology #3). Census cutover assertion flips at zero.

### U10 — System drills (rolling, final gate)

U12 kill-anything-anytime (cluster JVM, web-render JVM, leaf runtime — at the
audit's five kill points) · the
100-agent U10 drill · writer probes at target load · q18 OOME ·
byte-identity render gate. THE SYSTEM GATE = all green on the demo
scenario with the census at zero.

## The derived-execution program (added 2026-07-23 PM, owner green-lit)

Grounded in research/execution-planning-design-2026-07-23.md (accepted),
the bug-class triage, and rulings R29-R35. Extends this plan's queue; the
original U-units continue in parallel where still open.

### P1 — Edge bundle (L) — DISPATCHED, the earliest unsettled contract

Canonical direct-edge program graph: function→function call edges (both
tees, one schema owner), typed read/write attribute edges + :all-at-basis
markers, explicit uncertainty edges for dynamic construction (fail-closed,
never silently empty), effect/leaf descriptors from the seam metadata,
per-artifact export inventories (private terminals without public
:seon.fn rows), all entering the program-graph generation digest.
Falsifier: exact edge facts for a representative fixture on both tees;
digest changes when any edge changes. Spec:
tmp/orchestrator/edge-bundle-spec.md.

### P2 — The pure planner (M) — after P1

plan-execution over the P1 edges: one derivation returning placement
(:anywhere | :constrained | :unplannable), eligible tiers, schema +
capability manifests, unresolved edges, cache key (basis + commit +
graph digest + projection fingerprint + inventory digests). Sole
placement authority; derived-never-stored (R21). Falsifier: the design's
§2 folds proven over the P1 fixture corpus, incl. fail-closed
:unplannable on the dynamic case.

### P3 — Registration completion (M-L) — parallel with P2 (R29)

Pull-pattern admission (read-side validation vs the committed
projection, steering distinguishing derived projection keys from stored
attributes); the computed transactable population replacing the
agent-bootstrap-attrs hand list; manifest verification on persistent
leaves (full-projection coverage check, the owner's JIT posture).
Falsifier: the drill's pull-side class cannot recur (regression); the
hand list is gone; a leaf with a stale projection re-acquires and
reverifies.

### P4 — R33 predicate admission (M) — after P2

A named [:fn] predicate is admissible iff its plan placement is
:anywhere; run at schema commit, on reachable-fn change, and at
projection reconstruction (fail-closed on historical combinations).
Composes with the schema-admission lane's R30/R31/R34 gates.

### P5 — Driver enforcement + router deletion (M) — after P2

Pre-dispatch plan → verify coverage → compare inventories → provision →
execute; provision the permitted leaf or return a flat error naming missing
leaves/schemas/edges/bases. The mixed-tier router
becomes a planner consumer: its AST/loader/prefix scans DELETE (R18
becomes consumer behavior). The regex purity classifier
(host/context.clj pure-block?) deletes into the plan.

### P6 — Transparent distribution (L) — after P2+P5 (R35, the capstone)

The invoke request/response family on the seon.db typed protocol
(schema-projected args/results, receipts riding as for db effects,
same-tier call coalescing); the placement-aware wrapper installer
(local impl vs wire-calling stub per var; sync on vthreads, awaited on
pod); the R32 result-symbol lifecycle registry (handles tracked per
instance identity, wiped on that platform's reset/restart, steering to
re-derive). Falsifier: an agent form whose call graph spans three tiers
completes with no placement annotation, correct receipts, and a handle
that survives use, dies loudly on its platform's restart, and steers to
re-derivation.

Sequencing: P1 → P2 → {P3 ∥ P4 ∥ P5} → P6. The original U7 (ctx port),
U6b (transport leaf), schema-admission, and U8 portfolio continue in
their own lanes; U9 (great deletion) additionally waits for P5's router
deletion; U10 system drills resume after the core-hardening queue
(C-classes) and P3 land.

## Sequencing

```text
U1 ──► U2 ──────────────► U9 ──► U10
  \        (spine)        ▲
   parallel: U3, U4, U5 ──┤
   U6 (after owner talk) ─┤
   U7 (after U1+U5) ──────┘
   U8 fills every idle slot throughout

```

## Open owner decisions

- **D1 (beat cadence):** a failover-UX dial, not a throughput knob
  once U3 lands; config fact per ruling 27. Interim default 30s
  (pre-U3 writer math); post-U3 set to whatever failover feel you
  want.
- **D2 (cross-run beat batching):** blocked by CAS-abort-whole-tx
  semantics; only worth deciding if U3's 5–20× proves insufficient.
- **D3 — DECIDED (owner, 2026-07-23):** web/render is its OWN
  process (topology #2) — rendering is pure derivation over a
  replica and nothing agent-controlled can touch it.
- **D4 — PARTIALLY SETTLED (owner, 2026-07-23):** the JVM LLM leaf
  supports BOTH streaming and normal sessions; remaining morning
  item is timing/priority only.
- **Demo cadence (owner):** re-runs are BUG-FINDERS at orchestrator
  discretion — cheap, focused on proving the conversion right;
  never benchmark sweeps.
- **D5 — REFRAMED by ruling 27:** `time-limit` is the only SCI execution
  limit; `:seon.eval/fn-entries` is diagnostic evidence, never a budget.

## Final system gate

Kill anything at any time — cluster JVM, web-render JVM, leaf runtime — and the
demo scenario completes with zero lost or doubled effects; census
at zero with the cutover assertion flipped; 100 concurrent agents
on one cluster within the measured writer envelope; every SCI invocation uses
the one `:interrupt-fn` and `time-limit`; agent-authored code is absent from the
web-render JVM.
