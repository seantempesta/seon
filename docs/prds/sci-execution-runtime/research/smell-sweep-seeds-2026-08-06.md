---
type: research
status: active
tags: [research, audit, runtime]
---

# Adversarial smell-sweep seeds — 2026-08-06

Owner directive (verbatim): "Yeah let's not rush. Investigate all code
smells." / "I don't want hacky bandaids." This note seeds the
comprehensive adversarial audit that launches on the quiet tree after
the drive-repair wave lands. Each seed carries today's evidence; the
sweep verdicts each with REPL falsification and files/updates issues.

1. **Publication 25× discrepancy** — 70–82 s measured (three
   independent observations today) vs the recorded 2.7 s
   reset→republish→refork (08-05 working edge). Hotspot: malli var
   re-registration (RUNNABLE throughout). Issue:
   [complete-publication-takes-seventy-seconds.md](../../../seon/issues/complete-publication-takes-seventy-seconds.md).
2. **Analysis veto blast radius** — a dead `(:refer-clojure :exclude
   [fetch])` (info-level in raw clj-kondo) blocked ALL manifest/fixture
   population; three lanes stalled; 44-error cascade from one line.
   Investigate the level-elevation path (`.clj-kondo/config.edn` has no
   `:unresolved-excluded-var` row; something elevates it) and whether
   whole-manifest veto is the right blast radius for non-load-blocking
   findings. Fix commit for the instance: `b07ccfef0`.
3. **Agent-reachable require graph** — root's eval fork loaded
   `held-flocks`, `running-instances`, `root-store-holder`,
   `source-analysis-cache` (operator/process plumbing) during an
   ordinary turn. Why is operator-adjacent state loadable from
   agent-facing code paths at all? Map the require graph reachable from
   `my.*` + `seon.db` and flag operator/process leakage.
4. **Unbounded error payloads** — one maintenance error's
   `:seon.error/data-edn` is 4,010,918 chars of nested print nodes.
   The class: error construction has no size discipline. Issue exists
   (contract-violation-serializes-print-tree-inside-error-data.md);
   sweep for other unbounded error-data producers.
5. **Context dedup failure** — one prompt carried the same `pull-many`
   error 5×, one page 14×; 484 `:seon.config.*` occurrences and six
   complete config literals in one 44k-token prompt. Equality
   suppression / block identity may be broken upstream of token
   budgets. Evidence: [live-drive-2026-08-06.md](live-drive-2026-08-06.md).
6. **Wildcard-pull ref degradation class** — `[*]` pulls flatten refs
   to `{:db/id}` maps; broke `seon.problems`. Hunt every `[*]`/pull
   consumer that follows refs from the result.
7. **Wake arbitration** — a failed maintenance `:step` opened a paid
   model turn whose prompt absorbed the newer human message (the drive
   freeze's custody half). The messaging wave redesigns the seam; the
   sweep checks for OTHER trigger/prompt mismatches of the same shape.
8. **Stale claim accumulation** — eight invalid external claims
   persisted across sessions; reconciliation reports but never
   resolves. Wiped by reset; the accumulation mechanism remains. Issue:
   status-floods-unreadable-external-claim-warnings.md (face half).
9. **Coverage skew** — 51.4% of the tree is tests, bare gate green,
   and the first real end-to-end turn froze. No recurring drive-shaped
   proof exists; owned by the in-server-tests PRD (four owner questions
   pending).
10. **Lane process smell** (not code) — two lanes exited with finished
    work UNCOMMITTED at foreign-transient boundaries; the spec
    boilerplate's commit-your-slice rule needs to be stated as an
    absolute.

Standing sweep lenses on top of these seeds: second mechanisms, hand
lists, stored-derived creep, unjustified clocks, symptom patches, lying
docstrings, atoms beyond the process-local exception (census running:
atom-audit lane).
