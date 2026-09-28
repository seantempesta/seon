(ns seon.value-zoo-test
  "The value zoo: odd values at a form's root come back as their value or an
  error value, never a throw out of the run, through both entrants (an MCP
  SCI run and a scripted agent turn) and the HTML page's value printer."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.render.value :as render.value]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(def zoo
  "Each zoo value as one form: a database, a connection, a transaction report,
  host objects and references, an infinite and a throwing lazy sequence, large
  and deep values, a cycle through an atom, and the odd scalars. Symbols are
  wrapped in a call: a reply that is one bare word reads as prose."
  ["(seon.db/db)" "(identity seon.db/*conn*)" "(seon.db/with (seon.db/db) [])"
   "(do (defrecord ZooR [a]) (->ZooR 1))" "(do (deftype ZooT [a]) (->ZooT 1))"
   "(atom {:a 1})" "(promise)" "(delay (+ 1 2))" "(future 42)" "(fn [x] x)"
   "(identity #'clojure.core/inc)" "(identity *ns*)" "(Object.)" "(Thread/currentThread)"
   "(identity String)" "(range)" "(map (fn [_] (throw (ex-info \"boom when realized\" {}))) [1 2])"
   "(apply str (repeat 5000000 \"x\"))" "(vec (range 200000))"
   "(reduce (fn [m _] {:a m}) {} (range 1000))" "(let [a (atom nil)] (reset! a {:self a}) a)"
   "(identity nil)" "##NaN" "(byte-array 16)" "(sorted-map 'a 1 'b 2)" "(transient [1 2])"])

(deftest ^{:seon.test/long "One run of 26 forms per entrant (a 5M-character string and a 200k vector among them) plus arming: 54 single-form runs took 7.2 s on default at 1be0f7448."
           :seon.test/long-ms 20000}
  every-zoo-value-comes-back-through-both-entrants-and-the-page
  (let [target (support/execution-handle nil)
        connection (:seon.db/connection target)
        cluster-name (:seon.cluster/name target)
        agent-id (str "value-zoo-" (id/id))
        environment (support/environment cluster-name connection)
        ctx (support/fork-cluster-ctx connection cluster-name)
        routing (support/attempt-routing connection)
        settled (fn [result]
                  (mapv (juxt :seon.eval/shown :seon.cluster.eval/error)
                        (sort-by :seon.cluster.eval/ordinal
                                 (db/q '[:find [(pull ?e [:seon.cluster.eval/ordinal :seon.eval/shown :seon.cluster.eval/error]) ...]
                                         :in $ ?id :where [?r :seon.turn/id ?id] [?e :seon.cluster.eval/run ?r]]
                                       (db/db connection) (:seon.turn/id result)))))]
    (with-open [launcher (support/closeable
                          (flow/start-work-launcher!
                           {:seon.env/environment environment
                            :seon.flow/configuration (select-keys (config/effective (db/db connection) cluster-name)
                                                                  flow/flow-workload-attributes)})
                          flow/stop-work-launcher!)]
      (let [handle (support/cluster-handle
                    {:seon.env/environment environment :seon.db/connection connection :seon.cluster/name cluster-name
                     :seon.sci.eval/ctx ctx :seon.flow/work-launcher @launcher
                     :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                     :seon.db.process/id cluster/boot-process-identity})
            request {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id agent-id}
            text (str/join "\n" zoo)]
        (support/transacted! connection (support/agent-tx @connection agent-id))
        (agent/arm! request)
        (try
          (doseq [[entrant result] [[:mcp (agent/evaluate-source! (assoc request :seon.agent/segmenter 'seon.turn/code-sources
                                                                         :seon.cluster.reply/text text))]
                                    [:agent (turn/virtual-turn! (assoc request :seon.cluster.reply/text text))]]]
            (testing (name entrant)
              (is (:seon.turn/id result) "the run is answered, never refused or thrown")
              (when (:seon.turn/id result)
                (let [evaluations (settled result)]
                  (is (= (count zoo) (count evaluations)) "every zoo form settles as its own evaluation")
                  (is (every? (fn [[shown error]] (or (seq shown) (seq error))) evaluations)
                      "each is its value's shown text or its error value")))))
          (testing "the HTML page prints each live value, and the SCI context itself, with no throw"
            (doseq [source (conj zoo nil)
                    :let [value (if source (sci/eval-string* ctx source) ctx)
                          prepared (render.value/prepare {:seon.render/value value :seon.db/db (db/db connection)
                                                          :seon.sci.eval/ctx ctx :seon.agent/id agent-id
                                                          :seon.render.value/root [:zoo (hash source)]
                                                          :seon.sci.eval/time-limit-ms 30000
                                                          :seon.sci.admit/caps (config/result-caps config/defaults)}
                                                         :seon.render/html)]]
              (is (some? (render.value/render-html-data prepared))
                  "the page has hiccup for the value")))
          (finally
            (agent/disarm! request)
            (doseq [channel [(:seon.cluster.wake/channel handle)
                             (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
              (async/close! channel))))))))
