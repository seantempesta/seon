---
type: component
status: production
tags: [component, schema]
---
# Schema System

> Single source of truth for all data types in Seon — register once, derive everything.

## Purpose

Seon needs every data boundary (function calls, database writes, channel messages, UI rendering) to agree on what data looks like. The schema system solves this by providing one central registry where all attribute schemas are defined. From that single registration, the system derives Malli validation, Datalevin persistence schemas, generative test data, and function contracts. Without it, each boundary would define its own schema, and they would drift apart.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.schema` | `src/seon/schema.clj` | Global Malli registry: `register!`, `register-all!`, introspection |
| `seon.db.schema` | `src/seon/db/schema.clj` | Malli-to-Datalevin bridge: type mapping, entity validation, live schema comparison |

## Public API Surface

### `seon.schema` — Registration and Introspection

- **`register! [k v]`** — Register a single Malli schema by keyword. The fundamental operation. Every namespace calls this at load time for its attributes.
- **`register-all! [& kvs]`** — Register multiple schemas as keyword-value pairs.
- **`registered-schemas []`** — Return all registered domain schemas (map of keyword to schema def).
- **`registered? [k]`** — Check if a keyword has a registered schema.
- **`schema-definition [k]`** — Get the raw Malli definition for a keyword.
- **`schemas-in-namespace [ns-name]`** — Get all schemas under a namespace string.
- **`clear-all! []`** — Testing only. Reset the registry.

### Built-in Types

Three custom types are registered at load time:

- **`:inst`** — Timestamp type (Malli only provides `inst?` predicate; this provides a keyword type consistent with `:string`, `:int`, etc.)
- **`:seon.flow/dynamic`** — Wire protocol field validated dynamically at message boundaries, not statically. Used for `::args`, `::value`, `::payload` in flow messages.
- **`:seon.db/ref`** — Datalevin entity reference. Accepts positive integers (entity IDs) or lookup refs `[keyword value]`.

### `seon.db.schema` — Bridge and Validation

- **`malli-type->datalevin-type [malli-type]`** — Map a leaf Malli type to a `:db.type/*` keyword.
- **`malli-map->datalevin-schema [malli-schema]`** — Derive a full Datalevin schema map from a Malli `:map` schema. Handles collections (vector/set become cardinality-many), nested maps (become component refs), enums, and `:seon.db/ref`.
- **`register-entity-schema! [name schema]`** — Register a persisted entity schema for startup validation.
- **`persisted-schemas []`** — Return all registered persisted entity schemas.
- **`validate-persisted-schema [name schema]`** — Check one schema for banned types (`:any`, `:some`, `:nil`, `[:maybe X]`, mixed enums).
- **`validate-persisted-schemas! []`** — Validate all registered persisted schemas at startup. Throws on violations.
- **`validate-against-live-schema [live-schema]`** — Compare all persisted schemas against the actual Datalevin schema in the running database. Detects value type, cardinality, and uniqueness mismatches.

## Dependencies

- **Uses**: `malli.core`, `malli.registry` (Malli itself)
- **Used by**: [[components/database]] (transact! validates attrs via registry, ensure-schema! derives Datalevin types), `seon.dev.instrumentation` (function contracts), `seon.flow.msg` (wire protocol schemas — see [[components/flow-topology]]), every domain namespace (register! calls)

## How Data Flows

1. **Load time**: Each namespace calls `schema/register!` for its attributes. The atom-backed mutable registry accumulates all schemas.
2. **First write**: When `db/transact!` encounters an attribute not yet in the Datalevin schema, it calls `db-schema/malli-map->datalevin-schema` to derive the Datalevin type, then `d/update-schema` to add it. This is fully automatic.
3. **Startup**: `validate-persisted-schemas!` runs, checking all entity schemas registered via `register-entity-schema!` for banned types. `validate-against-live-schema` compares derived schemas against the live database.
4. **Runtime**: `seon.dev.instrumentation` wraps functions with `:malli/schema` metadata, validating every call's inputs and outputs against the registered schemas.
5. **Bridge translation**: The `seon-db-props->db-props` internal function translates `:seon.db/identity` to `:db/unique :db.unique/identity`, `:seon.db/unique` to `:db/unique :db.unique/value`, etc. Domain code never writes `:db/*` properties directly.

## Design Decisions

**Mutable atom, not a var.** The registry uses `defonce ^:private *schemas (atom {})` so it survives namespace reloads. The `defonce` + `mr/set-default-registry!` pattern initializes once and composes with Malli's default schemas.

**`:seon.db/*` properties, not `:db/*`.** Domain schemas annotate with `:seon.db/identity`, `:seon.db/unique`, etc. The bridge translates to `:db/*` at derive time. This keeps domain schemas independent of Datalevin's property namespace.

**Load-order guard.** `malli-map->datalevin-schema` catches resolution errors with a clear message: "ensure schema/register! is called BEFORE entity schema defs in the file." This prevents a subtle boot-order bug where entity schemas reference attributes that haven't been registered yet.

**Banned types for persistence.** `:any`, `:some`, `[:maybe X]`, `:nil`, and mixed-type enums are banned in persisted schemas. Absence means "key not present" (`:optional true`), never nil. This is enforced at startup, not just by convention.

**Custom `:inst` type.** Malli provides `inst?` as a predicate but not as a keyword type. Registering `:inst` as a simple schema means all timestamps use the same keyword form as `:string`, `:int`, etc., and the bridge maps it to `:db.type/instant`.

## Refactoring Opportunities

- **`malli-type->datalevin-type` has `:malli/schema` using `:any`** — The function's own schema uses `:any` for input and `[:maybe :keyword]` for output. Both violate conventions. Input should be `:keyword` or a union of the known types; output should use `{:optional true}` pattern instead of `:maybe`.
- **`malli-map->datalevin-schema` schema uses `:any` and `:map-of :keyword :any`** — Same issue. These are the bridge's own metadata schemas, not persisted, but they should still follow conventions.
- **`schema->datalevin-attr` is a large case expression** — 10+ branches. Could be converted to a multimethod or protocol for extensibility, but the closed set of Malli types makes this arguably fine.
- **Two separate registries** — `*schemas` (attributes) and `*persisted-schemas` (entity schemas) are independent atoms in different namespaces. Could be unified into a single registry with a `:persisted?` flag.
- **`:seon.flow/dynamic` is a workaround for `:any`** — It validates `some?` (non-nil) statically, with real validation deferred to message boundaries. This is the principled approach for polymorphic wire protocols, but it's worth tracking as the system evolves.
