(ns probe.host
  "C1 JVM sci agent-host skeleton: scale proofs over the LIVE default writer.

   Grows probe.jvm to the C1 gates of
   docs/prds/sci-execution-runtime/roadmap.md:

   - ONE shared loaded base: the real portable slice of src/my/**.cljs
     (pure defn blocks eval'd from their actual sources into sci), plus
     real compiled host cljc fns (seon.ai.tokens/estimate,
     seon.schema/validate) and a `seon.db` host namespace whose query/pull
     perform REAL synchronous UDS round-trips to the live writer through
     seon.db.transport.uds (the same client the operator uses).
   - sci context per agent via `sci/fork` of the shared base.
   - Thread-per-eval with deadline + interrupt (caller thread is the
     worker; a shared watchdog scheduler interrupts at deadline; sci's
     :interrupt-fn turns the flag into an unswallowable in-eval stop).
   - Scale proofs: N=100 one-turn wave (marginal memory WITH working
     state, GC behavior, wall time), 10 runaway among 90 healthy,
     OOME blast radius x20, final host footprint.

   Phase protocol identical to probe.jvm: prints `PHASE <name> READY`,
   waits for a stdin line; `DATA <edn>` lines carry results. Run from the
   repo root on the :writer basis (see host-run.sh)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [sci.core :as sci]
            [sci.interrupt :as interrupt]
            [seon.ai.tokens :as tokens]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.schema :as schema])
  (:import [java.lang.management ManagementFactory]
           [java.util.concurrent Executors ScheduledExecutorService
            ThreadPoolExecutor TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(set! *warn-on-reflection* true)

(defn data! [m] (println (str "DATA " (pr-str m))) (flush))

(defn used-heap-kb
  "Used JVM heap after a settle GC, in KB."
  []
  (System/gc)
  (Thread/sleep 200)
  (System/gc)
  (let [rt (Runtime/getRuntime)]
    (quot (- (.totalMemory rt) (.freeMemory rt)) 1024)))

(defn used-heap-mb [] (quot (used-heap-kb) 1024))

(defn gc-stats
  "Cumulative GC collection count and time across all collectors."
  []
  (reduce (fn [acc ^java.lang.management.GarbageCollectorMXBean b]
            (-> acc
                (update :gc-count + (max 0 (.getCollectionCount b)))
                (update :gc-time-ms + (max 0 (.getCollectionTime b)))))
          {:gc-count 0 :gc-time-ms 0}
          (ManagementFactory/getGarbageCollectorMXBeans)))

(defn rss-mb
  "Resident set size of this process in MB (ps, macOS)."
  []
  (let [pid (.pid (java.lang.ProcessHandle/current))
        out (:out (shell/sh "ps" "-o" "rss=" "-p" (str pid)))]
    (some-> out str/trim parse-long (quot 1024))))

;;; ------------------------------------------------------- live transport

(def socket-path "tmp/seon-cluster-default-req.sock")

(defn writer-call!
  "One request/response round-trip on a fresh UDS connection.
   Same shape as script/seon/dev/branch.clj's writer-call!."
  [request]
  (with-open [ch (uds/connect! socket-path)]
    (uds/call! {::uds/channel ch ::uds/message request})))

(def ^AtomicLong call-count (AtomicLong. 0))
(def ^AtomicLong call-nanos (AtomicLong. 0))

(defn timed-call! [request]
  (let [t0 (System/nanoTime)
        response (writer-call! request)]
    (.incrementAndGet call-count)
    (.addAndGet call-nanos (- (System/nanoTime) t0))
    response))

(defn resolve-head! []
  (let [r (timed-call! (protocol/resolve-head-request
                        {::protocol/request-id (str (random-uuid))
                         ::protocol/database-name "default"}))]
    (or (:seon.db/db r)
        (throw (ex-info "resolve-head failed" {:response r})))))

(defn db-query
  "Host `seon.db/query`: real read-only Datalog query against the live
   writer's current head. Blocking — the JVM form of the db boundary."
  [query-form & arguments]
  (let [db (resolve-head!)
        r (timed-call! (protocol/query-request
                        {::protocol/request-id (str (random-uuid))
                         :seon.db/db db
                         ::protocol/query-form query-form
                         ::protocol/arguments (vec arguments)}))]
    (if (::protocol/success? r)
      (:datahike.query/result r)
      {:seon/error (select-keys r [::protocol/error-kind ::protocol/error])})))

(defn db-pull
  "Host `seon.db/pull`: real read-only pull against the live head."
  [selector entity-id]
  (let [db (resolve-head!)
        r (timed-call! (protocol/pull-request
                        {::protocol/request-id (str (random-uuid))
                         :seon.db/db db
                         ::protocol/selector selector
                         ::protocol/entity-id entity-id}))]
    (if (::protocol/success? r)
      (::protocol/result r)
      {:seon/error (select-keys r [::protocol/error-kind ::protocol/error])})))

;;; -------------------------------------------- portable my.* base loading

(defn defn-blocks
  "Top-level defn blocks of one source string (same heuristic as
   tmp/sci-probe/inventory.bb)."
  [s]
  (let [lines (vec (str/split-lines s))
        tops (vec (keep-indexed
                   (fn [i l] (when (re-find #"^\((defn|def )" l) i)) lines))]
    (for [[a b] (map vector tops (concat (rest tops) [(count lines)]))
          :let [block (str/join "\n" (subvec lines a b))]
          :when (str/starts-with? block "(defn")]
      block)))

(defn classify [block]
  (let [async? (re-find #"\^:async|\(await |js/Promise" block)
        js? (re-find #"js/|#js|\(\.\-|\(\. |\(\.[a-zA-Z]" block)
        db? (re-find #"db/transact!|db/query|db/pull|db/entity|db/db\b|blob/" block)]
    {:name (second (re-find #"\(defn-? \^?[:a-z]*\s*([^\s]+)" block))
     :class (cond (and async? db?) :db-boundary-async
                  async? :js-async
                  js? :js-interop
                  db? :db-boundary
                  :else :pure)}))

(def my-source-files
  ["src/my/data.cljs" "src/my/plan.cljs" "src/my/kb.cljs" "src/my/ns.cljs"
   "src/my/canvas.cljs" "src/my/ui.cljs" "src/my/skills.cljs"
   "src/my/blob.cljs"])

(defn file-ns-name [path]
  (-> path (str/replace #"^src/" "") (str/replace #"\.cljs$" "")
      (str/replace "/" ".") symbol))

(defn base-ctx
  "ONE shared base context: host bindings (real cljc fns + live db
   boundary) plus every pure my.* defn block that evals from its real
   source. Returns {:ctx sci-ctx :report {...}} — the report is the
   honest real-vs-synthetic ledger."
  []
  (let [ctx (sci/init
             {:namespaces
              {'seon.db {'query db-query
                         'pull db-pull
                         'head resolve-head!}
               'seon.schema {'validate (fn [k v]
                                         (schema/valid-candidate-value? k v))
                             'register! (fn [_k _s] nil)}
               'seon.ai.tokens {'estimate tokens/estimate
                                'estimate-chars tokens/estimate-chars}}
              :interrupt-fn
              (fn []
                (when (.isInterrupted (Thread/currentThread))
                  (interrupt/interrupt! "eval deadline exceeded")))})
        loads
        (vec
         (for [path my-source-files
               :let [ns-sym (file-ns-name path)
                     source (slurp (io/file path))
                     blocks (defn-blocks source)
                     pure (filter #(= :pure (:class (classify %))) blocks)]]
           (do
             ;; synthetic ns form standing in for seon.eval/augment-ns-source:
             ;; the same aliases production injects, pointed at the host nses.
             (sci/eval-string*
              ctx (str "(ns " ns-sym
                       " (:require [clojure.string :as str]"
                       " [clojure.set :as set]"
                       " [clojure.edn :as edn]"
                       " [clojure.walk :as walk]"
                       " [seon.db :as db]"
                       " [seon.schema :as schema]"
                       " [seon.ai.tokens :as tokens]))"))
             (reduce
              (fn [acc block]
                (let [nm (:name (classify block))
                      r (try (sci/eval-string*
                              ctx (str "(in-ns '" ns-sym ")\n" block))
                             ::ok
                             (catch Throwable e
                               [::failed (first (str/split-lines
                                                 (str (.getMessage e))))]))]
                  (if (= ::ok r)
                    (update acc :loaded conj nm)
                    (update acc :failed conj [nm (second r)]))))
              {:ns ns-sym :pure-blocks (count pure) :loaded [] :failed []}
              pure))))]
    {:ctx ctx
     :report {:files (count my-source-files)
              :pure-blocks (reduce + (map :pure-blocks loads))
              :loaded (reduce + (map (comp count :loaded) loads))
              :failed (reduce + (map (comp count :failed) loads))
              :per-ns (mapv #(-> %
                                 (update :loaded count)
                                 (update :failed (fn [f] (mapv first f))))
                            loads)
              :failures (into {} (mapcat :failed loads))}}))

;;; --------------------------------------------------- eval runner (thread)

(defonce ^ScheduledExecutorService watchdog
  (Executors/newScheduledThreadPool 2))

(defn run-eval!
  "Evaluate `code` in `ctx` on the CALLING thread under `deadline-ms`.
   A watchdog interrupt fires at the deadline; sci's :interrupt-fn turns
   it into an unswallowable in-eval stop. Returns a status envelope."
  [ctx code deadline-ms]
  (let [worker (Thread/currentThread)
        task (.schedule watchdog
                        ^Runnable #(.interrupt worker)
                        (long deadline-ms) TimeUnit/MILLISECONDS)
        t0 (System/nanoTime)
        result (try {:status :ok :value (sci/eval-string* ctx code)}
                    (catch Throwable e
                      (let [oom? (loop [t e]
                                   (cond (nil? t) false
                                         (instance? OutOfMemoryError t) true
                                         :else (recur (.getCause ^Throwable t))))]
                        (cond
                          oom? {:status :oom :message (.getMessage e)}
                          (re-find #"deadline exceeded|interrupt"
                                   (str (.getMessage e)))
                          {:status :interrupted :message (.getMessage e)}
                          :else
                          {:status :error
                           :message (first (str/split-lines
                                            (str (.getMessage e))))})))
                    (finally
                      (.cancel task false)
                      (Thread/interrupted)))] ; clear the flag for pool reuse
    (assoc result :ms (quot (- (System/nanoTime) t0) 1000000))))

;;; ------------------------------------------------------ agent contexts

(defonce !base (atom nil))
(defonce !ctxs (atom []))

(def admission-src
  "Per-agent admission: 10 own defns (the probe's agent lib)."
  (str/join "\n" (for [i (range 10)]
                   (str "(defn agent-fn-" i " [x] (+ (* x 2) " i "))"))))

(defn make-agent-ctx
  "One agent context: a fork of the ONE shared base plus its own defs."
  [base]
  (doto (sci/fork base)
    (sci/eval-string* admission-src)))

(defn turn-src
  "One real turn's worth of work for agent `i`: a my.plan-shaped
   transform over its own data (through the REAL loaded my.data fns),
   one live db query + one live pull, and a few defs that persist as
   working state in the context."
  [i]
  (str
   "(def plan-rows (vec (for [n (range 250)]"
   "  {:id n :status :todo :owner " i
   "   :cost (mod (* n 7) 13)"
   "   :children (vec (for [j (range 3)] {:id j :status :todo}))})))\n"
   "(def done (mapv #(assoc % :status :done) plan-rows))\n"
   "(def by-cost (my.data/group-sum"
   "  {:seon.items/items done :my.data/group-key :status :my.data/key :cost}))\n"
   "(def total (my.data/sum-by {:seon.items/items done :my.data/key :cost}))\n"
   "(def agents (seon.db/query"
   "  '[:find ?e ?id :where [?e :seon.agent/id ?id]]))\n"
   "(def root-agent (seon.db/pull [:seon.agent/id]"
   "  (ffirst (filter #(= \"root\" (second %)) agents))))\n"
   "{:agent " i
   " :done (count done)"
   " :total total"
   " :groups (:seon.items/count by-cost)"
   " :live-agents (count agents)"
   " :root (:seon.agent/id root-agent)"
   " :tokens (seon.ai.tokens/estimate (pr-str (take 20 done)))}"))

(def runaway-src "(loop [i 0] (recur (inc i)))")
(def bomb-src "(count (vec (range 4000000000)))")

(defn pooled-run!
  "Run [ctx code deadline] triples on a fixed pool; returns results in
   submission order plus wall-clock ms for the whole wave."
  [pool-size triples]
  (let [pool (Executors/newFixedThreadPool (int pool-size))
        t0 (System/nanoTime)
        futures (mapv (fn [[ctx code deadline]]
                        (.submit ^ThreadPoolExecutor pool
                                 ^java.util.concurrent.Callable
                                 (fn [] (run-eval! ctx code deadline))))
                      triples)
        results (mapv #(.get ^java.util.concurrent.Future %) futures)]
    (.shutdown pool)
    {:results results
     :wall-ms (quot (- (System/nanoTime) t0) 1000000)}))

(defn quantiles [xs]
  (if (empty? xs)
    {}
    (let [s (vec (sort xs)) n (count s)
          at (fn [q] (nth s (min (dec n) (int (* q n)))))]
      {:min (first s) :p50 (at 0.5) :p90 (at 0.9) :p99 (at 0.99)
       :max (peek s)
       :mean (double (/ (reduce + s) n))})))

;;; ------------------------------------------------------------ phases

(def transport-proof
  (fn []
    (let [ping (writer-call! (protocol/ping-request
                              {::protocol/request-id (str (random-uuid))}))
          head (resolve-head!)
          q (db-query '[:find ?e ?id :where [?e :seon.agent/id ?id]])
          root-eid (ffirst (filter #(= "root" (second %)) q))
          p (db-pull [:seon.agent/id] root-eid)]
      (data! {:probe :transport
              :ping (select-keys ping [::protocol/pong?
                                       ::protocol/success?])
              :head (select-keys head [:db-name :t :datahike/commit-id])
              :query-agents (vec (sort (map second q)))
              :pull-root p}))))

(def phases
  [["baseline"
    (fn [] (data! {:used-heap-mb (used-heap-mb) :rss-mb (rss-mb)}))]
   ["base-loaded"
    (fn []
      (let [h0 (used-heap-kb)
            {:keys [ctx report]} (base-ctx)]
        (reset! !base ctx)
        (data! (assoc report
                      :probe :base
                      :base-heap-kb (- (used-heap-kb) h0)
                      :used-heap-mb (used-heap-mb)))))]
   ["transport-proof" transport-proof]
   ["ctx-100"
    (fn []
      (let [h0 (used-heap-kb)
            t0 (System/nanoTime)]
        (reset! !ctxs (mapv (fn [_] (make-agent-ctx @!base)) (range 100)))
        (let [h1 (used-heap-kb)]
          (data! {:probe :ctx-100
                  :ctxs (count @!ctxs)
                  :admit-ms (quot (- (System/nanoTime) t0) 1000000)
                  :idle-marginal-kb-per-ctx (/ (- h1 h0) 100.0)
                  :used-heap-mb (used-heap-mb)
                  :cross-ctx-isolated?
                  (do (sci/eval-string* (first @!ctxs) "(def leak-probe 1)")
                      (try (sci/eval-string* (last @!ctxs) "leak-probe")
                           :leaked
                           (catch Exception _ true)))}))))]
   ["wave-100"
    (fn []
      (let [h0 (used-heap-kb)
            gc0 (gc-stats)
            c0 (.get call-count)
            {:keys [results wall-ms]}
            (pooled-run! 10 (map-indexed
                             (fn [i ctx] [ctx (turn-src i) 30000])
                             @!ctxs))
            gc1 (gc-stats)
            h1 (used-heap-kb)
            ok (filter #(= :ok (:status %)) results)
            sample (:value (first ok))]
        (data! {:probe :wave-100
                :turns (count results)
                :ok (count ok)
                :not-ok (vec (remove #(= :ok (:status %)) results))
                :wall-ms wall-ms
                :turn-ms (quantiles (map :ms results))
                :db-calls (- (.get call-count) c0)
                :db-call-mean-ms (let [n (.get call-count)]
                                   (when (pos? n)
                                     (double (/ (.get call-nanos)
                                                n 1000000.0))))
                :sample-turn-value sample
                :working-marginal-kb-per-ctx (/ (- h1 h0) 100.0)
                :used-heap-mb (used-heap-mb)
                :gc {:collections (- (:gc-count gc1) (:gc-count gc0))
                     :gc-time-ms (- (:gc-time-ms gc1) (:gc-time-ms gc0))}})))]
   ["interrupt-scale"
    (fn []
      ;; 10 runaway contexts among 90 healthy on one pool.
      (let [runaway? (set (range 0 100 10))
            {:keys [results wall-ms]}
            (pooled-run!
             20 (map-indexed
                 (fn [i ctx]
                   (if (runaway? i)
                     [ctx runaway-src 500]
                     [ctx (str "(count (mapv #(assoc % :status :again) "
                               "plan-rows))") 30000]))
                 @!ctxs))
            runaways (keep-indexed #(when (runaway? %1) %2) results)
            healthy (keep-indexed #(when-not (runaway? %1) %2) results)]
        (data! {:probe :interrupt-scale
                :wall-ms wall-ms
                :runaway {:n (count runaways)
                          :interrupted (count (filter #(= :interrupted
                                                          (:status %))
                                                      runaways))
                          :ms (quantiles (map :ms runaways))
                          :statuses (frequencies (map :status runaways))}
                :healthy {:n (count healthy)
                          :ok (count (filter #(= :ok (:status %)) healthy))
                          :ms (quantiles (map :ms healthy))
                          :statuses (frequencies (map :status healthy))}})))]
   ["oome-20"
    (fn []
      (let [rounds
            (vec
             (for [round (range 20)]
               (let [bomber (nth @!ctxs (mod round 100))
                     ;; concurrent healthy evals DURING the bomb
                     concurrent-pool (Executors/newFixedThreadPool 5)
                     concurrent
                     (mapv (fn [i]
                             (.submit ^ThreadPoolExecutor concurrent-pool
                                      ^java.util.concurrent.Callable
                                      (fn []
                                        (run-eval!
                                         (nth @!ctxs (+ 50 i))
                                         "(reduce + (map inc (range 100000)))"
                                         30000))))
                           (range 5))
                     bomb (run-eval! bomber bomb-src 60000)
                     concurrent-results
                     (mapv #(.get ^java.util.concurrent.Future %) concurrent)
                     _ (.shutdown concurrent-pool)
                     ;; survivor probes AFTER the bomb: pure + real db
                     survivors
                     (mapv (fn [i]
                             (let [ctx (nth @!ctxs (* i 9))]
                               {:pure (:status (run-eval!
                                                ctx "(agent-fn-0 20)" 5000))
                                :db (:status
                                     (run-eval!
                                      ctx
                                      "(count (seon.db/query '[:find ?e :where [?e :seon.agent/id]]))"
                                      10000))}))
                           (range 10))]
                 {:round round
                  :bomb (:status bomb)
                  :bomb-message (:message bomb)
                  :concurrent-during-bomb
                  (frequencies (map :status concurrent-results))
                  :survivor-pure-ok
                  (count (filter #(= :ok (:pure %)) survivors))
                  :survivor-db-ok
                  (count (filter #(= :ok (:db %)) survivors))
                  :used-heap-mb (used-heap-mb)})))]
        (data! {:probe :oome-20
                :rounds (count rounds)
                :process-survived-all? true ; reaching here proves it
                :bomb-statuses (frequencies (map :bomb rounds))
                :survivor-pure-ok-total
                (reduce + (map :survivor-pure-ok rounds))
                :survivor-db-ok-total
                (reduce + (map :survivor-db-ok rounds))
                :concurrent-status-totals
                (apply merge-with + (map :concurrent-during-bomb rounds))
                :rounds-detail rounds})))]
   ["footprint"
    (fn []
      (data! {:probe :footprint
              :ctxs (count @!ctxs)
              :used-heap-mb (used-heap-mb)
              :rss-mb (rss-mb)
              :gc-total (gc-stats)
              :db-calls-total (.get call-count)}))]])

(defn -main [& _]
  (let [rdr (java.io.BufferedReader.
             (java.io.InputStreamReader. System/in))]
    (doseq [[nm f] phases]
      (try (f)
           (catch Throwable e
             (data! {:phase nm :phase-error (str (.getMessage e))})))
      (println (str "PHASE " nm " READY"))
      (flush)
      (.readLine rdr))
    (println "DONE")
    (System/exit 0)))
