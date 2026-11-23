(ns seon.code.refactor
  "Datahike-native lossless namespace refactoring & outline tools:
   1. `ns-form-outline`: inspects a namespace's forms with token size, visibility, intra-ns deps,
      and external callers in O(|forms|) without dumping 15k+ tokens of raw source.
   2. `move-vars-tx`: moves a cohesive set of forms from `from` into child namespace `to` losslessly
      using `:seon.code.form/src` in Datahike (zero manual re-typing, zero typos!), automatically
      checking unresolved intra-ns deps, promoting moved `defn-` helpers, updating `from`'s `(ns ...)`
      header with `:as`/`:refer`, and leaving 1-line re-exports for any externally referenced public vars."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.analyze :as analyze]))

(defn- var-key->sym [nsym k]
  (when (and k (not (str/includes? k "#")))
    (symbol (name nsym) (second (str/split k #"/" 2)))))

(defn ns-form-outline
  "Returns a compact `{:ns :total-tokens :form-count :forms [...]}` for `nsym` in `db`,
   including `:sym`, `:key`, `:lines`, `:chars`, `:tokens` and omitting empty optional keys
   so agents plan clean edits and namespace splits with minimal token overhead."
  {:malli/schema [:=> [:cat :seon.host/db :any] :map]}
  [db nsym]
  (let [nsym (if (string? nsym) (symbol nsym) nsym)
        forms (code/ns-forms db nsym)
        vars-by-k (into {} (map (juxt :seon.code.var/form identity)) (code/ns-vars db nsym))
        ns-str (name nsym)
        rows (mapv (fn [f]
                     (let [k (:seon.code.form/key f)
                           v (get vars-by-k k)
                           vsym (:seon.code.var/name v)
                           fsrc (str (:seon.code.form/src f))
                           chars (count fsrc)
                           lines (if (str/blank? fsrc) 0 (count (str/split-lines fsrc)))
                           local-deps (->> (:seon.code.form/deps f)
                                           (filter #(= ns-str (namespace %)))
                                           (remove #(= "#ns" (name %)))
                                           (map #(symbol (name %)))
                                           sort vec)
                           ext-callers (when vsym
                                         (->> (d/datoms db :avet :seon.code.var/refs vsym)
                                              (keep (fn [dt]
                                                      (let [cv (d/pull db (quote [:seon.code.var/name :seon.code.var/ns]) (:e dt))]
                                                        (when (and (:seon.code.var/name cv)
                                                                   (not= nsym (:seon.code.var/ns cv)))
                                                          (:seon.code.var/name cv)))))
                                              distinct sort vec))]
                       (cond-> {:sym (if vsym (symbol (name vsym)) :ns)
                                :key k
                                :lines lines
                                :chars chars
                                :tokens (quot chars 4)}
                         (:seon.code.var/private? v) (assoc :private? true)
                         (seq local-deps)            (assoc :local-deps local-deps)
                         (seq ext-callers)           (assoc :callers (vec (take 6 ext-callers))
                                                            :caller-count (count ext-callers)))))
                   forms)]
    {:ns nsym
     :total-tokens (reduce + 0 (map :tokens rows))
     :form-count (count rows)
     :forms rows}))

(defn- promote-defn-private [^String src]
  (str/replace-first src #"^\(\s*defn-\s+" "(defn "))

(defn- extract-require-clauses [^String ns-src]
  (let [form (try (read-string ns-src) (catch Throwable _ nil))]
    (when (and (seq? form) (= 'ns (first form)))
      (->> (drop 2 form)
           (filter #(and (seq? %) (= :require (first %))))
           (mapcat rest)
           vec))))

(defn- build-ns-header-src [to-ns doc requires]
  (let [req-lines (when (seq requires)
                    (str "\n  (:require "
                         (str/join "\n            " (map pr-str requires))
                         ")"))]
    (str "(ns " to-ns
         (when-not (str/blank? (str doc))
           (str "\n  " (pr-str (str doc))))
         req-lines
         ")")))

(defn- escape-regex-replacement [^String s]
  (let [sb (StringBuilder. (+ (.length s) 8))]
    (dotimes [i (.length s)]
      (let [c (.charAt s i)]
        (when (or (= c \\) (= c \$))
          (.append sb \\))
        (.append sb c)))
    (.toString sb)))

(defn- add-require-to-ns-src
  "Adds (or updates, unioning existing :refer symbols) a `[to-ns ...]` clause in `ns-src` cleanly."
  [^String ns-src to-ns alias-sym refer-syms]
  (let [existing-re (re-pattern (str "\\[\\s*" (java.util.regex.Pattern/quote (name to-ns)) "\\b(?:[^\\[\\]]|\\[[^\\]]*\\])*\\]"))
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
      (str/replace-first ns-src existing-re (escape-regex-replacement clause-str))

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

(defn- callable-var-src? [^String src]
  (boolean (re-find #"\^:seon/callable|\{\s*:seon/callable\s+true" (str src))))

(defn- make-reexport-src
  ([vsym alias-sym orig-src] (make-reexport-src vsym alias-sym orig-src false))
  ([vsym alias-sym orig-src private?]
   (let [vname (name vsym)
         target (str (name alias-sym) "/" vname)
         trimmed (str/triml (str orig-src))
         is-fn? (boolean (re-find #"^\(\s*defn-?\b" trimmed))
         meta-tags (str (when (and (not private?) (callable-var-src? orig-src)) "^:seon/callable ")
                        (when (str/includes? (str orig-src) ":seon.flow/edge") "^:seon.flow/edge "))]
     (cond
       (and is-fn? private?) (str "(defn- " vname " [& args] (apply " target " args))")
       is-fn?                (str "(defn " meta-tags vname " [& args] (apply " target " args))")
       private?              (str "(def ^:private " vname " " target ")")
       :else                 (str "(def " meta-tags vname " " target ")")))))

(defn- promote-private-src [^String src]
  (-> src
      (str/replace-first #"^\(\s*defn-\s+" "(defn ")
      (str/replace-first #"^\(\s*(def|defonce)\s+\^:private\s+" "($1 ")
      (str/replace-first #"^\(\s*(def|defonce)\s+\^:seon\.flow/edge\s+\^:private\s+" "($1 ^:seon.flow/edge ")
      (str/replace-first #"^\(\s*(def|defonce)\s+\^:private\s+\^:seon\.flow/edge\s+" "($1 ^:seon.flow/edge ")))

(defn- expand-local-dep-closure [from found-by-key seed-keys]
  (let [ns-str (name from)
        ns-hdr-k (str ns-str "#ns")]
    (loop [queue (vec seed-keys)
           seen (set seed-keys)]
      (if (empty? queue)
        seen
        (let [k (peek queue)
              q' (pop queue)
              deps (get-in found-by-key [k :seon.code.form/deps])
              local-keys (keep (fn [dep]
                                 (when (= ns-str (namespace dep))
                                   (let [dk (str ns-str "/" (name dep))]
                                     (when (and (not= dk ns-hdr-k)
                                                (contains? found-by-key dk)
                                                (not (contains? seen dk)))
                                       dk))))
                               deps)]
          (recur (into q' local-keys) (into seen local-keys)))))))

(defn- dynamic-var-src? [^String src]
  (boolean (re-find #"\^:dynamic|\{\s*:dynamic\s+true" (str src))))

(defn- add-refers-to-clauses [req-clauses refers-by-ns]
  (if (empty? refers-by-ns)
    req-clauses
    (mapv (fn [c]
            (if-let [rs (and (vector? c) (get refers-by-ns (first c)))]
              (let [existing (some (fn [[k v]] (when (and (= :refer k) (coll? v)) (set v)))
                                   (partition 2 1 (rest c)))
                    merged (vec (sort (into (or existing #{}) rs)))]
                (if existing
                  (loop [xs c acc []]
                    (cond
                      (empty? xs) acc
                      (= :refer (first xs)) (recur (nnext xs) (conj acc :refer merged))
                      :else (recur (rest xs) (conj acc (first xs)))))
                  (conj c :refer merged)))
              c))
          req-clauses)))

(defn- resolve-alias-ns [req-clauses alias-sym]
  (some (fn [c]
          (when (and (vector? c) (symbol? (first c)))
            (some (fn [[k v]] (when (and (= :as k) (= alias-sym v)) (first c)))
                  (partition 2 1 (rest c)))))
        req-clauses))

(defn- stub-target-alias-ns
  "If `form-src` is a 1-line delegating stub `(defn-? name [& args] (apply alias/name args))` or
   `(def ... name alias/name)`, returns `[alias-sym var-simple-sym]`, else nil."
  [^String form-src]
  (when form-src
    (let [t (str/trim form-src)]
      (or (when-let [[_ v a v2] (re-matches #"\(defn-?\s+(?:\^[^\s]+\s+)*([^\s]+)\s+\[& args\]\s+\(apply\s+([^\s/]+)/([^\s\)]+)\s+args\)\)" t)]
            (when (= v v2) [(symbol a) (symbol v)]))
          (when-let [[_ v a v2] (re-matches #"\(def\s+(?:\^[^\s]+\s+)*([^\s]+)\s+([^\s/]+)/([^\s\)]+)\)" t)]
            (when (= v v2) [(symbol a) (symbol v)]))))))

(defn move-vars-tx
  "Builds an atomic O(|moved|) Datahike transaction moving `vars` from `from` to child namespace `to`
   without re-parsing untouched forms in `from`. Automatically resolves remaining 1-line forwarding
   stubs in `from` into `:refer` clauses on `to`'s inherited requires so multi-step layered splits
   (`data` -> `markdown` -> `tiles` -> `debug` -> `canvas`) flow cleanly downward with zero manual plumbing!"
  {:malli/schema [:=> [:cat :seon.host/db :map] :map]}
  [db {:keys [from to vars alias doc requires re-export-public? keep-private? include-deps?]
       :or {re-export-public? true keep-private? false include-deps? false}}]
  (let [from (if (string? from) (symbol from) from)
        to (if (string? to) (symbol to) to)
        var-syms (mapv #(symbol (name %)) vars)
        alias-sym (or (when alias (symbol (name alias)))
                      (symbol (last (str/split (name to) #"\."))))
        from-forms (code/ns-forms db from)
        from-vars (into {} (map (juxt :seon.code.var/form identity)) (code/ns-vars db from))
        ns-str (name from)
        seed-keys (into #{} (map #(str ns-str "/" (name %))) var-syms)
        found-by-key (into {} (map (juxt :seon.code.form/key identity)) from-forms)
        missing (vec (remove #(contains? found-by-key %) (map #(str ns-str "/" (name %)) var-syms)))]
    (cond
      (empty? from-forms)
      {:ok? false :error :source-ns-not-found :from from}

      (empty? var-syms)
      {:ok? false :error :no-vars-specified}

      (seq missing)
      {:ok? false :error :vars-not-found :missing missing}

      :else
      (let [from-ns-hdr (first (filter #(= (str ns-str "#ns") (:seon.code.form/key %)) from-forms))
            inherited-reqs (->> (extract-require-clauses (:seon.code.form/src from-ns-hdr ""))
                                (remove #(and (vector? %) (= to (first %))))
                                vec)
            ;; Map any 1-line forwarding stub remaining in `from` to its real owning child namespace!
            stub-ns-by-sym
            (into {}
                  (keep (fn [f]
                          (when-let [[a-sym v-sym] (stub-target-alias-ns (:seon.code.form/src f))]
                            (when-let [real-ns (resolve-alias-ns inherited-reqs a-sym)]
                              [(symbol ns-str (name v-sym)) [real-ns v-sym]]))))
                  from-forms)
            wanted-keys (if include-deps?
                          (expand-local-dep-closure from found-by-key seed-keys)
                          seed-keys)
            ;; Don't accidentally pull 1-line stubs when using :include-deps? unless explicitly named in seed-keys!
            wanted-keys (if include-deps?
                          (into #{} (remove (fn [k]
                                              (and (not (contains? seed-keys k))
                                                   (contains? stub-ns-by-sym (var-key->sym from k)))))
                                wanted-keys)
                          wanted-keys)
            moved-forms (filterv #(contains? wanted-keys (:seon.code.form/key %)) from-forms)
            remaining-forms (filterv #(not (contains? wanted-keys (:seon.code.form/key %))) from-forms)
            remaining-var-syms (into #{}
                                     (keep #(var-key->sym from (:seon.code.form/key %)))
                                     remaining-forms)
            raw-unresolved (->> moved-forms
                                (mapcat :seon.code.form/deps)
                                (filter #(contains? remaining-var-syms %))
                                distinct sort vec)
            stub-refers-by-ns (reduce (fn [acc dep-sym]
                                        (if-let [[real-ns v-sym] (get stub-ns-by-sym dep-sym)]
                                          (update acc real-ns (fnil conj #{}) v-sym)
                                          acc))
                                      {}
                                      raw-unresolved)
            unresolved-deps (vec (remove #(contains? stub-ns-by-sym %) raw-unresolved))]
        (if (seq unresolved-deps)
          {:ok? false
           :error :unresolved-from-deps
           :missing-deps unresolved-deps
           :hint "Moved forms still call these vars left in source ns; include them in :vars or pass :include-deps? true."}
          (let [rem-deps-set (into #{} (mapcat :seon.code.form/deps) remaining-forms)
                re-exported-pub
                (if re-export-public?
                  (into #{}
                        (keep (fn [f]
                                (let [k (:seon.code.form/key f)
                                      v (get from-vars k)
                                      vsym (:seon.code.var/name v)]
                                  (when (and vsym
                                             (not (:seon.code.var/private? v))
                                             (not (dynamic-var-src? (:seon.code.form/src f))))
                                    (symbol (name vsym))))))
                        moved-forms)
                  #{})
                re-exported-priv
                (into #{}
                      (keep (fn [f]
                              (let [k (:seon.code.form/key f)
                                    vsym (var-key->sym from k)
                                    simple (when vsym (symbol (name vsym)))]
                                (when (and simple
                                           (not (contains? re-exported-pub simple))
                                           (not (dynamic-var-src? (:seon.code.form/src f)))
                                           (contains? rem-deps-set vsym))
                                  simple))))
                      moved-forms)
                all-stubbed (into re-exported-pub re-exported-priv)
                referred-syms
                (into #{}
                      (keep (fn [f]
                              (let [k (:seon.code.form/key f)
                                    vsym (var-key->sym from k)
                                    simple (when vsym (symbol (name vsym)))]
                                (when (and simple
                                           (not (contains? all-stubbed simple))
                                           (dynamic-var-src? (:seon.code.form/src f)))
                                  simple))))
                      moved-forms)
                existing-to-forms (code/ns-forms db to)
                to-reqs (add-refers-to-clauses (if (some? requires) requires inherited-reqs) stub-refers-by-ns)
                to-hdr-src (if (seq existing-to-forms)
                             (let [cur-hdr (:seon.code.form/src (first existing-to-forms))]
                               (if (or (some? requires) (seq stub-refers-by-ns))
                                 (build-ns-header-src to (or doc (:seon.code.ns/doc (d/pull db '[:seon.code.ns/doc] [:seon.code.ns/name to]))) to-reqs)
                                 cur-hdr))
                             (build-ns-header-src to (or doc (str "Extracted child namespace of `" from "`.")) to-reqs))
                existing-to-body (if (seq existing-to-forms)
                                   (mapv :seon.code.form/src (rest existing-to-forms))
                                   [])
                moved-srcs (mapv (fn [f]
                                   (let [s (str (:seon.code.form/src f))]
                                     (if keep-private? s (promote-private-src s))))
                                 moved-forms)
                to-file-src (str to-hdr-src "\n\n"
                                 (str/join "\n\n" (concat existing-to-body moved-srcs))
                                 "\n")
                to-path (code/ns->default-path to)
                to-tx (code/file-import-tx db to-path to-file-src false)
                newly-added-to-keys (into #{(str (name to) "#ns")}
                                          (map #(str (name to) "/" (name (var-key->sym from (:seon.code.form/key %)))))
                                          moved-forms)
                updated-from-hdr (add-require-to-ns-src (:seon.code.form/src from-ns-hdr "")
                                                        to alias-sym referred-syms)
                reexport-forms-meta (keep (fn [f]
                                            (let [k (:seon.code.form/key f)
                                                  vsym (var-key->sym from k)
                                                  simple (when vsym (symbol (name vsym)))]
                                              (when (contains? all-stubbed simple)
                                                {:key k :order (:seon.code.form/order f) :lead (:seon.code.form/lead f "\n\n")
                                                 :src (make-reexport-src vsym alias-sym (:seon.code.form/src f)
                                                                         (contains? re-exported-priv simple))})))
                                          moved-forms)
                delta-from-src (str updated-from-hdr "\n\n"
                                    (str/join "\n\n" (map :src reexport-forms-meta)) "\n")
                from-path (or (:seon.code.ns/path (d/pull db '[:seon.code.ns/path] [:seon.code.ns/name from]))
                              (code/ns->default-path from))
                reexport-by-k (into {} (map (juxt :key identity)) reexport-forms-meta)
                delta-parsed (code/read-clj from-path delta-from-src
                                            (into #{} (map #(symbol (name %))) remaining-var-syms))
                updated-from-forms
                (mapv (fn [f]
                        (let [k (:seon.code.form/key f)]
                          (if (= k (str ns-str "#ns"))
                            (assoc f :seon.code.form/order (:seon.code.form/order from-ns-hdr 0)
                                     :seon.code.form/lead (:seon.code.form/lead from-ns-hdr ""))
                            (if-let [orig (get reexport-by-k k)]
                              (assoc f :seon.code.form/order (:order orig)
                                       :seon.code.form/lead (:lead orig))
                              f))))
                      (:forms delta-parsed))
                to-var-refs (into {}
                               (keep (fn [op]
                                       (when (and (map? op) (:seon.code.var/name op))
                                         [(:seon.code.var/name op)
                                          (or (:db/id op)
                                              (when (contains? (:schema db) :seon.code.var/name)
                                                (:e (first (d/datoms db :avet :seon.code.var/name (:seon.code.var/name op)))))
                                              (analyze/var-tempid (:seon.code.var/name op)))])))
                               to-tx)
                from-bctx {:var->ref to-var-refs}
                updated-from-vars (mapv #(analyze/enrich-var-with-account db % nil code/boot-root-keys from-bctx)
                                        (:vars delta-parsed))
                retract-keys (vec (keep (fn [f]
                                          (let [k (:seon.code.form/key f)
                                                vsym (var-key->sym from k)
                                                simple (when vsym (symbol (name vsym)))]
                                            (when-not (contains? all-stubbed simple)
                                              [k vsym])))
                                        moved-forms))
                retract-ops (vec (mapcat (fn [[k vsym]]
                                           (cond-> [[:db/retractEntity [:seon.code.form/key k]]]
                                             (and vsym (first (d/datoms db :avet :seon.code.var/name vsym)))
                                             (conj [:db/retractEntity [:seon.code.var/name vsym]])))
                                         retract-keys))
                stale-from-deps (vec (mapcat (fn [f]
                                               (let [k (:seon.code.form/key f)
                                                     old-deps (set (:seon.code.form/deps (get found-by-key k)))
                                                     new-deps (set (:seon.code.form/deps f))]
                                                 (for [d (set/difference old-deps new-deps)]
                                                   [:db/retract [:seon.code.form/key k] :seon.code.form/deps d])))
                                             updated-from-forms))
                stale-from-refs (analyze/stale-var-edge-retracts db updated-from-vars {:preserve-analyzed? true})
                from-req-refs (when (and (seq (:requires delta-parsed))
                                        (contains? (:schema db) :seon.code.ns/requires-ns))
                              (analyze/resolve-ns-requires-refs db (:requires delta-parsed)
                                                                {:ns->ref {to (analyze/ns-tempid to)}}))
                from-ns-ent (cond-> {:seon.code.ns/name from}
                              (seq (:requires delta-parsed))
                              (assoc :seon.code.ns/requires (:requires delta-parsed))
                              (seq from-req-refs)
                              (assoc :seon.code.ns/requires-ns from-req-refs))
                clean-to-tx (remove #(and (map? %) (= from (:seon.code.ns/name %))) to-tx)
                rem-chars (+ (count updated-from-hdr)
                             (reduce + 0 (map #(count (str (:src %))) reexport-forms-meta))
                             (reduce + 0 (map #(count (str (:seon.code.form/src %))) (remove #(= (str ns-str "#ns") (:seon.code.form/key %)) remaining-forms))))]
            {:ok? true
             :from from
             :to to
             :alias alias-sym
             :moved (mapv #(symbol (name (var-key->sym from (:seon.code.form/key %)))) moved-forms)
             :re-exported (vec (sort all-stubbed))
             :referred (vec (sort referred-syms))
             :auto-referred-stubs stub-refers-by-ns
             :from-tokens (quot rem-chars 4)
             :to-tokens (quot (count to-file-src) 4)
             :to-path to-path
             :from-path from-path
             :delta-to-keys newly-added-to-keys
             :delta-from-keys (mapv :seon.code.form/key updated-from-forms)
             :tx-data (vec (concat clean-to-tx retract-ops stale-from-deps stale-from-refs
                                   [from-ns-ent] updated-from-forms updated-from-vars))}))))))

(defn apply-move-vars!
  "Executes `move-vars-tx` on `conn` in O(|moved|) time & memory, reloading ONLY newly added forms in `to`
   and updated header/re-exports of `from` into the live JVM. Pass `:dry-run? true` to preview."
  {:seon.eval/budget-override {:ticks 500000000, :bytes 1073741824}, :malli/schema [:=> [:cat :seon.host/conn :map] :map]}
  [conn {:keys [dry-run? reload?] :or {reload? true} :as spec}]
  (let [res (move-vars-tx @conn spec)]
    (cond
      (not (:ok? res)) res
      dry-run? (assoc (dissoc res :tx-data :delta-from-keys :delta-to-keys) :dry-run? true)
      :else
      (let [from (:from res)
            to (:to res)
            from-loaded? (boolean (find-ns from))
            to-loaded? (boolean (find-ns to))
            r (d/transact conn {:tx-data (:tx-data res)})
            db-after (:db-after r)
            re-exp-set (set (:re-exported res))
            delta-to-keys (set (:delta-to-keys res))
            all-to-forms (code/ns-forms db-after to)
            delta-to-forms (if to-loaded?
                             (filterv #(contains? delta-to-keys (:seon.code.form/key %)) all-to-forms)
                             all-to-forms)
            delta-from-keys (set (:delta-from-keys res))
            all-from-forms (code/ns-forms db-after from)
            delta-from-forms (if from-loaded?
                               (filterv #(contains? delta-from-keys (:seon.code.form/key %)) all-from-forms)
                               all-from-forms)
            [errs1 errs2]
            (if reload?
              (do
                (when-let [from-ns-obj (find-ns from)]
                  (doseq [vsym (:moved res)
                          :when (not (contains? re-exp-set vsym))]
                    (try (ns-unmap from-ns-obj vsym) (catch Throwable _))))
                [(code/apply-overlay! db-after {:upserts delta-to-forms :retracted-keys #{}} true)
                 (code/apply-overlay! db-after {:upserts delta-from-forms :retracted-keys #{}} true)])
              [[] []])]
        (-> (dissoc res :tx-data :delta-from-keys :delta-to-keys)
            (assoc :tx (:max-tx db-after)
                   :load-errors (vec (concat errs1 errs2))))))))

(defn install-user-helpers!
  "Interns `user/ns-outline` and `user/move-vars!` into `user` for live Seon agents & REPL sessions
   (propagating `:seon.eval/budget-override` onto `user/move-vars!` so splitting large namespaces
   never trips the default 268 MB per-eval REPL cap)."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [get-agent-ctx-fn refresh-ctx-fn]
  (let [u (create-ns 'user)]
    (intern u 'ns-outline
    (fn
      ([nsym]
       (when-let [{:keys [conn]} (get-agent-ctx-fn)]
         (let [db @conn]
           (if (and (coll? nsym) (not (map? nsym)))
             (into {} (map (fn [n] [n (ns-form-outline db n)])) nsym)
             (ns-form-outline db nsym)))))
      ([nsym & more]
       (when-let [{:keys [conn]} (get-agent-ctx-fn)]
         (let [db @conn]
           (into {} (map (fn [n] [n (ns-form-outline db n)])) (cons nsym more)))))))
    (let [mv (intern u 'move-vars!
                     (fn [spec]
                       (when-let [{:keys [conn]} (get-agent-ctx-fn)]
                         (let [res (apply-move-vars! conn spec)]
                           (when (and (:ok? res) refresh-ctx-fn)
                             (refresh-ctx-fn @conn nil))
                           res))))]
      (alter-meta! mv assoc :seon.eval/budget-override {:ticks 500000000 :bytes 1073741824})
      mv)))