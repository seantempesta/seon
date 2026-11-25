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
            [seon.store :as store]
            [seon.code.cst :as cst]
            [seon.code.cst.ns :as cst-ns]
            [malli.core :as m]
            [seon.schema :as schema]
            [seon.code.analyze :as analyze]
            [seon.code.io :as cio]
            [seon.code.edit.ns :as edit-ns]))

(defn- extract-defn-malli-schema [& args] (apply cst-ns/extract-defn-malli-schema args))

(defn- resolve-form-target [& args] (apply edit-ns/resolve-form-target args))

(defn- single-form-upsert-tx
"O(1-form) delta persistence for non-`#ns` edits."
([db nsym fkey path order lead new-fsrc] (single-form-upsert-tx db nsym fkey path order lead new-fsrc nil nil))
([db nsym fkey path order lead new-fsrc acct] (single-form-upsert-tx db nsym fkey path order lead new-fsrc acct nil))
([db nsym fkey path order lead new-fsrc acct ns-ctx]
 (let [ns-src (or (:ns-src ns-ctx) (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key (str nsym "#ns")])))
       local-defs (or (:local-defs ns-ctx) (into #{} (map #(symbol (name (:seon.code.var/name %)))) (code/ns-vars db nsym)))
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

(defn ^:seon.flow/edge ^{:seon.code/reexport 'seon.code.edit.ns/unused-requires} unused-requires [& args] (apply edit-ns/unused-requires args))

(defn ^{:seon.code/reexport 'seon.code.edit.ns/prune-requires!} prune-requires! [& args] (apply edit-ns/prune-requires! args))

(defn ^{:seon.code/reexport 'seon.code.edit.ns/diff} diff [& args] (apply edit-ns/diff args))

(defn ^{:seon.code/reexport 'seon.code.edit.ns/add-require!} add-require! [& args] (apply edit-ns/add-require! args))

(defn ^{:seon.code/reexport 'seon.code.edit.ns/ns-requires} ns-requires [& args] (apply edit-ns/ns-requires args))

(defn ^{:seon.code/reexport 'seon.code.edit.ns/reorder-ns-forms!} reorder-ns-forms! [& args] (apply edit-ns/reorder-ns-forms! args))

(defn- fn-schema-arity [& args] (apply cst-ns/fn-schema-arity args))

(defn- check-examples-against-arities [& args] (apply cst-ns/check-examples-against-arities args))

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
    (let [ed (ex-data ex)
          bad-type (or (get-in ed [:data :type]) (get-in ed [:data :schema]))
          type-tip (when bad-type
                     (str "Failing sub-schema: " (pr-str bad-type)
                          (case bad-type
                            :set " (bare :set requires a child schema — use [:set :any] or predicate set?). "
                            :vector " (bare :vector requires a child schema — use [:vector :any] or predicate vector?). "
                            :sequential " (use [:sequential :any] or sequential?). "
                            :map-of " (use [:map-of :keyword :any]). "
                            " ")))]
      {:ok? false :error :invalid-malli-schema :target target :key key :schema sch
       :message (or (.getMessage ex) (str ex))
       :ex-data ed
       :hint (str type-tip "Predicates are symbols (number?, set?); built-ins: :int, :string, :boolean, :keyword, :symbol, :map, :any, [:vector :any], [:set :any], :seon.host/db.")}))))

(defn add-fn!
"Adds or replaces one or more top-level forms in `ns-sym`, validates `:malli/schema`, live-evals every form, and persists to Datahike."
{:malli/schema [:function [:=> [:cat :any :any] [:maybe :map]] [:=> [:cat :any :any :map] [:maybe :map]]]
 :seon.eval/budget-override {:ticks 300000000 :bytes 536870912}}
([ns-sym form-src] (add-fn! ns-sym form-src {}))
([ns-sym form-src {:keys [eval?] :or {eval? true}}]
 (when-let [{:keys [conn]} *agent-ctx*]
   (let [nsym (cond (and (symbol? ns-sym) (namespace ns-sym)) (symbol (namespace ns-sym)) (symbol? ns-sym) ns-sym :else (symbol (name ns-sym)))
         raw-src (if (string? form-src)
                   (str/trim form-src)
                   (binding [*print-meta* true *print-namespace-maps* false] (str/trim (with-out-str (pp/write (cst-ns/strip-reader-meta form-src) :dispatch pp/code-dispatch)))))]
     (if (str/blank? raw-src)
       {:ok? false :error :empty-form :ns nsym}
       (let [db-pre @conn
             _ (when (and eval? (nil? (find-ns nsym)) (not (str/starts-with? raw-src "(ns ")))
                 (when-let [pre-ns (:seon.code.form/src (d/pull db-pre [:seon.code.form/src] [:seon.code.form/key (str nsym "#ns")]))]
                   (analyze/eval-form-with-account! db-pre nsym pre-ns true)))
             parsed (try
                      (binding [*ns* (or (find-ns nsym) *ns*)]
                        (let [pbr (java.io.PushbackReader. (java.io.StringReader. raw-src))
                              eof (Object.)
                              fvs (loop [acc []] (let [x (read {:eof eof} pbr)] (if (identical? x eof) acc (recur (conj acc x)))))]
                          (if (and (seq fvs) (every? seq? fvs))
                            {:ok? true :vals fvs}
                            {:ok? false :error :expected-list-form :message "add-fn! expects top-level list form(s) like (defn ...)"})))
                      (catch Throwable ex {:ok? false :error :invalid-clojure-syntax :message (or (.getMessage ex) (str ex))}))]
         (if-not (:ok? parsed)
           (assoc parsed :ns nsym)
           (let [fvs (:vals parsed)
                 fv (first fvs)
                 multi? (> (count fvs) 1)
                 src (if (and (not multi?) (string? form-src) (not (str/includes? raw-src "\n")) (>= (count raw-src) 200))
                       (str/trim (with-out-str (pp/write fv :dispatch pp/code-dispatch))) raw-src)
                 fhead (when (symbol? (first fv)) (symbol (name (first fv))))
                 vnames (vec (keep #(when (symbol? (second %)) (symbol (name nsym) (name (second %)))) fvs))
                 vname (first vnames)
                 db0 @conn
                 sch-err (or (when (and (str/ends-with? (name nsym) "-test")
                                    (re-find #"\(user/(?:db|main-db|conn)\b" raw-src)
                                    (not (str/includes? raw-src "*agent-ctx*")))
                           {:ok? false :error :repl-helper-in-test-ns :ns nsym
                            :hint "Test namespaces run in isolated COW workers where user/db, user/main-db, and user/conn do not exist. Use (seon.test-fixture/with-test-store [{:keys [conn]}] @conn) instead."})
                         (some #(when-let [s (extract-defn-malli-schema %)]
                                  (let [vn (when (symbol? (second %)) (symbol (name nsym) (name (second %))))]
                                    (validate-defn-malli-schema db0 (or vn nsym) (str (or vn nsym)) % s))) fvs))]
             (if sch-err
               sch-err
               (let [existing-ns-src (:seon.code.form/src (d/pull db0 (quote [:seon.code.form/src]) [:seon.code.form/key (str nsym "#ns")]))
                     seed-ns-src (when (and (str/ends-with? (name nsym) "-test") (not= fhead (quote ns)) (nil? existing-ns-src))
                                   (str "(ns " nsym "\n  (:require [clojure.test :refer [deftest is testing]]))"))
                     _ (when (and eval? (not= fhead (quote ns)) (nil? (find-ns nsym)))
                         (when-let [ns-s (or seed-ns-src existing-ns-src)] (analyze/eval-form-with-account! db0 nsym ns-s true)))
                     ev-parts (when eval?
                                (mapv #(let [fh (when (symbol? (first %)) (symbol (name (first %))))
                                             vn (when (symbol? (second %)) (symbol (name nsym) (name (second %))))]
                                         (assoc (analyze/eval-form-with-account! db0 nsym (if multi? (pr-str %) src) (= fh (quote ns))) :var vn))
                                      fvs))
                     eval-err (some :error ev-parts)
                     ir-map (into {} (keep #(when (and (:var %) (:account %)) [(:var %) (:account %)])) ev-parts)
                     tx (code/persist-forms-by-ns db0 {nsym (if seed-ns-src [seed-ns-src src] [src])} ir-map)]
                 (if (empty? tx)
                   {:ok? false :error :nothing-to-persist :ns nsym}
                   (let [r (binding [store/*tx-meta* {:seon.tx/agent (or (:agent-id *agent-ctx*) :repl) :seon.tx/branch (or (:branch *agent-ctx*) :main) :seon.tx/reason (str "add-fn! " (or vname nsym))}]
                             (d/transact conn {:tx-data tx}))
                         _ (reorder-ns-forms! nsym)
                         db-now @conn]
                     (ctx/refresh-active! db-now (:tx-data r))
                     (cond-> {:ok? (nil? eval-err) :ns nsym :var vname :head fhead :tx (:max-tx db-now)}
                       multi? (assoc :vars vnames)
                       seed-ns-src (assoc :seeded-ns? true)
                       eval-err (assoc :eval-error eval-err))))))))))))))

(defn edit-fn!
  "Structurally replaces `old-str` with `new-str` inside `'ns/fn` or `'ns` (whitespace/comment-insensitive
   via `seon.code.cst`), persists to Datahike, and live-evaluates in the target namespace.
   Also supports batched multi-edit in ONE parse + compile:
     `(edit-fn! 'ns/fn [[old1 new1] [old2 new2]])` or `(edit-fn! 'ns/fn [[old1 new1] ...] opts)`."
  {:malli/schema [:function
                  [:=> [:cat :any :any] [:maybe :map]]
                  [:=> [:cat :any :any :any] [:maybe :map]]
                  [:=> [:cat :any :any :any :map] [:maybe :map]]]
   :seon.eval/budget-override {:ticks 300000000, :bytes 536870912}}
  ([target edits]
  (cond
    (and (symbol? target) (namespace target)
         (or (and (seq? edits) (symbol? (first edits)) (str/starts-with? (name (first edits)) "def"))
             (and (string? edits) (boolean (re-find #"^\s*\(\s*(?:[\w.-]+/)?def" edits)))))
    ((requiring-resolve 'seon.code.edit/add-fn!) (symbol (namespace target)) edits)

    (and (vector? edits) (= 2 (count edits)) (not (vector? (first edits))))
    (edit-fn! target (first edits) (second edits) {})

    :else
    (edit-fn! target edits {})))
  ([target a b]
   (cond
     (and (vector? a) (seq a) (vector? (first a)) (map? b)) (edit-fn! target a nil b)
     (and (vector? a) (= 2 (count a)) (not (vector? (first a))) (map? b)) (edit-fn! target (first a) (second a) b)
     :else (edit-fn! target a b {})))
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
                       (cond-> (assoc raw-patched :target target :key key)
                         (and (str/ends-with? key "#ns") (= :no-match (:error raw-patched)))
                         (assoc :hint "Target 'ns edits ONLY the (ns ...) header form. To edit a function, pass 'ns/fn; to add or replace a full defn, use (user/add-fn! 'ns \"(defn ...)\").")))]
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
                          (let [file-src (cio/merged-ns-src conn branch ns)
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

(defn- prep-meta-edit
  {:seon.flow/edge true}
  [conn db branch target meta-map]
  (let [{:keys [ns key path src order lead]} (resolve-form-target conn branch target)]
    (if (str/blank? (str src))
      {:ok? false :error :form-not-found :target target :key key}
      (if-let [se (when (contains? meta-map :malli/schema)
                    (or (when-let [bad-fn (some #(when (fn? %) %) (tree-seq coll? seq (:malli/schema meta-map)))]
                      {:ok? false :error :unquoted-fn-in-schema :target target :key key
                       :hint (str "Quote :malli/schema so predicates stay symbols, not fn " (pr-str bad-fn) ".")})
                    (validate-defn-malli-schema db target key
                      (try (binding [*ns* (or (find-ns ns) *ns*)] (read-string src)) (catch Throwable _ nil))
                      (:malli/schema meta-map))))]
        se
        (let [res (cst-ns/set-defn-meta-src src meta-map)]
          (if-not (:ok? res)
            (assoc res :target target :key key)
            {:ok? true :ns ns :key key :path path :order order :lead lead :old-src src :src (:src res) :action (:action res)}))))))

(defn- apply-live-var-meta! [db0 k m]
(when-let [v (and (string? k) (str/includes? k "/") (find-var (symbol k)))]
  (letfn [(unquote-ex [node]
            (cond
              (and (seq? node) (= 2 (count node)) (= 'quote (first node))) (second node)
              (map? node) (reduce-kv (fn [a mk mv] (assoc a mk (unquote-ex mv))) {} node)
              (vector? node) (mapv unquote-ex node)
              :else node))
          (norm-m [node]
            (cond
              (map? node) (reduce-kv (fn [a mk mv] (assoc a mk (if (contains? #{:examples :malli/examples} mk) (unquote-ex mv) (norm-m mv)))) {} node)
              (vector? node) (mapv norm-m node)
              :else node))]
    (let [m' (if (or (contains? m :malli/schema) (contains? m :malli/examples)) (norm-m m) m)]
      (alter-meta! v merge m')
      (when (contains? m' :malli/schema) (schema/arm-var! db0 v))
      {:account nil}))))

(defn set-meta!
  "Merges metadata into `(defn ...)` attr-map(s), validating `:malli/schema` upfront."
  {:seon.eval/budget-override {:ticks 300000000 :bytes 536870912}
   :malli/schema [:function
                  [:=> [:cat :any] :map]
                  [:=> [:cat :any :map] [:maybe :map]]
                  [:=> [:cat :any :map :map] [:maybe :map]]]}
  ([target-or-batch]
   (cond
     (not (map? target-or-batch))
     {:ok? false :error :invalid-args :hint "Call (user/set-meta! 'ns/fn {...}) or (user/set-meta! {'ns/f1 {...} ...})."}

     (and (contains? target-or-batch :target) (map? (:meta target-or-batch)))
     (set-meta! (:target target-or-batch) (:meta target-or-batch) (dissoc target-or-batch :target :meta))

     (not (every? map? (vals target-or-batch)))
     {:ok? false :error :invalid-batch-map :hint "Use {'ns/f1 {...} ...} or (user/set-meta! 'ns/fn {...})."}

     (nil? *agent-ctx*)
     {:ok? false :error :no-agent-ctx}

     :else
     (let [{:keys [conn branch]} *agent-ctx*
           db0 @conn
           grouped (reduce (fn [acc [target meta-map]]
                             (let [k (or (:key (resolve-form-target conn branch target)) target)]
                               (update acc k (fn [prev]
                                               {:targets (conj (or (:targets prev) []) target)
                                                :target (or (:target prev) target)
                                                :meta (merge (or (:meta prev) {}) meta-map)}))))
                           {} target-or-batch)
           ns-ctxs (volatile! {})
           get-ns-ctx #(or (@ns-ctxs %)
                           (let [c {:ns-src (:seon.code.form/src (d/pull db0 '[:seon.code.form/src] [:seon.code.form/key (str % "#ns")]))
                                    :local-defs (into #{} (map (comp symbol name :seon.code.var/name)) (code/ns-vars db0 %))}]
                             (vswap! ns-ctxs assoc % c) c))
           step (reduce
                 (fn [{:keys [tx results ok-n]} [_ {:keys [target targets meta]}]]
                   (let [p (prep-meta-edit conn db0 branch target meta)]
                     (if-not (:ok? p)
                       {:tx tx :results (reduce #(assoc %1 %2 (assoc p :target %2)) results targets) :ok-n ok-n}
                       (let [{:keys [ns key path order lead src action]} p
                             ev (or (apply-live-var-meta! db0 key meta)
                                    (analyze/eval-form-with-account! db0 ns src false))
                             err (:error ev)
                             r-one (cond-> {:ok? (nil? err) :target target :key key :action action} err (assoc :eval-error err))]
                         {:tx (into tx (single-form-upsert-tx db0 ns key path order lead src (:account ev) (get-ns-ctx ns)))
                          :results (reduce #(assoc %1 %2 (assoc r-one :target %2)) results targets)
                          :ok-n (if (nil? err) (+ ok-n (count targets)) ok-n)}))))
                 {:tx [] :results {} :ok-n 0}
                 grouped)
           r (when (seq (:tx step))
               (binding [store/*tx-meta* {:seon.tx/agent (or (:agent-id *agent-ctx*) :repl) :seon.tx/branch branch :seon.tx/reason (str "set-meta! batch (" (count grouped) ")")}]
                 (d/transact conn {:tx-data (:tx step)})))]
       (when r (ctx/refresh-active! (:db-after r) (:tx-data r)))
       {:ok? (every? :ok? (vals (:results step))) :updated (:ok-n step) :total (count target-or-batch) :tx (when r (:max-tx (:db-after r))) :results (:results step)})))
  ([target meta-map] (set-meta! target meta-map {}))
  ([target meta-map {:keys [dry-run? eval?] :or {eval? true}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [db0 @conn
           p (prep-meta-edit conn db0 branch target meta-map)]
       (if-not (:ok? p)
         p
         (let [{:keys [ns key path order lead old-src src action]} p
               udiff (cst/unified-diff old-src src key)]
           (if dry-run?
             {:ok? true :dry-run? true :target target :key key :action action :diff udiff}
             (let [ev (if eval? (analyze/eval-form-with-account! db0 ns src false)
                          {:account (analyze/analyze-form-account ns src false) :error nil})
                   err (:error ev)
                   tx (single-form-upsert-tx db0 ns key path order lead src (:account ev))
                   r (binding [store/*tx-meta* {:seon.tx/agent (or (:agent-id *agent-ctx*) :repl) :seon.tx/branch branch :seon.tx/reason (str "set-meta! " target)}]
                       (d/transact conn {:tx-data tx}))]
               (ctx/refresh-active! (:db-after r) (:tx-data r))
               (cond-> {:ok? (nil? err) :target target :key key :action action :tx (:max-tx (:db-after r)) :diff udiff}
                 err (assoc :eval-error err))))))))))