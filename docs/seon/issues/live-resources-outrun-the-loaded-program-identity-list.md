---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [program-graph, test, schema, wave/program-graph-indexing]
---

# A schema-resource edit without adoption makes every in-process test run refuse

## Problem

`seon.program/authored-shapes` (`src/seon/program.cljc:146`) derives program-row
shapes from `seon.schema.edn/packaged-forms` — the declaration resources **as
they are on disk now**, deliberately keyed by
`seon.schema.edn/declaration-stamp` so a resource edit is a cache miss by
construction. It pairs those live resources with a **loaded** code constant,
`seon.program/identity-attributes` (`src/seon/program.cljc:11`). The two can
disagree: a shell or editor write that renames an identity attribute in the EDN
is live immediately, while the paired `def` is only live after adoption.

When they disagree, `derived-shape` refuses ("A program identity attribute
declares no row schema"), `shapes` refuses, and so does everything downstream:

    seon.test.runner/program-digest -> seon.test.runner/provenance
      -> {:seon.error/kind :seon.test.run/unavailable}

Both `seon.test/run` arities return that value without running anything, so
**every lane's in-process proof in the shared development JVM is refused at
once**, with a message that names a schema declaration and not the mismatch.

## Evidence

2026-09-17, default PID 30138, MCP jvm mode, while the publication-manifest
lane's rename of `:seon.fn.file/path` → `:seon.fn.file/relative-path` sat
uncommitted in the working tree (both `resources/seon/schemas/seon.fn.file.edn`
and `src/seon/program.cljc` written; only the resources live):

    seon.program/identity-attributes (loaded)
      => [:seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym
          :seon.fn.file/path :seon.lint/id]
    (contains? (seon.schema.edn/packaged-forms) :seon.fn.file/path)          => false
    (contains? (seon.schema.edn/packaged-forms) :seon.fn.file/relative-path) => true

    (seon.program/shapes)
      => refused, :seon.program/identity-attribute :seon.fn.file/path,
         :seon.program/missing-attributes [:seon.program/row-schema]
    (seon.test.runner/provenance (seon.db/db (seon.operator/connection "default")))
      => {:seon.error/kind :seon.test.run/unavailable
          :seon.error/message "Test provenance unavailable: A program identity attribute declares no row schema."}

Adopting `src/seon/program.cljc` clears it, so the state is transient — but it
is reachable by an ordinary shell write and it disables every lane in the JVM
until someone diagnoses it, which is exactly what happened here.

## Direction

The identity list and the row declarations are one fact read from two places
at two different times. Derive the identity attributes FROM the same forms
`authored-shapes` just read (an identity attribute is one declaring
`:seon.program/row-schema`), so the list cannot outlive the resources it
describes and a rename is a single edit. Failing that, the refusal must name
the mismatch — the identity the loaded list holds and the forms' stamp — rather
than reporting a healthy declaration set as broken.
