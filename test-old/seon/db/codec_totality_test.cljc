(ns seon.db.codec-totality-test
  "Standing schema-generated totality property for the database wire codec."
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])
   [clojure.test.check :as tc]
   #?(:clj [clojure.test.check.properties :as prop]
      :cljs [clojure.test.check.properties :as prop :include-macros true])
   [malli.core :as m]
   [malli.generator :as mg]
   [malli.util :as mu]
   [seon.agent.interaction :as interaction]
   [seon.db.protocol :as protocol]
   [seon.db.transport.uds :as uds]
   [seon.schema :as schema]))

(def ^:private trials-per-arm 30)
(def ^:private gate-seed 424242)

(defn- protocol-projection
  []
  (or (schema/current-projection)
      (schema/build-projection (schema/registered-schemas))))

(defn- message-leaves
  [shape]
  (let [shape (m/deref-all shape)]
    (case (m/type shape)
      :multi (mapcat message-leaves (map val (m/entries shape)))
      :or (mapcat message-leaves (m/children shape))
      [shape])))

(defn- required-map-keys
  [shape]
  (into []
        (keep (fn [[key entry]]
                (when-not (:optional (m/properties entry)) key)))
        (m/entries shape)))

(defn- wire-shape-variants
  [shape]
  (let [shape (m/deref-all shape)]
    [{:label :generated :shape shape}
     {:label :optional-present :shape (mu/required-keys shape)}
     {:label :optional-absent
      :shape (mu/select-keys shape (required-map-keys shape))}]))

(defn- registered-wire-shapes
  [options]
  (mapcat
   (fn [root]
     (mapcat
      (fn [shape]
        (map #(assoc % :root root) (wire-shape-variants shape)))
      (message-leaves (m/schema root options))))
   [::protocol/request ::protocol/response ::interaction/entity]))

(defn- round-trip-property
  [{:keys [shape root]} options]
  (prop/for-all [message (mg/generator shape options)]
    (let [{::protocol/keys [projected-value]}
          (protocol/wire-envelope-projection message)]
      (and (m/validate shape message)
           (m/validate root projected-value options)
           (protocol/ordinary-wire-value? projected-value)
           (= projected-value
              (uds/decode (uds/encode projected-value)))))))

(deftest every-registered-wire-shape-is-total-and-round-trips
  (let [projection (protocol-projection)
        options (:seon.schema.projection/compile-options projection)]
    (doseq [{:keys [label shape] :as wire-shape}
            (registered-wire-shapes options)]
      (testing (str label " " (m/form shape))
      (let [{:keys [result shrunk] :as check}
            (tc/quick-check trials-per-arm
                            (round-trip-property wire-shape options)
                            :seed gate-seed)]
        (is (true? result)
            (str "wire totality property falsified for " (m/form shape)
                 "; seed=" gate-seed
                 "; smallest=" (pr-str (:smallest shrunk))
                 "; check=" (pr-str check))))))))

(deftest datahike-read-shapes-match-the-vendored-grammars
  (testing "entity ids follow Datahike schema and lookup resolution"
    ;; datahike/schema.cljc:6-9; datahike/db/utils.cljc:106-139.
    (is (m/validate ::protocol/entity-id 1))
    (is (m/validate ::protocol/entity-id [:user/id "alice"]))
    (is (m/validate ::protocol/entity-id :db/ident))
    (is (m/validate ::protocol/entity-id "tempid"))
    (is (not (m/validate ::protocol/entity-id {1.5 0}))))
  (testing "pull selectors follow datalog-parser's pull grammar"
    ;; datalog/parser/pull.cljc:65-198.
    (is (m/validate ::protocol/selector
                    '[* :db/id
                      {:friend [[:name :limit 2]
                                [:nickname :default "unknown"]]}]))
    (is (not (m/validate ::protocol/selector 63))))
  (testing "query forms follow Datahike normalization and datalog-parser"
    ;; datahike/query.cljc:98-121; datalog/parser.cljc:9-24.
    (is (m/validate ::protocol/query-form
                    '[:find ?e :where [?e :db/ident ?ident]]))
    (is (m/validate ::protocol/query-form
                    '{:find [?e] :where [[?e :db/ident ?ident]]}))
    (is (not (m/validate ::protocol/query-form 63))))
  (testing "database values use Datahike connection IDs"
    ;; datahike/store.cljc:50-61.
    (is (m/validate :seon.db/db
                    {:db-name "default"
                     :store-id [(random-uuid) :db]
                     :t 0
                     :as-of nil
                     :since nil
                     :history false
                     :datahike/commit-id (random-uuid)}))
    (is (not (m/validate :seon.db/db
                         {:db-name "default"
                          :store-id [#{{0.5 0}} nil]
                          :t 0
                          :as-of nil
                          :since nil
                          :history false
                          :datahike/commit-id (random-uuid)}))))
  (testing "fractional numeric map keys are projected before Transit"
    (let [{::protocol/keys [projected-value degraded?]}
          (protocol/wire-projection {0.5 0})]
      (is degraded?)
      (is (= {"0.5" 0} projected-value))
      (is (= projected-value
             (uds/decode (uds/encode projected-value)))))))
