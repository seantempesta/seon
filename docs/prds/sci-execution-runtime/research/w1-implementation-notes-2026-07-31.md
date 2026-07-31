---
type: research
status: active
tags: [research, render, context, schema, datahike]
---

# W1 context/render data-model implementation notes — 2026-07-31

## State

Production edits are paused at a schema-ownership design gate. The sealed
contract's three new connection attributes cannot be installed and retained by
the current schema/config owners as written. The namespace relaxation, walk
repairs, derived edges, and properties are independently implementable, but
landing a partial W1 would violate the cut-wave ordering and leave the central
instruction path unprovable.

## Dependency ledger

- Datahike is pinned at `9b3be9d59cb07d9c895af280e60eb074bb57a400`.
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

The blocking contradiction is between spec §2.1/§2.2 and the current schema
and config ownership rules, not between the falsification report and the spec:

1. `:seon.config/instructions` is specified as a cluster-owned connection on
   the config singleton, but the closed derived config entity has no extension
   seam for non-dial attributes.
2. Config reconciliation owns the whole singleton entity, so an independently
   written connection would be treated as drift and retracted on config apply.
3. The owned schema paths do not include the live agent entity map in
   `run.edn`; declarations in `agent.edn` alone cannot install the new refs.

## Owner design gate — exactly three options

### 1. Separate cluster-facts entity — recommended

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

## Pending proof after the ruling

- Seed `:reply-grammar`, `:messaging`, `:declining`, and `:global`; ensure the
  source digest covers `AGENTS.md` bytes so `bin/seon init` cannot converge on
  stale global instructions.
- Create an agent through `store/transact!` with boot process provenance and
  observe its cluster edge.
- Walk at distance 1 and 2 and observe shared instruction bytes, the trigger
  message, asked-for runs, and explicit collection/node elision markers.
- Prove the non-ref-long regression and concrete reverse dependency plan.
- Run seeded P1/P5/P6 properties, the focused namespaces, the full gate, and a
  fresh `w1-proof` cluster; stop it after evidence collection.
