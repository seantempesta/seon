---
type: issue
status: resolved
severity: friction
tags: [issue, effect, render, schema, class/n1, wave/render-receipt-producer]
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

## N1 disposition — 2026-08-12

Still open outside this lane. Add named AI and HTML producer properties to
`:seon.effect/receipt` in `resources/seon/schemas/seon.effect.edn`, implement
the bounded domain projections in `seon.effect`, and prove ordinary
`seon.render/render-call` selection from one committed receipt.

## Verified at HEAD (2026-09-16, N1 verification)

**RESOLVED.** `resources/seon/schemas/seon.effect.edn:22-26` now declares
the pair on `:seon.effect/receipt`:

```clojure
:receipt
[:map
 {:seon.db/attributes true
  :seon.render/ai seon.effect/render-ai
  :seon.render/html seon.effect/render-html}
 …]
```

Probed live on `default` (pid 69622) with a complete render request and the
note's own representative receipt (insts where the schema declares insts).
AI:

```text
Effect effect-1 · run unknown, form 2, effect 0 · returned in 12 ms.
Request (~7 tokens): {:my.fs/path "README.md"}
Result (~6 tokens): {:my.fs/content "..."}
```

HTML (extract):

```clojure
[:article {:class "seon-family-entry seon-effect-receipt-entry"}
 [:h3 "Effect effect-1"]
 [:dl [:div [:dt "Run"] [:dd "Unknown"]]
      [:div [:dt "Form / effect"] [:dd "2 / 0"]]
      [:div [:dt "Disposition"] [:dd "returned"]] …]]
```

Capability/disposition, duration and form identity lead both faces in
domain terms; payloads are secondary and carry an estimated-token size
(`src/seon/effect.clj:48-50`); raw `#:db{:id …}` no longer leads either
face; `receipt-state` derives disposition from terminal attributes rather
than a stamp (`src/seon/effect.clj:40-46`).

One adjacent defect was observed while probing and is recorded on
[my-background-poll-costs-290-tokens-per-polled-result](my-background-poll-costs-290-tokens-per-polled-result.md):
when the payload is large the producer's whole face, identity line
included, is replaced by a single elision value.
