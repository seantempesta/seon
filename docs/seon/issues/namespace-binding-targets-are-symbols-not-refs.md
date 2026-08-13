---
type: issue
status: open
severity: friction
tags: [issue, database, sci, wave/future-program-graph-binding]
---

# Connect namespace alias and refer targets with refs

## Problem

`:seon.ns/requires` now connects namespace rows by Datahike refs, but alias and
refer component rows still store their target namespaces as symbols. A generic
database graph walk can reach each binding component from its owning namespace
but cannot continue to the target namespace, leaving one namespace relationship
represented in two incompatible ways.

## Evidence

- The PROGRAM section of `resources/seon/schema.edn` declares requires as
  refs while both `/target-ns` attributes remain symbols.
- `src/seon/fn.clj:173-191` converts static requires to namespace lookup refs
  but writes alias and refer target namespaces as symbols.
- `src/seon/sci/eval.clj:463-509` makes the same symbolic projection for
  runtime binding rows and reconstructs SCI bindings directly from those
  symbols.
- `test/seon/fn_test.clj:77-82` currently pins symbolic alias and refer target
  values.
- `docs/prds/sci-execution-runtime/research/requires-to-refs-notes-2026-07-31.md:57-65`
  records that aliases and refers were deliberately left out of the
  requires-to-refs landing and that their component rows cannot traverse to
  target namespace rows.

The existing `runtime-lint-does-not-resolve-namespace-aliases.md` issue owns a
different consumer mismatch: canonicalizing alias-qualified source names while
selecting lint stubs. It does not own this persisted graph representation.

## Owner

The one namespace binding representation shared by `seon.fn` indexing and
`seon.sci.eval` runtime publication/acquisition.

## Acceptance

- Alias and refer target namespaces are ordinary refs to identity-upserted
  `:seon.ns/name` rows, with no symbolic durable fallback.
- Static indexing and runtime publication write the same binding shape, and
  fresh acquisition reconstructs the exact SCI aliases and refers from it.
- The generic database ref walk reaches target namespace rows through alias
  and refer components.
- Recurring build, runtime, and restart proofs cover aliases, renamed refers,
  multiple aliases to one target, and external target namespaces.
