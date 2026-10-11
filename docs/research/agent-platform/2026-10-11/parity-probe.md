---
title: "Parity probe after the fence revert, 2026-10-11"
type: research
status: evidence
created: 2026-10-11
---

# Parity probe after the fence revert, 2026-10-11

Lane `parity-probe`. Question: after the fence revert (c53c9306e) and the database reset, does one DeepSeek-flash
agent still take one task red → green end to end, launched the way chain runs 3–5 and the
[loop rounds](../2026-10-10/loop-rounds.md) were (`seon.competency/start-run!`, then `await-runs` and `grade`, from root's JVM
REPL through MCP `eval_clj` on `default`)? With the auto-land dial off, a green end should record a merge request and
stop there.

**Answer: yes, for the loop's mechanics.** The bench task went green in 2 calls and recorded a merge request without
landing. The real-code task picked first was a contract task. It capped at 15 calls without writing a `defn`. That
says the contract-task class is hard, not that the loop regressed. No new-code task of the chain-run-5 class
(an ordinary behaviour fix, its proof red on main, its reaching tests green, no schema change) was open after the
reset.

## Before launch: the running default did not have the dial off

- Default ran archive c53c9306e (from `bin/seon status`). The dial-off commit f43646710 is not an ancestor of
  c53c9306e, so `(seon.config/effective db "default")` answered `:seon.config.root/land? true`. A green end would
  have landed and committed into the checkout by itself.
- `bin/seon config apply default config/default.edn` (HEAD's document, which differs from c53c9306e only in this
  dial) was refused with `seon.config/apply!` "expected an integer at least 1, got :seon.config/available-processors".
  `seon.config/apply!` compares the file with the running JVM's own shipped document. HEAD's differs, so it is read
  as a sparse overlay, and the overlay path does not accept smart values. The behaviour is as designed. Operationally,
  a committed dial change does nothing until a restart.
- Applied instead, from the JVM REPL:
  `(seon.config/apply! {:seon.db/connection conn :seon.boot/cluster-name "default" :seon.config/manifest {:seon.config.root/land? false}})`
  answered `{:converged? false :operations 3}`, and the effective config then read `land? false`. The cluster handle
  in `running-instances` has no `land?` key of its own, so `seon.turn/agent-dial` reads the database value.
  `land?` is not a per-agent dial, so an agent's settings cannot override it.

## Choosing the task

- After the reset no test results are stored: `seon.context/proofs` answers `{:pending 1}` for any task. Red-on-main
  candidates were taken from the compiled full tier after the revert (`revert-after.edn`, c53c9306e) and joined to
  `:seon/tasks` tags in `test/`. Thirty-seven tagged proofs were red. Every one is a census, platform, schema-carrying,
  bench or generated contract task. The round-1 tasks (path-form, search-limit, edn-symbol-text, search-snippet) are
  merged on main, so their proofs cannot be made red again without a fake.
- Picked first: `seon.render.transcript/contract-runtime-duration`, a 13-line private pure function. Its proof is
  `seon.contract-coverage-test/runtime-duration-is-contracted`, which reads `seon.contracts/gaps` in the executing
  world, so a branch `defn` changes it.
  - Red on main: run 8434b6303838, compiled as root, one finding, `:missing-spec`.
  - Tests reaching the function, green on main: run 64a30b0a807b, `seon.render.page-review-test/turn-headers-…`
    (22 pass) and `seon.render.runtime-test/runtime-html-…` (28 pass).
- Second, cheap and deliberate: `seon.competency/slug-removes-edge-hyphens`, the chain-run-3 bench task. Its setup
  makes it red on every run. I added it to answer the parity question once the first run turned out to measure the
  task class instead.

## Run 1: `parity-duration-1` (contract-runtime-duration), CAPPED

Settings: `{:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 15}`, DeepSeek flash, branch
`:agent-569ee40d02e4`.

| | |
|---|---|
| Start (setup, assignment, wake) | 1.1 s |
| Launch to settled | 32.8 s; model wall time 27.2 s |
| Calls | 15 (capped); attempts 15; forms 29; calls with no form 2; repeated forms 1 |
| Tokens | 172,818 prompt (134,400 cached), 2,403 completion |
| Errors | 12 agent, 0 platform |
| Proofs | 1 red |
| End | message to root `:seon.task/end :capped` (task cde64280edb8); `seon.context/ended` derives calls, tokens, red proofs and errors by signature |

The agent never wrote a `defn`. In order, it:

1. read the contracts guide twice, the source, callers and two callers' sources;
2. ran `seon.contracts/gaps` (useful) and `seon.fn/contract-findings` twice with the wrong arguments;
3. spent about ten forms looking for a schema that checks an instant:
   - listed the registry's keys with keyword-only filters, which hit `ClassCastException` twice and a wrong-arity
     `filter`. The registry mixes symbol keys (predicate schemas such as `inst?`) with keywords.
   - tried `(malli.core/validate :inst …)` and `:seon.render.transcript/instant`. Each answered only
     `":malli.core/invalid-schema"`.
   - guessed `seon.schema/resolve` and `seon.fn/source`.
   - called `my.fs/glob` with a string.

The schema it needed is `inst?`. Default's own contract errors print it (`:expected inst?`). Nothing pointed the
agent to it.

Errors by signature (from `seon.context/ended`):

| Signature | Count | Pattern |
|---|---|---|
| af68c49f2db0 | 3 | invalid-schema |
| 0d969d7bd8a8 | 2 | Symbol → Keyword cast |
| 4e594642bca0 | 2 | unresolved `seon.fn/source`, `seon.schema/resolve` |
| 432986f4d989 | 2 | no form |
| c1edfc847dc8, d391113e6f90, 6d5ac3a53f7e, 1f965cc97d93, 9c0a629fe615 | 1 each | `true?` cast, `filter` arity, `glob` string, `contract-findings` arguments ×2 |

## Run 2: `parity-slug-1` (slug-removes-edge-hyphens), GREEN, merge request recorded, not landed

Branch `:agent-542491f081bb`.

| | |
|---|---|
| Start | 1.4 s |
| Launch to settled `:done` | 7.4 s; model wall time 1.8 s |
| Calls | 2 (to green 2); forms 3: `(my.program/source 'comp.text/slug)`, `(in-ns 'comp.text)`, the `defn` |
| Tokens | 3,006 prompt, 142 completion |
| Errors | 0 agent, 0 platform; 0 repeated forms |
| Proofs | 1 green |

Chain run 3 on the same task before the revert: 1 call, 4.7 s, 1,337 prompt tokens, 0 errors.

What root sees to decide the merge:

- **The end message** to root (981c85273ea5): `:seon.task/end :done`, `:seon.task/merge "e121213ad63d"`.
- **`seon.context/ended`**: calls 2, tokens, proof counts `{:green 1}`, red proofs `[]`, the branch, and the next
  operations (`model-context` on the branch, `(my.program/prepare-merge! {:seon.task/merge "e121213ad63d"})`,
  `seon.task/create!`). No landing keys, as expected with the dial off.
- **The merge task** e121213ad63d: `:seon.task/kind :merge`, created by the agent, intent "Merge branch
  agent-542491f081bb's change for 39bc380f6a79".
- **The proposal row**: branch, commit 6acb4748…, `:seon.task/proved [[seon.competency-test/t5-slug-removes-edge-hyphens
  <digest>]]`, program digest, and run id 9e1a613bbcdb.
- **Missing**, as plan §0 item 8 already says: the diff since the merge base, and every reached test with its result.
  Root has to derive them (prepare-merge!, or the branch's evaluations).
- **Defect**: run 9e1a613bbcdb is recorded neither on the branch (which holds runs 26ea3d2f852c and 4e2f9cc0d2af)
  nor on main. Reproduced as root by running the same `my.test/check` twice: e049515f2437 was recorded, then
  e4c192b659d6 was recorded nowhere. A reused answer carries a fresh id that nothing records. Filed below.

Root has not merged the merge request (it is a bench fixture). Both branches are kept for review or reflection.

## Compared with the runs before the revert

- **The loop mechanics are back to parity.** Assignment, wake, test step, green end, one end message, merge
  request recorded, admission closed, `ended` derived. The capped end also reached root with its summary.
- **Run 2's cost is close to chain run 3's.** It took one extra call (the agent read the source first) and about 2×
  the prompt tokens.
- **The capped run looks like the failures of rounds 2–5 on the loop's own instrument tasks.** It met the tool-misuse
  class (`my.program/callers`- and `source`-style argument errors) recorded in round 3. It also met a new class:
  schema discovery for a contract.

## Filed (red proofs; committed red, check named the ingestion test)

- `seon.test/a-reused-answer-names-a-recorded-run`. Proof:
  `my.test-check-test/a-reused-check-answers-a-run-id-it-recorded`. Red at default's REPL: the second id was
  recorded nowhere.
- `seon.error/a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas`. Proof:
  `seon.error-test/a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas`. Red at default's REPL:
  `reply-value` has no `:seon.error/nearest`.

## Not filed (known or by design)

- **Running default lags HEAD (archive c53c9306e).** The dial-off, item 9's guessed-name answers (5fcbf5ed8) and
  later fixes are not live. RESTART NEEDED for root's batch.
- **A green `my.test/check` answers only id, `passed?` and elapsed time, with no counts.** Run 64a30b0a807b ran 2
  tests but the answer did not show how many.
- **JVM-mode cost diffs label compiled host functions `:interpreted :new`.** This is plan §0 item 10 and the
  chain-run-5 cost-diff finding.
- **The merge request does not carry its diff or its reached tests.** This is plan §0 item 8.
