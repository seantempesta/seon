---
type: capability
status: complete
tags: [vision, schema]
---
# Machine-Verifiable Data Contracts

Every attribute and function in the system has a machine-readable contract. Schemas are the single source of truth for what data looks like, how it persists, and what functions accept and return. Runtime instrumentation validates every public function call -- agents cannot ship code that violates its own declared interface.

## What Exists

- `schema/register!` is the sole registration point for all attribute schemas
- Three custom types (`:inst`, `:seon.db/ref`, `:seon.flow/dynamic`) bridge Malli to Datalevin
- Runtime instrumentation validates inputs, outputs, and arity on every public function call
- Startup consistency check bans `:any` and `[:maybe X]` from registered schemas
- Generative pipeline roundtrip tests prove schemas survive Malli -> Datalevin -> Nippy -> back

## Gaps

Some internal functions still use `:any` (flow writer args, render html output). Entity schema vars coexist with `register!` calls in some files -- should consolidate.

## Related

- Components: [[components/schema-system]], [[components/database]]
- PRDs: [[prds/schema-unification/prd]]
