---
type: research
status: active
tags: [research, agent, architecture]
---

# `generate-code` quarry — from pod scheduler to fault-routed owners

Owner request, verbatim: “`seon.ai/generate-code`... this is an older idea that
is in the git history or `src-old`. Launch an agent to dig it up.”

## What existed

### Verdict

The remembered idea existed twice.

First, a real pod-era `seon.ai/generate-code!` workflow landed. One strong
planner emitted a multi-namespace ClojureScript reply; the REPL path parsed and
evaluated it in dependency order; successful namespace units completed from
eval/test evidence; and a root-scoped scheduler CAS-claimed remaining units for
unique namespace-resident agents. It was substantial and partially
live-proven, but never graduated the promised warm repair loop or a successful
evidence-derived terminal delivery.

Second, the 2026-07-26 fresh-architecture sketch removed the coordinator and
sharpened the insight: one agent attempts the whole program, committed red
facts route by provenance to namespace owners, successes compose at the
current database value, and the planner wakes again only for residue or
escalation. A fake-agent Flow prototype proved coordination laws, not the
product: its evidence says `:prototype-only? true` and `:llm-calls 0`
(`tmp/plan-evidence/generate-code-loop-2026-07-26.edn:1-21`).

### Artifact and evidence ledger

| Artifact | Shape and maturity |
|---|---|
| Commit `68d19cca`; `3f1895b04^:src/seon/ai.cljs:745-774` | Landed public `seon.ai/generate-code!` wrapper |
| `3f1895b04^:src/seon/ai/generate_code.cljs:1-771` | Final deleted orchestrator: launch, observe, CAS claim, dispatch, recovery, terminal delivery |
| `fd304e8c1^:test/seon/ai/generate_code_test.cljs:1-818` | Final deleted focused tests for claims, dispatch, recovery, terminalization, retrieval |
| `src-old/seon/repl/parse.cljc:1424-1517` | Surviving parse-once, namespace projection and generated dependency order |
| `src-old/my/plan.cljc:574-660,1179-1315` | Exact-batch evidence acquisition and generated-program publication |
| `src-old/my/plan/internal.cljc:797-904` | Positive completion evidence and durable namespace DAG |
| `docs/prds/generate-code/roadmap.md:52-96,600-701` | Live results and remaining strands |
| `docs/prds/generate-code/research/audit-execute-code-pipeline-2026-07-21.md:14-180` | Landed/partial/missing boundary |
| Three archived issues named below | Observed terminal, retry and self-recipient failures |
| `docs/prds/sci-execution-runtime/research/generate-code-loop-2026-07-26.md:64-175` | Coordinator-free redesign |
| Commits `fad0559a2`, `af04d4eb9`, `b6d5a9d54`, `cd74cbf14` | Fresh sketch through fake-agent Flow proof |
| `src/seon/flow.clj:686-768`; `test/seon/flow/loop_test.clj:14-35,476-706` | Current prototype code and 60 seeded trials |
| `docs/prds/sci-execution-runtime/research/renderable-corpus-plan-2026-07-28.md:653-721` | Current §7 target and four preconditions |
| `docs/seon/architecture/toolkit.md:81-106`; `context.md:289-324` | Present namespace stewardship and context target |
| `docs/prds/sci-execution-runtime/research/local-provider-2026-07-28.md:109-168` | Local Qwen executable-form proof |

History confirms deletion rather than relocation: `3f1895b04` deleted the
771-line orchestrator, `fd304e8c1` deleted its 818-line test, and `f25e34594`
split surviving quarry into `src-old/` and `test-old/`.

### The landed function surface

Immediately before deletion, the public contract was:

```clojure
(defn ^{:async true} generate-code!
  "Hand one difficult goal to a stronger planning agent.
  ...
  The COMPACT derived result ... arrives later as an ordinary addressed
  message when the root reaches :done or :blocked."
  {:malli/schema
   [:=> [:cat :seon.ai.generate-code/generate-request]
         :seon.ai.generate-code/generate-response]}
  [request]
  (await (generate-code/start-generation! request)))
```

Source: `3f1895b04^:src/seon/ai.cljs:749-774`.

The request reused `my.plan` vocabulary: `:my.plan/goal`, optional
`:my.plan/description` and `:my.plan/expect`, plus the injected caller id. The
immediate response named the root and planning agent; a later compact terminal
result arrived as an addressed message
(`3f1895b04^:src/seon/ai/generate_code.cljs:92-107,701-771`). It was an
explicit effectful API, not an automatic phase of every turn.

`start-generation!` launched a `:planning` model-variant agent, committed root,
assignment message and claim together, then installed an `:execution`
scheduler. Provider selection remained agent birth data. Real drives attempted
Kimi K3, then substituted Muse after two 300-second Kimi timeouts
(`docs/prds/generate-code/roadmap.md:52-60`).

### Planning and evaluation

The planner emitted ordinary code, not a private plan language:

> “The planning model emits ordinary ClojureScript REPL input, not EDN plan
> data, JSON, Markdown, or a namespace manifest.”

Source: `docs/prds/generate-code/roadmap.md:175-179`.

`parse-program` read the reply once. `project-program` fenced entries under
valid `(ns ...)` declarations, recognized `schema/register!` through actual
aliases/refers, derived require edges, and rejected cycles before evaluation
(`src-old/seon/repl/parse.cljc:1424-1517`). Its key safety contract was:

> “Namespace declarations fence subsequent entries. A malformed declaration
> never lets later forms fall through into the previous namespace.”

Source: `src-old/seon/repl/parse.cljc:1424-1432`.

Per namespace, the evaluator ran the declaration, schemas, remaining forms and
affected behavioral gate. Generated dependencies ordered namespaces; authored
order remained within phases; an independent namespace survived a sibling
failure (`docs/prds/generate-code/roadmap.md:226-244`).

### Failure splitting and ownership

The exact parsed program and ordered eval batch crossed into
`my.plan/publish-generated-program!`; it did not reparse a reply blob. That
function acquired one current database value, read only the exact eval IDs,
derived completed namespaces from terminal eval/test evidence, and reconciled
unfinished namespaces as ordinary `my.plan` children connected by
`:my.plan/needs` (`src-old/my/plan.cljc:574-660,1179-1315`).

A namespace completed only when the exact batch was complete, the latest
progress eval was terminal, its native test summary was green, and no later
target-scoped error existed (`src-old/my/plan/internal.cljc:797-879`). Worker
prose never closed a unit. The compiler described itself as:

> “This is the generated-code specialization of the existing durable plan,
> not a second task model.”

Source: `src-old/my/plan/internal.cljc:892-904`.

For each ready unfinished step, the scheduler ensured a resident for its
`:seon.ns/name`, then committed CAS claim, addressed assignment message and
plan-message link in one transaction
(`3f1895b04^:src/seon/ai/generate_code.cljs:287-352,379-455`).

“Namespace agent” meant a persistent reusable resident connected by the unique
`:seon.agent/namespace` ref, not a disposable task:

> “Return the live resident for a namespace, birthing it when absent. ... The
> agent remains idle until an ordinary inbound message commits and wakes it.”

Source: `3f1895b04^:src/seon/agent.cljs:957-984`. The schema survives at
`src-old/seon/agent/core.cljc:6-24`. The intended preference for an idle agent
that had previously completed the namespace never landed
(`docs/prds/generate-code/research/audit-execute-code-pipeline-2026-07-21.md:140-150`).

### Honest maturity

| Layer | Result |
|---|---|
| Multi-namespace parse/eval | Landed, tested and live-proven |
| Durable namespace DAG and positive completion derivation | Landed |
| CAS claim, addressed assignment, recovery, blocked terminal | Landed and live-proven |
| Public wrapper and planner/executor routing | Landed |
| Real whole-program drive | Partial success: 18/18 forms, multi-unit DAG, function and test facts, blocked envelope |
| Failed namespace repaired by warm owner and dependent released | Not proven |
| Evidence-derived successful terminal and compact `:done` message | Not proven |
| Kimi planner quality | Not proven; two live timeouts |
| Product loop | Not graduated |

The strongest drive was meaningful: an 18-form Muse program evaluated 18/18
across hot reload, published multiple namespace units, committed function and
passing-test datoms, and delivered clean blocked envelopes
(`docs/prds/generate-code/roadmap.md:67-84`; `da845f887`).

The apparent green roots were not proof. The planner called an ordinary
`my.plan` transition itself, left namespace progress open and skipped the
compact terminal message
(`docs/seon/issues/archive/planner-self-done-bypasses-generated-terminal-delivery.md:10-39`).
No evidence found shows a failed first-pass namespace repaired by its warm
resident and then releasing a dependent.

The later redesign said:

> “the planner is one agent with the complete problem, and it attempts the
> whole program at once.”

Source: `generate-code-loop-2026-07-26.md:64-67`.

Program-graph writes became the patch language. Red facts routed by provenance;
owners fixed locally; and the planner re-planned at the current database value
with accepted sibling work still present. It added accrete-first admission and
spec-first economics: the strong model authors data, schemas, relational
properties and tests; cheaper local agents iterate implementations
(`generate-code-loop-2026-07-26.md:69-90,137-160`).

The surviving fake Flow proves only seeded termination, no self-wake,
inclusive idempotent escalation, current-basis re-plan and
rejection-as-value. The test itself says “PROTOTYPE ONLY”
(`test/seon/flow/loop_test.clj:14-15`).

## The lessons

### What worked

- Ordinary code was the right planner output. Accepted definitions entered the
  same program facts as other evals.
- Namespace fencing, actual require edges and exact alias resolution made
  parse/project/evaluate a coherent boundary.
- Exact eval IDs and test summaries were stronger completion evidence than
  model claims or textual markers.
- CAS made claim, addressed message and plan connection one race-safe fact.
- One current database value preserved partial success without a merge
  coordinator.
- Expected rejection remained data rather than a Flow core fault.

### What struggled

- The root observer, scheduler and recovery registry created a second lifecycle
  beside agent runs. The fresh architecture rejects that dispatcher shape.
- The repair assignment was only a pointer. The promised bundle—original
  vision, accepted prefix, exact failures, sibling status and current local
  source—never landed
  (`docs/prds/generate-code/research/audit-execute-code-pipeline-2026-07-21.md:80-92,140-149`).
- Two terminal owners let planner action bypass evidence-derived delivery.
- A no-reply retry opened a run whose cause no longer joined the root
  assignment, stranding Kimi roots
  (`docs/seon/issues/archive/generated-root-has-no-planner-retry-path.md:9-25`).
- A planner scratch namespace could become a self-addressed repair step and
  block dispatch
  (`docs/seon/issues/archive/planner-home-ns-step-blocks-on-self-recipient.md:13-30`).
- Kimi invented APIs, omitted a schema arm, added indirection and misplaced
  tests despite plausible code
  (`docs/prds/generate-code/research/design-seam-audit-2026-07-19.md:330-346`).
- A failed eval wrote `:seon.eval/ns nil` against a symbol schema, dropping the
  row and the provenance routing required (`da845f887`).
- Exact Datahike node-count budgets and whole-database equality fences were
  brittle; the rerun replaced them with honest caps and the root CAS.

The most valuable lesson is: **evaluate the ambitious whole-program pass once,
preserve every accepted fact, and bisect only red residue to owners through
queryable provenance.** The old system failed wherever attribution was lossy
or scheduling stored a parallel interpretation of that rule.

### Already subsumed versus genuinely missing

| Old responsibility | Fresh owner |
|---|---|
| Messaging and wake | `my.message` values; driver-committed message facts; `:seon.cluster.message/to` wake |
| Sequential runtime | One Flow graph per agent, parked between episodes |
| Iteration bound | Derived per-agent episode cap |
| Failures | Flat agent error values and durable core error facts with provenance |
| Fixer context | Namespace-centered rendered database view |
| Sibling success | Current immutable database value and native `since` |
| Eval outcomes | Reply → frozen sources → SCI receipts |
| Parallelism | Independently waking assigned owner flows |

Sources: `src/seon/schema/message.edn:1-80`,
`src/seon/cluster/agent.clj:1-45,203-227`,
`src/seon/cluster/work.cljc:133-242`,
`src/seon/schema/error.edn:38-108`, and
`docs/seon/architecture/context.md:289-324`.

The fresh tree also has real local-model generation/evaluation: Qwen returned
three executable forms, Seon froze and evaluated them, committed three terminal
receipts, and returned `"55"` (`local-provider-2026-07-28.md:109-168`).

Current plan §7 nevertheless places bisect-to-owners behind four preconditions:

1. queryable failure refs for function, schema, test, namespace and call root;
2. test-result facts linked to the exact test and batch/attempt;
3. unique namespace assignment to at most one agent; and
4. idempotent multi-failure delivery with durable commit evidence.

Source: `renderable-corpus-plan-2026-07-28.md:679-697`.

The current error row has operational provenance but often stores source
detail in `:seon.error/data-edn`, a string; printed schema keys are not Datalog
joins. `bin/test` returns counts but no exact test fact. Namespace assignment
and delivery evidence are not yet all landed. The just-launched
namespace-assignment and test-results-as-facts units are therefore real
preconditions, not generate-code implementation.

After P1–P4, the genuinely missing product layer is small but real: one pure
failure-to-owners/residue query; one idempotent delivery transaction; a global
stopping derivation; current-basis acceptance; escalation compatible with the
episode cap; a product-level `my.*` surface; and the accretion/batch admission
rule. The fake prototype supplied a controlled predicate, not breakage
detection.

## The resurrection sketch

Do not restore `seon.ai.generate-code`, its observer registry, root scheduler,
old forgiving parser, generated plan statuses or pod-side lifecycle calls.
Fresh `src/seon/cluster/reply.cljc:1-48` intentionally replaces the old
1,517-line parser with SCI reading into ordered source strings.

### Whole-program attempt

At a pinned database value, the planner receives the human goal and falsifiable
acceptance, the current namespace-centered view, affected contracts/tests and
the accrete-first admission rule. It emits ordinary Clojure forms. The strong
model spends its limited episode on data model, complete Malli contracts,
relational properties and behavioral tests before implementation.

Forms use the existing source splitter, frozen run plan, SCI evaluation and durable
receipt path. There is no second evaluator. One attempt/batch identity links
the vision to resulting receipts and test facts.

### Bisect only behind the four preconditions

Once P1–P4 exist, derive:

| Red fact | Owner join |
|---|---|
| Form | function ref → function namespace → assigned agent |
| Schema | schema ref → schema namespace → assigned agent |
| Test | exact result ref → test namespace → assigned agent |
| Cross-namespace call | call root + calls reachability → involved namespaces |
| Unattributable | loud root residue; never prefix guessing |

This is plan §7 (`renderable-corpus-plan-2026-07-28.md:699-721`). Group by
owner but retain exact refs, not copied error prose. One transaction commits
idempotent messages and delivery evidence. The global episode stops; messages
wake ordinary owner flows.

An owner's namespace view supplies current source, contracts, dependents and
local signals. The message adds only global vision, attempt/batch and exact red
refs. Owners fix through ordinary evals. Successful transactions immediately
change the current program; acceptance re-runs at the new basis and preserves
sibling success.

No owner self-wakes. A new message starts a new outside episode. Existing
episode caps bound work. Persistent or unattributable residue returns to root,
which may ask the strong planner to re-plan at the current basis. Done is not
planner prose: it is no red affected result at the acceptance basis plus
durable evidence for what ran.

### What a local-model v0 can exercise today

The Qwen endpoint has already driven a real fresh turn through executable
forms and terminal receipts. A bounded v0 can therefore:

1. give one planner agent a two-namespace-shaped goal and request contracts,
   tests and implementations as ordinary forms;
2. retain exact prompt, provider attempt and initial database value;
3. run the reply through the fresh splitter and SCI receipt path;
4. inspect accepted values and flat failures at the final database value; and
5. compare initial and final bases to measure contract correctness.

This exercises only the first half. The fresh splitter does not reproduce old
dependency-ordered multi-namespace admission, so v0 cannot claim that. Until
P1–P4 land, it must not synthesize owner assignments or call routing a query.
The real local-model pass and fake-agent Flow may be shown as separate
generation/eval and coordination proofs, never as an end-to-end product.

The first honest integrated v1 starts only after namespace assignment and exact
test-result facts land, P1 and P4 close, and the §7 falsifier passes: schema
conflict in A, failing test in B, throwing form in C, one unattributable
residue, three durable owner messages, and no re-attempt of delegated parts.
