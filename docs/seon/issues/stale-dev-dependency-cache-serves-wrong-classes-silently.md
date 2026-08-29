---
type: issue
status: open
severity: friction
tags: [issue, test, tooling, boot-velocity]
---

# A stale dev-dependency cache serves wrong classes silently

## Problem

`target/dev-dependency-classes` (the AOT'd dependency closure,
`dev_cache.clj`) went stale on 2026-08-17 and was served unchanged
until 2026-08-29: every consumer that put the old digest's classes on
a classpath got partial/old compilations of sci, tools.reader, and
cheshire, producing load failures that MASQUERADED as unrelated
defects — `namespace 'sci.impl.interpreter' not found`, missing
`whitespace?`, a cheshire `copy-arglists` arity clash. Four
implementation lanes independently stopped on these, mis-attributed to
concurrent tree churn, until `clojure -T:dev-cache refresh` (status
:rebuilt, 363 namespaces) cleared all of them.

`dev_cache.clj` HAS the machinery to detect staleness (`ensure-cache`
compares input digests) — but nothing on the consuming paths invokes
it; the cache is only refreshed when a human runs the alias. A mirror
with a drift check that never runs is a mirror with no drift check:
absence of refresh reads as health, the house failure class.

## Owner

The consuming seams: wherever the cache directory enters a classpath
(operator boot, and `bin/test`'s checkout symlink at `bin/test:303`),
call the existing `ensure-cache` (cheap when current) or refuse loudly
with the digest mismatch — never serve a stale digest silently. Also
record WHICH digest a run used in its `test-run.txt` line so the next
mis-attribution is impossible.

## Acceptance

With a deliberately stale cache (touch a dependency version), the next
`bin/test`/boot either rebuilds automatically or fails naming the
digest mismatch; a run's transcript names the digest it used.
