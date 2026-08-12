---
type: prd
status: draft
tags: [prd, render, agent, context, runtime]
---

# The evolving session: one generator from birth to every wake

This decision record opened ruling 36's owner-review gate and now records its
final disposition. It was synthesized from two independent tracks that
converged without seeing each other:
[Sol's live exploration](../research/evolving-session-exploration-2026-08-12.md)
(verbatim T0/T1/T2 probes on a running cluster, priced placements, eight
defects filed) and
[Fable's fresh-context review](../research/evolving-session-fable-review-2026-08-12.md)
(the eight named unknowns answered; verdict: the design is sound). Parent
design: [self-generating-context-prd-2026-08-11.md](self-generating-context-prd-2026-08-11.md#owner-rulings-1-50)
(rulings 1–50). The executable source ownership, dependencies, and acceptance
gates live in
[the implementation companion](evolving-session-implementation-2026-08-12.md);
where this decision record's earlier wording differs, that companion governs.

## The model in one paragraph

One function — `generate(pull, retained-history) → next (comment, form)
entries` — runs at every moment the session needs to move: at creation the
history is empty so it emits the whole episode; at a message wake it emits
only the delta (the since-basis listing, the new reads, the provenance
comments); an error addressed to the agent does the same; at a passive data
change it emits nothing into settled history until the next wake. Every
emitted form executes for real through the ordinary run loop (same reader,
same eval, same receipts as agent replies); entries append and never mutate;
a gap already closed by retained history generates nothing.

## Settled by convergence (both tracks, independently)

**Generation runs as a pre-prompt `:generate` phase in the agent's turn
proc, inside the wake's run** (Sol's Option 1; Fable's answer to unknown 1 —
which found the landed T0 code already does this and the same owner evolves
to every wake). The render proc stays read-only forever. At T2 (root writes a
plan, no message) the PAGE may show the pending block immediately, but the
agent's settled history gains the form/value at its next wake — the only
shape preserving ruling 36 literally. Sol's Option 2 (render-proc append) is
rejected as untruthful history; Option 3 (refresh runs) is rejected by ruling
42's re-observation contract.

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
  ruling used as selection), nearest-first to the
  `:seon.config.bootstrap/beyond-closure-token-budget` cap (V1 default `1024`
  estimated tokens, whole entries only). No hand lists;
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

## Final owner rulings (38–44)

- **Ruling 38 — the environment carries, never contains.** `seon.env` carries
  the projection, database basis and connection, and agent scope that
  derivation needs. The opening context is derived per agent from the walk;
  it is never stored in or on the environment.
- **Ruling 39 — root's preview is derived.** Root's own gap-closure walk over
  the agent supplies the preview: the newest block remains in membership and
  unshown messages appear since the retained shown basis. No fixed preview
  depth survives.
- **Ruling 40 — test results become facts before this build.** The one runner
  commits the declared test-run/result attributes. Dependency status:
  `[landed]` in commit `9648aed33`; the integration gate now reviews that
  return before evolving-session phases consume it. No phase builds on a stub.
- **Ruling 41 / D3 — zero turns force-settles `:wait`.** A distinct typed
  budget-exhausted condition is delivered to the requester, the run closes
  without a provider call, the agent stays alive, and the next wake shows why
  the episode ended.
- **Ruling 42 / D4 — corrections are re-observations.** The same read appends
  at a newer basis; newest basis wins in blocks and old bytes never change.
  There are no refresh runs, apology/meta entries, or mutation.
- **Ruling 43 / D5+D6 — the incremental pass and since deltas land as
  specified.** One invocation-local generation state replaces the per-form
  re-pull. The only delta mechanism is a `since` database plus per-new-id
  current-database reads; no callable gains a delta arity.
- **Ruling 44 — errors wake.** Messages and errors addressed to the agent open
  runs; every other data change remains passive until the next wake.

## Subsequent parent rulings (45–50)

- **Rulings 45–46 — rebirth and fact-closed gaps.** Generation from current
  facts plus empty history must produce a compact valid episode beside the
  still-queryable old history. Functions, declared renders, and green tests
  in the agent's namespace demonstrate their lesson and suppress reteaching.
- **Ruling 47 — survivable meaning is fact-backed.** Durable task meaning must
  be facts with a declared current-state render; prose and history replay are
  not recovery mechanisms. Ruling 49 supersedes the earlier plan-status
  example.
- **Ruling 48 — rebirth-first is universal.** Every new shape must render its
  compact present meaning from current facts alone. The evolving-session
  drive therefore includes a real reborn episode, not only an incremental
  wake.
- **Ruling 49 — the todo is the one task system.** Derived obligations union
  with authored item facts, and the declared current-state render omits
  completed items. `my.plan`, an agent-plan relationship, and a plan-status
  family are retired unbuilt.
- **Ruling 50 — effectful values are old by default.** Rebirth represents an
  effectful value from its last receipt and marks it old/not-current. Only a
  future declared read-only metadata tag may authorize re-execution, with the
  never-lie falsifier landing alongside its first declaration.

## What you'll read in the reports (the evidence)

Sol's report carries the VERBATIM goods: the live T0 episode as generated
today (plus the exact three boundaries preventing a complete one), the T1
delta with its provenance comment and gap closure proven, and the T2
passive change. Fable's report argues each unknown to ground. Eight defects
are filed from Sol's probes; the printer identity-hash residue and the
stale-pin pair it met are already known classes.

## Implementation authority

The design is ruled complete: 50 rulings, zero open owner choices. The one
current ordering is maintained in
[the program plan](README.md#the-plan-2026-07-26--this-section-owns-implementation-order),
and the exact phase owners and exits live in
[the implementation companion](evolving-session-implementation-2026-08-12.md#phase-ownership-and-dependency-order).
This parent record adds no second sequence. Ruling 40's test-result facts,
the integration gate, rebirth proof, live drive, and the implementation
phases remain implementation work rather than design questions.
