---
type: issue
status: open
severity: friction
tags: [issue, render, runtime, schema]
---

# Effect receipts have no render producers

## Problem

`:seon.effect/receipt` is a durable entity schema with refs to its run and
owner, but it declares neither `:seon.render/ai` nor `:seon.render/html`
(`resources/seon/schemas/seon.effect.edn:17-48`). When receipts exist, the
walk's bidirectional ref traversal makes them neighbours of the agent/run, and
the pulled receipt falls through to the structural value floor
(`src/seon/render/walk.clj:351-423`; `src/seon/render.clj:115-133`).

The raw floor is bounded but not an agent-facing receipt. It leads with
serialized request/result EDN and renders run/owner as opaque `#:db{:id ...}`
maps.

## Evidence

The live `default` cluster had no receipt row, so the audit passed a
representative value matching the declared entity shape directly through
`seon.render.value/render-ai`; no database write was performed:

```clojure
{:seon.effect/request-edn "{:my.fs/path \"README.md\"}",
 :seon.effect/result-edn "{:my.fs/content \"...\"}",
 :seon.effect/id "effect-1",
 :seon.effect/owner #:db{:id 11980},
 :seon.effect/run #:db{:id 11990},
 :seon.effect/settled-at "2026-08-03T18:00:00.012Z",
 :seon.effect/form-ordinal 2,
 :db/id 12000,
 :seon.effect/ordinal 0,
 :seon.effect/duration-ms 12,
 :seon.effect/opened-at "2026-08-03T18:00:00Z"}
```

The floor's one admitted print tree and two decorations are the intended honest
fallback (`src/seon/render/value.clj:168-223,399-423`). The missing producer
pair on an important durable receipt is the defect. Full census:
[[render-coverage-audit-2026-08-03]].

## Owner

`seon.effect` and `resources/seon/schemas/seon.effect.edn`.

## Acceptance

- `:seon.effect/receipt` declares named AI and HTML producers.
- Open, settled, and interrupted receipts render capability/disposition,
  duration, and run/form identity in domain terms.
- Request/result payloads are bounded and secondary, with a drill handle when
  blob-backed; raw database entity IDs do not lead either face.
- A recurring walk test commits one receipt and proves both projections select
  the declared producers through the ordinary agent/run neighbourhood.
