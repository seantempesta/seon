---
type: issue
status: open
severity: friction
tags: [issue, render, schema, context]
---

# Walk family detection reads "the first declared attribute" and calls it identity

## Problem

`entity-families` (`src/seon/render/walk.clj:189-220`) derives each
registered `:seon.db/entity` family's detection key as

```clojure
:seon.render.walk/identity-attribute (first attributes)
```

— the FIRST entry of the Malli map schema, in declaration order.
`family-probe` and `families-of-eid` (`:226-243`) then decide which
families an entity belongs to by that attribute's presence, and
`reverse-refs` uses the same attribute to decide which source entities
count (`:307-311`).

Nothing makes the first declared entry an identity attribute. Measured
across all 24 registered entity families (probe retained at
`tmp/audit-0731/probe1.clj`), four have a first attribute with no
`:db.unique/identity`: `:seon.ns.alias/binding`,
`:seon.ns.import/binding`, `:seon.ns.refer/binding`, and
`:seon.render.block/block`. Detection happens to work today because no
family's first entry is `{:optional true}` — also measured, also
undefended.

Two ways this breaks silently:

- reordering a map schema's entries, which the schema convention calls
  editorial ("File boundaries are editorial only",
  `src/seon/schema/edn.clj` admission rules), changes which attribute
  detects the family;
- making a first entry optional makes entities of that family
  undetectable, and by
  `render-walk-silently-drops-entities-outside-registered-families.md`
  that failure is silent — the entity renders as `#:db{:id N}`.

The name is also wrong in the way that matters: a reader of
`:seon.render.walk/identity-attribute` will assume it is the family's
`:db.unique/identity` attribute and reason about uniqueness that does
not exist.

## Acceptance

Family detection is derived from a property that cannot be reordered
away. Either the detection attribute is computed as the family's
declared `:db.unique/identity` attribute (with an explicit, loud
refusal for a family that has none), or detection uses the family's
complete required-attribute set rather than one positional pick. The
field is renamed to what it actually is in either case, and one
recurring property asserts that permuting a registered map schema's
entry order leaves every family's detection unchanged.

## Evidence

`docs/prds/sci-execution-runtime/research/context-wave-audit-2026-07-31.md`
