---
type: research
status: active
tags: [research, ui, runtime, render]
---

# Render pipeline design — patches, keyframes, and 60 fps

## Verdict

Use **one revisioned composite package**, rendered and serialized once per
agent page:

```clojure
{:seon.render.package/revision 43
 :seon.render.package/base-revision 42
 :seon.render.package/keyframe-bytes <one complete Datastar event>
 :seon.render.package/delta-bytes <one changed-fragment Datastar event>
 :seon.render.package/keyframe-size 86239
 :seon.render.package/delta-size 437}
```

The render proc publishes the same immutable package object through one
`mult`; every tab has one `(sliding-buffer 1)` tap. A fast contiguous tab sends
the delta. A late, stalled, or displaced tab detects a revision gap and sends
the package's keyframe. There is no delta-only buffer and no accumulated patch
list: every pending value is independently able to repair the page.

Keep **stable-ID render-unit patches** as the default server granularity. Do not
build a generic Hiccup tree differ. Datastar already parses the fragment,
matches persistent IDs, skips equal subtrees, and changes only unequal DOM
nodes. The server should choose a smaller semantic render unit only where
measurement demands it. The first such unit is the active streamed reply:
patch its stable-ID leaf, while settled transcript content remains in cached
serialized units.

The measurements answer both sides of that decision:

- A 250-event transcript-block morph is already fast in Chrome:
  **1.2–1.5 ms p95**, and a complete server frame to 50 fast tabs is
  **2.39 ms p95**. Generic intra-block diffing is not justified by CPU at this
  size.
- The same block patch is about **85.5 KB**. At 60 updates/s to 50 tabs that
  is **244.71 MiB/s**, versus **1.25 MiB/s** for the measured 437-byte active
  row. Datastar's client morph avoids DOM churn, but cannot avoid parsing,
  walking, or transmitting bytes the server sent.
- At 2,500 events the block consumes **12.4–13.1 ms p95 in Chrome** and the
  server package consumes **9.51–10.44 ms p95**. At 5,000 events both exceed a
  16 ms budget. A hot block must therefore stay bounded—conservatively no more
  than about 1,000 fixture-equivalent events—or split/paginate before it reaches
  that size.

The http-kit write-state fork remains exactly the assumed interface. One
connection-owned `:io` writer sends at most one already-framed event, observes
the atomic pending-write state, and parks on that pending epoch's exact
drain-or-close completion. While it parks, its sliding-1 tap keeps only the
newest composite package. On resume, a gap forces a keyframe snap. No
`send!` boolean is interpreted as backpressure.

This is design and measured evidence only. No production source changed.

## Scope and dependency ledger

| dependency or mechanism | selected revision | source read |
|---|---|---|
| Datastar Clojure SDK and http-kit adapter | `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` (`v1.0.0-RC7`) | `libraries/sdk/.../api/{elements,signals,sse}.clj`; `adapter/common.clj`; `libraries/sdk-http-kit/.../adapter/http_kit.clj`; `adapter/http_kit/impl.cljc` |
| Datastar browser morph | `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` (`v1.0.0-RC.7-8-gbb9ed6fb`) | `library/src/plugins/watchers/patchElements.ts`; built `resources/public/js/datastar.js` |
| core.async + Flow | `dc35f3e0d7bc2eef502e77982f48641f025c8051` (`v1.10.874-alpha3`) | `flow.clj`, `flow/impl.clj`, `flow/impl/graph.clj`, `core.async.clj` mult/tap, `impl/buffers.clj` |
| http-kit write-state contract | pinned `70432d3ab3c9f23cb4672c7656d94fe8d71726d6` (`v2.9.0-beta2`); additive fork pending | `httpkit-write-path-2026-07-29.md` and its cited Java owners |
| current Seon pipeline | tree at `c124ef9f69293a8c38a301251edaa88f522e25d7` for the last `web.clj` change | `src/seon/render/web.clj`; channel construction in `src/seon/cluster.clj` |
| maintained N4 benchmark | current tree; original package evidence in `6dcda1ab9` + `4fa0c96f7` | `bench/seon/render_bench.clj`; `n4-contracts-2026-07-27.md`; `plan/unsettled.md` N4 ledger |
| context/render rulings | current plan | `plan/README.md` rulings 13–16: one database copy, universal renderer and walk unification, byte-identical churn ordering, freshness over cache |

The dependency names above are their owners' names. Internal transport is
channels, `mult`, Flow, and database facts; only the external SSE crossing is
called a wire.

## Source findings that constrain the design

### Datastar already performs the fine DOM diff

The Clojure SDK can put several complete element strings in one
`datastar-patch-elements` event
(`api/elements.clj:112-129`). With default `outer` mode and no selector, the
browser iterates the event's top-level children, finds each live target by the
child's ID, and morphs it (`patchElements.ts:125-153`).

The morph is not a blind `outerHTML` replace:

- it computes persistent, non-duplicate IDs;
- greedily matches children using persistent-ID sets
  (`patchElements.ts:231-437`);
- preserves/moves ID-addressed nodes where possible; and
- stops descending when `oldElt.isEqualNode(newElt)`
  (`patchElements.ts:587`).

Therefore a generic server-side Hiccup diff would duplicate Datastar's
tree-matching concern and create another walker beside the one universal
bounded-render discipline required by rulings 13–16. It could reduce bytes,
but semantic stable-ID fragments achieve that without inventing a second
generic tree algorithm.

Datastar signals are a distinct `datastar-patch-signals` event
(`api/signals.clj`). They remain appropriate for tab-local transient controls.
They are not page truth, not a keyframe substitute, and not part of the
revision chain below.

### The SDK currently serializes per connection

The SDK separates element data-line construction, SSE event construction, and
adapter sending:

- `elements/->patch-elements-seq` builds Datastar data lines;
- `sse/write-event!` builds the complete SSE event
  (`api/sse.clj:120-128`);
- `adapter/->build-event-str` uses one `StringBuilder`
  (`adapter/common.clj:194-203`); and
- the http-kit `SSEGenerator` calls its connection's send function under a
  per-generator lock (`adapter/http_kit/impl.cljc:76-96`).

Calling `patch-elements!` N times therefore frames N times. That API cannot
meet “render once, serialize once, mult the bytes” by itself.

For identity-encoded feeds, the render proc can use the SDK's pure builders
once, UTF-8 encode once, and put the resulting byte array in the composite
package. The writer then submits that same byte-array object to its http-kit
channel. A small adapter seam must expose prepared-event sending without
reframing; it must not create a second event grammar.

Compression is different. The SDK's gzip profile owns a stateful output stream
per connection. Tabs join at different times, so their compressed stream
histories differ and a ready-compressed event is not generally shareable. The
shareable package is the uncompressed Datastar event bytes; a compressed feed
would still do per-connection compression. That tradeoff is left to owner
review because this experiment measured identity encoding.

### A raw `mult` shares objects but has no replay

core.async documents that `mult` distributes one item to every current tap,
waits for each tap to accept, and drops values when there are no taps
(`core.async.clj:797-837`). Buffered taps accept without waiting for their
writer. The live REPL confirmed:

```clojure
{:sliding-kept :r2
 :mult-replayed-to-late-tap? false
 :same-object-to-both-taps? true}
```

This settles two design points:

1. N tabs can hold N references to one large byte array; they do not require N
   serialized copies.
2. A source sliding buffer plus `mult` cannot by itself give a late tab the
   last package. One process-local latest-package snapshot is required.

The render proc is the snapshot's only writer. Feeds only dereference the
immutable value during join. It is disposable derived memory, like equality
suppression—not a database fact, replay log, or second render owner.

### Buffer behaviors are materially different

The vendored implementations are short and decisive
(`impl/buffers.clj:18-79`):

- fixed buffers become full and park producers;
- dropping buffers retain the earlier buffered values and discard later
  arrivals; and
- sliding buffers remove the oldest value and retain the newest.

Freshness ruling 16 and the page-repair contract select sliding-1 for render
transport. A dropping buffer has the wrong preference. A fixed per-tab buffer
would let a slow browser hold up `mult`, then the render proc, then every tab.

### Workload placement is part of correctness

Flow defines `:io` as work that may block but should not perform extended
computation, and `:compute` as work that must never block
(`flow.clj:194-202,264-286`).

The split is therefore:

- SCI render, Hiccup admission/serialization, equality comparison, and
  Datastar event framing: `:compute`;
- connection read, http-kit send, and pending-write drain wait: `:io`; and
- channel fan-out: core.async's own mechanism.

The current `web/render-step` is tagged `:io` while it derives and serializes
pages (`web.clj:315-470`). That is current-state evidence, not the target
workload design. This report does not change it.

## Measured experiments

The committed, reproducible artifacts are:

- `tmp/render-pipeline/server_bench.clj`;
- `tmp/render-pipeline/client_morph_bench.mjs`; and
- `tmp/render-pipeline/results-2026-07-29.md`.

The maintained harness command was:

```text
clojure -M:test bench/seon/render_bench.clj --trials 2000
```

The extension commands were:

```text
clojure -M:test tmp/render-pipeline/server_bench.clj
await import('./tmp/render-pipeline/client_morph_bench.mjs')
```

Both extension experiments ran twice. The JVM was OpenJDK 26.0.1 with 18
available processors. The browser was installed Chrome 150.0.7871.187,
headless, running the vendored Datastar 1.0.0-RC.7 bundle.

### Correcting the headline

The N4 ledger still quotes the historical headline that admitting the
250-event whole page cost 7.5 ms before serialization. Package 1 already
retracted that interpretation after reading and repairing the predicate.
The maintained harness reproduced the corrected values:

| 250-event whole page | p50 | p95 | p99 | bytes |
|---|---:|---:|---:|---:|
| Hiccup admission | 0.012 ms | 0.017 ms | 0.038 ms | — |
| HTML serialization | 0.445 ms | 0.582 ms | 0.769 ms | 82,893 |

The 7.5 ms number is real history about a bad predicate, not a current frame
cost. The surviving 60 fps thesis is targeted bytes and bounded target size.

### Where the server budget goes

The extension fixture adds stable IDs and is 86,231 keyframe bytes at 250
events. Ranges below are the two-run p95 range:

| events | render hiccup | serialize transcript | frame keyframe | complete newest package |
|---:|---:|---:|---:|---:|
| 250 | 0.047–0.052 ms | 0.666–0.868 ms | 0.044–0.057 ms | 0.847–0.965 ms |
| 1,000 | 0.155–0.195 ms | 2.453–3.460 ms | 0.201–0.206 ms | 3.461–4.329 ms |
| 2,500 | 0.389 ms | 6.297–8.803 ms | 0.544–1.504 ms | 9.506–10.436 ms |
| 5,000 | 0.752–0.789 ms | 11.650–16.657 ms | 1.635–1.807 ms | 18.199–20.033 ms |

“Complete newest package” constructs the streamed-token Hiccup, serializes the
complete keyframe and block delta once, and frames both as Datastar events.
HTML serialization dominates. Event framing and Hiccup construction do not.

These are not SCI evaluation timings. They begin at representative returned
Hiccup, because the specific agent-authored renderer is workload-dependent.
The design budget must add its bounded SCI evaluation. The current package
already promises one evaluation per changed render unit; this report does not
invent a representative agent function to make that unknown disappear.

### Serialization sharing at 2, 10, and 50 tabs

This compares rebuilding the same 250-event package per tab with building it
once and distributing the same object through `mult`:

| tabs | per-tab serialize p50 | once + `mult` p50 | p50 speedup | once + `mult` p95 |
|---:|---:|---:|---:|---:|
| 2 | 1.312–1.503 ms | 0.702–0.977 ms | 1.5–1.9× | 0.956–1.134 ms |
| 10 | 6.779–8.025 ms | 0.704–0.990 ms | 8.1–9.6× | 0.916–1.222 ms |
| 50 | 31.783–42.479 ms | 0.636–0.765 ms | 50.0–55.5× | 0.872–1.171 ms |

Bare `mult` distribution of the already-built object was at most
**0.047 ms p95 at 50 taps**. The difference is the architecture decision: by
50 tabs, per-tab rendering/serialization already loses the 16 ms budget before
any socket work; shared serialization remains near 1 ms.

### Fast writer cost and bandwidth

Fast-path write timing submits already-framed bytes to continuously draining
loopback TCP sockets. It does not model a stalled peer; the settled write-state
contract owns that case.

The 250-event shapes were 437 bytes for an active-row patch, 85,532 bytes for a
transcript-block patch, and 86,239 bytes for a keyframe:

| tabs | active row write p95 | transcript block write p95 | keyframe write p95 |
|---:|---:|---:|---:|
| 2 | 0.019–0.020 ms | 0.061–0.068 ms | 0.067–0.073 ms |
| 10 | 0.038–0.039 ms | 0.264–0.265 ms | 0.259–0.266 ms |
| 50 | 0.227–0.241 ms | 1.208–1.262 ms | 1.105–1.213 ms |

One complete streamed-token server frame—new keyframe once, same object through
`mult`, selected delta submitted to all fast sockets—measured:

| tabs | active-row delta p95 | transcript-block delta p95 |
|---:|---:|---:|
| 2 | 0.941 ms | 0.861 ms |
| 10 | 0.911 ms | 1.302 ms |
| 50 | 1.172 ms | 2.387 ms |

The small difference at 2 tabs is noise around package construction, which both
paths perform. The 50-tab difference is the external bytes.

At 60 events/s:

| tabs | active-row patch | transcript-block patch | keyframe every event |
|---:|---:|---:|---:|
| 2 | 0.05 MiB/s | 9.79 MiB/s | 9.87 MiB/s |
| 10 | 0.25 MiB/s | 48.94 MiB/s | 49.35 MiB/s |
| 50 | 1.25 MiB/s | 244.71 MiB/s | 246.73 MiB/s |

Keyframes are not sent every event to healthy contiguous tabs; the last column
is the pathological snap-every-time comparison. It shows why a separate
small active fragment matters even though the 250-event block is CPU-fast.

### Real browser cost

The browser probe instruments Datastar's own
`DOMParser.prototype.parseFromString` call and ends in the
`MutationObserver` microtask after the synchronous morph. It excludes localhost
transport and includes parse, ID matching, equality checks, and DOM mutation.

| events | target | patch bytes | Chrome morph p95 |
|---:|---|---:|---:|
| 250 | whole page | 85,839 | 1.3–1.6 ms |
| 250 | transcript block | 85,559 | 1.2–1.5 ms |
| 250 | active token leaf | 87 | 0.1–0.2 ms |
| 1,000 | transcript block | 342,434 | 4.9–5.0 ms |
| 1,000 | active token leaf | 87 | 0.1–0.2 ms |
| 2,500 | transcript block | 859,184 | 12.4–13.1 ms |
| 2,500 | active token leaf | 87 | 0.2 ms |
| 5,000 | transcript block | 1,720,434 | 25.0–32.2 ms |
| 5,000 | active token leaf | 87 | 0.2 ms |

Datastar makes a fine server diff unnecessary for bounded, ordinary blocks:
the 250-event fragment is comfortably inside the frame. It does not make
unbounded hot blocks safe and does not reduce bandwidth. The correct response
is bounded rendering plus a semantic active fragment, not a generic Hiccup
diff.

## Hiccup to patch contract

### One serialization owner

The compute transform receives admitted Hiccup from SCI and maintains, per
agent page, an ordered map:

```clojure
{surface-id
 {:seon.render.fragment/html "<section id=...>...</section>"
  :seon.render.fragment/bytes <UTF-8 bytes>}}
```

For each render pass:

1. derive all affected render units from one immutable database value and the
   newest admitted partial snapshot;
2. serialize each affected unit once;
3. compare its bytes with the previous process-local bytes;
4. suppress equal units;
5. build `delta-bytes` as one Datastar event containing only changed complete
   stable-ID fragments;
6. build `keyframe-bytes` as one Datastar event containing every current
   complete stable-ID fragment; and
7. publish one composite package only when at least one unit changed.

Building the keyframe does not re-render or reserialize unchanged units. It
concatenates already serialized fragment bytes through the Datastar event
builder. This is why a semantic active-reply unit removes full-transcript
serialization from token churn.

The equality key remains the actual serialized fragment bytes, matching ruling
15 and the current `changed` contract (`web.clj:261-285`).

### Granularity rule

Default: **one patch fragment per render unit**, using the stable ID already
derived by `seon.render.block/surface-id`.

A renderer may deliberately expose smaller named fragments when all are true:

- the fragment has a stable unique ID;
- it changes independently at high frequency;
- losing an intermediate value is harmless because the keyframe contains the
  complete current page;
- measurement shows material byte or frame savings; and
- the fragment is a semantic rendering boundary, not the output of a second
  generic Hiccup walker.

The active streamed reply satisfies these conditions. Arbitrary descendants
do not. Once the reply settles, ordinary facts replace the partial and the
settled transcript rendering becomes the keyframe truth.

### Why not generic intra-block diffing

A generic server differ would need to:

- retain a second tree form or parse serialized HTML;
- define identity for anonymous children;
- duplicate Datastar's persistent-ID matching;
- decide insert/remove/move semantics already implemented by the browser; and
- reconcile another traversal with the universal value/entity walker.

The 250-event browser measurement provides no CPU justification. The one
material gap—external bytes—is solved more simply by making the genuinely hot
leaf addressable.

## Composite keyframe package

### Package invariants

For every package:

- `revision` is strictly increasing within one agent-page registration;
- `base-revision` names exactly the previous produced package;
- `keyframe-bytes` is a complete Datastar event for `revision`;
- `delta-bytes` transforms exactly `base-revision` into `revision`;
- both events use the same stable IDs and default `outer` morph semantics;
- byte arrays are immutable by convention after publication; and
- the package is process-local and disposable.

The revision is not a database basis transaction, commit ID, or database
coordinate. It is only a process-local presentation sequence used to detect
channel displacement. Reconnect correctness still comes from current database
facts, not from this number.

### Why delta and keyframe are one channel value

Two channels—one for deltas, one for keyframes—create a coherence race: a tab
can observe delta revision 44 with keyframe revision 43 or 45. Fixing that
would require another synchronization mechanism.

One immutable package makes the relationship structural. A sliding buffer can
discard the entire older package without producing a torn pair. The newest
value always carries the repair for itself.

### Keyframe coalescing rule

There is no accumulated patch chain and no time-based “force keyframe after N”
dial.

A writer chooses the delta only when:

```clojure
(and (= delivered-revision base-revision)
     (< delta-size keyframe-size))
```

Otherwise it sends the keyframe.

Thus a keyframe is forced by:

- initial join;
- any revision gap;
- any displaced package;
- any writer recovery whose delivered revision is unknown; or
- a delta whose bytes are no smaller than the keyframe.

Commit and partial bursts still use the existing event-driven coalescing floor:
payload-free database wakes and complete provider prefixes live in sliding-1
inputs, and one compute pass reads the newest inputs. A 16 ms presentation
floor may cap a token stream near 60 updates/s, but it remains a floor after an
observed event, never a poll. The keyframe rule itself uses no clock.

## Per-tab state machine

```text
                 latest package exists
        ┌──────────────────────────────────┐
        │                                  ▼
     JOINING ──send keyframe──────────▶ STREAMING
        │                                  │
        │ no package                       │ contiguous + smaller
        │ wait for first                   │ send delta
        └──────────────────────────────┐    │
                                     │    │ gap / delta >= keyframe
                                     ▼    ▼
                                   SNAP ──send keyframe──▶ STREAMING
                                     │
                    http-kit pending │
                                     ▼
                                   PARKED
                                     │ exact drained-or-close completion
                                     ▼
                          take newest sliding-1 package
                          then STREAMING or SNAP by revision
```

Detailed transitions:

| state | input | action | next |
|---|---|---|---|
| joining | socket open | tap the `mult` first, then dereference the shared latest package | joining |
| joining | latest package present | send its keyframe bytes; record revision | streaming or parked |
| joining | no package | offer one render interest and wait for the first package; do not render in the tab | joining |
| streaming | package revision `<= delivered` | ignore duplicate/replayed package | streaming |
| streaming | `base == delivered` and delta smaller | send delta | streaming or parked |
| streaming | gap or delta not smaller | select keyframe | snap |
| snap | selected keyframe | send keyframe; record its revision | streaming or parked |
| parked | write state pending | park the virtual thread on that exact pending epoch; tap continues sliding | parked |
| parked | drain | take the newest package and run the streaming revision test | streaming or snap |
| any | close | untap, discard per-tab revision, end virtual thread | closed |

The join ordering is race-safe:

1. tap before reading the latest package, so later packages cannot be missed;
2. send the dereferenced keyframe directly from the same immutable package;
3. then consume the tap; and
4. ignore any equal/older revision that raced with the initial send.

If a newer package arrived during the initial write, the sliding-1 tap retains
it. Its base comparison decides delta versus snap.

This replaces the current per-tab `page-of` initial derivation
(`web.clj:541-578`). Opening 50 tabs no longer causes 50 page derivations.

## Per-edge buffer and workload table

| edge or state | value shape | buffer/state | workload | why loss is free |
|---|---|---|---|---|
| Datahike listener → render interest | payload-free “look” | `(sliding-buffer 1)` | listener only offers; render is compute | any wake causes a fresh read of the newest immutable database value |
| provider fold → render partial | complete prefix with agent and run IDs | `(sliding-buffer 1)` | provider `:io`; render `:compute` | newer prefix supersedes older; terminal database fact supersedes all partials |
| render proc → package `mult` | composite delta + keyframe package | source `(sliding-buffer 1)` | `:compute` producer | newest package contains a complete repair for itself |
| package `mult` → each tab | the same composite package object | per-tab `(sliding-buffer 1)` | core.async delivery | revision gap forces the retained package's keyframe |
| latest-package join snapshot | immutable package per agent page | single-writer process-local atomic snapshot, no queue | compute writes; `:io` join reads | disposable; late tab gets current bytes, restart re-derives |
| tab → http-kit | one already-framed Datastar event | no Seon queue; at most one http-kit event remainder | `:io` writer | writer parks on the exact pending epoch; newer package stays in the tab's sliding-1 |
| http-kit pending → writer | pending bytes + drain-or-close completion | dependency-owned atomic state | virtual thread parks | completion cannot be lost because state and epoch are acquired atomically |

Rejected:

- **fixed per-tab buffer** — preserves obsolete deltas and can backpressure
  `mult`, coupling one slow tab to all tabs;
- **dropping per-tab buffer** — retains the older pending value and discards
  freshness;
- **separate keyframe and delta buffers** — can observe incoherent revisions;
- **unbounded delta queue** — violates freshness and the transport law; and
- **polling http-kit** — invents a clock where the dependency can publish its
  exact state transition.

## Backpressure against the settled write-state interface

The writer algorithm assumes the additive interface recommended by
`httpkit-write-path-2026-07-29.md`:

```clojure
{:http-kit.write/pending-bytes 262144
 :http-kit.write/drained       completion}
```

After sending one prepared Datastar event:

1. read the state acquired under the same attachment monitor that owns
   `toWrites`;
2. if pending bytes are zero, take the next package;
3. otherwise park on `drained`;
4. let the per-tab tap replace obsolete packages while parked; and
5. after drain, take one newest package and run the revision rule.

This bounds Seon's submissions to one event at a time and bounds http-kit's
user-space backlog to at most the remainder of that event plus framing. It
does not claim remote receipt. The operating system still owns its bounded send
buffer.

The current Datastar/http-kit generator returns http-kit's logical-open
boolean unchanged. That boolean is not used by this design for pressure,
completion, or retry.

## 60 fps conclusion

There are three separate budgets:

| boundary | 250-event evidence | conclusion |
|---|---|---|
| render + serialize + keyframe/delta frame | 0.847–0.965 ms p95 | ample compute headroom before adding the agent-specific SCI renderer |
| distribute + fast submit to 50 tabs | complete block frame 2.387 ms p95 including render/package | CPU headroom, but external bytes are high |
| Datastar parse + morph in each browser | block 1.2–1.5 ms p95; active leaf 0.1–0.2 ms | bounded blocks fit; fine leaf materially reduces wire and client work |

The server and browser numbers run on different threads and machines in a real
deployment, so adding their p95s is not a statistically valid end-to-end p95.
Each boundary independently fits at 250 events. Both fail as the hot fragment
approaches 5,000 events.

The target rule is:

- bounded ordinary render units may use one Datastar `outer` morph;
- the active streamed reply is a stable-ID fragment, because bandwidth—not
  DOM mutation—is the binding evidence at 50 tabs;
- settled transcript history is collapsed, paged, or split before a hot unit
  exceeds roughly 1,000 fixture-equivalent events; and
- the universal renderer's depth/breadth budgets remain the primary structural
  bound, consistent with rulings 13–16.

“60 fps” does not mean sending every provider token as its own event. Provider
prefixes are complete values on sliding-1, the render input coalesces, and the
newest frame wins.

## Loss and crash walk

### Dropped delta

Delivered revision 40; packages 41, 42, and 43 arrive while the writer parks.
The tap retains package 43 with `base-revision 42`. On drain,
`40 != 42`, so the writer sends keyframe 43. The page cannot remain at
“40 plus delta 43.”

The committed simulation offered revisions 1, 2, and 3 into sliding-1. Revision
3 remained; a consumer at 0 chose snap 3, while a consumer at 2 chose delta 3.

### Process crash

The socket closes. An incomplete SSE event is not a complete event the
Datastar watcher can apply. Reconnect joins a new process-local latest package
derived from current database truth. Transient provider partials are not
recovered; the terminal reply fact, if any, supersedes them.

### Render proc restart

The latest-package snapshot and equality cache disappear. The first current
interest derives a complete keyframe. No database migration or render replay is
required.

### Tab close while parked

http-kit's pending epoch completes as closed. The writer untaps and ends. No
timeout stands in for closure.

## Differences from the current pipeline

Current `web.clj` derives one complete
`{agent-id → {surface-id → html}}` snapshot, `mult`s it, lets every tab diff
against its own delivered map, and performs a separate initial `page-of`
derivation per connection (`web.clj:1-25,315-358,515-591`).

The target simplifies and changes that ownership:

- equality/delta selection moves entirely to the render proc;
- the proc builds one revisioned delta + keyframe package;
- tabs stop holding complete HTML maps and hold only delivered revision;
- initial feed paint reuses the latest package instead of calling `page-of`;
- one per-tab tap carries the composite package, not a delta-only value; and
- the writer performs no Hiccup or Datastar framing work.

The architecture target currently describes one sliding-1 tap per visible
render unit. The composite-package design instead uses one tap per tab so loss
of any subset of deltas is detected by one revision chain and repaired by one
keyframe. If approved, `docs/seon/architecture/ui.md` must change with the
implementation; this analysis lane does not edit target architecture before
owner review.

## Owner review decisions

1. **Approve the composite package.** One per-tab sliding-1 value carries both
   delta and full keyframe; separate delta/keyframe channels are rejected.
2. **Approve one single-writer latest-package snapshot.** Vendored `mult` does
   not replay to late taps. The snapshot is required to satisfy “last package,
   not re-rendered.”
3. **Approve semantic fine granularity only for hot leaves.** Default remains
   one stable-ID render-unit fragment and Datastar's own client morph. The
   active streamed reply becomes the first explicit finer fragment; no generic
   server Hiccup differ is built.
4. **Choose the bounded transcript policy.** This report recommends keeping a
   hot fragment at or below roughly 1,000 fixture-equivalent events, then
   collapsing/paging/splitting. The exact semantic boundary belongs with the
   universal renderer and transcript design.
5. **Choose identity versus compression for feeds.** Identity encoding can
   mult one prepared byte array exactly. Stateful per-connection gzip can share
   the uncompressed package but must compress per tab; that CPU/byte tradeoff
   remains unmeasured here.
6. **Approve the small prepared-event adapter seam.** The Datastar SDK remains
   the event grammar owner, but its current generator frames inside each
   connection. The target needs a supported way to send an already-framed
   Datastar event and then inspect http-kit's additive write state.
7. **Choose initial document behavior.** The strongest “never re-render per
   tab” form is a constant shim whose feed paints the latest keyframe. Embedding
   the cached keyframe in the initial HTML can avoid a blank shell but must
   still reuse the cached bytes rather than call the renderer.

The recommended answers are 1–3 and 6 as written, a 1,000-event conservative
hot-unit bound for 4, identity encoding for loopback with a separate
compression experiment before remote defaulting for 5, and cached-keyframe
embedding for 7 if it can preserve one serialization owner.

## Chunk 2 — history synthesis before another protocol

The earlier designs were not one feed with five names. Git history and the
colocated reports show five materially different ownership/delivery
incarnations before this report's proposed sixth.

### Sources added to the dependency ledger

- `plan/reference/jvm-render-design-2026-07-26.md` records the pod resource
  multiplier, the separate JVM feed, the zero-consumer cache contradiction,
  the lazy-value containment hole, and the unbounded http-kit socket queue.
- `research/f2-live-render-proof-2026-07-28.md` proves the current cluster
  pipeline on a fresh boot with three real SSE sockets.
- `research/old-ui-quarry-2026-07-29.md`, especially L01–L08 and L14, records
  the pod and interim JVM feed mechanisms and what the fresh pipeline already
  replaced.
- `research/query-invalidation-2026-07-29.md` distinguishes exact E/A/V
  interests, captured Datahike dependency plans, query-result caching, and
  unconditional wake plus equality suppression.
- `research/n4-contracts-2026-07-27.md` supplies the corrected N4 morph costs
  and the block-targeting decision.

Only gaps in those reports were then read from `src-old` and Git:

- pod direct/gzip write acceptance, newest-pending replacement, normalized
  subscriptions, first-paint ordering, and view replacement in
  `src-old/seon/web/datastar.cljs`;
- interim JVM `.clear` + `.offer` mailboxes and virtual drain threads in
  `src-old/seon/web/feed.clj`;
- the transition commits `c6c8d0ff0`, `b19275dca`, `19be862e2`,
  `1cdb048c3`, `065542731`, `2e372027d`, and `fb1ce96d8`; and
- the streamed-prefix transport-law rationale in
  `research/flow-inventory-2026-07-28.md`.

### The five prior generations and the sixth inheritance

“Push” below means the page may first GET a static shim, but current render
state arrives unsolicited on the long-lived SSE response. None of the first
five generations implemented a browser-held revision deciding between a
keyframe GET and a delta subscription.

| incarnation | delivery model | evidence | what ended it | lesson inherited by generation 6 |
|---|---|---|---|---|
| 1. June pod whole-page feed (`c6c8d0ff0`, 2026-06-27) | push: GET shim, then gzip Datastar SSE of the complete stable-ID page | historical feed source; N4 replay of the actual whole-page shape | Per-change scope was the page. N4 later measured one 287-byte row at 0.004 ms versus the old 82,893-byte page at 0.460 ms: 289× bytes and 115× serialization CPU. Inputs/feed owners also had to sit outside their own morph target to survive updates. | Stable IDs and reconnecting SSE were sound; page-wide server work was not. |
| 2. July pod reactive/render-unit feed (`b19275dca` through `b6961bac5`) | push: normalized equivalent subscriptions, captured dependency plans, equality suppression, changed-surface morphs, newest pending event per socket | pod `render-read`, `observe-connection!`, subscription registry, and direct/gzip writer; query-invalidation old precedent | The Great Deletion removed the CLJS self-host/Promise tier. Before deletion, an unrelated selected commit still rebuilt and serialized the whole normalized view before equality: the retained five-surface lower bound was 50,558 bytes and 1.094 ms p50; ten surfaces were 181,078 bytes and 3.880 ms p50 / 6.861 ms p95. Per-connection gzip and lifecycle machinery survived after shared render. | Capture dependency semantics from Datahike, share equivalent work, and suppress bytes; do not port the pod execution/lifecycle shape. |
| 3. Supervised JVM `/data` feed (`1cdb048c3`, 2026-07-23) | push: one JVM reactive view, per-connection newest-only `ArrayBlockingQueue`, virtual drain thread, SDK/http-kit SSE | `src-old/seon/web/feed.clj`; JVM design report | It was an interim second web/render process and only served the data view. O14 dissolution merged web rendering into the cluster JVM, eliminating the cross-process reason to store derived snapshots. Its Seon mailbox was bounded, but the selected http-kit queue was still unbounded. | A complete latest value is the correct lossy mailbox value; process boundaries do not justify duplicate feed owners. |
| 4. Fresh per-tab block feed (`065542731`, 2026-07-27) | push: one listener, derivation, byte comparison, and latest mailbox per tab; one Datastar morph per changed block | N4 live socket proof: initial two-block paint 218 bytes, one changed block 102 bytes, unchanged projection 0 bytes | Multiple tabs still duplicated the page derivation. Commit `2e372027d` deleted the per-tab listener/mailbox and moved derivation to one cluster proc plus `mult`. | Block targeting is the right patch boundary; tab count must not multiply rendering. |
| 5. Current cluster Flow pipeline (`2e372027d`, 2026-07-28) | push: `listen!`/interest wake → one render proc → equality suppression → `mult` of a complete page snapshot → per-tab sliding-1 tap → per-tab diff/write | F2 live proof: 6 initial block morphs, one changed-block morph, byte-identical two-tab output, six-block current repaint on reconnect | Not deleted. It is the surviving owner. Its open seam is that each tab still derives initial `page-of`, stores a complete delivered map, and can safely slide only complete snapshots—not delta-only values. | Strengthen this owner in place: serialize one revisioned complete repair and changed fragments once; never create another renderer or feed registry. |
| 6. Proposed revisioned composite package (this report) | push with pull-like recovery: one SSE path; connected tabs get deltas, and any join/gap selects the same package's keyframe | Chunk 1 server/browser measurements and loss proof; Chunk 2 comparison below | Owner review pending. | The sixth design inherits block IDs, Datahike-derived interest, equality suppression, cluster-wide render sharing, complete newest repair, and reconnect-as-repaint. |

The history rejects two tempting summaries. Selective invalidation did exist,
but it did not make delivery pull-based; it selected which shared push
computation woke. Conversely, a complete newest mailbox did exist, but it did
not make initial paint share serialized bytes across tabs.

### The streamed-partial arc

The pod-era partial was
`:seon.ai.attempt/partial-text`: a cardinality-one, unindexed, no-history
database projection published on a cadence and read by the transcript. That
kept “one database path” by making high-churn presentation state a database
value.

The transport law corrected the premise. The attempt row and settled reply are
facts; a complete text/token prefix is supersedable presentation. Moving the
prefix onto `(sliding-buffer 1)` removed the partial transaction builder,
schema attributes, publisher virtual thread, mailbox, cadence state, and
terminal retraction coupling. The live F2 proof found zero installed
`:seon.ai.stream/*` attributes.

The first channel version still exposed why “loss is free” must be proved at
the semantic boundary: a shared sliding-1 stream could displace agent A's
clear with agent B's newer prefix. `fb1ce96d8` removed the clear message.
Prefixes now carry run identity, a terminal plan/error/close fact supersedes
them, and any database-interest repaint is facts-only and clears transient
prefix state. A delayed prefix is rejected when its immutable database value
already contains the terminal fact. Reconnect paints facts and never replays a
half reply.

Generation 6 inherits exactly that rule: a channel may lose a delta only when
the retained value contains, or can address, a complete repair. It does not
make a delta durable merely to avoid reasoning about a gap.

## Explicit client pull variant

The honest explicit-pull design is:

1. The browser holds the applied page revision and complete-package hash.
2. `GET /render/keyframe` returns the already-rendered, already-serialized
   newest keyframe plus revision and ETag/hash. The handler never calls SCI,
   Hiccup rendering, or serialization.
3. The browser opens the diff SSE with its applied revision/hash. Deltas carry
   `base`, `revision`, and the new complete hash. The SSE `id` is the revision.
4. A contiguous delta is applied. A base/hash mismatch is discarded; the
   browser pulls the newest keyframe, installs its revision/hash, and resumes
   diff consumption.
5. On reconnect the browser pulls current first. Nothing is replayed.

For step 4 to be true, the frontend must own a custom stream parser that can
validate before invoking a morph. Datastar's `@get` ignores event names that do
not start with `datastar`, and a `datastar-patch-signals` event applies its
signals immediately; neither can conditionally veto the following
`datastar-patch-elements` event. The bundle does not expose its internal
patch-elements watcher as a public “apply these validated bytes” action.
Without a custom parser/morph seam, the server must retain the tab's delivered
revision and select delta versus keyframe itself—which is the composite
design—or turn the SSE into wake signals followed by one patch GET per update.

This is not just “plain GET plus SSE.” A naive
`GET keyframe → subscribe to future diffs` misses an update committed between
the two requests. The experiment enumerated the update before GET, between GET
and subscribe, and after subscribe: only **2/3** order classes were safe.

A repaired pull protocol was safe in **3/3** by adding SSE admission:
the server compares the supplied revision with its current revision and either
opens the stream or emits `pull-required`. An alternative safe ordering is to
open/tap the SSE before GET and retain its newest delta while the GET completes.
Both alternatives add a two-request client state machine. The composite join
was safe in **3/3** with its existing ordering—tap first, then read the shared
latest package—without a second route or control event.

Datastar does preserve the last SSE event id during retries of one `@get`
request (`bundles/datastar.js` updates the `last-event-id` request header), but
the server has no replay log. Therefore built-in retry cannot repair the page
from an event id alone. Reconnect still needs a keyframe, either in the SSE
stream or through the extra GET.

### Pull edge and buffer table

| edge | value/buffer | why loss is free | added obligation |
|---|---|---|---|
| listener/provider → render proc | unchanged sliding-1 look/complete prefix | fresh derivation or newer prefix supersedes | none |
| render proc → shared latest keyframe | one atomic immutable revision/hash/bytes value | GET reads one complete current value; restart re-derives | GET must never trigger per-tab rendering and must reject/refresh a stale cached revision |
| render proc → diff `mult` | revisioned delta package, source sliding-1 | it is presentation only; a skipped base cannot be applied | package must expose chain metadata to custom browser stream code |
| diff `mult` → each SSE writer | per-tab `(sliding-buffer 1)` | custom browser parser detects a base/hash gap before morphing and pulls | Datastar `@get` cannot provide this gate |
| SSE writer → http-kit | unchanged one pending prepared event | exact drain-or-close completion parks the writer | a gap can be known only after the unusable delta crosses the socket |
| browser gap → keyframe GET | ordinary request/response, no queue | response is one complete newest keyframe | race-safe admission/order, cancellation, retry, and visible failure live in custom client logic |

The pull server is stateless only in the narrow sense that it deletes one
delivered-revision scalar per tab. It still owns:

- the SSE connection, tap, and http-kit pending-write state;
- the one shared latest serialized keyframe, because deriving on GET violates
  render-once/serialize-once;
- the current revision/hash used to admit the diff stream; and
- the same render/equality/delta computation as the composite design.

It therefore does not eliminate the shared “last buffer.” It moves gap
selection from the server writer to browser code.

### Measured protocol comparison

`tmp/render-pipeline/pull_variant_bench.clj` uses SDK-built Datastar events,
SHA-256 package hashes, 60 revisions of a 250-event page, and sliding-1
delivery strides. It counts application-protocol bytes only; TCP/TLS and
browser scheduling are excluded.

Fixture:

| item | bytes |
|---|---:|
| keyframe Datastar event | 26,384 |
| stable-ID hot delta | 149 |
| pull revision/hash control event | 141 |
| minimal keyframe GET request + response control | 253 |

The simpler fixture is intentionally independent of Chunk 1's 86 KB
presentation fixture. Its result is a protocol comparison, not a replacement
for the 60 fps timings.

| tabs | writer delivery stride | composite bytes / 60 revisions | pull bytes / 60 revisions | pull overhead | pull recoveries after initial |
|---:|---:|---:|---:|---:|---:|
| 2 | 1 (fast) | 70,452 | 87,900 | 17,448 (24.8%) | 0 |
| 10 | 1 (fast) | 352,260 | 439,500 | 87,240 (24.8%) | 0 |
| 50 | 1 (fast) | 1,761,300 | 2,197,500 | 436,200 (24.8%) | 0 |
| 50 | 5 | 17,150,150 | 17,491,300 | 341,150 (2.0%) | 12/tab |
| 50 | 20 | 5,276,950 | 5,371,800 | 94,850 (1.8%) | 3/tab |

Fast pull pays because the 141-byte revision/hash control is almost the
149-byte hot delta. Under a gap, the stateless writer first sends the newest
delta and metadata; the browser discards them and performs a GET for the same
shared keyframe. The composite writer knows its own delivered revision and
sends the keyframe directly. Both designs send one keyframe per gap in this
model; pull adds one discarded delta and one request/response control exchange.

### Trade table

| criterion | composite package on one SSE | explicit keyframe GET + diff SSE |
|---|---|---|
| render/serialize sharing | one render and serialization; one shared keyframe | same requirement; GET cannot derive per tab |
| server per-tab page state | one delivered revision scalar plus existing writer state | no delivered revision scalar; existing connection/tap/write state remains |
| browser protocol | Datastar `@get` applies the selected prepared event | custom SSE parser plus revision/hash, conditional morph, gap, GET, cancellation, and resume state; or one patch GET per update |
| join race | tap before latest-package read; one path | naive GET-first loses one of three order classes; repaired form needs admission or SSE-first buffering |
| gap traffic | server selects keyframe directly | unusable delta/control crosses first, then GET keyframe |
| built-in Datastar fit | ordinary patch-elements SSE | `@get` ignores custom control events and cannot veto a patch; event id supplies no replay |
| reconnect law | stream sends current keyframe; nothing replays | GET current keyframe; nothing replays |
| freshness over cache | latest-package read after tap | GET must validate current revision and coordinate with SSE admission |
| failure surface | one long-lived response | two requests whose partial success and retry ordering must compose |
| state removed | none | one scalar per tab |

## Chunk 2 recommendation

Keep the **revisioned composite package on one SSE path**.

Explicit pull is a valid loss-free protocol after its race is repaired, and it
does make the browser the authority on whether its DOM revision is current.
But that is not a simplification for Seon:

- the shared fully serialized keyframe remains mandatory;
- the server still owns every expensive and backpressured part;
- the only server state removed is one revision scalar already colocated with
  the socket writer;
- Datastar's automatic retry has no delta replay source;
- the repaired join adds admission/control semantics or SSE-first buffering;
  conditional delta application also needs a custom client stream/morph seam;
  and
- measured payload is never lower in the tested fast/stalled schedules.

The composite package already gives the frontend pull-like recovery without a
second delivery path: every retained package says “apply this delta if your
server-known delivered revision is contiguous, otherwise receive this current
full copy.” It also keeps the recovery choice next to the only authority that
knows which bytes were actually submitted and drained.

The owner decision remaining is therefore narrow: accept one per-tab delivered
revision scalar as the price of one-path recovery. If removing that scalar is
more important than the extra browser protocol, the repaired explicit-pull
variant above is the coherent alternative; the naive GET-then-subscribe form
must not be implemented.
