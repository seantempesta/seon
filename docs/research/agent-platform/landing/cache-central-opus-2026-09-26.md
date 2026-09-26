---
type: landing
status: slice 1 landed; RESET NEEDED; later slices stopped for root
lane: cache-central-opus
date: 2026-09-26
base: 8e4ac24c9 (default pid 75849)
design: docs/research/agent-platform/cache-central-design-2026-09-26.md (option B)
---

# Landing: one central cache, slice 1 (2026-09-26)

## Broken or unproven first

- **Fixed in `af7ba85b7`: the per-call cost of `558446356`.** Measured armed on pid 86089 at `c029d48cb`, with 200
  calls per probe:

  | call | before | after (estimated from components) |
  |---|---|---|
  | `seon.schema.edn/packaged-forms` | **2,309 µs**, of which `declaration-stamp` is 210 file stats (1.3 ms) | about 21 µs: `io/resource` 14 µs plus a URL-keyed cache hit |
  | `config/effective` | 1,250–3,260 µs (load varied) | about 250 µs: the `effective-in` body (`pull '[*]`) is 222 µs, and the population-keyed `declared-projection` hit is 4 µs |

  - **Why it is correct:**
    - Every JVM runs a committed archive, `data/source/<sha>`. Its `io/resource "seon/schemas"` is
      `…/data/source/c029d48cb…/resources/seon/schemas` (observed), and the files there never change while it runs.
    - So the packaged population is keyed by that URL.
    - `config/declared-projection` and `program/authored-shapes` are keyed by the population map itself, so a
      different population is a miss by construction.
    - This also removes the 2.3 ms from every other `packaged-forms` caller: `program/shapes` and each opened
      branch's schema accretion.
  - **Remaining:** task `68490518e186`, marked `:seon/tasks` on `seon.schema.edn/declaration-stamp`. The stamp has
    no source caller left. The held file `test/seon/program_test.clj` still redefines it;
    `tmp/cache-central-opus/program-test-stamp.patch` removes that. After the patch is applied, delete the stamp.
  - **Not yet measured on the new code.** Default runs `c029d48cb`. After the restart, root measures `effective` on
    default.
  - **My probes added two stray cache kinds on pid 86089** (`:probe/k`, `:probe/url`, 1 B each, visible in
    status). They vanish on restart.
  - **The editor's post-edit hook ran clj-kondo automatically** on my `edn.clj`, `program.cljc` and `config.clj`
    edits, using the shared cache. My own lint runs used `--cache false`.

- **Fixed in `558446356`, the root cause of both boot refusals: `config/effective` read the row by the branch's
  carried projection.**
  - `apply-compiled!` writes the row from this JVM's packaged declarations, and installs any attribute the branch
    lacks.
  - `effective` selected dials by the branch's program, which adopts those declarations only after boot. A dial the
    checkout added was therefore dropped from a row that held it.
  - The answer then failed its own armed `:seon.config/effective` contract: `loop-handle` ← `arm-agents!` after
    root's `0dc665873`, and `configure!` before it.
  - **Chosen fix: one population.** Writer, reader and contract all use config's `declared-projection`. The six
    `(schema/declaration-projection (schema.edn/packaged-forms))` sites in `config.clj` now go through it, and it is
    held in `seon.cache` under the declaration stamp.
  - **Rejected alternatives:**
    - Adopting before reads (b) would move the adoption write into boot's critical path. A declarations-only
      accretion would be refused on a retirement that the old program rows still reference. This is the
      `start --head` incident class.
    - Optional-with-default (a) would leave C's readers getting nil for a new dial during the window before
      adoption.
    - Compiled-at-boot (c) fixes one site at a time.
  - **Cost:** an `effective` call was about 0.03 ms (a carried-projection memo hit). It is now about **1.3 ms**
    armed, almost all of it `declaration-stamp` (210 file stats, measured on pid 82779). At three reads per agent
    form that is about 4 ms per form. A cheaper stamp, keyed on the loaded commit instead of file mtimes, would
    remove it. Root decides.
  - **Unproven live.** Default runs `8e4ac24c9`, which does not have the dial. Checks run:
    - contracts compile check on HEAD plus the change: 0 findings;
    - `clj-kondo --cache false`: 0 errors.

    The regression needs the platform boot test described below. A store that predates a dial cannot be built in
    one JVM: a raw `d/with` retraction of a declaration leaves the stored composites naming it, and projection
    derivation refuses (probed on pid 82779).

- **Fixed in `02fe65932`: HEAD `0fdfbcd8c` failed boot with an NPE in `seon.cache/configure!`.**
  - **Cause:** `boot-dials` (`config/effective`) keeps only the dials the *store's* program declares, and that
    program adopts the checkout's declarations only after boot. `apply-compiled!` had installed and written
    `:seon.config.cache/max-bytes`, but `effective` dropped it.
  - **Confirmed at the REPL on pid 81761**, a store at `8e4ac24c9` that predates the dial:
    - the store's projection does not declare it;
    - `effective` lacks it;
    - the attribute is not installed.
  - **Fix:** the process budget is acquired like `:seon.config.db/keep-history?`, from the compiled config boot
    just applied. `zero-overlay-compilation-resolves-every-registered-config-attribute` proves the compiled
    effective carries every declared dial. No nil guard was added.
  - **The same class of failure is still open.** Any dial the checkout adds that boot reads through `boot-dials`
    fails the same way: flow workload dials and the result caps. `dials` for `serve!` has the same exposure. At
    boot `apply-compiled!` exact-reconciles this cluster's row to the compiled row, so reading boot's own dials
    from `compiled-config` would give the same values plus new ones. That change is for root's ruling.
  - **Regression not written, because it needs a real boot.** A store that predates a dial cannot be built in one
    JVM: retracting the dial's declaration is refused, since the stored config composites (`:seon.config/effective`,
    `:seon.config/entity`, `:seon.config/manifest`) name it. This was probed with `seon.db/with` on default.
  - **Proposed platform test for root:**
    1. Publish and boot a fresh root at a commit before `92b22cbf1`, then stop.
    2. Boot the same root at HEAD.
    3. Assert the boot completes and `(:seon.cache/max-bytes (seon.cache/status))` equals the compiled value.

- **Default cannot boot current HEAD, and this lane did not cause it.** Root restarted default onto HEAD at 21:08Z.
  The boot of pid 80850 was refused during task indexing: "seon.program is refused: handle-mismatch".
  - The cause is task `0d8be4a76ab3` in `resources/seon/tasks/seon.program.edn`, added by contract-cost in
    `2d047c99c`.
  - Its handle must be `(seon.issue/task-handle 'seon.program "a-defn-scans-every-projection-form-per-call"
    :function)` = **`400bcf52dd53`**, evaluated in the JVM.
  - The operator fell back to commit `6ab830be…` (pid 81135), which does not contain `seon.cache`.
  - Root fixes that id and restarts.
- **Live evidence from pid 80850 before it was refused.** The failed boot did load `seon.cache` and served
  `runtime_status` with `:seon.cache/status`: budget 512,000,000 B and 32,307,200 B weighted. Per kind:

  | kind | misses | hits | bytes (est.) | load |
  |---|---|---|---|---|
  | `seon.schema.edn/packaged-population` | 1 | 15 | 1,327,500 | 86 ms |
  | `seon.schema/declaration-projection` | 2 | 5 | 4,754,100 | 174 ms |
  | `seon.schema/projection` | 2 | 0 | 26,225,600 | 504 ms |

  So the new classpath resolves, the namespaces load, the dial's declared default applies and the status block
  renders. Hits after boot were not observed.

- **RESET NEEDED** (once the task id above is fixed). Caffeine 3.2.4 is a new dependency. Default (pid 75849) does not have it on its classpath, so it
  cannot load `seon.cache` or any namespace that requires it: `seon.schema`, `seon.schema.edn`, `seon.program`,
  `seon.cluster` and `seon.cluster.boot`.
  - An in-place adoption of `92b22cbf1` would fail its reload with `ClassNotFoundException`. Only a restart onto
    the new classpath works.
  - The dependency-class cache misses on that start, because the classpath changed.
- **Nothing that uses `seon.cache` has run inside Seon.** The owner itself was proven in a bare JVM that holds only
  Clojure and the Caffeine jars (below). The converted call sites were not proven live:
  - `build-projection`, `declaration-projection`, `packaged-population`, `authored-shapes`;
  - the boot dial and the status block.

  Static checks passed. Root's post-restart runs are listed at the end.
- **The size rule was exceeded.** S1's source is +244 / −50, net **+194**. The design estimated +50.
  - `src/seon/cache.clj` is 197 lines, 178 of them non-blank and non-comment. Much of that is contracts and
    docstring headings, but it is still larger than the 70-line prototype.
  - The design's net-deletion slice is S2, in `db.clj`, which another lane holds. It is not landed, so S1 stands
    alone as net positive.
- **The process cache is a `defonce`, which departs from the design's "process roster, never a defonce".**
  - Seon has no process roster.
  - The first projection is built at namespace load (`seon.schema.edn/load!` in `seon.cluster.registry`), before
    any store is open.
  - So the process scope is `seon.cache/process`. It is a delay built from the dial's declared default, and boot
    applies the configured value with `configure!`.
  - Callers use the process arity. Tests pass their own cache value, so no test touches the process cache.
  - Root ruling wanted if this is not acceptable.
- **The dial is per cluster, but the cache is per process.** The last cluster booted in a JVM applies its
  `:seon.config.cache/max-bytes`. Clusters from one checkout share the declared default (512,000,000 bytes).
- **Stopped, because the cause was not clear enough to convert mechanically. Root decides.**
  - **#16–#18 (`schema/projection-cache-value`, 42 call sites).** To move this into `seon.cache` the key needs a
    projection content digest.
    - The `:seon.schema.projection/fingerprint` is an xor of 64-bit hashes. It is absent on declaration
      projections, and it does not cover predicate bindings, so it is not safe as a cache key.
    - Keying on the projection object would retain whole projections through keys the budget never weighs.
    - This needs S2's declaration digest first.
  - **#12/#13 (`seon.test.runner` `reach-holders` / `reach-lineage`).** The value is an atom that a request widens
    incrementally by compare-and-set, with a lineage pointer map. That is an accumulating index, not a content
    derivation. Moving it into `seon.cache` unchanged would keep an atom in a cache. Making it content-keyed is a
    design change to reach selection.
  - **#19 (`seon.render/shared-cache`, 36 uses across `render`, `walk`, `web` and `transcript`).** Its entries are
    validated by read evidence on each read, not keyed by content. The design's S4 re-keys pages by program digest
    and deletes `invalidate-runtime-derived-state`. That changes render semantics, and it has to be proven at a REPL
    that can load `seon.cache`, so it waits for the restart.
  - **#1–#4 (`db.clj`) and #9 (`program-identity-cache`, which evicts and re-points an alias).** These are held by
    one-commit-a-opus and under active change. They are S2, a design slice rather than a mechanical conversion.

## The branch-write cost root asked about (task 4bb788513862): cause found, fork patch written

perf-admit-tests measured 350–440 ms for the first writes on a fresh branch, against 3–6 ms on the committed value.

**The per-connection Datahike node LRU accounts for it.**

Evidence, one bounded read-only JVM probe on default. Branch `cache-central-opus` was created with the MCP `branch`
tool and given one SCI evaluation:

| | main connection | fresh branch connection |
|---|---|---|
| decoded nodes held | 1,000 (the cap) | 282 |
| konserve reads since open | 742 | **254** |
| of the branch's nodes, already decoded in main's LRU | — | **252 of 282** |

- A konserve read plus decode of one 4096-way leaf measured **2.1 ms** (37 leaves in 78.6 ms).
- So about 252 × 2.1 ms ≈ **530 ms** of the branch's early work re-decoded nodes the store already held.
- It also duplicated them on the heap. MAT measured about 350 KB per leaf, so about 88 MB for this one branch.

**Refuted as the main cost:** `seon.db/declaration-content-key`, which re-reads every declaration datom (28,043
datoms) on a fresh connection because the projection memo's revision key includes the connection id. It costs 1.9 ms
on the branch against 2.2 ms on main.

- It is still a waste the S2 slice deletes, but it is not the 350 ms.

**Fix: landed by root as `fc0ff40b9`** (fork `67b00071`, a port of upstream `d2b9e525`: one node cache per physical
store and threshold, reserved in the connection registry). It landed while this lane was writing its own patch, so
that patch is withdrawn and deleted. The measurements above are independent evidence for the cause it names.

The design's other S5 items are not done:

- a byte-like weigher (datom count) for the node LRU;
- `:store-cache-size` from Seon configuration.

## Slice 1: `92b22cbf1`

**Added:**

- `deps.edn`: `com.github.ben-manes.caffeine/caffeine 3.2.4`, which resolves offline together with jspecify 1.0.0 and
  error_prone_annotations 2.49.0.
- `src/seon/cache.clj`:
  - `lookup`: `[kind content-key weigh derive-fn]`, an optional `retain?`, and an explicit-cache arity;
  - `create`, `configure!`, `status`;
  - `retained` (slice 1b, below).
- `resources/seon/schemas/seon.cache.edn`: the cache predicate and the status shapes.
- `resources/seon/schemas/seon.config.cache.edn`: the dial `:seon.config.cache/max-bytes`, an int with declared
  default 512,000,000.
- `seon.cluster.boot`: applies the dial once `boot-dials` are read.
- `seon.cluster/mcp-runtime-observation` and its AI face: `:seon.cache/status`. That is about 12 kinds × 25 tokens,
  roughly 300 tokens at full adoption.
- `test/seon/cache_test.clj`: four tests covering
  - compute-once under 8 threads;
  - a nested lookup across kinds;
  - failures, refusals and nil not retained;
  - eviction under the byte budget, with per-kind bytes following it.

**Converted, with the old holder deleted in the same commit:**

| site | was | now (kind; weight per insert) |
|---|---|---|
| `seon.schema/build-projection` (#6) | `built-projections`, LRU 8, `lookup-or-miss` | `:seon.schema/projection`; 3,700 B × forms |
| `seon.schema/declaration-projection` (#5) | `declared-projections`, LRU 8 | `:seon.schema/declaration-projection`; 1,300 B × forms |
| `seon.schema.edn/packaged-population` (#7) | `packaged-populations` LRU 2 + size constant | `:seon.schema.edn/packaged-population`; 375 B × forms |
| `seon.program/authored-shapes` (#8) | `authored-shapes-memo` LRU 2 | `:seon.program/authored-shapes`; 4 KB × families |
| `seon.render.value/throwable-safe` (#14) | unbounded `memoize` by `Class` | deleted: `throwable-safe?` is asked per printed link (four `getMethod` calls) |

**Other changes in the commit:**

- Tasks `f20577d4eb4c` (projection stampede and swap-per-hit) and `6e5637a4afc8` (throwable-safe pins classes) are
  closed, which means removed from `resources/seon/tasks/`.
- `AGENTS.md` (which `CLAUDE.md` points to): the cache law was rewritten in place to "One central cache, bounded and
  configured…".
- Two tests used to clear the global population memo with `cache/seed`, which is JVM-global test state. They now
  measure one derivation directly (`resource-population`) and assert that operations read at most that.

**Lines** (source, `src/`): +244 / −50. Tests: +90 / −24. Resources: +28 / −16.

### Measurements

The owner was measured in a bare JVM with `-Xmx256m`, holding Clojure 1.12.5 and Caffeine only, unarmed
(`tmp/cache-central-opus/bare_probe.clj`).

| probe | before (design bench, core.cache) | after (`seon.cache`) |
|---|---|---|
| 8 concurrent misses of one key | 8 derivations | **1** (7 hits, 1 miss) |
| hit, one thread | `lookup-or-miss` 408–858 ns | **88–120 ns** (`getIfPresent` + join + counters) |
| budget 1,000 B, 20 × 300 B inserts | no byte bound | 900 B retained, 17 evictions, per-kind bytes = weighted bytes |
| thrown derivation | stored as a failed delay until evicted | not retained; the retry derives |

The first bare run exposed a problem: Caffeine logs a `WARNING` for every exceptionally completed future, which would
mean one warning per projection refusal. `lookup` therefore completes the removed future normally, carrying the
throwable, and waiters rethrow it.

**Heap and live hit/miss before and after: not measured.**

- Before: the design's inventory, from pid 59671/70849. Rows #5 +9.0 MB, #6 +51.9 MB, #7 1.32 MB; the MAT figures
  are in the design.
- After: needs the restart. Root reads `:seon.cache/status` in `runtime_status` after boot and after one agent turn.

### Static checks

- clj-kondo `--lint src script test`: 0 errors.
- The contracts compile check over the seven changed source files, run on a `git archive` of HEAD plus the diff
  (`tmp/cache-central-opus/snap`): **0 findings** (1,288 ms, one projection build).
- `seon.config-test/checkout-effective` on the snapshot: it refused at first, because the predicate
  `seon.cache/cache?` is not loaded in default, for want of Caffeine. With that one predicate swapped to
  `clojure.core/map?` in the scratch copy, it returned an effective map with `:seon.config.cache/max-bytes 512000000`
  in 508 ms. After restart, root runs it unmodified.

## Slice 1b: `retained` (committed with this note)

- `src/seon/cache.clj`: `retained`, the kind's completed values by content key, for diagnosis and retention tests.
- `test/seon/cache_test.clj`: a test for it (bare JVM: 4 tests, 19 assertions, 0 failures).

## Patches for root (held files)

- **`tmp/cache-central-opus/sci-eval-s1.patch`**: `src/seon/sci/eval.clj` together with
  `test/seon/cluster/branch_release_retention_test.clj`. It is +35 / −48 and applies to the current tree
  (`git apply --check`).
  - `base-context-cache` becomes `seon.cache` kind `:seon.sci.eval/base-context`, weight 6.9 MB each (MAT). Four
    fit the budget, as before.
  - `refusal-recording-cache` becomes kind `:seon.sci.eval/refusal-recording`, weight 256 B.
  - The try/evict wrappers are deleted, because failures are never retained.
  - The base-context half of `program-cache-policy` is removed.
  - The retention test reads `seon.cache/retained`.
  - Depends on `retained` (above).
  - `program-identity-cache` stays. Its evict-and-re-point is S2's alias kind.

## Coordination: validators as a kind (contract-cost-opus)

Compiled Malli validators and explainers can become a kind now, with no further API:

```clojure
(seon.cache/lookup :seon.instrument/validator [schema-form-or-digest registry-identity]
                   (constantly <calibrated-bytes>) #(m/validator schema options))
```

- Key on content: the schema form, or its definition digest, plus the registry's identity. Never key on a Var or a
  live schema object.
- Weigh with a per-kind calibration.
- A thrown compilation is not retained.
- Per-kind hits and misses appear in `runtime_status` under `:seon.cache/status`.

## What root must run (after the restart onto `92b22cbf1`)

1. `bin/seon down && bin/seon start` (RESET NEEDED: classpath). Record the boot time; the dependency-class cache
   misses once.
2. `bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection`
3. `bin/test-check default --test seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn`
4. `(seon.config-test/checkout-effective "/Users/sean/src/seon")` at the REPL.
5. `bin/test-check default --ns seon.cache-test`
6. `bin/test-check default --ns seon.schema.declaration-population-test`
7. `bin/test-check default --ns seon.sci.admit.declaration-population-test`
8. `bin/test-check default --test seon.schema-test/equal-packaged-populations-reuse-the-shape-projection`
9. MCP `runtime_status`: read `:seon.cache/status` at boot and after one agent turn. Expect kinds
   `:seon.schema/projection`, `:seon.schema/declaration-projection` and `:seon.schema.edn/packaged-population`, with
   misses of 1–3 each and hits climbing, and weighted bytes of about 13–60 MB.
10. After applying the patches: `bin/test-check default --test seon.cluster.branch-release-retention-test/released-branch-node-caches-are-collectable`.
    Then repeat perf-admit-tests' fresh-branch write probe. The target is the 3–6 ms committed-value figure,
    against 350–440 ms today.

## Cleanup

- The branch `cache-central-opus` was retired through the MCP `branch` tool. Retire returned an NPE during the
  21:08Z restart window and succeeded afterwards.
- The JVM-mode session `cache-central-opus` read only.
- The bare-JVM probes and snapshot are under `tmp/cache-central-opus/`.
