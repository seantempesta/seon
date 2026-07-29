---
type: issue
status: open
severity: friction
tags: [issue, config, architecture]
---

# "A dial exists" has no single authority

Surfaced by the 2026-07-29 config-chain trace (the no-auth dial was
registered, leaf-implemented, and assembly-read — yet unconfigurable,
because manifest admission and database installation are separate
contracts nobody crossed). The landed cross-check test fences the
class; this issue records the DISSOLUTION the fence approximates:

**Derive the `:seon.config/manifest` map from the dial registrations**
— one authority (the registered `:seon.config.*` attributes and their
schemas), the closed manifest map computed from it, so a dial that
exists is admissible and installable by construction. The fence test
then deletes itself.

## Acceptance

Adding one dial = one registration in `schema/config.edn`; it is
immediately manifest-admissible, database-installable, and
defaults-documentable, with no second edit anywhere. The cross-check
test is deleted in the same change (its class is unrepresentable).
Judge at implementation whether the entity-map derivation belongs in
the schema loader or the config gate — one owner, no new mechanism.

## Evidence

The 2026-07-29 `:seon.config.ai/max-tokens` accretion is the third
observed hand-sync. One dial required
manual edits to its leaf registration, closed manifest, effective map,
database entity map, shipped defaults, descriptor/request schemas,
provider assembly, wire projection, and the honest-population expected
set. The derivation remains deliberately out of scope for that blocker
repair.

## Triage 2026-07-29

**PRESSING — relaunching config-authority fix lane.** Current source still
requires a dial to be registered separately for schema admission, database
installation, and shipped defaults; the owner is the config/schema
reconciliation rung.

## Analysis 2026-07-29 — chunk 1

### Current synchronization sites

One dial currently crosses every one of these independently maintained sites:

- **Leaf registration.** Dial value schemas are spread across
  `schema/config.edn`, `schema/flow.edn`, `schema/admit.edn`, and
  `schema/ai.edn`.
- **Manifest admission.** `schema/config.edn:153-222` repeats every dial as an
  optional entry in the closed `:seon.config/manifest` map.
- **Effective projection.** `schema/config.edn:224-286` repeats the population
  and separately decides which values may be absent.
- **Database installation.** `schema/config.edn:288-342` repeats it again in
  `:seon.config/entity`. This copy is load-bearing:
  `seon.schema.form/database-attributes` derives the installed Datahike
  attributes from entity-map entries.
- **Defaults.** `config/default.edn` repeats every static default and its
  provenance; `seon.config/computed-defaults` separately supplies compute
  concurrency. Absence remains meaningful for the web port, no-auth, backup,
  and other optional dials.
- **Population fences.** `config_test.clj:18-150` repeats the complete expected
  set, re-derives requiredness, observes provider lookups, and cross-checks
  admission against installation.
- **Consumers.** Flow queue depth/concurrency are read by `seon.flow`; result
  caps by `seon.config/result-caps`, `seon.sci.*`, `seon.render.*`, and message
  projection; the eval limit by cluster/loop evaluation; error disposition,
  recurrence, and escalation by `seon.cluster`, `seon.cluster.loop`,
  `seon.error`, instrumentation, and SCI admission/eval; web port by cluster
  serving; render coalescing by `seon.render.web`; episode length by
  `seon.cluster.work`; message-chain length by cluster loop/message; and the
  primary, backup, and retry dials by `seon.ai/targets`,
  `seon.ai/retry-strategy`, and cluster loop assembly.

The first six are structural synchronization. Consumer reads are semantic and
must remain: a dial with no consumer is merely unused data.

### Decision: the schema loader owns derivation

`seon.schema.edn` is the only owner that sees the complete immutable
`{schema-key form}` population before admission and before
`canonical-database-attributes` derives installation. It will derive the
closed manifest, effective, and config-entity map forms from registered config
dial leaves before contributing the population. The config gate will consume
that result; it will not maintain another dial inventory.

Dial membership is computed from registered config attribute identities:
multi-segment `:seon.config.*/*` attributes are dials, while the legacy
single-segment `:seon.config/on-core-error` registration carries the explicit
dial facet. Structural `:seon.config/cluster`,
`:seon.config/applied-manifest-digest`, and the composite/request schemas
therefore never enter the dial population, without an exclusion roster.
Generated manifest entries are optional overrides. Generated effective/entity
dial entries are also optional: `desired-rows` supplies shipped defaults before
reconciliation, while consumers continue to declare the subset they require.
This makes a newly registered scratch dial valid without inventing a default;
adding a shipped value remains an intentional value-and-provenance decision,
not ceremony required for the dial to exist.

This owner also makes the acceptance test direct: add one scratch dial
registration to a schema-loader fixture, load it with no composite/default/test
edits, prove the manifest accepts it, prove canonical database population
installs it, and transact/read it through config reconciliation. Then remove
the fixture registration. Delete the expected-population and provider
cross-check fences in the same implementation change; the generated maps make
their failure class unrepresentable.

The 2026-07-29 midday locality ruling is a boundary on this derivation, not an
extra config layer. Durable provider descriptor rows are defaults. Per-agent or
per-situation model, endpoint, output budget, and thinking values remain
ordinary call-site `:seon.ai/*` request/target data, with the call-site map
winning. They do not acquire `:seon.config.*` identities and require no dial
registration.
