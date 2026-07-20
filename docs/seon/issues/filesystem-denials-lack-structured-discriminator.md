---
type: issue
status: open
severity: friction
tags: [issue, agent, architecture]
---

# Filesystem denials lack a structured discriminator

## Problem

`seon.warn/check-fs-denied` must distinguish an allowlist denial from another
failed filesystem operation. The filesystem response currently carries only
`:seon.agent.fs/ok? false`, `:seon.agent.fs/path`, and the prose
`:seon.agent.fs/error`. Both denial and caught-operation failures have that
same shape, so the warning can classify denials only by matching
`"allowed-roots"` in the rendered result.

Treating every failed filesystem response as a denial would give incorrect
grant guidance for ordinary I/O failures. Rewording the denial message would
silently hide the current warning.

## Evidence

`src/seon/agent/fs/internal.cljs` constructs both `->err` and `denied` with the
same three response attributes. `scope-denied` puts the distinction only in
its message. `src/seon/warn.cljs` therefore combines the rendered error-key
marker with the `"allowed-roots"` substring over
`:seon.eval/result-edn`. The source-cleanup fragile-index H2 consumer pass
cannot replace that prose match with a key lookup without changing the
producer contract.

## Expected owner

The `seon.agent.fs` response schema and constructors own the missing
discriminator. The warning consumer should then read the structured result
and select that attribute directly.

## Acceptance

- Denial responses carry a registered structured discriminator such as
  `:seon.agent.fs/denial` while ordinary caught failures do not.
- `seon.warn/check-fs-denied` reads the result as EDN and classifies by that
  attribute, with no substring or regex over rendered prose.
- Tests prove that denial-message rewording does not change classification
  and that an ordinary filesystem failure is not reported as a grant denial.
