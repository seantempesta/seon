---
title: "doc-reach: a docstring-only change reruns no test"
type: research
status: active
created: 2026-10-08
lane: doc-reach
---

# doc-reach: a docstring-only change reruns no test

Task `seon.turn.batch/a-docstring-defn-allocates-half-a-gigabyte` (and the older
`seon.program/a-docstring-edit-changes-the-definition-digest`, whose ruling this implements).
Root's ruling (2026-10-08): tests cannot observe a docstring, so a docstring-only change must not rerun them.

## What exists

- `seon.program/definition-digest` hashes the canonical row (minus storage provenance and derived
  graph facts) and the used resolver context. `:seon.fn/doc` and the docstring inside `:seon.fn/source`
  are in it. It is identity for merge, write-back, the writer's digest check, branch evidence and the
  test overlay; this lane does not change it.
- Two places decide what a change reruns:
  1. Selection: `seon.test/changes` names the declarations whose definition digests differ from the
     published commit the test world starts from (`digest-differences`); the pass's test step
     (`seon.turn/run-reaching`) runs the tests reaching them. `seon.test/changed-definition-symbols`
     does the same for incremental requests.
  2. Reuse: a selected test reuses its green record when its reach digest is unchanged. The reach
     digest's leaf is each reached declaration's definition digest (`seon.test.runner/reach-node`).
- Branch evidence (`seon.cluster.branch-evidence/file-evidence`) compares definition digests twice: file
  against branch (`::differing`) and branch against head (`::changed`, which demands tests).

## Design: a behavior digest, used only by selection and reuse

`seon.program/behavior-digest` is `definition-digest` of the row without its documentation:
`:seon.fn/doc` and `:seon.fn/doc-order` removed, and the docstring literal removed from
`:seon.fn/source` (rewrite-clj: the def form's third non-whitespace child, only when it reads as exactly
the row's `:seon.fn/doc` and a form follows it; anything else leaves the source whole, so the
comparison stays conservative). A row with no `:seon.fn/doc` has behavior digest equal to its
definition digest, by construction. It is computed, never stored: no schema change, no republish.

`seon.db/behavior-digest` derives it from a stored declaration with the writer's own row and resolver
context (`declaration-value`, `definition-digest-of`'s inputs) and caches it in `seon.cache` under the
definition digest, which determines every input (content-keyed, never invalidated).

Consumers:
- `seon.test.runner/reached-graph`: the leaf is the behavior digest for documented declarations, the
  stored definition digest otherwise (presence read from `:seon.fn/doc`'s datoms, cached per revision
  like the other reach attributes). Reach digests change once, at adoption of this commit.
- `seon.test/changes` and `changed-definition-symbols`: an identity held on both sides whose behavior
  digests are equal is not a change for selection. The overlay (`program-overlay-tx`) still carries it.
- Branch evidence: `::differing` keeps the definition digest (the file must equal the branch exactly);
  `::changed` — the declarations that demand reaching tests — compares behavior digests, so a
  docstring-only change demands no rerun and needs no reaching test.

Cost: one row read, one rewrite-clj parse (about 0.15–0.25 ms) and one hash per documented declaration
per definition digest, per JVM. Default holds 3,558 documented functions of 5,761: a cold reach over all
of them would cost about 1 s once; after that only changed declarations miss. Selection computes it only
for the identities whose definition digests differ.

What disappears: the docstring-only rerun (baseline below). Simplest alternative considered: storing
`:seon.program/behavior-digest` beside the definition digest (readers read an attribute; the writer
checks it), at the cost of a schema change in `seon.program.edn` (held by another lane), four producers
and a republish. Computing it keeps the change in the read path and can be promoted to a stored fact
later without changing its meaning.

## Baseline (default, agent `doc-reach-probe`, before this change)

A docstring-only `defn` of `seon.task/step-summary` (second edit on the branch): reply 36 ms,
`:seon.repl/alloc-mb` 52.7, 11,438 calls; JVM thread allocation over the pass 2,744 MB; the step reran
the two reaching tests (`seon.render.ns-test/task-and-schema-entities-render-as-graph-nodes` red, a
pre-existing failure).

## Landed (dc87db3f3) and what is not yet measured

- Throwaway check of HEAD plus the slice: `my.test-check-test`, `seon.test.runner-test` and
  `seon.cluster.branch-evidence-test` (long, included): 17 executed, 144 pass, 0 fail, 0 error.
  `my.test-check-test/a-docstring-only-defn-reruns-no-test-and-a-body-change-reruns-its-reaching-tests`
  drives `seon.mcp/call`: a docstring-only `defn` of `seon.task/steps-call` answers no test step; the same
  form with a `nil` before its body runs its reaching tests.
- REPL probes on default (scratch namespace, JVM mode): recomputing the canonical row reproduced 400/400
  sampled stored definition digests; the behavior digest equals the stored digest for every undocumented
  sample (166/166) and differs for every documented one (234/234); the docstring literal is removed from
  3,555 of 3,558 documented sources (the three left carry `:doc` in metadata and stay whole). About 0.8 ms
  per declaration uncached.
- Not measured: the after numbers (`:seon.repl/alloc-mb`, JVM thread allocation) on default, which runs
  the previous commit until root's restart; and the cold reach cost once per JVM (estimated at most
  3,558 × 0.8 ms ≈ 2.8 s if a request reaches every documented declaration, then cached). If the cold
  cost shows in the first test request after boot, store the digest beside the definition digest
  (option 2 above).
