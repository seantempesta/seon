(ns seon.fork.helpers
  "Agent REPL `user/*` inspection, Datahike time-travel, code-editing, schema, and health helpers."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
            [seon.health :as health]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.repl :as repl]
            [seon.fork.container :as fcontainer]
            [seon.cite :as cite]
            [seon.code.io :as cio]
            [seon.vector :as vec]
            [seon.vector.flow :as vflow]))

(defn source
  "Reads source of `'ns/fn`, `'ns`, `'ns#ns`, `:seon.schema/key`, `\"docs/f.md#sec\"`,
   `'user/<helper>`, a vector of targets, or variadic multiple targets `(source 'a/f 'b/g)`.
   When the 2nd arg is `{:lines [start end]}` (with no further args), slices a String result."
  {:malli/schema [:=> [:cat :any [:* :any]] :any]}
  ([target]
   (cond
     (or (vector? target) (set? target) (seq? target))
     (into {} (map (fn [t] [t (source t)])) target)

     (and (symbol? target) (= "user" (namespace target)))
     (repl/help target)

     :else
     (when-let [{:keys [conn branch]} *agent-ctx*]
       (let [bdb @conn
             mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
             base-db (when (not= branch :main)
                       (try (store/merge-base-db conn :main branch) (catch Throwable _ nil)))]
         (cond
           (and (or (symbol? target) (string? target)) (str/ends-with? (str target) "#ns"))
           (let [fk (str target)]
             (or (:seon.code.form/src (d/pull bdb '[:seon.code.form/src] [:seon.code.form/key fk]))
                 (:seon.code.form/src (d/pull mdb '[:seon.code.form/src] [:seon.code.form/key fk]))))

           (keyword? target)
           (let [pull-s #(let [m (d/pull % '[:seon.schema/key :seon.schema/form :seon.schema/doc
                                             :seon.schema/examples]
                                         [:seon.schema/key target])]
                           (when (:seon.schema/key m) m))]
             (or (pull-s bdb) (pull-s mdb)))

           (and (string? target) (or (str/includes? target "#") (str/ends-with? target ".md")))
           (if (str/includes? target "#")
             (let [pull-d #(let [m (d/pull % '[:seon.code.doc/id :seon.code.doc/path
                                               :seon.code.doc/heading :seon.code.doc/body]
                                           [:seon.code.doc/id target])]
                             (when (:seon.code.doc/id m)
                               (str (:seon.code.doc/heading m "") (:seon.code.doc/body m ""))))]
               (or (pull-d bdb) (pull-d mdb)))
             (or (code/render-doc bdb target) (code/render-doc mdb target)))

           :else
           (let [s (if (string? target) (symbol target) target)
                 src-in (fn [db]
                          (when-let [fkey (:seon.code.var/form (d/pull db '[:seon.code.var/form] [:seon.code.var/name s]))]
                            (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key fkey]))))]
             (if (namespace s)
               (let [b-src (src-in bdb)
                     base-src (when base-db (src-in base-db))
                     m-src (src-in mdb)]
                 (cond
                   (and (nil? b-src) (some? base-src)) nil
                   (and b-src (not= b-src base-src)) b-src
                   :else (or m-src b-src)))
               (let [ns-forms-of (fn [db]
                                   (->> (d/datoms db :avet :seon.code.form/ns s)
                                        (map #(d/pull db '[:seon.code.form/key :seon.code.form/order :seon.code.form/src] (:e %)))
                                        (sort-by #(or (:seon.code.form/order %) 0))))
                     m-fs (ns-forms-of mdb)
                     b-fs (ns-forms-of bdb)
                     b-by-k (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) b-fs)
                     base-by-k (if base-db
                                 (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) (ns-forms-of base-db))
                                 {})
                     m-keys (into #{} (map :seon.code.form/key) m-fs)
                     kept-m (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                                    (let [bs (get b-by-k key)]
                                      (cond
                                        (and (nil? bs) (contains? base-by-k key)) nil
                                        (and bs (not= bs (get base-by-k key))) bs
                                        :else src)))
                                  (if (seq m-fs) m-fs b-fs))
                     added-b (when (seq m-fs)
                               (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                                       (when-not (contains? m-keys key) src))
                                     b-fs))]
                 (str/join "\n\n" (concat kept-m added-b))))))))))
  ([target second-arg & more]
  (let [lines (cond
                (and (empty? more) (map? second-arg)) (:lines
                                                        second-arg)
                (and
                  (empty? more)
                  (vector? second-arg)
                  (= 2 (count second-arg))
                  (every? integer? second-arg)) second-arg
                (and
                  (= 1 (count more))
                  (integer? second-arg)
                  (integer? (first more))) [second-arg (first more)]
                :else nil)]
    (if lines
      (let [res (source target)]
        (if (and (string? res) (vector? lines) (= 2 (count lines)))
          (let [ls (vec (str/split-lines res))
                n (count ls)
                a (max 1 (long (first lines)))
                b (min n (long (second lines)))]
            (if (> a b) "" (str/join "\n" (subvec ls (dec a) b))))
          res))
      (source (into [target second-arg] more))))))

(defn similar
  "Semantic vector search for agents: `(user/similar q)`, `(user/similar q k)`, or `(user/similar q k opts)`.
   Accepts a query string (routed via daemon `:vectors`), float vector, entity ID, lookup ref, var symbol, schema/task keyword, or doc section ID."
  {:malli/schema [:function [:=> [:cat :any] :map] [:=> [:cat :any :any] :map] [:=> [:cat :any :any :map] :map]], :cost/ticks 25000000, :seon.flow/edge true}
  ([q] (similar q 10 {}))
  ([q k-or-opts]
   (if (map? k-or-opts)
     (similar q (or (:k k-or-opts) 10) (dissoc k-or-opts :k))
     (similar q k-or-opts {})))
  ([q k opts]
   (let [kk (max 1 (long (or k 10)))
         o (or opts {})
         {:keys [conn branch]} *agent-ctx*
         bdb (when conn @conn)
         mdb (when conn (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb)))
         db (if (and bdb (pos? (vflow/indexed-count bdb))) bdb mdb)
         target (cond
                  (or (int? q) (and (vector? q) (seq q) (number? (first q)))
                      (and (vector? q) (= 2 (count q)) (keyword? (first q)))) q
                  (symbol? q) [:seon.code.var/name q]
                  (keyword? q) (cond
                                 (and db (contains? (:schema db) :seon.task/id) (first (d/datoms db :avet :seon.task/id q))) [:seon.task/id q]
                                 :else [:seon.schema/key q])
                  (and (string? q) (str/includes? q "#") db (contains? (:schema db) :seon.code.doc/id)
                       (first (d/datoms db :avet :seon.code.doc/id q))) [:seon.code.doc/id q]
                  :else nil)]
     (if (and target db)
       (let [hits (vec/near db target kk o)]
         {:ok? true :query q :target target :hits hits :results (vflow/describe-hits db hits)})
       (vflow/remote-embed-search! (str q) kk o)))))

(defn install-agent-helpers!
  {:malli/schema [:=> [:cat] :any], :seon.flow/edge true}
  []
  (let [u (create-ns 'user)]
    (binding [*ns* u]
      (refer-clojure)
      (require '[datahike.api :as d]
               '[clojure.string :as str])
      (intern u 'conn-of
              (fn [] (:conn *agent-ctx*)))
      (intern u 'conn
              (fn [] (:conn *agent-ctx*)))
      (intern u 'db
              (fn [] (when-let [c (:conn *agent-ctx*)] @c)))
      (intern u 'source (fn [& args] (apply #'source args)))
      (intern u 'similar (fn [& args] (apply #'similar args)))
      (intern u 'as-of
              (fn [tx]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (d/as-of @conn (long tx)))))
      (let [all-refs-of (fn [db s]
                      (let [var-callers (->> (d/datoms db :avet :seon.code.var/refs s)
                                             (keep #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                                             distinct sort vec)
                            schema-renders (when (contains? (:schema db) :seon.schema/form)
                                             (vec (distinct
                                                   (keep (fn [dt]
                                                           (let [f (:v dt)
                                                                 r (when (and (vector? f) (map? (second f))) (:seon.render/hiccup (second f)))]
                                                             (when (= s r)
                                                               (when-let [k (:seon.schema/key (d/pull db '[:seon.schema/key] (:e dt)))]
                                                                 [:seon.render/hiccup k]))))
                                                         (d/datoms db :aevt :seon.schema/form)))))]
                        (vec (concat var-callers schema-renders))))]
      (intern u 'callers
      (fn
        ([sym]
         (when-let [{:keys [conn]} *agent-ctx*]
           (let [db @conn]
             (if (and (coll? sym) (not (map? sym)))
               (into {} (map (fn [x] (let [s (if (string? x) (symbol x) x)] [s (all-refs-of db s)]))) sym)
               (all-refs-of db (if (string? sym) (symbol sym) sym))))))
        ([sym & more]
         (when-let [{:keys [conn]} *agent-ctx*]
           (let [db @conn]
             (into {} (map (fn [x] (let [s (if (string? x) (symbol x) x)] [s (all-refs-of db s)]))) (cons sym more))))))))
      (intern u 'callees
              (fn [sym]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [db @conn
                        s (if (string? sym) (symbol sym) sym)
                        v (d/pull db '[:seon.code.var/name :seon.code.var/refs :seon.code.var/keywords
                                       {:seon.code.var/input [:seon.schema/key]}
                                       {:seon.code.var/optional-input [:seon.schema/key]}
                                       {:seon.code.var/output [:seon.schema/key]}]
                                  [:seon.code.var/name s])]
                    (when (:seon.code.var/name v)
                      {:refs (vec (sort (:seon.code.var/refs v)))
                       :input (vec (sort (keep :seon.schema/key (:seon.code.var/input v))))
                       :optional-input (vec (sort (keep :seon.schema/key (:seon.code.var/optional-input v))))
                       :output (vec (sort (keep :seon.schema/key (:seon.code.var/output v))))
                       :keywords (vec (sort (:seon.code.var/keywords v)))})))))
      (intern u 'tests-for
              (fn [sym-or-ns]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (code/reaching-tests @conn sym-or-ns))))
      (intern u 'remove-var!
              (fn
                ([sym] ((resolve 'user/remove-var!) sym {}))
                ([sym {:keys [force?]}]
                 (when-let [{:keys [conn]} *agent-ctx*]
                   (let [db @conn
                         s (if (string? sym) (symbol sym) sym)
                         k (str s)
                         callers (->> (d/datoms db :avet :seon.code.var/refs s)
                                      (keep #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                                      (remove #(= % s))
                                      distinct sort vec)]
                     (if (and (seq callers) (not force?))
                       {:ok? false
                        :error :has-callers
                        :var s
                        :callers callers
                        :hint "Update callers first or pass {:force? true}."}
                       (let [has-form? (boolean (first (d/datoms db :avet :seon.code.form/key k)))
                             has-var?  (boolean (first (d/datoms db :avet :seon.code.var/name s)))
                             tx (cond-> []
                                  has-form? (conj [:db/retractEntity [:seon.code.form/key k]])
                                  has-var?  (conj [:db/retractEntity [:seon.code.var/name s]]))
                             r (when (seq tx) (d/transact conn {:tx-data tx}))]
                         (when (namespace s)
                           (when-let [ns-obj (find-ns (symbol (namespace s)))]
                             (ns-unmap ns-obj (symbol (name s)))))
                         (when r (ctx/refresh-active! (:db-after r) (:tx-data r)))
                         {:ok? true
                          :removed s
                          :retracted-form? has-form?
                          :retracted-var? has-var?
                          :tx (when r (:max-tx (:db-after r)))})))))))
      (intern u 'doc!
      (fn
        ([doc-id body] ((resolve 'user/doc!) doc-id nil body))
        ([doc-id heading body]
         (if-not (string? body)
           {:ok? false :error :doc-body-must-be-string
            :message (str "user/doc! expects a String body, got " (type body))}
           (when-let [{:keys [conn]} *agent-ctx*]
             (let [db @conn
                   did (str doc-id)
                   existing (d/pull db '[:seon.code.doc/id :seon.code.doc/path
                                         :seon.code.doc/order :seon.code.doc/heading]
                                    [:seon.code.doc/id did])
                   [path slug] (str/split did #"#" 2)
                   eff-path (or (:seon.code.doc/path existing) path)
                   eff-head (cond
                              (some? heading) (str heading)
                              (:seon.code.doc/id existing) (:seon.code.doc/heading existing "")
                              :else (str "## " (or slug "Section")))
                   raw-body body
                   eff-body (cond-> raw-body
                              (and (not (str/blank? eff-head)) (not (str/starts-with? raw-body "\n")))
                              (#(str "\n" %))
                              (not (str/ends-with? raw-body "\n"))
                              (str "\n"))
                   eff-order (if (:seon.code.doc/id existing)
                               (long (:seon.code.doc/order existing 0))
                               (inc (reduce max -1 (map :seon.code.doc/order (code/doc-sections db eff-path)))))
                   ent (first (cite/enrich-doc-sections db
                          [{:seon.code.doc/id did
                            :seon.code.doc/path eff-path
                            :seon.code.doc/order eff-order
                            :seon.code.doc/heading eff-head
                            :seon.code.doc/body eff-body}]))
                   stale-refs (when (contains? (:schema db) :seon.code.doc/references)
                                (cio/retract-stale-many-ops db :seon.code.doc/id [:seon.code.doc/references] [ent]))
                   r (d/transact conn {:tx-data (vec (concat stale-refs [ent]))})]
               (ctx/refresh-active! (:db-after r) (:tx-data r))
               {:ok? true :doc-id did :path eff-path :tx (:max-tx (:db-after r))}))))))
      (intern u 'history
              (fn [target]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [db @conn
                        hdb (d/history db)
                        [id-attr id-val val-attr]
                        (cond
                          (keyword? target) [:seon.schema/key target :seon.schema/form]
                          (and (string? target) (str/includes? target "#")) [:seon.code.doc/id target :seon.code.doc/body]
                          :else [:seon.code.form/key (str target) :seon.code.form/src])
                        eids (into #{} (comp (filter :added) (map :e))
                                   (d/datoms hdb :avet id-attr id-val))]
                    (->> eids
                         (mapcat #(d/datoms hdb :eavt % val-attr))
                         (filter :added)
                         (sort-by :tx)
                         (mapv (fn [dt]
                                 {:tx (:tx dt)
                                  :value (:v dt)})))))))
      (intern u 'past-evals
              (fn
                ([] ((resolve 'user/past-evals) {}))
                ([opts]
                 (when-let [{:keys [conn agent-id]} *agent-ctx*]
                   (let [m (cond
                             (integer? opts) {:limit opts}
                             (string? opts)  {:grep opts}
                             (map? opts)     opts
                             :else           {})
                         aid (or (:agent-id m) agent-id)
                         lim (long (or (:limit m) 15))
                         retracted-only? (boolean (:retracted-only? m))
                         q (when-not (str/blank? (str (:grep m)))
                             (str/lower-case (str (:grep m))))
                         rows (store/agent-historical-evals @conn aid)
                         filtered (cond->> rows
                                    retracted-only? (filter :seon.agent.eval/retracted-tx)
                                    q (filter (fn [e]
                                                (let [s (str/lower-case
                                                         (str (:seon.agent.eval/code e) "\n"
                                                              (:seon.agent.eval/thought e) "\n"
                                                              (:seon.agent.eval/value e) "\n"
                                                              (:seon.agent.eval/error e)))]
                                                  (str/includes? s q)))))]
                     (->> filtered
                          (take-last lim)
                          (mapv (fn [e]
                                  (cond-> {:tx (:seon.agent.eval/tx e)
                                           :turn (:seon.agent.eval/turn e)
                                           :handle (:seon.agent.eval/handle e)}
                                    (:seon.agent.eval/retracted-tx e)
                                    (assoc :retracted-tx (:seon.agent.eval/retracted-tx e))
                                    (not (str/blank? (str (:seon.agent.eval/code e))))
                                    (assoc :code (let [c (str (:seon.agent.eval/code e))]
                                                   (if (> (count c) 140) (str (subs c 0 140) "…") c)))
                                    (not (str/blank? (str (:seon.agent.eval/thought e))))
                                    (assoc :thought (let [t (str (:seon.agent.eval/thought e))]
                                                      (if (> (count t) 140) (str (subs t 0 140) "…") t)))
                                    (:seon.agent.eval/error e)
                                    (assoc :error (:seon.agent.eval/error e)))))))))))
      (intern u 'transact!
              (fn [tx-data]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [r (d/transact conn {:tx-data tx-data})]
                    (ctx/refresh-active! (:db-after r) (:tx-data r))
                    {:tx (:max-tx (:db-after r))}))))
      (intern u 'task
              (fn []
                (when-let [{:keys [conn agent-id]} *agent-ctx*]
                  (let [ag (d/pull @conn '[:seon.agent/task :seon.agent/goal :seon.agent/scope]
                                   [:seon.agent/id agent-id])]
                    (if-let [tid (:seon.agent/task ag)]
                      (d/pull @conn '[*] [:seon.task/id tid])
                      ag)))))
      (intern u 'main-db
              (fn [] (when-let [{:keys [conn]} *agent-ctx*]
                       (store/head conn :main))))
      (intern u 'health
              (fn
                ([]
                 (when-let [{:keys [conn agent-id branch]} *agent-ctx*]
                   (let [role (:seon.agent/role (d/pull @conn '[:seon.agent/role] [:seon.agent/id agent-id]))
                         target-db (if (or (= branch :main) (= role :coordinator))
                                     (store/head conn :main)
                                     @conn)]
                     (health/report target-db))))
                ([which]
                 (when-let [{:keys [conn]} *agent-ctx*]
                   (health/report (if (= which :main) (store/head conn :main) @conn))))))
      (intern u 'config
              (fn
                ([] (when-let [{:keys [conn agent-id]} *agent-ctx*]
                      (:effective (store/config-map (store/head conn :main) agent-id))))
                ([k] (when-let [{:keys [conn agent-id]} *agent-ctx*]
                       (store/config-val (store/head conn :main) agent-id k)))))
      (intern u 'register-schema!
              (fn [key form & [{:keys [doc examples]}]]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [{:keys [sample entity dh-decls retract-ops]}
                      (schema/validate-schema-registration!
                       @conn {:key key :form form :doc doc :examples examples})]
                  (when (seq dh-decls)
                    (d/transact conn {:tx-data dh-decls}))
                  (let [r (d/transact conn {:tx-data (vec (concat retract-ops [entity]))})]
                    (ctx/refresh-active! (:db-after r) (:tx-data r)))
                  {:registered key :sample sample :derived-attributes (mapv :db/ident dh-decls)}))))
      (intern u 'search
              (fn [substr]
                (when-let [{:keys [conn branch]} *agent-ctx*]
                  (let [bdb @conn
                        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
                        q (str/lower-case (str substr))
                        find-vals (fn [attr lim]
                                    (->> (concat (d/datoms mdb :aevt attr)
                                                 (when (not= bdb mdb) (d/datoms bdb :aevt attr)))
                                         (keep #(when (str/includes? (str/lower-case (str (:v %))) q) (:v %)))
                                         distinct sort (take lim) vec))]
                    {:vars (find-vals :seon.code.var/name 25)
                     :schemas (find-vals :seon.schema/key 20)
                     :docs (find-vals :seon.code.doc/id 20)}))))
      (intern u 'context
              (fn
                ([] ((resolve 'user/context) {}))
                ([target]
                 (let [opts (cond
                              (map? target) target
                              (symbol? target) {:scope target}
                              (qualified-keyword? target) {:task target}
                              :else {})]
                   ((resolve 'user/context) opts {})))
                ([target extra]
                 (when-let [{:keys [conn branch agent-id]} *agent-ctx*]
                   (let [base-opts (cond
                                     (map? target) target
                                     (symbol? target) {:scope target}
                                     (qualified-keyword? target) {:task target}
                                     :else {})
                         opts (merge base-opts (when (map? extra) extra))
                         bdb @conn
                         mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
                         render-fn (requiring-resolve 'seon.render.wire/render-agent-context)]
                     (render-fn mdb bdb (or agent-id :agent/repl) opts))))))
      (fcalls/install-call-and-ui-helpers! u)
      (do (repl/install-repl-helpers! u)
      (fcontainer/install-container-helpers! u)
      (do
      (when-let [irh (try
                       (requiring-resolve
                         'seon.rule.context/install-rule-helpers!)
                       (catch Throwable _ nil))]
        (irh u (fn [] *agent-ctx*)))
      (ctx/install-ctx-helpers! u (fn [] *agent-ctx*)))
      (when-let [imh (try (requiring-resolve 'seon.code.migrate/install-user-helpers!) (catch Throwable _ nil))]
        (imh u (fn [] *agent-ctx*) ctx/refresh-active!))
      (when-let [itg (try (requiring-resolve 'seon.task.gate/install-user-helpers!) (catch Throwable _ nil))]
        (itg u (fn [] *agent-ctx*)))
      (when-let [ipr (try (requiring-resolve 'seon.render.print/install-user-helpers!) (catch Throwable _ nil))]
        (ipr u (fn [] *agent-ctx*)))
      (when-let [isb (try (requiring-resolve 'seon.signals.scoreboard/install-user-helpers!) (catch Throwable _ nil))]
        (isb u (fn [] *agent-ctx*)))))))