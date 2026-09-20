---
type: issue
status: open
severity: friction
tags: [issue, effect, contracts, error-model]
---

# Effect settlement has no declared refusal union for its payload

Observed at HEAD `61374edb8` by ops-effects-2. In
`src/seon/effect.clj:543`, `settle-value!` obtains an admitted handler value;
at line 557 it uses `:seon.error/kind` to exclude refusal maps from
`:seon.effect/arguments`. `settle-call` sends those arguments through
`declared-datoms`, so this distinction controls database writes, not merely
display. The result itself remains stored in result EDN.

The local `admitted-value` helper (`src/seon/effect.clj:206`) delegates to
`seon.sci.admit/admit-value` (`src/seon/sci/admit.clj:790`). Its output is
`:seon.sci.admit/admitted-value`; the nested `/value` member is explicitly
`:any`, with a polymorphic-boundary exemption
(`resources/seon/schemas/seon.sci.admit.edn:68`, `:71`). It does not declare
a union of handler refusal facets. A timeout-only or effect-only check
would let other capability refusals become effect datoms. A base-three
replacement is not authorized by conversion PRD §1.3's literal exception:
this callee does not declare `:seon.error/value`.

This invokes §6's consumer requiring a distinction the declared facet
members do not express. It is independent of foreign edits and of the
already deferred gate-selection facets. No production edits were made.

Three options, with engineering estimates rather than measured runtimes:

1. **Recommended: explicitly permit base recognition at this polymorphic
   result-inspection boundary.** Keep the check local, declare the complete
   pass-through error contract, and preserve the original observation.
   Cost: about 1–2 hours including canonical regression coverage. Guarantee:
   complete base errors do not become ordinary effect arguments. Give up:
   the literal requirement that this consumer use one domain facet member.
2. Carry the selected handler's declared output contract into settlement and
   validate the returned facets against that contract using the existing
   projection. Cost: about 3–6 hours across effect contracts and fixtures.
   Guarantee: refusal recognition follows the actual handler declaration.
   Give up: a mechanical sweep; this changes settlement's carried inputs and
   still needs a rule for handlers with polymorphic output contracts.
3. Defer this consumer explicitly and continue the remaining conversions.
   Cost: about 15 minutes to retain and document the debt; eventual repair
   still costs option 1 or 2. Guarantee: no unreviewed change to datom
   admission. Give up: a kind-free effect namespace at this checkpoint.

Acceptance: an orchestrator ruling, then a canonical fixture regression
showing ordinary handler attributes are recorded while a complete returned
refusal retains its evidence without being admitted as ordinary arguments.
