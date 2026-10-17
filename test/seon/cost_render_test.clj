(ns seon.cost-render-test
  "The request cost's render pair: the AI face one line of data, the HTML face
  every member."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.batch :as batch]
            [seon.cost-support :as cost-support]
            [seon.config :as config]
            [seon.mcp :as mcp]
            [seon.profile :as profile]
            [seon.render :as render]
            [seon.test-support :as support]))

(def ^:private cost
  {:seon.cost/ms 184.6 :seon.cost/alloc-mb 8.5 :seon.cost/calls 7303 :seon.cost/reads 45
   :seon.cost/transactions 1
   :seon.cost/jvm-window {:seon.cost/scope :jvm-window :seon.cost/attribution :includes-unrelated-work
                          :seon.cost/alloc-accuracy :approximate-counter :seon.cost/cpu-accuracy :os-counter
                          :seon.cost/alloc-mb 470.0}
   :seon.cost/step {:seon.cost/alloc-mb 88.4 :seon.cost/cpu-ms 54.9}
   :seon.cost/stages {:custody 95.9 :unattributed 9.3}})

(deftest request-cost-ai-face-is-ms-alloc-and-calls
  (is (= {:seon.cost/ms 184.6 :seon.cost/alloc-mb 96.9 :seon.cost/calls 7303}
         (batch/render-request-cost-ai cost))
      "allocation is the forms' plus the step's")
  (is (= {:seon.cost/ms 1.0} (batch/render-request-cost-ai {:seon.cost/ms 1.0 :seon.cost/reads 2}))))

(deftest request-cost-html-face-holds-every-member
  (let [text (set (filter string? (tree-seq coll? seq (batch/render-request-cost-html cost))))
        keys-of (fn keys-of [m] (mapcat (fn [[k v]] (cons k (when (map? v) (keys-of v)))) m))]
    (is (every? #(contains? text (str %)) (keys-of cost)))
    (is (contains? (set (support/hiccup-leaves (batch/render-request-cost-html cost))) 184.6))))

(def ^:private compared
  "A generated-shape cost: a comparison vector with a flag set, and stages under unqualified keys."
  (assoc cost :seon.cost/diff [{:seon.cost.comparison/subject 'a/b :seon.cost.comparison/measure :self-us
                                 :seon.cost.comparison/live 0.96 :seon.cost.comparison/unknown :single-run
                                 :seon.cost.comparison/flags #{:new :changed}}]))

(deftest a-cost-rendered-through-its-pair-shows-its-members-as-data
  ;; The pair's argument is the cost merged with the render unit's custody,
  ;; its database value included.
  (support/with-database
   (fn [connection]
     (let [request {:seon.db/db @connection
                    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                    :seon.render/value compared
                    :seon.render/schema :seon.cost/request
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 2000}
           measured (profile/measure #(render/render-html request))
           html (:seon.profile/value measured)
           rows (set (keep #(when (= :th (first %)) (second %)) (support/hiccup-elements html)))
           members (->> (tree-seq coll? #(if (map? %) (vals %) (seq %)) compared) (filter map?) (mapcat keys))]
       (is (= (set (map str members)) rows) "a row for each cost member and none for the custody")
       (is (every? #(not (string? %)) (map second (filter #(= :span (first %)) (support/hiccup-elements html))))
           "a member's value is data on the page, never a printed string")
       (is (contains? (set (support/hiccup-leaves html)) :changed) "a flag set is listed")
       (is (< (:seon.profile/alloc-bytes measured) 20000000) "one cost render allocates under 20 MB")))))

(defn- answer
  "The parsed MCP result of `code` evaluated in SCI as `agent-id` on `running`."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [running agent-id code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"cluster" running "agent" agent-id
                                                         "mode" "sci" "code" code}}))))

(deftest a-reply-shows-its-cost-as-the-ai-face-and-binds-the-whole-cost
  (support/with-running-agent
   (fn [running agent-id _]
     (let [reply (answer running agent-id ":ping")
           shown (support/mcp-request reply)
           bound (cost-support/bound-cost running agent-id reply)]
       (is (= #{:seon.cost/ms :seon.cost/alloc-mb :seon.cost/calls} (set (keys shown)))
           "the reply shows the cost's one line")
       (is (every? #(contains? bound %) [:seon.cost/stages :seon.cost/jvm-window :seon.cost/shared :seon.cost/step])
           "the whole cost is bound under the handle the reply names")
       (is (= (select-keys bound [:seon.cost/ms :seon.cost/calls]) (dissoc shown :seon.cost/alloc-mb))
           "ms and calls are the bound cost's own members")
       (is (= (batch/render-request-cost-ai bound) shown) "the line is the render of the bound cost")))))
