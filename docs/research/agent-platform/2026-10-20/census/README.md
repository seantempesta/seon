---
title: "Seon census: the keep/delete inventory for the Jolt architecture"
type: research
status: final
created: 2026-10-20
---

# Seon census (2026-10-20)

This census was requested by the Jolt session and approved by the owner. Owner guidance: "port the best ideas and not
necessarily the exact code as we want to adapt it and keep things simple." So the headline is a list of ideas, each
with the tests that prove it. The per-function data is the evidence behind it.

It took three passes. All of them are evidence only: no code or tests were changed.

| Pass | Commit | Files | What it holds |
|---|---|---|---|
| Static | bc1a20635 | `static.md`, `ideas.md`, `functions.edn`, `tests.edn`, `namespaces.edn` | Per function: contract status, callers, reach and run-time name lookups. Per namespace: whether the Jolt architecture replaces it, its idea is carried over, or it is unclear. Plus the 20 ranked ideas. |
| Generators | 14c50ddff | `gen.md`, `gen.edn` | Per contracted function: whether its schemas compile and generate, and whether `mg/check`-style sampling holds |
| Idea proofs | 9952f2c4c | `tests-run.md`, `tests-run.edn` | The 228 deftests the ideas cite, run in check JVMs and classified |

## Read first

1. **`ideas.md`: 20 ideas, ranked by their effect on turns-to-green.** Each gives its guarantee, its proving deftests,
   its simplest Jolt form and where today's code is more complex than the idea. The top six:
   1. generated task context;
   2. proofs that run by themselves;
   3. orientation text checked against the program;
   4. one data reply with its cost;
   5. refusals that say what to do;
   6. the value printer.
2. **`tests-run.md`: the spec's health.**
   - 210 of 228 cited tests are meaningful (green, with at least one assertion).
   - Every idea has at least 3 meaningful proofs.
   - **Red twice:** 12 tests. Six cover ideas #1 and #3 (`seon.context-test`, `seon.help-test`, a test-step verdict test); the others are 2 MCP-entrance tests, 2 reader tests, a cost overrun and the config document.
   - **Timed out:** 2. One is `seon.cluster.db-flow-test/a-filed-tasks-design-prove-implement-chain-merges-with-no-hand-act`: the designed chain stalls rather than finishing.
   - **Flaky:** 4, all timing bounds under load.
   - **Weakest:** idea #16 (estimate first), with 3 of 6 proofs meaningful.

## Totals

- **Functions:** 5,555 (104k definition lines) in 156 namespaces.
- **Verdict lines:** 54% in `port` namespaces, 37% `replaced`, 9% `unclear`. `port` means the *idea* is needed. NEEDS.md puts the Jolt version's real need near 11% of today's lines.
- **Contracts:** 3,068 complete, 830 weak, 1,092 missing (1,030 of them private), 2 malformed.
- **Generators:** 3,377 of 3,640 contracted functions generate. Of the 262 that don't, 235 fail only on a `[:fn pred]` with no `:gen`.
  - The common leaves are `ifn?` (69), `bytes?` (35), `malli.core/schema?` (30), `fn?` (18) and `volatile?` (16).
  - Named schemas blamed most: `:seon.heap/on-crossing`, `:seon.schema/compiled-validator` and the `:seon.embed/*` byte and matrix schemas.
- **Contracts that don't hold** (855 pure functions sampled, 10 samples each):
  - 24 return values their own contract refuses;
  - 18 pass a refused value to a callee;
  - 108 throw a non-contract exception on input their contract admits.
- **Run-time name lookups:** 131 functions, 88 of them in `port` namespaces.
- **Dead code:** 31 functions with no callers, no tests and no entrant role. Check `->Rec` and `map->Rec` before deleting.
- **Stored evidence:** default holds results for only 43 of 3,471 tests. Reach facts are not stored, so reach was computed statically from `:seon.fn/calls`.

## What this means for the port

1. **Carry each idea's tests as its spec, not its code.** The 210 meaningful proofs are the acceptance tests for the
   Jolt form. Fix or retire the 12 reds before an idea is ported, starting with ideas #1 and #3.
2. **Every named schema gets a generator where it is declared.** That removes most non-generating contracts: three
   leaves (`ifn?`, `bytes?`, `schema?`) cover 119 of 262. Given the census gate, a `[:fn pred]` without `:gen` should
   not enter `next/`.
3. **A contract that fails sampling marks a defect, not a weak test.** The 24 functions that violate their own output
   contracts, and the 108 that throw on admitted input, are where the ported idea needs a tighter schema or a different
   body.
4. **Most of today's complexity has one cause**: agents share one JVM. SCI reachability, content-keyed caches,
   re-rooting on reload and per-thread attribution all exist for that. The process-per-agent architecture removes the
   cause, so their guards, lookups and caches are not carried over (`static.md` lists them per namespace).
5. **The 131 run-time lookups** fail the Jolt runner's new lint (a non-literal resolve must declare its target). Each
   one is a place where the idea needs a direct call or a passed value.
6. **The `unclear` namespaces wait on owner decisions:** OD1 (schema admission), OD2 (render pairs) and OD4–OD8, as listed
   in `static.md`.

## Limits

- Assertion counts come from the report maps, so "meaningful" does not check that an assertion is on data rather than
  on printed text.
- Generator sampling ran on 23% of contracted functions, without shrinking. The effect rule that excluded the rest is
  static and conservative.
- Test times are member times inside loaded batches, so tests near their bound can flip.
