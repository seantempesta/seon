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

(defn node->structural-key [& args] (apply cst/node->structural-key args))

(defn parse-non-ws-nodes [& args] (apply cst/parse-non-ws-nodes args))

(defn patch-form-source [& args] (apply cst/patch-form-source args))

(defn unified-diff [& args] (apply cst/unified-diff args))

(defn collect-body-refs [& args] (apply cst/collect-body-refs args))

(defn analyze-ns-requires [& args] (apply cst/analyze-ns-requires args))

(defn prune-unused-requires-src [& args] (apply cst/prune-unused-requires-src args))

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

(defn do-edit-fn!
  ([target old-str new-str] (do-edit-fn! target old-str new-str {}))
  ([target old-str new-str {:keys [occurrence dry-run? eval?] :or {eval? true}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [{:keys [ns key path src order lead]} (resolve-form-target conn branch target)]
       (if (str/blank? (str src))
         {:ok? false :error :form-not-found :target target :key key}
         (let [to-src (fn [x] (if (string? x) x (binding [*print-meta* true *print-namespace-maps* false] (pr-str (cst/strip-reader-meta x)))))
               old-s (to-src old-str)
               new-s (to-src new-str)
               res (patch-form-source src old-s new-s {:occurrence occurrence})]
           (if-not (:ok? res)
             (assoc res :target target :key key)
             (let [new-fsrc (:src res)
                   udiff (unified-diff src new-fsrc key)
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

(defn do-grep-src
  ([pattern] (do-grep-src pattern {}))
  ([pattern {:keys [ns limit] :or {limit 30}}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [bdb @conn
           ns-pref (when ns (name ns))
           re? (instance? java.util.regex.Pattern pattern)
           q-low (when-not re? (str/lower-case (str pattern)))
           match-str? (fn [^String s]
                        (if re?
                          (boolean (re-find pattern s))
                          (str/includes? (str/lower-case s) q-low)))
           hits (volatile! [])]
       (if ns-pref
         (let [matching-nss (->> (d/datoms bdb :aevt :seon.code.ns/name)
                                 (map :v)
                                 (filter (fn [n]
                                           (let [s (name n)]
                                             (or (= s ns-pref) (str/starts-with? s (str ns-pref ".")))))))
               cand-eids (into [] (mapcat #(map :e (d/datoms bdb :avet :seon.code.form/ns %))) matching-nss)]
           (doseq [eid cand-eids
                   :while (< (count @hits) limit)
                   :let [src (:v (first (d/datoms bdb :eavt eid :seon.code.form/src)))]
                   :when (and src (match-str? src))
                   :let [fk (:v (first (d/datoms bdb :eavt eid :seon.code.form/key)))]]
             (doseq [[i line] (map-indexed vector (str/split-lines src))
                     :while (< (count @hits) limit)
                     :when (match-str? line)]
               (let [t (str/trim line)]
                 (vswap! hits conj {:key fk :line (inc i) :text (if (> (count t) 120) (str (subs t 0 120) "…") t)})))))
         (doseq [dt (d/datoms bdb :aevt :seon.code.form/src)
                 :while (< (count @hits) limit)
                 :let [src (str (:v dt))]
                 :when (match-str? src)
                 :let [fk (:v (first (d/datoms bdb :eavt (:e dt) :seon.code.form/key)))]]
           (doseq [[i line] (map-indexed vector (str/split-lines src))
                   :while (< (count @hits) limit)
                   :when (match-str? line)]
             (let [t (str/trim line)]
               (vswap! hits conj {:key fk :line (inc i) :text (if (> (count t) 120) (str (subs t 0 120) "…") t)})))))
       @hits))))

(defn do-unused-requires [nsym]
  (when-let [{:keys [conn branch]} *agent-ctx*]
    (let [s (if (string? nsym) (symbol nsym) nsym)
          fsrc (merged-ns-src conn branch s)]
      (if (str/blank? fsrc)
        {:ok? false :error :ns-not-found :ns s}
        (let [analysis (analyze-ns-requires fsrc)]
          {:ok? true
           :ns s
           :unused (vec (map :lib (remove :keep? analysis)))
           :kept (vec (map :lib (filter :keep? analysis)))
           :detail analysis})))))

(defn do-prune-requires!
  ([nsym] (do-prune-requires! nsym {}))
  ([nsym {:keys [dry-run?]}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [s (if (string? nsym) (symbol nsym) nsym)
           {:keys [path]} (resolve-form-target conn branch s)
           fsrc (merged-ns-src conn branch s)]
       (if (str/blank? fsrc)
         {:ok? false :error :ns-not-found :ns s}
         (let [res (prune-unused-requires-src fsrc)
               udiff (unified-diff fsrc (:src res) path)]
           (cond
             (not (:changed? res)) (assoc res :ns s :path path :diff "")
             dry-run? (assoc res :ns s :path path :dry-run? true :diff udiff)
             :else
             (let [tx (code/file-import-tx @conn path (:src res) false)
                   r (d/transact conn {:tx-data tx})]
               (ctx/refresh-active! (:db-after r) (:tx-data r))
               {:ok? true :ns s :path path :changed? true
                :removed (:removed res) :kept (:kept res)
                :tx (:max-tx (:db-after r)) :diff udiff}))))))))

(defn do-run-tests!
  ([] (do-run-tests! nil))
  ([targets]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [t0 (System/nanoTime)
           bdb @conn
           base-cid (when (not= branch :main)
                      (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
           ops (when base-cid (code/changed-form-ops conn base-cid (d/commit-id bdb)))
           _ (when (seq (:upserts ops))
               (wsup/apply-form-overlay! bdb {:upserts (:upserts ops) :retracted-keys (:retracted-keys ops)}))
           target-list (cond
                         (nil? targets) nil
                         (or (vector? targets) (set? targets) (seq? targets)) (vec targets)
                         :else [targets])
           test-syms
           (if (seq target-list)
             (->> target-list
                  (mapcat (fn [x]
                            (let [s (if (string? x) (symbol x) x)]
                              (cond
                                (namespace s) [s]
                                (str/ends-with? (name s) "-test")
                                (->> (d/datoms bdb :avet :seon.code.var/ns s)
                                     (keep #(let [v (d/pull bdb '[:seon.code.var/name :seon.code.var/test?] (:e %))]
                                              (when (:seon.code.var/test? v) (:seon.code.var/name v)))))
                                :else (code/reaching-tests bdb s)))))
                  distinct sort vec)
             (let [changed-nss (distinct (keep :seon.code.form/ns (:upserts ops)))]
               (->> changed-nss
                    (mapcat #(code/reaching-tests bdb %))
                    distinct sort vec)))
           results (mapv #(wsup/run-single-test-var % (str "local:" %)) test-syms)
           failures (filterv #(not (:seon.test/pass? %)) results)
           ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)]
       {:ok? (empty? failures)
        :ran (count results)
        :passed (- (count results) (count failures))
        :failed (count failures)
        :ms ms
        :failures (mapv #(select-keys % [:seon.test/var :seon.test/report :seon.test/ms]) failures)
        :results (mapv #(select-keys % [:seon.test/var :seon.test/pass? :seon.test/ms]) results)}))))

(defn do-diff
  ([] (do-diff nil))
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
                                 [key (unified-diff (or old-s "") (or src "") key)])))
                       (take 12 upserts))]
       {:branch branch
        :upserts (vec (sort (map :seon.code.form/key (:upserts ops))))
        :retracted (vec (sort (:retracted-keys ops)))
        :schemas (vec (sort (:schema-keys ops)))
        :health-errors errs
        :diffs diffs}))))

(def ^:private user-helper-docs
  '{edit-fn!        {:arglists ([target old-subform new-subform] [target old-subform new-subform opts])
                     :doc "Structurally replace old-subform with new-subform in 'ns/fn or 'ns (ignores whitespace/tabs/comments, preserves formatting, persists & live-evals). Opts: {:occurrence N :dry-run? true :eval? true}."}
    add-require!    {:arglists ([nsym spec-or-specs] [nsym spec-or-specs opts])
                     :doc "Idempotently add one or more :require specs (e.g. '[seon.store :as store] or '[[a :as a] [b :as b]]) to nsym, persist #ns, and live-require immediately."}
    set-meta!       {:arglists ([batch-map] [target meta-map] [target meta-map opts])
                     :doc "Insert/merge attr-map (e.g. {:malli/schema [...] :cost/ticks 500000}) onto a defn/defn- right after name + docstring, preserving formatting. Accepts a single 'ns/fn or a batch map {'ns/f1 {...} 'ns/f2 {...}}."}
    ns-requires     {:arglists ([nsym])
                     :doc "Return parsed :require specs of namespace nsym cleanly in ~1 ms without dumping the whole file."}
    grep-src        {:arglists ([pattern] [pattern {:keys [ns limit]}])
                     :doc "Fast linear/scoped grep across all form sources in Datahike (string or regex)."}
    unused-requires {:arglists ([nsym])
                     :doc "AST-accurate analysis of used vs unused :require specs in nsym (skips strings/docstrings/comments)."}
    prune-requires! {:arglists ([nsym] [nsym {:keys [dry-run?]}])
                     :doc "AST-accurately remove unused :require specs from nsym and persist cleanly."}
    run-tests!      {:arglists ([] [target-or-targets])
                     :doc "Run branch-local clojure.test deftest(s) live in-turn. No args = all tests reaching your branch changes; or pass 'ns-test/var, 'ns-test, or 'ns."}
    diff            {:arglists ([] [target])
                     :doc "Return branch upserts, retractions, schemas, health errors, and unified line diffs vs merge-base."}
    help            {:arglists ([] [sym])
                     :doc "List all user/* REPL helpers with signatures and docs, or inspect one helper (e.g. (user/help 'edit-fn!))."}
    source          {:arglists ([target-or-vec])
                     :doc "Read source of 'ns/fn, 'ns, 'ns#ns, :seon.schema/key, \"docs/f.md#sec\", 'user/<helper>, or a vector of targets ['a/b 'c/d]."}
    ns-outline      {:arglists ([nsym])
                     :doc "List all forms in nsym with token counts, local deps, and external callers."}
    move-vars!      {:arglists ([{:keys [from to alias doc vars include-deps?]}])
                     :doc "Losslessly split vars from :from into child ns :to, adding :as/:refer and hot-reloading both."}
    register-schema! {:arglists ([schema-key form] [schema-key form opts])
                      :doc "Register/update a Malli schema -> derived Datahike attribute, persisted to resources/seon/schemas/ on merge."}
    remove-var!     {:arglists ([sym] [sym {:keys [force?]}])
                     :doc "Safely retract a var & form (refuses if callers exist unless {:force? true})."}
    callers         {:arglists ([sym]) :doc "List all vars referencing sym."}
    callees         {:arglists ([sym]) :doc "List :refs, :schema-refs, and :keywords of sym."}
    tests-for       {:arglists ([sym-or-ns]) :doc "List all tests reaching sym-or-ns."}
    search          {:arglists ([query]) :doc "Search vars, schemas, and docs by substring."}
    health          {:arglists ([]) :doc "Compute live :seon.health/report on your branch."}
    past-evals      {:arglists ([] [limit-or-opts]) :doc "Query historical evals via Datahike d/history (pass int limit, grep string, or map)."}
    autofix-health! {:arglists ([] [{:keys [ns class dry-run?]}]) :doc "Execute programmatic :fix actions from (user/health) (e.g. prune unused requires) across your branch."}
    inspect-branch  {:arglists ([branch-or-agent-id]) :doc "Inspect a worker's branch diff, health errors, latest auto-proofs, and recent evals in 1 call."}
    save-example!   {:arglists ([agent-id example-id] [agent-id example-id opts]) :doc "Snapshot a real completed agent session into :example/<name> for resources/seon/agents/examples.edn."}
    create-task!    {:arglists ([{:keys [id from-template scope title plan needs tests spawn-agent?]}]) :doc "Create a task on :main (optionally inheriting defaults from :from-template) and persist to resources/seon/tasks.edn."}
    ready!          {:arglists ([]) :doc "Mark branch ready for automated test + 3-way merge + Git write-back."}})

(defn- intern-doc! [u sym f]
  (let [v (intern u sym f)]
    (when-let [m (get user-helper-docs sym)]
      (alter-meta! v merge m))
    v))

(defn do-help
  "Lists available `user/*` REPL helpers with signatures & one-line docs, or shows one helper in full."
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

(defn do-set-meta!
  "Structurally inserts or merges metadata (e.g. `{:malli/schema [...] :cost/ticks 500000 :cost/bytes 1048576}`)
   into a `(defn ...)` / `(defn- ...)` attr-map right after name + optional docstring, preserving all formatting.
   Validates `:malli/schema` upfront against the live registry before writing.
   Supports both single-target `(set-meta! 'ns/fn {...})` and batch map `(set-meta! {'ns/f1 {...} 'ns/f2 {...}})`."
  {:malli/schema [:function [:=> [:cat :any] :map] [:=> [:cat :any :map] [:maybe :map]] [:=> [:cat :any :map :map] [:maybe :map]]]}
  ([target-or-batch]
   (if (map? target-or-batch)
     (let [results (into {}
                         (map (fn [[t m]] [t (do-set-meta! t m {})]))
                         target-or-batch)]
       {:ok? (every? :ok? (vals results))
        :updated (count (filter :ok? (vals results)))
        :total (count results)
        :results results})
     {:ok? false :error :invalid-args :hint "Call (user/set-meta! 'ns/fn {:malli/schema ...}) or (user/set-meta! {'ns/f1 {...} ...})."}))
  ([target meta-map] (do-set-meta! target meta-map {}))
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
                       udiff (unified-diff src new-fsrc key)]
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

(defn do-add-require!
  "Idempotently adds one or more `:require` specs (e.g. `'[seon.store :as store]` or `[[a :as a] [b :as b]]`)
   to `nsym`, persists `#ns`, and activates the require(s) immediately in the live namespace."
  ([nsym spec-or-specs] (do-add-require! nsym spec-or-specs {}))
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
             dry-run? {:ok? true :dry-run? true :ns s :added (:added res) :diff (unified-diff src (:src res) key)}
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
                            :diff (unified-diff src new-ns-src key)}
                     eval-err (assoc :eval-error eval-err))))))))))))

(defn do-ns-requires
  "Returns the parsed `:require` specs of namespace `nsym` directly from its `#ns` form."
  [nsym]
  (when-let [{:keys [conn branch]} *agent-ctx*]
    (let [s (if (string? nsym) (symbol nsym) nsym)
          {:keys [src]} (resolve-form-target conn branch s)]
      (if (str/blank? (str src))
        {:ok? false :error :ns-not-found :ns s}
        {:ok? true :ns s :requires (cst/parse-ns-requires src)}))))

(defn do-autofix-health!
  "General programmatic autofix runner over `seon.health/report`: executes every problem carrying a
   `:fix {:fn sym :args [...]}` descriptor (optionally filtered by `:ns`, `:class`, or `{:dry-run? true}`)."
  {:malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :map] [:maybe :map]]]}
  ([] (do-autofix-health! {}))
  ([{:keys [ns class dry-run?]}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [rep (health/report @conn)
           targets (->> (:problems rep)
                        (filter :fix)
                        (filter #(or (nil? ns) (= ns (:ns %)) (health/ns-in-scope? ns (:ns %))))
                        (filter #(or (nil? class) (= class (:class %))))
                        vec)
           results (mapv (fn [p]
                           (let [{:keys [fn args]} (:fix p)
                                 f (or (try (resolve fn) (catch Throwable _ nil))
                                       (try (requiring-resolve fn) (catch Throwable _ nil)))]
                             (if-not f
                               {:ok? false :class (:class p) :subject (:subject p) :error :fix-fn-not-found :fn fn}
                               (try
                                 (let [r (apply f (if dry-run? (concat args [{:dry-run? true}]) args))]
                                   {:ok? (boolean (:ok? r true))
                                    :class (:class p)
                                    :subject (:subject p)
                                    :changed? (boolean (:changed? r true))
                                    :result (dissoc r :diff)})
                                 (catch Throwable ex
                                   {:ok? false :class (:class p) :subject (:subject p)
                                    :error (or (.getMessage ex) (str ex))})))))
                         targets)]
       {:ok? (every? :ok? results)
        :dry-run? (boolean dry-run?)
        :count (count results)
        :results results}))))

(defn do-inspect-branch
  "Coordinator inspection helper: given a branch keyword (e.g. `:branch/x`) OR an agent keyword
   (e.g. `:ns/seon.render`), returns changed forms, health errors, latest fast proofs, recent evals,
   and compact unified diffs in one O(|Delta|) call."
  {:malli/schema [:=> [:cat :keyword] [:maybe :map]]}
  [branch-or-agent]
  (when-let [{:keys [conn]} *agent-ctx*]
    (let [mdb (store/head conn :main)
          ag-ent (when (first (d/datoms mdb :avet :seon.agent/id branch-or-agent))
                   (d/pull mdb '[:seon.agent/id :seon.agent/branch :seon.agent/scope :seon.agent/goal :seon.agent/task]
                           [:seon.agent/id branch-or-agent]))
          branch (or (:seon.agent/branch ag-ent) branch-or-agent)
          bdb (try (store/head conn branch) (catch Throwable _ nil))]
      (if-not bdb
        {:ok? false :error :branch-not-found :target branch-or-agent :branch branch}
        (let [base-cid (when (not= branch :main)
                         (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
              base-db (when base-cid (d/commit-as-db conn base-cid))
              ops (if base-cid
                    (code/changed-form-ops conn base-cid (d/commit-id bdb))
                    {:upserts [] :retracted-keys #{} :schema-keys #{}})
              touched-nss (vec (distinct (keep :seon.code.form/ns (:upserts ops))))
              errs (if (= branch :main) [] (health/caused-by-branch mdb bdb {} touched-nss))
              aid (or (:seon.agent/id ag-ent)
                      (some (fn [dt]
                              (let [a (d/pull bdb '[:seon.agent/id :seon.agent/branch] (:e dt))]
                                (when (= branch (:seon.agent/branch a)) (:seon.agent/id a))))
                            (d/datoms bdb :aevt :seon.agent/id)))
              b-ag (when aid (d/pull bdb '[:seon.agent/last-proofs :seon.agent/ready? :seon.agent/waiting?] [:seon.agent/id aid]))
              recent-evals (when aid
                             (->> (d/datoms bdb :avet :seon.agent.eval/agent aid)
                                  (map #(d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/handle
                                                      :seon.agent.eval/intent :seon.agent.eval/error
                                                      :seon.agent.eval/ms] (:e %)))
                                  (sort-by #(or (:seon.agent.eval/turn %) 0))
                                  (take-last 6)
                                  vec))
              diffs (into {}
                          (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                                  (let [old-s (when base-db
                                                (:seon.code.form/src (d/pull base-db '[:seon.code.form/src] [:seon.code.form/key key])))
                                        ud (unified-diff (or old-s "") (or src "") key)]
                                    [key (if (> (count ud) 1200) (str (subs ud 0 1200) "\n…") ud)])))
                          (take 6 (:upserts ops)))]
          {:ok? true
           :agent aid
           :branch branch
           :branch-tx (:max-tx bdb)
           :ready? (boolean (:seon.agent/ready? b-ag))
           :last-proofs (:seon.agent/last-proofs b-ag)
           :upserts (vec (sort (map :seon.code.form/key (:upserts ops))))
           :retracted (vec (sort (:retracted-keys ops)))
           :schemas (vec (sort (:schema-keys ops)))
           :health-errors errs
           :recent-evals recent-evals
           :diffs diffs})))))

(defn do-save-example!
  "Snapshots a real completed agent session (`source-agent-id`) into a declarative `:example/<name>`
   record tagged `:seon.agent/seed-path \"resources/seon/agents/examples.edn\"` on your current branch.
   Merging writes the curated real-world session cleanly into `resources/seon/agents/examples.edn`."
  {:malli/schema [:function [:=> [:cat :keyword :keyword] [:maybe :map]] [:=> [:cat :keyword :keyword :map] [:maybe :map]]]}
  ([source-agent-id example-id] (do-save-example! source-agent-id example-id {}))
  ([source-agent-id example-id {:keys [goal max-evals] :or {max-evals 8}}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (if-not (= "example" (namespace example-id))
       {:ok? false :error :invalid-example-id :hint "Example ID must be in the :example/* namespace (e.g. :example/my-workflow)."}
       (let [mdb (store/head conn :main)
             ag (or (when (first (d/datoms mdb :avet :seon.agent/id source-agent-id))
                      (d/pull mdb '[*] [:seon.agent/id source-agent-id]))
                    (d/pull @conn '[*] [:seon.agent/id source-agent-id]))]
         (if-not (:seon.agent/id ag)
           {:ok? false :error :agent-not-found :agent source-agent-id}
           (let [b (:seon.agent/branch ag :main)
                 bdb (or (try (store/head conn b) (catch Throwable _ nil)) @conn)
                 raw-evals (->> (d/datoms bdb :avet :seon.agent.eval/agent source-agent-id)
                                (map #(d/pull bdb '[*] (:e %)))
                                (remove :seon.agent.eval/error)
                                (filter #(not (str/blank? (str (:seon.agent.eval/code %)))))
                                (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0)))
                                (take max-evals)
                                vec)
                 trunc (fn [s n] (let [st (str s)] (if (> (count st) n) (str (subs st 0 n) "…") st)))
                 ex-ent (cond-> {:seon.agent/id example-id
                                 :seon.agent/branch :main
                                 :seon.agent/role (or (:seon.agent/role ag) :worker)
                                 :seon.agent/goal (or goal (:seon.agent/goal ag) "Curated real session example.")
                                 :seon.agent/paused? true
                                 :seon.agent/waiting? true
                                 :seon.agent/seed-path "resources/seon/agents/examples.edn"}
                          (:seon.agent/scope ag) (assoc :seon.agent/scope (:seon.agent/scope ag)))
                 eval-ents (vec
                            (map-indexed
                             (fn [idx ev]
                               (let [turn (inc idx)
                                     eid (hasch/uuid [:seed-eval example-id turn 0])]
                                 (cond-> {:seon.agent.eval/id eid
                                          :seon.agent.eval/agent example-id
                                          :seon.agent.eval/turn turn
                                          :seon.agent.eval/call-index 0
                                          :seon.agent.eval/call-id (str "ex-" (name example-id) "-" turn)
                                          :seon.agent.eval/code (trunc (:seon.agent.eval/code ev) 1200)
                                          :seon.agent.eval/value (trunc (:seon.agent.eval/value ev) 600)
                                          :seon.agent.eval/out (trunc (:seon.agent.eval/out ev "") 200)
                                          :seon.agent.eval/handle (symbol "result" (str "e" turn))
                                          :seon.agent.eval/ms (double (or (:seon.agent.eval/ms ev) 1.0))
                                          :seon.agent.eval/ticks (long (or (:seon.agent.eval/ticks ev) 50000))}
                                   (not (str/blank? (str (:seon.agent.eval/intent ev))))
                                   (assoc :seon.agent.eval/intent (str (:seon.agent.eval/intent ev)))
                                   (not (str/blank? (str (:seon.agent.eval/thought ev))))
                                   (assoc :seon.agent.eval/thought (trunc (:seon.agent.eval/thought ev) 300))
                                   (:seon.agent.eval/result-schema ev)
                                   (assoc :seon.agent.eval/result-schema (:seon.agent.eval/result-schema ev)))))
                             raw-evals))
                 r (d/transact conn {:tx-data (into [ex-ent] eval-ents)})]
             (ctx/refresh-active! (:db-after r) (:tx-data r))
             {:ok? true :example-id example-id :source-agent source-agent-id
              :evals-saved (count eval-ents) :seed-path "resources/seon/agents/examples.edn"
              :tx (:max-tx (:db-after r))})))))))

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
  "Interns `edit-fn!`, `add-require!`, `set-meta!`, `ns-requires`, `grep-src`, `unused-requires`,
   `prune-requires!`, `run-tests!`, unified `diff`, and `help` into `user` with full metadata."
  {:malli/schema [:=> [:cat :seon.host/ns] :any]}
  [u]
  (intern-doc! u 'edit-fn! do-edit-fn!)
  (intern-doc! u 'add-require! do-add-require!)
  (intern-doc! u 'set-meta! do-set-meta!)
  (intern-doc! u 'ns-requires do-ns-requires)
  (intern-doc! u 'grep-src do-grep-src)
  (intern-doc! u 'unused-requires do-unused-requires)
  (intern-doc! u 'prune-requires! do-prune-requires!)
  (intern-doc! u 'run-tests! do-run-tests!)
  (intern-doc! u 'diff do-diff)
  (intern-doc! u 'help do-help)
  (intern-doc! u 'autofix-health! do-autofix-health!)
  (intern-doc! u 'inspect-branch do-inspect-branch)
  (intern-doc! u 'save-example! do-save-example!)
  (intern-doc! u 'create-task! do-create-task!))