---
type: research
status: completed
tags: [research, database, flow, web]
---

# Phase 1 coordinated baseline — 2026-07-13

## TL;DR

The default cluster is currently serving from the expected three-process local
shape—Shadow watcher, JVM writer, and Node pod—and its supervisor registrations,
Unix sockets, HTTP routes, and gzip Datastar feeds are live. The audit was
captured from `f3f2108e` while another lane was dirty. Immediately afterward,
the owner requested one shared checkpoint, so all visible work was committed at
`3e0e0bff` and the tree became clean. That makes the work readable and
recoverable, but it is **not yet a reproducible archival baseline**:

- the running pod and its original loader predate the settled plan commit;
- hot reload has subsequently changed the running CLJS program without an
  artifact manifest tying it to one Git tree;
- the live writer runs from `:simd:fork-deps:writer`, while `writer-uber` builds
  from `[:writer]`; and
- the only writer jar predates both current build inputs and cannot certify the
  current writer.

The present UI is responsive at the static route boundary, but the first root
agent frame is about 22,649 estimated tokens because all expanded primary faces
and all compact rail faces are constructed before `data-show` hides them. The
canvas rail face is slightly larger than its primary face. This is direct
baseline evidence for the already-planned lazy-unit and no-focused-duplicate
work, not a new mechanism or design.

No reset, restart, build, database mutation, browser action, test, ACME action,
branch operation, staging, or commit was performed while collecting the audit.
The later shared checkpoint and focused-test follow-up are recorded separately
below.

## Baseline identity and coordination boundary

The audit began from:

```text
branch: codex/runtime-reliability-refactor
commit: f3f2108e947c192172ce4b85cb2769bd961d5008
commit time: 2026-07-13T10:11:14-04:00
subject: docs(runtime): settle local reliability scope
```

Commands:

```bash
git branch --show-current
git rev-parse HEAD
git show -s --format='commit=%H%ncommit-time=%cI%nsubject=%s' HEAD
git status --short
git diff --name-only
git diff --cached --name-only
```

The index was empty. The working tree was not clean: ACME, plan,
REPL-autosuggest/needle, context, AI dispatch, database/schema, and matching
test files belonged to another active lane. This audit neither edited nor
staged those paths. A clean-tree build or destructive proof would therefore
have mixed ownership and was deliberately deferred.

## Post-audit shared checkpoint and focused proof

After evidence collection, the owner explicitly requested that every visible
change be committed before refactoring continued. The complete shared snapshot
was checked with `git diff --check` and committed as:

```text
3e0e0bff chore: checkpoint shared runtime lanes
```

That commit contains the ACME context migration, plan/schema and AI dispatch
work, and REPL fair-scoring research. `git status --short` was empty immediately
afterward. It is a coordination checkpoint, not yet the annotated pre-removal
archive ref.

The current CLJS test bundle was then rebuilt once and three directly affected
behavioral namespaces passed:

| Test namespace | Tests | Assertions | Result |
|---|---:|---:|---|
| `seon.schema-test` | 7 | 43 | pass |
| `my.plan-test` | 39 | 249 | pass |
| `seon.ai.dispatch-test` | 7 | 30 | pass |

The test build compiled 626 files and reported eight existing inference
warnings. The focused plan run also emitted an extremely large volume of
Datahike/Konserve trace logging; that is direct evidence for the planned test
signal/noise and runtime-tier cleanup. The ACME build and live proof remain with
the ACME lane and were not run here.

## Default-cluster process truth

The authoritative process names are `cljs-watch`, `wire-server`, and `pod`.
An initial probe using the informal names `shadow` and `writer` correctly
reported no such registrations; the canonical-name probe produced:

```bash
bin/seon status pod wire-server cljs-watch
```

```text
● pod          pid=49548  started=2026-07-13T13:46:54Z
● wire-server  pid=51638  started=2026-07-13T03:09:13Z
● cljs-watch   pid=26551  started=2026-07-13T01:45:28Z
pod port: 7890 -> http://127.0.0.1:7890
```

All three registrations carried PID, OS-start stamp, session ownership,
recorded command, and supervisor start time under `tmp/proc/<name>/`. Their
recorded commands were:

```text
cljs-watch: clj -M:cljs watch client
wire-server: clojure -M:simd:fork-deps:writer --backend file --db-name default
             --path data/clusters/default/store
             --req-sock tmp/seon-cluster-default-req.sock
             --pub-sock tmp/seon-cluster-default-pub.sock
             --repl-port 7891
             --repl-port-file tmp/seon-writer-repl-port-default
pod: npm run css:build && exec env SEON_FS_ROOT=/Users/sean/src/seon
     SEON_FS_READ_ONLY=1 node out/client/main.js
```

Three one-second `ps` samples were observational only:

| Process | Instantaneous CPU | Observed RSS | Interpretation |
|---|---:|---:|---|
| Shadow watcher | 0.2–2.8% | 1,733–1,985 MiB | fluctuated during concurrent source edits/build activity; not an idle profile |
| Node pod | 0.0–0.1% | 555 MiB | stable over this short sample |
| JVM writer | 0.0% | 622 MiB | stable over this short sample |

This proves process presence, not CPU, heap, event-loop, GC, or grown-database
budgets. In particular, the watcher RSS movement is an observation, not a
causal conclusion.

The writer owned both default Unix sockets and loopback port 7891; the pod
owned loopback port 7890. A bounded `nc -U` connection to the request socket
succeeded. The current pod log records its internal boot start at
`13:46:55.029Z`, HTTP listen at `13:47:00.803Z`, and `auto-boot ready` at
`13:47:00.807Z`. This is one uncontrolled startup observation, not the required
cold/warm benchmark.

## Live HTTP and feed evidence

The static probe was:

```bash
for route_path in / /agents /agent/root /data; do
  curl -sS --max-time 5 -o /dev/null \
    -w 'status=%{http_code} type=%{content_type} start=%{time_starttransfer}s total=%{time_total}s\n' \
    "http://127.0.0.1:7890$route_path"
done
```

One run returned:

```text
/           status=200 text/html; charset=utf-8 start=0.006801s total=0.006839s
/agents     status=200 text/html; charset=utf-8 start=0.003719s total=0.003747s
/agent/root status=200 text/html; charset=utf-8 start=0.004437s total=0.004460s
/data       status=200 text/html; charset=utf-8 start=0.117615s total=0.117669s
```

A separate coordinated probe observed approximately 15 ms, 2 ms, and 250 ms
for `/`, `/agents`, and `/data`. These values vary with concurrent work; both
runs show route readiness, not a performance budget.

The feed proof used Node's built-in `http` and `zlib`: request with
`Accept-Encoding: gzip`, pipe through `createGunzip`, stop after the first
blank-line-terminated SSE frame, and report `(quot frame-character-count 4)`.
This is the required server-side check because the controlled browser bridge
does not proxy long-lived SSE reliably.

```text
/agent/root/feed       200 gzip datastar-patch-elements 22,649 tokens 299 ms
/agents/feed           200 gzip datastar-patch-elements  1,245 tokens  74 ms
/agent/root/debug/feed 200 gzip datastar-patch-elements  8,126 tokens 632 ms
```

The bounded connections logged matching `FEED OPEN` and `FEED CLOSE` entries,
with `:seon.web.feed/released? true` and the connection count returning to zero.
An independent run produced the same frame token estimates within one token and
slower first-frame times, which confirms shape while also showing why these are
observations rather than budgets.

The root frame's approximate marker-to-marker slices were:

| Face | Surface | Estimated tokens |
|---|---|---:|
| primary | dashboard | 2,467 |
| primary | transcript | 4,838 |
| primary | canvas | 4,241 |
| primary | plan | 1,290 |
| rail | dashboard | 2,643 |
| rail | transcript | 503 |
| rail | canvas | 4,431 |
| rail | plan | 1,067 |

The remaining frame is shell/header overhead. This matches the current source:
`agent-view` materializes every surface, emits a primary panel and rail button
for each, and relies on `data-show` only after construction. The focused face is
therefore duplicated in the payload even though the browser hides one copy.

The debug frame had 12 collapsed details. Its displayed context accounting was:

```text
exact prompt 37,765 tokens; system 499; namespaces 15,808;
dashboard-live 17; canvas 2,231; core-faults 107;
instrumentation-gaps 990; plan 93; transcript 17,886.
```

HTML twins were present for dashboard, plan, and transcript. There was no
default skills section or skills text in the debug context. This proves the
requested default omission, while also proving that root's namespace and
transcript context remain large and the instrumentation-gaps block is active.

The controlled browser loaded the static root shim but remained at `loading…`,
which is the documented browser-bridge SSE limitation rather than feed failure.
The same browser successfully rendered `/data`: 3,768 datoms were shown, a
selected `seon.eval` detail was bounded to 50 rows, and no next-page link was
present for that selection.

## Writer namespace and dependency closure

A static `clojure.tools.reader` walk of all `src/**/*.clj` and `.cljc` namespace
declarations, starting at `seon.server.boot` and following only repository-local
`:require`/`:use` edges, reproduced the committed audit exactly:

```text
seon.ai.tokens
seon.db.datahike.schema
seon.db.id
seon.embed
seon.schema
seon.schema.internal
seon.server.boot
seon.server.broadcast
seon.server.codec
seon.server.reactive
seon.server.registry
seon.server.store
seon.server.wire
```

That is 13 namespaces; deleting the unused second reactive system reduces it to
12. This is source closure, not dependency/classpath closure.

Commands used for the dependency basis were:

```bash
clojure -Stree -M:simd:fork-deps:writer
clojure -Spath -M:simd:fork-deps:writer
bb -e '(require (quote [clojure.edn :as edn]))
       (let [b (edn/read-string (slurp ".cpcache/3960494174.basis"))]
         (prn {:libs (count (:libs b))
               :classpath-roots (count (:classpath-roots b))
               :argmap (:argmap b)}))'
```

The live basis contained 188 resolved libraries and 194 classpath roots. Its
argument map correctly selected the maintained Datahike SHA
`67934f650fae30924ac115c899cd3412d90dcacb`, Konserve SHA
`df6818d43ea3363a808cd051c0d68917f1b987a9`, `src-secondary`, the SIMD/native
JVM flags, Proximum, Transit, and Google GenAI.

It also inherited all 30 base direct dependencies. The resolved writer tree
therefore includes paused or unrelated application/tool dependencies such as
Integrant, http-kit, hato, Chassis, markdown-clj, clj-kondo, cljfmt,
libpython-clj, tech.ml.dataset, konserve-jdbc, and SQLite. The writer's source
closure is small; its dependency closure is not.

## Artifact and build truth

The live writer command uses the intended composition:

```text
-M:simd:fork-deps:writer
```

`build.clj:59` instead constructs its writer basis with only:

```clojure
(b/create-basis {:project "deps.edn" :aliases [:writer]})
```

Although the build manually copies `src-secondary`, that does not make its
dependency basis equal to the live maintained fork composition. This remains a
real packaging defect.

Observed artifact/input timestamps and SHA-256 digests were:

| Path | Modified | SHA-256 |
|---|---|---|
| `target/seon-wire-server-standalone.jar` | 2026-06-22 11:38 -04:00 | `2b9c9d48f685000dce80ab2a55b688d0924d3177cf48eeba1670546963f2022b` |
| `build.clj` | 2026-07-12 19:07 -04:00 | `8ba5b1efa75c54958c38fc1c873988f5333b450590629f3ba231cbc3794f9a61` |
| `deps.edn` | 2026-07-12 21:20 -04:00 | `921c9f4b99cb66b4901841de4ba7d731b9c1d38a033b4ca22ebed858817c2110` |
| `out/client/main.js` | 2026-07-13 10:05 -04:00 | `6e36edb02424eb768183602536591ed05d8213b942494d94ce24fc702724ffb8` |
| `out/test/test.js` | 2026-07-13 10:01 -04:00 | `3b2e0c1a364370d7512a26b4c460cf512d513ace959b10d2ce870bd23e25af17` |

The jar manifest names `seon.server.boot` and the jar contains both Proximum
and the soon-to-be-deleted reactive implementation, but its age makes it stale
evidence. Neither the writer jar nor `out/client/main.js` is tracked. There is
no published manifest binding a complete CLJS chunk closure, CSS, dependency
basis, source tree, and running PID to one digest.

The Node pod started before commit `f3f2108e`; later hot reloads changed its
program. The loader's timestamp also predates that commit, and current
`shadow-cljs.edn` differs from `HEAD` because of the other lane. The latest
watcher tail ends in successful 531-file builds, but transient build failures
occurred while that lane edited files. This proves incremental compilation is
working; it does not prove a clean reproducible artifact.

## Existing focused test doors

No test was run during the read-only audit itself. The post-audit focused runs
are recorded above.

| Door | Current behavior | Baseline limitation |
|---|---|---|
| `bin/test-cljs --test=<ns-or-var>` | compiles the `:test` build, then filters the Node run | the compile still sweeps every `-test$` namespace |
| `bin/test-cljs --no-build --test=<ns-or-var>` | genuinely fast runtime focus using `out/test/test.js` | no freshness/digest proof; current bundle can be stale |
| `bin/test-parser` | sub-second Babashka parser tests | intentionally non-authoritative inner loop only |
| `bin/test <ns>...` | Kaocha focus through `:simd:fork-deps:test` | paused/broad JVM dependency and discovery surface |
| `bin/test-clj` | symlink to `bin/test` | second name, not a separate authority |

The tree currently has 113 CLJS test files and 69 CLJ test files. There is no
`bin/test-writer` door. Writer-focused tests exist under `test/seon/server/`
for boot, broadcast, IDs, protocol, receipts, replay, registry, temporal reads,
and wire behavior, but they are reached through the broad JVM test basis.

The useful current CLJS runner already has important behavioral safeguards:
one bounded process, a final-summary completeness requirement, real
`cljs.test` failure detection, and the core-fault gate. The next step is to
split its compile/discovery closure and add artifact freshness, not replace
those protections.

## Log observations

- No `SEON-CORE-FAULT`, `auto-boot FAILED`, unhandled-rejection, address-in-use,
  or fatal marker appeared in the current pod/writer/watcher logs.
- The writer reports multiple SLF4J providers. This follows directly from the
  broad dependency basis; it is observed, not yet assigned a runtime cost.
- The writer reports that the existing database format lacks precomputed
  subtree counts and query planning is using heuristic estimates until reindex.
- A pod hot reload scanned 763 functions and refreshed 169 instrumentation
  gaps; a later reload still scanned 763 while refreshing 34 gaps. This is
  observed global reload work and supports the planned incremental
  instrumentation audit; the baseline does not claim it is the dominant cost.
- The watcher ended green after several transient failures caused by concurrent
  edits. Those historical failures are not current readiness failures.

## Proof still requiring a coordinated clean lane

The following cannot honestly be inferred from the serving stack:

- current-commit cold and warm `up` timing;
- a clean canonical CLJS build and complete artifact digest;
- a clean writer uberjar from the exact live dependency basis plus preflight;
- fresh-database boot, ordinary-agent birth, existing-agent resume, and crash
  recovery;
- transaction commit, lost-reply retry, replay, and reader catch-up against a
  reset proof database;
- multi-form non-fail-fast evaluation;
- root-only context, root navigation, and skill import/restart behavior beyond
  the read-only context observation above;
- a real canvas form with input, button, durable write, feed invalidation, and
  visible update;
- browser reconnect, two-tab isolation, debug open/closed cost, and responsive
  layouts;
- repeatable idle/open-feed CPU, writer heap/GC, Node heap, event-loop delay,
  render/SCI counts, and RSS baselines; and
- the archival ref itself.

## Proposed minimal Phase 1 exit checklist

- [ ] Coordinate the other lane to a commit/handoff and select one clean Git
  commit with an empty index and working tree for the default cluster.
- [ ] Record canonical process commands, dependency bases, maintained fork
  SHAs, and source namespace closure from that commit.
- [ ] Build CLJS and writer artifacts from that commit in a clean dependency
  state; publish one manifest of input, dependency, output, and launch digests.
- [ ] Prove the writer artifact uses the same `:simd:fork-deps:writer` basis as
  local execution and passes bounded preflight/boot/commit/replay/drain checks.
- [ ] Run only the smallest focused CLJS and writer behavioral gates needed for
  boot, birth/resume, transaction receipt/replay, Datastar rendering, and one
  canvas form; record final-summary completeness and no core-fault markers.
- [ ] On the authorized default database, observe cold boot, warm boot, one
  ordinary birth, one resume, root view, multi-form batch, skill importer,
  HTTP, gzip feed, browser action, and canvas update from the same artifacts.
- [ ] Capture comparable idle and active CPU, heap/GC, event-loop delay, RSS,
  feed/render/SCI work, and first-frame token budgets with no concurrent edit
  activity.
- [ ] Create the annotated pre-removal tag or protected archive branch at that
  proven commit and add the concise archive pointer.

Phase 1 exits only when that archived commit—not today's mixed hot-reloaded
process state—can still start, birth/resume, commit/replay, render, and process
a canvas form.
