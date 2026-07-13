(ns seon.repl.autocomplete-test
  "The repl-autosuggest A1 contract (docs/prds/repl-autosuggest/design.md):

     1. PROJECTION DETERMINISM — `context` is a pure function of the db
        VALUE: rendered twice over the same `(db/as-of db rendered-as-of)`
        it is byte-identical, shows the database BEFORE the turn (the prior
        turn's eval, not the turn's own), and fits the ~700-token budget.
     2. EXPORT ROW SHAPE — `export!` writes one JSONL row per ok-eval
        turn: context/cards/target/meta, target = the turn's ok sources
        in order, meta carries turn-id/agent/basis-t/store/projection-sha.
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
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.autocomplete :as auto]
    [seon.test-seed :as test-seed]))

;; ---------------------------------------------------------------------------
;; Fixtures — pid-scoped blob + export dirs, fresh conn per test.
;; ---------------------------------------------------------------------------

(def ^:private fixture-dir
  (.resolve npath (str "tmp/autocomplete-test-" (.-pid js/process))))

(defonce ^:private !saved-dir (atom nil))

(use-fixtures :once
  {:before (fn []
             (reset! !saved-dir @blob/!dir)
             (reset! blob/!dir (str fixture-dir "/blobs"))
             (.rmSync nfs fixture-dir #js {:recursive true :force true}))
   :after  (fn []
             (reset! blob/!dir @!saved-dir)
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
  (-> (with-conn chain)
      (.then (fn [_] (done)))
      (.catch (fn [e] (is false (str "threw — " e)) (done)))))

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
    (run-test
      (fn ^:async run []
        (let [a    (await (fresh-agent!))
              aid  (:seon.agent/id a)
              _    (await (drive-turn! a "(+ 11 22)\n"))
              t2   (await (drive-turn! a "(* 3 4)\n"))
              t    (:seon.agent.turn/rendered-as-of t2)
              aodb (db/as-of @db/*conn* t)
              c1   (auto/context {:seon.agent/id aid :seon.db/db aodb})
              c2   (auto/context {:seon.agent/id aid :seon.db/db aodb})]
          (is (= c1 c2)
              "byte-identical over the same as-of db value — the whole point")
          (is (not (str/blank? c1)) "the projection renders")
          (is (str/includes? c1 "(+ 11 22)")
              "the PREVIOUS turn's eval source is the recent tail")
          (is (not (str/includes? c1 "(* 3 4)"))
              "the turn's OWN forms are absent — this is the PRE-turn database")
          (is (not (str/includes? c1 "=> "))
              "no live readline — the one moving line is off in this profile")
          (is (not (str/includes? c1 "result/"))
              "no result/<id> handles — process-identity bytes are off")
          (is (<= (tokens/estimate c1) 700)
              (str "fits the encoder budget — got " (tokens/estimate c1)
                   " tokens"))
          ;; live-db render agrees with as-of render at the same basis
          (let [live (auto/context {:seon.agent/id aid
                                    :seon.db/db (db/as-of @db/*conn* t)})]
            (is (= c1 live)
                "re-deriving the same as-of value reproduces the bytes"))))
      done)))

;; ---------------------------------------------------------------------------
;; 1b. DEFAULT-path parity (owner ruling 2026-07-12: context generation is
;;     FROZEN — a profile-absent render must be byte-identical to today).
;;     Structural guards: the profile dials must NOT leak into the default
;;     render (readline present, result/<id> handles present), and two
;;     default renders over the SAME frozen db differ ONLY in the readline's
;;     live `now` line. The old-code-vs-new-code byte proof runs LIVE
;;     against stored prompt blobs (acme verification).
;; ---------------------------------------------------------------------------

(defn- strip-readline-now
  "Drop the one legitimate live-`now` readline status line."
  [s]
  (->> (str/split-lines s)
       (remove #(re-find #" · turn \d+ · loop \d+/" %))
       (str/join "\n")))

(deftest default-render-keeps-todays-bytes
  (async done
    (run-test
      (fn ^:async run []
        (let [a   (await (fresh-agent!))
              aid (:seon.agent/id a)
              _   (await (drive-turn! a "(+ 40 2)\n"))
              db  @db/*conn*
              d1  (seon.agent.ctx/render-context {:seon.agent/id aid :seon.db/db db})
              d2  (seon.agent.ctx/render-context {:seon.agent/id aid :seon.db/db db})]
          (is (str/includes? d1 "=> ")
              "the DEFAULT render keeps its live readline cursor")
          (is (str/includes? d1 "result/")
              "the DEFAULT render keeps its result/<id> handles")
          (is (= (strip-readline-now d1) (strip-readline-now d2))
              "two default renders over one frozen db differ only in the readline now")))
      done)))

;; ---------------------------------------------------------------------------
;; 2 + 3. Export row shape + curation.
;; ---------------------------------------------------------------------------

(defn- read-rows [path]
  (->> (str/split-lines (.readFileSync nfs path "utf8"))
       (remove str/blank?)
       (mapv #(js->clj (js/JSON.parse %)))))

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
              out  (str fixture-dir "/rows.jsonl")
              res  (auto/export! {:seon.repl.autocomplete/out-path out
                                  :seon.repl.autocomplete/projection-sha "test-sha"
                                  :seon.db/db @db/*conn*})]
          (is (true? (:seon.repl.autocomplete/ok? res)))
          (is (= 2 (:seon.repl.autocomplete/rows res))
              "both ok-eval turns row out")
          (let [rows (read-rows out)
                r2   (some #(when (str/includes? (get % "target") "db/query") %)
                           rows)]
            (is (= 2 (count rows)))
            (is (some? r2) "the db/query turn produced a row")
            (is (every? #(and (contains? % "context") (contains? % "cards")
                              (contains? % "target") (contains? % "meta"))
                        rows)
                "every row carries the design.md shape")
            (is (= (:seon.agent.turn/rendered-as-of t2)
                   (get-in r2 ["meta" "basis-t"]))
                "meta basis-t IS the turn's rendered-as-of")
            (is (= aid (get-in r2 ["meta" "agent"])))
            (is (= "test-sha" (get-in r2 ["meta" "projection-sha"])))
            (is (= (:seon.agent.turn/id t2) (get-in r2 ["meta" "turn-id"])))
            (is (number? (get-in r2 ["meta" "coverage"]))
                "per-row ingredients coverage rides the meta")
            (is (str/includes? (get r2 "context" "") "(+ 11 22)")
                "the row's context is the PRE-turn projection (prior turn visible)")
            (is (some #(str/includes? % "seon.db/query") (get r2 "cards"))
                "the called fn's compact card rides the row"))
          ;; curation: exclude t1, gold t2, re-export
          (let [r-ex (await (auto/rate! {:seon.agent.turn/id (:seon.agent.turn/id t1)
                                         :seon.repl.autocomplete/rating :excluded}))
                r-au (await (auto/rate! {:seon.agent.turn/id (:seon.agent.turn/id t2)
                                         :seon.repl.autocomplete/rating :gold}))
                res2 (auto/export! {:seon.repl.autocomplete/out-path out
                                    :seon.repl.autocomplete/projection-sha "test-sha"
                                    :seon.db/db @db/*conn*})
                rows2 (read-rows out)]
            (is (true? (:seon.repl.autocomplete/ok? r-ex)))
            (is (true? (:seon.repl.autocomplete/ok? r-au)))
            (is (= 1 (:seon.repl.autocomplete/skipped-excluded res2))
                ":excluded turns drop out")
            (is (= 1 (count rows2)))
            (is (= "gold" (get-in (first rows2) ["meta" "rating"]))
                "a rating rides the row's meta"))))
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
