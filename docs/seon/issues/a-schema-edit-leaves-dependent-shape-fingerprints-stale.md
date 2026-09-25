---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, publication, schema, call-preparation]
---

# A schema edit leaves its dependents' stored shape fingerprints stale

## Problem

A reference shape's fingerprint includes the definition fingerprints of what it
references (`seon.fn.schema-shape/reference-fingerprints`, schema_shape.clj:115-143;
`form-fingerprint`, :100-113). When a definition changes, incremental publication
re-derives only the rows whose own source changed (`program/with-contract-facts`,
program.cljc:1109-1150, run over the changed rows). Rows that reference the changed
definition keep their old fingerprint. The rows that were republished get the new
one. Afterwards the program graph holds two shapes for one reference.

## Evidence (default, 2026-09-25 01:25Z, read-only MCP JVM probes)

- `:seon.flow/work-launcher`'s schema row was re-shaped in tx 536878790
  (2026-09-24T21:23Z, the d2604ede9 flow edit). It is a dependency of
  `:seon.env/environment`.
- `:seon.env/environment` has two shapes, both with type `:seon.env/environment` and
  form `":seon.env/environment"`:
  - Shape 18535 has fingerprint `eb3694…` (tx 536870917, the index). The schema row and
    11 arities use it: `seon.env/scope`, `seon.env/supplied-agent-id`,
    `my.program/supplied-context`, `seon.call-preparation/prepare`, …
  - Shape 90816 has fingerprint `9a103b…` (tx 536878790). `seon.db/supplied-database-value`
    and `seon.db/supplied-connection` use it.
  - The current projection derives `9a103b…`.
- 25 stored schema rows disagree with the current projection's reference fingerprints:
  `:inst`, `:seon.env/environment`, `:seon.agent/execution-handle`, `:seon.test/request`, …
- The adopted published base commit `6ab5cca9…` holds the same split, so every test
  world inherits it.

## Consequence (platform)

`seon.call-preparation/coherent-supplier` compares a supplier's argument
fingerprint with the `:seon.env/environment` schema row's fingerprint. The supplied
defaults for `:seon.db/db` and `:seon.db/connection` are therefore refused as
incoherent. With the current fingerprint instead, `:my.program/context` and
`:seon.agent/id` are refused. No single fingerprint can satisfy both halves.

With no supplied default, no call is prepared. Every elided connection or database
reaches its callee missing. Live repro on an MCP branch of default: `(my.plan/plan)`
fails with "my.plan/plan refused argument count … got an argument count of 0". This
accounts for the arity-0 part of census class J
(red-baseline-census-2026-09-25b.md) in call_preparation_test, instrument_test and
issue_test.

## Fix direction

When a definition's fingerprint changes, re-derive the shape of every row whose
reference closure contains it: schema rows and function arity rows. The projection
already carries `:seon.schema.projection/reverse-schema-dependencies` (schema.clj:985).
The owner is publication (program.cljc / cluster publication). Then republish or nuke
default so the stored rows converge.

Class regression in place:
`seon.call-preparation-test/the-shipped-database-suppliers-are-coherent-in-the-executing-program`.
The publication owner should add its own regression: after a dependency edit, no two
shapes may share a reference form.
