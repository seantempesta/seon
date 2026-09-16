---
type: defect
status: resolved
severity: blocker
tags: [database, schema, test, adoption]
---

# `:seon.test/adoption-identities` is transacted as `#{nil}`

## Observed

`bin/seon init --dev default --changed src/seon/edit/jvm.clj`, 2026-09-16
07:4xZ, reloaded and instrumented cleanly and then refused at the cluster
population transaction:

```
✗ The cluster population transaction was refused.
seon.db/transact! refused transaction data at [1 :seon.test/adoption-identities]:
expected a set, got a set. Fix: Supply a set at [1 :seon.test/adoption-identities].
:seon.db/offending #{nil}
:seon.schema/form [:set :seon.db/ref]
:seon.boot/population :seon.source/commit-id
```

A `[:set :seon.db/ref]` whose only member is `nil`. The refusal's own wording
("expected a set, got a set") is a second, smaller defect: the diagnostic
compares the container and not the member that failed, so it names nothing
actionable.

## Attribution

`:seon.test/adoption-identities` and `src/seon/test/runner.clj` belong to the
test-runner lane; its `init --dev default --changed src/seon/test/runner.clj`
(pid 64390) held the operator lifecycle lock immediately before the refusing
run. The effect-facts lane met this while adopting and did not touch that
namespace.

## Done when

- whatever computes `:seon.test/adoption-identities` refuses, or omits the
  key, rather than transacting a set with a `nil` member — absent is no key;
- the write diagnostic names the offending MEMBER of a collection-valued
  attribute instead of re-describing the container.

## Cause (2026-09-16)

`src/seon/cluster.clj:1988` (pre-fix) derived `changed-identities` from the
scalar publication rows with a per-call roster of three identity attributes
(`:seon.ns/name`, `:seon.fn/sym`, `:seon.test/sym`). `seon.program/identity-attributes`
declares six. Every published file artifact carries a `:seon.fn.file/path`
digest row (`src/seon/fn.clj:988`) whose digest always changes, plus one
`:seon.lint/id` row per analyzer finding (`src/seon/fn.clj:991`); the roster
reads both as nil, so `(set changed-identities)` became `#{… nil}`.

Live reproduction, 2026-09-16 07:4xZ on `default`, from the triggering path:
`(seon.fn/build-artifact {:seon.fn.file/path ".../src/seon/edit/jvm.clj" …})`
returns 11 rows — `{:seon.fn.file/path 1, :seon.ns/name 1, :seon.fn/sym 8,
:seon.lint/id 1}` — and the old roster yields 2 nil members.

## Fix

`seon.cluster/adoption-identities` (`src/seon/cluster.clj:1962`) is now the one
derivation: identities come from `seon.program/row-identity` (the identity
authority, never a per-call roster) and only declaration identity attributes
(`adoption-identity-attribute?`, `src/seon/cluster.clj:1953`) are recorded.
A file-digest row and a lint finding are not declarations a test can reach;
their file is already recorded in `:seon.test/adoption-inputs`, so the key
carries real refs and absence is no member. The same filter now covers the
complete-rebuild branch, whose `seon.fn/index!` identities can also name
`:seon.fn.file/path` and `:seon.lint/id` rows that
`seon.test/check-adoption`'s four-attribute pull cannot resolve.

Regression: `seon.adoption-rows-test/adoption-identities-carry-no-nil-member`
(5 assertions, 0 failures, in-process on `default` 2026-09-16 07:55Z).
Live proof: `bin/seon init --dev default --changed src/seon/cluster.clj`
exited 0, `:seon.source/commit-id` advanced
`6aaa4811-588e-5b6e-8067-e568825ae892` → `6aaa4a92-92b9-5f83-a368-03f882c0cd36`.

## Still open

The second, smaller defect in the observation stands: the write diagnostic for
a collection-valued attribute says "expected a set, got a set" instead of
naming the offending MEMBER. That belongs to `seon.db`'s write admission, not
to this derivation; it is not fixed here.

`adoption-identity-attribute?` is a small declared constant because
`seon.program`'s declaration-identity set is private. Deriving it there (one
public predicate over `seon.program/shapes`) would remove this mirror.
