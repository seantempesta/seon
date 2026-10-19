---
title: "Runtime inventory: the old branch-runtime model"
type: research
status: active
created: 2026-10-19
---

# Runtime inventory: every statement of the old branch-runtime model

Lane `runtime-inventory` (read-only; 2026-10-19). Input: the new law in `AGENTS.md`, "The program is entities" (commit
36271f22a), and `docs/research/agent-platform/2026-10-19/branch-runtime.md`. The old model removed everywhere: a
compiled `src` caller sees a branch's change only after merge and reload; the loaded `src` program is the platform;
interpret differing entities and their affected callers; bind compiled Vars for the rest; lazy members, promotion,
the caller-closure walk, `interpret-affected!`, the overlay plan; tests deferred to a merge or platform run.

Scope searched: `docs/` (excluding `docs/research/agent-platform/2026-*/` dated evidence, `docs/prds/`),
`.agents/skills/`, `docs/seon/` (specs, cards, issues, architecture), `resources/seon/help.md`,
`resources/seon/tasks/*.edn`, and `src/`, `script/` (docstrings and comments, and code for class d).
Tests were searched only to confirm symbols; their assertions are out of scope and are not listed.

Classes: **(a)** doc or skill sentence a mechanical rewrite fixes. **(b)** task whose subject the new model makes moot
or changes. **(c)** docstring or comment in `src` that is code-adjacent. **(d)** code implementing the old model (P3
deletes it; listed only).

Caveat for the reader: the `2026-10-18` rule is still what `AGENTS.md` line 354 calls installed until `runtime-wire`
lands. Every row below should be rewritten in the same commit that lands runtime-wire, not before.

## 1. Authority and skills

| # | File, heading | Exact sentence (verbatim, excerpt) | Class | Proposed replacement |
|---|---|---|---|---|
| 1.1 | `AGENTS.md`, "The opposite of virtual machines" | "only what it changed is interpreted; everything else is the one loaded program" | a | "only what it changed is analysed by content; a runtime maps each name to the digest its branch holds, and a host-bound declaration runs compiled everywhere." |
| 1.2 | `AGENTS.md`, "How to work" item 1 / "A proof must observe the change" (line ~500) | "(compiled `src` callers see a branch's change only after merge)" | a | "(every SCI-runnable caller sees the change on the branch; a host-bound caller runs compiled, so a proof of such a change runs through the platform check)" |
| 1.3 | `AGENTS.md`, "The program is entities" (line 354) | "(Installed until lane runtime-wire lands: the narrower 2026-10-18 rule.)" | a | Delete this parenthesis in the runtime-wire commit; it is the transitional marker, not law. |
| 1.4 | `.agents/skills/seon-flow-architecture/SKILL.md`, base context paragraph (~line 62) | "which interprets the branch's own program changes (overrides and their source callers; loaded `src` callers stay compiled)" | a | "which resolves the branch's program by name to digests (`seon.sci.eval`); analyses are shared by content, and host-bound declarations stay compiled." |
| 1.5 | `.agents/skills/repl/SKILL.md`, "What the branch context is not" (~line 139) | "A host-bound declaration SCI cannot interpret keeps its compiled Var" | keep | Still true under the new law (host-bound runs compiled everywhere). No change; listed so it is not mistaken for old model. |

## 2. Architecture and specs

| # | File, heading | Exact sentence (verbatim, excerpt) | Class | Proposed replacement |
|---|---|---|---|---|
| 2.1 | `docs/seon/architecture/clusters-branches-contexts.md`, item 2 "Every other reality interprets its differences" (lines 39-49) | "The loaded `src` program is the platform: a compiled `src` caller is never re-rooted and sees the change only after merge and reload (owner, 2026-10-18)." | a | Rewrite item 2 whole: "A branch's change is live in its whole runtime. The platform is only what SCI cannot run: host-bound declarations, which run compiled everywhere. Every SCI-runnable function is analysed once per content and calls resolve late, by name, through the running runtime (`seon.sci.runtime`, owner 2026-10-19)." |
| 2.2 | same, lines 45-49 | "Everything else binds the compiled Var." / "a test reaching it only through compiled `src` callers is a deferred member of the merge (`seon.cluster.source/merge-members`), proven by the merge's platform run" | a/b | Drop both sentences. Replacement: "Selection follows the same rule: a test reaching a change through any SCI-runnable caller runs on the branch; a test reaching it only through a host-bound declaration is proven by the platform check (`seon.test.platform/check-files!`)." |
| 2.3 | `docs/seon/architecture/clusters-branches-contexts.md`, item 1 (lines 36-38) | "It is the one reality that runs compiled: a JVM holds one set of Vars." | keep (verify) | Still true for the loaded program. Confirm during rewrite of 2.1. |
| 2.4 | `docs/seon/plan.md`, performance section (~line 266) | "The cache of a branch's interpreted definitions (`seon.sci.eval/interpret-overlay!` per branch) is the first" | b | Retire the item. The first performance lane is the content-keyed analysis cache (`seon.cache`) shared by every branch. |
| 2.5 | `docs/seon/schedule/cards/ROOT-AGENT.md`, section 4 "Restart and adoption" (lines 142-144) | "every agent's SCI world reacquires from its branch head at each eval request and interprets the definitions that differ from the compiled program (`seon.sci.eval/interpret-affected!`, `:seon.fn/host-bound? false`). Compiled Vars catch up at the next" | a | "every agent's runtime is its branch's name-to-digest map, read at each eval request; a merged SCI-runnable definition is live on the next call, with no restart. Host-bound declarations change only through files and restart." |
| 2.6 | `docs/seon/schedule/cards/SCI-3.md`, redefinition cost | "The call in `seon.sci.eval/install-evaluated-rows!` (the `doseq` re-interpreting the reach of a definition that" | b | Retire: a redefinition no longer re-interprets callers (late name resolution). Re-measure the redefinition cost as one analysis per changed digest. |
| 2.7 | `docs/seon/schedule/cards/PLATFORM-STEP.md`, premise (lines 9-12) | "Today a branch cannot prove a host-bound or platform change. The compiled platform keeps running on every branch (CLAUDE.md \"The" | b | Narrow the subject to host-bound declarations only; SCI-runnable platform-looking changes are live on the branch now. Keep the card for the host-bound case. |
| 2.8 | same, "Branch evidence today" (~line 44) | "Its tests run the compiled Var, so they prove the old code." | b | Keep only for host-bound declarations; state that SCI-runnable branch changes are proven on the branch. |
| 2.9 | `docs/seon/schedule/cards/PLATFORM-STEP.md`, line 72 and 163 (platform run queue, asynchronous result) | "→ platform run (one at a time per process root; ...)" / "A finished platform run between requests could be a" | b | Keep as the host-bound proof queue; remove any implication that ordinary branch proof waits for it. |
| 2.10 | `docs/seon/architecture/architecture.md`, publication paragraph (~line 110) | "transact into an unpublished branch, use its report to identify affected callers" | keep | Publication lint, not the runtime model. Not in scope; no change. |
| 2.11 | `docs/seon/schedule/cards/RF-3.md`, lines 13-18 | "A branch defn of such a declaration keeps the compiled Var, so its branch evidence" | keep | Host-bound case, still true. No change. |

## 3. Issues (durable defect records; change only where the subject is moot)

| # | File | Exact sentence (verbatim, excerpt) | Class | Proposed replacement |
|---|---|---|---|---|
| 3.1 | `docs/seon/issues/affected-callers-fail-to-interpret-against-uninstalled-bindings.md` | "affected callers then fail to analyze. Each failure is a `:core` row refusal," | b | Subject moot (affected callers are not interpreted). Close with a pointer to the content-analysis runtime, or re-verify against it. |
| 3.2 | `docs/seon/issues/a-branch-forked-before-an-adoption-refuses-every-evaluation-after-it.md` (line ~61) | "Rows it has not changed run the loaded compiled Vars." | a | "Rows it has not changed resolve by name to their loaded digest; host-bound rows run compiled." |
| 3.3 | same file (line ~71) | "a form that calls the changed declaration uses P1's compiled Var." | a | "a form that calls the changed declaration resolves it through the branch's runtime." |
| 3.4 | `docs/seon/issues/an-agent-definition-gate-runs-the-host-test-that-quotes-its-symbol-nested-in-its-own-fixture.md` (line ~68) | "interprets differing rows and affected callers in SCI, and refuses unsupported overrides by name instead of" | a | "resolves differing rows by name in the branch's runtime, and refuses host-bound overrides by name instead of" |
| 3.5 | `docs/seon/issues/a-first-party-redefinition-gates-on-saturated-reach-interpreted-in-sci-and-is-refused.md` (lines 72, 81) | "The gate for a redefinition runs the tests that reach it through calls, as compiled Vars where" | a | Rewrite to the runtime wording: tests reaching the change run in the branch's runtime; host-bound callers run compiled. |
| 3.6 | `docs/seon/issues/development-adoption-can-mix-host-and-sci-generations.md` (line 19) | "context-local installation of affected callers." | a | "context-local resolution of names by digest." |
| 3.7 | `docs/seon/issues/cohost-start-races-the-reachability-sweep.md` (line 32) | "The earlier platform run over the custody source" | keep | Refers to a host-side run. No change unless runtime-wire renames it. |

## 4. Tasks (`resources/seon/tasks/*.edn`)

| # | File, task name | Exact sentence / subject (verbatim, excerpt) | Class | Action |
|---|---|---|---|---|
| 4.1 | `seon.sci.eval.edn`, `a-value-definition-in-a-changes-closure-is-bound-to-a-lazy-function` | "`seon.sci.eval/bind-lazy-members!` binds every member of a change's reverse closure to a function root (`seon.sci.eval/lazy-root`)" | b | Moot: lazy members are retired. Re-state the proof as "a value definition resolves by name in the branch runtime". |
| 4.2 | `seon.sci.eval.edn`, `incremental-install-leaves-callers-on-the-jvm-definition` | "install-row! installs an override's one entity and does not interpret its affected callers" | b | Moot: no affected-caller install. Replace with a late-name proof: a caller of a changed SCI-runnable definition sees the change at its next call. |
| 4.3 | `seon.sci.eval.edn`, `a-redefinition-walks-the-reverse-closure-with-its-test-reach` | "`seon.sci.eval/reinterpret-inherited-callers!` runs on every function write ... and calls `seon.fn/reverse-closure`" | b | Moot: the caller-closure walk for visibility is forbidden by the law. Close; any reach needed for test selection is a separate task. |
| 4.4 | `seon.sci.eval.edn`, `install-row-is-twice-as-slow-after-the-stale-var-fix` | "`seon.sci.eval/install-row!` warm self 452 us against main's 201 us" | b | Moot if `install-row!` is retired by P3. Re-measure the per-definition analysis cost instead. |
| 4.5 | `seon.sci.eval.edn`, `a-second-acquisition-of-an-unmoved-branch-interprets-nothing` | "Cache the interpreted fork as a seon.cache kind keyed [cluster-base-key, program basis]" | b | Moot: a branch's cost is its names plus uncached analyses. Re-state as a content-keyed analysis proof. |
| 4.6 | `seon.sci.eval.edn`, `branches-without-program-changes-add-no-overlay-entry` | "an empty overlay plan answers the cluster base ... as the fork source and writes no `:seon.sci.eval/overlay-context` entry" | b | Moot: no overlay plan. Close. |
| 4.7 | `seon.sci.eval.edn`, `promoting-a-hubs-closure-is-sub-second` | "`seon.sci.eval/interpret-affected!` of the seed `seon.id/digest` takes 2.9-3.4 s" | b | Moot: promotion and interpret-affected! retired. Keep the sub-second intent as a late-bound redefinition of a hub function (proof on the content analysis). |
| 4.8 | `seon.sci.eval.edn`, `a-fresh-fork-of-an-unchanged-program-acquires-from-the-base`; `the-program-context-is-a-value`; `a-settled-program-context-equals-its-rebuild`; `equal-programs-on-two-branches-share-one-program-context` | "`seon.sci.eval/program-context` (store, database value) answers the frozen program context" | keep | Base program values keyed by content; consistent with the new law. Re-check wording of "overlay" inside each. |
| 4.9 | `seon.test.platform.edn`, `a-host-bound-change-has-no-proof-from-the-repl` | "A branch's change to a host-bound function runs its tests on the old compiled Var" | keep (narrow) | Still true for host-bound only. Re-scope the subject to host-bound declarations. |
| 4.10 | `seon.test.platform.edn`, `a-test-the-branch-adds-is-not-eligible-in-the-check` | "`my.test/check-platform` cannot prove a test the branch adds" | b | Narrow to the host-bound check; SCI-runnable tests the branch adds run on the branch now. |
| 4.11 | `seon.flow.edn`, `an-interpreted-step-var-is-refused-on-a-branch` | "its interpreted affected callers include the agent arming path" | b | Premise is the old model ("interpreted affected callers"). Re-verify the `sci.lang.Var` refusal under the content runtime; the defect may remain, the diagnosis does not. |
| 4.12 | `seon.sci.branch-execution-test.edn`, `interpreted-callers-proof-expects-host-bound-callers-to-refuse` | "Since the ruling that a branch never changes the platform, install-row! binds an unchanged host-bound caller's compiled Var" | b | Rewrite the expectation: an unchanged host-bound caller runs compiled and does not refuse. The red is a stale assertion. |
| 4.13 | `seon.schema.edn`, `a-fn-schema-over-a-branch-predicate-is-refused` | "`seon.schema/malli-form?` resolves `:fn` predicate symbols against host Vars, not the evaluation's SCI context" | b | Owner decision: under late name resolution a branch predicate should be nameable. Re-scope the task to the runtime's name table. |
| 4.14 | `seon.fn.edn`, `tests-reaching-saturates`; `mention-closure-survives-beside-call-reach` | "`seon.fn/tests-reaching` took 1.9 s and 6.5 GB" | keep (re-verify) | Test selection by reach remains. Re-check whether `closure-walk` remains as selection only, not as visibility. |
| 4.15 | `resources/seon/help.md` | none | none | No statement of the old model found. |

## 5. Source docstrings and comments (`src/`, `script/`)

| # | File, symbol | Exact sentence (verbatim, excerpt) | Class | Proposed replacement |
|---|---|---|---|---|
| 5.1 | `src/seon/sci/eval.clj`, `acquire!` / `build-base-ctx` docstring (lines 82-88) | "Changed declarations and their affected callers interpret stored source; all other declarations bind the actual compiled Vars." | c | "Each SCI-runnable declaration resolves by name to the digest the branch holds, its analysis shared by content; host-bound declarations bind the compiled Vars." |
| 5.2 | `src/seon/sci/eval.clj`, `install-row!` comment (lines 1524-1526) | "A BRANCH NEVER CHANGES THE PLATFORM (owner, AGENTS ddd3cf9a2): ... sees a change only after merge and reload." | c | "A BRANCH'S CHANGE IS LIVE IN ITS WHOLE RUNTIME (owner, 2026-10-19). Host-bound declarations run compiled on every branch and see a change only after merge and reload." |
| 5.3 | `src/seon/sci/eval.clj`, `acquire-program!` docstring (lines 2732-2734) | "Compiled definitions bind their JVM roots; namespaces the JVM has not loaded interpret their source." / "Branch differences belong to `derive-overlay-plan`." | c | Remove the second sentence. Replacement: "Each declaration resolves by name to its digest; host-bound declarations bind their JVM roots." |
| 5.4 | `src/seon/sci/eval.clj`, `interpret-overlay!` docstring (lines 3323-3331) | "Interpret the overlay `plan` on `ctx`" | d | Whole function is old model; delete with P3. Its docstring is the mechanism's description. |
| 5.5 | `src/seon/sci/eval.clj`, `interpret-affected!` docstring (lines 3446-3455) | "A loaded platform caller is never re-rooted: it runs compiled and sees `changed` after merge and reload." | d | Delete function (P3). |
| 5.6 | `src/seon/sci/eval.clj`, comment in `install-row!`/`overlay` path (line 3210) | "promoting a member never touches its aliases" | d | Delete with `promote-member!`. |
| 5.7 | `src/seon/sci/eval.clj`, `install-row!` docstring/comment at 1396-1400 | "A file-derived row that differs is in the plan's interpret set (`derive-overlay-plan`)" | d | Delete with the overlay plan. |
| 5.8 | `src/seon/sci/arm.clj`, `arm!` docstring (lines 110-117) | "the tests reaching them are bound to run interpreted (`seon.sci.eval/interpret-affected!`)" / "a loaded platform caller stays compiled and sees it after merge and reload" | c | "its SCI-runnable callers and the tests reaching them see the definition at their next call; a host-bound caller stays compiled and sees it after merge and reload." |
| 5.9 | `src/seon/sci/arm.clj`, `behavior-held?` docstring (lines 91-95) | "every caller already reaches its behavior (a compiled caller the loaded or" | c | Re-word: "every caller resolves to the behavior it reaches by name". |
| 5.10 | `src/seon/fn.clj`, `source-callers` docstring (lines 2027-2031) | "a file-derived function of a `src` namespace runs compiled on every branch and sees a change only after merge and reload" | c | Host-bound only. Replacement: "`:branch`: the SCI-runnable callers a branch's runtime resolves by name. Host-bound declarations run compiled everywhere." |
| 5.11 | `src/seon/fn.clj`, `closure-walk` docstring (lines 2154-2160) | "so they and their callers call the compiled seed and cannot see the change there" | c/d | Delete the visibility claim; keep only test-selection reach. |
| 5.12 | `src/seon/fn.clj`, `selection-walk` docstring (lines 2183-2187) | "`::platform-tests`: through loaded platform callers too, proven by the merge's platform run" | d | Delete with the platform-tests concept (`::platform-tests` removed in P3). |
| 5.13 | `src/seon/fn.clj`, `reaching-tests` docstring (lines 2207-2210) | "whether a branch or only the platform run observes them (`selection-walk`)" | c | "Tests reaching the seeds, by call reach; the branch runs them all." |
| 5.14 | `src/seon/cluster/source.clj`, `merge-members` docstring (lines 980-983) | "`:seon.source/deferred` holds the members that run only on a platform host" | d/c | Delete the deferred concept (`:seon.source/deferred`) with P3; keep host-bound exclusions only. |
| 5.15 | `src/seon/cluster/source.clj`, comment (lines 952-953) | "reached only through loaded platform callers: the branch runs those compiled, so only a JVM loaded from the candidate observes the change" | c | Delete; SCI-runnable callers are observed on the branch now. |
| 5.16 | `src/seon/cluster.clj`, comment (line 2792) | "a deferred member with no platform run is unrun" | d | Delete with the deferred path in the merge-run (`:seon.source/deferred`). |
| 5.17 | `src/seon/schema.clj`, `register!`-area docstring (lines 2992-2995) | "compiled callers and the checked writer see it only after merge," | c | Re-word once 4.13 is decided: "the checked writer sees a branch's predicate by name in the branch's runtime; host-bound predicates see it after merge." |
| 5.18 | `src/seon/sci/kernel.clj`, docstring (lines 368-371) | "Compiled callers and the checked writer keep the database's projection: they see a branch's predicate only after merge" | c | Same as 5.17. |
| 5.19 | `src/seon/test.clj`, `clean-request-handle`-area comment (lines 2336-2339) | "Its context interprets those definitions over the loaded program (`seon.sci.eval/base-ctx`), so a test" | c | "Its context resolves those definitions by name over the loaded program; a test the branch inherits calls the branch's callee." |
| 5.20 | `src/seon/test.clj`, comment (line 2414) | "candidate's changes interpreted on a fork (`sci.eval/base-ctx`, one" | c | "candidate's changes resolved on a fork of the base runtime." |
| 5.21 | `src/seon/instrument.clj`, wrapper comment (lines 1060-1063) | "Other branches' differing entities interpret in SCI." | c | "Other branches' runtimes resolve this name by its digest in SCI." |
| 5.22 | `src/seon/turn.clj`, `platform-proof` docstring (lines 5324-5328) | "The compiled platform runs a host-bound declaration on every branch, so those tests prove nothing in SCI." | keep | Still true (host-bound). No change. |
| 5.23 | `src/seon/cluster/branch_evidence.clj`, `platform-evidence?` (lines 102-104) | "Whether `test-symbol`'s latest result on `branch` ran in a platform run" | d | Host-bound evidence only after P3; delete the SCI-runnable use. |
| 5.24 | `src/my/test.clj`, `check-platform` (lines 113-118) | "(into (vec (:seon.fn/tests selected)) (:seon.fn/platform-tests selected))" | d | Delete the platform-tests union with P3. |

## 6. Code implementing the old model (class d; P3 deletes, not changed by this lane)

- `src/seon/sci/eval.clj`: `promote-member!` (3182), `lazy-root` (3241), `bind-lazy-members!` (3268), `without-reinstalled` (3298), `unbind-reinstalled!` (3311), `interpret-overlay!` (3323), `interpret-affected!` (3446), `program-changes` (3472), `overlay!` (3486), `empty-overlay-plan` (3069), `fold-overlay-plan` (3073), `derive-overlay-plan` (3109), `overlay-plan` (3116), the `plan` delay (~4154) and `overlay!` call (~4021). Also `install-row!` (1459) and its lazy/host-bound branch, and the eager set from `bind-lazy-members!` at ~2066.
- `src/seon/sci/arm.clj`: the `interpret-affected!` call (149) and its reinterpretation branch.
- `src/seon/fn.clj`: `source-callers` (2027), `walk-callers` (2047), `nearest-tests` (2098), `closure-walk` (2148), `reverse-closure` (2170), `selection-walk` (2183) with `::platform-tests`, `reaching-tests` (2207), `gate-sets` (2219) on `::platform-tests`.
- `src/seon/cluster/source.clj`: `merge-members` platform and deferred construction (952-963), `:seon.source/deferred` consumers (1493, 1501-1504).
- `src/seon/cluster.clj`: deferred split and platform run (2782-2795).
- `src/seon/context.clj`: `deferred` (444, 462).
- `src/seon/turn.clj`: `deferred` member split (5507); `platform-proof` (5326) is host-bound and stays.
- `src/seon/test.clj`: `host-exclusions` (2458) and `isolated-members` (2631) are host-bound/destructive and stay; the `::platform-tests` consumers and `sci.eval/base-ctx` candidate path (2414-2421) are old model.
- `src/seon/cluster/branch_evidence.clj`: `platform-evidence?` (102).
- `src/my/test.clj`: `check-platform` union with `:seon.fn/platform-tests` (113-118).

## 7. Notes for the owner

- Nothing in `docs/research/agent-platform/2026-*` is listed (dated evidence, excluded by the brief). Undated files in
  `docs/research/agent-platform/*.md` (e.g. `task-model-synthesis-2026-09-24.md`) were not reviewed line by line; they
  describe past designs and should be left as history unless a runtime-wire doc cites them as current.
- `docs/seon/architecture/vocabulary.md` (program context) and `docs/seon/architecture/architecture.md` (publication lint)
  were checked for the old model and not found to state it; no row is listed.
- The reach-based test selection (`seon.fn/tests-reaching`, `closure-walk` for selection) is NOT retired by the law;
  only the caller-closure walk used to make a branch see its own change is. Rows 4.14 and 5.11 keep this distinction.
- Many issue records and tasks name the old mechanisms as defects already fixed or moot; this lane lists only the
  statements and tasks whose subject the new model changes.
