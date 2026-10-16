(ns seon.render-test
  "What a selected render function receives from the request it renders for."
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.program :as program]
            [seon.render :as render]
            [seon.test-support :as support]))

(deftest a-render-receives-request-options
  ;; A render reads its own options from the request's `:seon.render/options`, passed whole.
  (support/with-database
   (fn [connection]
     (let [mode (fn [unit] (name (get-in unit [:seon.render/options :probe/mode] :absent)))
           request {:seon.db/db (db/db connection)
                    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                    :seon.render/value {:probe/label "value" :seon.render/ai mode}
                    :seon.render.call/id [::options]
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 5000}]
       (is (= "absent" (render/render-ai request)) "a request without options hands the render none")
       (is (= "brief" (render/render-ai (assoc request :seon.render/options {:probe/mode :brief})))
           "the request's options reach the render whole")))))

(deftest a-viewer-ai-render-is-selected-by-returning-data
  ;; A viewer namespace's AI render returns data; one declaring text is never selected.
  (support/with-database
   (fn [connection]
     (support/transacted! connection [(support/namespace-entity (db/db connection) 'fixture.viewer)])
     (support/transacted!
      connection
      [(support/program-fn-entity (db/db connection) 'fixture.viewer/data-ai
                                  "(defn data-ai {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/ai-data]} [unit] {:shown (:probe/label unit)})")
       (support/program-fn-entity (db/db connection) 'fixture.viewer/text-ai
                                  "(defn text-ai {:malli/schema [:=> [:cat :seon.render/unit] :string]} [unit] (:probe/label unit))")])
     (let [decision (render/selection {:seon.db/db (db/db connection)
                                       :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                                       :seon.render/value {:probe/label "value"}
                                       :seon.render/namespace 'fixture.viewer
                                       :seon.render/viewer-functions ['fixture.viewer/data-ai 'fixture.viewer/text-ai]
                                       :seon.render/output :seon.render/ai
                                       :seon.render.call/id [::viewer]
                                       :seon.sci.admit/caps (config/result-caps config/defaults)
                                       :seon.sci.eval/time-limit-ms 5000})
           stage (some #(when (= :namespace (:seon.render.selection.stage/name %)) %)
                       (:seon.render.selection/stages decision))
           status (into {} (map (juxt :seon.render.selection.candidate/producer :seon.render.selection.candidate/status))
                        (:seon.render.selection.stage/candidates stage))]
       (is (= 'fixture.viewer/data-ai (:seon.render.selection/selected decision)) "the render returning data is selected")
       (is (= :compatible (status 'fixture.viewer/data-ai)) "a data-returning AI render fits the AI face")
       (is (= :rejected (status 'fixture.viewer/text-ai)) "a text-returning AI render does not")))))

(defn declare-family!
  "A parent schema and a child refining it, each declaring its own AI pair, written through the canonical writers
  with the two data-returning renders they name, and `fixture.family/child-of`, contracted to return the child."
  {:malli/schema [:=> [:cat :seon.db/connection] :any]}
  [connection]
  (support/transacted! connection [(support/namespace-entity (db/db connection) 'fixture.family)])
  (support/transacted!
   connection
   [(support/program-fn-entity (db/db connection) 'fixture.family/parent-ai
                               "(defn parent-ai {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/ai-data]} [unit] {:rendered-by :parent})")
    (support/program-fn-entity (db/db connection) 'fixture.family/child-ai
                               "(defn child-ai {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/ai-data]} [unit] {:rendered-by :child})")])
  (let [row (fn [k form] (program/declaration-row (db/carried-projection (db/db connection))
                                                  {:seon.schema/key k :seon.schema/form (pr-str form)} :all :core))]
    (support/transacted! connection [(row :fixture.family/parent
                                          [:map {:seon.render/ai 'fixture.family/parent-ai} [:probe/label :string]])])
    (support/transacted! connection [(row :fixture.family/child
                                          [:merge {:seon.render/ai 'fixture.family/child-ai}
                                           :fixture.family/parent [:map [:probe/kind [:= :child]]]])])
    (support/transacted!
     connection
     [(support/program-fn-entity (db/db connection) 'fixture.family/child-of
                                 "(defn child-of {:malli/schema [:=> [:cat] [:or :fixture.family/child :seon.error/value]]} [] {:probe/label \"value\" :probe/kind :child})")])))

(deftest a-requested-schema-selects-its-own-declared-render
  ;; A value satisfying a parent and its child renders by the schema its producer declared, never the broader one.
  (support/with-database
   (fn [connection]
     (declare-family! connection)
     (let [request {:seon.db/db (db/db connection)
                    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                    :seon.render/value {:probe/label "value" :probe/kind :child}
                    :seon.render/output :seon.render/ai
                    :seon.render.call/id [::requested]
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 5000}
           selected (fn [r] (:seon.render.selection/selected (render/selection r)))]
       (is (= 'fixture.family/child-ai (selected (assoc request :seon.render/schema :fixture.family/child)))
           "the requested child selects the child's own pair")
       (is (= 'fixture.family/parent-ai (selected (assoc request :seon.render/schema :fixture.family/parent)))
           "the requested parent selects the parent's pair")
       (is (= 'fixture.family/child-ai
              (selected (assoc request :seon.render/schema [:or :fixture.family/child :seon.error/value])))
           "a declared union selects the alternative the value satisfies")
       (is (= 'seon.render.value/render-ai
              (selected (assoc request :seon.render/schema :fixture.family/child
                               :seon.render/value {:probe/label "value" :probe/kind :other})))
           "a value the requested schema refuses selects no declared pair")))))
