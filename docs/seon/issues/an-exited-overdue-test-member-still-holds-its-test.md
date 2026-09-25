---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, test-runner, agent-platform]
---

# An exited overdue test member still holds its test

Observed on default (pid 35446, started 2026-09-25T14:24:44Z) by lane fix-turn-hang-opus.

- Run `268374a45931` (`bin/test-check default --ns seon.cluster.turn-test`,
  17:26Z) reported that member
  `seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts`
  did not exit within 114141 ms, "thread 16220 remains live".
- At 18:05Z a JSON thread dump (`jcmd 35446 Thread.dump_to_file -format=json`) had no
  thread 16220. It lists tracked virtual threads (512, 513 and others), so the
  body had exited.
- The member row still has `:seon.test.member/completed-tx` and no
  `:seon.test.member/terminated-tx`. The same is true for runs `58ceb4160596`,
  `d44b319ca171`, `ea5b1cc68382`, `7210e84f97dc` and `a02b017966d3`. Runs
  `3fd6afb85e4d`, `6fed2f5a4cc5`, `3a717e13bfe4` and `1e1eb9612541` did record
  termination.
- `seon.test/admit-run` (test.clj:1336) says "A body not observed to exit holds its test in every scope",
  so every later request for this test is held. Two
  `bin/test-check default --test <that member>` requests at 18:00Z answered
  `executed 0 … pending (not started)`. The result names no reason and no holding run.
  `seon.test/run` returned `:seon.test/pending [<member>]` with no key saying why.

Two defects:

1. The watcher (`bounded-result` `watch-exit!` → `release!` → `settle!`, test.clj:1933)
   did not durably record the late exit. The watcher runs on a bare virtual
   thread, so a throw in `release-context!` or `settle!` has no reader. Find
   which one failed, and make the watcher's failure a stored, delivered fault.
2. A held member is reported as bare `pending`. The result should name the holding run and
   member (`::held`, test.clj:2310) so the caller can see what holds the test.

Held until this JVM is replaced (admission filters live runs by JVM start).
