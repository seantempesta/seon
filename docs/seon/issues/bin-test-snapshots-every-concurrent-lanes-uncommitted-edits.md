---
type: defect
status: open
severity: friction
tags: [testing, runner, lanes, class/shared-tree]
---

# `bin/test` snapshots every concurrent lane's uncommitted edits

`bin/test` copies the working checkout into its isolated root. With several
lanes editing the shared tree at once, every lane's gate runs against every
other lane's half-finished edits: on 2026-09-08 a namespace measured
17/17 red in the shared tree while another lane held a mid-edit arity in
`src/seon/cluster/wake.clj` that refused static analysis, and two lanes
reported they could only gate in throwaway worktrees at HEAD.

## What it should do

The gate's subject is "HEAD plus MY changes". Options, simplest first:

1. `bin/test --paths <files…>`: snapshot HEAD, then overlay only the named
   working-tree paths (the lane's owned paths) — the same path list the lane
   commits with. Recommended; one flag, no new mechanism.
2. `bin/test --commit <sha>`: snapshot a commit only (a lane commits its
   slice first, then gates it).
3. Status quo, documented: lanes gate in a worktree with `reference-code`
   linked (what two lanes did by hand today).

Either way the runner should PRINT which working-tree files differed from
HEAD in its snapshot, so a foreign edit in the gate is visible, never silent.

## 2026-09-08 (`test-harness`) — the visibility half landed

Option 3's honesty requirement — "the runner should PRINT which working-tree
files differed from HEAD in its snapshot, so a foreign edit in the gate is
visible, never silent" — is in `bin/test`, before the first JVM starts:

```text
bin/test: SNAPSHOT IS THE WORKING TREE, not 44416779a…: 5 first-party path(s)
          differ from HEAD in this run:
bin/test:    M bin/test
bin/test:    M src/seon/cluster/agent.clj
bin/test:    M src/seon/error.clj
bin/test:    M test/seon/cluster/loop_test.clj
bin/test:    M test/seon/cluster/work_test.clj
bin/test: a red in a path you do not own may belong to another lane
```

That is the same disease as every other check this project has had to fix: the
gate reported a verdict about a tree it never described. It is now described.

**Still open: the SELECTION half** — options 1 (`--paths`) and 2 (`--commit`).
`test-harness` did not build either: it is a real change to what the gate's
subject IS, three lanes held live clusters and in-flight edits at the time, and
the owner design gate applies. The recommendation stands as written, and this
lane's own experience is a data point for it: it lost roughly forty minutes to
a mid-edit `src/seon/render.clj` that broke the dependency-cache build, and
then gated every slice from a throwaway worktree with `reference-code`
symlinked in — option 3, by hand, exactly as the note predicts.
