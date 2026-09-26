---
type: landing
status: unproven
lane: agent-repl-gaps-opus
date: 2026-09-26
base: 371e1e223
---

# agent-repl-gaps-opus — landing (2026-09-26)

`clojure.test` and `clojure.repl/source` in the agent REPL. The agent's `clojure.test` is the host namespace copied in
(`resources/seon/schemas/seon.sci.binding.edn:5`). Its `source-fn` is SCI's, which reads files. The fix binds report
output to the evaluation's writer, and gives the agent context SCI-aware namespace walkers and a `source-fn` that reads
the stored program row.

## Broken or unproven first

- **No installed proof.** Default's flow is down (`bin/seon status`: "flow not running"), and my edits are not adopted.
  I could not prove either fix through MCP SCI on a branch, and I ran neither regression. **RESET NEEDED**: root adopts
  HEAD, then runs the two regressions below.
- **The schema member needs root.** `my.test/run-request` should declare `:seon.ns/name` as optional. The patch is
  `tmp/agent-repl-gaps-opus/my-test-run-request-names-a-namespace.patch` (that resource is not my file). Until it is
  applied, the key passes only because the `:map` is open.
- **Ugly output (not fixed).** A `FAIL` line names the file and line of SCI's own frame, e.g.
  `FAIL in (fails) (lang.cljc:217)`. The cause is that host `clojure.test/do-report` takes the location from the JVM
  stack. clojure.main shows the test's own line.
- **Parity gaps (not fixed).**
  - `test-ns-hook` is not honoured.
  - A custom `report` method for `:begin-test-ns` is bypassed, because the "Testing ns" line is printed directly. The
    host method calls the host `ns-name`, which cannot name a SCI namespace.
  - `run-all-tests` still walks the host's namespaces.
- **Size.** Source is +112/−8 lines (eval.clj +97/−2, my/test.clj +15/−6), and nothing was deleted. That is above the
  ~40-line estimate. The extra lines are contracts, docstrings, the `use-fixtures` replacement and the `source` reader.

## Defect 1: `clojure.test` reports nowhere, and cannot run the agent's namespace

- **Library check.** SCI ships no `clojure.test`. Babashka's SCI-side copy is about 840 lines
  (`reference-code/babashka/src/babashka/impl/clojure/test.clj`) and is not on our classpath. I used its method, not
  its code: `sci-the-ns*` and `sci-ns-interns*` over the evaluating ctx (`test.clj:750-790`).
- **Cause.** Two causes:
  - Host `clojure.test/report` prints through `with-test-out`, which uses the host `*test-out*`. Nothing bound it, so
    reports went to the JVM's stdout.
  - Host `run-tests` and `test-ns` resolve namespaces with the host `the-ns`, so "No namespace: … found".
- **Fix, in `src/seon/sci/eval.clj`.**
  - `eval-form!` binds `clojure.test/*test-out*` to the evaluation's `printed` writer, beside the read-evidence sink.
  - `agent-test-all-vars`, `agent-test-ns`, `agent-run-tests` and `agent-use-fixtures` replace those four entries of
    the copied namespace in `build-base-ctx`.
    - They find namespaces through `sci.ctx-store/get-ctx`, which `sci/eval-form` binds
      (`reference-code/sci/src/sci/impl/interpreter.cljc:82`).
    - They call the host `test-vars`, `test-var` and `do-report` unchanged.
    - `use-fixtures` alters the SCI namespace's metadata, which is where the host `test-vars` reads fixtures.
- **Fix, in `src/my/test.clj`.** The `my.test/run` macro supplies `(ns-name *ns*)` as `:seon.ns/name` unless the
  request names one. `run-owned` then selects that namespace's stored tests; without a name it keeps `owned-symbols`.
- **REPL evidence.** A JVM-mode scratch probe used the same composition: a fresh `sci/init`, the copied `clojure.test`
  with the four replacements, and host `*test-out*` bound to a StringWriter.
  - Calling a failing `deftest` directly printed `FAIL in (fails) … expected: (= 1 2)`.
  - `(clojure.test/run-tests)` and `(run-tests 'probe.wc)` each answered `{:test 2 :pass 2 :fail 1 :error 0 :type :summary}`
    and printed "Testing probe.wc … Ran 2 tests containing 3 assertions. 1 failures, 0 errors."
  - The `:each` fixture ran 4 times (2 tests × 2 runs).
  - It took 2.8 ms.
- **Regression.** `seon.sci.documentation-test/clojure-test-at-the-agent-repl-reports-into-the-evaluations-output`
  (through `evaluation/evaluate`: a direct call, `run-tests`, and `run-tests 'ns`).

## Defect 2: `source` says "Source not found"

- **Cause.** SCI's `clojure.repl/source` macro prints `(clojure.repl/source-fn 'x)`
  (`reference-code/sci/src/sci/impl/namespaces.cljc:2436`). SCI's `source-fn` reads the Var's `:file`, or the
  `:load-fn`'s `:source`. An agent's declaration has neither: its source is a program row.
- **Fix.**
  - `seon.sci.eval/stored-source [database qualified]` is the reader. It asks each program family in
    `program/shapes-in` of the database's projection whose identity schema admits the value, then reads that family's
    declared source attribute. A read refusal throws.
  - `install-program-doc!` replaces only `clojure.repl/source-fn`, with `stored-source-fn`. That function resolves the
    symbol in the calling SCI namespace, so aliases and refers work, and reads through the evaluation's custody.
    SCI's own `source` macro is kept.
  - `doc` already reads facts (`program-doc-var`), so it has no gap.
- **REPL evidence.** A read-only probe against default's database took 6.6 ms:
  - `my.test/check` answered its stored source;
  - the namespace `my.test` answered its stored `ns` form;
  - `nope/nothing` answered nil.
  - Keying `seon.db/q` by every identity attribute was refused loudly at first (`:seon.schema/key` is keyword-typed),
    which is why the reader selects families by their identity schema.
- **Regression.** `seon.sci.documentation-test/source-at-the-agent-repl-prints-the-stored-declaration` (qualified,
  aliased, missing, and the reader value).

## Static checks (before commit)

`seon.contracts-compile-test/check` over `src/seon/sci/eval.clj` and `src/my/test.clj` returned `[]` (424 ms). Every
`resources/seon/schemas/*.edn` reads as an EDN map. `clj-kondo` reports 0 errors.

## Root runs (after adopting HEAD, serially)

```
bin/test-check default --test seon.sci.documentation-test/clojure-test-at-the-agent-repl-reports-into-the-evaluations-output
bin/test-check default --test seon.sci.documentation-test/source-at-the-agent-repl-prints-the-stored-declaration
```

Then, on a branch: `(clojure.test/run-tests)`, `(my.test/run)` from `(in-ns …)`, and `(clojure.repl/source <branch fn>)`.
