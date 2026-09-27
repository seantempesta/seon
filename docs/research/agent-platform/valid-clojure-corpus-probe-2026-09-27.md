---
type: research
status: evidence (no source changed)
created: 2026-09-27
branch: refactor/agent-platform
lane: probe-valid-clojure-opus
source-pin: live default e9877da0f / a2fd4676c (runs); 95f44cbc3 (h38 re-run, h39–h42); checkout src identical to 95f44cbc3 except bd3d35b19's cluster.clj
corpus: tmp/valid-clojure-corpus/ (items/*.clj, runner.clj, plain.clj, summarize.bb, table.bb, verdicts.edn, results/*.edn) — under the ignored tmp/, not committed
branches: corpus-1 (lost to root's 17:24Z restart, retired), corpus-2 (items), corpus-3 (h38); all retired at the end
---

# Does Seon run all valid Clojure? A corpus probe

## Answer

SCI is not what fails. SCI evaluated every item in the corpus except `definterface`, which SCI does not implement.
Every Seon divergence comes from a layer Seon adds on top of SCI:

- context construction: F1, where a copied JVM `import` macro shadows SCI's `import` special form;
- admission: F2–F5, F8, and F3's position heuristic;
- recording: F7, F10, F11;
- settlement: F6, F9;
- reply: the F6, F8 and F10 faces.

clj-kondo, through the project's analyzer entry, sees the nested definition in every `do`, `let`, `when` and
literal-macro-wrapper placement. It misses only names built by syntax-quote (h40). Where Seon refuses a nested
definition, kondo is not the cause. Seon's own matching rules are (F3, F4).

The owner's four known failures, re-checked:

- `(do (defn …))` is admitted and armed now (m-defn-do).
- `(do (in-ns 'x) 1) (+ 1 2)` still loses the whole run (h03, F6).
- A nested `register!` is still refused (h15, h37, F5).
- The 22-form `register!` run settles at 14.9 s when its forms are top-level (h36, F12). When the same forms are
  nested in one `do`, the run is refused (h37, F5).

Root's schema-redefinition input (h38) reproduced the data loss at a2fd4676c: adoption retracted all 459
`:seon.cluster.eval/read-evidence` values and the single `:seon.cluster.eval/faults` value. The writer then refused
the settlement with `unowned-entity 54842`. bd3d35b19 fixed this. At 95f44cbc3 the same run settles in 1.67 s with
zero retractions.

The corpus also killed default once. At about 17:28Z, one item returned `(range)`. Seon realizes the whole result
after evaluation (F7), and the heap filled. The OutOfMemoryError terminated pid 63809, and the heap dump is
`data/heap/java_pid63809.hprof` (19,064,293,155 bytes, preserved). The item is now bounded (h35, 1e6 elements), and
the realization site is proved. Section F7 has the details.

## Method

A **run** is one or more forms submitted as one ordinary run of the hosted branch agent `mcp/<branch>`. The runner
calls exactly what MCP `eval_clj sci` with a branch calls:

- `seon.cluster.host/host!`, then
- `seon.cluster.agent/evaluate-source!` with segmenter `seon.cluster.reply/code-sources`, then
- `seon.cluster.agent/run-observation` (script/seon/dev/mcp.clj:402-452).

Each run starts in `user`. One-form runs through MCP `eval_clj sci branch=corpus-2` gave the same shapes. Those were
the smoke test, the F7 realization probe and the F1 `resolve` probe.

For every item the runner records the following:

- **Seon reply:** the per-form response the agent's next context reads.
- **Seon stored:** the diff of `:seon.fn/sym` definition digests, armed contracts (`:seon.fn/spec`),
  `:seon.test/sym`, `:seon.schema/key` and `:seon.ns/name` between the branch head before the run and the
  settlement's basis.
- **Committed:** whether `evaluate-source!` answered a settled database. "NO" means the caller got no receipts.
- **Wall time** of the run.

Two reference answers come from a separate `clojure -J-Xmx512m` scratch JVM (tmp/valid-clojure-corpus/plain.clj),
never from default:

- plain Clojure 1.12 `read` + `eval` per form, continuing after errors like `clojure.main`;
- plain SCI (`reference-code/sci`, `sci/init {}`), `parse-next` + `eval-form` per form.

clj-kondo ran inside default through `seon.fn.analyzer/analyze` with `::sources` at a non-checkout path, so
`analyze` sets `:cache false` (analyzer.clj:809-820) and the shared cache is neither read nor written.

The matrix covers nine definition kinds × five placements:

- kinds: defn with a contract, defn without one, def, defmacro, defmulti+defmethod, defprotocol, defrecord, deftype,
  definterface;
- placements: top level, `(do …)`, `(let [unused 0] …)`, `(when true …)`, and a user macro `(wrap …)` that expands
  to `(do …)`.

Each generated item then calls what it defined in a second top-level form. There are 42 further hand items, h01–h42.

## Table

Columns: Clojure and SCI give each form's result in order. Kondo gives the definitions it reports. The Seon reply
gives each form's value or `ERR` message. Stored is the program-row diff. Committed/ms is whether a settled answer
came back, and the wall time. The verdict and class point to the failure classes below. "no row" means evaluated
but private to the SCI context: lost at restart, never merged.

| item | plain Clojure | plain SCI | kondo sees | Seon reply | Seon stored | committed | verdict | class |
|---|---|---|---|---|---|---|---|---|
| h01-ns-require | nil; "A"; #{1 2}; #object[clojure.lang.Namespace 0x30f929ff … | nil; A; #{1 2}; #object[sci.lang.Namespace 0x7a62e096 "user"… |  | nil; "A"; #{1 2}; #namespace[user] | {:namespaces #{corpus.h01}} | yes 1102 ms | PASS | — |
| h02-in-ns-top | #object[clojure.lang.Namespace 0x116c43d4 "corpus.h02"]; nil… | #object[sci.lang.Namespace 0x31d96cd6 "corpus.h02"]; nil; #'… | corpus.h02/x | #namespace[corpus.h02]; nil; #'corpus.h02/x; 2; #namespace[user] | {:namespaces #{corpus.h02}} | yes 189 ms | PASS | — |
| h03-in-ns-nested | 1; ERR Unable to resolve symbol: + in this context | 1; 3 |  | no receipts: #:seon.error{:message "The awaited channel closed before completion. Fix: pub… | nothing | NO 177 ms | FAIL: run lost | F6 |
| h04-several-ns | nil; #'corpus.h04a/f; nil; :a; #object[clojure.lang.Namespac… | nil; #'corpus.h04a/f; nil; :a; #object[sci.lang.Namespace 0x… | corpus.h04a/f | nil; #'corpus.h04a/f; nil; :a; #namespace[user] | {:fns #{corpus.h04a/f}, :armed #{corpus.h04a/f}, :namespaces #{corpus.… | yes 1463 ms | PASS (+ h03's fault delivered here) | F6 |
| h05-declare | #'user/h05-g; #'user/h05-f; #'user/h05-g; 2 | #'user/h05-g; #'user/h05-f; #'user/h05-g; 2 | user/h05-g user/h05-f user/h05-g | #'user/h05-g; ERR #:seon.error{:member :seon.test.accretion/install?, :expected true, :mes… | {:fns #{user/h05-g}, :armed #{user/h05-g}} | yes 741 ms | REFUSED | F8 |
| h06-letfn | true | true |  | true | nothing | yes 175 ms | PASS | — |
| h07-reader-conditional | :jvm; [1 2] | :jvm; [1 2] |  | :jvm; [1 2] | nothing | yes 33 ms | PASS | — |
| h08-tagged-literals | #inst "2026-01-01T00:00:00.000-00:00"; #uuid "6ab94f6d-91bb-… | #inst "2026-01-01T00:00:00.000-00:00"; #uuid "6ab94f6d-91bb-… |  | #inst "2026-01-01T00:00:00Z"; #uuid "6ab94f6d-91bb-592b-8a60-fe1d0853d193" | nothing | yes 29 ms | PASS | — |
| h09-metadata | #'user/h09-v; {:private true, :doc "A private value."}; {:k … | #'user/h09-v; {:private true, :doc "A private value."}; {:k … | user/h09-v | #'user/h09-v; {:private true, :doc "A private value."}; {:k true} | nothing | yes 55 ms | PASS | — |
| h10-syntax-quote | (user/a user/b 3 4 5); nil | (user/a user/b 3 4 5); nil |  | (user/a user/b 3 4 5); nil | nothing | yes 48 ms | PASS | — |
| h11-redefinition | #'user/h11-f; #'user/h11-f; 2 | #'user/h11-f; #'user/h11-f; 2 | user/h11-f user/h11-f | #'user/h11-f; #'user/h11-f; 2 | {:fns #{user/h11-f}, :armed #{user/h11-f}} | yes 980 ms | PASS | — |
| h12-ns-unmap | #'user/h12-v; nil; nil | #'user/h12-v; nil; nil | user/h12-v | #'user/h12-v; nil; nil | nothing | yes 351 ms | PASS | — |
| h13-program-ns-unmap | #'user/h13-f; ERR my.program; #'user/h13-f | #'user/h13-f; ERR Unable to resolve symbol: my.program/ns-un… | user/h13-f | #'user/h13-f; "No program declaration names user/h13-f."; #'user/h13-f | {:fns #{user/h13-f}, :armed #{user/h13-f}} | yes 739 ms | DIVERGES | F10 |
| h14-register-top | ERR seon.schema | ERR Unable to resolve symbol: seon.schema/register! |  | :corpus.h14/thing | {:schemas #{:corpus.h14/thing}} | yes 434 ms | PASS | — |
| h15-register-nested | ERR seon.schema | ERR Unable to resolve symbol: seon.schema/register! |  | ERR Execution error (ExceptionInfo) at (REPL:1:5). Schema declarations require an isolated… | nothing | yes 491 ms | REFUSED | F5 |
| h16-register-then-use | ERR seon.schema; #'user/h16-f; 3 | ERR Unable to resolve symbol: seon.schema/register!; #'user/… | user/h16-f | :corpus.h16/n; #'user/h16-f; 3 | {:fns #{user/h16-f}, :armed #{user/h16-f}, :schemas #{:corpus.h16/n}} | yes 1008 ms | PASS | — |
| h17-binding-set | #'user/*h17*; 3; "(0 1 ...)" | #'user/*h17*; 3; (0 1 ...) | user/*h17* | #'user/*h17*; 3; "(0 1 ...)" | nothing | yes 191 ms | PASS | — |
| h18-destructuring | [1 1 (2 3)]; #'user/h18-f; 5 | [1 1 (2 3)]; #'user/h18-f; 5 | user/h18-f | [1 1 (2 3)]; #'user/h18-f; 5 | {:fns #{user/h18-f}, :armed #{user/h18-f}} | yes 508 ms | PASS | — |
| h19-throw-mid-run | #'user/h19-a; ERR Divide by zero; #'user/h19-b; [1 2] | #'user/h19-a; ERR Divide by zero; #'user/h19-b; [1 2] | user/h19-a user/h19-b | #'user/h19-a; ERR Execution error (ArithmeticException) at (REPL:1:1). Divide by zero; #'u… | nothing | yes 195 ms | PASS | — |
| h20-unbalanced | #'user/h20-a; READ java.lang.RuntimeException: EOF while rea… | #'user/h20-a; READ EOF while reading, expected ) to match ( … | user/h20-a | #'user/h20-a; ERR EOF while reading, expected ) to match ( at [1,1] | nothing | yes 39 ms | PASS | — |
| h21-large-range | [0 1 2 3 4 5 6 7 ...] | [0 1 2 3 4 5 6 7 ...] |  | #:seon.repl{:value [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26… | nothing | yes 96 ms | PASS | — |
| h22-large-string | "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx… | xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx… |  | #:seon.repl{:value "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx… | nothing | yes 113 ms | PASS | — |
| h23-large-def | #'user/h23-v; 100000 | #'user/h23-v; 100000 | user/h23-v | #'user/h23-v; 100000 | nothing | yes 61 ms | PASS | — |
| h24-defonce | #'user/h24-a; 1 | #'user/h24-a; 1 | user/h24-a | #'user/h24-a; 1 | nothing | yes 51 ms | PASS eval, no row | F11 |
| h25-deftest | nil; #'user/h25-f; #'user/h25-t; nil | ERR Could not find namespace clojure.test.; #'user/h25-f; ER… | user/h25-f user/h25-t | nil; #'user/h25-f; #'user/h25-t; nil | {:fns #{user/h25-f}, :armed #{user/h25-f}, :tests #{user/h25-t}} | yes 882 ms | PASS | — |
| h26-qualified-defn | #object[clojure.lang.Namespace 0x3c73d613 "corpus.h26"]; ERR… | #object[sci.lang.Namespace 0x5126c0cd "corpus.h26"]; ERR Var… | user/corpus.h26/f | #namespace[corpus.h26]; ERR Execution error (ExceptionInfo) at (REPL:1:1). Var name should… | nothing | yes 192 ms | FAIL (same as Clojure; law claims support) | spec |
| h27-contract-refusal | #'user/h27-f; "a"; 1 | #'user/h27-f; a; 1 | user/h27-f | #'user/h27-f; ERR user/h27-f refused argument 0 (0-based) at []: expected an integer, got … | {:fns #{user/h27-f}, :armed #{user/h27-f}} | yes 708 ms | PASS (contract refusal by design) | — |
| h28-bare-tokens | 1; :k; "s"; \c; nil; nil | 1; :k; s; \c; nil; nil |  | 1; :k; "s"; \c; nil; nil | nothing | yes 198 ms | PASS | — |
| h29-comments-discard | nil; 2 | nil; 2 |  | nil; 2 | nothing | yes 35 ms | PASS | — |
| h30-require-import | nil; {:a 1}; java.util.UUID; true | nil; {:a 1}; ERR Unable to resolve classname: java.util.UUID… |  | nil; {:a 1}; ERR Execution error (ExceptionInfo) at (REPL:1:1). Unable to resolve symbol: … | nothing | yes 574 ms | FAIL | F1 |
| h31-def-no-value | #'user/h31-v; false | #'user/h31-v; false | user/h31-v | #'user/h31-v; false | nothing | yes 183 ms | PASS | — |
| h32-multi-arity | #'user/h32-f; [10 20] | #'user/h32-f; [10 20] | user/h32-f | ERR #:seon.error{:member :seon.test.accretion/install?, :expected true, :message "Fix the … | nothing | yes 150 ms | REFUSED | F8 |
| h33-loop-anon | [0 1 2]; (1 4 9) | [0 1 2]; (1 4 9) |  | [0 1 2]; (1 4 9) | nothing | yes 36 ms | PASS | — |
| h34-extend-protocol | H34P; nil; [3 :r] | H34P; #object[clojure.lang.MultiFn 0x181cafd "clojure.lang.M… | user/H34P user/h34-m | H34P; #object[clojure.lang.MultiFn]; [3 :r] | nothing | yes 53 ms | PASS eval, no row | F11 |
| h35-lazy-large | #'user/h35-seen; (0 1 2 3 4 5 6 7 ...); 32 | #'user/h35-seen; (0 1 2 3 4 5 6 7 ...); 32 | user/h35-seen | #'user/h35-seen; #:seon.repl{:value (0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 … | nothing | yes 561 ms | PASS reply; whole seq realized after | F7 |
| h36-register-22 | ERR seon.schema; ERR seon.schema; ERR seon.schema; ERR seon.… | ERR Unable to resolve symbol: seon.schema/register!; ERR Una… |  | :corpus.h36/k1; :corpus.h36/k2; :corpus.h36/k3; :corpus.h36/k4; :corpus.h36/k5; :corpus.h3… | {:schemas #{:corpus.h36/k19 :corpus.h36/k16 :corpus.h36/k20 :corpus.h3… | yes 14858 ms | PASS, 14.9 s | F12 |
| h37-register-22-in-do | ERR seon.schema | ERR Unable to resolve symbol: seon.schema/register! |  | ERR Execution error (ExceptionInfo) at (REPL:2:2). Schema declarations require an isolated… | nothing | yes 649 ms | REFUSED | F5 |
| h38-redefine-entity-schema |  |  |  | :corpus.h38/note; :seon.cluster.eval/receipt | {:schemas #{:corpus.h38/note}} | yes 1672 ms | PASS at 95f44cbc3 (refused at a2fd4676c) | F9 (fixed) |
| h39-two-defns-in-let | #'user/h39-b; [2 0] | #'user/h39-b; [2 0] | user/h39-a user/h39-b | ERR One form may make one program declaration; this one makes user/h39-a, user/h39-b. Send… | nothing | yes 417 ms | REFUSED | F4 |
| h40-macro-two-defns | #'user/h40-defpair; #'user/h40-y; [1 2] | #'user/h40-defpair; #'user/h40-y; [1 2] | user/h40-defpair | #'user/h40-defpair; ERR This form changed user/h40-x, user/h40-y, which its analysis decla… | nothing | yes 442 ms | REFUSED | F4 |
| h41-defn-when-timing-1 | #'user/h41-f1 | #'user/h41-f1 | user/h41-f1 | #'user/h41-f1 | {:fns #{user/h41-f1}, :armed #{user/h41-f1}} | yes 69117 ms | PASS, 69.1 s | F12 |
| h41-defn-when-timing-2 | #'user/h41-f2 | #'user/h41-f2 | user/h41-f2 | #'user/h41-f2 | {:fns #{user/h41-f2}, :armed #{user/h41-f2}} | yes 618 ms | PASS | — |
| h41-defn-when-timing-3 | #'user/h41-f3 | #'user/h41-f3 | user/h41-f3 | #'user/h41-f3 | {:fns #{user/h41-f3}, :armed #{user/h41-f3}} | yes 83049 ms | PASS, 83.0 s | F12 |
| h41-defn-when-timing-4 | #'user/h41-f4 | #'user/h41-f4 | user/h41-f4 | #'user/h41-f4 | {:fns #{user/h41-f4}, :armed #{user/h41-f4}} | yes 765 ms | PASS | — |
| h42-t1-a-let | #'user/h42-let1 | #'user/h42-let1 | user/h42-let1 | #'user/h42-let1 | {:fns #{user/h42-let1}, :armed #{user/h42-let1}} | yes 787 ms | PASS | — |
| h42-t1-b-when | #'user/h42-when1 | #'user/h42-when1 | user/h42-when1 | #'user/h42-when1 | {:fns #{user/h42-when1}, :armed #{user/h42-when1}} | yes 645 ms | PASS | — |
| h42-t2-a-let | #'user/h42-let2 | #'user/h42-let2 | user/h42-let2 | #'user/h42-let2 | {:fns #{user/h42-let2}, :armed #{user/h42-let2}} | yes 656 ms | PASS | — |
| h42-t2-b-when | #'user/h42-when2 | #'user/h42-when2 | user/h42-when2 | #'user/h42-when2 | {:fns #{user/h42-when2}, :armed #{user/h42-when2}} | yes 626 ms | PASS | — |
| h42-t3-a-let | #'user/h42-let3 | #'user/h42-let3 | user/h42-let3 | #'user/h42-let3 | {:fns #{user/h42-let3}, :armed #{user/h42-let3}} | yes 646 ms | PASS | — |
| h42-t3-b-when | #'user/h42-when3 | #'user/h42-when3 | user/h42-when3 | #'user/h42-when3 | {:fns #{user/h42-when3}, :armed #{user/h42-when3}} | yes 558 ms | PASS | — |
| m-def-do | #'user/m-def-do-v; {:a 1} | #'user/m-def-do-v; {:a 1} | user/m-def-do-v | #'user/m-def-do-v; {:a 1} | nothing | yes 36 ms | PASS (SCI-private) | — |
| m-def-let | #'user/m-def-let-v; {:a 1} | #'user/m-def-let-v; {:a 1} | user/m-def-let-v | #'user/m-def-let-v; {:a 1} | nothing | yes 37 ms | PASS (SCI-private) | — |
| m-def-macro | #'user/m-def-macro-wrap; #'user/m-def-macro-v; {:a 1} | #'user/m-def-macro-wrap; #'user/m-def-macro-v; {:a 1} | user/m-def-macro-wrap user/m-def-macro-v | #'user/m-def-macro-wrap; #'user/m-def-macro-v; {:a 1} | nothing | yes 60 ms | PASS (SCI-private) | — |
| m-def-top | #'user/m-def-top-v; {:a 1} | #'user/m-def-top-v; {:a 1} | user/m-def-top-v | #'user/m-def-top-v; {:a 1} | nothing | yes 39 ms | PASS (SCI-private) | — |
| m-def-when | #'user/m-def-when-v; {:a 1} | #'user/m-def-when-v; {:a 1} | user/m-def-when-v | #'user/m-def-when-v; {:a 1} | nothing | yes 40 ms | PASS (SCI-private) | — |
| m-definterface-do | user.MdefinterfacedoI; :ok | ERR Unable to resolve symbol: definterface; ERR Unable to re… | user/MdefinterfacedoI user/m | ERR Execution error (ExceptionInfo) at (REPL:1:5). Unable to resolve symbol: clojure.core/… | nothing | yes 36 ms | FAIL (SCI parity) | SCI |
| m-definterface-let | user.MdefinterfaceletI; :ok | ERR Unable to resolve symbol: definterface; ERR Unable to re… | user/MdefinterfaceletI user/m | ERR Execution error (ExceptionInfo) at (REPL:1:17). Unable to resolve symbol: clojure.core… | nothing | yes 39 ms | FAIL (SCI parity) | SCI |
| m-definterface-macro | #'user/m-definterface-macro-wrap; user.MdefinterfacemacroI; … | #'user/m-definterface-macro-wrap; ERR Unable to resolve symb… | user/m-definterface-macro-wrap user/MdefinterfacemacroI user… | #'user/m-definterface-macro-wrap; ERR Execution error (ExceptionInfo) at (REPL:1:28). Unab… | nothing | yes 78 ms | FAIL (SCI parity) | SCI |
| m-definterface-top | user.MdefinterfacetopI; :ok | ERR Unable to resolve symbol: definterface; ERR Unable to re… | user/MdefinterfacetopI user/m | ERR Execution error (ExceptionInfo) at (REPL:1:1). Unable to resolve symbol: clojure.core/… | nothing | yes 51 ms | FAIL (SCI parity) | SCI |
| m-definterface-when | user.MdefinterfacewhenI; :ok | ERR Unable to resolve symbol: definterface; ERR Unable to re… | user/MdefinterfacewhenI user/m | ERR Execution error (ExceptionInfo) at (REPL:1:12). Unable to resolve symbol: clojure.core… | nothing | yes 40 ms | FAIL (SCI parity) | SCI |
| m-defmacro-do | #'user/m-defmacro-do-m; 2 | #'user/m-defmacro-do-m; 2 | user/m-defmacro-do-m | #'user/m-defmacro-do-m; 2 | nothing | yes 62 ms | PASS eval, no row | F11 |
| m-defmacro-let | #'user/m-defmacro-let-m; 2 | #'user/m-defmacro-let-m; 2 | user/m-defmacro-let-m | ERR This form changed user/m-defmacro-let-m, which its analysis declares no definition for… | nothing | yes 80 ms | REFUSED | F3 |
| m-defmacro-macro | #'user/m-defmacro-macro-wrap; #'user/m-defmacro-macro-m; 2 | #'user/m-defmacro-macro-wrap; #'user/m-defmacro-macro-m; 2 | user/m-defmacro-macro-wrap user/m-defmacro-macro-m | #'user/m-defmacro-macro-wrap; #'user/m-defmacro-macro-m; 2 | nothing | yes 85 ms | PASS eval, no row | F11 |
| m-defmacro-top | #'user/m-defmacro-top-m; 2 | #'user/m-defmacro-top-m; 2 | user/m-defmacro-top-m | #'user/m-defmacro-top-m; 2 | nothing | yes 60 ms | PASS eval, no row | F11 |
| m-defmacro-when | #'user/m-defmacro-when-m; 2 | #'user/m-defmacro-when-m; 2 | user/m-defmacro-when-m | ERR This form changed user/m-defmacro-when-m, which its analysis declares no definition fo… | nothing | yes 78 ms | REFUSED | F3 |
| m-defmulti-do | #object[clojure.lang.MultiFn 0x4e8036bb "clojure.lang.MultiF… | #object[clojure.lang.MultiFn 0x60666985 "clojure.lang.MultiF… | user/m-defmulti-do-mm | #object[clojure.lang.MultiFn]; 1 | nothing | yes 38 ms | PASS eval, no row | F11 |
| m-defmulti-let | #object[clojure.lang.MultiFn 0x79f14c56 "clojure.lang.MultiF… | #object[clojure.lang.MultiFn 0x1f9ec3e7 "clojure.lang.MultiF… | user/m-defmulti-let-mm | #object[clojure.lang.MultiFn]; 1 | nothing | yes 38 ms | PASS eval, no row | F11 |
| m-defmulti-macro | #'user/m-defmulti-macro-wrap; #object[clojure.lang.MultiFn 0… | #'user/m-defmulti-macro-wrap; #object[clojure.lang.MultiFn 0… | user/m-defmulti-macro-wrap user/m-defmulti-macro-mm | #'user/m-defmulti-macro-wrap; #object[clojure.lang.MultiFn]; 1 | nothing | yes 61 ms | PASS eval, no row | F11 |
| m-defmulti-top | #'user/m-defmulti-top-mm; #object[clojure.lang.MultiFn 0x582… | #'user/m-defmulti-top-mm; #object[clojure.lang.MultiFn 0x72f… | user/m-defmulti-top-mm | #'user/m-defmulti-top-mm; #object[clojure.lang.MultiFn]; 1 | nothing | yes 46 ms | PASS eval, no row | F11 |
| m-defmulti-when | #object[clojure.lang.MultiFn 0x57f076bd "clojure.lang.MultiF… | #object[clojure.lang.MultiFn 0x179af25f "clojure.lang.MultiF… | user/m-defmulti-when-mm | #object[clojure.lang.MultiFn]; 1 | nothing | yes 37 ms | PASS eval, no row | F11 |
| m-defn-do | #'user/m-defn-do-f; 2 | #'user/m-defn-do-f; 2 | user/m-defn-do-f | #'user/m-defn-do-f; 2 | {:fns #{user/m-defn-do-f}, :armed #{user/m-defn-do-f}} | yes 825 ms | PASS | — |
| m-defn-let | #'user/m-defn-let-f; 2 | #'user/m-defn-let-f; 2 | user/m-defn-let-f | #'user/m-defn-let-f; 2 | {:fns #{user/m-defn-let-f}, :armed #{user/m-defn-let-f}} | yes 891 ms | PASS | — |
| m-defn-macro | #'user/m-defn-macro-wrap; #'user/m-defn-macro-f; 2 | #'user/m-defn-macro-wrap; #'user/m-defn-macro-f; 2 | user/m-defn-macro-wrap user/m-defn-macro-f | #'user/m-defn-macro-wrap; #'user/m-defn-macro-f; 2 | {:fns #{user/m-defn-macro-f}, :armed #{user/m-defn-macro-f}} | yes 788 ms | PASS | — |
| m-defn-noschema-do | #'user/m-defn-noschema-do-f; 2 | #'user/m-defn-noschema-do-f; 2 | user/m-defn-noschema-do-f | ERR user/m-defn-noschema-do-f declares no :malli/schema; add a complete contract to its at… | nothing | yes 67 ms | REFUSED (policy) | F2 |
| m-defn-noschema-let | #'user/m-defn-noschema-let-f; 2 | #'user/m-defn-noschema-let-f; 2 | user/m-defn-noschema-let-f | ERR user/m-defn-noschema-let-f declares no :malli/schema; add a complete contract to its a… | nothing | yes 68 ms | REFUSED (policy) | F2 |
| m-defn-noschema-macro | #'user/m-defn-noschema-macro-wrap; #'user/m-defn-noschema-ma… | #'user/m-defn-noschema-macro-wrap; #'user/m-defn-noschema-ma… | user/m-defn-noschema-macro-wrap user/m-defn-noschema-macro-f | #'user/m-defn-noschema-macro-wrap; ERR user/m-defn-noschema-macro-f declares no :malli/sch… | nothing | yes 84 ms | REFUSED (policy) | F2 |
| m-defn-noschema-top | #'user/m-defn-noschema-top-f; 2 | #'user/m-defn-noschema-top-f; 2 | user/m-defn-noschema-top-f | ERR user/m-defn-noschema-top-f declares no :malli/schema; add a complete contract to its a… | nothing | yes 341 ms | REFUSED (policy) | F2 |
| m-defn-noschema-when | #'user/m-defn-noschema-when-f; 2 | #'user/m-defn-noschema-when-f; 2 | user/m-defn-noschema-when-f | ERR user/m-defn-noschema-when-f declares no :malli/schema; add a complete contract to its … | nothing | yes 59 ms | REFUSED (policy) | F2 |
| m-defn-top | #'user/m-defn-top-f; 2 | #'user/m-defn-top-f; 2 | user/m-defn-top-f | #'user/m-defn-top-f; 2 | {:fns #{user/m-defn-top-f}, :armed #{user/m-defn-top-f}} | yes 1111 ms | PASS | — |
| m-defn-when | #'user/m-defn-when-f; 2 | #'user/m-defn-when-f; 2 | user/m-defn-when-f | #'user/m-defn-when-f; 2 | {:fns #{user/m-defn-when-f}, :armed #{user/m-defn-when-f}} | yes 58734 ms | PASS | F12 (m-defn-when 58.7 s) |
| m-defprotocol-do | MdefprotocoldoP; :ok | MdefprotocoldoP; :ok | user/MdefprotocoldoP user/m-defprotocol-do-pm | MdefprotocoldoP; :ok | nothing | yes 40 ms | PASS eval, no row | F11 |
| m-defprotocol-let | MdefprotocolletP; :ok | MdefprotocolletP; :ok | user/MdefprotocolletP user/m-defprotocol-let-pm | MdefprotocolletP; :ok | nothing | yes 37 ms | PASS eval, no row | F11 |
| m-defprotocol-macro | #'user/m-defprotocol-macro-wrap; MdefprotocolmacroP; :ok | #'user/m-defprotocol-macro-wrap; MdefprotocolmacroP; :ok | user/m-defprotocol-macro-wrap user/MdefprotocolmacroP user/m… | #'user/m-defprotocol-macro-wrap; MdefprotocolmacroP; :ok | nothing | yes 65 ms | PASS eval, no row | F11 |
| m-defprotocol-top | MdefprotocoltopP; :ok | MdefprotocoltopP; :ok | user/MdefprotocoltopP user/m-defprotocol-top-pm | MdefprotocoltopP; :ok | nothing | yes 36 ms | PASS eval, no row | F11 |
| m-defprotocol-when | MdefprotocolwhenP; :ok | MdefprotocolwhenP; :ok | user/MdefprotocolwhenP user/m-defprotocol-when-pm | MdefprotocolwhenP; :ok | nothing | yes 37 ms | PASS eval, no row | F11 |
| m-defrecord-do | user.MdefrecorddoR; 1 | user.MdefrecorddoR; 1 | user/->MdefrecorddoR user/MdefrecorddoR user/map->Mdefrecord… | ERR Execution error (ExceptionInfo) at (REPL:1:5). Unable to resolve symbol: clojure.core/… | nothing | yes 40 ms | FAIL | F1 |
| m-defrecord-let | user.MdefrecordletR; 1 | user.MdefrecordletR; 1 | user/->MdefrecordletR user/MdefrecordletR user/map->Mdefreco… | ERR Execution error (ExceptionInfo) at (REPL:1:17). Unable to resolve symbol: clojure.core… | nothing | yes 55 ms | FAIL | F1 |
| m-defrecord-macro | #'user/m-defrecord-macro-wrap; user.MdefrecordmacroR; 1 | #'user/m-defrecord-macro-wrap; user.MdefrecordmacroR; 1 | user/m-defrecord-macro-wrap user/->MdefrecordmacroR user/Mde… | #'user/m-defrecord-macro-wrap; ERR Execution error (ExceptionInfo) at (REPL:1:25). Unable … | nothing | yes 61 ms | FAIL | F1 |
| m-defrecord-top | user.MdefrecordtopR; 1 | user.MdefrecordtopR; 1 | user/->MdefrecordtopR user/MdefrecordtopR user/map->Mdefreco… | ERR Execution error (ExceptionInfo) at (REPL:1:1). Unable to resolve symbol: clojure.core/… | nothing | yes 34 ms | FAIL | F1 |
| m-defrecord-when | user.MdefrecordwhenR; 1 | user.MdefrecordwhenR; 1 | user/->MdefrecordwhenR user/MdefrecordwhenR user/map->Mdefre… | ERR Execution error (ExceptionInfo) at (REPL:1:12). Unable to resolve symbol: clojure.core… | nothing | yes 45 ms | FAIL | F1 |
| m-deftype-do | user.MdeftypedoT; 1 | user.MdeftypedoT; 1 | user/->MdeftypedoT user/MdeftypedoT | ERR Execution error (ExceptionInfo) at (REPL:1:5). Unable to resolve symbol: clojure.core/… | nothing | yes 47 ms | FAIL | F1 |
| m-deftype-let | user.MdeftypeletT; 1 | user.MdeftypeletT; 1 | user/->MdeftypeletT user/MdeftypeletT | ERR Execution error (ExceptionInfo) at (REPL:1:17). Unable to resolve symbol: clojure.core… | nothing | yes 50 ms | FAIL | F1 |
| m-deftype-macro | #'user/m-deftype-macro-wrap; user.MdeftypemacroT; 1 | #'user/m-deftype-macro-wrap; user.MdeftypemacroT; 1 | user/m-deftype-macro-wrap user/->MdeftypemacroT user/Mdeftyp… | #'user/m-deftype-macro-wrap; ERR Execution error (ExceptionInfo) at (REPL:1:23). Unable to… | nothing | yes 60 ms | FAIL | F1 |
| m-deftype-top | user.MdeftypetopT; 1 | user.MdeftypetopT; 1 | user/->MdeftypetopT user/MdeftypetopT | ERR Execution error (ExceptionInfo) at (REPL:1:1). Unable to resolve symbol: clojure.core/… | nothing | yes 40 ms | FAIL | F1 |
| m-deftype-when | user.MdeftypewhenT; 1 | user.MdeftypewhenT; 1 | user/->MdeftypewhenT user/MdeftypewhenT | ERR Execution error (ExceptionInfo) at (REPL:1:12). Unable to resolve symbol: clojure.core… | nothing | yes 39 ms | FAIL | F1 |

h38 at a2fd4676c (results/h38-redefine-entity-schema-a2fd4676c.edn): both forms gave ERR "Not settled: this run's
settlement was refused: Complete component validation refused: unowned-entity … entity 54842", nothing was stored,
and the run took 4.9 s. Adoption's transaction was computed read-only with the running code:
`(#'seon.turn/schema-attribute-change-tx db current candidate #{:corpus.h38/note :seon.cluster.eval/receipt})`,
116 ms. It retracts 459 of 459 `:seon.cluster.eval/read-evidence` values and 1 of 1 `:seon.cluster.eval/faults`
values, and installs `:corpus.h38/note`.

## Failure classes, ranked by item count

The counts exclude the policy refusals (F2, five items) and the SCI parity gaps (`definterface` ×5, h34's return
value). The stage names where Seon diverges from plain SCI.

| class | stage | items | count |
|---|---|---|---|
| F11 no program row, silently | recording | defmacro top/do/macro, defmulti ×5, defprotocol ×5, h24 defonce, h34 | 15 |
| F1 host `import` macro shadows SCI's special form | evaluation (context construction) | defrecord ×5, deftype ×5, h30 | 11 |
| F12 cost | settlement / admission | h36 (14.9 s); when-nested defn 58.7, 69.1, 83.0 s | 4 |
| F6 nested `in-ns` loses the run | read/segmentation → settlement → reply | h03 (+ h04 misdelivery) | 2 |
| F3 defmacro nested in let/when unindexed | recording/admission | m-defmacro-let, m-defmacro-when | 2 |
| F4 one declaration per form | recording/admission | h39, h40 | 2 |
| F5 nested `register!` refused | recording/admission | h15, h37 | 2 |
| F8 generative gate refuses valid code, no counterexample shown | admission + reply | h05, h32 | 2 |
| F7 whole result realized after evaluation | recording (problem classification) | h35 (killed default with `(range)`) | 1 |
| F10 in-run program changes invisible to `my.program` | recording + reply | h13 | 1 |
| F9 adoption dropped `[:vector]` many values | settlement | h38 at a2fd4676c | 1 (fixed bd3d35b19) |

Ranked by severity: F7, which kills the JVM; then F6, which loses the run silently; then F1, which breaks three core
forms in `user`; then F11, which loses definitions without saying so.

### F1 — `defrecord`, `deftype` and `import` fail in a namespace with program rows

What Seon returns: `Unable to resolve symbol: clojure.core/import*`.

In `user` (on corpus-2), `(resolve 'import)` answers `#'user/import` with metadata `{:macro true :file
"clojure/core.clj" :line 3455}`: the JVM macro, interned into the SCI namespace. In `corpus.h01` and `corpus.h02`,
`(ns-resolve … 'import)` is nil, so SCI's special form applies and plain SCI succeeds.

The owner is `install-caller-host-bindings!` (src/seon/sci/eval.clj:2370-2400). Its `own-bindings` copies every
`clojure.core` refer whose name the SCI core map lacks (eval.clj:2383-2391). `import` is absent from that map
because SCI implements it as an analyzer special form (reference-code/sci/src/sci/impl/analyzer.cljc:1843). The copy
therefore shadows the form that SCI's `defrecord` and `deftype` expansions emit (records.cljc:474,
deftype.cljc:663).

Side effects:

- At top level, the constructor Var still works on the next form, although the form reported an error and recorded
  `:seon.eval/unadmitted`.
- In `let` or `when`, the constructor is unbound (`Attempting to call unbound fn`), or its field accessor throws an
  NPE.
- `definterface` fails in plain SCI too. Seon's message names `clojure.core/gen-interface` because the same copy
  brought in the JVM macro.

The smallest fix is to exclude SCI's special-form names (`import` and the rest of SCI's special-form set) from
`own-bindings`. Related task: `interpreted-rows-lack-own-namespace-class-imports` (seon.sci.eval). It has a
different cause. This one has no task yet.

### F11 — definitions that evaluate but never become rows

The following define in SCI and store no row: `defmacro`, `defmulti`/`defmethod`, `defprotocol` (and its method
Vars), `defonce`, and `def` of data. The check on corpus-2 was `seon.db/pull [:seon.fn/sym …]`, which gave nil for
`user/m-defmacro-top-m`, `user/m-defmulti-top-mm`, `user/m-defprotocol-top-pm` and `user/h24-a`.

Only top-level `def` gets the note "lives only in your SCI context and is lost when the JVM restarts". Nested `def`,
macros, multimethods and protocols are silent. The admission code exempts macros from the contract requirement
(eval.clj:1005-1011: `(not (:seon.fn/macro? row))`), which implies macro rows were intended, yet none is written.
The agent is not told these definitions will not survive a restart or a merge.

### F12 — cost

- **h36:** 22 top-level `register!` forms took 14.9 s. Each form evaluated in 18–34 ms, so almost all of the time
  is outside evaluation. This matches db8937e8d's diagnosis: per-form projection rebuilds, and settlement.
- **Contracted `defn`:** runs take 0.56–1.11 s each (median about 0.7 s) against 34–80 ms for `def`, `defmacro` and
  `defprotocol`. m-defn-top took 1111 ms, which is over the one-second law.
- **Intermittent when-nested `defn`:** three of ten `(when true (defn … contracted …))` runs took 58.7 s
  (m-defn-when), 69.1 s (h41-1) and 83.0 s (h41-3). The other seven, and all five `let` variants (h42), took
  0.56–0.79 s. The slow runs coincided with root's test batches in the same JVM; the log shows `Testing …`. Two
  thread samples taken during fast runs caught nothing. This is unattributed: the next probe is a thread dump taken
  during a slow run.

### F6 — nested `in-ns` loses the run (task a-nested-in-ns-loses-the-whole-run, seon.turn)

For h03, plain Clojure answers `1` and then an error (`+` unresolved in the new namespace). SCI answers `1, 3`. Seon
answers nothing: `evaluate-source!` returned "The awaited channel closed before completion".

The datahike writer logged `Nothing found for entity id [:seon.ns/name corpus.h03]`. The reader tracks the reading
namespace only for top-level `ns` and `in-ns` (src/seon/sci/reader.cljc:396-415). A nested one only drops
attribution (reader.cljc:385-393). So no namespace row is written, while the next form's receipt carries the lookup
ref `:seon.cluster.eval/ns [:seon.ns/name corpus.h03]` (src/seon/turn.clj:886).

The fault then surfaced in the next run's last form (h04, ordinal 4, `:errors` plus faults `#:db{:id 59667}`), not in
h03's reply. The reply loss is task 6830ae125 (`evaluate-source!`'s answer).

### F3 — `defmacro` inside `let` or `when` is refused as unindexed

The refusal is "This form changed user/m-defmacro-let-m, which its analysis declares no definition for at the place
it ran…" (eval.clj:864-878).

Admission matches each new Var to its analyzed row by the Var's `:line`/`:column` (program-change,
eval.clj:627-673). SCI's `defmacro*` expands to `(do (clojure.core/defn …) (var name))` without carrying `&form`'s
location (reference-code/sci/src/sci/impl/fns.cljc:363-399). A nested `defmacro`'s Var therefore gets the enclosing
top-level form's position. Probe in bb: `(let [u 0] (defmacro b [x] x))` gives `#'b` column 20, which is the
`(let`, while a nested `defn` gets its own column.

At top level the two positions coincide, and a top-level `do` is unrolled (eval.clj top-level-do?), so only
`let`/`when` placements fail. There are two possible fixes: SCI's fork attaches `(meta &form)` to the generated
`defn`, or admission stops keying provenance on position.

### F4 — one program declaration per form

- **h39:** two contracted `defn`s in one `let` are refused with "One form may make one program declaration"
  (eval.clj:880-897, 999-1001).
- **h40:** a macro that expands to two `defn`s is refused as unindexed. kondo cannot see names built by
  syntax-quote: its `kondo sees` column lists only `h40-defpair`.

Clojure and SCI define both functions in each case. Related task: e32aa6d5b3eb
(a-do-with-two-definitions-refuses-instead-of-admitting-both). The `do` case now passes; `let`, `when` and macro
bodies still fail.

### F5 — nested `register!` (task b9163458502a, seon.schema)

`(do (register! …) :registered)` and the 22-form `do` are refused with "Schema declarations require an isolated
registration delta" (src/seon/schema.clj:1317). The same forms at top level succeed (h14, h16, h36).

### F8 — the generative gate refuses valid code, and the reply hides why

- **h05:** `(declare g) (defn f … (g x)) (defn g …)` is refused at f with the gate's
  `:seon.test.accretion/install?` refusal. The auto-check calls f while g is still unbound. Clojure and SCI both
  answer 2.
- **h32:** a multi-arity `defn-` whose contract admits every int while its `:pre` requires `(pos? x)`. The gate's
  refusal is arguably a real mismatch.

In both cases the reply is only `{:member :seon.test.accretion/install? :expected true :message "Fix the contract or
the function and re-evaluate the defn."}`. `install-refusal` builds orientation, failure groups and the auto-check
failure (src/seon/test/accretion.clj:384-405), but the shown text omits them. The agent never sees the failing input.

### F7 — the whole result is walked after evaluation, outside the armed boundary

This is the site that killed default. A thread probe proved it: a counting lazy seq of 200,000 elements, run through
MCP sci on corpus-2, reported `@h35-seen` = 200000 on the next run. The realizing stack is:

- `seon.turn/unbound-value?` (src/seon/turn.clj:94-104: `(tree-seq coll? seq value)` over the whole value),
- called from `seon.problems/form-problem` (src/seon/problems.clj:219),
- called from `seon.turn/resume-turn` → `phase`.

Within the run, the third form saw 32 (the floor printer's cut). The full walk happens afterwards, at problem
classification. That is outside the SCI interrupt and the time limit that eval.clj's docstring relies on:
"an infinite lazy sequence dies at the time limit here rather than in the receipt writer" (eval.clj:4885).

`(range)` therefore walks until the heap is gone. The memory-pressure guard only refuses new evaluations, and the
log says "In flight when it began: no open run". Every finite result also pays an O(size) walk, even when the form
is not red.

The fix is to decide `unbound?` inside admission (`seon.sci.admit`'s bounded walk already visits every node it keeps)
or bound the tree-seq. There is no task yet.

### F9 — adoption dropped every `[:vector]` many value (fixed)

At a2fd4676c, `seon.cluster/invalid-value-retraction` validated each entity's value set against `[:vector …]` and
refused all of them. bd3d35b19 validates each member against the element schema instead. The re-run on 95f44cbc3
settled: read-evidence went from 459 to 461, which is this run's own evidence with zero retracted, and
`:corpus.h38/note` was stored. The run took 1.67 s, which is over one second.

### F10 — `my.program/ns-unmap!` cannot see an earlier form of the same run

In h13, the `defn` in form 0 is a row only after settlement, so form 1's `(my.program/ns-unmap! 'user/h13-f)`
answered "No program declaration names user/h13-f." (src/my/program.clj:75). That answer was shown as a bare string
value, not an error map. The Var survived (`(resolve 'h13-f)` gave `#'user/h13-f`), and the row was stored at
settlement anyway.

### Not Seon divergences

- **`definterface` ×5:** plain SCI has no `definterface`.
- **h34:** `extend-protocol` returns nil in Clojure and a MultiFn in SCI, and Seon matches SCI.
- **h26:** `(defn corpus.h26/f …)` fails in Clojure ("did not conform to spec") and in SCI ("Var name should be
  simple symbol"), and Seon agrees. AGENTS.md says agents change the program with "`defn` inside the target
  namespace or by its fully qualified name". The fully-qualified half is not installed, so that claim needs an owner
  ruling.
- **h07:** kondo flags reader conditionals only because the mirror file is `.clj`.
- **h25:** Seon supports `clojure.test` where plain SCI does not.

## Other observations

- **Reply inconsistency:** a failed evaluation's reply shows `ERR` text, while an in-run refusal (h13) shows a string
  value.
- **h20:** Seon reports the reader error at `[1,1]`, relative to the span. SCI reports `[2,1]` in the source.
- **host! boot race:** at 17:36:19Z, 11 s after root's restart, `seon.cluster.host/host!` threw
  `NullPointerException: Cannot invoke "java.util.concurrent.Future.get()" because "fut" is null` from `hosted`
  (src/seon/cluster/host.clj:117-121). The next call succeeded.

## What the runner did between 17:24Z and 17:30Z (root's question)

- **17:24:13Z:** root restarted default onto e9877da0f. The corpus-1 chunk in flight lost its connection; nothing of
  mine caused this. corpus-1 was retired and corpus-2 created.
- **About 17:25–17:26:22Z:** the matrix (45 items) and h01–h19 ran on corpus-2. Per item, the runner did three
  things:
  - plain SCI inside default. This was read-only; the one heavy value was printed under `*print-length* 8`.
  - `seon.fn.analyzer/analyze` with a non-checkout path, uncached.
  - one `evaluate-source!` run.
- **About 17:26–17:28Z:** h20–h34, then `h35-lazy-infinite`, whose source was `(range)`. That was my mistake. I
  predicted the floor printer would bound it, and it did bound the shown text. `unbound-value?` (F7) then walked the
  infinite seq until the OutOfMemoryError at about 17:28–17:29Z.

  To rule out my own in-JVM SCI step, I ran the same corpus's SCI and Clojure steps in a 512 MB scratch JVM: `(range)`
  printed bounded there. The in-JVM trace probe above then proved the site.
- **No schema-redefinition item ran before the crash.** h38 first ran at 17:34Z on corpus-3 (refused, no JVM
  effect) and again after root's 17:36Z restart.
- **After root's rulings:**
  - every item is bounded: at most 1e6 elements, and a string of at most 1e6 characters;
  - `plain.clj` runs only in a `-Xmx512m` scratch JVM;
  - the h35 item is now `(map … (range 1000000))`.

## Reproduce

```clojure
;; MCP eval_clj jvm, cluster default (never default's own branch):
(load-file "/Users/sean/src/seon/tmp/valid-clojure-corpus/runner.clj")
(runner/run-all! "<branch>")                 ; after MCP branch create
(runner/schema-redefinition! "<other-branch>")
```

Plain references, from the checkout:

```sh
clojure -Sdeps '{:paths ["tmp/valid-clojure-corpus"] :deps {org.clojure/clojure {:mvn/version "1.12.0"} org.babashka/sci {:local/root "reference-code/sci"}}}' \
  -J-Xmx512m -M -e "(require 'plain) (plain/run-all!)"
bb tmp/valid-clojure-corpus/table.bb   # from tmp/valid-clojure-corpus
```

Regressions each class needs, one per class, asserting the wanted behavior:

- F1: `(defrecord R [a]) (:a (->R 1))` in `user` answers 1.
- F3: `(let [_ 0] (defmacro m [x] x)) (m 1)` answers 1.
- F4: `(let [k 1] (defn a …) (defn b …))` admits both.
- F6: `(do (in-ns 'x) 1) (+ 1 2)` returns both receipts.
- F7: the result `(map f (range 1000000))` realizes at most the printed prefix. Assert with a counting `f`.
- F8: a declared-then-defined callee admits both.
- F10: the same run's `ns-unmap!` retracts the row.
- F11: every definition kind either makes a row or says it will not persist.
