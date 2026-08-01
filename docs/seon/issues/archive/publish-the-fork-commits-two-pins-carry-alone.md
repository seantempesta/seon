---
type: issue
status: resolved
severity: friction
tags: [issue, dependency, source-grounding]
---

# Publish the fork commits two pins carry alone

## Problem

Two submodules Seon builds against are pinned at a commit that exists
**only in this working copy**: past a public tag, carrying Seon-authored
changes, on no remote branch. A fresh clone plus `git submodule update`
cannot reproduce the build, and a `git gc` inside either submodule could
have discarded the work outright.

`deps.edn` also states the opposite of the truth for one of them.

## Evidence

`reference-code/clj-kondo` is pinned at `57252e07`, which is tag
`v2026.07.24` plus two Seon commits — `0fc2f636` "Resolve source metadata
keywords in analysis" and `57252e07` "Resolve attr-map metadata keywords in
source namespace". `git branch -r --contains 57252e07` returns nothing. It
is consumed as `:local/root` at `deps.edn:18-22`, and the build indexer
depends on exactly that metadata resolution.

`reference-code/core.async.flow-monitor` is pinned at `fbff842` = tag
`v0.1.5` (`376d6ec`) plus one Seon commit, "Publish the bound monitor port".
No remote branch contains it. Meanwhile `deps.edn:92-93` says:

```clojure
;; JVM-only testbed dependency; vendored source matches
;; the published v0.1.5@376d6ec coordinate exactly.
```

That comment is false — the pin is one Seon commit past `376d6ec`.

Both pins were reachable from no ref at all until this sweep anchored them
(`clj-kondo` `master` fast-forwarded to the pin; a `seon` branch created at
the `flow-monitor` pin). That stops the GC risk locally but does not make
either reproducible elsewhere.

Full sweep: `docs/prds/sci-execution-runtime/research/upstream-delta-sweep-2026-07-31.md`.

## Owner

The root dependency ledger (`deps.edn`) plus the two fork checkouts. The
`sci` fork is the convention to copy: a named `seon` branch, pushed to our
own remote, with the artifact coordinate naming it.

## Acceptance

- Both pinned commits are reachable from a branch on a remote we control,
  and a clean clone plus `git submodule update --init` reproduces the pin
  without any local-only ref.
- The `deps.edn` comment states the actual relationship between the vendored
  revision and the published coordinate, or is deleted.
- The at-risk scan in the sweep document returns empty when re-run.

## Resolution

Resolved on 2026-07-31. The owner forks publish `clj-kondo/seon` at
`57252e07975710aa579b24f0d1b2b1e04195caa2` and
`core.async.flow-monitor/seon` at
`fbff8424696c7080ee7dc27b55cde1659ec18d8f`; both tips were verified through
GitHub's API. Commit `cdcf7cc69` makes the owner repositories the submodule
clone authorities, and `8805afd0c` corrects the false `deps.edn` claim.
The complete twelve-fork proof is recorded in
`docs/prds/sci-execution-runtime/research/fork-publication-2026-07-31.md`.
