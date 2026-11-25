(ns seon.bench.helpers
  "Extracted child namespace of `seon.bench.cluster`."
  (:require [jolt.fs :as fs]
            [datahike.api :as d]
            [seon.store :as store :refer [db-schema]]
            [seon.render.text :refer [round1]]
            [seon.fork.calls :as fcalls]
            [clojure.string :as str]))

(defn parse-listening-port
  "Extracts the bound localhost integer port from a Seon startup log line, or nil."
  {:malli/schema [:=> {:examples [[["SEON LISTENING http://127.0.0.1:54321 | Headless | Store /tmp/s"] 54321]
                                  [["Booting Seon..."] nil]
                                  [[""] nil]]}
                  [:cat :string] [:maybe :int]]
   :cost/ticks 250000
   :cost/bytes 2097152}
  [line]
  (when-let [[_ port-str] (re-find #"SEON LISTENING https?://[^:\s]+:(\d+)" line)]
    (parse-long port-str)))

(defn build-instance-cmd
  "Builds the CLI argv vector for spawning an isolated headless `target/seon` benchmark instance."
  {:malli/schema [:=> {:examples [[[{:bin-path "/app/target/seon"
                                     :store-path "/tmp/s1"
                                     :repo-dir "/app"
                                     :model-stub? true}]
                                   ["/app/target/seon"
                                    "--port" "0"
                                    "--headless"
                                    "--store" "/tmp/s1"
                                    "--repo" "/app"
                                    "--commit-git" "false"
                                    "--auto-switch-over" "false"
                                    "--model-stub"]]]}
                  [:cat :map] [:vector :string]]
   :cost/ticks 500000
   :cost/bytes 4194304}
  [{:keys [bin-path port store-path seed-store repo-dir headless? commit-git? auto-switch-over?
           model-base-url model model-stub? max-os-procs max-test-workers]
    :or {port 0 headless? true commit-git? false auto-switch-over? false}}]
  (cond-> [(str (or bin-path "target/seon"))
           "--port" (str (long (or port 0)))]
    headless? (conj "--headless")
    store-path (conj "--store" (str store-path))
    seed-store (conj "--seed-store" (str seed-store))
    repo-dir (conj "--repo" (str repo-dir))
    (some? commit-git?) (conj "--commit-git" (if commit-git? "true" "false"))
    (some? auto-switch-over?) (conj "--auto-switch-over" (if auto-switch-over? "true" "false"))
    model-base-url (conj "--model-base-url" (str model-base-url))
    model (conj "--model" (str model))
    model-stub? (conj "--model-stub")
    max-os-procs (conj "--max-os-procs" (str (long max-os-procs)))
    max-test-workers (conj "--max-test-workers" (str (long max-test-workers)))))

(defn ^:seon.flow/edge jolt-env-map
  "Builds the child-process env overlay map for isolated Jolt/Seon instances (`SEON_BINARY`, `JOLT_*_LIB`, plus `extra-env`)."
  {:malli/schema [:=> [:cat :any :any] [:map-of :string :string]]}
  [repo-dir extra-env]
  (let [lib-dir (fs/path (str (or repo-dir ".")) "target/lib")
        base-env (reduce (fn [acc [env-k base]]
                           (if (System/getenv env-k)
                             acc
                             (let [dylib (fs/path lib-dir (str base ".dylib"))
                                   so (fs/path lib-dir (str base ".so"))]
                               (cond
                                 (fs/regular-file? dylib) (assoc acc env-k (str (fs/absolutize dylib)))
                                 (fs/regular-file? so) (assoc acc env-k (str (fs/absolutize so)))
                                 :else acc))))
                         {"SEON_BINARY" "1"}
                         [["JOLT_LZ4_LIB" "liblz4"]
                          ["JOLT_ZSTD_LIB" "libzstd"]
                          ["JOLT_LMDB_LIB" "liblmdb"]])]
    (if (map? extra-env)
      (reduce-kv (fn [acc k v] (if (and k (some? v)) (assoc acc (str k) (str v)) acc))
                 base-env extra-env)
      base-env)))

(defn configure-jolt-env!
  "Populates `ProcessBuilder` env from `jolt-env-map`."
  {:malli/schema [:=> [:cat :any :any :any] :any], :seon.flow/edge true}
  [^ProcessBuilder pb repo-dir extra-env]
  (when (instance? ProcessBuilder pb)
    (let [env (.environment pb)]
      (doseq [[k v] (jolt-env-map repo-dir extra-env)]
        (.put env k v)))))

(defn- child-agent-active? [ag]
  (and (map? ag)
       (not= :merged (:status ag))
       (not (:paused? ag))
       (or (:busy? ag)
           (:needs-turn? ag)
           (:needs-test? ag)
           (:needs-merge? ag)
           (pos? (long (or (:unread-count ag) 0)))
           (zero? (long (or (:evals ag) 0))))))

(defn agent-settled?
  "Returns true when `agent-id` in a `/op {:op :status}` snapshot has executed at least one eval
   (or merged) and has no pending turns, unread messages, awaits, tests, merges, or active forked/child agents."
  {:malli/schema [:=> {:examples [[[{:agents [{:id :w/a :evals 2 :busy? false :unread-count 0
                                               :awaiting-count 0 :needs-turn? false :needs-test? false
                                               :needs-merge? false :status :waiting}]}
                                   :w/a]
                                  true]
                                 [[{:agents [{:id :w/a :evals 0 :busy? true :unread-count 1
                                               :awaiting-count 0 :needs-turn? true :needs-test? false
                                               :needs-merge? false :status :waiting}]}
                                   :w/a]
                                  false]
                                 [[{:agents [{:id :w/a :evals 1 :paused? true :busy? false :unread-count 0
                                               :awaiting-count 0 :needs-turn? false :needs-test? false
                                               :needs-merge? false :status :paused}
                                             {:id :refine/a :forked-from :w/a :evals 2 :paused? false
                                              :busy? true :status :working}]}
                                   :w/a]
                                  false]
                                 [[{:agents []} :w/missing]
                                  false]]}
                  [:cat :map :keyword] :boolean]
   :cost/ticks 500000
   :cost/bytes 4194304}
  [status-map agent-id]
  (boolean
   (let [agents (:agents status-map)]
     (when-let [ag (some #(when (= agent-id (:id %)) %) agents)]
       (let [active-child? (some (fn [c]
                                   (and (not= agent-id (:id c))
                                        (or (= agent-id (:parent c)) (= agent-id (:forked-from c)))
                                        (child-agent-active? c)))
                                 agents)]
         (and (not active-child?)
              (not (:busy? ag))
              (zero? (long (or (:unread-count ag) 0)))
              (zero? (long (or (:awaiting-count ag) 0)))
              (not (:needs-turn? ag))
              (not (:needs-test? ag))
              (not (:needs-merge? ag))
              (or (= :merged (:status ag))
                  (pos? (long (or (:evals ag) 0))))))))))

(defn- pull-compact-evals [conn main-db brs aid b]
  (let [as-l #(long (or % 0))
        dbx (if (and b (contains? brs b)) (store/head conn b) main-db)]
    (->> (d/datoms dbx :avet :seon.agent.eval/agent aid)
         (map #(d/pull dbx '[:seon.agent.eval/turn :seon.agent.eval/call-index
                             :seon.agent.eval/intent :seon.agent.eval/error
                             :seon.agent.eval/prompt-tokens :seon.agent.eval/cache-hit-tokens] (:e %)))
         (sort-by (juxt #(as-l (:seon.agent.eval/turn %)) #(as-l (:seon.agent.eval/call-index %))))
         (mapv (fn [e]
                 (cond-> {:t (as-l (:seon.agent.eval/turn e))
                          :i (or (:seon.agent.eval/intent e) "")
                          :pt (as-l (:seon.agent.eval/prompt-tokens e))
                          :hit (as-l (:seon.agent.eval/cache-hit-tokens e))}
                   (:seon.agent.eval/error e)
                   (assoc :err (subs (str (:seon.agent.eval/error e))
                                     0 (min 120 (count (str (:seon.agent.eval/error e))))))))))))

(defn- clip-str [s max-n]
  (let [t (str/trim (str (or s "")))]
    (if (> (count t) max-n) (str (subs t 0 max-n) "…") t)))

(defn- pull-failure-diagnostics [conn main-db brs aid br]
  (let [dbx (if (and br (contains? brs br)) (store/head conn br) main-db)
        sch (db-schema dbx)
        err-samples (if (sch :seon.agent.eval/agent)
                      (->> (d/datoms dbx :avet :seon.agent.eval/agent aid)
                           (map #(d/pull dbx '[:seon.agent.eval/turn :seon.agent.eval/code :seon.agent.eval/error] (:e %)))
                           (filter #(not (str/blank? (str (:seon.agent.eval/error %)))))
                           (sort-by #(long (or (:seon.agent.eval/turn %) 0)))
                           (take 6)
                           (mapv (fn [e]
                                   {:turn (long (or (:seon.agent.eval/turn e) 0))
                                    :code (clip-str (:seon.agent.eval/code e) 110)
                                    :error (clip-str (:seon.agent.eval/error e) 200)})))
                      [])
        fail-tests (if (sch :seon.test/pass?)
                     (->> (d/datoms dbx :avet :seon.test/pass? false)
                          (map #(d/pull dbx '[:seon.test/var :seon.test/report :seon.test/digest] (:e %)))
                          (filter :seon.test/var)
                          (take 5)
                          (mapv (fn [v]
                                  {:var (:seon.test/var v)
                                   :report (clip-str (:seon.test/report v) 220)})))
                     [])]
    {:error-samples err-samples :failing-tests fail-tests}))

(defn ^:seon.flow/edge trial-audit-summary
  "Runs inside an isolated trial instance before teardown: combines `seon.health.metrics/agent-friction-summary`
   for `agent-id` with honest end-to-end roll-ups across all forked/child agents, worker backward-walk row count,
   newly added scope/test var counts on isolated `:main`, and compact `:error-samples` + `:failing-tests` diagnostics."
  {:malli/schema [:=> [:cat :any :any :keyword [:? [:maybe :map]]] :map]}
  ([conn main-db agent-id] (trial-audit-summary conn main-db agent-id nil))
  ([conn main-db agent-id opts]
   (let [o (if (map? opts) opts {})
         afn (requiring-resolve 'seon.health.metrics/agent-friction-summary)
         base (if afn (afn conn main-db agent-id o) {})
         ag-eid (:e (first (d/datoms main-db :avet :seon.agent/id agent-id)))
         child-ids (if-not ag-eid
                     []
                     (vec (distinct
                           (keep (fn [dt]
                                   (let [cid (:v (first (d/datoms main-db :eavt (:e dt) :seon.agent/id)))]
                                     (when (and (keyword? cid) (not= cid agent-id)) cid)))
                                 (concat (when ((:schema main-db) :seon.agent/forked-from)
                                           (d/datoms main-db :avet :seon.agent/forked-from ag-eid))
                                         (when ((:schema main-db) :seon.agent/parent)
                                           (d/datoms main-db :avet :seon.agent/parent agent-id)))))))
         child-rows (mapv #(if afn (afn conn main-db % (dissoc o :branch)) {}) child-ids)
         as-l #(long (or % 0))
         c-turns (reduce + 0 (map #(as-l (:turns %)) child-rows))
         c-evals (reduce + 0 (map #(as-l (:evals %)) child-rows))
         c-pt (reduce + 0 (map #(as-l (:prompt-tokens %)) child-rows))
         c-ct (reduce + 0 (map #(as-l (:completion-tokens %)) child-rows))
         c-hit (reduce + 0 (map #(as-l (:cache-hit-tokens %)) child-rows))
         c-miss (reduce + 0 (map #(as-l (:cache-miss-tokens %)) child-rows))
         tot-pt (+ (as-l (:prompt-tokens base)) c-pt)
         tot-hit (+ (as-l (:cache-hit-tokens base)) c-hit)
         tot-miss (+ (as-l (:cache-miss-tokens base)) c-miss)
         tot-kv (cond (pos? (+ tot-hit tot-miss)) (round1 (/ (* 100.0 tot-hit) (+ tot-hit tot-miss)))
                      (pos? tot-pt) (round1 (/ (* 100.0 tot-hit) tot-pt))
                      :else nil)
         br (or (:branch o) (:branch base))
         brs (when conn (set (d/branches conn)))
         wdb (if (and br (contains? brs br)) (store/head conn br) main-db)
         bw-fn (requiring-resolve 'seon.ctx.graph/proof-backward-rows)
         bw-rows (if bw-fn (long (count (try (bw-fn main-db wdb agent-id) (catch Throwable _ [])))) 0)
         after-mtx (as-l (:after-main-tx o))
         scope (:scope o)
         new-scope-vars (if (and scope (pos? after-mtx) ((:schema main-db) :seon.code.var/ns))
                          (long (count (filter #(> (long (:tx %)) after-mtx)
                                               (d/datoms main-db :avet :seon.code.var/ns scope))))
                          0)
         new-test-vars (if (and (pos? after-mtx) ((:schema main-db) :seon.code.var/test?))
                         (long (count (filter #(> (long (:tx %)) after-mtx)
                                              (d/datoms main-db :avet :seon.code.var/test? true))))
                         0)
         diag (pull-failure-diagnostics conn main-db brs agent-id br)
         compact-evs (when (:include-trace? o)
                       {:worker (pull-compact-evals conn main-db brs agent-id br)
                        :children (mapv (fn [cid cr] {:id cid :merged? (boolean (:merged? cr))
                                                      :turns (as-l (:turns cr)) :kv (:kv-cache-hit-pct cr)
                                                      :evals (pull-compact-evals conn main-db brs cid (:branch cr))})
                                        child-ids child-rows)})]
     (cond-> (assoc base
                    :child-count (long (count child-rows))
                    :child-merged? (boolean (some :merged? child-rows))
                    :child-turns c-turns
                    :child-evals c-evals
                    :total-turns (+ (as-l (:turns base)) c-turns)
                    :total-evals (+ (as-l (:evals base)) c-evals)
                    :total-prompt-tokens tot-pt
                    :total-completion-tokens (+ (as-l (:completion-tokens base)) c-ct)
                    :total-kv-hit-pct tot-kv
                    :backward-walk-rows bw-rows
                    :new-scope-vars new-scope-vars
                    :new-test-vars new-test-vars)
       (seq (:error-samples diag)) (assoc :error-samples (:error-samples diag))
       (seq (:failing-tests diag)) (assoc :failing-tests (:failing-tests diag))
       compact-evs (assoc :eval-trace compact-evs)))))

(def telemetry-numeric-pairs
  [[:turns :turns] [:cache-hit-tokens :kv-hit-tokens] [:cache-miss-tokens :kv-miss-tokens]
   [:prompt-tokens :prompt-tokens] [:completion-tokens :completion-tokens]
   [:prefix-hash-count :prefix-hashes] [:prefix-busts :prefix-busts] [:ooms :ooms]
   [:eval-errors :eval-errors] [:upstream-errors :upstream-errors]
   [:rogue-fs-count :rogue-fs-count] [:no-match-count :no-match-count]
   [:delegated-calls :delegated-calls] [:settled-delegated-calls :settled-delegated-calls]
   [:child-turns :child-turns] [:child-evals :child-evals] [:total-turns :total-turns]
   [:total-prompt-tokens :total-prompt-tokens] [:total-completion-tokens :total-completion-tokens]
   [:backward-walk-rows :backward-walk-rows] [:new-scope-vars :new-scope-vars] [:new-test-vars :new-test-vars]])

(defn trial-telemetry
  "Projects optional per-trial friction, failure diagnostics & multi-agent audit telemetry from `trace` and `category`."
  {:malli/schema [:=> [:cat [:maybe :keyword] [:maybe :map]] :map] :cost/ticks 400}
  [category trace]
  (let [trace-map (if (map? trace) trace {})]
    (reduce (fn [acc [src-key dst-key]]
              (let [val (get trace-map src-key)]
                (if (number? val) (assoc acc dst-key (long val)) acc)))
            (cond-> {}
              category (assoc :category category)
              (boolean? (:turn-overrun? trace-map)) (assoc :turn-overrun? (:turn-overrun? trace-map))
              (boolean? (:child-merged? trace-map)) (assoc :child-merged? (:child-merged? trace-map))
              (number? (:total-kv-hit-pct trace-map)) (assoc :total-kv-hit-pct (double (:total-kv-hit-pct trace-map)))
              (seq (:error-samples trace-map)) (assoc :error-samples (:error-samples trace-map))
              (seq (:failing-tests trace-map)) (assoc :failing-tests (:failing-tests trace-map))
              (map? (:eval-trace trace-map)) (assoc :eval-trace (:eval-trace trace-map)))
            telemetry-numeric-pairs)))

(defn- settled-call-count [final-trace]
  (if (and (map? final-trace) (contains? final-trace :settled-delegated-calls))
    (long (or (:settled-delegated-calls final-trace) 0))
    (long (or (:delegated-calls final-trace) 0))))

(defn trial-failure
  "Renders the first human-readable failure reason for a trial, or nil when green."
  {:malli/schema [:=> [:cat [:map [:timeout-ms :int] [:agent-res [:maybe :map]] [:test-res [:maybe :map]]
                             [:merge-res [:maybe :map]] [:ag-snap [:maybe :map]] [:final-trace [:maybe :map]]
                             [:health-res [:maybe :map]] [:halted-not-merged? :boolean]
                             [:task {:optional true} [:maybe :map]]]] [:maybe :string]] :cost/ticks 15000000}
  [{:keys [timeout-ms agent-res test-res merge-res ag-snap final-trace health-res halted-not-merged? task]}]
  (let [min-delegated (long (or (:min-delegated-calls task) 0))
        settled-calls (settled-call-count final-trace)]
    (or (when (= :timeout (get-in agent-res [:wait :error]))
          (str "Timed out after " (long timeout-ms) " ms"))
        (when (and test-res (not (:ok? test-res)))
          (let [first-fail (first (remove :seon.test/pass? (:results test-res)))
                first-conflict (first (:conflicts test-res))]
            (str "test-failed: "
                 (or (:seon.test/var first-fail)
                     (when first-conflict (str (:reason first-conflict) " " (or (:message first-conflict) (:entity first-conflict))))
                     (:error test-res) "red"))))
        (when (and merge-res (not (:ok? merge-res)))
          (str "merge-failed: " (or (:error merge-res) (:message merge-res) "blocked")))
        (when halted-not-merged?
          (or (:last-error final-trace)
              (str "not-merged (status " (:status ag-snap) ", last " (:last-snippet final-trace) ")")))
        (when (and (pos? min-delegated) (< settled-calls min-delegated))
          (str "delegated-calls-short: " settled-calls "/" min-delegated " settled"))
        (when (and (:chat-agent task) (clojure.string/blank? (str (or (:last-reply final-trace) ""))))
          "chat-agent produced empty reply")
        (when (not (:ok? health-res))
          (str "health-err: " (or (:error health-res) (pr-str (take 2 (filter #(= :error (:severity %)) (:problems health-res)))))))
        (:last-error final-trace))))

(defn sample-random-tasks
  "Deterministically samples `n` diverse real tasks directly from live Datahike `db` (using `seed`) across
   3 pools (loose `:map` return contracts, entity schemas needing renderers, and pure namespace helpers)
   in O(V + S) indexed reads (<15 MB) so context-tuning never overfits to a fixed prompt set."
  {:malli/schema [:=> [:cat :seon.host/db [:? [:maybe :map]]] [:vector :map]]
   :cost/ticks 15000000}
  ([db] (sample-random-tasks db nil))
  ([db opts]
   (let [o (if (map? opts) opts {})
         n (max 1 (long (or (:n o) 4)))
         seed (long (or (:seed o) 42))
         timeout-ms (long (or (:timeout-ms o) 240000))
         det-shuffle (fn [xs] (vec (sort-by #(hash [seed (:id %)]) xs)))
         loose-vars
         (when (and ((db-schema db) :seon.code.var/name) ((db-schema db) :seon.code.var/schema))
           (->> (d/datoms db :aevt :seon.code.var/schema)
                (keep (fn [dt]
                        (let [e (:e dt)
                              sch (:v dt)
                              vnm (:v (first (d/datoms db :eavt e :seon.code.var/name)))
                              vns (:v (first (d/datoms db :eavt e :seon.code.var/ns)))
                              priv? (:v (first (d/datoms db :eavt e :seon.code.var/private?)))]
                          (when (and (qualified-symbol? vnm) (symbol? vns) (not priv?)
                                     (str/starts-with? (name vns) "seon.")
                                     (not (str/ends-with? (name vns) "-test"))
                                     (vector? sch) (= :=> (first sch))
                                     (= :map (last sch)))
                            (let [tid (keyword "rand" (str "contract-" (str/replace (name vnm) #"[^a-zA-Z0-9-]" "-")))]
                              {:id tid :category :schema-proof
                               :run-tests? true :merge? true :require-merge? true :timeout-ms timeout-ms
                               :task {:id tid :scope vns
                                      :title (str "Tighten loose :map return contract on " vnm)
                                      :plan (str "The public function `" vnm "` currently declares a loose `:map` return schema. Inspect its actual returned keys and callers, replace the loose `:map` return contract with explicit Malli keys (or a named schema), and verify all tests and seed=42 generative checks pass.")}})))))
                (sort-by #(str (:id %)))
                vec))
         ns-tasks
         (when ((db-schema db) :seon.code.ns/name)
           (->> (d/datoms db :aevt :seon.code.ns/name)
                (keep (fn [dt]
                        (let [nsym (:v dt)]
                          (when (and (symbol? nsym)
                                     (str/starts-with? (name nsym) "seon.")
                                     (not (str/ends-with? (name nsym) "-test"))
                                     (not (contains? '#{seon.main seon.fork} nsym)))
                            (let [tid (keyword "rand" (str "audit-" (str/replace (name nsym) "." "-")))]
                              {:id tid :category :ns-contract-audit
                               :run-tests? true :merge? true :require-merge? true :timeout-ms timeout-ms
                               :task {:id tid :scope nsym
                                      :title (str "Add Malli :examples and unit test coverage for a pure helper in " nsym)
                                      :plan (str "Inspect the pure public functions in `" nsym "`, pick one lacking concrete Malli `:examples` or direct unit test coverage, add crisp `:examples` to its `:malli/schema` plus a focused unit test verifying normal and edge cases, and prove everything green.")}})))))
                (sort-by #(str (:id %)))
                vec))
         schema-tasks
         (when ((db-schema db) :seon.schema/key)
           (->> (d/datoms db :aevt :seon.schema/key)
                (keep (fn [dt]
                        (let [sk (:v dt)]
                          (when (and (qualified-keyword? sk) (= "entity" (name sk))
                                     (str/starts-with? (namespace sk) "seon."))
                            (let [tid (keyword "rand" (str "render-" (str/replace (namespace sk) "." "-")))]
                              {:id tid :category :entity-renderers
                               :run-tests? true :merge? true :require-merge? true :timeout-ms timeout-ms
                               :task {:id tid :scope 'seon.render.entities
                                      :title (str "Verify/improve multi-footprint pure Hiccup renderers for " sk)
                                      :plan (str "Check renderer coverage and footprint distinctness for `" sk "` using `(user/coverage)`. Ensure it has clean compact and detailed pure `:seon.ui/hiccup` renderers with `:malli/schema` and unit test coverage.")}})))))
                (sort-by #(str (:id %)))
                vec))
         pools (filterv seq [(det-shuffle loose-vars) (det-shuffle ns-tasks) (det-shuffle schema-tasks)])]
     (vec
      (take n
            (loop [ps pools acc []]
              (if (or (>= (count acc) n) (empty? ps))
                acc
                (let [heads (keep first ps)
                      tails (filterv seq (map rest ps))]
                  (recur tails (into acc heads))))))))))

(defn ^:seon.flow/edge repl-sample-random-tasks
  "REPL helper (`user/sample-random-tasks`): samples `n` diverse real tasks from live Datahike."
  {:malli/schema [:function [:=> [:cat] [:vector :map]] [:=> [:cat [:maybe :map]] [:vector :map]]]
   :cost/ticks 20000000}
  ([] (repl-sample-random-tasks nil))
  ([opts]
   (let [ctx (some-> (requiring-resolve 'seon.fork.calls/*agent-ctx*) deref)
         db (or (:db opts) (when-let [c (:conn ctx)] @c))]
     (if db (sample-random-tasks db opts) []))))
