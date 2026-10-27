# Shrink research: where `src` rebuilds a library or does one job twice (2026-10-22, read-only)

The owner asked to bring `src` (16,661 lines at `fcac0df`) toward ~10k without losing capability. This note ranks what can go: code that only drivers and tests use (moved out of `src`), code that rebuilds core.async, Flow, Datahike, Jolt or clojure.core (replaced), and our own mechanisms that do one job twice (merged). It then groups the rows into five disjoint briefs for one session to do in order.

**How it was made.** Four read-only agents each read three of the twelve largest namespaces in full: check, root, render/ui, reconcile, program, print/select, ref, agent, render/proc, repl, print/emit and task. They also glanced at the namespaces around them. Every candidate was grepped by word boundary across `src`, `test`, `bin`, `resources` and `docs`, and root spot-checked a sample of those greps. Nothing was built, run or edited.

**What this note does not repeat.** Rows already in `notes/duplicates-audit-2026-10-22.md` (A1–A22, B1–B10, C1–C10) are not listed again, nor is what landed after it: one `eid`, one clock, `line-starts`, the views lane, and the test store leaving `src`. Lane **dup-root** owns B1 (fake refusal fields), B2 (error shapes) and A4 (uncontracted). A row below that extends one of those says so.

**Line counts are estimates from reading, not measurements.** A session that finds a real count off by 3× or more reports it as a finding.

## The total, honestly

| Kind | Est. lines |
|---|---|
| Moved out of `src` (drivers, fixtures, check-only API) | ~1,820 |
| Deleted or replaced, safe now or after a named lane | ~430 |
| Deleted behind an owner decision (marked **owner**) | ~150 |
| **`src` after all of it** | **~14.3k** |

These rows do not reach 10k. The gap is about 4k lines, and it sits in code that is our capability rather than duplication, and where no library offers a drop-in:
- **`ref.clj`'s CommonMark scanner** (`ref.clj:41-275`, ~235 lines). Neither Jolt's stdlib nor its vendor tree has a Markdown parser; v1 used commonmark-java.
- **The printer** (`print/select.clj` + `print/emit.clj`, 1,183 lines). It replaced fipp deliberately in `63517df`: 45.4 → 2.41 KB and 16–17 → 1.8 µs per entry. Jolt's `clojure.pprint` (`reference-code/jolt/stdlib/clojure/pprint.clj`, 2,474 lines) would give that win back.
- **The flight state machine** in `reconcile/advance`. §5's no-blocking step rule rules out rewriting it as `go` blocks.
- **The fork and socket physics** in `agent.clj`, `sock.clj` and `fork_server.clj`. v1 ran on JVM threads and had none of it.

v1 offers nothing shorter for any namespace read. It is 102.5k lines, and every counterpart was larger: `program.cljc` 1,879 lines, `ref.clj` 932, `task.clj` 2,019, `repl.clj` 635, `render/web.clj` 3,837, `render/value.clj` + `print.cljc` 2,740, and `reconcile.cljc` 469 (a different job).

Reaching 10k means owner rulings on what stays. Candidates:
- whether the dashboard (`render/ui` 971 + `render/proc` 487 + `web` 306) is `src` or a driver;
- whether the printer's selection and its `:cuts` data are worth their lines;
- whether `cost.clj` and `profile.clj` measurement belongs in the release binary.

---

## Ranked rows

Rows are ranked by lines per unit of risk. Area tags name the in-flight session whose files a row touches:
- **rename**: names everywhere;
- **dup-root**: `root.clj`, `reconcile.clj`, `agent_proc.clj`, `writer.clj`, the error shapes;
- **renders**: `seon.print` and `render/*`;
- **prove**: `proofs.clj`, `test_runner`, check selection;
- **dry-run**: e2e and model.

| # | What | Where | Replacement | Est. lines | Risk | Area | Evidence |
|---|---|---|---|---|---|---|---|
| 1 | **The check driver lives in `src`.** Only `root-serve` and the `root`/`mcp`/`ports` arms of `-main` are production code (~35 lines). | `check.clj` (1,199): production is `:938-961`, `:1156-1199` | `seon.main` holds the release `-main` (root, mcp, ports). The rest moves to `test/` (filed: `seon.main/drivers-leave-src`). | **~1,160 moved** | med | prove, dry-run | No `src` namespace requires `seon.check`. `bin/build:170-172` builds the release with `-m seon.check`, so `bin/root`, `bin/mcp` and `bin/ports` enter through it, and the build line changes. |
| 2 | **The e2e runner lives in `src`.** | `e2e.clj` (340) | `test/` | **340 moved** | low | dry-run | Only `check.clj` calls it; `fixtures.clj` names it in docstrings only. |
| 3 | **The scripted model and its task graphs live in `src`, and they are the default model.** | `fixtures.clj` (235); `root.clj:30` requires it; `default.edn:236-241` `:seon.config.ai/script seon.fixtures/reply` | `test/`. The default provider must stop being test data first: today it is a fake default (§9). | **~235 moved** | med | dry-run | `default.edn:239`: provider `"scripted"` unless `SEON_PROFILE`. |
| 4 | **Check-only root API**, beyond audit C8: `declared-threads`, `threads`, `events`, `stats`. `health` loops `ping-proc` over five hand-listed pids. | `root.clj:800`, `:810`, `:890`, `:933`, `:964-994`, `:1031-1064` | Moves with the driver. `health` becomes `flow/ping` (`reference-code/jolt/stdlib/clojure/core/async/flow.clj:160-166`), which pings every proc. | **~85 moved, ~8 deleted** | low | dup-root | `declared-threads`: 11 uses in check.clj, 1 in root_test. `threads`: 7 in check.clj. |
| 5 | **Form and definition facts are written and read three times** (`program/account-tx`, and merge's `def-ref`, `set-form-tx`, `set-def-tx`): <br>• the same chain of nine `(seq x) (assoc :form/x …)` lines twice; <br>• the Datahike tempid workaround twice; <br>• definition key → entity four ways; <br>• the empty account map three times. | `program.clj:164-165`, `208-209`, `353-360`, `384-404`, `442-445`, `645-654`, `700-701`; `merge.clj:55-61`, `84-87`, `156-207`; `source.clj:108-109` | One owner in `seon.program`: <br>• one attribute list that drives both the write and `form-row`; <br>• `def-eid`, `def-ref` and `pointer`; <br>• `account-tx` = supersede logic over merge's per-key tx. | 45–60 | med (tempid order) | — | Compare the comments at `program.clj:384-386` and `merge.clj:157-160`. |
| 6 | **Two test-selection mechanisms that disagree.** The agent uses `changed-vars` → `reaching-tests`, and `changed-vars` diffs only `:var/form`, so a changed defmethod, protocol extension or schema selects no tests. `reach-digests` follows all of them, but in `src` only `test_runner` calls it. | `program.clj:712-738` vs `745-778`; `agent.clj:297-307` | The agent runs the tests whose reach digest differs between base and branch; `reaching-tests` and `changed-vars` go. | ~28 (or 39 test-only lines in `src` if not unified) | med (cost) | prove | `reach-digests` src callers: `test/test_runner.clj:95` only. The `seon.agent.edn:16` task measured a broad `reaching-tests` at 359 ms. Needs a cost proof first. |
| 7 | **nREPL ops with no client**: describe, clone, close, ls-sessions, load-file and interrupt; the session bits in `session-ns`/`eval-code`; the `:interrupted` flag; `intr/clear-pending!`. | `repl.clj:40-47`, `80-87`, `117-151`; `intr.clj:45` | Keep only the `"eval"` op | ~35 | med | — | Every request is built as `"op" "eval"` (`repl.clj:397`, `:421`). Caveat: the dead `interrupt` op is the only thing that clears a pending interrupt, so first check that a SIGUSR1 landing while idle still clears. |
| 8 | **The root's api batches up to 256 messages per step** with `a/poll!`. That is why it carries `::taken` (a volatile kept across Flow's catch), `::lost`, and `:error`'s drain of `::lost`: Flow's error names one message. The batch also repeats `::flow/input-filter`'s `api-writes` rule. | `root.clj:432`, `477`, `504-506`, `516-527`, `546` | One message per step, as Flow's loop takes them (`flow/impl.clj:463-470`, `478-480`); drop `:seon.config.root/api-batch`. Measure `flow/update!` cost per spawn burst first. | ~17 | med | dup-root | `::taken`, `::lost`, `answer-taken`: 10 references, all in root.clj. |
| 9 | **The reconciler index keeps reverse indexes by hand** (`:by-task`, `:needed-by`) that Datahike's AVET index already holds. | `reconcile.clj:128`, `190-208` | `(d/datoms db :avet :seon.attempt/task eid)` (indexed, `seon.facts.edn:175`); reverse refs via pull (`reference-code/datahike/src/datahike/pull_api.cljc:389`); `:seon.task/needs` gains `{:seon.db/index true}` (`seon.facts.edn:130`). The derived `:depth`, `:acc`, `:done` and `:ready` stay. | ~15 | med | dup-root | Cost proofs must stay O(changed); an AVET seek is O(log n). |
| 10 | **Four hand-written closure loops in `program`** (`schema-dependents` rescans every key each round, O(K²)) **plus `task/accumulate`'s walk**, with its O(n) `(some #{id} acc)` per visit. | `program.clj:537-545`, `561-563`, `727-738`, `773-776`; `task.clj:209-212` | One `closure [start neighbors]` beside `program/topo` (joins audit A9). Datahike's `experimental/graph.cljc:131-143` `transitive-closure` is not a drop-in: it is not in our build, it needs a 6-method GraphSpec, and its lookups are O(E). | ~18 | low | — | Read. |
| 11 | **A thread only to copy Flow's error channel into `:back`**, using `alts!!` on one channel, with a declared thread and a `.join 5000` in `stop!`. | `root.clj:724-736`, `1019`, `1052` | `(a/pipe error-chan (a/chan 1 (map ->op)) false)` into `:back` (`reference-code/jolt/stdlib/clojure/core/async.clj:662`). No thread and no join. §5 allows a thread only for a foreign blocking read. | ~10 | low | dup-root | 3 references, all in root.clj. |
| 12 | **Output buffers (`::to-agents`, `::replies`) beside the step's own outputs volatile.** Flow sends outputs only after `transform` returns. | `root.clj:126-134`, `180-188`, `544-547` | `step/out! o …` (`step.clj:55`), threading `o` as `back-op` already does | ~8 | low-med | dup-root | `flow/impl.clj:470-473` |
| 13 | **`ref/pin` reads `.git` by hand** (gitdir, commondir, packed-refs), twice per view, and answers the checked-out HEAD rather than the pin its doc promises. | `ref.clj:520-544`, `567` | The gitlink, read as `test_runner.clj:43` reads it (`git ls-files -s`, mode 160000), through `fx/git!` (`fx.clj:77`) | ~20 | med (a shell call while building context) | — | One consumer: `context_test.clj:307`. |
| 14 | **The check harness is a test framework of its own**: `run-check`, `verdict!`, `out!`, and `{:checks {k bool}}` in place of assertions; e2e's second `verdict!`; a third waiter, `await-records`; hand-listed `pure-nss`/`pure-fns`. | `check.clj:12-37`, `312-323`, `967-972`; `e2e.clj:107` | Each mode becomes a `deftest` with `is`, run by `clojure.test/run-tests`/`successful?` (`reference-code/jolt/stdlib/clojure/test.clj:660`, `:741`), so `test_runner --reach` reuses unchanged checks too. Purity is marked on the contract. | ~55 (in the moved code) | med (`bin/check`'s OK/FAILED grep; kill/reopen modes exit 137) | prove | `seon.proofs` already reads clojure.test report data: two verdict readers today. |
| 15 | **Two producers of a section view that disagree on extent.** `ref` runs a section to the next heading at its level or above; `docs` stops at any heading. So one address shows different text at `:code` depth (`context.clj:79` picks either). `docs/page-sections`' `owner` is O(sections × citations). | `ref.clj:489-498`, `555-563`; `docs.clj:13-41`, `131-145` | One extent rule (ref's `:end-line`) that docs takes rather than recomputes | ~12 | low-med | — | Read. |
| 16 | **Code re-read where compiler facts exist** (§9): `macro-uses` re-reads the form and resolves heads, which can disagree with the analysis (a local named like a macro, a quoted list). `context/callees` and `learn.clj:21,31` read proof source when `:form/refs` holds the resolved refs. | `program.clj:278-288`; `context.clj:92-110`; `learn.clj:21-31` | The unit tap's facts and `:form/refs`. Jolt keeps only the innermost expansion (`analyzer.clj:111-120` `*expansion-box*`), so a `jolt/<topic>` branch lets the tap report every macro it expanded. | ~15 | med (needs Jolt) | — | Read. |
| 17 | **Our own read-eval loop**: per-form read, `*1 *2 *3 *e`, ns tracking. | `repl.clj:66-123` | Jolt's `clojure.main/repl` with `:read`, `:eval`, `:print` and `:caught` hooks (`reference-code/jolt/stdlib/clojure/main.clj:431-528`) | ~20 | med (the interrupt must make `:read` return request-exit; per-form output capture goes in `:eval`) | — | `jolt/nrepl.clj:396` `evaluate` loads a whole string, so it does not fit. |
| 18 | **Bindings as a second config path**: `:api-from-writer-buf`, `:reconciler-buf`, `:model-lanes`, `:model-rate`, `:web-port` and `:stall-ms` are read in `start!` and passed through `:bind/`. | `root.clj:710-713`; `system.edn` | The procs call `cfg/value`, or `system/read-decl` gets a `#cfg` tag (`system.clj:12-15`). Joins audit B6. | ~6 | low | dup-root | — |
| 19 | **A boot check nobody reads.** `interrupt-self-test` spins up to 100k evals at every agent boot; nothing reads `:interrupt-ok` by name. | `agent.clj:267-276`, `537`, `548`; `repl.clj:465`; `agent_proc.clj:129` | Delete it, or file a problem fact when it fails | ~14 | low | dup-root (agent_proc) | `interrupt-ok`: 3 hits, all producers or carriers. |
| 20 | **Task attribute lists typed four times.** | `task.clj:188-193` `task-pull`, `236-243` `attempt-pull`, `250`, `311-315` | Datahike wildcard pull `[* {:seon.task/needs [:seon.task/id]}]` (`datahike/pull_api.cljc:440-464`), or the lists read from `schemas/seon.task.edn` | ~12 | med (a wildcard also pulls `:seon.task/declared`) | — | Today a new attribute means editing up to four lists. |
| 21 | **Missing-needs and cycle checks done twice, with two refusal builders.** | `task.clj:82-91`, `87`, `317`, `356-362` | One private `needs-refusal` | ~10 | low | — | `load-tx` checks, then calls `create-tx`, which checks again. |
| 22 | **`group3` is a hand-written thousands separator.** | `render/ui.clj:40-50` | `(format "%,d" (long n))` (Jolt: `host/chez/natives-format.ss:299`, `test/chez/unit.edn:338`) | ~8 | low | renders | 19 hits by name (uses stay; the body goes). |
| 23 | **`leaf-width` has two arms that never run**, and `emit/emit-text`'s only caller is the dead `:else` arm. | `select.clj:237`, `242-243`; `emit.clj:421-426` | Delete them. The contract still returns an int. | ~9 | low | renders | `leaf` has one caller (`select.clj:344`). |
| 24 | **`throwable-value` re-implements `Throwable->map`'s `:via`.** | `select.clj:77-87` | `Throwable->map` (`reference-code/jolt/jolt-core/clojure/core/21-coll.clj:220-240`), dropping `:at` | ~8 | low-med (Jolt's has no cycle guard) | renders | — |
| 25 | **`classify` repeats itself**: identical keyword and symbol branches, two identical vector rows, three copies of the big-number face. | `select.clj:179-218` | One name-node helper, one row, one `big-number` | ~8 | low | renders | Keep the exact-class test ahead of `instance?`: it is there for speed. |
| 26 | **Each proc's numbers built twice**: a `:stats` answer and its `ping-map-fn`. | `web.clj:217-219` / `268-270`; `render/proc.clj:377-385` / `441-443` | `ping-map-fn` = the one stats fn | ~6 | low | renders | Near-identical key sets. |
| 27 | **Print options and keys written but never read**: `:seon.print/length`, `:level`, `:table?`, `:requery-form`. `value-html` postwalks every `<details>` open and dissocs an `:id` nobody sets. `keyframe!` regex-extracts a `<body>` that never exists. | `emit.clj:14-18`, `95-97`, `108`; `select.clj:725`; `ui.clj:138-141`; `web.clj:177` | Delete; `ops-hiccup` opens every node | ~12 | low | renders | `requery-form`: 2 hits, both writes. `render.clj:75` is the only `:hiccup` caller. |
| 28 | **Two judgement round-trips to the writer** (`:read`, then `:diffed`). After a train, `:landed` asks for main's db, which `:main` already delivered. | `reconcile.clj:662-669`, `694-695` | The `:branch-db` answer carries `:ours`; the write-back reads `(:db st)` at the train's t | ~8 | low/med | dup-root | — |
| 29 | **Thin wrappers**: `reconcile` = `(actions (index db))` (tests only), `running` = `(:live idx)`, `done?` aliases private `idx-done?`. The `:spawned` refused arm also rebuilds `attempt-start-tx`/`accumulate`. Stale docstrings claim a sliding buffer of one, so the `next?` relist only fires at boot. | `reconcile.clj:452-465`, `504`, `632-643`, `744`, `758-771` | Call `actions`/`index` directly; make `idx-done?` public; one `start-tx` let; `next?` → `(:idx st)` | ~17 | low | dup-root | `reconcile`: 37 uses, all tests; `running`: 3 (e2e, test). |
| 30 | **Two item shapes for one eval.** The record renames `:printed`→`:shown` and `:err`→`:error`, and `item-keys` retypes the schema's keys. `:nss` is written and never read; result handles are stored twice. | `repl.clj:108`, `113-114`, `341-343`, `363-365`; `agent.clj:548` | Store the item as it is; keys from `:seon.agent/item` (`seon.core.edn:65`). This removes the cause of audit A13's `(or :printed :shown)`. | ~7 | low | renders (ui reader) | — |
| 31 | **Agent-side test producers invent results**, `{:pass 0 :fail 0 :error 1}`: B1's pattern at producers B1 does not list. | `agent.clj:358-361`, `373-374`, `382` | A typed error map | 0–5 (root cause) | med | dup-root (extends B1) | — |
| 32 | **Two fold-and-record paths; a three-arity handler of which only zero-arity is used; the time/alloc/profile measurement written twice.** | `repl.clj:207-210`, `329-335`, `388-415`; `cost.clj:134-143` | One fold fn; one `reduce` (as Jolt's private `build-handler`, `nrepl.clj:593`); one measure fn in `seon.profile` | ~18 | low | — | Tests call only `(repl/handler)`. |
| 33 | **`merge-main!` reads main's program twice**, and re-derives the writer's three-way. | `agent.clj:206`, `209`; `writer.clj:22-30` | One `pm/against-main` → `[head result theirs]` | ~6 | low | dup-root (writer) | — |
| 34 | **A cache keyed by time**: `mem-mb` keeps its reading for one second (§5). | `agent.clj:421-430` | Measure on the event that asks (commit, `:tests` reply) | ~5 | low | — | Called on every eval reply (`repl.clj:415`). |
| 35 | **Dead**: `program/var-kind`. | `program.clj:47-53` | Delete | 7 | trivial | — | 1 hit (its own def). |
| 36 | **owner**: **three render-once caches beside the unit mechanism**: the event stream's hand patch (`:event-rows` twinned with `:events`, the late re-render, the prepend/remove assembly) and the zoom history's `:rows` cache. | `render/proc.clj:173-210`, `250-254`, `314-355`; `ui/history-list` | Each event and history row becomes a unit with a `:container`, as agent cards are (`ui.clj:776-778`); `finish-patch` drops any containered unit the layout dropped; units gain a prepend mode. This also retires audit B4(e)'s re-order clock. | ~35 | med | renders | — |
| 37 | **owner**: **`:cuts` is read only by tests.** The docstring says agents read it; agents get only `:text`. | `select.clj:639-659`, `701-709`; `emit.clj:89-116`; `context.clj:151` | Trim the cut node to prefix, unit, omitted, continuation and refusal; drop `:cuts` | ~30 | med | renders | Readers: `print_test.clj:69,80`, `context_test.clj:189-195`. |
| 38 | **owner**: **two string cutters with a continuation** (prose and select). | `print.clj:62-135`; `select.clj:289-303`, `502-513` | One cutter in `select` with a `:prose?` face | ~25 | high (output changes everywhere text is cut) | renders | — |
| 39 | **owner**: **proc wiring by hand beside Flow's `:conns`.** `:to-writer`, `:back`, `:ext` and `:events` are external channels passed as args. | `root.clj:214`, `689-713`; `system.edn:16-25`, `50-60` | Declared conns; agents' conns are added by the `update!` that adds their procs | ~10 | high | dup-root | — |

Smaller items, under 5 lines each, to take with their brief:
- `pull-end`'s rename loop is `update-keys`.
- `last-commit` sorts a whole query result where `max` would do.
- `stderr-tail` is public with one caller.
- `heading-text` repeats `inlines`' code-span and link handling (~8).
- `top-fns` hard-codes 10 and names a missing config key `:seon.config.test.cost/top`.
- `source.clj:44,131,138` and `repl.clj:72` write out the `LineNumberingPushbackReader` read four times beside edamame's `reader/read-all`.
- `task/conflict-members` and `task/root` are public but used only inside the file.

---

## Briefs: five disjoint sessions, run one after another

The rename session touches names everywhere. Each brief merges main after rename lands, or before rename starts, and does not run beside it. The briefs share no file, except brief 1 and brief 2 on `root.clj`: brief 1 takes only the check-only API region (`:800-1064`), and brief 2 the proc (`:120-740`).

### Brief 1: drivers leave `src` (rows 1–4, 14) ≈ −1,820 moved, −60 deleted
- **Write set:** `check.clj`, `e2e.clj`, `fixtures.clj`, a new `seon.main` (the filed task), `root.clj`'s check-only API, `bin/build`, `bin/check`, `default.edn` (`:seon.config.ai/*`), `test/`.
- **Order:**
  1. Stop the default provider from being test data (`fixtures` stays requirable only from `test/`).
  2. Make `seon.main` the release `-main` (root, mcp, ports), and point `bin/build` at it.
  3. Move check, e2e, fixtures and the check-only API to `test/`.
  4. Turn the checks into deftests (row 14).
- **Areas:** prove (check selection, verdicts), dry-run (e2e, the scripted model), dup-root (the `root.clj` API tail). Run it after dup-root lands.
- **Proofs:** `bin/check` still ends on OK/FAILED for every mode, and the release binary builds from `seon.main` and serves root, mcp and ports.

### Brief 2: root and reconciler, Flow-native (rows 8, 9, 11, 12, 18, 28, 29, 31, 33 and the `stderr-tail`/`pull-end` items) ≈ −110
- **Write set:** `root.clj` (the proc, `:120-740`), `reconcile.clj`, `agent_proc.clj`, `writer.clj`, `step.clj` (read), `system.edn`, `seon.facts.edn` (one index flag).
- **Order:** start after dup-root lands, since it extends B1/B6. Do 11, 12, 29, 18 and 33 first (low risk). Do 8 and 9 next, each behind a measured cost proof (`flow/update!` per burst; AVET versus the hand index). Do 28 last.
- **Area:** dup-root.
- **Proofs:** the reconcile cost proofs stay O(changed), and `bin/check` threads shows one fewer declared thread.

### Brief 3: program facts and reach, one owner (rows 5, 6, 10, 16, 35, and agent rows 19, 34) ≈ −130
- **Write set:** `program.clj`, `merge.clj`, `source.clj` (`:108-109` only), `agent.clj`, `task.clj` (`accumulate` only), `context.clj` (`callees`), `learn.clj`. Row 16's Jolt half goes on `jolt/<topic>`.
- **Areas:** prove (row 6 changes which tests an agent runs: hand it the selection rule, or land it with them); store-native has landed (`338ad4b`), so program.clj is free.
- **Proofs:** a defmethod-only change selects its tests at the agent (red today); the merge tests stay green; a cost proof for reach at the agent.

### Brief 4: REPL, tasks and references (rows 7, 13, 15, 17, 20, 21, 30, 32, and the reader, `heading-text` and `top-fns` items) ≈ −140
- **Write set:** `repl.clj`, `intr.clj`, `task.clj` (all but `accumulate`), `ref.clj`, `docs.clj`, `cost.clj` (`once`), `profile.clj`, `source.clj` (the readers), `default.edn` (one key).
- **Areas:** none in flight. Row 30 changes the stored item keys that `render/ui` reads, so coordinate with renders, or do this brief before brief 5.
- **Order:** row 7 first, after proving that a SIGUSR1 landing while idle still clears; row 17 after it.
- **Proofs:** the repl and task tests; one section address gives the same text through `ref` and `docs`.

### Brief 5: the printer and the views (rows 22–27, 36–38, and audit C6/A13 if still open) ≈ −50 safe, −90 behind owner calls
- **Write set:** `print.clj`, `print/select.clj`, `print/emit.clj`, `render/*`, `web.clj`.
- **Area:** renders. Run it after the renders session lands, or hand it these rows.
- **Order:** do 22–27 now. Rows 36, 37 and 38 each need the owner's ruling first. For 37: are `:cuts` data agents read, or test-only?
- **Proofs:** the print tests, with fipp still the oracle; render and web tests; `bin/check mcp idle`.

## Not measured, and left alone

- Every count above is read, not measured.
- No v1 namespace was shorter for the same capability.
- Malli: `render.clj:23-60`'s hand walk of schema forms is not replaced by `mu/keys` or `m/explicit-keys`, which do not resolve `[:and kw …]` parents with inherited properties (~5 lines at best).
- `task/entity-tx` stays: Datahike's `:db.fn/retractAttribute` retracts and re-adds unchanged values.
