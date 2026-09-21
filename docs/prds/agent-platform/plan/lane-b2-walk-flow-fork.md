---
type: plan
status: first pass (Fable, 2026-09-21) — for astra review, then the clean write
created: 2026-09-21
tags: [agent-platform, lane-b2, sci, core-async-flow, datastar, seon.turn, seon.render]
---

# Lane B2 — the walk is the history; flow is the launcher; the fork is the context

Owned: `src/seon/sci/*`, `src/seon/turn.clj`, `src/seon/run.clj` (rename),
`src/seon/cluster/{agent,wake,message,prompt}.clj`, `src/seon/flow.clj`,
`src/seon/oversight.clj`, `src/seon/render.clj`, `src/seon/render/*`,
`src/seon/print.cljc`, `src/seon/repl.clj`, `src/seon/ai.clj`, `src/my/*` except
`my/issue.clj`. Inputs read end to end: the brief, data-pack-b2, the
sci/turn/render deletion audit, the synthesis, the goals note, turn PRD §13–§16,
data-pack-a1 §A1-3, data-pack-c1 §1, data-pack-b3 §6b/§11, the
architecture-docs verification, and the four vendored seams in §3.

## 0. For the owner: what was dumb, and the simpler way

**What the code does today.** Every turn, the agent's context is rebuilt by
snapshotting all 5,326 bindings of the base SCI context into a map, forking,
walking the agent's own 5,326 bindings a second time, and diffing the two to
find the agent's private definitions (`sci/eval.clj:2100-2182`). Two atoms per
context copy the program's function rows out of the database so the code can
ask "is this function installed" without asking the database (`kernel.clj:108-182`).
The base context copies every first-party function's compiled root into a
fresh SCI Var at every acquisition — 7,725 copies — and must do it again after
any hot reload or re-arm because a copy does not follow its source
(`eval.clj:830-840`, `copy-var*`). The turn loop has three sibling functions
that fork, evaluate and settle in the same order but read their source from
different places (`turn.clj:4676-5010`), a 315-line function that does the
whole paid call (`:4175-4489`), a 252-line watchdog thread per turn that
re-implements the deadline core.async.flow already enforces (`:5011-5262`), and
a relay process whose only job is to turn a wake into another wake
(`cluster/agent.clj:442-464`). The agent's history is assembled by hand in a
2,443-line namespace with three extra views of the same evaluations, although
the ruling since 2026-09-08 is that the walk over stored evaluations through
the evaluation schema's declared render pair IS the history. The web page
keeps revisioned keyframe/delta packages, per-tab registration counts and a
render-invocation cache with hand-rolled invalidation, when the browser's
morph already diffs and Datahike already answers "did anything this read
depends on change". The namespace page clips its HTML through a three-tier
budget ladder, which the law forbids.

**Why that was the wrong shape.** Each is a mirror kept beside the authority
that already holds the fact, then machinery to keep the mirror current, then
tests policing the machinery's cost: the binding snapshot mirrors what
`:sci/generation` on each Var already says; the kernel atoms mirror the
database value the context already carries; the Var-root copies mirror the
JVM Var; the watchdog mirrors flow's `:compute-timeout-ms`; the transcript
mirrors the walk; the packages mirror the client morph; the invocation cache
mirrors read evidence plus the commit id. Every one does work proportional to
the whole program (or the whole page) on an event proportional to one change.

**The simpler way, as data flow.** The base context is a function of one
database value: namespaces from `:seon.ns` rows, first-party functions bound
as the JVM Var objects themselves (one 8.6 ms walk, measured §1, never
repeated — a re-arm or hot reload is visible through `Var.invoke`), agent-
authored rows interpreted, tests last. It is regenerated only when the
database's program commit id changes. An agent's context is `sci/fork` of
that base (0.02 ms); its private layer is exactly the Vars whose
`:sci/generation` equals the fork's, carried as objects across turns and
re-interned into a new fork when the base changes. The turn is one function:
open → prompt from the walk → provider (or system source) → evaluate →
settle, run as one flow proc whose in-port is the wake channel and whose
deadline is flow's own. The history is the walk over `seon.eval/of-agent`
through `seon.repl/render-ai|render-html`. The page is the whole current view
rendered per tab per batch over a dropping buffer and brotli-streamed by the
Datastar SDK we already vendor; whether a page must re-render is one read-
evidence check carried in the tab's loop. Every step is proportional to the
change: one evaluation, one wake, one page, one tab.

## 1. Goal and the numbers that prove it

| quantity | before (measured / declared) | after (target) | form (§4) |
|---|---|---|---|
| private-layer discovery per turn fork | `base-bindings` 1.5–2.4 ms + a second `namespace-state` walk + a merge walk (pack §2.5; total unmeasured) | `sci/fork` 0.002–0.02 ms + re-intern of N private Vars (N = the agent's defs, not the program's) | F1, F2 |
| first-party bind into the base ctx | 7,725 `copy-var*` root copies per acquisition, repeated after every reload (`eval.clj:830`) | ONE `ns-interns` walk binding Var objects: **8.58 ms** for 384 namespaces / 7,725 interns (probe P1, `default`, 2026-09-21) | P1 |
| a hot-reloaded or re-armed core function seen by SCI | after the next acquisition | immediately (P1: `[:old 1]` → `[:new 1]` through `Var.invoke`, also inside a fork) | P1 |
| ctx atoms carried per agent | 5 (`::base-bindings`, `::result-objects`, `::kernel/installed-functions`, `::kernel/program-snapshot`, `::print-session`, `eval.clj:2197-2204`) | 1 (`::result-objects`) | F3 |
| turn deadline mechanism | a parked `alts!!` thread + `backstop-state` atom per turn (`turn.clj:5053-5262`) | `.get` under `:compute-timeout-ms` in flow's own `proc` (`flow/impl.clj:258-260`) | F4 |
| procs per agent graph | 3 (`mailbox`, `turn`, `schedule`; `agent.clj:469-513`) | 2 | F4 |
| history render paths | 3 hand-assembled + the walk | the walk | F5 |
| web delivery | keyframe/delta revisions + per-tab registration + drain await (`web.clj:1849-1916`, `:2689-2891`) | whole view per batch, `(dropping-buffer 1)` tap, SDK brotli profile | F6 |
| render invocation cache | 198 lines + ~150 consumer lines (`render.clj:701-889`) | 0; commit id + read evidence (`same-committed-database?` `:786`, `db/read-evidence-current?`) | F6 |
| namespace page presentation | 6 renderings (3 tiers × AI/HTML) | 1 AI text (+ one elision value when the profile requires) + 1 HTML view | F7 |
| load-cycle breakers in owned files | 15 `defonce`+`delay`+`requiring-resolve` + 7 inline (`§2h`) | 0 | `grep -c requiring-resolve` |
| lines, owned src | 33,353 | **24,500** (§9) | `wc -l` |
| lines, owned tests | ~37,700 in 78 files | **~20,000** | `wc -l` |

Everything above 2 s in this area today is explained by an O(program) walk
(acquisition, base-bindings, whole-image arming) — none survives.

## 2. The data flow

### 2a. The agent context (deletes: `base-bindings`, `same-program-root?`, `regenerate-agent-context!`'s diff, the two kernel atoms, `installation-covers-program-change?`, `latest-print-fact`/`session-print-options`, `::print-session`)

| datum | computed when | carried in | recomputed on | proportional to |
|---|---|---|---|---|
| base ctx (program only) | first evaluation on a database value | the cluster handle (`:seon.sci.eval/ctx`) with `:seon.db/db` | the program commit id differs (`acquired-database?` `eval.clj:2252` — two ids compared) | namespaces × interns ONCE (8.6 ms); after that only `:agent` rows that changed (`install-row!` per row) |
| first-party (`:core`) bindings | base ctx construction | SCI `:namespaces` map holding the **`clojure.lang.Var` object** (P1) | never — the Var follows reload and re-arm by itself | 0 per change |
| agent-authored (`:agent`) rows | base ctx construction / `install-row!` on commit | sci Vars at the base generation, armed there by `install-function-contract!` (`eval.clj:694-709`) | that row's commit | 1 row |
| an agent's fork | first turn | the agent's retained handle (`:seon.sci.eval/agent-ctx`) | base change → `sci/fork` of the new base + re-intern private Vars | N private Vars |
| the private layer | as the agent `def`s | Vars with `(= (:sci/generation (meta v)) (:sci/generation @(:env ctx)))` (`utils.cljc:362-379`) | never derived, only carried | — |
| result objects | each evaluation | `::result-objects` atom on the fork; handle `result/e<id>` (`admit.clj:614`) | never; JVM restart empties it, the history says so | 1 |
| print options | evaluation time | the render profile (§2.4 law) | — | 0 |

**Why arming never runs against a fork after this cut (pack Q4 answered: A, by
construction).** A contracted `defn` IS a program row; it is committed by the
writer and installed at the base (`install-row!` `:841`), where `bind-root!`
runs at the base generation and mutates in place. `:core` rows are JVM Vars —
`arm-var!` (`instrument.clj:860-896`) binds the JVM root, and SCI calls
through `Var.invoke`. The private layer holds only uncontracted values (`def`,
atoms, results), which arming never touches. `gate-function-install`
(`turn.clj:3253`) evaluates the candidate in `(sci/fork agent-ctx)`, and
acceptance is the writer's commit, after which the base reacquires that one
row. No sci fork change is needed for generation discrimination; the audit's
"fork change 1" is withdrawn.

### 2b. The turn (deletes: `resume-turn`/`close-turn`/`generate-turn` as siblings, the backstop, the mailbox proc, `CountedSlidingBuffer`, `submit-evaluation!!`, delimiter repair, schema-change machinery, declaration helpers, `declaration-diverged-since-open?`)

| datum | computed when | carried in | recomputed on | proportional to |
|---|---|---|---|---|
| next work | on each wake message | proc state → `next-agent-work` (`turn.clj:2888`) over one `db/db` | each wake (sliding-1: coalesced) | this agent's open turn + unanswered wakes (indexed `:t` reads) |
| the source of a turn | one place: `turn-source` = `:generate` → system-turn forms; `:call` → provider reply; `:resume` → stored unevaluated forms; `:close` → none | the request map | per turn | forms in this turn |
| evaluation + settlement | `evaluate-sources` (`:4519`) → `settle!` (`:3742`) | transaction report | per form | 1 form |
| deadline | flow's `proc`: `.get ms` on the futurized transform (`impl.clj:258-260`) | the proc option `:compute-timeout-ms` = the dial `:seon.config.agent/turn-completion-backstop-ms` (B3 owns the key name; B2 reads it at one site) | per transform | 0 |
| the deadline's report | `TimeoutException` caught at `impl.clj:308-318` → `#::flow{:pid :ex :msg :state}` on `::flow/error` → the error fanout (`flow.clj:1133-1341`) → `fault-committer-step` | a durable fault | — | 1 |
| wake delivery | Datahike listener → the agent's wake channel `(async/chan (async/sliding-buffer 1))` = the turn proc's `::flow/in-ports {:seon.agent/wake …}` (the construct `mailbox-step:456` already uses) | — | — | 1 |

**The diagnostic constructor receives the deadline as data, never by
polling.** `turn-completion-error` (`turn.clj:5011`) becomes
`(turn-completion-error flow-error-map)`: the fault committer already
receives every `::flow/error`; when `(::flow/pid m)` is `:seon.agent/turn` and
`(::flow/ex m)` is a `java.util.concurrent.TimeoutException`, it commits the
constructor's value with `:seon.agent/id` from the proc's args and
`:seon.turn/id` from `::flow/state` (`ping-map-fn` already exposes it,
`:5296`). Two facts flow forgets that we must state: (1) `.get` with a
timeout does NOT cancel the `FutureTask` — the transform keeps running; the
bounds that STOP work are SCI's `time-limit`/`:interrupt-fn` and the
provider deadline, and flow's deadline is the bug report that one of them
failed (§2.3: a bound firing names what never arrived); (2) after the
report the proc keeps its previous state and continues (`impl.clj:317`), so a
second wake reruns `next-agent-work` from facts — correct by the crash model.

**Executor.** The paid call is IO. Fork change **F-async** (3 lines,
`impl.clj:257-261`): apply the futurize+`.get` deadline whenever
`:compute-timeout-ms` is supplied, for any workload, so the turn proc stays
`:io` on virtual threads (`dispatch.clj:82-89`). No-fork fallback: declare
`:compute` and hand `create-flow` `:compute-exec` = the root's virtual-thread
executor (`flow.clj:101` accepts any Executor) — rejected unless the fork is
refused, because the tag would misname the workload (AGENTS §1.4).

### 2c. The history, the page, the namespace page

| datum | computed when | carried in | recomputed on | proportional to |
|---|---|---|---|---|
| history | `walk/history` (`walk.clj:979`) over `seon.eval/of-agent` (`eval.clj:9`) through `seon.repl/render-ai|html` (`seon.eval.edn:7-8`) | the walk's units | each render | evaluations of one agent (stored shown text, no re-execution) |
| prompt | `prompt/compose` (`prompt.clj:266`) over the walk's units; whole-unit selection under the token budget (stays: the one legitimate second bound) | attempt row (`:seon.ai/attempt` digest) | each turn | units |
| a page view | per tab, per batch: `(render-page db registration-key)` on the tab's virtual thread | the tab's loop state: last basis `:t` + the read evidence the render captured (`db/*read-evidence-sink*`) | a value on the tab's `(dropping-buffer 1)` tap of the refresh mult; skipped when `db/read-evidence-current?` says nothing it read moved | one page; coalesced per tab |
| delivery | `datastar.http-kit/->sse-response` with `write-profile (brotli/->brotli-profile)` (`sdk-brotli/brotli.clj:99-113`) and one `patch-elements!` of the whole view | the connection | per batch | bytes of one view (brotli-streamed; no keyframe/delta) |
| namespace page AI | `ns/render-ai` full text; if the profile's token budget is exceeded, ONE `seon.print` elision value naming omitted definitions and the requery `(dir ns)` — the AI render function is a legal clipping spot | — | per render | definitions |
| namespace page HTML | the full view, never budgeted | — | per render | definitions |
| walk distance | kept as declared: a **query-work** bound reported as an elision (`walk.clj:698`, brief ruling) | the walk request | — | — |

The render proc, `pages-mult` packages, `::packages`/`::calls`/`::invocations`
retention (`web.clj:2340-2428`), `register-tab!`/`deregister-tab!`,
`write-package!`'s drain await and `await-feed-package!` dissolve: the refresh
mult carries only "look" (the Datahike listener's transaction event), and the
tab renders. This is `hyperlith/impl/datastar.clj:139-182` with our render.

## 3. Reading list (verified at the current gitlinks by data-pack-b2 §2; re-read this session)

| block | guarantees |
|---|---|
| `reference-code/sci/src/sci/core.cljc:345-350` `fork` | a new env atom over the same namespace map + a fresh generation; no per-Var work |
| `reference-code/sci/src/sci/impl/utils.cljc:356-379` `next-generation`, `generation-meta`, `bind-root!` | equal generation ⇒ mutate in place; unequal ⇒ copy stamped with the ctx's generation, interned into this env only |
| `reference-code/sci/src/sci/core.cljc:273-276` `bind-root!` public seam; `:260-271` `intern` | the two writes the private layer and result handles use |
| `reference-code/sci/src/sci/core.cljc:712-800` `namespace-bindings`, `namespace-interns`, `namespace-state`, `install-namespace-bindings!` | resolver structure installable as data per namespace row; refers are qualified symbols |
| `reference-code/sci/src/sci/core.cljc:294-322` `eval-string` docstring, `:call-preparation-hook` "a host Var an embedder installed directly into `:namespaces`" (fork commit `6ee57c9c`) | a `clojure.lang.Var` in the namespace map is a supported callee; P1 shows it is called through `Var.invoke` and survives `fork` |
| `reference-code/sci/src/sci/core.cljc:112-140` `copy-var*` | what we delete: copies the ROOT; never follows the source Var |
| `reference-code/core.async/.../flow/impl.clj:29-36` `futurize`; `:243-261` `proc` (`compute-timeout-ms` default 5000, the `.get`); `:308-318` the `::flow/error` map | the deadline and its ONLY report shape; `.get` does not cancel |
| `reference-code/core.async/.../impl/dispatch.clj:82-116` | `:io` = virtual threads; `executor-for` memoized per tag |
| `reference-code/core.async/.../flow.clj:76-101` `create-flow` (`:io-exec`/`:compute-exec`); `:136-155` `ping`; `:186-188` `:compute-timeout-ms` | per-proc status is `ping`, not a capacity observer |
| `reference-code/hyperlith/src/hyperlith/impl/datastar.clj:122-189` `render-handler` | whole view per batch; `(dropping-buffer 1)` tap because the mult distributes synchronously; on-close closes the tap |
| `reference-code/datastar-clojure/libraries/sdk-brotli/.../brotli.clj:86-113` `->brotli-os`, `->brotli-profile`; `sdk-http-kit/.../http_kit.clj:23-76` `->sse-response` (`write-profile`) | the brotli writer is ALREADY in the SDK we vendor ("Code taken from hyperlith"): vendoring 60 lines is unnecessary |
| `reference-code/datastar-clojure/libraries/sdk/.../api.clj:267` `patch-elements!`; `api/elements.clj` `->patch-elements-seq` (our one consumer `web.clj:1872`) | one event per view |
| first-party idioms: `seon.flow/var-process` (`flow.clj`), `fault-committer-step` (`:945-1132`), `walk/history` (`walk.clj:979-1016`), `repl/render-ai|html` (`repl.clj:447-517`), `wake/wake-attributes` (`wake.clj:92`), `install-row!` (`eval.clj:841-935`) | the mechanisms that survive and that the cut builds on |

## 4. REPL protocol (`mcp__seon__eval_clj`, jvm, `read_only`, cluster `default`)

| id | form | before (measured) | after |
|---|---|---|---|
| P1 (done) | `(let [v (clojure.lang.Var/create (fn [x] [:old x])) ctx (sci.core/init {:namespaces {'probe {'f v}}}) before (sci.core/eval-string* ctx "(probe/f 1)") _ (.bindRoot v (fn [x] [:new x])) after (sci.core/eval-string* ctx "(probe/f 1)") fork (sci.core/fork ctx) in-fork (sci.core/eval-string* fork "(probe/f 2)") first-party (filter #(clojure.string/starts-with? (str (ns-name %)) "seon.") (all-ns)) t0 (System/nanoTime) bound (into {} (map (fn [n] [(ns-name n) (into {} (ns-interns n))])) first-party) t1 (System/nanoTime)] {:before before :after after :in-fork in-fork :resolved-type (str (type (sci.core/resolve ctx 'probe/f))) :namespaces (count bound) :interns (reduce + (map count (vals bound))) :walk-ms (/ (- t1 t0) 1e6)})` | `{:before [:old 1] :after [:new 1] :in-fork [:new 2] :resolved-type "class clojure.lang.Var" :namespaces 384 :interns 7725 :walk-ms 8.581}` | the same form; the `:core` branch is this binding |
| F1 | `(let [h (get-in @seon.operator.runtime/running-instances ["default" :seon.turn.loop/cluster]) ctx (:seon.sci.eval/ctx h)] (time (#'seon.sci.eval/base-bindings ctx)))` | 1.5–2.4 ms (pack §2.5) | the Var is deleted; the form fails to resolve = done |
| F2 | `(let [ctx …] (time (sci.core/fork ctx)))` | 0.002–0.02 ms | unchanged |
| F3 | `(let [a (get-in @seon.operator.runtime/running-instances ["default" :seon.agent/routing])] (keys (get-in (seon.cluster.agent/armed a "root") [:seon.turn.loop/cluster :seon.sci.eval/agent-ctx])))` | contains `::base-bindings ::kernel/installed-functions ::kernel/program-snapshot ::print-session` | only `::result-objects` among ours |
| F4 | `(clojure.core.async.flow/ping (get-in @seon.operator.runtime/running-instances ["default" :seon.flow/graph]))` and the agent graph's ping | three agent pids; turn `:workload :io` + backstop | two pids; the turn proc's `:seon.turn/id` in `::flow/state` |
| F5 | `(seon.render.walk/history {:seon.db/db (seon.db/db (seon.operator/connection "default")) :seon.render.walk/lookup [:seon.agent/id "root"]})` | works today; `seon.render.transcript/render-ai` also works | only the walk resolves |
| F6 | fetch `/agent/root` twice, then `(get-in @seon.operator.runtime/running-instances ["default" :seon.render.web/view])` keys | `latest-packages`, `registration`, `pages-mult` present (P2 on 2026-09-21: `latest-packages` an Atom holding `{}` — no tab open, sizes unmeasured) | one refresh mult; no packages; response header `Content-Encoding: br` |
| F7 | `(seon.render.ns/render-html {:seon.db/db … :seon.ns/name 'seon.render.walk})` | tiered | the full view; `render-ai` returns the text or one `:seon.print/elided` value |

## 5. The work, ordered as commits (each net-negative unless named; each ends with `clojure -M -e "(require 'seon.turn 'seon.render.web 'seon.sci.eval)"` and `require :reload` on `default`; probe = the one debug read that shows `default` is sound)

| # | commit | deletes | adds / converts | probe |
|---|---|---|---|---|
| 1 | **fork F-async** (core.async): `:compute-timeout-ms` applies the futurized `.get` for any workload | — | 3 lines at `flow/impl.clj:257-261`; push the fork | `(flow/ping graph)` on a scratch cluster |
| 2 | `deps.edn`: `dev.data-star.clojure/brotli {:local/root "reference-code/datastar-clojure/libraries/sdk-brotli"}` (+ brotli4j natives it declares) | — | one dep | `(require 'starfederation.datastar.clojure.brotli)` |
| 3 | **`:core` = the JVM Var** — `install-jvm-root!` binds the Var object; `install-first-party-namespaces!`/`load-core-namespaces!`/`host-namespace!`/`classpath-locatable?` fold into one walk over `:seon.ns` rows with `:core` admission; `installation-covers-program-change?` deleted (`acquired-database?` is the answer) | ~500 | ~60 | P1 on the live base ctx; `runtime_status` |
| 4 | **one atom per fork** — `fork-for-turn` = `sci/fork` + re-intern private Vars by generation; delete `base-bindings`, `same-program-root?`, the kernel snapshot/installed atoms (`kernel.clj:108-182`, `eval.clj:273-274, 2148-2154, 2197-2204, 2384-2386`; `render.clj:293,679` read the row from the ctx's `:seon.db/db` instead), `latest-print-fact`/`session-print-options`/`::print-session` | ~330 | ~40 | F1–F3; one SCI eval defining an atom, a turn, the atom still there |
| 5 | **doc/dir as data** — `program-documentation … install-program-doc!` (`eval.clj:1407-1658`) → one pull of `:seon.fn/{sym,arglists,doc,spec}` rendered by the declared pair; candidate path keeps `evaluate-candidate` over `sci/fork` (`fork-candidate-ctx`/`accept-candidate!` go; the writer decides) | ~350 | ~80 | `(dir my.plan)` in sci mode returns data |
| 6 | **turn = one function** — `turn-source` selects the source per situation; `resume-turn`/`close-turn`/`generate-turn` deleted; `call-turn` split into `provider-attempt` (the paid call + `ai/disposition`) and the shared evaluate/settle tail; delimiter repair deleted (`sci.reader`'s typed refusal is the reply); schema-change machinery + declaration helpers + `declaration-diverged-since-open?` + `row-tx` prelude deleted (the writer's final-report validator and `program/exact-replacement-tx` own them); since-diff = one `db/since` over read evidence (`system-turn` 390 → ~150) | ~1,300 | ~250 | one virtual turn through the debug control; `:seon.turn/closed-tx` present |
| 7 | **flow is the launcher** — delete the work launcher (`flow.clj:241-944`), capacity observer (`:192-240`), the backstop (`turn.clj:5053-5262`), `await-turn-permit!`, `agent.clj:825-845` caller; turn proc `:io` + `:compute-timeout-ms`; `turn-completion-error` takes the `::flow/error` map; fault committer commits it; `effect.clj:991` (B3 file — the second launcher caller) converted in the same commit or STOP if held | ~1,050 | ~60 | F4; a deliberately slow SCI form → one fault row naming the turn |
| 8 | **the wake is the in-port** — mailbox proc, `CountedSlidingBuffer`, `wake-channel`'s counter, the conn deleted; `::flow/in-ports {:seon.agent/wake ch}` in `turn/step`'s init arity; `:seon.agent/episode` port gone; `episode-runs` → `turns-since-wake` (the config key stays B3's) | ~120 | ~15 | F4; send a message to root, the turn opens |
| 9 | **the walk is the history** — delete `render/transcript.clj`; `seon.runtime.edn:4-5,11-12` pairs → `render/agent.clj` thin functions over `walk/history`; `inbox-form`/`message-form` → `cluster/message.clj`; `seon.render.transcript/request-error` → `:seon.render/request-error` (schema key rename, RESET NEEDED); consumers `web.clj:1481`, `eval/drive.clj:285` → the walk; `bootstrap.clj:816` symbol literal (B3 file: convert or STOP) | ~2,450 | ~150 | F5; `/agent/root` history column paints; prompt bytes for one reply identical on page and in the prompt |
| 10 | **whole view per batch** — delete packages, registration, drain await, the render proc's retention and the invocation cache (`render.clj:701-889`, consumers `web.clj:436-554, 1409-1576, 2083-2511`, `walk.clj:442-497`, `turn.clj:1910-1918`); keep `source-generation` (`:692`), `same-committed-database?`, `refresh-read-evidence`; per-tab render on a `(dropping-buffer 1)` tap; `->sse-response` with the brotli profile; `*walk-context*` deleted (the request carries custody) | ~900 | ~120 | F6; two tabs open, one transaction, both repaint |
| 11 | **namespace page** — ladder deleted (`ns.clj:416-837`); `render-ai` full text or one elision value; `render-html` full; `ns.clj:405` atom → the projection's `:seon.schema/key` index | ~440 | ~30 | F7 |
| 12 | **debug page** — keep the four §16 sections; delete `debug-applicable-candidates`, `applicable-renderers-html`, `generic-entity`, `declared-entity-units` (use `render/selection-inspection` `:616`, `walk/root-selector`, `walk/neighborhood`); `namespace-candidates` + the namespace stage deleted (the declared property datom selects) | ~750 | ~40 | `/agent/root?debug=true` shows state line, context now, would-be system turn, prompt digest |
| 13 | **`seon.run` → `my.turn`** — fold the four functions into `my.turn` (they are value constructors; the thin protocol is the owner-facing surface, `my.*` rule); delete `run.clj`; `background.clj:4`, `my/agent.clj:5` requirers; Datalog literal `seon.run/walkthrough` → `my.turn/walkthrough`; the 21 test files' spellings; `bootstrap.clj:863,871` opening bytes (B3 file — convert or STOP). Stored evaluations on `default` hold the old bytes: **RESET NEEDED** (data disposable) | 149 | ~90 | `(my.turn/complete {...})` in sci mode |
| 14 | **cycles** — the 15 delays and 7 inline `requiring-resolve` go with the layering in §2h; `repl.clj:26` (`episode-runs`) and `render.clj:48` (`opening-db`) become arguments the caller hands; `render.clj:46` (`web/derive-context!`) deleted with the cache | ~60 | 0 | `clojure -M -e "(require 'seon.turn)"` from a cold JVM |
| 15 | **tests** (§7) | ~17,000 | ~1,200 | `seon.test/check` over the three turn classes |

### 2h. Load cycles remaining after the deletions, and the cut

| cycle today | breaker | after |
|---|---|---|
| `turn` ↔ `cluster.agent` (`turn.clj:52,54`) | `acquire-context!`, `submit-source!` delays | the proc args carry the agent's handle; `turn` receives `acquire-context!` as a function on the request (§2.1) — `cluster.agent` requires `turn`, never the reverse |
| `turn` ↔ `cluster.prompt` ↔ `context` (`:56,58`) | `prompt`, `capture-tx` delays | `prompt` and `context` stop requiring `turn`: their `:seon.turn/*` reads are Datalog over attributes (`prompt.clj:74-79,326-334`), no function of `turn` needed; `turn` requires `prompt` statically |
| `turn` ↔ `problems` (`:60,62`) | `assignment-value`, `form-problem` delays | the routed-problem settlement block (`turn.clj:2397-2613`) leaves `turn` for the task family (B3); `problems.clj:191-211` calls to `turn/unbound-value?` etc. go with it; `turn` no longer requires `problems` |
| `render` ↔ `render.web` ↔ `turn` (`render.clj:46,48`) | delays | `render` never requires `web` or `turn`; `web` requires both (top of the order) |
| `render` → `walk` (`render.clj:50`) | delay | `walk` requires `render`; the walk entry is called by `web`/`prompt`, not by `render` |
| `render.value` → `render`, `config` (`value.clj:23-27`) | delays | the profile is on the request (§2.1); `value` requires nothing above it |
| `repl` → `turn`, `cluster.agent` (`repl.clj:26,28`) | delays | `turns-since-wake` handed by the caller; `live-response` receives the ctx on the unit (it already reads `:seon.sci.eval/agent-ctx unit`) |
| `admit` → `kernel` (`admit.clj:62`) | delay | `kernel` requires `admit`, not the reverse (interrupted? moves next to the guard's reader) |
| `flow` → `operator.runtime` (`flow.clj:31`) | delay | executors ride the request (`:seon.flow/executor handle` already does at `agent.clj:511`) |

Resulting order (each requires only lower rows): `print` → `render.value` →
`repl` → `render` → `render.walk` → `sci.*` → `cluster.prompt`/`context` →
`turn` → `cluster.agent` → `render.web`/`oversight`.

## 6. Better than the floor (probe first; each decides one row of §5)

| candidate | smaller than | probe that decides | outcome |
|---|---|---|---|
| bind first-party namespaces as JVM Var objects | the audit's `copy-ns` (a compile-time macro over `ns-publics`; still copies roots) | P1 | **decided**: 8.6 ms once; reload/re-arm visible immediately; fork-safe |
| the SDK's brotli profile | vendoring hyperlith's writer (~60 lines) | read `sdk-brotli/brotli.clj:99-113` | **decided**: zero lines vendored; one dep |
| per-tab render (hyperlith) instead of a render proc with whole-view packages | the render proc + pages-mult + interest | `(time (seon.render.web/current-page service (db/db conn) "root"))` on `default` with the agent page: if one whole view renders in tens of ms, the proc is a mirror | lane measures; write the number in the landing note |
| since-diff as ONE `db/since` over the union of read evidence | five helpers (`turn.clj:1909-2103`) | `(seon.db/since db t)` filtered by the evidence's attributes for root's last turn; count datoms vs helpers' answer | expected: one query, 390 → ~150 lines |
| F-async fork (deadline on `:io`) | `:compute` tag with a supplied executor | `flow/ping` state and one deliberate timeout on a scratch cluster | recommended; fallback stated in §2b |

## 7. Tests

| today | disposition |
|---|---|
| 7 turn namespaces, 143 deftests, 8,786 lines (pack §7) | **3 classes**: `test/seon/turn_test.clj` (transitions: open/evaluate/settle/close as facts — from `turn_test` + `turn_loop_test`'s pure parts), `test/seon/turn_wake_test.clj` (next-work, answering by `:t`, continuation, bound — from `turn_work_test` + `turn_continue_test` + `turn_work_cost_test` + `turn_error_test`), `test/seon/cluster/turn_test.clj` (one real cluster, one provider reply per kill-position class, ~1/4 of today). `turn_backstop_test.clj` (93) dies with the backstop; the deadline gets ONE regression: a slow form → one fault naming the turn |
| `render/transcript_test` 1,431 + `history_test` 307 + `transcript_run_test` 100 + `episode_test` 50 | `history_test.clj` keeps ONE class regression: `one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt` (today `transcript_test.clj:1172`) rewritten over the walk; the rest dies |
| `render/call_test` (31), `retained_test` (198), `web_context_test` (252), `render_simplification_test` cache/package cases | die with the cache and packages; `web_feed_test` (26) becomes the two-tabs-repaint regression |
| `flow_test` 8 launcher deftests (~500) | die; keep `:1427` (child JVM death) |
| `sci/eval_test.clj:159,208-215` base-bindings | rewritten: "a private atom survives a base change; an inherited program Var does not become private" over `:sci/generation` |
| `sci/lazy_acquisition_test` (44, 30 s), `sci/eval_instrumentation_test` ns-level long, `sci/kernel_arm_carriage_test` (233) | acquisition test rewritten as "second acquisition on an unchanged commit id installs zero rows" with the default 5 s bound; carriage test dies with the atoms |
| `render/ns_test` (546): tier names appear nowhere | keep; add one assertion: HTML equals the full view under any profile |
| `oversight_test` `CountedSlidingBuffer` site | dies with the buffer |
| `render/web_test` 2,340, `web_debug_test` 1,071 | cut to the four §16 sections and the SSE join; ~1/3 |
| time escapes in the area (audit §5 table): `transcript_test:968` 66 s (dies), `lazy_acquisition:9` 30 s (rewritten, default bound), `turn_test:1322` 60 s ("two-analysis publication" — B1's seam; move the test to B1's class or delete), `error_result_test:99` 60 s no reason (B3 file; refused by the brief's rule), `ai_stream_fold_test:373` 90 s isolated store (genuinely long: keep, reason + number), `concurrency_independence_test` 137 s (dies: it verifies every receipt through the machinery §5 deletes; one 5-agent fold under 5 s survives), `print_test:240,264` 94 s (200 trials → 20; default bound), `my/test_test:13` 300 s (acquisition — after commit 3 the default bound holds; keep the test, drop the escape), `cluster/armed_test` ns-level (split per test, keep) | as stated |
| namespaced-map metadata `^#:seon.test{:long …}` invisible to grep (`source_lineage_test.clj:20,149`) | B4's selector defect; named here, not fixed here |

The lane runs only `seon.test/check` over the tests reaching each commit's
changed functions (`seon.fn/tests-reaching`); never a suite.

## 8. Done, landing note, stop rules

**Done** = §1's table filled with measured afters; `grep -c requiring-resolve`
over owned files = 0; `wc -l` at or under §9; every commit's probe recorded;
`bin/seon reset --force` run once after commits 9 and 13 (RESET NEEDED: the
`:seon.render/request-error` key and the opening bytes; nothing durable lost).

**Landing note**: `docs/prds/agent-platform/landing/lane-b2.md` — the §4 forms
with values, the per-tab render time (§6 row 3), the since-diff datom count,
the F-async fork commit id, and the deletions as `git diff --stat`.

**Stop rules** (report, do not work around): a held file (`git status` names a
foreign dirty path); `effect.clj:991`, `bootstrap.clj:816,863,871`, the
`plan.clj` boundary (the seven `settle-call`/`run-issue-tests!` sites collapse
to two at the one settlement point but stay until B3 lands `seon.task`), the
config key names (`:seon.config.run/max-episode-runs`,
`:seon.config.agent/turn-completion-backstop-ms`) and the `seon.error` render
split (b3 §11 row 10) — each a named seam of B3; the per-function digest (B1)
is not needed by this lane. Unsettled at three options in the note: the
F-async fork being refused (§2b fallback), and the debug page's "context now"
as-of check cost if it exceeds 2 s for root's history (options: per-evaluation
as-of on demand; a digest column; drop the check).

## 9. Size target

| file | today | audit floor (delta) | target | reasoning for the gap |
|---|---:|---:|---:|---|
| `turn.clj` | 5,417 | −760 (backstop, delimiter, schema/decl helpers, siblings) | **3,300** | the routed-problem block leaves for B3 (−207); since-diff one query (−240); `call-turn` split loses its 60-line comment novellas; `step`'s 20-line docstring describes a mechanism that is gone |
| `sci/eval.clj` | 3,382 | −1,050 | **2,400** | the `:core` branch is one walk (−180 beyond the floor's "delete the walk"); documentation as one pull |
| `sci/kernel.clj` | 750 | −120 | **600** | |
| `render/web.clj` | 3,694 | −690 | **2,300** | per-tab render also deletes the render proc's retention and interest (−400 beyond the floor) |
| `render/transcript.clj` | 2,443 | −1,586 | **0** (+150 in `render/agent.clj`, `cluster/message.clj`) | the survivors are declared pairs, thin over the walk |
| `render.clj` | 1,891 | −430 | **1,400** | |
| `flow.clj` | 1,364 | −750 | **600** | |
| `render/ns.clj` | 936 | −422 | **480** | |
| `cluster/agent.clj` | 1,048 | −55 | **900** | backstop caller and permit plumbing go with commit 7 |
| `run.clj` + `my/turn.clj` | 149 + 41 | — | **0 + 120** | |
| `repl.clj`, `render/value.clj`, `render/walk.clj`, `print.cljc`, `prompt.clj`, `wake.clj`, `message.clj`, `oversight.clj`, `ai.clj`, `admit.clj`, `reader.cljc`, `my/*` | 11,000 | ~0 | **~10,800** | delays removed; walk's distance report kept by ruling; `print.cljc` and the value renderer are not duplicates |
| **owned src** | **33,353** | **≈28,550** | **24,500** | 27 % — dissolution (one turn function, one bind, one history, one delivery) goes past deleting mechanisms |
| **owned tests** | **~37,700** | **≈33,200** | **~20,000** | seven → three turn classes; the cache/package/launcher/transcript suites die whole |
