---
type: landing
lane: fix-agent-path-opus
status: partial
date: 2026-09-26
base: 783b81fbe (default pid 41367)
commits: 38ab16d95
---

# fix-agent-path-opus: landing (stopped early on root's pause order)

## Broken things first

- **RESET NEEDED.** Neither fix is live. Default runs archive 783b81fbe.
- **Class resolution needs a fork patch, and I did not apply it.** The patch is
  `tmp/fix-agent-path-opus/sci-allow-all.patch` (reference-code/sci; not my file). Root applies it in the fork, commits
  and pushes it to `fork/seon-env-hook`, and bumps the gitlink. Until then, `(System/nanoTime)` still fails.
- **Not started, because of the pause:**
  - `defn` on a fresh branch: "evaluate-candidate refused … candidate-ctx nil (turn.clj:3319)".
  - The "Contract finding: seon.sci.kernel/failure-value …" line on every agent exception (render.clj:519-524,
    kernel.clj:559). Reproduced: `(System/nanoTime)` on root and `(m/validate …)` on a branch both print it.
  - The five `seon.sci.eval/acquisition-refusal` branch errors on cluster-default.

  All three stay open for the next lane.
- **No tests run** (load rule; root runs them).

## Defect 1: `(System/nanoTime)` fails to resolve on root and on branches (reproduced; cause found; patch for root)

- **What the JVM loaded.** It has the fork a88a1167 (`sci.impl.interop/host-class-opts` resolves, and it loads from
  `reference-code/sci/src`). It also has the committed eval.clj with `:classes {:allow :all}` and `:load-fn`. So stale
  code was not the cause.
- **Cause.**
  - `sci.impl.opts/normalize-classes` (`reference-code/sci/src/sci/impl/opts.cljc:161-171`) walks every entry of
    `:classes`, `:allow` included, and stores `:allow :all` as `{:class :all}`.
  - The fork's check `(identical? :all (:allow class->opts))` (`interop.cljc:201,205`) is therefore never true.
  - Upstream reads `:allow` by truthiness (`evaluator.cljc:265`).
- **Evidence.**
  - A fresh `build-base-ctx` on default's db has `(get-in @(:env ctx) [:class->opts :allow])` = `{:class :all}`, and
    `(System/nanoTime)` fails with "Unable to resolve symbol".
  - After `(swap! (:env ctx) assoc-in [:class->opts :allow] :all)`, the same ctx answers a number, and
    `(java.util.Date.)` works too.
- **Fix.** The patch makes the fork's check truthy, as upstream's is (2 lines).
- **Regression.** The existing
  `seon.sci.kernel-test/the-interpreter-resolves-the-classpath-and-loaded-namespaces` already asserts this. It was
  red at HEAD.

## Defect 2: `(require '[malli.core :as m])` then `(m/validate …)` fails to resolve on the next form (reproduced; fixed 38ab16d95)

- **Reproduced** on branch `fix-agent-path`:
  - `(require '[malli.core :as m])` → nil;
  - `(ns-aliases *ns*)` → `{m #namespace[malli.core]}`;
  - `(resolve 'malli.core/validate)` → false.

  The namespace and the alias survive, but the Vars do not.
- **Cause.**
  - `regenerate-agent-context!` (eval.clj, the `private-bindings` filter) carries only Vars stamped with the agent's
    `:sci/generation`.
  - `load-host-namespace` built its copies with `sci/copy-var*` (`core.cljc:112-137`), which stamps no generation.
  - So at the next turn or form the copies were dropped. The non-Var `:obj` and `:aliases` entries were kept, and
    SCI never reloads a namespace that is already present.
- **Fix.** `load-host-namespace` stamps each copy with the requiring context's generation, using
  `sci.impl.utils/generation-meta` (`utils.cljc:359`), as a `def` in that fork is stamped. +11/−3.
- **Proof.** A JVM read-only probe on default's db used `base-ctx`, then `fork-for-turn`, `require`, and
  `fork-for-turn` again with the agent ctx:
  - with the stamping load-fn: resolved true, `(m/validate int? 1)` → true (17 ms);
  - without it (the current code): false.
- **Regression.** `seon.sci.eval-test/a-namespace-an-agent-requires-keeps-its-vars-at-the-next-turn`.

## Root runs (after the fork patch and the reset)

1. `cd reference-code/sci && git apply ../../tmp/fix-agent-path-opus/sci-allow-all.patch`, then commit, push the
   fork and stage the gitlink.
2. `bin/test-check default --test seon.sci.kernel-test/the-interpreter-resolves-the-classpath-and-loaded-namespaces`
3. `bin/test-check default --test seon.sci.eval-test/a-namespace-an-agent-requires-keeps-its-vars-at-the-next-turn`
4. MCP SCI as root and on a fresh branch, each as a separate call:
   - `(System/nanoTime)` → a number
   - `(require '[malli.core :as m])`, then `(m/validate int? 1)` → true
   - `(defn f [x] (inc x))`, then `(f 1)` on a fresh branch. This is **expected still to fail** (candidate-ctx nil;
     not fixed).
   - `(/ 1 0)` is **expected still to print the contract-finding line** (not fixed).

Branch `fix-agent-path` is retired. There are no scratch namespaces (the probes were `let` forms).
