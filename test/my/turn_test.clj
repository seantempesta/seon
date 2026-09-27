(ns my.turn-test
  "Sealed acceptance draft for the two dispositions (N3, package 1).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). These are values,
  so the suite is short by construction: the whole contract is that the
  shapes validate, that they are the ONLY two, and that a bad argument
  comes back as a value an agent can read rather than a throw it
  cannot."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.run :as run]
            [seon.schema]
            [seon.test-support :as support]))

(deftest a-disposition-is-an-ordinary-value
  (testing "wait carries its reason"
    (let [value (run/wait "waiting for the file to land")]
      (is ((seon.schema/projection-validator (seon.schema/handed-projection) :my.turn/wait) value))
      (is (= :wait (:my.turn/disposition value)))))
  (testing "complete carries the reply itself — there is no result attribute"
    (let [value (run/complete "the answer is 42")]
      (is ((seon.schema/projection-validator (seon.schema/handed-projection) :my.turn/completed) value))
      (is (= :completed (:my.turn/disposition value)))
      (is (= "the answer is 42" (:my.turn/result value)))))
  (testing "both validate as the disposition union the loop reads"
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :my.turn/value) (run/wait "later")))
    (is ((seon.schema/projection-validator (seon.schema/handed-projection) :my.turn/value) (run/complete "done")))))

(deftest a-blank-completion-is-an-error-value-not-a-throw
  ;; `:my.turn/result` admits any non-empty string, so whitespace reaches the
  ;; function and its own refusal is the subject here. The empty string and
  ;; every wrong type are what the DECLARED CONTRACT forbids, and those are
  ;; asserted below at the boundary an agent actually calls.
  (doseq [blank ["   " "\n\t"]]
    (let [value (run/complete blank)]
      (is (string? (:seon.error/message value))
          "the agent gets something it can read and correct")
      (is (not ((seon.schema/projection-validator (seon.schema/handed-projection) :my.turn/value) value))
          "and the loop cannot mistake it for a disposition"))))

(deftest a-contract-forbidden-argument-is-a-value-the-agent-reads
  ;; AN AGENT NEVER INVOKES THE VAR. Its reply is read into forms and each
  ;; one crosses `seon.sci.eval/evaluate`, so the claim under test — a bad
  ;; argument comes back as something the agent can read and correct, never
  ;; a throw — is a claim about THAT boundary. Under the contracts every
  ;; cluster arms, the declared contract refuses these arguments before the
  ;; function body runs; the kernel still hands the agent a flat value, which
  ;; is what this asserts. A direct call here would assert an unreachable
  ;; branch of the function instead.
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)]
       (doseq [wrong ["\"\"" "123" ":kw" "{:a 1}" "nil" "[\"\"]"]
               call ["my.turn/complete" "my.turn/wait"]]
         (let [source (str "(" call " " wrong ")")
               value (support/agent-value ctx source)]
           (is (map? value) source)
           (is (string? (:seon.error/message value)) source)
           (is (= :seon.instrument/invocation
                  (:seon.error/layer value)) source)
           (is (= :input (:seon.instrument/check value)) source)
           (is (not ((seon.schema/projection-validator (seon.schema/handed-projection) :my.turn/value) value))
               "and the loop cannot mistake it for a disposition")))))))

(deftest the-lifecycle-surface-has-two-actions-and-its-own-presentation
  (is (= #{'wait 'complete 'usage-form}
         (set (keys (ns-publics 'my.turn)))))
  (is (str/includes? (:doc (meta (the-ns 'my.turn)))
                     "Return explicit completion or waiting data for my session."))
  (is (str/includes? (:doc (meta #'run/complete)) "Use `complete` when"))
  (is (str/includes? (:doc (meta #'run/wait)) "Use `wait` when")))

(deftest the-usage-test-renders-the-lifecycle-walkthrough
  ;; The my.turn namespace entity renders through the one :seon.ns/ns pair; its
  ;; teaching lives in the usage test, whose unit declares usage-form.
  (support/with-database
    (fn [connection]
      (let [entries (run/usage-form
                     {:seon.test/sym 'my.turn-test/the-lifecycle-walkthrough-is-executable-data
                      :seon.test/usage true
                      :seon.db/db @connection})]
        (is (= '(dir 'my.turn) (:seon.repl/form (first entries))))
        (is (= 6 (count entries)))))))

(deftest ^{:seon.test/usage true} the-lifecycle-walkthrough-is-executable-data
  (let [entries (run/walkthrough)
        forms (mapv :seon.repl/form entries)]
    (is (= 5 (count entries)))
    (is (every? :seon.repl/comment entries))
    (is (= ['defn 'defn 'largest 'clojure.test/deftest 'my.turn/complete]
           (mapv first forms)))
    (is (= [nil true]
           [(-> forms (nth 3) second meta :seon.test/usage)
            (-> #'the-lifecycle-walkthrough-is-executable-data
                meta
                :seon.test/usage)]))
    (is (= :not-a-row-sequence (second (nth forms 2))))
    (is (= :completed
           (:my.turn/disposition
            (eval (last forms)))))))
