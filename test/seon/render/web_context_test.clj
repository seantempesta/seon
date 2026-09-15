(ns seon.render.web-context-test
  "Callers derive with shared evidence while the tab delta proc is paused."
  (:require [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.walk :as walk]
            [seon.render.web-test :as web-test]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support])
  (:import [java.net URI]
           [java.util.concurrent Callable Executors TimeUnit]))

(deftest context-and-page-do-not-demand-the-render-proc
  (#'web-test/with-server
   (fn [connection server context]
     (flow/pause (:graph context))
     (db/transact! connection
                   [{:seon.message/id "context-probe-message" :seon.message/to [:seon.agent/id "root"] :seon.message/content "A caller-owned context." :seon.message/inbox [:seon.agent/id "root"]}
                    {:seon.turn/id "context-probe" :seon.turn/agent [:seon.agent/id "root"] :seon.turn/opened-tx "datomic.tx"}])
     (let [ctx (:ctx context)
           request {:seon.db/db @connection
                    :seon.db/connection connection
                    :seon.agent/id "root"
                    :seon.turn/id "context-probe"
                    :seon.sci.eval/ctx ctx
                    :seon.sci.admit/caps (config/result-caps (config/defaults))
                    :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                    :seon.config/on-core-error :record
                    :seon.render/distance 1
                    :seon.render/profile (render/agent-render-profile (config/defaults))}]
       (with-open [executor (Executors/newVirtualThreadPerTaskExecutor)]
         (let [context-result (.submit executor
                                       ^Callable (fn []
                                                   [(.isVirtual (Thread/currentThread))
                                                    (render/acquire-context! request)]))
               page-result (.submit executor
                                    ^Callable (fn []
                                                (let [connection (.openConnection
                                                                  (.toURL (URI/create
                                                                           (str (:seon.render.web/url server)
                                                                                "/agent/root"))))]
                                                  (.setReadTimeout connection (* 1000 support/event-backstop-seconds))
                                                  (try (.getResponseCode connection)
                                                       (finally (.disconnect connection))))))
               [virtual? result] (.get context-result support/event-backstop-seconds TimeUnit/SECONDS)]
           (is virtual?)
           (is (string? (:seon.cluster.prompt/text result)) (pr-str result))
           (is (= 200 (.get page-result support/event-backstop-seconds TimeUnit/SECONDS)))
           (let [costs (db/q '[:find ?cost ?tx
                               :in $ ?basis
                               :where [?cost :seon.render.cost/estimated-tokens _ ?tx]
                                      [(> ?tx ?basis)]]
                             @connection (db/basis-t (:seon.db/db request)))]
             (is (empty? costs) "saved history writes no new render costs"))
           (is (identical? (render/shared-cache ctx) (render/shared-cache ctx)))
           (is (seq (:seon.render.web/calls @(render/shared-cache ctx))))
           (db/transact! connection
             [{:seon.agent/id "root"
               :seon.agent/runtime {:seon.runtime/agent [:seon.agent/id "root"]
                                    :seon.runtime/turns [[:seon.turn/id "context-probe"]]}}
              {:seon.cluster.eval/id "retained-history"
               :seon.cluster.eval/run [:seon.turn/id "context-probe"]
               :seon.cluster.eval/ordinal 0
               :seon.cluster.eval/at (java.util.Date.)
               :seon.cluster.eval/source "(+ 1 1)" :seon.eval/shown "2"}])
           (let [request (dissoc request :seon.turn/id)
                 invoke kernel/invoke
                 calls (atom 0)
                 history walk/history
                 walks (atom 0)
                 checks (atom 0)
                 read-current? db/read-evidence-current?]
             (with-redefs [kernel/invoke (fn [input] (swap! calls inc) (invoke input))
                           walk/history (fn [input] (swap! walks inc) (history input))
                           db/read-evidence-current? (fn [database evidence]
                                                       (swap! checks inc)
                                                       (read-current? database evidence))]
               (let [first-result (render/acquire-context! (assoc request :seon.db/db (db/db connection)))
                     first-count @calls
                     _ (reset! checks 0)
                     second-result (render/acquire-context! (assoc request :seon.db/db (db/db connection)))]
                 (is (zero? @checks) "carried wrappers of one commit skip all read replay")
                 (is (pos? first-count))
                 (is (= (:seon.cluster.prompt/text first-result) (:seon.cluster.prompt/text second-result)))
                 (is (= first-count @calls) "unchanged saved evaluations reuse their retained calls")
                 (is (= 1 @walks) "current retained history is not queried and folded again")
                 (is (identical? (:seon.render.history/entries first-result)
                                 (:seon.render.history/entries second-result)))
                 (db/transact! connection
                   [{:seon.message/id "context-probe-message"
                     :seon.message/content "Does not change saved evaluations."}])
                 (let [unrelated (render/acquire-context! (assoc request :seon.db/db @connection))]
                   (is (= 1 @walks) "an unrelated transaction preserves the acquired history")
                   (is (identical? @connection (:seon.db/db unrelated))))
                 (let [changed (db/transact! connection
                                 [(assoc (db/pull @connection '[*] [:seon.cluster.eval/id "retained-history"])
                                         :seon.eval/shown "3")])]
                   (is (:db-after changed) (pr-str changed)))
                 (is (= "3" (:seon.eval/shown (db/pull @connection [:seon.eval/shown] [:seon.cluster.eval/id "retained-history"]))))
                 (let [changed (render/acquire-context! (assoc request :seon.db/db @connection))]
                   (is (> @calls first-count) "changed saved bytes invalidate the retained call")
                   (is (= 2 @walks) "a changed evaluation invalidates the retained history root")
                   (is (not= (:seon.cluster.prompt/text first-result) (:seon.cluster.prompt/text changed)))))))))))))

(deftest adoption-invalidates-pages-with-an-unchanged-sci-snapshot
  (#'web-test/with-server
   (fn [connection server context]
     (flow/pause (:graph context))
     (let [ctx (:ctx context)
           snapshot @(:seon.sci.kernel/program-snapshot ctx)
           invoke kernel/invoke
           calls (atom 0)]
       (with-redefs [kernel/invoke
                     (fn [request] (swap! calls inc) (invoke request))]
         (let [before (#'web-test/fetch server "/agent/root")
               initial @calls
               _ (#'web-test/fetch server "/agent/root")]
           (is (= 200 (.statusCode before)))
           (is (pos? initial) "the real SCI renderer ran")
           (is (= initial @calls) "the unchanged page reuses its calls")
           (let [report (db/transact! connection
                          [[:db/add [:seon.cluster/name "web-test"]
                            :seon.source/commit-id
                            #uuid "f54229d7-54eb-472d-9ae8-917a0f97af71"]])]
             (is (:db-after report) (pr-str report)))
           (let [after (#'web-test/fetch server "/agent/root")]
             (is (identical? snapshot @(:seon.sci.kernel/program-snapshot ctx)))
             (is (= 200 (.statusCode after)))
             (is (< initial @calls)
                 "adoption invalidates even without a proc wake or SCI snapshot replacement"))))))))

(deftest selected-session-defers-prompt-acquisition
  (#'web-test/with-server
   (fn [_connection server _context]
     (let [derive-prompt render/acquire-context!
           calls (atom 0)]
       (with-redefs-fn {#'render/acquire-context!
                       (fn [& args] (swap! calls inc) (apply derive-prompt args))}
         (fn []
           (let [ordinary (#'web-test/fetch server "/agent/root/debug")]
             (is (= 200 (.statusCode ordinary)))
             (is (= 1 @calls) "The ledger acquires current history once for saved rows and byte totals.")
             (is (str/includes? (.body ordinary) "Turn ledger"))
             (is (str/includes? (.body ordinary) "Record")))
           (let [explicit (#'web-test/fetch server "/agent/root/debug?prompt=true")]
             (is (= 200 (.statusCode explicit)))
             (is (= 1 @calls) "The explicit session defers its selected prompt; it adds no acquisition.")
             (is (str/includes? (.body explicit) "Session")))
           (let [inspection (#'web-test/fetch server "/agent/root/debug?subject=%5B%3Aseon.agent%2Fid%20%22root%22%5D")]
             (is (= 200 (.statusCode inspection)))
             (is (str/includes? (.body inspection) "Session"))
             (is (str/includes? (.body inspection) "debug-units"))
             (is (not (str/includes? (.body inspection) "Context now")))
             (is (not (str/includes? (.body inspection) "debug-ai-"))))))))))

(deftest a-new-message-does-not-reinvoke-the-identity-pair
  (#'web-test/with-server
   (fn [connection server context]
     (flow/pause (:graph context))
     (let [invoke kernel/invoke
           calls (atom 0)]
       (with-redefs [kernel/invoke
                     (fn [request]
                       (when (#{"seon.cluster.agent/render-identity-html"}
                              (str (:seon.fn/sym request)))
                         (swap! calls inc))
                       (invoke request))]
         (let [before (#'web-test/fetch server "/agent/root")
               initial @calls]
           (is (= 200 (.statusCode before)))
           (is (pos? initial))
           (db/transact! connection
                         [{:seon.message/id "identity-cache-message" :seon.message/to [:seon.agent/id "root"] :seon.message/content "A newly connected message." :seon.message/inbox [:seon.agent/id "root"]}])
           (let [after (#'web-test/fetch server "/agent/root")]
             (is (= 200 (.statusCode after)))
             (is (= "A newly connected message."
                    (:seon.message/content
                     (db/pull @connection [:seon.message/content]
                              [:seon.message/id "identity-cache-message"]))))
             (is (= initial @calls)
                 "reverse concern changes are not inputs to the scalar identity pair"))))))))
