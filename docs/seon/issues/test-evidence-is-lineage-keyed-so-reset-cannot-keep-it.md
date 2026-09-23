---
type: issue
status: open
severity: design
created: 2026-09-23
tags: [issue, test, reset, agent-platform]
---

# Test evidence is keyed to its branch's lineage, so reset cannot keep it as program rows

Observed 2026-09-23 (lane test-evidence-program) against the owner ruling
"test results that are tied to the program graph are part of the program graph"
(AGENTS.md "The program is rows on the branch"; plan README §7 "Schema change and reset").

## What a member's reuse evidence references today

Live read on `default` (JVM eval, read-only, 24 ms): 81 runs, all `:seon.test.run/branch
"cluster-default"`, basis-t 536870968..536871690. A completed member pulls as
`{:seon.test.member/completed-tx {:db/id 536871606} :seon.test.member/terminated-tx {:db/id 536871606}
:seon.test.member/symbol … :reasons … :pass/fail/error-count … :failures #{{:db/id 48187}}}`;
its run carries `basis-t`, `change-basis-t`, `selection-tx`, `cluster`, `program-digest`,
`input-digest`. No member stores a `:seon.program/definition-digest` or an execution commit id.

- `seon.test/changed-since-green` (`src/seon/test.clj:62-99`) and selection
  (`src/seon/test.clj:640-700`, `changed-definition-symbols` `:446-480`) compare definition
  digests by reading `(db/as-of database basis)` and `(db/since history basis)` with the run's
  numeric `basis-t`, and order runs by `selection-tx`.
- Member completion is transaction-entity refs (`claim-tx`, `completed-tx`, `terminated-tx`,
  `resources/seon/schemas/seon.test.member.edn`).

So the evidence is keyed to the recording branch's lineage, not to program content. B4 §2a
(`docs/prds/agent-platform/plan/lane-b4-tests-in-process.md:99-117`) already names the target:
numeric `as-of`/`since` only after proving ancestry, otherwise compare retained declaration
signatures. That fallback is not installed.

## Why a partition declaration alone is wrong

1. `reset` (`src/seon/cluster/boot.clj:590-618`) retires the cluster branch and `start!` forks a
   fresh one from the `current-src` publication commit (`registry/ensure-cluster!`,
   `src/seon/cluster/registry.clj:267-291`). The old cluster branch's commits are not
   ancestors of the new branch. Copied runs would carry basis-t values and tx refs that name
   unrelated states of the new lineage: `as-of basis` would compare against the wrong
   program, which is false reuse, not preserved evidence.
2. `seon.program/program-attributes` (`src/seon/program.cljc:29-50`) derives from the
   `:seon.program` partition and feeds the tested program digest
   (`src/seon/test/runner.clj:923-960`, "a result write since the seal is no work here") and SCI
   acquisition identity (`src/seon/sci/eval.clj:2343-2360`). Declaring runs `:seon.program`
   makes every recorded run change the program digest, so `reusable-result`'s
   `(= digest (:seon.test.run/program-digest run))` (`runner.clj:1628-1638`) fails for the run
   just recorded, and every test write invalidates the acquisition cache.

## What would make the ruling true

The recorder (test.clj/runner.clj owner) stores lineage-independent evidence on each member:
the tested definition digests of its reach (or one reach digest) plus the execution commit id,
and selection compares them against current `:seon.program/definition-digest` when ancestry is
unproven (B4 §2a). Program identity must then exclude evidence attributes: either
`program-attributes` reads a distinct partition value for evidence, or evidence attributes carry
`:seon.program/written-by` (already excluded at `program.cljc:44`). Only then does reset copy
the evidence rows onto the fresh branch.
