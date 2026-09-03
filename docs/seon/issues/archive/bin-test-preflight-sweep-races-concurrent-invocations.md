---
type: issue
status: resolved
severity: blocker
tags: [issue, test, runner, tooling]
---

# `bin/test`'s preflight sweep races concurrent invocations and aborts the gate

## Problem

Six lanes each ran `bin/test <ns>` concurrently on 2026-09-02. One
aborted before any test ran: `NoSuchFileException` deleting
`tmp/test-runs/run.RY8bX2/workers/pool-6/tmp/source-test`
(`src/seon/fs.clj:144`) — its preflight sweep (`bin/test:284-311`) was
deleting a retained inactive root while another invocation's sweep was
deleting the same root; a file vanished under the walk and the walk
threw, so the whole gate exited 1 with no tally. The lane also found the
second member of the class: a new run root is created at `bin/test:315`
(`mktemp -d`) but its ownership record (`test-run.txt` pid line) is not
written until `bin/test:365`; in that window a concurrent sweep judges
the root inactive (`active?` reads the pid record, `bin/test:284`) and
may delete a root that is being populated.

Both are the pre-read law (AGENTS.md §2): the sweep acts on a judgment
("inactive", "still exists") that another process re-decides.

## Owner

`bin/test` (the sweep block and root creation order) and
`seon.fs/delete-recursively!`.

## Acceptance

1. A root is never sweepable before its ownership record exists: write
   the pid record INTO the freshly created root before anything else
   (or create-and-claim in one step), and the sweep only considers roots
   whose record is older than a short grace or whose pid is provably
   dead.
2. Two concurrent sweeps of the same root both succeed: a path already
   gone during the deletion walk is success, not an exception (the
   no-follow guarantee stays; the symlinked-sentinel regression stays).
3. One regression per claim; two `bin/test` invocations started
   together on one tree both reach their tallies.

## Resolution

Resolved 2026-09-03. `bin/test` writes the complete pid ownership record as
the first act after `mktemp`; the preflight sweep leaves a root with no
readable pid outside retention policy for a 30-second creation grace and
continues to exclude every live pid. Candidate observation also treats a root
removed by a concurrent sweep as absent.

`seon.fs/delete-recursively!` now treats `NoSuchFileException` at any recursive
entry as successful completion for that path while retaining the lexical-root,
intermediate-symlink, and `NOFOLLOW_LINKS` checks.

Recurring evidence:

- `seon.fs-test/concurrent-deletions-treat-a-vanished-path-as-success`
  schedules two deletions at the attribute-read/directory-open seam.
- `seon.test-runner-test/a-fresh-run-root-is-claimed-before-population-and-sweep`
  proves the claim precedes population and fresh unclaimed roots survive.
- `seon.test-runner-test/concurrent-bin-test-invocations-both-reach-their-tallies`
  runs two real `bin/test seon.fs-test` processes against one retained-root
  parent; each reached `3 tests / 14 assertions / 0 failures / 0 errors`.

Gate: `bin/test seon.fs-test seon.test-runner-test` — 28 tests, 240
assertions, 0 failures, 0 errors.
