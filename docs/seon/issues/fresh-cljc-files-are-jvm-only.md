---
type: issue
status: open
severity: cleanup
tags: [issue, deletion]
---

# Make fresh CLJC namespaces portable or name them CLJ

## Problem

Four fresh `.cljc` namespaces contain unconditional JVM code. The CLJS build is
off, but the repository rule still makes `.cljc` a portability claim; these
files are neither portable cores nor honest JVM leaves.

## Evidence

- `src/seon/ai.cljc:67-71,429,540,607-611,689-690,748` unconditionally imports
  and invokes JVM HTTP, IO, crypto, and exception APIs.
- `src/seon/config.cljc:14-21,104-119,179-184` uses Java IO and `Runtime`.
- `src/seon/cluster/loop.cljc:63,1360,1388-1389` uses `Date`, `Thread/sleep`,
  and `requiring-resolve` unconditionally.
- `src/seon/schema.cljc:30,356-372` conditionally imports `MessageDigest` but
  calls it unconditionally; `:1051-1092,2063-2160` also retains retired-tier
  and compatibility behavior.
- A CLJS clj-kondo pass over fresh source reported 19 platform errors across
  these four files, including unresolved `Throwable`, `with-open`, `Runtime`,
  `Thread`, `MessageDigest`, and `requiring-resolve`.

## Owner

Each capability family: portable pure core plus one thin JVM leaf, or a truthful
`.clj` namespace when no portable consumer exists.

## Acceptance

Every remaining `.cljc` namespace passes CLJ and CLJS load/lint for its
unconditional forms. JVM-only owners use `.clj`; reader conditionals occur only
at platform entry functions, and retired compatibility branches are deleted.
