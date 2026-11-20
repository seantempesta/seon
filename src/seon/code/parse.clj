(ns seon.code.parse
  "Lossless Clojure & Markdown parsing, AST hashing, and var/dependency extraction."
  (:require [clojure.string :as str]
            [clojure.tools.reader.reader-types :as rt]
            [edamame.core :as eda]
            [hasch.core :as hasch]
            [jolt.analyzer]
            [jolt.host]
            [jolt.ir :as ir]
            [seon.schema :as schema]
            [seon.code.reach :as reach]))

(defn parent-ns-sym
  "Returns the parent namespace symbol for `nsym` (e.g. `'seon.schema.datahike` -> `'seon.schema`), or nil."
  {:malli/schema [:=> [:cat :symbol] [:maybe :symbol]]
   :cost/ticks 50000
   :cost/bytes 65536}
  [nsym]
  (let [s (name nsym)
        idx (.lastIndexOf s ".")]
    (when (pos? idx)
      (symbol (subs s 0 idx)))))

(defn ns-parent-chain-tx
  "Builds Datahike entity maps linking `nsym` up to its root namespace via `:seon.code.ns/parent`
   using `:db/id` tempids `(str \"ns/\" sym)` so Datahike upserts existing namespaces AND links
   brand-new parent chains atomically in a single transaction!"
  [nsym]
  (loop [cur nsym
         acc []]
    (let [tid (str "ns/" cur)]
      (if-let [p (parent-ns-sym cur)]
        (recur p (conj acc {:db/id tid :seon.code.ns/name cur :seon.code.ns/parent (str "ns/" p)}))
        (vec (rseq (conj acc {:db/id tid :seon.code.ns/name cur})))))))

(def boot-root-keys reach/boot-root-keys)

(defn line-offsets [^String s]
  (let [n (.length s)]
    (loop [i 0, acc [0]]
      (if (>= i n)
        acc
        (recur (inc i)
               (if (= (.charAt s i) \newline) (conj acc (inc i)) acc))))))

(defn pos->idx [offsets ^String s row col]
  (min (.length s)
       (+ (nth offsets (dec row) 0) (dec col))))

(defn lead-end [^String text i end]
  (loop [i (int i)]
    (if (>= i end)
      end
      (let [c (.charAt text i)]
        (cond
          (or (Character/isWhitespace c) (= c \,))
          (recur (inc i))

          (= c \;)
          (let [nl (.indexOf text "\n" i)]
            (recur (int (if (or (neg? nl) (> nl end)) end (inc nl)))))

          :else i)))))

(defn parse-ns-decl [form]
  (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
    (let [nsym (second form)
          more (drop 2 form)
          doc (when (string? (first more)) (first more))
          clauses (filter seq? more)
          req-specs (mapcat rest (filter #(= :require (first %)) clauses))]
      (reduce
       (fn [acc spec]
         (let [s (cond (symbol? spec) [spec] (vector? spec) spec :else nil)]
           (if-not s
             acc
             (let [target (first s)
                   opts (apply hash-map (rest s))
                   as-sym (:as opts)
                   refer-syms (when (sequential? (:refer opts)) (:refer opts))]
               (cond-> (update acc :requires conj target)
                 as-sym (assoc-in [:aliases as-sym] target)
                 (seq refer-syms) (update :refers merge (zipmap refer-syms (repeat target))))))))
       {:ns nsym :doc doc :aliases {} :refers {} :requires #{}}
       req-specs))))

(def def-heads
  '#{def defn defn- defmacro defonce defmulti defprotocol deftest})

(def persistable-repl-heads
  "Top-level defining/declaration heads that interactive REPL evals persist into `:seon.code.form/*`.
   Scratch REPL queries (`(let ...)`, `(clojure.test/test-vars ...)`, `(slurp ...)`, `(require ...)`, etc.)
   still evaluate and record into `:seon.agent.eval/*` without polluting source files as `#expr#` forms."
  (into def-heads '#{ns defmethod defrecord deftype extend-type extend-protocol declare}))

(defn sanitize-arglists [als]
  (mapv (fn [al] (vec (filter symbol? (tree-seq coll? seq al)))) als))

(defn collect-keywords
  "Collects all multi-segment qualified keywords (e.g. `:seon.agent/canvas`, `:seon.user/viewing`)
   referenced anywhere in `form` so Datahike automatically indexes Code <-> Schema edges in O(1)!"
  [acc x]
  (cond
    (qualified-keyword? x)
    (let [ns-s (namespace x)]
      (if (and ns-s (str/includes? ns-s ".") (not= ns-s "db"))
        (conj acc x)
        acc))
    (map? x) (reduce-kv (fn [a k v] (collect-keywords (collect-keywords a k) v)) acc x)
    (coll? x) (reduce collect-keywords acc x)
    :else acc))

(defn extract-var-facts
  "Extracts a structured `:seon.code.var/*` entity map from a top-level defining form,
   storing `:arglists` and `:schema` natively as plain Clojure data (`:db.type/data`)!"
  [nsym form-key deps form]
  (when (and (seq? form) (symbol? (first form)))
    (let [hd (symbol (name (first form)))
          sym (second form)]
      (when (and (contains? def-heads hd) (symbol? sym))
        (let [vsym (symbol (name nsym) (name sym))
              sym-meta (meta sym)
              tail (drop 2 form)
              doc (or (when (string? (first tail)) (first tail))
                      (:doc sym-meta))
              after-doc (if (string? (first tail)) (rest tail) tail)
              attr-map (when (and (map? (first after-doc)) (seq (rest after-doc)))
                         (first after-doc))
              after-attr (if attr-map (rest after-doc) after-doc)
              merged-meta (merge sym-meta attr-map)
              raw-als (cond
                        (#{'defn 'defn- 'defmacro} hd)
                        (cond
                          (vector? (first after-attr)) [(first after-attr)]
                          (seq? (first after-attr)) (vec (keep #(when (seq? %) (first %)) after-attr))
                          :else nil)
                        (= 'deftest hd) [[]]
                        :else nil)
              arglists (when (seq raw-als) (sanitize-arglists raw-als))
              malli-sch (:malli/schema merged-meta)
              sch-refs (schema/schema-refs-in-form malli-sch)
              kw-refs  (collect-keywords #{} form)
              var-refs (set (remove #(= "#ns" (name %)) deps))
              is-test? (= 'deftest hd)
              plat-test? (and is-test?
                              (boolean (some #(contains? boot-root-keys (str %)) var-refs)))
              ticks-b (or (:cost/ticks merged-meta) (get-in merged-meta [:seon.test/estimate :ticks]))
              bytes-b (or (:cost/bytes merged-meta) (get-in merged-meta [:seon.test/estimate :bytes]))
              big-o   (:cost/big-o merged-meta)]
          (cond-> {:seon.code.var/name vsym
                   :seon.code.var/ns nsym
                   :seon.code.var/ns-ref (str "ns/" nsym)
                   :seon.code.var/form form-key
                   :seon.code.var/private? (boolean (or (= 'defn- hd) (:private merged-meta)))
                   :seon.code.var/macro? (= 'defmacro hd)
                   :seon.code.var/test? is-test?
                   :seon.code.var/platform-test? plat-test?
                   :seon.code.var/flow-edge? (boolean (:seon.flow/edge merged-meta))}
            doc               (assoc :seon.code.var/doc doc)
            (seq arglists)    (assoc :seon.code.var/arglists arglists)
            malli-sch         (assoc :seon.code.var/schema malli-sch)
            (seq sch-refs)    (assoc :seon.code.var/schema-refs (set sch-refs))
            (seq kw-refs)     (assoc :seon.code.var/keywords kw-refs)
            (seq var-refs)    (assoc :seon.code.var/refs var-refs)
            (number? ticks-b) (assoc :seon.code.var/ticks-budget (long ticks-b))
            (number? bytes-b) (assoc :seon.code.var/bytes-budget (long bytes-b))
            (string? big-o)   (assoc :seon.code.var/big-o big-o)))))))

(defn ir-unit-refs [node]
  (let [acc (volatile! {:refs #{} :keywords #{}})]
    ((fn walk [n]
       (case (:op n)
         (:var :the-var)
         (let [vs (symbol (str (:ns n)) (str (:name n)))]
           (when-not (str/starts-with? (namespace vs) "clojure.")
             (vswap! acc update :refs conj vs)))

         (:const :quote)
         (let [v (if (= :const (:op n)) (:val n) (:form n))]
           (cond
             (qualified-keyword? v) (vswap! acc update :keywords conj v)
             (coll? v) (vswap! acc update :keywords into
                               (filter qualified-keyword?) (tree-seq coll? seq v))))
         nil)
       (ir/reduce-ir-children (fn [_ c] (walk c)) nil n))
     node)
    @acc))

(defn strip-docstring [form]
  (if-not (and (seq? form) (symbol? (first form)))
    form
    (let [hd (symbol (name (first form)))]
      (cond
        (= 'ns hd)
        (let [[op nsym & more] form]
          (if (string? (first more)) (apply list op nsym (rest more)) form))

        (and (contains? def-heads hd) (>= (count form) 4) (string? (nth form 2)))
        (let [[op sym _doc & more] form]
          (apply list op sym more))

        :else form))))

(defn clean-edn-for-hash [& args] (apply reach/clean-edn-for-hash args))

(defn semantic-hash [nsym form]
  (-> (str (hasch/uuid [nsym (clean-edn-for-hash (strip-docstring form))]))
      (str/replace "-" "")
      (subs 0 16)))

(defn form-identity [nsym form src]
  (if (and (seq? form) (symbol? (first form)))
    (let [hd (symbol (name (first form)))
          arg1 (second form)]
      (cond
        (= 'ns hd)
        {:key (str nsym "#ns") :def-sym nil :test? false}

        (and (contains? def-heads hd) (symbol? arg1))
        {:key (str nsym "/" (name arg1))
         :def-sym arg1
         :test? (= 'deftest hd)}

        (and (= 'defmethod hd) (symbol? arg1))
        {:key (str nsym "/" (name arg1) "#method#" (pr-str (nth form 2 nil)))
         :def-sym nil
         :test? false}

        :else
        {:key (str nsym "#expr#" (subs (str/replace (str (hasch/uuid src)) "-" "") 0 10))
         :def-sym nil
         :test? false}))
    {:key (str nsym "#expr#" (subs (str/replace (str (hasch/uuid src)) "-" "") 0 10))
     :def-sym nil
     :test? false}))

(defn collect-symbols [acc x]
  (cond
    (and (seq? x)
         (contains? #{'requiring-resolve 'resolve 'clojure.core/requiring-resolve 'clojure.core/resolve} (first x))
         (= 2 (count x))
         (seq? (second x))
         (= 'quote (first (second x)))
         (let [target (second (second x))]
           (and (symbol? target)
                (namespace target)
                (not= "user" (namespace target)))))
    (conj acc (second (second x)))

    (and (seq? x) (= 'quote (first x))) acc
    (symbol? x) (conj acc x)
    (map? x) (reduce-kv (fn [a k v] (collect-symbols (collect-symbols a k) v)) acc x)
    (coll? x) (reduce collect-symbols acc x)
    :else acc))

(def watched-runtime-syms
  "Blocking & ad-hoc state symbols kept in `:seon.code.var/refs` so `seon.health` detects
   blocking Flow steps and state-outside-Flow in O(|Δ|) straight from the DB index!"
  #{'clojure.core.async/<!!
    'clojure.core.async/>!!
    'clojure.core.async/alts!!
    'clojure.core.async/thread
    'clojure.core/atom
    'clojure.core/volatile!
    'clojure.core/promise
    'clojure.core/agent
    'Thread/sleep})

(def core-state-syms
  #{'atom 'volatile! 'promise 'agent})

(defn resolve-deps [nsym {:keys [aliases refers]} local-defs self-sym form]
  (let [syms (collect-symbols #{} form)]
    (into #{}
          (keep (fn [s]
                  (if-let [ns-part (namespace s)]
                    (let [ns-sym (symbol ns-part)
                          full-ns (get aliases ns-sym ns-sym)
                          qsym (symbol (name full-ns) (name s))]
                      (cond
                        (contains? watched-runtime-syms qsym) qsym
                        (not (str/starts-with? (name full-ns) "clojure.")) qsym
                        :else nil))
                    (cond
                      (and self-sym (= s self-sym)) nil
                      (contains? local-defs s) (symbol (name nsym) (name s))
                      (contains? refers s) (symbol (name (get refers s)) (name s))
                      (contains? core-state-syms s) (symbol "clojure.core" (name s))
                      :else nil))))
          syms)))

(defn read-clj
  ([path ^String text] (read-clj path text nil))
  ([path ^String text extra-local-defs]
   (let [offsets (line-offsets text)
         ns-ctx (volatile! {:ns 'user :doc nil :aliases {} :refers {} :requires #{} :kw-aliases {}})
         reader (rt/indexing-push-back-reader text)
         opts (eda/normalize-opts
               {:all true
                :read-eval false
                :read-cond :allow
                :features #{:clj}
                :auto-resolve (fn [alias-kw]
                                (if (= :current alias-kw)
                                  (:ns @ns-ctx)
                                  (get (:kw-aliases @ns-ctx) alias-kw (symbol (name alias-kw)))))
                :readers (fn [_tag] identity)})]
     (loop [prev-end 0, raw-forms []]
       (let [form (eda/parse-next reader opts)]
         (if (= ::eda/eof form)
           (let [ns-info @ns-ctx
                 nsym (:ns ns-info)
                 with-ids (mapv #(merge % (form-identity nsym (:form %) (:src %))) raw-forms)
                 local-defs (into (set extra-local-defs) (keep :def-sym) with-ids)
                 ns-deps (into #{} (map #(symbol (name %) "#ns")) (:requires ns-info))
                 [_ final-forms final-vars]
                 (reduce
                  (fn [[seen-keys facc vacc] [order {:keys [form lead src key def-sym test?]}]]
                    (let [ukey (if (contains? seen-keys key) (str key "#" order) key)
                          deps (resolve-deps nsym ns-info local-defs def-sym form)
                          var-ent (extract-var-facts nsym ukey deps form)
                          sch-dep-syms (map #(symbol "schema" (str (namespace %) "__" (name %)))
                                            (:seon.code.var/schema-refs var-ent))
                          full-deps (if (= ukey (str nsym "#ns"))
                                      ns-deps
                                      (into (conj deps (symbol (name nsym) "#ns")) sch-dep-syms))]
                      [(conj seen-keys ukey)
                       (conj facc {:seon.code.form/key ukey
                                   :seon.code.form/ns nsym
                                   :seon.code.form/order (long order)
                                   :seon.code.form/lead lead
                                   :seon.code.form/src src
                                   :seon.code.form/hash (semantic-hash nsym form)
                                   :seon.code.form/deps (set full-deps)
                                   :seon.code.form/test? (boolean test?)})
                       (cond-> vacc var-ent (conj var-ent))]))
                  [#{} [] []]
                  (map-indexed vector with-ids))]
             {:ns nsym
              :doc (:doc ns-info)
              :requires (set (:requires ns-info))
              :path path
              :tail (subs text prev-end)
              :forms final-forms
              :vars final-vars})
           (let [end (pos->idx offsets text (rt/get-line-number reader) (rt/get-column-number reader))
                 start (lead-end text prev-end end)]
             (when-let [parsed-ns (parse-ns-decl form)]
               (vreset! ns-ctx
                        (assoc parsed-ns :kw-aliases
                               (into {} (map (fn [[k v]] [(keyword (name k)) v])) (:aliases parsed-ns)))))
             (recur end
                    (conj raw-forms {:form form
                                     :lead (subs text prev-end start)
                                     :src (subs text start end)})))))))))

(defn md-path? [^String path]
  (and (str/ends-with? path ".md")
       (or (= path "AGENTS.md") (str/starts-with? path "docs/"))))

(defn heading->slug [^String hline idx]
  (let [clean (-> hline
                  (str/replace #"^#+\s*" "")
                  str/trim
                  str/lower-case
                  (str/replace #"[^a-z0-9]+" "-")
                  (str/replace #"^-+|-+$" ""))]
    (if (str/blank? clean) (str "sec-" idx) clean)))

(defn parse-md-sections [path ^String text]
  (let [m (re-matcher #"(?m)^#{1,4}\s+[^\n]*(?:\n|$)" text)
        starts (loop [acc []]
                 (if (.find m)
                   (recur (conj acc [(.start m) (.end m) (.group m)]))
                   acc))]
    (if (empty? starts)
      [{:seon.code.doc/id (str path "#top")
        :seon.code.doc/path path
        :seon.code.doc/order 0
        :seon.code.doc/heading ""
        :seon.code.doc/body text}]
      (let [preamble (when (pos? (ffirst starts))
                       [{:seon.code.doc/id (str path "#preamble")
                         :seon.code.doc/path path
                         :seon.code.doc/order 0
                         :seon.code.doc/heading ""
                         :seon.code.doc/body (subs text 0 (ffirst starts))}])
            base-ord (if preamble 1 0)
            [_ sections]
            (reduce
             (fn [[seen acc] [i [s e hraw]]]
               (let [next-s (if (< (inc i) (count starts))
                              (first (nth starts (inc i)))
                              (.length text))
                     slug0 (heading->slug hraw (+ base-ord i))
                     slug (if (contains? seen slug0) (str slug0 "-" (+ base-ord i)) slug0)]
                 [(conj seen slug)
                  (conj acc {:seon.code.doc/id (str path "#" slug)
                             :seon.code.doc/path path
                             :seon.code.doc/order (long (+ base-ord i))
                             :seon.code.doc/heading hraw
                             :seon.code.doc/body (subs text e next-s)})]))
             [#{} (vec preamble)]
             (map-indexed vector starts))]
        sections))))

(defn clj-path? [^String path]
  (and (or (str/starts-with? path "src/")
           (str/starts-with? path "test/"))
       (or (str/ends-with? path ".clj")
           (str/ends-with? path ".cljc"))))

(defn ns->default-path [nsym]
  (let [rel (-> (name nsym)
                (str/replace "." "/")
                (str/replace "-" "_")
                (str ".clj"))]
    (if (str/ends-with? (name nsym) "-test")
      (str "test/" rel)
      (str "src/" rel))))