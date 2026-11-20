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
            [seon.code.parse :as parse]
            [seon.code.io :as cio]))

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

(defn parent-ns-sym {:malli/schema [:=> [:cat :symbol] [:maybe :symbol]], :cost/ticks 50000, :cost/bytes 65536} [nsym] (parse/parent-ns-sym nsym))

(defn ns-parent-chain-tx [& args] (apply parse/ns-parent-chain-tx args))

;; --- Boot / Platform Entrypoints (Automatic Reach Classification, Zero Manual Labels!) ---

(def boot-root-keys parse/boot-root-keys)

(defn boot-file-path? [& args] (apply reach/boot-file-path? args))

(def ^:private persistable-repl-heads parse/persistable-repl-heads)

;; --- Compiler IR Tap (`jolt.analyzer/*unit-tap*`) ---

(defn ir-unit-refs [& args] (apply parse/ir-unit-refs args))

(defn clean-edn-for-hash [& args] (apply parse/clean-edn-for-hash args))

(def watched-runtime-syms parse/watched-runtime-syms)

;; --- Lossless Clojure Parser & Renderer ---

(defn read-clj [& args] (apply parse/read-clj args))

;; --- Lossless Markdown Documentation Parser & Renderer (`0`-byte diff!) ---

(defn md-path? [& args] (apply parse/md-path? args))

(defn schema-file-path? [^String path] (files/schema-file-path? path))

(defn parse-md-sections [& args] (apply parse/parse-md-sections args))

(defn doc-sections [& args] (apply cio/doc-sections args))

(defn render-doc [& args] (apply cio/render-doc args))

;; --- File Import & Reconstruction ---

(defn clj-path? [& args] (apply parse/clj-path? args))

(defn ns-forms [& args] (apply cio/ns-forms args))

(defn ns-vars [& args] (apply cio/ns-vars args))

(defn- retract-stale-many-ops [& args] (apply cio/retract-stale-many-ops args))

(defn file-import-tx [& args] (apply cio/file-import-tx args))

(defn render-ns [& args] (apply cio/render-ns args))

(defn render-path [& args] (apply cio/render-path args))

;; --- O(|Delta|) Branch / Commit Hot-Loader + Automatic Malli Var Instrumentation ---

(defn register-loaded-ns! [nsym]
  (when (and (symbol? nsym) (not= 'user nsym))
    (dosync (commute @#'clojure.core/*loaded-libs* conj nsym))))

(defn sort-forms-topologically [& args] (apply reach/sort-forms-topologically args))

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

(defn changed-form-ops [& args] (apply reach/changed-form-ops args))

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
      (let [existing (ns-forms db target-ns)
            existing-ns-src (some #(when (= (:seon.code.form/key %) (str target-ns "#ns")) (:seon.code.form/src %)) existing)
            bare-ns? (fn [s]
                       (when (str/starts-with? (str/triml s) "(ns ")
                         (let [v (try (read-string s) (catch Throwable _ nil))]
                           (and (seq? v) (= 'ns (first v)) (<= (count v) 2)))))
            srcs (if existing-ns-src (vec (remove bare-ns? srcs)) srcs)
            has-ns-form? (some #(str/starts-with? (str/triml %) "(ns ") srcs)
            existing-local-defs (into #{} (map #(symbol (name (:seon.code.var/name %)))) (ns-vars db target-ns))
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
