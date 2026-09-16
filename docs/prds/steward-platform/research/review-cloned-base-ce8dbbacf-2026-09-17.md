---
type: research
status: reviewed
created: 2026-09-17
tags: [review, orchestrator, cloned-base, boot-test]
---

# Orchestrator review — `ce8dbbacf` (cloned-base regression baseline)

Reviewed by the orchestrator before the gate, per the PRD's lane rule 4
(`docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md` §4b).

**Diff read in full:** `test/seon/cluster/boot_test.clj` (+5) and the landing
note (+49).

**What it changes.** `cloned-publication-analyzes-only-changed-files` now
calls `(cluster/refresh-source! root [])` once before relocating the
manifest, and asserts the no-op refresh after relocation returns the same
`:seon.source/commit-id` as that baseline, in addition to the existing "zero
analyses" and "exactly the reported path" assertions.

**Why it is the right fix.** Batch 98's cold red showed the pooled worker's
checkout differing from the cached published base in a handful of test files
that other tests rewrite (own-nothing-global, AGENTS.md §5 rule 7). The
regression's premise, "relocation alone", was therefore false in the shared
worker. Publishing the test's own baseline makes the premise true by
construction instead of tolerating a non-zero count. The added commit-id
equality is a stronger no-op check than `built?` alone.

**Checked and accepted.** No production edit; no harness added; the test
remains declared `:seon.test/long`; the baseline publication is the one
public owner (`refresh-source!`). Cost: one extra publication inside a test
already declared long.

**Rejected.** Nothing.

**Gate requested:** batch 99, `seon.cluster.boot-test`.
