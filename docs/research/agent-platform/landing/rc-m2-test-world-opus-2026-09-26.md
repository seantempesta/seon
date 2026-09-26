---
type: landing
lane: rc-m2-test-world-opus
status: partial — slice 1 as ONE patch for root (touches turn.clj, schemas, tasks, docs and non-owned tests); rest needs owner options
patch: tmp/rc-m2-test-world-opus/m2-slice1.patch (git apply --check passes on 00454cb00)
date: 2026-09-26
base: 00454cb00 (default pid 48787 runs 20b156860)
---

# rc-m2-test-world-opus: landing

## Design (written before the edit)

What exists: `seon.turn/gate-function-install` (turn.clj:3321) calls
`seon.sci.eval/evaluate-for-install` (eval.clj:4659 at 20b156860). That function forks a candidate
context only when the **reader** names a function (`(when (:seon.fn/sym event) (fork-candidate-ctx …))`,
eval.clj:4679). Otherwise it evaluates in the retained context. The gate then passes
`(:seon.test.accretion/candidate-ctx evaluation)`, which is nil for `(do (defn …) …)`, to
`evaluate-candidate`. There the optional key is present as nil and the contract refuses it (the urgent symptom).

- **The model error.** Two worlds exist for one definition, chosen by syntax:
  - A top-level `defn` evaluates in a fork. Its root is later **transferred** into the retained context
    (`accept-candidate!` → `transfer-evaluated-roots!`) under a second, recaptured restoration.
  - A `defn` inside `do` evaluates in the retained context. It gets no candidate. When refused, it
    **stays** in the retained context: `refuse-install` never restored it (latent bug).
  - `evaluate-candidate` also carries its own `evaluate` fallback, a third evaluation path.
- **The one owner.** `evaluate` already evaluates every form once in the retained context and returns
  `:seon.sci.eval/restore`. That is "the one restoration of this form's program change, reused at every
  failure point" (eval.clj:4436).
  - The gate proves the definition in a fork of the context that evaluated it, so the proof runs the
    root that acceptance keeps.
  - Acceptance arms the contract.
  - A refusal calls the evaluation's own restore.
- **Decision sites deleted:**
  - The reader-selected candidate fork and all of `evaluate-for-install` (−27).
  - `accept-candidate!`'s root transfer, recaptured restore and Var-for-value overwrite (−14). The
    overwrite made `(do (defn f …) (f 1))` answer the Var instead of 2.
  - `evaluate-candidate`'s request-supplied context, its independent `evaluate` fallback and its
    no-row branch (−25).
  - The `candidate-ctx` plumbing in turn.clj (−7) and in two schemas (−6).
- **Evidence to produce:** the smoke form on real data, the refusal restoring the context, timings, and
  one class regression.

## Broken things first

- **RESET NEEDED, and nothing is committed.** Slice 1 cannot be committed from this lane's files alone:
  - Deleting `evaluate-for-install` breaks turn.clj's load unless turn.clj changes in the same commit.
  - turn.clj, both schema resources, `resources/seon/tasks/seon.sci.eval.edn`,
    `docs/seon/architecture/agent-runtime.md` and five tests are outside the ledger row.
  - So the whole slice is one patch for root (apply, run the two static checks, commit). My working-tree
    edits were reverted to HEAD so the shared tree keeps loading.
- **Acceptance "generated calls never touch the agent's branch": NOT met.** The cause is proven below; it
  needs an owner decision (options at the end).
  - Slice 1 keeps the proof fork on the agent's connection, as HEAD does.
  - Gate test bodies still receive the live connection: `run-candidate-test!` hands down
    `:seon.db/connection`.
- **Acceptance "a gate test resolves its file's requires": NOT met.** This is loose-threads §11 Q1 (reach
  executor), still an open owner decision. `run-candidate-test!` still re-evaluates test source in the fork.
- **Not done:**
  - T11 / task 0261d4d9bbe6 (row-loaded functions get no supplied defaults). It does not fall out of this
    slice.
  - Task 845e1bce2592 (a world per changed check, ~1 s). It is `clean-request-handle`'s cost and is
    untouched.
  - Renaming conservative reach apart from test selection. That code is in `src/seon/fn.clj:1942`, not
    this lane's file.
- **Not updated: `test/seon/schema/datahike_parity.edn`.** It still lists `:seon.test.accretion/candidate-ctx`
  in `:seon.sci.eval/evaluation`.
  - The baseline was not regenerated for sci.eval.edn changes since 84416a8ab, so it is already stale.
  - Root decides whether that oracle is still gating.

## Slice 1 (patch `tmp/rc-m2-test-world-opus/m2-slice1.patch`)

| file | change |
|---|---|
| src/seon/sci/eval.clj (+56/−99) | Delete `evaluate-for-install`. `accept-candidate!` = arm the contract in the evaluating context; a failed install applies the evaluation's own restore. `refuse-install` applies the evaluation's restore. Its refusal input is widened to `[:or install-refused-error :seon.db/error-result :seon.turn/refused-error]`, which is **rc-m4's request for task 023acee5f88d**. `evaluate-candidate` always forks the evaluating context and requires the evaluation. The namespace docstring and comments are corrected. |
| src/seon/turn.clj (+4/−11) | `(sci.eval/evaluate request)`. No `candidate-ctx`/`source` passed. No candidate dissocs. |
| schemas seon.test.accretion.edn, seon.sci.eval.edn (+1/−7) | `candidate-request`: evaluation required, `candidate-ctx`/`source` removed. `evaluation`: `candidate-ctx` removed. |
| resources/seon/tasks/seon.sci.eval.edn | Two must-reads named the deleted `evaluate-for-install`. They are re-pointed to `evaluate-candidate` and `gate-function-install`. |
| docs/seon/architecture/agent-runtime.md | Its false claim "the definition-time candidate fork stays" is replaced with the one-evaluation model. |
| tests | Scripted sweep `evaluate-for-install` → `evaluate` in contracts_install_test, contracts_fixture and fn_test. restoration_test drops the candidate-ctx plumbing. core_functions_test and accretion_test evaluate before proving. |
| test/seon/test/accretion_test.clj | New class regression `a-definition-evaluates-once-and-its-gate-proves-that-root`. It replaces `candidate-tests-run-on-a-copy-on-write-turn-fork`. It deletes `two-candidate-forks-of-one-base-keep-their-own-roots` (retired design). |

The new regression asserts the following:
- `(do (defn target …) (target 1))` evaluates to 2, carries its row and is gated by both tests ([0 1]
  fails).
- The proof fork and the evaluating context hold the same root.
- `refuse-install` restores the original Var, and `(target 1)` is 1 again.

Net: source −50 (+60/−110), tests −68, all files −125.

For rc-m4 (023acee5f88d), the analyzed row is already available before settlement: the evaluation's
`:seon.program/row` from `admit-program-change!`. `gate-function-install`'s second `seon.fn/analyze-forms`
is M1's duplicate, not a need.

## Evidence

- **Reproduced** on fresh MCP branch `rc-m2` at 20b156860:
  - Wrapped in `do`, the smoke form fails with "evaluate-candidate refused argument 0 … candidate-ctx …
    got nil (turn.clj:3342)".
  - Top level, it installs `#'user/smoke-inc`.
- **The new path on real data.** JVM probe, scratch ns. Disposable isolated execution off the `rc-m2` host:
  `acquire-context!` isolate, released after. The installed functions are composed exactly as the patch
  composes them (`evaluate`, then `evaluate-candidate` with the evaluation and no candidate-ctx, then
  `install-candidate-function!`, then the evaluation's restore). Results:
  - `(do (defn smoke-inc …) (smoke-inc 1))`: value **2**, row `user/smoke-inc` with spec. **330 ms**, which
    includes the context's first acquisition.
  - Gate fork: auto-check `:passed` 25/25 in **32 ms**.
  - Accept (arm): **3 ms**. `(smoke-inc "a")` → `:input` refusal.
  - A redefinition `(* 10 x)` in `do` evaluates to 10. Its restore returns nil; `(smoke-inc 1)` is back to
    **2**, and the prior contract is still armed.
  - Total **≈365 ms < 1 s**.
- **Static checks (snapshot = HEAD + patch, absolute paths):**
  - `seon.contracts-compile-test/check` over eval.clj, turn.clj, accretion.clj and four tests: `[]`, 173 ms.
  - The three touched EDN resources read.
  - clj-kondo errors: 0.
  - `git apply --check` passes on 00454cb00.
- **Isolated execution cost** (for the options): `acquire-context!` isolate 66/40 ms, release 8 ms each.

## Why the rest of M2 is not a small slice (cause, proven)

- **A function root runs with the environment of the context that created it.** SCI's `fun` captures the
  evaluation ctx (`reference-code/sci/src/sci/impl/fns.cljc:177-180`). The call-preparation hook reads
  `:call-preparation-hook` and the environment from that ctx (`analyzer.cljc:1796`,
  `seon.call-preparation/hook` call_preparation.clj:1143).
- So the gate cannot move the candidate's generated calls to a disposable connection by forking:
  - Any `my.*` call inside the candidate that has a supplied `:seon.db/connection` gets the connection of
    the context that evaluated it.
  - Under "evaluate once in the retained context", that is the agent's live branch.
  - `kernel/invoke` only rebinds `db/*conn*` (dynamic custody for elided `seon.db` arities) from the
    invoking ctx (kernel.clj:722).
- This is the same mechanism as task 0261d4d9bbe6 (acquired functions get no supplied defaults).
- The file-test executor is §11 Q1.
- Each option below crosses call_preparation / seon.test / turn / fn owners and needs a ruling.

### Three priced options for the remaining M2 (simplest first)

1. **Gate world = isolated execution, re-evaluate nothing, no file tests at the gate.**
   - `evaluate-candidate` acquires an isolated execution (`acquire-context!` isolate, ~50 ms), forks
     the evaluating ctx onto its connection (`fork-cluster-ctx`) and hands that connection to test bodies
     and `invoke`.
   - Gate tests are branch-authored tests only (§11 Q1 option c); file tests go to merge.
   - Guarantee: test bodies and top-level generated calls write the disposable branch.
   - Sacrifice: nested supplied connections inside the candidate still reach the live branch until 0261
     lands.
   - ~+25/−20 in eval.clj, a turn.clj key, schemas. Half a day.
2. **(Recommended) Option 1 plus wrapper-applied defaults (0261 / S12, already a ruled plan step).**
   - Supplied defaults are applied by the armed wrapper from the calling world's environment, not the
     creation ctx. Then every generated call, nested included, uses the proof world's custody. 0261 is
     fixed too.
   - Guarantee: "generated calls never touch the agent's branch" holds completely.
   - Cost: the call_preparation hook and instrument wrapper, about a day. Plus Astra review.
3. **Route the gate through `seon.test/run` (full harness).**
   - Write the analyzed row onto a disposable branch, then run `:named` gate tests there. The harness
     interprets the row in `clean-request-handle`.
   - File tests resolve their requires as compiled Vars. They still test the compiled function, not the
     candidate (the Q1 binding question).
   - Guarantee: one harness.
   - Sacrifice: the root is re-interpreted, not the evaluated value. ~1–1.5 s per gated defn with tests
     (845e1bce2592) until the checkpoint folds the overlay.
   - Cost: one to two days.

## Root runs (after `git apply tmp/rc-m2-test-world-opus/m2-slice1.patch`, both static checks, commit, restart)

1. `bin/test-check default --ns seon.test.accretion-test`
2. `bin/test-check default --ns seon.sci.restoration-test`
3. `bin/test-check default --ns seon.contracts-install-test`
4. `bin/test-check default --test seon.core-functions-test` (the auto-check test using `check`)
5. `bin/test-check default --test seon.fn-test/agent-source-reaches-the-evaluator-through-one-visible-path`
6. `bin/test-check default --ns seon.custody-stability-test`
7. `bin/test-check default --test seon.cluster.turn-test/…` and `seon.cluster.agent-test/…`: the two tests that
   `with-redefs` `gate-function-install` (turn_test:2594, agent_test:1310). Their arity is unchanged.
8. Acceptance through MCP SCI on a fresh branch:
   - `(do (defn smoke-inc "Adds one." {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)) (smoke-inc 1))`
     → 2 in under 1 s;
   - then `(smoke-inc "a")` → input refusal;
   - then a refused redefinition leaves `(smoke-inc 1)` → 2.
