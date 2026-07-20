---
type: reference
status: active
tags: [prd, agent, architecture]
---

# Sci execution-runtime chunk runbook

Read order for ANY implementer: `design.md` (the architecture — nothing
here contradicts it), then `roadmap.md` (the transition ledger — pick the
earliest unit marked ready), then the research doc(s) your unit cites,
then the SOURCE the unit names. Do not re-research settled designs;
execute them. A deviation needs evidence and lands in the PRD with your
commit (see U2's receipt deviation as the model: it found an existing
owner and deleted spec instead of building it — that is the bar).

## Ground truth pointers (verified 2026-07-20)

- The execution-protocol contract inventory lives in `src/seon/host.clj`'s
  ns docstring: 4 parent messages (startup/invoke/cancel/shutdown), 4
  child messages (ready/result/error/stopped), protocol-version 3, one
  active invocation per session, sentinel invocation-ids, bounded results.
  It is the conformance baseline for BOTH transports (Bun IPC to children,
  transit-UDS to the host). Parity means shape-for-shape.
- U1/U2 seams are marked in source with TODO comments in
  `src/seon/host.clj` and `src/seon/host/context.clj`: eval-row/receipt/
  corpus recording (`:seon.eval/ids` stays `[]` until U4), `register!`
  admission (records-and-nils until U4), authored source-digest
  invocation, render stays pod-served BY DESIGN.
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
2. `ensure-database` with a wrong path SILENTLY CREATES a fresh store
   (open issue `ensure-database-creates-fresh-store-at-any-path`) —
   always take store paths from configuration, never guess.
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
    — when recording errors, capture the stack at the FAULT site, not at
    envelope construction (forensics expansion owns the fix; keep it
    true).

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

U5 toolkit port: the 46% db-boundary `my.*` fns become `.cljc` calling
the host's sync facade (the C1 failure ledger in
`research/c1-jvm-host-scale-2026-07-20` names each blocked helper);
js-bound survivors become pod-served capabilities via the registry.
Gate: the C1 loader's failure ledger reaches zero portable failures;
both suites.

U3 graduation skeleton: ONE real corpus fn through fingerprint →
both-tier differential tests → JVM `eval` compile → registry re-install
→ epoch re-link proof (U2's live-swap test is the template). The trust
gate is a pure predicate over facts (schema-valid + test-refs green) —
no hand list. Gate: the fn measurably faster post-graduation; edit
drops it back to nursery (fingerprint change).

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

U12 graduation demo: N=100 live agents, real work (defs + db +
capability + canvas), host kill mid-load + pod restart, zero fact loss,
no operator intervention. This is the program's exit.

## Standing constraints

Production `seon.eval`/`seon.execution` are modified ONLY by U4+ units
that own them explicitly. Every unit: falsifier first, full relevant
suites, live proof, issue notes closed with evidence, register/roadmap
rows updated with commit hashes. Errors are values. Docstrings render
into agent context. One mechanism, always.
