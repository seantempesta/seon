---
type: landing
status: UNPROVEN on default: proof owed after the checkout restart
created: 2026-09-23
tags: [agent-platform, errors, M4, fault]
---
# Lane m4-error-route: step 0 of the one error route

Extends README §4 row 1.6 and §7 "Priority to namespace agents";
`docs/research/agent-platform/error-route-final-design-2026-09-23.md` "One required
namespace and exact boundary"; `plan/lane-flow-owns-running-machinery.md` §2
("The dependency between N1–N4 and the error route", N1e/N1f). Prerequisite
`805da372d` (every write bounded; past the bound the write returns typed
outcome-unknown).

## Design (written before code)

**What step 0 is.** A move plus one policy function. `seon.cluster/commit-fault!`
(and its two private helpers `tagged-run`, `previously-reported-fault-signature?`)
moves verbatim into `src/seon/fault.clj` as `seon.fault/record!`; `seon.fault/fault!`
is the thin policy on top. The Flow committer (`flow.clj:1005-1090`) still consumes
`record!`'s tuple through its `:seon.flow/commit-fault!` callback; it leaves in the
fault-graph slice, so the tuple stays (accreted with a fourth element, the occurrence
lookup ref, which the receipt needs).

**Seams (file:line at HEAD `5246cb315`).**
- Recording, signature and occurrence identity: `seon.error/recording`
  (`src/seon/error.clj:1478-1549`, occurrence id `:1525-1529`), committed through
  `[:db.fn/call #'commit-call ...]` (`:1365-1476`). Unchanged.
- Wake: the writer decides inside `commit-call` (`error.clj:1459-1466`): steward,
  else the attributed agent's run, else `:seon.config.error/escalate-to` for a
  throwable-backed fault; `config/default.edn:289` sets it to `"root"`. Unchanged:
  "root by default, or the namespace owner" is already the writer's decision.
- Bounded write: `seon.db/transact!` returns `:seon.db/transaction-outcome-unknown`
  past `:seon.config.db/write-time-limit-ms` (`805da372d`); `record!` never retries.
- Dial: read once, after the write, from `config/effective` on the connection's
  current value; `policy(mode, committed?)` is `:record` only for `[:record true]`.
- Record-once: a panic is `ex-info` whose data carries `:seon.fault/recorded` and
  whose cause is the original; `fault!` walks `ex-cause` and rethrows a carrier
  unchanged. No weak set, no global state.

**Cost.** One `fault!` = one `record!` (reads: config/effective, tagged-run query,
signature query, error/prepare over the failure's evidence; one transaction whose
`commit-call` reads the occurrence, recurrence and recipients) + one config read.
Proportional to the failure's evidence size and the fault family's rows for that
signature, not to the program or store. Measured on the parent (below): first
fault on a fresh branch 404 ms, repeats 116-122 ms — sub-second, synchronous on
the caller's thread is affordable.

**Simplest alternative considered.** Changing `record!`'s return to the receipt and
converting the Flow committer, `sci/eval.clj` and `instrument.clj` consumers now:
three more files and the committer's slice pulled forward. Rejected: the tuple
consumers leave with the fault graph; step 0 keeps them loadable unchanged.

## Changes

- `src/seon/fault.clj` (new): moved `record!` (+ helpers, now with contracts),
  `policy`, `carried-receipt`, `fault!`.
- `resources/seon/schemas/seon.fault.edn` (new, required by the move):
  `:seon.fault/recording`, `/context`, `/recorded`, `/policy`, `/committed?`, `/outcome`.
- `src/seon/cluster.clj`: helpers + `commit-fault!` deleted (moved); the two Flow
  callbacks call `fault/record!`; unused `seon.error`/`seon.error.refusal` requires removed.
- `src/seon/cluster/boot.clj`: arm-request callback and `record-uncaught!` call
  `fault/record!`; `request!`'s catch keeps boot's own `:refused` disposition as its
  declared case and routes every other failure through `fault/fault!` in the
  request's world (`request-world`: carried environment, else the named/sole
  instance; none panics).
- `test/seon/instrument_test.clj`, `test/seon/cluster/fault_message_test.clj`:
  caller conversion (`fault/record!`); fault_message_test now passes the declared
  recording observation (it passed a bare fault, which the new contract refuses).
- `test/seon/fault_test.clj` (new): three regressions (record + chain + wake; panic
  stores then throws with receipt, rethrow/wrapped not recounted; outcome-unknown
  panics in both modes with exactly one writer attempt).

## Later slices (listed, not touched)

- Six `offer!`+`println` fault sites: `src/seon/turn.clj:5382`, `:5405`;
  `src/seon/cluster/agent.clj:1084`; `src/seon/render/web.clj:2343`, `:2941`, `:3541`.
  Also fault offers at `src/seon/cluster/wake.clj:388`, `:547`.
- Fault graph / counted-dropping route: `src/seon/flow.clj:999`
  (`counted-dropping-buffer`), `:1005` (`fault-committer-step`), `:1141`
  (`report-committer-loss!`), `:1165` (`join-fault-committer-errors!`), `:1191`
  (`start-error-fanout!`), `:1266`, `:1294`; `emit-core-fault!` `src/seon/cluster.clj:3291`.
- Recipient-missing fallthrough to nobody: `src/seon/error.clj:1459-1466` (`:else {}`).
- `record!`'s outer catch builds a message-only fact (`fault.clj`, last form of
  `record!`); the throwable itself is returned as the outcome and `fault!` suppresses
  it onto the panic, so nothing is dropped, but the pre-fact case records nothing.

## Evidence

Parent probe (HEAD `5246cb315`, default pid 90963, JVM eval, fixture branch
`:m4-error-route-probe` off `cluster-default`, released and unlinked after):
`(seon.cluster/commit-fault! conn "default" process caps {:seon.error/source
{:clojure.core.async.flow/ex (ex-info "m4 probe fault A" {:probe 1})}
:seon.error/declared-schema :seon.flow/exception-error})` three times.
Branch 31.6 ms; first `:seon.flow/committed` 403.96 ms; repeat 122.38 ms; other
message (same signature) 115.88 ms; wake message to `root` about the signature present.

Lint: `clj-kondo` on all changed paths, 0 errors.

**Blocked.** At ~05:15Z default (pid 90963) reached a full heap (G1 used 10.5 of 10.7 GB,
1297 old regions, 8 free; 1200-1330 % CPU) and stopped answering: `bin/seon status`
timed out at its 30,000 ms bound twice; `jcmd Thread.print` showed ~12 prepl
connections in `mcp_runtime_observation` → `problems/failed-tests` →
`test.runner/latest_results` pulls and one `async-mixed` thread deserializing index
nodes. My contract-compile check eval (`seon.contracts-compile-test/check` over
`fault.clj` and `boot.clj`) was lost with the session; no thread of it was on the
stack. Not attributed further. **RESET NEEDED** (the orchestrator's call).
Not run: adoption (`bin/seon init --dev default --changed ...`), the named
`seon.fault-test` request, the incremental request, the packaged contract compile.

**Orchestrator FREEZE (after the heap exhaustion).** Default was rebuilt by `bin/seon nuke`
and runs committed HEAD from an archive; this slice was committed lint-clean and
UNPROVEN on default: proof owed after the checkout restart (adoption, the named
`seon.fault-test` request, the incremental request over `seon.fault/fault!`,
`seon.fault/record!`, `seon.cluster.boot/request!`, `seon.cluster.boot/record-uncaught!`,
and the packaged contract compile of `fault.clj`, `boot.clj`, `seon.fault.edn`).
