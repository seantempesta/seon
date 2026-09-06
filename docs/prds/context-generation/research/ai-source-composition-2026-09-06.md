---
type: research
status: complete
tags: [research, context, render, sci]
---

# AI source composition — 2026-09-06

## Finding

An authored `:seon.render/ai` projection can become executable source only at
the existing outer context-composition boundary. A renderer leaf returns source
text. The composition owner orders the complete source collection, sends it
through the shared reader once, and gives the returned vector to the current
run's existing execution and settlement path. A stored evaluation value is
terminal data: format it directly and never send it through renderer discovery
again.

There are two custody cases, using the same source/run mechanism:

- Before a model call, the agent already has a held run. Source belongs in that
  run's generated prefix. `generate-turn` derives source,
  `append-generated-tx` stores it and starts its evaluation, and `resume-turn`
  executes and settles it before generation continues
  (`src/seon/cluster/loop.clj:1909-2002`,
  `src/seon/cluster/run.clj:806-884`). Opening a nested source run here must
  refuse because the current run is what makes the agent busy.
- A debug/editor action against an idle agent can use the paused working-tree
  `seon.cluster.agent/submit-source!` composition. It parses through
  `cluster.loop/planned-sources`, transacts `system-run-tx`, and sends one wake
  (`src/seon/cluster/agent.clj:629-714`,
  `src/seon/cluster/loop.clj:145-163`,
  `src/seon/cluster/run.clj:757-805`). It is an external entry to the same
  durable form/evaluation path, not a preview evaluator.

This reuses the mechanism audited end to end in
`debug-producing-source-integration-2026-09-06.md`. The paused submission seam
is not yet committed authority; its namespace comparison and unarmed-delivery
proof remain adoption conditions.

### Reuse the evaluated candidate by identity

A debug render is a possible context contribution, not an agent-authored turn.
Its system run already gives the durable identity chain needed to retrieve the
exact outcome: run `:seon.cluster.run/agent`, form
`:seon.cluster.run.form/run` plus ordinal and author, and evaluation
`:seon.cluster.eval/run` plus the same ordinal. Repeated debug presentation or
later context inclusion must select those same stored form/evaluation rows; it
must not open another run or evaluate the source again. A changed source is a
new form/run, while the existing `form/refreshes` relation and evaluation read
evidence own stale-dependency refresh.

The missing link is narrow. The retained render invocation currently stores
raw output plus cache/read evidence in memory, but no run or evaluation ref
(`src/seon/render.clj:1017-1058`). Prompt contribution facts retain block name,
hash, position, and token count, but likewise no evaluation ref
(`src/seon/context.clj:136-196`). Extend the existing invocation entry with the
ordinary stored run/evaluation identities returned by source submission. A
selected invocation can then contribute transcript entries queried from those
facts. Do not overload `run/supersedes` (curation replacement) or
`form/refreshes` (stale evaluation successor), and do not infer inclusion from
`:author :system`; that would insert every preview into the conversation.

The acceptance test is an execution counter: evaluate one candidate in debug,
render it repeatedly, select it for the provider context, and assert one run,
one evaluation, unchanged identities and result bytes, and counter value one.
Agent-authored forms remain an additional history query joined through the same
transcript entry function. The provider capture continues to prove the exact
bytes ultimately sent.

## Source and terminal value are phases, not string shapes

`:seon.render/ai` currently names a producer symbol or its returned string
(`resources/seon/schemas/seon.render.edn:7-24`). A string cannot reveal whether
it is executable source or terminal prose. Error schemas, message renderers,
run/evaluation renderers, identity faces, and ordinary values all return AI
strings. Parsing every AI string would turn expected prose into `no-forms`
failures and would try to execute rendered historical results.

Source provenance must therefore come from the caller:

1. Context composition asks AI producers for authored source and retains that
   result as source until it crosses the reader/run boundary.
2. `planned-sources` parses the complete ordered text. The resulting
   `:seon.cluster.run.form/source` rows with `:system` author are durable source
   provenance (`resources/seon/schemas/seon.cluster.run.form.edn:1-33`).
3. `resume-turn` evaluates those rows, binds each admitted value as
   `result/eN`, and settles the existing evaluation rows
   (`src/seon/cluster/loop.clj:1645-1855`).
4. Every settled value is terminal. An ordinary formatter may return a string;
   that string is displayed. It does not re-enter source composition because
   of its type or because a matching schema declares `:seon.render/ai`.

This needs no new render type. The phase and durable run-form authorship carry
the distinction. The older `:seon.render/form` projection proves that Seon
already generated executable context from live facts, but it need not survive
as a parallel source vocabulary.

## The one in-place composition owner

Git archaeology shows the existing shape. Commit `16f022fc9` deleted the
hand-authored bootstrap plan and introduced live-fact generated forms plus the
`:generate` → append → resume loop. Commit `326be0da8` later introduced a second
path: `render.walk/history` independently rendered `:seon.render/form` and
`:seon.render/ai`, paired them by lookup/path, manufactured
`form + printed-value` bytes, and `render.web/context-pass` retained those
bytes. The current code still has that divergence
(`src/seon/render/walk.clj:735-829`,
`src/seon/render/web.clj:2298-2362`). Commit `57f0008c6` deleted the earlier
direct prose assembler, confirming that joined history is intended to be the
one prompt composition owner rather than another prose path.

The minimum correction is to make the generated prefix supply that joined
history from durable forms and results:

- `bootstrap/pull-result` already owns one root acquisition and stable candidate
  order; `next-entry-in` already owns dependency readiness based on prior stored
  results (`src/seon/bootstrap.clj:470-606`). Change this owner in place to ask
  selected candidates for AI-authored source and send each ready wave through
  `planned-sources` before appending it to the held run.
- Independent sources in one ready wave may be joined and parsed once, then
  appended as the returned ordered vector in one transaction. A source that
  depends on an earlier `result/eN` stays in a later wave, after the earlier
  evaluation settles. This preserves the existing symbol-frontier guarantee.
- After those stored forms/results exist, prompt composition queries them
  through the existing transcript/history owner. The same query supplies agent
  context and debug display. `context-pass` retains those durable entries and
  concatenates their segments; it does not invoke source producers again.
- The explicit debug action enters through `submit-source!`, receives a run id,
  and observes that same stored history. It does not own a second assembler,
  evaluator, cache, or result formatter.

`planned-sources` is the existing reader seam. It preserves exact source slices
and parse namespaces, applies the one delimiter-repair policy, returns
prose-only input as a flat `no-forms` refusal, and lets other unreadable source
reach evaluation so the terminal diagnostic is stored
(`src/seon/cluster/loop.clj:145-163`). The generated append owner should accept
its ordered vector as an accretion of `append-generated-call`, retaining the
current prior-terminal and ordinal fences. `resume-turn` stays unchanged.

SCI confirms the batching boundary. `eval-string*` parses and evaluates
successive forms in one context, while `eval-form` analyzes each parsed form
(`reference-code/sci/src/sci/impl/interpreter.cljc:29-109`,
`reference-code/sci/src/sci/core.cljc:353-427`). An analyzed direct Var call
also applies the installed call-preparation hook
(`reference-code/sci/src/sci/impl/analyzer.cljc:1788-1805`). Seon's ordinary
turn already supplies that context, hook, time limit, admission, read evidence,
definition restoration, and result bindings. A parse/fork/run per render leaf
would duplicate these and lose truthful whole-vector ordering.

## Terminal formatting must stop execution

`seon.render/render-ai`, `render-call`, and `walk/neighborhood` are recursive
value renderers. They cannot execute returned text. The transcript is the
concrete recursion proof: `rendered-family` calls `render/render-call` with
`:seon.render/ai` for message, run, and evaluation entities, and `receipt-text`
uses that result to display a stored receipt
(`src/seon/render/transcript.clj:611-647,680-709`). If AI discovery there meant
“execute source,” rendering one completed evaluation would start another
evaluation, whose receipt rendering would start another.

Terminal evaluation values should end at the ordinary formatter already used
by `floor-text` and `bounded-result`
(`src/seon/render/transcript.clj:595-609,649-663`). A formatter-produced string
is emitted as formatted text without parsing. The transcript joins the stored
source with that terminal formatted value. It must not rediscover an AI source
producer for the receipt or result.

Likewise, `prompt/acquire-context-report` only budgets exact text from the
render proc (`src/seon/cluster/prompt.clj:174-191`). It cannot append execution
after prompt assembly: the held run has reached its model-call phase, and the
new facts would invalidate the prompt being priced. The generated prefix must
complete before `generation-complete-call` moves the run from `:generate` to
`:call` (`src/seon/cluster/run.clj:886-932`).

## Superseded code to delete

One mechanism requires removing the current mirrors in the same refactor:

- Delete `bootstrap/entry-source`, `entries`, the `:seon.repl/entry` conversion,
  and the unconditional `entry-source` calls in `root-candidate` and
  `next-entry-in` once AI-authored text is parsed directly. The current root
  candidate can contain a nil entry, which that unconditional conversion does
  not honestly represent (`src/seon/bootstrap.clj:119-132,521-606`).
- Delete `render.walk/generic-history-entries` and its separate full
  `:seon.render/form` and `:seon.render/ai` neighborhood invocations. They are
  the second, unexecuted form/value pairing
  (`src/seon/render/walk.clj:735-829`).
- Delete form-face-only candidate plumbing in bootstrap after AI source owns
  candidate production. Do not retain a compatibility form selector.
- Replace transcript `rendered-family` calls for stored evaluation results with
  the direct terminal value formatting path. Keep stored-source/result joining;
  delete schema rediscovery of a source renderer at that terminal boundary.
- Keep `render.web/append-history`, `history-segments`, and `history-text` only
  as retention and concatenation over queried durable entries. They no longer
  manufacture or refresh unexecuted source/value pairs.

## Risks and falsifiers

- **Source provenance must be explicit at the composition call.** No predicate
  over a string, function name, schema key, or parser success may classify
  source.
- **Generated ordering has two levels.** Independent ready sources can share a
  parsed/appended wave; dependencies on stored results require settlement
  before deriving the next wave.
- **Terminal rendering must be mechanically non-recursive.** Settle a source
  whose result is a formatter-produced string, render its transcript repeatedly,
  and prove run and evaluation counts do not change.
- **Agent context and debug must query the same stored entries.** A byte-identity
  regression should compare their source/result segments for one returned run.
- **Busy custody decides the entry.** A held generated run appends to itself; an
  idle debug submission opens a system run. Never fall back between them.
- **Paused `submit-source!` still has a namespace race.** Compare the namespace
  used for parsing inside `system-run-tx`; a caller pre-read is insufficient.
- **Unarmed debug delivery needs its positive proof.** Reuse the existing armer.
- **Bounds remain at the shared reader and evaluation seams.** Callers must not
  independently split, normalize, or digest source leaves.
- **Display derives from stored facts.** Re-rendering original inputs can see a
  later database basis and is not evidence of what executed.
