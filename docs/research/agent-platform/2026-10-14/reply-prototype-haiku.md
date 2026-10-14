# Prototype: reply data read (Haiku, read-and-measure, partial)

Brief: `docs/research/agent-platform/2026-10-14/reply-data-dig.md` (commit 6ff790bb7). Task
`seon.test-support/tests-read-the-evaluations-recorded-data-not-printed-text`. Stopped at about
25 minutes. No source or test was changed. Only this note was committed.

## What was run

- `mcp__seon__runtime_status` (JVM, default): state `:degraded`, readiness boot 1352 ms, heap-used 968 MB of 4 GB.
- JVM-mode `eval_clj` probes (no SCI request, no root writes):
  - `(require 'seon.test-support)` and `(resolve 'seon.test-support/evaluate)` resolve.
  - Four `seon.test-support/evaluate` cases (ordinary `(+ 1 2)`, thrown `ex-info`, reader error `(+ 1`, refused `defn`),
    run with `:seon.schema/projection` from `seon.schema/build-projection`. Blocked: `seon.schema/candidate-forms` is not public, and `seon.schema/handed-projection` is nil outside a fixture. Every case threw `build-base-ctx refused argument 0 projection`, so no key set was observed for any path.
- Each eval request printed its cost as a separate final content item (see Q3). Two probes measured `:seon.repl/request :seon.cost/ms` at 69.5 ms and 124.8 ms, and `:seon.cost/calls` at 4523 and 24265. The second is the full JVM cost of the four cases, not a single evaluation.

## Q1. Does `evaluate` set `:seon.sci.admit/value` on every path?

Not verified at runtime. Source only:

- `seon.test-support/evaluate` (test_support.clj:400) delegates to the one path (`seon.batch/evaluate-batch`).
- `seon.sci.eval/shown-result` (sci/eval.clj:4384) builds the map with `:seon.sci.admit/value value` (4461). `shown-failure` (4490) assigns the error to `:seon.sci.admit/value` (4507). So ordinary, thrown and failure values reach the key in source.
- Not checked: the reader-error path, the refused-form (entry policy) path and the render-failure path. The brief asked for their key sets. A fixture with a real `:seon.schema/projection` (`test-support/fork-cluster-ctx` on an agent branch, SCI mode) is needed.

## Q2. Does the settled `:seon.render.history/printed-value` always equal `:seon.eval/shown`?

Not verified at runtime. Source only, and the answer is no in general:

- `seon.turn` (turn.clj:111-121) replaces `:seon.eval/shown` with the settlement evaluation's shown text, so a later stage can change the shown text after `shown-result` ran.
- turn.clj:2872-2879 recomputes `final-shown` from the admitted value after a settlement refusal, so `:seon.sci.admit/value` (in-process, before replacement) and the settled text can differ.
- The dig's claim holds from source. A runtime case (a later refusal or delivery replacing the value) still needs an SCI-mode disposable branch to show a concrete difference.

## Q3. Where is `:seon.repl/request` computed, and what is the smallest split?

Verified by reading source and by observing the output:

- Computed in `seon.mcp/eval-clj` (mcp.clj, `eval-clj` returns `[texts failed?]`). The cost is `(seon.batch/request-cost started (merge stages run-stages) counts)`, then `with-diff` and `with-shared`. It is appended as the last item by `(printed {:seon.repl/request ...} code nil cluster-name)`.
- Observed shape of the printed cost: keys `:seon.cost/alloc-mb :calls :cpu-ms :entrance :ms :reads :transactions :jvm-window :shared :stages` (stages `:custody :read :evaluate :settle :print :unattributed`).
- Smallest split (design only, not implemented): `eval-clj` returns the data map (`[texts failed? request-cost]`), and `call` prints the texts and the cost as before. `seon.test-support/mcp-request` then reads `:seon.repl/request` from that data, not from JSON. The schema change is `[:tuple [:vector :string] :boolean]` to add the cost map. This is the same owner-question the dig raised.

## Q4. Three representative `reply` call sites, with the data read

Not run. The fixture needed for Q1 and Q2 is also needed here, so no equivalent data read was measured. The intended three are: a value assertion (`repl_test.clj`), an error assertion (`help_test.clj`), and a test-step assertion (`cluster/turn_test.clj`, `mcp-tests`).

## Could not determine, and why

- Q1 reader-error, refused and render-failure keys: no fixture projection in JVM mode.
- Q2 runtime difference: needs a settled SCI-mode run on a disposable branch.
- Q4 equivalence: depends on Q1.
- Timings for an SCI-mode evaluation: not measured; JVM probes do not run the agent's branch.
