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
            [seon.code :as code]))

(defn- var-key->sym [nsym k]
  (when (and k (not (str/includes? k "#")))
    (symbol (name nsym) (second (str/split k #"/" 2)))))

(defn ns-form-outline
  "Returns `{:ns :total-tokens :forms [{:key :sym :tokens :private? :local-deps :external-callers}]}`
   for `nsym` in `db` so agents can plan clean namespace splits in 1 fast call."
  [db nsym]
  (let [nsym (if (string? nsym) (symbol nsym) nsym)
        forms (code/ns-forms db nsym)
        vars-by-k (into {} (map (juxt :seon.code.var/form identity)) (code/ns-vars db nsym))
        ns-str (name nsym)
        rows (mapv (fn [f]
                     (let [k (:seon.code.form/key f)
                           v (get vars-by-k k)
                           vsym (:seon.code.var/name v)
                           local-deps (->> (:seon.code.form/deps f)
                                           (filter #(= ns-str (namespace %)))
                                           (remove #(= "#ns" (name %)))
                                           (map #(symbol (name %)))
                                           sort vec)
                           ext-callers (when vsym
                                         (->> (d/datoms db :avet :seon.code.var/refs vsym)
                                              (keep (fn [dt]
                                                      (let [cv (d/pull db '[:seon.code.var/name :seon.code.var/ns] (:e dt))]
                                                        (when (and (:seon.code.var/name cv)
                                                                   (not= nsym (:seon.code.var/ns cv)))
                                                          (:seon.code.var/name cv)))))
                                              distinct sort vec))]
                       {:key k
                        :sym (when vsym (symbol (name vsym)))
                        :order (:seon.code.form/order f 0)
                        :tokens (quot (count (str (:seon.code.form/src f))) 4)
                        :private? (boolean (:seon.code.var/private? v))
                        :local-deps local-deps
                        :external-callers (vec (take 6 ext-callers))
                        :external-caller-count (count ext-callers)}))
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

(defn- add-require-to-ns-src
  "Adds (or updates, unioning existing :refer symbols) a `[to-ns ...]` clause in `ns-src` cleanly."
  [^String ns-src to-ns alias-sym refer-syms]
  (let [existing-re (re-pattern (str "\\[\\s*" (java.util.regex.Pattern/quote (name to-ns)) "\\b[^\\]]*\\]"))
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
      (str/replace-first ns-src existing-re (java.util.regex.Matcher/quoteReplacement clause-str))

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

(defn- make-reexport-src [vsym alias-sym orig-src]
  (let [vname (name vsym)
        target (str (name alias-sym) "/" vname)
        meta-tags (str (when (callable-var-src? orig-src) "^:seon/callable ")
                       (when (str/includes? (str orig-src) ":seon.flow/edge") "^:seon.flow/edge "))]
    (str "(def " meta-tags vname " " target ")")))

(defn- promote-private-src [^String src]
  (-> src
      (str/replace-first #"^\(\s*defn-\s+" "(defn ")
      (str/replace-first #"^\(\s*(def|defonce)\s+\^:private\s+" "($1 ")
      (str/replace-first #"^\(\s*(def|defonce)\s+\^:seon\.flow/edge\s+\^:private\s+" "($1 ^:seon.flow/edge ")
      (str/replace-first #"^\(\s*(def|defonce)\s+\^:private\s+\^:seon\.flow/edge\s+" "($1 ^:seon.flow/edge ")))

(defn move-vars-tx
  "Builds an atomic Datahike transaction moving `vars` from `from` to child namespace `to`.
   Options:
   - `:from` (symbol, required): source namespace, e.g. `'seon.web`
   - `:to` (symbol, required): target namespace, e.g. `'seon.web.css`
   - `:vars` (coll of simple symbols, required): var names to move, e.g. `'[app-css datastar-js-text]`
   - `:alias` (symbol, optional): require alias in `from` (default: last dot-segment of `to`)
   - `:doc` (string, optional): docstring for newly created `to`
   - `:requires` (vector, optional): explicit `:require` specs for `to` (defaults to inheriting `from`'s requires)
   - `:re-export-public?` (boolean, default true): keep 1-line `(def v alias/v)` in `from` for any moved public var
     so external callers, dynamic `resolve`, schema `:seon.render/hiccup`, and tests keep working seamlessly.
   - `:keep-private?` (boolean, default false): when false, promotes moved `defn-`/`^:private` to public in `to`."
  [db {:keys [from to vars alias doc requires re-export-public? keep-private?]
       :or {re-export-public? true keep-private? false}}]
  (let [from (if (string? from) (symbol from) from)
        to (if (string? to) (symbol to) to)
        var-syms (mapv #(symbol (name %)) vars)
        alias-sym (or (when alias (symbol (name alias)))
                      (symbol (last (str/split (name to) #"\."))))
        from-forms (code/ns-forms db from)
        from-vars (into {} (map (juxt :seon.code.var/form identity)) (code/ns-vars db from))
        ns-str (name from)
        wanted-keys (into #{} (map #(str ns-str "/" (name %))) var-syms)
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
      (let [moved-forms (filterv #(contains? wanted-keys (:seon.code.form/key %)) from-forms)
            remaining-forms (filterv #(not (contains? wanted-keys (:seon.code.form/key %))) from-forms)
            remaining-var-syms (into #{}
                                     (keep #(var-key->sym from (:seon.code.form/key %)))
                                     remaining-forms)
            unresolved-deps (->> moved-forms
                                 (mapcat :seon.code.form/deps)
                                 (filter #(contains? remaining-var-syms %))
                                 distinct sort vec)]
        (if (seq unresolved-deps)
          {:ok? false
           :error :unresolved-from-deps
           :missing-deps unresolved-deps
           :hint "Moved forms still call these vars left in source ns; include them in :vars or move them first."}
          (let [rem-deps-set (into #{} (mapcat :seon.code.form/deps) remaining-forms)
                re-exported-syms
                (if re-export-public?
                  (into #{}
                        (keep (fn [f]
                                (let [k (:seon.code.form/key f)
                                      v (get from-vars k)
                                      vsym (:seon.code.var/name v)]
                                  (when (and vsym (not (:seon.code.var/private? v)))
                                    (symbol (name vsym))))))
                        moved-forms)
                  #{})
                referred-syms
                (into #{}
                      (keep (fn [f]
                              (let [k (:seon.code.form/key f)
                                    vsym (var-key->sym from k)
                                    simple (when vsym (symbol (name vsym)))]
                                (when (and simple
                                           (not (contains? re-exported-syms simple))
                                           (contains? rem-deps-set vsym))
                                  simple))))
                      moved-forms)
                existing-to-forms (code/ns-forms db to)
                from-ns-hdr (first (filter #(= (str ns-str "#ns") (:seon.code.form/key %)) from-forms))
                inherited-reqs (->> (extract-require-clauses (:seon.code.form/src from-ns-hdr ""))
                                    (remove #(and (vector? %) (= to (first %))))
                                    vec)
                to-hdr-src (if (seq existing-to-forms)
                             (let [cur-hdr (:seon.code.form/src (first existing-to-forms))]
                               (if (some? requires)
                                 (build-ns-header-src to (or doc (:seon.code.ns/doc (d/pull db '[:seon.code.ns/doc] [:seon.code.ns/name to]))) requires)
                                 cur-hdr))
                             (build-ns-header-src to (or doc (str "Extracted child namespace of `" from "`."))
                                                  (if (some? requires) requires inherited-reqs)))
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
                updated-from-hdr (add-require-to-ns-src (:seon.code.form/src from-ns-hdr "")
                                                        to alias-sym referred-syms)
                from-body-parts
                (keep (fn [f]
                        (let [k (:seon.code.form/key f)]
                          (cond
                            (= k (str ns-str "#ns"))
                            (str (:seon.code.form/lead f "") updated-from-hdr)

                            (contains? wanted-keys k)
                            (let [vsym (var-key->sym from k)
                                  simple (when vsym (symbol (name vsym)))]
                              (when (contains? re-exported-syms simple)
                                (str (:seon.code.form/lead f "\n\n")
                                     (make-reexport-src vsym alias-sym (:seon.code.form/src f)))))

                            :else
                            (str (:seon.code.form/lead f "\n\n") (:seon.code.form/src f)))))
                      from-forms)
                from-tail (or (:seon.code.ns/tail (d/pull db '[:seon.code.ns/tail] [:seon.code.ns/name from])) "\n")
                from-file-src (str (apply str from-body-parts) from-tail)
                from-path (or (:seon.code.ns/path (d/pull db '[:seon.code.ns/path] [:seon.code.ns/name from]))
                              (code/ns->default-path from))
                from-tx (code/file-import-tx db from-path from-file-src false)
                from-ns-names (into #{} (keep :seon.code.ns/name) from-tx)
                clean-to-tx (remove #(and (map? %) (contains? from-ns-names (:seon.code.ns/name %))) to-tx)]
            {:ok? true
             :from from
             :to to
             :alias alias-sym
             :moved (mapv #(symbol (name %)) var-syms)
             :re-exported (vec (sort re-exported-syms))
             :referred (vec (sort referred-syms))
             :from-tokens (quot (count from-file-src) 4)
             :to-tokens (quot (count to-file-src) 4)
             :to-path to-path
             :from-path from-path
             :to-forms (mapv :seon.code.form/key (filter #(and (map? %) (:seon.code.form/src %)) to-tx))
             :tx-data (vec (concat clean-to-tx from-tx))}))))))

(defn apply-move-vars!
  "Executes `move-vars-tx` on `conn`, reloads `to` and `from` cleanly into the live JVM,
   and refreshes `@seon.ctx/ctx`."
  [conn spec]
  (let [res (move-vars-tx @conn spec)]
    (if-not (:ok? res)
      res
      (let [r (d/transact conn {:tx-data (:tx-data res)})
            db-after (:db-after r)
            from (:from res)
            to (:to res)
            re-exp-set (set (:re-exported res))
            to-forms (code/ns-forms db-after to)
            from-forms (code/ns-forms db-after from)
            ;; Unmap moved non-re-exported vars from `from` BEFORE reloading `from` so `:refer` never collides!
            _ (when-let [from-ns-obj (find-ns from)]
                (doseq [vsym (:moved res)
                        :when (not (contains? re-exp-set vsym))]
                  (try (ns-unmap from-ns-obj vsym) (catch Throwable _))))
            errs1 (code/apply-overlay! db-after {:upserts to-forms :retracted-keys #{}} true)
            errs2 (code/apply-overlay! db-after {:upserts from-forms :retracted-keys #{}} true)]
        (-> (dissoc res :tx-data :to-forms)
            (assoc :tx (:max-tx db-after)
                   :load-errors (vec (concat errs1 errs2))))))))

(defn install-user-helpers!
  "Interns `user/ns-outline` and `user/move-vars!` into `user` for live Seon agents & REPL sessions."
  [get-agent-ctx-fn refresh-ctx-fn]
  (let [u (create-ns 'user)]
    (intern u 'ns-outline
            (fn [nsym]
              (when-let [{:keys [conn]} (get-agent-ctx-fn)]
                (ns-form-outline @conn nsym))))
    (intern u 'move-vars!
            (fn [spec]
              (when-let [{:keys [conn]} (get-agent-ctx-fn)]
                (let [res (apply-move-vars! conn spec)]
                  (when (and (:ok? res) refresh-ctx-fn)
                    (refresh-ctx-fn @conn nil))
                  res))))))
