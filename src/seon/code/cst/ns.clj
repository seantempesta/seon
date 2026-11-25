(ns seon.code.cst.ns
  "Pure rewrite-clj CST analysis & transforms for `:require` clauses and `defn` attr-maps."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]
            [seon.code.cst :as cst]
            [malli.core :as m]))

(defn collect-body-refs
  {:malli/schema [:function [:=> [:cat [:maybe [:sequential :any]]] [:map [:qual-ns [:set :string]] [:unqual [:set :string]] [:raw-toks [:set :string]]]]]}
  [body-nodes]
  (let [qual-ns (volatile! #{})
        unqual  (volatile! #{})
        raw-toks (volatile! #{})
        walk (fn walk [nd]
               (let [tag (n/tag nd)]
                 (cond
                   (or (n/whitespace-or-comment? nd) (= tag :multi-line) (= tag :regex)) nil
                   (= tag :token)
                   (let [s (n/string nd)]
                     (when-not (str/starts-with? s "\"")
                       (vswap! raw-toks conj s)
                       (cond
                         (str/starts-with? s "::")
                         (let [rest-s (subs s 2), idx (.indexOf ^String rest-s "/")]
                           (when (pos? idx) (vswap! qual-ns conj (subs rest-s 0 idx))))
                         (str/starts-with? s ":") nil
                         :else
                         (let [idx (.indexOf ^String s "/")]
                           (if (pos? idx)
                             (vswap! qual-ns conj (subs s 0 idx))
                             (vswap! unqual conj s))))))
                   (= tag :keyword)
                   (let [s (n/string nd)]
                     (when (str/starts-with? s "::")
                       (let [rest-s (subs s 2), idx (.indexOf ^String rest-s "/")]
                         (when (pos? idx) (vswap! qual-ns conj (subs rest-s 0 idx))))))
                   (n/inner? nd)
                   (doseq [ch (n/children nd)] (walk ch)))))]
    (doseq [nd body-nodes]
    (when-let [pnd (if (string? nd) (try (p/parse-string-all nd) (catch Exception _ nil)) nd)]
      (walk pnd)))
    {:qual-ns @qual-ns :unqual @unqual :raw-toks @raw-toks}))

(defn parse-require-spec
  {:malli/schema [:function [:=> [:cat :any] [:maybe :map]]]}
  [vec-nd]
  (when (= :vector (n/tag vec-nd))
    (let [elems (vec (remove n/whitespace-or-comment? (n/children vec-nd)))
          lib (some-> (first elems) n/string)]
      (when lib
        (loop [xs (rest elems), alias-s nil, refers nil, refer-all? false]
          (if (empty? xs)
            {:lib lib :alias alias-s :refers refers :refer-all? refer-all?}
            (let [k (n/string (first xs)), v (second xs)]
              (cond
                (= k ":as") (recur (nnext xs) (some-> v n/string) refers refer-all?)
                (= k ":refer")
                (if (= ":all" (some-> v n/string))
                  (recur (nnext xs) alias-s refers true)
                  (let [rs (when (and v (= :vector (n/tag v)))
                             (mapv n/string (remove n/whitespace-or-comment? (n/children v))))]
                    (recur (nnext xs) alias-s rs refer-all?)))
                :else (recur (nnext xs) alias-s refers refer-all?)))))))))

(defn analyze-parsed-root
  {:malli/schema [:=> [:cat [:maybe :any]] [:vector :map]]}
  [root]
  (let [top-forms (vec (remove n/whitespace-or-comment? (when (and root (n/inner? root)) (n/children root))))
        ns-nd (first top-forms)
        {:keys [qual-ns unqual raw-toks]} (collect-body-refs (rest top-forms))
        req-list (when (and ns-nd (= :list (n/tag ns-nd))
                 (= "ns" (some-> (first (remove n/whitespace-or-comment? (n/children ns-nd))) n/string)))
        (first (filter #(and (= :list (n/tag %))
                             (= ":require" (some-> (first (remove n/whitespace-or-comment? (n/children %))) n/string)))
                       (n/children ns-nd))))
        specs (when req-list
                (keep parse-require-spec (rest (remove n/whitespace-or-comment? (n/children req-list)))))]
    (mapv (fn [{:keys [lib alias refers refer-all?] :as sp}]
            (let [bare? (and (nil? alias) (nil? refers) (not refer-all?))
                  alias-used? (boolean (and alias (contains? qual-ns alias)))
                  lib-used? (boolean (or (contains? qual-ns lib)
                                         (some #(str/starts-with? % (str lib ".")) raw-toks)))
                  used-refers (when (seq refers) (filterv #(contains? unqual %) refers))
                  unused-refers (when (seq refers) (filterv #(not (contains? unqual %)) refers))
                  keep? (or bare? refer-all? alias-used? lib-used? (seq used-refers))]
              (assoc sp :keep? (boolean keep?) :alias-used? alias-used? :lib-used? lib-used?
                        :used-refers used-refers :unused-refers unused-refers)))
          specs)))

(defn analyze-ns-requires
  "Parses a complete `.clj` namespace file once with `rewrite-clj` and returns per-require usage facts."
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :map]]}
  [file-src]
  (if (str/blank? (str file-src))
  []
  (if-let [root (try (p/parse-string-all file-src) (catch Throwable _ nil))]
    (analyze-parsed-root root)
    [])))

(defn prune-unused-requires-src
  "Single-pass CST pruning of genuinely unused `:require` specs from `file-src`."
  {:malli/schema [:function [:=> [:cat :string] :map]]}
  [file-src]
  (if-let [root (try (p/parse-string-all file-src) (catch Exception _ nil))]
    (let [analysis (analyze-parsed-root root)
          drop-libs (into #{} (comp (remove :keep?) (map :lib)) analysis)
          kept-libs (mapv :lib (filter :keep? analysis))]
      (if (empty? drop-libs)
        {:ok? true :changed? false :src file-src :removed [] :kept kept-libs}
        (let [z0 (z/edn root)
              req-loc (loop [loc z0]
                        (cond
                          (z/end? loc) nil
                          (and (= :list (n/tag (z/node loc)))
                               (= ":require" (some-> (first (remove n/whitespace-or-comment? (n/children (z/node loc)))) n/string)))
                          loc
                          :else (recur (z/next loc))))]
          (if-not req-loc
            {:ok? true :changed? false :src file-src :removed [] :kept kept-libs}
            (let [req-nd (z/node req-loc)
                  kept-spec-nodes
                  (keep (fn [ch]
                          (when (= :vector (n/tag ch))
                            (let [lib (some-> (first (remove n/whitespace-or-comment? (n/children ch))) n/string)]
                              (when-not (contains? drop-libs lib) ch))))
                        (n/children req-nd))
                  new-req-str (if (empty? kept-spec-nodes)
                                ""
                                (str "(:require "
                                     (str/join "\n            " (map n/string kept-spec-nodes))
                                     ")"))
                  z' (if (str/blank? new-req-str)
                       (z/remove req-loc)
                       (z/replace req-loc (p/parse-string new-req-str)))]
              {:ok? true :changed? true :src (z/root-string z')
               :removed (vec (sort drop-libs)) :kept kept-libs})))))
    {:ok? false :changed? false :error :invalid-clojure-syntax :src file-src :removed [] :kept []}))

(defn strip-reader-meta
  "Recursively strips reader `:line`/`:column`/`:end-line`/`:end-column` metadata from Clojure data
   while keeping semantic metadata (`^:private`, `^String`, `^:seon.flow/edge`, etc.)."
  {:malli/schema [:=> [:cat :any] :any], :cost/ticks 3000000, :cost/bytes 3000000}
  [x]
  (let [clean-m (fn [m] (not-empty (dissoc m :line :column :end-line :end-column)))
        remeta (fn [orig v]
                 (if (instance? clojure.lang.IObj v)
                   (if-let [cm (clean-m (meta orig))]
                     (with-meta v cm)
                     (with-meta v nil))
                   v))]
    (cond
      (map? x)    (remeta x (into (empty x) (map (fn [[k v]] [(strip-reader-meta k) (strip-reader-meta v)]) x)))
      (vector? x) (remeta x (mapv strip-reader-meta x))
      (set? x)    (remeta x (into (empty x) (map strip-reader-meta x)))
      (seq? x)    (remeta x (apply list (map strip-reader-meta x)))
      :else       (remeta x x))))

(defn parse-ns-requires
  "Parses the `(:require ...)` specs directly from an `(ns ...)` form string."
  {:malli/schema [:function [:=> [:cat :string] [:vector :map]]]}
  [ns-src]
  (if-let [root (try (p/parse-string-all (str ns-src)) (catch Exception _ nil))]
  (let [ns-nd (first (remove n/whitespace-or-comment? (n/children root)))
        req-list (when (and ns-nd (n/inner? ns-nd))
                   (first (filter #(and (= :list (n/tag %))
                                        (= ":require" (some-> (first (remove n/whitespace-or-comment? (n/children %))) n/string)))
                                  (n/children ns-nd))))]
    (if req-list
      (vec (keep parse-require-spec
                 (rest (remove n/whitespace-or-comment? (n/children req-list)))))
      []))
  []))

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
                            " (0-arg: [[] out]; 1-arg taking a vector/seq [a b]: [[[a b]] out] — outer vector is the arg list!).")}))
            exs))))

(defn synth-ns-form-src
  "Builds a canonical `(ns nsym ...)` form string from `aliases` `{alias-sym lib-sym}` and `refers` `{var-sym lib-sym}`."
  {:malli/schema [:=> {:examples [[[(quote demo.t) {(quote str) (quote clojure.string)} {(quote deftest) (quote clojure.test) (quote is) (quote clojure.test)}]
                                   "(ns demo.t\n  (:require [clojure.string :as str]\n            [clojure.test :refer [deftest is]]))"]
                                  [[(quote demo.bare) {} {}] "(ns demo.bare)"]]}
                  [:cat :symbol :map :map] :string]
   :cost/ticks 1000000}
  [nsym aliases refers]
  (let [by-lib (reduce-kv (fn [a k lib] (if (and (symbol? k) (symbol? lib) (not= lib nsym) (not= lib (quote clojure.core))) (assoc-in a [lib :as] k) a)) {} (or aliases {}))
        by-lib (reduce-kv (fn [a r lib] (if (and (symbol? r) (symbol? lib) (not= lib nsym) (not= lib (quote clojure.core))) (update-in a [lib :refer] (fnil conj (sorted-set)) r) a)) by-lib (or refers {}))
        specs (mapv (fn [lib] (let [{:keys [as refer]} (get by-lib lib)] (pr-str (cond-> [lib] as (conj :as as) (seq refer) (conj :refer (vec refer)))))) (sort-by str (keys by-lib)))]
    (if (empty? specs) (str "(ns " nsym ")") (str "(ns " nsym "\n  (:require " (str/join "\n            " specs) "))"))))

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

(defn- merge-require-spec-node
  [ch sp]
  (let [req-map (parse-require-spec (p/parse-string (pr-str sp)))
        cur-map (if (= :vector (n/tag ch))
                  (parse-require-spec ch)
                  {:lib (n/string ch) :alias nil :refers nil :refer-all? false})
        add-as (when (and (nil? (:alias cur-map)) (:alias req-map)) (:alias req-map))
        cur-r (set (:refers cur-map))
        add-rs (when (and (not (:refer-all? cur-map)) (seq (:refers req-map)))
                 (filterv #(not (contains? cur-r %)) (:refers req-map)))]
    (if (and (nil? add-as) (empty? add-rs))
      [ch false]
      (let [final-as (or (:alias cur-map) add-as)
            final-rs (when-not (:refer-all? cur-map)
                       (let [u (into (vec (or (:refers cur-map) [])) add-rs)]
                         (when (seq u) u)))
            merged-vec (cond-> [(symbol (:lib cur-map))]
                         final-as (conj :as (symbol final-as))
                         (:refer-all? cur-map) (conj :refer :all)
                         (seq final-rs) (conj :refer (mapv symbol final-rs)))]
        [(p/parse-string (pr-str merged-vec)) true]))))

(defn add-require-src
  "Structurally adds or merges one or more `:require` spec(s) (e.g. `'[seon.store :as store]` or a vector of specs)
   into an `(ns ...)` form string idempotently, merging missing `:as`/`:refer` into existing lib specs."
  {:malli/schema [:function [:=> [:cat :string :any] :map]]}
  [ns-src spec-or-specs]
  (let [specs (let [v (if (string? spec-or-specs) (read-string spec-or-specs) spec-or-specs)]
                (if (and (vector? v) (seq v) (every? #(or (vector? %) (symbol? %)) v))
                  v
                  [v]))
        norm-specs (mapv (fn [sp] (if (symbol? sp) [sp] sp)) specs)
        root (or (try (p/parse-string-all (str ns-src)) (catch Exception _ nil)) (p/parse-string-all ""))
        z0 (z/edn root)
        req-loc (loop [loc z0]
                  (cond
                    (z/end? loc) nil
                    (and (= :list (n/tag (z/node loc)))
                         (= ":require" (some-> (first (remove n/whitespace-or-comment? (n/children (z/node loc)))) n/string)))
                    loc
                    :else (recur (z/next loc))))]
    (if req-loc
      (let [req-nd (z/node req-loc)
            by-lib (reduce (fn [m sp] (assoc m (str (first sp)) sp)) {} norm-specs)
            child-lib (fn [ch]
                        (cond
                          (= :vector (n/tag ch)) (some-> (first (remove n/whitespace-or-comment? (n/children ch))) n/string)
                          (= :token (n/tag ch)) (let [s (n/string ch)] (when-not (str/starts-with? s ":") s))))
            raw (vec (n/children req-nd))
            seen-libs (volatile! #{})
            merged-libs (volatile! [])
            raw' (mapv (fn [ch]
                         (if-let [lib (child-lib ch)]
                           (do (vswap! seen-libs conj lib)
                               (if-let [sp (get by-lib lib)]
                                 (let [[ch' chg?] (merge-require-spec-node ch sp)]
                                   (when chg? (vswap! merged-libs conj lib))
                                   ch')
                                 ch))
                           ch))
                       raw)
            existing-libs @seen-libs
            to-add (filterv (fn [sp] (not (contains? existing-libs (str (first sp))))) norm-specs)
            changed-libs (into @merged-libs (map (comp str first) to-add))]
        (if (empty? changed-libs)
          {:ok? true :changed? false :src ns-src :added []}
          (let [updated (if (empty? to-add)
                          raw'
                          (let [[_ col] (cst/prefix-line-and-col (cst/loc-prefix-str req-loc))
                                insert-nodes (mapcat (fn [sp]
                                                       [(n/newline-node "\n")
                                                        (n/spaces (+ col 10))
                                                        (p/parse-string (pr-str sp))])
                                                     to-add)
                                last-idx (last (keep-indexed (fn [i ch] (when-not (n/whitespace-or-comment? ch) i)) raw'))]
                            (vec (concat (subvec raw' 0 (inc last-idx))
                                         insert-nodes
                                         (subvec raw' (inc last-idx))))))
                z' (z/replace req-loc (n/replace-children req-nd updated))]
            {:ok? true :changed? true :src (z/root-string z') :added changed-libs})))
      (let [ns-loc (loop [loc z0]
                     (cond
                       (z/end? loc) nil
                       (= :list (n/tag (z/node loc))) loc
                       :else (recur (z/next loc))))]
        (if-not ns-loc
          {:ok? false :error :ns-form-not-found}
          (let [ns-nd (z/node ns-loc)
                req-str (str "(:require " (str/join "\n            " (map pr-str norm-specs)) ")")
                raw (vec (n/children ns-nd))
                updated (vec (concat raw [(n/newline-node "\n") (n/spaces 2) (p/parse-string req-str)]))
                z' (z/replace ns-loc (n/replace-children ns-nd updated))]
            {:ok? true :changed? true :src (z/root-string z')
             :added (mapv (comp str first) norm-specs)}))))))

(defn sync-ns-requires-for-srcs
  "Ensures a namespace has an `(ns ...)` header covering referenced live `aliases`/`refers`/`bare-libs` in `srcs`."
  {:malli/schema [:function
                  [:=> [:cat :symbol [:maybe :string] [:sequential :string] :map :map] [:maybe :string]]
                  [:=> [:cat :symbol [:maybe :string] [:sequential :string] :map :map [:maybe [:set :symbol]]] [:maybe :string]]]
   :cost/ticks 3000000}
  ([nsym existing-ns-src srcs aliases refers] (sync-ns-requires-for-srcs nsym existing-ns-src srcs aliases refers #{}))
  ([nsym existing-ns-src srcs aliases refers bare-libs]
   (let [{:keys [qual-ns unqual]} (collect-body-refs srcs)
         ok? (fn [lib] (and (symbol? lib) (not= lib nsym) (not= lib (quote clojure.core))))
         ua (into {} (filter (fn [[a lib]] (and (symbol? a) (ok? lib) (contains? qual-ns (name a))))) (or aliases {}))
         ur (into {} (filter (fn [[r lib]] (and (symbol? r) (ok? lib) (contains? unqual (name r))))) (or refers {}))
         ub (into (sorted-set) (filter (fn [lib] (and (ok? lib) (contains? qual-ns (name lib))))) (or bare-libs #{}))]
     (if (str/blank? (str existing-ns-src))
       (let [base (synth-ns-form-src nsym ua ur)
             extra (remove #(or (contains? (set (vals ua)) %) (contains? (set (vals ur)) %)) ub)]
         (if (seq extra) (or (:src (add-require-src base (mapv vector extra))) base) base))
       (let [cur (parse-ns-requires existing-ns-src)
             by-lib (into {} (map (juxt :lib identity)) cur)
             ex-l (set (keys by-lib))
             ex-a (into #{} (keep :alias) cur)
             ex-r (into #{} (mapcat :refers) cur)
             bl (reduce-kv (fn [m a lib]
                             (if (or (contains? ex-a (name a)) (some? (:alias (get by-lib (str lib)))))
                               m
                               (assoc-in m [lib :as] a)))
                           {} ua)
             bl (reduce-kv (fn [m r lib]
                             (if (or (contains? ex-r (name r)) (:refer-all? (get by-lib (str lib))))
                               m
                               (update-in m [lib :refer] (fnil conj (sorted-set)) r)))
                           bl ur)
             bl (reduce (fn [m lib] (if (or (contains? ex-l (str lib)) (contains? m lib)) m (assoc m lib {}))) bl ub)
             specs (mapv (fn [lib] (let [{:keys [as refer]} (get bl lib)] (cond-> [lib] as (conj :as as) (seq refer) (conj :refer (vec refer))))) (sort-by str (keys bl)))]
         (when (seq specs) (let [r (add-require-src existing-ns-src specs)] (when (:changed? r) (:src r)))))))))

(defn- unquoted-qual-ns
  [srcs]
  (let [qns (volatile! #{})
        walk (fn walk [nd]
               (let [tag (n/tag nd)]
                 (cond
                   (or (n/whitespace-or-comment? nd) (= tag :multi-line) (= tag :regex)
                       (= tag :quote) (= tag :syntax-quote)) nil
                   (and (= tag :list)
                        (= "quote" (some-> (first (remove n/whitespace-or-comment? (n/children nd))) n/string)))
                   nil
                   (= tag :token)
                   (let [s (n/string nd)]
                     (when-not (or (str/starts-with? s "\"") (str/starts-with? s ":"))
                       (let [idx (.indexOf ^String s "/")]
                         (when (pos? idx) (vswap! qns conj (subs s 0 idx))))))
                   (n/inner? nd)
                   (doseq [ch (n/children nd)] (walk ch)))))]
    (doseq [nd srcs]
    (when-let [pnd (if (string? nd) (try (p/parse-string-all nd) (catch Exception _ nil)) nd)]
      (walk pnd)))
    @qns))

(defn ^:seon.flow/edge sync-ns-requires-for-live-ns
  "Inspects live Clojure namespace `nsym` (if loaded) and unquoted loaded qualified ns refs in `srcs`, calling `sync-ns-requires-for-srcs`."
  {:malli/schema [:=> [:cat :symbol [:maybe :string] [:sequential :string]] [:maybe :string]] :cost/ticks 4000000}
  [nsym existing-ns-src srcs]
  (let [nso (find-ns nsym)
        blank-ex? (str/blank? (str existing-ns-src))
        aliases (if nso (into {} (map (fn [[a n2]] [a (ns-name n2)])) (ns-aliases nso)) {})
        refers (if nso (into {} (keep (fn [[s v]] (when-let [vn (some-> (and (var? v) (:ns (meta v))) ns-name)] (when (and (not= vn 'clojure.core) (not= vn nsym)) [s vn])))) (ns-refers nso)) {})
        bare (into #{}
                   (keep (fn [q]
                           (when (and (string? q) (str/includes? q "."))
                             (when-let [lns (find-ns (symbol q))]
                               (let [lname (ns-name lns)]
                                 (when (and (not= lname nsym)
                                            (not (some #(= nsym (ns-name %)) (vals (ns-aliases lns)))))
                                   lname))))))
                   (unquoted-qual-ns srcs))]
    (if (and (not blank-ex?) (empty? aliases) (empty? refers) (empty? bare))
      nil
      (sync-ns-requires-for-srcs nsym existing-ns-src srcs aliases refers bare))))

(defn split-leading-ns-src
  "Splits a source string starting with `(ns ...)` into `[ns-src rest-src]`, or nil if it has no trailing forms."
  {:malli/schema [:=> {:examples [[["(ns a.b)\n(defn f [] 1)"] ["(ns a.b)" "\n(defn f [] 1)"]]
                                  [["(ns a.b)"] nil]]}
                  [:cat :string] [:maybe [:tuple :string :string]]]
   :cost/ticks 1000000}
  [s]
  (when (str/starts-with? (str/triml s) "(ns ")
    (let [root (try (p/parse-string-all s) (catch Throwable _ nil))
          chs (when (and root (n/inner? root)) (vec (n/children root)))
          first-idx (first (keep-indexed (fn [i ch] (when-not (n/whitespace-or-comment? ch) i)) chs))
          rest-non-ws (when first-idx (some #(not (n/whitespace-or-comment? %)) (subvec chs (inc first-idx))))]
      (when (and first-idx rest-non-ws)
        [(n/string (nth chs first-idx))
         (apply str (map n/string (subvec chs (inc first-idx))))]))))
