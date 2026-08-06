---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, agent, durability, honesty, live-drive]
---

# Keep newly loaded system Vars out of the agent desk

## Problem

Loading or referring a first-party namespace during an agent turn makes that
namespace's previously unseen system Vars look like agent-authored desk
definitions. Turn settlement persists them as `:seon.def` rows owned by the
agent, and the next prompt renders those false ownership facts.

## Evidence

Transaction `536871007` persisted these root-owned desk rows at
`2026-08-06T17:26:19Z`:

- `seon.operator.runtime/held-flocks`;
- `seon.operator.runtime/running-instances`;
- `seon.operator.runtime/root-store-holder`; and
- `seon.cluster/source-analysis-cache`.

The three runtime atoms carry `"The atom's settled value is not
store-faithful."`; the source-analysis cache is stored as `nil`. All four rows
carry `:seon.schema.admission/source :agent` and are rendered in the next exact
root context capture.

`seon.sci.eval/desk-defs` compares every intern visible after a form with the
before snapshot. Newly loaded namespaces expand that universe, so existing
system Vars can appear as changes even though the agent did not define them.

## Owner

`seon.sci.eval/desk-defs` and the definition-event/namespace ownership facts
that distinguish an authored intern from a namespace-load side effect.

## Acceptance

- Requiring, referring, or calling into a previously unloaded first-party
  namespace persists no `:seon.def` row for that namespace's existing Vars.
- An actual `def`, `defn`, or atom authored by the agent still settles through
  the desk exactly once.
- A fresh-turn regression queries every persisted desk row and proves its
  defining form and agent attribution.

## Resolution

Resolved by `11ddaba1a`. Desk settlement now selects only SCI Vars carrying
the current turn fork's `:sci/generation`, the provenance SCI stamps on
interpreted `def`, `intern`, and inherited-root writes. System host Vars copied
while a namespace loads carry no turn generation and therefore cannot become
agent desk candidates.

`only-turn-authored-definitions-settle-into-the-agent-desk` installs the live
`seon.operator.runtime` namespace during an evaluation, settles the real
terminal receipt, and observes zero desk rows. Its next form defines
`own-value`; the committed row contains that exact source and
`:seon.schema.admission/source :agent` exactly once. The complete desk gate
passed 6 tests / 24 assertions, and the adjacent evaluator gate passed 52
tests / 245 assertions.

The prior prompt output was both dishonest and ugly: it presented runtime
custody atoms as agent work and then rendered false unrestorable notices. The
generation-derived attribution removes those rows rather than cosmetically
changing their rendering.
