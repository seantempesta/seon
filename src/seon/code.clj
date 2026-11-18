(ns seon.code
  "Pure Clojure-as-data engine (:ns/* and :form/*):
   - Zero atoms, zero global state, zero full-DB scans after cold init.
   - Pure lossless parse via Edamame (import -> render is a 0-byte diff, 0 eval during parse).
   - O(|Delta|) hot-loading via Datahike `d/changes` (startup cost depends ONLY on branch diff, never DB size N).
   - O(|Delta| + |callers|) incremental reverse-dependency Merkle reach graph for clojure.test."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.reader.reader-types :as rt]
            [datahike.api :as d]
            [edamame.core :as eda]
            [hasch.core :as hasch]))

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

;; --- Pure Namespace Require / Alias Extraction ---

(defn parse-ns-decl
  "Extracts `{:ns sym :aliases {alias full-ns} :refers {sym full-ns} :requires #{full-ns}}` from `(ns ...)`."
  [form]
  (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
    (let [nsym (second form)
          clauses (filter seq? (drop 2 form))
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
       {:ns nsym :aliases {} :refers {} :requires #{}}
       req-specs))))

;; --- Semantic Code Hash & Dependency Extraction ---

(def ^:private def-heads
  '#{def defn defn- defmacro defonce defmulti defprotocol deftest})

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
  "Pure lossless parse of Clojure `text` at `path` into `{:ns :path :tail :forms}`."
  [path ^String text]
  (let [offsets (line-offsets text)
        ns-ctx (volatile! {:ns 'user :aliases {} :refers {} :requires #{} :kw-aliases {}})
        reader (rt/indexing-push-back-reader text)
        opts {:all true
              :read-eval false
              :read-cond :allow
              :features #{:clj}
              :auto-resolve (fn [alias-kw]
                              (if (= :current alias-kw)
                                (:ns @ns-ctx)
                                (get (:kw-aliases @ns-ctx) alias-kw (symbol (name alias-kw)))))
              :readers (fn [_tag] identity)}]
    (loop [prev-end 0, raw-forms []]
      (let [form (eda/parse-next reader opts)]
        (if (= ::eda/eof form)
          (let [ns-info @ns-ctx
                nsym (:ns ns-info)
                with-ids (mapv #(merge % (form-identity nsym (:form %) (:src %))) raw-forms)
                local-defs (into #{} (keep :def-sym) with-ids)
                ns-deps (into #{} (map #(symbol (name %) "#ns")) (:requires ns-info))
                [_ final-forms]
                (reduce
                 (fn [[seen-keys acc] [order {:keys [form lead src key def-sym test?]}]]
                   (let [ukey (if (contains? seen-keys key) (str key "#" order) key)
                         deps (resolve-deps nsym ns-info local-defs def-sym form)
                         full-deps (if (= ukey (str nsym "#ns"))
                                     ns-deps
                                     (conj deps (symbol (name nsym) "#ns")))]
                     [(conj seen-keys ukey)
                      (conj acc {:form/key ukey
                                 :form/ns nsym
                                 :form/order (long order)
                                 :form/lead lead
                                 :form/src src
                                 :form/hash (semantic-hash nsym form)
                                 :form/deps (vec (sort full-deps))
                                 :form/test? (boolean test?)})]))
                 [#{} []]
                 (map-indexed vector with-ids))]
            {:ns nsym
             :path path
             :tail (subs text prev-end)
             :forms final-forms})
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

(defn file-import-tx
  "Builds Datahike `tx-data` for `path` + `text`. Retracts definitions removed from `nsym`."
  [db path text exec?]
  (if (clj-path? path)
    (let [{:keys [ns tail forms]} (read-clj path text)
          new-keys (into #{} (map :form/key) forms)
          stale (when db
                  (for [old (ns-forms db ns)
                        :when (not (contains? new-keys (:form/key old)))]
                    [:db/retractEntity [:form/key (:form/key old)]]))]
      (vec (concat stale
                   [{:ns/name ns :ns/path path :ns/tail tail}]
                   forms)))
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
  (if-let [ns-dt (first (d/datoms db :avet :ns/path path))]
    (render-ns db (:ns/name (d/pull db '[:ns/name] (:e ns-dt))))
    (:file/text (d/pull db '[:file/text] [:file/path path]))))

;; --- O(|Delta|) Branch / Commit Hot-Loader (Zero Full-DB Scans!) ---

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

(defn apply-overlay!
  "Unmaps `:retracted-keys` and evaluates `:upserts` in topological namespace & form order."
  [{:keys [upserts retracted-keys]}]
  (doseq [k retracted-keys
          :let [[ns-str var-str] (str/split k #"/" 2)]
          :when (and ns-str var-str (not (str/includes? var-str "#")))]
    (when-let [ns-obj (find-ns (symbol ns-str))]
      (ns-unmap ns-obj (symbol var-str))))
  (doseq [{:keys [:form/ns :form/src :form/key]} (sort-forms-topologically upserts)]
    (let [ns-obj (create-ns ns)]
      (binding [*ns* ns-obj]
        (refer-clojure)
        (let [rdr (java.io.PushbackReader. (java.io.StringReader. src))]
          (loop []
            (let [form (read {:eof ::eof} rdr)]
              (when-not (= ::eof form)
                (eval form)
                (recur))))))
      (when (str/ends-with? (str key) "#ns")
        (register-loaded-ns! ns))
      ;; Also ensure any namespace with forms in Datahike is registered as loaded for `:require`
      (register-loaded-ns! ns))))

(defn changed-form-ops
  "O(|Delta|) extraction of `{:upserts [form-map ...] :retracted-keys #{...}}` between `from-cid` and `to-cid`
   using Datahike's commit-changes log (`d/changes`) — NEVER scans the N-sized index!"
  [conn from-cid to-cid]
  (if (= from-cid to-cid)
    {:upserts [] :retracted-keys #{}}
    (let [ds (d/changes conn from-cid to-cid)
          db @conn
          key-aid (get-in db [:ident-ref-map :form/key])
          src-aid (get-in db [:ident-ref-map :form/src])
          relevant? (fn [a] (or (= a :form/key) (= a :form/src) (= a key-aid) (= a src-aid)))
          retracted-keys (into #{}
                               (keep (fn [dt]
                                       (when (and (not (:added dt))
                                                  (or (= (:a dt) :form/key) (= (:a dt) key-aid)))
                                         (:v dt))))
                               ds)
          touched-eids (into #{}
                             (keep (fn [dt] (when (and (:added dt) (relevant? (:a dt))) (:e dt))))
                             ds)
          live-forms (->> touched-eids
                          (keep (fn [eid]
                                  (let [f (d/pull db '[:form/key :form/ns :form/order :form/src
                                                       :form/hash :form/deps :form/test?] eid)]
                                    (when (:form/src f) f))))
                          sort-forms-topologically)
          live-keys (into #{} (map :form/key) live-forms)]
      {:upserts live-forms
       :retracted-keys (set/difference retracted-keys live-keys)})))

(defn load-delta!
  "Loads ONLY the forms changed between `from-cid` and `to-cid` into the current runtime in O(|Delta|) time."
  [conn from-cid to-cid]
  (let [delta (changed-form-ops conn from-cid to-cid)]
    (apply-overlay! delta)
    {:loaded (mapv :form/key (:upserts delta))
     :unmapped (vec (:retracted-keys delta))}))

;; --- O(|Delta| + |Callers|) Incremental Reach Graph for clojure.test ---

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

(defn build-reach-graph
  "Runs ONCE at cold boot: builds `{:nodes {key {:hash :deps :rdeps :test?}} :digests {key digest} :tests {sym digest}}`."
  [db]
  (let [raw-forms (->> (d/datoms db :aevt :form/key)
                       (map #(d/pull db '[:form/key :form/hash :form/deps :form/test?] (:e %))))
        base-nodes (into {}
                         (map (fn [f]
                                [(:form/key f)
                                 {:hash (:form/hash f)
                                  :deps (into #{} (map dep-sym->key) (:form/deps f))
                                  :rdeps #{}
                                  :test? (boolean (:form/test? f))}]))
                         raw-forms)
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
                    nodes)]
    {:commit (d/commit-id db)
     :nodes nodes
     :digests digests
     :tests tests}))

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

(defn update-reach-graph
  "O(|Delta| + |affected callers|) incremental reach update from `(:commit graph)` to `@conn`!
   Never scans unchanged forms."
  [graph conn]
  (let [to-cid (d/commit-id @conn)
        from-cid (:commit graph)]
    (if (= from-cid to-cid)
      graph
      (let [{:keys [upserts retracted-keys]} (changed-form-ops conn from-cid to-cid)
            ;; 1. Collect all callers of modified/removed keys BEFORE mutating edges
            dirty-seeds (into (set retracted-keys) (map :form/key) upserts)
            affected-pre (reverse-closure (:nodes graph) dirty-seeds)
            ;; 2. Remove old forward->reverse edges for retracted + updated keys
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
            ;; 3. Upsert changed/added nodes and wire new reverse edges
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
                    nodes1 upserts)
            ;; 4. Recompute digests ONLY for `affected` closure!
            affected (into affected-pre (reverse-closure nodes2 (map :form/key upserts)))
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
                    tests1 affected)]
        {:commit to-cid
         :nodes nodes2
         :digests digests'
         :tests tests2}))))

;; --- Pure REPL Evaluation -> Branch Transaction ---

(defn ns->default-path [nsym]
  (let [rel (-> (name nsym)
                (str/replace "." "/")
                (str/replace "-" "_")
                (str ".clj"))]
    (if (str/ends-with? (name nsym) "-test")
      (str "test/" rel)
      (str "src/" rel))))

(defn- persist-forms-by-ns [db ns-entries]
  ;; `ns-entries` is `[[nsym ns-src-or-nil [form-src ...]] ...]`
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
            kept-forms (if (or has-ns-form? existing-ns-src)
                         (if has-ns-form?
                           (:forms reparsed)
                           (vec (rest (:forms reparsed))))
                         (vec (rest (:forms reparsed))))
            by-key (into {} (map (juxt :form/key identity)) existing)
            max-ord (reduce max -1 (map :form/order existing))
            ns-ent (or (let [p (d/pull db '[:ns/name :ns/path :ns/tail] [:ns/name target-ns])]
                         (when (:ns/name p) p))
                       {:ns/name target-ns
                        :ns/path (ns->default-path target-ns)
                        :ns/tail "\n"})
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
        (into [ns-ent] form-txs)))
    ns-entries)))

(defn eval-forms
  "Evaluates `code-str` starting in `default-ns`, returning pure `{:ok? :value-str :out :error :tx-data}`
   ready to commit onto the agent's Datahike branch (0 atoms, full multi-namespace support!)."
  [db {:keys [agent-id turn seen-messages thought default-ns]} ^String code-str]
  (let [start-ns (create-ns (or default-ns 'user))
        _ (binding [*ns* start-ns] (refer-clojure))
        out-sw (java.io.StringWriter.)
        parsed (read-clj "<repl>" code-str)
        outcome (binding [*ns* start-ns
                          *out* out-sw]
                  (reduce
                   (fn [acc f]
                     (let [src (:form/src f)
                           trimmed (str/triml src)
                           ns-before (ns-name *ns*)]
                       (try
                         ;; Use `read` + `eval` (NOT `load-string`, which resets `*ns*` on exit!)
                         (let [form (read-string src)
                               v (eval form)
                               ns-after (ns-name *ns*)
                               is-ns? (str/starts-with? trimmed "(ns ")
                               _ (when is-ns? (register-loaded-ns! ns-after))
                               target-ns (if is-ns? ns-after ns-before)
                               _ (when (not= target-ns 'user) (register-loaded-ns! target-ns))
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
                                     :error (or (.getMessage t) (str t))
                                     :final-ns (ns-name *ns*)
                                     :by-ns (:by-ns acc)})))))
                   {:ok? true :value nil :final-ns (ns-name start-ns) :by-ns (array-map)}
                   (:forms parsed)))
        {:keys [ok? value error final-ns by-ns]} outcome
        persist-tx (when (and ok? (seq by-ns))
                     (persist-forms-by-ns db by-ns))
        eval-ent (cond-> {:eval/id (random-uuid)
                          :eval/agent (or agent-id :repl)
                          :eval/turn (long (or turn 1))
                          :eval/code code-str
                          :eval/out (str out-sw)}
                   (seq seen-messages) (assoc :eval/seen-messages (vec seen-messages))
                   thought (assoc :eval/thought thought)
                   ok? (assoc :eval/value (pr-str value))
                   error (assoc :eval/error error))]
    {:ok? ok?
     :value value
     :value-str (when ok? (pr-str value))
     :out (str out-sw)
     :error error
     :final-ns final-ns
     :tx-data (vec (concat persist-tx [eval-ent]))}))
