---
type: research
status: complete
created: 2026-09-17
tags: [research, steward, program-graph, schema, publication, performance]
---

# The indexer re-resolves its declaration world per file and per row

Dated 2026-09-17. Bounded measurement lane on `default` (pid 30138), MCP `jvm`
mode, instrumented development JVM. **No code change landed**: the owning
files were taken by a concurrent lane mid-measurement (see
[Verification boundary](#verification-boundary)). The numbers and the designed
fix are recorded here so the next lane starts from evidence.

## What was measured

Timing helper, 5–200 iterations per call, in one `seon.dev.mcp` evaluation:

| call | ms/call | note |
|---|---|---|
| `seon.schema.edn/packaged-forms` | **18.13** | re-reads and re-merges every authored resource |
| `seon.schema.edn/declaration-stamp` | 0.709 | the sorted `[name, length, last-modified]` |
| `seon.program/shapes` (0-arity, stamped) | 0.701 | a stamp plus a hit |
| `seon.program/shapes-in forms` | 0.0224 | the derivation itself |
| `seon.program/shapes forms` (the 9cc181289 memo) | 0.0013 | what the memo costs WHEN IT IS USED |

`seon.fn/build-artifact` of `src/seon/program.cljc` (62 rows): **457.6 ms**
per call, averaged over 3 calls after a warm-up call.

## The seam count: 20 files, counted not timed

Twenty smallest `src/seon/**.clj[c]` files, one `build-artifact` each, with
counting wrappers installed by `with-redefs` around
`seon.schema.edn/packaged-forms` and `seon.program/shapes-in`:

```
{:files 20, :rows 122,
 :packaged-forms-calls 40, :shapes-in-calls 122, :total-ms 7766.3}
```

Two facts, both the same defect class (a value re-fetched at call time instead
of carried, AGENTS.md §2.1):

1. **`packaged-forms` is resolved TWICE per file**, not once per operation —
   `seon.fn/declaration-forms` (`src/seon/fn.clj:1044`, the fallback when the
   request supplies no `:seon.schema.projection/forms`) and
   `analysis-rows-by-file`'s `(set (keys (schema.edn/packaged-forms)))`
   (`src/seon/fn.clj:989`, the declared-attribute key set
   `writes-by-writer` needs). At 18.13 ms that is **36.3 ms per file**, and at
   the 337 files one relocated-checkout publication analyses, **12.2 s** spent
   re-reading a population that cannot change during the operation.
2. **`shapes-in` is derived once PER ROW** — 122 calls for 122 rows. The memo
   `9cc181289` added for exactly this (`seon.program/shapes`'s one-argument
   arity, 0.0013 ms) is bypassed by every per-row caller: `program/shape`
   (`src/seon/program.cljc:250`), `canonical-row` (`:893`) and
   `changed-attributes` (`:1018`) call `shapes-in` directly, and
   `seon.fn/artifact` (`src/seon/fn.clj:1067`) and `normalized-index-row`
   (`src/seon/fn.clj:2296`) are their per-row callers. At 0.0224 ms this is
   small per file (122 rows → 2.7 ms) and large at publication scale
   (~90 000 rows → ~2.0 s per per-row caller, and `index!` has two).

The memo is also the wrong instrument: a process-wide atom keyed by object
identity is a mirror of what the caller already holds.

## The fix, designed and not landed

Thread the derived value; delete the memo rather than route more callers
through it.

1. Declare `:seon.program/shapes` as
   `[:map-of :seon.program/identity-attribute :seon.program/shape]` in
   `resources/seon/schemas/seon.program.edn`. This is what makes the threading
   honest: a caller that hands a declaration POPULATION where shapes are
   expected is a typed contract refusal instead of a row silently stripped of
   every owned attribute (a population map's keys are not identity attributes,
   so it cannot validate).
2. In `src/seon/program.cljc`, the explicit arities take that value, not a
   population: `(shape row-shapes identity-attribute)`,
   `(canonical-row row-shapes row)`,
   `(changed-attributes row-shapes current desired)`,
   `(exact-replacement-tx-in row-shapes current desired)`. The private
   `canonical-row-in` / `changed-attributes-in` / `replacement-tx` already take
   exactly this value, so each public arity becomes a direct call. Delete
   `!supplied-shapes`, `supplied-shapes`, and `shapes`'s one-argument arity:
   with every per-row caller handed the value, nothing is left to memoize.
3. In `src/seon/fn.clj`, resolve the operation's world ONCE and carry both
   halves of it — the population (for the declared-key set at `:989`) and its
   derived shapes — through `artifact`, `build-manifest`, `normalized-index-row`,
   `reconcile-tx-in` and `index!`, which already thread `forms` and need only
   to thread the pair. Expected counts for the 20-file probe above:
   `packaged-forms` 20 (from 40), `shapes-in` 20 (from 122); expected saving at
   337 files, ~6.1 s of re-reading plus the per-row derivations.
4. Regression in `seon.fn-test`, counting the seam and never wall time: N
   `build-artifact` calls resolve the declaration population once each and
   derive the shapes once each, whatever the row count — the same
   `with-redefs` counter used above.

### What this fix cannot reach from the owning namespaces

The remaining 18.13 ms × 337 is the CALLER's: `seon.cluster/incremental-source-refresh!`
calls `seon.fn/build-artifact` per file (`src/seon/cluster.clj:1986`) without
supplying `:seon.schema.projection/forms`, so each file resolves the
population again. The publication must resolve it once and pass it — one
`(let [forms (schema.edn/packaged-forms)] …)` hoisted above that loop, with
`:seon.schema.projection/forms forms` added to the request map already built
there. `src/seon/cluster.clj` is held by a concurrent lane; that hunk is
named here rather than edited.

## Verification boundary

Measurement only; nothing was committed to `src/`, and the two exploratory
edits this lane made (the `:seon.program/shapes` declaration and the
`shapes-in` contract referring to it) were backed out to the byte before
reporting — `git diff` on both files carries only the concurrent lane's work.

At 10:05–10:07 local a concurrent lane began rewriting program-row FILE
IDENTITY across 34 files, including both files this assignment owns:
`src/seon/fn.clj` (`rooted-file`, `artifact`, `lint-rows` and
`build-artifact`'s request key all re-signatured) and `src/seon/program.cljc`
(`identity-attributes` now names `:seon.fn.file/relative-path`), plus
`resources/seon/schemas/seon.program.edn`. That is triage item 1 of
[the incremental-refresh note](../../context-generation/research/incremental-refresh-exchange-bound-2026-09-16.md)
and it re-signatures the exact functions this fix threads a value through. A
path-limited commit here would have carried that lane's uncommitted work.
Sequence this fix AFTER that lane commits; the measurement above is against
`HEAD` behaviour and the seam counts remain the acceptance test.

Issue:
[the-indexer-resolves-its-declaration-world-per-file-and-per-row](../../../seon/issues/the-indexer-resolves-its-declaration-world-per-file-and-per-row.md).
