---
type: research
status: active
tags: [research, render, context, schema, datahike]
---

# W1 context/render data-model implementation notes — 2026-07-31

## State

W1 production edits landed in two path-limited commits:

- `c189a3d12` — instruction/cluster schemas, exact `AGENTS.md` ingestion,
  branch-local cluster population, agent cluster refs, creation provenance,
  and the source-less namespace relaxation;
- `071ca1e50` — schema-derived entity selectors and reverse ref subsets,
  computed edges, global back-references, floor provenance, loud caps, P1/P5/P6,
  and the archived reverse-long issue.

Focused W1 gates are green. The reset-boundary `bin/seon init` and fresh
`w1-proof` cluster proof are intentionally not claimed: the integration source
tree is moving under another render lane, and that lane's landed namespace
render declarations currently leave `seon.schema.program-test` red. This is
the exact cross-lane stop boundary requested by the owner.

## Dependency ledger

- Datahike is pinned at `9b3be9d59cb0`.
  Transaction argument maps are normalized in
  `reference-code/datahike/src/datahike/api/impl.cljc:30-48`; ref and
  cardinality declarations are derived by
  `src/seon/schema/datahike.cljc:149-230`.
- The packaged schema population and config composites are owned by
  `src/seon/schema/edn.clj:66-111`. `config-dial?` admits namespaces beginning
  `seon.config.`, while `derive-config-forms` constructs the closed
  `:seon.config/entity` from the two hard-coded identity fields plus those
  dials.
- Database attribute installation is derived by
  `src/seon/schema/form.cljc:8-73`: attributes occur in an entity map or carry
  a persistence facet. A bare ref or ref set declared only as a standalone
  form is not installed.
- Configuration apply is entity-exact:
  `src/seon/reconcile.cljc:262-303` retracts every current attribute absent
  from the desired entity, and `src/seon/config.cljc:239-254` supplies only the
  compiled config row.
- The current agent entity schema is
  `resources/seon/schema/run.edn:1-24`, not `agent.edn`. It does not include the
  proposed `/cluster` or `/instructions` connections.
- The W1 read owner is `src/seon/render/walk.clj:199-315`; the falsification
  report proves the current unbound reverse query both registers `:all` and
  follows non-ref longs.

## Shortest falsifier

Run from the repository root:

```clojure
(require '[seon.schema.edn :as schema.edn]
         '[seon.schema.form :as schema.form])

(let [forms (assoc (schema.edn/packaged-forms)
                   :seon.config/instructions [:set :seon.db/ref])
      derived (schema.edn/derive-config-forms forms)]
  {:dial?
   (boolean
    (some #(= :seon.config/instructions (first %))
          (drop 2 (:seon.config/manifest derived))))
   :entity?
   (boolean
    (some #(= :seon.config/instructions (first %))
          (drop 2 (:seon.config/entity derived))))
   :installed?
   (contains? (set (schema.form/database-attributes derived))
              :seon.config/instructions)})
```

Observed on Java 26.0.1:

```clojure
{:dial? false, :entity? false, :installed? false}
```

The result is mechanically implied by the sources above. The exact namespace
of `:seon.config/instructions` is `seon.config`, so it is not a dial under the
current `seon.config.` prefix rule. Because it is also absent from the derived
entity map and has no persistence facet, production population cannot install
it. The same installation rule rejects the two new standalone agent refs.

## Contract pushback

The nine required edits in
`render-invalidation-falsification-2026-07-31.md` otherwise agree with the
sealed spec. Edit 9 is now entity-membership rather than attribute-membership:
P1 says every schema'd entity appears or leaves an elision marker, while the
selector rule remains family-scoped. That is consistent if P1 intentionally
does not promise that attributes outside every entity family render.

The original contradiction was between spec §2.1/§2.2 and the current schema
and config ownership rules, not between the falsification report and the spec:

1. `:seon.config/instructions` is specified as a cluster-owned connection on
   the config singleton, but the closed derived config entity has no extension
   seam for non-dial attributes.
2. Config reconciliation owns the whole singleton entity, so an independently
   written connection would be treated as drift and retracted on config apply.
3. The owned schema paths do not include the live agent entity map in
   `run.edn`; declarations in `agent.edn` alone cannot install the new refs.

Owner ruling commit `b2b3e019d` resolved that contradiction with the separate
cluster entity. W1 installs the new agent attributes through the mechanically
discovered `:seon.cluster.agent/context-links` entity family in `agent.edn`, so
the live `run.edn` family remains untouched while every created agent carries
the mandatory cluster ref.

One narrower pushback remains. The global agent-authored schema replacement
path in `seon.cluster.run/program-row-tx` calls
`assert-schema-data-unused!` before computing whether a replacement changes
any physical Datahike declaration. Therefore an in-place logical loosening of
`:seon.ns/ns` is still refused whenever namespace data exists, even though
making `/source` optional is accretive and its Datahike declarations are
identical. W1's packaged population accepts the relaxation because a fresh
`current-src` is built from the new complete forms; changing the runtime
replacement guard would cross the owned paths and requires a separate ruling
on how logical tightenings are distinguished from loosenings.

## Resolved owner design gate — the three options considered

### 1. Separate cluster-facts entity — selected

Guarantee: create one cluster-facts entity identified by the cluster name;
agents point to it, and it owns the authoritative instruction refs. The config
singleton remains exact desired configuration and is connected to no runtime
facts.

Cost/risk: small and W1-local—one identity, one entity schema, population rows,
and the creation ref. No config/reconcile changes.

Operational trade-off: cluster dials and cluster context are two entities
joined by the same cluster-name value rather than one eid.

Capability given up: the sealed claim that the config singleton itself is the
cluster edge.

### 2. Attribute-scoped config reconciliation

Guarantee: preserve the sealed data shape. Extend config/reconcile so config
apply owns only the compiled config attribute set and preserves other schema'd
facts on the same config singleton. Add the instruction connection to a
general, mechanically derived singleton entity extension.

Cost/risk: highest. It crosses the sealed reconcile/config owners, changes
their exact-entity guarantee, requires new schema paths and state-transition
properties, and expands W1 beyond its owned paths.

Operational trade-off: one eid remains the cluster, but every reconciler must
state the attribute slice it owns.

Capability given up: whole-entity exact reconciliation as the simple config
drift rule.

### 3. Make instruction membership a config dial

Guarantee: keep the config singleton and entity-exact reconciliation by making
`:seon.config/instructions` a registered config dial whose shipped default is
the four instruction lookup refs.

Cost/risk: medium. It changes the sealed schema form, adds
`config/default.edn` ownership, and requires config read/decode work because a
pulled ref set is not the transaction-form `:seon.db/ref` schema.

Operational trade-off: config apply intentionally controls instruction
membership; mutation of instruction text remains an ordinary row update.

Capability given up: instruction membership as runtime-owned cluster data
independent of manifest compilation.

## Implementation evidence

### Population and ownership

`seon.cluster/populate-source!` reads `AGENTS.md` with
`Files/readString(..., UTF_8)` and convergently upserts the four instruction
rows. `AGENTS.md` is also a `source-roots` digest input, so changed global bytes
cannot reuse a stale publication digest. After config reconciliation,
`seed-cluster!` upserts exactly one `:seon.cluster/name` entity with the config
ref and four instruction refs. `seed-root-agent!` passes cluster name through
`creation-tx` and routes both creation and root block writes through
`store/transact!` with a resolvable process entity and
`:tx-meta {:seon.db/process ...}`.

The resulting intended path is structurally enforced:

```clojure
agent --:seon.cluster.agent/cluster--> cluster
cluster --:seon.cluster/config--------> config singleton
cluster --:seon.cluster/instructions--> four shared instruction entities
```

No per-agent fact moved onto the cluster entity. Peers remain independent
agent entities found only by reverse `:seon.cluster.agent/cluster` refs.

### Walk and invalidation claims

The nine falsification edits are reflected in the implementation:

1. entity-family selectors come from raw registered entity forms;
2. identity probes use each family's first child identity attribute;
3. family pulls contain only that family's installed attributes;
4. reverse pulls contain only installed ref attributes derived through the
   Malli→Datahike bridge;
5. all dependency-bearing reads are concrete pull/query clauses;
6. refs and the three specified derived-edge functions are one connection
   source;
7. hidden per-path visitation is replaced by one per-walk rendered set with
   explicit back-references;
8. render resolution's `:seon.render/would-fall-to-floor?` is retained on each
   walk node for W4;
9. P1 checks membership-or-elision over generated graph sizes and collection
   caps.

The reverse-long regression plants
`:seon.cluster.run.form/ordinal == <agent eid>` and proves the form eid is not
among `walk/refs` targets. The old unbound reverse Datalog query and wildcard
entity pulls no longer exist.

### Seeded recurring proof

These commands passed on Java 26.0.1:

```text
bin/test seon.render.walk-test
Testing seon.render.walk-test

bin/test seon.context-pilot-test
Testing seon.context-pilot-test

bin/test seon.cluster.agent-namespace-test
Ran 4 tests containing 10 assertions.
0 failures, 0 errors.

bin/issues-index --check
{:clean? true, :open-count 10, :archive-count 791}
```

`seon.render.walk-test` uses test.check seeds `2026073101`–`2026073103` and
Malli-generated bounded graph sizes. P5 finds the four shared instruction eids
from each of two agents' distance-2 walks and compares their leaf outputs for
byte equality. P6 varies the active reverse collection cap and requires the
attribute-specific elision marker; it separately proves the distance cap is
visible in `walk/prose`.

The decisive derived-edge assertions are:

```clojure
[:seon.ns/name 'external.missing]       ; visible unresolved require
[:seon.render.walk/asked-for-run run-eid]
[:seon.db/trigger message-eid]
```

All are computed at walk time. No derived edge is transacted.

## Cross-lane integration stop

The source-freeze prerequisite for `bin/seon init` is not satisfied. At the
stop boundary, these other-lane paths are modified or untracked:

```text
src/seon/render/block.clj
src/seon/render/data.clj
src/seon/render/web.clj
src/seon/render/transcript.clj
test/seon/render/block_test.clj
test/seon/render/data_test.clj
test/seon/render/web_test.clj
test/seon/render/transcript_test.clj
```

Independently, `bin/test seon.schema.program-test` has two failures in
`catalog-render-declarations-resolve`: the test still asserts that program
family render declarations are absent, while W3's landed namespace family now
advertises both AI and HTML renderers. W1 did not edit that assertion or any
other lane's files.

Consequently these requested forms remain unrecorded rather than fabricated:

- fresh `bin/seon init` over a frozen artifact;
- `bin/seon start w1-proof`;
- a live created agent's creating transaction provenance;
- live distance-1/distance-2 instruction bytes and elision markers;
- `bin/seon stop w1-proof`.

## Remaining proof after the cross-lane boundary clears

- Run the full gate after W3's stale schema assertion is reconciled.
- Run `bin/seon init` over a frozen source tree, then the reset-boundary
  `w1-proof` sequence above; stop the cluster after evidence collection.
- Append the live creating-transaction, distance-1/distance-2 bytes, derived
  edges, and elision forms to this report.
