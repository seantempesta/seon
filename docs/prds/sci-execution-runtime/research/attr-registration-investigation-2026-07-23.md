---
type: research
status: active
tags: [research, database, runtime]
---

# Global attribute-registration authority — investigation (2026-07-23)

## Conclusion

The best structural mechanism is **A: committed `:seon.schema` database facts as the global authority**, completed so every tier acquires the database-scoped projection before admitting work.

That is the only candidate that eliminates namespace load order and runtime tier from attribute registration. B, C, and D should compose around it:

```text
canonical :seon.schema facts
          ↓
boot reconciliation → installed Datahike schema
          ↓
committed per-database Malli projection
          ↓
parse/API admission → transaction/read
          ↓
writer remains final fail-closed authority
```

A critical correction: the literal `:seon.agent.run/current-turn` drill failure was not a claimant transaction and no registration ever existed for that key. It was a manual JVM `db/pull` using a derived projection key as a database attribute. The broader `.cljs`-only run/turn registration defect is real, but it is a related class rather than the exact cause of that error.

No files, processes, builds, or databases were changed.

## Current-state map

There are three different schema states today:

| State | Present behavior |
|---|---|
| Declaration candidate | `schema/register!` writes into process-local `!schema-state`; namespace order does not affect projection compilation. |
| Canonical authority | A `:seon.schema` entity stores the key and full EDN form in the cluster database. |
| Installed schema | Datahike receives storage declarations derived from canonical forms. A process-local registration alone does not install anything. |

### Declaration and projection

[`seon.schema/register!`](/Users/sean/src/seon/src/seon/schema.cljc:288) validates an EDN-round-trippable form and adds it to the process-local candidate collector. The collector and active projection share one atom, but the active projection is meant to represent the committed database generation ([schema.cljc:134](/Users/sean/src/seon/src/seon/schema.cljc:134)).

Committed rows are compiled as one complete, declaration-order-independent population by [`projection-from-rows`](/Users/sean/src/seon/src/seon/schema.cljc:491), then atomically published only after the matching transaction commits ([schema.cljc:563](/Users/sean/src/seon/src/seon/schema.cljc:563)). Isolated eval deltas already prevent failed registrations from leaking into the active population ([schema.cljc:622](/Users/sean/src/seon/src/seon/schema.cljc:622)).

This matches the intended architecture: `register!` is a candidate declaration, not Datahike installation; only transacted attributes need to be bridge-storable ([data-model.md:358](/Users/sean/src/seon/docs/seon/architecture/data-model.md:358)).

### How declarations become database facts

Compiled boot declarations are already converted into canonical rows by [`index-schemas`](/Users/sean/src/seon/src/seon/client.cljs:1779). It emits every registered key—not only entity maps—and supplies the complete program to writer initialization ([client.cljs:1825](/Users/sean/src/seon/src/seon/client.cljs:1825)).

Authored declarations already use the desired transaction ordering:

- The pod detects a successful registration delta, emits `:seon.schema` rows, commits them, and only then publishes the committed projection ([eval.cljs:4461](/Users/sean/src/seon/src/seon/eval.cljs:4461)).
- The JVM host builds the same canonical schema row in [`seon.host.record`](/Users/sean/src/seon/src/seon/host/record.clj:281).
- Program reconciliation queries existing schema rows, preserves non-boot authored rows, and reconciles compiled rows by `:seon.schema/key` ([program.clj:48](/Users/sean/src/seon/src/seon/db/program.clj:48), [program.clj:196](/Users/sean/src/seon/src/seon/db/program.clj:196)).

The durable corpus is therefore substantially present already. It is also already acquired by:

- Pod runtime admission: [runtime/admission.cljs:268](/Users/sean/src/seon/src/seon/runtime/admission.cljs:268)
- Execution acquisition: [execution.cljs:822](/Users/sean/src/seon/src/seon/execution.cljs:822)
- JVM host contexts: [host/context.clj:1100](/Users/sean/src/seon/src/seon/host/context.clj:1100)

### Writer installation

The writer already does the hard parts:

- It reads canonical forms from the same transaction and existing database rows, recursively following schema references ([writer.clj:332](/Users/sean/src/seon/src/seon/db/writer.clj:332)).
- It rejects a used attribute that has no canonical form ([writer.clj:422](/Users/sean/src/seon/src/seon/db/writer.clj:422)).
- It derives missing Datahike declarations and rejects incompatible installed facets ([writer.clj:448](/Users/sean/src/seon/src/seon/db/writer.clj:448)).
- It lazily installs a registered-but-not-installed attribute when first used ([writer.clj:474](/Users/sean/src/seon/src/seon/db/writer.clj:474)).
- Program initialization installs the requested attributes plus attributes derived from entity schemas before committing program rows ([writer.clj:1752](/Users/sean/src/seon/src/seon/db/writer.clj:1752)).

The new alias support from `0e1954cb6` correctly dereferences canonical qualified-keyword aliases against the complete explicit form population, with cycle detection and no dependence on process-global Malli state ([datahike/schema.clj:283](/Users/sean/src/seon/src/seon/db/datahike/schema.clj:283)).

The remaining boot weakness is [`agent-bootstrap-attrs`](/Users/sean/src/seon/src/seon/client.cljs:741): it is still partly a manually maintained installation list. The writer derives entity-map attributes as well, but the complete transactable population is not yet a single computed invariant.

### Existing transaction admission—and its claimant hole

`seon.db/transact!` already extracts every transaction attribute and validates it against an explicitly bound projection ([db.cljc:446](/Users/sean/src/seon/src/seon/db.cljc:446)). Its existing error text already teaches “register, then retry unchanged” ([db/internal.cljc:332](/Users/sean/src/seon/src/seon/db/internal.cljc:332)).

The general JVM host correctly supplies a database-scoped committed projection ([host/context.clj:245](/Users/sean/src/seon/src/seon/host/context.clj:245)). The claimant leaf explicitly defeats it:

- projection: `nil`
- cache: no-op
- validation: `false`

See [agent/driver/host.clj:23](/Users/sean/src/seon/src/seon/agent/driver/host.clj:23). Thus Datahike still rejects an uninstalled attribute, but the claimant misses Malli validation and the earlier teaching surface.

## Registration census and JVM use

A source-location census finds:

| Extension | Files containing `schema/register!` | Meaning |
|---|---:|---|
| `.cljs` | 58 | Pod-only load effects; includes the problematic persisted run/turn/attempt families. |
| `.cljc` | 45 | Portable declarations, including schema/db/config cores and the repaired eval-receipt owner. |
| `.clj` | 20 | JVM authority, host, writer, development, and server shapes. |

This is not a count of database attributes: many registrations are request, response, function-slot, or derived value schemas.

The decisive `.cljs`-only registrations are:

- All persisted `:seon.agent.run/*` declarations and the run entity: [run.cljs:26–103](/Users/sean/src/seon/src/seon/agent/run.cljs:26)
- All persisted `:seon.agent.turn/*` and `:seon.agent.turn.usage/*` declarations: [turn.cljs:41–109](/Users/sean/src/seon/src/seon/agent/turn.cljs:41)
- All `:seon.ai.attempt/*` declarations and the attempt entity: [turn.cljs:165](/Users/sean/src/seon/src/seon/agent/turn.cljs:165)
- Several attempt scalar aliases remain pod-only in [ai.cljs:65](/Users/sean/src/seon/src/seon/ai.cljs:65) and [ai.cljs:244](/Users/sean/src/seon/src/seon/ai.cljs:244).
- Even `:seon.agent/run` remains in [agent.cljs:113](/Users/sean/src/seon/src/seon/agent.cljs:113), while `:seon.agent/id` is under an acknowledged load-order workaround in [agent/ctx/render_fns.cljs:45](/Users/sean/src/seon/src/seon/agent/ctx/render_fns.cljs:45).

Meanwhile, portable code constructs transactions containing those attributes:

- Claims, leases, fences, releases, and closes: [run/core.cljc:35](/Users/sean/src/seon/src/seon/agent/run/core.cljc:35)
- Turn phase transitions and attempt creation/terminalization: [turn/core.cljc:19](/Users/sean/src/seon/src/seon/agent/turn/core.cljc:19)
- The cross-tier driver submits those transactions through `db/transact!`: [driver.cljc:218](/Users/sean/src/seon/src/seon/agent/driver.cljc:218)

The repaired eval receipt is the correct precedent: its complete persisted schema now lives beside its portable transaction builders in [eval/receipt.cljc:10](/Users/sean/src/seon/src/seon/eval/receipt.cljc:10).

## Exact drill anatomy

The recorded command used this pull pattern:

```clojure
{:seon.agent.run/current-turn [...]}
```

See [u2-resume1-stderr.log:94828](/Users/sean/src/seon/tmp/orchestrator/u2-resume1-stderr.log:94828). Datahike rejected it at [line 95344](/Users/sean/src/seon/tmp/orchestrator/u2-resume1-stderr.log:95344).

There is no `schema/register!` for `:seon.agent.run/current-turn` now or historically. The portable driver:

1. Pulls stored turns through `:seon.agent.turn/_run` ([driver.cljc:76](/Users/sean/src/seon/src/seon/agent/driver.cljc:76)).
2. Selects the latest running turn.
3. Adds `:seon.agent.run/current-turn` to the returned in-memory map with `assoc` ([driver.cljc:136](/Users/sean/src/seon/src/seon/agent/driver.cljc:136)).

Therefore:

- `current-turn` must not be registered or installed.
- The correct database read is `:seon.agent.turn/_run`, followed by derivation.
- A structural source check should reject `current-turn` when it appears in a pull selector or transaction position.

The actual fresh-schema cross-tier failure from this drill family was `:seon.eval/progress?`: it was pod-owned while the JVM created/read receipts. That was repaired by moving the schema into `eval/receipt.cljc`; the archived issue records the evidence and required reset-boundary proof ([portable-eval-receipt-schema-was-pod-owned.md:12](/Users/sean/src/seon/docs/seon/issues/archive/portable-eval-receipt-schema-was-pod-owned.md:12)).

## Candidate evaluation

### A. Database facts as the authority — recommended foundation

This is the right structural owner because it makes a registration cluster-global rather than namespace-global or process-global.

What already exists:

- Canonical full-form `:seon.schema` rows
- Compiled boot indexing
- Pod and JVM authored-registration tees
- Database-derived committed projections
- Writer compilation directly from same-transaction or stored canonical forms
- Per-database alias resolution isolated from process-global Malli state

What must change:

- No tier may validate ordinary work from incidental module-load candidates once the database is born.
- The claimant must acquire and bind the committed projection.
- Boot/readiness must reconcile the complete database corpus and installed transactable attribute set before admitting claims.
- A successful `register!` transition must always culminate in a committed schema row before functions using it are callable.

Do not make module-loading `register!` perform ambient database writes. Module load should continue collecting pure declarations; boot or eval admission owns the one explicit reconciliation transaction.

Bootstrap requires a narrow exception. An empty database cannot store its schema corpus until the schema-of-schemas is installed. Use a fixed genesis packet for `:seon.schema/key`, `:seon.schema/form`, and the row’s required identity/ref/provenance dependencies, then import the remaining canonical rows normally. The source declarations for `:seon.schema/*` belong in portable [schema.cljc](/Users/sean/src/seon/src/seon/schema.cljc:1), not pod-only `agent.cljs`, but genesis must remain an explicit writer bootstrap phase rather than pretending the database can bootstrap itself from absent facts.

Process-local test registries may remain as isolated declaration candidates. They are not database authority: a test performing database operations must either run program initialization, seed canonical rows explicitly, or intentionally use the documented empty-bootstrap mode only until the declaration transaction commits.

Installed facet conflicts cannot be repaired. Datahike retyping remains reset-bounded ([conversion-wiki.md:41](/Users/sean/src/seon/docs/prds/sci-execution-runtime/conversion-wiki.md:41)).

**A, fully implemented, is the single general mechanism that kills the tier/load-order defect class outright.** Merely storing rows without acquiring the projection and installing the transactable subset is not sufficient.

### B. Boot-time completeness reconciliation — required readiness gate

At every program initialization/open—not only config apply—compute and compare:

1. The compiled artifact declaration snapshot
2. Existing canonical database rows, including agent-authored rows
3. The computed set of attributes used in database operations/entity schemas
4. Installed Datahike facets

Then:

- Commit missing compiled canonical rows.
- Add compatible missing Datahike declarations.
- Reject missing canonical forms, unresolved aliases, alias cycles, and conflicting installed facets.
- Preserve agent-authored rows not owned by boot.
- Publish readiness only after equality is proven.

This should replace the manual bootstrap list as authority. `config/resolve.cljc` demonstrates the desirable “declare once, build one complete resolved graph” pattern ([config/resolve.cljc:97](/Users/sean/src/seon/src/seon/config/resolve.cljc:97), [config/resolve.cljc:954](/Users/sean/src/seon/src/seon/config/resolve.cljc:954)), but schema reconciliation belongs to program/database initialization, not configuration.

B alone is insufficient if its “reachable registrations” mean namespaces incidentally loaded in one process. The artifact snapshot must first become database corpus data.

### C. Parse/transact-time admission — required runtime totality and teaching

Keep writer enforcement as the final authority, but bind every leaf—including the claimant—to its committed projection and fail before transport when possible.

Admission should distinguish:

- No committed registration
- Registration exists but is not installed at the selected database value
- Installed facet conflicts with the canonical form
- Value violates the committed Malli shape
- A derived projection key was used as a database attribute

Extend the same check to pull selectors and literal query attributes, which would have caught the actual `current-turn` drill.

C is essential for dynamically computed transaction data and for model steering. It does not globalize registrations or make a fresh database ready.

### D. Computed structural/static check — valuable early proof

Build a semantic program-graph check over database-operation positions:

- Entity-map keys in transaction builders
- Attribute position in `:db/add`, `:db/retract`, CAS, and lookup refs
- Pull selector attributes
- Datalog attribute positions
- Parsed authored forms invoking the database API

Compare those literals with canonical schema keys and exclude Datahike system attributes. Do not merely grep qualified keywords: the current `:seon.fn/read-attrs` collector records essentially every qualified keyword in function bodies ([host/record.clj:183](/Users/sean/src/seon/src/seon/host/record.clj:183)), including many namespaced data keys that are not database attributes.

D has no runtime cost and catches checked-in source drift early. It cannot prove computed attributes or installed Datahike state, so C and B remain necessary.

## Ranked recommendation and migration

### S — close the concrete hole

- Move persisted run/claim declarations from `run.cljs:26–103` into [src/seon/agent/run/core.cljc](/Users/sean/src/seon/src/seon/agent/run/core.cljc:1).
- Move persisted turn, usage, attempt, and turn-entity declarations from `turn.cljs:41–109,165–274` into [src/seon/agent/turn/core.cljc](/Users/sean/src/seon/src/seon/agent/turn/core.cljc:1).
- Remove the `.cljs` copies; one declaration only.
- Move the shared attempt scalar aliases currently in `ai.cljs` into [src/seon/ai/core.cljc](/Users/sean/src/seon/src/seon/ai/core.cljc:1), so `turn/core.cljc` can resolve its complete schema graph on both tiers.
- Keep run-only derived/function-slot shapes such as `turn-count` and `now` in the pod owner unless their functions become portable.
- Bind the claimant leaf to `host.context`’s committed projection mechanism and enable validation.
- Correct the bad `current-turn` pull and add the exact read/derived-key regression.

This source move kills the known run/turn/attempt `.cljs` split, but not the general future class.

### M — establish the structural runtime invariant

- Complete A’s database-authority path for every tier.
- Add B’s boot/readiness reconciliation.
- Derive the transactable installation population; retire `agent-bootstrap-attrs` as a hand-maintained authority.
- Upgrade writer/client failures to the structured steering categories above.
- Exercise the full fresh/reset boot path required by the schema scars ([conversion-wiki.md:392](/Users/sean/src/seon/docs/prds/sci-execution-runtime/conversion-wiki.md:392)).

### M, independent proof lane — add D

- Compute semantic database-attribute uses from the program graph.
- Gate artifact admission/boot on registration completeness.
- Apply the same analysis to authored forms before publication.
- Keep C for dynamic data.

### L — remove residual process-load assumptions

- Make the schema-of-schemas genesis kernel explicit and minimal.
- Remove load-order workarounds such as the present `:seon.agent/id` placement.
- Make database-scoped committed projections the only ordinary validation authority.
- Retain process-local candidates solely for staging and isolated tests.
- Cover every public database read/write boundary, not only `transact!`.

## Exact steering text

Generic missing-registration error:

> Transaction rejected: attribute `:example/attribute` has no committed `:seon.schema` registration in this cluster. Define it first with `(schema/register! :example/attribute <concrete-malli-form>)`, allow that registration to commit, then retry the unchanged `seon.db/transact!` form. If the key is derived or read-only projection data, remove it from the transaction instead of registering it. No facts were written.

For the literal drill key, the more precise error is:

> Database read rejected: `:seon.agent.run/current-turn` is a derived run projection, not a database attribute. Pull `:seon.agent.turn/_run` and derive the latest running turn; do not register, transact, query, or pull `:seon.agent.run/current-turn`. No database state changed.

The structured value should also carry the offending attributes, immutable database value/basis transaction, and one reason such as `:missing-registration`, `:not-installed`, `:facet-conflict`, or `:derived-projection`.