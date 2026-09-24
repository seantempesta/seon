---
type: landing
status: partial — owned slice landed; committer deletion blocked on ownership; install RESET NEEDED
lane: fix-fault-route
created: 2026-09-24
reports:
  - docs/research/agent-platform/one-happy-path/execution-2026-09-24.md (row 1)
  - docs/research/agent-platform/one-happy-path/cross-cutting-astra-2026-09-24.md (row 1, P2)
  - docs/research/agent-platform/fail-loud-dev-resilient-prod-2026-09-24.md (R4)
---

# Lane fix-fault-route (2026-09-24)

Existing parts: `var-process` wraps every proc, and `seon.fault/fault!` is the one synchronous
route. The writer (`seon.error`) decides the occurrence and the wake. This slice sends every
proc throw through that route on the proc's own thread. It makes the committer's surviving
decision use `fault/policy`, and it deletes the first-ever dedup.

## Broken or not done, first

- **The committer graph, fan-out, `CountedDroppingBuffer` and fallback prints were NOT deleted
  (census ≈ 470 lines).** Other producers still write to the fault channel, and all of them
  sit in files this lane does not own:
  - `turn.clj:5359-5406` (write-refusal and backstop `offer!`s)
  - `render/web.clj:2324-2340,2938,3530`
  - `cluster/wake.clj` (route faults)
  - `cluster/agent.clj:1002,1021` (routing plus `join-error-fanout!`)
  - `cluster.clj:3278,3312-3330,3497-3588,3669` (construction, `emit-core-fault!`, stop)
  - `cluster/boot.clj:28-48,131` (`join-launcher-errors!`, which is now inert: the launcher no
    longer emits `::flow/error`)
  - schemas `seon.flow.edn`, `seon.boot.edn:75`, `seon.cluster.wake.edn:25`, `seon.render.web.edn`

  Next slice: turn each site into a `fault!` call, then delete in `flow.clj` everything from
  `dropped-fault-descriptor` to `stop-error-fanout!` together with their tests. Tracked by the
  existing issue `docs/seon/issues/durable-faults-cross-dropping-channels.md`.
- **The dead compute half of the launcher was NOT deleted.** Caller census:
  - `submit!!` is still referenced by the dead `turn.clj:3290-3334`
    (`submission-time-limit-evaluation`, `submit-evaluation!!`, which have no caller).
  - It is also used by tests outside this lane: `env_test.clj:182,251,371,378` and
    `instrument_test.clj:1292`.
  - `flow_test.clj:226,812,833,866` and `cluster/turn_test.clj:66` (a comment) also use it.

  Deleting it needs `turn.clj`, `env_test.clj` and `instrument_test.clj`.
- **First-occurrence-ever delivery is still in `error.clj:1393`** (`commit-call` recipients).
  Owner: `error.clj`. This slice removed only the `fault.clj` pre-read.
- **Fault write cost.** In a warm snapshot, one `fault!` takes 115–236 ms. Over 300 writes,
  67,991 ms were in `fault/record!` and 53,700 ms in `db/transact!` (inside
  `blob/with-publication!`). A proc that throws in a loop is now throttled by this cost instead
  of dropping faults.
  - These pre-existing red tests have unchanged bodies and fail only on their duration bound,
    both at HEAD and with this diff:
    - `fault-test/record-mode-…` (the `< 1000 ms` assert): 7,229 ms at HEAD, 2,473 ms with this diff.
    - `an-unknown-write-outcome-…`
    - `core-fault-signatures-…`: 132 real writes. Unfinished at HEAD; 25.4 s here with every
      assertion green.
  - Owner: `seon.error`/`seon.db` writer cost. No issue file was written, because that path is
    not owned here.
- **RESET NEEDED to install.** `default` (pid 90471) runs archive `ce7384682`, and hook
  publication is off.
- **Schema resource `resources/seon/schemas/seon.fault.edn`** is `record!`'s own return
  contract. It is not in this lane's ledger row. It was edited with its consumer: the
  `:boolean` element was dropped, following the "publish schema resources and consumers
  together" rule. Root should confirm.
- `with-redefs` over `db/transact!` in the old dead-writer case is gone. The replacement
  injects a failing callback value.

## Defects fixed

1. **Two routes for a proc fault → one.** `var-process` now catches transitions and transforms
   and calls `proc-failed!` → `fault!`, with the agent id taken from the scoped environment.
   The launcher's failed submission also calls `proc-failed!` instead of emitting `::flow/error`.
2. **Found live: the stop-transition route never recorded.**
   - `stop-failed!` passed `:seon.error/layer :flow`. The armed `fault!` contract refuses that,
     because it requires a namespaced keyword. So every stop fault was only printed.
   - HEAD's own regression `a-throwing-stop-transition-is-one-stored-fault-and-a-completed-join`
     is red at HEAD (stored `[]`). It is green with this diff, which uses `:seon.flow/proc`.
3. **An already-seen failed write now panics** (P2). The committer decides through
   `fault/policy`, and a failed write is never collapsed as `repeated?`.
4. **The first-ever dedup is deleted.** `previously-reported-fault-signature?` is gone, and
   `record!` returns `[fact outcome occurrence]`.

## Evidence

**Before: default, JVM mode, scratch namespace, scratch graph, no writes.**
- The setup was a throwing transform plus `start-error-fanout!`, with a 5 ms committer callback.
- Of 300 throws: 65 were committed, 166 collapsed into one drop summary, and **69 were lost
  silently**.
- With a 0 ms callback, 300 of 300 arrived. So loss depends on write speed.

**P2 before:** in the committer, a failed write that was previously stored, and one already
seen in this JVM, both gave **0 panics**.

**After: snapshot root.**
- Setup: `git archive` of HEAD `c327d05b5` plus this diff, with `reference-code`, `.cpcache` and
  `target` linked. From-zero boot took 134–287 s; boots were repeated because each test-file
  edit needs republication.
- 300 throws gave **300 stored** (one occurrence, count 300) and nothing left in Flow's error
  channel, in 70,836 ms (236 ms per write).
- 3 throws: 3 stored in 364 ms.

**Focused run `ef055aa2b36e` on the snapshot: 9 executed, 73 pass, 3 fail.** All three fails are
the duration-only reds listed above. Green:
- `a-proc-throw-during-work-is-one-occurrence-never-dropped` (new class regression)
- `core-fault-signatures-…` behaviour, including the new "a failed write panics every time, an
  already-seen signature included" (the P2 class regression)
- `a-throwing-stop-transition-…` (red at HEAD)
- `graph-construction-joins-…`
- `flow-launcher-error-join-test/a-throwing-background-submission-is-one-stored-fault`
- `cluster.fault-message-test`

**HEAD baseline run `0fd637963417`** (HEAD snapshot only): the same `fault-test` duration reds,
the stop test red, and `core-fault-signatures` unfinished.

## Lines

- Source: `flow.clj` +44/−62, `fault.clj` +10/−24, `seon.fault.edn` +1/−2. **Net −33.**
- Tests: +143/−110.
- Other suites not run here may still expect a proc throw on the fault channel. Found and
  converted: flow_test's resume tap now uses a bare `flow/process`. The orchestrator's
  integration run should confirm.

## Released paths

`src/seon/flow.clj`, `src/seon/fault.clj`, `resources/seon/schemas/seon.fault.edn`,
`test/seon/flow_test.clj`, `test/seon/flow_launcher_error_join_test.clj`,
`test/seon/cluster/fault_message_test.clj`, this note.
