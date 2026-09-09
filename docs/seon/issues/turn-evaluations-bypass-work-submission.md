---
type: issue
status: open
severity: friction
tags: [issue, runtime, sci, flow]
---

# Turn evaluations bypass work submission

Verified during the 2026-09-09 namespace move from accepted `7004818dc`:
the turn's evaluation path calls SCI directly inside the render/read-evidence
scope (`src/seon/turn.clj:4283`). The private `submit-evaluation!!` helper
has no caller (`src/seon/turn.clj:3142`). The work launcher's bounded admission
at `src/seon/flow.clj:814` therefore does not govern this turn path.

The rename preserves this behavior. Earlier agent docstrings and the Flow
skill reference incorrectly claimed every turn entered the launcher; those
claims are corrected in the rename commit.

The turn/evaluation owner must decide the execution boundary under the
current PRD, then prove the resulting admission and completion behavior on
the canonical armed fixture. Do not infer a bounded compute guarantee from
the existence of an unused submission helper.
