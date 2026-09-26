---
type: report
status: running (revised in place; most severe first)
created: 2026-09-26
scope: standing dogfood lane dogfood-repl-opus — being a Seon agent through MCP only (branch → eval_clj SCI → test → merge → write-back → retire)
---
# Dogfood report: the agent's REPL, from the agent's side

**Method.** I work only through MCP. The `branch` tool gives me my own branch, and `eval_clj`
runs in SCI mode on that branch as the hosted agent `mcp/default` in `user`. I do no file edits and
run no `bin/test-check`. Every finding below comes from the exact forms and shown text recorded
in its issue. Default moved through archives fbd0897df → 33ea69298 → a1b01c92a → f0efd9cc8
(fresh store) → 10d6fdff5 during cycle 1, which is about seven JVM replacements in 20 minutes.

**Milestone blockers** are marked **[B]**. Known in-progress defects are routed around and not
re-filed; they are listed at the end.

## Ranked findings

| # | Finding | Model that is wrong | Issue |
|---|---|---|---|
| 1 **[B]** | A branch forked before an adoption refuses **every** form after it, even `(doc help)`, with `install-row!` "Host-bound declaration … must change through the loaded source files". Default adopts several times an hour, so no agent branch survives a cycle. There is no rebase. | Acquisition treats every difference from the *loaded* program as the branch's override. The branch's changes are its diff from its fork point; the base moving means the branch is behind, not that it overrides anything. | [a-branch-forked-before-an-adoption-refuses-every-evaluation-after-it](../../seon/issues/a-branch-forked-before-an-adoption-refuses-every-evaluation-after-it.md) |
| 2 **[B]** | A docstring-only redefinition of `my.issue/status` gated on **1,495** tests (the subject has no callers), interpreted in SCI. 1,482 failed with `Unable to resolve symbol: support/with-database`, `testing`, `io/file`, `tc/quick-check`, … The install was refused and the report was 40 KB. On the next archive the same `defn` ran past MCP's 30 s bound. | Saturated reach used as behavioral reach, and unchanged file test bodies interpreted in the agent's SCI world instead of run as compiled Vars. | [a-first-party-redefinition-gates-on-saturated-reach-interpreted-in-sci-and-is-refused](../../seon/issues/a-first-party-redefinition-gates-on-saturated-reach-interpreted-in-sci-and-is-refused.md); evidence on [call-graph-fidelity](../../seon/issues/call-graph-fidelity-selection-awaits-adopted-proof.md) |
| 3 **[B]** | From a branch agent, `(my.test/check {})` runs 23 members; all 22 file tests error with "This write names branch :agent-…, which is not the writing cluster's branch :dogfood-repl-opus-5". | Custody is an ambient binding that the test member inherits from the calling agent; it should be injected per member world. | [a-branch-agents-test-run-inherits-its-custody-so-every-fixture-write-is-refused](../../seon/issues/a-branch-agents-test-run-inherits-its-custody-so-every-fixture-write-is-refused.md) |
| 4 | A REPL `deftest` runs in a world with **no agent rows**. `(my.plan/current)`, the docstring example, refuses arity 0 inside a test, and an explicit `root` is "not an existing agent". Only refusal paths are testable. One-test `my.test/check` takes 2.0–3.1 s for about 130 ms of member time. | The member world is a prepared commit that does not contain the agent it runs as. | [an-agents-test-world-has-no-agent-so-my-functions-cannot-be-tested-as-documented](../../seon/issues/an-agents-test-world-has-no-agent-so-my-functions-cannot-be-tested-as-documented.md) |
| 5 | `(keys m)`, `(sort …)`, `(vals m)`, `(seq m)`, `(rseq v)`, `(seq "ab")` and arrays print as `#object[clojure.lang.ArraySeq]` and similar. `(map inc [1 2])` prints `(…?)`. | The floor admits values by an exact-class allowlist and treats a finished finite lazy seq as pending. It should classify by interface and realize up to its bound. | [the-floor-prints-ordinary-seqs-as-objects-and-finite-lazy-results-as-pending](../../seon/issues/the-floor-prints-ordinary-seqs-as-objects-and-finite-lazy-results-as-pending.md) |
| 6 | A plain map containing `:seon.turn/id 12` was rendered by the turn renderer. It refused, and the **evaluation** was reported as an exception (7 KB, the value printed twice). | Renderer selection by key presence, not by schema validity, and a render refusal treated as an evaluation failure. | [a-plain-value-with-an-entity-key-renders-…](../../seon/issues/a-plain-value-with-an-entity-key-renders-through-that-entitys-renderer-and-fails-the-evaluation.md) |
| 7 | On a stale branch, an error rendered through `seon.error/render-ai`, whose refusal rendered through it again, about 8 levels and 25 KB. The real cause appears only in the branch-error notice. Reproduced independently by design-repl-entry-renders (D16). | The diagnostic path re-enters the failing selector; a result's AI print should be the structural floor. | [a-renderer-refusal-renders-through-the-same-renderer-until-the-depth-bound](../../seon/issues/a-renderer-refusal-renders-through-the-same-renderer-until-the-depth-bound.md) |
| 8 | MCP SCI replies drop `println` output. It is recorded as `:seon.cluster.eval/output` but never shown, which is why `clojure.repl/source` "prints nothing". stderr is merged into stdout. | The MCP bridge keeps its own receipt projection, a second printer, instead of rendering the evaluation entity. | [mcp-branch-replies-drop-the-evaluations-printed-output](../../seon/issues/mcp-branch-replies-drop-the-evaluations-printed-output.md) |
| 9 | `(ns x (:require [clojure.string :as str]))` followed by a use of `str/` in the same run refuses the whole run as "no analysis of str … fill the cache", records it as a core fault, and shows `null` for every later form. | Batch analysis before evaluation; the REPL reads and evaluates one form at a time. | evidence on [an-alias-required-by-an-earlier-form-of-a-batch…](../../seon/issues/an-alias-required-by-an-earlier-form-of-a-batch-refuses-later-forms-as-unanalyzed.md) |
| 10 | `(do (in-ns 'my.issue) (defn …))` is refused by the definition authority. Top-level `in-ns` then `defn` in one run works. | Analysis attributes the `do` to the starting namespace. | evidence on [dynamic-in-ns-cannot-persist-definition-namespace](../../seon/issues/dynamic-in-ns-cannot-persist-definition-namespace.md) |
| 11 | The auto-check generated an empty, schema-less database as `:seon.db/db` and reported `actual: [:seon.issue/id "0"]`. | Supplied inputs are generated instead of supplied by declared schema name. | evidence on [auto-check-reports-the-function-symbol-as-the-actual-value](../../seon/issues/auto-check-reports-the-function-symbol-as-the-actual-value.md) |

## Smaller findings (not yet filed; one line each)

- The `eval_clj` tool description says a branch run "evaluate[s] exactly one form"; several forms work.
- The host is created once per branch. A later call with another `session_id` or `namespace` still runs as `mcp/default` in `user`, and a `namespace` argument is refused as `starting-namespace-changed`. That run's MCP reply also carries a 5 KB `datahike.writer` `:error` stack trace as an `out` event, for an ordinary declared refusal.
- `(help)` lists "Tools: my.message, my.turn, seon.bootstrap, seon.db", derived from `user`'s requires. `my.program`, `my.test`, `my.issue`, `my.plan` and `my.agent` are not mentioned. It also teaches `:out`/`:result` and "results arrive in your NEXT turn", which the MCP entrance does not show.
- Turn ids repeat across branches for the same hosted agent (`77ec74824b5d` was the first turn on four branches), so branch-error windows are hard to correlate.
- A store rebuild answered a raw `Throwable->map` with a 5 KB trace for a declared `branch-absent` store refusal.
- The PostToolUse hook blamed my `eval_clj` for another lane's half-edited `src/seon/render.clj`. No eval wrote files.
- `sci.lang.Namespace` prints `#object[sci.lang.Namespace]` with no name. `defn` now prints `#'ns/name`, so D13 is partly fixed.
- `my.program/callers` records `call-arity 0` against `declared-arities {:min 1 :max 1}` for zero-argument `my.*` calls, which call preparation completes. The new arity gate (f81a22cd8) could misjudge them.

## Known in-progress defects (routed around, not re-filed)

D3 (`my.test/check`/`run`: `check` now answers run results, and `run` answers `[]` for an agent
with no tests in its own namespace), D4 (same-run reach: confirmed again, the link appears on the
next run), the missing merge proposal function, in-run ungated redefinition, host retire's keyword
tx ref (retire worked on branches 1, 2 and 4), D6 preview writes, and D7 call preparation's head
snapshot.

## Cycle log

- **Cycle 1** (2026-09-26 02:45–03:05Z). Branches 1–4 went stale or were deleted by root's store
  rebuild. On branch 5 I added `my.plan-test/current-refuses-an-unknown-agent-by-name`, which is
  coverage for `my.plan/current`, a subject that had 0 reaching tests. It passes with 2
  assertions (`my.test/check`, run `7ef033023a77`). The merge proposal was blocked by the missing
  merge function (known). Write-back was not reached. Branch retirement is pending, because default
  was restarting at the time.
