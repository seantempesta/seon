---
title: "The census's 12 red proofs, judged as spec for the Jolt port (spec-reds)"
type: research
status: final
created: 2026-10-20
---

# The 12 red proofs, judged as spec (lane spec-reds, 2026-10-20)

[tests-run.md](tests-run.md) found 12 cited deftests red twice at HEAD bc1a20635. Each is judged here as one of:

- `:spec-holds`: the test states the idea correctly and Seon fails it.
- `:test-stale`: the test asserted an implementation detail or a retired behaviour. It is rewritten to assert the idea on data.
- `:retire`: the test guards machinery Jolt removes and states no idea worth carrying.

Every red was reproduced with `bin/seon check --at HEAD` on cb9568dea (run 2e46fc67c1e5, 11 vars; run 50a89cc7da33, the cost table), with the same src, test and resources as the census commit. The rewrites and fixes were checked on the candidate (run 0831f2e66e03, 61 vars: every touched var green; the three other reds were in `seon.help-test` and were red on HEAD too, run 49a717ec7548, see Findings).

| test | idea | verdict | cause (evidence) | what changed |
|---|---|---|---|---|
| `seon.config-test/the-default-document-has-one-canonical-complete-location` | 19, 20 | `:spec-holds`, fixed | ed12f110a (cost wave 1) added seven `:seon.config.fn.cost/*` dials (`size-period`, `steps-k/c`, `reads-k/c`, `bytes-k/c`) with schema defaults but did not add them to `config/default.edn`. The set difference was exactly those seven (JVM read on default) | The seven dials were added to `config/default.edn` with their schema-default values, so behaviour is unchanged. Green. |
| `seon.help-test/help-names-only-installed-forms` | 3 | `:test-stale` + help text fixed | Three heads did not resolve. (a) The prose parenthetical `(the writer refuses it)` in `resources/seon/help.md`: help.md was at fault, since prose in parens reads as a form. (b) The keyword head `(:seon.store/store ...)`: a keyword applied as a function names no Var, so the test was stale. (c) `seon.test-support/with-database` and `execution-handle`: help (since f4f6155ef) teaches `(require 'seon.test-support)` first, and the test resolved them without that require, so the test was stale | help.md reworded. The test now looks up symbol heads only, requires a qualified head's namespace before resolving it (as help teaches), and asserts every head was looked up, so an erroring probe no longer passes vacuously. Green. |
| `seon.context-test/a-chain-of-one-is-judged-by-the-done-rule` | 9 | `:test-stale` | f038bf591 (10-18) made the done rule also read the merge's file check, so `done-refusals` now answers a second refusal (`seon.cluster.source/write-back!`, `:one-file`). The test required exactly one refusal | The test now asserts that the red-test refusals are exactly the done rule's, naming `tq.c1-test/edge`. A merge-preview red-test refusal would still fail it. Green. |
| `seon.context-test/broken-tests-show-with-their-failures` | 1 | `:test-stale` | The two steps are equal as data. The `my.task/current` MCP reply did not read back as EDN, because the value printer cut a quoted set inside a map into `'…1 (drop ...)` (`:cause "Map literal must contain an even number of forms"`, check run 73365b6846f1). This is a printer defect: [issue](../../../../seon/issues/an-elided-quoted-form-inside-a-map-prints-unreadable-edn.md) | It compares the two producers' data on the agent's branch (`seon.context/current-state` and `my.task/current`) instead of their printed replies. Green. |
| `seon.context-test/a-merge-branchs-unmerged-entry-shows-in-the-block-until-resolved` | 13 | `:spec-holds`, fixed | Regression from e7af24f14 (10-19): `seon.ref/references` at `:code` level stopped answering `:source` and answers only the read form. Only `seon.context/must-reads` went on to read the source (`code-read`), so the conflicts section lost both versions' source (NPE on `(:source theirs)`) | `seon.context/conflicts` reads each version through `code-read` (moved above it). Green. |
| `seon.context-test/a-warm-model-request-allocates-in-proportion-to-what-it-shows` | 1, 16 | `:spec-holds` | Red proof by design for the open cost task in `resources/seon/tasks/seon.context.edn` (live run 9). A warm build allocated 16,986,872 bytes against 200 bytes per character shown (2,542,800) | none (Jolt's pure context fold must meet it) |
| `seon.cost-estimate-test/ordinary-operations-cost-no-more-than-their-estimates` | 16 | `:spec-holds` | The strict estimate table is red by design while any overrun exists (AGENTS.md "Estimate first"). All 15 rows are over, each carried by an open `:overrun` task. For example, `:hub-behavior-change` measured 1,769 MB against 40 MB and `:cold-stale-merge-members` 4,382 MB against 175 MB (run 50a89cc7da33) | none |
| `seon.mcp-entrance-test/a-jvm-reply-keeps-its-duration-after-an-earlier-run` | 4 | `:test-stale`, renamed `a-reply-keeps-its-duration-after-an-earlier-run` | e361536a9 (10-10) made host evaluation root's alone (`seon.turn.batch/jvm-evaluate` refuses other agents). The test (10-07) ran JVM mode as a non-root agent, so it got `isError true` | The idea is that a second run's reply carries its time and is what the next context reads. It is now asserted on two runs of the agent. JVM mode is dropped, since Jolt has one evaluator. Green. |
| `seon.mcp-entrance-test/an-sci-reply-is-what-the-run-saved` | 4 | `:test-stale` | Two stale rows. (a) "def keeps its note": `:seon.repl/note` explained that a def lives only in the SCI context, which is retired (a def is now a program definition on the branch, help.md), so it is not shown. (b) "a refused settlement answers its refusal alone" contradicts the current law "the path is total": the reply now carries both values, each with the refused recording in `:seon.repl/diagnostics`, plus the writer's `:seon.db.write/validation-refusal` | Row (a) asserts that the def answers its value. Block (b) asserts the total path on data: `isError`, no stored receipt, both values returned, each with the `:seon.db.write/error` beside it, and the refusal as data. Green. |
| `seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart` | 8 | `:spec-holds` | Filed red by b40f21334 for task `seon.sci.forgive/forgiving-forgiven-text-changes-nothing`, never implemented. A dropped closer joins tokens (`"a]a"` gives `"aa"`), a quote before an inserted closer falls outside every span (`"('"`), and a closer inserted after a lone backslash becomes a character literal (`"(\\"` gives `"(\\)"`). Reproduced in JVM mode on default | none. There are three separate faults in the 200-line imperative `scan`; Jolt's NEEDS#12 scan is a rewrite and these rows are its examples. |
| `seon.sci.forgive-test/forgiving-forgiven-text-changes-nothing` | 8 | `:spec-holds` | The idempotence law, shrunk to `"a]a"` at trial 1, for the same task and cause | none |
| `seon.turn.test-step-verdict-test/a-test-run-in-the-request-that-redefined-its-callee-answers-the-new-definition` | 2 | `:retire` | `(f 1)` answered 2, so the redefinition was seen. The red half is `(:seon.test/passed? (clojure.test/test-vars ...))`, which relied on Seon's SCI override of `clojure.test/test-vars` (`seon.sci.lookup/agent-test-vars`, a request-view overlay) returning a run map. In a native forked process a redefinition is visible to the next call by construction, and `test-vars` returns nil | Deleted (it had no task tags). The namespace docstring and the unused `last-value` helper were removed too. Idea #2 keeps 9 meaningful proofs, and no idea loses its only proof. |

## Ideas with a clean spec now

- **#1** (generated context): 10 of its 11 cited proofs are green. The remaining red, `a-warm-model-request-allocates-in-proportion-to-what-it-shows`, is a valid cost spec that today's SCI-world build fails. It is the one bound Jolt's pure context fold must meet.
- **#3** (orientation text checked): all 8 cited proofs are green. `help-names-only-installed-forms` is the single resolve-all test the Jolt form describes. Three uncited `seon.help-test` vars are red on HEAD (see Findings).
- **#4**, **#9**, **#13**, **#19**: every cited proof is green.
- **#2**: one proof retired, the other 9 green.
- **#8**: the two reds are the reader's law (idempotence) and its example table. Both are valid spec and Seon fails both.
- **#16**: the strict table is a valid red spec. Its health is unchanged (two flaky rows).

## Findings outside the 12

- **Printer**: a cut quoted form inside a map prints unreadable EDN. See the issue linked in the table.
- **Unmerged refusal also lost its sources**: `seon.cluster.source/unmerged-refusal` builds its versions through the same `seon.cluster.source/versions`, so since e7af24f14 its references carry the read form and no `:source`. This is inferred from the producer and was not run. The lane fixed only the block's section, whose test reaches it.
- **Write-back refusal on the agent's home namespace**: in the chain-of-one fixture the done rule also answered `:seon.source/write-back-error` (`:one-file`, "Each change needs its source and one file") for `my.agents.running-agent-*`, the fixture agent's own namespace. Live chain agents start in `my.attempt-*`. Whether a live attempt that `in-ns`-es back to an agent namespace gets refused the same way was not checked.
- **`seon.help-test` has three uncited reds on HEAD**, all red on HEAD cb9568dea (run 49a717ec7548):
  - `help-is-an-orienting-guide-in-titled-groups`: a taught phrase is missing.
  - `help-teaches-defn-in-the-namespace-that-owns-the-function`: help teaches `in-ns 'my.`.
  - `help-teaches-no-placeholder-value`: a line has a placeholder slot (`my.attempt-<your attempt>`).

  All three come from help.md lessons added after the tests (f4f6155ef and later). Their idea is #3, and they need a ruling on whether `my.attempt-<attempt>` is a taught placeholder.
