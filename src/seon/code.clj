(ns seon.code
  "Pure Clojure, Schema & Markdown-as-data engine:
   - Structured indexed entities in Datahike: `:ns/*`, `:form/*`, `:var/*` (`:var/refs`, `:var/schema`,
     `:var/schema-refs`, `:var/ticks-budget`, `:var/platform-test?`), `:schema/*`, and `:doc/*`.
   - Lossless parse & byte-exact reconstruction (`0`-byte diff round-trip for `.clj` and `.md`).
   - Compiler-accurate IR analysis via `jolt.analyzer/*unit-tap*` on eval + structured extraction on import.
   - Tick- & byte-bounded `eval-forms` / `eval-tool-calls` via `jolt.host/run-interruptible` (un-swallowable
     by `catch Throwable`) + automatic Malli `arm-var!` instrumentation.
   - O(|Delta| + |callers|) incremental reverse-dependency Merkle reach graph over forms, vars & schemas,
     automatically identifying platform/boot-reaching changes & tests without manual labels."
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
            [seon.schema :as schema]
            [seon.store :as store]))

(def ^:private ticks-fn (scm/eval-string "(lambda () (jolt-ticks))"))
(def ^:private alloc-fn (scm/eval-string "(foreign-procedure \"(cs)thread_bytes_allocated\" () uptr)"))
(def ^:private mono-fn  (scm/eval-string "(lambda () (jolt-monotonic-ns))"))

(defn measure-call
  "Executes `(thunk)` and returns `{:val :ticks :bytes :ms}`."
  [thunk]
  (let [t0 (mono-fn)
        k0 (ticks-fn)
        b0 (alloc-fn)
        v  (thunk)
        dk (max 0 (- (ticks-fn) k0))
        db (max 0 (- (alloc-fn) b0))
        ms (/ (Math/round (/ (double (- (mono-fn) t0)) 1e4)) 100.0)]
    {:val v :ticks (long dk) :bytes (long db) :ms (double ms)}))

;; --- Boot / Platform Entrypoints (Automatic Reach Classification, Zero Manual Labels!) ---

(def boot-root-keys
  "Form keys & file paths that execute during cold OS boot / Fork Parent init (before Flow hot-swap).
   Any test calling these is automatically classified `:var/platform-test? true`, and any branch
   modifying these (or `deps.edn` / `native/*`) is automatically flagged `:boot-affected? true`!"
  #{"seon.main/-main"
    "seon.main/start-flow-instance!"
    "seon.fork/start-fork-parent!"
    "seon.fork/run-fork-parent-loop!"
    "seon.store/open!"
    "seon.store/attributes"})

(defn boot-file-path? [^String path]
  (or (= path "deps.edn")
      (= path "bin/build")
      (= path "bin/env")
      (str/starts-with? path "native/")))

;; --- Line / Character Span Helpers ---

(defn line-offsets
  "Vector of 0-based character start indices for each 1-based line in `s`."
  [^String s]
  (let [n (.length s)]
    (loop [i 0, acc [0]]
      (if (>= i n)
        acc
        (recur (inc i)
               (if (= (.charAt s i) \newline) (conj acc (inc i)) acc))))))

(defn pos->idx [offsets ^String s row col]
  (min (.length s)
       (+ (nth offsets (dec row) 0) (dec col))))

(defn lead-end
  "Advances past whitespace, commas, and `;` line comments from `i` up to `end`,
   stopping at the exact start character of the top-level form (including any `^meta` prefix!)."
  [^String text i end]
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

(defn parse-ns-decl
  "Extracts `{:ns sym :doc str :aliases {alias full-ns} :refers {sym full-ns} :requires #{full-ns}}` from `(ns ...)`."
  [form]
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

(defn extract-var-facts
  "Extracts a structured `:var/*` entity map from a top-level defining form `(defn / def / deftest ...)`."
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
              arglists (cond
                         (#{'defn 'defn- 'defmacro} hd)
                         (cond
                           (vector? (first after-attr)) [(first after-attr)]
                           (seq? (first after-attr)) (vec (keep #(when (seq? %) (first %)) after-attr))
                           :else nil)
                         (= 'deftest hd) [[]]
                         :else nil)
              malli-sch (:malli/schema merged-meta)
              sch-refs (schema/schema-refs-in-form malli-sch)
              var-refs (vec (sort (remove #(= "#ns" (name %)) deps)))
              is-test? (= 'deftest hd)
              plat-test? (and is-test?
                              (boolean (some #(contains? boot-root-keys (str %)) var-refs)))
              ticks-b (or (:cost/ticks merged-meta) (get-in merged-meta [:seon.test/estimate :ticks]))
              bytes-b (or (:cost/bytes merged-meta) (get-in merged-meta [:seon.test/estimate :bytes]))
              big-o   (:cost/big-o merged-meta)]
          (cond-> {:var/name vsym
                   :var/ns nsym
                   :var/form form-key
                   :var/private? (boolean (or (= 'defn- hd) (:private merged-meta)))
                   :var/macro? (= 'defmacro hd)
                   :var/test? is-test?
                   :var/platform-test? plat-test?}
            doc             (assoc :var/doc doc)
            (seq arglists)  (assoc :var/arglists (pr-str arglists))
            malli-sch       (assoc :var/schema (pr-str malli-sch))
            (seq sch-refs)  (assoc :var/schema-refs (vec (sort sch-refs)))
            (seq var-refs)  (assoc :var/refs var-refs)
            (number? ticks-b) (assoc :var/ticks-budget (long ticks-b))
            (number? bytes-b) (assoc :var/bytes-budget (long bytes-b))
            (string? big-o)   (assoc :var/big-o big-o)))))))

;; --- Compiler IR Tap (`jolt.analyzer/*unit-tap*`) for Exact Post-Macroexpansion Refs ---

(defn ir-unit-refs
  "Walks a `jolt.analyzer` IR node and returns `{:refs #{qualified-sym ...} :keywords #{qualified-kw ...}}`."
  [node]
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

(defn strip-docstring
  "Strips docstrings from defining forms so comment/docstring edits move 0 test digests."
  [form]
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
    (symbol? x) (conj acc x)
    (map? x) (reduce-kv (fn [a k v] (collect-symbols (collect-symbols a k) v)) acc x)
    (coll? x) (reduce collect-symbols acc x)
    :else acc))

(defn resolve-deps [nsym {:keys [aliases refers]} local-defs self-sym form]
  (let [syms (collect-symbols #{} form)]
    (into #{}
          (keep (fn [s]
                  (if-let [ns-part (namespace s)]
                    (let [ns-sym (symbol ns-part)
                          full-ns (get aliases ns-sym ns-sym)]
                      (when-not (str/starts-with? (name full-ns) "clojure.")
                        (symbol (name full-ns) (name s))))
                    (cond
                      (and self-sym (= s self-sym)) nil
                      (contains? local-defs s) (symbol (name nsym) (name s))
                      (contains? refers s) (symbol (name (get refers s)) (name s))
                      :else nil))))
          syms)))

;; --- Lossless Clojure Parser & Renderer (0 eval, 0 atoms) ---

(defn read-clj
  "Pure lossless parse of Clojure `text` at `path` into `{:ns :doc :requires :path :tail :forms :vars}`."
  [path ^String text]
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
                local-defs (into #{} (keep :def-sym) with-ids)
                ns-deps (into #{} (map #(symbol (name %) "#ns")) (:requires ns-info))
                [_ final-forms final-vars]
                (reduce
                 (fn [[seen-keys facc vacc] [order {:keys [form lead src key def-sym test?]}]]
                   (let [ukey (if (contains? seen-keys key) (str key "#" order) key)
                         deps (resolve-deps nsym ns-info local-defs def-sym form)
                         var-ent (extract-var-facts nsym ukey deps form)
                         sch-dep-syms (map #(symbol "schema" (str (namespace %) "__" (name %)))
                                           (:var/schema-refs var-ent))
                         full-deps (if (= ukey (str nsym "#ns"))
                                     ns-deps
                                     (into (conj deps (symbol (name nsym) "#ns")) sch-dep-syms))]
                     [(conj seen-keys ukey)
                      (conj facc {:form/key ukey
                                  :form/ns nsym
                                  :form/order (long order)
                                  :form/lead lead
                                  :form/src src
                                  :form/hash (semantic-hash nsym form)
                                  :form/deps (vec (sort full-deps))
                                  :form/test? (boolean test?)})
                      (cond-> vacc var-ent (conj var-ent))]))
                 [#{} [] []]
                 (map-indexed vector with-ids))]
            {:ns nsym
             :doc (:doc ns-info)
             :requires (vec (sort (:requires ns-info)))
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
                                    :src (subs text start end)}))))))))

;; --- Lossless Markdown Documentation Parser & Renderer (`:doc/*`, 0-byte diff!) ---

(defn md-path? [^String path]
  (and (str/ends-with? path ".md")
       (or (= path "AGENTS.md") (str/starts-with? path "docs/"))))

(defn- heading->slug [^String hline idx]
  (let [clean (-> hline
                  (str/replace #"^#+\s*" "")
                  str/trim
                  str/lower-case
                  (str/replace #"[^a-z0-9]+" "-")
                  (str/replace #"^-+|-+$" ""))]
    (if (str/blank? clean) (str "sec-" idx) clean)))

(defn parse-md-sections
  "Splits Markdown `text` at top/sub-level `#` headings into `:doc/*` entities whose concatenation
   (`(apply str (map #(str (:doc/heading %) (:doc/body %)) sections))`) is 100% byte-identical to `text`!"
  [path ^String text]
  (let [m (re-matcher #"(?m)^#{1,4}\s+[^\n]*(?:\n|$)" text)
        starts (loop [acc []]
                 (if (.find m)
                   (recur (conj acc [(.start m) (.end m) (.group m)]))
                   acc))]
    (if (empty? starts)
      [{:doc/id (str path "#top")
        :doc/path path
        :doc/order 0
        :doc/heading ""
        :doc/body text}]
      (let [preamble (when (pos? (ffirst starts))
                       [{:doc/id (str path "#preamble")
                         :doc/path path
                         :doc/order 0
                         :doc/heading ""
                         :doc/body (subs text 0 (ffirst starts))}])
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
                  (conj acc {:doc/id (str path "#" slug)
                             :doc/path path
                             :doc/order (long (+ base-ord i))
                             :doc/heading hraw
                             :doc/body (subs text e next-s)})]))
             [#{} (vec preamble)]
             (map-indexed vector starts))]
        sections))))

(defn doc-sections [db path]
  (->> (d/datoms db :avet :doc/path path)
       (map #(d/pull db '[:doc/id :doc/path :doc/order :doc/heading :doc/body] (:e %)))
       (sort-by (juxt :doc/order :doc/id))
       vec))

(defn render-doc [db path]
  (let [secs (doc-sections db path)]
    (when (seq secs)
      (apply str (map (fn [s] (str (:doc/heading s "") (:doc/body s ""))) secs)))))

;; --- File Classification, Import & Byte-Exact Reconstruction ---

(defn clj-path? [^String path]
  (and (or (str/starts-with? path "src/")
           (str/starts-with? path "test/"))
       (or (str/ends-with? path ".clj")
           (str/ends-with? path ".cljc"))))

(defn ns-forms
  "O(|forms in nsym|) indexed AVET lookup of forms belonging to `nsym`."
  [db nsym]
  (->> (d/datoms db :avet :form/ns nsym)
       (map #(d/pull db '[:db/id :form/key :form/ns :form/order :form/lead
                          :form/src :form/hash :form/deps :form/test?] (:e %)))
       (sort-by (juxt :form/order :form/key))
       vec))

(defn ns-vars
  "O(|vars in nsym|) indexed AVET lookup of structured `:var/*` entities in `nsym`."
  [db nsym]
  (->> (d/datoms db :avet :var/ns nsym)
       (map #(d/pull db '[*] (:e %)))
       (sort-by #(str (:var/name %)))
       vec))

(defn render-schemas [db]
  (let [ents (->> (d/datoms db :aevt :schema/key)
                  (map #(dissoc (d/pull db '[:schema/key :schema/doc :schema/form :schema/examples :schema/render] (:e %)) :db/id))
                  (sort-by #(str (:schema/key %)))
                  vec)]
    (when (seq ents)
      (str ";; First-class named Malli schemas stored in Datahike (:schema/key entities).\n"
           "["
           (str/join "\n\n " (map pr-str ents))
           "]\n"))))

(defn file-import-tx
  "Builds Datahike `tx-data` for `path` + `text`, extracting structured `:ns/*`, `:form/*`, `:var/*`,
   `:doc/*`, `:schema/*`, and `:config/*` entities cleanly."
  [db path text exec?]
  (cond
    (clj-path? path)
    (let [{:keys [ns doc requires tail forms vars]} (read-clj path text)
          new-keys (into #{} (map :form/key) forms)
          new-vars (into #{} (map :var/name) vars)
          stale-forms (when db
                        (for [old (ns-forms db ns)
                              :when (not (contains? new-keys (:form/key old)))]
                          [:db/retractEntity [:form/key (:form/key old)]]))
          stale-vars (when db
                       (for [old (ns-vars db ns)
                             :when (not (contains? new-vars (:var/name old)))]
                         [:db/retractEntity [:var/name (:var/name old)]]))
          ns-ent (cond-> {:ns/name ns :ns/path path :ns/tail tail}
                   doc (assoc :ns/doc doc)
                   (seq requires) (assoc :ns/requires requires))]
      (vec (concat stale-forms stale-vars [ns-ent] forms vars)))

    (md-path? path)
    (let [secs (parse-md-sections path text)
          new-ids (into #{} (map :doc/id) secs)
          stale-docs (when db
                       (for [old (doc-sections db path)
                             :when (not (contains? new-ids (:doc/id old)))]
                         [:db/retractEntity [:doc/id (:doc/id old)]]))]
      (vec (concat stale-docs secs)))

    (= path "resources/seon/schemas.edn")
    (let [entries (edn/read-string text)
          validated (schema/validate-schema-batch! db entries)]
      (conj validated {:file/path path :file/text text :file/exec? false}))

    (= path "resources/seon/config/default.edn")
    [(store/read-aero-config-tx text)
     {:file/path path :file/text text :file/exec? false}]

    :else
    [{:file/path path :file/text text :file/exec? (boolean exec?)}]))

(defn render-ns
  "O(|forms in nsym|) byte-exact file reconstruction from `db`."
  [db nsym]
  (when-let [ns-ent (d/pull db '[:ns/name :ns/tail] [:ns/name nsym])]
    (when (:ns/name ns-ent)
      (apply str
             (concat (mapcat (fn [f] [(:form/lead f "") (:form/src f "")]) (ns-forms db nsym))
                     [(:ns/tail ns-ent "")])))))

(defn render-path [db path]
  (cond
    (first (d/datoms db :avet :ns/path path))
    (let [ns-dt (first (d/datoms db :avet :ns/path path))]
      (render-ns db (:ns/name (d/pull db '[:ns/name] (:e ns-dt)))))

    (md-path? path)
    (or (render-doc db path)
        (:file/text (d/pull db '[:file/text] [:file/path path])))

    (= path "resources/seon/schemas.edn")
    (or (:file/text (d/pull db '[:file/text] [:file/path path]))
        (render-schemas db))

    :else
    (:file/text (d/pull db '[:file/text] [:file/path path]))))

;; --- O(|Delta|) Branch / Commit Hot-Loader + Automatic Malli Var Instrumentation ---

(defn register-loaded-ns! [nsym]
  (when (and (symbol? nsym) (not= 'user nsym))
    (dosync (commute @#'clojure.core/*loaded-libs* conj nsym))))

(defn sort-forms-topologically
  "Orders `forms` so required namespaces load before requiring namespaces,
   and within each namespace its `#ns` form loads first followed by `:form/order`."
  [forms]
  (let [by-ns (group-by :form/ns forms)
        ns-set (set (keys by-ns))
        ns-deps (into {}
                      (map (fn [[nsym fs]]
                             [nsym (into #{}
                                         (keep (fn [d]
                                                 (let [dns (some-> (namespace d) symbol)]
                                                   (when (and dns (not= dns nsym) (contains? ns-set dns))
                                                     dns))))
                                         (mapcat :form/deps fs))]))
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
                          [(if (str/ends-with? (:form/key f "") "#ns") 0 1)
                           (:form/order f 0)
                           (:form/key f "")])
                        (get by-ns nsym)))
             @ordered-nss))))

(defn- arm-defined-vars-in-ns! [db nsym]
  (when-let [ns-obj (find-ns nsym)]
    (doseq [[_ v] (ns-interns ns-obj)]
      (when (and (not (:private (meta v))) (:malli/schema (meta v)))
        (schema/arm-var! db v)))))

(defn apply-overlay!
  "Unmaps `:retracted-keys`, evaluates `:upserts` in topological namespace & form order,
   and arms Malli instrumentation on any public contracted vars in touched namespaces."
  ([overlay] (apply-overlay! nil overlay))
  ([db {:keys [upserts retracted-keys]}]
   (doseq [k retracted-keys
           :let [[ns-str var-str] (str/split k #"/" 2)]
           :when (and ns-str var-str (not (str/includes? var-str "#")))]
     (when-let [ns-obj (find-ns (symbol ns-str))]
       (ns-unmap ns-obj (symbol var-str))))
   (let [touched-nss (volatile! #{})]
     (doseq [{:keys [:form/ns :form/src]} (sort-forms-topologically upserts)]
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
         (vswap! touched-nss conj ns)))
     (doseq [nsym @touched-nss]
       (arm-defined-vars-in-ns! db nsym)))))

(defn changed-form-ops
  "O(|Delta|) extraction of `{:upserts [form-map ...] :retracted-keys #{...} :schema-keys #{...} :boot-affected? bool}`
   between `from-cid` and `to-cid` using Datahike's commit-changes log (`d/changes`)."
  [conn from-cid to-cid]
  (if (= from-cid to-cid)
    {:upserts [] :retracted-keys #{} :schema-keys #{} :boot-affected? false}
    (let [ds (d/changes conn from-cid to-cid)
          db @conn
          key-aid (get-in db [:ident-ref-map :form/key])
          src-aid (get-in db [:ident-ref-map :form/src])
          sch-aid (get-in db [:ident-ref-map :schema/key])
          fp-aid  (get-in db [:ident-ref-map :file/path])
          relevant-form? (fn [a] (or (= a :form/key) (= a :form/src) (= a key-aid) (= a src-aid)))
          retracted-keys (into #{}
                               (keep (fn [dt]
                                       (when (and (not (:added dt))
                                                  (or (= (:a dt) :form/key) (= (:a dt) key-aid)))
                                         (:v dt))))
                               ds)
          touched-eids (into #{}
                             (keep (fn [dt] (when (and (:added dt) (relevant-form? (:a dt))) (:e dt))))
                             ds)
          schema-eids (into #{}
                            (keep (fn [dt]
                                    (when (or (= (:a dt) :schema/key) (= (:a dt) sch-aid)
                                              (= (:a dt) :schema/form)
                                              (= (:a dt) (get-in db [:ident-ref-map :schema/form])))
                                      (:e dt))))
                            ds)
          schema-keys (into #{}
                            (keep (fn [eid] (:schema/key (d/pull db '[:schema/key] eid))))
                            schema-eids)
          boot-files? (boolean
                       (some (fn [dt]
                               (when (or (= (:a dt) :file/path) (= (:a dt) fp-aid))
                                 (boot-file-path? (str (:v dt)))))
                             ds))
          live-forms (->> touched-eids
                          (keep (fn [eid]
                                  (let [f (d/pull db '[:form/key :form/ns :form/order :form/src
                                                       :form/hash :form/deps :form/test?] eid)]
                                    (when (:form/src f) f))))
                          sort-forms-topologically)
          live-keys (into #{} (map :form/key) live-forms)
          boot-forms? (boolean (some #(contains? boot-root-keys %)
                                     (concat live-keys retracted-keys)))]
      {:upserts live-forms
       :retracted-keys (set/difference retracted-keys live-keys)
       :schema-keys schema-keys
       :boot-affected? (or boot-files? boot-forms?)})))

(defn load-delta!
  "Loads ONLY the forms changed between `from-cid` and `to-cid` into the current runtime in O(|Delta|) time."
  [conn from-cid to-cid]
  (let [delta (changed-form-ops conn from-cid to-cid)]
    (apply-overlay! @conn delta)
    {:loaded (mapv :form/key (:upserts delta))
     :unmapped (vec (:retracted-keys delta))
     :boot-affected? (:boot-affected? delta)}))

;; --- O(|Delta| + |Callers|) Incremental Reach Graph (Forms + Schemas + Platform/Boot Reach!) ---

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

(defn- reverse-closure
  "Finds all keys in `nodes` that transitively depend on `seed-keys` via `:rdeps`."
  [nodes seed-keys]
  (loop [queue (vec seed-keys), seen (set seed-keys)]
    (if (empty? queue)
      seen
      (let [cur (peek queue)
            callers (get-in nodes [cur :rdeps] #{})
            fresh (remove seen callers)]
        (recur (into (pop queue) fresh)
               (into seen fresh))))))

(defn build-reach-graph
  "Runs ONCE at cold boot: builds `{:nodes :digests :tests :contracted-vars :platform-tests}`."
  [db]
  (let [raw-forms (->> (d/datoms db :aevt :form/key)
                       (map #(d/pull db '[:form/key :form/hash :form/deps :form/test?] (:e %))))
        raw-schemas (->> (d/datoms db :aevt :schema/key)
                         (map #(d/pull db '[:schema/key :schema/form] (:e %))))
        contracted (->> (d/datoms db :aevt :var/schema)
                        (keep (fn [dt]
                                (let [v (d/pull db '[:var/name :var/form :var/schema] (:e dt))]
                                  (when (and (:var/name v) (str/starts-with? (str (:var/schema v)) "[:=>"))
                                    [(:var/name v) (:var/form v)]))))
                        (into {}))
        form-nodes (into {}
                         (map (fn [f]
                                [(:form/key f)
                                 {:hash (:form/hash f)
                                  :deps (into #{} (map dep-sym->key) (:form/deps f))
                                  :rdeps #{}
                                  :test? (boolean (:form/test? f))}]))
                         raw-forms)
        sch-nodes (into {}
                        (map (fn [{:keys [:schema/key :schema/form]}]
                               [(schema-kw->node-key key)
                                {:hash (-> (str (hasch/uuid [key form])) (str/replace "-" "") (subs 0 16))
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

(defn update-reach-graph
  "O(|Delta| + |affected callers|) incremental reach update from `(:commit graph)` to `@conn`!
   Never scans unchanged forms."
  [graph conn]
  (let [to-cid (d/commit-id @conn)
        from-cid (:commit graph)]
    (if (= from-cid to-cid)
      graph
      (let [db @conn
            {:keys [upserts retracted-keys schema-keys]} (changed-form-ops conn from-cid to-cid)
            sch-upserts (keep (fn [sk]
                                (let [ent (d/pull db '[:schema/key :schema/form] [:schema/key sk])]
                                  (when (:schema/form ent)
                                    {:key (schema-kw->node-key sk)
                                     :hash (-> (str (hasch/uuid [sk (:schema/form ent)]))
                                               (str/replace "-" "")
                                               (subs 0 16))})))
                              schema-keys)
            dirty-seeds (-> (set retracted-keys)
                            (into (map :form/key) upserts)
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
                      (let [k (:form/key f)
                            deps (into #{} (map dep-sym->key) (:form/deps f))
                            existing-rdeps (get-in ns [k :rdeps] #{})
                            ns' (assoc ns k {:hash (:form/hash f)
                                             :deps deps
                                             :rdeps existing-rdeps
                                             :test? (boolean (:form/test? f))})]
                        (reduce (fn [a dep-k]
                                  (if (contains? a dep-k)
                                    (update-in a [dep-k :rdeps] conj k)
                                    a))
                                ns' deps)))
                    nodes1b upserts)
            affected (into affected-pre
                           (reverse-closure nodes2 (concat (map :form/key upserts) (map :key sch-upserts))))
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
            ;; Update contracted-vars incrementally for touched forms
            contracted' (reduce
                         (fn [cv f]
                           (let [fk (:form/key f)
                                 v-eids (map :e (d/datoms db :avet :var/form fk))
                                 vs (map #(d/pull db '[:var/name :var/schema] %) v-eids)
                                 cv1 (into {} (remove #(= fk (val %)) cv))]
                             (reduce (fn [a v]
                                       (if (and (:var/name v) (str/starts-with? (str (:var/schema v)) "[:=>"))
                                         (assoc a (:var/name v) fk)
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
         :platform-tests platform-tests}))))

;; --- Tick-Bounded REPL Evaluation & Multi-Tool Execution -> Branch Transaction ---

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
            existing-ns-src (some #(when (= (:form/key %) (str target-ns "#ns")) (:form/src %)) existing)
            header (cond
                     has-ns-form? ""
                     existing-ns-src (str existing-ns-src "\n")
                     :else (str "(ns " target-ns ")\n"))
            combined (str header (str/join "\n" srcs))
            reparsed (read-clj (ns->default-path target-ns) combined)
            kept-forms (if has-ns-form?
                         (:forms reparsed)
                         (vec (rest (:forms reparsed))))
            ir-extra-refs (get ir-refs-by-ns target-ns #{})
            kept-vars (mapv (fn [v]
                              (if (seq ir-extra-refs)
                                (let [merged-refs (vec (sort (into (set (:var/refs v))
                                                                   (remove #(= (:var/name v) %))
                                                                   ir-extra-refs)))]
                                  (assoc v
                                         :var/refs merged-refs
                                         :var/platform-test? (and (:var/test? v)
                                                                  (boolean (some #(contains? boot-root-keys (str %))
                                                                                 merged-refs)))))
                                v))
                            (:vars reparsed))
            by-key (into {} (map (juxt :form/key identity)) existing)
            max-ord (reduce max -1 (map :form/order existing))
            ns-ent (or (let [p (d/pull db '[:ns/name :ns/path :ns/tail] [:ns/name target-ns])]
                         (when (:ns/name p) p))
                       (cond-> {:ns/name target-ns
                                :ns/path (ns->default-path target-ns)
                                :ns/tail "\n"}
                         (:doc reparsed) (assoc :ns/doc (:doc reparsed))
                         (seq (:requires reparsed)) (assoc :ns/requires (:requires reparsed))))
            [_ form-txs]
            (reduce
             (fn [[next-ord acc] f]
               (if-let [prev (get by-key (:form/key f))]
                 [next-ord (conj acc (assoc f
                                            :form/order (:form/order prev)
                                            :form/lead (:form/lead prev)))]
                 [(inc next-ord) (conj acc (assoc f
                                                  :form/order next-ord
                                                  :form/lead (if (zero? next-ord) "" "\n\n")))]))
             [(inc max-ord) []]
             kept-forms)]
        (vec (concat [ns-ent] form-txs kept-vars))))
    ns-entries)))

(defn- format-eval-throwable [^Throwable t tick-budget byte-budget]
  (let [d (ex-data t)]
    (if (:jolt/interrupted d)
      (if (:jolt/ticks-spent d)
        (str "Eval exceeded tick budget (" (:jolt/ticks-spent d) " > " tick-budget
             " ticks). Break up the computation, fix the infinite loop, or increase `:config/eval-ticks` on your agent entity.")
        (str "Eval exceeded allocation budget (limit " byte-budget " bytes)."))
      (or (.getMessage t) (str t)))))

(defn eval-forms
  "Evaluates `code-str` starting in `default-ns` inside `jolt.host/run-interruptible` (enforcing
   `:config/eval-ticks` & `:config/eval-bytes` so runaway loops can NEVER hang the system!),
   collects exact post-macroexpansion refs via `jolt.analyzer/*unit-tap*`, arms Malli contracts
   on newly defined functions, and returns pure `{:ok? :value-str :out :error :ticks :bytes :ms :tx-data}`."
  [db {:keys [agent-id turn call-index call-id seen-messages thought default-ns tick-budget byte-budget]} ^String code-str]
  (let [t0 (mono-fn)
        k0 (ticks-fn)
        b0 (alloc-fn)
        start-ns (create-ns (or default-ns 'user))
        _ (binding [*ns* start-ns] (refer-clojure))
        ticks-cap (long (or tick-budget (store/config-val db agent-id :config/eval-ticks) 100000000))
        bytes-cap (long (or byte-budget (store/config-val db agent-id :config/eval-bytes) 268435456))
        out-sw (java.io.StringWriter.)
        ir-by-ns (volatile! {})
        parsed (try {:ok? true :parsed (read-clj "<repl>" code-str)}
                    (catch Throwable t {:ok? false :error (str "Reader syntax error: " (.getMessage t))}))
        outcome
        (if-not (:ok? parsed)
          {:ok? false
           :error (:error parsed)
           :final-ns (ns-name start-ns)
           :by-ns (array-map)}
          (binding [*ns* start-ns
                    *out* out-sw]
            (reduce
             (fn [acc f]
               (let [src (:form/src f)
                     trimmed (str/triml src)
                     ns-before (ns-name *ns*)]
                 (try
                   (let [unit-refs (volatile! #{})
                         v (jolt.host/run-interruptible
                            (jolt.host/make-interrupt)
                            (fn []
                              (binding [jolt.analyzer/*unit-tap*
                                        (fn [_form node]
                                          (vswap! unit-refs into (:refs (ir-unit-refs node))))]
                                (let [form (read-string src)]
                                  (eval form))))
                            ticks-cap
                            bytes-cap)
                         ns-after (ns-name *ns*)
                         is-ns? (str/starts-with? trimmed "(ns ")
                         _ (when is-ns? (register-loaded-ns! ns-after))
                         target-ns (if is-ns? ns-after ns-before)
                         _ (when (not= target-ns 'user)
                             (register-loaded-ns! target-ns)
                             (vswap! ir-by-ns update target-ns (fnil into #{}) @unit-refs)
                             (arm-defined-vars-in-ns! db target-ns))
                         recordable? (and (not= target-ns 'user)
                                          (not (str/starts-with? trimmed "(in-ns"))
                                          (not (str/starts-with? trimmed "(user/")))]
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
        ;; Even if a later expression in the form threw, persist any valid definitions that succeeded before it?
        ;; No: atomic per tool-call (`ok?`), while multi-tool calls isolate errors across tool calls!
        persist-tx (when (and ok? (seq by-ns))
                     (persist-forms-by-ns db by-ns @ir-by-ns))
        eval-ent (cond-> {:eval/id (random-uuid)
                          :eval/agent (or agent-id :repl)
                          :eval/turn (long (or turn 1))
                          :eval/code code-str
                          :eval/out (str out-sw)
                          :eval/ticks (long dk)
                          :eval/bytes (long dbytes)
                          :eval/ms (double dms)}
                   call-index (assoc :eval/call-index (long call-index))
                   call-id    (assoc :eval/call-id (str call-id))
                   (seq seen-messages) (assoc :eval/seen-messages (vec seen-messages))
                   thought    (assoc :eval/thought thought)
                   ok?        (assoc :eval/value (pr-str value))
                   error      (assoc :eval/error error))]
    {:ok? ok?
     :call-id call-id
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
  "Executes a sequence of tool calls `[{:id \"tc-1\" :code \"...\"} ...]` sequentially against `conn`
   in the same live runtime! If tool call #k fails (exception, schema violation, syntax error, or tick
   budget), its error is recorded on its `:eval/*` entity and returned for `#k`, while tool calls
   `#(k+1)..#n` CONTINUE executing normally!"
  [conn {:keys [agent-id turn seen-messages thought start-ns]} tool-calls]
  (loop [idx 0
         cur-ns (or start-ns 'user)
         remaining tool-calls
         results []]
    (if (empty? remaining)
      {:ok? (every? :ok? results)
       :final-ns cur-ns
       :results results}
      (let [{:keys [id code]} (first remaining)
            res (eval-forms @conn
                            {:agent-id agent-id
                             :turn turn
                             :call-index idx
                             :call-id (or id (str "call-" idx))
                             :seen-messages (when (zero? idx) seen-messages)
                             :thought (when (zero? idx) thought)
                             :default-ns cur-ns}
                            (or code ":noop"))]
        (d/transact conn {:tx-data (:tx-data res)})
        (recur (inc idx)
               (:final-ns res cur-ns)
               (rest remaining)
               (conj results {:id (or id (str "call-" idx))
                              :ok? (:ok? res)
                              :value (:value-str res)
                              :out (:out res)
                              :error (:error res)
                              :ticks (:ticks res)
                              :ms (:ms res)}))))))
