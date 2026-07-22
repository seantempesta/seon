---
type: prd
status: active
tags: [prd, architecture]
---

# NS-0.5d — structural gate for the internal-require law

## Grounding preamble (mandatory)

Don't make things up: read the actual source of every file you touch and
every interface you connect to before editing. Report in your summary:
(a) a better seam if you find one; (b) the existing owners' exact terms.
**Stopping early to report is FREE.** If source contradicts this spec,
stop and report.

## Goal

The standing law "only a namespace's parent may require its `.internal`"
was closed in production by NS-0.5a/b but is proven only by one-off rg
commands — nothing pins it. Meanwhile the diffusion fence has a
checked-in conformance gate (`test/seon/diffusion_fence_test.cljs` —
read it first; it is the idiom: node-fs scan of `src/**` ns forms,
bracket-anchored matching, dated allowlist rows with a stale-row check,
steering-quality failure messages). Two changes:

1. **New conformance test** (suggested
   `test/seon/internal_require_boundary_test.cljs`, follow tree
   conventions): scan every `src/**/*.clj{,s,c}` ns form; for every
   require of a namespace whose last segment is `internal`, assert the
   requiring file's namespace is exactly the required namespace minus
   its final `.internal` segment (the parent). COMPUTE the internal
   namespace set from the scan — no literal list. Allowlist: NONE
   expected in production src today (NS-0.5a/b closed them — verify
   with your own scan first; if you find a residual violation, STOP
   AND REPORT rather than allowlisting it). Keep test-tree requires
   out of scope (tests may exercise internals; the law governs src).
2. **Fix the hand-maintained list** in
   `test/seon/internal_boundary_test.cljs` (`internal-nses`, ~line 24,
   and the pairs table in `included-ns-excludes-internal-keeps-the-public-parent`):
   derive the internal-ns set (and parent pairs) by scanning `src/`
   for `.internal` namespaces with the same fs idiom, so a new
   `.internal` ns is automatically covered by both the render-hiding
   assertions and the require gate. Preserve the existing assertions'
   semantics exactly (suffix beats config policy; parent stays
   included).

## Owned paths (touch nothing else)

- new `test/seon/internal_require_boundary_test.cljs`
- `test/seon/internal_boundary_test.cljs`

Protected: everything else. Two read-only research lanes are active —
no bin/seon lifecycle ops, no commits.

## Gates

- The two test namespaces green under focused `bin/test-cljs`
  selection; then the full suite once.
- Prove the gate bites: temporarily add a fake violating require in a
  scratch copy or assert via a unit-test fixture (do NOT commit a real
  violation) — show the failure message names the violating file, the
  internal ns, and the expected parent.
