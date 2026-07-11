---
type: prd
status: active
tags: [prd, agent, index]
---

# The minimal-context rebuild — deprecate everything, rebuild from the core

**The point (owner, 2026-07-11, sharpened): the OLD context tree is
deprecated wholesale — every legacy block, the skills system, and the shipped
system text. Nothing is "audited back in." The minimal core is the new base,
and capabilities return ONLY by being rebuilt on it: colocated, correct,
helpful, with drive evidence.** The live tile will come back; the 14k
`agents` block will not — unless something rebuilds a small, evidenced
replacement for whatever it was actually doing.

**Why (the poison principle — this is NOT about tokens):** bad information
in context poisons behavior, the old tree is an initial guess plus years of
pile-on (bloated, self-repeating, probably self-contradictory), and
attributing a bad drive to a specific line is intractable. The asymmetry
that decides everything: **omission is recoverable and attributable** (an
agent hits a wall, the transcript shows the wall, one rung adds one
fragment), **inclusion is neither** (poison hides in the pile). So the
inclusion bar is high and the deletion bar is zero.

Evidence ledger: `evals/runs/2026-07-10-minimal-buildup/README.md`.
Origin plan: `~/.claude/plans/lazy-splashing-rainbow.md`. Standing rules:
colocation (a block carries its own teaching; context is purely additive —
removing a block removes every word about it), variant teaching gated on DB
datoms, config→DB at boot, NO symptom-side hacks, three testing surfaces
only, implementation = opus agents against written specs.

## The inclusion bar (what it takes to enter the context, forever)

Every fragment — a system-text line, a block header sentence, a card policy —
enters only with all three:

1. **Colocation** — it lives in/with the block whose state it describes, and
   renders exactly when that state holds (the repl-mode fragments and the
   plan block's empty-state teaching are the worked examples).
2. **A ledger row** — added at a drive boundary under a variant tag, with at
   least one drive on each model class (iterate on Spark, gate on DeepSeek)
   showing the target behavior moved or a wall came down, and rung-0 fab
   metrics not regressing.
3. **A provenance note** — colocated with the fragment in the source
   (`;; evidence: <ledger row / rung>` beside the teaching string), so the
   line→test mapping is greppable and a provenance table can be DERIVED,
   never hand-maintained. This is the practical answer to "which tests cover
   which lines": we cannot attribute failures to lines after the fact, so we
   attach evidence at insertion time and never insert without it.

Corollary: when a drive shows confusion about a fragment, the loop is reword
THAT fragment → new variant tag → redrive. Two failed rewords = remove the
fragment and reconsider the mechanism (context can't fix a code problem).

## The base (rebuilt so far, ~7k fixed prefix)

| piece | ~tokens | status |
|---|---|---|
| system-text v3.1 (the `:seon.config/system-text` datom) | 470 | line-provenance table below; ONE undriven line (restart-survival, validates at rung 4) |
| `:namespaces` block | 6,400 | the tool-task floor for every model tested (no-cards probe RED on both); carries its own full-vs-cards/movement header |
| `:plan` block | 60–150 | rung-2 GREEN both models incl. mid-drive restart; teaches its own workflow, state-gated |
| `:transcript` block | grows, decayed | masthead + mode-gated repl fragment; the interleaved eval log |

Rungs closed: **0** (REPL reliability; fabrication contained/eliminated,
cross-model verdicts), **1** (namespace movement; exposed + fixed the
cross-turn current-ns core bug), **2** (planning flagship; decompose-first,
close-adjacency, resume-after-restart, answer delivery). Rung 3 (cards
suffice for correct first calls) was pre-answered by rung 0.

## The deprecation register — every legacy surface dies; some get reborn

The old tree is enumerated here so the cutover is a checklist, not a
discovery. "Rebuild" means a NEW minimal, colocated, evidenced fragment/block
built on the base — never a port of the old text.

| legacy surface | ~tokens | fate | rebuild path (if any) |
|---|---|---|---|
| shipped `ctx/system-text` def (~4–5k) | 4–5k | **DELETE** | one MINING pass first: skim for genuine mechanics not yet true anywhere; each candidate enters only via the inclusion bar. No coverage obligation — the default assumption is pile-on. |
| `agents` identity file-block | ~14,100 | **DELETE** | if identity/behavioral guidance proves needed, a future rung defines what it even is; expected outcome: a tiny block or nothing |
| `soul` file-block | ~1,900 | **DELETE** | same as above (drive law already had soul OFF) |
| `skills-catalog` + always-on skill bodies | ~2,900 | **DELETE the mechanism** | see "skills dissolve" below |
| `live-tile` block | ~2,000 | **DELETE, rebuild planned** | rung L (below) — the tile returns with colocated teaching + a human-UX win condition; the interactive plan tile (in flight) is the pattern |
| `jobs` block | ~85 | delete; rebuild if a rung shows background-job blindness | shell cards already taught job polling in rung-0 drives — likely covered |
| `plan-ledger`, `recent-verbs` (typeahead menus) | ~150 | other lane's live experiments — coordinate, exempt from this register until their arc concludes | — |
| `relevant-source` (retrieval) | var. | delete; rebuild only when a rung shows cards + current-ns are not enough for a real task | needs its own win condition |
| `findings`, `warnings`, `test-failures`, `usage`, `inventory` | small | delete; the REACTIVE ones (warnings/test-failures) are strong rebuild candidates — derived sections that render only when the state exists are colocation-native | rung W |
| `subagents` / `orphans` | small | delete; rebuild with the multi-agent rung | rung M |
| `my.kb` manual ns (pushed via home-requires) | in cards | stays reachable as a PULL reference (the agent reads it when needed); never pushed | rung 4 decides whether pushing anything is needed at all |

## Skills dissolve into the reactive context

The ideal (owner): no skills system. Its job — task-conditional teaching —
is exactly what the dynamic context already does better, in three forms:

1. **State-gated block teaching** — teaching renders when the DB state it
   describes holds (mode fragments, plan empty-state). A "skill" for tiles
   is the tile block teaching itself when a tile exists / is being edited.
2. **Cards** — verb discovery is solved (rung 0: compact cards buy correct
   first calls). Most skill bodies were verb documentation; cards already
   replaced them.
3. **Pull references** — deep worked manuals (the `my.kb` pattern: a
   self-describing ns in the store the agent READS on demand). The agent
   pulls depth when it decides it needs depth; nothing is pushed.

Migration: delete the catalog + always-on bodies from the rebuilt tree at
cutover. Former skill content is MINED per-gap only: when a rung shows a
wall that a skill used to paper over, the fix is a state-gated fragment or a
pull reference — with the inclusion bar applied. Expected outcome: most
skill text is never missed.

## The rebuild ladder (remaining rungs, in order)

Each rung = a capability + its colocated context element + an oracle-scored
drive pair + a ledger row. Win conditions are stated BEFORE the drive.

- **Rung 4 — the shared store (NEXT).** register!/transact!/query round-trip
  + recall across turns AND a restart; validates the v3.1 restart-survival
  line. Open question it answers: do cards alone suffice (Spark improvised
  a schema unprompted; DeepSeek recorded state in plan titles) or does one
  store-teaching fragment earn its place?
- **Rung W — reactive warnings/errors.** Derived sections (instrumentation
  errors, test failures, core-fault visibility) that render only when the
  state exists. Win: an agent recovers from a broken redefinition it made
  turns ago, without a human nudge.
- **Rung L — the live tile.** The human-facing canvas returns with the
  block's own teaching ("your human watches THIS, render to it, don't
  narrate"). Win condition is human-UX, not task oracle: a drive where the
  observer confirms the work is followable from the tile alone. The
  interactive plan tile (in flight) supplies the render-quality pattern.
- **Rung M — messaging + multi-agent.** Inbox semantics are largely in the
  transcript already; the subagents surface returns per the multiagent spec
  win conditions (spawn, outcome routing, orphan visibility).
- **Rung I — identity (owner-gated).** Decide what identity IS before any
  block exists. Nothing carries over by default.

Standing check at every rung: re-run rung-0 fab metrics on the accepted
variant (the additive-context fabrication check), and the earlier rungs'
oracles must not regress.

## Cutover mechanics (deprecation, not migration)

1. **Now → rung 4:** the rebuilt tree lives in `config/minimal-*.edn`;
   the default tree keeps running untouched.
2. **After rung 4 + W + L** (store + warnings + tile = daily-work coverage):
   `system.edn` SWITCHES to the rebuilt tree — one coordinated commit
   (owner + both lanes; the default pod is shared). The old tree survives
   only as `config/legacy.edn` for comparison drives, with a deletion date;
   it is never a fallback.
3. **The deletes land with the switch:** the shipped system-text def, the
   identity file-block reads, the skills catalog/bodies, and every register
   row above marked DELETE — plus their dead code paths. Deleting the
   mechanism is the point; a disabled block is still a poison vector.
4. **After the deletion date:** `legacy.edn` and the mined-out old text are
   removed; the ledger + provenance notes are the permanent record of why
   every surviving line exists.

## System-text v3.1 — line provenance (the graduation candidate)

Every line is drive-tested or source-verified; the discipline exists because
one line survived three versions while being WRONG (bare-`def` persistence
taught turns instead of restarts; corrected 2026-07-11 from
`scratch-def-note` semantics, undriven until rung 4):

| line | added | evidence |
|---|---|---|
| live-REPL framing / persistence | v0 | all drives |
| prose-in-parens execution rule | v2 | reduced, not eliminated, both models; mechanics contain the rest |
| concrete glyph prohibition | v2 | interview-endorsed both models |
| interleaved results + parallel threads | v0 | 60+ drives |
| result-KNOWLEDGE (ids/shas arrive interleaved) | v1 | observer-driven; unseen-value fabrication not recurred in Mode A |
| result/<id> re-reference | v0 | used correctly |
| movement verbs | rung 1 | rung-1 GREENs |
| restart-survival (defn/deftest/register! survive; bare def + atom values don't) | v3.1 | source-verified, UNDRIVEN — validate at rung 4 |
| errors-as-data | v0 | solid |
| message/complete/wait | v0 | solid |
| no-form/delivery rule | v3 | fixed 2 live failures, both models, n=2 |

## Method invariants (do not drift)

- Iterate wording on **Spark** (fast, ~0-fab noise), **gate on DeepSeek**
  (the model that reveals defects); never accept a variant without the gate.
- Smallest-n that changes a decision; transcripts over statistics; every
  scorer check stated verbatim in the task text.
- One drive at a time; `SEON_CONFIG` + provider key exported on every
  cluster create AND restart; check for `SEON-STUB-LLM` after provider boots.
- Implementation units are **opus** seon-agents against a written spec; the
  orchestrator keeps this doc, the roadmap, and the ledger current after
  every unit.

## Open defects riding this arc

- #10 multi-line bare-map strip gap (fabricated indented maps evade the
  line-shaped claim regexes — 5 residual v0 turns).
- Env-coupled suite flake (18 preflight-repair failures once, green on
  identical re-run) — recorded, not root-caused.
- Introspection-shadowing sweep beyond `ns-interns`/`ns-publics` (fixed via
  computed `core-macro-head?`) — check `ns-aliases` et al.
- The interactive plan tile html twin (in flight — the rung-L pattern).
