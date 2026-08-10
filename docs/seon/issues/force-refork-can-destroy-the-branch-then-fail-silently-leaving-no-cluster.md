---
type: issue
status: open
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
