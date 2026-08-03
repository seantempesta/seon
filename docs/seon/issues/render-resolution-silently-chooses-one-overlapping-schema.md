---
type: issue
status: open
severity: friction
tags: [issue, render, schema]
---

# Refuse ambiguous render declarations from overlapping open schemas

## Problem

Open maps correctly allow one value to validate against multiple schema shapes.
`matching-shapes-in` returns every match in deterministic rank order, but two
production render consumers use `some` to take the first declaration. A value
with two valid declared render shapes therefore resolves silently according to
schema rank instead of proving that the selected projection is unambiguous.

## Evidence

An independent projection containing shapes A (`x`) and B (`x`, optional `y`)
was probed with `{x 1, y 2}`. Closed A rejected the extra key, so only B
matched; open A and B both validated. `matching-shapes-in` returned
`[:seon.adversarial/shape-a :seon.adversarial/shape-b]`.

- `src/seon/schema.clj:2236-2238,2304-2324` ranks by descending required-key
  count then schema-key string and returns every validating row.
- `src/seon/render.clj:294-305` uses `some` over that ordered result to select
  the first declaration.
- `src/seon/render/walk.clj:153-159` does the same for overrides and defaults.

Related structural cause: [[map-unions-have-no-explicit-discriminants]].

## Owner

The render projection-resolution chain, using declared schema facts.

## Acceptance

If two matching schemas contribute different declarations for one render kind,
resolution returns or records a loud ambiguity value instead of selecting by
incidental rank. Equal declarations may collapse to one value. A regression
uses two overlapping open schemas in both the direct renderer and render walk.
