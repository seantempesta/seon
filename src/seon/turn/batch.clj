(ns seon.turn.batch
  "The program stages of the one REPL path, and every entrant's request.

  derive   a form's ordered writes -> its program change: clj-kondo's
           analysis of the form, each definition's write joined to exactly
           one analyzed definition by its source occurrence, entities for the
           joined, namespaces the form changed, schema keys whose registered
           form the working value does not hold yet, removals in write order
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
            [clojure.string :as str]
            [sci.impl.vars]
            [datahike.db.utils :as dbu]
            [my.program :as my.program]
            [sci.core :as sci]
            [seon.db :as db]
            [seon.config :as config]
            [seon.fn :as sfn]
            [seon.batch]
            [seon.blob :as blob]
            [seon.fn.analyzer :as analyzer]
            [seon.profile.measurement :as measurement]
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

(defn- line-starts
  {:malli/schema [:=> [:cat :string] [:vector nat-int?]]}
  [^String text]
  (into [0] (keep-indexed (fn [i c] (when (= \newline c) (inc i)))) text))

(defn namespace-entity
  "The namespace fact a form's analysis resolves through: SCI's live resolver
  bindings (aliases, refers, requires, imports) of `namespace-name`."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :symbol] :map]}
  [ctx namespace-name]
  (merge {:seon.ns/name namespace-name}
         (#'eval/binding-rows (#'eval/reader-context ctx namespace-name))))

(defn analyzed-entities
  "The program entities one analysis derives, keyed by [request-index utf8-offset name]."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :map] :map] :map]}
  [db requests analysis]
  (let [{a :seon.fn/analysis text :seon.fn/source spans :seon.fn/source-spans
         function-entities :seon.fn/function-rows} analysis
        context (#'sfn/text-context text)
        byte-starts (:seon.fn/byte-line-starts context)
        functions (into (#'sfn/first-party-function-symbols a) (map :seon.fn/sym) function-entities)
        submitted (#'sfn/source-analysis a (ffirst spans) (second (peek spans)))
        projection (db/carried-projection db)
        entities (#'sfn/analysis-entities-by-file
                  submitted functions {"<stdin>" context}
                  (#'sfn/program-targets db (#'sfn/population-targets projection (#'sfn/invoked-attributes submitted)) functions)
                  projection)
        form-starts (mapv (fn [[first-row _]] (nth byte-starts (dec first-row))) spans)
        form-of (fn [offset] (last (keep-indexed (fn [i s] (when (<= s offset) i)) form-starts)))
        shapes (program/shapes-in projection)]
    (into {}
          (keep (fn [entity]
                  (when (and (or (:seon.fn/sym entity) (:seon.test/sym entity)) (:seon.fn/form-span entity))
                    (let [[s _] (:seon.fn/form-span entity)
                          i (form-of s)
                          ns-row (:seon.fn/namespace-row (nth requests i))
                          row (program/declaration-row
                               projection
                               (program/canonical-row shapes (dissoc entity :seon.fn/file :seon.fn/form-span))
                               :all :agent)]
                      [[i (- s (form-starts i)) (name (or (:seon.fn/sym entity) (:seon.test/sym entity)))]
                       (assoc row :seon.program/definition-digest
                              (db/definition-digest-of projection row ns-row))]))))
          (get entities "<stdin>"))))

(defn- synthetic
  "An entry position [line column] in the analysis' synthetic coordinates."
  {:malli/schema [:=> [:cat :map [:tuple pos-int? pos-int?]] [:tuple pos-int? pos-int?]]}
  [{:keys [first-row line column]} [l c]]
  [(+ first-row (- l line)) (if (= l line) (+ 1 (- c column)) c)])

(defn- span-of
  "An analyzer entry's [row col end-row end-col]. A usage clj-kondo records
  inside a macro's expansion (the `->` of a `cond->`, the `if` of a `case`)
  has no position: its span is all nil and equals no definition's."
  {:malli/schema [:=> [:cat :map] [:tuple [:maybe int?] [:maybe int?] [:maybe int?] [:maybe int?]]]}
  [{r ::analyzer/row c ::analyzer/col er ::analyzer/end-row ec ::analyzer/end-col}]
  [r c er ec])

(defn- contains-pos?
  {:malli/schema [:=> [:cat :map [:tuple int? int?]] :boolean]}
  [d [r c]]
  (let [[row col end-row end-col] (span-of d)]
    (boolean (and row end-row
                  (or (< row r) (and (= row r) (<= col c)))
                  (or (< r end-row) (and (= r end-row) (< c end-col)))))))

(defn join-write
  "The one analyzed definition a definition's final root write is.

  Its origin is the source occurrence SCI evaluated it as (the SCI fork's
  producer origin): the def form's own span, or the innermost macro call
  whose expansion made it. A write with no origin rebinds a Var outside any
  definition (intern, alter-var-root) and keeps its value unpersisted. A
  write whose producer names its member (a record's constructor helper)
  belongs to that declaration. An origin without its complete span joins
  nothing."
  {:malli/schema [:=> [:cat :map :map :seon.batch/write] :seon.batch/join]}
  [analysis coords {sym :seon.batch/sym origin :seon.batch/origin member :seon.batch/member-of}]
  (let [a (:seon.fn/analysis analysis)
        same (filter #(= (name sym) (str (::analyzer/name %))) (::analyzer/var-definitions a))]
    (cond
      member {:seon.batch/join :member :seon.batch/member-of member}
      (nil? origin) {:seon.batch/join :rebound}
      (not (and (:end-line origin) (:end-column origin)))
      {:seon.batch/join :unanalyzed :seon.batch/reason :no-complete-span}
      :else
      (let [span (into (synthetic coords [(:line origin) (:column origin)])
                       (synthetic coords [(:end-line origin) (:end-column origin)]))
            ;; a generated definition's owning macro call, by its complete span
            call (when (:expanded-by origin)
                   (first (filter #(= span (span-of %)) (::analyzer/var-usages a))))
            op (when call (symbol (str (::analyzer/to call)) (str (::analyzer/name call))))
            cs (distinct
                (if (:expanded-by origin)
                  (when call (filter #(and (contains-pos? call [(::analyzer/row %) (::analyzer/col %)])
                                           (= op (::analyzer/defined-by %))) same))
                  ;; an authored definition: its complete span exactly
                  (filter #(= span (span-of %)) same)))]
        (case (count cs)
          0 {:seon.batch/join :unanalyzed :seon.batch/reason :no-definition-at-origin}
          1 (cond-> {:seon.batch/join :matched :seon.batch/definition (first cs)}
              (not= (namespace sym) (str (::analyzer/ns (first cs))))
              (assoc :seon.batch/other-namespace true))
          {:seon.batch/join :unanalyzed :seon.batch/reason :ambiguous})))))

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

;;; ---------------------------------------------------------------------------
;;; derive: one form's writes -> its program change
;;; ---------------------------------------------------------------------------

(defn- entry-coords
  {:malli/schema [:=> [:cat :map :map] :map]}
  [analysis entry]
  {:first-row (ffirst (:seon.fn/source-spans analysis))
   :line (:seon.batch/line entry) :column (:seon.batch/column entry)})

(defn- local-offset
  "UTF-8 offset, within `source`, of synthetic position [r c] of an analysis
  that placed `source` at `first-row`."
  {:malli/schema [:=> [:cat :string int? [:tuple int? int?]] nat-int?]}
  [^String source first-row [r c]]
  (let [ls (line-starts source)]
    (blob/utf8-size (subs source 0 (+ (get ls (- r first-row)) (dec c))))))

(defn- definition-source
  {:malli/schema [:=> [:cat :map :map] :string]}
  [analysis d]
  (let [t (:seon.fn/source analysis) ls (line-starts t)
        [r c er ec] (span-of d)
        off (fn [l col] (+ (get ls (dec l)) (dec col)))]
    (subs t (off r c) (off er ec))))

(defn- form-relative-expansions
  "The observed expansions the analyzer needs: a macro call that is the owning
  call of a generated definition (a bind's origin, `:expanded-by`) and that
  clj-kondo cannot analyze itself (a Clojure core macro such as `defn` or
  `defmacro` it knows; substituting SCI's expansion would hide the authored
  form). Their call spans move from the batch's text coordinates to the form's
  own (its first line 1, its first column 1): the analyzer places them at the
  form's first row."
  {:malli/schema [:=> [:cat :seon.batch/entry [:vector :seon.batch/write]] [:vector :seon.batch/write]]}
  [{line :seon.batch/line column :seon.batch/column} writes]
  (let [relative (fn [[l c]] [(inc (- l line)) (if (= l line) (inc (- c column)) c)])
        owning (into #{} (keep (fn [{op :seon.batch/op {:keys [line column end-line end-column expanded-by]} :seon.batch/origin}]
                                 (when (and (= :bind op) expanded-by end-line)
                                   [line column end-line end-column])))
                     writes)]
    (into [] (comp (filter #(and (= :expansion (:seon.batch/op %))
                                 (owning (:seon.batch/call-span %))
                                 (not (str/starts-with? (str (namespace (:seon.batch/macro %))) "clojure."))))
                   (map (fn [{[l c el ec] :seon.batch/call-span :as w}]
                          (assoc w :seon.batch/call-span (into (relative [l c]) (relative [el ec]))))))
          writes)))

(defn- stored-namespace-row
  "The namespace fact a form's transaction leaves stored for `namespace-name`:
  SCI's live bindings when the form changed its resolver state (`changed`) or
  `db` holds no row for it, else the row `db` holds (`namespace-facts` writes a
  stored namespace only when its resolver state changed). The writer digests a
  definition under this row, so its producer must too."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value [:set :symbol] :symbol] :map]}
  [ctx db changed namespace-name]
  (or (when-not (contains? changed namespace-name)
        (when (dbu/entid db [:seon.ns/name namespace-name])
          (db/pull db [:seon.ns/name :seon.ns/requires
                       {:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]}
                       {:seon.ns/refers [:seon.ns.refer/local :seon.ns.refer/target-ns :seon.ns.refer/target-name]}
                       {:seon.ns/imports [:seon.ns.import/local :seon.ns.import/target-class]}]
                   [:seon.ns/name namespace-name])))
      (namespace-entity ctx namespace-name)))

(defn- analyze-form
  "One clj-kondo analysis of `source` in `namespace-name`, every Var the batch has
  bound declared, with its entities; `namespace-row` names the namespace fact
  each namespace resolves through (`stored-namespace-row`)."
  {:malli/schema [:=> [:cat [:=> [:cat :symbol] :map] :seon.db/database-value :symbol :string [:vector :qualified-symbol]
                       [:vector :seon.batch/write]] :map]}
  [namespace-row db namespace-name source declared expansions]
  (let [req (cond-> {:namespace-name namespace-name :form-source source
                     :seon.fn/namespace-row (namespace-row namespace-name)
                     :seon.fn/declared declared}
              ;; the evaluation's observed expansions: the analyzer's hooks
              ;; (analyzer-expansion-hooks patch) analyze a generated definition at its call
              (seq expansions) (assoc :seon.batch/expansions expansions))
        analysis (#'sfn/runtime-analysis-batch db [req])]
    {:analysis analysis :entities (analyzed-entities db [req] analysis)}))

(defn- write-entity
  "The program row of one joined definition write, or the reason it has none.
  Its contract facts read `projection`, the one the write was armed under."
  {:malli/schema [:=> [:cat [:=> [:cat :symbol] :map] :seon.db/database-value :seon.schema/projection :map :map :seon.batch/write [:vector :qualified-symbol]] :map]}
  [namespace-row db projection entry {:keys [analysis entities]} {sym :seon.batch/sym v :seon.batch/var :as write} declared]
  (let [coords (entry-coords analysis entry)
        first-row (:first-row coords)
        source (:seon.batch/source entry)
        j (join-write analysis coords write)
        d (:seon.batch/definition j)
        [r c er ec] (when d (span-of d))
        nested? (and d (not= [first-row 1] [r c]))
        captured (when nested?
                   (captured-locals source [(inc (- r first-row)) c (inc (- er first-row)) ec]))
        row (when (and d (empty? captured))
              (if (:seon.batch/other-namespace j)
                (first (vals (:entities (analyze-form namespace-row db (symbol (namespace sym)) (definition-source analysis d) declared []))))
                (get entities [0 (local-offset source first-row [r c]) (name sym)])))
        contract (:malli/schema (meta v))]
    (cond
      ;; A program declaration is a function or a test; a def of any other
      ;; value (an atom, a map, a result) is the agent's private state, kept
      ;; in its context and never shared as program.
      (not (or (fn? @v) (:test (meta v))))
      {:seon.batch/private {:seon.batch/sym sym :seon.batch/join :value :seon.batch/reason :not-a-function}}
      (seq captured) {:seon.batch/private {:seon.batch/sym sym :seon.batch/join :unanalyzed
                                           :seon.batch/reason :captures-locals
                                           :seon.batch/captured captured}}
      ;; a contracted function's row carries its parsed contract facts
      ;; (:seon.fn/arities, their input/output refs), as the index's rows do:
      ;; the schema usage guard and arity checks read them
      row {:seon.batch/row (if (and contract (fn? @v))
                             (sfn/contract-facts-row projection
                                                     (namespace-row (symbol (namespace sym)))
                                                     (assoc row :seon.fn/spec (pr-str contract)))
                             row)}
      :else {:seon.batch/private (-> j (dissoc :seon.batch/definition) (assoc :seon.batch/sym sym))})))

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
                     ;; a `declare` binds no root, so no write names it: its namespace's
                     ;; unbound Vars join the prelude so a call to one is an edge
                     (into (for [w writes :when (= :namespace (:seon.batch/op w))
                                 :let [n (:seon.batch/ns w)]
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
        namespaces (into #{} (comp (filter #(= :namespace (:seon.batch/op %))) (map :seon.batch/ns)) writes)
        namespace-row (partial stored-namespace-row ctx working namespaces)
        analyzed (when (and (seq finals) (:seon.batch/source entry))
                   (analyze-form namespace-row working (:seon.batch/ns-before entry) (:seon.batch/source entry) declared
                                 (form-relative-expansions entry writes)))
        ;; the projection each write was armed under (`seon.sci.arm/arm!`):
        ;; it holds a schema this form registered before the batch applies it
        projection (when analyzed (arm/arming-projection request working))
        by-var (into {} (map (fn [w] [(:seon.batch/var w)
                                      (if analyzed
                                        (write-entity namespace-row working projection entry analyzed w declared)
                                        {:seon.batch/private {:seon.batch/sym (:seon.batch/sym w)
                                                              :seon.batch/join :unanalyzed
                                                              :seon.batch/reason :no-source}})]))
                     finals)
        ;; execution order: a later write supersedes an earlier one by identity
        program (reduce (fn [m w]
                          (case (:seon.batch/op w)
                            :bind (if (identical? w (some #(when (identical? (:seon.batch/var w) (:seon.batch/var %)) %) finals))
                                    (let [{:seon.batch/keys [row private]} (get by-var (:seon.batch/var w))
                                          sym (:seon.batch/sym w)]
                                      (cond
                                        row (assoc m sym [:entity row])
                                        ;; a rebinding withdraws a row this batch proposed for
                                        ;; the name: its source no longer describes the root
                                        (and (= :rebound (:seon.batch/join private))
                                             (dbu/entid working [:seon.fn/sym sym])
                                             (not (dbu/entid (:seon.batch/basis request) [:seon.fn/sym sym])))
                                        (assoc m sym [:withdrawn private])
                                        :else (assoc m sym [:private private])))
                                    m)
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
                :seon.batch/removals (vec (keep (fn [[k [tag]]] (when (#{:removed :removed-ns :withdrawn} tag) k)) program))
                :seon.batch/schema-keys schema-keys
                :seon.batch/namespaces (into (apply disj namespaces removed-ns)
                                             ;; a definition names its namespace only to create it: a stored
                                             ;; namespace row changes when its resolver state does
                                             (comp (keep #(some-> % :seon.fn/ns second))
                                                   (remove #(dbu/entid working [:seon.ns/name %])))
                                             entities)
                :seon.batch/private (vec (keep (fn [[_ [tag x]]] (when (#{:private :withdrawn} tag) x)) program))
                :seon.batch/notes (into (schema-notes request working schema-keys)
                                        (keep (fn [w] (when-let [e (:seon.batch/arm-error w)]
                                                        {:seon.batch/sym (:seon.batch/sym w) :seon.batch/arm-error e})))
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
  "A row's facts on `db`: the exact replacement of its stored identity, else
  the new entity (and its namespace, when `db` holds none)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/row] :seon.store/transaction-data]}
  [db row]
  (let [[ident v] (program/row-identity row)
        existing (when-let [e (dbu/entid db [ident v])] (db/pull db '[*] e))
        ns-attr (if (:seon.fn/ns row) :seon.fn/ns :seon.test/ns)
        ns-ref (get row ns-attr)
        missing-ns (when (and (vector? ns-ref) (not (dbu/entid db ns-ref))) (second ns-ref))
        row (cond-> row missing-ns (assoc ns-attr (str "namespace:" missing-ns)))]
    (cond-> (if (:db/id existing)
              (program/exact-replacement-tx existing row)
              [(assoc row :db/id (str (name ident) ":" v))])
      missing-ns (conj {:db/id (str "namespace:" missing-ns) :seon.ns/name missing-ns}))))

(defn- namespace-facts
  "The namespace fact of `n` as SCI holds it now, on `db`."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value :symbol] :seon.store/transaction-data]}
  [{{ctx :seon.sci.eval/ctx} :seon.batch/world} db n]
  (or (when (sci/find-ns ctx n)
        (when-let [row (#'eval/namespace-context-row (db/carried-projection db) db n nil {}
                                                     (#'eval/reader-context ctx n) true)]
          (if-let [stored (dbu/entid db [:seon.ns/name n])]
            ;; a stored namespace changes only the attributes the context row
            ;; carries: its doc, file, tasks and admission source stay
            (let [row (dissoc row :seon.schema.admission/source)]
              (program/exact-replacement-tx (select-keys (db/pull db '[*] stored) (cons :db/id (keys row))) row))
            (entity-facts db row))))
      []))

(defn- removal-facts
  "Retraction of a removed Var's or namespace's stored declarations on `db`,
  through the program owner's deletion report; surviving referrers answer
  the owner's refusal, naming them. A subject with no stored declaration
  (an empty namespace SCI held but never recorded) retracts nothing."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol]
                  [:or :seon.store/transaction-data :seon.program/mutation-refused-error]]}
  [db subject]
  (let [stored? (if (qualified-symbol? subject)
                  (or (dbu/entid db [:seon.fn/sym subject]) (dbu/entid db [:seon.test/sym subject]))
                  (dbu/entid db [:seon.ns/name subject]))]
    (if-not stored?
      []
      (let [[report subjects] (#'my.program/deletion-report db subject)]
        (if (or (#'my.program/blocking? report) (some keyword? subjects))
          (#'my.program/refusal 'seon.turn.batch/removal-facts report subjects)
          (mapv (fn [target] [:db/retractEntity (:seon.program/identity (#'my.program/locate db target))])
                (sort-by str subjects)))))))

(defn form-facts
  "A program change as transaction data on `db`, or the refusal of a removal
  whose referrers survive."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value :seon.batch/change]
                  [:or :seon.store/transaction-data :seon.program/mutation-refused-error]]}
  [request db {:seon.batch/keys [entities removals schema-keys namespaces]}]
  (let [stx (schema-tx request db schema-keys)
        ;; entities read the schema this change registers
        db' (if (and (seq stx) (seq entities)) (let [w (db/with db stx)] (if (:seon.error/message w) db w)) db)
        removed (mapv #(removal-facts db' %) removals)]
    (or (first (filter :seon.error/at removed))
        (-> stx
            (into (mapcat #(namespace-facts request db' %)) (sort namespaces))
            (into (mapcat #(entity-facts db' %)) entities)
            (into cat removed)))))

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

(defn program-facts
  "The folded program as transaction data on `head`, or the refusal of a
  definition changed since the batch's basis, or a removal's refusal."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value :map]
                  [:or :seon.store/transaction-data :seon.program/mutation-refused-error :seon.error/value]]}
  [request head {:keys [entities schema-keys namespaces removed-namespaces]}]
  (let [rows (vec (keep (fn [[_ r]] (when (map? r) r)) entities))]
    ;; decided at the writer: a definition changed underneath the run refuses
    (or (changed-since-basis request head rows (or schema-keys #{}))
        (form-facts request head
                    {:seon.batch/entities rows
                     :seon.batch/removals (into (vec (keep (fn [[s r]] (when (= :removed r) s)) entities))
                                                removed-namespaces)
                     :seon.batch/schema-keys (or schema-keys #{})
                     :seon.batch/namespaces (or namespaces #{})}))))

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

(defn- entry-evaluation
  "One settled entry's terminal facts, as its run records them and its reply
  prints them."
  {:malli/schema [:=> [:cat :seon.batch/entry] :seon.turn.loop/evaluation]}
  [{:seon.batch/keys [shown shown-map error facts-error duration-ms] :as entry}]
  (cond-> {}
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

(defn- admitted-form
  "The form one entry read, where it ran."
  {:malli/schema [:=> [:cat :seon.batch/entry] :seon.turn.loop/admitted-form]}
  [{:seon.batch/keys [source ns-before]}]
  {:seon.cluster.eval/source (or source "")
   :seon.cluster.eval/ns [:seon.ns/name (or ns-before 'user)]})

(defn- evaluation-record
  "One entry as the recording writer's terminal evaluation."
  {:malli/schema [:=> [:cat :string :seon.batch/entry]
                  [:and :seon.cluster.eval/settle-request
                   [:map
                    [:seon.cluster.eval/at :seon.cluster.eval/at]
                    [:seon.cluster.eval/source :seon.cluster.eval/source]
                    [:seon.cluster.eval/ns :seon.cluster.eval/ns]
                    [:seon.cluster.eval/origin [:= :outside]]]]]}
  [turn-id {ordinal :seon.batch/ordinal :as entry}]
  (merge {:seon.turn/id turn-id
          :seon.cluster.eval/ordinal ordinal
          :seon.cluster.eval/at (java.util.Date.)
          :seon.cluster.eval/origin :outside}
         (admitted-form entry)
         (entry-evaluation entry)))

(defn evaluated-sources
  "The batch's settled entries as held forms, each with the terminal facts its
  run recorded (`record-facts`): what the run's reply prints."
  {:malli/schema [:=> [:cat :seon.batch/batch] :seon.turn.loop/evaluated-sources]}
  [batch]
  (mapv (fn [{ordinal :seon.batch/ordinal :as entry}]
          {:seon.cluster.eval/ordinal ordinal
           :seon.turn.loop/admitted-form (admitted-form entry)
           :seon.sci.eval/evaluation (entry-evaluation entry)})
        (:seon.batch/entries batch)))

(defn record-facts
  "The run and its evaluations, through the recording writer."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.store/transaction-data]}
  [{{agent-id :seon.agent/id turn-id :seon.turn/id} :seon.batch/world basis :seon.batch/basis start-ns :seon.batch/start-ns
    record-call :seon.turn.batch/record-call} batch]
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
         :seon.turn/evaluations (mapv #(evaluation-record turn-id %) entries)}]])))

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
        ;; once for every attempt: measuring advances the cells' marks
        measuring (measurement/significant-tx
                   head (into #{} (mapcat (comp keys :seon.batch/executed)) (:seon.batch/entries batch)))
        ;; a measuring failure rides the answer as a diagnostic; the commit proceeds without it
        measured (if (:seon.error/at measuring) [] measuring)
        ;; under the settlement connection's own custody, never the calling
        ;; thread's: an entrant called from inside another evaluation records
        ;; on its own world's branch
        commit! (fn [tx] (let [r (db/call-with-custody {:seon.db/connection conn} #(writer conn tx))]
                           (if (and (map? r) (:seon.error/message r)) r (:db-after r))))
        attempt (fn [b]
                  (let [tx (try (let [tx (into (vec (record-facts request b)) (program-facts request head (:seon.batch/program b)))]
                                  ;; measurements ride a commit that happens anyway, never one of their own
                                  (cond-> tx (and (vector? tx) (seq tx)) (into measured)))
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
    (cond-> (if-not (:seon.batch/writer-refusal first-try)
      ;; every path answers the state it settled
      (assoc first-try :seon.batch/settled (settled batch))
      ;; the head moved: re-derive on it; the settled state is what commits
      (let [rebased (rebase request batch head)
            second-try (try (attempt rebased) (catch Throwable t {:seon.batch/storage-error (ex-message t)}))]
        (assoc second-try
               :seon.batch/settled (settled rebased)
               :seon.batch/first-refusal (:seon.batch/writer-refusal first-try))))
      (:seon.error/at measuring) (assoc :seon.batch/measurement-error measuring))))

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
     cluster :seon.boot/cluster-name} :seon.batch/world db :seon.batch/working}
   {:seon.batch/keys [value error source] :as entry}]
  (let [req (cond-> {:seon.sci.eval/ctx ctx :seon.cluster.eval/source (or source "")
                     ;; the profile derives from the form's working value and cluster
                     :seon.db/db db
                     :seon.sci.eval/time-limit-ms (or limit 30000)
                     :seon.sci.admit/caps (:seon.sci.admit/caps (#'eval/instrumentation-config db))}
              profile (assoc :seon.render/profile profile)
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
            :seon.batch/evaluate (fn [_ form _] {:seon.batch/value (eval form)})
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
