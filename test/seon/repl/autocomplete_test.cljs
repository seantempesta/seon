(ns seon.repl.autocomplete-test
  "Authority-only autocomplete projection and export tests."
  (:require
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [cljs.test :refer [async deftest is use-fixtures]]
    [clojure.string :as str]
    [seon.agent.turn :as turn]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.repl.autocomplete :as auto]))

(def ^:private fixture-dir
  (.resolve npath (str "tmp/autocomplete-test-" (.-pid js/process))))

(use-fixtures
  :once
  {:before #(do (.rmSync nfs fixture-dir #js {:recursive true :force true})
                (.mkdirSync nfs fixture-dir #js {:recursive true}))
   :after #(.rmSync nfs fixture-dir #js {:recursive true :force true})})

(def ^:private database
  {:db-name "default"
   :t 10
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"})

(defn- restore! [saved]
  (set! db/db (:db saved))
  (set! db/query (:query saved))
  (set! db/pull (:pull saved))
  (set! db/pull-many (:pull-many saved))
  (set! db/entity (:entity saved))
  (set! db/transact! (:transact saved))
  (set! turn/render-prompt (:render-prompt saved)))

(defn- saved-functions []
  {:db db/db
   :query db/query
   :pull db/pull
   :pull-many db/pull-many
   :entity db/entity
   :transact db/transact!
   :render-prompt turn/render-prompt})

(deftest context-uses-the-supplied-database-value
  (async done
    (let [original turn/render-prompt
          calls (atom [])]
      (set! turn/render-prompt
            (fn
              ([agent-id supplied-db]
               (turn/render-prompt agent-id supplied-db []))
              ([agent-id supplied-db profile]
               (swap! calls conj [agent-id supplied-db profile])
               (js/Promise.resolve
                 {:seon.render/text "bounded autocomplete context"}))))
      (-> (auto/context
            {:seon.agent/id "agent-1"
             :seon.db/db database
             :seon.agent.ctx/profile auto/context-blocks})
          (.then
            (fn [text]
              (is (= "bounded autocomplete context" text))
              (is (= [["agent-1" database auto/context-blocks]] @calls))
              (is (<= (tokens/estimate text) 700))))
          (.catch #(is false (str %)))
          (.finally #(do (set! turn/render-prompt original) (done)))))))

(deftest export-uses-rendered-transaction-and-one-application-digest
  (async done
    (let [saved (saved-functions)
          old-proc-dir (aget (.-env js/process) "SEON_PROC_DIR")
          artifact-digest (apply str (repeat 64 "a"))
          manifest-path (.resolve npath fixture-dir "artifact.edn")
          output-path (.resolve npath fixture-dir "export.json")]
      (aset (.-env js/process) "SEON_PROC_DIR" fixture-dir)
      (.writeFileSync nfs manifest-path
                      (pr-str {:seon.dev.artifact/application-digest
                               artifact-digest}))
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_request] (js/Promise.resolve database))))
      (set! db/query
            (fn
              ([{query :seon.db/query}]
               (js/Promise.resolve
                 (cond
                   (str/includes? (pr-str query) ":seon.agent.run/agent")
                   [["agent-1" 101]]

                   (str/includes? (pr-str query) ":seon.fn/sym") []
                   (str/includes? (pr-str query) ":seon.config/id") [[501]]
                   (str/includes? (pr-str query) ":seon.schema/key") []
                   :else [])))
              ([_query & _inputs] (js/Promise.resolve []))))
      (set! db/pull-many
            (fn
              ([_request] (js/Promise.resolve []))
              ([_selector _eids] (js/Promise.resolve []))
              ([_database _selector _eids]
               (js/Promise.resolve
                 [{:seon.agent.turn/id "turn-1"
                   :seon.agent.turn/rendered-tx {:db/id 7}
                   :seon.agent.turn/evals
                   [{:seon.eval/at (js/Date. 1)
                     :seon.eval/ok? true
                     :seon.eval/source "(+ 1 2)"}]}]))))
      (set! db/pull
            (fn
              ([_request] (js/Promise.resolve {}))
              ([_selector _eid] (js/Promise.resolve {}))
              ([_database _selector _eid] (js/Promise.resolve {}))))
      (set! db/entity
            (fn
              ([_eid] (js/Promise.resolve nil))
              ([_database eid]
               (js/Promise.resolve
                 (when (= 501 eid) {:db/id 501 :seon.config/id :cluster})))))
      (set! turn/render-prompt
            (fn
              ([_agent-id supplied-db]
               (js/Promise.resolve
                 {:seon.render/text (str "context-as-of-" (:as-of supplied-db))}))
              ([_agent-id supplied-db _profile]
               (js/Promise.resolve
                 {:seon.render/text (str "context-as-of-" (:as-of supplied-db))}))))
      (-> (auto/export!
            {:seon.repl.autocomplete/out-path output-path
             :seon.repl.autocomplete/projection-sha "projection"
             :seon.db/db database})
          (.then
            (fn [result]
              (is (true? (:seon.repl.autocomplete/ok? result)) (pr-str result))
              (let [manifest (js->clj
                               (js/JSON.parse (.readFileSync nfs output-path "utf8")))
                    row (first (get-in manifest ["content" "rows"]))]
                (is (= {"application_digest" artifact-digest}
                       (get-in manifest ["content" "runtime_artifact"])))
                (is (= 7 (get-in row ["db" "as-of"])))
                (is (= "context-as-of-7" (get row "context"))))))
          (.catch #(is false (str %)))
          (.finally
            (fn []
              (restore! saved)
              (if (nil? old-proc-dir)
                (js-delete (.-env js/process) "SEON_PROC_DIR")
                (aset (.-env js/process) "SEON_PROC_DIR" old-proc-dir))
              (done)))))))

(deftest rate-consumes-native-transaction-results
  (async done
    (let [saved (saved-functions)]
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_request] (js/Promise.resolve database))))
      (set! db/entity
            (fn
              ([_eid] (js/Promise.resolve {:seon.agent.turn/id "turn-1"}))
              ([_database _eid]
               (js/Promise.resolve {:seon.agent.turn/id "turn-1"}))))
      (set! db/transact!
            (fn [& requests]
              (is (= database (:seon.db/db (first requests))))
              (js/Promise.resolve {:db-before database :db-after database})))
      (-> (auto/rate! {:seon.agent.turn/id "turn-1"
                       :seon.repl.autocomplete/rating :gold})
          (.then #(is (true? (:seon.repl.autocomplete/ok? %))))
          (.catch #(is false (str %)))
          (.finally #(do (restore! saved) (done)))))))
