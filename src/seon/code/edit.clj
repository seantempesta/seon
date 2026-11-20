(ns seon.code.edit
"Whitespace-, tab-, newline-, and comment-insensitive structural Clojure editing (`rewrite-clj` CST),
 AST-accurate unused `:require` analysis/pruning, body grep, unified diffs, and in-turn branch test running."
(:require [clojure.string :as str]
          [datahike.api :as d]
          [seon.code :as code]
          [seon.code.parse :as parse]
          [seon.ctx :as ctx]
          [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
          [seon.fork.worker-support :as wsup]
          [seon.health :as health]
          [seon.store :as store]
          [seon.code.cst :as cst]
          [hasch.core :as hasch]
          [malli.core :as m]
          [seon.schema :as schema]))

(defn- merged-ns-src [conn branch nsym]
  (let [bdb @conn
        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
        base-db (when (not= branch :main) (try (store/merge-base-db conn :main branch) (catch Throwable _ nil)))
        ns-forms-of (fn [db]
                      (->> (d/datoms db :avet :seon.code.form/ns nsym)
                           (map #(d/pull db '[:seon.code.form/key :seon.code.form/order :seon.code.form/src] (:e %)))
                           (sort-by #(or (:seon.code.form/order %) 0))))
        m-fs (ns-forms-of mdb)
        b-fs (ns-forms-of bdb)
        b-by-k (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) b-fs)
        base-by-k (if base-db (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) (ns-forms-of base-db)) {})
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
    (str/join "\n\n" (concat kept-m added-b))))

(defn- resolve-form-target [conn branch target]
  (let [s (str target)
        bdb @conn
        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
        [nsym fkey] (cond
                      (str/ends-with? s "#ns") [(symbol (subs s 0 (- (count s) 3))) s]
                      (str/includes? s "/")
                      (let [sym (symbol s)
                            fk (or (:seon.code.var/form (d/pull bdb '[:seon.code.var/form] [:seon.code.var/name sym]))
                                   (:seon.code.var/form (d/pull mdb '[:seon.code.var/form] [:seon.code.var/name sym]))
                                   s)]
                        [(symbol (namespace sym)) fk])
                      :else [(symbol s) (str s "#ns")])
        path (or (:seon.code.ns/path (d/pull bdb '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
                 (:seon.code.ns/path (d/pull mdb '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
                 (code/ns->default-path nsym))
        f-ent (let [b (d/pull bdb '[:seon.code.form/src :seon.code.form/order :seon.code.form/lead] [:seon.code.form/key fkey])]
                (if (:seon.code.form/src b) b
                    (d/pull mdb '[:seon.code.form/src :seon.code.form/order :seon.code.form/lead] [:seon.code.form/key fkey])))]
    {:ns nsym :key fkey :path path
     :src (:seon.code.form/src f-ent)
     :order (:seon.code.form/order f-ent)
     :lead (:seon.code.form/lead f-ent)}))

(defn- single-form-upsert-tx
  "O(1-form) delta persistence for non-`#ns` form edits — avoids re-parsing the entire `.clj` file."
  [db nsym fkey path order lead new-fsrc]
  (let [ns-src (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key (str nsym "#ns")]))
        local-defs (into #{} (map #(symbol (name (:seon.code.var/name %)))) (code/ns-vars db nsym))
        reparsed (parse/read-clj path (str (or ns-src (str "(ns " nsym ")")) "\n" new-fsrc) local-defs)
        kept-forms (mapv (fn [f]
                           (cond-> f
                             (= (:seon.code.form/key f) fkey)
                             (assoc :seon.code.form/order (or order 1)
                                    :seon.code.form/lead (or lead "\n\n"))))
                         (rest (:forms reparsed)))
        kept-vars (:vars reparsed)
        stale-var-ops (vec
                       (mapcat (fn [v]
                                 (when-let [eid (:e (first (d/datoms db :avet :seon.code.var/name (:seon.code.var/name v))))]
                                   (mapcat (fn [a]
                                             (let [old-s (into #{} (map :v) (d/datoms db :eavt eid a))
                                                   new-s (set (get v a #{}))]
                                               (for [gone (remove new-s old-s)]
                                                 [:db/retract eid a gone])))
                                           [:seon.code.var/refs :seon.code.var/schema-refs :seon.code.var/keywords])))
                               kept-vars))]
    (vec (concat stale-var-ops kept-forms kept-vars))))

(defn do-create-task!
  "Creates a task on `:main` via `:transactor` (automatically written back to `resources/seon/tasks.edn`).
   Supports optional `:from-template :existing/task-id` to inherit default `:scope`, `:plan`, `:tests`, and `:needs`."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [id from-template scope title plan needs tests spawn-agent? agent-id role auto-merge?] :as opts}]
  (when-let [{:keys [conn]} *agent-ctx*]
    (let [mdb (store/head conn :main)
          tpl (when (and from-template (first (d/datoms mdb :avet :seon.task/id from-template)))
                (d/pull mdb '[:seon.task/scope :seon.task/plan :seon.task/tests :seon.task/needs]
                        [:seon.task/id from-template]))
          eff-scope (or scope (:seon.task/scope tpl))
          eff-plan (if (and (:seon.task/plan tpl) (not (str/blank? (str plan))))
                     (str (:seon.task/plan tpl) "\n\n" plan)
                     (or plan (:seon.task/plan tpl) ""))
          eff-tests (into (set (:seon.task/tests tpl)) tests)
          eff-needs (into (set (:seon.task/needs tpl)) needs)
          req (cond-> {:op :create-task
                       :id id
                       :title (or title (name id))
                       :plan eff-plan}
                eff-scope (assoc :scope eff-scope)
                (seq eff-tests) (assoc :tests (vec eff-tests))
                (seq eff-needs) (assoc :needs (vec eff-needs))
                (some? spawn-agent?) (assoc :spawn-agent? (boolean spawn-agent?))
                agent-id (assoc :agent-id agent-id)
                role (assoc :role role)
                (some? auto-merge?) (assoc :auto-merge? (boolean auto-merge?)))]
      (fcalls/tx-main-sync! req))))

(defn install-edit-helpers!
  "Interns all `user/*` code-editing, inspection, and task helpers into `u` via live var delegation
   (so hot-reloads take effect immediately in warm agent REPLs and propagate `:seon.eval/budget-override`)."
  {:malli/schema [:=> [:cat :seon.host/ns] :any]}
  [u]
  (intern-doc! u 'edit-fn! #'do-edit-fn!)
  (intern-doc! u 'add-require! #'do-add-require!)
  (intern-doc! u 'set-meta! #'do-set-meta!)
  (intern-doc! u 'ns-requires #'do-ns-requires)
  (intern-doc! u 'grep-src #'do-grep-src)
  (intern-doc! u 'unused-requires #'do-unused-requires)
  (intern-doc! u 'prune-requires! #'do-prune-requires!)
  (intern-doc! u 'run-tests! #'do-run-tests!)
  (intern-doc! u 'diff #'do-diff)
  (intern-doc! u 'help #'do-help)
  (intern-doc! u 'autofix-health! #'do-autofix-health!)
  (intern-doc! u 'inspect-branch #'do-inspect-branch)
  (intern-doc! u 'save-example! #'do-save-example!)
  (intern-doc! u 'create-task! #'do-create-task!))

(defn edit-fn!
  "Structurally replaces `old-str` with `new-str` inside `'ns/fn` or `'ns` (whitespace/comment-insensitive
   via `seon.code.cst`), persists to Datahike, and live-evaluates in the target namespace."
  {:malli/schema [:function [:=> [:cat :any :any :any] [:maybe :map]] [:=> [:cat :any :any :any :map] [:maybe :map]]], :seon.eval/budget-override {:ticks 300000000, :bytes 536870912}}
  ([target old-str new-str] (edit-fn! target old-str new-str {}))
  ([target old-str new-str {:keys [occurrence dry-run? eval?] :or {eval? true}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [{:keys [ns key path src order lead]} (resolve-form-target conn branch target)]
       (if (str/blank? (str src))
         {:ok? false :error :form-not-found :target target :key key}
         (let [to-src (fn [x] (if (string? x) x (binding [*print-meta* true *print-namespace-maps* false] (pr-str (cst/strip-reader-meta x)))))
               old-s (to-src old-str)
               new-s (to-src new-str)
               res (cst/patch-form-source src old-s new-s {:occurrence occurrence})]
           (if-not (:ok? res)
             (assoc res :target target :key key)
             (let [new-fsrc (:src res)
                   udiff (cst/unified-diff src new-fsrc key)
                   is-ns? (str/ends-with? key "#ns")]
               (if dry-run?
                 {:ok? true :dry-run? true :target target :key key :line (:line res) :diff udiff}
                 (let [tx (if is-ns?
                            (let [file-src (merged-ns-src conn branch ns)
                                  idx (.indexOf ^String file-src ^String src)]
                              (when-not (neg? idx)
                                (code/file-import-tx @conn path
                                                     (str (subs file-src 0 idx) new-fsrc (subs file-src (+ idx (count src))))
                                                     false)))
                            (single-form-upsert-tx @conn ns key path order lead new-fsrc))]
                   (if-not tx
                     {:ok? false :error :source-sync-mismatch :target target :key key}
                     (let [r (d/transact conn {:tx-data tx})
                           eval-err (when eval?
                                      (try
                                        (if is-ns?
                                          (binding [*ns* *ns*]
                                            (eval (read-string new-fsrc)))
                                          (do
                                            (when-not (find-ns ns) (require ns))
                                            (binding [*ns* (the-ns ns)]
                                              (eval (read-string new-fsrc)))))
                                        nil
                                        (catch Throwable ex (or (.getMessage ex) (str ex)))))]
                       (ctx/refresh-active! (:db-after r) (:tx-data r))
                       (let [defonce? (str/starts-with? (str/triml new-fsrc) "(defonce ")]
                         (cond-> {:ok? (nil? eval-err)
                                  :target target :key key :path path :line (:line res)
                                  :tx (:max-tx (:db-after r)) :diff udiff}
                           eval-err (assoc :eval-error eval-err)
                           defonce? (assoc :note "defonce keeps its existing runtime binding; use (reset! ...) or (alter-var-root ...) if testing a new initial value live.")))))))))))))))

(defn unused-requires
  "AST-accurate analysis of used vs unused `:require` specs in namespace `nsym`."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [nsym]
  (when-let [{:keys [conn branch]} *agent-ctx*]
    (let [s (if (string? nsym) (symbol nsym) nsym)
          fsrc (merged-ns-src conn branch s)]
      (if (str/blank? fsrc)
        {:ok? false :error :ns-not-found :ns s}
        (let [analysis (cst/analyze-ns-requires fsrc)]
          {:ok? true
           :ns s
           :unused (vec (map :lib (remove :keep? analysis)))
           :kept (vec (map :lib (filter :keep? analysis)))
           :detail analysis})))))

(defn prune-requires!
  "AST-accurately removes unused `:require` specs from `nsym`, persisting to Datahike and
   returning a compact diff of ONLY the `(ns ...)` form (never the entire file)."
  {:malli/schema [:function
                  [:=> [:cat :any] [:maybe :map]]
                  [:=> [:cat :any :map] [:maybe :map]]]}
  ([nsym] (prune-requires! nsym {}))
  ([nsym {:keys [dry-run?]}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [s (if (string? nsym) (symbol nsym) nsym)
           {:keys [path key src]} (resolve-form-target conn branch s)
           fsrc (merged-ns-src conn branch s)]
       (if (str/blank? fsrc)
         {:ok? false :error :ns-not-found :ns s}
         (let [res (cst/prune-unused-requires-src fsrc)
               new-ns-src (when (:changed? res)
                            (some-> (parse/read-clj (or path (str s)) (:src res))
                                    :forms first :seon.code.form/src))
               udiff (if (:changed? res)
                       (cst/unified-diff (or src "") (or new-ns-src "") (or key (str s "#ns")))
                       "")]
           (cond
             (not (:changed? res))
             {:ok? true :changed? false :ns s :path path :removed [] :kept (:kept res) :diff ""}

             dry-run?
             {:ok? true :changed? true :dry-run? true :ns s :path path
              :removed (:removed res) :kept (:kept res) :diff udiff}

             :else
             (let [tx (code/file-import-tx @conn path (:src res) false)
                   r (d/transact conn {:tx-data tx})]
               (ctx/refresh-active! (:db-after r) (:tx-data r))
               {:ok? true :ns s :path path :changed? true
                :removed (:removed res) :kept (:kept res)
                :tx (:max-tx (:db-after r)) :diff udiff}))))))))

(defn diff
  "Returns branch upserts, retractions, schemas, health errors, and compact unified diffs vs merge-base."
  {:malli/schema [:function
                  [:=> [:cat] [:maybe :map]]
                  [:=> [:cat :any] [:maybe :map]]]}
  ([] (diff nil))
  ([target]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [bdb @conn
           mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
           base-cid (when (not= branch :main) (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
           base-db (when base-cid (d/commit-as-db conn base-cid))
           ops (if base-cid
                 (code/changed-form-ops conn base-cid (d/commit-id bdb))
                 {:upserts [] :retracted-keys #{} :schema-keys #{}})
           errs (if (= branch :main) [] (health/caused-by-branch mdb bdb {}))
           upserts (if target
                     (let [k (str target)]
                       (filterv #(= k (:seon.code.form/key %)) (:upserts ops)))
                     (:upserts ops))
           diffs (into {}
                       (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                               (let [old-s (when base-db
                                             (:seon.code.form/src (d/pull base-db '[:seon.code.form/src] [:seon.code.form/key key])))]
                                 [key (cst/unified-diff (or old-s "") (or src "") key)])))
                       (take 12 upserts))]
       {:branch branch
        :upserts (vec (sort (map :seon.code.form/key (:upserts ops))))
        :retracted (vec (sort (:retracted-keys ops)))
        :schemas (vec (sort (:schema-keys ops)))
        :health-errors errs
        :diffs diffs}))))

(defn set-meta!
  "Structurally inserts or merges metadata (e.g. `{:malli/schema [...] :cost/ticks 500000}`)
   into a `(defn ...)` / `(defn- ...)` attr-map, preserving formatting.
   Validates `:malli/schema` upfront against the live registry. Supports single target or batch map."
  {:malli/schema [:function
                  [:=> [:cat :any] :map]
                  [:=> [:cat :any :map] [:maybe :map]]
                  [:=> [:cat :any :map :map] [:maybe :map]]]}
  ([target-or-batch]
   (if (map? target-or-batch)
     (let [results (into {}
                         (map (fn [[t m]] [t (dissoc (set-meta! t m {}) :diff)]))
                         target-or-batch)]
       {:ok? (every? :ok? (vals results))
        :updated (count (filter :ok? (vals results)))
        :total (count results)
        :results results})
     {:ok? false :error :invalid-args :hint "Call (user/set-meta! 'ns/fn {:malli/schema ...}) or (user/set-meta! {'ns/f1 {...} ...})."}))
  ([target meta-map] (set-meta! target meta-map {}))
  ([target meta-map {:keys [dry-run? eval?] :or {eval? true}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [{:keys [ns key path src order lead]} (resolve-form-target conn branch target)]
       (if (str/blank? (str src))
         {:ok? false :error :form-not-found :target target :key key}
         (let [sch-err (when (contains? meta-map :malli/schema)
                         (let [sch (:malli/schema meta-map)]
                           (try
                             (m/schema sch {:registry (:registry (schema/registry-for-db @conn))})
                             nil
                             (catch Throwable ex
                               {:ok? false
                                :error :invalid-malli-schema
                                :target target
                                :key key
                                :schema sch
                                :message (or (.getMessage ex) (str ex))
                                :hint "Valid Malli examples: [:=> [:cat :string] :string], [:=> [:cat [:int {:min 0}]] :int], [:function [:=> [:cat :string] :string] [:=> [:cat :string :int] :string]], or :seon.host/db / :any for host values."}))))]
           (if sch-err
             sch-err
             (let [res (cst/set-defn-meta-src src meta-map)]
               (if-not (:ok? res)
                 (assoc res :target target :key key)
                 (let [new-fsrc (:src res)
                       udiff (cst/unified-diff src new-fsrc key)]
                   (if dry-run?
                     {:ok? true :dry-run? true :target target :key key :action (:action res) :diff udiff}
                     (let [tx (single-form-upsert-tx @conn ns key path order lead new-fsrc)
                           r (d/transact conn {:tx-data tx})
                           eval-err (when eval?
                                      (try
                                        (when-not (find-ns ns) (require ns))
                                        (binding [*ns* (the-ns ns)]
                                          (eval (read-string new-fsrc)))
                                        nil
                                        (catch Throwable ex (or (.getMessage ex) (str ex)))))]
                       (ctx/refresh-active! (:db-after r) (:tx-data r))
                       (cond-> {:ok? (nil? eval-err)
                                :target target :key key :action (:action res)
                                :tx (:max-tx (:db-after r)) :diff udiff}
                         eval-err (assoc :eval-error eval-err))))))))))))))

(defn add-require!
  "Idempotently adds one or more `:require` specs to `nsym`, persists `#ns`, and live-requires."
  {:malli/schema [:function
                  [:=> [:cat :any :any] [:maybe :map]]
                  [:=> [:cat :any :any :map] [:maybe :map]]]}
  ([nsym spec-or-specs] (add-require! nsym spec-or-specs {}))
  ([nsym spec-or-specs {:keys [dry-run? eval?] :or {eval? true}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [s (if (string? nsym) (symbol nsym) nsym)
           {:keys [key path src]} (resolve-form-target conn branch s)]
       (if (str/blank? (str src))
         {:ok? false :error :ns-not-found :ns s}
         (let [res (cst/add-require-src src spec-or-specs)]
           (cond
             (not (:ok? res)) (assoc res :ns s)
             (not (:changed? res)) {:ok? true :ns s :changed? false :added [] :diff ""}
             dry-run? {:ok? true :dry-run? true :ns s :added (:added res) :diff (cst/unified-diff src (:src res) key)}
             :else
             (let [new-ns-src (:src res)
                   file-src (merged-ns-src conn branch s)
                   idx (.indexOf ^String file-src ^String src)
                   tx (when-not (neg? idx)
                        (code/file-import-tx @conn path
                                             (str (subs file-src 0 idx) new-ns-src (subs file-src (+ idx (count src))))
                                             false))]
               (if-not tx
                 {:ok? false :error :source-sync-mismatch :ns s}
                 (let [r (d/transact conn {:tx-data tx})
                       eval-err (when eval?
                                  (try
                                    (binding [*ns* *ns*]
                                      (eval (read-string new-ns-src)))
                                    nil
                                    (catch Throwable ex (or (.getMessage ex) (str ex)))))]
                   (ctx/refresh-active! (:db-after r) (:tx-data r))
                   (cond-> {:ok? (nil? eval-err)
                            :ns s :path path :changed? true :added (:added res)
                            :tx (:max-tx (:db-after r))
                            :diff (cst/unified-diff src new-ns-src key)}
                     eval-err (assoc :eval-error eval-err))))))))))))

(defn ns-requires
  "Returns the parsed `:require` specs of namespace `nsym` directly from its `#ns` form."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [nsym]
  (when-let [{:keys [conn branch]} *agent-ctx*]
    (let [s (if (string? nsym) (symbol nsym) nsym)
          {:keys [src]} (resolve-form-target conn branch s)]
      (if (str/blank? (str src))
        {:ok? false :error :ns-not-found :ns s}
        {:ok? true :ns s :requires (cst/parse-ns-requires src)}))))