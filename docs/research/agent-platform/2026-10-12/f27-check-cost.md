# F27 one interpreted test check cost

Lane `f27-check-cost`; model line `gpt-6-astra`; 2026-10-13 UTC.

**Accepted measured win: 236.32 ms / 369.35 MB → 97.06 ms / 179.83 MB** on the same one-interpreted-test check (59% less time, 51% less JVM allocation). Root ruled that measured wins should be committed and the retained regression should guard this result at **200 ms / 220 MB**, rather than treating the earlier 100 MB target as a blocker. The remaining whole-program setup is `seon.test/one-test-run-repeats-whole-program-setup`; it needs a new red proof before assignment.

## Initial live evidence

All default SCI profiling used agent `f27`, whose first form read `seon.context/current-state`. The original default source was `6299bcda4d2157a2a6c9de43fe8fbf99807a89c2`. This forced a fresh interpreted run on its branch:

```clojure
(dissoc
 (seon.profile/measure
  (fn []
    (my.test/check
     {:seon.test/identities
      #{'seon.render.block-test/stable-surface-addresses-are-injective}
      :seon.test/rerun? true})))
 :seon.profile/called)
```

Run `c5a67d7833ce`: 574.17 ms / 1149.24 MB JVM window, with an interpreted body of 48.25 ms / 62.31 MB. Subsequent checks were 417–430 ms / 876–943 MB. The historical 2.1–2.4 s / 4.0–4.5 GB did not reproduce after restart. JFR attributed dominant inclusive allocation to `seon.sci.eval/interpret-source-namespaces!` (191.6 MB), `seon.sci.eval/install-selected!` (205.2 MB), and `seon.sci.eval/install-caller-host-bindings!` (104.7 MB); totals overlap. Eagerly interpreting the helper namespace's breadth was the dominant confirmed cause. An earlier attempt to defer the loaded-database read alone measured no reproducible gain and was rejected.

Library seams inspected: SCI `sci.core/fork` at `eb3290d5c5ed72133ae1947492ecd324413868ed` isolates generation-owned bindings; Datahike `datahike.versioning/commit-as-db` at `c9e57c6fb65365d5ce92b59854fb02ea59054fe7` preserves attached cache identity; Malli `malli.core/-instrument` and `malli.core/validator` at `dd13ff81139159778edb0a67b491eccc0baa991d` own instrumentation and validation. No dependency change was made.

## Approved lazy source acquisition: implementation and proof

`seon.sci.eval/interpret-source-namespaces!` now binds ordinary functions with the existing `seon.sci.eval/bind-lazy-members!`; `seon.sci.eval/lazy-root` and `seon.sci.eval/promote-member!` interpret a helper when called. Stored `:seon.fn/macro?` excludes macros from those lazy roots even before their SCI Vars exist. Values and macros retain the existing file-order installation, and tests retain the existing namespace order. There is no new cache or mutable registry.

`seon.sci.eval/install-caller-host-bindings!` obtains each target namespace's interns once within an entry. Its missing-core selection reads `clojure.core/ns-map` directly instead of first constructing all of `clojure.core/ns-refers`. Clojure's two functions were inspected at `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`; `ns-map` returns the held mapping, while `ns-refers` builds a new filtered persistent map. The selection admits only Vars owned by `clojure.core`, so imported classes are not candidates.

Both parent and candidates used the same end-to-end `seon.profile/measure` probe on a quiet disposable JVM, with one warmup. Initially the assertion was 200 ms / 100 MB; root subsequently approved 200 ms / 220 MB to guard the demonstrated gain with a small margin. Every measured run executed one interpreted body and recorded its run. Default was not used for candidate loading or timings.

| Candidate | Run | ms | JVM MB | Result |
|---|---|---:|---:|---|
| Parent `7deab63f93736ae4dc928d9a4b4ea23d0dc2b9cd` | `d489bb307bd4` | 236.32 | 369.349136 | time and JVM allocation red |
| Lazy binding + target dedupe `6216f3ec9cc…` | `b7dc5e5c4b90` | 128.68 | 193.064264 | allocation red |
| Same with JFR `f14b1aa261ae82dc93b050a14124464b86c90f3a` | `ff6a4eea3711` | 107.76 | 191.847016 | allocation red |
| Also direct core selection `2e86066ea0f361e9a7339f0df78679df9d6a18d8` | `450e499504a5` | 92.05 | 181.052304 | allocation red |
| Repeated, same production change `335783b2d6aec38a9cd948cae53885a5d90a2d03` | `8bca12657345` | 97.06 | 179.833360 | allocation red |

Supporting counts: `seon.sci.eval/install-row!` 96 → 12; `seon.sci.eval/loaded-database` 91 → 7; vector queries 93 → 35. The calling thread stayed around 96–98 MB and missed the child thread's cost, demonstrating why the JVM-window assertion is the regression proof.

### Remaining allocation

`tmp/f27-lazy-deep.jfr` used 256-frame allocation stacks. Samples within epoch milliseconds 1791850257003–1791850257114 totalled 208.55 MB versus the exact approximate-counter window of 191.85 MB; these are samples, not exact accounting. Inclusive totals overlap.

- `seon.test/program-overlay-tx` / `seon.test/digest-differences`: about 72.28 MB inclusive, scanning declaration digests despite a tiny selected body.
- `seon.db/vector-query-call`: 73.33 MB nearest-Seon-frame allocation.
- `seon.sci.eval/install-caller-host-bindings!`'s `own-bindings`: 38.76 MB nearest-Seon-frame allocation before the final direct-core selection; stacks specifically name `clojure.core/ns-refers` and `clojure.core/filter-key`.
- `seon.test.runner/projection-form-keys`: 22.91 MB; whole-registry key sets for the live schema comparison.
- `seon.test.runner/set-drift`: 12.57 MB; whole-program ambient comparisons.
- `seon.test.runner/reach-names`: 12.57 MB.

Removing eager helper interpretation fixed its measured producer. Root accepted this measured win at a 220 MB regression bound; further reductions belong in repeated test setup/comparison producers, recorded as `seon.test/one-test-run-repeats-whole-program-setup`. F55 remains a separate Datahike fork/restart-batch task; no dependency was changed.

### Named verification

The final strengthened namespace-order test passed **5 assertions**, run `88de2d0c284e`, candidate `1950b511173f4a0200b13c4c89f4bbbe22dd4987`. It checks the eager value dependency, stored macro metadata, actual macro expansion, and a private source helper's result after lazy promotion. An initial attempted assertion about an automatically indexed macro-generated test Var was replaced with an actual macro-expansion assertion; no production workaround was added.

These named behavior tests were green in the candidate checks (the broad tiers were not run):

- `seon.sci.eval-test/a-test-namespace-def-reads-the-def-above-it-in-the-file`
- `seon.sci.eval-test/a-test-namespace-requiring-a-dev-namespace-interprets`
- `seon.sci.eval-test/an-interpreted-test-namespace-resolves-the-core-names-sci-lacks`
- `seon.sci.eval-test/a-required-librarys-private-var-resolves-by-var-quote`
- `seon.sci.eval-test/an-interpreted-namespace-runs-its-fixture-around-a-deftest`
- `seon.sci.eval-test/a-branch-that-wrote-no-program-keeps-the-program-of-the-commit-it-opened-at`
- `seon.sci.acquisition-cost-test/a-data-only-commit-reuses-the-program-overlay`
- `seon.sci.branch-execution-test/an-interpreted-caller-resolves-its-compiled-namespace-aliases-privates-and-core`
- `seon.sci.branch-execution-test/interpreted-callers-resolve-unchanged-names-to-compiled-vars`
- `seon.repl-differential-test/a-file-proof-aliasing-its-subject-runs-green-on-the-branch`
- `seon.repl-differential-test/an-inherited-file-caller-sees-the-branch-redefinition-of-its-helper`
- `seon.repl-differential-test/a-file-test-runs-the-branch-redefinition-of-a-file-function`
- `seon.repl-differential-test/an-inherited-test-sees-the-branch-redefinition`
- `seon.repl-differential-test/an-inherited-test-sees-the-branch-redefinition-of-a-contracted-callee`

**Pre-existing red:** `seon.repl-differential-test/promoting-a-closure-costs-its-walk-and-the-callers-called`. Parent: 729,517,496 bytes against 209,190,912 for the widely called seed, hub 2046.12 ms against 1000. Candidate: the allocation assertion passed, but hub 1689.78 ms remained red. This was reproduced on parent rather than blamed on another lane. Its existing task is `seon.sci.eval/promoting-a-closure-allocates-megabytes-per-caller-and-runs-twice`.

**Additional pre-existing red:** `seon.task-ingestion-test/an-assignment-reads-only-its-proofs` failed the first lane gate (`ba9bd65d9901`) at 69,259,968 calling-thread bytes against 8,388,608. A parent-only check on `c05059e0606e738455b1e6fd90d05400eb6267b5`, run `d8e12bba8caa`, reproduced it at 96,751,128 bytes. Its existing task `seon.task/an-assignment-reads-only-its-proofs` already records this cache-sensitive bound. The test file's change is only the retired-task comment reference; the test is excluded by var. The first gate otherwise passed 59 assertions, including the F27 probe at **151.41 ms / 181.805552 MB** under the approved 200 ms / 220 MB bounds. The final gate selects 17 tests, including task publication and ingestion.

`clj-kondo --lint src script test --cache false`: **0 errors**, 1645 warnings, 7389 ms. `git diff --check` passed. Patch size: source +25/−24 (net +1); tests +30/−8 (net +22). The old gigabyte-cost task and its tags are retired under the accepted 220 MB bound. The remaining setup task is referenced beside the assertion and from the older turn-cost task. The lane commit gate runs the named green tests; the pre-existing closure-cost red above is excluded by var.

### Foreign boundary and operations

Root independently restarted default and advanced HEAD during this work; this lane never stopped, reset or restarted it, or loaded uncommitted source into it. At the final commit preparation default ran `1d538ec208dde1e3394accdcfef1012a4f57fdfa`, PID 69901, with existing five untested-hot warnings and one unowned namespace, no missing readiness layer. Foreign edits include the `merge-lint` lane's `test/seon/cluster/write_back_test.clj`; all were left alone. Every candidate is HEAD plus the named owned paths. There was no foreign source failure blocking this slice. No other lane was contacted, resumed or edited, and no worktree was created.

Host-bound evaluator edits were proved by publication/reload on disposable branches and throwaway candidate JVMs through `bin/seon check`; never by loading them into default. Read-only JVM queries used root as required by that tool, never root SCI. The f27 SCI branch recorded its end through `my.agent/retire-branch!`, returning `:my.agent/retiring? true` for `:agent-b8359c2c20b4`; root owns eventual deletion. Owned check launchers exited and their operator-owned JVMs were cleaned up.

## Reproduction and evidence

The regression is `seon.test-test/one-interpreted-test-run-allocates-a-bounded-multiple-of-its-body`. It runs one interpreted `seon.id-test/a-host-object-has-no-identity` through `seon.test/run` on a branch execution handle, warms once, then measures the entire request including recording. The optional `SEON_F27_MEASUREMENTS` path writes bounded scalar measurements, selected call counts and the top eight self-time entries through the value printer; the measurement code is committed with the regression.

```sh
SEON_F27_MEASUREMENTS=tmp/f27-parent.edn bin/seon check test/seon/test_test.clj --test seon.test-test/one-interpreted-test-run-allocates-a-bounded-multiple-of-its-body
SEON_F27_MEASUREMENTS=tmp/f27-change.edn bin/seon check src/seon/sci/eval.clj test/seon/test_test.clj --test seon.test-test/one-interpreted-test-run-allocates-a-bounded-multiple-of-its-body
```

The parent command describes reproducing before this slice's commit; after it, use the corresponding parent archive. JFR startup options: `-XX:FlightRecorderOptions=stackdepth=256 -XX:StartFlightRecording=settings=profile,filename=/absolute/path/probe.jfr,dumponexit=true`. Filter `jdk.ObjectAllocationSample` events to the `:began`/`:ended` epoch milliseconds emitted by the probe.

Preserved local raw evidence: `tmp/f27-lazy-deep.jfr`, `tmp/f27-parent-current.edn`, `tmp/f27-lazy-host.edn`, `tmp/f27-lazy-final-scope.edn`, and their check logs. Operator diagnostics: `data/clusters/default/logs/operator-check-20261013T071533.523.edn`, `operator-check-20261013T071612.893.edn`, `operator-check-20261013T071827.448.edn`. Raw sampled allocation evidence from the first investigation is `f27-allocation-summary.edn`.

**RESTART NEEDED** for the compiled evaluator change. Root runs the broad tiers after its batched restart; this lane proves the named slice.
