---
type: research
status: active
tags: [research, agent, context]
---

# Minimum-context ablation — independent observer account

I am the independent observer for the drives of
[the minimum-context ablation plan](minimum-context-ablation-plan-2026-08-11.md),
which I read end to end before observing anything. I trusted no number the
driver produced: every figure below comes from my own Datalog against each
drive's own store, opened from the durable store directory after that drive's
JVM released its flock. My probes are committed under `tmp/ablation/`
(`observe.clj`, `grade_probe.clj`, `grade_probe2.clj`, `ns_probe.clj`,
`attr_probe.clj`); my raw accounts are `tmp/ablation/observer/<variant>.edn` and
the verbatim sent prompts are `tmp/ablation/observer/<variant>-prompt-<n>.txt`.

## Verdict in one line

**The measurements are faithful; the experiment is not yet interpretable.** Every
sent prompt was byte-exact for its variant and every attempt used
`deepseek-v4-flash`, so the harness did what it claimed. But three independent
defects — an unsatisfiable third grade criterion, a first-turn provider transport
failure in two of four drives, and a task instruction that contradicts
`my.run/complete`'s contract — mean the recorded success column measures the
harness, not the context. Nothing here supports a minimum-context conclusion yet.

## Per-variant verification

| Variant | Prompt SHA-256 matches variant file | Sent chars / est. tokens | Models on every attempt | My grade (contract fact / call receipt / contract query) | My steering errors (all runs) | Verdict |
|---|---|---|---|---|---:|---|
| FULL | yes — `b02d002f…0777`, both captures identical | 43 738 / 13 809 | `deepseek-v4-flash` only | present `[:=> [:cat] :int]` / present / **absent** | 4 (all bootstrap) | measurement verified; grade unsatisfiable |
| HALF | yes — `049b9a65…9891`, all three captures identical | 23 484 / 7 389 | `deepseek-v4-flash` only | present `[:=> [:cat] :int]` / present / **absent** | 4 (all bootstrap) | measurement verified; grade unsatisfiable |
| QUARTER | yes — `945a17a4…bdef`, all three captures identical | 5 996 / 1 873 | `deepseek-v4-flash` only | absent / absent / absent | 10 (4 bootstrap, 6 agent) | genuine failure, but confounded |
| FLOOR | yes — `212ed0df…4b6d`, both captures identical | 5 357 / 1 674 | `deepseek-v4-flash` only | absent / absent / absent | 4 (all bootstrap) | **no usable data** — agent never evaluated a task form |

Both prompt-hash and model checks are unqualified passes. The recorded
`:seon.ai.tokens/characters` equalled my own `count` of the prompt string in
every capture, and every capture within a variant was byte-identical.

## Provider usage — what was actually paid for

Read from each attempt's `:seon.ai.attempt/usage-edn`, in attempt order:

| Variant | Attempts | `prompt_tokens` per attempt | `prompt_cache_hit_tokens` | `completion_tokens` |
|---|---:|---|---|---|
| FULL | 2 | (transport failure, no usage), 13 585 | —, 0 | —, 17 952 |
| HALF | 3 | 7 229, 7 229, 7 229 | 0, 0, **7 168** | 12 770, 12 163, 19 810 |
| QUARTER | 3 | 1 773, 1 773, (transport failure, no usage) | **1 664**, **1 664**, — | 2 420, 3 404, — |
| FLOOR | 2 | (transport failure, no usage), 1 580 | —, 0 | —, 974 |

HALF and QUARTER are the two variants with a cross-turn cache observation. HALF's
third call hit 7 168 of 7 229 tokens (61 missed); both of QUARTER's usage-bearing
calls hit 1 664 of 1 773 (109 missed). **No collapse anywhere.** But this is not evidence for
the append-only ordering work, because the harness's interception replaces
`seon.cluster.prompt/prompt` with one frozen string for the whole episode — every
turn sends identical bytes, so a cache hit is guaranteed by construction and
proves only that DeepSeek caches an identical prefix. The live prefix-stability
question the PRD asks is untouched by this experiment.

## Disagreements with the driver's records

At the time I finished, the plan's own results table was still the empty
skeleton — the driver had not committed a filled table. The driver's records I
compare against are therefore its four written result values,
`tmp/ablation/results/{full,half,quarter,floor}.edn`, produced by
`ablation.run-variant/result`. If a committed table later differs from those
values, this section applies to the values, not to the prose.

1. **Provider prompt tokens are understated.** For HALF the driver recorded
   `:minimum-context.result/provider-prompt-tokens 7229`; three attempts were
   actually sent and billed, totalling **21 687** prompt tokens and 44 743
   completion tokens. QUARTER: driver `1773`, real **3 546**. The driver sums
   usage only over attempts belonging to the episode's
   `:seon.eval.drive/run-ids`, which contains exactly one run in every variant.
2. **Cache-hit tokens are understated the same way.** HALF — driver `0`, real
   **7 168**. QUARTER — driver `1664`, real **3 328**.
3. **Turn counts are not turn counts.** Every variant records
   `turns-to-completion 1`, because only one run carries the objective message as
   its trigger. HALF and QUARTER each opened three agent runs, FULL two, FLOOR
   two. Worse, HALF's two work runs (`67608fdb…` opened 04:52:03 and `d8518d55…` opened
   04:52:04) ran CONCURRENTLY and both defined `cluster-agent-count` and both
   called `my.run/complete` — the same task done twice and billed twice. A third
   HALF run (`2375d967…`) was still open with no `:seon.cluster.run/closed-at`
   when the drive stopped the cluster. QUARTER repeated the pattern: `5828f31c…`
   and `38b46d81…` opened 138 ms apart and worked in parallel.
4. **Steering-error counts differ by scope, not by fact.** The driver counts
   receipts of the objective run (FULL 0, HALF 1, QUARTER 0, FLOOR 0); I count
   every receipt in the store (FULL 4, HALF 4, QUARTER 10, FLOOR 4). The four
   common ones are in the shipped bootstrap plan, which is the already-filed
   [bootstrap teaching failures](../../../seon/issues/bootstrap-teaching-failures-strand-every-new-agent.md)
   defect, not agent behaviour; QUARTER's other six are real agent-form errors
   described below. I record both numbers rather than calling either wrong.

## Findings that block interpretation

### The third grade criterion cannot be earned

The plan's third success clause is "another clean receipt belongs to a form
calling `seon.db/q` and carrying literal keyword `:seon.fn/spec`". I falsified it
directly on the FULL root:

```text
seon.db/q entity: 12765
forms calling seon.db/q: nil
run forms with any call edge: 7
```

The FULL agent's own form does call `seon.db/q` and does carry `:seon.fn/spec`,
and its recorded facts are

```clojure
#:seon.fn{:keywords [:seon.fn/spec :seon.fn/sym]
          :calls [#:seon.fn{:sym my.run/complete}
                  #:seon.fn{:sym my.agents.w1-history-proof-5/cluster-agent-count}]}
```

The `seon.db/q` edge is simply never recorded — not for agent forms, not even for
the shipped bootstrap forms that evaluate `(seon.db/q …)` at ordinals 5, 6 and 12.
So `:minimum-context.grade/success?` is false for all four variants for a reason
that has nothing to do with context size. Filed as
[agent form calls to core namespaces are not indexed](../../../seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md).

Judged on the two criteria that CAN be earned, FULL and HALF both did the work
correctly and FLOOR and QUARTER did not.

### QUARTER failed on a missing namespace, not on missing context

QUARTER's first work run opened with `(require '[clojure.pprint :refer [pprint]])`
→ `Could not find namespace clojure.pprint.`, and the same reply's next five
forms all used `pprint`, each failing `Unable to resolve symbol: pprint`. Those
six errors are QUARTER's entire agent-error count. It never attempted the `defn`.
Its concurrent second run managed only `(keys (ns-publics *ns*))` and
`(dir seon.db)` before a third run hit the same provider transport failure and
the episode ended. A smaller context plausibly contributed to reaching for
`pprint` at all, but the recorded failure is dominated by an unavailable
namespace and a batched reply that could not see the first error.

### The task instruction contradicts the API it names

The task ends `use (my.run/complete {:function 'cluster-agent-count …})`, but
`my.run/complete` takes non-blank reply TEXT (`src/my/run.clj:32-43`) and returns
a flat error for a map. Both successful agents had to notice and work around
this: FULL wrapped the map in `pr-str`; HALF wrote the comment
`;; my.run/complete requires a string, so serialize the requested map as EDN.`
and its other run wrapped the call in `try`/`catch`. A contradictory instruction
is exactly the kind of load a minimum-context experiment must not carry, because
recovering from it costs context the smaller variants do not have.

### Three of four drives hit the same provider transport failure

FULL, FLOOR and QUARTER each recorded a run ending with

> The provider answered 200 and then ended the stream before sending any
> assistant text. The transport ended with: java.io.IOException: closed
> <- java.net.http.HttpTimeoutException: request timed out

with an attempt carrying `:seon.ai/http-status 200` and no usage at all. For FULL
and FLOOR this was the FIRST turn; for QUARTER it was the last, ending the
episode. FULL recovered on a second run; FLOOR did not — its second run's reply was pure prose
("The reply carried no Clojure forms"), and the episode then stopped. **FLOOR
produced zero task receipts.** Its recorded failure is a transport failure plus
one prose reply, not evidence about a 1 674-token context.

### The raw model reply is not durable anywhere

I looked for it: no attribute containing `reply` exists in any drive's cluster
branch, and only parsed forms and receipts are stored. When a reply is entirely
prose — FLOOR's whole second turn — nothing durable records what the model said.
For an ablation whose interesting failures are reply-shaped, that is a hole in
the evidence, and I could not close it after the fact.

### Three drives were killed by a foreign lane's uncommitted edit

FULL, HALF and QUARTER all aborted at `cluster/refresh-source!` with
`:malli.core/invalid-schema` because `src/seon/flow.clj` briefly declared an
unregistered `:ifn` schema. The instance was fixed nine minutes later by
`e019ffbd8`; the roots those drives had already created are permanently unusable
because the runner refuses a non-fresh root. Filed as
[an unregistered `:ifn` schema breaks every source publication](../../../seon/issues/unregistered-ifn-malli-schema-breaks-source-publication.md).
QUARTER's relaunch then died a second way — `Timed out awaiting bootstrap
w1-history-proof-5` after the hard 120 000 ms in `seon.eval.drive/await-fact!`,
while three JVMs shared the machine — and needed a third root.

## Context quality — reading the agent's own bytes

I read the verbatim prompts, not a summary of them.

**An unstable memory address sits in the third entry of every prompt.** Every
variant contains

```text
my.agents.w1-history-proof-5=> (in-ns 'my.agents.w1-history-proof-5)
#object[sci.lang.Namespace 0x454fde80 "my.agents.w1-history-proof-5"]
```

and the same cluster's root agent recorded `0x3075cf37` for the identical
operation. A JVM identity hash this early in the prompt truncates the cacheable
prefix at the third entry in any un-frozen run. Filed as
[object identity addresses break prompt-prefix stability](../../../seon/issues/object-identity-addresses-break-prompt-prefix-stability.md).

**The requires projection silently discards 209 of 210 results.** The entry

```text
my.agents.w1-history-proof-5=> (db/q (quote [:find [(pull ?entity [*]) ...] :where [?entity :seon.ns/requires]]) db)
(ns seon.bootstrap (:require [clojure.edn :as edn] …))
…
;; 28 definitions omitted by the namespace render budget.
```

renders exactly one namespace. I counted the real matches on the FULL root: 210
namespaces carry `:seon.ns/requires` and 298 carry `:seon.ns/name`. The omission
notice describes the definitions inside the one surviving card and says nothing
about the 209 dropped namespaces, so an agent reading it concludes the cluster
has one namespace. In FLOOR and QUARTER this misleading entry is a large fraction
of the entire context. Filed as
[a collection render drops 209 of 210 results](../../../seon/issues/collection-render-drops-209-of-210-results-without-an-elision-value.md).

**What reads well.** The `(help)` prose is genuinely good: concrete, second
person, no scolding, and it teaches the REPL contract in one screen. The
`(dir my.message)` / `(dir my.run)` entries are clean. The task entry's
`From outside this cluster to w1-history-proof-5:` prefix is unambiguous. The
prompt is a real REPL session throughout — `my.agents.w1-history-proof-5=>`
prompts, forms, and their actual values — with no `Conversation` header and no
comment-prefixed pseudo-output. The premise-rotation verdict in the plan is
confirmed by the bytes.

## What would make the next run interpretable

1. Fix the `:seon.fn/calls` edge, or grade the contract query on the receipt's
   recorded form source rather than on an edge that is never written.
2. Change the task to call `my.run/complete` with the string it actually takes.
3. Count and sum usage over EVERY attempt in the cluster, not only the attempts
   of runs the objective message triggered.
4. Decide what prefix stability is being tested. A frozen injected prompt cannot
   test it. Testing it needs the real `seon.cluster.prompt/prompt` and a
   multi-turn task, with the identity address removed first.
5. Run the drives serially. Two of the four failures this evening were
   contention: three JVMs publishing source at once, and a 120 s bootstrap
   backstop firing under that load.

## Method note

Verification ran against each drive's durable store after its JVM exited, not
against a live cluster: the runner stops its cluster in `finally` and retracts
the advertisement, but the JVM then lingers for several minutes still holding
the store flock, so neither the MCP tools nor a direct open can reach the facts
until it exits. Nothing was lost — the cluster branch persists and the grading
branch is the only thing retired — but a live observer cannot watch these drives
mid-flight as they are currently launched.
