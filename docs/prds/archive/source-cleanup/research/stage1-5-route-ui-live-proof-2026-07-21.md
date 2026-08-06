---
type: research
status: complete
tags: [research, web, rendering, testing]
---

# Stage 1.5 route and UI live-proof audit

## Decision

The Stage 1.5 route and UI source contracts are focused-test complete, but the
graduation claim still needs one coordinated live run against a single frozen
source revision. The missing evidence is not another unit test: it is the
composition of a real retained execution child, the database-seeded value
route, the `/data` feed, child retirement, Datastar fragment replacement, and
a real browser DOM.

This report is the executable integration script. It does not authorize a
source edit. Any failed assertion aborts the checkpoint and becomes an issue
or returns to the owning unit; do not repair the live database to make a
failure disappear.

## Source and focused evidence already present

The implemented route is the database-seeded `GET /agent/{id}/value` in
`src/seon/route.cljs` and `src/seon/web/serve.cljs`. The handler:

- accepts exactly one `eval` or `entity` selector;
- strictly decodes canonical EDN vector paths and canonical non-negative
  offsets under the configured path, byte, and realization budgets;
- authorizes eval ownership from the one acquired immutable database value
  before calling the retained execution child;
- restricts entity reads to `/agent/root/value` and never calls a child;
- returns identical `404` bodies for missing and cross-agent evals;
- distinguishes bounded user refusal (`400`), absence (`404`), and core
  unavailability (`503`); and
- returns `Cache-Control: no-store` without an access-control-allow-origin
  header.

`src/seon/web/debug.cljs` renders `/data` entities through the universal value
tree, using the database value supplied to the feed computation. The shim and
feed remain the existing static operator routes; the value route itself is
database route truth. `src/seon/handlers/eval.cljs` keeps the stored result as
a bounded fallback and exposes the live result only through an explicit value
route request. `src/seon/execution.cljs` and `src/seon/execution/host.cljs`
retain the live value in its owning child, use closed correlated Transit
frames, share the per-agent FIFO, and report retirement without spawning a
replacement child.

The shortest owning focused gate is:

```bash
bin/test-cljs \
  --test=seon.render.value-test \
  --test=seon.render.block-test \
  --test=seon.execution-test \
  --test=seon.execution.host-test \
  --test=seon.web.serve-test \
  --test=seon.web.router-test \
  --test=seon.route-test \
  --test=seon.handlers.eval-test \
  --test=seon.web.datastar-test
```

Load-bearing focused assertions include:

- `value-path-codec-is-canonical-and-closed` and
  `strict-value-path-reader-ignores-global-tag-parsers`;
- `absolute-value-framing-refuses-before-database-acquisition` and
  `configured-value-refusal-does-no-domain-or-sampler-work`;
- `missing-and-cross-agent-evals-are-uniform-and-send-zero`;
- `eval-value-statuses-and-admitted-sampler-request-are-exact`;
- `root-entity-value-uses-one-database-value-and-zero-host-sends`;
- `drill-value-enforces-the-ruled-1025-touch-ceiling`;
- `projected-map-keys-and-their-descendants-have-no-controls`;
- `missing-value-owner-is-unavailable-without-spawn`;
- `configuration-retirement-settles-an-active-sample-as-unavailable`; and
- `successful-technical-detail-fetches-only-the-authorized-live-value`.

These are source evidence, not substitutes for the live child and browser
proof below.

## Read-only live observation on 2026-07-21

With `bin/seon status` reporting watcher, writer, and pod alive, this identity
request was run without mutating the database:

```bash
curl -sS -N --max-time 5 \
  -D tmp/stage15-data-feed.headers \
  -H 'Accept: text/event-stream' \
  -H 'Accept-Encoding: identity' \
  -o tmp/stage15-data-feed.sse \
  'http://127.0.0.1:7890/data/feed?view=stage15-route-ui-audit'
```

The expected curl timeout occurred after the long-lived stream had delivered
148,921 bytes. The response was `200`, `text/event-stream`, `no-store`,
`keep-alive`, and `Vary: Accept-Encoding`, with no `Content-Encoding: gzip`.
The capture contained exactly one `datastar-patch-elements` event, exactly one
`id="app-view"`, 263 `seon-value-` identities, and zero `<pre` elements. This
proves the current `/data` endpoint can produce one identity-encoded universal
tree frame. It does not prove browser interaction, a later broadcast, eval
ownership, or retirement, so Stage 1.5 is not graduated by this observation.

## Frozen live-proof setup

Run the remaining matrix only after all source-editing lanes are coherent.
Record these facts before starting:

```bash
git rev-parse HEAD
git status --short
bin/seon status
curl -fsS http://127.0.0.1:7890/_seon/ready
```

The status may contain only the deliberately retained B2 cache directories.
Record the admitted artifact digest and immutable database value through the
cluster-qualified `eval_cljs` tool:

```clojure
{:artifact-digest (seon.execution/current-artifact-digest)
 :database (seon.db/db)}
```

Do not continue if the source revision changes, readiness is not `200`, the
artifact digest differs from the frozen build, or the database call returns a
`:seon/error`.

Use two existing ordinary agents, `AGENT_A` and `AGENT_B`, or create them
through the maintained lifecycle before freezing the proof inputs. In
`AGENT_A`'s real execution child, evaluate a value with both depth and paging:

```clojure
{:outer {:rows (vec (map (fn [n] {:n n :square (* n n)}) (range 80)))}}
```

The evaluation must be performed through cluster-qualified `eval_cljs`, not
in the pod parent. Record its returned `EVAL_ID`. Query the immutable database
value to verify the ownership join before HTTP work:

```clojure
(seon.db/query
 {:seon.db/query
  '[:find ?eval-id ?agent-id
    :in $ ?eval-id
    :where
    [?eval :seon.eval/id ?eval-id]
    [?eval :seon.eval/agent ?agent]
    [?agent :seon.agent/id ?agent-id]]
  :seon.db/args ["EVAL_ID"]})
```

Abort if it does not return exactly `EVAL_ID` and `AGENT_A`.

## HTTP refusal and path round-trip matrix

Set shell variables only to the exact recorded values:

```bash
SEON_BASE=http://127.0.0.1:7890
AGENT_A='recorded-agent-a'
AGENT_B='recorded-agent-b'
EVAL_ID='recorded-eval-id'
```

For every response below, also assert `Cache-Control: no-store` and absence of
`Access-Control-Allow-Origin`.

First capture missing and cross-agent disclosure results:

```bash
curl -sS -D tmp/value-missing.headers \
  -o tmp/value-missing.body \
  "$SEON_BASE/agent/$AGENT_A/value?eval=definitely-missing"

curl -sS -D tmp/value-cross.headers \
  -o tmp/value-cross.body \
  "$SEON_BASE/agent/$AGENT_B/value?eval=$EVAL_ID"

cmp tmp/value-missing.body tmp/value-cross.body
rg '^HTTP/1.1 404' tmp/value-missing.headers tmp/value-cross.headers
```

Abort on different bodies, a non-404 status, or evidence that the child was
contacted. Use process/log evidence or a host counter when the integration
driver exposes one; an HTTP-only equality check is necessary but not enough
to prove zero sends.

Exercise canonical success at the root, a nested path, and a nonzero page:

```bash
curl -sS -D tmp/value-root.headers -o tmp/value-root.html \
  "$SEON_BASE/agent/$AGENT_A/value?eval=$EVAL_ID&path=%5B%5D&offset=0"

curl -sS -D tmp/value-nested.headers -o tmp/value-nested.html \
  "$SEON_BASE/agent/$AGENT_A/value?eval=$EVAL_ID&path=%5B%3Aouter%20%3Arows%5D&offset=0"

curl -sS -D tmp/value-page.headers -o tmp/value-page.html \
  "$SEON_BASE/agent/$AGENT_A/value?eval=$EVAL_ID&path=%5B%3Aouter%20%3Arows%5D&offset=8"

rg '^HTTP/1.1 200' tmp/value-root.headers tmp/value-nested.headers tmp/value-page.headers
rg 'id="seon-value-' tmp/value-root.html tmp/value-nested.html tmp/value-page.html
```

The logical value identity must be unchanged between offsets for the same
selector and path; the rendered page contents must advance. Instrumented work
evidence must remain within `offset + page-size + 1`, not merely return small
HTML.

Run the closed refusal table and require `400` for every row:

```bash
for query in \
  '' \
  'eval=x&entity=1' \
  'eval=x&eval=x' \
  'eval=x&unknown=y' \
  'eval=x&path=%5B1%5D%20%3Atail' \
  'eval=x&path=%5B%23uuid%20%22bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb%22%5D' \
  'eval=x&path=%ZZ' \
  'eval=x&offset=-1' \
  'eval=x&offset=01' \
  'eval=x&offset=9007199254740992'
do
  status=$(curl -sS -o tmp/value-refusal.body -w '%{http_code}' \
    "$SEON_BASE/agent/$AGENT_A/value?$query")
  test "$status" = 400 || exit 1
done
```

Also generate a 33-segment canonical path, a raw path above 4,096 bytes, and
an offset whose `offset + page-size` exceeds 1,024. Each must return `400`
before authorization, child send, descent, or realization. Do not infer the
zero-work property from status alone; retain the integration counters.

For the database producer, choose a visible positive `ENTITY_ID` from the
`data-entity-id` attribute in the `/data/feed` frame, then run:

```bash
curl -sS -D tmp/entity-root.headers -o tmp/entity-root.html \
  "$SEON_BASE/agent/root/value?entity=$ENTITY_ID&path=%5B%3Adb%2Fident%5D&offset=0"

curl -sS -D tmp/entity-nonroot.headers -o tmp/entity-nonroot.body \
  "$SEON_BASE/agent/$AGENT_A/value?entity=$ENTITY_ID"
```

Require `200` universal-tree HTML for root, `404` for non-root, and zero
execution-host sends for both. The database acquisition, entity read, schema
projection, and render must all use the same immutable database value.

## Identity SSE proof

Keep one server-side client open while browser actions run:

```bash
curl -sS -N --max-time 30 \
  -D tmp/stage15-data-feed.headers \
  -H 'Accept: text/event-stream' \
  -H 'Accept-Encoding: identity' \
  -o tmp/stage15-data-feed.sse \
  "$SEON_BASE/data/feed?view=stage15-data-live" &
DATA_FEED_PID=$!

curl -sS -N --max-time 30 \
  -D tmp/stage15-agent-feed.headers \
  -H 'Accept: text/event-stream' \
  -H 'Accept-Encoding: identity' \
  -o tmp/stage15-agent-feed.sse \
  "$SEON_BASE/agent/$AGENT_A/feed?view=stage15-agent-live" &
AGENT_FEED_PID=$!

bin/seon logs pod --follow
```

The curl exit code `28` is expected only after frames have arrived. Require:

- `200`, `text/event-stream`, and no gzip content encoding;
- a `datastar-patch-elements` event containing exactly one `#app-view` per
  frame;
- universal `seon-value-` identities and no raw `<pre` on `/data`;
- `FEED OPEN` for both sockets in the current pod log; and
- after one scoped database mutation in the integration driver, one matching
  `broadcast` and a second affected frame without reopening either socket.

Abort if a source file changes during the stream, a frame contains
`#app-view-error`, a feed silently closes before the mutation, or an unrelated
attribute is used as the only broadcast proof.

## Real browser proof

The browser bridge is not the SSE oracle. Create a new agent-owned browser tab
and leave every pre-existing tab untouched:

1. `tabs_context_mcp`, then `tabs_create_mcp`; record the new tab id.
2. Navigate it to `http://127.0.0.1:7890/agent/AGENT_A`.
3. Read console messages before interaction and require no error, unhandled
   rejection, Datastar failure, or duplicate-id warning.
4. Find the successful eval's `live result` disclosure and toggle it. Require
   exactly one `@get` to `/agent/AGENT_A/value?eval=EVAL_ID`, no page reload,
   and the same logical `seon-value-` root after replacement.
5. Expand `:outer`, then `:rows`, then activate the next-page control. Require
   visible rows to advance, stable selector/path identity, and no second feed.
6. Hover one deliberately invalid fixture. Require the red explanation to
   become legible with zero network requests. Check valid green and partial
   hollow status indicators in the same Phosphor palette.
7. Switch a custom-rendered value to `as data`; require the same value in the
   generic tree and no recursion or duplicate root id.
8. Navigate the owned tab to `http://127.0.0.1:7890/data`. Require one
   `#app-view`, entity sections, universal trees, no raw EDN `<pre>`, and no
   execution-child request when an entity attribute is drilled.
9. Inspect a projected long or collection key. It must have a bounded label
   and no drill control.
10. Re-read console messages and require zero new errors.

Use screenshots at the agent root, nested/page result, invalid hover, custom
`as data`, and `/data` entity drill. The DOM assertions, network observations,
and screenshots are all required; a screenshot alone cannot prove request
identity or the absence of a second feed.

## Real retirement proof

With the browser still displaying `AGENT_A`'s available live eval, retire the
owning execution child through the maintained operator/lifecycle mechanism.
Do not terminate the agent entity, delete the eval, clear the database, or
spawn a replacement child. Repeat the exact authorized value GET:

```bash
curl -sS -D tmp/value-retired.headers -o tmp/value-retired.html \
  "$SEON_BASE/agent/$AGENT_A/value?eval=$EVAL_ID&path=%5B%5D&offset=0"
```

Require HTTP `200`, the prior-session/eviction error projection, the recorded
source recomputation affordance, and no stale value bytes. Prove no child was
spawned and no retry occurred. In the browser, reopen the same disclosure and
require the unavailable error to replace the old value without a reload or
console error. A transport/core failure remains a distinct `503`; do not count
it as honest retirement.

## Abort conditions and graduation gate

Abort immediately and preserve evidence when any of these occurs:

- source revision, artifact digest, or build input changes after freeze;
- readiness drops or a core render/feed error appears;
- cross-agent and missing requests differ, or either contacts a child;
- a refusal crosses into authorization, child IPC, descent, or realization;
- output is bounded but visited work exceeds the fixed budget;
- entity drill calls a child or observes more than one database value;
- retirement spawns/retries, returns stale data, or becomes an undifferentiated
  `503`;
- an SSE frame lacks stable `#app-view` identity, uses unexpected gzip on the
  identity request, or `/data` contains raw `<pre>` output; or
- the browser reloads, opens a second feed, duplicates a value root, or logs a
  console error during disclosure, paging, hover, or retirement.

Stage 1.5 route/UI graduates only when the focused namespaces and full CLJS
suite pass, the server refusal and work counters pass, both identity SSE feeds
and a real broadcast are captured, the browser matrix passes, and the same
retained child changes from available to honestly unavailable after real
retirement—all at one frozen source revision.
