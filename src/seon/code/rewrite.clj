(ns seon.code.rewrite
  "Pure CST/EDN/Markdown graph rewriters for Seon refactoring (`rewrite-clj-source`,
   `rewrite-edn-data`, `rewrite-doc-text`, `normalize-plan`, `rewrite-kw`, `rewrite-sym`).
   Zero Datahike dependency; lossless over comments, whitespace, aliases, and namespaced maps."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(defn- safe-sym [x]
  (cond
    (symbol? x) x
    (and (string? x) (not (str/blank? x))) (symbol x)
    (keyword? x) (symbol (namespace x) (name x))
    :else nil))

(defn- safe-qkw [x]
  (cond
    (qualified-keyword? x) x
    (and (string? x) (str/includes? x "/"))
    (let [s (if (str/starts-with? x ":") (subs x 1) x)
          [a b] (str/split s #"/" 2)]
      (when (and (not (str/blank? a)) (not (str/blank? b)))
        (keyword a b)))
    :else nil))

(defn- safe-ns-str [x]
  (cond
    (string? x) (let [s (if (str/starts-with? x ":") (subs x 1) x)]
                  (when-not (str/blank? s) s))
    (symbol? x) (str x)
    (keyword? x) (if (namespace x) (str (namespace x) "." (name x)) (name x))
    :else nil))

(defn normalize-plan
  "Normalizes a declarative graph-rewrite plan map into canonical symbol/keyword/string maps
   plus sorted search needles for fast pre-filtering."
  {:malli/schema [:=> [:cat :map] :map]
   :cost/ticks 5000000}
  [plan]
  (let [ns-renames (into {}
                         (keep (fn [[k v]]
                                 (let [ks (safe-sym k) vs (safe-sym v)]
                                   (when (and ks vs (nil? (namespace ks)) (nil? (namespace vs)) (not= ks vs))
                                     [ks vs]))))
                         (when (map? (:ns-renames plan)) (:ns-renames plan)))
        var-renames (into {}
                          (keep (fn [[k v]]
                                  (let [ks (safe-sym k) vs (safe-sym v)]
                                    (when (and ks vs (namespace ks) (namespace vs) (not= ks vs))
                                      [ks vs]))))
                          (when (map? (:var-renames plan)) (:var-renames plan)))
        attr-ns-renames (into {}
                              (keep (fn [[k v]]
                                      (let [ks (safe-ns-str k) vs (safe-ns-str v)]
                                        (when (and ks vs (not= ks vs))
                                          [ks vs]))))
                              (when (map? (:attr-ns-renames plan)) (:attr-ns-renames plan)))
        attr-renames (into {}
                           (keep (fn [[k v]]
                                   (let [kk (safe-qkw k) vk (safe-qkw v)]
                                     (when (and kk vk (not= kk vk))
                                       [kk vk]))))
                           (when (map? (:attr-renames plan)) (:attr-renames plan)))
        doc-renames (into {}
                          (keep (fn [[k v]]
                                  (when (and (string? k) (string? v) (not (str/blank? k)) (not= k v))
                                    [k v])))
                          (when (map? (:doc-renames plan)) (:doc-renames plan)))
        ns-prefixes (vec (sort-by (comp - count first)
                                  (filter #(str/ends-with? (first %) ".") attr-ns-renames)))
        needles (->> (concat (map str (keys ns-renames))
                             (map str (keys var-renames))
                             (map #(subs (str %) 1) (keys attr-renames))
                             (map #(if (str/ends-with? % ".") (subs % 0 (dec (count %))) %)
                                  (keys attr-ns-renames))
                             (keys doc-renames))
                     (remove str/blank?)
                     distinct
                     (sort-by (comp - count))
                     vec)]
    {:ns-renames ns-renames
     :var-renames var-renames
     :attr-ns-renames attr-ns-renames
     :attr-ns-prefixes ns-prefixes
     :attr-renames attr-renames
     :doc-renames doc-renames
     :ref-migrations (if (map? (:ref-migrations plan)) (:ref-migrations plan) {})
     :rewrite-strings? (boolean (:rewrite-strings? plan))
     :rewrite-docs? (if (contains? plan :rewrite-docs?) (boolean (:rewrite-docs? plan)) true)
     :rewrite-schemas? (if (contains? plan :rewrite-schemas?) (boolean (:rewrite-schemas? plan)) true)
     :rewrite-rules? (if (contains? plan :rewrite-rules?) (boolean (:rewrite-rules? plan)) true)
     :rewrite-tasks? (if (contains? plan :rewrite-tasks?) (boolean (:rewrite-tasks? plan)) true)
     :rewrite-agents? (if (contains? plan :rewrite-agents?) (boolean (:rewrite-agents? plan)) true)
     :needles needles}))

(defn rewrite-attr-ns
  "Rewrites a keyword namespace string using exact `:attr-ns-renames` or dot-suffix prefixes."
  {:malli/schema [:=> [:cat :string :map] :string]
   :cost/ticks 500000}
  [k-ns plan]
  (let [m (:attr-ns-renames plan)]
    (or (when (map? m) (get m k-ns))
        (some (fn [[pref repl]]
                (when (and (string? pref) (string? repl) (str/starts-with? k-ns pref))
                  (str repl (subs k-ns (count pref)))))
              (when (sequential? (:attr-ns-prefixes plan)) (:attr-ns-prefixes plan)))
        k-ns)))

(defn rewrite-kw
  "Rewrites a keyword according to normalized `plan` (`:attr-renames` overrides `:attr-ns-renames`)."
  {:malli/schema [:=> [:cat :keyword :map] :keyword]
   :cost/ticks 500000}
  [kw plan]
  (if-not (qualified-keyword? kw)
    kw
    (let [ar (:attr-renames plan)]
      (or (when (map? ar)
            (let [v (get ar kw)]
              (when (keyword? v) v)))
          (let [k-ns (namespace kw)
                new-ns (rewrite-attr-ns k-ns plan)]
            (if (and (string? new-ns) (not (str/blank? new-ns)) (not= new-ns k-ns))
              (keyword new-ns (name kw))
              kw))))))

(defn rewrite-sym
  "Rewrites a symbol according to normalized `plan` (`:var-renames` overrides `:ns-renames`)."
  {:malli/schema [:=> [:cat :symbol :map] :symbol]
   :cost/ticks 500000}
  [sym plan]
  (let [vr (when (map? (:var-renames plan)) (:var-renames plan))
        nr (when (map? (:ns-renames plan)) (:ns-renames plan))]
    (if-let [ns-s (namespace sym)]
      (or (let [v (get vr sym)] (when (symbol? v) v))
          (if-let [new-ns (let [v (get nr (symbol ns-s))] (when (symbol? v) v))]
            (symbol (name new-ns) (name sym))
            sym))
      (let [v (get nr sym)]
        (if (symbol? v) v sym)))))

(defn rewrite-edn-data
  "Recursively rewrites keywords and symbols inside arbitrary EDN data structures."
  {:malli/schema [:=> [:cat :any :map] :any]
   :cost/ticks 10000000}
  [data plan]
  (let [np (if (contains? plan :needles) plan (normalize-plan plan))]
    (if (and (empty? (:ns-renames np)) (empty? (:var-renames np))
             (empty? (:attr-renames np)) (empty? (:attr-ns-renames np)))
      data
      (walk/postwalk
       (fn [x]
         (cond
           (qualified-keyword? x) (rewrite-kw x np)
           (symbol? x) (rewrite-sym x np)
           :else x))
       data))))

(defn- extract-ns-env [root]
  (let [first-form (first (remove n/whitespace-or-comment? (n/children root)))
        sexpr (when (and first-form (= :list (n/tag first-form)))
                (try (n/sexpr first-form) (catch Throwable _ nil)))]
    (if (and (seq? sexpr) (= 'ns (first sexpr)) (symbol? (second sexpr)))
      (let [req-clauses (->> (drop 2 sexpr)
                             (filter #(and (seq? %) (= :require (first %))))
                             (mapcat rest))
            alias->ns (volatile! {})
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
        {:ns-sym (second sexpr)
         :alias->ns @alias->ns
         :ns->alias @ns->alias
         :refer->ns @refer->ns})
      {:ns-sym nil :alias->ns {} :ns->alias {} :refer->ns {}})))

(defn- rewrite-keyword-token [nd plan env]
  (let [s (n/string nd)]
    (cond
      (str/starts-with? s "::")
      (let [body (subs s 2)]
        (if (str/includes? body "/")
          (let [[al nm] (str/split body #"/" 2)
                al-sym (symbol al)
                resolved-ns (get (:alias->ns env) al-sym)]
            (if resolved-ns
              (let [orig-kw (keyword (name resolved-ns) nm)
                    new-kw (rewrite-kw orig-kw plan)]
                (if (= new-kw orig-kw)
                  [nd 0]
                  (let [new-ns-sym (symbol (namespace new-kw))
                        eff-old-ns (or (get (:ns-renames plan) resolved-ns) resolved-ns)]
                    (if (= new-ns-sym eff-old-ns)
                      [(n/keyword-node (keyword al (name new-kw)) true) 1]
                      [(n/keyword-node new-kw) 1]))))
              [nd 0]))
          [nd 0]))

      (and (str/starts-with? s ":") (str/includes? s "/"))
      (let [body (subs s 1)
            [k-ns k-nm] (str/split body #"/" 2)
            orig-kw (when (and (not (str/blank? k-ns)) (not (str/blank? k-nm)))
                      (keyword k-ns k-nm))
            new-kw (when orig-kw (rewrite-kw orig-kw plan))]
        (if (and new-kw (not= new-kw orig-kw))
          [(n/keyword-node new-kw) 1]
          [nd 0]))

      :else [nd 0])))

(defn- rewrite-symbol-token [nd plan env]
  (let [s (n/string nd)
        sym (try (symbol s) (catch Throwable _ nil))]
    (if-not (symbol? sym)
      [nd 0]
      (if-let [ns-s (namespace sym)]
        (let [ns-sym (symbol ns-s)
              al-target (get (:alias->ns env) ns-sym)]
          (if al-target
            (let [full-sym (symbol (name al-target) (name sym))
                  renamed (get (:var-renames plan) full-sym)]
              (if renamed
                (let [new-ns-sym (symbol (namespace renamed))
                      eff-al-ns (or (get (:ns-renames plan) al-target) al-target)]
                  (if (= new-ns-sym eff-al-ns)
                    [(n/token-node (symbol ns-s (name renamed))) 1]
                    (let [dest-al (get (:ns->alias env) new-ns-sym)]
                      [(n/token-node (symbol (name (or dest-al new-ns-sym)) (name renamed))) 1])))
                [nd 0]))
            (let [new-sym (rewrite-sym sym plan)]
              (if (not= new-sym sym)
                [(n/token-node new-sym) 1]
                [nd 0]))))
        (let [new-sym (or (get (:ns-renames plan) sym)
                          (when-let [ref-ns (get (:refer->ns env) sym)]
                            (when-let [renamed (get (:var-renames plan) (symbol (name ref-ns) (name sym)))]
                              (let [eff-ref-ns (or (get (:ns-renames plan) ref-ns) ref-ns)]
                                (if (= (symbol (namespace renamed)) eff-ref-ns)
                                  (symbol (name renamed))
                                  renamed)))))]
          (if (and new-sym (not= new-sym sym))
            [(n/token-node new-sym) 1]
            [nd 0]))))))

(defn- string-literal-node? [nd]
  (let [t (n/tag nd)]
    (or (= t :multi-line)
        (and (= t :token) (str/starts-with? (n/string nd) "\"")))))

(defn- escape-regex-repl [^String s]
  (-> s (str/replace "\\" "\\\\") (str/replace "$" "\\$")))

(defn rewrite-doc-text
  "Rewrites qualified keywords, namespace/var names, and doc paths in Markdown text cleanly
   using longest-match-first token-boundary regexes."
  {:malli/schema [:=> [:cat :string :map] :string]
   :cost/ticks 20000000}
  [md plan]
  (let [np (if (contains? plan :needles) plan (normalize-plan plan))
        pairs (->> (concat (map (fn [[k v]] [(str k) (str v)]) (:attr-renames np))
                           (map (fn [[k v]] [(str ":" k "/") (str ":" v "/")])
                                (remove #(str/ends-with? (first %) ".") (:attr-ns-renames np)))
                           (map (fn [[k v]] [(str ":" k) (str ":" v)])
                                (:attr-ns-prefixes np))
                           (map (fn [[k v]] [(str k) (str v)]) (:var-renames np))
                           (map (fn [[k v]] [(str k) (str v)]) (:ns-renames np))
                           (:doc-renames np))
                   (remove (fn [[a b]] (or (str/blank? a) (= a b))))
                   (sort-by (comp - count first)))]
    (reduce (fn [s [old-s new-s]]
              (if-not (str/includes? s old-s)
                s
                (let [prefix-end? (or (str/ends-with? old-s "/") (str/ends-with? old-s "."))
                    pat (re-pattern (str "(?<![A-Za-z0-9_\\.\\-/])"
                                         (java.util.regex.Pattern/quote old-s)
                                         (when-not prefix-end? "(?![A-Za-z0-9_\\-\\.])")))]
                (str/replace s pat (escape-regex-repl new-s)))))
            md
            pairs)))

(declare rewrite-node)

(defn- rewrite-ns-map-inner [map-nd qual-ns new-qual-ns plan env mentions]
  (let [kids (vec (n/children map-nd))
        changed (volatile! 0)
        idx (volatile! 0)
        new-kids
        (mapv (fn [ch]
                (if (n/whitespace-or-comment? ch)
                  ch
                  (let [pos @idx
                        _ (vswap! idx inc)
                        key-pos? (even? pos)]
                    (if (and key-pos? (n/keyword-node? ch))
                      (let [s (n/string ch)]
                        (if (and (str/starts-with? s ":") (not (str/starts-with? s "::")) (not (str/includes? s "/")))
                          (let [orig-kw (keyword qual-ns (subs s 1))
                                new-kw (rewrite-kw orig-kw plan)]
                            (cond
                              (= new-kw orig-kw) ch
                              (= (namespace new-kw) new-qual-ns)
                              (if (= (name new-kw) (name orig-kw))
                                ch
                                (do (vswap! changed inc) (n/keyword-node (keyword (name new-kw)))))
                              :else
                              (do (vswap! changed inc) (n/keyword-node new-kw))))
                          (let [[ch' c] (rewrite-node ch plan env mentions)]
                            (vswap! changed + c)
                            ch')))
                      (let [[ch' c] (rewrite-node ch plan env mentions)]
                        (vswap! changed + c)
                        ch')))))
              kids)]
    (if (pos? @changed)
      [(n/replace-children map-nd new-kids) @changed]
      [map-nd 0])))

(defn- rewrite-ns-map-node [nd plan env mentions]
  (let [kids (vec (n/children nd))
        q-idx (first (keep-indexed (fn [i ch] (when (= :map-qualifier (n/tag ch)) i)) kids))
        m-idx (first (keep-indexed (fn [i ch] (when (= :map (n/tag ch)) i)) kids))]
    (if (and q-idx m-idx)
      (let [qnd (nth kids q-idx)
            qstr (n/string qnd)
            auto? (str/starts-with? qstr "::")
            qual-ns (cond
                      (and auto? (> (count qstr) 2))
                      (when-let [r (get (:alias->ns env) (symbol (subs qstr 2)))] (name r))
                      auto? (when (:ns-sym env) (name (:ns-sym env)))
                      (str/starts-with? qstr ":") (subs qstr 1)
                      :else nil)
            new-qual-ns (if (and qual-ns (not auto?)) (rewrite-attr-ns qual-ns plan) qual-ns)
            q-changed? (and (not auto?) qual-ns (not= new-qual-ns qual-ns))
            qnd' (if q-changed? (n/map-qualifier-node false new-qual-ns) qnd)
            [mnd' mc] (if qual-ns
                        (rewrite-ns-map-inner (nth kids m-idx) qual-ns (or new-qual-ns qual-ns) plan env mentions)
                        (rewrite-node (nth kids m-idx) plan env mentions))
            tot (+ (if q-changed? 1 0) mc)]
        (if (pos? tot)
          [(n/replace-children nd (-> kids (assoc q-idx qnd') (assoc m-idx mnd'))) tot]
          [nd 0]))
      [nd 0])))

(defn- rewrite-node [nd plan env mentions]
  (cond
    (n/whitespace-or-comment? nd) [nd 0]
    (= :namespaced-map (n/tag nd)) (rewrite-ns-map-node nd plan env mentions)

    (string-literal-node? nd)
    (let [s (n/string nd)]
      (if (some #(str/includes? s %) (:needles plan))
        (if (:rewrite-strings? plan)
          (let [s2 (rewrite-doc-text s plan)]
            (if (not= s2 s)
              [(p/parse-string s2) 1]
              [nd 0]))
          (do (vswap! mentions conj (if (> (count s) 90) (str (subs s 0 90) "…") s))
              [nd 0]))
        [nd 0]))

    (n/keyword-node? nd) (rewrite-keyword-token nd plan env)
    (n/symbol-node? nd)  (rewrite-symbol-token nd plan env)

    (n/inner? nd)
    (let [kids (vec (n/children nd))
          changed (volatile! 0)
          new-kids (mapv (fn [ch]
                           (let [[ch' c] (rewrite-node ch plan env mentions)]
                             (vswap! changed + c)
                             ch'))
                         kids)]
      (if (pos? @changed)
        [(n/replace-children nd new-kids) @changed]
        [nd 0]))

    :else [nd 0]))

(defn rewrite-clj-source
  "Losslessly rewrites Clojure source string `src` via `rewrite-clj` CST according to `plan`.
   Preserves comments, whitespace, aliases, and string literals (reporting `:string-mentions`)."
  {:malli/schema [:=> [:cat :string :map] :map]
   :cost/ticks 20000000}
  [src plan]
  (let [np (if (contains? plan :needles) plan (normalize-plan plan))]
    (if (empty? (:needles np))
      {:ok? true :src src :changed? false :changes 0 :string-mentions []}
      (try
        (let [root (p/parse-string-all src)
              env (extract-ns-env root)
              mentions (volatile! [])
              [root' changes] (rewrite-node root np env mentions)
              out (if (pos? changes) (n/string root') src)]
          {:ok? true
           :src out
           :changed? (and (pos? changes) (not= out src))
           :changes changes
           :string-mentions (vec (distinct @mentions))})
        (catch Throwable ex
          {:ok? false
           :error :parse-error
           :message (or (.getMessage ex) (str ex))
           :src src
           :changed? false
           :changes 0
           :string-mentions []})))))
