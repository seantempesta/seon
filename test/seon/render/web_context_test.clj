(ns seon.render.web-context-test
  "Callers derive with shared evidence while the tab delta proc is paused."
  (:require [clojure.edn]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.web]
            [seon.render.web-test :as web-test]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{'seon.render.web/the-root-page-shows-a-newly-adopted-commit-after-the-fence-revert}}
  unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot
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
                                           '#{seon.context/model-context} thunk)]
                       [result (get calls 'seon.context/model-context)]))
           [ordinary ordinary-prompts] (counted #(#'web-test/fetch server "/agent/root/debug"))
           [opened opened-prompts] (counted #(#'web-test/fetch server "/agent/root/debug?sent=true"
                                                                 {"datastar-request" "true"}))
           inspection (#'web-test/fetch server "/agent/root/debug?subject=%5B%3Aseon.agent%2Fid%20%22root%22%5D")]
       (is (= 200 (.statusCode ordinary)))
       (is (zero? ordinary-prompts) "loading the page assembles no context")
       (is (some? (web-test/element (.body ordinary) "details.seon-session-context")))
       (is (some? (web-test/element (.body ordinary) "details.seon-session-record")))
       (is (= 200 (.statusCode opened)))
       (is (= 1 opened-prompts) "opening the view assembles the context once")
       (is (some? (web-test/element (.body opened) ".seon-data-panel")) "the value printer's panel shows it")
       (is (= 200 (.statusCode inspection)))
       (is (str/includes? (.body inspection) "debug-units"))
       (is (not (str/includes? (.body inspection) "Context now")))
       (is (not (str/includes? (.body inspection) "debug-ai-")))))))

(deftest a-context-view-string-cut-links-to-its-next-page-with-the-basis-and-text-answers-plain
  (#'web-test/with-server
   (fn [connection server _context]
     (#'web-test/open-run! connection "long-context")
     (support/transacted! connection
                          [{:seon.cluster.eval/id "long-context-e0"
                            :seon.cluster.eval/at (java.util.Date. 0)
                            :seon.cluster.eval/run [:seon.turn/id "long-context"]
                            :seon.cluster.eval/ordinal 0
                            :seon.cluster.eval/source (str "(count \"" (str/join " " (repeat 20000 "word")) "\")")}])
     (let [datastar {"datastar-request" "true"}
           opened (#'web-test/fetch server "/agent/root/debug?sent=true" datastar)
           hrefs (map #(.attr ^org.jsoup.nodes.Element % "href")
                      (.select (web-test/dom (.body opened)) "a.seon-print-whole-text"))
           href (first (filter #(str/includes? % "text=1") hrefs))
           whole (#'web-test/fetch server (str/replace href #"^.*/agent/" "/agent/") datastar)]
       (is (some? (some #(re-find #"offset=[1-9]\d*&amp;basis=\d+|offset=[1-9]\d*&basis=\d+" %)
                        (map #(.attr ^org.jsoup.nodes.Element % "href")
                             (.select (web-test/dom (.body opened)) "a.seon-print-elision"))))
           "a string cut links to its next page at the view's basis")
       (is (some? href) "a cut string offers its whole text")
       (is (re-find #"basis=\d+" href) "the link keeps the basis the view was derived at")
       (is (= 200 (.statusCode whole)))
       (is (= ["text/plain"] (map #(first (str/split % #";")) (.allValues (.headers whole) "content-type")))
           "text=1 answers text/plain")
       (is (pos? (count (.body whole))))))))

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
