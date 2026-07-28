---
type: research
status: active
tags: [research, agent, runtime]
---

# Zombie constructibility — deciding the claim/epoch deletion (F0(d))

Adversarial audit, 2026-07-28. Question: can any execution context
outlive its replacement and still commit a run transition ("zombie")?
If not, the claim/epoch machinery guards an unconstructible scenario
and the owner's standard says it dies. Judged against the current
source (presence-settlement wave `7bb7ccbfe`/`447abce8e`, orderly-stop
completion `852ef9759`, var-backed procs `1b72cc8da`) and against the
F2 future in which the central loop pass is deleted for per-agent flow
graphs.

**Verdict up front: NO zombie is constructible.** The lifecycle chain
(§1) leaves at most one thread able to commit run transitions per
branch at any instant, and the presence fences close the two
transaction-level orders a hypothetical second committer could take.
Every epoch and lease check guards only the multi-claimant world the
flock already deleted. What the audit DID construct is the inverse
defect: a live context that can never commit what it must — the
unheld-resume disposition livelock (§3), reproduced independently here
and by the trigger-conservation audit the same day
(`trigger-conservation-2026-07-28.md` property-a violation; its issue
note `a-recovered-planned-run-cannot-complete-and-hot-livelocks.md`
was in flight in the issues directory at writing time — two
independent transition-level repros, one root cause).
The deletion slice is §6. One correction to the commissioning brief:
lease clocks are NOT already deleted — `::lease-until`, `claimed?`,
`expired?`, and `heartbeat-tx` all survive in `run.cljc` today; the
slice below deletes them.

## 1. The lifecycle chain — why one committer per branch is structural

Every run transition is a `[:db.fn/call …]` on the branch connection's
serial writer, so "zombie" reduces to: can two threads hold that
connection's write path across a replacement boundary?

1. **One JVM per store.** `store/open-store!` takes a non-blocking
   exclusive flock before existence checks; a foreign holder refuses
   immediately, and in-process re-opens refuse via the `held-flocks`
   registry because JVM `FileLock` is per-process
   (`src/seon/cluster/store.clj:186-235,277-334`).
2. **One connection per branch per process.** `open-branch!` refuses
   `::branch-already-open` — Datahike would reference-count a second
   connect into the SAME connection, silently giving two instances one
   writer (`store.clj:355-384`).
3. **One instance per cluster name per JVM.** `reserve-cluster!` CAS's
   the registry; a stopping instance's marker still occupies the entry
   until `stop!`'s `finally`, so a replacement cannot start while any
   old pass can still run (`src/seon/cluster.clj:247-257,898-908,
   1019-1043`).
4. **One thread per loop, and evals run ON it.** The loop is one flow
   proc (one `futurize`d loop thread —
   `reference-code/core.async/.../flow/impl.clj:243-321`);
   `seon.sci.eval/evaluate` "runs on the CALLER's thread"
   (`src/seon/sci/eval.clj:292-297`) and the `:resume` fold calls it
   inline (`src/seon/cluster/loop.cljc:769-800`). `seon.flow/submit!!`
   has NO production caller (only `flow_test.clj`) — there is no
   separate eval-settle thread to orphan.
5. **Orderly stop joins the pass, event-driven.** Flow reads control
   only between transforms (`impl.clj:283-321`, `alts!!` with
   `:priority true` but only re-entered after the transform returns),
   so the `::flow/stop` transition arity — which publishes the
   completion marker (`loop.cljc:436-448`) — runs strictly after the
   active pass, model call and transactions included. `disarm-loop!`
   blocks on that promise-chan before `d/release`
   (`cluster.clj:695-726`).
6. **Crash replacement is by fact.** A killed process's threads die
   with it; the next boot's `recover-runs!` runs BEFORE `arm-loop!`
   (`cluster.clj:728-772`), releasing dead custody and stamping
   `interrupted-at` on every running receipt. Process identity is
   `(pid, start-instant)`, so even a same-pid respawn is a different
   holder string (`cluster.clj:393-403`).

Consequence: across every replacement boundary (orderly stop, crash,
graph re-arm), the old committer is provably finished before the new
one exists. Two live claimants are unrepresentable.

## 2. Scenario table

| # | Scenario | Verdict | Evidence |
|---|---|---|---|
| a | Old loop thread survives instance-addressed `stop!` and settles a run the new instance works | **Unrepresentable** | §1.3+§1.5: registry marker blocks the replacement until `finally`; completion marker published only after the active transform returned; the released connection refuses further writes. A delayed `stop!` of a stale instance value is a no-op (`active-instance?` compares the prepl-server identity, `cluster.clj:892-896`). |
| b | Eval thread (sci on `:compute`) completes after its run was recovered; its settle commits against the new state | **Unrepresentable today** | No such thread exists: evals run synchronously on the loop thread (§1.4). And sci'd code cannot commit anything — the base ctx binds only pure `my.run`/`my.message` values, no db capability (`sci/eval.clj:136-163`); a time-limited eval returns on the SAME thread before any settle is built. |
| c | Wake/fault procs alive after graph rebuild | **Unrepresentable** | No rebuild path exists (the graph is created once per instance, `cluster.clj:613-693`). Disarm order: graph stop + completion join → fanout stop (joins its OWN completion before untap, `flow.clj:632-650`) → wake channel close. A stale `listen!` callback can only `offer!` into a closed channel (returns false, harmless) — listeners never commit (`wake.cljc` handler prohibitions). |
| d | Hot reload re-defs a var mid-pass | **Unrepresentable as a zombie** | The active pass finishes in the old fn body; the next transform derefs the var root (var-backed procs, `flow.clj:83-111`; `1b72cc8da`). Passes never overlap (one proc thread). Transition fns are applied BY VAR inside the serial writer (`run.cljc:220-222`), one deref per transaction — old-code and new-code commits still serialize through the same fences. Worst case is one pass of stale behavior, the documented live-update semantics. |
| e | In-process `interrupted-at` assertion racing a slow-but-alive settle | **Unrepresentable today; one soft spot named** | Probe §4. Order recover→settle: the settle refuses `::receipt-terminal` by PRESENCE alone (the epoch was unchanged and never fired). Order settle→recover-over-stale-read: the CAS-on-absence SUCCEEDS and stamps `interrupted-at` onto a settled receipt — recover-tx's `terminal?` filter is read-time, not in-transaction (`run.cljc:616-626`). Unreachable because recovery runs pre-arm with no concurrent committer (§1.6); if F1 ever runs recovery beside live procs, move the stamp into a `[:db.fn/call]` that re-checks `terminal?` mid-transaction. |
| f1 | Duplicate cluster instances of one name | **Unrepresentable** | Same JVM: `reserve-cluster!`. Same store, two JVMs: flock. Two store dirs: disjoint databases — nothing shared to zombie. Same store, two names: `registry/ensure-cluster!` gives each name its own branch; branches share no runs. |
| f2 | Stop during pre-provider work | **Not a zombie** | The completion join honestly includes the model call (`disarm-loop!` docstring, `cluster.clj:701-712`); nothing new derives after the pass ends. |
| f3 | Error-channel replay | **Unrepresentable** | The fault committer commits only `:seon.error` facts (`commit-fault!` → `error/commit-tx`, `cluster.clj:532-567`); it holds no run transition path. Overflow DROPS with a counted stderr line, never re-enqueues. |
| f4 | An in-process REPL adversary (io-prepl is always open) | **The one real concurrent committer — and the presence fences hold** | A REPL thread CAN transact run transitions concurrently with a pass. Every decision is inside the transaction on the serial writer, so interleavings serialize: a REPL settle first → the loop's settle refuses `::receipt-terminal`; a REPL close first → the loop refuses `::run-closed`. Note the epoch adds NOTHING here: the adversary reads the live epoch as easily as the process string. Presence + in-transaction reads are the whole defense, and they suffice. |
| f5 | Unheld resume with a disposition form (crash recovery's own continuation) | **CONSTRUCTED — but it is a livelock, not a zombie** | §3. The live context cannot commit; nothing commits twice. |

## 3. The constructed incident: unheld-resume disposition livelock

`next-work` routes an open, unheld, planned run to `:resume`
(`work.cljc:183-185`); the loop folds it WITHOUT claiming
(`loop.cljc:766-776`). Receipts start and settle fine (no holder check
on the receipt path), but a disposition form's `terminal-tx` bundles
`close-tx`/`release-tx`, whose `held-run` refuses `::not-the-holder` —
aborting the settle with it. The receipt is left running; every later
pass derives the same `:resume`, refuses `::receipt-exists`, commits
one error fact, and rewakes: the hot livelock the `:close` branch
already fixed for itself (`loop.cljc:924-936`), resurrected one branch
over. Reachable by kill -9 mid-fold (the COMMON recovery case) and by
a plan whose `my.run/wait` is not the final form.

Reproduced at the transition level (probe committed inline below;
also `tmp/zombie-audit-resume-unheld-probe.clj`):

```text
post-recovery run: #:seon.cluster.run{:claim-epoch 1}   ; custody released
next-work: {situation :resume, run r1, ordinal 1}
receipt1 start: :committed
terminal (settle+close): {… :rule :seon.cluster.run/not-the-holder}
receipt1 after abort: {}                                ; still running
next-work after abort: {situation :resume, ordinal 1}
receipt1 re-start: {… :rule :seon.cluster.run/receipt-exists}   ; forever
```

Probe sequence (in-memory Datahike, production transitions only):
open → claim(p-dead) → plan `["(+ 1 1)" "(my.run/complete …)"]` →
receipt 0 start+settle → `recover-tx` with live set `#{p-new}` →
`next-work` for p-new → receipt 1 start → `cluster.loop/terminal-tx`
with `{:my.run/disposition :completed}` → observe the refusal chain
above. Root cause and owner are recorded in the trigger-conservation
lane's issue note
(`a-recovered-planned-run-cannot-complete-and-hot-livelocks.md`),
which reached the identical diagnosis from an independent probe; this
audit's probe corroborates it and adds the no-crash reachability (a
mid-plan `my.run/wait`).

## 4. The interrupted-at/settle ordering probe (scenario e)

`tmp/zombie-audit-interrupt-settle-probe.clj`, both orders over one
running receipt:

```text
A (recover first, settle late):
  settle → {… :rule :seon.cluster.run/receipt-terminal}
  receipt: {:interrupted-at #inst "…"}          ; presence fence held
B (settle first, recover-tx from a stale read):
  settle → :committed
  recover → :committed                          ; CAS nil→now SUCCEEDED
  receipt: {:result-edn "2", :interrupted-at #inst "…"}   ; contradiction
```

Order A is the load-bearing one and it is fenced by PRESENCE — the
run's epoch was identical on both sides, so the epoch check
contributed nothing. Order B shows the recover stamp's settled-receipt
guard lives at read time; today's boot-before-arm lifecycle makes the
interleaving unconstructible, and the F1 hardening (re-check
`terminal?` inside a `:db.fn/call`) is one line when recovery ever
runs beside live procs.

## 5. Claim/epoch inventory — every surviving check, judged

Attributes (`src/seon/schema/run.edn`): `:seon.cluster.run/process`,
`:seon.cluster.run/claim-epoch`, `:seon.cluster.run/lease-until`,
`:seon.cluster.eval/claim-epoch` (also inside the receipt identity
string). Plus `:seon.cluster.loop/terminal-request`'s epoch field
(`schema/loop.edn:27`).

| Check | Where | Guarded scenario | Verdict |
|---|---|---|---|
| `claimed?` (lease liveness) | `run.cljc:74-85`; `claim-call` `::lease-live`; `held-run` `::lease-expired` | A second LIVE claimant stealing / a holder outliving its lease. No second claimant exists (§1); nothing renews a lease (`heartbeat-tx` has ZERO production callers); `now` is pinned per pass so a lease can never expire against its own holder mid-pass, and a claimed run always self-rewakes so no >60s gap opens between passes | **DELETE** — guards nothing; the 60 s constants (`loop.cljc:513,572,945`) are the "tuned constant standing in for an observable event" smell verbatim, and the observable event (process death) is already answered by fact at boot |
| `expired?` | `run.cljc:87-100` | nothing — zero production callers (only `run_test.clj:122-124`) | **DELETE** |
| `heartbeat-tx`/`heartbeat-call` | `run.cljc:303-330` | lease renewal for a long-held run — never called outside `run_test.clj` | **DELETE** |
| takeover epoch increment | `claim-call` `run.cljc:298-301` | distinguishing the new holder's receipts from a displaced live holder's. Every takeover in the system claims an UNHELD run (recovery released it, or `my.run/wait` did); the displaced-live-holder case needs two claimants | **DELETE** — claim becomes: refuse when `::process` present, assert otherwise |
| `::stale-epoch` in `held-run`/`receipt-run` | `run.cljc:203-204,476-477` | a displaced holder committing with an old epoch. Subsumed (§6). Also note: the request epoch is a CALLER PRE-READ (the loop `d/pull`s the run to learn it, `loop.cljc:768`), violating run.cljc's own no-pre-reads contract (`run.cljc:22-26`) | **DELETE** |
| `::stale-receipt-epoch` + epoch in receipt identity `(pr-str [id ordinal epoch])` | `run.cljc:462-466,580-581` | a re-claimed run re-running an ordinal already receipted under the old epoch. Cannot happen: recovery stamps every running receipt terminal, and `next-ordinal` skips terminal ordinals (`work.cljc:105-133`) — no ordinal is ever attempted twice | **DELETE** — shrink identity to `(pr-str [id ordinal])`, which makes "at most one attempt per form, ever" unrepresentable in the identity itself (strictly stronger than the epoch) |
| `::not-the-holder` in `held-run` | `run.cljc:200-201` | loop bookkeeping bugs — it surfaced §3 (as a livelock, but loudly) | **KEEP** — the one custody check; cheap, and it is what makes a custody-model regression a refusal instead of silence |
| `::process` custody presence | `run.cljc`, `work.cljc`, `problems.clj:90-106`, `cluster.clj:513-530` | the incident that saves it: without custody presence, an unplanned open run "mid-model-call" and one "whose holder died losing its paid call" are indistinguishable — the loop would re-call, violating NOTHING-RETRIES-A-PAID-CALL (`work.cljc:33-43`). Also: wedged-run derivation, fault attribution, forensics (who died) | **KEEP** |
| settle-once presence fence (`::receipt-terminal`/`::no-terminal-fact`) + recover CAS-on-absence | `run.cljc:541-596,616-626` | THE fence — proven in §4 order A and against the REPL adversary (f4) | **KEEP** — this is the mechanism that subsumes the epochs |
| one-open-run agent pointer (`::agent-already-running`, close's pointer CAS) | `run.cljc:243-257,388-398` | the real busy fence; F1 carries it forward unchanged by ruling | **KEEP** |
| `recover-tx` dead-holder release | `run.cljc:627-639` | boot recovery by fact | **KEEP**, minus the lease retraction arm |
| epoch prose/log projections | `error.clj:420-433,593-595` | narrate a deleted concept | **DELETE** with the slice |

## 6. Does the presence fence subsume the epoch checks? Proven, with the mechanism named

The epoch exists to refuse a DISPLACED holder's late transition. Case
analysis over every displacement the system can produce:

1. Displacement happens only by death or release (§1: no live steal is
   reachable — `::lease-live` refuses it, and no second claimant
   exists to attempt it).
2. After a death, boot recovery stamps `interrupted-at` on EVERY
   running receipt of every open run before any new work derives
   (`cluster.clj:748-751` — recovery precedes `arm-loop!`), and the
   dead holder's threads are gone, so no late transition from it can
   ever arrive.
3. After a clean release (`my.run/wait`), the releasing pass has
   returned; the same thread is the only future committer.
4. Therefore no receipt is ever RUNNING across a takeover boundary,
   and any hypothetical late settle meets a receipt that is already
   terminal — refused by `::receipt-terminal`, by presence, with the
   epoch checks deleted (§4 order A shows exactly this refusal firing
   with the epoch inert).
5. The remaining epoch consumer — receipt identity uniqueness across
   claims — is subsumed the other way: `next-ordinal` never revisits a
   terminal ordinal, so `(run, ordinal)` identity plus
   `::receipt-exists` makes re-execution refuse loudly (and the crash
   model FORBIDS re-execution anyway; the epoch was licensing an
   interleaving the model rules out).

Under F2 the subsumption gets stronger, not weaker: the central pass
dies, per-agent turn procs all share ONE process identity, so the
epoch could not even distinguish two procs of one process — the
one-open-run pointer and the settle-once presence fence are the
fences that carry, exactly the two F1 pins ("the one-open-run
transaction fence and interrupted+adapt recovery carry forward
UNCHANGED").

## 7. The deletion slice (F0(d))

One wave, cut-first; the §3 seam fix rides the same surgery area
because the simplified claim IS the fix.

- `src/seon/schema/run.edn`: delete `:seon.cluster.run/claim-epoch`,
  `:seon.cluster.run/lease-until`, `:seon.cluster.eval/claim-epoch`
  (attribute + entity rows). `src/seon/schema/loop.edn`: drop the
  epoch from `terminal-request`.
- `src/seon/cluster/run.cljc`: delete `claimed?`, `expired?`,
  `heartbeat-tx`/`heartbeat-call`; `claim-call` becomes
  presence-custody (refuse `::run-held` when `::process` present, else
  assert process — no lease, no epoch); `held-run` keeps
  `::no-such-run`/`::run-closed`/`::not-the-holder` only;
  `receipt-run` drops `::stale-epoch`; receipt identity becomes
  `(pr-str [id ordinal])`; `receipt-settle-call` drops
  `::stale-receipt-epoch`; `retract-custody` and `recover-tx` drop the
  lease arms.
- `src/seon/cluster/loop.cljc`: delete every epoch read/thread-through
  (`172-205, 510-529, 569-573, 625-646, 768, 783, 858, 942-959`) and
  the three `(Date. (+ (inst-ms now) 60000))` lease constants; the
  `:resume` branch claims when it does not hold (the §3 fix,
  mirroring `:close`).
- `src/seon/error.clj:420-433,593-595`: drop the requested-epoch prose
  and log field.
- Tests, replaced by the surviving mechanism's classes, never
  green-washed: `test/seon/cluster/run_test.clj` (55 epoch/lease hits
  — the heartbeat/expiry/steal arms of the state-machine property die;
  the model shrinks to presence custody; keep one regression per
  surviving class: claim-of-held refuses, settle-once by presence,
  recovery idempotence, `(run, ordinal)` uniqueness);
  `loop_test.clj` (10), `work_test.clj` (6), `boot_test.clj` (4),
  `turn_test.clj` (1) epoch plumbing; `problems_test.clj` unaffected
  (process-presence only). NEW regression: the §3 plan
  `[form, disposition]` with released custody closes.
- Unchanged: `::process` custody, `::not-the-holder`, the settle-once
  presence fence, recover-tx's CAS-on-absence, the agent pointer
  fence, `problems/wedged-runs`.

Slice size: 3 schema attributes + 1 request field, 2 dead functions +
2 dead derivations, ~5 checks, 2 probes' worth of livelock seam, ~76
test references across 5 suites; zero behavior a live system can
observe today is lost, one blocker-class livelock is fixed.

## 8. Checks that earn their keep

`::process` presence (the paid-call/no-retry incident, §5), the
settle-once presence fences, recover-tx's CAS-on-absence, the
one-open-run pointer fence, and `::not-the-holder` as the single loud
custody assertion. Nothing else. Named hardening for F1, not now: make
recovery's interrupted stamp re-check `terminal?` in-transaction if
recovery ever runs concurrently with live procs (§4 order B).
