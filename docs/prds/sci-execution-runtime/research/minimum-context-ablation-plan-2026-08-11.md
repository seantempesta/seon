---
type: research
status: active
tags: [research, agent, context]
---

# Minimum-context ablation plan

## Decision and scope

Prepare four prompt variants now; run no experimental drives in this lane.
Every later drive uses `deepseek-v4-flash`, thinking disabled, and a fresh
isolated operator root. The task, grader, and agent id are identical across
variants. The only experimental variable is the injected opening prompt.

I read the complete
[self-generating-context PRD](../plan/self-generating-context-prd-2026-08-11.md),
including “Rulings appended after W0 markup” and the overnight rulings. I also
read the complete current `tmp/orchestrator/w1-integration-summary.txt`. That
file is 1,746 bytes, not the claimed verbatim 43,627-byte opening history. The
actual capture was recovered losslessly from the first marked apply-patch
record in `tmp/orchestrator/w1-integration-stdout.log`; the committed recovery
and digest check live in `tmp/ablation/prepare.clj`.

## Dependency ledger

| Dependency or mechanism | Selected identity | Source boundary used |
|---|---|---|
| Datahike | `407e9328851ccce318148188f1d284646eb64132` | `reference-code/datahike/src/datahike/api/impl.cljc`; queries through `src/seon/db.clj` |
| SCI | `fcbd8862800e638dc0f8f5521111f999279cbcd2` | turn facts and calls are produced by `src/seon/sci/eval.clj` and settlement, not by the harness |
| Prompt acquisition | Seon commit `5d18a27af165d25e93741990ab8dffb0ccd795ed` | `src/seon/cluster/prompt.clj`, `src/seon/render/walk.clj`, `src/seon/render/web.clj`, `src/seon/cluster/loop.clj` |
| Define-before-use proof | current Seon test | `test/seon/render/history_test.clj:113-140` plus `src/seon/render/walk.clj:698-784` |
| Token estimate | current Seon source | `src/seon/ai/tokens.cljc:164-181`; every displayed size uses `seon.ai.tokens/estimate` |
| Drive lifecycle | current Seon source | `src/seon/eval/drive.clj:300-368`; the harness changes only the root value of `seon.cluster.prompt/prompt` while the episode is running |

The prompt-path files did not change between the live probe basis
`b529e09d80c3c436a8f9d93cb7731c9a1d8f3bf7` and the recorded HEAD above.

## Premise rotation verdict

**Verdict: current HEAD consumes the new derived history.** It does not
assemble the retired transcript prose.

The source trace is:

1. inbound message route → `seon.cluster.message/inbound-tx`;
2. message wake → agent turn → `seon.cluster.loop/call-turn`;
3. `seon.cluster.prompt/prompt` synchronously acquires the render proc's
   context;
4. `seon.render.web/context-pass` calls `seon.render.walk/history` and joins
   `:seon.render.history/bytes`;
5. `seon.render.walk/history` combines durable
   `seon.render.transcript/history-entries` with generic form/AI neighborhood
   entries and orders them;
6. the loop commits the exact text as `:seon.context.capture/prompt`, then
   supplies that same string as `:seon.ai/prompt`.

The isolated live probe used root `tmp/ablate-root`, cluster `ablate`, and
fresh agent `premise`. The capture at basis transaction `536870980` recorded
22,828 characters. Its prefix was a real REPL history beginning
`my.agents.premise=> (db/q ...)` followed by the printed cluster value. The
prompt contained the exact task message and `my.agents.premise=>` prompts and
did not contain the old `Conversation` header. The production attempt then
reported 7,149 provider prompt tokens, close to the 7,072 pre-call estimate.

### No-spend interception failure

The probe was intended to make no paid call. Before sending the message it
stored a verified-absent credential variable on the agent. Provider descriptor
resolution silently replaced that declared per-agent value, so one real
`deepseek-v4-flash` attempt occurred. The root was stopped immediately and no
second message was sent. This is recorded, not hidden, in
[the provider-setting issue](../../../seon/issues/provider-descriptor-overwrites-per-agent-credential-selection.md).
All experiment drives use the explicit prompt interception in
`tmp/ablation/run_variant.clj`; they never use credential selection as an
interceptor.

## Recovered subject

The first complete marker pair in the W1 integration log reconstructs:

- 43,627 Unicode characters;
- 43,810 UTF-8 bytes;
- SHA-256 `d5077f2638d83245b515f208132a75bd3cb1dfc04f931e0e39792519b5318625`;
- 603 logical lines, with no terminal newline.

All 138 repeated patch copies in the log reconstruct to the same digest. The
original report called 43,627 a byte count; it is the Clojure character count.
`tmp/ablation/generated/opening-history.txt` is the recovered committed
subject.

The overwritten summary lost the original 56-entry data vector and retained
only its joined text. Consequently the harness preserves FULL byte-for-byte
and selects the smaller variants by unambiguous form/value spans. It runs lane
A's private define-before-use helper over every explicit selected entry vector
after `seon.render.walk/order-history`. FULL relies on the W1 integration
proof for the opaque recovered body, then checks the appended task entry. This
limitation does not change prompt bytes, but it must not be mistaken for an
independent reconstruction of the lost 56 maps.

## Variants

Every prompt ends with the same `my.message/read` task entry. Token counts are
from `seon.ai.tokens/estimate`, not character counts.

| Variant | Construction | Estimated prompt tokens | Define-before-use |
|---|---|---:|---|
| FULL | Exact recovered W1 history, then task | 13,809 | clean; recovered body carries W1 proof, appended entry rechecked |
| HALF | FULL minus the seven distance-2 toolkit namespace-detail entries; retained `dir my.message` and `dir my.run`; then task | 7,389 | clean |
| QUARTER | Help, `in-ns`, the two `dir` entries, own namespace, requires projection, task | 1,873 | clean |
| FLOOR | Help, `in-ns`, requires projection, task | 1,674 | clean |

The generated prompt files and EDN manifest are under
`tmp/ablation/generated/`. Rebuild them exactly with:

```bash
clojure -M:dev:test -e \
  '(load-file "tmp/ablation/prepare.clj") (ablation.prepare/-main)'
```

The command refuses a capture with the wrong character count, UTF-8 byte
count, digest, or toolkit-entry count. It also refuses any constructed entry
vector that fails lane A's helper.

## Fixed task and Datalog grade

The driven agent is always `w1-history-proof-5`, so its namespace and every
prompt prefix remain fixed. The task asks it to:

1. author permanent contracted
   `my.agents.w1-history-proof-5/cluster-agent-count`, accepting no arguments
   and returning the count of facts carrying `:seon.cluster.agent/id`;
2. call the function;
3. query its `:seon.fn/spec` back from the program graph; and
4. complete with the function name, count, and returned contract.

Success is the conjunction of facts, not completion prose:

- the function entity carries the exact `:seon.fn/sym` and a
  `:seon.fn/spec`;
- a run form has a `:seon.fn/calls` edge to that entity, and the same
  run/ordinal has a result receipt without `:seon.cluster.eval/error`;
- another clean receipt belongs to a form calling `seon.db/q` and carrying
  literal keyword `:seon.fn/spec`;
- the episode completed and no receipt has an eval error or error kind.

`tmp/ablation/run_variant.clj` executes these queries against the immutable
ending database value before stopping the isolated cluster.

## Exact drive procedure

Run the preparation command once. Then assign one drive agent to each variant.
Each agent chooses a unique, nonexistent root and runs exactly one command:

```bash
clojure -M:dev:test -e \
  '(load-file "tmp/ablation/run_variant.clj")
   (ablation.run-variant/-main "full"
     "tmp/ablation/drive-roots/full-01/clusters")'
```

Replace `full` and the root prefix with `half`, `quarter`, or `floor`. Do not
reuse a root: the runner refuses an existing path. It publishes current source,
starts one isolated cluster, asserts every recorded attempt used only
`deepseek-v4-flash`, injects the selected prompt at the existing prompt Var,
sends the fixed task through `seon.eval.drive/run-episode!`, queries the ending
facts, writes `tmp/ablation/results/<variant>.edn`, retires the grading branch,
and stops the cluster in `finally`.

The interception is deliberately local to the drive JVM and is the one
experimental seam: it replaces only the value returned by
`seon.cluster.prompt/prompt`. Message submission, capture, provider call,
reply reading, SCI evaluation, settlement, indexing, and grading remain the
production mechanisms.

After all four drives, print the comparable rows:

```bash
clojure -M:dev:test -e \
  '(load-file "tmp/ablation/measure_results.clj")
   (ablation.measure-results/-main)'
```

For manual observation, every result row records the episode id, message id,
and ending commit. The same facts can be queried while a drive is live:

```clojure
(db/q '[:find (pull ?capture [*])
        :where [?capture :seon.context.capture/id _]] db)

(db/q '[:find (pull ?attempt [*])
        :where [?attempt :seon.ai.attempt/run _]] db)

(db/q '[:find ?spec . :in $ ?sym
        :where [?function :seon.fn/sym ?sym]
               [?function :seon.fn/spec ?spec]]
      db "my.agents.w1-history-proof-5/cluster-agent-count")
```

## Results skeleton

| Variant | Estimated prompt tokens | Provider prompt tokens | DeepSeek cache-hit tokens | Turns to completion | Steering errors | Contract fact | Call receipt | Contract query | Success |
|---|---:|---:|---:|---:|---:|---|---|---|---|
| FULL | 13,809 | — | — | — | — | — | — | — | — |
| HALF | 7,389 | — | — | — | — | — | — | — | — |
| QUARTER | 1,873 | — | — | — | — | — | — | — | — |
| FLOOR | 1,674 | — | — | — | — | — | — | — | — |

Provider cache hits are read from the stored DeepSeek usage document's
`prompt_cache_hit_tokens`; provider prompt tokens are read from
`prompt_tokens`. Turns are the objective run count. Nothing in this lane fills
the table beyond construction-time estimates.
