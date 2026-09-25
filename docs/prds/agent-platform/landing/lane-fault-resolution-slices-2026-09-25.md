---
type: landing
status: landed
created: 2026-09-25
scope: Derived fault resolution slices 1-2 and log-line deletion.
---
# Lane fault-resolution-slices (2026-09-25)

Implements slices 1 and 2 of `docs/research/agent-platform/fault-resolution-design-2026-09-25.md`
(6c181108e), and deletes `seon.error/log-line`.

## Broken or not done first

- **No test was run.** This follows the LOAD RULE. Root runs the commands below.
- **The status/MCP resolved count is not landed.** `problem-counts` and `open-faults` are
  computed in `src/seon/cluster.clj` `mcp-runtime-observation`, and that file is held by
  cluster-deadcode-and-mcp-bound. The owner of that file should add this hunk:

  ```clojure
  ;; beside open-faults, with the same guard
  resolved-count (when (and connection ready (not unavailable))
                   (count (filter :seon.error/resolution (:seon.problems/error-signatures problems))))
  ;; in the cond-> tail
  resolved-count (assoc :seon.dev.mcp/resolved-fault-count resolved-count)
  ```

  Health already reads only `open-faults`, which now excludes resolved signatures.
- **`:seon.problems/error-signature` does not declare its new optional key.** That schema is
  in `resources/seon/schemas/seon.problems.edn`, which this lane does not own. The map is open,
  so the value validates today. The owner should add
  `[:seon.error/resolution {:optional true} :seon.error/resolution]`.
- **Slice 3 (widening the chain) is not possible yet.** `:seon.error/chain` is evidence and not
  an attribute. It is stored only inside the evidence blob (seon.error.edn `:chain`), and no row
  on default carries it. Until the chain has a queryable producer fact, `resolve!` remains the
  statement for off-origin fixes (D, H), as the design recommends.
- **Deviation from the design: history instead of `as-of`.** Every `seon.db` read on an
  `as-of` view derives that view's own projection. Measured on default: 18 signatures took
  2,334 ms with `db/pull` on `as-of` and 955 ms with `db/q` on `as-of`. The profile names
  `seon.db/content-projection` ×18 at 2,262 ms. `resolution` avoids this in two steps:
  1. It reads the current digest datom's tx. When that tx is at or before the latest
     occurrence, the fault is unchanged and no history is read.
  2. Otherwise it reads the digest that was current then from one `history` view (0.015 ms
     to create): the latest datom at or before that tx, where an assertion wins its tx.
- **No RESET is needed for this slice.** The two faults copied from tests that the design
  names are already resolved on default: `6a63862e` and `b49c357c` have `resolved-tx`.

## What changed

- `seon.error/resolution [database signature]` (contracted) returns one of three values:
  `nil` (open); `{:seon.error/resolved-by :seon.program/definition-digest, :seon.instrument/fn,
  :seon.error/digest-then, :seon.error/digest-now}`; or `{:seon.error/resolved-by
  :seon.error/resolved-tx, :seon.error/resolved-tx id}`. It never falls back to a default: a
  missing function row or digest leaves the fault open, and a read refusal is returned.
- `opening` calls it. A recurrence after resolution opens under the current digest, or under
  the `resolve!` tx id. The message id is `id([signature recipient opening])`, so a recurrence
  wakes once. `message-tx` accepts either key, and recipients are computed for any non-nil
  opening.
- `resolved?` is deleted. The fault HTML reads `resolution`, which fixes the queued defect
  "Resolved shown after recurrence". Without a database the page says "Resolution unknown".
- `seon.problems/open-faults` keeps the entries whose resolution is not resolved.
  `error-signatures` entries carry `:seon.error/resolution` when resolved. The problems
  page's errors row adds "resolved: by change to f (d0→d1)" or "by resolve! in transaction N".
- `seon.error/log-line` is deleted, along with error_test's `rendered` helper and
  `the-log-line-is-one-line-and-derived`, and the log-line assertions in two tests.
  Callers were checked two ways, and both agree. `rg` over src/, script/ and test/ found only
  error_test. The stored `:seon.fn/calls` reverse edges on default name only
  `seon.problems/log-report` (deleted in 0db2517fe, not yet adopted) and
  `seon.error-test/rendered`.
- `resources/seon/schemas/seon.error.edn` declares `:seon.error/resolution`, `resolved-by`,
  `digest-then` and `digest-now`. These are derived and never stored. The schema form was
  validated against default's registry: both alternatives are accepted and a bad enum is
  refused.

## Evidence (MCP JVM on default, read-only; probes in a scratch namespace, since removed)

- `resolution`, copied verbatim into `lane-fault-resolution-scratch`, took **47 ms for all 18**
  signatures, with 0 refusals. It reported 3 resolved by digest: `0c3d12e2`
  `seon.problems/failed-tests`, `371424a9` `seon.turn/step` and `b184bc84`
  `seon.fn/unresolved-macro-refusal`. It reported 2 resolved by `resolved-tx`: `6a63862e` and
  `b49c357c`. The rest are open. This matches the design's probe.
- Cost added per `problems` call: one `resolution` per signature, about 2.6 ms each on default.

## Commits and lines

| commit | slice | added / deleted |
|---|---|---|
| 80958c50b | 1: resolution, opening, open-faults, render, schema, regression | src+schema +91 / −48; test +48 / −1 |
| e04a511c1 | 2: problems entries and page resolved lines | src +21 / −10; test +7 |
| 0bdc91d54 | delete log-line | src −64; test +3 / −19 |

Net source change: −10 lines.

## Root runs (serially, in a quiet window, after adopting these paths)

```
bin/test-check default --test seon.problems-test/a-fault-resolves-when-its-function-changes-and-reopens-on-recurrence
bin/test-check default --test seon.problems-test/a-resolved-fault-stays-closed-until-it-recurs
bin/test-check default --ns seon.error-test
bin/test-check default --test seon.error.branch-test/a-fault-is-delivered-and-shown-open-once-per-opening
bin/test-check default --ns seon.problems-test
bin/test-check default --ns seon.cluster.mcp-test
```

The new regression covers three cases:
- An unchanged fault stays open, and a repeat is not delivered again.
- A changed digest reads as resolved by the digest, shows on the page and validates as `:seon.problems/problems`.
- A recurrence reopens the fault and is delivered exactly once, and a further repeat is not delivered.

Verification limits:
- `test/seon/schema/datahike_parity.edn` may list declared attributes. The four new keys are
  not entity attributes, but only the parity test can confirm this.
- The test assumes that a flow ex-info carrying `:seon.instrument/fn` and `:seon.instrument/arm`
  records `:seon.instrument/fn` on the error row. The regression asserts this first.
