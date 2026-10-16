---
title: Printer core hybrid plan, narrow check
date: 2026-10-16
lane: printer-check
type: check
status: needs correction (nine corrections, none structural)
---

# Narrow check of the printer hybrid plan ([hybrid.md](hybrid.md))

Lane printer-check (Sonnet), 2026-10-16, HEAD `4297a615d` plus the working tree. Probes were read-only JVM forms and
one disposable SCI agent (`printer-check`, no definitions); code claims were re-read at HEAD.

**Verdict: needs correction.** The design holds and every phase names a proof that resolves, but one live claim is
contradicted (the invocation cache key), P1f's deletion set has unlisted callers, P1d deletes a function whose
converting caller lands in P1e, and two proofs are not yet a named test.

## 1. Live-system claims

| Claim | Result | Evidence |
|---|---|---|
| Two-key partial error map selects `seon.db/render-rejection-ai`, replaced by a huge diagnostic | REPRODUCED | SCI form `{:seon.error/type :seon.db.write/validation-refusal :seon.error/message "partial"}` returned the value plus a `:seon.render/unknown` diagnostic: producer `seon.db/render-rejection-ai`, `:reason :refused`, six problem paths, a 28-frame chain; the stack runs `value/make-node` -> `value/declared` -> `render/declared-projection` -> `render/invoke-selected`. The diagnostic is several thousand tokens (not counted exactly). |
| `seon.render/invocation` cache key is content-only, nothing per agent | CONTRADICTED | `seon.render/invocation-cache-key` is `[(branch-scope ctx) selected output (hash program-evidence) (hash projection-evidence) (hash selection-input)]`. `branch-scope` is the Datahike `:datahike.cache/connection-id` and `:datahike.cache/generation` of the ctx's connection. So it is per branch and generation, not content-only (CLAUDE.md "never key by context"). Two chains on two branches do not share a retained render output. Not per agent id, but a chain agent has its own branch. |
| Malli caches the compiled validator per schema | REPRODUCED | `malli.core/validator` is `(-cached (schema ?schema options) :validator -validator)` (`reference-code/malli/src/malli/core.cljc` 2672-2678; `-cached` at 353). |
| `seon.render/render-argument` fixed key list near render.clj 188 | REPRODUCED, count off | The list is at 188-215 and holds 17 keys, not 18 (it includes `:seon.render/rendering` as the 17th). `malli.core/explicit-keys` exists (core.cljc 2846). |
| `…+774` / `…+976` docstring cut at 1,024 / 512 tokens | NOT CHECKED | Needs the function entity as a unit under the agent profile; I could not reach it cheaply from SCI (`:seon.fn/symbol` is not unique; entity lookup API unknown). A synthetic 1,050-char doc in a 4,800-token map printed whole at the live agent budget, so it neither confirms nor refutes. Opus's table (opus.md lines 101-102) is the only evidence. |
| 4,956-tuple query: 28.1 ms / 63 MB vs 2.5 ms / 2.7 MB; 80 `[…2]` | NOT CHECKED | Only in opus.md (lines 107, 117); the prototype is not committed. Parent number and the "80 placeholders" were not re-run. |
| 40-evaluation transcript probe exists and runs on the parent | CONTRADICTED as stated | `a028e7231` committed only the 314-line sketch patch and findings text; no 40-evaluation harness, test or script is in the tree (grep of `test`, `script`, `docs`). The 43 vs 16 ms figures are prose. P1c's cost proof has no runnable parent probe today. |
| `seon.print-census-test/no-value-is-printed-outside-the-value-printer` exists and is red | REPRODUCED | Scanning the same files with its `printing?` rule gives 84 sites, 83 outside the one `canonical-edn` entry (plan: 85). 113 files, 0 unreadable. |
| `seon.source-census-test/no-timeout-or-limit-is-a-hardcoded-number` exists and is red | REPRODUCED | 42 sites over 115 files (plan's R4 count: 36); only 5 are in `render`/`print` files. |
| `bin/lane status`: `src/seon/sci/eval.clj` held | REPRODUCED | `fix-sci-eval (since 4a8a83c11): 5 paths`; `git status` shows `src/seon/sci/eval.clj` modified. The four one-render files are clean. |
| R12 text at CLAUDE.md/AGENTS.md line 582 | REPRODUCED | Both files, line 582: "Render output goes straight to the value printer, which never re-dispatches." |
| Existing tests the phases keep (value-law, repl, context, print, sent, value tests) | REPRODUCED | Every named existing deftest resolves (`rg "deftest NAME" test`); `history-preserves-shown-text-without-applying-a-later-profile` is in `test/seon/repl_test.clj`, not a context test. |
| Census counts of R1/R2 (about 65), R3 (about 150), R5 (22) | NOT CHECKED | They are inventory numbers; R3 over `src` alone is 83 at HEAD, the rest needs the P0 rules. |

## 2. Deletions against their callers (grep over src, test, script, resources at HEAD)

Callers of `seon.render.value` names outside `value.clj`, by file count:

| Deleted name | Callers outside the owner | Converted by the plan? |
|---|---|---|
| `prepare` | src: error, repl, sci/eval, render/web, render/walk; test: error_result, value_zoo, value_law, value, my/program; resources: seon.fn tasks, seon.render.value tasks | P1a sweep: yes, if the sweep includes tests and task resources. |
| `text`, `html` | src: repl, health, render/web, cluster/status, error, render/walk, render/schema, render/sent, render/var; test: floor, value_law, value, web, start_flow_benchmark; `resources/seon/schemas/seon.render.value.edn`; `test/seon/schema/datahike_parity.edn` | **GAP.** P1a makes them delegate, but no phase P1b to P1e converts `health.clj`, `cluster/status.clj`, `render/schema.clj`, `render/var.clj`, `render/sent.clj`, `error.clj`. P1f lists "the 22 `core-text` files" and edge sites; the `text`/`html` callers are not named, and P2-faces/web run after P1f deletes the names, so HEAD would not load. |
| `render-ai`, `render-html` | src: problems, repl, ai, render, test/runner, render/transcript, render/web; many tests; two schema lines | **GAP.** `src/seon/problems.clj` and `src/seon/test/runner.clj` are in no phase's file list. |
| `core-text` | 15 src files (mcp, context, error, repl, problems, test, turn, fault, cluster, cluster/boot, test/accretion, cluster/store, sci/eval, render/web, my/background), 6 script files, about 16 test files, 3 task resources | P1f says "the 22 `core-text` files" (15 src + 6 script + 1 is plausible); **the test files are not named** (floor_test, value_test, print_test, test_support_test and others). |
| `core-html` | test/seon/render/floor_test only (2) | covered only if `floor_test.clj` is converted or deleted; it is in no file list. |
| `bounded-text`, `throwable-text`, `plain-projection`, `unavailable`, `text-limits` (private or defs, reached by `#'`) | tests: floor_test (`bounded-text`, `throwable-text`, `plain-projection`), branch_execution_test:489, sci/admit_test:162, value_test:1117 (`unavailable`), test_support_test:191 and web_test:2257 and error_test:1942 (`text-limits`); src: `error.clj:2510` (`:frames` of `text-limits`), sci/admit docstring | **GAP.** P1f names `error.clj` but none of these seven test files; each fails to compile or resolve when the Var goes. |
| `artifact`, `artifact-edn`, `read-artifact`, `artifact-value` (plan: 21 outside callers) | src: test/runner, render/walk; test: db, cluster/mcp, web, test_support, value; resources: seon.sci.eval and seon.sci.reader task text | **GAP.** P1f moves them to `seon.sci.admit` but lists neither `test/runner.clj` nor `render/walk.clj` nor the tests; they must convert in that commit. |
| `render-database-identity-ai` | value.clj (own), `resources/seon/schemas/seon.db.edn`, `datahike_parity.edn` | P1c moves it to `seon.db`; the schema resource and parity file must change in the same commit (not stated). |
| `floor-text` (P1d) | Two different private functions: `seon.sci.eval/floor-text` (4525) and `seon.render.transcript/floor-text` (339, three uses) | **ORDER GAP.** P1d deletes "`floor-text`" with `seon.sci.eval` in its files; the transcript copy is converted in P1e, so P1d either leaves it (then say so) or breaks the transcript page. Name both and put each in the phase that owns its file. |
| `reply-map`, `entries-in-order`, `ends-in-comment?`, `shown-output` | `src/seon/repl.clj` only, plus `test/seon/sci/forgive_test.clj` and `forgive_replies.edn` (20 + 7 references), `repl_test`, task resources | **GAP.** The forgive replies data and test are not in P1d's files. |
| `evaluated-source` and `source-output?` (P1d) | `evaluated-source` is private in `seon.sci.eval` (4554) with its own test `test/seon/sci/render_source_test.clj`; `source-output?` is set in render.clj (1388) and web.clj (1375) and read in render.clj (608, 929, 1602-1657) and value.clj (1574) | Producers 2 (render.clj 1388, web.clj 1375) as the plan says; `render_source_test.clj` and the schema member in `seon.render.value.edn` line 44 need conversion in P1d; not listed. P1d's list also omits `seon.render.value` (1574). |
| `exchange-ai`, `exchange-text` (P1e) | `context.clj`, `render/transcript.clj`, `render/sent_test`, `context_test`, **`resources/seon/schemas/seon.context.edn` (3), `seon.config.context.edn` (1)** | P1e lists src files only; the two schema resources are not listed. |
| `select-nodes`, `scalar-bound`, `chunk-width`, `name-bound`, `nest-cap`, `sorted-bound`, `tuple-bound`, `print-option-keys`, `walk-with-related`, `beside-share` | value.clj only (`select-nodes` also in task text) | Clean: no outside callers. |
| `related-html` | render/walk.clj only | Clean (R10, P1c). |
| `seon.cluster.agent/whoami` (R11) | `src/seon/cluster/agent.clj`, `test/seon/cluster/agent_identity_test.clj` (7) | P2-faces: the test is not named. |
| `seon.error/render-chain-ai` `:frames` | `error.clj`, `error_test.clj`, `resources/seon/schemas/seon.error.edn` | P1f names error.clj; the schema resource and the test are not named. |

Net: the plan lists source files but not the tests, `script/` and schema resources that reference the deleted names; since
every phase commits through `bin/lane commit ... --test`, each of those would refuse the commit. P1a's delegate-then-delete
shape is right; it needs a per-name caller list for P1f.

## 3. Phase proofs

| Phase | Proof | Resolves? |
|---|---|---|
| P0 | Three census tests with rules R1 to R5 and one mutation fixture each | The two census tests resolve (red at HEAD, numbers above). `render_census_test.clj` is new with clear rules. **R4 is vague:** the existing census detects a constant or `or`-default naming a policy number, which already scans render and value; the plan's R4 (a literal count in `subs`, `take`, `take-last`, `drop-last`) is not a pattern that rule detects, and says "no new analyzer". State the form it matches. |
| P1a | `show-answers-tree-text-and-cuts` (new), `a-render-receives-request-options` (new), extend `ai-values-are-laid-out-by-fipp-at-their-width` | Resolves. "Not slower on all three" depends on the three probes; the function-entity probe needs `seon.sci.eval/shown-result` and that file is held (patch), so the probe cannot run in this phase unless the held lane commits first. |
| P1b | rows on cut data (docstring whole at 1,024 and 512; one cut at `[]` for the 4,956 query; 24-attribute error; key-only budget; 2,000-line `:out`); extends four existing tests | Resolves and asserts data. The query allocation bound "at most a quarter of the parent's" needs a parent figure recorded in the test; the plan gives 63 MB as prose only. |
| P1c | `nested-values-select-their-pairs`, `selection-requires-contract-fit`, `held-evaluation-selects-by-declared-schema`, `a-derived-face-cut-reads-the-derived-value` | Resolves, data-asserted. **The cost proof (40-evaluation transcript through `show` at most the direct path) has no committed harness, and after P1d "the direct path" is being deleted**; name the parent probe as a committed script or test. The retained-output reuse is per branch (section 1). |
| P1d | `a-reply-is-one-map-under-one-budget`, `chat-messages-take-form-and-reply-parts`, `a-rendered-form-is-never-evaluated` | Resolves. "`show` calls per reply = 1" is a call count, which CLAUDE.md allows only as supporting evidence; the reply-time cost still needs an end-to-end `measure`. |
| P1e | `an-older-unit-over-its-budget-collapses-to-its-reference`, `history-is-one-derivation` | Resolves. The "context build for a 40-unit history not slower than parent" probe is again not committed. |
| P1f | `a-hostile-throwable-prints-as-class-and-identity`, `a-printer-failure-prints-structurally-once`, two extended tests, R3 green | Resolves. Note the existing floor tests cover the retired functions and are deleted or converted in this commit. |
| P2 | R1 to R5 per group plus named existing tests | Resolves as a static check; per-group "each page or report not slower" has no measured test named. |

None of the proofs is wall-clock only. All cost proofs depend on the three-probe parent comparison, which exists only
as prose; it should be a committed `test/` or `script/` measurement.

## Corrections

1. Replace "the invocation key is content-only": it contains the branch scope. Either accept per-branch retention for
   cut handles (state it) or drop `branch-scope` from the key in the same slice, as CLAUDE.md requires.
2. Commit the 40-evaluation transcript probe and the three-probe parent baseline before P1a, so every phase's cost
   proof has a runnable parent. Today the one-render harness is not in the tree.
3. P1f: list every caller of `text`, `html`, `render-ai`, `render-html`, `core-text`, `core-html`, `bounded-text`,
   `throwable-text`, `plain-projection`, `unavailable`, `text-limits` and the four `artifact*` names, including
   `health.clj`, `cluster/status.clj`, `render/schema.clj`, `render/var.clj`, `render/sent.clj`, `problems.clj`,
   `test/runner.clj`, `render/walk.clj`, the six scripts and the seven test files above; or move the `text`/`html`
   callers into P1b to P1e so P1f deletes only names with no caller.
4. P1d: name both `floor-text` functions and convert the transcript one in P1d, or defer deleting it to P1e. Add
   `forgive_test.clj`, `forgive_replies.edn`, `render_source_test.clj`, `seon.render.value.edn` (line 44 member) and
   `value.clj` (1574) to P1d's files.
5. P1e: add `resources/seon/schemas/seon.context.edn` and `seon.config.context.edn` (they cite `exchange-ai`).
6. P1c: say that `seon.db.edn` and `datahike_parity.edn` change with the move of `render-database-identity-ai`.
7. P0: define R4's matched forms (the existing census does not match `subs`/`take` literals); fix the counts (render-argument
   has 17 keys; R3 on `src` is 83 unlisted forms; the hardcoded-number census has 42 sites).
8. P1a: `src/seon/sci/eval.clj` is held by `fix-sci-eval`; the function-entity probe and the `shown-result` change need that
   lane's commit first, so order P1a after it or drop that probe from P1a.
9. Re-run the `…+774` and 4,956-tuple measurements on default before filing P1b (not reproduced here); the numbers are
   the red rows' expected values.

No new root cause was confirmed beyond what the inventory already holds; the cache-key finding (correction 1) is a plan
defect, not a code defect, and is recorded here.
