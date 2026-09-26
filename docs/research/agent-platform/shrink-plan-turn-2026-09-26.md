---
type: plan
lane: shrink-turn-opus
date: 2026-09-26
status: plan only, no source edits; line numbers at HEAD cc3514a1e
live evidence: default pid 89341, JVM mode, read-only
---

# Shrink plan: `src/seon/turn.clj` (2026-09-26)

Lane shrink-turn-opus. Plan only. No source was edited. Line numbers are at HEAD `cc3514a1e`; turn.clj last
changed in `65d0ca8e0`, 5,990 lines. The turn namespace owns work derivation, the run writer transitions and
settlement for one agent's turn proc. Its data flow is:

`step` → `next-agent-work` → `turn` → `resume-turn` → `evaluate-sources` → `settle-batch!` → the writer's `*-call`
functions.

The approach throughout is to delete work and paths without adding machinery. The eval-path audit
(`docs/research/agent-platform/eval-path-audit-2026-09-26.md`) already owns the cross-file eval-path cuts: S1–S8,
the gate (`gate-function-install`), source re-reads and `call-turn`. This plan does not duplicate them. It covers
what can be cut inside turn.clj alone.

## Evidence

**Profile.** Cumulative `seon.profile` cells on default (pid 89341), read through JVM-mode MCP, read-only, at about
23:00Z. Times are inclusive.

| cell | calls | total ms | mean ms | max ms |
|---|---|---|---|---|
| `seon.turn/step` | 290 | 92,923 | 320 | 14,474 |
| `seon.turn/resume-turn` | 120 | 101,582 | 847 | 14,432 |
| `seon.turn/evaluate-sources` | 101 | 43,245 | 428 | 11,797 |
| `seon.turn/settle-batch!` | 91 | 39,843 | 438 | 2,666 |
| **`seon.turn/with-settled` = `settled-value`** | **26** | **26,454** | **1,017** | **10,382** |
| `seon.db/with` (all callers) | 52 | 30,000 | 577 | 10,311 |
| `seon.turn/row-tx` | 50 | 1,552 | 31 | 366 |
| `seon.turn/opening-deferred?` | 1,016 | 13,174 | 13.0 | 186 |
| `seon.turn/deferred-triggers` | 880 | 11,641 | 13.2 | 186 |
| `seon.turn/episode-runs` | 1,022 | 6,966 | 6.8 | 69 |
| `seon.turn/max-episode-runs` | 1,007 | 3,142 | 3.1 | 132 |
| `seon.turn/gate-function-install` | 113 | 7,011 | 62 | 972 |
| `seon.turn/fault-window` | 91 | 4,997 | 55 | 759 |
| `seon.error/occurred-since` | 78 | 4,745 | 61 | 756 |
| `seon.turn/close-folded-call` | 91 | 3,222 | 35 | 316 |
| `seon.db/q` | 21,489 | 119,144 | 5.5 | 2,744 |
| `seon.db/carried-projection` | 77,042 | 23,523 | 0.3 | 998 |

**Dead code.** Callers were checked in two ways:
- clj-kondo analysis over `src script test` with `--cache false`, saved at `tmp/shrink-turn-opus/analysis.edn`. The
  per-definition caller table is `tmp/shrink-turn-opus/defs.txt`.
- `rg` for every public name without a src caller, as `turn/<name>` and `seon.turn/<name>`, across
  `src script resources bin`. This catches references made through symbols, such as `requiring-resolve`, the
  web.clj action table and schema render pairs.

## 1. Slow paths first

### P1. `evaluate-sources` speculatively applies every program row, including the last form's (turn.clj:4910-4922, :5021-5023)

After each form that produced a `:seon.program/row`, the loop's `recur` computes
`(update working 1 with-settled row)`. That value is a `seon.db/with` of the row's writer transaction data.

It runs even when no form follows. In that case the loop exits and the with'd value is never read. A one-`defn`
run pays one `db/with` for nothing, and `db/with` of a program row costs 1,017 ms on average (maximum 10.4 s).

**Proportional to:** the number of program rows in the run, times the cost of `db/with`, where only the rows before
a later form are needed.

There is a second cost. When the branch's commit moved between forms, the loop rebuilds the whole prefix with
`(reduce with-settled latest all-earlier-rows)`. The form's own first durable write moves the commit, because it
opens the run. So a run that mixes writing forms and `defn`s is O(n²) in `db/with` calls.

- **Fix, the smallest version.** Keep the rows still to be applied in `working`. Apply them at the top of the next
  iteration, where `database` is first needed. The existing `(if (= commit-id …) working (reduce …))` becomes the
  one place that applies rows, so the last form never pays. About +3/−3 lines. This does not change what a later
  form sees.
- **Expected effect:** −1 `db/with` per run whose last form declares, which is the common single-`defn` MCP run.
  The one-commit landing's defn series (2,645 / 815 / 753 / 864 ms) should fall by roughly the `db/with` share.
- **Proof of behaviour, as regressions:**
  - existing `turn_test/batch-settlement-preserves-declaration-order` (:928);
  - a program_test run of `(defn f …)` followed by a form reading f's row;
  - one new class regression: a one-form `defn` run makes zero `seon.db/with` calls, measured as the delta between
    `seon.profile/begin` and `explain`.
- **Clock row:** a contracted `defn` on a fresh branch, first call and then three more, before and after. Root
  runs it.
- **Cross-lane note for shrink-db.** `seon.db/with` of a declaration row is itself about 0.6–1 s: the writer's
  checks, `carried-projection` and the projection carry. That belongs in db.clj, not here.

### P2. The turn bound derivation repeats its own reads (turn.clj:2724-2922, :2973-3064)

`opening-deferred?` is 13 ms, called 1,016 times. `deferred-triggers` is called 880 times. Most of those calls come
from `seon.problems/deferred-agents` on every status read (task in `resources/seon/tasks/seon.problems.edn`).

On one database value, `opening-deferred?` does the following:
- calls `max-episode-runs` (up to 3 `db/q`);
- calls `wake/declarations-refusal`;
- calls `episode-runs`: an issue `q`, then `outside-wake-t` (`agent-eid` `q` plus history seeks), then 2 more `q`;
- calls `outside-wake-t` again (another `agent-eid` `q` and the same seeks);
- runs the failed-attempt `q`.

`deferred-triggers` then calls `unanswered-wakes`, which is another `agent-eid` and `latest-answering-turn-t`.

Cost follows the number of queries, not their size. The perf-eval-overhead landing measured about 1 ms per `q` on
a fresh value, against 0.06 ms on a held value.

- **Fix, inside turn.clj:**
  - Resolve `agent-eid` once per derivation and pass it on.
  - Compute `outside-wake-t` once in `opening-deferred?` and hand it to a private `episode-runs-since`. The public
    `episode-runs` keeps its signature, because issue.clj, repl.clj, transcript, problems, oversight and bootstrap
    call it.
  - Replace the error triple-checks (see D6) in the same functions.

  This removes 3–4 of about 10 queries per call. Target: `opening-deferred?` under 8 ms on a fresh value. Net lines
  are negative.
- **Already done:** the rewake re-derivation named in task `62c45543598c`. `step` no longer calls
  `more-agent-work?` (turn.clj:5947-5951), and only `cluster/agent.clj:1496` still does. Root should mark that part
  of the task done.
- **Regressions:** the turn-bound tests in `turn_loop_test` and `turn_test` (`a-fired-turn-bound-…`, :2276), plus
  `problems` status tests. Clock: `seon.problems/problems` on default before and after.

### P3. `fault-window` reads full error observations to keep only signatures (turn.clj:3723-3741): measure before cutting

`fault-window` calls `seon.error/occurred-since`, which does all of the following:
- scans every `:seon.error.occurrence/last-at`;
- pulls the full observation selector for each error;
- pulls each error's resolution.

`fault-window` then keeps only `:seon.error/signature`. The profile mean is 55 ms per settlement. On default's own
branch, with 2 occurrences, both calls measured 1–1.5 ms. The mean therefore comes from worlds that hold many
errors, most likely test branches.

- **Fix, preferred.** Error's owner (shrink-schema lane, error.clj) provides a signatures-only window read; turn
  calls it. The alternative is one direct `db/q` for signatures in `fault-window`. Either way the window reads what
  it saves, and nothing more.
- Measure first on a test world with more than 50 errors. If the numbers stay near 1 ms, drop this item.

### P4. Owned elsewhere (named here so no one looks for them in turn.clj)

- `gate-function-install` (62 ms mean): the cost is `seon.sci.eval/evaluate-candidate` (206 ms per candidate) plus
  a `config/effective` read per `defn`. Audit S5.
- `settle-batch!` (438 ms): its time is `db/transact!` (326 ms mean) inside `blob/with-publication!`, which is
  durable commit cost (perf-eval-overhead, fix-write-cost landings). Task `0e9208e52dea` (close as a third commit)
  is already folded as `close-folded-call`, so root should verify and close it.
- `seon.db/q` at 5.5 ms mean over 21k calls, and `carried-projection` at 77k calls: these belong to the db owner.

## 2. Deletions (behaviour preserved)

Each item gives its proof and the lines it saves. Every src caller was checked by the kondo table and by `rg` for
symbol references.

| # | what | proof | src lines | risk | regression |
|---|---|---|---|---|---|
| D1 | `render-html` (:1917) | no kondo usage; no symbol reference. The run render pair in `seon.turn.edn:5-6` names `seon.render.transcript/render-run-html`. | −8 | none | load plus `contracts-compile-test` |
| D2 | the unconsumed routed-problem derivation: `plan-settlement` (:2659), `form-settlement` (:2616), `assignment-facts` (:2577), `form-run-id` (:2568), `terminal-receipt?` (:2560, a copy of `terminal?`), `red-receipt?` (:2504). Delete `:seon.turn.work/form-settlement` and `plan-settlement` from `seon.turn.work.edn` (:23, :84) with them. | No src, script or resources caller: the only callers are tests (problem_routing_test :166 and :224-245, resume_artifact_routing_test, gen/loop_test :284 and :318, turn_test `red-receipt?`), which assert the derivation against itself. Keep `form-owner`, `resume-artifact?`, `problem-id` and `planner-scoped-attempt?`, which problems.clj uses. | −125 (+ tests) | medium: root rules whether a planner consumer is planned | remaining problem_routing tests via `seon.problems` |
| D3 | `committed-attributes` (:3271): a test-only property helper. Move it into the wake/loop test support. | Callers are tests only (turn_loop_test, wake_test, message_test); wake.clj:99 mentions it only in a docstring, which gets updated. | −51 src (+~15 test) | low | the C2 disjointness tests, unchanged |
| D4 | inline contracts that copy named schemas: `receipt-settle-call` (:1667-1703, a 37-line copy of `:seon.cluster.eval/settle-request`); duplicated request maps in `plan-tx`/`plan-call`, `receipt-start-tx`/`receipt-start-call` and `open-tx`/`open-call` | Each pair declares the same map twice. Name it once in `seon.turn.edn`. | −60 src (+~15 EDN) | low | `contracts-compile-test/check`; turn_test writer tests |
| D5 | `evaluation-facts` (:104, 109 lines) rebuilds a map key by key with about 30 `cond->` arms | Use `select-keys` of the evaluation's keys and the settlement's keys, then `(into {} (remove (comp nil? val)))`. Two real transforms remain: `renderer-fn` as a lookup ref and `gate-tests` as refs. The `int?`/`some?` guards are already enforced by the settle-request contract at the writer. | −75 | low-medium: 0 and false values must survive, and `remove nil?` keeps them | turn_loop_test settlement tests; `an-unbound-result-settles-red…` (turn_test:2137) |
| D6 | 16 copies of `(and (map? x) (contains? x :seon.error/at) (contains? x :seon.error/layer) (contains? x :seon.error/operation))` | Where the value is a `db/q` or `db/pull` answer, use `seon.db/read-refusal?` (db.clj:195, "THE ONE TEST every reader applies"). The rest pass through a callee's declared error and are tested against that callee's declared member. | −45 | low-medium: each site's producer must be checked | turn-bound tests; `a-history-read-refusal-cannot-suppress-declaration-divergence` (turn_test:1044) |
| D7 | `receipt-run` (:905) duplicates `require-open-run` (:362) | Same `nil` → `::no-such-run`, closed → `::run-closed`; `require-open-run` also throws a read refusal. | −10 | low | receipt writer tests |
| D8 | starting-namespace and reply-size derivation written 3 times: `plan-call` (:699-724), `system-run-plan` (:766-783), `record-evaluated-call` (:1545) | one private helper | −15 | low | plan/system-run tests |
| D9 | the commit closure (`with-submission` + `phase` + `with-publication!` + `transact!`) written 3 times (`settle-batch!` :3834, `settle!` :4051, `settle-batch-refusal!` :3988), and the refusal recording (`error/recording` + `open-run-tx-call` + `close-tx`) written twice (`refusal-terminal-data`, `settle-batch-refusal!`) | one `commit!` and one refusal-tx builder | −30 | medium: the refusal arm's phase handling differs (`settle-batch-refusal!` does not wrap in `phase`), so decide whether that difference is intended | `a-refused-batch-settlement-closes-the-turn…` (turn_loop_test:1123); `a-terminal-refusal-settles-when-its-run-has-vanished` (turn_test:384) |
| D10 | kondo findings: unused require `seon.flow` (:24); unused bindings (:886, :4421, :5044); redundant lets (:4437, :4524, :5050, :5505); redundant `or` (:5308); duplicate `declare`s | kondo | −10 | none | lint plus load |
| D11 | history in comments: 574 `;` lines plus narrative docstrings (dated measurements, incident stories, "used to"). Examples: :3851-3870, `open-run-tx-call`'s docstring :497-520, `outside-wake-t`'s :2724-2748, `unanswered-wakes`' :3092-3124. | CLAUDE.md: "history in Git"; docstrings use fixed headings | about −250 | none | lint; docstring-link checks |
| D12 | **(ruling)** the second evaluation writer: `record-evaluated-call` / `record-evaluated-tx` (:1526, :1599) with `recorded-evaluation`, `recorded-run` and `stored-record-content` (about 210 lines), used by `system-turn` write? (the web.clj debug control) | It duplicates the submission path (`system-run-tx` + `settle-batch!`) that MCP runs use. Converging needs web.clj (shrink-web lane) and changes the identical-record no-op. | −210 | high | system_turn tests (45 test references) |
| D13 | **(ruling, with audit)** two terminal writers: `settle!` (single form or phase failure; callers :4461, :4566, :5118, :5171, :5256, :5319, :5339, :5373) and `settle-batch!` | `settle!` is `settle-batch!` of one request plus the failure arm | about −80 | high | the whole turn suite; this belongs with audit S3/S8 |

Kept after checking. Each has a live caller through a symbol:
- `virtual-turn!` and `compact!` (web.clj:2463-2464)
- `open-call` (issue.clj:34)
- `open-tx` (test.clj:182)
- `unanswered-triggers` (bootstrap.clj:144)
- `recover-call` (cluster/agent.clj)
- the `*-tx` one-line wrappers. Tests build transactions through them, and inlining them into 59 test sites makes
  those sites worse.

Total without the rulings (D1–D11): about −680 src lines, 5,990 → about 5,310. With D12 and D13: about −970.

## 3. Ordered loadable slices (slow first)

Each slice is one commit with `git commit --only`. For each:
- `clj-kondo --lint src script test --cache false` shows 0 errors;
- `contracts-compile-test/check` passes over turn.clj and any touched EDN;
- touched schema resources read.

Root runs the named tests.

1. **P1**: lazy speculative settlement in `evaluate-sources`, plus the zero-`db/with` class regression. Root clocks
   the `defn` series.
2. **P2 + D6 (bound functions only)**: one `agent-eid` and one `outside-wake-t` per derivation, and
   `db/read-refusal?` in `opening-deferred?`, `turns-left`, `episode-runs`, `max-episode-runs` and
   `next-agent-work`. Root clocks `problems`.
3. **P3**, only if measurement on an error-heavy test world shows more than 10 ms. Otherwise drop it. Coordinate
   with the error.clj owner.
4. **D1 + D10**: dead render and lint residue.
5. **D5**: `evaluation-facts` by selection.
6. **D4**: contracts by name (turn.clj plus `seon.turn.edn`).
7. **D6 (remaining sites) + D7 + D8**: duplicate predicates and helpers.
8. **D9**: one commit closure and one refusal-tx builder.
9. **D3**: `committed-attributes` moves to test support (turn.clj plus the test files).
10. **D2**, after root's ruling: routed-settlement derivation, its schemas and its test sections.
11. **D11**: comment and docstring history pruning, section by section, with no code change.
12. **D12 and D13**, only after owner rulings, and joined with the eval-path audit slices.

## Defects found (not fixed; task EDN pending root's go-ahead, since this mode commits only the plan)

- The wasted `db/with` on the last form (P1) is a new defect class. It is not in `resources/seon/tasks/seon.turn.edn`
  and should be filed there.
- Task `62c45543598c` is partly stale: `step` already rewakes without re-deriving.
- Task `0e9208e52dea` (close as a third commit) appears to be implemented as `close-folded-call`. Verify it and
  close the task.
