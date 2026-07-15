---
type: issue
status: open
severity: blocker
tags: [issue, database, pod, flow]
---

# Keep restore publication closed through completion

## Problem

Restore requires one completion transaction after successful program
reconstruction but before any executable boundary opens. Ordinary publication
must still admit immediately, while the restore-aware cold owner must retain
the verified projection under closed admission through completion read-back.

Adding a restore-only registry, instrumentation pass, or `:restoring` phase
would duplicate the existing publication mechanism rather than close this seam.

## Evidence

`src/seon/runtime/admission.cljs` now factors preparation from exact-generation
admission, and its ordinary wrapper composes both synchronously.
`src/seon/client.cljs` calls that wrapper during cold start and only afterward
resumes hosts and starts runtime surfaces. The exact restore-aware order and
predecessor inputs are grounded in
`docs/prds/database-lifecycle-recovery/research/restore-blob-and-cold-reconstruction-contract-2026-07-15.md`.

## Owner

The one `seon.runtime.admission` committed-program publication transition,
composed by the existing `seon.client/start-runtime!` cold entry.

## Current state

The admission-owner seam is implemented in place. `prepare-committed!` builds,
reconciles, activates, and retains one exact verified projection fingerprint
while the existing state remains `:publishing`. `admit-prepared!` opens only
when its preparation result names that retained fingerprint; a stale or
different generation remains closed. Ordinary publication immediately composes
those same two functions through `publish-committed!`.

The focused `prepared-publication-stays-closed-through-an-injected-completion`
regression places an injected completion-verification effect between those
halves and rejects a mismatched generation before admitting the exact prepared
result.

The durable fact owner is also implemented independently in
`seon.db.restore`. It colocates the compact completion identity and all thirteen
architecture payload attributes, then records or proves one exact completion
through `seon.db`. An equal identity retry returns its completion transaction
coordinate without a write while that transaction remains the current head;
any required-value or optional-digest conflict fails closed. The operation
neither reads nor changes admission.

The later-head retry boundary is now implemented through the one database
protocol. A closed main-lineage request gives the authoritative writer a frozen
head and transaction id. It walks retained immutable commit maps, proves that
head remains an ancestor, excludes branch/force metadata commits that repeat a
parent's `t`, and requires exactly one ordinary transaction origin. The pod's
existing replica RPC owner carries the request; `seon.db` preserves typed
writer failures as a structured error value. An equal completion retry after a
later transaction now returns the original completion coordinate without a
write or a shadow commit attribute. Exact grounding is in
[[../../prds/database-lifecycle-recovery/research/restore-completion-transaction-coordinate-2026-07-15]].

The retained lifecycle observation now consumes that same resolver directly.
Its closed response exposes
`:seon.db.protocol/restore-completion-coordinates` as a map from every durable
completion id to its exact original
`{database-id, branch, commit-id, t}` coordinate. The earlier transaction-only
map was removed: a force metadata commit can repeat the completion transaction
number at a different commit id, so `t` alone cannot admit terminal cleanup.
The resolver moved intact into the registry lifecycle owner, and the standalone
writer RPC delegates to it; there is still one commit-graph walker.

The restore-aware cold caller is implemented under focused proof.
`with-restore-startup` forces the existing launch capability to
nonautonomous. Cold startup attaches exact forced head `F`, replays and prepares
the one committed projection under the existing `:publishing` state, then
submits the frozen completion claim to one whole-head-fenced `record!` operation
`F→C`. The database allocates the completion's 12-character id; the caller
retains the returned full completion and exact `C`, rather than treating the
UUID operator intent as completion identity. It does not admit that projection,
host or resume agents, seed or recover facts, record replay faults, open the
public web surface, synchronize provider/brand data, install the ticker, or
react to Shadow build admission.

The existing `/_seon/ready` route is the only observable preparation door. Its
restore projection returns 200 only when the expected completion entity is
exact, its identity datom was asserted by the current main-branch head `C`, and
admission remains `:publishing`. Its body is the shared closed
`:seon.db.restore/readiness-response`: exact generated completion, exact `C`,
`:seon.db.restore/ready? true`, and `:seon.db.restore/executable? false`. Any
later head `D` returns 503. A fresh ordinary restart, after the external
coordinator removes the retained intent, owns normal admission. No restore
phase, registry, or second status path is introduced.

## Acceptance

- The existing `:publishing` state can retain one verified, activated projection
  while an owning cold transition commits restore completion.
- Completion read-back leaves admission exactly `:publishing` and executable
  work closed.
- Ordinary boot and hot reload still use the same factored publication owner;
  there is no second registry, restore instrumentation path, force-open, or new
  admission status.
- Completion failure leaves admission closed and process retry reconstructs
  disposable projection state from committed facts.
- A crash after completion observes the same completion, does not repeat
  guarded force or overlays, and remains nonautonomous until intent cleanup and
  a fresh ordinary restart.
- An exact retry after a later branch transaction resolves and returns the
  original completion coordinate without writing or inventing stored commit
  metadata.

Focused writer proof passes four tests/12 assertions, including a real
repeated-`t` force commit, abandoned non-ancestor head, missing transaction,
wrong attachment, and branch-head alias rejection. Replay plus resolver proof
passes six tests/33 assertions. The request and response production Transit
gate passes ten tests/32 assertions, and the focused CLJS restore gate passes
six tests/34 assertions for the pod wrapper, original-coordinate result,
structured error kind, and zero-write retry.

The lifecycle extension is additionally proven by the registry, standalone
resolver, and writer-integration selector at 28 tests/190 assertions. Its real
Datahike falsifier publishes a completion, force-commits the same `t` at a
different commit id, reopens the database, and requires lifecycle observation
to return the original completion commit coordinate rather than the force head.

The caller boundary passes the focused launch selector at 7 tests/49
assertions, client-runtime selector at 30 tests/202 assertions, and web selector
at 13 tests/75 assertions. These cover UUID intent evidence, the exact
`::completion-claim` request, generated completion identity, attached refresh
reuse, unchanged ordinary readiness, byte/semantic-equivalent closed restore
readiness, and rejection after a later head `D`. The issue remains open only
until the external coordinator consumes that endpoint through a complete
restore lifecycle proof.

## Fresh schema integration fixed

The full CLJS gate exposed a separate cold-bootstrap ordering defect. Requiring
`seon.db.restore` registers the generated identity
`:seon.db.restore/id` globally, so `seon.client/generator-policy-facts`
correctly includes its generator policy. Fresh fixture databases still install
only `seon.client/agent-bootstrap-attrs`; because that vector omits the restore
completion attributes, policy installation rejects the identity as
`:seon.db.id.error/invalid-generator-policy` before runtime startup.

The canonical fix belongs at the existing initial-schema owner:
`seon.client/agent-bootstrap-attrs` feeds `pod-full-schema`, which
`install-runtime-schema!` commits before generator policies. Add the complete
restore-completion attribute set there so schema precedes policy and later
completion publication. Do not weaken generator-policy validation, filter the
globally registered candidate opportunistically, or patch individual fixtures.

The fix is now implemented without duplicating that closure.
`seon.db.restore/completion-attrs` publicly owns the identity plus all thirteen
architecture attributes, and `seon.client/agent-bootstrap-attrs` derives its
final vector by adding that one collection. A fresh isolated connection proves
every completion attribute is installed, the compact generator policy is
readable through `seon.db.id/generator-policies`, and the native
`:db/ident :seon.db.restore/id` transaction precedes its policy transaction.
The focused `seon.db.restore-test` gate passes 7 tests/37 assertions with zero
failures, errors, or compile warnings at
`tmp/test-cljs-20260715-110003-54902.log`.
