---
type: research
status: complete
tags: [research, runtime, agent]
---

# Whole-system arc drive — default cluster, 2026-08-08

## Verdict

**Stage 1 is half proven and the half that fails is one defect. Stage 2 is
unreachable because its mechanism does not exist. Stage 3 passes cleanly.**

The headline positive is real and is a first: **four agents ran concurrent live
turns in one cluster**, each owning a distinct namespace, each with its own
run, its own rendered context, its own provider call, and its own page. Custody
never wobbled. The restart mid-arc recovered exactly as the crash model
promises, and the arc's whole story is reconstructible from facts alone.

The headline negative is also one thing, not many: **root could not create the
three agents**, because call preparation — the mechanism whose entire purpose is
supplying an elided connection — is never installed on the cluster's sci
context. I created the agents from the operator side to let the arc continue,
and that is the drive's largest deviation from the spec.

Then a second, independent failure took the drive's remaining time: from
09:47:38 onward **every arc-agent turn died on a mid-stream provider
disconnect**, seven consecutive runs across three agents. So no agent defined a
contracted function, and the inter-agent message round-trip was authored but
never delivered by a live turn.

I read end to end before starting, as instructed: the
[arc spec](../plan/whole-system-arc-2026-08-08.md), the
[fix-and-re-drive report](drive-fix-redrive-2026-08-08.md), and the
[observer lane's method notes](live-drive-observer-2026-08-08.md). The
observer's two named reading traps both bit me during this drive and both are
recorded below, because avoiding them is not automatic.

## Scope

- cluster `default`, operator root `/Users/sean/src/seon`, web
  `http://127.0.0.1:7994`;
- JVM pid `31475` (prepl 51016) until the Stage 3 restart, then a fresh JVM
  (prepl 52905);
- model `deepseek-v4-flash`, owner-pre-authorized;
- never reset, never reforked; `bin/seon stop`/`start` exactly once, at Stage 3;
- window 09:33:25Z (cluster boot) to 10:08:14Z.

## Timeline

| Time (UTC) | Event |
|---|---|
| 09:33:25 | cluster boot; bootstrap:root, then four maintenance-fault runs |
| 09:38:34 | opening human message to root; run `ec979da7` opens same second |
| 09:38:53 | root closes having only oriented — no disposition, work undone |
| 09:40:35 | nudge; root authors all 8 correct forms |
| 09:41:37 | all three `ensure-entity!` calls fail: wrong number of args (2) |
| 09:44 | call preparation confirmed inert; issue filed |
| 09:45:03 | three agents created from the operator side (deviation) |
| 09:45:24–25 | three assignments dispatched; **four concurrent live runs** |
| 09:45:24–09:46:06 | all three agents run real turns; all three orient only |
| 09:47:38 | corrected follow-ups; all three runs die instantly on the provider |
| 09:47:38–09:55:40 | seven consecutive provider mid-stream disconnects |
| 10:00:35 | root authors a durable plan; refused — undeclared attributes |
| 10:03:57 | run `945f3226` opened deliberately |
| 10:04:06–10:04:44 | `bin/seon stop` (38 s) with that run in flight |
| 10:04:47–10:04:59 | `bin/seon start` (11 s) |
| 10:05:00 | root re-woken from the unconsumed message; recovery verified |

## Per-acceptance-item verdicts

| # | Acceptance item | Verdict |
|---|---|---|
| 1a | Root claims the human's opening message | **PASS** — run opened in the same second as the 204 |
| 1b | Root delegates to three agents in three namespaces | **FAIL** — root authored the correct calls; `ensure-entity!` refused. Agents created by the driver instead |
| 2 | Three agents run CONCURRENT live turns | **PASS** — four runs open simultaneously at 09:45:25, one holder each |
| 2b | Messages flow between agents and appear in transcripts | **NOT REACHED** — root's three `my.message/send` calls succeeded, but no agent-to-agent send ever executed |
| 3 | Each agent defines a contracted function a later form calls | **FAIL** — zero agent-authored functions; blocked by the provider failure |
| 4a | Namespace pages update live over SSE, no reload | **PASS** — 27 morph events / 7.2 MB streamed on `/feed/root` during turns |
| 4b | Debug pane shows exact prompt bytes of an in-flight run | **PASS** — captured mid-run, containing the system message and the live message verbatim |
| 4c | `/data` answers | **PASS with a caveat** — 200, but 6.4 s for 3,168 bytes |
| 4d | Reconnect-repaints holds | **PASS** — closed mid-turn, reopened, one 573 KB keyframe with current state |
| 5 | Interactive `my.canvas` surface | **CANNOT RUN** — no canvas mechanism exists in the tree |
| 6a | Restart mid-arc; nothing re-executes | **PASS** — receipts unchanged at 102 across the restart |
| 6b | Interrupted receipts honest | **PASS (vacuous)** — 0 interrupted, correctly: the run was mid-provider-call, not mid-eval |
| 6c | Agents' plans survive and continue | **FAIL, driver-caused** — no plan ever committed; my script used undeclared attributes |
| 6d | Agents continue after restart | **PASS** — root re-woke from its unconsumed message at 10:05:00 |
| 7 | Token sentinel holds; no context collapse or unbounded completion | **PASS with one regression** — see the table below |
| 8 | Reproduce the arc's story from facts alone | **PASS** |

## The three blocking defects

### 1. Call preparation is never installed (arc-blocking)

[Issue](../../../seon/issues/call-preparation-is-never-installed-on-the-cluster-sci-context.md),
filed and committed.

Root authored exactly what the spec wanted:

```clojure
(cluster/ensure-entity! "31475-1786181598529"
  {:seon.cluster.agent/id "inventory"
   :seon.ns/name "arc.inventory"
   :seon.cluster/name "default"})
```

All three calls returned `Wrong number of args (2) passed to:
seon.cluster/ensure-entity!`. Reproduced independently in door mode.

The plan is not the problem. Derived live from the same database value:

```clojure
{"seon.cluster/ensure-entity!"
 {:seon.call-preparation/by-supplied-count {3 …, 2 …}
  :seon.call-preparation/empty? false}}
```

The 2-argument call **is** planned and unambiguous. The hook never consults it,
because `hook` reads `(get ctx :seon.call-preparation/state)` and the acquired
cluster ctx does not carry that key. `install` — the one function that adds it —
**has no caller in `src/` at all**; its only caller in the tree is
`test/seon/call_preparation_test.clj:362`, on a scratch ctx. The owning
docstring says so plainly: "S2 calls this in `seon.sci.eval/cluster-ctx` …
until then a probe or test attaches it to a scratch ctx."

So the suite is green on a mechanism that is dead in production. `seon.db/q`
and `seon.db/pull` keep working with an elided database only because they carry
their own shorter arities, which masked it.

I did not apply the one-line fix. Arming the hook without S2's
`db?`/`connection?` dispatch would change the meaning of every existing elided
`seon.db` call at once — larger than the small-fix exception allows.

### 2. A mid-stream provider disconnect discards the whole turn (arc-blocking)

[Issue](../../../seon/issues/a-mid-stream-provider-disconnect-discards-the-whole-turn.md),
filed and committed.

The attempt's own facts are unambiguous:

```clojure
{:seon.ai/http-status          200
 :seon.ai/request-transmitted? true
 :seon.ai/response-started?    true
 :seon.ai/output-observed?     true
 :seon.ai/model                "deepseek-v4-flash"}
```

Output **was** observed, and the run was still refused with zero forms, zero
receipts, closed in the same second it opened. The agent-visible message says
only `The provider's response was not readable JSON: closed` — strictly less
than the database already knows.

Seven consecutive runs, and it is neither an outage nor concurrency: the same
three agents' first turns all succeeded three-at-once, `root` succeeded at
09:50:55 while inventory was failing, and inventory failed at 09:55:40 with
nothing else running. The prompt is not at fault either — 58,071 characters, no
control characters, no surrogates, maximum code point 8212.

Retry policy cannot absorb it: `maximum-retries 2` inside
`maximum-total-delay-ms 3000`, against a condition that persisted eight minutes.

### 3. Provider control tokens leak into replies, and comment-only forms settle no receipt

Evidence appended to
[the existing receipt-gap issue](../../../seon/issues/a-runs-last-form-can-close-without-a-receipt.md).

The observer's 46/41 gap **reproduced**: 105 forms, 102 receipts, three runs
with one form and no receipt. This drive found the cause, and it is two stacked
defects. All three unreceipted forms are comment-only, and each is comment-only
because deepseek's own chat-template markup reached the reply parser verbatim:

```text
; <｜｜DSML｜｜AgentThoughts>We need respond to current instruction about core
  fault. Need inspect. Let's gather data first.</｜｜DSML｜｜AgentThoughts>
; <assistant1>I’m checking the facts before answering — first the relevant
  schema and entity attributes.
; <assistant1>
```

`<assistant1>` and `<｜｜DSML｜｜AgentThoughts>` are control tokens that should
never appear in completion text. The parser then commits each as a comment-only
form, which records a form row and no receipt.

## A behavioural finding that cost more than any single bug

**Every agent spends its first turn orienting, and orientation is then thrown
away.** Root, inventory, health and timeline each opened their first real turn
with `dir`, `doc`, and attribute-discovery queries — reasonable behaviour — and
each ended without `my.run/complete` or `my.run/wait`. A turn that ends with
neither is CLOSED, so the orientation is discarded and the assignment never
starts. Every unit of work therefore cost at least two human messages.

This also contradicts a docstring in the owning namespace. `seon.cluster.loop/disposition`
(`src/seon/cluster/loop.clj:126-127`) states:

> a run whose plan ends without one simply stays open for the next wake

It does not. `close-turn`'s own design comment 1,300 lines later
(`loop.clj:1452-1457`) describes deriving `:close` for "any open planned run
whose forms are all settled" — which is the observed behaviour and the opposite
of what the docstring promises. One of the two is wrong, and the docstring is
the one agents and readers see.

## Token sentinel

Estimated with `seon.ai.tokens/estimate`; provider figures from
`:seon.ai.attempt/usage-edn`.

| At (UTC) | Agent | Prompt chars | Est. tokens | Provider prompt | Completion | of which reasoning | C:P | Cache hit |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 09:33:31 | root | 53,137 | 13,284 | 16,772 | 1,278 | 1,190 | 0.08 | 512 |
| 09:33:46 | root | 56,073 | 14,018 | 17,695 | 410 | 301 | 0.02 | 512 |
| 09:33:54 | root | 63,538 | 15,884 | 19,811 | 725 | 452 | 0.04 | 1,280 |
| 09:34:05 | root | 108,032 | 27,008 | 33,476 | 1,117 | 999 | 0.03 | 1,408 |
| 09:38:34 | root | 115,415 | 28,853 | 35,453 | 892 | 808 | 0.03 | 1,536 |
| 09:40:35 | root | 116,572 | 29,143 | **35,827** | 6,702 | 6,119 | 0.19 | 1,664 |
| 09:45:06 | root | 32,786 | 8,196 | 9,496 | 2,738 | 2,706 | 0.29 | 0 |
| 09:45:24 | inventory | 54,026 | 13,506 | 16,812 | 3,608 | 3,313 | 0.21 | 0 |
| 09:45:25 | health | 53,864 | 13,466 | 16,778 | 1,038 | 1,010 | 0.06 | 0 |
| 09:45:25 | timeline | 54,260 | 13,565 | 16,900 | 778 | 614 | 0.05 | 0 |
| 09:45:46 | root | 33,468 | 8,367 | 9,716 | 2,202 | 2,195 | 0.23 | 3,072 |

**Verdict: the sentinel holds, with one named regression.**

- No context collapse. Every prompt is a real rendered context; the 509-char
  starvation of the morning drive is gone and did not recur once.
- No unbounded completion. Every completion:prompt ratio is at or below 0.29.
  The 46.7 inverse explosion is gone.
- **Regression against the 24k ceiling.** Root's prompt grew monotonically from
  16,772 to 35,827 provider tokens over six turns — 2.1×, and 48% above the
  24,257 recorded as the known ceiling on 08-08 morning. The cause is visible
  and benign in mechanism: the session accumulates, and `my.run/complete` resets
  it. Root's prompt drops to 9,496 immediately after completing. So the ceiling
  is not a per-turn constant but a function of how long an agent goes without
  completing — which is exactly the behaviour the orientation finding makes
  worse.
- A fresh agent's first prompt is 13.5k tokens, comfortably inside the band.
- **Cache hit is bad and got worse.** 0 for all three new agents' first calls,
  and 512–3,072 elsewhere against 08-06's 17,792. Prompt-prefix stability is
  worth an owner.

## Facts-only reconstruction

The spec's last acceptance item: prove the arc's story by query alone. Every
number below is a single Datalog query against the post-restart database,
with no reference to this document.

```clojure
{:agents-and-namespaces [["health"    arc.health]
                         ["inventory" arc.inventory]
                         ["root"      my.agents.root]
                         ["timeline"  arc.timeline]]
 :runs-per-agent        [["health" 4] ["inventory" 5] ["root" 13] ["timeline" 4]]
 :zero-form-runs        8
 :human-messages        19
 :receipts-total        102
 :receipts-errored      16
 :contracted-by-agents  [[arc.timeline/largest  arc.timeline]
                         [arc.health/largest    arc.health]
                         [arc.inventory/largest arc.inventory]]}
```

That reads correctly as the arc's true story, including its failures: four
agents in four distinct namespaces, twenty-six runs unevenly distributed, eight
runs that recorded nothing at all (the provider disconnects), and **no
agent-authored contracted function** — the three `arc.*/largest` rows are the
bootstrap teaching function seeded into each new namespace, not agent work.

The database answered every diagnostic question in this report. Not one
conclusion needed process memory.

## Stage 3 in detail — the restart

Deliberately performed with run `945f3226` open and held.

| Measure | Before (10:03:43) | After (10:05:09) |
|---|---:|---:|
| basis `t` | 536,871,298 | 536,871,317 |
| agents | 4 | 4 |
| runs | 24 | 26 |
| receipts | 102 | **102** |
| indexed functions | 2,743 | 2,743 |
| interrupted receipts | 0 | 0 |
| open runs | 0 | 1 (root, re-woken) |

- `bin/seon stop` 38 s (prepl path, then SIGTERM on an empty JVM);
  `bin/seon start` 11 s.
- The in-flight run was closed at 10:04:55 during recovery, its
  `:seon.cluster.run/process` attribute shed. Custody released cleanly.
- **Receipts unchanged at 102 is the crash-model proof**: nothing re-executed.
- Zero interrupted receipts is correct rather than a miss — the run was waiting
  on a provider call, not inside an eval, so there was no dangling receipt to
  mark.
- The unconsumed message re-drove root at 10:05:00, five seconds after boot.
- All web surfaces alive immediately after: `/` 200 in 18 ms warm,
  `/ns/arc.inventory` 200, debug 200 in 31 ms, `/data` 200, SSE keyframe
  959 KB.

## Stage 2 — why it could not run

`my.canvas` does not exist. Neither does any canvas mechanism:

```bash
rg -rn "canvas" --glob '*.clj*' --glob '*.edn' src/ resources/   # no matches
ls resources/seon/schemas/ | grep -i canvas                      # nothing
```

The cluster's declared toolkit is `my.background`, `my.edit`, `my.fs`,
`my.message`, `my.run`, `my.shell`, `my.web` — queried from
`:seon.cluster/toolkit`. The vocabulary table defines `canvas` as
`:seon.render.canvas/content`; that attribute is not declared anywhere.

Stage 2 is not a failure of the drive; it is a precondition the spec assumed and
the tree does not have. It should be reclassified as unbuilt before the next
arc attempt.

## Deviations from the spec, named

1. **The driver created the three agents.** Root should have. It authored the
   right calls and was refused by defect 1. Recorded as the arc's largest
   deviation; Stage 1's delegation step is unproven.
2. **The driver gave the agents wrong information.** I told all three that
   "eliding a connection currently fails," over-generalising the
   `ensure-entity!` defect to `seon.db`. It is false — `seon.db/transact!` has a
   1-argument arity. Inventory acted on it, reached for a `seon.db/connection`
   var that does not exist, and lost its entire turn to
   `Unable to resolve var: seon.db/connection`. I withdrew it explicitly in the
   follow-up. This is a driver error, not a system defect.
3. **The plan-survives-restart item was unprovable as scripted.** I asked agents
   to commit `:arc.plan/agent`, `:arc.plan/steps`, `:arc.plan/at` — attributes
   nobody has declared. The system refused correctly and loudly:

   ```clojure
   {:seon.error/kind :seon.db/rejected,
    :seon.error/message "Bad entity attribute :arc.plan/agent … not defined in
                         current schema",
    :seon.db/transaction-refused true}
   ```

   The system behaved exactly as it should. But it exposes a real gap worth an
   owner: **an agent has no taught route to declaring a data attribute.** Its
   system message explains that a `defn` with a complete `:malli/schema` becomes
   durable, and says nothing about how to record an arbitrary durable fact. An
   agent asked to "remember something" cannot, without first declaring a schema
   it has not been told how to declare.
4. **Root also missed the refusal.** It called `transact!`, got an error value
   back, read the facts back, got nothing, and reported neither. Errors-as-values
   only helps if the value is looked at — the same class the previous drive
   recorded when an error map was fed to arithmetic.

## Ugly output, verbatim

**The MCP admission window silently destroys a result and misreports it.**
Asking for eight forms with their ordinals returned:

```clojure
[[0 "seon.sci.admit/elided" "seon.sci.admit/elided"]
 [0 "seon.sci.admit/elided" "seon.sci.admit/elided"]
 [0 "seon.sci.admit/elided" "seon.sci.admit/elided"]
 …
 "seon.sci.admit/elided"]
```

Every row reports ordinal **0**, which is false for seven of eight, and the
vector ends in a bare marker string. A reader who did not already know the true
ordinals would draw a wrong conclusion, not merely an incomplete one. This is
worse than the observer's instance, which at least kept true field values.

**Drilling the elided value returns nothing and says nothing is missing.**

```clojure
{:seon.render.value/window     "seon.sci.admit/elided"
 :seon.render.value/shown      0
 :seon.render.value/total      nil
 :seon.render.value/more?      false
 :seon.render.value/beyond-end? false}
```

`shown 0`, `total nil`, `more? false` — the retrieval path for an elided value
reports that there is nothing more to see. The vocabulary table requires an
elision value to carry omitted count, known total, path, next offset, profile,
and requery identity. This carries none of them and actively asserts
completeness.

**Nested calls in a Datalog clause still fail as a raw cast exception.**
Confirmed still present, exactly as the previous drive reported:

```clojure
[(subs ?c 0 (min 160 (count ?c))) ?out]
;; class clojure.lang.PersistentList cannot be cast to class java.lang.Number
```

Neither the clause nor the restriction is named.

**A recurring five-second error storm with no owner named.** Ten identical
lines, 09:40:49 → 09:41:34, then silence:

```text
:error datahike.db.utils [138 10] Expected number or lookup ref for entity id,
  got "31475-1786181598529"
  data: {:error :entity-id/syntax, :entity-id "31475-1786181598529"}
```

Something on a 5-second cadence passed a process id string where an entity id
belongs. The message names neither the caller nor the task. It self-resolved,
which is why it is recorded here rather than filed — but a periodic task
failing every five seconds should say which task it is.

**`bin/seon status` prints eight unreadable-record lines on every invocation.**

```text
record unreadable /Users/sean/src/seon/data/operator/claims/roots/1ff66f77-….edn:
  The external claim is invalid.
```

Eight of them, every time, on a healthy root with one live cluster. The refusal
is honest but it is now permanent furniture, which is how a real signal gets
missed.

**The declaration-population fallback wall is undiminished.** Nearly every
`eval_clj` call in this drive returned several
`seon.schema: DECLARATION POPULATION FALLBACK ×10 — <caller>` lines before its
value. Callers seen this drive include `seon.sci.admit`, `seon.cluster`,
`seon.schema.datahike`, `seon.print`, `seon.instrument`, and
`seon.call-preparation`. The owning issue already exists; this is one more
independent confirmation that the volume is unchanged.

**A multi-arm contract error pads with positional nils.**

```text
seon.call-preparation/plan-for violated its contract (invalid-input):
  [nil nil [{:value seon.db/q, :message "should be a string"}]]
```

The two leading `nil`s are the arguments that were fine. A reader has to count
positions to find which argument failed.

## What is genuinely in good shape

- **Concurrency.** Four agents, four runs, four contexts, four provider calls,
  simultaneously in one JVM. Exactly one holder per run at every observation,
  no orphan, no double claim, no cross-talk. This is the arc's real new proof.
- **The restart.** Stop 38 s, start 11 s, and on the far side: every agent
  present, every fact intact, custody shed, receipts unchanged, and the pending
  message re-driving the agent five seconds after boot. Nothing re-executed.
- **The web transport.** SSE streamed 27 morph events and 7.2 MB during live
  turns; a mid-turn reconnect got one keyframe with current state; the debug
  pane served the exact in-flight prompt bytes in 31 ms; `/agent/inventory` and
  `/ns/arc.inventory` were byte-identical at 279,813 B. New namespace pages
  appeared and served the moment their agents existed.
- **Refusals are loud, typed, and name what was missing.** The undeclared
  attribute, the symbol-not-string namespace, the wrong arity — each named the
  exact key, value, and expectation. Every one of them taught me something in a
  single read.
- **The attempt diagnostics did the diagnosis.** `:seon.ai/http-status`,
  `request-transmitted?`, `response-started?` and `output-observed?` turned
  "the provider is flaky" into "200, output seen, stream truncated" in one pull.
  That is the flywheel working exactly as the ethos describes.
- **The model's authoring is good.** Given a correct context, root produced all
  eight required forms in the right order on its first real attempt — three
  creations, three sends, a completion — with correct namespaced keys. Where the
  arc failed, it was not for want of a capable model.

## Method corrections, recorded

Both of the observer's named traps caught me, so they are not avoidable by
intention alone:

1. **A query over an uninstalled attribute returns empty, not an error.** My
   first census reported `agents 0` and `fns 0` because I guessed
   `:seon.cluster.agent/name` and `:seon.fn/name`. The real attributes are
   `/id` and `/sym`. An empty result and a wrong attribute name are
   indistinguishable — always confirm the ident against `:db/ident` first.
2. **Receipts join to the run, not to the form.** There is no
   `:seon.cluster.eval/form`. My first receipt query returned empty and I very
   nearly filed "two forms, zero receipts" as a defect; the join is
   `:seon.cluster.eval/run` plus `/ordinal`.
3. New, and worth adding to the list: `:seon.fn/sym` is a **string** and
   `:seon.fn/ns` is a **ref**. A query treating either as a symbol returns
   empty, silently.

## Recommended order for the next attempt

1. Install call preparation (defect 1) — without it root cannot delegate, and
   Stage 1 cannot be proven at all.
2. Fix the mid-stream disconnect (defect 2) — without it agents cannot complete
   turns reliably enough to define anything.
3. Give an agent a taught route to declaring a data attribute, or drop the
   "plans survive restart" item to something schema-backed that already exists.
4. Reclassify Stage 2 as unbuilt until a canvas mechanism exists.
5. Consider whether a turn that ends without a disposition should really close,
   and fix `loop.clj:126` either way — the docstring and the behaviour disagree
   today.
