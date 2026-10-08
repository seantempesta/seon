---
type: research
status: landed
date: 2026-10-08
lane: stale-caller
---

# stale-caller: a caller keeps the definition of an earlier pass

Task: `seon.sci.eval/a-caller-keeps-the-definition-of-an-earlier-pass`. Lane `stale-caller`, base 7087c463c (default ran
4b4caf142).

## Broken things first

- Reproduced on default, agent `stale-caller` (branch `:agent-3c252ec6e8da`), ns `scratch.stale1`. Pass 1
  `(defn g2 [] 1) (defn h2 [] (g2)) (defn g2 [] 2) [(h2) (g2)]` answered `[2 2]`. Pass 2 `(defn g2 [] 3) [(h2) (g2)]`
  answered `[2 3]`. A third pass answered `[3 3]`: the defect is only inside the pass that redefines.
- The same defect breaks tests. In `scratch.stale3`, pass 1 defined `sq`, `other` (calls `sq`) and
  `(deftest other-squares (is (= 4 (other 2))))`. Pass 2 ran `(defn sq [x] (+ x 4))` and
  `(deftest other-is-six (is (= 6 (other 2))))`. Pass 3: `(other 2)` answered 4. `my.test/check` recorded
  `other-squares` GREEN and `other-is-six` RED (`(not (= 6 4))`). Both test members ran on their own branches
  (acquire about 4 ms). The check took 414 ms and 40,325 calls; the check cost is the existing redo-finish F-series.
- Nine tests are red at HEAD with or without this change: the same names, and pass 487 / fail 19 / error 2 on both
  runs. `seon.cluster.agent-identity-test/identity-map-and-omitted-arguments-use-the-same-function`,
  `seon.cluster.evaluate-sources-test/ordered-evaluation-retains-one-explicit-basis-without-publication`, and in
  `seon.sci.eval-test`: `acquisition-binds-loaded-first-party-compiled-vars`,
  `an-install-reads-registrations-an-earlier-run-committed`, `evaluate-invokes-eval-form-exactly-once-on-every-path`,
  `generated-sources-compose-fork-guard-and-admission`, `schema-and-contract-declarations-have-bounded-allocation`,
  `the-evaluator-remains-live-after-its-namespace-reloads`, `the-request-is-what-the-contract-says-it-is`. No task
  named them; filed `seon.sci.eval/nine-tests-reaching-acquisition-are-red-at-head` for root to classify.
- The pass after a contracted defn runs about 128,000 armed calls and 90 MB (8,149 `attribute-declaration`, 8,263
  `storable-attribute-in?`) at HEAD and with the change. Existing task:
  `seon.schema.datahike/an-ordinary-pass-builds-hundreds-of-storability-refusals`.

## Cause (measured, not inferred)

The defect is not the PB-01 overlay. On the MCP path the overlay plan was empty in every pass: debug output showed
`::overridden #{}` and the same plan object each pass. The base never re-acquired.

1. Settlement runs `seon.sci.eval/install-evaluated-rows!` → `transfer-evaluated-roots!`. This step `sci/intern`s each
   evaluated ROOT into the base context under the base's own Var. The root of `h2` is a closure over the agent's Var
   `g2` (SCI resolves a symbol to its Var object at analysis). After the transfer the base's `h2` Var holds a closure
   that calls the AGENT's `g2` Var, not the base's.
2. The next pass's `regenerate-agent-context!` decided which of the agent's own Vars to carry. Both of its rules dropped
   `g2` and `h2`. `same-program-root?` was true, because the base holds the transferred identical root.
   `changed?` was also true, because the agent's snapshot had no row and the base's had the row. Debug (throwaway JVM):
   `{:p [... g2], :changed true, :prior true, :next true, :next-same-var false}`, and the same for `h2`.
3. The agent's namespace now maps `g2` to the base's Var. Its generation is foreign, so SCI's `eval-def` copies it on
   the next `defn g2` (`reference-code/sci/src/sci/impl/evaluator.cljc` `eval-def`, generation check). The closure
   inside `h2` still derefs the agent's original `g2` Var, which keeps value 2. Generations confirm this: `g2` and `h2`
   were `sci-generation-577228` (the base) while the agent was `577232`.

Does it predate PB-01? Yes, by code identity. At 666c767f7 (before PB-01), `regenerate-agent-context!`,
`transfer-evaluated-roots!` and `install-evaluated-rows!` are the same text (`git show 666c767f7:src/seon/sci/eval.clj`).
PB-01 changed base derivation and overlay, which are not on this path. The throwaway root at 666c767f7 was NOT booted:
`bin/seon` launches only HEAD's archive, and there is no flag for another commit. Verification limit.

## Fix (BEHAVIOR CHANGE, with its regression)

`regenerate-agent-context!`: the agent keeps its own definition's Var, as one Clojure Var does.

- A Var of the agent's generation is carried when the base holds its same program root (settlement transferred it), or
  when its row is unchanged.
- It is dropped only when another writer changed its row.
- For a transferred definition, the base's row entry is kept in the snapshot, not the agent's stale one.
- Non-generation Vars (host namespace copies) keep the old rule unchanged (EQUIVALENT: the `previous` check was always
  false for them).

What this deletes: nothing. Which step of the one path it serves: eval. A redefinition now replaces the root of the Var
that every earlier closure holds. Simpler alternative considered and tried first: re-interpret the base's overlay on
each agent fork in the agent's generation (`own-overlay!`). It had no effect, because the overlay was empty. It also
re-interpreted per fork, so it was reverted.

## Second slice: the cluster head (root) and another writer

On default after the restart to 3168005b3, root on `:cluster-default` still answered `[2 3]`. Root's base is the cluster
context, which re-acquired between passes. The fresh `g2` and `h2` Vars belonged to the new base's generation, so the
transfer rule did not apply. `changed?` was still true because the agent's snapshot never recorded the rows its own
definitions committed, and the agent's Vars were dropped.

Fix, at the producers:

- `install-evaluated-rows!` merges the installed rows' function entries into the agent context's snapshot. Settlement is
  the owner that knows those rows are the agent's own.
- `regenerate-agent-context!`, when another writer really changed an own definition's row, keeps the own Var and
  adopts that definition's root and metadata into it in place, as a reload redefines one Clojure Var. Only a row the
  base no longer holds drops the Var.

Ablation in a throwaway JVM: without the snapshot record, `[2 2]` read `[1 2]`. Without adoption, `[7 7]` read
`[1 7]` and `[8 8]` read `[1 8]`. At 3168005b3 all three are red; with both parts, pass 3 / fail 0.

The regression is `seon.repl-differential-test/another-writer-on-the-cluster-head-neither-hides-nor-freezes-a-callee`.
In a test world the MCP agent `root` is a new id and gets its own branch: the probe showed connection `:agent-ce5b…`,
while the fixture agent held the cluster head. So the fixture agent is the cluster-head custody, and a second agent
created on the same branch is the other writer. The earlier `root` row added in this slice tested only a branch, so
it was removed.

The `seon.sci.arm/write-event` nil `:seon.batch/origin :line` error did not reproduce for me. I sent root's exact
pass-1 text as root on default and got no error. This path (`regenerate-agent-context!`, `install-evaluated-rows!`)
writes no batch event or origin, so it cannot produce the error. It belongs to lane eval-defs.

## Remaining limitation (reproduced, filed)

`seon.sci.eval/an-inherited-caller-sees-a-redefinition-only-after-the-pass`: B `(defn k7 [] (g7))`, then
A `(defn g7 [] 2) [(k7) (g7)]` answers `[1 2]`. The next pass answers `[2 2]`. The same holds for any caller the base
interpreted before a fresh fork (the first pass after a restart, or a new agent). The fork does not own that caller's
callee Var, so a `defn` copies it. The task lists three options for root. Model-agent passes go through the same
`fork-for-turn` and settlement (`seon.turn` calls `install-evaluated-rows!` with `:seon.sci.eval/agent-ctx`), so they
are covered as MCP is.

Also red at HEAD 3168005b3 and unchanged by this slice: `seon.turn-test/a-definition-changed-since-the-run-read-it-is-refused-at-the-writer`
and `seon.turn-test/a-form-whose-facts-are-refused-says-it-was-not-admitted` (error). These add to the nine in
`seon.sci.eval/nine-tests-reaching-acquisition-are-red-at-head`.

Second-slice MCP pass medians (HEAD 3168005b3 against the change, two runs each). Ordinary passes: 30.1-87.9 ms
(the first HEAD run was noisy) against 32.2-39.8 ms, both about 25.5 MB and 8,200-8,800 calls. Defn passes: 146.6-175.7
against 137.3-146.5 ms, 111-116 MB in both. After-defn passes: 63-68 against 62-69 ms, 65.5-66.9 MB and about 57,500
calls in both.

## Design check

The design held against the real code once the path was measured. What went wrong was the first guess (the overlay),
which inspection alone suggested. Two debug runs in a throwaway check JVM refuted it.

The awkward part is that the private-layer rule decides Var identity by comparing roots and rows. One Var per
definition would make the transfer unnecessary. Ledger slice PB-02b ("fork once, advance in place") replaces
regeneration and should keep this regression green.

## Proof

- New rows and a test in `test/seon/repl_differential_test.clj`, all through `seon.mcp/call`:
  - `later-pass-redefinition-reaches-earlier-callers` answers `[3 3]`;
  - `later-pass-redefinition-reaches-a-caller-of-a-later-pass` answers `[2 2 2]`;
  - `redefinition-after-a-pass-that-changed-no-program` answers `[2 2]`;
  - `a-test-of-an-earlier-pass-runs-the-redefined-callee`: `other-squares` fails once, `other-is-six` passes.
- At HEAD all four are red (`[2 3]`, `[1 1 2]`, `[1 2]`, and the test map). With the fix: pass 10 / fail 0, 4.4 s.
- Reaching tests (`seon.sci.eval-test`, `seon.sci.lazy-acquisition-test`, `seon.cluster.agent-identity-test`,
  `seon.cluster.evaluate-sources-test`, `seon.sci.branch-execution-test`): the same nine reds with and without the
  change, and 487 passes on both.

## Measurements (MCP SCI passes, `pass-measure.clj`, throwaway check JVMs, medians per run)

Sixteen passes per run: ordinary, defn and after-defn passes. Medians exclude the first two ordinary passes and the
first defn.

| run | ordinary ms | ordinary MB | defn ms | defn MB | after-defn ms | after-defn MB | after-defn calls |
|---|---|---|---|---|---|---|---|
| HEAD 1 | 24.5 | 25.3 | 102.6 | 112.0 | 66.4 | 91.4 | 128,336 |
| HEAD 2 | 28.8 | 25.2 | 138.5 | 112.1 | 72.7 | 91.4 | 128,310 |
| HEAD 3 | 29.5 | 25.5 | 115.2 | 111.5 | 76.7 | 91.5 | 128,305 |
| HEAD 4 | 28.0 | 25.0 | 131.5 | 110.4 | 67.3 | 90.6 | 128,255 |
| fix 1 | 29.6 | 25.4 | 121.1 | 113.5 | 72.4 | 91.6 | 128,139 |
| fix 2 | 29.4 | 25.5 | 125.7 | 113.1 | 74.8 | 95.6 | 128,315 |
| fix 3 | 33.5 | 26.1 | 143.5 | 110.8 | 80.9 | 90.6 | 128,007 |
| fix 4 | 25.4 | 26.0 | 116.5 | 113.2 | 73.3 | 91.1 | 127,987 |

Armed calls are unchanged. Wall time is within the run-to-run spread (HEAD ordinary 24.5-29.5 ms, fix 25.4-33.5 ms).
Ordinary-pass allocation is 0.2-0.6 MB higher in three of four fix runs (about 2%), below the 20% / 50 ms bar. The
first defn of a new agent allocates about 345 MB in both versions.
