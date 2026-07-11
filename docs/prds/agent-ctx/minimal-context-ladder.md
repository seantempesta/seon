---
type: prd
status: active
tags: [prd, agent, index]
---

# The minimal-context rebuild — old surfaces retire, their IDEAS return tested

**The point (owner, 2026-07-11, refined): the old context tree's SURFACES
retire wholesale, but their IDEAS are the inventory.** Each old block held
an idea — some good (the live tile, reactive warnings, shared instructions),
some accreted guesswork. The rebuild is: carefully re-add NEW blocks based
on the ideas the old blocks held, each colocated, each tested to confirm it
actually works as intended. Deletion applies only where the idea itself
isn't useful. Nothing is ported verbatim; everything that returns is
rebuilt on the minimal base and earns a ledger row.

**Why (the poison principle — this is NOT about tokens):** bad information
in context poisons behavior, the old tree is an initial guess plus pile-on
(bloated, self-repeating, probably self-contradictory), and attributing a
bad drive to a specific line after the fact is intractable. The asymmetry
that decides everything: **omission is recoverable and attributable** (an
agent hits a wall, the transcript shows the wall, one rung adds one
fragment), **inclusion is neither** (poison hides in the pile). So evidence
attaches at INSERTION time, and the safest posture is minimal.

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

## The idea inventory — every old surface, the idea it holds, its replacement

The COMPLETE old tree (priorities from `default-ctx-blocks` + the identity
file-blocks + the skills machinery), each mapped to the idea it embodies,
what replaces the surface, and how the replacement gets tested. "Rebuild"
always means a NEW minimal, colocated block built on the base and validated
by a drive — never a port of the old text.

| old surface (prio) | the idea it holds | replacement | tested by |
|---|---|---|---|
| shipped `ctx/system-text` def (~4–5k) | REPL/eval mechanics; async-reads-as-synchronous; register!-before-transact; namespaced-keys; schemas-are-enforced; entities-are-attributes-not-kinds; concept doctrine | v3.x already holds the REPL mechanics (measured). The DATA-RULE ideas are rung-4 candidates — but note the runtime already teaches most of them REACTIVELY (`transact!` rejects unregistered attrs with a guiding envelope; instrumentation errors carry the schema) — a text line enters only where the error envelope demonstrably isn't enough | rung 4 drives; per-line inclusion bar |
| `soul` (5, SOUL.md) | persistent identity/persona | rung I — identity as DB state (config-through-DB), owner defines what identity IS first; drive law already ran soul OFF | owner-defined win condition |
| `agents` (8, AGENTS.md, ~14.1k) | house rules / operating instructions | MINE for ideas: global+load-bearing → candidate system-text lines (one at a time); block-specific → that block's teaching; the rest presumed pile-on | per-line inclusion bar |
| `shared-instructions` (10) | **good idea**: DB-backed standing instructions every agent sees; humans/agents can add them at runtime | rebuild as a state-gated block rendering ONLY when rows exist (reactive, colocation-native) | an instruction-following drive: add a shared instruction, verify the behavior change, remove it, verify the reversal |
| `skills-catalog` (12) + always-on skill bodies (16, ~2.9k) | discoverable on-demand deep expertise | dissolves three ways (below): cards (proven), state-gated teaching, pull references | rung L is the first direct test (tile-building with no skill body) |
| `namespaces` (20) | **kept — the proven floor** | as-is | rungs 0–2 |
| `live-tile` (35) | **good idea**: the human watches a live canvas, not a chat log; render, don't narrate | rung L — rebuilt with its own colocated teaching; the interactive plan tile (in flight) is the render-quality pattern | observer-scored followability drive |
| `warnings` (40) | **good idea**: reactive self-healing surfaces — problems render until fixed, vanish on fix, no acks | rung W — derived sections, born colocated | agent recovers from its own turns-old breakage without a nudge |
| `jobs` (42) | background-job visibility | likely already covered: cards + result envelopes drove correct job polling in rung-0 drives; rebuild only if a drive shows job blindness | a long-job + restart drive |
| `test-failures` (43) | the latest testrun state visible (why the complete-gate refused) | rung W family — the gate already ENFORCES mechanically; the block's idea is explaining the refusal | two-bucket-style drive: turns-to-understand-refusal with/without |
| `plan` (45) | **kept — rebuilt and proven** | as-is (rung 2) | rung 2 |
| `recent-verbs` (46) / `plan-ledger` (47) | typeahead offer menus (derived per render) | the OTHER lane's live experiment — born-colocated already; exempt here, coordinate at cutover | their arc's own measures |
| `relevant-source` (48) | retrieval: source related to what you're doing, beyond current-ns | rebuild only when a drive shows cards + current-ns fail a real cross-ns discovery task; consider agent-PULL (semantic search verb) before a pushed block | a discovery-task oracle |
| `subagents` (96) | children status + outcome routing visibility | rung M per the multiagent spec's win conditions | spawn/outcome/orphan drives |
| `findings` (97) | durable task findings that outlive the transcript | rung 4 decides: findings-as-db-facts recalled by query may subsume it; else a state-gated recent-findings render | store-then-recall across restart |
| `transcript` (100) | **kept — the spine** | as-is | every drive |
| `usage`, `inventory` (families, not in the default tree) | token-spend awareness; store inventory | ideas on the shelf; rebuild on demonstrated need | — |
| `my.kb` manual ns | the worked DB-memory manual | stays as a PULL reference (agent reads it on demand); never pushed | rung 4 |

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

## Progressive graduation — the minimal MATURES INTO the system (owner Q 2026-07-11: yes)

Not a big-bang cutover: the minimal tree matures in place until it IS
`system.edn`, in steps that are each individually safe, so the system being
built is the system being run — that continuity is what makes it
sustainable.

1. **Now (safe, immediate):** the measured system-text graduates to
   `system.edn`'s `:seon.config/system-text` — every cluster gets the
   evidenced v3.x lines instead of the shipped guess. The default's blocks
   stay untouched; this swaps only the text with the best provenance.
2. **Continuous (already happening):** rebuilt blocks are THE SAME block
   specs both trees reference — the plan block's rung-2 teaching reached
   the default automatically. Every rung's improvement flows to both trees
   by construction; there is never a fork to reconcile.
3. **Tree parity (after rung 4 + W + L — store, warnings, tile = daily-work
   coverage):** `system.edn`'s TREE switches to the rebuilt tree, one
   coordinated commit (owner + both lanes; the default pod is shared). The
   old tree survives only as `config/legacy.edn` for comparison drives,
   with an expiry date — never a fallback.
4. **The retires land with the switch:** the shipped system-text def, the
   identity file-block reads, the skills catalog/bodies — plus their dead
   code paths (a disabled surface is still a poison vector). Ideas not yet
   rebuilt stay in the inventory table above, not in the tree.
5. **Steady state (the sustainable loop):** there is ONE tree; every later
   rung lands directly in it; maintenance IS the same loop that built it —
   confusion in a transcript → reword that block's own lines → redrive →
   ledger row → provenance note. The system never again accretes unattributed
   text, because the inclusion bar is the only door.

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
