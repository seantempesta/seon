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

Every turn persists — always on, no debug flag:

- **`:seon.agent.turn/basis-t`** — the basis-t of the frozen db value the loop
  threaded through the turn. This is the coordinate that makes the context
  re-derivable: `render-prompt` over `(db/as-of db basis-t)` reproduces the
  structured context for that turn.
- **`:seon.agent.turn/prompt-blob`** — the assembled prompt verbatim, in the
  blob store. The as-of re-render is the *structured, queryable* view; the blob
  is the byte ground truth that survives render-code changes (a re-render runs
  TODAY's render fns; the blob is what the model actually saw).
- **`:seon.agent.turn/reply`** — the raw LLM reply. Not derivable from
  anything, so it is stored: inline as a datom when small, a blob ref past the
  size threshold.
- **The volatile prompt inputs, as data** — the `:relevant-source`
  embedding-hit ids and any `result/<id>` values rendered into the prompt are
  recorded on the turn, so nothing the model saw came from an unrecorded
  volatile.
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
- **Verbs** — `my.blob/put!`, `my.blob/get`, `my.blob/text` (paged, honest
  totals), on the same never-throw result envelope as every toolkit verb.
- **One store, many writers** — turn capture, oversized eval results, and
  agent-authored artifacts all use the same verbs. There is no separate
  "debug capture" file tree; debug persistence IS blob persistence.
- **Searchable** — blobs are inside the `my.search` grep surface, and any
  blob-backed attr can be pointed at the embedding index.

## Replay, diff, search

Three verbs make a turn a first-class object of study:

- **`inspect/turn`** — one call returns the whole bundle for agent X, turn N:
  the exact prompt (blob), the structured context (as-of re-render, per-block),
  the reply, the evals with results, usage, and the messages visible at that
  basis-t. No joins by hand, no filesystem.
- **`inspect/turn-diff`** — what changed between two turns: a block-level diff
  of the two rendered contexts plus the datom delta between the two basis-ts
  (`db/since`). This is also the cache-stability instrument: bytes that should
  have been frozen but moved show up here.
- **`inspect/ctx-preview`** — generalized over time: preview any agent's
  context at any t, not just now.

Search runs at two ends, one door each, and nothing in between:

- **Literal** — `grep-graph` targets every text-carrying attr: fns, schemas,
  evals, messages, turns, and blob-backed prompts/replies; filterable by
  agent, time range, and attr.
- **Semantic** — the ONE `:seon/embedding` index. `register-embeddable!`
  points it at message bodies and eval narrations alongside fns and `my.kb`;
  `search-pull` scopes KNN by a datalog `:where`. No second index, no
  separate FTS engine — exact regex and semantic KNN cover the spectrum.

## The forensic agent

Debugging an agent is done **by another agent given the exact world**, not by
a human reading logs:

- Mint a scratch agent over a **snapshot conn** — the same isolation mechanic
  as the `/solve` door — whose db value is `as-of` the target's basis-t at the
  turn under investigation. Its world IS the target's world at that moment.
- Seed its ctx with the target's **reconstructed context blocks** plus one
  extra **debug-brief block**: the behavior in question and the ask —
  "identify why the agent did this; answer in clear markdown."
- It **evals code to investigate** — its transactions land on the local
  scratch snapshot, advancing a copy of the world realistically, never
  touching the live cluster.
- **Per-agent LLM config** selects a cheap reasoning model with thinking ON
  for these runs, so forensic passes are routine, not precious.

This is a composition of existing mechanisms — scratch-conn isolation,
seed-copy ctx override via `install!`, as-of replay, per-agent provider
routing — not a new runtime. A forensic pass is cheap enough to run on every
puzzling drive.

## The external evaluation door

`/solve` is the one HTTP door for driving a hermetic agent from outside: mint
a scratch agent on an isolated conn, inject the input through the real wake
path, await the derived `:idle`, return the final reply plus honest
termination metadata (turns, evals, closed-reason, timed-out). The inspect.ai
bridge is a thin client of it; the gym drives the same recipe in-process. The
answer key never enters the pod — scoring stays host-side. Forensic agents,
gym scenarios, and benchmark samples are the same shape: a sealed world, a
brief, a derived verdict.

## Build path

The gaps between this design and the current code — basis-t not yet stored,
reply not persisted, blob store spec-only, grep/embedding targets not yet
widened, the forensic seed verb — live in [[roadmap]].
