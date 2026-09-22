---
type: landing
status: landed-on-refactor-agent-platform
created: 2026-09-23
tags: [agent-platform, errors, cause-chain]
---

# Lane error-cause-chain (census F0), 2026-09-23

Authority: `docs/research/agent-platform/swallowed-errors-census-2026-09-23.md` F0.
Owner, 2026-09-23: "Do not allow swallowing of errors" (AGENTS.md "No swallowed
errors"). Orchestrator rulings (1)-(3) from the lane launch.

## What changed

- `seon.error.refusal/diagnostic` (`src/seon/error/refusal.clj`) is accreted in
  place. With `:seon.error/throwable` supplied it now also records
  `:seon.error/chain` from `Throwable->map`
  (`reference-code/clojure/src/clj/clojure/core_print.clj:473`), takes
  `:seon.error/frame` from the ROOT cause, and supplies the root message when
  the observation has no message. A stated message is preserved. No new
  constructor exists and no caller changed.
- New public readers in the same namespace: `chain` (one link per throwable,
  outermost to root: `:seon.error/throwable-class`, `:seon.error/message`,
  `:seon.error/data` = ex-data, `:seon.error/frames` = that link's own
  first-party frames), `root-frame`, and `frame-function`. `frame-function` is
  the rule `seon.error/stack-failing-function` already applied. It moved here
  whole with its machinery prefixes, and `stack-failing-function` now calls it.
  First-party means the function namespace starts with `seon.` or `my.` and is
  outside those prefixes.
- `seon.error/prepare` records the same chain on its observation. It takes the
  frame from `error.refusal/root-frame`. The private `top-frame`, which read the
  outermost frame, is deleted. The D13 signature now keys on the root frame.
- Schema (`resources/seon/schemas/seon.error.edn`): `:seon.error/chain`
  `[:vector {:min 1} :seon.error/link]`, `:seon.error/link` (map above),
  `:seon.error/frames` `[:vector {:min 1} :seon.error/frame]`, and one optional
  `:seon.error/base` member `[:seon.error/chain {:optional true}]`. The ref-typed
  `:seon.error/cause` is untouched. ex-data reuses `:seon.error/data :map`, the
  registry's existing declaration for error-carried data. Its siblings
  `:seon.schedule/handler-exception-data`, `:seon.program/read-exception-data`
  and `:my.shell/process-exception-data` declare ex-data as `:map` too. No
  `:any` was added. The chain is evidence, not an attribute. An optional member
  whose value has no Datahike type is excluded by the bridge
  (`src/seon/schema/datahike.clj` `compiled-attribute-selection`), so it rides
  the observation and its admitted evidence content (`data-edn`/blob).
  `seon.error.occurrence.edn` needed no change.
- `seon.ai/cause-chain` is retired. `truncation` and the unreadable-JSON arm of
  `send-request` now hand the Throwable to `refusal/diagnostic`, so the chain
  arrives as `:seon.error/chain`. `::cause-chain` is no longer written.
- Error-owner swallows fixed:
  - `error.clj` `fact-source` (census `:762`) caught Throwable and kept only
    `ex-message`. It now catches Exception and returns `refusal/diagnostic` with
    the throwable, so the chain, root frame and class are kept.
  - `error.clj` `refusal-data` (census `:1061`) was `(catch Exception _ nil)`
    around `m/schema`. It now keeps only Malli's declared schema-construction
    refusal as data: an ex-info whose `:type` is a `malli.core` keyword
    (`reference-code/malli/src/malli/core.cljc:203`, pin
    `606083c5c5b388e84d169c7080af33ed3ec242ae`). Every other failure is
    rethrown.

## R-PRED ruling (orchestrator, recorded for the census's later sweep)

A total predicate catches ONLY the dependency's declared exception for "not
parseable". Everything else propagates. This slice does not apply the ruling to
any R-PRED site.

## Evidence

- Census probe, rerun after the fix (`tmp/error-cause-chain/probe.clj`,
  `clojure -M`, 0.55 s wall; `diagnostic` 2.9 ms cold). The wrapped ex-info now
  gives 2 links. The wrapper link is `"wrapper"` with `{:seon.probe/outer 2}` and
  4 first-party frames. The root link is `"Keyword cannot be cast to Number"`
  with `{:seon.probe/leaf 1}` and 6 first-party frames. The message is the root
  message. The frame is `[seon.probe$inner invokeStatic "probe.clj" 4]`, the
  root, where before it was the wrapper's. Before the fix the census recorded
  keys `(at exception-class frame layer operation)`, the wrapper frame, and
  `ex-data kept? false false`.
- Incremental schema adoption (`tmp/error-cause-chain/adoption-proof.clj`, MCP
  JVM eval in default pid 43581, 1,146 ms total):
  - The proof branched default's store at its head (`:error-cause-chain-proof`,
    24 ms).
  - It transacted only the 4 changed canonical declaration rows (`base chain
    frames link`, all `:seon.schema/generatable? true`) through
    `seon.db/transact!`: committed, 864 ms.
  - The branch projection took 152 ms to derive. It validated the census
    diagnostic and refused a link with no class. `:seon.error/chain` is not a
    database attribute.
  - The proof then released and retired the branch. A roster check afterwards
    read false. Default's own branch was never written, nothing was adopted,
    and nothing was reset.
  - The change is additive and retires nothing, so the 1.3e retirement refusal
    has no surviving writer to name.
- Two R-DIAG sites checked, HEAD `cd95c73f5` + lane paths, git-archive snapshot
  with `reference-code` linked (`tmp/error-cause-chain/rdiag-probe.clj`):
  - `seon.effect/handler-failure` (effect.clj:629, reached from the `:931` and
    `:935` catches) on an ExecutionException wrapping an ex-info returns both
    links (class, message, ex-data, frames) and the root frame
    `[seon.rdiag_probe$leaf invokeStatic ...]`.
  - The `seon.schedule/invoke-handler` catch form (schedule.clj:644) gives 2
    links and the root frame.
  - Neither caller was edited.
- Nine R-DIAG sites become B with no caller edit: `ai.clj:1484`, `edit.clj:121`,
  `:281`, `effect.clj:931`, `:935`, `maintenance.clj:463`, `schedule.clj:644`,
  `sci/kernel.clj:677` and `shell/jvm.clj:447` (census rows).
- Focused test run `fabefc553bf8` (`bin/test-fast --paths` with the lane paths
  over HEAD `535ce45cf`, `seon.error.refusal-test`, 44.9 s wall). It executed
  8 tests with 21 assertions: 0 failures, 1 error. The error is in
  `armed-refusal-returns-every-declared-family-without-refusing` at
  `test_support.clj:342`: "The canonical fixture needs an executing test handle:
  run the test through seon.test/run". That is the fixture harness, before any
  lane code runs. Both new regressions pass: `a-wrapped-cause-is-recorded-whole`
  and `the-frame-comes-from-the-root-cause`. So does the updated expectation in
  `constructor-consumes-only-the-throwable-input`, which the three-question rule
  classes as a retired assumption.

## Verification limits

- `seon.error-test`, `seon.error-result-test`, `seon.error-recording-test` and
  `seon.ai-test` did NOT run through the harness. After run `fabefc553bf8`,
  `bin/test-fast` and `bin/_test-slot` were deleted in the working tree.
  `bin/test` still sources `bin/_test-slot`, and the working-tree
  `seon.test.cache` lost `source-inputs`, so the snapshot phase exits 1
  (bin/test:717). These files belong to realities-commit-5. A plain
  `clojure.test` run is not the harness and is not evidence.
- `test/seon/ai_test.clj` is outside this lane and still asserts
  `[:seon.error/data :seon.ai/cause-chain]` at lines 939-941, 951-953 and 1211.
  Required change (retired assumption):
  - 939-941 and 951-953: assert
    `(mapv (juxt :seon.error/throwable-class :seon.error/message) (:seon.error/chain truncation))`
    (resp. `completion`) equals
    `[["java.io.IOException" "closed"] ["java.io.IOException" "connection reset by peer"]]`.
  - 1211: `(seq (:seon.error/chain truncation))`.
  - `test/seon/cluster/turn_test.clj:2177` and `:2286` only build literal data
    with the old key. The map is open, so they stay green. Converting them
    belongs to that file's owner.
- `bin/seon-hook` is outside this lane. Required change: delete
  `throwable-message` (`bin/seon-hook:459`) and have its five callers (`:557`,
  `:738`, `:762`, `:1793`, `:1849`) take
  `(:seon.error/message (peek (seon.error.refusal/chain e)))` for the sentence
  and record `(seon.error.refusal/chain e)` beside it. Checked under bb:
  `seon.error.refusal/chain` loads and returns both links. Under SCI no
  first-party frames exist, so `:seon.error/frames` is absent.

## Timings (every operation over 1 s)

| operation | wall | phases | cache |
|---|---|---|---|
| bin/test-fast run fabefc553bf8 | 44.9 s (DEFECT >10 s) | snapshot 5 s; JVM start+program load to projection acquired ~31 s; arm 3.1 s; tier 1.4 s; tests 0.5 s | published base d73e0a6c (106 commits behind), hit |
| HEAD-snapshot load of error/refusal/ai/effect/schedule | 14.9 s load, 16 s wall (DEFECT >10 s) | source load of the program namespaces, no AOT | classpath cache miss (fresh snapshot dir) |
| adoption proof (default JVM) | 1.15 s | rows 80 ms; branch 24 ms; transact 864 ms; projection 152 ms | - |
| `bin/test` snapshot attempts x3 | 1-2 s each, failed | refused at snapshot phase | - |

Existing issue class for the focused-JVM start: `docs/seon/issues/a-focused-test-jvm-spends-twenty-seconds-before-its-first-test.md`.
Under the ledger rule the orchestrator folds these rows into it. A 4-row declaration
transaction at 864 ms is not over the bound but is disproportionate. It is recorded
here for the publication-path owner.

RESET NEEDED: no.
