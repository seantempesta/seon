---
type: research
status: complete
tags: [research, architecture, database, web]
---

# Reactive seam names — grounded vocabulary for the live-update pipeline

Charter: vocabulary-unification PRD, owner ruling 2 (2026-07-20). The owner
rejects abstract channel nouns ("feed", "stream", "subscription") that lack a
grounded reason. This report traces the live-update pipeline end to end,
records what each hop's OWNING dependency or mechanism calls the thing, and
proposes a glossary built only from those seam-owned words.

## Dependency ledger

| Dependency / mechanism | Source read | Owns the vocabulary for |
|---|---|---|
| Datahike fork (submodule) | `reference-code/datahike/src/datahike/core.cljc:199-217`, `writer.cljc:235-240,346-407`, `committed_report.cljc` | listener, transaction report, committed report |
| Seon writer | `src/seon/db/writer.clj:853,2118-2207` | selective committed-report interests |
| Seon database protocol v11 | `src/seon/db/protocol.cljc:20-98,304,383,645,1213-1219` | operations, events, `database-advanced` |
| `seon.reactive` | `src/seon/reactive.cljs` (whole ns) | registration, computation, consumer |
| Datastar JS + Clojure SDK | `reference-code/datastar-clojure/libraries/sdk/src/main/starfederation/datastar/clojure/api.clj:67-360`, `protocols.clj:4-30` | SSE generator, patch-elements, patch-signals |
| Bun / Web Streams / zlib | `src/seon/subprocess.cljs:66-75`, `src/seon/web/datastar.cljs:249,793-860` | stream (ReadableStream, gzip stream, `text/event-stream`) |
| openai-node SDK | `reference-code/openai-node/src/resources/chat/completions/completions.ts:757-836` | stream, chunk (`chat.completion.chunk`), delta |

## The traced pipeline

### Hop 1 — Datahike commit → writer delivery

- The fork's noun for what a listener receives is the **transaction report**
  (`tx-report` with `:db-before`, `:db-after`, `:tx-data`):
  `datahike/core.cljc:199-211` ("callback ... with the transaction report"),
  `datahike/writer.cljc:235-240,346-407`.
- The fork's noun for the committed-side delivery machinery is the
  **committed report**: a whole namespace, `datahike.committed-report`, with
  `offer-committed!` (`writer.cljc:239`) and per-source evidence keys
  `:datahike.committed-report/{queued,offered,delivered,overflowed,...}`
  (`committed_report.cljc:17-25`). The queue owner is called a **source**.
- The callback attachment is a **listener**: `listen!`/`unlisten!`, "the key
  under which this listener is registered" (`core.cljc:205,213`).

### Hop 1b — writer interest + protocol delivery

- The Seon writer's own mechanism name is the **interest** — "Selective
  committed-report interests" (`writer.clj:2118`), `::interest-state`,
  `interest-attributes`, `add-interest-to-entry`, `remove-interest-locked!`
  (`writer.clj:2127-2207`), and the protocol constructor docstrings say
  "database interest request" (`protocol.cljc:1213-1219`).
- Protocol v11 (`current-version 11`, `protocol.cljc:98`) separates
  acquisition from delivery with named **events**: `datoms-event`,
  `resynchronization-event`, `database-advanced-event`
  (`protocol.cljc:49-51,304`). The client tracks
  `::database-advanced-acquisitions` (`writer.clj:1679-1681` region).
- One stray abstraction inside the protocol itself: `feed-behind-status`
  (`protocol.cljc:96,383`), a receipt-status enum member. No producer or
  consumer of this var exists anywhere else in `src/` — it is dead vocabulary
  AND the only "feed" on the database side. Its grounded name would be
  `committed-report-behind` (the condition it describes is the
  committed-report delivery lagging the receipt query).

### Hop 2 — seon.reactive

Docstrings and keys are already mechanism-honest (`reactive.cljs:1-7`):
"Each **registration** owns one **writer interest**, one active
**computation**, and one **newest pending database value**." Concrete names:
`::registrations`, `::registration-id`, `interest-key`, `install-interest!`,
`::consumers`, `notify-consumers!`, `::equal-notifications-suppressed`
(equality suppression), `observe!`/`unobserve!` attaching a **consumer**.

Verdict: **"registration" is the honest noun.** It matches Datahike's own
sentence ("the key under which this listener is registered") and needs no
change. "consumer" is likewise grounded (the notify fn attached under a
consumer-key).

### Hop 3 — web delivery (Datastar)

Datastar's own wire vocabulary (JS library + Clojure SDK):

- the wire unit is an **SSE event** named `datastar-patch-elements` /
  `datastar-patch-signals`; the operation is a **patch** with a **patch
  mode** (default `outer`, i.e. morph) — `api.clj:267-360`;
- the server-side handle is the **SSE generator** (`sse-gen`, protocol
  `SSEGenerator`, `close-sse!`, `with-open-sse`, `lock-sse!`) —
  `protocols.clj:4-30`, `api.clj:67-119`;
- the client-visible state units are **signals** (`patch-signals!`).

`src/seon/web/datastar.cljs` today mixes THREE invented/overloaded nouns for
this hop:

- **"feed"** (191 uses in this file; 21 more in `web/debug.cljs`, 10 in
  `route.cljs`) = the open socket-owning view + its registry + the
  `:seon.web.feed/*` keys (`datastar.cljs:51-86,259-298`) + the public URL
  segment `/agent/{id}/feed` (`route.cljs:105-110`).
- **"subscription"** (46 uses, `datastar.cljs:72-520`) = the normalized
  shared render authority that one `reactive/observe!` registration backs
  (`subscription-key` is literally passed as `::reactive/key`,
  `datastar.cljs:432-445`).
- **"stream"** (21 uses) = the Bun `ReadableStream`/gzip pipe and
  `text/event-stream` response (`datastar.cljs:249,793-860`) — this usage is
  grounded in Bun/Web-Streams/SSE vocabulary and is fine.

None of the `:seon.web.feed/*` keys are transacted; the registry is a
process-local atom (`!feeds`), so no persisted datoms carry the name.

### Hop 4 — LLM provider streaming

The provider SDK's own words: `stream: true`, a **stream** yielding **chunk**
objects (`object: 'chat.completion.chunk'`) each carrying a `choice.delta`
(**delta**) — `openai-node completions.ts:757-836`. Seon already uses exactly
these: `stream-until-form!`, "Per content delta", "usage-only final chunk"
(`openai_compat.cljs:370-434`), `:seon.ai/stream?`, repl-mode `:stream`.

Verdict: **"stream" is the seam-owned word at this hop** (and for Web
Streams in `subprocess.cljs`/`shell.cljs`, and for the SSE
`text/event-stream` response). Keep it there; ban it as a synonym for the
database-side hops, where nothing is called a stream by its owner.

## Current-usage inventory mapped to hops

Reported baseline: feed 227, stream 215, subscription 51.

| Cluster | Count (per-file `rg -c`) | Hop | Grounded? |
|---|---|---|---|
| `web/datastar.cljs` feed | 191 | 3 | no — Datastar says sse-gen/patch |
| `web/debug.cljs` + `route.cljs` + `router.cljs` + `serve.cljs` feed | 38 | 3 + public URL | URL is a public surface |
| `db/protocol.cljc` `feed-behind-status` | 2 | 1b | no — and dead (no producer) |
| `web/datastar.cljs` subscription | 46 | 3 | no — it IS a reactive registration |
| `my/data.cljs` `:my.subscription/*` | 6 | none | example domain schema, unrelated, keep |
| `ai/*` + `agent/turn.cljs` stream/delta/chunk | ~80 | 4 | yes — openai-node vocabulary |
| `subprocess.cljs`, `agent/shell.cljs` stream | ~47 | Web Streams | yes |
| `web/datastar.cljs` stream (Bun/SSE) | 21 | 3 transport | yes |
| `config.cljs`, `client.cljs` "downstream" | ~50 | n/a | English word, not the channel noun |
| `agent/ctx/transcript.cljs` "event stream" prose | 16 | prose | acceptable; "ordered events" is tighter |

## Proposed grounded glossary

One name per hop, each copied from the seam that owns it:

| Hop | Name | Owner that grounds it |
|---|---|---|
| Datahike commit unit | **transaction report** (`tx-report`, db-before/db-after/tx-data) | datahike core/writer |
| Committed-side delivery | **committed report** (source, offered, delivered) | `datahike.committed-report` |
| Writer selectivity | **interest** (database interest, interest request) | `seon.db.writer` + protocol constructors |
| Protocol delivery unit | **event** — `datoms`, `resynchronization`, `database-advanced` | `seon.db.protocol` v11 |
| Pod reactive unit | **registration** with **consumers** | `seon.reactive` (already correct) |
| Web shared render authority | **registration** (drop "subscription"; it is the datastar-side handle on one reactive registration) | `seon.reactive` |
| Web socket handle | **SSE generator** (`sse-gen`) | datastar-clojure SDK |
| Web wire unit | **patch event** (`datastar-patch-elements`), **signals** | Datastar |
| Transport pipe | **stream** (ReadableStream, gzip stream, `text/event-stream`) | Bun/Web Streams/SSE spec |
| LLM delivery | **stream** of **chunks** carrying **deltas** | openai-node |

### Does `:seon.web.feed/*` earn "feed"?

No. No dependency on either side of that seam says "feed": Datahike delivers
committed reports to interests; Datastar receives patch events from an SSE
generator. "feed" is exactly the fourth invented umbrella word the owner
rejects. Proposed replacement inside `seon.web.datastar`:

- the socket-owning descriptor (`::feed-definition`, `!feeds`, `open-*-feed!`)
  → **sse-gen** vocabulary: `::sse-gen`, `!sse-gens` / `::views`,
  `open-agent-sse!` (or keep `open-agent-view!` since what it opens is a
  view's SSE generator);
- `:seon.web.feed/*` keys → `:seon.web.datastar.sse/*` (or
  `:seon.web.sse/*`);
- "subscription" → **registration** (`registration-key`, matching the
  `::reactive/key` it becomes at `datastar.cljs:437`);
- `feed-behind-status` → `committed-report-behind-status`, or delete it
  outright (no producer/consumer exists; deleting shrinks the enum at
  `protocol.cljc:383` — a protocol-compatible narrowing since nothing emits
  it, but note `current-version`).

### Migration cost per rename

| Rename | Occurrences | Public surface | Persisted schema |
|---|---|---|---|
| datastar.cljs "subscription" → "registration" | ~46, one file, process-local keys | none | none (atom-only) |
| datastar.cljs/debug.cljs "feed" → sse-gen/patch vocabulary | ~212 across 2 files + serve/router mentions | internal fn names (`open-agent-feed!` is a route handler symbol in `route.cljs:106` — route data must move in the same commit) | none — `:seon.web.feed/*` never transacted |
| URL segment `/agent/{id}/feed` | 10 in `route.cljs` + shipped `data-init="@get('…/feed')"` shim HTML | YES — bookmarks, downstream acme shims, docs; routes are database data so a change needs config/route reseed (`cluster reset` or config apply) per cluster | route facts in each live database |
| `feed-behind-status` → delete or rename | 2 lines, one file | protocol enum (v11); no emitter found, so effectively free — verify with `bin/test-writer` | none |
| Hop-4 / Web-Streams "stream" | 0 — keep; it is the owner's word | — | — |

Recommendation on the URL: treat the path segment as a public surface, not
vocabulary. Either keep `/agent/{id}/feed` as a frozen route literal (cheap,
slightly inconsistent) or rename to `/agent/{id}/sse` inside the stage-2
freeze where shim page + route data + downstream acme move atomically. The
namespace-internal renames do not depend on the URL decision.

## Calibration — naming discipline in vendored Clojure codebases

- **datahike** (`reference-code/datahike`): every noun is the data's own
  shape — `tx-report` is named after the map it is; the delivery namespace is
  named `committed-report` after the thing it moves; the callback attachment
  is a "listener". No channel abstraction ("feed"/"bus") appears anywhere in
  the write path.
- **datastar-clojure** (`libraries/sdk/.../api.clj`): names everything after
  the wire reality — `sse-gen`, `patch-elements!`, `patch-signals!`,
  `with-open-sse`, `lock-sse!`. The README-level docs say "SSE generators",
  never "connection manager" or "feed". The server handle is named for the
  protocol it speaks.
- **posh** (`reference-code/posh/src/posh/core.cljc:106-136`): functions are
  named after the Datalog operations they cache — `add-q`, `add-pull`,
  `add-pull-many` over a `posh-tree`; the only invented noun is the library's
  own name for its one composite structure. No "subscription" noun despite
  being a reactive-query library.

The pattern across all three: the only new noun a namespace introduces is
the one composite IT owns (`posh-tree`, `committed-report` source,
`sse-gen`); everything that crosses a seam keeps the seam owner's word.
`seon.reactive` already follows this discipline; `seon.web.datastar` is the
one namespace that invented two extra nouns ("feed", "subscription") for
things whose owners already have names.

## Open questions

1. URL policy: freeze `/agent/{id}/feed` as a literal, or rename to `/sse`
   during the stage-2 freeze with acme coordination? (Owner call; both are
   cheap in src, the second touches live route facts + downstream shims.)
2. `feed-behind-status`: delete (narrowing the v11 enum) or rename? Verify no
   writer emitter with `bin/test-writer` before deleting.
3. `web/debug.cljs` uses "feed" for the same SSE mechanism (21 uses,
   `debug-feed!` handler at `route.cljs:109-110`) — assume it rides the same
   rename series?
4. Whether the datastar.cljs socket descriptor should be named `sse-gen`
   (SDK word) or `view` (it already carries view identity); both are
   defensible — `sse-gen` is the stricter copy of the owning SDK.
