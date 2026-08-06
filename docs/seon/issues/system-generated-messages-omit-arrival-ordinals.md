---
type: issue
status: open
severity: friction
tags: [issue, runtime, ordering, render, errors, live-drive]
---

# Give system-generated messages arrival ordinals

## Problem

Inbound and ordinary delivered messages carry
`:seon.cluster.message/ordinal`, but renderer-failure and error-notice message
constructors omit it. Ordering consumers silently substitute zero, so several
messages in one transaction no longer carry the numeric fact that establishes
their arrival order.

This regresses the general message-order ruling recorded by the resolved
[Preserve lexical order for same-transaction messages](docs/seon/issues/archive/same-transaction-message-order-is-lexical.md).

## Live evidence — default cluster, 2026-08-06

At final observed basis transaction `536871035`, 17 message entities existed.
The one inbound message, eid `23679`, carried ordinal `0`; the other 16 lacked
the attribute. The missing rows included maintenance/error notices such as
eids `23640`, `23650`, `23655`, `23660`, and `23701`, plus renderer-failure
messages eids `23663`–`23667`, `23708`–`23713`, and `23716`.

The constructors omit the fact at `src/seon/error.clj:715-730` and
`src/seon/render.clj:445-487`. Consumers compensate with `get-else ... 0` at
`src/seon/render/transcript.clj:164,316` and
`src/seon/cluster/work.clj:684`, which hides rather than declares the missing
ordering fact.

## Owner

The one message transaction-data construction contract, including system
producers. Ordering remains a numeric database fact; identity strings and
consumer defaults are not ordering mechanisms.

## Acceptance

- Every newly committed message row carries an arrival ordinal.
- A transaction producing several error/render notices records deterministic
  ordinals and both work derivation and transcript rendering use them.
- Ordering consumers no longer need a fallback for current rows; historical
  rows remain readable without migration.
