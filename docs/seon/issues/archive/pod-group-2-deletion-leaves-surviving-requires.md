---
type: issue
status: superseded
severity: cleanup
tags: [issue, agent, runtime]
---

# Remove surviving requires of deleted pod Group 2 namespaces

## Problem

The protected Group 5 pod entry still requires namespaces deleted by pod cut
Group 2. The Group 2-owned source and test seams are removed. Top-level
integrated the configuration repair in `aa766168e`; the remaining Group 2 seam
repair is commit `10cc8fd11`.

## Evidence

The fresh exact require scan after Group 2 seam cleanup finds only four
protected references:

- `src/seon/client.cljs:56`, `:175`, `:180`, and `:186` still require deleted
  schedule, filesystem, search, and shell namespaces.
- `my.blob`, `seon.agent.message`, and `seon.agent.web` no longer require or
  fall back to their deleted CLJS leaves; their existing JVM leaf bindings
  remain.
- `config/system.edn` no longer advertises deleted search, filesystem, or shell
  namespaces in `:seon.eval/home-requires`; top-level integrated that cleanup
  in `aa766168e`.
- The CLJS-only blob, filesystem, schedule, search, shell, and web-search tests
  are deleted. The portable filesystem and shell core tests retain their JVM
  assertions and no longer inspect deleted public CLJS entries.

Focused JVM proof loads the three surviving capability namespaces and runs the
blob host leaf, web host leaf, message portable, filesystem core, and shell core
tests: 6 tests, 33 assertions, 0 failures, 0 errors.

## Owner

Group 5 owns the remaining `seon.client` removal. Deleted namespaces receive no
replacement.

## Acceptance

A source/config require scan has zero references to the Group 2 namespaces.
The surviving JVM capability namespaces continue to load through their existing
owners.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
