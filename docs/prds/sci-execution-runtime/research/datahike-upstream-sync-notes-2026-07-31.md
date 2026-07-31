---
type: research
status: active
tags: [research, dependency, datahike, database]
---

# Datahike upstream sync notes — 2026-07-31

## Outcome

The merge was attempted and **stopped without resolution**. The maintained
branch remains `main` at `9b3be9d59cb07d9c895af280e60eb074bb57a400`.
Nothing was committed or pushed, and the super-repository gitlink did not
move.

This is the required semantic-conflict stop, not a failed textual merge.
`git merge --no-commit --no-ff upstream/main` produced 50 conflict hunks in
12 files. More importantly, upstream `3342c643` imposes schema-transition
policies opposite to maintained Seon behaviors, while the recursive-rule and
query-execution rewrites replace fork-specific execution contracts. Choosing
one side would discard a known-correct behavior.

The in-progress merge was inspected and then aborted. Post-abort proof:

```text
## main...origin/main
9b3be9d59cb07d9c895af280e60eb074bb57a400
95  28
```

## Dependency ledger

- Maintained fork: `seantempesta/datahike` `main` at `9b3be9d5`.
- Upstream: `replikativ/datahike` `main` at `437d6401`.
- Merge base: `85c40aee8a8662d757fcd69f85c5477ff36e605f`.
- Divergence: 95 commits only on maintained `main`; 28 only on
  `upstream/main`.
- Fork source owners: `reference-code/datahike/src/datahike/query*.cljc`,
  `db/transaction.cljc`, `writer.cljc`, `connector.cljc`, versioning and GC,
  plus `src-secondary/.../proximum.clj`.
- First-party consumers and proof surfaces:
  `src/seon/cluster/store.clj`, `src/seon/cluster/registry.clj`,
  `src/seon/schema/datahike.cljc`, `test/seon/datahike_fork_test.clj`, and the
  cluster boot/registry/schema suites.
- Shortest falsifier: a no-commit merge plus inspection of the three-stage
  conflict blobs. It found a policy contradiction before any test or live
  proof could honestly apply to a merged revision.

## Maintained-fork inventory

`git log upstream/main..main` contains 95 commits. Their major intent groups
are:

- query correctness, deterministic planning, bounded execution, cache
  identity/inheritance, dependency evidence, and resource admission;
- versioning, branch lifecycle, `force-branch!`, the store-scoped branch-roster
  fence, expected-basis fencing, writer durability, and committed-report
  readiness;
- Proximum secondary-index ownership, reopen, force, and filtered-search
  behavior;
- schema/index evolution, including current+temporal AVET backfill and safe
  indexed-schema removal; and
- CLJS compatibility, build/codegen, documentation, and resource-ownership
  tests retained in the fork history.

Exact chronological SHA inventory (each fork-only commit appears once):

```text
1c630664 6e0b891e a7690d0b fc1bba01 f77ea5f1 f2f2809d
6cf05300 5f62d57f 7ef2b5de e6d196d5 22153e6f da257d38
c9a2704c eedde719 fa03b0fa 5566ab13 1598a824 6e2d9bee
9fbb66fc 23ad3d9f d6a99f53 1528147f cae24611 7f2c77d0
30a15483 67934f65 ca4383dc ea6e9881 1e78cb9c 6f90b339
41764938 069a807e f18a0c82 a7c9aeb7 4605c12a 96e465c3
6c54b88a 92300d4e 9ada7550 0b652215 acbc6f20 a795dc37
f9fc0940 999e26a2 f8192962 5e82166f 45f58511 940810f5
092f5b05 04213e17 7eb1b849 c1faf70d d7ac886f e1d28437
f8d0b34b 34365046 d9765276 8cb44a29 a5315858 1296cfc4
f0ee54c2 f3776b72 d21abadb 670cd1ad 0070d507 0f40370e
a464cd88 4c55791b b9a487f6 6a0386d2 e37ae715 2e6c7bcf
0cf39e57 2241df17 3af6e46e 107bc8f6 d45ee18b eedea005
d664dce7 c2379bcf d59f76bb 6611de27 6f256908 58764d90
c1c4c293 9c356e32 caf52685 357ffc87 9a7a9ef1 19f5cdd9
5cdbc88a c0a74e12 b73550bf e2f71ad0 9b3be9d5
```

The inventory command of record is:

```bash
git -C reference-code/datahike log --reverse \
  --format='%h%x09%ad%x09%an%x09%s' upstream/main..main
```

## Upstream-only inventory and risk

### A — query-engine correctness and tests

These 15 commits are query/planner/executor correctness work. They are not
storage-format changes, although several collide with maintained query
machinery:

| Commit | Intent |
|---|---|
| `61f436d8` | Keep predicates on join-eliminated vars in the correct slot. |
| `2bafda19` | Resolve variable `get-else` defaults. |
| `e15505fa` | Make recursive-rule demand restriction incremental. |
| `b5ef35e2` | Bind rule-head vars absent from branch bodies. |
| `c4d19929` | Validate clause bindings before folding and scope negations. |
| `d95785fa` | Fix anti-fold locality and magic-set direction. |
| `e4e26c68` | Fix anti-joins over `:in` vars and boolean empty-column relations. |
| `a5045907` | Preserve sub-plan context and recursion across a changed edge. |
| `8e5da567` | Expand differential generation and fix three resulting query defects. |
| `cf8b75df` | Treat repeated variable occurrences as equality obligations. |
| `60f2f0a7` | Extend equality obligations across scopes, rules, attrs, and history. |
| `5f859c00` | Stop recursive rules inheriting caller relations. |
| `c1daf5b4` | Fix multi-clause recursive rules on CLJS. |
| `6d5f602d` | Evaluate caller-supplied recursive-rule args as demand. |
| `437d6401` | Preserve left-outer `get-else` semantics. |

### B — storage, writer, schema, GC, or secondary-index touching

These nine commits require the higher-risk storage/schema proof surface:

| Commit | Intent |
|---|---|
| `11426b97` | Add GC-tracked `:db.type/store-ref`. |
| `3342c643` | Mixed query/transact/schema/search-cache systematic repair. |
| `1ac36159` | Share Konserve's monotonic write clock with GC. |
| `e585fd76` | Content identity for nested unstructured objects. |
| `ac70ef3a` | Attribute predicates, transaction predicates, and stored value caps. |
| `8f6d1e43` | Accept Fressian BigDecimal values on CLJS. |
| `999fffa8` | Add float/double array value types. |
| `fabf4b41` | Let secondary indices own external-engine query specifications. |
| `779724b6` | Derive/validate tuples with attribute refs. |

### C — public API, dependency, build, or isolated feature changes

| Commit | Intent |
|---|---|
| `16c1ab9a` | Add the CLJS async-storage seam and dependency changes. |
| `44c59fd1` | Add BFS distances and one lowest-common-ancestor API. |
| `f8176958` | Replace the LCA heuristic with lowest-common-ancestor sets. |
| `f29c9e9c` | Re-pin Stratum `0.3.69` to `0.3.77`. |

## Semantic conflicts requiring a design ruling

### 1. Indexed schema evolution — opposite policies

Upstream `3342c643` validates the resulting schema state by rejecting
`:db/index` enablement when an attribute has current or historical datoms. Its
assumption is that AVET was not populated.

Maintained commits `58764d90` and `c1c4c293` deliberately support exactly the
`nil -> true` transition by atomically backfilling current and temporal AVET,
then reject only non-monotonic index changes. Taking upstream would delete a
Seon capability; taking ours unchanged would bypass upstream's new universal
validation. This is a policy collision.

### 2. Schema removal with retained history — opposite policies

The same upstream `3342c643` validation rejects removing a schema entry while
historical datoms remain. Maintained `5cdbc88a` and `b73550bf` deliberately
fence on **current** AEVT data, allowing removal after current datoms are gone
while ordinary history remains readable through Seon's historical schema-row
projection. This is the schema-removal design proven on 2026-07-30, not an
incidental implementation detail.

### 3. Recursive-rule shortcut contract — incompatible metadata and execution

Maintained `1598a824` lowers direction-aware `:base-scan-info` plus
`:rec-expand-infos`, and its executor uses those topology records to avoid
wrong-direction or CLJS-truncated recursion.

Upstream `d95785fa`, `a5045907`, and `6d5f602d` instead lower
`:base-scan-attr`, `:magic-demand-sound?`, and `:delta-driven-sound?`, and
change demand propagation and branch-execution inputs. The no-commit merge
conflicted across both lowering and the full fixpoint executor. This is one
execution contract with two non-equivalent representations; concatenating the
fields would leave two authorities deciding whether the shortcut is sound.

### 4. Equality obligations versus maintained fast-path contracts

Upstream `cf8b75df` and `60f2f0a7` replace boolean scan-value/transaction
checks with slot-coded equality obligations, add merge-order constraints, and
change sorted-merge eligibility. Maintained `6611de27`, `9a7a9ef1`, and
`19f5cdd9` separately protect symbol values, cardinality-many scans/merges,
and stable identical-operation removal. The merge has 27 hunks in
`query/execute.cljc` and three in `query/plan.cljc`; the array shapes,
eligibility predicates, and operation-removal semantics must be redesigned as
one path and re-proven, not selected hunk by hunk.

### 5. Connection identity and stored value-cap authority

Maintained connector code derives a semantic connection-acquisition key that
includes remote writer identity and deliberately omits create-time-fixed
store settings. Upstream `ac70ef3a` replaces the nearby normalization owner
and merges persisted value caps into connect-time config before consistency
checks. The merge must decide whether value caps participate in physical
connection sharing and how older databases with absent caps behave. A wrong
choice can share a connection across inequivalent enforcement policies.

These are more than two semantic decisions, so the owner-specified hairy-merge
stop applies even apart from the two direct policy contradictions in
`3342c643`.

## Textual conflicts that are not themselves the stop

The no-commit merge also conflicted in changelog/dependency ordering, CLJS test
registration, Proximum branch cleanup versus the new KNN declaration, writer
imports, and secondary-index tests. Those appear mechanically preservable by
retaining both declarations or tests. They do not reduce the semantic
conflicts above.

Conflict-hunk count by file:

| File | Hunks |
|---|---:|
| `src/datahike/query/execute.cljc` | 27 |
| `src/datahike/query/plan.cljc` | 3 |
| `src/datahike/query.cljc` | 3 |
| `src/datahike/db/transaction.cljc` | 3 |
| `test/datahike/test/nodejs_test.cljs` | 3 |
| `CHANGELOG.md` | 3 |
| `src/datahike/query/lower.cljc` | 2 |
| `src/datahike/connector.cljc` | 2 |
| `deps.edn`, `writer.cljc`, Proximum source/test | 1 each |

## Proof status

The requested Datahike suite, Seon focused gates, and live scratch-cluster
smoke were **not run as merge proof**, because there is no resolved merge
revision to prove. Running them on either parent would not answer the conflict.
No claim of merged correctness is made.

The next safe boundary is an owner ruling on the two schema policies, followed
by a dedicated integration design for the recursive/equality execution
contracts. Only then should a new merge commit be constructed and the full
three-layer proof run.

## `:db.type/store-ref` and Seon's future blob seam

No Seon wiring was attempted.

Upstream `11426b97` provides a UUID-valued datom type whose distinguishing
behavior is GC marking. A referenced object stays live across branches and
retained history; retracting the last reachable datom makes an in-store object
collectable. It also exposes the live mark set for an external object-store
sweep. `datahike.blob/blob-id` derives a content-addressed UUID, but Datahike
deliberately does not own upload/fetch APIs.

That is a promising substrate for the transport law's future blob seam:
bulky immutable bytes can remain outside ordinary datoms while the database
row carries identity, digest, size, content type, and ownership connections.
The content-addressed UUID preserves historical reads and deduplicates bytes;
the datom becomes the durable reachability root. A future design still must
settle storage placement, the write-before-reference GC window, external-store
age floors/guards, streaming/range access, and whether retained history should
retain the bytes. Those decisions belong to the blob owner, not this sync.
