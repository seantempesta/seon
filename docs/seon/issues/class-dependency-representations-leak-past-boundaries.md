---
type: issue
status: open
severity: friction
tags: [issue, architecture, dependency, class-kill]
---

# Translate dependency representations once at their boundary

## Problem

Consumers inspect or assume concrete dependency callback, collection, wrapper,
or positional representations instead of receiving a Seon contract value.
Supported variants then behave differently or fail far from the boundary.

## Evidence

Four open issues span 2026-08-01 through 2026-08-06:
[[admit-inst-overlap-prefers-collection-shape]],
[[blob-get-assumes-file-store-callback-shape]],
[[schema-map-extraction-still-depends-on-position-two]], and
[[transcript-about-lookup-passes-a-set-to-pull-many]].

The archive repeats the class on 2026-08-07, 2026-08-08, and 2026-08-11 in
[[archive/a-cohosted-second-cluster-cannot-boot]],
[[archive/a-component-value-is-refused-by-its-own-ref-shape]],
[[archive/walk-refuses-an-as-of-database-value-and-empties-the-agent-context]],
[[archive/capture-basis-read-through-the-identity-reader-kills-the-turn]],
[[archive/root-data-metadata-erased-named-sci-arity-identity]], and
[[archive/datahike-pull-evidence-misses-automatic-component-expansion]].

## Owner

Each dependency integration boundary, using the dependency's maintained
protocols and value vocabulary.

## Acceptance

- Concrete dependency values are translated once into a declared ordinary
  value at the boundary; downstream consumers cannot inspect host layout.
- Properties cover every supported wrapper/callback/collection variant from
  the pinned dependency source.
- Unsupported variants refuse at the boundary with the dependency operation
  and received representation, never by a downstream cast or missing key.
