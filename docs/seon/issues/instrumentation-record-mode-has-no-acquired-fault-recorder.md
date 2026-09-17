---
type: issue
status: open
severity: blocker
tags: [issue, errors, instrumentation, contracts, wave/instrumentation-error-data]
---

# Instrumentation record mode has no acquired fault recorder

**2026-09-18 SCI resume:** the recording operation is now acquired by host
and SCI wrappers. `base-ctx`, `acquire!`, installation and cold cluster forks
carry the cluster's existing committer. Both dials share per-arity body facet
enforcement; record arming without custody refuses. The direct real-SCI
regression passes both dials with committed provenance and exact refusal
identity. The issue stays open for complete integration proof, not for the
original missing-operation design question.

The next generic declaration boundaries observed by the full fast run are
`seon.render.value/transacted` (`src/seon/render/value.clj:29`, output `:map`)
and `seon.sci.kernel/failure-value` (`src/seon/sci/kernel.clj:519`, output
`:seon.error/value`). They preserve wrapper contract/arity facets but do not
declare them. Rendering and normal SCI failure normalization consequently
refuse at those helpers. Their owners need the explicit base/facet alternatives
required by program-facts §1q. `seon.error/latest-fact` had the same gap and is
fixed in this lane. `1278dfa16` already fixed `seon.error.refusal/refusal` and
`seon.error/refusal`; the accepted `db/pull` and `db/transact-call` dependencies
remain with the database owner. Full tallies, timings, acquisition corrections
and outstanding proof are in the linked research note below. The historical
sections that follow do not describe the current implementation.

Implementation commit: `f3ae2d055`. Final HEAD-plus-owned-diff worktree fast
result: **157 tests / 851 assertions / 6 failures / 8 errors**. Both new SCI
enforcement/construction/fork regressions and the complete error and
arm-carriage namespaces pass. The full result is still red at the declaration
dependencies above and the separately itemized existing suite findings in the
research note. The worktree excluded a concurrent `dev-cache/digest-file!`
removal that prevented the shared tree's fixture from loading; it did not
change that foreign owner. Cold/platform and live adoption remain owed.

**2026-09-18 host implementation update:** acquisition is implemented on the
host path, but the complete behavior remains blocked by generic return
contracts. The real canonical recorder regression reaches
`seon.error/commit-call` and its `db/pull` of an earlier occurrence.
`src/seon/db.clj:2032–2054` declares each `pull` result as
`[:or :nil :map :seon.error/value]`; it declares neither base nor the new
facets. Returning a stored contract refusal therefore triggers the new
independent check. The writer reports `seon.db/pull returned undeclared error
facets #{:seon.instrument/contract-error}`. Handling that failure encounters
the same gap in `src/seon/error/refusal.clj:4–8`, whose output is
`[:or :nil :map]`. Both generic helpers need explicit facet alternatives under
owner §1q, including explicit base permission. This is a declaration dependency
exposed by enforcement, not foreign in-flight breakage. The database owner
was not edited; its path was clean at this observation, but remains outside
this lane's assigned host files.

The three-namespace fast run completed **79 tests / 424 assertions / 3 failures
/ 1 error**. The recurrence assertions and generic refusal helper identify
the dependency; the arm-release namespace passed. Do not call the host side
green. SCI acquisition and its regressions remain explicitly deferred by the
owner. The linked research note carries implementation and timing evidence.

The sections below preserve the earlier acquisition stops chronologically.

Error-entities PRD §5.2 requires invalid invocations to stop before the body,
record their flat refusal through the existing fault owner, and return it
under `:record`. `wrap-interpreted` instead returns the original function
(`src/seon/instrument.clj:507–547`). Its arguments and the declared environment
carry no recording operation. The committer receives custody as Flow proc
arguments (`src/seon/flow.clj:981`, `:1164`) and calls private
`seon.cluster/commit-fault!` (`src/seon/cluster.clj:2955`).

The [slice-2 dependency record](../../prds/steward-platform/research/error-wrapper-enforcement-2026-09-18.md)
contains interfaces, resume options and the exact successful live probe.
Both invalid input and wrong arity execute the lexical body under `:record`.
No implementation landed.

Resolution: carry a bounded recording/disposition operation with provenance,
per-call mode and truthful recording outcome to the wrapper. Reuse the existing
committer, without global custody lookup or a parallel recorder. Then verify
host and SCI invalid calls never execute, return the recorded refusal under
`:record`, and throw that flat value under `:panic`. Preserve `with-arm` release
on every exit. New base/facet occurrence preservation remains recorder-owned.

## Owner resolution and next boundary — 2026-09-18

The owner authorized acquiring the existing committer at arm time and refusing
`:record` construction when it is absent; `:panic` construction needs none.
The design choice is settled. Implementation remains open at the assignment's
explicit held-file stop: `src/seon/sci/eval.clj:674–685`,
`install-function-contract!`, calls `instrument/wrap-interpreted` without a
recording operation. That exact hunk has a foreign uncommitted change.

`acquire!` at `eval.clj:2101–2117` receives the existing
`:seon.flow/commit-fault!` closure from `cluster.clj:2344–2358` but supplies it
only to acquisition-refusal recording after `base-ctx` installs wrappers.
Thread it through construction and later installation in the held owner;
then resume the wrapper/fixture implementation. The
[resumed handoff](../../prds/steward-platform/research/error-wrapper-enforcement-2026-09-18.md)
records the exact hunk and call sites. No production change or test run is
claimed for this stop.
