---
type: research
status: active
created: 2026-09-17
tags: [research, program-graph, schema, adoption, landing-note, class/p1]
---

# Program-row ownership follows the declarations, not the process — 2026-09-17

Read end to end before starting: [AGENTS.md](../../../../AGENTS.md) (§2.1
values carry their world, §2.2 derive or die, §2.5 one mechanism),
`tmp/orchestrator/wave2/repl-rule.txt` (all twelve rules),
[the issue](../../../seon/issues/program-shapes-cache-strips-attributes-declared-after-the-jvm-started.md),
and [the dissolution landing note](program-shapes-dissolution-2026-09-16.md)
(`8795db4ac`), which this change finishes.

## Broken things first

- **Nothing in this slice is broken at hand-off.** The one thing I could not
  prove in process is named under "Verification boundary": the isolated gate
  owns the verdict for `seon.cluster.source-test`, which I did not run.
- **The foreign publication refusal the previous note left open is gone.**
  `bin/seon init --dev default` now reaches `development cluster converged`
  (commit `6aaaa62d-c08e-571f-ace2-ca01f86d1e27`); the
  `seon.fn/rooted-source-files` entity-id rejection did not recur.
- **The cheapest honest freshness check is not free.** Stating the 180
  authored schema resources costs **1.0 ms**, against **16 ms** to re-read and
  re-merge them and **0.15 ms** for the derivation itself (measured on
  `default`, pid 88182). That is why the per-row callers are threaded rather
  than left on the fallback: 180 000 stamped calls in one full publication
  would have added ~180 s.

## The defect

`seon.program/shapes` answered from a process-level `defonce` holding the
authored declarations as of the first call. `seon.fn/artifact` filters every
indexed row through `program/canonical-row`, so an attribute declared AFTER a
JVM started was silently stripped from that JVM's rows until a restart. The
runner lane declared `:seon.test/long-ms` (`8c2f62701`), the analyzer lifted
it, `canonical-row` dropped it, and the regression read that as "the indexer
does not lift it". Gate workers are fresh JVMs and were unaffected; the
development JVM — where every in-process proof of a new attribute happens —
was not.

Fifth sighting of one class in two days: a `delay`/`defonce` caching a
snapshot the authority later re-decides (the fixture base, the effect
`probe-ctx`, the shapes resolver's first version which cached a THROWABLE and
wedged `default` for an hour, and now the shapes cache itself).

## The fix, in two halves

### 1. The fallback's cache key is the resources' own stamp

`seon.schema.edn/declaration-stamp` (`src/seon/schema/edn.clj:342`) is the
sorted `[name, length, last-modified]` of the files under the
`seon/schemas` classpath directory. Every declared attribute lives in one of
those files, so the stamp cannot stay equal across an added, edited or
removed declaration: **a declaration edit is a cache MISS BY CONSTRUCTION**,
and no adoption, hook or event has to remember to invalidate anything. A
jar-served resource cannot change while the process runs and stamps once from
its URL. Only a SUCCESS is remembered, preserving the property the previous
note established after a cached refusal wedged every lane.

The authority is still the AUTHORED resources, never the active projection: a
cluster whose stored schema predates a declaration must still index by it, and
reading the mid-publication projection is exactly what refused and wedged
`default` on 2026-09-16.

### 2. The indexer asks no global at all

One population per operation, handed to every row (§2.1):

| Seam | Change |
|---|---|
| `seon.fn/declaration-forms` | resolves the operation's population ONCE — the request's `:seon.schema.projection/forms` when supplied, else the authored resources (the `DECLARATION POPULATION FALLBACK ×1` floor the advisory names) |
| `seon.fn/build-artifact`, `seon.fn/build-manifest` | optional `:seon.schema.projection/forms` request key (accretion: an absent key behaves exactly as before) |
| `seon.fn/artifact`, `seon.fn/normalized-index-row` | carry the population; no per-row global |
| `seon.fn/reconcile-tx` | keeps its SINGLE 3-argument `:db.fn/call` arity and delegates to a private `reconcile-tx-in` that carries the population |
| `seon.fn/index!` | resolves once and threads into `reconcile-tx` and both `normalized-index-row` calls |
| `seon.program/exact-replacement-tx-in` | a new NAME, not a new arity (see "The arity trap"), carrying the population `canonical-row` and `changed-attributes` already accept |
| `seon.program/shapes` (1-arity) | memoizes the LAST supplied population by identity, because `shapes-in` is pure — a per-row caller that resolved its population once pays one identity check |

Measured on `default`: **5.8 µs** per supplied-population call against
**0.77 ms** for the stamped fallback and **16 ms** for an unmemoized merge.

## Live proof on `default` (pid 88182, never restarted)

1. `bin/seon init --dev default --changed …` (three source files, then the
   restructure and both test files) →
   `development cluster converged`, `:current-src` commit
   `6aaaa62d-c08e-571f-ace2-ca01f86d1e27`; the cluster's stored
   `:seon.source/commit-id` equals it.
2. `(seon.fn/build-artifact {:seon.fn.file/path
   "test/seon/cluster/boot_test.clj" :seon.fn.file/first-party-functions []})`
   → 58 rows, 36 distinct attributes, and the row
   `seon.cluster.boot-test/development-adoption-targets-one-of-two-cohosted-clusters`
   carries **`:seon.test/long-ms 600000`** — the very attribute that vanished.
   No restart.
3. Falsification of the key itself, before the regression existed: with
   `declaration-stamp` redefined to a different value and `packaged-forms`
   returning a population declaring `:probe/facet` on `:seon.fn.file/file`,
   `(seon.program/shapes)` owned `:probe/facet`; restoring both restored the
   previous derivation exactly (`{:before-has-probe? false
   :after-has-probe? true :restored? true}`).

## Regressions

| Namespace | Regression | Asserts |
|---|---|---|
| `seon.program-test` | `a-declaration-added-after-the-first-call-is-a-cache-miss` | the class: an attribute absent from the authored declarations is carried by no row; a changed resource stamp re-derives and owns it without a restart; a row built from that population carries it; an unchanged stamp answers the same derivation |
| `seon.fn-test` | `an-attribute-declared-after-this-jvm-started-is-indexed-without-a-restart` | at the indexer, on the canonical harness: the attribute is installed through the fixture's `::test-support/extra-schema` (the real admission path), `reconcile-tx` with the authored population does not write it, and `reconcile-tx` re-indexing the SAME file with the declared population handed in writes it; `build-artifact` given a population that stops declaring `:seon.fn.file/root` stops carrying it, so nothing answers from a process-lifetime cache |
| `seon.program-test` | `process-resolved-shapes-match-the-current-declarations` → renamed `resolved-shapes-match-the-current-declarations` | "resolved once per process" is no longer the claim |

## In-process proof and its boundary

Every run is `(seon.test/run (#'seon.test/resolve-test 'ns/test)
(seon.operator/connection "default") {:seon.test.run/provenance … 
:seon.test/remaining-ms 100000})` inside `default`'s JVM on a daemon thread,
one at a time. No test JVM, no `bin/test`, no `bin/test-fast`.

| Regression | pass / fail / error |
|---|---|
| `seon.program-test/a-declaration-added-after-the-first-call-is-a-cache-miss` | **5 / 0 / 0** |
| `seon.program-test/resolved-shapes-match-the-current-declarations` | **1 / 0 / 0** |
| `seon.program-test/declaring-an-attribute-on-a-program-row-schema-is-sufficient` | **5 / 0 / 0** |
| `seon.program-test/every-program-row-attribute-is-owned-or-names-another-writer` | **20 / 0 / 0** |
| `seon.program-test/program-identity-attributes-are-exactly-the-declared-row-schemas` | **4 / 0 / 0** |
| `seon.program-test/optional-attributes-are-replaced-exactly` | **3 / 0 / 0** |
| `seon.program-test/schema-row-properties-survive-and-retract-exactly` | **2 / 0 / 0** |
| `seon.program-test/declaration-admission-refuses-ambiguous-or-incomplete-rows` | **6 / 0 / 0** |
| `seon.fn-test/an-attribute-declared-after-this-jvm-started-is-indexed-without-a-restart` | **7 / 0 / 0** |
| `seon.fn-test/the-indexer-emits-no-attribute-the-program-row-schema-drops` | **7 / 0 / 0** |
| `seon.fn-test/indexed-declarations-carry-exact-file-bytes` | **12 / 0 / 0** |
| `seon.fn-test/static-findings-are-replaced-with-their-program-rows` | **9 / 0 / 0** |

Each fixture-backed `seon.fn-test` run took **20–30 minutes** to return under
concurrent lane load; the `seon.program-test` runs are seconds.

## The arity trap, measured — and two red herrings it produced

The first shape of this change added an ARITY to `seon.fn/reconcile-tx` and to
`seon.program/exact-replacement-tx`. Adoption converged, the cluster's
`:seon.source/commit-id` matched, the published `:seon.fn/arglists` and
`:seon.fn/spec` carried both arities, and both arities answered from the
prepl — and BOTH were refused inside `seon.test/run`:

```
seon.fn/reconcile-tx refused argument count at []: … an argument count of 4.
seon.program/exact-replacement-tx refused argument count at []: … of 3.
```

The cause is the already-open blocker
[a successful shared fixture base retains pre-adoption contracts](../../../seon/issues/successful-fixture-base-retains-pre-adoption-contracts.md):
the shared canonical base's program rows predate the edit, so the wrapper
re-armed under the fixture knows only the old arglists. It made THREE existing
regressions report errors that were not theirs.

The change now adds no arity: `reconcile-tx` keeps one arity and delegates to
a private `reconcile-tx-in` (a private function is not a callable root), and
`exact-replacement-tx-in` is a new name (a new name has no stale row). Both
are provable in process today, and the public contracts are unchanged. The
recurrence, with the numbers, is recorded on that issue.

**Second trap, same day:** `seon.test/run` reloads the test namespace INSIDE
the run, so a `test-var` resolved BEFORE the call executes the PREVIOUS
definition. Two runs reported an error from test code the file no longer
contained. Reload through `seon.test`'s loader and resolve the Var AFTER the
reload, in the same evaluation. Also recorded on that issue.

## Verification boundary

Measured on `default` (pid 88182) only, through `mcp__seon__eval_clj` in `jvm`
mode with explicit custody. `default` was never stopped, reforked or
restarted; no test JVM was launched. `seon.cluster.source-test` — the other
caller of `program/shape` — was NOT run in process and is named in the gate
request, and no other `seon.fn-test` or `seon.program-test` regression beyond
the twelve above was run. PROTECTED paths (`src/seon/cluster.clj`, `src/seon/operator.clj`,
`src/seon/test/runner.clj`, `src/seon/turn.clj`, and the test files the
write-storm and sweep lanes hold) were not touched: the refresh is triggered
by the derivation reading its own authority, so no adoption hunk was needed.
Commits are path-limited. The batched isolated gate is the orchestrator's
proof, not a claim made here:
`tmp/orchestrator/gate-requests/program-shapes-adoption.txt`.
