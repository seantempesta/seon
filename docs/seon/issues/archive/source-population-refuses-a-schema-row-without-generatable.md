---
type: issue
status: resolved
severity: blocker
tags: [issue, publication, schema, adoption]
---

# Live publication refuses a schema row without its generatable declaration

## Problem

On 2026-09-09 at 21:17 UTC, `bin/seon init --dev default --changed
src/seon/turn.clj` refused during `:seon.schema/rows` population:
`Attribute :seon.schema/generatable? expected :boolean, got :seon.error/unknown.`
The diagnostic path was `[0 :seon.schema/generatable?]`.

## Evidence

The context-blocks selected-path snapshot published and booted successfully;
its gate passed 51 tests / 417 assertions and the separate platform gate
passed 83 / 490. The live process's publication boundary differs from that
fresh selected snapshot. No foreign edit was changed and the underlying
cause has not been attributed. Default remained HTTP 200 after refusal.

## Owner

The canonical schema-row population and the live publication projection.

## Acceptance

The same live publication produces complete schema rows, records its source
commit, and adopts it successfully, or explicitly reports the required reset.

## Resolution (2026-09-15 triage)

surface: adoption-publication

At HEAD `91d5547b5`, `resources/seon/schemas/seon.schema.edn:68` makes generatability optional (commit `26ec13420`); a row lacking it no longer violates this contract. Publication's canonical rows at `src/seon/fn.clj:1648–1653` pass through `seon.test.accretion/schema-row`; `src/seon/test/accretion.clj:30–39` always associates a boolean, returning false on generation failure. Verified these complete source branches with `git show HEAD:<path>`. The recorded missing/unknown row is excluded by the current population and contract. The old live offending row was not retained, so its historical cause is not reconstructed. No live publication was initiated during concurrent source edits.
