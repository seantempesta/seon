---
type: prd
status: active
tags: [prd, agent, architecture, database]
---

# Sci execution runtime roadmap

## Program goal (owner, 2026-07-20 night)

**Complete the transition: Seon runs fully on this architecture.** The
graduation gate is a working system — every agent on a context/host, the
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
| U4 | eval-record/receipt/corpus integration over the marked seams (subsumes register R2 — the program-row rejection diagnosis) | ready |
| U5 | toolkit port: .cljc the db-boundary 46%; capability proxies for the js-bound tail | parallel-safe |
| U3 | graduation walking skeleton (one corpus fn: fingerprint → both-tier tests → JVM eval → epoch re-link) | after U2 |
| U6 | instrumentation over sci vars (B1 deferred item 5) | after U4 |
| U7 | park/idle policy + warm spares (owner-ruled shape) | after U1.5 |
| U8 | steering/context re-alignment: all agent-facing guidance teaches the sync idiom | before cutover |
| U9 | await-corpus migration pass (measured, small) | before cutover |
| U10 | integration drills: host kill + pod restart with live agents, derived notices proven | gate |
| U11 | children retirement, deletion commit, architecture docs + one-mechanism table | cutover |
| U12 | graduation demo: N=100 live fleet, real work, kill + restart survival | **the gate** |

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

### Decision gate

B vs B+C ruled by the owner on: B2's production numbers, C1's scale
proofs, and the measured share of live agent programs that are
js-bound. Architecture docs and the one-mechanism table update ride the
decision, not the exploration.
