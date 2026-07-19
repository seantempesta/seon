---
type: research
status: active
tags: [research, agent, cljs, database, schema]
---

# `generate-code!` design seam audit — 2026-07-19

## Question

Can goal-driven code generation reuse Seon's ordinary REPL, program graph,
`my.plan`, agent lifecycle, and reactive database mechanisms without adding a
parallel plan language or scheduler?

## Conclusion

Yes, with two foundational changes and one new pure projection:

- namespace identity values must migrate from keywords to symbols as one
  coordinated database rebuild;
- `my.plan` needs namespace ownership plus a scalar compare-and-swap claim;
  and
- a planner reply must be projected into fenced namespace units before any
  form is evaluated.

The rest is composition. The planning model writes ordinary ClojureScript.
`seon.repl.internal/parse-forms` reads it once, the existing evaluator records
accepted forms, `(ns ... (:require ...))` derives the namespace DAG, `my.plan`
stores recovery state, messages assign work, agent runs prove execution, and
one `seon.reactive/observe!` registration derives the ready frontier. There is
no second plan syntax, code registry, parser, evaluator, event bus, or worker
lifecycle.

Owner review on 2026-07-19 approved this direction and added one context
constraint: development guidance is a progressive `:generate-code` block,
while full code selection remains exclusively on the existing `:namespaces`
block. Preparatory cleanup `e205dc9e` removed the duplicate agent-level and
cluster-level namespace render controls, leaving cluster namespace config as
source storage only.

## Dependency ledger

| Mechanism | Selected source | Existing Seon owner |
|---|---|---|
| ClojureScript self-host compiler | ClojureScript 1.12.145; `reference-code/clojurescript/src/main/cljs/cljs/js.cljs` | `seon.eval` |
| REPL parsing | rewrite-clj 1.2.51 and parinferish 0.8.0 | `seon.repl.internal`, `seon.repair` |
| Schemas | Malli 0.20.0 | `seon.schema` |
| Database graph and queries | maintained Datahike source under `reference-code/datahike/`; CLJS coordinate 0.8.1681 | `seon.db`, JVM writer |
| Durable plan DAG | current first-party source | `my.plan`, `my.plan.internal` |
| Agent birth and execution evidence | current first-party source | `seon.agent/delegate!`, `seon.agent.message`, `seon.agent.run` |
| Reactive frontier | current first-party source | `seon.reactive/observe!` |
| Semantic retrieval | Gemini `gemini-embedding-2`, Proximum HNSW, disabled unless `SEON_EMBED` is present | `seon.embed` |
| Planning-model probe | Kimi K3 through its OpenAI-compatible API, 2026-07-19 | `seon.ai.openai-compat` target |

## Existing mechanisms that carry the design

### Parsing and evaluation

- `cljs.js/analyze-str*` reads one form at a time. The `:ns` option is only the
  initial namespace; a successful `ns` AST changes the namespace for subsequent
  forms.
- `seon.eval/eval-batch!` already evaluates parsed entries independently,
  continues after failures, and carries the last successfully established
  namespace.
- `seon.repl.internal/parse-forms` already preserves original spans, repairs
  mechanically recoverable delimiters, rewrites Seon's `#code` heredocs, keeps
  narration, and emits explicit read failures.
- `seon.eval/build-tee-entities` records literal single-form namespace,
  `schema/register!`, `defn`, and `deftest` evals as program facts. Keeping
  per-form evaluation preserves this one indexing path.
- Re-evaluating a named definition replaces that definition. Omission is not
  deletion: functions and tests require explicit `ns-unmap`; registered schema
  removal has no current public operation.

One unsafe behavior matters: if an `ns` declaration fails, a later definition
in the same raw batch can land in the last good namespace. A planner response
therefore cannot be handed directly to `eval-batch!`. It must first be parsed
and partitioned into namespace-fenced units. A unit whose declaration does not
parse or evaluate does not release its remaining forms.

### Plans, messages, and runs

- A `my.plan` step already carries identity, title, description, status,
  owning agent, caller, assignment message, tree parent, dependencies, goal,
  acceptance expectation, and timestamps.
- `my.plan.internal/rules` already derives leaf, unfinished, blocked, and ready
  work from `:my.plan/status` and `:my.plan/needs`.
- `my.plan/active!` is a context-position operation. It deliberately demotes
  every other active step for that agent, so it is not a parallel work claim.
- `seon.agent.message/message!` already allocates a message identity and can
  link a plan step to the message in the same transaction.
- The worker run points to the waking message through
  `:seon.agent.run/cause`. Worker identity and terminal outcome are therefore
  derivable from the step's assignment message and run; another stored worker
  attribute is unnecessary.

### Reactive scheduling

`seon.reactive/observe!` already gives one normalized computation:

- database-query dependency capture;
- selection by committed transaction attributes;
- at most the newest pending database value;
- Clojure-equality suppression; and
- cleanup when the consumer releases the registration.

The scheduler should observe one sorted ready-frontier value for a generation
root, not one listener per step. A callback still does not own work. It first
commits an atomic claim, and only the winner sends or delegates.

## Executable evidence

### Parser salvage and namespace boundaries

A JVM REPL probe passed one source string containing two namespace declarations,
schema registrations, definitions, and intervening narration through
`parse-forms`. It returned six independent entries with the original source and
spans intact. A second probe inserted an unreadable odd map between namespaces:
the parser returned one localized `:read` error and still recovered the later
valid namespace and definition.

The focused parser gate passed:

```text
seon.repl.internal-test — 39 tests, 330 assertions, 0 failures
```

### ClojureScript namespace switching

In a standalone Shadow ClojureScript REPL:

- evaluating a form under a dotted namespace that had never been established
  failed;
- evaluating `(ns my.probe.schema)` first, then evaluating
  `(def captured ::item)` with `:ns 'my.probe.schema`, produced
  `:my.probe.schema/item`; and
- one source containing `(ns my.probe.three)` followed by
  `(ns my.probe.four)` ended in `my.probe.four`.

This proves the schema-first pass must evaluate each unit's real namespace
declaration before its schema forms. It must not synthesize keyword names or
evaluate local `::schema` keys from the planner agent's namespace.

The evaluator, authored-program loading, require, and plan gates passed:

```text
seon.eval.require-test + seon.eval.auto-refer-test +
seon.execution-test + my.plan-test — 67 tests, 250 assertions, 0 failures
```

### Atomic claim and ready frontier

An isolated in-memory Datahike database was exercised through the running JVM
writer REPL. With `model` required by `service`:

1. the initial ready query returned `["model"]`;
2. one transaction compared claim `nil` to `"claim-1"`, created the assignment
   message, and linked it to the step;
3. the frontier became empty while `model` was claimed;
4. a competing compare-and-swap from `nil` to `"claim-2"` failed with
   `:transact/cas`; and
5. marking `model` done exposed `["service"]`.

The maintained Datahike source and the live error also establish that a tempid
cannot be the new value in `:db.fn/cas`. The claim therefore cannot be only a
new message ref. The safe transaction uses a preallocated message identity as
an opaque scalar claim value, creates that message, and links the ordinary
`:my.plan/message` ref in the same transaction.

The focused reactive and analyzer gate passed:

```text
seon.reactive-test + seon.analyzer-info-test —
13 tests, 46 assertions, 0 failures
```

### Namespace symbol migration

An isolated Datahike probe installed `:seon.ns/name` as a keyword identity,
wrote one namespace, and attempted to change the attribute value type to
symbol. Datahike rejected it as an unsupported schema-attribute update.

The repository contains 226 textual uses of `:seon.ns/name`; 23 production
files participate in the value contract. `:seon.ns.require/target` is also a
keyword today. The Malli-to-Datahike bridge already maps `:symbol` to
`:db.type/symbol`.

Consequences:

- use `:symbol`, not Malli `:qualified-symbol`: `my.library.model` is a complete
  namespace name but contains no slash;
- migrate `:seon.ns/name`, namespace refs, require targets, selectors, config
  values, render code, and tests together;
- rebuild or explicitly migrate databases; a rolling mixed keyword/symbol
  representation is invalid; and
- do not add coercions or dual lookup paths.

### Context size

The source catalog contains 150 production namespaces and no missing namespace
docstrings. Rendering only `namespace — first docstring line` for all of them
measured 11,668 characters and 2,917 estimated tokens through
`seon.ai.tokens/estimate`. Including tests grows the catalog to 325 namespaces
and 6,237 estimated tokens, so tests should not be in the planning catalog.

A deterministic twelve-namespace sample rendered with each public function's
signature/docstring line and its schema registrations measured 6,590 estimated
tokens, or about 549 tokens per namespace. This supports the following initial
budget, to be measured against the exact database renderer during implementation:

| Context layer | Initial budget |
|---|---:|
| Complete production namespace summary catalog | about 3,000 tokens |
| 8–20 retrieved compact namespace cards | about 4,400–11,000 tokens |
| Full source for the closest 1–3 owners and their `.internal` children | measured, hard-capped separately |
| Goal, description, expectation, and generation instructions | about 1,000–2,000 tokens |

At the user-supplied K3 cache-miss input price of $3 per million tokens, the
complete production namespace catalog costs about $0.009 per uncached call.
The catalog is therefore cheap and should be a stable prefix. Full source, not
the catalog, is the material input-cost risk.

### Existing context and model configuration seams

The configuration audit found one viable namespace mechanism after
`e205dc9e`: a block entity named `:namespaces` owns `full-source`, `with-tests`,
`current-full?`, and `current-tests?`. `:seon.config/namespaces` now owns only
the framework full-source storage superset. Code generation should reconcile
the block's existing `full-source` set for each assignment; it should not add a
second dynamic renderer.

The LLM audit found one database-owned resolution chain. It now exposes the
complete non-secret provider surface on every agent entity: provider, model,
temperature, output cap, thinking, timeout, base URL, credential-variable name,
backend, extra body, and retries. Planning/execution specialization therefore
attaches ordinary agent attributes and remains outside `generate-code!`
dispatch. Independent Muse and Kimi agents can share a cluster while their
actual secrets remain process environment values.

The progressive developer renderer should derive four states without storing a
phase: initial planning from the root assignment, namespace repair from failed
eval/test evidence, verification from remaining acceptance failures, and empty
output when no generation assignment is active. The namespaces block supplies
full source for selected owners, the target, and their `.internal` descendants;
the generate-code block supplies instructions, root contract, original reply,
unit evidence, and sibling DAG orientation.

### Retrieval

The current namespace context includes the current namespace and explicit
requirements. Compact cards contain persisted schemas and public,
schema-complete function contracts but no bodies. General context deliberately
excludes `.internal`; the planning context must override that selection rule
for the `.internal` descendants of a selected owner because those internals are
necessary design context even though they are not an agent-facing API.

The current embedding corpus is only entities carrying `:seon.fn/source`, and
the feature is off unless `SEON_EMBED` is present. It was disabled in the probed
runtime. Therefore embeddings cannot be an MVP correctness dependency.

The deterministic primary retrieval order is:

1. namespace symbols explicitly named in the request;
2. the caller's current and required namespaces;
3. lexical matches over namespace summaries, schema keys, and public symbols;
4. requirement neighbors of those results; and
5. `.internal` descendants of every selected owner.

When embeddings are enabled and preflight passes, function-source hits can
augment this set. Hits are grouped by their owning namespace and merged before
the same deterministic expansion. Namespace-summary embeddings should be added
to the one existing index only after a measured recall evaluation shows that
function-only retrieval misses owners.

### Kimi K3 planning and compatibility probes

Two paid calls used the official `kimi-k3` endpoint with a 239-token identical
prompt asking for two small ClojureScript namespaces, schemas first, and
behavioral tests.

The provider contract was checked against the official
[Kimi K3 quickstart](https://platform.kimi.ai/docs/guide/kimi-k3-quickstart) and
[K3 pricing page](https://platform.kimi.ai/docs/pricing/chat-k3). The configured
environment name is `MOONSHOT_API_KEY`, the OpenAI-compatible base URL is
`https://api.moonshot.ai/v1`, and K3 currently exposes only maximal reasoning
effort. Prices in this report are a dated observation, not configuration truth.

| Probe | Completion cap | Wall time | Cache | Completion total | Reasoning subset | Result |
|---|---:|---:|---:|---:|---:|---|
| planning | 4,096 | 107.17 s | miss | 4,093 tokens | 4,093 tokens | `length`; no code |
| planning | 8,192 | 47.18 s | 239-token hit | 1,106 tokens | 975 tokens | `stop`; roughly 131 visible tokens and code returned |
| earlier isolated named variant | 16,384 | 13.30 s | miss | 2 tokens | 0 reported | `stop`; `(+ 20 22)` evaluated to `42` |
| exact-artifact batch | 16,384 | 11.22 s | miss | 40 tokens | 19 tokens | `stop`; `(+ 20 22)` evaluated to `42` |
| exact-artifact forced cap | 32 | 11.36 s | miss | 32 tokens | 29 tokens | `length`; no visible text; explicit truncation error |

At the supplied prices, the first probe cost approximately $0.062 and produced
nothing usable; the second cost approximately $0.017. These costs treat
reasoning completion tokens as billed output tokens.

The earlier third call ran through a fresh `kimi-k3-audit` cluster and the
then-shipped `:planning` model variant, not a direct HTTP script. Its committed
attempt fact records `:openai-compat`, endpoint
`https://api.moonshot.ai/v1/chat/completions`, credential name
`MOONSHOT_API_KEY`, requested model `kimi-k3`, one successful attempt, and an
effective false/omitted reasoning field. The turn recorded 1,809 prompt tokens,
2 completion tokens, and one successful eval. At cache-miss prices the estimated
cost was $0.0055. This is a transport/configuration and low-complexity latency
floor only; it does not supersede the reasoning-heavy planning measurements.

The final exact-artifact proof ran at commit `6e3b741d` and application digest
`21cf5dbe…`. An initial config-free reopen deliberately preserved the old
database variant and produced one stale-profile streaming call; that call is
configuration-persistence evidence, not adapter acceptance. After a scoped
registry deletion, fresh boot installed the current variant before readiness:
`:batch`, `:max-completion-tokens`, a 300-second adapter timeout, and a
360-second outer timeout.

The current normal agent `big-days-attack` recorded turn `k55f66phlib4` as one
nonstreaming successful attempt with `finish_reason=stop`, provider-reported
usage of 32,551 prompt and 40 completion tokens, 19 reasoning tokens, and eval
result `42`. Its close transaction was 11.22 seconds after launch; estimated
cache-miss cost was $0.098253. The separate agent
`breezy-places-wave` copied the same variant, then received an ordinary
agent-local max-token value of 32 before its first message. Turn
`k8baadedsbxy` recorded `finish_reason=length`, `truncated? true`, no evals, a
nonretryable provider-error value, and retained usage of 32,553 prompt and 32
completion tokens including 29 reasoning tokens. Its close transaction was
11.36 seconds after delivery; estimated cache-miss cost was $0.098139.

A config-free isolated restart reopened at basis transaction 536871437 and
preserved both distinct agent rows plus both complete attempt and turn facts.
Canonical close was clean and left the isolated pod absent. The two intended
current-contract calls cost about $0.1964 together. The stale-profile evidence
call added about $0.0859, for approximately $0.2823 spent in the full audit.
The current 114K-character development prompt measured about 32.5K provider
tokens, so embedding-ranked and progressive context directly govern the cost
of K3 planning.

The successful response obeyed the requested namespace split, emitted normal
forms, used immutable transformations, added behavioral tests, and declared the
service's require. It was not suitable for direct execution in Seon:

- it invented `cljs.spec.alpha` rather than using `seon.schema/register!` and
  Malli;
- its result status schema omitted `:checkout/invalid-db`, which its service
  then returned;
- it added validation and a service indirection not required by the goal; and
- it placed tests in implementation namespaces rather than following the
  repository's indexed test conventions.

This is the central model-routing result. K3 is plausible as an explicit
high-latency planning escalation, not as the normal executor and not as a
blind code generator. A low cap is actively unsafe because K3 always reasons
and can consume the entire allowance before producing visible output. The
planning call needs enough completion headroom, an explicit cost ceiling, and
the real retrieved Seon contracts in context. Its first pass still goes through
the ordinary parser, evaluator, tests, and namespace repair agents.

## Refined data model

The generation root and namespace leaves are ordinary `my.plan` steps. Reuse
all existing attributes. Add only:

```clojure
(schema/register! :my.plan/namespace :seon.db/ref)
(schema/register! :my.plan/claim :string)
```

`:my.plan/namespace` points to the entity identified by
`:seon.ns/name 'my.library.model`. It is absent on the root. `:my.plan/claim`
is an opaque, cardinality-one fence value equal to the preallocated assignment
message identity. The message remains the actual connection and the run's
`:seon.agent.run/cause` remains execution evidence.

The existing `:blocked` status also needs one public `my.plan/blocked!`
transition. It is not another state system: it exposes the stored status that
the rules already understand. A blocked transition retains the claim, message,
run, eval failures, and original plan for diagnosis. Reopening a namespace step
retracts its old claim before it can be scheduled again.

Do not add stored dependency order, worker state, ready booleans, result blobs,
namespace summaries, or diffs. Requirements, readiness, workers, accepted
program facts, and changes are derived from existing connections.

## Pure reply projection

The one new transformation consumes the planner's raw reply and
`parse-forms` entries, and returns ordinary data:

```clojure
{:seon.generate/root-comments [entry ...]
 :seon.generate/namespaces
 [{:seon.generate/namespace 'my.library.model
   :seon.generate/declaration entry
   :seon.generate/schemas [entry ...]
   :seon.generate/forms [entry ...]
   :seon.generate/requires #{'seon.schema}}
  ...]
 :seon.generate/errors [entry ...]}
```

This is an invocation-local projection, not a stored entity taxonomy or a
second source format. The persisted facts remain the raw model reply, eval
entries, program graph, plan steps, messages, and runs.

Mechanical rules:

1. Parse the complete reply exactly once.
2. Keep comments and `#code` bodies attached through the parser's existing
   narration/source spans.
3. Start a unit only at a valid `(ns <symbol> ...)` form.
4. Associate later forms with that unit until the next namespace declaration.
5. Resolve `schema/register!` only from the declaration's actual alias/refer
   map; do not classify arbitrary calls by suffix alone.
6. Treat forms before the first namespace as root comments or an explicit
   unassigned error; never guess a namespace.
7. Derive edges only between generated namespace symbols. Existing required
   namespaces are load prerequisites, not generated plan steps.
8. Reject a generated requirement cycle as a graph error and return it to the
   planner before evaluation. Do not invent an evaluation order for a cycle.

## Schema-first evaluation

For each dependency-ready namespace unit:

1. evaluate its original namespace declaration;
2. evaluate its classified `schema/register!` forms in source order;
3. evaluate the remaining definitions and tests in source order; and
4. run the smallest affected behavioral test gate.

Across units, dependencies release dependents only after this complete gate.
This yields early data-model feedback without creating a schema language or
moving schemas outside their owning namespace. If a referenced generated schema
lives in another namespace, the ordinary `:require` edge already orders it.

Accepted forms are never re-sent merely to produce one large all-or-nothing
eval. Failed forms and their exact errors are handed to the namespace repair
agent with the accepted prefix visible. Explicit `ns-unmap` remains the only
function/test deletion instruction. Schema deletion stays unsupported until
the existing schema owner gains a principled public operation.

## Scheduling and completion

The normalized ready query returns open namespace leaves whose requirements are
done and whose claim is absent. One observer owns this sorted frontier.

For each candidate:

1. preallocate an assignment message identity;
2. transact `[:db.fn/cas step :my.plan/claim nil message-id]`, the message, and
   `:my.plan/message` together;
3. only after success, ensure or delegate the namespace worker; and
4. let the addressed message wake exactly that worker.

The coordinator, not model prose, decides completion. A namespace step becomes
done when its current unit has no unresolved parse/eval failures, every required
behavioral gate passes, and the corresponding worker run is closed successfully.
If the planner's first pass already satisfies those conditions, no worker is
launched and the coordinator marks the step done directly. Empty output or a
word such as `completed` is never completion evidence.

A recoverable worker failure leaves concrete eval/test failures and an error
message, then readdresses the same warm namespace worker. An impossible or
external failure uses `my.plan/blocked!` and transacts an addressed message to
the planning coordinator or caller in the same operation. That message wakes
only the owner, which can revise/reopen the durable plan.

Warm reuse is derived rather than configured as another registry. For a target
namespace, query prior successful namespace steps, follow the assignment
message recipient and run cause, and prefer an idle live agent. Otherwise
delegate a new child. A later `generate-code!` request can therefore reuse the
agent that previously worked on that namespace and its transcript context.

## Context contracts

### Planning agent

The planning agent receives:

- the caller's goal, description, and falsifiable expectation;
- the complete production namespace summary catalog as a stable cache prefix;
- deterministic and optionally embedding-augmented relevant namespace cards;
- full source for the closest owners and their `.internal` descendants;
- compact schemas and public contracts for requirement neighbors;
- exact instructions and examples for `schema/register!`, `(ns ...
  (:require ...))`, `defn`, `deftest`, `;` prose, and `#code`; and
- explicit upsert and deletion semantics.

It is shown correct Seon code, not cleanup folklore. Mechanical repair is a
safety net that preserves intent and produces errors when intent is ambiguous.

### Namespace repair agent

Each namespace worker receives:

- the complete root goal, description, and expectation;
- the original full planner reply;
- its namespace unit and exact accepted/failed entry ledger;
- full current source for its target namespace and all of its `.internal`
  descendants;
- compact cards for its requirements and generated dependents;
- a compact view of sibling namespace steps and their status; and
- the exact REPL and affected-test instructions.

It does not automatically require a parent namespace. Public namespaces
normally require their internal implementation namespaces; making children
require parents would create cycles. Context inclusion and code requirements
are separate decisions.

### Calling agent

The small caller's own transcript contains its original `generate-code!` call
and the returned compact result, just as for another async function. The full
planner reply and repair transcripts live with the planning and namespace
agents and remain addressable through the root plan, messages, runs, evals, and
program facts. The return value should summarize:

- root plan id and terminal/open/blocked state;
- namespace symbols changed;
- accepted, failed, deleted, and test counts derived from facts;
- remaining blocked steps and their error handles; and
- the database value at which the result was derived.

It should not inline the full plan or pretend to be a textual patch. The caller
can inspect the durable plan or invoke `generate-code!` again with follow-up
acceptance criteria.

## Risks and gates

| Risk | Design response | Graduation proof |
|---|---|---|
| Namespace declaration failure contaminates later forms | Fence units before eval | failed first unit cannot define into previous namespace; later unit remains recoverable |
| Schema reorder changes local keyword resolution | Establish real ns first | local `::key` registers under target namespace |
| Concurrent observers duplicate workers | scalar CAS plus message/ref in one tx | two schedulers yield one message and one worker run |
| Crash after claim | claim and assignment are durable; host is ensured from message | restart resumes claimed uncompleted work without duplicate assignment |
| Omitted definition is mistaken for deletion | deletion is explicit `ns-unmap` | re-generation retains omitted defs and removes only explicit targets |
| Embeddings are unavailable | deterministic retrieval is primary | planning succeeds with `SEON_EMBED` absent |
| Retrieval hides implementation internals | selected owners include `.internal` descendants | planner cites and reuses internal seams without exposing them as public API |
| Strong planner invents APIs | actual indexed contracts plus eval/tests | seeded model battery measures compile, schema, reuse, and behavior accuracy |
| K3 consumes cap before visible output | explicit escalation, sufficient cap, timeout/cost reporting | empty length result is an error value and does not schedule work |
| Warm worker retains stale assumptions | every assignment carries current database-derived unit/context | second request reuses worker but follows current source and database value |

## Recommended MVP boundary

The MVP should prove one goal producing two acyclic namespaces where the first
pass contains at least one schema or function error. It must:

- create a root plus two derived namespace steps;
- evaluate the valid schema and code prefix;
- avoid launching a worker for an already-green namespace;
- send the failed namespace to one reusable worker;
- derive completion from eval and test evidence;
- release its dependent reactively without polling;
- survive a coordinator restart between claim and worker completion; and
- return a compact result to a caller using a small execution model.

Do not enable deletion, cyclic namespace rewriting, broad embedding changes,
or automatic K3 routing in the MVP. Those are follow-on gates after the one
path is live and measured.
