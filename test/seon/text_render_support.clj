(ns seon.text-render-support
  "Shows one pinned render-bench sample on the AI face through `seon.render.value/show`, the one render path."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.printer-baseline :as baseline]
            [seon.render-bench :as bench]
            [seon.render.value :as value]
            [seon.test-support :as support]))

(defn ai-face
  "`{:sample :answer}`: the pinned sample `sample-id`'s value and its `seon.render.value/show` answer on the AI face, under the running test cluster."
  {:malli/schema [:=> [:cat :seon.db/connection :string] :map]}
  [connection sample-id]
  (let [database (db/db connection)
        custody {:seon.db/db database
                 :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                 :seon.boot/cluster-name (db/q '[:find ?name . :where [_ :seon.config/cluster ?name]] database)}
        sample (first (filter #(= sample-id (:id %)) (:samples (bench/read-samples (System/getProperty "user.dir")))))]
    {:sample (:value sample)
     :answer (value/show (assoc (baseline/unit custody (:value sample) nil)
                                :seon.render/output :seon.render/ai
                                :seon.render/outputs #{:text}))}))

(defn render-output
  "The selected render's output of `answer`: the root of its value tree."
  {:malli/schema [:=> [:cat :map] :seon.schema/value]}
  [answer]
  (:seon.print/value (:seon.render.value/tree answer)))

(defn leaves
  "Every scalar leaf of data `x`, keys included."
  {:malli/schema [:=> [:cat :seon.schema/value] [:set :seon.schema/value]]}
  [x]
  (set (remove coll? (tree-seq coll? (fn [c] (if (map? c) (mapcat identity c) (seq c))) x))))

(defn carries?
  "True when a scalar leaf of the `answer`'s value tree is, or contains as text, `expected`."
  {:malli/schema [:=> [:cat :map :seon.schema/value] :boolean]}
  [answer expected]
  (boolean (some #(or (= expected %) (and (string? %) (string? expected) (str/includes? % expected)))
                 (leaves (:seon.render.value/tree answer)))))

(defn with-ai-face
  "Runs `body` on the pinned sample `sample-id`'s value and its AI-face answer, in a running test agent."
  {:malli/schema [:=> [:cat :string [:=> [:cat :seon.schema/value :map] :any]] :any]}
  [sample-id body]
  (support/with-running-agent
   (fn [_ _ connection]
     (let [{:keys [sample answer]} (ai-face connection sample-id)]
       (body sample answer)))))
