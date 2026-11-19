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
            [seon.store :as store]
            [seon.code.reach :as reach]
            [seon.code.parse :as parse :refer [persistable-repl-heads]]))

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

(defn parent-ns-sym [& args] (apply parse/parent-ns-sym args))

(defn ns-parent-chain-tx [& args] (apply parse/ns-parent-chain-tx args))

;; --- Boot / Platform Entrypoints (Automatic Reach Classification, Zero Manual Labels!) ---

(def boot-root-keys parse/boot-root-keys)

(defn boot-file-path? [^String path]
  (or (= path "deps.edn")
      (= path "bin/build")
      (= path "bin/env")
      (str/starts-with? path "native/")))

;; --- Line / Character Span Helpers ---

(defn line-offsets [& args] (apply parse/line-offsets args))

(defn pos->idx [& args] (apply parse/pos->idx args))

(defn lead-end [& args] (apply parse/lead-end args))

;; --- Pure Namespace & Var Metadata Extraction ---

(defn parse-ns-decl [& args] (apply parse/parse-ns-decl args))

(defn collect-keywords [& args] (apply parse/collect-keywords args))

(defn ^:seon.flow/edge extract-var-facts [& args] (apply parse/extract-var-facts args))

;; --- Compiler IR Tap (`jolt.analyzer/*unit-tap*`) ---

(defn ir-unit-refs [& args] (apply parse/ir-unit-refs args))

;; --- Semantic Code Hash & Dependency Extraction ---

(defn strip-docstring [& args] (apply parse/strip-docstring args))

(defn clean-edn-for-hash [& args] (apply parse/clean-edn-for-hash args))

(defn semantic-hash [& args] (apply parse/semantic-hash args))

(defn form-identity [& args] (apply parse/form-identity args))

(defn collect-symbols [& args] (apply parse/collect-symbols args))

(def watched-runtime-syms parse/watched-runtime-syms)

(defn resolve-deps [& args] (apply parse/resolve-deps args))

;; --- Lossless Clojure Parser & Renderer ---

(defn read-clj [& args] (apply parse/read-clj args))

;; --- Lossless Markdown Documentation Parser & Renderer (`0`-byte diff!) ---

(defn md-path? [& args] (apply parse/md-path? args))

(defn schema-file-path? [^String path] (files/schema-file-path? path))

(defn parse-md-sections [& args] (apply parse/parse-md-sections args))

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

(defn clj-path? [& args] (apply parse/clj-path? args))

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
   (let [retracted-by-ns
         (reduce (fn [acc k]
                   (let [[ns-str var-str] (str/split k #"/" 2)]
                     (if (and ns-str var-str (not (str/includes? var-str "#")))
                       (let [nsym (symbol ns-str)]
                         (when-let [ns-obj (find-ns nsym)]
                           (ns-unmap ns-obj (symbol var-str)))
                         (update acc nsym (fnil conj #{}) var-str))
                       acc)))
                 {}
                 retracted-keys)
         ;; If a retracted var was unmapped in a namespace whose forms are also being updated
         ;; (e.g. `:refer` after a namespace split), re-eval any remaining forms in that same ns
         ;; that reference the unmapped symbol so already-compiled closures rebind off the unbound Var!
         upsert-keys (into #{} (map :seon.code.form/key) upserts)
         rebind-forms
         (when (and db (seq retracted-by-ns))
           (for [[nsym unmapped-names] retracted-by-ns
                 f (ns-forms db nsym)
                 :let [fk (:seon.code.form/key f)
                       src (str (:seon.code.form/src f))]
                 :when (and (not (contains? upsert-keys fk))
                            (not (contains? retracted-keys fk))
                            (some #(str/includes? src %) unmapped-names))]
             f))
         all-upserts (if (seq rebind-forms) (into (vec upserts) rebind-forms) upserts)
         touched-nss (volatile! #{})
         errors (volatile! [])]
     (doseq [{:keys [:seon.code.form/key :seon.code.form/ns :seon.code.form/src]} (sort-forms-topologically all-upserts)]
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
             (catch Throwable ex
               (vswap! errors conj {:key key :error (or (.getMessage ex) (str ex))})))
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

(defn schema-kw->node-key [& args] (apply reach/schema-kw->node-key args))

(defn dep-sym->key [& args] (apply reach/dep-sym->key args))

(defn build-reach-graph [& args] (apply reach/build-reach-graph args))

(defn update-reach-graph [& args] (apply reach/update-reach-graph args))

;; --- Tick-Bounded REPL Evaluation & Multi-Tool Execution ---

(defn ns->default-path [& args] (apply parse/ns->default-path args))

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
            [_ raw-form-txs]
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
            merged-all (vals (merge by-key (into {} (map (juxt :seon.code.form/key identity)) raw-form-txs)))
            new-keys (into #{} (remove #(contains? by-key %)) (map :seon.code.form/key raw-form-txs))
            need-reorder?
            (and (seq new-keys)
                 (let [ord-by-k (into {} (map (juxt :seon.code.form/key :seon.code.form/order)) merged-all)]
                   (some (fn [f]
                           (let [co (get ord-by-k (:seon.code.form/key f) 0)]
                             (some (fn [dep]
                                     (let [dk (str dep)]
                                       (and (contains? new-keys dk)
                                            (< co (get ord-by-k dk 0)))))
                                   (:seon.code.form/deps f))))
                         merged-all)))
            form-txs
            (if-not need-reorder?
              raw-form-txs
              (let [ns-hdr? #(str/ends-with? (:seon.code.form/key % "") "#ns")
                    existing-ord (vec (sort-by (fn [f] [(if (ns-hdr? f) 0 1) (:seon.code.form/order f 0) (:seon.code.form/key f "")])
                                               (remove #(contains? new-keys (:seon.code.form/key %)) merged-all)))
                    new-ord (vec (sort-by :seon.code.form/order (filter #(contains? new-keys (:seon.code.form/key %)) raw-form-txs)))
                    ;; Process in reverse order so chains (new-A -> new-B -> existing-C) place new-B before C, then new-A before B!
                    placed (reduce
                            (fn [acc nf]
                              (let [nk (:seon.code.form/key nf)
                                    nsym-dep (when (str/includes? nk "/") (symbol nk))
                                    idx (when nsym-dep
                                          (first (keep-indexed
                                                  (fn [i ef]
                                                    (when (contains? (set (:seon.code.form/deps ef)) nsym-dep) i))
                                                  acc)))]
                                (if idx
                                  (vec (concat (subvec acc 0 idx) [nf] (subvec acc idx)))
                                  (conj acc nf))))
                            existing-ord
                            (reverse new-ord))
                    tx-by-k (into {} (map (juxt :seon.code.form/key identity)) raw-form-txs)]
                (vec
                 (keep-indexed
                  (fn [i f]
                    (let [k (:seon.code.form/key f)]
                      (if-let [changed (get tx-by-k k)]
                        (assoc changed :seon.code.form/order (long i))
                        (when (not= (long i) (long (:seon.code.form/order f -1)))
                          {:seon.code.form/key k :seon.code.form/order (long i)}))))
                  placed))))
            stale-form-deps (retract-stale-many-ops db :seon.code.form/key [:seon.code.form/deps]
                                                    (filter :seon.code.form/src form-txs))
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

(defn reaching-tests [& args] (apply reach/reaching-tests args))

(defn ns-linked-docs [& args] (apply reach/ns-linked-docs args))

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
