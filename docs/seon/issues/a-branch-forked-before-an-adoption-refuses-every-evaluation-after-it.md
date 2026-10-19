---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, sci, acquisition, branch, adoption, dogfood]
---

# A branch forked before an adoption refuses every evaluation after it

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default, 2026-09-26)

1. `branch create dogfood-repl-opus-1` off default while it ran archive fbd0897df
   (pid 25654). Ordinary SCI runs on it worked (`(help)`, `(dir my.test)`).
2. Root replaced the JVM with archive 33ea69298 (pid 30357). I changed nothing on the branch.
3. Every later run on the branch refused, whatever the form. `(doc help)` answered:

   ```
   seon.sci.eval/install-row! refused  at []: expected the declared requirement
   (:seon.sci.eval/interpretable-definition), got nil nil. Fix: Cannot interpret
   seon.db/arity-mismatches-with: Host-bound declaration seon.db/arity-mismatches-with must
   change through the loaded source files. …
   ```

   The branch-error window listed `seon.render.transcript/render-runtime-html` (host-bound, 12
   occurrences) and `seon.loop-proof-test/assert-changed-response!` ("Unable to resolve
   symbol: bytes-evidence", 53 occurrences).
4. A fresh branch forked after the adoption (`dogfood-repl-opus-2`) answered `(doc help)`
   normally.
5. It happened again: `dogfood-repl-opus-2` was forked on 33ea69298. After root moved default to
   a1b01c92a (pid 32988), `(in-ns 'my.issue) (str *ns*)` refused with `install-row!` for
   `seon.render.value/throwable-safe?` (host-bound) and `seon.test-support/program-fn-row`
   ("Unable to resolve symbol: source-manifest", 64 occurrences). The renderer then failed on
   that refusal and nested itself ([separate issue](a-renderer-refusal-renders-through-the-same-renderer-until-the-depth-bound.md)).

Default moves several times an hour, so an agent's branch rarely survives one cycle. The branch
has no rebase and no way to adopt the new program. The only recovery is to retire the branch,
which loses the agent's work.

## Which model is wrong

Acquisition treats **every row where the branch differs from the JVM's loaded program** as an
override by the branch (`seon.sci.eval/install-row!`, `src/seon/sci/eval.clj:1570-1606`,
`overridden-row?` against `::loaded-database`). After an adoption, that difference is mostly
the *loaded program moving ahead* of the branch's fork point, not anything the branch authored.
The stale rows are treated as overrides of host-bound code and refused, and the refusal covers
the whole acquisition, not only callers of the stale rows.

The branch's own changes are the difference between the branch and its **fork point** (Datahike
lineage: the branch's parent commit, `versioning.cljc`). They are not the difference between
the branch and whatever program the JVM loads now. Rows that the branch did not change and that
the loaded program has moved past mean the branch is **behind**. The branch should run the
loaded definitions for them, or offer an explicit rebase of its unchanged rows onto the new
program. It should never interpret stale core rows.

## Expected

- A branch whose only differences from the loaded program come from the base moving evaluates
  ordinary forms. Rows it has not changed resolve by name to their loaded digest; host-bound rows run compiled.
- A row the branch did change that conflicts with a newer loaded row is refused **by name, when
  called**, as the per-declaration law requires. It never refuses unrelated forms like
  `(doc help)`.
- The refusal names the cause ("the cluster adopted program P after this branch forked at P0")
  and the way forward.

## Regression

On a branch forked at program P0, adopt P1, which changes one host-bound declaration. `(+ 1 2)`
on the branch answers `3`, and a form that calls the changed declaration uses P1's compiled Var.
