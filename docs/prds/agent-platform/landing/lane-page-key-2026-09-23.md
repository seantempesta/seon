---
type: evidence
status: committed (fix schedule #24c step 3, the page part)
created: 2026-09-23
---

# Page key: an unchanged head re-derives no block

Census row A2 (`docs/research/agent-platform/invalidation-census-2026-09-23.md`)
and the review's step 3 (`docs/research/agent-platform/review-invalidation-design-2026-09-23.md`).
The fix keeps the existing pure owners. Each page derivation is now keyed on the
inputs it actually reads.

## Change (`src/seon/render/web.clj`)

* `program-key`: the page's code key is the context's program snapshot
  **without** `:seon.db/db`, plus `render/source-generation`. On a
  same-program acquisition, `seon.sci.eval/acquire!` replaces the snapshot's
  `:seon.db/db` with the newest database. The old key compared the whole
  snapshot, so each acquisition set `changed-code?` and dropped every
  retained call and fragment.
* Commit confirmation: `derive-page!` keeps the database value each page was
  last derived or checked at (`::page-databases`). When
  `render/same-committed-database?` (Datahike's committed-value identity:
  commit id, connection and generation) matches, `candidate-call-ids` does not
  ask read evidence again. A source preview with no output is still a
  candidate. This reuses the existing owner; no new identity was added.
* `newer-page?` replaces the `::page-bases` basis-t guard. Every fork of a
  cluster context shares one render cache (`seon.render/shared-cache`, keyed
  on `:seon.sci.eval/projection-state`). A basis-t from another branch
  therefore blocked the store: a fixture branch off an older head never
  recorded its page, so its next GET re-derived. The guard now orders only
  values on the same connection lineage. `::page-bases` is gone; nothing
  else read it.
* Complete Malli contracts on `candidate-call-ids`, `program-key`,
  `newer-page?`, `derive-page` and `derive-page!`. Each one compiled against the
  live projection registry (probe below). The first draft's bare `:vector`
  output was a `:malli.core/child-error` and blocked working-tree boots. It was
  replaced before the commit with
  `[:tuple [:or :nil :map] [:or [:vector :seon.source/commit-id] :seon.db/error-result]]`.

## Proof

Live default (pid 70720, `http://127.0.0.1:62979/agent/root`). Read-only GETs
only; web.clj was `load-file`d into the default JVM because hook publication is
paused (`.claude/seon-hook.edn` `:current-source {:enabled false}`).

| GET at an unchanged head | wall ms |
|---|---|
| before, curl ×4 | 833 / 289 / 290 / 285 (census P11: 1,151 / 667 / 614) |
| before, in-JVM `slurp` | 703 |
| before: `candidate-call-ids` alone, 11 retained calls, 0 candidates | 334 |
| after, curl ×6 | 630 (first after load) / 9 / 8 / 9 / 9 / 7 |
| after, in-JVM ×40 (same head 536871049) | 4–9 |
| after, in-JVM ×30 (same head 536871088, 500 ms apart) | 1,406 first (the cache was emptied by non-read-only MCP evals, `invalidate-runtime-derived-state`), then 8–29 |

Fixture page (`/agent/page-key`, with-server, isolated branch of default).
Phases are timed by redefining the owners around one run.

| step | GET ms | evidence ms | page-result ms | blocks re-derived |
|---|---|---|---|---|
| first | 1,217 | 810 | 1,031 | all |
| unchanged head | 25 | 0 (not asked) | 0 | 0 |
| unrelated data write + `acquire!` | 1,446 | 1,424 | 0 | 0 attribute-bound (1 run: one `:all` whole-index read) |
| block input change (`my.agents.page-key` doc) | 1,393 | 1,231 | 301 | the changed block |

An earlier run had the unchanged head at 10/8 ms, the unrelated write at
2,381 ms, and each repeat at the new head at 11–13 ms.

Regression:
`seon.render.web-test/an-unchanged-head-re-derives-no-block-and-an-input-change-re-derives-its-block`.
It asserts that an unchanged head asks for no evidence and derives nothing; an
acquisition after an unrelated write keeps the retained calls and re-derives no
attribute-bound block; the checked commit is then itself an unchanged head; and
a block-input change makes that block a candidate while the rest stay retained.
Runs were `clojure.test/test-vars` under an isolated `acquire-context!`
execution handle in the default JVM (seon.test/run cannot select an unindexed
test while publication is paused). The test was green 3/3 (14.7 / 33.4 /
34.1 s). One further green run took 40.7 s.

Neighbours, green in one run (114.2 s, a fixture defect):
`unrelated-transaction-reuses-debug-observation-and-render-call`,
`an-agent-page-is-the-same-mechanism-as-root`,
`a-page-failing-every-pass-offers-one-fault-and-the-proc-survives`,
`debug-data-reuses-read-evidence-when-the-database-is-unchanged`. The full
namespace was not run.

Falsification, same run:
* The old key (a snapshot that includes `:seon.db/db`) fails only "the
  acquisition keeps the program key, so the retained calls survive".
* With commit confirmation disabled (`same-committed-database?` returns
  false), it fails "an unchanged head asks no read evidence…" and "the
  checked commit is itself an unchanged head".

Expectations fixed in `unrelated-transaction-reuses-debug-observation-and-render-call`
(retired assumptions):
* Its "unrelated" write was a `:seon.ns` row without
  `:seon.program/definition-digest`, which the writer now refuses. It also
  was a program write. It is now a data row (a second `:seon.cluster` row).
* The relevant doc is unique per run. Invocations are content-keyed in the
  shared cache, so a warm JVM answers a repeated identical doc from that cache.
* The initial "executes its applicable render functions" positivity was
  dropped: a warm JVM answers the initial invocation from the cache.

Contract compile probe (JVM, after load):
`(malli.core/schema (:malli/schema (meta #'seon.render.web/<fn>)) {:registry (:seon.schema.projection/registry (seon.sci.kernel/context-projection ctx))})`
compiled all five. The same probe returns `:malli.core/child-error` for the
bare-`:vector` form.

## Timings over 1 s (defects over 10 s marked)

| operation | wall ms | phase |
|---|---|---|
| `support/seed-cluster!` in with-server on a fresh branch | 19,364–20,100 | **DEFECT (>10 s)**: `config/apply!` → `seon.db/transact!` 15,785 ms; writer thread samples in `schema/load-projection` → `build-projection` → `validate-contracts!`, `projection-from-rows`, `canonical-value-string` (census A7, cross-branch projection miss) |
| one with-server web test | 14,700–41,700 | **DEFECT (>10 s)**: the fixture row above, plus JVM load |
| GET after a write (evidence check) | 1,424–2,381 | `db/read-evidence-current?`: census A3 order inversion (`index-evidence-current` `d/since` history scan before the O(attrs) revision compare), `src/seon/db.clj:1119-1146` |
| first page derivation | 1,044–1,406 | page-result 1,031, evidence 810 |
| `acquire-context!` isolated | 856 | |

Nothing here was a cache hit reported as work. The unchanged-head GETs are pure
cache hits, with zero evidence asks and zero derivations.

## Out of scope (other owners; exact changes)

1. **`src/seon/db.clj` (writer-cost lane):** in `read-evidence-current?`, run
   the revision compare (`(= revision (dependency-revision …))`) before
   `index-evidence-current`. After a write, this is 98% of GET cost (1,424 of
   1,446 ms). That function's `(catch Throwable _ false)` around replay also
   swallows the cause.
2. **Render cache shared across branches:** `seon.render/shared-cache`
   hangs off `:seon.sci.eval/projection-state`, which every `sci/fork` of a
   cluster context shares. Fixture branches, test members and default
   therefore write the same `::calls`/`::programs`/`::packages` entries
   by registration key. The render proc does
   `(swap! cache assoc ::packages packages)` wholesale, so a fixture proc
   overwrites default's packages. Web tests that use agent `root` in a JVM
   hosting a live `root` page are nondeterministic (observed:
   `unrelated-transaction-reuses-debug-observation-and-render-call`
   observation 2 vs 1 on both HEAD and this change). Owner: `seon.render`
   / `seon.sci.eval` fork. The cache should be scoped per branch
   connection, with eviction when a branch is unlinked.
3. **`seon.cluster.boot/readiness` → `mcp-runtime-observation`**
   (`src/seon/cluster.clj:628`): `:seon.problems/problems` returned a flat
   `seon.test.runner/latest-results` refusal instead of a problems map, and
   `(count rows)` threw `count not supported on this type: Date`. `bin/seon
   status` failed until the refusal cleared.
4. `seon.db/index-page` captures `:all`, so a page block that reads a reverse
   ref (`:my.note/_agent`) re-derives after every commit.

RESET NEEDED: no. Default runs the working-tree web.clj by `load-file`
(equal to this commit); the orchestrator's next adoption publishes it.
