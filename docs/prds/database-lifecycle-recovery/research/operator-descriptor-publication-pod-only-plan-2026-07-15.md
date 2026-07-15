---
type: research
status: active
tags: [research, database, pod, flow, architecture]
---

# Operator descriptor publication and pod-only ownership plan

## Decision

The next unit-1 slice should publish the settled `seon.launch` descriptor as
immutable pod process input and derive a pod-only owned process map without
adding branch create/delete CLI. It first makes the real coordinate and
protocol owners Babashka-loadable by reader-conditionally excluding only their
Datahike- and Hasch-dependent functions. Their portable schemas and pure data
constructors stay colocated; there is no parallel schema namespace. The
operator then uses the exact shared constructor and does not reproduce the
descriptor shape.

The smallest implementation boundary is pure derivation and consumption:

1. make `seon.launch` load in Babashka without Datahike or Hasch;
2. derive the ordinary descriptor in `seon.dev.config` and accept an explicit
   already-validated branch descriptor at the same configuration boundary;
3. publish that descriptor through the pod spec's immutable environment and
   include it in the spec identity digest;
4. have the pod read and validate that exact launch value before database,
   blob, or runtime work; and
5. derive a pod-only owned spec whose source watcher/writer are declared
   external dependencies, never members of its owned start/stop set.

This stops before lifecycle mutation. The following slice will use the typed
Transit UDS protocol to create/adopt a native branch, atomically retain the
returned descriptor, prove external dependency readiness, and start the
pod-only spec under interruption-safe unwind.

## Dependency ledger

| Dependency or mechanism | Selected version or SHA | Exact source read | Existing Seon owner |
|---|---|---|---|
| Babashka | `1.12.212` | operator runtime plus `reference-code/babashka-process` | `bin/seon` and `seon.dev.cli` |
| `babashka.process` | `16a84e0af0da51b8c84e289970f6b7cc35b35d18` | `src/babashka/process.cljc:96-171,367-507,678-710` | `seon.dev.process` detached process identity, readiness, and drain |
| Transit CLJ | `1.0.333`, tag object `12f50e4391208d36f910a39dd947cefabf77dc52` | `reference-code/transit-clj`, tag `v1.0.333`, `src/cognitect/transit.clj:139-171,290-323` | `seon.db.transport.uds` length-framed typed maps; Babashka already loads `cognitect.transit` |
| Datahike | `417649383c65e13f15ea41d394fb1ed742477965` | `reference-code/datahike` | runtime coordinate resolution and writer branch authority; must not enter the Babashka launch constructor classpath |
| Konserve | `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve` | shared physical database/blob bases; branch pod never owns a writer |
| Atomic operator state | current branch | `script/seon/dev/state.clj` | `write-edn!` uses temp plus atomic replace; retained lifecycle state remains file data |
| Settled launch composition | `9edf26f1` | `src/seon/launch.cljc`, `src/seon/client/schema.cljc`, `src/my/blob/schema.cljc` | one closed descriptor, one client capability, one blob storage view |

## Source observations

`seon.dev.process/specs` currently always returns watcher, writer, and pod. The
pod declares both other process ids as owned dependencies, while
`target-processes` drives ordinary reverse-order shutdown. A branch target
cannot reuse that map: selecting only the pod leaves unresolved dependency ids,
and selecting the whole map grants ownership over source processes.

`process/ensure!` already reconciles exact argv, managed environment digest,
artifact digest, retained process identity, and readiness. Publishing a launch
descriptor in the immutable pod environment therefore naturally participates
in process identity and forces replacement when the selected descriptor
changes. No second PID registry or launch-state atom is needed.

`seon.dev.state/write-edn!` is the later retained-state publication primitive.
The current slice does not need another descriptor file merely to pass launch
data to one child: the child environment is fixed at spawn and recorded through
the existing managed-environment digest. The later mutating transition will
atomically retain its descriptor before pod publication so interruption can
retry exact cleanup.

Transit `1.0.333` constructs stream readers/writers and preserves keywords,
symbols, UUIDs, and maps through the default handlers. Babashka already loads
`cognitect.transit` without a new `bb.edn` dependency. The existing JVM UDS
transport remains the framing oracle; the next typed lifecycle client should
reuse its four-byte bounded frame and Transit JSON map, not shell out or invent
JSON coercion.

## REPL probes

The first Babashka probe falsified direct contract reuse:

```clojure
(require '[seon.launch])
;; FileNotFoundException: datahike/api.clj[c] is not on the Babashka classpath
```

The cause is structural, not a missing Babashka dependency: `seon.launch`
requires `seon.db.coordinate`, whose `resolved` and `at` functions require
Datahike, and `seon.db.protocol` requires Hasch only for
`logical-transaction-hash`. Adding the full database runtime to Babashka would
collapse the process boundary. The repair keeps both public namespace owners
intact and reader-conditionally excludes only those runtime-dependent pieces
from Babashka.

The corresponding Transit probe succeeded:

```clojure
(require '[cognitect.transit])
;; :transit-loaded
```

After that portability repair, the exact `require` and default-descriptor probe
succeeded without Datahike or Hasch. Focused coordinate/launch CLJS proof
passed 7 tests/37 assertions with zero failures, errors, or warnings at
`tmp/test-cljs-20260715-032254-17355.log`.

## Smallest path-bounded code slice

Owned production paths:

- `src/seon/db/coordinate.cljc`;
- `src/seon/db/protocol.cljc`;
- `src/seon/launch.cljc`;
- `script/seon/dev/config.clj`;
- `script/seon/dev/process.clj`;
- `src/seon/db/replica.cljs` and `src/seon/client.cljs` only for validated
  process-descriptor selection.

Focused tests belong in `test/seon/launch_test.cljs`,
`test/seon/dev/config_test.clj`, `test/seon/dev/process_test.clj`,
`test/seon/db/replica_test.cljs`, and `test/seon/client_runtime_test.cljs`.
Do not edit `seon.dev.cli`, `seon.dev.mcp`, writer registry/protocol operations,
or branch create/delete in this slice.

The implemented process data distinguishes owned and external dependencies.
An ordinary descriptor produces the unchanged owned graph
`watcher -> writer -> pod`. A non-autonomous branch descriptor produces one
owned pod spec with source watcher/writer dependency identities carried as
external data. Each external dependency names its generic source owner process
directory and exact artifact digest; the launch descriptor separately names
the writer owner's process directory. Readiness reads the real live
source-owner record and proves the watcher's current client bytes, rather than
manufacturing target-owned records or trusting a stale completion log. The
validated configuration selector rejects a flavor/build mismatch, and
`ensure!` rejects an unavailable external owner before it can publish a branch
pod record.

The descriptor is published with CLJ `pr-str` as immutable pod environment
data. The CLJS pod reads it with `cljs.reader/read-string` and validates the
closed descriptor before database, blob, or runtime initialization. The
focused configuration/process proof passes 20 tests/83 assertions with zero
failures or errors.
The source-frozen CLJS checkpoint still needs to execute the exact hard-coded
CLJ-print/CLJS-read UUID-coordinate regression together with the other changed
CLJS lanes.

## Falsifiable acceptance

- `bb -e "(require '[seon.launch])"` succeeds without Datahike or Hasch in
  `bb.edn`.
- Default and ACME configuration derive schema-valid descriptors identical to
  the CLJS defaults.
- Descriptor encoding round-trips UUIDs, keywords, coordinates, capability,
  writer sockets, process paths, and blob view without coercion.
- A changed descriptor changes the pod's managed environment digest and cannot
  hot-switch an attached process.
- Ordinary owned specs and start/stop order remain behaviorally compatible;
  descriptor publication intentionally changes the pod environment identity.
- Branch derivation returns exactly one owned pod; watcher and writer occur
  only as external dependencies and can never enter its stop set.
- The pod validates and claims the published descriptor before ping, ensure,
  local connect, blob access, or autonomous runtime work.
- Focused operator and CLJS selectors pass. The integrated live checkpoint
  remains later because this slice deliberately exposes no branch mutation CLI.

## Next ordered lifecycle slice

The next unit must retain exact lifecycle intent before it mutates the writer.
The immutable launch descriptor is necessary runtime input but is not a
complete lifecycle record. Its coordinate is the branch creation cut; after
intentional target writes it is not the current head required to fence release
and deletion. It also preserves the source writer process/cluster identity but,
after `::launch/database` selects the target route, it does not preserve the
source logical database route. The operator must never infer that route from
the writer cluster name.

The closed retained record therefore owns the exact source descriptor, exact
typed create request, target-private process/blob coordinates, desired
lifecycle state, and—after creation—the validated response and derived launch
descriptor. Publish the deterministic intent atomically with
`seon.dev.state/write-edn!` before sending create. If the operator exits after
Datahike creates the branch but before response publication, retry sends the
same request and the writer adopts only an exact matching durable branch.
Publish the validated returned descriptor atomically before pod publication.

The exact open transition is:

1. Read the existing source artifact manifest and prove its current client
   bytes plus the real source watcher/writer owners. A branch open reuses these
   resources; it never builds artifacts or stops an external process.
2. Ask the writer for the source route's complete current head. Build the
   closed create request from the explicitly selected complete source
   coordinate and that current expected source head, then retain the intent.
3. Send the existing typed create request. Validate target route, attachment,
   coordinate, backend, database path, and the exclusive `created?`/`adopted?`
   result before composing and selecting the descriptor.
4. Retain response plus descriptor, derive the one owned pod spec, reprove the
   external dependencies, and publish the pod. Any failure unwinds only effects
   whose ownership and inverse are proved.

The exact close transition is:

1. Retain close intent, stop the target pod, and prove its process and endpoint
   absent. An uncertain process inverse forbids database deletion.
2. Ensure the exact target route/attachment after pod absence to read its
   complete current head. Never reuse the descriptor's creation cut as this
   fence.
3. Release that exact route, attachment, and current head. Delete the exact
   branch through the source route retained in the create intent, using the
   same target head, and require typed proof of roster removal.
4. Only after every inverse succeeds remove target-private port/process/blob
   state. Retain the lifecycle record and evidence at the first unproved
   inverse so retry never guesses identity.

### Transport prerequisite

The JVM `seon.db.transport.uds` namespace remains the one byte/framing owner.
The pre-edit Babashka load probe failed before use:

```clojure
(require '[seon.db.transport.uds])
;; Unable to resolve classname:
;; java.nio.channels.AsynchronousCloseException
```

Babashka already loads Transit CLJ `1.0.333` and supports the synchronous Unix
`SocketChannel` used by readiness probes. The cause was narrower than the
transport: SCI could load the JDK class reflectively but could not resolve
`java.nio.channels.AsynchronousCloseException` as a catch class while analyzing
the namespace. The existing owner now resolves that class once through the
same `Class/forName` compatibility pattern used by the checked-in nREPL Unix
socket source and classifies it inside the existing broad resource-boundary
catch. Codec, four-byte frame, sockets, request server, and publisher remain one
mechanism.

The post-edit Babashka proof loads the owner and runs the complete focused
transport namespace at 9 tests/28 assertions. Its bounded local UDS fixture
sends the real closed create-branch request and response through `connect!` and
`call!`, preserving keyword discriminators and complete UUID coordinates. The
retained JVM transport plus writer-integration gate passes 16 tests/93
assertions. No `bb.edn` dependency, JSON coercion, shell call, admin socket, or
second envelope was added.

### Interruption prerequisite

[[../../../seon/issues/operator-interruption-can-orphan-managed-process]] is a
graduation blocker for mutation plus pod launch. The one supervisor transition
needs an invocation-local ordered ledger of only processes newly started by
that invocation and must drain them in reverse dependency order on interrupt.
Pre-existing converged processes remain alive. A failed process inverse retains
the managed record and prevents destructive branch cleanup.

### Owned implementation paths and proof

Anticipated owners are:

- `src/seon/db/transport/uds.clj` for the one portable synchronous typed call;
- a focused `script/seon/dev/branch.clj` for closed retained lifecycle data and
  open/close transitions;
- `script/seon/dev/process.clj` for newly-started evidence and exact absence;
- `script/seon/dev/cli.clj` only after the internal transition is proved; and
- existing transport/writer tests plus focused
  `test/seon/dev/branch_test.clj` and process/CLI interruption tests.

The implementation proof must cover Transit UUID/keyword preservation, a
retained-intent crash before and after writer create, exact create versus adopt,
artifact/source-owner rejection before mutation, pod-only ownership, target
head advancement before close, release/delete fencing with that current head,
failure at every inverse retaining retry data, and SIGINT at every process
start/readiness boundary without a newly started child surviving under PID 1.
Only then should CLI syntax, external-owner status/MCP exposure, and live
default-plus-ACME branch experiments widen the boundary.
