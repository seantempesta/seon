(ns seon.fork.worker-support
  "Pure test runner & agent worker turn-history/Hiccup helpers."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.store :as store]
            [seon.ctx :as ctx]
            [seon.schema :as schema]))

(defn apply-form-overlay!
  ([overlay] (code/apply-overlay! nil overlay))
  ([db overlay] (code/apply-overlay! db overlay)))

(defn run-single-test-var
  "Executes one `deftest` var with tick/byte/ms measurement and cost-budget enforcement.
   When `db` is provided and `test-sym`'s namespace is not yet loaded, hydrates it directly
   from Datahike `:seon.code.form/*` before falling back to `(require ns-sym)`."
  {:malli/schema [:function
                  [:=> [:cat :qualified-symbol :string] :map]
                  [:=> [:cat :any :qualified-symbol :string] :map]]}
  ([test-sym digest] (run-single-test-var nil test-sym digest))
  ([db test-sym digest]
   (let [ns-sym (symbol (namespace test-sym))
         sw (java.io.StringWriter.)
         counts (volatile! {:pass 0 :fail 0 :error 0 :messages []})
         measured
         (binding [*out* sw
                   *err* sw
                   t/*test-out* sw
                   t/report (fn [m]
                              (case (:type m)
                                :pass (vswap! counts update :pass inc)
                                :fail (vswap! counts #(-> % (update :fail inc)
                                                          (update :messages conj (str "FAIL: " (:message m "") " expected=" (pr-str (:expected m)) " actual=" (pr-str (:actual m))))))
                                :error (vswap! counts #(-> % (update :error inc)
                                                           (update :messages conj (str "ERROR: " (:message m "") " actual=" (pr-str (:actual m))))))
                                nil))]
           (code/measure-call
            (fn []
              (try
                (when-not (find-ns ns-sym)
                  (let [fs (when (and db (contains? (:schema db) :seon.code.form/ns))
                             (code/ns-forms db ns-sym))]
                    (if (seq fs)
                      (code/apply-overlay! db {:upserts fs})
                      (require ns-sym))))
                (if-let [v (resolve test-sym)]
                  (do (t/test-var v) v)
                  (do (vswap! counts #(-> % (update :error inc)
                                          (update :messages conj (str "Test var not found: " test-sym))))
                      nil))
                (catch Throwable ex
                  (vswap! counts #(-> % (update :error inc)
                                      (update :messages conj (str "UNCAUGHT: " (.getMessage ex)))))
                  nil)))))
         v (:val measured)
         vmeta (when (var? v) (meta v))
         ticks (:ticks measured)
         bytes (:bytes measured)
         ms (:ms measured)
         ticks-budget (or (:cost/ticks vmeta) (get-in vmeta [:seon.test/estimate :ticks]))
         bytes-budget (or (:cost/bytes vmeta) (get-in vmeta [:seon.test/estimate :bytes]))
         ms-budget (or (:cost/ms vmeta) (get-in vmeta [:seon.test/estimate :ms]))
         _ (when (and ticks-budget (> ticks ticks-budget))
             (vswap! counts #(-> % (update :fail inc)
                                 (update :messages conj
                                         (str "COST OVERRUN on " test-sym ": actual " ticks " ticks > estimated "
                                              ticks-budget " ticks (:cost/ticks). Optimize or update estimate.")))))
         _ (when (and bytes-budget (> bytes bytes-budget))
             (vswap! counts #(-> % (update :fail inc)
                                 (update :messages conj
                                         (str "BYTES OVERRUN on " test-sym ": actual " bytes " bytes > estimated "
                                              bytes-budget " bytes (:cost/bytes). Optimize or update estimate.")))))
         _ (when (and ms-budget (> ms ms-budget))
             (vswap! counts #(-> % (update :fail inc)
                                 (update :messages conj
                                         (str "TIME OVERRUN on " test-sym ": actual " ms " ms > estimated "
                                              ms-budget " ms (:cost/ms). Optimize or update estimate.")))))
         {:keys [fail error messages]} @counts
         pass? (zero? (+ fail error))]
     {:seon.test/digest digest
      :seon.test/var test-sym
      :seon.test/kind :deftest
      :seon.test/pass? pass?
      :seon.test/ms (double ms)
      :seon.test/ticks (long ticks)
      :seon.test/bytes (long bytes)
      :seon.test/report (if pass?
                          (let [s (str/trim (str sw))] (if (str/blank? s) "PASS" s))
                          (str/join "\n" (cond-> messages (not (str/blank? (str sw))) (conj (str/trim (str sw))))))})))

(defn unread-messages-for [main-db branch-db agent-id]
  (let [seen (store/agent-seen-message-ids branch-db agent-id)]
    (->> (d/datoms main-db :avet :seon.agent.message/to agent-id)
         (map #(d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq
                                 :seon.agent.message/from :seon.agent.message/to
                                 :seon.agent.message/type :seon.agent.message/body] (:e %)))
         (remove #(contains? seen (:seon.agent.message/id %)))
         (sort-by :seon.agent.message/seq)
         vec)))

(defn agent-history-turns [branch-db agent-id]
  (->> (d/datoms branch-db :avet :seon.agent.eval/agent agent-id)
       (map #(d/pull branch-db '[:seon.agent.eval/turn :seon.agent.eval/call-index
                                 :seon.agent.eval/call-id :seon.agent.eval/handle
                                 :seon.agent.eval/result-schema :seon.agent.eval/thought
                                 :seon.agent.eval/reasoning :seon.agent.eval/intent
                                 :seon.agent.eval/code :seon.agent.eval/value
                                 :seon.agent.eval/out :seon.agent.eval/error
                                 :seon.agent.eval/ms :seon.agent.eval/ticks] (:e %)))
       (group-by :seon.agent.eval/turn)
       (sort-by key)
       (mapv (fn [[turn es]]
               (let [ordered (sort-by #(or (:seon.agent.eval/call-index %) 0) es)
                     first-e (first ordered)]
                 {:turn turn
                  :thought (:seon.agent.eval/thought first-e)
                  :reasoning (:seon.agent.eval/reasoning first-e)
                  :calls (mapv (fn [e]
                                 {:id (or (:seon.agent.eval/call-id e) (str "t" turn "-c" (or (:seon.agent.eval/call-index e) 0)))
                                  :handle (:seon.agent.eval/handle e)
                                  :result-schema (:seon.agent.eval/result-schema e)
                                  :intent (:seon.agent.eval/intent e)
                                  :code (:seon.agent.eval/code e)
                                  :value (:seon.agent.eval/value e)
                                  :out (:seon.agent.eval/out e)
                                  :error (:seon.agent.eval/error e)
                                  :ms (:seon.agent.eval/ms e)
                                  :ticks (:seon.agent.eval/ticks e)})
                               ordered)})))))

(defn sanitize-edn-hiccup
  "Walks Hiccup returned by a branch-local render fn so it is guaranteed pure EDN for IPC transport."
  [x]
  (cond
    (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (symbol? x)) x
    (vector? x) (mapv sanitize-edn-hiccup x)
    (map? x) (into {} (map (fn [[k v]] [(sanitize-edn-hiccup k) (sanitize-edn-hiccup v)])) x)
    (list? x) (apply list (map sanitize-edn-hiccup x))
    (seq? x) (mapv sanitize-edn-hiccup x)
    (set? x) (into #{} (map sanitize-edn-hiccup) x)
    :else (pr-str x)))

(defn sync-missing-main-schema!
  "Ensures a long-lived branch (e.g. `:branch/root`) always has every accretive Datahike `:db/ident`
   attribute declaration AND matching `:seon.schema/key` Malli entity installed on `:main`."
  {:malli/schema [:=> [:cat :seon.host/conn :keyword] :any]}
  [conn branch]
  (when (not= branch :main)
    (let [mdb (store/head conn :main)
          bdb @conn
          main-schema (:schema mdb)
          br-schema (:schema bdb)
          missing-dh (->> main-schema
                          (keep (fn [[k decl]]
                                  (when (and (qualified-keyword? k)
                                             (not= "db" (namespace k))
                                             (map? decl)
                                             (:db/valueType decl)
                                             (not (contains? br-schema k)))
                                    (dissoc decl :db/id))))
                          vec)
          _ (when (seq missing-dh)
              (d/transact conn {:tx-data missing-dh}))
          bdb2 @conn
          br-keys (into #{} (map :v) (d/datoms bdb2 :aevt :seon.schema/key))
          missing-ents (->> (d/datoms mdb :aevt :seon.schema/key)
                            (keep (fn [dt]
                                    (when-not (contains? br-keys (:v dt))
                                      (let [e (d/pull mdb '[:seon.schema/key :seon.schema/form :seon.schema/doc :seon.schema/render] (:e dt))]
                                        (when (:seon.schema/form e) e)))))
                            vec)]
      (when (seq missing-ents)
        (d/transact conn {:tx-data missing-ents})))))

(defn sync-conn-to-latest-branch-head!
  "Lazily syncs a warm agent worker ONLY when it wakes for `:eval` or `:run-turn`:
   - If `:main` moved since last wake: installs any new `:main` schema attrs & loads `:main` form delta,
     then re-overlays this branch's own edits on top.
   - If this branch moved externally: refreshes `conn`, loads branch delta, and refreshes `@ctx`."
  {:malli/schema [:=> [:cat :seon.host/conn :keyword :any :any] :any]}
  [conn branch last-cid last-main-cid]
  (let [latest-db (store/head conn branch)
        latest-cid (d/commit-id latest-db)
        latest-main-cid (d/commit-id (store/head conn :main))
        main-moved? (and (not= branch :main)
                         last-main-cid latest-main-cid
                         (not= last-main-cid latest-main-cid))
        branch-moved? (and last-cid latest-cid (not= last-cid latest-cid))]
    (when main-moved?
      (sync-missing-main-schema! conn branch)
      (try
        (code/load-delta! conn last-main-cid latest-main-cid)
        (when-let [base-cid (d/merge-base conn :main branch)]
          (when (not= base-cid latest-cid)
            (code/load-delta! conn base-cid latest-cid true)))
        (catch Throwable _ nil)))
    (when branch-moved?
      (let [ds (try (d/changes conn last-cid latest-cid) (catch Throwable _ nil))]
        (reset! (:wrapped-atom conn) latest-db)
        (code/load-delta! conn last-cid latest-cid true)
        (ctx/refresh-active! latest-db ds)))
    {:branch-cid latest-cid :main-cid latest-main-cid}))

(defn run-fast-auto-proofs!
  "Runs fast end-of-eval-block proofs (task `:seon.task/tests`, fast reaching deftests <= `fast-test-ms`,
   and 25-sample seed=42 generative checks on newly edited contracted vars) in-process once per code-mutating
   turn, storing a concise `:seon.agent/last-proofs` summary for `<branch-status>`."
  {:malli/schema [:=> [:cat :seon.host/conn :keyword :keyword :any] :any]}
  [conn agent-id branch before-cid]
  (when (and conn agent-id (not= branch :main) before-cid)
    (try
      (let [after-cid (d/commit-id @conn)]
        (when (and after-cid (not= before-cid after-cid))
          (let [ops (code/changed-form-ops conn before-cid after-cid)]
            (when (seq (:upserts ops))
              (let [t0 (System/nanoTime)
                    bdb @conn
                    mdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb)
                    fast-ms (double (or (store/config-val mdb agent-id :seon.config/fast-test-ms) 150))
                    edited-nss (vec (distinct (keep :seon.code.form/ns (:upserts ops))))
                    edited-fkeys (into #{} (map :seon.code.form/key) (:upserts ops))
                    gen-vars (->> edited-fkeys
                                  (mapcat #(map :e (d/datoms bdb :avet :seon.code.var/form %)))
                                  (keep (fn [e]
                                          (let [v (d/pull bdb '[:seon.code.var/name :seon.code.var/test? :seon.code.var/schema] e)]
                                            (when (and (:seon.code.var/name v)
                                                       (not (:seon.code.var/test? v))
                                                       (schema/generatable-function-schema? (:seon.code.var/schema v)))
                                              (:seon.code.var/name v)))))
                                  distinct sort (take 6) vec)
                    task-id (:seon.agent/task (d/pull mdb '[:seon.agent/task] [:seon.agent/id agent-id]))
                    task-tests (when (and task-id (contains? (:schema mdb) :seon.task/tests))
                                 (vec (filter #(first (d/datoms bdb :avet :seon.code.var/name %))
                                              (:seon.task/tests (d/pull mdb '[:seon.task/tests] [:seon.task/id task-id])))))
                    fast-enough? (fn [tsym]
                                   (let [prev-ms (when (contains? (:schema mdb) :seon.test/var)
                                                   (some->> (d/datoms mdb :avet :seon.test/var tsym)
                                                            (keep #(:seon.test/ms (d/pull mdb '[:seon.test/ms] (:e %))))
                                                            last))]
                                     (or (nil? prev-ms) (<= (double prev-ms) fast-ms))))
                    reach-tests (->> edited-nss
                                     (mapcat #(code/reaching-tests bdb %))
                                     (filter fast-enough?))
                    test-vars (->> (concat task-tests reach-tests)
                                   distinct sort (take 6) vec)]
                (when (or (seq test-vars) (seq gen-vars))
                  (let [t-res (mapv #(run-single-test-var % (str "auto:" %)) test-vars)
                        g-res (vec (keep (fn [vsym]
                                           (when-let [r (schema/stress-var bdb vsym {:samples 25 :seed 42 :measure-fn code/measure-call})]
                                             {:var vsym :ok? (boolean (:ok? r)) :reason (:reason r)}))
                                         gen-vars))
                        t-fails (keep #(when-not (:seon.test/pass? %)
                                         (str (:seon.test/var %) ": " (:seon.test/report %)))
                                      t-res)
                        g-fails (keep #(when-not (:ok? %)
                                         (str (:var %) " [gen]: " (:reason %)))
                                      g-res)
                        all-fails (vec (take 5 (concat t-fails g-fails)))
                        ran (+ (count t-res) (count g-res))
                        ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
                        summary {:tx (:max-tx bdb)
                                 :ok? (empty? all-fails)
                                 :ran ran
                                 :passed (- ran (count all-fails))
                                 :failed (count all-fails)
                                 :ms ms
                                 :failures all-fails}]
                    (when (contains? (:schema bdb) :seon.agent/last-proofs)
                      (d/transact conn {:tx-data [{:seon.agent/id agent-id
                                                   :seon.agent/last-proofs summary}]}))
                    summary)))))))
      (catch Throwable _ nil))))
