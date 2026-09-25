---
type: issue
status: open
severity: defect
created: 2026-09-25
tags: [program-graph, db, writer]
---

# The writer admits a definition digest its row does not produce

## Problem

`seon.db/transact!` accepts a program row whose stored `:seon.program/definition-digest` disagrees with the
definition the row now carries. Digest-based comparisons treat the digest as the definition's identity, so they
all read such a row as unchanged:

- acquisition's `function-digests` (src/seon/sci/eval.clj);
- `install-row!`'s `overridden-row?`;
- the lazy `install-function-from-database!`;
- test reach digests and recorded evidence.

That breaks "decide at the authority": the writer is the one owner that sees the final row, and it does not check
the identity every reader trusts.

## Evidence (2026-09-25, default pid 35446, read-only)

```clojure
(let [row (seon.test-support/program-fn-row 'seon.id/valid?)
      admitted (assoc row :seon.schema.admission/source :agent
                      :seon.fn/source "(defn valid? [length id] (s3.missing/function length id))")]
  [(#'seon.sci.eval/row-definition-digest admitted)                                 ; "c29f0e66…" (the stored digest)
   (seon.program/definition-digest (dissoc admitted :seon.program/definition-digest))]) ; "302120263…"
```

`test-support/transacted!` wrote that row, and the refusal never came. Three `seon.sci.eval-test` members then
failed several assertions away from the cause: `database-provenance…`, `non-evaluable-agent-source…` and
`base-context-bindings…` (census d class N4, `docs/research/agent-platform/red-baseline-census-2026-09-25d.md`).
The lane fix-harness-fallout-opus converted the tests to analyzed rows (d8b80b1c3). The writer still admits the
mismatch.

## Wanted

Final-report validation recomputes `program/definition-digest` for each changed program row (its resolver context
included). A mismatch refuses by name, naming the identity, the stored digest and the produced one. A row that
omits the digest either gets it from the writer or refuses; it never keeps a stale value. One class regression:
a transaction that changes `:seon.fn/source` but keeps the old digest is refused.

## Owner

src/seon/db.clj write admission (`write-entity-error` / the final-report validator), or `seon.fn/reconcile-tx` if
program rows must enter only through it. Root routes it.
