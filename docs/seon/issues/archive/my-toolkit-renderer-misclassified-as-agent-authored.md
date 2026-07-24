---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, render]
---

# my.* toolkit renderers misclassified as agent-authored by a name-prefix rule

## Evidence

`my.plan` value dispatch fails live and in `test/my/plan_test.cljs:167`
("Missing custom renderer my.plan.internal/plan-html"; recent runs show
`[1 1]` → `[0 0]` deterministically). Two cooperating causes, both verified
from source:

- U7's static trusted renderer table (`src/seon/render/core.cljc:16-32`)
  carries only `seon.render.*` entries — no `my.plan` renderers.
- `seon.error/agent-authored-sym?` (`src/seon/error.cljc:211-225`)
  classifies trust by NAME-PREFIX REGEX (`seon|clojure|cljs|sci|goog`), so
  every compiled first-party `my.*` toolkit fn is treated as agent-authored
  and refused the trusted direct path.

## Why this is a class, not an instance

The prefix regex is a hand-maintained classification rule — exactly the
smell R34 dissolved for registration provenance. Trust must be DERIVED
(asserting-transaction provenance / artifact-inventory membership), never
inferred from a namespace spelling. Any future first-party toolkit
namespace outside the blessed prefixes re-hits this class.

## Proposed owner ruling (recommendation)

Replace `agent-authored-sym?`'s prefix rule with the R34 derived
provenance: core-admitted corpus rows (or compiled artifact-inventory
membership) ⇒ core; agent-turn provenance ⇒ agent; unknown ⇒ agent
(fail-closed). The static renderer table then either enumerates by the
same computed rule or is deleted into it. `test/my/plan_test.cljs:167`
becomes the class regression.

## Acceptance

- `my.plan` value dispatch renders live via the trusted path.
- No name-prefix trust classification remains in `seon.error`.
- One regression: a compiled `my.*` renderer classifies `:core`; an
  agent-authored renderer with identical naming classifies `:agent`.

## Resolution

Resolved by R43. The compiled schema projection now carries authorship derived
from each current `:seon.fn/source` datom's asserting transaction plus exact
P1b artifact exports. Both values participate in the projection fingerprint
and its guarded reuse path.

`seon.error/agent-authored-sym?` is the sole classifier. Corpus provenance
decides before artifact membership and unknown symbols fail closed. Compiled
renderer lookup is resolution only; it grants no trust.

Recurring proofs cover both tiers, the `my.plan` render path, fingerprint
reuse, and the Datahike no-op case where an agent replaces source while
reasserting an identical core spec.
