---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# Loop → portable CLJC + sci design (P4 loop-migration slice) — 2026-07-23

Owner-directed fresh-eyes design: move the turn driver itself — not just
evals — into the portable CLJC + sci system, so a RUN is CAS-claimable
database state that ANY process can claim, advance one step, and release.
Grounded in a direct read of the vendored Datahike fork
(`reference-code/datahike`, seon fork with `datahike.committed-report`) and
the vendored sci fork (`reference-code/sci`, branch `seon`,
`8fac6e8`), plus the current loop source. Builds directly on — and
reconciles with — the two accepted precursors:
`research/crashproof-feasibility-2026-07-22.md` §5 (four durable closures
A–D) and `research/pod-state-virtualization-audit-2026-07-22.md` (VA-1..7).
Binding constraints honored throughout: one mechanism / strengthen in
place, errors as values, no stored derived state, effect classes + op-id
as the replay vocabulary (rulings 9, 19, seam design §4/§8a), async
ceremony confined to leaves, metadata minimalism (ruling 19).

## 0. Current loop anatomy (what actually exists, file:line)

The turn driver is pod-resident Bun code:

- **Loop fold.** `seon.agent.loop/run-loop!` folds a pure transition table
  (`transitions`, loop.cljs:49-61) over events derived per recurrence from
  ONE acquired database value (`acquire-loop-state`, loop.cljs:205-243;
  `next-event`, loop.cljs:245-287). The fold state (FSM state, no-forms
  streak, prior eval observation) lives in the async recursion
  (loop.cljs:409). Per-agent serialization is a process-local promise
  registry (`!run-loop-promises`, loop.cljs:94), and the llm-fn closure
  registry (`!loop-input`, loop.cljs:88) is a hard prerequisite for
  driving (`drive-run!` refuses without it, loop.cljs:1017-1022).
- **Run entity.** `seon.agent.run` owns the durable run:
  identity/fencing token (run.cljs:25-29), bounds, heartbeat
  (`last-beat-at`, run.cljs:37), pause banking, `:open`/`:closed` +
  eleven closed-reasons (run.cljs:51-54). Atomic wake: `open-run!` CASes
  the ABSENT agent run pointer (run.cljs:497-501). Work fence:
  `run-fence` asserts the pointer still names this run (run.cljs:389-395)
  and leads every work tx (`beat!` run.cljs:777-789, `renew!`, `pause!`,
  `resume!`, `close-tx-data` run.cljs:514-534). Watchdogs are stateless
  DB scans on the one ticker: deadline (run.cljs:899-943) and heartbeat
  staleness → close `:crashed` (`stale-runs-query` run.cljs:966-978,
  `close-stale-runs!` run.cljs:1008-1074).
- **Turn bracket.** `seon.agent.turn/open-turn!` commits a `:running`
  turn row + the run work-fence CAS in one allocation tx
  (turn.cljs:535-559), runs the body, `close-turn!` merges telemetry and
  pins the final pull to the close tx's db-after (turn.cljs:569-637,
  1199-1205). The body is render → LLM (sole retry authority,
  `call-llm!` turn.cljs:968-1026, attempts buffered in a local
  `!attempts` atom, turn.cljs:981) → parse (`seon.repl.parse`, already
  .cljc) → eval dispatch (`eval-parsed!` turn.cljs:440-492 with the loud
  zero-attempt guard) → close. Attempt rows are a registered component
  schema (turn.cljs:161-219) but only persist at turn close.
- **Eval dispatch (ruling 18, landed).** `execution.host/invoke-now!`
  routes per parsed batch: `seon.packages.js.*` → Bun child, everything
  else → the JVM sci host coordinate (host.cljs:1016-1048). The host
  serves ONLY `eval-batch!`; prompt/view rendering is explicitly refused
  (invoke.clj:138-144) and runs in the Bun child
  (`seon.execution.runtime/render-prompt!`, runtime.cljs:280-359).
- **JVM sci host.** Receipt-before-run is already the durable execution
  boundary: a `:running` receipt commits before the form runs; terminal
  commits behind the receipt CAS with tee'd program rows
  (host/eval.clj:255-467, esp. 319-333; host/context.clj:30-38). The
  host re-fences the run per invocation (`claim-run-fence!`
  host/eval.clj:198-212: old→old pointer CAS). Deadline enforcement is a
  scheduled watchdog + `Thread.interrupt` + sci's uncatchable interrupt
  marker (invoke.clj:30-35, 100-103; sci.interrupt below).
- **Recovery.** `seon.runtime.recovery/recover!` is deliberately
  conservative: one fenced tx closes every nonterminated pointer-owned
  run `:crashed`, marks running turns `:interrupted`, terminalizes
  running eval receipts via the existing receipt CAS, and writes one
  anchor (recovery.cljs:351-416, 429-532). Cold boot runs it
  cluster-wide — which is exactly why two autonomous pods are unsafe
  today (pod-state audit, Multi-driver verdict).
- **Admission/quiesce.** Process-local publication gate
  (admission.cljs:37-102); the loop consults it every recurrence
  (loop.cljs:410-413).
- **Supervision (WP-S2).** Four managed members
  watcher/writer/host/pod (process.clj:26-31); exact-generation
  TERM→KILL both kill modes drilled. The pod's host-lane `kill!` remains
  a UDS close-stream (execution/host.cljs:603-607) — invocation
  cancellation on the host is the sci interrupt, not a process kill.

The precise gap (pod-state audit, confirmed): durable run truth exists,
but exclusive next-turn ownership, input consumption, LLM-attempt
progress, and the reply/eval phase cursor live in one pod's closures,
promises, and atoms. `open-turn!`'s old→old pointer CAS does NOT prevent
two drivers from allocating two turns on the same open run.

## 1. Fresh-eyes library grounding (what the vendored sources actually offer)

### Datahike (seon fork)

- **`:db.fn/cas` exact semantics** —
  `datahike.db.transaction/compare-and-swap`
  (reference-code/datahike/src/datahike/db/transaction.cljc:963-985):
  cardinality-one compare against the current datom value; `nil`
  expected means "attribute absent" (the `open-run!` absent-pointer CAS
  rides this, :971-973); cardinality-many compares set membership; a
  mismatch raises `:transact/cas` with old/expected/new in ex-data,
  aborting the WHOLE transaction. Multiple CAS ops compose in one tx —
  everything this design needs (epoch fence + beat assert + phase
  advance in one tx) is native.
- **Writer architecture** — `datahike.writer/LocalWriter`
  (writer.cljc:42-76): ONE processing thread drains a bounded
  transaction queue, ONE commit thread; total serialization of writes is
  a structural property. Sharp edge (from the accepted crashproof
  audit, writer.cljc:205-240): the commit thread batches queued reports
  and persists once — multiple tx ids can share one commit id, so
  branch-from-arbitrary-`t` is NOT available; claims/phases must never
  assume per-tx commits.
- **`listen!`** — `datahike.core/listen!` (core.cljc:199-217) is
  PROCESS-LOCAL: callbacks in an atom on the connection's meta. It
  cannot serve cross-process wakeup. The FORK's cross-process primitive
  is `datahike.committed-report` (committed_report.cljc:1-60 +
  open!/offer-committed!/poll-batch!/close-scope!): bounded per-scope
  queues of committed reports with readiness signaling. Seon's writer
  serves SELECTIVE committed-report interests over the transport on top
  of it (`seon.db.writer` "Selective committed-report interests",
  writer.clj:2364-2453: per-scope attribute-indexed interest tables,
  connection-agnostic). The pod session consumes this as the typed
  `listen-request` with resynchronization on session restore
  (db/session.cljs:561-621, 363-388). **The JVM db leaf
  (`seon.db.host/leaf`, db/host.clj:561) exposes call!/resolve-db! but
  NO listen — the seam design deferred the listener surface "until a
  consumer exists" (p1-capability-seam-design §Open questions). P4 is
  that consumer.**
- **Idempotent replay** — the writer stamps the public
  `:seon.capability/op-id` (crossing as `::protocol/request-id`) onto
  the committed transaction and `recover-committed` returns the recorded
  outcome for a repeat (writer.clj:1433-1444; db.cljc:382-415 mints or
  threads the op-id and marks `:seon.capability/replayed? true`). This
  is ruling 9, landed on both tiers. Nothing new is needed for
  db-effect replay.
- **Temporal reads** — as-of/since/history are pure value wrappers
  (db.cljc:351-369) over the fork's temporal indexes; recovery already
  derives its notices from history joins (recovery.cljs:559-677). Claim
  archaeology (who held what when) is free — no audit log attribute is
  ever needed.

### sci (seon fork, branch `seon`)

- **`sci/fork` is near-free**: `(update ctx :env (fn [env] (atom @env)))`
  (reference-code/sci/src/sci/core.cljc:318-323) — measured ~0.05 µs
  (crashproof §2). BUT pre-existing vars are shared mutable roots:
  redefining an inherited var mutates the base. The host already applies
  the required detach-then-recreate discipline
  (`materialize-pinned-function!` host/context.clj:1060-1065 removes and
  recreates the ns; `restore-context-defs!` host/context.clj:939-952).
- **Replay cost is linear in defs**: measured p50 0.17 ms for 1 def,
  2.24 ms for 100, 7.28 ms for 500 (crashproof §2 probe) —
  `eval-string*` re-parses/analyzes/evaluates each form
  (interpreter.cljc:89-109); there is no durable compiled-context
  serialization. `parse-next`/`eval-form` (core.cljc:373-404) exist, but
  pre-parsing saves only the edamame pass, not analysis — not worth a
  second eval path (one mechanism).
- **`load-fn` is the namespace-lazy door** (load.cljc:161-234), shared
  by all forks — registering a namespace makes it lazily requirable in
  every live context, and re-registering upgrades the shared vars in
  place (host/context.clj:2-15). Portable cores load through the same
  door (`load-portable-slice!` host/context.clj:764).
- **Interrupt** — the fork's `sci.interrupt` provides `:interrupt-fn`
  polling at interpreted fn entry/loop recurrence plus opt-in
  interrupt-aware `clojure.core` overrides (range/reduce/into/regex …)
  and an uncatchable interrupt marker
  (reference-code/sci/src/sci/interrupt.cljc:25-42, 205-315,
  clojure-core map at :316+). The host base already installs
  `:interrupt-fn` (host/context.clj:879) and the invocation watchdog
  does deadline → `Thread.interrupt` with phase-locked cleanup
  (invoke.clj:30-35, 100-103, 122-139 in host/eval.clj
  `finish-evaluation!`). Arbitrary blocking host calls remain a
  respawn-boundary matter (crashproof §3) — physics stays with WP-S2.
- **Context store** — `sci.ctx-store/with-ctx` scopes the ambient ctx
  per call (used at host/eval.clj:163, invoke.clj:61); no global
  singleton is assumed. Retained per-agent contexts are already
  explicitly a CACHE of database facts: "park drops it, restore forks
  the base and replays the agent's home-ns corpus def sources"
  (host/context.clj:25-28, invoke.clj:249-250).

**Verdict on current sci usage: already optimal in mechanism.** Retained
contexts-as-cache + fork-from-base + corpus replay + load-fn matches what
the source supports; there is no cheaper native primitive being missed.
The only P4-relevant change is making the cache KEY explicit (below).

## 2. The run-claim state machine as database facts

### 2a. Claim = attributes on the existing run entity (not a new entity)

The crashproof synthesis proposed a separate `:seon.turn-claim` entity
(§5A). Fresh-eyes ruling here, per one-mechanism: the run entity ALREADY
owns exclusive-work vocabulary — the fencing token, the heartbeat, the
staleness threshold, and the watchdog that enforces it. A second claim
entity would duplicate that ownership (two heartbeats, two expiry
scans). Instead the claim strengthens `seon.agent.run` in place:

```clojure
;; NEW attributes, registered in seon.agent.run (the one owner):
:seon.agent.run/claimant     ; string — process-instance identity
:seon.agent.run/claim-epoch  ; int — monotonic per run, fencing token v2
;; REUSED as the lease: :seon.agent.run/last-beat-at (the heartbeat,
;; run.cljs:37) + the config watchdog threshold
;; :seon.config.watchdog/stale-ms (run.cljs:1002-1003). No lease-until
;; attribute: expiry is DERIVED (beat + stale-ms < now), per
;; derive-don't-store.

```

Claim transitions, all plain Datahike CAS compositions
(transaction.cljc:963-985):

- **Acquire** (first claim on an open run): one tx
  `[cas run :seon.agent.run/claimant nil <me>]` +
  `[cas run :seon.agent.run/claim-epoch nil 1]` + beat. Two racing
  claimants: writer serialization (LocalWriter's single processing
  thread) guarantees exactly one wins; the loser receives the direct
  `:transact/cas` error value it already knows how to read
  (run.cljs:382-387 comment block).
- **Renew/beat**: the existing `beat!` (run.cljs:777-789), with the
  fence extended (2b). Heartbeat cadence stays the loop's per-turn beat;
  the watchdog threshold stays the one config fact.
- **Release** (clean): retract claimant in the same tx as the run close
  or the pause (`close-tx-data` and `pause!` gain the retract; epoch is
  never retracted — it is the run's monotonic history of ownership).
- **Steal** (expired lease): the claimant observed stale at db value t
  issues one tx: `[cas run :seon.agent.run/last-beat-at
  <observed-stale-beat> <observed-stale-beat>]` (assert the beat did NOT
  advance since the staleness observation — closes the read→steal race
  against a merely-slow holder) + `[cas run :seon.agent.run/claim-epoch
  e (inc e)]` + `[:db/add run :seon.agent.run/claimant <me>]` + beat.
  The displaced holder's next work tx fails its epoch fence (2b) and its
  loop reads that as lost authority — exactly the semantics `beat!`
  documents today (run.cljs:781-784).

Pause keeps its existing meaning: a paused run is unclaimable (the
watchdog and scan queries already exclude `paused-at`,
run.cljs:920-924, 974); `pause!` releases the claim, `resume!` leaves
the run claimable by anyone.

### 2b. The fence strengthens in place

`run-fence` (run.cljs:389-395) currently asserts only the agent's run
pointer. It becomes a two-op fence returning
`[pointer-cas epoch-cas]` where the epoch CAS is old→old at the
CLAIMED epoch the driver holds. Every existing fence consumer inherits
the fix by construction: `beat!`, `renew!`, `pause!`, `resume!`,
`close-tx-data`, `open-turn!`'s work fence (turn.cljs:555-558), AND the
host-side `claim-run-fence!` (host/eval.clj:198-212 builds the same tx
shape — it takes the epoch as one more field on the existing
`run-fence` wire map). This closes the duplicate-turn hole directly:
two drivers can both hold an open-run view, but only the epoch holder's
`open-turn!` tx commits; the audit's VA-1 exit ("two drivers race one
run; exactly one turn identity commits") falls out of the fence, with
no new fence mechanism.

Input consumption (crashproof §5B, VA-2's second half) also lands here
without a new entity: the tx that opens or renews a run for a waking
message already carries `:seon.agent.run/cause` (run.cljs:34, 416).
Strengthen it to the explicit consumption edge: a NEW cardinality-many
`:seon.agent.run/consumed-input` ref asserted in the SAME claim/renew tx
that folds a message into the run. `pending-inbound-query`'s
coverage-by-close inference (loop.cljs:615-634) is then replaced by a
not-join on the explicit edge — deleting the "crash close silently
covers an unconsumed message" hazard the audit flagged
(pod-state audit row "Queued wake/renew…").

### 2c. Mapping the existing states — nothing parallel

| Existing owner | P4 disposition |
|---|---|
| `:open`/`:closed` + 11 closed-reasons (run.cljs:51-54) | Unchanged. `:superseded`, `:terminated`, `:waited`, `:completed`, bounds — all keep their close paths. |
| `:crashed` + heartbeat watchdog (run.cljs:1008-1074) | Split: a stale CLAIM is now first a steal-and-resume (2a); `close-stale-runs!` closes `:crashed` only when takeover is impossible (no resumable phase, ambiguous external effect, or repeated steal churn). Same scan, same ticker, one added branch. |
| Turn `:running/:done/:error/:interrupted` (turn.cljs:51-54) | Unchanged; `:interrupted` remains recovery-only. Phase cursor (3) is a separate attribute — status stays the coarse outcome. |
| `recover!` unconditional cluster repair (recovery.cljs:429-532) | Becomes lease-aware (VA-6): live claim (fresh beat, other claimant) → untouched; expired claim → steal + resume from the phase cursor; irrecoverably ambiguous → today's behavior (interrupt receipts, close `:crashed`, anchor). Strengthened in place — `compile-recovery`'s fences/retractions/anchor all survive as the fallback arm. |
| Admission (admission.cljs) | Unchanged and stays process-local: it gates whether THIS process may claim, never who owns a run. |
| `!run-loop-promises`, `!loop-input`, invocation tails | Demoted to per-process optimizations (audit verdict "MISPLACED-DURABLE as the correctness serializer"); the claim is the correctness serializer. `!loop-input` stops being a drive prerequisite: a scheduler resolves the llm-fn AFTER winning a claim whose next phase needs it. |

## 3. Step granularity: the turn phase cursor

Whole-turn claims would force every claimant tier to own every leaf
(render + LLM + eval). Sub-STEP claims (a claim entity per phase) would
be a second claim mechanism. The design takes the middle that needs no
new mechanism: **the claim stays RUN-scoped; the TURN carries a durable
phase cursor; a process claims a run only when it can execute the run's
next phase, and releases (clean, epoch intact) when the next phase needs
a leaf it lacks.** Phase eligibility is scheduler policy data, not a
second lock.

The cursor is crashproof §5D / VA-4, mapped onto facts that mostly
already exist:

```clojure
:seon.agent.turn/phase  ; NEW enum, CAS-advanced (cardinality-one):
;; :rendered      — prompt-blob + rendered-tx committed (exists today,
;;                  turn.cljs:535-559; the phase makes it addressable)
;; :attempt-open  — a durable LLM attempt row is :open (4)
;; :reply-ready   — reply-blob linked (exists: eager link,
;;                  turn.cljs:699-710) + parse input pinned
;; :evaling       — eval receipts running on the selected tier (exists:
;;                  receipt-before-run, host/eval.clj:319-333)
;; :evaled        — batch terminal, all receipts settled
;; :published     — plan publication done (turn.cljs:744-757)
;; then close-turn! (:done/:error) as today

```

Each advance is one tx: `[cas turn :seon.agent.turn/phase old new]` +
the run epoch fence + the phase's own facts. On takeover the new
claimant reads the cursor and resumes at the exact boundary; it never
infers progress from a recreated sci context (crashproof §5D rule).
Effect-class replay at each boundary (ruling 19b + wiki): a receipt
with a committed terminal is done; a `:running` receipt at takeover is
terminalized `:interrupted` via the existing
`eval.receipt/terminal-tx-data` CAS (recovery.cljs:389-394) and NEVER
re-run (absent effect metadata ⇒ `:external`, conservative); `:pure`/
`:read` capability entries are re-derivable; `:idempotent` entries
replay through their op-id receipts (§1 Datahike).

The no-forms streak and prior-observation fold state stop being loop
locals: `next-observation-state` (loop.cljs:123-141) already computes
from persisted turn/eval rows — the portable core derives the streak
from the run's trailing turns at the acquired db value (VA-2), so a
takeover inherits the honest streak instead of resetting it.

## 4. Durable LLM attempt receipts

Crashproof §5C / VA-3, strengthening the EXISTING
`:seon.ai.attempt/*` component family (turn.cljs:161-219) in place:

- The attempt row commits `:open` (new `:seon.ai.attempt/state` or the
  existing `outcome` enum gains `:open`) BEFORE network dispatch, with
  ordinal, frozen config digest, and absolute per-attempt deadline
  (already computed: `effective-llm-attempt-timeout-ms`,
  turn.cljs:910-919). Terminal is a CAS transition carrying the
  existing outcome/usage/evidence fields and linking the reply blob in
  the same tx.
- The `!attempts` buffering atom (turn.cljs:981) and close-time
  persistence (turn.cljs:586-598 select-keys) are deleted in the same
  change — the rows are already durable when close-turn! runs; close
  stops carrying them.
- Provider idempotency for DeepSeek/OpenAI-compat is NOT GROUNDED
  (crashproof §5C): an `:open` attempt at takeover stays honestly
  `:unknown` — the effect class of an LLM call is `:external`. Policy:
  a takeover opens a NEW attempt (next ordinal) rather than replaying;
  the retry budget already counts attempts, so the frozen resolution's
  `agent-max-retries` naturally bounds crash-loops. Double-billing on a
  kill mid-request is possible and is recorded as two attempt rows —
  honest, visible, bounded.

`call-llm!` remains the sole retry authority (turn.cljs:968-1026);
nothing about backoff/fallback moves. Only WHERE the evidence lives
changes.

## 5. Portable core vs leaves — the honest split

Per the landed seam pattern (p1-capability-seam-design §"The design in
one paragraph"): pure `.cljc` core, one leaf per tier, entry fns the
only reader-conditional site, sync CLJ path / Promise pod leaf.

### 5a. Already portable (no work)

- `seon.db` core with op-id + effect metadata (db.cljc — landed);
  message/lifecycle cores (agent/message.cljc, lifecycle.cljc);
  `seon.repl.parse` + `repair` (.cljc); `my.plan` (.cljc);
  `my.blob` core (.cljc); `seon.ai.tokens` (.cljc);
  `seon.eval.receipt` (.cljc).

### 5b. The loop core port (the slice's real code motion)

`run.cljs`, the pure halves of `loop.cljs`, `turn.cljs`'s tx builders,
and `recovery.cljs`'s compiler are ALREADY data-oriented: pure tx
builders (`new-run-row`, `close-tx-data`, `compile-recovery`), pure
predicates (`turn-limit-reached?`, `deadline-passed?`,
`stale-run-ids`), a pure transition table, and db calls that all go
through the portable `seon.db`. Platform residue is enumerable:
`js/Date.` (→ leaf clock, same pattern as the seam's "operation
identity minted at entry, clock is a leaf service", seam design ruling
4), `^:async`/`await` ceremony (→ entry-fn conditionals; the CLJ path
is sync by owner direction), `js/Promise`/`setTimeout`/`setInterval`
(loop drivers + ticker + zero-delay schedulers → per-tier leaves), and
`seval/race-timeout` in `await-bounded` (loop.cljs:289-311 — a pod-leaf
concern; the JVM driver's step bound is the invocation watchdog +
thread interrupt it already has, invoke.clj:100-103).

Port shape (strengthen-in-place, no `-v2`):

- `seon.agent.run` → `.cljc` (tx builders, scans, predicates, claim
  transitions; clock/uuid via the db leaf services already used by
  db.cljc:386).
- `seon.agent.loop` splits along its existing seams: `transitions`,
  `transition`, `next-event`, streak derivation, and the step-advance
  planner become the portable core ("given the acquired projection +
  claim, the next step is X" — effects-as-data); the wake listener,
  ticker interval, timers, and promise registry stay in the pod leaf; a
  new thin JVM driver (a host-side loop on one thread, plain sync) is
  the CLJ leaf.
- `seon.agent.turn` core: schemas, tx builders (open row, close merge,
  attempt rows, phase CAS), `reply-program`, retry strategy/decision
  fns (llm-retryable?, llm-retry-strategy — pure). The pod leaf keeps
  AbortController/blob-capture Promise ceremony; the JVM leaf uses the
  invocation watchdog it has.
- `seon.runtime.recovery` core: `compile-recovery` + the lease-aware
  decision fn; leaves supply evidence capture.

### 5c. Context render — the honest size of the lift

The render path is the big one and it is NOT part of the first slices.
Today `render-prompt!` runs in the Bun execution child
(runtime.cljs:280-359, invoked through the artifact-digest compiled
path, turn.cljs:373-438), and the JVM host explicitly refuses it
(invoke.clj:138-144). The family is ~8.5k lines across
`seon.agent.ctx` (2,161) + eight `ctx/*` block families + `seon.render`
walker/schema/value (value.cljc is already portable) + `seon.ui.markdown`.
Structurally it is portable-in-principle — blocks are db reads
(execute-many members) + pure formatting, and ctx.cljs's platform
residue is small (~23 js/await sites) — but it couples to `seon.eval`
for legibility helpers and late lookup (ctx.cljs:18, 63, 366-406),
which must be re-seamed before a JVM require can load it. That is a
P2-style family port of its own (est. comparable to the fs/shell+web
lanes combined), and W5's child deletion forces a decision on WHERE
render runs even before it is portable (Owner decision 4).

### 5d. LLM I/O

Adapters are pod-only npm-SDK .cljs (openai_compat.cljs:342 injects
fetch; anthropic.cljs likewise); `seon.ai.provider` is already .cljc.
Two honest options:

- **(i) Pod-only LLM service (near term, recommended for L1-L3):** the
  LLM phase is executed only by LLM-capable claimants — i.e. the pod.
  With the phase cursor + attempt receipts, a dead pod's turn parks at
  `:attempt-open`/`:rendered` and resumes on pod restart; the JVM
  meanwhile advances any run whose next phase is eval. This matches the
  owner's stated end state ("pod demotes toward web UI + LLM I/O +
  scheduler").
- **(ii) JVM http leaf (P4 completion):** request-building/response
  interpretation already lives in the adapters as mostly-pure map
  transforms; a `java.net.http` leaf under a `seon.ai` portable core
  erases the tier difference entirely. This is what makes "kill the pod
  FOREVER, agents don't notice" true rather than "kill the pod, it
  restarts". Not required for U12 as written.

## 6. Scheduling and wakeup — no new coordination mechanism

Who notices a claimable run ("open run + unconsumed input + no live
claim" — a pure query):

- **Pod:** unchanged — the per-agent wake listener over the writer's
  committed-report interests (loop.cljs:1096-1138; session listen!
  db/session.cljs:561-621) plus the one ticker (loop.cljs:1192-1241).
  Both become claim-fenced: every close/steal/schedule-fire tx leads
  with the epoch fence, so N processes running the same scans converge
  (VA-7) — duplicate scans become harmless CAS losers.
- **JVM host:** two grounded options, in preference order:
  1. **Extend the host db leaf with the existing typed
     `listen-request`.** The writer's interest machinery is
     connection-agnostic (writer.clj:2364-2453 keys interests by
     transport connection; nothing is pod-specific), and the protocol
     shape exists (protocol.cljc listen/unlisten requests, consumed at
     db/session.cljs:573). The host leaf's retained pool sessions
     (db/host.clj:33+) gain one interest-bearing session. This is the
     native primitive and the seam design's anticipated moment ("until
     a consumer exists — no speculative parity").
  2. **Poll**: a host-side tick running the same portable claimable-run
     scan. Honest, ~zero code beyond the portable core + one timer, at
     the cost of tick-latency (30 s default cadence,
     loop.cljs:1194-1198). Acceptable for the first JVM-claimant slice;
     replaced by (1), not layered on it.
- Datahike-native alternatives considered and rejected: `core/listen!`
  is process-local (core.cljc:199-217) and lives inside the writer
  process only; a transaction-function-based queue or a second feed
  would be a parallel mechanism.

## 7. Sci context lifecycle per claim

- **Retained vs per-claim ephemeral, with measured costs:** fork is
  ~0.05 µs; corpus replay is the real cost and is linear
  (0.17 ms/1 def → 2.24 ms/100 → 7.28 ms/500, hot-JVM p50, crashproof
  §2). The demo agent's working set is far below 500 defs, so
  per-claim reconstruction is affordable TODAY — but retained contexts
  are strictly better and already correct as a cache. Ruling
  recommended: keep the retained per-agent context (contexts map,
  session.clj:277; park-drop at invoke.clj:249-250), and make the cache
  key explicit: `(agent-id, admitted program generation, home-ns corpus
  basis-t)`. A claim whose basis moved past the cached key drops and
  replays — which is exactly what `verify-pinned-function!`/
  `materialize-pinned-function!` already do per invocation for pinned
  fns (invoke.clj:48-61); the claim generalizes the same rule to the
  whole context.
- **Interrupt/deadline per step:** already optimal — per-invocation
  scheduled watchdog → `Thread.interrupt` + sci `:interrupt-fn` +
  uncatchable marker + interrupt-aware core overrides
  (invoke.clj:100-103, host/context.clj:879, sci/interrupt.cljc:32-42).
  The claim adds only the DURABLE bound: the step deadline is derived
  from the run deadline and the lease; a step that outlives its lease
  gets stolen, and the late settler's write fails the epoch fence. The
  pod-tier equivalent stays whole-process (WP-S2 kill; the host-lane
  `kill!` UDS-close stub, execution/host.cljs:603-607, is NOT a
  preemption path and P4 does not need it to become one — the JVM
  interrupt is the in-thread cancel, the supervisor is the physics).
- **Memory bounds:** base sharing via persistent structure; retained
  live-values already bounded per session (session live-values
  order/values with caps, invoke.clj:251); parked agents cost nothing.
  The q18 OOME drill remains the supervisor-layer proof (containment
  thesis), unchanged by this design.

## 8. Optimality audit — mechanism → native primitive → Seon hand-roll disposition

| Need | Native primitive (cited) | Seon today | Disposition |
|---|---|---|---|
| Exclusive claim / fence | `:db.fn/cas`, nil-expected = absent, multi-op per tx (datahike transaction.cljc:963-985) | `db/cas-assert` + run-fence pointer CAS (run.cljs:389-395); open-turn old→old (turn.cljs:555-558) — insufficient for multi-driver | STRENGTHEN: epoch attrs + two-op fence in `run-fence`; host `claim-run-fence!` gains the epoch field. Nothing deleted. |
| Lease/heartbeat/expiry | none in Datahike (correct — it's app data) | `last-beat-at` + `stale-ms` + watchdog scan (run.cljs:37, 966-1074) | REUSE as the lease verbatim; add steal branch. Do NOT add lease-until (derived). |
| Cross-process wakeup | fork `datahike.committed-report` + writer selective interests (committed_report.cljc; writer.clj:2364-2453) | pod session listen! only (db/session.cljs:561-621); JVM leaf lacks listen (db/host.clj) | EXTEND host db leaf with the existing typed listen-request; interim poll acceptable. Never a second feed. |
| Serialized writes | LocalWriter single processing thread (writer.cljc:42-76) | seon.db.server on top (settled architecture; datahike's own remote `backend-dispatch` writer.cljc:315 deliberately unused) | KEEP. Note constraint: commit batching ⇒ no branch-from-every-t (crashproof §4). |
| Idempotent effect replay | writer op-id receipt + `recover-committed` (writer.clj:1433-1444) | ruling 9 landed both tiers (db.cljc:382-415) | KEEP; P4 recovery consumes it as designed. |
| Per-form durability | — (Seon receipt mechanism) | receipt-before-run + terminal CAS (host/eval.clj:255-467; eval/receipt.cljc) | KEEP unchanged — this is the model the phase cursor generalizes. |
| Turn-progress durability | plain datoms + CAS | LOCALS: `!attempts`, parse/eval cursor, streak, promise registries (turn.cljs:981; loop.cljs:88,94,409) | DELETE as authority: attempt receipts (4), phase cursor (3), derived streak; atoms demote to caches. |
| In-step preemption (JVM) | sci `:interrupt-fn` + `sci.interrupt` overrides + Thread.interrupt (sci/interrupt.cljc; invoke.clj:100-103) | already wired (host/context.clj:879) | KEEP — optimal. |
| In-step preemption (pod) | none (single JS event loop) | `await-bounded` frees the awaiter only (loop.cljs:289-311); WP-S2 kills processes | KEEP posture: awaiter-free + CAS fencing + supervisor kill. Do not build a Bun kill bridge for P4 (owner P3a ruling stands). |
| Context materialization | `sci/fork` ~0.05 µs + load-fn lazy + linear replay (core.cljc:318-323; crashproof §2) | retained ctx cache, park/restore (host/context.clj:25-28) | KEEP; make cache key explicit (generation + corpus basis). No compiled-context persistence exists — do not invent one. |
| Claim archaeology / notices | as-of/history temporal indexes (db.cljc:351-369) | recovery notices already history-derived (recovery.cljs:559-677) | KEEP pattern; claims need zero audit attributes. |
| Step scheduling | — (DB is passive about wall-clock) | ONE ticker + pure scans (loop.cljs:1192-1241; run.cljs scans) | KEEP; scans move to the portable core; every ticker action becomes epoch-fenced so N tickers converge. |

Net: **no Datahike or sci capability is being missed**, and the
hand-rolls to delete are exactly the process-local authorities the
audit already indicted (promise registries as serializers, buffered
attempts, loop-local cursor/streak, close-inferred message coverage,
unconditional cold recovery).

## 9. Phased cut (smallest honest slices, falsifier + acceptance each)

Ordered so every slice is independently landable and the U12 drill
arrives as early as truth allows. Slices L0-L2 are pod-only durability
work (no new JVM capability); L3 is the first cross-process claimant.

- **L0 — claim epoch + fence + consumption edge** (owner: run.cljs +
  turn.cljs fence call sites + host/eval.clj fence map + loop wake path).
  Falsifier: two simulated drivers race one open run (the audit's
  scenario) — WITHOUT the fix both open-turn! txs commit; WITH it
  exactly one turn identity exists and the loser holds a direct CAS
  error value. Acceptance: that regression + full gates + a live drive
  unchanged in behavior (single driver is the common case and must not
  notice).
- **L1 — durable attempt receipts** (owner: turn.cljs attempt family).
  Falsifier: kill the pod between provider dispatch and response on the
  demo scenario; today the DB cannot say an attempt existed
  (audit §loss-boundary 2); after, an `:open` attempt row with its
  ordinal and config digest survives. Acceptance: attempt rows commit
  open/terminal; close-turn! no longer carries them; regression that a
  crash leaves `:open` (never a phantom terminal).
- **L2 — phase cursor + lease-aware recovery** (owners: turn.cljs,
  recovery.cljs, run.cljs steal branch, watchdog).
  Falsifier: the five kill points (audit graduation list — before
  dispatch / after possible provider acceptance / after reply link /
  during a form after a committed effect / after eval terminal before
  close). Today every one closes the run `:crashed` and the turn
  `:interrupted`; after, restart resumes the exact phase and the run
  COMPLETES. **Acceptance = the U12 drill as the reconciled plan words
  it: kill the pod mid-turn on the demo scenario, restart, the run
  completes with zero lost/doubled effects** — proven from receipts
  (one consumed-input edge, one turn identity, ≤1 terminal attempt per
  ordinal, every effect receipt settled exactly once).
- **L3 — portable step core + first JVM claimant (eval phase)**
  (owners: run/loop/turn/recovery cores → .cljc per 5b; a host-side
  driver; host noticing via poll first).
  Falsifier: kill the pod after `:reply-ready`; WITH the pod still
  down, the JVM host claims the run (steal after lease expiry),
  advances `:evaling → :evaled → :published` and closes the turn; the
  pod restarts and merely opens the next turn. Acceptance: the demo
  scenario with receipts proving host-tier phase advancement while the
  pod process was dead, dual-tier .cljc tests for the ported cores
  (portable_test pattern, test/seon/db/portable_test.cljc precedent),
  census delta.
- **L4 — pod demotion completers** (parallel-portfolio units, each
  owner-ruled): (a) render relocation/port (5c) — required by W5 child
  deletion regardless; (b) JVM LLM http leaf (5d-ii) if ruled; (c) host
  leaf listen-request (6.1) replacing the poll; (d) two-autonomous-pod
  race drill = the audit's full graduation gate (kill the WINNER at all
  five points; require single consumed-input/turn/attempt/effect/eval
  facts and operatorless takeover).

Each slice appends its scars to `conversion-wiki.md` and updates the
anchor same-turn (standing rules).

## 10. Owner decisions needed (flagged, not silently chosen)

1. **Claim placement.** Recommended: claim attrs on `seon.agent.run`
   (2a), REUSING heartbeat+stale-ms as the lease. Alternative: the
   crashproof §5A separate `:seon.turn-claim` entity (cleaner history
   granularity per turn, at the cost of a second ownership vocabulary
   and a second expiry scan). This design assumes the run-attr form.
2. **Claim scope.** Recommended: run-scoped claim + phase-eligibility
   scheduling (3). Alternative: per-phase claims (finer parallelism
   inside one turn — no identified need; rejected here as a second
   claim shape).
3. **LLM I/O end state.** Pod-only LLM service through L3 (5d-i) is
   assumed. Decide whether the JVM `java.net.http` leaf (5d-ii) is a
   P4 unit or stays queued — it is what upgrades U12 from
   "pod restarts invisibly" to "pod is optional".
4. **Render placement at W5.** Child deletion orphans
   `render-prompt!`'s current home (the Bun child). Options: (a) run
   render in-pod-process (small move, keeps .cljs); (b) the full
   ctx/render family port to .cljc (~8.5k lines + the seon.eval
   re-seam, 5c) so any claimant renders. (a) then (b) is the honest
   order; (b)'s timing is the decision.
5. **Watchdog steal semantics.** A stale claim now yields
   steal-and-resume instead of close-`:crashed` + parent notice.
   Confirm the notice policy: notify on STEAL (noisy) or only on the
   eventual honest close (recommended — the parent cares about
   outcomes, not custody changes).
6. **Claimant identity shape.** Recommended: the WP-S2 process identity
   pair (pid, start-instant — process.clj vocabulary; pid alone lies,
   wiki) rendered as one string; alternative: launch
   generation ids. Implementation-level, but it is the value in every
   claim datom — pin it once.
7. **Host leaf listen extension.** The seam design deferred the
   listener surface until a consumer existed; P4 is the consumer.
   Approve extending `seon.db.host` with the existing typed
   listen-request (6.1) as an L4 unit, or hold at polling.

## Appendix: reconciliation with the precursor designs

- crashproof §5A (claim/lease) → §2 here, entity placement changed
  (owner decision 1), CAS/steal semantics made concrete against
  transaction.cljc:963-985.
- §5B (input-consumption) → folded into the claim/renew tx (2b), no new
  entity.
- §5C (attempt receipts) → §4, strengthening the existing attempt
  component family instead of a new `:seon.attempt` namespace.
- §5D (phase cursor) → §3, phases mapped onto already-durable facts.
- VA-1..7 → L0..L4 respectively (VA-1=L0, VA-2=L0/L3 streak, VA-3=L1,
  VA-4/6=L2, VA-5=already landed as ruling-9 op-id + effect metadata,
  VA-7=L3/L4 scheduling); the audit's graduation gate = L4(d).
- Ruling 19 check: the only metadata this design requires remains
  `:malli/schema`; effect declarations stay optional (absent ⇒
  `:external` conservative); no new markers.
