---
type: issue
status: open
severity: defect
created: 2026-10-20
tags: [issue, cache, sci, branch-runtime]
---

# An open caller handing a derivation leaves its deriver compiled

Class: [a compiled function caching a handed derivation](a-compiled-function-caching-a-handed-derivation-serves-it-across-runtimes.md)
(N1, marked fixed). Found by lane review4-opus on HEAD `342e14357`, 2026-10-20; evidence in
[runtime-review4-opus](../../research/agent-platform/2026-10-20/runtime-review4-opus.md) (B1).

A branch replaces `seon.problems/missing-models` with `[db] [{:seon.config.ai/model "BRANCH"}]` (F1's change).
`seon.health/problems-unit` hands each family's derivation, read from the open value `seon.problems/families`, to
`seon.health/derived` (`(fn [family derivation] (derived database family derivation))`). On the branch
`problems-unit` runs analysed (it is open) and `derived` stays compiled. Same committed branch database value,
kinds `:seon.health/derived`, `:seon.health/reads` and `:seon.health/problems` evicted before each order;
`:seon.problems/missing-models` of `(#'seon.health/problems-unit db 60000 nil)`:

| Order | branch | compiled, same value | expected |
|---|---|---|---|
| branch first | BRANCH | **BRANCH** | BRANCH / nil |
| compiled first | **nil** | nil | BRANCH / nil |

Root cause: `seon.sci.runtime/compiled-names` drops a handed deriver only when one of its `::leaders` is changed or
is in the closed reach of a change (`reached` is built from `::reached-by`, which holds closed names only). Here the
leader is `seon.health/problems-unit`, which is open: its reach is unknown, so it never counts as reaching the change,
and `derived` keeps keying the branch's derivation under main's bare key. The fix of the N1 issue covered closed
leaders only, while its own scope list names "every problems family through `seon.health/problems-unit`".

Confirmation: candidate `76bcf8c5c` (probe commit `6f04892fe` plus one edit of `compiled-names`: a leader that is a
`seon.*` name outside `::closed` counts as reaching) answers BRANCH / nil in both orders, and the committed row
`seon.sci.eval-test/a-derivation-handed-to-a-deriver-is-keyed-by-its-runtime-in-either-order` and
`seon.sci.open-derivers-test` stay green (check run `d0331b6d2a1c`, 6 tests, 13 pass). That edit is a probe, not the
fix: the open-macro rule in `seon.sci.eval/bind-analysed!` already says "an open name's reach is unknown: any change
may be what it reads", and the same rule for an open leader makes `seon.health/derived` run analysed on every changed
runtime (cost to measure on the cost table).

Red proof (to install with the fix): probe `seon.runtime-review4-probe-test/h1-problems-family-through-derived`
(run `f2e341cc7fe2` red on HEAD), as a row beside the N1 row in `seon.sci.eval-test`.

Scope: as N1's, compiled code reading a branch's database value (root's health of a branch, a branch agent's own
`seon.health/report`); main's and a branch's own database values never share these keys.
