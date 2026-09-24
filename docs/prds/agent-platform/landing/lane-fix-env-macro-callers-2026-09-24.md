# Lane fix-env-macro-callers (2026-09-24)

`seon.fn/host-bound-callers` (`src/seon/fn.clj`) derives `:seon.fn/host-bound?`
from each declaration's own clj-kondo facts. It covered unadmitted classes and
`reify`/`proxy`/`definterface`/`import`. It did not cover a JVM macro that SCI
cannot expand, so affected callers using `async/go-loop` were classified as
interpretable and refused the branch acquisition. The fix adds that case as one
more fact about the declaration's own body.

## Still broken

- **Stored rows stay stale until their file is republished.** Publication
  (`seon.cluster/full-source-refresh!`, `cluster.clj:1993`) reanalyzes only
  changed paths. A change to the indexer (`fn.clj`) does not reindex the files
  whose rows it derives. On default the six test rows (`seon.cluster.turn-test/render-proc-for`,
  `.../with-render-context-proc`, `seon.cluster.agent-test/with-connection`,
  `seon.render.web-test/with-server`, `seon.turn-loop-test/with-render-context-proc`,
  `seon.gen.loop-test/with-render-context-proc`) and five src rows still hold
  `:seon.fn/host-bound? false` until those files change. That is a
  publication-owner defect (derived facts are not keyed by their producer's
  code), and it is outside this lane (`cluster.clj`).
- **The rule is conservative.** Only macros that read `&env` actually fail.
  Probe: `go-loop` in a bare SCI context gives "Symbol cannot be cast to
  Compiler$LocalBinding". A copied `taoensso.timbre/warn` expands and logs. No
  analysis fact says "reads `&env`": `go-loop` does not read it, and it expands
  to `go`, which does. So every JVM macro from a namespace SCI does not
  interpret counts as host-bound. On the whole program, 11 rows change from
  false to true: the six test rows above, plus `seon.cluster/serve!`,
  `seon.cluster/warn-low-space!`, `seon.render.web/failed-page-result`,
  `seon.render.web/unreportable-page-result` and
  `seon.render.retained-test/replace-renderer!`. I did not attribute each of these five to its macro; the dependency macros in use are timbre, `sci.core/binding`/`ns`, test.check and clj-kondo cache macros. An unchanged
  host-bound row binds its compiled Var, so behaviour is the same. A branch that
  changes one of these rows now gets a by-name refusal instead of an
  interpretation.
- **clj-kondo gives no macro fact for dependency macros.** Its cache has no
  dependency analysis (`.clj-kondo/.cache/v1/clj` has no `clojure.core.async`).
  Of 14,936 dependency-macro usages, 164 arrive with no `:macro` flag, including
  every `go-loop`. The macro fact is therefore the loaded Var's `:macro` metadata
  (`host-macro?`). That is the same Var `seon.sci.eval/install-caller-host-bindings!`
  copies into SCI. This makes the stored classification depend on the target
  namespace being loaded when indexing runs. If the namespace is not loaded, the
  row stays interpretable, and SCI then refuses with "Unable to resolve" rather
  than LocalBinding.
- **The `seon.fn-test` namespace run is red for foreign reasons** (run 1b7aff7fbab1,
  68 executed, 8 fail / 8 error).
  - Fixture writes refused on `:seon.agent/branch` and
    `:seon.program/definition-digest`, plus `StackOverflowError` and
    `:malli.core/invalid-schema`.
  - `indexing-resolves-its-declaration-world-once-per-operation`:
    `packaged-forms-calls` is 2 instead of 1. It was already red before this
    lane (lane-fix-fn-correctness-2026-09-24.md:35).
  - `analyzer-edges-are-readable-symbols-or-no-edge`: the write refusal has no
    `:seon.error/member`.
  - One red was mine: `the-indexer-emits-no-attribute-the-program-row-schema-drops`
    called the private `analysis-rows-by-file` with four arguments. I converted
    that call (+1 line). It is green in the adoption gate (run 5b181d9da16d).
- The regression `a-dependency-macro-sci-cannot-expand-is-host-bound` was committed
  inside 87b9d3bcb (lane sweep-hand-built-ns-rows committed the whole
  `fn_test.clj`, including my uncommitted hunk). This lane's commit carries only
  the one-line caller conversion in that file.

## Change

`src/seon/fn.clj` (+63/−22):
- `host-macro?`: the loaded Var named by kondo's resolved target has `:macro`
  metadata.
- `interpreted-namespaces` is the union of:
  - SCI's `sci.impl.namespaces/namespaces` keys,
  - the namespaces of `seon.program/base-context-injected-symbols` (`clojure.test`,
    `seon.bootstrap`),
  - the first-party functions' namespaces,
  - the analysis's own namespace definitions.
- `host-bound-callers [analysis first-party-functions projection]` adds one pass
  over var-usages: a usage of a host macro outside `interpreted-namespaces` makes
  its caller host-bound. The projection is threaded explicitly through
  `analyzed-form` and all three `analysis-rows-by-file` callers.

`test/seon/fn_test.clj`:
- The regression, committed in 87b9d3bcb: `looping` (uses `async/go-loop`) is
  host-bound, while `channel` (`async/chan`) and `plain` are not.
- +1 line: caller conversion.

## Evidence (default pid 99265)

- The REPL probe (`tmp.fix-env-macro-callers/probe`, JVM mode) works on a disposable Datahike branch of
  default at its current commit. It stamps a new definition digest on
  `seon.render.hiccup/class-value`, the issue's reproduction, then runs
  `eval/acquire!` and deletes the branch.
  - **Before** (stored facts): 5 acquisition refusals, all "cannot be cast to
    Compiler$LocalBinding", for `agent-test/with-connection`,
    `turn-test/render-proc-for`, `turn-test/with-render-context-proc`,
    `web-test/with-server` and `turn-loop-test/with-render-context-proc`.
    Acquisition took 1,052 ms, and 955 ms on a repeat.
  - **After** (the same branch, with those files' `:seon.fn/host-bound?`
    recomputed by the fixed `seon.fn/rows` and written to the branch): 0
    refusals, and acquisition took 722 ms.
  - Six rows changed on the branch. The after run writes the producer's output
    onto the branch because default's rows are stale (see above).
- `bin/test-check default --test seon.fn-test/a-dependency-macro-sci-cannot-expand-is-host-bound`
  passed, run 00f2b378230a, 1,052 ms. Before publication it failed in the save
  gate with `looping false` (run 8ad06d6ae0a6).
- `--test seon.fn-test/host-binding-is-a-declaration-fact-not-a-caller-closure`
  passed, run 140a48f6571e, 998 ms.
- Cost:
  - `host-bound-callers` over the whole program (src+test, 158,782 var-usages)
    takes 41 ms, including the armed wrapper. The whole analysis it reads takes
    5.7 s.
  - `seon.program/base-context-injected-symbols` takes 0.49 ms per call.
  - `seon.fn/rows` for `turn_test.clj` alone takes 415 ms.
  - I took no parent measurement of the indexer on the same probe. The added
    pass is linear in var-usages and does no DB work.

Net lines: +64/−22 (source and test). RESET NEEDED: no. No branches were left:
each probe deleted its branch in `finally`.

## Limits

- Hook publication raced other lanes. The first publication of the fn.clj edit
  refused on another lane's `tmp/fn-test/...` fixture file, and the second on a
  stale source head. The third published.
- The acquisition proof on default itself waits for the stale-row
  republication described above.
