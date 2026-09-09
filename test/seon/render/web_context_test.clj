(ns seon.render.web-context-test
  "Callers derive with shared evidence while the tab delta proc is paused."
  (:require [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.web-test :as web-test]
            [seon.render.web :as web]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support])
  (:import [java.net URI]
           [java.util.concurrent Callable Executors TimeUnit]))

(deftest context-and-page-do-not-demand-the-render-proc
  (#'web-test/with-server
   (fn [connection server context]
     (flow/pause (:graph context))
     (db/transact! connection
                   [{:seon.cluster.message/id "context-probe-message"
                     :seon.cluster.message/to [:seon.cluster.agent/id "root"]
                     :seon.cluster.message/at (java.util.Date.)
                     :seon.cluster.message/content "A caller-owned context."}
                    {:seon.cluster.run/id "context-probe"
                     :seon.cluster.run/agent [:seon.cluster.agent/id "root"]
                     :seon.cluster.run/opened-at (java.util.Date.)}])
     (let [ctx (:ctx context)
           request {:seon.db/db @connection
                    :seon.db/connection connection
                    :seon.cluster.agent/id "root"
                    :seon.cluster.run/id "context-probe"
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
             (is (< 1 (count costs)) "the cold context records multiple render costs")
             (is (= 1 (count (set (map second costs))))
                 "all costs cross the writer in one transaction"))
           (is (identical? (render/shared-cache ctx) (render/shared-cache ctx)))
           (is (seq (:seon.render.web/ai-calls @(render/shared-cache ctx))))
           (is (seq (:seon.render.web/calls @(render/shared-cache ctx))))))))))

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
         (let [before (#'web-test/fetch server "/agent/root/debug")
               initial @calls
               _ (#'web-test/fetch server "/agent/root/debug")]
           (is (= 200 (.statusCode before)))
           (is (pos? initial) "the real SCI renderer ran")
           (is (= initial @calls) "the unchanged page reuses its calls")
           (db/transact! connection
                         [{:seon.cluster/name "web-test"
                           :seon.source/commit-id
                           #uuid "f54229d7-54eb-472d-9ae8-917a0f97af71"}])
           (let [after (#'web-test/fetch server "/agent/root/debug")]
             (is (identical? snapshot @(:seon.sci.kernel/program-snapshot ctx)))
             (is (= 200 (.statusCode after)))
             (is (< initial @calls)
                 "adoption invalidates even without a proc wake or SCI snapshot replacement"))))))))

(deftest the-context-algorithm-runs-only-when-requested
  (#'web-test/with-server
   (fn [_connection server _context]
     (let [derive @#'web/debug-prompt
           calls (atom 0)]
       (with-redefs-fn {#'web/debug-prompt
                       (fn [& args] (swap! calls inc) (apply derive args))}
         (fn []
           (let [ordinary (#'web-test/fetch server "/agent/root/debug")]
             (is (= 200 (.statusCode ordinary)))
             (is (zero? @calls))
             (is (str/includes? (.body ordinary) "Inspect context algorithm"))
             (is (not (str/includes? (.body ordinary) "Context now"))))
           (let [explicit (#'web-test/fetch server "/agent/root/debug?prompt=true")]
             (is (= 200 (.statusCode explicit)))
             (is (pos? @calls))
             (is (str/includes? (.body explicit) "Context now"))
             (is (str/includes? (.body explicit) "Would-be system turn")))))))))

(deftest a-new-message-does-not-reinvoke-the-identity-pair
  (#'web-test/with-server
   (fn [connection server context]
     (flow/pause (:graph context))
     (let [invoke kernel/invoke
           calls (atom 0)]
       (with-redefs [kernel/invoke
                     (fn [request]
                       (when (#{"seon.cluster.agent/render-identity-ai"
                                "seon.cluster.agent/render-identity-html"}
                              (str (:seon.fn/sym request)))
                         (swap! calls inc))
                       (invoke request))]
         (let [before (#'web-test/fetch server "/agent/root/debug")
               initial @calls]
           (is (= 200 (.statusCode before)))
           (is (pos? initial))
           (db/transact! connection
                         [{:seon.cluster.message/id "identity-cache-message"
                           :seon.cluster.message/to [:seon.cluster.agent/id "root"]
                           :seon.cluster.message/at (java.util.Date.)
                           :seon.cluster.message/content "A newly connected message."}])
           (let [after (#'web-test/fetch server "/agent/root/debug")]
             (is (= 200 (.statusCode after)))
             (is (str/includes? (.body after) "A newly connected message."))
             (is (= initial @calls)
                 "reverse concern changes are not inputs to the scalar identity pair"))))))))
