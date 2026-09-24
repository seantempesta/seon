---
type: research
status: evidence — read-only study; implementation frozen
created: 2026-09-24
scope: installed core-error policy (:panic vs :record), the 16 stored error signatures on default, delivery and status, smallest loud-dev/resilient-prod design
---

# Fail loud in dev, resilient in prod: how it works today (2026-09-24)

Read-only research. Owner's question: "Why aren't these errors being brought to the
attention of the system? How are we implementing my requested 'fail loud in dev, be
resilient and stable in production'?" Laws: CLAUDE.md "Return agent mistakes; store and
deliver core faults", "Panic visibly", "Make failure local and loud", "Keep the whole
cause". Memory R41: one dial, dev panics on any degraded fallback, prod never crashes.

Evidence is from JVM-mode `eval_clj` on `default` (pid 90471, booted 2026-09-23T22:18Z,
process `90471-1790201894324`) and `data/clusters/default/logs/seon.log`. Stored times
print as `CST` (UTC-6); UTC is given below. Line numbers are at HEAD `b1af1d100` plus
the uncommitted edits in the working tree.

## Broken, first

1. **`:panic` does not panic.** In dev mode a core fault produces one stderr line in
   `seon.log`: `emit-core-fault!` (`cluster.clj:3298-3321`) via the committer's `panic!`
   (`flow.clj:1138-1141`). Flow keeps the proc running after a step throws
   (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:312-316`),
   and nothing in Seon stops the graph. Status, the page and the REPL do not show the
   fault. `runtime_status` reports `health "observed"` with
   `error-signatures 16`.
2. **Health comes from absence.** `mcp-runtime-observation` sets
   `:seon.dev.mcp/health (if connection :observed :unknown)` (`cluster.clj:681-682`).
   Having a connection counts as health. Open faults only add to a count
   (`cluster.clj:661-665`).
3. **Faults are never resolved or re-opened.** `:seon.error/resolved-tx` is declared
   (`resources/seon/schemas/seon.error.edn:129,210`), and `problems.clj:102` reads it.
   Nothing writes it (`rg resolved-tx src` finds only the reader). So there is no "open"
   or "unhandled" state. Every signature ever recorded counts forever, and a fault from
   yesterday's JVM looks the same as one from now.
4. **Delivery goes only to the first occurrence ever, then stays silent across restarts.**
   - `commit-call` picks recipients only when `first-occurrence? (zero? occurrences)`
     (`error.clj:1393,1431-1436`). That means the first occurrence ever, across every
     process.
   - `fault/record!` suppresses stderr for any signature already in the database
     (`previously-reported-fault-signature?`, `fault.clj:32-39`, used at `fault.clj:124`
     and `flow.clj:1118-1131`).
   - Result: once a signature exists, a fault that keeps recurring in every later JVM
     prints nothing and messages nobody. Example: the `refused!` signature `7cda…`
     recurred 12+11+19+19 times across four JVMs and was delivered once.
5. **Delivery goes to an agent that cannot act, and "handled" is read as "resolved".**
   - All 16 signatures have a `:seon.message/about` message from root to root. That is
     the `escalate-to "root"` / `:your-run` routing at `error.clj:1431-1446`, and those
     messages did wake root.
   - But root is the in-cluster DeepSeek agent, doing a toy task (`largest`). Its turns
     took the fault messages as triggers and marked them `:seon.turn/handled`: turn
     `0342edda4bf9` handled 6 fault messages and replied "The task is complete"; turn
     `5ca8da65674e` handled 3. Nothing about the faults was acted on.
   - `latest-answering-turn-t` = 536872224 is later than every message tx (the last is
     536872219). So `unanswered-wakes` = 0 and root correctly runs no episode now
     (`episode-runs 0`).
   - The outside orchestrator, which is the real development responsible party, gets no
     route at all.
6. **Declared refusals are recorded as core faults.**
   - `cluster/refused!` marks its refusals `{:seon.boot/refused true}` (`cluster.clj:697-709`).
   - `boot/request!` recognises only `:seon.cluster.boot/disposition :refused`
     (`boot.clj:662`).
   - So every publication refusal ("Source changed during development adoption",
     `cluster.clj:2328`) goes through `fault/fault!` (`boot.clj:669`) as a core fault. That
     accounts for 70 of the stored occurrences.
7. **Probe noise lands in default as core faults.** Five `user/evalNNN` signatures are MCP
   JVM-mode probes that threw `AssertionError` on core.async threads ("injected Error in
   one write"). The uncaught handler found one live instance (`boot.clj:344-346`) and
   recorded them into `default`. The law says to probe in throwaway namespaces or scratch
   clusters.
8. **The census of swallowed errors is stale.**
   - `swallowed-errors-census-2026-09-23.md` counted 343 catch sites at `3fbf9dd48`.
     There are now 314, and 663 commits have landed since.
   - Its F0 has landed: `refusal.clj:76-114` and `error.clj:555` now carry
     `:seon.error/chain`.
   - Its site table and line numbers must be regenerated with its own committed-to-tmp
     scripts (`tmp/swallowed-errors-census/{full,classify,tables}.clj`). Do not trust them
     line by line.
   - Still present: 23 `catch Throwable _` sites, for example `repl.clj:165`,
     `schema.clj:1095,1100`, `render/transcript.clj:1117` and `ai.clj:868`.
   - The committer's own loss path prints only the class name (`flow.clj:1150-1154`,
     `report-committer-loss!` `flow.clj:1196-1220`).

## 1. The 16 signatures

"Now?" means an occurrence from the current process `90471`. There are **none**: the last
occurrence of any signature was 2026-09-23T16:44Z, 5.5 h before this boot. Root has run no
turn since, so producers that only turns exercise have not been re-exercised. No
recurrence here is not proof of a fix.

Channel columns:
- **L** = stderr line in `seon.log`.
- **M** = message to root. All 16 have one, and all were consumed by unrelated turns.
- **T** = thrown or returned to the caller.
- Status, page and REPL showed only a count in every case.

| # | operation (instrument fn) | when (UTC, Sep 23) | n | class | what happened / producer | real bug? | L | T |
|---|---|---|---|---|---|---|---|---|
| 1-4 | `seon.cluster/refused!` "Source changed during development adoption." (2 signatures by frame 699/700, 4 processes) | 13:51-15:13 | 9+12+11+19+19 | **declared refusal misclassified** | `verify-development-sources!` (`cluster.clj:2315-2331`): another lane's unpublished edit was on disk during adoption. Correct refusal; `boot.clj:662` doesn't recognise `:seon.boot/refused` | yes: classification at `boot.clj:662` / `cluster.clj:702` | no | yes, publish response (panic throws out of `request!` catch, `fault.clj:210-219`) |
| 5 | `seon.cluster/adopt-rows!` arity 4 | 13:54 | 1 | core (reload skew) | caller reloaded against the old 3-arity callee during `68f769a4a` ("rows and the adoption record in one transaction"). Now 4-arity (`cluster.clj:2507`) | transient; the reload-order class stays open | no | yes, publish response |
| 6 | `development-source-refresh!` missing `:seon.flow/executor` (fn `refresh-source!`) | 14:00-14:01 | 2 | core (reload skew) | `4b6834f7d` moved the executor into the graph; mid-edit publish. Now supplied (`cluster.clj:3447,3563`) | transient | no | yes |
| 7 | `seon.flow/fault-committer-proc` missing `:seon.flow/completion` (fn `boot/start!`) | 14:04 | 1 | core (skew at boot) | `ac3d8b2fd` retired completion members; a JVM booted on a half-converted tree | transient | no | yes, start response |
| 8 | `seon.fn.analyzer/invoke-kondo` "cache entries changed again" | 14:08 | 1 | declared race thrown as raw `ex-info` | `analyzer.clj:370-375`, during `populate-source!` from `source/publish!`; a concurrent edit | yes: it should be a declared publication refusal | no | yes |
| 9 | `datahike.datom/compare-value` ClassCastException List→Symbol, agent root | 14:14-14:35 | 7 | core | read-evidence replay, `seon.db/replay-read` `db.clj:973,1556` from `read-evidence-current?` `db.clj:1141` / `read-evidence-changes` `db.clj:1053`: a replayed read compares a list against a symbol-valued index. Root's malformed query was the trigger | **yes, open**; producer is read-evidence replay | yes, log:43092 | no (proc fault) |
| 10-14 | `user/eval861847`, `862863`, `863201`, `885082`, `1002859` | 14:27-14:35 | 1-2 | probe noise | MCP JVM evaluations throwing `AssertionError` on `async-mixed-*` threads; uncaught handler `boot.clj:344` | no (process violation) | yes, 44875-48942 | no |
| 15 | `seon.fn/source-context` NoSuchFileException `test/seon/bootstrap_drive_test.clj` | 15:11 | 1 | core | `fn.clj:127` read a file deleted between listing and read, via `full-source-refresh!` `cluster.clj:1978`. The file is now gone | yes: a deletion race should become a removal or declared refusal | no | yes |
| 16-17 | `seon.sci.eval/acquire-program!` "namespace binding cycle" (2 signatures, 2 processes) | 15:12-15:14 | 2+3 | core | `eval.clj:2030-2053`; context acquisition from `cluster.agent/acquire-context!` `agent.clj:782`. Analysed in `three-entry-defects-2026-09-24.md` | yes; owned by the `sci/eval` lane | yes, 53625/54804 | no |
| 18 | `seon.turn/settle-batch!` missing `:seon.cluster.eval/ns` (fn `resume-turn`), root turn 62279 | 16:44 | 2 | core | `resume-turn` built an evaluation request without `:seon.cluster.eval/ns`; the contract refused | yes; producer is `seon.turn/resume-turn` | yes, 55199 | no |

The table has 18 rows, but rows 1-4 cover 2 signatures and rows 16-17 cover 2, so the
total is 16 signatures. No row was an agent mistake. The only agent involvement is
row 9 (root's query triggered a replay bug) and row 18 (the loop, not the reply). Every
fault that reached a caller did so through `request!` (rows 1-8, 15). No proc fault
(rows 9, 16-18) stopped anything.

## 2. The installed path, end to end

**Where the dial is read.** Each consumer re-reads `config/effective`:
- the committer: `cluster.clj:3490-3495`, falling back to `:record` when absent
- `fault/fault!`: `fault.clj:199-202`
- instrumentation arm time: `instrument.clj:878-921`, captured in the wrapper
- the SCI ctx: `sci/eval.clj:677-713`, falling back to `:record`
- admission: `sci/admit.clj:419`
- render and effect contexts: `render.clj:1011`, `effect.clj:200`

**What `:panic` does.**
- **Contract wrapper:** rethrows to the caller (`instrument.clj:703-713`). `:record`
  commits and returns the refusal value.
- **Admission:** throws on a codec degradation (`admit.clj:419-429`).
- **`fault/fault!`** (the one route, `fault.clj:165-219`):
  - records, then throws `SEON CORE FAULT (panic)` with the receipt and cause;
  - `policy` (`fault.clj:147-153`) panics in both modes when the write fails.
  - Callers: `request!` `boot.clj:669`, `flow.clj:200` (stop transition),
    `cluster/store.clj:606` (listener), `cluster/agent.clj:1087` (join bound).
- **Flow proc faults.** Flow catches the step, puts it on the error channel and continues
  (`impl.clj:312-316`). The fan-out goes to the committer (`flow.clj:1098-1154`), which
  calls `record!` and then `panic!`. `panic!` is `emit-core-fault!`: one `println` to
  stderr (`cluster.clj:3298-3321`). **In both modes the graph keeps running.** Panic
  differs from record by exactly one stderr line, and only on the first signature ever
  (item 4 above).
- **Uncaught throwables** (`boot.clj:340-374`): record, then print under `:panic`.

**Storage.** `fault/record!` → `error/recording` → `[:db.fn/call commit-call]`
(`fault.clj:66-145`, `error.clj:1449-1520`, `error.clj:1336-1447`). One signature entity
plus one occurrence per (signature, agent | turn | process), with an evidence blob holding
the `Throwable->map` face. That meets "Keep the whole cause": the blobs for rows 9, 15 and
16 hold `:via` and `:trace`.

**Delivery.** `commit-call` recipients (`error.clj:1431-1436`):
- the steward of the function's namespace;
- else the attributed agent on an interrupting throwable (`:your-run`);
- else `escalate-to` (`:no-attributable-agent`);
- **only on the first occurrence ever.**

The wake is `:seon.message/to` (`turn.clj:3639`). Nothing marks a fault resolved.

**Swallowed or silenced.**
- `previously-reported-fault-signature?` (`fault.clj:32-39`): prints once per database
  lifetime, not once per process.
- `already-reported?` (`flow.clj:1130`).
- `report-committer-loss!` prints the class only (`flow.clj:1196-1220`).
- The committer catches its own failure and counts `::lost` (`flow.clj:1147-1154`). That
  count is visible only in a ping.
- `boot.clj:662` misclassifies refusals.
- Census residue: see item 8.

## 3. Root causes

- **R1: "panic" was built as "print", not "stop and show".** The ruling "FAIL LOUD IS NOT
  FALL DOWN" (`cluster.clj:3509-3512`) became one stderr line in a 30 MB log that nobody
  reads. Flow's own model continues after a step throw, and Seon adds no stop. So a
  `:panic` cluster keeps running with core faults by construction.
- **R2: nobody consumes a fault.**
  - Delivery addresses root, an LLM agent that has its own task and no fault skill.
  - Answering is decided by the turn's t (`turn.clj:2990-3003`), so any unrelated turn
    "answers" the fault.
  - The outside orchestrator, which is the development owner, has no route. The only
    route is a count in `runtime_status`.
- **R3: status declares health from absence** (`cluster.clj:681-682`). A missing
  resolution state (`:seon.error/resolved-tx` has no writer) forces every consumer to show
  counts, not open faults.
- **R4: first-ever dedup.** Both the stderr line (`fault.clj:124`) and the message
  (`error.clj:1393`) are keyed to "this signature has never existed in the database". A
  recurring fault in a new JVM is silent everywhere.
- **R5: a declared refusal is mislabelled as a core fault** (`boot.clj:662` vs
  `cluster.clj:702`). This inflates the fault set, so real faults are buried in noise.

## 4. Smallest correct design (existing owners only)

**One question:** is this signature open, meaning there is no `:seon.error/resolved-tx`
after its latest occurrence? Everything below derives from that one open-fault query.

1. **Open state (`problems.clj` + `error.clj`).**
   - `error-signatures` (`problems.clj:109-127`) returns open signatures, each with its
     latest occurrence's process, message, root chain link and `last-at`.
   - Resolution is one writer, `seon.error/resolve!`, asserting
     `:seon.error/resolved-tx` (the attribute is already declared). It is called by the
     orchestrator or a namespace agent after the fix, never inferred from a turn.
   - A later occurrence re-opens the signature, because open means an occurrence after
     `resolved-tx`.
   - About +15 lines.
2. **Status tells the truth (`cluster.clj:646-690`).**
   - `health` is `:faulted` when any open signature has an occurrence in the current
     process, `:faulted-historic` when open ones exist only from earlier processes, and
     `:observed` only when none are open.
   - `runtime_status` lists each open fault (signature, operation, count, `last-at`,
     process, root cause line, blob digest for the chain), bounded by the MCP profile.
   - The page shows the same list through `error/render-faults-html`, which already
     exists (`error.clj:1843`).
   - About +12 lines.
3. **Deliver per process and per re-opening (`error.clj:1393`, `fault.clj:32-39,124`).**
   - Recipients fire when the signature is open with no prior delivery since its last
     resolution, not only on the first occurrence ever.
   - Delete `previously-reported-fault-signature?`. The committer's per-process
     `::seen-signatures` (`flow.clj:1094,1117`) already gives one line per signature per
     JVM.
   - About −10 / +4 lines.
4. **Dev panic stops the failing graph (`cluster.clj:3505-3512`).**
   - Under `:panic`, `panic!` also stops the graph named by the fault's agent tag, through
     the existing agent stop path (`cluster/agent.clj` stop/join, the same one
     `agent.clj:1075-1087` uses).
   - A fault from plumbing (armer, render) sets readiness `:seon.boot/panicked` and leaves
     the REPL up.
   - `:record` keeps today's behaviour: store, deliver, keep running. Prod stays resilient
     and dev becomes loud.
   - Re-arming a stopped agent graph is an explicit armer wake after `resolve!`.
   - About +15 lines.
5. **Classify at the producer (`cluster.clj:702`).** `refused!` also asserts
   `:seon.cluster.boot/disposition :refused`, so `request!` returns it as the declared
   refusal it is. `invoke-kondo`'s race (`analyzer.clj:373`) and the deletion race
   (`fn.clj:127`) become `refused!` calls through the publication path. About +3 lines.
6. **Responsible agent in dev.** Under `:panic`, the MCP `runtime_status` open-fault list
   is the delivery to the outside orchestrator. No new channel: the orchestrator's loop
   already reads status. Prod keeps namespace-agent or escalation messages.
   - `escalate-to "root"` should name an agent that is actually tasked with faults. Root's
     toy-task identity is not. This is an owner decision.

**Deleted:** `previously-reported-fault-signature?`, the first-ever gate, and
health-from-connection. About +50 / −12 lines net, in `problems.clj`, `cluster.clj`,
`error.clj` and `fault.clj`. `error.clj` is held by the opening-prompt lane, so steps 1 and
3 in `error.clj` wait for it or go to that owner.

**First slice (steps 2 + 5, files `cluster.clj` and `problems.clj`, no schema change).**
Probe on `default`:

```clojure
(let [o (seon.cluster/mcp-runtime-observation "default")]
  [(:seon.dev.mcp/health o) (count (:seon.dev.mcp/open-faults o))])
;; today: [:observed nil]  (and 16 in problem-counts)
;; after: [:faulted-historic 12]  — the 4 refused! signatures now return as refusals;
;;        a fresh fault in pid 90471 flips health to :faulted
```

Time it: `mcp-runtime-observation` is already 32 s total over 378 calls, max 420 ms.
Record the before and after numbers from the profile line.

**Verification limits.** No fault was reproduced in the current process. Row 9's
producer was found from its stored first-party frames, not re-run. The census re-walk was
not done. The claim that stderr is unread is inferred (log size 30 MB, no reader in
`bin/seon status`), not measured.
