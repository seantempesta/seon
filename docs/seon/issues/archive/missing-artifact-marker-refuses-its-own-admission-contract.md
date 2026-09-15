---
type: issue
status: resolved
severity: friction
tags: [issue, render, schema, wave/print-path]
---

# Missing artifact markers refuse their own admission contract

## Problem

`seon.render.value/artifact` re-admits a missing marker with an empty caps
map and `:seon.sci.admit/unbounded? true`. The admission request schema
requires both bound keys even on that path. Under armed contracts, the
fallback cannot produce the print node its own output contract requires.

## Evidence

The pre-WIP snapshot `38c49a1db` reproduces the nil print-node refusal.
The live JVM probe
`(seon.render.value/artifact {:seon.sci.admit/reason :over-bound :seon.sci.admit/bytes 100})`
refuses the nested admission at
`[:seon.sci.admit/caps :seon.config.eval.result/max-bytes]`.
See [the investigation](../../../prds/context-generation/research/bisect-today-reds-2026-09-15.md).

## Owner

`src/seon/render/value.clj`, `artifact`, and the existing request declaration
in `resources/seon/schemas/seon.sci.admit.edn`.

## Acceptance

A missing marker renders as its reason and measured size under the same
armed contracts used in production. Its construction must fit the one
admission contract without an invented bound or a nil node.

## Verification, September 15

The four-namespace HEAD-plus-owned-paths gate passed **103 tests / 248
assertions, 0 failures / 0 errors**, exit 0, at 20:10:49Z. The snapshot
basis was `6dc70f30a`; the exact command and content digest are in the landing
note. This includes the armed missing-marker check, executable artifact
requery, nested-map elision coordinates, and root/nested SCI record rendering.
