---
type: issue
status: open
severity: friction
tags: [issue, schema, wave/open-maps-accretion]
---

# Give open map unions explicit discriminants

## Problem

Owner ruling #48 removes closed-map rejection so every map can accrete. Six
union families still depend on mutually exclusive producer keys rather than an
explicit discriminant: `:seon.ai/request`, `:seon.ai/target`,
`:seon.render/surface`, `:my.message/value`, `:seon.ai/completion`, and
`:seon.flow/work-call`. A combined value carrying both arms' required keys now
validates against both branches. The old `:seon.render/surface` comment stated
explicitly that closedness made its branches disjoint.

The two honest precedents are already in the same population:
`:my.run/value` dispatches on `:my.run/disposition`, and
`:seon.flow/work-result` dispatches on `:seon.flow/outcome`.

## Evidence

A fresh Malli 0.20.0 projection over all 690 registered forms found nine
multi-map `:or` nodes (including a nested duplicate of `:my.message/value`).
Three generated values per branch produced no accidental overlap because the
generator emits only declared keys, but combining both arms' distinct required
keys makes the six families above satisfy both branches. The map population's
explicit constant discriminants keep the other two families disjoint.

The broader shape-matching measurement generated 441 values from 169 indexed
map shapes using seeds 48001 through 48003. Opening the population produced
130 values with at least one additional match, spanning 67 distinct ordered
shape pairs. That is real global overlap exposed by
`matching-shapes-in`, independently of the six semantic unions above; callers
must treat its result as the complete set of satisfied declarations rather
than assume a single classification.

## Owner

Each union's producing function and its one declaration in
`resources/seon/schema.edn`.

## Acceptance

Each semantic union either carries one explicit namespaced discriminating
attribute with distinct constant values per branch, or has an admission check
that names and refuses the impossible combination. Generated and hand-built
combined values prove every branch is unambiguous. No `:closed` property is
restored.
