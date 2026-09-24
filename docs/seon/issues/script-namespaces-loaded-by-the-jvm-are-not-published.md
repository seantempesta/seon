---
type: issue
status: open
severity: friction
tags: [issue, operator, publication, class/n3]
---

# Script namespaces loaded by the JVM are neither rows nor reloaded on publication

## Evidence — 2026-09-24

Lane fix-operator-unreadable-prepl-value edited `script/seon/operator.clj`. Default JVM resolved
`(clojure.java.io/resource "seon/operator.clj")` to `file:/Users/sean/src/seon/script/seon/operator.clj`,
yet `[:find ?e . :where [?e :seon.fn/sym seon.operator/prepl-value!]]` answered nil: the namespace has
no program rows. Hook publication after the edit reported "red after adoption (host-bound ...)" and ran
`seon.operator-test` against the previously loaded Vars (7 fail); `(resolve 'seon.operator/read-prepl-val)`
was nil. Only an explicit `(require 'seon.operator :reload)` (54 ms) installed the change, after which
`bin/test-check default --ns seon.operator-test` passed (run cf91ab04787c, 8/8).

## Boundary

Babashka callers (the hook, `bin/seon`) read the file fresh, so they saw the change; JVM callers and
JVM tests did not, and nothing reported the mixed generation. Member of
[class-loaded-artifacts-lack-source-identity](class-loaded-artifacts-lack-source-identity.md).

## Acceptance

A saved edit to a `script/` namespace the JVM has loaded is either published and reloaded through the
ordinary adoption path, or refused by name as not installed; a test reaching it never runs green or red
against stale Vars silently.
