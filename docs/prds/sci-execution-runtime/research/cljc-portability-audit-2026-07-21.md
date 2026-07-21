---
type: research
status: active
tags: [research, architecture]
---

# CLJC portability audit — `src/seon` and `src/my`

## Executive verdict

The owner direction is strongly supported.

Excluding the diffusion subsystem and W5 death-row code bands, the audit covers **148 files / 83,681 LOC**.

| Classification | Files | LOC | Share |
|---|---:|---:|---:|
| PORTABLE-NOW | 34 | 7,972 | 9.5% |
| PORTABLE-WITH-SEAM | 40 | 25,122 | 30.0% |
| ALREADY-CLJC | 37 | 18,839 | 22.5% |
| EDGE | 37 | 31,748 | 37.9% |
| **Total** | **148** | **83,681** | **100%** |

Target outcome:

- **111/148 files, about 51,933 LOC (62%)**, can belong to the portable canon after bounded edge extraction.
- **37 files / 31,748 LOC** should remain platform-specific.
- Of the 37 current `.cljc` files, only **26 / 10,029 LOC** are honestly portable throughout.
- **11 current `.cljc` files / 8,810 LOC** contain false or partial portability: unguarded `await`, whole-platform conditional bands, or one-platform implementations behind a `.cljc` extension.
- Because seam files include code that would move into edge namespaces, the likely final portable canon is approximately **49–51K LOC**, not the full 51,933-LOC ceiling.

The greatest return is not in porting infrastructure. It is in making the JVM SCI host run the same:

- plan, KB, canvas, and toolkit operations;
- context and transcript derivation;
- rendering, UI, and warnings;
- schema, transaction, and reconciliation laws;
- eval receipt construction;
- error-value classification and presentation.

Transport, HTTP/SSE, subprocess, provider SDK, self-host compiler, Datahike authority, and process supervision remain edges.

## Scope boundary

Excluded:

- W5 death-row portions of `seon.eval`, `seon.execution`, `seon.execution.host`, and `seon.execution.runtime`;
- analyzer-info production machinery;
- `seon.diffusion.*`, `seon.worker-eval`, `seon.worker-validator`, and the current DiffusionGemma provider.

The surviving portions of the four W5 split owners are addressed separately below. No files were modified.

## Classification key

- **P** — PORTABLE-NOW: direct promotion, with at most small reader conditionals.
- **S** — PORTABLE-WITH-SEAM: portable rules dominate, but acquisition, IO, clocks, resolution, or publication must move to an edge.
- **E** — EDGE: genuinely platform-bound.
- **C** — ALREADY-CLJC and genuinely portable.
- **C⚠** — ALREADY-CLJC but only partly or falsely portable.
- Leverage: **VH**, **H**, **M**, **L**.
- Effort: **S**, **M**, **L**.

## Complete file inventory

### `src/my`

| File | Ext | Class | Interop inventory / seam | Leverage | Effort |
|---|---|---|---|---:|---:|
| `my/blob.cljs` | cljs | E | Node `crypto/fs/path`; sync archive IO, fsync/rename, `js/Error`, `js/Date`, DB await | M | L |
| `my/blob/schema.cljc` | cljc | C | No interop; schema data only | H | S |
| `my/canvas.cljc` | cljc | C⚠ | Guarded `js/Buffer`, but 8 unguarded awaits in `show!`, `clear!`, `pinned`, `state`, `save!` | VH | M |
| `my/data.cljs` | cljs | P | One awaited `db/query`; reducers pure | H | S |
| `my/kb.cljc` | cljc | C⚠ | Guarded integer/date shims; 16 unguarded DB/embed awaits | VH | M |
| `my/kb/shared.cljs` | cljs | P | Four DB awaits; no platform objects | H | S |
| `my/ns.cljs` | cljs | S | DB acquisition plus `ctx/install!`; discovery/card logic pure | H | M |
| `my/plan.cljc` | cljc | C⚠ | Date constructors guarded; 69 unguarded awaits across 31 functions | VH | L |
| `my/plan/internal.cljc` | cljc | C⚠ | Unconditional `cljs.reader`; 10 awaits; large pure DAG/compiler/rollup core | VH | L |
| `my/skills.cljc` | cljc | C⚠ | Guarded filesystem implementations; unguarded `js/Math.round`; 8 DB/context awaits | H | M |
| `my/ui.cljs` | cljs | P | Pure hiccup and AI-twin construction | VH | S |

### `src/seon/agent`

| File | Ext | Class | Interop inventory / seam | Leverage | Effort |
|---|---|---|---|---:|---:|
| `agent.cljs` | cljs | S | About 50 DB/derive awaits; one `js/Error`; entity schemas, transaction builders, create/mint/delegate logic otherwise portable | VH | L |
| `agent/ctx.cljs` | cljs | S | Node `fs/crypto`, `process.cwd`, `Intl`, `Buffer`; 10 DB awaits; extract file/timezone/hash/acquisition | VH | L |
| `agent/ctx/canvas.cljs` | cljs | P | Five DB awaits; no platform interop | VH | M |
| `agent/ctx/menu.cljs` | cljs | P | Five DB awaits; pure menus/cards | VH | M |
| `agent/ctx/namespaces.cljs` | cljs | P | Eight DB awaits; `cljs.reader`; `js/Number.MAX_SAFE_INTEGER` | VH | M |
| `agent/ctx/ns_name.cljc` | cljc | C | Pure namespace-name predicates | M | S |
| `agent/ctx/render_fns.cljs` | cljs | S | Awaited selected-function invocation; formatting pure | H | S |
| `agent/ctx/subagents.cljs` | cljs | S | `js/Date`, `.getTime`; five DB awaits | H | M |
| `agent/ctx/transcript.cljs` | cljs | S | Node load/memory metrics, Date formatting, `toFixed`, three `Promise.all`, DB awaits | VH | L |
| `agent/ctx/typeahead_steps.cljs` | cljs | P | DB awaits; `cljs.reader`; rounding/`toFixed` shims | M | M |
| `agent/ctx/usage.cljs` | cljs | P | Pure parse/format/render | H | S |
| `agent/ctx/warnings.cljs` | cljs | S | Date arithmetic plus DB acquisition | H | M |
| `agent/debug.cljs` | cljs | S | DB awaits and incidental `Promise.all`; generated repro text emits `.then` | H | M |
| `agent/fs.cljs` | cljs | E | Node filesystem capability implementation | L | L |
| `agent/fs/internal.cljs` | cljs | E | Node path/fs throughout | L | M |
| `agent/fs/match.cljc` | cljc | C | Pure deterministic matching/splicing | H | S |
| `agent/home.cljs` | cljs | P | Four DB awaits; home namespace/require derivation pure | VH | S |
| `agent/internal.cljs` | cljs | P | Pure shared transforms | M | S |
| `agent/lifecycle.cljs` | cljs | S | DB awaits, Date creation/comparison; transitions portable | VH | M |
| `agent/loop.cljs` | cljs | E | Promise serialization, timers, listeners, wake loop, child-retirement handling | L | L |
| `agent/message.cljs` | cljs | P | DB awaits and Date timestamps; transaction/routing logic portable | VH | M |
| `agent/message/internal.cljs` | cljs | P | Two DB awaits; `js/Date. 0` barrier | H | S |
| `agent/run.cljs` | cljs | S | DB/message awaits, Date arithmetic, one `js/Error`; run fences portable | VH | L |
| `agent/runtime.cljs` | cljs | E | Execution-host session resume and Promise callback | L | S |
| `agent/schedule.cljs` | cljs | S | `Intl`, JS calendar mutation, `parseInt`, Date getters/setters; cron rules portable | H | M |
| `agent/search.cljs` | cljs | S | Awaited rg/graph capabilities; contract and shaping portable | H | M |
| `agent/search/internal.cljs` | cljs | E | Ripgrep binary, Node fs, subprocess, JSON, `RegExp` | L | M |
| `agent/shell.cljs` | cljs | S | Awaited subprocess boundary; envelope/recording logic portable | H | M |
| `agent/shell/internal.cljs` | cljs | E | Process completion, Promise chains, timing | L | M |
| `agent/testrun.cljs` | cljs | P | One parse-int shim and one DB await | M | S |
| `agent/turn.cljs` | cljs | E | LLM/host transport, abort controller, attempt coordination | L | L |
| `agent/web.cljs` | cljs | S | Awaited web/blob/db operations; request policy and projections portable | H | M |
| `agent/web/internal.cljs` | cljs | E | DNS, fetch, streams, abort, DOM/readability, WHATWG URL | L | L |

### `src/seon/ai`

| File | Ext | Class | Interop inventory / seam | Leverage | Effort |
|---|---|---|---|---:|---:|
| `ai.cljs` | cljs | S | URL/header objects, retry-after Date parsing, numeric parsing, Node SHA, DB sync; schemas/config resolution dominate | VH | L |
| `ai/anthropic.cljs` | cljs | E | Anthropic Node SDK, streaming, JS error objects | L | M |
| `ai/dispatch.cljs` | cljs | S | Promise facade only; selection/registry data portable | H | S |
| `ai/generate_code.cljs` | cljs | S | DB awaits, scheduler/spawn, `Promise.all`, Date; DAG/state logic portable | VH | L |
| `ai/openai_compat.cljs` | cljs | E | OpenAI SDK, async iterator, streaming/abort | L | L |
| `ai/provider.cljc` | cljc | C | Pure provider-locality data/schema | M | S |
| `ai/tokens.cljc` | cljc | C | Honest bounded-printer branches; common public API | H | S |
| `ai/typeahead.cljs` | cljs | S | DB/worker awaits and Date writes; policy/assembly/projections portable | H | L |

### Core, configuration, errors, host, database

| File | Ext | Class | Interop inventory / seam | Leverage | Effort |
|---|---|---|---|---:|---:|
| `client.cljs` | cljs | E | Bun composition root, Node IO, process lifecycle, Promises, startup/quiescence | L | L |
| `client/schema.cljc` | cljc | C | Portable process-launch schemas | M | S |
| `code.cljc` | cljc | C | Pure tagged-code predicates/accessors | M | S |
| `config.cljs` | cljs | S | Node fs/env acquisition, parsing shims; schemas, defaults and policy resolution portable | VH | M |
| `content_hash.cljc` | cljc | C | Honest JVM `MessageDigest` vs Node crypto implementation | H | S |
| `db.cljs` | cljs | E | Bun database session, reconnect/listen/cancel, Promise transport | L | L |
| `db/backend.clj` | clj | S | JVM `File`, path hardening, UUID; backend data rules portable | L | M |
| `db/branch.cljc` | cljc | C⚠ | Database-value helpers portable; `head`/`at` are CLJ-only Datahike | H | S |
| `db/datahike/schema.clj` | clj | P | Pure Malli→Datahike transformation; Java class literals are the only branch | H | M |
| `db/executor.clj` | clj | E | JVM virtual threads, core.async, monitor wait/notify, executor lifecycle | — | L |
| `db/id.cljc` | cljc | C | Real dual-platform API; pure candidate manifests mixed with CLJ and CLJS allocation adapters | H | L |
| `db/id/schema.cljc` | cljc | C | Portable identity/allocation schemas | H | S |
| `db/internal.cljs` | cljs | S | Pure tx/schema/ref normalization plus AsyncLocalStorage scope functions | VH | M |
| `db/process.cljc` | cljc | C | Portable provenance identity data | M | S |
| `db/program.clj` | clj | S | Pure reconciliation compiler; only `acquire-current` performs Datahike query | H | M |
| `db/protocol.cljc` | cljc | C | Genuine shared protocol; guarded Future/Promise, URI/Uint8Array, Transit forms | VH | S |
| `db/registry.clj` | clj | E | Datahike connection/branch/history/restore lifecycle throughout | — | L |
| `db/restore.cljc` | cljc | C⚠ | Portable proof data; most service functions hidden under CLJS conditional | H | M |
| `db/restore/schema.cljc` | cljc | C | Portable restore schemas | M | S |
| `db/restore_admin.clj` | clj | P | Pure result projection/validation | M | S |
| `db/restore_admin/schema.cljc` | cljc | C | Portable admin schemas | M | S |
| `db/server.clj` | clj | E | JVM process, NIO/files, streams, env, shutdown | — | L |
| `db/transport/uds.cljc` | cljc | C⚠ | JVM/BB NIO selector implementation; sibling `.cljs` supplies Bun namespace | — | L |
| `db/transport/uds.cljs` | cljs | E | Bun UDS, typed arrays, sockets, Promise multiplexing | — | L |
| `db/writer.clj` | clj | E | Datahike/Proximum authority, transactions, history, pools, listeners | — | L |
| `demo.cljs` | cljs | P | No interop | L | S |
| `derive.cljs` | cljs | S | DB awaits, incidental `Promise.all`, Date arithmetic; FSM/status rules portable | VH | M |
| `dev/docstring.clj` | clj | S | Pure rules plus file scan/slurp edge | L | M |
| `dev/markdown.clj` | clj | S | Pure parsing/fixes plus vault/file acquisition | L | L |
| `dev/restore.clj` | clj | P | Pure restore state machine; JVM SHA implementation only | M | S |
| `dev/restore/schema.cljc` | cljc | C | Portable schemas | M | S |
| `dev/runtime_id.cljc` | cljc | C | Portable runtime addressing/selection | M | S |
| `embed.clj` | clj | E | JVM Gemini client, Datahike/Proximum/Konserve, file lifecycle | — | L |
| `embed.cljs` | cljs | E | Bun feature gate and async DB facade; shared contract should be extracted | M | L |
| `embed/preflight.clj` | clj | E | JVM module/env checks, Gemini and throwaway Datahike/Proximum test | — | M |
| `error.cljs` | cljs | S | Error maps mixed with V8 stacks, AsyncLocalStorage, Promise persistence, console/exit | VH | L |
| `error/instrument.cljc` | cljc | C | Genuine error-envelope logic; guarded class/catch forms | VH | S |
| `error/sci.clj` | clj | E | SCI contexts, SCI stacktrace/vars, JVM Throwable/ArityException | H at host edge | M |
| `eval/bootstrap_cache.cljs` | cljs | E | Node bootstrap cache plus `cljs.js/load-analysis-cache!` | — | L |
| `eval/internal.cljs` | cljs | S | Pure receipt transactions; only cljs `db/cas-assert` dependency blocks promotion | VH | S |
| `host.clj` | clj | E | JVM SCI/UDS server, threads, Futures, cancellation, sampling | — | L |
| `host/context.clj` | clj | E | SCI contexts, wrapper registry, UDS pool, threads, filesystem corpus discovery | — | L |
| `host/graduate.clj` | clj | E | SCI vars/JIT/compiled Clojure graduation | H at host edge | M |
| `host/record.clj` | clj | P | Pure tools.reader→corpus transaction builders; guarded Throwable access needed | H | S |
| `indexing.clj` | clj | E | CLJS analyzer/compiler macro and classpath resources | — | L |
| `instrument.cljc` | cljc | C⚠ | Nearly every definition is `#?(:cljs ...)`; JS function-object mutation and Promise wrappers | H only after redesign | L |
| `items.cljs` | cljs | P | Schema declarations only; promote with `result` | M | S |
| `launch.cljc` | cljc | C⚠ | Descriptor canon portable; env/decode/load-time descriptor is CLJS-only tail | H | M |
| `log.cljs` | cljs | S | Portable event/filter/format logic plus Node fs/console/Date sink | M | M |
| `platform.cljs` | cljs | E | Deliberate Bun env/path leaf | L | S |

### Rendering, UI, warnings, state

| File | Ext | Class | Interop inventory / seam | Leverage | Effort |
|---|---|---|---|---:|---:|
| `handlers/eval.cljs` | cljs | S | URLSearchParams, URI/JSON encoding; eval rendering otherwise pure | H | M |
| `handlers/fn.cljs` | cljs | P | Pure string/hiccup projection | H | S |
| `handlers/message.cljs` | cljs | S | JS Date formatting and dependency on misplaced message-label helper | H | S |
| `handlers/ns.cljs` | cljs | P | Pure sorting/anchors/hiccup | H | S |
| `handlers/schema.cljs` | cljs | P | Only CLJS catch form | H | S |
| `handlers/test.cljs` | cljs | P | Pure status/source rendering | H | S |
| `render.cljs` | cljs | S | Global var lookup, cljs pprint, EDN decode, URL/JSON, fault/config access | VH | L |
| `render/canvas.cljs` | cljs | S | Date locale formatting and cljs-only EDN decoder dependency | VH | M |
| `render/chat.cljs` | cljs | P | Date type check/format shim | H | S |
| `render/schema.cljs` | cljs | P | Schema registrations only | H | S |
| `render/surface.cljs` | cljs | P | Pure; depends on promotable `web/view_unit` | H | S |
| `render/system.cljs` | cljs | S | Two awaited DB queries; fleet summary/rendering pure | VH | S |
| `render/value.cljc` | cljc | C | Genuine sampler core; JVM/JS number, buffer, cycle and error branches | VH | M for full parity |
| `ui/agent_view.cljs` | cljs | P | Pure hiccup | H | S |
| `ui/clojure.cljs` | cljs | P | Portable String methods; conditional catch needed | H | S |
| `ui/header.cljs` | cljs | P | Pure hiccup | M | S |
| `ui/html.cljc` | cljc | C | Genuine portable HTML serializer | H | S |
| `ui/markdown.cljs` | cljs | P | Pure parser/hiccup generation | H | S |
| `warn.cljs` | cljs | S | `cljs.reader`, function-name access, EDN decode, compiled-symbol lookup | VH | M |

### Web, runtime, REPL, support

| File | Ext | Class | Interop inventory / seam | Leverage | Effort |
|---|---|---|---|---:|---:|
| `web/brand.cljs` | cljs | S | Node CSS file read and async DB boot sync; branding data/projection pure | L | M |
| `web/datastar.cljs` | cljs | E | Gzip streams, SSE, controllers, backpressure, timers, Promises | L | L |
| `web/debug.cljs` | cljs | E | WHATWG Request/Response/URL and async feed handlers | L | M |
| `web/reactive/call.cljs` | cljs | E | HTTP parsing plus remote host invocation | M | L |
| `web/reactive/transform.cljs` | cljs | P | Pure postwalk; SHA, URI encoding and Date need narrow branches | M | M |
| `web/router.cljs` | cljs | E | Bun/WHATWG request routing and reactive-interest lifecycle | L | L |
| `web/serve.cljs` | cljs | E | Bun HTTP/files, Node fs/path, request bodies, timers, operator endpoints | L | L |
| `web/value.cljs` | cljs | S | Promise aggregation around portable queries/projection | M | S |
| `web/view_unit.cljs` | cljs | P | Base64url encoding only | M | S |
| `runtime/admission.cljs` | cljs | S | DB publication and CLJS instrumentation edge; state/projection transitions pure | H | M |
| `runtime/lifecycle.cljc` | cljc | C | Portable lifecycle schemas | M | S |
| `runtime/recovery.cljs` | cljs | S | DB/blob acquisition and publication; recovery compiler/evidence projections pure | H | L |
| `reactive.cljs` | cljs | E | Promise/timer/listener scheduler and process-local registrations | L | L |
| `repair.cljc` | cljc | C | Genuine portable repair transformations | H | S |
| `repair/candidates.cljc` | cljc | C⚠ | Pure candidate ranking plus unguarded async `pick-winner`; diffusion Levenshtein dependency | H | M |
| `repl.cljs` | cljs | E | Retained self-host compiler state and bootstrap Promise path | — | L |
| `repl/autocomplete.cljs` | cljs | S | Pure selection/coverage logic mixed with Node fs/crypto/git/export | M | L |
| `repl/internal.cljc` | cljc | C | Genuine portable text→forms parser | VH | S |
| `result.cljs` | cljs | P | Schema registration only | M | S |
| `retry.cljc` | cljc | C | Portable retry state machine; CLJS timer executor is a bounded edge band | M | S |
| `route.cljs` | cljs | P | Pure route schemas/transactions | M | S |
| `schema.cljc` | cljc | C | Genuine two-platform registry/projection implementation | VH | S |
| `schema/internal.cljc` | cljc | C | Pure Malli-form mechanics | H | S |
| `state.cljs` | cljs | S | Pure reconciliation compiler plus async DB acquisition/transact/retry | H | M |
| `subprocess.cljs` | cljs | E | Bun spawn/process groups, stream pumping, timeout cancellation | L | L |
| `test/runner.cljs` | cljs | E | `cljs.test`, Promise/thenable completion and CLJS analyzer behavior | L | L |
| `time.cljc` | cljc | C | Honest Java Date vs JS Date ISO conversion | M | S |

## Existing `.cljc` quality

### Good house-style examples

- `seon.schema.cljc`
- `seon.retry.cljc`
- `seon.ai.tokens.cljc`
- `seon.content-hash.cljc`
- `seon.db.protocol.cljc`
- `seon.render.value.cljc`
- `seon.repl.internal.cljc`
- `seon.ui.html.cljc`
- `seon.error.instrument.cljc`
- `seon.time.cljc`

These put reader conditionals at leaf differences: reader implementation, exception type, clock/hash implementation, byte representation, or bounded executor.

### Existing `.cljc` defects or overgrowth

| File | Problem |
|---|---|
| `my.canvas.cljc` | Unguarded native CLJS `await` |
| `my.kb.cljc` | Unguarded DB/embed awaits |
| `my.plan.cljc` | 69 unguarded awaits |
| `my.plan.internal.cljc` | Unguarded awaits plus unconditional `cljs.reader` |
| `my.skills.cljc` | Unguarded awaits and unguarded JS rounding |
| `db.branch.cljc` | Ordinary database-value helpers portable; Datahike branch operations CLJ-only |
| `db.restore.cljc` | Large CLJS-only service band |
| `db.transport.uds.cljc` | JVM/BB NIO implementation masquerading as shared source |
| `instrument.cljc` | CLJ loads an effectively empty namespace |
| `launch.cljc` | Large portable descriptor canon with one-platform runtime tail |
| `repair/candidates.cljc` | Unguarded `await` in `pick-winner` |

`render/value.cljc` is genuinely portable but asymmetric: its sampler core runs on both platforms, while several AI/HTML preparation APIs remain CLJS-only. That is unfinished parity, not a false extension.

An unqualified `await` is especially dangerous in `.cljc`: the CLJ projection can resolve `clojure.core/await`, whose meaning is waiting on Clojure agents, not “identity over a synchronous host call.” This can fail semantically rather than at read time.

## House style for the conversion

The repository exemplars establish four rules.

1. Put reader conditionals at the smallest real platform difference:

```clojure
#?(:clj  (java.util.Date.)
   :cljs (js/Date.))
```

2. Keep business rules in one ordinary function. For files with many DB calls, separate acquisition from transformation instead of scattering dozens of conditional awaits.

3. A CLJS caller may await an edge; a JVM SCI caller invokes its synchronous wrapper and receives ordinary data:

```clojure
(defn derive-view [rows]
  ;; one portable data transformation
  ...)

;; CLJS edge
(defn ^:async acquire-view [request]
  (derive-view (await (db/query request))))

;; JVM/SCI edge
(defn acquire-view [request]
  (derive-view (db/query request)))
```

4. Never introduce a Promise/future abstraction into the common contract merely to make the signatures look alike. The shared contract is the resolved value or error value.

For a single isolated call, an expression conditional is acceptable:

```clojure
#?(:cljs (await (db/query request))
   :clj  (db/query request))
```

For `my.plan`, `agent.ctx`, `derive`, `state`, and similar large owners, explicit acquisition edges are cleaner and keep one transformation canon.

## Ranked conversion order

### Wave 1 — small leaves and blockers

1. `result.cljs`, then `items.cljs`
2. `eval/internal.cljs`
3. `agent/home.cljs`
4. `my/ui.cljs`
5. `my/data.cljs`
6. `route.cljs`
7. `web/view_unit.cljs`
8. `ui/markdown.cljs`, `ui/clojure.cljs`, `ui/header.cljs`
9. `render/schema.cljs`, `render/chat.cljs`, `render/surface.cljs`
10. `handlers/{fn,ns,schema,test}.cljs`
11. `host/record.clj`
12. `db/datahike/schema.clj`

These are mostly S effort and establish the portable dependency floor.

### Wave 2 — toolkit behavior

1. Repair `my.plan.internal.cljc`
2. Repair `my.plan.cljc`
3. Repair `my.kb.cljc` and promote `my/kb/shared.cljs`
4. Repair `my.canvas.cljc`
5. Repair `my.skills.cljc`
6. Promote `my/ns.cljs`

This is the W5/U5 host-toolkit spine.

### Wave 3 — render and context parity

1. `render/canvas.cljs`
2. Complete `render/value.cljc` parity
3. `render.cljs`
4. `warn.cljs`
5. `handlers/eval.cljs` and `handlers/message.cljs`
6. `render/system.cljs`
7. `agent/ctx/{canvas,menu,namespaces,usage}.cljs`
8. `agent/ctx/transcript.cljs`
9. `agent/ctx.cljs`

### Wave 4 — shared runtime rules

1. `db/internal.cljs`
2. `derive.cljs`
3. `state.cljs`
4. `config.cljs`
5. `error.cljs`
6. `agent/message.cljs`
7. `agent/run.cljs`
8. `agent/lifecycle.cljs`
9. `agent.cljs`
10. `runtime/admission.cljs` and `runtime/recovery.cljs`

### Wave 5 — lower-leverage extraction

- `db/program.clj`
- `log.cljs`
- `web/reactive/transform.cljs`
- `repl/autocomplete.cljs`
- capability facades `agent/{shell,search,web}.cljs`
- development utilities.

Do not hold the conversion spine behind HTTP/SSE, subprocess, provider, or writer edges.

## Edge-namespace map

| Portable canon | Bun/CLJS edge | JVM/host edge |
|---|---|---|
| `my.plan`, `my.kb`, `my.canvas`, `my.data` | Async `seon.db` wrapper calls | Synchronous SCI wrapper registry |
| Blob metadata/schema | `my.blob.io.bun` | `my.blob.io.jvm`, if host-local archive access is required |
| `seon.agent.ctx` | `seon.agent.ctx.platform.cljs` | `.clj` peer |
| `seon.agent.ctx.transcript` | `seon.agent.ctx.system-metrics.cljs` | host metrics peer or omitted data |
| `seon.agent.run`/message/lifecycle/schedule | Existing expanded `seon.time` | Same portable clock vocabulary |
| `seon.config` | `seon.config.io.cljs` | `seon.config.io.clj` |
| `seon.db.internal` normalization | `seon.db.scope.cljs` using AsyncLocalStorage | `seon.db.scope.clj` using bindings/SCI context |
| `seon.db.protocol` | `seon.db.cljs`, `db.transport.uds.cljs` | writer/registry and truthful `uds.clj` |
| `seon.db.program` | Ordinary acquired rows | `seon.db.program.datahike.clj` |
| `seon.derive` | Async DB acquisition adapter | Synchronous host acquisition |
| `seon.error` | V8/ALS/persist/console runtime | `seon.error.sci` and Throwable adapter |
| `seon.eval` receipts | Pod publication adapter | Host receipt writer |
| `seon.render` | Global-var resolver, URL codec, fault sink | SCI var resolver, host fault sink |
| `seon.render.system` | Async row acquisition | Synchronous row acquisition |
| `seon.warn` | CLJS compiled-symbol resolver | SCI resolver |
| `seon.state` | Async DB adapter | Synchronous DB adapter |
| `seon.runtime.admission` | DB publication + CLJS instrumentation | SCI instrumentation adapter |
| `seon.log` | Node console/file sink | JVM sink |
| `seon.ai.dispatch` | Provider SDK adapters | Registry data; no SDK emulation |
| `seon.ai.generate-code` | Scheduler and agent-spawn runtime | Portable plan/state logic |
| `seon.ai.typeahead` | Registered worker backing | Portable policy/projection logic |
| Capability facades | Existing `.internal` fs/search/shell/web implementations | Registry-backed synchronous host capabilities |
| HTTP/SSE | `web.datastar`, router, serve, reactive.call | No duplicate JVM web stack |
| Subprocess | `seon.subprocess` | Optional separate JVM capability, not common logic |
| Self-host compiler | Diffusion quarantine / `eval.bootstrap-cache` | SCI host; no shared compiler abstraction |

The edge namespaces must remain data-in/data-out adapters. They must not become second renderers, warning engines, transaction compilers, or schema registries.

## Top 10 highest-leverage conversions

### 1. `my/plan.cljc`

This is already named as portable canon but is not genuinely host-runnable.

- Preserve one transaction-builder and plan-state canon.
- Replace 69 unguarded awaits with platform acquisition boundaries.
- Keep existing Date conditionals.
- Return ordinary host values, never futures.
- Gate with differential plan mutation, tree, status, reconcile, and generated-plan tests.

### 2. `my/plan/internal.cljc`

Most of the 2,124 LOC are the pure plan compiler.

- Conditionalize `cljs.reader`.
- Extract consult/DB acquisition.
- Keep DAG construction, ready frontier, rollups, reconciliation and document pruning common.
- This should be repaired before `my.plan`, because it is the dependency floor.

### 3. `seon/render.cljs`

The central render walker is almost entirely data transformation.

- Inject or adapt symbol resolution: global var on Bun, SCI var on host.
- Move EDN slot decoding to a portable value/protocol leaf.
- Isolate URL/JSON codec and fault sink.
- Keep entity selection, recursion, schema dispatch, AI/HTML twins, and fallback behavior in one canon.

### 4. `seon/agent/ctx.cljs`

This is the canonical context semantics owner.

- Extract file reads, timezone and SHA.
- Keep system teaching, namespace/schema rendering, block selection, stable-boundary splitting, and context assembly common.
- Split install/remove acquisition from transaction construction.
- This prevents the host and pod from teaching or selecting different context.

### 5. `seon/warn.cljs`

Nearly every check is pure analysis over acquired rows.

- Conditionalize reader/error access.
- Inject “does symbol resolve?”.
- Move EDN decoding to the same portable leaf used by render.
- Run identical warning checks on host and pod.

### 6. `seon/db/internal.cljs`

Approximately 430 LOC are portable transaction/schema mechanics.

- Extract AsyncLocalStorage and its scope functions.
- Share attribute derivation, EDN encoding, lookup-ref normalization, transaction validation, provenance merging, and error envelopes.
- Reconcile this with `db/datahike/schema.clj` so there is one Malli→Datahike law.

### 7. `seon/derive.cljs`

The FSM and status semantics must not vary by runtime.

- Make pure functions consume acquired facts.
- Put DB reads and clock acquisition at platform edges.
- Replace incidental `Promise.all` with one batch acquisition or ordinary host reads.

### 8. `seon/config.cljs`

The host should validate and resolve the same configuration rather than receive a separately interpreted result.

- Keep schemas, defaults, model variants, route/context policy and limit accessors portable.
- Move env, Aero/file selection and manifest loading to platform IO namespaces.
- Replace JS numeric parsing with portable helpers.

### 9. `seon/eval/internal.cljs`

Only 67 LOC, but it closes a major parity seam.

- Promote receipt-state, start transaction, and terminal CAS transaction.
- Move or expose the CAS constructor from a portable owner.
- Use these builders from both execution tiers.

### 10. `seon/agent/ctx/transcript.cljs`

The transcript is the most visible parity surface.

- Extract Node load/memory metrics.
- Normalize time formatting through the shared time owner.
- Keep turn/eval/message grouping, clipping, labels and render generation common.
- Treat `Promise.all` as acquisition strategy, not part of the semantic contract.

Close followers: `my.kb`, `agent.home`, `agent.message`, `state`, `render.canvas`, `render.system`, and the handler/UI leaf bundle.

## Async honesty

### Annotation-bound or acquisition-bound

These do not intrinsically require JS:

- `my.canvas`, `my.data`, `my.kb`, `my.kb.shared`
- `my.plan`, `my.plan.internal`, `my.skills`, `my.ns`
- database acquisition in `agent.ctx.*`
- `agent.debug`, `agent.home`, `agent.lifecycle`
- `agent.message`, `agent.run`, `agent.schedule`, `agent.testrun`
- `derive`, `state`, `render.system`, `web.value`
- the pure portions of admission and recovery.

Their host equivalent is a direct synchronous wrapper call followed by the same portable transformation.

### Structurally async

These genuinely coordinate resources that complete later:

- `client`
- `db.cljs` and Bun UDS
- `agent.loop`, `agent.turn`, `agent.runtime`
- `reactive`
- `web.datastar`, router, serve, debug, reactive.call
- `subprocess`
- fs/search/shell/web internal capability implementations
- Anthropic/OpenAI adapters
- scheduler/spawn portions of generate-code
- worker-backing portions of typeahead
- CLJS test runner
- self-host REPL/bootstrap.

A namespace can still expose portable schemas or pure helpers, but its platform owner remains an edge.

### Incidental Promise usage

`Promise.all` in transcript, debug, derive, and similar row-acquisition functions does not make their domain rules structurally asynchronous. On the host:

- use one database batch operation when snapshot identity matters;
- otherwise perform ordinary synchronous reads;
- never manufacture JVM futures to preserve a Bun implementation detail.

## W5 surviving bands

The four mixed W5 owners were not counted as complete files because their deletion bands dominate their current LOC. Their surviving bands classify as follows:

| Current owner / surviving band | Approx. LOC | Disposition |
|---|---:|---|
| `execution.cljs` protocol constants, message schemas, codec, validators, bounded result | ~310 | PORTABLE-NOW → canonical `execution.cljc` |
| `execution.cljs` authored-program queries, canonical program, source digest, invocation plans | ~275 | PORTABLE-WITH-SEAM |
| `execution/host.cljs` host-session dispatch, sampling, cancel, invoke | ~890 | EDGE; remains Bun→JVM transport, renamed `execution.dispatch` |
| `execution/runtime.cljs` prompt/view rendering band | ~530 | PORTABLE-WITH-SEAM; move into portable render/context owners |
| `eval.cljs` schemas, budget vocabulary, lookup/value and result-render bands | ~630 | PORTABLE-WITH-SEAM; split Promise/global lookup edges from receipt/render canon |

These add roughly **1.7K portable candidate LOC** and **0.9K edge LOC** after excluding the actual death rows. They do not materially change the headline ratio: the post-W5 retained source remains approximately **62% portable canon**.

W5 specifically changes:

- Bun child IPC, cljs.js loading, retained child values, Promise auto-await, and child lifecycle disappear.
- `execution` becomes the one `.cljc` protocol contract.
- `eval/internal` and the surviving eval vocabulary become more valuable.
- `ctx/render_fns` loses child invocation.
- Core rendering moves to the pod, while authored renderer/handler functions route through the JVM SCI execution dispatch.
- `repair.candidates/pick-winner` can become a synchronous host preflight loop.
- `agent.loop` and `agent.turn` remain async because wake scheduling, LLM calls, and pod↔host transport remain.
- HTTP/SSE, providers, subprocesses, database sessions, and package-host calls remain async edges.

## Final recommendation

Adopt one program-level rule:

> Portable namespaces synchronously transform ordinary immutable data. Platform edges acquire, resolve, wait, publish, and transport that data.

The first implementation boundary should be the Wave 1 leaf bundle plus `eval/internal`, followed immediately by the existing toolkit `.cljc` repairs. That gives W5/U5 a real portable dependency floor and replaces the host’s current regex-selected “portable slice” with namespaces that are actually evaluable, function-for-function, on both tiers.