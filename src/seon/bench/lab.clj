(ns seon.bench.lab
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.bench.cluster :as cluster]
            [datahike.api :as d]
            [seon.store :as store]))

(defn- ^:seon.flow/edge eval-edn [inst code]
  (let [res (cluster/instance-op! inst {:op :eval :agent :repl/lab-probe :branch :branch/lab-probe :code code})]
    (if (and (:ok? res) (string? (:value res)))
      (try (edn/read-string (:value res)) (catch Throwable _ {:raw (:value res) :ok? true}))
      {:ok? false :eval-res res})))

(defn- ^:seon.flow/edge probe-agent-detail [inst aid branch]
  (eval-edn inst
    (str "(let [conn (user/conn) mdb (user/main-db) bdb (or (when ((set (datahike.api/branches conn)) " (pr-str branch) ") (seon.store/head conn " (pr-str branch) ")) mdb) "
         "evs (->> (datahike.api/datoms bdb :avet :seon.agent.eval/agent " (pr-str aid) ") "
         "(map #(datahike.api/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/call-index :seon.agent.eval/intent :seon.agent.eval/code :seon.agent.eval/error :seon.agent.eval/thought :seon.agent.eval/ms] (:e %))) "
         "(sort-by (juxt #(long (or (:seon.agent.eval/turn %) 0)) #(long (or (:seon.agent.eval/call-index %) 0)))) vec) "
         "errs (filterv #(not (clojure.string/blank? (str (:seon.agent.eval/error %)))) evs) "
         "ma (datahike.api/pull mdb '[:seon.agent/branch :seon.agent/paused? :seon.agent/merged-tx :seon.agent/tested-tx] [:seon.agent/id " (pr-str aid) "]) "
         "ed (seon.task.gate/branch-latest-edit-info mdb bdb ma) "
         "turns (count (distinct (keep :seon.agent.eval/turn evs))) "
         "clip (fn [s n] (let [t (str (or s \"\"))] (if (> (count t) n) (str (subs t 0 n) \"…\") t)))] "
         "{:ok? true :turns turns :evals (count evs) :errors (count errs) :has-edits? (boolean (:has-edits? ed)) "
         ":merged-tx (:seon.agent/merged-tx ma) :paused? (boolean (:seon.agent/paused? ma)) "
         ":trace (mapv (fn [e] (cond-> {:turn (:seon.agent.eval/turn e) :intent (:seon.agent.eval/intent e) :code (clip (:seon.agent.eval/code e) 220)} "
         "(not (clojure.string/blank? (str (:seon.agent.eval/error e)))) (assoc :error (clip (:seon.agent.eval/error e) 220)) "
         "(and (clojure.string/blank? (str (:seon.agent.eval/code e))) (not (clojure.string/blank? (str (:seon.agent.eval/thought e))))) (assoc :thought (clip (:seon.agent.eval/thought e) 260)))) evs)})")))

(defn- ^:seon.flow/edge probe-context-and-corpus [inst aid branch check-corp?]
  (let [sim (eval-edn inst
              (str "(let [conn (user/conn) mdb (user/main-db) bdb (or (when ((set (datahike.api/branches conn)) " (pr-str branch) ") (seon.store/head conn " (pr-str branch) ")) mdb) "
                   "sim (seon.repl/simulate-context " (pr-str aid) " {:db bdb :main-db mdb :conn conn :wire? false}) "
                   "ch (try (seon.code/changed-form-ops conn (datahike.api/merge-base conn :main " (pr-str branch) ") (datahike.api/commit-id bdb)) (catch Throwable _ {})) "
                   "mr (into #{} (map :seon.rule/id) (seon.rule/load-rules mdb)) "
                   "br (into #{} (map :seon.rule/id) (seon.rule/load-rules bdb))] "
                   "{:ok? true :selected-count (count (:selected sim)) :tokens (:tokens sim) :diff-vs-main (:diff-vs-main sim) "
                   ":selected-refs (mapv :ref (take 16 (:selected sim))) "
                   ":upserted-forms (vec (sort (keep :seon.code.form/key (:upserts ch)))) "
                   ":new-rules (vec (sort (remove mr br)))})"))
        corp (when check-corp?
               (eval-edn inst
                 (str "(let [conn (user/conn) mdb (user/main-db) bdb (or (when ((set (datahike.api/branches conn)) " (pr-str branch) ") (seon.store/head conn " (pr-str branch) ")) mdb) "
                      "cc (seon.repl/check-corpus {:db bdb :main-db mdb})] "
                      "{:corpus-score (:corpus-score cc) :corpus-ok? (get-in cc [:delta-vs-main :ok?]) :generality-issues (:generality-issues cc)})")))]
    (merge sim corp)))

(defn should-intervene?
  "Pure trigger predicate deciding when the Supervisor pauses a running Worker and forks `:template/tune-context`."
  {:malli/schema [:=> {:examples [[[{:status :runnable :turns 5 :errors 2 :has-edits? false} {:trigger-on-errors 2 :trigger-on-empty-turns 8}] :error-threshold]
                                  [[{:status :runnable :turns 8 :errors 0 :has-edits? false} {:trigger-on-errors 2 :trigger-on-empty-turns 8}] :empty-turns]
                                  [[{:status :merged :turns 6 :errors 0 :has-edits? true} {:trigger-on-errors 2 :trigger-on-empty-turns 8}] nil]
                                  [[{:status :waiting :turns 4 :errors 1 :has-edits? false} {:trigger-on-errors 2 :trigger-on-empty-turns 8}] :settled-not-merged]]}
                  [:cat :map :map] [:maybe :keyword]]
   :cost/ticks 50000}
  [{:keys [status turns errors has-edits?]} {:keys [trigger-on-errors trigger-on-empty-turns force-tune-after-turns]}]
  (let [t (long (or turns 0)) e (long (or errors 0))
        err-lim (long (or trigger-on-errors 2))
        emp-lim (long (or trigger-on-empty-turns 8))]
    (cond
      (= :merged status) nil
      (and (pos-int? force-tune-after-turns) (>= t (long force-tune-after-turns))) :forced-turn
      (and (pos? err-lim) (>= e err-lim)) :error-threshold
      (and (pos? emp-lim) (>= t emp-lim) (not has-edits?)) :empty-turns
      (contains? #{:waiting :gate-blocked :failed} status) :settled-not-merged
      :else nil)))

(defn- ^:seon.flow/edge run-tuner-intervention! [inst tid worker-id worker-br tuner-id watch opts t0]
  (let [{:keys [tuner-timeout-ms resume-timeout-ms tuner-max-turns]} opts
        _ (cluster/instance-op! inst {:op :transact :tx-data [{:seon.agent/id worker-id :seon.agent/paused? true}] :reason :lab-pause-worker})
        _ (a/<!! (a/timeout 1500))
        pre-det (probe-agent-detail inst worker-id worker-br)
        pre-ctx (probe-context-and-corpus inst worker-id worker-br false)
        err-hint (->> (:trace pre-det) (keep :error) last)
        tuner-goal (str "Supervisor Pause-and-Fork for " worker-id " (trigger: " (name (:reason watch))
                        ", turns: " (:turns pre-det) ", errors: " (:errors pre-det)
                        (when err-hint (str ", latest error: " err-hint))
                        "). DO NOT solve " worker-id "'s task yourself! Diagnose why " worker-id " struggled, improve the GENERAL context system (Lever 1 rules / Lever 2 renderers / Lever 3 schemas), verify <target-context-preview> + (user/check-corpus) 12/12 (0 generality issues), roll back bad turns via @user/ctx, and reply concisely to merge back and resume " worker-id ".")
        spawn-res (cluster/instance-op! inst {:op :spawn-agent :id tuner-id :fork-from [:seon.agent/id worker-id]
                                              :merge-target [:seon.agent/id worker-id] :tasks [:template/tune-context]
                                              :auto-merge? true :pause-parent? true
                                              :config {:seon.config/max-tool-turns tuner-max-turns} :goal tuner-goal})
        tuner-br (:branch spawn-res)
        tuner-wait (cluster/wait-for-agent! inst tuner-id tuner-timeout-ms)
        tuner-row (some #(when (= tuner-id (:id %)) %) (get-in tuner-wait [:status :agents]))
        tuner-det (probe-agent-detail inst tuner-id tuner-br)
        post-tune-w (probe-agent-detail inst worker-id worker-br)
        post-tune-ctx (probe-context-and-corpus inst worker-id worker-br true)
        _ (when (:paused? post-tune-w)
            (cluster/instance-op! inst {:op :transact :tx-data [{:seon.agent/id worker-id :seon.agent/paused? false}] :reason :lab-resume-worker}))
        resume-wait (cluster/wait-for-agent! inst worker-id resume-timeout-ms)
        final-row (some #(when (= worker-id (:id %)) %) (get-in resume-wait [:status :agents]))
        final-det (probe-agent-detail inst worker-id worker-br)
        final-health (cluster/instance-op! inst {:op :health})]
    {:ok? (= :merged (:status final-row)) :phase :supervised-cycle
     :task tid :worker worker-id :tuner tuner-id :trigger (:reason watch)
     :merged? (= :merged (:status final-row)) :final-worker-status (:status final-row)
     :tuner-status (:status tuner-row) :tuner-merged? (= :merged (:status tuner-row))
     :rolled-back-evals? (< (long (:evals post-tune-w 0)) (long (:evals pre-det 0)))
     :pre-worker pre-det :pre-context pre-ctx
     :tuner-detail tuner-det :post-tune-worker-evals (:evals post-tune-w) :post-tune-context post-tune-ctx
     :final-worker final-det :health-ok? (boolean (:ok? final-health))
     :elapsed-s (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e8)) 10.0)}))

(defn- ^:seon.flow/edge watch-worker-until-trigger! [inst worker-id worker-br timeout-ms trig-opts]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (a/<!! (a/timeout 1500))
      (let [st (cluster/instance-op! inst {:op :status})
            row (some #(when (= worker-id (:id %)) %) (:agents st))
            wstatus (or (:status row) :runnable)
            det (probe-agent-detail inst worker-id worker-br)
            reason (should-intervene? (assoc det :status wstatus) trig-opts)]
        (cond
          (= :merged wstatus) {:outcome :cold-merged :status wstatus :detail det}
          reason {:outcome :intervene :reason reason :status wstatus :detail det}
          (>= (System/currentTimeMillis) deadline) {:outcome :intervene :reason :timeout :status wstatus :detail det}
          :else (recur))))))

(defn- execute-supervisor-cycle! [opts]
  (let [{:keys [task timeout-ms tuner-timeout-ms resume-timeout-ms seed-store keep-dir?
                max-tool-turns tuner-max-turns trigger-on-errors trigger-on-empty-turns force-tune-after-turns]
         :or {timeout-ms 180000 tuner-timeout-ms 210000 resume-timeout-ms 180000 seed-store "runs/store"
              keep-dir? false max-tool-turns 20 tuner-max-turns 14 trigger-on-errors 2 trigger-on-empty-turns 8}} opts
        inner (if (map? (:task task)) (:task task) (or task opts))
        tid (or (:id inner) :lab/task)
        slug (str/replace (name tid) #"[^A-Za-z0-9._-]" "-")
        worker-id (keyword "bench" (str "w-" slug))
        worker-br (keyword "branch" (str "w-" slug))
        tuner-id (keyword "tune" (str "t-" slug))
        t0 (System/nanoTime)
        inst (cluster/start-instance! {:id (keyword "lab" slug) :seed-store seed-store :max-os-procs 3 :max-test-workers 2 :timeout-ms 60000})]
    (try
      (let [pre-ags (:agents (cluster/instance-op! inst {:op :status}))
            p-tx (vec (keep #(when (and (:id %) (not= :paused (:status %))) {:seon.agent/id (:id %) :seon.agent/paused? true}) pre-ags))]
        (when (seq p-tx) (cluster/instance-op! inst {:op :transact :tx-data p-tx :reason :lab-isolate})))
      (cluster/instance-op! inst (assoc (select-keys inner [:id :scope :title :plan :tests :needs]) :op :create-task))
      (cluster/instance-op! inst (cond-> {:op :spawn-agent :id worker-id :branch worker-br :task tid
                                          :goal (str (or (:title inner) (name tid))) :auto-merge? true
                                          :config {:seon.config/auto-refine-tasks? false :seon.config/max-tool-turns max-tool-turns}}
                                   (:scope inner) (assoc :scope (:scope inner))))
      (cluster/instance-op! inst {:op :send-message :from :user :to worker-id :type :chat
                                  :body "Work your assigned task, verify your changes pass tests and health, then reply concisely with your summary."})
      (let [watch (watch-worker-until-trigger! inst worker-id worker-br timeout-ms
                                               {:trigger-on-errors trigger-on-errors :trigger-on-empty-turns trigger-on-empty-turns
                                                :force-tune-after-turns force-tune-after-turns})]
        (if (= :cold-merged (:outcome watch))
          {:ok? true :phase :cold-merged :task tid :worker worker-id :merged? true
           :worker-pre (:detail watch) :elapsed-s (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e8)) 10.0)}
          (run-tuner-intervention! inst tid worker-id worker-br tuner-id watch
                                   {:tuner-timeout-ms tuner-timeout-ms :resume-timeout-ms resume-timeout-ms :tuner-max-turns tuner-max-turns} t0)))
      (finally
        (cluster/stop-instance! inst {:keep-dir? keep-dir?})))))

(defn- publish-lab-run! [coord-url run-id title tid started-ms status trial-map finished-ms]
  (let [ent (cond-> {:seon.bench.run/id run-id
                     :seon.bench.run/title title
                     :seon.bench.run/suite-id :lab/supervisor-cycle
                     :seon.bench.run/status status
                     :seon.bench.run/started-ms (long started-ms)
                     :seon.bench.run/updated-ms (System/currentTimeMillis)
                     :seon.bench.run/total 1
                     :seon.bench.run/passed (if (= :passed status) 1 0)
                     :seon.bench.run/failed (if (= :failed status) 1 0)
                     :seon.bench.run/trials [(assoc trial-map :pair-id tid :arm :supervised)]}
              finished-ms (assoc :seon.bench.run/finished-ms (long finished-ms)))]
    (try (cluster/instance-op! {:base-url coord-url}
                               {:op :transact :tx-data [ent] :reason [:lab-run run-id]})
         (catch Throwable _ nil))))

(defn ^:seon.flow/edge run-supervisor-cycle!
  "Live Pause-and-Fork Supervisor Lab: runs cold Worker on `:task`, intervenes with `:template/tune-context` on error/stall/miss, verifies rollback + context diff + corpus, and resumes Worker. Pass `:sync? false` to run asynchronously and stream progress to `:seon.bench.run/entity` (inspectable with `(user/bench-run run-id)`)."
  {:seon.repl/export true
   :malli/schema [:=> [:cat :map] :map]
   :seon.eval/budget-override {:ticks 2000000000 :bytes 1073741824}}
  [opts]
  (if (false? (:sync? opts))
    (let [inner (if (map? (:task (:task opts))) (:task (:task opts)) (or (:task opts) opts))
          tid (or (:id inner) :lab/task)
          run-id (random-uuid)
          started-ms (System/currentTimeMillis)
          title (or (:title opts) (str "Supervisor Cycle: " tid))
          port (or (:coordinator-port opts) (System/getenv "SEON_PORT") 9998)
          coord-url (str "http://127.0.0.1:" port)]
      (publish-lab-run! coord-url run-id title tid started-ms :running
                        {:id tid :status :booting :evals 0 :elapsed-ms 0 :merged? false :health-ok? false :alive? true}
                        nil)
      (a/thread
        (try
          (let [res (execute-supervisor-cycle! opts)
                ok? (boolean (:ok? res))
                ms (long (* 1000.0 (double (or (:elapsed-s res) 0.0))))]
            (publish-lab-run! coord-url run-id title tid started-ms (if ok? :passed :failed)
                              (assoc res :id tid :status (if (:merged? res) :merged :failed)
                                     :elapsed-ms ms :alive? false)
                              (System/currentTimeMillis)))
          (catch Throwable ex
            (publish-lab-run! coord-url run-id title tid started-ms :failed
                              {:id tid :status :failed :last-error (or (.getMessage ex) (str ex))
                               :merged? false :health-ok? false :alive? false :elapsed-ms (- (System/currentTimeMillis) started-ms)}
                              (System/currentTimeMillis)))))
      {:ok? true :run-id run-id :status :running :task tid :title title})
    (execute-supervisor-cycle! opts)))

(defn summarize-bench-run
  "Formats a `:seon.bench.run/*` entity map into a crisp summary with KV hit %, per-trial outcomes, `:error-samples`, and `:failing-tests`."
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]
   :cost/ticks 2000000}
  [r]
  (when (map? r)
    (let [raw-ts (:seon.bench.run/trials r)
          ts (if (string? raw-ts) (try (edn/read-string raw-ts) (catch Throwable _ [])) (or raw-ts []))
          px (:seon.bench.run/proxy-stats r)
          pt (long (or (:prompt-tokens px) 0))
          ht (long (or (:cache-hit-tokens px) 0))
          s-ms (:seon.bench.run/started-ms r)
          f-ms (:seon.bench.run/finished-ms r)]
      {:id (:seon.bench.run/id r)
       :title (:seon.bench.run/title r)
       :suite-id (:seon.bench.run/suite-id r)
       :status (:seon.bench.run/status r)
       :passed (:seon.bench.run/passed r)
       :failed (:seon.bench.run/failed r)
       :total (:seon.bench.run/total r)
       :elapsed-s (when (and s-ms f-ms) (/ (Math/round (/ (double (- f-ms s-ms)) 100.0)) 10.0))
       :kv-hit-pct (when (pos? pt) (/ (Math/round (* 1000.0 (/ (double ht) pt))) 10.0))
       :proxy px
       :trials (mapv #(select-keys % [:id :pair-id :arm :status :merged? :turns :evals :elapsed-ms
                                      :health-ok? :eval-errors :total-kv-hit-pct :last-error
                                      :error-samples :failing-tests :phase :trigger :tuner-merged? :rolled-back-evals?])
                     ts)})))

(defn ^:seon.flow/edge bench-run
  "Inspects live `:seon.bench.run/*` runs directly from `:main` (never a stale branch snapshot). Pass no args/`:latest` for the newest run, a UUID for a specific run, or `:list` for the last 10 runs."
  {:seon.repl/export true
   :malli/schema [:function [:=> [:cat] :any] [:=> [:cat :any] :any]]
   :cost/ticks 10000000}
  ([] (bench-run :latest))
  ([which]
   (let [ctx (some-> (requiring-resolve 'seon.fork.calls/*agent-ctx*) deref)
         conn (:conn ctx)
         mdb (when conn (or (store/head conn :main) @conn))]
     (when (and mdb (contains? (:schema mdb) :seon.bench.run/id))
       (cond
         (uuid? which)
         (summarize-bench-run (d/pull mdb '[*] [:seon.bench.run/id which]))

         (= :list which)
         (->> (d/datoms mdb :aevt :seon.bench.run/id)
              (map #(d/pull mdb '[:seon.bench.run/id :seon.bench.run/title :seon.bench.run/status
                                  :seon.bench.run/passed :seon.bench.run/failed :seon.bench.run/total
                                  :seon.bench.run/started-ms :seon.bench.run/finished-ms] (:e %)))
              (sort-by #(long (or (:seon.bench.run/started-ms %) 0)) >)
              (take 10)
              vec)

         :else
         (let [latest (->> (d/datoms mdb :aevt :seon.bench.run/id)
                           (map #(d/pull mdb '[:seon.bench.run/id :seon.bench.run/started-ms] (:e %)))
                           (sort-by #(long (or (:seon.bench.run/started-ms %) 0)) >)
                           first)]
           (when (:seon.bench.run/id latest)
             (summarize-bench-run (d/pull mdb '[*] [:seon.bench.run/id (:seon.bench.run/id latest)])))))))))
