---
type: research
status: active
tags: [research, architecture, database, agent]
---

# Crash-proof feasibility — datahike/sci/konserve source research (2026-07-22)

Orchestrator-accepted; THE design document for the post-W5 virtual-agents
series. Verdict: crash-SURVIVABLE at turn/form granularity is achievable on
these dependencies after the four durable loop closures (claim/lease,
input-consumption link, attempt-open receipts w/ idempotency keys, phase
cursor — concrete attribute proposals inside). Sharp caveats: branch/time-
travel granularity is COMMIT-level (batched txs share a commit ID; arbitrary
branch-from-t expected to falsify); whole-value blobs COPY on change
(incremental big values need datom normalization or an explicit konserve
DAG); exactly-once external effects need provider/capability idempotency;
Proximum head repointing not proven atomic with the primary head (queued
q28). The three falsifier probes are the series entry gates.

# Dependency-source feasibility audit

## Scope and pins

Source aliases used below:

- `$DH` = `reference-code/datahike`, measured at `c1c4c29382257317cd34e160df11985cb384f8a6`.
- `$SCI` = `reference-code/sci`, branch `seon`, measured at requested `8fac6e88f32d53a5fd82ebe80640881e317b84fd`.
- `$K` = `/Users/sean/.gitlibs/libs/org.replikativ/konserve/b5c99bc02a7175652a610324215288b78551801f`.
- `$P` = `/Users/sean/.gitlibs/libs/org.replikativ/proximum/9846d3e79e1aee48474bc876d3d563d7137209c6`.

Datahike pins Konserve, persistent-sorted-set, Hasch, and Proximum at those versions (`$DH/deps.edn:2-20`, `$DH/deps.edn:86-100`). No repository files were changed.

## 1. Structural-sharing reality

### Datahike index persistence

Datahike database values are not serialized as one monolithic database blob:

1. Before publishing a database value, `db->stored` flushes EAVT, AEVT, AVET and—when history is enabled—the three temporal indexes, then records detached roots (`$DH/src/datahike/writing.cljc:48-84`, `$DH/src/datahike/writing.cljc:135-180`).

2. Its persistent-sorted-set adapter assigns each new node a logical address and queues `[address node]`; existing addressed nodes remain references. With `:crypto-hash? true`, addresses are content-derived; otherwise they are fresh/recycled sequential UUIDs (`$DH/src/datahike/index/persistent_set.cljc:239-282`, `$DH/src/datahike/index/persistent_set.cljc:409-444`).

3. The current defaults use persistent-sorted-set, retain temporal history, and do not enable cryptographic content addressing (`$DH/src/datahike/config.cljc:19-25`). Thus the default is identity-addressed structural sharing, not universal content-addressed deduplication.

4. Root fusion may inline the root node into the stored database record, but deeper tree nodes remain separate. Crypto-addressed roots are deliberately retained separately where sharing could otherwise be broken (`$DH/src/datahike/writing.cljc:135-180`, `$DH/src/datahike/writing.cljc:384-408`).

5. Pending immutable index nodes are written before the immutable commit and mutable branch head, so a published head does not precede its child objects (`$DH/src/datahike/writing.cljc:410-421`, `$DH/src/datahike/writing.cljc:467-552`).

6. Konserve maps each logical key to a physical key derived with `uuid(key)` (`$K/src/konserve/impl/defaults.cljc:44-53`).

**Verdict:** successive Datahike database values share unchanged index nodes on disk. “Immutable just-transformed data is cheap” is true for Datahike index paths, proportional to changed datoms/tree paths rather than total database size. It does not follow for arbitrary values merely because Konserve is underneath.

### Flush and GC

Offline GC is reachability-based:

- It begins from all registered branch heads, follows retained commit parents, and marks current, temporal, schema, and secondary-index roots before sweeping (`$DH/src/datahike/gc.cljc:22-81`, `$DH/src/datahike/gc.cljc:83-146`).
- Konserve’s collector does not understand object graphs itself; it deletes keys not present in the caller-supplied whitelist and old enough for the cutoff (`$K/src/konserve/gc.cljc:8-41`).
- Therefore a tree node shared by any retained database value or branch remains live.

Online GC is much weaker evidence for time travel:

- It is disabled when multiple branches exist because a node freed on one branch may remain shared by another (`$DH/src/datahike/online_gc.cljc:137-158`).
- On one branch it deletes or recycles freed addresses without traversing old commit records (`$DH/src/datahike/online_gc.cljc:176-212`).

Consequently, indefinite cold readability of old commits while online GC is enabled is **NOT GROUNDED** and appears unsafe. Cheapest falsifier: retain commit A, replace enough tree nodes under zero-grace online GC, then cold-load A through `commit-as-db`.

### Arbitrary agent values and blobs

Konserve does not transparently preserve the in-memory structural sharing of an updated Clojure value:

- A nested EDN update reads and deserializes the old top-level value, runs `update-in`, serializes the complete replacement, writes it, then atomically moves it into place (`$K/src/konserve/impl/defaults.cljc:57-123`, `$K/src/konserve/impl/defaults.cljc:321-346`).
- Binary `bassoc` treats one key as one binary input, not as a graph of independently addressed chunks (`$K/src/konserve/impl/defaults.cljc:580-611`).
- Incognito handlers are merged into the serializer; that is a codec-extension path, not an object-graph persistence layer (`$K/src/konserve/serializers.cljc:29-54`).

For current whole-content-addressed `my.blob` semantics:

| Change | Present storage behavior |
|---|---|
| `v2` serializes identically to `v1` | Same whole-content address; whole-blob dedupe. |
| One leaf changes | New serialization and full new blob. |
| Two versions share large subtrees in memory | No subtree sharing survives serialization automatically. |

Exact filesystem amplification, compression, or block-level clone behavior is **NOT GROUNDED** in these dependencies.

### What could make large transformed values incremental?

| Representation | Incremental sharing | Cost of a small transform |
|---|---|---|
| Opaque SHA blob | Whole-value only | Serialize/write all of `v2`. |
| One nested Konserve EDN value | None below its top-level key | Read old value, serialize/write all of `v2`. |
| Normalized Datahike entities and refs | Yes, through unchanged datoms and index nodes | Transact changed datoms and update affected current/temporal index paths (`$DH/src/datahike/db/transaction.cljc:429-474`, `$DH/src/datahike/db/transaction.cljc:528-572`). |
| Explicit content-addressed DAG above Konserve | Potentially | Write changed leaves and ancestor path, then publish a root pointer. |
| PSS-like ordered representation | Yes | Dirty tree path/buffers only, but only suitable for shapes that admit that representation. |

Konserve supplies enough flat primitives for a custom DAG: immutable child objects can be written before the mutable root using ordered multi-association (`$K/src/konserve/core.cljc:434-472`), followed by an application-specific reachability walk for GC (`$K/src/konserve/gc.cljc:8-41`).

Hasch and Incognito internals were not present as permitted vendored source. Dependency-visible call sites establish UUID/key derivation and serializer handlers, but claims that they already provide chunking, DAG persistence, or subtree GC are **NOT GROUNDED**. They do not provide such a finished mechanism through the Konserve APIs inspected here.

## 2. Context materialization speed

### What `sci/fork` copies

`sci/fork` creates a new env atom containing the existing immutable env value:

```clojure
(update ctx :env (fn [env] (atom @env)))
```

`$SCI/src/sci/core.cljc:318-323`.

It does not traverse or replay namespaces, so fork creation is effectively shallow persistent-root copying. But existing namespace maps still contain mutable SCI `Var` objects: a var’s root is mutable, and redefining an existing var calls `bindRoot` (`$SCI/src/sci/lang.cljc:71-105`, `$SCI/src/sci/impl/evaluator.cljc:25-47`).

Measured isolation probe:

```clojure
{:base-after-plain 2
 :detached          3
 :base-after-detached 2}
```

A plain fork’s redefinition changed the base var. Removing and recreating the private namespace caused fresh vars and isolated the later definition. That follows from namespace dissociation/recreation and fresh interning (`$SCI/src/sci/impl/namespaces.cljc:604-634`).

**Conclusion:** W3d1’s detach-then-recreate replay is necessary. “Private forks copy” means private env topology, not deep-copying pre-existing vars.

### Registry/load-fn behavior

SCI’s `load-fn` is already the right namespace-lazy boundary:

- If a namespace is present and reload was not requested, `require` links it without invoking `load-fn` (`$SCI/src/sci/impl/load.cljc:161-197`).
- On a miss, `load-fn` receives the requested library, context, requesting namespace, and reload state (`$SCI/src/sci/impl/load.cljc:198-206`).
- Returned source is parsed, analyzed, and evaluated in full, after which the namespace is linked (`$SCI/src/sci/impl/load.cljc:207-234`).
- JVM loads are serialized through one process-global lock (`$SCI/src/sci/impl/load.cljc:264-267`).

The laziness is therefore **namespace-granular, not function-granular**. Replaying N independently stored function sources remains linear in total forms/source bytes because `eval-string*` parses, analyzes, and evaluates each form anew (`$SCI/src/sci/impl/interpreter.cljc:89-109`).

### Cheap JVM probe

Hot JVM, synthetic definitions shaped like:

```clojure
(defn f123 [x] (let [y (inc x)] (+ y 2)))
```

| Operation | p50 | p90 |
|---|---:|---:|
| Fork only | ~0.049 µs | ~0.056 µs |
| Replay 1 definition | 0.170 ms | 0.423 ms |
| Replay 10 definitions | 0.382 ms | 1.282 ms |
| Replay 100 definitions | 2.239 ms | 3.381 ms |
| Replay 500 definitions | 7.276 ms | 9.363 ms |

These are mechanism measurements, not a production SLO. They exclude database reads, real dependency closures, schemas, instrumentation, wrapper reconciliation, contention on the global load lock, and larger function bodies.

### Safe caching boundary

Safe to share across agents:

- Immutable context roots, source/digest data, permissions and readers.
- Deliberately shared registry vars whose upgrades are meant to become visible everywhere; `add-namespace!` merges the supplied namespace objects directly (`$SCI/src/sci/core.cljc:651-656`).
- Already materialized dependency namespaces inherited by forks created afterward.

Must be private or reconstructible:

- Agent-authored vars and namespaces.
- Any context containing partial effects from a failed multi-form replay.
- Parsed/analyzed private code: SCI exposes no durable compiled-context serialization in this path—**NOT GROUNDED**.

A viable turn path is therefore: shallow-fork preloaded base → detach/recreate private namespaces → replay the agent’s private definitions → satisfy other namespaces lazily through `load-fn` → retain that private context as a revision-keyed process-local cache.

## 3. Containment limits

SCI is an interpreter boundary, not a complete process-isolation or error-value boundary.

| Failure class | Honest verdict | Evidence and policy |
|---|---|---|
| Parse/analysis/ordinary runtime failure | Contained in-process by the embedder | Runtime evaluation catches `Throwable` only to add location, then rethrows; `eval-string*` does not convert it to data (`$SCI/src/sci/impl/interpreter.cljc:29-78`, `$SCI/src/sci/impl/interpreter.cljc:89-109`). The outer host must catch and return `:seon/error`. |
| Partial multi-form mutation | Context may be tainted | Forms are evaluated sequentially, so earlier definitions survive a later failure (`$SCI/src/sci/impl/interpreter.cljc:99-109`). `load-fn` cleanup catches `Exception`, not JVM `Error` (`$SCI/src/sci/impl/load.cljc:217-227`). Discard uncertain private contexts. |
| Interpreted infinite loop/recursion | Contained in-process | Interrupt polling occurs at interpreted function entry and loop recurrence (`$SCI/src/sci/impl/fns.cljc:24-30`, `$SCI/src/sci/impl/fns.cljc:39-81`). |
| Sandboxed code attempting to catch timeout | Contained in-process | Interrupt uses a private marker; user catches cannot forge or consume it, and a throwing `finally` cannot mask it (`$SCI/src/sci/interrupt.cljc:32-42`, `$SCI/src/sci/impl/utils.cljc:42-56`, `$SCI/src/sci/impl/evaluator.cljc:74-175`). |
| Host sequence/regex operation | Only selected operations contained | The opt-in overrides poll selected producers/materializers and JVM regex; the source explicitly says CLJS regex cannot be interrupted in-thread (`$SCI/src/sci/interrupt.cljc:44-117`, `$SCI/src/sci/interrupt.cljc:205-315`). |
| Arbitrary blocking/uncooperative host call | Respawn boundary | SCI cannot inject polling into arbitrary host code. Deadline → interrupt → terminate disposable host/process. |
| `StackOverflowError` | Often recoverable, not an invariant | A disposable `-Xss256k` probe surfaced an SCI exception caused by `StackOverflowError`; the same JVM subsequently evaluated `42`. SCI catches `Throwable` around execution (`$SCI/src/sci/impl/interpreter.cljc:60-75`). Universal recovery after stack exhaustion is **NOT GROUNDED**; discard the context and retain respawn fallback. |
| `OutOfMemoryError` | Respawn boundary | A disposable `-Xmx32m` probe recovered after one heap-exhaustion case, but SCI’s rethrow/location path itself allocates (`$SCI/src/sci/impl/utils.cljc:121-182`). Reliable in-process recovery across heap, metaspace, direct, or native exhaustion is **NOT GROUNDED**. |
| Thread leak | Respawn boundary if API exposed | SCI’s future addon exposes host futures and `pmap` but has no context-owned join/cleanup registry (`$SCI/src/sci/addons/future.clj:7-47`). Exclude thread APIs or recycle the host process. |
| Native/JNI crash, `Runtime.halt`, memory corruption | Not containable by SCI | SCI invokes configured host constructors/methods (`$SCI/src/sci/impl/interop.cljc:40-73`, `$SCI/src/sci/impl/interop.cljc:105-126`). Process/capability isolation is required. |
| Privileged native code corrupting durable files | Genuinely fatal remains possible | SCI’s default class surface is limited but embedders can add classes (`$SCI/src/sci/impl/opts.cljc:101-118`, `$SCI/src/sci/impl/opts.cljc:236-272`). “No package can reach durable authority” is an application isolation property, **NOT GROUNDED** by SCI. |

The fork’s classifier patch does not close this: it adds unresolved-symbol data, not a universal error-value/resource boundary (`$SCI/src/sci/impl/resolve.cljc:323-334`). Complete containment means outer `Throwable` conversion plus process supervision and capability isolation—not one immortal JVM.

“Genuinely fatal-to-durability should be NONE” is therefore:

- **Refuted today** if arbitrary native/host packages can reach database files, credentials, or writer internals.
- Achievable as an architectural invariant for process crashes only if disposable hosts cannot access durable storage authority and every externally visible effect has a durable idempotency/receipt protocol.
- Media corruption, storage implementation defects, and cross-store atomicity are not eliminated by these three dependencies and are **NOT GROUNDED** as impossible.

## 4. Time travel and fix-and-resume

### Temporal reads

- `as-of`, `since`, and `history` require temporal indexes (`$DH/src/datahike/api/impl.cljc:148-194`).
- Numeric `as-of` is inclusive; `since` is exclusive (`$DH/src/datahike/db.cljc:142-152`).
- History scans combine current and temporal indexes and reconstruct the requested view (`$DH/src/datahike/query/execute.cljc:868-950`).
- Replacements/retractions enter the temporal indexes except for `:db/noHistory` attributes (`$DH/src/datahike/db/transaction.cljc:429-474`, `$DH/src/datahike/db/utils.cljc:48-50`).

This gives exact read-only historical views at retained transaction points. An `AsOfDB` is still a wrapper over the current origin database; it is not itself a writable historical database (`$DH/src/datahike/db.cljc:594-614`).

### Branch semantics

Same-store `branch!` accepts:

- an existing branch keyword; or
- an immutable commit UUID when the commit graph is enabled.

It does not accept a transaction ID (`$DH/src/datahike/versioning.cljc:205-257`).

`fork-database` creates an independent store by copying every source Konserve key and selecting an exact commit/date point. It is not a cheap same-store CoW branch (`$DH/src/datahike/versioning.cljc:485-519`, `$DH/src/datahike/versioning.cljc:571-651`).

### Critical branch-from-t limitation

`fork-database` assumes a commit can be found whose database has the requested `:max-tx` (`$DH/src/datahike/versioning.cljc:491-502`). The writer, however, drains multiple queued transaction reports, persists only the last resulting database once, and assigns that committed database/commit ID to every report in the batch (`$DH/src/datahike/writer.cljc:205-240`).

Therefore an intermediate transaction in a writer batch can be:

- queryable through `as-of`;
- absent as an immutable commit;
- unusable as input to `branch!`; and
- rejected by `fork-database {:at t}`.

**Arbitrary writable branch-from-every-transaction/form boundary is NOT GROUNDED.** The source’s “one commit per transaction” assumption is contradicted by writer batching.

### Honest corrective workflow

When the target has an exact commit:

1. Branch from that commit and apply corrective transactions.
2. Validate the repair branch.
3. Operate on that branch, merge caller-supplied corrective tx-data, or publish it using `force-branch!`.

Normal commit publication writes immutable nodes/commit before updating the branch head (`$DH/src/datahike/writing.cljc:467-550`). `force-branch!` similarly prepares immutable state and then replaces the destination head (`$DH/src/datahike/versioning.cljc:291-304`, `$DH/src/datahike/versioning.cljc:329-410`).

Constraints:

- `force-branch!` requires exclusive write access; its expected-head check is not an independent-writer CAS (`$DH/src/datahike/versioning.cljc:295-304`).
- Existing connections must be released and reconnected afterward (`$DH/src/datahike/versioning.cljc:303-304`).
- `merge!` records multiple parents, but the caller supplies the actual corrective transaction; Datahike does not calculate the repair (`$DH/src/datahike/versioning.cljc:656-670`).

For a transaction lacking an exact commit, byte-faithful writable restoration from exactly that `t` is **NOT GROUNDED**.

### Long-history cost and GC

A history-enabled head owns six primary index roots: three current and three temporal (`$DH/src/datahike/db.cljc:897-957`). Retractions and replacements keep adding historical datoms (`$DH/src/datahike/db/transaction.cljc:459-474`).

Offline GC’s history window can remove old commit snapshots and unreachable nodes, but it always marks the live head’s temporal roots. It therefore does not erase old fact history retained by those roots (`$DH/src/datahike/gc.cljc:31-74`, `$DH/src/datahike/gc.cljc:148-190`). Explicit purge removes matching facts from both current and temporal indexes (`$DH/src/datahike/db/transaction.cljc:477-500`, `$DH/src/datahike/db/transaction.cljc:809-818`).

Long history consequently grows with both:

- commit/root retention; and
- logical fact churn in the live temporal indexes.

Exact growth rates are **NOT GROUNDED** without workload measurement.

### Proximum interplay

Datahike branches a Proximum secondary by loading the native commit referenced by the selected Datahike commit, creating/syncing a native branch, and storing the new native commit/root in the Datahike secondary key map (`$DH/src-secondary/datahike/index/secondary/proximum.clj:461-480`).

Proximum has its own roots and branch-aware reachability GC (`$P/src/proximum/writing.clj:37-89`, `$P/src/proximum/gc.clj:139-188`, `$P/src/proximum/gc.clj:216-257`). Datahike deliberately does not mark Proximum’s separate store (`$DH/src-secondary/datahike/index/secondary/proximum.clj:259-261`, `$DH/src-secondary/datahike/index/secondary/proximum.clj:385-386`).

Two gaps follow:

- Datahike branch deletion does not delete the corresponding native Proximum branch, although Proximum has its own deletion path (`$DH/src/datahike/versioning.cljc:261-289`, `$P/src/proximum/versioning.clj:184-210`). Native branch leakage is therefore a supported inference.
- `force-branch!` updates the Proximum destination first, then publishes the Datahike primary head (`$DH/src/datahike/versioning.cljc:339-401`, `$DH/src-secondary/datahike/index/secondary/proximum.clj:388-459`). Those are two stores, so atomic crash recovery between them is **NOT GROUNDED**.

## 5. Synthesis: minimal end state

Taking the companion audit’s four missing closures as the starting premise, the loop needs four durable datom families.

### A. Exclusive next-turn claim/lease

Proposed attributes:

```clojure
:seon.run/next-turn
:seon.turn-claim/id
:seon.turn-claim/run
:seon.turn-claim/turn
:seon.turn-claim/holder
:seon.turn-claim/epoch
:seon.turn-claim/lease-until
:seon.turn-claim/program-digest
:seon.turn-claim/state
```

Acquisition must be one writer transaction that compares/swaps the cardinality-one run frontier and creates the claim. A lease alone is not exclusive ownership. Datahike’s CAS validates the prior cardinality-one value before replacing it (`$DH/src/datahike/db/transaction.cljc:963-985`).

### B. Input-consumption link

Proposed attributes:

```clojure
:seon.input-consumption/id
:seon.input-consumption/claim
:seon.input-consumption/input
:seon.input-consumption/ordinal
```

Create these in the admission transaction. Restart then answers “which exact inputs did this claimed turn consume?” without rereading a mutable mailbox projection.

### C. Attempt-open receipts

Proposed attributes:

```clojure
:seon.attempt/id
:seon.attempt/turn
:seon.attempt/ordinal
:seon.attempt/request-digest
:seon.attempt/provider-key
:seon.attempt/opened-at
:seon.attempt/state
:seon.attempt/reply-blob
:seon.attempt/error
```

Persist `:open` before dispatch. The provider/capability call uses the stable attempt ID as its idempotency key. Terminal completion is a CAS transition so a restarted driver cannot silently create a second logical attempt. Where a provider does not support idempotency, exactly-once external effects remain **NOT GROUNDED**; only at-least-once plus reconciliation is honest.

### D. Reply/eval phase cursor

Proposed attributes:

```clojure
:seon.turn/phase
:seon.turn/reply-blob
:seon.turn/parsed-source-digest
:seon.turn/next-form
:seon.turn/current-eval-receipt
:seon.turn/program-generation
:seon.turn/terminal-result
```

The phase and next-form cursor are cardinality-one and advanced transactionally. A form’s durable result/error receipt must land before the cursor advances. On restart, the driver either resumes the next form or reconciles the one open receipt; it never infers progress from a recreated SCI context.

### Large-value publication

The honest storage rule is:

1. Publish a complete blob or content-addressed DAG root.
2. Only then transact the datom referring to its stable address.
3. Treat an unreferenced blob/DAG node as collectable garbage.

Opaque changed values still copy whole. If large transformed agent values must be genuinely incremental, either normalize their meaningful structure into datoms or build the explicit Konserve DAG. The dependency does not make whole-value serialization incremental automatically.

### What stays process-local forever

These remain caches/resources, never semantic authority:

- SCI contexts, namespace maps, vars, parsed forms and analyzer artifacts.
- Registry closures and live host object identities.
- Sockets, provider clients, streams, timers, abort controllers.
- Threads, executors, in-flight promises and native handles.
- Datahike connections/database-value handles.
- SSE/render delivery state.

Their invalidation key is the durable program/source generation plus database basis. On mismatch, serious eval failure, `StackOverflowError`, OOME, connection replacement, or process restart, discard and reconstruct them. SCI supplies reconstruction primitives, not durable context serialization.

## Final verdict

The owner’s target is achievable with an important wording correction:

> Crash-survivable at turn/form granularity, with zero loss of already committed durable state, deterministic resume/reconciliation, and read time travel.

That is feasible on these dependencies after adding the four durable loop closures and enforcing writer/package capability isolation.

It is not accurate to promise:

- an uncrashable process;
- complete in-process containment of OOME, native/JNI code, thread leaks or arbitrary blocking host calls;
- incremental storage for every changed serialized value;
- exactly-once external effects without provider/capability idempotency; or
- writable branching from every arbitrary transaction under the current batched commit model.

Today the system does **not** meet “basically crash-proof”: the four closure gaps allow ambiguous replay, arbitrary branch-from-t is missing, and Proximum repointing is not proven atomic with the primary head.

The three riskiest assumptions and cheapest falsifiers are:

1. **Every transaction/form boundary is branchable.**  
   Issue concurrent transactions, prove multiple tx IDs share one commit ID, then try `fork-database {:at t}` for each. This is expected to falsify the assumption (`$DH/src/datahike/writer.cljc:205-240`).

2. **A small mutation of a large immutable value is cheap.**  
   Store a representative 100 MiB nested value, mutate one leaf across versions, and measure bytes written/latency for whole blobs versus normalized datoms or a prototype DAG. Konserve’s current whole-value path predicts a full rewrite (`$K/src/konserve/impl/defaults.cljc:57-123`, `$K/src/konserve/impl/defaults.cljc:321-346`).

3. **Every fatal host event leaves a coherently resumable durable state.**  
   Fault-inject at each receipt/cursor boundary, kill the pod/package host with stack exhaustion and low-heap OOME, and inject failure between Proximum force and primary-head publication. Cold restart must yield exactly one claim, one consumed-input set, one reconciled attempt, and one unambiguous form cursor. The Proximum ordering makes the cross-store portion currently **NOT GROUNDED** (`$DH/src/datahike/versioning.cljc:339-401`).