---
type: research
status: active
date: 2026-10-11
---

# Codex model usage findings

## Summary

From 2026-08-28 through 2026-10-11, 175+ Astra, 32+ Sol, and 0 Luna lanes ran. **Astra dominated as reviews/audits/perf fixes (owner's recent characterization: "the FIXER"), Sol as direct implementation from written specs, both underutilized for parallel design work.** Owner's diagnosis (2026-10-11): "we drifted into reviews/perf-only (missed opportunity); Codex models trained on a different distribution are best used to design in parallel, not just review." New ruling: Haiku dig → Opus + Sol 6.1 independent phased plans → Fable hybrid synthesis.

## Codex lane usage by kind

| Kind | Astra | Sol | Period | Notes |
|---|---:|---:|---|---|
| Design | ~5 | 0 | 2026-09-16 to 2026-09-23 | Astra-floor-descriptor, dogfood-design, plan-review. Stopped 2026-09-25 (owner: "NO Astra first-pass design") |
| Reviews/diffs | ~90 | 0 | Ongoing | review-astra-batch-a/b/c/d, review-*-diff-astra, review-landings-astra. Every Opus landing gets one. |
| Audits/diagnosis | ~45 | 0 | Ongoing | astra-*-audit, astra-*-analysis, error-delivery-audit, dogfood-analyst-astra, reinvention-audit |
| Perf/fixes | ~25 | ~8 | Ongoing | astra-*-cost, sol-*-cost, astra-perf-followup, sol-cold-store-reads, alloc-research-sol |
| Implementation | ~10 | ~24 | 2026-09-16+ | Sol: sol-error-constructor, sci-vars, files, docs-steward, merge-path. Astra: few; mostly specs. |
| Trials | 3 | 3 | 2026-10-07 | trial-astra-s1/specs/guide, trial-sol61-* (gpt-6.1-sol new model test vs astra) |

**Total: ~175 Astra, ~32 Sol, 0 Luna.** Astra = 84%, Sol = 16%.

## Key historical rulings and drift

| Date | Ruling | Effect |
|---|---|---|
| 2026-09-16 | "Astra for hard work, Opus for the rest" | Astra reserved for implementation; others → Opus |
| 2026-09-17 | "Sol lanes are competent; use for ordinary implementation" | Sol ~gpt-5.6-sol emerges as implementer |
| 2026-09-23 | "Opus for ALL implementation, Astra for reviews + synthesis" | Astra pulled from implementation to review |
| 2026-09-25 | "Opus designs → Astra reviews (no Astra first-pass)" | Halted 5 Astra design lanes mid-flight |
| 2026-10-07 | "Dual review: Opus + Astra (different distributions)" | Trial: Astra vs Sol 6.1 found ~80% overlap, Astra ~10% cheaper → Astra picked |
| 2026-10-11 | "Haiku dig → Opus + Sol 6.1 independent plans → Fable hybrid" | **Reversal: parallel design resumes, explicit synthesis step added** |

**The drift:** 2026-09-23 onward, Astra relegated to review-only, losing design role until today's ruling.

## Cases: parallel independent design + hybrid synthesis

### 1. Design floor (2026-09-21 to 2026-09-25) — incomplete trial

- Opus wrote design-floor-descriptor-opus (design)
- Astra wrote design-floor-descriptor-astra (design) — STOPPED 2026-09-25 ("NO Astra first-pass design")
- Status: never reached synthesis/comparison
- Finding: owner halted parallel design work explicitly, saying Astra should only review

### 2. Trial dual-review (2026-10-07) — Codex model comparison

- Astra: review trial-astra-{s1,specs,guide} at high effort
- Sol 6.1: review trial-sol61-{s1,specs,guide} at high effort (same prompts)
- Result: "Equal quality, ~80% overlap; Astra ~10% cheaper and slightly more accurate → Astra is the codex half"
- Outcome: Astra chosen as the dual-review pair with Opus going forward
- Hybrid step: none (direct selection)

### 3. No recorded multi-plan design syntheses

- Memory and landing docs searched for cases where two designs were compared and merged
- Found: planning decisions by owner on evidence, but no agent-authored "hybrid" synthesis
- E.g., merge-path, hostbound: mentioned as "opus + sol parallel options, owner decides" but no synthesis document

## When Codex found what Claude missed

### High-confidence cases (audits that changed code)

1. **astra-day-audit (2026-10-08)** — 8 findings in cluster.source and cluster.merge (landing fences):
   - Commit 860220add: landing resumption after acceptance
   - Commit 17138ccad: one owner holds checkout through write-back + git
   - Commit 14b8ea164: landing commits exact proven bytes
   - Commit 7ab559153: recorded test result names its evaluator
   - 4 more in f540268a1, fff956fff, c95848065, 56d2efa61
   - **Owner response:** "Astra critique landed as mandatory fixes before acceptance"

2. **astra-contexto-cost profile** (2026-10-10) — cold first-turn costs 86-177 ms / 148-354 MB, "dominated by contract-references and proof reach" (led to CACHE-1/CACHE-2 caching plan)

3. **astra-honest-audit** (2026-09-27) — false claims on honest-eval semantics caught (via rewrite-clj vs sci repl behavior)

### Low-confidence / unverified

- Astra audit lanes produced findings but unclear if they found bugs Opus missed vs. alternative perspectives
- Most Astra lanes are reviews of Opus/root landings, making causal credit unclear

## Failure patterns

### Lanes that stalled or cost heavily

1. **Astra design-floor (2026-09-25)** — halted mid-work by owner directive, not technical failure
2. **Long-running audits** (astra-*-audit, astra-analysis-*) — often 30–60 min, owner noted "if lanes take this long the spec is too large or not well spec'ed" (feedback 2026-10-08)
3. **Sol implementation misses** — sol-error-constructor, sol-refusal-canonicalization had rework; Opus preferred for semantic correctness

### Hooking/sandboxing issues

- Codex lanes bypass hooks with `--dangerously-bypass-hook-trust` (SKILL.md)
- If hooks.json changes, must stop/resume all lanes
- No reported failures here, but adds operational fragility

## Owner's stated model preferences

### From memory notes (citations)

1. **2026-09-25, AGENTS.md §4:** "I want Opus to do the first pass on all designs and astra to do reviews. Astra is also to be used for auditing our system. The goal is to use outside models for a different perspective and since they were trained on a different dataset so they have a different distribution. The goals are to create hybrid designs that improve on all aspects and to catch bugs and errors both in designs and in the existing code."
   - Then: Astra never got that "hybrid design" role until today's ruling

2. **2026-10-07, dual-review:** "every review is dual: an Opus agent plus one codex model (Astra chosen after a 3-round trial vs Sol 6.1); different training distributions catch different things"

3. **2026-10-11, TODAY:** "that's how we do planning and design from now on" (Haiku dig → Opus + Sol independent → Fable hybrid)
   - Explicit recognition that parallel design was missed opportunity
   - Sol 6.1 chosen for parity with Opus (not Astra, which will stay in review role)

## Key evidence files

| Path | Kind | Date | Note |
|---|---|---|---|
| /Users/sean/.claude/projects/.../memory/feedback_dual_review_opus_plus_astra_2026_10_07.md | Decision | 2026-10-07 | Trial results: Astra ~10% cheaper, equal quality |
| /Users/sean/.claude/projects/.../memory/feedback_opus_implements_astra_reviews_2026_09_23.md | Decision | 2026-09-23 | Opus implements, Astra reviews; Astra pulled from design |
| /Users/sean/.claude/projects/.../memory/feedback_design_by_independent_plans_hybrid.md | Decision | 2026-10-11 | New ruling: Haiku → Opus + Sol 6.1 independent → Fable hybrid |
| tmp/orchestrator/*astra*-summary.txt | Lanes | 2026-09-14 to 2026-10-11 | ~175 Astra summaries; count and parse for kind |
| tmp/orchestrator/*sol*-summary.txt | Lanes | 2026-09-16 to 2026-10-11 | ~32 Sol summaries |
| .agents/skills/codex-lanes/SKILL.md | Reference | 2026-09-26 | Models and effort dials; gpt-6-astra as default |
| docs/seon/reference/driving-codex-agents.md | Reference | Current | Codex launcher grammar and lane contract |

## Conclusion

**Drift period (2026-09-23 to 2026-10-10):** Astra was sidelined to review-only, Sol used sparingly for implementation, both underutilized for what they do best — exploring design space from a different learned distribution. **Today (2026-10-11):** Owner is restoring parallel-design methodology with explicit synthesis: "we drifted into reviews/perf-only (missed opportunity); Codex models trained on a different distribution are best used to design in parallel, not just review."
