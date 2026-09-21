---
type: research
status: draft
created: 2026-09-21
tags: [instructions, process, agents-md, skills, docs, orchestration, audit]
---

# Instructions and process machinery — audit, 2026-09-21

Scope: what agents are TOLD (`AGENTS.md`, skills, memory) and the tooling that
exists to RUN agents rather than to run the product (lane launchers, gate
shells, hooks, orchestrator manual). Read-only pass: no JVM, no `bin/test`, no
`bin/seon`. Line numbers are `AGENTS.md` unless another file is named.

Owner framing (2026-09-21): "the codebase has become a bloated mess just to
achieve all tests passing … if the previous agents were forecasting several more
weeks of grinding out bad code I want to remove those instructions as they are
poisoning the project."

## 0. Measured baseline

| Artifact | Measure |
|---|---|
| `AGENTS.md` | 1,321 lines / 98,825 bytes; `CLAUDE.md` is a symlink to it (every agent loads all of it) |
| `.agents/skills/*/SKILL.md` | 12 skills, 1,656 lines; `.claude/skills` is ONE symlink to `.agents/skills` (no duplication) |
| Shell process machinery | `bin/test` 1,107 · `bin/seon-hook` 1,987 · `bin/codex-agent` 573 · `bin/_test-slot` 165 · `bin/test-fast` 48 · `bin/issues-index` 4 (→ `script/seon/dev/issues.clj` 41) |
| Orchestration docs | `docs/TRANSFER_PROMPT.md` 391 · `driving-codex-agents.md` 104 · `codex-cli-hooks-2026-09-17.md` 324 |
| `docs/` | 4,018 `.md` files, **868,142 lines**, 151 MB |
| `docs/seon/issues` | 492 live notes / 31,567 lines (+1,399 archived); **386 created in the last 7 days** (~78% of the live tree is one week old) |
| Memory index | `MEMORY.md` 142 lines / 25,399 bytes — **already over its 24.4 KB load limit; line 142 is truncated at load** |
| Disposable exhaust | `tmp/` 7.9 GB; `tmp/orchestrator` 1.2 GB of lane stdout across 86 lane dirs; `tmp/head-wt` worktree left behind against rule 12 |

---

## 1. `AGENTS.md` — classification

Every line assigned to exactly one class. Totals sum to 1,321 (15 lines are
blank separators between classified blocks).

| Class | Lines | % | Meaning |
|---|---:|---:|---|
| **(a) product law / design invariant** | **827** | 62.6% | keep — an engineer needs it |
| **(b) vocabulary table rows** | **76** | 5.8% | 74 rows + header/separator |
| **(c) multi-lane process choreography** | **351** | 26.6% | slots, lane counts, effort levels, gate cadence, protected paths, codex mechanics |
| **(d) incident history / dated anecdote** | **44** | 3.3% | "on 2026-09-17 a lane…" |
| **(e) forecasts or normalizes slowness** | **3** | 0.2% | (line-scored; 7 sentences — §1.3) |
| **(f) self-contradiction / contradicts SECONDS-NOT-MINUTES** | **5** | 0.4% | (line-scored; 4 sentences — §1.3) |
| separators | 15 | 1.1% | |

### 1.1 Section by section

| Lines | Section | a | b | c | d | e | f | Verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| 1–13 | frontmatter + authority statement | 13 | | | | | | keep |
| 15–113 | **lane instructions block** | 18 | | 76 | 4 | | | 77% process — cut to the 5 laws |
| 115–196 | How we work here | 82 | | | | | | **keep near-verbatim — the best part of the file** |
| 197–322 | §1 What Seon is and how it runs | 126 | | | | | | keep as law; move mechanism detail to `architecture/` |
| 323–495 | §2 The five design laws | 173 | | | | | | **keep — the core** |
| 496–665 | §3 Data and schema | 170 | | | | | | keep the rulings; the Datahike mechanics belong to the guide (839 lines) + skill (486) |
| 666–710 | vocabulary law prose | 45 | | | | | | keep, compress |
| 711–786 | **vocabulary TABLE** | | 76 | | | | | see §1.2 |
| 788–865 | §4 REPL-driven development | 78 | | | | | | **keep near-verbatim** |
| 866–952 | §5 test fixtures | 60 | | 10 | 12 | | 5 | keep the 9 rules; the prose tail is drifted mechanics |
| 953–1077 | §5 **the gate** | 8 | | 113 | | 2 | | **90% process — delete; 1052–1059 survives** |
| 1078–1164 | §6 Operating | 19 | | 65 | | | | shrink to the `bin/seon` verb list + "churn is weather" |
| 1165–1307 | §7 Collaborating | 21 | | 87 | 28 | 1 | | **80% process/anecdote — delete with the lanes** |
| 1308–1321 | §8 Pointers | 14 | | | | | | keep, rewrite targets |

The two biggest process blocks — the gate (953–1077) and collaborating
(1165–1307) — are 268 lines, 20% of everything every agent loads, and neither
teaches anything about the product.

### 1.2 Vocabulary table (711–786)

| Measure | Count |
|---|---:|
| Rows | 74 |
| Rows marked **[TARGET]** (ruled but not proven built) | **9** — L717, 725, 726, 739, 762, 768, 777, 778, 780 |
| Rows citing a first-party `src/…:line` | 25 |
| Rows citing `reference-code/` | 13 |
| Rows with **no** code citation at all | 25 |

The table's own preamble (L697) says: *"Rows marked **[TARGET]** describe a ruled
integration not yet proven complete"*. Nine rows therefore define vocabulary for
things that do not exist — a dictionary of intentions. **Settled rows to keep:
65.** The 9 [TARGET] rows move to the plan document that owns each target; the
25 uncited rows must each earn a citation or be deleted, by the file's own law
(L853–855: "an unverifiable claim is DELETED, never hedged").

### 1.3 (e) and (f) — verbatim

**(e) — forecasts or normalizes slowness. 7 sentences.**

| # | Line | Verbatim |
|---|---|---|
| E1 | 101–102 | "every edit otherwise queues a complete publication and adoption of `default` (**60–150 s** under the lifecycle lock), and each one is refused" |
| E2 | 91–92 | "On 2026-09-20 a retired predicate with 74 inventoried callers left HEAD uncompilable and **blocked every gate and lane for two hours**." |
| E3 | 1049–1050 | "Shell integration remains the **post-reset Stage 1 work**; this paragraph does not claim it has migrated." |
| E4 | 1263 | "**Until hook-side admission is installed**, instructions bound their gate use." |
| E5 | 1142–1143 | "the [TARGET] root maintenance portfolio is the machinery that **eventually** owns this automatically." |
| E6 | 1255 | "Lanes report their fast tally and **the cold proof still owed**." |
| E7 | 697 | "Rows marked **[TARGET]** describe a ruled integration **not yet proven complete**" |

E3, E4, E6 and E7 are the poison the owner named: they install a standing
expectation that work is half-landed, that a stage is owed, and that an
instruction is a placeholder for machinery someone will build later.

**(f) — contradictions. 4 sentences.**

| # | Lines | The contradiction |
|---|---|---|
| F1 | 929–931 vs 416–417 | §2.3 law: "**A bound firing is itself a bug report naming what never arrived — never a silent retry.**" §5 then instructs: "Its execution waits the declared `event-backstop-seconds`, which the shared fixture base's post-adoption construction **consumes whole**, so hand `:seon.test/remaining-ms` explicitly after an adoption and **never read that first bound failure as a red**." The file teaches agents to ignore the exact signal its own law declares load-bearing. |
| F2 | 101–102 vs 134–135 | "**SECONDS, NOT MINUTES** … Anything that takes longer than TEN SECONDS requires the owner's explicit authorization" vs a publication documented, without authorization, at **60–150 s** — and worked around by disabling publication rather than fixing it (`.claude/seon-hook.edn` `:current-source {:enabled false}`). |
| F3 | 48–49 vs 81 | Lane rule 4: "baseline in a throwaway worktree (`git worktree add tmp/<lane>-wt HEAD` + link `reference-code`)." Lane rule 12, 32 lines later: "**Lanes never create worktrees.** … never work around it with `git worktree add`." |
| F4 | 861 vs (d)=44 lines | "the dated ledger records rulings — **dates, ruling numbers, and incident history live THERE**" — while `AGENTS.md` itself carries 44 lines of incident narrative (L79–80, 91–92, 897–901, 917–923, 1217–1245, 1276–1279). |

### 1.4 Proposed table of contents — rewritten `AGENTS.md`, ≤250 lines

**Honest constraint first: (a) is 827 lines and (b) 76. A 250-line file cannot
hold every (a) sentence verbatim.** It can hold every (a) *law* if the (a)
*mechanism description* moves to the documents `AGENTS.md` already names as
authorities — which are already written: `architecture/` (1,762 lines),
`data-modeling-guide.md` (839), and the 9 product skills (1,554). The rewrite is
therefore a re-homing, not a deletion: nothing in (a) is lost, and the
always-loaded surface drops 81%.

| § | Title | Lines | Source |
|---|---|---:|---|
| — | frontmatter + "the one maintained instruction authority" | 8 | **L9–13 verbatim** |
| 1 | How we work here | 55 | **L117–195 verbatim, whole block** — quarry, ask-the-dependency, SECONDS-NOT-MINUTES, dissolution, derive-don't-remember, no-pre-read, absence-of-signal, write-it-down |
| 2 | What Seon is — boot, running, cluster, transport, crash | 30 | condensed from L197–322; mechanism detail → `architecture/architecture.md` + `agent-runtime.md` |
| 3 | The five design laws | 55 | L329–495 compressed ~3:1; each law's statement verbatim, evidence links replacing the worked examples |
| 4 | Data and schema — the rulings | 30 | L498–530 (symbols, no kinds), L546–612 (retraction, value-vs-ref, empty-set, components) as one-sentence rulings; **all mechanics → `data-modeling-guide.md` + `datahike` skill** |
| 5 | Vocabulary | 30 | 65 settled rows, three columns kept; **9 [TARGET] rows → their plan docs** |
| 6 | REPL-driven development | 25 | **L790–848 verbatim** (the 7-step loop, dependency ledger, where work lives) |
| 7 | Testing — fixtures and what a proof is | 20 | **L870–908 verbatim** (the 9 fixture rules, anecdotes stripped) + **L1052–1059 verbatim** ("Tests find design issues; structure dissolves them") |
| 8 | Operating | 12 | `bin/seon` verb block **L1082–1098**, **L1145–1151 verbatim** ("Churn is weather"), **L1159–1163 verbatim** ("No hobbling") |
| 9 | Working and reporting | 12 | **L1210–1212 verbatim** (VERIFY THE CLAIM), **L1289–1299** issues loop, **L1301–1306 verbatim** (reporting) |
| 10 | Pointers | 8 | rewritten targets |
| | **Total** | **~245** | |

**Surviving verbatim, by current line number:** 9–13, 21–26, 39–44, 55–56,
87–90, 117–195, 329 (law titles), 392–398, 416–423, 498–530, 546–612, 790–848,
870–908, 1052–1059, 1082–1098, 1145–1151, 1159–1163, 1210–1212, 1301–1306.

**Deleted outright:** the lane block's process items (27–38, 45–54, 57–86,
93–113), the gate section (953–1051, 1061–1077), §6's hook/slot/sweep prose
(1100–1143), and §7 minus three paragraphs (1167–1209, 1214–1287). **≈580 lines,
none of which teaches an engineer anything about Seon.**

---

## 2. Skills

`.claude/skills` is a single symlink to `.agents/skills` (same inode; `diff -rq`
identical). One source of truth — nothing to deduplicate.

| Skill | Lines | Teaches | Class | Verdict |
|---|---:|---|---|---|
| datahike | 486 | Datahike as the one database owner: retraction reach, pull grammars, write admission, temporal values | PRODUCT | **keep** — ~88% product invariants, 1 line of incident, 0% process |
| data-modeling | 204 | attributes, identities, refs, components in the canonical population | PRODUCT | keep |
| clojure-testing | 167 | canonical fixture, armed contracts, bounded waits | PRODUCT | keep |
| repl | 110 | reply reader vs agent SCI ctx vs MCP JVM eval vs raw REPL | PRODUCT | keep |
| seon-flow-architecture | 110 | procs, graph lifecycle, workloads, bounded execution | PRODUCT | keep |
| **codex-lanes** | **102** | launching/resuming Codex lanes, model and effort, gate/load rules | **PROCESS** | **RETIRE with the lanes** |
| data-oriented-clojure | 99 | explicit data, declared schemas, database authority | PRODUCT | keep |
| datastar-web-ui | 96 | namespace pages, blocks, routes, SSE | PRODUCT | keep |
| llm-providers | 92 | provider requests, caching, streaming, usage | PRODUCT | keep |
| **clojurescript** | **77** | mining the deleted CLJS pod from Git history | OBSOLETE-adjacent | shrink to a pointer; subject has no live code |
| seon-context-config | 58 | database-backed cluster config | PRODUCT | keep |
| ui-canvas | 55 | whether a canvas needs a new contract | PRODUCT (target-stage) | keep |

### 2.1 Citation verification (178 resolved `path:line` claims, ≥15 per skill)

**Zero citations point at a nonexistent file. Zero are past EOF.** Ten point at
a clearly unrelated line — all but one in first-party `src/seon/*` that churned
under a fixed number:

| Skill:line | Claim | Actual content of the cited line |
|---|---|---|
| `clojurescript:3` and `:15` | CLJS deletion stated at `AGENTS.md:247-254` | L247 is "reference transforms as vars (`#'f`)…"; the ruling is at **`AGENTS.md:200`**. Also wrong in the frontmatter `description` every agent sees at trigger time |
| `repl:39` | `src/seon/sci/eval.clj:1956` → `fork-for-turn` | L1956 is `install-row`; target is **+228 lines** |
| `seon-context-config:31` | `src/seon/config.clj:504` → `effective` | L504 is `:seon.error/diagnostic-evidence`; target is **+367** |
| `llm-providers:59` (also L11, L18) | `src/seon/ai.clj:339` → `wire-settings` | L339 is `{:seon.error/at (java.util.Date.)`; target is **+267** |
| `data-oriented-clojure:38` | `schema/edn.clj:303` → `packaged-forms` | **+103** |
| `datastar-web-ui:44` | `repl.clj:233`, `:166` → `text` | L233 blank; `text` is at **`repl.clj:256`** |
| `datahike:118`, `:155`, `:22` | `transaction.cljc:33` → `transact-add`; `db.clj:3002` → `write-entity-value`; `db.clj:283` → `call-with-custody` | **+753**, **+584**, **+82** |

Two further anchors land on blank lines and are each cited by two skills
(`reference-code/sci/src/sci/core.cljc:330`; `src/seon/db.clj:468`).

**The pattern:** every vendored `reference-code/` citation lands exactly.
All drift is first-party, concentrated in `src/seon/ai.clj`, `src/seon/repl.clj`
and `src/seon/config.clj`. Hand-maintained line numbers into a churning tree are
the repo's own banned "hand-maintained mirror" (L392–398) — the durable fix is
deriving anchors from the `:seon.fn` declaration spans the program graph already
stores. `codex-lanes` carries **zero** `file:line` claims, so nothing detects
when `bin/codex-agent` changes under it.

**Could not verify:** ~40% of citations are ranges into EDN/config/docs with no
symbol to anchor on — existence and EOF checked, content not judged. The
`references/*.md` files inside four skills were not checked at all.

---

## 3. Process machinery

| Item | Lines | Exists to | Verdict |
|---|---:|---|---|
| `bin/codex-agent` | 573 | Hand-rolled multi-lane Codex supervisor: atomic PID files (`:81-87`), lane+session mutual exclusion (`:99-217`), FIFO/tee transcripts (`:325-369`), tree-kill with 3 s verification (`:482-509`), injected `lane_rule` prompt line (`:61`), exports `SEON_CODEX_LANE`/`SEON_TEST_WORKERS=3` | **RETIRE** — ~470 of 573 lines are process supervision the product's own namespace agents make unnecessary; nothing in `src/` references it |
| `bin/test` | 1,107 | Cold gate launcher | **SHRINK** — actual JVM invocation is ~25 lines (`:1068-1080`); ~970 lines are hermetic-gate isolation that serves any engineer; only **~60–80 lines are lane bookkeeping**: `SEON_CODEX_LANE` refusal (`:242-245`), `--paths` parse (`:174-193`), `--prepare-head-base` (`:166`, `:839`), and the ls-tree contamination proof (`:759-800`) that exists solely to exclude *foreign dirty edits* |
| `bin/test-fast` | 48 | One-JVM lane iteration; `--paths` delegates to `bin/test --fast` (`:13-15`) | **KEEP**, drop the `--paths` branch with the lanes |
| `bin/_test-slot` | 165 | 2-JVM admission per repo, derived from the git common dir (`:40-46`) | **SHRINK** — the bound encodes a real incident (7 JVMs, load 25) and serves anyone; the lane-identity refusal (`:21-38`, ~18 lines) goes |
| `bin/seon-hook` | **1,987** | Babashka edit hook: kondo lint, markdown/docstring lint, shell-write digest walk, current-source publication | **KEEP** — serves any editor; Codex wiring is 26 lines. **But see finding 3** |
| `.claude/seon-hook.edn` | 84 | the hook's dials | KEEP |
| `.codex/hooks.json` | 26 | points Codex at `bin/seon-hook` | RETIRE with the lanes |
| `.codex/agents/*.toml` | 2 files | `seon-agent` / `seon-verifier` personas, self-described "compatibility alias" | RETIRE with the lanes |
| `bin/issues-index` | 4 → 41 | derives/checks the issue index | **KEEP** — orchestration-independent |
| `docs/TRANSFER_PROMPT.md` | 391 | orchestrator manual | **SHRINK to ~150** — ~60–65% (≈240 lines) is lane choreography; durable residue is owner working style (`:115-135`), session start (`:25-55`), the sweep (`:366-391`) |
| `driving-codex-agents.md` | 104 | `bin/codex-agent` CLI conventions | RETIRE with the lanes |
| `codex-cli-hooks-2026-09-17.md` | 324 | dated Codex hook investigation; §0 is titled "THE BLOCKER, FIRST: our lanes are running with hooks OFF" — a condition since fixed | RETIRE (it is a research note filed as a reference) |
| `lanes/` | — | **does not exist at the repo root.** Records are `tmp/orchestrator/lanes/<name>/sid`, one line each, 86 dirs, gitignored (`.gitignore:28`), **1.2 GB of stdout** | RETIRE + sweep now |

**Findings.**

1. **`SEON_CODEX_LANE` has exactly three consumers** — `bin/codex-agent:386`,
   `bin/test:242`, `bin/_test-slot:23`. Retiring lanes deletes ~25 lines across
   the two test scripts. The retirement is cheap.
2. **The `--paths` overlay and its contamination proof exist only to defend
   against concurrent foreign dirty edits.** Single-engineer or in-process-agent
   operation dissolves that whole mechanism, and `--prepare-head-base` exists
   purely for the orchestrator/lane split.
3. **The most valuable tool is the most degraded, and lanes are why.**
   `.claude/seon-hook.edn` currently has `:current-source {:enabled false}`
   (disabled because three lanes were editing `src/` concurrently — `AGENTS.md`
   rule 15, L98–105), `:check-tests false`, and `:schema-admission {:enabled
   false}`. **Two of three disablements are consequences of multi-lane
   operation.** Retiring lanes lets publication be turned back on — the single
   strongest argument in this audit for the retirement.
4. **Exhaust to sweep** (only 2 retained run roots, so little is held):
   `tmp/orchestrator` 1.2 GB, `tmp/test-runs` 1.8 GB, and `tmp/head-wt` — an
   orchestrator worktree left behind against the same-turn removal rule (L85).

---

## 4. Docs tree

868,142 lines across 4,018 files. The three live PRD programs hold 394,385
lines, of which **807 dated `research/` notes are 303,529 lines (77%)**.
`docs/seon/issues` live tree: 492 notes, **386 created in the last seven days**;
400 `open`, 86 `resolved`, 4 `superseded`; 351 `friction`, 108 `blocker`, 31
`cleanup`.

**Living authority** (keep in place): `docs/seon/architecture/*.md` (1,762
lines; L857 declares the directory always-current), `data-modeling-guide.md`,
`docs/seon/issues/README.md` + the 400 open notes, the `plan/README.md` +
`unsettled.md` of `context-generation` and `steward-platform`,
`docs/seon/reference/llm-adapters.md`, `docs/TRANSFER_PROMPT.md` (shrunk).

### Proposed `git mv` list — NOT EXECUTED

| # | Move | Files | Lines out of the live tree |
|---|---|---:|---:|
| 1 | `git mv docs/prds/archive docs/archive/prds` | 996 | 332,945 |
| 2 | `git mv docs/seon/issues/archive docs/archive/issues` | 1,399 | 97,644 |
| 3 | `git mv docs/prds/sci-execution-runtime docs/archive/prds/sci-execution-runtime` ⚠ | 489 | 190,029 |
| 4 | `git mv docs/prds/context-generation/research docs/archive/research/context-generation` | 214 | 68,454 |
| 5 | `git mv docs/prds/steward-platform/research docs/archive/research/steward-platform` ⚠ | 237 | 95,218 |
| 6 | `git mv docs/seon/vision docs/archive/vision` | 44 | 4,371 |
| 7 | `git mv docs/seon/lineage docs/archive/lineage` | 4 | 923 |
| 8 | `git mv docs/seon/concepts docs/archive/concepts` | 12 | 705 |
| 9 | `git mv docs/seon/components docs/archive/components` | 20 | 374 |
| 10 | `git mv docs/seon/architecture/archive docs/archive/architecture` | 2 | 299 |
| 11 | `rmdir docs/seon/pod` (empty) | 0 | 0 |
| | **Total** | **3,417** | **790,962 (91.1%)** |

Residual live tree: **~601 files / ~77,180 lines.** Git retains everything moved.

**Paths `AGENTS.md` links that must survive or have their link rewritten in the
same commit** (AGENTS.md line of each link): `docs/TRANSFER_PROMPT.md` 321,
1285, 1310 · `context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md`
15, 288, 368 and 17 vocabulary rows · `context-generation/plan/README.md` 859,
1315 · `unsettled.md` 860 · `architecture/data-modeling-guide.md` 501 ·
`architecture/architecture.md` 1313 · `architecture/ui.md` 717 ·
`issues/README.md` 1295, 1319 · `reference/llm-adapters.md` 1157 ·
`reference/driving-codex-agents.md` 1189 · `reference/codex-cli-hooks-2026-09-17.md`
1275 · `steward-platform/plan/{namespace-agents-plan,program-facts-are-the-runtime}`
725, 742.

⚠ Moves 3 and 5 break **8 links** (L154, 170, 361, 530, 620, 627, 777, 778) —
all pointing at *evidence* notes for claims `AGENTS.md` already states in full.
Eight link-text edits buy 285,247 lines out of the live tree.

---

## 5. Orchestrator memory

261 files / 1.4 MB at `~/.claude/projects/-Users-sean-src-seon/memory/`.
`MEMORY.md` is 142 lines / 25,399 bytes — **already past its 24.4 KB limit, so
line 142 is silently truncated at every load.** An index that drops its newest
entry without saying so is this project's own named failure class (L185–190:
"a check that reads ABSENCE OF SIGNAL as health") living in the orchestrator's
own memory.

| Class | Count | Recommendation |
|---|---:|---|
| 1 — standing PRODUCT rule | 32 | **Fold into the rewritten `AGENTS.md`.** Nearly all already have a paraphrase there (memory L117 ↔ AGENTS.md:657; L70 ↔ :47; L142 ↔ :524) — they are duplicate authority. Keep the topic file only where it carries evidence `AGENTS.md` omits. |
| 2 — standing PROCESS rule | 55 | Fold the ~35 lane/gate/model/codex rules into `AGENTS.md` §9 or **delete with the lanes**; keep only the ~10 account-specific ones (model availability, credit handling). Largest class, largest duplication with AGENTS.md L27–113 and L1165–1307. |
| 3 — superseded handover / dated arc | 24 | **Delete the index lines.** Nine say "superseded" in their own text (memory L6, 7, 10, 13, 17, 19, 47, 51, 136). Only L4 (orchestrator method) and L9 (namespace-agents direction) are live. Deleting the other 22 alone brings the index back under the limit. |
| 4 — user preference | 12 | Keep as memory — style belongs to the assistant, not the repo. |
| 5 — external reference | 6 | Keep as memory — pricing, key locations, MLX limits have no repo home. |

Class-1 titles to fold (memory line): MEASURED LANDINGS ONE JVM (5) · STOP
FIGHTING MALLI (8) · UNBREAKABLE PROGRAM GRAPH (12) · AGENT RECORD + REPL REPLY
(15) · FAST BY DEFAULT (20) · CLASS REGRESSIONS (26) · DB DATA IS DISPOSABLE
(27) · UGLY OUTPUT IS A DEFECT (31) · HICKEY ETHOS (40) · QUARRY FIRST (42) ·
TEN-SECOND START (43) · DELETE ABANDONED CODE (46) · HEAD MUST LOAD (54) ·
PLATFORM FIRST (68) · LOAD NEVER BLOCKS THE PAGE (69) · ONE CLIPPING SPOT (70) ·
NAMES GROUNDED IN SOURCE (79) · R41 (80) · R28 (83) · scorers/PRD-code/zero-scores
(89) · three-tier storage (92) · cljc + vendored deps (99, 106) · REPL
skepticism/atoms→flow (102) · functions-not-verbs / no hacks / no hand-maintained
lists (107) · align context with runtime (112) · show don't tell (115) · always
be committing (116) · no :kind/:type (117) · test behavior not strings (118) ·
no legacy/shims (121) · symbols stored as symbols (142, currently truncated).

Caveat: several memory filenames are dated 2026-09-22/23 though today is
2026-09-21 — treat the suffix as a label, not a timestamp.

---

## 6. What could not be verified

- **Whether removing the process instructions changes agent behaviour.** This is
  a read-only textual audit; no lane was run and no gate executed. The causal
  claim "these instructions produced bloated code" is the owner's, not measured
  here.
- **~40% of skill citations** (ranges into EDN/config/docs with no symbol
  anchor) were checked for existence and EOF only, not for content. The
  `references/*.md` inside four skills were not checked at all.
- **`bin/test`'s internals** were deliberately out of scope; the 60–80 line
  lane-bookkeeping estimate is a read of arg-parse and refusal sites, not a
  call-graph proof.
- **Which of the 400 open issue notes are still real.** 386 were created in
  seven days; no sampling pass was run to see how many describe conditions that
  survived the commits since.
- **Whether `docs/seon/architecture/` is in fact current.** L857 asserts it is
  "always-current aspirational"; the 1,762 lines were not verified against
  `src/`.
