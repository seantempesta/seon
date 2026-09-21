---
type: research
status: review complete; owner decisions named below
created: 2026-09-21
tags: [agent-platform, instructions, review]
---

# Review of the AGENTS.md rewrite

Read end to end: [writer brief](../../prds/agent-platform/research/writer-brief-2026-09-21.md), [rewrite](../../prds/agent-platform/plan/AGENTS-rewrite-2026-09-21.md), [current AGENTS.md](../../../AGENTS.md), [instructions audit](../../prds/agent-platform/research/instructions-and-process-audit-2026-09-21.md), [goals §§3–5](../../prds/agent-platform/research/durable-goals-and-rulings-2026-09-21.md), [plan README](../../prds/agent-platform/plan/README.md), and all seven `lane-*.md` specs there. Below, R = rewrite, P = plan README, G = goals, and A1–C1 = those specs. All line anchors checked against HEAD `209a6652a0220570c77b3d9aadd75c446ab6e9bc`; dependency reads used HEAD's gitlinks. No evaluation, JVM, operator or test ran. Only this note was written; the four pre-existing dirty source/test files were untouched.

## Findings

| Item | Finding | Evidence | Proposed change | Priority |
|---|---|---|---|---|
| 1 | 497 lines still load an architecture guide and an API index with every task. The audit's own outline is not arithmetically feasible: its budgets sum to 385, not ≈245; 65 table rows cannot fit in 30 lines. | R `wc -l` = 497; audit §1.4 | Budget 250 including headings/blanks; move mechanics, delete repetition, measure bytes as well as lines. Do not join paragraphs into enormous lines to claim the target. | P1 |
| 2 | The strongest instruction is repeated in the opening, REPL section and lane section. | R:25–33, 349–360, 465–469 | Keep “Use the REPL to test ideas, inspect data and measure.” verbatim once; put the plan, seam, probe, test request and landing path first. | P1 |
| 3 | Test API and selection policy disagree with B4, and the latter is still an owner decision. | R:400–405 says static calls and `check` over `run-owned`; B4 §2/§6 replaces them with `run`; P §7 #2 leaves observed reach open | Keep one request law; point at B4 for the installed signature. Do not silently approve observed reach or treat missing reach as green. | P0 |
| 4 | Destructive isolation is omitted while “boot from zero” is the sole subprocess. B4 both preserves `isolated-snapshot` and deletes its machinery. | G §3 F1; brief:99–101; R:405; B4:100, 111, 186–187; P §6 allows destructive drills | Owner options below; retain F1's isolation and reach-derived classification until explicitly changed. | P0 |
| 5 | Lanes are invited to reset or restart the owner's development cluster. | R:440–446; brief “The REPL stays up”; G F8 grants reset to the orchestrator | State who resets; lanes record RESET NEEDED and use their own authorized scratch root. | P0 |
| 6 | One-JVM prose contradicts the cold `clojure -M` load check in the same paragraph. | R:466, 470–472; P §5 and each spec repeat the cold check | Require loadability and live reload; put the fresh-process proof in the one isolated integration request. Fix the specs in the clean write too. | P1 |
| 7 | Proposed behavior is presented as present behavior. This is not solved by reinstating a permanent stage forecast. | R:101–107, 259–260, 435–437; HEAD pull default = 1000; hook `:enabled false` at `.claude/seon-hook.edn:48` | State the law and link its implementation spec; only describe current operation after verifying it. Never imply an edit is adopted without observing it. | P0 |
| 8 | Core product rules disappeared: gated write-back, no self-modification through shell, no shared admission without contracts and reaching tests, no task admission without a done condition, agents never retracted. | G §3 R5/C1/C2/C6/D3, “Agents are never retracted”; G §5; P D1; R §3 | Restore concise rules below. Keep the task's assigned agent distinct from responsibility derived through namespace refs. | P0 |
| 9 | Prompt unit selection is lost; “never the history” could forbid the explicitly retained operation. Answered vs handled also loses the qualifying ordinary-reply condition. | brief:94–97; G F3; B2 §2c; current AGENTS “Waking and the loop”; R:195–204, 307 | Say whole-unit prompt selection stays; historical shown text never changes. Opening/system-only turns do not answer wakes; handling is independent. | P1 |
| 10 | Missing modeling/render rules must move, not evaporate: union checked against body-derived errors; composed error rendering; provenance-based write bounds with refused transaction data; config symbols resolve; component completeness; bootstrap config exception. | G §3 §1o/§1q/§1r; current AGENTS §§1,3; R §§1–3 | Keep compact guard sentences below; move the detailed algorithms to the guide and skills in the same clean write. | P1 |
| 11 | A correct anchor is not proof of its attached claim. `current-wrapper?` also compares policy and referenced definitions; SCI `init` does not specify Seon's schema-name matching. Fixture shorthand changes its apparent file after the turn citation. | R:185–186, 298, 393–395; HEAD `instrument.clj:844–858`; SCI `core.cljc:310–319`; A1-5/C2 proposes structural equality against G's name-only ruling | Include all invalidation inputs or move the algorithm. Cite SCI's hook plus Seon's owner. Fully qualify fixture citations. Reject structural supplier matching unless the owner changes the ruling. | P1 |
| 12 | “No dynamic var” conflicts with C1's proposed custody lookup. Publication prose hides B1's change from report-based caller selection to a precomputed diff. | R:138–142; C1 §2; G “transaction report is the seam”; B1 §2a steps 6–9 | Do not weaken either law implicitly. C1 must justify a narrow custody exception; B1 must reconcile its proposal with the ruling in its own spec. | P0 |
| 13 | Retired words survive as current prose; the table also bans legitimate domain uses too broadly. | R:123 “two classes”, :125 “core fault”, :443 “Churn”, :470 “heartbeat”; G §4; R:307 bans “trigger” while §3 requires task triggers | Use error value/core error, process change, commit. Qualify retired spellings by meaning; `my.task` as a second family is retired, B3's thin surface is not. | P1 |
| 14 | No surviving sentence forecasts weeks or excuses a multi-minute edit; do not call the >10 s authorization rule a slowness exception. Some specs still normalize boot time or retain “cold proof owed.” | R:35–49; A1 §8, B4 §8, C1 §8; B1 §1 “measured, paid once” | Keep explicit owner authorization and algorithmic justification, including boot/indexing. Remove standing debt language from the coordinated clean write. | P1 |
| 15 | Cleanup lost the no-live-holder check. The old working edge/ideas ledger are unnamed and slated for deletion. | R:76–78, 441; current AGENTS lane rule 14; P §8 | Keep holder checks; use the plan's actual decision section and landing notes, with migration of rulings before deleting old authorities. | P1 |
| 16 | Generic “diagnostics … or say nothing” contradicts typed unknown; “function whose contract fails does not run” cannot describe output failure. | R:187–192 | Input failure prevents entry; output failure refuses the result. Unavailable observation is explicit, never silence. | P1 |

Citation inventory (all numbered references, including shorthand and range endpoints, checked with `git show HEAD:<path>` and numbered `grep`; repeats grouped). **No missing file, out-of-range number, or misplaced definition anchor found.** These are anchor checks, not live proof; semantic exceptions are items 7 and 11.

| File | Verified anchors |
|---|---|
| `src/seon/db.clj`; `src/seon/env.clj` | 1219 `carried-projection`; 330 `scope` |
| `src/seon/turn.clj` | 215 `open?`, 395 `open-call`, 442 `close-call`, 465 `open-run-tx-call`, 1704 `recover-tx`, 2103 `system-turn`, 2888 `next-agent-work`, 2980 `latest-answering-turn-t`, 5263 `step` |
| `src/seon/sci/eval.clj`; `src/seon/fn.clj` | 2184 `fork-for-turn`, 2218 `base-ctx`; 1573 `gate-set`, 1586 `tests-reaching` |
| `src/seon/instrument.clj`; `src/seon/error.clj` | 844–858 `current-wrapper?`, 898 `collect-contracts!`; 304 `diagnostic` (B3 retires it) |
| `src/seon/render.clj`; `src/seon/render/walk.clj`; `src/seon/ai/tokens.cljc` | 115 `request-profile`; 698 distance elision; 145 `estimate` |
| `src/seon/schema/datahike.clj`; `src/my/message.clj`; `src/seon/render/route.clj` | 27–28 symbol mappings; 31 `send`; 5 routes |
| `src/seon/cluster/wake.clj`; `src/seon/eval.clj`; `src/seon/id.clj` | 317 `inside-wake?`; 9 `of-agent`; 29 `id`, 40 `digest`, 47 `symbol-in`, 55 `evaluation` |
| `src/seon/repl.clj`; `src/seon/effect.clj`; `src/seon/program.cljc` | 447/480 render pair; 1046 `request!`; 1042 `exact-replacement-tx` |
| `src/my/program.clj` | 152 callers, 167 tests-reaching, 217 reads-key, 278 breaks, 357 history |
| `test/seon/test_support.clj` | 30 event bound, 309 transacted!, 982 with-database, 1026 program-fn-row, 1039 apply-config!, 1058 seed-cluster!, 1073 preserving-instrumentation-state, 1116 closeable |
| `src/seon/test.clj`; `src/seon/test/runner.clj` | 576 run-owned, 1901 check; 3145 commit-results! |
| `reference-code/datahike/src/datahike/{pull_api,query}.cljc` at `006e634a` | 16 default 1000; 2877 query-dependency-plan |
| `reference-code/sci/src/sci/core.cljc` at `fcbd8862` | 260 intern, 331 init, 345 fork; hook semantics are at 310–319 |
| `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` at `dc35f3e0` | 78 graph keys/executors; 165 process |

## Proposed table of contents and cuts

Budgets include headings and blank lines; total **250**, a 247-line reduction (49.7%). Preserve normal wrapping; `wc -lc` must demonstrate a real context reduction. The exact text below supplies the changed operational sections and added laws; the retained laws use the stated compression, not deletion.

| Section | Budget | Exact retention / compression / destination |
|---|---:|---|
| Frontmatter and authority | 13 | R:1–13 unchanged. |
| Start here | 24 | Move plan, seam, REPL, test request and landing path from §§5/6/8/9 to the first screen. Replacement below. |
| How we work | 20 | Keep verbatim “Ask what the dependency already does before you build anything.”; “Use the REPL to test ideas, inspect data and measure.”; “The best change deletes a mechanism.”; “Conversation memory is never the only record.” R:17–23 becomes “Read Git history and the owning PRD's research before designing; improve what you find.” R:35–49 becomes the four sentences below; historical examples disappear. |
| Boot, running, cluster, transport, crash | 22 | R:80–127 → replacement below. Boot/publication sequence and wrapper mechanics → `architecture/architecture.md`; SCI lifecycle → `architecture/agent-runtime.md` and `repl` skill. |
| Five design laws | 43 | Keep the five titles verbatim; keep “No seam may act on a pre-read or a mirror that its authority will re-decide: derive at the authority, or hand it the decision”; “A pre-read is legitimate only when its answer cannot change before the authority acts.”; “A REGEX IN PRODUCTION CODE REQUIRES THE OWNER'S PERMISSION — STOP AND ASK.”; “HTML has no presentation clipping.” R:135–145 → supplied inputs/carried derivations plus the defaults exception; R:149–164 → query facts/derive-check-date/regex; R:168–178 → readiness plus enforced bound; §2.4 → corrected boundary/render text below; R:211–223 → open maps/accretion/no duplicate mechanism/three-option design gate. |
| Data, errors, tasks and admission | 40 | R:231–281 retain each bold ruling as one sentence. Ref mechanics, temporal schema origin, component validation and pulled grammars → `data-modeling-guide.md` + `datahike` skill; add missing rules below. No ruled behavior disappears merely to meet the budget. |
| Vocabulary | 16 | Replace the 32-row API index with the six distinctions below; move every remaining definition/legacy mapping to its existing domain architecture section, with the matching skill linking it. No second glossary or new registry. |
| Tests and proof | 28 | R:373–415 → replacement below: nine fixture obligations in four sentences, class regression, bounds, honest result recording. Move helper signatures and restoration mechanics to `clojure-testing`. |
| Operating | 20 | R:419–458 → replacement below; CLI variants/cache repair/provider details → `repl` and `llm-providers` skills. Keep capability and cleanup laws. |
| Working and reporting | 14 | R:462–485 → replacement below. Model assignments belong in the current plan, not permanent instruction authority. |
| Pointers | 10 | Architecture map, data guide, issue lifecycle, skills; plan already first. Preserve moved definitions and citations before deleting their former home. |

## Exact replacement text

### Replace §§5 and 9's plan pointer with “Start here” immediately after the authority statement

> Read [the active plan](docs/prds/agent-platform/plan/README.md), your `lane-*.md` spec, named authorities and the nearest `AGENTS.md` end to end. The plan owns decisions and the specs own implementation details; neither makes an unverified mechanism current.
>
> Before editing, read the spec's vendored seam in `reference-code/` and the first-party call site that uses it. Record the dependency, pinned revision, file:line, guarantee, inputs, recomputation event and work proportionality. Read Git history before designing. Load the matching `.agents/skills/` skill at design time.
>
> Start every Clojure change at a running system, not at a file. Check `bin/seon status`, then `mcp__seon__runtime_status` for `default`. Use `mcp__seon__eval_clj` with explicit root/cluster; `jvm` mode requires explicit custody such as `(seon.operator/connection "default")`. `sci` mode changes the shared SCI context; keep probes disposable. Report and record missing or degraded tools before any workaround.
>
> Reproduce with one small form; read its complete envelope. Call the owner directly, inspect facts and schema, probe a better algorithm than the spec's floor, and record exact forms, values and timings. Edit the owner in place, verify adoption or hot reload, then repeat the probe. Name whether live evidence used hot reload, a fork, or in-place adoption; observe browser paint separately.
>
> Run the armed tests reaching the change through the installed in-process request described in [B4](docs/prds/agent-platform/plan/lane-b4-tests-in-process.md). Never gate on the full suite. Put before/after evidence, commit ids, changed files, size measurements and verification boundaries in `docs/prds/agent-platform/landing/lane-<x>.md`. Commit evidence scripts; disposable probes live in `tmp/`, reusable checks in `test/`.

### Replace R:35–49 and R:76–78

> Anything taking longer than ten seconds requires the owner's explicit authorization, including cold boot or initial indexing; neither is an automatic exemption. Investigate costs above a couple of seconds by naming unnecessary work and the library seam that already owns it. An edit costs changed declarations and their callers; a fork changes a branch pointer; an unchanged request compares commit ids. No double caching: use the existing tool caches.
>
> Record rulings in the active plan's decision section, evidence in the owning landing note, and defects in the existing issue authority in the same commit. Conversation memory is never the only record.

### Replace §1

> One JVM runs the CLJ system, REPL-first. Boot opens the REPL before acquiring the store, then constructs process → store → facts → flow; each layer publishes readiness. Tiny process bootstrap settings select paths and binds; running cluster configuration is database facts. One process root owns one Datahike store under its lifetime flock; Datahike owns transaction serialization.
>
> A cluster is one database branch, its agents and shared plumbing, with one environment scoped per agent. One JVM may host many clusters. A new cluster forks an exact published commit; ordinary clusters keep their program until explicitly reforked, while selected development clusters adopt in place. A file edit is live only after publication, reload and arming succeed; hot reload alone is not program indexing.
>
> Each agent owns its flow graph; no central dispatcher or scheduler. Procs explicitly select `:io` or `:compute`; graph transforms reference Vars, and topology changes rebuild the graph. Channels carry only data whose loss is free because facts re-derive it or a newer complete value replaces it; buffers express that loss policy. Anything recovery or another process needs is durable fact, with bulky durable payloads in blobs; evaluation result objects are excluded.
>
> Interrupted execution never resumes: recovery closes open turns and marks unfinished evaluations interrupted. Private defs, atoms and result objects stay in memory and disappear on JVM restart; shown text remains. Keep private objects across turns without rebuilding an unchanged program context. The agent may call every function in its cluster's program graph; prompt visibility never gates execution.
>
> Agent mistakes return flat error values. Core errors follow the one configured policy: throw in development, record in production; database failures require their declared handling. Consumer-specific UI, integrations and domain models belong downstream. Mechanisms and verified seams live in [architecture](docs/seon/architecture/architecture.md) and [agent execution](docs/seon/architecture/agent-runtime.md).

### Replace §2.4's boundary/render paragraphs; retain the other four law titles and compressed laws

> Every function, private included, has a complete Malli contract. Invalid input prevents entry; invalid output refuses the returned value. Refusals name the operation, offending member/value and expected shape. Unavailable evidence is a typed unknown, never success or silence. Every domain function names its possible errors; the declared union is checked against the body-derived set. A genuinely polymorphic inspection boundary may use the declared error base. No general error predicate, copied union or discriminator stamp.
>
> Rendering is total for ordinary values. AI render functions and the value renderer alone apply presentation limits, once at evaluation; store the exact shown text and never clip it again. HTML has no presentation clipping. Whole-unit prompt selection stays; it never rewrites historical units. Query-work limits and evaluation deadlines are separate; a query-work cut names its bound in an elision value with count, path and requery identity. Count floor hits. Derive a missing profile at the render entry or return the typed refusal. Report unreadable output; human display sizes use estimated tokens.
>
> Render pairs are schema properties, one AI/HTML pair per entity schema. Scalars share the entity's block; components and declared queries supply their own. When no pair is declared, use the default attribute-map printer; a failed render produces a diagnostic. Error rendering composes the base and every satisfied error schema, never a most-specific winner. An attribute request never silently becomes an entity request.

### Add these compressed rules to §3; preserve its existing retraction/value/component/empty-set/symbol laws

> Every function/test's required analysis digest distinguishes analyzed-with-no-calls from never analyzed. A name without a current function is reported unresolved, never repaired with a placeholder. Retraction and all repairs of surviving references belong in one final transaction or refuse together. Components validate with their owner, including swept refs; missing children, cycles, multiple owners and exhausted validation bounds refuse. Stored entity schemas and selector-derived pulled shapes are distinct; never hand-write a second pulled schema. Pull must never silently cut.
>
> Config reconciles the difference; its function symbols must resolve to current program facts. Provenance stays in transaction metadata. System and agent write bounds derive from provenance; a bounded-out agent write returns the refused transaction data. Store only schema-declared data. Use `seon.id/id`, `digest` and `evaluation`, never a second identity generator or truncation; canonically order identity inputs whose map order is not semantic.
>
> One `seon.task` owns work; `my.*` is its thin surface, never another fact family. Task identity is resolved at the writer: wake its existing agent or create the task and agent with the first turn atomically. Namespace responsibility is many-to-many context, never routing. Refuse starting work with neither tests nor a detector; never invent a test entity. Done is the cited tests verifying on the current program or the detector no longer naming the subject; only settlement writes resolution. Assigned agents continue while the task is open and budget remains; exhaustion is explicit and resumable. Agents are archived, never retracted.
>
> Candidate work uses its own branch and SCI context. Shared admission requires complete contracts and reaching tests; untested private experiments remain possible. Merge requires the task tests and all reaching tests on the combined program, with a durable acceptance record; conflicts become one task for root and its resolution uses the same gate. Write-back is the checked reverse of indexing by exact source span. Product agents use declared evaluation/adoption/test requests, never shell commands to modify their system.
>
> Wake answering derives from the datom's `:t` and an accepted ordinary reply's basis; opening or a system-only turn does not answer it. Handling is independent. For messages, `:seon.message/from` alone identifies inside wakes; the subject string is stored verbatim, independent of routing and assignment. Every distinct read's latest evaluation participates in the since-diff; changed reads append system evaluations, writes/effects never rerun. Existing prompt bytes stay unchanged until compaction wipes evaluations and regenerates the opening; no manual curation. Root's scheduled collection derives its cutoff and runs at twice the last retained size; no separate retention dial.

### Replace §4

> Use Clojure's name, else the dependency's name and semantics; coin only an actual Seon concept, once, with both boundary sources. Preserve literal identifiers and historical quotations. Correct legacy prose in scope or record it as a defect. Domain architecture and the matching skill own the detailed definitions and legacy mappings.
>
> | Distinguish | Meaning |
> |---|---|
> | cluster / environment | database branch plus agents/plumbing / the value boot supplies |
> | JVM REPL / SCI evaluation | host evaluation with explicit custody / evaluation in the selected SCI context |
> | base SCI context / agent SCI context / prompt | program context / private in-memory objects on a fork / ordered rendered evaluations |
> | turn / evaluation / result / shown text | agent work interval / one evaluated form / actual object / exact saved rendering |
> | namespace agent / task / conversation | responsibility / linked work facts / derived messages |
> | call preparation / supplied defaults | SCI hook supplying absent declared arguments by schema NAME; caller wins; unavailable is a typed error |
>
> Use evaluation/result, history, namespace agent, error schema and occurrence; never receipt, transcript, steward, facet or fault/class as error-entity nouns. `my.task` may name B3's thin surface, never a separate task family. Use Datahike's database value, basis `:t`, commit ID, branch and transaction report; wire means an external crossing only.

### Replace §6

> Tests use the canonical database fixture, real SCI and the contracts the cluster arms; never a hand-rostered substitute. Use `with-database` plus `::extra-schema` only for synthetic attributes, and canonical entity helpers with `transacted!` so refused writes are visible. Hand the projection/environment, every proc input and a fixed render profile explicitly. Await terminal facts under the declared event bound, never quiescence; assert current typed behavior, not absence.
>
> Tests own no global namespace or scheduling state. Preserve instrumentation when testing its mutation; never reinstall roots a reload replaced. Stop a graph before a fixture retracts facts it is settling, or hand the loop the decision. Acquire resources in `with-open` so setup failure and cleanup failure still release earlier acquisitions. Details and helper contracts: `.agents/skills/clojure-testing/SKILL.md`.
>
> Every test has a bound, default 5 s, and fails over it; a longer bound needs a number, reason and any required owner authorization. A reason without a number refuses. Never accept an ordinary test because its backstop fired; a regression of deadline behavior must assert the typed failure and cleanup. Tests publish a small fixture program, never all of `src/`.
>
> The request names its cluster and change basis. The database selects tests and reuses matching recorded green evidence; missing evidence is explicit, never green. Results record where execution occurred and the tally is a query; recording failure cannot report success. B4 owns the one request and its selected reach algorithm; resolve its open owner decision before publishing that algorithm as settled policy.
>
> Run the reaching set in process. The orchestrator verifies the integrated reaching set and isolated platform proof; never gate on a full suite. Destructiveness derives from declared owner reach and requires an immutable snapshot in an isolated process, never `default`; the owner must settle its relation to the platform host before B4 deletes that path. A branch or SCI fork alone does not isolate JVM-global mutation or filesystem effects.
>
> Tests find design issues; structure dissolves them: move the invariant to the owning seam and keep one regression per class. Delete tests of removed machinery; retain each surviving behavior's proof. Landing requires measured improvement, loadable HEAD, armed reaching tests and a named live observation; schema/acquisition/process changes also need the orchestrator's isolated boot proof. A green run alone lands nothing.

### Replace §§7–8

> `bin/seon` is the development operator: `status`, `start`, `init`, `config apply`, `open`, `stop`, `down`, `reset --force`; `--root PATH` selects an isolated root. Stop through the operator using exact `(pid, start-instant)` identity, never blind child kills. Use explicit root/cluster in MCP. Keep the development REPL available throughout the work.
>
> A lane never stops, reforks, restarts or resets `default`. The orchestrator owns recovery and batches incompatible stored-shape changes into a reset; data is disposable, migrations are not the remedy. Lanes record RESET NEEDED with the commit and verify on an authorized scratch root when necessary. Create that directory first, then down and remove it after use. Never operate another lane's session.
>
> Check the hook's actual configuration; an edit is adopted only when publication and the cluster's source commit agree. Shell source edits require explicit publication/adoption. The hook lints, never tests. Verify dependency cache freshness before changing correct references to satisfy unresolved-name findings; the matching skill owns cache repair. Observe the page separately.
>
> Preserve unrelated dirty files. A held file stops that slice; a public Var retirement and every caller conversion are one commit. Keep HEAD loadable and reload the touched namespaces in the existing development JVM, then perform the commit's named debug probe. The orchestrator's isolated boot proves fresh-process loading; do not launch a JVM for every commit.
>
> Commit one coherent slice with `git commit --only -- <owned paths>`; never `git add -A`, `reset --hard` or `checkout --`. No structural worktrees. Delete only your own disposable roots after proving no live holder remains; shared cleanup belongs to the orchestrator. Recursive deletion never follows symlinks; its regression includes a symlinked sentinel.
>
> Credentials name environment variables, never datoms; paid runs are deliberate, cheapest probe first. Agents retain full capability: restrictions require evidence of a real problem. Check process/adoption freshness after process changes and continue within the assignment's authority.
>
> VERIFY THE CLAIM BEFORE YOU NAME THE CAUSE. Record every finding in the existing issue authority or the assigned review note; search before duplicating it. Report broken things first with repository-relative links, exact evidence and verification limits. Use verify, probe and falsify. For an unsettled design with broad consequences, stop before production edits and give exactly three options: simplest viable constraint first, recommendation marked, guarantee, cost and what we give up.

## Owner choice: destructive tests and the one subprocess

These are proposals, not authorization. Resolve in P §7, B4 and AGENTS together; include where results remain queryable after scratch cleanup.

| Option | Guarantee | Cost | What we give up |
|---|---|---|---|
| 1 — recommended: one isolated execution mechanism for platform **and** destructive requests | F1's immutable named-cluster snapshot remains; boot tests start from zero; destructive tests derive from owner reach and cannot damage `default`. Same runner and recorder. | Extend the one host request with the required initial database value; serialize isolated requests; retain recorded evidence before deleting a root. OS-fence tests may need a specifically declared peer process. | The literal claim “boot from zero is the only subprocess”; preserve one mechanism and bounded concurrency instead. |
| 2 — retain only platform subprocesses; convert other destructive tests | Every remaining in-process test must prove all effects confined to its branch/ctx and owned resources; real JVM/process destructive behavior moves to platform scope. | Audit reach and effects, rewrite tests, and specify platform snapshot support; refuse any unconverted member explicitly. | Cheap mechanical deletion of isolation code; some tests must change scope. Branch immutability alone cannot establish safety. |
| 3 — explicit narrow exception to the one-subprocess rule | Keep boot platform and existing destructive snapshot execution, with one runner, explicit bounds and no live-root mutation. | More host lifecycle code and isolated requests, but immediate preservation of F1; no full-suite requirement. | The strongest process simplification; owner accepts the measured extra cost. |

## Verdict

Do not replace AGENTS.md with this draft yet. It preserves the essential speed, vendored-source, REPL and no-full-suite direction, and its numbered anchors are unusually accurate, but it doubles the intended reading budget, states proposed behavior as current, drops admission laws and contradicts reset ownership and B4. A 250-line authority is feasible by retaining laws once, moving mechanisms to existing architecture/skills, putting the five-minute workflow first and resolving the named spec conflicts in the same clean write. No production code or foreign lane was changed; this is textual and HEAD-source verification only.
