---
type: issue
status: open
severity: defect
created: 2026-09-23
tags: [issue, test-runner, provenance, performance, agent-platform]
---

# admit-run derives the program digest from every change since the seal (10-15 s per admission)

## Evidence (default pid 90963, 2026-09-23 ~04:40Z, lane unfinished-runs)

- In the regression `seon.test.interrupted-request-test`, one
  `[:db.fn/call seon.test/admit-run admission]` of two members took
  13,674 ms and 15,316 ms (timed around `support/transacted!`). The
  `commit-results!` after it took 360-542 ms.
- `(#'seon.test.runner/derive-program-digest (seon.db/db conn))` on default
  took 9,618 ms. The memoized `seon.test.runner/program-digest` on the same
  committed value took 0.2-0.4 ms. `admit-run` calls `runner/program-digest`
  on the in-transaction database (`src/seon/test.clj`, `admit-run`, the
  `digest` binding). That value has no committed identity, so the memo is
  skipped and the digest derives from scratch.
- Breakdown of the derivation at that moment:
  - seal basis t 536870921, head t 536871537.
  - 6,292 entities carry a program attribute in `since` the seal (520 ms).
  - 2,609 of them are program rows (272 ms), and `pull-many` over them takes 338 ms.
  - The remaining ~8 s is `program-fact` over the old and current rows.

## Cost is proportional to

It is proportional to every program row changed since the source seal. A
development adoption changes program rows without moving the seal, so the
cost grows through the JVM's lifetime. It is paid once per admission
transaction, which means once per request batch.

## Smallest fix at the owner (not implemented)

Hand `admit-run` the digest its caller already derived. `selection-admission`
reads `runner/program-digest` on the committed value, which is a memo hit, and
the writer then compares that value with its own basis. The other way is to let
the in-transaction value key the memo by its db-before commit. Either way the
transaction stops redoing whole-derivation work. Separately, the seal should
advance on adoption so the diff stays proportional to one adoption.
