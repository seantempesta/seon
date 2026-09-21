---
type: issue
status: open
severity: cleanup
tags: [issue, schema, database, flow, class/n11, class-kill, wave/class-kill-queue]
---

# Reject readerless rows and duplicate mechanisms at publication

## Problem

Deleted and unified paths leave behind rows with no reader, unused caches,
parallel codecs, hand-rolled substitutes, and duplicate registrations. Nothing
at publication proves that a public declaration or mechanism is reachable
through its surviving owner.

## Evidence

Current open members carry `class/n11` and are derived with
`bin/issues-index --class class/n11`.

## Owner

Program/schema publication and the one owners of the duplicated mechanisms.

## Acceptance

- Publication derives reader closure from the program graph and refuses a
  public row or callable mechanism with no reader.
- A semantic operation has one declared owner; alternate codecs, caches,
  rosters, and bypass writes have no construction path.
- The deletion regression asserts absence of the superseded facts/functions,
  not behavior of the deleted path.

## Folded members — 2026-09-21

The five notes below were instances of this class and are archived
(`status: superseded`, `superseded-by` this note). Their full evidence
remains readable at `archive/<name>.md`; the membership query
(`bin/issues-index --class class/n11`) no longer returns them, so the
member list is stated here rather than derived.

| Member (in `archive/`) | Claim | Current file:line |
|---|---|---|
| `context-capture-prompts-bypass-the-blob-splitter.md` | The exact rendered prompt is written straight to `:seon.context.capture/prompt` instead of through the one `seon.blob` payload owner, so the blob threshold cannot move it out of Datahike's indexes | `src/seon/context.clj:497`, `:530` (no `seon.blob` reference in the namespace) |
| `error-class-catalog-and-renderers-disagree.md` | A hand-maintained error-class catalog is a second representation beside the declared Malli error schemas and the renderers that select on them | see paragraph below |
| `failover-adds-an-uncaptured-system-context-fragment.md` | A backup provider attempt still receives a separately assembled `:seon.ai/system` fragment beside the one captured prompt — a second context-assembly path | `src/seon/turn.clj:4399`; `src/seon/ai.clj:706` |
| `flow-has-no-read-set-control-and-a-hand-rolled-egress.md` | Seon hand-rolls weaker substitutes for two shipped `core.async.flow` mechanisms | see paragraph below |
| `value-floor-residue-duplicate-cursors-and-marker-hand-lists.md` | One of its four findings survives: two implementations of the reverse-ref window | `src/seon/render/web.clj:583-625` vs `src/seon/render/walk.clj:247` |

**Folded evidence — the catalog is not the classification.** From
`error-class-catalog-and-renderers-disagree.md`: current owner rulings identify
an error map structurally through its declared Malli schema; neither a kind
stamp nor a catalog entry is the classification mechanism. The member records
that a bounded error lane converted nine contract-kind assertion sites to
schema validation and that a four-namespace fast run still found retired
reader/evaluation/missing-supplied-key assertions, a string-valued function
lookup, and value-printer bounds that do not hold (one scalar render measured
24,621 bytes against an 8,192-byte expectation). That is this class in its
sharpest form: a second catalog of a fact the schema already declares. Exact
table in
[the error-family landing note](../../prds/steward-platform/research/error-family-1a-2026-09-19.md).

**Folded evidence — a dependency mechanism with zero adoption is the same
disease as a duplicate one.** From
`flow-has-no-read-set-control-and-a-hand-rolled-egress.md`: our pinned
`core.async.flow` ships `::flow/input-filter`, a predicate of cid controlling
a proc's next read set
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:229-233`),
with zero hits in `src/`; Seon's substitute is `CountedDroppingBuffer`
(`src/seon/flow.clj:525-553`), a drop policy rather than read-set control.
`::flow/out-ports` (`flow.clj:221-227`) is likewise unadopted and the SSE exit
leaves the graph through `async/mult` + `tap`/`untap` outside flow's lifecycle
(`src/seon/flow.clj:717-764`). `::flow/cast` and `:signal-select` are unadopted
and `src/seon/flow.clj:195` strips `::flow/casts`, so no proc can receive a
broadcast. This widens the class's acceptance: the publication check must
refuse a hand-rolled substitute for a mechanism the pinned dependency already
owns, not only a readerless row left by a cut.

## Folded member — corrected claim, 2026-09-21

`agent-html-still-uses-the-retired-transcript-assembler.md` is archived here
with its central claim CORRECTED, not confirmed. The resource it cites
(`resources/seon/schemas/seon.cluster.agent.edn:1-9`) no longer exists and the
agent entity's HTML projection is now `seon.cluster.agent/render-identity-html`
(`resources/seon/schemas/seon.agent.edn:17`). What survives is this class
exactly: `seon.render.transcript/render-session-html` is still DEFINED at
`src/seon/render/transcript.clj:864` with zero callers in `src`, `test`,
`script` or `resources` — a readerless public mechanism left by a completed
cut. Its hand-maintained entry-kind roster and independent candidate queries
are still in that namespace, and `:seon.render/form` is still declared and
selected on it (`resources/seon/schemas/seon.message.edn:29`, `:66`), which
[the retired form-projection note](retired-form-projection-still-declared-and-selected.md)
owns separately and which remains open.
