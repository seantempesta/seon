---
title: Reds after the Datahike bump to dbca76d5 (bump-reds)
type: research
status: landed
date: 2026-10-07
lane: bump-reds
---

# bump-reds: classifying the reds after the Datahike bump to dbca76d5

Root bumped the Datahike gitlink d9121bbb -> dbca76d5 (74f007427). The check
`--test seon.db-test --test seon.cluster.evaluate-source-test --test seon.read-evidence-test` was red:
110 executed, 761 pass, 14 fail, 1 error (run 78c3607894b7).

## Classification: every red predates the bump

The same three namespaces ran on the parent 6010462f7 (old gitlink d9121bbb) in a throwaway JVM, built
through the operator's own `candidate-tests!` on that commit (driver: scratchpad `parent-check.clj`; no
working-tree change). Run 4fad00094e39: 110 executed, 761 pass, 14 fail, 1 error, the same failing
assertions at the same lines.

| Red | Line | Before the bump | Class | Cause |
|---|---|---|---|---|
| seon.db-test/database-operations-preserve-refusals-at-their-declared-boundary | db_test.clj:2528, 2537 | same | pre-existing | The test asserts old behavior. Since 33fb994f2 (errors S1) the read and write variants are on `:seon.error/type`, not in metadata, so `=` sees the restamp to `:seon.db/invalid-read-error` / `:seon.db.write/validation-refusal`. Test corrected (below). |
| seon.read-evidence-test/another-recipients-message-keeps-junipers-inbox-current | :60, :82 | same | pre-existing | (a) test plumbing, (b) wildcard-pull evidence: see below |
| seon.read-evidence-test/junipers-message-invalidates-junipers-inbox | :60 | same | pre-existing | (a) |
| seon.read-evidence-test/a-fresh-connection-keeps-an-unrelated-read-current | :60, :91 | same | pre-existing | (a), (b) |
| seon.read-evidence-test/a-fresh-connection-sees-a-related-write | :60 | same | pre-existing | (a) |
| seon.read-evidence-test/a-branch-recreated-under-the-same-name-proves-nothing-by-index | :60 plus a ClassCastException in `as-seen-by` | same | pre-existing | (a); the throw disappears once the test reads the path's evidence |

No regression comes from dbca76d5, and nothing was flaky (identical counts on both runs). seon.cluster.evaluate-source-test
is green on both.

## Causes

**(a) The read-evidence test reads a sink the one path no longer feeds.** `seon.turn/evaluate-row` passes
the form's world reads (`:seon.batch/reads`) as the request's `:seon.db/read-evidence-sink`, and
`seon.sci.eval/form-read-sink` prefers it over the ambient `seon.db/*read-evidence-sink*` the test binds. The
reads are recorded on the batch entry, but `seon.test-support/evaluate` drops them. Fix: `evaluate` returns
the last entry's `:seon.batch/reads`, and the test reads the evidence from there. This is the second option
of task `seon.sci.eval/an-evaluated-forms-reads-reach-no-bound-evidence-sink` (c721a1b18521). test_support.clj is held by
lane s0-probe, so the change is a patch for root: `reads-on-the-evaluation.patch` (it applies cleanly over
s0-probe's uncommitted diff, which does not touch `evaluate`'s return). Proven on candidate 3 files over HEAD:
9 executed, 115 pass, 2 fail (run 6d3fcf7bb595). The db-test and four of the five inbox tests are green.

**(b) A wildcard pull is never provably current.** With (a) applied, the form's evidence holds three reads:
- the inbox query (bound patterns, current);
- the agent namespace lookup (`:seon.agent/id`, `:seon.agent/namespace`, `:seon.ns/name`), bound;
- `{:seon.db/read-operation :pull :seon.db/pull-arguments [[*] [:seon.config/cluster "default"]]}`. This is
  the config read (`seon.config/effective-in` / `held-overlay`) made during the form.

`seon.db/pull-index-patterns` answers nil for a `:wildcard?` spec, so the read keeps no patterns. Its source
reads `:all` attributes, and `seon.db/read-evidence` drops the replay request by default. After any commit
`read-evidence-current?` therefore answers false: the inbox goes stale on an unrelated write. The two
"stays current" tests stay red. Machinery reads entering the form's sink is the same defect class. This is
filed as `seon.db/a-wildcard-pull-read-is-never-current` (ecef507665ac). It is not a 15-minute fix: it
changes currency semantics in `seon.db`, owned by whoever takes the read-evidence work.

## Evidence

- Bump run: scratchpad `dh-bump-check.txt`. Parent run: scratchpad `bump-reds/parent-run.txt`
  (archive 4.5 s, boot 53.6 s, tests 26.8 s). Candidate run: `bump-reds/cand1-run.txt` (boot 43.8 s, tests 9.4 s).
- The diagnostic runs (`bump-reds/diag-run.txt`) used throwaway candidate commits that printed the evidence
  and the since-basis datoms. The changed datoms after root's message are all on the new entity 48794. The
  only evidence entry without patterns is the config wildcard pull.

## Design check

- The db-test correction holds against the real code. `refused-read` (db.clj:232) and the write refusal
  name their own variant on `:seon.error/type` by design since S1. The test now asserts that type and that
  every other member of the refusal is preserved. The restamp replaces the producer's variant
  (`:seon.config/error`), which loses information. The errors S-reads work deletes read refusals that are
  passed on, so this is not patched here.
- Awkward: no `bin/seon check` option targets an arbitrary commit. Classifying against a parent needed the
  operator's private `candidate-tests!` through a bb driver. A `check --commit SHA` would serve every
  bump classification.
- Happy path: (a) serves the record step (the evidence the fold recorded is what a test reads, with no
  second sink). It adds no mechanism.

## Recommendation

Keep the bump. Root restart of default is not needed for this lane's slice (tests only).
