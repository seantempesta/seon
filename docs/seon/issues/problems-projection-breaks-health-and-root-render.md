---
type: issue
status: open
severity: blocker
tags: [issue, errors, render, observability, live-drive]
---

# Keep committed error facts valid in the problems projection

## Problem

`seon.problems/problems` violates its output contract on the freshly reset
default cluster. The same invalid value breaks the MCP health report and makes
the root AI/HTML walk begin with an unavailable renderer.

## Evidence

On 2026-08-06, `mcp__seon__runtime_status` returned only:

```text
seon.problems/problems violated its contract (invalid-output)
```

The problem path points into
`:seon.problems/error-signatures -> :seon.error/fact -> :seon.error/run`.
Both exact root context captures begin with
`:seon.instrument/contract-violated` followed by `Renderer unavailable.` The
HTTP root namespace page likewise contains a top-level
`seon-render-unavailable` unit.

The current projection builds each signature from `(pull ?error [*])`, removes
only `:db/id`, and promises the result as `:seon.error/fact`
(`src/seon/problems.clj`, `error-signatures`). A real maintenance error fact
is enough to falsify that promise.

## Owner

`seon.problems/error-signatures` and the `:seon.error/fact` projection
boundary. The health consumer and renderers should not grow local coercions.

## Acceptance

- Every committed error fact admitted by the database can participate in
  `seon.problems/problems` without a contract violation.
- `runtime_status` returns the bounded cluster health map when errors exist.
- The same database value renders a valid problems block in AI and HTML; no
  `Renderer unavailable` placeholder replaces it.
