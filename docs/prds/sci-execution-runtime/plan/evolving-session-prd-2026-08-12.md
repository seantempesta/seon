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
(rulings 1–36).

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

Two enabling changes Option 1 needs (Sol, with file:line):
`generated-run?` currently classifies any run with a system form as forever
generated (`src/seon/cluster/work.clj:570-591`) — the state transition must
become `generate → call`; and plan publication must append after the
existing prefix instead of ordinal zero (`src/seon/cluster/loop.clj:1604-1614`).

## Owner rulings on this document (2026-08-12 evening round)

- **D1 RULED — pure closure, with the declared-render gate for growth**: V1
  generates the task arc's explained closure only. Any context beyond the
  closure is admitted by ONE derived signal: the shape carries a declared
  render function (the existing "important schemas declare their renders"
  ruling used as selection), nearest-first to the cap. No hand lists;
  "check messages" derives from the run's trigger fact. Context grows by
  the agent ACTING (requiring namespaces generates their dirs next wake).
- **D2 RULED — the demonstration is the agent authoring a render function
  for its own namespace-state** (schema + defn + render declaration + its
  output appearing in context, feeding root's tiles): real, wanted, unique
  per agent, nothing to retract; the my.run usage test stays the
  suite-gated source of the arc's SHAPE, retargeted to this content.
  Discipline is taught by comments narrating intent ("; scratch first";
  "; schemas as metadata make it a program fact") — never staged mistakes;
  the ONE real error exchange remains the wrong-call contract violation
  (errors-as-values, live). Deliberate-failure theater stays dead.
- **T2 RULED — pending page, settle at wake**: the page pulls for the user
  now; the agent pulls at wake; ruling 36 intact.

## Remaining decisions for markup (D3–D5)

- **D1 — Demand-pull generation (Fable's principal divergence,
  recommended).** Invert the landed supply-push (emit every dependency-ready
  candidate in the pull) to: fix the ACTION ARC first (the task message →
  the demonstration → the disposition), emit only ITS explained-set closure.
  One inversion = the survey-loop fix + the lean-context fix + a cost fix;
  the ablation evidence (demonstration load-bearing; discovery-only variants
  never acted) supports it. Discovery beyond the arc's closure stays
  one doc away, never pre-paid.
- **D2 — Demo artifacts are real work; the usage flag must not replicate.**
  Embrace the walkthrough's defns as system-authored facts with provenance
  (never retracted, never sandboxed) — but Fable caught the landed demo
  stamping `^{:seon.test/usage true}` into EVERY agent's namespace,
  advertising a canonical demonstration per agent nobody declared.
  Recommended: the usage flag lives only on the shipped my.run walkthrough
  test; the per-agent replica carries plain deftest metadata.
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
