---
title: "Card RP-3: Capture each Var write as it happens"
type: reference
status: active
created: 2026-10-08
---

# RP-3 Capture each Var write as it happens

Ledger row RP-3 (alias S5-04b, group C). Design: `redo/pass-and-recording.md` §2 CAPTURE ("each root
:bind/:unmap/:remove-ns write → :seon.batch/write {sym var kind fn? meta resolver-row arm-error}"). Lane: Sonnet.
Cited at HEAD `22910a03a`.

## What changes, in one paragraph

SCI's producer observer (`sci.lang/*write-observer*`, bound in `seon.sci.observe/observe`,
`src/seon/sci/observe.clj:19-61`) hands each root write to `seon.sci.arm/write-event` (`src/seon/sci/arm.clj:71-86`),
which keeps the Var, its symbol, origin, kind and arm result, but not the Var's metadata or the namespace's resolver
state **at that moment**. Later stages re-read both from the live Var and the live namespace after the eval request,
so a redefinition or an alias change inside the same eval request is seen as its final state. Each `:bind` write now
also carries `:seon.batch/meta` (the Var's metadata when written, without the namespace object) and
`:seon.batch/resolver` (the namespace row its definition resolves through when written). Nothing reads them yet: RP-5
analyzes each definition under its captured row, RP-8 compares rows, RP-9 reads nested definitions from them.

## Files

- `src/seon/sci/eval.clj` (one public function, made from two private ones)
- `src/seon/sci/arm.clj`
- `src/seon/sci/observe.clj`
- `src/seon/turn/batch.clj` (`namespace-entity` delegates; one line)
- `resources/seon/schemas/seon.batch.edn`
- `test/seon/repl_differential_p8_test.clj` (new file; the RP rows' proofs live here)
- `test/seon/turn_test.clj` (one capture test)

## Schema (`seon.batch.edn`, the `:write` multi at `:90`)

In the `:bind` branch add:

```edn
[:seon.batch/meta {:optional true} :seon.batch/meta]
[:seon.batch/resolver {:optional true} :seon.batch/resolver]
```

and top-level:

```edn
:meta [:map {:description "The Var's metadata when it was written (name, arglists, doc, line, column, end positions, :malli/schema …), without :ns, the live namespace object."}]
:resolver [:map {:description "The namespace row a definition resolves through when written: SCI's resolver bindings of the Var's namespace at that write, in program-row shape (seon.sci.eval/namespace-row)."}
           [:seon.ns/name :symbol]
           [:seon.ns/requires {:optional true} [:set :symbol]]
           [:seon.ns/aliases {:optional true} [:set :map]]
           [:seon.ns/imports {:optional true} [:set :map]]
           [:seon.ns/refers {:optional true} [:set :map]]]
```

Both are runtime values in the batch, never stored (the batch is not an entity).

## Functions

- ADD `seon.sci.eval/namespace-row` (public)
  `{:malli/schema [:=> [:cat :seon.sci.eval/ctx :symbol] :seon.batch/resolver]}` `[ctx namespace-name]`
  = `(merge {:seon.ns/name namespace-name} (binding-rows (reader-context ctx namespace-name)))`, the body of today's
  `seon.turn.batch/namespace-entity` (`src/seon/turn/batch.clj:58-64`), which reaches the two private functions by
  Var (`#'eval/binding-rows`, `#'eval/reader-context`, `src/seon/sci/eval.clj:513`, `:600`). The seam is SCI's
  `sci.core/namespace-bindings` (`reference-code/sci/src/sci/core.cljc:701`).
- CHANGE `seon.turn.batch/namespace-entity` (`:58`): body becomes `(eval/namespace-row ctx namespace-name)`; contract
  unchanged. (RP-5 deletes it with `analyze-form`; its one caller stays until then.)
- CHANGE `seon.sci.arm/write-event` (`:71`): arity `[w resolver]`, contract
  `[:=> [:cat :map [:maybe :seon.batch/resolver]] :seon.batch/write]`. In the `:bind` branch add
  `(assoc :seon.batch/meta (dissoc (meta the-var) :ns))` when the Var has metadata and
  `(assoc :seon.batch/resolver resolver)` when `resolver` is non-nil. Other branches ignore `resolver`.
- CHANGE `seon.sci.observe/observe` (`:19`): in the `*write-observer*` fn (`:46-55`), for a `:bind` whose Var has a
  namespace, compute `(eval/namespace-row ctx (symbol (str (:ns (meta (:var w))))))` **before** `arm!` (arming
  re-interprets callers and must not change what the write resolved through) and pass it as `write-event`'s second
  argument; other ops pass `nil`. Require `[seon.sci.eval :as eval]` in `seon.sci.observe` only if `seon.sci.arm`'s
  existing require of `seon.sci.eval` proves no cycle (`arm.clj` already calls `#'eval/install-function-contract!`).

## Data (one `:bind` write after the change)

```edn
{:seon.batch/op :bind :seon.batch/kind :root :seon.batch/var #'rp3.a/f :seon.batch/sym rp3.a/f
 :seon.batch/fn? true :seon.batch/origin {:line 1 :column 1}
 :seon.batch/meta {:name f :arglists ([]) :doc "one" :file nil :line 1 :column 13 :sci/generation 7}
 :seon.batch/resolver {:seon.ns/name rp3.a :seon.ns/requires #{clojure.string}
                       :seon.ns/aliases #{{:seon.ns.alias/local s :seon.ns.alias/target-ns clojure.string}}
                       :seon.ns/imports #{} :seon.ns/refers #{}}}
```

Probed on default (an SCI eval request, `(sort (keys (meta #'g)))` after `(defn g "first" [] …)`): a defined Var's
metadata keys are `:arglists :column :doc :file :line :name :ns :sci/generation`; there are no end positions.

## Proof

1. `seon.repl-differential-p8-test/redefinition-keeps-each-calls-value-and-stores-the-last` (new file
   `test/seon/repl_differential_p8_test.clj`, the `seon.repl-differential-p3-test` idiom: a private `reply` over
   `seon.mcp/call` with `support/with-running-agent`):

   ```clojure
   (let [r (reply "(ns rp3.a) (defn f \"one\" [] 1) (f) (defn f \"two\" [] 2) (f)" agent-id running)]
     ;; the eval request's reply holds one envelope per form; read the values of the two calls
     (is (= [1 2] (keep-calls r))))
   (is (= "two" (:seon.repl/value (reply "(:doc (meta #'rp3.a/f))" agent-id running))))
   (is (= "two" (:seon.repl/value (reply "(:seon.fn/doc (seon.db/pull (seon.db/db) [:seon.fn/doc] [:seon.fn/sym 'rp3.a/f]))" agent-id running))))
   ```

   `reply` reads the **last** envelope; for the values of the 2nd and 4th forms, read every content item of the
   result (`(get result "content")`), one per form, the way `seon.repl-differential-test` does for multi-form rows.
   The program row stores the docstring as `:seon.fn/doc` (probed: a pulled row has `:seon.fn/doc "first"`). This row is green on HEAD for the
   values and guards them; the capture itself is proven by 2.
2. `seon.turn-test/a-root-write-captures-its-meta-and-resolver-when-written` (new, `test/seon/turn_test.clj`, beside
   `a-definition-changed-since-the-run-read-it-is-refused-at-the-writer` `:2240`, whose fixture it copies:
   `support/with-database`, `world/agent-world`, `support/code-request`, `observe/observe`,
   `seon.batch/evaluate-batch` with `:seon.batch/settle` dissoc'ed):
   text `"(ns rp3.b (:require [clojure.string :as s])) (defn g \"first\" [] (s/join [1])) (ns-unalias *ns* 's) (require '[clojure.set :as s]) (defn g \"second\" [] (s/union #{1}))"`.
   Assert on `(filter #(= 'rp3.b/g (:seon.batch/sym %)) (:seon.batch/writes batch))`: two writes; their
   `[:seon.batch/meta :doc]` are `"first"` then `"second"`; their resolvers' alias `s` targets are `clojure.string`
   then `clojure.set` (read `:seon.ns.alias/target-ns` of the alias whose local is `s`). The original probe predates FK-SCI: SCI then retargeted the alias in place (probed: `(require '[clojure.set :as s])` after `[clojure.string :as s]` answers
   `(ns-aliases *ns*)` = `{s #namespace[clojure.set]}`). The proof explicitly unaliases first, so it also works after FK-SCI restores Clojure's refusal of a silent retarget.

## Reaching tests (run in `--test`)

`seon.repl-differential-p8-test`, `seon.turn-test`, `seon.turn.batch-test`, `seon.fn-test`
(`defining-forms-share-one-form-local-kondo-batch`), `seon.sci.eval-test`, `seon.repl-differential-test`.

## Cost

Expected from the work: per root `:bind`, one `sci.core/namespace-bindings` read of the namespace's (small) resolver
maps plus a metadata `dissoc`: microseconds and a few KB, following the namespace's aliases and refers, never the
program. The redesign's measured capture is 2.3 µs / 3.1 KB per root bind (§6). Report the O probe's `defn` row
(`seon.pass-probe/run-mcp` SCI, one-line `defn` and five-`defn` eval requests) before and after; the expectation is
no measurable change.

## Deleted

Nothing (the two private-Var reaches from `seon.turn.batch` become one public call).

## Commit

`bin/lane commit <lane> -m "RP-3: each root write carries its metadata and resolver row when written" --test seon.repl-differential-p8-test --test seon.turn-test --test seon.turn.batch-test --test seon.fn-test --test seon.sci.eval-test src/seon/sci/eval.clj src/seon/sci/arm.clj src/seon/sci/observe.clj src/seon/turn/batch.clj resources/seon/schemas/seon.batch.edn test/seon/repl_differential_p8_test.clj test/seon/turn_test.clj`

## Dependencies

RP-2 (ledger order: RP-5 consumes both). `src/seon/sci/eval.clj` (group C) is also PB-02b's and PB-03's file;
`src/seon/turn/batch.clj` and `test/seon/turn_test.clj` are group B: claim them with `bin/lane claim` and wait for any
holder's release.

## Reset needs

None: the schema keys are non-entity batch members.
