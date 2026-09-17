---
type: issue
status: open
severity: blocker
tags: [issue, errors, instrumentation, contracts, wave/instrumentation-error-data]
---

# Instrumentation record mode has no acquired fault recorder

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
