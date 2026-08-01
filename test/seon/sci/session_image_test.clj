(ns seon.sci.session-image-test
  "Recurring acceptance for the database-backed SCI session image."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.cluster.loop :as loop]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(def ^:private caps (config/result-caps (config/defaults)))

(defn- evaluate!
  [ctx namespace-name source]
  (eval/evaluate
   {:seon.cluster.run.form/source source
    :seon.cluster.run.form/ns [:seon.ns/name namespace-name]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps caps
    :seon.sci.eval/time-limit-ms 30000
    :seon.config/on-core-error :panic}))

(defn- commit-evaluation!
  [connection evaluation ordinal]
  (d/transact
   connection
   {:tx-data (#'loop/session-image-tx @connection evaluation ordinal)}))

(deftest fresh-context-restores-the-forms-session-image
  (test-support/with-database
   (fn [connection]
     (let [namespace-name 'my.agents.session-image
           _ (d/transact connection
                         {:tx-data [{:seon.ns/name namespace-name
                                     :seon.ns/source
                                     "(ns my.agents.session-image)"}]})
           live (eval/cluster-ctx @connection)
           sources ["(def big (vec (range 200000)))"
                    "(def names [\"Ada\" \"Grace\"])"
                    "(def limit 10)"
                    "(def scale (fn [v] (* v limit)))"
                    "(def ordered (into (sorted-set) [2 1]))"
                    "(def tagged (with-meta [1 2] {:session true}))"
                    (str "(def dropped (do (.toUpperCase \"x\") "
                         "(fn [] 1)))")]
           evaluations (mapv #(evaluate! live namespace-name %) sources)]
       (doseq [[ordinal evaluation] (map-indexed vector evaluations)]
         (commit-evaluation! connection evaluation ordinal))
       (testing "the env diff sees a redefinition through the existing SCI Var"
         (let [redefinition (evaluate! live namespace-name "(def limit 11)")]
           (is (= ["my.agents.session-image/limit"]
                  (mapv :seon.code.def/id
                        (:seon.sci.eval/session-defs redefinition))))
           (commit-evaluation! connection redefinition 7)))
       (let [fresh (eval/cluster-ctx @connection)
             resolved #(some-> (sci/resolve fresh %) deref)]
         (is (= 200000 (count (resolved 'my.agents.session-image/big))))
         (is (= 44 ((resolved 'my.agents.session-image/scale) 4)))
         (is (= "Ada, Grace"
                (str/join
                 ", " (resolved 'my.agents.session-image/names))))
         (is (= clojure.lang.PersistentTreeSet
                (class (resolved 'my.agents.session-image/ordered))))
         (is (= {:session true}
                (meta (resolved 'my.agents.session-image/tagged))))
         (is (contains? (get (sci/namespace-interns fresh) namespace-name)
                        'dropped)
             "an unrestorable name is pre-interned, never marker-bound")
         (is (= "Defining form touched host interop."
                (:seon.code.def/unrestorable
                 (d/pull @connection
                         [:seon.code.def/unrestorable]
                         [:seon.code.def/id
                          "my.agents.session-image/dropped"]))))
         )))))

(deftest two-hundred-form-image-install-stays-bounded
  (test-support/with-database
   (fn [connection]
     (let [namespace-name 'my.agents.session-cost
           rows
           (into [{:seon.ns/name namespace-name
                   :seon.ns/source "(ns my.agents.session-cost)"}]
                 (map (fn [ordinal]
                        {:seon.code.def/id
                         (str namespace-name "/n" ordinal)
                         :seon.code.def/ns [:seon.ns/name namespace-name]
                         :seon.code.def/name (symbol (str "n" ordinal))
                         :seon.code.def/source
                         (str "(def n" ordinal " " ordinal ")")
                         :seon.code.def/ordinal ordinal}))
                 (range 200))
           _ (d/transact connection {:tx-data rows})
           ctx (eval/build-base-ctx)
           _ (eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db @connection})
           started (System/nanoTime)
           _ (eval/install-session-image!
              {:seon.sci.eval/ctx ctx :seon.db/db @connection})
           elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
       (is (< elapsed-ms 50.0)
           (str "200-form session install took " elapsed-ms " ms"))
       (is (= 199 @(sci/resolve ctx 'my.agents.session-cost/n199)))))))
