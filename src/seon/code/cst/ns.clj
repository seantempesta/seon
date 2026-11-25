(ns seon.code.cst.ns
  "Pure rewrite-clj CST analysis & transforms for `:require` clauses and `defn` attr-maps."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]
            [seon.code.cst :as cst]
            [seon.code.cst.meta :as cst-meta]))

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

(defn ^{:seon.code/reexport 'seon.code.cst.meta/topo-order-ns-forms} topo-order-ns-forms [& args] (apply cst-meta/topo-order-ns-forms args))

(defn ^{:seon.code/reexport 'seon.code.cst.meta/topo-sort-ns-forms} topo-sort-ns-forms [& args] (apply cst-meta/topo-sort-ns-forms args))

(defn ^{:seon.code/reexport 'seon.code.cst.meta/extract-defn-malli-schema} extract-defn-malli-schema [& args] (apply cst-meta/extract-defn-malli-schema args))

(defn ^{:seon.code/reexport 'seon.code.cst.meta/fn-schema-arity} fn-schema-arity [& args] (apply cst-meta/fn-schema-arity args))

(defn ^{:seon.code/reexport 'seon.code.cst.meta/check-examples-against-arities} check-examples-against-arities [& args] (apply cst-meta/check-examples-against-arities args))

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

(defn ^{:seon.code/reexport 'seon.code.cst.meta/set-defn-meta-src} set-defn-meta-src [& args] (apply cst-meta/set-defn-meta-src args))

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

(defn- safe-parse-all
  [s]
  (try (p/parse-string-all s) (catch clojure.lang.ExceptionInfo _ nil)))

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
    (when-let [pnd (if (string? nd) (safe-parse-all nd) nd)]
      (walk pnd)))
    {:qual-ns @qual-ns :unqual @unqual :raw-toks @raw-toks}))

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
  (if-let [root (safe-parse-all file-src)]
    (analyze-parsed-root root)
    [])))

(defn prune-unused-requires-src
  "Single-pass CST pruning of genuinely unused `:require` specs from `file-src`."
  {:malli/schema [:function [:=> [:cat :string] :map]]}
  [file-src]
  (if-let [root (safe-parse-all file-src)]
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

(defn parse-ns-requires
  "Parses the `(:require ...)` specs directly from an `(ns ...)` form string."
  {:malli/schema [:function [:=> [:cat :string] [:vector :map]]]}
  [ns-src]
  (if-let [root (safe-parse-all (str ns-src))]
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
        root (or (safe-parse-all (str ns-src)) (p/parse-string-all ""))
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
    (when-let [pnd (if (string? nd) (safe-parse-all nd) nd)]
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
    (let [root (safe-parse-all s)
          chs (when (and root (n/inner? root)) (vec (n/children root)))
          first-idx (first (keep-indexed (fn [i ch] (when-not (n/whitespace-or-comment? ch) i)) chs))
          rest-non-ws (when first-idx (some #(not (n/whitespace-or-comment? %)) (subvec chs (inc first-idx))))]
      (when (and first-idx rest-non-ws)
        [(n/string (nth chs first-idx))
         (apply str (map n/string (subvec chs (inc first-idx))))]))))