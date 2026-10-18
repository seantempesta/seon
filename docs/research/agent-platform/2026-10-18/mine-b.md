---
title: "mine-b: model turns to green on four chains, wasted turns by producer"
type: evidence
status: "lane mine-b (Haiku), 2026-10-18, JVM mode on default (HEAD 7a38c6e22 era data); evidence only, no source or task changed"
---

# mine-b: where the model turns went

Lane mine-b (Haiku). Read-only: JVM mode on cluster `default`, with `(seon.db/read-at main :chain-…)` on each kept chain
branch. No SCI request was made on default. Scratch output is in `tmp/mine-b/` (`situations.edn`, `flat-*.txt`,
`errs-d6c6.txt`, `judge-142f.txt`).

## Corpus used

1. Live run 9's three chains (named in `live-run-9.md`): `:chain-d6c6977d38fd` (fork point, capped red),
   `:chain-8c4f98deb037` (unresolved-symbol reply, merged), `:chain-ae969b87b62b` (root seed, sent back).
2. The context judge's chain `:chain-95da3ba029e7`, attempt `attempt/142f07c37ddb`. Its friction log is
   `context-judge-1.md`.
3. Other branches: not used. The corpus above was enough.

## Tool findings that limit this report

- **`seon.mine/situations` returns empty for the judge's chain, silently.** `:chain-95da3ba029e7` is a kept chain and
  its attempt `142f07c37ddb` has 14 model calls and 15 forms, but `seon.mine/model-calls` filters on
  `:seon.turn/attempts`, and that attribute is absent on every call of that branch (0 of 276 calls; the run-9 branches
  have 17 to 40). The miner answers `[]` and does not refuse. This is the miner gap behind the lane's note on task
  `seon.mine/a-cause-producer-from-an-agent-definition-refuses-the-miner`. The judge chain was therefore read by hand
  (`judge-142f.txt`), not with the miner.
- **`seon.competency/grade` and `seon.competency/classify` do not exist in this repo** (grep finds no `ns seon.competency`).
  The classes used here are `seon.mine/classify` and the `productive` set `#{:read :probe :progress :wait}` from
  `seon.mine`. Mine counts a probe as productive and navigation (`in-ns`) as not productive. Both choices are kept as
  the miner has them; the hand judgement below says where they differ.
- `AGENT.md` does not exist in this checkout; `AGENTS.md` was read.
- Refused evaluations (for example the judge's second `current-state`, refused `admission-closed`) are not stored as
  evaluations, so they are invisible to the miner and to this table.

## Per chain

Turn = one model call. "Productive" uses the miner's rule (a turn with a read, probe, progress or wait). "Wasted" is
the miner's rule plus my hand judgement where it differs.

| chain | task | turns | productive (miner) | wasted (miner) | wasted (hand) | turns to green | end |
|---|---|---|---|---|---|---|---|
| `8c4f98deb037` | unresolved-symbol reply | 17 | 16 | 1 (T5) | 2 (T5, T8) | **17** (green at T16) | done, merged |
| `d6c6977d38fd` | fork point | 40 | 29 | 11 | about 17 (11 plus 6 guessed or fixture-replay probes) | none | capped red |
| `ae969b87b62b` | root seed | 23 | 16 | 7 | 7 | none | sent back at T22 |
| `95da3ba029e7` / `142f07c37ddb` | judge: `ns-render-tests-are-red-on-head` | 14 | 8 | (miner empty; hand) 6 | 6 (T1, T6 to T10) | **14** (green at T13) | done; merge refused twice |

The judge chain's 14 turns contain 15 forms. The judge's own count of 16 included two refused calls. Its merge was
refused as `:seon.source/test-evidence-error` twice (root step, not a model turn; see row J-note).

## Wasted turns, one row each

Form excerpts are under 200 characters. "Fixed" means the producer changed in a named commit; the fix is recorded
but not re-measured here.

### Chain 8c4f98deb037 (unresolved-symbol reply)

| turn | form (excerpt) | class | producer | evidence |
|---|---|---|---|---|
| T5 | `(in-ns 'seon.error)` then `(my.program/source 'seon.error/reply-value)` | re-read | unsure: the reply-value source was shown in full at T3 (2,667 tokens, 0 elided) and the agent read it again | `shown` at T3, `value-tokens` 2667, `elisions` 0 |
| T8 | `(seon.db/q '[:find [?s ...] :where [?f :seon.fn/sym ?s] [?f :seon.fn/arglists _]] (seon.db/db))` | search (hand query of the database) | missing fact: no verb in the block lists the candidate symbols with their arglists; `my.program/search` is by name only | the form is the only hand `seon.db/q` in the chain |
| T6 | `(my.program/source 'seon.error/name-tokens)` | re-read (shown at T1) | unsure (same as T5: a shown value is not kept in the next turn's context) | T1 shows it at 83 tokens |
| all but T0, T9 | `(in-ns 'seon.error)` re-sent (15 of 17 turns) | navigation, 0 ms | unsure: the block's turns section names the namespace, yet the agent re-sends it; the run-9 help line (step 2) says not to; not a wasted turn, a wasted form | 15 forms, each 0 ms; live-run-9 friction 5, still unfiled |

### Chain d6c6977d38fd (fork point, capped red)

Mine flags T14, T23, T24, T27, T29, T30, T33, T34, T35, T36, T37. Hand judgement adds the guesses at T2, T4, T15 and
the probes at T13, T21, T22, T25, T26 that used the wrong store.

| turn | form (excerpt) | class | producer | evidence (stored error) |
|---|---|---|---|---|
| T2 | `(grep-lite 1)` | unresolved guess | unsure: no grep helper is named by help; agent invented it | `Unable to resolve symbol: grep-lite` |
| T4 | `(my.program/caller-count 1)` | unresolved guess | help names `my.program/callers` (829263bac) but not a count verb; unsure | `Unable to resolve symbol: my.program/caller-count` |
| T14 | `(defn- held-fork-point ...)` | avoidable error: derive refusal | **open**: `seon.schema/register!` admitted `:seon.cluster.registry/fork-point-result` while it referenced an unregistered key, and the derive refusal names no schema (run-9 friction 6, unfiled) | `derive: :malli.core/invalid-schema`, no schema named |
| T15 | `(my.program/source :seon.cluster.registry/fork-point-result 1)` | contract-refusal guess (keyword for a database argument) | unsure: the refusal is clear (`expected :seon.db/database-value`); the guess came from no named signature | `my.program/source refused argument 0 \`database\`` |
| T23, T24 | `(seon.cluster.registry/fork! ...)`, `(seon.cluster.registry/branches store)` on `(:store (seon.db/schema-database database))` | fixture-replay guess (wrong store: no `:seon.store/dir`) | **fixed** f4f6155ef (help step 4: load fixtures from `user`, take the store from `execution-handle`) | `expected the required key :seon.store/dir` |
| T27, T29 | `test-support/transacted!` | unresolved (alias guessed) | fixed-by-help f4f6155ef for the fixture; the `test-support/` alias itself: unsure | `Unable to resolve symbol: test-support/transacted!` |
| T30 | `(get-in database ...)` with a Delay used as a fn | avoidable error in the fixture hunt | fixed-by-help f4f6155ef (the hunt, not this form); unsure | `class clojure.lang.Delay cannot be cast to IFn` |
| T33, T34 | `(seon.test-support/with-database ...)` | unresolved, nobody required the namespace | **fixed** f4f6155ef | `Unable to resolve symbol: seon.test-support/with-database` |
| T35 | `(let [wd (resolve 'seon.test-support/with-database) ...` | unresolved | **fixed** f4f6155ef | `Unable to resolve symbol: seon.test-support/execution-handle` |
| T36, T37 | `(wd (fn [connection] ...))` with `resolve`'d fixture | NullPointerException, fixture hunt | **fixed** f4f6155ef | `Cannot invoke IFn.invoke because the return value ... is null` |
| T39 (hand) | `(contains? (seon.cluster.registry/branches store) probe)` | contract-refusal, same wrong store | **fixed** f4f6155ef | `refused argument 0 \`store\` at [:seon.store/dir]` |
| T14, T16 (hand, same cause as T14) | `(seon.schema/register! ...)` and the redefinition | avoidable error | **open**, see T14 | the two refusals repeat the same `derive:` text |

The fixture hunt (T21 to T39, 19 turns) is the same friction run 9 measured (10 wasted calls). Its help fix is f4f6155ef.
A recurrence check on the next live run is the proof that the fix holds; it is not yet taken.

### Chain ae969b87b62b (root seed, sent back)

| turn | form (excerpt) | class | producer | evidence |
|---|---|---|---|---|
| T4 | `(seon.db/q '[:find ?f :where [?a :seon.ns.alias/local ?f] ...])` and `[:find ?name ?src ... [?a :seon.ns/source ?src]]` | search (hand query for the root row) | **open, task-side**: the proof asks for a `my.agents.root` row that no producer writes (run 9 Broken 3; recorded as 98b839d98) | run 9 `live-run-9.md` "a retired assumption" |
| T10 | `(seon.program/source 'my.agents.root)` | unresolved (namespace guessed; the right one is `my.program/source`) | unsure: the help names `my.program/source`; this guess was not corrected by the block | `Unable to resolve symbol: seon.program/source` |
| T11 | `(seon.db/q '[:find ?src . ...] (seon.db/db) 'my.ag...)` | repeat of T4's hunt | same as T4 | `:repeat` class |
| T12 | `(seon.db/pull (seon.db/db) '[*] [:seon.ns/name 'my.agents.root])` | search | same as T4 | `:search` class |
| T13 | `(my.program/callers 'seon.cluster/seed-root-agent!)` and `(my.program/search :seon.turn/agent)` | search | same as T4: the proof's premise (where the root turn opens) is not in the block | run 9: no producer opens a root turn |
| T20 | `(my.task/send-back! {... :my.task/reason (str ... seon.bootstrap ...)})` | unresolved inside the reason form | agent-side (a symbol in the reason); unsure what named `seon.bootstrap` | `Unable to resolve symbol: seon.bootstrap` |
| T21 | `(my.task/send-back! {...})` with nil `:my.task/target` | contract-refusal, agent-side | not a producer waste: the refusal names the fix (`Supply a namespaced symbol at [:my.task/target]`); run 9 Broken-list item 3 | `expected :seon.task/sym ..., got nil` |

T22 (`my.task/send-back!` accepted) is the productive end. The send-back was correct: the proof was retired.

### Judge chain 142f07c37ddb (hand reading; the miner is empty here)

| turn | form (excerpt) | class | producer | evidence (stored form) |
|---|---|---|---|---|
| T1 | `(my.program/search "identity-reads-stewardship")` | search for a renamed proof | **open, task data**: the plan names `identity-reads-stewardship-without-an-agent-cluster-attribute`; the block said "resolves to nothing" but gave no rename | judge friction 3; the test is now `identity-reads-stewardship-from-the-namespace` |
| T6 | `(let [src (my.program/source 'seon.render.ns-test/task-and-schema-entities-render-as-graph-nodes)] (clojure.string/inclu...` | probe of source text | **open**: the first block did not show the failing proof's failure; it arrived at evaluation 3 (judge "perfect context" item 2); the block now names each proof (f9f623ecc) but not its current failure | judge friction 2 and 3 |
| T7 | `(mapv ... (ns-publics 'my.edit))` | search for edit verbs | **open**: no block line names how a definition is edited by text on the branch (judge friction 6); task `seon.context/a-definition-is-edited-by-exact-text-on-the-branch` | `ns-publics 'my.edit` in the form |
| T8, T9, T10 | `(let [src (pr-str (my.program/source '...task-and-schema...)) old "(list 's..." ...] ...)` | editing by resent text, three times | **open**, the same task as T7; each resend costs about 1.5k tokens (judge friction 6, about 7k in total) | three forms with the same `pr-str`/`old` shape |

Judge turns T0, T2, T3, T4 (the search and sources), T5, T11 (productive: the redefinitions moved the proof), T12 and
T13 (the 4th redefinition is green at T13) are productive by the miner's rule and by hand. T2 `update!` was forced by the
stale proof name (judge friction 3), so it is a productive progress turn that is still a consequence of T1's producer.

J-note (root step, not a model turn): the merge was refused twice with `:seon.source/test-evidence-error` because a
redefined test's changed declaration is reached by no test (judge friction 9 and 10). This is the merge's
`uncovered-refusal` path (`seon.cluster.source/uncovered-refusal`); the reproduction is still open (unsure whether the
redefinition or the merge gate is the cause).

## Frictions already fixed, counted

| fix | where it appears in this corpus | count |
|---|---|---|
| f4f6155ef (help step 4, fixture replay; nearest-name fix) | d6c6 T23, T24, T27, T29, T30, T33 to T37, T39 | 12 turns (of 11 mine-flagged + 3 hand) |
| 829263bac (help names callers) | d6c6 T1 and ae969b87b62b T13 used `my.program/callers` as the named verb | 3 searches, not counted as waste |
| 942c3850b (`seon.task/chain!` answers the attempt agent id) | judge friction 1 (pre-fix) | 1 form-group (about 6 evals on the judge's run before the fix) |
| 8b6a73320 (directory names schemas by key) | judge friction 5 (pre-fix) | 1 form (4,797 tokens, now about 1,650) |
| f9f623ecc (first block names each proof and its source form) | judge friction 2 (pre-fix) | 1 block (4,260 tokens, now about 2,900) |

The three context-fix commits affect the judge's pre-fix run only; the 8c4f, d6c6 and ae96 runs predate them except
where noted. Counting the judge's pre-fix frictions as fixed does not change the open list below.

## Producer ranking, open items first

Open (not fixed):

1. **Definition edit by text on the branch.** 3 judge turns (T7 to T10), about 7k tokens. Producer: no verb or reader
   stage for a partial definition edit (task `seon.context/a-definition-is-edited-by-exact-text-on-the-branch`, design
   filed by context-fix in 39c9108d9). Largest open cost per turn.
2. **Retired or renamed proof, no pointer.** 1 judge turn (T1) and 4 to 5 root-seed turns (ae969b87b62b T4, T11 to T13),
   the second being the retired `my.agents.root` premise. Producer: the task data and the block's proof resolution
   (recorded as 98b839d98 for run 9). Open.
3. **Derive refusal names no schema, `:malli.core/invalid-schema`.** 2 fork-chain turns (T14, T16), run 9 friction 6
   unfiled. Producer: `seon.schema/register!` (admits a schema with unregistered references) and the derive refusal
   (`seon.error` text). Open; it needs a red proof.
4. **First block lacks the failing proof's current failure.** Judge T6 and the 2 extra turns it caused. Partly fixed by
   f9f623ecc (proofs named, source shown); the failure text is still absent until the first red run. Open.
5. **Hand queries of the datahike database for facts no verb shows.** 2 root-seed turns (T4, T12), 1 8c4f turn (T8).
   Producer: missing fact (the candidate function symbols with arglists; the root agent row). Open, unsure which fact
   is missing in each case.
6. **Miner gap: `:seon.turn/attempts` absent on the judge's calls, and an empty answer instead of a refusal.** Not a
   turn cost, but it hides every turn of that chain from `seon.mine`. Producer: `seon.mine/model-calls`. Open; it is
   the miner task the lane was pointed at.
7. **Guessed API names** (`grep-lite`, `my.program/caller-count`, `seon.program/source`, `seon.bootstrap` in a
   reason, `test-support/` alias, keyword for a database argument). 7 turns across fork, root seed. Producer: unsure;
   help names the verbs for callers and source, but not a count or a grep. Open, unsure.
8. **Redundant `(in-ns 'seon.error)` on 15 of 17 turns.** 0 ms each, not wasted turns. Producer: unsure (block namespace
   line vs help step 2). Open (run 9 friction 5, unfiled).
9. **Re-read of a shown source.** 8c4f T5 (reply-value, shown in full at T3), T6 (name-tokens), fork T12 and T19.
   Producer: unsure; if the exchange keeps every shown value, the re-read is the agent's choice; if the exchange cuts
   older turns, it is the exchange (`seon.context/exchange-values`). Open, unsure.
10. **Merge gate refuses a redefined test's declaration as untested** (judge J-note). Not a turn; root step. Open,
    reproduction needed.

Fixed (counted, not open):

- Fixture replay (f4f6155ef): 12 fork-chain turns. Recurrence check on the next live run is pending.
- Help naming callers (829263bac): 3 search turns now a named verb.
- Judge's pre-fix frictions (942c3850b, 8b6a73320, f9f623ecc): 3 items.

Summary of turn cost: 94 model turns in the four chains (17 + 40 + 23 + 14). The miner's rule marks 19 of the 80 turns
it reads as wasted (1 + 11 + 7); the hand reading adds 6 judge turns, and the fork and root-seed rows above note their
extra wasted turns. Turns to green: 17 (unresolved-symbol, merged) and 14 (judge, green, merge refused). Fork point and
root seed ended without green; their wasted turns are the open items above.

## Unsure

- Whether the re-read class (item 9) is the exchange or the agent's choice.
- Whether the guessed API names (item 7) are a help gap or agent error.
- The `seon.test-support/` alias in the fork chain (T27, T29): not checked against the namespace's alias table.
- The judge merge refusal (J-note, item 10): cause not reproduced.
- Whether the judge's refused calls (`admission-closed`, the second `current-state`) would change the turn count: they
  are not stored, so not counted.
