(ns seon.render.sent-test
  "What each model call was sent, re-derived at the call's recorded basis, and its HTML face: sections sized in tokens,
  forms paired with replies, every cut named."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.context :as context]
            [seon.db :as db]
            [seon.eval]
            [seon.mcp :as mcp]
            [seon.render.sent :as sent]
            [seon.render.transcript :as transcript]
            [seon.test-support :as test-support]))

(defn- classed
  "Every element of `hiccup` whose class names `class`, depth first."
  {:malli/schema [:=> [:cat :seon.render/hiccup :string] [:vector :seon.render/hiccup]]}
  [hiccup css]
  (into [] (filter #(and (vector? %) (map? (second %)) (some #{css} (str/split (str (:class (second %))) #" "))))
        (tree-seq #(or (vector? %) (seq? %)) seq hiccup)))

(defn- text
  "The text an element shows: its strings, depth first, joined."
  {:malli/schema [:=> [:cat :seon.render/hiccup] :string]}
  [hiccup]
  (apply str (filter string? (tree-seq #(or (vector? %) (seq? %)) #(if (map? (second %)) (drop 2 %) (rest %)) hiccup))))

(def ^:private call
  {:seon.ai.attempt/id "a1" :seon.ai.attempt/ordinal 0 :seon.ai.attempt/basis-t 7 :seon.turn/id "run-1"
   :seon.ai.usage/prompt-tokens 4431 :seon.ai.usage/cached-tokens 2304})

(defn- exchange
  "Evaluation `emission` as :seon.context/exchange at `age` under the reference dial `limit`."
  {:malli/schema [:=> [:cat :seon.repl/emission :seon.context/age :seon.config.context/reference-tokens] :seon.context/exchange]}
  [emission age limit]
  {:seon.context/emission emission :seon.context/age age :seon.config.context/reference-tokens limit})

(deftest a-call-view-shows-its-sections-its-pairs-and-every-cut
  (let [exchanges [[(exchange {:seon.cluster.eval/source "(+ 1 2)" :seon.eval/shown "3" :seon.cluster.eval/id "e1"
                               :seon.repl/handle (symbol "result" "e1") :seon.eval/duration-ms 4}
                              :older 400)
                    (exchange {:seon.cluster.eval/source "(range 100)" :seon.eval/shown "(0 1 …98)" :seon.cluster.eval/id "e2"
                               :seon.repl/handle (symbol "result" "e2")}
                              :older 10)]
                   [(exchange {:seon.cluster.eval/source "(range 100)" :seon.eval/shown "(0 1 …98)" :seon.cluster.eval/id "e3"}
                              :newest 10)]]
        sent (mapv #(mapv context/exchange-ai %) exchanges)
        shown {:seon.render.sent/basis 7 :seon.render.sent/call call :seon.render.sent/exchanges exchanges
               :seon.render.sent/context {:seon.ai/system "help text" :seon.ai/prompt "{:task {:sym tq/x}}"
                                          :seon.context/exchanges sent}}
        html (sent/view-html {:seon.render/value shown})
        reference (get-in sent [0 1 :seon.context/reference])]
    (is (= ["seon-sent-system" "seon-sent-exchanges" "seon-sent-prompt"]
           (mapv #(second (str/split (:class (second %)) #" ")) (classed html "seon-sent-section")))
        "the system message, the exchanges and the turn sections, in the order they are sent")
    (is (= (mapv (juxt :seon.ai/form #(or (:seon.ai/reply %) "")) (apply concat sent))
           (mapv (fn [pair] [(text (first (classed pair "seon-sent-code")))
                             (text (last (classed pair "seon-sent-code")))])
                 (classed html "seon-sent-pair")))
        "each form is paired with its reply as the exchange's AI face sends it, character for character")
    (is (= [{:handle "result/e2"
             :says (str "elided: " (:seon.context/reply-tokens reference) " tokens over :seon.config.context/reference-tokens 10")
             :requery "(get-in result/e2 [])"}]
           (mapv (fn [elision] {:handle (:data-handle (second elision))
                                :says (text (first (classed elision "seon-sent-cut")))
                                :requery (text (first (classed elision "seon-sent-requery")))})
                 (classed html "seon-sent-elision")))
        "a reply sent by reference names its handle, its size, the dial that cut it and the form that reads it whole")
    (is (= ["…98"] (mapv text (classed html "seon-syntax-elision"))) "the value printer's cut is marked where it stands")
    (is (= ["4 ms"] (mapv text (classed html "seon-sent-cost"))) "an evaluation's stored cost stands beside its form")))

(deftest a-call-is-re-derived-at-its-own-basis
  ;; Each call's context is model-context at the basis the call recorded: a later evaluation is not in an earlier
  ;; call's exchanges.
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [evaluate! #(mcp/call (json/write-str {"name" "eval_clj" "arguments" {"code" % "agent" agent-id "cluster" running}}))
           pass! (fn [ordinal code]
                   (let [basis (db/basis-t (db/db connection))
                         _ (evaluate! code)
                         run (-> (seon.eval/of-agent (db/db connection) agent-id '[{:seon.cluster.eval/run [:seon.turn/id]}])
                                 peek (get-in [:seon.cluster.eval/run :seon.turn/id]))
                         attempt (str "sent-attempt-" ordinal)]
                     (test-support/transacted! connection
                                               [{:db/id attempt :seon.ai.attempt/id attempt :seon.ai.attempt/ordinal 0
                                                 :seon.ai.attempt/basis-t basis :seon.ai.attempt/at #inst "2026-10-16"
                                                 :seon.ai/endpoint "http://localhost/sent" :seon.ai/model "fixture"
                                                 :seon.ai.attempt/settings-edn "{}"}
                                                [:db/add [:seon.turn/id run] :seon.turn/attempts attempt]])))
           _ (doall (map-indexed pass! ["(+ 1 2)" "(+ 3 4)" "(+ 5 6)"]))
           database (db/db connection)
           calls (sent/calls database agent-id)
           handle (test-support/running-cluster running)
           world {:seon.sci.eval/ctx (:seon.sci.eval/ctx handle) :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                  :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)}
           views (mapv #(sent/view database world agent-id %) calls)]
       (is (= ["sent-attempt-0" "sent-attempt-1" "sent-attempt-2"] (mapv :seon.ai.attempt/id calls)) "oldest first")
       (is (= [nil nil nil] (mapv #(get-in % [:seon.render.sent/context :seon.error/message]) views))
           "each call's context derives at its basis")
       (is (= [[] ["(+ 1 2)"] ["(+ 1 2)" "(+ 3 4)"]]
              (mapv #(into [] (comp cat (map :seon.ai/form)) (get-in % [:seon.render.sent/context :seon.context/exchanges])) views))
           "each call holds the exchanges recorded before its basis and none after")
       (is (every? #(= (mapv (partial mapv context/exchange-ai) (:seon.render.sent/exchanges %))
                       (get-in % [:seon.render.sent/context :seon.context/exchanges]))
                   views)
           "the view's exchanges are what the context sent: their AI face is the context's exchanges")
       (let [head (sent/view database world agent-id)
             page (transcript/render-html {:seon.db/db database :seon.agent/id agent-id
                                           :seon.sci.admit/caps (:seon.sci.admit/caps handle)})]
         (is (= (mapv (juxt :seon.ai/form #(or (:seon.ai/reply %) ""))
                      (apply concat (get-in head [:seon.render.sent/context :seon.context/exchanges])))
                (mapv (fn [pair] [(text (first (classed pair "seon-sent-code")))
                                  (text (last (classed pair "seon-sent-code")))])
                      (classed page "seon-sent-pair")))
             "the transcript shows each evaluation as the next call sends it, through the exchange's HTML face"))))))
