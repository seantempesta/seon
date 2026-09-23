---
type: plan
status: implementation specification (not started); owner decisions §7 open
created: 2026-09-23
lane: repl-simple-reliable
depends-on: [repl-star-vars (5aa3989d0, landed)]
evidence: docs/research/agent-platform/repl-investigation-2026-09-23.md
tags: [agent-platform, repl, mcp, prepl, errors, render, shrink]
---

# Lane — a simple, reliable development REPL

Owner (2026-09-23): "A repl should do what all good repls do -- be very reliable and
resiliant and helpful when possible"; "I'm not sure the mcp tools should be keeping any
stored system results"; "remove the *1 *2 *3 etc code". Evidence, defect numbers D1–D17
and every measurement cited here are in
[the investigation](../../../research/agent-platform/repl-investigation-2026-09-23.md).

## 0. For the owner

Today one `eval_clj` braids seven roles through a ThreadLocal: transport, evaluation,
value printing, error description, a database configuration read, durable blob storage
and web-page cache invalidation (investigation §2). Because printing depends on the
schema projection, configuration and admission, every fault in those layers removes the
REPL that should diagnose it (D8). Answers are matched by position, so one throwing
print shifts every later answer onto the wrong caller (D1, reproduced). Errors lose the
outer message, `ex-data` and chain (D3, reproduced).

The target is four simple parts, each with one role, holding no results:

| Part | One role | Where |
|---|---|---|
| **Bridge** | JSON-RPC ⇄ one prepl connection per call; request-id check; bound | `script/seon/dev/mcp.clj` (thin, ~350 lines from 889) |
| **Wire** | print prepl events as bounded EDN; a print failure is an answer, never a throw | `resources/seon/operator/prepl.clj` |
| **Evaluator** | one function: evaluate one form in a named namespace (JVM) or context (SCI), render value and error once, return plain text data | `seon.repl/host-eval` in `src/seon/repl.clj` |
| **Renderer** | bounded shown text, elision with path/offset/requery | `seon.render.value/render-ai` (exists) with a fixed compiled profile |

What goes: the ThreadLocal hand-off, the loaded-code mark and page invalidation from the
REPL, per-eval configuration reads, admission-then-artifact-then-blob on the REPL path,
`get_value` (decision §7.1), named stateful sessions (decision §7.2), the bespoke
exception summary, the health observation on every call, and the SCI template string.
Projected: **src ≈ −830 net lines, tests ≈ −1,000 net** (§5).

## 1. Properties (acceptance)

Each property names the regression that proves it (§4) and the dependency that supplies it.

1. **One request, one answer.** Every answer carries the request id the bridge sent; a
   mismatch is a typed `:seon.dev.mcp/desync` failure and the connection is dropped,
   never an answer to the wrong caller. (Port attaches ids to prepl tool evals for the
   same reason; nREPL's `:id`.)
2. **A print failure never loses the result or the error.** If rendering fails, the
   answer carries a bounded core print of the value *and* the render failure whole.
   The wire's out-fn never throws (Clojure's own `io-prepl` behaviour, CLJ-2620,
   `reference-code/clojure/src/clj/clojure/core/server.clj:275-289`).
3. **Printing depends on nothing but the value and a compiled profile.** No database,
   configuration, projection or blob read on the value path. The profile is
   `(render/agent-render-profile seon.config/defaults)` — a compiled program constant
   (AGENTS.md "Values carry their world": `seon.config/defaults` is not cluster
   configuration).
4. **Bounded time and output.** Output bound = the renderer's token budget; the wire's
   printer is additionally bounded (`*print-length*`, `*print-level*`, a byte quota) so
   no path can build a >2 GB string (D7). Evaluation bound = the caller's `timeout_ms`;
   expiry reports the *running* request id, "outcome unknown", and closes that
   connection (a timeout is not termination; AGENTS.md "Bounded, event-driven execution").
5. **Errors whole and bounded.** Message and class of every link, each link's `ex-data`,
   the phase, the first-party (`seon.*`/`my.*`) frames and the root frame, plus Clojure's
   one-line `ex-str` — all from `Throwable->map` and `clojure.main/ex-triage`
   (`core_print.clj:474-509`, `main.clj:207`, `:268`), rendered through the same renderer.
   No Seon error-schema contract sits between the JVM's frame and the answer (D4).
6. **Stateless about results.** No star vars (5aa3989d0), no stored results, no blob
   writes, no result cache. What an agent wants to keep, it names: `(def v …)` in a
   namespace, then `(get-in v path)` / `(take n (drop k v))`. The elision's requery text
   says exactly that.
7. **Isolated per call.** Each `eval_clj` opens its own prepl connection (0.47–0.70 ms
   measured incl. connect) and closes it; nothing but namespaces persists between calls.
8. **Resilient discovery.** The endpoint derives from the advertisement and the exact
   `(pid, start-instant)` (`script/seon/operator.clj:106-131`) on every call — no health
   observation on the eval path. `runtime_status` observes health only when asked.
9. **Honest about unavailability.** Every catch on the path handles a declared case or
   returns the throwable whole (D17). A missing cluster is `:repl-unavailable` with the
   start command, as today (`mcp.clj:115-136`).
10. **Timed.** The answer carries the evaluation's `:ms` (prepl already measures it,
    `server.clj:233-235`) and, over one second, the C1 directive with the armed functions
    that spent it (`seon.profile/begin`/`explain-slow`, today in `cluster.clj:309`, `:536`).
11. **REPL text, not JSON data.** MCP content is the shown text (EDN as printed), with a
    short header of namespace, ms and request id, so `:k` and `"k"` stay distinct (D14).

## 2. Algorithmic cost per call

| Operation | Today | Target |
|---|---|---|
| Discovery | read advertisements + one prepl round trip running `mcp-runtime-observation` per advertisement: ~105 ms, proportional to problems + armed functions + a Datalog query | read advertisement files + OS process identity: O(clusters), sub-millisecond |
| Connect | reuse a named socket | one connect per call, ~0.5 ms |
| Evaluation | the form | the form |
| Code-change mark | O(namespaces) scan (557 ns, 0.2–0.3 ms) + page invalidation fan-out | none |
| Configuration | `config/effective` from the database under projection state, per value | none (compiled constant) |
| Value printing | admit to EDN up to 8 MB (385 ms for a 200k vector, value then hidden), artifact, digest, `prepare`, blob write >4 KB | `render-ai`, proportional to the **shown** window: 2.1 ms for the same vector, 1.5 ms for `(range)` |
| Error printing | summary of the last `:via` link, armed contract on frames | `Throwable->map` + `ex-triage` through `render-ai`: 4.8 ms measured for a two-link chain |
| Bridge post-processing | EDN re-read, tail-sentinel walk (O(value)), JSON encode | pass text through |

Target complexity: O(shown window) per answer, O(1) per call of fixed overhead, nothing
proportional to the store, the program or the loaded namespaces.

Simplest alternatives considered: (a) use `clojure.core.server/io-prepl` unchanged with
`:valf` — rejected only because the operator's process-exit halt and the star-var
clearing need the out-fn (kept, 36 lines); (b) nREPL — rejected: a new server dependency
and middleware stack to replace a 36-line entry; its lessons (ids, quota, caught,
ephemeral sessions) are adopted as properties above; (c) keep the projection and fix
each defect at its site — rejected: that is how the six exception-face rewrites happened.

## 3. What each current mechanism becomes

| Mechanism (file:line) | Lines | Becomes |
|---|---|---|
| `mcp-projection` ThreadLocal, `project-next-prepl-value!`, `consume-mcp-projection!` (`cluster.clj:260-261`, `:293-319`) | 30 | **delete**; request data is the argument of `seon.repl/host-eval` |
| `identity-hash-map?`, `loaded-code-mark`, `loaded-code-changed?`, page fan-out in `mcp-valf` (`cluster.clj:263-291`, `:522-535`) | 40 | **delete**; page staleness derives from adoption, which already offers `:runtime-eval` (`cluster.clj:2647-2650`) — the reload owner. A hand `require :reload` at the REPL is followed by `bin/seon init --dev … --changed`, the path that signals. `read_only` retires with it |
| `mcp-effective` (`:325-348`) | 24 | **delete**; compiled profile |
| `nil-deref?`, `first-seon-frame`, `exception-summary`, `mcp-projection-error` (`:350-402`) | 53 | **delete**; errors from `Throwable->map` + `ex-triage` in `host-eval` |
| `mcp-project` (`:404-510`) | 107 | **delete**; `render-ai` in `host-eval` |
| `mcp-valf` (`:512-549`) | 38 | **delete**; the wire prints plain data |
| `mcp-io-prepl` (`:551-569`) | 19 | **delete**; tests use `seon.operator.prepl/io-prepl` |
| `mcp-get-value` (`:571-626`) | 56 | **delete** (§7.1 A) |
| `mcp-runtime-observation`, `program-function-files` (`:628-691`) | 64 | **keep** in its owner (status); called only by `runtime_status` and `bin/seon status`, never by discovery |
| `prepl.clj` out-fn (`resources/seon/operator/prepl.clj:12-36`) | 25 | **keep, simplified**: bounded print in a try (print failure → `:exception true`, phase `:print-eval-result`), exit check that cannot throw, no `ns-resolve` of `seon.cluster` |
| Bridge sessions `clj-sessions`, `current-clj-session!`, `session-rows`, `session-lost` (`mcp.clj:30-31`, `:188-240`, `:631-643`) | 70 | **delete** (§7.2 A); one connection per call |
| Bridge form check (`mcp.clj:242-324`) | 83 | **keep** (one form, readable, clear refusal); replace the hand comment/whitespace skipper with a second `read` on the same `LineNumberingPushbackReader` (already done at `:300`) |
| `decoded-projection-event`, `elision-value`, `enrich-*` (`mcp.clj:351-423`) | 73 | **delete**; the renderer's elision is already complete |
| `sci-evaluation-form` template (`mcp.clj:480-528`) | 49 | **move** to `host-eval`'s SCI arm as ordinary code: calls `seon.sci.eval/evaluate` with the keys it declares, `acquire-context!`/`release-context!` for a branch |
| `execute-get-value`, `digest!`, `path!`, `hex-digit?`, tool entry (`mcp.clj:696-749`, `:773-782`) | 66 | **delete** (§7.1 A) |
| discovery observation (`mcp.clj:91-113`) | 23 | **shrink** to advertisement + identity; the swallowing `catch` (`:106`) goes |
| JSON-RPC loop (`mcp.clj:821-889`) | 69 | **keep**; `tools/call` handled on a `future` so one long evaluation does not block other callers (`stdout-lock` already serializes writes, `:807`) |
| Operator `request-form` mark (`operator.clj:204-209`) | 3 | **delete** the mark; reply is already EDN via `readable-response` |
| Operator "PREPL evaluation failed." (`operator.clj:175`) | 1 | **fix owner**: lift the failing link's class and message into the message; carry the bounded map, not a string-in-a-string (D6) |
| `readable-response` / `diagnostic` render (`boot.clj:535-545`, `:290-301`) | — | **fix owner**: bounded print for the round trip; a render failure inside the diagnostic falls back to bounded `pr-str` and carries the render failure (D9) |
| `seon.dev.mcp.edn`, `seon.dev.mcp.artifact.edn` members for the projection | ~45 | **retire** with their last writer in the same slice (1.3e refusal names survivors) |

## 4. Slice plan

Each slice is one loadable commit, ≤ ~100 added src lines, deletion-heavy, proven on
default's one JVM (adopt with `bin/seon init --dev default --changed <paths>`, one
`bin/test-check … --changed` request, MCP probe in a throwaway namespace). Holds are the
ledger's at 2026-09-23 ~08:55Z (`tmp/orchestrator/file-ownership.md`).

| Slice | Change | Files | Added / deleted src (est.) | Regression (WANTED behaviour) | Collides with |
|---|---|---|---|---|---|
| **R1 evaluator** | `seon.repl/host-eval`: `{:seon.repl/request-id :seon.repl/form :seon.repl/ns :seon.repl/mode :seon.cluster/name :seon.agent/branch}` → `{:seon.repl/request-id :seon.repl/ns :seon.repl/ms :seon.repl/shown or :seon.repl/error …}`; JVM arm: `in-ns`, eval, render; error arm: `Throwable->map` links (`:type :message :data`, first-party frames), `ex-str` line, phase; render failure → bounded `pr-str` + failure; `profile/begin`/`explain-slow`; complete Malli contracts from declared schemas | `src/seon/repl.clj`, new `resources/seon/schemas/seon.repl.host.edn` (or members in the existing `seon.repl` schema), new `test/seon/repl_host_eval_test.clj` | +85 / 0 | (a) `(vec (range 200000))` shows its first elements and an elision naming 199,968 omitted, in < 50 ms; (b) `(throw (ex-info "outer" {:k 1} (IllegalStateException. "inner")))` shows `outer`, `{:k 1}`, `inner` and both classes; (c) a value whose `toString` throws returns the evaluation's success, a core print and the print failure; (d) a frame with a nil file renders; (e) `(sorted-map "a" 1)`, a Var, `(range)` render | none (`src/seon/repl.clj` free) |
| **R2 wire** | out-fn: `:val` printed with bounded `pr-str` inside `try` (failure → `ex->data` phase `:print-eval-result`, `:exception true`); exit check `(and (map? v) (not (sorted? v)) (true? (get v :seon.operator/process-exit?)))`; drop `ns-resolve 'seon.cluster 'mcp-valf` | `resources/seon/operator/prepl.clj`, `test/seon/dev/prepl_retention_test.clj` (+ one test) | +8 / −10 | one connection: `(sorted-map "a" 1)` then `:next` yields exactly two `:ret` events whose values are those forms' (D1 regression) | **held by repl-star-vars** → its follow-up |
| **R3 bridge** | `jvm`/`sci` form = `((requiring-resolve 'seon.repl/host-eval) {…request-id…})`; one connection per call; answer's request id checked (mismatch → `:seon.dev.mcp/desync`); timeout names the running request id; discovery without observation; content = shown text + header; `tools/call` on a future; delete sessions, enrichment, SCI template | `script/seon/dev/mcp.clj`, `test/seon/dev/mcp_bridge_test.clj` (delete projection/session rows, add three) | +45 / −330 | (a) two interleaved callers each get their own answer; (b) a stale extra `:ret` on the socket yields `desync`, never the wrong value; (c) `eval_clj` of `(+ 1 2)` issues no `mcp-runtime-observation` (count via C1 profile cell); (d) `:k` and `"k"` answers differ | **held by repl-star-vars** → its follow-up after R1 |
| **R4 delete the projection** | delete `cluster.clj:256-626` except runtime observation; convert `bin/seon-hook:1652`, `script/seon/operator.clj:207`, `test/seon/render/web_context_test.clj:227-245`, `test/seon/cluster/reload_measure.clj`, `test/seon/dev/hook_measure.clj`; delete `test/seon/cluster/mcp_test.clj` and `test/seon/mcp_test.clj` rows that test deleted machinery; retire `seon.dev.mcp` projection members | `src/seon/cluster.clj`, `bin/seon-hook`, `script/seon/operator.clj`, those tests, `resources/seon/schemas/seon.dev.mcp.edn` | +5 / −380 | a page served by default keeps its cache across an `eval_clj` that changes no code, and refreshes after `bin/seon init --dev --changed` (adoption signal) | **cluster.clj held by m4-n1**; after release |
| **R5 get_value and blobs** (if §7.1 A) | delete the tool, `seon.dev.mcp.artifact.edn`, the artifact functions' last REPL caller | `script/seon/dev/mcp.clj` (with R3), `src/seon/cluster.clj` (with R4), schema, tests | 0 / −130 | `eval_clj` of a 100 KB value writes no blob (store blob count unchanged) and shows the head + requery text | same as R3/R4 |
| **R6 operator and boot wire** | lift the cause into "PREPL evaluation failed" (D6); bounded prints in `readable-response`, `operator.clj:1437`; diagnostic render fallback (D9) | `script/seon/operator.clj`, `src/seon/cluster/boot.clj` | +15 / −5 | `bin/seon init` on a misplaced schema attribute prints the attribute message on its first line; a reply holding a Var is readable EDN (D5) | **boot.clj held** (m4-n1's error route touched it); operator.clj free |
| **R7 interrupt** (if §7.3 B) | `host-eval` names its thread `seon.repl/<request-id>` for the evaluation and restores it; `seon.repl/interrupt!` finds it via `Thread/getAllStackTraces` and `.interrupt`s; bridge calls it on timeout; SCI arm uses SCI's interrupt hook | `src/seon/repl.clj`, `script/seon/dev/mcp.clj` | +20 / 0 | a `Thread/sleep`-blocked evaluation ends with `InterruptedException` after the bridge's bound; a CPU loop reports "interrupt requested; outcome unknown" | as R3 |
| **R8 skill** | rewrite `.agents/skills/repl/SKILL.md` MCP section (§6) | skill | docs | every claim `file:line`, verified | #27/#61 skills lane holds `.agents/skills/repl/**` only while running |

Order: R1 → R2 → R3 (repl-star-vars follow-ups) → R4 + R5 (after m4-n1 releases
`cluster.clj`) → R6 (boot.clj release) → R7 → R8. R1–R3 alone fix D1, D3, D4, D5 (JVM
eval path), D7 (REPL path), D8, D12–D16; R4 deletes the dead projection.

Every slice's test red/green follows AGENTS.md's three questions: a test of the deleted
projection is deleted with it; a test expecting stored results or JSON data is a retired
assumption, fixed; a test of wanted behaviour that fails is fixed at the owner.

## 5. Projected net lines

| Area | Before | After (est.) | Net |
|---|---|---|---|
| `script/seon/dev/mcp.clj` | 889 | ~360 | −530 |
| `src/seon/cluster.clj` MCP section | 436 | 64 (runtime observation) | −372 |
| `src/seon/repl.clj` | 517 | ~625 | +105 |
| `resources/seon/operator/prepl.clj` | 36 | 34 | −2 |
| operator/boot wire | — | — | ~+10 |
| schemas (`seon.dev.mcp*`, new host members) | 63 | ~25 | −38 |
| **src + script + resources** | | | **≈ −830** |
| tests: `cluster/mcp_test.clj` 578, `mcp_test.clj` 125, bridge projection/session/get_value rows (~350 of 777) | ~1,050 deleted | ~150 new (R1 5 rows, R2 1, R3 4, R4 1, R5 1, R6 2, R7 2) | **≈ −900** |

## 6. What the REPL skill must carry afterwards

Each line is a claim the R8 author verifies at the landed `file:line`:

- The three surfaces: agent SCI evaluation (`seon.sci.eval/evaluate`), MCP JVM
  evaluation (`seon.repl/host-eval` over one prepl connection per call), raw JVM REPL.
- One call, one answer: the request id, the `desync` failure and what to do (retry; the
  connection is already dropped).
- Nothing is kept: no `*1`/`*e`, no stored result, no `get_value`; bind with `def` in a
  throwaway namespace, then slice (`get-in`, `take`/`drop`); the elision prints the
  path, omitted count and next offset.
- Errors: what the answer contains (every link's class/message/data, first-party frames,
  phase, `ex-str` line) and that a render failure still shows the value.
- Bounds: `timeout_ms`, what a timeout means (outcome unknown; the form may still run;
  the interrupt behaviour chosen in §7.3), the renderer's shown window.
- SCI mode: live (the cluster's shared context — a `def` enters every agent's world) vs
  `branch` (isolated), with the acquisition entrance cited.
- Timing: `:ms` on every answer; the C1 directive over one second.
- Page caches refresh on adoption (`bin/seon init --dev … --changed`), not on evaluation.
- The bridge is a thin, long-lived process: behaviour changes land in the cluster JVM
  (`seon.repl`), which adoption reloads; a bridge change needs an MCP client restart.

## 7. Owner decisions

### 7.1 `get_value` and blob-stored results

- **A (recommended) — delete both.** Guarantee: the REPL stores nothing; oversized values
  show a bounded head and an elision whose requery text says `(def v <form>)` then slice.
  Cost: −~190 src lines, −~150 test lines; no store growth from dev probes (collection is
  off, so today's blobs never go). Gives up: paging an *unnamed* result without
  re-evaluating; a non-idempotent form must be bound the first time.
- **B — keep `get_value` over an in-memory bounded LRU of recent results.** Guarantee:
  page an unnamed result for a while. Cost: a result cache beside the namespaces (the
  retention the star-var ruling removed, reintroduced as a new mechanism), ~+60 lines,
  memory proportional to the cap. Gives up: "stateless about results".
- **C — keep today's blob tier.** Guarantee: durable paging by digest. Cost: dev results
  written into the cluster store with no collector; the admission+artifact path stays
  braided into printing (D8 class survives). Gives up: the D8 fix and ~500 of the lines.

### 7.2 Sessions

- **A (recommended) — one connection per call; no `session_id`.** Guarantee: callers
  cannot see each other's `*ns*`, bindings or answers; desync cannot outlive a call.
  Cost: ~0.5 ms connect per call; −70 lines. Gives up: `set!` of dynamic vars across
  calls (put them in the form).
- **B — keep named sessions, default id unique per MCP process.** Guarantee: opt-in
  continuity. Cost: keeps the session map, reconnect and `session-lost` logic
  (`mcp.clj:188-240`), one issue note already open on it. Gives up: little, but
  subagents of one client still share a process-unique id.
- **C — keep today's shared `"default"`.** Gives up isolation (D2).

### 7.3 Interrupt on timeout (JVM mode)

- **A — none (today's honest behaviour).** The answer says the form may still run.
  Cost 0. Gives up: a runaway form keeps a thread and its memory until it ends.
- **B (recommended) — `Thread.interrupt` by thread name, SCI's hook in SCI mode (R7).**
  Guarantee: interruptible code (sleep, IO, `await`, SCI) stops at the bound; non-yielding
  JVM code is reported "interrupt requested; outcome unknown" (JDK 20+ removed
  `Thread.stop`; nrepl#296). Cost: +20 lines, no registry (the thread name is the key).
  Gives up: nothing current.
- **C — nREPL-style JVMTI agent.** Guarantee: stops CPU loops. Cost: a native agent per
  platform. Gives up: portability; not recommended.

## 8. Out of scope, recorded

- `seon.repl/error-text` swallows a triage read failure (`src/seon/repl.clj:165`,
  `(catch Throwable _ nil)`); R1 touches `repl.clj` and fixes it in place.
- Admission prints a host Var's class as `sci.lang.Var` (D15), a defect of
  `seon.sci.admit`'s reference printer for the agent path too; file under
  `docs/seon/issues/` when R1 lands (the MCP path stops using admission).
- Existing issue notes this lane closes when its slices land:
  `mcp-exception-projection-is-opaque-after-the-kind-removal.md`,
  `mcp-eval-refuses-map-results-without-cluster-projection-state.md`,
  `mcp-jvm-small-result-projection-fails-during-live-adoption.md`,
  `mcp-sci-error-projection-passes-a-nil-database.md` (SCI arm renders through the
  evaluation's own shown text), `mcp-session-loss-claims-unobserved-restart.md` (7.2 A),
  `a-missing-required-dial-kills-every-io-prepl-connection.md` (no config read on the
  wire), and fix-schedule rows 24z, 31, 54, 60 (MCP schema residue).
