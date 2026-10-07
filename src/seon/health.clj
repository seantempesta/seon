(ns seon.health
  "Everything wrong with one cluster now, ranked, in one value.

  THE REPORT IS ITS OWNERS' VALUES PLUS WHAT MATCHES. Each unit is an owner's
  value, whole, under its own schema (problems, the cluster
  status, the process cache, every profile observation), so each
  keeps its owner's render pair. `:seon.health/findings` is one ranked vector
  of matches: a FINDING IS THE NAMED SCHEMA A VALUE SATISFIES (health plan
  §2b). Any registered schema declaring `:seon.health/severity` is a finding
  schema; its candidates are the values under the report path its
  `:seon.health/in` names, or, without one, the stored entities carrying its
  required attributes; a candidate is a match when it validates. Nothing
  stamps a kind, and a new finding schema is ranked the moment it is
  registered.

  RANKED BY DATA. `ranking` holds the half-life and the faulted severity;
  each schema declares its severity, the member that counts its impact, the
  member saying when it last happened, its subject and its clues. A score is
  severity × (1 + log2(1 + impact)) × 2^(−age/half-life).

  NEVER WAITS, NEVER STAMPS. The problems unit is read through
  `seon.cache/lookup-within`: past the request's deadline it is `:pending`
  with its requery, its one derivation keeps running (never cancelled), and
  its completion runs the request's wake. Each problems family and each
  stored finding query is retained by the revisions of the attributes its
  last derivation read (`derived`), so an unrelated commit reuses it.

  ## Costs

  Armed, default 2026-09-28, this code evaluated at the REPL: a warm report
  with an unchanged database 10.4-13.6 ms (median 11.0; status 3.9, 2.9 of it
  the current-source commit read; profile 3.6-5.7, 2.9 of it the cells vector;
  Flow ping 2.3, up to its 20 ms timeout while a proc is busy; findings 1.8).
  Seven problems families warm 0.2 ms, cold 2.0 s (failed-tests' 1,775
  captured index seeks 1.4 s of it, until the runner reads members in one
  query). A cold page read answers in 85-212 ms with problems pending; its
  wake followed at 1.9-2.8 s. The report writes nothing; its cache kinds weigh
  about 3.6 MB estimated (derived 3.2 MB)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [seon.cache :as cache]
            [seon.cluster.status :as status]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.fn :as fn]
            [seon.instrument :as instrument]
            [seon.operator.runtime :as operator.runtime]
            [seon.problems :as problems]
            [seon.profile :as profile]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]))

(def ranking
  "How findings rank, as data. A match's age halves its score every
  `:seon.health/half-life-hours`; a report with a match at or above
  `:seon.health/faulted-severity` is faulted."
  {:seon.health/half-life-hours 24
   :seon.health/faulted-severity 8})

;;; ---------------------------------------------------------------------------
;;; Retaining a derivation by what it read
;;; ---------------------------------------------------------------------------

(defn- read-attributes
  "The attributes `captured` reads depend on, or `:all` when one read's
  dependency is every attribute (a wildcard pull)."
  {:malli/schema [:=> [:cat [:vector :seon.db/captured-read]] [:or [:set :keyword] [:= :all]]]}
  [captured]
  (let [attributes (map (comp :datahike.read/attributes :datahike.read/revision) (db/read-evidence captured))]
    (if (some #{:all} attributes) :all (into (sorted-set) cat attributes))))

(defn- derivation-key
  "What a derivation named `derivation-name` read at `database`: the loaded program,
  and the revisions of `reads` (Datahike advances one per changed attribute,
  `datahike/db.cljc` `advance-cache-context`), or the commit when the
  attributes are unknown. Nil for an uncommitted value, which nothing retains."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/value [:or [:set :keyword] :keyword]]
                  [:maybe :seon.schema/value]]}
  [database derivation-name reads]
  (let [context (:cache-context database)]
    (when (:datahike.cache/committed? context)
      [derivation-name @sci.eval/loaded-identity
       (if (set? reads)
         [(:datahike.cache/connection-id context) (:datahike.cache/generation context)
          (select-keys (:datahike.cache/attribute-revisions context) reads)]
         (:datahike.cache/commit-id context))])))

(defn- weight
  "Estimated bytes of a derived value: a count it has, times a calibration."
  {:malli/schema [:=> [:cat :seon.schema/value] :int]}
  [value]
  (* 512 (inc (if (counted? value) (count value) 1))))

(defn derived
  "`(derivation database)`, retained under the revisions of the attributes its
  last derivation read, so a commit that changes none of them reuses it.

  The attributes are the derivation's own read evidence. A derivation reading
  different attributes on different data is correct: the retained value is
  keyed by what IT read, and while those attributes are unchanged a new
  derivation would read the same data and take the same path. `derivation-name`
  names it (a family key, a schema key).

  ## Costs

  Armed, default 2026-09-28: a hit 0.03 ms (seven problems families 0.2 ms);
  a miss costs the derivation plus its captured reads (0.8 ms per captured
  index seek)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/value [:fn clojure.core/ifn?]]
                  :seon.schema/value]}
  [database derivation-name derivation]
  (let [reads (cache/lookup ::reads derivation-name weight (constantly ::unknown) #(not= ::unknown %))
        k (derivation-key database derivation-name reads)]
    (if-not k
      (derivation database)
      (let [same-reads (volatile! true)]
        (cache/lookup
         ::derived k weight
         (fn []
           (let [sink (atom [])
                 value (binding [db/*read-evidence-sink* sink] (derivation database))
                 actual (read-attributes @sink)]
             (when (not= actual reads)
               ;; Retained only under the key of what it did read.
               (vreset! same-reads false)
               (cache/evict! ::reads derivation-name)
               ;; `:all` is kept too: such a derivation is retained per commit
               (cache/lookup ::reads derivation-name weight (constantly actual) some?)
               ;; Never the key being derived: looking it up here would wait on itself.
               (let [actual-key (derivation-key database derivation-name actual)]
                 (when (and actual-key (not= actual-key k))
                   (cache/lookup ::derived actual-key weight (constantly value)))))
             value))
         (fn [_] @same-reads))))))

;;; ---------------------------------------------------------------------------
;;; Finding schemas and their matches
;;; ---------------------------------------------------------------------------

(defn- finding-schemas
  "Every registered finding schema of `projection` with its declared
  properties: the forms whose properties declare `:seon.health/severity`."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:map-of :qualified-keyword :map]]}
  [projection]
  (schema/projection-cache-value
   projection ::finding-schemas
   #(into (sorted-map)
          (keep (fn [[k form]]
                  (let [properties (when (vector? form) (second form))]
                    (when (and (map? properties) (:seon.health/severity properties)) [k properties]))))
          (:seon.schema.projection/forms projection))))

(defn- candidates
  "Every map reachable from `value` through maps and collections, stopping at
  one `valid?` accepts: the matches."
  {:malli/schema [:=> [:cat :seon.schema/value [:fn clojure.core/ifn?]] [:vector :map]]}
  [value valid?]
  (loop [stack [value] found []]
    (if-let [node (peek stack)]
      (let [stack (pop stack)]
        (cond (and (map? node) (valid? node)) (recur stack (conj found node))
              (map? node) (recur (into stack (filter coll?) (vals node)) found)
              (coll? node) (recur (into stack (filter coll?) node) found)
              :else (recur stack found)))
      found)))

(defn- declared-keys
  "The keys `schema` declares, `{:required #{…} :optional #{…}}`: every entry
  of its maps, through `:and` and registered references."
  {:malli/schema [:=> [:cat :seon.schema/value]
                  [:map [:required [:set :qualified-keyword]] [:optional [:set :qualified-keyword]]]]}
  [schema]
  (let [resolved (m/deref-all schema)]
    (case (m/type resolved)
      :and (apply merge-with into {:required #{} :optional #{}} (map declared-keys (m/children resolved)))
      :map (reduce (fn [found [k properties]] (update found (if (:optional properties) :optional :required) conj k))
                   {:required #{} :optional #{}} (m/children resolved))
      {:required #{} :optional #{}})))

(defn- identity-only?
  "True when stored finding schema `k` constrains nothing beyond identity
  attributes: it would match every entity carrying them (`[:map [:seon.fn/sym
  …]]` matched all 5,789 functions, health model proof)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection :qualified-keyword] :boolean]}
  [database projection k]
  (let [{:keys [required optional]} (declared-keys (m/schema k {:registry (:seon.schema.projection/registry projection)}))]
    (and (empty? optional) (every? (set (db/identity-attributes database)) required))))

(defn refused-schemas
  "A refusal for each stored finding schema (no `:seon.health/in`) that
  constrains nothing beyond identity attributes: ranking every entity is no
  finding. Its matches are never derived."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :seon.error/value]]}
  [database]
  (let [projection (db/carried-projection database)]
    (into []
          (keep (fn [[k properties]]
                  (when (and (not (:seon.health/in properties)) (identity-only? database projection k))
                    (refusal/diagnostic {:seon.error/at (java.util.Date.) :seon.error/layer :seon.health/report
                                         :seon.error/operation `findings :seon.error/member :seon.health/severity
                                         :seon.error/offending k
                                         :seon.error/message (str "Finding schema " k " constrains only identity attributes, so it would match every entity carrying them.")
                                         :seon.error/fix "Constrain a member's value or absence (e.g. [:seon.fn/spec {:optional true} [:not :any]])."}))))
          (finding-schemas projection))))

(defn- stored-matches
  "The stored entities satisfying finding schema `k`: those carrying its
  required attributes, pulled by every declared attribute (an optional one may
  be constrained absent) and validated. Retained by the revisions of the
  attributes read.

  ## Costs

  Proportional to the candidates, not the matches: over-bound over stored
  measurements 10.7 ms for 1,171 pulled and 355 valid, where a Datalog
  pushdown took 3.4 ms (health model proof, 2026-09-28)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection :qualified-keyword]
                  [:or [:vector :map] :seon.db/error-result]]}
  [database projection k]
  (derived
   database k
   (fn [database]
     (let [{:keys [required optional]} (declared-keys (m/schema k {:registry (:seon.schema.projection/registry projection)}))
           entities (db/q {:find '[[?e ...]] :where (mapv (fn [a] ['?e a]) (sort required))} database)]
       (if (db/read-refusal? entities)
         entities
         (let [pulled (db/pull-many database (vec (sort (into required optional))) entities)]
           (if (db/read-refusal? pulled)
             pulled
             (filterv (schema/projection-validator projection k) pulled))))))))

(defn- score
  "`ranking`'s score of one match: severity × (1 + log2(1 + impact)) ×
  2^(−age/half-life), times ten, rounded."
  {:malli/schema [:=> [:cat :map :inst :map :map] :seon.health/score]}
  [weights now properties match]
  (let [impact (or (some-> (:seon.health/impact properties) match) 0)
        at (some-> (:seon.health/at properties) match)
        age-hours (if (inst? at) (max 0 (/ (- (inst-ms now) (inst-ms at)) 3.6e6)) 0)]
    (Math/round (* 10 (:seon.health/severity properties)
                   (+ 1 (/ (Math/log (+ 1 (max 0 (double impact)))) (Math/log 2)))
                   (Math/pow 2 (- (/ age-hours (:seon.health/half-life-hours weights))))))))

(defn- fold-callers
  "For inclusive schemas, drop each match whose subject statically calls
  another match's subject of the same schema, naming it in that match's
  `:seon.health/callers`: the callee's measure is inside the caller's."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.health/finding] [:set :qualified-keyword]]
                  [:vector :seon.health/finding]]}
  [database findings inclusive]
  (let [subjects (fn [k] (into #{} (comp (filter #(= k (:seon.health/schema %))) (map :seon.health/subject)
                                         (filter qualified-symbol?))
                               findings))
        by-schema (into {} (map (juxt identity subjects)) inclusive)
        all (vec (sort (into #{} cat (vals by-schema))))
        edges (if (seq all)
                (derived database [::calls all]
                         #(db/q '[:find ?caller ?callee :in $ [?caller ...]
                                  :where [?f :seon.fn/sym ?caller] [?f :seon.fn/calls ?callee]] % all))
                #{})
        edges (if (set? edges) edges #{})
        folded (into {} (map (fn [[k members]]
                               [k (filter (fn [[caller callee]] (and (members caller) (members callee) (not= caller callee)))
                                          edges)]))
                     by-schema)]
    (into []
          (keep (fn [{k :seon.health/schema subject :seon.health/subject :as finding}]
                  (let [edges (get folded k)
                        callers (into (sorted-set) (keep (fn [[caller callee]] (when (= callee subject) caller))) edges)]
                    (cond (some (fn [[caller _]] (= caller subject)) edges) nil
                          (seq callers) (assoc finding :seon.health/callers callers)
                          :else finding))))
          findings)))

(defn- with-tasks
  "Each finding with the tasks whose must-reads name its subject."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.health/finding]] [:vector :seon.health/finding]]}
  [database findings]
  (let [symbol-of #(cond (qualified-symbol? %) % (qualified-keyword? %) (symbol (namespace %) (name %)))
        subjects (vec (sort (into #{} (keep (comp symbol-of :seon.health/subject)) findings)))
        rows (if (seq subjects)
               (derived database [::tasks subjects]
                        #(db/q '[:find ?s ?h :in $ [?s ...] :where [?t :seon.task/must-read ?s] [?t :seon.task/id ?h]]
                               % subjects))
               #{})
        tasks (reduce (fn [found [s h]] (update found s (fnil conj (sorted-set)) h)) {} (if (set? rows) rows #{}))]
    (mapv #(if-let [handles (tasks (symbol-of (:seon.health/subject %)))] (assoc % :seon.health/tasks handles) %)
          findings)))

(defn findings
  "The ranked matches of every finding schema over `report`'s units and, for
  schemas naming no report path, over the stored entities of `database`.

  ## Examples

      (seon.health/findings (seon.db/db) (seon.health/report {:seon.boot/cluster-name \"default\"}))"
  {:malli/schema [:=> [:cat :seon.db/database-value :map] [:vector :seon.health/finding]]}
  [database report]
  (let [projection (db/carried-projection database)
        now (java.util.Date.)
        schemas (finding-schemas projection)
        matches (for [[k properties] schemas
                      :when (or (:seon.health/in properties) (not (identity-only? database projection k)))
                      :let [found (if-let [path (:seon.health/in properties)]
                                    (candidates (get-in report path) (schema/projection-validator projection k))
                                    (stored-matches database projection k))]
                      :when (vector? found)
                      match found]
                  (cond-> {:seon.health/schema k
                           :seon.health/score (score ranking now properties match)
                           :seon.health/value match}
                    (some? (some-> (:seon.health/subject properties) match))
                    (assoc :seon.health/subject ((:seon.health/subject properties) match))))
        inclusive (into #{} (keep (fn [[k properties]] (when (:seon.health/inclusive properties) k))) schemas)]
    (->> (fold-callers database (vec matches) inclusive)
         (with-tasks database)
         (sort-by (juxt (comp - :seon.health/score) (comp str :seon.health/subject)))
         vec)))

;;; ---------------------------------------------------------------------------
;;; The report
;;; ---------------------------------------------------------------------------

(defn- collected
  "`(collect)`, or the one error value of its failure: one unit failing never
  blinds the others. Health writes nothing, not even this fault (owner: health
  is never a drain); the error value is delivered in the report, and an entrant
  that sees an escaping throw records its own."
  {:malli/schema [:=> [:cat :qualified-keyword [:fn clojure.core/ifn?]] :seon.schema/value]}
  [unit collect]
  (try (collect)
       (catch Throwable failure
         (refusal/diagnostic {:seon.error/at (java.util.Date.) :seon.error/layer :seon.health/report
                              :seon.error/operation `report :seon.error/member unit :seon.error/throwable failure}))))

(defn- program-function-files
  "Symbol and relative source path of every `defn`/`defn-` row in `database`:
  the Vars whose root compiles as their namespace's own class, what
  `seon.instrument/replaced-roots` compares."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or [:vector [:tuple :qualified-symbol :string]] :seon.db/error-result]]}
  [database]
  (let [rows (db/q '[:find ?sym ?path
                     :in $ [?definer ...]
                     :where [?function :seon.fn/defined-by ?definer]
                            [?function :seon.fn/sym ?sym]
                            [?function :seon.fn/file ?file]
                            [?file :seon.fn.file/relative-path ?path]]
                   database '[clojure.core/defn clojure.core/defn-])]
    (if (db/read-refusal? rows) rows (vec rows))))

(defn- replaced-roots
  "The loaded first-party Vars whose root is not their namespace's compile.
  Retained until a Var root changes (`clojure.lang.Var/rev` advances on every
  root binding, `Var.java` bindRoot/alterRoot) or the program's files do."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :seon.instrument/replaced-root]]}
  [database]
  (let [files (derived database ::function-files program-function-files)]
    (if (db/read-refusal? files)
      []
      (cache/lookup ::replaced-roots [clojure.lang.Var/rev files] weight
                    #(vec (instrument/replaced-roots files))))))

(defn- untested
  "The public functions no test reaches (`seon.fn/functions-without-tests`),
  retained by the revisions of the call-graph attributes it reads: 919 ms cold
  (health model proof), so once per program version, never per report."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:set :qualified-symbol]]}
  [database]
  (set (derived database ::untested fn/functions-without-tests)))

(defn- profile-unit
  "Observations of the armed definitions with at least one second of
  inclusive time (every over-bound match needs that much), each saying whether
  a test reaches it once that set is derived: the set is read within the
  deadline, and while it is still deriving an observation says nothing about
  tests (unknown, never untested) and the completion runs `wake`. Read live (the counters are the measurement; any key over
  them moves with this very read), a cell's one total counter first, so only
  candidates pay for an observation."
  {:malli/schema [:=> [:cat :seon.db/database-value [:int {:min 0}] [:maybe [:fn clojure.core/ifn?]]]
                  [:vector :seon.profile/observation]]}
  [database deadline-ms wake]
  (let [committed (db/committed-value-identity database)
        unreached (if committed
                    (cache/lookup-within ::untested committed weight #(untested database) deadline-ms wake)
                    (untested database))
        known? (set? unreached)]
    (into [] (comp (filter #(<= 1000000000 (.sum ^java.util.concurrent.atomic.LongAdder (:seon.profile/total-counter %))))
                   (map profile/observation)
                   (map #(cond-> % known? (assoc :seon.health/tested? (not (contains? unreached (:seon.profile/sym %)))))))
          (profile/cells))))

(defn- problems-unit
  "The problems value at `database`, each family retained by what it read;
  `:pending` with its requery past `deadline-ms`, waking `wake` on completion."
  {:malli/schema [:=> [:cat :seon.db/database-value [:int {:min 0}] [:maybe [:fn clojure.core/ifn?]]]
                  :seon.health/problems]}
  [database deadline-ms wake]
  (let [assemble #(problems/problems database {} (fn [family derivation] (derived database family derivation)))
        committed (db/committed-value-identity database)
        value (if committed
                (cache/lookup-within ::problems committed weight assemble deadline-ms wake)
                (assemble))]
    (if-let [since (:seon.cache/since value)]
      {:seon.health/unit :seon.health/problems :seon.health/since since
       :seon.health/requery '(seon.problems/problems (seon.db/db) {})}
      value)))

;;; ---------------------------------------------------------------------------
;;; What regressed: newest stored value beside the value before its change
;;; ---------------------------------------------------------------------------

(defn- phase-costs
  "Each layer of a boot `timeline` (`#{[layer at-ms]}`, cumulative) with its
  own cost, the milliseconds since the layer before it in `order`, and the
  whole timeline as `:seon.boot/layers`."
  {:malli/schema [:=> [:cat [:set [:tuple :qualified-keyword [:int {:min 0}]]] [:vector :qualified-keyword]]
                  [:map-of :qualified-keyword [:int {:min 0}]]]}
  [timeline order]
  (let [rank (zipmap order (range))
        ordered (sort-by (fn [[layer ms]] [ms (rank layer (count order))]) timeline)]
    (into {:seon.boot/layers (reduce max 0 (map second timeline))}
          (map (fn [[before [layer ms]]] [layer (max 0 (- ms (if before (second before) 0)))]))
          (map vector (cons nil ordered) ordered))))

(defn boot-comparisons
  "Each boot phase of the newest process record in `records` beside the same
  phase of the newest record of the previous distinct program (source commit):
  empty with fewer than two programs recorded."
  {:malli/schema [:=> [:cat [:sequential [:map {:description "A pulled process record: its layers arrive as a vector of [layer at-ms]."}
                                           [:seon.db.process/id :seon.db.process/id]]]
                       [:vector :qualified-keyword]]
                  [:vector :seon.health/boot-comparison]]}
  [records order]
  (let [recorded (sort-by :seon.db.process/start-instant
                          (filter #(and (:seon.db.process/start-instant %) (:seon.source/commit-id %) (seq (:seon.db.process/layers %)))
                                  records))
        after (last recorded)
        before (last (remove #(= (:seon.source/commit-id %) (:seon.source/commit-id after)) recorded))]
    (if-not (and after before)
      []
      (let [was (phase-costs (set (:seon.db.process/layers before)) order)
            now (phase-costs (set (:seon.db.process/layers after)) order)]
        (into []
              (keep (fn [[phase after-ms]]
                      (when-some [before-ms (get was phase)]
                        {:seon.health/phase phase
                         :seon.health/before-ms before-ms
                         :seon.health/after-ms after-ms
                         :seon.health/increase-ms (- after-ms before-ms)
                         :seon.health/increase-percent (quot (* 100 (- after-ms before-ms)) (max 1 before-ms))
                         :seon.health/before-program (:seon.source/commit-id before)
                         :seon.health/after-program (:seon.source/commit-id after)
                         :seon.health/before-process (:seon.db.process/id before)
                         :seon.health/after-process (:seon.db.process/id after)
                         :seon.health/changed-at (:seon.db.process/start-instant after)})))
              (sort-by key now))))))

(defn definition-comparisons
  "For each symbol measured under more than one digest in `measurements`, its
  newest digest's median bucket beside its previous digest's, and the time the
  move adds over the newest digest's calls (the tail is not compared: rare
  heavy calls dominate it). A digest's value is its newest measurement;
  digests order by it."
  {:malli/schema [:=> [:cat [:sequential :seon.health/measured]]
                  [:vector :seon.health/definition-comparison]]}
  [measurements]
  (into []
        (mapcat (fn [[sym rows]]
                  (let [by-digest (->> rows
                                       (group-by :seon.profile.measurement/digest)
                                       vals
                                       (map #(apply max-key (comp inst-ms :seon.profile.measurement/at) %))
                                       (sort-by (comp inst-ms :seon.profile.measurement/at)))
                        [before after] (take-last 2 by-digest)]
                    (when after
                      [(let [before-us (quot (bit-shift-left 1 (:seon.profile.measurement/p50 before)) 1000)
                             after-us (quot (bit-shift-left 1 (:seon.profile.measurement/p50 after)) 1000)
                             calls (:seon.profile.measurement/calls after)]
                         {:seon.profile.measurement/sym sym
                          :seon.health/before-bucket (:seon.profile.measurement/p50 before)
                          :seon.health/after-bucket (:seon.profile.measurement/p50 after)
                          :seon.health/buckets-up (- (:seon.profile.measurement/p50 after) (:seon.profile.measurement/p50 before))
                          :seon.health/before-us before-us
                          :seon.health/after-us after-us
                          :seon.health/added-ms (quot (* (- after-us before-us) calls) 1000)
                          :seon.health/before-calls (:seon.profile.measurement/calls before)
                          :seon.health/before-digest (:seon.profile.measurement/digest before)
                          :seon.health/after-digest (:seon.profile.measurement/digest after)
                          :seon.profile.measurement/calls calls
                          :seon.health/changed-at (:seon.profile.measurement/at after)})]))))
        (sort-by key (group-by :seon.profile.measurement/sym measurements))))

(defn- stored
  "Every entity of `database` carrying `identity`, pulled by `attributes`:
  empty while `identity` or the first attribute is not installed (a store
  that has not adopted the producer's schema yet)."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword [:vector :qualified-keyword]]
                  [:or [:vector :map] :seon.db/error-result]]}
  [database identity-attribute attributes]
  (if-not (every? #(get (:schema database) %) [identity-attribute (first attributes)])
    []
    (let [pulled (db/q {:find [[(list 'pull '?e (into [identity-attribute] attributes)) '...]]
                        :where [['?e (first attributes)]]} database)]
      (if (db/read-refusal? pulled) pulled (vec pulled)))))

(defn regressions
  "Every comparison of a stored measure's newest value with its value before
  the change that moved it: boot phases from the process records boot writes
  at seeding (`boot-comparisons`), definition buckets from the stored
  measurements (`definition-comparisons`). A regression is the finding schema
  a comparison satisfies (`:seon.health/boot-regression`,
  `:seon.health/definition-regression`); nothing is stored.

  ## Examples

      (seon.health/regressions (seon.db/db))

  ## Costs

  Proportional to the process records and the measurements stored: one
  query with a pull expression each."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.health/regressions]}
  [database]
  (let [records (stored database :seon.db.process/id
                        [:seon.db.process/layers :seon.db.process/start-instant :seon.source/commit-id])
        measurements (stored database :seon.profile.measurement/id
                             [:seon.profile.measurement/sym :seon.profile.measurement/digest
                              :seon.profile.measurement/p50
                              :seon.profile.measurement/errors :seon.profile.measurement/calls
                              :seon.profile.measurement/at])]
    (cond (db/read-refusal? records) records
          (db/read-refusal? measurements) measurements
          :else (into (boot-comparisons records @(requiring-resolve 'seon.cluster.boot/boot-layers))
                      (definition-comparisons measurements)))))

(defn- state
  "The report's one word: faulted at the ranking's faulted severity; unknown
  while a unit is pending or an error; degraded with any other match."
  {:malli/schema [:=> [:cat :seon.schema/projection :map [:vector :seon.health/finding]] :seon.health/state]}
  [projection report found]
  (let [schemas (finding-schemas projection)
        unread? (some #(or (:seon.health/unit %) (:seon.error/at %))
                      (map report [:seon.health/problems :seon.health/status]))]
    (cond (some #(<= (:seon.health/faulted-severity ranking)
                     (get-in schemas [(:seon.health/schema %) :seon.health/severity] 0)) found) :faulted
          unread? :unknown
          (seq found) :degraded
          :else :observed)))

(defn report
  "Everything wrong with cluster `:seon.boot/cluster-name` now: each owner's
  value whole, and every finding schema's matches ranked.

  Reads the running instance from the process roster and one database value
  (the request's, else the cluster's current one). The problems unit waits at
  most `:seon.health/deadline-ms` (default 900: under the one-second bound),
  then shows `:pending` and runs `:seon.health/wake` when it completes. Never
  throws: a unit that fails is its error value, recorded as a fault.

  ## Examples

      (seon.health/report {:seon.boot/cluster-name \"default\"})

  ## Costs

  See the namespace: warm, the Flow ping and the profile observations
  dominate (about 15 ms)."
  {:malli/schema [:=> [:cat :seon.health/request] [:or :seon.health/report :seon.health/missing-cluster-error]]}
  [{cluster-name :seon.boot/cluster-name deadline-ms :seon.health/deadline-ms wake :seon.health/wake :as request}]
  (let [instance (get @operator.runtime/running-instances cluster-name)
        connection (:seon.boot/cluster-connection instance)
        database (or (:seon.db/db request) (when connection (db/db connection)))
        ctx (:seon.sci.eval/ctx instance)]
    (if (or (nil? instance) (nil? database) (db/read-refusal? database))
      (refusal/diagnostic {:seon.error/at (java.util.Date.) :seon.error/layer :seon.health/report
                           :seon.error/operation `report :seon.health/cluster cluster-name
                           :seon.error/message "No running cluster named :seon.health/cluster has a readable database in this JVM."
                           :seon.error/fix "Name a cluster bin/seon status lists."})
      (let [loaded @sci.eval/loaded-identity
            units (cond-> {:seon.health/cluster cluster-name
                           :seon.health/observed-at (java.util.Date.)
                           :seon.health/basis (cond-> {:seon.db/basis-t (db/basis-t database)}
                                                (db/committed-value-identity database)
                                                (assoc :datahike/commit-id (:datahike.value/commit-id (db/committed-value-identity database))))
                           :seon.health/readiness (collected :seon.health/readiness
                                                             #((requiring-resolve 'seon.cluster.boot/readiness) instance))
                           :seon.health/problems (collected :seon.health/problems
                                                            #(problems-unit database (or deadline-ms 900) wake))
                           :seon.health/status (collected :seon.health/status
                                                          #(status/snapshot {:seon.db/db database :seon.db/connection connection
                                                                             :seon.sci.eval/ctx ctx}))
                           :seon.health/cache (cache/status)
                           :seon.health/profile (collected :seon.health/profile #(profile-unit database (or deadline-ms 900) wake))
                           :seon.health/replaced-roots (collected :seon.health/replaced-roots
                                                                  #(replaced-roots database))
                           :seon.health/regressions (collected :seon.health/regressions
                                                               #(derived database ::regressions regressions))}
                    (:seon.error/at loaded) (assoc :seon.health/program loaded))
            found (collected :seon.health/findings #(findings database units))
            found (if (vector? found) found [])]
        (assoc units
               :seon.health/refused (collected :seon.health/refused #(refused-schemas database))
               :seon.health/findings found
               :seon.health/state (state (db/carried-projection database) units found))))))

;;; ---------------------------------------------------------------------------
;;; The faces
;;; ---------------------------------------------------------------------------

(defn- clues
  "The members of `match` its finding schema's `:seon.health/clues` name."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [properties match]
  (into {} (keep (fn [path] (let [v (get-in match path)] (when (some? v) [(peek path) v]))))
        (:seon.health/clues properties)))

(defn- unit-summary
  "One unit as the AI face counts it: pending and error values say so."
  {:malli/schema [:=> [:cat :qualified-keyword :seon.schema/value] :seon.schema/value]}
  [k value]
  (cond
    (:seon.health/unit value) (select-keys value [:seon.health/since :seon.health/requery])
    (:seon.error/at value) (select-keys value [:seon.error/operation :seon.error/message])
    :else
    (case k
      :seon.health/readiness (select-keys value [:seon.boot/missing-layers :seon.boot/ready-ms :seon.render.web/url])
      :seon.health/problems (into (sorted-map) (map (fn [[family rows]] [family (count rows)])) value)
      :seon.health/status (select-keys value [:seon.cluster.status/heap-used :seon.cluster.status/heap-live :seon.cluster.status/heap-max
                                              :seon.cluster.status/open-turns :seon.cluster.status/platform-threads])
      :seon.health/cache (select-keys value [:seon.cache/max-bytes :seon.cache/weighted-bytes])
      :seon.health/profile {:seon.health/observed (count value)}
      :seon.health/replaced-roots {:seon.health/count (count value)}
      :seon.health/regressions {:seon.health/count (count value)}
      value)))

(def ^:private shown
  "How many findings the AI face shows with their clues: at the 1,000-token
  agent budget ten came to 841 tokens with every clue clipped (default,
  2026-09-28). The rest are counted and one read away."
  5)

(defn render-ai
  "`:seon.render/ai`: the state, each unit counted, the top findings with
  their clues and tasks, the count of each finding schema's matches, and the
  reads that dig further. Renders less; the whole report is one read away."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.health/view]}
  [{value :seon.render/value database :seon.db/db}]
  (if (:seon.error/at value)
    value
    (let [schemas (if database (finding-schemas (db/carried-projection database)) {})
          found (:seon.health/findings value)
          cluster (:seon.health/cluster value)]
      {:seon.health/cluster cluster
       :seon.health/state (:seon.health/state value)
       :seon.health/basis (:seon.health/basis value)
       :seon.health/top
       (mapv (fn [{k :seon.health/schema :as finding}]
               (cond-> (select-keys finding [:seon.health/schema :seon.health/subject :seon.health/score :seon.health/tasks])
                 (seq (clues (get schemas k) (:seon.health/value finding)))
                 (assoc :seon.health/clues (clues (get schemas k) (:seon.health/value finding)))
                 (:seon.health/callers finding)
                 (assoc :seon.health/callers (count (:seon.health/callers finding)))))
             (take shown found))
       :seon.health/counts (into (sorted-map) (frequencies (map :seon.health/schema found)))
       :seon.health/units (into (sorted-map)
                                (keep (fn [k] (when-some [v (get value k)] [k (unit-summary k v)])))
                                [:seon.health/readiness :seon.health/problems :seon.health/status
                                 :seon.health/cache :seon.health/profile
                                 :seon.health/replaced-roots :seon.health/regressions :seon.health/program])
       :seon.render/operations
       [(list 'seon.health/report {:seon.boot/cluster-name cluster})
        '(seon.problems/open-faults (seon.db/db))
        '(seon.cache/status)]})))

(defn- finding-item
  "One finding as a list item: score, subject and schema, then its clues and
  tasks on a second line, so it reads at any width."
  {:malli/schema [:=> [:cat :seon.db/database-value :map :seon.health/finding] :seon.render/hiccup]}
  [database schemas {k :seon.health/schema :as finding}]
  (let [shown-clues (clues (get schemas k) (:seon.health/value finding))]
    [:li {:class "seon-health-finding" :data-schema (str k)}
     [:div [:strong (str (:seon.health/score finding))] " "
      (render.value/html {:seon.render/value (:seon.health/subject finding) :seon.db/db database :seon.render.value/root [::subject k (:seon.health/subject finding)]}) " "
      [:span {:class "seon-health-schema"} (str k)]]
     (when (or (seq shown-clues) (:seon.health/tasks finding) (:seon.health/callers finding))
       [:div {:class "seon-health-clues"}
        (str/join " · " (concat (map (fn [[ck v]] (str (name ck) " " (render.value/text {:seon.render/value v :seon.db/db database :seon.render.value/root [::clue k ck]}))) shown-clues)
                                (when-let [callers (:seon.health/callers finding)]
                                  [(str "callers " (str/join ", " callers))])
                                (when-let [tasks (:seon.health/tasks finding)]
                                  [(str "tasks " (str/join " " tasks))])))])]))

(defn- unit-section
  "One unit through its owner's HTML face; a pending or failed unit says so."
  {:malli/schema [:=> [:cat :seon.db/database-value :string :seon.schema/value [:fn clojure.core/ifn?]] :seon.render/hiccup]}
  [database title value face]
  [:section {:class "seon-health-unit"}
   [:h2 title]
   (cond (:seon.health/unit value) [:p {:class "seon-health-pending"} "deriving… " (render.value/html {:seon.render/value (:seon.health/requery value) :seon.db/db database :seon.render.value/root [::requery title]})]
         (:seon.error/at value) [:p {:class "seon-health-error"} (:seon.error/message value)]
         :else (face value))])

(defn render-html
  "`:seon.render/html`: the state and basis, every finding ranked, then each
  unit through its owner's own HTML face; problems as family counts, since
  their entries are the findings."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [{value :seon.render/value database :seon.db/db}]
  (let [schemas (if database (finding-schemas (db/carried-projection database)) {})]
    [:article {:class "seon-health" :data-state (name (:seon.health/state value :unknown))}
     [:header [:h1 "Health " [:span {:class "seon-health-state"} (name (:seon.health/state value :unknown))]]
      (render.value/html {:seon.render/value (:seon.health/basis value) :seon.db/db database :seon.render.value/root [::basis]})]
     [:section {:class "seon-health-findings"}
      [:h2 (str (count (:seon.health/findings value)) " findings, worst first")]
      (into [:ol {:class "seon-health-list"}] (map (partial finding-item database schemas)) (:seon.health/findings value))]
     ;; Counts, not problems' own report: every family's entries are already
     ;; findings above, and that report renders each failed test through the
     ;; test render at 28 ms apiece (241 on default: 6.8 s, 2026-09-28).
     (unit-section database "Problems" (:seon.health/problems value)
                   (fn [found] (into [:dl] (map (fn [[family rows]] [:div [:dt (name family)] [:dd (str (count rows))]]))
                                     (sort-by key found))))
     (unit-section database "Cluster" (:seon.health/status value) #(status/render-html {:seon.render/value % :seon.db/db database}))
     (unit-section database "Cache" (:seon.health/cache value)
                   (fn [cache]
                     [:table [:thead [:tr [:th "kind"] [:th "MB"] [:th "bytes %"] [:th "hits %"] [:th "evictions"]]]
                      (into [:tbody]
                            (map (fn [[kind c]] [:tr [:td (str kind)] [:td (str (quot (:seon.cache/bytes c) 1000000))]
                                                 [:td (str (:seon.cache/byte-percent c))] [:td (str (:seon.cache/hit-percent c))]
                                                 [:td (str (:seon.cache/evictions c))]]))
                            (sort-by (comp - :seon.cache/bytes val) (:seon.cache/kinds cache)))]))]))
