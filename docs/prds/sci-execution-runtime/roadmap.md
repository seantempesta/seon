---
type: prd
status: active
tags: [prd, agent, architecture, database]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Sci execution runtime roadmap

## The final system gate (owner, 2026-07-25 night) — READ THIS FIRST

This is what "done" means. Not a slogan: every line is falsifiable, and a
session that cannot point at one of these is not finished, however green its
suite is.

**Live agents, really running.** Not a fixture, not a drive script. Real agents
take real turns against a real model in the default cluster, author functions
into the one corpus, message each other, and are still running an hour later.
The proof is a transcript and committed datoms, not a passing test.

**Load-tested, by us, on purpose.** We drive it until something breaks, and we
know which thing broke and why. Not "it seems fine" — a number, a ceiling, and
the name of the resource that hit it. We already know the shape of the answer:
the commit path is one core, SCI is ~5% of a turn, and the model dwarfs both.
Find the real wall.

**Kick-ass fast, measured.** Boot in seconds, agent start in milliseconds, a
turn dominated by the model call and nothing else. Every performance claim
carries the conditions it was measured under — this program has already been
misled twice by a number without its context.

**Every weird smell chased to its cause.** A coercion, an inconsistency, a
duplicate mechanism, a silently-wrong default: each one is a bug until proven
otherwise, and it gets an issue with evidence even when it is not fixed today.
Today alone produced the vector-order defect, `read-string` honouring
`*read-eval*`, and limits that did not bound — all found by pulling on
something that merely looked odd.

**Clojure already solved most of this — go read it.** Before inventing any
mechanism, find where Clojure, `core.async`, `core.async.flow`, SCI, or
Datahike already answers it, and take their answer *and their name for it*.
This program's best decisions were all of that shape: `:interrupt-fn` over an
invented door, `:io`/`:compute` over invented pool names, flow's `transform`
discipline, the admin surface we get for free by putting state in a database,
and `[:set X]` over a bridge rewrite. **The wheel is round. Every time we
reinvented it today, the evidence took it away from us.**

**And the standing test, from the owner:** *is this simpler than it was?* If it
is equally complex, the model was ported, not applied.

## Program goal (owner, 2026-07-20 night)

**Complete the transition: Seon runs fully on this architecture.** The
final system gate is a working system — every agent on a `ctx` in the cluster JVM, the
child fleet deleted, three suites green, the drills passing at
integration level, and one live demonstration: a cluster of 100 agents
doing real work (defs, db, capabilities, canvas) surviving a host kill
and a pod restart without fact loss or operator intervention. Exploration
is over (B1/B2/C1/seam all PASS); this roadmap is now the active
transition ledger.

## Transition ledger (dependency order)

| # | Unit | State |
|---|---|---|
| U1 | seon.host skeleton + kill drill | **DONE** `cd239b79` |
| U2 | wrapper registry + capability op-id receipts | **DONE** |
| U1.5 | pod dials the host: tier-as-data dispatch, one REAL turn end-to-end (pod renders, host evals) | **DONE** |
| U4 | eval-record/receipt/corpus integration over the marked seams (subsumes register R2 — the program-row rejection diagnosis) | **DONE** |
| U5 | toolkit port — corrected dependency loader → registry provisioning → stdlib shims shape | **DONE** `48b31f59`, `535e8c9d`, `f037cbbf`, `5ac8f0ef`, `67358be2` |
| U3 | accretion walking skeleton; R48 interim containment refusal until P4/R33 pure-call-graph admission | **ESCAPE CLOSED** `3bb7c2d39`; native gate reopens only at P4 |
| U6 | instrumentation over sci vars (B1 deferred item 5) | after U4 |
| U7 | park/idle policy + warm spares (owner-ruled shape) | after U1.5 |
| U8 | steering/context re-alignment: all agent-facing guidance teaches the sync idiom | before cutover |
| U9 | await-corpus migration pass (measured, small) | before cutover |
| U10 | integration drills: host kill + pod restart with live agents, derived notices proven | gate |
| U11 | children retirement, deletion commit, architecture docs + one-mechanism table | cutover |
| U13 | agent package provisioning: `my.pkg/install` for :npm (pod host, `bun add` + wrapper gen) and :maven (JVM host, runtime classloader + allowlisted binding table), gated by config-fact policy with provenance, delivered through the U2 registry (lazy to all contexts, epoch-upgradeable wrappers). C2's admission guard steers js-form attempts toward the capability | after U4; demo-able before U11 |
| U12 | final system demo: N=100 live fleet, real work, kill + restart survival | **the gate** |

Concurrent: outside agents complete the source-cleanup stages; the
optional Bun sci tier (variant B) is decided at U11 with C2's js-bound
audit.

## Follow-on (non-blocking, after the U-series)

| # | Unit | State |
|---|---|---|
| F1 | Cloud store surfaces (S3/GCS): cloud-primary via konserve tiered store ranked first, replicate-to-cloud as second config; ordered U-cs1..U-cs5 sketch, cost model (3 RTTs/commit, live median commit gap 154 ms), and blockers in [research/cloud-store-surfaces-2026-07-20.md](research/cloud-store-surfaces-2026-07-20.md) | researched; gated on mirroring konserve-s3/gcs into `reference-code/` |

## Outcome

Replace the execution child's self-host `cljs.js` engine with sci's
JIT-tier evaluation, exploring two variants to a measured decision:

- **Variant B — sci-JIT Bun children**: engine swap inside the existing
  per-agent child. Full semantic compatibility (native `^:async`/`await`
  over real Promises, js interop, agent macros); burst retention returns
  (~90 MB settled vs 416 MB permanent); small-form eval 10-16x faster;
  in-process interruption via `:interrupt-fn`.
- **Variant C — JVM sci agent host**: one JVM process beside the writer,
  a sci context per agent (22.7 KB marginal at N=100 via structural
  sharing), `Thread/interrupt` containment, database calls become plain
  synchronous calls over the existing UDS protocol's JVM client side.
  Covers the 42% pure + 46% db-boundary toolkit surface; the 12%
  js-bound surface stays on a Bun tier.

They are not exclusive: B is the safe engine swap with no topology
change; C is the deeper simplification decided on B's usage data. The
Bun client pod (web UI, LLM, loop, rendering) is out of scope and
remains the application host under every variant.

## Design thesis (owner, 2026-07-20 evening)

Deep sci integration at the eval boundary — the harness owns the
interpreter, not merely hosts it:

- **Agents are CONTEXTS on HOSTS with BINDING TABLES** (sci's own
  vocabulary; never "sandbox"). A context is the agent's private state
  between evals; hosts are the JVM agent host and the Bun pod (the
  cluster's shared JS host); binding tables are the allowlisted
  capability surfaces packages are provisioned into.
- **Placement is derived from requires.** The persisted require graph
  (:seon.ns/require-edges) maps each namespace to its host; the eval
  boundary synthesizes remote-call crossings so cross-platform calls
  need no agent-visible FFI. The agent perceives ONE platform; every
  non-local capability (db, npm, Java, OS tools) is a remote function
  call with a pure-data transit boundary and the standard envelope.
- **The REPL concept is the interception seam**: parse -> repair ->
  route -> execute -> envelope -> persist-corrected. Platform routing is
  one more rewrite at the seam that already owns auto-await and
  augment-ns-source.
- Why: control (in-process interrupt, allowlisted tables), speed (JIT
  tier, 12 ms p50 turns, 3.4-3.9x envelope perf), reuse (one shared
  immutable program across contexts; 118 KB working marginal), and
  crash/restart behavior (contained failures; contexts rebuild from
  database facts; park/restore instead of process churn).
- Sci is EPL-1.0 (verified) — forkable on the existing
  datahike/shadow-cljs mirror model; prefer minimal upstreamable
  patches. Seam selection: [[research/sci-routing-seam-2026-07-20]]
  (in flight).

## Evidence base

The 2026-07-25 CLJS instrument checkpoint fixed the gate that precedes
load-testing and end-to-end measurement. Commit `1fbbc7b8e` takes Datahike's
entity-id, pull-selector, query-form, and connection-ID definitions directly,
pins the codec-totality seed to `424242`, and repairs test cleanup that leaked
global database stubs across namespaces. Two unchanged full runs now report
the same 1,304 tests, 6,697 assertions, zero failures, and one error. The one
error is named R28 fallout:
`seon.web.serve-test/model-transport-projection-is-ordered-bounded-and-fail-closed`
still asks the deleted pod turn stack for `:seon.ai.attempt/entity`. This
checkpoint makes the suite a stable instrument; it does not satisfy the live
load, performance, or end-to-end final system gates. Full evidence:
[[research/codec-test-instrument-repair-2026-07-25]].

The 2026-07-24 reset-boundary checkpoint fixed claimant-host committed
acquisition in `c2c5faeff`: one frozen database value, AEVT identity pages,
and one bounded query per variable-size form row. Fresh isolated start →
restart proved watcher, writer, host, pod, and web-render all alive/ready; the
host did not drain. See
[[../../seon/issues/archive/claimant-host-drains-after-clean-restart]].

The later `planschema` reset-boundary checkpoint proves acquisition
completeness at its first toolkit consumer. Commit `0ae0fda9e` binds claimant
schema lookup to the retained committed projection; `3fd9137f6` routes
claimant identity allocation through the portable allocator; and `f6dd94682`
atomically terminalizes an active turn on observation timeout. A live JVM
claimant persisted nested plan root `mft542256r45`, registered and transacted a
schema-backed memory fact, and read back `CLAIMANT_MEMORY_ALIVE`. All turns are
terminal and custody is absent. The final completion-form placement refusal
remains the earliest U2 contract; default-cluster cross-turn memory and U12
remain the system gates. Evidence:
`tmp/orchestrator/planschema-gate.log`.

[[../source-cleanup/research/sci-execution-child-feasibility-2026-07-20]]
(measured probe: retention, perf ratios, four semantic gaps, JVM context
sharing, port inventory, bb impossibility) built on
[[../source-cleanup/research/child-footprint-bisect-2026-07-20]] and
[[../source-cleanup/research/bun-shared-memory-options-2026-07-20]].
Reproducible harness: `tmp/sci-probe/`. Sci checkout at the JIT commit
(`45bcf0f`, reference only — sci is not yet a dependency).

## Known blockers (from the probe; each needs a closing gate)

1. General sci vs cljs.js semantic audit beyond the four probed gaps —
   drive the full agent test corpus (the eval/repl behavioral tests)
   through a sci engine before any cutover.
2. The 91 MB eager-schema band is orthogonal: fixed by lazy validator
   compilation at admission (register lever 3) — sequence it with or
   before B so the win is compounded, not attributed wrongly.
3. Retention re-proof at production anchoring (the probe anchored less
   live state than a real child).
4. Bundle-proportional ~60 MB floor for B — the child bundle must stay
   small; C makes the floor shared-once.
5. C only: GC blast-radius proof (OOME containment beyond one lucky
   run), the js-bound 12% tier design, and pod/host protocol for turn
   dispatch.
6. Sync contract: defs in a sci context must persist to the program
   graph through the SAME one corpus mechanism (no second registry);
   note sci value-defs actually improve on self-host here.

## Exploration order

### B1 — sci engine behind the existing eval boundary

Prototype `seon.eval`'s engine seam: the child hosts a sci context
armed with the same admitted bindings; the eval envelope, receipts,
`maybe-await-value`, augment-ns-source, and instrumentation flow
unchanged. Gate: the full existing CLJS eval/repl test selection green
against the sci engine in the harness (not yet wired into production);
divergence list written.

**Status: DONE — green with divergences (2026-07-20).** Evidence:
[[research/b1-eval-corpus-divergence-2026-07-20]]. The adapter
(`tmp/sci-probe/src/probe/adapter.cljs`) satisfies the production eval
envelope over `sci/eval-string+`; the ported corpus
(`src/probe/corpus.cljs`, 33 tests / 80 assertions naming their
production sources) is green 3/3 runs under the vendored bun.
0 blockers; 9 adapter-work items (error-prose synthesis,
warning→catch-site classification, binding-table provisioning replaces
guarded-load's bundle trick, sci resolution queries for
prose/preflight, instrumentation over sci vars, print-fn→ALS bridge,
setup-agent-ns! sci form, cljs.test-in-ctx, timeout prose);
5 improvements (value defs persist, in-process loop interrupt,
async-try quirk absent, direct defmacro, defs-as-data); 3 cosmetic.
Perf: 200-form burst 37–43 ms through the full envelope path vs
143 ms self-host (raw sci 8.8–13.6 ms).

### B2 — retention + perf at production anchoring

One real agent driven end-to-end on a sci child (branch cluster):
memory per phase, burst retention, turn latency vs today's child. Gate:
retention returns at production anchoring; no eval-latency regression.

**Status: DONE, gate PASS (2026-07-20).** Evidence:
[[research/b2-production-anchoring-2026-07-20]]. A sci-engined child
ARTIFACT VARIANT (`:execution-sci`, `seon.execution.sci-runtime` in the
harness source root; sci pinned in deps.edn as `:local/root` on the
reference checkout — packaging implication noted) boots through the
production `seon.execution/-main` (real IPC, session, admission), reuses
the production render entries, and swaps only the `eval-batch!` compiled
entry. One real agent on branch cluster `default-b2` drove 21 REAL
turns through `seon.agent.turn/run-turn!` with a scripted llm-fn, A/B
against the normal child on the same branch: 21/21 `:done` both, same
eval counts, errors-as-values parity. Footprint: settled **231M vs
442M** (−211M), peak 419M vs 701M, retention holds through a 202-form
burst + gc + 60 s. Latency: non-burst median **2728 ms vs 4258 ms**,
burst **64.3 s vs 345.9 s** (5.4×) — no regression (iso-context caveat:
final ctx 33.2k vs 47.1k tokens from the minimal tee). Implemented B1
items: 1, 2, 3 (computed binding provisioning), 6 (per-form), 7, 9.
Deferred punch list for the decision gate: 4, 5, 8, full program-graph
tee, result-var caps, failed-defs fencing, ALS-spanning print capture.
Blocker 3 closes; blocker 4 (bundle floor — the variant still ships
cljs.js unused) is the next B-side measurement. Fixed in passing: the
unparseable `my.plan` generated-namespace find clause that errored every
run-attached turn close on this branch.

### C1 — JVM host skeleton — DONE, gate PASS (2026-07-20)

The probe's JVM harness grown to: sci context per agent, admitted
bindings loaded once and shared, UDS client to the writer, thread-per-
eval with interrupt + deadline. Gate: N=100 contexts, one real turn's
worth of eval work each, marginal-memory and interrupt proofs repeated
at that scale; OOME blast-radius test repeated 20x.

Verdict ([[research/c1-jvm-host-scale-2026-07-20]], harness
`tmp/sci-probe/jvm/{src/probe/host.clj,host-run.sh}` on the exact
`:writer` basis against the LIVE default writer): **PASS**. N=100
one-real-turn wave 100/100 ok in 164 ms wall, **117.9 KB working-set
marginal**/context (18.6 KB idle); 805 real UDS
ping/head/query/pull round-trips at ~2 ms mean through the one
existing `seon.db.transport.uds` client; 10 runaways among 90 healthy
all interrupted **≤5 ms past a 500 ms deadline** with healthy p99
3 ms; OOME blast radius **20/20 process survivals**, 200/200 survivor
pure + 200/200 survivor live-db evals ok, 100/100 concurrent evals ok
during bombs; N=100 host ~55 MB used heap / ~505 MB Physical
footprint (Xmx512m commit + full writer classpath — an upper bound).
Honest limits carried to the decision gate: OOME containment is
strong evidence not kill-certainty; the shared base is the real
25-of-42 pure `my.*` slice plus host bindings (db-boundary port and
`register!` admission not yet real); the js-bound 12% tier is C2's
scope. Blocker 5's GC blast-radius item is closed by this evidence;
its tier-design and dispatch-protocol items remain open for C2.

### C2 — tier split design — DONE, verdict SINGLE-TIER (2026-07-20)

The js-bound 12% inventory hardened into a computed rule (which agent
programs REQUIRE a JS runtime — detectable from their require/interop
surface, never a hand list); dispatch design for pure/db agents to the
JVM host and js-bound agents to a Bun child; one sync contract across
both.

**Status: DONE — genuinely-needs-js-eval measured EMPTY; recommend
single-tier C, Bun sci tier stays unbuilt.** Evidence:
[[research/c2-js-bound-audit-2026-07-20]]. Form-parsed re-derivation of
the C1 heuristic (125 public `my.*` fns, not 137): pure 61 (48.8%),
db-boundary family 47 (37.6%, incl. 16 await-idiom-only), real-js hits
17 (13.6%) — and ALL 17 are stdlib date/number/error shims (`.getTime`
runs verbatim on `java.util.Date`; `js/Date.`, `js/Math.round`,
`js/parseInt`, `.toISOString`, `.-message` are one-line reader
conditionals). `my.canvas` is 0-js (canvas is data). Persisted default-
cluster corpus: 5 agent namespaces, all bare ns declarations, 0 authored
defns; 11 eval rows with 0 organic js (6 are this arc's own memory
probes). Fixtures: 1037 samples across e1/typeahead/tb2 with 0 js; the
single js fixture form is the deliberate child-crash drill
`(js/process.exit 17)` (infra, re-point at U11). The tier rule (namespace
js-eval-bound iff own real-js forms or require-edge reachability to one,
STOPPING at wrapper-registry capability namespaces) is specified in the
report and becomes the eval-seam admission guard: real-js forms on the
host tier get a steering `:seon/error`, computed, never a hand list.
Residual before U11 closes the B decision: one same-shape query of the
acme cluster's corpus. Confirms U5 small (17 shims + 3 private
capability impls) and U9 tiny (0 awaits in persisted agent sources).

### U1 — host-skeleton productionization — DONE (2026-07-20)

`seon.host` (+ `seon.host.context`) is production source: a JVM agent
host serving the execution child's exact message semantics
(startup/ready, invoke/result/error, cancel, shutdown) over
length-prefixed transit-UDS through the one `seon.db.transport.uds`
codec. Per-agent sci contexts fork one shared base (portable `my.*`
pure slice from real sources — 25/42 blocks, ledgered failures — plus
compiled `seon.ai.tokens`/`seon.schema` host fns and a `seon.db`
binding table over ONE retained writer connection; the writer scopes
database access to physical connections, so per-call reconnects are
wrong). Eval runs on pooled threads under the invocation's absolute
deadline with `Thread/interrupt` -> `:interrupt-fn` ->
`sci.interrupt`; results are bounded ordinary wire values; every
failure is a `:seon/error` value. sci is pinned in deps.edn's `:host`
alias (`:local/root reference-code/sci`, HEAD `be4021d` containing JIT
`45bcf0f`; a pushed mirror is required for a publishable coordinate).

Gates:

- **Conformance**: `test/seon/host_conformance_writer_test.clj`
  replays the inventoried pod->child sequences against a fake writer —
  18 tests / 60 assertions green inside the full `bin/test-writer`
  gate (251 tests / 1958 assertions, 0 failures).
- **Kill drill (design §7) PASS, twice**: `tmp/sci-probe/jvm/drill.sh`
  on a private drill writer. 20 contexts admitted (working state +
  one writer fact each), runaway wave, `kill -9` mid-wave:
  20/20 EOFs -> 20 recorded child-exited error values (pod-side
  synthesis contract); restart -> 20/20 contexts rebuilt from the
  shared base + replayed def sources and verified; fleet context
  rebuild **132-133 ms** after host-ready; host cold start 8.2-11.5 s
  (JVM + clojure + base load dominates downtime); zero fact loss
  (20/20 facts, head t unchanged across the kill).

Recorded seams (deliberately unbuilt, marked in source): def
persistence/corpus tee + real `register!` admission (U2 with
`seon.eval`'s owners — `:seon.eval/ids` stays empty until then);
authored function invocation; `seon.execution` promotion to `.cljc`
(the host registers a JVM projection of the wire schemas and echoes
the startup's artifact identity — its trust root is the JVM
classpath); render-prompt!/render-agent-view! stay pod-served.
Favorable divergences: timeout/cancel interrupt in-process without
poisoning, so contexts survive and only the session ends on cancel.

### U2 — wrapper registry + capability op-id receipts — DONE (2026-07-20)

`seon.host.context` now provisions EVERY capability namespace through
one wrapper registry (`registry` + `register-wrappers!`) backing the
base's sci `:load-fn`: first require injects cached wrapper vars
(real sci vars, `:arglists`/`:doc` live), the shared load-fn closure
makes a namespace registered after forks exist require-able in every
live context, and re-registering a function alters the shared var's
root so already-required contexts use the new implementation on their
next call (plain JVM var alteration; the probed var-epoch property).
The U1 eager `:namespaces` binding path is DELETED — the
db/schema/tokens families ride the registry, and the registry-var an
agent context resolves is `identical?` to the registry's cached var.
Registries are process-local derived state: restart rebuilds them by
re-registration from the host's configuration
(`register-host-capabilities!` in `build-base!`), never persistence.

Receipts: `seon.db/transact!` accepts the pod's shapes plus an
optional `:seon.capability/op-id` (wrapper-generated when absent) and
translates it at the boundary to the database protocol's
`::protocol/request-id` — the writer's EXISTING durable idempotency
receipt (the `:seon.db.protocol/request-id` datom on the committed
transaction entity; `seon.db.writer` recovery replays the recorded
outcome). No second receipt entity was added: the prescribed
`:seon.capability/receipt` fact already exists as that protocol fact
(one mechanism; writer.clj's "the durable receipt, not the delivery
failure, is authoritative"). A caller-supplied op-id is pre-checked
against the receipt, so a retry after any crash returns the recorded
outcome with `:seon.capability/replayed? true`; the completed-at
basis is the receipt datom's own transaction (derived, not stored).

Gates (`test/seon/host_registry_writer_test.clj`, real memory-backend
`seon.db.writer`): cross-context post-fork provisioning, live wrapper
upgrade without re-require, registry-var identity, and the crash
drill — transaction delivered and committed, connection killed before
the acknowledgement, same-op-id retry replayed the receipt with the
fact count still 1 (4 tests / 24 assertions). Full `bin/test-writer`
255 tests / 1982 assertions green; the §7 kill drill re-ran PASS on
the registry-backed host (fleet restore + zero fact loss, registry
rebuilt by re-registration).

### U1.5 — pod dials the host: tier-as-data + one real turn — DONE (2026-07-20)

Tier assignment is DATA on the agent entity:
`:seon.execution.host/eval-socket-path` (registered by
`seon.execution.host`, `[:string {:min 1}]`). Presence of the host
coordinate routes that agent's `eval-batch!` invocations to the JVM
`seon.host` at that UDS socket; absence keeps today's Bun child for
every invocation — no `:type` taxonomy, no enum, default unchanged for
every existing agent. The dispatch reads the fact at the invocation's
pinned database value (injectable per configure! for tests); a failed
tier read errors loudly, never a silent child fallback.

One dispatch mechanism, two transports, strengthened IN PLACE in
`seon.execution.host`: the claim/settle/exit/retire/cancel state
machinery is lane-keyed (`::children` for Bun IPC children,
`::host-sessions` for host sessions) and shared; the host session is a
child-shaped control over `seon.db.transport.uds/connect-stream!` — a
new framed text-payload layer in the ONE transport codec namespace
(shared four-byte framing, transit payload text; `execution`'s message
codec unchanged on both ends). The startup value a child receives as
argv[2] is the session's FIRST frame; ready validation, invoke,
result-currency, cancel, shutdown, and exit synthesis reuse the child
lane's exact functions. Startup sends the launch descriptor's honest
artifact identity; the host echoes it (the documented U1 trust-root
divergence, carried in evidence as
`:seon.execution.host/eval-socket-path` + echoed digest). Host
sessions never idle-stop (park would drop context defs until the
U2/U4 corpus tee + replay; U7 owns the policy).

Contract parity checklist (the `seon.host` docstring inventory), each
proven by focused pod tests
(`test/seon/execution/host_test.cljs`, 21 tests / 107 assertions
green) and/or the live drive: startup-as-first-frame ✓; ready echo
validated by the same `ready-message-valid?` ✓; invoke/result with
db-value echo + result-currency ✓; error frames ✓; one active
invocation per agent (shared `!invocation-tails` queue) ✓; cancel
(session ends, context survives host-side — favorable divergence,
comment recorded) ✓; shutdown (host parks context) ✓; session death →
the exact contract child-exited error value with
`::execution/child-retired? true` and host evidence ✓.

Real-turn proof (branch `default-u15`, harness
`tmp/sci-probe/exec/{run-u15.sh,out/u15*-drive.log}` +
`tmp/sci-probe/exec-src/seon/execution/u15_driver.cljs`, normal
execution artifact for renders): real agent minted, tier fact + its
canonical `:seon.schema` row transacted, five REAL turns through
`seon.agent.turn/run-turn!` with a scripted llm-fn. Every turn
rendered its prompt in the Bun child and evaled on the JVM host.
Value-level wire evidence (captured per-form envelopes): turn 1
`#'user/u15-double` + `42`; turn 2 cross-turn reuse — `(u15-double
21)` resolved inside a `db/transact!` built in-context (the write
reached the writer; it was rejected only by the writer's new managed
identity policy for the harness's literal `:seon.agent.message/id`,
returned as an ordinary `:seon/error` VALUE — errors-as-values held
end to end). Kill drill first slice (U10): kill -9 the host mid-turn
→ the turn recorded `:error` with the contract child-exited error
value (message, `child-retired? true`, socket-path evidence, pinned
db); driver respawned the host (~10 s) → turn 4 `:done` with a fresh
def; turn 5 honestly showed `Unable to resolve symbol: u15-double`
(pre-kill defs are NOT replayed — the U2/U4 seam). A direct
`invoke-compiled!` eval-batch through the same dispatch returned
`n-ok 2` with per-form values, and a JVM-side two-invocation probe
proved cross-invocation def persistence plus a successful
writer-committed fact (`u15-probe-fact`, op-id receipt present from
U2's registry).

Honest limits inherited by U4/U6 (visible in the drive):

- `:seon.eval/ids` stays empty — NO eval rows, receipts, corpus tee,
  program-graph tee, or transcript eval rendering for host-tier turns;
  the turn closes `:done` with eval counts only, and eval failures are
  invisible to the transcript (the drive had to capture wire envelopes
  itself). U4 owns recording.
- No def replay on context loss (host restart, shutdown-park): the
  next turn starts from the shared base; agent-visible defs silently
  vanish. U2's corpus tee + `replay-defs!` wiring closes this.
- Renders (prompt/agent-view) and authored invocations still run in
  the per-agent Bun child — a host-tier agent keeps a child alive for
  rendering (render-in-child is the recorded design divergence until
  U4/U11); `setup-agent-ns!`/starting-ns is ignored by the host batch
  (evals land in the context's current ns, `user`).
- No instrumentation over sci vars (U6), no repair sub-loop/preflight
  resolution queries, no ALS print capture (B1 deferred items).
- Timeout/child-exited synthesis: a host-side timeout error carries no
  `child-retired?` claim (context survives — favorable divergence).
- Cross-lane observation during the drive (not U1.5 defects): the
  writer restarted mid-window by a concurrent lane produced honest
  session EOFs until reconnect, and a protocol v11/v12 skew between a
  freshly compiled driver and the older running writer failed
  admission loudly; both resolved by version-consistent processes.

### U4 — host eval recording through the one corpus mechanism — DONE (2026-07-20)

Host-tier turns are first-class recorded citizens. The marked U1/U2
seams are replaced, not rebuilt elsewhere:

- **Recording data parity.** `seon.host.record` (pure builders) mirrors
  the DATA the child's detect-and-tee writes — `:seon.eval` receipt
  rows (`:running` start, CAS-fenced terminal with outcome,
  duration, bounded result-edn/error, ending ns, agent connection,
  turn `:seon.agent.turn/evals` component), `:seon.fn` rows for a
  strict single-defn source (sym/ns-upsert/source/arglists/doc/private?/
  spec-or-schema-error from the returned sci var's metadata + the read
  form), `:seon.fn/read-attrs` exact-set ops, `:seon.ns` +
  require-edge rows for explicit ns declarations, and `:seon.schema`
  rows from the registry diff around each form. Transient scratch nses
  (`user`, `cljs.user`, …) stay untee'd (C14). The child's `seon.eval`
  owners remain authoritative for the Bun tier.
- **The receipt is the durable execution boundary.** `seon.host.context`
  allocates managed `:seon.eval/id`s over the wire protocol's
  `::generated-candidates` field (new public
  `seon.db.id/candidate-manifest` seam; same grammar, policy read from
  the database, bounded conflict retry) and commits the `:running`
  receipt BEFORE the form runs — no receipt, no run. The batch returns
  real ordered `:seon.eval/ids`; receiptless probes (no turn id) stay
  engine-only.
- **Home-ns evals.** The batch establishes and evals in the request's
  `:seon.eval/starting-ns` (synthetic ns form with the standard
  capability aliases), so defs land in `my.agent.<id>`, not scratch.
- **Def replay closes the U1.5 turn-5 gap.** A fresh context fork at
  startup replays the agent's home-ns `:seon.fn/source` rows in tee
  order (`agent-def-sources` + `restore-context-defs!`).
- **`register!` admission is real**: the host wrapper calls the one
  `seon.schema/register!` bridge (errors as values) and the tee writes
  the canonical `:seon.schema` row.
- **Provenance threads per invocation** (closes handbook gotcha 13's
  host half): `seon.host.context/*agent-id*` binds around every
  invocation; reads carry `:seon.db/user`/`:seon.db/process`, writes
  carry the same references as transaction metadata.

Gates and evidence:

- `bin/test-writer` 261 tests / 2016 assertions green, including the new
  real-memory-writer parity test
  (`host-evals-record-the-same-corpus-data-as-the-child-tee`): 3 forms →
  3 receipts under the turn with agent refs; eval-row content parity;
  `:seon.fn` row (source/arglists/doc/ns); `:seon.schema` row from
  `register!`; then `host/stop!` + fresh `host/start!` → the pre-restart
  def replays from the corpus and evaluates. `bin/test-cljs` 1336 tests /
  6170 assertions green.
- §7 kill drill PASS on the recording host (20/20 in-flight EOFs →
  child-exited values, 20/20 fleet replay + verification, zero fact
  loss). The drill database now seeds the corpus schema/policy a real
  cluster carries from genesis; recording loudly refuses to run a form
  whose receipt cannot commit, exactly as designed. Honest cost: fleet
  context rebuild through recorded batches is ~14 s for 20 agents × 3
  forms on the file backend (2 writer transactions per recorded form —
  the child pays the same shape); restore replay itself does not record.
- U1.5 driver rerun (`tmp/sci-probe/exec/out/u4-proof-drive.log`, fresh
  `default-u15` fork): turns 1/2/4/5 `:done` WITH eval rows (turn 1:
  2 rows), turn 3 the kill slice unchanged; post-kill turn 5 evaluated
  `u15-double` — no "Unable to resolve symbol" (def replay live); the
  branch database holds all six host eval rows in
  `my.agent.forty-dots-count` plus `:seon.fn` rows for both defs; turn
  5's captured prompt (turn debug blob) contains the turn-1 def source
  and its `42` result — the transcript renders host evals.
- R2 resolved as fixture noise, not a live defect:
  [[../../seon/issues/archive/dev-eval-program-row-rejection-was-fixture-noise]].
  Live falsifier on default: a real agent eval turn records
  (`["(+ 20 22)" true "42"]`).

Honest limits carried forward: no run-fence CAS assertion host-side (the
§8b fence stays with the child path; U6/U10 own parity), no ALS print
capture (`:seon.eval/output` absent), result-edn is a capped `pr-str`
(no render-ai skeleton or `result/<id>` binding), no repair
sub-loop/preflight, renders stay pod-served, and `record-eval-terminal!`
failures surface as `::record-error` on the envelope (batch continues).

### U3 — accretion walking skeleton — DONE (2026-07-20)

R48 containment ruling (2026-07-24) supersedes the native-execution claims
below. The original walking skeleton remains historical differential-test and
registry-link evidence, but tests-pass no longer admits native code.
`graduate!` returns a flat refusal naming R48 and the P4 reopen gate, performs
no host eval or tier transaction, and matching legacy `:graduated` rows derive
`:nursery` and rebuild through SCI (`3bb7c2d39`). Native compilation may reopen
only after P4/R33 proves the exact transitive call graph pure,
capability-free, and `:interrupt-fn`-equivalent. The original measurements
below are not current execution behavior.

One real agent-authored corpus function now crosses the complete
accretion pipeline. The host harness records `sum-squares` through U4
as a strict single `defn` with a Malli schema and inline `:test`; its
`:seon.fn` row carries the verbatim source, exact UTF-8 SHA-256
`:seon.fn/source-fingerprint`, and the historical
`:seon.fn/execution-tier :nursery` literal.
Both the Bun tee and JVM host tee write those facts, so a source edit is
one identity upsert that changes the fingerprint and returns the row to
interpreted SCI without a stale optional fact.

Dependency ledger:

- Clojure 1.12.0 supplies plain `clojure.core/eval`; Malli 0.20.0
  validates the recorded `:seon.fn/spec` before compilation.
- sci is the pinned `reference-code/sci` checkout at `be4021d`; the
  relied-on mechanisms are `sci/fork`, `sci/add-namespace!`,
  `sci/alter-var-root`, and `eval-def`'s reuse of an existing var
  (`src/sci/core.cljc`, `src/sci/impl/evaluator.cljc`,
  `src/sci/impl/vars.cljc`, `src/sci/lang.cljc`). U2's live-swap test
  remains the first-party call-site template.
- `seon.host.record` remains the source-row owner and
  `seon.host.context/replay-defs!` remains the SCI reconstruction path.
  `seon.content-hash` is the extracted portable owner of the exact hash
  already used by `my.blob` and `seon.execution/source-digest`; U3 adds
  no hasher.

`seon.host.graduate/trust-gate?` is a pure predicate over facts. It is
true iff the recorded schema parses with no schema-error, an inline test
exists, the supplied fingerprint equals both the exact recorded-source
hash and the row fingerprint, both SCI and JVM test thunks finish
without throwing, and their `pr-str` results are equal. Schema/test
preflight runs before the source reaches JVM `eval`; there is no symbol
allowlist. A passing candidate commits the same fingerprint plus
`:graduated`, then `register-wrappers!` changes the one shared SCI var's
root to the dereferenced JVM function.

The registry gained one link operation, not another binding path:
`install-registered-wrappers!` merges the registry's exact cached vars
into a context through sci's public `add-namespace!`. Restore order is
replay first, link second. Consequently a later SCI `defn` edit reuses
that linked var, bumps sci's var epoch, and immediately makes every linked
caller use interpreted SCI again; recording then makes the new fingerprint
and interpreted tier durable. Host startup queries those facts and derives all
interpreted/compiled roots before accepting sessions. Bytecode and vars remain
process-local projections.

Proof (`test/seon/host_graduate_writer_test.clj`):

- focused U3: 2 tests / 29 assertions; U2/U4 registry focus: 6 / 43;
- 10,000 calls through one already-required caller context, three
  warmups and seven samples per tier: interpreted SCI ns
  `[21647167 21473500 19854375 23703000 19521291 20139250 22290250]`,
  compiled ns
  `[13220459 15487875 12909458 12145500 12927375 15586958 12496375]`;
  medians 21.474 ms vs 12.927 ms, **1.661x faster**;
- edit proof: fingerprint
  `e56c73843834c9cb7fabac76faa82e9869571cf0b00347ec11a6fb0c9d4d95e5`
  became
  `525a14c96b51ec833bfe494c7d39885849b4f944a706954a22298682c4653a57`;
  the row and `effective-tier` carried the historical `:nursery` value and the
  linked caller returned the edited SCI result before re-accretion;
- fresh `host/stop!` + `host/start!` rebuilt exactly one accreted root
  from facts and returned the edited result after replay/link;
- full writer 263 tests / 2045 assertions and full CLJS 1349 / 6257,
  zero failures/errors; and
- the existing 20-agent kill drill remained PASS: 20/20 EOF notices,
  20/20 replayed and verified, zero fact loss, 8.436 s kill-to-host-ready
  and 26.930 s kill-to-full-fleet-ready. The focused restart assertion,
  not the pre-accretion legacy drill corpus, proves accreted-state
  reconstruction.

Honest boundary: this skeleton accretes one self-contained pure
function with one inline test. Cross-function compiled dependency
loading, cooling-window policy, richer test refs, and canary promotion
remain later accretion work; JVM eval intentionally runs without SCI once the
gate passes.

### U5 — dependency-ordered toolkit port — DONE (2026-07-20)

The corrected three-phase shape is implemented in dependency order.
`seon.host.record` remains the one tools.reader owner for namespace forms,
require edges, and host-feature form selection. `seon.host.context` discovers
all `src/my` sources, topologically orders namespaces from those parsed
requires, preserves source order within a namespace, and records one
loaded/failed/excluded row for every discovered top-level definition. Cycles
and parse/eval failures are values in the same ledger; no hand-ordered toolkit
list exists.

The pre-change direct-HEAD ledger was 8 files / 43 portable candidates:
25 loaded and 18 failed. Phase (a)'s deliberately expanded, honest discovery
ledger was 11 files / 167 portable candidates: 138 loaded, 29 failed, and 106
excluded. After registry provisioning and shim conversion the host-selected
ledger is 11 files / 273 total definitions: **162 portable, 162 loaded, 0
failed, 111 excluded**. The count changes are intentional: discovery now
includes every definition and reader-conditionals are classified from their
`:clj` branch. An otherwise-portable caller of an excluded private helper is
excluded with that exact dependency reason, not misreported as a portable
failure.

Registry provisioning reuses U2's live SCI vars for ordinary immutable values
as well as functions. It supplies `seon.db.id` candidate generation and policy
query data, database protocol operation/result vocabulary, schema definitions,
token clipping, repair ranking, source-form reading, provider classification,
content hashing, instant formatting, file reads/skill enumeration, and canvas
field encoding. The portable owners are `.cljc`; the loader never eagerly
requires the Node blob materialization family. Its remaining callers are the
explicit JS-bound exclusions named in the ledger.

The C2 “17 shims” count is 17 public functions and **18 offending forms**:

| Portable conversion | Public functions/sites | Forms |
|---|---|---:|
| `.getTime` → `inst-ms` | `ready-leaves-from-rows`, `active-steps-from-rows`, `forest-from-rows`, `open-steps-from-rows`, `next`, `list-open`, `kb.shared/instructions` | 7 |
| `js/Date.` → reader-selected JVM/CLJS constructor | `step!`, `plan!`, `commit-generated-terminal!`, `publish-generated-program!`, `done!`, `reconcile!`, `kb/remember` | 7 |
| `js/parseInt` → reader-selected radix parse | `kb/remember` | 1 |
| `.toISOString` → `seon.time/iso-string` | `plan.internal/stamp` | 1 |
| `(.-message e)` → `ex-message` | `plan.internal/maybe-consult!` | 1 |
| `js/Math.round` → non-negative portable rounding | `ui/progress` | 1 |

Evidence on the frozen U5 source:

- focused dependency/ledger proof: 2 tests / 12 assertions;
- full writer: 265 tests / 2057 assertions, zero failures/errors;
- kill drill PASS: both cold and rebuilt boot ledgers reported 162/162
  portable loaded, 0 failed, 111 excluded; 20/20 EOF notices, 20/20 contexts
  replayed and verified, 20/20 facts retained;
- full CLJS: 1366 tests / 6497 assertions across 131 namespaces, zero
  failures/errors (491 files compiled, zero warnings).

The durable C1 and C2 reports are the available pre-change evidence. No C5
report exists in this PRD, so U5 does not invent a C5 count.

### Decision gate

B vs B+C ruled by the owner on: B2's production numbers, C1's scale
proofs, and the measured share of live agent programs that are
js-bound. Architecture docs and the one-mechanism table update ride the
decision, not the exploration.
