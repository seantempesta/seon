# Attribute declaration refusals are values — 2026-09-28

The storage bridge already derives native attributes from the retained Malli
root. `attribute-declaration` now returns a named refusal for an unstorable
root; optional-property filtering consumes that value. This removes throwing
and catching an exception for each expected unstorable declaration. Malli's
`mr/schema`, `m/schema?` and the existing `compiled-storage` remain authoritative.
The legacy `malli->datahike-attr-in` wrapper keeps throwing the complete refusal
for callers not yet converted; this slice does not claim to remove every throw.

Applied the supplied no-crash patch. The algorithm stays O(attributes), but
expected refusals allocate no exception/stack trace on the storable-check path.
Source +45/-31, schema +5/-1, test +20/-0. No new cache or registry.

On default's actual projection, all 3736 qualified attributes produced identical
storable booleans (2253 true): parent 117.223 ms, candidate 94.290 ms, a 19.6%
reduction. The supplied author's separate 423 → 72 ms result is not our result.
The candidate outer function was unarmed, nested existing helpers armed.
This read-only operation produces zero commits and zero store bytes. A scratch
projection containing the new declarations validated the absent-attribute
refusal against its named Malli schema; 1/10/100 probes returned 1/10/100 named
refusals. The deterministic regression counts 1/10/100 declaration calls and
zero calls to the legacy throwing wrapper, and validates the refusal's data.

Archive `88c65fc1922e3ef781ed285edf308f69ac05e710` plus this slice and the
separate observed-exit slice: lint src/script/test with cache false, 0 errors,
1617 warnings, 7.227 s. Contract compilation: 499 files, 24.808 s, only the ten
existing foreign docstring/markdown script findings named in the arity landing.
No tests were run by this lane. Drill `749f18b1e236` passed: base fresh boot 153.652 s, move 29.781 s,
candidate fresh boot 164.747 s; total 431.126 s. Boot remains far above
the target; neither of these cuts claims to solve whole-boot cost.

Root's focused tests:

- `seon.schema.datahike-test/unstorable-declarations-return-values-without-the-throwing-wrapper`
- `seon.schema.datahike-test/schema-entity-properties-lift-only-when-their-declarations-are-storable`
- `seon.schema.datahike-test/unsupported-database-attributes-refuse-at-one-rule`
- `seon.schema.datahike-test/a-schema-absent-from-the-projection-refuses-by-name`

Evidence: `tmp/astra-store-fixes-20260928/attribute-probe.clj`,
`attribute-final.json`, `attribute-validation.json`. Foreign boundary: MCP,
turn, profile, health and cache lookup changes are excluded. The shared turn
file briefly had a syntax error during this work; checks use the archive and
never load that foreign working-tree edit. Default was neither stopped nor
reloaded by this lane.
