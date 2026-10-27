# Shrink by convergence, 2026-10-22 (read-only audit)

This audit looks for places where Seon on Jolt builds the same idea more than once, and proposes one reusable implementation for each so `src` gets smaller. It changed no code, started no root and did not touch `runs/store`. Main is `6114183`, and `src` is 16,842 lines against the ~10k aim.

**Method.** I read every namespace over ~300 lines, grepped each repeated idiom across `src`, and checked the earlier audit `../notes/AUDIT-2026-10-21-growth.md`. Its dead-code list is gone now; I re-grepped each name and only `seon.program/edn-value?` still exists, so none of that list is proposed again here. The line savings are my estimates from the code as it stands. I did not measure them, and they assume the guarding tests stay as they are.

**The headline.**
- Converging these mechanisms deletes **about 960 lines** of `src`.
- Separately, the harness (`seon.check`, `seon.e2e`, `seon.fixtures`: 1,945 lines today, about 1,500 after convergence) can leave `src`. That is the already-filed `seon.main/drivers-leave-src`. It is a move, not a deletion, so I count it apart.
- Together they take `src` to about **14,400**. That is still not 10k. What remains is mostly core (the printer, the reconciler, the writer, merge, the program) and needs its own audit.

## Ranked table

| # | Convergence (onto what) | Net src lines | Risk | Guarding tests (must stay green) | What it makes cheap |
|---|---|---|---|---|---|
| 1 | Check modes become one harness: `run-check` (boot, n agents ready, body answers `{:checks :report}`, verdict, stop, exit) plus one event-driven wait. Overlapping modes are folded into others. | **-280** | low (harness only) | `bin/check` (every step), `seon.check-test/*`, `seon.root-test/root-threads-are-what-root-declares` (reads the threads line) | A new check mode is ~10-20 lines of data and assertions, not ~50-60 lines of setup |
| 2 | One request/answer helper in `seon.step` (`ask`, `answered`) replaces five hand-rolled rid/pending tables. This deletes a clock (`ASK-BOUND-MS`). | **-70** | medium | `seon.procs-test/*` (api-entrance, reconciler, agent-stand-in, a-throwing-step-answers-every-pending-request), `seon.render-test/render-step`, `seon.web-test/calls-become-requests`, `bin/check smoke drain backpressure idle` | A new proc that asks root is one `ask` call and one `case` arm |
| 3 | e2e scenarios become data: one `run-scenario` over `seon.fixtures/scenarios` entries. | **-120** | low | `seon.e2e-test/*`, `bin/check e2e-*`, `resume` | A new scenario is one fixtures entry plus its expectation (~5-10 lines) |
| 4 | The agent and task lifecycle are declared once as data: the status enum carries its sets (live, stopped, ended); the task's observed state lives in the reconciler's index; one task row is shared by ui, ai, entrance and e2e. | **-90** | medium | `seon.render-test/observed-task-states`, `stop-row-says-how-it-ended-once-main-knows`, `problems-tile`; `seon.reconcile-test/*`; `seon.problems-test/*`; `bin/check e2e-calc` | A new status or state is one enum entry; every view follows |
| 5 | The renders converge: one metric-tile render over data, one bounded-list helper (`print/bounded`), and `seon.render.ai` reuses `seon.render.ui`'s derivations and `prose`. | **-80** | low | `seon.render-test/*` (root-context-names-what-each-list-left, no-cut-on-the-page-drops-silently), `seon.print-test/more-says-how-many-and-where`, `bin/check idle` | A new tile is a map; a new bounded list is one call, with its bound in config |
| 6 | Small helpers that exist twice or more: 4 token estimators become 1; plus `line-starts`, `ms-since`, `ns-tid`/`form-tid`, `source-of`, `update-tx`, `kname`/`kn`, `out!`/`step-out`/`vswap!`. | **-70** | low | unit tests of each owner (model, context, source, merge, program, docs, task_files, print) | One sizer means context, history and printer budgets agree |
| 7 | One door, the entrance verbs: web calls and MCP tools become entrance requests; root's REPL eval goes through the REPL path; the second recorder and the `intern` are deleted. | **-70** | medium | `seon.web-test/calls-become-requests`, `ui-call-allowlist`; `bin/check mcp`; `seon.check-test/a-check-leaves-its-store-as-it-found-it` | A new verb is one row that reaches UI, MCP and the entrance at once |
| 8 | Config has one shape: `start!` opts become a config overlay (`--set` layer); `with-provider` is deleted; reconciler defaults are read with `(cfg/value k {:task t})`; fences become config keys. | **-60** | medium | `seon.config-test/*`, `seon.reconcile-test/max-running-caps-parallel-attempts`, `environment-change-restarts-clean`; `bin/check config problems window lease` | A new knob is one key in `default.edn`: no `start!` option, no `def` |
| 9 | REPL reply items are built as data in-process. Today values go `pr-str` into `"seon/*"` string keys and are `edn/read-string` back, in the same process. | **-45** | medium | `seon.repl-test/*`, `seon.agent-test/each-eval-call-is-answered-before-the-next`, `bin/check robust record admit` | A new per-form fact is one assoc, not an encode, a key and a decode |
| 10 | One fork helper (the spawning pillar) for the fork server's `:fork` and `:standby`, the clone's double fork, and the test fork. | **-30** | medium | `bin/check threads robust smoke` (clone), `seon.procs-test/the-fork-server-step`, `seon.agent-test/a-killed-test-run-names-the-test-that-was-running` | A new forked role is one call |
| 11 | "What is wrong" has one producer: `seon.render.ai/problems` reads recorded problems instead of re-deriving agent problems from the view. | **-25** | low-medium | `seon.render-test/problems-tile`, `root-context`; `seon.problems-test/*` | The banner and root's context cannot disagree |
| 12 | (move, not convergence) `check`, `e2e` and `fixtures` leave `src` (`seon.main/drivers-leave-src`) | **~-1,500 moved** | low | `bin/build` (`-main` moves to a driver namespace), `bin/check` | `src` measures the system, not its drivers |

---

## Evidence, per item

### 1. Check modes: one harness, not 25 copies of setup

**What repeats.** Each of the 25 root-booting modes in `src/seon/check.clj` hand-writes the same frame. Grepped counts in check.clj:

| Idiom | Count |
|---|---|
| `c/start!` | 27 |
| `spawn!` | 33 |
| `await-status` | 37 |
| `c/stop!` | 24 |
| `c-exit` | 36 |
| `"X OK" "X FAILED"` verdict lines | 21 |
| `pprint` | 19 |
| `Thread/sleep` | 21 |

Examples:
- `smoke` (check.clj:21-104);
- `admit` :153-169, `record` :171-188 (frame and body nearly identical);
- `config-check` :968-988, `threads-check` :1095-1119;
- `lease` :1133-1164.

The verdicts are not even uniform: some print `FAIL`, some `FAILED`, and `contracts`/`census` print `:result :CONTRACTS-OK` inside a map (:150, :314).

**Waits written four ways, three of them polls or clocks** (AGENTS §8):
- `await-status`: a deadline loop with `Thread/sleep 2` (check.clj:9-15);
- `until`: a deadline loop with `Thread/sleep 100` (:190-192);
- `idle`'s `await-more`: a deadline loop with `Thread/sleep 5` (:1188-1191);
- three `(loop [k 0] ... (Thread/sleep ..))` waits (:469, :526, :884);
- but `seon.e2e/await-db` already exists: a watch on main's connection, with no polling (e2e.clj:17-26).

**Phase pairs written three times.** `durable-write`/`durable-read` (:584-662), `window-write`/`window-read` (:664-698) and e2e `resume`/`resume2` (e2e.clj:150-174) share one shape: phase 1 boots on main `nm`, works, then `c-kill` of itself; phase 2 reopens and asserts. bin/check orchestrates each pair separately (bin/check:116-123, :134).

**Overlaps.**
- `swarm` (check.clj:502-549) is the API-level twin of `seon.e2e/swarm` (e2e.clj:291-316). It has no verdict at all: it always exits 0 (:549).
- `program` (:319-347) proves merge-gating, which `smoke` (merge of a1/a3, a2 refused) and e2e calc already prove.
- `threads-declared` (:754-772) is `lifecycle`'s first half (:704-752) printed as EDN.

**Converge onto.**
- A `run-check` fn in check.clj taking `{:name :boot {opts} :agents [tasks] :body (fn [sys ids] {:checks {k bool} :report m})}`. It prints the report, prints `NAME OK`/`NAME FAILED` (one spelling), stops and exits.
- A `phase-check` for kill/reopen pairs.
- Waits through `seon.e2e/await-db`, or a tap of `c/events` filtered by predicate (root already emits `:ready`, `:ended`, `:commit` and `:reply`). This replaces all four polls.
- Delete `swarm` and `program`; fold `threads-declared` into `lifecycle`, printing the same EDN line `root_test` reads.

**Moves.** All 25 modes, plus the `-main` `case` (:1254-1298).

**Net.** Boilerplate about -175, polls about -25, phase helper about -20, overlaps about -90; about **-280** in check.clj and about -15 in bin/check.

**Pattern stopped.** "Every new check mode copies ~50 lines of boot, spawn, await, print, verdict and exit." After this, a mode is only its body and its checks map.

### 2. Request/answer: one helper, not five pending tables

AGENTS §3 says "request/answer with credit, `{:id :reply}`, exactly one answer". Today each proc keeps its own request-id counter and pending map:

| Where | Counter / pending | Answer path |
|---|---|---|
| root api | `rid!` root.clj:234-235, `pend` :237-238 | `deliver-rid` :136-142, `reply!` :126-134 |
| reconciler | `request` reconcile.clj:495-502; `quiet` hand-rolls two more :684-689 | `:answers` arm :735-739 |
| render proc | `ask` render/proc.clj:39-45, `pending?` :53-56, `ask-state` :58-65, `ask-db` :67-73 | `:answers` arm :500-511 |
| web proc | tag vectors `[:web n var target]` web.clj:257-260 | answered inside render/proc :502-510 |
| agent stand-in | `:inflight` + `:pings` queue, agent_proc.clj:84-88 | `answer-rid` :113-116 |
| agent process | `await-op!` agent.clj:76-90, `:fx-pending` :170-191, :457-459 | by `:k` |

`render/proc` even adds a clock to its table: `ASK-BOUND-MS` 2000 (render/proc.clj:47-51), "unanswered this long counts as gone". That clock exists because a throwing step loses its answer. `procs-test/a-throwing-step-answers-every-pending-request` already proves the root side answers every request it took, so the clock is unneeded.

**Converge onto.** Two functions in `seon.step`, next to `out!` (step.clj:55-58):
- `(ask st o route kind req)` bumps `:asks`, stores `kind` and emits `{:op :request :req req :route route :tag rid}`;
- `(answered st m)` answers `[st' kind r]` and drops the entry.

The reconciler, render, web and root's own route use them. The stand-in's `answer-rid` and the agent's `await-op!` keep their wire, but share the `:asks` shape. Delete `ASK-BOUND-MS` and `pending?`.

**Net.** About **-70**.

**Also.** `step-out` (web.clj:226-227) and the 10 `(vswap! o update ... (fnil conj []))` in render/proc are `step/out!` re-typed. That is about -12, counted in item 6.

### 3. e2e scenarios as data

`calc`, `par`, `scenario`, `swarm`, `model-run` and `stuck` (e2e.clj:113-381) each repeat the same steps, about 12 lines per function:
1. `c/start!` with opts;
2. `seed!`;
3. `root/create!` (sometimes with `max-turns`);
4. `root/run!` over `task-id`s;
5. `quiet!`;
6. `(doseq [[id s] (summary ..)] (println :task ..))`;
7. `(println :numbers ..)`;
8. `fresh`, then `verdict!` with a predicate.

`seon.fixtures/scenarios` and the `expectations` map (e2e.clj:383-405) are already half of a data table. Also, `fresh` sleeps 300 ms instead of awaiting the agent's `:ready` (e2e.clj:97-100), and so does `calc` before its cancel (:125).

**Converge onto.** One `run-scenario` taking `{:specs :opts :config :after :check :expect :extra (fn [db q] bool)}`. calc's cancel and stuck's always-wrong model become `:during` and `:provider` keys of their entries. `fresh` waits on `:ready`.

**Net.** About **-120**. `loop-run` and `resume` stay, sharing item 1's phase helper.

### 4. The lifecycle, declared once

**Agent status sets are re-typed at 19 sites:**
- `#{:dead :killed :ended}` at root.clj:257, :801, :814, web.clj:78 and entrance.clj:26;
- `#{:killed :ended}` at root.clj:157, :212 and :350;
- `#{:killed :ended :dead}` at render/ai.clj:57, render/proc.clj:359, :378 and render/ui.clj:679;
- `#{:killed :dead}` at ui.clj:173, and `ended-statuses` at ui.clj:176;
- `#{:done :merged}` / `#{:merged :done}` at problems.clj:80, reconcile.clj:139, ai.clj:58, render/proc.clj:360 and ui.clj:679.

"Ended after its attempt finished" is derived three times: `ui/ended-as` (ui.clj:673-679), `ai/problems` `finished?` (ai.clj:57-58), and render/proc's event decoration (:359-360, :378).

**Task done/observed state is derived four times:**
- `reconcile/done-info` and `idx-done?` (reconcile.clj:133-143), the authority;
- `problems/task-done?` (problems.clj:76-81), a second query;
- `render.proc/task-rows` (render/proc.clj:150-175), a `cond` of seven states;
- task summaries in `entrance/agents-answer` (entrance.clj:21-31) and `e2e/summary` (e2e.clj:46-56).

`ai/section-work` (ai.clj:94-100) recounts "blocked, waiting on", which `ui/waiting-on` (ui.clj:303-308) computes.

**Converge onto.**
- The `:seon.agent/status` schema form carries properties `{:seon.status/live #{..} :seon.status/stopped #{..}}`, read by one `seon.agent/live?` and one `ended-as`.
- The reconciler index carries each task's observed `:state` (it already has done, ready, live and blocked).
- `task-rows` moves next to it as the one task row that ui, ai, entrance and e2e read.
- `problems/task-done?` reads the index, or `reconcile/done?`.

**Net.** About **-90**.

**Cheap afterwards.** A new status (e.g. `:paused` as a lifecycle fact) is one enum entry.

### 5. Renders: tiles as data, bounded lists once, ai reuses ui

**Same tile shape, written three times.** `tile-writer`, `tile-server` and `tile-main` (ui.clj:206-256) each build kicker, big number, sparkline and `[:dl {:class "kv"}]` rows: one shape with three bodies.

**Defect line, written three times.** `tile-fx` (ui.clj:375-392) re-renders `proc-health`'s defect line (:258-269), and `tile-errors` (:394-415) does it a third time.

**"Bounded list plus what it left", hand-written about 10 times:**
- in ai.clj: `(take 8 ..)` + `more-line` at :103-106, `(take 5 ..)` at :91/:107-110, `(take-last 3 ..)` at :50-52, `(take 8 events)` at :135-140, `(take 5 ..)` at :128-129;
- in ui.clj: `(take 8 ..)` at :318-319, `(take-last 6 ..)` at :404-406 and :539.

Each bound is a literal, which breaks the "only documented config" law.

**Wrappers and helpers duplicated across the two namespaces:**
- `ai/line` (ai.clj:17-23) and `ui/prose` (ui.clj:59-76) both wrap `print/prose`;
- `ai/more-line` (:25-29) and `ui/more-li` (:78-82) both wrap `print/more`;
- `ai/kn` (:36) duplicates `ui/kname` (:31).

**Converge onto.**
- A `metric-tile` render over `{:id :kicker :big :unit :series :tone :rows}` data.
- One `defect-line`.
- `print/bounded` (items, bound key, where), answering `[shown more-line]`, with bounds as `:seon.config.render/*` keys.
- `seon.render.ai` calling `ui/waiting-on`, `ui/ended-as` and one `prose`.

**Net.** About **-80**.

### 6. Small duplicated helpers

| Concept | Copies | Converge on |
|---|---|---|
| tokens of text | `print/chars-per-token` 3.2 literal (print.clj:58-60); `select/estimate` via config prior (print/select.clj:15-21); `model/estimate-tokens` /4.0 (model.clj:21-24); `context/kept-turns` `quot .. 4` (context.clj:259); `ai/tokens` wraps select (ai.clj:31-34) | `seon.print.select/estimate`. Today four sizers give context, history and printer budgets that disagree |
| line offsets | `source/line-starts` (source.clj:20-26) = `repl/line-starts` (repl.clj:49-55), byte for byte | one, in `seon.reader` |
| ms since | `lib/ms-since` (lib.clj:72-73); `turn/ms-since` (turn.clj:62-63) | lib |
| temp ids | `merge/ns-tid`, `form-tid` (merge.clj:155-156) = `program/ns-tid`, `form-tid` (program.clj:318-320) | program |
| a var's source | `context/source-of` (context.clj:52-55, a query) and `program/source-of` (program.clj:443-447) | program |
| "make entity e state exactly want" | `task/update-tx` (task.clj:318-338) and `docs/update-tx` (docs.clj:80-96) (filed in the 10-21 audit, still both there) | one `entity-tx` in task |
| keyword name | `ui/kname`, `ai/kn` | ui (item 5) |
| proc outputs | `step/out!`; `web/step-out` (web.clj:226-227); 10 inline `vswap!` in render/proc | `step/out!` |

**Net.** About **-70**.

### 7. One door: entrance verbs serve web, MCP and the REPL

**Root's verbs are written four times:**
- the root API fns (root.clj:713-900, about 30 thin `call!` wrappers);
- `web/requests` (web.clj:80-104), which re-builds `merge!`'s and `merge-main!`'s request maps (`:merge-tests` with `:merge-branch`, `:data-attrs :all`), duplicating root.clj:787-817;
- `entrance/answer` (entrance.clj:91-109);
- `mcp/tools` (mcp.clj:11-25), which already maps 1:1 onto entrance ops.

**Root-level evaluations are recorded three ways:**
- `entrance/root-eval` (entrance.clj:34-60): its own read/eval loop, `intern` (an AGENTS §3 code smell), `pr-str` instead of the printer, and its own record transaction;
- `web/record-tx` (web.clj:106-116);
- `repl/record-tx` (repl.clj:395-409), the real one.

**Converge onto.**
- The entrance's op map is the verb table.
- `web` turns a UI call into the same entrance request (its allowlist is the table's keys); MCP stays thin.
- Root's eval uses `repl/record-tx` and the one printer.
- Delete `web/requests`' per-verb arms, `web/record-tx` and `root-eval`'s loop.

**Net.** About **-70**.

### 8. Config in one shape

**Shape 1: `start!` opts.** `start!`'s destructure (root.clj:577) takes `{:path :main :backend :max-running :provider :flush-ms :stall-ms :source :datoms}`, each `(or opt (cfg/value ..))` at :585-588 and :634. That is a second config surface beside the aero layers and `--set` (config.clj:136-183). Because a provider arrives as a map, `seon.config/with-provider` (config.clj:261-275) exists only to turn it back into config. The 10-21 audit already said so.

**Shape 2: the reconciler's own defaults.**
- `default-max-turns`, `default-max-running`, `default-max-merging`, `merge-dials` and `default-budget` (reconcile.clj:14-34);
- the hand-written task-over-default merges in `dials` (:41-46), `desired-env` (:49-54) and `refine?` (:36-39).

`cfg/resolve-in` already resolves task → model → provider → config (config.clj:211-229). AGENTS calls that the one resolver.

**Shape 3: fences as `def`s outside config.**
- `agent_proc/stall-ms` and `stop-bound-ms` (agent_proc.clj:42-54);
- `seon.db/flush-ms` (db.clj:55);
- `root/tail-bytes` (root.clj:64-66);
- `repl/top-fns` (repl.clj:204-207);
- `render.proc/EVENT-CAP`, `SERIES-CAP`, `RETIRE-MS`, `HISTORY-CAP` and `ASK-BOUND-MS` (render/proc.clj:32-51);
- `ui/BIG-ALLOC` (ui.clj:470).

**Converge onto.**
- `start!` takes `{:set {k v}}` (a config layer) plus the store path.
- Callers write `{:set {:seon.config.agent/max-running 2}}`, as `--set` already does on the command line.
- The reconciler reads `(cfg/value k {:task t})`.
- Each fence becomes a documented key (or is deleted, like `ASK-BOUND-MS` in item 2).

**Net.** About **-60** in `src`. `default.edn` grows by the keys.

### 9. REPL items as data, not strings round-tripped in-process

**What happens now.**
- `wrap-caught` does `(assoc m "seon/error" (pr-str em))` (repl.clj:198);
- `wrap-cost` does `"seon/fns" (pr-str ..)` (:225-226);
- `wrap-print` does `"seon/printer-error" (pr-str pv)` (:183);
- then `fold-item` reads them back with `clojure.edn/read-string` (repl.clj:361, :373-375). Its `text` argument is `identity` "the answers are in-process" (:378-382, :437, :454).

**Why it is waste.** The values never cross a wire as strings: the frame to root is fasl (root.clj:191). So this is a second encoding of the same values, and it costs a print and a read per form.

**Converge onto.** The middleware assoc keyword keys (as `:repl/value` already does, repl.clj:19-20), and `fold-item` `select-keys` them. nREPL's string keys stay only where a real nREPL client would read them (none today).

**Net.** About **-45**. It also removes 4 of the 21 remaining `edn/read-string` decode sites.

### 10. One fork helper (the spawning pillar)

**The same skeleton, four times:** take the passed fd, check single-threaded, `c-fork`, then three branches (child, refused, parent).
- fork server `:fork` (fork_server.clj:57-66);
- fork server `:standby` (:74-83);
- the clone's `safe-fork` + double fork (agent.clj:518-540);
- the test fork (agent.clj:379-404).

**Converge onto.** `lib/fork!` taking `{:fd :child (fn) :refused (fn) :double? bool}`. AGENTS §2 lists spawning among the mechanisms that get one implementation.

**Net.** About **-30**.

### 11. One producer of "what is wrong"

**Two producers of one view** (AGENTS §8 "two producers of one view").
- `seon.render.ai/problems` (ai.clj:41-83) builds the agent lines (died, red tests, refused merge) from root's live view.
- `seon.problems/report` builds the recorded ones from main.

Root already records an agent's death as `:seon.agent/died` (root.clj:115-124, :215-219). The remaining gap is a non-task agent's red run or refused merge.

**Converge onto.** Record those as problems too (one `problem` call each in `agent_proc`'s `:tests` arm and root's `:merged` arm). Then `ai/problems` reads only `:recorded`.

**Net.** About **-25**.

### 12. The harness leaves `src` (move)

`seon.check` (1,298), `seon.e2e` (420) and `seon.fixtures` (227) are drivers. After items 1 and 3 they are about 1,500 lines.

`seon.root` requires `seon.fixtures` for `warm!` (root.clj:30, :508). That one dependency moves with a small warm fixture.

`bin/build` names `seon.check/-main`. It would name a driver namespace under `check/` (or `test/`) that the build compiles but the src count leaves out. This is filed already as `seon.main/drivers-leave-src`.

---

## Patterns that keep producing code, and the idea that stops each

1. **"A new check mode copies boot, spawn, await, print, verdict and exit."** That is about 50 lines per mode, 25 times. Stopped by `run-check` plus the event wait (item 1).
2. **"A new proc that asks root grows its own rid counter, pending map and answer arm"**: five so far, one with a clock. Stopped by `step/ask` and `step/answered` (item 2).
3. **"A new view re-derives a lifecycle from raw statuses."** 19 literal sets, 4 task-state derivations. Stopped by status sets on the schema and observed state in the index (item 4).
4. **"A new knob becomes a `start!` option or a `def`."** 8 fence `def`s and 8 `start!` options. Stopped by one config shape (item 8).
5. **"A new verb is wired per door."** Root fn, web arm, entrance op, MCP tool. Stopped by the entrance's table (item 7).
6. **"A new list on a page or in a context takes `(take n ..)` plus a hand-made more-line with a literal n."** Stopped by `print/bounded` with config bounds (item 5).
7. **"A new e2e run copies the scenario skeleton."** Stopped by scenarios as data (item 3).

## Lanes: parallel, disjoint write sets

**Wave 1** (all at once; no two share a file):

| Lane | Write set | Items | Size |
|---|---|---|---|
| `check-harness` | `src/seon/check.clj`, `bin/check` | 1 | ~4 h, -295 |
| `e2e-data` | `src/seon/e2e.clj`, `src/seon/fixtures.clj` | 3 | ~3 h, -120 |
| `ask` | `src/seon/step.clj`, `agent_proc.clj`, `render/proc.clj`, `reconcile.clj` (request, quiet, answers), `root.clj` (api-proc section), `agent.clj` (await-op!, fx-pending), `web.clj` (step-out, tags) | 2, item 6's out! row | ~4 h, -80 |
| `repl-items` | `src/seon/repl.clj` | 9 | ~2 h, -45 |
| `helpers` | `model.clj`, `context.clj`, `source.clj`, `merge.clj`, `program.clj`, `docs.clj`, `turn.clj`, `task.clj` (entity-tx) | 6, except out! and kn | ~2 h, -55 |
| `renders` | `render/ui.clj`, `render/ai.clj`, `print.clj`, `resources/seon/config/default.edn` (render bounds only) | 5 | ~3 h, -80 |

**Wave 2** (after wave 1 lands; each pair is disjoint):

| Lane | Write set | Items | Size |
|---|---|---|---|
| `lifecycle` | `resources/seon/schemas/*` (agent status), `reconcile.clj` (index), `root.clj` (set-agent, end-agent), `problems.clj`, `render/proc.clj` (task-rows), `render/ui.clj`, `render/ai.clj` | 4, 11 | ~4 h, -115 |
| `doors` | `web.clj`, `entrance.clj`, `mcp.clj`, `root.clj` (API fns section) | 7 | ~3 h, -70 |
| `fork` | `lib.clj`, `fork_server.clj`, `agent.clj` (clone!, run-tests-forked) | 10 | ~2 h, -30 |

`lifecycle` and `doors` both touch `root.clj`, in different sections (`lifecycle`: api state helpers :147-232; `doors`: :713-900). If root wants strict file disjointness, run `doors` after `lifecycle`.

**Wave 3:**

| Lane | Write set | Items | Size |
|---|---|---|---|
| `config-shape` | `config.clj`, `root.clj` (start!), `reconcile.clj` (defaults), `db.clj` (flush-ms), `agent_proc.clj` (fences), `default.edn`, and the `start!` call sites in check, e2e and tests | 8 | ~3 h, -60 |
| `drivers-leave-src` | move check, e2e, fixtures; `bin/build`; root's warm fixture | 12 | ~2 h, ~-1,500 moved |

Each lane writes its proofs first: the guarding tests above must stay green, and new ones where a mechanism is new (e.g. a `step/ask` deftest in `procs_test`). Each lane states its net `src` lines in the commit, per AGENTS §4.

## Not measured, and left alone

- I did not measure the savings by implementing them. Each is an estimate from reading the code, and a ratio past 3× is a finding for the lane.
- I left these as they are:
  - `print/select` (747) and `print/emit` (436): the 10-21 audit keeps emit's layout for its measured win;
  - `seon.ref/scan` (a CommonMark subset; Jolt has no Markdown library);
  - the writer's group commit.
- `seon.ref/pin` (ref.clj:520-544) parses git's internals by hand where the `:git` effect owner exists. That is about -20. I didn't rank it because pulling is synchronous and read-only.
- `seon.context/current-state` builds the model's view by hand rather than as a render declared on `:seon.task/task`, as AGENTS §2 asks. This is a design question for root's gate, not a duplicate, so it isn't ranked.
