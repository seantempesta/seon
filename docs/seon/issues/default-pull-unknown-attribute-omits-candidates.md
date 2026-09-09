---
type: issue
status: open
severity: friction
tags: [issue, database, agent, wave/agent-context]
---

# Default pull refusal omits registered attribute candidates

## Problem

The chart promises that an unknown read attribute teaches installed candidates.
Default's JVM `seon.db/pull` instead returns the generic Datahike exception data.

## Evidence

On 2026-09-09 at 21:28 UTC, with an explicit default connection and
`seon.schema/call-with-projection` around the read:

```clojure
(seon.db/pull database [:seon.agent/runtime] [:seon.agent/id "juniper"])
```

The actual result was `:seon.db/invalid-read`, with message
`Bad entity attribute :seon.agent/runtime ... not defined in current schema`.
It contained neither `:seon.db/registered-candidates` nor the owning declaration.
The full result is retained in
[the cookbook](../../prds/context-generation/research/context-cookbook-2026-09-09.md).
Source has `unknown-attribute-error` and candidate derivation in `src/seon/db.clj`;
this is a live observation, not an attribution to those definitions or to the
concurrently edited transaction path. Adoption has not converged during this slice.

## Owner

The existing `seon.db` read-admission owner; currently a protected file held
by transact-feedback. Verify after its publication before changing code.

## Acceptance

On the canonical armed fixture and adopted default, a pull selecting an absent
attribute returns a typed refusal naming that attribute and installed candidates.
An absent entity or unknown attribute must not be interpreted as a healthy empty read.
