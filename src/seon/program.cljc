(ns seon.program
  "Pure declaration identities and exact-row ownership for program rows."
  (:require [malli.core :as m]
            [seon.error.refusal :as error]
            [seon.fn.schema-shape :as schema-shape]
            [seon.fn.signature :as signature]
            [seon.id :as id]
            [seon.schema :as schema]
            [malli.registry :as mr]
            [seon.schema.internal :as internal]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            ;; CLJ-only: `seon.schema.edn` has no CLJS side, and the two uses
            ;; below already sit inside a `#?(:clj …)` branch.
            #?(:clj [seon.schema.edn :as schema.edn])))

(def identity-attributes
  "Source declaration identities, derived from their authored row-schema links.
   Program components and other analysis rows are selected by program-attributes."
  (into (sorted-set)
        (keep (fn [[attribute definition]]
                (when (and (vector? definition)
                           (map? (second definition))
                           (:seon.program/row-schema (second definition)))
                  attribute)))
        #?(:clj (schema.edn/packaged-forms)
           :cljs (schema/registered-schemas))))

(defn program-attributes
  "Attributes declared by program entity schemas in this compiled projection.
   Other writers' entries are excluded. Schema-row properties come from the
   same bridge that projects them onto stored schema declarations."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:set :qualified-keyword]]}
  [projection]
  (let [registry (:seon.schema.projection/registry projection)
        forms (:seon.schema.projection/forms projection)
        definitions (map #(mr/schema registry %) (keys forms))
        program (filter #(= :seon.program
                            (:seon.program/partition
                             (internal/entity-properties %))) definitions)
        properties? (some #(:seon.program/projected-properties
                            (internal/entity-properties %)) program)]
    (into
     (into #{}
           (comp (mapcat internal/entity-entries)
                 (remove (fn [[_ properties _]] (:seon.program/written-by properties)))
                 (map first))
           program)
     #?(:clj
        (when properties?
          (let [project (requiring-resolve 'seon.schema.datahike/storable-properties-in)]
            (mapcat #(keys (project projection %)) (keys forms))))
        :cljs []))))

(defn edn-round-trip-symbol?
  "Whether `value` is a symbol whose printed EDN reads back as that symbol.

  This total predicate is the storage boundary for program-graph edge names.
  It rejects symbols whose namespace begins with `:`, including symbols made
  from clj-kondo's `:clj-kondo/unknown-namespace` sentinel: `pr-str` prints
  those as keywords, so an EDN boundary changes their type."
  {:malli/schema
   [:=>
    [:cat
     [:any
      {:seon.schema.admission/exemption
       :seon.schema.admission/polymorphic-boundary
       :seon.schema.admission/reason
       "A total schema predicate accepts arbitrary objects and returns false for values that are not round-tripping symbols."
       :gen/elements [nil false 0 "" :k 'plain 'qualified/name]}]]
    :boolean]}
  [value]
  (and (symbol? value)
       (try
         (= value (#?(:clj edn/read-string :cljs reader/read-string)
                   (pr-str value)))
         ;; The reader's declared "not readable" failure only: clojure.edn's
         ;; RuntimeException (EdnReader.java:130, :174-177); cljs.reader's
         ;; ex-info. Everything else propagates.
         (catch #?(:clj RuntimeException :cljs ExceptionInfo) _ false))))

#?(:clj
   (defn overrides
     "Return current agent-admitted function identities in indexed src namespaces.

      Namespace membership follows recorded declaration/file relations, including
      history: replacing a declaration removes its file coordinates, and replacing
      the last indexed function must not make its namespace cease to be first-party.
      Both current facts and history derive from the supplied database value.
      An empty vector means this database has no overrides. JVM callers continue
      running their compiled definitions until write-back and reload.

      Example:
      (seon.program/overrides (seon.db/db connection))"
     {:malli/schema [:=> [:cat :seon.db/database-value]
                     [:or [:vector :seon.fn/sym] :seon.db/invalid-read-error]]}
     [database]
     ; seon.db requires this declaration owner; resolve its read functions late.
     (let [history ((requiring-resolve 'seon.db/history) database)]
       (if (:seon.db/invalid-read history)
         history
         (let [result
               ((requiring-resolve 'seon.db/q)
                '[:find [?symbol ...]
                  :in $ $history
                  :where
                  [$ ?function :seon.schema.admission/source :agent]
                  [$ ?function :seon.fn/sym ?symbol]
                  [$ ?function :seon.fn/ns ?namespace]
                  [$ ?namespace :seon.ns/name ?name]
                  [$history ?indexed-namespace :seon.ns/name ?name]
                  [$history ?member :seon.fn/ns ?indexed-namespace]
                  [$history ?member :seon.fn/file ?file]
                  [$ ?file :seon.fn.file/relative-root "src"]]
                database history)]
           (if (:seon.db/invalid-read result)
             result
             (vec (sort result))))))))

#?(:clj
   (defn base-context-injected-symbols
     "Interpreter bindings declared with their reason in the schema population."
     {:malli/schema [:=> [:cat :seon.schema/projection] [:vector :symbol]]}
     [projection]
     (->> (keys (:seon.schema.projection/forms projection))
          (map #(m/properties (mr/schema (:seon.schema.projection/registry projection) %)))
          (filter :seon.sci.binding/reason)
          (mapcat
           (fn [properties]
             (if-let [target (:seon.sci.binding/target properties)]
               [target]
               (when-let [namespace-name (:seon.sci.binding/public-namespace properties)]
                 (require namespace-name)
                 (map #(symbol (str namespace-name) (str %))
                      (keys (ns-publics namespace-name)))))))
          distinct
          (sort-by str)
          vec)))

(defn- declaration-refused!
  {:malli/schema [:=> [:cat :string [:vector :seon.program/identity] :map] :nil]}
  [message identities data]
  (throw
   (ex-info message
            {:seon.error/at #?(:clj (java.util.Date.) :cljs (js/Date.))
              :seon.error/layer :seon.program/declaration
              :seon.error/operation 'seon.program/declaration-refused!
              :seon.error/message message
              :seon.error/offending identities
              :seon.program/identity-attributes (set (map first identities))
              :seon.error/data data
              :seon.error/expected :seon.program/declaration-row})))

(defn- entry-attribute
  [entry]
  (when (vector? entry) (first entry)))

(defn- entry-properties
  [entry]
  (when (and (vector? entry) (map? (second entry))) (second entry)))

(defn- derived-shape
  "The shape one identity attribute's declared entity map defines.

  The identity attribute names its entity schema (`:seon.program/row-schema`)
  and its source attribute; the entity map's own entries are the attributes
  the static indexer owns, minus every entry declaring another
  `:seon.program/written-by`. Nothing here may read a missing declaration as
  an empty shape: each absence refuses, naming the identity and the member,
  because an empty owned set would silently strip every attribute of the
  family it describes."
  [projection identity-attribute]
  (let [refuse!
        (fn [message data]
          (declaration-refused!
           message [[identity-attribute nil]]
           (merge {:seon.program/identity-attribute identity-attribute} data)))
        registry (:seon.schema.projection/registry projection)
        properties (some-> (mr/schema registry identity-attribute) m/properties)
        row-schema (:seon.program/row-schema properties)
        _ (when-not (qualified-keyword? row-schema)
            (refuse! "A program identity attribute declares no row schema."
                     {:seon.program/missing-attributes [:seon.program/row-schema]}))
        source-attribute (:seon.program/source-attribute properties)
        _ (when-not (qualified-keyword? source-attribute)
            (refuse! "A program identity attribute declares no source attribute."
                     {:seon.program/missing-attributes
                      [:seon.program/source-attribute]}))
        definition (mr/schema registry row-schema)
        _ (when-not (and definition (internal/entity-schema? definition))
            (refuse! "A program row schema is not a declared entity map."
                     {:seon.program/row-schema row-schema}))
        entries (internal/entity-entries definition)
        owned (into [] (comp (filter #(nil? (:seon.program/written-by
                                             (entry-properties %))))
                             (keep entry-attribute))
                    entries)]
    (when-not (some #{identity-attribute} owned)
      (refuse! "A program row schema does not declare its own identity attribute."
               {:seon.program/row-schema row-schema}))
    {:seon.program/identity-attribute identity-attribute
     :seon.program/source-attribute source-attribute
     :seon.program/owned-attributes
     (if (true? (:seon.program/projected-properties
                 (internal/entity-properties definition)))
       :seon.program/schema-row-properties
       owned)}))

(def test-marker-attributes
  "The declared test markers a namespace form may carry for its deftests.

  Each is a property of the WHOLE namespace when declared there — a namespace
  of real-boot drills declares `:seon.test/long` once, a namespace of fixture
  material declares `:seon.test/fixture` once — so a deftest inherits what it
  does not declare itself.

  Lifting a marker here is sufficient for BOTH indexing seams. The runner then
  reads the indexed fact instead of the Var: the tier partition asks the
  published row whether a test is a platform regression, and bare namespace
  selection asks whether a namespace is fixture material, in place of the
  `_test` filename convention that answered it before."
  [:seon.test/long :seon.test/long-ms :seon.test/platform :seon.test/fixture])

(defn test-markers
  "The declared markers for one test: its own metadata, then its namespace's.

  THE ONE RULE both lifting seams read. `seon.fn/var-row` holds the analyzed
  deftest form and its namespace form; `seon.sci.eval` holds the loaded Var
  and its namespace. Reading the Var alone made a namespace-declared
  `:seon.test/long` reach the loaded-Var reader and NEVER the published row,
  so a statically indexed base answered NOT-LONG for twelve real-boot drills."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :map]}
  [var-metadata namespace-metadata]
  (into {}
        (keep (fn [attribute]
                (when-let [declared (or (find var-metadata attribute)
                                        (find namespace-metadata attribute))]
                  (when (some? (val declared))
                    [attribute (val declared)]))))
        test-marker-attributes))

(defn shapes-in
  "Program-row shapes derived from the supplied generation's retained entity roots.

  THE ONE ANSWER to \"which attributes does a program row own\". Declaring an
  attribute on `:seon.fn/fn`, `:seon.test/test`, `:seon.ns/ns`,
  `:seon.fn.file/file`, `:seon.lint/finding` or `:seon.schema/schema` is
  therefore sufficient for the indexer to keep, write and exactly replace it:
  there is no second list, which is what twice silently stripped an owned
  attribute on 2026-09-16 (`7cfe02790`, `925ca19fe`)."
  {:malli/schema [:=> [:cat :seon.schema/projection] :seon.program/shapes]}
  [projection]
  (into {}
        (map (fn [identity-attribute]
               [identity-attribute (derived-shape projection identity-attribute)]))
        (filter #(some-> (mr/schema (:seon.schema.projection/registry projection) %)
                         m/properties :seon.program/row-schema)
                (keys (:seon.schema.projection/forms projection)))))

#?(:clj (defonce ^:private !authored-shapes (atom nil)))

(defn- authored-shapes
  "The shapes the AUTHORED declaration resources currently define.

  The program row families are this program's own structure, so the authority
  is the declaration resources and not whichever projection happens to be
  active: a cluster whose stored schema predates a declaration must still index
  by it — the exact scope the literal table this replaced had.

  THE CACHE KEY IS THE RESOURCES' OWN STAMP, never the process. A
  process-lifetime snapshot made an attribute declared after this JVM started
  invisible to `canonical-row`, so `bin/seon init --dev` adopted a declaration
  the next `seon.fn/artifact` then silently stripped
  (`program-shapes-cache-strips-attributes-declared-after-the-jvm-started`);
  keying by `seon.schema.edn/declaration-stamp` makes that edit a MISS BY
  CONSTRUCTION. Only a SUCCESS is remembered, because a cached refusal would
  outlive the reload that fixes the declaration and report a healthy
  population as broken forever."
  []
  #?(:clj
     (let [stamp (schema.edn/declaration-stamp)
           cached @!authored-shapes]
       (if (= stamp (:seon.program/declaration-stamp cached))
         (:seon.program/shapes cached)
         (let [derived (shapes-in (schema/build-projection (schema.edn/packaged-forms)))]
           (reset! !authored-shapes
                   {:seon.program/declaration-stamp stamp
                    :seon.program/shapes derived})
           derived)))
     :cljs (shapes-in (schema/build-projection (schema/registered-schemas)))))

(defn shapes
  "Program-row shapes keyed by their database identity attribute.

  Answers from the AUTHORED declaration resources as they are NOW (see
  [[authored-shapes]]) — the answer for a caller holding no operation of its
  own. A caller that DOES hold one resolves its declaration population once,
  derives its shapes once with [[shapes-in]], and hands that VALUE to every
  per-row question (AGENTS.md 2.1). Nothing memoizes a supplied population
  here: the memo this replaced was a process-wide mirror of a value its caller
  already held, and every per-row caller went around it."
  {:malli/schema [:=> [:cat] :seon.program/shapes]}
  []
  (authored-shapes))

(defn declaration-at
  "The declaration whose `:seon.fn/form-span` contains `position`.

  Spans are half-open UTF-8 byte offsets, so a position belongs to exactly
  one declaration and `end` belongs to the next. THE ANSWER IS NEVER NIL:
  \"no declaration contains this byte\" is a fact a caller must be able to
  read and report, so it comes back as a flat
  `:seon.program/no-declaration-at-error` value naming the position and the
  spanned declarations examined. A merge that silently found nothing is
  the absence-as-health defect this exists to prevent."
  {:malli/schema
   [:=> [:cat [:sequential :map] :seon.program/position]
    [:or :map :seon.program/no-declaration-at-error]]}
  [declarations position]
  (let [spanned (filter :seon.fn/form-span declarations)]
    (or (first (filter (fn [declaration]
                         (let [[start end] (:seon.fn/form-span declaration)]
                           (and (<= start position) (< position end))))
                       spanned))
        {:seon.error/at #?(:clj (java.util.Date.) :cljs (js/Date.))
          :seon.error/layer :seon.program/declaration
          :seon.error/operation 'seon.program/declaration-at
          :seon.error/message "No declaration span contains the requested byte; choose a byte within a declaration."
          :seon.error/offending position
          :seon.program/position position
          :seon.program/declarations-examined (count spanned)
          :seon.error/expected :seon.fn/form-span})))

(defn shape
  "The program shape owned by `identity-attribute`.

  The two-argument arity reads the operation's own derived shapes (see
  [[shapes-in]]); it takes that value, never the declaration population it was
  derived from, so a population handed here is a typed refusal rather than a
  shape silently missing."
  {:malli/schema
   [:function
    [:=> [:cat :seon.program/identity-attribute]
     [:maybe :seon.program/shape]]
    [:=> [:cat :seon.program/shapes :seon.program/identity-attribute]
     [:maybe :seon.program/shape]]]}
  ([identity-attribute] (get (shapes) identity-attribute))
  ([row-shapes identity-attribute] (get row-shapes identity-attribute)))

(defn row-identity
  "The `[identity-attribute value]` pair carried by `row`."
  {:malli/schema
   [:=> [:cat [:maybe :map]]
    [:maybe :seon.program/identity]]}
  [row]
  (some (fn [identity-attribute]
          (when-some [value (get row identity-attribute)]
            [identity-attribute value]))
        identity-attributes))

(def ^:private definition-digest-excluded-attributes
  #{:db/id
    :seon.fn/file
    :seon.fn/form-span
    :seon.fn/calls
    :seon.fn/references
    :seon.fn/keywords
    :seon.fn/writes
    :seon.fn/call-arities
    ;; Derived from `:seon.fn/spec` and `:seon.fn/arglists`, which it hashes.
    :seon.fn/arities
    :seon.fn/host-bound?
    :seon.program/analyzed-source-digest
    :seon.program/definition-digest
    :seon.schema.admission/source})

(defn definition-digest
  "Digest one declaration's identity and effective meaning.

   The digest includes authored definition, resolver context and effective
   semantic metadata. Storage provenance, position and derived graph
   observations are deliberately excluded."
  {:malli/schema
   [:function
    [:=> [:cat :map] :seon.program/definition-digest]
    [:=> [:cat :map [:maybe :map]] :seon.program/definition-digest]]}
  ([row] (definition-digest row nil))
  ([row resolver-context]
   (let [parts
         (cond-> {:seon.program/declaration
                  (apply dissoc row definition-digest-excluded-attributes)}
           resolver-context
           (assoc :seon.program/resolver-context resolver-context))]
     (id/digest 64 [(schema/canonical-data-string parts)]))))

#?(:clj
   (defn- digest-map-refusal
     "The typed unknown `digest-map` answers when its evidence is incomplete."
     {:malli/schema [:=> [:cat :seon.program/missing-evidence :string :map]
                     :seon.program/digest-map-refusal]}
     [missing message members]
     (error/diagnostic
      (merge {:seon.error/at (java.util.Date.)
              :seon.error/layer :seon.program/comparison
              :seon.error/operation 'seon.program/digest-map
              :seon.error/message message
              :seon.program/missing-evidence missing}
             members))))

(defn declaration-families
  "Classify every program identity family this projection declares.

  A family is compared when its row schema declares
  `:seon.program/definition-digest`, and recomputed when the row schema declares
  `:seon.program/recomputed`. A family declaring neither, or whose row schema is
  absent, is unclassified: its evidence is unknown, never an empty family."
  {:malli/schema [:=> [:cat :seon.schema/projection]
                  :seon.program/declaration-families]}
  [projection]
  (let [registry (:seon.schema.projection/registry projection)
        classify
        (fn [attribute]
          (let [row-schema (some-> (mr/schema registry attribute)
                                   m/properties :seon.program/row-schema)
                definition (some->> row-schema (mr/schema registry))
                entity? (and definition (internal/entity-schema? definition))
                digested? (and entity?
                               (some #{:seon.program/definition-digest}
                                     (keep entry-attribute
                                           (internal/entity-entries definition))))
                recomputed? (and entity?
                                 (true? (:seon.program/recomputed
                                         (internal/entity-properties definition))))]
            (cond
              (and digested? (not recomputed?)) :seon.program/compared-families
              (and recomputed? (not digested?)) :seon.program/recomputed-families
              :else :seon.program/unclassified-families)))
        families (filter #(some-> (mr/schema registry %)
                                  m/properties :seon.program/row-schema)
                         (keys (:seon.schema.projection/forms projection)))]
    (reduce (fn [result attribute]
              (update result (classify attribute) conj attribute))
            {:seon.program/compared-families #{}
             :seon.program/recomputed-families #{}
             :seon.program/unclassified-families #{}}
            families)))

#?(:clj
   (defn digest-map
     "Read every compared program declaration and its stored definition digest.

  The families come from `database`'s own carried projection, so a branch
  compares the families its declarations define. Recomputed families (file
  and lint rows, derived from bytes and analysis) are not compared. Incomplete
  evidence answers a typed refusal, never a partial map: an unclassified
  family, an unreadable index page, a compared declaration without its digest,
  or more than `:seon.program/max-datoms` index datoms. Supplied
  `:seon.program/identities` (`changed-identities`) read only those."
     {:malli/schema
      [:=> [:cat :seon.db/database-value
            [:map [:seon.program/max-datoms :seon.program/max-datoms]
             [:seon.program/identities {:optional true} :seon.program/identity-set]]]
       [:or :seon.program/digest-map :seon.program/digest-map-refusal]]}
     [database {:seon.program/keys [max-datoms identities]}]
     (let [projection (try ((requiring-resolve 'seon.db/carried-projection) database)
                           (catch clojure.lang.ExceptionInfo failure
                             (digest-map-refusal
                              :seon.program/projection
                              "The database value carries no declaration projection."
                              {:seon.error/evidence-unavailable (ex-message failure)})))]
       (if (:seon.error/at projection)
         projection
         (let [{compared :seon.program/compared-families
                unclassified :seon.program/unclassified-families}
               (declaration-families projection)
               outside (remove (program-attributes projection) compared)
               index-page (requiring-resolve 'seon.db/index-page)
               ;; One attribute's complete AEVT range, paged through the index
               ;; owner, or the refusal naming the exhausted bound or failed read.
               read-range
               (fn [attribute remaining]
                 (loop [datoms [] cursor nil]
                   (let [page (index-page database
                                          (cond-> {:index :aevt
                                                   :components [attribute]
                                                   :direction :forward
                                                   :limit 200
                                                   :max-result-weight 10000000}
                                            cursor (assoc :cursor cursor)))]
                     (cond
                       (:seon.error/at page)
                       (digest-map-refusal
                        :seon.program/read "A program index page could not be read."
                        {:seon.error/member attribute
                         :seon.error/offending page})

                       :else
                       (let [datoms (into datoms (:datahike.index-page/datoms page))]
                         (cond
                           (< remaining (count datoms))
                           (digest-map-refusal
                            :seon.program/read-bound
                            "The program digest map exceeds its datom bound."
                            {:seon.error/member attribute
                             :seon.program/max-datoms max-datoms})

                           (:datahike.index-page/complete? page) datoms
                           :else (recur datoms (:datahike.index-page/cursor page))))))))]
           (cond
             (seq unclassified)
             (digest-map-refusal
              :seon.program/family-classification
              "A program identity family declares neither a definition digest nor recomputation."
              {:seon.error/expected-key :seon.program/definition-digest
               :seon.program/unclassified-families (set unclassified)})

             (or (empty? compared) (seq outside))
             (digest-map-refusal
              :seon.program/family-classification
              "The compared families are empty or outside the program partition."
              (cond-> {:seon.error/expected-key :seon.program/partition}
                (seq outside)
                (assoc :seon.program/unclassified-families (set outside))))

             ;; Two queries: one join plans a scan (982 ms / 1,000 vs 26 + 22 ms).
             identities
             (let [q (requiring-resolve 'seon.db/q)
                   rows (q '[:find ?a ?v ?e :in $ [[?a ?v] ...] :where [?e ?a ?v]]
                           database (vec identities))
                   stored (if (:seon.error/at rows) rows
                            (into {} (q '[:find ?e ?d :in $ [?e ...] :where
                                          [(get-else $ ?e :seon.program/definition-digest "") ?d]]
                                        database (mapv peek rows))))
                   missing (when-not (:seon.error/at stored)
                             (into #{} (keep (fn [[a v e]] (when (= "" (stored e)) [a v]))) rows))]
               (cond
                 (:seon.error/at stored)
                 (digest-map-refusal :seon.program/read "A program declaration could not be read."
                                     {:seon.error/offending stored})
                 (seq missing)
                 (digest-map-refusal :seon.program/definition-digest
                                     "Program declarations are missing their stored definition digest."
                                     {:seon.error/expected-key :seon.program/definition-digest
                                      :seon.program/undigested-declarations missing})
                 :else (into {} (map (fn [[a v e]] [[a v] (stored e)])) rows)))

             :else
             (let [ranges
                   (reduce (fn [result attribute]
                             (let [range (read-range attribute
                                                     (- max-datoms (:count result)))]
                               (if (map? range)
                                 (reduced range)
                                 (-> result
                                     (assoc-in [:ranges attribute] range)
                                     (update :count + (count range))))))
                           {:count 0 :ranges {}}
                           (cons :seon.program/definition-digest (sort compared)))]
               (if (:seon.error/at ranges)
                 ranges
                 (let [digests (into {} (map (juxt :e :v))
                                     (get-in ranges [:ranges :seon.program/definition-digest]))
                       identities (mapcat #(get-in ranges [:ranges %]) (sort compared))
                       missing (into #{} (comp (remove #(contains? digests (:e %)))
                                               (map (juxt :a :v)))
                                     identities)]
                   (if (seq missing)
                     (digest-map-refusal
                      :seon.program/definition-digest
                      "Program declarations are missing their stored definition digest."
                      {:seon.error/expected-key :seon.program/definition-digest
                       :seon.program/undigested-declarations missing})
                     (into {} (map (fn [datom]
                                     [[(:a datom) (:v datom)] (get digests (:e datom))]))
                           identities)))))))))))

#?(:clj
   (defn changed-identities
     "Declarations whose digest `database` asserted or retracted after `base`,
  deleted ones named by history: `digest-map`'s scoping identities. Only
  functions and tests may change; another or no family, missing history and
  more than `:seon.program/max-datoms` changed entities refuse by name.
  Datahike indexes no transaction prefix: this walks the digest's history."
     {:malli/schema
      [:=> [:cat :seon.db/database-value :seon.db/database-value
            [:map [:seon.program/max-datoms :seon.program/max-datoms]]]
       [:or :seon.program/identity-set :seon.program/digest-map-refusal]]}
     [base database {:seon.program/keys [max-datoms]}]
     (let [[q history since carried basis-t]
           (map requiring-resolve '[seon.db/q seon.db/history seon.db/since
                                    seon.db/carried-projection seon.db/basis-t])
           refuse #(digest-map-refusal %1 %2 (assoc %3 :seon.error/operation
                                                    'seon.program/changed-identities))
           past (history database)
           entities (if (:seon.error/at past) past
                      (q '[:find [?e ...] :where [?e :seon.program/definition-digest]]
                         (since past (basis-t base))))
           rows (if (:seon.error/at entities) entities
                  (q '[:find ?e ?a ?v :in $ [?e ...] [?a ...] :where [?e ?a ?v]]
                     past entities (vec (:seon.program/compared-families
                                         (declaration-families (carried database))))))
           identities (when-not (:seon.error/at rows) (into #{} (map (comp vec rest)) rows))
           outside (into #{} (remove (comp #{:seon.fn/sym :seon.test/sym} first)) identities)]
       (cond
         (:seon.error/at rows)
         (refuse :seon.program/history "The changes since the base need readable history."
                 {:seon.error/offending rows})
         (< max-datoms (count entities))
         (refuse :seon.program/read-bound "The changes since the base exceed their datom bound."
                 {:seon.program/max-datoms max-datoms})
         (not= (set entities) (set (map first rows)))
         (refuse :seon.program/family-classification
                 "A changed digest belongs to no compared declaration family."
                 {:seon.error/expected-key :seon.program/declaration-identity})
         (seq outside)
         (refuse :seon.program/scope "Only function and test declarations may change here."
                 {:seon.error/offending outside})
         :else identities))))

(defn three-way
  "Classify branch and head declaration identities relative to base.

   Absence is a state. Equal branch/head states are unchanged, including equal
   additions, edits and retractions. Conflicts retain all three states."
  {:malli/schema
   [:=> [:cat :seon.program/digest-map :seon.program/digest-map
         :seon.program/digest-map]
    :seon.program/three-way]}
  [base branch head]
  (let [absent :seon.program/absent
        state (fn [digests declaration] (get digests declaration absent))]
    (reduce
     (fn [result declaration]
       (let [base-digest (state base declaration)
             branch-digest (state branch declaration)
             head-digest (state head declaration)]
         (cond
           (= branch-digest head-digest)
           (update result :seon.program/unchanged conj declaration)

           (= branch-digest base-digest)
           (update result :seon.program/changed-on-head conj declaration)

           (= head-digest base-digest)
           (update result
                   (cond
                     (= base-digest absent) :seon.program/added
                     (= branch-digest absent) :seon.program/retracted
                     :else :seon.program/changed-on-branch)
                   conj declaration)

           :else
           (-> result
               (update :seon.program/conflict conj declaration)
               (assoc-in [:seon.program/conflict-digests declaration]
                         {:seon.program/base-digest base-digest
                          :seon.program/branch-digest branch-digest
                          :seon.program/head-digest head-digest})))))
     {:seon.program/unchanged #{}
      :seon.program/changed-on-branch #{}
      :seon.program/changed-on-head #{}
      :seon.program/conflict #{}
      :seon.program/added #{}
      :seon.program/retracted #{}
      :seon.program/conflict-digests {}}
     (into #{} (concat (keys base) (keys branch) (keys head))))))

(defn- row-identities
  [row]
  (into []
        (keep (fn [identity-attribute]
                (when-some [value (get row identity-attribute)]
                  [identity-attribute value])))
        identity-attributes))

(defn- read-edn
  [source]
  (#?(:clj edn/read-string :cljs reader/read-string) source))

(defn- component-id
  [function-symbol path]
  (str "seon.fn.contract/" function-symbol "/" (pr-str path)))

(defn- binding-id
  [function-symbol arity-order argument-index path]
  (component-id function-symbol
                [:arity arity-order :argument argument-index :binding path]))

(declare binding-row)

(defn- binding-child
  [function-symbol arity-order argument-index path order role binding]
  {:db/id (binding-id function-symbol arity-order argument-index
                      (conj path :child order))
   :seon.fn.binding.child/order (long order)
   :seon.fn.binding.child/role role
   :seon.fn.binding.child/binding
   (binding-row function-symbol arity-order argument-index
                (conj path :child order :binding) binding)})

(defn- shorthand-source-key
  [directive spelling binding-symbol]
  (let [binding-namespace (or (namespace binding-symbol)
                              (namespace directive))
        binding-name (name binding-symbol)]
    (case spelling
      :keys (if binding-namespace
              (keyword binding-namespace binding-name)
              (keyword binding-name))
      :strs binding-name
      :syms (if binding-namespace
              (symbol binding-namespace binding-name)
              (symbol binding-name)))))

(defn- shorthand-binding
  [binding-symbol]
  (symbol (name binding-symbol)))

(defn- map-binding-entries
  [binding]
  (let [directive-name (fn [value]
                         (when (keyword? value) (keyword (name value))))
        defaults (some (fn [[directive value]]
                         (when (= :or (directive-name directive)) value))
                       binding)
        as-binding (some (fn [[directive value]]
                           (when (= :as (directive-name directive)) value))
                         binding)
        explicit
        (for [[local source-key] binding
              :when (not (contains? #{:keys :strs :syms :or :as}
                                    (directive-name local)))]
          {:seon.fn.binding.entry/spelling :explicit
           :seon.fn.binding.entry/source-key source-key
           :seon.fn.binding.entry/local-binding local})
        shorthand
        (for [[directive binding-symbols] binding
              :let [spelling (directive-name directive)]
              :when (contains? #{:keys :strs :syms} spelling)
              binding-symbol binding-symbols]
          {:seon.fn.binding.entry/spelling spelling
           :seon.fn.binding.entry/source-key
           (shorthand-source-key directive spelling binding-symbol)
           :seon.fn.binding.entry/local-binding
           (shorthand-binding binding-symbol)})]
    (->> (concat explicit shorthand)
         (sort-by (juxt (comp schema/canonical-data-string
                             :seon.fn.binding.entry/source-key)
                        (comp str :seon.fn.binding.entry/spelling)
                        (comp pr-str :seon.fn.binding.entry/local-binding)))
         (map-indexed
          (fn [order entry]
            (let [local-binding (:seon.fn.binding.entry/local-binding entry)]
              (cond-> (assoc entry :seon.fn.binding.entry/order (long order))
                (and (symbol? local-binding)
                     (contains? defaults local-binding))
                (assoc :seon.fn.binding.entry/default-edn
                       (pr-str (get defaults local-binding)))))))
         (#(with-meta % {:seon.fn.binding/as as-binding})))))

(defn- binding-entry-row
  [function-symbol arity-order argument-index path
   {order :seon.fn.binding.entry/order
    spelling :seon.fn.binding.entry/spelling
    source-key :seon.fn.binding.entry/source-key
    local-binding :seon.fn.binding.entry/local-binding
    default-edn :seon.fn.binding.entry/default-edn}]
  (cond->
   (merge
    {:db/id (binding-id function-symbol arity-order argument-index
                        (conj path :entry order))
     :seon.fn.binding.entry/order order
     :seon.fn.binding.entry/spelling spelling
     :seon.fn.binding.entry/binding
     (binding-row function-symbol arity-order argument-index
                  (conj path :entry order :binding) local-binding)}
    (schema-shape/typed-key-facts source-key))
    default-edn
    (assoc :seon.fn.binding.entry/default-edn default-edn)))

(defn- sequential-binding-children
  [function-symbol arity-order argument-index path binding]
  (loop [remaining (seq binding), order 0, children []]
    (if-not remaining
      children
      (let [value (first remaining)]
        (cond
          (= '& value)
          (if (and (next remaining) (nil? (next (next remaining))))
            (conj children
                  (binding-child function-symbol arity-order argument-index
                                 path order :rest (second remaining)))
            (throw
             (ex-info "Sequential destructuring has a malformed rest binding."
                      {:seon.error/at #?(:clj (java.util.Date.) :cljs (js/Date.))
                        :seon.error/layer :seon.program/declaration
                        :seon.error/operation 'seon.program/sequential-binding-children
                        :seon.error/message "Sequential binding syntax is invalid; correct the rest or alias binding."
                        :seon.error/offending binding
                        :seon.program/binding-function function-symbol
                        :seon.program/binding-argument argument-index
                        :seon.error/member :seon.fn.binding/form
                        :seon.error/expected :seon.fn.binding/row})))

          (= :as value)
          (if (and (next remaining) (nil? (next (next remaining))))
            (conj children
                  (binding-child function-symbol arity-order argument-index
                                 path order :as (second remaining)))
            (throw
             (ex-info "Sequential destructuring has a malformed :as binding."
                      {:seon.error/at #?(:clj (java.util.Date.) :cljs (js/Date.))
                        :seon.error/layer :seon.program/declaration
                        :seon.error/operation 'seon.program/sequential-binding-children
                        :seon.error/message "Sequential binding syntax is invalid; correct the rest or alias binding."
                        :seon.error/offending binding
                        :seon.program/binding-function function-symbol
                        :seon.program/binding-argument argument-index
                        :seon.error/member :seon.fn.binding/form
                        :seon.error/expected :seon.fn.binding/row})))

          :else
          (recur (next remaining) (inc order)
                 (conj children
                       (binding-child function-symbol arity-order argument-index
                                      path order :element value))))))))

(defn- binding-row
  [function-symbol arity-order argument-index path binding]
  (cond
    (symbol? binding)
    {:db/id (binding-id function-symbol arity-order argument-index path)
     :seon.fn.binding/form (pr-str binding)
     :seon.fn.binding/shape :symbol
     :seon.fn.binding/symbol binding}

    (map? binding)
    (let [entries (map-binding-entries binding)
          as-binding (:seon.fn.binding/as (meta entries))]
      (cond->
       {:db/id (binding-id function-symbol arity-order argument-index path)
        :seon.fn.binding/form (pr-str binding)
        :seon.fn.binding/shape :map}
        (seq entries)
        (assoc :seon.fn.binding/entries
               (mapv #(binding-entry-row function-symbol arity-order
                                         argument-index path %)
                     entries))
        as-binding
        (assoc :seon.fn.binding/children
               [(binding-child function-symbol arity-order argument-index
                               path 0 :as as-binding)])))

    (sequential? binding)
    (let [children (sequential-binding-children
                    function-symbol arity-order argument-index path binding)]
      (cond->
       {:db/id (binding-id function-symbol arity-order argument-index path)
        :seon.fn.binding/form (pr-str binding)
        :seon.fn.binding/shape :sequential}
        (seq children) (assoc :seon.fn.binding/children children)))

    :else
    (throw
     (ex-info "Function declaration has an unsupported binding form."
              {:seon.error/at #?(:clj (java.util.Date.) :cljs (js/Date.))
                :seon.error/layer :seon.program/declaration
                :seon.error/operation 'seon.program/binding-row
                :seon.error/message "Function binding is unsupported; use a symbol, map or sequential binding."
                :seon.error/offending binding
                :seon.program/binding-function function-symbol
                :seon.program/binding-argument argument-index
                :seon.error/member :seon.fn.binding/form
                :seon.error/expected :seon.fn.binding/row}))))

(defn- schema-reference-keys
  [compiled canonical-keys]
  (let [!references (volatile! #{})]
    (m/walk
     compiled
     (fn [schema _path _children _options]
       (when (m/-ref-schema? schema)
         (let [reference (m/-ref schema)]
           (when (contains? canonical-keys reference)
             (vswap! !references conj reference))))
       schema)
     {::m/walk-schema-refs #(not (contains? canonical-keys %))
      ::m/walk-refs #(not (contains? canonical-keys %))})
    @!references))

(defn- schema-references
  [compiled canonical-keys]
  (schema-reference-keys compiled canonical-keys))

(defn- signature-key
  [signature]
  (if (:seon.fn.signature/variadic? signature)
    [:variadic (:seon.fn.signature/min signature)]
    [:fixed (:seon.fn.signature/min signature)]))

(defn- malli-arity-key
  [info]
  (if (= :varargs (:arity info))
    [:variadic (:min info)]
    [:fixed (:min info)]))

(defn- one-by-key
  [values key-fn reason]
  (let [grouped (group-by key-fn values)]
    (when-let [[join-key matches]
               (some (fn [[join-key matches]]
                       (when (not= 1 (count matches)) [join-key matches]))
                     grouped)]
      (throw
       (ex-info "Function source and Malli contract are not bijective."
                {:seon.error/at #?(:clj (java.util.Date.) :cljs (js/Date.))
                  :seon.error/layer :seon.program/declaration
                  :seon.error/operation 'seon.program/one-by-key
                  :seon.error/message "Function arity is duplicated; give each source and contract arity one matching declaration."
                  :seon.error/offending matches
                  :seon.program/signature-member reason
                  :seon.program/signature-count (count matches)
                  :seon.error/data {:seon.fn.signature/join-key join-key}
                  :seon.error/expected 1})))
    (into {} (map (fn [[join-key matches]]
                    [join-key (first matches)])) grouped)))

(defn- input-slots
  [input]
  (case (m/type input)
    :cat
    (mapv (fn [child] {:seon.fn.argument/compiled-schema child})
          (m/children input))

    :catn
    (mapv (fn [[label _properties child]]
            {:seon.fn.argument/label label
             :seon.fn.argument/compiled-schema child})
          (m/children input))

    (throw
     (ex-info "Function input contract must be a :cat or :catn schema."
              {:seon.error/at #?(:clj (java.util.Date.) :cljs (js/Date.))
                :seon.error/layer :seon.program/declaration
                :seon.error/operation 'seon.program/input-slots
                :seon.error/message "Function input contract needs cat or catn; correct its input schema."
                :seon.error/offending (m/form input)
                :seon.program/signature-member :seon.fn/spec
                :seon.program/signature-count (count (m/children input))
                :seon.error/expected [:enum :cat :catn]}))))

(defn- label-facts
  [label]
  (cond-> {:seon.fn.argument/label-edn (pr-str label)}
    (keyword? label) (assoc :seon.fn.argument/label-keyword label)
    (string? label) (assoc :seon.fn.argument/label-string label)
    (symbol? label) (assoc :seon.fn.argument/label-symbol label)))

(defn- rest-element-schema
  [tail]
  (when (contains? #{:* :+ :repeat} (m/type tail))
    (let [children (m/children tail)]
      (when (and (= 1 (count children)) (m/schema? (first children)))
        (first children)))))

(defn- argument-row
  [function-symbol arity-order source-signature order
   {compiled :seon.fn.argument/compiled-schema
    label :seon.fn.argument/label}
   schema-forms predicate-functions]
  (let [bindings (:seon.fn.signature/bindings source-signature)
        rest-index (:seon.fn.signature/rest-index source-signature)
        rest? (= order rest-index)
        shape (schema-shape/shape-row compiled schema-forms predicate-functions)
        repeated (when rest? (rest-element-schema compiled))]
    (cond->
     {:db/id (component-id function-symbol [:arity arity-order :argument order])
      :seon.fn.argument/order (long order)
      :seon.fn.argument/index (long order)
      :seon.fn.argument/rest? rest?
      :seon.fn.argument/binding
      (binding-row function-symbol arity-order order [] (nth bindings order))
      :seon.fn.argument/schema shape}
      rest? (assoc :seon.fn.argument/rest-tail-schema shape)
      repeated
      (assoc :seon.fn.argument/rest-element-schema
             (schema-shape/shape-row repeated schema-forms predicate-functions))
      (some? label) (merge (label-facts label)))))

(defn- arity-row
  [function-symbol order info canonical-keys source-signature
   schema-forms predicate-functions]
  (let [input-refs (schema-references (:input info) canonical-keys)
        output-refs (schema-references (:output info) canonical-keys)
        guard-refs (when-let [guard (:guard info)]
                     (schema-references guard canonical-keys))
        slots (input-slots (:input info))
        expected-count (count (:seon.fn.signature/bindings source-signature))
        _ (when-not (= expected-count (count slots))
            (throw
             (ex-info "A source binding has no matching Malli input slot."
                      {:seon.error/at #?(:clj (java.util.Date.) :cljs (js/Date.))
                        :seon.error/layer :seon.program/declaration
                        :seon.error/operation 'seon.program/arity-row
                        :seon.error/message "Source bindings and contract slots differ; declare one slot per binding."
                        :seon.error/offending source-signature
                        :seon.program/signature-member :seon.fn.arity/argument-count
                        :seon.program/signature-count (count slots)
                        :seon.error/data {:seon.fn.signature/source-count expected-count}
                        :seon.error/expected expected-count})))
        arguments (mapv (fn [argument-order slot]
                          (argument-row function-symbol order source-signature
                                        argument-order slot schema-forms
                                        predicate-functions))
                        (range) slots)]
    (cond->
     {:db/id (component-id function-symbol [:arity order])
      :seon.fn.arity/order (long order)
      :seon.fn.arity/arity (pr-str (:arity info))
      :seon.fn.arity/min (long (:min info))
      :seon.fn.arity/input-schema
      (schema-shape/shape-row (:input info) schema-forms predicate-functions)
      :seon.fn.arity/arguments arguments
      :seon.fn.arity/argument-count (long (count arguments))
      :seon.fn.arity/return-schema
      (schema-shape/shape-row (:output info) schema-forms predicate-functions)}
      (contains? info :max) (assoc :seon.fn.arity/max (long (:max info)))
      (:guard info)
      (assoc :seon.fn.arity/guard-schema
             (schema-shape/shape-row (:guard info) schema-forms
                                     predicate-functions))
      (seq input-refs) (assoc :seon.fn.arity/input-refs input-refs)
      (seq output-refs) (assoc :seon.fn.arity/output-refs output-refs)
      (seq guard-refs) (assoc :seon.fn.arity/guard-refs guard-refs))))

(defn contract-facts
  "Parsed query facts derived from one canonical function contract."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.program/function-symbol :qualified-symbol]
      [:seon.program/spec [:string {:min 1}]]
      [:seon.program/source [:string {:min 1}]]
      [:seon.program/arglists {:optional true} :string]
      [:seon.program/compile-options :map]
      [:seon.program/predicate-functions :map]
      [:seon.program/schema-keys [:set :keyword]]]]
    :map]}
  [{function-symbol :seon.program/function-symbol
    spec :seon.program/spec
    source :seon.program/source
    arglists :seon.program/arglists
    compile-options :seon.program/compile-options
    predicate-functions :seon.program/predicate-functions
    canonical-keys :seon.program/schema-keys
    schema-forms :seon.program/schema-forms
    reader-namespace :seon.program/reader-namespace
    reader-aliases :seon.program/reader-aliases}]
  (let [compiled (m/function-schema
                  (schema/compilable-form (read-edn spec)
                                          predicate-functions)
                  compile-options)
        arities (m/-function-schema-arities compiled)
        {source-signatures :seon.fn.signature/signatures
         override? :seon.fn.signature/arglists-override?}
        (signature/function-signatures
         (cond-> {:seon.fn/source source
                  :seon.fn.signature/namespace reader-namespace
                  :seon.fn.signature/aliases (or reader-aliases {})}
           arglists (assoc :seon.fn/arglists arglists)))
        source-by-key (one-by-key source-signatures signature-key
                                  :seon.fn/source)
        malli-by-key (one-by-key (mapv m/-function-info arities)
                                 malli-arity-key :seon.fn/spec)
        source-keys (set (keys source-by-key))
        malli-keys (set (keys malli-by-key))]
    (when-not (= source-keys malli-keys)
      (throw
       (ex-info "Source and Malli arity sets do not match."
                {:seon.error/at #?(:clj (java.util.Date.) :cljs (js/Date.))
                  :seon.error/layer :seon.program/declaration
                  :seon.error/operation 'seon.program/contract-facts
                  :seon.error/message "Source and contract arities differ; give each source arity a matching contract."
                  :seon.error/offending malli-keys
                  :seon.program/signature-member :seon.fn/arities
                  :seon.program/signature-count (count malli-keys)
                  :seon.error/expected source-keys})))
    (cond->
     {:seon.fn/arities
      (mapv (fn [order arity]
              (let [info (m/-function-info arity)]
                (arity-row function-symbol order info canonical-keys
                           (get source-by-key (malli-arity-key info))
                           schema-forms predicate-functions)))
            (range)
            arities)}
      override? (assoc :seon.fn/arglists-override? true))))

(defn with-contract-facts
  "Add parsed facts to one canonical contracted function row."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.program/row :seon.program/declaration-row]
      [:seon.program/compile-options :map]
      [:seon.program/predicate-functions :map]
      [:seon.program/schema-keys [:set :keyword]]]]
    :seon.program/declaration-row]}
  [{row :seon.program/row
    compile-options :seon.program/compile-options
    predicate-functions :seon.program/predicate-functions
    canonical-keys :seon.program/schema-keys
    schema-forms :seon.program/schema-forms
    reader-aliases :seon.program/reader-aliases}]
  (cond
    (:seon.fn/spec row)
    (merge row
           (contract-facts
            (cond->
             {:seon.program/function-symbol (:seon.fn/sym row)
              :seon.program/spec (:seon.fn/spec row)
              :seon.program/source (:seon.fn/source row)
              :seon.program/compile-options compile-options
              :seon.program/predicate-functions predicate-functions
              :seon.program/schema-keys canonical-keys
              :seon.program/schema-forms
              (or schema-forms (schema/registered-schemas))
              :seon.program/reader-namespace
              (second (:seon.fn/ns row))
              :seon.program/reader-aliases (or reader-aliases {})}
              (:seon.fn/arglists row)
              (assoc :seon.program/arglists (:seon.fn/arglists row)))))

    (:seon.schema/key row)
    (assoc row :seon.schema/shape
           (schema-shape/shape-row
            (m/schema (:seon.schema/key row) compile-options)
            (or schema-forms (schema/registered-schemas))
            predicate-functions))

    :else row))

(defn- canonical-row-in
  [row-shapes row]
  (let [identities (row-identities row)]
    (when (> (count identities) 1)
      (declaration-refused!
       "A declaration row carries more than one identity family."
       identities
       {}))
    (when-let [[identity-attribute _] (first identities)]
      (let [owned-attributes
            (:seon.program/owned-attributes (get row-shapes identity-attribute))
            owned-attributes
            (if (= :seon.program/schema-row-properties owned-attributes)
              (into [] (filter qualified-keyword?) (keys row))
              owned-attributes)]
        (into {}
              (remove (fn [[attribute value]]
                        (or (nil? value)
                            (and (contains? #{:seon.ns/requires
                                              :seon.ns/aliases
                                              :seon.ns/imports
                                              :seon.ns/refers}
                                            attribute)
                                 (empty? value)))))
              (select-keys row owned-attributes))))))

(defn canonical-row
  "The exact non-nil attributes owned by one declaration row.

  Ownership comes from the family's declared entity map (see [[shapes-in]]),
  so an attribute declared there is kept without any code change here. The
  explicit-shapes arity is the honest one for a caller that already derived
  its operation's shapes: it asks nothing per row."
  {:malli/schema
   [:function
    [:=> [:cat [:maybe :map]] [:maybe :map]]
    [:=> [:cat :seon.program/shapes [:maybe :map]] [:maybe :map]]]}
  ([row] (canonical-row-in (shapes) row))
  ([row-shapes row] (canonical-row-in row-shapes row)))

(defn- canonical-namespace-components
  "Namespace components in the one shape `:seon.ns/ns` declares.

  A reader event names required namespaces as bare symbols in reading order
  and carries its component collections as vectors; the persisted row holds
  the same facts as sets, with each requirement a `:seon.ns/name` lookup ref.
  The evaluator also builds these components directly from SCI's namespace
  table, so this is idempotent over an already canonical row."
  [row]
  (cond-> row
    (:seon.ns/requires row)
    (update :seon.ns/requires set)
    (:seon.ns/aliases row) (update :seon.ns/aliases set)
    (:seon.ns/imports row) (update :seon.ns/imports set)
    (:seon.ns/refers row) (update :seon.ns/refers set)))

(defn declaration-row
  "Canonical declaration row for a reader event under a function policy.

  `:all` indexes every directly read top-level build function as future graph
  input;
  `:contracted` admits only runtime functions carrying a complete contract.

  `admission-source` is who admitted the declaration, and this function
  stamps it. Every declaration row family REQUIRES it, so it is an argument
  rather than an event key a caller can forget: a row with no admission
  source is not constructable here."
  {:malli/schema
   [:=> [:cat :seon.schema/projection :map [:enum :all :contracted]
         :seon.schema.admission/source]
    [:maybe :seon.program/declaration-row]]}
  [projection event function-policy admission-source]
  (let [event (assoc event :seon.schema.admission/source admission-source)
        candidate
        (cond
          (:seon.fn.file/relative-path event) event
          (:seon.lint/id event) event
          (:seon.ns/name event) event
          (and (:seon.fn/sym event)
               (or (= :all function-policy)
                   (and (= :contracted function-policy)
                        (:seon.fn/spec event))))
          event
          (:seon.schema/key event) event
          (:seon.test/sym event) event
          :else nil)
        candidate
        (cond
          (:seon.ns/name candidate)
          (canonical-namespace-components candidate)

          (and (:seon.schema/key candidate)
               (:seon.schema/form candidate))
          (let [schema-key (:seon.schema/key candidate)
                definition (read-edn (:seon.schema/form candidate))
                candidate-projection
                (schema/projection-with-schema
                 projection schema-key definition
                 {:seon.schema.admission/source admission-source})
                row (some #(when (= schema-key (:seon.schema/key %)) %)
                          (schema/canonical-schema-rows
                           candidate-projection {schema-key definition}))]
            (cond-> (assoc (merge (select-keys candidate
                                              [:seon.schema/generatable?
                                               :seon.schema/shape])
                                 row)
                           :seon.schema.admission/source
                           (:seon.schema.admission/source candidate))
              (:seon.schema/ns candidate)
              (assoc :seon.schema/ns (:seon.schema/ns candidate))))

          :else candidate)
        row-shapes (shapes-in projection)
        candidate (canonical-row row-shapes candidate)
        candidate
        (if (and candidate
                 (or (:seon.ns/name candidate)
                     (:seon.fn/sym candidate)
                     (:seon.schema/key candidate)
                     (:seon.test/sym candidate))
                 (nil? (:seon.program/definition-digest candidate)))
          (assoc candidate :seon.program/definition-digest
                 (definition-digest candidate))
          candidate)
        row candidate]
    (when row
      (let [[identity-attribute _ :as program-identity] (row-identity row)
            source-attribute (:seon.program/source-attribute
                              (shape row-shapes identity-attribute))]
        (when-not (get row source-attribute)
          (declaration-refused!
           "A reader declaration has no source. Analysis has not run at this stage."
           [program-identity]
           {:seon.program/missing-attributes [source-attribute]}))))
    row))

(defn- changed-attributes-in
  [row-shapes current desired]
  (if-let [[identity-attribute _]
           (or (row-identity desired) (row-identity current))]
    (let [owned-attributes
          (:seon.program/owned-attributes (get row-shapes identity-attribute))
          owned-attributes
          (if (= :seon.program/schema-row-properties owned-attributes)
            (into #{}
                  (filter qualified-keyword?)
                  (concat (keys current) (keys desired)))
            owned-attributes)]
      (into
       []
       (comp
        (remove #{identity-attribute})
        (filter #(not= (get current %) (get desired %))))
       owned-attributes))
    []))

(defn changed-attributes
  "Owned non-identity attributes whose exact values differ.

  An attribute another writer owns (`:seon.program/written-by` on its entry)
  is never named here, so an exact re-index can never retract a fact the
  indexer did not write."
  {:malli/schema
   [:function
    [:=> [:cat :map :map] [:vector :keyword]]
    [:=> [:cat :seon.program/shapes :map :map] [:vector :keyword]]]}
  ([current desired] (changed-attributes-in (shapes) current desired))
  ([row-shapes current desired]
   (changed-attributes-in row-shapes current desired)))

(defn- replacement-tx
  [row-shapes current desired]
  (let [entity-id (:db/id current)
        changed (changed-attributes-in row-shapes current desired)]
    (into
     []
     (concat
      (map (fn [attribute]
             ;; Datahike validates tuple values before dispatching retraction.
             ;; Cardinality-many values are sets; validate one existing tuple,
             ;; not the set. retractAttribute still removes the entire attribute.
             ;; The current row comes from the writer's transaction database.
             (let [value (get current attribute)]
               [:db.fn/retractAttribute entity-id attribute
                (if (or (set? value)
                        (and (sequential? value) (sequential? (first value))))
                  (first value) value)]))
           (sort (filter #(contains? current %) changed)))
      [(assoc desired :db/id entity-id)]))))

(defn exact-replacement-tx-in
  "Replace one declaration row, owning it by the shapes `row-shapes` carries.

  The shape-carrying companion to [[exact-replacement-tx]]: a caller that
  derived its operation's shapes once hands them here and never asks a
  process-wide answer what its own operation already decided (AGENTS.md 2.1).
  It is a separate name rather than an arity because a newly added arity of an
  existing callable root is refused until every armed wrapper of it has been
  re-armed from a population that knows it."
  {:malli/schema
   [:=> [:cat :seon.program/shapes [:map [:db/id :int]] :map]
    [:vector :seon.schema/value]]}
  [row-shapes current desired]
  (replacement-tx row-shapes current desired))

(defn exact-replacement-tx
  "Replace one declaration row using current values and component retraction."
  {:malli/schema
   [:=> [:cat [:map [:db/id :int]] :map] [:vector :seon.schema/value]]}
  [current desired]
  (replacement-tx (shapes) current desired))

(defn deletion-row
  "Typed identities removed by one explicit REPL deletion event.

  `ns-unmap` removes the matching function and test identities. A reader-
  resolved `seon.schema/unregister!` removes one global schema identity."
  {:malli/schema
   [:=> [:cat :map] [:maybe :seon.program/deletion-row]]}
  [event]
  (let [form (:seon.sci.reader/form event)
        schema-key (:seon.sci.reader/schema-unregister-key event)
        removed-identities (:seon.sci.reader/ns-unmap-identities event)
        quoted-symbol
        (fn [value]
          (when (and (seq? value)
                     (= 'quote (first value))
                     (= 2 (count value))
                     (symbol? (second value)))
            (second value)))]
    (cond
      schema-key
      {:seon.program/delete-identities [[:seon.schema/key schema-key]]
       :seon.program/source (:seon.sci.reader/source event)}

      (seq removed-identities)
      (cond-> {:seon.program/delete-identities (vec removed-identities)
               :seon.program/source (:seon.sci.reader/source event)}
        (:seon.sci.reader/ns event)
        (assoc :seon.program/ns
               [:seon.ns/name (:seon.sci.reader/ns event)]))

      (:seon.sci.reader/ns-unmap? event)
      (when (and (seq? form)
                 (= 3 (count form)))
        (when-let [namespace-name (quoted-symbol (second form))]
          (when-let [declaration-name (quoted-symbol (nth form 2))]
            (let [qualified (symbol (str namespace-name)
                                    (str declaration-name))]
              {:seon.program/delete-identities
               [[:seon.fn/sym qualified]
                [:seon.test/sym qualified]]
               :seon.program/source (:seon.sci.reader/source event)
               :seon.program/ns [:seon.ns/name namespace-name]}))))

      :else nil)))
