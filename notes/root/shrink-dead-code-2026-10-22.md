# Shrink audit 2026-10-22: dead code and size census (read-only)

Main at 6114183. `src` has 16,842 lines in 57 namespaces: 1,215 top-level definitions, 749 comment-only lines and 1,375 blank lines. The goal is about 10k.

**The short answer.** Very little of `src` is unused: about **45 lines** of dead vars, and about **115 lines** at low risk once test-only and never-run code is counted. `test/` holds another **344 lines** that nothing runs. Most of the dead code the 2026-10-21 growth audit found (`../notes/AUDIT-2026-10-21-growth.md`) has already been deleted. Of its twelve unused check modes, only `units` and `inference` are left. So the shrink the owner wants has to come from structure, not from sweeping up unused code:
- the harness that lives in `src` (1,945 lines);
- the dashboard (about 2,260 lines, never audited);
- four remaining duplicate mechanisms (about 95 lines).

## Method

1. **Compiler facts.** I made a throwaway worktree (`bin/worktree audit-dead`) and built it. I started its own root on 8330 with `:seon.config.source/root` set in its gitignored `config/local.edn`, so main imported the source: 80 files, 1,298 forms, 1,225 vars. From `:form/refs` I dumped the whole callers graph and the 101 vars that have no caller. The root is stopped, and the worktree and its branch are removed.
2. **Alias-aware grep.** A script checked every top-level definition (`defn`, `defn-`, `def`, `defc`, `defchecked`, `defmacro`, `defonce`). It parses each file's `ns` form for `:as` and `:refer`, then counts references:
   - in the same file outside the definition, with strings and comments stripped;
   - as `alias/name`, fully qualified, or referred, in the rest of `src` and in `test`;
   - fully qualified in `resources/` (system.edn, schemas, config, task files), `bin/` and `docs/`.
3. **Every candidate was checked by hand** with `grep -rn` across src, test, resources, bin and docs.

**A gap in the compiler facts (worth filing as a task).** `:form/refs` misses three kinds of reference:
- syntax-quoted symbols (`` `tile-tasks `` in `seon.render.ui/system-page`, ui.clj:763-778 and 981-985);
- quoted symbols (`'seon.check/probe-proc`, check.clj:1004; `'seon.e2e/always-wrong`, e2e.clj:357);
- macro uses (`seon.sock/with-mem`, `seon.config/baked-schema-forms`, `seon.contract/baked-forms`).

So `my.agent/callers` reports false "no caller" results for 25 `seon.render.ui` functions and about 10 others. An agent that deletes code based on `callers` alone would break the dashboard. The fix belongs in `seon.program` (`account`, the analyzer tap), so that it records quoted and syntax-quoted qualified symbols as refs.

## Ranked table

Lines are net deletions from the named tree unless marked "moves".

| # | Candidate | Lines | Risk | Guarding tests (must stay green) |
|---|---|---|---|---|
| 1 | `test/` files nothing runs: `r3/admit.clj`, `r3/bench_inc.clj`, `r3/bench_rec.clj`, `seon/bench_commit.clj`, and the unreachable part of `r3/oracle.clj` | **-344 (test)** | low | `r3.inc-test`, `r3.train-test` |
| 2 | `seon.schema.datahike/declared-reference` + `chain-properties` | -20 | low | `seon.render-test`, `seon.schema-test`, `bin/check census` |
| 3 | `seon.e2e/par` mode + `seon.fixtures/par`, `par-name` | -29 | low | `seon.e2e-test`, `bin/check` e2e steps |
| 4 | `seon.check/units` mode | -12 | low | `seon.cost-test/the-unit-table-is-current` |
| 5 | `seon.check/inference` mode | -11 | low | Jolt's build-smoke elem-nil case; `seon.ref-test` |
| 6 | `seon.root/send-message!` + ext-op `:message` arm | -6 | low | `seon.root-test`, `seon.procs-test`, `bin/check smoke` |
| 7 | `seon.root/branch-info` + writer `:branch-info` arm | -6 | low | `seon.procs-test`, `seon.db-test`, `bin/check record` |
| 8 | `seon.root/agent-ping` (the agent `:ping` route maybe too, +~6 after checking) | -6 (to -12) | low (medium for the route) | `seon.root-test`, `seon.procs-test`, `bin/check lifecycle` |
| 9 | `seon.render/render` (tests only) | -7 | low | `seon.render-test` (switch to `render/html` and `render/ai`) |
| 10 | `seon.program/source-of`, which duplicates `seon.context/source-of` | -5 | low | `seon.merge-test` (switch to `context/source-of`) |
| 11 | `seon.learn/read-forms`: a second "text as data" reader beside `seon.reader/read-text` | -6 | low | `seon.context-test`, `seon.turn` via `bin/check e2e-stuck` |
| 12 | `seon.lib/c-getppid`, `bytes-allocated`, `collections` + their two Scheme defines | -5 | low | `seon.lib-test`, `bin/check threads` |
| 13 | `seon.fx/port` | -2 | low | `seon.fx-test` |
| 14 | `seon.entrance/root-eval`, the second REPL (uses `intern`), routed through `seon.repl` | ~-25 | medium | `seon.main-test/root-evals-go-through-the-one-repl` (filed), `bin/check mcp` |
| 15 | The `:provider` round trip: `seon.config/with-provider` and start!'s `:provider` replaced by a config layer | ~-20 | medium | `seon.config-test`, `bin/check config`, e2e scenarios |
| 16 | One "make entity e state exactly want" (`seon.task/update-tx` and `seon.docs/update-tx` become one `seon.db` fn) | ~-20 | medium | `seon.task-test`, `seon.task-files-test`, `seon.docs-test`, `seon.docs-cost-test` |
| 17 | Config's own baked schema reader (`seon.config/baked-schema-forms`) and `seon.contract/schema-forms`: one reader | ~-10 | medium | `seon.config-test`, `bin/check contracts`, `census` |
| 18 | 13 schema keys named nowhere (seon.core.edn 8, seon.merge.edn 3, seon.task.edn 1, seon.render.edn 1) | ~-16 (resources) | low-med | `seon.schema-test`, `bin/check census` |
| 19 | `seon.cost/unit-benches` + `measure-unit` (66), a bench in src that only a test uses | 66 moves to test/ | medium | `seon.cost-test`, `seon.print-cost-test` |
| 20 | The harness leaves `src`: `seon.check` 1,298, `seon.e2e` 420, `seon.fixtures` 227 (task `seon.main/drivers-leave-src`) | ~1,700 moves | medium | `seon.main-test/root-boots-without-test-fixtures` (filed), all of `bin/check` |
| 21 | The check modes rewritten as data rows (spawn → submit → assert), with no `out!` or sleep polling | -300 to -500 | high | every `bin/check` step replaced |
| 22 | The dashboard audit: `render.ui` 986, `render.proc` 528, `web` 337, `render.ai` 180, `render` 123, `web.html` 106 | unknown (largest block not yet audited) | high | `seon.render-test`, `seon.web-test`, `bin/check idle` |

**Totals.**
- Low risk (rows 1-13): **115 src + 344 test = 459 lines.**
- Medium (14-18): about 90 more in src and resources, plus 66 that move.
- Structural (20-22): where the size actually is.

## Evidence per candidate

### 1. test/ code nothing runs (-344, test)
- `test/test_runner.clj:16-21` loads only `*_test` namespaces, and no bin script names these files. Their only callers are each other.
  - `r3.admit` (94): no reference anywhere. It is a prototype of an admission policy, "jolt -A:test run -m r3.admit".
  - `r3.bench-inc` (48) and `r3.bench-rec` (30): referenced only by each other. `seon.source.edn` mentions `r3.bench-inc` in prose about require order; that prose needs one edit.
  - `seon.bench-commit` (126): no reference ("jolt -A:test run -m seon.bench-commit").
- `r3/oracle.clj` (281): `r3.inc-test` uses only `oracle/view` and `oracle/reconcile-view`. A closure over the file leaves `hairy`, `done?`, `blocked-by`, `should-run` and `to-kill` (41 lines) unreachable. `oracle/reconcile` (5) is used only by the bench files.
- This was already named in `seon.main/dead-code-goes`, and is still undone.

### 2. `seon.schema.datahike/declared-reference` (schema/datahike.clj:200-220, -20)
- The compiler facts show no caller of `declared-reference`. Its only callee, the private `chain-properties` (200-210), has no other caller. Grep finds only the definitions and the docstring.
- Renders are chosen by `seon.render` (render.clj) through its own selection, so this is a second, unused "render a schema declares".

### 3. e2e `par` (e2e.clj:180-198, its case line 412; fixtures.clj:40-49, -29)
- `bin/check` runs `e2e calc`, `scenario {conflict semantic hairy newns sibling}`, `swarm`, `loop`, `stuck`, `resume` and `resume2` (bin/check:140-150). Nothing runs `par`: no bin script, test or doc names it. Only `notes/option3-R3-scale.md` names it, as history.
- `seon.reconcile/reconcile` loses its only src caller but stays: `reconcile_test`, `r3.inc-test` and `r3.train-test` use it.

### 4. `seon.check/units` (check.clj:1121-1131, case line, -12)
- No step in `bin/check` runs it (bin/check:34-150). Its only use is regenerating the `seon.cost/units` literal by hand.
- `seon.cost-test/the-unit-table-is-current` re-measures through `cost/measure-unit` and stays the proof. Its failure message can print the new row, which is what `units` printed.

### 5. `seon.check/inference` (check.clj:902-911, case line 1295, -11)
- Not in `bin/check`, `bin/test` or any script.
- It was the binary-level proof for `jolt.ns/whole-program-inference-keeps-nil-checks`. That task records the fix in Jolt (`jolt/infer-nil-union`) with "proof build-smoke's elem-nil case", so the check duplicates the dependency's own proof.

### 6. `seon.root/send-message!` (root.clj:848-851; ext-op arm 304-305, -6)
- Compiler facts: no caller. Grep: no occurrence outside its definition in src, test, resources, bin or docs.
- Agents message each other through `my.agent/send-message` → `seon.agent/send-message`, which root handles in `back-op` (root.clj:391). That path stays. The `ext-op` `:message` arm (304) is reached only from `send-message!`.

### 7. `seon.root/branch-info` (root.clj:831-833; writer.clj:186-188, -6)
- No caller in src or test. The writer's `:branch-info` op is sent only by it (`grep -rn :branch-info src test` finds the two sites).

### 8. `seon.root/agent-ping` (root.clj:884-889, -6)
- No caller in src, test, bin or docs. The only mention is the plan of task `seon.fences/health-is-answered-and-failover-recorded` (seon.fences.edn:49), which wants its `:unknown` mapping fixed. Deleting it settles that part. Its proof `seon.procs-test/a-ping-of-a-gone-agent-answers-its-error` does not exist yet, so that task's text must be updated in the same commit.
- The `:ping` request route (root.clj:278 and 287, agent_proc.clj:87 and 165-166, agent.clj:86) may also become dead. agent.clj:85 says "Flow's ping reaches a waiting agent too", so verify that Flow's `ping-proc` never routes there before removing it. Medium risk for that part.

### 9. `seon.render/render` (render.clj:104-110, -7)
- Compiler facts: no src caller. Production calls `seon.render/html` and `seon.render/ai` directly. Only `test/seon/render_test.clj` calls `render`. Rewrite those assertions against `html` and `ai`.

### 10. `seon.program/source-of` (program.clj:443-447, -5)
- Compiler facts: no src caller. `seon.context/source-of` (context.clj:52-55) answers the same thing, the `:form/src` of `:var/name` sym, and has four src callers.
- `test/seon/merge_test.clj:84,114` call the program one; switch them to `context/source-of`.

### 11. `seon.learn/read-forms` (learn.clj:16-22, -6)
- It is `read-string` of `"[" s "]"` with `[]` on failure. `seon.reader/read-text` (reader.clj:26-35) is the one "text as data, nil when it doesn't read" reader, used by five callers. `learn/aliases` and `learn/form-reads` can call `(or (reader/read-text s 'user) [])`.
- `test/` uses `seon.source/read-forms`, a different function that stays.

### 12. seon.lib leftovers (lib.clj:33, 145, 147, 240, 242, -5)
- `c-getppid`, `bytes-allocated` and `collections`: no reference anywhere.
- `seon.profile/alloc-bytes` already reads `(sstats-bytes (statistics))` (profile.clj, `seon-profile-alloc`), so `bytes-allocated` is a second copy.
- `gc-stats` covers the collection count.

### 13. `seon.fx/port` (fx.clj:18-19, -2)
- No caller in src, test, resources, bin or docs. Root builds the same keyword inline (`(keyword "fx" (name k))`, root.clj and check.clj:1195).

### 14. `seon.entrance/root-eval` (entrance.clj:32-56)
- It is a second read/eval loop beside `seon.repl/eval-code`. It `intern`s `sys` into `root.repl` (AGENTS.md §3 calls that a code smell) and prints with `pr-str` instead of the one printer.
- It is already filed as step (5) of `seon.main/dead-code-goes`, with a named proof. About -25.

### 15. The provider round trip (config.clj:247-273; root.clj:604-606)
- `start!` takes `:provider` and converts it back into config with `with-provider` (13 lines), so the facts say what ran.
- The callers that pass `:provider` are e2e (`stuck` e2e.clj:357, `model-provider`) and `check config-check`. They can pass a config layer (`--set :seon.config.ai/script=...`, as agent_test.clj:100 already does).
- About -20. It touches root.clj, e2e.clj and check.clj, so it runs after lanes A and C.

### 16. Two `update-tx` (task.clj:318-338, docs.clj:80-97)
- Both pull the entity, diff it against want, assert or retract, and handle card-many element by element. One `seon.db` function taking `[db e pull-pattern want]` serves both. About -20.

### 17. Two baked schema readers (config.clj:66-77, contract.clj:~75-110)
- Both read `resources/seon/schemas/*.edn` at compile time. Config cannot require contract (contract requires config), so the shared reader goes into `seon.edn`, a leaf. About -10.

### 18. Schema keys named nowhere (resources/seon/schemas)
- These keys appear in no src, test or config text, and no other schema refers to them:
  - seon.core.edn:24-66: `:seon.db/delta`, `:seon.db/conflict`, `:seon.step/out`, `:seon.writer/answer`, `:seon.fork-server/state`, `:seon.fork-server/root-state`, `:seon.sock/read`, `:seon.wire/reader`, `:seon.agent/printed`;
  - seon.merge.edn:19-47: `:seon.merge/form`, `:seon.merge/entry`, `:seon.merge/merge-main-result`;
  - seon.task.edn:69: `:seon.reconcile/view`.
- The `*-fact` keys with `{:seon.db/attributes true}` look unused too, but they are live: `seon.schema.datahike` derives attributes from them.

### 19. A bench in src (cost.clj `unit-benches` 54, `measure-unit` 12)
- Its callers are `seon.check/units` (row 4) and `seon.cost-test`. After row 4 only the test uses it, so it moves next to the test. `seon.cost/units` (the table literal) stays.

### 20-22. Structural
- **The harness in src (1,945 lines, 11.5% of src).**
  - `seon.root` requires `seon.fixtures` (root.clj:30), and default.edn:235 defaults `:seon.config.ai/script` to `seon.fixtures/reply`.
  - The release binary's `-main` is `seon.check/-main` (bin/build:5). It also serves `root`, `mcp` and `ports`, so those entry points must move to a `seon.main`.
  - Root functions that only checks call stay where they are: `pause-agent!`, `resume-agent!`, `branch-diff`, `branch-db`, `agent-stats!`, `agent-health`, `threads`, `seon.lib/summ`, `gc-stats` and `seon.db/store?` (compiler facts: every caller is in seon.check or seon.e2e).
  - Task `seon.main/drivers-leave-src` is filed.
- **Data-driven checks.** `check.clj` has 52 definitions. Its modes repeat one script shape: `c/start!` → spawn or submit → `await-status` (a `Thread/sleep` poll, check.clj:9-16) → `out!` → `c-exit`. The largest are smoke 83, robust 67, scale 66, backpressure 55, idle 50, problems-check 49, lifecycle 49, swarm 48 and fx 45. One table of rows over one driver replaces most of them, and removes the sleeps that AGENTS.md §8 forbids.
- **The dashboard.** About 2,260 lines, the largest subsystem relative to what it shows, and never audited. Every schema-declared render function (`verdict-box`, `task-box`, `attempt-row`, `config-panel`, `proc-health`, `cost-report`, `agent-page`, `system-page`, `agent-card`) is reached through its schema key, and each key is used in src. So the cut is a design question (which tiles earn their place), not dead code.

### Already gone or not candidates (re-verified today)
- **Already deleted:** most of the 2026-10-21 audit's dead list: `alarm-in!`, `unblock-signals!`, `sigmask`, `intr/running?`, `db/diff-ops`, `stop-agent!`, `freeze!`, `thaw!`, `decode-edn`, `read-one`, `task-classes`, `docs/homes`, `config/sources-from-db`, `pr-edn`, and ten of the twelve check modes. **Task `seon.main/dead-code-goes` is stale**: its steps (1)-(3) are mostly done, and its line citations are wrong. It should be rewritten from this table.
- **Kept on purpose:**
  - `threads-declared`: `seon.root-test` runs it, root_test.clj:27.
  - `seon.fixtures/two-calls`: a test's scripted model, resolved by the built binary, agent_test.clj:100.
  - `seon.check/probe-proc` and `seon.e2e/always-wrong`: quoted references.
  - `lib/scheme-defs` and `profile/scheme-defs`: side-effecting `defonce`s.
  - Every `my.*` verb: agents call them in eval'd text.
- **Not recommended:**
  - `seon.ref/scan` (235, the largest function), a deliberate one-pass CommonMark subset; Jolt has no Markdown library.
  - The printer (`print` + `print.select` + `print.emit` = 1,326), whose measured win is on record (63517df).

## Size census

### Lines per namespace
| ns | lines | role |
|---|---|---|
| seon.check | 1,298 | harness: check modes and the binary's -main |
| seon.render.ui | 986 | dashboard HTML renders |
| seon.root | 965 | control process: graph, agents, API |
| seon.program | 759 | program as entities: account, load, reach |
| seon.print.select | 747 | value printer: selection under budget |
| seon.reconcile | 740 | the one reconciler |
| seon.ref | 608 | Markdown references (scan is 235) |
| seon.agent | 569 | agent process |
| seon.render.proc | 528 | render proc |
| seon.repl | 511 | agent nREPL |
| seon.print.emit | 436 | printer layout |
| seon.e2e | 420 | harness: task e2e |
| seon.task, seon.db | 373 each | tasks as data; store |
| seon.context | 360 | agent context |
| seon.cost | 345 | cost proofs (54-line bench inside) |
| seon.source | 342 | source as program |
| seon.fx | 341 | effect owners |
| seon.web | 337 | web proc |
| seon.merge | 310 | program merge |
| seon.config | 305 | config |
| seon.contract | 299 | contracts |
| seon.writer | 289 | group-commit writer |
| seon.sock | 284 | transport |
| seon.lib | 269 | FFI and host |
| seon.agent-proc | 262 | agent stand-in proc |
| seon.fork-server | 253 | fork server |
| seon.model | 229 | model call |
| seon.fixtures | 227 | harness: scripted model and graphs |
| seon.turn | 224 | turn loop |
| seon.schema.datahike | 220 | schema → attributes |
| seon.reader | 196 | reading source (scan is 103) |
| seon.render.ai | 180 | AI renders |
| seon.docs | 175 | wiki in the store |
| seon.template | 171 | task templates |
| seon.problems | 167 | problem read |
| seon.print | 143 | printer entry |
| seon.error | 142 | recorded errors |
| seon.entrance | 136 | entrance socket |
| seon.profile | 134 | call profiler |
| my.root | 130 | root verbs |
| seon.render | 123 | render selection |
| seon.web.html, seon.intr | 106, 104 | HTML serializer; interrupts |
| seon.learn, my.schema, my.agent | 88, 88, 87 | waste; agent verbs |
| seon.mcp, seon.proofs, seon.system, seon.step, seon.harvest | 72, 65, 64, 62, 52 | |
| my.branch, my.task, my.fx, my.db, seon.edn, my.turn | 46, 28, 23, 20, 17, 14 | |

**Out of line with what they do:**
- Harness 1,945 lines in src.
- Dashboard about 2,260 lines, more than the reconciler, the writer and the fork server together (1,282).
- Printer 1,326 lines.
- `seon.ref` 608, for finding citations in Markdown.

### The 30 largest functions (lines, from `:form/src`)
| lines | var | lines | var |
|---|---|---|---|
| 235 | seon.ref/scan | 60 | seon.reconcile/actions |
| 117 | seon.root/start! | 58 | seon.program/account-tx |
| 112 | seon.root/back-op | 57 | seon.root/api-proc |
| 103 | seon.reader/scan | 56 | seon.turn/act! |
| 100 | seon.agent-proc/agent-frame | 55 | seon.render.proc/render-proc |
| 87 | seon.reconcile/advance | 55 | seon.profile/scheme-defs |
| 83 | seon.check/smoke | 55 | seon.check/backpressure |
| 81 | seon.root/ext-op | 54 | seon.writer/other-op |
| 77 | seon.fixtures/scenarios | 54 | seon.fork-server/server-step |
| 75 | seon.render.proc/pass | 54 | seon.cost/unit-benches |
| 73 | seon.print.select/classify | 53 | seon.writer/writer-proc |
| 67 | seon.fork-server/fork-server-proc | 50 | seon.template/attach-tx |
| 67 | seon.check/robust | 50 | seon.check/idle |
| 66 | seon.check/scale | 49 | seon.web/web-proc |
| 63 | seon.repl/eval-code | 49 | seon.render.ui/tile-tasks |

## Deletion lanes (disjoint files, can run in parallel)

| Lane | Rows | Files | Net |
|---|---|---|---|
| **A: root dead API** | 6, 7, 8 (agent-ping only) | root.clj, writer.clj, seon.fences.edn (task text) | -18 |
| **B: leaf dead vars** | 2, 9, 10, 11, 12, 13 | schema/datahike.clj, render.clj, program.clj, learn.clj, lib.clj, fx.clj; render_test.clj, merge_test.clj | -45 |
| **C: harness trim** | 3, 4, 5, 19 | check.clj, e2e.clj, fixtures.clj, cost.clj, cost_test.clj | -52 (66 moves) |
| **D: test prune** | 1 | test/r3/{admit,bench_inc,bench_rec,oracle}.clj, test/seon/bench_commit.clj, seon.source.edn (prose) | -344 test |
| **E: state-tx** | 16 | task.clj, docs.clj, db.clj | ~-20 |
| **F: schema keys** | 18 | resources/seon/schemas/seon.{core,merge,task}.edn | ~-16 |

After A and C land:
- **G: entrance** (row 14): entrance.clj, repl.clj.
- **H: provider** (row 15): config.clj, root.clj, e2e.clj, check.clj.
- **I: registry** (row 17): config.clj, contract.clj, edn.clj. It runs after H, since both touch config.clj.

Then:
- **J: drivers-leave-src** (row 20) on the trimmed harness.
- **K: dashboard audit** (row 22), read-only, can start now.
- **L: data-driven checks** (row 21), after J.
- **M:** the `:form/refs` gap (quoted and syntax-quoted symbols, macro uses) filed as a `seon.program` task before any agent deletes code based on `callers`.

Each lane's landing proof: the tests named in its rows, plus `bin/check census` and `bin/check smoke` (and `bin/test r3.inc-test r3.train-test` for D).
