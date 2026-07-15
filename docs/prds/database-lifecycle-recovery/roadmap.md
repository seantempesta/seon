---
type: prd
status: active
tags: [prd, database, flow]
---

# Database lifecycle recovery roadmap

## Outcome

One database-native lifecycle reconstructs runtime projections after fresh
boot, config-free reopen, clean restart, unexpected crash, historical reads,
fork, restore, and undo without replaying arbitrary eval effects or consulting
a second authority.

## Current state

The JVM writer is the sole durable Datahike owner; the pod uses one immutable
replica and typed protocol. Durable receipts, bounded replay/live overlap,
config reconciliation, numeric as-of reads, and crash fencing exist. Complete
Malli projection building also exists. Receipt-native schema is now derived
from that registry, installed through Datahike's creation transaction, and
validated before an existing connection is published. One fail-closed runtime
admission now reconstructs and verifies the committed generation before agent,
eval, web-command, schedule, wake, run-loop, or ticker work proceeds.
Deterministic publication-failure and config-free live restart evidence remain
before this transition graduates.

The maintained Datahike SHA contains same-store branch/delete, commit/branch
root reads, historical-secondary-index correction, awaited connection release,
and guarded/read-back-verified force. Seon's writer exposes typed native
create/release/delete and has removed the physical-copy fork. Its registry,
feed, replica, launch descriptor, public operator, and CLJ/CLJS MCP discovery
now own branch-qualified runtimes through the source writer without a second
registry or writer. Quiesced clean restart, crash replacement, restore/undo,
promotion, and ordered multi-form process-failure proof remain unimplemented.

The branch-qualified replica/operator audit is complete at
[[research/branch-qualified-replica-operator-launch-audit-2026-07-15]]. It
defines one closed launch descriptor separating runtime identity, target
database route/attachment/path, source writer ownership, process coordinates,
artifact flavor/build, non-autonomous capability, and blob storage view. The
smallest reviewed implementation order is descriptor derivation and exact
replica consumption first, typed pod-only operator lifecycle second, then the
restart/crash matrix. `bin/seon` and `bin/acme` remain thin wrappers over the
one Babashka supervisor; a branch reuses its source flavor's watcher and writer.

The first non-mutating implementation slice is complete. `seon.launch` derives
the same closed descriptor for default and ACME,
composes the existing client launch capability, retains the immutable branch
creation coordinate, rejects source/target path containment, and identifies
the source writer owner. `seon.db.replica` consumes its exact route,
attachment, backend, database path, and sockets for ensure, local config,
remote writes, replay, and feed selection. Reopen requires a complete current
head on the same attachment but does not compare it to the creation cut because
intentional branch writes advance that head. `seon.client` claims the blob view
once before runtime work and fails a conflicting or invalid claim closed.
The four-namespace proof passed 53 tests/330 assertions before the claim helper
moved from the agent-facing blob namespace to its correct private process owner.
The smallest post-move client/blob selector passes 29 tests/197 assertions with
zero failures or errors at `tmp/test-cljs-20260715-030704-86367.log`; the next
integrated source-frozen checkpoint must rerun the complete descriptor/replica/
client/blob set.

Operator descriptor publication and pod-only process ownership are now at a
source-frozen checkpoint. The Babashka operator loads the real portable
coordinate/protocol/launch owners, derives the ordinary descriptor from the
existing target configuration, and publishes its exact CLJ value in the pod's
immutable environment. The pod reads and validates that closed value before
database, blob, or runtime effects. A non-autonomous descriptor owns only its
pod: watcher and writer remain external dependencies identified by their real
source owner process directory, live process records, readiness, and exact
artifact digests; watcher admission also proves the current client bytes.
Ordinary watcher/writer/pod derivation and start order remain
unchanged. Focused configuration/process proof passes 20 tests/83 assertions
with zero failures or errors; the coordinated CLJS checkpoint still must run
the new exact CLJ-print/CLJS-read UUID regression and the full overlapping
descriptor/replica/client/blob selector.

The real synchronous Transit UDS call boundary is now Babashka-callable without
a second transport. `seon.db.transport.uds` dynamically resolves the one catch
class SCI could not analyze while retaining its existing codec, four-byte
frame, sockets, server, and publisher. Babashka runs the complete focused
transport namespace at 9 tests/28 assertions, including an exact typed
create-branch call with UUID coordinates over a bounded local Unix socket. The
retained JVM transport/writer-integration gate passes 16 tests/93 assertions.

The retained-create retry prerequisite is also complete at `e7bd160c`.
`create-branch!` adopts an exact published route at its freshly resolved head,
including after legitimate target writes, and adopts an unpublished exact
durable branch at the immutable retained fork cut even if the source later
advances. Attachment, physical database, durable roster, and fork-coordinate
fences remain closed; the expected-source-head fence runs immediately before
new mutation only. Focused writer proof passes 37 tests/259 assertions.

The retained typed operator lifecycle began at `74bfa7e2`.
One finite, atomically published record owns the source descriptor, exact create
request, target-private coordinates, response, launch descriptor, desired
state, phase, and inverse evidence. Every read semantically re-derives those
relationships before effects. Open proves source owners, creates or adopts the
exact branch, and starts only its pod. Close stops and proves that pod absent,
reads the exact current target head, releases and deletes with that fence
through the retained source logical route, and cleans only descriptor-derived
private paths. The launch descriptor retains the immutable creation cut.

The bounded real Transit UDS fixture covers response-loss retry, exact adopt,
created-coordinate mismatch, target advancement before close, newly-started
publication-failure cleanup, and preservation of a pre-existing converged pod.
The original branch/process/config/CLI selector passed 30 tests/130 assertions;
the public command, status, restart, and MCP boundary is recorded below.

Ordinary startup interruption ownership is complete. The one supervisor now
installs an invocation-scoped shutdown hook, serializes shutdown admission with
detached spawn plus managed-record publication, and drains only newly started
groups in reverse order. Direct JDK process-group signals remain usable after
Babashka's future executor has terminated. Real OS-SIGINT proof covers the
pre-spawn publication race and watcher, writer, and pod readiness cuts; a
converged writer survives while invocation-owned watcher/pod groups unwind.
The branch/CLI/process selector passes 31 tests/153 assertions. Evidence is in
[[research/ordinary-startup-sigint-ownership-2026-07-15]].

Retained branch interruption now consumes that same inverse. The one startup
ownership monitor retains ordered resource inverses as well as process ids,
making exact native create publication and detached pod publication indivisible
with respect to shutdown. Reverse unwind stops and proves the target pod absent
before the existing close owner reads the current target head and releases and
deletes. Only a branch actually created by this invocation is claimed; adoption
and a converged pod remain resumable. A failed pod inverse retains both exact
identities and admits no destructive writer request. Real OS-SIGINT proof covers
pre-spawn, delayed spawn publication, readiness, converged reuse, and cleanup
failure. The branch-only gate passes 4 tests/61 assertions. Evidence is in
[[research/retained-branch-sigint-ownership-2026-07-15]]. The exact combined
branch/CLI/process checkpoint passes 34 tests/190 assertions.

Public branch ownership and its first live checkpoint are complete. Public
request derivation, inventory, status, pod-only restart, external-owner health,
and the four CLI commands landed at `50974ca4`; `60797eaa` holds the branch lock
across stop/reopen; and `eab641c1` resolves branch-qualified CLJ MCP through the
pod's immutable writer advertisement. Watcher-owned client publication at
`d6eaffe2` removed the impossible one-shot/watch byte comparison, and
descriptor-owned replica routing at `104586d4` preserves the logical writer
route when a non-streaming Datahike connection dereferences to its durable
configuration. `bb6f10f7` prepares descriptor-derived readiness parents in the
one process owner; its branch/process/CLI gate passes 40 tests/244 assertions.

The source-frozen default proof created `default-proof-1044` at database
`54b5b7e7-51fb-3220-b079-81a81914d86f`, branch
`:seon.branch/default-proof-1044`, commit
`6a57647c-2f82-5603-b575-600b722c89ee`, and `t=536870988`. CLJS advertised the
branch runtime and default writer; branch-qualified CLJ MCP resolved the same
attachment. A branch-only claim advanced the target to commit
`6a576618-c919-5945-aeab-6197aec099ed`, `t=536870990`, while default remained at
its original head with no claim. Pod-only restart changed PID `18469` to
`21145`; watcher PID `7266` and writer PID `7323` remained fixed, and both MCP
runtimes read the claim after restart. Close removed lifecycle, process, port,
blob, and log paths, the registry route, and the durable Datahike branch.
Branch-qualified MCP then failed closed while default remained ready and
unchanged. ACME was observed only in MCP inventory and was not operated.

The pod-crash containment prerequisite is implemented at `f59a8b75`, following
[[research/dead-leader-process-subtree-containment-2026-07-15]]. Every new
generation has one persistent owner outside the execution group and one live
session-leading anchor that alone signals its pinned group. Publication and
adoption retain exact owner, anchor, and workload start identities; a terminal
result requires anchor-originated escalation, final group absence, and the
matching generation. Missing owner, anchor, or result evidence remains
`containment-uncertain`; replacement and destructive branch inverse stay
closed. Focused process/branch/CLI proof passes 48 tests/282 assertions,
including publication/adoption cuts, TERM delivery, individual-anchor death,
dead-workload descendants, and one exact live-legacy retirement path. The
retained default processes have not yet been migrated through that path.

The clean-shutdown primitives are integrated but do not yet form an operator
guarantee. `b9c39ac1` adds one atomic
`:available` -> `:quiescing` admission transition, closes an already admitted
run as `:quiesced` only at a turn recurrence boundary, and derives both
pointer-owned open runs and every running turn bracket from one database
value. `3c2671a1` exposes that drain through a loopback-peer-only lifecycle
action, retains retryable cleanup capability, and keeps HTTP alive long enough
to flush its closed EDN result; focused proof passes 32 tests/205 assertions.
`272de2f3` makes `seon.db.server/stop!` return whether shutdown completed plus
every exact registry attachment, final coordinate, release outcome, and
release error after the UDS transport has closed and joined; focused writer
proof passes 19 tests/112 assertions. The exact remaining seam is now ordered:
publish the writer's generation-bound application result, extract the pod
response schema into one portable `.cljc` owner, extend containment with
per-process immutable grace plus requested-versus-unexpected trigger evidence,
and add the one clean-or-force coordinator specified by
[[research/clean-or-force-operator-coordinator-audit-2026-07-15]]. The
coordinator uses pod -> writer -> watcher order and one absolute deadline equal
to the configured turn bound plus 120 seconds. Missing application evidence
after proven subtree absence is forced recovery; uncertain containment retains
the record and blocks replacement.

The implementation card after that clean-or-force boundary is now reconciled at
[[research/post-clean-restart-restore-undo-multi-form-card-2026-07-15]]. Restore
and undo use one fsync-durable immutable intent, stop every pod consuming the
selected writer, materialize every reachable target blob into the main archive,
run guarded `force-branch!` through a no-listener invocation of the existing
writer artifact, then reconstruct and transact completion before admission
opens. Initially only a retained branch's exact commit-head coordinate is
branchable: a Datahike `as-of` interior cut is a read view, not a concrete store,
and must fail explicitly rather than round to its containing commit. The Unit-1
three-form crash proof records the committed prefix and absent suffix without
fabrication; general durable per-form position remains a later
agentic-refinement contract.

The Slice-3 writer-admin boundary is partially implemented from the grounding at
[[research/restore-writer-admin-transition-audit-2026-07-15]]. The smallest
root-move owner is one no-listener invocation of the existing writer artifact:
an invocation-local observational registry open proves the exact main,
prepared-target, undo, and full-roster fences; guarded `force-branch!` moves
main at most once; fresh coordinate, parent, EAVT, declared-secondary Merkle
root, roster, and release evidence is required for one closed portable result. Retry
derives old, exact desired, or divergent state from storage, so response loss
never repeats force. The final Slice-2 intent supplies the exact backend
locator, full expected roster, writer/protocol binding, and one retained
intent-specific atomic result path. Publication replaces that same path on
retry; absence remains unknown until a later storage observation proves the
durable head. The scaffold fails closed on a newly discovered dependency
blocker: selected Datahike force flushes the prepared Proximum instance while
labeling its key map as destination branch `:db`; a file-backed one-vector
fixture reopens with equal EAVT and KNN results but unequal secondary roots.
Focused admin proof passes 9 tests and 53 assertions; the combined writer-admin
gate passes 22 tests and 131 assertions, including the real dependency
falsifier and injected connect, operation, first/second release, post-force
read-back, invalid-result, and result-loss failures. No public
restore command may invoke this boundary until Proximum has a guarded,
idempotent existing-destination force/replace primitive and Datahike consumes
it through its one `force-branch!` path. Ordinary Proximum `branch!` rejects the
already-existing live `:db`, and key-map relabeling cannot change the source
branch recorded in a committed snapshot; the rejected Datahike-only prototype
and exact dependency contract are retained at
[[research/datahike-force-secondary-falsifier-2026-07-15]]. That operator must
independently hash the exact writer artifact before launch; the intent's plan
digest only commits the expected artifact claim.
The concrete missing source boundary is tracked in
[[../../seon/issues/restore-writer-admin-transition-is-unimplemented]].

Slice 4 retained-blob materialization is complete in the one existing
`my.blob` owner. Its closed non-agent-facing boundary fences the exact retained
target coordinate, derives the canonical distinct/sorted `B(T)` through
`seon.db` only, and requires SHA-256 of the UTF-8 `(pr-str B(T))` bytes to equal
the frozen intent digest before any filesystem effect. An absent blob
attribute means the empty set. Lookup is overlay-first and stops at the first
existing path, so corrupt target bytes never fall through to a valid lower
base. Missing and corrupt main destinations are published only from
independently verified source bytes through the directory-durable publisher;
valid destinations are re-synced, every destination is read and hashed again,
and retry reports converged counts without a database write. Focused proof
passes 21 tests and 148 assertions with a warning-free 510-file CLJS compile,
including target-coordinate, frozen-digest, missing-source, corrupt-overlay,
orphan, repair, publication-failure, final-readback, and retry falsifiers.

The independent Slice-5 completion-fact owner is complete in
`seon.db.restore`. Its closed schema is exactly the compact identity plus the
thirteen architecture payload attributes, with only the core/config overlay
digests optional. After schemas are preinstalled, one root/boot-provenanced,
whole-head-fenced `seon.db/transact!` records the fact and proves the exact
entity plus identity-datom transaction on read-back. An equal retry performs no
write; a same-id value conflict fails closed. Focused fresh-Datahike proof
passes 5 tests and 31 assertions. Commit `b2461d64` closes the later-head retry
gap through one canonical writer-backed transaction-coordinate resolver: it
walks raw immutable main-branch commit ancestry, skips force commits that
repeat a parent's transaction id, requires exactly one original ordinary
commit, and returns that original coordinate without another transaction.
Branch aliases, abandoned or unavailable history, attachment mismatch,
absence, and ambiguity fail closed. Resolver and replay proof passes 6 tests/33
assertions, Transit proof passes 10/32, and the CLJS restore retry passes 6/34
with zero warnings. The ordered integration slice must complete the Proximum-
then-Datahike secondary force contract, then compose forced-main result,
blob proof, cold reconstruction, completion, exact admission, readiness, and
intent deletion. It must not add shadow completion metadata or another restore
state machine.

The current-source cold-composition reconciliation is retained at
[[research/restore-cold-composition-reconciliation-2026-07-15]]. Commit
`c2b4013d` completes the first in-place runtime unit: one optional closed,
digest-bound
restore startup value travels through the existing launch descriptor and its
exact `pr-str` process publication. It contains only the authoritative frozen
intent identity/digests/consumer map, exact admin success, and exact blob
success. Relational validation derives agreement on intent, plan, reachable
blobs, selected target, the required pod generation member, and the descriptor's
actual forced-main coordinate; ordinary descriptor bytes remain unchanged.
Focused proof passes launch 7 tests/47 assertions, blob 21/148, admin 9/53, and
operator restore 9/57. The second in-place unit is now implemented in the
existing cold entry: a fresh main attach keeps writes closed, validates the
exact startup generation/head and preinstalled completion schema, performs
preserve-only recovery and replay, calls `prepare-committed!`, records and
reads back completion under root/boot provenance, and admits that exact
generation. Any replay or completion failure stays closed; ordinary cold start
retains its existing composition. Autonomy and readiness start last; intent
deletion remains the external operator's durable inverse. No restore-only boot
path, callback, status, or ambient intent-file reread was added. The
transaction-coordinate resolver is complete at `b2461d64`, and the fresh
schema owner now installs the complete restore closure before generator-policy
publication. The isolated dependency repair now passes its complete focused
gate: Proximum `fb6572c` provides guarded generation publication and Datahike
`069a807e` integrates it through the one force path, including legacy-shape
migration, response-loss retry, stale destination rejection, and cold
source-branch non-clobber (108 tests/570 assertions across all three Datahike
index backends). The exact changes are not yet selected dependencies: Proximum
must first be forward-ported onto upstream `v0.1.26`, given cold Git-dependency
preparation, published, and consumed by the final public Datahike commit. That
artifact cutover remains the hard predecessor of the destructive integrated
gate; therefore no public restore command or destructive default/ACME proof is
claimed yet. Focused cold-composition proof
passes 29 tests/195 assertions, including post-completion crash and idempotent
retry before exact admission, with zero compile warnings at
`tmp/test-cljs-20260715-112026-67588.log`; adjacent restore, admission, and
launch owners pass 7/37, 14/85, and 7/47 respectively.

The exact inverse contract is grounded at
[[research/retained-head-restore-undo-contract-audit-2026-07-15]]. Undo is not a
reverse-datom operation: it retains the actual current main head as a redo
point, selects the prior completion's exact retained undo head, and reuses the
same force/reconstruction/completion transition. Current intent derivation can
still label any structurally valid retained target as `undo`; the future
operator selector must bind it to one prior completion's database id, undo
branch, source commit, and source transaction before intent publication. That
gap is tracked at
[[../../seon/issues/undo-target-is-not-bound-to-retained-completion]].

The first operator-only restore slice now owns one closed immutable intent and
derives its next command from the current main coordinate, an explicitly
observed forced-main coordinate, main ancestry, reserved branch heads, and
completion-fact ids; it persists no phase, status, or retry counter. Target
source blobs and the main destination archive are frozen through distinct
pinned launch descriptors so overlay-first lookup cannot be masked by the
destination; those descriptors also bind the physical backend/path, database
identity, writer owner, artifact flavor, and exact main/target heads. The
intent additionally freezes the post-preparation branch roster, protocol
version, writer artifact digest, and process generations. Its canonical
SHA-256 plan digest derives from every frozen and deterministic field rather
than accepting an arbitrary digest-shaped string. This v1 intent explicitly
preserves both core and config populations; its closed schema rejects ambient
or requested overlays until the later overlay slice can freeze their exact
bytes and digests. Intent and completion use the same compact-string identity
shape. The pure contract lives in the writer-visible `seon.dev.restore` source
owner; script-only `seon.dev.restore-state` owns fsync publication. The bounded
admin invocation derives one intent-specific atomic-EDN result destination,
and passes the canonical fsync-published intent path rather than EDN in process
arguments. Consumer generations are nonempty and deliberately exclude the
writer: exact generation-bound writer absence remains coordinator evidence
rather than semantic intent. `seon.dev.state` synchronizes the temporary file
before atomic rename and the parent directory afterward, and conflicting
retained intent bytes fail closed. Public CLI parsing remains deliberately
deferred:
the eventual surface is branch-oriented (`cluster restore <retained-branch>`
and undo selecting a completed restore or retained undo target), but it cannot
land until writer/status exposes the exact current branch head. The operator
must never substitute the retained branch's immutable creation coordinate or
the unpinned default launch descriptor. Slice 3 owns recovery when the guarded
force committed but its result was lost: until writer/status can recognize and
publish that exact forced-main coordinate, a changed main without exact
evidence diagnoses divergence rather than inferring promotion from ancestry.

The exact dependency/source audit, live probes, transition matrix, and ordered
implementation slices are in
[[research/database-lifecycle-source-audit-2026-07-14]]. Implementation began
after the completed runtime-reliability graduation checkpoint.

The first coordinate kernel and head handshake are implemented.
`seon.db.coordinate` owns one closed
`{database-id, branch, commit-id, t}` shape plus its stable attachment
projection. The writer's ensure response returns that point from the connected
Datahike value; the pod config consumes the writer-owned database/branch
attachment; replica diagnostics expose the canonical local head instead of a
replica-specific public progress map. Focused proof passes ten JVM tests/51
assertions and 20 CLJS tests/104 assertions. After a public rebuild/restart,
CLJ and CLJS MCP both reported database `54b5b7e7-51fb-3220-b079-81a81914d86f`,
branch `:db`, commit `6a56c20e-eb61-5cc2-b20f-90d25090eab5`, and `t`
`536870932`.

Slice 1 now carries complete coordinates through transaction responses/events,
durable-receipt recovery, frozen replay pages/cursors, replica progress, and
own-write correlation. One immutable replay commit contains every page cut;
later writes cannot move its watermark, and the writer proves an initial cursor
commit is an ancestor before replay. Focused proof passes the complete JVM gate
(55 tests/329 assertions), the replica gate (17/93), and the complete CLJS gate
(1,311/6,195). After a public rebuild/restart, the writer and replica both
reported database `54b5b7e7-51fb-3220-b079-81a81914d86f`, branch `:db`, commit
`6a56c8da-68c9-5c20-b4f4-99b6fc150056`, and `t` `536870935`; feed attachment
replayed zero transactions and became live.

The implementation audit corrected an earlier selector assumption: a Datahike
commit can contain multiple temporal cuts. `commit-id` therefore pins the
immutable containing value while `t` selects a cut inside it. `as-of` is only a
read filter; no code searches ancestry for an exact-`t` commit.

Whole-head writes now carry `expected-coordinate` through `seon.db`, the remote
writer, protocol hashing, rejection errors, and the serialized JVM comparison.
The local Datahike writer receives only the extracted `t` at its third-party
boundary. An equal `t` and commit on a different branch is rejected without a
write. The breaking protocol shape increments the durable receipt version to
2. After the second public rebuild/restart, the JVM writer, public
`seon.db/head-coordinate`, and replica status all reported database
`54b5b7e7-51fb-3220-b079-81a81914d86f`, branch `:db`, commit
`6a56cd91-5f6c-58cc-be3c-eb732741fb5b`, and `t` `536870938`; both runtimes
reported protocol version 2.

Exact historical reads now have one honest asynchronous resolver.
`seon.db/at-coordinate` loads the coordinate's immutable containing commit by
UUID through maintained Datahike, proves it belongs to the currently attached
database branch, validates the selected t inside that container, and returns
the `as-of` view. Partial coordinates, wrong attachments, missing commits, and
out-of-range cuts return structured error values. The focused CLJS proof passes
2 tests/11 assertions. After the combined public rebuild/restart, live CLJS resolved
database `54b5b7e7-51fb-3220-b079-81a81914d86f`, branch `:db`, commit
`6a56d546-9284-5854-beb5-e0902938c200`, t `536870953`; the returned view
reported the same t and queried root successfully. Changing only the branch to
`:experiment` returned a `:user-input` error value. This is the dependency for
migrating turn, error, autocomplete, and frozen-web consumers without retaining
a bare-t path.

Turn capture and autocomplete export now use the same complete coordinate.
The turn open transaction writes database id, branch, containing commit id,
and t as an all-or-none group; old partial rows remain honestly
unreconstructable. Debug projection returns those facts and reports a numeric
t delta only inside one proven containing commit. Autocomplete export resolves
each point through `seon.db/at-coordinate` and emits the complete coordinate in
JSONL metadata. The focused gate passed 11 tests/73 assertions. After a public
rebuild/restart, live turn `ep2np287dio2` stored database
`54b5b7e7-51fb-3220-b079-81a81914d86f`, branch `:db`, commit
`6a56dd4d-4110-5bc4-8c4d-6235b75796bc`, and t `536870956`; resolving that
point returned t `536870956` and excluded the turn's later creation datom.

Error capture and reproduction now use the same full point. The injected
database seam returns one canonical coordinate; `seon.error/record!` projects
its four facts together, and `seon.agent.debug/repro` asynchronously resolves
the retained containing commit and cut. Old/partial rows return a typed
non-reconstructable value. The unsafe t-only writable-fork hint is omitted
until native coordinate-aware branch creation replaces physical copying. The
focused gate passed 17 tests/116 assertions. After a public rebuild/restart,
live error eid `3097` stored database
`54b5b7e7-51fb-3220-b079-81a81914d86f`, branch `:db`, commit
`6a56e16e-47c6-5d2e-82c5-907822251e3a`, and t `536870963`; repro resolved t
`536870963` and proved that the later error datom was absent from that value.

The downstream coordinate cut is complete. Historical web selectors are
all-or-none, resolve the retained containing commit, key frozen subscriptions
by the full point, and echo it in the SSE response. Public transaction and
reconcile success envelopes also return the complete point, while the hot
config-view cache keys plain decoded data by the point and retains no database
value. Focused web proof passes 36 tests/180 assertions, the combined
turn/error/autocomplete/web gate passes 64/369, state/config/envelope passes
48/235, and replica remains green at 17/93. Registry and native branch/restore
lifecycle are the next Slice 1 boundary. After a full rebuild, a converged live
reconcile returned exactly default head
`54b5b7e7-51fb-3220-b079-81a81914d86f`/`:db`/
`6a56e5fa-0caa-5574-b579-ba8be7a2ae85`/`536870971`; the config cache held
that coordinate plus the decoded map and no database value.

The receipt schema bypass is removed. `request-id` declares its identity
semantics in the canonical Malli form; the writer derives all five native
Datahike declarations from the registry snapshot. A fresh database receives
them through Datahike `:initial-tx`, while a reopened database must already
match before the registry publishes its connection. The raw declaration vector
and receipt-specific seed transaction are deleted. Datahike source and live
probes established why genesis is required here: ordinary entity data may use
schema declared in the same transaction, but transaction metadata is validated
against the schema that existed before that transaction. Receipts are
transaction metadata. The complete JVM gate passes 57 tests/337 assertions;
the relevant CLJS schema/replica gate passes 24/140. After a full restart, the
writer and pod agreed on default head
`54b5b7e7-51fb-3220-b079-81a81914d86f`/`:db`/
`6a56e85d-cd67-5c5a-a2cb-5f1aeb6ef905`/`536870974`, and live schema read-back
showed the expected string, UUID, long, string, and ref signatures with
`request-id` as a unique identity. Exact wrapper preparation now rejects
incomplete live/schema arity coverage before mutation. The one publication
transition closes admission, reconstructs the complete committed projection,
verifies wrappers, retries once from a newly frozen database value, and either
publishes that generation or leaves readiness unavailable. Agent and web
boundaries consume the same admission state. The combined publication boundary
gate passes 57 tests/361 assertions. Unit failure injection proves one retry,
one owning fault, and deterministic fail-closed behavior.

The agent-side admission cut is implemented against the one runtime gate.
Messages refuse before identity allocation or durable write; public spawn,
delegate, and resume refuse before minting or hosting; wake, run-loop,
re-drive, ticker, and schedule execution check before their owning work and at
asynchronous continuation boundaries. Refusals reuse typed domain envelopes
and never record another core fault. Wait, complete, pause, terminate, and
unhost remain available for diagnosis and drain. Cold boot alone continues to
call the non-agent-facing `create!`/`mint!` primitives before committed program
publication; there is no public bypass. Focused proof passes admission 8
tests/46 assertions, lifecycle 11/75, loop 18/92, and message 14/66 with zero
failures or errors. Config-free live restart rebuilt every source artifact and
reopened writer and pod at
`54b5b7e7-51fb-3220-b079-81a81914d86f`/`:db`/
`6a570614-19ab-5c2b-855e-231d990ed4fc`/`536870983`. Admission and the active
projection agreed on fingerprint `-833049123` (1,575 schemas and 819 function
contracts). A live MCP transition made `/_seon/ready` return 503 while closed,
reconciled 814 wrappers with no coverage gaps, and restored 200 on the same
generation. Root, `/data`, gzip SSE, and browser-console checks were clean.

The JVM writer-drain prerequisite is implemented in place. The UDS request
server admits each decoded request under its existing lifecycle lock, rejects
later admission once close begins, preserves the complete response for work
already admitted, and joins every accept/connection thread before returning.
Registry release now trusts maintained Datahike's awaited shutdown result:
success removes the entry, while failure retains the exact database identity
and error and cannot be reclassified as success inside that process. Deletion
stops at an unproved release, and writer/server stop project all failures as
`stopped? false`; the JVM shutdown hook emits the same result. A pre-edit
executable probe had shown the old false success and discarded identity. The
focused transport/registry/writer/server gate passes 17 tests/87 assertions,
and the complete writer checkpoint passes 62/360. Clean agent-turn quiescence
and operator coordination remain the next lifecycle boundary.

The clean planned-restart boundary is refreshed against current source at
[[research/clean-planned-restart-quiescence-refresh-2026-07-15]]. The old
audit's UDS, registry, and writer-release gaps are closed; the dependency-ready
work is now one `:quiescing` extension of runtime admission, turn-boundary
`:quiesced` run close, database-derived drain, loopback-only pod lifecycle
action, remote-writer drain, and exact coordinate response. One newly isolated
cross-process blocker remains: writer/server stop results are typed in-process,
but the operator cannot consume their release failures or final coordinate.
That proof must reuse the generation-matched managed-process terminal result
from the containment slice; process disappearance and log text are not clean
writer-drain evidence.

The branch-qualified backend/registry attachment boundary is implemented in
place. Backend configuration accepts one explicit `{database-id, branch}`
attachment while preserving the deterministic main-branch default. The one
writer registry retains that stable attachment and live resource, derives the
current coordinate from `d/db`, and enforces a logical-name-to-attachment
bijection plus agreement on physical id/backend/path. Main `:db` may create;
non-main branches are open-only and must exist in Datahike's durable branch
roster before connect. The registry validates the actual connected attachment
before initialization and publication. Ensure requests carry the attachment
through the existing Transit protocol, and subsequent routing resolves the
same attachment and fresh coordinate. A pre-edit executable probe proved the
guard is necessary: after `delete-branch!`, Datahike removed the branch from
`d/branches` but a raw connect to its stale head key still succeeded. Focused
backend/registry/transport/writer proof passes 24 tests/121 assertions, and the
complete writer checkpoint passes 68/388. Native branch create/delete,
branch-local feed/replica attachment, operator/runtime launch, forensic pods,
blob-view injection, promotion, and restore remain later slices.

The implementation-ready reconciliation for the next boundary is
[[research/native-branch-create-delete-implementation-plan-2026-07-14]]. Typed
JVM create/release/delete can now land against the existing registry and source
connection monitor. Commit `3649c6b1` completed its observational-open
prerequisite in the one existing initializer: every open receives its exact
attachment and main/branch intent; a branch validates inherited protocol schema
and every declared secondary instance, installs only its process-local
listener, and never runs the writing database initializer. The registry compares
the complete coordinate around initialization and releases without publication
if it changes. The pre-edit probe advanced a branch from commit
`6a570735-a43b-512a-894f-3561ece6d4ba` at `t=536870912` to
`6a570735-9d96-52a7-b0b7-0f2e9b925364` at `t=536870913`; the regression now
rejects that write. Focused registry proof passes 12 tests/67 assertions, the
writer/UDS fixture restores a real file-backed Proximum branch at an unchanged
coordinate in 6/51. Review then exposed a failed-open cleanup hole: release
errors were swallowed for an unpublished connection. Commit `1a46d3c5` retains
that exact resource in the same registry as `cleanup-required`, excludes it
from connection resolution, and preserves initialization plus release evidence
without pretending cleanup or route admission succeeded.

Commit `f34b7bda` completes the typed JVM-native branch lifecycle. Closed
Transit create, release, and delete requests now pass through the one UDS
writer protocol. Creation fences the exact source attachment and head, resolves
the selected immutable commit, rejects a cut that Datahike cannot branch
without advancing, creates or exactly adopts the durable branch, opens it
observationally, and publishes only after complete-coordinate read-back.
Release fences the target attachment and head. Delete proves release, the
durable roster entry, and exact branch head before removal, then proves roster
absence. Any failed release or failed-open cleanup retains the exact connection
and error facts and is unroutable; `cleanup-required` is derived from those
canonical facts instead of stored as parallel state. The superseded physical-
copy fork implementation and tests are deleted. Exact primary-datom equality
uses a bounded-memory lockstep EAVT comparison that stops at the first
mismatch. The focused registry, routing, UDS, and writer gate passes 31
tests/194 assertions, and the complete writer checkpoint passes 76/456.
Branch-qualified feed/replica launch, non-autonomous
pod/operator ownership, forensic runtime, blob-view injection, promotion,
restart/crash, restore, and undo remain ordered after this writer-local cut.

The pod-local prerequisite for branch-qualified launch is now implemented.
One retained closed capability selects ordinary autonomous launch or a
non-autonomous attachment that reconstructs and publishes the committed
program plus read surfaces without boot/config/recovery/genesis/hosting or
other autonomous writes. Replay diagnostics are console-only in that mode and
real broken-namespace proof leaves the database coordinate unchanged while a
later namespace loads. Hot reload preserves the capability. One serialized,
retryable inverse closes web/SSE, ticker and hosted agents, replica feed,
runtime admission plus active wrappers, then awaits maintained Datahike
release before clearing ambient ownership. The operator and replica protocol
still need branch-qualified selection; this slice deliberately adds no
branch-pod command or second supervisor.
The retained combined lifecycle/admission/instrumentation checkpoint compiles
506 files with zero warnings and passes 38 tests/316 assertions; the focused
client lifecycle proof contributes 17/122.

The first branch-local blob prerequisite is implemented independently of
native branch attachment. `my.blob` now consumes one validated process-local
storage view with one writable directory and ordered read-only bases. Writes
publish a unique temporary file through fsync plus atomic rename; reads search
overlay-to-base, recompute SHA-256, and refuse corrupt bytes instead of hiding
them through fallback. The five public blob functions and the database
projection are unchanged. Focused proof passes 10 tests/65 assertions; the
combined blob/turn/retry/loop/autocomplete gate compiles with zero warnings and
passes 43/245. A live default-cluster probe read source bytes through an empty
overlay without copying them, then placed corrupt bytes in the overlay and
received a false integrity envelope naming the actual digest. Supplying the
storage view from a branch launch descriptor, overlay release, promotion
materialization, and retention remain later lifecycle slices.

## Research evidence

- [[research/database-lifecycle-source-audit-2026-07-14]] — current dependency
  ledger, live probes, transition matrix, and ordered implementation slices.
- [[research/native-branch-registry-protocol-audit-2026-07-14]] — exact native
  branch attachment, registry, protocol, and deletion cutover.
- [[research/native-branch-create-delete-implementation-plan-2026-07-14]] —
  current-HEAD reconciliation, non-writing branch-open prerequisite, exact JVM
  implementation slice, failure matrix, tests, and live acceptance proof.
- [[research/branch-local-blobs-forensic-runtime-audit-2026-07-14]] — blob
  overlays, integrity, non-autonomous runtime, and promotion materialization.
- [[research/non-autonomous-runtime-launch-reconciliation-2026-07-15]] — exact
  pod launch capability, replay write suppression, hot-reload preservation,
  ordered teardown, and the remaining branch-qualified operator boundary.
- [[research/branch-qualified-replica-operator-launch-audit-2026-07-15]] — one
  launch descriptor, pod-only/shared-writer ownership, exact replica/feed/blob
  consumption, MCP writer-owner routing, deletion order, restart matrix, and
  the smallest path-bounded implementation slice.
- [[research/quiesced-restart-restore-undo-audit-2026-07-14]] — planned drain,
  unexpected recovery, immutable restore intent, promotion, and undo.
- [[research/clean-planned-restart-quiescence-refresh-2026-07-15]] — current
  planned-restart-only admission, turn drain, pod action, writer proof gap,
  failure matrix, tests, and live default gate.
- [[research/post-clean-restart-restore-undo-multi-form-card-2026-07-15]] —
  post-restart restore/undo contract, exact branchability limit, blob
  materialization, crash matrix, and bounded ordered multi-form failure proof.
- [[research/restore-writer-admin-transition-audit-2026-07-15]] — selected
  dependency grounding, no-listener writer-admin boundary, exact force/read-back
  fences, closed result, idempotent retry, Slice-2 field boundary, and failure
  matrix.
- [[research/post-commit-program-admission-audit-2026-07-14]] — exact
  publication failure paths, runtime admission gates, partial Malli mutation,
  committed-generation reconstruction, readiness, and ordered proof.
- [[research/config-schema-runtime-restoration-2026-07-12]],
  [[research/malli-runtime-schema-authority-audit-2026-07-13]], and
  [[research/db-protocol-cut-implementation-audit-2026-07-13]] — historical
  schema, reconstruction, writer/protocol, and deletion evidence.
- [[research/datahike-as-of-fork-and-restore-2026-07-12]],
  [[research/time-travel-api-implementation-audit-2026-07-12]], and
  [[research/database-runtime-responsiveness-audit-2026-07-13]] — historical
  branch, restore, coordinate, and responsiveness evidence.
- [[research/human-readable-word-ids-datahike-and-tokenization-2026-07-12]],
  [[research/local-allocation-writer-config-audit-2026-07-12]], and
  [[research/provenance-users-processes-and-ids-2026-07-12]] — identity,
  allocation, and transaction-provenance evidence.

## Required transition matrix

- fresh boot and converged explicit config;
- existing database reopen with no config and no write;
- failed schema/program publication reconstructed from committed facts;
- clean quiesced restart versus unexpected interrupted-run recovery;
- one `{database-id, branch, commit-id, t}` coordinate through reads, receipts,
  feeds, turns, errors, caches, and bookmarks;
- bounded as-of reads, writable same-database branches, restore, and undo;
- branch-local blob semantics and stale-writer/cursor rejection; and
- ordered multi-form execution that records every real result and fabricates
  none after process failure.

## Graduation

The complete transition matrix passes focused writer/pod tests plus destructive
default-cluster REPL, datom, restart, crash, replay, and read-back proof. Runtime
reconstruction uses committed facts and maintained Datahike primitives only;
there is no hidden manifest/runtime state, arbitrary eval replay, compatibility
path, duplicate registry, or Seon-specific physical history copy.
