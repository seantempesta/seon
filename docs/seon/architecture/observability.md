---
type: architecture
status: active
tags: [architecture, agent, database]
---

# Observability — inspect any agent, any turn

> **Target design** (present tense — the system as it is when built). Current
> code state + the build order live in [[roadmap]].

Every question about agent behavior — "what exactly did this agent see at turn
N?", "what changed between turn N and N+1?", "why did it do that?" — is answered
by a **query against the DB plus the blob store**, never by hunting log files.
This falls out of the core property: a turn's prompt is a pure render of ONE
frozen db value, so persisting that value's coordinate makes the whole turn
reproducible. Observability is not a subsystem bolted on; it is the
derive-everything principle pointed backwards in time.

## The turn record

Every datom already names its Datahike transaction. Normal Seon transactions
add `:seon.db/user` and `:seon.db/process` refs on that transaction, so
“who/through which execution path wrote this datom?” is a join, never a stored
domain back-reference. Turn/eval causality is intentionally not copied onto
arbitrary transactions: ordinary run/turn/eval/message refs record the modeled
facts, but Seon does not claim it can enumerate or replay every external effect
caused inside a turn. What every turn additionally persists—always on, no debug
flag:

- **The rendered database coordinate** —
  `:seon.agent.turn/rendered-store-id`, `rendered-branch`,
  `rendered-commit-id`, and `rendered-as-of` persist the exact
  `{store-id, branch, commit-id, t}` of the frozen db value the prompt rendered
  from. This is the ONE coordinate the turn transaction cannot provide: the
  prompt renders before its own tx, with other agents' commits interleaving.
  Commit id is canonical; t remains an ordered display/query aid.
  `render-prompt` resolves that retained commit on the named branch and
  reproduces the structured context exactly.
- **`:seon.agent.turn/prompt-blob`** — the assembled prompt verbatim, in the
  blob store. The as-of re-render is the *structured, queryable* view; the blob
  is the byte ground truth that survives render-code changes (a re-render runs
  TODAY's render fns; the blob is what the model actually saw).
- **`:seon.agent.turn/reply-blob`** — the raw LLM reply. Not derivable from
  anything, so it is stored: a blob ref on the turn (content-addressing makes
  repeated replies free). An errored turn stores WHY instead —
  `:seon.agent.turn/error`, the failure as a bounded data string — so capture
  never depends on turn success.
- **The volatile prompt inputs, as data** — any `result/<id>` values rendered
  into the prompt are recorded on the turn, so nothing the model saw came
  from an unrecorded volatile.
- The existing projections: prompt size (tokens at display), `llm-usage` /
  `llm-meta`, the `:seon.eval` component refs, status, retries.

**Datom vs blob is decided by size, never by kind.** The DB stores projections
and small values; large text — a prompt, a raw reply, a big eval result —
lives in the blob store behind a datom ref. The DB is never a text dump.

## The blob store — `my.blob`

The disk tier of the three-tier storage rule (datoms = projections, blobs =
persistent full content, stash = volatile live values), made a first-class
capability instead of ad-hoc log files:

- **Content-addressed** — a blob's name is its content hash; writes are
  idempotent, identical content dedupes for free. Blobs live under the
  cluster dir, beside the store they annotate.
- **A blob ref is data** — the datom carries the hash plus a token estimate
  and a media hint, so queries can filter and budget without touching disk.
- **Functions** — `my.blob/put!`, `my.blob/get`, `my.blob/text` (paged, honest
  totals), on the same never-throw result envelope as every toolkit function.
- **One store, many writers** — turn capture, oversized eval results, and
  agent-authored artifacts all use the same functions. There is no separate
  "debug capture" file tree; debug persistence IS blob persistence.
- **Searchable** — blobs are inside the `my.search` grep surface, and any
  blob-backed attr can be pointed at the embedding index.

## Replay, diff, search

Three functions make a turn a first-class object of study:

- **`agent-debug/turn`** — one call returns the whole bundle for agent X, turn N:
  the exact prompt (blob), the structured context (as-of re-render, per-block),
  the reply, the evals with results, usage, and the messages visible at that
  resolved coordinate. No joins by hand, no filesystem.
- **`agent-debug/turn-diff`** — what changed between two turns: a block-level diff
  of the two rendered contexts plus the datom delta when both commits share the
  required ancestry (`db/since`). Different lineages use two immutable snapshot
  results and report that no linear since-range exists. This is also the
  cache-stability instrument: bytes that should have been frozen but moved show
  up here.
- **`agent-debug/ctx-preview`** — generalized over time: preview any agent's
  context at any resolved coordinate, not just now. A legacy `{branch, t}`
  selector must resolve to exactly one retained commit before rendering.

Search runs at two ends, one door each, and nothing in between:

- **Literal** — `grep-graph` targets every text-carrying attr: fns, schemas,
  evals, messages, turns, and blob-backed prompts/replies; filterable by
  agent, time range, and attr.
- **Semantic** — the ONE `:seon/embedding` index. `register-embeddable!`
  points it at message bodies and eval narrations alongside fns and `my.kb`;
  `search-pull` scopes KNN by a datalog `:where`. No second index, no
  separate FTS engine — exact regex and semantic KNN cover the spectrum.

## Error recording — fault-tagged datoms + the strict gate

Errors join turn replay as first-class DB objects. `seon.error/record!`
is the catch-site function (the iron rule as a fn: nothing is caught without
becoming data): it classifies `:seon.error/fault` (`:agent` — expected,
the agent's learning signal; `:core` — our bug), stamps
the full `:seon.error/store-id`, `:seon.error/branch`,
`:seon.error/commit-id`, `:seon.error/at` coordinate live at the catch site—the
resolved immutable db is the frozen state the failing code saw, composing directly with
`agent-debug/turn` replay), parses the stack into `:seon.error/frames`
component entities (Datalog-queryable traces), and keeps the full args
of malli contract violations as bounded `:seon.error/args-edn`.
Persistence is fire-and-forget (never throws, never awaits; a bounded
drop-oldest buffer rides out conn-less windows) and one caught failure yields
one deduplicated error record in one recording transaction (propagating
rejections are dedup-tagged).

Two capture layers need zero per-site work: the malli instrumentation
wrapper's async arms (rejections + resolved-value output violations —
both previously invisible) and the process net
(`uncaughtException`/`unhandledRejection` → fault `:core`).

Expected-test faults, dev-REPL classification, and the error-write recursion
fence are ambient execution facts, not process-wide modes. One
`AsyncLocalStorage` scope map carries their fully namespaced markers only along
the Promise/await work spawned inside that scope. Concurrent agents never
inherit one another's markers. In particular, a pending error persistence write
suppresses only a contract fault caused by that same write chain; it cannot drop
an unrelated agent's simultaneous fault. None of these markers is persisted.

The `:seon.config/on-core-error` dial (manifest; `:crash | :gate |
:log`, shipped `:gate`) governs `:core` faults only — `:agent` faults
never escalate in any mode. Under `:gate` the pod keeps running but the
CI-shaped wrappers fail any run that accumulated a new `:core` fault
(`bin/test-cljs` greps the run transcript for the `SEON-CORE-FAULT`
marker; the dev hook brackets the pod log by byte offset). The derived
`core-faults-block` section renders on the ROOT view only and vanishes
when the last `:core` fault predates the latest user message — no
acknowledgement state. Design + rulings:
`docs/prds/agent-ctx/research/error-blame-strict-gate-2026-07-03.md`.

The persisted message is the DEEPEST real cause (`seon.error/deepest-message`
walks the `:seon.error/cause` chain past cljs.js's generic wrappers), so the
`SEON-CORE-FAULT <message> @t=<t> commit=<abbrev>` marker and every triage surface name
the actual failure, never `"ERROR"`.

Triage runs through three `seon.agent.debug` functions, three altitudes over
the same datoms:

- **`errors`** — compact recent list, newest first (optional
  `:seon.error/fault` filter + limit): per row the error eid, fault, full
  store/branch/commit/t coordinate,
  deepest-cause short message, top stack frame, and the recording agent.
- **`error`** — one full envelope by eid (message, fault, full coordinate, frames
  table, args-edn, data-edn, stack) plus the JOINS: the recording agent and
  the turn active at that resolved coordinate (derived from the agent's turn windows and
  ordinary domain refs) — the turn eid composes with `agent-debug/turn`
  inspection.
- **`repro`** — the work-backwards bundle: the LIVE immutable db value resolved
  from the error's full coordinate (REPL material—render t + abbreviated commit,
  never print the db),
  the failing fn sym + args-edn when the malli envelope captured them, the
  linked turn, a ready-to-eval reproduction expression string built from
  what is actually stored (an honest note when args were not captured —
  nothing fabricated), and the `::fork-hint` — the exact supervisor command
  that boots this error's view as a live cluster (below).

The as-of db is read-only; when the fix needs a WRITABLE view—re-running
safe code, patching data, letting a forensic agent act—the fourth step is
**fork**: `bin/seon cluster fork <cluster> --commit <commit-id>` creates a
Datahike same-store copy-on-write branch with `branch!` at that retained commit,
then boots a branch-qualified debug pod/writer on its own port. It does not copy
Konserve or define another versioning model. Entity/transaction ids through the
fork point are identical, but coordinates are always store+branch+commit
qualified because later transaction ids can diverge/reuse after a reset.

A convenience `--branch <branch> --t <t>` selector is accepted only when graph
resolution finds exactly one matching retained commit. Zero or multiple matches
is a typed ambiguity, never “pick the first max-tx.” The debug pod starts
non-autonomously: opening history installs no ticker, wake trigger, or agent
host and never resumes agents, schedules, providers, or external-effect workers.

The coordinate semantics are precise: it is captured at the catch site—the db
value the failing code saw—while the error datom itself commits later, so the
error datom does not exist inside its own branch. Prompt/reply blobs are
content-addressed: the branch reads the source blob layer read-only and writes to
a branch-local overlay, so destroying the branch cannot delete/mutate source
content. Before promotion, every target-referenced overlay hash is verified and
materialized into main's base; blob GC treats every retained branch as a root.
The full flow is find (`watch-faults`) → `errors` → `error` → `repro` →
**fork** → fix, then branch destroy stops/releases the branch pod/connection,
calls `delete-branch!`, and removes only its overlay. The generic physical-store
destroy door refuses branch-qualified targets.

The standing alarm is `bin/seon watch-faults` — a dependency-free supervisor
subcommand that tails the pod log from end-of-file (following across
rotation and pod restarts), blocks until the first NEW un-expected
`SEON-CORE-FAULT` marker (the `SEON-EXPECTED-CORE-FAULT` fixture marker is
ignored), prints it with the last ~20 log lines, and exits 0. Run it as a
background task at session start; when it fires, triage `errors` → `error`
→ `repro`.

## The forensic agent

Debugging an agent is done **by another agent given the exact db the target
saw**, not by a human reading logs:

- Mint a forensic agent in an **ephemeral writable branch** (`bin/seon cluster
  fork`, its own pod/branch connection) rooted at the target's resolved commit, so it
  sees exactly what the target saw at that moment.
- Seed its ctx with the target's **reconstructed context blocks** plus one
  extra **debug-brief block**: the behavior in question and the ask —
  "identify why the agent did this; answer in clear markdown."
- It **evals code to investigate** — its transactions land in its OWN
  cluster's db, advancing that copy realistically, never touching the
  target's cluster.
- **Per-agent LLM config** selects a cheap reasoning model with thinking ON
  for these runs, so forensic passes are routine, not precious.

This is a composition of existing mechanisms—pod isolation, Datahike branch
roots, seed-copy ctx override via `install!`, as-of reconstruction,
per-agent provider routing — not a new runtime. A forensic pass is cheap
enough to run on every puzzling drive.

## Cluster lifecycle and the composition door

Isolation is the CLUSTER: one shared db + its agents, one Node pod per
cluster, all dbs hosted by the one wire-server JVM (the registry). From
inside a cluster there is ONE conn and ONE database — agents never know
other clusters exist (`list-dbs`/`remove-db` are supervisor-facing wire
ops, never agent-exposed). The supervisor owns the lifecycle:

- `bin/seon cluster create <name> [--ephemeral]` — a db entry (`:file`,
  ensured at pod boot) + a pod on its own ephemeral HTTP port. ~10s warm,
  ~25s cold.
- `bin/seon cluster fork <source> --commit <commit-id> [name]` — same physical
  store, new Datahike branch + branch-qualified registry/wire/pod target + blob
  overlay. `--branch <branch> --t <t>` is a legacy convenience only when it
  resolves uniquely.
- `bin/seon cluster destroy <branch-name>` — for a branch target, stop/release
  its pod/conn, `delete-branch!`, and remove only its overlay; never delete the
  shared store/source blobs.
- `bin/seon cluster destroy <name>` — stop the pod, delete the db from the
  registry (`registry/delete-db!`), remove `data/clusters/<name>/`
  including `blobs/` (turn capture is per-cluster).

`POST /agents/run` is the one-shot composition door on EVERY pod, built
purely from the agent primitives: start-or-reuse an agent in the pod's own
cluster (optional `agent_id` — durable store, so the same agent can be
driven again across a pod restart), deliver the input through the real wake
path, await the derived `:idle` of the run it woke, return the truthful
reply plus termination metadata (turns/evals scoped to this request's
window, closed-reason, timed-out) and `model_config` — the RESOLVED LLM
config the agent runs under (provider/model/temperature/max-tokens/
thinking), COMPUTED at response time by the pure resolver
`seon.ai/resolved-config` (agent overrides → config row → shipped
defaults; derive-don't-store, [[data-model]] §4.4 — per-turn historical
exactness is the same resolver over an as-of db). Inspect AI drives per-sample
ephemeral clusters by port through this same production boundary; there is no
in-process evaluator lifecycle. The answer key never enters the pod — scoring
stays host-side. Benchmark vocabulary is harness-side only.

## Build path

Turn capture is partly LIVE (2026-07-02): every turn persists legacy
`rendered-as-of` + prompt/reply blob refs (always on), and
`seon.agent.debug/turn` /
`turn-diff` reconstruct/compare turns from them. The blob refs are the ONE
capture path — the old gated `seon.debug` file tree (`SEON_DEBUG_CAPTURE`,
`logs/turns/`) is deleted; Inspect AI and debug projections read prompts back
by blob hash.
Remaining gaps—the full store/branch/commit coordinate and honest legacy-t
migration, the as-of per-block re-render inside `agent-debug/turn`, the
volatile prompt inputs as recorded data, grep/embedding targets not yet
widened, the forensic seed function — live in [[roadmap]].
Source-grounded verification of what datahike's tx metadata already provides
(and why the pre-turn basis-t is the one thing it doesn't):
`docs/prds/agent-fsm/research/` tx-metadata findings, 2026-07-02.
