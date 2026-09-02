---
type: issue
status: open
severity: blocker
tags: [issue, test, runner, tooling, pre-read]
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
