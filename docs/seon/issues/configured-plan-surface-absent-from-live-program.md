---
type: issue
status: open
tags: [issue, plan, rendering]
severity: blocker
---

# Configured plan surface is absent from the live database program

## Evidence

After a clean exact-head restart at `c977e774`, the root Datastar feed renders
the configured plan block as an error card: `The selected function is absent
from the current database program.` The selected HTML symbol is the newly ruled
`my.plan/plan-surface`, which exists in compiled source and passed focused and
complete CLJS gates.

The same feed successfully delivered its full Datastar frame, so this is not a
browser or transport failure. Either boot indexing did not publish the new
public function row, configuration activation selected a symbol outside the
admitted program, or live program lookup is reading a stale projection.

## Acceptance

- The immutable live database contains one admitted function row for
  `my.plan/plan-surface` with its current source fingerprint.
- The configured plan block resolves that exact row after a clean restart.
- The root page and server-side feed render the plan surface without an error
  card, with no compatibility registry or hard-coded render branch.
- A missing selected function remains a visible structured error.
