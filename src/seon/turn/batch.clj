(ns seon.turn.batch
  "The program stages of the one REPL path, and every entrant's request.

  derive   a form's ordered writes -> its program change: `record-form`,
           clj-kondo's analysis of the text the form read joined by qualified
           name to what it bound, namespaces the form changed, schema keys
           whose registered form the working value does not hold yet,
           removals in write order
  facts    a program change -> transaction data on a database value
  apply    that transaction on the working value through the writer's own
           validation (`seon.db/with`); a refusal is the form's facts error
  fold     applied changes folded by identity: what the batch proposes
  settle   one transaction: the run's evaluations and the folded program,
           already validated form by form; a writer refusal of it (a
           concurrent write changed a checked basis) re-derives each form's
           facts on the new head and drops only the forms now refused
  show     each entry's shown text through the one printer

  Entrants (`turn-request`, `code-request`, `jvm-request`, `test-request`)
  are `seon.batch/evaluate-batch` requests that differ only in these values."
  (:require [clj-kondo.core :as kondo]
            [edamame.core :as edamame]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [sci.impl.vars]
            [datahike.db.utils :as dbu]
            [my.program :as my.program]
            [sci.core :as sci]
            [sci.ctx-store]
            [seon.db :as db]
            [seon.config :as config]
            [seon.fn :as sfn]
            [seon.batch]
            [seon.cluster.source :as source]
            [seon.fn.analyzer :as analyzer]
            [seon.program :as program]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.id :as id]
            [seon.error.refusal]
            [seon.sci.admit :as admit]
            [seon.sci.arm :as arm]
            [seon.sci.batch :as sci.batch]
            [seon.sci.eval :as eval]
            [seon.sci.forgive :as forgive]
            [seon.turn.world :as world]
            [seon.sci.kernel :as kernel]
            [seon.test.accretion :as accretion]))

;;; ---------------------------------------------------------------------------
;;; Analysis and the join
;;; ---------------------------------------------------------------------------

(defn namespace-entity
  "The namespace fact a form's analysis resolves through: SCI's live resolver
  bindings (aliases, refers, requires, imports) of `namespace-name`."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :symbol] :map]}
  [ctx namespace-name]
  (merge {:seon.ns/name namespace-name}
         (#'eval/binding-rows (#'eval/reader-context ctx namespace-name))))

(def ^:private file-attributes
  "The owned attributes a form evaluated from text cannot know: where its declaration lives in a file.
  A redefinition's row omits them and keeps the stored ones (`entity-facts`)."
  [:seon.fn/file :seon.fn/form-span])

(defn- span-of
  "An analyzer entry's [row col end-row end-col]. A usage clj-kondo records
  inside a macro's expansion (the `->` of a `cond->`, the `if` of a `case`)
  has no position: its span is all nil and equals no definition's."
  {:malli/schema [:=> [:cat :map] [:tuple [:maybe int?] [:maybe int?] [:maybe int?] [:maybe int?]]]}
  [{r ::analyzer/row c ::analyzer/col er ::analyzer/end-row ec ::analyzer/end-col}]
  [r c er ec])

(defn captured-locals
  "Locals of `source`'s enclosing form that the definition spanning `span`
  (synthetic [row col end-row end-col] within `source` at row 1) reads: a
  row of it cannot be reacquired from its own span.

  clj-kondo's local analysis, run only for a definition nested inside its
  top-level form. A local shadowing a global counts: it is a local."
  {:malli/schema [:=> [:cat :string [:tuple int? int? int? int?]] [:set :symbol]]}
  [source [r c er ec]]
  (let [{{:keys [locals local-usages]} :analysis}
        (with-in-str source
          (kondo/run! {:lint ["-"] :cache false :config {:output {:format :none} :analysis {:locals true}}}))
        inside? (fn [{row :row col :col}]
                  (and (or (< r row) (and (= r row) (<= c col)))
                       (or (< row er) (and (= row er) (< col ec)))))
        outside (into {} (keep (fn [l] (when-not (inside? l) [(:id l) (:name l)]))) locals)]
    (into #{} (keep (fn [u] (when (inside? u) (get outside (:id u))))) local-usages)))

(defn- text-namespace
  "The namespace an `ns` text declares."
  {:malli/schema [:=> [:cat :string] :symbol]}
  [ns-text]
  (second (edamame/parse-string ns-text {:all true :read-cond :allow :features #{:clj}
                                         :auto-resolve (fn [_] 'user) :readers (fn [_] identity)})))

(defn- used-aliases
  "The aliases the form's analysis resolved a usage or keyword through: local -> target."
  {:malli/schema [:=> [:cat :map] :seon.batch/aliases]}
  [analysis]
  (into {} (concat (keep (fn [u] (when-let [a (::analyzer/alias u)] [a (::analyzer/to u)])) (::analyzer/var-usages analysis))
                   (keep (fn [k] (when-let [a (::analyzer/alias k)] [(symbol (str a)) (symbol (str (::analyzer/ns k)))]))
                         (::analyzer/keywords analysis)))))

(defn- self-require-refusal
  "The refusal of a namespace text that requires its own namespace, as Clojure cannot load it."
  {:malli/schema [:=> [:cat :map :symbol] [:maybe :seon.error/value]]}
  [analysis n]
  (when (some #(= :self-requiring-namespace (::analyzer/type %)) (::analyzer/findings analysis))
    (seon.error.refusal/diagnostic
     (java.util.Date.) :seon.sci.eval/program `record-form
     {:seon.error/message (str "Cyclic load dependency: " n " requires itself; the require is refused and records nothing.")
      :seon.error/offending {n #{n}}
      :seon.error/member :seon.ns/requires
      :seon.error/expected :seon.sci.eval/acyclic-bindings})))

(defn record-form
  "What one evaluated form declares: clj-kondo's analysis of the text it read
  (the namespace's `ns` text and the directives evaluated in it since, or the
  `ns` form `namespace-row` prints to, after the `declare` prelude of the
  program's and `declared`'s names, then `source`) joined by qualified name to
  `bound`, what the evaluation bound. A bound Var the text declares is a row
  (the last occurrence of its name, as the last root write is its value); one
  it does not is the agent's private state. `database` is the program the rows
  resolve against."
  {:malli/schema [:=> [:cat [:map
                             [:seon.batch/source [:maybe :string]]
                             [:seon.batch/bound :seon.batch/bound]
                             [:seon.batch/ns-text {:optional true} :seon.batch/ns-text]
                             [:seon.batch/directives {:optional true} :seon.batch/directives]
                             [:seon.batch/ns {:optional true} :seon.batch/ns]
                             [:seon.batch/namespace-row {:optional true} :map]
                             [:seon.batch/declared {:optional true} [:vector :qualified-symbol]]
                             [:seon.batch/database :seon.db/database-value]]]
                  :seon.batch/record]}
  [{:seon.batch/keys [source bound ns-text directives namespace-row declared database] form-ns :seon.batch/ns}]
  (let [bound (vec (distinct bound))
        private (fn [reason] (mapv (fn [sym] {:seon.batch/sym sym :seon.batch/join :unanalyzed :seon.batch/reason reason}) bound))]
    (if-not source
      {:seon.batch/entities [] :seon.batch/private (private :no-source) :seon.batch/aliases {}}
      (let [n (or form-ns (text-namespace ns-text))
            request (cond-> {:namespace-name n :form-source source :seon.fn/declared (vec declared)}
                      ns-text (assoc :seon.fn/namespace-source ns-text :seon.fn/directives (vec directives))
                      namespace-row (assoc :seon.fn/namespace-row namespace-row))
            {a :seon.fn/analysis text :seon.fn/source [[first-row last-row]] :seon.fn/source-spans
             function-entities :seon.fn/function-rows} (#'sfn/runtime-analysis-batch database [request])
            submitted (#'sfn/source-analysis a first-row last-row)
            functions (into (#'sfn/first-party-function-symbols a) (map :seon.fn/sym) function-entities)
            projection (db/carried-projection database)
            analyzed (#'sfn/analysis-entities-by-file
                      submitted functions {"<stdin>" (#'sfn/text-context text)}
                      (#'sfn/program-targets database (#'sfn/population-targets projection (#'sfn/invoked-attributes submitted)) functions)
                      projection)
            shapes (program/shapes-in projection)
            qualified (fn [d] (symbol (str (::analyzer/ns d)) (str (::analyzer/name d))))
            ;; the last occurrence of each name is its declaration
            definitions (into {} (map (juxt qualified identity)) (::analyzer/var-definitions submitted))
            rows (into {} (keep (fn [entity]
                                  (when-let [sym (or (:seon.fn/sym entity) (:seon.test/sym entity))]
                                    [sym (cond-> (program/declaration-row
                                                  projection (program/canonical-row shapes (apply dissoc entity file-attributes)) :all :agent)
                                           namespace-row (as-> row (assoc row :seon.program/definition-digest
                                                                          (db/definition-digest-of projection row namespace-row))))])))
                       (get analyzed "<stdin>"))
            decided (mapv (fn [sym]
                            (let [d (get definitions sym)
                                  [r c er ec] (when d (span-of d))
                                  captured (when (and d (not= [first-row 1] [r c]))
                                             (captured-locals source [(inc (- r first-row)) c (inc (- er first-row)) ec]))]
                              (cond
                                (not (and d (get rows sym))) {:seon.batch/sym sym :seon.batch/join :unanalyzed
                                                              :seon.batch/reason :not-declared-by-text}
                                (seq captured) {:seon.batch/sym sym :seon.batch/join :unanalyzed
                                                :seon.batch/reason :captures-locals :seon.batch/captured captured}
                                :else (get rows sym))))
                          bound)
            refusal (when ns-text (self-require-refusal a n))]
        (cond-> {:seon.batch/entities (filterv (complement :seon.batch/join) decided)
                 :seon.batch/private (filterv :seon.batch/join decided)
                 :seon.batch/aliases (used-aliases submitted)}
          refusal (assoc :seon.batch/facts-error refusal))))))

;;; ---------------------------------------------------------------------------
;;; derive: one form's writes -> its program change
;;; ---------------------------------------------------------------------------

(defn- live-bindings
  "A reader context's resolver bindings as `seon.sci.eval/row-bindings` shapes them."
  {:malli/schema [:=> [:cat :map] :map]}
  [context]
  {:requires (set (:seon.sci.eval/requires context))
   :aliases (or (:seon.sci.reader/aliases context) {})
   :refers (or (:seon.sci.reader/refers context) {})
   :imports (or (:seon.sci.eval/imports context) {})})

(defn- stored-namespace-row
  "The namespace fact a form's transaction leaves stored for `namespace-name`, as far as a
  definition digests under it: SCI's live bindings when the form changed its resolver state
  (`changed`) or `db` holds no row for it, else the row `db` holds with SCI's live bindings
  laid over it, the ones `namespace-facts` declares when a definition uses them. The writer
  digests a definition under the stored row, so its producer must too."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value [:set :symbol] :symbol] :map]}
  [ctx db changed namespace-name]
  (or (when-not (contains? changed namespace-name)
        (when (dbu/entid db [:seon.ns/name namespace-name])
          (let [stored (db/pull db @#'eval/namespace-pattern [:seon.ns/name namespace-name])
                held (#'eval/row-bindings stored)
                live (live-bindings (#'eval/reader-context ctx namespace-name))]
            (merge {:seon.ns/name namespace-name}
                   (#'eval/binding-rows
                    {:seon.sci.reader/aliases (merge (:aliases held) (:aliases live))
                     :seon.sci.reader/refers (merge (:refers held) (:refers live))
                     :seon.sci.eval/imports (merge (:imports held) (:imports live))
                     
                     :seon.sci.eval/requires (into (:requires held) (:requires live))
                     :seon.sci.eval/fixtures {}})))))
      (namespace-entity ctx namespace-name)))

(defn- changed-schema-keys
  "Schema keys whose form in the batch's delta the working value does not hold."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value] [:set :qualified-keyword]]}
  [{delta :seon.sci.batch/delta} working]
  (if-let [forms (some-> delta :seon.schema.delta/candidate-forms deref)]
    (let [held (:seon.schema.projection/forms (db/carried-projection working))]
      (if (identical? forms held)
        #{}
        (into #{} (keep (fn [k] (when (and (qualified-keyword? k) (not= (get forms k ::absent) (get held k ::absent))) k)))
              (into (set (keys forms)) (keys held)))))
    #{}))

(defn- schema-notes
  "Each registered key the bridge cannot store: not installed, with its reason."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value [:set :qualified-keyword]] [:vector :seon.batch/note]]}
  [{delta :seon.sci.batch/delta :as request} working ks]
  (if (empty? ks)
    []
    (let [candidate (arm/arming-projection request working)]
      (vec (for [k (sort ks) :when (schema/registration-delta-form delta k)
                 :let [why (try (schema.datahike/malli->datahike-attr-in candidate k) nil
                                (catch clojure.lang.ExceptionInfo t (ex-message t)))]
                 :when why]
             {:seon.schema/key k :seon.schema/installed? false :seon.error/message why})))))

(defn derive-form
  "One form's program change from its ordered writes, against `working`."
  {:malli/schema [:=> [:cat :seon.batch/request :map :seon.db/database-value :seon.batch/entry [:vector :seon.batch/write]]
                  [:map [:seon.batch/change :seon.batch/change] [:seon.batch/derive-state :map]]]}
  [{{ctx :seon.sci.eval/ctx} :seon.batch/world :as request} state working entry writes]
  (let [declared (-> (vec (:declared state))
                     (into (keep #(when (= :bind (:seon.batch/op %)) (:seon.batch/sym %))) writes)
                     ;; a `declare` binds no root, so no write names it: the unbound Vars of
                     ;; the form's namespace and of those it wrote join the prelude, so a call
                     ;; to one is an edge, as the file's `declare` makes it
                     (into (for [n (into #{(:seon.batch/ns-before entry)}
                                         (keep #(when (= :namespace (:seon.batch/op %)) (:seon.batch/ns %)))
                                         writes)
                                 :when n
                                 [s v] (get-in @(:env ctx) [:namespaces n])
                                 :when (and (symbol? s) (instance? sci.lang.Var v) (not (sci.impl.vars/hasRoot v)))]
                             (symbol (str n) (str s))))
                     distinct vec)
        ;; the final root write of each Var this form defined (extension
        ;; bookkeeping writes are not definitions)
        finals (vals (reduce (fn [m w] (if (and (= :bind (:seon.batch/op w)) (:seon.batch/sym w)
                                                (= :root (:seon.batch/kind w)))
                                         (assoc m (:seon.batch/var w) w) m))
                             {} writes))
        bound (into [] (comp (filter (set finals)) (map :seon.batch/sym)) writes)
        namespaces (into #{} (comp (filter #(= :namespace (:seon.batch/op %))) (map :seon.batch/ns)) writes)
        namespace-row (partial stored-namespace-row ctx working namespaces)
        n (:seon.batch/ns-before entry)
        recorded (when (seq bound)
                   (record-form {:seon.batch/source (:seon.batch/source entry) :seon.batch/bound bound
                                 :seon.batch/ns n :seon.batch/namespace-row (namespace-row n)
                                 :seon.batch/declared declared :seon.batch/database working}))
        _ (when-let [refusal (:seon.batch/facts-error recorded)]
            (throw (ex-info (:seon.error/message refusal) refusal)))
        ;; the projection each write was armed under (`seon.sci.arm/arm!`):
        ;; it holds a schema this form registered before the batch applies it
        projection (when (seq (:seon.batch/entities recorded)) (arm/arming-projection request working))
        ;; a contracted function's row carries its parsed contract facts
        ;; (:seon.fn/arities, their input/output refs), as the index's rows do;
        ;; its authored contract's predicates resolve as this context resolves
        ;; them, under the projection the write was armed under
        rows (sci.ctx-store/with-ctx ctx
               (into {} (map (fn [row]
                               (let [sym (:seon.fn/sym row (:seon.test/sym row))]
                                 [sym (if-let [spec (:seon.fn/spec row)]
                                        (sfn/contract-facts-row
                                         (kernel/context-bound-projection ctx projection (edn/read-string spec))
                                         (namespace-row (symbol (namespace sym)))
                                         row)
                                        row)])))
                     (:seon.batch/entities recorded)))
        private (into {} (map (juxt :seon.batch/sym identity)) (:seon.batch/private recorded))
        ;; execution order: a later write supersedes an earlier one by identity
        program (reduce (fn [m w]
                          (case (:seon.batch/op w)
                            :bind (let [sym (:seon.batch/sym w)]
                                    (cond
                                      (not (identical? w (some #(when (identical? (:seon.batch/var w) (:seon.batch/var %)) %) finals))) m
                                      (get rows sym) (assoc m sym [:entity (get rows sym)])
                                      :else (assoc m sym [:private (get private sym)])))
                            :unmap (assoc m (:seon.batch/sym w) [:removed])
                            :remove-ns (let [n (:seon.batch/ns w)]
                                         (-> (into {} (remove (fn [[s _]] (= (str n) (namespace s)))) m)
                                             (assoc n [:removed-ns])))
                            m))
                        {} writes)
        removed-ns (into #{} (keep (fn [[k [tag]]] (when (= :removed-ns tag) k))) program)
        entities (vec (keep (fn [[_ [tag x]]] (when (= :entity tag) x)) program))
        schema-keys (changed-schema-keys request working)
        change {:seon.batch/entities entities
                :seon.batch/removals (vec (keep (fn [[k [tag]]] (when (#{:removed :removed-ns} tag) k)) program))
                :seon.batch/schema-keys schema-keys
                :seon.batch/namespaces (into (apply disj namespaces removed-ns)
                                             ;; a definition names its namespace only to create it: a stored
                                             ;; namespace row changes when its resolver state does
                                             (comp (keep #(some-> % :seon.fn/ns second))
                                                   (remove #(dbu/entid working [:seon.ns/name %])))
                                             entities)
                :seon.batch/private (vec (keep (fn [[_ [tag x]]] (when (= :private tag) x)) program))
                :seon.batch/notes (into (schema-notes request working schema-keys)
                                        (mapcat (fn [w] (cond-> []
                                                          (:seon.batch/arm-error w)
                                                          (conj {:seon.batch/sym (:seon.batch/sym w) :seon.batch/arm-error (:seon.batch/arm-error w)})
                                                          (:seon.batch/host-bound-reached w)
                                                          (conj {:seon.batch/sym (:seon.batch/sym w)
                                                                 :seon.batch/host-bound-reached (:seon.batch/host-bound-reached w)}))))
                                        writes)}]
    {:seon.batch/change change :seon.batch/derive-state (assoc state :declared declared)}))

;;; ---------------------------------------------------------------------------
;;; facts: a change -> transaction data on a value
;;; ---------------------------------------------------------------------------

(defn- attribute-declarations
  "Datahike declarations of the attributes among `ks` in `projection`, the
  selection rule applied to the roots that can list them (`ks` and their
  schema dependents), never the whole population."
  {:malli/schema [:=> [:cat :seon.schema/projection [:set :keyword]] [:map-of :keyword :map]]}
  [projection ks]
  (let [roots (into (set ks) (schema/dependent-schema-keys projection (set ks)))
        within (update projection :seon.schema.projection/forms select-keys roots)
        attributes (filterv (set ks) (schema.datahike/database-attributes-in within))]
    (into {} (map (juxt :db/ident identity))
          (schema.datahike/malli->datahike-schema-in projection attributes))))

(defn attribute-change-tx
  "Transaction data adopting a schema change in place, proportional to the
  change: the attributes of the changed keys and their dependents compared
  between `current` and `candidate`, through the one mechanism
  (`seon.cluster/attribute-change-tx`). Replaces seon.turn's
  schema-attribute-change-tx, which compared every attribute of both
  projections (75 ms per call on default)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection :seon.schema/projection [:set :keyword]]
                  :seon.store/transaction-data]}
  [db current candidate schema-keys]
  (let [affected (into (schema/dependent-schema-keys current schema-keys)
                       (schema/dependent-schema-keys candidate schema-keys))
        current-declarations (attribute-declarations current affected)
        candidate-declarations (attribute-declarations candidate affected)
        changed (into #{} (filter #(not= (get current-declarations %) (get candidate-declarations %)))
                      (into (set (keys current-declarations)) (keys candidate-declarations)))
        dependent (into #{} (filter #(contains? candidate-declarations %))
                        (schema.datahike/database-attributes-core-in
                         (update current :seon.schema.projection/forms select-keys
                                 (schema/dependent-schema-keys current schema-keys))))]
    ((requiring-resolve 'seon.cluster/attribute-change-tx)
     db (sort (into changed dependent)) candidate-declarations candidate)))

(defn- schema-tx
  "Declarations and attributes of `keys` as the batch's delta holds them,
  written against `db`. A storable key an agent registered is an attribute
  (owner Q4); an unstorable one is not (`schema-notes` says why)."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value [:set :qualified-keyword]]
                  :seon.store/transaction-data]}
  [{delta :seon.sci.batch/delta :as request} db ks]
  (if (empty? ks)
    []
    (let [candidate (arm/arming-projection request db)
          current (db/carried-projection db)
          forms (:seon.schema.projection/forms candidate)
          selected (set (keys (attribute-declarations candidate (set ks))))
          reason (fn [k] (try (schema.datahike/malli->datahike-attr-in candidate k) nil
                              (catch clojure.lang.ExceptionInfo t (ex-message t))))
          registered (filter #(schema/registration-delta-form delta %) (sort ks))
          ;; compiled-attribute-selection at HEAD selects only entity-listed
          ;; keys; S3 moves this rule there and deletes these two lines
          extra (for [k registered :when (and (not (selected k)) (nil? (reason k)))]
                  (schema.datahike/malli->datahike-attr-in candidate k))
          entities (for [k (sort ks)]
                 (if-let [form (schema/registration-delta-form delta k)]
                   (let [row (program/declaration-row
                              candidate
                              (program/with-contract-facts
                               {:seon.program/row (accretion/schema-row
                                                   forms {:seon.schema/key k :seon.schema/form (pr-str form)
                                                          :seon.schema.admission/source :agent})
                                :seon.program/compile-options (:seon.schema.projection/compile-options candidate)
                                :seon.program/predicate-functions (schema/predicate-functions-in candidate)
                                :seon.program/schema-keys (set (keys forms))
                                :seon.program/schema-forms forms})
                              :all :agent)
                         existing (db/pull db '[*] [:seon.schema/key k])]
                     (if (:db/id existing)
                       (program/exact-replacement-tx existing row)
                       [(assoc row :db/id (str "schema:" k))]))
                   (when-let [id (:db/id (db/pull db [:db/id] [:seon.schema/key k]))]
                     [[:db/retractEntity id]])))]
      (-> (vec (attribute-change-tx db current candidate (set ks)))
          (into extra)
          (into (mapcat identity) entities)))))

(defn- entity-facts
  "`rows`' facts on `db`: none for a row whose stored identity already holds its definition digest; one
  reconciliation by the source owner (`seon.fn/reconcile-tx`, retaining equal refs and components) of every stored
  row replaced, so their transaction-local identities are one population; else the new entity (and its namespace,
  when `db` holds none)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :seon.program/row]] :seon.store/transaction-data]}
  [db rows]
  (let [stored (fn [row] (let [[ident v] (program/row-identity row)]
                           (when-let [e (dbu/entid db [ident v])] (db/pull db '[*] e))))
        [replaced added]
        (reduce (fn [[replaced added] row]
                  (let [existing (stored row)
                        digest (:seon.program/definition-digest row)]
                    (cond
                      ;; the stored definition already is this one: the file link, spans, admission and
                      ;; analysis the stored row owns stay, as redefining a Var never edits its file
                      (and digest (= digest (:seon.program/definition-digest existing))) [replaced added]
                      (:db/id existing) [(conj replaced (merge (select-keys existing file-attributes) row)) added]
                      :else [replaced (conj added row)])))
                [[] []] rows)
        reconciled (if (seq replaced) (sfn/reconcile-tx db replaced []) [])]
    (when (:seon.error/at reconciled) (throw (ex-info (:seon.error/message reconciled) reconciled)))
    (into reconciled
          (mapcat (fn [row]
                    (let [[ident v] (program/row-identity row)
                          ns-attr (if (:seon.fn/ns row) :seon.fn/ns :seon.test/ns)
                          ns-ref (get row ns-attr)
                          missing-ns (when (and (vector? ns-ref) (not (dbu/entid db ns-ref))) (second ns-ref))]
                      (cond-> [(cond-> (assoc row :db/id (str (name ident) ":" v))
                                 missing-ns (assoc ns-attr (str "namespace:" missing-ns)))]
                        missing-ns (conj {:db/id (str "namespace:" missing-ns) :seon.ns/name missing-ns})))))
          added)))

(defn- declared-bindings
  "The bindings the stored namespace's row `stored` leaves declared given `live`, the bindings SCI
  holds for it, and `used`, what its changed declarations use of them
  (`seon.program/used-resolver-context`): a REPL `require` changes the runtime namespace, and reaches
  the namespace's ns form only through a declaration that uses it, as Clojure's own `require` never
  edits a file. A namespace whose resolver state SCI changed (`added-only?` false) keeps of `stored`
  what `live` still holds; one only its declarations named keeps all of `stored`."
  {:malli/schema [:=> [:cat :map :map [:vector :map] :boolean] :map]}
  [stored live used added-only?]
  (let [used-in (fn [k] (into {} (mapcat k) used))
        entries (fn [k] (let [u (used-in k)
                              held (get stored k)]
                          (merge (if added-only? held (into {} (filter (fn [[l t]] (= t (get-in live [k l])))) held))
                                 (into {} (filter (fn [[l t]] (= t (get u l)))) (get live k)))))
        required (fn [r] (some #(contains? (:requires %) r) used))]
    {:requires (into (if added-only? (:requires stored) (into #{} (filter (:requires live)) (:requires stored)))
                     (filter required) (:requires live))
     :aliases (entries :aliases)
     :refers (entries :refers)
     :imports (:imports live)}))

(defn- namespace-facts
  "The namespace fact of `n` as SCI holds it now, on `db`, declaring of a stored namespace's live
  bindings only the ones its stored row or `entities` use (`declared-bindings`); nothing
  for a namespace only `entities` named whose declared bindings are as stored."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value [:vector :map] :symbol :boolean]
                  :seon.store/transaction-data]}
  [{{ctx :seon.sci.eval/ctx} :seon.batch/world} db entities n entity-only?]
  (or (when (sci/find-ns ctx n)
        (let [context (#'eval/reader-context ctx n)
              stored-id (dbu/entid db [:seon.ns/name n])
              stored (when stored-id (#'eval/row-bindings (db/pull db @#'eval/namespace-pattern [:seon.ns/name n])))
              in-n (filterv #(= [:seon.ns/name n] (or (:seon.fn/ns %) (:seon.test/ns %))) entities)
              ;; a class the loaded namespace defines itself is imported
              ;; as it loads (`seon.sci.eval/own-class-imports`), never by
              ;; its `ns` form: the row declares only what its form does
              live (update (live-bindings context) :imports
                           #(apply dissoc % (remove (set (keys (:imports stored))) (keys (eval/own-class-imports n)))))
              kept (when stored
                     (declared-bindings stored live (mapv #(#'program/used-resolver-context % live) in-n) entity-only?))
              context (cond-> context
                        kept (assoc :seon.sci.eval/requires (:requires kept) :seon.sci.reader/aliases (:aliases kept)
                                    :seon.sci.reader/refers (:refers kept) :seon.sci.eval/imports (:imports kept)))]
          (when-not (and entity-only? stored (= kept stored))
            (when-let [row (#'eval/namespace-context-row (db/carried-projection db) db n {} context true)]
              (if stored-id
                ;; a stored namespace changes only the attributes the context row
                ;; carries: its doc, file, tasks and admission source stay, so its
                ;; digest is the digest of the row as it is left stored
                ;; digest is the digest of the row as it is left stored; a row
                ;; whose digest is the stored one is the stored content, and
                ;; writing it again writes nothing
                (let [row (dissoc row :seon.schema.admission/source)
                      row (assoc row :seon.program/definition-digest (db/namespace-digest-of db stored-id row))
                      current (db/pull db '[*] stored-id)]
                  (if (= (:seon.program/definition-digest row) (:seon.program/definition-digest current))
                    []
                    (program/exact-replacement-tx (select-keys current (cons :db/id (keys row))) row)))
                (entity-facts db [row]))))))
      []))

(defn- removal-report
  "A removed Var's or namespace's deletion report on `db`, through the program
  owner, as `[subject report affected]`; nil for a subject with no stored
  declaration (an empty namespace SCI held but never recorded)."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol]
                  [:maybe [:tuple :symbol :seon.program/breakage :seon.program/affected]]]}
  [db subject]
  (when (if (qualified-symbol? subject)
          (or (dbu/entid db [:seon.fn/sym subject]) (dbu/entid db [:seon.test/sym subject]))
          (dbu/entid db [:seon.ns/name subject]))
    (into [subject] (#'my.program/deletion-report db subject))))

(defn- removal-facts
  "Retraction of the declarations of every removal `reports` names, judged as
  one set as `ns-unmap` removes them: a referrer the same change removes holds
  nothing back. Surviving referrers answer the owner's refusal, naming them."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential [:tuple :symbol :seon.program/breakage :seon.program/affected]]]
                  [:or :seon.store/transaction-data :seon.program/mutation-refused-error]]}
  [db reports]
  (let [removed (into #{} (mapcat #(nth % 2)) reports)
        surviving (fn [report]
                    (reduce (fn [report relation] (update report relation #(into #{} (remove removed) %)))
                            report (filter #(set? (get report %)) @#'my.program/blocking-relations)))
        refused (some (fn [[_ report affected]]
                        (let [report (surviving report)]
                          (when (or (#'my.program/blocking? report) (some keyword? affected))
                            (#'my.program/refusal 'seon.turn.batch/removal-facts report affected))))
                      reports)]
    (or refused
        (mapv (fn [target] [:db/retractEntity (:seon.program/identity (#'my.program/locate db target))])
              (sort-by str removed)))))

(defn- binding-targets
  "The namespaces `bindings` load before their namespace: its requires and its refers' namespaces,
  the edges `seon.sci.eval/namespace-order` orders by."
  {:malli/schema [:=> [:cat :map] [:set :symbol]]}
  [{:keys [requires refers]}]
  (into (set requires) (keep (comp #(some-> % symbol) namespace)) (vals refers)))

(defn- stored-targets
  "The namespaces `n`'s stored row in `db` loads before it."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol] [:set :symbol]]}
  [db n]
  (if (dbu/entid db [:seon.ns/name n])
    (binding-targets (#'eval/row-bindings (db/pull db @#'eval/namespace-pattern [:seon.ns/name n])))
    #{}))

(defn- cycle-closing
  "The targets new in `n`'s live `bindings` whose stored requires in `db` lead back to `n`."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol :map] [:set :symbol]]}
  [db n bindings]
  (let [leads-back? (fn [target]
                      (loop [frontier [target] seen #{target}]
                        (cond (empty? frontier) false
                              (contains? seen n) true
                              :else (let [next (into #{} (comp (mapcat #(stored-targets db %)) (remove seen)) frontier)]
                                      (recur (vec next) (into seen next))))))]
    (into #{} (filter leads-back?)
          (disj (reduce disj (binding-targets bindings) (stored-targets db n)) n))))

(defn- refuse-cycle!
  "The refusal of the form whose require of `closing` from `n` would make the program's
  namespaces require each other, as Clojure refuses a cyclic load; `n`'s live
  bindings in `ctx` drop those targets, so the refused require leaves nothing behind."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :symbol :map [:set :symbol]] :seon.error/value]}
  [ctx n bindings closing]
  (sci/install-namespace-bindings!
   ctx n (-> bindings
             (update :requires #(into #{} (remove closing) %))
             (update :aliases #(into {} (remove (comp closing val)) %))
             (update :refers #(into {} (remove (comp closing (fn [target] (some-> target namespace symbol)) val)) %))))
  (seon.error.refusal/diagnostic
   (java.util.Date.) :seon.sci.eval/program `form-facts
   {:seon.error/message (str "Cyclic load dependency: " n " cannot require " (str/join ", " (sort closing))
                             ", which already requires " n "; the require is refused, adds no alias and records nothing."
                             " Require it from a namespace it does not reach, such as user or a test namespace.")
    :seon.error/offending {n closing}
    :seon.error/member :seon.ns/requires
    :seon.error/expected :seon.sci.eval/acyclic-bindings}))

(defn form-facts
  "A program change as transaction data on `db`, or the refusal of a removal
  whose referrers survive, or of a require that would close a namespace cycle."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value :seon.batch/change]
                  [:or :seon.store/transaction-data :seon.program/mutation-refused-error :seon.error/value]]}
  [{{ctx :seon.sci.eval/ctx} :seon.batch/world :as request} db {:seon.batch/keys [entities removals schema-keys namespaces]}]
  (let [stx (schema-tx request db schema-keys)
        ;; entities read the schema this change registers
        db' (if (and (seq stx) (seq entities)) (let [w (db/with db stx)] (if (:seon.error/message w) db w)) db)
        removed (removal-facts db' (into [] (keep #(removal-report db' %)) removals))
        cyclic (some (fn [n]
                       (when (sci/find-ns ctx n)
                         (let [bindings (sci/namespace-bindings ctx n)
                               closing (cycle-closing db' n bindings)]
                           (when (seq closing) (refuse-cycle! ctx n bindings closing)))))
                     (sort namespaces))]
    (or cyclic
        (when (:seon.error/at removed) removed)
        (-> stx
            (into (mapcat #(namespace-facts request db' entities % (not (contains? namespaces %))))
                  (sort (into (set namespaces) (keep #(let [r (or (:seon.fn/ns %) (:seon.test/ns %))] (when (vector? r) (second r)))) entities)))
            (into (entity-facts db' entities))
            (into removed)))))

(defn apply-facts
  "`tx` on `working` through the writer's own validation. A refusal is the
  form's facts error. The REPL admits every definition the writer accepts; a
  definition's contract and its reaching tests are what its Var render shows
  (`seon.render.var`), and merge acceptance is the one refusal point."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value :seon.store/transaction-data :seon.batch/change]
                  [:or [:map [:seon.batch/working :seon.db/database-value]] [:map [:seon.batch/refusal :map]]]]}
  [_request working tx _change]
  (if (empty? tx)
    {:seon.batch/working working}
    (let [after (db/with working tx)]
      (if (and (map? after) (:seon.error/message after))
        {:seon.batch/refusal after}
        {:seon.batch/working after}))))

(defn fold-change
  "An applied change folded into the batch's program by identity."
  {:malli/schema [:=> [:cat :seon.batch/request :map :seon.batch/change] :map]}
  [_request program {:seon.batch/keys [entities removals schema-keys namespaces]}]
  (as-> program p
    (reduce (fn [p r] (assoc-in p [:entities (second (program/row-identity r))] r)) p entities)
    (reduce (fn [p s] (if (qualified-symbol? s)
                        (assoc-in p [:entities s] :removed)
                        (-> p
                            (update :entities #(into {} (map (fn [[k v]] (if (= (str s) (namespace k)) [k :removed] [k v]))) %))
                            (update :namespaces (fnil disj #{}) s)
                            (update :removed-namespaces (fnil conj #{}) s))))
            p removals)
    (update p :schema-keys (fnil into #{}) schema-keys)
    (update p :namespaces (fnil into #{}) namespaces)))

;;; ---------------------------------------------------------------------------
;;; settle: the one recording transaction
;;; ---------------------------------------------------------------------------

(defn- changed-since-basis
  "The refusal of a change whose declarations another writer changed after
  the batch read them: an entity whose stored `:seon.program/definition-digest`
  on `head` differs from the one on the batch's basis, or a schema key the
  batch registers whose stored `:seon.schema/form` differs. Detect and push
  back, never lock: the author re-evaluates against the current declaration.
  nil when nothing changed underneath."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value [:vector :seon.program/row]
                       [:set :qualified-keyword]]
                  [:maybe :seon.error/value]]}
  [request head entities schema-keys]
  (when-let [basis (:seon.batch/basis request)]
    (when-not (identical? basis head)
      (let [stored (fn [database attribute lookup]
                     (get (db/pull database [attribute] lookup) attribute))
            differs? (fn [attribute lookup]
                       (not= (stored basis attribute lookup) (stored head attribute lookup)))
            changed (-> []
                        (into (keep (fn [row]
                                      (let [lookup (program/row-identity row)]
                                        (when (and lookup (differs? :seon.program/definition-digest lookup))
                                          lookup))))
                              entities)
                        (into (keep (fn [k]
                                      (let [lookup [:seon.schema/key k]]
                                        (when (differs? :seon.schema/form lookup) lookup))))
                              (sort schema-keys)))]
        (when (seq changed)
          (seon.error.refusal/diagnostic
           (java.util.Date.) :seon.turn.batch/program 'seon.turn.batch/program-facts
           {:seon.error/message (str "Definitions in :seon.error/offending changed by another writer since this run read them."
                                     " Evaluate your definition again against the current one.")
            :seon.error/offending changed
            :seon.error/member :seon.program/definition-digest
            :seon.error/expected :seon.program/definition-digest}))))))

(defn removal-facts-call
  "The retractions of `removals` on `db`, the writer's value after the same settlement's definitions (`program-facts`
  orders this call after them): a removal is judged as its request's forms ran, so a caller the request redefined
  off the removed helper no longer holds it back. A refusal is the writer's refusal of the settlement."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :symbol]] :seon.store/transaction-data]}
  [db removals]
  ;; a transaction function's throw is the writer's refusal of this settlement
  (let [facts (removal-facts db (into [] (keep #(removal-report db %)) removals))]
    (if (:seon.error/at facts)
      (throw (ex-info (:seon.error/message facts) facts))
      facts)))

(defn program-facts
  "The folded program as transaction data on `head`, or the refusal of a
  definition changed since the batch's basis; its removals follow its
  definitions as one `removal-facts-call`, judged by the writer on the value
  those definitions leave."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value :map]
                  [:or :seon.store/transaction-data :seon.program/mutation-refused-error :seon.error/value]]}
  [request head {:keys [entities schema-keys namespaces removed-namespaces]}]
  (let [rows (vec (keep (fn [[_ r]] (when (map? r) r)) entities))
        removals (into (vec (keep (fn [[s r]] (when (= :removed r) s)) entities)) removed-namespaces)]
    ;; decided at the writer: a definition changed underneath the run refuses
    (or (changed-since-basis request head rows (or schema-keys #{}))
        (let [facts (form-facts request head
                                {:seon.batch/entities rows
                                 :seon.batch/removals []
                                 :seon.batch/schema-keys (or schema-keys #{})
                                 :seon.batch/namespaces (or namespaces #{})})]
          (cond-> facts
            (and (vector? facts) (seq removals)) (conj [:db.fn/call #'removal-facts-call removals]))))))

(defn program-facts-call
  "The folded program's facts, derived at the writer on its head: a turn's one
  settlement commits them beside its evaluations (`seon.turn/settle-batch!`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.batch/request :map] :seon.store/transaction-data]}
  [db request program]
  ;; a transaction function's throw is the writer's refusal of this settlement
  (let [facts (program-facts request db program)]
    (if (:seon.error/at facts)
      (throw (ex-info (:seon.error/message facts) facts))
      facts)))

(defn private-fact
  "`:seon.eval/private`: the Vars this entry's change classed as the agent's private
  state (`record-form`), the one decision the reply's note reads; nil when none."
  {:malli/schema [:=> [:cat :seon.batch/entry] [:maybe [:map [:seon.eval/private [:set :qualified-symbol]]]]]}
  [{private :seon.batch/private}]
  (when (seq private)
    {:seon.eval/private (into #{} (map :seon.batch/sym) private)}))

(defn- entry-evaluation
  "One settled entry's terminal facts, as its run records them and its reply
  prints them."
  {:malli/schema [:=> [:cat :seon.batch/entry] :seon.turn.loop/evaluation]}
  [{:seon.batch/keys [shown shown-map error facts-error duration-ms] :as entry}]
  (cond-> (or (private-fact entry) {})
    ;; the reply's :ms, as an SCI evaluation records it
    duration-ms (assoc :seon.eval/duration-ms (Math/round (double duration-ms)))
    shown (assoc :seon.eval/shown shown)
    ;; what the value was, as the show stage recorded it (`seon.sci.eval/shown-result`)
    (:seon.eval/value-class shown-map) (assoc :seon.eval/value-class (:seon.eval/value-class shown-map))
    (contains? entry :seon.batch/error)
    (assoc :seon.cluster.eval/error (or (ex-message error) (.getName (class error))))
    facts-error (assoc :seon.cluster.eval/error (str "facts not proposed: " (:seon.error/message facts-error)))
    (and (nil? shown) (not (contains? entry :seon.batch/error)) (nil? facts-error))
    (assoc :seon.eval/shown "nil")))

(defn- defined-symbols
  "The symbols one change defines (functions and tests) or removes."
  {:malli/schema [:=> [:cat :seon.batch/change] [:set :symbol]]}
  [{entities :seon.batch/entities removals :seon.batch/removals}]
  (into (set removals) (keep #(or (:seon.fn/sym %) (:seon.test/sym %))) entities))

(defn- prior-uncontracted
  "The symbols the agent's earlier evaluations recorded as defined with an incomplete contract."
  {:malli/schema [:=> [:cat :seon.db/database-value :string] [:set :qualified-symbol]]}
  [database agent-id]
  (set (db/q '[:find [?s ...] :in $ ?agent
               :where [?e :seon.eval/uncontracted ?s]
               [?e :seon.cluster.eval/run ?t]
               [?t :seon.turn/agent ?agent]]
             database [:seon.agent/id agent-id])))

(defn- gap-fact
  "One contract gap (`seon.cluster.source/contract-gaps`) as `:seon.eval/contract-gaps` holds it: function,
  finding, position kind and input index (-1 for a position that is no input)."
  {:malli/schema [:=> [:cat :seon.fn.contract/finding-row] [:tuple :qualified-symbol :qualified-keyword :qualified-keyword :int]]}
  [{sym :seon.fn/sym finding :seon.fn.contract/finding position :seon.fn.contract/position}]
  (let [[kind index] (if (vector? position) position [position -1])]
    [sym finding kind index]))

(defn- judged-database
  "`working` with the last change among `entries` applied: the batch applies a change to its working value only
  when a later form can read it, so the last one's functions are not yet there to be judged."
  {:malli/schema [:=> [:cat :map :seon.db/database-value [:sequential :map]] :seon.db/database-value]}
  [request working entries]
  (let [change (some :seon.batch/change (rseq (vec entries)))
        facts (when change (form-facts request working change))
        applied (when (vector? facts) (db/with working facts))]
    (if (and applied (not (:seon.error/message applied))) applied working)))

(defn uncontracted-measures
  "Per ordinal, for each entry whose change defines functions that `working` (with the last change applied) holds with an incomplete contract
  (`seon.cluster.source/contract-gaps`, the one rule: missing, :any or :some): `:seon.eval/uncontracted` (those
  symbols), `:seon.eval/contract-gaps` (each gap's slot) and `:seon.eval/uncontracted-count`, the agent's
  functions still with a gap after that entry (earlier evaluations' still with one, less what the batch
  redefined or removed, plus this form's). One read, and only when some entry defines a function that carries a
  contract (`seon.fn/contract-carrier?`): a value definition, macro or host-bound declaration has none to miss."
  {:malli/schema [:=> [:cat :map [:maybe :seon.db/database-value] [:maybe :string] [:sequential :map]]
                  [:map-of :seon.cluster.eval/ordinal :map]]}
  [request working agent-id entries]
  (let [own (fn [{change :seon.batch/change}]
              (into #{} (comp (filter sfn/contract-carrier?) (keep :seon.fn/sym)) (:seon.batch/entities change)))]
    (if-not (and working agent-id (some (comp seq own) entries))
      {}
      (let [judged (judged-database request working entries)
            prior (prior-uncontracted judged agent-id)
            gaps (group-by :seon.fn/sym
                           (source/contract-gaps judged (into #{} (map #(vector :seon.fn/sym %)) (into prior (mapcat own) entries))))]
        (loop [[entry & more] entries
               held (set (filter gaps prior))
               measures {}]
          (if-not entry
            measures
            (let [added (set (filter gaps (own entry)))
                  held (into (reduce disj held (some-> entry :seon.batch/change defined-symbols)) added)]
              (recur more held
                     (cond-> measures
                       (seq added) (assoc (:seon.batch/ordinal entry)
                                          {:seon.eval/uncontracted added
                                           :seon.eval/contract-gaps (into #{} (comp (mapcat gaps) (map gap-fact)) added)
                                           :seon.eval/uncontracted-count (count held)}))))))))))

(defn- admitted-form
  "The form one entry read, where it ran."
  {:malli/schema [:=> [:cat :seon.batch/entry] :seon.turn.loop/admitted-form]}
  [{:seon.batch/keys [source ns-before]}]
  {:seon.cluster.eval/source (or source "")
   :seon.cluster.eval/ns [:seon.ns/name (or ns-before 'user)]})

(defn- evaluation-record
  "One entry as the recording writer's terminal evaluation."
  {:malli/schema [:=> [:cat :string :seon.batch/entry [:maybe :map]]
                  [:and :seon.cluster.eval/settle-request
                   [:map
                    [:seon.cluster.eval/at :seon.cluster.eval/at]
                    [:seon.cluster.eval/source :seon.cluster.eval/source]
                    [:seon.cluster.eval/ns :seon.cluster.eval/ns]
                    [:seon.cluster.eval/origin [:= :outside]]]]]}
  [turn-id {ordinal :seon.batch/ordinal :as entry} measures]
  (merge {:seon.turn/id turn-id
          :seon.cluster.eval/ordinal ordinal
          :seon.cluster.eval/at (java.util.Date.)
          :seon.cluster.eval/origin :outside}
         (admitted-form entry)
         (entry-evaluation entry)
         measures))

(defn evaluated-sources
  "The batch's settled entries as held forms, each with the terminal facts its
  run recorded (`record-facts`): what the run's reply prints."
  {:malli/schema [:=> [:cat :seon.batch/batch] :seon.turn.loop/evaluated-sources]}
  [batch]
  (let [measures (uncontracted-measures batch (:seon.batch/working batch) (get-in batch [:seon.batch/world :seon.agent/id])
                                        (:seon.batch/entries batch))]
    (mapv (fn [{ordinal :seon.batch/ordinal :as entry}]
            {:seon.cluster.eval/ordinal ordinal
             :seon.turn.loop/admitted-form (admitted-form entry)
             :seon.sci.eval/evaluation (merge (entry-evaluation entry) (get measures ordinal))})
          (:seon.batch/entries batch))))

(defn record-facts
  "The run and its evaluations, through the recording writer."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.store/transaction-data]}
  [{{agent-id :seon.agent/id turn-id :seon.turn/id} :seon.batch/world basis :seon.batch/basis start-ns :seon.batch/start-ns
    record-call :seon.turn.batch/record-call :as request} batch]
  (let [entries (:seon.batch/entries batch)]
    ;; a batch with no agent or no form records nothing: the empty transaction
    (if-not (and agent-id (seq entries))
      []
      [[:db.fn/call record-call
        {:seon.turn/id turn-id :seon.turn/agent [:seon.agent/id agent-id]
         :seon.turn/starting-ns [:seon.ns/name (or start-ns 'user)]
         :seon.turn/opened-tx (db/basis-t basis) :seon.turn/closed-tx (db/basis-t basis)
         :seon.turn/reply-size (count (:seon.batch/text batch ""))
         :seon.turn/sources (mapv (fn [e] {:seon.cluster.eval/source (or (:seon.batch/source e) "")
                                       :seon.ns/name (or (:seon.batch/ns-before e) 'user)}) entries)
         :seon.turn/evaluations (let [measures (uncontracted-measures request (:seon.batch/working batch) agent-id entries)]
                                  (mapv #(evaluation-record turn-id % (get measures (:seon.batch/ordinal %))) entries))}]])))

(defn revalidate
  "Each form's change re-derived as facts on `head`, in order, through the
  writer's validation: a form the head now refuses gets its facts error and
  loses its change; the rest fold. Answers the working value, the program and
  the entries."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value
                       [:vector [:map [:seon.batch/ordinal :seon.cluster.eval/ordinal]
                                 [:seon.batch/change {:optional true} :seon.batch/change]]]]
                  [:tuple :seon.db/database-value :map [:vector :map]]]}
  [request head entries]
  (reduce (fn [[w p es] e]
            (if-let [change (:seon.batch/change e)]
              (let [facts (or (changed-since-basis request head (:seon.batch/entities change)
                                                        (or (:seon.batch/schema-keys change) #{}))
                              (form-facts request w change))
                    ;; the removal's refusal whole, with its referrers: the
                    ;; refactoring data it exists to give (a message alone lost it)
                    r (if (:seon.error/at facts)
                        {:seon.batch/refusal facts}
                        (apply-facts request w facts change))]
                (if-let [refusal (:seon.batch/refusal r)]
                  [w p (conj es (-> e (dissoc :seon.batch/change) (assoc :seon.batch/facts-error refusal)))]
                  [(:seon.batch/working r) (fold-change request p change) (conj es e)]))
              [w p (conj es e)]))
          [head {} []] entries))

(defn- rebase
  "The batch's changes revalidated on `head` (`revalidate`)."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch :seon.db/database-value] :seon.batch/batch]}
  [request batch head]
  (let [[working program entries] (revalidate request head (:seon.batch/entries batch))]
    (assoc batch :seon.batch/working working :seon.batch/program program :seon.batch/entries entries)))

(defn settle
  "Commit the batch: its recording and its folded program in one transaction
  on the head of the world's settlement connection, through the world's
  writer. Content was validated form by form; if the writer refuses anyway
  (the head moved under a checked basis), each form is re-derived on the head
  and only the forms it now refuses lose their facts, logged as their errors.
  A storage failure keeps every value and answers the error."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/commit]}
  [{world :seon.batch/world :as request} batch]
  (let [conn (world/settlement-connection world)
        writer (:seon.db/writer world)
        head (db/db conn)
        ;; under the settlement connection's own custody, never the calling
        ;; thread's: an entrant called from inside another evaluation records
        ;; on its own world's branch
        commit! (fn [tx] (let [r (binding [seon.batch/*world* world]
                                    (db/with-branch {:seon.db/connection conn} #(writer conn tx)))]
                           (if (and (map? r) (:seon.error/message r)) r (:db-after r))))
        attempt (fn [b]
                  (let [tx (try (into (vec (record-facts request b)) (program-facts request head (:seon.batch/program b)))
                                (catch Throwable t {:seon.error/message (ex-message t) :seon.error/data (ex-data t)}))]
                    (cond
                      (map? tx) {:seon.batch/writer-refusal tx}
                      (empty? tx) {:seon.batch/applied true :seon.batch/tx-count 0}
                      :else (let [r (commit! tx)]
                              (if (and (map? r) (:seon.error/message r))
                                {:seon.batch/writer-refusal r}
                                {:seon.batch/applied true :seon.batch/tx-count (count tx) :seon.batch/after r})))))
        settled (fn [b] (select-keys b [:seon.batch/entries :seon.batch/program :seon.batch/working]))
        first-try (try (attempt batch) (catch Throwable t {:seon.batch/storage-error (ex-message t)}))]
    (if-not (:seon.batch/writer-refusal first-try)
      ;; every path answers the state it settled
      (assoc first-try :seon.batch/settled (settled batch))
      ;; the head moved: re-derive on it; the settled state is what commits
      (let [rebased (rebase request batch head)
            second-try (try (attempt rebased) (catch Throwable t {:seon.batch/storage-error (ex-message t)}))]
        (assoc second-try
               :seon.batch/settled (settled rebased)
               :seon.batch/first-refusal (:seon.batch/writer-refusal first-try))))))

(defn- printed
  "The show stage's answer from the one printer's shown value, kept whole:
  its diagnostics (a renderer that refused) ride it to the reply."
  {:malli/schema [:=> [:cat :seon.sci.eval/shown] :seon.batch/printed]}
  [{shown :seon.eval/shown :as whole}]
  {:seon.batch/shown shown :seon.batch/shown-map whole})

(defn show
  "One entry's shown text through the one printer, under the arm, with the
  printer's own diagnostics (a renderer that refused, a profile it could not
  prepare): the fold records those as faults."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/entry] :seon.batch/printed]}
  [{{ctx :seon.sci.eval/ctx limit :seon.sci.eval/time-limit-ms profile :seon.render/profile
     cluster :seon.boot/cluster-name} :seon.batch/world db :seon.batch/working defining :seon.batch/defining}
   {:seon.batch/keys [value error source] :as entry}]
  (let [req (cond-> {:seon.sci.eval/ctx ctx :seon.cluster.eval/source (or source "")
                     ;; the profile derives from the form's working value and cluster
                     :seon.db/db db
                     :seon.sci.eval/time-limit-ms (or limit 30000)
                     :seon.sci.admit/caps (:seon.sci.admit/caps (#'eval/instrumentation-config db))}
              profile (assoc :seon.render/profile profile)
              ;; the form's own definitions: the database holds them and their tests' results only after the form is folded
              (seq defining) (assoc :seon.render.var/defining defining)
              cluster (assoc :seon.boot/cluster-name cluster))]
    (kernel/with-arm
     ctx (or limit 30000)
     (fn [armed]
       (printed
        (if (contains? entry :seon.batch/error)
          (eval/shown-failure (or (:seon.batch/error-value (ex-data error))
                                  (kernel/failure-value {::kernel/time-limit-kind ::eval/time-limit
                                                         ::kernel/failure-kind ::eval/evaluation-failed}
                                                        error ((::kernel/record armed) :error)))
                              req nil)
          (eval/shown-result value req ((::kernel/record armed) :ok))))))))

(defn read-only?
  "Whether an evaluation only read: no write was observed for it and it
  changed no schema. The refresh classifier replays only these."
  {:malli/schema [:=> [:cat :seon.batch/batch :seon.batch/entry] :boolean]}
  [batch {n :seon.batch/ordinal :as entry}]
  (and (not-any? #(and (= n (:seon.batch/ordinal %)) (not= :expansion (:seon.batch/op %))) (:seon.batch/writes batch))
       (empty? (get-in entry [:seon.batch/change :seon.batch/schema-keys]))
       (not (contains? entry :seon.batch/facts-error))))

(defn reply
  "The one reply, as data: what a Seon agent's next context reads and what the
  MCP tool encodes as JSON, identical but for transport. Each evaluation's
  shown text (the one print), its outcome, its facts error and notes; the
  settlement and the faults."
  {:malli/schema [:=> [:cat :seon.batch/batch] :seon.batch/reply]}
  [batch]
  (merge
   {:seon.batch/evaluations
    (mapv (fn [{:seon.batch/keys [ordinal shown facts-error notes private duration-ms] :as e}]
            (cond-> {:seon.batch/ordinal ordinal :seon.batch/shown (or shown "")
                     :seon.batch/duration-ms duration-ms}
              (contains? e :seon.batch/error) (assoc :seon.batch/error? true)
              facts-error (assoc :seon.batch/facts-error (:seon.error/message facts-error))
              (seq notes) (assoc :seon.batch/notes notes)
              (seq private) (assoc :seon.batch/private (mapv #(select-keys % [:seon.batch/sym :seon.batch/join :seon.batch/reason]) private))))
          (:seon.batch/entries batch))
    :seon.batch/diagnostics (mapv :seon.error/message (:seon.batch/diagnostics batch))}
   (select-keys batch [:seon.batch/unread :seon.batch/faults])
   (when-let [c (:seon.batch/commit batch)]
     {:seon.batch/commit (cond-> (select-keys c [:seon.batch/applied :seon.batch/tx-count :seon.batch/storage-error])
                           (:seon.batch/writer-refusal c)
                           (assoc :seon.batch/writer-refusal (select-keys (:seon.batch/writer-refusal c) [:seon.error/message :seon.error/operation]))
                           (:seon.batch/first-refusal c)
                           (assoc :seon.batch/first-refusal (:seon.error/message (:seon.batch/first-refusal c))))})))

;;; ---------------------------------------------------------------------------
;;; Entrants
;;; ---------------------------------------------------------------------------

(defn- sci-stages
  {:malli/schema [:=> [:cat :map] :map]}
  [{world :seon.batch/world :as request}]
  (let [db (or (:seon.batch/basis request) (world/basis world))]
  (merge {:seon.batch/basis db
          :seon.batch/read-state forgive/read-state
          :seon.batch/read-next sci.batch/read-next
          :seon.batch/ns-name sci.batch/current-ns
          :seon.batch/around sci.batch/around
          :seon.batch/show show
          :seon.batch/fault world/record-fault
          :seon.batch/within world/with-world
          :seon.batch/start-ns 'user
          :seon.batch/mode :code
          :seon.sci.batch/delta (schema/begin-registration-delta (db/carried-projection db))}
         request)))

(defn program-request
  "An evaluation that records program facts: derive, facts, apply, fold,
  and settle over the SCI stages, all reading the request's world
  (`:seon.batch/world`); `observe` is the write observer stage."
  {:malli/schema [:=> [:cat :map] :seon.batch/request]}
  [request]
  (sci-stages (merge {:seon.batch/derive derive-form
                      :seon.batch/facts form-facts
                      :seon.batch/apply apply-facts
                      :seon.batch/fold fold-change
                      :seon.batch/settle settle}
                     request)))

(defn turn-request
  "A product turn: the agent's reply read by the reply line rules."
  {:malli/schema [:=> [:cat :map] :seon.batch/request]}
  [request]
  (program-request (merge {:seon.batch/mode :reply} request)))

(defn code-request
  "An MCP SCI run (root or a branch's hosted agent): the text read as code."
  {:malli/schema [:=> [:cat :map] :seon.batch/request]}
  [request]
  (program-request (merge {:seon.batch/mode :code} request)))

(defn- jvm-read-next
  "One host `read+string` at the live `*ns*` after `seon.sci.forgive/skip-to`; a failed
  read is that construct's read error (`seon.sci.forgive/failure-step`), and reading
  continues after it."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/read-state] :seon.batch/read-step]}
  [_ {v :seon.batch/version}]
  (let [text (:seon.batch/text v)
        pos (forgive/skip-to text (:seon.batch/pos v))
        rdr (clojure.lang.LineNumberingPushbackReader. (doto (java.io.StringReader. text) (.skip (long pos))))
        eof (Object.)
        [form s] (try (read+string {:eof eof :read-cond :allow} rdr)
                      (catch Throwable t [::error t]))
        v' (fn [p] (assoc v :seon.batch/pos p))]
    (cond
      (identical? eof form) {:seon.batch/eof true :seon.batch/version (v' pos)}
      (= ::error form) (forgive/failure-step text pos s)
      :else (let [end (+ pos (count s))]
              {:seon.batch/form form :seon.batch/start pos :seon.batch/end end
               :seon.batch/version (v' end)}))))

(defn- jvm-show
  "A host evaluation's shown text through the same floor as every entrant
  (`seon.sci.eval/shown-result`, `shown-failure`), with the world's render
  profile and the host observation the fold measured. With the agent's SCI
  context in the world, the value is bound there under its result handle
  (`result/e<evaluation id>`), so the agent digs it as it digs an SCI value."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/entry] :seon.batch/printed]}
  [{{profile :seon.render/profile caps :seon.sci.admit/caps
     cluster :seon.boot/cluster-name ctx :seon.sci.eval/ctx
     turn-id :seon.turn/id limit :seon.sci.eval/time-limit-ms} :seon.batch/world db :seon.batch/working}
   {:seon.batch/keys [value error source duration-ms allocated-bytes ordinal] :as entry}]
  (let [req (cond-> {:seon.cluster.eval/source (or source "")}
              cluster (assoc :seon.boot/cluster-name cluster)
              profile (assoc :seon.render/profile profile)
              db (assoc :seon.db/db db)
              ctx (assoc :seon.sci.eval/ctx ctx :seon.sci.eval/time-limit-ms (or limit 30000)
                         :seon.sci.admit/caps caps)
              (and ctx turn-id) (assoc :seon.repl/handle (admit/result-handle (id/evaluation turn-id ordinal)))
              ;; The host form's own `set!` of *print-length* / *print-level*:
              ;; the show stage runs inside its evaluation's bindings.
              :always (assoc :seon.print/options {:seon.print/length *print-length* :seon.print/level *print-level*}))
        record {:seon.eval/fn-entries 0 :seon.eval/host-interop-count 0
                :seon.eval/duration-ms (Math/round (double duration-ms)) :seon.eval/allocated-bytes (or allocated-bytes -1)
                :seon.eval/outcome (if (contains? entry :seon.batch/error) :error :ok)}]
    (printed
     (if (contains? entry :seon.batch/error)
       (eval/shown-failure (kernel/failure-value {::kernel/time-limit-kind ::eval/time-limit
                                                   ::kernel/failure-kind ::eval/evaluation-failed}
                                                  error record)
                          req nil)
       (eval/shown-result value req nil)))))

(def ^:private ^java.lang.management.ThreadMXBean thread-mx
  (java.lang.management.ManagementFactory/getThreadMXBean))

(defn- jvm-evaluate
  "The host evaluation of `form`: its value or error, with the evaluating
  thread's allocation and CPU as the evaluation `arm-measures` sums; -1 on a
  thread HotSpot does not count (a virtual thread). Only root evaluates on the
  host: a host evaluation can load, reload or intern in the one JVM every
  cluster shares, so any other agent's form is refused by name, unevaluated,
  and its evaluation carries no measures: a refusal measured nothing."
  {:malli/schema [:=> [:cat :map :any :any] :map]}
  [{{agent-id :seon.agent/id} :seon.batch/world} form _]
  (if (not= "root" agent-id)
    {:seon.batch/error
     (ex-info "JVM mode is root's: run read-only host queries as agent \"root\"."
              (seon.error.refusal/diagnostic
               (java.util.Date.) :seon.turn.batch/host-evaluation `jvm-evaluate
               {:seon.error/message (str "Agent " agent-id " cannot evaluate on the host JVM, which every cluster"
                                         " shares; evaluate in SCI mode, or run read-only host queries as agent \"root\".")
                :seon.error/offending agent-id
                :seon.error/member :seon.agent/id
                :seon.error/expected "root"}))
     :seon.batch/evaluation {}}
  (let [bytes0 (.getCurrentThreadAllocatedBytes ^com.sun.management.ThreadMXBean thread-mx)
        cpu0 (.getCurrentThreadCpuTime thread-mx)
        outcome (try {:seon.batch/value (eval form)}
                     (catch Throwable t {:seon.batch/error t}))
        bytes1 (.getCurrentThreadAllocatedBytes ^com.sun.management.ThreadMXBean thread-mx)
        cpu1 (.getCurrentThreadCpuTime thread-mx)]
    (assoc outcome :seon.batch/evaluation
           {:seon.eval/allocated-bytes (if (and (<= 0 bytes0) (<= bytes0 bytes1)) (- bytes1 bytes0) -1)
            :seon.cost/cpu-ns (if (and (<= 0 cpu0) (<= cpu0 cpu1)) (- cpu1 cpu0) -1)}))))

(defn jvm-request
  "Return a host-read/eval pass with render settings acquired from its starting basis."
  {:malli/schema [:=> [:cat :map] :seon.batch/request]}
  [{world :seon.batch/world :as request}]
  (let [database (world/basis world)
        settings (delay
                   (let [effective (config/effective database (:seon.boot/cluster-name world))]
                     {:seon.sci.admit/caps (config/result-caps effective)
                      :seon.render/profile (render/agent-render-profile effective)}))]
    ;; the host evaluates: no SCI write observer, no program facts
    (dissoc
     (merge {:seon.batch/basis database
             :seon.batch/read-state (fn [text] {:seon.batch/version {:seon.batch/text text :seon.batch/pos 0}})
            :seon.batch/read-next jvm-read-next
            :seon.batch/evaluate jvm-evaluate
            :seon.batch/ns-name (fn [_] (ns-name *ns*))
            :seon.batch/around (fn [{n :seon.batch/start-ns} body]
                                 (binding [*ns* (create-ns n)] (refer-clojure) (body)))
            :seon.batch/show (fn [request entry]
                               (jvm-show (update request :seon.batch/world #(merge @settings %)) entry))
            :seon.batch/fault world/record-fault
            :seon.batch/within world/with-world
            :seon.batch/settle settle
            :seon.batch/start-ns 'user}
           request)
     :seon.batch/observe :seon.batch/derive)))

(defn test-request
  "A test agent's resolved body: one entry whose evaluation is the body, its
  recording the same settle stage, on its world's settlement connection."
  {:malli/schema [:=> [:cat :map] :seon.batch/request]}
  [{body :seon.batch/body world :seon.batch/world :as request}]
  (merge {:seon.batch/text "(body)"
          :seon.batch/basis (world/basis world)
          :seon.batch/read-state (fn [text] {:seon.batch/version {:seon.batch/text text :seon.batch/pos 0}})
          :seon.batch/read-next (fn [_ {v :seon.batch/version}]
                                  (if (zero? (:seon.batch/pos v))
                                    {:seon.batch/form ::body :seon.batch/start 0 :seon.batch/end 6
                                     :seon.batch/version (assoc v :seon.batch/pos 6)}
                                    {:seon.batch/eof true :seon.batch/version v}))
          :seon.batch/evaluate (fn [_ _ _] {:seon.batch/value (body)})
          :seon.batch/ns-name (fn [_] 'user)
          :seon.batch/settle settle
          :seon.batch/fault world/record-fault
          :seon.batch/within world/with-world}
         request))
