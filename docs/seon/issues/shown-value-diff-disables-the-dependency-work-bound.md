---
type: issue
status: open
severity: friction
tags: [issue, database, runtime, render, wave/verification-audit]
---

# Keep the shown-value diff's work bounded

## Problem and evidence

Commit `1f18b99fc` adds `src/seon/db.clj:2214–2220` (`value-changes`)
with `:vec-timeout Long/MAX_VALUE`. The public value-diff contract at
`src/seon/db.clj:2255–2264` has no execution-bound input, and
`src/seon/turn.clj:2113–2125` calls the diff after preview evaluation, in
the host system-turn path.

The dependency already supplies a bounded implementation:
`reference-code/editscript/src/editscript/util/common.cljc:119–128` defaults
the timeout to 1000 ms and passes it to the actual edit loop; line 85 compares
elapsed time against that timeout. The quick algorithm at
`reference-code/editscript/src/editscript/diff/quick.cljc:40–46` handles a
timeout by replacing the whole value. Seon's override effectively disables
that work bound. The SCI interpreted-function deadline does not, by itself,
interrupt this native loop after the preview has returned.

This is source-verified loss of a bound, not a claimed observed hang. It is
separate from the duplicate debug diff in
[the companion issue](debug-reread-summary-duplicates-shown-value-and-diff-owners.md).

## Owner and acceptance

Remove the effectively unlimited override. Use the dependency's existing
bounded replacement path or hand a declared work bound at the one diff
admission seam, with an honest indication if exact diff work was cut short.
Do not add a second diff worker/retry mechanism.

Keep the existing round-trip property and add one bounded canonical
system-turn regression exercising a large sequence change. It must either
finish under its declared bound or report that boundary explicitly, without
changing the evaluation deadline to conceal diff work. Estimated scope:
1–15 production lines plus the meaningful regression.
