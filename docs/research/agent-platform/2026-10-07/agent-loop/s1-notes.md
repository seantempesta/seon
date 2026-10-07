# loop-s1 REPL notes (2026-10-07) — stopped before implementation by owner order

No commits, no lane claims, no source/schema/test edits, no paid DeepSeek calls.
All probes ran in default's JVM (HEAD a8e3bde5b loaded; working-tree turn.clj is errors-s1's, line numbers
below are HEAD's) against a disposable branch agent.

## Probe world (left in place, disposable)

- Agent `loop-s1-probe`, created by one MCP `eval_clj` sci run `(+ 1 2)`; its branch `:agent-a44a0509958c`.
  The agent entity lives on default's branch (agent rows are the cluster's), its runs on the branch.
- Branch cluster handle: `(:seon.turn.loop/cluster @(:seon.cluster.host/host (get-in @(:seon.agent/routing inst)
  [:seon.agent/children :agent-a44a0509958c])))` with `inst (get @seon.operator.runtime/running-instances "default")`.
- Host Vars from JVM-mode probes: `user/probe-cluster`, `user/probe-call-id`, `user/probe-ev` (lost at restart).
- Retire later with `my.agent/retire-branch! {:seon.store/branch :agent-a44a0509958c}` from root.

## 1. The fold without a turn entity — HOLDS

`seon.turn/evaluate-sources` with a fresh `:seon.turn/id` value (a digest, no entity), a fork from
`sci.eval/fork-for-turn` (no turn entity needed either), `planned-sources` of
`"Let me add.\n(+ 1 2)\n(defn probe-add [a b] (+ a b))"`:
- fork 9.8 ms, evaluate 64 ms; two entries, shown `"3"` and the var; comment `"; Let me add."` kept on form 1;
  result handles minted from (run-id, ordinal); program `{:entities :schema-keys :namespaces}`; basis unchanged
  (nothing written).
- Inside the fold the run id is only read in `evaluate-sources`' generated-read-fault check
  (turn.clj:4992-4996), which pulls the turn's situation and the evaluation's author: both absent → nil, harmless.

## 2. Evaluation units through seon.cache — plausible, not yet measured at scale

`seon.repl/render-ai` on a pulled evaluation entity (+ `:seon.db/db`) renders `user=> (+ 1 2)\n#:seon.repl{...}`;
3.1 ms for one cold. `prompt/select` + `prompt/compose` over `{:seon.render.history/bytes text}` units work
unchanged. Not probed: hit cost via `seon.cache/lookup`, 600-evaluation timing, `seon.health/derived` live reads.

## 3. ONE settlement transaction — needs design decisions the spec did not spell out

The existing settlement is run-shaped all the way down, so `record-call` cannot just "call" it:

a. **Ordinals vs. ids.** The fold needs ordinals BEFORE the writer (it mints `result/e<id(run-id, ordinal)>`
   handles in SCI during evaluation, turn.clj:4910-4913). The spec assigns per-agent ordinals AT the writer.
   Consistent resolution: evaluation id = `(id/evaluation call-or-fresh-id local-index)` (handles stay valid),
   stored `:seon.cluster.eval/ordinal` = writer's `last-ordinal + 1 + local-index`. The spec should say so: an
   evaluation's ordinal is then no longer part of its identity.
b. **Turning `evaluation-facts` output into entity rows.** `receipt-settle-call` (turn.clj:1437) requires a run
   entity (`receipt-run`) and a pre-existing started receipt; it cannot be reused. What the new writer needs is the
   private helpers `receipt-terminal-attributes`/`-terminal-assertions` (turn.clj:1180-1235),
   `receipt-read-evidence-tx` (component tempids, :1237) and `evaluated-namespaces` (identity upserts placed before
   the rows, :598). turn.clj is held by errors-s1, so either (i) those become public in a turn.clj change, or
   (ii) `record-call` derives the attribute list from the `:seon.cluster.eval/receipt` schema keys (CLAUDE.md:
   derive attribute lists from entity schemas) and re-states the ~15 lines of read-evidence/namespace rows. (ii) is a
   partial copy; the spec says "call them, never copy them". Owner decision needed.
c. **What the old settlement also did that the spec's §2.1 list may drop:** `settlement-projection` blob staging of
   cut values (public, reusable, needs `blob/with-publication!` around the commit), fault windows
   (`with-fault-windows`, private), `seon.fault/folded-tx` repeats riding the commit, `measurement/significant-tx`,
   the per-form revalidate-and-retry (`turn.batch/revalidate`, public), and `sci.eval/install-evaluated-rows!`
   after commit. §2.1 names measurement, revalidate and install; fault windows and folded faults are not named.
d. **Calibration.** `prompt/model-calibration` joins attempts to captures through `:seon.turn/attempts`
   (prompt.clj:75-105); with no capture and no turn it finds nothing, so S1 context prices with the prior
   (`tokens/prior-calibration` of `:seon.config.ai/chars-per-token-prior`) until calibration joins on
   `:seon.ai.attempt/characters` (spec says this, but it is a prompt.clj change outside S1's claimed files).
e. **Name collision.** `:seon.eval/origin` already exists (the generating entity's identity, a string,
   seon.eval.edn:1) and `evaluation-facts` copies it; the new `:seon.cluster.eval/origin` enum is a different
   concept with a near-identical name. Worth renaming one before it is installed.
f. **`outside-wake-t`** is public in turn.clj (HEAD :2627) and callable from the new namespace as the brief says.

Not probed before the stop: the actual one-transaction commit (needs the §1 schema accretion adopted first — the
receipt schema requires `:seon.cluster.eval/run` today, so an agent-ref evaluation is refused until it is optional),
`ai/complete` with a fixture `:seon.ai/send!`, and `message/reply` delivery.
