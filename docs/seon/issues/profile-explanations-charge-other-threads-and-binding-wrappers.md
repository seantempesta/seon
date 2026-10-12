---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, profile, measurement]
---

# Profile explanations charge other threads and binding wrappers

## Problem

`seon.profile/explain` (`src/seon/profile.clj:204-241`) reports every armed cell's inclusive growth in the
whole JVM between `begin` and `explain`. Its docstring says so ("concurrent work on other threads is
included"), but every consumer reads the ranking as the operation's own cost. Three things distort it.

1. **Other threads.** Root's test runs, adoption and Flow procs grow the same cells during the window.
   A write-cost probe of one trivial root run (`evaluate-source!` of `(+ 1 2)`) showed the window's
   `seon.db/transact!` calls varying from 3 to 6 with the operation unchanged, and a
   `content-projection` / `declaration-content-key` / `arity-content-key` miss appeared only in windows
   where `seon.test*` cells also grew.
2. **Binding wrappers rank first.** `seon.schema/call-with-projection`, `call-with-projection-state`
   (`src/seon/schema.clj:1279-1294`) and `seon.db/with-branch` are a dynamic `binding` around a
   whole operation. Their inclusive total is the operation, so they top every explanation and read as
   the cost:
   - P3a's stage table charged 193 ms to `call-with-projection-state`;
     `docs/research/agent-platform/one-repl-language-2026-09-25.md` §2.10.
   - The adoption issue charged 18.4 s x86 to `call-with-projection`;
     `development-adoption-writes-program-rows-seconds-before-its-record.md`, "Evidence 2026-09-26".
     That call is the wrapper around all of `refresh-source!`'s body, `src/seon/cluster.clj:2979`.
3. **A call completing in the window contributes its whole duration.** The total accumulates at
   completion, so a 126 s `call-detecting-program-change` that started before `begin` shows up as a
   126 s growth inside a 1 s window.

Each distortion sent a lane toward the wrong owner. The first probes of
`docs/research/agent-platform/landing/fix-write-cost-2026-09-26.md` spent their time proving that the
projection was not rebuilt per write.

## Evidence

Default pid 87270, 2026-09-26, scratch namespace, 8 consecutive trivial root runs through
`evaluate-source!` at 122-195 ms:

- Windows that showed `seon.test*` growth (2, 18, 5 cells) carried 4-6 transactions and a projection
  miss.
- Quiet windows carried 3 transactions and no miss.
- In both kinds of window, `call-with-projection-state` x6 ranked first at 218-259 ms, more than the run's
  own elapsed time.

## Evidence 2026-09-26 (fix-test-request-cost): a delegating arity counts every call twice

The dogfood report's `seon.db/with-declarations x13680` for one `my.test/check` is 6,840 reads. The
3-arity delegates to the armed 4-arity (`src/seon/db.clj:1679-1703`), so one read grows the cell twice.
The same window showed `seon.db/read-declarations x6882`, one per read. Of those reads, 6,297 were
`seon.db/datoms`: three AVET reads for each of about 2,100 names the reverse walk from
`seon.effect/request*` visits (`seon.fn/reverse-closure`, `src/seon/fn.clj:1786-1788`). Total time was
56 ms. The count pointed at the wrong owner. The time sat in `seon.db/q` (84 calls, 887 ms) and two
writes. Landing: `docs/research/agent-platform/landing/fix-test-request-cost-2026-09-26.md`.

## Owner and direction

Owner: `src/seon/profile.clj`, with `seon.instrument` (`timed`).

Library first: JDK Flight Recorder (`jdk.jfr`, in the running JDK 26) samples per-thread stacks and
yields self time with thread identity. It needs no accumulators added to the call path.

Smaller alternatives, each to be priced before choosing one:
- restrict the explanation to the thread that called `begin` plus threads it hands work to (the Flow
  proc thread is a real hand-off);
- mark pure binding wrappers (a declared property, never a hand list) and report them apart from the
  ranking;
- attribute a call to the window only if it started inside it.

## Acceptance

- The same trivial run, measured while another thread runs a test request, explains the same set of
  transactions as in a quiet window.
- No binding wrapper outranks the definition that does the work.
- One class regression covers both conditions.
