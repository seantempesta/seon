;; The entity-vocabulary sweep: rename our "row" vocabulary (an entity, an
;; entity map, transaction data) to Datahike's entity vocabulary, driven by
;; the data in tmp/entity-vocabulary/rename-map.edn.
;;
;; bb tmp/entity-vocabulary/sweep.clj map   <files...>  ; derive rename-map.edn
;; bb tmp/entity-vocabulary/sweep.clj apply <files...>  ; rewrite files in place
;;
;; Code is rewritten node by node with rewrite-clj: symbol tokens by the map,
;; keywords never (they are attributes, schema keys or data), strings and
;; comments through the prose rule. Markdown files take the prose rule only.
(require '[rewrite-clj.parser :as p]
         '[rewrite-clj.node :as n]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pp])

(def map-path "tmp/entity-vocabulary/rename-map.edn")
(def report-path (or (System/getenv "REPORT") "tmp/entity-vocabulary/residue.edn"))

;;; Naming rule -------------------------------------------------------------

(def segment-re #"(?:^|[^a-zA-Z])rows?(?:$|[^a-zA-Z])")
(defn row-name? [s] (boolean (re-find segment-re s)))

(def word {"row" "entity" "rows" "entities" "Row" "Entity" "Rows" "Entities"})

(defn generate
  "The entity spelling of a name: each row/rows segment becomes entity/
  entities; a preceding article segment `a` becomes `an`."
  [nm]
  (let [parts (re-seq #"[a-zA-Z0-9]+|[^a-zA-Z0-9]+" nm)
        v (vec parts)]
    (apply str
           (map-indexed
            (fn [i part]
              (cond
                (word part) (word part)
                (and (#{"a" "A"} part)
                     (word (get v (+ i 2)))
                     (let [d (get v (inc i))] (and d (re-matches #"[- ]" d))))
                (str part "n")
                :else part))
            v))))

;;; File context -------------------------------------------------------------

(defn walk [node f]
  (f node)
  (when (n/inner? node)
    (doseq [c (n/children node)] (walk c f))))

(defn token-value [node]
  (when (and node (= :token (n/tag node)))
    (try (n/sexpr node) (catch Exception _ nil))))

(defn ns-context
  "Aliases and referred names from a file's ns form."
  [root]
  (let [ns-node (first (filter #(and (= :list (n/tag %))
                                     (= 'ns (token-value (first (remove n/whitespace-or-comment? (n/children %))))))
                               (n/children root)))
        form (when ns-node (try (n/sexpr ns-node) (catch Exception _ nil)))
        libspecs (->> (rest form)
                      (filter seq?)
                      (filter #(#{:require :require-macros} (first %)))
                      (mapcat rest)
                      (filter vector?))]
    {:ns (second form)
     :aliases (into {} (keep (fn [spec]
                               (let [opts (apply hash-map (rest spec))
                                     a (or (:as opts) (:as-alias opts))]
                                 (when a [(str a) (str (first spec))]))))
                    libspecs)
     :refers (into {} (mapcat (fn [spec]
                                (let [opts (apply hash-map (rest spec))
                                      r (:refer opts)]
                                  (when (sequential? r)
                                    (map (fn [s] [(str s) (str (first spec))]) r)))))
                   libspecs)}))

(def line-namespaces #{nil "analyzer" "edamame" "seon.lint" "clj-kondo"})
(def line-keywords #{"row" "end-row" "col" "end-col" "name-row" "name-end-row" "name-col" "name-end-col"})

(defn form-context
  "Per top-level form: every symbol name, names tied to keywords by :keys,
  and whether the form speaks of source positions (row = line)."
  [top]
  (let [names (volatile! #{}) tied (volatile! #{}) line? (volatile! false)]
    (walk top
          (fn [node]
            (let [v (token-value node)]
              (cond
                (symbol? v) (do (vswap! names conj (name v))
                                (when (#{"line-number" "end-row" "col" "column" "end-col"} (name v))
                                  (vreset! line? true)))
                (keyword? v) (when (and (line-keywords (name v))
                                        (contains? line-namespaces (namespace v)))
                               (vreset! line? true))))
            (when (= :map (n/tag node))
              (doseq [[k v] (partition 2 (remove n/whitespace-or-comment? (n/children node)))]
                (when (and (keyword? (token-value k))
                           (#{"keys" "syms"} (name (token-value k)))
                           (= :vector (n/tag v)))
                  (doseq [s (n/children v) :let [sv (token-value s)] :when (symbol? sv)]
                    (vswap! tied conj (name sv))))))))
    {:names @names :tied @tied :line? @line?
     :top (when (n/inner? top)
            (some-> (remove n/whitespace-or-comment? (n/children top)) second token-value str))}))

;;; Decisions -----------------------------------------------------------------

(defn load-map [] (edn/read-string (slurp map-path)))

(def residue (atom []))
(defn note! [m] (swap! residue conj m))

(defn deferred-name?
  "A name owned by a file the eval-path rewrite holds."
  [rmap resolved-ns nm]
  (and resolved-ns
       (contains? (get (:deferred-defs rmap) resolved-ns #{}) nm)))

(defn deferred-namespace? [rmap resolved-ns]
  (contains? (set (keys (:deferred-defs rmap))) resolved-ns))

(defn rename-symbol
  [rmap {:keys [file aliases refers ns file-defs defs-after]} {:keys [names tied line? top]} sym]
  (let [nsp (namespace sym) nm (name sym)
        site-to (get-in rmap [:site-overrides file (:site (meta sym))])
        resolved-var (when nsp (get aliases nsp nsp))
        var-to (when resolved-var (get-in rmap [:var-overrides resolved-var nm]))
        form-to (when-not nsp (get-in rmap [:form-overrides file top nm]))
        form-to (or site-to var-to form-to)
        to (or form-to (get-in rmap [:file-overrides file nm]) (get-in rmap [:renames nm :to]))
        resolved (if nsp (get aliases nsp nsp) (get refers nm))]
    (cond
      (nil? to) sym
      form-to (if nsp (symbol nsp form-to) (symbol form-to))
      (contains? (get (:keep-in-file rmap) file #{}) nm) sym
      (deferred-name? rmap (or resolved (when-not nsp (str ns))) nm)
      (do (note! {:file file :kind :deferred :symbol (str sym)}) sym)
      (and (nil? nsp) (contains? tied nm))
      (do (note! {:file file :kind :tied :symbol (str sym)}) sym)
      (and (nil? nsp) line? (contains? (:line-sensitive rmap) nm))
      (do (note! {:file file :kind :line :symbol (str sym)}) sym)
      (and (nil? nsp) (not (contains? file-defs nm)) (contains? defs-after to))
      (do (note! {:file file :kind :shadows-var :symbol (str sym) :with to}) sym)
      (and (nil? nsp) (contains? names to))
      (let [fallback (get-in rmap [:collision-fallback nm])]
        (if (and fallback (not (contains? names fallback)) (not (contains? defs-after fallback)))
          (do (note! {:file file :kind :fallback :symbol (str sym) :with fallback}) (symbol fallback))
          (do (note! {:file file :kind :collision :symbol (str sym) :with to}) sym)))
      :else (if nsp (symbol nsp to) (symbol to)))))

(def skip-before #{"table" "clock" "header" "csv" "markdown" "html" "grid" "matrix" "spreadsheet"})

(def ident-re #"(?<![A-Za-z0-9_.*+!?<>=/'-])(:{0,2})([A-Za-z](?:[A-Za-z0-9_.*+!?<>=-]*[A-Za-z0-9_*+!?<>=-])?(?:/[A-Za-z0-9_.*+!?<>=-]*[A-Za-z0-9_*+!?<>=-])?)")

(def word-re #"(?<![-\w/:.])(?:(\w+)(\s+))?\b(row|rows|Row|Rows)\b(?![-\w/])")

(defn prose-identifiers [rmap ctx fctx text]
  (str/replace text ident-re
               (fn [[whole colons token]]
                 (let [sym (try (symbol token) (catch Exception _ nil))]
                   (cond
                     (seq colons) whole
                     (not (row-name? token)) whole
                     (word token) whole
                     (str/includes? token "_") whole
                     (nil? sym) whole
                     (contains? (:keep rmap) (name sym)) whole
                     (get-in rmap [:renames (name sym) :to])
                     (str (rename-symbol rmap ctx (assoc fctx :names #{} :tied #{}) sym))
                     (namespace sym) whole
                     (:line? fctx) whole
                     :else (generate token))))))

(defn prose-words [text]
  (-> text
      (str/replace word-re
                   (fn [[whole before space w]]
                     (if (contains? skip-before (some-> before str/lower-case))
                       whole
                       (str before space (word w)))))))

(defn prose
  "Rename row vocabulary in text. Identifiers take the symbol decision, plain
  words become entity/entities (a row: an entity), keywords never change."
  [rmap ctx fctx text]
  (if (:line? fctx)
    (prose-identifiers rmap ctx fctx text)
    (-> (str/replace text #"(?<![\w-])([Aa]) (?=[Rr]ows?\b)" "$1n ")
        (->> (prose-identifiers rmap ctx fctx))
        prose-words)))

;;; Tree rewrite --------------------------------------------------------------

(defn rewrite-node [rmap ctx fctx node]
  (let [tag (n/tag node)]
    (cond
      (and (= :token tag) (symbol? (token-value node)))
      (let [s (token-value node)
            s2 (rename-symbol rmap ctx fctx
                              (with-meta s {:site [(:row (meta node)) (:col (meta node))]}))]
        (if (= s s2) node (n/token-node s2)))

      (and (#{:token :multi-line} tag) (string? (token-value node)) (:lines node))
      (let [text (str/join "\n" (:lines node))
            ws? (re-find #"\s" text)
            text2 (if ws?
                    (prose rmap ctx fctx text)
                    ;; an identifier spelled as a string: only a renamed def
                    (let [sym (try (symbol text) (catch Exception _ nil))]
                      (if (and sym (contains? (:def-names rmap) (name sym)))
                        (str (rename-symbol rmap ctx fctx sym))
                        text)))]
        (if (= text text2) node (assoc node :lines (str/split text2 #"\n" -1))))

      (= :comment tag)
      (let [s (:s node) s2 (prose rmap ctx fctx s)]
        (if (= s s2) node (assoc node :s s2)))

      (n/inner? node)
      (n/replace-children node (mapv #(rewrite-node rmap ctx fctx %) (n/children node)))

      :else node)))

(defn rewrite-code [rmap file]
  (let [root (p/parse-file-all file)
        defs (into #{} (keep (fn [top]
                               (let [[head nm-node] (remove n/whitespace-or-comment? (when (n/inner? top) (n/children top)))
                                     nm (token-value (if (= :meta (some-> nm-node n/tag)) (last (n/children nm-node)) nm-node))]
                                 (when (and (symbol? (token-value head))
                                            (str/starts-with? (name (token-value head)) "def")
                                            (symbol? nm))
                                   (name nm)))))
                   (n/children root))
        ctx (assoc (ns-context root) :file file :file-defs defs
                   :defs-after (into #{} (map #(or (get-in rmap [:file-overrides file %])
                                                   (get-in rmap [:renames % :to]) %)) defs))
        out (n/replace-children
             root
             (mapv (fn [top]
                     (let [fctx (form-context top)]
                       (when (:line? fctx)
                         (note! {:file file :kind :line-form :line (:row (meta top))}))
                       (rewrite-node rmap ctx fctx top)))
                   (n/children root)))]
    (n/string out)))

(defn rewrite-markdown [rmap file]
  (prose rmap {:file file :aliases {} :refers {}} {:names #{} :tied #{} :line? false}
         (slurp file)))

;;; Map derivation ------------------------------------------------------------

(defn derive-map [files deferred-files]
  (let [symbols (volatile! {}) defs (volatile! {}) def-names (volatile! #{})]
    (doseq [file files
            :when (re-find #"\.(clj|cljc|cljs|edn|bb)$" file)
            :let [root (p/parse-file-all file)
                  {:keys [ns]} (ns-context root)]]
      (doseq [top (n/children root)
              :let [[head nm-node] (remove n/whitespace-or-comment? (when (n/inner? top) (n/children top)))
                    head (token-value head)
                    nm (token-value (if (= :meta (some-> nm-node n/tag))
                                      (last (n/children nm-node)) nm-node))]]
        (when (and (#{'def 'defn 'defn- 'defmacro 'defonce 'deftest 'def-} head) (symbol? nm)
                   (row-name? (name nm)))
          (vswap! def-names conj (name nm))
          (when (deferred-files file)
            (vswap! defs update (str ns) (fnil conj #{}) (name nm)))))
      (let [{:keys [aliases refers]} (ns-context root)]
        (walk root (fn [node]
                     (let [v (token-value node)]
                       (when (and (symbol? v) (row-name? (name v)))
                         (vswap! symbols update (name v) (fnil inc 0))
                         ;; a deferred file's calls into other namespaces keep
                         ;; their names until the rewrite renames both ends
                         (when (deferred-files file)
                           (when-let [target (if (namespace v)
                                               (get aliases (namespace v) (namespace v))
                                               (get refers (name v)))]
                             (vswap! defs update target (fnil conj #{}) (name v))))))))))
    {:symbols @symbols :deferred-defs @defs :def-names @def-names}))

(defn meaning [nm]
  (cond
    (re-find #"(^|-)tx!?$" nm) "transaction data (entity maps and :db/add operations a writer submits)"
    (re-find #"^\?" nm) "Datalog variable bound to an entity id"
    (> (count (re-seq #"-" nm)) 4) "deftest name"
    (re-find #"s[?!]?$" nm) "entities (maps, ids or transaction data) of one family"
    :else "an entity: an entity map to write, a pulled entity or its id"))

(let [[mode & files] *command-line-args*]
  (case mode
    "map"
    (let [base (edn/read-string (slurp map-path))
          deferred-files (set (:deferred-files base))
          {:keys [symbols deferred-defs def-names]} (derive-map files deferred-files)
          keep-names (set (keys (:keep base)))
          renames (into (sorted-map)
                        (for [[nm cnt] symbols
                              :when (not (keep-names nm))
                              :let [to (get-in base [:overrides nm] (generate nm))]
                              :when (not= to nm)]
                          [nm {:to to :uses cnt :meaning (meaning nm)}]))]
      (spit map-path
            (with-out-str
              (pp/pprint (assoc base
                                :renames renames
                                :def-names (into (sorted-set) def-names)
                                :deferred-defs (into (sorted-map) (update-vals deferred-defs #(into (sorted-set) %)))))))
      (println "renames" (count renames) "deferred-defs" (reduce + (map count (vals deferred-defs)))))

    "apply"
    (let [rmap (update (load-map) :renames
                       #(reduce dissoc % (keys (:keep (load-map)))))
          skip (into (set (:deferred-files rmap)) (keys (:keep-files rmap)))]
      (doseq [file files :when (not (skip file))]
        (let [before (slurp file)
              after (if (str/ends-with? file ".md")
                      (rewrite-markdown rmap file)
                      (rewrite-code rmap file))]
          (when (not= before after) (spit file after))))
      (spit report-path (with-out-str (pp/pprint (frequencies (map #(dissoc % :line) @residue)))))
      (println "residue" (count @residue)))

    nil))
