---
type: issue
status: resolved
severity: friction
tags: [issue, render, agent]
---

# A nil render key paints an error card instead of omitting

Found 2026-07-28 while grounding the owner's omission-consistency question
(read-only audit; evidence file:line verified at that read).

## The class

"Key absent" and "key present with nil" must be indistinguishable for
`:seon.render/*` declarations — the router already makes them so
(`src/seon/render.clj:92-94,109-115`: nil fails `declaration?`, so nil ==
absent for `kinds` and `render`). Durably nil is unrepresentable
(`src/seon/schema/datahike.cljc:97-101` refuses `:maybe` on stored
attributes; `:seon.render/projection` is bare `:qualified-symbol`,
`src/seon/schema/render.edn:32`). But two in-memory sites test key
PRESENCE instead of `declaration?`, so an in-memory-constructed unit with
a nil-valued key diverges:

- `src/seon/render/block.clj:289` — `(contains? block kind)` admits the
  nil-keyed block, the router then refuses `::kind-not-declared`, and the
  block renders as an ERROR CARD on the page instead of being omitted.
  Inverts the documented rule at `src/seon/schema/block.edn:51-55`.
- `src/seon/render/block.clj:462` — `(contains? unit :seon.render/html)`
  treats nil as declared, so the `data-panel` generic backstop is not
  substituted and a ref-hole degrades to a text note.

Related, same audit: a projection that RETURNS nil emits an empty string
through `web/surface-html` (`src/seon/render/web.clj:155-168,189-196`) —
an empty patch has no element id, so the block's morph target disappears
from the document and later patches for that block land nowhere.

## Acceptance

Both `contains?` sites test `render/declaration?` on the value (one rule,
the router's), making nil == absent everywhere in memory too; one
regression per site. The nil-returning-projection behavior is a contract
decision (probably: omitted, while preserving the block's identified
wrapper element so later morphs still have a target) — owned by the
context-blocks omission ruling (Decision 1), not fixed ad hoc here.

## Resolution

Commit `12a5fef33` applies the 2026-07-28 owner ruling at every cited
consumer. Block selection and entity-ref fallback now call
`render/declaration?` on the value read with `get`; the generic data panel
unifies nil and absent render values and removes nil-punned render keys from
its unit fallback. A nil html projection is a successful omission rather than
malformed hiccup.

`seon.render.web/surface-html` serializes an omitted surface as an empty `div`
carrying the block's existing `:seon.render/surface-id`. The identified wrapper
therefore survives the empty state, and a later non-nil output produces a
whole-element patch for the same id.

Proof:

- `bin/test seon.render.block-test seon.render.web-test` — 60 tests, 145
  assertions, 0 failures, 0 errors.
- The changed-test gate widened through nine dependent namespaces — 147 tests,
  504 assertions, 0 failures, 0 errors.
- `bin/test` — 396 tests, 1,544 assertions, 0 failures, 0 errors.
