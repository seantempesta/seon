---
type: research
status: active
tags: [research, agent, context]
---

# Minimum-context ablation plan

## Decision and scope

The four drives were subsequently run and are recorded in
[Results](#results) below; this section is the preparation lane's original
scope.

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

## Results

Provider cache hits are read from the stored DeepSeek usage document's
`prompt_cache_hit_tokens`; provider prompt tokens are read from
`prompt_tokens`.

All four variants were driven on 2026-08-12 (UTC) against
`deepseek-v4-flash` only — every drive asserts the model set and every
recorded attempt in every root is that one model. Each drive used its own
fresh operator root under `tmp/ablation/drive-roots/`.

| Variant | Estimated prompt tokens | Provider prompt tokens | DeepSeek cache-hit tokens | Usable attempts | Agent runs | Reasoning tokens | Runs with error | Contract fact | Called it | Returned the contract | Task done |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|
| FULL | 13,809 | 13,585 | 0 | 1 (+1 lost) | 2 | 17,772 | 1 | `[:=> [:cat] :int]` | yes | yes | **yes** |
| HALF | 7,389 | 21,687 | 7,168 | 3 | 3 | 44,187 | 0 | `[:=> [:cat] :int]` | yes | no (completion refused) | partial |
| QUARTER (run A) | 1,873 | 3,546 | 3,328 | 2 (+1 lost) | 3 | 5,615 | 1 | absent | no | no | no |
| QUARTER (run B) | 1,873 | 5,319 | 4,992 | 3 | 3 | 3,521 | 0 | absent | no | no | no |
| FLOOR | 1,674 | 1,580 | 0 | 1 (+1 lost) | 2 | 955 | 2 | absent | no | no | no |

“Usable attempts” counts attempts that recorded a usage document; “+1 lost”
is an attempt the provider ended without any assistant text (see the
reasoning-only defect below), which records no usage at all — so the provider
token columns UNDERSTATE real spend for FULL, QUARTER run A, and FLOOR.

Per-variant behaviour, read from each drive's ending database value:

- **FULL** — first turn lost to a reasoning-only stream; second turn did the
  whole task in three forms: `(in-ns …)`, the contracted `defn`, then one
  `let` that called the function, queried `:seon.fn/spec`, and completed.
  Settled result: `{:function cluster-agent-count, :count 2, :contract
  "[:=> [:cat] :int]"}`.
- **HALF** — defined `cluster-agent-count`, then two helper defs, then called
  `(my.run/complete {…})` exactly as the task text instructs, which the
  toolkit refuses: `complete needs the reply text you want delivered, as a
  string.` The turn was spent on the refusal. A later root run completed with
  `{:function cluster-agent-count, :count 2, :contract nil}` — the contract
  step never landed.
- **QUARTER** — both replicates spent every turn exploring and never wrote
  the `defn`. Run A: `(keys (ns-publics *ns*))`, `(dir seon.db)`,
  `pprint`-wrapped `db/q` surveys. Run B, all three turns, verbatim:
  `(dir seon.db)`, `(doc seon.db/q)`, `(dir seon.schema)`,
  `(db/q '[:find ?a :where [?e ?a]] db)`; then the same two openers again
  with `(dir seon.fn)`; then again with `(doc my.run/complete)` and
  `(ns-publics 'my.agents.w1-history-proof-5)`. Three turns, three
  restarts of the same survey — the agent re-derives its bearings every
  turn instead of acting, which is what an opening history without a worked
  example buys. The episode run cap ended it.
- **FLOOR** — one turn lost to a reasoning-only stream, one reply that was
  entirely prose: `The reply carried no Clojure forms — its whole text read
  as prose.` Nothing ran.

### Recommendation

**HALF is the minimum defensible opening context; FULL is the only variant
that finished.** The break is not gradual: between HALF and QUARTER the agent
stops ACTING and starts SURVEYING. The seven distance-2 toolkit namespace
entries that HALF drops cost nothing observable — HALF still produced the
correct contracted `defn` on its first turn — but the `dir my.message` /
`dir my.run` pair plus the worked `defn`-with-`:malli/schema` example that
QUARTER and FLOOR lack is exactly what the model imitates. QUARTER retains the
two `dir` entries and still failed, so the load-bearing part of the opening
history is the WORKED EXAMPLE (a contracted `defn`, its call, its refusal, and
the `:seon.fn/spec` query in the bootstrap history), not the namespace
inventory.

Read as cost: HALF is 46% of FULL's estimated prompt tokens and reached the
same contract fact on its first turn. QUARTER's 1,873 tokens bought nothing —
it spent MORE provider tokens than FULL's successful turn while producing no
durable fact, because a model with no example writes surveys.

### Confounds recorded, not hidden

1. FULL and HALF ran concurrently (three JVMs); FLOOR and both QUARTER
   replicates ran alone. Concurrency correlates with the lost attempts but
   does not explain FLOOR's, which ran alone.
2. The interception replaces `seon.cluster.prompt/prompt` for EVERY agent in
   the drive JVM, so the cluster's root agent also receives the variant
   prompt and sometimes performs the task in its own namespace. The graded
   facts are namespace-qualified to `my.agents.w1-history-proof-5`, so this
   contaminates behaviour, not grades.
3. Cache-hit tokens are cross-run: DeepSeek served QUARTER run B a 6,720-token
   prefix hit from run A's identical prefix, and HALF's later turns hit their
   own first turn. A first-of-its-prefix turn always shows 0.

### Grading correction

The in-drive `success?` conjunction reports FALSE for FULL even though FULL
finished. Its `contract-query-receipt` clause requires a form carrying the
LITERAL keyword `:seon.fn/spec` in `:seon.fn/keywords`; FULL reached that
keyword inside a quoted query vector, which the program graph does not index
as a form keyword. The same row also counts turns from the episode's own run
ids and steering errors from episode receipts: FLOOR reported `1` turn and
`0` steering errors while its database holds two agent runs, both carrying
`:seon.cluster.run/error`.

`tmp/ablation/grade_root.clj` therefore grades every variant post hoc from
the ending database value — agent runs, run errors, attempts, usage sums, and
the contract fact — and the table above uses those derived numbers. The task
completion itself is read from the settled `:my.run/result`.

### Defects met

Filed with evidence:

- [A reasoning-only provider stream burns the whole time limit and settles
  nothing](../../../seon/issues/a-reasoning-only-stream-burns-the-whole-time-limit.md)
  — three of nine attempts, with `:seon.config.ai/thinking :disabled` in the
  effective settings and 76,249 reasoning characters received.
- [A collection render drops 209 of 210 results without saying
  so](../../../seon/issues/collection-render-drops-209-of-210-results-without-an-elision-value.md)
  — met independently in this lane: the requires-projection entry is a large
  fraction of the QUARTER and FLOOR prompts, and the only omission notice the
  agent sees is `;; 28 definitions omitted by the namespace render budget.`
  while 209 namespaces vanish. Probed on the FLOOR root: 210 namespaces carry
  `:seon.ns/requires`. The note already existed; this is a second
  observation, not a second issue.
- Ugly output met in the agent's own context, quoted verbatim, standing
  order:
  - the requires projection above, whose whole collection-level output is one
    `(ns seon.bootstrap …)` card plus
    `;; 28 definitions omitted by the namespace render budget.`;
  - inside that same card, raw schema boilerplate the agent must read past —
    `; schema :seon.bootstrap/agent-plan-absent-error = [:map {:seon.error/class
    true, :seon.render/ai seon.error/render-ai, :seon.render/html
    seon.error/render-html, :error/message "must identify the agent with no
    bootstrap plan"} …]` — already owned by
    [namespace units render error schema
    boilerplate](../../../seon/issues/namespace-units-render-error-schema-boilerplate.md);
  - the run-level error string for a lost turn, which names a socket rather
    than the reasoning-only stream that caused it (issue filed above).
- The fixed ablation task instructs a call `my.run/complete` refuses —
  recorded here rather than as a system issue: `complete` takes reply TEXT,
  and the task's `(my.run/complete {…})` wording cost HALF a turn. A rerun of
  this experiment should say “complete with the printed map”.
