---
type: decision
status: implemented
date: 2026-03-05
---

# ADR-003: Unified Ref Type (`:seon.db/ref`)

## Context

Entity references in Datalevin accept two forms: `pos-int?` (entity IDs from `d/pull`) and lookup refs (`[:identity-attr value]`). Before unification, ref attributes were typed as `:any` or `:int` with ad-hoc `:db/valueType :db.type/ref` properties, scattered across 5 attributes in runtime and ingest modules.

## Decision

Define `:seon.db/ref` as a custom Malli type in `seon.schema` that accepts both `pos-int?` and `[keyword value]` lookup refs. All entity reference attributes use this single type. The bridge (`seon.db.schema`) recognizes it and produces `{:db/valueType :db.type/ref}`.

## Usage

```clojure
;; Register a ref attribute
(schema/register! :seon.call/from-fn :seon.db/ref)
(schema/register! :seon.agent.run/runtime :seon.db/ref)

;; Transact with lookup ref
(db/transact! :seon [{:seon.call/from-fn [:seon.fn/qualified-name "foo/bar"]}])

;; Transact with entity ID
(db/transact! :seon [{:seon.call/from-fn 42}])
```

## Rationale

- **Replaces `:any` on ref attrs.** The 4 ref attributes in `ingest.clj` and 1 in `runtime.clj` were typed `:any` -- a banned type. `:seon.db/ref` gives them a concrete, validatable type.
- **Lives in `seon.schema`.** The namespace matches `seon.db` (the sole DB API), making it discoverable. Agents writing new ref attributes just use `:seon.db/ref`.
- **Bridge auto-derives.** `seon.db.schema` recognizes the `:seon.db/ref` type and emits `{:db/valueType :db.type/ref}` -- no manual `:db/*` properties needed on ref attributes.
- **One pattern for all refs.** Component refs (nested `:map`) are auto-derived by the bridge as `{:db/valueType :db.type/ref :db/isComponent true}`. Non-component refs use `:seon.db/ref`. No third path.

## Rejected Alternatives

- **`:int` with `:db/valueType` property** -- doesn't accept lookup refs, which are vectors.
- **`:any`** -- banned. No validation, no type safety.
- **Separate types for entity ID vs lookup ref** -- unnecessary complexity. Both resolve to the same Datalevin ref at transact time.

## Details

- `src/seon/schema.clj` -- type definition
- `src/seon/db/schema.clj` -- bridge recognition
- [[components/datalevin]] for ref semantics (component vs non-component)
