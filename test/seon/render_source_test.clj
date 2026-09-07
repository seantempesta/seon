(ns seon.render-source-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.data :as data]
            [seon.render.transcript :as transcript]
            [seon.render.web]
            [seon.sci.eval :as eval]
            [seon.test-support :as support]))

(def ^:private caps (config/result-caps (config/defaults)))

(defn authored-source
  "A source-producing fixture whose contract is indexed like any producer."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [_unit]
  "(+ 1 1)")

(deftest default-source-reproduces-the-exact-reached-value
  (support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:my.plan.item/id "source-provenance"
        :my.plan.item/title "Exact title"}])
     (let [database @connection
           lookup [:my.plan.item/id "source-provenance"]
           entity (db/pull database '[*] lookup)
           cursor {:seon.render.data/path [:my.plan.item/title]
                   :seon.render.data/offset 0}
           entity-source
           (render/render-default-ai-source
            {:seon.db/db database :seon.render/value entity})
           scalar-source
           (render/render-default-ai-source
            {:seon.db/db database
             :seon.render/value "Exact title"
             :seon.render.value/root lookup
             :seon.render.data/cursor cursor})
           ctx (support/fork-cluster-ctx connection)
           evaluate (fn [source]
                      (:seon.sci.admit/value
                       (eval/evaluate
                        {:seon.cluster.run.form/source source
                         :seon.sci.eval/ctx ctx
                         :seon.sci.admit/caps caps
                         :seon.sci.eval/time-limit-ms 2000
                         :seon.config/on-core-error :panic})))]
       (testing "entity source uses the public omitted-database pull"
         (is (= '(seon.db/pull (quote [*])
                               [:my.plan.item/id "source-provenance"])
                (read-string entity-source)))
         (is (= entity (evaluate entity-source))))
       (testing "a cursor constrains the source to the reached scalar"
         (is (= "Exact title" (evaluate scalar-source)))
         (is (= '(seon.render.data/pull-at
                   (quote [*])
                   [:my.plan.item/id "source-provenance"]
                   {:seon.render.data/path [:my.plan.item/title]
                    :seon.render.data/offset 0})
                (read-string scalar-source))))
       (testing "query and path failures remain flat diagnostics"
         (is (:seon.error/kind
              (data/pull-at '[*] [:my.plan.item/id "absent"] cursor)))
         (is (:seon.error/kind
              (data/pull-at '[*] lookup
                            {:seon.render.data/path [:no.such/path]
                             :seon.render.data/offset 0}))))))))

(deftest anonymous-values-refuse-to-forge-source-provenance
  (let [failure
        (render/render-default-ai-source
         {:seon.render/value (Object.)})]
    (is (= :seon.render/missing-source-provenance
           (:seon.error/kind failure)))
    (is (= :seon.render.value/root
           (:seon.error/diagnostic-member failure)))
    (is (not (re-find #"#object" (pr-str failure))))))

(deftest source-output-selects-a-source-builder-before-the-terminal-floor
  (support/with-database
   (fn [connection]
     (let [request {:seon.db/db @connection
                    :seon.sci.eval/ctx
                    (support/fork-cluster-ctx connection)
                    :seon.render/value 7
                    :seon.render/output :seon.render/ai
                    :seon.render.call/source-output? true
                    :seon.render.call/id [:source-test/default]
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic}
           decision (render/selection request)]
       (is (= 'seon.render/render-default-ai-source
              (:seon.render.selection/selected decision)))))))

(deftest source-contracts-execute-and-terminal-transcripts-never-resubmit
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "source-contract")
     (db/transact! connection
                   (agent/creation-tx
                    {:seon.cluster.agent/id "source-contract-agent"
                     :seon.ns/name 'my.agents.source-contract
                     :seon.cluster/name "source-contract"}))
     (doseq [[run-id source result] [["declared-source" "(+ 1 1)"
                                     "#:seon.print{:face :seon.print/number, :value 2}"]
                                    ["stored-transcript" "(println \"already ran\")"
                                     "#:seon.print{:face :seon.print/nil}"]]]
       (db/transact!
        connection
        [{:seon.cluster.run/id run-id
          :seon.cluster.run/agent [:seon.cluster.agent/id "source-contract-agent"]
          :seon.cluster.run/opened-at #inst "2026-09-06T20:00:00Z"
          :seon.cluster.run/closed-at #inst "2026-09-06T20:00:01Z"
          :seon.cluster.run/starting-ns [:seon.ns/name 'my.agents.source-contract]}
         {:seon.cluster.eval/id (str run-id "/0")
          :seon.cluster.eval/run [:seon.cluster.run/id run-id]
          :seon.cluster.eval/ordinal 0
          :seon.cluster.eval/at #inst "2026-09-06T20:00:00Z"
          :seon.cluster.eval/source source
          :seon.cluster.eval/ns [:seon.ns/name 'my.agents.source-contract]
          :seon.cluster.eval/result-edn result}
         {:seon.cluster.run.form/id (str run-id "/form/0")
          :seon.cluster.run.form/run [:seon.cluster.run/id run-id]
          :seon.cluster.run.form/ordinal 0
          :seon.cluster.run.form/source source}]))
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           source-call (ns-resolve 'seon.render.web 'render-source-call)
           captured-calls (atom {})
           captured-invocations (atom {})
           submissions (atom [])
           request
           (fn [id producer value]
             {:seon.db/db database :seon.sci.eval/ctx ctx
              :seon.render/value value :seon.render/ai producer
              :seon.render/output :seon.render/ai
              :seon.render/profile
              {:seon.render.profile/id :test/source-contract
               :seon.render.profile/token-budget 1000
               :seon.render.profile/max-depth 8
               :seon.render.profile/max-children 100
               :seon.render.profile/composition
               :seon.render.profile.composition/context}
              :seon.render.call/id [id]
              :seon.render/captured-calls captured-calls
              :seon.render/captured-invocations captured-invocations
              :seon.sci.admit/caps caps :seon.sci.eval/time-limit-ms 2000
              :seon.config/on-core-error :panic
              :seon.cluster.agent/id "source-contract-agent"
              :seon.cluster.loop/cluster {}
              :seon.cluster.agent/routing (atom {})})
           stored-run (db/pull database '[*] [:seon.cluster.run/id "stored-transcript"])
           output-refs
           (fn [function-symbol]
             (set (db/q '[:find [?key ...] :in $ ?symbol
                          :where [?function :seon.fn/sym ?symbol]
                          [?function :seon.fn/arities ?arity]
                          [?arity :seon.fn.arity/output-refs ?schema]
                          [?schema :seon.schema/key ?key]]
                        database function-symbol)))]
       (is (= "(+ 1 1)" (authored-source {})))
       (is (contains? (output-refs "seon.render-source-test/authored-source")
                      :seon.render/source))
       (is (not (contains? (output-refs "seon.render.transcript/render-run-ai")
                           :seon.render/source)))
       (with-redefs [agent/submit-source!
                     (fn [submission]
                       (swap! submissions conj (:seon.cluster.reply/text submission))
                       {:seon.cluster.run/id "declared-source"})]
         (is (str/includes? (source-call (request :source 'seon.render-source-test/authored-source {}))
                            "2"))
         (let [terminal-request (request :terminal 'seon.render.transcript/render-run-ai stored-run)
               terminal (source-call terminal-request)
               original-output (transcript/render-run-ai
                                (merge terminal-request stored-run))
               retained (get @captured-calls [:terminal])]
           (is (= original-output terminal))
           (is (str/includes? terminal "already ran"))
           (is (= ["(+ 1 1)"] @submissions)
               "stored transcript source is never submitted again")
           (is (nil? (:seon.cluster.run/id retained))
               "a run-valued input does not prove what evaluations a renderer displayed")
           (is (nil? (:seon.context.contribution/evaluations retained)))
           (is (nil? (:seon.render.call/source-run-id retained)))
           (is (= (db/q '[:find (count ?evaluation) .
                          :where [?evaluation :seon.cluster.eval/id]] database)
                  (db/q '[:find (count ?evaluation) .
                          :where [?evaluation :seon.cluster.eval/id]] @connection)))))))))
