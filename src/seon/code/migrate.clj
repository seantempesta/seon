(ns seon.code.migrate
  "Datahike-native graph migration planner, atomic transaction builder, live entity/ref migrator,
   post-migration validator, and `user/*` agent helpers (`plan-rewrite`, `rewrite-graph!`,
   `rename-ns!`, `rename-schema!`, `migrate-data!`, `validate-migration`)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.parse :as parse]
            [seon.code.rewrite :as rw]
            [seon.health :as health]
            [seon.schema :as schema]))

(defn- src-may-match? [^String s needles]
  (boolean (and s (some #(str/includes? s %) needles))))

(defn- renamed-ns-path [old-path new-ns]
  (let [default-p (parse/ns->default-path new-ns)]
    (cond
      (and old-path (str/starts-with? old-path "test/") (not (str/starts-with? default-p "test/")))
      (str/replace-first default-p #"^src/" "test/")
      (and old-path (str/starts-with? old-path "src/") (not (str/starts-with? default-p "src/")))
      (str/replace-first default-p #"^test/" "src/")
      :else default-p)))

(defn- check-plan-collisions [db np]
  (let [ns-renames (:ns-renames np)
        existing-nss (into #{} (map :v) (d/datoms db :aevt :seon.code.ns/name))
        existing-schemas (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))
        ns-cols (for [[o n] ns-renames
                      :when (and (contains? existing-nss n) (not (contains? ns-renames n)))]
                  {:kind :ns-collision :from o :to n})
        dup-ns (for [[target srcs] (group-by val ns-renames) :when (> (count srcs) 1)]
                 {:kind :duplicate-ns-target :to target :from (mapv key srcs)})
        missing-ns (for [o (keys ns-renames) :when (not (contains? existing-nss o))]
                     {:kind :source-ns-missing :ns o})
        sch-cols (for [k existing-schemas
                       :let [k' (rw/rewrite-kw k np)]
                       :when (and (not= k' k)
                                  (contains? existing-schemas k')
                                  (= k' (rw/rewrite-kw k' np)))]
                   {:kind :schema-collision :from k :to k'})]
    (vec (concat ns-cols dup-ns missing-ns sch-cols))))

(defn- scan-code-rewrites [db np]
  (let [needles (:needles np)
        ns-renames (:ns-renames np)
        all-nss (->> (d/datoms db :aevt :seon.code.ns/name) (map :v) sort vec)]
    (reduce
     (fn [acc nsym]
       (let [old-path (:seon.code.ns/path (d/pull db '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
             target-ns (get ns-renames nsym)
             ns-src (when old-path (code/render-path db old-path))]
         (if (and ns-src (or target-ns (src-may-match? ns-src needles)))
           (let [r (rw/rewrite-clj-source ns-src np)
                 new-path (if target-ns (renamed-ns-path old-path target-ns) old-path)]
             (cond-> acc
               (not (:ok? r)) (update :errors conj {:ns nsym :error (:message r)})
               (and (:ok? r) (or target-ns (:changed? r)))
               (update :namespaces conj {:from-ns nsym :to-ns (or target-ns nsym)
                                         :old-path old-path :new-path new-path
                                         :renamed? (boolean target-ns)
                                         :changes (:changes r) :src (:src r)})
               (seq (:string-mentions r))
               (update :string-mentions conj {:ns nsym :mentions (:string-mentions r)})))
           acc)))
     {:namespaces [] :errors [] :string-mentions []}
     all-nss)))

(defn- scan-schema-rewrites [db np]
  (if-not (:rewrite-schemas? np)
    []
    (vec
     (keep (fn [dt]
             (let [ent (d/pull db '[:seon.schema/key :seon.schema/form :seon.schema/doc
                                    :seon.schema/examples :seon.schema/render] (:e dt))
                   k (:seon.schema/key ent)
                   k' (rw/rewrite-kw k np)
                   f' (rw/rewrite-edn-data (:seon.schema/form ent) np)
                   r' (when (:seon.schema/render ent) (rw/rewrite-sym (:seon.schema/render ent) np))
                   d' (when (:seon.schema/doc ent) (rw/rewrite-doc-text (:seon.schema/doc ent) np))]
               (when (or (not= k' k) (not= f' (:seon.schema/form ent))
                         (not= r' (:seon.schema/render ent)) (not= d' (:seon.schema/doc ent)))
                 {:old-key k :new-key k'
                  :entity (cond-> {:seon.schema/key k' :seon.schema/form f'}
                            d' (assoc :seon.schema/doc d')
                            (:seon.schema/examples ent) (assoc :seon.schema/examples (:seon.schema/examples ent))
                            r' (assoc :seon.schema/render r'))})))
           (d/datoms db :aevt :seon.schema/key)))))

(defn- scan-entity-rewrites [db attr-id pull-spec rewrite-fn]
  (if-not (contains? (:schema db) attr-id)
    []
    (vec
     (keep (fn [dt]
             (let [ent (dissoc (d/pull db pull-spec (:e dt)) :db/id)
                   ent' (rewrite-fn ent)]
               (when (and ent' (not= ent' ent))
                 {:id (get ent attr-id) :before ent :after ent'})))
           (d/datoms db :aevt attr-id)))))

(defn plan-rewrite
  "Dry-run analyzer for a graph-wide rewrite plan: checks collisions and reports every
   affected namespace, schema, rule, task, agent, doc section, and string literal mention."
  {:malli/schema [:=> [:cat :seon.host/db :map] :map]}
  [db plan]
  (let [np (rw/normalize-plan plan)
        collisions (check-plan-collisions db np)
        code-scan (scan-code-rewrites db np)
        schemas (scan-schema-rewrites db np)
        rules (if (:rewrite-rules? np)
                (scan-entity-rewrites db :seon.rule/id
                                      '[:seon.rule/id :seon.rule/when :seon.rule/assert :seon.rule/doc]
                                      (fn [e]
                                        (cond-> e
                                          (:seon.rule/when e) (update :seon.rule/when rw/rewrite-edn-data np)
                                          (:seon.rule/assert e) (update :seon.rule/assert rw/rewrite-edn-data np)
                                          (:seon.rule/doc e) (update :seon.rule/doc rw/rewrite-doc-text np))))
                [])
        tasks (if (:rewrite-tasks? np)
                (scan-entity-rewrites db :seon.task/id
                                      '[:seon.task/id :seon.task/scope :seon.task/tests :seon.task/plan]
                                      (fn [e]
                                        (cond-> e
                                          (:seon.task/scope e) (update :seon.task/scope rw/rewrite-sym np)
                                          (:seon.task/tests e) (update :seon.task/tests #(set (map (fn [s] (rw/rewrite-sym s np)) %)))
                                          (:seon.task/plan e) (update :seon.task/plan rw/rewrite-doc-text np))))
                [])
        agents (if (:rewrite-agents? np)
                 (scan-entity-rewrites db :seon.agent/id
                                       '[:seon.agent/id :seon.agent/scope :seon.agent/render-fn
                                         :seon.agent/tail-renders :seon.agent/pinned]
                                       (fn [e]
                                         (cond-> e
                                           (:seon.agent/scope e) (update :seon.agent/scope rw/rewrite-sym np)
                                           (:seon.agent/render-fn e) (update :seon.agent/render-fn rw/rewrite-sym np)
                                           (:seon.agent/tail-renders e) (update :seon.agent/tail-renders rw/rewrite-edn-data np)
                                           (:seon.agent/pinned e) (update :seon.agent/pinned rw/rewrite-edn-data np))))
                 [])
        docs (if (:rewrite-docs? np)
               (scan-entity-rewrites db :seon.code.doc/id
                                     '[:seon.code.doc/id :seon.code.doc/path :seon.code.doc/heading :seon.code.doc/body]
                                     (fn [e]
                                       (cond-> e
                                         (:seon.code.doc/heading e) (update :seon.code.doc/heading rw/rewrite-doc-text np)
                                         (:seon.code.doc/body e) (update :seon.code.doc/body rw/rewrite-doc-text np))))
               [])]
    {:ok? (and (empty? collisions) (empty? (:errors code-scan)))
     :collisions collisions
     :parse-errors (:errors code-scan)
     :counts {:namespaces (count (:namespaces code-scan))
              :renamed-namespaces (count (filter :renamed? (:namespaces code-scan)))
              :schemas (count schemas)
              :rules (count rules)
              :tasks (count tasks)
              :agents (count agents)
              :docs (count docs)
              :string-mention-nss (count (:string-mentions code-scan))}
     :namespaces (mapv #(dissoc % :src) (:namespaces code-scan))
     :schemas (mapv #(select-keys % [:old-key :new-key]) schemas)
     :rules (mapv :id rules)
     :tasks (mapv :id tasks)
     :agents (mapv :id agents)
     :docs (mapv :id docs)
     :string-mentions (:string-mentions code-scan)
     :_internal {:plan np :code-scan code-scan :schemas schemas
                 :rules rules :tasks tasks :agents agents :docs docs}}))

(defn- retract-ns-ops [db nsym]
  (concat (map #(vector :db/retractEntity [:seon.code.form/key (:seon.code.form/key %)]) (code/ns-forms db nsym))
          (map #(vector :db/retractEntity [:seon.code.var/name (:seon.code.var/name %)]) (code/ns-vars db nsym))
          [[:db/retractEntity [:seon.code.ns/name nsym]]]))

(defn build-rewrite-tx
  "Builds `{:ok? :dh-decls :schema-tx :code-tx :retracted-nss :touched-nss :summary}` from `db` and `plan`."
  {:malli/schema [:=> [:cat :seon.host/db :map] :map]}
  [db plan]
  (let [dry (plan-rewrite db plan)]
    (if-not (:ok? dry)
      (dissoc dry :_internal)
      (let [{:keys [code-scan schemas rules tasks agents docs]} (:_internal dry)
            new-forms-map (into {} (map (fn [{:keys [new-key entity]}] [new-key (:seon.schema/form entity)])) schemas)
            reg-info (when (seq new-forms-map) (schema/registry-for-db db new-forms-map))
            dh-decls (if reg-info
                       (->> (:declarations reg-info)
                            (remove #(contains? (:schema db) (:db/ident %)))
                            vec)
                       [])
            schema-tx (vec (mapcat (fn [{:keys [old-key new-key entity]}]
                                     (cond-> [entity]
                                       (not= old-key new-key)
                                       (conj [:db/retractEntity [:seon.schema/key old-key]])))
                                   schemas))
            renamed-map (into {} (keep (fn [{:keys [renamed? from-ns to-ns]}] (when renamed? [from-ns to-ns]))) (:namespaces code-scan))
            renamed-froms (set (keys renamed-map))
            retract-ops (vec (mapcat #(retract-ns-ops db %) renamed-froms))
            focus-ops (if (and (seq renamed-map) (contains? (:schema db) :seon.agent/focus))
                        (vec (keep (fn [dt]
                                     (let [nsym (:seon.code.ns/name (d/pull db (quote [:seon.code.ns/name]) (:v dt)))]
                                       (when-let [new-ns (get renamed-map nsym)]
                                         [:db/add (:e dt) :seon.agent/focus [:seon.code.ns/name new-ns]])))
                                   (d/datoms db :aevt :seon.agent/focus)))
                        [])
            ns-ops (vec (mapcat (fn [{:keys [new-path src renamed?]}]
                                  (code/file-import-tx (when-not renamed? db) new-path src false))
                                (:namespaces code-scan)))
            task-ops (vec (mapcat (fn [{:keys [id before after]}]
                                    (let [gone (set/difference (set (:seon.task/tests before)) (set (:seon.task/tests after)))]
                                      (concat (for [g gone] [:db/retract [:seon.task/id id] :seon.task/tests g])
                                              [after])))
                                  tasks))
            other-ops (vec (concat (map :after rules) task-ops (map :after agents) (map :after docs)))]
        {:ok? true
        :dh-decls dh-decls
        :schema-tx schema-tx
        :code-tx (vec (concat ns-ops other-ops))
        :focus-ops focus-ops
        :retract-ops retract-ops
        :retracted-nss (vec (sort renamed-froms))
        :touched-nss (vec (sort (map :to-ns (:namespaces code-scan))))
        :summary (dissoc dry :_internal)}))))

(defn validate-migration
  "Post-migration verification: compiles full Malli schema registry, checks all `:seon.schema/render`
   and `:seon.agent/render-fn` targets exist, and runs `seon.health/report` for error count."
  {:seon.eval/budget-override {:ticks 500000000 :bytes 1073741824}
   :malli/schema [:function
                  [:=> [:cat :seon.host/db] :map]
                  [:=> [:cat :seon.host/db :map] :map]]}
  ([db] (validate-migration db {}))
  ([db {:keys [check-health?] :or {check-health? true}}]
   (let [forms (schema/db-schema-forms db)
         schema-err (try (schema/compile-registry forms) nil
                         (catch Throwable ex (or (.getMessage ex) (str ex))))
         known-vars (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name))
         missing-renders
         (vec
          (concat
           (when (contains? (:schema db) :seon.schema/render)
             (keep (fn [dt]
                     (let [sym (:v dt)]
                       (when (and (symbol? sym) (not (contains? known-vars sym)) (nil? (try (resolve sym) (catch Throwable _ nil))))
                         {:kind :schema-render :key (:seon.schema/key (d/pull db '[:seon.schema/key] (:e dt))) :target sym})))
                   (d/datoms db :aevt :seon.schema/render)))
           (when (contains? (:schema db) :seon.agent/render-fn)
             (keep (fn [dt]
                     (let [sym (:v dt)]
                       (when (and (symbol? sym) (not (contains? known-vars sym)) (nil? (try (resolve sym) (catch Throwable _ nil))))
                         {:kind :agent-render :agent (:seon.agent/id (d/pull db '[:seon.agent/id] (:e dt))) :target sym})))
                   (d/datoms db :aevt :seon.agent/render-fn)))))
         hr (when check-health? (health/report db))
         h-errs (when hr (filterv #(= :error (:severity %)) (:problems hr)))]
     {:ok? (and (nil? schema-err) (empty? missing-renders) (empty? h-errs))
      :schema-count (count forms)
      :schema-error schema-err
      :missing-renderers missing-renders
      :health-ok? (if hr (:ok? hr) true)
      :health-error-count (count h-errs)
      :health-errors (vec (take 10 h-errs))})))

(defn- intern-helper! [u sym f doc-str budget]
  (let [v (intern u sym f)]
    (alter-meta! v merge (cond-> {:doc doc-str} budget (assoc :seon.eval/budget-override budget)))
    v))

(defn- migrate-ref-attr-ops [db old-attr {:keys [to lookup-attr on-missing] :as rm} retract-old?]
  (let [db-schema (:schema db)
        target-attr (or to old-attr)
        rm-retract? (if (contains? rm :retract-old?) (boolean (:retract-old? rm)) retract-old?)
        missing-mode (or on-missing :skip)
        ops (volatile! [])
        dangling (volatile! [])
        migrated (volatile! 0)
        retracted (volatile! 0)]
    (when (contains? db-schema old-attr)
      (doseq [dt (d/datoms db :aevt old-attr)]
        (let [e (:e dt)
              v (:v dt)
              resolved (when (and lookup-attr (contains? db-schema lookup-attr))
                         (:e (first (d/datoms db :avet lookup-attr v))))]
          (if resolved
            (do
              (vswap! ops conj [:db/add e target-attr resolved])
              (vswap! migrated inc)
              (when (and rm-retract? (not= old-attr target-attr))
                (vswap! ops conj [:db/retract e old-attr v])
                (vswap! retracted inc)))
            (do
              (vswap! dangling conj {:e e :attr old-attr :value v :lookup lookup-attr})
              (when (= :error missing-mode)
                (throw (ex-info (str "Dangling ref during migration of " old-attr ": " (pr-str v))
                                {:e e :attr old-attr :value v :lookup lookup-attr}))))))))
    {:ops @ops :dangling @dangling :migrated @migrated :retracted @retracted}))

(defn- migrate-rename-attr-ops [db old-a new-a np retract-old?]
  (let [ops (volatile! [])
        migrated (volatile! 0)
        retracted (volatile! 0)]
    (when (and (contains? (:schema db) old-a) (contains? (:schema db) new-a))
      (doseq [dt (d/datoms db :aevt old-a)]
        (let [e (:e dt)
              v (:v dt)
              v' (rw/rewrite-edn-data v np)]
          (vswap! ops conj [:db/add e new-a v'])
          (vswap! migrated inc)
          (when retract-old?
            (vswap! ops conj [:db/retract e old-a v])
            (vswap! retracted inc)))))
    {:ops @ops :migrated @migrated :retracted @retracted}))

(defn migrate-entities-tx
  "Builds live Datahike entity migration tx-data for attribute renames and scalar -> `:db.type/ref`
   upgrades via `:ref-migrations`. Never crashes on dangling refs; reports them in `:dangling`."
  {:malli/schema [:=> [:cat :seon.host/db :map] :map]}
  [db spec]
  (let [np (rw/normalize-plan spec)
        retract-old? (if (contains? spec :retract-old?) (boolean (:retract-old? spec)) true)
        db-schema (:schema db)
        ref-migs (:ref-migrations np)
        ref-parts (mapv (fn [[old-a rm]] (migrate-ref-attr-ops db old-a rm retract-old?)) ref-migs)
        ren-parts (mapv (fn [old-a] (migrate-rename-attr-ops db old-a (rw/rewrite-kw old-a np) np retract-old?))
                        (filter (fn [a]
                                  (and (qualified-keyword? a)
                                       (not (contains? ref-migs a))
                                       (not= a (rw/rewrite-kw a np))))
                                (keys db-schema)))
        all-parts (concat ref-parts ren-parts)]
    {:ok? true
     :tx-data (vec (mapcat :ops all-parts))
     :migrated (reduce + 0 (map :migrated all-parts))
     :retracted (reduce + 0 (map :retracted all-parts))
     :dangling (vec (mapcat :dangling ref-parts))}))

(defn apply-rewrite!
  "Executes a graph-wide rewrite plan on `conn` (`:dry-run? true` previews safely,
   `:migrate-data? true` migrates live datoms, `:reload? true` hot-reloads touched namespaces)."
  {:seon.eval/budget-override {:ticks 1000000000 :bytes 2147483648}
   :malli/schema [:=> [:cat :seon.host/conn :map] :map]}
  [conn {:keys [dry-run? migrate-data? reload?] :or {reload? true} :as plan}]
  (if dry-run?
    (assoc (dissoc (plan-rewrite @conn plan) :_internal) :dry-run? true)
    (let [built (build-rewrite-tx @conn plan)]
      (if-not (:ok? built)
        built
        (do
          (when (seq (:dh-decls built))
            (d/transact conn {:tx-data (:dh-decls built)}))
          (when (seq (:schema-tx built))
            (d/transact conn {:tx-data (:schema-tx built)}))
          (when (seq (:code-tx built))
            (d/transact conn {:tx-data (:code-tx built)}))
          (when (seq (:focus-ops built))
            (d/transact conn {:tx-data (:focus-ops built)}))
          (when (seq (:retract-ops built))
            (d/transact conn {:tx-data (:retract-ops built)}))
          (let [mig-res (when migrate-data?
                          (let [m (migrate-entities-tx @conn plan)]
                            (when (seq (:tx-data m))
                              (d/transact conn {:tx-data (:tx-data m)}))
                            (dissoc m :tx-data)))
                db-after @conn
                load-errs
                (if reload?
                  (let [upserts (vec (mapcat #(code/ns-forms db-after %) (:touched-nss built)))
                      rkeys (into #{} (map #(str % "#ns")) (:retracted-nss built))]
                  (if (seq upserts)
                    (code/apply-overlay! db-after {:upserts upserts :retracted-keys rkeys} true)
                    []))
                  [])]
            (cond-> {:ok? (empty? load-errs)
                     :tx (:max-tx db-after)
                     :counts (get-in built [:summary :counts])
                     :retracted-nss (:retracted-nss built)
                     :touched-nss (:touched-nss built)
                     :string-mentions (get-in built [:summary :string-mentions])
                     :load-errors load-errs}
              mig-res (assoc :data-migration mig-res))))))))

(defn apply-migrate-data!
  "Applies `migrate-entities-tx` on `conn` (pass `{:dry-run? true}` to preview)."
  {:seon.eval/budget-override {:ticks 1000000000 :bytes 2147483648}
   :malli/schema [:=> [:cat :seon.host/conn :map] :map]}
  [conn {:keys [dry-run?] :as spec}]
  (let [res (migrate-entities-tx @conn spec)]
    (if dry-run?
      (assoc (dissoc res :tx-data) :dry-run? true :op-count (count (:tx-data res)))
      (let [r (when (seq (:tx-data res))
                (d/transact conn {:tx-data (:tx-data res)}))]
        (-> (dissoc res :tx-data)
            (assoc :tx (if r (:max-tx (:db-after r)) (:max-tx @conn))))))))

(defn install-user-helpers!
  "Interns `user/{plan-rewrite,rewrite-graph!,rename-ns!,rename-schema!,migrate-data!,validate-migration}`
   with proper `:doc` and `:seon.eval/budget-override` so any Seon agent can refactor the graph safely."
  {:malli/schema [:=> [:cat :seon.host/ns :any :any] :any]}
  [u get-agent-ctx-fn refresh-ctx-fn]
  (let [big-budget {:ticks 1000000000 :bytes 2147483648}]
    (intern-helper! u 'plan-rewrite
                    (fn [plan]
                      (when-let [{:keys [conn]} (get-agent-ctx-fn)]
                        (dissoc (plan-rewrite @conn plan) :_internal)))
                    "Dry-run graph rewrite plan (collisions + impact across code/schemas/rules/tasks/agents/docs)."
                    big-budget)
    (intern-helper! u 'rewrite-graph!
                    (fn [plan]
                      (when-let [{:keys [conn]} (get-agent-ctx-fn)]
                        (let [res (apply-rewrite! conn plan)]
                          (when (and (:ok? res) (not (:dry-run? res)) refresh-ctx-fn)
                            (refresh-ctx-fn @conn nil))
                          res)))
                    "Atomically rewrites namespaces, vars, schemas, rules, tasks, agents, and docs across the Datahike graph."
                    big-budget)
    (intern-helper! u 'rename-ns!
                    (fn
                      ([old-ns new-ns] ((resolve 'user/rename-ns!) old-ns new-ns {}))
                      ([old-ns new-ns opts]
                       ((resolve 'user/rewrite-graph!) (merge opts {:ns-renames {old-ns new-ns}}))))
                    "Renames a Clojure namespace and updates all callers, schemas, rules, tasks, agents, and docs."
                    big-budget)
    (intern-helper! u 'rename-schema!
                    (fn
                      ([old-k new-k] ((resolve 'user/rename-schema!) old-k new-k {}))
                      ([old-k new-k opts]
                       (let [plan (if (and (qualified-keyword? old-k) (qualified-keyword? new-k))
                                    {:attr-renames {old-k new-k}}
                                    {:attr-ns-renames {(str old-k) (str new-k)}})]
                         ((resolve 'user/rewrite-graph!) (merge opts plan)))))
                    "Renames a schema/attribute keyword or attribute namespace prefix across the entire Datahike graph."
                    big-budget)
    (intern-helper! u 'migrate-data!
                    (fn [spec]
                      (when-let [{:keys [conn]} (get-agent-ctx-fn)]
                        (let [res (apply-migrate-data! conn spec)]
                          (when (and (:ok? res) (not (:dry-run? res)) refresh-ctx-fn)
                            (refresh-ctx-fn @conn nil))
                          res)))
                    "Migrates live Datahike entity datoms (attribute renames & scalar->ref upgrades) without crashing on dangling refs."
                    big-budget)
    (intern-helper! u 'validate-migration
                    (fn
                      ([] ((resolve 'user/validate-migration) {}))
                      ([opts]
                       (when-let [{:keys [conn]} (get-agent-ctx-fn)]
                         (validate-migration @conn opts))))
                    "Validates schema registry compilation, renderer var targets, and health after a graph migration."
                    big-budget)))
