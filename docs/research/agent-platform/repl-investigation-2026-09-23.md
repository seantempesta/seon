---
type: research
status: evidence (point-in-time observation, 2026-09-23 ~14:10–14:40Z, default pid 63253 started 14:11:20Z)
created: 2026-09-23
lane: repl-prd (Opus 5.5)
feeds: docs/prds/agent-platform/plan/lane-b1-one-publication-path.md §3b (the PRD lane-repl-simple-reliable.md, 9fd0f5347, was folded there)
---

# The development REPL, end to end — investigation

Owner (2026-09-23): "a repl should do what all good repls do -- be very reliable and
resiliant and helpful when possible"; "I'm not sure the mcp tools should be keeping any
stored system results"; "remove the *1 *2 *3 etc code … redundant". Complexity is judged
by Hickey's *Simple Made Easy*: a part is simple when it has one role; complected when
roles are braided so that one cannot change or fail without the other.

Every probe below ran through MCP `eval_clj` (JVM mode, private sessions
`repl-prd-probe` / `repl-prd-desync`, throwaway namespace `repl-prd.probe`, `read_only`)
or a plain Python socket client against default's advertised prepl port. No Var of
default was redefined; no transaction was written. One blob write was avoided by design:
no probe returned a value between 4 KB and 8 MB.

## 1. Surfaces and sizes

| Surface | File | Lines | Role today |
|---|---|---|---|
| MCP bridge (Babashka process, one per MCP client) | `script/seon/dev/mcp.clj` | 889 | JSON-RPC, discovery, session sockets, form validation, SCI form template, elision enrichment, get_value, runtime_status |
| Cluster-side MCP projection | `src/seon/cluster.clj:256-691` | 436 | ThreadLocal hand-off, page invalidation, profile window, config read, admission, render, blob write, get_value paging, exception summary, runtime observation |
| io-prepl entry | `resources/seon/operator/prepl.clj` | 36 | prepl out-fn, star-var clearing (5aa3989d0), calls `mcp-valf`, process-exit halt |
| Duplicate io-prepl | `src/seon/cluster.clj:551-569` (`mcp-io-prepl`) | 19 | used only by tests (`test/seon/mcp_test.clj:23`, `test/seon/dev/mcp_bridge_test.clj:764`) |
| Operator prepl client | `script/seon/operator.clj:133-178` (`prepl-value!`), `:204-209` (`request-form`) | 55 | one-form client, reads `:val` as EDN |
| Boot request wire | `src/seon/cluster/boot.clj:264-301` (`diagnostic`), `:535-545` (`readable-response`) | 50 | EDN round-trip of replies, renders offending evidence |
| Agent REPL grammar | `src/seon/repl.clj` | 517 | shown text, `error-text` via `clojure.main/ex-str` (`:148-166`) |
| Schemas | `resources/seon/schemas/seon.dev.mcp.edn`, `seon.dev.mcp.artifact.edn` | 54 + 9 | MCP envelope members, artifact |
| Tests | `test/seon/cluster/mcp_test.clj` 578, `test/seon/dev/mcp_bridge_test.clj` 777, `test/seon/mcp_test.clj` 125, `test/seon/dev/prepl_retention_test.clj` 49 | 1,529 | 15 + 25 + 2 + 1 deftests |
| Skill | `.agents/skills/repl/SKILL.md` | 119 | three sentences on MCP (`:25-28`); nothing on size, errors, sessions, timeouts |

Other callers of the projection: `bin/seon-hook:1652` (`project-next-prepl-value!`),
`script/seon/operator.clj:207`, `test/seon/render/web_context_test.clj:227-245`,
`test/seon/cluster/reload_measure.clj`, `test/seon/dev/hook_measure.clj`.

## 2. How one JVM-mode `eval_clj` runs today

1. Bridge validates one form by reading it twice with a hand-rolled whitespace/comment
   skipper (`mcp.clj:242-324`).
2. **Discovery on every call** (`mcp.clj:91-113`, `:138-182`): for each advertisement it
   opens a *second* prepl connection and runs `mcp-runtime-observation` — health,
   readiness, problem counts, profile summary, a replaced-roots Datalog query and Flow
   status (`cluster.clj:645-691`). Only `:state` is used to pick the endpoint.
3. Writes `(do (project-next-prepl-value! {...}) (in-ns 'ns) (refer 'clojure.core) (eval 'form))`
   (`mcp.clj:468-478`) to the session socket (`clj-sessions` atom keyed
   `[root cluster session-id]`, default id `"default"`, `mcp.clj:31`, `:540`).
4. In the JVM, `project-next-prepl-value!` stores a request map in a **ThreadLocal**
   (`cluster.clj:260-313`), begins a profile window, and, unless `read_only`, snapshots
   every namespace's mappings (`loaded-code-mark`, `:269-279`).
5. Clojure's `prepl` evaluates and `set!`s `*1..*3`/`*e`
   (`reference-code/clojure/src/clj/clojure/core/server.clj:236-238, 251, 257`); our
   out-fn clears them (`prepl.clj:18-19`) and calls `seon.cluster/mcp-valf` resolved by
   name (`prepl.clj:25-31`).
6. `mcp-valf` (`cluster.clj:512-549`) consumes the ThreadLocal, compares the loaded-code
   mark and offers `:runtime-eval` to every cohosted cluster's page proc
   (`:532-535`), explains a slow evaluation, then `mcp-project` (`:404-510`) reads the
   cluster's **effective configuration from the database** under the projection state
   (`mcp-effective`, `:325-348`), admits the value (`seon.sci.admit/admit-value`),
   builds an artifact, EDN-prints it, digests it, renders it with `render.value/prepare`,
   and **writes a blob** into the cluster store when the EDN exceeds 4,096 bytes
   (`:494-495`). An exception goes to `exception-summary` (`:364-382`).
7. The result is `pr-str`'d again (`admit/canonical-edn`, `src/seon/sci/admit.clj:86-96`),
   printed on the socket, re-read by the bridge (`decoded-projection-event`,
   `mcp.clj:351-360`), re-walked to enrich tail sentinels (`:362-423`), then JSON-encoded
   (`content-text`, `mcp.clj:35-40`).

Seven roles braided through one call: transport, evaluation, value printing, error
description, configuration read, durable storage, and web-page cache invalidation, plus
profiling. The ThreadLocal is the braid's knot: steps 4 and 6 communicate through ambient
thread state instead of an argument.

## 3. Measurements (all sub-second; no justification owed)

| Operation | Value | ms |
|---|---|---|
| `loaded-code-mark` (557 namespaces) | identity map of every ns mapping | 0.19–0.33 |
| `mcp-runtime-observation "default"`, in JVM | 7 keys | 168 |
| same through a fresh prepl socket (discovery, per eval call), 3 runs | 3,474 bytes | 104–112 |
| raw prepl `(+ 1 2)` incl. connect, no projection mark, 3 runs | 58 bytes | 0.47–0.70 |
| prepl `1` with the projection mark, 3 runs | 199 bytes | 2.5–7.3 |
| `mcp-valf` on `{:a 1 :b [1 2 3]}`, first / second | projected | 85 / 3.5 |
| `mcp-valf` on `(vec (range 200000))` (2 runs) | `{:seon.sci.admit/bytes 8388644 :seon.sci.admit/reason :over-bound}` — **the value is gone** | 385–411 |
| `render.value/render-ai`, fixed agent profile from `seon.config/defaults`, no db/config/projection read: 200k vector | head of 32 + elision naming omitted 199,968, path, next offset | 2.1 |
| same: `(range)` (infinite) | head of 32 + elision | 1.5 |
| same: `#'clojure.core/map` / `(sorted-map "a" 1)` / `{:a 1}` | `#'clojure.core/map` / `{"a" 1}` / `{:a 1}` | 0.28 / 0.24 / 0.23 |
| same: `Throwable->map` of `ex-info` with cause | complete map (trace first — key order) | 4.8 |
| probe eval combining the rows above | — | 672 |
| forced-timeout cleanup of the desynced session: `(reduce + (range 600000000))` | CPU loop on one prepl thread, deliberately longer than the bridge's 1 ms bound so the bridge closes the stale socket (the only way to close a named session) | ≈2,000 (estimated from the bridge's timeout; not measured) |

Reading: the value renderer alone answers a 200,000-element vector ~190× faster than
the current projection **and shows it** (head + a requery-able elision); the current
path spends 385 ms printing 8 MB of EDN only to replace the value with an over-bound
marker. Every `eval_clj` pays ~105 ms of health observation it discards, 40–200× the
cost of a typical evaluation.

## 4. Known defects — verified, with causes

| # | Defect | Verified? | Cause (file:line) |
|---|---|---|---|
| D1 | **Session desync: every later answer is the previous form's.** | **Reproduced** in `repl-prd-desync`: `(sorted-map "a" 1)` answered correctly; `:second-form` answered a `ClassCastException` trace; `:third-form` answered `:second-form`; the off-by-one persisted for four more calls until a forced timeout closed the socket. | The out-fn calls `(:seon.operator/process-exit? (:val event))` *after* printing (`prepl.clj:34-35`; `:27-28` before 5aa3989d0). A keyword lookup on a sorted map with non-keyword keys throws in `Keyword.compareTo`. `prepl` catches the out-fn's throw as an evaluation failure and emits a **second `:ret`** for the same form (`server.clj:249-253`). The bridge matches answers by position (`collect-prepl-response!`, `mcp.clj:326-349`), has no request id, so the extra `:ret` becomes the next caller's answer. This is the mechanism of "a shared session returns another caller's result". The earlier hypothesis "String→Keyword at prepl.clj:28 when a session is left in another namespace" is refuted: the namespace plays no part; the lookup's receiver does. |
| D2 | Sharing of session `default` across agents | Code-level | `session_id` defaults to `"default"` (`mcp.clj:540`); every caller of one MCP server process (a Claude session and all of its subagents share one) shares one socket, its `*ns*`, its dynamic bindings and any desync. The JSON-RPC loop is serial (`mcp.clj:880-889`), so one 30 s evaluation blocks every other caller's tool call. |
| D3 | Exception face drops the error's whole cause | **Reproduced**: `(throw (ex-info "probe failure" {:probe/key 1 …} (IllegalStateException. "inner cause")))` returned message `"inner cause"`, class `IllegalStateException`, frame `seon.operator.prepl$io_prepl … prepl.clj 11`. The outer message, its `ex-data` and the chain are gone. | `exception-summary` keeps only `(last (:via value))` and `:cause`, and falls back to the first trace frame (`cluster.clj:364-382`). Violates AGENTS.md "No swallowed errors". Five rewrites of this face since August (ab60b6463, e780f8b48, c683c7149, 510a9236d, f8861b66e; plus ed62a3e06). |
| D4 | "first-seon-frame refused trace … got nil" hides the real error | Cause verified in source; sightings in `docs/prds/agent-platform/landing/lane-m4-n3-2026-09-23.md` and `lane-opus-publication-2026-09-23.md` | `:seon.error/frame` is `[:tuple :symbol :symbol :string :int]` (`resources/seon/schemas/seon.error.edn:273`); `StackTraceElement->vec` yields a nil file for compiler/generated frames. The armed contract on the *diagnostic helper* `first-seon-frame` (`cluster.clj:355-362`) refuses the JVM's own frame, and its refusal replaces the evaluation's error. |
| D5 | `bin/seon start` masked a boot failure as "No dispatch macro for: '" | **Reproduced**: `mcp-valf` with `:project? false` (the operator's mark, `operator.clj:207-208`) on `{:ok 1 :v #'clojure.core/map}` put `{:ok 1, :v #'clojure.core/map}` on the wire; `clojure.edn/read-string` refused "No dispatch macro for: '". | The unprojected path prints with `pr-str` (`admit/canonical-edn`, `admit.clj:86-96`) and the client reads with `edn/read-string` (`operator.clj:176`, `mcp.clj:334`). pr-str is not EDN: Vars, fns, objects, records print unreadably. |
| D6 | `bin/seon init` prints only "PREPL evaluation failed." | Verified in logs (`tmp/orchestrator/prepare-head-base-2026-09-23.log`) | `operator.clj:175` fails with a constant message; the cause (e.g. "Schema attribute … belongs in seon.test.timing.edn") is a `Throwable->map` printed *as a string inside* `:seon.error/offending :val`, unbounded and unlifted. |
| D7 | "Requested array size exceeds VM limit" | Cause by source | Three unbounded printers remain: `admit/canonical-edn` binds `*print-length* nil *print-level* nil` (`admit.clj:90-91`) and runs on every unprojected reply and on the Throwable->map of any exception whose projection mark was consumed (seen in D1's second answer, a whole trace); `readable-response`'s `(pr-str response)` (`boot.clj:541`); `operator.clj:1437` `prn` of a diagnostic. A >2 GB answer is recorded in `fix-schedule-2026-09-23.md:184`. |
| D8 | Every MCP value refused "expected must be a print node" (admit.clj:749) | Verified in `fix-schedule-2026-09-23.md:204` (pid 28202 had a reverted `seon.schema` loaded) | The REPL's printer depends on the schema projection, the configuration read and admission. Any fault in those layers removes the tool used to diagnose it. Same class: `mcp-eval-refuses-map-results-without-cluster-projection-state.md` (a plain map replaced by "no cluster projection state"), `mcp-jvm-small-result-projection-fails-during-live-adoption.md`, `mcp-sci-error-projection-passes-a-nil-database.md`, `a-missing-required-dial-kills-every-io-prepl-connection.md` (a missing config dial killed every connection for 45 min). |
| D9 | boot/diagnostic fails while rendering (value.clj:509) | Cause by source | `boot.clj:290-301` renders offending evidence with `render.value/render-ai` inside the error constructor; a render failure (`print/fit`, `value.clj:509`) throws out of the diagnostic and replaces the failure it was describing. |
| D10 | Star-var retention of large values | Fixed in 5aa3989d0 (`prepl.clj:18-19`); `mcp-io-prepl` duplicate (`cluster.clj:551`) still has none of it | — |
| D11 | Oversized values written into the cluster's blob tier by a dev tool | Cause by source (not exercised: a probe would have written into default) | `cluster.clj:494-495` `blob/put!` for any result whose artifact EDN > 4,096 bytes (`:seon.config.eval.result/blob-threshold`), `read_only` included. Storage collection is OFF by owner ruling (fix-schedule 10:00Z), so these blobs accumulate with no collector. Above 8 MB (`max-bytes`) nothing is stored and nothing is shown (§3). |
| D12 | ThreadLocal hand-off is consumed by any nested call | **Reproduced**: a probe that called `project-next-prepl-value!`+`mcp-valf` inside its own body got its *own* result back unprojected (a raw `pr-str` string) because the inner call consumed the outer mark. | `consume-mcp-projection!` (`cluster.clj:315-319`). |
| D13 | Timeout is attributed to the wrong form and is not termination | **Observed**: the timeout response named `:after-long` while the running form was the previous `(reduce …)`. | `mcp.clj:604-614` closes the socket; the prepl thread keeps evaluating (no interrupt; `server.clj` has none); forms written after it still run on that thread. |
| D14 | Value type lost in the answer | **Observed**: `:second-form` came back as `"second-form"`; keyword and string are indistinguishable. | The envelope is JSON-encoded data (`content-text`, `mcp.clj:35-40`), not REPL text. |
| D15 | Wrong class shown for a JVM Var | **Observed**: `#'clojure.core/map` shown as `{:seon.sci.admit/reference "sci.lang.Var"}`. | admission's reference printer names SCI's class for a host Var. |
| D16 | Stale tool description in running bridges | **Observed**: the loaded tool description still says "retains raw *1/*2"; three `bb … -m seon.dev.mcp` processes started 2026-09-22 18:00–21:46 run pre-5aa3989d0 code. | The bridge is a separate long-lived process; any logic in it changes only at MCP client restart. |
| D17 | Swallowing catches on the REPL path | Code-level | `mcp.clj:106` discovery `(catch Exception _ nil)` turns every observation failure into `:unknown` without its cause; `mcp.clj:194`, `:215` close failures dropped; `mcp.clj:356` `::unreadable`; `mcp.clj:447`, `:715`; `repl.clj:165` `(catch Throwable _ nil)` in `error-text`. |

## 5. Git history — why it grew

`git log -G "mcp-valf|mcp-project|project-next-prepl-value|loaded-code-mark|mcp-get-value|exception-summary" -- src/seon/cluster.clj`
returns 15 commits. The shape:

- 857f0e1aa (2026-08-03) "Land the MCP value chain on one stored print node" — admission,
  artifact and blob enter the REPL path so a large value could be paged by digest.
- ab60b6463, e780f8b48 (08-03), c683c7149 (08-04), 510a9236d (09-19), f8861b66e (09-21),
  ed62a3e06 (09-22): the exception face rewritten six times, each after the error family
  it depended on changed. The REPL's error description is coupled to Seon's error
  schemas, so every error-family cut broke the REPL (issue
  `mcp-exception-projection-is-opaque-after-the-kind-removal.md`, reopened 2026-09-23).
- 0dd6bc0aa (09-15) `read_only` introduced so a probe would stop invalidating pages;
  81e620d4b (09-22) replaced the flag with the loaded-code mark (issue 24z). The REPL
  learned about web caches because it was the place code changed by hand.
- c3a8d0f01, 83d5f49bd, 4ec6bd82a: recognition bypasses and key renames in the SCI
  template — the template is Clojure inside a syntax-quote inside a string, invisible to
  lint and contracts until armed.
- 56c0941af (fix-schedule row 19): blob-only storage, no transaction, "unreferenced blobs
  go with the next explicit GC sweep" — the sweep has since been switched off.

Each step was locally correct (habit 3, "fixing at the site instead of the owner", and
habit 1, "fetching at call time": the projection re-reads configuration from the
database on every value instead of being handed a fixed profile).

## 6. What good REPLs do, and what our dependencies already give us

| Property | Reference behaviour | Source | We already have |
|---|---|---|---|
| A print failure never loses the result or the error | `io-prepl` wraps `valf` and reports a print failure as `:exception true` with phase `:print-eval-result` (CLJ-2620, fixed in 1.11) | `server.clj:275-289`; [ask.clojure.org 10429](https://ask.clojure.org/index.php/10429/prepl-does-serialize-exception-thrown-during-serialization) | Clojure's io-prepl does it; **our `prepl.clj` copy omits the try** (`prepl.clj:20-36`) |
| Exactly one terminal answer per request, matched by id | nREPL responses carry `:id`/`:session`/`:status done`; Port (a prepl client) wraps tool evals in a function that attaches a request id because prepl has none | [nrepl.org design overview](https://nrepl.org/nrepl/design/overview.html); [clojure-emacs/port design](https://github.com/clojure-emacs/port/blob/main/doc/design.md) | nothing; we match by position (D1) |
| Bounded output with a named truncation | nREPL print middleware `:nrepl.middleware.print/quota` and `…/truncated-keys`; `*print-length*`/`*print-level*` | [nREPL middleware](https://nrepl.org/nrepl/design/middleware.html), [nREPL ops](https://nrepl.org/nrepl/ops.html) | `seon.render.value/render-ai` already bounds by children/token budget and names path, omitted count and next offset (§3: 1.5–2.1 ms) |
| Structured, whole errors | `Throwable->map` (`:via` every link with `:type :message :data :at`, `:trace`, `:cause`, `:data`); `clojure.main/ex-triage` + `ex-str` for the one-line human form; nREPL `caught` middleware | `reference-code/clojure/src/clj/clojure/core_print.clj:474-509`, `main.clj:207`, `:268` | `seon.repl/error-text` already uses `ex-str` for agent turns (`repl.clj:148-166`) |
| Sessions: ephemeral or explicitly cloned; closing is explicit | nREPL `clone`/`close`, ephemeral sessions for one message | [nREPL ops](https://nrepl.org/nrepl/ops.html) | our "session" carries only `*ns*` (reset every call, `mcp.clj:476`) and bindings; with star vars gone it holds nothing an evaluation needs — state lives in namespaces |
| Interrupt | nREPL `interrupt` op; broken for non-yielding code on JDK 20+ since `Thread.stop` throws ([nrepl#296](https://github.com/nrepl/nrepl/issues/296), [JDK-8368370](https://bugs.openjdk.org/browse/JDK-8368370)); Port: "prepl has no interrupt op" | as cited | SCI's interrupt hook for SCI mode (AGENTS.md "Bounded, event-driven execution"); JVM mode can only `Thread.interrupt` |
| out/err streaming | prepl `:out`/`:err` events; nREPL `:out` messages | `server.clj:223-225` | kept today; works |
| Reconnect after server replacement | client re-discovers the endpoint | — | advertisement + process identity (`operator.clj:106-131`); the default session reconnects (`mcp.clj:218-240`) |
| Thin client, logic on the server | Port bootstraps its tool function into the server; unrepl ships "the blob" | Port design | our logic sits in the Babashka bridge, which never reloads (D16) |

## 7. The braids (findings, ranked)

1. **Printing depends on the whole system.** The REPL's value path reads the database's
   effective configuration, the schema projection, admission and the blob store
   (`cluster.clj:404-510`). The REPL is the tool for diagnosing exactly those layers, so
   each of their faults removes it (D8, D9, D4, the 45-minute dial outage). A value
   renderer with a fixed compiled profile needs none of them (§3).
2. **Evaluation and printing talk through a ThreadLocal** (`cluster.clj:260-319`): ambient
   state, consumable by any nested call (D12); the prepl entry resolves `mcp-valf` by
   name at every print (`prepl.clj:25-26`).
3. **Positional request matching** (D1, D13): any out-fn throw or late answer shifts
   every later answer onto the wrong caller.
4. **The REPL stores results** — blobs per oversized result (D11), `get_value` paging
   (`cluster.clj:571-626`, `mcp.clj:696-749`) that duplicates the value renderer's own
   path/offset elision and a REPL's ordinary `(def v …)`/`(get-in v path)`.
5. **The REPL invalidates web caches** (`cluster.clj:263-291`, `:529-535`): a scan of all
   namespaces per non-read-only eval and a `read_only` flag every caller must remember.
   Adoption, the owner of reload, already offers the same signal
   (`cluster.clj:2647-2650`), and AGENTS.md forbids hand redefinition in default.
6. **Discovery observes health on every call** (`mcp.clj:91-113`): ~105 ms of work
   discarded per evaluation, and a failure of that observation swallowed as `:unknown`.
7. **Errors reduced to a summary** built from Seon's error schemas rather than Clojure's
   own `Throwable->map`/`ex-triage` (D3, D4).
8. **pr-str on an EDN wire** (D5, D7): the operator path prints Clojure and reads EDN.
9. **Logic in the long-lived bridge** (D16) and a 45-line SCI evaluation template kept as
   syntax-quoted text (`mcp.clj:480-528`).

## 8. Verification boundary

- Exercised: JVM-mode MCP eval on default pid 63253 (live bridge running pre-5aa3989d0
  code; the cluster JVM started 14:11:20Z from the checkout), raw prepl over a socket,
  in-JVM calls of `mcp-valf`, `mcp-runtime-observation`, `loaded-code-mark`,
  `render.value/render-ai`.
- Not exercised: SCI mode; `get_value`; blob write (avoided); the operator's own
  `bin/seon` commands (D5/D6 reproduced at the wire, not through `bin/seon`); D2's
  cross-agent sharing (inferred from `mcp.clj:540` and one MCP process per client);
  the bridge's own wall time per call (not observable from inside the call).
- Residue left: none in default's namespaces except `repl-prd.probe` (throwaway);
  session `repl-prd-desync` was closed by the forced timeout; `repl-prd-probe` remains
  open in the bridge (a socket, no result retained after 5aa3989d0 once loaded — the
  loaded entry at pid 63253 is from the checkout, so it clears star vars).
