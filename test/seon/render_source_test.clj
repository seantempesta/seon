(ns seon.render-source-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.data :as data]
            [seon.sci.eval :as eval]
            [seon.test-support :as support]))

(def ^:private caps (config/result-caps (config/defaults)))

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
