---
type: landing
status: stopped-for-ruling
created: 2026-09-24
tags: [agent-platform, sci, contracts, entry]
---

# Lane fix-entry-eval-2: printed output, nested definitions, refers (2026-09-24)

Owned: `src/seon/sci/eval.clj`, `test/seon/sci/entry_gate_test.clj`, `test/seon/sci/eval_test.clj`, this note.
**No source change landed. No commit.** Default was not touched (branch `fix-entry-eval-2` created and retired).

## Broken or open first

1. **Item (2): the fork-and-adopt design is not supported by SCI's ctx structure. Stopped, as instructed.**
   Adopting a fork's `:env` into the retained ctx replaces redefined Vars with *new Var objects*.
   Existing interpreted callers keep the old Var, so a redefinition is invisible to them.
   - Cause: a fork gets a new `:sci/generation` (`reference-code/sci/src/sci/core.cljc:345-351`, pinned
     `fcbd88628`). `eval-def` copies any Var whose generation differs from the env's
     (`src/sci/impl/evaluator.cljc:37-43`: `lang/->Var @prev …`). `bind-root!` does the same
     (`src/sci/impl/utils.cljc:367-379`). The SCI analyzer holds the Var object in callers.
   - Probe (JVM, pure `sci/init`): defined `f`→1 and `g` calling `f`. Evaluated `(defn f [] 2)` in a
     fork, then `reset!` the parent env to the fork's env (generation restored). `(f)` returned 2,
     `(g)` returned **1**, `identical?` old/new Var false.
   - Transferring roots instead (`sci/intern` → `bind-root!` in place; the existing
     `transfer-evaluated-roots!` / `accept-candidate!` seam, `eval.clj:1110`, `:3696`) keeps `(g)` = 2.
     But it loses every non-Var effect of the form: after
     `(require '[clojure.string :as s]) (def x 5) (defn f [] 2)` in the fork, the parent had
     no alias `s` and no `x`. For this reason `evaluate-for-install` forks only when the reader
     reports a top-level function.
   - Cost is not the obstacle. On default's ctx (122 namespaces), `sci/fork` + a one-def eval took
     6–19 µs and the namespace-map identity diff 29–57 µs (5 warm runs).
   - Three options for root, simplest first:
     - **(A) recommended.** No fork. Evaluate in the retained ctx as today. Take the definition diff
       `evaluate` already computes (`bindings`/`turn-interns`: every created or replaced
       generation-owned Var with value and metadata). Refuse if any fn-valued one lacks
       `:malli/schema`, restoring each changed Var's prior root and meta in place and unmapping
       new ones. Arm every contracted one (today `definition-row` uses `some`, so only the first
       is armed).
       - Guarantee: gate and arming cover `(do (defn …))`, `let`, `(def f (fn …))` and macros.
         Var identity and aliases are preserved.
       - Cost: about 30–40 lines, no extra per-eval work.
       - Sacrifice: the form's other side effects (require, atoms, IO) stay after a refusal.
     - **(B)** Fork always. Transfer roots plus namespace-binding changes
       (`install-namespace-bindings!`) and newly created namespaces.
       - Cost: about 80–120 lines.
       - Sacrifice: still loses other env state the fork mutates, such as schema registration
         and imports of classes, and duplicates the part of adoption SCI does not offer.
     - **(C)** Adopt the fork env (root's design): refuted above.
   - Limit common to all three: a call to `f` inside the same form that defines it runs before the check.
2. **Item (1) was cancelled by root. My change for it was reverted and not committed.**
   The premise was wrong: `evaluate` already returns printed text as `:seon.cluster.eval/output`.
   `(do (println "hi") 1)` on default gives output `"hi\n"`, shown `"1"`. The toy loop read only
   `:seon.eval/shown`. The real defect found is that printed output is **unbounded**: 20,000 prints
   came back as a 200,744-character result, and the namespace docstring falsely claims a
   `max-string` cap. Filed as `docs/seon/issues/agent-printed-output-has-no-storage-bound.md`.
   The reverted patch is kept at `tmp/fix-entry-2-output-bound.patch`. It streams output under
   `max-bytes` and refuses with `:seon.sci.admit/error`.
   Its regression passed on a HEAD+patch `git archive` root: run `cedb990fbe64`, 4 executed,
   18 pass / 0 fail, 7,575 ms.
3. **Item (3): the 7 refer targets without a SCI Var.** A live probe of default's 770 stored
   refers against `sci/namespace-state` finds 7 unresolved. No exemption was added.
   - `seon.cluster` refers `running-instances` and `root-store-holder`; `seon.cluster.boot`,
     `seon.mcp-test` and `seon.cluster.mcp-test` refer `running-instances`; `seon.cluster.store`
     refers `held-flocks`. All target `seon.operator.runtime`. It lives in
     `resources/seon/operator/runtime.clj`, deliberately outside the indexed roots (its ns
     docstring). It has no `:seon.ns` row, so acquisition never installs it into SCI, and
     `custody_stability_test.clj:203-210` asserts that. Its three `defonce` atoms are process-root
     custody, never program identities.
   - `seon.ai-test` refers `clojure.test.check.clojure-test/defspec`, a third-party macro
     namespace not installed in SCI.
   - Proposed owner fixes, which make "install refers for every row" safe with no filter:
     - Convert the five `:refer` sites of `seon.operator.runtime` (`cluster.clj:53`,
       `cluster/boot.clj:22`, `cluster/store.clj:26`, `test/seon/mcp_test.clj:10`, and the
       `test/seon/cluster/mcp_test.clj` ns form) to the alias most callers already use
       (`[seon.operator.runtime :as operator.runtime]`). Aliases now install for every row. An
       interpreted override that reaches process custody then refuses at analysis by name.
     - Convert `test/seon/ai_test.clj:496` `defspec` to `deftest` +
       `seon.test-support/assert-check!`. The testing skill names this as the canonical property helper.
     - After both, a future unresolvable refer should refuse loudly
       (`sci/core.cljc:808-822`), not be filtered.

## Evidence and numbers

- Named probe before (default, archive `ce7384682`, warm): `(do (println "hi") 1)` took 9 ms and
  9,706,480 B allocated. Default's first evaluation on the new branch (acquisition) took 853 ms and 1.70 GB.
- No after-number, because no change landed.
- The scratch root boot for the patch proof took **3 min 42 s** (existing issue `from-zero-boot-takes-minutes`, over ten seconds).
- The scratch root and its source were stopped (`process-exit? true`) and deleted. The logs are kept at
  `tmp/fix-entry-2-start.log` and `tmp/fix-entry-2-test.log`.

## Net lines

Owned source and tests: 0 (reverted). New issue file: 1. This note: 1.
