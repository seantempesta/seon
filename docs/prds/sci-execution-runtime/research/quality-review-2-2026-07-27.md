---
type: research
status: complete
tags: [research, testing, runtime]
---

# Fresh-tree quality review 2 — 2026-07-27

## Scope and verdict

Reviewed the post-review-1 B0 entry, B1 store, revised N2 run transitions and
model suite, Datahike bridge fixes, Gemini hook review upgrade, dependency
evolution, and adopted Flow suite against every 2026-07-27 ruling in the plan.
Review 1's resolved takeover, caller-agent mismatch, terminal-recovery
comparison, bridge-facet, dependency, and Flow-discovery findings were treated
as inputs rather than re-argued.

The organization boundary is sound: fresh source and the default classpath do
not reach the quarry, no namespace is duplicated across `src/` and `src-old/`,
the selected Datahike/Konserve/Proximum closure is singular, and all 43 fresh
`deftest`s—including the 15-test adopted Flow suite—are discoverable by
`bin/test`. The full gate exits zero.

The runtime boundary is **not trustworthy yet**. A failed Datahike release
drops the only cross-process fence while the writer remains live; an expired
run holder can resurrect itself or mutate the run; and concurrent stops can
kill a newly started replacement. Two N2 relational/receipt invariants and the
hook backlog race also remain open despite a green gate. The issue authority's
derived index fails validation on notes from this same landing wave.

## Findings

### Blocker — failed Datahike release drops the only OS fence

**Location:** `src/seon/cluster/store.clj:299-315`.

**Failing scenario:** `d/release` was replaced with a deterministic exception.
`release-store!` propagated the exception but its `finally` invalidated the
flock. The old connection still satisfied `connection?`, and a second
`open-store!` succeeded. In this JVM Datahike returned the same live
connection; a foreign JVM sees no flock and can open the forbidden second
writer.

**Violated rule:** exactly one live write connection per physical store; an
unproved release must fail closed. The leak-vs-drop choice is backwards:
retaining the fence is safe, dropping it is data-loss exposure. Tracked in
[[../../../../seon/issues/store-release-failure-drops-the-flock]].

### Blocker — held transitions ignore lease expiry

**Location:** `src/seon/cluster/run.cljc:230-248` and
`src/seon/cluster/run.cljc:342-455`.

**Failing scenario:** a run was expired according to `run/expired?`.
`heartbeat-tx` from the old process and epoch nevertheless committed a later
lease, after which `run/claimed?` returned true. The same `held-run` fence
admits plan, release, and close after expiry because none of their requests
carries `now`.

The oracle duplicates the omission at
`test/seon/cluster/run_test.clj:269-281`: it treats holder+epoch as sufficient,
so the generated property agrees with the defect.

**Violated rule:** custody is process + epoch + live lease, and every decision
belongs inside the transaction over the current database value. Tracked in
[[../../../../seon/issues/run-held-transitions-ignore-lease-expiry]].

### Blocker — a delayed stop can kill a replacement instance

**Location:** `src/seon/cluster.clj:336-355`.

**Failing scenario:** two controlled `stop!` calls both accepted one old
instance. The first completed; a replacement started on port 57236; then the
second resumed its name-addressed `stop-server` and advertisement deletion.
The replacement remained registered, but its advertisement read nil and its
server had been stopped.

**Violated rule:** `(pid, start-instant)`/generation identity fences lifecycle
effects; concurrent start/stop must not let an old generation act on a new
one. Tracked in
[[../../../../seon/issues/cluster-stop-can-kill-a-replacement-instance]].

### Blocker — close still does not fence the agent pointer

**Location:** `src/seon/cluster/run.cljc:419-428`.

**Failing scenario:** after opening and claiming a run, the owning agent's
pointer was removed. `close-tx` still committed `closed-at`; `cond->` merely
omitted the retraction instead of refusing the broken relation. The revised
model cannot generate an absent or foreign pointer independently.

The N2 revision correctly removed the two caller-controlled agent identities.
It did not satisfy the old issue's remaining acceptance condition: the
transition must prove and remove the exact ref it settles.

**Violated rule:** an entity is its attributes and connections; a relational
transition must fence the exact connection in the same transaction. Updated in
[[../../../../seon/issues/run-close-does-not-fence-the-agent-pointer]].

### Blocker — terminal receipts can return to running

**Location:** `src/seon/cluster/run.cljc:102-117`,
`test/seon/cluster/run_test.clj:241-246`,
`test/seon/cluster/run_test.clj:391-399`, and
`test/seon/cluster/run_test.clj:423-449`.

**Failing scenario:** one receipt identity was transacted as `:done`, then
upserted as `:running`. Both writes committed and the durable status became
`:running`. The model emits receipt commands but never compares receipt facts
with its own receipt map, so the green property cannot observe any receipt
transition disagreement.

Recovery's complete terminal-entity comparison is now honest and is not
reopened. The missing owner is receipt start/settlement itself.

**Violated rule:** a form has at most one terminal receipt ever; state-machine
properties independently observe durable facts. Updated in
[[../../../../seon/issues/run-acceptance-properties-miss-takeover-and-terminal-preservation]].

### Friction — Gemini review backlog race remains unfixed

**Location:** `bin/seon-hook:339-381` and `bin/seon-hook:570-640`.

**Failing scenario:** the review upgrade still uses unlocked
read-modify-write over one pending file and clears by path, not generation.
Concurrent hooks can lose distinct edits, and a same-file re-edit during the
model call is cleared with the older snapshot. The clear also removes pending
paths that failed to read into the actual review batch, and second-resolution
artifact names can collide.

The synchronous call path is bounded and catches ordinary exceptions at its
outer boundary. The totality claim does not repair the shared-state race, and
no review-path test is discoverable by the fresh gate; the surviving hook CLI
tests remain under `test-old/` and do not cover Gemini review.

**Violated rule:** absence is never health; multiple lanes sharing one checkout
are normal; a proof invisible to the owning runner is not coverage. Updated in
[[../../../../seon/issues/gemini-review-pending-state-loses-concurrent-edits]].

### Cleanup — fresh-rung issue notes violate their own lifecycle

**Location:** `docs/seon/issues/README.md` and six in-scope top-level issue
notes.

**Failing scenario:** `bin/issues-index --check` reports the index stale.
Bridge notes use `active`/`high`/`medium`; resolved bridge, flock, Flow, and run
property notes remain at the open root with `resolved` or unsupported `closed`
status. The check also reports older unrelated violations, which this review
did not absorb.

**Violated rule:** one issue lifecycle, one severity vocabulary, and a derived
index that validates. Tracked in
[[../../../../seon/issues/fresh-rung-issue-notes-break-the-derived-index]].

## Organization verdict

- **Fresh/quarry split:** passes. No fresh require points into `src-old` or
  `test-old`; `clojure -Spath` contains neither by default.
- **Namespace uniqueness:** passes. The intersection of source namespace names
  across `src/` and `src-old/` is empty.
- **Dependency closure:** passes. `clojure -Stree` selects the maintained local
  Datahike plus one Konserve SHA (`b5c99bc`) and one Proximum SHA
  (`9846d3e`).
- **Test discovery:** passes structurally. `bin/test` discovers 43 tests across
  six namespaces: boot 7, run 6, store 8, Flow loop 4, adopted Flow 15, and
  Datahike bridge 3. The full gate exits zero.
- **Flow adoption:** passes. The previously invisible 15-test contract suite is
  now under `test/seon/flow_test.clj`, and flow-monitor is test-only.
- **Bridge fixes:** pass their claimed boundary. Alias chains preserve
  collection cardinality, index, and no-history facets; the public
  derive/install/transact/read path passes. The already-filed mixed-union codec
  gap is not duplicated here.
- **Hook ownership:** fails test honesty. Review state lives in the active
  `bin/seon-hook`, while its CLI tests are quarry-only and the review queue has
  no recurring test.
- **Issue authority:** fails. In-scope issue notes violate the localized
  lifecycle, and `bin/issues-index --check` cannot validate the derived index.

## House-bar census

- No new name-based trust or placement classification was introduced in the
  reviewed fresh changes. The three-name core-process rule is an explicit
  owner-approved follow-up and was not re-litigated.
- B0's instance registry and B1's flock table are sanctioned process-local
  ownership, not stored durable projections. The defects are transition
  linearizability and unsafe release ordering, not the existence of the atoms.
- The `:any` parameters on N2 `*-call` functions are raw Datahike database
  values, the genuine third-party boundary exception. No reviewed domain
  attribute adds `:any`, `:maybe`, stored nil, or an entity kind field.
- N2 transaction functions throw to abort a core Datahike transaction, as
  required. No agent-facing surface was added here.
- B1 reads missing `:branches` as failed genesis and repairs; it does not treat
  absence as health. The hook does the opposite when it clears unreadable
  pending paths.
- The new runtime literals are path conventions or explicit external/test
  backstops. No unexplained production polling deadline was introduced.

## Coverage gaps

- B0 has no controlled concurrent same-cluster start, concurrent stop, or
  stop/restart generation test. Its live advertisement test exercises the
  platform's millisecond conversion, but no focused tolerance-boundary case.
- B1 has no injected Datahike release failure, concurrent release/reopen, or
  allocation/resource observation around repeated genesis probes. Konserve's
  maintained file backend declares release a no-op, so the last item is a
  coverage question rather than a defect found here.
- N2 cannot generate an independently broken agent pointer, an expired holder
  operation rejected by the oracle, a lease earlier than `now`, a nonexistent
  agent open, or any receipt fact disagreement.
- The fresh gate has no hook review test; old hook CLI tests cover edit
  normalization and lint feedback only.

## Proof record

- `bin/test` — exit 0; 43 discovered `deftest`s.
- Controlled B0 two-stop/replacement probe —
  `{:advertisement-after-race nil, :replacement-port 57236}`.
- Injected B1 release-failure probe — old connection live, old lock invalid,
  reopen succeeded.
- N2 expiry/receipt probe — expired before heartbeat, heartbeat committed,
  claimed afterward; terminal-to-running committed.
- N2 pointer probe — pointer absent before close, close committed,
  `closed-at` present.
- Default classpath, cross-tree namespace intersection, dependency tree, and
  fresh test inventory inspected directly.
- `bin/issues-index --check` — failed on a stale index plus in-scope and older
  lifecycle/severity violations.
