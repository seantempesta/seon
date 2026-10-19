(ns seon.sci.open-derivers-test
  "The open `seon.cache` derivers: functions whose `:seon.fn/calls` hold `seon.cache/lookup` or
  `seon.cache/lookup-within` and that the program's reach index (`seon.sci.eval/reach-index`) leaves open, because
  they reach a call that resolves a name at run time. Their derivations cannot be keyed by the code they reach.
  The closed-runtime plan (`docs/research/agent-platform/2026-10-19/closed-runtime-hybrid.md`) lowers the set one
  phase at a time; each phase's commit edits `open-derivers` and adds its row to `converted`."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.sci.eval]
            [seon.sci.runtime :as runtime]))

(def ^:private open-derivers
  "The open cache derivers of the program, exactly: a name joining it is a new open derivation, a name leaving it is
  a phase's progress, and both edit this set in the commit that causes them."
  '#{seon.cluster.source/file-proof seon.cluster.source/member-contributions seon.cluster.source/members-of
     seon.cluster.source/publication seon.cluster.source/unmerged-count seon.context/conflicts seon.embed/corpus
     seon.health/problems-unit seon.health/profile-unit seon.health/replaced-roots seon.program/content-digest
     seon.program/own-changed-identities seon.sci.eval/derive-program-context seon.sci.eval/program-context
     seon.sci.eval/record-refusals-once! seon.sci.runtime-test/shaped-main seon.test.runner/current-results})

(def ^:private converted
  "Each landed phase's converted functions: none of them calls a run-time resolution
  (`seon.sci.eval/run-time-resolution`) directly any more."
  ;; P2's functions are deleted seams: built from names, never quoted as symbols, so the program keeps no reference
  ;; to a function that no longer exists (a deleted function with a surviving referrer refuses the deletion).
  [{:phase :P2
    :functions (into #{} (map #(symbol "seon.db" %))
                     ["program-definition-digest" "program-behavior-digest" "stored-namespace-context"
                      "program-shapes-in" "program-canonical-entity"])}
   {:phase :P3b
    :functions (into #{} (map #(symbol "seon.program" %)) ["derived-shape" "derived-program-attributes"])}
   {:phase :P1
    :functions (into #{} (map #(symbol "seon.db" %))
                     ["call-preparation-snapshot" "call-preparation-report-snapshot" "call-preparation-plan-for"])}
   {:phase :P3a-1
    :functions (into #{} (map #(symbol "seon.schema" %)) ["canonical-schema-entities"])}
   ;; `projection-builder` is deleted, so it is built from its name.
   {:phase :P5
    :functions (into #{} (map #(symbol "seon.db" %))
                     ["carried-projection" "content-projection" "connection-projection-state" "projection-builder"])}
   ;; P4b: the refusal reads the handed projection (`seon.schema.handed`); its seams and `seon.schema`'s
   ;; packaged-forms fallback are deleted, so those are built from their names.
   {:phase :P4b
    :functions (into (into #{} (map #(symbol "seon.error.refusal" %))
                           ["diagnostic" "references" "authored-namespace?" "error" "chain" "checking-projection"
                            "identity-projector" "operation-projection"])
                     (map #(symbol "seon.schema" %))
                     ["candidate-forms" "declaration-population" "packaged-forms"])}
   ;; P3a-2: seon.schema requires the bridge statically; its seven seams are deleted, so they are built from names.
   {:phase :P3a-2
    :functions (into (into #{} (map #(symbol "seon.schema" %))
                           ["assert-complete-contract!" "pulled-attribute-entry" "canonical-schema-entities"
                            "canonical-database-attributes"])
                     (map #(symbol "seon.schema" (str "schema-datahike-" %)))
                     ["storable-attribute-in?" "storable-properties-in" "assert-storable-schema!" "database-attributes-in"
                      "malli->datahike-attr-in" "storage-schema" "value-schema"])}
   ;; P7 (P6b folded, mechanism 2): the projection binds the loaded Var by interop, or a callable resolving it by name
   ;; at call; loading stays in admission (`seon.schema/runtime-predicate`, `core-predicate-registered?`).
   {:phase :P7
    :functions '#{seon.schema/loaded-predicate-var seon.schema/late-predicate seon.schema/unloaded-predicate-functions
                  seon.schema/identity-only-descriptors-in seon.schema/predicate-callable seon.schema/projection-bindings
                  seon.schema/projection-state-bindings seon.schema/predicate-bindings}}])

(defn- open-cache-derivers
  "The cache derivers of `database`'s program that its reach index leaves open."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:set :qualified-symbol]]}
  [database]
  (let [closed (::runtime/closed (@#'seon.sci.eval/reach-index database))]
    (into #{} (remove closed)
          (db/q '[:find [?sym ...] :in $ [?callee ...] :where [?f :seon.fn/calls ?callee] [?f :seon.fn/sym ?sym]]
                database '[seon.cache/lookup seon.cache/lookup-within]))))

(deftest open-cache-derivers-are-exactly-the-recorded-set
  (let [open (open-cache-derivers (db/db))]
    (is (= {:opened #{} :closed #{}}
           {:opened (set/difference open open-derivers) :closed (set/difference open-derivers open)})
        "a newly opened deriver is a regression; a newly closed one is recorded by removing it from open-derivers")))

(deftest converted-functions-resolve-no-name-at-run-time
  (let [database (db/db)
        opening @#'seon.sci.eval/run-time-resolution]
    (is (= [] (into [] (keep (fn [{:keys [phase functions]}]
                               (let [still (into (sorted-map)
                                                 (keep (fn [sym]
                                                         (let [calls (db/q '[:find [?callee ...] :in $ ?sym
                                                                             :where [?f :seon.fn/sym ?sym] [?f :seon.fn/calls ?callee]]
                                                                           database sym)]
                                                           (when-let [held (seq (filter opening calls))] [sym (set held)]))))
                                                 functions)]
                                 (when (seq still) {:phase phase :still-opening still}))))
                    converted)))))

(def ^:private handed-derivers
  "The cache derivers whose contract takes a function, exactly (`seon.sci.eval/handed-derivers`): code their callers
  hand them crosses their lookup, so each runs analysed whenever a change reaches its callers' code
  (`seon.sci.eval/coupling`). A name joining is a new deriver handed code, recorded here in the commit that adds it."
  '#{seon.db/write-entity-error seon.embed/index seon.embed/layered seon.embed/similar-now seon.health/derived
     seon.health/problems-unit seon.health/profile-unit seon.instrument/compiled-wrapper
     seon.schema/with-bound-predicates seon.sci.eval/record-refusals-once!})

(deftest handed-cache-derivers-follow-their-callers
  ;; Runtime review 3 (Opus N1): a compiled deriver handed a branch's derivation keyed it by main's identity.
  (let [database (db/db)
        projection (db/carried-projection database)
        derivers (map first (@#'seon.sci.eval/cache-derivers database))
        handed (@#'seon.sci.eval/handed-derivers projection derivers)
        leaders (::runtime/leaders (@#'seon.sci.eval/coupling database projection))
        unled (into (sorted-map)
                    (keep (fn [deriver]
                            (let [callers (db/q '[:find [?caller ...] :in $ ?d :where [?f :seon.fn/calls ?d] [?f :seon.fn/sym ?caller]]
                                                database deriver)
                                  missing (remove #(contains? (get leaders deriver) %) callers)]
                              (when (seq missing) [deriver (set missing)]))))
                    handed)]
    (is (= {:new #{} :gone #{}} {:new (set/difference handed handed-derivers) :gone (set/difference handed-derivers handed)})
        "a new deriver handed a function is recorded in handed-derivers")
    (is (= {} unled) "a change to any caller of a handed deriver runs it analysed")))
