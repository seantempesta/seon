(ns seon.code.edit
"Whitespace-, tab-, newline-, and comment-insensitive structural Clojure editing (`rewrite-clj` CST),
 AST-accurate unused `:require` analysis/pruning, body grep, unified diffs, and in-turn branch test running."
(:require [clojure.pprint :as pp]
          [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.parse :as parse]
            [seon.ctx :as ctx]
            [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
            [seon.health :as health]
            [seon.store :as store]
            [seon.code.cst :as cst]
            [seon.code.cst.ns :as cst-ns]
            [malli.core :as m]
            [seon.schema :as schema]
          [seon.code.analyze :as analyze]
          [seon.code.io :as cio]))

(defn- extract-defn-malli-schema
  "Extracts `:malli/schema` from a parsed `(defn ...)` / `(defn- ...)` form (var metadata or attr-map)."
  [form-obj]
  (when (and
          (seq? form-obj)
          (contains? #{'defn 'defn-} (first form-obj)))
    (let [sym-meta (meta (second form-obj))
          rest-forms (drop 2 form-obj)
          after-doc (if (string? (first rest-forms))
                      (next rest-forms)
                      rest-forms)
          attr-map (when (map? (first after-doc)) (first after-doc))]
      (or (:malli/schema attr-map) (:malli/schema sym-meta)))))

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
  "O(1-form) delta persistence for non-`#ns` form edits with compiler-exact var enrichment."
  ([db nsym fkey path order lead new-fsrc] (single-form-upsert-tx db nsym fkey path order lead new-fsrc nil))
  ([db nsym fkey path order lead new-fsrc acct]
   (let [ns-src (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key (str nsym "#ns")]))
         local-defs (into #{} (map #(symbol (name (:seon.code.var/name %)))) (code/ns-vars db nsym))
         reparsed (parse/read-clj path (str (or ns-src (str "(ns " nsym ")")) "\n" new-fsrc) local-defs)
         kept-forms (mapv (fn [f]
                            (cond-> f
                              (= (:seon.code.form/key f) fkey)
                              (assoc :seon.code.form/order (or order 1)
                                     :seon.code.form/lead (or lead "\n\n"))))
                          (rest (:forms reparsed)))
         kept-vars (mapv #(analyze/enrich-var-with-account db % acct code/boot-root-keys) (:vars reparsed))
         stale-form-ops (cio/retract-stale-many-ops db :seon.code.form/key [:seon.code.form/deps] kept-forms)
         stale-var-ops (analyze/stale-var-edge-retracts db kept-vars {:preserve-analyzed? (nil? acct)})]
     (vec (concat stale-form-ops stale-var-ops kept-forms kept-vars)))))

(defn edit-fn!
  "Structurally replaces `old-str` with `new-str` inside `'ns/fn` or `'ns` (whitespace/comment-insensitive
   via `seon.code.cst`), persists to Datahike, and live-evaluates in the target namespace.
   Also supports batched multi-edit in ONE parse + compile:
     `(edit-fn! 'ns/fn [[old1 new1] [old2 new2]])` or `(edit-fn! 'ns/fn [[old1 new1] ...] opts)`."
  {:malli/schema [:function
                  [:=> [:cat :any [:vector [:tuple :any :any]]] [:maybe :map]]
                  [:=> [:cat :any :any :any] [:maybe :map]]
                  [:=> [:cat :any :any :any :map] [:maybe :map]]]
   :seon.eval/budget-override {:ticks 300000000, :bytes 536870912}}
  ([target edits]
   (edit-fn! target edits {}))
  ([target a b]
   (if (and (vector? a) (seq a) (vector? (first a)) (map? b))
     (edit-fn! target a nil b)
     (edit-fn! target a b {})))
  ([target old-or-edits new-str {:keys [occurrence dry-run? eval?] :or {eval? true}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [{:keys [ns key path src order lead]} (resolve-form-target conn branch target)]
       (if (str/blank? (str src))
         {:ok? false :error :form-not-found :target target :key key}
         (let [to-src (fn [x] (if (string? x) x (binding [*print-meta* true *print-namespace-maps* false]
                                                (let [clean (cst-ns/strip-reader-meta x)
                                                      ps (pr-str clean)]
                                                  (if (> (count ps) 90)
                                                    (str/trim (with-out-str (pp/write clean :dispatch pp/code-dispatch)))
                                                    ps)))))
               pairs (if (and (nil? new-str) (vector? old-or-edits) (seq old-or-edits) (vector? (first old-or-edits)))
                       old-or-edits
                       [[old-or-edits new-str]])
               str-pairs (mapv (fn [[o n]] [(to-src o) (to-src n)]) pairs)
               raw-patched (cst/patch-form-source-batch src str-pairs {:occurrence occurrence})
               patched (if (:ok? raw-patched)
                         {:ok? true
                          :cur-src (:src raw-patched)
                          :first-line (:line raw-patched)
                          :applied (:applied raw-patched)}
                         (assoc raw-patched :target target :key key))]
           (if-not (:ok? patched)
             patched
             (let [new-fsrc (:cur-src patched)
                   udiff (cst/unified-diff src new-fsrc key)
                   is-ns? (str/ends-with? key "#ns")]
               (if dry-run?
               {:ok? true :dry-run? true :target target :key key :line (:first-line patched) :applied (:applied patched) :diff udiff}
               (let [ev-res (if eval?
                            (analyze/eval-form-with-account! @conn ns new-fsrc is-ns?)
                            {:account (when-not is-ns? (analyze/analyze-form-account ns new-fsrc false)) :error nil})
                     eval-err (:error ev-res)
                     tx (if is-ns?
                          (let [file-src (merged-ns-src conn branch ns)
                                idx (.indexOf ^String file-src ^String src)]
                            (when-not (neg? idx)
                              (code/file-import-tx @conn path
                                                   (str (subs file-src 0 idx) new-fsrc (subs file-src (+ idx (count src))))
                                                   false)))
                          (single-form-upsert-tx @conn ns key path order lead new-fsrc (:account ev-res)))]
                 (if-not tx
                   {:ok? false :error :source-sync-mismatch :target target :key key}
                   (let [tx-meta {:seon.tx/agent (or (:agent-id *agent-ctx*) :repl)
                                  :seon.tx/branch branch
                                  :seon.tx/reason (str "edit-fn! " target)}
                         r0 (binding [store/*tx-meta* tx-meta] (d/transact conn {:tx-data tx}))
                         r (if-let [ord-tx (when-not is-ns?
                                             (not-empty
                                               (cst-ns/topo-order-ns-forms
                                                 (code/ns-forms (:db-after r0) ns))))]
                             (binding [store/*tx-meta* tx-meta] (d/transact conn {:tx-data ord-tx}))
                             r0)]
                     (ctx/refresh-active! (:db-after r) (:tx-data r))
                     (let [defonce? (str/starts-with? (str/triml new-fsrc) "(defonce ")]
                       (cond->
                         {:ok? (nil? eval-err),
                          :target target,
                          :key key,
                          :path path,
                          :line (:first-line patched),
                          :applied (:applied patched),
                          :tx (:max-tx (:db-after r)),
                          :diff udiff}
                         eval-err
                         (assoc :eval-error eval-err)
                         defonce?
                         (assoc
                           :note
                           "defonce keeps its existing runtime binding; use (reset! ...) or (alter-var-root ...) if testing a new initial value live.")))))))))))))))

(defn unused-requires
  "AST-accurate analysis of used vs unused `:require` specs in namespace `nsym`."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [nsym]
  (when-let [{:keys [conn branch]} *agent-ctx*]
    (let [s (if (string? nsym) (symbol nsym) nsym)
          fsrc (merged-ns-src conn branch s)]
      (if (str/blank? fsrc)
        {:ok? false :error :ns-not-found :ns s}
        (let [analysis (cst-ns/analyze-ns-requires fsrc)]
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
         (let [res (cst-ns/prune-unused-requires-src fsrc)
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
  "Returns branch upserts, retractions, schemas, and compact unified diffs vs merge-base.
   Fast by default (`:health? false`, < 15 ms); pass `{:health? true}` or `(diff 'ns/fn {:health? true})`
   to include full `seon.health/caused-by-branch` checks."
  {:malli/schema [:function
                  [:=> [:cat] [:maybe :map]]
                  [:=> [:cat :any] [:maybe :map]]
                  [:=> [:cat :any :map] [:maybe :map]]]
   :seon.eval/budget-override {:ticks 300000000, :bytes 1073741824}}
  ([] (diff nil {}))
  ([target-or-opts]
   (if (map? target-or-opts)
     (diff nil target-or-opts)
     (diff target-or-opts {})))
  ([target {:keys [health?] :or {health? false}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [bdb @conn
           mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
           base-cid (when (not= branch :main) (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
           base-db (when base-cid (d/commit-as-db conn base-cid))
           ops (if base-cid
                 (code/changed-form-ops conn base-cid (d/commit-id bdb))
                 {:upserts [] :retracted-keys #{} :schema-keys #{}})
           errs (if (and health? (not= branch :main))
                  (health/caused-by-branch mdb bdb {})
                  [])
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
       {:ok? true
        :branch branch
        :upserts (vec (sort (map :seon.code.form/key (:upserts ops))))
        :retracted (vec (sort (:retracted-keys ops)))
        :schemas (vec (sort (:schema-keys ops)))
        :health-checked? (boolean health?)
        :health-errors errs
        :diffs diffs}))))

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
         (let [res (cst-ns/add-require-src src spec-or-specs)]
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
        {:ok? true :ns s :requires (cst-ns/parse-ns-requires src)}))))

(defn reorder-ns-forms!
  "Topologically reorders `:seon.code.form/order` for `nsym` so same-namespace callees always\n       precede their callers (eliminating `:used-before-defined` with minimal diff)."
  {:malli/schema
   [:function
    [:=> [:cat :any] [:maybe :map]]
    [:=> [:cat :any :map] [:maybe :map]]]}
  ([nsym] (reorder-ns-forms! nsym {}))
  ([nsym {:keys [dry-run?]}]
    (when-let [{:keys [conn]} *agent-ctx*]
      (let [ns-sym (if (string? nsym) (symbol nsym) nsym)
            forms (code/ns-forms @conn ns-sym)
            tx (cst-ns/topo-order-ns-forms forms)]
        (cond
          (empty? tx) {:ok? true,
                       :ns ns-sym,
                       :changed? false,
                       :reordered 0}
          dry-run? {:ok? true,
                    :dry-run? true,
                    :ns ns-sym,
                    :changed? true,
                    :reordered (count tx)}
          :else (let [r (d/transact conn {:tx-data tx})]
                  (ctx/refresh-active! (:db-after r) (:tx-data r))
                  {:ok? true,
                   :ns ns-sym,
                   :changed? true,
                   :reordered (count tx),
                   :tx (:max-tx (:db-after r))}))))))

(defn- fn-schema-arity [fs]
  (let [in (first (m/children fs))
        it (when in (m/type in))
        ch (when in (m/children in))]
    (when (= it :cat)
      (if (some #(contains? #{:* :? :+} (m/type %)) ch) :variadic (count ch)))))

(defn- check-examples-against-arities [exs arities target key sch]
  (when (some? exs)
    (if-not (and (coll? exs) (not (map? exs)))
      {:ok? false :error :invalid-malli-examples :target target :key key :schema sch
       :hint ":examples must be a vector of [[arg1 ...] expected-out] pairs."}
      (some (fn [ex]
              (cond
                (not (and (vector? ex) (= 2 (count ex)) (vector? (first ex))))
                {:ok? false :error :invalid-malli-examples :target target :key key :example ex :expected-arities arities
                 :hint (str "Each :examples entry must be [[arg1 ...] expected-out] (e.g. [[] out] for 0-arg, [[x] out] for 1-arg). Got: " (pr-str ex))}

                (and (seq arities) (not (contains? arities :variadic)) (not (contains? arities (count (first ex)))))
                {:ok? false :error :malli-examples-arity-mismatch :target target :key key :example ex
                 :example-arity (count (first ex)) :expected-arities arities
                 :hint (str "Example args " (pr-str (first ex)) " has arity " (count (first ex))
                            " but schema expects arity " arities
                            " (for 0-arg use [[] expected-out], NOT [[[]] expected-out]).")}))
            exs))))

(defn- validate-defn-malli-schema
  "Validates `:malli/schema` against the live DB registry, checks arity match against `form-obj`, and validates `:examples` `[[args] out]` arity."
  [db target key form-obj sch]
  (try
    (let [compiled (m/schema sch {:registry (:registry (schema/registry-for-db db))})
          defn-arities (when (and (seq? form-obj) (contains? #{'defn 'defn-} (first form-obj)))
                         (let [tail (drop-while #(not (or (vector? %) (seq? %))) (drop 2 form-obj))
                               vecs (if (vector? (first tail)) [(first tail)] (keep #(when (seq? %) (first %)) tail))]
                           (into #{} (map #(if (some #{'&} %) :variadic (count %))) vecs)))
          t (m/type compiled)
          fn-schemas (cond (= t :=>) [compiled] (= t :function) (m/children compiled) :else nil)
          sch-arities (when (seq fn-schemas) (into #{} (keep fn-schema-arity) fn-schemas))
          ex-err (or (check-examples-against-arities (:examples (m/properties compiled)) (or sch-arities defn-arities) target key sch)
                     (when (= t :function)
                       (some (fn [fs]
                               (when-let [exs (:examples (m/properties fs))]
                                 (let [a (fn-schema-arity fs)]
                                   (check-examples-against-arities exs (if a #{a} sch-arities) target key sch))))
                             fn-schemas)))]
      (cond
        (and (seq defn-arities) (seq sch-arities)
             (not (contains? defn-arities :variadic)) (not (contains? sch-arities :variadic))
             (not-every? defn-arities sch-arities))
        {:ok? false :error :malli-arity-mismatch :target target :key key
         :defn-arities defn-arities :schema-arities sch-arities :schema sch
         :hint (str "Schema arities " sch-arities " do not match defn arities " defn-arities ". Check (user/source '" key ").")}

        ex-err ex-err
        :else nil))
    (catch Throwable ex
      {:ok? false :error :invalid-malli-schema :target target :key key :schema sch
       :message (or (.getMessage ex) (str ex))
       :hint "Note: Malli predicates are symbols like number?, pos-int?, nat-int? (NOT :number). Built-ins: :int, :double, :string, :boolean, :keyword, :map, :vector, :any, :seon.host/db."})))

(defn set-meta!
  "Structurally inserts or merges metadata (e.g. `{:malli/schema [...] :cost/ticks 500000}`)
   into a `(defn ...)` / `(defn- ...)` attr-map, preserving formatting and compiler-analyzed edges.
   Validates `:malli/schema` upfront against the live registry. Supports single target or batch map."
  {:malli/schema [:function
                  [:=> [:cat :any] :map]
                  [:=> [:cat :any :map] [:maybe :map]]
                  [:=> [:cat :any :map :map] [:maybe :map]]]}
  ([target-or-batch]
   (if (map? target-or-batch)
     (let [results (into {} (map (fn [[t m]] [t (dissoc (set-meta! t m {}) :diff)])) target-or-batch)]
       {:ok? (every? :ok? (vals results))
        :updated (count (filter :ok? (vals results)))
        :total (count results)
        :results results})
     {:ok? false :error :invalid-args
      :hint "Call (user/set-meta! 'ns/fn {:malli/schema ...}) or (user/set-meta! {'ns/f1 {...} ...})."}))
  ([target meta-map] (set-meta! target meta-map {}))
  ([target meta-map {:keys [dry-run? eval?] :or {eval? true}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [{:keys [ns key path src order lead]} (resolve-form-target conn branch target)]
       (if (str/blank? (str src))
         {:ok? false :error :form-not-found :target target :key key}
         (let [sch-err (when (contains? meta-map :malli/schema)
                         (validate-defn-malli-schema @conn target key
                                                     (try (read-string src) (catch clojure.lang.ExceptionInfo _ nil))
                                                     (:malli/schema meta-map)))]
           (if sch-err
             sch-err
             (let [res (cst-ns/set-defn-meta-src src meta-map)]
               (if-not (:ok? res)
                 (assoc res :target target :key key)
                 (let [new-fsrc (:src res)
                       udiff (cst/unified-diff src new-fsrc key)]
                   (if dry-run?
                     {:ok? true :dry-run? true :target target :key key :action (:action res) :diff udiff}
                     (let [ev-res (if eval?
                                    (analyze/eval-form-with-account! @conn ns new-fsrc false)
                                    {:account (analyze/analyze-form-account ns new-fsrc false) :error nil})
                           eval-err (:error ev-res)
                           tx (single-form-upsert-tx @conn ns key path order lead new-fsrc (:account ev-res))
                           tx-meta {:seon.tx/agent (or (:agent-id *agent-ctx*) :repl)
                                    :seon.tx/branch branch
                                    :seon.tx/reason (str "set-meta! " target)}
                           r (binding [store/*tx-meta* tx-meta] (d/transact conn {:tx-data tx}))]
                       (ctx/refresh-active! (:db-after r) (:tx-data r))
                       (cond-> {:ok? (nil? eval-err)
                                :target target :key key :action (:action res)
                                :tx (:max-tx (:db-after r)) :diff udiff}
                         eval-err (assoc :eval-error eval-err))))))))))))))

(defn add-fn!
  "Adds or replaces a top-level definition form inside `target-ns`, auto-formats 1-line minified
   strings, auto-seeds `(ns ... (:require [clojure.test :refer [deftest is testing]]))` for brand-new
   `*-test` namespaces, persists with automatic topological ordering, and live-evals in `target-ns`."
  {:malli/schema [:function
                  [:=> [:cat :any :any] [:maybe :map]]
                  [:=> [:cat :any :any :map] [:maybe :map]]]
   :seon.eval/budget-override {:ticks 300000000 :bytes 536870912}}
  ([target-ns form-src] (add-fn! target-ns form-src {}))
  ([target-ns form-src {:keys [eval?] :or {eval? true}}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [nsym (if (string? target-ns) (symbol target-ns) target-ns)
           fmt-form (fn [f]
                      (binding [*print-meta* true *print-namespace-maps* false]
                        (str/trim (with-out-str (pp/write (cst-ns/strip-reader-meta f) :dispatch pp/code-dispatch)))))
           raw-src (if (string? form-src) (str/trim form-src) (fmt-form form-src))
           parsed (try {:ok? true :val (read-string raw-src)}
                       (catch Throwable ex {:ok? false :error :invalid-clojure-syntax :message (or (.getMessage ex) (str ex))}))]
       (if-not (:ok? parsed)
         (assoc parsed :ns nsym)
         (let [fv (:val parsed)
               src (if (and (string? form-src) (not (str/includes? raw-src "\n")) (>= (count raw-src) 200))
                     (fmt-form fv)
                     raw-src)
               fhead (when (and (seq? fv) (symbol? (first fv))) (symbol (name (first fv))))
               vname (when (and (seq? fv) (symbol? (second fv))) (symbol (name nsym) (name (second fv))))
               sch (extract-defn-malli-schema fv)
               db0 @conn
               sch-err (when (some? sch) (validate-defn-malli-schema db0 (or vname nsym) (str (or vname nsym)) fv sch))]
           (if sch-err
             sch-err
             (let [existing-ns-src (:seon.code.form/src (d/pull db0 '[:seon.code.form/src] [:seon.code.form/key (str nsym "#ns")]))
                   seed-ns-src (when (and (str/ends-with? (name nsym) "-test") (not= fhead 'ns) (nil? existing-ns-src))
                                 (str "(ns " nsym "\n  (:require [clojure.test :refer [deftest is testing]]))"))
                   _ (when (and eval? (not= fhead 'ns) (nil? (find-ns nsym)))
                       (when-let [ns-s (or seed-ns-src existing-ns-src)]
                         (analyze/eval-form-with-account! db0 nsym ns-s true)))
                   ev-res (when eval? (analyze/eval-form-with-account! db0 nsym src (= fhead 'ns)))
                   eval-err (:error ev-res)
                   ir-map (if (and vname (:account ev-res)) {vname (:account ev-res)} {})
                   srcs (if seed-ns-src [seed-ns-src src] [src])
                   tx (code/persist-forms-by-ns db0 {nsym srcs} ir-map)]
               (if (empty? tx)
                 {:ok? false :error :nothing-to-persist :ns nsym}
                 (let [tx-meta {:seon.tx/agent (or (:agent-id *agent-ctx*) :repl)
                                :seon.tx/branch (or (:branch *agent-ctx*) :main)
                                :seon.tx/reason (str "add-fn! " (or vname nsym))}
                       r (binding [store/*tx-meta* tx-meta] (d/transact conn {:tx-data tx}))
                       _ (reorder-ns-forms! nsym)
                       db-now @conn]
                   (ctx/refresh-active! db-now (:tx-data r))
                   (cond-> {:ok? (nil? eval-err) :ns nsym :var vname :head fhead :tx (:max-tx db-now)}
                     seed-ns-src (assoc :seeded-ns? true)
                     eval-err (assoc :eval-error eval-err))))))))))))