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

   Compiled entity inspection and register!-time gates
   live in `seon.schema.internal`, outside agent context."
  {:seon/tasks #{"cea47b015b23"}}
  (:require [malli.core :as m]
            [seon.id :as id]
            [malli.registry :as mr]
            [clojure.core.cache.wrapped :as cache]
            [seon.cache]
            [clojure.core.reducers :as reducers]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [datahike.api :as d]
            [datahike.pull-api :as pull-api]
            [datahike.db.interface :as dbi]
            [datahike.db.utils :as db-utils]
            [seon.schema.internal :as internal]
            [seon.error.refusal :as refusal]
            [clojure.string :as str]
            [clojure.edn :as edn]))

;;; LOAD-CYCLE BOUNDARIES. `seon.schema.edn`, `seon.error` and
;;; `seon.schema.datahike` all require `seon.schema`, so this namespace
;;; cannot require them back. One resolution per var, realized at first use,
;;; instead of a `requiring-resolve` on every call (AGENTS §2.1).
(defonce ^:private schema-edn-packaged-forms
  (delay (requiring-resolve 'seon.schema.edn/packaged-forms)))
(defonce ^:private schema-datahike-storable-attribute-in?
  (delay (requiring-resolve 'seon.schema.datahike/storable-attribute-in?)))
(defonce ^:private schema-datahike-storable-properties-in
  (delay (requiring-resolve 'seon.schema.datahike/storable-properties-in)))
(defonce ^:private schema-datahike-assert-storable-schema!
  (delay (requiring-resolve 'seon.schema.datahike/assert-storable-schema!)))
(defonce ^:private schema-datahike-database-attributes-in
  (delay (requiring-resolve 'seon.schema.datahike/database-attributes-in)))
(defonce ^:private schema-datahike-malli->datahike-attr-in
  (delay (requiring-resolve 'seon.schema.datahike/malli->datahike-attr-in)))
(defonce ^:private schema-datahike-storage-schema
  (delay
    (require 'seon.schema.datahike)
    (ns-resolve 'seon.schema.datahike 'storage-schema)))
(defonce ^:private schema-datahike-value-schema
  (delay
    (require 'seon.schema.datahike)
    (ns-resolve 'seon.schema.datahike 'value-schema)))

(defn- reference-children
  "The compiled schemas directly under `node`: plain children, and the schema
   of each map entry (`[key properties schema]`)."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] [:sequential [:fn malli.core/schema?]]]}
  [node]
  (keep (fn [child]
          (cond (m/schema? child) child
                (and (vector? child) (m/schema? (peek child))) (peek child)))
        (m/children node)))

(defn contract-references
  "Canonical registry keys `compiled` depends on, in the world it was compiled in.

   `registry` is the registry `compiled` was compiled against and
   `canonical-keys` its canonical declarations. A ref is canonical only when its
   OWN scope resolves the name to the object `registry` answers for it: a local
   `:registry` shadowing a canonical name is followed, not recorded. Any other
   ref is followed through `m/deref` under a recursion guard keyed by
   `[scope-registry name]`, so a local recursive registry, or nested scopes
   reusing one recursive name, terminate. Callers pass the registry of the
   compilation world; a set of names alone cannot tell a shadow from its target.

   ## Examples

   `[:schema {:registry {:a/x :a/y}} :a/x]` in a world declaring both answers
   `#{:a/y}`.

   ## Costs

   Proportional to the compiled value's nodes; each canonical ref is a leaf."
  {:malli/schema [:=> [:cat [:fn malli.registry/registry?] [:set :keyword] [:fn malli.core/schema?]]
                  [:set :keyword]]}
  [registry canonical-keys compiled]
  (letfn [(walk-node [node seen found]
            (if (m/-ref-schema? node)
              (let [reference (m/-ref node)
                    scope (:registry (m/options node))]
                (cond
                  (nil? reference)
                  (reduce #(walk-node %2 seen %1) found (reference-children node))

                  (and (contains? canonical-keys reference)
                       (some? (mr/schema registry reference))
                       (identical? (mr/schema scope reference) (mr/schema registry reference)))
                  (conj found reference)

                  (contains? seen [scope reference]) found

                  :else (walk-node (m/deref node) (conj seen [scope reference]) found)))
              (reduce #(walk-node %2 seen %1) found (reference-children node))))]
    (walk-node compiled #{} #{})))

(defn arity-references
  "Per arity of the function schema `compiled`, in Malli's arity order, its
   `[input output guard]` canonical reference sets ([[contract-references]]
   in the world `registry` names). An absent guard is `#{}`."
  {:malli/schema [:=> [:cat [:fn malli.registry/registry?] [:set :keyword] [:fn malli.core/schema?]]
                  [:vector [:tuple [:set :keyword] [:set :keyword] [:set :keyword]]]]}
  [registry canonical-keys compiled]
  (mapv (fn [arity]
          (let [info (m/-function-info arity)]
            (mapv #(if-let [role (get info %)]
                     (contract-references registry canonical-keys role)
                     #{})
                  [:input :output :guard])))
        (m/-function-schema-arities compiled)))

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
  {:malli/schema [:=> [:cat [:map-of :keyword :seon.schema/value] [:seqable :keyword] [:map-of :keyword [:set :keyword]]] :nil]}
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
         })))))

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
  "The Var a qualified predicate symbol names, or nil when nothing names it.

   THE resolution rule for host-authored predicates. A qualified symbol names
   exactly one Var, so resolution is collision-free by construction — two
   environments declaring the same predicate cannot overwrite each other the
   way the process-global symbol->function cache did (2026-08-07 isolation
   audit, `probe_predicate_function_cache`: a second registration of one
   symbol made a value that was valid under the first stop validating,
   process-wide, though both projections were rebuilt from immutable forms).

   The VAR is retained rather than its current value. Invoking a Var reads its
   root at call time, so re-evaluating a `defn` changes what an already
   compiled schema calls, exactly as it changes every other caller.

   Nil means only that nothing names the predicate: its namespace loads
   without that Var, or no file for the namespace is on the classpath
   (`RT.load` throws `FileNotFoundException` itself for the requested root).
   A namespace that exists but fails to load — a missing transitive require
   arrives wrapped in a `CompilerException` too — throws the declared
   `:seon.schema/unloadable-predicate-error` carrying the whole cause chain,
   so a compile failure is never read as an unresolved predicate."
  {:malli/schema [:=> [:cat :qualified-symbol] [:or :nil [:fn clojure.core/var?]]]}
  [predicate]
  (try
    (requiring-resolve predicate)
    (catch java.io.FileNotFoundException _
      nil)
    (catch clojure.lang.Compiler$CompilerException failure
      (let [refusal
            (refusal/diagnostic
             {:seon.error/at (java.util.Date.)
              :seon.error/layer :seon.schema/derivation
              :seon.error/operation 'seon.schema/runtime-predicate
              :seon.error/message
              (str "Predicate " predicate " cannot resolve: its namespace "
                   (namespace predicate) " failed to load ("
                   (ex-message failure) "). Fix that namespace, then retry.")
              :seon.error/member predicate
              :seon.error/throwable failure
              :seon.schema/unloadable-predicate predicate})]
        (throw (ex-info (:seon.error/message refusal) refusal failure))))))

(defn- loaded-predicate-var
  "The Var a qualified predicate symbol names, WITHOUT loading its namespace.

   Compilation must never load code as a side effect of examining a
   declaration: agents author `[:fn ...]` forms, and [[malli-form?]] answers
   structural questions about them, so a loading resolver would let an
   arbitrary namespace be required by writing its name into a schema. The
   load-time `register-core-predicate!` assertion is what makes this
   sufficient for a valid declaration — a predicate's owner is loaded before
   anything can declare against it, and `clojure.core` is always loaded. A
   predicate added to a namespace this process already loaded resolves as
   nothing and the compile refuses naming that namespace: its adoption
   reloads the owner (`seon.cluster/load-development-definitions!`); the
   compile never reloads it, since a reload replaces every root of the
   namespace, contract wrappers included."
  [predicate]
  (when (qualified-symbol? predicate)
    (some-> (find-ns (symbol (namespace predicate)))
            (ns-resolve (symbol (name predicate))))))

(declare structural-schema)

(defn- widen-component-children
  "Reconstruct component transaction grammar through Malli's syntax nodes.

   Local registry values are already compiled by Malli. Transform their
   declarations without following recursive refs; the complete generation
   subsequently compiles the resulting form in its own captured scopes."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] [:fn malli.core/schema?]]}
  [compiled]
  (m/walk
   compiled
   (fn [node _ children _]
     (let [properties (m/properties node)
           properties (cond-> properties
                        (:registry properties)
                        (update :registry update-vals widen-component-children))
           children
           (if (true? (:seon.db/component properties))
             (mapv (fn [child]
                     (if (and (= :or (m/type child))
                              (some #(or (= :seon.db/component-entity (m/type %))
                                         (and (m/-ref-schema? %)
                                              (= :seon.db/component-entity (m/-ref %))))
                                    (m/children child)))
                       child
                       (m/into-schema :or nil
                                      [child :seon.db/component-entity]
                                      (m/options node))))
                   children)
             children)]
       (if (= properties (m/properties node))
         (m/-set-children node children)
         (m/into-schema (m/parent node) properties children (m/options node)))))))

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
   [[widen-component-children]] — derived from the
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
   the process require code or reload a loaded namespace; an unresolved
   predicate refuses by name."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}] :map] :seon.schema/value]}
  [form predicate-functions]
  (walk/postwalk
   (fn [value]
     (cond
       ;; A `:gen/gen` symbol stays a symbol: Malli resolves it to the loaded
       ;; Var's current value when it generates (malli.generator/-create-from-gen,
       ;; malli.core/-loaded-qualified-value), so a reload is never stale here.
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
                   (loaded-predicate-var predicate)))]
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
                      }
               ;; Name the namespace that must define it: its loaded copy
               ;; lacks the predicate, so the namespace's source or its
               ;; adoption (which reloads it) is the owner to act.
               (qualified-symbol? predicate)
               (assoc :seon.schema/predicate-namespace
                      (symbol (namespace predicate))))))))

       :else value))
   (m/form (widen-component-children (structural-schema form)))))

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

   Plain validators and explainers belong to Malli's retained schemas. This
   holder owns only additional products, including arity descriptors and
   selector-derived projections. It never stores a second plain validator.

   The holder is installed FRESH at every construction and never inherited: a
   projection derived by changing forms would otherwise carry its parent's
   compiled answers for definitions it no longer has. It needs no key,
   because its key is the value it hangs on. It starts empty: a function's
   arities derive on first use from the registry (`function-arities-in`).

   ## Costs

   Measured on default's carried projection, 2026-09-27: 3,898 entries, each
   keyed by one declaration (2,563 write-attribute plans, 1,262 write
   validators, 73 others), so it is bounded by the projection's declarations
   and dies with it. A hit costs 47 ns unarmed, 240 ns armed; the same entry
   as a `seon.cache` kind cost 1,171 ns armed, and its key would pin what the
   key names (a compiled schema pins its whole registry) past the
   projection's life.

   ## Known issues

   `seon.db/write-validator` stores a second plain validator here, contrary
   to this contract: Malli already caches it on the compiled schema
   (`malli.core/validator`, `-cached`), 1,262 of the 3,898 entries
   (tmp/value-hygiene-opus/db-write-validator.patch)."
  [projection]
  (assoc projection
         :seon.config.db/validation-node-limit
         (:seon.config/default
          (some-> (mr/schema (:seon.schema.projection/registry projection)
                             :seon.config.db/validation-node-limit)
                  m/properties))
         :seon.schema.projection/compiled (cache/basic-cache-factory {})))

(defn- projection-cache
  "The holder [[with-compiled-cache]] installed, or nil.

   Nil is honest rather than exceptional: a projection assembled by a caller
   that did not go through a constructor still answers every question, it
   just recompiles. Correctness never depends on the cache being there, which
   is what makes it safe for it to be absent."
  [projection]
  (:seon.schema.projection/compiled projection))

(defn projection-cache-value
  "Return one value derived from `projection`, retaining one answer per key.

   The answer is retained by the projection's own runtime holder. A projection
   assembled without that holder remains correct and simply derives afresh."
  {:malli/schema
   [:=> [:catn [:seon.schema/projection :map] [:seon.schema/cache-key [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A projection cache key is ordinary heterogeneous data chosen by its caller; cached values are the supplied thunk's arbitrary result.", :gen/elements [nil false 0 "" :k [] {}]}]] [:seon.schema/derive-fn [:fn clojure.core/ifn?]]] [:or :seon.schema/value :seon.schema/validation-refusal]]}
  [projection cache-key derive-fn]
  (if-let [compiled (projection-cache projection)]
    ;; A hit is a read; only a miss swaps (lookup-or-miss swaps every call).
    (let [held (cache/lookup compiled cache-key ::miss)]
      (if (identical? ::miss held)
        (cache/lookup-or-miss compiled cache-key (fn [_] (derive-fn)))
        held))
    (derive-fn)))

(defn predicate-functions-in
  "Return the predicate bindings `projection` carries; `{}` when none are bound.

   THE ONE DERIVATION for that question. A caller-assembled projection can
   omit predicate bindings, while [[compilable-form]] declares a map, so a
   bare read handed it nil and refused every compile in a worker where
   nothing bound a projection first. Translating the absence in
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

(defn- unresolved-reference-in
  "The qualified registry key a failed compilation could not resolve, read
   through the whole cause chain by the gate that owns the reading
   (`seon.schema.internal/missing-schema-reference`); nil when the failure is
   not an unresolved reference."
  {:malli/schema [:=> [:cat :seon.error/throwable]
                  [:or :nil :qualified-keyword]]}
  [throwable]
  (let [missing (or (some (comp :seon.schema/missing-reference ex-data)
                          (take-while some? (iterate ex-cause throwable)))
                    (#'internal/missing-schema-reference throwable))]
    (when (qualified-keyword? missing) missing)))

(defn- unresolved-reference-refusal
  "The flat declared refusal for a declaration naming a schema no member of
   the candidate population declares.

   Names the refusing operation, the declaration being admitted (when one is),
   the missing key, and every candidate declaration that names it directly;
   the Malli failure survives whole as the cause chain."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.error/operation :seon.error/operation]
               [:seon.schema/missing-reference :qualified-keyword]
               [:seon.schema/definitions :map]
               [:seon.error/throwable :seon.error/throwable]
               [:seon.schema/key {:optional true} :seon.schema/key]]]
    :seon.error/base]}
  [{operation :seon.error/operation
    missing :seon.schema/missing-reference
    definitions :seon.schema/definitions
    throwable :seon.error/throwable
    declaration :seon.schema/key}]
  (let [names-missing? (fn [definition]
                         (boolean (some #{missing}
                                        (tree-seq coll? seq definition))))
        referrers (into (sorted-set-by (fn [a b] (compare (str a) (str b))))
                        (keep (fn [[identity definition]]
                                (when (names-missing? definition) identity)))
                        definitions)
        referrers (if (and declaration (empty? referrers))
                    (conj referrers declaration)
                    referrers)
        schema-keys (into #{} (filter keyword?) referrers)
        function-symbols (into #{} (filter qualified-symbol?) referrers)
        referrer (first referrers)
        schema-keys-in-order (filter keyword? referrers)]
    (vary-meta (refusal/diagnostic
     (cond->
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.schema/derivation
       :seon.error/operation operation
       :seon.error/message
       (str "Schema reference " missing " is not declared in the candidate "
            "projection; it is named by " (str/join ", " referrers)
            (when (and declaration (not (contains? referrers declaration)))
              (str " while admitting " declaration))
            ". Declare " missing " in the same change, or convert every "
            "referrer that names it.")
       :seon.error/member missing
       :seon.error/throwable throwable
       :seon.schema/missing-reference missing
       :seon.schema/missing-reference-namespace (namespace missing)
       :seon.schema/refused-value (get definitions referrer)
       :seon.schema/expected-value :seon.schema/registry-key
       :seon.schema.blockers/schema-keys schema-keys
       :seon.schema.blockers/function-symbols function-symbols}
       ;; The declaration being admitted; a whole-population build admits
       ;; every declaration, so its first naming schema is the offender.
       (or declaration (first schema-keys-in-order))
       (assoc :seon.schema/key (or declaration (first schema-keys-in-order)))
       (seq schema-keys)
       (assoc :seon.schema/invalid-schema
              (if (contains? schema-keys declaration)
                declaration
                (first (filter keyword? referrers))))
       (seq function-symbols)
       (assoc :seon.schema/undefined-contract
              (first (filter qualified-symbol? referrers))))) assoc :seon.error/declared-schema :seon.schema/validation-refusal)))

(defn- refuse-unresolved-reference!
  "Rethrow `throwable` as the declared unresolved-reference refusal when it is
   one; any other failure propagates unchanged."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.error/operation :seon.error/operation]
               [:seon.schema/definitions :map]
               [:seon.error/throwable :seon.error/throwable]
               [:seon.schema/key {:optional true} :seon.schema/key]]]
    :nil]}
  [{throwable :seon.error/throwable definitions :seon.schema/definitions
    :as request}]
  (let [data (ex-data throwable)]
    (when (and (:seon.error/at data) (:seon.error/layer data)
               (:seon.error/operation data))
      ;; Already the declared refusal of a nested compilation.
      (throw throwable))
    (if-let [missing (unresolved-reference-in throwable)]
      (if (contains? definitions missing)
        (throw throwable)
        (let [refusal (unresolved-reference-refusal
                       (assoc request :seon.schema/missing-reference missing))]
          (throw (ex-info (:seon.error/message refusal) refusal throwable))))
      (throw throwable))))

(defn- projection-registry
  "Compile one immutable generation serially, then seal its complete table.

   Retained entries must be unaffected by the candidate's changes. All other
   declarations close over prepared immutable definitions; a captured lazy
   scope can only memoize those same answers, never consult current state."
  {:malli/schema
   [:function
    [:=> [:cat :map :map] [:fn malli.registry/registry?]]
    [:=> [:cat :map :map :map :map] [:fn malli.registry/registry?]]]}
  ([forms predicate-functions]
   (projection-registry forms predicate-functions {} {}))
  ([forms predicate-functions contracts retained]
   (let [definitions
         (if (seq retained)
           (reduce
            (fn [changed population]
              (reduce-kv
               (fn [changed identity definition]
                 (if (contains? retained identity)
                   changed
                   (assoc changed identity definition)))
               changed population))
            {} [forms contracts])
           (merge {} forms contracts))
         predicate-functions
         (reduce (fn [bindings predicate]
                   (if (contains? bindings predicate)
                     bindings
                     (if-let [callable (runtime-predicate predicate)]
                       (assoc bindings predicate callable)
                       bindings)))
                 predicate-functions
                 (into #{} (mapcat predicate-symbols-in) (vals definitions)))
         prepared (bound-forms definitions predicate-functions)
         registry
         (mr/lazy-registry
          (mr/composite-registry (m/default-schemas) retained)
          (fn [identity scope]
            (when-let [definition (get prepared identity)]
              ((if (qualified-symbol? identity) m/function-schema m/schema)
               definition {:registry scope}))))]
     (try
       (doseq [identity (sort-by str (remove #(contains? retained %) (keys forms)))]
         ;; Only what makes the projection compilable. Authoring rules judge
         ;; a new or changed declaration at its admission (`register!`,
         ;; `projection-with-schema`, the edit hook), never an existing
         ;; population: an as-of view keeps the rules that admitted it.
         (internal/assert-compilable-schema!
          forms identity (get forms identity) {:registry registry}))
       (doseq [identity (sort-by str (remove #(contains? retained %) (keys contracts)))]
         (mr/schema registry identity))
       (catch Exception failure
         (refuse-unresolved-reference!
          {:seon.error/operation 'seon.schema/projection-registry
           :seon.schema/definitions (merge {} forms contracts)
           :seon.error/throwable failure})))
     (mr/fast-registry (mr/schemas registry)))))

(defn canonical-definition
  "Return one Malli definition as durable EDN.

   `definition` is authored source read as data (`seon.fn` qualifies its
   symbols): predicates are symbols, a quoted predicate symbol is its name.
   A callable value — an evaluated root, an anonymous `(fn ...)` form — names
   nothing and is refused instead of printed as an unreadable `#object`. The
   compiled direction is never inverted: a refusal carries its producer's
   declared identity (`seon.instrument/boundary-refusal`), not a name read
   back off a compiled predicate. A `:=>` without an output schema is refused
   as `::missing-output-schema`, naming the form.

   Example:
   (canonical-definition [:=> [:cat :int] :int])"
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/definition]}
  [definition]
  (let [callable-symbol
        (fn [value]
          (throw
               (ex-info
                (str "A durable Malli definition contains an unnamed callable: contracts are data; "
                     "use a registered predicate schema or a quoted symbol naming an admitted predicate.")
                {:seon.schema/error :seon.schema/noncanonical-definition
                 :seon.schema/noncanonical-definition ::unnamed-callable
                 :seon.schema/value value
                 })))
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

                    ;; A function schema without its output is not a contract:
                    ;; refuse it here by name, not one hop later as an invalid
                    ;; return of this function.
                    (and (vector? value) (= :=> (first value))
                         (< (count (remove map? (rest value))) 2))
                    (throw
                     (ex-info
                      (str "A function schema needs an input :cat and an output schema; "
                           (pr-str value) " declares no output. Name the returned shape: "
                           "[:=> [:cat :my.ns/request] :my.ns/result].")
                      {:seon.schema/error :seon.schema/noncanonical-definition
                       :seon.schema/noncanonical-definition ::missing-output-schema
                       :seon.error/message (str "The function schema " (pr-str value)
                                                " declares no output schema.")
                       :seon.schema/value value}))

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
                                      })))
                                 [(canonicalize k false)
                                  (canonicalize v false)]))
                          value)

                    (set? value)
                    (into #{} (map #(canonicalize % false)) value)

                    (and (= :predicate reference-kind)
                         (seq? value)
                         (= 'quote (first value))
                         (nil? (next (next value)))
                         (qualified-symbol? (second value)))
                    (second value)

                    ;; An unevaluated callable form in a predicate slot, e.g.
                    ;; `(fn [r] (seq r))` read from source metadata, names nothing.
                    (and (= :predicate reference-kind) (seq? value))
                    (callable-symbol value)

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
         })))
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
           (contract-references
            registry-for-references canonical-keys
            (m/schema
             (compilable-form definition predicate-functions)
             options))
           (catch Exception _
             ;; The ordinary compilation gate owns malformed and unresolved
             ;; forms. This preflight owns only the reference graph needed to
             ;; make recursive expansion unrepresentable.
             #{}))]))
     forms))))

(defn- direct-reference-keys-in
  "Canonical keys `definition` names directly, compiled against references
   only. An undeclared reference refuses as the declared unresolved-reference
   refusal naming `schema-key` and the refusing `operation`."
  {:malli/schema
   [:=> [:cat :seon.error/operation :seon.schema/key :seon.schema/value :map
         [:set :keyword] [:fn malli.registry/registry?]]
    [:set :keyword]]}
  [operation schema-key definition predicate-functions canonical-keys fallback]
  (let [registry-for-references
        (reference-registry canonical-keys fallback)]
    (try
      (contract-references
       registry-for-references canonical-keys
       (m/schema (compilable-form definition predicate-functions)
                 {:registry registry-for-references}))
      (catch Exception failure
        (refuse-unresolved-reference!
         {:seon.error/operation operation
          :seon.schema/key schema-key
          :seon.schema/definitions {schema-key definition}
          :seon.error/throwable failure})))))

(declare canonical-data-string)

(defn- portable-string-hash [s]
  (.hashCode ^String s))

(defn- framed [tag payload]
  (str tag (count payload) ":" payload))

(defn- canonical-value-string
  "The canonical encoding of `value`. It recurses through a local function, so
  the armed wrapper runs once per value, never once per node."
  {:malli/schema [:=> [:cat :seon.schema/value] :string]}
  [value]
  (letfn [(coll [tag values]
            (framed tag (apply str (map encode values))))
          (encode [value]
            (cond
              (nil? value) "n"
              (true? value) "b1"
              (false? value) "b0"
              (keyword? value) (framed "k" (str value))
              (symbol? value) (framed "y" (str value))
              (string? value) (framed "s" value)
              (number? value) (framed "d" (str value))
              (vector? value) (coll "v" value)
              (set? value) (coll "t" (sort (map encode value)))
              (map? value)
              (coll "m" (sort (map (fn [[k v]] (str (encode k) (encode v))) value)))
              (sequential? value) (coll "q" value)
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
                               :seon.schema/noncanonical-projection-data true}))))]
    (encode value)))


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
   artifact-exports pure-predicate-symbols]
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
     (portable-string-hash "seon.schema.projection/fingerprint-v3")
     [(map-fingerprint :forms forms)
      (map-fingerprint :function-contracts function-contracts)
      (map-fingerprint :schema-admissions schema-admissions)
      (map-fingerprint :function-admissions function-admissions)
      (set-fingerprint :artifact-exports artifact-exports)
      (set-fingerprint :pure-predicate-symbols pure-predicate-symbols)])))

(def ^:private projection-fingerprint-version 3)

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
    (contract-references (:registry compile-options) (set (keys forms)) compiled)))

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
                  "recorded admission source, so this entity is admitted as "
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
;; here: this namespace sits below `seon.db`, so no fault can be recorded, and
;; a throw would be wrong regardless — the fallback is the LEGITIMATE bootstrap
;; path before any projection exists, so throwing would make boot impossible. Loud therefore
;; means NAMED AND COUNTED, and production-bounded means one line per calling
;; function per decade of occurrences: the 2026-08-07 loop would have printed
;; six lines, not a quarter of a million.
(defonce ^:private !fallback-counts (atom {}))

(defn- frame-namespace
  "The namespace part of a stack frame's demunged class name."
  {:malli/schema [:=> [:cat :seon.error/frame] :string]}
  [[class-name]]
  (let [demunged (clojure.lang.Compiler/demunge (str class-name))
        separator (.indexOf demunged "/")]
    (if (neg? separator) demunged (subs demunged 0 separator))))

;; The warning is only worth printing if its advice is ACTIONABLE, and the
;; reader can only act on code we wrote. Which frames those are is the refusal
;; owner's one classpath-derived rule (`seon.error.refusal/first-party-frame?`);
;; the resolution owners themselves are the callee, never the caller.
(def ^:private resolution-owner-namespaces #{"seon.schema" "seon.schema.edn" "seon.instrument"})

(defn- fallback-caller
  "The nearest first-party frame outside resolution, as `ns (file:line)`.
   Stack introspection is a diagnostic and runs only on the fallback path."
  {:malli/schema [:=> [:cat] :string]}
  []
  (or (some (fn [^StackTraceElement element]
              (when-let [file (.getFileName element)]
                (let [frame [(symbol (.getClassName element)) (symbol (.getMethodName element))
                             file (long (.getLineNumber element))]
                      frame-ns (frame-namespace frame)]
                  (when (and (not (contains? resolution-owner-namespaces frame-ns))
                             (refusal/first-party-frame? frame))
                    (str frame-ns " (" file ":" (.getLineNumber element) ")")))))
            (.getStackTrace (Thread/currentThread)))
      "an unidentified caller"))

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
    (let [caller (fallback-caller)
          diagnostic
          (vary-meta (seon.error.refusal/diagnostic (java.util.Date.) :seon.schema/derivation 'seon.schema/declaration-population
           {:seon.error/message "No declaration projection was handed to this schema operation."
            :seon.schema/missing-projection true
            :seon.schema/refused-value nil
            :seon.schema/expected-value :seon.schema/projection
            :seon.error/data {:seon.schema/caller caller}}) assoc :seon.error/declared-schema :seon.schema/validation-refusal)]
      (throw (ex-info (:seon.error/message diagnostic) diagnostic)))))

(defn call-with-forms
  "Call `f` with one immutable declaration population for this operation."
  {:malli/schema [:=> [:cat :map [:fn clojure.core/ifn?]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [forms f]
  (binding [*packaged-forms* forms]
    (f)))

(defn call-with-projection
  "Call `f` with one immutable database-derived projection for this operation."
  {:malli/schema [:=> [:cat :map [:fn clojure.core/ifn?]] [:or :seon.error/base :seon.schema/validation-refusal :seon.db.write/validation-refusal :seon.instrument/registration-error [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The callback preserves declaration data or returns the writer's base diagnostic or instrumentation registration refusal unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]]]}
  [projection f]
  (binding [*projection* projection
            *projection-state* nil]
    (f)))

(defn call-with-projection-state
  "Call `f` with one cluster-owned, advanceable schema projection state."
  {:malli/schema [:=> [:cat [:fn clojure.core/deref] [:fn clojure.core/ifn?]] [:or :seon.schema/validation-refusal :seon.db.write/validation-refusal :seon.instrument/registration-error [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The body returns its polymorphic result unchanged; instrumentation acquisition can return its declared registration refusal.", :gen/elements [nil false 0 "" :k [] {}]}]]]}
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
         }))))
  predicate)

(defn core-predicate-registered?
  "True when `predicate` resolves to a callable Var."
  {:malli/schema [:=> [:cat :qualified-symbol] :boolean]}
  [predicate]
  (boolean (some-> (runtime-predicate predicate) var-get ifn?)))

(register-core-predicate! 'seon.schema/byte-array? byte-array?)

(defn- update-candidate-forms!
  {:malli/schema [:=> [:cat [:=> [:cat :map [:* :seon.schema/value]] :map] [:* :seon.schema/value]] :map]}
  [f & args]
  (if *candidate-forms-overlay*
    (apply swap! *candidate-forms-overlay* f args)
    (throw
     (ex-info "Schema declarations require an isolated registration delta."
              {:seon.schema/error
               :seon.schema/registration-outside-delta
               :seon.schema/registration-outside-delta true
               }))))

(defn assert-config-display!
  "Admission gate: refuse a per-agent dial that cannot name its settings row.

   Called for new or changed declarations only (`projection-with-schema`,
   bootstrap `schema.edn/admit`); a projection build never re-judges an
   admitted population, so an as-of view stays readable."
  {:malli/schema [:=> [:cat :map] :nil]}
  [forms]
  (doseq [[attribute definition] forms
          :let [properties (m/properties (structural-schema definition))]
          :when (and (:seon.config/dial properties)
                     (:seon.config/per-agent properties)
                     (not (and (string? (:seon.config/display-label properties))
                               (seq (:seon.config/display-label properties)))))]
    (let [refusal (vary-meta {:seon.error/at (java.util.Date.)
                   :seon.error/layer :seon.schema/admission
                   :seon.error/operation 'seon.schema/assert-config-display!
                   :seon.schema/refused-value definition
                   :seon.schema/expected-value :seon.config/display-label
                   :seon.schema/key attribute
                   :seon.error/message
                   (str "Per-agent dial " attribute
                        " must declare a nonempty :seon.config/display-label.")} assoc :seon.error/declared-schema :seon.schema/validation-refusal)]
      (throw (ex-info (:seon.error/message refusal) refusal)))))

;;; The process-cache weights of the projection kinds: bytes a retained entry
;;; keeps alive per counted unit, fitted offline (bench/seon/measure_cache_kinds.clj,
;;; one kind per fresh JVM; results in bench/seon/cache_kind_measurement.edn,
;;; 2026-09-27: declaration projection 8.9-9.1 MB / 3,549 forms; projection
;;; 10.1 MB marginal over it, 19.5-19.9 MB with 2,371 contracts; the
;;; predicate Vars the packaged forms name, 5.1 KB / 72).
;;; test/seon/cache_calibration_test.clj holds each charge to its measurement.
(def declaration-projection-bytes-per-form 2550)
(def projection-bytes-per-form 2850)
(def projection-bytes-per-contract 4050)
(def declaration-predicates-bytes-per-entry 72)

(defn declaration-projection
  "One immutable projection over the declaration population in hand.

   A seam that walks many attributes resolves this ONCE and passes it to every
   `-in` question it asks (see [[declaration-population]] for the cost of not
   doing so). It carries the registry as well as the forms, because a
   projection without one cannot compile a validator — a forms-only map made
   `projection-validator` throw `:malli.core/invalid-schema` for every
   EDN-backed attribute (2026-08-07). Construction realizes and retains every
   named schema; ordinary validation never rebuilds this generation."
  {:malli/schema
   [:function
    [:=> [:cat] ::projection]
    [:=> [:catn [::forms :map]] ::projection]]}
  ([] (declaration-projection (declaration-population)))
  ([forms]
   (let [;; The predicate Vars the forms name: a function of the forms
         ;; (a Var keeps its identity across a reload), so it is keyed by
         ;; them. Walking every form for it cost ~6.4 ms armed per call.
         predicates (seon.cache/lookup
                     ::declaration-predicates forms
                     #(* declaration-predicates-bytes-per-entry (count %))
                     (fn []
                       (into {} (keep (fn [sym]
                                        (when-let [f (runtime-predicate sym)] [sym f])))
                             (into #{} (mapcat predicate-symbols-in) (vals forms)))))]
     ;; A pure function of its forms and the predicate Vars they name: equal
     ;; inputs answer the built value from the process cache (~340 ms /
     ;; ~410 MB per packaged build, perf-profile 2026-09-26), weighed by
     ;; its measured retention per form.
     (seon.cache/lookup
      ::declaration-projection [forms predicates]
      #(* declaration-projection-bytes-per-form (count (:seon.schema.projection/forms %)))
      (fn []
        (let [dependencies (canonical-reference-graph forms predicates)
              _ (assert-acyclic-references!
                 forms (keys forms) dependencies)
              registry (projection-registry forms predicates)]
          (with-compiled-cache
           (with-predicate-functions
            {:seon.schema.projection/forms forms
             :seon.schema.projection/schema-dependencies dependencies
             :seon.schema.projection/registry registry
             :seon.schema.projection/compile-options {:registry registry}}
            predicates))))))))

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
  (let [defaults (mr/fast-registry
                  (assoc (m/default-schemas) :fn
                         (m/-simple-schema {:type :fn :pred any? :min 1 :max 1})))]
    (reify
      mr/Registry
      (-schema [_ type]
        (or (mr/-schema defaults type)
            ;; Canonical names and loaded Var references remain opaque here.
            ;; The armer's supplied var-registry resolves Vars later; syntax
            ;; preparation must neither dereference them nor reject them.
            (when (or (keyword? type) (qualified-symbol? type) (var? type))
              (m/-simple-schema {:type type :pred any? :min 0 :max nil}))))
      (-schemas [_] (mr/-schemas defaults)))))

(defn structural-schema
  "Compile syntax for preparation, with references opaque and predicates inert.

   This cannot prove a target exists, storage mapping or inherited members.
   Full admission resolves those questions in its supplied complete registry.
   No predicate or generator namespace is loaded by this inspection."
  {:malli/schema [:=> [:cat :seon.schema/value] [:fn malli.core/schema?]]}
  [definition]
  (m/schema definition {:registry structural-registry}))

(defn malli-form?
  "True when `value` is readable EDN and Malli can parse its STRUCTURE.

   References are opaque here: this predicate never asks whether a declaration
   population defines them, because the authority extending the projection
   re-decides that against the projection it holds. Validation, reference
   resolution, and acyclicity remain separate operations at that authority."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (let [encoded (pr-str value)
        ;; Only each dependency's declared "not parseable" refusal is false:
        ;; clojure.edn's RuntimeException (EdnReader.java:130, :174-177) and
        ;; Malli's `-exception` ex-info with a `malli.core` :type
        ;; (reference-code/malli/src/malli/core.cljc:203). Everything else
        ;; propagates.
        [decoded readable?] (try [(edn/read-string encoded) true]
                                 (catch RuntimeException _ [nil false]))]
    (boolean
     (and readable?
          (= value decoded)
          (try
            (some? (m/schema (compilable-form decoded {})
                             {:registry structural-registry}))
            (catch clojure.lang.ExceptionInfo failure
              (let [data (ex-data failure)
                    malli-type (:type data)]
                ;; and this owner's own declared refusal for a predicate
                ;; with no admitted callable (`compilable-form`, above), which
                ;; fails closed without loading its namespace.
                (if (or (and (qualified-keyword? malli-type)
                             (= "malli.core" (namespace malli-type)))
                        (= :seon.schema/unresolved-predicate
                           (:seon.schema/error data)))
                  false
                  (throw failure)))))))))

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
  ;; A pull selector is sequential (datalog-parser's parse-pull reads a
  ;; vector of attr-specs). Its declared refusal is the ex-info carrying
  ;; `{:error :parser/pull}` (datalog-parser 0.2.37 datalog/parser/pull.cljc:233,
  ;; reached through reference-code/datahike/src/datahike/pull_api.cljc:58).
  ;; Everything else propagates.
  (boolean
   (and (sequential? value)
        (try
          (pull-api/compile-pull-plan value)
          true
          (catch clojure.lang.ExceptionInfo failure
            (if (= :parser/pull (:error (ex-data failure)))
              false
              (throw failure)))))))

(register-core-predicate! 'seon.schema/pull-selector? pull-selector?)


;;; ---------------------------------------------------------------------------
;;; Registration API
;;; ---------------------------------------------------------------------------

(defn- assert-entity-partition!
  "Identity-bearing stored entity schemas must declare their partition."
  {:malli/schema [:=> [:cat :seon.schema/registry-key
                       [:fn malli.core/schema?] [:fn malli.registry/registry?]] :nil]}
  [schema-key compiled registry]
  (let [properties (internal/entity-properties compiled)]
    (when (and (:seon.db/attributes properties)
               (some (fn [[attribute _ _]]
                       (some-> (mr/schema registry attribute)
                               m/properties :seon.db/identity))
                     (internal/entity-entries compiled))
               (not (#{:seon.program :seon.data}
                     (:seon.program/partition properties))))
      (throw
       (ex-info "An identity-bearing entity schema must declare its partition."
                {:seon.error/operation 'seon.schema/build-projection
                 :seon.schema/identity schema-key
                 :seon.schema/member :seon.program/partition
                 :seon.schema/expected #{:seon.program :seon.data}
                 :seon.schema/offending properties}))))
  nil)

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
  (let [prepared? (and (or compiled-forms compiled-schemas)
                       registry compile-options canonical-keys)
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
        compiled-forms (or compiled-forms compiled-schemas
                           (bound-forms forms predicate-functions))
        compiled-definition
        (or compiled-definition compiled
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
                 }))))
        ;; Every consumer reads a registry entry as a compiled schema, as a
        ;; projection's registry holds them; a registry of FORMS made each
        ;; read resolve against Malli's defaults and refuse declared keys
        ;; such as `:inst`. Compile lazily in this registry, as
        ;; `projection-registry` does.
        registry (or registry
                     (mr/lazy-registry
                      (m/default-schemas)
                      (fn [reference scope]
                        (when-let [form (get compiled-forms reference)]
                          ((if (qualified-symbol? reference) m/function-schema m/schema)
                           form {:registry scope})))))
        compile-options
        (or compile-options
            {:registry registry})
        canonical-keys (or canonical-keys (set (keys forms)))
        default-admission (or admission
                              {:seon.schema.admission/source :agent})]
    (letfn [(walk-schema [schema role entity-identity entity-definition entity-admission visited]
              (if (and (keyword? entity-identity)
                       reference-advisories
                       (contains? compiled-schemas entity-identity))
                (reference-advisories
                 entity-identity role entity-admission
                 #(walk-schema-body schema role entity-identity entity-definition
                                    entity-admission visited))
                (walk-schema-body schema role entity-identity entity-definition
                                  entity-admission visited)))
            (walk-schema-body [schema role entity-identity entity-definition entity-admission
                               visited]
              (when (keyword? entity-identity)
                (@schema-datahike-assert-storable-schema! entity-identity schema))
              (let [advisories
                    (if (and reference-advisories
                             (= :core (:seon.schema.admission/source entity-admission))
                             (not= :seon.error/base entity-identity)
                             (not (internal/extends-schema? schema :seon.error/base)))
                      ;; For core declarations this inspection can refuse only
                      ;; error-schema inheritance. Its other output is advisory
                      ;; data, which the constructor does not consume.
                      []
                      (internal/assert-complete-schema!
                     {:seon.schema/identity entity-identity
                      :seon.schema/forms forms
                      :seon.schema/storable-attribute?
                      (fn [attribute] (@schema-datahike-storable-attribute-in?
                                       {:seon.schema.projection/forms forms
                                        :seon.schema.projection/registry registry} attribute))
                      :seon.schema/definition entity-definition
                      :seon.schema/compiled schema
                      :seon.schema/role role
                      :seon.schema/admission entity-admission
                      :seon.schema/predicate-symbols
                      (predicate-symbols-in entity-definition)
                      :seon.schema/pure-predicate-symbols pure-predicate-symbols
                      :seon.schema/canonical-keys canonical-keys}))
                    references
                    (if (and (keyword? entity-identity)
                             (contains? schema-dependencies entity-identity))
                      (get schema-dependencies entity-identity)
                      (contract-references (:registry compile-options) canonical-keys schema))]
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
                                       (compilable-form reference-form predicate-functions))
                                   compile-options))
                              role reference reference-form reference-admission
                              (conj visited reference))))))
                      references)))
            (walk-function [compiled entity-identity entity-definition entity-admission]
              (if (m/-function-schema? compiled)
                (into
                 []
                 (mapcat
                  (fn [arity]
                    (let [{:keys [input output guard]}
                          (m/-function-info arity)]
                      (cond->
                       (into
                        (walk-schema input :input entity-identity entity-definition
                                     entity-admission #{})
                        (walk-schema output :output entity-identity entity-definition
                                     entity-admission #{}))
                        guard
                        (into
                         (walk-schema guard :guard entity-identity entity-definition
                                      entity-admission #{}))))))
                 (m/-function-schema-arities compiled))
                (walk-schema compiled :schema entity-identity entity-definition
                             entity-admission #{})))]
      (let [compiled
            (or compiled
                (if (and (vector? definition)
                         (#{:=> :function} (first definition)))
                  (m/function-schema compiled-definition compile-options)
                  (m/schema compiled-definition compile-options)))]
        (when (keyword? identity)
          (assert-entity-partition! identity compiled registry))
        (walk-function compiled identity definition default-admission)))))

(def ^:dynamic ^:private *contract-validation-fold-size* 64)

(defn- validate-contracts!
  "Validate each declaration; the constructor does not return advisories."
  {:malli/schema [:=> [:cat [:vector :map]] :nil]}
  [requests]
  (reducers/fold *contract-validation-fold-size*
                 (fn ([] nil) ([_ _] nil))
                 (fn [_ request] (assert-complete-contract! request) nil)
                 requests))

(defn identity-attr?
  "Whether the supplied generation declares this attribute as an identity."
  {:malli/schema [:=> [:cat :seon.schema/projection :keyword] :boolean]}
  [projection attr-key]
  (boolean (some-> (mr/schema (:seon.schema.projection/registry projection) attr-key)
                   m/properties :seon.db/identity)))

(defn enum-members
  "Members of a registered `:enum` attr schema, or an empty vector.

   Reads the retained compiled root from the supplied generation.
   Empty when the attr is not an enum. Members are literal values
   (keywords/strings/ints) — a third-party-structure boundary, hence `:any`."
  {:malli/schema [:=> [:cat ::projection :keyword] [:vector [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli enum members are arbitrary literal values, not a homogeneous collection.", :gen/elements [nil false 0 "" :k [] {}]}]]]}
  [projection attr-key]
  (if-let [compiled (mr/schema (:seon.schema.projection/registry projection) attr-key)]
    (let [node (m/deref compiled)]
      (if (= :enum (m/type node)) (vec (m/children node)) []))
    []))

(declare register-candidate!)

(defn- registration-refusal
  "The error value a refused registration of `k` as `v` answers with: the
  population's own refusal (an `ex-info` naming its kind under
  `:seon.schema/error` or `:seon.schema.edn/error`) with its whole cause
  chain, the kind as its member and the authored form as the offending value."
  {:malli/schema [:=> [:cat ::registry-key [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The refused authored form can be any value, a function object included.", :gen/elements [nil 0 :k [] {}]}] :seon.error/throwable]
                  ::registration-refused-error]}
  [k v refused]
  (let [data (ex-data refused)]
    (refusal/diagnostic
     ;; the producer's fields (its kind, cycle path, missing reference...);
     ;; the key and form are named once, below, never as an identity
     (merge (apply dissoc data :seon.schema/key :seon.schema/definition
                   (remove qualified-keyword? (keys data)))
            {:seon.error/at (java.util.Date.) :seon.error/layer :seon.schema/registration
             :seon.error/operation 'seon.schema/register!
             :seon.error/message (ex-message refused)
             :seon.error/member (or (:seon.schema/error data) (:seon.schema.edn/error data))
             :seon.error/offending v
             :seon.schema/registration-refused k
             :seon.error/throwable refused}))))

(defn register!
  "Define a new attribute so facts using it can be saved and queried.

   Adds one canonical declaration to the current candidate collector. Schema
   references are resolved only when [[build-projection]] validates the
   complete population, so namespace load order cannot change whether a
   declaration is accepted.

   Arguments:
     k - Schema keyword (use `::name` for auto-namespacing)
     v - Malli schema definition

   Returns the registration `{:seon.schema/key k :seon.schema/definition v}`
   (`:seon.schema/registration`). Inside an evaluation, a changed form of an
   installed attribute also carries `:seon.schema.usage/check`: how many
   stored values the new form refuses (`seon.db/registration-check`). Its
   render pair shows the form it replaces, that check, and the schemas and
   contracts naming `k` (`seon.render.schema/usage`).

   A registration the population refuses (an unreadable or non-round-tripping
   form, a nilable value schema, an admission refusal) is RETURNED as a
   `:seon.schema/registration-refused-error` naming the refused kind as its
   member and the authored form as the offending value; nothing is admitted.

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
                  [:or ::registration ::registration-refused-error]]}
  [k v]
  (try
    (register-candidate! k v)
    ;; Declared cases: the population's refusals, each naming its kind.
    (catch clojure.lang.ExceptionInfo refused
      (if (let [data (ex-data refused)] (or (:seon.schema/error data) (:seon.schema.edn/error data)))
        (registration-refusal k v refused)
        (throw refused)))))

(defn- register-candidate!
  "The body of [[register!]]: admit `v` as `k`, or throw the population's
  refusal, which [[register!]] returns as its error value."
  {:malli/schema [:=> [:cat ::registry-key ::definition] ::registration]}
  [k v]
  (internal/assert-non-nilable-value-schema! (candidate-forms) k (structural-schema v))
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
                         :seon.schema/unreadable-form k}
                        e))))]
    (when-not (= v decoded)
      (throw
        (ex-info
          (str "schema/register! " k
               ": schema form does not round-trip as EDN")
          {:seon.schema/error :seon.schema/non-round-tripping-form
           :seon.schema/key k
           :seon.schema/definition v
           :seon.schema/non-round-tripping-form k}))))
  ;; The schema authority's own shapes are the computed bootstrap population:
  ;; they must exist before the EDN loader and its admission gate can compile.
  ;; Every other JVM registration flows through seon.schema.edn/admit once that
  ;; namespace has finished loading; there is no hand-maintained exception set.
  (when-let [admit (some-> (find-ns 'seon.schema.edn)
                           (ns-resolve 'admit))]
    (let [admitted (admit
                    {:seon.schema/forms (assoc (candidate-forms) k v)
                     :seon.schema/identity k
                     :seon.schema/admission
                     {:seon.schema.admission/source *registration-admission-source*}})]
      ;; the population's refusal value, raised to register!, which answers it
      (when (:seon.error/at admitted)
        (throw (ex-info (:seon.error/message admitted) admitted)))))
  (update-candidate-forms! assoc k v)
  ;; The stored-data check runs here, once, in the registering evaluation
  ;; (never in its render): `seon.db` is resolved without loading it, as
  ;; `seon.schema.edn/admit` is above, since `seon.db` requires this namespace.
  (let [check (when-let [registration-check (some-> (find-ns 'seon.db)
                                                    (ns-resolve 'registration-check))]
                (registration-check k v))]
    (cond-> {:seon.schema/key k :seon.schema/definition v}
      (some? check) (assoc :seon.schema.usage/check check))))

(defn unregister!
  "Stage removal of one schema from the current evaluation delta.

   Removal is published only if the evaluation's terminal transaction
   commits. Called outside an isolated registration delta it returns
   `:seon.schema/unregister-outside-delta-error` rather than mutating the
   process-wide candidate population."
  {:malli/schema [:=> [:catn [::registry-key ::registry-key]]
                  [:or ::registry-key ::unregister-outside-delta-error]]}
  [k]
  (if *candidate-forms-overlay*
    (do (swap! *candidate-forms-overlay* dissoc k)
        k)
    (refusal/diagnostic
     {:seon.error/at (java.util.Date.) :seon.error/layer :seon.schema/registration
      :seon.error/operation 'seon.schema/unregister!
      :seon.error/message "schema/unregister! requires an evaluation registration delta."
      :seon.error/member :seon.schema/unregister-outside-delta
      :seon.error/fix "Unregister inside an agent evaluation, whose run stages the removal and publishes it when its settlement commits."
      :seon.schema/unregister-outside-delta k})))

(defn ^:no-doc contribute-candidate-forms!
  "Merge a prevalidated population into the candidate collector."
  {:malli/schema [:=> [:catn [::forms :map]] :map]}
  [forms]
  (update-candidate-forms! merge forms)
  (candidate-forms))

(def ^:private render-declaration-properties
  [:seon.render/ai :seon.render/html :seon.render/form])

(defn- render-declarations-in
  "Named render declarations carried by the selected schema forms."
  [projection schema-keys]
  (into []
        (mapcat
         (fn [schema-key]
           (let [definition (get (:seon.schema.projection/forms projection) schema-key)
                 properties
                 (m/properties (mr/schema (:seon.schema.projection/registry projection) schema-key))]
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

(defn- arity-render-input
  [arity]
  (let [input (:input (m/-function-info arity))
        arguments (m/children input)
        arguments (if (= :catn (m/type input)) (map peek arguments) arguments)]
    (first (remove #(and (m/-ref-schema? %)
                         (= :seon.db/database-value (m/-ref %))) arguments))))

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
    (let [registry (:seon.schema.projection/registry projection)
          declaring (mr/schema registry schema-key)
          arities (m/-function-schema-arities (mr/schema registry renderer))
          accepted
          (some (fn [arity]
                  (when-let [input (arity-render-input arity)]
                    (let [reference (when (m/-ref-schema? input) (m/-ref input))]
                      (when (or (= reference schema-key)
                                (= reference :seon.schema/value)
                                ;; A render unit carries any value under
                                ;; :seon.render/value, including scalar refs.
                                (= reference :seon.render/unit)
                                (and (= :or (m/type input))
                                     (some #(and (m/-ref-schema? %)
                                                 (= :seon.render/unit (m/-ref %)))
                                           (m/children input)))
                                (schema-accepts-schema? input declaring))
                        (m/form input)))))
                arities)]
      {:seon.schema/render-contract contract
       :seon.schema/render-input
       (or accepted (some-> (some arity-render-input arities) m/form))
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
  {:malli/schema
   [:=> [:cat [:map [:seon.schema/key :seon.schema/key]
                    [:seon.render/property :qualified-keyword]
                    [:seon.render/function :qualified-symbol]]
         [:map [:seon.schema/render-contract :seon.schema/value]
               [:seon.schema/render-input :seon.schema/value]
               [:seon.schema/render-contract-cause :seon.schema/render-contract-cause]]]
    :nil]}
  [{schema-key :seon.schema/key
    property :seon.render/property
    renderer :seon.render/function}
   {:seon.schema/keys [render-contract render-input render-contract-cause]}]
  (let [diagnostic
        (vary-meta (seon.error.refusal/diagnostic (java.util.Date.) :seon.schema/admission 'seon.schema/render-contract-refusal!
         {:seon.schema/refused-value renderer
          :seon.schema/render-contract-cause render-contract-cause
          :seon.schema/expected-value schema-key
          :seon.error/message (str "Schema publication refused " schema-key ": " property
               " names " renderer
               (if (= :seon.schema/render-function-has-no-declared-contract render-contract-cause)
                 " which has no declared contract."
                 (str " whose declared input " (pr-str render-input)
                      " does not accept the declaring shape.")))
          :seon.error/data {:seon.schema/key schema-key
           :seon.render/property property
           :seon.render/function renderer
           :seon.fn/spec render-contract
           :seon.fn/input render-input}}) assoc :seon.error/declared-schema :seon.schema/validation-refusal)]
    (throw (ex-info (:seon.error/message diagnostic) diagnostic))))

(defn- assert-render-contracts!
  [projection schema-keys]
  (doseq [{schema-key :seon.schema/key
           renderer :seon.render/function
           :as declaration}
          (render-declarations-in projection schema-keys)
          :let [observation
                (render-contract-observation projection schema-key renderer)]
          :when (not (:seon.schema/render-contract-coherent? observation))]
    (render-contract-refusal! declaration observation))
  projection)

(defn- shape-entity-in
  [registry schema-key]
  (let [compiled (mr/schema registry schema-key)]
    ;; Only maps and conjunctions can contribute entity entries. Scalar
    ;; declarations do not need either entity traversal.
    (when (or (= :map (m/type compiled))
              (and (or (m/-ref-schema? compiled) (= :and (m/type compiled)))
                   (internal/entity-schema? compiled)))
      (let [entries (internal/entity-entries compiled)
            props (or (internal/entity-properties compiled)
                      (m/properties compiled) {})
            required-attrs
            (not-empty (into #{} (keep (fn [[k properties _]]
                                        (when (and (keyword? k) (not= k ::m/default)
                                                   (not (:optional properties))) k)))
                             entries))
            renderers (into {}
                            (filter
                             (fn [[property declaration]]
                               (and (qualified-keyword? property)
                                    (= "seon.render" (namespace property))
                                    (qualified-symbol? declaration))))
                            props)]
        ;; A map with no required attribute is still a shape when it is an
        ;; entity or declares its render pair: a value carrying any of its
        ;; declared attributes and validating is of that schema.
        (when (or (seq required-attrs)
                  (and (seq entries)
                       (or (:seon.db/attributes props) (seq renderers))))
          (merge
            {:seon.schema/key schema-key
             :seon.schema/required-attrs required-attrs
             :seon.schema/entity? (boolean (:seon.db/attributes props))}
            renderers))))))

(defn- validate-declarations!
  "Validate `schema-keys` and `function-symbols` of one compiled candidate
   population as one batch.

   Every declaration and role shares one reference walk per referenced
   declaration, so a batch costs its distinct references, not the paths to
   them; a whole build and a replacement's closure are the same request over
   different identities."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.schema/forms :map]
               [:seon.schema/function-contracts :map]
               [:seon.schema/compiled-schemas :map]
               [:seon.schema/schema-dependencies :map]
               [:seon.schema/registry [:fn malli.registry/registry?]]
               [:seon.schema/canonical-keys [:set :keyword]]
               [:seon.schema/schema-admissions :map]
               [:seon.schema/function-admissions :map]
               [:seon.schema/pure-predicate-symbols [:set :symbol]]
               [:seon.schema/predicate-functions :map]
               [:seon.schema/schema-keys [:sequential [:or :keyword :symbol]]]
               [:seon.schema/function-symbols [:sequential :symbol]]]]
    :nil]}
  [{:seon.schema/keys [forms function-contracts compiled-schemas
                       schema-dependencies registry canonical-keys
                       schema-admissions function-admissions
                       pure-predicate-symbols predicate-functions
                       schema-keys function-symbols]}]
  (let [core-admission {:seon.schema.admission/source :core}
        options {:registry registry}
        ;; One operation's memo (core.cache wrapped): a reference walk runs
        ;; once per declaration, role and admission source, however many
        ;; paths reach it.
        reference-memo (cache/basic-cache-factory {})
        core-references?
        (every? #(= :core (get-in schema-admissions
                                 [% :seon.schema.admission/source]))
                (keys forms))
        reference-advisories
        (fn [reference role admission derive-advisories]
          ;; The only role-sensitive rule is an agent's nilable output
          ;; (internal/assert-complete-schema!). If EVERY referenced
          ;; declaration explicitly records core admission, the whole
          ;; reference walk has the same result in every role. The reference
          ;; graph is already proven acyclic. Admission consumes refusals, not
          ;; advisory vectors: a shared descendant's advisory output is not
          ;; copied along every path to it; standalone contract inspection
          ;; still returns it.
          (cache/lookup-or-miss
           reference-memo
           [reference (if core-references? :schema role)
            (:seon.schema.admission/source admission)]
           (fn [_] (derive-advisories) [])))
        validation-base
        {:seon.schema/forms forms
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
                   :seon.schema/admission
                   (get schema-admissions k core-admission)))
          (map (juxt identity forms) (sort schema-keys)))
         (comp
          ;; Core function contracts have already compiled. Completeness
          ;; inspection has no refusing rule for their inline positions;
          ;; their referenced core schemas are validated above. Agent
          ;; declarations and any population with agent references keep the
          ;; role-sensitive walk (internal/assert-complete-schema!).
          (remove (fn [[sym _]]
                    (and core-references?
                         (= :core (:seon.schema.admission/source
                                   (get function-admissions sym core-admission))))))
          (map
          (fn [[sym contract]]
            (assoc validation-base
                   :seon.schema/identity sym
                   :seon.schema/definition contract
                   :seon.schema/direct-predicate-symbols
                   (predicate-symbols-in contract)
                   :seon.schema/compiled
                   (get compiled-schemas sym)
                   :seon.schema/admission
                   (get function-admissions sym core-admission)))))
         (map (juxt identity function-contracts) (sort function-symbols)))]
    (validate-contracts! validation-requests)))

(declare incremental-base? projection-with-declarations)

(defn- predicate-bindings
  "`supplied` plus the loaded Var each other predicate in `forms` and
   `function-contracts` names ([[runtime-predicate]])."
  {:malli/schema
   [:=> [:cat :map :map [:map-of :qualified-symbol [:fn clojure.core/ifn?]]]
    [:map-of :qualified-symbol [:fn clojure.core/ifn?]]]}
  [forms function-contracts supplied]
  (reduce (fn [bindings predicate]
            (if (contains? bindings predicate)
              bindings
              (if-let [f (runtime-predicate predicate)]
                (assoc bindings predicate f)
                bindings)))
          supplied
          (into (into #{} (mapcat predicate-symbols-in) (vals forms))
                (mapcat predicate-symbols-in)
                (vals function-contracts))))

(defn- projection-build
  "The build [[build-projection]] memoizes: its whole body over explicit inputs."
  {:malli/schema
   [:=> [:catn [::forms :map] [::function-contracts :map]
               [:seon.schema/projection-options :map]]
    :map]}
  [forms function-contracts
    {:seon.schema/keys [schema-admissions function-admissions
                        artifact-exports
                        pure-predicate-symbols predicate-functions
                        validate-render-contracts? admission?]
     :or {admission? true
          schema-admissions {}
          function-admissions {}
          artifact-exports #{}
          pure-predicate-symbols #{}
          predicate-functions {}}
     :as options}]
   (cond
     ;; A complete supplied projection is derived from by replacement: only
     ;; the changed declarations and their reverse closure recompile.
     (some-> (:seon.schema/projection options) incremental-base?)
     (projection-with-declarations
      'seon.schema/build-projection (:seon.schema/projection options)
      forms function-contracts options)

     :else
     (let [predicate-functions
         (predicate-bindings forms function-contracts predicate-functions)
         supplied (:seon.schema/projection options)
         reuse-declarations?
         (and (:seon.schema.projection/registry supplied)
              (:seon.schema.projection/schema-dependencies supplied)
              (not (contains? supplied :seon.schema.projection/function-contracts))
              (= forms (:seon.schema.projection/forms supplied))
              (= (predicate-functions-in supplied)
                 (select-keys predicate-functions
                              (into #{} (mapcat predicate-symbols-in) (vals forms)))))
         schema-dependencies
         (if reuse-declarations?
           (:seon.schema.projection/schema-dependencies supplied)
           (canonical-reference-graph forms predicate-functions))
         _ (when-not reuse-declarations?
             (assert-acyclic-references!
              forms (keys forms) schema-dependencies))
         registry (projection-registry
                   forms predicate-functions function-contracts
                   (if reuse-declarations?
                     (select-keys (mr/schemas (:seon.schema.projection/registry supplied))
                                  (keys forms))
                     {}))
         options  {:registry registry}
         canonical-keys (set (keys forms))
         ordered-forms (sort-by key forms)
         compiled-schemas
         (into {}
               (map (fn [[k _]]
                      [k (mr/schema registry k)]))
               ordered-forms)
         compiled-function-contracts
         (into {}
               (map (fn [[sym _contract]]
                      [sym (mr/schema registry sym)]))
               function-contracts)
         _ (when admission?
            (validate-declarations!
            {:seon.schema/forms forms
             :seon.schema/function-contracts function-contracts
             :seon.schema/compiled-schemas compiled-schemas
             :seon.schema/schema-dependencies schema-dependencies
             :seon.schema/registry registry
             :seon.schema/canonical-keys canonical-keys
             :seon.schema/schema-admissions schema-admissions
             :seon.schema/function-admissions function-admissions
             :seon.schema/pure-predicate-symbols pure-predicate-symbols
             :seon.schema/predicate-functions predicate-functions
             :seon.schema/schema-keys (vec (keys forms))
             :seon.schema/function-symbols (vec (keys function-contracts))}))
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
                     [sym (contract-references
                           registry canonical-keys
                           (get compiled-function-contracts sym))]))
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
        shape-entities
        (into (sorted-map)
              (keep
                (fn [[k _]]
                  (when-let [entity (shape-entity-in registry k)]
                    [k entity])))
              forms)
        required-by-key
        (into (sorted-map)
              (map (fn [[k entity]]
                     [k (:seon.schema/required-attrs entity)]))
              shape-entities)
        raw-shape-index
        (reduce-kv
          (fn [index schema-key required-attrs]
            (reduce (fn [result attr]
                      (update result attr (fnil conj []) schema-key))
                    index
                    (or (seq required-attrs)
                        (map first (internal/entity-entries (mr/schema registry schema-key))))))
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
        catalog  (->> shape-entities
                      (sort-by (comp str key))
                      (map second)
                      (filter :seon.schema/entity?)
                      vec)
        fingerprint
        (projection-fingerprint
         forms function-contracts schema-admissions function-admissions
         artifact-exports pure-predicate-symbols)
        projection
        (with-predicate-functions
         {:seon.schema.projection/forms forms
          :seon.schema.projection/registry registry
          :seon.schema.projection/compile-options options
          :seon.schema.projection/schema-admissions schema-admissions
          :seon.schema.projection/function-admissions function-admissions
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
          :seon.schema.projection/shape-rows shape-entities
          :seon.schema.projection/catalog catalog
          :seon.schema.projection/fingerprint-version
          projection-fingerprint-version
          :seon.schema.projection/fingerprint fingerprint}
         predicate-functions)]
    (when validate-render-contracts?
      (assert-render-contracts! projection (keys forms)))
    (with-compiled-cache projection))))


(defn build-projection
  "Build and validate one immutable runtime projection.

   `forms` is the canonical `{schema-key form}` population and optional
   `function-contracts` is `{qualified-symbol function-form}`. Every schema and
   contract compiles against the complete candidate registry, so validation is
   independent of declaration order. Schema/function dependency indexes and
   the entity catalog are derived here; none are stored as a second model.
   A supplied complete projection is derived from by replacement: only the
   declarations that differ from it and their reverse closure over schema
   references recompile, validate and re-index, and the result equals the
   whole build over the same inputs. A supplied declaration projection
   (no contracts) retains its compiled schema roots only when all stored forms
   and predicate bindings match; contracts still compile against the complete
   candidate registry.
   Pure at its boundary: validation uses only build-scoped coordination and
   performs no default-registry, database, or var mutation, so a build without
   a supplied projection answers equal inputs from the process cache
   (`seon.cache`, kind `:seon.schema/projection`): a build costs ~390 ms /
   ~570 MB allocated over the packaged population (perf-profile 2026-09-26),
   and concurrent callers of equal inputs share one build."
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
  ([forms function-contracts options]
   (if (:seon.schema/projection options)
     ;; A supplied base derives by replacement; its identity is not a key.
     (projection-build forms function-contracts options)
     (let [options (assoc options :seon.schema/predicate-functions
                          (predicate-bindings forms function-contracts
                                              (:seon.schema/predicate-functions options {})))]
       ;; Weighed by its measured retention per form and per contract, so a
       ;; contract-only projection weighs its contracts.
       (seon.cache/lookup ::projection [forms function-contracts options]
                          #(+ (* projection-bytes-per-form (count (:seon.schema.projection/forms %)))
                              (* projection-bytes-per-contract
                                 (count (:seon.schema.projection/function-contracts %))))
                          #(projection-build forms function-contracts options))))))

(defn- projection-fingerprint-from-data
  [projection]
  (projection-fingerprint
   (:seon.schema.projection/forms projection)
   (:seon.schema.projection/function-contracts projection)
   (:seon.schema.projection/schema-admissions projection)
   (:seon.schema.projection/function-admissions projection)
   (:seon.schema.projection/artifact-exports projection)
   (:seon.schema.projection/pure-predicate-symbols projection)))

(defn- reusable-projection-fingerprint
  [projection]
  (if (= projection-fingerprint-version
         (:seon.schema.projection/fingerprint-version projection))
    (:seon.schema.projection/fingerprint projection)
    (projection-fingerprint-from-data projection)))

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

(defn- replace-shape-entities
  [shape-entities registry schema-keys]
  (reduce
   (fn [entities schema-key]
     (if-let [entity (shape-entity-in registry schema-key)]
       (assoc entities schema-key entity)
       (dissoc entities schema-key)))
   shape-entities
   schema-keys))

(defn- function-dependents-of
  [projection schema-keys]
  (into #{}
        (mapcat
         #(get (:seon.schema.projection/reverse-function-dependencies
                projection)
               % #{}))
        schema-keys))

(defn- declaration-changes
  "The identities whose value `after` holds differently from `before`, and the
   identities `before` holds that `after` does not, as `[changed removed]`.

   Unchanged members of a projection derived from the same rows are the same
   objects, so the common comparison is `identical?`."
  {:malli/schema
   [:=> [:cat :map :map]
    [:tuple [:set [:or :keyword :symbol]] [:set [:or :keyword :symbol]]]]}
  [before after]
  (if (identical? before after)
    [#{} #{}]
    [(persistent!
      (reduce-kv (fn [changed identity value]
                   (let [prior (get before identity ::absent)]
                     (if (or (identical? prior value) (= prior value))
                       changed
                       (conj! changed identity))))
                 (transient #{}) after))
     (persistent!
      (reduce-kv (fn [removed identity _]
                   (if (contains? after identity)
                     removed
                     (conj! removed identity)))
                 (transient #{}) before))]))

(defn- set-changes
  "The members of exactly one of `before` and `after`."
  {:malli/schema [:=> [:cat [:set :symbol] [:set :symbol]] [:set :symbol]]}
  [before after]
  (if (identical? before after)
    #{}
    (into (set/difference after before) (set/difference before after))))

(defn- fingerprint-with
  "`fingerprint` with each identity's `section` entry replaced from `before`
   to `after`; a set section is a map of its members to true."
  {:malli/schema
   [:=> [:cat :int :keyword :map :map [:set [:or :keyword :symbol]]] :int]}
  [fingerprint section before after identities]
  (reduce (fn [fingerprint identity]
            (replace-fingerprint-entry
             fingerprint section identity
             (get before identity ::absent) (get after identity ::absent)))
          fingerprint identities))

(defn- shape-index-attributes
  "The attributes `schema-key`'s shape row indexes, as the whole
   [[build-projection]] derives them; empty without a row."
  {:malli/schema
   [:=> [:cat [:fn malli.registry/registry?] :map :keyword]
    [:sequential :keyword]]}
  [registry shape-entities schema-key]
  (if-let [entity (get shape-entities schema-key)]
    (vec (or (seq (:seon.schema/required-attrs entity))
             (map first (internal/entity-entries (mr/schema registry schema-key)))))
    []))

(defn- shape-projections-with
  "The shape indexes of `shape-rows`, derived from `projection`'s indexes
   by replacing only `schema-keys`' rows.

   A key's rank depends only on its own row, so every other key keeps its
   place; only the attribute vectors a replaced key leaves or enters are
   re-sorted."
  {:malli/schema
   [:=> [:cat :map [:fn malli.registry/registry?] :map [:set :keyword]] :map]}
  [projection registry shape-entities schema-keys]
  (let [old-entities (:seon.schema.projection/shape-rows projection)
        old-registry (:seon.schema.projection/registry projection)
        required-by-key
        (reduce (fn [required schema-key]
                  (if-let [entity (get shape-entities schema-key)]
                    (assoc required schema-key (:seon.schema/required-attrs entity))
                    (dissoc required schema-key)))
                (:seon.schema.projection/required-by-key projection)
                schema-keys)
        shape-rank
        (fn [schema-key]
          [(- (count (get required-by-key schema-key))) (str schema-key)])
        leaving
        (reduce (fn [index schema-key]
                  (reduce (fn [index attr]
                            (let [remaining (into [] (remove #{schema-key})
                                                  (get index attr))]
                              (if (seq remaining)
                                (assoc index attr remaining)
                                (dissoc index attr))))
                          index
                          (distinct (shape-index-attributes
                                     old-registry old-entities schema-key))))
                (:seon.schema.projection/shape-index projection)
                schema-keys)
        entering
        (into [] (mapcat (fn [schema-key]
                           (map (fn [attr] [attr schema-key])
                                (shape-index-attributes
                                 registry shape-entities schema-key))))
              (sort-by str schema-keys))
        shape-index
        (reduce (fn [index attr]
                  (update index attr #(vec (sort-by shape-rank %))))
                (reduce (fn [index [attr schema-key]]
                          (update index attr (fnil conj []) schema-key))
                        leaving entering)
                (distinct (map first entering)))
        kept (into [] (remove #(contains? schema-keys (:seon.schema/key %)))
                   (:seon.schema.projection/catalog projection))
        entities (filter :seon.schema/entity? (keep #(get shape-entities %) schema-keys))
        catalog (if (seq entities)
                  (vec (sort-by (comp str :seon.schema/key) (into kept entities)))
                  kept)]
    {:seon.schema.projection/required-by-key required-by-key
     :seon.schema.projection/shape-index shape-index
     :seon.schema.projection/catalog catalog}))

(def ^:private incremental-projection-keys
  "The members a projection needs to be derived from by replacement."
  [:seon.schema.projection/forms
   :seon.schema.projection/registry
   :seon.schema.projection/function-contracts
   :seon.schema.projection/schema-dependencies
   :seon.schema.projection/reverse-schema-dependencies
   :seon.schema.projection/function-dependencies
   :seon.schema.projection/reverse-function-dependencies
   :seon.schema.projection/shape-rows
   :seon.schema.projection/required-by-key
   :seon.schema.projection/shape-index
   :seon.schema.projection/catalog])

(defn- incremental-base?
  "True when `projection` carries every index a replacement derives from."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [projection]
  (every? #(contains? projection %) incremental-projection-keys))

(defn population-options
  "The [[build-projection]] options that reproduce `projection`'s own
   admissions, exports and predicate bindings; a replacement changes one."
  {:malli/schema [:=> [:cat :map] :map]}
  [projection]
  {:seon.schema/schema-admissions
   (get projection :seon.schema.projection/schema-admissions {})
   :seon.schema/function-admissions
   (get projection :seon.schema.projection/function-admissions {})
   :seon.schema/artifact-exports
   (get projection :seon.schema.projection/artifact-exports #{})
   :seon.schema/pure-predicate-symbols
   (get projection :seon.schema.projection/pure-predicate-symbols #{})
   :seon.schema/predicate-functions (predicate-functions-in projection)
   :seon.schema/validate-render-contracts? true})

(defn- replacement-base
  "`projection` when it carries every index a replacement reads, else the
   complete projection of its own population.

   A declaration projection ([[declaration-projection]]) holds forms and
   registry only: replacing over it read a nil contract map and no reverse
   edges, so dependents stayed compiled against the old definition and the
   shape catalog held only the changed keys. The complete base is read, not
   admitted (the declaration projection never judged admission or renderer
   coherence either), and the build is process-cached by its population, so
   it costs one build per distinct base."
  {:malli/schema [:=> [:cat :map] :map]}
  [projection]
  (if (incremental-base? projection)
    projection
    (build-projection (:seon.schema.projection/forms projection)
                      (get projection :seon.schema.projection/function-contracts {})
                      (assoc (population-options projection)
                             :seon.schema/admission? false
                             :seon.schema/validate-render-contracts? false))))

(defn- projection-with-declarations
  "The projection of `forms` and `function-contracts`, derived from `projection`
   by recompiling only what changed.

   The changed declarations and their reverse closure over schema references
   recompile, validate and re-index; every other compiled schema is Malli's
   own retained object from `projection`'s registry (`projection-registry`
   seeds the new lazy registry with them). Refs inside a retained schema
   resolve through the registry it was compiled in, which is exactly why the
   closure must include every referrer of a changed key: nothing outside it
   names a changed definition. The result equals [[build-projection]] over
   the same inputs (`seon.schema-test/an-incremental-projection-equals-its-full-build`).

   Predicate bindings are those of the population, as a build binds them: a
   binding whose symbol only a replaced or removed definition named is dropped
   after one scan of the population, which runs only when such a symbol
   exists. A changed unqualified key is a bootstrap definition every compile
   sees, so it derives the whole build instead."
  {:malli/schema
   [:=> [:cat :seon.error/operation :map :map :map :map] :map]}
  [operation projection forms function-contracts
   {:seon.schema/keys [schema-admissions function-admissions
                       artifact-exports
                       pure-predicate-symbols predicate-functions
                       validate-render-contracts? admission?]
    :or {admission? true
         schema-admissions {}
         function-admissions {}
         artifact-exports #{}
         pure-predicate-symbols #{}
         predicate-functions {}}}]
  (let [projection (replacement-base projection)
        old-forms (:seon.schema.projection/forms projection)
        old-contracts (:seon.schema.projection/function-contracts projection)
        old-registry (:seon.schema.projection/registry projection)
        [changed-keys removed-keys] (declaration-changes old-forms forms)
        [changed-fns removed-fns] (declaration-changes old-contracts function-contracts)
        old-schema-admissions (:seon.schema.projection/schema-admissions projection {})
        old-function-admissions (:seon.schema.projection/function-admissions projection {})
        old-exports (:seon.schema.projection/artifact-exports projection #{})
        old-pure (:seon.schema.projection/pure-predicate-symbols projection #{})
        schema-admission-changes
        (apply set/union (declaration-changes old-schema-admissions schema-admissions))
        function-admission-changes
        (apply set/union (declaration-changes old-function-admissions function-admissions))
        export-changes (set-changes old-exports artifact-exports)
        pure-changes (set-changes old-pure pure-predicate-symbols)
        old-predicates (predicate-functions-in projection)
        replaced-definitions
        (concat (keep #(get old-forms %) (into changed-keys removed-keys))
                (keep #(get old-contracts %) (into changed-fns removed-fns)))
        new-definitions
        (concat (map forms changed-keys) (map function-contracts changed-fns))
        new-symbols (into #{} (mapcat predicate-symbols-in) new-definitions)
        dropped-symbols
        (set/difference (into #{} (mapcat predicate-symbols-in) replaced-definitions)
                        new-symbols)
        in-use-symbols
        (when (some #(contains? old-predicates %) dropped-symbols)
          (into (into #{} (mapcat predicate-symbols-in) (vals forms))
                (mapcat predicate-symbols-in) (vals function-contracts)))
        predicate-functions
        (reduce (fn [bindings predicate]
                  (if (contains? bindings predicate)
                    bindings
                    (if-let [f (runtime-predicate predicate)]
                      (assoc bindings predicate f)
                      bindings)))
                (merge (reduce (fn [bindings predicate]
                                 (if (contains? in-use-symbols predicate)
                                   bindings
                                   (dissoc bindings predicate)))
                               old-predicates
                               (when in-use-symbols dropped-symbols))
                       predicate-functions)
                new-symbols)]
    (cond
      (and (empty? changed-keys) (empty? removed-keys)
           (empty? changed-fns) (empty? removed-fns)
           (empty? schema-admission-changes) (empty? function-admission-changes)
           (empty? export-changes)
           (empty? pure-changes) (= predicate-functions old-predicates))
      projection

      (some #(not (qualified-keyword? %)) (into changed-keys removed-keys))
      (build-projection forms function-contracts
                        {:seon.schema/schema-admissions schema-admissions
                         :seon.schema/function-admissions function-admissions
                         :seon.schema/artifact-exports artifact-exports
                         :seon.schema/pure-predicate-symbols pure-predicate-symbols
                         :seon.schema/predicate-functions predicate-functions
                         :seon.schema/validate-render-contracts? validate-render-contracts?
                         :seon.schema/admission? admission?})

      :else
      (let [canonical-keys
            (into (reduce disj
                          (or (:seon.schema.projection/canonical-keys projection)
                              (set (keys old-forms)))
                          removed-keys)
                  changed-keys)
            old-dependencies (:seon.schema.projection/schema-dependencies projection)
            schema-dependencies
            (reduce (fn [dependencies schema-key]
                      (assoc dependencies schema-key
                             (direct-reference-keys-in
                              operation schema-key (get forms schema-key)
                              predicate-functions canonical-keys old-registry)))
                    (apply dissoc old-dependencies removed-keys)
                    (sort-by str changed-keys))
            ;; A new cycle passes through a changed key; the refusal then
            ;; names it from the whole population, as a build does.
            _ (when (reference-cycle schema-dependencies changed-keys)
                (assert-acyclic-references! forms (keys forms) schema-dependencies))
            reverse-schema-dependencies
            (reduce (fn [edges schema-key]
                      (replace-reverse-dependencies
                       edges schema-key
                       (get old-dependencies schema-key #{})
                       (get schema-dependencies schema-key #{})))
                    (:seon.schema.projection/reverse-schema-dependencies projection)
                    (into changed-keys removed-keys))
            affected-keys
            (reduce disj
                    (dependent-schema-keys
                     {:seon.schema.projection/reverse-schema-dependencies
                      reverse-schema-dependencies}
                     (-> changed-keys (into removed-keys)
                         (into (filter keyword?) schema-admission-changes)))
                    removed-keys)
            affected-fns
            (reduce disj
                    (-> (function-dependents-of projection (into affected-keys removed-keys))
                        (into changed-fns)
                        (into (filter symbol?) function-admission-changes))
                    removed-fns)
            retained
            (apply dissoc (mr/schemas old-registry)
                   (concat affected-keys removed-keys affected-fns removed-fns))
            registry
            (projection-registry forms predicate-functions function-contracts retained)
            old-function-dependencies
            (:seon.schema.projection/function-dependencies projection)
            function-dependencies
            (reduce (fn [dependencies function-symbol]
                      (assoc dependencies function-symbol
                             (contract-references registry canonical-keys
                                                  (mr/schema registry function-symbol))))
                    (apply dissoc old-function-dependencies removed-fns)
                    affected-fns)
            reverse-function-dependencies
            (reduce (fn [edges function-symbol]
                      (replace-reverse-dependencies
                       edges function-symbol
                       (get old-function-dependencies function-symbol #{})
                       (get function-dependencies function-symbol #{})))
                    (:seon.schema.projection/reverse-function-dependencies projection)
                    (into affected-fns removed-fns))
            shape-keys (into affected-keys removed-keys)
            shape-entities
            (replace-shape-entities
             (apply dissoc (:seon.schema.projection/shape-rows projection) removed-keys)
             registry affected-keys)
            shape-data
            (if (= shape-entities (:seon.schema.projection/shape-rows projection))
              (select-keys projection [:seon.schema.projection/required-by-key
                                       :seon.schema.projection/shape-index
                                       :seon.schema.projection/catalog])
              (shape-projections-with projection registry shape-entities shape-keys))
            fingerprint
            (-> (reusable-projection-fingerprint projection)
                (fingerprint-with :forms old-forms forms (into changed-keys removed-keys))
                (fingerprint-with :function-contracts old-contracts function-contracts
                                  (into changed-fns removed-fns))
                (fingerprint-with :schema-admissions old-schema-admissions
                                  schema-admissions schema-admission-changes)
                (fingerprint-with :function-admissions old-function-admissions
                                  function-admissions function-admission-changes)
                (fingerprint-with :artifact-exports (zipmap old-exports (repeat true))
                                  (zipmap artifact-exports (repeat true)) export-changes)
                (fingerprint-with :pure-predicate-symbols (zipmap old-pure (repeat true))
                                  (zipmap pure-predicate-symbols (repeat true)) pure-changes))
            candidate
            (with-predicate-functions
             (merge (dissoc projection :seon.schema.projection/compiled
                            :seon.schema.projection/definition-strings)
                    {:seon.schema.projection/forms forms
                     :seon.schema.projection/registry registry
                     :seon.schema.projection/compile-options {:registry registry}
                     :seon.schema.projection/schema-admissions schema-admissions
                     :seon.schema.projection/function-admissions function-admissions
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
                     :seon.schema.projection/shape-rows shape-entities
                     :seon.schema.projection/fingerprint-version
                     projection-fingerprint-version
                     :seon.schema.projection/fingerprint fingerprint}
                    shape-data)
             predicate-functions)
            _ (when admission?
               (validate-declarations!
               {:seon.schema/forms forms
                :seon.schema/function-contracts function-contracts
                :seon.schema/compiled-schemas (mr/schemas registry)
                :seon.schema/schema-dependencies schema-dependencies
                :seon.schema/registry registry
                :seon.schema/canonical-keys canonical-keys
                :seon.schema/schema-admissions schema-admissions
                :seon.schema/function-admissions function-admissions
                :seon.schema/pure-predicate-symbols pure-predicate-symbols
                :seon.schema/predicate-functions predicate-functions
                :seon.schema/schema-keys (vec affected-keys)
                :seon.schema/function-symbols (vec affected-fns)}))]
        (when validate-render-contracts?
          ;; A renderer whose contract recompiled is checked against every
          ;; schema naming it, as a build checks every declaration.
          (assert-render-contracts!
           candidate
           (into affected-keys
                 (when (seq affected-fns)
                   (into #{}
                         (comp (filter #(contains? affected-fns (:seon.render/function %)))
                               (map :seon.schema/key))
                         (render-declarations-in candidate (keys forms)))))))
        (with-compiled-cache candidate)))))

(defn- projection-entities
  "Join attribute ranges, retaining historical values for duplicate refusal."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :qualified-keyword :qualified-keyword :boolean]
    [:vector [:tuple [:or :keyword :symbol :string] :string :int]]]}
  [database identity-attribute value-attribute identity-transaction?]
  (let [values (group-by :e (d/datoms database :aevt value-attribute))]
    (into []
          (comp
           (mapcat (fn [identity-datom]
                     (map (fn [value-datom]
                            [(:v identity-datom) (:v value-datom)
                             (:tx (if identity-transaction? identity-datom value-datom))])
                          (get values (:e identity-datom)))))
           (distinct))
          (d/datoms database :avet identity-attribute))))

(defn- projection-admissions
  "Join declaration identities to admission datoms once, without per-entity reads."
  {:malli/schema [:=> [:cat :seon.db/database-value] :map]}
  [database]
  (let [sources (into {} (map (juxt :e :v))
                      (d/datoms database :aevt :seon.schema.admission/source))]
    (into {}
          (mapcat (fn [attribute]
                    (keep (fn [datom]
                            (when-let [source (get sources (:e datom))]
                              [[attribute (:v datom)]
                               {:seon.schema.admission/source source}]))
                          (d/datoms database :avet attribute))))
          [:seon.schema/key :seon.fn/sym])))

(defn- schema-scoped-admissions
  "The admission of every schema declaration, read only for entities holding
   `:seon.schema/key`: `{[:seon.schema/key k] {:seon.schema.admission/source s}}`.

   Slice 1 lands it unused (contracts-out design §3, N5.1): while function
   contracts are still projection inputs, [[projection-admissions]] must keep
   reading function rows too. Slice 4 switches the loader and the content key
   to this reader together with the contract range's removal."
  {:malli/schema [:=> [:cat :seon.db/database-value] :map]}
  [database]
  (into {}
        (keep (fn [datom]
                (when-let [source (:v (first (d/datoms database :eavt (:e datom)
                                                         :seon.schema.admission/source)))]
                  [[:seon.schema/key (:v datom)] {:seon.schema.admission/source source}])))
        (d/datoms database :avet :seon.schema/key)))

(defn- deleted-predicate-functions
  "Refusing callables for the predicates stored rows name but the loaded files deleted.

  A stored commit (the published source a resume diffs, a cluster that
  retains its program) may name a predicate whose namespace is loaded but no
  longer defines it. Those rows stay readable: each such predicate compiles
  to a callable that refuses by name when a value is validated against it,
  and its namespace is never reloaded to look for it. A predicate of an
  unloaded namespace keeps the ordinary resolution."
  {:malli/schema [:=> [:cat :map :map] [:map-of :qualified-symbol [:fn clojure.core/ifn?]]]}
  [forms function-contracts]
  (into {}
        (comp (filter #(and (vector? %) (= :fn (first %))))
              (map #(get % (if (map? (second %)) 2 1)))
              (filter qualified-symbol?)
              (distinct)
              (filter #(and (find-ns (symbol (namespace %)))
                            (nil? (loaded-predicate-var %))))
              (map (fn [predicate]
                     [predicate
                      (fn [value]
                        (throw
                         (ex-info (str "Predicate " predicate
                                       " was deleted from its loaded namespace; a stored"
                                       " declaration still names it.")
                                  {:seon.error/at (java.util.Date.)
                                   :seon.error/layer :seon.schema/compilation
                                   :seon.error/operation 'seon.schema/projection-from-entities
                                   :seon.error/message "A stored declaration names a deleted predicate."
                                   :seon.schema/error :seon.schema/unresolved-predicate
                                   :seon.schema/unresolved-predicate predicate
                                   :seon.error/offending value})))])))
        (tree-seq coll? seq [(vals forms) (vals function-contracts)])))

(defn projection-from-entities
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
   (projection-from-entities projection-input {}))
  ([{:seon.schema/keys [database-value schema-rows function-contract-rows
                        artifact-exports
                        pure-predicate-symbols]
     :or {artifact-exports #{}
          pure-predicate-symbols #{}}}
    reusable-projection]
   (let [recorded-admissions
         (if (contains? (dbi/-schema database-value) :seon.schema.admission/source)
           (projection-admissions database-value)
           {})
         prior-strings
         (get reusable-projection :seon.schema.projection/definition-strings {})
         admission-for-identity
         (fn [attribute identity]
           (get recorded-admissions [attribute identity]
                {:seon.schema.admission/source :agent
                 :seon.schema.admission/note
                 "The supplied database has no admission provenance for this identity."}))]
     (letfn [(parse-tuples [tuples identity-fn identity-label identity-attribute
                          prior-definitions]
            (reduce
              (fn [parsed tuple]
                (when-not (and (sequential? tuple)
                               (= 3 (count tuple)))
                  (throw (ex-info (str "Malformed committed " identity-label
                                       " tuple.")
                                  {:seon.schema/error
                                   :seon.schema/malformed-projection-row
                                   :seon.schema/row tuple
                                   :seon.schema/malformed-projection-row true})))
                (let [[raw-identity form-string _asserting-tx-eid] tuple
                      identity (identity-fn raw-identity)]
                  (when-not (string? form-string)
                    (throw (ex-info (str "Malformed committed " identity-label
                                         " form.")
                                    {:seon.schema/error
                                     :seon.schema/malformed-projection-form
                                     :seon.schema/row tuple
                                     :seon.schema/malformed-projection-form true})))
                  (when (contains? parsed identity)
                    (throw (ex-info (str "Duplicate committed " identity-label
                                         " " identity ".")
                                    {:seon.schema/error
                                     :seon.schema/duplicate-projection-row
                                     :seon.schema/duplicate-projection-row identity
                                     :seon.schema/identity identity
                                     })))
                  (assoc parsed identity
                         {:seon.schema.parsed/form
                          ;; The reusable projection's form for text it
                          ;; parsed; only this function records that text.
                          (if (= form-string (get prior-strings identity))
                            (get prior-definitions identity)
                            (edn/read-string form-string))
                          :seon.schema.parsed/string form-string
                          :seon.schema.parsed/admission
                          (admission-for-identity identity-attribute raw-identity)})))
              {}
              tuples))]
    (let [schemas
          (parse-tuples schema-rows
                  (fn [identity]
                    (if (keyword? identity)
                      identity
                      (throw (ex-info "Committed schema identity is not a keyword."
                                      {:seon.schema/error
                                       :seon.schema/malformed-projection-identity
                                       :seon.schema/identity identity
                                       :seon.schema/malformed-projection-identity true}))))
                  "schema" :seon.schema/key
                  (:seon.schema.projection/forms reusable-projection {}))
          contracts
          (parse-tuples function-contract-rows
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
                                           :seon.schema/malformed-projection-identity true}))))
                      :else
                      (throw (ex-info "Committed function identity is malformed."
                                      {:seon.schema/error
                                       :seon.schema/malformed-projection-identity
                                       :seon.schema/identity identity
                                       :seon.schema/malformed-projection-identity true}))))
                   "function contract" :seon.fn/sym
                   (:seon.schema.projection/function-contracts reusable-projection {}))
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
                                        :seon.schema/malformed-artifact-export true}))))
                         :else
                         (throw
                          (ex-info "Artifact export is malformed."
                                   {:seon.schema/error
                                    :seon.schema/malformed-artifact-export
                                    :seon.schema/export export
                                    :seon.schema/malformed-artifact-export true})))))
                artifact-exports)
          forms
          (into {} (map (fn [[k entity]]
                          [k (:seon.schema.parsed/form entity)]))
                schemas)
          function-contracts
          (into {} (map (fn [[k entity]]
                          [k (:seon.schema.parsed/form entity)]))
                contracts)
          schema-admissions
          (into {} (map (fn [[k entity]]
                          [k (:seon.schema.parsed/admission entity)]))
                schemas)
          function-admissions
          (into {} (map (fn [[k entity]]
                          [k (:seon.schema.parsed/admission entity)]))
                contracts)
          ;; A complete reusable projection answers by replacement (it is
          ;; returned itself when nothing differs), so the population-wide
          ;; fingerprint is computed only for a partial one.
          fingerprint
          (when (and (:seon.schema.projection/fingerprint reusable-projection)
                     (not (incremental-base? reusable-projection)))
            (projection-fingerprint
             forms function-contracts schema-admissions function-admissions
             artifact-exports pure-predicate-symbols))
          projection
          (if (and fingerprint
                   (= fingerprint
                      (:seon.schema.projection/fingerprint reusable-projection)))
            reusable-projection
            (build-projection
             forms
             function-contracts
             {:seon.schema/schema-admissions schema-admissions
              :seon.schema/projection reusable-projection
              :seon.schema/function-admissions function-admissions
              :seon.schema/artifact-exports artifact-exports
              :seon.schema/pure-predicate-symbols pure-predicate-symbols
              :seon.schema/predicate-functions
              (deleted-predicate-functions forms function-contracts)
              ;; Reading committed entities re-admits nothing: completeness and
              ;; renderer coherence were judged when the entities were written
              ;; (`projection-with-schema` and the other replacements,
              ;; publication's build). A historical commit opens as its own
              ;; entities, under the rules that admitted it, not today's.
              :seon.schema/validate-render-contracts? false
              :seon.schema/admission? false}))
          ;; The entity text each form was read from, so the next derivation
          ;; from this projection reads only entities whose text differs. A
          ;; runtime member: every other constructor drops it, so it never
          ;; outlives its forms.
          definition-strings
          (persistent!
           (reduce-kv (fn [strings identity entity]
                        (assoc! strings identity (:seon.schema.parsed/string entity)))
                      (transient {})
                      (merge schemas contracts)))]
      (if (= definition-strings
             (:seon.schema.projection/definition-strings projection))
        projection
        (assoc projection :seon.schema.projection/definition-strings
               definition-strings)))))))

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
    :seon.schema/validation-refusal]}
  [db]
  (vary-meta (seon.error.refusal/diagnostic (java.util.Date.) :seon.schema/derivation 'seon.schema/refuse-projection-source
   {:seon.schema/refused-value db
    :seon.schema/expected-value :seon.db/database-value
    :seon.error/message "The program projection requires a Datahike database value; a projection with no forms is never derived from one that is not."
    :seon.error/member :seon.schema/database-value
    :seon.error/data {:seon.schema/database-value db}}) assoc :seon.error/declared-schema :seon.schema/validation-refusal))

(def projection-ranges
  "The declaration ranges `load-projection` reads, as [identity value identity-tx?].

  One definition serves the loader and every consumer that must know what a
  projection depends on (`seon.db`'s memo key), so a new range cannot be read
  without also invalidating."
  {:seon.schema/schema-rows [:seon.schema/key :seon.schema/form true]
   :seon.schema/function-contract-rows [:seon.fn/sym :seon.fn/spec false]})

(def projection-attributes
  "Every attribute a projection derivation reads: its ranges plus admissions."
  (into [:seon.schema.admission/source]
        (comp (mapcat (fn [[identity value _]] [identity value])) (distinct))
        (vals projection-ranges)))

(defn load-projection
  "Derive the complete projection from this database value's declaration rows.

  With `base`, a projection of any other value, the result is derived from it
  by replacement ([[build-projection]]): only declarations whose rows differ
  and their reverse closure recompile. Any base gives the same result; a near
  one gives it in time proportional to the difference."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value] ::projection]
    [:=> [:cat :seon.db/database-value ::projection] ::projection]]}
  ([db] (load-projection db {}))
  ([db base]
   (when-not (db-utils/db? db)
     (let [refusal (refuse-projection-source db)]
       (throw (ex-info (:seon.error/message refusal) refusal))))
   (projection-from-entities
    (reduce-kv (fn [input member [identity value identity-tx?]]
                 (assoc input member (projection-entities db identity value identity-tx?)))
               {:seon.schema/database-value db
                :seon.schema/artifact-exports #{}
                :seon.schema/pure-predicate-symbols #{}}
               projection-ranges)
    base)))

(defonce ^:private database-projection
  (delay (requiring-resolve 'seon.db/carried-projection)))

(defn projection-from-database
  "Read the projection derived from this database value, memoized when committed.

  The second arity remains for existing constructors; its formerly reusable
  projection cannot override the supplied database's declaration rows."
  {:malli/schema
   [:function
    [:=> [:catn [:seon.schema/database-value :seon.db/database-value]] ::projection]
    [:=> [:catn [:seon.schema/database-value :seon.db/database-value]
                 [::projection ::projection]] ::projection]]}
  ([db] (@database-projection db))
  ([db _reusable-projection] (@database-projection db)))

(defn projection-with-schema
  "Validate the projection produced by exactly one schema replacement.

  This is an admission: a changed definition also meets the authoring rules
  `register!` applies, which a projection build never re-judges."
  {:malli/schema
   [:=> [:catn [::projection ::projection]
                [::registry-key ::registry-key]
                [::definition ::definition]
                [:seon.schema/admission :map]]
    ::projection]}
  [projection schema-key definition admission]
  (when (not= definition (get-in projection [:seon.schema.projection/forms schema-key] ::absent))
    (internal/assert-non-nilable-value-schema!
     (:seon.schema.projection/forms projection) schema-key (structural-schema definition))
    (assert-config-display! {schema-key definition}))
  (projection-with-declarations
   'seon.schema/projection-with-schema projection
   (assoc (:seon.schema.projection/forms projection) schema-key definition)
   (get projection :seon.schema.projection/function-contracts {})
   (assoc (population-options projection)
          :seon.schema/schema-admissions
          (assoc (:seon.schema.projection/schema-admissions projection)
                 schema-key admission))))

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
    :seon.schema/validation-refusal]}
  [schema-key selector-element cause message]
  (vary-meta (seon.error.refusal/diagnostic (java.util.Date.) :seon.schema/derivation 'seon.schema/pulled-selector-refusal
   {:seon.schema/refused-value selector-element
    :seon.schema/expected-value :seon.schema/pull-selector-element
    :seon.error/message message
    :seon.error/data {:seon.schema/key schema-key
     :seon.schema/pull-selector-element selector-element}}) assoc :seon.error/declared-schema :seon.schema/validation-refusal))

(defn- entity-entry-map
  "Map one entity schema's attributes to their Malli map entries."
  {:malli/schema
   [:=> [:cat ::projection [:or :nil ::registry-key]]
    [:map-of :qualified-keyword :seon.schema/value]]}
  [projection schema-key]
  (if-let [compiled (mr/schema (:seon.schema.projection/registry projection) schema-key)]
    (into {} (map (fn [entry] [(first entry) entry]))
          (internal/entity-entries compiled))
    {}))

(defn- reverse-target-schema
  "The sole entity schema declaring `attribute`, or nil when ambiguous."
  {:malli/schema
   [:=> [:cat ::projection :qualified-keyword]
    [:or :nil ::registry-key]]}
  [projection attribute]
  (let [entities (:seon.schema.projection/shape-rows projection)
        candidates
        (->> (get-in projection
                     [:seon.schema.projection/shape-index attribute])
             (filter #(true? (:seon.schema/entity? (get entities %)))))]
    (when (= 1 (count candidates)) (first candidates))))

(defn- selector-target-schema
  "The sole entity schema identified by a sub-selector's identity attribute."
  {:malli/schema
   [:=> [:cat ::projection :seon.schema/parsed-pull-spec]
    [:or :nil ::registry-key]]}
  [projection pull-spec]
  (let [registry (:seon.schema.projection/registry projection)
        candidates
        (into #{}
              (keep (fn [[_ options]]
                      (some-> (mr/schema registry (:attr options))
                              m/properties
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
                    (boolean (#{:set :vector :sequential}
                              (m/type (@schema-datahike-storage-schema
                                       (or (mr/schema (:seon.schema.projection/registry projection) attribute)
                                           (structural-schema attribute-form))))))
                    many? (if forward? declared-many? (not component?))
                    component-schema
                    (:seon.db/component-schema
                     (some-> (mr/schema (:seon.schema.projection/registry projection) attribute) m/properties))
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
                      (m/form (@schema-datahike-value-schema
                               (or (mr/schema (:seon.schema.projection/registry projection) attribute)
                                   (structural-schema attribute-form)))))]
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
    (if (and (map? projected) (:seon.schema/expected-value projected))
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
               ))))
    (projection-with-declarations
     'seon.schema/projection-without-schema projection
     (dissoc (:seon.schema.projection/forms projection) schema-key)
     (get projection :seon.schema.projection/function-contracts {})
     (assoc (population-options projection)
            :seon.schema/schema-admissions
            (dissoc (:seon.schema.projection/schema-admissions projection)
                    schema-key)))))

(defn projection-with-function-contract
  "Validate the projection produced by one function-contract replacement."
  {:malli/schema
   [:=> [:catn [::projection ::projection]
                [:seon.schema/function-symbol :qualified-symbol]
                [::definition ::definition]
                [:seon.schema/admission :map]]
    ::projection]}
  [projection function-symbol definition admission]
  (projection-with-declarations
   'seon.schema/projection-with-function-contract projection
   (:seon.schema.projection/forms projection)
   (assoc (get projection :seon.schema.projection/function-contracts {})
          function-symbol definition)
   (-> (population-options projection)
       (update :seon.schema/function-admissions assoc function-symbol admission))))

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

(defn canonical-schema-entities
  "Build canonical rows from a complete construction generation.
  The two-argument arity selects authored rows while reference and storage
  decisions use the complete supplied projection. Missing targets still refuse."
  {:malli/schema
   [:function
    [:=> [:cat] [:vector :map]]
    [:=> [:cat :map] [:vector :map]]
    [:=> [:cat ::projection :map] [:vector :map]]]}
  ([] (canonical-schema-entities (registered-schemas)))
  ([forms] (canonical-schema-entities (build-projection forms) forms))
  ([projection forms]
   (let [materialized-keys (into #{} (filter keyword?) (keys forms))
         reference-graph (:seon.schema.projection/schema-dependencies projection)
         ordering-graph (update-vals reference-graph #(set/intersection materialized-keys %))]
     (mapv
      (fn [schema-key]
        (let [references (get reference-graph schema-key)]
          (let [entity
                (cond->
                 (merge (@schema-datahike-storable-properties-in projection schema-key)
                        {:seon.schema/key schema-key
                         :seon.schema/form
                         (binding [*print-namespace-maps* false] (pr-str (get forms schema-key)))
                         :seon.schema.admission/source :core})
                  (seq references) (assoc :seon.schema/references (set references)))]
            (assoc entity :seon.program/definition-digest
                   ((requiring-resolve 'seon.program/definition-digest) entity)))))
      (dependency-first-schema-keys ordering-graph materialized-keys)))))

(defn canonical-database-attributes
  "Compute the complete production database-attribute population.

   Entity-map entries are attributes by construction. Standalone registered
   forms join that population only when they carry a persistence property."
  {:malli/schema [:=> [:cat ::projection] [:vector :qualified-keyword]]}
  [projection]
  (@schema-datahike-database-attributes-in projection))

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

(declare projection-validator projection-explainer)

(defn valid-candidate-value?
  "True when value satisfies schema-key in the supplied generation."
  {:malli/schema [:=> [:cat ::projection ::registry-key ::value] :boolean]}
  [projection schema-key value]
  ((projection-validator projection schema-key) value))

(defn explain-candidate-value
  "Native Malli explanation in the supplied generation; nil means valid."
  {:malli/schema [:=> [:cat ::projection ::registry-key ::value]
                  [:maybe ::explanation]]}
  [projection schema-key value]
  ((projection-explainer projection schema-key) value))

(def ^:const shape-candidate-limit
  "Maximum schema entities examined and returned for structural diagnostics."
  32)

(def ^:const shape-input-key-limit
  "Maximum map entries examined for structural diagnostics."
  32)

(def ^:dynamic *candidate-visit!*
  "Optional test instrumentation called once per diagnostic schema visit."
  (fn [_schema-key] nil))

(defn projection-validator
  "Acquire the retained schema's Malli-owned validator."
  {:malli/schema [:=> [:catn [::projection ::projection]
                             [::registry-key ::registry-key]]
                  ::compiled-validator]}
  [projection schema-key]
  (if-let [compiled (mr/schema (:seon.schema.projection/registry projection)
                              schema-key)]
    (m/validator compiled)
    (throw (ex-info (str "Missing schema declaration " schema-key ".")
                    {:seon.schema/invalid-schema schema-key
                     :seon.schema/key schema-key
                     :seon.schema/missing-reference schema-key
                     :seon.schema/missing-reference-namespace (namespace schema-key)}))))

(defn- function-arities-in [projection function-symbol]
  (projection-cache-value
   projection [::function-arities function-symbol]
   (fn []
     (if-let [compiled
              (mr/schema (:seon.schema.projection/registry projection)
                         function-symbol)]
       (compiled-function-arities compiled)
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
  ;; An undeclared symbol has no arities (`function-arities-in` answers []);
  ;; nothing is parsed, so a failing validator propagates.
  (boolean (seq (function-matching-outputs-in
                 projection function-symbol arguments))))

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
  ;; An undeclared symbol has no arities (`function-arities-in` answers []);
  ;; nothing is parsed, so a failing validator propagates.
  (boolean (some (fn [[_ output]] (= output-schema output))
                 (function-arities-in projection function-symbol))))

(defn function-accepts-and-returns-in?
  "True when one arity accepts `arguments` and declares `output-schema`."
  {:malli/schema
   [:=> [:catn [::projection ::projection]
         [::function-symbol :qualified-symbol]
         [::arguments :seon.schema/arguments]
         [::output-schema ::registry-key]]
    :boolean]}
  [projection function-symbol arguments output-schema]
  ;; An undeclared symbol has no arities (`function-arities-in` answers []);
  ;; nothing is parsed, so a failing validator propagates.
  (boolean (some #{output-schema}
                 (function-matching-outputs-in
                  projection function-symbol arguments))))

(defn projection-explainer
  "Acquire the retained schema's Malli-owned explainer, with native paths."
  {:malli/schema [:=> [:catn [::projection ::projection]
                             [::registry-key ::registry-key]]
                  ::compiled-validator]}
  [projection schema-key]
  (if-let [compiled (mr/schema (:seon.schema.projection/registry projection)
                              schema-key)]
    (m/explainer compiled)
    (throw (ex-info (str "Missing schema declaration " schema-key ".")
                    {:seon.schema/invalid-schema schema-key
                     :seon.schema/key schema-key
                     :seon.schema/missing-reference schema-key
                     :seon.schema/missing-reference-namespace (namespace schema-key)}))))

(defn- shape-projection
  {:malli/schema [:=> [:cat] :seon.schema/projection]}
  []
  (or (handed-projection)
      (throw
       (ex-info "Shape inspection requires the operation's schema projection."
                {:seon.schema/missing-projection true}))))

(defn- identity-only-descriptors-in
  {:malli/schema [:=> [:cat :seon.schema/projection] [:vector [:map [:seon.schema/key :keyword] [:seon.schema.identity-only/validator :seon.schema/compiled-validator] [:seon.schema/identity-projection [:fn clojure.core/var?]]]]]}
  [projection]
  (into
   []
   (keep
    (fn [[schema-key _definition]]
      (let [properties (m/properties (mr/schema (:seon.schema.projection/registry projection) schema-key))]
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
                       })))
                projection-var (requiring-resolve projection-symbol)]
            (when-not (ifn? (var-get projection-var))
              (throw
               (ex-info
                (str schema-key " declares identity-only admission without "
                     "a callable identity projection.")
                {:seon.schema/key schema-key
                 :seon.schema/identity-projection projection-symbol
                 :seon.schema/invalid-identity-projection true
                 })))
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
  (projection-cache-value projection :seon.schema.identity-only/descriptors
                          #(identity-only-descriptors-in projection)))

(defn identity-only-projector
  "One function of a value to its registered identity data, or nil.

  Derive it once per walk: value admission asks at every node, and asking
  through the armed `identity-only-projection-in` Var cost ~2.9 KB per node."
  {:malli/schema [:=> [:cat :seon.schema/projection] :seon.instrument/callable]}
  [projection]
  (let [descriptors (identity-only-descriptors projection)]
    (fn [value]
      (some
       (fn [{:seon.schema/keys [key identity-projection]
             validator :seon.schema.identity-only/validator}]
         (when (validator value)
           {:seon.schema/key key
            :seon.schema/identity-value (identity-projection value)}))
       descriptors))))

(defn identity-only-projection-in
  "Project a registered reference value to its declared identity data."
  {:seon.fn/invokes #{:seon.schema/identity-projection}
   :malli/schema
   [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:maybe :map]]}
  [projection value]
  ((identity-only-projector projection) value))

(defn projection-value?
  "True for a schema projection: a map holding its registry and declaration forms."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (map? value)
       (contains? value :seon.schema.projection/registry)
       (contains? value :seon.schema.projection/forms)))

(register-core-predicate! 'seon.schema/projection-value? projection-value?)

(defn projection-identity
  "A projection is referenced by its content fingerprint, never copied: its
  registry, forms and indexes are megabytes derived from the database."
  {:malli/schema [:=> [:cat :seon.schema/projection-reference] :map]}
  [projection]
  ;; A declaration projection carries no stored fingerprint; its content
  ;; fingerprint is derived once and kept on the projection's own holder.
  {:seon.schema.projection/fingerprint
   (or (:seon.schema.projection/fingerprint projection)
       (projection-cache-value projection ::fingerprint
                               #(projection-fingerprint-from-data projection)))
   :seon.schema.projection/fingerprint-version projection-fingerprint-version})

(defn identity-only-projection
  "Project a registered reference value using the active schema registry."
  {:malli/schema [:=> [:catn [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:maybe :map]]}
  [value]
  (identity-only-projection-in (shape-projection) value))

(defn- shape-rank [entity]
  [(- (count (:seon.schema/required-attrs entity)))
   (str (:seon.schema/key entity))])

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
    (let [entities (:seon.schema.projection/shape-rows projection)]
      (->> (diagnostic-schema-keys projection attrs)
           (map entities)
           (sort-by shape-rank)
           vec))
    []))

(defn matching-shapes-in
  "All schemas in explicit `projection` that validate `value`."
  {:malli/schema [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/value [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Schema discovery and explanation inspect arbitrary candidate values, including scalars, nil and host objects; the supplied validators decide whether they match.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:vector :map]]}
  [projection value]
  (if-let [attrs (seq (complete-present-attrs value))]
    (let [index (:seon.schema.projection/shape-index projection)
          entities (:seon.schema.projection/shape-rows projection)
          present (set attrs)
          possible (into #{} (mapcat #(get index % [])) attrs)]
      (->> possible
           (map entities)
           (filter (fn [entity]
                     (every? present (:seon.schema/required-attrs entity))))
           (sort-by shape-rank)
           (filter (fn [{:seon.schema/keys [key]}]
                      ((projection-validator projection key) value)))
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

;;; ---------------------------------------------------------------------------
;;; Function contracts compiled against a shape projection (contracts-out §1)
;;; ---------------------------------------------------------------------------

(defn- predicate-callable
  "The callable `predicate` names: the projection's binding, else the loaded Var's value."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value] [:or :nil [:fn clojure.core/ifn?]]]}
  [projection predicate]
  (or (get (predicate-functions-in projection) predicate)
      (when (qualified-symbol? predicate)
        (some-> predicate requiring-resolve deref))))

(defn bind-contract-predicates
  "Bind a normalized contract's named predicates and `:error/fn` symbols to callables.

   Moved from `seon.instrument` (contracts-out §1): the armed compile binds
   after [[compilable-form]] normalizes, exactly as the instrument caller did.
   Malli's code evaluator is never opened. An unresolved predicate refuses by name."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value] :seon.schema/value]}
  [projection contract]
  (walk/postwalk
   (fn [value]
     (cond
       (and (map? value) (qualified-symbol? (:error/fn value)))
       (assoc value :error/fn (predicate-callable projection (:error/fn value)))

       (and (vector? value) (= :fn (first value)))
       (let [predicate-index (if (map? (second value)) 2 1)
             predicate (get value predicate-index)
             callable (when (symbol? predicate)
                        (predicate-callable projection predicate))]
         (cond
           (and (ifn? predicate) (not (symbol? predicate))) value
           (ifn? callable) (assoc value predicate-index callable)
           :else
           (throw
            (ex-info
             (str "Contract predicate " (pr-str predicate)
                  " has no active callable.")
             {:seon.error/at (java.util.Date.)
              :seon.error/layer :seon.instrument/registration
              :seon.error/operation 'seon.schema/bind-contract-predicates
              :seon.schema/unresolved-predicate predicate
              :seon.error/message "The schema predicate is unresolved."
              :seon.schema/predicate predicate}))))
       :else value))
   contract))

(defn contract-product
  "Compile one authored function contract against `projection` and derive its references.

   Symbol-free: the product carries the compiled contract and, per Malli arity,
   its `[input output guard]` canonical references derived in the SAME world
   ([[arity-references]]), so every consumer takes both together and never
   re-walks. `:shape` compiles the normalized form ([[compilable-form]]) in the
   projection registry, as the projection itself compiles contracts; `:armed`
   also binds predicates and `:error/fn` ([[bind-contract-predicates]]) and
   compiles in the registry composed with Malli's var registry, as arming does.
   A host form holding a compiled schema keeps that schema's own options.

   ## Costs

   One Malli compile and one reference walk; nothing is retained here
   (retention is the population table, §10, disabled until slice 4)."
  {:malli/schema [:=> [:cat :seon.schema/projection
                       [:map [:seon.schema/form :seon.schema/value]
                        [:seon.schema/mode [:enum :shape :armed]]]]
                  [:map [:seon.schema/compiled [:fn malli.core/schema?]]
                   [:seon.schema/references
                    [:vector [:tuple [:set :keyword] [:set :keyword] [:set :keyword]]]]]]}
  [projection {form :seon.schema/form mode :seon.schema/mode}]
  (let [registry (:seon.schema.projection/registry projection)
        normalized (compilable-form form (predicate-functions-in projection))
        compiled (case mode
                   :shape (m/function-schema normalized {:registry registry})
                   :armed (m/schema (bind-contract-predicates projection normalized)
                                    {:registry (mr/composite-registry registry (mr/var-registry))}))]
    {:seon.schema/compiled compiled
     :seon.schema/references
     (arity-references (:registry (m/options compiled))
                       (or (:seon.schema.projection/canonical-keys projection)
                           (set (keys (:seon.schema.projection/forms projection))))
                       compiled)}))

(defn function-contract
  "Admit one authored function contract against `projection`: its product
   ([[contract-product]]) after the same completeness rules a projection
   applies to a contract ([[assert-complete-contract!]]), or the refusal those
   rules throw. `identity` names the refused declaration only; it is no input
   of the product."
  {:malli/schema [:=> [:cat :seon.schema/projection
                       [:map [:seon.schema/form :seon.schema/value]
                        [:seon.schema/mode [:enum :shape :armed]]
                        [:seon.schema/admission :map]
                        [:seon.schema/identity :qualified-symbol]]]
                  [:map [:seon.schema/compiled [:fn malli.core/schema?]]
                   [:seon.schema/references
                    [:vector [:tuple [:set :keyword] [:set :keyword] [:set :keyword]]]]]]}
  [projection {form :seon.schema/form admission :seon.schema/admission
               identity :seon.schema/identity :as request}]
  (let [product (contract-product projection request)
        registry (:seon.schema.projection/registry projection)
        forms (:seon.schema.projection/forms projection)]
    (assert-complete-contract!
     {:seon.schema/identity identity
      :seon.schema/definition form
      :seon.schema/direct-predicate-symbols (predicate-symbols-in form)
      :seon.schema/compiled (:seon.schema/compiled product)
      :seon.schema/admission admission
      :seon.schema/forms forms
      :seon.schema/compiled-schemas (mr/schemas registry)
      :seon.schema/schema-dependencies (:seon.schema.projection/schema-dependencies projection {})
      :seon.schema/registry registry
      :seon.schema/compile-options {:registry registry}
      :seon.schema/canonical-keys (set (keys forms))
      :seon.schema/admissions (:seon.schema.projection/schema-admissions projection {})
      :seon.schema/pure-predicate-symbols (:seon.schema.projection/pure-predicate-symbols projection #{})
      :seon.schema/predicate-functions (predicate-functions-in projection)})
    product))

(defn admit-declarations!
  "Admit declarations a write changed, judged by the projection the write leaves.

   The write-side half of reading without re-admission: [[projection-from-rows]]
   derives a committed value's projection without its completeness and
   renderer-coherence rules, so the writer (`seon.db/validate-write-report`)
   applies them here to exactly what its report changed. `schema-keys` and
   `function-symbols` are the changed identities present in `projection`; the
   reverse schema closure and the contracts depending on it are judged too, as
   a replacement judges them, and every schema naming a changed renderer is
   rechecked. Throws the same refusals a replacement throws; nil when admitted."
  {:malli/schema [:=> [:cat :seon.schema/projection [:set :keyword] [:set :qualified-symbol]] :nil]}
  [projection schema-keys function-symbols]
  (let [forms (:seon.schema.projection/forms projection)
        contracts (get projection :seon.schema.projection/function-contracts {})
        registry (:seon.schema.projection/registry projection)
        schema-keys (into #{} (filter #(contains? forms %)) schema-keys)
        affected-keys (dependent-schema-keys projection schema-keys)
        affected-fns (into (into #{} (filter #(contains? contracts %)) function-symbols)
                           (function-dependents-of projection affected-keys))]
    (validate-declarations!
     {:seon.schema/forms forms
      :seon.schema/function-contracts contracts
      :seon.schema/compiled-schemas (mr/schemas registry)
      :seon.schema/schema-dependencies (:seon.schema.projection/schema-dependencies projection {})
      :seon.schema/registry registry
      :seon.schema/canonical-keys (or (:seon.schema.projection/canonical-keys projection) (set (keys forms)))
      :seon.schema/schema-admissions (:seon.schema.projection/schema-admissions projection {})
      :seon.schema/function-admissions (:seon.schema.projection/function-admissions projection {})
      :seon.schema/pure-predicate-symbols (:seon.schema.projection/pure-predicate-symbols projection #{})
      :seon.schema/predicate-functions (predicate-functions-in projection)
      :seon.schema/schema-keys (vec affected-keys)
      :seon.schema/function-symbols (vec affected-fns)})
    (assert-render-contracts!
     projection
     (into affected-keys
           (when (seq function-symbols)
             (into #{}
                   (comp (filter #(contains? function-symbols (:seon.render/function %)))
                         (map :seon.schema/key))
                   (render-declarations-in projection (keys forms))))))
    nil))
