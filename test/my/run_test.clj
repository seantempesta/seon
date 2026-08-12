(ns my.run-test
  "Sealed acceptance draft for the two dispositions (N3, package 1).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). These are values,
  so the suite is short by construction: the whole contract is that the
  shapes validate, that they are the ONLY two, and that a bad argument
  comes back as a value an agent can read rather than a throw it
  cannot."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [my.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.schema]
            [seon.test-support :as support]))

(deftest a-disposition-is-an-ordinary-value
  (testing "wait carries its reason"
    (let [value (run/wait "waiting for the file to land")]
      (is (seon.schema/valid-candidate-value? :my.run/wait value))
      (is (= :wait (:my.run/disposition value)))))
  (testing "complete carries the reply itself — there is no result attribute"
    (let [value (run/complete "the answer is 42")]
      (is (seon.schema/valid-candidate-value? :my.run/completed value))
      (is (= :completed (:my.run/disposition value)))
      (is (= "the answer is 42" (:my.run/result value)))))
  (testing "both validate as the disposition union the loop reads"
    (is (seon.schema/valid-candidate-value?
         :my.run/value (run/wait "later")))
    (is (seon.schema/valid-candidate-value?
         :my.run/value (run/complete "done")))))

(deftest a-blank-completion-is-an-error-value-not-a-throw
  (doseq [blank ["" "   " "\n\t"]]
    (let [value (run/complete blank)]
      (is (string? (:seon.error/message value))
          "the agent gets something it can read and correct")
      (is (not (seon.schema/valid-candidate-value? :my.run/value value))
          "and the loop cannot mistake it for a disposition"))))

(deftest a-wrong-type-is-the-same-error-value-never-a-throw
  ; agent-facing boundary: (complete 123) must answer, not
  ; ClassCastException out of str/blank?
  (doseq [wrong [123 :kw {:a 1} nil [""]]]
    (is (string? (:seon.error/message (run/complete wrong))))
    (is (string? (:seon.error/message (run/wait wrong))))))

(deftest the-lifecycle-surface-has-two-actions-and-its-own-presentation
  (is (= #{'wait 'complete 'render-namespace-ai 'walkthrough 'usage-form}
         (set (keys (ns-publics 'my.run)))))
  (is (str/includes? (:doc (meta (the-ns 'my.run)))
                     "Every run ends by calling `complete` or `wait`"))
  (is (str/includes? (:doc (meta #'run/complete)) "Use `complete` when"))
  (is (str/includes? (:doc (meta #'run/wait)) "Use `wait` when"))
  (let [rendered
        (run/render-namespace-ai
         {:seon.ns/name 'my.run
          :seon.ns/doc "Every run ends with a disposition."})]
    (is (< (.indexOf rendered "complete") (.indexOf rendered "wait")))))

(deftest my-run-namespace-selects-its-declared-protocol-renderer
  (support/with-database
    (fn [connection]
      (let [database @connection
            ctx (support/fork-cluster-ctx connection)
            namespace-entity
            (db/pull database '[*] [:seon.ns/name 'my.run])
            selected
            (#'render/producer
             {:seon.db/db database
              :seon.sci.eval/ctx ctx
              :seon.render/namespace 'my.run
              :seon.render/value namespace-entity
              :seon.render/output :seon.render/ai
              :seon.sci.admit/caps
              (config/result-caps (config/defaults))
              :seon.sci.eval/time-limit-ms 5000
              :seon.config/on-core-error :record}
             :seon.render/ai :seon.render/ai)]
        (is (= 'my.run/render-namespace-ai selected))))))

(deftest ^{:seon.test/usage true} the-lifecycle-walkthrough-is-executable-data
  (let [entries (run/walkthrough)
        forms (mapv :seon.repl/form entries)]
    (is (= 5 (count entries)))
    (is (every? :seon.repl/comment entries))
    (is (= ['defn 'defn 'largest 'clojure.test/deftest 'my.run/complete]
           (mapv first forms)))
    (is (= :not-a-row-sequence (second (nth forms 2))))
    (is (= :completed
           (:my.run/disposition
            (eval (last forms)))))))
