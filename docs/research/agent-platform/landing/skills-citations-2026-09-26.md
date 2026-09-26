---
type: landing
lane: skills-citations-opus
created: 2026-09-26
status: "594 skill citations checked; stale ones re-pointed to HEAD 39ee6f3f3; 13 behaviour claims corrected or deleted; hook green on all 22 skill files"
---

# Landing: skills-citations-opus (2026-09-26)

Docs only. Scope: every `file:line` citation in `.agents/skills/**` (22 Markdown
files, 594 citations). The base for verification was `373bfaf50`; files kept
changing under the lane (8b4a10a44, f83931226, faedc30f1), so a second pass
followed those commits to HEAD `39ee6f3f3`. The citation hook passes on every
skill file at `85bc65589` (`citations: 22 documents, 0 failures`).

## Broken first

- **The hook cannot see a stale citation that has no name.**
  `script/seon/dev/citations.clj` fails a citation only when its path is missing,
  its file is too short, or a named Var or text is not on the cited lines. A
  citation with no adjacent name passes whenever the file is long enough. That is
  how `config/default.edn:392-396` in llm-providers drifted onto render settings
  and still passed. This lane used a different check: take the cited lines' text
  at the commit that last touched the skill line (`git blame`), then find that
  text at HEAD. A block found elsewhere was re-pointed. A block not found was
  read by hand. The check is not committed and the hook is not changed, because
  `script/**` is outside this lane.
- **Citations into files under active edit keep drifting.** turn.clj, sci/eval.clj
  and instrument.clj moved twice during this lane. Any lane that edits them
  shifts skill citations again.

## Claims that were false about behaviour (for root)

These go beyond line numbers. Each was corrected to what the cited code does, or
deleted.

1. **repl, "A lane works on its own branch."** Said: a `mode sci` + `branch`
   evaluation acquires a context through `acquire-context!` and keeps it under
   `[branch nil]`; "MCP runs no turn"; "a `defn` writes no `:seon.fn/*` row".
   Now: `run-form` (`script/seon/dev/mcp.clj:395-438`) runs the form as an
   ordinary agent run of the hosted agent `mcp/<branch>`
   (`seon.cluster.host/host!` → `seon.cluster.agent/evaluate-source!`). The
   no-turn and no-row sentences are deleted. The "no installed operation arms
   that worker … it is the gap" paragraph is also deleted, because
   `seon.cluster.host` now arms a branch agent inside the JVM.
2. **repl.** Said: a host-bound override "does not refuse; the named refusal
   exists only on the source-adoption path". Now: the override refuses by name
   when called (`src/seon/sci/eval.clj:1722-1737`). Re-pointed.
3. **repl.** Said: "compiled callers still call the compiled Var (B2 §2a
   [TARGET])". Contradicted by the `base-ctx` docstring, which says overrides
   and affected callers interpret their admitted source. Deleted.
4. **seon-flow-architecture / wakes-and-faults, "Fault fan-out" and "The config
   dial".** Described machinery that no longer exists:
   - `start-error-fanout!`, `CountedDroppingBuffer`, `fault-committer-step`, the
     capacity-64 fault channel, `emit-core-fault!`, and the `:record` fallback
     mode reader;
   - `:panic` shown as [TARGET].

   Now every producer calls `seon.fault/fault!` on its own thread
   (`src/seon/fault.clj:1-7`). `proc-failed!` and `fault-port` replace Flow's
   error channel. `seon.fault/policy` panics unless record mode committed, and
   under `:panic`, `fault!` throws. Both sections were rewritten to those facts.
   Checklist item 6 ("through the fan-out") was corrected.
5. **wakes-and-faults, "Never throw".** Said: catch at the router boundary and
   send the failure to the fault channel, and Datahike only logs. Now the router
   throws a refused delivery into Datahike. The fork's `notify-listeners!`
   (`writer.cljc:394-415`) retires the listener and calls the connection's
   `:listener-failure` handler (`seon.cluster.store/listen-failures!`).
   Rewritten, and checklist item 2 ("catch everything at the listener") was
   deleted.
6. **wakes-and-faults.** Said: the router queries only on declaration-change
   reports, and its catch `offer!`s a bare Throwable. Now it derives matchers
   with a Datalog query on every report (`wake.clj:506-507`), and it has no
   catch. `route!` is registered by the armer proc (`agent.clj:1527`), not by
   `arm-agents!`, and the error fan-out join no longer exists in the arm order.
7. **decisions.md.** "Core faults belong in one fan-out and committer" became
   the one fault route (`seon.fault/fault!`). The "counted-dropping fault
   observation" example was deleted.
8. **workloads-and-scheduling.** Said: agent and cluster graphs run on the
   handle's projection executor; the work-launcher graph falls back to
   core.async's default `:io`; the root `:compute` pool runs the capacity
   observer. Now `start-graph!` gives every graph its own `graph-executor` as
   `:io-exec` (`src/seon/flow.clj:80-125`), and no proc uses root `:compute`.
   The table was rewritten, and the fallback and capacity-observer paragraphs
   were deleted.
9. **degraded-start.** `seon.cluster/read-advertisement` no longer exists.
   Deleted.
10. **datahike.** Said `transact-call` installs the final-report validator. The
    installer is now `prepared-write` (`db.clj:4639-4648`), and the throwable is
    read back through `write-refusal`. The fork-maintenance evidence test
    `file-store-executes-ordered-multi-key-operations` was deleted in
    `20d317e19` (filestore back to per-key writes), so its citation is gone.
11. **datastar-web-ui.** The eval render pair is declared in
    `seon.cluster.eval.edn:30`, not `seon.eval.edn`. `ordered-episode` no
    longer exists in `seon.render.walk`, so its mention was deleted.
12. **clojurescript.** The pinned ClojureScript reference is 1.11.132, which has
    no `await` macro or async emission. The core, analyzer and compiler async
    citations were deleted; the `js.cljs` self-host pointer stays.
13. **seon-flow-architecture SKILL.** Said the law caches contexts "by commit
    id". Current AGENTS.md says "reusing content-valid derivations", so that
    clause was deleted.

`config/default.edn` still exists at HEAD. Its 13.1 retirement is a design
only, so the llm-providers citations point at HEAD's lines: model at `:407-411`,
max-tokens at `:418`, thinking disabled at `:440`, provider rows at `:558-583`,
model rows at `:585-666`.

## Per skill

"Changed" counts citation spans whose text changed; "deleted" is the drop in
citation count.

| skill file | citations (before → after) | re-pointed | deleted |
|---|---|---|---|
| llm-providers | 31 → 31 | 30 | 0 |
| repl | 35 → 34 | 18 | 1 (plus 3 false claims) |
| data-oriented-clojure | 19 → 19 | 18 | 0 |
| clojure-testing | 39 → 39 | 33 | 0 |
| datahike SKILL | 142 → 142 | 52 | 0 |
| datahike fork-maintenance | 63 → 62 | 21 | 1 |
| datahike querying | 10 → 10 | 4 | 0 |
| seon-flow-architecture SKILL | 24 → 23 | 15 | 1 |
| flow decisions | 34 → 33 | 30 | 1 |
| flow degraded-start | 23 → 22 | 14 | 1 |
| flow wakes-and-faults | 43 → 38 | ~30 | ~13 (two sections rewritten) |
| flow workloads-and-scheduling | 22 → 19 | 7 | 3 |
| seon-context-config | 13 → 13 | 12 | 0 |
| data-modeling | 35 → 35 | 20 | 0 |
| datastar-web-ui SKILL + design-principles | 41 → 41 | 25 | 0 (one claim) |
| codex-lanes | 11 → 11 | 4 | 0 |
| ui-canvas | 4 → 4 | 2 | 0 |
| clojurescript | 3 → 1 | 2 | 2 |

## Commits

`4b69b4437` `8ae93fd30` (llm-providers), `792ff30c9` (repl), `a5e5415c8`
(data-oriented-clojure), `c6aecede9` (clojure-testing), `dc1b7ed3b` (datahike),
`edfb05a0e` (seon-flow-architecture), `5d5b8d98a` (seon-context-config),
`6a546d53e` (data-modeling), `4f6f15f8c` (datastar-web-ui), `1bc87730a`
(codex-lanes), `11091dda4` (ui-canvas), `85bc65589` (clojurescript).

Released paths: `.agents/skills/**`.

## Limits

- No JVM probe was run. Behaviour claims were checked by reading HEAD source,
  not by running it.
- Single-line citations inside a long function were accepted when the anchored
  text still matched. A single-line anchor that was already wrong when it was
  written can still be wrong. One such case was caught by hand: repl's
  `cluster.clj:2714` pointed at a `finally` line.
- Citations with no path, and prose claims with no citation, were not swept.
