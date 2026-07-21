---
type: research
status: completed
tags: [research, database, flow, architecture]
---

# Writer terminal-result transport audit — 2026-07-15

## Result

The writer already produces the complete semantic shutdown value. The missing
mechanism is a generation-bound application-result slot inside the process
containment result. Do not add a writer control socket, parse the log, or make
PID disappearance mean database release.

The smallest one-mechanism transport is:

1. `seon.dev.process` allocates one application-result path beside the current
   containment descriptor/result, includes its exact shutdown grace in the
   descriptor, and injects the path plus generation into the workload
   environment;
2. the JVM shutdown hook calls the existing `seon.db.server/stop!`, wraps its
   return in one closed namespaced EDN envelope, and atomically publishes that
   envelope only after `stop!` has returned;
3. the existing persistent Python containment owner treats those bytes as an
   opaque, bounded attachment to its own atomic JSON terminal result; and
4. Babashka validates the outer process generation/termination result, reads
   the attached EDN with `clojure.edn`, validates it against one portable
   schema, and applies the clean-or-force policy.

Process proof and application proof remain separate. A matching containment
result proves the exact process subtree is absent. Only a requested termination
with a matching, schema-valid writer envelope whose `stopped?` is true proves a
clean writer drain. Missing, oversized, unreadable, stale, malformed, thrown,
or `stopped? false` application data records no clean claim, but it does not
erase valid subtree-absence evidence. The operator may therefore replace the
writer through the forced/unproved path and let ordinary cold recovery decide
durable agent ownership.

Implementation can begin immediately after the containment owner commit is
reviewed and its focused real-process gate passes. It must not begin by editing
the current uncommitted `process.clj` or `detach.py`, because those two files
still define the transport being extended.

## Dependency ledger

| Dependency or mechanism | Selected identity | Source grounding | Constraint |
|---|---|---|---|
| Clojure | `org.clojure/clojure` `1.12.0` | `deps.edn`; JVM shutdown-hook use in `src/seon/db/server.clj` | The hook may publish only a complete value returned by `server/stop!`. EDN preserves keywords and UUID coordinates without another codec dependency. |
| Datahike | `org.replikativ/datahike` `417649383c65e13f15ea41d394fb1ed742477965` | exact `reference-code/datahike`; `writer.cljc:40-73`; `connector.cljc:438-510` | Release synchronously closes write admission, joins processing/commit threads and accepted out-of-band operations, closes secondaries, then releases Konserve. There is no internal deadline; a supervising grace may expire without turning absence into success. |
| Konserve | `org.replikativ/konserve` `df6818d43ea3363a808cd051c0d68917f1b987a9` | exact `reference-code/konserve`; `core.cljc:771-784` | Its release is already reached through Datahike. No second store close or result channel belongs in the operator. |
| Writer/server result | commits `272de2f3` and current `src/seon/db/writer.clj:1099-1117`, `server.clj:166-203` | focused gate: 19 tests/112 assertions recorded in the roadmap | `writer/stop!` closes UDS first, snapshots every registry entry, and returns database name, attachment, complete pre-release coordinate, released flag, and optional release error. `server/stop!` preserves it. Only `-main` still discards it after logging. |
| Portable database data | current `seon.db.coordinate` and `seon.db.protocol` | `src/seon/db/coordinate.cljc`; `src/seon/db/protocol.cljc` has an explicit `:bb` dependency branch | The cross-runtime terminal envelope schema should be registered in the existing portable protocol-data owner, not duplicated independently in JVM and operator namespaces. |
| Babashka | executable `1.12.212`; `bb.edn` paths include `script`, `src`, and `test` | `bb --version`; `bb.edn`; `script/seon/dev/process.clj` | Babashka can read EDN and load portable schema/coordinate namespaces without loading Datahike or the JVM server. It must not require `seon.db.server` merely to validate a result. |
| `babashka.process` | `0.6.25`, maintained source SHA `16a84e0af0da51b8c84e289970f6b7cc35b35d18` | exact `reference-code/babashka-process/src/babashka/process.cljc:156-174,420-451,540-560` | `destroy-tree` is a `ProcessHandle.descendants()` snapshot and shutdown hooks carry no semantic result. The retained Python owner remains the hard subtree authority. |
| Containment owner | current WIP `script/seon/dev/process.clj` and `detach.py` | generation/adoption/result paths, anchored group, atomic JSON, exact owner/anchor/workload identities | Extend this record and result. Do not introduce a second writer supervisor file tree or socket. |
| JDK | OpenJDK `26.0.1`; installed source archive identified in the containment audit | `Runtime.addShutdownHook`; `java.nio.file.Files.move` and same-directory atomic move | A same-directory temporary file, flushed before `ATOMIC_MOVE + REPLACE_EXISTING`, prevents a killed hook from publishing a partial final result. The exact installed JDK source is not mirrored under `reference-code/`; this audit relies only on standard JDK APIs already used by Seon. |
| Python | CPython `3.14.6` | current `detach.py`; exact installed source provenance recorded in the containment audit | Python must not interpret Clojure data. It records capture status, byte count, digest, and the opaque EDN string in the generation's one terminal JSON value. |

## Current seam and shortest falsifier

`seon.db.writer/stop!` already returns:

```clojure
{:seon.db.writer/stopped? true
 :seon.db.writer/release-results
 [{:seon.db.registry/database-name :default
   :seon.db.registry/attachment
   {:seon.db.coordinate/database-id #uuid "..."
    :seon.db.coordinate/branch :db}
   :seon.db.registry/coordinate
   {:seon.db.coordinate/database-id #uuid "..."
    :seon.db.coordinate/branch :db
    :seon.db.coordinate/commit-id #uuid "..."
    :seon.db.coordinate/t 536870990}
   :seon.db.registry/released? true}]}

```

`seon.db.server/stop!` projects the same release vector and false result when
any release is unproved. Its `-main` hook currently prints failure and returns
nothing to the parent. Conversely, the containment owner currently publishes
only `{generation, status "drained", anchor_exit -9}`. The shortest falsifier
is therefore source-local: a forced KILL after the 2.5-second grace produces
the same process terminal shape whether Datahike release completed, failed, or
never returned. A disappearing writer cannot graduate clean restart.

The current fixed 2.5-second `TERM_GRACE` is also insufficient as writer-clean
proof. Maintained Datahike deliberately waits for every accepted transaction,
both writer loops, accepted out-of-band operations, secondary indexes, and the
Konserve release, and it has no internal deadline. Two and a half seconds is a
hard-containment latency, not evidence that those joins can finish.

## Settled wire and schema

### One application envelope

Register the portable closed variants in `seon.db.protocol.cljc` so the JVM and
Babashka consume the same schema without loading the server implementation.
The exact names may follow the namespace's conventions; the required data is:

```clojure
;; Completed hook: `stop!` returned, even if a release was unproved.
{:seon.db.terminal/generation "6d..."
 :seon.db.terminal/process :seon.dev.process/writer
 :seon.db.terminal/completed? true
 :seon.db.terminal/stop-response
 {:seon.db.server/stopped? false
  :seon.db.server/release-results [...]}}

;; Hook caught before `stop!` returned a value.
{:seon.db.terminal/generation "6d..."
 :seon.db.terminal/process :seon.dev.process/writer
 :seon.db.terminal/completed? false
 :seon.db.terminal/stop-error "bounded exception text"}

```

Use an `:orn` or equivalent closed union so exactly one of `stop-response` and
`stop-error` is present. The generation is a canonical UUID string because it
originates in the operator environment. Coordinates inside the stop response
remain native typed EDN values. Do not stringify coordinates, flatten release
rows, convert qualified keys to bare JSON names, or add a `clean?` bit.

The existing writer/server stop-response schema should reference this one
portable shape rather than leave a copied operator approximation. The semantic
test is still `stopped?` plus every validated release row; the `completed?`
field distinguishes “the hook returned a failed response” from “the hook
threw before a response existed.”

### One containment terminal result

The Python JSON remains process transport. Extend its normalized Clojure form
with:

- exact generation;
- termination trigger: requested, workload-exit, adoption-timeout, or owner-
  abort;
- anchor exit and the exact admitted shutdown grace;
- application capture status: captured, missing, oversized, or read-error;
- for captured data, byte count, SHA-256, and the opaque EDN string.

The owner unlinks the unique application result before spawning the workload,
reads it only after the anchor is reaped, enforces a documented size ceiling,
hashes the exact bytes it embeds, then publishes this metadata and the process
result in one atomic JSON replacement. A 1 MiB ceiling is ample for local
release rows while bounding an accidental file; exceeding it is an explicit
unclean capture result, never truncation.

The operator normalizes external JSON keys immediately into fully namespaced
Clojure data. It checks the JSON byte count/digest before EDN parsing, uses
`clojure.edn/read-string` rather than the general Clojure reader, and validates
the result with the shared schema. JSON validity alone is not application
validity.

## Shutdown grace is generation/spec data

Move the grace out of Python's global constant and into
`process-spec-schema`, then copy it into the immutable containment descriptor
and terminal result. The owner and anchor receive that exact millisecond value
at launch; a later operator invocation never substitutes another policy for an
already-running generation.

Recommended initial bounds are:

| Process | Grace | Reason |
|---|---:|---|
| watcher | 2,500 ms | No semantic application result; only bounded watcher subtree drain. |
| pod | 5,000 ms | The HTTP lifecycle action must already have quiesced and released the runtime; containment is the final process inverse. |
| writer | 30,000 ms | Allows admitted UDS work, Datahike writer loops/out-of-band work, secondary close, and Konserve release to settle while remaining below the existing 300-second down lock. |

Thirty seconds is an explicit initial operator policy, not a claim about a
Datahike upper bound. A blocked release beyond it is force-stopped and remains
unproved. Record elapsed TERM-to-anchor-reap time in the terminal result so the
later performance/graduation run can tune this value from evidence. Never reuse
the readiness timeout as shutdown grace: startup and inverse latency are
different contracts.

The writer hook publishes only after `server/stop!` returns. If SIGKILL lands
while it is waiting or writing the temporary file, the final application path
is absent and the owner records `missing`; a temp file is never accepted. If
the atomic application result exists before the final anchored KILL, its typed
release result remains valid even if a lingering non-database thread required
hard process containment. Forced process disappearance by itself never
manufactures that file or changes `stopped?`.

## Clean, forced, and crash semantics

The operator may call a writer stop clean only when all of these hold:

1. the outer result matches the managed record's generation;
2. termination was requested through the generation-matched control command;
3. the anchored subtree result is valid and the exact owner is absent;
4. application capture is complete and its bytes match the recorded digest;
5. EDN reads and validates as the writer terminal envelope;
6. its generation and process id match the outer record; and
7. `:seon.db.server/stopped?` is true, which entails every typed release row is
   released.

Everything else is unclean, with two importantly different outcomes:

- **planned request, missing/malformed/failed application result:** subtree
  absence is still usable. Return the process result plus a typed unclean
  application reason, clear the absent generation, and allow forced/unproved
  replacement. Do not say the writer drained cleanly and do not authorize a
  destructive restore/promotion from this evidence;
- **workload exited before a matched request:** classify the generation as an
  unexpected crash even if its JVM hook happened to publish `stopped? true`.
  Drain the anchored subtree, replace only after absence proof, and run the
  existing cold recovery path. A graceful self-exit is not a planned restart.

An uncertain containment owner/anchor remains the stronger failure: retain its
record and refuse replacement because subtree absence itself is unproved.

`process/stop!` should return the validated outer terminal value instead of
discarding it. It may clear the exact absent generation after retaining that
value in the call stack. The CLI's one clean-or-force coordinator consumes the
returned pod and writer evidence. This keeps application validation from
blocking safe cleanup of a subtree already proved absent and avoids a retry
loop permanently wedged on the same malformed application file.

## Failure cuts

| Cut | Process result | Application result | Required classification |
|---|---|---|---|
| Before descriptor publication | No managed generation | None | Launch fails; owner drains its unpublished subtree. |
| Descriptor published, before managed-record adoption | Generation-bound abort result | None | Never ready; not a planned clean stop. |
| Managed record adopted, before writer reads environment | Valid process generation after drain | Missing | Unclean forced replacement. |
| Stale file from an earlier generation | Current outer result | Rejected generation | Unclean; unique path and pre-spawn unlink make this an injected corruption. |
| Operator sends another generation | No accepted drain | None | Retain current owner; mismatched command cannot authorize stop. |
| Workload exits before drain request | Unexpected process result | Any | Crash replacement and cold recovery, never planned clean. |
| Request accepted while writer UDS handler runs | Requested; anchor waits grace | Published only after handler join and release | Clean only when the complete successful value arrives. |
| Datahike release throws | Requested, subtree absent | Completed response with `stopped? false` and exact failure row | Unclean forced replacement; preserve returned evidence for diagnosis. |
| `server/stop!` throws before response | Requested, subtree absent | Failed terminal variant | Unclean forced replacement. |
| Hook exceeds 30-second writer grace | Requested, forced subtree absence | Missing final file | Unclean forced replacement; elapsed result proves timeout. |
| KILL during temporary EDN write | Requested, subtree absent | Missing final file | Unclean; never read a temp or partial file. |
| EDN final rename succeeds, then KILL | Requested, subtree absent | Complete valid result | Application result controls clean/failed classification; KILL alone adds no claim. |
| Python cannot read or result exceeds limit | Requested, subtree absent | Explicit capture failure | Unclean forced replacement. |
| Owner dies before final JSON publication | Containment uncertain | Irrelevant | Retain descriptor; refuse replacement. |
| Outer terminal JSON is stale/malformed | Containment uncertain | Irrelevant | Retain descriptor; refuse replacement. |
| Operator crashes after `process/stop!` clears record | Subtree already proved absent | Result lost from invocation | Later startup treats prior stop as unproved; cold recovery is idempotent. |
| Pod result lost but writer result succeeds | Both processes may be absent | Pod clean proof absent | Entire restart is not labeled clean; recovery reads settled facts and may no-op. |

## Exact implementation owners

Implement after the containment commit, in this order:

1. `src/seon/db/protocol.cljc` and its existing protocol/coordinate tests:
   register the one portable writer terminal and stop-result schema, referring
   to the existing complete-coordinate schema.
2. `src/seon/db/writer.clj` and `src/seon/db/server.clj`: make current
   stop-response registrations reference the portable schema; add the private
   atomic EDN publisher used only by `-main` when the operator-provided
   generation/path environment exists. Preserve the current in-process
   `stop!` return.
3. `script/seon/dev/process.clj` and `script/seon/dev/detach.py`: add shutdown
   grace and application path to the current descriptor, inject immutable
   environment values, classify requested versus unexpected termination,
   capture the bounded opaque result in the one terminal JSON, normalize and
   validate it, and return the terminal value from `process/stop!`.
4. `script/seon/dev/cli.clj`: consume this result in the one clean-or-force
   coordinator used by down, restart, rebuild reconciliation, and later reset.
   Branch pod-only restart consumes only pod proof and never stops its external
   writer.

Do not add a new executable, socket, result directory, writer registry, log
parser, or shutdown database fact.

## Focused proof

Add or extend these tests:

- `test/seon/db/server_test.clj`: a real `-main` subprocess receives a unique
  generation/path, exits under TERM, and publishes a schema-valid successful
  envelope; injected release failure publishes the complete false response;
  injected throw publishes the failed variant; a killed delayed hook leaves no
  final file.
- `test/seon/db/writer_integration_test.clj`: retain the existing exact
  pre-release attachment/coordinate and failure-row assertions; no new writer
  lifecycle mechanism is needed.
- `test/seon/dev/process_test.clj`: generation/path/grace descriptor round trip;
  requested versus workload-exit classification; captured digest validation;
  missing, oversized, malformed EDN, stale generation, false stop response,
  KILL-during-temp-write, and owner-result corruption; a writer fixture whose
  release completes after more than 2.5 seconds but before 30 seconds must
  graduate clean, while one beyond the bound must be absent but unclean.
- `test/seon/dev/cli_test.clj`: all public stop callers use one coordinator;
  missing/failed writer proof selects forced/unproved replacement; an uncertain
  containment result still blocks; pod-only branch restart never requests the
  external writer.
- `test/seon/dev/branch_test.clj`: branch close sends no destructive writer
  request until branch-pod absence is proved; its shared source writer remains
  untouched.

Then run the focused writer/server/process/CLI/branch gates. The source-frozen
default proof must show one successful pod quiesce coordinate, one requested
writer terminal result with all releases true, old subtree absence, and a
reopened writer/replica at the same attachment and an equal or verified
descendant coordinate. UUID order is never ancestry proof. Injected missing and
failed writer results must still permit forced crash replacement while producing
no clean-restart claim.

## Go/no-go

**Go immediately after containment integration**, provided its commit freezes
the generation/adoption/result descriptor, requested-versus-unexpected
termination can be represented, and the real process tests leave no subtree
alive. The application-result extension is narrow and uses no unresolved
Datahike behavior.

**No-go before that commit** for edits to `process.clj` or `detach.py`: their
current WIP changes the same schema, CLI arguments, owner loop, result JSON, and
failure cuts. The portable schema and JVM publisher can be prepared on paper,
but landing them early would leave an unused result path and encourage a second
temporary transport.
