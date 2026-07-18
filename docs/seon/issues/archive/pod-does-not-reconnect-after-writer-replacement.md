---
type: issue
status: closed
tags: [issue, database, pod, flow]
---

# Pod does not reconnect after writer replacement

## Evidence

On 2026-07-18 the writer JVM workload was killed with `SIGKILL`. The operator
replaced only the writer while retaining the healthy watcher and Bun pod. Their
owner PIDs remained `29240` and `30043`; the writer owner changed from `29600`
to `32032`.

The retained pod's physical database session closed when the old writer died.
After the replacement reported ready, `POST /agents/run` returned HTTP 500 and
the pod ticker repeatedly failed with `This process has no open database
session.` The `seon.db` close callback retains the selected database and
listener functions, but ordinary reads do not invoke the existing coalesced
`open-session!` path. Only transaction recovery currently does so.

## Acceptance

- The next ordinary database operation after physical disconnect reuses the
  retained database selection and the one coalesced `open-session!` owner.
- Concurrent reads share that opening and retained listeners restore once.
- An owner-requested `close-session!` never reconnects.
- A killed writer is replaced without changing the watcher or pod owner PID,
  and a subsequent real agent turn completes through the replacement.
- Focused database facade and operator tests pass.

## Resolution

Commit `9975a4ec` makes ordinary requests reopen the retained database
selection through the existing coalesced `open-session!` owner. The focused
facade suite passes 16 tests and 79 assertions, including an ordinary read that
owns exactly one reconnect. Existing session proof covers concurrent identical
opening and listener restoration.

Live, watcher PID `48456` and pod PID `48926` survived the writer replacement;
writer PID changed from `48796` to `50460`. Agent `sharp-pigs-smell` then
executed `seon.db/db`, sent its result, closed its plan, and completed in one
turn/11.16 seconds at basis transaction `536872797`.
