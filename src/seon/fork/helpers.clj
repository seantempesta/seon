(ns seon.fork.helpers
  "Agent REPL `user/*` inspection, Datahike time-travel, code-editing, schema, and health helpers."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.repl :as repl]
            [seon.fork.container :as fcontainer]
            [seon.cite :as cite]
            [seon.code.io :as cio]
            [seon.vector :as vec]
            [seon.vector.flow :as vflow]))

(defn ^:seon.flow/edge render-preview!
  "Renders Hiccup or a `:seon.ui/hiccup` renderer (auto-deriving input for a var symbol) across
   display widths (default `[360 640 1100]`, `:dark`). Defaults to `:persist? false` (0 LMDB writes);
   pass `:persist? true` to persist `:seon.media/image` entities."
  {:seon.repl/export true
   :malli/schema [:function [:=> [:cat :any] :seon.media/frames] [:=> [:cat :any :map] :seon.media/frames]]
   :cost/ticks 45000000}
  ([hiccup-or-renderer] (render-preview! hiccup-or-renderer nil))
  ([hiccup-or-renderer opts]
   (let [o (if (map? opts) opts {})
         eff-conn (or (:conn o) (:conn *agent-ctx*))
         has-in? (or (contains? o :input) (contains? o :data) (contains? o :args))
         vsym (cond (qualified-symbol? hiccup-or-renderer) hiccup-or-renderer (var? hiccup-or-renderer) (symbol hiccup-or-renderer) :else nil)
         cap-opts (merge {:widths [360 640 1100] :theme :dark}
                         (select-keys o [:widths :theme :height :quality :max-width :label-prefix :timeout-ms :settle-ms :binary-path]))
         target (or (:render-fn o) (select-keys o [:port :url :direct? :browser-ch]))
         derived (when (and vsym (not has-in?))
                   ((requiring-resolve (quote seon.browser.preview/render-var-preview-frames!))
                    target (when eff-conn @eff-conn) vsym (merge cap-opts (select-keys o [:no-cache? :direct?]))))
         cap (or derived
                 (let [raw (if (or (fn? hiccup-or-renderer) vsym)
                             (let [f (cond (fn? hiccup-or-renderer) hiccup-or-renderer (var? hiccup-or-renderer) @hiccup-or-renderer :else @(requiring-resolve hiccup-or-renderer))]
                               (cond (contains? o :args) (apply f (:args o)) (contains? o :input) (f (:input o)) (contains? o :data) (f (:data o)) :else (f)))
                             hiccup-or-renderer)
                       node (if (and (map? raw) (contains? raw :seon.ui/hiccup)) (:seon.ui/hiccup raw) raw)
                       html (if (string? node) node (str ((requiring-resolve (quote seon.render/hiccup->html)) node)))]
                   ((requiring-resolve (quote seon.browser.preview/capture-html-frames!)) target html cap-opts o)))]
     (if-not (and (:ok? cap) (seq (:frames cap)))
       (with-meta {:ok? false :error (or (:error cap) :render-preview-failed) :persisted? false :seon.media/frames []
                   :summary (str "Preview failed: " (or (:message cap) (:error cap) :render-preview-failed))}
                  {:seon/schema :seon.media/frames})
       (let [img-ents (mapv (requiring-resolve (quote seon.render.media/normalize-preview-frame)) (:frames cap))
             comp-fn (requiring-resolve (quote seon.render.media/compact-preview-result))]
         (if (and (true? (:persist? o)) eff-conn (contains? (:schema @eff-conn) :seon.media/id))
           (let [r (d/transact eff-conn {:tx-data img-ents})]
             (when (:conn *agent-ctx*) (ctx/refresh-active! (:db-after r) (:tx-data r)))
             (cond-> (assoc (comp-fn img-ents) :ok? true :persisted? true) (:source derived) (assoc :source (:source derived) :var vsym)))
           (cond-> (assoc (comp-fn (mapv #(dissoc % :seon.media/id) img-ents)) :ok? true :persisted? false)
             (:source derived) (assoc :source (:source derived) :var vsym)
             (some? (:cached? derived)) (assoc :cached? (:cached? derived)))))))))

(defn ^:seon.flow/edge install-agent-helpers!
  "Installs all `user/*` REPL helpers and `d`/`str`/`fs`/`proc` aliases into `user` and `repl`."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [u (create-ns 'user)
        ctx-fn (fn [] *agent-ctx*)
        opt (fn [s] (try (requiring-resolve s) (catch Throwable _ nil)))]
    (binding [*ns* u]
      (refer-clojure)
      (require '[datahike.api :as d] '[clojure.string :as str] '[jolt.fs :as fs] '[jolt.process :as proc])
      (fcalls/export-ns-helpers! u 'seon.fork.helpers)
      (fcalls/install-call-and-ui-helpers! u)
      (repl/install-repl-helpers! u)
      (fcontainer/install-container-helpers! u)
      (ctx/install-ctx-helpers! u ctx-fn)
      (when-let [f (opt 'seon.code.migrate/install-user-helpers!)] (f u ctx-fn ctx/refresh-active!))
      (doseq [s ['seon.task.gate/install-user-helpers! 'seon.render.print/install-user-helpers!
                 'seon.signals.scoreboard/install-user-helpers!]]
        (when-let [f (opt s)] (f u ctx-fn)))
      (when-let [f (opt 'seon.render.status/install-user-helpers!)] (f u)))
    (binding [*ns* (create-ns 'repl)]
      (require '[jolt.fs :as fs] '[jolt.process :as proc]))))

(defn ^:seon.flow/edge conn
  "Returns the current agent branch Datahike connection."
  {:seon.repl/export [(quote conn) (quote conn-of)], :malli/schema [:=> [:cat] [:maybe :seon.host/conn]]}
  []
  (:conn *agent-ctx*))

(defn ^:seon.flow/edge db
  "Returns the current branch Datahike DB value (`@conn`)."
  {:seon.repl/export true, :malli/schema [:=> [:cat] [:maybe :seon.host/db]]}
  []
  (when-let [c (:conn *agent-ctx*)] @c))

(defn ^:seon.flow/edge source
  "Reads source of `'ns/fn`, `'ns`, `'ns#ns`, `:seon.schema/key`, `\"docs/f.md#sec\"`,
   `'user/<helper>`, a vector of targets, or variadic multiple targets `(source 'a/f 'b/g)`.
   When the 2nd arg is `{:lines [start end]}` (with no further args), slices a String result."
  {:seon.repl/export true
   :malli/schema [:=> [:cat :any [:* :any]] :any]}
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
               (cio/merged-ns-src conn branch s))))))))
  ([target second-arg & more]
   (let [lines (cond
                 (and (empty? more) (map? second-arg)) (:lines second-arg)
                 (and (empty? more) (vector? second-arg) (= 2 (count second-arg)) (every? integer? second-arg)) second-arg
                 (and (= 1 (count more)) (integer? second-arg) (integer? (first more))) [second-arg (first more)]
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
       (if (and (empty? more) (map? second-arg))
         (let [res (source target)
               off (:offset second-arg)
               lim (:limit second-arg)]
           (if (and (string? res) (or (integer? off) (integer? lim)))
             (let [ls (vec (str/split-lines res))
                   n (count ls)
                   s (max 0 (long (or off 0)))
                   e (if (integer? lim) (min n (+ s (max 0 (long lim)))) n)]
               (if (>= s e) "" (str/join "\n" (subvec ls s e))))
             res))
         (source (into [target second-arg] more)))))))

(defn similar
  "Semantic vector search for agents: `(user/similar q)`, `(user/similar q k)`, or `(user/similar q k opts)`.
   Accepts a query string (routed via daemon `:vectors`), float vector, entity ID, lookup ref, var symbol, schema/task keyword, or doc section ID."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat :any] :map] [:=> [:cat :any :any] :map] [:=> [:cat :any :any :map] :map]], :cost/ticks 25000000, :seon.flow/edge true}
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

(defn- resolve-explore-ref [db target]
  (let [dh (if db (or (:schema db) (d/schema db) {}) {})
        exists? (fn [[a v]]
                  (or (= a :seon.system/id) (= a :seon.signal/kind) (= a :seon.branch/name)
                      (and (= a :db/id) (integer? v) db (boolean (seq (d/datoms db :eavt v))))
                      (and db (contains? dh a) (boolean (first (d/datoms db :avet a v))))))
        cand (cond
               (or (= target :system) (= target :root)) [:seon.system/id :root]
               (integer? target) [:db/id target]
               (and (vector? target) (= 2 (count target)) (qualified-keyword? (first target))) target
               (and (symbol? target) (namespace target)) [:seon.code.var/name target]
               (symbol? target) [:seon.code.ns/name target]
               (and (string? target) (str/includes? target "#")) [:seon.code.doc/id target]
               (qualified-keyword? target)
               (some (fn [a] (when (and db (contains? dh a) (first (d/datoms db :avet a target))) [a target]))
                     [:seon.agent/id :seon.task/id :seon.rule/id :seon.schema/key :seon.system/id])
               :else nil)]
    (if (and cand (exists? cand)) cand {:error :entity-not-found :ref target})))

(defn ^:seon.flow/edge explore!
  "Explores ANY entity through `seon.render.surface/project-entity-view` (`:target :wire`),
   returning crisp Upgrade-Ladder structured text under `:token-budget` (default 2400).
   Accepts lookup refs, qualified symbols, ns symbols, keywords, doc IDs, or `:system`/`:root`."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat :any] :any] [:=> [:cat :any [:maybe :map]] :any]]
  :cost/ticks 45000000}
  ([ref] (explore! ref {}))
  ([ref opts]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [bdb @conn
           mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
           o (if (map? opts) opts {})
           r0 (resolve-explore-ref bdb ref)
           fref (if (and (map? r0) (:error r0) (not= (d/commit-id bdb) (d/commit-id mdb)))
                  (resolve-explore-ref mdb ref)
                  r0)]
       (if (and (map? fref) (:error fref))
         fref
         (let [proj-fn (requiring-resolve 'seon.render.surface/project-entity-view)
               res (proj-fn mdb bdb fref (merge {:target :wire :token-budget 2400} o))]
           (if (:raw? o) res (or (:text res) {:error :entity-not-found :ref ref}))))))))

(defn ^:seon.flow/edge main-db
  "Returns the current `:main` branch Datahike DB value."
  {:seon.repl/export true, :malli/schema [:=> [:cat] [:maybe :seon.host/db]]}
  []
  (when-let [{:keys [conn]} *agent-ctx*] (store/head conn :main)))

(defn ^:seon.flow/edge as-of
  "Returns a historical Datahike DB snapshot at transaction id `tx`."
  {:seon.repl/export true, :malli/schema [:=> [:cat :int] [:maybe :seon.host/db]]}
  [tx]
  (when-let [{:keys [conn]} *agent-ctx*] (d/as-of @conn (long tx))))

(defn ^:seon.flow/edge callees
  "Returns a schema-typed `:seon.code.var/callees-result` map `{:seon.code.var/name :seon.code.var/refs :seon.code.var/input :seon.code.var/optional-input :seon.code.var/output :seon.code.var/keywords}` for `sym`."
  {:seon.repl/export true
   :malli/schema [:=> [:cat [:or :seon.code.var/name :string]] [:maybe :seon.code.var/callees-result]]}
  [sym]
  (when-let [{:keys [conn]} *agent-ctx*]
    (let [db @conn
          s (if (string? sym) (symbol sym) sym)
          v (d/pull db (quote [:seon.code.var/name :seon.code.var/refs :seon.code.var/keywords
                               {:seon.code.var/input [:seon.schema/key]}
                               {:seon.code.var/optional-input [:seon.schema/key]}
                               {:seon.code.var/output [:seon.schema/key]}])
                    [:seon.code.var/name s])]
      (when (:seon.code.var/name v)
        (with-meta
          {:seon.code.var/name s
           :seon.code.var/refs (vec (sort (:seon.code.var/refs v)))
           :seon.code.var/input (vec (sort (keep :seon.schema/key (:seon.code.var/input v))))
           :seon.code.var/optional-input (vec (sort (keep :seon.schema/key (:seon.code.var/optional-input v))))
           :seon.code.var/output (vec (sort (keep :seon.schema/key (:seon.code.var/output v))))
           :seon.code.var/keywords (vec (sort (:seon.code.var/keywords v)))}
          {:seon/schema :seon.code.var/callees-result})))))

(defn ^:seon.flow/edge tests-for
  "Returns the reach-selected tests that cover `sym-or-ns`."
  {:seon.repl/export true, :malli/schema [:=> [:cat [:or :seon.code.var/name :seon.code.ns/name :string]] [:maybe [:vector :seon.code.var/name]]]}
  [sym-or-ns]
  (when-let [{:keys [conn]} *agent-ctx*] (code/reaching-tests @conn sym-or-ns)))

(defn ^:seon.flow/edge doc!
  "Creates or updates a documentation section entity in the branch DB and refreshes context."
  {:seon.repl/export true, :malli/schema [:function [:=> [:cat :seon.code.doc/id :seon.code.doc/body] [:maybe :map]] [:=> [:cat :seon.code.doc/id [:maybe :string] :seon.code.doc/body] [:maybe :map]]]}
  ([doc-id body] (doc! doc-id nil body))
  ([doc-id heading body]
   (if-not (string? body)
     {:ok? false :error :doc-body-must-be-string :message (str "user/doc! expects a String body, got " (type body))}
     (when-let [{:keys [conn]} *agent-ctx*]
       (let [db @conn
             did (str doc-id)
             existing (d/pull db '[:seon.code.doc/id :seon.code.doc/path :seon.code.doc/order :seon.code.doc/heading]
                              [:seon.code.doc/id did])
             [path slug] (str/split did #"#" 2)
             eff-path (or (:seon.code.doc/path existing) path)
             eff-head (cond
                        (some? heading) (str heading)
                        (:seon.code.doc/id existing) (:seon.code.doc/heading existing "")
                        :else (str "## " (or slug "Section")))
             eff-body (cond-> body
                        (and (not (str/blank? eff-head)) (not (str/starts-with? body "\n"))) (#(str "\n" %))
                        (not (str/ends-with? body "\n")) (str "\n"))
             eff-order (if (:seon.code.doc/id existing)
                         (long (:seon.code.doc/order existing 0))
                         (inc (reduce max -1 (map :seon.code.doc/order (code/doc-sections db eff-path)))))
             ent (first (cite/enrich-doc-sections db
                          [{:seon.code.doc/id did :seon.code.doc/path eff-path
                            :seon.code.doc/order eff-order :seon.code.doc/heading eff-head
                            :seon.code.doc/body eff-body}]))
             stale-refs (when (contains? (:schema db) :seon.code.doc/references)
                          (cio/retract-stale-many-ops db :seon.code.doc/id [:seon.code.doc/references] [ent]))
             r (d/transact conn {:tx-data (vec (concat stale-refs [ent]))})]
         (ctx/refresh-active! (:db-after r) (:tx-data r))
         {:ok? true :doc-id did :path eff-path :tx (:max-tx (:db-after r))})))))

(defn ^:seon.flow/edge history
  "Returns transaction history `[{:tx :value} ...]` for a var, schema keyword, or doc section ID."
  {:seon.repl/export true :malli/schema [:=> [:cat :any] [:maybe [:vector :map]]]}
  [target]
  (when-let [{:keys [conn]} *agent-ctx*]
    (let [db @conn
          hdb (d/history db)
          [id-attr id-val val-attr]
          (cond
            (keyword? target) [:seon.schema/key target :seon.schema/form]
            (and (string? target) (str/includes? target "#")) [:seon.code.doc/id target :seon.code.doc/body]
            :else [:seon.code.form/key (str target) :seon.code.form/src])
          eids (into #{} (comp (filter :added) (map :e)) (d/datoms hdb :avet id-attr id-val))]
      (->> eids
           (mapcat #(d/datoms hdb :eavt % val-attr))
           (filter :added)
           (sort-by :tx)
           (mapv (fn [dt] {:tx (:tx dt) :value (:v dt)}))))))

(defn ^:seon.flow/edge past-evals
  "Queries historical eval records for the current (or `:agent-id`) agent, with optional `:limit`, `:grep`, `:retracted-only?`."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat] [:maybe [:vector :map]]] [:=> [:cat :any] [:maybe [:vector :map]]]]}
  ([] (past-evals {}))
  ([opts]
   (when-let [{:keys [conn agent-id]} *agent-ctx*]
     (let [m (cond (integer? opts) {:limit opts} (string? opts) {:grep opts} (map? opts) opts :else {})
           aid (or (:agent-id m) agent-id)
           lim (long (or (:limit m) 15))
           retracted-only? (boolean (:retracted-only? m))
           q (when-not (str/blank? (str (:grep m))) (str/lower-case (str (:grep m))))
           rows (store/agent-historical-evals @conn aid)
           filtered (cond->> rows
                      retracted-only? (filter :seon.agent.eval/retracted-tx)
                      q (filter (fn [e]
                                  (str/includes? (str/lower-case
                                                   (str (:seon.agent.eval/code e) "\n" (:seon.agent.eval/thought e) "\n"
                                                        (:seon.agent.eval/value e) "\n" (:seon.agent.eval/error e))) q))))]
       (->> filtered
            (take-last lim)
            (mapv (fn [e]
                    (cond-> {:tx (:seon.agent.eval/tx e) :turn (:seon.agent.eval/turn e) :handle (:seon.agent.eval/handle e)}
                      (:seon.agent.eval/retracted-tx e) (assoc :retracted-tx (:seon.agent.eval/retracted-tx e))
                      (not (str/blank? (str (:seon.agent.eval/code e))))
                      (assoc :code (let [c (str (:seon.agent.eval/code e))] (if (> (count c) 140) (str (subs c 0 140) "…") c)))
                      (not (str/blank? (str (:seon.agent.eval/thought e))))
                      (assoc :thought (let [t (str (:seon.agent.eval/thought e))] (if (> (count t) 140) (str (subs t 0 140) "…") t)))
                      (:seon.agent.eval/error e) (assoc :error (:seon.agent.eval/error e))))))))))

(defn ^:seon.flow/edge transact!
  "Transacts `tx-data` into the agent branch DB and refreshes active context."
  {:seon.repl/export true :malli/schema [:=> [:cat :any] [:maybe :map]]}
  [tx-data]
  (when-let [{:keys [conn]} *agent-ctx*]
    (let [r (d/transact conn {:tx-data tx-data})]
      (ctx/refresh-active! (:db-after r) (:tx-data r))
      {:tx (:max-tx (:db-after r))})))

(defn ^:seon.flow/edge task
  "Returns the current agent's assigned `:seon.task/*` entity (or agent scope/goal map)."
  {:seon.repl/export true :malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (when-let [{:keys [conn agent-id]} *agent-ctx*]
    (let [ag (d/pull @conn '[{:seon.agent/tasks [*]} :seon.agent/goal :seon.agent/scope] [:seon.agent/id agent-id]) ts (:seon.agent/tasks ag)] (if (seq ts) (if (= 1 (count ts)) (first ts) (vec ts)) ag))))

(defn ^:seon.flow/edge config
  "Returns effective `:seon.config/*` map or single config key `k` for the current agent."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :keyword] :any]]}
  ([] (when-let [{:keys [conn agent-id]} *agent-ctx*]
        (:effective (store/config-map (store/head conn :main) agent-id))))
  ([k] (when-let [{:keys [conn agent-id]} *agent-ctx*]
         (store/config-val (store/head conn :main) agent-id k))))

(defn ^:seon.flow/edge register-schema!
  "Validates and registers a Malli schema `:seon.schema/*` entity and its derived Datahike attributes."
  {:seon.repl/export true, :malli/schema [:function [:=> [:cat :seon.schema/key :seon.schema/form] [:maybe :map]] [:=> [:cat :seon.schema/key :seon.schema/form [:maybe :map]] [:maybe :map]]]}
  ([key form] (register-schema! key form nil))
  ([key form {:keys [doc examples]}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [{:keys [sample entity dh-decls retract-ops]}
           (schema/validate-schema-registration! @conn {:key key :form form :doc doc :examples examples})]
       (when (seq dh-decls) (d/transact conn {:tx-data dh-decls}))
       (let [r (d/transact conn {:tx-data (vec (concat retract-ops [entity]))})]
         (ctx/refresh-active! (:db-after r) (:tx-data r)))
       {:registered key :sample sample :derived-attributes (mapv :db/ident dh-decls)}))))

(defn ^:seon.flow/edge context
  "Renders the current agent's `<walk-context>` + `<ephemeral-tail>`, optionally overriding `:scope`, `:task`, or render opts."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat] :any] [:=> [:cat :any] :any] [:=> [:cat :any [:maybe :map]] :any]]}
  ([] (context {}))
  ([target]
   (let [opts (cond (map? target) target (symbol? target) {:scope target} (qualified-keyword? target) {:task target} :else {})]
     (context opts {})))
  ([target extra]
   (when-let [{:keys [conn branch agent-id]} *agent-ctx*]
     (let [base-opts (cond (map? target) target (symbol? target) {:scope target} (qualified-keyword? target) {:task target} :else {})
           opts (merge base-opts (when (map? extra) extra))
           bdb @conn
           mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
           render-fn (requiring-resolve 'seon.render.wire/render-agent-context)]
       (render-fn mdb bdb (or agent-id :agent/repl) opts)))))

(defn- callers-entry [db s]
  (let [var-callers (->> (d/datoms db :avet :seon.code.var/refs s)
                         (keep #(:seon.code.var/name (d/pull db (quote [:seon.code.var/name]) (:e %))))
                         distinct sort vec)
        schema-renders (when (contains? (:schema db) :seon.schema/form)
                         (vec (distinct
                               (keep (fn [dt]
                                       (let [f (:v dt)
                                             r (when (and (vector? f) (map? (second f))) (:seon.render/hiccup (second f)))]
                                         (when (= s r)
                                           (:seon.schema/key (d/pull db (quote [:seon.schema/key]) (:e dt))))))
                                     (d/datoms db :aevt :seon.schema/form)))))]
    (with-meta
      (cond-> {:seon.code.var/name s :seon.code.var/callers var-callers}
        (seq schema-renders) (assoc :seon.code.var/render-schemas schema-renders))
      {:seon/schema :seon.code.var/callers-result})))

(defn ^:seon.flow/edge callers
  "Returns a schema-typed `:seon.code.var/callers-result` map `{:seon.code.var/name :seon.code.var/callers :seon.code.var/render-schemas}` for `sym` (or `{sym result ...}` for multiple symbols)."
  {:seon.repl/export true
   :malli/schema [:function
                  [:=> [:cat [:or :seon.code.var/name :string [:sequential [:or :seon.code.var/name :string]]]]
                       [:maybe [:or :seon.code.var/callers-result [:map-of :seon.code.var/name :seon.code.var/callers-result]]]]
                  [:=> [:cat [:or :seon.code.var/name :string] [:* [:or :seon.code.var/name :string]]]
                       [:maybe [:map-of :seon.code.var/name :seon.code.var/callers-result]]]]}
  ([sym]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [db @conn]
       (if (and (coll? sym) (not (map? sym)))
         (into {} (map (fn [x] (let [s (if (string? x) (symbol x) x)] [s (callers-entry db s)]))) sym)
         (callers-entry db (if (string? sym) (symbol sym) sym))))))
  ([sym & more]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [db @conn]
       (into {} (map (fn [x] (let [s (if (string? x) (symbol x) x)] [s (callers-entry db s)]))) (cons sym more))))))

(defn ^:seon.flow/edge remove-var!
  "Retracts a var and its form from the branch DB (refuses if callers exist unless `{:force? true}`)."
  {:seon.repl/export true, :malli/schema [:function [:=> [:cat [:or :seon.code.var/name :string]] [:maybe :map]] [:=> [:cat [:or :seon.code.var/name :string] [:maybe :map]] [:maybe :map]]]}
  ([sym] (remove-var! sym {}))
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
         {:ok? false :error :has-callers :var s :callers callers
          :hint "Update callers first or pass {:force? true}."}
         (let [has-form? (boolean (first (d/datoms db :avet :seon.code.form/key k)))
               has-var? (boolean (first (d/datoms db :avet :seon.code.var/name s)))
               tx (cond-> []
                    has-form? (conj [:db/retractEntity [:seon.code.form/key k]])
                    has-var? (conj [:db/retractEntity [:seon.code.var/name s]]))
               r (when (seq tx) (d/transact conn {:tx-data tx}))]
           (when (namespace s)
             (when-let [ns-obj (find-ns (symbol (namespace s)))]
               (ns-unmap ns-obj (symbol (name s)))))
           (when r (ctx/refresh-active! (:db-after r) (:tx-data r)))
           {:ok? true :removed s :retracted-form? has-form? :retracted-var? has-var?
            :tx (when r (:max-tx (:db-after r)))}))))))

(defn ^:seon.flow/edge ui-patch!
  "1-turn UI edit + derived preview shortcut: edits `target` via `(ui-patch! sym new-defn-str)`
   or `(ui-patch! sym old-str new-str [opts])`, evaluates its derived preview via `seon.browser.preview`
   (0 DB image writes), and returns `{:ok? :edit :preview :dom}`."
  {:seon.repl/export true
   :malli/schema [:function
                  [:=> [:cat :any :any] :map]
                  [:=> [:cat :any :any :any] :map]
                  [:=> [:cat :any :any :any :map] :map]]
   :cost/ticks 60000000}
  ([target patch-or-defn] (ui-patch! target patch-or-defn nil {}))
  ([target old-str new-str]
   (if (and (vector? old-str) (map? new-str))
     (ui-patch! target old-str nil new-str)
     (ui-patch! target old-str new-str {})))
  ([target old-or-defn new-str opts]
   (let [o (if (map? opts) opts {})
         sym (if (string? target) (symbol target) target)
         edit-res (cond
                    (some? new-str) ((requiring-resolve (quote seon.code.edit/edit-fn!)) sym old-or-defn new-str)
                    (vector? old-or-defn) ((requiring-resolve (quote seon.code.edit/edit-fn!)) sym old-or-defn)
                    :else (let [nsym (if (namespace sym) (symbol (namespace sym)) sym)]
                            ((requiring-resolve (quote seon.code.edit/add-fn!)) nsym old-or-defn)))]
     (if-not (:ok? edit-res)
       {:ok? false :edit edit-res :preview nil :dom nil}
       (let [eff-db (when-let [c (or (:conn o) (:conn *agent-ctx*))] @c)
             var-sym (or (:var edit-res) (when (qualified-symbol? sym) sym))
             prev ((requiring-resolve (quote seon.browser.preview/eval-renderer-preview))
                   eff-db var-sym (assoc o :no-cache? true))
             want-browser? (and (:ok? prev) (not= false (:browser? o))
                                (or (true? (:browser? o)) (fn? (:render-fn o))
                                    (:browser-ch o) (:port o) (:url o)))
             fr-res (when want-browser?
                      ((requiring-resolve (quote seon.browser.preview/render-var-preview-frames!))
                       (or (:render-fn o) (select-keys o [:port :url :direct? :browser-ch]))
                       eff-db var-sym (merge {:widths [360 640] :theme :dark :no-cache? true} o)))
             dom (if (and (:ok? fr-res) (seq (:frames fr-res)))
                   (mapv #(select-keys % [:label :width :height :dom]) (:frames fr-res))
                   (when (:ok? prev)
                     ((requiring-resolve (quote seon.render.fit/measure-hiccup)) (:hiccup prev))))]
         {:ok? (boolean (:ok? prev))
          :edit edit-res
          :preview (select-keys prev [:ok? :var :source :input-hash :html :hiccup :error :message])
          :dom dom})))))