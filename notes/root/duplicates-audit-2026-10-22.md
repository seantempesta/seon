# Duplicates, fences and dead code: audit, 2026-10-22 (read-only)

This audit answers the owner's ask of 2026-10-22: "keep the source code size down and find and resolve all duplicate code paths and find root causes rather than fences or patches." It finds the work and leaves the fixing to root's tasks and lanes. It changed no source, ran no build or test, and touched neither `runs/` nor `~/src/seon`. It was read at main `e117b9b` (wave 2 landed), where `src` is 16,549 lines.

**What was left out as already landed.** I checked these against `git log -60`, `notes/shrink-convergence-2026-10-22.md`, `notes/shrink-dead-code-2026-10-22.md` and `notes/fences-triage-2026-10-22.md`:
- the check harness and its event waits;
- `step/ask`, `step/pend` and `step/answered`;
- e2e scenarios as data;
- the lifecycle schema sets;
- tiles as data, and `print/bounded`;
- REPL items as data;
- `lib/fork!`;
- one producer of "what is wrong";
- the doors' verb table;
- `entity-tx`, `ns-tid`/`form-tid`, `ms-since` and `source-of`;
- the model and context sizers;
- the `web/ask!` 10 s fallback.

What remains of those notes (their unlanded rows) is restated here only where the code still shows it, with its current line numbers.

**Method.**
- **Dead vars:** every `def*` in `src` (1,171 of them) was grepped by symbol boundary across `src`, `test`, `bin`, `resources` and `docs`, with qualified uses counted. Then each candidate was read.
- **Duplicates:** same-named defs across namespaces, repeated literals (status sets, `:avet` seeks, `(:error r)` readers, numeric bounds), and reading the five largest files plus `web`, `entrance`, `agent_proc`, the render namespaces, `writer`, `fork_server` and `my.root`.
- **Catches:** listed by enclosing fn against `docs/DRAGONS.md`. All 33 catch sites are named there, so no row in B is a stray catch. B's rows are fallbacks, fake answers, clocks and patches.

**Line counts are estimates from reading, not measured.** A lane that finds its real number off by 3× or more reports that as a finding.

**Lanes in flight** (write-set tags in the tables):
- **TA**, tasks-at-once: `template.clj`, root spawn/fork, `my/task.clj`, `turn.clj`, the reconcile done rule;
- **CV**, context-v2: `context.clj`, ref, model cached tokens, help;
- **SN**, store-native: `db.clj`, the fork server, program compiled units;
- **RD**, reds: merge/conflict-value, the writer's group commit, root proc answers, check orphan, `my.schema`;
- **—**: none of the four.

## Summary

| Group | Rows | Est. lines deleted | Moved out of `src` |
|---|---|---|---|
| A. Duplicate code paths | 22 | ~190 | ~25 (harness) |
| B. Fences, patches, fake defaults, clocks | 10 | ~110 (plus config keys added to `default.edn`) | – |
| C. Dead and near-dead | 10 | ~31 | ~85 (test helpers, the check-only API) |
| **Total** | | **~330** | **~110** |

That would take `src` to about 16.1k lines before `seon.main/drivers-leave-src` (~1,500 moved).

The biggest **root causes** (not sizes) are:
- **B1:** root answers a refusal with fabricated protocol members (an eval item `:id -1`, `:pass 0 :fail 0 :error 1`, `:applied []`). The reconciler then detects death by `(= -1 (:id ...))` and strips the fakes again in `error-text`.
- **B2:** a second error shape `{:error "string"}` lives beside the one error map, and five readers patch both.
- **A4:** "uncontracted" is computed four ways. The agent's done check, root's judge and the writer's merge gate can disagree.

---

## A. Duplicate code paths

| # | What | Where (every copy) | The one place (owner) | Est. lines | Risk | Lanes touched |
|---|---|---|---|---|---|---|
| A1 | Agent "live / gone" status sets still typed out, and the declared sets read three ways | Literals: `root.clj:801` `(#{:dead :killed :ended} ...)`; `web.clj:72-73` `live?`; `entrance.clj:21`; `check.clj:55`. Readers: `(get-in contract/forms [k 1 :seon.status/...])` at `root.clj:146-147` and `reconcile.clj:129-130`; `render/properties` plus `(vec (drop 2 (get contract/forms k)))` at `render/ui.clj:161-164` and `275-278`; a third hand reader in `ui/config-panel`'s `prop` (`render/ui.clj:387-389`). Undeclared sets: `problems.clj:135,137` `#{:dead :lost}` and `problems.clj:159` `[:dead :lost :behind]` (no `:seon.status/failed` on `:seon.attempt/status`). | `properties`/`enum-values` move down to `seon.contract` (`render/properties` calls them); one `gone?` predicate on the schema's `:seon.status/gone` used by root, web, entrance and check; add `:seon.status/failed` to `seon.facts.edn` for problems | ~12 | low | TA (`root.clj:273` in `:spawn`), else — |
| A2 | `eid` (one AVET seek) written 10 times; four single-entity Datalog queries do the same job slower | `task.clj:16-21` (public), `program.clj:362-363` (private copy), inline `(:e (first (d/datoms db :avet ...)))` at `merge.clj:61,175,224`, `source.clj:206,233,331`, `ref.clj:508`, `my/schema.clj:57`; `[:find ?e . ...]` at `config.clj:303`, `error.clj:97`, `reconcile.clj:489`, `context.clj:106` (task.clj:17-19's own doc says a query costs ~95 µs and 100 KB) | `seon.program/eid` made public (the lowest namespace that merge, source and task require); `task/eid` deleted, callers say `program/eid` | ~10 | low | SN (program.clj), RD (merge.clj, my/schema.clj), CV (context.clj:106) |
| A3 | Root's entity (`[:seon.root/id :root]`) read six ways | `reconcile.clj:427-431` `quiescent`, `reconcile.clj:439-445` `generation`, `task.clj:268-276` `root`, `root.clj:607` (import-source!), `config.clj:298-305` `from-db` (a Datalog query), `db.clj:269` (query for `:seon.root/collected`) | one `seon.task/root-fact db attrs` (a pull by lookup ref behind the `eid` check) | ~8 | low | TA (reconcile), SN (db.clj) |
| A4 | "Uncontracted functions" computed four ways from four sources, so the agent's done check, root's judge and the writer's merge gate can disagree | `contract.clj:285-289` (ns-interns), `harvest.clj:19-26` (live vars' `:malli/schema` meta; the agent's gaps, `turn.clj:44`), `writer.clj:49-52` (merge `:ours` entries), `reconcile.clj:570-573` (`pm/program` branch vs main) | one `seon.merge/uncontracted` over a `pm/program` value (writer and reconcile); harvest's live-runtime one calls `contract/uncontracted` | ~8 | medium | RD (writer), TA (reconcile done rule) |
| A5 | `line-starts` byte for byte twice (convergence item 6, unlanded) | `repl.clj:49-55`, `source.clj:20-26` | `seon.reader/line-starts` | ~7 | low | — |
| A6 | Three monotonic clocks, mixed even inside one function | `lib.clj:69-70` (System/nanoTime), `profile.clj:93-96` (Chez), `sock.clj:49-52` (CLOCK_UPTIME_RAW). Mixed at `writer.clj:16` (lib) with `:17` (sock); `root.clj:282` (lib) with `:320,407` (sock); `reconcile.clj:767` (sock) | one `seon.lib/now-ns` (sock's, the clock every process shares); profile keeps its Chez read private for counters | ~6 | low | RD (writer.clj) |
| A7 | Two `write-all!` loops; lib's lacks EINTR handling and answers `-1` | `lib.clj:101-110`, `sock.clj:227-235` | `seon.sock` (it owns fds; `:epipe` is its declared answer) | ~8 | low | — |
| A8 | Two baked schema readers (dead-code row 17, unlanded) | `config.clj:66-77` `baked-schema-forms` (seon.config*.edn), `contract.clj:73-109` `schema-forms`/`baked-forms` (all *.edn) | one baked map in `seon.config` (contract already requires config); contract filters nothing, config selects its keys | ~10 | medium | — |
| A9 | Two topological sorts and three transitive walks over `:needed-by` | `program.clj:466` `topo` (general); `reconcile.clj:207-221` `topo` (Kahn's, own closure at :211-214); `reconcile.clj:388-396` `blocks` (the same BFS) | `program/topo` plus one closure fn in reconcile used by both | ~10 | medium | TA (reconcile), SN (program.clj) |
| A10 | Attempt id derived three times, and "an agent's attempt" looked up three times | ids: `reconcile.clj:383` `(inc (count by-task))`, `reconcile.clj:411` `(count ...)`, `entrance.clj:58` `(inc (count (filter ...)))`. Lookups: `entrance.clj:64-70` `attempt-of` (scans every attempt per call), `render/proc.clj:220-224` `attempt-of`, `render/proc.clj:164` `:agent-attempt` | `seon.task/attempt-id` and one `attempt-of-agent` over the reconciler's index | ~8 | low | TA (reconcile, attempts) |
| A11 | Root's view numbers decorated twice (p50s, tx-per-commit, `dissoc :recent :batch-ms`): two producers of one view | `root.clj:762-776` `state`; `render/proc.clj:93-104` `system-value` | one fn in `seon.root` (`state` uses it; the render proc asks for it) | ~12 | low | — |
| A12 | "Is this test run red" and its words, written about nine times | `root.clj:159`, `agent_proc.clj:174`, `render/ui.clj:409-419` (`test-dots`), `:531`, `:583-584`, `:588`, `:607`, `:864`; `(or fail 0)` arithmetic at each | `seon.proofs/red?` and one run-summary line | ~12 | low | RD (root proc, `agent_proc` answers) for the two root-side copies |
| A13 | One evaluation rendered by two near-identical renders (form, arrow, value, meta, fns), plus the `(or (:printed e) (:shown e) (:error e))` pick twice | `render/ui.clj:475-493` `eval-row`, `render/ui.clj:874-894` `history-row` (:482, :881) | `history-row` with a compact budget option | ~12 | low | — |
| A14 | A merge's result and an agent's end said in words three times each | Merge: `render/ui.clj:513-516` (card pill), `:580-587` (event detail), `agent_proc.clj:181`. End: `render/ui.clj:166-174` `shown-status`, `:561-564` `event-kind`, `:598-600` | one `merge-words` and one `ended-words` in ui | ~8 | low | — |
| A15 | `my.root`'s "tx → refusal, or transact plus citation problems" three times | `my/root.clj:8-16` `create!`, `:48-61` `load-tasks!`, `:63-75` `load-docs!` | one private `write-declared!` | ~8 | low | — |
| A16 | Task summaries built three times | `entrance.clj:16-25` `agents-answer`, `e2e.clj:49-58` `summary`, `render/proc.clj:127-143` `task-rows` (the one row, convergence item 4) | `task-rows`, moved next to `reconcile/task-state`; entrance and e2e read it | ~15 | low | TA (reconcile) if moved; else — |
| A17 | Harness twins: two watch-waiters, two verdict printers, two `eval-records`, and e2e's own ready wait | `check.clj:74-83` `await-ref` vs `e2e.clj:20-28` `await-db`; `check.clj:16` vs `e2e.clj:118` `verdict!`; `check.clj:948` vs `render/proc.clj:226` `eval-records`; `e2e.clj:100-106` `fresh` vs `check.clj` `await-event`/`spawn-ready!` | one waiter and one verdict in the harness; `render/proc`'s `eval-records` reused | ~25 (harness) | low | RD (check orphan) only for check.clj:514-533 |
| A18 | Percentiles computed twice | `render/proc.clj:393-394` `q` vs `lib.clj:246-251` `summ` | `lib/summ` | ~2 | low | — |
| A19 | The fork server's stop written twice: kill, drop standby ×2, join 5000, waitpid | `fork_server.clj:204-209` (stop transition), `:219-226` (`:shutdown`) | one private `release!` | ~5 | low | SN |
| A20 | Error tested two ways | `(:seon.error/type x)` as a predicate: `my/root.clj:13,42,57,73,117`, `turn.clj:136,224`, `fx.clj:274,325`, `agent.clj:151`; `contract/error?` at 14 sites | `contract/error?` | 0 (consistency) | low | TA (turn.clj) |
| A21 | The web proc keeps its own request table (`:next` counter, `:waiting` map) beside `step/pend` | `web.clj:208-219` | `step/pend`/`step/answered` | ~4 | low | — |
| A22 | Two signal-handler installers, and an alias of SIGUSR1 | `intr.clj:57-62,69-76` (kick and poll variants), `lib.clj:204-211` `on-signal!`; `intr.clj:27` `SIGUSR1` = `lib/SIGUSR1` | `lib/on-signal!` for the plain case; intr keeps only the kick | ~6 (overlaps C4) | low | — |

## B. Fences and patches (each with the producer of the bad value)

| # | What | Where | Root cause (producer), and the fix there | Est. lines | Risk | Lanes touched |
|---|---|---|---|---|---|---|
| B1 | Refusals carry **fabricated protocol members**, which callers then detect and strip | Producer: `root.clj:45-56` `no-agent` (eval item `{:id -1 :ms 0.0 :alloc-bytes 0}`, tests `:pass 0 :fail 0 :error 1`, `:merged? false`, `:ok false`); `root.clj:811` (`:applied [] :conflicts []`), `:286`, `:915`; `writer.clj:32-36` `{:ok false}`. Patches: `reconcile.clj:646` death detected by `(= -1 (:id (first r)))`; `reconcile.clj:495-499` `error-text` strips `:pass :fail :error :reports :merged? :ok :tx :applied :conflicts` back out | Root answers the one error map alone; every asker branches on `contract/error?` (reconcile's `:worked` and `:tested` arms, the turn loop, web, check) | ~15 | medium | **RD** (root proc answers), TA (reconcile) |
| B2 | A second error shape `{:error "string"}` beside the one error map, and readers that patch both | Producers: `entrance.clj:54,94,95,105,113,125`; `mcp.clj:23-24`; `web.clj:290`; `agent.clj:503,506` (clone-failed); `agent_proc.clj:106,153`; `root.clj:360,379,389` (the agent view's `:error` string). Patches: `render/proc.clj:437,473` and `render/ui.clj:591` `(or (:seon.error/message r) (:error r))`; `root.clj:736` `(:seon.error/message r (:error r))`; `root.clj:157` re-wraps the string as `contract/error`; `render/ui.clj:516,585` | `contract/error` at each producer (`lib/fork!`'s refused branch, the fork server's `:fork-failed`, `:clone-failed`, the entrance verbs); readers read `:seon.error/message` only | ~10 | medium | TA (root spawn/fork, clone), RD (root answers) |
| B3 | Fake defaults standing for unknown | `reconcile.clj:484-493` `pull-end` answers `{}` for "no end" (again at `:622`); `reconcile.clj:331` root `{:boot ""}`; `reconcile.clj:352` `(:seon.attempt/outcome a :error)`; `root.clj:334` `(::errors st {})`; `root.clj:966` `:unknown`; `root.clj:286` the clone refusal says "no live agent" when the cause is too many agents; `render/proc.clj:256` `{:status :unknown}`, `:233` eval-id parse `(or ... 0)`; `web.clj:158` `:bytes-per-s (:bytes-per-s st 0)`, a key nothing ever sets; `my/root.clj:118` `(:seon.fx/us r 0)` | nil or absent at each producer; renders say "–" (they already do for nil) | ~5 | low | TA (reconcile), else — |
| B4 | Clocks deciding | (a) stall timer `root.clj:529-536` + `agent_proc.clj:42-48,95-102` (filed: `seon.fences/a-stall-is-past-the-requests-own-bound`). (b) stop bounds `agent_proc.clj:50-70` (`await-dead` deadline loop, `:251` 5000), `root.clj:994-998` (`stop-bound+1000`, 15000, join 5000), `root.clj:708` `:stop-timeout-ms 10000`, `fork_server.clj:208,224` join 5000 (filed: `shutdown-and-death-are-dataflow`). (c) the fork server reaps children on a **100 ms poll tick**, `fork_server.clj:107-109` via `sock/recv-within!` (`sock.clj:216-222`) (filed in `seon.root.edn`: SIGCHLD self-pipe). (d) the flusher `writer.clj:272-289` `Thread/sleep` + `db.clj:55-60` `flush-ms` (filed; reds). (e) `render/proc.clj:34,59` `RETIRE-MS` 90 s takes ended agents off the grid, and `:70` re-orders on a `< 1000` ms clock. (f) `root.clj:951-979` `agent-health`/`health` `:timeout-ms 1000` → `:unknown` (fences 10; only check calls them) | (a, b, c, d) as filed. (e) retire on "ended and its attempt judged" (a fact), with no clock. (f) moves with the harness (C8) | ~10 (e, plus c's tick and recv-within!) | medium | SN (c), RD (d), else — |
| B5 | Undocumented bounds (config law: "every limit is a key") | `root.clj:64-66` tail-bytes 2048, `:172,:435` `take-last 20`, `:369,380,406,418,419` push-window 500/200/100000/200/2000, `:672` `(+ 256 ...)`, `:679-685` chans 8192/1024/4096/4096/64, `:792` 1024; `web.clj:83` `(min 100 ...)`, `:251` 200, `:281` 256, `:289` keep-alive and write timeouts; `render/proc.clj:32-35` EVENT/SERIES/HISTORY caps, `:458` 4096, `:470` 2048; render budgets 600/700/400 at `render.clj:80,115`, `render/ai.clj:114`, `render/ui.clj:888`, `render/proc.clj:264,356`; `render/ui.clj:823` ratio `3` (a copy of `cost.clj:40-43` `slack :ns 3.0`); `cost.clj:40-59` `slack`/`steeper-by`/`loose`/`ratchet` (fences 14, on unmerged `jolt/unitflake`); `print.clj:58-60` `chars-per-token 3.2` beside `select.clj:15`'s config prior; `render/ai.clj:33` the text "holds the newest 20" | keys in `default.edn` under `seon.config.root/*` and `seon.config.render/*`; print's sizer reads select's | ±0 in src (keys move to config) | low | — (root.clj chans are at boot: TA-adjacent) |
| B6 | Config bypass (convergence item 8, unlanded) | `root.clj:637-667` `start!` opts `:provider :flush-ms :stall-ms :max-running` + `config.clj:261-275` `with-provider`; `reconcile.clj:14-54` `default-*` defs and hand-written task-over-default merges in `refine?`, `dials` and `desired-env`, bypassing `cfg/resolve-in` | `start!` takes `{:set {k v}}` as a config layer; reconcile reads `(cfg/value k {:task t})` | ~45 | medium | TA (reconcile), RD (flush-ms) |
| B7 | The check harness waits on sleeps, though its own doc (`check.clj:8-9`) says "never a poll or a deadline" | 13 `Thread/sleep` "windows" at `check.clj:160,395,486,525,620,678,719,756,758,774,918,1070,1076`; `deref p 300 :held` at `:683`; `check.clj:524-525` `run-tests!` with a 600000-tick budget then `sleep 300`, so a bounded "forever" test may end before root dies (racy) | No event says "the eval started" (`agent_proc.clj:88-90` emits only a status), "the pause landed", or "the test child forked". Emit `{:type :started}` and `{:type :tests-started}` events from the stand-in; the harness awaits them | ~10 | low | RD (check orphan, `:514-533`) |
| B8 | The entrance starts attempts by a second path beside the reconciler's spawn flight | `entrance.clj:48-62` `attach!` (env-tx, execution-tx, `spawn!`, its own attempt id, `attempt-start-tx`); a refused spawn here throws instead of being recorded as a `:refused` attempt | The reconciler already handles model `"outside"` at `:started` (`reconcile.clj:637-638`). Attach writes env `{:model "outside"}` plus the release, and the reconciler spawns and records it | ~12 | medium | TA (spawn, attempts) |
| B9 | The web records root's UI verb with a fabricated answer | `web.clj:232-237`: an item `{:id 0 :tag :ret :printed "requested"}` is recorded, not the verb's answer | Record when the answer arrives (`render/proc.clj:476-481` already takes it) | ~3 | low | — |
| B10 | The render proc re-lists main in full per commit while the reconciler keeps the same index incrementally (a duplicate computation, O(tasks) per commit) | `render/proc.clj:152-162` (`reconcile/index`, `task/tasks`, `problems/report`) vs `reconcile.clj:752-754` | The reconciler publishes its index with each `:main` commit (or the render proc runs `observe`) | ~0 (perf) | medium | TA (reconcile) |

## C. Dead and near-dead code

| # | What | Where | Action | Est. lines | Risk | Lanes touched |
|---|---|---|---|---|---|---|
| C1 | `seon.root/agent-ping` has no caller (dead-code row 8, unlanded), and with it the agent `:ping` request route | `root.clj:961-966`; route: `root.clj:294,303`; `agent_proc.clj:87,165-167,238` (`:pings`); `agent.clj:86-88` (pong in `await-op!`) | delete; keep `step/run-loop`'s control ping until a check shows nothing sends it | ~14 | low (medium for the route) | RD (root answers), TA (`agent_proc`) |
| C2 | `seon.root/stats`: no caller (only a task's prose) | `root.clj:778-779` | delete | 2 | low | — |
| C3 | The ext-op `:message` arm: no producer since `send-message!` went | `root.clj:320-321` (the back-op arm `:407-408` stays) | delete | 2 | low | — |
| C4 | `intr`'s poll variant: `install!` is never called with `kick? false` | `intr.clj:60-62` `install-poll*`, the arity at `:69-76` | delete | ~5 | low | — |
| C5 | Placeholder rows for numbers nobody reports | `render/ui.clj:236` the fork-server "standby" row (root's view holds no `:standby`); `render/ui.clj:271-272` the fx lanes line; `web.clj:158` `:bytes-per-s` | delete until an owner emits them | ~4 | low | — |
| C6 | `render.ai/tokens`: a pure alias of `select/estimate` | `render/ai.clj:17-20` (callers `render/proc.clj:249,357`) | call `select/estimate` | ~4 | low | — |
| C7 | Test-only helpers in `src` | `db.clj:183-222` `test-store`/`test-db`/`test-branch!` (only `test/` uses them), `fixtures.clj:214` `two-calls` | move to `test/` (test_runner) | ~45 moved | low | **SN** (db.clj): after it lands |
| C8 | The check-only root API: only `check.clj` calls these | `root.clj:942-979` `pause-agent!`, `resume-agent!`, `agent-health`, `health`, `fs-call!`, `agent-stats!`; `root.clj:911-915` `branch-diff`; `lib/summ`, `lib/gc-stats`, `lib/sysmem` | move with the drivers (`seon.main/drivers-leave-src`), or call Flow and `call!` from the harness | ~40 moved | low | — |
| C9 | Stale citations | `render/ui.clj:13` cites the deleted `seon.render/render`; `check.clj:8-9` claims no polls (B7); `root.clj:714-726` the error reader's `rd` is `alts!!` over one channel (a `<!!`) | fix the text; inline `<!!` | ~3 | low | — |
| C10 | Stringly-typed keys round-tripped | `:method/key` and `:impl/key` stored as `pr-str` and `read-string` back at `program.clj:446-447,645,704`, `harvest.clj:42`; `clojure.edn/read-string` called directly beside `seon.edn/decode` at `program.clj:568,610,763`, `source.clj:160,333` | store the key as data (EDN attribute) or decode through `seon.edn` once | ~6 | medium | **SN** (program units) |

### The largest files: where they shrink

- **`check.clj` (1170):** it leaves `src` (filed). Before that: A17, B7 and C8. Its 13 sleeps are the harness's real debt.
- **`root.clj` (1042):**
  - `start!` (`:620-738`) loses its opts surface (B6, about -25);
  - B1 and B2 shrink the api (`no-agent`, `refused` members);
  - A11 drops the duplicated decoration;
  - C1–C3 and C8 delete or move about 60 lines of thin API.
  - Expected: about -110 in place, about -40 moved.
- **`render/ui.clj` (972):** A1 (sets), A12–A14, C5 and B5's literals. About -45.
- **`program.clj` (781):** A2 (`eid` public), A9 (one topo), C10. About -10. It belongs to store-native first.
- **`reconcile.clj` (777):**
  - B6 (defaults: -30);
  - A9 (closures: -8);
  - A10 (attempt id);
  - B1 (`error-text` and the `-1` check go);
  - B3 (`pull-end` `{}`).
  - About -50, all after tasks-at-once lands.

---

## Proposed lanes, disjoint from the four in flight and from each other

**Lane DUP-VIEWS (now; no overlap).** Write set: `render.clj`, `render/ui.clj`, `render/ai.clj`, `render/proc.clj`, `web.clj`, `entrance.clj` (all but `attach!`), `mcp.clj`, `problems.clj`, `proofs.clj`, `my/root.clj`, `resources/seon/config/default.edn` (render keys only), `seon.facts.edn` (the `:seon.status/failed` set only).
- Rows: A1 (views half: web, entrance, problems, ui; via `render/properties`), A11 (render side reads root's), A12 (render copies), A13, A14, A15, A16 (entrance → `task-rows`), A18, A21, B2 (entrance, mcp and web producers; render readers), B3 (render and web rows), B5 (render bounds), B9, C5, C6, C9 (ui doc).
- Size: about -120. Its proofs: `seon.render-test/*`, `seon.web-test/*`, `seon.problems-test/*`, `bin/check mcp idle`.

**Lane DUP-HELPERS (now; no overlap).** Write set: `reader.clj`, `repl.clj` (line-starts only), `source.clj`, `lib.clj`, `sock.clj`, `profile.clj`, `intr.clj`, `config.clj`, `contract.clj`, `task.clj` (`root-fact`, `attempt-id`), `ref.clj`, `error.clj`, `harvest.clj`.
- Rows: A3 (the task, config and root-reader halves), A5, A6 (lib, sock, profile), A7, A8, A22, C4. Also A2's non-SN/RD/CV callers (`source.clj`, `ref.clj`, `config.clj`, `error.clj`) moved onto `task/eid` for now, and A4's harvest half.
- Size: about -55. Proofs: `seon.source-test`, `seon.repl-test`, `seon.config-test`, `seon.lib-test`, `bin/check contracts census threads`.

**Lane DUP-ROOT (queued: after tasks-at-once and reds land; it overlaps both).** Write set: `root.clj`, `reconcile.clj`, `agent_proc.clj`, `writer.clj` (A4 and A6 only), `entrance.clj` `attach!`, `agent.clj` (`await-op!` ping), `fork_server.clj` (A19), `merge.clj` (A4), `program.clj` (`eid` public, topo), `my/schema.clj` (`eid`), `db.clj` (C7, A3).
- Rows: B1, B2 (root half), B3 (reconcile and root rows), B4(e), B6, B8, A1 (root and reconcile readers onto `contract/properties`), A2 (finish: one `program/eid`), A4, A9, A10, A11 (root side), A19, A20, C1–C3, C7, C8, C10.
- Size: about -150, plus about -85 moved. B1 and B2 first: they are the root causes the others patch.

DUP-VIEWS and DUP-HELPERS share no file, so both can start now. DUP-ROOT takes each file after the lane that holds it lands (TA: reconcile, turn, root spawn; RD: writer, merge, my.schema, root answers; SN: db, fork server, program).

## Not measured, and left alone

- Line savings are read estimates. No row was implemented or timed.
- Already filed, so not refiled here (named in B4):
  - the stall timer;
  - the stop bounds;
  - the fork server's tick reaping;
  - the flusher's sleep;
  - `drivers-leave-src`.
- `context.clj`, `turn.clj` and `template.clj` were read only where they meet another row (A4's `turn.clj:44`). Their own duplicates are the CV and TA lanes' to find.
- `print/select.clj` and `print/emit.clj`: same names (`line`, `flat-width`, `form-text`), but different jobs. Not duplicates.
