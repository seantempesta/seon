(ns seon.render.retained-test
  (:require [clojure.core.async.flow :as flow]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render-simplification-test :as simplification]
            [seon.render.web-test :as web-test]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(defn- seed-shown! [connection namespace-name shown]
  (let [report (db/transact! connection
                 (into
                 [{:seon.ns/name namespace-name :seon.ns/doc "Retained renderer fixture"}]
                 (concat
                 ;; The agent through its canonical creation writer.
                 (support/agent-tx (db/db connection) "retained" namespace-name)
                 [{:seon.turn/id "retained-turn" :seon.turn/agent [:seon.agent/id "retained"]
                   :seon.turn/opened-tx "datomic.tx"}
                  {:seon.cluster.eval/id "retained-shown"
                   :seon.cluster.eval/run [:seon.turn/id "retained-turn"]
                   :seon.cluster.eval/ordinal 0 :seon.cluster.eval/at (java.util.Date.)
                   :seon.eval/shown shown}])))]
    (is (:db-after report) (pr-str (select-keys report [:seon.error/operation :seon.error/message])))))

(defn- declare-subject!
  "Fixture A's declared subject, whose AI pair names its namespace-ai renderer."
  [connection]
  (simplification/declare-subject!
   connection 'seon.render-simplification.fixture-a
   {:seon.render/ai 'seon.render-simplification.fixture-a/namespace-ai}))

(defn- replace-renderer! [connection ctx form]
  (let [report (db/transact! connection
                 [(support/revision (db/db connection)
                                    [:seon.fn/sym 'seon.render-simplification.fixture-a/namespace-ai]
                                    {:seon.fn/source (pr-str form)})])]
    (is (:db-after report) (pr-str (select-keys report [:seon.error/operation :seon.error/message]))))
  (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                     :seon.schema/projection (kernel/context-projection ctx)})
  ;; The indexed fixture is compiled core; install the same authored form in
  ;; this private SCI fork as well as carrying its acquired program evidence.
  (sci/binding [sci/ns (sci/create-ns 'seon.render-simplification.fixture-a)]
    (sci/eval-form ctx form)))

(deftest equal-committed-database-skips-read-replay
  (support/with-database
   (fn [connection]
     (let [namespace-name 'seon.render-simplification.fixture-a
           _ (seed-shown! connection namespace-name "one")
           _ (declare-subject! connection)
           ctx (support/fork-cluster-ctx connection)
           profile (render/agent-render-profile config/defaults)
           caps (config/result-caps config/defaults)
           calls (atom {})
           call-id [::namespace]
           request (fn []
                     {:seon.db/db (db/db connection)
                      :seon.sci.eval/ctx ctx
                      :seon.render.call/function (symbol (str namespace-name) "subject")
                      :seon.render/value {:seon.ns/name namespace-name}
                      :seon.render/output :seon.render/ai
                      :seon.render/profile profile
                      :seon.render.call/id call-id
                      :seon.render/retained-calls @calls
                      :seon.render/captured-calls calls
                      :seon.render/candidate-call-ids #{call-id}
                      :seon.sci.admit/caps caps
                      :seon.sci.eval/time-limit-ms 2000})]
       (sci/binding [sci/ns (sci/create-ns namespace-name)]
         (replace-renderer!
          connection ctx
          '(defn namespace-ai [value]
             (seon.db/q '[:find ?shown .
                          :where [?evaluation :seon.cluster.eval/id "retained-shown"]
                                 [?evaluation :seon.eval/shown ?shown]]))))
       (let [a (:seon.db/db (request)) b (:seon.db/db (request))]
         (is (some? (db/carried-projection a)))
         (is (not (identical? a b)))
         (is (= a b))
         (is (render/same-committed-database? a b))
         (is (not (render/same-committed-database? a (db/history b))))
         (is (not (render/same-committed-database? a (db/since b (:max-tx b))))))
       (is (= "one" (render/render-call (request))))
       (is (seq (:seon.render.call/read-evidence (get @calls call-id))))
       ;; The armed per-Var counters observe the checks and invocations; no
       ;; Var is redefined.
       (let [checked (fn [thunk] (let [[result calls] (support/armed-calls '#{seon.db/read-evidence-current?} thunk)]
                                   [result (get calls 'seon.db/read-evidence-current?)]))
             [shown checks] (checked #(render/render-call (request)))]
         (is (= "one" shown))
         (is (zero? checks) "equal committed wrappers must not replay reads")
         (let [report (db/transact! connection [[:db/add [:seon.cluster.eval/id "retained-shown"]
                                                :seon.eval/shown "two"]])]
           (is (:db-after report) (pr-str (select-keys report [:seon.error/operation :seon.error/message]))))
         (let [[shown checks] (checked #(render/render-call (request)))]
           (is (= "two" shown))
           (is (pos? checks) "a changed database still validates dependencies"))
         (support/transacted! connection [[:db/add [:seon.ns/name namespace-name]
                                           :seon.ns/doc "Updated fixture documentation"]])
         (let [[shown calls] (support/armed-calls '#{seon.sci.kernel/invoke} #(render/render-call (request)))]
           (is (= "two" shown))
           (is (zero? (get calls 'seon.sci.kernel/invoke)) "an unrelated fact is not a renderer input"))
         (sci/binding [sci/ns (sci/create-ns namespace-name)]
           (replace-renderer! connection ctx '(defn namespace-ai [_] "changed renderer")))
         (is (= "changed renderer" (render/render-call (request)))
             "redefining the selected renderer invalidates its program identity")
         (sci/binding [sci/ns (sci/create-ns namespace-name)]
           (replace-renderer! connection ctx '(defn namespace-ai [_] nil)))
         (let [refusal (render/render-call (request))]
           (is (= :ai (:seon.render/invalid-output refusal)))
           (is (= 'seon.render/raw-output (:seon.error/operation refusal)))
           (is (nil? (:seon.error/offending refusal))))
         (sci/binding [sci/ns (sci/create-ns namespace-name)]
           (replace-renderer! connection ctx '(defn namespace-ai [value]
                                (seon.db/q '[:find ?shown .
                                             :where [?e :seon.cluster.eval/id "retained-shown"]
                                                    [?e :seon.eval/shown ?shown]]))))
         (support/with-database
          (fn [other]
            (seed-shown! other namespace-name "other connection")
            (declare-subject! other)
            ;; The renderer is the program of the database rendered from: the
            ;; other branch carries the same query renderer as its own entity.
            (sci/binding [sci/ns (sci/create-ns namespace-name)]
              (replace-renderer! other ctx '(defn namespace-ai [value]
                                   (seon.db/q '[:find ?shown .
                                                :where [?e :seon.cluster.eval/id "retained-shown"]
                                                       [?e :seon.eval/shown ?shown]]))))
            (is (not (render/same-committed-database? (db/db connection) (db/db other))))
            (is (= "other connection"
                   (render/render-call (assoc (request) :seon.db/db (db/db other))))))))))))

(deftest adoption-of-an-unrelated-namespace-re-renders-zero-evaluations
  (#'web-test/with-server
   (fn [connection _server context]
     (flow/pause (:graph context))
     (is (:db-after
          (db/transact! connection
            (into [{:seon.turn/id "retained-adoption"
                    :seon.turn/agent [:seon.agent/id "root"]
                    :seon.turn/opened-tx "datomic.tx"}
                   {:seon.agent/id "root"
                    :seon.agent/runtime
                    {:seon.runtime/agent [:seon.agent/id "root"]
                     :seon.runtime/turns [[:seon.turn/id "retained-adoption"]]}}]
                  (for [ordinal (range 2)]
                    {:seon.cluster.eval/id (str "retained-adoption-" ordinal)
                     :seon.cluster.eval/run [:seon.turn/id "retained-adoption"]
                     :seon.cluster.eval/ordinal ordinal
                     :seon.cluster.eval/at (java.util.Date.)
                     :seon.cluster.eval/source (str ordinal)
                     :seon.eval/shown (str ordinal)})))))
     (let [ctx (:ctx context)
           request (fn []
                     {:seon.db/db (db/db connection) :seon.db/connection connection
                      :seon.agent/id "root" :seon.sci.eval/ctx ctx
                      :seon.sci.admit/caps (config/result-caps config/defaults)
                      :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                      :seon.render/profile (render/agent-render-profile config/defaults)})
           ;; The render pair's armed counters (host and SCI context) observe
           ;; its invocations; no Var is redefined.
           renders (fn [thunk] (let [[result calls] (support/armed-calls '#{seon.repl/render-ai} thunk)]
                                 [result (get calls 'seon.repl/render-ai)]))
           [before calls] (renders #(render/acquire-context! (request)))
           snapshot @(:seon.sci.kernel/program-snapshot ctx)
           namespace-entity (db/pull (db/db connection) [:seon.ns/source]
                                     [:seon.ns/name 'seon.schedule])]
       (is (string? (:seon.cluster.prompt/text before)) (pr-str before))
       (is (= 2 calls) "both saved evaluations use the real SCI render pair")
       (is (string? (:seon.ns/source namespace-entity)))
       (is (:db-after
            (db/transact! connection
              [[:db/add [:seon.ns/name 'seon.schedule]
                :seon.ns/source (str (:seon.ns/source namespace-entity) "\n")]
               [:db/add [:seon.cluster/name "web-test"]
                :seon.source/commit-id #uuid "f54229d7-54eb-472d-9ae8-917a0f97af71"]])))
       ;; The same acquisition owner used by development adoption replaces
       ;; the program snapshot; a stamp-only test would miss this defect.
       (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                          :seon.schema/projection (kernel/context-projection ctx)})
       (is (not (identical? snapshot @(:seon.sci.kernel/program-snapshot ctx))))
       (let [[again calls] (renders #(render/acquire-context! (request)))]
         (is (= (:seon.cluster.prompt/text before) (:seon.cluster.prompt/text again)))
         (is (zero? calls) "unrelated adopted source re-renders zero evaluations"))
       (is (:db-after
            (db/transact! connection [[:db/add [:seon.cluster.eval/id "retained-adoption-0"]
                                       :seon.eval/shown "changed shown text"]])))
       (let [[after calls] (renders #(render/acquire-context! (request)))]
         (is (not= (:seon.cluster.prompt/text before) (:seon.cluster.prompt/text after)))
         (is (= 1 calls) "only the evaluation with changed shown text re-renders"))
       (let [renderer (db/pull (db/db connection) [:seon.fn/source]
                               [:seon.fn/sym 'seon.repl/render-ai])]
         (is (string? (:seon.fn/source renderer)))
         (is (:db-after
              (db/transact! connection [(support/revision (db/db connection) [:seon.fn/sym 'seon.repl/render-ai]
                                                           {:seon.fn/source (str (:seon.fn/source renderer) "\n")})])))
         (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                            :seon.schema/projection (kernel/context-projection ctx)})
         (is (= 2 (second (renders #(render/acquire-context! (request)))))
             "a changed render pair invalidates both evaluations"))
       (is (= 2 (second (renders #(render/acquire-context!
                                   (update-in (request) [:seon.render/profile :seon.render.profile/token-budget] inc)))))
           "the render profile remains an invocation input")))))
