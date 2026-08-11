---
type: issue
status: resolved
severity: blocker
tags: [issue, operator]
---

# --force refork can destroy the branch then fail silently, leaving no cluster

2026-08-10: `bin/seon init default --force` destroyed the default branch,
then the refork conversation with the live shared JVM (pid 31570) went
silent past the 30 s `:seon.config.operator/event-silence-backstop-ms`
backstop and the command failed — leaving NO default cluster (status
showed only a leftover lane scratch cluster; web down). An immediate
retry succeeded in seconds, so the loss window is real but the cause is
unattributed. Related but distinct from
[init-publication-silent-beyond-backstop](init-publication-silent-beyond-backstop.md)
(publication-phase silence) and the 2026-08-08 refork store-hold fixes
(`912199d73`..`e83fd4bae` — those refusals were loud and named).

Class: destroy-then-fail must be unrepresentable — the destructive half
of `--force` should not be able to commit while the refork half can
still fail silently; either the refork completes or the destroy does
not happen (one atomic custody, or destroy staged after the fork
proves viable).

Acceptance: a forced refork whose refork phase fails leaves the prior
branch intact and refuses loudly with the phase named; a regression
injects the silence and proves the branch survives; the silent-refork
cause on a live shared JVM is attributed with evidence.

## Resolution — 2026-08-10

The destructive state was a composition defect. The operator retired the
named branch as one committed operation, then attempted to create its
replacement as a second operation. A silent or failed second half therefore
made absence durable.

Commit `69d95a4be` changed `seon.cluster.registry/reset-cluster!` to one
expected-head `force-branch!`: the replacement commit becomes the branch head
atomically, and an injected failure leaves the exact prior head and facts
reachable. Commit `59e71e0cd` removed the operator's retire-then-refork caller:
it quiesces the process and no-follow cluster directory without touching the
branch, reacquires the store after process stop, and calls only the atomic
reset owner. There is no longer a committed state between destruction and
replacement.

The reported silence was amplified by the caller publishing source before a
named fork and by its parent buffering child output until EOF. Commit
`a0738794f` makes named init consume `current-src`, composes reset as one
publish-and-fork child, and relays child progress while it is read. On an
isolated root, the clean-run failing reset boundary changed from 157.617 s and
its backstop failure to 61.22 s and success. The registry regression injects
the replacement failure and proves the prior branch commit and facts remain.
