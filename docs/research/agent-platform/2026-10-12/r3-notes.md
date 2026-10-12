---
title: "R3 notes: a method body has no row in either index"
type: research
status: stopped for an owner decision (lane r3, Opus); options A (recommended), B, C
basis: HEAD 50ca41030; probes on default as agents r3-probe-a, r3-probe-b and root (jvm, read-only)
---

# R3 notes: the code contradicts "as the file index does" (lane r3, 2026-10-12)

R3 (hybrid-test-results §6, task `seon.sci.eval/a-defmethod-is-a-declaration-on-a-branch`) says the branch write path
should declare `defmulti`, `defmethod` and protocol extension forms "as the file index does", so that a method body is
part of its multimethod's leaf. The running system shows the file index does not do that either. A method has no model
to copy, so the method model is a decision for root.

## What the running system shows (default, HEAD 50ca41030, evidence below)

1. **Branch, the `defmulti`.** `seon.turn.batch/write-entity` keeps `area` private with `:not-a-function`: a
   `clojure.lang.MultiFn` is not `fn?`. Its request derives (16 ms) but has no facts stage, and
   `(seon.db/pull db '[...] [:seon.fn/sym 'r3a.probe/area])` gives nil. The file index does declare it
   (`seon.fn/function-definition?` names `clojure.core/defmulti`).
2. **Branch, the `defmethod`.** It binds no Var root, so `seon.turn.batch/derive-form` gets no `:bind` write for it
   (derive 0.0 ms, no facts). The deftest's row calls `[= deftest is]` and not `area`, so the test's reach does not
   hold the multimethod at all.
3. **Files, the digest.** `seon.fn/program-entities` over a disposable root holding `sample/multi.clj`
   (`defmulti area :shape`, `defmethod area :sq ... (* s s)`, `deftest t-multi`), then the same file with
   `(* 2 s)`: `area`'s row is `{:seon.fn/source "(defmulti area :shape)", :seon.fn/calls #{area * defmulti defmethod}}`,
   and **both `area`'s and `t-multi`'s definition digests are unchanged**. `seon.fn.analyzer/attributed-usages` adds the
   method body's *call edges* to the multimethod's row, but the body's text is in no row and no digest. This settles
   the question §8 left open ("whether file-declared defmethod bodies change the multimethod's leaf"): they do not.
   R3's file proof case is red on HEAD for files too, not only on branches.
4. **One request (F5), the same root cause.** `ns`, `defmulti`, `defmethod` and `deftest` in one request on a fresh
   agent leave `t-multi` errored, with error `500ddeee…` (`seon.sci.eval/acquisition-refusal`) on row
   `[:seon.test/sym r3b.probe/t-multi]`, cause "Unable to resolve symbol: area". The acquired program holds a test
   that calls a name no row declares. This is filed as
   `seon.sci.eval/a-multimethod-and-its-test-in-one-request-acquire-on-a-branch`. Acquisition is not the defect: it is
   the row producer in (1) and (2). Declaring the `defmulti` alone would turn the error into a red ("No method in
   multimethod"), because acquisition also needs the method's source to install it. Every later request on that
   branch reruns the errored test (test-step 490 ms).

So a method body has to become stored source that is digested and installable. Neither path stores it today.

## Options (simplest viable first)

**A. A method is its own declaration (recommended).** Add a program family keyed by multimethod and dispatch value:
a tuple identity `:seon.fn.method/id` over `:seon.fn.method/multi` (the multimethod's symbol, a value) and
`:seon.fn.method/dispatch` (clj-kondo's `:dispatch-val-str`). The family carries the method's own `:seon.fn/source`,
`:seon.fn/ns`, `:seon.fn/calls` and `:seon.fn/references`, plus `:seon.program/definition-digest` through
`seon.db/definition-digest-of`.
- File index: `seon.fn.analyzer/attributed-usages` already finds each method's span. `seon.fn/analysis-entities-by-file`
  emits one row per method and stops folding method calls into the multimethod's row.
- Branch: SCI's `defmethod` expands to `(. multi addMethod dispatch fn)`, which `seon.turn.batch/derive-form` never
  sees. It needs a write op for the expansion (`:seon.batch/op :method`, observed where the fork records `:bind`).
  Then `write-entity` joins it to the analyzed method row the same way it joins a `defn`. The `fn?` check in
  `write-entity` also admits a `MultiFn`.
- Reach: `seon.test.runner/reach-memberships` adds the edge from a multimethod to its methods (the reverse of
  `:seon.fn.method/multi`, one index lookup). The methods' digests then enter the closure digest.
- Acquisition installs a method after its multimethod and its namespace. Write-back splices a method at its own span,
  so a method in another file, like the `test/fixtures/call_graph_fidelity/implementation.txt` case, stays its own
  file's.
- Guarantee: per-method digests, so a change to one method reruns exactly the tests that reach its multimethod.
  Cost: a schema family, plus five consumers (file index, branch derive, reach, acquisition, write-back and merge
  partition). About 2 to 3 hours of Opus work in four slices, each with its proof. The SCI write op touches the fork.
- Sacrifice: a new entity kind, and the hybrid's "as the file index does" no longer holds, because both indexes change.

**B. Methods are components of the multimethod's row.** The `defmulti` row gets `:seon.fn/methods`
`[{:seon.fn.method/dispatch … :seon.fn/source …}]`, so `canonical-row` digests them with no change to reach.
- Branch: a `defmethod` form writes a revision of the multimethod's row.
- Guarantee: the same rerun set as A, at lower cost in the runner (none).
- Cost: about 1 to 2 hours.
- Sacrifice: one row then spans several forms, and across files for a method defined in another namespace.
  Write-back's one-declaration, one-span rule breaks. Two branches that add methods conflict on one entity. A component
  is validated as the whole owning value on every method write.

**C. Declare only the `defmulti` and widen.** Make `write-entity` admit a `MultiFn`. Treat a `defmethod` form on a
branch as a change to its multimethod's row by adding a digest of the method source to the row (no source stored).
- Cost: under an hour.
- Sacrifice: acquisition still cannot install the method, so F5 becomes a red ("No method"), not a pass. A digest with
  no source is evidence nothing can re-derive. Rejected unless the owner wants only the key fixed now.

Protocol extension forms (`extend-protocol`, `extend-type`, and the inline methods of `defrecord`) have the same shape
as A, keyed by protocol method and type. They are host-bound today: `defprotocol`'s names are class-bound and
`seon.fn/function-definition?` excludes them. They can follow as A's fifth slice once A is chosen.

## Red proofs (design-independent; land with the first slice, since a red test cannot pass `bin/lane commit`)

- `seon.test.reach-test/a-digest-follows-its-closures-content-and-nothing-else`, file case: write the three-form file
  above to a temporary root, transact `seon.fn/program-entities` through `seon.fn/reconcile-tx`, take
  `runner/reach-digests` for `t-multi`, change the method body to `(* 2 s)`, re-transact, and assert the digest
  differs. Red on HEAD per (3).
- `seon.test.redefined-callee-test/a-test-reruns-when-a-method-of-its-multimethod-changes`, in the file r1r2 holds:
  `defmulti`, `defmethod` and `deftest` each in its own request on one branch, then the method `(* 3 s)`, then
  `my.test/check` `{:seon.test/identities #{t-multi}}`: `:seon.test/executed-count` 1, `:passed?` false. Red on HEAD per
  (1) and (2), as in narrow-check row 12.

## Probe forms

- Branch, separate requests: agent `r3-probe-a`, namespace `r3a.probe`.
- One request: agent `r3-probe-b`, namespace `r3b.probe`, branch `:agent-2559040ff3c5`.
- Files: root JVM, `seon.fn/program-entities` over a disposable root (deleted after), 26 ms.
