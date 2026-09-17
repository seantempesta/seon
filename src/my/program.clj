(ns ^{:seon.ns/context-relevant? true} my.program
  "Read the program graph before changing a declaration.

  Reads use one supplied database value. Writes check those facts before
  changing SCI; referrers, past reach and unknown coverage remain separate.

  Example:
  (my.program/breaks {:seon.program/subject 'seon.turn/open?})"
  (:require [seon.db :as db]
            [sci.core :as sci]
            [clojure.set :as set]
            [seon.error :as error]
            [seon.fn :as function]
            [seon.issue :as issue]))

(defn- checked [value]
  (if (:seon.error/kind value)
    (throw (ex-info (:seon.error/message value) value))
    value))

(defn- read-result [request operation read-value]
  (try (read-value)
       (catch Exception failure
         (if (:seon.error/kind (ex-data failure))
           (ex-data failure)
           (error/diagnostic
            {:seon.error/kind :seon.program/read-refused
             :seon.error/message (str "Cannot read program facts: " (ex-message failure))
             :seon.program/read-refused true
             :seon.error/diagnostic-layer :program-read
             :seon.error/diagnostic-operation operation
             :seon.error/diagnostic-member (:seon.program/subject request)
             :seon.error/diagnostic-expected :seon.program/breakage
             :seon.error/diagnostic-offending request
             :seon.error/diagnostic-cause (ex-message failure)
             :seon.error/diagnostic-evidence (ex-data failure)})))))

(defn- stored-name
  "Use the installed identity type until the symbols-everywhere reset."
  [database attribute subject]
  (if (= :db.type/string (get-in (db/schema-database database) [:schema attribute :db/valueType]))
    (str subject)
    subject))

(defn- subject-identities [subject]
  (cond
    (keyword? subject) [:seon.schema/key]
    (qualified-symbol? subject) [:seon.fn/sym :seon.test/sym]
    :else [:seon.ns/name]))

(defn- locate [database subject]
  (or (some (fn [attribute]
              (let [value (stored-name database attribute subject)
                    entities (checked
                              (db/q database
                                    '[:find [?entity ...] :in $ ?attribute ?value
                                      :where [?entity ?attribute ?value]]
                                    attribute value))]
                (when (seq entities)
                  {:seon.program/kind attribute
                   :seon.program/identity [attribute value]
                   :seon.program/entities entities})))
            (subject-identities subject))
      (checked
       (error/diagnostic
        {:seon.error/kind :seon.program/not-found
         :seon.error/message (str "No program declaration names " subject ".")
         :seon.program/not-found subject
         :seon.error/diagnostic-layer :program-read
         :seon.error/diagnostic-operation 'my.program/breaks
         :seon.error/diagnostic-member subject
         :seon.error/diagnostic-expected (subject-identities subject)
         :seon.error/diagnostic-offending subject
         :seon.error/diagnostic-cause :seon.program/not-found
         :seon.error/diagnostic-evidence {:seon.db/basis-t (db/basis-t database)}}))))

(defn- names-through [database attribute target identity-attribute]
  (checked
   (db/q database
         '[:find [?name ...] :in $ ?attribute ?target ?identity
           :where [?owner ?attribute ?target] [?owner ?identity ?name]]
         attribute target identity-attribute)))

(defn- symbols [values] (into #{} (map symbol) values))

(defn- present-groups [groups]
  (into {} (filter (comp seq val)) groups))

(defn- observation [database subject kind]
  {:seon.program/subject subject
   :seon.program/kind kind
   :seon.db/basis-t (db/basis-t database)
   :seon.program/unknown
   [{:seon.program/gap :outside-graph
     :seon.program/message
     "This supplied database value answers; callers outside its program graph are unknown."}]})

(defn- caller-data [database subject entity]
  (let [callers (symbols (names-through database :seon.fn/calls entity :seon.fn/sym))
        arities (checked
                 (db/q database
                       '[:find [?arity ...] :in $ ?entity
                         :where [?entity :seon.fn/arities ?arity]] entity))
        declared (mapv #(dissoc (checked (db/pull database
                                                 [:seon.fn.arity/min :seon.fn.arity/max] %))
                                :db/id) arities)
        sites
        (into []
              (mapcat
               (fn [caller]
                 (let [stored (stored-name database :seon.fn/sym caller)
                       row (checked (db/pull database [:db/id :seon.fn/form-span]
                                             [:seon.fn/sym stored]))
                       tuples (checked
                               (db/q database
                                     '[:find [?tuple ...] :in $ ?entity
                                       :where [?entity :seon.fn/call-arities ?tuple]]
                                     (:db/id row)))
                       counts (keep (fn [[callee arity]]
                                      (when (= subject (symbol callee)) arity)) tuples)
                       site (cond-> {:seon.fn/sym caller}
                              (:seon.fn/form-span row)
                              (assoc :seon.fn/form-span (:seon.fn/form-span row))
                              (seq declared) (assoc :seon.fn/declared-arities declared))]
                   (if (seq counts)
                     (map #(assoc site :seon.fn/call-arity %) (sort counts))
                     [site]))))
              (sort callers))]
    (present-groups {:seon.program/callers callers :seon.program/call-sites sites})))

(defn callers
  "Read direct callers and their declaration spans and recorded call arities.

  Empty groups are absent; unknown coverage remains explicit.
  Example:
  (my.program/callers {:seon.program/subject 'seon.turn/open?})"
  {:malli/schema [:=> [:cat :my.program/subject-request]
                  [:or :seon.program/breakage :seon.error/value]]}
  [{database :seon.db/db subject :seon.program/subject :as request}]
  (read-result request 'my.program/callers
               #(let [{kind :seon.program/kind entities :seon.program/entities}
                      (locate database subject)]
                  (merge (observation database subject kind)
                         (caller-data database subject (first entities))))))

(defn tests-reaching
  "Read the current gate set, derived by seon.fn/gate-set.

  This is current test selection, not recorded reach from a past test run.
  Example:
  (my.program/tests-reaching {:seon.program/subject 'seon.turn/open?})"
  {:malli/schema [:=> [:cat :my.program/subject-request]
                  [:or :seon.program/breakage :seon.error/value]]}
  [{database :seon.db/db subject :seon.program/subject :as request}]
  (read-result request 'my.program/tests-reaching
               #(let [{kind :seon.program/kind} (locate database subject)]
                  (merge (observation database subject kind)
                         (present-groups
                          {:seon.program/gating
                           (mapv symbol (checked (function/gate-set
                                                  database (stored-name database :seon.fn/sym subject))))})))))

(defn- key-data [database schema-key entity]
  (let [contracts
        (checked
         (db/q database
               '[:find [?name ...]
                 :in $ ?key [?attribute ...]
                 :where [?arity ?attribute ?key]
                 [?function :seon.fn/arities ?arity]
                 [?function :seon.fn/sym ?name]]
               entity [:seon.fn.arity/input-refs :seon.fn.arity/output-refs
                       :seon.fn.arity/guard-refs]))
        datoms (if (get-in (db/schema-database database) [:schema schema-key])
                 (checked (db/datoms database :aevt schema-key)) [])]
    (present-groups
     {:seon.program/contract-refs (symbols contracts)
      :seon.program/writes-of (symbols (names-through database :seon.fn/writes entity :seon.fn/sym))
      :seon.program/schema-references
      (set (names-through database :seon.schema/references entity :seon.schema/key))
      :seon.program/mentions (symbols (checked (function/functions-using database schema-key)))
      :seon.program/data-in-use (when (seq datoms)
                                 {:seon.schema/key schema-key :seon.schema/datoms (count datoms)})})))

(defn reads-key
  "Read contract references, declared writes and literal keyword mentions.

  Mentions are membership evidence only, never a read or write claim.
  Example:
  (my.program/reads-key {:seon.schema/key :seon.db/connection})"
  {:malli/schema [:=> [:cat :my.program/key-request]
                  [:or :seon.program/breakage :seon.error/value]]}
  [{database :seon.db/db schema-key :seon.schema/key :as request}]
  (read-result request 'my.program/reads-key
               #(let [{entities :seon.program/entities} (locate database schema-key)]
                  (-> (merge (observation database schema-key :seon.schema/key)
                             (key-data database schema-key (first entities)))
                      (update :seon.program/unknown conj
                              {:seon.program/gap :keyword-mentions
                               :seon.program/message "Constructed keywords and reads not declared in contracts are unknown; mentions never block retraction."})))))

(defn- render-referrers [database subject]
  (into #{}
        (mapcat
         (fn [property]
           (keep (fn [[schema-key renderer]]
                   (when (= subject renderer)
                     {:seon.schema/key schema-key :seon.schema/property property}))
                 (checked (db/q database
                                '[:find ?key ?renderer :in $ ?property :where
                                  [?schema :seon.schema/key ?key]
                                  [?schema ?property ?renderer]]
                                property)))))
        [:seon.render/ai :seon.render/html]))

(defn- proposed-plan [database subject caller-symbols sites]
  (let [names (sort caller-symbols)
        stored (mapv #(stored-name database :seon.fn/sym %) names)
        gates (checked (function/gate-sets database stored))
        detector 'seon.program/unresolved-callers]
    {:seon.program/issues
     (mapv (fn [caller value]
             (cond->
              {:seon.issue/id (issue/subject-id detector [:seon.fn/sym value])
               :seon.issue/title (str caller " must be repaired before retracting " subject)
               :seon.issue/problem
               (str "Repair the caller before retrying the retraction. Preserve its gate set. "
                    "Done condition: seon.program/unresolved-callers no longer names the caller. "
                    (pr-str (filterv #(= caller (:seon.fn/sym %)) sites)))
               :seon.issue/status :open :seon.issue/severity :friction
               :seon.issue/functions #{[:seon.fn/sym value]}
               :seon.issue/detector [:seon.fn/sym (stored-name database :seon.fn/sym detector)]}
               (seq (get gates value))
               (assoc :seon.issue/tests (into #{} (map #(vector :seon.test/sym %)) (get gates value)))))
           names stored)
     :seon.program/launch
     (list 'my.program/launch!
           {:seon.program/subject (list 'quote subject) :seon.issue/budget 8})}))

(defn breaks
  "Read the referrers a retraction would strand, with a prospective plan.

  Callers, references and test subjects block; stored test reach is advisory.
  Plans contain one detected issue per caller and its current gate set.
  Nothing is written or launched. Unknown dispatch, apply, macro and external
  callers are reported explicitly. A supplied contract is advisory: argument
  shapes require the existing candidate test gate.

  Example:
  (my.program/breaks {:seon.program/subject 'seon.turn/open?})"
  {:malli/schema [:=> [:cat :my.program/breaks-request]
                  [:or :seon.program/breakage :seon.error/value]]}
  [{database :seon.db/db subject :seon.program/subject :as request}]
  (read-result
   request 'my.program/breaks
   #(let [{kind :seon.program/kind entities :seon.program/entities} (locate database subject)
          entity (first entities)
          calls (caller-data database subject entity)
          owned-functions (when (= :seon.ns/name kind)
                            (concat (names-through database :seon.fn/ns entity :seon.fn/sym)
                                    (names-through database :seon.test/ns entity :seon.test/sym)))
          owned (when (= :seon.ns/name kind)
                  (into (symbols owned-functions)
                        (names-through database :seon.schema/ns entity :seon.schema/key)))
          gate-names (if (= :seon.ns/name kind) owned-functions
                         (when (qualified-symbol? subject)
                           [(stored-name database :seon.fn/sym subject)]))
          gates (checked (function/gate-sets database (vec gate-names)))
          report
          (merge (observation database subject kind) calls
                 (present-groups
                  {:seon.program/references (symbols (names-through database :seon.fn/references entity :seon.fn/sym))
                   :seon.program/subject-of (symbols (names-through database :seon.test/subject entity :seon.test/sym))
                   :seon.program/stale-reach (symbols (names-through database :seon.test/reach entity :seon.test/sym))
                   :seon.program/gating (vec (sort (symbols (mapcat val gates))))
                   :seon.program/capability-of (symbols (names-through database :seon.fn/capability-fn entity :seon.fn/sym))
                   :seon.program/schedule-tasks (set (names-through database :seon.schedule.task/function entity :seon.schedule.task/id))
                   :seon.program/render-declared-by (render-referrers database subject)
                   :seon.program/owned-declarations owned
                   :seon.program/requiring-namespaces
                   (when (= :seon.ns/name kind)
                     (set (names-through database :seon.ns/requires entity :seon.ns/name)))})
                 (when (= :seon.schema/key kind) (key-data database subject entity)))
          unknown-arities (count (remove :seon.fn/call-arity (:seon.program/call-sites report)))]
      (-> report
          (assoc :seon.program/plan
                 (proposed-plan database subject (:seon.program/callers report)
                                (:seon.program/call-sites report)))
          (update :seon.program/unknown into
                  (cond->
                   [{:seon.program/gap :dispatch-not-modelled
                     :seon.program/message "Protocol and multimethod implementations have no independent program row."}
                    {:seon.program/gap :arity-unknown-sites
                     :seon.program/count unknown-arities
                     :seon.program/message "Calls without recorded argument counts, including apply, cannot be checked for arity."}
                    {:seon.program/gap :macro-mediated
                     :seon.program/message "Macro expansions can introduce calls absent from the indexed caller relation."}
                    {:seon.program/gap :unresolvable-callers
                     :seon.program/message "Runtime-computed targets cannot be attributed to this subject by static analysis."}
                    {:seon.program/gap :launch-unavailable
                     :seon.program/message "This is a prospective plan. Ref-valued calls cannot report unresolved names after a sweep; generate reads actual detector subjects, not a refused deletion's proposed subjects. Nothing was launched."}]
                    (:seon.program/contract request)
                    (conj {:seon.program/gap :contract-shapes-unknown
                           :seon.program/message "The proposed contract has not run through the candidate test gate; these referrers are suspects, not proven contract violations."})))))))

(defn- entity-facts [database entity]
  (reduce (fn [row {:keys [a v]}]
            (if (= :db.cardinality/many (get-in (db/schema-database database) [:schema a :db/cardinality]))
              (update row a (fnil conj #{}) v)
              (assoc row a v)))
          {} (checked (db/datoms database :eavt entity))))

(defn history
  "Read source events and their definition facts and transaction provenance.

  Supply :seon.db/tx for the source visible as of that transaction; omit it
  for ordered assertion and retraction events. Refs remain entity ids.
  Example:
  (my.program/history {:seon.program/subject 'seon.turn/open?})"
  {:malli/schema [:=> [:cat :my.program/history-request]
                  [:or :seon.program/history-report :seon.error/value]]}
  [{database :seon.db/db subject :seon.program/subject at :seon.db/tx :as request}]
  (read-result
   request 'my.program/history
   #(let [view (if at (checked (db/as-of database at)) database)
          past (checked (db/history database))
          {kind :seon.program/kind entities :seon.program/entities} (locate past subject)
          identity-declaration (checked (db/pull database [:seon.program/source-attribute]
                                                [:seon.schema/key kind]))
          source-attribute (:seon.program/source-attribute identity-declaration)
          events (checked
                  (db/q (if at view past)
                        '[:find ?entity ?source ?tx ?added
                          :in $ [?entity ...] ?attribute
                          :where [?entity ?attribute ?source ?tx ?added]]
                        entities source-attribute))
          entries
          (mapv (fn [[entity source tx added]]
                     (let [snapshot (checked (db/as-of database (or at (if added tx (dec tx)))))
                           provenance (checked (db/pull database [:seon.db/user :seon.db/process] tx))]
                       (merge {:seon.db/tx tx :seon.db/added? added
                               :seon.program/source source
                               :seon.program/definition (entity-facts snapshot entity)}
                              (select-keys provenance [:seon.db/user :seon.db/process]))))
                   (sort-by (juxt (fn [event] (nth event 2))
                                  (fn [event] (nth event 3)) first) events))]
      (cond-> (assoc (observation database subject kind) :seon.program/history entries)
        at (assoc :seon.db/tx at)))))

(defn supplied-context
  "Supply the executing SCI context, its cluster base and writer connection.

  Call preparation carries the executing context explicitly; no process or
  thread lookup is used. A host call without that context refuses."
  {:malli/schema [:=> [:cat :seon.env/environment]
                  [:or :my.program/context :seon.error/value]]}
  [environment]
  (let [ctx (:my.program/executing-ctx environment)
        base (:my.program/base-ctx ctx)
        connection (:seon.db/connection environment)]
    (if (and ctx base connection)
      {:seon.sci.eval/ctx ctx :my.program/base-ctx base :seon.db/connection connection}
      (error/diagnostic
       {:seon.error/kind :seon.program/declaration-refused
        :seon.program/declaration-refused true
        :seon.error/message "Program mutation requires the executing SCI context, its cluster base and connection."
        :seon.error/diagnostic-layer :program-write
        :seon.error/diagnostic-operation 'my.program/supplied-context
        :seon.error/diagnostic-member :my.program/context
        :seon.error/diagnostic-expected :my.program/context
        :seon.error/diagnostic-offending (select-keys environment [:seon.agent/id])
        :seon.error/diagnostic-cause :context-unavailable
        :seon.error/diagnostic-evidence {:my.program/executing? (boolean ctx)
                                         :my.program/base? (boolean base)
                                         :my.program/connection? (boolean connection)}}))))

(defn overrides
  "Read current agent-admitted identities under an indexed src root.

  The declaration owner follows historical file provenance over this same
  database value. This does not assert JVM write-back or SCI loadability.
  Example: (my.program/overrides)"
  {:malli/schema [:=> [:cat :my.program/read-request]
                  [:or [:vector :seon.fn/sym] :seon.error/value]]}
  [{database :seon.db/db :as request}]
  (read-result request 'my.program/overrides
               #((requiring-resolve 'seon.program/overrides) database)))

(defn- refusal [operation report affected]
  (merge
   (error/diagnostic
    {:seon.error/kind :seon.program/declaration-refused
     :seon.program/declaration-refused true
     :seon.error/message (str "Cannot perform " operation " on " (:seon.program/subject report)
                              "; repair the named referrers first.")
     :seon.error/diagnostic-layer :program-write
     :seon.error/diagnostic-operation operation
     :seon.error/diagnostic-member (:seon.program/subject report)
     :seon.error/diagnostic-expected :seon.program/change
     :seon.error/diagnostic-offending report
     :seon.error/diagnostic-cause :live-referrers
     :seon.error/diagnostic-evidence report
     :seon.error/data report})
   {:seon.program/affected affected
    :seon.program/plan (:seon.program/plan report)
    :seon.program/unknown (:seon.program/unknown report)}))

(defn- blocking? [report]
  (boolean
   (some seq
         (vals (select-keys report
                            [:seon.program/callers :seon.program/references
                             :seon.program/subject-of :seon.program/render-declared-by
                             :seon.program/capability-of :seon.program/schedule-tasks
                             :seon.program/contract-refs :seon.program/schema-references
                             :seon.program/writes-of :seon.program/requiring-namespaces
                             :seon.program/data-in-use])))))

(defn- deletion-report [database subject]
  (let [root (checked (breaks {:seon.db/db database :seon.program/subject subject}))
        affected (conj (set (:seon.program/owned-declarations root)) subject)
        reports (into [root]
                      (map #(checked (breaks {:seon.db/db database :seon.program/subject %})))
                      (sort-by str (disj affected subject)))
        relations [:seon.program/callers :seon.program/references :seon.program/subject-of
                   :seon.program/capability-of :seon.program/schedule-tasks
                   :seon.program/contract-refs :seon.program/schema-references
                   :seon.program/writes-of :seon.program/requiring-namespaces]
        report (reduce (fn [result attribute]
                         (assoc result attribute (set/difference (into #{} (mapcat attribute) reports) affected)))
                       root relations)
        render-pairs (into #{} (comp (mapcat :seon.program/render-declared-by)
                                     (remove #(affected (:seon.schema/key %)))) reports)
        sites (into [] (comp (mapcat :seon.program/call-sites)
                             (remove #(affected (:seon.fn/sym %))) (distinct)) reports)
        report (assoc report :seon.program/render-declared-by render-pairs
                             :seon.program/call-sites sites
                             :seon.program/plan (proposed-plan database subject
                                                               (:seon.program/callers report) sites))]
    [report affected]))

(defn- native! [ctx form]
  ; Only this already-admitted native call skips preparation, never agent code.
  (sci/eval-form (assoc ctx :call-preparation-hook nil) form))

(defn- retract-operation! [context subject operation native-form]
  (read-result
   {:seon.program/subject subject} operation
   #(let [connection (:seon.db/connection context)
          [report affected] (deletion-report (checked (db/db connection)) subject)]
      (if (or (blocking? report) (some keyword? affected))
        (cond-> (refusal operation report affected)
          (some keyword? affected)
          (assoc :seon.error/message
                 "Namespace removal requires the schema owner's declaration-and-attribute retraction; its turn.clj seam is held."
                 :seon.error/data
                 (merge (:seon.error/data (refusal operation report affected))
                        {:seon.error/diagnostic-cause :schema-retraction-unavailable})))
        (let [transaction
              (checked
               (db/transact!
                connection
                [[:db.fn/call
                  (fn [database]
                    (let [[current subjects] (deletion-report database subject)]
                      (when (or (blocking? current) (some keyword? subjects))
                        (throw (ex-info "Program retraction refused." (refusal operation current subjects))))
                      (mapv (fn [target]
                              [:db/retractEntity (:seon.program/identity (locate database target))])
                            (sort-by str subjects))))]]))]
          (native! (:seon.sci.eval/ctx context) native-form)
          (native! (:my.program/base-ctx context) native-form)
          {:seon.program/subject subject
           :seon.program/affected (second (deletion-report (:db-before transaction) subject))
           :seon.db/basis-t (db/basis-t (:db-after transaction))
           :seon.program/unknown (:seon.program/unknown report)})))))

(defn ns-unmap!
  "Retract a declaration, then unmap its Var in the executing fork and base.

  Calls breaks first; a refusal changes neither facts nor SCI. Until seam B
  lands, deletion refuses from breaks alone; :seon.program/unknown records
  dispatch, apply and macro callers the analyzer cannot see.
  Example: (my.program/ns-unmap! 'seon.turn/open?)"
  {:malli/schema [:=> [:cat :my.program/context :qualified-symbol]
                  [:or :seon.program/change :seon.error/value]]}
  [context subject]
  (retract-operation! context subject 'my.program/ns-unmap!
                      (list 'clojure.core/ns-unmap (list 'quote (symbol (namespace subject)))
                            (list 'quote (symbol (name subject))))))

(defn remove-ns!
  "Retract a namespace and its owned declarations, then remove it from SCI.

  Calls breaks first and writes the entire affected set in one transaction.
  Until seam B lands, deletion refuses from breaks alone; the returned
  :seon.program/unknown retains dispatch, apply and macro coverage limits.
  Namespaces owning schema declarations refuse until the existing schema
  retraction seam can be shared; declaration-only deletion would leave DB attributes.
  Example: (my.program/remove-ns! 'my.scratch)"
  {:malli/schema [:=> [:cat :my.program/context :symbol]
                  [:or :seon.program/change :seon.error/value]]}
  [context namespace-name]
  (retract-operation! context namespace-name 'my.program/remove-ns!
                      (list 'clojure.core/remove-ns (list 'quote namespace-name))))

(defn ns-unalias!
  "Retract an alias component before removing the alias from fork and base.

  Calls breaks first. Namespace referrers conservatively refuse removal:
  indexed calls do not retain which alias spelling resolved their targets.
  Until seam B lands this refusal comes from breaks alone; the result carries
  :seon.program/unknown for dispatch, apply and macro callers.
  Example: (my.program/ns-unalias! 'my.scratch 'str)"
  {:malli/schema [:=> [:cat :my.program/context :symbol :symbol]
                  [:or :seon.program/change :seon.error/value]]}
  [context namespace-name alias-name]
  (read-result
   {:seon.program/subject namespace-name} 'my.program/ns-unalias!
   #(let [connection (:seon.db/connection context)
          [report affected] (deletion-report (checked (db/db connection)) namespace-name)]
      (if (blocking? report)
        (refusal 'my.program/ns-unalias! report affected)
        (let [transaction
              (checked
               (db/transact!
                connection
                [[:db.fn/call
                  (fn [database]
                    (let [[current subjects] (deletion-report database namespace-name)
                          _ (when (blocking? current)
                              (throw (ex-info "Alias removal refused." (refusal 'my.program/ns-unalias! current subjects))))
                          aliases (checked
                                   (db/q database
                                         '[:find [?alias ...] :in $ ?ns ?local :where
                                           [?namespace :seon.ns/name ?ns]
                                           [?namespace :seon.ns/aliases ?alias]
                                           [?alias :seon.ns.alias/local ?local]]
                                         namespace-name alias-name))]
                      (mapv (fn [entity] [:db/retractEntity entity]) aliases)))]]))
              form (list 'clojure.core/ns-unalias (list 'quote namespace-name) (list 'quote alias-name))]
          (native! (:seon.sci.eval/ctx context) form)
          (native! (:my.program/base-ctx context) form)
          {:seon.program/subject namespace-name :seon.program/affected #{namespace-name}
           :seon.db/basis-t (db/basis-t (:db-after transaction))
           :seon.program/unknown (:seon.program/unknown report)})))))

(defn native-call-refusal
  "Return a reduced diagnostic for native program mutation, or nil.

  The SCI hook calls this before supplied-default preparation. Intern and
  alter-var-root remain available for private bindings with no program row.
  Example: called by the installed SCI call-preparation hook."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.schema/value :seon.schema/arguments]
                  :seon.schema/value]}
  [ctx callee arguments]
  (let [metadata (meta callee)
        native (when (:ns metadata) (symbol (str (:ns metadata)) (str (:name metadata))))
        operation (case native
                    clojure.core/remove-ns 'my.program/remove-ns!
                    clojure.core/ns-unalias 'my.program/ns-unalias!
                    clojure.core/intern 'my.program/define!
                    clojure.core/alter-var-root 'my.program/define!
                    nil)]
    (when operation
      (let [environment ((requiring-resolve 'seon.env/of) ctx)
            connection (:seon.db/connection environment)]
        (when connection
          (let [subject (case native
                          clojure.core/alter-var-root
                          (let [m (meta (first arguments))]
                            (when (:ns m) (symbol (str (:ns m)) (str (:name m)))))
                          clojure.core/intern
                          (when (and (first arguments) (second arguments))
                            (symbol (str (first arguments)) (str (second arguments))))
                          (when (first arguments) (symbol (str (first arguments)))))
                report (when subject
                         (breaks {:seon.db/db (db/db connection) :seon.program/subject subject}))]
            (when (or (#{'clojure.core/remove-ns 'clojure.core/ns-unalias} native)
                      (and report (not (:seon.error/kind report))))
              (reduced
               (error/diagnostic
                {:seon.error/kind :seon.program/declaration-refused
                 :seon.program/declaration-refused true
                 :seon.error/message (str "Use " operation " so program facts decide before SCI changes.")
                 :seon.error/diagnostic-layer :program-write
                 :seon.error/diagnostic-operation operation
                 :seon.error/diagnostic-member native
                 :seon.error/diagnostic-expected operation
                 :seon.error/diagnostic-offending arguments
                 :seon.error/diagnostic-cause :native-program-mutation
                 :seon.error/diagnostic-evidence (or report {})
                 :seon.error/data (or report {})})))))))))
