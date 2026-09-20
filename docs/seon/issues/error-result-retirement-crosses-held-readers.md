---
type: issue
status: open
severity: blocker
tags: [issue, schema, database, sci]
---

# Error result retirement crosses held database and test readers

The owner rulings of 2026-09-22 at approximately 10:30 and 10:40 local in
`docs/prds/steward-platform/plan/unsettled.md` require one result identity,
the existing SCI result binding, a blob reference and printer-produced shown
text. The constructor's returned error map must also be its stored map.
The four retired attributes must be removed with their readers in one slice.

The error-family lane's read-only probe finds active consumers in paths
explicitly held by bridge step 2 and gate restructure:

| Held path | Current use and required conversion |
|---|---|
| `src/seon/db.clj:3320,3389` | Supplies raw conflict/attribute in `:seon.error/offending`; hand the actual value to result construction. |
| `src/seon/db.clj:3405` | Reads that member for a sentence; consume printer-produced shown text. |
| `src/seon/test/accretion.clj:85` | Supplies the raw contract; construct its result through the shared owner. |
| `src/seon/test/runner.clj:744,2024,3822,3863` | Supplies raw marker, command or readiness values; construct their results through the shared owner. |
| `src/seon/test/runner.clj:4255` | Reads the raw member for a launch request; obtain the actual value through the result/blob mechanism, preserving confirmation evidence. |
| `resources/seon/schemas/seon.test.runner.edn:91,103,114` | Declares the retired raw member; declare the new stored result members. |
| `resources/seon/schemas/seon.test.accretion.edn:232` | Requires the retired projected member; convert this required promise in the same schema publication. |

These are source-verified dependencies, not a claimed test failure. Removing
declarations alone leaves required references unresolved and readers without
their evidence. The assignment explicitly requires stopping at held paths.
Release or coordinate these sites before atomic retirement; do not introduce
a compatibility member or writer-side drop.

`seon.sci.admit/result-handle` at line 695 derives the symbol. The actual
existing `sci/intern` owner is `seon.sci.eval/bind-result!` at lines 534–550.
Reuse that object-binding path. No production changes or test execution
were performed on this resume.

Acceptance remains two armed canonical regressions: an agent turn's
contract refusal exposes the actual value through `result/e<id>` and stores
the blob and printer text; a large nested value has capped shown text and
validates as stored. The walk must render the printer's shown text.

## Additive slice ruling and explicit-input dependency — 2026-09-22

The orchestrator authorized accretion first: retain all four old members
unchanged and retire them with held readers later. The retirement hold no
longer prevents adding attributes. A separate source-verified dependency
remains before the additive producer can promise storage and SCI binding:

- `seon.error.refusal/diagnostic` accepts observation data only. Its callers
  do not supply a connection, SCI context, render profile or result writer.
- `src/seon/instrument.clj:376` constructs the actual contract refusal with
  diagnostic data; `boundary-refusal` at line 682 carries a projection and
  caps but no connection/context. An agent-turn regression must exercise this
  path, not a manually enriched error map in a fixture.
- `seon.blob/put!` at line 342 accepts a connection and string content;
  `put-binary!` accepts a connection and input stream. Neither accepts an
  arbitrary object without a serialization decision. `store-faithful-edn`
  returns no text when EDN cannot preserve value, class and metadata.
- `seon.blob` requires the leaf constructor itself and `seon.db`;
  `seon.db` also requires that leaf. A direct leaf-to-blob require creates
  a namespace cycle. The printer requires schema, which also requires the
  leaf. SCI object binding remains in the existing eval owner.

Supplying explicit operations from an already acquired environment can
avoid the cycle, but those operations must reach the constructor through
the actual producer's inputs. A dynamic binding, global connection lookup,
or deferred writer-side enrichment would not satisfy the ruled constructor
guarantee and the carried-input law. No such mechanism was added. The
landing note records three scope choices for this remaining decision.
