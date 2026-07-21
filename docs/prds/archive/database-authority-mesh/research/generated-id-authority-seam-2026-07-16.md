---
type: research
status: complete
tags: [research, prd, database, flow, agent]
---

# Generated-ID authority seam — 2026-07-16

## Decision

Keep candidate generation and each pure transaction builder in Bun. Send the
resulting ordinary transaction data and generated-candidate manifest through
the existing transaction operation. On an exact candidate conflict, generate a
new candidate set, rerun the same pure builder from scratch, and submit a new
logical request. On an ambiguous reply, resend the identical frozen request
with the identical request ID so the JVM writer returns the durable committed
result.

Do not add a declarative transaction-template language. The current version-6
transaction protocol already supplies the stronger seam: Bun owns ordinary
data construction, while the JVM owns policy validation, collision detection,
transaction preparation, serialized commit, durable receipts, and recovery of
the generated entity IDs. Normal allocation remains one transaction round trip
after generator policies have been obtained with the cluster's ordinary schema
acquisition. A candidate collision alone adds another transaction round trip.

This corrects one recommendation in
[[atomic-bun-authority-consumer-replacement-2026-07-16]]: replica deletion does
not require moving candidate generation or the transaction builder into the
JVM. It requires removing their dependency on a local Datahike value and local
connection.

## Dependency ledger

| Owner | Selected source | Relevant fact |
|---|---|---|
| Seon generated identities | `src/seon/db/id.cljc` at `38dfd1bf` | Candidate generation and transaction construction are already separate from serialized validation and commit. |
| Seon transaction protocol | `src/seon/db/protocol.cljc` at `38dfd1bf`, version 6 | The transaction request already carries request ID, transaction data, expected coordinate, metadata, and generated candidates. |
| Seon JVM writer | `src/seon/db/writer.clj` at `38dfd1bf` | The writer fingerprints the complete logical request, validates generated candidates, commits once, and reconstructs generated entity IDs from a durable receipt. |
| Datahike | `reference-code/datahike` at `d9765276` | One per-connection writer serializes transaction preparation and commit; immutable transaction data is retried internally only for Datahike tempid/upsert resolution. |
| Compact candidate package | `@paralleldrive/cuid2` 3.3.0 | Bun's selected 12-character generator uses Web Crypto, time, a counter, salt, fingerprint, and SHA3. |
| Human-readable package | `human-id` 4.2.0 | The selected three-word form has exactly 200 × 300 × 250 = 15,000,000 combinations. |
| Production callers | Eleven `db.id/allocate!` calls in eight source files | Every builder is synchronous and pure over generated IDs plus already-frozen caller data; none queries a database or performs I/O inside the builder. |

## Shortest falsifiers

The declarative-template proposal would be justified if either of these claims
were true:

1. the current transaction protocol could not recover generated entity IDs
   after a committed reply was lost; or
2. every production builder was only structural placeholder substitution, so a
   smaller ordinary template could replace the Clojure functions without
   duplicating domain logic.

Both claims are false.

`protocol/transaction-request` already includes `::transaction-data`,
`::expected-coordinate`, `::transaction-meta`, and
`::generated-candidates`. Its durable fingerprint includes all four. The JVM
writer first looks up the request ID in the current database, verifies that
fingerprint, reconstructs transaction datoms and tempids, and finds each
generated entity ID from the committed identity datom. The response already
carries `::generated-entity-ids` and `::recovered?`. The retained
`generated-manifest-is-part-of-the-durable-request-fingerprint` test proves
that exact redelivery recovers the same generated IDs and that changing the
manifest under the same request ID is rejected.

The caller audit found simple substitution builders, but also plan compilation,
plan reconciliation over a frozen database projection, recovery transaction
assembly, initial-agent source construction, and eval result/tee assembly.
Those are valuable pure domain functions. Moving them behind a remotely named
builder or expanding a template until it can express their branching would
create a second domain execution surface.

## Production caller audit

There are eleven production calls, not counting tests.

| Caller | Allocations | Builder behavior | Database access inside builder? |
|---|---:|---|---|
| `my.plan/step!` | 1 compact ID | Adds one conditionally populated step map. | No |
| `my.plan/plan!` | One compact ID per compiled node | Reruns `compile-plan` over the frozen request, agent ref, IDs, and timestamp. | No |
| `my.plan/reconcile!` | Zero or more compact IDs | Reruns `compile-reconcile` over the previously captured database value and edited tree. | No new read; it closes over one immutable value. |
| `seon.runtime.recovery/recover!` | 1 compact ID | Builds CAS fences, pointer retractions, run/turn/eval closes, and one recovery fact from precomputed targets. | No |
| `seon.agent/mint!` | 1 human-readable ID | Calls the pure `initial-agent-tx`, including home-namespace facts. | No |
| `seon.agent.turn/open-turn!` | 1 compact ID | Adds an optional run CAS and the running turn row. | No |
| `seon.db.restore/record!` | 1 compact ID | Adds an expected-coordinate fence, one completion fact, and one dependent unique claim. | No |
| `seon.agent.message/message!` | 1 compact message ID plus zero or more compact plan IDs | Builds one message and one plan step per human recipient. | No |
| `seon.agent.run/open-run!` | 1 compact ID | Builds a run row and the absent-to-run CAS. | No |
| `seon.eval/start-eval!` | 1 compact ID | Builds the running eval receipt. | No |
| `seon.eval/record-eval!` fallback | 1 compact ID | Builds the final eval row and appends already accepted program-graph transaction data. | No |

The builders branch, map, concatenate, validate, and call existing pure domain
functions. None needs JVM-local state at construction time. Reads that precede
an allocation remain part of the larger async coarse-read migration; their
results are ordinary frozen input to the builder. CAS and expected-coordinate
facts retain the writer-side authority to reject stale work.

## Recommended final contract

Keep `seon.db.id/allocate!` as one Bun-local async function with this conceptual
contract:

1. Resolve generator policies for the requested identity attributes from
   ordinary database data already acquired for the attachment. On a cold
   attachment, use the existing query or `execute-many` operation; no dedicated
   wire operation is necessary. Cache this small immutable projection in the
   session owner and invalidate it when `:seon.db.id/generator` or
   `:seon.schema/key` changes.
2. Generate a candidate manifest in Bun and call the synchronous pure builder
   with the allocation-key-to-value map.
3. Validate the builder's ordinary result locally, attach dependent identity
   claims to the candidate manifest, and create one ordinary version-6
   transaction request.
4. Let the session's transaction request owner retain the exact encoded request
   until a terminal response. Reply loss resends the same bytes and request ID;
   it never invokes the builder again.
5. On success or recovered success, return the candidate IDs and the JVM's
   generated entity IDs. The local database read currently used by
   `committed-eids` disappears.
6. Only an exact `generated-candidate-conflict` starts a new candidate round.
   The new transaction data gets a new request ID because its durable
   fingerprint is different. Preserve the existing maximum of 16 rounds.
7. A stale coordinate, CAS loss, invalid policy, invalid builder result,
   request conflict, or unrelated transaction error is terminal for that
   allocation. It is not mislabeled as an ID collision.

The durable policy facts remain authoritative even though Bun proposes values.
The writer's current `prepare-transaction` path resolves policies from its
current immutable database value and rejects a stale, malformed, occupied, or
misplaced candidate before commit. A stale Bun policy cache can therefore
cause a bounded rejection and refresh, never an invalid commit.

The protocol should tighten `::generated-candidates` from `[:vector :any]` to
the existing shared generated-candidate schema when the final ordinary-data
contract is frozen. This is strengthening the current transaction request, not
adding an allocation language or operation.

## Round trips and failure traces

| Trace | Bun/JVM requests | Commit count | Required behavior |
|---|---:|---:|---|
| Warm policy, no collision | 1 transaction | 1 | Normal path. |
| Cold policy | 1 coarse query plus 1 transaction | 1 | Fold the policy query into attachment/bootstrap acquisition where possible. |
| Exact candidate collision | 1 rejected transaction per collision, then 1 accepted transaction | 1 | Rerun the pure builder with fresh candidates and a new request ID. |
| Reply lost before commit | Repeated identical request until terminal failure/success | 0 or 1 | Same request ID and bytes; writer receipt decides. |
| Reply lost after commit | Repeated identical request | 1 | Writer returns `recovered?` and the same generated entity IDs. |
| Coordinate or CAS race | 1 rejected transaction | 0 | Caller recomputes the larger domain operation only if its own contract permits. |

A declarative JVM template saves no request on the normal warm path. It only
avoids the additional request after a generated-candidate collision. It cannot
remove the durable commit acknowledgement, and it must use the same request
receipt machinery for ambiguous delivery.

## Collision and generation measurements

The following Bun 1.3.14 probes ran in this checkout after 1,000 warm-up calls,
100,000 calls per package:

| Generator | Measured time | Throughput | Approximate time per candidate |
|---|---:|---:|---:|
| CUID2, length 12 | 7,484 ms | 13,361/s | 74.8 µs |
| Human ID, three words | 11.0 ms | 9.11 million/s | 0.110 µs |

These are local microbenchmarks, not end-to-end allocation latency. Compact
generation is not free, but one 75-microsecond hash is small beside a durable
transaction and is distributed across Bun agent processes instead of consuming
the JVM writer. Very large multi-ID plan construction should remain a measured
case; 1,000 compact candidates would be roughly 75 ms on the measured process.

The compact syntax has 26 × 36^11, about 3.42 × 10^18, possible strings. A
uniform-space estimate gives a next-candidate collision probability below
3 × 10^-13 even after one million existing compact IDs. CUID2 does more than a
uniform character draw, so this is a syntax-space scale estimate rather than a
proof of its distribution.

The human-readable pool is exactly 15,000,000. Its next-candidate collision
probability is approximately the occupied fraction:

| Existing agent IDs in one database | Next candidate collision |
|---:|---:|
| 1,000 | 0.0067% |
| 10,000 | 0.067% |
| 100,000 | 0.67% |
| 1,000,000 | 6.7% |

Human IDs therefore make collision retry a real, intentionally supported path
at unusually large populations, but not a reason to charge every ordinary
allocation a richer JVM interface. Even at one million occupied combinations,
the independent-draw estimate for exhausting 16 attempts is about
`(1/15)^16`, below 2 × 10^-19. Capacity and word-distribution measurements
should precede any claim that a million human-readable agents belong in one
database.

## Why not the declarative JVM template

The smallest possible template is a recursive placeholder substituted through
maps, vectors, sets, lookup refs, CAS forms, and nested entity maps. It adds at
least these concepts:

- a placeholder value and collision-free escaping rule;
- allowed placeholder positions and value-type validation;
- recursive traversal of every transaction data shape;
- dependent-identity references to generated placeholders;
- a template fingerprint and protocol schema;
- a JVM generator implementation and package dependencies; and
- fixtures proving that a literal resembling a placeholder cannot be rewritten.

That still does not express `compile-plan`, `compile-reconcile`,
`initial-agent-tx`, recovery assembly, or eval tee assembly. Bun would have to
run those functions first using placeholder values that intentionally fail the
real ID schemas, or the JVM would need a catalog of remotely named domain
builders and their arguments. The first shape forces invasive domain rewrites;
the second is a remote interpreter coupled to application namespaces.

The template also broadens the trust boundary. Today the writer accepts one
well-known Datahike transaction data language, rejects reserved protocol
attributes, validates every generated identity claim, and fingerprints the
exact data to commit. A second substitution language creates inputs whose wire
meaning differs from their committed meaning and must be audited independently.

## Simpler seams considered

### Datahike tempids only

Datahike already retries transaction interpretation when a tempid resolves
through an existing unique identity. That cannot mint a new persistent
identity value: the transaction data is frozen, and Datahike's retry preserves
that value. It therefore cannot replace generated-candidate collision retry.

### Authority candidate reservation

The JVM could return candidates before Bun builds the transaction. This removes
policy caching from Bun but makes every allocation at least two round trips and
creates reservation lifetime, expiry, disconnect, and cleanup state. A returned
candidate is not unique until committed, so reservation either provides no
new guarantee or becomes another database write mechanism.

### Transaction function or remotely named builder

A transaction function could generate and build inside the writer, but current
builders close over arbitrary domain data and functions. Shipping closures is
not portable; registering them by name couples the database protocol to Seon
application code and expands executable authority. This is the remote
interpreter the design is trying to avoid.

No third seam is smaller than the existing candidate-manifest transaction.

## Source deletion and addition impact

With the recommended mechanism:

- delete the CLJS local Datahike policy query and `committed-eids` database
  lookup;
- remove `:seon.db/conn` and Datahike writer assertions from the Bun-facing
  allocation request;
- route the frozen transaction directly through the persistent session's one
  transaction request owner;
- retain the pure builders at their domain call sites;
- retain the JVM candidate preparation, dependent-identity validation, and
  generated-EID report/recovery code;
- remove JVM-only candidate generation and its Java candidate packages if no
  remaining production JVM caller needs `id/allocate!` after the cut; and
- add only a small attachment-owned generator-policy projection/cache plus
  collision retry around the existing transaction operation.

The declarative alternative adds a protocol operation or variant, template
schema, traversal/substitution implementation, JVM generator ownership, and
per-domain template migrations while deleting only the local builder callback
shape. It is more code, more vocabulary, and a wider security surface for a
rare-round-trip optimization.

## Graduation proof

Before replica deletion, retain or add focused proofs for:

1. all eleven production builder shapes running without a local Datahike value
   or connection;
2. one and many compact allocations, one human-readable allocation, nested
   components, CAS forms, expected coordinate, and dependent identities;
3. a forced first-round direct collision and a forced dependent-identity
   collision rebuilding the complete transaction with a new request ID;
4. 16 forced collisions terminating with zero commits;
5. exact request redelivery after a simulated post-commit reply loss returning
   the original IDs, entity IDs, coordinate, and `recovered? true`;
6. changed transaction data or candidate manifest under a reused request ID
   returning request conflict;
7. stale generator-policy cache rejection followed by one policy refresh,
   without admitting an invalid candidate;
8. unrelated CAS, coordinate, schema, and transaction errors causing no
   candidate retry; and
9. static Bun reachability containing no Datahike/Konserve CLJS dependency and
   the JVM artifact containing no candidate-generation package unless a real
   JVM caller remains.

The retained JVM tests already prove multi-generated relationships, nested
component allocation, concurrent candidate conflict atomicity, manifest
fingerprinting, exact committed recovery, dependent-identity collision, whole
builder rebuild, and bounded exhaustion. The new proof should exercise those
same semantics through the final persistent Bun session, not preserve a local
replica test path.
