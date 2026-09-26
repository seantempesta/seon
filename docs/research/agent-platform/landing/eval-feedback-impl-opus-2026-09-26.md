---
type: landing
lane: eval-feedback-design-opus (implementation, owner ruling 2026-09-26)
created: 2026-09-26
status: "repl.clj + bounds schema + regressions committed; honest :ms and recorded cost are patch P1 for root (held files); RESET NEEDED; tests run only as a scratch copy"
authority: docs/research/agent-platform/eval-feedback-design-2026-09-26.md; owner ruling relayed by root (reply = one map; findings only past a declared bound)
---
# Landing: the reply stays one map; misuse shows only past a declared bound (2026-09-26)

What exists: `seon.repl/response` is the one composer behind the MCP answer and the next Seon context. This cut:
- fixes its two reply defects (task `18c37812b5bc`);
- adds `findings`: one `;;` line above the map per `m/explain` error of the evaluation's measures against one declared
  Malli schema, `:seon.config.eval/bounds`.

A normal reply adds nothing. Recording an honest `:ms` and the rest of the cost needs held files, so it is patch P1.

## Broken first / not done

1. **`:ms` is still dishonest until root applies P1.** Today `:ms` still includes Seon's program acquisition and
   excludes admission and render. Until then the new duration bound can fire on a first evaluation whose time was
   Seon's, beside the old `explained-evaluation` `:out` text, which P1 deletes. **Apply P1 in the same adoption as this
   commit.**
2. **Seon-overhead fault not implemented (unclear, so stopped).** The ruling asks that Seon's overhead past a bound
   become a fault for root. P1 records `:seon.eval/acquisition-ms` on the receipt, so it is queryable, but raises no
   fault. `seon.fault/fault!` needs the evaluation's environment, a `:seon.fault/context` and a stop-request owner
   inside `seon.sci.eval/evaluate`, which I cannot verify from outside a held file. Root: name the owner, or accept a
   query over `:seon.eval/acquisition-ms`.
3. **A bound edited in the resource is adopted at the next reload of `seon.repl`.** The compiled explainer is a load-time
   `delay` over `seon.schema.edn/packaged-forms`. Reading `packaged-forms` costs 3.7 ms per call, too much per reply.
   The reply path holds no projection to key a projection cache on.
4. **`def-note` stays.** Its replacement, a recorded `:unrecorded-defs` measure, needs `seon.sci.eval`. Not in P1.
5. **P1's failure path is unchanged.** `failed-evaluation` still reports the kernel record's duration.
6. **Tests ran only as a copy.** `test/seon/repl_test.clj` (18 tests, 92 assertions) passed against a scratch copy of
   the new `repl.clj` loaded into `scratch.efd-repl` on default (pid 75849), with the bounds read from the working-tree
   resource. That copy was unarmed. The contract shapes were validated explicitly: `cost` matches
   `[:map-of :qualified-keyword :int]` and `findings` matches `[:vector :string]`. Root must run the real ones.

## Changed (commit below)

- `src/seon/repl.clj`:
  - `reply-map` ends the line after any entry whose text ends in a line comment (the floor's elision legend), using
    this namespace's own lossless lexer `syntax-tokens`.
  - A declared renderer's output is followed by the reply map minus `:value`/`:error`, so it keeps `:result`, `:ms`
    and `:out`.
  - `cost` reads the recorded measures the bounds name from the emission, plus `:seon.repl/out-tokens` (a
    `seon.ai.tokens/estimate` of the saved output).
  - `findings` checks that cost against the bounds; `response` puts the findings first.
- `resources/seon/schemas/seon.config.eval.edn`: `:seon.config.eval/bounds`, a Malli map schema. Each entry carries its
  `:seon.repl/fix`:
  - `:seon.eval/duration-ms` `[:<= 1000]`: the fix names `(seon.profile/summary (seon.profile/cells) 5)`, verified
    callable from agent SCI on branch `efd-3`;
  - `:seon.repl/out-tokens` `[:<= 500]`.
- `resources/seon/schemas/seon.repl.edn`: `:seon.repl/cost` and `:seon.repl/out-tokens` declared.
- `test/seon/repl_test.clj`: two regressions.
  - `the-reply-map-reads-back-as-data-whatever-the-value-text-ends-in` (task `18c37812b5bc`) covers two cases:
    - an elided value's reply reads back with `:result` and `:ms`;
    - a renderer's reply keeps `:result` and `:ms`.
  - `a-reply-within-every-declared-bound-adds-nothing-and-an-exceeded-bound-is-one-line-above-it` covers two cases:
    - within every bound, the exact normal reply `#:seon.repl{:value 2, :result result/ee5885, :ms 4}`;
    - two exceeded bounds give two lines above the map.
- Sizes: +150 / −21 lines (repl.clj +90 / −14).

Measured on default (scratch copy, pid 75849):
- a normal response costs 3.8 µs;
- a response with two findings over 4,400 characters of output costs 102 µs;
- the compiled explainer costs 1.3 µs; an uncompiled `m/explain` costs 27 µs.

Static checks:
- clj-kondo reports 0 errors on both files.
- `seon.contracts-compile-test/check` over `repl.clj` and `repl_test.clj` returns `[]`.
- Every file in `resources/seon/schemas/` reads as one EDN map (bb).
- `seon.schema/declaration-projection` over packaged forms plus the new entries builds 3,515 forms, and
  `:seon.config.eval/bounds` resolves in it.
- The task id was verified in the JVM with `seon.issue/task-handle`.

## Patch P1 for root: `tmp/eval-feedback-design-opus/p1-honest-ms-and-recorded-cost.patch` (+41 / −34)

Written against HEAD d8742ab1f's files. It passes `git apply --check` against the working tree, including other lanes'
edits, except for the `seon.config.eval.edn` hunk, which applies after this commit.

- `src/seon/sci/eval.clj`:
  - `:seon.eval/duration-ms` is one clock difference, from `evaluate`'s `started-at` to the shown answer, minus
    `acquire!`'s time.
  - `:seon.eval/acquisition-ms` and the kernel's `:seon.eval/allocated-bytes` are recorded on the evaluation.
  - `explained-evaluation` (the output-munging slow text) and the per-evaluation `profile/begin` (133 µs each) are
    deleted.
- `src/seon/turn.clj`: settlement copies the two keys to the receipt.
- `src/seon/render/transcript.clj`: the emission carries `:seon.eval/allocated-bytes`, so its bound can fire.
- `resources/seon/schemas/seon.eval.edn`: `:seon.eval/acquisition-ms` declared.
- `resources/seon/schemas/seon.cluster.eval.edn`: both keys added to the receipt as optional.
- `resources/seon/schemas/seon.config.eval.edn`: the `:seon.eval/allocated-bytes` `[:<= 536870912]` bound.

Checks on the patched copies:
- clj-kondo reports 0 errors; the only warning delta was the unused `seon.profile` require, now removed.
- `contracts-compile-test/check` over the three patched sources returns `[]`.
- The patched schema projection builds.
- A receipt with both keys validates.
- An 900 MB allocation yields a finding at `[:seon.eval/allocated-bytes]`.
- The patch was not loaded into default, because those files are held.

## Tokens (item 4; `seon.ai.tokens/estimate`, 3.2 characters per token)

| Text | Estimated tokens |
|---|---|
| `#:seon.repl{:value 2, :result result/e5885fdcf651b, :ms 4}` | 18 |
| `result/e5885fdcf651b` (the handle alone) | 6 |
| `, :result result/e5885fdcf651b` | 8, which is 44% of the reply |
| `, :ms 4` | 2 |

The handle dominates. The estimator also undercounts hex: 13 random hex characters are not merged by BPE, so the
real count is likely higher. No tokenizer is installed to confirm this.

**Proposal (not implemented).** Name results by the agent's own evaluation count, `result/e41`, the form this
namespace's docstring already shows. The reply becomes `#:seon.repl{:value 2, :result result/e41, :ms 4}`,
46 characters or about 14 tokens.
- The count is unique within the agent's context, which is the only place the agent reads handles.
- The receipt keeps the 12-hex global id, and the binding resolves the short name to it.
- The count derives from the agent's receipts, so it is not a stamp.
- Owner of the change: `seon.sci.admit/result-handle` plus the binder in `seon.sci.eval`.

## Root runs

1. Apply P1 (above) together with this commit.
2. Run `bin/test-check default --ns seon.repl-test`.
3. Run `bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection`
   and `--test seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn`.
4. After P1, the tests that read receipts: `--ns seon.turn-test` and the transcript tests.

**RESET NEEDED.**

Branch `eval-feedback-design-opus` is retired. **Branch `efd-3` (the SCI probe) is NOT retired:** default restarted under it, and the MCP `branch` tool (`retire` and `list`) then answered `NullPointerException: Future.get() because "fut" is null` while default was coming up. Root: retire `efd-3`. The scratch JVM namespaces ended with the restart.
