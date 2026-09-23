---
type: issue
status: resolved
severity: defect
created: 2026-09-23
tags: [issue, database, read-evidence, turn, backstop, profile, agent-platform]
---

# Read-evidence currency hands a nil source to the index check; root's turn faults, and its backstop fires 600 s later

This note owns the `seon.schema/call-with-projection-state` max of 600,001 ms
in default's C1 profile. The plan status audit reported it
(`docs/research/agent-platform/plan-status-audit-2026-09-23.md`, "Live defects").

## Evidence (default pid 90963, started 04:00:24Z, read-only MCP `eval_clj` jvm)

1. Stored core fault 45846, process `90963-1790136024807`, proc `:seon.agent/turn`,
   `:seon.error/op :step`, two occurrences at 04:01:24.561Z and 04:01:27.552Z:
   `seon.db/index-evidence-current refused source at []: expected a map, got nil …
   Called from seon.db (db.clj:1174)`. The stored `:seon.instrument/args` are
   `[<db t 536871055> nil <revision :attributes :all, generation 4732…> <current :attributes :all, generation 5e9d…>]`.
   Form: `(d/pull db '[*] 45846)` on `(seon.db/db (seon.cluster.boot/connection "default"))`.
2. Stored core fault 46229, same process, `:seon.error/op :seon.agent/turn-completion-backstop`,
   `:seon.error/expected :seon.agent/turn-terminal`, two occurrences at 04:11:20.058Z
   and 04:11:26.350Z: `Agent "root" with no observable open turn did not publish turn
   completion within 600000 ms.` The effective `:seon.config.agent/turn-completion-backstop-ms`
   is 600000. Each firing is 600 s after its step was armed, a few seconds before
   that step threw (item 1).
3. Profile at 04:21:37Z and 04:28:47Z: `call-with-projection-state` has max 600,001 ms
   in both samples. The call count grew from 281 to 418 and throws from 75 to 77.
   No other armed cell has a maximum over 87 s: `transact!` max is 30,185 ms and
   `seon.test/run` max is 86,949 ms.
4. A virtual-thread dump (`HotSpotDiagnosticMXBean/dumpThreads`, JSON) shows every
   io Runnable nested in `call-with-projection-state` via `seon.cluster/projection-executor`
   (loaded class line 3322; the working tree has it at cluster.clj:3426-3440).
   These Runnables are the flow proc loops and the turn backstop observer
   (turn.clj:5434). `Thread/getAllStackTraces` does not list them, because they are
   virtual threads.

## Cause (verified in source)

- `seon.db/read-evidence-current?` (src/seon/db.clj:1149-1180) looks up
  `source` in `(:datahike.query.dependency/sources plan)` by argument position.
  When the plan has no source at that position, `source` is nil. The function
  still calls `index-evidence-current` with it. That function's contract is
  `[:cat :seon.db/database-value :map :map :map]` (db.clj:1107), and its docstring
  says it returns "no decision" for a read it cannot check. The armed contract
  throws instead. Root's turn step escapes with that throw.
- `seon.turn/step` cancels the backstop only when `succeeded?` is true
  (turn.clj:5590-5596). An escaped step therefore leaves the observer
  (`arm-turn-completion-backstop!`, turn.clj:5409-5450) parked in `alts!!` for the
  whole 600,000 ms. The observer then records a second fault for the same failed
  turn, ten minutes later. That observer's lifetime is the 600,001 ms call.
- The profile attributes the observer's timed wait to `call-with-projection-state`,
  because `projection-executor` wraps every io Runnable in it. So the 600 s is a
  wait, not work.

## Live or historical

- The throw occurred twice in this JVM, both at root's first turns after boot
  (04:01). Root has not turned since. It recurs whenever a retained read's plan
  has no source at the evidence's position.
- The profile maximum is fixed at 600,001. The call count keeps growing, because
  test graphs start and stop procs.

## Cost is proportional to

- One parked virtual thread per escaped turn, held for the configured backstop
  (600 s).
- A second fault per escaped turn.
- With the throw removed, the check falls back to the existing replay, which is
  proportional to the read.

## Smallest fix at the owner (not implemented)

- In `read-evidence-current?` (db.clj:1172-1174), take the index check only when a
  source was found: `(when source (index-evidence-current …))`. A nil answer
  already falls through to the declared replay path. The seam this uses is
  Datahike's dependency plan sources (`d/dependency-plan-attributes`,
  db.clj:888).
- Add one regression that fails without the fix: a retained read whose plan
  carries no source at its position is answered by replay, not by a thrown
  contract refusal.
- Optional, needs a ruling: the escape could deliver its failure to the armed
  backstop, so the observer completes at once instead of re-reporting after
  600 s. The docstring (turn.clj:5402-5405) deliberately leaves it armed. Also,
  `offer-turn-backstop-fault!` (turn.clj:5380-5398) falls back to `println` when
  the fault channel refuses the offer. The error policy calls a print-only panic
  a defect.

## Related

- The profile line reads "call-with-projection-state max 600 s" for any
  long-lived io Runnable. That misattribution goes away when step 1.4 deletes the
  ambient projection transport (`projection-executor`); see the plan README §4.

## Resolution (lane m4-write-bound, 2026-09-23)

- `read-evidence-current?` calls `index-evidence-current` only when a source was
  found; a plan without one (the declared `:all` plan) falls through to replay.
- REPL reproduction on default pid 90963 (throwaway ns `m4-write-bound.probe`,
  `nil-source-probe`: a real `seon.db/q` capture with its plan set to `:all`, one
  unrelated commit on a disposable `d/branch!`): parent threw the stored fault's exact
  message, "seon.db/index-evidence-current refused source at []: expected a map, got
  nil … (db.clj:1174)", in 2.7 ms; fixed answers `true` by replay in 5.5 ms.
- Regression: `seon.db-test/a-retained-read-whose-plan-names-no-source-is-answered-by-replay`
  (equal result → true, renamed cluster → false), green in run `deb20e255a9d`.
- The optional backstop/`println` items above stay open for their owner (`turn.clj`).
