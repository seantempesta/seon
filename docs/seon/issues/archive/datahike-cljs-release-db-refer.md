---
type: issue
status: resolved
severity: blocker
tags: [issue, database, cljs]
---

# Keep CLJ-only Datahike release function out of CLJS refers

## Problem

`datahike.writing/release-db` is defined only for CLJ, while
`datahike.versioning` unconditionally referred it from a `.cljc` namespace. A
clean CLJS dependency compilation therefore failed before Seon's Bun artifact
could build.

## Evidence

Resolving Datahike commit `c1faf70d` into a fresh Git dependency cache and
running `bin/test-cljs --test=seon.embed-test` failed at
`datahike/versioning.cljc:1` because `datahike.writing/release-db` did not exist
for CLJS. The owned Datahike source now reader-conditions that one refer to CLJ;
the surrounding cross-platform API remains unchanged.

## Owner

The owned Datahike `datahike.versioning` namespace and its platform-specific
resource-release implementation.

## Acceptance

- A clean CLJS build loads `datahike.versioning` without referring to a missing
  CLJ-only function.
- JVM historical database release remains covered by its focused lifecycle
  tests.
- Seon does not carry a dependency workaround or alternate versioning path.
