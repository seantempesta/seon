---
type: prd
status: draft
tags: [prd, render, agent, context, runtime]
---

# The evolving session: one generator from birth to every wake

For owner review before ANY implementation (ruling 36's gate). Synthesized
from two independent tracks that converged without seeing each other:
[Sol's live exploration](../research/evolving-session-exploration-2026-08-12.md)
(verbatim T0/T1/T2 probes on a running cluster, priced placements, eight
defects filed) and
[Fable's fresh-context review](../research/evolving-session-fable-review-2026-08-12.md)
(the eight named unknowns answered; verdict: the design is sound). Parent
design: [self-generating-context-prd-2026-08-11.md](self-generating-context-prd-2026-08-11.md)
(rulings 1–37).

## The model in one paragraph

One function — `generate(pull, retained-history) → next (comment, form)
entries` — runs at every moment the session needs to move: at creation the
history is empty so it emits the whole episode; at a message wake it emits
only the delta (the since-basis listing, the new reads, the provenance
comments); at a passive data change it emits nothing into settled history
until the next wake. Every emitted form executes for real through the
ordinary run fold (same reader, same eval, same receipts as agent replies);
entries append and never mutate; teaching erases itself because a closed gap
generates nothing.

## Settled by convergence (both tracks, independently)

**Generation runs as a pre-prompt `:generate` phase in the agent's turn
proc, inside the wake's run** (Sol's Option 1; Fable's answer to unknown 1 —
which found the landed T0 code already does this and it needs only
extension). The render proc stays read-only forever. At T2 (root writes a
plan, no message) the PAGE may show the pending block immediately, but the
agent's settled history gains the form/value at its next wake — the only
shape preserving ruling 36 literally. Sol's Option 2 (render-proc append) is
rejected as untruthful history; Option 3 (refresh runs) is rejected unless
ruling 36 is ever explicitly narrowed.

The two enabling changes Option 1 needed are now landed. Held generated runs
derive `:resume`, `:generate`, or `:call` from stored facts
(`src/seon/cluster/work.clj:570-650`), and `plan-call` offsets provider-reply
ordinals by the existing generated-form count
(`src/seon/cluster/run.clj:640-698`).

## Owner rulings on this document (2026-08-12 evening round)

- **D1 RULED — pure closure, with the declared-render gate for growth**: V1
  generates the task arc's explained closure only. Any context beyond the
  closure is admitted by ONE derived signal: the shape carries a declared
  render function (the existing "important schemas declare their renders"
  ruling used as selection), nearest-first to the cap. No hand lists;
  "check messages" derives from the run's trigger fact. Context grows by
  the agent ACTING (requiring namespaces generates their dirs next wake).
- **D2 RULED, as governed by ruling 37 — spec-first owning-namespace
  render**: the demonstration uses the existing mechanism. The agent authors
  one function in its own namespace accepting the existing `:seon.ns/ns` unit
  and returning `:seon.render/ai`. The landed owning-namespace contract-fit
  chain selects it before the schema default or floor, so this is how the
  agent controls what other agents' walks see of its namespace. Comments
  narrate the data model; every needed named spec precedes the function, and
  the function is written once. There is no invented status value, schema
  registration exchange, scratch definition, or redefinition. The stable
  `my.run` usage test remains the suite-gated byte authority for the retargeted
  arc. The one real error exchange remains the wrong-call class-shaped contract
  violation; the test is declared, the correct AI value is observed, and
  completion is delivered to the requester.
- **T2 RULED — pending page, settle at wake**: the page pulls for the user
  now; the agent pulls at wake; ruling 36 intact.

## Remaining decisions for markup (D3–D6)

- **D3 — Zero turns force-settles `:wait` with a typed budget-exhausted
  condition** (Fable's unknown-4 answer): the episode ends loudly as data,
  the requester sees why, nothing silently stops. Currently derivation-side
  only; the agent-experience half is undesigned without this ruling.
- **D4 — Corrections are re-observations at a newer basis, never prose**
  (Fable's unknown-3 answer): a wrong/misleading earlier entry is superseded
  by appending the same read freshly derived; renders fixed later simply
  produce better appends. No mutation, no apology entries; convergence
  follows from gap-closure.
- **D5 — One generation pass per wake with an incremental fold** (Fable's
  cost divergence): the landed `next-entry` re-pulls and re-runs the episode
  derivation per generated form (O(n²)); carry frontier/explained/shown-bases
  as an accumulator on retained history and emit the ready suffix in one
  pass. Slow-first-implementation ≠ bad idea — this is the named fast shape.
- **D6 — Delta forms need zero new arities** (Fable's unknown-7 answer): the
  one `since`-database mechanism plus per-new-id reads covers every delta;
  callables do not grow delta options. (Sol's probes used exactly this
  shape live.)

## What you'll read in the reports (the evidence)

Sol's report carries the VERBATIM goods: the live T0 episode as generated
today (plus the exact three boundaries preventing a complete one), the T1
delta with its provenance comment and self-erasure proven, and the T2
passive change. Fable's report argues each unknown to ground. Eight defects
are filed from Sol's probes; the printer identity-hash residue and the
stale-pin pair it met are already known classes.

## After your markup

Implementation order (gated on this document's approval): D5's fold and
D1's inversion land together in the generator owner; the `generate → call`
transition and prefix-append changes enable T1; the T2 page-pending block
rides W3's tiles; the drive re-runs on the demand-pull episode and the
MINIMUM numbers re-measure (expect below HALF).
