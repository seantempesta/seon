---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, write-admission, seon.db, schema, class/absence-as-health]
---

# An entity with no identity attribute is never validated at the writer

## Problem

`seon.db`'s whole-entity check selects the schemas to validate a resulting row
against from that row's IDENTITY attributes:

```clojure
schema-keys (distinct (mapcat #(get schemas %) (keys identities)))   ; src/seon/db.clj:3058
```

`identities` is built in `write-report-error` (`src/seon/db.clj:3196-3199`) from
the installed `:db.unique/identity` attributes present before and after. An
entity carrying none selects NO schema, so `write-entity-error` validates
nothing and returns `nil` — the writer reports health because its subject is
absent, which is this project's recurring failure class stated on the write
side.

The population this leaves unchecked is not hypothetical: the schema-key audit
counted **29 marked component maps** with no identity attribute
([audit](../../prds/steward-platform/research/schema-key-audit-2026-09-16.md)),
and the deletion study names the same hole at the same seam
([section 0](../../prds/steward-platform/research/deletion-semantics-agents-and-turns-2026-09-16.md)).
A component row swept or half-written by a foreign transaction is admitted in
any shape.

The sibling exemption one line earlier, `(when (seq row))` at
`src/seon/db.clj:3055`, is deliberate and correct: an entity retracted to
nothing is skipped, which is why coordinated deletion works.

## Wanted end

A component row is validated as part of its parent's value — the ruled unit
(AGENTS.md §3, G5: "a component is part of its parent's value"): the parent is
pulled with its components expanded and validated once against the parent's
schema. Inventing identity attributes on component rows to make the selector
see them is explicitly not the fix.

## Evidence

Found while refuting
[a sparse program upsert is now admitted…](archive/a-sparse-program-upsert-is-now-admitted-where-the-test-expects-a-refusal.md)
on 2026-09-17: the create path there is sound precisely because
`:seon.fn/sym` is an identity attribute. Out of that lane's scope.
