(ns seon.code
  "Pure Clojure, Co-Located Schema & Markdown-as-data engine:
   - Structured multi-segment entities in Datahike: `:seon.code.ns/*`, `:seon.code.form/*`,
     `:seon.code.var/*` (with native `:db.type/data` `:arglists` & `:schema`), `:seon.schema/*`,
     `:seon.code.doc/*`, and `:seon.code.file/*`.
   - Hierarchical namespace & schema subtree scoping (`in-scope?`, `scope-view`) so distributed
     agents tree out cleanly along namespace prefixes (`seon.code`, `seon.test`, `seon.app.billing`).
   - Lossless parse & byte-exact reconstruction (`0`-byte diff round-trip for `.clj` and `.md`).
   - Tick- & byte-bounded `eval-forms` / `eval-tool-calls` via `jolt.host/run-interruptible`
     + automatic Malli `arm-var!` instrumentation + `O(|Delta| + |callers|)` Merkle reach graph."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.reader.reader-types :as rt]
            [datahike.api :as d]
            [edamame.core :as eda]
            [hasch.core :as hasch]
            [jolt.analyzer]
            [jolt.host]
            [jolt.ir :as ir]
            [jolt.scheme :as scm]
            [seon.code.files :as files]
            [seon.schema :as schema]
            [seon.store :as store]))

(def ^:private ticks-fn (scm/eval-string "(lambda () (jolt-ticks))"))
(def ^:private alloc-fn (scm/eval-string "(foreign-procedure \"(cs)thread_bytes_allocated\" () uptr)"))

(defn alloc-bytes
  "Returns cumulative heap bytes allocated on the current thread."
  []
  (long (alloc-fn)))
(def ^:private mono-fn  (scm/eval-string "(lambda () (jolt-monotonic-ns))"))

(defn measure-call [thunk]
  (let [t0 (mono-fn)
        k0 (ticks-fn)
        b0 (alloc-fn)
        v  (thunk)
        dk (max 0 (- (ticks-fn) k0))
        db (max 0 (- (alloc-fn) b0))
        ms (/ (Math/round (/ (double (- (mono-fn) t0)) 1e4)) 100.0)]
    {:val v :ticks (long dk) :bytes (long db) :ms (double ms)}))

;; --- Natural Namespace Entity Hierarchy (`:seon.code.ns/parent`) ---

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

;; --- Boot / Platform Entrypoints (Automatic Reach Classification, Zero Manual Labels!) ---

(def boot-root-keys
  #{"seon.main/-main"
    "seon.main/start-flow-instance!"
    "seon.fork/start-fork-parent!"
    "seon.fork/run-fork-parent-loop!"
    "seon.store/open!"
    "seon.schema.datahike/declarations"
    "seon.switchover/run-switch-over!"})

(defn boot-file-path? [^String path]
  (or (= path "deps.edn")
      (= path "bin/build")
      (= path "bin/env")
      (str/starts-with? path "native/")))

;; --- Line / Character Span Helpers ---

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

;; --- Pure Namespace & Var Metadata Extraction ---

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

(def ^:private def-heads
  '#{def defn defn- defmacro defonce defmulti defprotocol deftest})

(def ^:private persistable-repl-heads
  "Top-level defining/declaration heads that interactive REPL evals persist into `:seon.code.form/*`.
   Scratch REPL queries (`(let ...)`, `(clojure.test/test-vars ...)`, `(slurp ...)`, `(require ...)`, etc.)
   still evaluate and record into `:seon.agent.eval/*` without polluting source files as `#expr#` forms."
  (into def-heads '#{ns defmethod defrecord deftype extend-type extend-protocol declare}))

(defn- sanitize-arglists [als]
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

;; --- Compiler IR Tap (`jolt.analyzer/*unit-tap*`) ---

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

;; --- Semantic Code Hash & Dependency Extraction ---

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

(defn clean-edn-for-hash [x]
  (cond
    (or (seq? x) (list? x) (vector? x)) (mapv clean-edn-for-hash x)
    (set? x) (vec (sort-by pr-str (map clean-edn-for-hash x)))
    (map? x) (vec (sort-by (comp pr-str first)
                           (map (fn [[k v]] [(clean-edn-for-hash k) (clean-edn-for-hash v)]) x)))
    (instance? java.util.regex.Pattern x) [:regex (str x)]
    :else x))

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

(def ^:private core-state-syms
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

;; --- Lossless Clojure Parser & Renderer ---

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

;; --- Lossless Markdown Documentation Parser & Renderer (`0`-byte diff!) ---

(defn md-path? [^String path]
  (and (str/ends-with? path ".md")
       (or (= path "AGENTS.md") (str/starts-with? path "docs/"))))

(defn schema-file-path? [^String path] (files/schema-file-path? path))

(defn- heading->slug [^String hline idx]
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

(defn doc-sections [db path]
  (->> (d/datoms db :avet :seon.code.doc/path path)
       (map #(d/pull db '[:seon.code.doc/id :seon.code.doc/path :seon.code.doc/order
                          :seon.code.doc/heading :seon.code.doc/body] (:e %)))
       (sort-by (juxt :seon.code.doc/order :seon.code.doc/id))
       vec))

(defn render-doc [db path]
  (let [secs (doc-sections db path)]
    (when (seq secs)
      (apply str (map (fn [s] (str (:seon.code.doc/heading s "") (:seon.code.doc/body s ""))) secs)))))

;; --- File Import & Reconstruction ---

(defn clj-path? [^String path]
  (and (or (str/starts-with? path "src/")
           (str/starts-with? path "test/"))
       (or (str/ends-with? path ".clj")
           (str/ends-with? path ".cljc"))))

(defn ns-forms [db nsym]
  (->> (d/datoms db :avet :seon.code.form/ns nsym)
       (map #(d/pull db '[:db/id :seon.code.form/key :seon.code.form/ns :seon.code.form/order
                          :seon.code.form/lead :seon.code.form/src :seon.code.form/hash
                          :seon.code.form/deps :seon.code.form/test?] (:e %)))
       (sort-by (juxt :seon.code.form/order :seon.code.form/key))
       vec))

(defn ns-vars [db nsym]
  (->> (d/datoms db :avet :seon.code.var/ns nsym)
       (map #(d/pull db '[*] (:e %)))
       (sort-by #(str (:seon.code.var/name %)))
       vec))

(defn render-schema-file
  "Renders all `:seon.schema/key` entities whose owner path is `path` into a clean EDN map."
  [db path]
  (files/render-schema-file db path))

(defn- retract-stale-many-ops
  "Retracts removed values from `:db.cardinality/many` set attributes (`attrs`) on updated entities
   so editing a function to remove a ref/dep/schema-ref actually clears the old Datahike datoms!"
  [db id-attr attrs ents]
  (when db
    (vec
     (mapcat
      (fn [ent]
        (let [id-val (get ent id-attr)
              eid (when (contains? (:schema db) id-attr)
                    (:e (first (d/datoms db :avet id-attr id-val))))]
          (when eid
            (mapcat
             (fn [a]
               (let [old-set (into #{} (map :v) (d/datoms db :eavt eid a))
                     new-set (set (get ent a #{}))]
                 (for [gone (set/difference old-set new-set)]
                   [:db/retract [id-attr id-val] a gone])))
             attrs))))
      ents))))

(defn file-import-tx [db path text exec?]
  (cond
    (clj-path? path)
    (let [{:keys [ns doc requires tail forms vars]} (read-clj path text)
          new-keys (into #{} (map :seon.code.form/key) forms)
          new-vars (into #{} (map :seon.code.var/name) vars)
          stale-forms (when db
                        (for [old (ns-forms db ns)
                              :when (not (contains? new-keys (:seon.code.form/key old)))]
                          [:db/retractEntity [:seon.code.form/key (:seon.code.form/key old)]]))
          stale-vars (when db
                       (for [old (ns-vars db ns)
                             :when (not (contains? new-vars (:seon.code.var/name old)))]
                         [:db/retractEntity [:seon.code.var/name (:seon.code.var/name old)]]))
          stale-form-deps (retract-stale-many-ops db :seon.code.form/key [:seon.code.form/deps] forms)
          stale-var-refs  (retract-stale-many-ops db :seon.code.var/name [:seon.code.var/refs :seon.code.var/schema-refs :seon.code.var/keywords] vars)
          ns-chain (ns-parent-chain-tx ns)
          ns-ent (cond-> (assoc (peek ns-chain) :seon.code.ns/path path :seon.code.ns/tail tail)
                   doc (assoc :seon.code.ns/doc doc)
                   (seq requires) (assoc :seon.code.ns/requires requires))]
      (vec (concat stale-forms stale-vars stale-form-deps stale-var-refs (pop ns-chain) [ns-ent] forms vars)))

    (md-path? path)
    (let [secs (parse-md-sections path text)
          new-ids (into #{} (map :seon.code.doc/id) secs)
          stale-docs (when db
                       (for [old (doc-sections db path)
                             :when (not (contains? new-ids (:seon.code.doc/id old)))]
                         [:db/retractEntity [:seon.code.doc/id (:seon.code.doc/id old)]]))]
      (vec (concat stale-docs secs)))

    (files/schema-file-path? path)
    (files/import-schema-file-tx path text)

    (files/config-file-path? path)
    [(store/read-aero-config-tx db text)
     {:seon.code.file/path path :seon.code.file/text text :seon.code.file/exec? false}]

    (files/task-file-path? path)
    (files/import-tasks-tx db path text)

    (files/agent-file-path? path)
    (files/import-agents-tx db path text)

    :else
    [{:seon.code.file/path path :seon.code.file/text text :seon.code.file/exec? (boolean exec?)}]))

(defn render-ns [db nsym]
  (when-let [ns-ent (d/pull db '[:seon.code.ns/name :seon.code.ns/tail] [:seon.code.ns/name nsym])]
    (when (:seon.code.ns/name ns-ent)
      (let [fs (ns-forms db nsym)]
        (apply str
               (concat (mapcat (fn [[i f]]
                                 (let [lead (:seon.code.form/lead f "")
                                       eff-lead (if (and (pos? i) (= "" lead)) "\n\n" lead)]
                                   [eff-lead (:seon.code.form/src f "")]))
                               (map-indexed vector fs))
                       [(:seon.code.ns/tail ns-ent "")]))))))

(defn render-path [db path]
  (cond
    (first (d/datoms db :avet :seon.code.ns/path path))
    (let [ns-dt (first (d/datoms db :avet :seon.code.ns/path path))]
      (render-ns db (:seon.code.ns/name (d/pull db '[:seon.code.ns/name] (:e ns-dt)))))

    (md-path? path)
    (or (render-doc db path)
        (:seon.code.file/text (d/pull db '[:seon.code.file/text] [:seon.code.file/path path])))

    (files/schema-file-path? path)
    (or (:seon.code.file/text (d/pull db '[:seon.code.file/text] [:seon.code.file/path path]))
        (files/render-schema-file db path))

    (files/config-file-path? path)
    (files/render-config-file db path)

    (files/task-file-path? path)
    (files/render-tasks-file db path)

    (files/agent-file-path? path)
    (files/render-agent-file db path)

    :else
    (:seon.code.file/text (d/pull db '[:seon.code.file/text] [:seon.code.file/path path]))))

;; --- O(|Delta|) Branch / Commit Hot-Loader + Automatic Malli Var Instrumentation ---

(defn register-loaded-ns! [nsym]
  (when (and (symbol? nsym) (not= 'user nsym))
    (dosync (commute @#'clojure.core/*loaded-libs* conj nsym))))

(defn sort-forms-topologically [forms]
  (let [by-ns (group-by :seon.code.form/ns forms)
        ns-set (set (keys by-ns))
        ns-deps (into {}
                      (map (fn [[nsym fs]]
                             [nsym (into #{}
                                         (keep (fn [d]
                                                 (let [dns (some-> (namespace d) symbol)]
                                                   (when (and dns (not= dns nsym) (contains? ns-set dns))
                                                     dns))))
                                         (mapcat :seon.code.form/deps fs))]))
                      by-ns)
        visited (volatile! #{})
        ordered-nss (volatile! [])
        visit-ns (fn visit-ns [n path]
                   (when-not (or (contains? @visited n) (contains? path n))
                     (doseq [dep (sort (get ns-deps n))]
                       (visit-ns dep (conj path n)))
                     (vswap! visited conj n)
                     (vswap! ordered-nss conj n)))]
    (doseq [n (sort-by (fn [s] [(if (str/ends-with? (name s) "-test") 1 0) (str s)]) ns-set)]
      (visit-ns n #{}))
    (vec
     (mapcat (fn [nsym]
               (sort-by (fn [f]
                          [(if (str/ends-with? (:seon.code.form/key f "") "#ns") 0 1)
                           (:seon.code.form/order f 0)
                           (:seon.code.form/key f "")])
                        (get by-ns nsym)))
             @ordered-nss))))

(defn- arm-defined-vars-in-ns! [db nsym]
  (when-let [ns-obj (find-ns nsym)]
    (doseq [[_ v] (ns-interns ns-obj)]
      (when (and (not (:private (meta v))) (:malli/schema (meta v)))
        (schema/arm-var! db v)))))

(defn apply-overlay!
  ([overlay] (apply-overlay! nil overlay false))
  ([db delta] (apply-overlay! db delta false))
  ([db {:keys [upserts retracted-keys]} tolerant?]
   (doseq [k retracted-keys
           :let [[ns-str var-str] (str/split k #"/" 2)]
           :when (and ns-str var-str (not (str/includes? var-str "#")))]
     (when-let [ns-obj (find-ns (symbol ns-str))]
       (ns-unmap ns-obj (symbol var-str))))
   (let [touched-nss (volatile! #{})
         errors (volatile! [])]
     (doseq [{:keys [:seon.code.form/key :seon.code.form/ns :seon.code.form/src]} (sort-forms-topologically upserts)]
       (let [load-one (fn []
                        (let [ns-obj (create-ns ns)]
                          (binding [*ns* ns-obj]
                            (refer-clojure)
                            (let [rdr (java.io.PushbackReader. (java.io.StringReader. src))]
                              (loop []
                                (let [form (read {:eof ::eof} rdr)]
                                  (when-not (= ::eof form)
                                    (eval form)
                                    (recur))))))
                          (register-loaded-ns! ns)
                          (vswap! touched-nss conj ns)))]
         (if tolerant?
           (try
             (load-one)
             (catch Throwable t
               (vswap! errors conj {:key key :ns ns :error (or (.getMessage t) (str t))})))
           (load-one))))
     (doseq [nsym @touched-nss]
       (arm-defined-vars-in-ns! db nsym))
     @errors)))

(defn changed-form-ops [conn from-cid to-cid]
  (if (= from-cid to-cid)
    {:upserts [] :retracted-keys #{} :schema-keys #{} :boot-affected? false}
    (let [ds (d/changes conn from-cid to-cid)
          db (or (try (d/commit-as-db conn to-cid) (catch Throwable _ nil)) @conn)
          key-aid (get-in db [:ident-ref-map :seon.code.form/key])
          src-aid (get-in db [:ident-ref-map :seon.code.form/src])
          sch-aid (get-in db [:ident-ref-map :seon.schema/key])
          schf-aid (get-in db [:ident-ref-map :seon.schema/form])
          fp-aid  (get-in db [:ident-ref-map :seon.code.file/path])
          relevant-form? (fn [a] (or (= a :seon.code.form/key) (= a :seon.code.form/src)
                                     (= a key-aid) (= a src-aid)))
          retracted-keys (into #{}
                               (keep (fn [dt]
                                       (when (and (not (:added dt))
                                                  (or (= (:a dt) :seon.code.form/key) (= (:a dt) key-aid)))
                                         (:v dt))))
                               ds)
          touched-eids (into #{}
                             (keep (fn [dt] (when (and (:added dt) (relevant-form? (:a dt))) (:e dt))))
                             ds)
          schema-eids (into #{}
                            (keep (fn [dt]
                                    (when (or (= (:a dt) :seon.schema/key) (= (:a dt) sch-aid)
                                              (= (:a dt) :seon.schema/form) (= (:a dt) schf-aid))
                                      (:e dt))))
                            ds)
          schema-keys (into #{}
                            (keep (fn [eid] (:seon.schema/key (d/pull db '[:seon.schema/key] eid))))
                            schema-eids)
          boot-files? (boolean
                       (some (fn [dt]
                               (when (or (= (:a dt) :seon.code.file/path) (= (:a dt) fp-aid))
                                 (boot-file-path? (str (:v dt)))))
                             ds))
          live-forms (->> touched-eids
                          (keep (fn [eid]
                                  (let [f (d/pull db '[:seon.code.form/key :seon.code.form/ns :seon.code.form/order
                                                       :seon.code.form/src :seon.code.form/hash :seon.code.form/deps
                                                       :seon.code.form/test?] eid)]
                                    (when (:seon.code.form/src f) f))))
                          sort-forms-topologically)
          live-keys (into #{} (map :seon.code.form/key) live-forms)
          boot-forms? (boolean (some #(contains? boot-root-keys %)
                                     (concat live-keys retracted-keys)))]
      {:upserts live-forms
       :retracted-keys (set/difference retracted-keys live-keys)
       :schema-keys schema-keys
       :boot-affected? (or boot-files? boot-forms?)})))

(defn load-delta!
  ([conn from-cid to-cid] (load-delta! conn from-cid to-cid false))
  ([conn from-cid to-cid tolerant?]
   (let [delta (changed-form-ops conn from-cid to-cid)
         to-db (or (try (d/commit-as-db conn to-cid) (catch Throwable _ nil)) @conn)
         errs (apply-overlay! to-db delta (boolean tolerant?))]
     {:loaded (mapv :seon.code.form/key (:upserts delta))
      :unmapped (vec (:retracted-keys delta))
      :errors errs
      :boot-affected? (:boot-affected? delta)})))

;; --- O(|Delta| + |Callers|) Incremental Reach Graph ---

(defn schema-kw->node-key [kw]
  (str "schema/" (namespace kw) "__" (name kw)))

(defn dep-sym->key [^clojure.lang.Symbol sym]
  (if (= "#ns" (name sym))
    (str (namespace sym) "#ns")
    (str (namespace sym) "/" (name sym))))

(defn- compute-node-digest [nodes memo-vol k visiting]
  (if-let [d (get @memo-vol k)]
    d
    (if (contains? visiting k)
      (get-in nodes [k :hash] "cycle")
      (if-let [node (get nodes k)]
        (let [visiting' (conj visiting k)
              dep-digests (mapv #(compute-node-digest nodes memo-vol % visiting')
                                (sort (filter #(contains? nodes %) (:deps node))))
              d (-> (str (hasch/uuid [k (:hash node) dep-digests]))
                    (str/replace "-" "")
                    (subs 0 16))]
          (vswap! memo-vol assoc k d)
          d)
        "external"))))

(defn- reverse-closure [nodes seed-keys]
  (loop [queue (vec seed-keys), seen (set seed-keys)]
    (if (empty? queue)
      seen
      (let [cur (peek queue)
            callers (get-in nodes [cur :rdeps] #{})
            fresh (remove seen callers)]
        (recur (into (pop queue) fresh)
               (into seen fresh))))))

(defn- function-schema? [sch]
  (boolean (schema/generatable-function-schema? sch)))

(defn build-reach-graph [db]
  (let [raw-forms (->> (d/datoms db :aevt :seon.code.form/key)
                       (map #(d/pull db '[:seon.code.form/key :seon.code.form/hash
                                          :seon.code.form/deps :seon.code.form/test?] (:e %))))
        raw-schemas (->> (d/datoms db :aevt :seon.schema/key)
                         (map #(d/pull db '[:seon.schema/key :seon.schema/form] (:e %))))
        contracted (->> (d/datoms db :aevt :seon.code.var/schema)
                        (keep (fn [dt]
                                (let [v (d/pull db '[:seon.code.var/name :seon.code.var/form :seon.code.var/schema] (:e dt))]
                                  (when (and (:seon.code.var/name v) (function-schema? (:seon.code.var/schema v)))
                                    [(:seon.code.var/name v) (:seon.code.var/form v)]))))
                        (into {}))
        form-nodes (into {}
                         (map (fn [f]
                                [(:seon.code.form/key f)
                                 {:hash (:seon.code.form/hash f)
                                  :deps (into #{} (map dep-sym->key) (:seon.code.form/deps f))
                                  :rdeps #{}
                                  :test? (boolean (:seon.code.form/test? f))}]))
                         raw-forms)
        sch-nodes (into {}
                        (map (fn [{:keys [:seon.schema/key :seon.schema/form]}]
                               [(schema-kw->node-key key)
                                {:hash (-> (str (hasch/uuid [key (clean-edn-for-hash form)]))
                                           (str/replace "-" "")
                                           (subs 0 16))
                                 :deps #{}
                                 :rdeps #{}
                                 :test? false}]))
                        raw-schemas)
        base-nodes (merge sch-nodes form-nodes)
        nodes (reduce-kv
               (fn [acc k {:keys [deps]}]
                 (reduce (fn [a dep-k]
                           (if (contains? a dep-k)
                             (update-in a [dep-k :rdeps] conj k)
                             a))
                         acc deps))
               base-nodes base-nodes)
        memo (volatile! {})
        _ (doseq [k (keys nodes)] (compute-node-digest nodes memo k #{}))
        digests @memo
        tests (into {}
                    (keep (fn [[k n]] (when (:test? n) [(symbol k) (get digests k)])))
                    nodes)
        boot-closure (reverse-closure nodes (filter #(contains? nodes %) boot-root-keys))
        platform-tests (into #{}
                             (keep (fn [k] (when (get-in nodes [k :test?]) (symbol k))))
                             boot-closure)]
    {:commit (d/commit-id db)
     :nodes nodes
     :digests digests
     :tests tests
     :contracted-vars contracted
     :platform-tests platform-tests}))

(defn update-reach-graph [graph conn]
  (let [to-cid (d/commit-id @conn)
        from-cid (:commit graph)]
    (if (= from-cid to-cid)
      graph
      (try
        (let [db @conn
              {:keys [upserts retracted-keys schema-keys]} (changed-form-ops conn from-cid to-cid)
              sch-upserts (keep (fn [sk]
                                  (let [ent (d/pull db '[:seon.schema/key :seon.schema/form] [:seon.schema/key sk])]
                                    (when (some? (:seon.schema/form ent))
                                      {:key (schema-kw->node-key sk)
                                       :hash (-> (str (hasch/uuid [sk (clean-edn-for-hash (:seon.schema/form ent))]))
                                                 (str/replace "-" "")
                                                 (subs 0 16))})))
                                schema-keys)
              dirty-seeds (-> (set retracted-keys)
                              (into (map :seon.code.form/key) upserts)
                              (into (map :key) sch-upserts))
              affected-pre (reverse-closure (:nodes graph) dirty-seeds)
              nodes1 (reduce
                      (fn [ns k]
                        (if-let [old (get ns k)]
                          (let [ns' (reduce (fn [a dep-k]
                                              (if (contains? a dep-k)
                                                (update-in a [dep-k :rdeps] disj k)
                                                a))
                                            ns (:deps old))]
                            (if (contains? retracted-keys k) (dissoc ns' k) ns'))
                          ns))
                      (:nodes graph) dirty-seeds)
              nodes1b (reduce
                       (fn [ns {:keys [key hash]}]
                         (assoc ns key {:hash hash
                                        :deps #{}
                                        :rdeps (get-in ns [key :rdeps] #{})
                                        :test? false}))
                       nodes1 sch-upserts)
              nodes2 (reduce
                      (fn [ns f]
                        (let [k (:seon.code.form/key f)
                              deps (into #{} (map dep-sym->key) (:seon.code.form/deps f))
                              existing-rdeps (get-in ns [k :rdeps] #{})
                              ns' (assoc ns k {:hash (:seon.code.form/hash f)
                                               :deps deps
                                               :rdeps existing-rdeps
                                               :test? (boolean (:seon.code.form/test? f))})]
                          (reduce (fn [a dep-k]
                                    (if (contains? a dep-k)
                                      (update-in a [dep-k :rdeps] conj k)
                                      a))
                                  ns' deps)))
                      nodes1b upserts)
              affected (into affected-pre
                             (reverse-closure nodes2 (concat (map :seon.code.form/key upserts) (map :key sch-upserts))))
              kept-digests (apply dissoc (:digests graph) (concat affected retracted-keys))
              memo (volatile! kept-digests)
              _ (doseq [k affected :when (contains? nodes2 k)]
                  (compute-node-digest nodes2 memo k #{}))
              digests' @memo
              tests1 (apply dissoc (:tests graph) (map symbol retracted-keys))
              tests2 (reduce
                      (fn [ts k]
                        (if-let [n (get nodes2 k)]
                          (if (:test? n)
                            (assoc ts (symbol k) (get digests' k))
                            (dissoc ts (symbol k)))
                          ts))
                      tests1 affected)
              contracted' (reduce
                           (fn [cv f]
                             (let [fk (:seon.code.form/key f)
                                   v-eids (map :e (d/datoms db :avet :seon.code.var/form fk))
                                   vs (map #(d/pull db '[:seon.code.var/name :seon.code.var/schema] %) v-eids)
                                   cv1 (into {} (remove #(= fk (val %)) cv))]
                               (reduce (fn [a v]
                                         (if (and (:seon.code.var/name v) (function-schema? (:seon.code.var/schema v)))
                                           (assoc a (:seon.code.var/name v) fk)
                                           a))
                                       cv1 vs)))
                           (into {} (remove #(contains? retracted-keys (val %)) (:contracted-vars graph)))
                           upserts)
              boot-closure (reverse-closure nodes2 (filter #(contains? nodes2 %) boot-root-keys))
              platform-tests (into #{}
                                   (keep (fn [k] (when (get-in nodes2 [k :test?]) (symbol k))))
                                   boot-closure)]
          {:commit to-cid
           :nodes nodes2
           :digests digests'
           :tests tests2
           :contracted-vars contracted'
           :platform-tests platform-tests})
        (catch Throwable _
          (build-reach-graph @conn))))))

;; --- Tick-Bounded REPL Evaluation & Multi-Tool Execution ---

(defn ns->default-path [nsym]
  (let [rel (-> (name nsym)
                (str/replace "." "/")
                (str/replace "-" "_")
                (str ".clj"))]
    (if (str/ends-with? (name nsym) "-test")
      (str "test/" rel)
      (str "src/" rel))))

(defn- persist-forms-by-ns [db ns-entries ir-refs-by-ns]
  (vec
   (mapcat
    (fn [[target-ns srcs]]
      (let [has-ns-form? (some #(str/starts-with? (str/triml %) "(ns ") srcs)
            existing (ns-forms db target-ns)
            existing-local-defs (into #{} (map #(symbol (name (:seon.code.var/name %)))) (ns-vars db target-ns))
            existing-ns-src (some #(when (= (:seon.code.form/key %) (str target-ns "#ns")) (:seon.code.form/src %)) existing)
            header (cond
                     has-ns-form? ""
                     existing-ns-src (str existing-ns-src "\n")
                     :else (str "(ns " target-ns ")\n"))
            combined (str header (str/join "\n" srcs))
            reparsed (read-clj (ns->default-path target-ns) combined existing-local-defs)
            kept-forms (if has-ns-form?
                         (:forms reparsed)
                         (vec (rest (:forms reparsed))))
            ir-extra-refs (get ir-refs-by-ns target-ns #{})
            kept-vars (mapv (fn [v]
                              (if (seq ir-extra-refs)
                                (let [merged-refs (into (set (:seon.code.var/refs v))
                                                        (remove #(= (:seon.code.var/name v) %))
                                                        ir-extra-refs)]
                                  (assoc v
                                         :seon.code.var/refs merged-refs
                                         :seon.code.var/platform-test?
                                         (and (:seon.code.var/test? v)
                                              (boolean (some #(contains? boot-root-keys (str %)) merged-refs)))))
                                v))
                            (:vars reparsed))
            by-key (into {} (map (juxt :seon.code.form/key identity)) existing)
            max-ord (reduce max -1 (map :seon.code.form/order existing))
            ns-chain (ns-parent-chain-tx target-ns)
            ns-ent (let [existing-ns (let [p (d/pull db '[:seon.code.ns/name :seon.code.ns/path :seon.code.ns/tail]
                                                     [:seon.code.ns/name target-ns])]
                                       (when (:seon.code.ns/name p) p))
                         base (if existing-ns
                                (merge (peek ns-chain) existing-ns)
                                (assoc (peek ns-chain)
                                       :seon.code.ns/path (ns->default-path target-ns)
                                       :seon.code.ns/tail "\n"))]
                     (cond-> base
                       (and (or has-ns-form? (nil? existing-ns)) (:doc reparsed))
                       (assoc :seon.code.ns/doc (:doc reparsed))
                       (and (or has-ns-form? (nil? existing-ns)) (seq (:requires reparsed)))
                       (assoc :seon.code.ns/requires (:requires reparsed))))
            stale-ns-reqs (when has-ns-form?
                            (retract-stale-many-ops db :seon.code.ns/name [:seon.code.ns/requires] [ns-ent]))
            [_ form-txs]
            (reduce
             (fn [[next-ord acc] f]
               (let [is-ns-hdr? (str/ends-with? (:seon.code.form/key f "") "#ns")]
                 (if-let [prev (get by-key (:seon.code.form/key f))]
                   [next-ord (conj acc (assoc f
                                              :seon.code.form/order (:seon.code.form/order prev)
                                              :seon.code.form/lead (:seon.code.form/lead prev)))]
                   (if (and is-ns-hdr? (seq existing))
                     (let [min-ord (reduce min 0 (map :seon.code.form/order existing))]
                       [next-ord (conj acc (assoc f
                                                  :seon.code.form/order (dec min-ord)
                                                  :seon.code.form/lead ""))])
                     [(inc next-ord) (conj acc (assoc f
                                                      :seon.code.form/order next-ord
                                                      :seon.code.form/lead (if (zero? next-ord) "" "\n\n")))]))))
             [(inc max-ord) []]
             kept-forms)
            stale-form-deps (retract-stale-many-ops db :seon.code.form/key [:seon.code.form/deps] form-txs)
            stale-var-refs  (retract-stale-many-ops db :seon.code.var/name [:seon.code.var/refs :seon.code.var/schema-refs :seon.code.var/keywords] kept-vars)]
        (vec (concat stale-ns-reqs stale-form-deps stale-var-refs (pop ns-chain) [ns-ent] form-txs kept-vars))))
    ns-entries)))

(defn- format-eval-throwable [^Throwable t tick-budget byte-budget]
  (let [d (ex-data t)]
    (if (:jolt/interrupted d)
      (if (:jolt/ticks-spent d)
        (str "Eval exceeded tick budget (" (:jolt/ticks-spent d) " > " tick-budget
             " ticks). Break up the computation, fix the loop, or set `:seon.config/eval-ticks` on your agent entity.")
        (str "Eval exceeded allocation budget (limit " byte-budget " bytes)."))
      (or (.getMessage t) (str t)))))

(defn infer-result-schema
  "Infers the entity/value schema key of an eval result `v` from explicit `:seon/schema` metadata,
   known entity identity keys, or lookup-ref vectors `[:seon.code.doc/id \"...\"]`."
  [v]
  (or (when (instance? clojure.lang.IObj v) (:seon/schema (meta v)))
      (when (map? v)
        (cond
          (and (contains? v :ok?) (contains? v :problems) (contains? v :counts)) :seon.health/report
          (:seon.code.var/name v) :seon.code.var/entity
          (:seon.code.ns/name v)  :seon.code.ns/entity
          (:seon.code.doc/id v)   :seon.code.doc/entity
          (:seon.schema/key v)    :seon.schema/entity
          (:seon.task/id v)       :seon.task/entity
          (:seon.agent/id v)      :seon.agent/entity
          :else nil))
      (when (and (vector? v) (= 2 (count v)) (qualified-keyword? (first v)))
        (case (first v)
          :seon.code.var/name :seon.code.var/entity
          :seon.code.ns/name  :seon.code.ns/entity
          :seon.code.doc/id   :seon.code.doc/entity
          :seon.schema/key    :seon.schema/entity
          :seon.task/id       :seon.task/entity
          :seon.agent/id      :seon.agent/entity
          nil))))

(defn reaching-tests
  "Reverse BFS over Datahike's indexed `:seon.code.var/refs` (up to 4 hops) from a var symbol
   (e.g. `'seon.render/hiccup->html`) or all vars of a namespace (`'seon.render`), returning
   sorted reaching `:seon.code.var/test?` var symbols."
  [db sym-or-ns]
  (if-not (and db sym-or-ns
               (contains? (:schema db) :seon.code.var/refs)
               (contains? (:schema db) :seon.code.var/ns))
    []
    (let [s (if (string? sym-or-ns) (symbol sym-or-ns) sym-or-ns)
          seeds (if (namespace s)
                  [s]
                  (mapv #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %)))
                        (d/datoms db :avet :seon.code.var/ns s)))]
      (loop [hop 0
             frontier (set (remove nil? seeds))
             visited (set (remove nil? seeds))
             tests #{}]
        (if (or (empty? frontier) (>= hop 4))
          (vec (sort tests))
          (let [next-ents (into []
                                (comp (mapcat #(d/datoms db :avet :seon.code.var/refs %))
                                      (map #(d/pull db '[:seon.code.var/name :seon.code.var/test?] (:e %)))
                                      (filter :seon.code.var/name))
                                frontier)
                new-tests (into tests (keep #(when (:seon.code.var/test? %) (:seon.code.var/name %))) next-ents)
                new-front (into #{}
                                (keep (fn [e]
                                        (let [vn (:seon.code.var/name e)]
                                          (when (and (not (:seon.code.var/test? e))
                                                     (not (contains? visited vn)))
                                            vn))))
                                next-ents)]
            (recur (inc hop) new-front (into visited new-front) new-tests)))))))

(defn ns-linked-docs
  "Returns sorted `{:id :path :heading}` summaries for `:seon.code.doc/*` sections linked to `nsym`
   by path stem (e.g. `seon.render*` -> `docs/render.md`) or body mention of `nsym`."
  ([db nsym] (ns-linked-docs db nsym 8))
  ([db nsym max-n]
   (if-not (and db nsym (contains? (:schema db) :seon.code.doc/id))
     []
     (let [ns-str (name nsym)
           seg2 (second (str/split ns-str #"\."))
           stem-path (when seg2 (str "docs/" seg2 ".md"))]
       (->> (d/datoms db :aevt :seon.code.doc/id)
            (keep (fn [dt]
                    (let [did (str (:v dt))]
                      (when-not (str/starts-with? did "docs/decisions.md#")
                        (let [e (d/pull db '[:seon.code.doc/id :seon.code.doc/path
                                             :seon.code.doc/order :seon.code.doc/heading
                                             :seon.code.doc/body] (:e dt))
                              p (:seon.code.doc/path e "")
                              b (:seon.code.doc/body e "")
                              h (:seon.code.doc/heading e "")]
                          (when (or (and stem-path (= p stem-path) (not (str/blank? h)))
                                    (str/includes? b ns-str)
                                    (str/includes? h ns-str))
                            {:id (:seon.code.doc/id e)
                             :path p
                             :order (or (:seon.code.doc/order e) 0)
                             :heading (str/trim (str/replace h #"^#+\s*" ""))
                             :exact? (boolean (and stem-path (= p stem-path)))}))))))
            (sort-by (fn [d] [(if (:exact? d) 0 1) (:path d) (:order d) (:id d)]))
            (take max-n)
            vec)))))

(defn- next-result-handle [db agent-id]
  (let [n (inc (:eval-count (store/agent-eval-history-stats db (or agent-id :repl))))]
    (symbol "result" (str "e" n))))

(defn eval-forms
  [db {:keys [agent-id turn call-index call-id handle seen-messages thought reasoning intent usage default-ns tick-budget byte-budget]} ^String code-str]
  (let [t0 (mono-fn)
        k0 (ticks-fn)
        b0 (alloc-fn)
        start-ns (create-ns (or default-ns 'user))
        _ (binding [*ns* start-ns] (refer-clojure))
        ticks-cap (long (or tick-budget (store/config-val db agent-id :seon.config/eval-ticks) 100000000))
        bytes-cap (long (or byte-budget (store/config-val db agent-id :seon.config/eval-bytes) 268435456))
        out-sw (java.io.StringWriter.)
        ir-by-ns (volatile! {})
        parsed (try {:ok? true :parsed (read-clj "<repl>" code-str)}
                    (catch Throwable t {:ok? false :error (str "Reader syntax error: " (.getMessage t))}))
        outcome
        (if-not (:ok? parsed)
          {:ok? false :error (:error parsed) :final-ns (ns-name start-ns) :by-ns (array-map)}
          (binding [*ns* start-ns
                    *out* out-sw]
            (reduce
             (fn [acc f]
               (let [src (:seon.code.form/src f)
                     trimmed (str/triml src)
                     ns-before (ns-name *ns*)]
                 (try
                   (let [form-val (read-string src)
                         form-head (when (and (seq? form-val) (symbol? (first form-val)))
                                     (symbol (name (first form-val))))
                         unit-refs (volatile! #{})
                         v (jolt.host/run-interruptible
                            (jolt.host/make-interrupt)
                            (fn []
                              (binding [jolt.analyzer/*unit-tap*
                                        (fn [_form node]
                                          (vswap! unit-refs into (:refs (ir-unit-refs node))))]
                                (eval form-val)))
                            ticks-cap
                            bytes-cap)
                         ns-after (ns-name *ns*)
                         is-ns? (= 'ns form-head)
                         _ (when is-ns? (register-loaded-ns! ns-after))
                         target-ns (if is-ns? ns-after ns-before)
                         _ (when-not (contains? #{'user 'repl} target-ns)
                             (register-loaded-ns! target-ns)
                             (vswap! ir-by-ns update target-ns (fnil into #{}) @unit-refs)
                             (arm-defined-vars-in-ns! db target-ns))
                         scratch-def? (and (= 'def form-head)
                                           (some #(and (symbol? %) (= "user" (namespace %)))
                                                 (tree-seq coll? seq form-val)))
                         recordable? (and (not (contains? #{'user 'repl} target-ns))
                                          (contains? persistable-repl-heads form-head)
                                          (not scratch-def?))]
                     {:ok? true
                      :value v
                      :final-ns ns-after
                      :by-ns (if recordable?
                               (update (:by-ns acc) target-ns (fnil conj []) src)
                               (:by-ns acc))})
                   (catch Throwable t
                     (reduced {:ok? false
                               :error (format-eval-throwable t ticks-cap bytes-cap)
                               :final-ns (ns-name *ns*)
                               :by-ns (:by-ns acc)})))))
             {:ok? true :value nil :final-ns (ns-name start-ns) :by-ns (array-map)}
             (get-in parsed [:parsed :forms]))))
        dk (max 0 (- (ticks-fn) k0))
        dbytes (max 0 (- (alloc-fn) b0))
        dms (/ (Math/round (/ (double (- (mono-fn) t0)) 1e4)) 100.0)
        {:keys [ok? value error final-ns by-ns]} outcome
        eid (random-uuid)
        res-handle (or handle (next-result-handle db agent-id))
        res-schema (when ok? (infer-result-schema value))
        ;; Bind real live Clojure runtime ref `result/e<n>` in the `result` namespace so subsequent
        ;; evals & functions can pass `result/e<n>` directly (with telemetry on `(meta #'result/e<n>)`)!
        _ (when ok?
            (let [rns (create-ns 'result)
                  rv (intern rns (symbol (name res-handle)) value)]
              (alter-meta! rv assoc
                           :seon.eval/id eid
                           :seon.eval/handle res-handle
                           :seon.eval/result-schema res-schema
                           :seon.eval/ms (double dms)
                           :seon.eval/ticks (long dk)
                           :seon.eval/out (str out-sw)
                           :seon.eval/code code-str)))
        persist-tx (when (seq by-ns)
                     (persist-forms-by-ns db by-ns @ir-by-ns))
        eval-ent (cond-> {:seon.agent.eval/id eid
                          :seon.agent.eval/agent (or agent-id :repl)
                          :seon.agent.eval/turn (long (or turn 1))
                          :seon.agent.eval/handle res-handle
                          :seon.agent.eval/code code-str
                          :seon.agent.eval/out (str out-sw)
                          :seon.agent.eval/ticks (long dk)
                          :seon.agent.eval/bytes (long dbytes)
                          :seon.agent.eval/ms (double dms)}
                   call-index (assoc :seon.agent.eval/call-index (long call-index))
                   call-id    (assoc :seon.agent.eval/call-id (str call-id))
                   res-schema (assoc :seon.agent.eval/result-schema res-schema)
                   (seq seen-messages) (assoc :seon.agent.eval/seen-messages (set seen-messages))
                   thought    (assoc :seon.agent.eval/thought thought)
                   reasoning  (assoc :seon.agent.eval/reasoning reasoning)
                   intent     (assoc :seon.agent.eval/intent intent)
                   (:prompt-tokens usage) (assoc :seon.agent.eval/prompt-tokens (long (:prompt-tokens usage)))
                   (:completion-tokens usage) (assoc :seon.agent.eval/completion-tokens (long (:completion-tokens usage)))
                   (:cache-hit-tokens usage) (assoc :seon.agent.eval/cache-hit-tokens (long (:cache-hit-tokens usage)))
                   (:cache-miss-tokens usage) (assoc :seon.agent.eval/cache-miss-tokens (long (:cache-miss-tokens usage)))
                   ok?        (assoc :seon.agent.eval/value (pr-str value))
                   error      (assoc :seon.agent.eval/error error))]
    {:ok? ok?
     :call-id call-id
     :handle res-handle
     :result-schema res-schema
     :value value
     :value-str (when ok? (pr-str value))
     :out (str out-sw)
     :error error
     :ticks (long dk)
     :bytes (long dbytes)
     :ms (double dms)
     :final-ns final-ns
     :tx-data (vec (concat persist-tx [eval-ent]))}))

(defn eval-tool-calls
  [conn {:keys [agent-id turn seen-messages thought reasoning usage start-ns]} tool-calls]
  (loop [idx 0
         cur-ns (or start-ns 'user)
         remaining tool-calls
         results []]
    (if (empty? remaining)
      {:ok? (every? :ok? results)
       :final-ns cur-ns
       :results results}
      (let [{:keys [id code intent]} (first remaining)
            res (eval-forms @conn
                            {:agent-id agent-id
                             :turn turn
                             :call-index idx
                             :call-id (or id (str "call-" idx))
                             :seen-messages (when (zero? idx) seen-messages)
                             :thought (when (zero? idx) thought)
                             :reasoning (when (zero? idx) reasoning)
                             :usage (when (zero? idx) usage)
                             :intent intent
                             :default-ns cur-ns}
                            (or code ":noop"))]
        (d/transact conn {:tx-data (:tx-data res)})
        (recur (inc idx)
               (:final-ns res cur-ns)
               (rest remaining)
               (conj results {:id (or id (str "call-" idx))
                              :handle (:handle res)
                              :result-schema (:result-schema res)
                              :ok? (:ok? res)
                              :value (:value-str res)
                              :out (:out res)
                              :error (:error res)
                              :ticks (:ticks res)
                              :ms (:ms res)}))))))

(defn task-file-path? [^String path] (files/task-file-path? path))

(defn agent-file-path? [^String path] (files/agent-file-path? path))
