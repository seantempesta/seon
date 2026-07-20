---
type: issue
status: resolved
tags: [issue, database, cljs]
severity: friction
---

# Complete gates retain pre-v11 listener assumptions

## Evidence

The 2026-07-19 complete writer gate expected protocol version 10 and waited for
a database-advanced event on listener sockets that had never acquired their
databases. Protocol version 11 deliberately makes database-advanced delivery
an acquisition option so execution children can decline unsolicited values.

The complete operator gate also found canonical `seon-skills/ui-canvas` still
documented synchronous `my.canvas/state` calls while its generated Codex
adapter described the real asynchronous interface.

## Acceptance

- The closed protocol test names version 11.
- A listener receives database-advanced events only after explicitly acquiring
  that database with delivery enabled; database isolation and unlisten behavior
  still pass.
- The canonical canvas skill documents `await` and regenerates exact adapters.
- Focused writer and operator gates pass before the complete rerun.

## Resolution

The closed protocol expectation now names version 11. The keyed-listener test
explicitly acquires each database with database-advanced delivery enabled
before expecting those events, while retaining its ordered datom, isolation,
and unlisten assertions. The focused writer gate passes 25 tests/154
assertions.

The canonical canvas skill now documents asynchronous state reads and generated
adapters are exact. The focused operator skill gate passes 2 tests/7
assertions. The complete gates remain the archival checkpoint.
