(ns seon.render.value-test
  "Behavioral tests for the universal structural value renderer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.render :as render]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(def ^:private effective
  (config/defaults))

(def ^:private caps
  (config/result-caps effective))

(defrecord FakeDB [max-tx max-eid])

(deftype FakeDatom [e a v]
  clojure.lang.ILookup
  (valAt [_ key]
    (case key :e e :a a :v v nil))
  (valAt [_ key not-found]
    (case key :e e :a a :v v not-found)))

(deftype HostilePrintedValue [writes]
  Object
  (toString [_]
    (swap! writes inc)
    (apply str (repeat 100000 "hostile"))))

(defn self-ai
  "Render a value-owned declaration for the precedence tests."
  [_unit]
  "value-owned")

(defn render-ai
  "Render the requesting namespace's override for the precedence tests."
  [_unit]
  "namespace-override")

(defn schema-ai
  "Render the schema-attached default for the precedence tests."
  [_unit]
  "schema-default")

(defn schema-text
  "Render a schema-attached default for a newly introduced kind."
  [_unit]
  "schema-text-default")

(defn- sampled-map
  [sampled]
  (into {}
        (map (fn [[key child]]
               [key
                (if (and (map? child)
                         (contains? child :seon.render.value/map-entries))
                  (sampled-map child)
                  child)]))
        (:seon.render.value/map-entries sampled)))

(defn- render-request
  ([kind raw]
   (render-request kind raw {}))
  ([kind raw context]
   {:seon.render/unit
    (merge {:seon.render/value raw
            :seon.sci.admit/caps caps
            :seon.config/effective effective}
           context)
    :seon.render/kind kind}))

(defn- with-active-projection
  [forms body]
  (let [before (schema/snapshot-state)]
    (try
      (schema/activate-projection! (schema/build-projection forms))
      (body)
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

(deftest every-renderer-option-registration-owns-a-default
  (let [entries
        (drop 2 (schema/schema-definition :seon.render.value/options))]
    (is (seq entries))
    (doseq [[option] entries]
      (let [properties (second (schema/schema-definition option))]
        (is (contains? properties :seon.render.value/default)
            (str option))
        (is (pos-int? (:seon.render.value/default properties))
            (str option))))))

(deftest zero-overlay-routing-uses-the-registered-presentation-defaults
  (let [rendered
        (render/render
         {:seon.render/unit {:seon.render/value (vec (range 20))}
          :seon.render/kind :seon.render/ai})
        output (:seon.render/output rendered)]
    (is (string? output))
    (is (str/includes? output "… +12 more")
        "the registered default retains eight values without caller config")
    (is (str/includes? output "partial view of vector 20 items"))))

(deftest applied-config-changes-the-next-database-backed-render
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "value-renderer-live-config"
            raw (vec (range 20))
            apply-cap!
            (fn [maximum]
              (config/apply!
               {:seon.config/connection connection
                :seon.boot/cluster-name cluster-name
                :seon.config/manifest
                {:seon.config.eval.result/max-collection maximum}}))
            shown-count
            (fn []
              (count
               (:seon.render.value/shown
                (:seon.render.value/tree
                 (value/prepare {:seon.db/db @connection
                                 :seon.render/value raw})))))]
        (apply-cap! 3)
        (is (= 3 (shown-count)))
        (apply-cap! 6)
        (is (= 6 (shown-count))
            "the next immutable database value changes caps without restart")))))

(deftest small-values-preserve-their-structure
  (is (= [1 2 3]
         (:seon.render.value/shown
          (value/sample effective [1 2 3] {}))))
  (is (= {:a 1 :b 2}
         (sampled-map (value/sample effective {:a 1 :b 2} {})))))

(deftest breadth-caps-have-honest-tail-counts
  (let [vector-sample (value/sample effective (vec (range 100))
                                    {:seon.render.value/max-collection 8})
        map-sample (value/sample
                    effective
                    (into (sorted-map)
                          (map (fn [i] [(keyword (str "k" i)) i]))
                          (range 20))
                    {:seon.render.value/max-collection 6
                     :seon.render.value/max-map-visits 12})]
    (is (= 8 (count (:seon.render.value/shown vector-sample))))
    (is (= 92 (:seon.render.value/elided vector-sample)))
    (is (= 6 (count (:seon.render.value/map-entries map-sample))))
    (is (= 14 (:seon.render.value/elided-keys map-sample)))))

(deftest depth-caps-preserve-a-typed-counted-marker
  (let [sampled (value/sample effective
                              {:a {:b {:c {:d 1 :e 2}}}}
                              {:seon.render.value/max-depth 3})
        pruned (get-in (sampled-map sampled) [:a :b :c])]
    (is (= :map (:seon.render.value/pruned pruned)))
    (is (= 2 (:seon.render.value/count pruned))))
  (testing "an empty value at the boundary stays navigable"
    (let [sampled (value/sample effective
                                {:a {:b {:c []}}}
                                {:seon.render.value/max-depth 3})]
      (is (= [] (get-in (sampled-map sampled)
                        [:a :b :c :seon.render.value/shown]))))))

(deftest retained-keys-and-indices-remain-real-navigation
  (let [live {:api/results [{:user/id 1 :user/name "John"}
                            {:user/id 2 :user/name "Jane"}]}
        sampled (value/sample effective live {})
        results (:api/results (sampled-map sampled))]
    (is (= 1 (-> results
                 :seon.render.value/shown
                 first
                 sampled-map
                 :user/id)))
    (is (= "John" (get-in live [:api/results 0 :user/name])))))

(deftest infinite-sequences-realize-only-the-bounded-head-and-sentinel
  (let [realized (atom 0)
        input (map (fn [number]
                     (swap! realized inc)
                     number)
                   (range))
        sampled (value/sample effective input
                              {:seon.render.value/max-collection 8})]
    (is (= 8 (count (:seon.render.value/shown sampled))))
    (is (= :more (:seon.render.value/elided sampled)))
    (is (= 9 @realized))))

(deftest poisoned-lazy-sequences-never-crash-the-renderer
  (doseq [input [(map (fn [_] (throw (ex-info "boom" {}))) [1 2 3])
                 {:nested (map (fn [_] (throw (ex-info "nested boom" {})))
                               [1 2 3])}]]
    (let [sampled (value/sample effective input {})
          rendered (value/render-ai
                    {:seon.render/value input
                     :seon.sci.admit/caps caps
                     :seon.config/effective effective})]
      (is (string? rendered))
      (is (str/includes? (pr-str sampled) "realization threw")))))

(deftest homogeneous-heads-name-only-the-shared-columns
  (let [rows (mapv (fn [number]
                     (cond-> {:row/id number}
                       (even? number) (assoc :row/name (str "r" number))
                       (odd? number) (assoc :row/arity number)))
                   (range 40))
        sampled (value/sample effective rows
                              {:seon.render.value/max-collection 5})]
    (is (= [:row/id] (:seon.render.value/shape sampled)))
    (is (= 35 (:seon.render.value/elided sampled)))
    (is (str/includes?
         (value/render-ai-data
          {:seon.render.value/tree sampled
           :seon.render.value/summary "vector 40 items"
           :seon.render.value/truncated? true})
         "sampled columns {:row/id}"))))

(deftest opaque-handles-and-datoms-use-the-proven-marker-vocabulary
  (let [database (value/sample effective (->FakeDB 42 99) {})
        datom (value/sample effective
                            (FakeDatom. 7 :user/name "Jane") {})]
    (is (= "datahike/DB" (:seon.eval/opaque database)))
    (is (str/includes? (:seon.eval/summary database) "max-tx=42"))
    (is (= [7 :user/name "Jane"] (:seon.eval/datom datom)))))

(deftest real-datahike-values-are-opaque-navigation-tokens
  (test-support/with-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.agent/id "rendered-agent"}])
      (let [database @connection
            database-sampled (value/sample effective database {})
            database-routed
            (render/render (render-request :seon.render/ai database))
            entity (d/entity database
                             [:seon.cluster.agent/id "rendered-agent"])
            sampled (value/sample effective entity {})
            routed (render/render
                    (render-request :seon.render/ai entity))]
        (is (= "datahike/DB" (:seon.eval/opaque database-sampled)))
        (is (str/includes? (:seon.render/output database-routed)
                           "datahike/DB"))
        (is (= "datahike/Entity" (:seon.eval/opaque sampled)))
        (is (str/includes? (:seon.eval/summary sampled) ":db/id="))
        (is (str/includes? (:seon.render/output routed)
                           "datahike/Entity"))))))

(deftest opaque-values-never-invoke-hostile-printers
  (let [writes (atom 0)
        sampled (value/sample effective (HostilePrintedValue. writes) {})]
    (is (= "jvm/Object" (:seon.eval/opaque sampled)))
    (is (zero? @writes))))

(deftest strings-and-named-scalars-clip-with-explicit-length
  (let [long-string (apply str (repeat 300 "x"))
        sampled (value/sample effective long-string
                              {:seon.render.value/max-string 80})
        huge-symbol (symbol "demo" (apply str (repeat 10000 "n")))
        symbol-output (value/render-ai
                       {:seon.render/value huge-symbol
                        :seon.sci.admit/caps caps
                        :seon.config/effective effective})]
    (is (= 300 (:seon.render.value/string-len sampled)))
    (is (<= (count (:seon.render.value/head sampled)) 80))
    (is (< (count symbol-output) 500))
    (is (str/includes? symbol-output "symbol"))))

(deftest ai-and-html-data-reuse-one-identical-skeleton
  (let [calls (atom 0)
        original value/sample
        raw (vec (range 100))]
    (with-redefs [value/sample
                  (fn [configuration input options]
                    (swap! calls inc)
                    (original configuration input options))]
      (let [projection (value/prepare
                        {:seon.render/value raw
                         :seon.sci.admit/caps caps
                         :seon.config/effective effective})]
        (is (= 1 @calls))
        (is (identical? (:seon.render.value/tree projection)
                        (:seon.render.value/tree
                         (value/render-html-data projection))))
        (is (str/includes? (value/render-ai-data projection)
                           "… +"))))))

(deftest huge-nested-output-stays-bounded
  (let [huge (vec (repeat 500
                          (into {}
                                (map (fn [number]
                                       [(keyword (str "k" number))
                                        (vec (range 50))]))
                                (range 30))))
        output (value/render-ai
                {:seon.render/value huge
                 :seon.sci.admit/caps caps
                 :seon.config/effective effective})]
    (is (< (count output) 4000))
    (is (str/includes? output "elided"))))

(deftest value-owned-render-keys-win-over-every-default
  (with-active-projection
    {::id :int
     ::shape [:map {:seon.render/ai `schema-ai}
              [::id ::id]]}
    (fn []
      (let [raw {::id 1 :seon.render/ai `self-ai}
            result (render/render
                    (render-request :seon.render/ai raw
                                    {:seon.render/namespace
                                     'seon.render.value-test}))]
        (is (= "value-owned" (:seon.render/output result)))))))

(deftest namespace-defined-override-wins-over-the-schema-default
  (with-active-projection
    {::id :int
     ::shape [:map {:seon.render/ai `schema-ai}
              [::id ::id]]}
    (fn []
      (let [result (render/render
                    (render-request :seon.render/ai {::id 1}
                                    {:seon.render/namespace
                                     'seon.render.value-test}))]
        (is (= "namespace-override" (:seon.render/output result)))))))

(deftest schema-attached-default-wins-over-the-structural-floor
  (with-active-projection
    {::id :int
     ::shape [:map {:seon.render/ai `schema-ai}
              [::id ::id]]}
    (fn []
      (let [result (render/render
                    (render-request :seon.render/ai {::id 1}))]
        (is (= "schema-default" (:seon.render/output result)))))))

(deftest schema-default-discovery-accretes-with-a-new-render-kind
  (with-active-projection
    {::id :int
     ::shape [:map {:seon.render/text `schema-text}
              [::id ::id]]}
    (fn []
      (let [result (render/render
                    (render-request :seon.render/text {::id 1}))]
        (is (= "schema-text-default"
               (:seon.render/output result)))))))

(deftest structural-renderer-is-the-floor-for-any-data
  (let [lazy-value {:rows (map (fn [number]
                                 {:row/id number
                                  :row/payload (vec (range 40))})
                               (range))}
        ai (render/render (render-request :seon.render/ai lazy-value))
        html (render/render (render-request :seon.render/html lazy-value))]
    (is (string? (:seon.render/output ai)))
    (is (str/includes? (:seon.render/output ai) "elided"))
    (is (vector? (:seon.render/output html)))
    (is (= :div (first (:seon.render/output html))))))
