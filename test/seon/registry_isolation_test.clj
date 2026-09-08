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
  [connection cluster-name schema-key definition]
  (support/seed-cluster! connection cluster-name)
  (config/apply! {:seon.db/connection connection
                  :seon.boot/cluster-name cluster-name})
  (let [contract [:=> [:cat [:map [:seon.schema/projection :map]
                                 [::value schema-key]]] schema-key]
        result (db/transact!
                connection
                [{:seon.schema/key schema-key
                  :seon.schema/form (pr-str definition)
                  :seon.schema.admission/source :core}
                 {:seon.fn/sym "seon.registry-isolation-test/value"
                  :seon.fn/spec (pr-str contract)}])]
    (is (not (:seon.error/kind result)) (pr-str (:seon.error/kind result)))
    (schema/projection-from-database @connection)))

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
          (instrument/apply! {:seon.schema/projection a
                              :seon.config/on-core-error :panic
                              :seon.sci.admit/caps caps})
          (let [roots (into {} (map (juxt identity deref))
                            (instrument/instrumented))]
            (is (contains? roots #'value))
            (instrument/apply! {:seon.schema/projection b
                                :seon.config/on-core-error :record
                                :seon.sci.admit/caps caps})
            (is (= 7 (value {:seon.schema/projection a ::value 7})))
            (is (= "right" (value {:seon.schema/projection b ::value "right"})))
            (is (thrown? Exception (value {:seon.schema/projection a ::value "wrong"})))
            (is (thrown? Exception (value {:seon.schema/projection b ::value 7})))
            (doseq [ctx [ctx-a ctx-b]]
              (schema/call-with-projection-state
               (:seon.sci.eval/projection-state ctx) instrument/remove!))
            (is (every? (fn [[candidate root]] (identical? root @candidate)) roots))
            (is (= 9 (value {:seon.schema/projection a ::value 9})))
            (is (= "still right" (value {:seon.schema/projection b ::value "still right"})))
            (is (= global-before (m/function-schemas))))))))))
