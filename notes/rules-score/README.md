# Rules as a score: a lab experiment

This experiment asks whether AGENTS.md's rules can become an explicit score: points for reuse and merged green work, a cost for growth and waste. Scored against the 31 merges into main since 620fb31dda, plus 7 earlier merges that carry a self-review rating, the score agrees with the known judgments 10 times in 14, with a pairwise concordance of 0.64. Four rules account for the four disagreements. Lab code only. The score is computed from git and the run records each time it runs, and is stored nowhere.

- `rules.edn` holds the rule table: each rule's id, its AGENTS.md sentence, weight, cap and measure.
- `score.clj` computes it: `bb notes/rules-score/score.clj [REPO] [MEASURE-DIR]` (about 12 s).

`git log --merges 620fb31dda..main` lists 81 merges, but 50 of them merge main into a branch. The 31 first-parent merges are the merges into main, and those are the ones scored.

## Two kinds of rule (owner, 2026-10-26)

**Enforced: a gate that refuses the merge outright, with no weight.** A gate that carried weight could be traded for points somewhere else.

| rule | why it is a gate |
|---|---|
| main's tests stay green on the merged tree | Old tests run against the merge. Deleting a test does not stop it from running, so a removed test needs no score: the gate catches it ("removed tests" column, shown for information only). |
| laws (`bin/laws catch`, `clocks`, `resolution`) | An unnamed site turns bin/prove red. |
| every contract generates (`mg/check`) | A bin/check step. |
| cost proofs | They are tests inside the prove gate. |
| pins verified (`git ls-tree`, `gh api`) | The merge session refuses an unpushed pin. |

**Scored: weights over facts, each with a cap.** "Diff" below means the first-parent diff.

| id | measure (fact) | weight | cap |
|---|---|---|---|
| outcome/merged-green | merge-log or message records a green prove | +30 | 30 |
| outcome/reverted | a revert on main names the merge | −40 | 40 |
| outcome/added-forms-survive | share of the merge's new defs that still exist on main, s → 30·(2s−1) | ±30 | 30 |
| outcome/deletions-stick | defs deleted and still absent on main | +1 | 15 |
| reuse/calls-existing-vars | distinct pre-existing Seon vars that the added lines reference | +0.5 | 15 |
| reuse/improve-not-add | changed defs minus new defs | +0.5 | 15 |
| growth/net-src-lines | net src lines | −0.05 | 20 |
| growth/new-namespaces, config-keys, schema-attrs | net new src files, config keys, schema attributes | −5, −2, −1 | 20, 10, 10 |
| growth/names-what-it-replaces | src grows and the message names no deletion | −8 | 8 |
| proofs/added-with-change | new deftests | +2 | 12 |
| tests/merged-with-a-red | merge-log records a RED at merge time | −10 | 10 |
| laws/new-catch, new-clock, dates-in-src | net added sites (the gate decides; this prices them) | −3, −3, −1 | 12, 12, 5 |
| laws/fix-in-dependency | pin bumps (a cause fixed in its dependency) | +3 | 6 |
| run/green, turns-to-green, capped, repeats, errors | run outcome; green: 20 − turns, otherwise −turns; repeated-call turns; turn errors | +30, ±1, −10, −2, −1 | 30, 20, 10, 10, 10 |

Approximations:
- Reuse is a token match, not the compiler's `:form/refs`. It counts qualified `alias/name` tokens whose name is a def in the parent tree, plus bare tokens that name a def of the same file. A common name (`run`, `status`) can over-count.
- Forms are split at lines that start with `(`.
- Clock reads match `System/currentTimeMillis`, `System/nanoTime`, `Thread/sleep` and calls to `timeout`.
- The repeats for whole2 were never recorded, so they show as `:not-measured` and score 0.

## Merges, ranked (excerpt; the full table is the script's output)

| rank | merge | score | net src | new/changed/gone defs | survive | judged |
|---|---|---|---|---|---|---|
| 1 | 9c47ace0b8 view-parts | 110 | −65 | 13/22/17 | 1.00 | **low** |
| 2 | 830ef818e8 model-reads-the-page 4.2 | 108 | −107 | 15/68/20 | 0.80 | high |
| 3 | 6929ca4e93 ctx-atom (no derived data) | 81 | −53 | 6/18/6 | 0.67 | high |
| 4 | f1ef540137 agent-loop slice 6' | 77 | +157 | 10/17/0 | 1.00 | |
| 5 | b0df4826e2 an-asker-records-its-effects-error-once | 73 | +51 | 1/13/0 | 1.00 | |
| … | | | | | | |
| 34 | 330ab1b3f0 ctx-atom 4.1 derived members | 21 | +44 | 4/10/2 | **0.00** | low |
| 35 | d2eab023ef a-watcher-is-ready-when-registered | 5 | 0 | 0/0/0 | – | |
| 36 | b482fcdd35 cost-bounds-lab (notes only) | 0 | 0 | | | |
| 37 | f564b314c4 template-plan-text (task file only) | 0 | 0 | | | |
| 38 | 98c6b97c0c delete-stages | −35 | −39 | 0/3/1 | – | low (reverted) |

## Agreement with the known judgments

The split is at the median score of 37. **10 of 14 agree.**

| merge | judged | score (rank of 38) | agrees |
|---|---|---|---|
| 830ef818e8 model-reads-the-page 4.2 | high | 108 (2) | yes |
| 6929ca4e93 ctx-atom | high | 81 (3) | yes |
| fd5fe513f4 prove-key (worth it) | high | 72 (7) | yes |
| 11d34aacd5 context-render-observes (worth it) | high | 62 (10) | yes |
| b8c6180959 request's one path | high | 60 (11) | yes |
| 0595c749d7 a-platform-error-closes (worth it) | high | 59 (12) | yes |
| fbb279900d delete-dead-merge-path (worth it) | high | 43 (17) | yes |
| 59a742ee30 view-reads-story | low | 35 (22) | yes |
| 330ab1b3f0 ctx-atom 4.1 derived | low | 21 (34) | yes |
| 98c6b97c0c delete-stages | low | −35 (38) | yes |
| 9c47ace0b8 view-parts (the parts layer) | low | **110 (1)** | **no** |
| 79a2aa9a5e stability (questionable) | low | **66 (9)** | **no** |
| 205b0dc41d a-cost-proof-is-the-same-under-load (worth it) | high | **37 (20)** | **no** (on the median) |
| a2b0f78892 mcp-check (worth it) | high | **30 (30)** | **no** |

Most review ratings come from before 620fb31dda. Inside the range, the merge log records none, so the in-range judgments come from the merge log's own notes (superseded, reverted) and from docs/decisions.md.

### Why each disagreement happens

1. **view-parts ranks first.** Survival reads 1.00 because the parts layer is still on main: `parts-page`, `part-faces` and `agent-part` are in render.clj, ui.clj and render/proc.clj. The deletion has been ruled (b20d48ab82, decisions.md) but not yet carried out. The size rules also pay for churn: 77 reused vars, 22 changed defs and 17 deleted defs collect 45 of the 110 points, and the merge deleted the old screen round trip while adding a second system beside render. Two rules are wrong here:
   - *reuse/improve* counts touching code, not improving it.
   - *survival* sees only deletions already carried out. It needs a "ruled against" fact: a ruling in decisions.md or the plan that names the merge's mechanism.
2. **stability ranks 9th.** It merged green and changed many forms. "Questionable" was the self-review's rating, and three defects were fixed afterwards. **Missing rule:** the self-review's findings count and rating. The rating is a recorded fact, so the score should read it rather than infer it.
3. **The cost proof (205b0dc41d) sits on the median.** Its value is that a cost proof stays the same under load, which is a gate made trustworthy, and no rule measures that. **Missing rule:** measured value, meaning a before-and-after number in the commit (ticks, bytes, turns to green, reds fixed).
4. **mcp-check (a2b0f78892) ranks 30th.** It is test-only and turned a red `bin/check mcp` green. **Missing rule:** "fixes a red on main" should score close to a merge. There is also a mirror defect: **tests/merged-with-a-red penalized fbb279900d** for the durable red that main already had ("equally red at 85df832c2"). The rule must count only the reds the merge caused since its merge base, which is health's rule.

The bottom also shows a fact gap. d2eab023ef (watcher) scores 5 only because its verdict was not yet in the merge log and its message carries no "prove OK". An outcome rule must read a recorded verdict. "Not recorded" is unknown, not zero.

## Live runs

The run set covers 34 model runs in staged2, whole2 and db1. Bookkeeping runs with 0 turns are left out.
- Green runs score from 14 to 41, led by help at 8 and 9 turns and message/eval at 9 and 11 turns.
- **The four stale db1 requests** (transport, proof-never-ran, long-name, map; 112 turns) rank 15, 27, 32 and 33 of 34. Their mean is −34.5, against −11.1 for all runs. **That agrees with "low".**

Two caveats:
- The non-stale capped runs score just as low. The score cannot tell "nothing to do" from "context failure", and it blames the agent for the request set's staleness.
- Transport's correct `fail!` at turn 22 scores −23, almost as low as a cap.

**Missing rule:** check the premise. When the request's proof was already green at the run's base, `fail!` within about 5 turns should score as a green; every turn past that is waste charged to the request set, not to the agent.

## Gaming

| rule | how it is gamed | what stops it |
|---|---|---|
| reuse/calls-existing-vars | Split a function in one merge, call the pieces in the next. Or reference many vars from a wrapper. | Count only references from added forms that survive. Score reuse per form, not per merge. A new var gives nothing until a later merge reuses it, and splitting adds new defs (−) first. |
| reuse/improve-not-add | Rewrite many forms cosmetically (churn). | Pay for a changed form only when the merge also shows a measured value or a deletion. Normalize by size. Keep the cap. |
| outcome/deletions-stick | Delete dead or duplicate code you added yourself earlier, and collect twice. | Pay only for defs that existed before the session's first merge, or net the earlier addition against the deletion. |
| outcome/added-forms-survive | Add nothing new (inline everything), or rename to dodge being counted as "gone". | Track survival by `:form/refs` identity rather than name. A rename that keeps the callers counts as survival, and inlining is caught by growth. |
| growth/net-src-lines | Move code into native/, test helpers or resources, or pack it onto long lines. | Count forms and bytes over every compiled source root, not lines in src only. |
| growth/names-what-it-replaces | Write "replaces X" in the message. | Check it: X must be a def or namespace the diff deletes. |
| growth config, schema, namespaces | Hide a setting as a literal. | Already enforced: no literals, config only (§5). |
| proofs/added-with-change | Add trivial or placebo deftests. | The test must be red at the merge base and green at the merge (a red-then-green check on the two trees). Mutation sensitivity is §5's "good tests only" rule. |
| tests/merged-with-a-red | Avoid recording the red. | Read the verdict from the prove run's record, not from prose. Reds only by merge-base comparison. |
| removed tests | Delete a failing test to look green. | Enforced, not scored: main's tests run on the merged tree. |
| laws | Hide a catch in a dependency, or name the site on the page so the law passes. | The gate already lists named sites. A named site still costs points, because the score prices it. |
| run/turns-to-green | Pick easy tasks, or split one task into many. | Score per request (the request's tasks summed), and weight by the task's proof count. |
| run/repeats, errors | Vary a call trivially so it is not "equal". | Compare observed state (`:same-observed`), not call text. Waste in tokens is harder to dodge. |
| outcome/merged-green | Merge tiny no-op branches for +30 each. | Pay the merge outcome only for a task whose proofs were red at base. Notes-only and pin-only merges get the dependency rule and nothing else. |

## Verdict

The score works as a lens, not yet as a target. It ranks deletions and the reverted merge correctly, and it finds the stale runs. Its size-driven reuse rules would have ranked the second system first. Before any agent sees it, the three rules to change are:
- reuse/improve, which today pays for churn;
- survival, which needs ruled deletions as facts;
- tests-red, which needs merge-base attribution and a "fixes a red" credit.
