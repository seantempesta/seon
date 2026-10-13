(ns seon.program
  "Pure declaration identities and exact-entity ownership for program entities."
  (:require [malli.core :as m]
            [clojure.set :as set]
            [seon.error.refusal :as error]
            [seon.fn.signature :as signature]
            [seon.id :as id]
            [seon.print :as print]
            [seon.schema :as schema]
            [malli.registry :as mr]
            [seon.schema.internal :as internal]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            ;; CLJ-only: `seon.schema.edn` has no CLJS side, and the two uses
            ;; below already sit inside a `#?(:clj …)` branch.
            #?(:clj [seon.schema.edn :as schema.edn])
            #?(:clj [seon.cache])
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]))

(defn- source-family?
  "Does this identity attribute's property map declare a source declaration
   family? Every identity attribute names its owning row schema; only a program
   family also names the attribute holding its source."
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [properties]
  (boolean (and (:seon.program/row-schema properties)
                (:seon.program/source-attribute properties))))

(def identity-attributes
  "Source declaration identities, derived from their authored row-schema links.
   Program components and other analysis rows are selected by program-attributes."
  (into (sorted-set)
        (keep (fn [[attribute definition]]
                (when (and (vector? definition)
                           (map? (second definition))
                           (source-family? (second definition)))
                  attribute)))
        #?(:clj (schema.edn/packaged-forms)
           :cljs (schema/registered-schemas))))

(defn- derived-program-attributes
  "The attributes program entity schemas in `projection` declare, derived
  from every schema form."
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

(defn program-attributes
  "Attributes declared by program entity schemas in this compiled projection.
   Other writers' entries are excluded. Schema-row properties come from the
   same bridge that projects them onto stored schema declarations."
  {:seon/tasks #{'seon.program/program-attributes-trust-any-partition}
   :malli/schema [:=> [:cat :seon.schema/projection] [:set :qualified-keyword]]}
  [projection]
  ;; Held by the projection, then by its schema forms and admissions: the
  ;; derivation reads only schema declarations, so a write that changes only
  ;; function contracts (every contracted defn) builds a new projection with
  ;; equal forms and reuses the answer instead of walking every schema again.
  (#?(:clj schema/projection-cache-value :cljs (fn [_ _ derive] (derive)))
   projection ::program-attributes
   #?(:clj #(seon.cache/lookup ::program-attributes
                               [(:seon.schema.projection/forms projection)
                                (:seon.schema.projection/schema-admissions projection)]
                               (fn [attributes] (* 64 (count attributes)))
                               (fn [] (derived-program-attributes projection)))
      :cljs #(derived-program-attributes projection))))

(defn- derived-cluster-attributes
  "Cluster entity attributes and their declared concurrent edit rules."
  {:malli/schema [:=> [:cat :seon.schema/projection] :seon.program/cluster-attributes]}
  [projection]
  (let [registry (:seon.schema.projection/registry projection)]
    (into {}
          (mapcat (fn [{schema-key :seon.schema/key}]
                    (let [compiled (mr/schema registry schema-key)
                          properties (internal/entity-properties compiled)]
                      (map (fn [[attribute entry-properties _]]
                             [attribute (get entry-properties :seon.cluster/conflict
                                             (get properties :seon.cluster/conflict :root))])
                           (internal/entity-entries compiled)))))
          (filter #(= :seon.cluster (:seon.program/partition %))
                  (:seon.schema.projection/catalog projection)))))

(defn cluster-attributes
  "Attributes copied at an attempt's successful end, with the rule for each."
  {:seon/tasks #{'seon.program/cluster-attributes-are-the-declared-cluster-entities}
   :malli/schema [:=> [:cat :seon.schema/projection] :seon.program/cluster-attributes]}
  [projection]
  (#?(:clj schema/projection-cache-value :cljs (fn [_ _ derive] (derive)))
   projection ::cluster-attributes
   #?(:clj #(seon.cache/lookup ::cluster-attributes
                               [(:seon.schema.projection/forms projection)
                                (:seon.schema.projection/schema-admissions projection)]
                               (fn [attributes] (* 96 (count attributes)))
                               (fn [] (derived-cluster-attributes projection)))
      :cljs #(derived-cluster-attributes projection))))

(defn- edn-symbol-text?
  "Whether `s` reads with clojure's EDN reader as one symbol token
  (EdnReader `read`, `readToken`, `interpretToken`, `matchSymbol` and its
  pattern `([\\D&&[^/]].*/)?(/|[\\D&&[^/]][^/]*)`).

  ## Edge cases

  - `\"a//\"` reads (name `/`); `\"a/b/\"`, `\"/x\"`, `\"a/1\"`, `\"+1\"`,
    `\"nil\"`, `\"a:\"`, `\"a::b\"` and `\"#a\"` do not."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String s]
  (let [n (.length s)
        last-slash (.lastIndexOf s "/")
        ;; the pattern's greedy namespace group ends at the separating slash;
        ;; a trailing `//` leaves the name `/`
        sep (if (and (> n 1) (== last-slash (dec n))) (dec last-slash) last-slash)
        line-break (.indexOf s "\u0085" 1)]
    (boolean
     (and (pos? n)
          ;; every character continues a token: no whitespace, comma,
          ;; terminating macro or non-constituent (`readToken`); inline,
          ;; since this runs per character of every edge name
          (loop [i 0]
            (or (== i n)
                (let [c (.charAt s i)]
                  (case c
                    (\" \; \^ \( \) \[ \] \{ \} \\ \@ \` \~ \,) false
                    (and (not #?(:clj (Character/isWhitespace c) :cljs (re-matches #"\s" c)))
                         (recur (inc i)))))))
          ;; not a number, dispatch or keyword start (`read`)
          (let [c0 (.charAt s 0)
                digit? #?(:clj #(Character/isDigit (char %)) :cljs #(re-matches #"[0-9]" %))]
            (not (or (digit? c0) (= \# c0) (= \: c0)
                     (and (or (= \+ c0) (= \- c0)) (> n 1) (digit? (.charAt s 1))))))
          (not (or (= "nil" s) (= "true" s) (= "false" s)))
          (or (neg? last-slash)
              (= "/" s)
              (and (pos? sep)
                   ;; the reader's namespace group ([\\D&&[^/]].*/)? cannot
                   ;; start with a slash: a leading slash is allowed only for
                   ;; the whole text "/", handled above
                   (not= \/ (.charAt s 0))
                   (= \/ (.charAt s sep))
                   (let [c (.charAt s (inc sep))] (not (and (<= (int \0) (int c)) (<= (int c) (int \9)))))
                   (not= \: (.charAt s (dec sep)))
                   (or (neg? line-break) (>= line-break sep))))
          (or (= "/" s) (not= \: (.charAt s (dec n))))
          (neg? (.indexOf s "::" 1))))))

(defn edn-round-trip-symbol?
  "Whether `value` is a symbol whose printed EDN reads back as that symbol.

  This total predicate is the storage boundary for program-graph edge names.
  It rejects symbols whose namespace begins with `:`, including symbols made
  from clj-kondo's `:clj-kondo/unknown-namespace` sentinel: they print as
  keywords, so an EDN boundary changes their type. It checks the symbol's
  text against the EDN reader's symbol grammar without printing or reading."
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
  (boolean
   (and (symbol? value)
        (let [s (str value) ns (namespace value) slash (.indexOf ^String s "/")]
          (and (edn-symbol-text? s)
               ;; the reader splits at the first slash (Symbol/intern)
               (if ns
                 (and (pos? (count ns)) (= slash (count ns)))
                 (or (neg? slash) (= "/" s))))))))

#?(:clj
   (defn overrides
     "Return current agent-admitted function identities in indexed src namespaces.

      Namespace membership follows recorded declaration/file relations, including
      history: replacing a declaration removes its file coordinates, and replacing
      the last indexed function must not make its namespace cease to be first-party.
      Both current facts and history derive from the supplied database value.
      An empty vector means this database has no overrides. JVM callers continue
      running their compiled definitions until write-back and reload.

      ## Example

      ```clojure
      (seon.program/overrides (seon.db/db connection))
      ```"
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
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.program/identity-attribute]
                  :seon.program/shape]}
  [projection identity-attribute]
  (let [refuse!
        (fn [message data]
          (declaration-refused!
           message [[identity-attribute nil]]
           (merge {:seon.program/identity-attribute identity-attribute} data)))
        registry (:seon.schema.projection/registry projection)
        properties (some-> (mr/schema registry identity-attribute) m/properties)
        entity-schema (:seon.program/row-schema properties)
        _ (when-not (qualified-keyword? entity-schema)
            (refuse! "A program identity attribute declares no entity schema."
                     {:seon.program/missing-attributes [:seon.program/row-schema]}))
        source-attribute (:seon.program/source-attribute properties)
        _ (when-not (qualified-keyword? source-attribute)
            (refuse! "A program identity attribute declares no source attribute."
                     {:seon.program/missing-attributes
                      [:seon.program/source-attribute]}))
        definition (mr/schema registry entity-schema)
        _ (when-not (and definition (internal/entity-schema? definition))
            (refuse! "A program entity schema is not a declared entity map."
                     {:seon.program/row-schema entity-schema}))
        entries (internal/entity-entries definition)
        owned (into [] (comp (filter #(nil? (:seon.program/written-by
                                             (entry-properties %))))
                             (keep entry-attribute))
                    entries)]
    (when-not (some #{identity-attribute} owned)
      (refuse! "A program entity schema does not declare its own identity attribute."
               {:seon.program/row-schema entity-schema}))
    {:seon.program/identity-attribute identity-attribute
     :seon.program/source-attribute source-attribute
     :seon.program/set-attributes
     #?(:clj (let [vector-shaped? (requiring-resolve 'seon.schema.datahike/vector-shaped-attr-in?)]
               (into #{}
                   (keep (fn [{:db/keys [ident cardinality isComponent]}]
                           (when (and (= :db.cardinality/many cardinality) (not isComponent)
                                      (not (vector-shaped? projection ident))) ident)))
                   ((requiring-resolve 'seon.schema.datahike/malli->datahike-schema-in)
                    projection owned)))
        :cljs #{})
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
  ;; A function of the projection value: one walk of its forms per projection,
  ;; never one per caller (a contracted defn asked ~10 times per form).
  (#?(:clj schema/projection-cache-value :cljs (fn [_ _ derive-fn] (derive-fn)))
   projection ::shapes
   (fn []
     (into {}
           (map (fn [identity-attribute]
                  [identity-attribute (derived-shape projection identity-attribute)]))
           (filter #(source-family? (some-> (mr/schema (:seon.schema.projection/registry projection) %)
                                            m/properties))
                   (keys (:seon.schema.projection/forms projection)))))))

;; Process-cache weight: bytes the authored shapes keep alive per entity family,
;; their projection built (bench/seon/cache_kind_measurement.edn, 2026-09-27:
;; 244 KB / 6 families).
(def shape-bytes-per-family 40600)

(defn- authored-shapes
  "Program shapes from the current authored declarations, retained by their content."
  {:malli/schema [:=> [:cat] :seon.program/shapes]}
  []
  #?(:clj
     (let [forms (schema.edn/packaged-forms)]
       (seon.cache/lookup ::authored-shapes forms
                          #(* shape-bytes-per-family (count %))
                          #(shapes-in (schema/declaration-projection forms))))
     :cljs (shapes-in (schema/declaration-projection (schema/registered-schemas)))))

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
  ([entity-shapes identity-attribute] (get entity-shapes identity-attribute)))

(defn row-identity
  "The `[identity-attribute value]` pair carried by `entity`."
  {:malli/schema
   [:=> [:cat [:maybe :map]]
    [:maybe :seon.program/identity]]}
  [entity]
  (some (fn [identity-attribute]
          (when-some [value (get entity identity-attribute)]
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

(defn- source-resolver-names
  "The candidate alias and refer names spelled by `source`."
  {:malli/schema [:=> [:cat :string [:set :symbol] [:set :symbol]]
                  [:map [:qualifiers [:set :symbol]] [:symbols [:set :symbol]]]]}
  [source qualifiers symbols]
  (let [derive
        (fn []
          (reduce (fn [names n]
                    (let [{:keys [value k auto-resolved? prefix]} n
                          qualifier (cond
                                      (symbol? value) (some-> value namespace symbol)
                                      (and auto-resolved? (keyword? k)) (some-> k namespace symbol)
                                      (and auto-resolved? prefix) (symbol prefix))]
                      (cond-> names
                        (contains? qualifiers qualifier) (update :qualifiers conj qualifier)
                        (and (symbol? value) (nil? (namespace value)) (contains? symbols value))
                        (update :symbols conj value))))
                  {:qualifiers #{} :symbols #{}}
                  (remove node/inner? (tree-seq node/inner? node/children (parser/parse-string-all source)))))]
    #?(:clj
       (seon.cache/lookup
        ::source-resolver-names (id/id [source qualifiers symbols] 64)
        (fn [names]
          (+ 256 (reduce (fn [bytes sym] (+ bytes 128 (* 2 (count (name sym)))))
                         0 (concat (:qualifiers names) (:symbols names)))))
        derive)
       :cljs (derive))))

(defn- used-resolver-context
  "The part of a namespace's resolver context `row`'s own usages resolve
  through: requires of the namespaces its calls, references and keywords
  name, the aliases its source spells as qualifiers and the refers it spells
  as bare symbols (by target namespace and used symbol when it carries no
  source). Imports stay whole: the analysis records no class usage. An alias
  or refer the definition does not spell therefore leaves its digest
  unchanged, while retargeting one it spells changes the kept entry."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [entity {:keys [requires aliases refers imports]}]
  (let [used (into (set (:seon.fn/calls entity)) (:seon.fn/references entity))
        used-namespaces (into #{} (keep #(some-> % namespace symbol))
                              (concat used (:seon.fn/keywords entity)))
        source (some->> (row-identity entity) first shape :seon.program/source-attribute (get entity))
        {:keys [qualifiers symbols]}
        (when (string? source)
          (source-resolver-names source (set (keys aliases)) (set (keys refers))))]
    {:requires (into #{} (filter used-namespaces) requires)
     :aliases (into {} (filter (if qualifiers (comp qualifiers key) (comp used-namespaces val))) aliases)
     :refers (into {} (filter (if symbols (comp symbols key) (comp used val))) refers)
     :imports (or imports {})}))

(defn definition-digest
  "Digest one declaration's identity and effective meaning.

   The digest includes authored definition, the resolver context its usages
   resolve through ([[used-resolver-context]]) and effective semantic
   metadata. Storage provenance, position and derived graph observations are
   deliberately excluded."
  {:malli/schema
   [:function
    [:=> [:cat :map] :seon.program/definition-digest]
    [:=> [:cat :map [:maybe :map]] :seon.program/definition-digest]]}
  ([entity] (definition-digest entity nil))
  ([entity resolver-context]
   (let [parts
         (cond-> {:seon.program/declaration
                  (apply dissoc entity definition-digest-excluded-attributes)}
           resolver-context
           (assoc :seon.program/resolver-context
                  (used-resolver-context entity resolver-context)))]
     (id/id parts 64))))

(defn- without-docstring
  "`source` without its def form's docstring literal `doc`: the form's third
  child when it reads as exactly `doc` and another form follows it; `source`
  itself otherwise."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [source doc]
  (let [form (parser/parse-string source)
        children (vec (node/children form))
        forms (into [] (remove #(node/whitespace-or-comment? (nth children %))) (range (count children)))
        at (get forms 2)
        candidate (some->> at (nth children))]
    (if (and candidate (< 3 (count forms)) (node/sexpr-able? candidate) (= doc (node/sexpr candidate)))
      (node/string (node/replace-children form (into (subvec children 0 at) (subvec children (inc at)))))
      source)))

(defn behavior-digest
  "The [[definition-digest]] of `entity` without its documentation: the
  docstring, its ordering and the docstring literal in its source. Equal to
  the definition digest for a row with no `:seon.fn/doc`. Test selection and
  reuse key on it: a test cannot observe a docstring."
  {:malli/schema [:=> [:cat :map [:maybe :map]] :seon.program/definition-digest]}
  [entity resolver-context]
  (if-let [doc (:seon.fn/doc entity)]
    (definition-digest (cond-> (dissoc entity :seon.fn/doc :seon.fn/doc-order)
                         (:seon.fn/source entity) (update :seon.fn/source without-docstring doc))
                       resolver-context)
    (definition-digest entity resolver-context)))

#?(:clj
   (defn digest-map-refusal
     "The typed unknown `digest-map` answers when its evidence is incomplete."
     {:malli/schema [:=> [:cat :seon.program/missing-evidence :string :map]
                     :seon.program/digest-map-refusal]}
     [missing message members]
     (error/error
      :seon.program/digest-map-refusal
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
          (let [entity-schema (some-> (mr/schema registry attribute)
                                   m/properties :seon.program/row-schema)
                definition (some->> entity-schema (mr/schema registry))
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
              :else :seon.program/unclassified-families)))]
    (#?(:clj schema/projection-cache-value :cljs (fn [_ _ derive-fn] (derive-fn)))
     projection ::declaration-families
     (fn []
       (reduce (fn [result attribute]
                 (update result (classify attribute) conj attribute))
               {:seon.program/compared-families #{}
                :seon.program/recomputed-families #{}
                :seon.program/unclassified-families #{}}
               (keys (shapes-in projection)))))))

(defn- namespace-stub?
  "Whether an undigested row of identity attribute `a` is no declaration: a
  namespace row the turn writes as its identity alone, for the evaluations
  that ran in it (`seon.turn/namespace-rows`), until a fold declares it."
  {:malli/schema [:=> [:cat :qualified-keyword] :boolean]}
  [a]
  (= :seon.ns/name a))

#?(:clj
   (defn- declaration-digest-map
     "Read every compared program declaration and its stored definition digest.

  The families come from `database`'s own carried projection, so a branch
  compares the families its declarations define. Recomputed families (file
  and lint rows, derived from bytes and analysis) are not compared. Incomplete
  evidence answers a typed refusal, never a partial map: an unclassified
  family, an unreadable index page, a compared declaration without its digest,
  or more than `:seon.program/max-datoms` index datoms. A namespace stub
  (`namespace-stub?`) is absent, as a removed namespace is. Supplied
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
                   entities (q '[:find ?a ?v ?e :in $ [[?a ?v] ...] :where [?e ?a ?v]]
                           database (vec identities))
                   stored (if (:seon.error/at entities) entities
                            (into {} (q '[:find ?e ?d :in $ [?e ...] :where
                                          [(get-else $ ?e :seon.program/definition-digest "") ?d]]
                                        database (mapv peek entities))))
                   ;; a namespace row without a digest is a turn's reference
                   ;; stub, not a declaration: absent from the map
                   entities (if (:seon.error/at stored) entities
                              (remove (fn [[a _ e]] (and (= "" (stored e)) (namespace-stub? a))) entities))
                   missing (when-not (:seon.error/at stored)
                             (into #{} (keep (fn [[a v e]] (when (= "" (stored e)) [a v]))) entities))]
               (cond
                 (:seon.error/at stored)
                 (digest-map-refusal :seon.program/read "A program declaration could not be read."
                                     {:seon.error/offending stored})
                 (seq missing)
                 (digest-map-refusal :seon.program/definition-digest
                                     "Program declarations are missing their stored definition digest."
                                     {:seon.error/expected-key :seon.program/definition-digest
                                      :seon.program/undigested-declarations missing})
                 :else (into {} (map (fn [[a v e]] [[a v] (stored e)])) entities)))

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
                       identities (remove #(and (namespace-stub? (:a %)) (not (contains? digests (:e %))))
                                          (mapcat #(get-in ranges [:ranges %]) (sort compared)))
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
   (defn- declaration-changed-identities
     "Declarations whose digest `database` asserted or retracted after `base`
  (a value, or a basis-t on `database`'s own lineage),
  deleted ones named by history: `digest-map`'s scoping identities. Only
  functions, tests, namespaces and schemas may change; another or no family, missing history and
  more than `:seon.program/max-datoms` changed entities refuse by name.
  Datahike indexes no transaction prefix: this walks the digest's history."
     {:malli/schema
      [:=> [:cat [:or :seon.db/database-value :seon.db/basis-t] :seon.db/database-value
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
                         (since past (if (int? base) base (basis-t base)))))
           entity-maps (if (:seon.error/at entities) entities
                  (q '[:find ?e ?a ?v :in $ [?e ...] [?a ...] :where [?e ?a ?v]]
                     past entities (vec (:seon.program/compared-families
                                         (declaration-families (carried database))))))
           identities (when-not (:seon.error/at entity-maps) (into #{} (map (comp vec rest)) entity-maps))
           outside (into #{} (remove (comp #{:seon.fn/sym :seon.test/sym :seon.ns/name :seon.schema/key} first)) identities)]
       (cond
         (:seon.error/at entity-maps)
         (refuse :seon.program/history "The changes since the base need readable history."
                 {:seon.error/offending entity-maps})
         (< max-datoms (count entities))
         (refuse :seon.program/read-bound "The changes since the base exceed their datom bound."
                 {:seon.program/max-datoms max-datoms})
         (not= (set entities) (set (map first entity-maps)))
         (refuse :seon.program/family-classification
                 "A changed digest belongs to no compared declaration family."
                 {:seon.error/expected-key :seon.program/declaration-identity})
         (seq outside)
         (refuse :seon.program/scope "Only function, test, namespace and schema declarations may change here."
                 {:seon.error/offending outside})
         :else identities))))

#?(:clj
   (defn projected-values
     "Stored value sets at selected entity/attribute coordinates, with collision refs remapped."
     {:malli/schema [:=> [:cat :seon.db/database-value [:set :seon.program/identity]
                         [:map-of :seon.db/entity-id :seon.db/entity-id]]
                     [:map-of :seon.program/identity [:set :seon.schema/value]]]}
     [database coordinates remap]
     (let [datoms (requiring-resolve 'seon.db/undecoded-datoms)]
       (into {}
             (keep (fn [[attribute entity]]
                     (let [ref? (= :db.type/ref (get-in database [:schema attribute :db/valueType]))
                           values (into #{} (map (fn [[_ _ value]]
                                                   (if ref? (get remap value value) value)))
                                        (datoms database :eavt [entity attribute]))]
                       (when (seq values)
                         [[attribute (get remap entity entity)] values]))))
             coordinates))))

#?(:clj
   (defn digest-map
     "Stored declaration digests, or value digests under an explicit attribute projection."
     {:malli/schema
      [:function
       [:=> [:cat :seon.db/database-value :map]
        [:or :seon.program/digest-map :seon.program/digest-map-refusal]]
       [:=> [:cat :seon.db/database-value
             [:map [:seon.program/identities [:set :seon.program/identity]]
              [::remap {:optional true} [:map-of :seon.db/entity-id :seon.db/entity-id]]]
             [:set :qualified-keyword]]
        [:map-of :seon.program/identity :seon.program/definition-digest]]]}
     ([database bounds] (declaration-digest-map database bounds))
     ([database {coordinates :seon.program/identities remap ::remap} attributes]
      (into {} (map (fn [[coordinate values]] [coordinate (id/id values 64)]))
            (projected-values database (into #{} (filter #(attributes (first %))) coordinates)
                              (or remap {}))))))

#?(:clj
   (defn changed-identities
     "Changed declaration identities, or entity/attribute coordinates under a projection."
     {:malli/schema
      [:function
       [:=> [:cat [:or :seon.db/database-value :seon.db/basis-t] :seon.db/database-value :map]
        [:or :seon.program/identity-set :seon.program/digest-map-refusal]]
       [:=> [:cat [:or :seon.db/database-value :seon.db/basis-t] :seon.db/database-value :map
             [:set :qualified-keyword]] [:set :seon.program/identity]]]}
     ([base database bounds] (declaration-changed-identities base database bounds))
     ([base database _bounds attributes]
      (let [[basis changed-attributes entities-changed-after]
            (map requiring-resolve '[seon.db/basis-t seon.db/changed-attributes
                                     seon.db/entities-changed-after])
            since (if (int? base) base (basis base))]
        (into #{} (mapcat (fn [attribute]
                           (map (fn [entity] [attribute entity])
                                (entities-changed-after database since attribute))))
              (changed-attributes base database attributes))))))

(defn three-way
  "Classify branch and head declaration identities relative to base.

   Absence is a state. Equal branch/head states are unchanged, including equal
   additions, edits and retractions. Conflicts retain all three states."
  {:malli/schema
   [:=> [:cat [:map-of :seon.program/identity :seon.program/definition-digest]
         [:map-of :seon.program/identity :seon.program/definition-digest]
         [:map-of :seon.program/identity :seon.program/definition-digest]]
    [:map
     [:seon.program/unchanged [:set :seon.program/identity]]
     [:seon.program/changed-on-branch [:set :seon.program/identity]]
     [:seon.program/changed-on-head [:set :seon.program/identity]]
     [:seon.program/conflict [:set :seon.program/identity]]
     [:seon.program/added [:set :seon.program/identity]]
     [:seon.program/retracted [:set :seon.program/identity]]
     [:seon.program/conflict-digests [:map-of :seon.program/identity
       [:map [:seon.program/base-digest :seon.program/digest-or-absence]
             [:seon.program/branch-digest :seon.program/digest-or-absence]
             [:seon.program/head-digest :seon.program/digest-or-absence]]]]]]}
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

(defn- entity-identities
  [entity]
  (into []
        (keep (fn [identity-attribute]
                (when-some [value (get entity identity-attribute)]
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

(declare binding-entity)

(defn- binding-child
  "One positional child of a destructuring binding, as a component row."
  {:malli/schema [:=> [:cat :symbol :int :int [:vector :seon.schema/value] :int :keyword :seon.schema/value] :map]}
  [function-symbol arity-order argument-index path order role binding]
  {:db/id (binding-id function-symbol arity-order argument-index
                      (conj path :child order))
   :seon.fn.binding.child/role role
   :seon.fn.binding.child/binding
   (binding-entity function-symbol arity-order argument-index
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
  "A map binding's entries in canonical order, each carrying its position for
   its component id (never stored: the entries vector keeps the order)."
  {:malli/schema [:=> [:cat :map] [:sequential :map]]}
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
         (sort-by (juxt :seon.fn.binding.entry/source-key
                        :seon.fn.binding.entry/spelling
                        :seon.fn.binding.entry/local-binding)
                  print/compare-values)
         (map-indexed
          (fn [order entry]
            (let [local-binding (:seon.fn.binding.entry/local-binding entry)]
              ;; The position names the entry's component id only; the vector keeps its order.
              (cond-> (assoc entry ::entry-position (long order))
                (and (symbol? local-binding)
                     (contains? defaults local-binding))
                (assoc :seon.fn.binding.entry/default-edn
                       (pr-str (get defaults local-binding)))))))
         (#(with-meta % {:seon.fn.binding/as as-binding})))))

;; A map-entry key's typed facts (`seon.schema.map-entry`): one value
;; attribute per EDN key kind, so a binding key is queryable by its own type.
(defn- key-kind
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema.map-entry/key-kind]}
  [value]
  (cond
    (nil? value) :nil
    (keyword? value) :keyword
    (string? value) :string
    (symbol? value) :symbol
    (boolean? value) :boolean
    (integer? value) :int
    (double? value) :double
    (uuid? value) :uuid
    (inst? value) :inst
    (vector? value) :vector
    (map? value) :map
    (set? value) :set
    (sequential? value) :sequential
    :else
    (throw
     (ex-info "Schema map entry has an unsupported non-EDN key."
              {:seon.schema.map-entry/key-edn (pr-str value)}))))

(defn- typed-key-facts
  "Typed database facts for one Malli map-entry key."
  {:malli/schema [:=> [:cat :seon.schema/value] :map]}
  [value]
  (let [kind (key-kind value)
        base {:seon.schema.map-entry/key-kind kind
              :seon.schema.map-entry/key-fingerprint (id/id value 64)
              :seon.schema.map-entry/key-edn (pr-str value)}]
    (case kind
      :keyword (assoc base :seon.schema.map-entry/key-keyword value)
      :string (assoc base :seon.schema.map-entry/key-string value)
      :symbol (assoc base :seon.schema.map-entry/key-symbol value)
      :boolean (assoc base :seon.schema.map-entry/key-boolean value)
      :int (assoc base :seon.schema.map-entry/key-int (long value))
      :double (assoc base :seon.schema.map-entry/key-double value)
      :uuid (assoc base :seon.schema.map-entry/key-uuid value)
      :inst (assoc base :seon.schema.map-entry/key-inst value)
      base)))

(defn- binding-entry-entity
  "One map-binding entry as a component row."
  {:malli/schema [:=> [:cat :symbol :int :int [:vector :seon.schema/value] :map] :map]}
  [function-symbol arity-order argument-index path
   {order ::entry-position
    spelling :seon.fn.binding.entry/spelling
    source-key :seon.fn.binding.entry/source-key
    local-binding :seon.fn.binding.entry/local-binding
    default-edn :seon.fn.binding.entry/default-edn}]
  (cond->
   (merge
    {:db/id (binding-id function-symbol arity-order argument-index
                        (conj path :entry order))
     :seon.fn.binding.entry/spelling spelling
     :seon.fn.binding.entry/binding
     (binding-entity function-symbol arity-order argument-index
                  (conj path :entry order :binding) local-binding)}
    (typed-key-facts source-key))
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

(defn- binding-entity
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
               (mapv #(binding-entry-entity function-symbol arity-order
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
                :seon.error/operation 'seon.program/binding-entity
                :seon.error/message "Function binding is unsupported; use a symbol, map or sequential binding."
                :seon.error/offending binding
                :seon.program/binding-function function-symbol
                :seon.program/binding-argument argument-index
                :seon.error/member :seon.fn.binding/form
                :seon.error/expected :seon.fn.binding/row}))))

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

(defn- argument-entity
  "One argument of an arity as a component row; its position is its place in
   the arity's arguments vector."
  {:malli/schema [:=> [:cat :symbol :int :map :int :map] :map]}
  [function-symbol arity-order source-signature order
   {label :seon.fn.argument/label}]
  (let [bindings (:seon.fn.signature/bindings source-signature)
        rest-index (:seon.fn.signature/rest-index source-signature)]
    (cond->
     {:db/id (component-id function-symbol [:arity arity-order :argument order])
      :seon.fn.argument/rest? (= order rest-index)
      :seon.fn.argument/binding
      (binding-entity function-symbol arity-order order [] (nth bindings order))}
      (some? label) (merge (label-facts label)))))

(defn- arity-entity
  "One stored arity row. `references` are this arity's `[input output guard]`
   reference sets, derived with the compiled contract in its own compilation
   world ([[seon.schema/contract-references]]); they are never re-derived here.
   Its position is its place in the function's arities vector."
  {:malli/schema [:=> [:cat :symbol :int :map [:sequential :seon.schema/value] :map] :map]}
  [function-symbol order info references source-signature]
  (let [[input-refs output-refs guard-refs] references
        slots (input-slots (:input info))
        expected-count (count (:seon.fn.signature/bindings source-signature))
        _ (when-not (= expected-count (count slots))
            (throw
             (ex-info "A source binding has no matching Malli input slot."
                      {:seon.error/at #?(:clj (java.util.Date.) :cljs (js/Date.))
                        :seon.error/layer :seon.program/declaration
                        :seon.error/operation 'seon.program/arity-entity
                        :seon.error/member function-symbol
                        :seon.error/message "Source bindings and contract slots differ; declare one slot per binding."
                        :seon.error/offending source-signature
                        :seon.program/signature-member :seon.fn.arity/argument-count
                        :seon.program/signature-count (count slots)
                        :seon.error/data {:seon.fn.signature/source-count expected-count}
                        :seon.error/expected expected-count})))
        arguments (mapv (fn [argument-order slot]
                          (argument-entity function-symbol order source-signature
                                        argument-order slot))
                        (range) slots)]
    (cond->
     {:db/id (component-id function-symbol [:arity order])
      :seon.fn.arity/arity (pr-str (:arity info))
      :seon.fn.arity/min (long (:min info))
      :seon.fn.arity/argument-count (long (count arguments))}
      ;; Absence is no key: storage keeps no empty component set, so an empty
      ;; vector here differed from every stored zero-argument arity and made
      ;; each republished one a signature change that relinted all callers.
      (seq arguments) (assoc :seon.fn.arity/arguments arguments)
      (contains? info :max) (assoc :seon.fn.arity/max (long (:max info)))
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
    reader-namespace :seon.program/reader-namespace
    reader-aliases :seon.program/reader-aliases}]
  (let [;; The compiled contract and its references come as ONE product,
        ;; derived in the same world (contracts-out §4, pairing rule).
        {compiled :seon.schema/compiled references :seon.schema/references}
        (schema/contract-product
         (schema/with-predicate-functions
          {:seon.schema.projection/registry (:registry compile-options)
           :seon.schema.projection/canonical-keys canonical-keys}
          predicate-functions)
         {:seon.schema/form (read-edn spec) :seon.schema/mode :shape})
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
                  :seon.error/member function-symbol
                  :seon.error/message "Source and contract arities differ; give each source arity a matching contract."
                  :seon.error/offending malli-keys
                  :seon.program/signature-member :seon.fn/arities
                  :seon.program/signature-count (count malli-keys)
                  :seon.error/expected source-keys})))
    (cond->
     {:seon.fn/arities
      (mapv (fn [order arity references]
              (let [info (m/-function-info arity)]
                (arity-entity function-symbol order info references
                           (get source-by-key (malli-arity-key info)))))
            (range)
            arities
            references)}
      override? (assoc :seon.fn/arglists-override? true))))

(defn with-contract-facts
  "Add parsed facts to one canonical contracted function entity."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.program/row :seon.program/declaration-row]
      [:seon.program/compile-options :map]
      [:seon.program/predicate-functions :map]
      [:seon.program/schema-keys [:set :keyword]]]]
    :seon.program/declaration-row]}
  [{entity :seon.program/row
    compile-options :seon.program/compile-options
    predicate-functions :seon.program/predicate-functions
    canonical-keys :seon.program/schema-keys
    reader-aliases :seon.program/reader-aliases}]
  (cond
    (:seon.fn/spec entity)
    (merge entity
           (contract-facts
            (cond->
             {:seon.program/function-symbol (:seon.fn/sym entity)
              :seon.program/spec (:seon.fn/spec entity)
              :seon.program/source (:seon.fn/source entity)
              :seon.program/compile-options compile-options
              :seon.program/predicate-functions predicate-functions
              :seon.program/schema-keys canonical-keys
              :seon.program/reader-namespace
              (second (:seon.fn/ns entity))
              :seon.program/reader-aliases (or reader-aliases {})}
              (:seon.fn/arglists entity)
              (assoc :seon.program/arglists (:seon.fn/arglists entity)))))

    :else entity))

(defn- canonical-entity-in
  [entity-shapes entity]
  (let [identities (entity-identities entity)]
    (when (> (count identities) 1)
      (declaration-refused!
       "A declaration entity carries more than one identity family."
       identities
       {}))
    (when-let [[identity-attribute _] (first identities)]
      (let [owned-attributes
            (:seon.program/owned-attributes (get entity-shapes identity-attribute))
            owned-attributes
            (if (= :seon.program/schema-row-properties owned-attributes)
              (into [] (filter qualified-keyword?) (keys entity))
              owned-attributes)]
        ;; an empty collection is absence: Datahike stores nothing for it
        (into {}
              (remove (fn [[_ value]] (or (nil? value) (and (coll? value) (empty? value)))))
              (select-keys entity owned-attributes))))))

(defn canonical-row
  "The exact non-nil, non-empty attributes owned by one declaration row.

  Ownership comes from the family's declared entity map (see [[shapes-in]]),
  so an attribute declared there is kept without any code change here. The
  explicit-shapes arity is the honest one for a caller that already derived
  its operation's shapes: it asks nothing per row."
  {:malli/schema
   [:function
    [:=> [:cat [:maybe :map]] [:maybe :map]]
    [:=> [:cat :seon.program/shapes [:maybe :map]] [:maybe :map]]]}
  ([entity] (canonical-entity-in (shapes) entity))
  ([entity-shapes entity] (canonical-entity-in entity-shapes entity)))

(defn- canonical-namespace-components
  "Namespace components in the one shape `:seon.ns/ns` declares.

  A reader event names required namespaces as bare symbols in reading order
  and carries its component collections as vectors; the persisted row holds
  the same facts as sets, with each requirement a `:seon.ns/name` lookup ref.
  The evaluator also builds these components directly from SCI's namespace
  table, so this is idempotent over an already canonical row."
  [entity]
  (cond-> entity
    (:seon.ns/requires entity)
    (update :seon.ns/requires set)
    (:seon.ns/aliases entity) (update :seon.ns/aliases set)
    (:seon.ns/imports entity) (update :seon.ns/imports set)
    (:seon.ns/refers entity) (update :seon.ns/refers set)))

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
                entity (some #(when (= schema-key (:seon.schema/key %)) %)
                          (schema/canonical-schema-entities
                           candidate-projection {schema-key definition}))]
            (cond-> (assoc (merge (select-keys candidate
                                              [:seon.schema/generatable?])
                                 entity)
                           :seon.schema.admission/source
                           (:seon.schema.admission/source candidate))
              (:seon.schema/ns candidate)
              (assoc :seon.schema/ns (:seon.schema/ns candidate))))

          :else candidate)
        entity-shapes (shapes-in projection)
        candidate (canonical-row entity-shapes candidate)
        ;; Only a namespace or schema digest is its entity alone. A function or
        ;; test digest includes its namespace's resolver context, which this
        ;; stage does not hold: `seon.db/definition-digest-of` supplies it, and
        ;; an undigested entity refuses at the writer by the missing key.
        candidate
        (if (and candidate
                 (or (:seon.ns/name candidate)
                     (:seon.schema/key candidate))
                 (nil? (:seon.program/definition-digest candidate)))
          (assoc candidate :seon.program/definition-digest
                 (definition-digest candidate))
          candidate)
        entity candidate]
    (when entity
      (let [[identity-attribute _ :as program-identity] (row-identity entity)
            source-attribute (:seon.program/source-attribute
                              (shape entity-shapes identity-attribute))]
        (when-not (get entity source-attribute)
          (declaration-refused!
           "A reader declaration has no source. Analysis has not run at this stage."
           [program-identity]
           {:seon.program/missing-attributes [source-attribute]}))))
    entity))

(defn- changed-attributes-in
  [entity-shapes current desired]
  (if-let [[identity-attribute _]
           (or (row-identity desired) (row-identity current))]
    (let [owned-attributes
          (:seon.program/owned-attributes (get entity-shapes identity-attribute))
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
  ([entity-shapes current desired]
   (changed-attributes-in entity-shapes current desired)))

(defn- replacement-tx
  "Retract removed set members, preserving unchanged members and component cascades."
  {:malli/schema [:=> [:cat :seon.program/shapes [:map [:db/id :int]] :map]
                  [:vector :seon.schema/value]]}
  [entity-shapes current desired]
  (let [entity-id (:db/id current)
        changed (changed-attributes-in entity-shapes current desired)
        many (get-in entity-shapes [(first (row-identity desired)) :seon.program/set-attributes] #{})]
    (conj
     (vec
      (mapcat (fn [attribute]
              (if (many attribute)
                (map #(vector :db/retract entity-id attribute %)
                     (set/difference (set (get current attribute)) (set (get desired attribute))))
                (when (contains? current attribute)
             ;; Datahike validates tuple values before dispatching retraction.
             ;; Cardinality-many values are sets; validate one existing tuple,
             ;; not the set. retractAttribute still removes the entire attribute.
             ;; The current entity comes from the writer's transaction database.
             (let [value (get current attribute)]
               [[:db.fn/retractAttribute entity-id attribute
                (if (or (set? value)
                        (and (sequential? value) (sequential? (first value))))
                  (first value) value)]]))))
              (sort changed)))
     (reduce (fn [row attribute]
               (let [added (set/difference (set (get desired attribute)) (set (get current attribute)))]
                 ;; Absence is no key: retractions are already emitted above.
                 (if (empty? added) (dissoc row attribute) (assoc row attribute added))))
             (assoc desired :db/id entity-id) many))))

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
  [entity-shapes current desired]
  (replacement-tx entity-shapes current desired))

(defn exact-replacement-tx
  "Replace one declaration entity using current values and component retraction."
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
