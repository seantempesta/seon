(ns ^{:seon.ns/context-relevant? true} my.program
  "Read the program graph before changing a declaration.

  Reads use one supplied database value. Writes check those facts before
  changing SCI; referrers, past reach and unknown coverage remain separate.

  Example:
  (my.program/breaks {:seon.program/subject 'seon.turn/open?})"
  (:require [seon.db :as db]
            [clojure.set :as set]
            [seon.error :as error]
            [seon.fn :as function]
            [seon.issue :as issue]))

(defn- read-result
  {:malli/schema
   [:=> [:cat :map :qualified-symbol [:=> [:cat] :seon.schema/value]]
    [:or :seon.program/breakage :seon.program/history-report
     [:vector :seon.fn/sym] :seon.program/change
     :seon.program/read-refused-error :seon.program/not-found-error
     :seon.program/mutation-refused-error]]}
  [request operation read-value]
  (try (read-value)
       (catch Exception failure
         (if (or (:seon.program/not-found (ex-data failure))
                 (:seon.program/blocked-subject (ex-data failure))
                 (:seon.program/read-operation (ex-data failure)))
           (ex-data failure)
           {:seon.error/at (java.util.Date.)
             :seon.error/layer :seon.program/read
             :seon.error/operation 'my.program/read-result
             :seon.error/message (str "Cannot read program facts: " (ex-message failure))
             :seon.program/read-operation operation
             :seon.error/exception-class (symbol (.getName (class failure)))
             :seon.error/offending request
             :seon.error/expected :seon.program/breakage
             :seon.error/data (merge (ex-data failure) {:seon.error/member (:seon.program/subject request) :seon.error/source (ex-message failure)})}))))

(defn- subject-identities [subject]
  (cond
    (keyword? subject) [:seon.schema/key]
    (qualified-symbol? subject) [:seon.fn/sym :seon.test/sym]
    :else [:seon.ns/name]))

(defn- locate
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/subject] [:map [:seon.program/kind :seon.program/kind] [:seon.program/identity :seon.program/identity] [:seon.program/entities [:vector :int]]]]}
  [database subject]
  (or (some (fn [attribute]
              (let [value subject
                    entities (let [result (db/q database
                                    '[:find [?entity ...] :in $ ?attribute ?value
                                      :where [?entity ?attribute ?value]]
                                    attribute value)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))]
                (when (seq entities)
                  {:seon.program/kind attribute
                   :seon.program/identity [attribute value]
                   :seon.program/entities entities})))
            (subject-identities subject))
      (let [refusal {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.program/read
         :seon.error/operation 'my.program/locate
         :seon.error/message (str "No program declaration names " subject ".")
         :seon.program/not-found subject
         :seon.error/expected (subject-identities subject)
         :seon.error/data {:seon.db/basis-t (db/basis-t database)}}] (throw (ex-info (:seon.error/message refusal) refusal)))))

(defn- names-through
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value :qualified-keyword] [:vector :seon.schema/value]]}
  [database attribute target identity-attribute]
  (let [result (db/q database
         '[:find [?name ...] :in $ ?attribute ?target ?identity
           :where [?owner ?attribute ?target] [?owner ?identity ?name]]
         attribute target identity-attribute)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result)))

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

(defn- caller-data
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/subject :int] [:map [:seon.program/callers {:optional true} :seon.program/callers] [:seon.program/call-sites {:optional true} :seon.program/call-sites]]]}
  [database subject entity]
  (let [callers (into (set (names-through database :seon.fn/calls subject :seon.fn/sym))
                      (names-through database :seon.fn/calls subject :seon.test/sym))
        arities (let [result (db/q database
                       '[:find [?arity ...] :in $ ?entity
                         :where [?entity :seon.fn/arities ?arity]] entity)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))
        declared (mapv #(dissoc (let [result (db/pull database
                                                 [:seon.fn.arity/min :seon.fn.arity/max] %)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))
                                :db/id) arities)
        sites
        (into []
              (mapcat
               (fn [caller]
                 (let [stored caller
                       row (or (let [result (db/pull database [:db/id :seon.fn/form-span]
                                                 [:seon.fn/sym stored])]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))
                               (let [result (db/pull database [:db/id :seon.fn/form-span]
                                                 [:seon.test/sym stored])]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result)))
                       tuples (let [result (db/q database
                                     '[:find [?tuple ...] :in $ ?entity
                                       :where [?entity :seon.fn/call-arities ?tuple]]
                                     (:db/id row))]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))
                       counts (keep (fn [[callee arity]]
                                      (when (= subject callee) arity)) tuples)
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
                  [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error]]}
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
                  [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error]]}
  [{database :seon.db/db subject :seon.program/subject :as request}]
  (read-result request 'my.program/tests-reaching
               #(let [{kind :seon.program/kind} (locate database subject)]
                  (merge (observation database subject kind)
                         (present-groups
                          {:seon.program/gating
                           (mapv symbol (let [result (function/gate-set
                                                  database subject)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result)))})))))

(defn- key-data
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/key :int] :map]}
  [database schema-key _entity]
  (let [contracts
        (let [result (db/q database
               '[:find [?name ...]
                 :in $ ?key [?attribute ...]
                 :where [?arity ?attribute ?key]
                 [?function :seon.fn/arities ?arity]
                 [?function :seon.fn/sym ?name]]
               schema-key [:seon.fn.arity/input-refs :seon.fn.arity/output-refs
                       :seon.fn.arity/guard-refs])]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))
        datoms (if (get-in (db/schema-database database) [:schema schema-key])
                 (let [result (db/datoms database :aevt schema-key)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result)) [])]
    (present-groups
     {:seon.program/contract-refs (symbols contracts)
      :seon.program/writes-of (symbols (names-through database :seon.fn/writes schema-key :seon.fn/sym))
      :seon.program/schema-references
      (set (names-through database :seon.schema/references schema-key :seon.schema/key))
      :seon.program/mentions (symbols (let [result (function/functions-using database schema-key)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result)))
      :seon.program/data-in-use (when (seq datoms)
                                 {:seon.schema/key schema-key :seon.schema/datoms (count datoms)})})))

(defn reads-key
  "Read contract references, declared writes and literal keyword mentions.

  Mentions are membership evidence only, never a read or write claim.
  Example:
  (my.program/reads-key {:seon.schema/key :seon.db/connection})"
  {:malli/schema [:=> [:cat :my.program/key-request]
                  [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error]]}
  [{database :seon.db/db schema-key :seon.schema/key :as request}]
  (read-result request 'my.program/reads-key
               #(let [{entities :seon.program/entities} (locate database schema-key)]
                  (-> (merge (observation database schema-key :seon.schema/key)
                             (key-data database schema-key (first entities)))
                      (update :seon.program/unknown conj
                              {:seon.program/gap :keyword-mentions
                               :seon.program/message "Constructed keywords and reads not declared in contracts are unknown; mentions never block retraction."})))))

(defn- render-referrers
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/subject] :seon.program/render-declared-by]}
  [database subject]
  (into #{}
        (mapcat
         (fn [property]
           (keep (fn [[schema-key renderer]]
                   (when (= subject renderer)
                     {:seon.schema/key schema-key :seon.schema/property property}))
                 (let [result (db/q database
                                '[:find ?key ?renderer :in $ ?property :where
                                  [?schema :seon.schema/key ?key]
                                  [?schema ?property ?renderer]]
                                property)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result)))))
        [:seon.render/ai :seon.render/html]))

(defn- proposed-plan
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/subject [:or :nil :seon.program/callers] [:or :nil :seon.program/call-sites]] :seon.program/plan]}
  [database subject caller-symbols sites]
  (let [names (sort caller-symbols)
        stored (vec names)
        gates (let [result (function/gate-sets database stored)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))
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
               :seon.issue/detector [:seon.fn/sym detector]}
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
                  [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error]]}
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
                           [subject]))
          gates (let [result (function/gate-sets database (vec gate-names))]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))
          report
          (merge (observation database subject kind) calls
                 (present-groups
                  {:seon.program/references (symbols (names-through database :seon.fn/references subject :seon.fn/sym))
                   :seon.program/subject-of (symbols (names-through database :seon.test/subject subject :seon.test/sym))
                   :seon.program/stale-reach (symbols (names-through database :seon.test/reach subject :seon.test/sym))
                   :seon.program/gating (vec (sort (symbols (mapcat val gates))))
                   :seon.program/capability-of (symbols (names-through database :seon.effect/capability subject :seon.fn/sym))
                   :seon.program/schedule-tasks (set (names-through database :seon.schedule.task/function entity :seon.schedule.task/id))
                   :seon.program/render-declared-by (render-referrers database subject)
                   :seon.program/owned-declarations owned
                   :seon.program/requiring-namespaces
                   (when (= :seon.ns/name kind)
                     (set (names-through database :seon.ns/requires subject :seon.ns/name)))})
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

(defn- entity-facts
  {:malli/schema [:=> [:cat :seon.db/database-value :int] :seon.program/definition]}
  [database entity]
  (reduce (fn [row {:keys [a v]}]
            (if (= :db.cardinality/many (get-in (db/schema-database database) [:schema a :db/cardinality]))
              (update row a (fnil conj #{}) v)
              (assoc row a v)))
          {} (let [result (db/datoms database :eavt entity)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))))

(defn history
  "Read source events and their definition facts and transaction provenance.

  Supply :seon.db/tx for the source visible as of that transaction; omit it
  for ordered assertion and retraction events. Refs remain entity ids.
  Example:
  (my.program/history {:seon.program/subject 'seon.turn/open?})"
  {:malli/schema [:=> [:cat :my.program/history-request]
                  [:or :seon.program/history-report :seon.program/read-refused-error :seon.program/not-found-error]]}
  [{database :seon.db/db subject :seon.program/subject at :seon.db/tx :as request}]
  (read-result
   request 'my.program/history
   #(let [view (if at (let [result (db/as-of database at)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result)) database)
          past (let [result (db/history database)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))
          {kind :seon.program/kind entities :seon.program/entities} (locate past subject)
          identity-declaration (let [result (db/pull database [:seon.program/source-attribute]
                                                [:seon.schema/key kind])]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))
          source-attribute (:seon.program/source-attribute identity-declaration)
          events (let [result (db/q (if at view past)
                        '[:find ?entity ?source ?tx ?added
                          :in $ [?entity ...] ?attribute
                          :where [?entity ?attribute ?source ?tx ?added]]
                        entities source-attribute)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))
          entries
          (mapv (fn [[entity source tx added]]
                     (let [snapshot (let [result (db/as-of database (or at (if added tx (dec tx))))]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))
                           provenance (let [result (db/pull database [:seon.db/user :seon.db/process] tx)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))]
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
                  [:or :my.program/context :seon.program/context-unavailable-error]]}
  [environment]
  (let [ctx (:my.program/executing-ctx environment)
        base (:my.program/base-ctx ctx)
        connection (:seon.db/connection environment)]
    (if (and ctx base connection)
      {:seon.sci.eval/ctx ctx :my.program/base-ctx base :seon.db/connection connection}
      {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.program/write
        :seon.error/operation 'my.program/supplied-context
        :seon.program/missing-context-members (into #{} (keep (fn [[member value]] (when-not value member)))
              [[:my.program/executing-ctx ctx] [:my.program/base-ctx base]
               [:seon.db/connection connection]])
        :seon.error/message "Program mutation requires the executing SCI context, its cluster base and connection."
        :seon.error/member :my.program/context
        :seon.error/expected :my.program/context
        :seon.error/offending (select-keys environment [:seon.agent/id])
        :seon.error/data {:my.program/executing? (boolean ctx)
                                         :my.program/base? (boolean base)
                                         :my.program/connection? (boolean connection)}})))

(defn overrides
  "Read current agent-admitted identities under an indexed src root.

  The declaration owner follows historical file provenance over this same
  database value. This does not assert JVM write-back or SCI loadability.
  Example: (my.program/overrides)"
  {:malli/schema [:=> [:cat :my.program/read-request]
                  [:or [:vector :seon.fn/sym] :seon.program/read-refused-error]]}
  [{database :seon.db/db :as request}]
  (read-result request 'my.program/overrides
               #(let [result ((requiring-resolve 'seon.program/overrides) database)]
                  (if (:seon.db/invalid-read result)
                    (throw (ex-info (:seon.error/message result) result))
                    result))))

(defn- refusal
  {:malli/schema [:=> [:cat :qualified-symbol :seon.program/breakage :seon.program/affected]
                  :seon.program/mutation-refused-error]}
  [operation report affected]
  (merge
   {:seon.error/at (java.util.Date.)
     :seon.error/layer :seon.program/write
     :seon.error/operation 'my.program/refusal
     :seon.program/blocked-subject (:seon.program/subject report)
     :seon.error/message (str "Cannot perform " operation " on " (:seon.program/subject report)
                              "; repair the named referrers first.")
     :seon.error/data report
     :seon.error/expected :seon.program/change}
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
  (let [root (let [result (breaks {:seon.db/db database :seon.program/subject subject})]
 (if (or (:seon.program/read-operation result) (:seon.program/not-found result))
 (throw (ex-info (:seon.error/message result) result)) result))
        affected (conj (set (:seon.program/owned-declarations root)) subject)
        reports (into [root]
                      (map #(let [result (breaks {:seon.db/db database :seon.program/subject %})]
 (if (or (:seon.program/read-operation result) (:seon.program/not-found result))
 (throw (ex-info (:seon.error/message result) result)) result)))
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
  ((requiring-resolve 'seon.sci.eval/evaluate-native!) ctx form))

(defn- retract-operation!
  {:malli/schema [:=> [:cat :my.program/context :seon.program/subject :qualified-symbol [:sequential :seon.schema/value]] [:or :seon.program/change :seon.program/read-refused-error :seon.program/not-found-error :seon.program/mutation-refused-error]]}
  [context subject operation native-form]
  (read-result
   {:seon.program/subject subject} operation
   #(let [connection (:seon.db/connection context)
          [report affected] (deletion-report (let [result (db/db connection)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result)) subject)]
      (if (or (blocking? report) (some keyword? affected))
        (cond-> (refusal operation report affected)
          (some keyword? affected)
          (assoc :seon.error/message
                 "Namespace removal requires the schema owner's declaration-and-attribute retraction; its turn.clj seam is held."
                 :seon.error/data
                 (merge (:seon.error/data (refusal operation report affected))
                        {})))
        (let [transaction
              (let [result (db/transact!
                connection
                [[:db.fn/call
                  (fn [database]
                    (let [[current subjects] (deletion-report database subject)]
                      (when (or (blocking? current) (some keyword? subjects))
                        (throw (ex-info "Program retraction refused." (refusal operation current subjects))))
                      (mapv (fn [target]
                              [:db/retractEntity (:seon.program/identity (locate database target))])
                            (sort-by str subjects))))]])]
 (if (:seon.db/transaction-refused result)

 (throw (ex-info (:seon.error/message result) result)) result))]
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
                  [:or :seon.program/change :seon.program/read-refused-error :seon.program/not-found-error :seon.program/mutation-refused-error]]}
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
                  [:or :seon.program/change :seon.program/read-refused-error :seon.program/not-found-error :seon.program/mutation-refused-error]]}
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
                  [:or :seon.program/change :seon.program/read-refused-error :seon.program/not-found-error :seon.program/mutation-refused-error]]}
  [context namespace-name alias-name]
  (read-result
   {:seon.program/subject namespace-name} 'my.program/ns-unalias!
   #(let [connection (:seon.db/connection context)
          [report affected] (deletion-report (let [result (db/db connection)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result)) namespace-name)]
      (if (blocking? report)
        (refusal 'my.program/ns-unalias! report affected)
        (let [transaction
              (let [result (db/transact!
                connection
                [[:db.fn/call
                  (fn [database]
                    (let [[current subjects] (deletion-report database namespace-name)
                          _ (when (blocking? current)
                              (throw (ex-info "Alias removal refused." (refusal 'my.program/ns-unalias! current subjects))))
                          aliases (let [result (db/q database
                                         '[:find [?alias ...] :in $ ?ns ?local :where
                                           [?namespace :seon.ns/name ?ns]
                                           [?namespace :seon.ns/aliases ?alias]
                                           [?alias :seon.ns.alias/local ?local]]
                                         namespace-name alias-name)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result))]
                      (mapv (fn [entity] [:db/retractEntity entity]) aliases)))]])]
 (if (:seon.db/transaction-refused result)

 (throw (ex-info (:seon.error/message result) result)) result))
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
                      (:seon.program/kind report))
              (reduced
               {:seon.error/at (java.util.Date.)
                 :seon.error/layer :seon.program/write
                 :seon.error/operation 'my.program/native-call-refusal
                 :seon.program/native-operation native
                 :seon.program/advised-operation operation
                 :seon.error/message (str "Use " operation " so program facts decide before SCI changes.")
                 :seon.error/offending arguments
                 :seon.error/data (or report {})}))))))))
