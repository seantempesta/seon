(ns seon.code.cst.meta
  "Pure CST topological form ordering, Malli defn schema extraction, and attr-map editing."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]
            [malli.core :as m]))

(defn topo-order-ns-forms
  "Given a sequence of `:seon.code.form/*` maps for one namespace, returns the minimal vector of\n       `{:seon.code.form/key k :seon.code.form/order new-idx}` updates needed to place every same-ns\n       callee before its callers (keeping `#ns` at order 0 and preserving original order on ties/cycles)."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]],
   :cost/ticks 2000000,
   :cost/bytes 4194304}
  [forms]
  (let [valid (vec
                (sort-by
                  #(long (or (:seon.code.form/order %) 0))
                  (filter #(string? (:seon.code.form/key %)) forms)))
        n (count valid)]
    (if (< n 2)
      []
      (let [ns-hdrs (filterv
                      #(str/ends-with? (:seon.code.form/key %) "#ns")
                      valid)
            body (filterv
                   #(not
                     (str/ends-with? (:seon.code.form/key %) "#ns"))
                   valid)
            idx-by-key (into
                         {}
                         (map-indexed
                           (fn [i f] [(:seon.code.form/key f) i])
                           body))
            sym->key (into
                       {}
                       (keep
                         (fn [f]
                           (let [k (:seon.code.form/key f)]
                             (when (str/includes? k "/")
                               [(symbol k) k]))))
                       body)
            by-key (into
                     {}
                     (map (juxt :seon.code.form/key identity))
                     body)
            edges (reduce
                    (fn [acc f]
                      (let [ck (:seon.code.form/key f)
                            raw-deps (:seon.code.form/deps f)
                            deps (if (coll? raw-deps) raw-deps nil)]
                        (reduce
                          (fn [a d]
                            (if-let [dk (get sym->key d)]
                              (if (not= dk ck)
                                (update a dk (fnil conj #{}) ck)
                                a)
                              a))
                          acc
                          deps)))
                    {}
                    body)
            init-indeg (reduce
                         (fn [acc [_ cs]]
                           (reduce
                             (fn [a c] (update a c (fnil inc 0)))
                             acc
                             cs))
                         (into
                           {}
                           (map (fn [f] [(:seon.code.form/key f) 0]))
                           body)
                         edges)
            ordered-keys (loop [indeg init-indeg out []]
                           (let [ready
                                 (sort-by
                                   #(get idx-by-key % 0)
                                   (keep
                                     (fn [[k d]] (when (zero? d) k))
                                     indeg))]
                             (if-let [nxt (first ready)]
                               (let 
                                 [indeg1 (dissoc indeg nxt)
                                  indeg2
                                  (reduce
                                    (fn 
                                      [m c]
                                      (if
                                        (contains? m c)
                                        (update m c dec)
                                        m))
                                    indeg1
                                    (get edges nxt))]
                                 (recur indeg2 (conj out nxt)))
                               (into
                                 out
                                 (sort-by
                                   #(get idx-by-key % 0)
                                   (keys indeg))))))
            final-forms (into (vec ns-hdrs) (map by-key ordered-keys))]
        (vec
          (keep-indexed
            (fn [i f]
              (when (not=
                      (long i)
                      (long (or (:seon.code.form/order f) -1)))
                {:seon.code.form/key (:seon.code.form/key f),
                 :seon.code.form/order (long i)}))
            final-forms))))))

(defn topo-sort-ns-forms
  "Deterministic Kahn topological sort of non-`#ns` forms in `nsym` by same-ns `:deps`, preserving `:order` as tie-breaker. Returns ordered vec if acyclic, or `nil` on cycle."
  {:malli/schema [:=> [:cat :symbol [:sequential :map]] [:maybe [:vector :map]]]
   :cost/ticks 5000000}
  [_nsym body-forms]
  (let [by-key (into {} (map (juxt :key identity)) body-forms)
        sym->key (into {} (keep (fn [{:keys [key]}] (when (and key (not (str/includes? key "#"))) [(symbol key) key]))) body-forms)
        in-deg (volatile! (into {} (map (fn [f] [(:key f) 0])) body-forms))
        rdeps  (volatile! {})
        _ (doseq [{:keys [key deps]} body-forms]
            (let [local-deps (->> deps (keep #(get sym->key %)) (remove #(= % key)) distinct)]
              (doseq [dk local-deps]
                (vswap! in-deg update key (fnil inc 0))
                (vswap! rdeps update dk (fnil conj []) key))))
        orig-ord (into {} (map-indexed (fn [i f] [(:key f) i])) body-forms)]
    (loop [ready (vec (sort-by orig-ord (keep (fn [[k d]] (when (zero? d) k)) @in-deg)))
           deg @in-deg
           out []]
      (if (empty? ready)
        (when (= (count out) (count body-forms)) (mapv by-key out))
        (let [cur (first ready) rest-r (subvec ready 1) kids (get @rdeps cur)
              [deg' newly] (reduce (fn [[dm nr] k]
                                     (let [nd (dec (get dm k 0))]
                                       [(assoc dm k nd) (if (zero? nd) (conj nr k) nr)]))
                                   [deg []] kids)
              next-ready (if (seq newly) (vec (sort-by orig-ord (into rest-r newly))) rest-r)]
          (recur next-ready deg' (conj out cur)))))))

(defn extract-defn-malli-schema
  "Extracts `:malli/schema` from a parsed `(defn ...)` / `(defn- ...)` form (var metadata or attr-map)."
  {:malli/schema [:=> [:cat :any] :any], :cost/ticks 500000}
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

(defn fn-schema-arity
  {:malli/schema [:=> [:cat :any] :any], :cost/ticks 500000}
  [fs]
  (when (m/schema? fs)
  (let [in (first (m/children fs))
        it (when in (m/type in))
        ch (when in (m/children in))]
    (when (= it :cat)
      (if (some #(contains? #{:* :? :+} (m/type %)) ch) :variadic (count ch))))))

(defn check-examples-against-arities
  {:malli/schema [:=> [:cat :any :any :any :any :any] :any], :cost/ticks 1000000}
  [exs arities target key sch]
  (when (some? exs)
    (let [arity-set (cond (set? arities) arities
                          (coll? arities) (set arities)
                          (some? arities) #{arities}
                          :else #{})]
      (if-not (and (coll? exs) (not (map? exs)))
        {:ok? false :error :invalid-malli-examples :target target :key key :schema sch
         :hint ":examples must be a vector of [[arg1 ...] expected-out] pairs."}
        (some (fn [ex]
                (cond
                  (not (and (vector? ex) (= 2 (count ex)) (vector? (first ex))))
                  {:ok? false :error :invalid-malli-examples :target target :key key :example ex :expected-arities arity-set
                   :hint (str "Each :examples entry must be [[arg1 ...] expected-out] (e.g. [[] out] for 0-arg, [[x] out] for 1-arg). Got: " (pr-str ex))}

                  (and (seq arity-set) (not (contains? arity-set :variadic)) (not (contains? arity-set (count (first ex)))))
                  {:ok? false :error :malli-examples-arity-mismatch :target target :key key :example ex
                   :example-arity (count (first ex)) :expected-arities arity-set
                   :hint (str "Example args " (pr-str (first ex)) " has arity " (count (first ex))
                              " but schema expects arity " arity-set
                              " (0-arg: [[] out]; 1-arg taking a vector/seq [a b]: [[[a b]] out] — outer vector is the arg list!).")}))
              exs)))))

(defn- quote-examples-for-src [x]
  (letfn [(has-sym? [v] (boolean (some symbol? (tree-seq coll? seq v))))
          (quoted? [v] (and (seq? v) (= 2 (count v)) (= 'quote (first v))))
          (walk [node]
            (cond
              (map? node)
              (reduce-kv (fn [a k v]
                           (assoc a k
                                  (if (and (contains? #{:examples :malli/examples} k)
                                           (coll? v) (not (quoted? v)) (has-sym? v))
                                    (list 'quote v)
                                    (walk v))))
                         {} node)
              (vector? node) (mapv walk node)
              :else node))]
    (if (map? x)
      (reduce-kv (fn [a k v]
                   (assoc a k (if (contains? #{:malli/schema :malli/examples} k) (walk v) v)))
                 {} x)
      x)))

(defn set-defn-meta-src
  "Structurally inserts or merges `meta-map` into a `(defn ...)` / `(defn- ...)` attr-map,
   ALWAYS placed right after `name` + optional `docstring` (valid Clojure syntax) while preserving
   100% of original indentation, comments, `^Type` hints, and multi-arity formatting."
  {:malli/schema [:function [:=> [:cat :string :map] :map]]}
  [form-src meta-map]
  (if-not (and (map? meta-map) (seq meta-map))
    {:ok? false :error :invalid-meta-map :hint "Pass a non-empty map, e.g. {:malli/schema [...] :cost/ticks 500000}."}
    (let [root (p/parse-string-all (str form-src))
          z0 (z/edn root)
          fn-loc (loop [loc z0]
                   (cond
                     (z/end? loc) nil
                     (= :list (n/tag (z/node loc))) loc
                     :else (recur (z/next loc))))]
      (if-not fn-loc
        {:ok? false :error :not-a-defn-form}
        (let [fn-nd (z/node fn-loc)
              raw (vec (n/children fn-nd))
              non-ws (vec (keep-indexed (fn [i ch]
                                          (when-not (n/whitespace-or-comment? ch)
                                            {:i i :node ch :tag (n/tag ch)}))
                                        raw))
              op-s (some-> (first non-ws) :node n/string)]
          (if-not (#{"defn" "defn-"} op-s)
            {:ok? false :error :not-a-defn-form :op op-s}
            (let [str-node? (fn [{:keys [tag node]}]
                              (or (= tag :multi-line)
                                  (and (= tag :token) (str/starts-with? (n/string node) "\""))))
                  has-doc? (and (> (count non-ws) 3) (str-node? (nth non-ws 2)))
                  attr-slot (if has-doc? 3 2)
                  existing-attr (when (and (< attr-slot (count non-ws))
                                           (= :map (:tag (nth non-ws attr-slot))))
                                  (nth non-ws attr-slot))]
              (if existing-attr
                (let [old-map (try (read-string (n/string (:node existing-attr))) (catch Throwable _ {}))
                      merged (quote-examples-for-src (merge old-map meta-map))
                      new-map-nd (p/parse-string (binding [*print-namespace-maps* false] (pr-str merged)))
                      updated (assoc raw (:i existing-attr) new-map-nd)
                      z' (z/replace fn-loc (n/replace-children fn-nd updated))]
                  {:ok? true :action :merged :src (z/root-string z')})
                (let [anchor-idx (:i (nth non-ws (dec attr-slot)))
                      next-raw (subvec raw (inc anchor-idx))
                      starts-nl? (and (seq next-raw) (= :newline (n/tag (first next-raw))))
                      trimmed-rest (if starts-nl? next-raw (vec (drop-while n/whitespace? next-raw)))
                      map-str (binding [*print-namespace-maps* false] (pr-str (quote-examples-for-src meta-map)))
                      ins (cond-> [(n/newline-node "\n") (n/spaces 2) (p/parse-string map-str)]
                            (not starts-nl?) (into [(n/newline-node "\n") (n/spaces 2)]))
                      updated (vec (concat (subvec raw 0 (inc anchor-idx)) ins trimmed-rest))
                      z' (z/replace fn-loc (n/replace-children fn-nd updated))]
                  {:ok? true :action :inserted :src (z/root-string z')})))))))))