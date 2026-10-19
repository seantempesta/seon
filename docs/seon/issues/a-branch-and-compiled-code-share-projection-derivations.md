---
type: issue
status: fixed (lane runtime-fix3, item 2)
severity: blocker
created: 2026-10-20
tags: [issue, sci, branch-runtime, review3-astra]
---

# A branch and compiled code share projection derivations

`seon.schema.internal/projection-cache-value` caches by the caller's key in a holder on the projection, without the executing program. `seon.sci.runtime/keyed-entries` changes only `seon.cache` calls; it cannot change this `clojure.core.cache.wrapped` cache. `seon.db/lowered-where` supplies a thunk reaching `seon.schema.datahike/lower-where`, whose branch definition can change while the held projection remains the same.

On HEAD `06614f6a8`, replace `seon.schema.datahike/lower-where` on a disposable database with a function returning `[]`. Acquire its branch runtime and main's, and pass both the same held projection with a fresh valid `:where` clause. Branch-first returns `[[] []]` for branch then main; expected `[[] clauses]`. Main-first returns `[clauses clauses]` for branch then main; expected `[[] clauses]`. The holder exists. Thus the first runtime to derive decides the other runtime's answer. This includes compiled main consuming the branch's query plan.

Red proof: `seon.runtime-review3-astra-extra-test/projection-cache-holds-the-executing-program`, candidate `8dbf87ca612fe7caf807884e51ecaa1424773758`, run `ab364de6a99e`. Both order assertions fail. Passing a held parent projection is intentional: a caller may supply another branch's immutable value; the installed runtime tests already require that behavior for a database value.

The fix belongs to the derivation's identity at its producer. A branch name, a cache flush, or an exemption from the runtime requirement does not supply the missing code dependency.

Evidence and limits: [independent review](../../research/agent-platform/2026-10-20/runtime-review3-astra.md). Reproducible probe source: [review patch](../../research/agent-platform/2026-10-20/runtime-review3-astra-probes.patch); it is evidence, not an installed change.

## Fix (lane runtime-fix3, 2026-10-20)

`seon.schema.internal/projection-cache-value` is a cache entry point like `seon.cache/lookup`: a changed runtime binds
it in `seon.sci.runtime/keyed-entries`, so a runtime's analysed caller keys its projection derivation by its code.
Proof `seon.sci.eval-test/a-projection-derivation-is-keyed-by-its-runtime-in-either-order` (Astra's `lower-where` row):
red on HEAD, green with the fix. The holder's retention outside the budget stays filed separately.
