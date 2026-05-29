(ns sidecar-poc.agent
  "Synthetic agent for Phase D multi-agent smoke. Reads its identity + role
   from the wasi env (SIDECAR_AGENT_ID, SIDECAR_AGENT_ROLE, SIDECAR_AGENT_DURATION_MS),
   then runs one of three workload loops against the shared sidecar DB:

   - writer : transacts a new :task entity every 200ms
   - reader : listens for tx events, periodically queries pending counts
   - mixed  : listens; CAS :pending -> :in-progress on observed tasks,
              \"works\" 100ms, transacts :done + a :result entity

   Returns an EDN report string capturing per-agent counters when the
   duration elapses.

   The agent is intentionally tiny so the Phase D smoke turns yellow only
   on real architectural problems (race/dedup/listener fanout), not on
   the agent's own complexity."
  (:require [sidecar-poc.datahike :as d]
            [sidecar-poc.fs       :as fs]
            [sidecar-poc.facts    :as facts]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Lightweight env reader (works for wasm-rquickjs's process.env shim, which
;; pulls from wasi:cli/environment when inherit_env is set on WasiCtxBuilder
;; or when explicit `.env(k,v)` calls are made).
;; ---------------------------------------------------------------------------

(defn- env [k default]
  (or (some-> js/process .-env (aget k))
      default))

(defn- now-ms []
  (.now js/Date))

(defn- log [agent-id & parts]
  (js/console.log (str "[" agent-id "] " (str/join " " (map str parts)))))

;; ---------------------------------------------------------------------------
;; Promise-based sleep (no core.async on the guest side).
;; ---------------------------------------------------------------------------

(defn- ^:async sleep-ms [ms]
  (await (js/Promise.
           (fn [resolve _reject]
             (js/setTimeout #(resolve nil) ms)))))

;; ---------------------------------------------------------------------------
;; Workload loops
;; ---------------------------------------------------------------------------

(defn- ^:async run-writer [agent-id conn duration-ms]
  (let [end (+ (now-ms) duration-ms)
        counter (atom 0)]
    (log agent-id "writer-loop-begin end-ms" end)
    (loop []
      (when (< (now-ms) end)
        (try
          (let [n (swap! counter inc)
                id (str agent-id "-" n)
                tx [{:task/id id
                     :task/status :pending
                     :task/created-by (keyword agent-id)
                     :task/created-ms (now-ms)}]
                _  (d/transact! conn tx)]
            (when (or (= n 1) (zero? (mod n 5)))
              (log agent-id "wrote" id "now" (now-ms))))
          (catch :default e
            (log agent-id "writer-err" (.-message e))))
        (await (sleep-ms 200))
        (recur)))
    (log agent-id "writer-loop-end" "commits" @counter)
    {:role :writer :commits @counter}))

(defn- ^:async run-reader [agent-id conn duration-ms]
  (let [end       (+ (now-ms) duration-ms)
        events    (atom 0)
        queries   (atom 0)
        last-bt   (atom 0)
        out-of-ord (atom 0)]
    ;; Register listener.
    (d/listen! conn (str agent-id "-listen")
               (fn [ev]
                 (swap! events inc)
                 (let [bt (:basis-t ev)]
                   (when (and bt (< bt @last-bt))
                     (swap! out-of-ord inc))
                   (when bt (reset! last-bt bt)))))
    (loop []
      (when (< (now-ms) end)
        (try
          (let [_pending (d/q '[:find (count ?e) .
                                :where [?e :task/status :pending]]
                              conn)]
            (swap! queries inc))
          (catch :default e
            (log agent-id "reader-q-err" (.-message e))))
        (await (sleep-ms 50))
        (recur)))
    {:role :reader :events @events :queries @queries :out-of-order @out-of-ord
     :last-bt @last-bt}))

;; -------- Cache-friendly variants (Phase D' rerun, 2026-05-25) --------
;;
;; Reader-CF and Mixed-CF capture (d/db conn) once and run K read cycles
;; against that *pinned* basis-t before refreshing. Identical (basis-t,
;; query, args) tuples hit the snapshot cache in the Rust host. K is read
;; from env SIDECAR_CACHE_BATCH (default 100). Pinned reads also use 3
;; different query shapes per cycle to demonstrate that the cache keys
;; correctly differentiate.

(defn- ^:async run-reader-cf [agent-id conn duration-ms]
  (let [end        (+ (now-ms) duration-ms)
        batch      (or (some-> (env "SIDECAR_CACHE_BATCH" nil) js/parseInt) 100)
        events     (atom 0)
        queries    (atom 0)
        snap-rolls (atom 0)
        last-bt    (atom 0)
        out-of-ord (atom 0)]
    (d/listen! conn (str agent-id "-listen")
               (fn [ev]
                 (swap! events inc)
                 (let [bt (:basis-t ev)]
                   (when (and bt (< bt @last-bt))
                     (swap! out-of-ord inc))
                   (when bt (reset! last-bt bt)))))
    (log agent-id "reader-cf begin batch" batch)
    (loop []
      (when (< (now-ms) end)
        ;; Pin a snapshot. If conn's basis-t is 0 (no tx event seen yet),
        ;; skip the batch and yield — the listener loop will populate it
        ;; on the next writer commit. This guards against the cold-start
        ;; race where the first capture has basis-t=0 and triggers
        ;; unpinned reads.
        (let [bt @(:basis-t conn)]
          (if (zero? bt)
            (await (sleep-ms 50))
            (let [db-snap (d/db conn)]
              (swap! snap-rolls inc)
              (loop [i 0]
                (when (and (< i batch) (< (now-ms) end))
                  (try
                    ;; Three different query shapes, each pinned to same basis-t.
                    ;; All three identical across iterations = high cache-hit rate.
                    (d/q '[:find (count ?e) . :where [?e :task/status :pending]]
                         db-snap)
                    (d/q '[:find (count ?e) . :where [?e :task/status :done]]
                         db-snap)
                    (d/q '[:find ?id :where
                           [?e :task/status :pending]
                           [?e :task/id ?id]]
                         db-snap)
                    (swap! queries + 3)
                    (catch :default e
                      (log agent-id "reader-cf-q-err" (.-message e))))
                  (recur (inc i))))
              (await (sleep-ms 25)))))
        (recur)))
    {:role :reader-cf :events @events :queries @queries
     :snap-rolls @snap-rolls :batch batch
     :out-of-order @out-of-ord :last-bt @last-bt}))

(defn- ^:async run-mixed [agent-id conn duration-ms]
  (let [end (+ (now-ms) duration-ms)
        events (atom 0)
        completed (atom 0)
        cas-conflicts (atom 0)
        last-claimed (atom nil)]
    (d/listen! conn (str agent-id "-listen")
               (fn [ev]
                 (swap! events inc)
                 ;; Scan tx-data for new :pending tasks; remember the most
                 ;; recent task-id we saw added.
                 (doseq [datom (:tx-data ev)]
                   (let [[_e a v _t op] datom]
                     (when (and op (= a "task/id"))
                       (reset! last-claimed v))))))
    (loop []
      (when (< (now-ms) end)
        (try
          ;; Find one :pending task. CAS to :in-progress.
          (let [rows (d/q '[:find ?id :where
                            [?e :task/status :pending]
                            [?e :task/id ?id]]
                          conn)
                ids  (sort (map first rows))
                pick (first ids)]
            (when pick
              (try
                (d/transact! conn
                             [[:db/cas
                               [:task/id pick]
                               :task/status
                               :pending
                               :in-progress]
                              {:db/id [:task/id pick]
                               :task/started-ms (now-ms)}])
                (await (sleep-ms 100))
                (d/transact! conn
                             [{:db/id [:task/id pick]
                               :task/status :done
                               :task/done-ms (now-ms)}
                              {:result/of pick
                               :result/blob (str "result-of-" pick)
                               :result/by (keyword agent-id)}])
                (swap! completed inc)
                (catch :default e
                  (let [msg (or (.-message e) (str e))]
                    (if (re-find #"cas" (str/lower-case msg))
                      (swap! cas-conflicts inc)
                      (log agent-id "mixed-tx-err" msg)))))))
          (catch :default e
            (log agent-id "mixed-loop-err" (or (.-message e) (str e)))))
        (await (sleep-ms 25))
        (recur)))
    {:role :mixed :events @events :completed @completed
     :cas-conflicts @cas-conflicts}))

(defn- ^:async run-mixed-cf [agent-id conn duration-ms]
  (let [end (+ (now-ms) duration-ms)
        batch (or (some-> (env "SIDECAR_CACHE_BATCH" nil) js/parseInt) 100)
        events (atom 0)
        completed (atom 0)
        cas-conflicts (atom 0)
        snap-rolls (atom 0)
        queries (atom 0)]
    (d/listen! conn (str agent-id "-listen")
               (fn [_ev] (swap! events inc)))
    (log agent-id "mixed-cf begin batch" batch)
    (loop []
      (when (< (now-ms) end)
        (let [bt @(:basis-t conn)]
          (if (zero? bt)
            (await (sleep-ms 50))
            (let [db-snap (d/db conn)]
              (swap! snap-rolls inc)
              (let [pick (atom nil)]
                (loop [i 0]
                  (when (and (< i batch) (< (now-ms) end))
                    (try
                      (let [rows (d/q '[:find ?id :where
                                        [?e :task/status :pending]
                                        [?e :task/id ?id]]
                                      db-snap)
                            ids  (sort (map first rows))]
                        (when (and (nil? @pick) (first ids))
                          (reset! pick (first ids)))
                        (swap! queries inc))
                      (catch :default e
                        (log agent-id "mixed-cf-q-err" (.-message e))))
                    (recur (inc i))))
                (when-let [p @pick]
                  (try
                    (d/transact! conn
                                 [[:db/cas [:task/id p] :task/status :pending :in-progress]
                                  {:db/id [:task/id p] :task/started-ms (now-ms)}])
                    (await (sleep-ms 50))
                    (d/transact! conn
                                 [{:db/id [:task/id p]
                                   :task/status :done
                                   :task/done-ms (now-ms)}
                                  {:result/of p
                                   :result/blob (str "result-of-" p)
                                   :result/by (keyword agent-id)}])
                    (swap! completed inc)
                    (catch :default e
                      (let [msg (or (.-message e) (str e))]
                        (if (re-find #"cas" (str/lower-case msg))
                          (swap! cas-conflicts inc)
                          (log agent-id "mixed-cf-tx-err" msg))))))
                (await (sleep-ms 25))))))
        (recur)))
    {:role :mixed-cf :events @events :completed @completed
     :queries @queries :snap-rolls @snap-rolls :batch batch
     :cas-conflicts @cas-conflicts}))

;; ---------------------------------------------------------------------------
;; fs-smoke — exercise WASI preopens.
;; ---------------------------------------------------------------------------

(defn run-fs-smoke [agent-id conn _duration-ms]
  (let [checks (atom [])
        push!  (fn [name result]
                 (swap! checks conj {:check name :result result})
                 (log agent-id "check" name "=>" (pr-str result)))]
    ;; 0. Make sure facts schema exists so record-fact! calls below work.
    (try
      (let [s (facts/ensure-schema! conn)]
        (push! :ensure-schema {:status s}))
      (catch :default e
        (push! :ensure-schema {:ok false :error (or (.-message e) (str e))})))

    ;; 1. Read CLAUDE.md.
    (try
      (let [c (fs/read-file "/seon-src/CLAUDE.md")
            line1 (-> c (str/split #"\n") first)]
        (push! :read-claude-md
               {:ok true :bytes (count c) :first-line line1})
        (try
          (facts/record-fact! conn
                              {:id        (str "fs-probe-" agent-id "-claude-md")
                               :subject   :seon/project
                               :predicate :has-claude-md
                               :object    true
                               :source    "/seon-src/CLAUDE.md"
                               :recorded-by (keyword "agent" agent-id)
                               :confidence  100
                               :tags       #{:fs-smoke :probe}})
          (catch :default e
            (push! :record-fact-error (or (.-message e) (str e))))))
      (catch :default e
        (push! :read-claude-md {:ok false :error (or (.-message e) (str e))})))

    ;; 2. ls /seon-src/src/seon
    (try
      (let [entries (fs/ls "/seon-src/src/seon")
            cljs-files (filterv (fn [{:keys [name type]}]
                                  (and (= type :file) (str/ends-with? name ".cljs")))
                                entries)]
        (push! :ls-src-seon
               {:total (count entries) :cljs-count (count cljs-files)})
        (try
          (facts/record-fact! conn
                              {:id        (str "fs-probe-" agent-id "-cljs-count")
                               :subject   :seon.dir/src-seon
                               :predicate :cljs-file-count
                               :object    (count cljs-files)
                               :source    "/seon-src/src/seon"
                               :recorded-by (keyword "agent" agent-id)
                               :confidence  100
                               :tags       #{:fs-smoke :probe}})
          (catch :default _ nil)))
      (catch :default e
        (push! :ls-src-seon {:ok false :error (or (.-message e) (str e))})))

    ;; 3. Attempt write to RO mount (should fail).
    (try
      (fs/write-file! "/seon-src/test-from-agent.txt" "hi")
      (push! :write-ro {:ok false :note "EXPECTED EROFS BUT WRITE SUCCEEDED!"})
      (catch :default e
        (let [errno (or (some-> (ex-data e) :errno) "unknown")]
          (push! :write-ro {:ok true :rejected-with errno}))))

    ;; 4. Write to RW scratch.
    (try
      (fs/write-file! "/scratch/agent-test.txt" (str "hello from " agent-id))
      (let [readback (fs/read-file "/scratch/agent-test.txt")]
        (push! :write-rw {:ok true :readback readback}))
      (catch :default e
        (push! :write-rw {:ok false :error (or (.-message e) (str e))})))

    {:role :fs-smoke :agent-id agent-id :checks @checks}))

;; ---------------------------------------------------------------------------
;; learn — read source under /seon-src, extract ns forms, record facts.
;; ---------------------------------------------------------------------------

(defn- extract-ns [src]
  ;; Very simple regex — finds the first `(ns symbol` form. Good enough for
  ;; the synthetic learn loop. Real agents would use rewrite-clj or similar.
  (when-let [m (re-find #"\(ns\s+([a-zA-Z0-9.\-_!?*]+)" (or src ""))]
    (second m)))

(defn run-learn [agent-id conn _duration-ms]
  ;; Ensure schema + seed first.
  (log agent-id "seeding facts...")
  (let [seed-result (facts/seed! conn)]
    (log agent-id "seed result" (pr-str seed-result))
    ;; Walk /seon-src/src/seon for .cljs files; for each, extract ns + record.
    (let [files (fs/list-tree "/seon-src/src/seon" {:max-depth 3})
          cljs  (filterv #(or (str/ends-with? % ".cljs")
                              (str/ends-with? % ".cljc")) files)
          learned (atom 0)]
      (doseq [path cljs]
        (try
          (let [head (->> (str/split (fs/read-file path) #"\n")
                          (take 10)
                          (str/join "\n"))
                ns-name (extract-ns head)]
            (when ns-name
              (facts/record-fact! conn
                                  {:id        (str "learned-ns-" ns-name "-by-" agent-id)
                                   :subject   (keyword ns-name)
                                   :predicate :file-exists
                                   :object    path
                                   :source    path
                                   :recorded-by (keyword "agent" agent-id)
                                   :confidence  100
                                   :tags       #{:learn :auto-extracted}})
              (swap! learned inc)))
          (catch :default e
            (log agent-id "skip" path (or (.-message e) (str e))))))
      ;; Demo query: facts about seon.agent.
      (let [agent-facts (facts/facts-about conn :seon.agent)]
        (log agent-id "facts-about :seon.agent ->" (count agent-facts) "facts")
        {:role        :learn
         :agent-id    agent-id
         :seed        seed-result
         :files-scanned (count cljs)
         :namespaces-recorded @learned
         :fact-count  (facts/fact-count conn)
         :sample-query {:subject :seon.agent
                        :facts   agent-facts}}))))

;; ---------------------------------------------------------------------------
;; Entry — exported via globalThis for the ESM shim.
;; ---------------------------------------------------------------------------

(defn ^:async run-agent! [agent-id role duration-ms]
  (try
    (let [conn (d/connect {})
          dur  (or duration-ms
                   (some-> (env "SIDECAR_AGENT_DURATION_MS" nil) js/parseInt)
                   30000)
          aid  (or agent-id
                   (env "SIDECAR_AGENT_ID" "agent-unknown"))
          rol  (or role
                   (env "SIDECAR_AGENT_ROLE" "writer"))]
      (let [mode (env "SIDECAR_BENCH_MODE" "default")]
        (log aid "starting" {:role rol :duration-ms dur :bench-mode mode})
        (let [report (cond
                       (= rol "fs-smoke") (run-fs-smoke aid conn dur)
                       (= rol "learn")    (run-learn    aid conn dur)
                       :else
                       (await
                        (case [mode rol]
                          ["default" "writer"]        (run-writer aid conn dur)
                          ["default" "reader"]        (run-reader aid conn dur)
                          ["default" "mixed"]         (run-mixed  aid conn dur)
                          ["cache-friendly" "writer"] (run-writer aid conn dur)
                          ["cache-friendly" "reader"] (run-reader-cf aid conn dur)
                          ["cache-friendly" "mixed"]  (run-mixed-cf  aid conn dur)
                          (throw (js/Error. (str "unknown mode/role: " mode "/" rol))))))]
          (log aid "role-done — stopping conn")
          (d/stop! conn)
          (log aid "FINAL-REPORT" (pr-str report))
          (let [out (pr-str (assoc report :agent-id aid :ok true :bench-mode mode))]
            (log aid "returning")
            out))))
    (catch :default e
      (pr-str {:ok false
               :error (or (.-message e) (str e))}))))

;; Light "run-smoke" path so the existing single-guest invocation still works
;; against this bundle.
(defn ^:async run-smoke! []
  (try
    (let [conn   (d/connect {})
          rep    (d/transact! conn
                              [{:task/id (str "smoke-" (now-ms))
                                :task/status :pending
                                :task/created-by :smoke
                                :task/created-ms (now-ms)}])
          rows   (d/q '[:find (count ?e) . :where [?e :task/id _]] conn)]
      (pr-str {:ok true :transact rep :task-count rows}))
    (catch :default e
      (pr-str {:ok false :error (or (.-message e) (str e))}))))

(set! (.-sidecarAgentRun js/globalThis) run-agent!)
(set! (.-sidecarAgentSmoke js/globalThis) run-smoke!)

(defn -main [& _args] nil)
