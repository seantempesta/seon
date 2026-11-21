(ns seon.code.migrate
  "First-class Datahike-native CST graph refactoring, schema renaming, and live data/ref
   migration tools for Seon agents (`user/plan-rewrite`, `user/rewrite-graph!`,
   `user/rename-ns!`, `user/rename-schema!`, `user/migrate-data!`, `user/validate-migration`)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [datahike.api :as d]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [seon.code :as code]
            [seon.code.parse :as parse]
            [seon.health :as health]
            [seon.schema :as schema]))

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

(defn- string-literal-node? [nd]
  (let [t (n/tag nd)]
    (or (= t :multi-line)
        (and (= t :token) (str/starts-with? (n/string nd) "\"")))))

(defn- rewrite-node [nd plan env mentions]
  (cond
    (n/whitespace-or-comment? nd) [nd 0]
    (= :namespaced-map (n/tag nd)) (rewrite-ns-map-node nd plan env mentions)

    (string-literal-node? nd)
    (let [s (n/string nd)]
      (when (some #(str/includes? s %) (:needles plan))
        (vswap! mentions conj (if (> (count s) 90) (str (subs s 0 90) "…") s)))
      [nd 0])

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
                (let [pat (re-pattern (str "(?<![A-Za-z0-9_\\.\\-/])"
                                           (java.util.regex.Pattern/quote old-s)
                                           "(?![A-Za-z0-9_\\-])"))]
                  (str/replace s pat (escape-regex-repl new-s)))))
            md
            pairs)))

(defn- src-may-match? [^String s needles]
  (boolean (and s (some #(str/includes? s %) needles))))

(defn- renamed-ns-path [old-path new-ns]
  (let [default-p (parse/ns->default-path new-ns)]
    (cond
      (and old-path (str/starts-with? old-path "test/") (not (str/starts-with? default-p "test/")))
      (str/replace-first default-p #"^src/" "test/")
      (and old-path (str/starts-with? old-path "src/") (not (str/starts-with? default-p "src/")))
      (str/replace-first default-p #"^test/" "src/")
      :else default-p)))

(defn- check-plan-collisions [db np]
  (let [ns-renames (:ns-renames np)
        existing-nss (into #{} (map :v) (d/datoms db :aevt :seon.code.ns/name))
        existing-schemas (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))
        ns-cols (for [[o n] ns-renames
                      :when (and (contains? existing-nss n) (not (contains? ns-renames n)))]
                  {:kind :ns-collision :from o :to n})
        dup-ns (for [[target srcs] (group-by val ns-renames) :when (> (count srcs) 1)]
                 {:kind :duplicate-ns-target :to target :from (mapv key srcs)})
        missing-ns (for [o (keys ns-renames) :when (not (contains? existing-nss o))]
                     {:kind :source-ns-missing :ns o})
        sch-cols (for [k existing-schemas
                       :let [k' (rewrite-kw k np)]
                       :when (and (not= k' k)
                                  (contains? existing-schemas k')
                                  (= k' (rewrite-kw k' np)))]
                   {:kind :schema-collision :from k :to k'})]
    (vec (concat ns-cols dup-ns missing-ns sch-cols))))

(defn- scan-code-rewrites [db np]
  (let [needles (:needles np)
        ns-renames (:ns-renames np)
        all-nss (->> (d/datoms db :aevt :seon.code.ns/name) (map :v) sort vec)]
    (reduce
     (fn [acc nsym]
       (let [old-path (:seon.code.ns/path (d/pull db '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
             target-ns (get ns-renames nsym)
             ns-src (when old-path (code/render-path db old-path))]
         (if (and ns-src (or target-ns (src-may-match? ns-src needles)))
           (let [r (rewrite-clj-source ns-src np)
                 new-path (if target-ns (renamed-ns-path old-path target-ns) old-path)]
             (cond-> acc
               (not (:ok? r)) (update :errors conj {:ns nsym :error (:message r)})
               (and (:ok? r) (or target-ns (:changed? r)))
               (update :namespaces conj {:from-ns nsym :to-ns (or target-ns nsym)
                                         :old-path old-path :new-path new-path
                                         :renamed? (boolean target-ns)
                                         :changes (:changes r) :src (:src r)})
               (seq (:string-mentions r))
               (update :string-mentions conj {:ns nsym :mentions (:string-mentions r)})))
           acc)))
     {:namespaces [] :errors [] :string-mentions []}
     all-nss)))

(defn- scan-schema-rewrites [db np]
  (if-not (:rewrite-schemas? np)
    []
    (vec
     (keep (fn [dt]
             (let [ent (d/pull db '[:seon.schema/key :seon.schema/form :seon.schema/doc
                                    :seon.schema/examples :seon.schema/render] (:e dt))
                   k (:seon.schema/key ent)
                   k' (rewrite-kw k np)
                   f' (rewrite-edn-data (:seon.schema/form ent) np)
                   r' (when (:seon.schema/render ent) (rewrite-sym (:seon.schema/render ent) np))
                   d' (when (:seon.schema/doc ent) (rewrite-doc-text (:seon.schema/doc ent) np))]
               (when (or (not= k' k) (not= f' (:seon.schema/form ent))
                         (not= r' (:seon.schema/render ent)) (not= d' (:seon.schema/doc ent)))
                 {:old-key k :new-key k'
                  :entity (cond-> {:seon.schema/key k' :seon.schema/form f'}
                            d' (assoc :seon.schema/doc d')
                            (:seon.schema/examples ent) (assoc :seon.schema/examples (:seon.schema/examples ent))
                            r' (assoc :seon.schema/render r'))})))
           (d/datoms db :aevt :seon.schema/key)))))

(defn- scan-entity-rewrites [db attr-id pull-spec rewrite-fn]
  (if-not (contains? (:schema db) attr-id)
    []
    (vec
     (keep (fn [dt]
             (let [ent (dissoc (d/pull db pull-spec (:e dt)) :db/id)
                   ent' (rewrite-fn ent)]
               (when (and ent' (not= ent' ent))
                 {:id (get ent attr-id) :before ent :after ent'})))
           (d/datoms db :aevt attr-id)))))

(defn plan-rewrite
  "Dry-run analyzer for a graph-wide rewrite plan: checks collisions and reports every
   affected namespace, schema, rule, task, agent, doc section, and string literal mention."
  {:malli/schema [:=> [:cat :seon.host/db :map] :map]}
  [db plan]
  (let [np (normalize-plan plan)
        collisions (check-plan-collisions db np)
        code-scan (scan-code-rewrites db np)
        schemas (scan-schema-rewrites db np)
        rules (if (:rewrite-rules? np)
                (scan-entity-rewrites db :seon.rule/id
                                      '[:seon.rule/id :seon.rule/when :seon.rule/assert :seon.rule/doc]
                                      (fn [e]
                                        (cond-> e
                                          (:seon.rule/when e) (update :seon.rule/when rewrite-edn-data np)
                                          (:seon.rule/assert e) (update :seon.rule/assert rewrite-edn-data np)
                                          (:seon.rule/doc e) (update :seon.rule/doc rewrite-doc-text np))))
                [])
        tasks (if (:rewrite-tasks? np)
                (scan-entity-rewrites db :seon.task/id
                                      '[:seon.task/id :seon.task/scope :seon.task/tests :seon.task/plan]
                                      (fn [e]
                                        (cond-> e
                                          (:seon.task/scope e) (update :seon.task/scope rewrite-sym np)
                                          (:seon.task/tests e) (update :seon.task/tests #(set (map (fn [s] (rewrite-sym s np)) %)))
                                          (:seon.task/plan e) (update :seon.task/plan rewrite-doc-text np))))
                [])
        agents (if (:rewrite-agents? np)
                 (scan-entity-rewrites db :seon.agent/id
                                       '[:seon.agent/id :seon.agent/scope :seon.agent/render-fn
                                         :seon.agent/tail-renders :seon.agent/pinned]
                                       (fn [e]
                                         (cond-> e
                                           (:seon.agent/scope e) (update :seon.agent/scope rewrite-sym np)
                                           (:seon.agent/render-fn e) (update :seon.agent/render-fn rewrite-sym np)
                                           (:seon.agent/tail-renders e) (update :seon.agent/tail-renders rewrite-edn-data np)
                                           (:seon.agent/pinned e) (update :seon.agent/pinned rewrite-edn-data np))))
                 [])
        docs (if (:rewrite-docs? np)
               (scan-entity-rewrites db :seon.code.doc/id
                                     '[:seon.code.doc/id :seon.code.doc/path :seon.code.doc/heading :seon.code.doc/body]
                                     (fn [e]
                                       (cond-> e
                                         (:seon.code.doc/heading e) (update :seon.code.doc/heading rewrite-doc-text np)
                                         (:seon.code.doc/body e) (update :seon.code.doc/body rewrite-doc-text np))))
               [])]
    {:ok? (and (empty? collisions) (empty? (:errors code-scan)))
     :collisions collisions
     :parse-errors (:errors code-scan)
     :counts {:namespaces (count (:namespaces code-scan))
              :renamed-namespaces (count (filter :renamed? (:namespaces code-scan)))
              :schemas (count schemas)
              :rules (count rules)
              :tasks (count tasks)
              :agents (count agents)
              :docs (count docs)
              :string-mention-nss (count (:string-mentions code-scan))}
     :namespaces (mapv #(dissoc % :src) (:namespaces code-scan))
     :schemas (mapv #(select-keys % [:old-key :new-key]) schemas)
     :rules (mapv :id rules)
     :tasks (mapv :id tasks)
     :agents (mapv :id agents)
     :docs (mapv :id docs)
     :string-mentions (:string-mentions code-scan)
     :_internal {:plan np :code-scan code-scan :schemas schemas
                 :rules rules :tasks tasks :agents agents :docs docs}}))

(defn- retract-ns-ops [db nsym]
  (concat (map #(vector :db/retractEntity [:seon.code.form/key (:seon.code.form/key %)]) (code/ns-forms db nsym))
          (map #(vector :db/retractEntity [:seon.code.var/name (:seon.code.var/name %)]) (code/ns-vars db nsym))
          [[:db/retractEntity [:seon.code.ns/name nsym]]]))

(defn build-rewrite-tx
  "Builds `{:ok? :dh-decls :schema-tx :code-tx :retracted-nss :touched-nss :summary}` from `db` and `plan`."
  {:malli/schema [:=> [:cat :seon.host/db :map] :map]}
  [db plan]
  (let [dry (plan-rewrite db plan)]
    (if-not (:ok? dry)
      (dissoc dry :_internal)
      (let [{:keys [code-scan schemas rules tasks agents docs]} (:_internal dry)
            new-forms-map (into {} (map (fn [{:keys [new-key entity]}] [new-key (:seon.schema/form entity)])) schemas)
            reg-info (when (seq new-forms-map) (schema/registry-for-db db new-forms-map))
            dh-decls (if reg-info
                       (->> (:declarations reg-info)
                            (remove #(contains? (:schema db) (:db/ident %)))
                            vec)
                       [])
            schema-tx (vec (mapcat (fn [{:keys [old-key new-key entity]}]
                                     (cond-> [entity]
                                       (not= old-key new-key)
                                       (conj [:db/retractEntity [:seon.schema/key old-key]])))
                                   schemas))
            renamed-froms (into #{} (comp (filter :renamed?) (map :from-ns)) (:namespaces code-scan))
            retract-ops (vec (mapcat #(retract-ns-ops db %) renamed-froms))
            ns-ops (vec (mapcat (fn [{:keys [new-path src renamed?]}]
                                  (code/file-import-tx (when-not renamed? db) new-path src false))
                                (:namespaces code-scan)))
            task-ops (vec (mapcat (fn [{:keys [id before after]}]
                                    (let [gone (set/difference (set (:seon.task/tests before)) (set (:seon.task/tests after)))]
                                      (concat (for [g gone] [:db/retract [:seon.task/id id] :seon.task/tests g])
                                              [after])))
                                  tasks))
            other-ops (vec (concat (map :after rules) task-ops (map :after agents) (map :after docs)))]
        {:ok? true
         :dh-decls dh-decls
         :schema-tx schema-tx
         :code-tx (vec (concat retract-ops ns-ops other-ops))
         :retracted-nss (vec (sort renamed-froms))
         :touched-nss (vec (sort (map :to-ns (:namespaces code-scan))))
         :summary (dissoc dry :_internal)}))))

(defn migrate-entities-tx
  "Builds live Datahike entity migration tx-data for:
   1. Attribute renames (`:attr-renames`, `:attr-ns-renames`) across existing stored datoms.
   2. Scalar -> `:db.type/ref` upgrades via `:ref-migrations {old-attr {:to new-attr :lookup-attr :seon.ns/name :retract-old? true :on-missing :skip}}`.
   Never crashes on dangling refs; reports them in `:dangling`."
  {:malli/schema [:=> [:cat :seon.host/db :map] :map]}
  [db spec]
  (let [np (normalize-plan spec)
        retract-old? (if (contains? spec :retract-old?) (boolean (:retract-old? spec)) true)
        db-schema (:schema db)
        ref-migs (:ref-migrations np)
        ops (volatile! [])
        dangling (volatile! [])
        migrated (volatile! 0)
        retracted (volatile! 0)]
    (doseq [[old-attr {:keys [to lookup-attr on-missing] :as rm}] ref-migs
            :when (contains? db-schema old-attr)
            :let [target-attr (or to old-attr)
                  rm-retract? (if (contains? rm :retract-old?) (boolean (:retract-old? rm)) retract-old?)
                  missing-mode (or on-missing :skip)]]
      (doseq [dt (d/datoms db :aevt old-attr)]
        (let [e (:e dt)
              v (:v dt)
              resolved (when (and lookup-attr (contains? db-schema lookup-attr))
                         (:e (first (d/datoms db :avet lookup-attr v))))]
          (if resolved
            (do
              (vswap! ops conj [:db/add e target-attr resolved])
              (vswap! migrated inc)
              (when (and rm-retract? (not= old-attr target-attr))
                (vswap! ops conj [:db/retract e old-attr v])
                (vswap! retracted inc)))
            (do
              (vswap! dangling conj {:e e :attr old-attr :value v :lookup lookup-attr})
              (when (= :error missing-mode)
                (throw (ex-info (str "Dangling ref during migration of " old-attr ": " (pr-str v))
                                {:e e :attr old-attr :value v :lookup lookup-attr}))))))))
    (doseq [old-a (keys db-schema)
            :when (and (qualified-keyword? old-a)
                       (not (contains? ref-migs old-a))
                       (not= old-a (rewrite-kw old-a np)))
            :let [new-a (rewrite-kw old-a np)]
            :when (contains? db-schema new-a)]
      (doseq [dt (d/datoms db :aevt old-a)]
        (let [e (:e dt)
              v (:v dt)
              v' (rewrite-edn-data v np)]
          (vswap! ops conj [:db/add e new-a v'])
          (vswap! migrated inc)
          (when retract-old?
            (vswap! ops conj [:db/retract e old-a v])
            (vswap! retracted inc)))))
    {:ok? true
     :tx-data @ops
     :migrated @migrated
     :retracted @retracted
     :dangling @dangling}))

(defn apply-rewrite!
  "Executes a graph-wide rewrite plan on `conn`. Pass `{:dry-run? true}` to preview safely,
   `{:migrate-data? true}` to also migrate live entity datoms after schema updates, and
   `{:reload? true}` (default) to hot-reload touched namespaces in topological order."
  {:seon.eval/budget-override {:ticks 1000000000 :bytes 2147483648}
   :malli/schema [:=> [:cat :seon.host/conn :map] :map]}
  [conn {:keys [dry-run? migrate-data? reload?] :or {reload? true} :as plan}]
  (if dry-run?
    (assoc (dissoc (plan-rewrite @conn plan) :_internal) :dry-run? true)
    (let [built (build-rewrite-tx @conn plan)]
      (if-not (:ok? built)
        built
        (do
          (when (seq (:dh-decls built))
            (d/transact conn {:tx-data (:dh-decls built)}))
          (when (seq (:schema-tx built))
            (d/transact conn {:tx-data (:schema-tx built)}))
          (when (seq (:code-tx built))
            (d/transact conn {:tx-data (:code-tx built)}))
          (let [mig-res (when migrate-data?
                          (let [m (migrate-entities-tx @conn plan)]
                            (when (seq (:tx-data m))
                              (d/transact conn {:tx-data (:tx-data m)}))
                            (dissoc m :tx-data)))
                db-after @conn
                load-errs
                (if reload?
                  (do
                    (doseq [old-ns (:retracted-nss built)]
                      (try (remove-ns old-ns) (catch Throwable _)))
                    (let [upserts (vec (mapcat #(code/ns-forms db-after %) (:touched-nss built)))]
                      (if (seq upserts)
                        (code/apply-overlay! db-after {:upserts upserts :retracted-keys #{}} true)
                        [])))
                  [])]
            (cond-> {:ok? (empty? load-errs)
                     :tx (:max-tx db-after)
                     :counts (get-in built [:summary :counts])
                     :retracted-nss (:retracted-nss built)
                     :touched-nss (:touched-nss built)
                     :string-mentions (get-in built [:summary :string-mentions])
                     :load-errors load-errs}
              mig-res (assoc :data-migration mig-res))))))))

(defn apply-migrate-data!
  "Applies `migrate-entities-tx` on `conn` (pass `{:dry-run? true}` to preview)."
  {:seon.eval/budget-override {:ticks 1000000000 :bytes 2147483648}
   :malli/schema [:=> [:cat :seon.host/conn :map] :map]}
  [conn {:keys [dry-run?] :as spec}]
  (let [res (migrate-entities-tx @conn spec)]
    (if dry-run?
      (assoc (dissoc res :tx-data) :dry-run? true :op-count (count (:tx-data res)))
      (let [r (when (seq (:tx-data res))
                (d/transact conn {:tx-data (:tx-data res)}))]
        (-> (dissoc res :tx-data)
            (assoc :tx (if r (:max-tx (:db-after r)) (:max-tx @conn))))))))

(defn validate-migration
  "Post-migration verification: compiles full Malli schema registry, checks all `:seon.schema/render`
   and `:seon.agent/render-fn` targets exist, and runs `seon.health/report` for error count."
  {:seon.eval/budget-override {:ticks 500000000 :bytes 1073741824}
   :malli/schema [:function
                  [:=> [:cat :seon.host/db] :map]
                  [:=> [:cat :seon.host/db :map] :map]]}
  ([db] (validate-migration db {}))
  ([db {:keys [check-health?] :or {check-health? true}}]
   (let [forms (schema/db-schema-forms db)
         schema-err (try (schema/compile-registry forms) nil
                         (catch Throwable ex (or (.getMessage ex) (str ex))))
         known-vars (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name))
         missing-renders
         (vec
          (concat
           (when (contains? (:schema db) :seon.schema/render)
             (keep (fn [dt]
                     (let [sym (:v dt)]
                       (when (and (symbol? sym) (not (contains? known-vars sym)) (nil? (try (resolve sym) (catch Throwable _ nil))))
                         {:kind :schema-render :key (:seon.schema/key (d/pull db '[:seon.schema/key] (:e dt))) :target sym})))
                   (d/datoms db :aevt :seon.schema/render)))
           (when (contains? (:schema db) :seon.agent/render-fn)
             (keep (fn [dt]
                     (let [sym (:v dt)]
                       (when (and (symbol? sym) (not (contains? known-vars sym)) (nil? (try (resolve sym) (catch Throwable _ nil))))
                         {:kind :agent-render :agent (:seon.agent/id (d/pull db '[:seon.agent/id] (:e dt))) :target sym})))
                   (d/datoms db :aevt :seon.agent/render-fn)))))
         hr (when check-health? (health/report db))
         h-errs (when hr (filterv #(= :error (:severity %)) (:problems hr)))]
     {:ok? (and (nil? schema-err) (empty? missing-renders) (empty? h-errs))
      :schema-count (count forms)
      :schema-error schema-err
      :missing-renderers missing-renders
      :health-ok? (if hr (:ok? hr) true)
      :health-error-count (count h-errs)
      :health-errors (vec (take 10 h-errs))})))

(defn- intern-helper! [u sym f doc-str budget]
  (let [v (intern u sym f)]
    (alter-meta! v merge (cond-> {:doc doc-str} budget (assoc :seon.eval/budget-override budget)))
    v))

(defn install-user-helpers!
  "Interns `user/{plan-rewrite,rewrite-graph!,rename-ns!,rename-schema!,migrate-data!,validate-migration}`
   with proper `:doc` and `:seon.eval/budget-override` so any Seon agent can refactor the graph safely."
  {:malli/schema [:=> [:cat :seon.host/ns :any :any] :any]}
  [u get-agent-ctx-fn refresh-ctx-fn]
  (let [big-budget {:ticks 1000000000 :bytes 2147483648}]
    (intern-helper! u 'plan-rewrite
                    (fn [plan]
                      (when-let [{:keys [conn]} (get-agent-ctx-fn)]
                        (dissoc (plan-rewrite @conn plan) :_internal)))
                    "Dry-run graph rewrite plan (collisions + impact across code/schemas/rules/tasks/agents/docs)."
                    big-budget)
    (intern-helper! u 'rewrite-graph!
                    (fn [plan]
                      (when-let [{:keys [conn]} (get-agent-ctx-fn)]
                        (let [res (apply-rewrite! conn plan)]
                          (when (and (:ok? res) (not (:dry-run? res)) refresh-ctx-fn)
                            (refresh-ctx-fn @conn nil))
                          res)))
                    "Atomically rewrites namespaces, vars, schemas, rules, tasks, agents, and docs across the Datahike graph."
                    big-budget)
    (intern-helper! u 'rename-ns!
                    (fn
                      ([old-ns new-ns] ((resolve 'user/rename-ns!) old-ns new-ns {}))
                      ([old-ns new-ns opts]
                       ((resolve 'user/rewrite-graph!) (merge opts {:ns-renames {old-ns new-ns}}))))
                    "Renames a Clojure namespace and updates all callers, schemas, rules, tasks, agents, and docs."
                    big-budget)
    (intern-helper! u 'rename-schema!
                    (fn
                      ([old-k new-k] ((resolve 'user/rename-schema!) old-k new-k {}))
                      ([old-k new-k opts]
                       (let [plan (if (and (qualified-keyword? old-k) (qualified-keyword? new-k))
                                    {:attr-renames {old-k new-k}}
                                    {:attr-ns-renames {(str old-k) (str new-k)}})]
                         ((resolve 'user/rewrite-graph!) (merge opts plan)))))
                    "Renames a schema/attribute keyword or attribute namespace prefix across the entire Datahike graph."
                    big-budget)
    (intern-helper! u 'migrate-data!
                    (fn [spec]
                      (when-let [{:keys [conn]} (get-agent-ctx-fn)]
                        (let [res (apply-migrate-data! conn spec)]
                          (when (and (:ok? res) (not (:dry-run? res)) refresh-ctx-fn)
                            (refresh-ctx-fn @conn nil))
                          res)))
                    "Migrates live Datahike entity datoms (attribute renames & scalar->ref upgrades) without crashing on dangling refs."
                    big-budget)
    (intern-helper! u 'validate-migration
                    (fn
                      ([] ((resolve 'user/validate-migration) {}))
                      ([opts]
                       (when-let [{:keys [conn]} (get-agent-ctx-fn)]
                         (validate-migration @conn opts))))
                    "Validates schema registry compilation, renderer var targets, and health after a graph migration."
                    big-budget)))
