---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, sci, acquisition, dogfood]
---

# A namespace's functions install in name order, so a caller whose name sorts before its callee is dropped

Found by the standing dogfood lane dogfood-repl-opus-2 (cycle 10), working only through MCP SCI on its own
branches. Default ran archives 6ccf12a5a and then 5bed888d0.

## Observed

An ordinary four-function namespace, written as a Clojure developer writes it (callee first, caller after,
names chosen for meaning), on branch `dogfood-repl-opus-2-c10c` (5bed888d0):

```clojure
(ns dogfood.wc)
(defn blank-word? "…" {:malli/schema [:=> [:cat :string] :boolean]} [w] (clojure.string/blank? w))
(defn tokens "…" {:malli/schema [:=> [:cat :string] [:vector :string]]} [text] (vec (remove blank-word? (clojure.string/split (clojure.string/lower-case text) #"\W+"))))
(defn word-frequencies "…" {:malli/schema [:=> [:cat :string] [:map-of :string :int]]} [text] (frequencies (tokens text)))
(defn top-words "…" {:malli/schema [:=> [:cat :string :int] [:vector :string]]} [text n] (->> (word-frequencies text) (sort-by (fn [[w c]] [(- c) w])) (take n) (mapv first)))
(top-words "The cat, the hat." 2)
```

- The run answers `["the" "cat"]`, but the branch-error notice already says
  `seon.sci.eval/install-row!`: "Cannot interpret dogfood.wc/top-words: Unable to resolve symbol: word-frequencies".
- The next run that reinstalls rows (here, a run that only added two `deftest`s) loses the Var:
  `(sort (keys (ns-publics 'dogfood.wc)))` no longer lists `top-words`, and the `deftest` calling it refuses
  "Unable to resolve symbol: top-words". The row is still there: `(my.program/history {:seon.program/subject 'dogfood.wc/top-words})`
  answers its source and definition digest.
- Every test world rebuilds from rows, so `(my.test/check {:seon.test/changed [… 'dogfood.wc/top-words]})` reports
  `top-words-orders-by-count` as `:seon.test/error-count 1`, "The admitted identity has no executable test Var.",
  even after re-evaluating the `defn` and the `deftest` in the agent's own run.
- **Control.** The identical body under the name `zz-top-words` (sorts after `word-frequencies`) installs without an
  error and its test passes 2/2. A copy of the whole namespace with names `a-blank?`, `b-tokens`, `c-frequencies`,
  `d-top-words` (branch `-c10d`) installs, survives every run, and its two tests pass 4/4.
- Earlier on 6ccf12a5a the same class showed as "Cannot interpret dogfood.words/frequencies-of: Unable to resolve symbol:
  tokens" (`frequencies-of` < `tokens`), recorded 56 times in 20 minutes on one branch, once per acquisition.
  There, redefining the callee `tokens` also dropped the transitive caller `top-words` in the same run.

## Which model is wrong

Acquisition installs one namespace's function rows in **symbol-name order** and analyzes each body as it installs it:
`(sort-by first (get function-rows-by-ns namespace-name))` at `src/seon/sci/eval.clj:2832` (HEAD 5bed888d0), then
`install-row!` interprets each source. SCI resolves free symbols at analysis time, so a caller analyzed before its
same-namespace callee cannot resolve it. Clojure's own model is order of definition (a file is read top to bottom), and the
REPL's answer to forward references is `declare`. Name order is neither.

Two further faults turn a wrong order into silent loss:

1. The refused row becomes a missing Var instead of a refusal the agent sees. The evaluation that defined it
   succeeded, the notice names it once, and later runs simply lack it (no error at the call site names the cause).
2. `my.test/check` answers `:seon.test/passed? true` with `top-words` named in `:seon.test/changed` and no test
   executing it, when the test that reaches it could not be installed. Missing coverage must refuse by name.

## Expected

Rows install in an order that cannot depend on names: interning every declared Var of a namespace first (SCI's
`declare`) and then analyzing the bodies, or installing in definition-transaction order. A row that still cannot be
installed refuses by name at the next use and in the next run's reply, and a check whose named change has no executable
reaching test refuses by name.

## Regression

On a fresh branch: define `zz` (callee) and then `aa` calling `zz` in one run; add a `deftest` calling `aa` in a
second run; in a third run `(ns-publics …)` lists `aa` and `my.test/check` naming `aa` passes the test. The same for a
caller of a callee that is redefined afterwards.
