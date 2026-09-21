---
type: issue
status: resolved
severity: friction
tags: [issue, config, wave/config-application-contract, wave/boot-velocity]
---

# The config dial digest cannot prove initialization is unchanged

At `f6a463de6`, the requested boot shortcut cannot safely skip all of
`seon.config/apply-compiled!` using the stored applied-manifest digest.
`test/seon/config_test.clj:294` proves that the same manifest repairs direct
fact edits; `:297` changes initialization without changing the dial digest
and requires the new initialization fact to appear. The stored digest is
past application evidence, not a digest of the current resulting database.

Three choices for the owner:

1. Recommended: compare desired dials with current config facts (O(config)),
   skip their transaction when equal, and reconcile initialization separately.
   Preserves both current guarantees; still reads the small config manifest.
2. Add a new digest covering the complete manifest and compare it plus the
   current facts. More schema/reset and regression work; complete authored
   identity is available, but cannot alone detect direct fact edits.
3. Let a matching stored digest suppress reconciliation. Smallest edit, but
   explicitly gives up repair of direct fact edits and initialization-only
   changes until an explicit forced apply is supplied. That forced behavior
   would need an API decision; it is not an existing command.

The lane left config unchanged pending this decision. Boot phase timing is
now explicit; the measured 2330 ms config-labelled interval includes process
and root seeding, search acquisition and minimal SCI construction AFTER the
config result was published. It does not establish 2330 ms config reconciliation.
See [the landing note](../../prds/steward-platform/research/one-jvm-redesign-2026-09-22.md).


Owner ruling: compare the desired facts with the current database; do not
use the digest to skip initialization or hand-edit repair. The config writer
now submits only that difference and the applied-manifest digest and its
readers are deleted. RESET NEEDED. The armed fast run was refused at recording
admission by the pre-reset host's old config schema; see the dated landing
note for the exact refusal and remaining generic reconciliation acquisition
cost. This resolves the design decision, not a claim of a quiet-run gate.
