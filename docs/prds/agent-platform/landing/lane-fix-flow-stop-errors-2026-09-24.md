---
type: landing
status: blocked
lane: fix-flow-stop-errors
created: 2026-09-24
report: docs/research/agent-platform/one-happy-path/execution-2026-09-24.md
---
# Lane fix-flow-stop-errors — landing (2026-09-24)

core.async.flow's graph `stop` closed `::flow/error` and `::flow/report` before any proc handled
`::flow/stop`. This fix makes the producer close them only after every proc exits. It is
committed in the core.async submodule on a local branch only.

## Broken or not done (read first)

1. **Not pushed. The gitlink is not advanced.** No personal core.async fork exists.
   The submodule's `origin` is upstream `https://github.com/clojure/core.async.git`, and
   `gh repo view seantempesta/core.async` answers "Could not resolve to a Repository".
   Pushing to origin would push to upstream, so I did not push. Creating a public GitHub fork
   needs the owner's permission. Advancing the Seon gitlink to a commit that exists on no remote
   would break every fresh clone's `git submodule update`, so the gitlink stays at `dc35f3e`.
   **Owner action:** create `seantempesta/core.async` and repoint the submodule URL in
   `.gitmodules`. Then push `seon/flow-stop-errors` (fb21fc0) and advance the gitlink with
   `git commit --only -- reference-code/core.async .gitmodules`.
2. **The fix does not reach the running JVM.** Seon resolves core.async from Maven
   (`deps.edn:21-23`, `org.clojure/core.async {:mvn/version "1.10.874-alpha3"}`), not
   `:local/root`. Adoption needs a `deps.edn` change, which is outside this lane's ownership,
   and a restart with that classpath. I made no default-JVM claim.

## Evidence

- Source at `dc35f3e`: `impl.clj:174-183` (`stop` sends the command, then closes `error` and
  `report` at once). The proc loop reports a transition throw with `>!!` on
  `(outs ::flow/error)` (`impl.clj:312-316`), and that put fails on the closed channel. The same
  catch returns the *old* status, so a throwing stop transition also left the proc looping.
- Reproduction (scratch JVM, the fork's source through `:local/root`): a proc whose stop
  transition throws, followed by start, resume, stop and then `<!! error-chan`.
  - Before the fix: `nil` in 5/5 runs, so the error was dropped.
  - After the fix: `stop boom` in 5/5 runs, 0.03–0.2 ms after stop.
- Regression `clojure.core.async.flow-test/test-stop-transition-error-delivered` runs 20
  iterations. Each asserts that the error arrives with `::flow/op :transition` and
  `::flow/transition ::flow/stop`, and that the channel then closes. It fails on the `dc35f3e`
  impl, run from a `git archive` snapshot with the new test file. It passes with the fix.
- Full clj suite of the fork: `run-all-tests #"clojure.core.async.*"` ran 51 tests with
  407 assertions: 0 failures, 0 errors, 11.1 s wall. That includes JVM start and AOT-free
  loading of every test namespace.

## Change (fork commit fb21fc0, branch `seon/flow-stop-errors`, +36/−10 source and docs, +20 test)

- `impl.clj` `start`: stores each launcher's `spi/start` return under `:procs`. The built-in
  proc already returns its run `FutureTask`.
- `impl.clj` `stop`: after the stop command, one `futurize`d `:io` task waits for every
  returned `Future` and then closes error and report. `stop` itself stays non-blocking.
- `impl.clj` proc loop: a transition wrapper reports a throw with `:op :transition` and keeps
  the state. The status change still takes effect, so a failed stop exits.
- `spi.clj` `start` docstring: an accretive promise that `start` may return a `Future`
  completing at exit. Other returns are still ignored, so custom launchers are unaffected.
- Remaining risk: a proc that never exits keeps error and report open, and one virtual
  thread stays parked on it. This is the intended meaning, because the channels close at exit.

## Seon workaround check (src/seon/flow.clj is held by another lane, so I did not edit it)

- `var-process` catches a stop-transition throw and calls `seon.fault/fault!` on the proc's
  own thread (`flow.clj:258-260`, `proc-failed!` at `:190-212`). This is the designed
  synchronous fault seam, not a workaround, so nothing there becomes deletable.
- `join-graph!` (`flow.clj:126-136`) joins through a per-graph executor's termination because
  "stop is a command, not a join", and it cites `impl.clj:174-183`. Once the fork is on the
  classpath, the graph's error channel closing becomes a proc-exit signal. The per-graph
  executor join could then be replaced by awaiting the error channel's close under
  `seon.await/await!`. That is a candidate deletion for the flow.clj holder after adoption.
  I have not measured it.

## Commits

- core.async submodule: `fb21fc0` (local only).
- Seon: this landing note only.
