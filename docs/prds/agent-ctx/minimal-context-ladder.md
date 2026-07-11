---
type: prd
status: active
tags: [prd, agent, index]
---

# The minimal-context ladder — target, we-are-here, graduation

**The point (owner, 2026-07-10/11): the built-up minimal context BECOMES the
default.** This is not a benchmark side-quest — each rung validates one
context block with drive evidence, and the end state is `system.edn`'s
context tree replaced by the evidenced tree, deleting whatever never earned
its tokens. The ladder is also a diagnostic instrument: every RED so far has
traced to a core runtime bug or one teachable line, never to mush.

Evidence ledger (per-drive tables, transcripts, verdicts):
`evals/runs/2026-07-10-minimal-buildup/README.md`. Plan of record:
`~/.claude/plans/lazy-splashing-rainbow.md`. Standing rules:
colocation (a block carries its own teaching; context is purely additive),
variants gated on DB datoms, config→DB at boot, NO symptom-side hacks,
three testing surfaces only.

## The context under test

`config/minimal-plan{,-stream}.edn` — four pieces, ~7k fixed prefix:

| piece | ~tokens | teaching it carries |
|---|---|---|
| system-text v3.1 (the datom) | 470 | global REPL mechanics only (see provenance below) |
| `:namespaces` block (prio 20) | 6,400 | current-ns full source + home-require compact cards + full-vs-cards/movement header |
| `:plan` block (prio 45) | 60–150 | empty-state decompose teaching / anchor + frontier + done-tail |
| `:transcript` block (prio 100) | grows, decayed | masthead + mode-gated repl fragment + interleaved eval log |

Deliberately absent until a rung earns them: warnings, findings, live-tile,
skills catalog/bodies, jobs, subagents, relevant-source, soul/agents.

## Rung status

- **Rung 0 — REPL reliability: CLOSED.** 12 valid drives + the cross-model
  addendum. Verdicts: fabrication is model-specific (DeepSeek 32–48%
  attempts in `:batch`, ~0 for Spark) and structurally eliminated by
  `:stream`; the namespaces block is the tool-task floor for EVERY model
  (no-cards probe RED on both); repl-mode defaults per-model.
- **Rung 1 — namespace movement: GREEN both models** (`ns-move-v1` contract
  + oracle). Exposed and fixed a real core bug: the eval batch re-seeded at
  the HOME ns every turn, so `in-ns` never held across turns (Mode A had
  masked it forever).
- **Rung 2 — planning (flagship): GREEN both models** via the src-inspect-ai
  planning driver re-grounded on this context, with a real mid-sample pod
  restart. Oracle = answer + resume-trajectory + decompose-first +
  close-adjacency (all stated in the contract). Fixed along the way:
  `restart_pod` dropping `SEON_CONFIG` (context swap), the plan block's
  parentless-`step!` teaching, the no-form/delivery rule.
- **Rung 3 — tool cards: pre-answered by rung 0** (cards buy correct first
  calls; the 6.4k block pays for itself). No separate run planned.
- **Rung 4 — the shared store: NEXT.** db-memory already GREEN at rung 0;
  Spark improvised a real schema + transacts unprompted at rung 2. The rung
  adds `my.kb`-style teaching only if a drive shows the cards alone fail,
  and validates the NEW restart-survival system-text line (v3.1).
- **Rung 5+ — messaging / live-tile / skills-catalog / identity:** each
  needs its own win condition (task oracles don't fit identity or human-UX
  blocks). Not designed yet.

## System-text v3.1 — line provenance (the graduation candidate)

Every line is either drive-tested or source-verified; the audit discipline
exists because one line survived three versions while being WRONG (the
bare-`def` persistence line taught the wrong boundary — turns instead of
restarts — corrected 2026-07-11 from `scratch-def-note`'s actual semantics,
not yet drive-validated):

| line | added | evidence |
|---|---|---|
| live-REPL framing / persistence | v0 | all drives |
| prose-in-parens execution rule | v2 | reduced, not eliminated, both models; mechanics contain the rest |
| concrete glyph prohibition | v2 | interview-endorsed both models |
| interleaved results + parallel threads | v0 | 60+ drives |
| result-KNOWLEDGE (ids/shas arrive interleaved) | v1 | observer-driven; unseen-value fabrication not recurred in Mode A |
| result/<id> re-reference | v0 | used correctly |
| movement verbs | rung 1 | rung-1 GREENs |
| restart-survival (defn/deftest/register! survive; bare def + atom values don't) | **v3.1** | source-verified, UNDRIVEN — validate at rung 4 |
| errors-as-data | v0 | solid |
| message/complete/wait | v0 | solid |
| no-form/delivery rule | v3 | fixed 2 live failures, both models, n=2 |

## Graduation criteria (minimal → the shipped default)

1. **The shipped-text diff audit (the main remaining work):** every
   paragraph of the shipped `ctx/system-text` def shown to be (a) covered by
   a block's colocated teaching, (b) covered by v3.x, or (c) evidenced
   unnecessary. Until this exists, "nothing load-bearing is dropped" is
   unproven.
2. **Ride-along validation** for young lines (v3/v3.1) across rungs 4+ with
   no reword needed.
3. **The default-block gap map judged** — largest first: `agents` (~14.1k
   tokens, HALF the default prefix, zero evidence), `soul` (~1.9k),
   always-on skill bodies (~2.9k, must prove lift over cards), live-tile
   (~2k, needs a human-UX win condition), and the small tail (warnings /
   findings / jobs / subagents / relevant-source / plan-ledger /
   recent-verbs) — each gets a rung, a colocated rewrite, or deletion by
   evidence.
4. **Cutover as a coordinated unit** — owner + both lanes (the default pod
   is shared), the ledger as the argument, one commit that swaps
   `system.edn`'s tree and deletes the shipped system-text def.

## Method invariants (do not drift)

- Iterate wording on **Spark** (fast, ~0-fab noise), **gate on DeepSeek**
  (the model that reveals defects); never accept a variant without the gate.
- Smallest-n that changes a decision; transcripts over statistics; every
  scorer check stated verbatim in the task text.
- Confusion in a transcript → reword THAT block's own lines → new variant
  tag → redrive. Never global prose, never a symptom-side layer.
- One drive at a time; `SEON_CONFIG` + provider key exported on every
  cluster create AND restart; check for `SEON-STUB-LLM` in the pod log
  after any provider boot (the harness refuses stub drives).
- Implementation units are **opus** seon-agents against a written spec
  (owner 2026-07-11); the orchestrator's compensating duty is keeping this
  doc, the roadmap, and the ledger current after every unit.

## Open defects riding this arc

- #10 multi-line bare-map strip gap (fabricated indented maps evade the
  line-shaped claim regexes — 5 residual v0 turns).
- Env-coupled suite flake (18 preflight-repair failures once, green on
  identical re-run) — recorded, not root-caused.
- Introspection-shadowing sweep beyond `ns-interns`/`ns-publics`
  (fixed via computed `core-macro-head?`) — check `ns-aliases` et al.
- The plan tile html twin (in flight — the human-facing live plan view).
