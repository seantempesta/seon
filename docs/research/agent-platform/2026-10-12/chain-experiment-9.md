---
title: "Chain experiment 9: in-ns on call 2 and a defn on call 40, but 32 of 40 replies were DSML tool-call markup; capped, nothing merged"
type: research
status: complete (lane chain9, 2026-10-12); no merge, no publication; the subject task is left :held on default
created: 2026-10-12
---

# Chain experiment 9

**Result: not merged.** The attempt ended **`:capped` after 40 model calls in 3 min 27 s**, from launch at 15:35:38.9 to
the branch end at 15:39:07.4 UTC.

- The subject is the same as chains 6 to 8: `seon.sci.forgive/forgiving-forgiven-text-changes-nothing`, with the fix
  going in `seon.sci.forgive/scan`.
- Default ran archive **8d14faeee**: the `(help)` guide, `seon.context/task-sections`, one-field tasks, the stall rule
  removed, the stale merge base fixed, and the publication path.
- The model used DeepSeek flash with a cap of 40 calls.
- It ran `(in-ns 'seon.sci.forgive)` **on call 2**, which fixes chain 8's P11 in practice.
- **32 of its 40 replies were DSML tool-call markup** (`<｜｜DSML｜｜ tool_calls>`). Seon dropped each one as
  `no-forms`, and the error message named the cause.
- It wrote the `defn` of `scan` only in **the 40th reply**, 2.4 s before the cap.
- That `scan` **throws**: it calls `peek` on a transient vector in the stray-closer arm. The proofs on it gave 2 pass,
  4 fail and 3 errors, which is worse than main's 3 pass and 6 fail.
- `:merge`, `:publish` and `:delete-branch` were **never reached**. This is the sixth chain in a row.
- There is no merge commit and no Seon git commit, so the task-writeback-impl lane's file claims were never tested
  against publication.
- **Hand acts after launch: 0.** Before launch there was 1: the `:running` transaction.

Authorities: AGENTS.md "The goal"; [chain experiment 8](chain-experiment-8.md) (method, P-frictions); [chain experiment
7](chain-experiment-7.md).

## Runtime and subject check (before running)

- **Default.**
  - pid 94346, booted 15:30:26 UTC on 8d14faeee.
  - Health is **`:faulted`**: an open fault on `seon.sci.eval/install-row!` ("Unable to resolve symbol: refine-tasks",
    11 tasks), one errored receipt, and two untested-hot warnings. This was recorded but not attributed to this run.
  - Default was not stopped or reset.
- **Subject task.**
  - Handle **`be71b5c7e9ea`**, new since 4fded61c0: the handle is `seon.id/id` over `[ns name]`, and
    `:seon.task/name` is now a string.
  - Its state was `:held`, with no attempt.
  - `seon.task/ready` over a `d/with` of `:running` answered `#{"be71b5c7e9ea"}`.
  - The intent (a single field, with inline references) came back whole from `d/pull`.
- **Red re-verified, interpreted.** I ran this as probe agent `chain9-probe` in SCI mode. `my.test/check` with
  `:seon.test/identities` of the three proofs gave:
  - `a-stray-closer-keeps-tokens-apart`: 2 pass, 5 fail, 0 error;
  - `forgiving-forgiven-text-changes-nothing`: 0 pass, 1 fail (counterexample `"a]a"`);
  - `the-repairs-account-for-every-change`: 1 pass, 0 fail.

  This matches chain 8 (3 pass, 6 fail). The reset did not change the subject.
  - The cold check took **1.67 s / 533 MB** (226,029 calls, 1,468 reads). Chain 8's was 1.32 s / 711 MB.
  - My first call passed a vector and was refused by `:my.test/check-request`. The refusal printed its example and
    contract, which was enough to correct the call.
- **Config.** `seon.config/apply!` set `:seon.config.ai/no-provider false` and `:seon.config.run/max-episode-runs 40`.
  `seon.config/effective` confirmed both, with `:seon.config.fs/working-root` = this checkout.

## Timeline (UTC)

| At | Event |
|---|---|
| 15:35:38.912 | root writes `:seon.task/execution :running` (the only hand act) |
| 15:35:38.956 | db<->flow writes attempt **`6f4a9782cf90`**, 44 ms later. Its id is new, because the handle changed (P2 was not exercised). `:seon.task/merge-base` `6accfe4a-…` **equals** `(seon.cluster.registry/base-commit-id db :attempt-6f4a9782cf90)`. **P3 is fixed** |
| 15:35:40.4 | call 1: **prompt 7,808 tokens** (chain 8: 2,608). It is prose plus fenced forms, including `my.program/source` with 6 symbols, refused by argument count (3 such refusals in the run) |
| :35:43.9 | call 2: **`(in-ns 'seon.sci.forgive)`**, then reads of helper sources |
| :35:49-:36:36 | calls 4-8: two blob replies (10.3 and 8.5 KB) analysing the cases; probes confirm the three failing behaviours exactly (`a]a`→`aa`, `('` span, `(\\`) |
| :36:40.3 | call 9: "Understood — I sent markup that was ignored", the first sign of the no-forms message |
| :36:52-:38:21 | calls 13-39: **DSML in almost every reply** (`fs_search`, `my.program/source`, `probe` and **`my.program/edit`** as invented tools). Each answered `:seon.cluster.reply/no-forms-error`: "Your reply had no form: its tool-call markup was ignored. Write each call as a Clojure form at column zero." The model counts down its own budget ("16 turns left" … "2 turns left") and restates its three-point plan about every other call. Calls 11, 18, 22 and 29 open with "I have the (full) task context back" (P16) |
| :37:57.7 | call 33: "My earlier `my.program/edit` calls failed … Let me redefine `scan` … as a `defn-` at the REPL", followed by more DSML |
| 15:38:25.5 | call 40: a 40.4 KB reply with 10,605 completion tokens, about 39 s of generation |
| 15:39:04.998 | `seon.sci.forgive/scan` on the branch takes definition digest `464e21fe…` (main: `c54e2f6c…`) |
| 15:39:07.412 | `:capped` on the branch |
| 15:39:07.535 | `:capped` end copied to main, **123 ms** after the branch end; attempt commit `6accff1b-…` |
| — | problem task `attempt-ended-6f4a9782cf90` (`a10f2e5b9c08`) on main |

Totals:
- 40 calls, all with `finish-reason "stop"`;
- **1,480,165 prompt / 1,189,632 cached (80 %) / 24,944 completion tokens** (chain 8: 370k / 289k / 10.3k);
- 73 evaluations, 39 of them errors: 30 no-forms, 6 unresolved symbols, 3 `my.program/source` arity refusals;
- three replies over the 4,096-byte threshold were stored as blobs (8.5, 10.3 and 40.4 KB);
- the last call's prompt was 51,905 tokens.

## What the model's `scan` does

To measure it, I diffed the branch source against HEAD's and evaluated it on `chain9-probe`. In that probe copy only, I
replaced the `…` character literal with `(char 8230)`, because my MCP transport's JSON decoding had turned it into
`…`. The model's change has two parts:

- A **dangling-prefix** mechanism: a `dangling` volatile, a `dangle!` call before `'` `@` `` ` `` `~` `^` `\\` and
  `#`-prefixes, and a `close-all!` that inserts the closer before a dangling item and gives that item its own span.
  This targets the `('` and `(\\` rows.
- A **stray closer becomes a space**: `(.append out \space)` and `recur (inc i) i`. It records the space with
  `(vswap! repairs assoc! (dec (count @repairs)) (assoc (peek @repairs) :seon.batch/inserted " "))`, and **`peek` of a
  transient throws** a `ClassCastException`. So every input with a stray closer errors, including `a]a`, the
  idempotence counterexample.

The proofs on the probe after the `defn`, rerun in a separate request with the program digest changed:

| Proof | main | branch `scan` |
|---|---|---|
| `a-stray-closer-keeps-tokens-apart` | 2 / 5 / 0 | **2 / 2 / 3** |
| `forgiving-forgiven-text-changes-nothing` | 0 / 1 / 0 | 0 / 1 / 0 (errors on `"a]a"`) |
| `the-repairs-account-for-every-change` | 1 / 0 / 0 | **0 / 1 / 0** (regressed) |

Repairing the record with `(nth @repairs …)` in place of `peek` would probably have fixed the space rows. The model
never saw its result, because the cap fell in the same eval request.

The model also **dropped every `;;` comment** of `scan` (15 comment lines, which main's `:seon.fn/source` keeps). A
write-back of this definition would delete them.

## Hand acts

| | Chain 6 | Chain 7 | Chain 8 | **Chain 9 (8d14faeee)** |
|---|---|---|---|---|
| Cap | 15 | 15 | 40 | **40** |
| Hand acts before assignment | 1 | 3 | 1 | **1** (`:running`) |
| Hand acts after assignment | 0 | 0 | 0 | **0** |
| Calls / time | 9 | 15 / 128 s | 24 / 93 s | **40 / 207 s** |
| First-call prompt | — | — | 2,608 | **7,808** |
| Tokens prompt / cached / completion | 89k / 55k / 4.4k | 434k / 352k / 17.5k | 370k / 289k / 10.3k | **1,480k / 1,190k / 24.9k** |
| `in-ns` the owner | no | yes | no | **yes, call 2** |
| `defn` of the subject | no | yes, 4 of 5 rows | no | **yes, call 40; throws, worse than main** |
| `my.task/refine!` | — | — | — | **never** (0 replies mention it) |
| DSML replies | 2 | 0 | 0 | **32** |
| End | `:stalled` | `:capped` | `:stalled` | **`:capped`** |
| End copied to main | 75 ms | never | 122 ms | **123 ms** |
| Merge / publication / branch deleted | no / no / no | no / no / no | no / no / no | **no / no / no** |

**The cap was the binding limit.** With the stall rule gone, the 30 formless replies cost calls but did not end the
attempt. Without the DSML, the model would have had about 30 more calls to test and repair its `defn`.

Publication was never reached, so it never came near the paths the task-writeback-impl lane holds. The merged file
would have been `src/seon/sci/forgive.clj`, which that lane does not hold.

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| P14 (new, dominant) | 32 of 40 replies were DeepSeek DSML tool-call markup, and each cost one call. The no-forms message was read: call 9 says "I sent markup that was ignored", and call 33 says "My earlier `my.program/edit` calls failed". It did not change the next reply. The markup names invented tools (`fs_search`, `probe`, `my.program/edit`, `my.program/source` with `<parameter name="subject">`). So the model is making native tool calls that the request offers no tools for. In chain 8 there were none. What differs since then is the larger context: the `(help)` guide and the 7.8k first prompt. That is a hypothesis, not measured. The markup's parameters are fully structured (`name` = a Var, `parameter` = its argument), so a reply's DSML invoke could be read as the form `(my.program/source 'seon.sci.forgive/scan)`. That is a reader stage on the one path, not a fence, and AGENTS.md's "text in" stage already repairs what is unambiguous | `seon.sci.forgive/forgive` (prose → comments, markup dropped); the provider request in `seon.ai` (whether tools are declared, or `tool_choice` is `none`) | **New**: in `seon.sci.forgive-test`, a reply whose only content is `<｜｜DSML｜｜ tool_calls><invoke name="my.program/source"><parameter name="subject">seon.sci.forgive/scan</parameter></invoke>…` forgives to text holding the form `(my.program/source 'seon.sci.forgive/scan)`, with the markup recorded as a `:seon.batch/repair`. Alternatively: an `seon.ai` request-construction test asserts the DeepSeek body declares no tools and sets `tool_choice` `"none"`, if the provider supports it. One owner ruling picks between these |
| P15 (new) | The `defn` arrived on the last call, so its result (the auto test step's reds and the errored `a-stray-closer-keeps-tokens-apart`) was never delivered. The model counted its budget down ("2 turns left") but spent those calls restating its plan | the context's budget line; model behaviour | none: a consequence of P14 |
| P16 (new) | "I have the (full) task context back" opens calls 11, 18, 22 and 29. This suggests the rendered context dropped and then restored the task block between calls (`:seon.config.render.agent/token-budget` is 6,144). Not isolated: it may be only the model's phrasing | `seon.context/task-sections`, the agent history render | **New**: in `seon.context-test`, after N evaluations whose shown values exceed the render token budget, the current-state block still carries the task's intent and proofs |
| P17 (new) | The model's `defn` of `scan` dropped all 15 `;;` comment lines. A write-back of it would delete documentation that the model was shown | `my.program/write-branch!` (write-back of a redefined entity); what `my.program/source` shows | **New**: write-back of a `defn` whose new body omits the old body's `;;` comments, for a body otherwise unchanged at those spans, keeps or reports the comments. This needs an owner ruling on whether a REPL redefinition owns its comments |
| P18 (new, probe side) | In `chain9-probe`, a `my.test/check` in the **same eval request** as the `defn` of `scan` answered the pre-`defn` results (identical counts). The auto test step of that same request reported the new reds. A separate request then executed and answered the new results | `my.test/check` reuse by reach digest within one eval request (the program derives at the request's end) | **New**: one eval request `(defn f …) (my.test/check {:seon.test/identities #{t}})` where `t` reaches `f` answers `t`'s result over the new `f` |
| P19 (new) | Health is `:faulted` on boot of 8d14faeee, with an open fault on `seon.sci.eval/install-row!`: "Unable to resolve symbol: refine-tasks" | `seon.sci.eval/install-row!` | its 11 existing tasks |
| K4 (again) | The problem task of a capped attempt still says "Merge it with (my.root/merge! …)" for a branch whose proofs are worse than main | `seon.cluster.agent/ended-problem!` | chain 5 K4's proposal |
| P9 | The 3-test check costs 1.67 s / 533 MB cold, and 486 ms / 158 MB when 2 of 3 results are reused | test check path | the cost queue, as in chain 8 |

**Resolved since chain 8:**
- **P3**: the stored merge base equals the branch's base commit.
- **P11**: `in-ns` came on call 2, unprompted.
- **P10**: the intent is one field and pulls whole. Its rendering in the block was not separately measured.
- **P12**: the stall rule is removed, and the cap was the bound.

**P2** was not exercised: the new handle gave a new attempt id.

## What the next run changes

- P14 decides the next run. The model already knew where the work goes and what to change; DSML took 80 % of its
  calls. Either read the markup as forms at the reader stage or keep the provider from producing tool calls. Then
  rerun the same subject at a cap of 40.
- The `peek`-on-transient bug is the kind of error that the auto test step's red report (P15) would have shown it with
  calls left.

## Residue on default (disposable)

- **Subject task.** `be71b5c7e9ea` is set back to `:held` (cleanup, not counted). Its attempt `6f4a9782cf90` ended
  `:capped` on main and on the branch.
- **Branches.** `:attempt-6f4a9782cf90` is kept. It holds the model's `scan`, digest `464e21fe…`, with commit
  `6accff1b-…`. `:base/attempt-6f4a9782cf90` is also kept.
- **Problem task.** `a10f2e5b9c08` reads "ended capped".
- **Config.** Restored to `no-provider true` and `max-episode-runs 100`, verified with `seon.config/effective`.
- **Probe branch.** `:agent-ec2dd13fe36b` (chain9-probe) is retiring (`:my.agent/retiring? true`). It carried the
  probe copy of the model's `scan`.
- Nothing was published, and Seon made no git commit. HEAD advanced during the run only by other lanes' commits
  (`97c0fd7a3`, `606f9ec52`, `7c87dfb8e`).
- The probe copy of the branch source is in `tmp/chain9/` (disposable).
