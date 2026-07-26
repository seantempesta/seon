---
type: reference
status: active
tags: [prd, agent, architecture]
---

# Sci execution-runtime chunk runbook

Read order for ANY implementer: **`roadmap.md` → "THE ONE ORDERED LEDGER"
FIRST**, and take the earliest row whose `state` is `open`. That section is
the **only** ordering in this chunk (owner ruling O17, 2026-07-26). Then the
research doc(s) the row cites, then the SOURCE it names. Do not re-research
settled designs; execute them.

**Sequence from nowhere else.** Seven orderings once existed across six files
in five naming schemes — U/B/C-series here, P-series in `unified-plan`, a build
order in `design.md`, plus §3.5 "The order" and §4 "Waves" in
`research/implementation-plan-2026-07-25.md` — which is why "follow the plan"
had no referent. Each of those files now carries a SUPERSEDED banner and is
evidence only. `design.md` is still the architecture and nothing contradicts it,
but it does not sequence work.

**A row not re-verified since the last cut is a hypothesis, not work.** Every
row carries a `verified` date; re-grep its evidence before you start, because a
deletion elsewhere may already have discharged it — one cut discharged five
rows on 2026-07-26. A deviation needs evidence and lands in the PRD with your
commit (see U2's receipt deviation as the model: it found an existing
owner and deleted spec instead of building it — that is the bar).

## Ground truth pointers (verified 2026-07-20)

- The execution-protocol contract inventory lives in `src/seon/host.clj`'s
  ns docstring: 4 parent messages (startup/invoke/cancel/shutdown), 4
  child messages (ready/result/error/stopped), protocol-version 3, one
  active invocation per session, sentinel invocation-ids, bounded results.
  It is the conformance baseline for BOTH transports (Bun IPC to children,
  transit-UDS to the host). Parity means shape-for-shape.
- U4 replaced the recording seams: host eval batches commit a `:running`
  receipt (managed `:seon.eval/id` over the protocol's
  `::generated-candidates`; `seon.db.id/candidate-manifest`) BEFORE each
  form runs, terminalize with the frozen row + the program tee
  (`seon.host.record` pure builders mirror the child tee's DATA), return
  real `:seon.eval/ids`, eval in the request's starting ns, and replay
  home-ns corpus defs on context fork. Remaining seams: authored
  source-digest invocation; render stays pod-served BY DESIGN; no host
  run-fence CAS/print capture/preflight (roadmap U4 honest limits).
- The wrapper registry (`seon.host.context`) is the ONLY capability
  provisioning path. Never add a second binding mechanism; register.
  Effectful capability calls carry `:seon.capability/op-id`, translated
  at the boundary to the writer's durable `::protocol/request-id` —
  replay semantics are the writer's, not a new ledger.
- sci is pinned `:local/root reference-code/sci` @ `be4021d` (JIT
  `45bcf0f` included). NOT a publishable coordinate — packaging (U11)
  needs a pushed mirror. The `:host` deps alias composes with `:writer`;
  `bin/test-writer` runs `-M:writer:host:writer-test`.
- Conformance + registry tests ride `bin/test-writer` (255/1982 green at
  U2). The kill drill is `tmp/sci-probe/jvm/drill.sh` — rerun it whenever
  host lifecycle code changes; PASS = 20/20 rebuild, zero fact loss.

## Gotcha ledger (each cost a lane real time — do not relearn)

1. Writer databases release when their LAST connection closes — hold ONE
   retained channel; never per-call reconnect (`seon.host.context` does
   this correctly; copy it).
2. External bare `ensure-database` is open-existing only for a file database;
   creation requires an initialization page. The writer checks Datahike's
   `database-exists?` before any parent/store creation, and a live logical
   route rejects a different backend path. Still take store paths from
   configuration, never guess (`d0a73db8e`).
3. Bun does not carry AsyncLocalStorage into process-level
   unhandledRejection (commit `6c9bfe83`) — on the pod side, attach
   rejection handlers to the owning Promise, never rely on ambient scope
   in process listeners.
4. sci's unresolved-symbol message is "Unable to resolve symbol" (not
   cljs.js's "Could not resolve") — match semantics, not strings; prefer
   `:seon.error/kind`.
5. `(fn ^:async [] ...)` metadata on the ARGV is ignored by the CLJS
   analyzer — name the fn: `(fn ^:async f [] ...)`. (Pod-side only; the
   JVM host has no async ceremony at all.)
6. Anonymous sci contexts share the base via `sci/fork`; direct env
   injection does NOT propagate to existing forks — the shared load-fn
   registry DOES. Provision via the registry, always.
7. vmmap on macOS labels Bun's mimalloc heap "IOAccelerator" (tag 100).
   Set `MIMALLOC_OS_TAG=240` when profiling.
8. The B2 sci child's smaller context (minimal tee) inflates its
   apparent latency win on non-burst turns — cite the 5.4x burst factor,
   which is eval-dominated, when comparing engines.
9. Shared tree: check `git status` before editing; other lanes hold
   files. Path-limited commits only (`git commit --only -- <paths>`).
10. Frame extraction on fault datoms was ExceptionInfo-constructor noise
    — fixed in `ea031f85` (deepest-cause stack, computed constructor-frame
    drop); keep it true when touching error recording.
11. Protocol version bumps follow the v11/v12 pattern exactly: optional
    fields, input schemas, transport test version constant, and the three
    `test/seon/dev/*` release fixtures — all in one commit (`d784432e`).
12. Operator commands (`bin/seon up`/`down`) must run UNSANDBOXED — a
    sandboxed `killpg` is blocked and leaves stale containment records
    that break `bin/seon status`.
13. Host-context reads aggregated under the EMPTY identity until U4;
    `seon.host.context/*agent-id*` now binds per invocation and the
    wrappers stamp `:seon.db/user`/`:seon.db/process` on reads and as
    tx-meta on writes. Keep new wrappers on that path.
14. An interrupted eval leaves the worker's interrupt STATUS set — any
    NIO writer call after it dies ClosedByInterrupt and closes the
    retained channel. Clear the flag (`Thread/interrupted`) once the
    form is settled, before recording the terminal receipt.
15. Exact-set corpus ops (`:db.fn/retractAttribute` on optional attrs)
    require the attribute INSTALLED; the writer auto-installs only on
    assertion. Real clusters carry the population from genesis — a
    stand-in drill/test database must seed the corpus schema rows,
    the `:seon.eval/id` generator policy, and one assertion probe (see
    `test/seon/host_registry_writer_test.clj` corpus-schema-rows and
    the drill client's Phase 0).
16. A replay-created SCI var is private to its fork. Accretion must
    replay first, then link the U2 registry's exact cached var with
    `sci/add-namespace!`; linking before replay lets `defn` globally
    overwrite the compiled root, while registering without linking never
    reaches the private call site. Once linked, a source edit safely
    rebinds the shared var to the interpreted form and bumps the epoch.
17. The Malli→Datahike bridge rejects a database attribute whose stored
    canonical form references the regex content-digest schema. Persist a
    source fingerprint as Datahike `:string`; derive and validate its
    64-hex content-hash shape at the accretion boundary.
18. `my.blob` uses Node crypto for both hashing and temporary-file UUIDs.
    Extracting the hash owner does not remove the namespace's crypto
    dependency while `.randomUUID` remains.
19. Reader conditionals are rejected in `.cljs` source. A toolkit owner with
    real `:clj` and `:cljs` branches must be `.cljc`; renaming is part of the
    portable change, not a later packaging cleanup.
20. The corpus parser preserves reader conditionals, but the JVM loader must
    classify and eval the tools.reader-selected `:clj` projection. Searching
    the preserved source for `js/` falsely excludes a portable conditional;
    selecting forms with a second parser is a duplicate mechanism.
21. Bare SCI does not expose JVM `Date`, `Math`, `Long`, or their methods.
    Prefer portable core operations (`inst-ms`) or a narrow registry-backed
    `.cljc` owner (`seon.time/iso-string`) rather than widening SCI's class
    allowlist. Registry vars may contain immutable protocol data as well as
    functions; the closed wrapper union keeps those shapes explicit.

## Per-unit executable briefs (U4-U12)

U4 eval-record integration: replace the marked seams so host evals
persist real eval rows/receipts through the ONE corpus mechanism —
study how the child's eval tee works (`seon.eval` detect-and-tee, the
program-graph rows) and mirror the DATA it writes, not its
implementation; includes diagnosing register R2 (every dev-eval
"program row rejected" — find the rejection rule in the writer/indexer
path; the 27x log evidence is in
`../source-cleanup/research/live-system-detectors-2026-07-20`). Gate:
a host-tier agent's defs appear as `:seon.fn` rows queryable next turn,
dev evals record, full writer+cljs suites.

U5 toolkit port: first topologically order every discovered `my.*` namespace
from `seon.host.record/ns-require-edges`, preserving source order inside it;
then provision portable dependency families and immutable protocol values
through the U2 registry; only then convert the C2 17-function/18-form stdlib
table. Classify the tools.reader-selected host branch, never the preserved
conditional source. JS-bound private helpers and their callers are exclusions
with dependency reasons. Gate: zero portable failures, both boot ledgers in the
kill drill, full writer + CLJS suites, and drill PASS.

U3 accretion skeleton is historical evidence only. R48 closes its
tests-pass native escape: `graduate!` refuses until P4/R33 proves the exact
transitive call graph pure, capability-free, and door-equivalent; matching
legacy `:graduated` rows rebuild as interpreted SCI functions. Keep the
differential test as a sanity check for the future P4 gate, never as native
admission by itself.

U6 instrumentation: malli wrappers over sci vars at provisioning time
(B1 proved the envelope shape); derive which vars from the program
graph — closes the 621-gap class for host-tier agents.

U7 park/idle: policy = park after N idle ticks (config fact), 1-2 warm
spares; restore = fork + replay (`seon.host.context/replay-defs!`).
NEVER park an agent with an open run. Gate: park/restore round-trip
preserves next-turn behavior byte-identically.

U8 guidance re-alignment: every skill/docstring/warn example that
teaches `await`-idiom db calls gets a host-tier variant or neutral
phrasing; grep corpus: `await.*db/`, skills mentioning Promises. The
warn-example audit pattern from `0887b1ea` is the method. Gate: a
host-tier agent's rendered context contains zero js-idiom guidance.

U9 await-corpus migration: query persisted `:seon.ns/source` for await
forms; mechanical rewrite to sync idiom for host-tier agents; count
first, then migrate. Small by evidence.

U10 integration drills: host kill + pod restart with LIVE agents on a
branch cluster, derived recovery notices asserted from the agent's
rendered context (not logs). Scripts extend `drill.sh`.

U11 cutover: children retire per-agent (tier data flip), then delete
`seon.execution` child machinery + builds; sci mirror pushed + pinned;
architecture docs + one-mechanism table updated IN THE SAME SERIES.
The pod-term stage-2 rename freeze composes here if not already done.

U12 final system demo: N=100 live agents, real work (defs + db +
capability + canvas), host kill mid-load + pod restart, zero fact loss,
no operator intervention. This is the program's exit.

## Standing constraints

Production `seon.eval`/`seon.execution` are modified ONLY by U4+ units
that own them explicitly. Every unit: falsifier first, full relevant
suites, live proof, issue notes closed with evidence, register/roadmap
rows updated with commit hashes. Errors are values. Docstrings render
into agent context. One mechanism, always.
