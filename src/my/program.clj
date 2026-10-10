(ns ^{:seon.ns/context-relevant? true} my.program
  "Read the program graph before changing a declaration.

  Reads use one supplied database value. Writes check those facts before
  changing SCI; referrers, past reach and unknown coverage remain separate.

  Example:
  (my.program/breaks {:seon.program/subject 'seon.turn/open?})"
  (:require [seon.error.refusal]
            [seon.db :as db]
            [clojure.set :as set]
            [clojure.string :as str]
            [seon.error :as error]
            [seon.fn :as function]
            [seon.render.value :as render.value]
                    [seon.cluster.branch :as cluster.branch]
            [seon.cluster.message :as message]
            [seon.task :as task]
            [seon.cluster.registry :as registry]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.source :as source]
            [seon.fault :as fault]
            [seon.flow :as work]
            [seon.test]
            [malli.error]))

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
           ;; The request is referenced (its subject and the basis read),
           ;; never copied: it carries the database. The failure is the
           ;; whole cause chain, from which the class is derived.
           (seon.error.refusal/diagnostic (java.util.Date.) :seon.program/read 'my.program/read-result
            {:seon.error/message (str "Cannot read program facts: " (ex-message failure))
             :seon.program/read-operation operation
             :seon.error/throwable failure
             :seon.error/offending
             (cond-> (select-keys request [:seon.program/subject])
               (:seon.db/db request) (assoc :seon.db/basis-t (db/basis-t (:seon.db/db request))))
             :seon.error/expected :seon.program/breakage})))))

(defn- subject-identities [subject]
  (cond
    (keyword? subject) [:seon.schema/key]
    (qualified-symbol? subject) [:seon.fn/sym :seon.test/sym]
    :else [:seon.ns/name]))

(defn- locate
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/subject] [:map [:seon.program/identity :seon.program/identity] [:seon.program/entities [:vector :int]]]]}
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
                  {:seon.program/identity [attribute value]
                   :seon.program/entities entities})))
            (subject-identities subject))
      (let [refusal (seon.error.refusal/diagnostic (java.util.Date.) :seon.program/read 'my.program/locate
                     {:seon.error/message (str "No program declaration names " subject ".")
         :seon.program/not-found subject
         :seon.error/expected (subject-identities subject)
         :seon.error/data {:seon.db/basis-t (db/basis-t database)}})] (throw (ex-info (:seon.error/message refusal) refusal)))))

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

(defn- observation [database subject identity]
  {:seon.program/subject subject
   :seon.program/identity identity
   :seon.db/basis-t (db/basis-t database)
   :seon.program/unknown
   [{:seon.program/gap :outside-graph
     :seon.program/message
     "This supplied database value answers; callers outside its program graph are unknown."}]})

(defn- caller-data
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/subject :int] [:map [:seon.program/callers {:optional true} :seon.program/callers] [:seon.program/call-sites {:optional true} :seon.program/call-sites] [:seon.fn/declared-arities {:optional true} :seon.fn/declared-arities]]]}
  [database subject entity]
  (let [read! (fn [result]
                (if (:seon.db/invalid-read result) (throw (ex-info (:seon.error/message result) result)) result))
        callers (into (set (names-through database :seon.fn/calls subject :seon.fn/sym))
                      (names-through database :seon.fn/calls subject :seon.test/sym))
        sorted (vec (sort callers))
        arities (read! (db/q database '[:find [?arity ...] :in $ ?entity :where [?entity :seon.fn/arities ?arity]] entity))
        declared (mapv #(dissoc % :db/id) (read! (db/pull-many database [:seon.fn.arity/min :seon.fn.arity/max] arities)))
        selector [:db/id :seon.fn/form-span :seon.fn/call-arities]
        functions (read! (db/pull-many database selector (mapv #(vector :seon.fn/sym %) sorted)))
        missing (into [] (keep-indexed (fn [position pulled] (when-not pulled position))) functions)
        tests (when (seq missing)
                (read! (db/pull-many database selector (mapv #(vector :seon.test/sym (sorted %)) missing))))
        pulled (reduce (fn [found [position entity-map]] (assoc found position entity-map))
                       functions (map vector missing tests))
        site-of (fn [caller entity-map]
                  (let [counts (keep (fn [[callee arity]] (when (= subject callee) arity))
                                     (:seon.fn/call-arities entity-map))
                        site (cond-> {:seon.fn/sym caller}
                               (:seon.fn/form-span entity-map)
                               (assoc :seon.fn/form-span (:seon.fn/form-span entity-map)))]
                    (if (seq counts)
                      (map #(assoc site :seon.fn/call-arity %) (sort counts))
                      [site])))]
    (present-groups {:seon.program/callers callers
                     :seon.program/call-sites (vec (mapcat site-of sorted pulled))
                     :seon.fn/declared-arities declared})))

(defn callers
  "Read each direct caller once, as a call site with its declaration span and recorded call arity, and the subject's declared arities once.

  Empty groups are absent; unknown coverage remains explicit.
  Example:
  (my.program/callers {:seon.program/subject 'seon.turn/open?})"
  {:malli/schema [:=> [:cat :my.program/subject-request]
                  [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error]]}
  [{database :seon.db/db subject :seon.program/subject :as request}]
  (read-result request 'my.program/callers
               #(let [{identity :seon.program/identity entities :seon.program/entities}
                      (locate database subject)]
                  (merge (observation database subject identity)
                         (dissoc (caller-data database subject (first entities)) :seon.program/callers)))))

(defn tests-reaching
  "Read the current gate set, derived by seon.fn/gate-set.

  This is current test selection, not recorded reach from a past test run.
  Example:
  (my.program/tests-reaching {:seon.program/subject 'seon.turn/open?})"
  {:malli/schema [:=> [:cat :my.program/subject-request]
                  [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error]]}
  [{database :seon.db/db subject :seon.program/subject :as request}]
  (read-result request 'my.program/tests-reaching
               #(let [{identity :seon.program/identity} (locate database subject)]
                  (merge (observation database subject identity)
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
               #(let [{identity :seon.program/identity entities :seon.program/entities} (locate database schema-key)]
                  (-> (merge (observation database schema-key identity)
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
                                '[:find ?key ?renderer :in $ ?property ?renderer :where
                                  [?function :seon.fn/sym ?renderer]
                                  [?schema ?property ?function]
                                  [?schema :seon.schema/key ?key]]
                                property subject)]
 (if (:seon.db/invalid-read result)

 (throw (ex-info (:seon.error/message result) result)) result)))))
        [:seon.render/ai :seon.render/html]))

(defn breaks
  "Read the referrers a retraction would strand.

  Callers, references and test subjects block; stored test reach is advisory.
  Nothing is written. Gating tests come from one reverse walk seeded with the
  subject, or with every function and test a namespace owns. Dispatch, macro
  and runtime-computed callers are not modelled; call sites without a recorded
  argument count are counted under :seon.program/unknown. A supplied contract
  is advisory: argument shapes require the existing candidate test gate.

  Example:
  (my.program/breaks {:seon.program/subject 'seon.turn/open?})"
  {:seon/tasks #{'my.program/breaks-plans-a-contract-change-as-a-retraction}
   :malli/schema [:=> [:cat :my.program/breaks-request]
                  [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error]]}
  [{database :seon.db/db subject :seon.program/subject :as request}]
  (read-result
   request 'my.program/breaks
   #(let [{identity :seon.program/identity entities :seon.program/entities} (locate database subject)
          [kind _] identity
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
          gating (let [result (function/gating-tests database (vec (symbols gate-names)))]
                   (if (:seon.db/invalid-read result)
                     (throw (ex-info (:seon.error/message result) result))
                     result))
          report
          (merge (observation database subject identity) calls
                 (present-groups
                  {:seon.program/references (symbols (names-through database :seon.fn/references subject :seon.fn/sym))
                   :seon.program/subject-of (symbols (names-through database :seon.test/subject subject :seon.test/sym))
                   :seon.program/stale-reach (symbols (names-through database :seon.test/reach subject :seon.test/sym))
                   :seon.program/gating gating
                   :seon.program/capability-of (symbols (names-through database :seon.effect/capability subject :seon.fn/sym))
                   :seon.program/schedule-tasks (set (names-through database :seon.schedule.task/function entity :seon.schedule.task/id))
                   :seon.program/render-declared-by (render-referrers database subject)
                   :seon.program/owned-declarations owned
                   :seon.program/requiring-namespaces
                   (when (= :seon.ns/name kind)
                     (set (names-through database :seon.ns/requires subject :seon.ns/name)))})
                 (when (= :seon.schema/key kind) (key-data database subject entity)))
          unknown-arities (count (remove :seon.fn/call-arity (:seon.program/call-sites report)))]
      (cond-> report
        (pos? unknown-arities)
        (update :seon.program/unknown conj
                {:seon.program/gap :arity-unknown-sites
                 :seon.program/count unknown-arities
                 :seon.program/message "Calls without recorded argument counts, including apply, cannot be checked for arity."})
        (:seon.program/contract request)
        (update :seon.program/unknown conj
                {:seon.program/gap :contract-shapes-unknown
                 :seon.program/message "The proposed contract has not run through the candidate test gate; these referrers are suspects, not proven contract violations."})))))

(defn render-breakage-ai
  "A breakage as an agent acts on it: callers once, as call sites; each relation
  whole when it fits the render profile's children, else its count and the form
  that lists it."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.program/breakage-view]}
  [unit]
  (let [report (:seon.render/value unit)
        limit (get-in unit [:seon.render/profile :seon.render.profile/max-children])
        subject (:seon.program/subject report)
        request {:seon.program/subject (if (symbol? subject) (list 'quote subject) subject)}
        cut? (fn [[relation group]]
               (and limit (not= :seon.program/unknown relation) (coll? group) (not (map? group))
                    (< limit (count group))))]
    (into {}
          (map (fn [[relation group :as entry]]
                 (if (cut? entry)
                   [relation {:seon.program/count (count group)
                              :seon.render/operations [(list relation (list 'my.program/breaks request))]}]
                   entry)))
          ;; every caller is named by its call sites
          (cond-> report (:seon.program/call-sites report) (dissoc :seon.program/callers)))))

(defn- entity-facts
  "`entity`'s attributes at `database` (`seon.db/stored-entity`): refs remain
   entity ids, and a stored vector reads whole, in order, from its cells."
  {:malli/schema [:=> [:cat :seon.db/database-value :int] :seon.program/definition]}
  [database entity]
  (db/stored-entity database entity))

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
          {identity :seon.program/identity entities :seon.program/entities} (locate past subject)
          [kind _] identity
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
      (cond-> (assoc (observation database subject identity) :seon.program/history entries)
        at (assoc :seon.db/tx at)))))

(defn supplied-context
  "Supply the executing SCI context and its held acquisition resources.

  Call preparation carries the executing context explicitly; no process or
  thread lookup is used. A host call without that context refuses."
  {:seon.fn/internal? true
   :malli/schema [:=> [:cat :seon.env/environment]
                  [:or :my.program/context :seon.program/context-unavailable-error]]}
  [environment]
  (let [ctx (:my.program/executing-ctx environment)
        base (or (:seon.sci.eval/base-ctx ctx) (:my.program/base-ctx ctx))
        connection (:seon.db/connection environment)]
    (if (and ctx base connection)
      (merge {:seon.sci.eval/ctx ctx :my.program/base-ctx base
              :seon.db/connection connection
              :seon.cluster/name (:seon.boot/cluster-name environment)
              :seon.env/environment environment}
             (select-keys environment [:seon.store/store :seon.agent/routing]))
      (seon.error.refusal/diagnostic (java.util.Date.) :seon.program/write 'my.program/supplied-context
       {:seon.program/missing-context-members (into #{} (keep (fn [[member value]] (when-not value member)))
              [[:my.program/executing-ctx ctx] [:my.program/base-ctx base]
               [:seon.db/connection connection]])
        :seon.error/message "Program mutation requires the executing SCI context, its cluster base and connection."
        :seon.error/member :my.program/context
        :seon.error/expected :my.program/context
        :seon.error/offending (select-keys environment [:seon.agent/id])
        :seon.error/data {:my.program/executing? (boolean ctx)
                                         :my.program/base? (boolean base)
                                         :my.program/connection? (boolean connection)}}))))

(defn propose-merge!
  "Propose my branch's change for the task I am assigned, once its proofs pass here.

  The assignment is root's, read on the cluster (`seon.task/assignment`); I
  name no task. Every test naming the task (on the cluster and on my branch)
  runs on my branch, and each must then hold green evidence current for my
  program (`seon.test/verified?`); otherwise the refusal names them and root
  hears nothing. A green proof records on the cluster the merge task and my
  proposal (`seon.task/propose!`), then tells root with a message naming the
  merge task, and answers the proposal with root's `prepare-merge!` offered. A
  failed message leaves the proposal on record (root's `proposals` reads it)
  and is named as `:seon.task/undelivered`.

  Example:
  (my.program/propose-merge! {})"
  {:seon/tasks #{'my.program/an-agent-cannot-see-or-choose-its-change}
   :malli/schema [:=> [:cat [:map [:my.program/context :my.program/context] [:seon.db/connection :seon.db/connection]
                             [:seon.agent/id :seon.agent/id]]]
                  [:or :seon.task/proposal-answer :seon.task/refused-error :seon.task/conflict-error :seon.error/value]]}
  [{context :my.program/context connection :seon.db/connection agent-id :seon.agent/id}]
  (let [branch (get-in (db/db connection) [:config :branch])
        work (task/assignment (db/db (task/home-connection connection)) agent-id branch)
        edges (when (:seon.task/sym work) (task/task-edges (db/db connection) (:seon.task/sym work)))
        proofs (into (set (:seon.task/proofs work)) (:seon.task/proofs edges))
        refuse #(seon.error.refusal/diagnostic (java.util.Date.) :seon.task/ingestion 'my.program/propose-merge!
                 {:seon.error/message (str "The proposal is refused: " (name %1) ".") :seon.task/refused %1
                  :seon.task/holder (:seon.task/ns work) :seon.error/offending %2})]
    (cond
      (:seon.error/at work) work
      (:seon.error/at edges) edges
      (empty? proofs) (refuse :no-proofs (:seon.task/key work))
      :else
      (let [run (seon.test/run {:seon.test/execution context :seon.test/recording-connection connection
                                :seon.test/policy :named :seon.test/identities proofs})
            proved (db/db connection)
            unproven (when-not (:seon.error/at run) (vec (sort (remove #(true? (seon.test/verified? proved %)) proofs))))
            digests (when (empty? unproven)
                      (db/q '[:find ?test ?digest :in $ [?test ...]
                              :where [?t :seon.test/sym ?test] [?t :seon.program/definition-digest ?digest]] proved (vec proofs)))
            proposal (when (= (count proofs) (count digests))
                       (task/propose! connection agent-id work
                                      {:seon.task/branch branch :seon.task/commit (:seon.source/commit-id run) :seon.task/proved (set digests)
                                       :seon.task/run-id (:seon.test.run/id run) :seon.task/program (:seon.test.run/program-digest run)}))]
        (cond (:seon.error/at run) run
              (seq unproven) (refuse :unproven unproven)
              (:seon.error/at proposal) proposal
              (nil? proposal) (refuse :unproven (vec (sort (remove (set (map first digests)) proofs))))
              :else
              (let [sent (message/send! {:my.message/to "root" :my.message/about (:seon.task/merge proposal)
                                         :my.message/content (str "Please merge branch " (name branch) ": merge task "
                                                                  (:seon.task/merge proposal) " (" (:seon.task/key work) ").")}
                                        connection agent-id)]
                ;; A failed message leaves the proposal on record: `proposals` reads it.
                (cond-> (assoc proposal :seon.render/operations
                               [(list 'my.program/prepare-merge! {:seon.task/merge (:seon.task/merge proposal)})])
                  (:seon.error/at sent) (assoc :seon.task/undelivered sent))))))))

(defn proposals
  "Read the open merge proposals on my cluster: every proposal whose merge task
  records no outcome yet, with its branch, commit, proofs and run, each
  offering its `prepare-merge!`.

  Example:
  (my.program/proposals)"
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.db/database-value]]]
                  [:or [:vector :seon.task/proposal-view] :seon.db/error-result]]}
  [{database :seon.db/db}]
  (let [found (db/q '[:find [(pull ?p [:seon.task/proposal :seon.task/merge :seon.task/branch :seon.task/commit
                                       :seon.task/proved :seon.task/run-id :seon.task/program]) ...]
                      :where [?p :seon.task/merge ?handle] [?t :seon.task/id ?handle] (not [?t :seon.task/outcome])]
                    database)]
    ;; a pulled cardinality-many value is a vector; :seon.task/proved is a set
    (if (db/read-refusal? found)
      found
      (mapv #(-> (update % :seon.task/proved set)
                 (assoc :seon.render/operations [(list 'my.program/prepare-merge! {:seon.task/merge (:seon.task/merge %)})]))
            (sort-by :seon.task/proposal found)))))

(defn- open-proposal
  "The open proposal of merge task `merge-task` my cluster records last, or
  the refusal naming it: the one `prepare-merge!` prepares."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/merge] [:or :seon.task/proposal-view :seon.error/value]]}
  [database merge-task]
  (let [found (db/q '[:find ?tx (pull ?p [:seon.task/proposal :seon.task/merge :seon.task/branch :seon.task/commit
                                          :seon.task/proved :seon.task/run-id :seon.task/program])
                      :in $ ?handle
                      :where [?p :seon.task/merge ?handle] [?p :seon.task/proposal _ ?tx]
                      [?t :seon.task/id ?handle] (not [?t :seon.task/outcome])]
                    database merge-task)]
    (cond (db/read-refusal? found) found
          (empty? found)
          (seon.error.refusal/diagnostic (java.util.Date.) :seon.program/write 'my.program/prepare-merge!
           {:seon.error/message (str "Merge task " merge-task " has no open proposal.")
            :seon.error/member :seon.task/merge :seon.error/offending merge-task
            :seon.render/operations ['(my.program/proposals)]})
          :else (update (second (last (sort-by first found))) :seon.task/proved set))))

(defn prepare-merge!
  "Test the open proposal of merge task `:seon.task/merge` (one of
  `proposals`) through the merge gate on a fresh branch of the head I hold and
  answer the verdict `accept-merge!` takes (`seon.cluster.source/prepare-merge!`):
  the whole run's `:seon.test/passed?`, tally and red members, shown; its
  proofs, the tests the proposal proved, gate acceptance. The answer offers its
  `accept-merge!`. Name `:seon.program/identities` to merge only those
  declarations; one reaching an unselected change refuses by name; a selected
  one carries its namespace's changed row (a require added at the REPL with
  `ns`). Name the checkout `:seon.fn/root` to prove the file write-back would
  produce before any test runs; the accepted merge then offers its
  `write-back!` there. The merge is prepared onto the head of the branch my
  context holds.

  Example:
  (my.program/prepare-merge! {:seon.task/merge \"0123456789ab\" :seon.fn/root \"/path/to/checkout\"})"
  {:malli/schema [:=> [:cat :my.program/prepare-request]
                  [:or :my.program/merge-proposal :seon.program/three-way :seon.source/write-back-error :seon.error/value]]}
  [{context :my.program/context merge-task :seon.task/merge selection :seon.program/identities root :seon.fn/root}]
  (let [proposal (open-proposal (db/db (:seon.db/connection context)) merge-task)
        prepared (when-not (:seon.error/at proposal)
                   (source/prepare-merge! context (:seon.store/store context) (:seon.task/branch proposal)
                                          (into #{} (map first) (:seon.task/proved proposal)) selection root))]
    (cond
      (:seon.error/at proposal) proposal
      (not (:seon.source/candidate prepared)) prepared
      :else
      (let [request (cond-> (assoc (select-keys prepared [:seon.source/candidate :seon.test.run/id :seon.task/proofs
                                                          :seon.source/expected-digests :parents
                                                          :seon.source/advanced-base])
                                   :seon.task/merge merge-task :seon.task/branch (:seon.task/branch proposal))
                      root (assoc :seon.fn/root root))]
        (merge prepared request
               {:seon.render/operations [(list 'my.program/accept-merge! request)]})))))

(defn accept-merge!
  "Merge exactly the program `prepare-merge!` tested into my cluster
  (`seon.cluster.source/accept-merge!`), record the merge task `:accepted`,
  and answer the accepted merge `write-back!` takes: the merged identities,
  the commit before it, its merge commit and the run. Refuses when a proof is
  not green, or when a merged declaration itself changed since the prepare,
  naming it with both digests. The merge closes the proposing branch: the
  tested branch and the proposal's own branch (its hosted agent first,
  `my.agent/retire-branch!`) retire afterwards as detached io work, off this
  call. The merge lands on the branch my context holds: root's is its cluster.

  Example:
  (def accepted (my.program/accept-merge! proposal))"
  {:malli/schema [:=> [:cat :my.program/accept-request]
                  [:or :seon.source/accepted :seon.source/test-evidence-error :seon.error/value]]}
  [{context :my.program/context tested :seon.source/candidate merge-task :seon.task/merge
    proposing :seon.task/branch :as proposal}]
  (let [store (:seon.store/store context)
        accepted (source/accept-merge! context store (dissoc proposal :my.program/context :seon.render/operations))
        ;; The merge task closes with the merge: `proposals` no longer lists it.
        closed (when (:seon.source/commit-id accepted)
                 (task/decide! (:seon.db/connection context) merge-task :accepted))
        environment (:seon.env/environment context)
        retire! #(do (registry/retire-branch! {:seon.store/store store :seon.store/branch tested})
                     ;; The agent's branch retires when its merge closes (AGENTS.md, merge).
                     (when proposing
                       (let [retired ((requiring-resolve 'my.agent/retire-branch!)
                                      {:seon.store/branch proposing}
                                      (select-keys context [:seon.store/store :seon.db/connection :seon.agent/routing]))]
                         (when (:seon.error/at retired) (throw (ex-info (:seon.error/message retired) retired))))))]
    ;; Unlinking takes the store's roster permit, which a running
    ;; collection holds for seconds: the launcher's io work, off this call.
    (when (and (:seon.source/commit-id accepted)
               (not (some-> (:seon.flow/work-launcher environment)
                            (work/submit!
                             {:seon.env/environment environment :seon.flow/workload :io
                              :seon.flow/submission-id (str "retire-" (name tested))
                              :seon.flow/work-fn (fn [_] (retire!))
                              :seon.flow/complete!
                              (fn [{failure :seon.flow/throwable}]
                                (when failure
                                  (fault/fault! environment failure
                                                {:seon.error/layer :seon.program/write
                                                 :seon.error/operation 'my.program/accept-merge!
                                                 :seon.db.process/id (cluster.process/id (cluster.process/current-identity))}))
                                nil)}))))
      ;; No launcher accepting work (a closing cluster): unlink here.
      (retire!))
    (cond (:seon.error/at closed) closed
          (and (:seon.source/commit-id accepted) (:seon.fn/root proposal))
          (assoc accepted :seon.render/operations
                 [(list 'my.program/write-back! {:seon.source/accepted accepted :seon.fn/root (:seon.fn/root proposal)})])
          :else accepted)))

(defn write-back!
  "Write an accepted merge's declarations into their source file under `root`
  (`seon.cluster.source/write-back!`): proven equivalent before an atomic
  install, checked against the branch my context holds. Answers the written
  path and digests.

  Example:
  (my.program/write-back! {:seon.source/accepted accepted :seon.fn/root \"/path/to/checkout\"})"
  {:malli/schema [:=> [:cat [:map [:my.program/context :my.program/context]
                             [:seon.source/accepted :seon.source/accepted] [:seon.fn/root :seon.fn/root]]]
                  [:or :seon.source/written :seon.source/write-back-error :seon.error/value]]}
  [{context :my.program/context accepted :seon.source/accepted root :seon.fn/root}]
  (source/write-back! {:seon.db/connection (:seon.db/connection context)
                       :seon.source/accepted accepted :seon.fn/root root}))

(def ^:private blocking-relations
  "The breakage relations that block a retraction."
  [:seon.program/callers :seon.program/references
   :seon.program/subject-of :seon.program/render-declared-by
   :seon.program/capability-of :seon.program/schedule-tasks
   :seon.program/contract-refs :seon.program/schema-references
   :seon.program/writes-of :seon.program/requiring-namespaces
   :seon.program/data-in-use])

(defn- refusal
  {:malli/schema [:=> [:cat :qualified-symbol :seon.program/breakage :seon.program/affected]
                  :seon.program/mutation-refused-error]}
  [operation report affected]
  (merge
   (seon.error.refusal/diagnostic (java.util.Date.) :seon.program/write operation
    {:seon.program/blocked-subject (:seon.program/subject report)
     :seon.error/message (str "Cannot perform " operation " on " (:seon.program/subject report)
                              "; repair the named referrers first.")
     :seon.error/data report
     :seon.error/expected "no surviving referrer"
     ;; The referrers that block it: the refactoring data the refusal exists to give.
     :seon.error/offending (into {} (filter (comp seq val)) (select-keys report blocking-relations))})
   {:seon.program/affected affected
    :seon.program/unknown (:seon.program/unknown report)}))

(defn- blocking? [report]
  (boolean (some seq (vals (select-keys report blocking-relations)))))

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
                             :seon.program/call-sites sites)]
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
                 (:seon.error/data (refusal operation report affected))))
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

  Calls breaks first; a refusal changes neither facts nor SCI and names the
  surviving referrers. :seon.program/unknown counts call sites without a
  recorded argument count; dispatch and macro callers are not modelled.

  Example:
  (my.program/ns-unmap! 'user/helper)"
  {:malli/schema [:=> [:cat :my.program/context :qualified-symbol]
                  [:or :seon.program/change :seon.program/read-refused-error :seon.program/not-found-error :seon.program/mutation-refused-error]]}
  [context subject]
  (retract-operation! context subject 'my.program/ns-unmap!
                      (list 'clojure.core/ns-unmap (list 'quote (symbol (namespace subject)))
                            (list 'quote (symbol (name subject))))))

(defn remove-ns!
  "Retract a namespace and its owned declarations, then remove it from SCI.

  Calls breaks first and writes the entire affected set in one transaction; a
  refusal names the surviving referrers; dispatch and macro callers are not
  modelled. A namespace owning
  schema declarations refuses: declaration-only deletion would leave its
  database attributes.

  Example:
  (my.program/remove-ns! 'my.scratch)"
  {:malli/schema [:=> [:cat :my.program/context :symbol]
                  [:or :seon.program/change :seon.program/read-refused-error :seon.program/not-found-error :seon.program/mutation-refused-error]]}
  [context namespace-name]
  (retract-operation! context namespace-name 'my.program/remove-ns!
                      (list 'clojure.core/remove-ns (list 'quote namespace-name))))

(defn ns-unalias!
  "Retract an alias component before removing the alias from fork and base.

  Calls breaks first. Namespace referrers conservatively refuse removal:
  indexed calls do not retain which alias spelling resolved their targets.
  Until seam B lands this refusal comes from breaks alone; dispatch and macro
  callers are not modelled.
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

;;; Reading a declaration: source, search and sizes return small data maps.

(defn- declaration
  "The program entity for sym, from the function family or the test family; empty when neither names it."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol] :map]}
  [database sym]
  (or (some (fn [[id-key source-key kind]]
              (when-let [entity (db/pull database [id-key source-key :seon.fn/doc :seon.fn/spec :seon.fn/form-span
                                                   {:seon.fn/file [:seon.fn.file/relative-path]}]
                                         [id-key sym])]
                (assoc entity ::kind kind ::source (get entity source-key))))
            [[:seon.fn/sym :seon.fn/source :function] [:seon.test/sym :seon.test/source :test]])
      {}))

(defn- file-line
  "The 1-based start line of a declaration on disk, as {:line n}; empty when the file no longer holds its indexed source."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{path :seon.fn.file/relative-path} :seon.fn/file [start end] :seon.fn/form-span source ::source}]
  (let [file (when (and path start) (java.io.File. ^String (System/getProperty "user.dir") ^String path))]
    (if (and file (.isFile file))
      (let [content (java.nio.file.Files/readAllBytes (.toPath file))]
        (if (and (<= end (alength content))
                 (= source (String. content (int start) (int (- end start)) java.nio.charset.StandardCharsets/UTF_8)))
          {:line (inc (count (filter #(= 10 %) (take start content))))}
          {}))
      {})))

(defn- nearest
  "Up to five declared symbols closest to sym by edit distance of their names, the same namespace before others."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol] [:vector :qualified-symbol]]}
  [database sym]
  (let [ns-part (namespace sym) name-part (name sym)]
    (->> (concat (db/q database '[:find [?s ...] :where [?e :seon.fn/sym ?s]])
                 (db/q database '[:find [?s ...] :where [?e :seon.test/sym ?s]]))
         ;; each distance once, never per comparison
         (map (fn [s] [[(malli.error/levenshtein (name s) name-part) (not= ns-part (namespace s)) (str s)] s]))
         (sort-by first)
         (take 5) (mapv second))))

(defn source
  "Read one declaration's source, docstring, contract and location, for a function or a test.

  Example:
  (my.program/source 'seon.task/create!)
  Errors: an unknown symbol returns a not-found error with the nearest symbols under :seon.error/expected."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol]
                  [:or :my.program/declaration-source :seon.program/not-found-error]]}
  [database sym]
  (let [entity (declaration database sym)]
    (if (::kind entity)
      (let [src (::source entity)]
        (cond-> (merge {:sym sym :kind (::kind entity) :source src} (file-line entity))
          (and (:seon.fn/doc entity)
               (not (clojure.string/includes? (str src) (str (:seon.fn/doc entity)))))
          (assoc :doc (:seon.fn/doc entity))
          (:seon.fn/spec entity) (assoc :contract (read-string (:seon.fn/spec entity)))
          (:seon.fn/file entity) (assoc :file (:seon.fn.file/relative-path (:seon.fn/file entity)))))
      (seon.error.refusal/diagnostic (java.util.Date.) :seon.program/read 'my.program/source
        {:seon.error/message "No program declaration names that symbol."
         :seon.program/not-found sym
         :seon.error/expected (nearest database sym)}))))

(defn render-source-ai
  "A declaration's source as its own text: the code an agent can copy into a `defn`
  unchanged, under a one-line location comment. The reply's other keys stay on the
  live result."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [{:keys [source file line]} (:seon.render/value unit)]
    (if file (str ";; " file ":" line "\n" source) source)))

(defn- sources
  "Every program declaration's [sym source kind], functions before tests."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector [:tuple :qualified-symbol :string :keyword]]]}
  [database]
  (into (mapv #(conj % :function) (db/q database '[:find ?s ?src :where [?e :seon.fn/sym ?s] [?e :seon.fn/source ?src]]))
        (map #(conj % :test) (db/q database '[:find ?s ?src :where [?e :seon.test/sym ?s] [?e :seon.test/source ?src]]))))

(defn- match-row
  "A declaration's first matching source line for needle, as a map; empty when it has none."
  {:malli/schema [:=> [:cat :string :qualified-symbol :string :keyword] :map]}
  [needle sym text kind]
  (let [hits (keep-indexed (fn [i line] (when (str/includes? line needle) [i line]))
                           (str/split-lines text))]
    (if-let [[i line] (first hits)]
      {:sym sym :line (inc i) :hits (count hits) :test? (= :test kind)
       :snippet (let [t (str/trim line)] (if (< 100 (count t)) (str (subs t 0 97) "...") t))
       :named? (str/includes? (str sym) needle)}
      {})))

(defn search
  "Search program source for a string or keyword; ranked matches, one per declaration.

  Ranked: name matches, then functions before tests, then most hits. The query is the text, or
  {:text text :limit n}. :line counts from the declaration's first line (my.program/source gives its file line).
  Example:
  (my.program/search :seon.render/form)
  (my.program/search {:text \"java.util.Date.\" :limit 25})
  Costs: scans every indexed source once; a few hundred ms."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:or :string :keyword [:map [:text [:or :string :keyword]] [:limit {:optional true} :int]]]]
                  :map]}
  [database query]
  (let [{:keys [text limit] :or {limit 8}} (if (map? query) query {:text query})
        needle (if (keyword? text) (str text) text)
        rows (->> (sources database)
                  (keep (fn [[sym src kind]] (not-empty (match-row needle sym src kind))))
                  (sort-by (juxt #(not (:named? %)) :test? #(- (:hits %)) #(str (:sym %)))))]
    (cond-> {:count (count rows)
             :matches (mapv #(select-keys % [:sym :line :snippet]) (take limit rows))}
      (< limit (count rows)) (assoc :more (list 'my.program/search {:text text :limit (* 3 limit)})))))

(defn sizes
  "The functions and tests of a namespace with their line counts, largest first (top 20).

  Example:
  (my.program/sizes 'seon.task)"
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol] :map]}
  [database ns-sym]
  (let [rows (->> (concat (db/q database '[:find ?s ?src :in $ ?n :where [?ns :seon.ns/name ?n] [?e :seon.fn/ns ?ns]
                                           [?e :seon.fn/sym ?s] [?e :seon.fn/source ?src]] ns-sym)
                          (db/q database '[:find ?s ?src :in $ ?n :where [?ns :seon.ns/name ?n] [?e :seon.test/ns ?ns]
                                           [?e :seon.test/sym ?s] [?e :seon.test/source ?src]] ns-sym))
                  (map (fn [[sym src]] [sym (count (str/split-lines src))]))
                  (sort-by (juxt #(- (second %)) #(str (first %)))))]
    {:ns ns-sym :count (count rows) :lines (reduce + (map second rows))
     :largest (vec (take 20 rows))}))

;;; Reading the branch's difference from its cluster.

(def ^:private identity-kinds
  "The declaration families a program diff compares, by identity attribute."
  {:seon.fn/sym :function :seon.test/sym :test :seon.ns/name :namespace})

(defn- diff-entries
  "One entry per identity: symbol, kind, the head's digest before and the branch's after, and the dig form for a changed one."
  {:malli/schema [:=> [:cat :seon.program/identity-set :map :map :boolean] [:vector :map]]}
  [identities before after dig?]
  (->> identities
       (mapv (fn [identity]
               (cond-> {:sym (second identity) :kind (identity-kinds (first identity))}
                 (before identity) (assoc :digest-before (before identity))
                 (after identity) (assoc :digest-after (after identity))
                 (and dig? (not= :namespace (identity-kinds (first identity))))
                 (assoc :dig (list 'my.program/declaration-diff (second identity))))))
       (sort-by (comp str :sym))
       vec))

(defn- diff-value
  "The branch's difference from the cluster head as {:added :changed :removed}, each entry data."
  {:malli/schema [:=> [:cat :map] :map]}
  [{[_ branch head] :maps {:seon.program/keys [added changed-on-branch retracted conflict]} :three}]
  (cond-> {:added (diff-entries added {} branch false)
           :changed (diff-entries changed-on-branch head branch true)
           :removed (diff-entries retracted head {} false)}
    (seq conflict) (assoc :conflicts (diff-entries conflict head branch true))))

(defn- declaration-pair
  "A declaration's source on the cluster head and on the branch, as {:sym :kind :before :after}."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :qualified-symbol] :map]}
  [head branch sym]
  (let [read (fn [database] (declaration database sym))
        [before after] (map read [head branch])]
    (if (or (::kind before) (::kind after))
      (cond-> {:sym sym :kind (or (::kind after) (::kind before))}
        (::source before) (assoc :before (::source before))
        (::source after) (assoc :after (::source after)))
      (seon.error.refusal/diagnostic (java.util.Date.) :seon.program/read 'my.program/diff
        {:seon.error/message (str "Neither the cluster head nor your branch declares " sym ".")
         :seon.program/not-found sym}))))

(defn- head-comparison
  "The branch compared with the cluster head as {:head :compared}, plus :answer when the comparison already decides the reply (a refusal, or the empty difference on the cluster's own branch). The caller releases it with `release-comparison!`."
  {:malli/schema [:=> [:cat :my.program/context] :map]}
  [{connection :seon.db/connection store :seon.store/store cluster :seon.cluster/name}]
  (let [held (get-in (db/db connection) [:config :branch])
        home (cluster.branch/cluster-branch cluster)
        head-id (registry/branch-commit-id {:seon.store/store store :seon.store/branch home})
        head (when-not (:seon.error/at head-id) (source/commit-database store head-id))
        compared (if head (source/compare-branch store held head home) {:c-id head-id})
        refusal (some #(when (:seon.error/at %) %) [(:c-id compared) (:changed compared) (first (:maps compared))])]
    (cond-> {:head head :head-id head-id :compared compared}
      refusal (assoc :answer refusal)
      (and (not refusal) (= held home)) (assoc :answer {:added [] :changed [] :removed []})
      (and (not refusal) (not= held home) (nil? (:three compared)))
      (assoc :answer (some #(when (:seon.error/at %) %) (:maps compared))))))

(defn- release-comparison!
  "Release the databases a head comparison acquired."
  {:malli/schema [:=> [:cat :map] :nil]}
  [{:keys [head compared]}]
  (source/release! [head (:base compared) (:branch compared)])
  nil)

(defn diff
  "Your branch's program difference from your cluster's head, as small data:
  {:added [...] :changed [...] :removed [...]}. An entry is {:sym :kind
  :digest-before :digest-after}, a changed one with the form that reads it
  (`my.program/declaration-diff`). The same comparison merge takes
  (`seon.cluster.source/compare-branch`).

  Example:
  (my.program/diff)
  Edge cases: holding the cluster's own branch answers empty.
  Costs: one fork-point read and digests of the changed declarations only."
  {:malli/schema [:=> [:cat :my.program/context] [:or :map :seon.error/value]]}
  [context]
  (let [comparison (head-comparison context)]
    (try (or (:answer comparison) (diff-value (:compared comparison)))
         (finally (release-comparison! comparison)))))

(defn write-branch!
  "Write your branch's declarations into their source files under `root`
  without a merge (`seon.cluster.source/write-back!`): the changed, added and
  removed declarations of `my.program/diff`, or the `:seon.program/identities`
  you pass. Proven equivalent before an atomic install; answers the written
  paths and digests. `:seon.fn/root` is the checkout's absolute path
  (`seon.fs/checkout-root?`): a relative path or one inside `data/source`
  is refused (`:root-refused`) and nothing is written.

  Example:
  (my.program/write-branch! {:seon.fn/root \"/Users/me/src/project\"})
  Edge cases: holding the cluster's own branch answers the empty difference; nothing is written.
  Costs: one fork-point read (as `my.program/diff`) and one analysis per written file."
  {:malli/schema [:=> [:cat [:map [:my.program/context :my.program/context] [:seon.fn/root :seon.fn/root]
                             [:seon.program/identities {:optional true} :seon.program/identity-set]]]
                  [:or :seon.source/written :seon.source/write-back-error :seon.error/value]]}
  [{context :my.program/context root :seon.fn/root given :seon.program/identities}]
  (let [comparison (head-comparison context)
        {:keys [head-id compared]} comparison
        {:seon.program/keys [added changed-on-branch retracted]} (:three compared)
        identities (or given (into #{} cat [added changed-on-branch retracted]))]
    (try (cond (:seon.error/at (:answer comparison)) (:answer comparison)
               (:answer comparison)
               (seon.error.refusal/diagnostic (java.util.Date.) :seon.program/read 'my.program/write-branch!
                 {:seon.error/message "Your branch is the cluster's own: there is nothing to write."})
               :else
               (source/write-back!
                 {:seon.db/connection (:seon.db/connection context) :seon.fn/root root
                  :seon.source/accepted {:seon.program/identities identities
                                         :seon.source/base-commit-id head-id
                                         :seon.source/commit-id (:c-id compared)}}))
         (finally (release-comparison! comparison)))))

(defn declaration-diff
  "One declaration's source on the cluster head (:before) and on your branch (:after).

  Example:
  (my.program/declaration-diff 'my.program/source)
  Errors: a symbol neither the head nor your branch declares returns a not-found error.
  Edge cases: holding the cluster's own branch answers the empty difference.
  Costs: as `my.program/diff`."
  {:malli/schema [:=> [:cat :my.program/context :qualified-symbol] [:or :map :seon.error/value]]}
  [context sym]
  (let [comparison (head-comparison context)]
    (try (or (:answer comparison)
             (declaration-pair (:head comparison) (:branch (:compared comparison)) sym))
         (finally (release-comparison! comparison)))))
