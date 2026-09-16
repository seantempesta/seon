---
type: issue
status: resolved
severity: friction
tags: [issue, schema, render, test]
---

# Evaluation reader refuses its pulled renderer ref

On 2026-09-16, the default JVM's in-process
`seon.render.faults-test/the-opening-derives-repair-reads-through-function-refs`
recorded 2 passes, 0 failures, 1 error. `seon.eval/of-agent` refused its return
at `[0 :seon.eval/renderer-fn]`: expected an integer, got a map.

`src/seon/eval.clj:18` declares its output as `:seon.eval/entity` and its
default selector at line 26 includes `*`. The new renderer-fn field in
`resources/seon/schemas/seon.eval.edn:2` aliases `:seon.db/ref`, while pull
returns a ref map. This observation does not establish whether a newer
adoption has repaired the mismatch; it is the exact failed live boundary.

The evaluation reader/schema owner must accept the actual pulled ref shape
or return a declared normalized ref consistently. Verify on the canonical
armed fixture after adoption, including an evaluation with a renderer ref.
Do not suppress the ref or disable the output contract. The error-graph lane
left these owners unchanged; its direct error render regression passes.

## Fresh turn-fixture confirmation, 2026-09-16 03:11 UTC

An isolated development JVM at committed `9c03c1ec1` plus turn-test lane
changes reproduces the same boundary after a fresh process start, canonical
base, branch and SCI context. The real register/unregister test never reaches
the provider: its opening records `seon.instrument/contract-violated`, function
`seon.eval/of-agent`, arm `:output`, with the exact renderer-fn refusal above.
The captured occurrence has count 1 and durable turn/agent refs. This falsifies
hot-reload staleness as a sufficient explanation for this reproduction.

The schema file remains under another lane's uncommitted edit; no workaround,
contract suppression or assertion weakening was applied. The turn-test landing
note records the complete occurrence and the affected candidate run.

## Resolved, 2026-09-16

`resources/seon/schemas/seon.eval.edn` now declares the entity map's
`:seon.eval/renderer-fn` as `[:or :seon.eval/renderer-fn [:map [:db/id :int]]]`.
`seon.eval/of-agent` pulls with a default `[*]` selector, so a `:seon.db/ref`
attribute arrives as `{:db/id n}`; the declaration that admits only an integer
described a shape the reader never returns. The same map already declares
`[:seon.cluster.eval/run [:map [:db/id :int]]]`, and
`resources/seon/schemas/seon.test.edn:51` declares
`[:seon.test/run {:optional true} [:or :seon.test/run [:map [:db/id :int]]]]`
for the identical pulled-ref case. Narrowing the selector instead was rejected:
`{:seon.eval/renderer-fn [:seon.fn/sym]}` still yields a map, so the entity map
must admit the pulled shape either way, and forcing it would constrain every
caller-supplied selector. `src/seon/eval.clj` is unchanged.

Live falsification in the `default` JVM, armed, against real evaluations
carrying `:seon.eval/renderer-fn`: the pulled row validates under the adopted
declaration (`true`) and refuses under the previous bare-ref declaration
(`false`), which is the exact recorded boundary.
`seon.eval/of-agent` returned 11 rows with renderer-fn values `{:db/id 4630}`
and siblings, and the var carries its `:seon.instrument/var` wrapper.

In-process regressions on the canonical fixture, one at a time:
`seon.render.faults-test/the-opening-derives-repair-reads-through-function-refs`
recorded 13 passes, 0 failures, 0 errors (previously 2 passes, 1 error).
`seon.issue-test/issue-worker-opening-links-its-issue` recorded 0 errors,
6 passes and 2 failures asserting absent `:seon.eval/shown` on its opening
evaluations. That contract error is gone; the residual failures are the turn
and plan opening writer, not this reader, and were masked by the refusal.
