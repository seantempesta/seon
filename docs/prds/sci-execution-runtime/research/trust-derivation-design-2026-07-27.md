---
type: research
status: active
tags: [prd, research]
---

# Core schema admission from ancestor genesis

## Verdict

Derive core schema admission from an existing construction fact:

> An asserting transaction is core-authored exactly when its
> `:seon.db/process` ref resolves to a process entity whose
> `:seon.db.process/id` was first asserted before the ancestor seal transaction.

The seal transaction is the transaction that asserts the one
`:seon.ancestor/digest` fact. `ancestor/ensure!` writes that fact only after the
injected population has completed (`src/seon/cluster/ancestor.clj:242-267`,
`:287-304`), and a cluster is then branched from that completed ancestor
(`test/seon/cluster/registry_test.clj:119-140`). The process entity's membership
in genesis is therefore a durable fact of database history, not a process-name
rule and not a new trust stamp.

Unknown, malformed, post-genesis, and missing provenance all classify
`:agent`. The literal `core-process-identities` set is deleted in the same
implementation change.

This recommendation is simpler than recursive trust delegation. A producer is
core because its durable process identity is part of the population from which
every cluster is born. Adding another core producer means including its process
entity in that population; the classifier does not change.

## Scope and dependency ledger

This document designs the replacement only. It changes no source or tests.

The design is grounded in the following maintained mechanisms at Seon commit
`858d6bf8662235dfff49a635b3f4e50c0b334103`:

- `seon.schema` derives admission from the transaction that asserted each
  canonical row, then caches the result in the disposable projection
  (`src/seon/schema.cljc:1407-1464`, `:1497-1528`, `:1560-1583`).
- Transaction provenance is two refs, `:seon.db/user` and
  `:seon.db/process`, and the process identity is a string identity attribute
  (`src/seon/schema/provenance.edn:1-15`).
- The ancestor population, its seal fact, and branch publication are owned by
  `seon.cluster/populate-ancestor!` and `seon.cluster.ancestor/ensure!`
  (`src/seon/cluster.clj:309-330`;
  `src/seon/cluster/ancestor.clj:242-304`).
- Configuration uses the string `"seon.db.process/config"` and commits it as a
  lookup ref in transaction metadata (`src/seon/config.cljc:42-44`,
  `:162-177`; `src/seon/reconcile.cljc:402-427`).
- Agent registration is staged with admission source `:agent`, passes through
  the same `seon.schema.edn/admit` gate, and publishes only after the durable
  transaction (`src/seon/schema.cljc:1651-1670`;
  `src/seon/schema/edn.clj:250-289`;
  `docs/prds/sci-execution-runtime/research/n5-plan-2026-07-27.md:263-288`).

The database dependency is the maintained Datahike fork at
`357ffc87c8009f342b239145802e1385d4a18ca9`:

- A transaction id is `inc` of the prior database value's `:max-tx`, so ids
  provide the required strict order
  (`reference-code/datahike/src/datahike/db/transaction.cljc:53-55`).
- `:tx-meta` is converted into datoms on the current transaction entity
  (`reference-code/datahike/src/datahike/db/transaction.cljc:892-911`,
  `:1222-1227`).
- `d/history` exposes the temporal database value used to find the first
  assertion transaction
  (`reference-code/datahike/src/datahike/api/impl.cljc:185-194`).
- Datahike defaults `:keep-history?` to true, but permits configuration and an
  environment value to override it
  (`reference-code/datahike/src/datahike/config.cljc:18-25`, `:220-239`).
  Because genesis admission depends on history, Seon's store configuration
  must assert `:keep-history? true` rather than inherit that overrideable
  default. The current store map specifies the backend, writer, and schema
  flexibility but omits history (`src/seon/cluster/store.clj:136-155`).
- `branch!` publishes a stored database head under a new branch name rather
  than rebuilding its datoms; the inherited temporal indexes and transaction
  ids are consequently part of the branched database value
  (`reference-code/datahike/src/datahike/versioning.cljc:237-289`).

The recurring ancestor suite passed on 2026-07-27: 8 tests, 28 assertions, 0
failures, 0 errors via `bin/test seon.cluster.ancestor-test`. Its live file-store
proof observes the injected population and seal facts together
(`test/seon/cluster/ancestor_test.clj:157-194`); the registry suite separately
proves that descendant clusters inherit every ancestor row
(`test/seon/cluster/registry_test.clj:119-140`).

## Current defect

`core-process-identities` contains three keywords while
`:seon.db.process/id` is registered and used as a string
(`src/seon/schema.cljc:269-272`;
`src/seon/schema/provenance.edn:12-15`;
`src/seon/config.cljc:42-44`). The membership test compares the pulled string
directly with that keyword set (`src/seon/schema.cljc:274-288`). It can never
match, so every committed row currently takes the `:agent` branch.

Correcting the constants to strings would make the code active but would
preserve the architectural defect: a new producer would still require a
classifier edit, and a trusted-looking name would still be the entire trust
decision. R34 instead requires transaction-derived provenance with unknown
rows failing closed
(`docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:1204-1212`).
R43 generalizes the rule: authorship comes from source-datom provenance or
compiled artifact inventory, never a name rule or stored authorship attribute
(`docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:1551-1563`).

The asserting transaction remains the right input. The defect is only the
literal interpretation of its process ref.

## Decision 1 — the durable core fact

### Option A — membership in ancestor genesis

Core means the process identity's first assertion is earlier than the ancestor
seal transaction.

Advantages:

- It already exists by construction. The ancestor is built from an injected
  population and sealed only after that population succeeds
  (`src/seon/cluster/ancestor.clj:27-42`, `:242-304`).
- It survives every descendant branch as ordinary history, while sibling
  writes remain isolated (`test/seon/cluster/registry_test.clj:119-163`).
- It admits a new producer without changing the decision function: place that
  producer's process entity in the ancestor population.
- A post-fork transaction cannot backdate the first assertion or move the seal
  transaction; transaction ids advance monotonically
  (`reference-code/datahike/src/datahike/db/transaction.cljc:53-55`).
- It adds no attribute, registry, or second trust mechanism.

Costs:

- The ancestor population must be ordered correctly. Database attribute
  declarations land first; core process entities land next; canonical schema
  and program-graph rows then transact with a genesis process ref; the digest
  seal remains last. The present order puts the one config process entity after
  the canonical schema rows and gives those rows no provenance
  (`src/seon/cluster.clj:309-330`), so the implementation must reorder it.
- Adding a core producer changes ancestor population and therefore requires the
  normal new ancestor/reset boundary. That is desirable: clusters reset to
  current genesis instead of accepting an in-place elevation.
- The query uses temporal history. Projection acquisition should derive the
  genesis process set once per immutable database value and reuse it for every
  row, rather than repeat the same history scan per schema.
- Temporal history becomes a trust dependency. The implementation must make
  `:keep-history? true` explicit in Seon's closed store configuration and prove
  that a store which cannot supply history refuses startup; silently degrading
  every row to `:agent` would recreate the current inert failure.

### Option B — add a core attribute or connection to process entities

Examples are `:seon.db.process/core? true` or a ref from a process entity to a
trusted ancestor entity.

Advantages:

- The read is a direct current-database query.
- The asserted intent is visually obvious.

Costs:

- It is a trust stamp, not a derived fact. The implementation must separately
  decide who may assert, retract, or replay the stamp.
- It introduces bootstrap recursion: the stamp is trustworthy only if its own
  asserting transaction was already trusted.
- It duplicates what genesis membership already proves and creates another
  fact that can drift.
- A boolean is especially close to the banned kind/type reflex; it says what
  the entity is instead of deriving how it entered the database.

### Option C — derive directly from the artifact or ancestor digest

The ancestor digest is a SHA-256 over declared source roots
(`src/seon/cluster/ancestor.clj:18-23`, `:121-160`) and is stored on the
ancestor itself (`src/seon/cluster/ancestor.clj:96-101`, `:291-297`).
Compiled artifact exports are also already a separate projection input
(`src/seon/schema.cljc:1422-1427`, `:1529-1551`).

Advantages:

- Both facts are computed inventories rather than names.
- Artifact exports are the correct R43 basis for classifying compiled
  third-party terminals.

Costs:

- Neither fact connects an asserting transaction's process ref to the build.
  Making that connection requires a new relation or stamp, returning to Option
  B.
- A digest proves which bytes formed an ancestor, not which runtime process
  accepted a transaction.
- Treating every transaction in a process running the current artifact as core
  would erase the user/process distinction and could elevate agent requests
  executed by that same JVM.

### Recommendation

Choose Option A. Use the digest datom's transaction as the existing seal
boundary, not the digest string as a trust token. Keep compiled artifact export
classification as its existing, separate R43 branch; it does not classify
transaction processes.

Do not implement the recursive alternative proposed provisionally in the B2
plan, where a core transaction can create another core process
(`docs/prds/sci-execution-runtime/research/b2-plan-2026-07-27.md:679-690`).
It adds delegation and revocation questions that the sealed acceptance rows do
not require. A new producer belongs in the next ancestor by construction.

## Decision 2 — exact fail-closed derivation

### Input shape

The decision is a pure function of two ordinary values:

```clojure
(admission-from-asserting-transaction db asserting-tx-eid)
```

- `db` is the exact immutable database value from which the projection rows
  were acquired.
- `asserting-tx-eid` is the datom's transaction id, not a caller-assembled map
  of selected provenance strings.
- The result retains the current
  `:seon.schema.admission/source`, `/process-id`, `/user`, and optional `/note`
  shape (`src/seon/schema.cljc:289-308`).

The projection row therefore becomes `[identity form-string tx-eid]`, and
`projection-from-rows` receives that same `db` in its input. This prevents a
row producer from accidentally dropping the entity identity needed to inspect
history. The current row contract instead permits an optional pulled map
(`src/seon/schema.cljc:554-580`).

### Decision function

The exact logical shape is:

```clojure
(defn admission-from-asserting-transaction [db asserting-tx-eid]
  (let [process-ref       (unique-process-ref db asserting-tx-eid)
        process-id        (current-process-id db process-ref)
        user              (current-user-provenance db asserting-tx-eid)
        ancestor-seal-tx  (unique-ancestor-seal-tx db)
        first-process-tx  (first-added-process-id-tx
                           (d/history db)
                           process-ref
                           process-id)
        core?             (and process-ref
                               process-id
                               ancestor-seal-tx
                               first-process-tx
                               (< first-process-tx ancestor-seal-tx))]
    (cond-> {:seon.schema.admission/source
             (if core? :core :agent)
             :seon.schema.admission/process-id process-id
             :seon.schema.admission/user (or user {})}
      (not core?)
      (assoc :seon.schema.admission/note
             (fail-closed-note ...)))))
```

Each helper is a query over the supplied database value:

- `unique-process-ref` accepts exactly one
  `[asserting-tx-eid :seon.db/process process-eid]` fact.
- `current-process-id` requires the referenced entity currently to carry one
  `:seon.db.process/id`. A retracted or unresolved identity is unrecognized.
- `unique-ancestor-seal-tx` accepts exactly one added
  `[ancestor-eid :seon.ancestor/digest digest tx true]` datom. The digest
  attribute is an identity attribute (`src/seon/schema/ancestor.edn:1-5`), but
  zero or multiple seals still fail closed rather than choosing one.
- `first-added-process-id-tx` takes the minimum added transaction for the same
  process entity and current identity value from `d/history`. It never uses a
  process-id spelling as policy.
- Only the strict inequality `<` is core. An identity asserted in the seal
  transaction or later is agent-authored.

Malformed multiplicity is not guessed through scalar-query iteration order.
Every zero-or-many case returns `:agent` and a diagnostic note. This preserves
the current safe default (`src/seon/schema.cljc:274-308`) while fixing its
classifier.

Projection construction may optimize without changing this contract: derive
`#{genesis-process-eid}` once from `db`, then let the exact public decision
reduce to membership of the asserting transaction's process ref in that
derived set. The set is a cache of database history, never a literal roster and
never durable state.

### Recommendation

Adopt this two-argument shape and transaction-id row contract. Do not pass a
precomputed `:core?` flag or process-name set across the boundary; those would
move the ungrounded decision to a caller.

## Decision 3 — replay resistance

There are two distinct attacks, and both must be sealed.

### Trusted-looking new string

An agent may submit ordinary tx-data containing
`:seon.db.process/id "seon.db.process/core"`. Its first assertion is necessarily
after the inherited ancestor seal, so the new process entity is not a genesis
process. Its spelling is irrelevant.

If the string already names a genesis identity, Datahike's identity semantics
resolve it to the existing entity; that still does not make the agent's
asserting transaction use that entity as provenance. Admission follows the
transaction's system-injected `:seon.db/process` ref, not process-looking domain
data in the payload.

### Forged transaction provenance

The database capability must not accept agent-authored transaction metadata.
The ruled contract says provenance is injected by the run loop from the
executing receipt and is never supplied by agent code
(`docs/prds/sci-execution-runtime/research/step1-contract-2026-07-26.md:26-43`).
That guard must also refuse payload operations that target `:db/current-tx` or
assert `:seon.db/user` / `:seon.db/process`; Datahike otherwise recognizes
`:db/current-tx` as the current transaction entity
(`reference-code/datahike/src/datahike/db/transaction.cljc:59-65`) and writes
ordinary transaction metadata as datoms on that same entity
(`reference-code/datahike/src/datahike/db/transaction.cljc:892-911`).

Thus the trust chain is:

1. the run loop selects agent/REPL provenance from the executing receipt;
2. `seon.effect/request!` carries that identity through the one guarded
   capability path, whose architecture forbids a second entry
   (`docs/seon/architecture/toolkit.md:121-125`);
3. the database owner stamps the selected refs;
4. admission later derives from the committed transaction and immutable
   genesis history.

The genesis predicate prevents a forged-looking process identity from becoming
trusted. The guarded database boundary prevents an agent from selecting an
existing genesis process ref as transaction provenance. Both are necessary.
If low-level code can stamp arbitrary provenance refs, no downstream
classification of those refs can recover the true caller.

### Recommendation

Make the implementation falsifier drive through `my.db/transact!`, not only
through raw `d/transact`. Assert that both a trusted-looking process entity and
an attempted `:db/current-tx` provenance write remain agent-authored or are
refused at the guarded boundary.

## Decision 4 — migration and deletion boundary

The literal list dies in the same implementation change. The complete move is:

1. Delete `core-process-identities` and replace
   `admission-from-asserting-transaction`'s one-argument name test
   (`src/seon/schema.cljc:269-308`) with the database-value plus transaction-id
   derivation above.
2. Change `asserting-transaction-provenance-pattern` and the projection-row
   contract so acquisition returns the asserting transaction id, not only a
   nested process-id map (`src/seon/schema.cljc:262-267`, `:554-580`).
3. Thread the immutable database value through `projection-from-rows`, and
   move both current call sites: canonical schema/function rows in `parse-rows`
   (`src/seon/schema.cljc:1422-1477`) and function-source admission
   (`src/seon/schema.cljc:1497-1528`). `rg` finds no source caller of
   `projection-from-rows` yet; N5's acquisition producer is the pending owner
   that must adopt the row shape
   (`docs/prds/sci-execution-runtime/research/n5-plan-2026-07-27.md:333-349`).
4. Reorder `populate-ancestor!`: install derived database declarations; assert
   every core producer process entity as genesis data; commit canonical schema
   rows with a genesis process ref; let `ancestor/ensure!` write the digest
   seal last. The current three transactions put canonical rows before the one
   config process entity and attach no provenance
   (`src/seon/cluster.clj:309-330`).
5. Make `:keep-history? true` explicit in
   `cluster.store/datahike-configuration`; Datahike's overrideable default is
   not a sufficient trust contract (`src/seon/cluster/store.clj:136-155`;
   `reference-code/datahike/src/datahike/config.cljc:220-239`).
6. Keep `config/managing-process-identity` as the producer's opaque string and
   keep `reconcile!`'s lookup-ref transaction metadata
   (`src/seon/config.cljc:42-44`, `:162-177`;
   `src/seon/reconcile.cljc:420-425`). Trust no longer depends on that string.
7. Remove literal-list language from
   `src/seon/schema/provenance.edn:12-15` and
   `src/seon/config.cljc:21-25`, `:162-167`. Describe genesis membership
   instead.
8. Preserve the two-producer admission gate. Runtime agent registration remains
   staged as `:agent` before commit
   (`src/seon/schema.cljc:1659-1670`), while every later reload derives the
   durable classification from its committed transaction. Do not add a second
   gate to `seon.schema.edn/admit`
   (`src/seon/schema/edn.clj:250-289`).
9. Seal the guarded database request against caller-supplied tx metadata before
   counting replay resistance as green. The step-1 contract already assigns
   provenance injection to the run loop
   (`docs/prds/sci-execution-runtime/research/step1-contract-2026-07-26.md:26-43`).

There is no compatibility period and no fallback to process-name membership.
Rows with old missing provenance become `:agent`; a reset rebuilds them through
the corrected ancestor population.

## Decision 5 — sealed falsifiers

Use one real temporal Datahike database and construct the ancestor boundary in
the same order as production. Every assertion calls the same pure
`admission-from-asserting-transaction db tx-eid` function. The table is the
minimum recurring matrix required by the issue.

| Row | Construction | Expected source | What it seals |
|---|---|---:|---|
| boot | Seed a boot process entity before the digest seal; assert a row after the seal with that process ref. | `:core` | Boot remains core without name membership. |
| config | Seed `config/managing-process-identity` before the seal; run `config/apply!`; classify the committed config transaction. | `:core` | The live string-valued config path and lookup ref compose with genesis. |
| runtime core | Seed the runtime-core process before the seal; assert a post-fork row with it. | `:core` | Core is a durable producer fact, not “boot transaction happened early.” |
| agent | Create/use the agent process after the seal and stamp the real agent user. | `:agent` | Normal agent provenance cannot receive core exceptions. |
| REPL | Create/use the REPL process after the seal. | `:agent` | Human REPL access is attributable but not core by default. |
| missing | Assert rows with no process ref, a dangling/ref-without-id process, no seal, and multiple seals in separate malformed fixtures. | `:agent` | Every unrecognized shape fails closed. |
| new producer | Generate a fresh random producer identity, seed it before the seal, and assert a post-fork row with it without editing classifier data. | `:core` | Completeness for future producers; no hidden three-name roster. |
| trusted-looking replay | Through the guarded agent database capability, transact a new process entity using a core-looking string and attempt a current-tx provenance override. | `:agent` or refusal | Spelling and replay cannot elevate an agent. |

The new-producer value must be generated in the test, not copied from boot,
config, or core. The test must also assert that the implementation contains no
`core-process-identities` var and no equivalent literal roster; behavioral
coverage alone would not catch a renamed hand list.

The boot/config/runtime-core rows should use distinct process entities so one
passing lookup cannot green-wash all three. The agent and REPL rows should use
the same post-genesis mechanism with different user provenance, proving that
user attribution is preserved but does not affect core trust.

The production reset proof is separate from the fixture: build a fresh
ancestor, fork a cluster, query the first process-id assertion transactions and
the digest seal transaction, and show every intended core producer has
`first-process-tx < seal-tx`. Then classify one actual config transaction as
`:core`. This is the reset-boundary evidence that fixture-only schema tests
cannot supply.

### Recommendation

Seal all eight rows together. The issue's seven provenance categories are one
classification matrix; trusted-looking replay is the adversarial property that
makes the core rows meaningful.

## Rejected shortcuts

- Changing the keyword constants to strings activates the hand list but does
  not compute trust.
- Storing `:seon.schema.admission/source` on schema rows duplicates a derived
  projection cache as database truth. The current projection already treats
  admission as derived cache (`src/seon/schema.cljc:1560-1583`).
- Inferring core from namespace, symbol, or process-id prefixes repeats the R34
  defect.
- Treating all ancestor datoms as core is insufficient: post-fork config and
  runtime-core transactions are core because their process entities are
  genesis, while schema rows still need explicit asserting-transaction
  provenance.
- Allowing already-core transactions to mint new trusted processes adds a
  delegation system. Put a legitimate new producer in the next ancestor
  population instead.

## Implementation exit

The later implementation lane is complete only when:

- classification is derived from immutable database history and the ancestor
  seal;
- Seon's store configuration explicitly retains the temporal history that
  admission requires;
- the literal process roster and all prose describing it are gone;
- the projection builder uses one immutable database value plus transaction
  ids;
- the guarded database capability owns provenance and refuses agent attempts
  to write it;
- all sealed falsifiers recur under the maintained test runner; and
- a fresh ancestor/cluster reset proves a real config transaction classifies
  `:core`.

This satisfies the issue without a new trust attribute, a second registry, a
name rule, or recursive elevation.
