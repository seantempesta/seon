---
type: issue
status: open
severity: blocker
created: 2026-09-16
tags: [schema, test, gate, publication, shared-tree]
---

# HEAD declares a contract over an untracked schema resource, so every HEAD-snapshot gate refuses

## Problem

`src/seon/test.clj` is committed at HEAD declaring

```clojure
{:malli/schema [:=> [:cat :seon.test.check/request]
                :seon.test.check/response]}
```

(`git show HEAD:src/seon/test.clj`, lines 982-983, and again at `:1028`,
`:1029`, `:1043`). The only declaration of that family,
`resources/seon/schemas/seon.test.check.edn`, is **untracked**
(`git ls-files resources/seon/schemas/ | grep seon.test.check` → nothing; the
file exists in the working tree and declares `:seon.test.check/request`,
`/response`, `/result`, `/text`, `/passed`).

A working-tree run loads, because the untracked file is on disk. **A
HEAD-snapshot run does not**, and that is the run AGENTS §5 tells every lane
with concurrent neighbours to use:

```
bin/test-fast --paths <files> -- seon.turn-test seon.issue-test …
bin/test: snapshot differences from HEAD 0c7711e590397182c5860326c6599a6ad7848416:
bin/test-fast: initialization or execution failed: The loaded function contract cannot compile.
{:seon.error/data #:seon.error{:diagnostic-layer :instrumentation,
                               :diagnostic-operation seon.instrument/apply!,
                               :diagnostic-member seon.test/check-request,
                               :diagnostic-expected [:=> [:cat :seon.test.check/request]
                                                     :seon.test.check/response],
                               :diagnostic-offending :seon.test.check/request,
                               :diagnostic-cause :malli.core/invalid-schema}}
```

The refusal is correct and loud — the defect is upstream of it. Observed
2026-09-16 at `0c7711e59` by the `requiring-resolve` census lane, which could
not gate on a HEAD snapshot at all.

## Why it matters

Every lane that follows the standing instruction gates on HEAD plus its own
paths. While this holds, that gate is red for **all** of them, for a reason
that has nothing to do with their change, and the failure arrives before a
single test runs. It also inverts the usual diagnosis: the working tree is
green and HEAD is red, so a lane that gates on the working tree sees nothing
and a lane that gates correctly sees an unexplained instrumentation refusal.

## Class

This is the committed-tree form of
[live-resources-outrun-the-loaded-program-identity-list](live-resources-outrun-the-loaded-program-identity-list.md)
and the standing rule AGENTS §7 already states: *"A schema resource and its
loaded consumer land in one publication."* Here the consumer landed and the
resource never did — the file was never `git add`ed, so a path-limited commit
of `src/seon/test.clj` alone could not have carried it.

## Fix

Commit `resources/seon/schemas/seon.test.check.edn` together with (or ahead
of) the `seon.test/check-request` consumer — it belongs to whichever lane is
dissolving `bin/test-check` (ranked item #24 of the
[workaround inventory](../../prds/steward-platform/research/workaround-inventory-2026-09-16.md)).

The class fix is a check that answers the question when its subject is
absent: nothing today reports that a declared schema key has no declaring
resource **in the committed tree**. A gate input that only exists in someone's
working tree is exactly the "absence of signal read as health" shape AGENTS §0
names — green working tree, red HEAD, no statement either way.
