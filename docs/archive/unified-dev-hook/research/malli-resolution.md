# Malli Schema Resolution

Research on how to resolve Malli schema references recursively for building AI context.

## The Problem

Given a function schema like:
```clojure
(m/=> process-order [:=> [:cat :user/id :order/cart] :order/result])
```

We need to:
1. Extract the schema references (`:user/id`, `:order/cart`, `:order/result`)
2. Resolve each reference to its definition
3. Recursively resolve nested references
4. Build a complete picture for AI context

## Malli's Schema Resolution

### Registry Architecture

Malli uses a layered registry system:

```clojure
(require '[malli.core :as m]
         '[malli.registry :as mr])

;; Default registry includes ~140 built-in schemas
(-> m/default-registry mr/schemas count)
;; => 140

;; Registries can be:
;; 1. Immutable (fast-registry) - static schemas
;; 2. Mutable (mutable-registry) - spec-like, can add at runtime
;; 3. Composite - combines multiple registries
;; 4. Var registry - resolves #'var references
```

### Key Functions for Schema Resolution

```clojure
;; 1. m/schema - Parse schema form, resolve references
(m/schema [:map [:id :uuid]])
;; => #malli.core/Schema

;; 2. m/type - Get schema type
(m/type (m/schema :uuid))
;; => :uuid

;; 3. m/children - Get child schemas
(m/children (m/schema [:map [:id :int] [:name :string]]))
;; => ([:id nil #malli.core/Schema] [:name nil #malli.core/Schema])

;; 4. m/deref - Dereference top-level refs
(m/deref (m/schema :uuid))
;; => schema for uuid

;; 5. m/deref-all - Recursively dereference top-level refs
(m/deref-all (m/schema :uuid))

;; 6. m/deref-recursive - Dereference ALL refs at ALL levels
(m/deref-recursive (m/schema [:map [:id :uuid]]))
;; => Fully expanded schema with no refs
```

### Walking Schemas

The `m/walk` function is the key to traversing schemas:

```clojure
;; Walk and collect all schema types
(defn collect-refs [schema]
  (let [refs (atom #{})]
    (m/walk
      schema
      (fn [s path children opts]
        (when (keyword? (m/type s))
          (swap! refs conj (m/type s)))
        (-set-children s children)))
    @refs))

;; Walk and transform to map representation
(m/walk
  (m/schema [:map [:id :uuid] [:items [:vector :item/product]]])
  (fn [schema path children opts]
    {:type (m/type schema)
     :children (when (seq children) children)
     :properties (m/properties schema)}))
```

### Detecting Schema References

Schema references are keywords that aren't built-in types:

```clojure
(def builtin-types
  #{:and :or :not :map :map-of :vector :set :tuple
    :enum :maybe :multi :re :fn :ref :=> :-> :function
    :string :int :double :boolean :keyword :symbol :uuid
    :any :some :nil :cat :catn :alt :altn :* :+ :? :repeat})

(defn schema-ref? [x]
  (and (keyword? x)
       (namespace x)  ; Custom refs are usually namespaced
       (not (builtin-types x))))

;; Check: :user/id is a ref, :string is not
(schema-ref? :user/id)  ;; => true
(schema-ref? :string)   ;; => false
```

## Implementation: Recursive Schema Resolution

```clojure
(ns seon.dev.schema-resolver
  "Resolve Malli schemas recursively for AI context."
  (:require [malli.core :as m]
            [malli.registry :as mr]))

(defn extract-refs
  "Extract all schema references from a schema.
   Returns set of keywords that are registry references."
  [schema]
  (let [refs (atom #{})
        builtin? (fn [k] (contains? (m/type-schemas) k))]
    (m/walk
      schema
      (fn [s _ children _]
        (let [t (m/type s)]
          ;; Keyword type that's not builtin = registry ref
          (when (and (keyword? t) (not (builtin? t)))
            (swap! refs conj t))
          ;; Return schema with walked children
          (m/-set-children s children))))
    @refs))

(defn resolve-schema
  "Resolve a schema keyword from the registry.
   Returns the schema definition or nil."
  [schema-key opts]
  (when-let [s (try (m/schema schema-key opts)
                    (catch Exception _ nil))]
    (m/form s)))

(defn resolve-all-refs
  "Recursively resolve all schema references.
   Returns map of {ref-key schema-form}.

   Example:
   (resolve-all-refs [:map [:cart :order/cart]] {})
   => {:order/cart [:vector :order/item]
       :order/item [:map [:id :uuid] [:qty :pos-int]]}
  "
  ([schema] (resolve-all-refs schema nil))
  ([schema opts]
   (loop [pending (extract-refs (m/schema schema opts))
          resolved {}]
     (if (empty? pending)
       resolved
       (let [ref-key (first pending)
             definition (resolve-schema ref-key opts)]
         (if definition
           (let [nested-refs (extract-refs (m/schema definition opts))
                 new-refs (clojure.set/difference nested-refs
                                                   (set (keys resolved))
                                                   #{ref-key})]
             (recur (into (disj pending ref-key) new-refs)
                    (assoc resolved ref-key definition)))
           (recur (disj pending ref-key) resolved)))))))

(defn schema-context
  "Build complete schema context for a function.
   Returns map with :function-schema and :schema-definitions."
  [fn-schema opts]
  (let [resolved (resolve-all-refs fn-schema opts)]
    {:function-schema (m/form (m/schema fn-schema opts))
     :schema-definitions resolved}))
```

## Example Usage

```clojure
;; Define some schemas in registry
(def my-registry
  {:user/id :uuid
   :order/item [:map
                [:id :uuid]
                [:product :string]
                [:qty :pos-int]]
   :order/cart [:vector :order/item]
   :order/result [:map
                  [:order-id :uuid]
                  [:total :double]
                  [:items :order/cart]]})

;; Function schema
(def fn-schema [:=> [:cat :user/id :order/cart] :order/result])

;; Resolve all references
(resolve-all-refs fn-schema {:registry my-registry})
;; =>
;; {:user/id :uuid
;;  :order/cart [:vector :order/item]
;;  :order/item [:map [:id :uuid] [:product :string] [:qty :pos-int]]
;;  :order/result [:map [:order-id :uuid] [:total :double] [:items :order/cart]]}

;; Build complete context for Gemini
(schema-context fn-schema {:registry my-registry})
;; =>
;; {:function-schema [:=> [:cat :user/id :order/cart] :order/result]
;;  :schema-definitions {...all resolved refs...}}
```

## Handling the Global Registry

When using Malli's global registry (via `m/=>` macro):

```clojure
(require '[malli.core :as m])

;; The m/=> macro registers in the global function-schemas atom
;; Schemas use the default-registry for type lookup

;; To get schemas with resolution:
(defn resolve-fn-schema [ns-sym fn-sym]
  (when-let [schema-data (get-in (m/function-schemas) [:clj ns-sym fn-sym])]
    (let [schema (:schema schema-data)]
      {:function-schema (m/form schema)
       :schema-definitions (resolve-all-refs schema)})))
```

## Primitive Detection

For the PRD's entity model, we need to know if a schema is primitive:

```clojure
(def primitive-schemas
  "Schemas that don't need further resolution."
  #{:string :int :double :boolean :keyword :symbol :uuid
    :any :some :nil :pos-int :neg-int :nat-int
    :pos :neg :float :number :integer :decimal
    :inst :uri :bytes :char
    ;; Predicates
    string? int? double? boolean? keyword? symbol? uuid?
    number? integer? pos? neg? zero? pos-int? nat-int?})

(defn primitive-schema?
  "Check if schema is a leaf type (no refs to resolve)."
  [schema]
  (let [t (m/type (m/schema schema))]
    (or (contains? primitive-schemas t)
        (fn? t)  ; predicate functions
        (= :enum t))))
```

## For XTDB Storage

When storing resolved schemas in XTDB:

```clojure
;; Store the schema as EDN (Clojure data structures work directly)
{:xt/id :order/cart
 :entity/type :schema
 :schema/definition [:vector :order/item]
 :schema/refs #{:order/item}
 :schema/primitive? false}

;; Query for all schemas used by a function
(from :schema [{:xt/id schema-key} schema/definition schema/refs])

;; Recursive resolution via application code (not XTQL)
(defn resolve-schema-tree [db schema-refs]
  (loop [pending schema-refs
         resolved {}]
    (if (empty? pending)
      resolved
      (let [ref (first pending)
            entity (xt/entity db ref)]
        (recur (into (disj pending ref)
                     (clojure.set/difference (:schema/refs entity #{})
                                             (set (keys resolved))))
               (assoc resolved ref (:schema/definition entity)))))))
```

## Key Insights

1. **m/deref-recursive** does the heavy lifting but returns a schema object, not a map of refs to definitions.

2. **m/walk** is the right tool for traversal - use it to collect refs rather than manual recursion.

3. **The registry is key** - custom schemas live in registries. Use `m/schema` with `:registry` option for local registries.

4. **Namespaced keywords** are the convention for custom schema refs (`:user/id` vs `:string`).

5. **Schema forms are data** - they serialize naturally to EDN/XTDB.

## REPL Testing Examples

```clojure
;; Test schema resolution
(require '[malli.core :as m])

;; Simple ref resolution
(m/form (m/deref (m/schema :uuid)))
;; => uuid?

;; Walking to find refs
(def test-schema
  [:map
   [:id :uuid]
   [:items [:vector [:map [:name :string]]]]])

(m/walk test-schema
        (fn [s p c o]
          (println "Type:" (m/type s) "Children:" (count c))
          (m/-set-children s c)))

;; With custom registry
(def opts {:registry {:my/type [:map [:x :int]]}})
(m/form (m/schema :my/type opts))
;; => [:map [:x :int]]
```
