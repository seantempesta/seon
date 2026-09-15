---
type: issue
status: resolved
severity: friction
tags: [issue, operator, class/n7, wave/operator-process-identity]
---

# Derive operator process identity without command substring lists

## Problem

The fresh operator supplements exact process records and advertisements with
OS-wide command-line substring classification. It also positively recognizes
four deleted JVM/CLJS roles as current Seon processes.

## Evidence

- `script/seon/fresh_operator.clj:464-479` identifies current launches by
  literal fragments in an evaluated form.
- `script/seon/fresh_operator.clj:481-510` recognizes `seon.web.server`,
  `seon.host`, a retired database-server jar, and Shadow CLJS watch.
- `script/seon/fresh_operator.clj:512-523` adds another broad `seon.cluster` +
  `start!` substring classifier.
- `script/seon/fresh_operator.clj:586-608` scans every OS process through the
  classifier.
- `test/seon/dev/fresh_operator_test.clj:397-449` positively pins the legacy
  roles instead of deleting them.

## Owner

The operator's `(pid, start-instant, generation)` process records and
root-scoped advertisements.

## Acceptance

Current Seon process discovery derives from recorded identity and explicit JVM
properties/advertisements. The legacy role classifier and its tests are
deleted; arbitrary command strings cannot be misclassified as Seon.

## N7 closure — 2026-09-15

`5342b2b4d` replaced role inference with exact process records and explicit JVM properties. Current `observed-property-processes` in `resources/seon/operator/state.clj` reads declared root/generation and OS identity; arbitrary command substrings are not role evidence. Residual proof: cross-checkout claim custody is the separate still-open `operator-down-misses-a-live-scratch-jvm-from-another-checkout` member, and is being repaired in N7. `seon.classification-test/process-claim-installations-follow-the-exact-root-fact` tests root membership independently of command spelling.

Owner decision explicitly permits closing dissolved members with the residual
proof named. See the N7 landing note for the bounded verification record.
