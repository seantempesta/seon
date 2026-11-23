(ns seon.fork.worker-support
  "Pure test runner & agent worker turn-history/Hiccup helpers."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.store :as store]
            [seon.ctx :as ctx]
            [seon.schema :as schema]
            [seon.rule :as rules]))

(defonce ^:private ^:seon.flow/edge active-overlay-state (atom nil))

(defn- merge-3way-map
  [base ours theirs]
  (let [after-del (reduce (fn [acc k]
                            (if (and (not (contains? theirs k))
                                     (= (get ours k) (get base k)))
                              (dissoc acc k)
                              acc))
                          (or ours {})
                          (keys base))]
    (reduce-kv (fn [acc k v]
                 (if (not= v (get base k ::absent))
                   (assoc acc k v)
                   acc))
               after-del
               (or theirs {}))))

(defn- sort-rules [rule-maps]
  (->> rule-maps
       (sort-by (juxt #(long (or (:seon.rule/stratum %) 50)) #(str (:seon.rule/id %))))
       vec))

(defn- apply-schema-overlay [main-schemas {:keys [schema-upserts retracted-schemas]}]
  (let [s1 (if (seq retracted-schemas) (apply dissoc main-schemas retracted-schemas) main-schemas)]
    (reduce (fn [acc {:keys [:seon.schema/key :seon.schema/form]}]
              (if (and key (some? form)) (assoc acc key form) acc))
            s1
            schema-upserts)))

(defn- apply-rule-overlay [main-rules {:keys [rule-upserts retracted-rules]}]
  (let [by-id (into {} (map (juxt :seon.rule/id identity)) main-rules)
        m1 (if (seq retracted-rules) (apply dissoc by-id retracted-rules) by-id)
        m2 (reduce (fn [acc r]
                     (if-let [rid (:seon.rule/id r)] (assoc acc rid r) acc))
                   m1
                   rule-upserts)]
    (sort-rules (vals m2))))

(defn- merge-ns-forms-overlay [main-forms nsym {:keys [upserts retracted-keys]}]
  (let [by-k (into {} (map (juxt :seon.code.form/key identity)) main-forms)
        m1 (if (seq retracted-keys) (apply dissoc by-k retracted-keys) by-k)
        m2 (reduce (fn [acc f]
                     (if (= nsym (:seon.code.form/ns f))
                       (assoc acc (:seon.code.form/key f) f)
                       acc))
                   m1
                   upserts)]
    (->> (vals m2)
         (sort-by (juxt #(long (or (:seon.code.form/order %) 0)) :seon.code.form/key))
         vec)))

(defn- branch-main-context
  [db]
  (let [branch (when db (get-in db [:config :branch]))
        st (:store db)]
    (when (and st branch (not= branch :main)
               (get-in db [:config :commit-graph?] true))
      (let [br-cid (d/commit-id db)
            main-db (d/branch-as-db st :main)
            main-cid (when main-db (d/commit-id main-db))
            base-cid (when (and main-cid br-cid (d/commit-graph-record st br-cid))
                       (d/merge-base db main-cid br-cid))]
        (when (and base-cid main-cid (not= base-cid main-cid))
          {:branch branch
           :branch-cid br-cid
           :main-db main-db
           :main-cid main-cid
           :base-cid base-cid})))))

(defn- resolve-candidate-overrides
  [db]
  (if-let [{:keys [branch-cid main-db main-cid base-cid]} (branch-main-context db)]
    (let [ov-st @active-overlay-state
          hit? (and ov-st (= branch-cid (:branch-cid ov-st)) (= main-cid (:main-cid ov-st)))]
      (if (and hit? (:resolved ov-st))
        (:resolved ov-st)
        (let [ov (when hit? (:overlay ov-st))
              full-ov? (and ov (contains? ov :schema-upserts))
              base-db-delay (delay (d/commit-as-db db base-cid))
              schemas (when (or (contains? (:schema main-db) :seon.schema/key)
                                (contains? (:schema db) :seon.schema/key))
                        (let [ms (schema/db-schema-forms main-db)
                              res (if full-ov?
                                    (apply-schema-overlay ms ov)
                                    (merge-3way-map (schema/db-schema-forms @base-db-delay)
                                                    ms
                                                    (schema/db-schema-forms db)))]
                          (when (seq res) res)))
              rules (when (or (contains? (:schema main-db) :seon.rule/id)
                              (contains? (:schema db) :seon.rule/id))
                      (let [mr (rules/load-rules main-db)
                            res (if full-ov?
                                  (apply-rule-overlay mr ov)
                                  (let [to-m #(into {} (map (juxt :seon.rule/id identity)) %)]
                                    (sort-rules (vals (merge-3way-map (to-m (rules/load-rules @base-db-delay))
                                                                      (to-m mr)
                                                                      (to-m (rules/load-rules db)))))))]
                        (when (seq res) res)))
              res {:schemas schemas
                   :rules rules
                   :form-db main-db
                   :main-db main-db
                   :base-cid base-cid
                   :overlay ov}]
          (when hit?
            (swap! active-overlay-state assoc :resolved res))
          res)))
    {:schemas (when (and db (contains? (:schema db) :seon.schema/key))
                (let [fs (schema/db-schema-forms db)] (when (seq fs) fs)))
     :rules (when (and db (contains? (:schema db) :seon.rule/id))
              (let [rs (rules/load-rules db)] (when (seq rs) rs)))
     :form-db db
     :main-db nil
     :base-cid nil
     :overlay nil}))

(defn- candidate-ns-forms [db {:keys [main-db base-cid overlay]} ns-sym]
  (cond
    (and main-db overlay)
    (merge-ns-forms-overlay (code/ns-forms main-db ns-sym) ns-sym overlay)

    (and main-db base-cid)
    (let [base-db (d/commit-as-db db base-cid)
          to-m #(into {} (map (juxt :seon.code.form/key identity)) %)
          merged (merge-3way-map (to-m (code/ns-forms base-db ns-sym))
                                 (to-m (code/ns-forms main-db ns-sym))
                                 (to-m (code/ns-forms db ns-sym)))]
      (->> (vals merged)
           (sort-by (juxt #(long (or (:seon.code.form/order %) 0)) :seon.code.form/key))
           vec))

    (and db (contains? (:schema db) :seon.code.form/ns))
    (code/ns-forms db ns-sym)

    :else nil))

(defn apply-form-overlay!
"Applies a code overlay into the current worker. When `db` is on a branch whose `:main`
 advanced past `merge-base`, binds merged candidate schemas and loads untouched forms from
 `:main` so stale branch bases never overwrite newer `:main` vars."
{:malli/schema [:function
                [:=> [:cat :map] [:vector :map]]
                [:=> [:cat [:maybe :seon.host/db] :map] [:vector :map]]]
 :cost/ticks 50000000}
([overlay] (code/apply-overlay! nil overlay))
([db overlay]
 (if-let [{:keys [branch-cid main-db main-cid]} (branch-main-context db)]
   (do
     (reset! active-overlay-state {:branch-cid branch-cid :main-cid main-cid :overlay overlay})
     (let [{:keys [schemas]} (resolve-candidate-overrides db)]
       (binding [schema/*base-forms-override* (or schema/*base-forms-override* schemas)]
         (code/apply-overlay! main-db overlay))))
   (code/apply-overlay! db overlay))))

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
  "Ensures a branch inherits new accretive Datahike `:db/ident` declarations & `:seon.schema/key`
   entities added on `:main` since `merge-base`, without resurrecting schemas intentionally deleted on `branch`."
  {:malli/schema [:=> [:cat :seon.host/conn :keyword] :any]}
  [conn branch]
  (when (not= branch :main)
    (let [mdb (store/head conn :main)
          base-db (store/merge-base-db conn :main branch)
          base-schema (when base-db (:schema base-db))
          base-keys (if (and base-db (contains? base-schema :seon.schema/key))
                      (into #{} (map :v) (d/datoms base-db :aevt :seon.schema/key))
                      #{})
          bdb @conn
          main-schema (:schema mdb)
          br-schema (:schema bdb)
          missing-dh (->> main-schema
                          (keep (fn [[k decl]]
                                  (when (and (qualified-keyword? k)
                                             (not= "db" (namespace k))
                                             (map? decl)
                                             (:db/valueType decl)
                                             (not (contains? br-schema k))
                                             (not (contains? base-schema k)))
                                    (dissoc decl :db/id))))
                          vec)
          _ (when (seq missing-dh)
              (d/transact conn {:tx-data missing-dh}))
          bdb2 @conn
          br-keys (into #{} (map :v) (d/datoms bdb2 :aevt :seon.schema/key))
          missing-ents (->> (d/datoms mdb :aevt :seon.schema/key)
                            (keep (fn [dt]
                                    (let [k (:v dt)]
                                      (when (and (not (contains? br-keys k))
                                                 (not (contains? base-keys k)))
                                        (let [e (d/pull mdb '[:seon.schema/key :seon.schema/form :seon.schema/doc] (:e dt))]
                                          (when (:seon.schema/form e) e))))))
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

(defonce ^:private ^:seon.flow/edge spliced-rebound-tx (atom nil))

(defn prepare-test-worker-overrides!
"Resolves post-merge candidate schemas, rules, and spliced-caller rebinds for a test worker,
 preventing stale branch bases from hiding `:main` schemas/rules or re-evaluating stale callers."
{:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :qualified-symbol]] :map]
 :cost/ticks 20000000}
[db test-sym]
(let [{:keys [schemas form-db] :as ov-info} (resolve-candidate-overrides db)]
  (when (and form-db test-sym (contains? (:schema form-db) :seon.code.var/calls)
             (not= @spliced-rebound-tx [(:max-tx form-db) (when db (:max-tx db)) test-sym]))
    (binding [schema/*base-forms-override* (or schema/*base-forms-override* schemas)]
      (code/rebind-spliced-callers! form-db test-sym))
    (reset! spliced-rebound-tx [(:max-tx form-db) (when db (:max-tx db)) test-sym]))
  ov-info))

(defn run-single-test-var
  "Executes one `deftest` var with tick/byte/ms measurement and cost-budget enforcement.
   When `db` is provided and `test-sym`'s namespace is not yet loaded, hydrates it directly
   from Datahike `:seon.code.form/*` before falling back to `(require ns-sym)`."
  {:malli/schema [:function [:=> [:cat :qualified-symbol :string] :map] [:=> [:cat :any :qualified-symbol :string] :map]]}
  ([test-sym digest] (run-single-test-var nil test-sym digest))
  ([db test-sym digest]
   (let [ns-sym (symbol (namespace test-sym))
         sw (java.io.StringWriter.)
         counts (volatile! {:pass 0 :fail 0 :error 0 :messages []})
         {:keys [schemas rules form-db] :as ov-info} (prepare-test-worker-overrides! db test-sym)
         measured
         (binding [*out* sw *err* sw t/*test-out* sw
                   schema/*base-forms-override* (or schema/*base-forms-override* schemas)
                   rules/*base-rules-override* (or rules/*base-rules-override* rules)
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
                   (let [fs (candidate-ns-forms db ov-info ns-sym)]
                   (if (seq fs) (code/apply-overlay! form-db {:upserts fs}) (require ns-sym))))
                 (if-let [v (resolve test-sym)]
                   (do (t/test-var v) v)
                   (do (vswap! counts #(-> % (update :error inc) (update :messages conj (str "Test var not found: " test-sym)))) nil))
                 (catch Throwable ex
                   (let [msg (or (.getMessage ex) (str ex))]
                     (vswap! counts #(-> % (update :error inc) (update :messages conj (str "UNCAUGHT: " msg))))
                     {:uncaught msg}))))))
         v (:val measured)
         vmeta (when (var? v) (meta v))
         ticks (:ticks measured)
         bytes (:bytes measured)
         ms (:ms measured)
         ticks-budget (or (:cost/ticks vmeta) (get-in vmeta [:seon.test/estimate :ticks]))
         bytes-budget (or (:cost/bytes vmeta) (get-in vmeta [:seon.test/estimate :bytes]))
         ms-budget (or (:cost/ms vmeta) (get-in vmeta [:seon.test/estimate :ms]))
         _ (when (and ticks-budget (> ticks ticks-budget))
             (vswap! counts #(-> % (update :fail inc) (update :messages conj (str "COST OVERRUN on " test-sym ": actual " ticks " ticks > estimated " ticks-budget " ticks (:cost/ticks). Optimize or update estimate.")))))
         _ (when (and bytes-budget (> bytes bytes-budget))
             (vswap! counts #(-> % (update :fail inc) (update :messages conj (str "BYTES OVERRUN on " test-sym ": actual " bytes " bytes > estimated " bytes-budget " bytes (:cost/bytes). Optimize or update estimate.")))))
         _ (when (and ms-budget (> ms ms-budget))
             (vswap! counts #(-> % (update :fail inc) (update :messages conj (str "TIME OVERRUN on " test-sym ": actual " ms " ms > estimated " ms-budget " ms (:cost/ms). Optimize or update estimate.")))))
         {:keys [fail error messages]} @counts
         pass? (zero? (+ fail error))]
     {:seon.test/digest digest :seon.test/var test-sym :seon.test/kind :deftest :seon.test/pass? pass?
      :seon.test/ms (double ms) :seon.test/ticks (long ticks) :seon.test/bytes (long bytes)
      :seon.test/report (if pass?
                          (let [s (str/trim (str sw))] (if (str/blank? s) "PASS" s))
                          (str/join "\n" (cond-> messages (not (str/blank? (str sw))) (conj (str/trim (str sw))))))})))

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