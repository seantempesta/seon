---
type: issue
status: open
severity: blocker
tags: [issue, database, flow]
---

# Native branch create rejected retained intent retries

## Problem

`seon.db.registry/create-branch!` rejected a repeated exact create request once
the target route was published, and applied the current-source-head fence
before checking whether Datahike had already created the exact durable branch.
An operator crash after either mutation therefore could not resume from its
retained create intent.

## Evidence

The pre-edit disposable memory-database probe created a native branch on the
first request and returned `:seon.db.protocol.error/duplicate-route` for the
same exact request. Source inspection also showed the stale-source-head check
ran before durable roster adoption, so an unpublished exact branch could no
longer be adopted after an unrelated source transaction.

The target route's current coordinate cannot identify the retained fork by
equality: a correctly published target may have accepted transactions before
an operator restart. Its logical route, exact branch attachment, physical
coordinates, and durable roster membership identify it; restart must return
the freshly resolved current target head.

## Owner

`seon.db.registry/create-branch!` is the one branch creation and route-
publication owner. The operator retains and retries the exact typed request;
it does not infer another route or add a recovery mutation.

## Acceptance

- Repeating an exact create request adopts the published route.
- Repeating it after target writes returns the fresh target head.
- An exact unpublished durable branch is adopted even after the source head
  advances, while its immutable fork coordinate still has to match.
- A genuinely new branch still requires the expected source head to be
  current before mutation.
- Attachment, physical database, durable roster, and fork-coordinate
  mismatches fail closed.
