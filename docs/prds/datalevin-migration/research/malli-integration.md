# Malli-Datalevin Schema Integration Research

**Date:** 2026-01-28
**Status:** Complete

This document explores approaches for unifying Malli schemas with Datalevin database schemas, enabling a single source of truth for data definitions that can drive both validation and database storage.

---

## Executive Summary

**Recommended Approach:** Build a custom `seon.schema.datalevin` namespace that transforms Malli schemas to Datalevin schemas using the same multimethod pattern that Malli uses for JSON Schema transformation. This gives us:

1. **Single source of truth** - Define entity schemas in Malli, generate Datalevin schemas
2. **Property testing** - Use Malli generators to create valid DB entities
3. **Validation** - Malli validates data before DB transactions
4. **Full control** - Custom namespace means we own the mapping and can extend it

No existing library solves this problem well enough to use as-is.

---

## Existing Solutions Evaluated

### 1. malli-datomic (licht1stein/malli-datomic)

**Repository:** [github.com/Blasterai/malli-datomic](https://github.com/Blasterai/malli-datomic)

**Status:** Pre-release, sparse documentation ("FIXME: write usage documentation!")

**Verdict:** Not ready for production use. Only 4 commits, undocumented API. However, the concept is exactly what we need - it proves the approach is viable.

**Useful insight:** The library exists and is deployed to Clojars as `io.github.blasterai/malli-datomic`, suggesting others have the same need.

### 2. spectomic (Provisdom/spectomic)

**Repository:** [github.com/Provisdom/spectomic](https://github.com/Provisdom/spectomic)

**Status:** Mature library for clojure.spec -> Datomic/Datascript

**Key Pattern:** Uses test.check generators to *infer* types rather than parsing spec forms:

- Sample each spec 100 times
- Detect what types are generated
- Map those to Datomic types
- Verify all samples produce consistent types

**Verdict:** Great conceptual model, but uses clojure.spec not Malli. The generator-based inference approach is clever but overkill for Malli (where we have explicit type information).

### 3. DataScript as Domain Model (vvvvalvalval approach)

**Blog Post:** [DataScript as a Lingua Franca for Domain Modeling](https://vvvvalvalval.github.io/posts/2018-07-23-datascript-as-a-lingua-franca-for-domain-modeling.html)

**Pattern:** Store domain model in DataScript, derive everything else:

- Datomic schema transactions
- GraphQL schemas
- Validation rules
- Security policies

**Quote:** "Adding a single Attribute required changes to Datomic schema installation transactions and to a GraphQL Field and to data validation schemas."

**Verdict:** Philosophically aligned with our goals. We're doing the inverse - using Malli as the source of truth and deriving Datalevin schemas from it.

### 4. Malli JSON Schema Transform

**In reference-code/malli:** `src/malli/json_schema.cljc`

**Key Pattern:** Multimethod-based transformation:

```clojure
(defmulti accept (fn [name _schema _children _options] name) :default ::default)

(defmethod accept 'uuid? [_ _ _ _] {:type "string" :format "uuid"})
(defmethod accept 'inst? [_ _ _ _] {:type "string" :format "date-time"})
(defmethod accept :map [_ schema children _] ...)
```

**Verdict:** This is the exact pattern to follow for Datalevin transformation.

---

## Type Mapping: Malli -> Datalevin

### Primitive Types

| Malli Type | Datalevin Type | Notes |
|------------|----------------|-------|
| `:string` | `:db.type/string` | Direct mapping |
| `:int` | `:db.type/long` | Datalevin only has long |
| `:double` | `:db.type/double` | Direct mapping |
| `:boolean` | `:db.type/boolean` | Direct mapping |
| `:uuid` | `:db.type/uuid` | Direct mapping |
| `:keyword` | `:db.type/keyword` | Direct mapping |
| `:symbol` | `:db.type/symbol` | Direct mapping |
| `inst?` | `:db.type/instant` | java.util.Date |

### Complex Types

| Malli Type | Datalevin Approach | Notes |
|------------|-------------------|-------|
| `:map` | Individual attributes | Each key becomes an attribute |
| `:vector` | `:db.cardinality/many` | Or EDN-encode as string |
| `:set` | `:db.cardinality/many` | Natural fit |
| `:enum` | `:db.type/keyword` | Keywords work well |
| Nested maps | EDN string | Datalevin doesn't support nested |
| References | `:db.type/ref` | Entity references |

### Type Properties for Datalevin

Extend Malli schemas with `:datalevin/*` properties (following Malli's JSON Schema pattern):

```clojure
(def SessionId
  [:string
   {:datalevin/unique :db.unique/identity
    :datalevin/doc "4-char hex session ID"}])

(def SessionStatus
  [:enum
   {:datalevin/index true}
   :running :stopped :error])

(def Messages
  [:vector
   {:datalevin/cardinality :db.cardinality/many}
   :string])
```

---

## Recommended Design

### Approach: Malli Schema Compiler

Create a namespace `seon.schema.datalevin` that compiles Malli schemas to Datalevin format.

#### Core API

```clojure
(ns seon.schema.datalevin
  "Transform Malli schemas to Datalevin attribute definitions."
  (:require [malli.core :as m]))

;; Transform a single Malli schema to Datalevin attribute spec
(defmulti -transform
  "Transform Malli schema to Datalevin attribute map."
  (fn [schema options] (m/type schema))
  :default ::default)

;; Entry point
(defn attribute
  "Generate Datalevin attribute definition from Malli schema.

   Arguments:
     attr-name - Keyword attribute name (e.g., :ai.session/id)
     schema    - Malli schema definition

   Returns:
     Map suitable for Datalevin schema, e.g.:
     {:db/valueType :db.type/string
      :db/unique :db.unique/identity}"
  [attr-name schema]
  ...)

;; Generate full schema from entity definition
(defn entity-schema
  "Generate Datalevin schema for an entity type.

   Arguments:
     entity-name - Keyword prefix (e.g., :ai.session)
     definition  - Malli :map schema

   Returns:
     Map of attribute definitions for Datalevin."
  [entity-name definition]
  ...)
```

#### Type Multimethods

```clojure
(defmethod -transform :string [schema _]
  {:db/valueType :db.type/string})

(defmethod -transform :int [schema _]
  {:db/valueType :db.type/long})

(defmethod -transform :double [schema _]
  {:db/valueType :db.type/double})

(defmethod -transform :boolean [schema _]
  {:db/valueType :db.type/boolean})

(defmethod -transform :uuid [schema _]
  {:db/valueType :db.type/uuid})

(defmethod -transform :keyword [schema _]
  {:db/valueType :db.type/keyword})

(defmethod -transform 'inst? [schema _]
  {:db/valueType :db.type/instant})

(defmethod -transform :enum [schema _]
  {:db/valueType :db.type/keyword})

(defmethod -transform :vector [schema options]
  (let [child (first (m/children schema))]
    (merge (-transform child options)
           {:db/cardinality :db.cardinality/many})))

;; References to other entities
(defmethod -transform :ref [schema _]
  {:db/valueType :db.type/ref})
```

#### Property Handling

```clojure
(defn- merge-datalevin-properties
  "Merge :datalevin/* properties into attribute definition."
  [attr-def schema]
  (let [props (m/properties schema)]
    (cond-> attr-def
      (:datalevin/unique props)      (assoc :db/unique (:datalevin/unique props))
      (:datalevin/index props)       (assoc :db/index true)
      (:datalevin/cardinality props) (assoc :db/cardinality (:datalevin/cardinality props))
      (:datalevin/doc props)         (assoc :db/doc (:datalevin/doc props))
      (:datalevin/isComponent props) (assoc :db/isComponent true))))
```

---

## Example: Unified Entity Definition

### Define Entity Schema (Single Source of Truth)

```clojure
(ns seon.ai.schema
  (:require [seon.schema :as schema]
            [seon.schema.datalevin :as dl]))

;; Register Malli schemas
(schema/register-all!
  ;; AI Session entity
  ::session-id     [:string {:datalevin/unique :db.unique/identity
                             :datalevin/doc "Unique session ID"}]
  ::session-type   [:enum :session]
  ::session-status [:enum {:datalevin/index true}
                    :active :completed :failed :interrupted]
  ::namespace      [:string {:datalevin/index true}]
  ::prompt         :string
  ::started-at     [:fn {:datalevin/index true} inst?]
  ::ended-at       [:fn inst?]
  ::input-tokens   :int
  ::output-tokens  :int
  ::cost-usd       :double
  ::error          :string)

;; Entity schema combining attributes
(def Session
  [:map
   [:ai.session/id ::session-id]
   [:ai.session/type ::session-type]
   [:ai.session/status ::session-status]
   [:ai.session/namespace ::namespace]
   [:ai.session/prompt ::prompt]
   [:ai.session/started-at ::started-at]
   [:ai.session/ended-at {:optional true} ::ended-at]
   [:ai.session/input-tokens {:optional true} ::input-tokens]
   [:ai.session/output-tokens {:optional true} ::output-tokens]
   [:ai.session/cost-usd {:optional true} ::cost-usd]
   [:ai.session/error {:optional true} ::error]])

;; Generate Datalevin schema
(def datalevin-schema
  (dl/entity-schema :ai.session Session))
;; => {:ai.session/id {:db/valueType :db.type/string
;;                     :db/unique :db.unique/identity
;;                     :db/doc "Unique session ID"}
;;     :ai.session/type {:db/valueType :db.type/keyword}
;;     :ai.session/status {:db/valueType :db.type/keyword :db/index true}
;;     ...}
```

### Property Testing with Generators

```clojure
(ns seon.ai.schema-test
  (:require [clojure.test :refer [deftest]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [malli.generator :as mg]
            [datalevin.core :as d]
            [seon.ai.schema :as schema]))

(defspec session-roundtrip-test 100
  (prop/for-all [session (mg/generator schema/Session)]
    (let [conn (d/create-conn nil schema/datalevin-schema)]
      ;; Transaction should succeed
      (d/transact! conn [session])
      ;; Query should find it
      (= session
         (d/pull (d/db conn) '[*] [:ai.session/id (:ai.session/id session)])))))
```

---

## Trade-offs Analysis

### Approach A: Malli -> Datalevin Compiler (Recommended)

**Pros:**

- Malli is the source of truth (familiar, well-documented)
- Generators "just work" for property testing
- Validation happens before DB transactions
- Type properties are explicit and visible

**Cons:**

- Must maintain transformation code
- Some Malli features don't map to Datalevin (e.g., regex patterns)

### Approach B: Datalevin -> Malli Generator

**Pros:**

- Datalevin schema is the source of truth
- Database semantics are primary

**Cons:**

- Datalevin schema is less expressive than Malli
- Generators require additional work
- Validation needs custom implementation

### Approach C: Single DSL Generating Both

**Pros:**

- Truly unified definition
- Could be more concise

**Cons:**

- Another DSL to learn
- More abstraction = more complexity
- Loses Malli ecosystem benefits

### Approach D: Convention-Based (Same Keywords, Different Registries)

**Pros:**

- Minimal abstraction
- Both schemas exist independently

**Cons:**

- Easy for them to drift out of sync
- Manual maintenance burden
- No automatic property testing

---

## GitHub Repositories to Consider as Submodules

### Recommended Additions

1. **malli-datomic** - Even though sparse, useful as reference

   ```bash
   git submodule add https://github.com/Blasterai/malli-datomic reference-code/malli-datomic
   ```

2. **spectomic** - Good patterns for type inference

   ```bash
   git submodule add https://github.com/Provisdom/spectomic reference-code/spectomic
   ```

### Already Present

- `reference-code/malli/` - Malli source (essential)
- `reference-code/datalevin/` - Datalevin source (essential)

---

## Implementation Plan

### Phase 1: Core Transformer

1. Create `seon.schema.datalevin` namespace
2. Implement primitive type transformations
3. Handle `:datalevin/*` properties
4. Write tests for basic schemas

### Phase 2: Entity Support

1. Implement `:map` schema transformation
2. Handle optional keys
3. Support `:vector` with cardinality many
4. Support `:ref` for entity references

### Phase 3: Integration

1. Update existing entity definitions to unified format
2. Generate Datalevin schema at connection time
3. Property tests for entity roundtrips
4. Document in CONVENTIONS.md

### Phase 4: Advanced Features

1. Schema migration utilities (detect changes)
2. Generator customization for realistic test data
3. Validation error messages with DB context

---

## References

- [Malli GitHub](https://github.com/metosin/malli) - Data-driven schemas for Clojure
- [Datalevin GitHub](https://github.com/juji-io/datalevin) - Datalog database
- [malli-datomic](https://github.com/Blasterai/malli-datomic) - Early-stage Malli to Datomic bridge
- [spectomic](https://github.com/Provisdom/spectomic) - Spec to Datomic/Datascript schema
- [DataScript as Lingua Franca](https://vvvvalvalval.github.io/posts/2018-07-23-datascript-as-a-lingua-franca-for-domain-modeling.html) - Domain modeling approach
- [Malli JSON Schema](https://github.com/metosin/malli/blob/master/src/malli/json_schema.cljc) - Reference implementation for schema transformation
