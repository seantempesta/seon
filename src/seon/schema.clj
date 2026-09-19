(ns seon.schema
  "Malli declaration and runtime-projection boundary for Seon.

   Canonical schema forms are database facts. During module loading this
   namespace collects compiled declarations; after database reconciliation it
   validates and activates one immutable projection of those facts.

   Namespaces declare schemas here with `register!`, making them
   available for `:malli/schema` fn validation, generative testing, and
   runtime validation. The `::` syntax expands to the current namespace,
   so `::user-id` in `seon.trading.core` becomes
   `:seon.trading.core/user-id`.

     (require '[seon.schema :as schema])
     (schema/register! ::user-id   :uuid)
     (schema/register! ::user-name [:string {:min 1 :max 200}])

   Reusable form inspection lives in `seon.schema.form`; register!-time gates
   live in `seon.schema.internal`, outside agent context."
  (:require [malli.core :as m]
            [seon.id :as id]
            [malli.registry :as mr]
            [clojure.core.reducers :as reducers]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [datahike.api :as d]
            [datahike.pull-api :as pull-api]
            [datahike.db.interface :as dbi]
            [datahike.db.utils :as db-utils]
            [seon.schema.form :as form]
            [seon.schema.internal :as internal]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;;; LOAD-CYCLE BOUNDARIES. `seon.schema.edn`, `seon.error` and
;;; `seon.schema.datahike` all require `seon.schema`, so this namespace
;;; cannot require them back. One resolution per var, realized at first use,
;;; instead of a `requiring-resolve` on every call (AGENTS §2.1).
(defonce ^:private schema-edn-packaged-forms
  (delay (requiring-resolve 'seon.schema.edn/packaged-forms)))
(defonce ^:private error-diagnostic
  (delay (requiring-resolve 'seon.error/diagnostic)))
(defonce ^:private schema-datahike-storable-attribute-in?
  (delay (requiring-resolve 'seon.schema.datahike/storable-attribute-in?)))
(defonce ^:private schema-datahike-storable-properties-in
  (delay (requiring-resolve 'seon.schema.datahike/storable-properties-in)))
(defonce ^:private schema-datahike-database-attributes-in
  (delay (requiring-resolve 'seon.schema.datahike/database-attributes-in)))
(defonce ^:private schema-datahike-malli->datahike-attr-in
  (delay (requiring-resolve 'seon.schema.datahike/malli->datahike-attr-in)))
(defonce ^:private schema-datahike-form->cardinality-in
  (delay
    (require 'seon.schema.datahike)
    (ns-resolve 'seon.schema.datahike 'form->cardinality-in)))
(defonce ^:private schema-datahike-form->child-form-in
  (delay
    (require 'seon.schema.datahike)
    (ns-resolve 'seon.schema.datahike 'form->child-form-in)))

(defn- direct-references*
  "Canonical registry keys directly referenced by one compiled schema.

   Malli's own walker distinguishes references from keyword data. Canonical
   refs are recorded but not followed; local property-registry refs are
   followed so canonical refs nested behind them are still visible."
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

(defn- reference-cycle
  "First deterministic cycle in `graph` reachable from `roots`, or nil."
  [graph roots]
  (letfn [(visit [node path positions visited]
            (if-some [cycle-start (get positions node)]
              [(conj (subvec path cycle-start) node) visited]
              (if (contains? visited node)
                [nil visited]
                (let [path (conj path node)
                      positions (assoc positions node (dec (count path)))]
                  (loop [references (sort-by str (get graph node #{}))
                         visited visited]
                    (if-let [reference (first references)]
                      (let [[cycle visited]
                            (visit reference path positions visited)]
                        (if cycle
                          [cycle visited]
                          (recur (next references) visited)))
                      [nil (conj visited node)]))))))]
    (loop [roots (sort-by str roots)
           visited #{}]
      (when-let [root (first roots)]
        (let [[cycle visited] (visit root [] {} visited)]
          (if cycle
            cycle
            (recur (next roots) visited)))))))

(defn- assert-acyclic-references!
  "Refuse cycles in a canonical schema-reference graph."
  [forms roots reference-graph]
  (when-let [cycle-path (reference-cycle reference-graph roots)]
    (let [identity (first cycle-path)]
      (throw
       (ex-info
        (str "Schema population refused " identity
             ": canonical schema reference cycle "
             (pr-str cycle-path)
             ". Recursive canonical registrations are not supported. "
             "Use a Malli local `:schema` registry for a recursive value "
             "shape, or a named predicate schema when the grammar belongs "
             "to its enforcing function.")
        {:seon.schema/error :seon.schema/cyclic-reference
         :seon.schema/cyclic-reference identity
         :seon.schema/identity identity
         :seon.schema/definition (get forms identity)
         :seon.schema/cycle-path cycle-path
         :seon.error/kind :user-input})))))

(defn- predicate-symbols-in [value]
  (cond
    (and (vector? value) (= :fn (first value)))
    (let [body (if (map? (second value)) (drop 2 value) (rest value))
          predicate (first body)]
      (if (qualified-symbol? predicate) #{predicate} #{}))

    (map? value)
    (into #{} (mapcat predicate-symbols-in) (concat (keys value)
                                                    (vals value)))

    (coll? value)
    (into #{} (mapcat predicate-symbols-in) value)

    :else #{}))

(defn- runtime-predicate
  "The Var a qualified predicate symbol names, or nil.

   THE resolution rule for host-authored predicates. A qualified symbol names
   exactly one Var, so resolution is collision-free by construction — two
   environments declaring the same predicate cannot overwrite each other the
   way the process-global symbol->function cache did (2026-08-07 isolation
   audit, `probe_predicate_function_cache`: a second registration of one
   symbol made a value that was valid under the first stop validating,
   process-wide, though both projections were rebuilt from immutable forms).

   The VAR is retained rather than its current value. Invoking a Var reads its
   root at call time, so re-evaluating a `defn` changes what an already
   compiled schema calls, exactly as it changes every other caller."
  [predicate]
  (try
    (when (qualified-symbol? predicate)
      (requiring-resolve predicate))
    (catch Throwable _
      nil)))

(defn- loaded-predicate-var
  "The Var a qualified predicate symbol names, WITHOUT loading its namespace.

   Compilation must never load code as a side effect of examining a
   declaration: agents author `[:fn ...]` forms, and [[malli-form?]] answers
   structural questions about them, so a loading resolver would let an
   arbitrary namespace be required by writing its name into a schema. The
   load-time `register-core-predicate!` assertion is what makes this
   sufficient for a valid declaration — a predicate's owner is loaded before
   anything can declare against it, and `clojure.core` is always loaded. The
   one case it is NOT sufficient for is a predicate added to a namespace this
   process already loaded, which resolves here as nothing and reaches
   [[converged-predicate-var]] instead of refusing."
  [predicate]
  (when (qualified-symbol? predicate)
    (some-> (find-ns (symbol (namespace predicate)))
            (ns-resolve (symbol (name predicate))))))

(defn- converged-predicate-var
  "Resolve `predicate` against its SOURCE after [[loaded-predicate-var]] missed.

   The JVM's var table is a MIRROR of first-party source that a publication
   is about to re-decide, and a long-lived process holds the copy it loaded
   at boot. A declaration naming a predicate added since then resolves to
   nothing, so every publication is refused — including the one whose own
   adoption would have reloaded the owner. That is the pre-read the owner
   law forbids, and it wedged `default` on 2026-09-16 for
   `seon.search/handle?` (filed as
   `docs/seon/issues/a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place.md`).

   So the resolver converges instead of refusing: a namespace this process
   ALREADY LOADED, whose source declares a predicate its loaded copy does not
   have, is RELOADED — the only thing that replays its
   `register-core-predicate!` forms, since `require` on a loaded namespace is
   a no-op. An UNLOADED namespace is left alone and still refuses, so
   [[loaded-predicate-var]]'s guarantee is intact where it matters: an
   authored `[:fn ...]` naming a namespace nothing has loaded cannot make
   this process require code (`seon.schema-test/canonical-definition-keeps-admitted-predicate-symbols`
   asserts exactly that, and it is the stale MIRROR, not an absent one, that
   wedged the machine).

   This runs only where the alternative is the refusal itself — a predicate
   that already resolves is never reloaded — so it can turn a wedge into a
   success and can never change a working compile.

   It deliberately remembers NOTHING. A memo of attempted convergences would
   be exactly the process-global state named for predicates that
   `seon.schema-test/one-predicate-symbol-cannot-name-two-environments-callables`
   forbids, and that regression caught a first draft of this function holding
   one. It is not needed: a successful reload makes the predicate resolve, so
   convergence never runs for it again, and a predicate that survives its own
   reload throws out of [[compilable-form]], ending the walk. The cost is
   paid only in an already-broken state, by a caller that swallows the
   refusal and keeps asking."
  [predicate]
  (let [namespace-name (symbol (namespace predicate))]
    (when (find-ns namespace-name)
      (try
        (require namespace-name :reload)
        (catch Throwable _ nil)))
    (loaded-predicate-var predicate)))

(defn compilable-form
  "Prepare one authored declaration for Malli compilation.

   THE choke point every compile path goes through, so a preparation added
   here cannot be forgotten by a new one. Two preparations live here:

   Predicate symbols are replaced by their callables. Malli evaluates
   symbol/string/list predicate code by constructing its own SCI context.
   Seon admits only named predicates and supplies their already materialized
   callables from the corpus environment, so unresolved code fails closed
   here instead of opening that second evaluator.

   Component child positions are widened by
   [[seon.schema.form/widen-component-children]] — derived from the
   `:seon.db/component true` property the form already declares, so a row
   that carries its component entities validates as the transaction data it
   is (owner ruling 2026-08-08). Only the COMPILED shape widens; the authored
   declaration, its canonical EDN, and the Datahike bridge keep reading the
   narrow form.

   `predicate-functions` is the caller's explicit override — a preprocessed
   projection carries its own callables. Anything absent from it resolves
   through [[loaded-predicate-var]], which never loads a namespace: there is
   no process-global cache to consult, so there is nothing a second
   environment can overwrite, and examining a valid declaration cannot make
   the process require code. Only a declaration that would otherwise be
   REFUSED reaches [[converged-predicate-var]], which loads the predicate's
   own source rather than wedging every publication behind this JVM's stale
   copy of it."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}] :map] :seon.schema/value]}
  [form predicate-functions]
  (walk/postwalk
   (fn [value]
     (cond
       (and (map? value)
            (qualified-symbol? (:gen/gen value)))
       (assoc value :gen/gen
              (some-> (:gen/gen value) requiring-resolve deref))

       (and (vector? value) (= :and (first value))
            (not (and (map? (second value)) (:gen/gen (second value))))
            (some #(and (vector? %) (= :fn (first %))
                        (:gen/gen (second %))) (rest value)))
       (let [generator (some #(when (and (vector? %) (= :fn (first %)))
                               (:gen/gen (second %))) (rest value))
             properties (if (map? (second value)) (second value) {})
             children (if (map? (second value)) (drop 2 value) (rest value))]
         (into [:and (assoc properties :gen/gen generator)] children))

       (and (vector? value) (= :fn (first value)))
       (let [predicate-index (if (map? (second value)) 2 1)
             predicate (get value predicate-index)
             bound
             (when (qualified-symbol? predicate)
               (or (get predicate-functions predicate)
                   (loaded-predicate-var predicate)
                   (converged-predicate-var predicate)))]
         (cond
           (and (ifn? predicate)
                (not (or (symbol? predicate)
                         (string? predicate)
                         (sequential? predicate))))
           value

           (ifn? bound)
           (assoc value predicate-index bound)

           :else
           (throw
            (ex-info
             (str "Predicate " (pr-str predicate)
                  " has no admitted callable in the corpus projection.")
             (cond-> {:seon.schema/error :seon.schema/unresolved-predicate
                      :seon.schema/unresolved-predicate predicate
                      :seon.schema/predicate predicate
                      :seon.error/kind :user-input}
               ;; Name the namespace that must define it: after
               ;; `converged-predicate-var` has loaded and reloaded that
               ;; source, an unresolved predicate is a declaration naming a
               ;; definition its own source does not have.
               (qualified-symbol? predicate)
               (assoc :seon.schema/predicate-namespace
                      (symbol (namespace predicate))))))))

       :else value))
   (form/widen-component-children form)))

(defn- compiled-function-arities [compiled]
  (mapv (fn [arity]
          (let [{:keys [input output]} (m/-function-info arity)]
            [(m/validator input) (m/form output)]))
        (m/-function-schema-arities compiled)))

(defn- with-compiled-cache
  "Give one projection its own holder for state compiled FROM it.

   Malli's pattern, applied: a validator is a pure function of the schema it
   was compiled from, so it lives on that instance and cache identity is
   structural — there is nothing to invalidate and nothing to compare.

   Seon's compiled validators, explainers, and identity-only descriptors used
   to live in two process-global slots with room for ONE projection each,
   guarded by comparing the slot's projection against the caller's. That
   comparison was a check-then-act on shared mutable state — the check and
   the returned value were two independent derefs — so one environment could
   be handed another environment's validator for the same schema key,
   reproduced in both directions, 2 runs in 5 (2026-08-07 parallel isolation
   audit, Defect II, `probe_shape_generation_cache`).

   The holder is installed FRESH at every construction and never inherited: a
   projection derived by changing forms would otherwise carry its parent's
   compiled answers for definitions it no longer has. It needs no key,
   because its key is the value it hangs on."
  ([projection] (with-compiled-cache projection {}))
  ([projection compiled-contracts]
   (assoc projection
          :seon.config.db/validation-node-limit
          (:seon.config/default
           (form/attr-form-properties
            (get (:seon.schema.projection/forms projection) :seon.config.db/validation-node-limit)))
          :seon.schema.projection/compiled
          (atom (into {}
                      (map (fn [[sym compiled]]
                             [[::function-arities sym]
                              (delay (compiled-function-arities compiled))]))
                      compiled-contracts)))))

(defn- projection-cache
  "The holder [[with-compiled-cache]] installed, or nil.

   Nil is honest rather than exceptional: a projection assembled by a caller
   that did not go through a constructor still answers every question, it
   just recompiles. Correctness never depends on the cache being there, which
   is what makes it safe for it to be absent."
  [projection]
  (:seon.schema.projection/compiled projection))

(defn projection-cache-value
  "Return one value derived from `projection`, computing it at most once.

   The answer is retained by the projection's own runtime holder. A projection
   assembled without that holder remains correct and simply derives afresh."
  {:malli/schema
   [:=> [:catn [:seon.schema/projection :map] [:seon.schema/cache-key [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A projection cache key is ordinary heterogeneous data chosen by its caller; cached values are the supplied thunk's arbitrary result.", :gen/elements [nil false 0 "" :k [] {}]}]] [:seon.schema/derive-fn [:fn clojure.core/ifn?]]] :seon.schema/value]}
  [projection cache-key derive-fn]
  (if-let [cache (projection-cache projection)]
    (let [candidate (delay (derive-fn))
          selected
          (get
           (swap! cache
                  (fn [entries]
                    (if (contains? entries cache-key)
                      entries
                      (assoc entries cache-key candidate))))
           cache-key)]
      @selected)
    (derive-fn)))

(defn predicate-functions-in
  "Return the predicate bindings `projection` carries; `{}` when none are bound.

   THE ONE DERIVATION for that question, and the only reader of
   `:seon.schema.projection/predicate-functions`. A projection with no bound
   predicates carries NO KEY, never a stored nil — [[declaration-projection]]
   and every cold arm build exactly that shape — while [[compilable-form]]
   declares a map, so a bare read handed it nil and refused every compile in a
   worker where nothing bound a projection first. Translating the absence in
   one place is what makes that unconstructable; `compilable-form` keeps
   declaring `:map` so a future bare read still refuses loudly rather than
   reading absence as \"no predicates\".

   [[with-predicate-functions]] is the only writer."
  {:malli/schema
   [:=> [:catn [:seon.schema/projection :map]]
    [:map-of :qualified-symbol [:fn clojure.core/ifn?]]]}
  [projection]
  (get projection :seon.schema.projection/predicate-functions {}))

(defn with-predicate-functions
  "Return `projection` carrying `predicate-functions` as its bound predicates.

   THE ONE WRITER of `:seon.schema.projection/predicate-functions`, paired with
   [[predicate-functions-in]]; `seon.schema-test` derives from the program
   graph that no other first-party function names the key."
  {:malli/schema
   [:=> [:catn [:seon.schema/projection :map]
               [:seon.schema/predicate-functions
                [:map-of :qualified-symbol [:fn clojure.core/ifn?]]]]
    :map]}
  [projection predicate-functions]
  (assoc projection
         :seon.schema.projection/predicate-functions predicate-functions))

(defn- bound-forms [forms predicate-functions]
  (update-vals forms #(compilable-form % predicate-functions)))

(defn- projection-registry
  "Registry over immutable forms that binds only the requested declaration."
  [forms predicate-functions]
  (let [defaults (mr/fast-registry (m/default-schemas))
        all-schemas (delay (merge (mr/-schemas defaults) forms))]
    (reify
      mr/Registry
      (-schema [this type]
        (or (mr/-schema defaults type)
            (when-let [definition (get forms type)]
              (m/schema (compilable-form definition predicate-functions)
                        {:registry this}))))
      (-schemas [_]
        @all-schemas))))

(defn canonical-definition
  "Return one Malli definition as durable EDN.

   Evaluating Clojure metadata resolves predicate symbols to callable roots.
   This is the inverse of `compilable-form`: a bound predicate IS the Var its
   qualified symbol names, and a Var carries that symbol, so the inverse is
   reading the name back off the Var — no process-global table of callables
   is scanned, and no second environment's registration can supply a
   different answer. Callables the caller supplied explicitly (a preprocessed
   projection carries raw functions) are matched by identity against that
   supplied map. Anonymous or otherwise unresolvable callables are refused
   instead of being printed as unreadable `#object` values."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}] :map] :seon.schema/definition]}
  [definition predicate-functions]
  (let [bindings (sort-by (comp str key) predicate-functions)
        callable-symbol
        (fn [value]
          (or (when (var? value) (symbol value))
              (some (fn [[predicate f]]
                      (when (identical? value f) predicate))
                    bindings)
              (throw
               (ex-info
                "A durable Malli definition contains an unnamed callable."
                {:seon.schema/error :seon.schema/noncanonical-definition
                 :seon.schema/noncanonical-definition ::unnamed-callable
                 :seon.schema/value value
                 :seon.error/kind :core-bug}))))
        callable?
        (fn [value]
          (and (ifn? value)
               (not (or (map? value)
                        (set? value)
                        (keyword? value)
                        (symbol? value)
                        (vector? value)))))
        canonical
        (letfn [(canonicalize [value reference-kind]
                  (cond
                    (callable? value)
                    (let [predicate (callable-symbol value)]
                      (if reference-kind
                        predicate
                        [:fn predicate]))

                    (vector? value)
                    (let [predicate-index
                          (when (= :fn (first value))
                            (if (map? (second value)) 2 1))]
                      (mapv (fn [index child]
                              (canonicalize child
                                            (when (= predicate-index index)
                                              :predicate)))
                            (range)
                            value))

                    (map? value)
                    (into (empty value)
                          (map (fn [[k v]]
                                 (when (and (callable? v)
                                            (not= :gen/gen k))
                                   (throw
                                    (ex-info
                                     (str "A durable Malli property contains "
                                          "a callable outside :gen/gen.")
                                     {:seon.schema/error
                                      :seon.schema/noncanonical-definition
                                      :seon.schema/noncanonical-definition
                                      ::callable-property
                                      :seon.schema/property k
                                      :seon.schema/value v
                                      :seon.error/kind :core-bug})))
                                 [(canonicalize k false)
                                  (canonicalize v
                                                (when (= :gen/gen k)
                                                  :generator))]))
                          value)

                    (set? value)
                    (into #{} (map #(canonicalize % false)) value)

                    (and (= :predicate reference-kind)
                         (seq? value)
                         (= 'quote (first value))
                         (nil? (next (next value)))
                         (qualified-symbol? (second value)))
                    (second value)

                    (sequential? value)
                    (doall (map #(canonicalize % false) value))

                    :else value))]
          (canonicalize definition false))
        encoded (pr-str canonical)
        decoded (edn/read-string encoded)]
    (when-not (= canonical decoded)
      (throw
       (ex-info
        "A durable Malli definition contains non-EDN data."
        {:seon.schema/error :seon.schema/noncanonical-definition
         :seon.schema/noncanonical-definition ::non-edn
         :seon.schema/value canonical
         :seon.error/kind :core-bug})))
    decoded))

(defn- reference-registry
  [canonical-keys fallback]
  (let [defaults (mr/fast-registry (m/default-schemas))
        references
        (into {}
              (comp
               (filter qualified-keyword?)
               (map (fn [schema-key]
                      [schema-key [:ref schema-key]])))
              canonical-keys)
        all-schemas
        (delay
          (merge (mr/-schemas defaults)
                 (mr/-schemas fallback)
                 references))]
    (reify
      mr/Registry
      (-schema [_ type]
        (or (mr/-schema defaults type)
            (get references type)
            (mr/-schema fallback type)))
      (-schemas [_]
        @all-schemas))))

(defn- canonical-reference-graph
  "Direct canonical references without eagerly expanding canonical forms."
  ([forms predicate-functions]
   (canonical-reference-graph forms predicate-functions (set (keys forms))))
  ([forms predicate-functions canonical-keys]
   (let [canonical-keys (set canonical-keys)
        bootstrap-forms
        (into {}
              (remove (comp qualified-keyword? key))
              forms)
        registry-for-references
        (reference-registry
         canonical-keys
         (mr/fast-registry
          (bound-forms bootstrap-forms predicate-functions)))
        options {:registry registry-for-references}]
    (into
     (sorted-map)
     (map
      (fn [[schema-key definition]]
        [schema-key
         (try
           (direct-references*
            (m/schema
             (compilable-form definition predicate-functions)
             options)
            canonical-keys)
           (catch Exception _
             ;; The ordinary compilation gate owns malformed and unresolved
             ;; forms. This preflight owns only the reference graph needed to
             ;; make recursive expansion unrepresentable.
             #{}))]))
     forms))))

(defn- reference-candidate-keys
  "Population keys plus qualified keywords Malli may classify as refs."
  [forms]
  (into (into #{} (filter keyword?) (keys forms))
        (comp
         (mapcat #(tree-seq coll? seq %))
         (filter qualified-keyword?))
        (vals forms)))

(defn- direct-reference-keys-in
  [definition predicate-functions canonical-keys fallback]
  (let [registry-for-references
        (reference-registry canonical-keys fallback)]
    (direct-references*
     (m/schema (compilable-form definition predicate-functions)
               {:registry registry-for-references})
     canonical-keys)))

(declare canonical-data-string canonical-value-string)

(defn- portable-string-hash [s]
  (.hashCode ^String s))

(defn canonical-data-fingerprint
  "Portable content fingerprint for ordinary data."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Canonical projection encoding handles heterogeneous EDN data, including nil, literals and nested collections; unsupported runtime objects are reported as noncanonical projection data.", :gen/elements [nil false 0 "" :k [] {}]}]] :int]}
  [value]
  (portable-string-hash (canonical-data-string value)))

(defn- framed [tag payload]
  (str tag (count payload) ":" payload))

(defn- canonical-coll-string [tag values]
  (framed tag (apply str (map canonical-value-string values))))

(defn- canonical-value-string
  [value]
  (cond
    (nil? value) "n"
    (true? value) "b1"
    (false? value) "b0"
    (keyword? value) (framed "k" (str value))
    (symbol? value) (framed "y" (str value))
    (string? value) (framed "s" value)
    (number? value) (framed "d" (str value))
    (vector? value) (canonical-coll-string "v" value)
    (set? value) (canonical-coll-string
                   "t" (sort (map canonical-value-string value)))
    (map? value)
    (canonical-coll-string
      "m"
      (sort (map (fn [[k v]]
                   (str (canonical-value-string k)
                        (canonical-value-string v)))
                 value)))
    (sequential? value) (canonical-coll-string "q" value)
    ; EDN literals `seon.db/stable-value` admits as stable read data
    ; (`src/seon/db.clj:422-424`). Rejecting them made every read result
    ; carrying a `:db/instant` or a commit id undigestable, so the agent
    ; history's retained read could never prove itself current and ANY
    ; commit re-walked it (2026-09-16). Their tags are new, so no
    ; fingerprint that encoded before this change encodes differently.
    (instance? java.util.Date value)
    (framed "i" (str (.getTime ^java.util.Date value)))
    (instance? java.time.Instant value)
    (framed "i" (str (.toEpochMilli ^java.time.Instant value)))
    (uuid? value) (framed "u" (str value))
    (char? value) (framed "c" (str value))
    :else
    (throw (ex-info "Schema projection fingerprint contains non-EDN data."
                    {:seon.schema/error
                     :seon.schema/noncanonical-projection-data
                     :seon.schema/value value
                     :seon.error/kind :core-bug :seon.schema/noncanonical-projection-data true}))))


(defn canonical-data-string
  "Canonical byte-comparison string for ordinary projection data.

   This is the portable content oracle used by projection fingerprints and by
   the preprocessed-base composition proof. It encodes every EDN literal,
   including instants, uuids and characters; runtime objects are rejected."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Canonical projection encoding handles heterogeneous EDN data, including nil, literals and nested collections; unsupported runtime objects are reported as noncanonical projection data.", :gen/elements [nil false 0 "" :k [] {}]}]] :string]}
  [value]
  (canonical-value-string value))

(defn byte-array?
  "True when `value` is a platform byte array."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (bytes? value))

(defn sha-256
  "Lowercase SHA-256 hex digest of ordered byte arrays (`seon.id/sha-256`)."
  {:malli/schema [:=> [:cat [:sequential [:fn seon.schema/byte-array?]]]
                  [:string {:min 64 :max 64}]]}
  [byte-arrays]
  (id/sha-256 byte-arrays))

(defn- projection-fingerprint
  [forms function-contracts schema-admissions function-admissions
   function-source-admissions artifact-exports pure-predicate-symbols]
  (letfn [(entry-fingerprint [section identity value]
            (portable-string-hash
             (canonical-data-string [section identity value])))
          (map-fingerprint [section values]
            (reduce-kv
             (fn [fingerprint identity value]
               (bit-xor fingerprint
                        (entry-fingerprint section identity value)))
             0
             values))
          (set-fingerprint [section values]
            (reduce
             (fn [fingerprint value]
               (bit-xor fingerprint
                        (entry-fingerprint section value true)))
             0
             values))]
    (reduce
     bit-xor
     (portable-string-hash "seon.schema.projection/fingerprint-v2")
     [(map-fingerprint :forms forms)
      (map-fingerprint :function-contracts function-contracts)
      (map-fingerprint :schema-admissions schema-admissions)
      (map-fingerprint :function-admissions function-admissions)
      (map-fingerprint :function-source-admissions
                       function-source-admissions)
      (set-fingerprint :artifact-exports artifact-exports)
      (set-fingerprint :pure-predicate-symbols pure-predicate-symbols)])))

(def ^:private projection-fingerprint-version 2)

(defn- replace-fingerprint-entry
  [fingerprint section identity before after]
  (let [entry-fingerprint
        (fn [value]
          (portable-string-hash
           (canonical-data-string [section identity value])))]
    (cond-> fingerprint
      (not= ::absent before) (bit-xor (entry-fingerprint before))
      (not= ::absent after) (bit-xor (entry-fingerprint after)))))

(defn direct-references
  "Canonical schema keys directly referenced by `form` in `projection`.

   This is a derived dependency view over Malli schema objects, not a keyword
   scan and not stored state. It works for value, entity, and function-schema
   forms and follows local recursive registries without expanding canonical
   references transitively."
  {:malli/schema [:=> [:catn [:seon.schema/projection :map] [:seon.schema/definition [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:set :keyword]]}
  [projection form]
  (let [forms (:seon.schema.projection/forms projection)
        registry (:seon.schema.projection/registry projection)
        compile-options
        (or (:seon.schema.projection/compile-options projection)
            {:registry registry})
        compiled
        (m/schema
         (compilable-form form (predicate-functions-in projection))
         compile-options)]
    (direct-references* compiled (set (keys forms)))))

(defn dependent-schema-keys
  "Changed schema keys plus their reverse transitive dependents.

   The dependency graph belongs to the immutable projection. The result is
   derived with a bounded graph walk and is empty when `changed` is empty."
  {:malli/schema [:=> [:catn [::projection :map]
                             [::changed [:set :keyword]]]
                  [:set :keyword]]}
  [projection changed]
  (let [reverse-edges
        (:seon.schema.projection/reverse-schema-dependencies projection)]
    (loop [frontier (set changed)
           seen #{}]
      (if (empty? frontier)
        seen
        (let [seen' (into seen frontier)
              next-frontier
              (into #{}
                    (comp
                      (mapcat #(get reverse-edges % #{}))
                      (remove seen'))
                    frontier)]
          (recur next-frontier seen'))))))

(defn schema-removal-blockers
  "Schema and function identities preventing removal of `schema-key`.

   Blockers are derived from the immutable projection. Schema blockers are
   reverse-transitive dependents; function blockers are contracts referencing
   the removed key or one of those dependents. The target itself is never a
   blocker."
  {:malli/schema [:=> [:catn [::projection :map]
                             [::registry-key :keyword]]
                  [:map
                   [:seon.schema.blockers/schema-keys [:set :keyword]]
                   [:seon.schema.blockers/function-symbols
                    [:set :qualified-symbol]]]]}
  [projection schema-key]
  (let [affected (dependent-schema-keys projection #{schema-key})
        schema-keys (disj affected schema-key)
        function-symbols
        (into #{}
              (keep (fn [[function-symbol dependencies]]
                      (when (seq (set/intersection affected dependencies))
                        function-symbol)))
              (:seon.schema.projection/function-dependencies projection))]
    {:seon.schema.blockers/schema-keys schema-keys
     :seon.schema.blockers/function-symbols function-symbols}))

;;; ---------------------------------------------------------------------------
;;; Registry Setup
;;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private *candidate-forms-overlay* nil)
(def ^:dynamic ^:private *projection* nil)
(def ^:dynamic ^:private *projection-state* nil)
(def ^:dynamic ^:private *packaged-forms* nil)
(def ^:dynamic ^:private *registration-admission-source* :core)

(defn admission-from-asserting-transaction
  "Read admission source recorded on a row asserted by `asserting-tx-eid`.

   Missing, ambiguous, and unrecognized facts deliberately fail closed as
   agent-authored. Temporal history is irrelevant to admission strictness."
  {:malli/schema [:=> [:cat :map :int] :map]}
  [db asserting-tx-eid]
  (let [sources
        (when (contains? (dbi/-schema db) :seon.schema.admission/source)
          (set
           ;; `seon.db` requires this namespace for predicate registration and
           ;; transaction encoding. Resolve the one database Var late instead
           ;; of recreating that load cycle.
           (d/q
            '[:find [?source ...]
              :in $ ?tx
              :where
              [?declaration _ _ ?tx]
              [?declaration :seon.schema.admission/source ?source]]
            db
            asserting-tx-eid)))
        recorded (when (= 1 (count sources)) (first sources))
        recognized? (contains? #{:core :agent} recorded)
        source (if recognized? recorded :agent)]
    (cond->
      {:seon.schema.admission/source source}
      (not recognized?)
      (assoc :seon.schema.admission/note
             (str "The asserting transaction does not identify exactly one "
                  "recorded admission source, so this row is admitted as "
                  "agent-authored.")))))

;;; ---------------------------------------------------------------------------
;;; The classpath fallback, and why it is loud
;;; ---------------------------------------------------------------------------

;; Reached with no population in hand, resolution reads and merges every schema
;; resource on the classpath. On 2026-08-07 one caller reached it 1,886 times —
;; 286,672 file reads, twenty-six seconds — and logged NOTHING; both instances
;; found that day were found by thread dump. R41 says development must be
;; unable to miss it. The mechanism is the stderr line `seon.instrument/apply!`
;; already uses (`instrument.clj:411-414`), which is the only one available
;; here: this namespace sits below `seon.db`, so the
;; `:seon.config/on-core-error` fact is unreadable, and a panic would be wrong
;; regardless — the fallback is the LEGITIMATE bootstrap path before any
;; projection exists, so panicking would make boot impossible. Loud therefore
;; means NAMED AND COUNTED, and production-bounded means one line per calling
;; function per decade of occurrences: the 2026-08-07 loop would have printed
;; six lines, not a quarter of a million.
(defonce ^:private !fallback-counts (atom {}))

(defn- frame-namespace
  [^StackTraceElement frame]
  (let [demunged (clojure.lang.Compiler/demunge (.getClassName frame))
        separator (.indexOf demunged "/")]
    (if (neg? separator) demunged (subs demunged 0 separator))))

;; The warning is only worth printing if its advice is ACTIONABLE, and the
;; reader can only act on first-party code. Naming the nearest non-`clojure.`
;; frame did not do that: `seon.schema/malli-form?` is a registered core
;; predicate, so Malli invokes it through `-safe-pred` and the resolution
;; escaped with `malli.core (core.cljc:209)` as its nearest outside frame —
;; a dependency line nobody can thread a population through
;; (`docs/seon/issues/malli-form-predicate-resolves-the-declaration-population-itself.md`).
;;
;; "First party" is DERIVED, never a namespace-prefix rule. The Clojure CLI
;; already recorded the distinction in the basis it wrote for this process: a
;; `:classpath` entry carrying a `:lib-name` belongs to a dependency, and an
;; entry without one is a source root this project declared for itself. The
;; only correction that judgement needs is dropping an entry that CONTAINS
;; another entry — the repository root a test alias adds as `"."`, which also
;; holds every vendored fork under `reference-code/`. A frame is actionable
;; when its source file resolves on the classpath under one of the survivors.
(def ^:private resolution-owner-namespaces #{"seon.schema" "seon.schema.edn" "seon.instrument"})

(defn- canonical-directory
  [path]
  (str (.getCanonicalPath (java.io.File. ^String path)) java.io.File/separator))

(def ^:private first-party-source-roots
  (delay
    (let [basis (try
                  (some-> (System/getProperty "clojure.basis")
                          slurp
                          edn/read-string)
                  (catch Throwable _ nil))
          declared (into {}
                         (keep (fn [[root descriptor]]
                                 (when-not (:lib-name descriptor)
                                   (try [root (canonical-directory root)]
                                        (catch Throwable _ nil)))))
                         (:classpath basis))]
      (into #{}
            (keep (fn [[root path]]
                    (when-not (some (fn [[other other-path]]
                                      (and (not= root other)
                                           (.startsWith ^String other-path
                                                        ^String path)))
                                    declared)
                      path)))
            declared))))

(defn- frame-source-path
  "Where the frame's source file sits on the classpath, or nil.

   The namespace-to-resource mapping is Clojure's own munging, not a Seon
   convention: the loaded file is the frame's own file name inside the
   namespace's directory."
  [^StackTraceElement frame ^String frame-ns]
  (when-let [file-name (.getFileName frame)]
    (let [directory (.replace (.replace frame-ns "-" "_") \. \/)
          package (subs directory 0 (inc (.lastIndexOf directory "/")))]
      (when-let [url (io/resource (str package file-name))]
        (when (= "file" (.getProtocol url))
          (.getPath url))))))

(defn- first-party-frame?
  [^StackTraceElement frame ^String frame-ns]
  (and (not (contains? resolution-owner-namespaces frame-ns))
       (boolean
        (when-let [path (frame-source-path frame frame-ns)]
          (some #(.startsWith ^String path ^String %)
                @first-party-source-roots)))))

(defn- frame-description
  [^StackTraceElement frame frame-ns]
  (str frame-ns " (" (.getFileName frame) ":" (.getLineNumber frame) ")"))

(defn- fallback-caller
  "The nearest frame under a declared first-party source root.
   Stack introspection is a diagnostic and runs only on the fallback path.

   When no frame resolves to a declared source root — a bare REPL form, a
   dynamically evaluated namespace, a host thread entry — the nearest frame
   outside resolution is named instead and marked as a best-effort guess
   rather than a place to go and edit."
  []
  (let [frames (.getStackTrace (Thread/currentThread))]
    (or (some (fn [^StackTraceElement frame]
                (let [frame-ns (frame-namespace frame)]
                  (when (first-party-frame? frame frame-ns)
                    (frame-description frame frame-ns))))
              frames)
        (some (fn [^StackTraceElement frame]
                (let [frame-ns (frame-namespace frame)]
                  (when-not (or (contains? resolution-owner-namespaces frame-ns)
                                (.startsWith ^String frame-ns "clojure.")
                                ;; instrumentation plumbing is never a
                                ;; caller: with contracts armed, malli's
                                ;; wrapper sits between the caller and the
                                ;; refusal and would otherwise be named as
                                ;; the place to go and edit
                                (.startsWith ^String frame-ns "malli.")
                                (.startsWith ^String frame-ns "java."))
                    (str (frame-description frame frame-ns)
                         " [no declared source root — nearest frame]"))))
              frames)
        "an unidentified caller")))

(defn- decade?
  [n]
  (let [magnitude (Math/log10 (double n))]
    (== magnitude (Math/floor magnitude))))

;; The explanation is printed ONCE per process; each occurrence after it is one
;; short line. Repeating a 300-character sentence per caller per decade made
;; the signal its own wall — 45% of `seon.cluster.boot-test`'s wrapped
;; transcript on 2026-08-07 — and a diagnostic that buries the reader is the
;; defect the ethos names, not a louder version of the right one.
(defn- warn-classpath-fallback!
  []
  (let [caller (fallback-caller)
        counts (swap! !fallback-counts update caller (fnil inc 0))
        occurrence (get counts caller)]
    (when (decade? occurrence)
      (binding [*out* *err*]
        (when (= 1 (count counts) occurrence)
          (println
           (str "seon.schema: resolving the declaration population with none"
                " in hand reads every schema resource on the classpath (152"
                " reads, ~14 ms). ONE per operation is the current floor; the"
                " SAME caller repeating within one operation is the defect —"
                " resolve it once with schema/declaration-population and pass"
                " it to every question that operation asks. Each occurrence"
                " below names its caller and its count for this process.")))
        (println (str "seon.schema: DECLARATION POPULATION FALLBACK ×"
                      occurrence " — " caller))
        (flush)))))

(defn- packaged-forms []
  (warn-classpath-fallback!)
  (@schema-edn-packaged-forms))

(defn- candidate-forms []
  (if *candidate-forms-overlay*
    @*candidate-forms-overlay*
    (or *packaged-forms*
        (some-> *projection-state*
                deref
                :seon.schema/projection
                :seon.schema.projection/forms)
        (:seon.schema.projection/forms *projection*)
        (packaged-forms))))

(defn declaration-population
  "THE declaration population in hand for this operation.

   Resolve it ONCE per operation and pass it to every question that operation
   asks; never ask a per-item function that resolves it again. With no
   projection, projection state, or candidate overlay supplied, resolution
   falls through to the packaged resources, which re-reads and re-merges every
   schema resource on disk — so a `keep` over N keys that calls
   [[schema-definition]] per key costs N complete resource merges (measured
   2026-08-07: 1,036 ms and 12,616 resource reads for one
   `seon.config/registration-defaults`). The population is an ordinary
   immutable map of registry key -> schema form; read it with `get`."
  {:malli/schema [:=> [:cat] :map]}
  []
  (if (or *candidate-forms-overlay* *packaged-forms* *projection*
          *projection-state*)
    (candidate-forms)
    (throw
     (ex-info
      "Schema declaration resolution requires the projection handed to the operation."
      {:seon.error/kind ::missing-projection
       :seon.error/message
       "No declaration projection was handed to this schema operation."
       :seon.error/data {:seon.schema/caller (fallback-caller)} :seon.schema/missing-projection true}))))

(defn call-with-forms
  "Call `f` with one immutable declaration population for this operation."
  {:malli/schema [:=> [:cat :map [:fn clojure.core/ifn?]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [forms f]
  (binding [*packaged-forms* forms]
    (f)))

(defn call-with-projection
  "Call `f` with one immutable database-derived projection for this operation."
  {:malli/schema [:=> [:cat :map [:fn clojure.core/ifn?]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [projection f]
  (binding [*projection* projection
            *projection-state* nil]
    (f)))

(defn call-with-projection-state
  "Call `f` with one cluster-owned, advanceable schema projection state."
  {:malli/schema [:=> [:cat [:fn clojure.core/deref] [:fn clojure.core/ifn?]] [:or :seon.instrument/registration-error [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The body returns its polymorphic result unchanged; instrumentation acquisition can return its declared registration refusal.", :gen/elements [nil false 0 "" :k [] {}]}]]]}
  [projection-state f]
  (binding [*projection-state* projection-state
            *projection* nil
            *packaged-forms* nil]
    (f)))

(defn- active-projection []
  *projection*)

(defn register-core-predicate!
  "Assert at load time that `predicate` names the callable `f`, and return it.

   This no longer caches anything: resolution is [[runtime-predicate]], which
   reads the Var the qualified symbol names, so there is no process-global
   symbol->function map for a second environment to overwrite (2026-08-07
   isolation audit, Defect I.3).

   It remains a call rather than nothing for one reason worth keeping: it
   resolves the predicate EAGERLY, as the owning namespace loads, instead of
   leaving the first resolution to happen lazily inside a schema compile —
   which is where a `require` triggered mid-compile could meet a load cycle.
   A typo'd symbol therefore fails while loading the namespace that declared
   it, naming that namespace, rather than at some later projection build.

   Its 37 call sites across `src/` are queued for deletion with the rest of
   the load-time registration sentinels when acquisition-at-a-basis lands
   (seon.env PRD deletion list); eight of them sit in `seon.flow` and
   `seon.sci.admit`, which this owner does not hold."
  {:malli/schema
   [:=> [:cat :qualified-symbol [:fn clojure.core/ifn?]]
    :qualified-symbol]}
  [predicate f]
  (let [resolved (runtime-predicate predicate)]
    (when-not (and resolved (identical? f (var-get resolved)))
      (throw
       (ex-info
        (str "Predicate " predicate " does not name the supplied callable.")
        {:seon.schema/error :seon.schema/unresolved-predicate
         :seon.schema/unresolved-predicate predicate
         :seon.schema/predicate predicate
         :seon.schema/resolved resolved
         :seon.error/kind :core-bug}))))
  predicate)

(defn core-predicate-registered?
  "True when `predicate` resolves to a callable Var."
  {:malli/schema [:=> [:cat :qualified-symbol] :boolean]}
  [predicate]
  (boolean (some-> (runtime-predicate predicate) var-get ifn?)))

(register-core-predicate! 'seon.schema/byte-array? byte-array?)

(defn- update-candidate-forms! [f & args]
  (if *candidate-forms-overlay*
    (apply swap! *candidate-forms-overlay* f args)
    (throw
     (ex-info "Schema declarations require an isolated registration delta."
              {:seon.schema/error
               :seon.schema/registration-outside-delta
               :seon.schema/registration-outside-delta true
               :seon.error/kind :user-input}))))

(defn- candidate-registry
  ([] (candidate-registry (declaration-population)))
  ([forms]
   (let [defaults (mr/fast-registry (m/default-schemas))]
     (reify
       mr/Registry
       (-schema [this type]
         (or (mr/-schema defaults type)
             (when-let [form (get forms type)]
               (m/schema
                (compilable-form form {})
                {:registry this}))))
       (-schemas [_]
         (merge (mr/-schemas defaults) forms))))))

(defn declaration-projection
  "One immutable projection over the declaration population in hand.

   A seam that walks many attributes resolves this ONCE and passes it to every
   `-in` question it asks (see [[declaration-population]] for the cost of not
   doing so). It carries the registry as well as the forms, because a
   projection without one cannot compile a validator — a forms-only map made
   `projection-validator` throw `:malli.core/invalid-schema` for every
   EDN-backed attribute (2026-08-07). The registry is a lazy `reify`, so
   pairing it costs nothing beyond the population itself."
  {:malli/schema
   [:function
    [:=> [:cat] ::projection]
    [:=> [:catn [::forms :map]] ::projection]]}
  ([] (declaration-projection (declaration-population)))
  ([forms]
   (let [registry (candidate-registry forms)]
     (with-compiled-cache
      {:seon.schema.projection/forms forms
       :seon.schema.projection/registry registry
       :seon.schema.projection/compile-options {:registry registry}}))))

;; THE structural registry: Malli's own default schemas, plus one opaque
;; placeholder for every other type. It resolves nothing from any declaration
;; population, because whether a reference RESOLVES is not this question.
;;
;; Reading the ambient population here was the pre-read the owner law forbids.
;; The authority that holds the projection — `projection-with-schema`, the
;; admission path, `validate-one-contract!` — compiles the same form against
;; the projection in its hand and refuses there, naming the key. Asking a
;; second, ambient world first produced exactly the two defects filed against
;; it: an incremental build was refused for a reference the projection it was
;; extending already resolved, and a call with no projection bound re-read
;; every schema resource on the classpath (152 reads, ~14 ms) only to answer
;; false. A placeholder with no child bound admits a reference used as a type.
(defonce ^:private structural-registry
  (let [defaults (mr/fast-registry (m/default-schemas))
        opaque (m/-simple-schema {:type ::opaque-reference
                                  :pred any?
                                  :min 0
                                  :max nil})]
    (reify
      mr/Registry
      (-schema [_ type]
        (or (mr/-schema defaults type)
            ;; A REFERENCE is a keyword or a qualified symbol — the two
            ;; shapes a declaration population is keyed by (unqualified keys
            ;; are real: `resources/seon/schemas/malli.edn` declares `:inst`).
            ;; Anything else — a string, a number, an unqualified symbol Malli
            ;; itself does not know — is not a reference and stays a refusal.
            (when (or (keyword? type) (qualified-symbol? type))
              opaque)))
      (-schemas [_] (mr/-schemas defaults)))))

(defn malli-form?
  "True when `value` is readable EDN and Malli can parse its STRUCTURE.

   References are opaque here: this predicate never asks whether a declaration
   population defines them, because the authority extending the projection
   re-decides that against the projection it holds. Validation, reference
   resolution, and acyclicity remain separate operations at that authority."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (try
    (let [encoded (pr-str value)
          decoded (edn/read-string encoded)]
      (and (= value decoded)
           (some? (m/schema
                   (compilable-form decoded {})
                   {:registry structural-registry}))))
    (catch Exception _ false)))

(register-core-predicate! 'seon.schema/malli-form? malli-form?)

(defn pull-selector?
  "True when `value` is a Datahike pull selector."
  {:malli/schema
   [:=>
    [:cat
     [:any
      {:seon.schema.admission/exemption
       :seon.schema.admission/polymorphic-boundary
       :seon.schema.admission/reason
       "A total selector predicate accepts arbitrary objects and asks Datahike's parser whether each is a selector."
       :gen/elements [nil false 0 "" :k [] {}]}]]
    :boolean]}
  [value]
  (try
    (pull-api/compile-pull-plan value)
    true
    (catch Exception _ false)))

(register-core-predicate! 'seon.schema/pull-selector? pull-selector?)


;;; ---------------------------------------------------------------------------
;;; Registration API
;;; ---------------------------------------------------------------------------

(defn assert-complete-contract!
  "Assert that a schema or function contract is complete.

   Uses its derived
   admission source. Returns non-terminal advisories."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{:seon.schema/keys [identity definition forms admission admissions
                       pure-predicate-symbols predicate-functions
                       direct-predicate-symbols
                       compiled compiled-definition compiled-forms
                       compiled-schemas schema-dependencies registry
                       compile-options canonical-keys reference-advisories]
    :or {forms {}
         admissions {}
         pure-predicate-symbols #{}
         predicate-functions {}}}]
  (let [prepared? (and compiled-forms registry compile-options canonical-keys)
        direct-predicate-symbols
        (or direct-predicate-symbols
            (predicate-symbols-in definition))
        forms (if (and (keyword? identity) (not (contains? forms identity)))
                (assoc forms identity definition)
                forms)
        predicate-symbols
        (if prepared?
          #{}
          (into direct-predicate-symbols
                (mapcat predicate-symbols-in)
                (vals forms)))
        predicate-functions
        (if prepared?
          predicate-functions
          (reduce (fn [bindings predicate]
                    (if (contains? bindings predicate)
                      bindings
                      (if-let [f (runtime-predicate predicate)]
                        (assoc bindings predicate f)
                        bindings)))
                  predicate-functions
                  predicate-symbols))
        schema-dependencies
        (or schema-dependencies
            (canonical-reference-graph forms predicate-functions))
        _ (when-not prepared?
            (assert-acyclic-references!
             forms
             (if (keyword? identity) [identity] (keys forms))
             schema-dependencies))
        compiled-forms (or compiled-forms
                           (bound-forms forms predicate-functions))
        compiled-definition
        (or compiled-definition
            (compilable-form definition predicate-functions))
        _ (when (= :agent (:seon.schema.admission/source
                           (or admission
                               {:seon.schema.admission/source :agent})))
            (when-let [predicate
                       (first (remove pure-predicate-symbols
                                      (sort direct-predicate-symbols)))]
              (throw
               (ex-info
                (str identity " references predicate " predicate
                     ", but its existing program-graph call edges do not yet "
                     "prove a pure, capability-free transitive call graph. "
                     "Keep the predicate as a separately schema'd corpus "
                     "function, then re-register this contract after the "
                     "execution planner admits that graph.")
                {:seon.schema/error
                 :seon.schema/unproved-predicate-purity
                 :seon.schema/unproved-predicate-purity predicate
                 :seon.schema/identity identity
                 :seon.schema/predicate predicate
                 :seon.error/kind :user-input}))))
        registry (or registry
                     (mr/composite-registry
                      (m/default-schemas)
                      (mr/fast-registry compiled-forms)))
        compile-options
        (or compile-options
            {:registry registry})
        canonical-keys (or canonical-keys (set (keys forms)))
        default-admission (or admission
                              {:seon.schema.admission/source :agent})]
    (letfn [(walk-schema [schema role row-identity row-definition row-admission
                          visited]
              (let [advisories
                    (if (and (keyword? row-identity)
                             reference-advisories
                             (contains? compiled-schemas row-identity))
                      (reference-advisories
                       row-identity role row-admission)
                      (internal/assert-complete-schema!
                       {:seon.schema/identity row-identity
                        :seon.schema/forms forms
                        :seon.schema/storable-attribute?
                        (fn [attribute] (@schema-datahike-storable-attribute-in?
                                         {:seon.schema.projection/forms forms} attribute))
                        :seon.schema/definition row-definition
                        :seon.schema/compiled schema
                        :seon.schema/role role
                        :seon.schema/admission row-admission
                        :seon.schema/predicate-symbols
                        (predicate-symbols-in row-definition)
                        :seon.schema/pure-predicate-symbols
                        pure-predicate-symbols
                        :seon.schema/canonical-keys canonical-keys}))
                    references
                    (if (and (keyword? row-identity)
                             (contains? schema-dependencies row-identity))
                      (get schema-dependencies row-identity)
                      (direct-references* schema canonical-keys))]
                (into advisories
                      (mapcat
                        (fn [reference]
                          (when-not (contains? visited reference)
                            (let [reference-form (get forms reference)
                                  reference-admission
                                  (get admissions reference default-admission)]
                              (walk-schema
                                (or (get compiled-schemas reference)
                                    (m/schema
                                     (or (get compiled-forms reference)
                                         (compilable-form
                                          reference-form
                                          predicate-functions))
                                     compile-options))
                                role
                                reference
                                reference-form
                                reference-admission
                                (conj visited reference))))))
                      references)))
            (walk-function [compiled row-identity row-definition row-admission]
              (if (m/-function-schema? compiled)
                (into
                 []
                 (mapcat
                  (fn [arity]
                    (let [{:keys [input output guard]}
                          (m/-function-info arity)]
                      (cond->
                       (into
                        (walk-schema input :input row-identity row-definition
                                     row-admission #{})
                        (walk-schema output :output row-identity row-definition
                                     row-admission #{}))
                        guard
                        (into
                         (walk-schema guard :guard row-identity row-definition
                                      row-admission #{}))))))
                 (m/-function-schema-arities compiled))
                (walk-schema compiled :schema row-identity row-definition
                             row-admission #{})))]
      (let [compiled
            (or compiled
                (if (and (vector? definition)
                         (#{:=> :function} (first definition)))
                  (m/function-schema compiled-definition compile-options)
                  (m/schema compiled-definition compile-options)))]
        (walk-function compiled identity definition default-admission)))))

(def ^:dynamic ^:private *contract-validation-fold-size*
  64)

(defn- fold-contract-validations
  [requests]
  (let [combine
        (fn
          ([] [])
          ([left right] (into left right)))
        validate
        (fn [advisories request]
          (into advisories (assert-complete-contract! request)))]
    (reducers/fold
     *contract-validation-fold-size* combine validate requests)))

(defn identity-attr?
  "True when the attr schema for `attr-key` carries `{:seon.db/identity true}`.

   Covers the three identity shapes Seon uses
   (plain `:string`/`:keyword` with the prop, and the `:and` id wrap).
   PUBLIC: the single identity-attr predicate — callers reuse it rather than
   re-deriving the props lookup. A caller asking about more than one key
   supplies the population it already resolved (see [[declaration-population]]);
   the one-argument arity resolves one per call, so asking it per key in a loop
   costs one complete resource merge per key."
  {:malli/schema
   [:function
    [:=> [:cat :keyword] :boolean]
    [:=> [:cat :map :keyword] :boolean]]}
  ([attr-key]
   (internal/identity-attr? (candidate-forms) attr-key))
  ([forms attr-key]
   (internal/identity-attr? forms attr-key)))

(defn enum-members
  "Members of a registered `:enum` attr schema, or an empty vector.

   Empty when the attr is not an enum (absence = empty, never nil). Reads the schema
   form directly — NO db query. PUBLIC: low-cardinality value surfaces reuse
   it. Members are Malli-form contents
   (keywords/strings/ints) — a third-party-structure boundary, hence `:any`."
  {:malli/schema [:=> [:cat :keyword] [:vector [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli enum members are arbitrary literal values, not a homogeneous collection.", :gen/elements [nil false 0 "" :k [] {}]}]]]}
  [attr-key]
  (form/enum-members (get (candidate-forms) attr-key)))

(defn register!
  "Define a new attribute so facts using it can be saved and queried.

   Adds one canonical declaration to the current candidate collector. Schema
   references are resolved only when [[build-projection]] validates the
   complete population, so namespace load order cannot change whether a
   declaration is accepted.

   Arguments:
     k - Schema keyword (use `::name` for auto-namespacing)
     v - Malli schema definition

   Returns the registered keyword `k`.

   Map render metadata stays in the authored form. Database storability and
   stable identity derive from installed attribute declarations; no map-level
   entity-kind marker is required.

   Example:
     (register! ::api-key [:string {:min 1}])
     (register! ::timeout [:int {:min 1000 :max 600000}])
     (register! :seon.eval [:map {:seon.db/attributes true
                                  :seon.render/ai 'foo}
                            [:seon.eval/id ...] ...])"
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]
                         [::definition ::definition]]
                  ::registry-key]}
  [k v]
  (internal/assert-non-nilable-value-schema! (candidate-forms) k v)
  (let [encoded (pr-str v)
        decoded (try
                  (edn/read-string encoded)
                  (catch Exception e
                    (throw
                      (ex-info
                        (str "schema/register! " k
                             ": schema forms must be readable EDN; "
                             "function objects and executable values belong "
                             "at function boundaries")
                        {:seon.schema/error :seon.schema/unreadable-form
                         :seon.schema/key k
                         :seon.schema/definition v
                         :seon.error/kind :user-input :seon.schema/unreadable-form k}
                        e))))]
    (when-not (= v decoded)
      (throw
        (ex-info
          (str "schema/register! " k
               ": schema form does not round-trip as EDN")
          {:seon.schema/error :seon.schema/non-round-tripping-form
           :seon.schema/key k
           :seon.schema/definition v
           :seon.error/kind :user-input :seon.schema/non-round-tripping-form k}))))
  ;; The schema authority's own shapes are the computed bootstrap population:
  ;; they must exist before the EDN loader and its admission gate can compile.
  ;; Every other JVM registration flows through seon.schema.edn/admit once that
  ;; namespace has finished loading; there is no hand-maintained exception set.
  (when-let [admit (some-> (find-ns 'seon.schema.edn)
                           (ns-resolve 'admit))]
    (admit
     {:seon.schema/forms (assoc (candidate-forms) k v)
      :seon.schema/identity k
      :seon.schema/admission
      {:seon.schema.admission/source *registration-admission-source*}}))
  (update-candidate-forms! assoc k v)
  k)

(defn unregister!
  "Stage removal of one schema from the current evaluation delta.

   Removal is published only if the evaluation's terminal transaction
   commits. Calling this outside an isolated registration delta refuses rather
   than mutating the process-wide candidate population."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]]
                  ::registry-key]}
  [k]
  (when-not *candidate-forms-overlay*
    (throw
     (ex-info
      "schema/unregister! requires an evaluation registration delta."
      {:seon.schema/error :seon.schema/unregister-outside-delta
       :seon.schema/key k
       :seon.error/kind :user-input :seon.schema/unregister-outside-delta k})))
  (swap! *candidate-forms-overlay* dissoc k)
  k)

(defn ^:no-doc contribute-candidate-forms!
  "Merge a prevalidated population into the candidate collector."
  {:malli/schema [:=> [:catn [::forms :map]] :map]}
  [forms]
  (update-candidate-forms! merge forms)
  (candidate-forms))

(defn form-string
  "Canonical, full EDN encoding of registered schema `k`, or nil when absent.

   Registration already proves the value round-trips, so this never truncates
   or replaces runtime objects with display placeholders. This is the durable
   `:seon.schema/form` value."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]]
                  [:maybe :string]]}
  [k]
  (some-> (get (candidate-forms) k) pr-str))

(declare compose-projection-data materialize-projection)

(def ^:private render-declaration-properties
  [:seon.render/ai :seon.render/html :seon.render/form])

(defn- render-declarations-in
  "Named render declarations carried by the selected schema forms."
  [forms schema-keys]
  (into []
        (mapcat
         (fn [schema-key]
           (let [definition (get forms schema-key)
                 properties
                 (some->> definition form/attr-form-properties)]
             (keep (fn [property]
                     (let [renderer (get properties property)]
                       (when (qualified-symbol? renderer)
                         {:seon.schema/key schema-key
                          :seon.schema/definition definition
                          :seon.render/property property
                          :seon.render/function renderer})))
                   render-declaration-properties))))
        (sort-by str schema-keys)))

(defn- map-shaped-schema?
  [compiled]
  (let [compiled (m/deref compiled)]
    (case (m/type compiled)
      :map true
      :and (boolean (some map-shaped-schema? (m/children compiled)))
      :or (every? map-shaped-schema? (m/children compiled))
      false)))

(defn- required-map-entries
  [compiled]
  (let [compiled (m/deref compiled)]
    (case (m/type compiled)
      :map
      (into {}
            (keep (fn [[entry-key properties child]]
                    (when-not (:optional properties)
                      [entry-key child])))
            (m/children compiled))

      :and
      (reduce merge {} (map required-map-entries (m/children compiled)))

      {})))

(declare schema-accepts-schema?)

(defn- map-schema-accepts-schema?
  [input declaring]
  (let [required-inputs (required-map-entries input)
        required-declarations (required-map-entries declaring)]
    (every? (fn [[entry-key input-child]]
              (when-let [declaring-child (get required-declarations entry-key)]
                (schema-accepts-schema? input-child declaring-child)))
            required-inputs)))

(defn- schema-accepts-schema?
  "Whether `input` structurally accepts every value in `declaring`.

   Maps are open: required input members must be guaranteed by the declaring
   shape, while optional input members and additional declaring members do not
   affect coherence. Unknown predicate implication fails closed."
  [input declaring]
  (let [input-form (m/form input)
        declaring-form (m/form declaring)
        input (m/deref input)
        declaring (m/deref declaring)]
    (cond
      (= input-form declaring-form) true
      (= (m/form input) (m/form declaring)) true
      (= :any (m/type input)) true

      (= :or (m/type declaring))
      (every? #(schema-accepts-schema? input %) (m/children declaring))

      (= :or (m/type input))
      (boolean
       (some #(schema-accepts-schema? % declaring) (m/children input)))

      (= :and (m/type input))
      (every? #(schema-accepts-schema? % declaring) (m/children input))

      (= :and (m/type declaring))
      ;; An intersection guarantees each child; any one sufficient child
      ;; proves acceptance without guessing predicate implication.
      (boolean
       (some #(schema-accepts-schema? input %) (m/children declaring)))

      (and (= :map (m/type input))
           (map-shaped-schema? declaring))
      (map-schema-accepts-schema? input declaring)

      :else false)))

(defn- function-arities
  [contract]
  (case (first contract)
    :=> [contract]
    :function (vec (rest contract))
    []))

(defn- arity-render-input-form
  [arity]
  (let [input (second arity)
        arguments (rest input)
        arguments (if (map? (first arguments)) (rest arguments) arguments)
        arguments (if (= :catn (first input))
                    (map (fn [[_ properties child]]
                           (if child child properties))
                         arguments)
                    arguments)]
    (first (remove #(= :seon.db/database-value %) arguments))))

(defn- render-contract-observation
  "Check a named render function against the declaration it serves.

   An attribute-level declaration describes the attribute value, so its first
   function input must accept that value schema. Entity and value declarations
   use their declaring schema. Additional declared arguments, including the
   call-prepared database value, are accretive and do not make the first input
   incoherent."
  [projection schema-key renderer]
  (if-let [contract
           (get (:seon.schema.projection/function-contracts projection)
                renderer)]
    (let [declaring
          (m/schema schema-key
                    (:seon.schema.projection/compile-options projection))
          arities (function-arities contract)
          accepted
          (some (fn [arity]
                  (when-let [input-form (arity-render-input-form arity)]
                    (let [input
                          (m/schema
                           (compilable-form
                            input-form
                            (predicate-functions-in projection))
                           (:seon.schema.projection/compile-options projection))]
                      (when (or (= input-form schema-key)
                                (= input-form :seon.schema/value)
                                ;; A render unit carries any value under
                                ;; :seon.render/value, including scalar refs.
                                (= input-form :seon.render/unit)
                                (and (vector? input-form)
                                     (= :or (first input-form))
                                     (some #{:seon.render/unit} (rest input-form)))
                                (schema-accepts-schema? input declaring))
                        input-form))))
                arities)]
      {:seon.schema/render-contract contract
       :seon.schema/render-input
       (or accepted (some arity-render-input-form arities))
       :seon.schema/render-contract-coherent? (boolean accepted)
       :seon.schema/render-contract-cause
       (when-not accepted
         :seon.schema/render-input-does-not-accept-declaring-shape)})
    {:seon.schema/render-contract nil
     :seon.schema/render-input nil
     :seon.schema/render-contract-coherent? false
     :seon.schema/render-contract-cause
     :seon.schema/render-function-has-no-declared-contract}))

(defn- render-contract-refusal!
  [{schema-key :seon.schema/key
    property :seon.render/property
    renderer :seon.render/function}
   {:seon.schema/keys [render-contract render-input render-contract-cause]}]
  (let [diagnostic
        (@error-diagnostic
         {:seon.error/kind :seon.schema/render-contract-incoherent
          :seon.error/message
          (str "Schema publication refused " schema-key ": " property
               " names " renderer " whose declared input "
               (pr-str render-input) " does not accept the declaring shape.")
          :seon.error/diagnostic-layer :schema-admission
          :seon.error/diagnostic-operation
          'seon.schema/render-contract-coherence
          :seon.error/diagnostic-member schema-key
          :seon.error/diagnostic-expected schema-key
          :seon.error/diagnostic-offending renderer
          :seon.error/diagnostic-cause render-contract-cause
          :seon.error/diagnostic-evidence
          {:seon.schema/key schema-key
           :seon.render/property property
           :seon.render/function renderer
           :seon.fn/spec render-contract
           :seon.fn/input render-input} :seon.schema/render-contract-incoherent true})]
    (throw (ex-info (:seon.error/message diagnostic) diagnostic))))

(defn- assert-render-contracts!
  [projection schema-keys]
  (doseq [{schema-key :seon.schema/key
           renderer :seon.render/function
           :as declaration}
          (render-declarations-in
           (:seon.schema.projection/forms projection) schema-keys)
          :let [observation
                (render-contract-observation projection schema-key renderer)]
          :when (not (:seon.schema/render-contract-coherent? observation))]
    (render-contract-refusal! declaration observation))
  projection)

(defn- schemas-rendered-by
  [projection renderer]
  (into #{}
        (comp
         (filter #(= renderer (:seon.render/function %)))
         (map :seon.schema/key))
        (render-declarations-in
         (:seon.schema.projection/forms projection)
         (keys (:seon.schema.projection/forms projection)))))

(defn- shape-row-in
  [forms schema-key definition]
  (let [props (or (form/schema-properties definition)
                        (form/attr-form-properties definition) {})
        required-attrs
        (some-> (internal/map-required-attrs forms definition) set)]
    (when (or (seq required-attrs)
              (and (:seon.db/attributes props)
                   (seq (form/map-entries forms definition))))
      (merge
        {:seon.schema/key schema-key
         :seon.schema/required-attrs required-attrs
         :seon.schema/entity? (boolean (:seon.db/attributes props))}
        (into {}
              (filter
               (fn [[property declaration]]
                 (and (qualified-keyword? property)
                      (= "seon.render" (namespace property))
                      (qualified-symbol? declaration))))
              props)))))

(defn build-projection
  "Build and validate one immutable runtime projection.

   `forms` is the canonical `{schema-key form}` population and optional
   `function-contracts` is `{qualified-symbol function-form}`. Every schema and
   contract compiles against the complete candidate registry, so validation is
   independent of declaration order. Schema/function dependency indexes and
   the entity catalog are derived here; none are stored as a second model.
   Pure at its boundary: the fold uses only build-scoped coordination and
   performs no default-registry, database, or var mutation."
  {:malli/schema
   [:function
    [:=> [:catn [::forms :map]] :map]
    [:=> [:catn [::forms :map] [::function-contracts :map]] :map]
    [:=> [:catn [::forms :map] [::function-contracts :map]
                   [:seon.schema/projection-options :map]]
     :map]]}
  ([forms]
   (build-projection
    forms {} {}))
  ([forms function-contracts]
   (build-projection
    forms function-contracts {}))
  ([forms function-contracts
    {:seon.schema/keys [schema-admissions function-admissions
                        function-source-admissions artifact-exports
                        pure-predicate-symbols predicate-functions
                        validate-render-contracts?]
     :or {schema-admissions {}
          function-admissions {}
          function-source-admissions {}
          artifact-exports #{}
          pure-predicate-symbols #{}
          predicate-functions {}}
     :as options}]
   (if (contains? forms :seon.schema.projection/forms)
     (materialize-projection
      (compose-projection-data forms function-contracts)
      options)
     (let [predicate-symbols
         (into (into #{} (mapcat predicate-symbols-in) (vals forms))
               (mapcat predicate-symbols-in)
               (vals function-contracts))
         predicate-functions
         (reduce (fn [bindings predicate]
                   (if (contains? bindings predicate)
                     bindings
                     (if-let [f (runtime-predicate predicate)]
                       (assoc bindings predicate f)
                       bindings)))
                 predicate-functions
                 predicate-symbols)
         schema-dependencies
         (canonical-reference-graph forms predicate-functions)
         _ (assert-acyclic-references!
            forms (keys forms) schema-dependencies)
         compiled-forms (bound-forms forms predicate-functions)
         compiled-contracts
         (bound-forms function-contracts predicate-functions)
         core-admission {:seon.schema.admission/source :core}
         registry (mr/composite-registry
                    (m/default-schemas)
                    (mr/fast-registry compiled-forms))
         options  {:registry registry}
         canonical-keys (set (keys forms))
         _ (doseq [[k form] (sort-by key forms)]
             (internal/assert-compilable-schema!
              compiled-forms k
              (get compiled-forms k)
              options)
             (internal/assert-non-nilable-value-schema! forms k form))
         compiled-schemas
         (into (sorted-map)
               (map (fn [k]
                      [k (m/schema (get compiled-forms k) options)]))
               (sort (keys forms)))
         compiled-function-contracts
         (into (sorted-map)
               (map (fn [[sym contract]]
                      [sym (m/function-schema contract options)]))
               compiled-contracts)
         !reference-advisories (atom {})
         reference-advisories
         (fn [reference role admission]
           (let [cache-key
                 [reference role
                  (:seon.schema.admission/source admission)]
                 pending
                 (delay
                   (internal/assert-complete-schema!
                    {:seon.schema/identity reference
                     :seon.schema/forms forms
                     :seon.schema/storable-attribute?
                     (fn [attribute] (@schema-datahike-storable-attribute-in?
                                      {:seon.schema.projection/forms forms} attribute))
                     :seon.schema/definition (get forms reference)
                     :seon.schema/compiled (get compiled-schemas reference)
                     :seon.schema/role role
                     :seon.schema/admission admission
                     :seon.schema/pure-predicate-symbols
                     pure-predicate-symbols
                     :seon.schema/canonical-keys canonical-keys}))
                 cached
                 (get
                  (swap! !reference-advisories
                         (fn [cache]
                           (if (contains? cache cache-key)
                             cache
                             (assoc cache cache-key pending))))
                  cache-key)]
             @cached))
         validation-base
         {:seon.schema/forms forms
          :seon.schema/compiled-forms compiled-forms
          :seon.schema/compiled-schemas compiled-schemas
          :seon.schema/schema-dependencies schema-dependencies
          :seon.schema/registry registry
          :seon.schema/compile-options options
          :seon.schema/canonical-keys canonical-keys
          :seon.schema/reference-advisories reference-advisories
          :seon.schema/admissions schema-admissions
          :seon.schema/pure-predicate-symbols pure-predicate-symbols
          :seon.schema/predicate-functions predicate-functions}
         validation-requests
         (into
          (mapv
           (fn [[k form]]
             (assoc validation-base
                    :seon.schema/identity k
                    :seon.schema/definition form
                    :seon.schema/direct-predicate-symbols
                    (predicate-symbols-in form)
                    :seon.schema/compiled (get compiled-schemas k)
                    :seon.schema/compiled-definition (get compiled-forms k)
                    :seon.schema/admission
                    (get schema-admissions k core-admission)))
           (sort-by key forms))
          (map
           (fn [[sym contract]]
             (assoc validation-base
                    :seon.schema/identity sym
                    :seon.schema/definition contract
                    :seon.schema/direct-predicate-symbols
                    (predicate-symbols-in contract)
                    :seon.schema/compiled
                    (get compiled-function-contracts sym)
                    :seon.schema/compiled-definition
                    (get compiled-contracts sym)
                    :seon.schema/admission
                    (get function-admissions sym core-admission)))
           (sort-by key function-contracts)))
         _ (fold-contract-validations validation-requests)
         reverse-schema-dependencies
         (reduce-kv
          (fn [reverse-edges dependent dependencies]
            (reduce (fn [edges dependency]
                      (update edges dependency (fnil conj #{}) dependent))
                    reverse-edges
                    dependencies))
          {}
          schema-dependencies)
        function-dependencies
        (into (sorted-map)
              (map (fn [[sym _form]]
                     [sym (direct-references*
                           (get compiled-function-contracts sym)
                           canonical-keys)]))
              function-contracts)
        reverse-function-dependencies
        (reduce-kv
         (fn [reverse-edges function-symbol dependencies]
           (reduce
            (fn [edges dependency]
              (update edges dependency (fnil conj #{}) function-symbol))
            reverse-edges
            dependencies))
         {}
         function-dependencies)
        shape-rows
        (into (sorted-map)
              (keep
                (fn [[k raw]]
                  (when-let [row (shape-row-in forms k raw)]
                    [k row])))
              forms)
        required-by-key
        (into (sorted-map)
              (map (fn [[k row]]
                     [k (:seon.schema/required-attrs row)]))
              shape-rows)
        raw-shape-index
        (reduce-kv
          (fn [index schema-key required-attrs]
            (reduce (fn [result attr]
                      (update result attr (fnil conj []) schema-key))
                    index
                    (or (seq required-attrs)
                        (map first (form/map-entries forms (get forms schema-key))))))
          (sorted-map)
          required-by-key)
        shape-rank
        (fn [schema-key]
          [(- (count (get required-by-key schema-key))) (str schema-key)])
        shape-index
        (into (sorted-map)
              (map (fn [[attr schema-keys]]
                     [attr (vec (sort-by shape-rank schema-keys))]))
              raw-shape-index)
        catalog  (->> shape-rows
                      (sort-by (comp str key))
                      (map second)
                      (filter :seon.schema/entity?)
                      vec)
        fingerprint
        (projection-fingerprint
         forms function-contracts schema-admissions function-admissions
         function-source-admissions artifact-exports pure-predicate-symbols)
        projection
        (with-predicate-functions
         {:seon.schema.projection/forms forms
          :seon.schema.projection/registry registry
          :seon.schema.projection/compile-options options
          :seon.schema.projection/schema-admissions schema-admissions
          :seon.schema.projection/function-admissions function-admissions
          :seon.schema.projection/function-source-admissions
          function-source-admissions
          :seon.schema.projection/artifact-exports artifact-exports
          :seon.schema.projection/pure-predicate-symbols pure-predicate-symbols
          :seon.schema.projection/schema-dependencies schema-dependencies
          :seon.schema.projection/canonical-keys canonical-keys
          :seon.schema.projection/reverse-schema-dependencies
          reverse-schema-dependencies
          :seon.schema.projection/function-contracts function-contracts
          :seon.schema.projection/function-dependencies function-dependencies
          :seon.schema.projection/reverse-function-dependencies
          reverse-function-dependencies
          :seon.schema.projection/required-by-key required-by-key
          :seon.schema.projection/shape-index shape-index
          :seon.schema.projection/shape-rows shape-rows
          :seon.schema.projection/catalog catalog
          :seon.schema.projection/fingerprint-version
          projection-fingerprint-version
          :seon.schema.projection/fingerprint fingerprint}
         predicate-functions)]
    (when validate-render-contracts?
      (assert-render-contracts! projection (keys forms)))
    (with-compiled-cache projection compiled-function-contracts)))))

(def ^:private projection-runtime-keys
  #{:seon.schema.projection/registry
    :seon.schema.projection/compile-options
    :seon.schema.projection/compiled
    :seon.schema.projection/predicate-functions})

(defn projection-pure-data
  "Return the EDN-only portion of one immutable projection."
  {:malli/schema [:=> [:catn [::projection :map]] :map]}
  [projection]
  (apply dissoc projection projection-runtime-keys))

(defn- projection-fingerprint-from-data
  [projection]
  (projection-fingerprint
   (:seon.schema.projection/forms projection)
   (:seon.schema.projection/function-contracts projection)
   (:seon.schema.projection/schema-admissions projection)
   (:seon.schema.projection/function-admissions projection)
   (:seon.schema.projection/function-source-admissions projection)
   (:seon.schema.projection/artifact-exports projection)
   (:seon.schema.projection/pure-predicate-symbols projection)))

(defn- reusable-projection-fingerprint
  [projection]
  (if (= projection-fingerprint-version
         (:seon.schema.projection/fingerprint-version projection))
    (:seon.schema.projection/fingerprint projection)
    (projection-fingerprint-from-data projection)))

(defn- reverse-dependencies
  [dependencies]
  (reduce-kv
   (fn [reverse-edges dependent dependency-keys]
     (reduce (fn [edges dependency]
               (update edges dependency (fnil conj #{}) dependent))
             reverse-edges
             dependency-keys))
   {}
   dependencies))

(defn- shape-projections
  [forms shape-rows]
  (let [required-by-key
        (into (sorted-map)
              (map (fn [[k row]]
                     [k (:seon.schema/required-attrs row)]))
              shape-rows)
        raw-shape-index
        (reduce-kv
         (fn [index schema-key required-attrs]
           (reduce (fn [result attr]
                     (update result attr (fnil conj []) schema-key))
                   index
                   (or (seq required-attrs)
                       (map first (form/map-entries forms (get forms schema-key))))))
         (sorted-map)
         required-by-key)
        shape-rank
        (fn [schema-key]
          [(- (count (get required-by-key schema-key))) (str schema-key)])
        shape-index
        (into (sorted-map)
              (map (fn [[attr schema-keys]]
                     [attr (vec (sort-by shape-rank schema-keys))]))
              raw-shape-index)
        catalog
        (->> shape-rows
             (sort-by (comp str key))
             (map second)
             (filter :seon.schema/entity?)
             vec)]
    {:seon.schema.projection/required-by-key required-by-key
     :seon.schema.projection/shape-index shape-index
     :seon.schema.projection/catalog catalog}))

(defn compose-projection-data
  "Compose preproved base pure data with one divergence pure-data delta.

   Row identities are map keys, so divergence naturally wins for a redefined
   base identity. The cross-population fingerprint and reverse/shape indexes
   are recomputed over the composed ordinary data; no schema is compiled here."
  {:malli/schema [:=> [:catn [::projection :map]
                             [:seon.schema/divergence-delta :map]]
                  :map]}
  [base divergence]
  (let [merge-map-key
        (fn [projection key]
          (assoc projection key
                 (merge (get projection key {})
                        (get divergence key {}))))
        keyed
        [:seon.schema.projection/forms
         :seon.schema.projection/schema-admissions
         :seon.schema.projection/function-admissions
         :seon.schema.projection/function-source-admissions
         :seon.schema.projection/schema-dependencies
         :seon.schema.projection/function-contracts
         :seon.schema.projection/function-dependencies
         :seon.schema.projection/shape-rows]
        composed
        (reduce merge-map-key (projection-pure-data base) keyed)
        composed
        (cond-> composed
          (contains? divergence :seon.schema.projection/artifact-exports)
          (assoc :seon.schema.projection/artifact-exports
                 (:seon.schema.projection/artifact-exports divergence))

          (contains? divergence
                     :seon.schema.projection/pure-predicate-symbols)
          (assoc :seon.schema.projection/pure-predicate-symbols
                 (:seon.schema.projection/pure-predicate-symbols divergence)))
        composed
        (merge composed
               {:seon.schema.projection/canonical-keys
                (set (keys (:seon.schema.projection/forms composed)))
                :seon.schema.projection/reverse-schema-dependencies
                (reverse-dependencies
                 (:seon.schema.projection/schema-dependencies composed))
                :seon.schema.projection/reverse-function-dependencies
                (reverse-dependencies
                 (:seon.schema.projection/function-dependencies composed))}
               (shape-projections
                (:seon.schema.projection/forms composed)
                (:seon.schema.projection/shape-rows composed)))]
    (assoc composed
           :seon.schema.projection/fingerprint
           (projection-fingerprint-from-data composed))))

(defn projection-delta
  "Return the row-keyed pure-data difference from `base` to `composed`.

   This is the ordinary value stored by the divergence cache. Runtime objects
   and population-wide indexes are never included."
  ;; the two arguments are NAMED DISTINCTLY, which sounds obvious and
  ;; was not: `:catn` refuses duplicate keys, so binding both to
  ;; `::projection` made this contract uncompilable — and nothing ever
  ;; compiled it, so it sat here unenforced until instrumentation
  ;; collected it (2026-07-27, the first thing `seon.instrument` found)
  {:malli/schema [:=> [:catn [::base :map] [::composed :map]] :map]}
  [base composed]
  (let [base (projection-pure-data base)
        composed (projection-pure-data composed)
        keyed
        [:seon.schema.projection/forms
         :seon.schema.projection/schema-admissions
         :seon.schema.projection/function-admissions
         :seon.schema.projection/function-source-admissions
         :seon.schema.projection/schema-dependencies
         :seon.schema.projection/function-contracts
         :seon.schema.projection/function-dependencies
         :seon.schema.projection/shape-rows]]
    (reduce
     (fn [delta key]
       (let [base-values (get base key {})
             changed
             (into (sorted-map)
                   (filter (fn [[identity value]]
                             (not= value (get base-values identity))))
                   (get composed key {}))]
         (cond-> delta (seq changed) (assoc key changed))))
     {:seon.schema.projection/artifact-exports
      (:seon.schema.projection/artifact-exports composed)
      :seon.schema.projection/pure-predicate-symbols
      (:seon.schema.projection/pure-predicate-symbols composed)}
     keyed)))

(def ^:private projection-delta-identities
  {:seon.schema.projection/forms :schema
   :seon.schema.projection/schema-admissions :schema
   :seon.schema.projection/schema-dependencies :schema
   :seon.schema.projection/shape-rows :schema
   :seon.schema.projection/function-admissions :function
   :seon.schema.projection/function-source-admissions :function
   :seon.schema.projection/function-contracts :function
   :seon.schema.projection/function-dependencies :function})

(defn maintain-projection-delta
  "Update one complete divergence delta by the identities changed in a commit.

   Unlike [[projection-delta]], this function never walks either projection
   population. Each changed identity performs a fixed number of keyed lookups;
   serialization cost is therefore bounded by the complete divergence value,
   not by the verified release population."
  {:malli/schema
   [:=> [:catn [::base :map]
                [::divergence-delta :map]
                [::projection :map]
                [:seon.schema/changed-schema-keys [:set :keyword]]
                [:seon.schema/changed-function-symbols [:set :symbol]]]
    :map]}
  [base divergence composed changed-schema-keys changed-function-symbols]
  (let [base (projection-pure-data base)
        composed (projection-pure-data composed)
        update-identity
        (fn [delta projection-key identity]
          (let [base-values (get base projection-key {})
                composed-values (get composed projection-key {})
                changed? (and (contains? composed-values identity)
                              (not= (get composed-values identity)
                                    (get base-values identity)))
                next-values
                (cond-> (get delta projection-key (sorted-map))
                  changed?
                  (assoc identity (get composed-values identity))

                  (not changed?)
                  (dissoc identity))]
            (if (seq next-values)
              (assoc delta projection-key next-values)
              (dissoc delta projection-key))))
        maintained
        (reduce-kv
         (fn [delta projection-key identity-class]
           (reduce
            (fn [result identity]
              (update-identity result projection-key identity))
            delta
            (case identity-class
              :schema changed-schema-keys
              :function changed-function-symbols)))
         divergence
         projection-delta-identities)]
    (assoc maintained
           :seon.schema.projection/artifact-exports
           (:seon.schema.projection/artifact-exports composed)
           :seon.schema.projection/pure-predicate-symbols
           (:seon.schema.projection/pure-predicate-symbols composed))))

(defn materialize-projection
  "Rematerialize registry/options over preproved pure projection data."
  {:malli/schema
   [:function
    [:=> [:catn [::projection :map]] :map]
    [:=> [:catn [::projection :map]
                 [:seon.schema/projection-options :map]]
     :map]]}
  ([pure-data]
   (materialize-projection pure-data {}))
  ([pure-data {:seon.schema/keys [predicate-functions]
               :or {predicate-functions {}}}]
   (let [forms (:seon.schema.projection/forms pure-data)
         contracts (:seon.schema.projection/function-contracts pure-data)
         predicate-symbols
         (into (into #{} (mapcat predicate-symbols-in) (vals forms))
               (mapcat predicate-symbols-in)
               (vals contracts))
         predicate-functions
         (reduce (fn [bindings predicate]
                   (if (contains? bindings predicate)
                     bindings
                     (if-let [f (runtime-predicate predicate)]
                       (assoc bindings predicate f)
                       bindings)))
                 predicate-functions
                 predicate-symbols)
         compiled-forms (bound-forms forms predicate-functions)
         registry (mr/composite-registry
                   (m/default-schemas)
                   (mr/fast-registry compiled-forms))
         options {:registry registry}]
     ;; Materialization still compiles the runtime objects, but all population
     ;; validation and pure-data derivation was completed before publication.
     (doseq [[_ form] compiled-forms]
       (m/schema form options))
     (with-compiled-cache
      (with-predicate-functions
       (assoc pure-data
              :seon.schema.projection/registry registry
              :seon.schema.projection/compile-options options)
       predicate-functions)
      (update-vals (bound-forms contracts predicate-functions)
                   #(m/function-schema % options))))))

(defn- predicate-functions-with
  [projection definitions]
  ;; The accumulator is seeded through the one derivation, which answers the
  ;; empty map the absent key means.
  (let [existing (predicate-functions-in projection)]
    (reduce
     (fn [bindings predicate]
       (if (contains? bindings predicate)
         bindings
         (if-let [f (runtime-predicate predicate)]
           (assoc bindings predicate f)
           bindings)))
     existing
     (into #{} (mapcat predicate-symbols-in) definitions))))

(defn- validate-one-contract!
  [projection identity definition admission]
  (let [forms (:seon.schema.projection/forms projection)
        predicate-functions (predicate-functions-in projection)
        compile-options
        (:seon.schema.projection/compile-options projection)
        bound (compilable-form definition predicate-functions)
        function? (qualified-symbol? identity)
        compiled ((if function? m/function-schema m/schema)
                  bound compile-options)]
    (assert-complete-contract!
     {:seon.schema/identity identity
      :seon.schema/definition definition
      :seon.schema/forms forms
      :seon.schema/admission admission
      :seon.schema/admissions
      (:seon.schema.projection/schema-admissions projection)
      :seon.schema/pure-predicate-symbols
      (:seon.schema.projection/pure-predicate-symbols projection)
      :seon.schema/predicate-functions predicate-functions
      :seon.schema/direct-predicate-symbols
      (predicate-symbols-in definition)
      :seon.schema/compiled compiled
      :seon.schema/compiled-definition bound
      :seon.schema/compiled-forms {identity bound}
      :seon.schema/compiled-schemas
      (if function? {} {identity compiled})
      :seon.schema/schema-dependencies
      (:seon.schema.projection/schema-dependencies projection)
      :seon.schema/registry
      (:seon.schema.projection/registry projection)
      :seon.schema/compile-options compile-options
      :seon.schema/canonical-keys
      (:seon.schema.projection/canonical-keys projection)})))

(defn- replace-reverse-dependencies
  [reverse-edges dependent before after]
  (let [without-before
        (reduce
         (fn [edges dependency]
           (let [dependents (disj (get edges dependency #{}) dependent)]
             (if (seq dependents)
               (assoc edges dependency dependents)
               (dissoc edges dependency))))
         reverse-edges
         before)]
    (reduce
     (fn [edges dependency]
       (update edges dependency (fnil conj #{}) dependent))
     without-before
     after)))

(defn- replace-shape-rows
  [shape-rows forms schema-keys]
  (reduce
   (fn [rows schema-key]
     (if-let [row (shape-row-in forms schema-key (get forms schema-key))]
       (assoc rows schema-key row)
       (dissoc rows schema-key)))
   shape-rows
   schema-keys))

(defn- function-dependents-of
  [projection schema-keys]
  (into #{}
        (mapcat
         #(get (:seon.schema.projection/reverse-function-dependencies
                projection)
               % #{}))
        schema-keys))

(defn projection-from-rows
  "Build one complete projection from committed schema and contract rows.

   Rows are ordinary database query results. Duplicate identities are rejected
   rather than resolved by iteration order, and every EDN form is parsed by
   the platform reader before the one [[build-projection]] mechanism runs."
  {:malli/schema
   [:function
    [:=> [:catn [::projection-input ::projection-input]]
     ::projection]
    [:=> [:catn [::projection-input ::projection-input]
                 [::projection ::projection]]
     ::projection]]}
  ([projection-input]
   (projection-from-rows projection-input {}))
  ([{:seon.schema/keys [database-value schema-rows function-contract-rows
                        function-source-rows artifact-exports
                        pure-predicate-symbols]
     :or {function-source-rows []
          artifact-exports #{}
          pure-predicate-symbols #{}}}
    reusable-projection]
   (let [recorded-admissions
         (if (contains? (dbi/-schema database-value) :seon.schema.admission/source)
           (into {}
                 (map (fn [[attribute identity source]]
                        [[attribute identity] {:seon.schema.admission/source source}]))
                 (d/q '[:find ?attribute ?identity ?source
                        :in $ [?attribute ...]
                        :where [?declaration ?attribute ?identity]
                               [?declaration :seon.schema.admission/source ?source]]
                      database-value [:seon.schema/key :seon.fn/sym]))
           {})
         admission-for-identity
         (fn [attribute identity]
           (get recorded-admissions [attribute identity]
                {:seon.schema.admission/source :agent
                 :seon.schema.admission/note
                 "The supplied database has no admission provenance for this identity."}))]
     (letfn [(parse-rows [rows identity-fn identity-label identity-attribute]
            (reduce
              (fn [parsed row]
                (when-not (and (sequential? row)
                               (= 3 (count row)))
                  (throw (ex-info (str "Malformed committed " identity-label
                                       " row.")
                                  {:seon.schema/error
                                   :seon.schema/malformed-projection-row
                                   :seon.schema/row row
                                   :seon.error/kind :core-bug :seon.schema/malformed-projection-row true})))
                (let [[raw-identity form-string _asserting-tx-eid] row
                      identity (identity-fn raw-identity)]
                  (when-not (string? form-string)
                    (throw (ex-info (str "Malformed committed " identity-label
                                         " form.")
                                    {:seon.schema/error
                                     :seon.schema/malformed-projection-form
                                     :seon.schema/row row
                                     :seon.error/kind :core-bug :seon.schema/malformed-projection-form true})))
                  (when (contains? parsed identity)
                    (throw (ex-info (str "Duplicate committed " identity-label
                                         " " identity ".")
                                    {:seon.schema/error
                                     :seon.schema/duplicate-projection-row
                                     :seon.schema/duplicate-projection-row identity
                                     :seon.schema/identity identity
                                     :seon.error/kind :core-bug})))
                  (assoc parsed identity
                         {:seon.schema.parsed/form
                          (edn/read-string form-string)
                          :seon.schema.parsed/admission
                          (admission-for-identity identity-attribute raw-identity)})))
              {}
              rows))]
    (let [schemas
          (parse-rows schema-rows
                  (fn [identity]
                    (if (keyword? identity)
                      identity
                      (throw (ex-info "Committed schema identity is not a keyword."
                                      {:seon.schema/error
                                       :seon.schema/malformed-projection-identity
                                       :seon.schema/identity identity
                                       :seon.error/kind :core-bug :seon.schema/malformed-projection-identity true}))))
                  "schema" :seon.schema/key)
          contracts
          (parse-rows function-contract-rows
                  (fn [identity]
                    (cond
                      (qualified-symbol? identity) identity
                      (string? identity)
                      (let [parsed (symbol identity)]
                        (if (qualified-symbol? parsed)
                          parsed
                          (throw (ex-info "Committed function identity is not qualified."
                                          {:seon.schema/error
                                           :seon.schema/malformed-projection-identity
                                           :seon.schema/identity identity
                                           :seon.error/kind :core-bug :seon.schema/malformed-projection-identity true}))))
                      :else
                      (throw (ex-info "Committed function identity is malformed."
                                      {:seon.schema/error
                                       :seon.schema/malformed-projection-identity
                                       :seon.schema/identity identity
                                       :seon.error/kind :core-bug :seon.schema/malformed-projection-identity true}))))
                   "function contract" :seon.fn/sym)
          source-admissions
          (reduce
           (fn [admissions row]
             (when-not (and (sequential? row) (= 3 (count row)))
               (throw (ex-info "Malformed committed function source row."
                               {:seon.schema/error
                                :seon.schema/malformed-projection-row
                                :seon.schema/row row
                                :seon.error/kind :core-bug :seon.schema/malformed-projection-row true})))
             (let [[raw-identity source _asserting-tx-eid] row
                   identity (cond
                              (qualified-symbol? raw-identity) raw-identity
                              (string? raw-identity) (symbol raw-identity)
                              :else raw-identity)]
               (when-not (and (qualified-symbol? identity) (string? source))
                 (throw (ex-info "Malformed committed function source row."
                                 {:seon.schema/error
                                  :seon.schema/malformed-projection-row
                                  :seon.schema/row row
                                  :seon.error/kind :core-bug :seon.schema/malformed-projection-row true})))
               (when (contains? admissions identity)
                 (throw (ex-info (str "Duplicate committed function source "
                                      identity ".")
                                 {:seon.schema/error
                                  :seon.schema/duplicate-projection-row
                                  :seon.schema/duplicate-projection-row identity
                                  :seon.schema/identity identity
                                  :seon.error/kind :core-bug})))
               (assoc admissions identity
                      (admission-for-identity :seon.fn/sym raw-identity))))
           {}
           function-source-rows)
          artifact-exports
          (into #{}
                (map (fn [export]
                       (cond
                         (qualified-symbol? export) export
                         (string? export)
                         (let [parsed (symbol export)]
                           (if (qualified-symbol? parsed)
                             parsed
                             (throw
                              (ex-info "Artifact export is not qualified."
                                       {:seon.schema/error
                                        :seon.schema/malformed-artifact-export
                                        :seon.schema/export export
                                        :seon.error/kind :core-bug :seon.schema/malformed-artifact-export true}))))
                         :else
                         (throw
                          (ex-info "Artifact export is malformed."
                                   {:seon.schema/error
                                    :seon.schema/malformed-artifact-export
                                    :seon.schema/export export
                                    :seon.error/kind :core-bug :seon.schema/malformed-artifact-export true})))))
                artifact-exports)
          forms
          (into {} (map (fn [[k row]]
                          [k (:seon.schema.parsed/form row)]))
                schemas)
          function-contracts
          (into {} (map (fn [[k row]]
                          [k (:seon.schema.parsed/form row)]))
                contracts)
          schema-admissions
          (into {} (map (fn [[k row]]
                          [k (:seon.schema.parsed/admission row)]))
                schemas)
          function-admissions
          (into {} (map (fn [[k row]]
                          [k (:seon.schema.parsed/admission row)]))
                contracts)
          fingerprint
          (projection-fingerprint
           forms function-contracts schema-admissions function-admissions
           source-admissions artifact-exports pure-predicate-symbols)]
      (if (= fingerprint
             (:seon.schema.projection/fingerprint reusable-projection))
        reusable-projection
        (build-projection
         forms
         function-contracts
         {:seon.schema/schema-admissions schema-admissions
          :seon.schema/function-admissions function-admissions
          :seon.schema/function-source-admissions source-admissions
         :seon.schema/artifact-exports artifact-exports
          :seon.schema/pure-predicate-symbols pure-predicate-symbols
          :seon.schema/predicate-functions {}
          :seon.schema/validate-render-contracts? true})))))))

(defn- refuse-projection-source
  "Typed refusal naming the value handed in place of a database.

   The class this closes: a refused read handed on as `db` produced a
   projection whose forms table was empty, and every downstream
   `storable-attribute-in?` then answered from that empty table. Absence of
   schema rows is never health here, so the derivation refuses loudly with
   the offending value instead of returning a projection with no forms
   (critical finding #20)."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption
                     :seon.schema.admission/polymorphic-boundary
                     :seon.schema.admission/reason
                     "The refused value is whatever a caller handed in place of a database value."
                     :gen/elements [nil false {} :k]}]]
    :seon.error/value]}
  [db]
  (@error-diagnostic
   {:seon.error/kind :seon.schema/invalid-projection-source
    :seon.error/message
    "The program projection requires a Datahike database value; a projection with no forms is never derived from one that is not."
    :seon.error/diagnostic-layer :schema-derivation
    :seon.error/diagnostic-operation 'seon.schema/projection-from-database
    :seon.error/diagnostic-member :seon.schema/database-value
    :seon.error/diagnostic-expected :seon.db/database-value
    :seon.error/diagnostic-offending db
    :seon.error/diagnostic-cause :seon.schema/invalid-projection-source
    :seon.error/diagnostic-evidence
    {:seon.schema/database-value db}}))

(defn- derive-projection-from-database
  [db reusable-projection]
  (when-not (db-utils/db? db)
    (let [refusal (refuse-projection-source db)]
      (throw (ex-info (:seon.error/message refusal) refusal))))
  (projection-from-rows
   {:seon.schema/database-value db
    :seon.schema/schema-rows
    (d/q
     '[:find ?key ?form ?tx
       :where
       [?schema :seon.schema/key ?key ?tx]
       [?schema :seon.schema/form ?form]]
     db)
    :seon.schema/function-contract-rows
    (d/q
     '[:find ?sym ?spec ?tx
       :where
       [?function :seon.fn/sym ?sym]
       [?function :seon.fn/spec ?spec ?tx]]
     db)
    :seon.schema/function-source-rows
    (d/q
     '[:find ?sym ?source ?tx
       :where
       [?function :seon.fn/sym ?sym]
       [?function :seon.fn/source ?source ?tx]]
     db)
    :seon.schema/artifact-exports #{}
    :seon.schema/pure-predicate-symbols #{}}
   reusable-projection))

(defn projection-from-database
  "Build the immutable program projection at exactly `db`.

   The caller owns the resulting projection and passes it to later operations.
   The optional reusable projection avoids recompilation only when its
   canonical fingerprint equals the queried rows. No process-global holder
   retains a cluster's projection."
  {:malli/schema
   [:function
    [:=> [:catn [:seon.schema/database-value :map]] ::projection]
    [:=> [:catn [:seon.schema/database-value :map]
                 [::projection ::projection]]
     ::projection]]}
  ([db]
   (derive-projection-from-database db {}))
  ([db reusable-projection]
   ;; The reusable value may carry process-local predicate functions which are
   ;; deliberately absent from its pure fingerprint. It therefore remains an
   ;; explicit caller-owned optimization rather than entering the database-only
   ;; cache.
   (derive-projection-from-database db reusable-projection)))

(defn projection-with-schema
  "Validate the projection produced by exactly one schema replacement."
  {:malli/schema
   [:=> [:catn [::projection ::projection]
                [::registry-key ::registry-key]
                [::definition ::definition]
                [:seon.schema/admission :map]]
    ::projection]}
  [projection schema-key definition admission]
  (let [old-forms (:seon.schema.projection/forms projection)
        old-definition (get old-forms schema-key ::absent)
        forms (assoc old-forms schema-key definition)
        predicate-functions (predicate-functions-with projection [definition])
        registry (projection-registry forms predicate-functions)
        compile-options {:registry registry}
        canonical-keys
        (conj (or (:seon.schema.projection/canonical-keys projection)
                  (set (keys old-forms)))
              schema-key)
        direct-dependencies
        (direct-reference-keys-in
         definition predicate-functions canonical-keys registry)
        old-dependencies
        (get (:seon.schema.projection/schema-dependencies projection)
             schema-key #{})
        schema-dependencies
        (assoc (:seon.schema.projection/schema-dependencies projection)
               schema-key direct-dependencies)
        _ (assert-acyclic-references!
           forms [schema-key] schema-dependencies)
        compiled (m/schema (compilable-form definition predicate-functions)
                           compile-options)
        reverse-schema-dependencies
        (replace-reverse-dependencies
         (:seon.schema.projection/reverse-schema-dependencies projection)
         schema-key old-dependencies direct-dependencies)
        affected-schema-keys
        (dependent-schema-keys projection #{schema-key})
        schema-admissions
        (assoc (:seon.schema.projection/schema-admissions projection)
               schema-key admission)
        candidate
        (with-predicate-functions
         (assoc projection
                :seon.schema.projection/forms forms
                :seon.schema.projection/registry registry
                :seon.schema.projection/compile-options compile-options
                :seon.schema.projection/canonical-keys canonical-keys
                :seon.schema.projection/schema-admissions schema-admissions
                :seon.schema.projection/schema-dependencies schema-dependencies
                :seon.schema.projection/reverse-schema-dependencies
                reverse-schema-dependencies)
         predicate-functions)
        _ (doseq [affected (sort-by str affected-schema-keys)]
            (validate-one-contract!
             candidate affected (get forms affected)
             (get schema-admissions affected
                  {:seon.schema.admission/source :core})))
        affected-function-symbols
        (function-dependents-of candidate affected-schema-keys)
        _ (doseq [function-symbol (sort-by str affected-function-symbols)]
            (validate-one-contract!
             candidate function-symbol
             (get (:seon.schema.projection/function-contracts candidate)
                  function-symbol)
             (get (:seon.schema.projection/function-admissions candidate)
                  function-symbol
                  {:seon.schema.admission/source :core})))
        shape-rows
        (replace-shape-rows
         (:seon.schema.projection/shape-rows projection)
         forms affected-schema-keys)
        shape-data
        (if (identical? shape-rows
                        (:seon.schema.projection/shape-rows projection))
          (select-keys
           projection
           [:seon.schema.projection/required-by-key
            :seon.schema.projection/shape-index
            :seon.schema.projection/catalog])
          (shape-projections forms shape-rows))
        fingerprint
        (-> (reusable-projection-fingerprint projection)
            (replace-fingerprint-entry
             :forms schema-key old-definition definition)
            (replace-fingerprint-entry
             :schema-admissions schema-key
             (get (:seon.schema.projection/schema-admissions projection)
                  schema-key ::absent)
             admission))]
    (let [result
          (with-compiled-cache
           (merge candidate shape-data
                  {:seon.schema.projection/shape-rows shape-rows
                   :seon.schema.projection/fingerprint-version
                   projection-fingerprint-version
                   :seon.schema.projection/fingerprint fingerprint}))]
      (assert-render-contracts! result affected-schema-keys)
      result)))

(defn pulled-schema-key
  "Stable registry key for `schema-key` pulled under exactly `selector`."
  {:malli/schema [:=> [:cat ::registry-key :seon.schema/pull-selector]
                  ::registry-key]}
  [schema-key selector]
  (keyword "seon.schema.pulled" (id/id [schema-key selector])))

(defn- pulled-selector-refusal
  "Typed refusal naming the selector element whose shape is unknown."
  {:malli/schema
   [:=> [:cat ::registry-key :seon.schema/pull-selector-element
         :keyword :string]
    :seon.error/value]}
  [schema-key selector-element cause message]
  (@error-diagnostic
   {:seon.error/kind :seon.schema/unsupported-pull-selector
    :seon.error/message message
    :seon.error/diagnostic-layer :schema-derivation
    :seon.error/diagnostic-operation 'seon.schema/pulled-form-in
    :seon.error/diagnostic-member schema-key
    :seon.error/diagnostic-expected :seon.schema/pull-selector-element
    :seon.error/diagnostic-offending selector-element
    :seon.error/diagnostic-cause cause
    :seon.error/diagnostic-evidence
    {:seon.schema/key schema-key
     :seon.schema/pull-selector-element selector-element}}))

(defn- entity-entry-map
  "Map one entity schema's attributes to their Malli map entries."
  {:malli/schema
   [:=> [:cat ::projection [:or :nil ::registry-key]]
    [:map-of :qualified-keyword :seon.schema/value]]}
  [projection schema-key]
  (if-let [entity-form
           (get (:seon.schema.projection/forms projection) schema-key)]
    (into {} (map (fn [entry] [(first entry) entry]))
          (form/map-entries (:seon.schema.projection/forms projection) entity-form))
    {}))

(defn- reverse-target-schema
  "The sole entity schema declaring `attribute`, or nil when ambiguous."
  {:malli/schema
   [:=> [:cat ::projection :qualified-keyword]
    [:or :nil ::registry-key]]}
  [projection attribute]
  (let [rows (:seon.schema.projection/shape-rows projection)
        candidates
        (->> (get-in projection
                     [:seon.schema.projection/shape-index attribute])
             (filter #(true? (:seon.schema/entity? (get rows %)))))]
    (when (= 1 (count candidates)) (first candidates))))

(defn- selector-target-schema
  "The sole entity schema identified by a sub-selector's identity attribute."
  {:malli/schema
   [:=> [:cat ::projection :seon.schema/parsed-pull-spec]
    [:or :nil ::registry-key]]}
  [projection pull-spec]
  (let [forms (:seon.schema.projection/forms projection)
        candidates
        (into #{}
              (keep (fn [[_ options]]
                      (some-> (get forms (:attr options))
                              form/attr-form-properties
                              :seon.program/row-schema)))
              (:attrs pull-spec))]
    (when (= 1 (count candidates)) (first candidates))))

(declare pulled-form-from-spec)

(defn- pulled-attribute-entry
  "Derive one result-map entry from Datahike's parsed attribute options."
  {:malli/schema
   [:=> [:cat ::projection [:or :nil ::registry-key]
         :keyword :seon.schema/parsed-pull-attribute-options
         [:set ::registry-key]]
    :seon.schema/pulled-entry-result]}
  [projection schema-key raw-key options seen]
  (let [forms (:seon.schema.projection/forms projection)
        attribute (:attr options)
        result-key (or (:as options) raw-key)
        forward? (= raw-key attribute)
        owner-key (or schema-key :seon.schema/registry-key)]
    (cond
      (contains? options :recursion)
      (pulled-selector-refusal
       owner-key {raw-key (:recursion options)} :seon.schema/pull-recursion
       "Recursive selectors may return nested and id-only maps; this derivation refuses that union.")

      (= :db/id raw-key)
      [:db/id :int]

      (not (keyword? attribute))
      (pulled-selector-refusal
       owner-key raw-key :seon.schema/dynamic-pull-attribute
       "A pulled form requires a keyword attribute in the supplied projection.")

      :else
      (let [attribute-form
            (or (get forms attribute)
                (case attribute :db/ident :keyword :db/txInstant :inst nil))]
        (if-not attribute-form
          (pulled-selector-refusal
           owner-key raw-key :seon.schema/unknown-pull-attribute
           (str "Pull attribute " attribute " has no declaration."))
          (let [datahike-attribute
                (case attribute
                  :db/ident {:db/valueType :db.type/keyword
                             :db/cardinality :db.cardinality/one}
                  :db/txInstant {:db/valueType :db.type/instant
                                 :db/cardinality :db.cardinality/one}
                  (try
                    (@schema-datahike-malli->datahike-attr-in
                     projection attribute)
                    (catch clojure.lang.ExceptionInfo failure failure)))]
            (if (instance? Throwable datahike-attribute)
              (pulled-selector-refusal
               owner-key raw-key :seon.schema/unstorable-pull-attribute
               (ex-message datahike-attribute))
              (let [reference? (= :db.type/ref
                                  (:db/valueType datahike-attribute))
                    component? (:db/isComponent datahike-attribute)
                    declared-many?
                    (= :db.cardinality/many
                       (@schema-datahike-form->cardinality-in
                        projection attribute-form))
                    many? (if forward? declared-many? (not component?))
                    component-schema
                    (:seon.db/component-schema
                     (form/attr-form-properties attribute-form))
                    nested-target
                    (or component-schema
                        (when-not forward?
                          (reverse-target-schema projection attribute))
                        (when-let [subpattern (:subpattern options)]
                          (selector-target-schema projection subpattern)))
                    child
                    (cond
                      (contains? options :subpattern)
                      (pulled-form-from-spec projection nested-target
                                             (:subpattern options) seen
                                             {raw-key :subpattern})

                      (and reference? component? forward?)
                      (if component-schema
                        (pulled-form-from-spec
                         projection component-schema
                         (pull-api/pull-plan-spec
                          (pull-api/compile-pull-plan '[*]))
                         seen raw-key)
                        (pulled-selector-refusal
                         owner-key raw-key :seon.schema/missing-component-schema
                         (str "Component attribute " attribute
                              " has no declared target schema.")))

                      reference?
                      [:map [:db/id :int]
                       [:db/ident {:optional true} :keyword]]

                      (not forward?)
                      (pulled-selector-refusal
                       owner-key raw-key :seon.schema/reverse-non-reference
                       (str "Reverse attribute " raw-key
                            " does not name a reference."))

                      :else
                      (@schema-datahike-form->child-form-in
                       projection attribute-form))]
                (if (map? child)
                  child
                  (let [limit (get options :limit 1000)
                        pulled-value
                        (if many?
                          (if (nil? limit)
                            [:vector child]
                            [:vector {:max limit} child])
                          child)
                        pulled-value
                        (if (contains? options :default)
                          [:or pulled-value [:= (:default options)]]
                          pulled-value)]
                    (if (contains? options :default)
                      [result-key pulled-value]
                      [result-key {:optional true} pulled-value])))))))))))

(defn- pulled-form-from-spec
  "Derive a pull-result form from one parsed Datahike PullSpec."
  {:malli/schema
   [:=> [:cat ::projection [:or :nil ::registry-key]
         :seon.schema/parsed-pull-spec [:set ::registry-key]
         :seon.schema/pull-selector-element]
    :seon.schema/pulled-form-result]}
  [projection schema-key pull-spec seen selector-element]
  (let [forms (:seon.schema.projection/forms projection)
        wildcard? (:wildcard? pull-spec)]
    (cond
      (and schema-key (nil? (get forms schema-key)))
      (pulled-selector-refusal
       schema-key selector-element :seon.schema/unknown-schema
       (str "Cannot derive a pulled form for unknown schema " schema-key "."))

      (and wildcard? (nil? schema-key))
      (pulled-selector-refusal
       :seon.schema/registry-key selector-element
       :seon.schema/unknown-pull-target
       "A wildcard nested selector requires its target entity schema.")

      (and wildcard? (contains? seen schema-key))
      (pulled-selector-refusal
       schema-key selector-element :seon.schema/pull-component-cycle
       (str "Wildcard component expansion revisits " schema-key "."))

      :else
      (let [seen (cond-> seen schema-key (conj schema-key))
            wildcard-attributes
            (if wildcard?
              (into {:db/id {:attr :db/id}}
                    (map (fn [attribute] [attribute {:attr attribute}]))
                    (keys (entity-entry-map projection schema-key)))
              {})
            attributes (merge wildcard-attributes (:attrs pull-spec))
            entries
            (reduce-kv
             (fn [result raw-key options]
               (if (map? result)
                 (reduced result)
                 (let [entry (pulled-attribute-entry
                              projection schema-key raw-key options seen)]
                   (if (map? entry)
                     (reduced entry)
                     (conj result entry)))))
             [] attributes)]
        (if (map? entries) entries (into [:map] entries))))))

(defn projection-with-pulled-form-in
  "Return `projection` with the selector-specific form registered."
  {:malli/schema
   [:=> [:cat ::projection ::registry-key :seon.schema/pull-selector]
    :seon.schema/pulled-projection-result]}
  [projection schema-key selector]
  (let [derived-key (pulled-schema-key schema-key selector)
        cache-key [::pulled-form (id/id [schema-key selector])]]
    (projection-cache-value
     projection cache-key
     (fn []
       (let [pull-spec (pull-api/pull-plan-spec
                        (pull-api/compile-pull-plan selector))
             definition (pulled-form-from-spec
                         projection schema-key pull-spec #{}
                         (or (first selector) '*))]
         (if (map? definition)
           definition
           (projection-with-schema
            projection derived-key definition
            {:seon.schema.admission/source :core})))))))

(defn pulled-form-in
  "Return the Malli form of `schema-key` pulled under exactly `selector`.

   Unsupported selectors return a flat refusal naming their element."
  {:malli/schema
   [:=> [:cat ::projection ::registry-key :seon.schema/pull-selector]
    :seon.schema/pulled-form-result]}
  [projection schema-key selector]
  (let [derived-key (pulled-schema-key schema-key selector)
        projected (projection-with-pulled-form-in
                   projection schema-key selector)]
    (if (and (map? projected) (:seon.error/kind projected))
      projected
      (get (:seon.schema.projection/forms projected) derived-key))))

(defn projection-without-schema
  "Validate the projection produced by removing one unused schema.

   Removal refuses while any schema or function contract depends on the
   affected key. Database-value usage is guarded separately by the terminal
   transaction function that owns schema-attribute retraction."
  {:malli/schema
   [:=> [:catn [::projection ::projection]
                [::registry-key ::registry-key]]
    ::projection]}
  [projection schema-key]
  (let [{:seon.schema.blockers/keys [schema-keys function-symbols]
         :as blockers}
        (schema-removal-blockers projection schema-key)]
    (when (or (seq schema-keys) (seq function-symbols))
      (throw
       (ex-info
        (str "Schema removal refused for " schema-key
             ": installed contracts still depend on it.")
        (assoc blockers
               :seon.schema/error :seon.schema/schema-in-use
               :seon.schema/key schema-key
               :seon.error/kind :user-input))))
    (build-projection
     (dissoc (:seon.schema.projection/forms projection) schema-key)
     (:seon.schema.projection/function-contracts projection)
     {:seon.schema/schema-admissions
      (dissoc (:seon.schema.projection/schema-admissions projection)
              schema-key)
      :seon.schema/function-admissions
      (:seon.schema.projection/function-admissions projection)
      :seon.schema/function-source-admissions
      (:seon.schema.projection/function-source-admissions projection)
      :seon.schema/artifact-exports
      (:seon.schema.projection/artifact-exports projection)
      :seon.schema/pure-predicate-symbols
      (:seon.schema.projection/pure-predicate-symbols projection)
      :seon.schema/predicate-functions (predicate-functions-in projection)
      :seon.schema/validate-render-contracts? true})))

(defn projection-with-function-contract
  "Validate the projection produced by one function-contract replacement."
  {:malli/schema
   [:=> [:catn [::projection ::projection]
                [:seon.schema/function-symbol :qualified-symbol]
                [::definition ::definition]
                [:seon.schema/admission :map]]
    ::projection]}
  [projection function-symbol definition admission]
  (let [forms (:seon.schema.projection/forms projection)
        old-contracts (:seon.schema.projection/function-contracts projection)
        old-definition (get old-contracts function-symbol ::absent)
        contracts (assoc old-contracts function-symbol definition)
        predicate-functions (predicate-functions-with projection [definition])
        registry (projection-registry forms predicate-functions)
        compile-options {:registry registry}
        compiled
        (m/function-schema
         (compilable-form definition predicate-functions)
         compile-options)
        canonical-keys
        (or (:seon.schema.projection/canonical-keys projection)
            (set (keys forms)))
        dependencies
        (direct-references* compiled canonical-keys)
        old-dependencies
        (get (:seon.schema.projection/function-dependencies projection)
             function-symbol #{})
        function-admissions
        (assoc (:seon.schema.projection/function-admissions projection)
               function-symbol admission)
        function-source-admissions
        (assoc
         (:seon.schema.projection/function-source-admissions projection)
         function-symbol admission)
        candidate
        (with-predicate-functions
         (assoc projection
               :seon.schema.projection/registry registry
               :seon.schema.projection/compile-options compile-options
               :seon.schema.projection/canonical-keys canonical-keys
               :seon.schema.projection/function-contracts contracts
               :seon.schema.projection/function-admissions function-admissions
               :seon.schema.projection/function-source-admissions
               function-source-admissions
               :seon.schema.projection/function-dependencies
               (assoc (:seon.schema.projection/function-dependencies projection)
                      function-symbol dependencies)
               :seon.schema.projection/reverse-function-dependencies
               (replace-reverse-dependencies
                (:seon.schema.projection/reverse-function-dependencies
                 projection)
                function-symbol old-dependencies dependencies))
         predicate-functions)
        _ (validate-one-contract! candidate function-symbol definition admission)
        _ (assert-render-contracts!
           candidate (schemas-rendered-by candidate function-symbol))
        fingerprint
        (-> (reusable-projection-fingerprint projection)
            (replace-fingerprint-entry
             :function-contracts function-symbol old-definition definition)
            (replace-fingerprint-entry
             :function-admissions function-symbol
             (get (:seon.schema.projection/function-admissions projection)
                  function-symbol ::absent)
             admission)
            (replace-fingerprint-entry
             :function-source-admissions function-symbol
             (get
              (:seon.schema.projection/function-source-admissions projection)
              function-symbol ::absent)
             admission))]
    (assoc candidate
           :seon.schema.projection/fingerprint-version
           projection-fingerprint-version
           :seon.schema.projection/fingerprint fingerprint)))

(defn activate-projection!
  "Return an already validated projection.

   A live cluster publishes this value through its own `::projection-state`;
   the schema namespace retains no process-global active generation."
  {:malli/schema [:=> [:catn [::projection :map]] :map]}
  [projection]
  projection)

(defn activate!
  "Validate and atomically activate a complete `{schema-key form}` set.

   The candidate is fully built before the declaration collector changes. Existing canonical function contracts are revalidated
   against the replacement schema population. Returns the activated projection."
  {:malli/schema [:=> [:catn [::forms :map]] :map]}
  [forms]
  (when-let [admit (some-> (find-ns 'seon.schema.edn)
                           (ns-resolve 'admit))]
    (admit {:seon.schema/forms forms}))
  (build-projection forms))

(defn current-projection
  "The evaluation-local disposable projection, or nil outside one delta."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  []
  (active-projection))

(defn handed-projection
  "The immutable projection explicitly supplied for this operation, or nil."
  {:malli/schema [:=> [:cat] [:maybe :seon.schema/projection]]}
  []
  (or *projection*
      (some-> *projection-state* deref :seon.schema/projection)))

(defn entity-catalog
  "Derived database-storable shape catalog for packaged schema facts."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (:seon.schema.projection/catalog (build-projection (candidate-forms))))

(defn current-keys
  "Snapshot of all currently-registered schema keywords.

   Used by detect-and-tee in eval-batch! for atom-diff schema detection (before vs
   after an eval reveals what the form registered)."
  {:malli/schema [:=> [:cat] [:set :keyword]]}
  []
  (set (keys (candidate-forms))))

(defn snapshot
  "Immutable `{schema-key form}` snapshot for one eval transition."
  {:malli/schema [:=> [:cat] :map]}
  []
  (candidate-forms))

(declare delta-over)

(defn begin-registration-delta
  "Create an isolated schema delta for one synchronous eval.

   With a projection, the overlay starts from exactly that database value's
   forms. The zero-arity compatibility path remains the canonical JVM
   declaration population; neither path publishes the overlay."
  {:malli/schema
   [:function
    [:=> [:cat] :map]
    [:=> [:catn [::projection ::projection]] :map]]}
  ;; EACH ARITY HANDS ON ONLY WHAT IT HAS. Delegating through the 1-arity
  ;; with `nil` made this function violate its own declared contract under
  ;; the instrumentation every live cluster arms, and wrote a stored nil
  ;; projection into the delta besides.
  ([]
   (delta-over nil))
  ([projection]
   (delta-over projection)))

(defn- delta-over
  [projection]
  (let [before (or (:seon.schema.projection/forms projection)
                   (candidate-forms))]
    (cond-> {:seon.schema.delta/before before
             :seon.schema.delta/candidate-forms (atom before)}
      projection (assoc :seon.schema.delta/projection projection))))

(defn call-with-registration-delta
  "Call the function with registrations staged in the supplied delta.

   Runtime evaluation defaults to agent admission. Build indexing passes core
   admission explicitly; the overlay mechanism is identical, but the two
   producers retain their distinct contract strictness."
  {:malli/schema
   [:function [:=> [:catn [:seon.schema/registration-delta :map] [:seon.schema/body [:fn clojure.core/ifn?]]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]] [:=> [:catn [:seon.schema/registration-delta :map] [:seon.schema/admission [:map [:seon.schema.admission/source [:enum :core :agent]]]] [:seon.schema/body [:fn clojure.core/ifn?]]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]]]}
  ([delta f]
   (call-with-registration-delta
    delta {:seon.schema.admission/source :agent} f))
  ([delta admission f]
   (binding [*candidate-forms-overlay*
             (:seon.schema.delta/candidate-forms delta)
             *projection* (:seon.schema.delta/projection delta)
             *registration-admission-source*
             (:seon.schema.admission/source admission)]
     (f))))

(defn- changed-candidate-keys [before after]
  (into #{}
        (keep (fn [k]
                (when (not= (get before k ::absent)
                            (get after k ::absent))
                  k)))
        (into (set (keys before)) (keys after))))

(defn changed-keys
  "Schema keys whose canonical form differs from `before`, including new keys."
  {:malli/schema [:=> [:catn [::before :map]] [:set :keyword]]}
  [before]
  (if-let [candidate (:seon.schema.delta/candidate-forms before)]
    (changed-candidate-keys (:seon.schema.delta/before before) @candidate)
    (changed-candidate-keys before (candidate-forms))))

(defn registration-delta-form
  "The evaluated canonical form registered for `schema-key`, or nil."
  {:malli/schema
   [:=> [:catn [:seon.schema/registration-delta :map]
                [::registry-key ::registry-key]]
    [:maybe ::definition]]}
  [delta schema-key]
  (get @(:seon.schema.delta/candidate-forms delta) schema-key))

(defn commit-registration-delta!
  "Return the identities changed in one isolated registration delta."
  {:malli/schema
   [:=> [:catn [:seon.schema/registration-delta :map]] [:set :keyword]]}
  [delta]
  (changed-keys delta))

(defn restore!
  "Revert only the schema delta represented by `before`.

   Eval-owned deltas are isolated overlays, so failure only discards that
   overlay. Plain snapshots retain the single-threaded compatibility behavior;
   exact test-state capture remains [[snapshot-state]] / [[restore-state!]]."
  {:malli/schema [:=> [:catn [::before :map]] :nil]}
  [before]
  (when-let [candidate (:seon.schema.delta/candidate-forms before)]
    (reset! candidate (:seon.schema.delta/before before)))
  nil)

(defn register-all!
  "Register multiple schemas at once from keyword/definition pairs.

   Returns the set of registered keywords. Throws if an odd
   number of arguments is provided.

   Example:
     (register-all!
       ::user-id    :uuid
       ::user-name  [:string {:min 1}]
       ::user-email [:string {:min 5}])"
  {:malli/schema [:=> [:catn [:seon.schema/kvs [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli's repeated concatenation enforces complete keyword/definition pairs; there is no additional cross-pair relation.", :gen/elements [[]]} [:cat :keyword :seon.schema/definition]]]] [:set :keyword]]}
  [& kvs]
  ;; NOTE: each kv pair is a [registry-key form] pair; the variadic slot
  ;; can't enumerate them, hence `[:* :any]`.
  (assert (even? (count kvs)) "register-all! requires pairs of [key schema]")
  (let [pairs (partition 2 kvs)]
    (doseq [[k v] pairs]
      (register! k v))
    (set (map first pairs))))

;;; ---------------------------------------------------------------------------
;;; Introspection
;;; ---------------------------------------------------------------------------

(defn registered-schemas
  "A map of all registered domain schemas (Malli's built-ins excluded)."
  {:malli/schema [:=> [:cat] :map]}
  []
  (or (:seon.schema.projection/forms (active-projection))
      (candidate-forms)))

(defn- dependency-first-schema-keys
  [reference-graph schema-keys]
  (letfn [(visit [ordered seen schema-key]
            (if (contains? seen schema-key)
              [ordered seen]
              (let [[ordered seen]
                    (reduce
                     (fn [[ordered seen] reference]
                       (visit ordered seen reference))
                     [ordered seen]
                     (sort-by str (get reference-graph schema-key)))]
                [(conj ordered schema-key) (conj seen schema-key)])))]
    (first
     (reduce
      (fn [[ordered seen] schema-key]
        (visit ordered seen schema-key))
      [[] #{}]
      (sort-by str schema-keys)))))

(defn canonical-schema-rows
  "Build canonical rows for a complete or database-extending population.

  A database-extending population may reference an already persisted schema
  whose definition is deliberately absent from `forms`. Malli's own walker
  classifies those external references so the row still carries its durable
  edge; the terminal transaction validates the candidate against the complete
  database-derived projection before admitting it. Every other projection
  error remains a refusal here."
  {:malli/schema
   [:function
    [:=> [:cat] [:vector :map]]
    [:=> [:catn [::forms :map]] [:vector :map]]]}
  ([]
   (canonical-schema-rows (registered-schemas)))
  ([forms]
   (let [complete-projection
         (try
           (build-projection forms)
           (catch clojure.lang.ExceptionInfo error
             (if (:seon.schema/missing-reference (ex-data error))
               nil
               (throw error))))
         projection
         (or complete-projection
             {:seon.schema.projection/forms forms})
         materialized-keys
         (into #{} (filter keyword?) (keys forms))
         predicate-functions
         (if-let [bound (not-empty (predicate-functions-in projection))]
           bound
           (predicate-functions-with {} (vals forms)))
         reference-graph
         (or (:seon.schema.projection/schema-dependencies projection)
             (canonical-reference-graph
              forms predicate-functions (reference-candidate-keys forms)))
         ordering-graph
         (update-vals reference-graph
                      #(set/intersection materialized-keys %))
         storable-properties-in
         @schema-datahike-storable-properties-in]
     (into
      []
      (map
       (fn [[schema-key definition]]
         (let [references (get reference-graph schema-key)]
           (cond->
            (merge (storable-properties-in projection definition)
                   {:seon.schema/key schema-key
                    :seon.schema/form (pr-str definition)
                    :seon.schema.admission/source :core})
             (seq references)
             (assoc :seon.schema/references
                    (set references))))))
      (map (fn [schema-key] [schema-key (get forms schema-key)])
           (dependency-first-schema-keys
            ordering-graph materialized-keys))))))

(defn canonical-database-attributes
  "Compute the complete production database-attribute population.

   Entity-map entries are attributes by construction. Standalone registered
   forms join that population only when they carry a persistence facet."
  {:malli/schema
   [:function
    [:=> [:cat] [:vector :qualified-keyword]]
    [:=> [:catn [::forms :map]] [:vector :qualified-keyword]]]}
  ([]
   (canonical-database-attributes (registered-schemas)))
  ([forms]
   (@schema-datahike-database-attributes-in
    {:seon.schema.projection/forms forms})))

(defn registered?
  "Check if a schema keyword is registered."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]] :boolean]}
  [k]
  (contains? (candidate-forms) k))

(defn schema-definition
  "The raw definition for a registered schema, or nil if not registered.

   A caller asking about more than one key supplies the population it already
   resolved (see [[declaration-population]]); the one-argument arity resolves
   one per call, so `(map schema-definition keys)` is `(count keys)` complete
   classpath populations."
  {:malli/schema
   [:function [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]] [:=> [:catn [:seon.schema/forms :map] [:seon.schema/registry-key :seon.schema/registry-key]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]]]}
  ([k] (get (candidate-forms) k))
  ([forms k] (get forms k)))

(defn valid-candidate-value?
  "True when `value` satisfies `schema-key` in the current candidate.

   Candidate declarations never mutate Malli's process-global default registry. Boundaries
   validating a declaration and its first facts together use this function so
   they see the complete candidate without publishing it early.

   A caller validating more than one value supplies the population it already
   resolved (see [[declaration-population]]); the two-argument arity resolves
   one per call."
  {:malli/schema
   [:function [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]]] :boolean] [:=> [:catn [:seon.schema/forms :map] [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]]] :boolean]]}
  ([schema-key value]
   (m/validate schema-key value {:registry (candidate-registry)}))
  ([forms schema-key value]
   (m/validate schema-key value {:registry (candidate-registry forms)})))

(defn explain-candidate-value
  "Explain a value rejected by the current declaration candidate.

   Uses the same explicit candidate registry as `valid-candidate-value?`; nil
   means the value is valid."
  {:malli/schema
   [:function [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:maybe :seon.schema/explanation]] [:=> [:catn [:seon.schema/forms :map] [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:maybe :seon.schema/explanation]]]}
  ([schema-key value]
   (m/explain schema-key value {:registry (candidate-registry)}))
  ([forms schema-key value]
   (m/explain schema-key value {:registry (candidate-registry forms)})))

(def ^:const shape-candidate-limit
  "Maximum schema rows examined and returned for structural diagnostics."
  32)

(def ^:const shape-input-key-limit
  "Maximum map entries examined for structural diagnostics."
  32)

(def ^:dynamic *candidate-visit!*
  "Optional test instrumentation called once per diagnostic schema visit."
  (fn [_schema-key] nil))

(defn projection-validator
  "Compile a validator against exactly one immutable projection."
  {:malli/schema [:=> [:catn [::projection ::projection]
                             [::registry-key ::registry-key]]
                  ::compiled-validator]}
  [projection schema-key]
  (m/validator
    (m/deref-recursive
      schema-key
      {:registry (:seon.schema.projection/registry projection)})))

(defn- function-arities-in [projection function-symbol]
  (projection-cache-value
   projection [::function-arities function-symbol]
   (fn []
     (if-let [contract
              (get (:seon.schema.projection/function-contracts projection)
                   function-symbol)]
       (compiled-function-arities
        (m/function-schema
         (compilable-form
          contract (predicate-functions-in projection))
         {:registry (:seon.schema.projection/registry projection)}))
       []))))

(defn function-matching-outputs-in
  "Return the declared output forms of the arities accepting `arguments`.

  Input validators belong to the immutable projection's existing compiled
  holder; discovery never recompiles a retained arity."
  {:malli/schema
   [:=> [:catn [::projection ::projection]
         [::function-symbol :qualified-symbol]
         [::arguments :seon.schema/arguments]]
    [:vector ::value]]}
  [projection function-symbol arguments]
  (into []
        (keep (fn [[accepts? output]] (when (accepts? arguments) output)))
        (function-arities-in projection function-symbol)))

(defn function-accepts-in?
  "True when one arity of `function-symbol` accepts `arguments` in `projection`.

  This validates the complete declared input contract, not merely one referenced
  schema. The acquired program snapshot bounds candidates; this function is the
  exact semantic check over that bounded set."
  {:malli/schema
   [:=> [:cat ::projection :qualified-symbol :seon.schema/arguments]
    :boolean]}
  [projection function-symbol arguments]
  (try
    (boolean (seq (function-matching-outputs-in
                   projection function-symbol arguments)))
    (catch Throwable _ false)))

(defn function-returns-in?
  "True when one arity of `function-symbol` declares `output-schema`.

  Built-in Malli schemas such as `:string` do not have program-graph entities,
  so output-ref datoms alone cannot express this question. The durable function
  contract in the immutable projection is the authority for both built-in and
  registered output schemas."
  {:malli/schema
   [:=> [:cat ::projection :qualified-symbol ::registry-key]
    :boolean]}
  [projection function-symbol output-schema]
  (try
    (boolean (some (fn [[_ output]] (= output-schema output))
                   (function-arities-in projection function-symbol)))
    (catch Throwable _ false)))

(defn function-accepts-and-returns-in?
  "True when one arity accepts `arguments` and declares `output-schema`."
  {:malli/schema
   [:=> [:catn [::projection ::projection]
         [::function-symbol :qualified-symbol]
         [::arguments :seon.schema/arguments]
         [::output-schema ::registry-key]]
    :boolean]}
  [projection function-symbol arguments output-schema]
  (try
    (boolean (some #{output-schema}
                   (function-matching-outputs-in
                    projection function-symbol arguments)))
    (catch Throwable _ false)))

(defn projection-explainer
  "Compile an explainer against exactly one immutable projection."
  {:malli/schema [:=> [:catn [::projection ::projection]
                             [::registry-key ::registry-key]]
                  ::compiled-validator]}
  [projection schema-key]
  (m/explainer
    (m/deref-recursive
      schema-key
      {:registry (:seon.schema.projection/registry projection)})))

(defn- shape-projection []
  (or (handed-projection)
      (throw
       (ex-info "Shape inspection requires the operation's schema projection."
                {:seon.error/kind ::missing-projection
                 :seon.schema/missing-projection true}))))

(defn- identity-only-descriptors-in
  [projection]
  (into
   []
   (keep
    (fn [[schema-key definition]]
      (let [properties (form/attr-form-properties definition)]
        (when (true? (:seon.schema/identity-only properties))
          (let [projection-symbol
                (:seon.schema/identity-projection properties)
                _ (when-not (qualified-symbol? projection-symbol)
                    (throw
                     (ex-info
                      (str schema-key " declares identity-only admission "
                           "without a qualified identity projection.")
                      {:seon.schema/key schema-key
                       :seon.schema/identity-projection projection-symbol
                       :seon.schema/invalid-identity-projection true
                       :seon.error/kind :core-bug})))
                projection-var (requiring-resolve projection-symbol)]
            (when-not (ifn? (var-get projection-var))
              (throw
               (ex-info
                (str schema-key " declares identity-only admission without "
                     "a callable identity projection.")
                {:seon.schema/key schema-key
                 :seon.schema/identity-projection projection-symbol
                 :seon.schema/invalid-identity-projection true
                 :seon.error/kind :core-bug})))
            {:seon.schema/key schema-key
             :seon.schema.identity-only/validator
             (projection-validator projection schema-key)
             :seon.schema/identity-projection projection-var})))))
   (sort-by (comp str key)
            (:seon.schema.projection/forms projection))))

(defn- identity-only-descriptors
  "The identity-only descriptors of exactly `projection`.

   Derived once and kept on the projection's own holder, so asking twice
   costs one map lookup and asking about a DIFFERENT projection cannot be
   answered with this one's descriptors. Value admission asks this per node,
   which is why it must be cheap and why it must be right."
  [projection]
  (if-let [cache (projection-cache projection)]
    (or (:seon.schema.identity-only/descriptors @cache)
        (let [descriptors (identity-only-descriptors-in projection)]
          (swap! cache assoc
                 :seon.schema.identity-only/descriptors descriptors)
          descriptors))
    (identity-only-descriptors-in projection)))

(defn identity-only-projection-in
  "Project a registered reference value to its declared identity data."
  {:malli/schema
   [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:maybe :map]]}
  [projection value]
  (some
   (fn [{:seon.schema/keys [key identity-projection]
         validator :seon.schema.identity-only/validator}]
     (when (validator value)
       {:seon.schema/key key
        :seon.schema/identity-value (identity-projection value)}))
   (identity-only-descriptors projection)))

(defn identity-only-projection
  "Project a registered reference value using the active schema registry."
  {:malli/schema [:=> [:catn [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:maybe :map]]}
  [value]
  (identity-only-projection-in (shape-projection) value))

(defn- cached-compiler-in!
  "One compiled validator or explainer for `schema-key` in `projection`.

   The compiled result is kept on the projection's own holder, so the cache
   cannot be asked about one projection and answer about another. The old
   shape — reset a process-global slot to the caller's projection, then deref
   it AGAIN to read the answer — was two independent reads of shared mutable
   state between which a second environment could reset the slot to its own
   projection; that check-then-act is banned rather than tightened."
  [projection cache-key compiler schema-key]
  (if-let [cache (projection-cache projection)]
    (or (get-in @cache [cache-key schema-key])
        (let [compiled (compiler projection schema-key)]
          (swap! cache assoc-in [cache-key schema-key] compiled)
          compiled))
    (compiler projection schema-key)))

(defn- shape-rank [row]
  [(- (count (:seon.schema/required-attrs row)))
   (str (:seon.schema/key row))])

(defn- complete-present-attrs [value]
  (when (map? value)
    (->> (keys value) (filter keyword?) (sort-by str) vec)))

(defn- diagnostic-present-attrs [value]
  (when (map? value)
    (into []
          (comp (take shape-input-key-limit)
                (map first)
                (filter keyword?))
          value)))

(defn- diagnostic-schema-keys [projection attrs]
  (let [index (:seon.schema.projection/shape-index projection)]
    (loop [remaining-attrs attrs
           remaining-keys []
           visited 0
           selected #{}]
      (cond
        (>= visited shape-candidate-limit)
        selected

        (seq remaining-keys)
        (let [schema-key (first remaining-keys)]
          (*candidate-visit!* schema-key)
          (recur remaining-attrs
                 (next remaining-keys)
                 (inc visited)
                 (conj selected schema-key)))

        (seq remaining-attrs)
        (recur (next remaining-attrs)
               (get index (first remaining-attrs) [])
               visited
               selected)

        :else
        selected))))

(defn candidate-shapes-in
  "Bounded diagnostic schema window from explicit `projection`."
  {:malli/schema [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:vector :map]]}
  [projection value]
  (if-let [attrs (seq (sort-by str (diagnostic-present-attrs value)))]
    (let [rows (:seon.schema.projection/shape-rows projection)]
      (->> (diagnostic-schema-keys projection attrs)
           (map rows)
           (sort-by shape-rank)
           vec))
    []))

(defn candidate-shapes
  "Bounded diagnostic schema window from the activated projection.

   Examines at most [[shape-input-key-limit]] map entries and
   [[shape-candidate-limit]] indexed schema references. Structural candidates
   outside either window may be omitted, so rows never assert validity.
   Candidate declarations do not affect the result after activation."
  {:malli/schema [:=> [:catn [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:vector :map]]}
  [value]
  (let [projection (shape-projection)]
    (candidate-shapes-in projection value)))

(defn matching-shapes-in
  "All schemas in explicit `projection` that validate `value`."
  {:malli/schema [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:vector :map]]}
  [projection value]
  (if-let [attrs (seq (complete-present-attrs value))]
    (let [index (:seon.schema.projection/shape-index projection)
          rows (:seon.schema.projection/shape-rows projection)
          present (set attrs)
          possible (into #{} (mapcat #(get index % [])) attrs)]
      (->> possible
           (map rows)
           (filter (fn [row]
                     (every? present (:seon.schema/required-attrs row))))
           (sort-by shape-rank)
           (filter (fn [{:seon.schema/keys [key]}]
                      ((cached-compiler-in!
                        projection :seon.schema.shape/validators
                        projection-validator key)
                      value)))
           vec))
    []))

(defn matching-shapes
  "All schemas that validate `value` in the activated projection.

   Matching is deliberately independent of the capped diagnostic result: all
   structurally possible schemas validate and survive in deterministic order."
  {:malli/schema [:=> [:catn [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:vector :map]]}
  [value]
  (let [projection (shape-projection)]
    (matching-shapes-in projection value)))

(defn explain-shape-in
  "Explain `value` against `schema-key` in explicit `projection`."
  {:malli/schema [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:maybe :seon.schema/explanation]]}
  [projection schema-key value]
  (when-not (contains? (:seon.schema.projection/shape-rows projection)
                       schema-key)
    (throw (ex-info (str "Unknown projected map schema " schema-key ".")
                    {:seon.schema/error :seon.schema/unknown-shape
                     :seon.schema/key schema-key
                     :seon.error/kind :core-bug :seon.schema/unknown-shape schema-key})))
  ((cached-compiler-in!
     projection :seon.schema.shape/explainers projection-explainer schema-key)
   value))

(defn explain-shape
  "Explain `value` against one activated structural schema.

   Returns nil when valid and Malli explanation data when invalid. The schema
   key must name a row returned by [[candidate-shapes]]; an unknown key is a
   caller defect and throws before compiling against any other registry."
  {:malli/schema [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:maybe :seon.schema/explanation]]}
  [schema-key value]
  (let [projection (shape-projection)]
    (explain-shape-in projection schema-key value)))

(defn candidate-validator
  "Compile a recursively resolved validator from current declarations."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]]
                  ::compiled-validator]}
  [schema-key]
  (m/validator
   (m/deref-recursive
    schema-key
    {:registry (candidate-registry)})))

(defn candidate-explainer
  "Compile a recursively resolved explainer from current declarations."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]]
                  ::compiled-validator]}
  [schema-key]
  (m/explainer
   (m/deref-recursive
    schema-key
    {:registry (candidate-registry)})))

(defn schemas-in-namespace
  "The `{keyword definition}` map of schemas registered under `ns-name`.

   `ns-name` is a namespace symbol, e.g. `seon.agent`."
  {:malli/schema [:=> [:catn [::namespace-name ::namespace-name]] :map]}
  [ns-name]
  (into {}
        (filter (fn [[k _]] (= (some-> k namespace symbol) ns-name)))
        (candidate-forms)))

;;; ---------------------------------------------------------------------------
;;; Development Helpers
;;; ---------------------------------------------------------------------------

(defn clear-all!
  "Return packaged schemas; declarations are database facts, not mutable state."
  {:malli/schema [:=> [:cat] :map]}
  []
  (registered-schemas))
