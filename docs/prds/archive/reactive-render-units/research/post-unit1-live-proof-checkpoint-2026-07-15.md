---
type: research
status: completed
tags: [research, web, database, flow]
---

# Post-unit-1 reactive live-proof checkpoint — 2026-07-15

## Decision

Run the first live checkpoint only after unit 1 has a coherent source freeze.
Use a retained native branch rooted at the then-current default head, not ACME
and not an ad-hoc pod. The branch gives the proof the production writer,
replica, router, gzip feed, and operator while `branch close` removes every
database/process side effect afterward.

The checkpoint combines two kinds of evidence:

- commits `365052f0` and `afc70d3f` remain the executable authority that an
  unrelated attribute performs zero replay, producer, serialization, and
  emission work and that candidate routing is a superset of replay-all; and
- the source-frozen branch proves that the same code reaches real gzip sockets,
  shares equivalent consumers, updates a helper-indirected production render,
  suppresses the unrelated per-agent header, and releases retained data.

This is an integration checkpoint, not unit-2 graduation. Root, canvas, debug,
and `/data` have not all moved to `seon.web.view-unit`.

## Dependency ledger

| Dependency or mechanism | Selected identity | Source read | Proof constraint |
|---|---|---|---|
| Datahike | maintained SHA `417649383c65e13f15ea41d394fb1ed742477965` in both root aliases | `reference-code/datahike` at that SHA; `query-attribute-dependencies` in `src/datahike/query.cljc`; listener replacement in `src/datahike/core.cljc` | Literal dependencies narrow; unsafe reads widen. The listener callback receives one immutable post-transaction value. |
| Konserve | maintained SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve` | Snapshot once; no retained unit may contain a database, connection, or entity view. |
| Datastar | client source SHA `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` | `reference-code/datastar/library/src/plugins/watchers/patchElements.ts` | One event may carry several complete stable-ID elements; outer morph selects each by `id`. |
| Datastar Clojure gzip example | SHA `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | `reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj` | Open and close are socket ownership events; tiny gzip updates must flush rather than wait for stream close. |
| Node gzip/SSE | runtime built-in `node:http` and `node:zlib` | `seon.web.datastar/open-feed!`, `patch-elements`, and `push-event!` | Response must be `text/event-stream` plus `Content-Encoding: gzip`; inspect the decompressed stream server-side. |
| Unit lifecycle | commits `365052f0` and `afc70d3f` | `seon.web.view-unit`, `seon.web.datastar`, and their focused tests | One plain state owns consumers, observations, reverse buckets, coordinate, and serialized output. Final close removes all of it. |

No dependency addition or recent-output LRU is justified by this checkpoint.

## Important interpretation of “unrelated”

The always-demanded `system-header` calls `seon.db/datom-count`, so every real
transaction can affect that unit. There is no honest transaction that is
unrelated to *all* currently demanded header units. The live falsifier is
therefore per unit:

- creating a temporary agent is related to `system-header`, including its
  helper-indirected `seon.render.system/fleet-summary` read; and
- the same transaction is unrelated to root's `agent-header`, whose
  `seon.derive/derive-state` inputs do not change.

The expected event contains a changed `#system-header` but no standalone
`#agent-view-header`. The exact zero-work claim for the latter remains the
counter-based focused test at `365052f0`; absence from a live event alone would
prove only output suppression.

## Source-freeze admission

The top-level agent owns this gate. Pause every source-editing lane whose files
enter the artifact, then record:

```bash
git rev-parse HEAD
git status --short
git diff --quiet -- deps.edn shadow-cljs.edn package.json package-lock.json \
  src script resources test
bin/test-cljs seon.web.view-unit-test seon.web.datastar-test
bin/seon restart
bin/seon status --edn

```

Do not count the checkpoint if a listed build input changes before the retained
branch reports ready. Protected unrelated evidence files may remain untracked;
name them in the record rather than staging or deleting them.

Use a unique branch name and capture its status instead of assuming its dynamic
web endpoint:

```bash
PROOF_NAME="rru-proof-$(date +%H%M%S)"
bin/seon branch open "$PROOF_NAME"
bin/seon branch status "$PROOF_NAME" --edn

```

Record the returned runtime cluster, database, branch, commit, `t`, pod PID,
and URL. Every later `eval_cljs` call uses the returned cluster-qualified root
id. Do not use a bare `root` while default and the proof branch both advertise
it.

## Dual-feed gzip probe

Set `SEON_URL` to the exact branch URL. Run this one-off stdin program in its
own terminal; it creates no repository runner. Commands are `post-agent`,
`close-a`, `close-b`, and `quit`.

```bash
SEON_URL=http://127.0.0.1:PORT node --input-type=module <<'NODE'
import http from 'node:http'
import {createGunzip} from 'node:zlib'
import {createHash} from 'node:crypto'
import {createInterface} from 'node:readline'

const base = new URL(process.env.SEON_URL)
const started = performance.now()
const streams = new Map()

function summary(name, event) {
  const ids = [...event.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1])
  const top_level_ids = event.split('\n')
    .filter((line) => line.startsWith('data: elements <'))
    .map((line) => line.match(/\sid="([^"]+)"/)?.[1])
    .filter(Boolean)
  const bytes = Buffer.byteLength(event)
  const sha256 = createHash('sha256').update(event).digest('hex')
  console.log(JSON.stringify({phase: 'event', name,
    elapsed_ms: Math.round(performance.now() - started), bytes, sha256, ids,
    top_level_ids}))
}

function open(name) {
  const path = `/agent/root/feed?view=rru-proof-${name}`
  const req = http.get(new URL(path, base), {headers: {accept: 'text/event-stream'}}, (res) => {
    console.log(JSON.stringify({phase: 'headers', name, status: res.statusCode,
      content_type: res.headers['content-type'],
      content_encoding: res.headers['content-encoding']}))
    const gunzip = createGunzip()
    let pending = ''
    gunzip.setEncoding('utf8')
    gunzip.on('data', (chunk) => {
      pending += chunk
      for (;;) {
        const split = pending.indexOf('\n\n')
        if (split < 0) break
        const event = pending.slice(0, split + 2)
        pending = pending.slice(split + 2)
        if (event.startsWith('event: datastar-patch-elements')) summary(name, event)
      }
    })
    res.pipe(gunzip)
  })
  req.on('error', (error) => console.log(JSON.stringify({phase: 'error', name,
    message: error.message})))
  streams.set(name, req)
}

function postAgent() {
  const body = 'purpose=reactive-render-live-proof'
  const req = http.request(new URL('/agents', base), {method: 'POST', headers: {
    'content-type': 'application/x-www-form-urlencoded',
    'content-length': Buffer.byteLength(body)}}, (res) => {
    let value = ''
    res.setEncoding('utf8')
    res.on('data', (chunk) => value += chunk)
    res.on('end', () => console.log(JSON.stringify({phase: 'post-agent',
      status: res.statusCode, agent_id: value.trim(),
      elapsed_ms: Math.round(performance.now() - started)})))
  })
  req.end(body)
}

open('a')
open('b')
createInterface({input: process.stdin, output: process.stdout}).on('line', (line) => {
  const command = line.trim()
  if (command === 'post-agent') postAgent()
  else if (command === 'close-a') streams.get('a')?.destroy()
  else if (command === 'close-b') streams.get('b')?.destroy()
  else if (command === 'quit') process.exit(0)
})
NODE

```

The first two events must have status 200, gzip plus event-stream headers,
byte-identical hashes, and one each of `app-view`, `system-header`, and
`agent-view-header` in their ID inventory. First-frame latency is at most 2,000
ms per stream and decompressed event size is at most 64 KiB.

## Registry evidence at each phase

After both initial frames, evaluate this single form through cluster-qualified
`eval_cljs`:

```clojure
(let [registry @seon.web.datastar/!feeds
      units (get-in registry
                    [:seon.web.view-unit/state :seon.web.view-unit/units])]
  {:seon.proof/view-ids
   (sort (keys (:seon.web.datastar/views registry)))
   :seon.proof/subscription-count
   (count (:seon.web.datastar/subscriptions registry))
   :seon.proof/listener-installed?
   (:seon.web.datastar/listener-installed? registry)
   :seon.proof/units
   (->> units
        (map (fn [[token unit]]
               {:seon.proof/token token
                :seon.proof/coordinate
                (:seon.web.view-unit/coordinate unit)
                :seon.proof/database-coordinate
                (:seon.web.view-unit/database-coordinate unit)
                :seon.proof/consumers
                (sort (:seon.web.view-unit/consumers unit))
                :seon.proof/observation-count
                (count (:seon.web.view-unit/read-observations unit))
                :seon.proof/observation-hash
                (hash (:seon.web.view-unit/read-observations unit))
                :seon.proof/output-bytes
                (js/Buffer.byteLength
                 (:seon.web.view-unit/serialized-element unit))
                :seon.proof/output-hash
                (hash (:seon.web.view-unit/serialized-element unit))}))
        (sort-by (comp pr-str :seon.proof/coordinate))
        vec)})

```

Expected: exactly two known views, one normalized subscription, two retained
header units, and both units name consumers `rru-proof-a` and `rru-proof-b`.
Save this as `before` evidence.

Enter `post-agent`. Both streams must receive byte-identical related events.
The top-level target inventory must contain `system-header`; it must not
contain `agent-view-header`. A structural `app-view` fallback may contain the
header as a nested ID and therefore remains visible in the complete ID
inventory. Run the registry projection again:

- `system-header` output hash changes and its helper-derived agent count rises;
- root `agent-header` observation and output hashes remain identical; and
- both unit coordinates advance to the branch's new complete coordinate.

This proves the helper-indirected production output, unchanged-unit
suppression, one shared derivation, and fanout over actual gzip sockets. In the
pod log, the matching `broadcast` record must name the transaction's changed
attributes, two connections, bounded targets, and `render-ms <= 500`. The
related frame must arrive within 1,000 ms of the completed POST and remain at
most 64 KiB decompressed.

Enter `close-a`, wait for `FEED CLOSE`, and rerun the projection. One view and
one subscription remain; each unit has only consumer `rru-proof-b`. Enter
`close-b`; within 1,000 ms the projection must show zero views, zero
subscriptions, and `seon.web.view-unit/empty-state`. `FEED CLOSE` must report
feed count zero and `listener-installed?` must be false.

## Real-browser check

After the server-side probe has fully released, create two agent-owned browser
tabs at the branch URL. Verify in each:

- one `#app-view`, `#system-header`, and `#agent-view-header`;
- Phosphor layout and stable IDs after one new proof agent is created;
- no console error or Datastar warning; and
- the static shim is not mistaken for SSE proof if the browser bridge returns
  503 for the long-lived stream.

Close both owned tabs and use the same registry projection to prove final
release again. The server-side gunzip transcript, not browser network
inspection, remains the transport authority.

## Evidence record

Retain one dated research appendix or checkpoint note with these fields:

- source HEAD, dirty-path inventory, artifact/version digest, dependency SHAs;
- default restart result and retained branch status/complete coordinate;
- focused test counts and exact retained log path;
- response headers, initial/related event latency, bytes, SHA-256, and target
  IDs for both streams;
- before/after/first-close/final-close registry projections;
- POST status and temporary agent id;
- matching `FEED OPEN`, `broadcast`, and `FEED CLOSE` log records, including
  changed attrs, connections, targets, and render milliseconds;
- two browser tab IDs, static DOM identities, screenshot paths, and console
  result; and
- teardown result from `bin/seon branch close "$PROOF_NAME"`, followed by
  proof that the branch runtime, routes, endpoints, attachment, and Datahike
  branch are absent while default remains ready.

## Shortest falsifiers

Stop and preserve evidence immediately if any of these occurs:

- source changes after the checkpoint digest;
- either feed lacks gzip/event-stream headers or an immediate patch frame;
- equal views produce two subscriptions, two producer outputs, or unequal
  bytes;
- the related write leaves `system-header` stale;
- the same write emits a standalone root `agent-view-header`;
- first close releases shared unit data, or final close retains any unit,
  observation, output, subscription, or view;
- initial frame exceeds 2,000 ms, related frame exceeds 1,000 ms, a frame
  exceeds 64 KiB, or matching broadcast render exceeds 500 ms;
- the browser has duplicate stable IDs or console errors; or
- branch close changes default or leaves a proof process/route/attachment.

## One remaining observability gap

Current live logs expose subscription-level targets and inclusive render time,
but no per-unit counts for candidate selection, observation replay, producer or
SCI invocation, serialization, suppression, and retained output weight. The
focused `365052f0` regression measures zero work by redefining those owners,
and this checkpoint can prove its integrated transport consequences, but a
live event's missing target cannot by itself distinguish zero work from equal-
output suppression.

That is a real blocker for a *standalone live* zero-work and per-unit cost
claim. Before unit-2 graduation, add bounded plain-data transition metrics to
the existing unit/feed owner, without a second registry or database facts.
Until then, report the combined automated-plus-live evidence honestly and do
not claim the live checkpoint alone measured zero internal work.
