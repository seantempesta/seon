---
type: research
status: active
tags: [research, error, log, agent, web]
---

# Error envelope unification + `bin/seon log-stream`

Research date: 2026-05-23. Branch: `feature/agent-runtime`. Driving
directive from the user:

> "We can't have the pod just crashing. We need reliability and for
> most operations to return data not an exception that crashes things."

Companion to (and supersedes for ergonomics)
[[eval-error-envelope-2026-05-22]] — that note solved cljs.js cause-
chain flattening (`:seon.error/data`); this note solves the
substrate-wide *envelope shape*, the *return-vs-throw doctrine*, and
the *operator log surface* that observes both.

## TL;DR — three recommendations

1. **One envelope, two keys.** Every substrate fn that can fail at a
   call boundary returns
   `{<ns>/ok? boolean, <ns>/error <seon.error/->map e> | <ns>/<success-key> v}`.
   The `<ns>` segment matches the fn's owning namespace
   (`:seon.db/ok?`, `:seon.eval/ok` — note the existing inconsistency).
   The error value is ALWAYS the output of `seon.error/->map` — never a
   bare string, never `{:msg "" :data {}}`. Converge `seon.db`'s
   `::transact-response` Malli shape to reference the
   `:seon.error/->map` shape rather than its current `{::msg ::data}`
   ad-hoc body. The watcher tile reads `:seon.error/message` for the
   one-liner and may pretty-print `:seon.error/data` on expand.

2. **Doctrine: programmer-mistakes throw; remote/IO/eval failures
   return data.** The dividing line is "would a passing test catch it?"
   Invocation-shape, unregistered-attr, unbound `*conn*` —
   programmer-mistakes that the agent's eval boundary catches and
   surfaces as `{:ok? false :error …}` anyway. Datahike commit failures,
   listener-handler throws, HTTP I/O, LLM rejections, eval timeouts —
   ALL return data. Concretely: leave `seon.db/transact!`'s validation
   throws in place (the eval boundary catches them), but DO NOT
   introduce `try-transact!`/`transact-or-throw!` variants — one fn,
   one shape; the doctrine is enforced by *what kind of failure*, not
   *which fn was called*.

3. **`bin/seon log-stream` tails `:seon.log` over SSE, not text logs.**
   New subcommand opens an SSE connection to a new pod endpoint
   `GET /log-stream` (registered next to `/sse`) that broadcasts
   `:seon.log/entry` transactions as structured NDJSON-over-SSE.
   Filters (`--level`, `--source`, `--agent`) are query-string params
   parsed pod-side. `--replay N` returns the last N entries before
   tailing live. The pod is already the structured-event authority
   (every error already lands as a `:seon.log/entry` per `seon.log`
   docstring); reusing `seon.web.serve` keeps one process, one
   transport, one source of truth. Parsing `logs/pod.log` text would
   re-derive structure we already have.

## Topic 1 — Error envelope: every seam, today and proposed

The substrate seams (sources where errors originate or are translated)
and what each surfaces today:

| Seam | File:Line | Current shape | Read by | Proposed converged shape |
|---|---|---|---|---|
| `seon.eval/eval` | `src/seon/eval.cljs:356-407` | `{:ok true :value … :ns …}` \| `{:ok false :error <error/->map>}` | agent eval ctx, `eval-batch!`, `record-eval!` (writes `:seon.eval/error` as `pr-str` of the map) | **GOLD STANDARD** — keep. Promote key to `:seon.eval/ok` for ns consistency (open question — see §Risks). |
| `seon.eval/eval-batch!` | `src/seon/eval.cljs:591-654` | Returns `[eval-id …]` (always); per-form result lives in `:seon.eval/error` text on the DB entity | agent next-turn ctx via `recent-errors`, watcher tile (via the eval entity) | Add a second return key — `{:seon.eval/eval-ids […] :seon.eval/all-ok? true|false}` so callers can branch without re-reading the DB. Per-form result envelope unchanged. |
| `seon.eval/raw-eval` | `src/seon/eval.cljs:296-354` | Promise resolves `{:value :ns}` / rejects with ex-info | `eval` only (internal) | Stay throw-based — it's the internal cljs.js bridge. `eval` is the boundary. |
| `seon.db/transact!` | `src/seon/db.cljs:672-728` | **Throws** on validation (3 distinct ex-info shapes — `:seon.db/unregistered-attrs`, `:seon.db/invalid-value`, `:seon.db/invalid-ref-child`, `:seon.db/invalid-invocation-shape`); **returns** `{::ok? true ::tx-report}` / `{::ok? false ::error <error/->map>}` on datahike commit | callers branch on `(:seon.db/ok? r)`; agent eval catches the throws | Keep both paths. Update Malli `::transact-response` to reference `seon.error/->map`'s schema instead of inline `{::msg ::data}` body (currently lies — `error/->map` emits `:seon.error/message`, not `::msg`). |
| `seon.db/listen!` handler dispatch | `src/seon/db.cljs:1108-1127` | sync throws caught + `js/console.warn`; async rejections caught + `js/console.warn`; both swallow ex-data | `js/console.warn` only — nothing structured | **CHANGE.** On handler throw OR async rejection, call `seon.log/error!` with `:seon.log/source :seon.db/listen!` + `:seon.log/data {:seon.db/key key}`. Watcher tile sees these; agent's `recent-errors` sees them. Don't replace the console.warn — keep both (stdout for tailing + DB for agent). |
| `seon.db/query` / `pull` / `entity` | `src/seon/db.cljs:1002-1030` | Throws | callers (rare; reads are inside eval ctx) | Leave throwing. Reads from inside agent eval already convert to data at the eval boundary; promoting these to data-return adds friction at every call site. |
| `seon.log/error!` (and warn/info/debug) | `src/seon/log.cljs:118-154` | Returns Promise; `.catch` falls back to `console.error`. Sync throw caught + console.error. | nothing branches on the result | Keep — `log!` is the **terminal sink**. By definition it must never raise; an envelope here would have nowhere to go. The current double-catch is correct. |
| `seon.web.broadcast/render-agent!` | `src/seon/web/broadcast.cljs:59-87` | Returns HTML string always; on render throw, transacts `:seon.log/level :error` + returns fallback red-tile HTML | SSE patch fan-out, browser | **REFERENCE PATTERN.** This is exactly the convergence target — try/catch + `log/error!` + inline visible fallback. Apply this shape anywhere a UI surface might be affected by a substrate failure (eg. `serve/serve-root!` if `page/root-html` ever throws). |
| `seon.web.broadcast/render-for-new-conn!` | `src/seon/web/broadcast.cljs:144-162` | per-agent try/catch → `log/error-console!` (stderr only, **not** DB) | stderr | **CHANGE.** Switch to `log/error!` so the watcher actually sees what failed at connect time. Right now an SSE-connect crash is invisible to the agent. |
| `seon.web.serve` HTTP handlers (`/chat`, `/clear`, `/log`) | `src/seon/web/serve.cljs:194-294` | per-handler try/catch → 4xx/5xx text body + `log/error-console!` (stderr); request body errors swallowed | HTTP client (browser), stderr | **CHANGE.** Replace `log/error-console!` with `log/error!` (DB-backed) for the *request handling* failures; keep `error-console!` for *body-read* level failures (those land before the request has identity). Body-of-error: respond JSON `{:seon.error/message …}` instead of plain text — caller can JSON.parse it. |
| `seon.web.serve` top-level `handler` try/catch | `src/seon/web/serve.cljs:296-321` | catches everything, writes 500 text body, `log/error-console!` | HTTP, stderr | Promote to `log/error!`. This is the last-line-of-defense for the HTTP surface — should be visible to agent. |
| `seon.repl/parse-forms` (CURRENT) | `src/seon/repl.cljs:112-148` | Read errors halt loop, return truncated vector. Comment says "(V0.5 we'll thread the read error back as a sentinel pair.)" | `eval-batch!` | **Platform's pending rewrite-clj refactor must land:** per v1.md §4 hard rule 1, parse failures land as `{:ok? false :kind :read :source "..." :error <map>}` entries in the parsed vector. `eval-batch!` records them as `:seon.eval` entities with `:seon.eval/ok? false` + `:seon.eval/kind :read`. Use `seon.error/->map` on the rewrite-clj exception so the shape matches every other seam. |
| `seon.eval/record-eval!` DB write | `src/seon/eval.cljs:564-589` | `pr-str`s `(:error result)` into a string field `:seon.eval/error` | watcher tile, agent ctx (via the eval entity) | **CHANGE.** Add `:seon.eval/error-data` (Malli `:any`, datahike `:db.type/string` with edn round-trip, OR add a `:seon.eval/error-edn` companion field). Watcher should be able to expand structured data, not parse `pr-str` output. Cheapest path: store `(pr-str (:error result))` as today but ALSO store `:seon.eval/error-message` (the `:seon.error/message` string) so the tile shows a clean one-liner without parsing edn. |
| Process-level `unhandledRejection` / `uncaughtException` | `src/seon/client.cljs:404-428` | `log/error-console!` only (stderr) | stderr | **CHANGE (low priority).** Promote to `log/error!` with `:seon.log/source :seon.process/unhandled`. *Caveat:* if `*conn*` is unbound when the handler fires (boot-time crash), `log/error!` will itself fail; the current console fallback inside `log/log!` covers that case (see `seon.log/log!` `.catch` + sync `catch`). So the change is safe — degrades gracefully. |
| `seon.fs/*` (allowlist deny) | (not read in this session; flagged) | Throws ex-info on deny per recent ship | agent eval (caught), boot precondition checks | Should match `seon.db/transact!`: agent eval boundary converts to data; explicit attempts in trusted boot paths get the throw. No new fn variants. |

### Watcher-tile contract

The watcher (`seon.render.default/recent-errors` + the per-agent tile)
should read errors as `:seon.log/entry` rows from datahike. The
*structured* fields the tile needs:

- `:seon.log/at` — already present
- `:seon.log/source` — already present (the originating ns)
- `:seon.log/message` — already present (the one-liner)
- `:seon.log/agent` — already present (so the per-agent tile filters
  by id)
- `:seon.log/data` — already present, `:any`. **Convention:** when the
  source surfaces a `seon.error/->map` output, put the whole map here.
  Tile expand reads `:seon.log/data` and renders the `:seon.error/data`
  flattened ex-data + the `:seon.error/cause` chain as a collapsible
  tree.
- `:seon.log/stack` — already present.

**No new attrs needed.** This is the load-bearing finding: the storage
shape already matches what we want; the work is *populating it
consistently* by funneling every seam through `log/error!` with
`:seon.log/data` = the `error/->map` output.

## Topic 2 — Return-data-vs-throw doctrine

**The dividing question:** would a passing test suite have caught this?

- **YES → throw.** It's programmer-mistake. The agent's eval boundary
  catches it and surfaces it as `{:ok false :error …}` to the agent's
  result tile anyway. Throwing locally keeps call sites clean and
  preserves the loud failure mode for tests + REPL exploration.
- **NO → return data.** Remote I/O, listener handlers (whose body is
  out-of-our-control user-or-agent code), eval (whose body is
  user-or-LLM-supplied), HTTP request handling. These failures are
  expected.

Concrete classification by fn (the dividing line above applied):

| Fn | Class | Why |
|---|---|---|
| `seon.db/transact!` validation | **MAY throw** | KI-1 invocation-shape, unregistered-attr, invalid-value — all programmer-mistakes. Agent eval catches. Throwing keeps the per-validation `:seon.db/error` keyword precise (the agent reads `(-> result ex-data ::error)` and switches on `:seon.db/invalid-value` vs `:seon.db/unregistered-attrs`). |
| `seon.db/transact!` datahike commit | **MUST return data** | Already does. The boundary layer per spec-02 §2.5. |
| `seon.db/query` / `pull` / `entity` | **MAY throw** | Reads against a synchronous db value. A bad query is a programmer-mistake; agent eval catches. Wrapping every read in a `{:ok? …}` envelope adds friction; agents would unwrap at every call. |
| `seon.db/listen!` handler invocation | **MUST return data** | Handler body is out-of-our-control. Listener currently swallows + console.warn; convert to `log/error!` so the data surfaces. |
| `seon.eval/eval` | **MUST return data** | Already does. The defining boundary. |
| `seon.eval/eval-batch!` | **MUST return data** | Already does (returns the eval-id vector). Add `:all-ok?` derived flag. |
| `seon.eval/raw-eval` (internal) | **MAY throw / reject** | Internal cljs.js adapter; the public `eval` is the boundary. |
| `seon.log/*!` | **MUST NOT throw** | Terminal sink. Already correctly double-catches. |
| `seon.web.serve` HTTP handlers | **MUST return data** | All paths must end in a written response. Currently they DO (via try/catch); the change is *what shape* of error response — JSON body w/ `:seon.error/message` instead of plain text. |
| `seon.web.broadcast/render-agent!` | **MUST return data** | Reference pattern — try/catch → log + fallback HTML. |
| `seon.fs/*` allowlist deny | **MAY throw** | Programmer-mistake (or LLM hallucination). Agent eval catches. |
| `seon.db/assert-preconditions!` | **MUST throw** | Boot-time loud-failure. There's no "continue degraded" path that makes sense if `:keep-history?` is false. Stays throwing. |
| `seon.db/resolve-conn` | **MAY throw** | Programmer-mistake (unbound `*conn*`). Agent eval catches; tests catch. |
| Process-level handlers | **MUST NOT throw** | Already correctly log-and-continue. Promote logger from stderr to DB-backed. |

### Why not a parallel `try-*!` family

The original prompt asked whether `db/transact!` should have a
companion `db/try-transact!` returning data, leaving the throwing
version for trusted callsites. **Recommend against.** Rationale:

1. There's no real ambiguity. The eval boundary catches throws and
   converts them to data; trusted callsites (boot, tests) want the
   throw and have it. A `try-*!` variant would split the callsite
   space and create the "which one do I use?" question every reader
   asks.
2. The validation throws are *programmer-mistakes* by doctrine. They
   should be loud in development. The eval boundary's catch is exactly
   the right cushion for the production case.
3. Today's split — validation throws, commit returns data — encodes
   the doctrine in the *failure mode*, not in *which fn*. That's the
   right invariant.

The one place we DO want consistency improvement: rename the existing
inconsistency. `seon.eval` uses `:ok`; `seon.db` uses `:seon.db/ok?`.
The data rule (CLAUDE.md §Data Rules) demands every key namespaced.
**Open question for MVP-track:** promote `:ok` → `:seon.eval/ok`?
Touches ~30 callsites. Cheap if done as a single ship; expensive if
left for organic conversion. Recommend bundling with the
`:seon.eval/error-message` add-on noted above.

## Topic 3 — `bin/seon log-stream` UX

### CLI surface

```
bin/seon log-stream [--level LVL] [--source NS] [--agent ID] [--replay N] [--format edn|json]

  --level LVL    Filter to entries at or above LVL. One of:
                 debug, info, warn, error. Default: info.
  --source NS    Filter to entries whose :seon.log/source matches NS
                 (substring; `seon.eval` matches `seon.eval/*`).
                 Repeatable.
  --agent ID     Filter to entries whose :seon.log/agent equals ID.
                 Repeatable.
  --replay N     Before live-tailing, emit the last N matching entries
                 from the DB. Default: 50. Use --replay 0 to skip.
  --format FMT   Output format. `edn` (default — one form per line)
                 or `json`.

Examples:
  bin/seon log-stream                                 # info+ live tail
  bin/seon log-stream --level error                   # error-only
  bin/seon log-stream --source seon.eval --agent seon # one agent's evals
  bin/seon log-stream --replay 200 --level error      # see what's been failing
  bin/seon log-stream | jq -c 'select(.source == "seon.web.serve")'
                                                     # post-filter with jq
```

Output (`--format edn`, one entry per line):

```
{:seon.log/at #inst "2026-05-23T18:23:45.123Z" :seon.log/level :error
 :seon.log/source :seon.web.broadcast/render
 :seon.log/agent "seon"
 :seon.log/message "TypeError: undefined is not a function"
 :seon.log/data {:seon.error/message "TypeError: undefined is not a function"
                 :seon.error/data {:seon.eval/warning-type :undeclared-var
                                   :seon.eval/undeclared "agent.foo/missing"}
                 :seon.error/stack "..."}}
```

### Transport choice — SSE over HTTP, NOT logs/pod.log tail

The pod owns the structured-event authority: every error already lands
as a `:seon.log/entry` (per `seon.log` docstring + the convergence in
Topic 1). Two transport options:

1. **Tail `logs/pod.log`.** Pro: zero pod-side change. Con: re-parses
   the pretty-printed Timbre-style line format (`2026-05-23T…  ERROR
   [source] msg`), loses `:seon.log/data` (which never lands in stdout
   today — only `:seon.log/message` does). Filter quality drops:
   `--source` becomes substring on a possibly-truncated source string,
   `--agent` requires the agent id to appear in the message body
   (it usually doesn't).
2. **SSE from a new `/log-stream` endpoint on the pod's loopback
   HTTP+SSE server.** Pro: structured at the wire; pod-side filter
   pushed close to source; replay is just a Datalog query at
   connect-time; integrates cleanly with the existing
   `seon.web.serve` (`:seon.web/port` already in `tmp/seon-port`, so
   the CLI reads the port the same way other tools do). Con: requires
   ~80 LOC of pod-side endpoint + ~50 LOC of bash/`curl`-based CLI
   side.

**Recommend transport (2).** Reasons:

- The whole convergence story is "every error is structured data in
  the DB." Letting `log-stream` parse stdout would re-introduce the
  structure-loss step we're explicitly trying to eliminate.
- The pod already publishes per-tx morphs over SSE; pushing
  `:seon.log/entry`s over SSE reuses identical infrastructure
  (`seon.web.sse` + a one-key tx-listener filter).
- Pod-side filtering is more efficient than wire-side: a
  `--source seon.eval` filter excludes 95% of entries before they
  cross the loopback.
- `bin/seon tail pod` already exists for raw-stdout tailing; the new
  command is for *structured queryable* events, a distinct use case.

### Pod-side implementation sketch

New endpoint in `seon.web.serve`:

```clojure
;; GET /log-stream?level=info&source=seon.eval&agent=seon&replay=50&format=edn
(defn- open-log-stream! [^js req ^js res]
  (.writeHead res 200 #js {"Content-Type" "text/event-stream"
                           "Cache-Control" "no-cache"
                           "Connection" "keep-alive"})
  (let [params  (parse-query-params req)
        level   (keyword (or (get params "level") "info"))
        sources (set (get-all params "source"))   ; multi-value
        agents  (set (get-all params "agent"))
        replay  (parse-int (get params "replay") 50)
        fmt     (or (get params "format") "edn")
        levels  (level-set level)                 ; :info ⇒ #{:info :warn :error}
        match?  (fn [entry]
                  (and (contains? levels (:seon.log/level entry))
                       (or (empty? sources)
                           (some #(str/includes? (str (:seon.log/source entry)) %)
                                 sources))
                       (or (empty? agents)
                           (contains? agents (:seon.log/agent entry)))))
        emit!   (fn [entry]
                  (.write res (str "data: " (render-entry entry fmt) "\n\n")))]
    ;; Replay: one query, ordered by :seon.log/at desc, take replay, reverse.
    (doseq [entry (recent-log-entries replay match?)] (emit! entry))
    ;; Tail: listen! on :seon.log/* attr-index slices.
    (let [key (random-uuid)]
      (db/listen!
        {:seon.db/key key
         :seon.db/handler
         (fn [{:seon.db/keys [db attr-index]}]
           (let [log-eids (->> (vals attr-index)
                               (apply concat)
                               (filter #(= "seon.log" (namespace (:seon.db/a %))))
                               (map :seon.db/e) distinct)]
             (doseq [eid log-eids
                     :let [entry (db/pull {:seon.db/db db
                                           :seon.db/pull-pattern '[*]
                                           :seon.db/ref eid})]
                     :when (match? entry)]
               (emit! entry))))})
      (.on req "close" (fn [] (db/unlisten! {:seon.db/key key}))))))
```

### Client-side `bin/seon log-stream`

Pure bash; reads `tmp/seon-port`, opens the SSE stream with `curl
--no-buffer`, strips `data: ` prefix, prints. ~30 LOC. Pseudocode:

```bash
cmd_log_stream() {
  local port
  port="$(cat tmp/seon-port 2>/dev/null)" \
    || { echo "pod not running (no tmp/seon-port)" >&2; exit 1; }
  # Parse --level/--source/--agent/--replay/--format into query string.
  local qs
  qs="$(build_log_stream_qs "$@")"
  exec curl -sN --no-buffer "http://127.0.0.1:${port}/log-stream${qs}" \
    | sed -nE 's/^data: //p'
}
```

`build_log_stream_qs` URL-encodes per arg; bash-native (no python).
Multi-value `--source` / `--agent` repeat the param.

### Why not register a new `log-stream` *process*

The user might consider making `log-stream` a registered process in
`bin/seon`'s `process_command` registry. **Don't.** It's a *client of*
the pod, not a peer process. The registry's idempotency / mutex
semantics presume a long-running daemon to be managed; `log-stream` is
a foreground tail that the user opens and Ctrl-Cs. It's a peer of
`bin/seon tail`, not of `bin/seon start pod`.

## Risks + sequencing

### Cheap, can converge first

1. **Switch `seon.web.serve` handler `log/error-console!` →
   `log/error!`** for the request-level catches (the body-of-request
   logs stay console-only — they fire before request identity).
   Impact: low (3 callsites in `handle-clear!`, `handle-chat!`,
   `handler`). Watcher immediately sees HTTP failures.
2. **Switch `seon.web.broadcast/render-for-new-conn!`** stderr to
   `log/error!`. One callsite.
3. **`seon.db/listen!` swallowed errors → `log/error!`** in addition
   to the existing `console.warn`. Two callsites (sync throw + async
   reject). The reason this is cheap: `listen!`'s try/catch already
   exists; we're just adding a DB write inside the catch.
4. **Promote process-level handlers** in `seon.client/-main` to
   `log/error!`. Caveat per Topic 1 table re: `*conn*` unbound
   degradation — `log/log!`'s existing fallback covers it.
5. **Add `:seon.eval/error-message` companion field** to `record-eval!`
   so the watcher tile shows a clean one-liner without parsing edn.

### Touchier — needs MVP-track alignment

6. **Promote `seon.eval` envelope from `:ok` → `:seon.eval/ok`.**
   ~30 callsites (every `eval` + `eval-batch!` consumer, plus the
   inner `maybe-await-value` / `setup-agent-ns!` plumbing). Should
   ride alongside the rewrite-clj `parse-forms` refactor MVP is doing
   per Platform's pending work — same touch surface in `seon.repl` +
   `seon.eval`, same change-of-keys risk profile, one ship. Affects
   v1.md §4 example bodies; design doc should be patched in the same
   commit.
7. **Malli `::transact-response`** body shape: change
   `[::msg :string] [::data :any]` → reference whatever schema
   `seon.error/->map` registers (currently `seon.error` registers
   nothing — define + register `:seon.error/error-map`). Catches
   future drift between the response Malli and the actual
   `error/->map` output.

### `log-stream` ship — independent

8. Pod-side `/log-stream` endpoint + `bin/seon log-stream` subcommand
   are an independent ship, blocked only by item 7 (the response
   shape over the wire is the `:seon.error/error-map`). Ships after
   the convergence items above settle, so the structured payloads it
   tails are actually structured.

### Non-risks

- **Don't worry about backwards compat of the eval envelope.** Agent
  code lives in the agent's home ns and is rewritten between sessions;
  any `:ok` consumer is in our substrate code, not user persistence.
  One coordinated commit converts everything.
- **Don't worry about the `:seon.log/data :any` Malli registration.**
  CLAUDE.md flags `:any` as a code smell, but `:seon.log/data` is
  exactly the "genuinely opaque object" carve-out (per
  `memory_no_any` note). It IS the polymorphic carrier. Keep it as
  `:any` for in-memory validation; persisted form is the string-edn
  pair in `:seon.log/message` + the `pr-str` of `:seon.log/data` —
  see the existing comment at `src/seon/log.cljs:106-110`.

## File:line references for non-obvious claims

- `seon.error/->map` is the cause-chain flattener that gives us
  `:seon.error/data` (flat) + `:seon.error/cause` (recursive):
  `src/seon/error.cljs:42-69`.
- `seon.db/transact!` validation throws are different ex-info shapes
  per failure mode: `src/seon/db.cljs:478-482` (unregistered),
  `582-591` (invalid value), `542-549` (invalid ref child),
  `630-653` (KI-1 invocation shape).
- `seon.db/transact!` datahike commit failures return
  `{::ok? false ::error (error/->map e)}`:
  `src/seon/db.cljs:716-728`.
- `seon.db/listen!` handler invocation try/catch + Promise `.catch`
  goes to `console.warn` only (no DB write):
  `src/seon/db.cljs:1113-1127`.
- `seon.eval/eval` is the canonical envelope:
  `src/seon/eval.cljs:356-407`.
- `seon.eval/record-eval!` stringifies the error via `pr-str`:
  `src/seon/eval.cljs:582-584`.
- `seon.log` doc explicitly states errors are first-class DB data:
  `src/seon/log.cljs:1-26`. `log!` double-catch:
  `src/seon/log.cljs:118-137`.
- `seon.web.broadcast/render-agent!` is the gold-standard try/catch +
  log + fallback HTML pattern: `src/seon/web/broadcast.cljs:59-87`.
- `seon.web.broadcast/render-for-new-conn!` uses stderr-only,
  inconsistent with `render-agent!`: `src/seon/web/broadcast.cljs:152-162`.
- `seon.web.serve` HTTP handler error paths log to stderr only:
  `src/seon/web/serve.cljs:209-215`, `263-270`, `285-294`, `317-321`.
- `seon.client/install-process-safety-net!` console-only:
  `src/seon/client.cljs:404-428`.
- `seon.repl/parse-forms` halts on read error, pending rewrite-clj
  refactor: `src/seon/repl.cljs:112-148`.
- v1.md hard rule for `:kind :read` partial-failure entries:
  `docs/prds/agent-runtime/v1.md:144-148`, `586-613`.
- `bin/seon` subcommand dispatch + `tail` precedent for the
  `log-stream` slot: `bin/seon:262-336`.
- `tmp/seon-port` discovery file written by `seon.web.serve/start!`:
  `src/seon/web/serve.cljs:333-397`.
- `seon.web.serve` SSE pattern + connection registry to copy from:
  `src/seon/web/serve.cljs:129-150`, plus `seon.web.sse` (not read
  in this session — flagged for the implementer to adapt
  `emit-patch!` to the new entry format).
