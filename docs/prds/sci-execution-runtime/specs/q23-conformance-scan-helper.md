---
type: prd
status: active
tags: [prd, architecture]
---

# q23 — one shared source-scan helper for the conformance tests

## Grounding preamble (mandatory)

Read the actual source of every file you touch before editing. Report:
(a) a better seam if found; (b) the owners' exact terms. Stopping early
to report is FREE. If source contradicts this spec, stop and report.

## Goal

The fs/ns-form scan idiom (`source-files`, `sanitized-ns-form`, and the
bracket-anchored require matching) is duplicated across three
conformance tests: `test/seon/diffusion_fence_test.cljs`,
`test/seon/internal_require_boundary_test.cljs`, and
`test/seon/internal_boundary_test.cljs` (its computed internal-ns
scan). Extract ONE shared test helper namespace (follow where the test
tree already keeps shared fixtures/helpers — find the existing idiom
and name it in the owners' vocabulary; if no shared-helper location
exists, `test/seon/conformance/scan.cljs` is acceptable) and rewire all
three tests to it. Pure move/dedup: each test's assertions, allowlists,
and failure messages stay byte-equivalent in behavior.

## Owned paths (touch nothing else)

The three test files + the one new helper ns.

## Gates

Focused runs of all three test namespaces, then full `bin/test-cljs`
once. rg proof: the helper fns defined exactly once under test/. No
commits, no lifecycle ops.
