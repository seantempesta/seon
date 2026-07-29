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

**REAL-BUT-QUEUED — config-aero mining.** Current source still requires a dial
to be registered separately for schema admission, database installation, and
shipped defaults. The quarry report ranks this maintenance defect behind the
missing selection/apply workflow.

Config patching remains FROZEN. This classification cross-references the
returned `config-aero-quarry` mining report; it is not a fix plan.

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

## Independent verification 42887d234

Verdict: **the issue remains open.** The file-loader path derives the three
composite schemas, but the ordinary registration path does not. The deleted
cross-check failure class is therefore still representable.

1. **FALSE — one registration does not automatically derive every
   contract.** In `42887d234`, `derive-config-forms` correctly constructs
   `:seon.config/manifest`, `:seon.config/effective`, and
   `:seon.config/entity`, but only `schema.edn/load!` calls it, over the forms
   just read from schema resource files
   (`src/seon/schema/edn.clj:51-117,226-228`). `schema/register!` contributes a
   new leaf directly and never invokes that compiler. A clean-checkpoint REPL
   probe registered
   `:seon.config.verification428/enabled` as
   `[:boolean {:seon.config/default false}]`. The leaf registered and
   `malli->datahike-attr` produced a Boolean declaration, but the automatic
   results were manifest `false`, effective `false`, entity `false`, and
   canonical database attribute `false`. Explicitly calling
   `derive-config-forms` changed all four results to `true`, and
   `config-registration-defaults` returned `false`. The scratch registration
   was removed with `schema/restore-state!`; a final check returned
   `{:scratch-removed? true}`. `git grep` also finds
   `config-registration-defaults` only at its definition and in
   `edn_test.clj`; the target commit's `seon.config/defaults` does not consume
   it.
2. **FALSE as a claim that the hand-maintained surface is gone.** The three
   authored definitions are absent from `schema/config.edn`, and ordinary
   consumers correctly continue reading their generated schema keys. However,
   `test/seon/cluster/boot_test.clj:345-353` remains a manual writer: after
   registering a dial it loops over exactly `:seon.config/manifest`,
   `:seon.config/effective`, and `:seon.config/entity`, then re-registers each
   with the dial conjoined. This is the old three-map synchronization
   operation surviving outside the deleted definitions.
3. **FALSE — the deleted tests' failure class remains representable.** The
   direct REPL construction registered
   `:seon.config.verification428/optional-dial`, then validated a manifest
   containing it. Registration returned true while manifest validation
   returned false with Malli `:malli.core/extra-key`. Re-running
   `schema.edn/load!` after the registration still left the scratch dial out of
   manifest, entity, and `canonical-database-attributes`, because `load!`
   derives from only its resource-file population. The surviving boot test's
   manual three-shape widening independently corroborates that the state is
   constructible. The new `one-config-registration-derives-every-structural-contract`
   test calls the private compiler directly over a fabricated forms map; it
   does not exercise the public `schema/register!` producer it claims to
   prove. Deleting the population cross-check was premature.
4. **TRUE — optionality metadata preserves absence, not nil.** At a clean
   `42887d234` checkpoint, applying defaults to a fresh canonical in-memory
   database and reading `config/effective` produced
   `{:defaults-contains? false, :effective-contains? false,
   :effective-get :user/absent, :effective-nil-valued-keys #{}}` for
   `:seon.config.ai.backup/model`.
5. **TRUE — the reported flow failure was unrelated.** On an archived clean
   `42887d234` tree, `bin/test seon.config-test seon.schema.edn-test` ran 15
   tests / 103 assertions with zero failures, and full `bin/test` ran 487
   tests / 2,051 assertions with zero failures. The lane's shared-tree failure
   was
   `seon.flow-test/production-launcher-wedges-degrade-capacity-by-exactly-n`,
   which expected `:seon.flow/platform-threads? true` and observed false. Its
   actual owner is the later `seon.flow` virtual-work-launcher wave:
   `f14a6cf7a` changed `start-work-launcher!` to use virtual task threads, and
   `24544c1d1` updated and extended the owning flow tests. Both commits postdate
   `42887d234`; the clean target checkpoint is green.

Acceptance remains unchanged: every supported registration producer must feed
the one derivation before manifest admission and canonical database-attribute
selection, and the regression must exercise that public producer rather than
calling the derivation helper directly.
