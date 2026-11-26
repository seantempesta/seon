(ns seon.code.cst.meta
  "Pure CST topological form ordering, Malli defn schema extraction, and attr-map editing."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]
            [malli.core :as m]))

(defn- form-ns-name [nsym k]
  (cond
    (symbol? nsym) (name nsym)
    (string? k) (let [i (.indexOf ^String k "/")
                      j (.indexOf ^String k "#")]
                  (cond (pos? i) (subs k 0 i) (pos? j) (subs k 0 j) :else nil))
    :else nil))

(defn- kahn-order-body [nsym body key-fn deps-fn src-fn strict-acyclic?]
  (let [n (count body)]
    (if (< n 2)
      (vec body)
      (let [idx-by-key (into {} (map-indexed (fn [i f] [(key-fn f) i])) body)
            by-key (into {} (map (juxt key-fn identity)) body)
            decl-by-sym (reduce-kv
                         (fn [acc i f]
                           (let [k (key-fn f)
                                 s (when-let [rs (src-fn f)] (str/triml (str rs)))
                                 ns-s (form-ns-name nsym k)]
                             (if (and ns-s s (str/starts-with? s "(declare "))
                             (let [nd (try (p/parse-string s) (catch clojure.lang.ExceptionInfo _ nil))
                                   syms (when (and nd (= :list (n/tag nd)))
                                          (keep #(when (= :token (n/tag %))
                                                   (let [ts (n/string %)]
                                                     (when (and (not= ts "declare") (not (str/starts-with? ts ":")))
                                                       (symbol ts))))
                                                (rest (remove n/whitespace-or-comment? (n/children nd)))))]
                               (reduce (fn [m sym]
                                         (let [qs (symbol ns-s (name sym))]
                                           (if (contains? m qs) m (assoc m qs {:key k :idx i}))))
                                       acc syms))
                             acc)))
                         {} (vec body))
            sym->key (reduce
                      (fn [acc f]
                        (let [k (key-fn f)
                              ns-s (form-ns-name nsym k)
                              s (when-let [rs (src-fn f)] (str/triml (str rs)))
                              acc1 (if (and (string? k) (str/includes? k "/") (not (str/includes? k "#")))
                                     (assoc acc (symbol k) k)
                                     acc)]
                          (if (and ns-s s (or (str/starts-with? s "(defrecord ") (str/starts-with? s "(deftype ")))
                            (if-let [[_ kind tname] (re-find #"^\((defrecord|deftype)\s+([A-Za-z0-9_?!-]+)" s)]
                              (cond-> (assoc acc1 (symbol ns-s (str "->" tname)) k)
                                (= kind "defrecord") (assoc (symbol ns-s (str "map->" tname)) k))
                              acc1)
                            acc1)))
                      {} body)
            edges (reduce-kv
                   (fn [acc fi f]
                     (let [ck (key-fn f)
                           raw-deps (deps-fn f)
                           deps (when (coll? raw-deps) raw-deps)]
                       (reduce
                        (fn [a d]
                          (let [decl (get decl-by-sym d)
                                dk (cond
                                     (and decl (< (:idx decl) fi)) (:key decl)
                                     :else (get sym->key d))]
                            (if (and dk (not= dk ck))
                              (update a dk (fnil conj #{}) ck)
                              a)))
                        acc deps)))
                   {} (vec body))
            init-indeg (reduce
                        (fn [acc [_ cs]]
                          (reduce (fn [a c] (update a c (fnil inc 0))) acc cs))
                        (into {} (map (fn [f] [(key-fn f) 0])) body)
                        edges)
            ordered-keys
            (loop [ready (vec (sort-by #(get idx-by-key % 0) (keep (fn [[k d]] (when (zero? d) k)) init-indeg)))
                   indeg init-indeg
                   out []]
              (if (empty? ready)
                (if strict-acyclic?
                  (when (= (count out) (count idx-by-key)) out)
                  (into out (sort-by #(get idx-by-key % 0) (keys indeg))))
                (let [nxt (first ready)
                      rest-r (subvec ready 1)
                      indeg1 (dissoc indeg nxt)
                      [indeg2 newly]
                      (reduce (fn [[m nr] c]
                                (if (contains? m c)
                                  (let [nd (dec (long (get m c 0)))]
                                    [(assoc m c nd) (if (zero? nd) (conj nr c) nr)])
                                  [m nr]))
                              [indeg1 []]
                              (get edges nxt))
                      next-ready (if (seq newly) (vec (sort-by #(get idx-by-key % 0) (into rest-r newly))) rest-r)]
                  (recur next-ready indeg2 (conj out nxt)))))]
        (when ordered-keys (mapv by-key ordered-keys))))))

(defn topo-order-ns-forms
  "Given a sequence of `:seon.code.form/*` maps for one namespace, returns the minimal vector of
   `{:seon.code.form/key k :seon.code.form/order new-idx}` updates needed to place every same-ns
   callee before its callers (respecting `declare`/`defrecord`/`deftype`, keeping `#ns` at order 0,
   and preserving original order on ties/cycles)."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]
   :cost/ticks 4000000
   :cost/bytes 4194304}
  [forms]
  (let [safe-ord (fn [f] (let [o (:seon.code.form/order f)] (if (number? o) (long o) 0)))
        valid (vec (sort-by safe-ord (filter #(string? (:seon.code.form/key %)) forms)))
        n (count valid)]
    (if (< n 2)
      []
      (let [ns-hdrs (filterv #(str/ends-with? (:seon.code.form/key %) "#ns") valid)
            body (filterv #(not (str/ends-with? (:seon.code.form/key %) "#ns")) valid)
            ordered-body (kahn-order-body nil body :seon.code.form/key :seon.code.form/deps :seon.code.form/src false)
            final-forms (into (vec ns-hdrs) ordered-body)]
        (vec (keep-indexed
              (fn [i f]
                (let [old-o (:seon.code.form/order f)]
                  (when (or (not (number? old-o)) (not= (long i) (long old-o)))
                    {:seon.code.form/key (:seon.code.form/key f)
                     :seon.code.form/order (long i)})))
              final-forms))))))

(defn topo-sort-ns-forms
  "Deterministic Kahn topological sort of non-`#ns` forms in `nsym` by same-ns `:deps` (respecting
   `declare`/`defrecord`/`deftype` and preserving original order as tie-breaker). Returns ordered vec if acyclic, or `nil` on cycle."
  {:malli/schema [:=> [:cat :symbol [:sequential :map]] [:maybe [:vector :map]]]
   :cost/ticks 5000000}
  [nsym body-forms]
  (let [indexed (mapv (fn [i f] (if (string? (:key f)) f (assoc f ::ord-key (str "#idx#" i))))
                      (range) body-forms)
        key-fn #(or (when (string? (:key %)) (:key %)) (::ord-key %))
        ordered (kahn-order-body nsym indexed key-fn :deps :src true)]
    (when ordered (mapv #(dissoc % ::ord-key) ordered))))

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
                (let [old-res (try {:ok? true :val (read-string (n/string (:node existing-attr)))}
                                 (catch Throwable ex {:ok? false :error :unreadable-attr-map
                                                      :message (or (.getMessage ex) (str ex))}))]
                (if-not (and (:ok? old-res) (map? (:val old-res)))
                  {:ok? false :error :unreadable-attr-map
                   :message (or (:message old-res) "Existing defn attr-map could not be parsed as a map")}
                  (let [merged (quote-examples-for-src (merge (:val old-res) meta-map))
                        new-map-nd (p/parse-string (binding [*print-namespace-maps* false] (pr-str merged)))
                        updated (assoc raw (:i existing-attr) new-map-nd)
                        z' (z/replace fn-loc (n/replace-children fn-nd updated))]
                    {:ok? true :action :merged :src (z/root-string z')})))
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