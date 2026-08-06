---
type: issue
status: open
severity: friction
tags: [issue, schema, runtime]
---

# Replace load-time schema registration sentinels with acquired declarations

## Problem

Twenty-two `defonce` sentinels perform one-time schema or predicate registration
as a namespace-load side effect. The schema-declaration half makes load order a
second authority beside `resources/seon/schemas/*.edn` and the database program
graph. The predicate half captures a function value once, so redefining its Var
does not refresh the cached callable. `schema.edn/packaged-base-forms` then
freezes the ambient bootstrap registry before consumer namespaces load.

This conflicts with the acquire-at-basis model: shipped declarations are
database facts; a qualified predicate symbol is durable; a resolved callable is
only a reloadable compiler artifact.

## Evidence

- `src/seon/schema.clj:620-790` defines 18 `_...` `defonce` sentinels. Four
  cache predicate functions or install Malli's default, and fourteen mutate
  candidate schema forms.
- `src/my/fs.clj:52-58`, `src/my/edit.clj:41-43`, and `src/seon/blob.clj:28-30`
  add four more predicate-registration sentinels.
- `src/seon/schema/edn.clj:43-47` captures `schema/registered-schemas` once and
  later merges that snapshot with EDN resources at `:360-365`.
- `src/seon/schema.clj:597-608` stores the callable function object, not its Var;
  `defonce` prevents the sentinel from refreshing it after a `defn` reload.

## Owner

`resources/seon/schemas/*.edn` and the database-backed schema acquisition path
own durable declarations. A process-local compiler cache may resolve qualified
predicate symbols at the acquired generation and refresh on Var redefinition.

## Acceptance

- No shipped schema declaration depends on namespace load order or a
  `defonce` registration side effect.
- Delete `packaged-base-forms`; complete publication derives canonical schema
  rows from declared resources and the indexed program graph only.
- Predicate compilation resolves or caches Vars by qualified symbol and sees a
  re-evaluated `defn` without reloading a sentinel.
- Any unavoidable Malli default-registry integration is an explicit
  process-bootstrap resource operation, not schema authority and not a hidden
  one-time registration.
