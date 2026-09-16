---
type: defect
status: open
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
