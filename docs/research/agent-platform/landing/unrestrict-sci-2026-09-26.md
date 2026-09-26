# unrestrict-sci-opus — audit #2 (classes, dependency namespaces) and #5 (nested-context arm), 2026-09-26

What exists: the agent interpreter is `seon.sci.eval/build-base-ctx` (`sci/init`); its arm is
`seon.sci.kernel/acquire-arm`. Approach: use SCI's own seams (`:classes {:allow :all}`, `:load-fn`,
the `sci.addons.future` addon) and one small fork change where upstream has no seam; delete the refusals.

## Broken things first

- **Default was OOM-killed at about 16:10Z** (pid 19859; `java.lang.OutOfMemoryError: Java heap space`, heap dump kept at
  `data/heap/java_pid19859.hprof`, 17.7 GB). The last thing this lane ran on it was one JVM-mode `base-ctx` derivation
  (2.9 s, cache miss) at about 16:08Z. Other lanes were active too, so I can't say this lane caused it. The JVM came
  back at 16:13Z (pid 27723) with `flow not running` in status.
- **Not live yet. RESET NEEDED.** The fork commit and the eval.clj hunk reach default only after a restart plus root
  applying the patches. The after-proofs through MCP SCI have **not run**. Every after-number below is from a
  scratch SCI context in the JVM, not from an agent branch.
- **Until the patches land, two existing tests assert the deleted refusal and will fail:**
  - `seon.sci.eval-test/a-foreign-armed-context-is-refused-as-a-value`
  - `seon.sci.kernel-arm-carriage-test/a-foreign-arm-refusal-identifies-the-existing-arm-and-site`
  Both are deleted by the patches below.
- **`seon.sci.kernel-test/the-interpreter-resolves-the-classpath-and-loaded-namespaces` is red until both land:**
  eval.patch and the restart that loads fork a88a1167.

## Reproduced (before, MCP SCI on branch `unrestrict-sci` off default basis 536871337)

| form | answer |
|---|---|
| `(System/nanoTime)` | `Unable to resolve symbol: System/nanoTime` (analysis failure, so `try` cannot catch it) |
| `(Math/abs -1)` | `Unable to resolve symbol: Math/abs` |
| `(require '[malli.core :as m])` | `Could not find namespace malli.core.` |
| `@(future 1)` | `Unable to resolve symbol: future` |
| `(seon.sci.kernel/with-arm <another base ctx> 5000 (fn [_] :entered))` | `A different SCI context is already armed on this thread. Existing arm … requested interpreter …` |

- `future` was missing for a different reason from classes: SCI ships it only as an opt-in addon
  (`reference-code/sci/src/sci/addons/future.clj:37-46`), and we never installed it.

## Causes

- **#2, classes.**
  - `:classes` was the `java.lang` Throwables only.
  - Upstream `:allow :all` only permits members of classes already *reachable*: it never resolves a new class name
    (`reference-code/sci/README.md:497-513`; `interop.cljc` `resolve-class-opts` reads `class->opts` only).
  - Upstream has no seam for this. The fork now falls back to the host.
- **#2, namespaces.** There was no `:load-fn`. SCI calls one for any namespace missing from the context
  (`reference-code/sci/src/sci/impl/load.cljc:204-243`).
  - Program namespaces are already in every base context. Live check on default: 463 program namespaces, 0 missing
    from the SCI env.
  - So a load-fn only ever sees dependency namespaces. It needs no program guard.
- **#5.** `acquire-arm` threw when the thread's arm named another interpreter.
  - Nested same-interpreter work already inherited the arm.
  - The guard and interrupt-fn are process-wide, so inheriting loses nothing: one latch, the outer deadline governs.

## Changes

- **reference-code/sci `a88a1167`** (pushed to `fork/seon-env-hook`): `interop/host-class-opts`, `resolve-class-opts`.
  - Under `{:classes {:allow :all}}`, an unconfigured class name resolves the way Clojure's compiler does:
    short names through `RT/DEFAULT_IMPORTS` (all of `java.lang`), dotted names through `Class/forName` on
    `RT/baseLoader`, and imports through the imported name.
  - A record or protocol the interpreter defines under the same name still wins. Example: `clojure.lang.IDeref` stays
    SCI's protocol (`namespaces.cljc:939-955`).
  - Size: +27/−3. It is JVM-only (`#?(:clj …)`).
- **Commit `b086ecd42`, `src/seon/sci/kernel.clj`:**
  - Deleted the foreign-context throw, `same-interpreter?`, `context-evidence`, `arm-evidence`, and the per-arm
    `::arm-token`, `::armed-at` (a stack capture on *every* arm) and `::ctx`.
  - Added `future-call`: `clojure.core/future-call` carrying `current-arm` into `adopt-arm`, so a future's
    interpreted work is cut by the evaluation's one deadline.
  - Docstrings corrected. Net 34 lines added, 71 deleted.
- **Same commit, `test/seon/sci/kernel_test.clj`:** two class regressions (+74). Both run through the agent's
  `eval/evaluate` on `build-base-ctx`, the interpreter an agent gets.
- **`tmp/unrestrict-sci-opus/eval.patch`** (src/seon/sci/eval.clj, held by inv-agent-run-stability-opus; root forwards):
  - Deletes `java-lang-throwables` (22 lines).
  - Passes `:classes {:allow :all}`.
  - Adds `:load-fn load-host-namespace`. It copies a JVM-loaded namespace's publics with the existing
    `copy-host-var`.
  - Installs `sci.addons.future` with `future-call` set to `kernel/future-call`.
  - Corrects the ns docstring. Net 28 lines added, 23 deleted.
- **`tmp/unrestrict-sci-opus/eval_test.patch`** (−31) and **`tmp/unrestrict-sci-opus/kernel_arm_carriage_test.patch`**
  (−24): delete the two tests that asserted the refusal.
- **Net source** is about −42 across kernel.clj and eval.clj, plus 24 lines in the fork. Tests: +74 added, −55 deleted.
- **Contracts** compile (`seon.contracts-compile-test/check` over kernel.clj, kernel_test.clj and the patched eval.clj
  returns `[]`; the committed-tree static test passes). The scratch copies are unarmed, so I also validated these
  explicitly against real SCI values:
  - the `load-host-namespace` input (keys `:ctx :libname :namespace :ns :opts :reload`) against
    `[:map [:namespace :symbol] [:ctx :map]]`: true;
  - its output `{}`/nil against `[:maybe :map]`: true;
  - the `future-call` output against `[:fn future?]`: true.

## KEEP (not a reason to deny classes)

- **A host call is not interruptible** (`reference-code/sci/doc/interrupt.md:84-98`). With every class reachable,
  `(Thread/sleep 60000)` or a blocking socket read runs past the deadline, and a raw `(Thread. f)` or executor
  submission escapes the arm entirely. The deadline still names this; `deadline-remaining-ms` exists for blocking
  host work to honour.
- **`future` carries the arm; the addon's `pmap` does not.** `pmap` runs `f` on unarmed threads up to about n+2
  elements ahead of the armed consumer.
- **Copied dependency Vars are copies at their roots** (SCI `copy-var*`, `core.cljc:112-137`). An agent's `binding` of
  a copied dynamic Var (for example a library's `*registry*`) does not reach the compiled library code that reads it.
  JVM-only macros such as `go` copy, but may not expand in SCI.

## Cost (before; after measured on a scratch ctx)

- **`build-base-ctx`: 0.71 ms (before).** After: unchanged by construction. `:load-fn` and `:classes` are constants;
  nothing is exposed at build time and nothing is rebuilt per fork. `sci/fork` is 0.0007 ms.
- **Base-context derivation** (a `base-ctx` cache miss) was 2,925 ms on default, dominated by `acquire-program!`.
  This change does not touch it.
- **`(require '[malli.core :as m])`: 1.18 ms**, copying 243 publics. It happens once per context, at the agent's
  require, never per fork or turn, so no cache is needed. Measured in a scratch SCI ctx with the same load-fn.
- **An unresolvable dotted symbol** now costs one `ClassNotFoundException` at analysis. Class resolution runs last in
  `resolve.cljc` (after locals, namespaces and aliases), so only genuinely unresolved names pay it.
- **Each arm no longer captures a 12-frame stack trace.**

## Scratch proof of the pieces (JVM mode, scratch namespace `tmp.unrestrict-sci`, since removed)

- **SCI ctx with the load-fn and future addon:**
  - `(m/validate int? 1)` → true; `(m/validate [:map [:a int?]] {:a "x"})` → false; `m/explain` returns errors.
  - `(require '[datahike.api :as d])` works; `(fn? d/q)` → true.
  - `(str (io/file "a" "b"))` → "a/b".
  - `@(future 1)` → 1.
  - `(require 'no.such.ns)` → "Could not find namespace: no.such.ns."
- **Host fallback logic on symbols:**
  - `System`, `Math`, `Throwable`, `IllegalStateException` and `java.util.Date` resolve to classes.
  - `java.util.date`, `no.such.Klass` and `str` give nil.
  - `clojure.lang.IDeref` gives the class, but the fork's SCI-namespace check returns SCI's protocol first.

## Root runs

1. Apply the patches (eval.clj via inv-agent-run-stability-opus):
   `git apply tmp/unrestrict-sci-opus/eval.patch tmp/unrestrict-sci-opus/eval_test.patch tmp/unrestrict-sci-opus/kernel_arm_carriage_test.patch`
2. Also delete the Throwable special case in `fn.clj:1053` (audit #2; fn.clj is not mine). After this change a
   `java.util.Date` body stops being host-bound only once audit #1's `host-bound-callers` java-class clause goes.
3. **RESET NEEDED**: restart default so fork a88a1167 loads (`bin/seon down && bin/seon start`).
4. `bin/test-check default --ns seon.sci.kernel-test`
5. `bin/test-check default --ns seon.sci.kernel-arm-carriage-test`
6. `bin/test-check default --test seon.sci.eval-test/a-selected-render-inherits-the-live-arm-or-owns-one-when-unarmed`
7. MCP SCI on a fresh branch, where each should answer:
   - `(System/nanoTime)` → a number
   - `(Math/abs -1)` → 1
   - `(require '[malli.core :as m])` then `(m/validate int? 1)` → true
   - `@(future 1)` → 1
   - `(seon.sci.kernel/with-arm (seon.sci.eval/build-base-ctx (seon.schema/projection-from-database (seon.db/db))) 5000 (fn [_] :entered))` → `:entered`
   - the audit's gated redefinition of `seon.agent/archived?` is no longer refused by its reaching test with "A
     different SCI context".
8. The reaching gate for kernel.clj's changed functions (`acquire-arm`, `with-arm`, `detached-arm`, `new-armed`).

The branch `unrestrict-sci` is retired.
