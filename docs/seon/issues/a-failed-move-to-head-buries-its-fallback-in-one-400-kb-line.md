---
type: issue
status: fixed-pending-verification
created: 2026-09-26
severity: friction
owner: script/seon/operator.clj (move-to-head!, -main)
tags: [issue, operator, start-head, visibility]
---

# A failed move to HEAD buries its fallback in one 400 KB line

## Evidence

From 65bf078ec to b28fcf408, three `bin/seon start --head` runs did not reach
readiness. Each fell back to the previous archive (`move-to-head!`,
script/seon/operator.clj:1319-1370). Root missed this twice.
`tmp/orchestrator/start-head-1300.txt` shows why:

- It has 5 lines. Lines 1-4 are two pairs of `REPL …` and `DEPENDENCY-CLASSES …`
  lines, one pair for the failed HEAD child and one for the resumed previous
  child. They are the same shape a successful start prints.
- Line 5 is 410,737 bytes of one `prn` of the whole diagnostic map. The
  headline ("The JVM from <sha> did not reach readiness … the previous program
  … runs again") starts at byte 1195. The rest of the line is the failed
  child's instance, cause chain and phases.
- `-main` already exits 1 whenever the result carries `:seon.error/message`
  (operator.clj:1493). A pipe through `grep`, `tail` or `cut` hides that exit
  code, and it hides the headline because the headline shares a line with
  400 KB of data.

## Wanted

- A failed move prints a single short line on stderr before any data. The line
  names the failed sha, the missing layers, the first cause message, and
  whether the previous program runs again. For example:
  `START --head FAILED: 2a993fb08 did not reach readiness (missing …); running previous <sha>`.
- The whole diagnostic goes to a file named on that line
  (`data/clusters/default/logs/…`), or it is printed after the headline.
  It is not the only line.
- Keep exit 1, and give a failed move that resumed the old program its own
  code, distinct from a failure that left nothing running. The existing code 3
  is `:seon.operator/fallback-from`, which a failed `move-to-head!` does not set.

## Resolution (inv-boot-operator-opus, 2026-09-26)

`-main` prints one stderr line first for every command (`headline`):
`SEON start --head FAILED (exit 4, previous program runs): <message> CAUSE:
<root cause> DIAGNOSTIC: <file>`. The cause is the failed child's root cause
(`Throwable->map` `:cause`), beneath the launch form's panic. The whole
diagnostic goes to `data/clusters/<name>/logs/operator-<command>-<stamp>.edn`,
never to the terminal. The launch no longer prints `REPL`/`DEPENDENCY-CLASSES`/
`HEAP-DUMPS` lines ahead of the outcome; they are data in the result, and a
success line names the class-cache status. Exit codes: 0 ok, 3 nuke fell back
to an older program, 4 failed and the previous program runs again, 5 failed
and no JVM of this root runs (observed with `selected-processes`), 1 other.
Replayed against the recorded failures 1300, 2000, 2130 and 1435: each
headline names its real cause. Regression: `script/seon/operator_outcome_test.clj`.
