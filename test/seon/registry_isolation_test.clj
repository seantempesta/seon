(ns seon.registry-isolation-test
  "Two canonical cluster branches share host wrappers, never declarations."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [malli.core :as m]
            [malli.instrument :as mi]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(use-fixtures
  :each
  (fn [body]
    (let [roots (into {} (map (juxt identity deref)) (instrument/instrumented))]
      (try
        (body)
        (finally
          (doseq [candidate (instrument/instrumented)]
            (alter-var-root candidate mi/-f->original))
          (doseq [[candidate root] roots]
            (alter-var-root candidate (constantly root))))))))

(defn value
  "Return the supplied value; the cluster's program declares its shape."
  {:malli/schema
   [:=> [:cat [:map [:seon.schema/projection :map]
                    [::value :seon.schema/value]]]
    :seon.schema/value]}
  [request]
  (::value request))

(defn- cluster-projection
  {:malli/schema [:=> [:cat :seon.db/connection :string :qualified-keyword :seon.schema/definition] :seon.schema/projection]}
  [connection cluster-name schema-key definition]
  (support/seed-cluster! connection cluster-name)
  (config/apply! {:seon.db/connection connection
                  :seon.boot/cluster-name cluster-name})
  ;; Fixture writes go through `transacted!` (custody of the connection it
  ;; names; a nested `with-database` otherwise wrote into the other world's
  ;; branch, f46f9d461) and the program row through the production analyzer
  ;; (`replacement-tx`): `:seon.fn/sym` is a symbol since 0e8f7d323, and a
  ;; hand-written spec on a stored row fails the writer's digest check.
  (support/transacted! connection [{:seon.schema/key schema-key
                                    :seon.schema/form (pr-str definition)
                                    :seon.schema.admission/source :core}])
  (support/transacted!
   connection
   (support/replacement-tx
    @connection [:seon.fn/sym 'seon.registry-isolation-test/value]
    (str "(defn value \"Return the supplied value; the cluster's program declares its shape.\"\n"
         " {:malli/schema [:=> [:cat [:map [:seon.schema/projection :map] [:seon.registry-isolation-test/value "
         schema-key "]]] " schema-key "]}\n"
         " [request] (:seon.registry-isolation-test/value request))")
    {}))
  (schema/projection-from-database @connection))

(deftest two-clusters-own-their-contracts-and-share-stable-host-wrappers
  (support/with-database
   (fn [left]
     (support/with-database
      (fn [right]
        (let [global-before (m/function-schemas)
              a (cluster-projection left "registry-left" ::left-only :int)
              b (cluster-projection right "registry-right" ::right-only :string)
              ctx-a (support/fork-cluster-ctx left "registry-left")
              ctx-b (support/fork-cluster-ctx right "registry-right")
              caps (config/result-caps (support/effective-config))]
          (doseq [[ctx connection projection] [[ctx-a left a] [ctx-b right b]]]
            (env/advance-projection! (:seon.sci.eval/projection-state ctx)
                                    (db/basis-t @connection) projection))
          (is (not (identical? ctx-a ctx-b)))
          (is (schema/call-with-projection
               a #(schema/call-with-projection-state
                   (:seon.sci.eval/projection-state ctx-b)
                   (fn [] (identical? b (schema/handed-projection))))))
          (is (schema/call-with-projection-state
               (:seon.sci.eval/projection-state ctx-b)
               #(schema/call-with-projection
                 a (fn [] (identical? a (schema/handed-projection))))))
          (is (= 3 (support/agent-value ctx-a "(+ 1 2)")))
          (is (= 5 (support/agent-value ctx-b "(+ 2 3)")))
          (is (= :int (get (:seon.schema.projection/forms a) ::left-only)))
          (is (nil? (get (:seon.schema.projection/forms b) ::left-only)))
          (is (= :string (get (:seon.schema.projection/forms b) ::right-only)))
          (is (nil? (get (:seon.schema.projection/forms a) ::right-only)))
          (is (thrown? Exception (m/schema ::left-only)))
          ;; Each adoption arms only this suite's `value` (changed identities)
          ;; against its own cluster's projection: a whole-JVM `apply!` re-armed
          ;; every production Var without its supplied-default plan. One wrapper
          ;; per adoption (`seon.instrument/arm-var!`): a call never re-selects
          ;; another projection, so the cluster armed last governs host calls
          ;; and the other cluster's rows interpret in SCI. RETIRED: one host
          ;; wrapper validating each call against the projection it carried.
          (let [arm! (fn [projection]
                       (instrument/apply!
                        {:seon.schema/projection projection
                         :seon.sci.admit/caps caps
                         :seon.instrument/changed-identities
                         #{[:seon.fn/sym 'seon.registry-isolation-test/value]}}))]
            (is (= 1 (:seon.instrument/registered (arm! a))))
            (is (= 7 (value {:seon.schema/projection a ::value 7})))
            (is (thrown? Exception (value {:seon.schema/projection a ::value "wrong"})))
            (is (= 1 (:seon.instrument/registered (arm! b))))
            (let [roots (into {} (map (juxt identity deref))
                              (instrument/instrumented))]
              (is (contains? roots #'value))
              (is (= "right" (value {:seon.schema/projection b ::value "right"})))
              (is (thrown? Exception (value {:seon.schema/projection b ::value 7})))
              (doseq [ctx [ctx-a ctx-b]]
                (schema/call-with-projection-state
                 (:seon.sci.eval/projection-state ctx) instrument/remove!))
              (is (every? (fn [[candidate root]] (identical? root @candidate)) roots))
              (is (= "still right" (value {:seon.schema/projection b ::value "still right"})))
              (is (= global-before (m/function-schemas)))))))))))
