---
type: issue
status: resolved
severity: blocker
tags: [issue, config, sci, mcp]
---

# Refuse incomplete result caps at construction

## Problem

`seon.config/result-caps` projected admission limits with `select-keys` and
returned the result without proving that every required config attribute was
present. A sovereign cluster whose database predated a required attribute
therefore constructed a partial caps map. `seon.sci.admit` received that map
and failed later with a raw `NullPointerException` while coercing the absent
value to `long`, obscuring the missing config fact that caused the refusal.

The defect class is construction of a partial required config projection, not
the downstream numeric coercion.

## Evidence

On 2026-08-11 every `eval_clj` form against the live default cluster,
including `(+ 1 2)`, failed at value admission with `Cannot invoke
Number.doubleValue() because x is null`. Direct database inspection showed a
stale config population. The current default branch also predates the
`:seon.config.ai/chars-per-token-prior` attribute, so it requires reset or
refork rather than data migration.

An audit of `src/seon/config.clj` found no other cap or limit projection built
with the same unchecked `select-keys` shape. Other uses are intentionally
sparse overlays or already validate completeness.

## Owner

`seon.config/result-caps` owns construction of the complete admission caps
value. `seon.cluster/mcp-project` owns preserving a construction refusal as a
flat MCP value before admission.

## Acceptance

- Removing any required result-cap fact produces a flat `:seon.error` value
  naming the absent config key.
- Value admission never receives that partial projection.
- A complete isolated cluster evaluates an ordinary JVM form through MCP.
- Config reconciliation restores a missing datom when its schema attribute is
  already installed; an older branch missing the attribute is reset or
  reforked, never migrated.

## Resolution

Resolved by commit `61fb28db6`. `result-caps` now either returns the complete
projection or a flat `:seon.config/missing-result-cap` value carrying the
absent key. The MCP projection returns that refusal directly and does not call
value admission.

The class regression retracts `:seon.config.eval.result/max-nodes` from a
fully populated database and proves that construction names that member.
Focused `seon.config-test` verification passed with 17 tests and 151
assertions. An isolated `caps-construction` cluster evaluated `(+ 1 2)` to `3`;
after retracting the same fact its MCP result was the flat named refusal, and
`bin/seon config apply` with the defaults-only manifest restored the fact and
the result `3` without restart.

The complete changed-path gate reached 493 tests and was stopped at the
unrelated `seon.cluster.agent-test/wake-routing-conservation-property`
boundary. Its isolated confirmation failed on the shrunk action sequence
`[[:message :create-and-message :create-and-message :message :create
:message]]`; no source or test in that owner was changed or rerun here.
