---
type: issue
status: active
tags: [issue, runtime, database]
---

# Writer run-readiness! busy-spins when a ready source has no runtime

## Evidence (verified from source)

`src/seon/db/writer.clj:3316-3333` — `run-readiness!` loops:
`take-ready!` → `readiness-runtime` absent → `requeue-ready!` +
`Thread/yield` → recur. With one ready source whose runtime is not
registered, this is a full-speed busy-spin on a writer thread (yield is
not a wait). Found by the poll/timeout census
(`research/poll-timeout-census-2026-07-23.md`, "suspected defect").

## Trace results (Sol read-only trace, 2026-07-23 — verified with file:line)

- `take-ready!` BLOCKS on empty (ArrayBlockingQueue.take) — the spin is
  reachable only via the explicit `requeue-ready!` path.
- Absence IS legitimate transiently: normal unlisten/teardown and
  gap-recovery replacement races, plus the supported late-ownership-
  publication window (writer_interest_test.clj:1005-1021 pins
  "requeued, not lost"). So a loud absence assertion is WRONG — it
  would convert valid races into failures.
- The SUSTAINED spin route is real: `writer/stop!` unregisters the
  runtime BEFORE verifying interests are gone (writer.clj:4864-4867);
  a retained interest's open ready source then take→requeue→yields
  forever whenever another runtime keeps the shared readiness thread
  alive.

## Ruled fix shape (R42 park-until-event)

- Park a missing-owner source once (no immediate requeue); wake it
  exactly once on SOURCE-OWNER PUBLICATION into a registered runtime
  (not on register-readiness! — ownership can publish later); drop
  parked sources on close/replace.
- Fix stop ordering: never unregister a runtime while `::by-scope`
  holds live interests (close/fence them first, or keep the failed-stop
  runtime registered).
- `requeue-ready!` stays the wake primitive (identity-fenced,
  idempotent — committed_report.cljc:226-247).

## Acceptance

- The failed-stop retained-interest scenario does not spin (regression
  exercises exactly it: retained interest + second live runtime).
- The existing late-publication regression still passes (parked source
  delivered exactly once on ownership publication).
- No CPU consumption while parked; no sleep loops introduced.
