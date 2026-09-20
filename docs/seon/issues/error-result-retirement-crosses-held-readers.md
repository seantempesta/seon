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

**Superseded construction requirement:** the later 11:35 and 12:05 rulings
keep the constructor pure and its raw object in flight. Result preparation
belongs to `seon.error/prepare`, driven by explicit request keys. The older
constructor-input dependency below is resolved by that ruling; the held
reader retirement remains pending.

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

## Recording callers and selected-overlay dependency — 2026-09-22

One deferred caller conversion is authorized and required when publication
releases `src/seon/cluster.clj`: its fault-committer block at approximately
3216–3233 must supply `:seon.db/connection`, `:seon.render/profile` and the
available projection to `seon.error/prepare`, then remove its size-gated
`blob/stage!`. Preserve the blob owner's publication/transaction guarantee.
Do not modify that held block in the error lane. The evaluation recorder is
the currently authorized caller; the pure constructor remains unchanged.

Before any new implementation, the selected fast baseline exits 1 while
compiling `seon/error.clj:279:41`: `No such var:
schema.datahike/database-attributes-core-in`. No tests execute. The restored
error owner already calls that Var. `git show HEAD:src/seon/schema/datahike.clj`
has no definition; the held working-tree diff adds it. Status is `MM` for
that schema owner. This is a source-verified dependency on bridge step 2,
not a test assertion failure or evidence against the recording design.
The required overlay cannot include held changes or revert restored callers
to the schema implementation being retired. Resume the selected baseline
after that dependency lands; no new design choice is needed.

## Atomic retirement resume — 2026-09-23

The earlier schema dependency is resolved. The new assignment requires all
four old attributes and their consumers to retire atomically. The test-system
sources and schemas named above are clean at this check. A fresh whole-source
search exposes another concurrently edited producer: `src/seon/issue.clj`
(`git diff --numstat`: 39 additions, 21 deletions). Its diagnostic maps supply
`:seon.error/offending` at lines 88, 148, 214, 546, 562, 577, 606, 621,
641, 802, 1048, 1081, 1095, 1110, 1125, 1139, 1154, 1222, 1238, 1342,
1357 and 1405. These are 22 producer sites, not 22 observed test failures.
They must stop emitting that key in the atomic retirement; their existing
`:seon.error/diagnostic-offending` inputs carry the same raw observation into
the pure constructor. `seon.error.refusal/diagnostic` currently preserves
supplied domain members, so removing the schema declaration alone cannot
retire these emissions. The lane stopped before production edits rather than
alter a concurrently edited source or silently discard members.

The separately authorized deferred fault-committer conversion now lives at
`src/seon/cluster.clj:3025–3033`: it reads `data-size` and `data-edn` from
`prepare`, then conditionally stages `data-content`. This file is also dirty.
Its redesign owner must supply connection/profile on the request and use the
shared result preparation instead of the size-gated staging. This existing
issue remains the single follow-up for that conversion.

## Accretion resumed — blob encoding decision, 2026-09-23

The latest ruling explicitly permits keeping every legacy write unchanged,
so the held producers no longer block accretion. Their atomic retirement and
the cluster conversion remain the follow-up above.

The recording seam still needs a declared representation for arbitrary
objects in the blob. `seon.blob/put!` accepts UTF-8 text; `put-binary!` accepts
an input stream. Neither encodes objects. The existing `store-faithful-edn`
at `src/seon/blob.clj:43` returns text only when the EDN round trip preserves
value, class and metadata. The pure probe in
`docs/prds/steward-platform/research/error-result-blob-probe-2026-09-23.clj`
returned `:map true`, `:atom false`, `:function false`, `:object false`.
The same JVM loaded error, SCI evaluation and print successfully (exit 0).
This is an encoding decision, not a held-file or test failure. SCI binding
can preserve the live object; it does not define its durable bytes.

The landing note prices exactly three choices. No new codec, lossy substitute
or production change was introduced while that guarantee is undecided.

## Representation ruled; accretion implemented — 2026-09-23

The owner selected the printer representation precisely: the blob contains
the value printer's complete rendering, the entity contains its rendering
under the supplied profile, and only SCI retains the live object. No faithful
EDN probe or additional object encoder belongs in this path. That resolves
the representation decision above.

`seon.error/prepare-result` is the shared result preparation function called
by `prepare` and the SCI evaluation recorder. Its two new stored attributes
are `:seon.error/result-id` and `:seon.error/shown`; the existing blob digest
attribute remains `:seon.error/data-blob`. Legacy writes remain in place.
Verification is currently blocked by the canonical fixture's missing new
declarations, recorded in the existing
[fixture issue](canonical-fixture-retains-old-function-contracts-after-adoption.md).

This issue remains the single atomic-retirement follow-up: convert the 22
issue producers, the fault committer's staging hunk and the other inventoried
readers, then remove all four legacy declarations and writes together once
the held sources are released. No producer was partly retired here.
