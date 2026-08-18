---
type: research
status: complete
tags: [research, runtime, agent]
---

# Model-authoring re-drive — default cluster, 2026-08-08

## Verdict

**The milestone was not reached, and it could not have been: the entire live
context-rendering path is down at this HEAD.** Every agent turn receives, as its
ENTIRE prompt, a `seon.render.walk/neighborhood` `invalid-output` contract
violation — 336 provider tokens of render-error text — instead of its REPL
session, instructions, and the human's message. A real model (deepseek-v4-flash)
did run: it made six paid provider calls, each burning 10–14k reasoning tokens,
and authored nothing, because the only thing in its prompt was an error about a
broken renderer.

This is a **regression at the freshly-reforked HEAD**, not a task failure and not
a repeat of the previous drive's walls. The two walls that blocked the prior
drive are genuinely down — I verified call preparation is now installed on the
cluster ctx (`seon.sci.eval/cluster-ctx` calls `call-preparation/install`,
`src/seon/sci/eval.clj:1533`). But a different, upstream defect now collapses
every prompt before delegation, authoring, or message flow can even be attempted.

I read end to end before starting, as instructed: the
[blocked arc drive](whole-system-arc-drive-2026-08-08.md), the
[observer method notes](whole-system-arc-observer-2026-08-08.md) (its
silent-empty-query and literal-eid traps both bit me and are recorded below), and
the [arc spec](../plan/whole-system-arc-2026-08-08.md).

## Scope

- cluster `default`, operator root `/Users/sean/src/seon`, web
  `http://127.0.0.1:7994`;
- JVM pid 14148, prepl 58540, booted 2026-08-08T11:49:31Z, `:seon.boot/ready-ms`
  5,380;
- model `deepseek-v4-flash`, owner-pre-authorized;
- **cluster never reset, reforked, or stopped** — lifecycle is the orchestrator's;
- one human message submitted (the word-count task); all other actions read-only;
- window 11:52Z → 12:07Z.

## The one thing to prove — verdict: BLOCKED

> A real model authors a contracted function end to end and it becomes callable.

**Facts-only proof of the negative** (single Datalog queries, post-window basis
536871094):

```clojure
{:word-count-fn   []          ; no :seon.fn/sym matches "word-count"
 :agents          [["root"]]  ; delegation never happened; still one agent
 :runs            7           ; +4 root turns since baseline of 3
 :receipts        0           ; ZERO receipts cluster-wide, ever
 :attempts        6           ; six paid provider calls
 :my-msg-run      "fe68fac3…" ; run whose :seon.cluster.run/trigger = my message}
```

The human message `inbound-536871012-0` (eid 25876, 11:53:15Z) asked root to
define a contracted `word-count` with `:malli/schema [:=> [:cat :string] :int]`,
call it, and `(my.run/complete …)`. It was claimed by run `fe68fac3` (25909),
whose `:seon.cluster.run/trigger` is exactly 25876 — so custody and claiming
worked. But that run's provider prompt was 336 tokens of the render error; root
replied with prose debugging `seon.render/walk`, defined nothing, completed
nothing. `word-count` is absent from the program graph.

Every acceptance sub-claim of the milestone therefore fails for one upstream
reason: **the model never saw the task.**

## Root cause — a shared key with two meanings, tightened on one side

`seon.render.walk/neighborhood` violates its OWN output contract on every
non-empty walk. Reproduced live at distance 0 on a perfectly healthy entity:

```clojure
(neighborhood {… :seon.render.walk/lookup [:seon.cluster/name "default"]
                 :seon.render/distance 0})
⟹ invalid-output: [#:seon.render{:output
;;      [{:value "Cluster default.\nConfiguration default and bootstrap plan
;;                :default; 1 shared instruction and 7 toolkit namespaces.",
;;        :message "should be either :seon.render/ai or :seon.render/html"}]}]
```

That `:value` is a correct AI render string. It fails anyway, because:

- Commit `102fdeac3` ("Make seon.render.edn editable…") retyped the shared key
  `:seon.render/output` from `:any` to `[:enum :seon.render/ai :seon.render/html]`.
  That is correct for the request map's projection **selector**
  (`resources/seon/schemas/seon.render.walk.edn:29-30`).
- But `:seon.render.walk/unit` reuses the SAME key for each unit's rendered
  **output field** (`seon.render.walk.edn:55-57`), and that field holds the
  actual rendered value — a `String` for AI, Hiccup for HTML — assigned at
  `src/seon/render/walk.clj:462` (normal), `:455` (failure), `:323` (elision).
- So every unit's rendered output is validated against a two-keyword enum and
  fails. Rendered text can never equal the literal keyword `:seon.render/ai`.

**The failure is universal**, proven above on a clean entity — it is not the
producer-specific bare-string bug the existing issue first described. There is no
producer defect: the value is correct; the field's declared type is wrong.

**Consequence chain** (each link falsified live):

1. `neighborhood` fails its output contract on any non-empty walk.
2. Prompt assembly (`seon.cluster.prompt/prompt` → `render/acquire-context!`)
   gets the contract-violation error as the walk text — a 336-token prompt.
3. `renderer-failure` (`src/seon/render.clj:479-519`) messages the namespace
   OWNER: "A renderer in <ns> failed… repair its declared contract." Root owns
   `my.agents.root`, so each collapse re-wakes root with another broken prompt —
   a paid self-waking loop.
4. Root spends every turn reasoning about how to fix `seon.render/walk`, never
   sees human messages, never completes.

This was already filed (by the sibling observer, during my window) as
[every-agent-prompt-is-a-neighborhood-render-walk-contract-violation.md](../../../seon/issues/every-agent-prompt-is-a-neighborhood-render-walk-contract-violation.md),
severity blocker. I appended the pinned cause and one correction that matters:
**the issue's proposed fix — producers tag items as `{:seon.render/ai …}` — will
not work**, because a tagged map fails `[:enum :seon.render/ai :seon.render/html]`
exactly as a bare string does. The fix must **split the shared key**: give the
unit's rendered-output field its own string-or-Hiccup value type, distinct from
the request selector. That is a schema-EDN + re-registration change (not
hot-reloadable) and a render-contract design decision with blast radius across
every walk — an owner/render-owner call, which is why I did not make it.

**No independent path exists.** The outage is universal across all agents, so
creating a fresh agent would fail identically; door-mode eval bypasses the model
entirely. The milestone cannot be proven until this schema is fixed and the
cluster reforked from the fixed HEAD. Reported to main; lifecycle is the
orchestrator's.

## What is confirmed fixed from the previous drive

- **Call preparation is installed.** `seon.sci.eval/cluster-ctx` now calls
  `call-preparation/install` (`src/seon/sci/eval.clj:1533`) and `watch!`
  (`:1543`). The previous drive's arc-blocking defect 1 ("install has no caller
  in src/") is resolved in source. It could not be exercised end-to-end here
  because delegation never ran, but the wall is structurally gone.

## Token sentinel — a pathological inversion, upstream-caused

All six attempts, from durable `:seon.ai.attempt/usage-edn`:

| At (UTC) | Run | Prompt | Completion | Reasoning | C:P | Cache hit |
|---|---|---:|---:|---:|---:|---:|
| 11:49:47 | 1de9beeb | 336 | 14,531 | 14,175 | 43.2 | 0 |
| 11:52:00 | 8988f92b | 336 | 14,641 | 14,284 | 43.6 | 256 |
| 11:54:11 | 599645bd | 336 | 13,579 | 13,268 | 40.4 | 256 |
| 11:56:11 | fe68fac3 | 336 | 10,516 | 10,215 | 31.3 | 256 |
| 11:59:40 | 04f10637 | 336 | 12,330 | 11,874 | 36.7 | 256 |
| 12:05:00 | 387f3046 | 336 | 8,437 | 8,132 | 25.1 | 256 |

`fe68fac3` is the run triggered by my word-count message.

**Verdict: the sentinel is breached, in exactly the shape the spec named to watch
for.** The completion:prompt ratio reaches 43.6, at the 46.7 inversion the arc
spec flagged. But this is not runaway generation in the ordinary sense — the
prompt is pathologically SMALL (336 tokens, a collapsed error) and the model
reasons ~14k tokens trying to repair the render error it keeps being shown.
Reasoning is 96–97% of every completion, and `:seon.config.ai/thinking :disabled`
is still being billed as reasoning (a pre-existing finding, unchanged). Every one
of these is a paid call producing nothing. The calibrated estimator could not be
exercised against a real rendered context, because no real context ever reached
the wire.

## Deviations from the spec, named

1. **I asked root directly rather than proving delegation first.** The task
   permits "delegate to an agent (or directly ask an agent)"; I chose the direct
   path as the highest-probability route to the milestone. It made no difference
   — the block is upstream of both.
2. **The S4 latent transact site was never reached.** The brief flagged
   `src/seon/cluster.clj:1978` (`seon.db/transact!` shape by dynamic var) as a
   watch item if a model-authored function transacts. No model-authored function
   ran, so this remains untested this drive.
3. **The fuller slate (second agent, inter-agent message, durable plan) was not
   attempted.** All of it depends on at least one agent receiving a coherent
   prompt, which never happened.

## Ugly output, verbatim

**The agent's entire prompt is a render-internal contract error.** The exact
336-token prompt every turn received (the run-form prose is the model reacting to
it):

```text
seon.render.walk/neighborhood violated its contract (invalid-output):
 [#:seon.render{:output [{:value "Renderer unavailable.",
   :message "should be either :seon.render/ai or :seon.render/html"}]}
  #:seon.render{:output [{:value "elided connections at the requested distance cap",
   :message "should be either :seon.render/ai or :seon.render/html"}]}
  … 1 more subtree; requery refused: no stable identity was supplied at path []
   offset 0 with :seon.render.profile/unspecified]
```

**`GET /` hung past 120 s** (the previous drive served it warm in 15 ms). The
root page render walks the same broken neighborhood; I did not re-request it.
Diagnosis moved to surgical JVM reproduction instead.

**The declaration-population fallback storm is worse, and it is now a timing
hazard.** A single `neighborhood` reproduction emitted `DECLARATION POPULATION
FALLBACK ×1000 — seon.print (print.cljc:232)` and `×1000 — seon.db (db.clj:430)`
and took 13.2 s at distance 0. The owning issues already exist; this is one more
confirmation that the volume is climbing.

**`bin/seon status`-class record-unreadable furniture** and the multi-line
fallback preamble on nearly every `eval_clj` return persist unchanged from the
previous drive; both already have owners.

## What is genuinely in good shape

Calibration, not alarm:

- **Custody and claiming.** My human message was claimed by exactly one run,
  whose `:seon.cluster.run/trigger` is exactly that message; every open run held
  process `14148-1786189771699`, every closed run had shed it. Concurrency was
  low here, but the mechanism is clean.
- **Diagnostics did the diagnosis.** `:seon.ai.attempt/usage-edn` made the
  336-token collapse visible; `:seon.cluster.run/trigger` tied my message to its
  run; the run-form sources showed exactly what the model saw; a single
  `neighborhood` REPL call falsified the universality. Cheap correct diagnosis is
  what turned "the agent won't do the task" into "the field type is wrong" in one
  sitting.
- **Refusals are loud and typed.** The contract violation names the exact field,
  the offending value, and the expectation — which is precisely how the cause was
  found. The mechanism that broke the walk is also the mechanism that explained
  it.
- **Call preparation is wired.** The previous drive's largest wall is gone in
  source.

## Method notes (traps that bit me, recorded)

1. **A literal entity id in a ref value-position returns empty, silently.**
   `[?f :seon.cluster.run.form/run 25872]` returned `[]` while
   `[?f :seon.cluster.run.form/run ?run]` bound 25872 to 6 forms. Always bind a
   var, never inline the eid, when the attribute is a ref.
2. **`(seon.db/db)` on a raw REPL thread errors** with
   `:seon.db/missing-connection-binding` — custody is elided only inside an agent
   eval. I bound `seon.db/*conn*` to the running instance's
   `:seon.boot/cluster-connection` for every read.
3. **A wrong `from`/`claimed-by` attribute returns empty, indistinguishable from
   "nothing claimed."** The real claim link is `:seon.cluster.run/trigger`, run →
   message. Confirm the ident before trusting an empty result.
