(ns seon.render.web-context-test
  "Callers derive with shared evidence while the tab delta proc is paused."
  (:require [clojure.edn]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.web]
            [seon.render.web-test :as web-test]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support])
  (:import [java.net URI]
           [java.util.concurrent Callable Executors TimeUnit]))

(deftest context-and-page-do-not-demand-the-render-proc
  (#'web-test/with-server
   (fn [connection server context]
     (flow/pause (:graph context))
     (support/transacted! connection
                          [{:seon.message/id "context-probe-message" :seon.message/to [:seon.agent/id "root"] :seon.message/content "A caller-owned context."}
                           {:seon.turn/id "context-probe" :seon.turn/agent [:seon.agent/id "root"] :seon.turn/opened-tx "datomic.tx"}])
     (let [ctx (:ctx context)
           request {:seon.db/db @connection
                    :seon.db/connection connection
                    :seon.agent/id "root"
                    :seon.turn/id "context-probe"
                    :seon.sci.eval/ctx ctx
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                    :seon.render/distance 1
                    :seon.render/profile (render/agent-render-profile config/defaults)}]
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
           (is (string? (:seon.cluster.prompt/text result)))
           (is (= 200 (.get page-result support/event-backstop-seconds TimeUnit/SECONDS)))
           (is (identical? (render/shared-cache ctx) (render/shared-cache ctx)))
           (is (seq (:seon.render.web/calls @(render/shared-cache ctx))))
           (support/transacted! connection
                    [{:seon.cluster.eval/id "retained-history"
                      :seon.cluster.eval/run [:seon.turn/id "context-probe"]
                      :seon.cluster.eval/ordinal 0
                      :seon.cluster.eval/at (java.util.Date.)
                      :seon.cluster.eval/source "(+ 1 1)" :seon.eval/shown "2"}])
           (let [request (dissoc request :seon.turn/id)
                 ;; The armed per-definition counters observe invocations,
                 ;; history walks and read checks; no Var is redefined.
                 counted (fn [thunk]
                           (let [[result calls] (support/armed-calls
                                                 '#{seon.sci.kernel/invoke seon.render.walk/history
                                                    seon.db/read-evidence-current?}
                                                 thunk)]
                             [result {:calls (get calls 'seon.sci.kernel/invoke)
                                      :walks (get calls 'seon.render.walk/history)
                                      :checks (get calls 'seon.db/read-evidence-current?)}]))
                 acquire #(render/acquire-context! (assoc request :seon.db/db %))
                 [first-result first-counts] (counted #(acquire (db/db connection)))
                 [second-result second-counts] (counted #(acquire (db/db connection)))]
             (is (zero? (:checks second-counts)) "carried wrappers of one commit skip all read replay")
             (is (pos? (:calls first-counts)))
             (is (= (:seon.cluster.prompt/text first-result) (:seon.cluster.prompt/text second-result)))
             (is (zero? (:calls second-counts)) "unchanged saved evaluations reuse their retained calls")
             (is (= 1 (+ (:walks first-counts) (:walks second-counts)))
                 "current retained history is not queried and folded again")
             (is (identical? (:seon.render.history/entries first-result)
                             (:seon.render.history/entries second-result)))
             ;; UNRELATED means unrelated: an entity NO acquisition
             ;; derives interest in. An inbox edge to the agent under test
             ;; is a LISTENED attribute and would be a wake; a message to a
             ;; bystander agent mints a half-agent beside the real creation
             ;; path (peer issue
             ;; an-unrelated-fixture-transaction-mints-a-half-agent). A
             ;; `:db/doc` probe entity carries no identity and joins
             ;; nothing. It sits on the transaction entity: a bare entity
             ;; is refused as unowned by the writer's component check.
             (support/transacted! connection
                      [{:db/id "datomic.tx" :db/doc "An unrelated fact on an entity nothing derives interest in."}])
             (let [[unrelated unrelated-counts] (counted #(acquire @connection))
                   [_ reuse-counts] (counted #(acquire (db/db connection)))]
               (is (zero? (:walks unrelated-counts)) "an unrelated transaction preserves the acquired history")
               (is (identical? @connection (:seon.db/db unrelated)))
               (is (zero? (:checks reuse-counts)) "reuse carries the successfully checked committed basis"))
             (let [changed (db/transact! connection
                             [(assoc (db/pull @connection '[*] [:seon.cluster.eval/id "retained-history"])
                                     :seon.eval/shown "3")])]
               (is (:db-after changed)))
             (is (= "3" (:seon.eval/shown (db/pull @connection [:seon.eval/shown] [:seon.cluster.eval/id "retained-history"]))))
             (let [[changed changed-counts] (counted #(acquire @connection))]
               (is (pos? (:calls changed-counts)) "changed saved bytes invalidate the retained call")
               (is (= 1 (:walks changed-counts)) "a changed evaluation invalidates the retained history root")
               (is (not= (:seon.cluster.prompt/text first-result) (:seon.cluster.prompt/text changed)))))))))))

(deftest unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot
  (#'web-test/with-server
   (fn [connection server context]
     (flow/pause (:graph context))
     (let [ctx (:ctx context)
           snapshot @(:seon.sci.kernel/program-snapshot ctx)
           invoke kernel/invoke
           calls (atom {})]
       (with-redefs [kernel/invoke
                     (fn [request] (swap! calls update (str (:seon.fn/sym request)) (fnil inc 0)) (invoke request))]
         (let [before (#'web-test/fetch server "/agent/root")
               initial @calls
               _ (#'web-test/fetch server "/agent/root")]
           (is (= 200 (.statusCode before)))
           (is (seq initial) "the real SCI renderers ran")
           (is (= initial @calls) "the unchanged page reuses its calls")
           (let [report (db/transact! connection
                          [[:db/add [:seon.cluster/name "web-test"]
                            :seon.source/commit-id
                            #uuid "f54229d7-54eb-472d-9ae8-917a0f97af71"]])]
             (is (:db-after report)))
           (let [after (#'web-test/fetch server "/agent/root")]
             (is (identical? snapshot @(:seon.sci.kernel/program-snapshot ctx)))
             (is (= 200 (.statusCode after)))
             (let [status-renderer "seon.cluster.status/render-html"
                   ;; A read whose dependency is `:all` attributes has no
                   ;; revision proof, and retained evidence carries no read
                   ;; result to replay (owner ruling 2026-09-26: re-render,
                   ;; never replay), so any commit makes its block a
                   ;; candidate, and a candidate block re-derives the page.
                   all-read (into #{}
                                  (keep (fn [call]
                                          (when (some #(= :all (get-in % [:datahike.read/revision
                                                                          :datahike.read/attributes]))
                                                      (:seon.render.call/read-evidence call))
                                            (str (get-in call [:seon.render.call/static-evidence
                                                               :seon.render.call/producer])))))
                                  (vals (get-in @(render/shared-cache ctx)
                                                [:seon.render.web/calls "root"])))]
               (is (= (apply dissoc initial status-renderer all-read)
                      (apply dissoc @calls status-renderer all-read))
                   "renderers with revision-proved reads keep their calls")
               (is (= 1 (get initial status-renderer))
                   "the first page renders the cluster observation")
               (is (= 2 (get @calls status-renderer))
                   ;; diagnosis (shrink-web 2026-09-26): why the status call
                   ;; was not re-invoked after a write to a read attribute
)
               (is (str/includes? (.body after)
                                  "f54229d7-54eb-472d-9ae8-917a0f97af71")
                   "the page shows the newly adopted commit")))))))))

(deftest root-page-blocks-read-attribute-bounded
  ;; A read Datahike plans on `:all` attributes (a `[*]` pull, a pull inside
  ;; :find) has no revision proof, so every commit re-rendered its block and
  ;; re-derived the page. Every retained call on the root page reads named
  ;; attributes. Two wait: faults (`seon.error/for-agent`,
  ;; tmp/shrink-web-opus/error-faults-bounded.patch) and settings, whose
  ;; `config/effective` pulls the config entity with `[*]`
  ;; (tmp/shrink-web-opus/config-bounded-dials.patch: 73 -> 310 µs a read).
  (#'web-test/with-server
   (fn [_connection server context]
     (flow/pause (:graph context))
     (is (= 200 (.statusCode (#'web-test/fetch server "/agent/root"))))
     (let [unbounded
           (into (sorted-set)
                 (keep (fn [call]
                         (when (some #(= :all (get-in % [:datahike.read/revision
                                                        :datahike.read/attributes]))
                                     (:seon.render.call/read-evidence call))
                           (str (get-in call [:seon.render.call/static-evidence
                                              :seon.render.call/producer])))))
                 (vals (get-in @(render/shared-cache (:ctx context))
                               [:seon.render.web/calls "root"])))]
       (is (empty? (disj unbounded "seon.error/render-faults-html" "seon.agent/render-settings-html")))))))

(deftest the-context-view-is-computed-only-when-opened
  (#'web-test/with-server
   (fn [_connection server _context]
     ;; The armed counters observe the server thread's calls; no Var is redefined.
     (let [counted (fn [thunk]
                     (let [[result calls] (support/armed-calls
                                           '#{seon.cluster.prompt/prompt seon.render/acquire-context!} thunk)]
                       [result (get calls 'seon.cluster.prompt/prompt)
                        (get calls 'seon.render/acquire-context!)]))
           [ordinary ordinary-prompts ordinary-acquisitions] (counted #(#'web-test/fetch server "/agent/root/debug"))
           [opened opened-prompts _] (counted #(#'web-test/fetch server "/agent/root/debug?sent=true"
                                                                 {"datastar-request" "true"}))
           inspection (#'web-test/fetch server "/agent/root/debug?subject=%5B%3Aseon.agent%2Fid%20%22root%22%5D")]
       (is (= 200 (.statusCode ordinary)))
       (is (zero? ordinary-prompts) "loading the page assembles no context")
       (is (zero? ordinary-acquisitions) "loading the page acquires no history")
       (is (some? (web-test/element (.body ordinary) "details.seon-session-context")))
       (is (some? (web-test/element (.body ordinary) "details.seon-session-record")))
       (is (= 200 (.statusCode opened)))
       (is (= 1 opened-prompts) "opening the view assembles the context once")
       (is (some? (web-test/element (.body opened) ".seon-data-panel")) "the value printer's panel shows it")
       (is (= 200 (.statusCode inspection)))
       (is (str/includes? (.body inspection) "debug-units"))
       (is (not (str/includes? (.body inspection) "Context now")))
       (is (not (str/includes? (.body inspection) "debug-ai-")))))))

(deftest a-new-message-does-not-reinvoke-the-identity-pair
  (#'web-test/with-server
   (fn [connection server context]
     (flow/pause (:graph context))
     ;; The identity pair's armed counter observes its invocations; no Var is
     ;; redefined.
     (let [identity-renders (fn [thunk]
                              (let [[result calls] (support/armed-calls '#{seon.cluster.agent/render-identity-html} thunk)]
                                [result (get calls 'seon.cluster.agent/render-identity-html)]))
           [before initial] (identity-renders #(#'web-test/fetch server "/agent/root"))]
       (is (= 200 (.statusCode before)))
       (is (pos? initial))
       (support/transacted! connection
                            [{:seon.message/id "identity-cache-message" :seon.message/to [:seon.agent/id "root"] :seon.message/content "A newly connected message."}])
       (let [[after calls] (identity-renders #(#'web-test/fetch server "/agent/root"))]
         (is (= 200 (.statusCode after)))
         (is (= "A newly connected message."
                (:seon.message/content
                 (db/pull @connection [:seon.message/content]
                          [:seon.message/id "identity-cache-message"]))))
         (is (zero? calls)
             "reverse concern changes are not inputs to the scalar identity pair"))))))
