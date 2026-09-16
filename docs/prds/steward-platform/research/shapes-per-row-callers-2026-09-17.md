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

## 2026-09-16: re-measured and landed at HEAD

Same probe, same JVM discipline, on `default` pid 53320 (reset that morning),
against `f479a2441` — the first-party file-identity rewrite this note named as
its boundary had landed (`28f1a761e`), so the seams were found by name rather
than by the line numbers above.

### Before, re-measured at HEAD

| call | ms/call | note |
|---|---|---|
| `seon.schema.edn/packaged-forms` | **21.31** | 30 calls after a warm-up (was 18.13) |
| `seon.program/shapes-in forms` | 0.0224 | 200 calls (unchanged) |

Twenty smallest `src/seon/**.clj[c]` files, one `build-artifact` each, counting
wrappers on both seams:

```
{:files 20, :rows 90, :packaged-forms-calls 40, :shapes-in-calls 90,
 :total-ms 9780.3}
```

Two per-file resolutions and one per-ROW derivation, exactly as measured on
2026-09-17. The ratio is the whole finding: 40 = 2 × files, 90 = 1 × rows.

### What landed

The designed fix, unchanged in shape:

1. `:seon.program/shapes`
   (`[:map-of :seon.program/identity-attribute :seon.program/shape]`) is
   declared in `resources/seon/schemas/seon.program.edn`. The cost of the typed
   refusal was measured before adopting it: validating the whole six-family
   shapes map costs **0.00083 ms**, 27× cheaper than the 0.0224 ms derivation
   it guards, so the contract is free at indexing scale.
2. `src/seon/program.cljc`: `shape`, `canonical-row`, `changed-attributes` and
   `exact-replacement-tx-in` take that value in their explicit arities;
   `!supplied-shapes`, `supplied-shapes` and `shapes`'s one-argument arity are
   deleted. `shapes` is now a plain no-argument function over the authored
   resources.
3. `src/seon/fn.clj`: `build-artifact` and `build-manifest` resolve the
   population once, derive its shapes once, and carry BOTH halves —
   `analysis-rows-by-file` takes the declared-attribute key set as an argument,
   `artifact` / `normalized-index-row` / `reconcile-tx-in` / `index!` take the
   shapes.
4. `src/seon/cluster.clj`: `incremental-source-refresh!` resolves
   `schema.edn/packaged-forms` once above the per-file loop and supplies it as
   `:seon.schema.projection/forms` in the `build-artifact` request.
5. `seon.fn-test/indexing-resolves-its-declaration-world-once-per-operation`
   asserts the COUNT at three seams: one complete manifest over four fixture
   files is 1 population resolution and 1 shape derivation whatever the row
   count; one file artifact is 1 and 1 (not 2 and per-row); and a caller that
   supplies its own population resolves the resources 0 times.

### The typed refusal earned its place immediately

The first development adoption of the new contract refused:

```
seon.program/canonical-row refused row-shapes at [:seon.sci.eval/evaluation]:
expected either :seon.ns/name or :seon.fn/sym or :seon.schema/key or
:seon.test/sym or :seon.fn.file/relative-path or :seon.lint/id, got a keyword.
```

That is a declaration POPULATION handed where shapes belong — the exact
silently-stripped-row failure the schema key exists to make impossible. The
cause was a stale `seon.fn` still loaded in the development JVM against a
freshly adopted `seon.program`; without the contract it would have published
rows stripped of every owned attribute and reported success.

### After, measured at HEAD on the same JVM

Same 20 files, same counting wrappers:

| | `packaged-forms` calls | `shapes-in` calls | wall ms |
|---|---|---|---|
| before | 40 | 90 | 9780.3 |
| after | 20 | 20 | 6840.8 |
| after, population supplied once | **0** | 20 | 6248.1 |

The counts are the result; the wall times are recorded only because they were
measured, and they are contended (a full publication and three other lanes
shared this JVM). Per incremental publication of 337 changed files the removed
work is 674 resolutions of a 21.31 ms merge, replaced by ONE hoisted
resolution in `seon.cluster/incremental-source-refresh!`.

### In-process verification

On `default` pid 53320, through `seon.test/run` with the three-argument arity
(`:seon.test/remaining-ms` 600000), each namespace reloaded through
`#'seon.test/with-test-loader` first. No test JVM was launched; `default` was
never stopped, reforked or restarted.

- `seon.fn-test/indexing-resolves-its-declaration-world-once-per-operation`:
  8 assertions, 0 failures. Its first run failed on the `0` assertion with an
  actual of `1`; the cause was `with-redefs` counting a CONCURRENT publication
  thread's call, proven by a stack-trace probe that recorded 0 in-thread calls
  for the same operation. The counter now ignores other threads.
- `seon.program-test`: 26 tests, 0 failed, 0 errored.

The publication path itself is the live proof: `bin/seon init --dev default`
completed source build, analysis, program population (93 392 rows), branch
publication, development schema declarations, development program
reconciliation, loaded definitions, SCI acquisition and JVM instrumentation
through the changed `index!` / `reconcile-tx-in` / `artifact` seams. Its final
adoption commit was refused with `:seon.cluster/source-changed-during-adoption`
because concurrent lanes kept editing the tree, not by anything in this change.
