---
type: issue
status: open
severity: blocker
tags: [issue, test, runner, tooling, pre-read]
---

# `bin/test`'s shared published base compiles other lanes' half-edits, so one lane's edit blocks every lane's gate

## Problem

Three concurrent lanes on 2026-09-05 (`run-loop-velocity`,
`prospective-prompt`, `status-face`) each finished a correct change and
could not run their focused gate: `bin/test <ns>` prepares "one shared
published test base" from the WORKING TREE, which at that moment carried
another lane's in-flight 262-line edit to `src/seon/fn.clj`. The indexer
refused the base:

```text
Conflicting upsert: "seon.fn.index/10477" resolves both to 593 and 2994
Program indexing transaction was refused.
bin/test: shared published base preparation failed
```

No test ran, no tally was produced, three lanes stopped with verified fixes
uncommitted (lane rule: never commit unverified). The earlier
`mcp-status` lane hit the same class with a torn read of `runner.clj`.
File-disjoint ownership does not protect a lane's GATE, because the gate
compiles files the lane does not own.

## Owner

Additional observation, 2026-09-05: direct focused tests also encounter an
unclassified `IndexOutOfBoundsException` at `seon.fn/exact-source:142`
during fixture population. The generated-form test reported zero passes and
one error before its body; a subsequent invocation reached 81 passes and no
failures or errors. The web evaluation-wake regression independently saw the
same pre-body failure, then reached 80 passes and no failures or errors.
`build-manifest` reads source contexts before asking the analyzer to reread
the paths. Concurrent changes can therefore make source spans inconsistent;
the offending file was not captured, so that cause remains a hypothesis for
these two failures. The boundary must identify the file and span rather than
silently clip or publish inconsistent source.

Commit coordination exposed the same missing snapshot at the history seam.
One lane checked `HEAD`, another lane committed, and the first lane's pending
`git commit --amend --only` then amended the newly arrived commit instead of
the commit it had inspected. No content was lost, but two independently owned
changes became one commit. Any operation that intends to replace a specific
commit must compare the expected object ID at the mutation boundary; a prior
`git log` is not authority once another lane can advance the shared branch.

`bin/test` (the shared published base preparation) and the indexer's
behavior on an inconsistent tree (`seon.fn/index!` / publication).

## Options (owner's call; simplest viable first)

1. **Build the shared base from committed HEAD plus the invoking lane's
   own uncommitted files only.** The lane names its owned paths (it
   already does in its spec); `bin/test --owned <paths>` overlays exactly
   those working-tree files on a `git archive HEAD` checkout. Guarantee: a
   lane's gate sees its own edits and nobody's half-edits. Cost: a flag and
   an overlay step; a lane that forgets the flag tests HEAD + nothing.
   Give up: implicit "test whatever is on disk".
2. **Build from HEAD only; lanes commit before their gate.** Guarantee:
   deterministic base. Cost: a red gate leaves a bad commit on the shared
   branch; lanes must amend or follow up. Give up: verify-before-commit.
3. **Keep the working-tree base; make the indexer refuse LOUDLY naming the
   torn file** and have `bin/test` retry once after a short wait. Guarantee:
   the message says whose file; sometimes the edit settles. Cost: races
   remain; gates still block. Give up: determinism.

## Acceptance

Two lanes with disjoint owned paths, one of them mid-edit on a file the
other's tests load, both reach a tally; the base preparation names the
files it overlaid; one regression per claim.
