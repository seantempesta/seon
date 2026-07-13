(ns seon.needle-lora-audit-test
  "REPL-eval audit of the LoRA training pairs (repl-autosuggest lane).

   Gated on SEON_LORA_AUDIT (like the gym battery gate) — a no-op in a
   normal suite run. NOT part of the main-tree classpath: this file is
   committed at src-needle/audit/seon/needle_lora_audit_test.cljs and
   COPIED into the PINNED worktree's test/seon/ before compiling its
   :test build (pin sha 93c8d8ad — the APIs below are the pin's).

   Per manifest row (src-needle/scripts/lora_audit_manifest.py), the
   pair runs through the LIVE pipeline, the gym's stub mechanism:
     1. fresh isolated :memory conn (seon.client/open-agent-conn!)
     2. the pair's agent (setup-agent-ns! + agent/create!, the gym's
        boot order) + situation-implied peer agents
     3. seed-core! (THE user entity), the plan-block tx, open-run!
     4. ECHO TURN — run-turn! with a scripted llm-fn replying the
        situation's own transcript echoes (its claimed history)
     5. TARGET TURN — run-turn! replying the raw target text, so the
        REAL reader/segmentation/eval-batch!/record path judges it
     6. read back each turn's :seon.eval rows + live values
        (seon.eval/lookup-result) for envelope verdicts
   Results stream to SEON_LORA_AUDIT_OUT as JSONL; classification
   happens in lora_audit_report.py.

   Run (from the pin):
     cp .../src-needle/audit/seon/needle_lora_audit_test.cljs test/seon/
     clj -M:cljs compile test && bb bin/fix-bootstrap-macros
     SEON_LORA_AUDIT=1 SEON_CONFIG=config/test.edn \\
     SEON_LORA_AUDIT_MANIFEST=/abs/audit-manifest.jsonl \\
     SEON_LORA_AUDIT_OUT=/abs/audit-results.jsonl \\
     node out/test/test.js"
  (:require
    [cljs.reader :as reader]
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.agent :as agent]
    [seon.agent.fs :as sfs]
    [seon.agent.run :as run]
    [seon.agent.turn :as turn]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.instrument :as instrument]
    [seon.log :as slog]
    [seon.repl :as repl]
    [seon.schema :as schema]))

(slog/quiet-library-logs!)

(defn- env [k] (aget (.. js/process -env) k))
(defn- gate-set? [] (boolean (seq (str (or (env "SEON_LORA_AUDIT") "")))))

(def ^:private fs-mod (js/require "node:fs"))

(defn- truncate [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- safe-pr [v]
  (try (truncate (pr-str v) 300)
       (catch :default e (str "<unprintable: " (.-message e) ">"))))

(defn- envelope-fail
  "When `v` is a capability-verb envelope with any `*/ok?` key = false,
   return its error string; else nil. The REPL-proven standard: an eval
   can succeed while the verb REFUSED (errors are values)."
  [v]
  (when (map? v)
    (when (some (fn [[k kv]]
                  (and (keyword? k) (= "ok?" (name k)) (false? kv)))
                v)
      (or (some (fn [[k kv]]
                  (when (and (keyword? k)
                             (contains? #{"error" "message"} (name k))
                             (some? kv))
                    (safe-pr kv)))
                v)
          "ok? false (no error key)"))))

(defn- scripted-llm
  "The gym's stub llm-fn: resolve with exactly the given reply text."
  [text]
  (fn [_ctx] (js/Promise.resolve {:text text})))

(defn- latest-turn-eid
  "Eid of the newest turn on `run-id` in `dbv` (nil when none)."
  [dbv run-id]
  (some->> (db/query {:seon.db/query '[:find ?t
                                       :in $ ?rid
                                       :where
                                       [?r :seon.agent.run/id ?rid]
                                       [?t :seon.agent.turn/run ?r]]
                      :seon.db/args [run-id]
                      :seon.db/db dbv})
           (map first) seq (apply max)))

(defn- turn-eval-rows
  "The turn's :seon.eval rows, eid-ordered:
   [{eid, id, src, ok?, output}]."
  [dbv turn-eid]
  (->> (db/query {:seon.db/query '[:find ?ev ?id ?src ?ok ?err ?res
                                   :in $ ?t
                                   :where
                                   [?t :seon.agent.turn/evals ?ev]
                                   [?ev :seon.eval/id ?id]
                                   [?ev :seon.eval/source ?src]
                                   [?ev :seon.eval/ok? ?ok]
                                   [(get-else $ ?ev :seon.eval/error "") ?err]
                                   [(get-else $ ?ev :seon.eval/result-edn "") ?res]]
                  :seon.db/args [turn-eid]
                  :seon.db/db dbv})
       (sort-by first)
       (mapv (fn [[eid id src ok err res]]
               {:eid eid :id id :src src :ok ok :err err :res res}))))

(defn ^:async drive-turn!
  "One scripted turn through the LIVE pipeline; return js eval verdicts."
  [compile-state agent-id run-id reply-text]
  (await
    (db/with-agent agent-id
      (fn ^:async drive* []
        (await (turn/run-turn! {:seon.agent/id            agent-id
                                :seon.agent/llm-fn        (scripted-llm reply-text)
                                :seon.agent/compile-state compile-state
                                :seon.agent.run/id        run-id})))))
  (let [dbv @db/*conn*
        tid (latest-turn-eid dbv run-id)
        rows (if tid (turn-eval-rows dbv tid) [])
        out #js []]
    (doseq [{:keys [id src ok err res]} rows]
      (let [v (when ok (seval/lookup-result id))
            env-err (when ok (envelope-fail v))]
        (.push out #js {:src (truncate src 240)
                        :ok ok
                        :err (when-not ok (truncate err 300))
                        :val (when ok (or (safe-pr v) (truncate res 300)))
                        :envFail (some? env-err)
                        :envErr (when env-err (truncate env-err 300))})))
    out))

(defn ^:async boot-agent!
  "The gym's boot order: home ns first, then the entity (no turn 0)."
  [compile-state agent-id]
  (await
    (db/with-agent agent-id
      (fn ^:async boot* []
        (await (seval/setup-agent-ns! compile-state
                                      (agent/home-ns agent-id)
                                      agent-id))
        (await (agent/create! {:seon.agent/id agent-id}))))))

(defn ^:async audit-pair!
  "Stage one manifest row's world hermetically and drive its two turns.
   Returns a js result object; restores db/*conn* + the schema registry."
  [compile-state baseline-schemas row]
  (let [prev db/*conn*
        conn (await (client/open-agent-conn!))
        _ (set! db/*conn* conn)
        agent-id (unchecked-get row "agent")
        staging  #js []
        stage!   (fn [step res] (.push staging #js {:step step :res res}))]
    (try
      (await (boot-agent! compile-state agent-id))
      (doseq [wid (array-seq (or (unchecked-get row "extra_agents") #js []))]
        (set! db/*conn* conn)
        (let [r (await (db/with-agent wid
                         (fn ^:async mk-peer* []
                           (await (agent/create! {:seon.agent/id wid})))))]
          (stage! (str "peer:" wid) (safe-pr (:seon.agent/id r)))))
      (set! db/*conn* conn)
      ;; THE user entity (message/user's user-ref target) + kb singleton
      (let [r (await (db/transact! {:seon.db/tx-data (client/seed-core!)}))]
        (stage! "seed-core" (safe-pr (:seon.db/ok? r))))
      ;; the plan block's state, verbatim ids
      (when-let [tx (unchecked-get row "plan_tx")]
        (set! db/*conn* conn)
        (let [r (await (db/with-agent agent-id
                         (fn ^:async plan-tx* []
                           (await (db/transact!
                                    {:seon.db/tx-data (reader/read-string tx)})))))]
          (stage! "plan-tx" (safe-pr (or (:seon.db/ok? r) r)))))
      (set! db/*conn* conn)
      (let [r (await (run/open-run! {:seon.agent/id agent-id
                                     :seon.agent.run/trigger :message}))
            run-id (:seon.agent.run/id r)]
        (stage! "open-run" (safe-pr (boolean run-id)))
        (if-not run-id
          #js {:sid (unchecked-get row "sid") :staging staging
               :crash (str "open-run failed: " (safe-pr r))}
          (let [echo-src (->> (array-seq (or (unchecked-get row "echo_forms")
                                             #js []))
                              (map #(unchecked-get % "src"))
                              (str/join "\n"))
                echo-res (when (seq echo-src)
                           (set! db/*conn* conn)
                           (await (drive-turn! compile-state agent-id run-id
                                               echo-src)))
                _ (set! db/*conn* conn)
                target-res (await (drive-turn! compile-state agent-id run-id
                                               (unchecked-get row "target")))]
            #js {:sid (unchecked-get row "sid")
                 :staging staging
                 :echoes (or echo-res #js [])
                 :forms target-res})))
      (catch :default e
        #js {:sid (unchecked-get row "sid")
             :staging staging
             :crash (truncate (str e) 400)})
      (finally
        (set! db/*conn* prev)
        (reset! schema/*schemas baseline-schemas)))))

(defn ^:async run-audit! []
  (let [manifest-path (env "SEON_LORA_AUDIT_MANIFEST")
        out-path      (env "SEON_LORA_AUDIT_OUT")
        limit         (let [n (js/parseInt (str (or (env "SEON_LORA_AUDIT_LIMIT") "0")) 10)]
                        (if (pos? n) n 100000))
        only          (let [s (str (or (env "SEON_LORA_AUDIT_ONLY") ""))]
                        (when (seq s) (set (str/split s #","))))
        rows0 (->> (str/split-lines (.readFileSync fs-mod manifest-path "utf8"))
                   (remove str/blank?)
                   (mapv #(js/JSON.parse %)))
        rows (->> rows0
                  (filterv #(not= "abstain" (unchecked-get % "status")))
                  ((fn [rs] (if only
                              (filterv #(contains? only (unchecked-get % "sid")) rs)
                              rs)))
                  (take limit)
                  vec)
        compile-state (await (repl/ensure-bootstrap!))]
    ;; live-pod instrumentation posture, once per process: index the core
    ;; into a scratch conn and wrap every schema'd fn from the program graph.
    (let [conn0 (await (client/open-agent-conn!))]
      (set! db/*conn* conn0)
      (await (db/transact! {:seon.db/tx-data (client/index-core!)}))
      (let [stats (instrument/instrument-from-db! @conn0)]
        (println "LORA-AUDIT instrumented:" (pr-str stats))))
    ;; the gym's fs posture (repo src/docs, read-only)
    (let [cwd (.cwd js/process)]
      (sfs/configure! {:seon.agent.fs/allowed-roots [(str cwd "/src")
                                                     (str cwd "/docs")]
                       :seon.agent.fs/read-only? true}))
    (.writeFileSync fs-mod out-path "")
    (let [baseline @schema/*schemas
          t0 (js/Date.now)]
      (loop [i 0]
        (when (< i (count rows))
          (let [row (nth rows i)
                res (await (audit-pair! compile-state baseline row))]
            (.appendFileSync fs-mod out-path
                             (str (js/JSON.stringify res) "\n"))
            (when (zero? (mod (inc i) 25))
              (println "LORA-AUDIT" (inc i) "/" (count rows)
                       (str (js/Math.round (/ (- (js/Date.now) t0) 1000)) "s")))
            (recur (inc i)))))
      (println "LORA-AUDIT DONE" (count rows) "pairs ->" out-path))))

(deftest lora-audit-run
  (if-not (gate-set?)
    (is true "SEON_LORA_AUDIT unset — audit is a no-op (normal suite)")
    (async done
      (-> (run-audit!)
          (.then (fn [] (is true "audit completed") (done)))
          (.catch (fn [e]
                    (is false (str "LORA-AUDIT crashed: " e))
                    (done)))))))
