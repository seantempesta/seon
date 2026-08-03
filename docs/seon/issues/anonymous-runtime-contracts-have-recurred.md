---
type: issue
status: open
severity: friction
tags: [issue, schema, config, flow, render]
---

# Replace recurring anonymous runtime contracts with named predicates

## Problem

Public runtime functions and registry declarations again use anonymous `:any`
or `:some` where an existing named contract, a concrete predicate, or a
discoverable domain schema describes the value. This reopens a contract class
an archived repair claimed to have removed.

## Evidence

- An EDN parse of `resources/seon/schema.edn` finds 19 `:any` leaves across 18
  schema keys and zero `:some` leaves. Five evaluation/session maps repeat an
  anonymous value slot instead of referencing the declared
  `:seon.sci.admit/value` boundary (`:47-49`, `:1241-1254`, `:1282`, `:1501`).
- `resources/seon/schema.edn:819-825` contracts the config connection as
  `:any`, although the database connection predicates are registered.
- `resources/seon/schema.edn:1000` says a drill path is `[:vector :any]` while
  `src/seon/render/data.clj:22-28` narrows its steps to keyword, string, or
  integer. `resources/seon/schema.edn:2248-2250` similarly repeats `:any` for
  an identity value instead of the declared `:seon.schema/value` boundary.
- A current-tree lexical census finds 58 `:any` and 22 `:some` occurrences in
  active `src/` contracts/data (quarry and dependencies excluded). Nineteen of
  the `:some` occurrences are transaction-data returns—18 in
  `src/seon/cluster/run.clj` and one at `src/seon/cluster/loop.clj:290`—even
  though `:seon.store/transaction-data` is declared at
  `resources/seon/schema.edn:2682-2683`. The other three are Flow emission
  maps at `src/seon/cluster/agent.clj:128,182,459` and need a named Flow
  emission contract rather than the transaction schema.
- `src/seon/config.clj:263-264` contracts database inputs as `:any` and then
  uses them as database values/connections.
- `src/seon/flow.clj:83-111` contracts the step Var as `:any` and then
  hand-validates `var?`.
- `src/seon/render/hiccup.clj:75` declares `raw`'s concrete `Raw` return as
  `:any`; `src/seon/instrument.clj:108` declares its set of Vars as
  `[:set :any]`. These are concrete opaque values, not polymorphic boundaries.
- The generic SCI result, provider JSON document, total-predicate input,
  Datahike lookup, and arbitrary value-renderer input remain legitimate
  polymorphic boundaries. The repair must name them once and reference that
  name; it must not pretend those domains are closed.
- `docs/seon/issues/archive/database-and-transaction-boundaries-use-anonymous-any-contracts.md`
  records the previous class and its intended removal.

## Owner

The schema owner for shared value, database, transaction-data, Flow emission,
and opaque runtime-object predicates, with honest generators where Malli needs
one.

## Acceptance

Every non-polymorphic occurrence names its actual accepted shape; genuinely
polymorphic boundaries are declared once and referenced rather than repeated
anonymously. Generated values pass the named predicates, and invalid inputs
fail at the contract rather than a second hand check. The inventory is rerun
structurally for `resources/seon/schema.edn` and lexically for active `src/`,
with a verdict for every remaining `:any` and `:some`.
