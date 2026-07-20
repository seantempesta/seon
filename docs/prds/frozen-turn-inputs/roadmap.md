---
type: prd
status: active
tags: [prd, agent, database, architecture]
---

# Frozen turn inputs roadmap

One turn = one database value = deterministic prompt bytes, retries included.
This chunk owns the byte-identity purity unit carved from
[[../source-cleanup/research/issues-triage-2026-07-20]] §NEW GAP for the two
open issues [[../../seon/issues/ai-context-is-not-pure-over-database-value]]
(friction) and [[../../seon/issues/turn-retries-reread-provider-inputs]]
(blocker).

## Outcome

Rendering the same agent at the same immutable database value produces
byte-identical prompt blobs, across delay, result-cache eviction, and pod
restart. A provider retry consumes the ORIGINAL rendered inputs — prompt,
system text, config resolution, retry budget, attempt timeout — never
re-acquired ones. The one deliberately live surface (the root readline's
wall clock, plus any future load/memory line) is confined to an explicit
free dynamic tail after every cache boundary, hard-capped, and preserved
verbatim only in the prompt blob. No new mechanism: the fix strengthens
`seon.agent.turn`, `seon.execution.runtime/render-prompt!`, and the
`seon.agent.ctx` block functions in place.

Why this matters beyond correctness: the prompt-cache law (transcript
age-band eviction — `src/seon/agent/CLAUDE.md` invariants, enforced today by
`transcript.cljs`'s tier design) already demands that stored bytes render
byte-identically as they age; every impurity below silently busts the LLM
prompt-cache prefix and invalidates `seon.agent.debug/turn-diff`'s
cache-stability instrument. The corrective-steering audit
([[../source-cleanup/research/corrective-steering-audit-2026-07-20]])
graduated its G1-G7 recommendations only because each is persist-time or a
pure render over stored bytes; this chunk supplies the enforcement that
makes "pure render over one database value" actually true.

## Current position — the impurity inventory

Source audit 2026-07-20 on `codex/runtime-reliability-refactor`. Much of the
original issue evidence is already fixed in place and is recorded here so
the acceptance sweep does not re-litigate it.

### Already pure (verified, keep under regression)

- `render-prompt!` performs ONE `db/execute-many` acquisition at the active
  database value inside the execution child and derives `config-resolution`
  from the acquired rows (`src/seon/execution/runtime.cljs:265-345`);
  `seon.agent.turn/render-prompt` rejects a child result whose
  `:seon.db/db` moved (`src/seon/agent/turn.cljs:330-337`).
- `call-llm!`'s retry thunk threads the frozen `resolution` from the prompt
  result; `llm-retry-strategy` reads `:seon.ai/agent-max-retries` from that
  same resolution (`src/seon/agent/turn.cljs:674-686,835-861`). The issue's
  named `bounded-llm-attempt!` conn-deref / `ai/resolved-config` /
  head-inside-thunk sites no longer exist, and the
  `retry-persists-ordered-immutable-config-drift` test expecting drift is
  gone from the tree.
- System text is threaded: the child resolves
  `:seon.config/system-text` from the acquired cluster-config row
  (`runtime.cljs:292-293`), and `seon.ai/effective-system-prompt` is pure
  over the request with a constant fallback (`src/seon/ai.cljs:810-821`).
- `seon.agent.debug/ctx-preview` formats the same compiled-child result the
  turn consumes via the shared `ai/debug-full-prompt`
  (`src/seon/agent/debug.cljs:59-105`, `src/seon/ai.cljs:823-837`) — the
  byte-identity claim is structurally sound and is only as pure as the
  block functions below.

### Impurities (each named site reads outside the pinned database value)

| # | Site | Read | Effect |
|---|---|---|---|
| I1 | `src/seon/agent/ctx/transcript.cljs:562,716` via `seon.eval/result-live?` (`src/seon/eval.cljs:1224-1231`) | `js/globalThis` result-runtime membership | Historical eval rows flip between `result/<id>` handle and inline form after eviction/restart — the ai-context issue's core evidence. `::result-handles?` (`transcript.cljs:102-110`) defaults true; the autocomplete profile already renders the pure form (`transcript.cljs:1215`) |
| I2 | `src/seon/agent/ctx/warnings.cljs:141` (`now (js/Date.)`), window math at `:94`, cutoff compare at `:262` | Wall clock | Warnings block bytes drift with time at one database value (slow-eval one-hour window, recent-failure cutoffs) |
| I3 | `src/seon/agent/ctx/subagents.cljs:269-272` (`now (js/Date.)` → breaker `since`), age display at `:40` | Wall clock | Subagents block bytes drift with time at one database value (crash-window query args, "Ns ago" strings) |
| I4 | `src/seon/agent/ctx/transcript.cljs:661-664` readline `now` + `ctx/host-timezone` (`ctx.cljs:298-304`) | Wall clock + Intl | The DELIBERATE live line ("the ONE legitimate live `now`"). Not a bug per se, but placement/cap are unenforced: nothing proves it sits after every cache boundary under a hard token cap, root-only |
| I5 | `src/seon/agent/ctx.cljs:133,196-206` file-block `read-file-text` (fresh disk read per render); load-time env `SEON_SOUL`/`SEON_SOUL_FILE` at `:253-255` | Filesystem + env | SOUL.md/AGENTS.md bytes enter the cacheable body from disk, not the database value; two pods or two instants can render different bodies at the same coordinate |
| I6 | `src/seon/agent/turn.cljs:772-776` `effective-llm-attempt-timeout-ms` → `config/llm-attempt-timeout-ms` (env `SEON_LLM_ATTEMPT_TIMEOUT_MS`, `src/seon/config.cljs:1400-1409`), called per attempt at `turn.cljs:798` | Env, inside the retry loop | Each retry re-reads the attempt cap instead of consuming the turn's frozen resolution; attempt rows can honestly record different `outer-timeout-ms` per attempt of one turn |
| I7 | `src/seon/agent/turn.cljs:1011-1014` final turn pull passes no `:seon.db/db` | Ambient latest database value | The close transaction's returned coordinate does NOT pin the final asynchronous pull — a later head can change the returned turn (explicit acceptance item of the retries issue, still open) |
| I8 | `src/seon/agent/turn.cljs:934` `run-turn-body!` fallback `(or db (await (db/db)))` | Session-cached latest | The unpinned door: the loop always passes its pinned value, but the fallback silently accepts an unpinned turn instead of failing loudly |

Non-impurities recorded to prevent re-diagnosis: `:seon.agent.turn/at
(js/Date.)` (`turn.cljs:442`) is a persisted fact, not prompt bytes; the
eager reply-blob link transact (`turn.cljs:577-586`) is a write, not a
read; `config/default-run-policy` and the render caps are pure over the
passed configuration map (`config.cljs:769-777,1100-1130`);
`loop.cljs:278,1237` clock reads are run-bound scheduling, not rendering.

## Falsifiable acceptance

1. **Byte identity**: render the same agent twice at the same database value
   (same coordinate, `::readline?` on) with a result-cache eviction and a
   child restart between renders → the cacheable bodies are byte-identical;
   the two full prompts differ at most in the free-tail line. With the tail
   off, the blobs are byte-identical, full stop. Assert with
   `seon.agent.debug/turn-diff`: `::prompt-lines-added`/`-removed` = 0.
2. **Frozen retry**: force a transient provider error while landing a
   concurrent transaction (moving head, changed model config) before retry
   two → every `:seon.ai.attempt/*` row of the turn carries the same
   requested model, endpoint, timeouts, and the turn's single
   `rendered-tx`; the retry sends the original prompt bytes (prompt blob
   unchanged, one blob per turn).
3. **Pinned return**: the final pull consumes the close transaction's
   returned database value; a transaction landed between close and pull
   does not alter the returned turn map.
4. **Free-tail confinement**: live clock/load/memory bytes appear only
   after every cache boundary, under a hard token cap, root-only; the
   prompt blob preserves the exact tail bytes sent.
5. **Regeneration**: re-rendering from a stored turn's recorded coordinate
   (`db/as-of` on `rendered-tx`) reproduces the cacheable body
   byte-for-byte against the prompt blob.
6. A failed required acquisition member yields one error value and zero
   provider calls (already the child contract — keep under the same gate).

## Dependency ledger

- Mechanisms: `seon.agent.turn` (turn bracket, sole LLM retry authority),
  `seon.execution.runtime/render-prompt!` (one acquisition, block
  invocation), `seon.agent.ctx` + `ctx/transcript|warnings|subagents`
  (block bodies), `seon.eval/result-live?` (the cache probe to demote),
  `seon.agent.debug` (`ctx-preview`, `turn`, `turn-diff` — the proof
  instruments), `seon.db` (`execute-many`, `as-of`, pinned `pull`),
  `seon.ai` (`effective-system-prompt`, `resolved-config-from-rows`),
  `seon.retry` (strategy combinators).
- Files: exactly the inventory table plus `src/seon/config.cljs`
  (resolution gains the attempt cap as an ordinary resolved value) and the
  transcript/turn tests under `test/seon/agent/`.
- Skills: `data-oriented-clojure`, `datahike` (as-of/basis mechanics),
  `clojurescript` (child/async), `clojure-testing`,
  `seon-context-config` (block/profile wiring).
- Reference source: `reference-code/datahike/` (db-as-value, as-of),
  `reference-code/again/` (retry strategy design).
- Upstream docs: `docs/seon/architecture/agent-runtime.md`,
  `observability.md`, `context.md` (cache gradient);
  `src/seon/agent/AGENTS.md` invariants (§8a one-value-per-turn,
  age-band byte identity).

## Implementation order

Each stage is one coherent commit series; gates are `bin/test-cljs` focused
suites plus the named live proof. One stage in progress at a time.

### Stage 1 — pin the turn spine (I6, I7, I8)

Resolve the attempt timeout once into the turn's config resolution (the
child's acquisition already merges the config rows; the env default becomes
the resolution's fallback at acquisition time, read once per turn). The
final pull passes the close transaction's `db-after` value; the
`run-turn-body!` fallback either disappears (loop is the only caller and
always pins) or becomes a loud error value. Gate: acceptance 2 and 3 as
focused regressions; live proof via a REPL-forced 429 with a concurrent
transaction.

### Stage 2 — database-stable result handles (I1)

Make ordinary AI context render the runtime-independent eval form the
autocomplete profile already proves, OR derive handle presence from
database facts so identical values render identical bytes; process-local
value reuse remains an execution optimization that cannot alter context
bytes or advertise a dead handle after restart. This is the ai-context
issue's core; decide the exact steering (handle line as a database-derived
fact vs. handles-off default) against live agent behavior before
hardening. Gate: acceptance 1 with forced eviction + child restart;
transcript suite; one live drive confirming agents can still drill results.

### Stage 3 — clock-free block bodies (I2, I3)

Warnings and subagents windows derive their cutoffs from the database
value's own basis (latest transaction instant / recorded facts), not
`(js/Date.)`; age displays become transaction-relative or move to the free
tail. Gate: acceptance 1 across a wall-clock delay (render, wait, render →
identical bodies); warnings/subagents suites.

### Stage 4 — the explicit free dynamic tail (I4) and file-blocks (I5)

Confine the readline's live line (and any future load/memory line) to the
one root-only tail after every cache boundary under a hard token cap;
prompt-blob capture keeps the original tail bytes. File-block question RULED (owner,
2026-07-20): content stays a file on disk or in the content-addressed
blob tier — the ~65k practical ceiling on stored values makes inlining
wrong either way. The FINGERPRINT (content hash) is the database fact: a
file-backed section renders pure over (database value + fingerprint), and
a file edit changes the fingerprint datom, making the cache bust a
visible transacted event with provenance rather than silent drift. The
implementation settles where the fingerprint reconciles
(operation-boundary hash-on-read vs a watcher) — never inside a render
fn; a silent fresh disk read in the cacheable body remains not an option.
Gate: acceptance 4 and 5; `seon-context-config` docs updated in the same
commit.

### Stage 5 — the standing byte-identity gate

One regression that renders every default/required block twice at one
coordinate (not only the transcript — the ai-context issue's audit
acceptance) and diffs bytes; wire it into the existing turn/transcript
suite, never a fourth harness. Close and archive both issues with commit
plus live proof.

## Graduation

Both source issues closed and archived with behavioral proof; acceptance
1-6 green as committed regressions; one live cluster demonstration of
render-twice byte identity across eviction + restart and one frozen retry
under concurrent writes; `docs/seon/architecture/agent-runtime.md` and
`observability.md` updated where the free-tail contract is now enforced
rather than aspirational.
