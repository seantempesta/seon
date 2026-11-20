(ns seon.code.cst.ns
  "Pure rewrite-clj CST analysis & transforms for `:require` clauses and `defn` attr-maps."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]
            [seon.code.cst :as cst]))

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
      (walk (if (string? nd) (p/parse-string-all nd) nd)))
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
  (let [root (p/parse-string-all file-src)
        analysis (analyze-parsed-root root)
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
             :removed (vec (sort drop-libs)) :kept kept-libs}))))))

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

(defn add-require-src
  "Structurally adds one or more `:require` spec(s) (e.g. `'[seon.store :as store]` or a vector of specs)
   to an `(ns ...)` form string idempotently, preserving formatting and existing comments."
  {:malli/schema [:function [:=> [:cat :string :any] :map]]}
  [ns-src spec-or-specs]
  (let [specs (let [v (if (string? spec-or-specs) (read-string spec-or-specs) spec-or-specs)]
                (if (and (vector? v) (seq v) (every? #(or (vector? %) (symbol? %)) v))
                  v
                  [v]))
        norm-specs (mapv (fn [sp] (if (symbol? sp) [sp] sp)) specs)
        root (p/parse-string-all (str ns-src))
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
            existing-libs (into #{}
                                (keep (fn [ch]
                                        (cond
                                          (= :vector (n/tag ch))
                                          (some-> (first (remove n/whitespace-or-comment? (n/children ch))) n/string)
                                          (= :token (n/tag ch))
                                          (let [s (n/string ch)] (when-not (str/starts-with? s ":") s)))))
                                (rest (remove n/whitespace-or-comment? (n/children req-nd))))
            to-add (filterv (fn [sp] (not (contains? existing-libs (str (first sp))))) norm-specs)]
        (if (empty? to-add)
          {:ok? true :changed? false :src ns-src :added []}
          (let [[_ col] (cst/prefix-line-and-col (cst/loc-prefix-str req-loc))
                indent-str (apply str (repeat (+ col 10) \space))
                raw (vec (n/children req-nd))
                insert-nodes (mapcat (fn [sp]
                                       [(n/newline-node "\n")
                                        (n/spaces (+ col 10))
                                        (p/parse-string (pr-str sp))])
                                     to-add)
                ;; Keep any trailing whitespace/comment clean by inserting right after last non-ws child
                last-idx (last (keep-indexed (fn [i ch] (when-not (n/whitespace-or-comment? ch) i)) raw))
                updated (vec (concat (subvec raw 0 (inc last-idx))
                                     insert-nodes
                                     (subvec raw (inc last-idx))))
                z' (z/replace req-loc (n/replace-children req-nd updated))]
            {:ok? true :changed? true :src (z/root-string z')
             :added (mapv (comp str first) to-add)})))
      ;; No (:require ...) clause yet — append inside (ns ...)
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
                      merged (merge old-map meta-map)
                      new-map-nd (p/parse-string (binding [*print-namespace-maps* false] (pr-str merged)))
                      updated (assoc raw (:i existing-attr) new-map-nd)
                      z' (z/replace fn-loc (n/replace-children fn-nd updated))]
                  {:ok? true :action :merged :src (z/root-string z')})
                (let [anchor-idx (:i (nth non-ws (dec attr-slot)))
                      next-raw (subvec raw (inc anchor-idx))
                      starts-nl? (and (seq next-raw) (= :newline (n/tag (first next-raw))))
                      trimmed-rest (if starts-nl? next-raw (vec (drop-while n/whitespace? next-raw)))
                      map-str (binding [*print-namespace-maps* false] (pr-str meta-map))
                      ins (cond-> [(n/newline-node "\n") (n/spaces 2) (p/parse-string map-str)]
                            (not starts-nl?) (into [(n/newline-node "\n") (n/spaces 2)]))
                      updated (vec (concat (subvec raw 0 (inc anchor-idx)) ins trimmed-rest))
                      z' (z/replace fn-loc (n/replace-children fn-nd updated))]
                  {:ok? true :action :inserted :src (z/root-string z')})))))))))

(defn parse-ns-requires
  "Parses the `(:require ...)` specs directly from an `(ns ...)` form string."
  {:malli/schema [:function [:=> [:cat :string] [:vector :map]]]}
  [ns-src]
  (let [root (p/parse-string-all (str ns-src))
        ns-nd (first (remove n/whitespace-or-comment? (n/children root)))
        req-list (first (filter #(and (= :list (n/tag %))
                                      (= ":require" (some-> (first (remove n/whitespace-or-comment? (n/children %))) n/string)))
                                (n/children ns-nd)))]
    (if req-list
      (vec (keep parse-require-spec
                 (rest (remove n/whitespace-or-comment? (n/children req-list)))))
      [])))

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
