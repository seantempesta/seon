---
title: "outside-2: the second :outside chain task worked through MCP eval_clj (S3b-2 dogfood)"
type: research
status: stopped (lane outside-2, 2026-10-14, agent attempt/768cc101f874 on :chain-05dcb5c5b578; proofs green, done never proposed, merge not reached)
---

# outside-2: the second `:outside` chain task, worked through MCP `eval_clj`

Lane outside-2, 2026-10-14, default restarted 21:55 onto 5c78940d9 (HEAD moved to c94977458 during the run, no
restart), `:seon.config.ai/no-provider` true (`(:seon.config.ai/no-provider (seon.config/effective db "default"))`
answered `true`). One Implement task, worked as its agent id in SCI mode. Agent work 14:58:08 to 15:01:49 UTC
(under 4 minutes); root-side diagnosis after.

## Broken first

1. **The task's proofs are green and the done path never fires: four reaching tests stay `:pending` forever, so the
   step reads `:working` and `seon.turn/task-end` never answers `:done`.** After the change (call 4) both proofs ran
   green on the chain branch (run 13d5e9fcf7c9) and the block showed `:counts {:green 2}`. The step still read
   `:seon.task/status :working`, with a merge-preview refusal (`seon.cluster.source/accept-merge!`,
   `:seon.source/red-test-error`) listing `seon.edit-test/form-edit-preserves-every-unselected-byte`,
   `form-edit-records-its-span-in-utf8-bytes`, `form-failures-never-fall-back-to-text` and
   `selectors-refuse-ambiguity-and-use-parsed-dispatch` at `:seon.source/verdict :unknown`, and
   `:seon.context/red-tests` listing the same four as `:seon.context/proof-status :pending`. Running the defn
   reply's offered check (call 7, `(my.test/check {:seon.test/changed ['seon.edit/single-form]})`) answered
   `:seon.test/passed? true`, 6 tests, 33 passes, run f5f69971dc7c, and changed nothing: 8 s later, and at every
   later read, the four were still `:pending`.

   Where (root, JVM reads of the chain branch): both runs recorded all four members green (`:fail-count 0
   :error-count 0`, `:pass-count` 5, 6, 5, 3), each with `:seon.test.member/dynamic-calls [seon.edit/single-form?]`
   and **no `:seon.test.member/reach-digest`**; the two proofs' members carry reach digests. With no reach digest,
   `seon.context/current-results` finds no current result for them on the branch (it answered `[]` for
   `form-edit-preserves-every-unselected-byte`, and main holds no member for it at all), so they are unknown to the
   preview and nothing the agent can run changes that. The keying is dropped in
   `seon.test.runner/commit-results!`: `executed-roots` derives `::dynamic-calls` and `::stale`, and
   `(apply dissoc digests stale)` keys nothing for a test "whose body ran a definition other than the tested one"
   ("it is kept and never reused"). The four tests reach `seon.edit/single-form?` only dynamically (their static
   closure does not hold it), so either they are in `::stale` or `reach-digests` cannot key them; not isolated
   further (the `:seon.test.member/executed` it judges is dissociated before commit, so the committed facts do not
   say which). Owner: `seon.test.runner/commit-results!` / `seon.test.runner/executed-roots` (runner.clj, held by
   the chain lanes: not touched).

   A second, spec-level gap shows here: TASK-CHAIN §2.4 says the block shows the task's own done refusals and
   "mid-chain it never shows merge refusals", and that the turn proposes `:done`. `seon.turn/task-end` proposes
   `:done` only when `seon.context/step-verdict` (the merge preview, `results-verdict` over every member) is
   `:done`, so a member without a current result blocks the proposal even where `seon.cluster.source/done-refusals`
   would let a reaching test with no result through ("A test main holds no result for is unknown, not a
   regression"). On this last task the two coincide for the final merge, but the agent cannot tell which rule holds
   it.

   **Not repaired by hand.** The chain is left running for root: branch `:chain-05dcb5c5b578`, task `05dcb5c5b578`
   (`seon.edit/outside-2-an-unreadable-odd-map-is-not-one-form`), attempt agent `attempt/768cc101f874`, its change
   (below) admitted on the branch. No send-back: the work is done and correct; once the four members key a result
   the existing path should end, merge and publish it with no agent action, which is the S3b-2 proof still owed.
   `src/seon/edit.clj` was not written; the defect is not fixed on main.

2. **A `require ... :as` alias used by a `defn` in the same request was refused by the writer.** Call 4, as one
   request: `(require '[seon.sci.reader :as reader])` then `(defn- single-form ... (reader/read {...}) ...)`. The
   defn answered "Not admitted to the program: ... The stored definition digest of [:seon.fn/sym
   seon.edit/single-form] is not the one its definition produces. Write the entity its canonical writer produced."
   (`:seon.error/operation seon.db/transact!`, expected 035f9dd1…, offending 67ca5f28…), yet the Var was bound in the
   SCI context and the same request's `(mapv single-form? ...)` answered the fixed values. The same body written with
   `seon.sci.reader/read` fully qualified (call 5) was admitted, with an `:auto-required #{seon.sci.reader}` finding
   that its ns form now requires it. The health report already lists this message class for other agents
   (`request-view-tests`, 20 occurrences). Cause not isolated: the reader or analysis that digests the defn does not
   see the alias the same request's `require` made. Producer: the defn's digest at `seon.sci.eval`/program facts.

## Setup (as root, JVM mode)

- Picked `seon.edit/an-unreadable-odd-map-is-not-one-form` (`resources/seon/tasks/seon.edit.edn`): a few lines in
  `src/seon/edit.clj` (held by no lane; `bin/lane status`: only changes-render, on context files), its red proofs
  already on main from its Prove merge (62eae1983): `seon.edit-test/an-odd-map-anywhere-is-not-one-form` (table) and
  `seon.edit-test/every-source-holding-an-odd-map-is-not-one-form` (property). Reproduced in the JVM first:
  `(seon.edit/single-form? "{:a 1 :b}")` throws "No value supplied for key: :b". No red row needed writing.
- Chain: fresh identity `seon.edit/outside-2-an-unreadable-odd-map-is-not-one-form` (the filed entry's identity not
  reused, as in outside-1), `(seon.task/template db 'seon.step.implement/template)` merged with the plan "## Problem"
  plus the filed entry's plan verbatim, the two proofs and `:seon.task/model :outside`;
  `{:seon.db/connection conn :seon.store/store (:seon.store/store inst)}` with `inst` from
  `@seon.operator.runtime/running-instances`. `chain!` 120 ms evaluate (169 ms request). Root started
  `attempt/768cc101f874` within 3 s; its start step ran both proofs red (run fd0b7904a705).
- Setup calls: 12. Wasted: `(seon.config/defaults)` (a map, not a function), `seon.operator.runtime/running-instances`
  is an atom, `seon.config/effective` needs the cluster name (called with the db alone, call preparation filled the
  db and refused the db value as `cluster-name`: "expected :seon.boot/cluster-name ..., got a map"),
  `seon.db/branch-database` takes a database value, not the store; the agent id is on the branch only, so a main
  query found nothing.
- Root-side: `:seon.task/model` still reads `":outside"` (string) through raw `datahike.api/pull` on the branch
  (filed: `seon.task/a-tasks-model-reads-back-as-the-keyword-it-was-written-as`).

## The first block, verbatim

`(seon.context/current-state)`, call 1: 505 ms evaluate, 757 ms request (test step 90 ms), 592.6 MB. Findings:
`:large-value` (`:seon.eval/value-tokens 6943`, shown 6,062 estimated tokens) and `:large-allocation`.
outside-1's first block was 5,286 shown tokens, 2,347 ms request, 300.8 MB.

```clojure
#:seon.context{:task
   #:seon.task{:sym
      seon.edit/outside-2-an-unreadable-odd-map-is-not-one-form,
    :plan
      "## Problem

`seon.edit/single-form?` throws instead of answering false when its source holds a map literal with an odd number of forms: `(seon.edit/single-form? \"{:a 1 :b}\")` throws IllegalArgumentException \"No value supplied for key: :b\" against its own `:boolean` output, and so do \"{:a}\", \"[{:a}]\" and \"(f {:a 1 :b})\" (observed in the JVM on default 6299bcda4). `seon.edit/parse-root` parses the odd map without complaint, `rewrite-clj.zip/sexpr-able?` is true for it, and `rewrite-clj.zip/sexpr` inside `seon.edit/location-sexpr` throws, so `seon.edit/valid-form-operation?` also throws on a form-edit request whose replacement holds one. Source holding an odd map anywhere is not readable, and `seon.sci.reader/read`, the one reader, already says so: it answers one read event carrying `:seon.sci.reader/error` for \"{:a 1 :b}\" and \"[{:a}]\", and events with no error for readable source such as \"(a)\", \"::x\", \"#?(:clj 1)\" and \"#(inc %)\". `seon.edit/single-form` decides readability with that reader, and an unreadable source gets the refusal it already gives a source with the wrong form count, so `seon.edit/single-form?` answers false; every readable source answers exactly what it answers today (\"#_x (a)\" stays false: its form count is a separate question, out of scope). The property generates odd-count maps with `clojure.test.check.generators/vector` of `clojure.test.check.generators/keyword` under `clojure.test.check.generators/such-that` `clojure.core/odd?` of `clojure.core/count`, and checks them with `clojure.test.check/quick-check` under `seon.test-support/assert-check!`. Read `seon.edit/single-form`, `seon.edit/single-form?`, `seon.edit/location-sexpr`, `seon.edit/parse-root` and `seon.sci.reader/read`.",
    :instructions
      "## Work

Read the plan and your proofs first: they start red; your block shows each failure. Change the producer the Approach
names, and convert its callers in the same change; read each owner with `my.program/source` before changing it.
Every def and defn you evaluate is a program definition on your branch and merges: unmap what you do not keep with
`my.program/ns-unmap!`.
A proof that contradicts the plan, or a row you believe is wrong, is not yours to satisfy: send it back with
`my.task/send-back!` and the row and why. Never special-case one row to pass.

## Done

Every proof is green, no test that is green on main is red here, and every function you changed has a complete
contract."},
 :proofs
   #:seon.context{:counts {:red 2},
    :proof-list
      [{:seon.test/sym
          seon.edit-test/an-odd-map-anywhere-is-not-one-form,
        :seon.context/proof-status :red,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.edit/implement-an-unreadable-odd-map-is-not-one-form}}
  an-odd-map-anywhere-is-not-one-form
  (doseq [[source expected]
          [[\"(a)\" true]
           [\"x\" true]
           [\"(a) ;; c\" true]
           [\"{:a 1}\" true]
           [\"#?(:clj 1)\" true]
           [\"#(inc %)\" true]
           [\"::x\" true]
           [\"(a) (b)\" false]
           [\"\" false]
           [\"{:a 1 :b}\" false]
           [\"{:a}\" false]
           [\"[{:a}]\" false]
           [\"(f {:a 1 :b})\" false]]]
    (is (= expected (seon.edit/single-form? source))
        (str \"single-form? must answer its declared boolean for \" source))))",
        :seon.context/proof-failures
          [{:type :error,
            :expected (= expected (seon.edit/single-form? source)),
            :actual
              [#:seon.error{:message "No value supplied for key: :a",
  :throwable-class "java.lang.IllegalArgumentException"}],
            :message
              "single-form? must answer its declared boolean for [{:a}]"}
           {:type :error,
            :expected (= expected (seon.edit/single-form? source)),
            :actual
              [#:seon.error{:message "No value supplied for key: :b",
  :throwable-class "java.lang.IllegalArgumentException"}],
            :message
              "single-form? must answer its declared boolean for (f {:a 1 :b})"}
           {:type :error,
            :expected (= expected (seon.edit/single-form? source)),
            :actual
              [#:seon.error{:message "No value supplied for key: :b",
  :throwable-class "java.lang.IllegalArgumentException"}],
            :message
              "single-form? must answer its declared boolean for {:a 1 :b}"}
           {:type :error,
            :expected (= expected (seon.edit/single-form? source)),
            :actual
              [#:seon.error{:message "No value supplied for key: :a",
  :throwable-class "java.lang.IllegalArgumentException"}],
            :message
              "single-form? must answer its declared boolean for {:a}"}]}
       {:seon.test/sym
          seon.edit-test/every-source-holding-an-odd-map-is-not-one-form,
        :seon.context/proof-status :red,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.edit/implement-an-unreadable-odd-map-is-not-one-form}}
  every-source-holding-an-odd-map-is-not-one-form
  (let [check
        (clojure.test.check/quick-check
         300
         (clojure.test.check.properties/for-all
          [ks (clojure.test.check.generators/such-that
               (comp odd? count)
               (clojure.test.check.generators/vector
                clojure.test.check.generators/keyword 1 7))
           w (clojure.test.check.generators/choose 0 2)]
          (let [map-text (str \"{\" (clojure.string/join \" \" ks) \"}\")
                source (case w
                         0 map-text
                         1 (str \"[\" map-text \"]\")
                         2 (str \"(f \" map-text \")\"))]
            (false? (seon.edit/single-form? source))))
         :seed 20261013)]
    (seon.test-support/assert-check!
     check
     \"Every source holding an odd-map literal must not be one form.\")))",
        :seon.context/proof-failures
          [{:type :error,
            :expected :property-holds,
            :actual
              [#:seon.error{:message "No value supplied for key: :A",
  :throwable-class "java.lang.IllegalArgumentException"}],
            :message
              "Every source holding an odd-map literal must not be one form."}
           {:type :fail,
            :expected :property-holds,
            :actual [[:A :A :A] 0],
            :message
              "Every source holding an odd-map literal must not be one form. Seed 20261013, 1 trials, shrunk in 10 nodes visited."}]}]},
 :step
   {:seon.task/status :working,
    :seon.source/refusals
      [{:seon.error/at #inst "2026-10-14T14:58:09.095Z",
        :seon.error/layer :seon.source/merge,
        :seon.error/operation seon.cluster.source/accept-merge!,
        :seon.test.run/branch :chain-05dcb5c5b578,
        :seon.source/refused-test-run "fd0b7904a705",
        :seon.error/type :seon.source/red-test-error,
        :seon.error/message
          "A test of the named run is not green; a merge commits only when every test is.",
        :seon.source/red-tests
          [{:seon.test/sym
              seon.edit-test/an-odd-map-anywhere-is-not-one-form,
            :seon.test.run/id "fd0b7904a705",
            :seon.source/verdict :red}
           {:seon.test/sym
              seon.edit-test/every-source-holding-an-odd-map-is-not-one-form,
            :seon.test.run/id "fd0b7904a705",
            :seon.source/verdict :red}]}],
    :seon.context/start {:seon.render/operations [(in-ns 'seon.edit)]}},
 :must-reads
   [{:in [:cat :string],
     :out :boolean,
     :seon.ref/name seon.edit/single-form?,
     :seon.ref/level :doc,
     :summary
       "True when `source` is exactly one readable semantic form.",
     :arglists ([source])}
    {:in [:cat :string],
     :seon.ref/name seon.edit/parse-root,
     :seon.ref/level :doc,
     :arglists ([source]),
     :out [:or :map :my.edit/parse-refused-error],
     :schemas
       {:my.edit/parse-refused-error
          {:form
             [:and
              :seon.error/base
              [:map
              [:my.edit/parse-byte-count :my.edit/parse-byte-count]]]}}}
    {:seon.ref/name rewrite-clj.zip/sexpr-able?,
     :seon.ref/pin
       #:seon.ref{:project "rewrite-clj",
        :revision "60782e501aaf312cb90c9ff0bee05d5da5125563"},
     :seon.ref/level :summary,
     :doc
       "Return true if current node's element type in `zloc` can be [[sexpr]]-ed.",
     :seon.render/operations [(doc rewrite-clj.zip/sexpr-able?)]}
    {:seon.ref/name rewrite-clj.zip/sexpr,
     :seon.ref/pin
       #:seon.ref{:project "rewrite-clj",
        :revision "60782e501aaf312cb90c9ff0bee05d5da5125563"},
     :seon.ref/level :summary,
     :doc
       "Return s-expression (the Clojure form) of current node in `zloc`.",
     :seon.render/operations [(doc rewrite-clj.zip/sexpr)]}
    {:seon.ref/name seon.edit/location-sexpr,
     :seon.ref/level :doc,
     :arglists ([location]),
     :in
       {:seon.error/at #inst "2026-10-14T14:58:09.376Z",
        :seon.error/layer :seon.sci.eval/program,
        :seon.error/operation seon.sci.lookup/declaration-absent,
        :seon.error/offending :seon.fn/spec,
        :seon.sci.eval/missing-declaration :seon.fn/spec,
        :seon.error/expected :seon.sci.eval/present-declaration,
        :seon.error/message
          "The program row lacks a declaration; supply the declared member before relying on it."},
     :out
       {:seon.error/at #inst "2026-10-14T14:58:09.376Z",
        :seon.error/layer :seon.sci.eval/program,
        :seon.error/operation seon.sci.lookup/declaration-absent,
        :seon.error/offending :seon.fn/spec,
        :seon.sci.eval/missing-declaration :seon.fn/spec,
        :seon.error/expected :seon.sci.eval/present-declaration,
        :seon.error/message
          "The program row lacks a declaration; supply the declared member before relying on it."}}
    {:out :boolean,
     :seon.ref/name seon.edit/valid-form-operation?,
     :seon.ref/level :doc,
     :summary
       "Whether a form-edit request has the required replacement source.",
     :body
       "Takes a request value and returns a boolean. This predicate validates
`:my.edit/form-request`; call `form` to perform the edit.",
     :arglists ([request]),
     :in
       [:cat
        [:any
         {:seon.schema.admission/exemption
            :seon.schema.admission/polymorphic-boundary,
          :seon.schema.admission/reason
            "A total predicate accepts arbitrary objects, including nil, and "…+58,
          :gen/elements [nil false …5]}]]}
    {:in [:cat :map],
     :seon.ref/name seon.sci.reader/read,
     :seon.ref/level :doc,
     :summary
       "Read accepted Clojure source into ordered read events or a flat error.",
     :body
       "`::ctx` is the evaluating SCI context, with the target namespace bound as
`sci.core/ns` by the caller: SCI then resolves auto-resolved keywords and
syntax-quote from that context. Without it, resolution uses only the
supplied `::ns`, `::aliases` and `::refers`.",
     :arglists
       ([{ctx :seon.sci.reader/ctx,
          aliases :seon.sci.reader/aliases,
          text :seon.sci.reader/text,
          features :seon.sci.reader/features,
          tags :seon.sci.reader/tags,
          namespace-name :seon.sci.reader/ns,
          refers :seon.sci.reader/refers,
          publics :seon.sci.reader/publics,
          defer-auto-resolve? :seon.sci.reader/defer-auto-resolve?,
          max-source :seon.config.eval.result/max-source}]),
     :out [:or [:vector :map] :seon.sci.reader/failure],
     :schemas
       {:seon.sci.reader/failure
          {:form
             [:or :seon.sci.reader/unreadable-error
              :seon.sci.reader/refused-tag-error
              :seon.sci.reader/oversize-error]}}}
    {:key :seon.sci.reader/error,
     :returns [],
     :seon.ref/name :seon.sci.reader/error,
     :seon.ref/level :doc,
     :form [:and {:seon.db/attributes true} :seon.error/base [:map …3]]}
    {:seon.ref/name seon.edit/single-form,
     :seon.ref/level :doc,
     :arglists ([source message]),
     :in [:cat :string :string],
     :out [:or :map :my.edit/parse-refused-error]}
    {:seon.ref/name clojure.test.check.generators/vector,
     :seon.ref/level :summary,
     :doc
       "Creates a generator of vectors whose elements are chosen from `generator`.",
     :seon.render/operations
       [(doc clojure.test.check.generators/vector)]}
    {:seon.ref/name clojure.test.check.generators/keyword,
     :seon.ref/level :summary,
     :doc "Generates keywords without namespaces.",
     :seon.render/operations
       [(doc clojure.test.check.generators/keyword)]}
    {:seon.ref/name clojure.test.check.generators/such-that,
     :seon.ref/level :summary,
     :doc
       "Creates a generator that generates values from `gen` that satisfy predicate `pred`.",
     :seon.render/operations
       [(doc clojure.test.check.generators/such-that)]}
    {:seon.ref/name clojure.core/odd?,
     :seon.ref/level :summary,
     :doc
       "Returns true if n is odd, throws an exception if n is not an integer",
     :seon.render/operations [(doc clojure.core/odd?)]}
    {:seon.ref/name clojure.core/count,
     :seon.ref/level :summary,
     :doc "Returns the number of items in the collection.",
     :seon.render/operations [(doc clojure.core/count)]}
    {:seon.ref/name clojure.test.check/quick-check,
     :seon.ref/level :summary,
     :doc "Tests `property` `num-tests` times.",
     :seon.render/operations [(doc clojure.test.check/quick-check)]}
    {:seon.ref/name seon.test-support/assert-check!,
     :seon.ref/level :doc,
     :summary
       "Report a test.check result as clojure.test events: a pass when it ran trials and held; else a failure whose `:actual` is the shrunk smallest input itself (a value the value printer prints once) and whose message is prose with the seed, trial count and shrink depth.",
     :body "A property that threw adds the thrown cause as an error.",
     :arglists ([check] [check message]),
     :in
       {:seon.error/at #inst "2026-10-14T14:58:09.424Z",
        :seon.error/layer :seon.sci.eval/program,
        :seon.error/operation seon.sci.lookup/declaration-absent,
        :seon.error/offending :seon.fn/spec,
        :seon.sci.eval/missing-declaration :seon.fn/spec,
        :seon.error/expected :seon.sci.eval/present-declaration,
        :seon.error/message
          "The program row lacks a declaration; supply the declared member before relying on it."},
     :out
       {:seon.error/at #inst "2026-10-14T14:58:09.424Z",
        :seon.error/layer :seon.sci.eval/program,
        :seon.error/operation seon.sci.lookup/declaration-absent,
        :seon.error/offending :seon.fn/spec,
        :seon.sci.eval/missing-declaration :seon.fn/spec,
        :seon.error/expected :seon.sci.eval/present-declaration,
        :seon.error/message
          "The program row lacks a declaration; supply the declared member before relying on it."}}
    ;; then the doc-level must-reads of my.program/source, my.program/ns-unmap! and my.task/send-back!
    ;; (summary, body, example, arglists, in/out schemas), as in outside-1
    ],
 :turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.edit}}
```

(The three tool must-reads are elided above only in this note; the agent received them whole. outside-1's `:cost`
section is absent this time.)

**Was it enough?** Yes, for the work. The plan named the producer, the reader and its exact behavior; the proof
failures named each red row and the thrown cause (outside-1's timeout red named none); the start entered the right
namespace. One `my.program/source` and one reader probe were all the reading the change needed.

## Every agent call (SCI, as `attempt/768cc101f874`)

| # | Form | Request ms / alloc (test step) | Spent on something the context should have said? |
|---|---|---|---|
| 1 | `(seon.context/current-state)` | 757 / 593 MB (90 ms) | No: the entrance. |
| 2 | `(in-ns 'seon.edit)` `(my.program/source 'seon.edit/single-form)` | 199 / 2 MB (111 ms) | No: the instructions ask for it; the body was needed to change it. |
| 3 | `seon.sci.reader/read` over the 13 table sources | 164 / 2 MB (89 ms) | No: confirmed the plan's claim about the reader (true for the four odd-map rows, false otherwise, `[]` for `""`). |
| 4 | `(ns-aliases 'seon.edit)` | 158 / 0 MB (89 ms) | Partly: whether the file already requires `seon.sci.reader`; nothing in the block says how a new require reaches the ns form. |
| 5 | `(require '[seon.sci.reader :as reader])` + `defn- single-form` (alias) + probe | 1,036 / 196 MB (81 ms) | Yes: the defn was refused at the writer (Broken 2) while the probe in the same request answered the fixed values `[true false false false false]`. |
| 6 | `defn- single-form` (fully qualified `seon.sci.reader/read`) | 5,219 / 45 MB eval, 9.3 GB step (4,447 ms) | No: admitted; `:auto-required` finding explained the ns change; reaching `:red []`, 6 tests. |
| 7 | `current-state` (proofs, step) | 2,724 / 6.5 GB (evaluate 2,514) | No: proofs `{:green 2}`. My own key mistake (`:step` for `:seon.context/step`) left two nils. |
| 8 | `(:seon.context/step (current-state))` + `(my.program/diff)` | 304 / 288 MB | Partly: four reaching tests `:pending` with no form to run them. |
| 9 | `(my.program/declaration-diff 'seon.edit)` | 125 | Yes: `my.program/diff` lists the namespace as changed with no `:dig`, and `declaration-diff` refuses an unqualified symbol, so the agent cannot see what changed in its ns form. |
| 10 | `(my.test/check {:seon.test/changed ['seon.edit/single-form]})` | 1,625 / 1.9 GB | Yes: the defn reply offered it (with an unquoted symbol, filed `seon.render.var/the-offered-check-names-an-unquoted-symbol`); it passed 6 tests and changed nothing in the block (Broken 1). |
| 11 | `current-state` (step, turns, counts) | 2,412 / 6.0 GB | Checking the end that never came. |
| 12 | `(Thread/sleep 8000)` then the step's red tests | 10,672 (block 2,506 / 6.5 GB) | Same. |

12 agent calls, 3 min 41 s wall; outside-1 took 18 calls and about 7 minutes and never had green proofs.
The change itself took calls 2-6. Time from the block to green proofs: about 50 s.

The change, as admitted on the branch (only `single-form` changed; the ns form gained `seon.sci.reader`):

```clojure
(let [read (seon.sci.reader/read {:seon.sci.reader/text source
                                  :seon.config.eval.result/max-source (count source)})
      readable? (and (vector? read) (not-any? :seon.sci.reader/error read))
      locations (top-level-locations (:seon.edit/root parsed))
      location (first locations)
      semantic (when readable? (some-> location location-sexpr))]
  ;; the rest of the body as before: an unreadable source takes the wrong-form-count refusal
  ...)
```

## Misleading or missing context (exact excerpts)

1. The block after green proofs: `:seon.context/red-tests [{:seon.test/sym seon.edit-test/form-edit-preserves-every-unselected-byte, :seon.context/proof-status :pending} ...]`
   under a key named `red-tests`, with no operation, while running them answered `:seon.test/passed? true` and left
   them `:pending`. A pending test that can never become current should say why (no reach digest; dynamic calls
   `[seon.edit/single-form?]`), and the step should not stay `:working` with nothing for the agent to do.
2. `:seon.source/refusals` still holds an `accept-merge!` preview stamped `:seon.error/at` with the read time
   (14:58:09.095, 14:59:00.773, 14:59:22.724 at three reads): filed as
   `seon.cluster.source/a-merge-previews-refusals-carry-no-read-time-stamp`. Its verdict for the four is `:unknown`
   under `:seon.source/red-test-error` "A test of the named run is not green", which names an unknown as red.
3. Must-reads `seon.edit/location-sexpr` and `seon.test-support/assert-check!`: `:in` and `:out` each hold a whole
   `seon.sci.lookup/declaration-absent` error map ("The program row lacks a declaration; supply the declared member
   before relying on it.", `:seon.error/offending :seon.fn/spec`), twice per reference with read-time stamps, where
   the function simply has no contract (a private helper; a test-support function). About 250 tokens of noise
   that reads as a fault the agent should fix. `seon.edit/location-sexpr` is the function the plan says throws,
   and it shows no schema at all.
4. Call 5's refusal "The stored definition digest ... is not the one its definition produces. Write the entity its
   canonical writer produced." tells an agent to do something no agent verb does; the cause was the alias (Broken 2).
5. `my.program/diff` lists `{:sym seon.edit, :kind :namespace, ...}` with two digests and no `:dig` form, unlike the
   function entry beside it.
6. Must-reads of library functions cited only as prose in the plan's property sentence (`clojure.core/odd?`,
   `clojure.core/count`, four test.check functions): about 400 tokens the Implement agent did not need (the
   property already exists). The plan was the filed Problem text written for Prove.

## Timings

- Block reads: 757 ms / 593 MB at the start; after the change 2,724 / 6.5 GB, 2,412 / 6.0 GB, 2,506 / 6.5 GB
  (evaluate stage 2.3-2.5 s each). outside-1 saw the same growth (filed:
  `seon.context/current-state-after-a-send-back-costs-the-block-before-it`); here no send-back and no runaway test,
  so the cause is not those: the block after a program change costs 3-4x in time and 11x in allocation.
- Test steps: 60-111 ms with nothing changed (reuse); 4,447 ms and 9.3 GB for the defn's step (2 proofs + 4
  reaching, 327 ms of test time, 296 ms fork), so about 4.1 s of the step is outside the tests themselves.
- `my.test/check` of the 4+2 reaching tests: 1,470 ms, 1.9 GB.
- `seon.db/declaration-content-key` showed `:self-us` 20-37 ms per request against 1.7 ms on main, flagged `:changed`
  on every request of the attempt.
- Root JVM reads: 37-135 ms each.

## For root

- **RESET NEEDED: no.** Default is healthy as far as observed.
- **The chain `:chain-05dcb5c5b578` is open and running, its work green on the branch.** When the reach-digest gap
  is fixed (or the done proposal stops waiting on members with no result), its next eval request or pass should
  end, merge and publish it: that is the first live automatic merge with a program change, still unproven. Or
  close it (`my.root/close!`); `src/seon/edit.clj` on main is unchanged and the filed task
  `seon.edit/an-unreadable-odd-map-is-not-one-form` stays open.
- Tasks to file (no tasks file claimed by this lane; `seon.test.runner` and `seon.turn` files are held by the chain
  lanes):
  1. `seon.test.runner`: a test that reaches a changed function only dynamically (`:seon.test.member/dynamic-calls`)
     records its green result with no `:seon.test.member/reach-digest`, so `seon.context/current-results` never
     finds it and a chain task whose change it reaches can never be done. Proof: a runner row with a test calling a
     changed function through a path its static closure lacks, asserting the committed member has a reach digest
     and `current-results` answers it. Read `seon.test.runner/commit-results!`, `seon.test.runner/executed-roots`
     and `seon.test.runner/reach-digests`. Reproduction: this chain branch, run 13d5e9fcf7c9 or f5f69971dc7c.
  2. `seon.turn/task-end` proposes `:done` from `seon.context/step-verdict` (the merge preview) rather than
     `seon.cluster.source/done-refusals`, against TASK-CHAIN §2.4 ("mid-chain it never shows merge refusals").
  3. `seon.sci.eval`: a `defn` using an alias made by `require ... :as` in the same request is refused at the
     writer for a digest mismatch while its Var is bound (Broken 2).
  4. `seon.context`/`seon.ref`: a must-read of an uncontracted function shows `seon.sci.lookup/declaration-absent`
     error maps as its `:in` and `:out`.
  5. `my.program/diff`: a changed namespace carries no dig form, and `my.program/declaration-diff` takes only a
     qualified symbol.
  6. `seon.context/current-state` after a program change on the branch: 2.4-2.7 s and 6-6.5 GB against 757 ms and
     593 MB before it (broadens the filed send-back entry).
