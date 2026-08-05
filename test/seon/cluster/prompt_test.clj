(ns seon.cluster.prompt-test
  "Recurring acceptance for one retained walk per prompt."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow.core]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.cluster.prompt :as prompt]
            [seon.flow :as flow]
            [seon.render :as render]
            [seon.render.web :as web]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support])
  (:import [java.util Date]))

(def ^:private caps
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(defn- planted
  [body]
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "prompt-walk")
      (db/transact! connection
                  (agent/creation-tx
                   {:seon.cluster.agent/id "walker"
                    :seon.cluster/name "prompt-walk"
                    :seon.ns/name 'my.agents.walker}))
      (db/transact! connection
                  [{:seon.cluster.message/id "walk-message"
                    :seon.cluster.message/to
                    [:seon.cluster.agent/id "walker"]
                    :seon.cluster.message/content "inspect this walk"
                    :seon.cluster.message/at (Date. 1700000000000)}])
      (db/transact! connection
                  [{:seon.cluster.run/id "walk-run"
                    :seon.cluster.run/agent
                    [:seon.cluster.agent/id "walker"]
                    :seon.cluster.run/trigger
                    [:seon.cluster.message/id "walk-message"]
                    :seon.cluster.run/opened-at (Date. 1700000001000)}
                   {:seon.cluster.agent/id "walker"
                    :seon.cluster.agent/run
                    [:seon.cluster.run/id "walk-run"]}])
      (let [context-channel (async/chan)
            render-channel (async/chan (async/sliding-buffer 1))
            pages-channel (async/chan (async/sliding-buffer 1))
            stream-channel (async/chan (async/sliding-buffer 1))
            completion (async/promise-chan)
            ctx (sci.eval/cluster-ctx @connection connection)
            graph
            (flow.core/create-flow
             {:procs
              {:seon.render.web/render
               {:proc
                (flow/var-process
                 #'web/render-step :io
                 {:seon.render.web/render-channel render-channel
                  :seon.render/context-channel context-channel
                  :seon.render.web/pages-channel pages-channel
                  :seon.render.web/registration (atom {})
                  :seon.render.web/latest-packages (atom {})
                  :seon.render.web/completion completion
                  :seon.render.web/root-agent-id "walker"
                  :seon.cluster.loop/cluster
                  {:seon.db/connection connection
                   :seon.cluster.loop/stream-channel stream-channel
                   :seon.sci.admit/caps caps
                   :seon.sci.eval/ctx ctx
                   :seon.config.eval/time-limit-ms
                   (:seon.config.eval/time-limit-ms (config/defaults))
                   :seon.config/on-core-error :panic
                   :seon.cluster.run/process "prompt-test"}})}}
              :conns []})
            {:keys [report-chan error-chan]} (flow.core/start graph)]
        (async/go-loop [] (when (async/<! report-chan) (recur)))
        (async/go-loop [] (when (async/<! error-chan) (recur)))
        (try
          (flow.core/resume graph)
          (body connection context-channel)
          (finally
            (flow.core/stop graph)
            (async/<!! completion)))))))

(defn- request
  [connection context-channel]
  {:seon.cluster.run/id "walk-run"
   :seon.cluster.agent/id "walker"
   :seon.sci.admit/caps caps
   :seon.sci.eval/ctx (sci.eval/cluster-ctx @connection connection)
   :seon.sci.eval/time-limit-ms 2000
   :seon.config/on-core-error :panic
   :seon.render/context-channel context-channel})

(deftest prompt-is-one-retained-labeled-walk
  (planted
   (fn [connection context-channel]
     (let [render-request (request connection context-channel)
           rendered (prompt/prompt @connection render-request)
           text (:seon.cluster.prompt/text rendered)
           direct (render/call-with-walk-context
                   {:seon.db/db @connection
                    :seon.cluster.agent/id "walker"
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/ctx (:seon.sci.eval/ctx render-request)
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic}
                   render/walk)
           contribution (first (:seon.context/contributions rendered))
           lines (str/split-lines text)
           volatile-index
           (first (keep-indexed
                   (fn [index line]
                     (when (str/starts-with?
                            line ";; Volatile context metadata")
                       index))
                   lines))
           repl-index
           (first (keep-indexed
                   (fn [index line]
                     (when (str/starts-with? line ";; REPL state ")
                       index))
                   lines))]
       (is (= direct text)
           "prompt assembly calls the same public function agents call")
       (is (= text (:seon.context.contribution/text contribution)))
       (is (= :walk (:seon.render.block/name contribution)))
       (is (= 1 (count (re-seq #";; \(seon\.render/walk" text)))
           "assembly opens exactly one walk")
       (is (re-find #"(?m)^;; d\d+ · " text)
           "unit labels use the compact depth and provenance form")
       (is (str/includes? text "inspect this walk")
           "the transcript is a branch inside the walk")
       (is (str/starts-with?
            (second lines)
            ";; Some branches are elided · inspect with ")
           "load-bearing elision guidance stays beside the walk header")
       (is (and (some? volatile-index)
                (some? repl-index)
                (< volatile-index repl-index))
           "exact elision metrics join basis and time in one suffix region")
       (is (str/starts-with? (last lines) ";; REPL state namespace="))
       (is (str/includes? (last lines) "my.agents.walker"))
       (is (str/includes?
            (render/call-with-walk-context
             {:seon.db/db @connection
              :seon.cluster.agent/id "walker"
              :seon.sci.admit/caps caps
              :seon.sci.eval/ctx (:seon.sci.eval/ctx render-request)
              :seon.sci.eval/time-limit-ms 2000
              :seon.config/on-core-error :panic}
             #(render/walk {:depth 2 :branch []}))
            "branch=[]")
           "branch is the labeled get-in drill handle")
       (is (empty? (db/q '[:find ?block
                          :where
                          [?agent :seon.cluster.agent/id "walker"]
                          [?agent :seon.cluster.agent/blocks ?block]]
                        @connection))
           "creation stores no presentation blocks")))))

(deftest every-call-derives-the-current-basis
  (planted
   (fn [connection context-channel]
     (let [before (:seon.cluster.prompt/text
                   (prompt/prompt @connection
                                  (request connection context-channel)))]
       (db/transact! connection
                   [{:seon.cluster.message/id "later"
                     :seon.cluster.message/to
                     [:seon.cluster.agent/id "walker"]
                     :seon.cluster.message/content "new durable fact"
                     :seon.cluster.message/at (Date. 1700000002000)}])
       (let [after (:seon.cluster.prompt/text
                    (prompt/prompt @connection
                                   (request connection context-channel)))]
         (is (not= before after))
         (is (str/includes? after "new durable fact")))))))

(deftest identical-context-reuses-retained-ai-render-bytes
  (planted
   (fn [connection context-channel]
     (let [render-ai! render/render-ai
           invocations (atom 0)]
       (with-redefs [render/render-ai
                     (fn [render-request]
                       (swap! invocations inc)
                       (render-ai! render-request))]
         (let [first-context
               (prompt/prompt @connection
                              (request connection context-channel))
               after-first @invocations
               second-context
               (prompt/prompt @connection
                              (request connection context-channel))]
           (is (= (:seon.cluster.prompt/text first-context)
                  (:seon.cluster.prompt/text second-context)))
           (is (pos? after-first))
           (is (= after-first @invocations)
               "an identical context performs zero second-pass renderer invocations")))))))

(deftest volatile-database-metadata-follows-the-stable-context-prefix
  (planted
   (fn [connection context-channel]
     (let [before (:seon.cluster.prompt/text
                   (prompt/prompt @connection
                                  (request connection context-channel)))
           split-context (fn [text]
                           (let [marker "\n;; REPL state "
                                 offset (str/last-index-of text marker)]
                             [(subs text 0 offset) (subs text (inc offset))]))
           [before-prefix before-suffix] (split-context before)]
       (db/transact! connection [])
       (let [after (:seon.cluster.prompt/text
                    (prompt/prompt @connection
                                   (request connection context-channel)))
             [after-prefix after-suffix] (split-context after)]
         (is (= before-prefix after-prefix)
             "a basis-only transaction leaves the reusable prefix byte-identical")
         (is (not= before-suffix after-suffix)
             "basis and transaction time remain visible in the volatile suffix"))))))

(deftest a-held-run-without-a-trigger-refuses
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "no-trigger")
      (db/transact! connection
                  (agent/creation-tx
                   {:seon.cluster.agent/id "walker"
                    :seon.cluster/name "no-trigger"
                    :seon.ns/name 'my.agents.walker}))
      (db/transact! connection
                  [{:seon.cluster.run/id "walk-run"
                    :seon.cluster.run/agent
                    [:seon.cluster.agent/id "walker"]
                    :seon.cluster.run/opened-at (Date.)}])
      (testing "the custody invariant remains independent of presentation"
        (is (= :seon.cluster.prompt/no-trigger
               (:seon.cluster.prompt/rule
                (support/refusal-data
                 #(prompt/prompt @connection
                                 (request connection
                                          (async/chan)))))))))))
