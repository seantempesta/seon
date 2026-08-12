---
type: issue
status: resolved
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

## Resolution

Resolved by `31044d4ac`. The `:seon.error/fact` contract was correct:
`seon.error/normalize` emits run and agent lookup refs, and the error renderers
use those identities. The projection was wrong because Datahike wildcard pull
turns ordinary refs into `{:db/id ...}` maps
(`reference-code/datahike/src/datahike/pull_api.cljc`, pinned at
`10540578248e`). `error-signatures` now requests
the run and agent identity attributes and restores the transaction-shaped
lookup refs. A ref whose target lacks the expected identity falls back to its
eid, which remains an admitted `:seon.db/ref` rather than making the whole
projection partial.

This is a failure class, not a one-off error-fact defect: every consumer of a
wildcard `[*]` pull that promises a transaction-shaped entity contract can
silently receive ref maps instead. The follow-up sweep should inspect all
`(pull ... [*])`, `db/pull '[*]`, and wildcard entity consumers, then prove
that each either accepts pulled form, restores transaction form generically,
or reconstructs identity-bearing lookup refs where downstream semantics need
the identity.

Recurring proof is `every-committed-error-fact-shape-is-projectable` in
`test/seon/problems_test.clj`. Every one of its 60 trials transacts all four
run/agent attribution combinations while generating the remaining optional
error evidence. Focused verification passed 17 tests / 57 assertions / 0
failures / 0 errors.

Live proof used only the isolated operator root
`tmp/problems-projection-lane`. With five committed error signatures,
instrumented `seon.cluster/readiness` returned a bounded health map whose
`:seon.problems/problems` value validated. The same immutable database value
produced valid AI text and HTML Hiccup, and the problems block contained no
unavailable placeholder. A cold scratch restart served `/` as HTTP 200 and no
longer contained `seon.problems/problems violated its contract`.

Ugly output remains outside this owner: that root response still carried five
unrelated `seon-render-unavailable` units, and the original contract failure's
prepl envelope expanded into a very large nested print tree instead of a
concise explanation.
