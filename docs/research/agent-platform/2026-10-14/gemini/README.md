---
title: "Gemini lanes: model evaluation, performance hunt, backlog root causes"
type: research
status: active
---

# Gemini lanes, 2026-10-14: model evaluation, performance hunt, backlog root causes

Run through `bin/agy-agent` (agy 1.3.5). The reports here are the lanes' own output; every claim below that the plan
relies on was reproduced by root against the code or the live JVM, as noted.

## Model evaluation (blind tests with known answers)

| Test | Known answer | Results |
|---|---|---|
| A: repeated work behind a measured symptom, code at `17ec81f0b^` | `seon.schema.datahike/compiled-attribute-selection` recomputes; fix with `seon.schema/projection-cache-value` | all right: Argon-medium 4.4 min; Flash-low 1.5 min, -medium 2.4 min, -high 4.4 min (only Flash-high keyed by registry and forms, as the real fix did) |
| B: judge the Fressian attribute cache of `f18ab95e3` before measurement | measured slower, reverted in `c2284b109`; its 1000 → 1 count a proxy | both "drop": Argon-medium 8.5 min (read Fressian's bytecode with `javap`), Flash-high 6.5 min; the premise (`clojure.data.fressian/write-named` already caches keyword strings) checked in the jar |
| C: audit `docs/seon/schedule/cards/TASK-CHAIN.md` against the code | none; findings reproduced | Argon-medium 8 findings, one with invented code excerpts; Flash-high 11, quotes real (new: `my.root/run!` described as present, does not exist) |
| D: F53 live-lock from its symptom, code at `0c684db75^` | `seon.turn/first-proof-run-t` joins `:seon.test.run/identities`, which reach-selected runs never write | Flash-high exact in 7 min; Argon (high, resumed on low) exact in about 14 min; Argon-medium exact in 18.6 min |

Intuition (in the agy-lanes skill): Flash-high is the default; it reads widely and quotes what it read. Argon is for
long open-ended synthesis. Argon fills gaps from memory; Flash over-classifies. Both propose against design rules the
brief does not name, and both invent target bounds.

## Performance hunt (Argon-medium, live JVM, cumulative profile in `profile-since-boot.edn`)

- `perf-request.md`: a trivial JVM eval request is about 7,900 instrumented calls and 40-48 ms, reproduced by root:
  `seon.config/effective` 7 times, `seon.print/emit-both` twice, `seon.cluster.agent/acquire-context!` once, and the
  recording transaction about 41 ms of it. Its first proposal (skip the recording transaction on read-only requests)
  contradicts AGENTS.md and is rejected. It found that `seon.profile/summary` costs about 88k instrumented calls
  because the profiler's own predicates carry contracts, which inflated root's first measurement 15-fold.
- `perf-analysis.md`: `seon.fn.analyzer/attributed-usages` scans every var-quote of a file per usage (O(usages ×
  quotes); 80.6M `contains-position?` calls since boot), confirmed in the code; `seon.cluster/rehearse-source!` and
  `seon.fn/analyzed-artifacts` re-derive the whole program (1,557 s and 669 s since boot), to measure.
- `perf-schema.md`: `seon.program/authored-shapes` keys `seon.cache/lookup` by the whole packaged forms map (confirmed
  in the code; 615k calls); `seon.cache` at 511.99 of 512 MB with 1,568 projection evictions while ten 105 MB
  declaration projections are held; `seon.schema/projection-cache-value` called 47M times from per-datom attribute
  checks in `seon.db/write-attribute-plan`. The analysis lane found the same `authored-shapes` key independently.

## Backlog root causes (Flash-high over 872 tasks)

`rootcause-flash-high.md`: nine clusters retiring 51 tasks, eight tasks asking for fences, 25 "moot" tasks. Root
checked the moot list and it fails: tasks whose namespace is absent mostly ask for it to be built (the red proof
`seon.census-test/a-docstring-census-reads-the-branchs-database` resolves a `seon.census/docstrings` still to write;
likewise `seon.html-views`, `seon.dev.hook`); the five `seon.step` entries are the chain templates `seon.task` and
`seon.task-test` read; the three `seon.test` selection tasks name `test/seon/test/selection_test.clj`, which exists;
four are deletion tasks not yet done (`seon.db/deletion-error-has-no-caller`,
`seon.db/transaction-stamp-attributes-outlive-their-producer`, `seon.turn/eval-origin-has-no-producer-or-consumer`,
`seon.turn/row-tx-is-a-second-declaration-writer`). Only `seon.turn/receipt-attributes-are-a-hand-list` is confirmed
moot. The nine clusters are unverified.

## Worktree pitches (brief `worktree-pitch-brief.md`; Flash-high 5.2 min, Argon high resumed on low 9.3 min)

Both recommend no per-agent git worktree: the Datahike branch and its SCI context are the agent's workspace, and files
are written only when root merges (`seon.merge/publish!`). Measured: a worktree takes 0.76-0.83 s and 217 MB (467 MB
with `reference-code/`); in it the edit hook crashes (empty submodules) or skips every check (`repository-file?`
tests the main checkout's path), and clj-kondo starts cold (3.0 s against 0.02 s). Argon names the two gaps that
block parallel work, both confirmed by root in the code: `seon.test/recorded-result` reads results only from the
calling branch's database, and the `::changes` key in `seon.test` includes the branch name beside its content digest,
so a test green on one agent's branch reruns on every sibling; and an outside agent's file edit dirties the shared
checkout until it is committed, which is the only reason `bin/lane` claims exist.

## Backlog root causes, Argon (high resumed on low, 22 min)

`rootcause-argon.md`: 13 clusters said to retire 199 tasks, 38 "moot". Its counts fail verification: it reports 538
generated contract tasks where the task files hold 14, and 7 of the 12 namespaces it calls deleted exist
(`seon.sci.lookup`, `seon.test.accretion`, `seon.fault`, `seon.operator`, `seon.effect`, `seon.problems`,
`seon.bootstrap`). Across both models, whole-backlog clusters are hypotheses about producers; their counts and every
"subject is gone" claim are checked before any task is retired.
