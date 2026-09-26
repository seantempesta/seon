---
type: landing
status: complete
lane: inv-agent-run-stability-opus
date: 2026-09-26
base: 61d207c3e (default pid 13262, committed archive, hook publication off)
commits: e90e18b29 853bae417 71d3d74c5 e6c3487ad 85b459845 be96a64ba 45833be3e
---

# Agent run stability: landing

## Broken things first

- **None of these commits is proven installed.** Default ran the archive 61d207c3e with publication off, then went
  down during this lane (root restart, about 16:10Z; `bin/seon status` exit 5). Every "after" below is a REPL proof
  of a working-tree copy in a scratch namespace (`tmp.inv-eval-copy`, loaded by
  `tmp/inv-agent-run-stability-opus/load_copy.clj`), or is not proven. **Root runs the tests listed at the end.**
- **Tests of the machinery I deleted or changed, in files I do not own:**
  - `test/seon/cluster/evaluate_source_test.clj:61` asserts `:seon.turn/starting-namespace-changed`. That refusal is
    deleted (audit #4), so delete the assertion.
  - `test/seon/sci/branch_execution_test.clj:132-139` should still pass, because the row it installs is an evaluated
    row. Verify it.
- **Defect 5 (clojure.test parity) is not done.** It needs an owner decision; the three options are below.
- **Audit #6 (`native-call-refusal`) is not done.** It lives in `src/my/program.clj`, which is not my file.
- **The dependency-analysis platform fault still cannot be stored.** Its schema,
  `:seon.fn/dependency-analysis-missing-error` (`resources/seon/schemas/seon.fn.edn:246`), is a plain `:map` that
  does not extend `:seon.error/base`. So `seon.error/validate-declaration!` (`error.clj:529`) refuses every recording
  of it. The comment at `fn.clj:1006-1007` ("`seon.turn` stores and delivers it") is now false. Both belong to the
  owners of fn.clj and the schema.
- **The MCP `namespace` parameter defaults to `user`,** so MCP runs still start in `user`, and the new
  "start where the previous run ended" default (audit #4) never applies to them. Fix that in
  `script/seon/dev/mcp.clj` by omitting the namespace when the caller gives none.

## Defects

### 1. A namespace's functions install in name order (blocker): fixed, e90e18b29

- **Reproduced** on branch `inv-run-stab-a`:
  `(ns probe.order)`, `(defn zz …)`, `(defn aa … (zz x))`, `(aa 1)` answered `2`. The branch error
  `seon.sci.eval/install-row!` "Cannot interpret probe.order/aa: Unable to resolve symbol: zz" was recorded 6 times
  over 5 runs.
- **Cause.** `acquire-program!` installs a namespace's rows `(sort-by first …)`, which is symbol-name order
  (`eval.clj:2832`). SCI resolves a body's free symbols when it analyzes the body, so the caller failed. The failed
  install then `ns-unmap`s the Var (`eval.clj:1650`).
- **Model error.** Clojure's order is the order of definition, and its answer to forward references is `declare`.
  Rows carry no order.
- **Fix (+11 lines).** Before a namespace's rows install, `declare` every one of its function Vars that does not
  already resolve. SCI's `declare` keeps an existing root; I probed this with `sci.core/init`.
- **Before/after.** A fresh context acquired the branch at head with the real and the copied `acquire!`. Calling
  `(probe.order/aa 1)` gave:
  - before: "Unable to resolve symbol: probe.order/aa" (3,376 ms);
  - after: `2` (1,947 ms, including a second derivation).
- **Regression.** `seon.sci.eval-test/a-caller-named-before-its-callee-is-acquired-with-its-namespace`.
- **Not fixed.** `my.test/check` answers green when the changed symbol has no executable reaching test. That is in
  test.clj, which is not my file.

### 2. A refused run leaves the context unavailable forever (blocker; audit #3): fixed, 853bae417

- **Reproduced** on `inv-run-stab-a`. A run redefined `zz`, then ran `(my.program/ns-unmap! 'probe.order/aa-test)`
  twice. Settlement was refused: "Nothing found for entity id". The next run, `(+ 1 1)`, answered "This execution is
  unavailable: its program was reacquired … release and reacquire the context."
- **Cause.** `regenerate-agent-context!` re-asserted `::unavailable-execution` on every turn fork, and no entrance
  releases the context.
- **Fix.** The failed run still refuses its remaining forms. The next turn's fork regenerates the same handle from
  the base acquired at the branch head, carries no private layer, and reports `:private-state :absent`. The message
  now says exactly that.
- **After** (copy, `fork-for-turn`): a fresh fork had a private def `kept` = 7. Setting the flag and forking again
  gave `{:fresh :absent, :flag false, :kept-after false, :two 2}` on the same env atom.
- **Regression.** `seon.sci.eval-test/an-unavailable-execution-starts-its-next-run-from-the-branch-head`.
- **Open question for root.** Why was the program "reacquired" inside one run? `restore-or-unavailable!` compares
  `::loaded-database` by `identical?`, and every real acquisition re-materializes that value with
  `loaded-program`/`d/commit-as-db`. The acquisitions inside the run coincided with defect 1's refusal recording.
  This is not traced further.

### 3 and 4. An ns + alias + defn + caller run never settles, and the agent's mistake is recorded as a core panic: fixed, 71d3d74c5

- **Reproduced** on `inv-run-stab-b`: `(ns probe.min3 (:require [clojure.string :as str]))`, `(defn a1 … (str/upper-case x))`,
  `(defn b1 … (a1 x))`, `(b1 "q")`.
  - MCP timed out at 45 s.
  - Turn `bc7514e50d13` stayed open, and its 4 evaluations had no shown value and no error.
  - The branch recorded no error.
  - A virtual-thread dump (`jcmd … Thread.dump_to_file -format=json`) showed every proc idle.
  - `data/clusters/default/logs/seon.log:107307` holds the whole cause.
- **The chain,** starting from turn.clj:5070 → `analyze-submitted` (turn.clj:3477):
  1. Settlement re-analyzes the batch's declarations against the run's *starting* database. There, `probe.min3` is a
     bare entity with no alias, so `seon.fn/host-bound-callers` refuses `str/upper-case` as "no analysis of str"
     (fn.clj:1011).
  2. `analyze-submitted` routes that refusal to `fault/fault!` as a platform fault.
  3. Its declared schema does not extend `:seon.error/base`, so recording is refused ("The supplied error
     declaration does not validate its observation") and the fault is not committed.
  4. The `:panic` escapes `resume-turn`, `proc-failed!` stops the graph, and nothing settles the run.
- **Missing terminal event.** The step's `finally` republishes the permit and cancels the bound even when the pass
  threw, so no bound fires. The run's close never comes, and `agent-already-running` holds until retire.
- **Model error.** Settlement analyzed against a namespace the batch had already changed. `runtime-analysis-batch`
  already accepts `:seon.fn/namespace-row`, and per-form evaluation analysis already uses the live namespace.
- **Fix.**
  - `resume-turn` folds the batch. Each declaration carries the namespace row that an earlier form of the batch
    declared.
  - `analyze-submitted` returns every analysis refusal on its form through `phase`, so the run settles. The `fault!`
    branch is deleted.
- **Evidence.** `seon.fn/analyze-forms` on branch b's database, for the `a1` source:
  - without the row: the exact refusal;
  - with the batch's ns row: analyzed (293 ms).
- **Regression.** `seon.cluster.agent-test/a-run-defining-with-an-alias-its-own-ns-form-required-settles`. The run
  settles with `"Q"`, and the next run is admitted and starts in `alias.batch`.

### Astra R1/R2 (lost-error-r1 review): fixed, e6c3487ad

- **R1.** `arm!` now puts `:seon.fault/stop-request!` in the agent handle. When called, it reads the roster, calls
  `interrupt-turn!` and runs `flow/stop` on a virtual thread; it requests the stop and never joins. The turn bound
  carries it to `fault!`.
- **R2.** `record-turn-backstop-fault!` now puts the panic whole on the join channel, with its receipt, outcome and
  suppressed causes. A failure before any receipt is caught in the runnable: it requests the stop and settles the
  channel.
- **Regression.** `seon.turn-test/a-fired-turn-bound-requests-its-owners-stop-and-settles-its-join-whole`.
  `agent-test/failed-turn-transform-keeps-a-live-backstop…` now reads the failure through the panic.

### Audit #4 (namespace friction): 85b459845

- **Deleted.** Both `::starting-namespace-changed` refusals (`plan-call` and `system-run-call`), and their test.
- **New default.** A run with no requested namespace starts where the agent's latest settled evaluation ended
  (`previous-ending-namespace`, a single `max` query), else in the assigned namespace.

### Audit #1 (host-bound rows at the REPL): be96a64ba

- A host-bound or unbound row acquired from the database now installs a root that refuses by name when it is
  called. Before, it returned an interpretation refusal, which refused the acquisition and every later form.
- An agent's evaluated row keeps the returned refusal.
- `install-refusing-root!` binds the Var that acquisition declared, so callers already analyzed against it reach
  the refusal.
- **Not live-verified.** Regression: `seon.sci.eval-test/a-stale-host-bound-row-refuses-by-name-only-when-called`.
  The `fn.clj`/`test.clj`/`cluster.clj` deletions the audit names are not mine.

### Applied patch: 45833be3e

This is inv-lifecycle-storage-opus's Astra E2 child-roster release patch for agent.clj and agent_test.clj. It applied
cleanly and lints clean.

## Defect 5 (not done): clojure.test at the agent REPL — three options

The agent's `clojure.test` is the host namespace copied in (`seon.sci.binding.edn:5`). Its consequences:

- `run-tests` calls the host `the-ns`, so it finds no SCI namespace.
- `report` prints to the host `*test-out*` (the JVM's stdout), so the evaluation's output never shows it.

The options:

1. **Recommended, about 40 lines in eval.clj.** Bind the host `clojure.test/*test-out*` to the evaluation's `*out*`
   for each agent evaluation. Replace `run-tests`/`test-ns`/`test-all-vars` in the agent context with small
   functions that list the SCI namespace's `:test` Vars and call the host `test-vars`. `my.test/run` then defaults
   to `*ns*`.
   - Guarantee: `is`/`deftest`/`run-tests` report into the reply.
   - Sacrifice: fixtures of SCI namespaces still use host metadata.
2. **About 840 lines.** Vendor babashka's SCI-side `clojure.test` (`reference-code/babashka/src/babashka/impl/clojure/test.clj`).
   Parity is full, but the size breaks the law.
3. **About 5 lines.** Document that `my.test/check` is the only test entrance, and make only `my.test/run` use
   `*ns*`. This gives no clojure.test parity.

Arglists printing `(nil)`, `Math/abs` (audit #2, lane unrestrict-sci-opus) and the "Contract finding" line
(error.clj) were not reproduced: default went down first.

## Numbers

- My source changes in the six commits (excluding the applied patch): **+135 / −75**. Tests: **+224 / −110**.
- Deleted: the `analyze-submitted` fault branch (−12), both starting-namespace refusals (−7), the sticky re-assert
  of unavailability (−3), and the unbound-only refusing-root special case (merged into one `cond`).
- Probe timings: acquisition 3,376 ms before and 1,947 ms after (a copy, cold). `analyze-forms` took 293 ms. Both
  are over one second because of whole-program acquisition (`seon.sci.eval/acquire!`, 3,375 ms armed). That cost is
  pre-existing and unchanged by this lane.

## Root runs (serially, after adopting HEAD)

```
bin/test-check default --test seon.sci.eval-test/a-caller-named-before-its-callee-is-acquired-with-its-namespace
bin/test-check default --test seon.sci.eval-test/an-unavailable-execution-starts-its-next-run-from-the-branch-head
bin/test-check default --test seon.sci.eval-test/a-stale-host-bound-row-refuses-by-name-only-when-called
bin/test-check default --test seon.cluster.agent-test/a-run-defining-with-an-alias-its-own-ns-form-required-settles
bin/test-check default --test seon.turn-test/a-fired-turn-bound-requests-its-owners-stop-and-settles-its-join-whole
bin/test-check default --test seon.cluster.agent-test/failed-turn-transform-keeps-a-live-backstop-after-releasing-its-permit
bin/test-check default --test seon.cluster.agent-test/system-source-submission-uses-the-ordinary-durable-run
bin/test-check default --test seon.cluster.agent-test/releasing-an-execution-releases-the-executions-acquired-through-it
bin/test-check default --ns seon.sci.branch-execution-test
bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection
```

Repeat the MCP probes (forms above) on fresh branches.

Branches `inv-run-stab-a`, `-b` (wedged by defect 3) and `-c` need retiring once default is back. **RESET NEEDED: no.**
