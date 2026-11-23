(ns seon.code.rewrite.move
  "CST form & namespace-header rewriters for lossless var moves (`move-vars!`)."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(defn string-literal-node?
  {:malli/schema [:=> [:cat :any] :boolean]}
  [nd]
  (boolean
    (when (n/node? nd)
      (let [t (n/tag nd)]
        (or (= t :multi-line)
            (and (= t :token) (str/starts-with? (n/string nd) "\"")))))))

(defn parse-require-env
  "Builds `{:alias->ns :ns->alias :refer->ns}` from parsed `:require` clause forms (e.g. `[[foo.bar :as fb :refer [x]]]`)."
  {:malli/schema [:=> [:cat [:sequential :any]] :map]
   :cost/ticks 2000000}
  [req-clauses]
  (let [alias->ns (volatile! {})
        ns->alias (volatile! {})
        refer->ns (volatile! {})]
    (doseq [c req-clauses :when (and (vector? c) (symbol? (first c)))]
      (let [target (first c)]
        (doseq [[k v] (partition 2 1 (rest c))]
          (when (and (= :as k) (symbol? v))
            (vswap! alias->ns assoc v target)
            (vswap! ns->alias assoc target v))
          (when (and (= :refer k) (coll? v))
            (doseq [r v :when (symbol? r)]
              (vswap! refer->ns assoc r target))))))
    {:alias->ns @alias->ns
     :ns->alias @ns->alias
     :refer->ns @refer->ns}))

(defn- destructured-key-sym [nd]
  (let [t (n/tag nd)]
    (when (or (= t :token) (n/keyword-node? nd))
      (let [s (n/string nd)]
        (cond
          (str/starts-with? s "::")
          (let [b (subs s 2)] (symbol (if (str/includes? b "/") (second (str/split b #"/" 2)) b)))
          (str/starts-with? s ":")
          (let [b (subs s 1)] (symbol (if (str/includes? b "/") (second (str/split b #"/" 2)) b)))
          (str/starts-with? s "\"") nil
          :else (when-let [sym (try (symbol s) (catch Throwable _ nil))]
                  (when (symbol? sym) (symbol (name sym)))))))))

(defn- binding-pattern-syms [nd]
  (let [t (n/tag nd)]
    (cond
      (n/symbol-node? nd)
      (let [s (n/string nd)]
        (if (or (= s "&") (= s "_"))
          #{}
          (let [sym (try (symbol s) (catch Throwable _ nil))]
            (if (and (symbol? sym) (nil? (namespace sym))) #{sym} #{}))))

      (= t :vector)
      (reduce (fn [acc ch] (if (n/whitespace-or-comment? ch) acc (into acc (binding-pattern-syms ch))))
              #{} (n/children nd))

      (= t :map)
      (let [xs (vec (remove n/whitespace-or-comment? (n/children nd)))]
        (reduce (fn [acc [k v]]
                  (let [ks (n/string k)]
                    (cond
                      (= ks ":as") (into acc (binding-pattern-syms v))
                      (or (= ks ":keys") (= ks ":syms") (= ks ":strs")
                          (and (str/starts-with? ks ":")
                               (or (str/ends-with? ks "/keys") (str/ends-with? ks "/syms") (str/ends-with? ks "/strs"))))
                      (if (= :vector (n/tag v))
                        (reduce (fn [a ch]
                                  (if (n/whitespace-or-comment? ch) a
                                      (if-let [s (destructured-key-sym ch)] (conj a s) a)))
                                acc (n/children v))
                        acc)
                      (= ks ":or") acc
                      :else (into acc (binding-pattern-syms k)))))
                #{} (partition 2 xs)))

      (= t :meta)
      (let [xs (remove n/whitespace-or-comment? (n/children nd))]
        (if-let [last-ch (last xs)] (binding-pattern-syms last-ch) #{}))

      :else #{})))

(defn- binding-vec-locals [vec-nd]
  (let [bxs (vec (remove n/whitespace-or-comment? (n/children vec-nd)))]
    (loop [i 0 acc #{}]
      (if (>= (inc i) (count bxs))
        acc
        (let [lhs (nth bxs i) rhs (nth bxs (inc i)) ls (n/string lhs)]
          (cond
            (= ls ":let") (recur (+ i 2) (if (= :vector (n/tag rhs)) (into acc (binding-vec-locals rhs)) acc))
            (or (= ls ":when") (= ls ":while")) (recur (+ i 2) acc)
            :else (recur (+ i 2) (into acc (binding-pattern-syms lhs)))))))))

(defn- form-extra-locals [head-s sig]
  (cond
    (#{"let" "loop" "when-let" "if-let" "when-some" "if-some" "with-open" "for" "doseq"} head-s)
    (if (and (>= (count sig) 2) (= :vector (n/tag (second sig))))
      (binding-vec-locals (second sig))
      #{})

    (= head-s "catch")
    (if (>= (count sig) 3) (binding-pattern-syms (nth sig 2)) #{})

    (= head-s "letfn")
    (if (and (>= (count sig) 2) (= :vector (n/tag (second sig))))
      (reduce (fn [acc ch]
                (if (= :list (n/tag ch))
                  (let [fn-head (first (remove n/whitespace-or-comment? (n/children ch)))]
                    (if fn-head (into acc (binding-pattern-syms fn-head)) acc))
                  acc))
              #{} (n/children (second sig)))
      #{})

    (#{"defn" "defn-" "fn" "defmacro"} head-s)
    (let [rest-xs (rest sig)
          fn-name (when (and (= head-s "fn") (seq rest-xs) (n/symbol-node? (first rest-xs)))
                    (binding-pattern-syms (first rest-xs)))
          after-name (if fn-name (rest rest-xs)
                         (if (and (not= head-s "fn") (seq rest-xs)) (rest rest-xs) rest-xs))
          ;; Skip optional docstring and attr-map before arglist(s)
          body-start (drop-while #(or (string-literal-node? %) (= :map (n/tag %))) after-name)
          first-ar (first body-start)
          ar-locals (cond
                      (and first-ar (= :vector (n/tag first-ar)))
                      (binding-pattern-syms first-ar)

                      (and first-ar (= :list (n/tag first-ar)))
                      (reduce (fn [acc ch]
                                (if (= :list (n/tag ch))
                                  (let [p (first (remove n/whitespace-or-comment? (n/children ch)))]
                                    (if (and p (= :vector (n/tag p))) (into acc (binding-pattern-syms p)) acc))
                                  acc))
                              #{} body-start)
                      :else #{})]
      (into (or fn-name #{}) ar-locals))

    :else #{}))

(defn- remap-moved-alias [al-sym from-env to-env to-ns]
  (when-let [target-ns (get (:alias->ns from-env) al-sym)]
    (if (= target-ns to-ns)
      :self
      (if-let [to-al (get (:ns->alias to-env) target-ns)]
        (when (not= to-al al-sym) [:alias to-al])
        [:full target-ns]))))

(defn- rewrite-moved-kw [nd from-ns to-ns from-env to-env]
  (let [s (n/string nd)]
    (if (str/starts-with? s "::")
      (let [body (subs s 2)]
        (if (str/includes? body "/")
          (let [[al nm] (str/split body #"/" 2)
                rm (remap-moved-alias (symbol al) from-env to-env to-ns)]
            (cond
              (= rm :self) (n/keyword-node (keyword nm) true)
              (and (vector? rm) (= :alias (first rm))) (n/keyword-node (keyword (name (second rm)) nm) true)
              (and (vector? rm) (= :full (first rm))) (n/keyword-node (keyword (name (second rm)) nm))
              :else nd))
          (n/keyword-node (keyword (name from-ns) body))))
      nd)))

(defn- rewrite-moved-sym [nd locals from-ns to-ns from-env to-env moved-simple-syms]
  (let [s (n/string nd)
        sym (try (symbol s) (catch Throwable _ nil))]
    (if-let [ns-s (and (symbol? sym) (namespace sym))]
      (let [ns-sym (symbol ns-s)
            v-str (name sym)
            v-sym (symbol v-str)
            rm (cond
                 (= ns-sym to-ns) :self
                 (and (= ns-sym from-ns) (contains? moved-simple-syms v-sym)) :self
                 :else (remap-moved-alias ns-sym from-env to-env to-ns))]
        (cond
          (= rm :self)
          (if (contains? locals v-sym)
            (n/token-node (symbol (name to-ns) v-str))
            (n/token-node v-sym))
          (and (vector? rm) (= :alias (first rm))) (n/token-node (symbol (name (second rm)) v-str))
          (and (vector? rm) (= :full (first rm))) (n/token-node (symbol (name (second rm)) v-str))
          :else nd))
      nd)))

(defn- rewrite-moved-ns-map [nd walk-fn locals quoted? from-ns to-ns from-env to-env]
  (let [kids (vec (n/children nd))
        q-idx (first (keep-indexed (fn [i ch] (when (= :map-qualifier (n/tag ch)) i)) kids))
        qnd (when q-idx (nth kids q-idx))
        qstr (when qnd (n/string qnd))
        new-qnd (when (and qstr (str/starts-with? qstr "::"))
                  (let [al-s (subs qstr 2)]
                    (if (str/blank? al-s)
                      (n/map-qualifier-node false (name from-ns))
                      (let [rm (remap-moved-alias (symbol al-s) from-env to-env to-ns)]
                        (cond
                          (= rm :self) (n/map-qualifier-node true nil)
                          (and (vector? rm) (= :alias (first rm))) (n/map-qualifier-node true (name (second rm)))
                          (and (vector? rm) (= :full (first rm))) (n/map-qualifier-node false (name (second rm)))
                          :else nil)))))
        kids' (if (and q-idx new-qnd) (assoc kids q-idx new-qnd) kids)]
    (n/replace-children nd (mapv (fn [i ch] (if (= i q-idx) ch (walk-fn ch locals quoted?)))
                                 (range (count kids')) kids'))))

(defn rewrite-moved-form-src
  "Losslessly rewrites a top-level Clojure form moved from `from-ns` to `to-ns` using `rewrite-clj`:
   strips self-aliases (`alias/v`, `#'alias/v`, `::alias/k`, `#::alias{...}`), remaps differing aliases
   (or qualifies fully when `to-env` lacks an alias), qualifies `::k` -> `:<from-ns>/k`, preserves quoted
   symbols while still rewriting read-time `::` keywords inside quotes, and avoids local shadowing."
  {:malli/schema [:=> [:cat :string :symbol :symbol :map :map [:set :symbol]] :string]
   :cost/ticks 20000000}
  [src from-ns to-ns from-env to-env moved-simple-syms]
  (let [root (try (p/parse-string-all src) (catch Throwable _ nil))]
    (if-not root
      src
      (let [walk-nd (fn walk-nd [nd locals quoted?]
                      (let [t (n/tag nd)]
                        (cond
                          (or (n/whitespace-or-comment? nd) (string-literal-node? nd))
                          nd

                          (= t :quote)
                          (n/replace-children nd (mapv #(walk-nd % locals true) (n/children nd)))

                          (n/keyword-node? nd)
                          (rewrite-moved-kw nd from-ns to-ns from-env to-env)

                          (n/symbol-node? nd)
                          (if quoted? nd (rewrite-moved-sym nd locals from-ns to-ns from-env to-env moved-simple-syms))

                          (= t :namespaced-map)
                          (rewrite-moved-ns-map nd walk-nd locals quoted? from-ns to-ns from-env to-env)

                          (= t :list)
                          (let [kids (vec (n/children nd))
                                sig (vec (remove n/whitespace-or-comment? kids))
                                head-s (when (seq sig) (n/string (first sig)))]
                            (if (and (not quoted?) (= head-s "quote"))
                              (n/replace-children nd (mapv #(walk-nd % locals true) kids))
                              (let [extra (if quoted? #{} (form-extra-locals head-s sig))
                                    locals' (if (seq extra) (into locals extra) locals)]
                                (n/replace-children nd (mapv #(walk-nd % locals' quoted?) kids)))))

                          (n/inner? nd)
                          (n/replace-children nd (mapv #(walk-nd % locals quoted?) (n/children nd)))

                          :else nd)))]
        (n/string (walk-nd root #{} false))))))

(defn merge-ns-header-requires
  "Merges needed `:require` clauses from `from-reqs` + `stub-refers-by-ns` into an existing `to` `(ns ...)`
   header while preserving non-`:require` clauses (`:import`, `:refer-clojure`, docstrings, comments),
   avoiding `:as` alias collisions, and merging missing `:as`/`:refer` on shared libs."
  {:malli/schema [:=> [:cat :string [:sequential :any] :map :symbol [:sequential :string]] :map]
   :cost/ticks 15000000}
  [existing-hdr-src from-reqs stub-refers-by-ns to-ns moved-srcs]
  (let [parsed (try (read-string existing-hdr-src) (catch Throwable _ nil))
        ex-reqs (if (and (seq? parsed) (= 'ns (first parsed)))
                  (vec (mapcat rest (filter #(and (seq? %) (= :require (first %))) (drop 2 parsed))))
                  [])
        ex-env (parse-require-env ex-reqs)
        used-als (volatile! (set (keys (:alias->ns ex-env))))
        moved-blob (str/join "\n" moved-srcs)
        parse-clause (fn [c]
                       (when (and (vector? c) (symbol? (first c)))
                         (let [pairs (partition 2 1 (rest c))]
                           {:lib (first c)
                            :as (some (fn [[k v]] (when (and (= :as k) (symbol? v)) v)) pairs)
                            :refer (some (fn [[k v]] (when (and (= :refer k) (coll? v)) (set v))) pairs)})))
        from-by-lib (into {} (keep #(when-let [p (parse-clause %)] [(:lib p) p])) from-reqs)
        ex-by-lib (into {} (keep #(when-let [p (parse-clause %)] [(:lib p) p])) ex-reqs)
        needed-lib? (fn [{:keys [lib as refer]}]
                      (or (contains? stub-refers-by-ns lib)
                          (and as (str/includes? moved-blob (str (name as) "/")))
                          (str/includes? moved-blob (str (name lib) "/"))
                          (some #(str/includes? moved-blob (name %)) refer)))
        build-clause (fn [lib al refs]
                       (cond-> [lib]
                         al (conj :as al)
                         (seq refs) (conj :refer (vec (sort refs)))))
        ;; 1. Update existing clauses (preserve alias, fill missing :as if non-colliding, union :refer)
        merged-ex (mapv (fn [c]
                          (if-let [{:keys [lib as refer]} (parse-clause c)]
                            (let [fp (get from-by-lib lib)
                                  add-as (when (and (nil? as) (:as fp) (not (contains? @used-als (:as fp))))
                                           (vswap! used-als conj (:as fp))
                                           (:as fp))
                                  new-al (or as add-as)
                                  new-refs (into (or refer #{}) (concat (:refer fp) (get stub-refers-by-ns lib)))]
                              (if (and (= new-al as) (= new-refs (or refer #{})))
                                c
                                (build-clause lib new-al new-refs)))
                            c))
                        ex-reqs)
        ;; 2. Append needed extra clauses from from-reqs (excluding to-ns, avoiding alias collisions)
        extra (vec (keep (fn [c]
                           (when-let [{:keys [lib as refer] :as p} (parse-clause c)]
                             (when (and (not= lib to-ns)
                                        (not (contains? ex-by-lib lib))
                                        (needed-lib? p))
                               (let [eff-al (when (and as (not (contains? @used-als as)))
                                              (vswap! used-als conj as)
                                              as)
                                     refs (into (or refer #{}) (get stub-refers-by-ns lib))]
                                 (build-clause lib eff-al refs)))))
                         from-reqs))
        final-reqs (into merged-ex extra)]
    (if (= final-reqs ex-reqs)
      {:hdr-src existing-hdr-src :to-reqs final-reqs}
      (let [req-str (str "(:require " (str/join "\n            " (map pr-str final-reqs)) ")")
            root (try (p/parse-string existing-hdr-src) (catch Throwable _ nil))]
        (if (and root (= :list (n/tag root)))
          (let [kids (vec (n/children root))
                req-idxs (vec (keep-indexed
                                (fn [i ch]
                                  (when (and (= :list (n/tag ch))
                                             (let [f (first (remove n/whitespace-or-comment? (n/children ch)))]
                                               (and f (= ":require" (n/string f)))))
                                    i))
                                kids))
                new-req-nd (p/parse-string req-str)
                new-kids (if (seq req-idxs)
                           (let [keep-idx (first req-idxs)
                                 drop-set (set (rest req-idxs))]
                             (vec (keep-indexed (fn [i ch]
                                                  (cond
                                                    (= i keep-idx) new-req-nd
                                                    (contains? drop-set i) nil
                                                    :else ch))
                                                kids)))
                           (conj kids (n/newlines 1) (n/spaces 2) new-req-nd))]
            {:hdr-src (n/string (n/replace-children root new-kids)) :to-reqs final-reqs})
          {:hdr-src existing-hdr-src :to-reqs final-reqs})))))

(defn add-require-to-ns-src
  "Adds (or updates, unioning existing :refer symbols) a `[to-ns ...]` clause in `ns-src` cleanly."
  {:malli/schema [:=> [:cat :string :symbol [:maybe :symbol] [:maybe [:or [:set :symbol] [:sequential :symbol]]]] :string], :cost/ticks 500000}
  [^String ns-src to-ns alias-sym refer-syms]
  (let [existing-re (re-pattern (str "\\[\\s*" (java.util.regex.Pattern/quote (name to-ns)) "(?=\\s|\\])(?:[^\\[\\]]|\\[[^\\]]*\\])*\\]"))
        existing-match (re-find existing-re ns-src)
        existing-refer (when existing-match
                         (let [v (try (read-string existing-match) (catch Throwable _ nil))]
                           (when (vector? v)
                             (some (fn [[k val]] (when (and (= :refer k) (coll? val)) (set val)))
                                   (partition 2 1 (rest v))))))
        merged-refer (into (or existing-refer #{}) refer-syms)
        clause (cond-> [to-ns]
                 alias-sym (conj :as alias-sym)
                 (seq merged-refer) (conj :refer (vec (sort merged-refer))))
        clause-str (pr-str clause)]
    (cond
      existing-match
      (str/replace-first ns-src existing-re (fn [_] clause-str))

      (str/includes? ns-src "(:require")
      (let [idx (.lastIndexOf ns-src "(:require")
            end-idx (loop [i idx depth 0 in-str? false esc? false]
                      (if (>= i (.length ns-src))
                        nil
                        (let [c (.charAt ns-src i)]
                          (cond
                            esc? (recur (inc i) depth in-str? false)
                            (and in-str? (= c \\)) (recur (inc i) depth true true)
                            (= c \") (recur (inc i) depth (not in-str?) false)
                            in-str? (recur (inc i) depth true false)
                            (= c \() (recur (inc i) (inc depth) false false)
                            (= c \)) (if (= depth 1) i (recur (inc i) (dec depth) false false))
                            :else (recur (inc i) depth false false)))))]
        (if end-idx
          (str (subs ns-src 0 end-idx) "\n            " clause-str (subs ns-src end-idx))
          ns-src))

      :else
      (let [last-paren (.lastIndexOf ns-src ")")]
        (if (pos? last-paren)
          (str (subs ns-src 0 last-paren) "\n  (:require " clause-str ")" (subs ns-src last-paren))
          ns-src)))))

(defn extract-require-clauses
  "Extracts `:require` clauses from an `(ns ...)` form string."
  {:malli/schema [:=> [:cat :string] [:maybe [:vector :any]]] :cost/ticks 50000}
  [^String ns-src]
  (let [form (try (read-string ns-src) (catch Throwable _ nil))]
    (when (and (seq? form) (= 'ns (first form)))
      (->> (drop 2 form)
           (filter #(and (seq? %) (= :require (first %))))
           (mapcat rest)
           vec))))

(defn extract-ns-extra-clauses
  "Extracts `:import` and `:refer-clojure` clauses from an `(ns ...)` form string."
  {:malli/schema [:=> [:cat :string] [:maybe [:vector :any]]] :cost/ticks 50000}
  [^String ns-src]
  (let [form (try (read-string ns-src) (catch Throwable _ nil))]
    (when (and (seq? form) (= 'ns (first form)))
      (->> (drop 2 form)
           (filter #(and (seq? %) (#{:import :refer-clojure} (first %))))
           vec))))

(defn build-ns-header-src
  "Builds a clean `(ns ...)` header string with optional docstring, `:require` clauses, and extra clauses (`:import`, `:refer-clojure`)."
  {:malli/schema [:function
                  [:=> [:cat :symbol [:maybe :string] [:maybe [:sequential :any]]] :string]
                  [:=> [:cat :symbol [:maybe :string] [:maybe [:sequential :any]] [:maybe [:sequential :any]]] :string]]
   :cost/ticks 50000}
  ([to-ns doc requires] (build-ns-header-src to-ns doc requires nil))
  ([to-ns doc requires extra-clauses]
   (let [req-lines (when (seq requires)
                     (str "\n  (:require "
                          (str/join "\n            " (map pr-str requires))
                          ")"))
         extra-lines (when (seq extra-clauses)
                       (str/join "" (map #(str "\n  " (pr-str %)) extra-clauses)))]
     (str "(ns " to-ns
          (when-not (str/blank? (str doc))
            (str "\n  " (pr-str (str doc))))
          req-lines
          extra-lines
          ")"))))

(defn make-reexport-src
  "Builds a forwarding stub in `from` pointing to `alias-sym/var` (preserving `^:seon/callable` / `^:seon.flow/edge`)."
  {:malli/schema [:function
                  [:=> [:cat :symbol :symbol :string] :string]
                  [:=> [:cat :symbol :symbol :string :boolean] :string]]
   :cost/ticks 50000}
  ([vsym alias-sym orig-src] (make-reexport-src vsym alias-sym orig-src false))
  ([vsym alias-sym orig-src private?]
   (let [vname (name vsym)
         target (str (name alias-sym) "/" vname)
         trimmed (str/triml (str orig-src))
         is-fn? (boolean (re-find #"^\(\s*defn-?\b" trimmed))
         callable? (boolean (re-find #"\^:seon/callable|\{\s*:seon/callable\s+true" (str orig-src)))
         meta-tags (str (when (and (not private?) callable?) "^:seon/callable ")
                        (when (str/includes? (str orig-src) ":seon.flow/edge") "^:seon.flow/edge "))]
     (cond
       (and is-fn? private?) (str "(defn- " vname " [& args] (apply " target " args))")
       is-fn?                (str "(defn " meta-tags vname " [& args] (apply " target " args))")
       private?              (str "(def ^:private " vname " " target ")")
       :else                 (str "(def " meta-tags vname " " target ")")))))

(defn promote-private-src
  "Promotes a private `defn-` or `^:private` def/defonce source string to public."
  {:malli/schema [:=> [:cat :string] :string] :cost/ticks 50000}
  [^String src]
  (-> src
      (str/replace-first #"^\(\s*defn-\s+" "(defn ")
      (str/replace-first #"^\(\s*(def|defonce)\s+\^:private\s+" "($1 ")
      (str/replace-first #"^\(\s*(def|defonce)\s+\^:seon\.flow/edge\s+\^:private\s+" "($1 ^:seon.flow/edge ")
      (str/replace-first #"^\(\s*(def|defonce)\s+\^:private\s+\^:seon\.flow/edge\s+" "($1 ^:seon.flow/edge ")))
