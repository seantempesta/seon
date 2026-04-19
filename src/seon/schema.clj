(ns seon.schema
  "Global Malli schema registry for Seon.

   Provides a centralized, mutable registry for domain schemas. All namespaces
   register their schemas here using `register!`, making them available for:
   - Function schema validation via `m/=>` or `:malli/schema` metadata
   - Generative testing via `malli.generator`
   - Runtime validation via `malli.core/validate`

   Usage:
     (require '[seon.schema :as schema])

     ;; Register schemas using auto-namespaced keywords
     (schema/register! ::user-id :uuid)
     (schema/register! ::user-name [:string {:min 1 :max 200}])

     ;; Or register multiple at once
     (schema/register-all!
       ::order-id :uuid
       ::order-total [:double {:min 0}])

   The `::` syntax expands to the current namespace, so `::user-id` in
   `seon.trading.core` becomes `:seon.trading.core/user-id`.

   This design makes it easy for LLM agents to:
   - Parse individual `register!` forms
   - Update single schema definitions
   - Track which schemas are defined in which namespace"
  (:require [malli.core :as m]
            [malli.registry :as mr]))

;;; ---------------------------------------------------------------------------
;;; Registry Setup
;;; ---------------------------------------------------------------------------

;; Atom holding all registered domain schemas.
;; Use `defonce` to survive namespace reloads.
(defonce ^:private *schemas (atom {}))

;; Initialize the global registry once at load time.
;; Combines Malli's default schemas with our mutable registry.
(defonce ^:private _registry-init
  (mr/set-default-registry!
   (mr/composite-registry
    (m/default-schemas)
    (mr/mutable-registry *schemas))))

;; Register :inst as a keyword type (Malli only provides inst? predicate).
;; This lets schemas use :inst instead of inst? for consistency with :string, :int, etc.
(defonce ^:private _inst-type
  (swap! *schemas assoc :inst (m/-simple-schema {:type :inst :pred inst?})))

;; Register :seon.flow/dynamic — a wire protocol field validated dynamically.
;; Used for ::args, ::value, and ::payload in flow message envelopes.
;; These fields carry data whose type depends on the target function or
;; payload key, so static schema can only assert non-nil. Real validation
;; happens at the message boundary via validate-fn-args!, validate-fn-value!,
;; and validate-payload! in seon.flow.msg.
(defonce ^:private _dynamic-type
  (swap! *schemas assoc :seon.flow/dynamic
         (m/-simple-schema
          {:type :seon.flow/dynamic
           :pred some?
           :type-properties {:gen/schema [:or :int :string :keyword :boolean
                                          [:vector :int] [:map-of :keyword :string]]
                             :gen/fmap identity}})))

;; Register :seon.db/ref — a Datalevin entity reference.
;; Accepts positive integers (entity IDs) or lookup refs [keyword value].
;; At transact time, Datalevin resolves lookup refs to entity IDs automatically.
;; The stored form is always a long (entity ID).
;; Generator produces both forms for testing; pipeline tests for ref attrs
;; need manual handling (target entities must exist first).
(defonce ^:private _ref-type
  (swap! *schemas assoc :seon.db/ref
         (m/-simple-schema
          {:type :seon.db/ref
           :pred (fn [x]
                   (or (pos-int? x)
                       (and (vector? x) (= 2 (count x)) (keyword? (first x)))))
           :type-properties {:gen/schema [:or
                                          [:int {:min 1}]
                                          [:tuple :keyword :string]]
                             :gen/fmap identity}})))

;;; ---------------------------------------------------------------------------
;;; Registration API
;;; ---------------------------------------------------------------------------

(defn register!
  "Register a single schema in the global registry.

   Arguments:
     k - Schema keyword (use `::name` for auto-namespacing)
     v - Malli schema definition

   Returns:
     The registered schema keyword.

   Example:
     (register! ::api-key [:string {:min 1}])
     (register! ::timeout [:int {:min 1000 :max 600000}])"
  [k v]
  (swap! *schemas assoc k v)
  k)

(defn register-all!
  "Register multiple schemas at once.

   Arguments:
     Pairs of keyword and schema definition.

   Returns:
     Set of registered schema keywords.

   Throws:
     AssertionError if odd number of arguments provided.

   Example:
     (register-all!
       ::user-id :uuid
       ::user-name [:string {:min 1}]
       ::user-email [:string {:min 5}])"
  [& kvs]
  (assert (even? (count kvs)) "register-all! requires pairs of [key schema]")
  (let [pairs (partition 2 kvs)]
    (doseq [[k v] pairs]
      (register! k v))
    (set (map first pairs))))

;;; ---------------------------------------------------------------------------
;;; Introspection
;;; ---------------------------------------------------------------------------

(defn registered-schemas
  "Return a map of all registered domain schemas.

   Does not include Malli's built-in schemas."
  []
  @*schemas)

(defn registered?
  "Check if a schema keyword is registered."
  [k]
  (contains? @*schemas k))

(defn schema-definition
  "Get the raw definition for a registered schema.

   Returns nil if not registered."
  [k]
  (get @*schemas k))

(defn schemas-in-namespace
  "Get all schemas registered under a specific namespace.

   Arguments:
     ns-name - String namespace name (e.g., \"seon.ai.gemini\")

   Returns:
     Map of {keyword definition} for schemas in that namespace."
  [ns-name]
  (into {}
        (filter (fn [[k _]]
                  (= (namespace k) ns-name))
                @*schemas)))

;;; ---------------------------------------------------------------------------
;;; Development Helpers
;;; ---------------------------------------------------------------------------

(defn clear-all!
  "Clear all registered schemas. USE WITH CAUTION - only for testing."
  []
  (reset! *schemas {}))

(comment
  ;; REPL exploration

  ;; Register a test schema
  (register! ::test-schema [:string {:min 1}])

  ;; Check what's registered
  (registered-schemas)
  (registered? ::test-schema)

  ;; Get schemas for a namespace
  (schemas-in-namespace "seon.schema")

  ;; Validate data against registered schema
  (m/validate ::test-schema "hello")
  (m/validate ::test-schema "")  ; fails - min 1

  ;; Generate sample data
  (require '[malli.generator :as mg])
  (mg/generate ::test-schema)

  nil)
