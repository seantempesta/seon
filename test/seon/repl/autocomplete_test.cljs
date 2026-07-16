(ns seon.repl.autocomplete-test
  "The repl-autosuggest A1 contract (docs/prds/repl-autosuggest/design.md):

     1. PROJECTION DETERMINISM — `context` is a pure function of the db
        VALUE: rendered twice over the same exact resolved coordinate
        it is byte-identical, shows the database BEFORE the turn (the prior
        turn's eval, not the turn's own), and fits the ~700-token budget.
     2. EXPORT MANIFEST — `export!` writes one content-addressed manifest with
        stable row ids/splits, complete coordinates, source/runtime/config/
        profile identities, and referenced-schema closures emitted once.
     3. CURATION — `rate!` upserts `::rating` onto a real turn (an unknown
        id is refused as a value); `:excluded` turns drop out of the
        export; a rating rides the row's meta.

   Hermetic: fresh :memory conn root-set! as db/*conn* per test (set!,
   not binding — CLJS dynamic bindings don't survive await), blobs to a
   pid-scoped tmp dir, stub llm-fn (no live LLM)."
  (:require
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [cljs.test :refer [deftest is async use-fixtures]]
    [clojure.string :as str]
    [my.blob :as blob]
    [seon.agent :as agent]
    [seon.agent.home :as home]
    [seon.agent.ctx :as ctx]
    [seon.agent.run :as run]
    [seon.agent.turn :as turn]
    [seon.ai.tokens :as tokens]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.coordinate :as coordinate]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.autocomplete :as auto]
    [seon.test-seed :as test-seed]))

;; ---------------------------------------------------------------------------
;; Fixtures — pid-scoped blob + export dirs, fresh conn per test.
;; ---------------------------------------------------------------------------

(def ^:private fixture-dir
  (.resolve npath (str "tmp/autocomplete-test-" (.-pid js/process))))

(defonce ^:private !saved-storage-view (atom nil))

(use-fixtures :once
  {:before (fn []
             (reset! !saved-storage-view @blob/!storage-view)
             (reset! blob/!storage-view
                     {:my.blob/writable-dir (str fixture-dir "/blobs")
                      :my.blob/read-only-dirs []})
             (.rmSync nfs fixture-dir #js {:recursive true :force true}))
   :after  (fn []
             (reset! blob/!storage-view @!saved-storage-view)
             (.rmSync nfs fixture-dir #js {:recursive true :force true}))})

(defn- with-conn
  "Fresh schema-loaded :memory conn as the ROOT db/*conn*, run `body`
   (0-arg, may return a Promise), restore the prior conn after."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (db/transact! {:seon.db/tx-data (test-seed/my-core-rows)})
                     (.then (fn [_] (body)))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(defn- run-test [chain done]
  (let [original turn/render-prompt]
    (set! turn/render-prompt
          (fn
            ([agent-id point]
             (original agent-id point))
            ([_agent-id point _profile]
             (js/Promise.resolve
               {:seon.render/text (str "autocomplete context at "
                                       (::coordinate/t point))
                :seon.ai/system-prompt "system"}))))
    (-> (with-conn chain)
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done)))
        (.finally (fn [] (set! turn/render-prompt original))))))

(defn ^:async fresh-agent!
  "Create a booted agent + ONE open run on the current conn.

   The run matters: `agent-turns` (the exporter's spine and the
   transcript's) walks agent → runs → turns, so a runless turn is
   invisible — the live loop always drives under a run, and these
   tests must too (the turn-capture test's rung-1 lesson)."
  []
  (let [cs     (await (repl/ensure-bootstrap!))
        minted (await (agent/mint! {}))
        aid    (:seon.agent/id minted)]
    (await (db/with-agent aid
             (fn ^:async boot []
               (await (seval/setup-agent-ns! cs (home/home-ns aid) aid)))))
    (let [r (await (run/open-run! {:seon.agent/id aid
                                   :seon.agent.run/trigger :message}))]
      {:seon.agent/id aid :seon.agent/compile-state cs
       :seon.agent.run/id (:seon.agent.run/id r)})))

(defn ^:async drive-turn!
  "One real run-turn! for the agent with a stub llm reply; resolves to the turn."
  [{aid :seon.agent/id cs :seon.agent/compile-state rid :seon.agent.run/id} reply]
  (await (db/with-agent aid
           (fn []
             (turn/run-turn! {:seon.agent/id            aid
                              :seon.agent/llm-fn        (fn [_p] (js/Promise.resolve {:text reply}))
                              :seon.agent/compile-state cs
                              :seon.agent.run/id        rid})))))

;; ---------------------------------------------------------------------------
;; 1. Projection determinism + as-of exactness + budget.
;; ---------------------------------------------------------------------------

(deftest context-is-deterministic-as-of-exact-and-bounded
  (async done
    (let [original turn/render-prompt
          calls (atom [])
          point {::coordinate/database-id
                 #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                 ::coordinate/branch :db
                 ::coordinate/commit-id
                 #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
                 ::coordinate/t 42}]
      (set! turn/render-prompt
            (fn [agent-id coordinate profile]
              (swap! calls conj [agent-id coordinate profile])
              (js/Promise.resolve
                {:seon.render/text "bounded autocomplete context"
                 :seon.ai/system-prompt "system"})))
      (-> (js/Promise.all
            #js [(auto/context
                   {:seon.agent/id "agent-1"
                    :seon.db.coordinate/coordinate point
                    :seon.agent.ctx/profile auto/context-blocks})
                 (auto/context
                   {:seon.agent/id "agent-1"
                    :seon.db.coordinate/coordinate point
                    :seon.agent.ctx/profile auto/context-blocks})])
          (.then
            (fn [results]
              (let [c1 (aget results 0)
                    c2 (aget results 1)]
                (is (= c1 c2))
                (is (= 2 (count @calls)))
                (is (every? #(= ["agent-1" point auto/context-blocks] %)
                            @calls))
                (is (<= (tokens/estimate c1) 700)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (set! turn/render-prompt original)
              (done)))))))

;; ---------------------------------------------------------------------------
;; 2 + 3. Export row shape + curation.
;; ---------------------------------------------------------------------------

(defn- read-manifest [path]
  (js->clj (js/JSON.parse (.readFileSync nfs path "utf8"))))

(defn- manifest-rows [manifest]
  (get-in manifest ["content" "rows"]))

(deftest export-rows-shape-target-meta-and-curation
  (async done
    (run-test
      (fn ^:async run []
        ;; a program-graph row so a CALLED fn resolves to a card
        (await (db/transact!
                 {:seon.db/tx-data
                  [{:seon.fn/sym      "seon.db/query"
                    :seon.fn/doc      "Run a Datalog query against the db."
                    :seon.fn/arglists "([request])"}]}))
        (let [a    (await (fresh-agent!))
              aid  (:seon.agent/id a)
              t1   (await (drive-turn! a "(+ 11 22)\n"))
              t2   (await (drive-turn!
                            a "(db/query {:seon.db/query '[:find ?id :where [_ :seon.agent/id ?id]]})\n"))
              out  (str fixture-dir "/manifest.json")
              res  (await
                     (auto/export! {:seon.repl.autocomplete/out-path out
                                    :seon.repl.autocomplete/projection-sha "test-sha"
                                    :seon.db/db @db/*conn*}))]
          (is (true? (:seon.repl.autocomplete/ok? res)) (pr-str res))
          (is (= 2 (:seon.repl.autocomplete/rows res))
              "both ok-eval turns row out")
          (let [manifest (read-manifest out)
                rows (manifest-rows manifest)
                r2   (some #(when (str/includes? (get % "target") "db/query") %)
                           rows)]
            (is (= (:seon.repl.autocomplete/manifest-id res)
                   (get manifest "manifest_id")))
            (is (= 64 (count (get manifest "manifest_id"))))
            (is (= "seon.autocomplete.export/v1"
                   (get-in manifest ["content" "format"])))
            (is (= 2 (count rows)))
            (is (some? r2) "the db/query turn produced a row")
            (is (every? #(and (contains? % "context") (contains? % "cards")
                              (contains? % "target") (contains? % "row_id")
                              (contains? % "split"))
                        rows)
                "every row carries stable identity and split assignment")
            (let [point (turn/rendered-coordinate t2)]
              (is (= (str (::coordinate/database-id point))
                     (get-in r2 ["coordinate" "database_id"])))
              (is (= (name (::coordinate/branch point))
                     (get-in r2 ["coordinate" "branch"])))
              (is (= (str (::coordinate/commit-id point))
                     (get-in r2 ["coordinate" "commit_id"])))
              (is (= (::coordinate/t point)
                     (get-in r2 ["coordinate" "t"]))))
            (is (= aid (get r2 "agent")))
            (is (= "test-sha"
                   (get-in manifest ["content" "source" "projection_sha"])))
            (is (= (:seon.agent.turn/id t2) (get r2 "turn_id")))
            (is (= "observed" (get r2 "projection_mode")))
            (is (number? (get r2 "coverage")))
            (is (some #(= (get r2 "schema_closure_id") (get % "id"))
                      (get-in manifest ["content" "schema_closures"])))
            (is (map? (get-in manifest ["content" "runtime_artifact"]))
                "one runtime artifact identity is manifest-wide")
            (is (some #(str/includes? % "seon.db/query") (get r2 "cards"))
                "the called fn's compact card rides the row")
            (let [bytes-1 (.readFileSync nfs out "utf8")
                  res-repeat (await
                               (auto/export!
                                 {:seon.repl.autocomplete/out-path out
                                  :seon.repl.autocomplete/projection-sha "test-sha"
                                  :seon.db/db @db/*conn*}))]
              (is (= (:seon.repl.autocomplete/manifest-id res)
                     (:seon.repl.autocomplete/manifest-id res-repeat)))
              (is (= bytes-1 (.readFileSync nfs out "utf8"))
                  "same basis/source/runtime world is byte-identical")))
          ;; curation: exclude t1, gold t2, re-export
          (let [r-ex (await (auto/rate! {:seon.agent.turn/id (:seon.agent.turn/id t1)
                                         :seon.repl.autocomplete/rating :excluded}))
                r-au (await (auto/rate! {:seon.agent.turn/id (:seon.agent.turn/id t2)
                                         :seon.repl.autocomplete/rating :gold}))
                res2 (await
                       (auto/export! {:seon.repl.autocomplete/out-path out
                                      :seon.repl.autocomplete/projection-sha "test-sha"
                                      :seon.db/db @db/*conn*}))
                manifest2 (read-manifest out)
                rows2 (manifest-rows manifest2)
                rejections2 (get-in manifest2 ["content" "rejections"])]
            (is (true? (:seon.repl.autocomplete/ok? r-ex)))
            (is (true? (:seon.repl.autocomplete/ok? r-au)))
            (is (= 1 (:seon.repl.autocomplete/skipped-excluded res2))
                ":excluded turns drop out")
            (is (= 1 (count rows2)))
            (is (= "gold" (get (first rows2) "rating"))
                "a rating rides the row")
            (is (= "excluded-rating" (get (first rejections2) "reason")))
            (is (= 64 (count (get (first rejections2) "rejection_id")))
                "excluded evidence is retained with an addressable id"))))
      done)))

(deftest rate-of-unknown-turn-is-a-refusal-value
  (async done
    (run-test
      (fn ^:async run []
        (let [r (await (auto/rate! {:seon.agent.turn/id "turnunknown1"
                                    :seon.repl.autocomplete/rating :gold}))]
          (is (false? (:seon.repl.autocomplete/ok? r)))
          (is (str/includes? (str (:seon.repl.autocomplete/error r)) "no turn")
              "unknown id is a guiding value, never a throw")))
      done)))
