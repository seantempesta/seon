(ns seon.repl
  "Interactive REPL session commands (`run-tests!`, `grep-src`, `help`, `autofix-health!`,
   `inspect-branch`, `save-example!`, `create-task!`) and unified `user/*` helper installer."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [seon.code :as code]
            [seon.code.cst :as cst]
            [seon.code.edit :as edit]
            [seon.ctx :as ctx]
            [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
            [seon.fork.worker-support :as wsup]
            [seon.health :as health]
            [seon.store :as store]
            [seon.health.metrics :as hmetrics]))

(def ^:private grep-src-cache (store/lru-cache 8))

(defn- cached-form-index [bdb]
  (let [ck [(get-in bdb [:config :store :id]) (get-in bdb [:config :branch] :main) (:max-tx bdb)]]
    (store/lru-get! grep-src-cache 8 ck
      (fn []
        (let [by-e (volatile! {}) out (volatile! [])]
          (doseq [dt (d/datoms bdb :aevt :seon.code.form/key)] (vswap! by-e assoc (:e dt) {:key (str (:v dt))}))
          (doseq [dt (d/datoms bdb :aevt :seon.code.form/ns)]
            (when (contains? @by-e (:e dt)) (vswap! by-e assoc-in [(:e dt) :ns] (name (:v dt)))))
          (doseq [dt (d/datoms bdb :aevt :seon.code.form/src)
                  :let [info (get @by-e (:e dt))] :when info
                  :let [src (str (:v dt)) fk (:key info)]]
            (vswap! out conj {:key fk :sym (try (symbol fk) (catch Throwable _ nil))
                              :ns (or (:ns info) (first (str/split fk #"/|#"))) :src src :low (str/lower-case src)}))
          @out)))))

(def ^:private user-helper-docs
  '{edit-fn! {:arglists ([t old new] [t old new opts]) :doc "CST-replace in 'ns/fn ('ns = header only)."}
    add-require! {:arglists ([nsym spec] [nsym spec opts]) :doc "Add :require spec(s) to nsym."}
    set-meta! {:arglists ([batch] [t m] [t m opts]) :doc "Insert/merge defn attr-map."}
    ns-requires {:arglists ([nsym]) :doc "Return parsed :require specs."}
    grep-src {:arglists ([pat] [pat opts]) :doc "Cached grep (+ :seon.schema/* for seon.*)."}
    unused-requires {:arglists ([nsym]) :doc "Analyze unused :require specs."}
    prune-requires! {:arglists ([nsym] [nsym opts]) :doc "Remove unused :require specs."}
    run-tests! {:arglists ([] [t & more]) :doc "Run deftests live (+ :main-pass?/:failure-lines)."}
    diff {:arglists ([] [t]) :doc "Branch upserts, retractions, schemas, diffs."}
    context {:arglists ([] [t] [t opts]) :doc "Render <walk-context> + tail."}
    help {:arglists ([] [sym]) :doc "List or inspect user/* helpers."}
    source {:arglists ([t] [t opts]) :doc "Read var, ns, schema, or doc source."}
    ns-outline {:arglists ([nsym]) :doc "Forms with :sym :key :lines :chars :tokens."}
    move-vars! {:arglists ([spec]) :doc "Split vars into child ns."}
    register-schema! {:arglists ([k form] [k form opts]) :doc "Register Malli schema -> Datahike attr."}
    remove-var! {:arglists ([sym] [sym opts]) :doc "Retract var & form."}
    add-fn! {:arglists ([nsym src] [nsym src opts]) :doc "Add/overwrite form (auto-seeds *-test ns)."}
    callers {:arglists ([sym]) :doc "List callers & hiccup renderers."}
    callees {:arglists ([sym]) :doc "List :refs, :input, :output, :keywords."}
    tests-for {:arglists ([sym-or-ns]) :doc "List reaching tests."}
    search {:arglists ([q]) :doc "Search vars (name/doc), schemas (key/doc/form), docs."}
    health {:arglists ([] [which]) :doc "Fast scoped health (<15 MB); :all/:main for full."}
    past-evals {:arglists ([] [opts]) :doc "Query historical evals via d/history."}
    autofix-health! {:arglists ([] [opts]) :doc "Run programmatic :fix health actions."}
    inspect-branch {:arglists ([target]) :doc "Inspect branch diff, errors, proofs, evals."}
    save-example! {:arglists ([src ex] [src ex opts]) :doc "Snapshot session into :example/<name>."}
    create-task! {:arglists ([spec]) :doc "Create linked task on :main."}
    update-task! {:arglists ([spec]) :doc "Update active task on :main."}
    assign-task! {:arglists ([tid]) :doc "Attach :seon.task/id to agent."}
    metrics {:arglists ([] [w]) :doc "Sliding-window completion & frustration metrics."}
    chat! {:arglists ([msg] [ag msg]) :doc "Send live chat message to agent."}
    monitor {:arglists ([] [ag]) :doc "Inspect perspective, merge-gate, children."}
    ready! {:arglists ([]) :doc "Mark branch ready for test + merge."}})

(defn- intern-doc! [u sym f]
  (let [target (if (var? f) (fn [& args] (apply @f args)) f)
        v (intern u sym target)
        base-m (get user-helper-docs sym)
        ov (when (var? f) (:seon.eval/budget-override (meta f)))]
    (when (or base-m ov)
      (alter-meta! v merge base-m (when ov {:seon.eval/budget-override ov})))
    v))

(defn help
  "Lists available `user/*` REPL helpers with signatures & one-line docs, or shows one helper in full."
  {:malli/schema [:function
                  [:=> [:cat] :map]
                  [:=> [:cat :any] :map]]}
  ([]
   (into (sorted-map)
         (map (fn [[k {:keys [arglists doc]}]]
                [k (str (pr-str arglists) " — " doc)]))
         user-helper-docs))
  ([sym]
   (let [nm (symbol (name sym))]
     (if-let [info (get user-helper-docs nm)]
       (assoc info :var (symbol "user" (name nm)))
       (if-let [v (ns-resolve 'user nm)]
         (merge {:var (symbol "user" (name nm))} (select-keys (meta v) [:arglists :doc]))
         {:error :unknown-helper :sym sym :available (vec (sort (keys user-helper-docs)))})))))

(defn autofix-health!
  "Executes programmatic `:fix` actions from `seon.health/report` (filtered by `:ns`, `:class`, or `{:dry-run? true}`)."
  {:malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :map] [:maybe :map]]]}
  ([] (autofix-health! {}))
  ([{:keys [ns class dry-run?]}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [targets (->> (:problems (health/report @conn))
                        (filter #(and (:fix %) (or (nil? ns) (= ns (:ns %)) (health/ns-in-scope? ns (:ns %)))
                                      (or (nil? class) (= class (:class %))))) vec)
           results (mapv (fn [p]
                           (let [{:keys [fn args]} (:fix p)
                                 f (or (try (resolve fn) (catch Throwable _ nil)) (try (requiring-resolve fn) (catch Throwable _ nil)))]
                             (if-not f
                               {:ok? false :class (:class p) :subject (:subject p) :error :fix-fn-not-found :fn fn}
                               (try
                                 (let [r (apply f (if dry-run? (concat args [{:dry-run? true}]) args))]
                                   {:ok? (boolean (:ok? r true)) :class (:class p) :subject (:subject p)
                                    :changed? (boolean (:changed? r true)) :result (dissoc r :diff)})
                                 (catch Throwable ex
                                   {:ok? false :class (:class p) :subject (:subject p) :error (or (.getMessage ex) (str ex))})))))
                         targets)]
       {:ok? (every? :ok? results) :dry-run? (boolean dry-run?) :count (count results) :results results}))))

(defn inspect-branch
  "Inspects a branch or agent keyword: changed forms, health errors, proofs, recent evals, and diffs."
  {:malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :keyword] [:maybe :map]]]}
  ([] (inspect-branch (:branch *agent-ctx*)))
  ([branch-or-agent]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [mdb (store/head conn :main)
           ag-ent (when (first (d/datoms mdb :avet :seon.agent/id branch-or-agent))
                    (d/pull mdb '[:seon.agent/id :seon.agent/branch :seon.agent/scope :seon.agent/goal :seon.agent/task] [:seon.agent/id branch-or-agent]))
           branch (or (:seon.agent/branch ag-ent) branch-or-agent)
           bdb (try (store/head conn branch) (catch Throwable _ nil))]
       (if-not bdb
         {:ok? false :error :branch-not-found :target branch-or-agent :branch branch}
         (let [base-cid (when (not= branch :main) (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
               base-db (when base-cid (d/commit-as-db conn base-cid))
               ops (if base-cid (code/changed-form-ops conn base-cid (d/commit-id bdb)) {:upserts [] :retracted-keys #{} :schema-keys #{}})
               tnss (vec (distinct (keep :seon.code.form/ns (:upserts ops))))
               errs (if (= branch :main) [] (health/caused-by-branch mdb bdb {} tnss))
               aid (or (:seon.agent/id ag-ent)
                       (some #(let [a (d/pull bdb '[:seon.agent/id :seon.agent/branch] (:e %))] (when (= branch (:seon.agent/branch a)) (:seon.agent/id a)))
                             (d/datoms bdb :aevt :seon.agent/id)))
               b-ag (when aid (d/pull bdb '[:seon.agent/last-proofs :seon.agent/ready? :seon.agent/waiting?] [:seon.agent/id aid]))
               recent-evals (when aid
                              (->> (d/datoms bdb :avet :seon.agent.eval/agent aid)
                                   (map #(d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/handle :seon.agent.eval/intent :seon.agent.eval/error :seon.agent.eval/ms] (:e %)))
                                   (sort-by #(or (:seon.agent.eval/turn %) 0)) (take-last 6) vec))
               diffs (into {} (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                                      (let [old-s (when base-db (:seon.code.form/src (d/pull base-db '[:seon.code.form/src] [:seon.code.form/key key])))
                                            ud (cst/unified-diff (or old-s "") (or src "") key)]
                                        [key (if (> (count ud) 1200) (str (subs ud 0 1200) "\n…") ud)])))
                           (take 6 (:upserts ops)))]
           {:ok? true :agent aid :branch branch :branch-tx (:max-tx bdb) :ready? (boolean (:seon.agent/ready? b-ag))
            :last-proofs (:seon.agent/last-proofs b-ag) :upserts (vec (sort (map :seon.code.form/key (:upserts ops))))
            :retracted (vec (sort (:retracted-keys ops))) :schemas (vec (sort (:schema-keys ops)))
            :health-errors errs :recent-evals recent-evals :diffs diffs}))))))

(defn save-example!
  "Snapshots a completed agent session into `:example/<name>` for `resources/seon/agents/examples.edn`."
  {:malli/schema [:function [:=> [:cat :keyword :keyword] [:maybe :map]] [:=> [:cat :keyword :keyword :map] [:maybe :map]]]}
  ([source-agent-id example-id] (save-example! source-agent-id example-id {}))
  ([source-agent-id example-id {:keys [goal max-evals] :or {max-evals 8}}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (if-not (= "example" (namespace example-id))
       {:ok? false :error :invalid-example-id :hint "Example ID must be in :example/* (e.g. :example/my-workflow)."}
       (let [mdb (store/head conn :main)
             ag (or (when (first (d/datoms mdb :avet :seon.agent/id source-agent-id)) (d/pull mdb '[*] [:seon.agent/id source-agent-id]))
                    (d/pull @conn '[*] [:seon.agent/id source-agent-id]))]
         (if-not (:seon.agent/id ag)
           {:ok? false :error :agent-not-found :agent source-agent-id}
           (let [b (:seon.agent/branch ag :main)
                 bdb (or (try (store/head conn b) (catch Throwable _ nil)) @conn)
                 raw-evals (->> (d/datoms bdb :avet :seon.agent.eval/agent source-agent-id)
                                (map #(d/pull bdb '[*] (:e %))) (remove :seon.agent.eval/error)
                                (filter #(not (str/blank? (str (:seon.agent.eval/code %)))))
                                (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0)))
                                (take max-evals) vec)
                 trunc (fn [s n] (let [st (str s)] (if (> (count st) n) (str (subs st 0 n) "…") st)))
                 ex-ent (cond-> {:seon.agent/id example-id :seon.agent/branch :main :seon.agent/role (or (:seon.agent/role ag) :worker)
                                 :seon.agent/goal (or goal (:seon.agent/goal ag) "Curated real session example.")
                                 :seon.agent/paused? true :seon.agent/waiting? true :seon.agent/seed-path "resources/seon/agents/examples.edn"}
                          (:seon.agent/scope ag) (assoc :seon.agent/scope (:seon.agent/scope ag)))
                 eval-ents (vec (map-indexed
                                 (fn [idx ev]
                                   (let [turn (inc idx)]
                                     (cond-> {:seon.agent.eval/id (hasch/uuid [:seed-eval example-id turn 0])
                                              :seon.agent.eval/agent example-id :seon.agent.eval/turn turn :seon.agent.eval/call-index 0
                                              :seon.agent.eval/call-id (str "ex-" (name example-id) "-" turn)
                                              :seon.agent.eval/code (trunc (:seon.agent.eval/code ev) 1200)
                                              :seon.agent.eval/value (trunc (:seon.agent.eval/value ev) 600)
                                              :seon.agent.eval/out (trunc (:seon.agent.eval/out ev "") 200)
                                              :seon.agent.eval/handle (symbol "result" (str "e" turn))
                                              :seon.agent.eval/ms (double (or (:seon.agent.eval/ms ev) 1.0))
                                              :seon.agent.eval/ticks (long (or (:seon.agent.eval/ticks ev) 50000))}
                                       (not (str/blank? (str (:seon.agent.eval/intent ev)))) (assoc :seon.agent.eval/intent (str (:seon.agent.eval/intent ev)))
                                       (not (str/blank? (str (:seon.agent.eval/thought ev)))) (assoc :seon.agent.eval/thought (trunc (:seon.agent.eval/thought ev) 300))
                                       (:seon.agent.eval/result-schema ev) (assoc :seon.agent.eval/result-schema (:seon.agent.eval/result-schema ev)))))
                                 raw-evals))
                 r (d/transact conn {:tx-data (into [ex-ent] eval-ents)})]
             (ctx/refresh-active! (:db-after r) (:tx-data r))
             {:ok? true :example-id example-id :source-agent source-agent-id :evals-saved (count eval-ents)
              :seed-path "resources/seon/agents/examples.edn" :tx (:max-tx (:db-after r))})))))))

(defn create-task!
  "Creates a linked task on `:main` via `:transactor` (written back to `resources/seon/tasks.edn`)."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [id from-template scope title plan needs tests spawn-agent? agent-id role auto-merge?
           parent template? owner created-by summary assign?]}]
  (when-let [{:keys [conn] cur-aid :agent-id} *agent-ctx*]
    (let [mdb (store/head conn :main)
          cur-task (when cur-aid (or (:seon.agent/task (d/pull @conn '[:seon.agent/task] [:seon.agent/id cur-aid]))
                                     (:seon.agent/task (d/pull mdb '[:seon.agent/task] [:seon.agent/id cur-aid]))))
          tpl (when (and from-template (first (d/datoms mdb :avet :seon.task/id from-template)))
                (d/pull mdb '[:seon.task/scope :seon.task/plan :seon.task/tests :seon.task/needs] [:seon.task/id from-template]))
          eff-scope (or scope (:seon.task/scope tpl))
          eff-plan (if (and (:seon.task/plan tpl) (not (str/blank? (str plan)))) (str plan "\n\n---\n" (:seon.task/plan tpl)) (or plan (:seon.task/plan tpl) ""))
          eff-tests (into (set (:seon.task/tests tpl)) tests)
          eff-needs (into (set (:seon.task/needs tpl)) needs)
          eff-parent (or parent (when (and (not template?) (qualified-keyword? cur-task) (not= cur-task id)) cur-task))
          req (cond-> {:op :create-task :id id :title (or title (name id)) :plan eff-plan}
                eff-scope (assoc :scope eff-scope) eff-parent (assoc :parent eff-parent)
                (some? template?) (assoc :template? (boolean template?)) from-template (assoc :template from-template)
                owner (assoc :owner owner) (or created-by cur-aid) (assoc :created-by (or created-by cur-aid))
                (string? summary) (assoc :summary summary) (seq eff-tests) (assoc :tests (vec eff-tests))
                (seq eff-needs) (assoc :needs (vec eff-needs)) (some? spawn-agent?) (assoc :spawn-agent? (boolean spawn-agent?))
                (and spawn-agent? cur-aid) (assoc :parent-agent cur-aid) agent-id (assoc :agent-id agent-id)
                role (assoc :role role) (some? auto-merge?) (assoc :auto-merge? (boolean auto-merge?)))
          ans (fcalls/tx-main-sync! req)]
      (when (and (:ok? ans) assign? cur-aid) ((resolve 'user/assign-task!) id))
      ans)))

(defn metrics
  "Returns the live anti-gaming sliding-window task completion & tool-frustration scorecard
   over the latest `window` (default 10) autonomous Seon agents."
  {:malli/schema [:function
                  [:=> [:cat] [:maybe :map]]
                  [:=> [:cat :int] [:maybe :map]]]
   :seon.eval/budget-override {:ticks 300000000 :bytes 1073741824}}
  ([] (metrics 10))
  ([window]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [mdb (or (try (store/head conn :main) (catch Throwable _ nil)) @conn)]
       (hmetrics/agent-learning-metrics conn mdb {:window window})))))

(defn- grep-schema-hits [bdb ns-pref compiled multi? match-low? rem-lim]
  (if-not (and (pos? rem-lim) (contains? (:schema bdb) :seon.schema/key)
               (some #(str/includes? (str/lower-case (:raw %)) "seon.") compiled))
    []
    (let [by-e (volatile! {}) sm (:schema bdb) out (volatile! [])]
      (doseq [dt (d/datoms bdb :aevt :seon.schema/key)] (vswap! by-e assoc (:e dt) {:key (:v dt)}))
      (when (:seon.schema/form sm)
        (doseq [dt (d/datoms bdb :aevt :seon.schema/form)]
          (when (contains? @by-e (:e dt)) (vswap! by-e assoc-in [(:e dt) :form] (pr-str (:v dt))))))
      (when (:seon.schema/doc sm)
        (doseq [dt (d/datoms bdb :aevt :seon.schema/doc)]
          (when (contains? @by-e (:e dt)) (vswap! by-e assoc-in [(:e dt) :doc] (str (:v dt))))))
      (doseq [{:keys [key form doc]} (sort-by #(str (:key %)) (vals @by-e))
              :while (< (count @out) rem-lim)
              :let [kns (when (qualified-keyword? key) (namespace key))]
              :when (or (nil? ns-pref) (= kns ns-pref) (and kns (str/starts-with? kns (str ns-pref "."))))
              :let [txt (str key " " (or form "") (when (and doc (not (str/blank? doc))) (str " — " doc)))
                    hit-p (some #(when (match-low? % txt (str/lower-case txt)) (:raw %)) compiled)]
              :when hit-p]
        (vswap! out conj (cond-> {:key (str key) :schema key :ns "seon.schema" :line 1
                                  :text (if (> (count txt) 140) (str (subs txt 0 140) "…") txt)}
                           multi? (assoc :pattern hit-p))))
      @out)))

(defn grep-src
  "Cached single- or multi-pattern grep across forms (falls back to `:seon.schema/*` when pattern mentions `seon.`)."
  {:malli/schema [:function [:=> [:cat :any] [:maybe [:vector :map]]] [:=> [:cat :any :map] [:maybe [:vector :map]]]]}
  ([pattern] (grep-src pattern {}))
  ([pattern {:keys [ns limit] :or {limit 30}}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [bdb @conn ns-pref (when ns (if (string? ns) ns (name ns)))
           multi? (and (coll? pattern) (not (map? pattern)))
           compiled (mapv #(if (instance? java.util.regex.Pattern %) {:raw (str %) :re? true :re %} {:raw (str %) :re? false :low (str/lower-case (str %))})
                          (if multi? (vec pattern) [pattern]))
           mlow? (fn [{:keys [re? re low]} ^String s ^String ls] (if re? (boolean (re-find re s)) (str/includes? ls low)))
           mline? (fn [{:keys [re? re low]} ^String l] (if re? (boolean (re-find re l)) (str/includes? (str/lower-case l) low)))
           hits (volatile! [])]
       (doseq [{:keys [key sym src low] :as e} (cached-form-index bdb)
               :while (< (count @hits) limit) :let [ens (:ns e)]
               :when (or (nil? ns-pref) (= ens ns-pref) (str/starts-with? (str ens) (str ns-pref ".")))
               :let [mp (filterv #(mlow? % src low) compiled)] :when (seq mp)]
         (doseq [[i line] (map-indexed vector (str/split-lines src))
                 :while (< (count @hits) limit)
                 :let [hp (some #(when (mline? % line) (:raw %)) mp)] :when hp
                 :let [t (str/trim line)]]
           (vswap! hits conj (cond-> {:key key :sym sym :ns ens :line (inc i)
                                      :text (if (> (count t) 120) (str (subs t 0 120) "…") t)}
                               multi? (assoc :pattern hp)))))
       (into @hits (grep-schema-hits bdb ns-pref compiled multi? mlow? (- (long limit) (count @hits))))))))

(defn- search-db-hits [db q]
  (let [sm (:schema db) vs (volatile! []) ss (volatile! []) ds (volatile! [])
        hit? #(str/includes? (str/lower-case (str %)) q)
        e-v (fn [e a] (:v (first (d/datoms db :eavt e a))))]
    (when (:seon.code.var/name sm) (doseq [dt (d/datoms db :aevt :seon.code.var/name)] (when (hit? (:v dt)) (vswap! vs conj (:v dt)))))
    (when (:seon.code.var/doc sm) (doseq [dt (d/datoms db :aevt :seon.code.var/doc)] (when (hit? (:v dt)) (when-let [v (e-v (:e dt) :seon.code.var/name)] (vswap! vs conj v)))))
    (when (:seon.schema/key sm) (doseq [dt (d/datoms db :aevt :seon.schema/key)] (when (hit? (:v dt)) (vswap! ss conj (:v dt)))))
    (when (:seon.schema/doc sm) (doseq [dt (d/datoms db :aevt :seon.schema/doc)] (when (hit? (:v dt)) (when-let [k (e-v (:e dt) :seon.schema/key)] (vswap! ss conj k)))))
    (when (:seon.schema/form sm) (doseq [dt (d/datoms db :aevt :seon.schema/form)] (when (hit? (pr-str (:v dt))) (when-let [k (e-v (:e dt) :seon.schema/key)] (vswap! ss conj k)))))
    (when (:seon.code.doc/id sm) (doseq [dt (d/datoms db :aevt :seon.code.doc/id)] (when (hit? (:v dt)) (vswap! ds conj (:v dt)))))
    {:vars @vs :schemas @ss :docs @ds}))

(defn search
  "Searches vars (name + doc), schemas (key + doc + form), and docs by case-insensitive substring."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [substr]
  (when-let [{:keys [conn branch]} *agent-ctx*]
    (let [bdb @conn
          mdb (if (= branch :main) bdb (or (store/head conn :main) bdb))
          q (str/lower-case (str substr))
          h1 (search-db-hits mdb q)
          h2 (when (not= (d/commit-id bdb) (d/commit-id mdb)) (search-db-hits bdb q))]
      {:vars (->> (concat (:vars h1) (:vars h2)) distinct sort (take 25) vec)
       :schemas (->> (concat (:schemas h1) (:schemas h2)) distinct sort (take 20) vec)
       :docs (->> (concat (:docs h1) (:docs h2)) distinct sort (take 20) vec)})))

(defn- main-test-pass? [mdb vsym]
  (when (and mdb vsym (contains? (:schema mdb) :seon.test/var) (contains? (:schema mdb) :seon.test/pass?))
    (some-> (->> (d/datoms mdb :avet :seon.test/var vsym)
                 (keep (fn [dt]
                         (when-let [pdt (first (d/datoms mdb :eavt (:e dt) :seon.test/pass?))]
                           {:tx (long (or (:tx pdt) (:tx dt) 0)) :e (:e dt) :pass? (boolean (:v pdt))})))
                 (sort-by (fn [{:keys [tx e]}] [(- tx) (- e)]))
                 first)
            :pass?)))

(defn- concise-failure-line [f main-pass?]
  (let [ls (->> (str/split-lines (str (:seon.test/report f))) (map str/trim) (remove str/blank?) (take 3))
        snip (let [s (str/join " | " ls)] (if (> (count s) 220) (str (subs s 0 217) "…") s))]
    (str (:seon.test/var f) (when (false? main-pass?) " [PRE-EXISTING FAIL ON :main]")
         ": " (if (str/blank? snip) "FAIL" snip))))

(defn run-tests!
  "Runs reach-selected or explicit `deftest`s live, surfacing `:main-pass?` and concise `:failure-lines`."
  {:malli/schema [:=> [:cat [:* :any]] [:maybe :map]] :seon.eval/budget-override {:ticks 2000000000 :bytes 4294967296}}
  ([] (run-tests! nil))
  ([target & more]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [t0 (System/nanoTime) bdb @conn
           mdb (if (= branch :main) bdb (or (store/head conn :main) bdb))
           base-cid (when (not= branch :main) (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
           ops (when base-cid (code/changed-form-ops conn base-cid (d/commit-id bdb)))
           _ (when (seq (:upserts ops)) (wsup/apply-form-overlay! bdb {:upserts (:upserts ops) :retracted-keys (:retracted-keys ops)}))
           tlist (vec (mapcat #(cond (nil? %) nil (or (vector? %) (set? %) (seq? %)) % :else [%]) (if (seq more) (cons target more) [target])))
           test-syms (if (seq tlist)
                       (->> tlist
                            (mapcat (fn [x]
                                      (let [s (if (string? x) (symbol x) x)]
                                        (cond
                                          (namespace s) [s]
                                          (str/ends-with? (name s) "-test")
                                          (keep #(let [v (d/pull bdb '[:seon.code.var/name :seon.code.var/test?] (:e %))]
                                                   (when (:seon.code.var/test? v) (:seon.code.var/name v)))
                                                (d/datoms bdb :avet :seon.code.var/ns s))
                                          :else (code/reaching-tests bdb s)))))
                            distinct sort vec)
                       (->> (distinct (keep :seon.code.form/ns (:upserts ops))) (mapcat #(code/reaching-tests bdb %)) distinct sort vec))
           results (mapv #(wsup/run-single-test-var % (str "local:" %)) test-syms)
           fails (mapv (fn [f]
                         (let [mp (main-test-pass? mdb (:seon.test/var f))]
                           (assoc (select-keys f [:seon.test/var :seon.test/report :seon.test/ms]) :main-pass? mp :summary (concise-failure-line f mp))))
                       (filterv #(not (:seon.test/pass? %)) results))
           ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 10000.0)) 100.0)]
       (cond-> {:ok? (empty? fails) :ran (count results) :passed (- (count results) (count fails)) :failed (count fails)
                :ms ms :failures fails :results (mapv #(select-keys % [:seon.test/var :seon.test/pass? :seon.test/ms]) results)}
         (seq fails) (assoc :failure-lines (mapv :summary fails)))))))

(defn- ^:seon.flow/edge branch-delta-info [conn bdb branch scope]
  (let [bc (try (d/merge-base conn :main branch) (catch Throwable _ nil))
        btx (if bc (long (:max-tx (d/commit-as-db conn bc))) Long/MAX_VALUE)
        fk (volatile! []) tn (volatile! #{}) sk (volatile! #{})
        ev (fn [e a] (:v (first (d/datoms bdb :eavt e a))))]
    (when (and bc (not= bc (d/commit-id bdb)))
      (if (and scope (not= scope 'seon))
        (doseq [dt (d/datoms bdb :avet :seon.code.form/ns scope)
                :let [e (:e dt) h (first (d/datoms bdb :eavt e :seon.code.form/hash))]
                :when (and h (> (long (:tx h)) btx))]
          (when-let [k (ev e :seon.code.form/key)] (vswap! fk conj k))
          (vswap! tn conj scope))
        (doseq [dt (d/datoms bdb :aevt :seon.code.form/hash) :when (> (long (:tx dt)) btx)]
          (when-let [k (ev (:e dt) :seon.code.form/key)] (vswap! fk conj k))
          (when-let [n (ev (:e dt) :seon.code.form/ns)] (vswap! tn conj n))))
      (when (contains? (:schema bdb) :seon.schema/form)
        (doseq [dt (d/datoms bdb :aevt :seon.schema/form) :when (> (long (:tx dt)) btx)]
          (when-let [k (ev (:e dt) :seon.schema/key)] (vswap! sk conj k)))))
    {:fkeys (vec (distinct @fk)) :tnss (vec (sort @tn)) :skeys (vec (sort @sk))}))

(defn- ^:seon.flow/edge scoped-branch-health [conn mdb bdb branch agent-id explicit-scope]
  (let [ag-sc (when agent-id (:seon.agent/scope (d/pull bdb '[:seon.agent/scope] [:seon.agent/id agent-id])))
        known (or explicit-scope (when (and ag-sc (not= ag-sc 'seon)) ag-sc))
        {:keys [fkeys tnss skeys]} (branch-delta-info conn bdb branch known)
        sc (or known (first tnss))
        cnss (if sc [sc] [])
        alerts (health/fast-scoped-alerts mdb bdb (or sc 'seon) agent-id nil {})
        sforms (into {} (keep (fn [k] (when-let [f (:seon.schema/form (d/pull bdb '[:seon.schema/form] [:seon.schema/key k]))] [k f]))) skeys)
        ctr-errs (if (seq fkeys)
                   (let [proj '[:seon.code.var/name :seon.code.var/ns :seon.code.var/private? :seon.code.var/macro?
                                :seon.code.var/test? :seon.code.var/arglists :seon.code.var/schema]]
                     (vec (for [fk (take 24 fkeys) dt (d/datoms bdb :avet :seon.code.var/form fk)
                                :let [v (d/pull bdb proj (:e dt)) vn (:seon.code.var/name v) n (:seon.code.var/ns v)]
                                :when (and vn n (not (str/ends-with? (name n) "-test")) (not (:seon.code.var/private? v))
                                           (not (:seon.code.var/macro? v)) (not (:seon.code.var/test? v))
                                           (seq (:seon.code.var/arglists v)) (nil? (:seon.code.var/schema v)))]
                            {:class :missing-contract :severity :error :kind :static :ns n :var vn :subject (str vn)
                             :branch branch :message (str "Public fn " vn " is missing :malli/schema.")})))
                   [])
        delta (fn [f] (when-let [cur (and (seq cnss) (seq (f bdb cnss)))]
                        (let [b (into #{} (map :subject) (f mdb cnss))]
                          (remove #(contains? b (:subject %)) cur))))
        probs (vec (distinct (concat (when (seq sforms) (health/schema-violations mdb sforms))
                                     ctr-errs (delta health/used-before-defined) (delta health/missing-ns-forms)
                                     alerts (when (and sc (not= sc 'seon)) (health/static-efficiency-warnings bdb sc)))))]
    (with-meta {:ok? (not (some #(= :error (:severity %)) probs)) :branch branch :scoped? true :scope sc
                :touched-nss tnss :counts (frequencies (map :class probs)) :problems probs
                :note "Fast scoped branch health (<15 MB). Pass :all or :main for full DB health."}
               {:seon/schema :seon.health/report})))

(defn health
  "Returns fast scoped/branch health (< 15 MB) by default on non-`:main` branches, or full
   `seon.health/report` when passed `:all`/`:main` (or when on `:main`). Pass `'ns` to scope explicitly."
  {:malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :any] [:maybe :map]]]}
  ([] (health nil))
  ([which]
   (when-let [{:keys [conn agent-id branch]} *agent-ctx*]
     (let [bdb @conn
           mdb (if (= branch :main) bdb (or (store/head conn :main) bdb))]
       (cond
         (= which :main) (health/report mdb)
         (or (= which :all) (and (nil? which) (= branch :main))) (health/report bdb)
         :else (scoped-branch-health conn mdb bdb branch agent-id (when (symbol? which) which)))))))

(defn install-repl-helpers!
  "Interns `user/*` editing, inspection, test, health, search, and task helpers into `u`."
  {:malli/schema [:=> [:cat :seon.host/ns] :any]}
  [u]
  (doseq [[s v] [['add-fn! #'edit/add-fn!] ['edit-fn! #'edit/edit-fn!] ['add-require! #'edit/add-require!]
                 ['set-meta! #'edit/set-meta!] ['ns-requires #'edit/ns-requires] ['unused-requires #'edit/unused-requires]
                 ['prune-requires! #'edit/prune-requires!] ['diff #'edit/diff] ['grep-src #'grep-src]
                 ['run-tests! #'run-tests!] ['health #'health] ['search #'search] ['help #'help]
                 ['autofix-health! #'autofix-health!] ['inspect-branch #'inspect-branch] ['save-example! #'save-example!]
                 ['create-task! #'create-task!] ['metrics #'metrics]]]
    (intern-doc! u s v)))