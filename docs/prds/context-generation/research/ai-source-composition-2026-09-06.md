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

## Bounded debug-cache integration — 2026-09-06

The debug surface now marks only its selected AI call as an authored-source
composition boundary. `render-call` retains the returned source in the existing
shared invocation entry. `render-source-call` submits that source through
`seon.cluster.agent/submit-source!`, retains the returned run id in the same
entry, and returns immediately. A later database render wake derives pending or
terminal state from the retained run facts; no separate status is retained, and terminal output comes from
`seon.render.transcript/render-run-ai`. Evaluation read-evidence rows join the
invocation's existing read evidence, so the normal cache-current check governs
both source production and the reads performed by the evaluated form.

The render proc receives the already existing cluster handle and routing atom
as explicit arguments from `cluster-graph-definition`. It performs no global
lookup, wait, polling, inline SCI evaluation, or new caching. When that agent
already holds a run, the invocation retains the queried run identity and read
evidence as a pending prerequisite; its settlement invalidates that evidence
and permits one later submission. A transaction-race refusal is returned but
is not retained as a candidate result. Alternative-candidate inspection remains
read-only; this bounded slice submits the one selected debug candidate rather
than opening one run per alternative.

The remaining context/bootstrap integration is explicit. Before a model call,
the agent already owns a held generated run, so source candidates must be
collected in dependency-ready order, parsed once per ready group, and appended
through the existing generated-form owner. Context composition must then reuse
the stored run/evaluation identities retained by this invocation cache. It must
not call the idle-agent submission seam, open nested runs, or execute one run
per renderer leaf. That batching and held-run wiring is outside this debug-cache
slice.

## Stored-history integration audit — 2026-09-06

The current source confirms that the replacement can stay inside the existing
owners, but it also narrows the earlier batching proposal. `generate-turn`
currently asks `bootstrap/next-entry` for exactly one dependency-ready entry,
appends exactly one form, and immediately resumes that ordinal
(`src/seon/cluster/loop.clj:1909-2002`). `append-generated-call` deliberately
requires the preceding ordinal to be terminal before accepting its successor
(`src/seon/cluster/run.clj:810-879`). That serial frontier is the authority.
Joining multiple apparently independent sources into one append transaction
would require widening the transaction contract and supplies no necessary
guarantee. The minimum change preserves one append and one evaluation per
frontier step; it does **not** mean one run per leaf. Every generated source is
an ordinal in the already-held run.

### Exact replacement flow

1. `bootstrap/pull-result` continues to acquire and order candidates. Replace
   `root-candidate` and candidate entry construction with an invocation
   descriptor carrying the existing render call/invocation identity and the
   explicitly requested authored AI source. Delete `entry-source`, `entries`,
   and the `:seon.repl/entry` conversion. The current unconditional conversion
   is visible at `src/seon/bootstrap.clj:119-132,521-606`.
2. `next-entry-in` continues to query the held run's stored form/evaluation
   pairs in ordinal order. Match each settled ordinal to its candidate by the
   retained invocation identity recorded with the generated form, rather than
   by source-string equality. Source text is payload, not identity. The next
   candidate is the first candidate whose declared dependencies are satisfied
   by those stored admitted result nodes.
3. `generate-turn` passes that source directly through the existing
   `planned-sources` reader seam, requires the one-candidate result to contain
   exactly one parsed form, and hands that exact source/namespace to
   `append-generated-tx`. The transaction owner and `resume-turn` remain
   unchanged. A multi-form renderer source is a typed candidate error at this
   boundary unless the owner separately widens “one candidate = one ordinal”;
   silently splitting it would destroy the candidate-to-evaluation identity.
4. Once the generated prefix is terminal, `render.walk/history` queries its
   stored form/evaluation rows and builds transcript entries with the existing
   terminal result formatter. Delete `generic-history-entries` and both
   neighborhood render passes (`src/seon/render/walk.clj:730-829`); they now
   simulate source plus result from current values and can differ from what ran.
5. `context-pass` keeps its per-agent retained entries and segment
   concatenation, but observations come from the stored-history query. Its
   current retention and concatenation are already isolated in
   `history-segments`, `history-text`, and `context-pass`
   (`src/seon/render/web.clj:2447-2505`). It must not call source producers.

### Cached and uncached candidates

An invocation with `:seon.render.call/source-run-id` and a terminal stored run
is already evaluated. Resolve that run's exact form/evaluation rows and reuse
their bytes and read evidence. Context inclusion performs zero submission and
zero evaluation. `render-source-call` already derives terminal output from the
retained run and enriches the same invocation entry with evaluation read
evidence (`src/seon/render/web.clj:1438-1515`).

An invocation carrying authored source but no run identity has two distinct
custody cases. While the agent has a held generated run, it becomes the next
eligible ordinal in that run through `append-generated-tx`; it must not enter
`submit-source!`. When no run is held, the explicit debug action may use
`submit-source!` and retain its returned run identity as it does now. The
current debug owner already records a held run as
`:seon.render.call/source-blocked-run-id`; that is an observation, not a queued
submission. Bootstrap must re-derive eligibility from the held run and the
candidate invocation, then append at the transaction authority.

If a cached debug run belongs to the same agent but is outside the held
generated run, its stored transcript entry can be included directly as the
selected candidate contribution. It cannot masquerade as an ordinal of the
held run, and it must not be appended or evaluated again merely for display.
The current schema has no generated-form-to-external-evaluation relation and a
turn fork does not bind another run's result as its own `result/eN`; therefore a
later generated source that depends on that symbol cannot yet consume the
cross-run result. That dependency case is an unresolved provenance gap, not
permission to re-execute a cached candidate silently.

### Ordering and inclusion

Actual agent history is ordered by its durable run/form ordinals and is always
included independently of render candidates. Generated context candidates are
ordered by `pull-result`/`ordered-episode`; only candidates admitted into the
held run appear, and they appear in that run's ordinal order after settlement.
A debug preview is not history merely because it has a terminal system run.
It enters agent context only when bootstrap selects its invocation for the
held generated prefix. The current `generic-history-entries` ordering trick
that moves `current-task?` last is deleted with the simulated entries; the
stored-history query must express the desired actual-history/current-trigger/
generated-prefix segment order explicitly.

### Implementation ownership

- `src/seon/bootstrap.clj` and its focused tests: candidate ordering,
  dependency frontier, invocation identity, removal of entry simulation.
- `src/seon/cluster/loop.clj` and `src/seon/cluster/run.clj` plus their focused
  tests: parse the selected source and append it to the already-held run. The
  transaction fences remain in `append-generated-call`.
- `src/seon/render/walk.clj` and transcript tests: query stored forms and
  evaluations, terminally format their admitted results, delete
  `generic-history-entries`.
- `src/seon/render/web.clj` and focused web tests: retain/concatenate those
  stored observations and preserve the debug invocation-to-run identity. No
  new cache or registry belongs here.
- The schema owner must declare one explicit invocation reference on a
  generated form if invocation identity cannot be reconstructed from existing
  facts. It must not use source hash/equality as a substitute.

### Remaining decisions

1. **Invocation provenance fact.** Current run-form facts carry run, ordinal,
   author, source, and namespace, but no render invocation reference. Choose a
   real ref/identity attribute before implementation; source equality is
   demonstrably ambiguous.
2. **Historical debug-result dependencies.** Direct context inclusion reuses a
   terminal debug run without execution. There is no relation or SCI binding
   allowing a later held-run source to depend on that external evaluation.
   Cross-run dependency reuse requires an explicit provenance/binding decision
   and is not part of deleting simulated history.
3. **Segment ordering.** Durable facts determine order within runs, but the
   product order between prior agent history, current trigger, and the newly
   generated prefix must be stated once in the context PRD before replacing
   the current `current-task?` placement.

These choices do not justify a new proc, cache, evaluator, or per-candidate
run. They determine only which existing fact connects the selected invocation
to the form/evaluation that actually ran.
