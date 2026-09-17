(ns ^{:seon.ns/context-relevant? true} my.program
  "Read the program graph before changing a declaration.

  Every operation reads one supplied database value. Referrers, past test
  reach and unknown analyzer coverage remain separate data.

  Example:
  (my.program/breaks {:seon.program/subject 'seon.turn/open?})"
  (:require [seon.db :as db]
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
