(ns seon.repl
  "Interactive REPL commands (`run-tests!`, `help`, `simulate-context`, `check-corpus`, `tune-context!`) and `user/*` installer."
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
            [seon.health.metrics :as hmetrics]
            [seon.repl.search :as search]
            [seon.bench.golden :as bgolden]
            [seon.health.liveness :as hliveness]
            [seon.render.wire]
            [seon.bench.helpers :as bhelpers]
            [seon.bench.lab :as blab]
            [seon.schema :as schema]
            [seon.repl.context :as context]))

(defn- intern-doc! [u sym f]
  (let [v (intern u sym (if (var? f) (fn [& args] (apply @f args)) f))
        m (when (var? f) (meta f))
        src (when (and (:ns m) (:name m)) (symbol (str (ns-name (:ns m))) (name (:name m))))
        km (cond-> (select-keys m [:doc :arglists :malli/schema :seon.eval/budget-override]) src (assoc :seon.repl/source src))]
    (when (seq km) (alter-meta! v merge km))
    v))

(defn autofix-health!
  "Executes `:fix` actions from `seon.health/report` (`:ns`, `:class`, `:dry-run?`)."
  {:malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :map] [:maybe :map]]], :seon.flow/edge true}
  ([] (autofix-health! {}))
  ([{:keys [ns class dry-run?]}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [targets (filterv #(and (:fix %) (or (nil? ns) (= ns (:ns %)) (health/ns-in-scope? ns (:ns %))) (or (nil? class) (= class (:class %)))) (:problems (health/report @conn)))
           results (mapv (fn [p]
                           (let [{:keys [fn args]} (:fix p) f (or (try (resolve fn) (catch Throwable _ nil)) (try (requiring-resolve fn) (catch Throwable _ nil)))]
                             (if-not f
                               {:ok? false :class (:class p) :subject (:subject p) :error :fix-fn-not-found :fn fn}
                               (try
                                 (let [r (apply f (if dry-run? (concat args [{:dry-run? true}]) args))]
                                   {:ok? (boolean (:ok? r true)) :class (:class p) :subject (:subject p) :changed? (boolean (:changed? r true)) :result (dissoc r :diff)})
                                 (catch Throwable ex {:ok? false :class (:class p) :subject (:subject p) :error (or (.getMessage ex) (str ex))})))))
                         targets)]
       {:ok? (every? :ok? results) :dry-run? (boolean dry-run?) :count (count results) :results results}))))

(defn save-example!
  "Snapshots a completed agent session into `:example/<name>` for `resources/seon/agents/examples.edn`."
  {:malli/schema [:function [:=> [:cat :keyword :keyword] [:maybe :map]] [:=> [:cat :keyword :keyword :map] [:maybe :map]]] :seon.flow/edge true}
  ([source-agent-id example-id] (save-example! source-agent-id example-id {}))
  ([source-agent-id example-id {:keys [goal max-evals] :or {max-evals 8}}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (if-not (= "example" (namespace example-id))
       {:ok? false :error :invalid-example-id :hint "Example ID must be in :example/*."}
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
                                (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0))) (take max-evals) vec)
                 trunc (fn [s n] (let [st (str s)] (if (> (count st) n) (str (subs st 0 n) "…") st)))
                 ex-ent (cond-> {:db/id (str "agent/" (name example-id)) :seon.agent/id example-id :seon.agent/branch :main
                                 :seon.agent/goal (or goal (:seon.agent/goal ag) "Curated session example.")
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
                                       (:seon.agent.eval/result-schema ev) (assoc :seon.agent.eval/result-schema (:seon.agent.eval/result-schema ev))
                                       (contains? (:schema @conn) :seon.agent.eval/agent-ref) (assoc :seon.agent.eval/agent-ref (or (:e (first (d/datoms @conn :avet :seon.agent/id example-id))) (str "agent/" (name example-id)))))))
                                 raw-evals))
                 r (d/transact conn {:tx-data (into [ex-ent] eval-ents)})]
             (ctx/refresh-active! (:db-after r) (:tx-data r))
             {:ok? true :example-id example-id :source-agent source-agent-id :evals-saved (count eval-ents)
              :seed-path "resources/seon/agents/examples.edn" :tx (:max-tx (:db-after r))})))))))

(defn create-task!
  "Creates a linked task on `:main` via `:transactor` (`resources/seon/tasks.edn`)."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [id from-template scope title plan needs tests spawn-agent? agent-id role auto-merge? parent template? owner created-by summary assign?]}]
  (when-let [{:keys [conn] cur-aid :agent-id} *agent-ctx*]
    (let [mdb (store/head conn :main)
          cur-task (when cur-aid (or (first (keep :seon.task/id (:seon.agent/tasks (d/pull @conn '[{:seon.agent/tasks [:seon.task/id]}] [:seon.agent/id cur-aid]))))
                                     (first (keep :seon.task/id (:seon.agent/tasks (d/pull mdb '[{:seon.agent/tasks [:seon.task/id]}] [:seon.agent/id cur-aid]))))))
          tpl (when (and from-template (first (d/datoms mdb :avet :seon.task/id from-template)))
                (d/pull mdb '[:seon.task/scope :seon.task/plan :seon.task/tests :seon.task/needs] [:seon.task/id from-template]))
          eff-scope (or scope (:seon.task/scope tpl))
          eff-plan (if (and (:seon.task/plan tpl) (not (str/blank? (str plan)))) (str plan "\n\n---\n" (:seon.task/plan tpl)) (or plan (:seon.task/plan tpl) ""))
          eff-tests (into (set (:seon.task/tests tpl)) tests) eff-needs (into (set (:seon.task/needs tpl)) needs)
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
  "Live sliding-window task completion & tool-frustration scorecard over the latest `window` (default 10) agents."
  {:malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :int] [:maybe :map]]], :seon.eval/budget-override {:ticks 300000000, :bytes 1073741824}, :seon.flow/edge true}
  ([] (metrics 10))
  ([window]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [mdb (or (try (store/head conn :main) (catch Throwable _ nil)) @conn)]
       (hmetrics/agent-learning-metrics conn mdb {:window window})))))

(defn grep-src [& args] (apply search/grep-src args))

(defn search [& args] (apply search/search args))

(defn- main-test-pass? [mdb vsym]
  (when (and mdb vsym (contains? (:schema mdb) :seon.test/var) (contains? (:schema mdb) :seon.test/pass?))
    (some-> (->> (d/datoms mdb :avet :seon.test/var vsym)
                 (keep (fn [dt] (when-let [pdt (first (d/datoms mdb :eavt (:e dt) :seon.test/pass?))]
                                  {:tx (long (or (:tx pdt) (:tx dt) 0)) :e (:e dt) :pass? (boolean (:v pdt))})))
                 (sort-by (fn [{:keys [tx e]}] [(- tx) (- e)])) first)
            :pass?)))

(defn- concise-failure-line [f main-pass?]
  (let [ls (->> (str/split-lines (str (:seon.test/report f))) (map str/trim) (remove str/blank?) (take 3))
        s (str/join " | " ls) snip (if (> (count s) 220) (str (subs s 0 217) "…") s)]
    (str (:seon.test/var f) (when (false? main-pass?) " [PRE-EXISTING FAIL ON :main]") ": " (if (str/blank? snip) "FAIL" snip))))

(defn- ^:seon.flow/edge branch-delta-info [conn bdb branch scope]
  (let [bc (try (d/merge-base conn :main branch) (catch Throwable _ nil))
        btx (if-let [base-db (and bc (or (d/commit-as-db conn bc) (store/head conn :main)))]
              (long (or (:max-tx base-db) Long/MAX_VALUE)) Long/MAX_VALUE)
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
        alerts (health/fast-scoped-alerts mdb bdb (or sc 'seon) agent-id nil {})
        sforms (into {} (keep (fn [k] (when-let [f (:seon.schema/form (d/pull bdb '[:seon.schema/form] [:seon.schema/key k]))] [k f]))) skeys)
        caused (if (and mdb (not= mdb bdb))
                 (health/caused-by-branch mdb bdb sforms (when (seq tnss) tnss))
                 (when (seq sforms) (health/schema-violations mdb sforms)))
        ctr-errs (if (seq fkeys)
                   (let [proj '[:seon.code.var/name :seon.code.var/ns :seon.code.var/private? :seon.code.var/macro? :seon.code.var/test? :seon.code.var/arglists :seon.code.var/schema]]
                     (vec (for [fk (take 24 fkeys) dt (d/datoms bdb :avet :seon.code.var/form fk)
                                :let [v (d/pull bdb proj (:e dt)) vn (:seon.code.var/name v) n (:seon.code.var/ns v)]
                                :when (and vn n (not (str/ends-with? (name n) "-test")) (not (:seon.code.var/private? v))
                                           (not (:seon.code.var/macro? v)) (not (:seon.code.var/test? v))
                                           (seq (:seon.code.var/arglists v)) (nil? (:seon.code.var/schema v)))]
                            {:class :missing-contract :severity :error :kind :static :ns n :var vn :subject (str vn)
                             :branch branch :message (str "Public fn " vn " is missing :malli/schema.")})))
                   [])
        probs (vec (distinct (concat caused ctr-errs alerts
                                     (when (and sc (not= sc 'seon)) (health/static-efficiency-warnings bdb sc)))))]
    (with-meta {:ok? (not (some #(= :error (:severity %)) probs)) :branch branch :scoped? true :scope sc
                :touched-nss tnss :counts (frequencies (map :class probs)) :problems probs
                :note "Scoped branch health (full caused-by-branch merge gates). Pass :all or :main for full DB health."}
               {:seon/schema :seon.health/report})))

(defn health
  "Scoped branch health (<15 MB) by default; pass `:all`, `:main`, or `'ns`."
  {:malli/schema [:function [:=> [:cat] [:maybe :seon.health/report]] [:=> [:cat [:maybe [:or :keyword :symbol :map]]] [:maybe :seon.health/report]]], :seon.eval/budget-override {:ticks 600000000, :bytes 1073741824}, :seon.flow/edge true}
  ([] (health nil))
  ([which]
   (when-let [{:keys [conn agent-id branch]} *agent-ctx*]
     (let [bdb @conn mdb (if (= branch :main) bdb (or (store/head conn :main) bdb))]
       (cond
         (= which :main) (health/report mdb)
         (or (= which :all) (and (nil? which) (= branch :main))) (health/report bdb)
         :else (scoped-branch-health conn mdb bdb branch agent-id (when (symbol? which) which)))))))

(defn- branch-merge-base-cid [mdb bdb branch]
  (when (and (not= branch :main) mdb bdb (get-in bdb [:config :commit-graph?] true))
    (let [mcid (d/commit-id mdb) bcid (d/commit-id bdb) st (:store bdb)]
      (when (and mcid bcid st (d/commit-graph-record st mcid) (d/commit-graph-record st bcid))
        (d/merge-base bdb mcid bcid)))))

(defn inspect-branch
  "Inspects a branch or agent: changed forms, health errors, proofs, recent evals, and diffs."
  {:malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :keyword] [:maybe :map]]]}
  ([] (inspect-branch (:branch *agent-ctx*)))
  ([branch-or-agent]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [mdb (store/head conn :main)
           ag-ent (when (first (d/datoms mdb :avet :seon.agent/id branch-or-agent))
                    (d/pull mdb '[:seon.agent/id :seon.agent/branch :seon.agent/scope :seon.agent/goal {:seon.agent/tasks [:seon.task/id]}] [:seon.agent/id branch-or-agent]))
           branch (or (:seon.agent/branch ag-ent) branch-or-agent)
           bdb (when (contains? (d/branches conn) branch) (store/head conn branch))]
       (if-not bdb
         {:ok? false :error :branch-not-found :target branch-or-agent :branch branch}
         (let [base-cid (branch-merge-base-cid mdb bdb branch) base-db (when base-cid (d/commit-as-db conn base-cid))
               ops (if base-cid (code/changed-form-ops conn base-cid (d/commit-id bdb)) {:upserts [] :retracted-keys #{} :schema-keys #{}})
               tnss (vec (distinct (keep :seon.code.form/ns (:upserts ops))))
               errs (if (= branch :main) [] (health/caused-by-branch mdb bdb {} tnss))
               aid (or (:seon.agent/id ag-ent)
                       (some #(let [a (d/pull bdb '[:seon.agent/id :seon.agent/branch] (:e %))] (when (= branch (:seon.agent/branch a)) (:seon.agent/id a))) (d/datoms bdb :aevt :seon.agent/id)))
               b-ag (when aid (d/pull bdb '[:seon.agent/last-proofs :seon.agent/ready? :seon.agent/waiting?] [:seon.agent/id aid]))
               recent-evals (when aid (->> (d/datoms bdb :avet :seon.agent.eval/agent aid)
                                           (map #(d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/handle :seon.agent.eval/intent :seon.agent.eval/error :seon.agent.eval/ms] (:e %)))
                                           (sort-by #(or (:seon.agent.eval/turn %) 0)) (take-last 6) vec))
               diffs (into {} (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                                      (let [old-s (when base-db (:seon.code.form/src (d/pull base-db '[:seon.code.form/src] [:seon.code.form/key key])))
                                            ud (cst/unified-diff (or old-s "") (or src "") key)]
                                        [key (if (> (count ud) 1200) (str (subs ud 0 1200) "\n…") ud)]))) (take 6 (:upserts ops)))]
           {:ok? true :agent aid :branch branch :branch-tx (:max-tx bdb) :ready? (boolean (:seon.agent/ready? b-ag))
            :last-proofs (:seon.agent/last-proofs b-ag) :upserts (vec (sort (map :seon.code.form/key (:upserts ops))))
            :retracted (vec (sort (:retracted-keys ops))) :schemas (vec (sort (:schema-keys ops)))
            :health-errors errs :recent-evals recent-evals :diffs diffs}))))))

(defn ^{:seon.code/reexport 'seon.repl.context/help} help [& args] (apply context/help args))

(defn ^:seon.flow/edge ^{:seon.code/reexport 'seon.repl.context/simulate-context} simulate-context [& args] (apply context/simulate-context args))

(defn ^:seon.flow/edge ^{:seon.code/reexport 'seon.repl.context/check-corpus} check-corpus [& args] (apply context/check-corpus args))

(defn ^:seon.flow/edge ^{:seon.code/reexport 'seon.repl.context/tune-context!} tune-context! [& args] (apply context/tune-context! args))

(defn- order-direct-tests-first [ops tlist test-syms]
  (let [direct-nss (into #{} (keep (fn [x]
                                     (let [s (if (string? x) (symbol x) x)]
                                       (when (symbol? s)
                                         (let [np (or (namespace s) (name s))]
                                           (if (str/ends-with? np "-test") np (str np "-test")))))))
                         (concat (keep :seon.code.form/ns (:upserts ops)) tlist))]
    (vec (sort-by (fn [s] [(if (contains? direct-nss (namespace s)) 0 1) (str s)]) test-syms))))

(defn- resolve-run-tests-targets [bdb ops target more]
  (let [raw (if (seq more) (cons target more) [target])
        [pos-args kw-args] (split-with (complement keyword?) raw)
        kw-map (when (and (seq kw-args) (even? (count kw-args))) (apply hash-map kw-args))
        opt-m (merge (when (map? (last pos-args)) (last pos-args)) kw-map)
        tlist (if-let [vs (or (:only opt-m) (:vars opt-m) (:tests opt-m))]
                (if (coll? vs) (vec vs) [vs])
                (vec (mapcat #(cond (or (nil? %) (map? %) (keyword? %)) nil (coll? %) % :else [%]) pos-args)))
        test-syms (if (seq tlist)
                    (->> tlist
                         (mapcat (fn [x]
                                   (let [s (if (string? x) (symbol x) x)]
                                     (cond (namespace s) (if (:seon.code.var/test? (d/pull bdb '[:seon.code.var/test?] [:seon.code.var/name s]))
                                                           [s] (code/reaching-tests bdb (symbol (namespace s))))
                                           (str/ends-with? (name s) "-test")
                                           (keep #(let [v (d/pull bdb '[:seon.code.var/name :seon.code.var/test?] (:e %))] (when (:seon.code.var/test? v) (:seon.code.var/name v)))
                                                 (d/datoms bdb :avet :seon.code.var/ns s))
                                           :else (code/reaching-tests bdb s)))))
                         distinct sort vec)
                    (->> (distinct (keep :seon.code.form/ns (:upserts ops))) (mapcat #(code/reaching-tests bdb %)) distinct sort vec))
        gen-vars (if (seq tlist)
                   (vec (distinct (keep (fn [x]
                                          (let [s (if (string? x) (symbol x) x)]
                                            (when (and (qualified-symbol? s) (not (str/ends-with? (namespace s) "-test")))
                                              (let [v (d/pull bdb '[:seon.code.var/test? :seon.code.var/flow-edge? :seon.code.var/schema] [:seon.code.var/name s])]
                                                (when (and (not (:seon.code.var/test? v)) (not (:seon.code.var/flow-edge? v))
                                                           (schema/generatable-function-schema? (:seon.code.var/schema v)))
                                                  s)))))
                                        tlist)))
                   (let [ed-eids (vec (distinct (mapcat #(map :e (d/datoms bdb :avet :seon.code.var/form (:seon.code.form/key %))) (:upserts ops))))
                         ed-nss (into #{} (keep :seon.code.form/ns) (:upserts ops))
                         same-ns-callers (when (contains? (:schema bdb) :seon.code.var/calls)
                                           (into [] (comp (mapcat #(map :e (d/datoms bdb :avet :seon.code.var/calls %)))
                                                          (filter #(contains? ed-nss (:seon.code.var/ns (d/pull bdb '[:seon.code.var/ns] %)))))
                                                 ed-eids))]
                     (->> (concat ed-eids same-ns-callers)
                          (keep (fn [e]
                                  (let [v (d/pull bdb '[:seon.code.var/name :seon.code.var/test? :seon.code.var/flow-edge? :seon.code.var/schema] e)
                                        vn (:seon.code.var/name v)]
                                    (when (and vn (not (:seon.code.var/test? v)) (not (:seon.code.var/flow-edge? v))
                                               (not (str/ends-with? (namespace vn) "-test"))
                                               (schema/generatable-function-schema? (:seon.code.var/schema v)))
                                      vn))))
                          distinct sort vec)))]
    {:ordered-tests (order-direct-tests-first ops tlist test-syms) :gen-vars gen-vars}))

(defn run-tests!
  "Runs reach-selected or explicit `deftest`s (direct tests first, soft-capped inside in-process eval budget) AND `seed=42` Malli generative checks on edited/target contracted vars."
  {:malli/schema [:=> [:cat [:* :any]] [:maybe :map]], :seon.eval/budget-override {:ticks 6000000000, :bytes 12884901888}, :seon.flow/edge true}
  ([] (run-tests! nil))
  ([target & more]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [t0 (System/nanoTime) bdb @conn mdb (if (= branch :main) bdb (or (store/head conn :main) bdb))
           base-cid (branch-merge-base-cid mdb bdb branch)
           ops (when base-cid (code/changed-form-ops conn base-cid (d/commit-id bdb)))
           _ (when (or (seq (:upserts ops)) (seq (:retracted-keys ops))) (wsup/apply-form-overlay! bdb (select-keys ops [:upserts :retracted-keys])))
           {:keys [ordered-tests gen-vars]} (resolve-run-tests-targets bdb ops target more)
           b0 (code/alloc-bytes) soft-byte-cap 4294967296
           {:keys [results skipped-by-budget gen-results]}
           (wsup/call-with-candidate-overrides bdb
             (fn [_]
               (let [[ran-res skipped]
                     (loop [rem ordered-tests acc []]
                       (if (empty? rem)
                         [acc 0]
                         (if (and (seq acc) (> (- (code/alloc-bytes) b0) soft-byte-cap))
                           [acc (count rem)]
                           (recur (rest rem) (conj acc (binding [fcalls/*agent-ctx* nil] (wsup/run-single-test-var bdb (first rem) (str "local:" (first rem)))))))))]
                 {:results ran-res
                  :skipped-by-budget skipped
                  :gen-results (vec (keep (fn [vsym]
                                 (when (< (- (code/alloc-bytes) b0) soft-byte-cap)
                                   (when-let [r (schema/stress-var bdb vsym {:samples 100 :seed 42 :measure-fn code/measure-call})]
                                     (when-not (:skipped? r)
                                       {:seon.test/var vsym :seon.test/kind :generative :seon.test/pass? (boolean (:ok? r))
                                        :seon.test/ms (double (:ms r 0.0)) :seon.test/report (or (:reason r) "PASS")}))))
                               gen-vars))})))
           all-res (into results gen-results)
           fails (mapv (fn [f] (let [mp (main-test-pass? mdb (:seon.test/var f))]
                                 (assoc (select-keys f [:seon.test/var :seon.test/kind :seon.test/report :seon.test/ms]) :main-pass? mp :summary (concise-failure-line f mp))))
                       (filterv #(not (:seon.test/pass? %)) all-res))
           ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 10000.0)) 100.0)]
       (when (seq fails) (println (str "FAIL (" (count fails) "/" (count all-res) "): " (str/join " | " (map :summary fails)))))
       (cond-> {:ok? (empty? fails) :ran (count all-res) :passed (- (count all-res) (count fails)) :failed (count fails)
                :gen-checked (count gen-results) :ms ms :failures fails
                :results (mapv #(select-keys % [:seon.test/var :seon.test/kind :seon.test/pass? :seon.test/ms]) all-res)}
         (pos? (long (or skipped-by-budget 0)))
         (assoc :truncated-by-budget? true
                :skipped-heavy-tests skipped-by-budget
                :note "Direct & fast reached tests ran within in-process eval cap; full reach suite runs automatically in parallel COW workers on merge.")
         (seq fails) (assoc :failure-lines (mapv :summary fails)))))))

(defn install-repl-helpers!
  "Interns `user/*` REPL helpers into `u`."
  {:malli/schema [:=> [:cat :seon.host/ns] :any]}
  [u]
  (doseq [[s v] [['add-fn! #'edit/add-fn!] ['edit-fn! #'edit/edit-fn!] ['add-require! #'edit/add-require!]
                 ['set-meta! #'edit/set-meta!] ['ns-requires #'edit/ns-requires] ['unused-requires #'edit/unused-requires]
                 ['prune-requires! #'edit/prune-requires!] ['diff #'edit/diff] ['grep-src #'search/grep-src]
                 ['run-tests! #'run-tests!] ['stress-var #'schema/stress-var] ['health #'health] ['search #'search/search] ['ns-list #'search/ns-list] ['help #'context/help]
                 ['autofix-health! #'autofix-health!] ['inspect-branch #'inspect-branch] ['save-example! #'save-example!]
                 ['create-task! #'create-task!] ['metrics #'metrics]
                 ['simulate-context #'context/simulate-context] ['check-corpus #'context/check-corpus] ['tune-context! #'context/tune-context!]
                 ['friction #'hmetrics/repl-friction] ['golden-scoreboard #'bgolden/repl-golden-scoreboard]
                 ['run-golden-suite! #'bgolden/run-golden-suite!] ['run-ab! #'bgolden/run-ab!] ['summarize-ab #'bgolden/summarize-ab]
                 ['sample-random-tasks #'bhelpers/repl-sample-random-tasks] ['run-supervisor-cycle! #'blab/run-supervisor-cycle!] ['bench-run #'blab/bench-run]
                 ['liveness #'hliveness/check-liveness]]]
    (intern-doc! u s v)))