---
type: plan
status: active
created: 2026-09-20
tags: [plan, publication, test-system, tools, wave/publication-velocity]
---

# Publication dissolution — lane spec (owner go-ahead 2026-09-20 ~22:40 UTC: "do them all in the order that gives the greatest wins")

## Measured problem (2026-09-20, `tmp/orchestrator/gate-1a-three-suites-2026-09-20.log`, `data/operator/operations/jvm-5b0db59f-*.log`)

A complete publication of the program graph costs ≈200 s of JVM work and runs
in a FRESH JVM on every cold gate after HEAD moves, on every `bin/seon init`,
and on every reset: population commit 64 s (98,850 datoms; our final-report
validator, not Datahike), clj-kondo analysis of all 372 inputs 27 s, branch
head 18 s, contract rows 17 s, preparation 13 s, JVM boot and load 40–60 s;
645 kondo warnings printed per gate. Three publishers build the same value
from scratch: the edit hook (`bin/seon-hook` → `bin/seon init --dev`), `bin/seon
init` (`script/seon/fresh_operator.clj`), and `bin/test`'s base
(`src/seon/test/cache.clj`, `src/seon/cluster/source.clj`). The hook already
publishes CHANGED files as same-identity upserts (`src/seon/cluster.clj`
`incremental-source-refresh!`); the other two do not.

## Order by win (each item its own path-limited commit; HEAD loads after each)

1. **One publication per source digest, incremental by file content digest,
   shared by default and every gate.** The gate's base for HEAD = the newest
   published base + the incremental publication of exactly the inputs whose
   digest changed since that base (the same digest `seon.test.cache` already
   records per input). `bin/test --prepare-head-base` and the cold gate's
   `published-base` phase call that ONE owner; `bin/seon init` calls it for a
   complete publication only when no base exists. A publication is keyed by
   its source digest and reused when present. Delete the second and third
   publishers' own build paths. Guarantee: a landing that touched N files
   costs N files' analysis + rows, not 372. Proof: a gate after a one-file
   commit publishes in single-digit seconds and its program digest equals a
   complete publication's of the same tree (regression compares both).
   **Lineage ruling (2026-09-20 ~22:55 UTC):** a publication reconciles on the
   CURRENT published lineage and never starts a new database history. The
   results-reuse lane proved (`6e011707c`) that a full rebuild orphans recorded
   test evidence: a member's `selection-tx` refers to the previous history and
   execution refuses it. Every recorded evidence reference (the note lists them
   with file:line) must resolve after a publication; a regression records a
   result, publishes one changed file, and reuses the result.

2. **Publish through the live JVM, never a fresh one.** When `default` (or
   any live cluster JVM of the root) is alive, the publication runs over its
   prepl (the same seam `init --dev` adoption uses), removing the 40–60 s
   boot and the second 10 GB heap. A fresh JVM only when none is alive.
   Bound: the publication's own phase events refresh the operator's
   lifecycle heartbeat (issue
   `the-lifecycle-watchdog-measures-silence-from-lock-acquisition`); a flat
   subprocess deadline never fires while phases progress.
3. **Analysis cached by input digest.** clj-kondo analysis results per input
   are stored keyed by the input's content digest and reused; only changed
   inputs are analyzed. (Falls out of item 1's digest key; separate commit.)
4. **Warnings are findings, not log lines.** Kondo warnings become
   `:seon.fn/finding` facts on the declaration (tools item 5, `seon.fn.finding.edn`
   exists); the gate prints a count and the delta since the previous base,
   never the roster.
5. **Population commit diet** is bridge step 4 (its own lane from
   `docs/prds/steward-platform/research/step4-validator-measurement-2026-09-20.md`,
   after bridge step 1 per §6 order). Not this lane.

## Grounding to read end to end

`AGENTS.md` §§1–3 and lane rules 11–16; `docs/prds/steward-platform/plan/unsettled.md`
blocks from "RESUME HERE (2026-09-20 ~11:45 UTC)" to the end (the
publication measurements, the reset, the rebuild-from-rows refusal);
`docs/prds/steward-platform/research/step3-carried-projection-2026-09-20.md`
sections "Census method and risk key" and "Exact production changes required
by step 3" (the publication owners and their acquisition seams — do NOT
implement step 3; do not add a stamp); `docs/seon/issues/an-aborted-publication-leaves-no-record.md`;
`docs/seon/issues/the-lifecycle-watchdog-measures-silence-from-lock-acquisition.md`;
`bin/seon-hook` (the incremental path), `bin/test` (`--prepare-head-base`,
`published-base` phase), `src/seon/test/cache.clj` (`input-roots`,
`test-input-digest`, `newest-base`), `src/seon/cluster/source.clj`,
`src/seon/cluster.clj` (`populate-source!` :1741, `source-base!` :1990,
`incremental-source-refresh!`, `full-source-refresh!`),
`script/seon/fresh_operator.clj` (`publication-bound-ms` :273, phases).

## Owned paths

`src/seon/cluster/source.clj`, `src/seon/cluster.clj` (publication region
only — the turn/cluster kind sweep must have LANDED first; check `git status`),
`src/seon/test/cache.clj`, `bin/test`, `bin/seon-hook`,
`script/seon/fresh_operator.clj`, `src/seon/fn.clj` analysis seam,
`resources/seon/schemas/seon.source.edn`, `seon.fn.finding.edn`, their tests,
and the landing note `docs/prds/steward-platform/research/publication-dissolution-2026-09-20.md`.
Held paths at launch are named in the launch text; name, never edit.

## Rules

One JVM at a time, foreground; never a worktree; iterate with
`bin/test-fast --paths <owned files> -- seon.test.cache-test seon.cluster.source-test
seon.test-runner-test seon.fn-test` plus affected namespaces; never a cold
gate; never touch `default` (verify item 2 on an owned scratch root:
`bin/seon --root tmp/publication-root start`, downed and deleted before
reporting). Landing note with measured before/after per phase for a one-file
commit and for a complete publication, the deleted paths, and the cold
command owed. Stop at a genuine decision with three priced options.
