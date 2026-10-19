---
type: issue
status: fixed (lane runtime-fix3, item 2)
severity: defect
tags: [issue, cache, sci, runtime]
---

# A compiled function caching a derivation it is handed serves it across runtimes

Class: `seon-cache-derivations-cross-branches-through-their-deriving-code.md` (F7, R1). Found by lane review3-opus,
2026-10-20, on HEAD `06614f6a8`; evidence and forms in
[runtime-review3-opus](../../research/agent-platform/2026-10-20/runtime-review3-opus.md) (N1).

A branch replaces `seon.bootstrap/sections` with `[database text] []`. `seon.bootstrap/groups` reaches it and runs
analysed on the branch; it calls `seon.health/derived` with the derivation `#(sections % text)`. `seon.health/derived`
is closed and its closed reach does not hold `sections` (the derivation is an argument), so it stays compiled on the
branch and calls the JVM `seon.cache/lookup` with main's bare key. Same committed branch database value, cache kinds
`:seon.health/derived` and `:seon.health/reads` evicted before each order:

| Order | branch `(count (:seon.help/groups (seon.bootstrap/help-value db)))` | compiled, same value | expected |
|---|---|---|---|
| branch first | 0 | **0** | 0 / 8 |
| compiled first | **8** | 8 | 0 / 8 |

Root cause: the exact key (`seon.sci.runtime/keyed-entries`) is chosen by whether the code calling
`seon.cache/lookup` runs analysed, and a closed function's compiled status is decided by its closed reach over
`:seon.fn/calls`. A derivation handed in as an argument is code the reach does not see, so a compiled deriver keys a
branch's code under main's identity. The hybrid's rule "a callback must never cross a cache lookup" was applied to
`seon.db/content-projection` only; nothing enforces it.

Scope by census of `src` (functions holding a `seon.cache/lookup`, `lookup-within` or `projection-cache-value` whose
contract takes a function): `seon.health/derived` (every problems family through `seon.health/problems-unit`, help
groups, `::calls`, `::tasks`, `::function-files`, `::untested`, `::regressions`), `seon.embed/index` and
`similar-now` (the `request` function), `seon.schema/with-bound-predicates` (a predicate map).

Proof to write: a row of `seon.sci.eval-test/a-branch-derivation-is-keyed-by-its-runtime-in-either-order` with the
`sections` change above. Owner-side options: the deriver takes the derivation's code identity into its key (the
caller's runtime code), or the handed derivation becomes a named function the deriver calls, so the reach sees it.

## Fix (lane runtime-fix3, 2026-10-20)

The reach decides it structurally. `seon.sci.eval/handed-derivers` names every cache deriver whose contract takes a
function (ten on the loaded program, recorded exactly in `seon.sci.open-derivers-test/handed-cache-derivers-follow-their-callers`,
which goes red on a new one); `seon.sci.eval/coupling` gives each its leaders: every caller and the names its calls of
the deriver mention (`seon.sci.eval/handed-code`), and a handed caller's own leaders. `seon.sci.runtime/compiled-names`
drops a deriver from the compiled names whenever a leader is changed or reaches a change, so it runs analysed and
keys its lookup by the runtime (`seon.sci.runtime/keyed-entries`). Proof
`seon.sci.eval-test/a-derivation-handed-to-a-deriver-is-keyed-by-its-runtime-in-either-order` (the `sections` row
above): red on HEAD (branch first `[0 0]`, compiled first `[8 8]`), green with the fix.
