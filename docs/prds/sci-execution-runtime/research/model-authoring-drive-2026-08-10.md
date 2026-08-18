---
type: research
status: active
tags: [research, agent, runtime]
---

# Model-authoring drive — 2026-08-10

Driver lane. I read END TO END before driving, and say so explicitly:
[model-authoring-redrive-observer-2026-08-08.md](model-authoring-redrive-observer-2026-08-08.md)
(why the 2026-08-08 attempt failed), the arc plan
[whole-system-arc-2026-08-08.md](../plan/whole-system-arc-2026-08-08.md), and
the `repl` skill (`.claude/skills/repl/SKILL.md`) — all three complete, not
grepped.

I changed no production code. Every stimulus was an HTTP message to the real
web surface; every observation is a Datalog query against the live connection
or a direct call to the shipped render functions.

## Verdict

**Stage 1 — ACHIEVED.** A real DeepSeek turn authored a contracted function end
to end, first try, in one turn of 23 seconds:

```clojure
my.agents.root/token-pressure
;; :seon.fn/spec, read back out of the program graph:
[:=> [:cat [:sequential [:map [:prompt-tokens :int] [:completion-tokens :int]]]]
 [:map [:turns :int] [:prompt-total :int] [:completion-total :int] [:ratio :double]]]
;; :seon.schema.admission/source "agent"
```

It defined it, called it — `(token-pressure [{:prompt-tokens 100
:completion-tokens 25} {:prompt-tokens 300 :completion-tokens 75}])` →
`{:turns 2, :prompt-total 400, :completion-total 100, :ratio 0.25}` — and
closed the run with `my.run/complete`. A later turn queried both of its own
contracts back out of the database on its own.

**Stage 2 — ACHIEVED.** The model authored a render producer and the selector
picked it up with zero wiring:

```clojure
my.agents.root/token-pressure-line
[:=> [:cat [:map [:seon.render/value
                  [:map [:turns :int] [:prompt-total :int]
                        [:completion-total :int] [:ratio :double]]]]]
 :seon.render/ai]
```

Selection proof, on a value the model never saw, through the shipped seams:

```clojure
(#'seon.render/candidates {… :seon.render/namespace 'my.agents.root
                             :seon.render/value {:turns 3 :prompt-total 36000
                                                 :completion-total 8300 :ratio 0.23}
                             :seon.render/output-schema :seon.render/ai})
⟹ ["my.agents.root/token-pressure-line"]   (of three public fns in the ns)

(seon.render/render-call {… :seon.render/output :seon.render/ai
                            :seon.render/namespace 'my.agents.root
                            :seon.render/value {:turns 9 :prompt-total 120000
                                                :completion-total 15000 :ratio 0.125}})
⟹ "Across 9 turns, prompt tokens totaled 120000, completion tokens totaled
;;     15000, and the completion/prompt ratio was 0.125."
```

`render-call` is the exact function `seon.render.walk` calls per node
(`src/seon/render/walk.clj:421-422`), so this is the walk's own selection path,
not a parallel one.

Two negative controls held: the same value under `:seon.render/namespace
'seon.db` yields `[]` candidates (a producer is not selected outside its owning
namespace), and a value that does not fit the declared input does not select it.

**Caveat, stated plainly.** The task text asks for the walk/page to select the
producer over a *rendered value*. The walk renders database ENTITIES and derives
the namespace from `walk/owning-namespace` of a pulled entity
(`src/seon/render/walk.clj:412-422`). The model's shape is a plain value, not an
entity, so I proved selection through `candidates` and `render-call` with the
namespace supplied exactly as the walk supplies it, rather than by pointing at a
page pixel. The namespace page itself rendered clean during the drive
(`GET /ns/my.agents.root` → 200, 339,914 bytes, 49 mentions of `token-pressure`,
zero contract-violation text). Rendering an authored shape as an entity in the
walk is the untested remainder.

## What I told the model, verbatim scope

I gave stage 2 the platform's selection rule (public function in the owning
namespace, input map carrying the value under `:seon.render/value`, declared
return schema exactly `:seon.render/ai`) because that rule is the contract, not
the answer. I wrote none of its code, chose none of its argument destructuring,
and supplied no schema text. Stage 1 got only the domain and "complete
`:malli/schema`, no `:any`".

## Every turn, by fact

Cluster `default`, pid 91415, JVM start `2026-08-10T21:25:24Z`, web
`http://127.0.0.1:7994`, prepl `127.0.0.1:56626`. All times UTC.

| Run (eid / id) | Window | prompt | completion | reasoning | finish | c:p | forms/receipts |
|---|---|---:|---:|---:|---|---:|---|
| 25939 `30019a4b-…` | 21:29:12→21:29:35 | 12,161 | 2,551 | 2,195 | stop | 0.21 | 3/3 |
| 25988 `9ff4c153-…` | 21:31:20→21:32:11 | 13,151 | 5,490 | 5,174 | stop | 0.42 | 3/3 |
| 26029 `fe408373-…` | 21:33:57→21:34:15 | 13,755 | 1,716 | 1,573 | stop | 0.12 | 2/2 |

Pre-drive boot runs, for the baseline: 25804 (scripted bootstrap, no provider
attempt), 25878 (10,766 prompt / 1,469 completion), 25905, 25910, 25924 (11,056
/ 299).

**Every form settled a receipt** in all three drive runs — no repeat of the
2026-08-08 comment-only-form gap. **No truncation fact exists on any attempt.**
**No errored receipt and no failed run belongs to a drive turn** — the only two
errored receipts and the one failed run are boot-time (below).

### Token sentinel

The context is real and lean, and the failure the 2026-08-08 observer found is
gone: prompts are 10.7k–13.8k tokens of actual agent context (34,798 characters
in the first capture, one `walk` contribution), not a 931-character render
error. Growth across the session is **+3.0k tokens over five turns**, ~600–1,100
per turn, which is transcript accretion, not a runaway; prompt-cache hits rose
1,408 → 3,584 as it accreted. Completion-to-prompt ratios are 0.12–0.42 —
nowhere near the ~43 pathology signature of the broken-context loop. No producer
needs naming: nothing exploded.

The one number worth watching: the stage-2 turn spent 5,174 reasoning tokens
(3× the others) on a task whose answer was 15 lines. That is the cost of a
contract stated abstractly, not a defect.

## Defects and ugly output found

1. **[The render value floor refuses any map with unqualified keys](../../../seon/issues/render-value-floor-refuses-any-map-with-unqualified-keys.md)** — NEW, filed.
   `seon.render/render-ai` renders `{:my/a 1 :my/b 2}` and `[1 2 3]`, and
   refuses `{:a 1 :b 2}` with `seon.render.value/prepare violated its contract
   (invalid-input) … "should be a qualified keyword"`. Cause:
   `render-argument` merges a map value's own keys into the render unit
   (`src/seon/render.clj:106-107`) while `:seon.render/unit` is `[:map-of
   :qualified-keyword …]` (`resources/seon/schemas/seon.render.edn:80-87`).
   This is exactly the shape the model itself authored — `token-pressure`
   returns `{:turns … :ratio …}` — so the floor cannot render the ordinary
   result of ordinary agent code.

2. **A validator dump reaching a caller as the whole answer.** The refusal above
   surfaces as a 200-character Malli problem vector rather than a flat typed
   refusal naming what was wrong. Recorded inside the issue above rather than
   as a second note; it is the same defect's face.

3. **Boot-time unbalanced reply closes a run with a reader error, silently.**
   Run 25905 (`deb721df-…`, 21:26:10, zero-second window) carries
   `:seon.cluster.run/error "EOF while reading, expected ) to match ( at
   [12,1]"` and zero forms. This is the reader behaving as documented (no repair
   layer) and the loop recovered on the next run, so it is an observation, not a
   blocker — but a run that produced nothing and cost a full attempt is worth a
   sentinel. Not filed; recorded here for the observer to corroborate.

4. **Teaching-failure classification works, and its message is good.** Run
   25804's first `largest` definition was refused with `my.agents.root/largest
   uses :any in an agent-authored contract. Replace the undefined slot with a
   named predicate schema, for example (schema/register! ::value [:fn {…}
   'my.domain/value?])` and the model immediately redefined it correctly. This
   is calibration, not alarm: the loud refusal taught in one round trip.

5. **`DECLARATION POPULATION FALLBACK` noise floods every eval envelope.**
   Ordinary probes return with up to six stderr lines each, and the cluster log
   shows `×1000 — seon.db (db.clj:430)`. The log text itself names this as the
   defect ("the SAME caller repeating within one operation is the defect").
   Already owned by
   [value-admission-resolves-the-declaration-population-per-node.md](../../../seon/issues/value-admission-resolves-the-declaration-population-per-node.md)
   and its siblings; noted here only as continued live evidence, since it is the
   single loudest source of unreadable output on the cluster right now.

6. **Elision replaces short strings with the marker.** `(mapv runinfo [a b c
   d])` returned `{"run" "seon.sci.admit/elided", "agent"
   "seon.sci.admit/elided", …}` — the 36-character run id and the 4-character
   agent id were elided while the timestamps survived, so the result was less
   useful than a plain truncation. Depth/node budget, not size. Minor; not
   filed, but it is the kind of ugly output the standing order wants recorded.

## What is genuinely in good shape

- **The context render is fixed and stayed fixed** across five turns and three
  drives. This was the 2026-08-08 blocker; there is no trace of it.
- **Contract admission is real, not cosmetic.** The authored specs decompose
  into `:seon.fn.arity`, `:seon.fn.ast`, and per-argument facts — the contract
  is queryable structure, not a stored string.
- **Producer selection is derived, per the ruling.** No registration, no list,
  no naming convention: three public functions in one namespace, exactly one
  fits the contract, and it wins. Adding a renderer really is adding a function.
- **Custody and settlement are clean.** No run held by two processes, no
  dangling receipt, every form settled.

## Method notes / near-misses

- `:seon.cluster.eval/source` does not exist — form source is
  `:seon.cluster.run.form/source` and the receipt is a separate
  `:seon.cluster.eval` entity joined by ordinal. My first census counted zero
  forms and looked like a dead cluster.
- `:seon.cluster.eval/error` is a STRING, not a ref; binding it as an entity in
  a `:where` clause throws a Datahike pattern error rather than returning empty.
- `my.agents.root/largest` also carries `:seon.schema.admission/source "agent"`
  but was authored by the SCRIPTED bootstrap (run 25804, `:usage []` — no
  provider attempt). The admission source does not distinguish bootstrap from
  model; provenance must come from the run's attempts. Anyone auditing this
  milestone by grepping for agent-sourced functions will over-count.
