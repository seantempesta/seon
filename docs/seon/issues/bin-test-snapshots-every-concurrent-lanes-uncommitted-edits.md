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
