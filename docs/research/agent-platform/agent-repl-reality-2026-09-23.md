---
type: research
status: current
created: 2026-09-23
scope: What agents actually see and do at the Seon REPL, measured from the live `default` database (read-only probes), with the smallest compositions that would change it.
---

# Agent REPL reality, measured from the database

This measures what exists: every stored evaluation lives in Datahike as a
`:seon.cluster.eval/*` row. The row carries its exact shown text
(`:seon.eval/shown`), and every prompt an agent received is stored verbatim as
`:seon.context.capture/prompt`. Every number here comes from queries over
those rows. Nothing was re-derived from source. The principal Clojure
approach: query and count, then read the producers.

Probe basis: cluster `default` (pid 48902, started 2026-09-23T18:07:01Z), HEAD
`c8dc92833`. The connection is on branch `cluster-default`. I read six branches
through `datahike.api/branch-as-db`, releasing each one. I ran only queries,
pulls and pure render calls in JVM mode, in session `repl-reality`, with
`read_only true`. Runtime status was `observed` with no missing layers, so no
tool was degraded. One probe failed on my own error: `seon.db/branches` does
not exist, and I used `datahike.api/branches` instead.

**Sample caveat (VERIFIED).** The corpus is small and covers one task. There
are 214 distinct evaluations across all branches, 54 of them agent-authored,
from 2 agents (`root` and `juniper`). They span one day
(2026-09-23 13:47–16:54Z). Every agent session was the same task: "define
`largest`…". The rates below describe that task, not agent behaviour in
general.

## 1. Answer first

1. **Replayed evaluations outnumber what agents write, and one generated query dominates the prompt (VERIFIED).**
   - On `cluster-default`, 142 of 176 evaluations are `:system`-authored replays of earlier reads (`seon.turn/system-turn`, `src/seon/turn.clj:2059`). Only 34 are agent-authored.
   - The agent's failing query `(seon.db/q '[:find ?a … [(namespace ?a) "example"]])` was replayed **23 times**, and its `vec` variant 21 times.
   - The first opening prompt is 115,192 chars. Its error-inspection read is **109,640 chars (95%)** and returns `[]`.
   - The latest prompt is 680,643 chars. That same read appears **5 times, ≈548k chars (≈80%)**.
   - The cause is `faults-form` (`src/seon/error.clj:1786-1818`). It expands `observation-selector` (`src/seon/error.clj:1534`) into an explicit list of every error attribute with `:limit nil`, and prints that list as the query's source.

2. **Agents almost never compose (VERIFIED, counted).**
   - Of 51 readable agent-authored sources, **1** refers to an earlier result handle: `(get-in result/eb0974d7a1d48 [])`.
   - **0** use `map`/`mapv`/`pmap`/`reduce`/`filter`/`for`/`keep`/`mapcat`/`into`/`transduce`/`doseq`/`sort-by`/`group-by`, over anything.
   - The latest prompt contains **389** `result/e…` handle mentions, and the `(help)` text tells agents to use them.
   - Telling agents to use handles and showing them the handles has not produced chaining.

3. **Plain error values reach the agent as a render ambiguity, which hides the real error (VERIFIED).**
   - 10 stored results show a `seon.render/ambiguity` error (6 agent-authored, 4 system): "More than one function in  accepts this value", with candidates `[seon.error/mcp-prose seon.error/render-ai]`.
   - The hidden error was a Datalog parse error, `:parser/binding`. The agent only saw it after wrapping the query in `vec`.
   - The agent concluded "the issue is the *renderer*, not the query". That is half right.
   - `seon.error/mcp-prose` is declared on 5 `:seon.dev.mcp/*-error` schemas. An ordinary error value was accepted by both producers, which is selection by structural resemblance rather than by declared schema name.

4. **The one contract refusal an agent received was prose with no handle (VERIFIED).**
   - This was the auto-check install refusal on `largest` (evaluation `f34fde256734`). It was rendered by the declared renderer `seon.test.accretion/render-ai`.
   - The Malli failure appears only as text: `why: {:example/amount ["missing required key"]}`.
   - Because a declared renderer was used, `seon.repl/response` returns the bare text (`src/seon/repl.clj:220`). The `:result result/e…` key is dropped, so the refusal cannot be reached with `(-> rN …)` from the text the agent saw.
   - No agent-visible runtime instrumentation refusal (`:seon.instrument/invocation`) exists in any stored evaluation. The 4 such error rows are all core faults.

5. **Stored instrumentation explanations keep paths but replace the schema with a digest (VERIFIED from source and a stored row).**
   - `boundary-refusal` (`src/seon/instrument.clj:586-636`) stores, per Malli problem:
     - `schema-location` and `value-location`, which are paths;
     - `expected-shape` as a **SHA-256 fingerprint**, not the schema form;
     - `actual` as a projected print-node string;
     - the constant text `humanization-unavailable`.
   - The stored row has `:seon.error/expected-shape "7789ee8f…"` and `:seon.error/data-size 8388639`: 8 MB of error data for one refusal.
   - An agent cannot read that digest as a schema.

6. **The opening is REPL-shaped data from render functions, plus one prose block (VERIFIED).**
   - The opening is 8 `my.agents.root=> ` evaluations. Their forms come from agent-schema render functions reached by `walk/neighborhood` in `declared-sources` (`src/seon/turn.clj:1852`), for example `render-identity-ai` (`src/seon/cluster/agent.clj:257`).
   - The values are printed by the value renderer.
   - The hand-written prose is:
     - `(help)`, 3,208 chars (`src/seon/bootstrap.clj:53`);
     - one fixed `;;` sentence inside each form producer.
   - By character count, prose is about 3%, generated query source about 95%, and result data about 2%.

7. **Coverage of AI render pairs (VERIFIED).**
   - The projection has 3,373 schema forms. Of these, 292 are entity (`:seon.db/attributes`) schemas, and **153 declare both `:seon.render/ai` and `:seon.render/html`**.
   - Error schemas dominate that 153: `seon.error/render-ai` alone is on 274 schemas across the projection.
   - Of the **84 non-error entity schemas, 23 declare an AI renderer and 61 do not.**
   - For an entity without a renderer, the AI source is the pull form `(seon.db/pull (quote [*]) [:seon.context.contribution/id "418ca1784a2d"])`, and the value printer renders the flat attribute map (quoted in §2.5).

8. **Prose leaks into evaluation (VERIFIED).**
   - 3 of 54 agent sources are unreadable: the agent's prose was split into forms. One piece of prose, "`\n\nI", evaluated to the symbol `my.agents.root/I`.
   - `(dir seon.db)` rows put two `declaration-absent` error maps in the `:in`/`:out` columns of every dynamic Var (`*conn*`, `*read-database*`, …), so the directory is mostly noise.

## 2. Evidence

### 2.1 Stored evaluations

Evaluation attributes are declared in these files (VERIFIED, read):

- `resources/seon/schemas/seon.eval.edn` (44 lines). Its `:seon.eval/entity` pairs `seon.repl/render-ai` and `seon.repl/render-html`.
- `seon.cluster.eval.edn` (208 lines), which has the `:seon.cluster.eval/receipt` map.
- `seon.turn.edn` (242 lines).

The run ref points to a `:seon.turn/*` entity. That entity's attributes are
`agent`, `closed-tx`, `id`, `opened-tx`, `reply`, `reply-size` and
`starting-ns`.

Probe, 55 ms:

```clojure
(time (let [conn (seon.cluster.boot/connection "default") db (datahike.api/db conn)]
  {:evals (d/q '[:find (count ?e) . :where [?e :seon.cluster.eval/id]] db) ...
   :authors (d/q '[:find ?a (count ?e) :where [?e :seon.cluster.eval/author ?a]] db)}))
;; => {:evals 176 :with-shown 175 :with-source 176 :with-error 34 :turns 51
;;     :authors [[:system 142] [:agent 34]]}
```

Attribute frequency over the 176 rows, 43 ms:

- `id`, `run`, `ordinal`, `source`, `at`, `ns` and `author`: 176 each.
- `shown`, `read-evidence`, `read-basis-transaction`, `duration-ms` and `ending-ns`: 175 each.
- `comment`: 144.
- `error` and `triage-edn`: 34 each.
- `renderer` and `renderer-fn`: 5 each.
- `output`: 4.
- `interrupted-at`: 1.
- `seon.test.accretion/*`: 2.

Per branch, 76 ms:

| branch | evaluations | agent-authored | turns | agents |
|---|---|---|---|---|
| cluster-default | 176 | 34 | 51 | root |
| cluster-running-fixture | 167 | 49 | 67 | juniper, root |
| agent-aa7e7ffc8142 / agent-code-api-probe-2 | 147 | 32 | 48 | root (forks of default) |
| db, current-src | 0 | – | – | – |

Deduplicated by id, there are 214 distinct evaluations: 54 agent-authored and
160 system-authored.

**The handle (VERIFIED).**

- `seon.sci.admit/result-handle` (`src/seon/sci/admit.clj:608-617`) returns `(id/symbol-in "result" \e evaluation-id)`, defined at `src/seon/id.clj:47`.
- The evaluation id is 12 hex chars from `seon.id/evaluation`, so a handle looks like `result/e7c31b8da5ad2`.
- `entity-emission` attaches it only when the row has both an id and a shown string (`src/seon/repl.clj:432-439`).
- `response-entries` prints it as the `:result` key (`src/seon/repl.clj:199`).

Five real shown results, rendered as the agent read them through
`(seon.repl/text (seon.repl/entity-emission (assoc row :seon.db/db db)))`
(18 ms for six rows, verbatim with comments trimmed):

```
my.agents.root=> (largest [{:example/amount 1} {:example/amount 9} {:example/amount 3}])
#:seon.repl{:value #:example{:amount 9}, :result result/e7c31b8da5ad2, :ms 6}

my.agents.root=> (seon.db/pull '[:seon.fn/sym :seon.fn/spec] [:seon.fn/sym 'my.agents.root/largest])
#:seon.repl{:value #:seon.fn{:spec "[:=> [:cat [:vector [:map [:example/amount :int]]]] [:map [:example/amount {:optional true} :int]]]", :sym my.agents.root/largest}, :result result/e1ad8f337cfd8, :ms 7}

my.agents.root=> (seon.agent/settings)
#:seon.repl{:value {}, :result result/ec8d75e165e99, :ms 56}

my.agents.root=> (my.agent/done)
#:my.turn{:disposition :wait, :note "Session complete."}

my.agents.root=> (seon.db/q '[:find ?a :where [?e ?a] [(namespace ?a) "example"]])
#:seon.repl{:value {:seon.error/at #inst "2026-09-23T13:48:33.141-00:00", :seon.error/data #:seon.render{:candidates [seon.error/mcp-prose seon.error/render-ai], :namespace nil, :output :seon.render/ai}, :seon.error/expected "one applicable renderer", ..., :seon.error/message "More than one function in  accepts this value and returns :seon.render/ai.", ...}, :result result/ed7809de8935b, :ms 14}
```

Two points about these results:

- **`:schema` survives only as a string.** In the pull result, `:seon.fn/spec` is a string, not a Malli form, so the agent cannot `get-in` into its own contract. The agent then quoted the string back to itself with a backtick form (`ebcb08fefc22`).
- **`(my.agent/done)` shows no handle.** It has the declared `my.turn` value renderer, so like all declared-renderer results it carries no `:result` key.

### 2.2 Composition rate

Script, 626 ms over all six branches: a query, then a Clojure function over the
stored sources, parsed with `edamame.core/parse-string-all` using
`{:all true :read-cond :allow}`.

```clojure
(let [rows (into {} (for [b (d/branches conn) :let [db (d/branch-as-db conn b)]
                          row (try (d/q '[:find ?id ?au ?src ?ag :where [?e :seon.cluster.eval/id ?id]
                                          [?e :seon.cluster.eval/author ?au] [?e :seon.cluster.eval/source ?src]
                                          [?e :seon.cluster.eval/run ?r] [?r :seon.turn/agent ?a] [?a :seon.agent/id ?ag]] db)
                                   (finally (d/release-materialized-db db)))]
                      [(first row) (rest row)]))
      syms (fn [forms] (set (filter symbol? (tree-seq coll? seq forms))))
      hof '#{map mapv pmap reduce filter filterv for keep remove mapcat run! doseq into transduce sort-by group-by some every?}]
  ;; handle? = some symbol with namespace "result"; hof? = some symbol whose name is in hof
  ...)
```

| author | total | unreadable | uses a `result/…` handle | uses a HOF |
|---|---|---|---|---|
| agent | 54 (root 34, juniper 20) | 3 | **1** | **0** |
| system | 160 (root 142, juniper 18) | 0 | 4 (replays of that same form) | 0 |

The one handle use worked mechanically. `(get-in result/eb0974d7a1d48 [])`
returned the error chain of evaluation `b0974d7a1d48`, which was itself a
system replay (`29011a306bf3`, 5 ms).

The top system-replayed sources were:

| count | replayed source |
|---|---|
| 23 | the `namespace` query |
| 21 | its `vec` variant |
| 19 | `[:find ?a :where [?e ?a]]` |
| 16 | the `?spec` query |
| 10 | the pull-spec query |
| 9 | the cluster read |
| 8 | the message reverse pull |
| 5 | the errors query |

The agent noticed the replays: "I see my queries keep re-executing because the
REPL replays them" (`9ba10a3d1911`, a prose-only reply, refused as
`seon.cluster.reply/sources … got nil`).

### 2.3 Contract refusals as the agent sees them

There were 34 rows with a stored `:seon.cluster.eval/error`. Pattern counts over
all shown text (63 ms), as agent/system pairs:

- `seon.render/ambiguity`: 6/4.
- `seon.instrument`: 2/0. Both are `dir` output, not refusals.
- `install-refusal`: 1/0.
- Humanized Malli text: 1/0.

**No stored evaluation shows a runtime `:seon.instrument/contract-error`.**
This is the corpus limit.

Refusal 1 is the auto-check install refusal (`f34fde256734`). It has
`:seon.eval/renderer seon.test.accretion/render-ai` and 8
`seon.test.accretion/*` attributes. This is its complete response:

```
seon.test.accretion/install-refusal
Fix the contract or the function and re-evaluate the defn.

Gate: 0 tests · 0 passed · 0 failed · auto-check 1/25 · install refused
...
arguments: [[]]
expected: [:map [:example/amount :int]]
actual: {}
why: {:example/amount ["missing required key"]}
```

- The explanation is humanized Malli, printed as text.
- No `:result` handle is shown, because `seon.repl/response` returns only `value-text` whenever `:seon.eval/renderer` is present (`src/seon/repl.clj:213-229`).
- Whether SCI bound a handle for this value is UNVERIFIED. I did not evaluate in SCI.
- The agent repaired the contract correctly on its third try (`73c7810533c0`), after one reply that was unreadable prose (`fee50758529f`).

Refusal 2 is the render ambiguity, quoted in §2.1. It is not the query error
itself. The real value was only visible after
`(vec (seon.db/q …))`:

```
[[:seon.error/chain [#:seon.error{:data {:error :parser/binding, :form "example"}, :frames [[seon.db$aligned_query_arguments ...] ... 32 frames ... {:seon.print/omitted 11, :seon.print/requery-form (seon.print/value-at result/e29011a306bf3 [0 1 0 :seon.error/frames]) ...}], :message "Cannot parse binding, expected (bind-scalar | bind-tuple | bind-coll | bind-rel)" ...}]] [:seon.error/operation seon.db/q] ...]
```

This error shows two problems:

- The map became a vector of entries.
- 32 JVM frames were printed, including 3 levels of anonymous `fn__110794` frames.

The elision does name its requery form with the handle, which is good.

The ambiguity error comes from `seon.render/ambiguity` (`src/seon/render.clj:310-327`).
Its candidate evidence names `:namespace nil`, which means a stage without an
owning namespace chose the two producers. I did not verify which selection stage
(`namespace-candidates` at `src/seon/render.clj:276` or schema-declared)
produced them. The 5 `mcp-prose` schemas are:

- `:seon.dev.mcp/cluster-degraded-error`
- `:seon.dev.mcp/jvm-exception-error`
- `:seon.dev.mcp/projection-failed-error`
- `:seon.dev.mcp/remainder-not-retrievable-error`
- `:seon.dev.mcp/value-not-found-error`

Stored instrumentation errors (10 ms): there are 16 `:seon.error/signature`
rows. By layer they are normalization 10, `seon.instrument/invocation` 4 and
`sci.eval/program` 2. One stored `contract-error` row has:

- `:seon.instrument/explanations` as a component with `count 1`;
- items whose `actual` is `"#:seon.print{:face :seon.print/nil, :value nil}"`;
- `:seon.error/expected-shape "7789ee8f75c6…"`;
- `:seon.error/data-size 8388639` and `:seon.error/capped? true`.

The producer is `src/seon/instrument.clj:609-632`. It calls `m/explain`, maps
`:path` and `:in` to locations, and fingerprints `:schema`. Malli ships
`malli.error/humanize` (`reference-code/malli/src/malli/error.cljc`), which the
store does not use and which was not verified to be armed here.

### 2.4 The opening context

- **Where it comes from.** Each prompt is captured before the provider call by `seon.context/capture-tx` (`src/seon/context.clj:359-400`) as `:seon.context.capture/prompt`, with ordered contribution rows. I read the stored prompt rather than re-rendering it. The live path `system-turn` with `write? false` calls `preview-sources`, which evaluates forms in the shared SCI context (`src/seon/turn.clj:2098-2106`), so I skipped it under the brief.
- **Captures.** There are 27 captures (3.6 ms), from 115,192 to 680,643 chars.
- **First capture.** Basis 536870945, run `8ca443e36fcb`. It has 9 contributions:
  - 8 are `walk` blocks and 1 is a `frame` block;
  - their token counts are 1001, 143, 101, 191, 63, 102, **34262**, 127 and 7.

Sections of the first capture, split on the `=> ` prompts (0.4 ms):

| # | chars | section (producer) |
|---|---|---|
| 1 | 3,208 | `(help)`: prose from `seon.bootstrap/render-help-ai` (`src/seon/bootstrap.clj:53`) |
| 2 | 457 | identity pull (`seon.cluster.agent/render-identity-ai`, `src/seon/cluster/agent.clj:257`) |
| 3 | 321 | `(seon.plan/plan {})` |
| 4 | 612 | message reverse pull |
| 5 | 202 | `(seon.agent/settings)` |
| 6 | 326 | notes reverse pull |
| 7 | **109,640** | errors query (`seon.error/render-faults-ai`, `src/seon/error.clj:1820`) → `[]` |
| 8 | 426 | cluster and commit read |
| tail | – | `turns left: 100 of 100` (`src/seon/repl.clj:76`) |

The first 60 lines are quoted verbatim, lightly trimmed:

```
my.agents.root=> ;; I should understand how this REPL works before I act.
(help)
The prompt shows your namespace my.agents.root and is drawn for you. Send only ;; thinking comments and forms.
Results are data: chain them with ->>, sort-by, filter, map, and get-in. Functions are callable by their fully qualified symbols.
Forms are evaluated in order, and their results arrive in your NEXT turn. Act on a result only after you have seen it; ...
The REPL supplies responses: :value (or :error) is result data, :out is printed text, and :result names the live value. ...
result/e... is a real symbol bound to the live value: evaluate it, pass it as an argument, or dig in with get-in and keys.
When unsure, use (dir ns) first, for example (dir my.note). ... (dir my.agents.root) lists your own public functions ...
Your plan is your instructions. ...
Read incoming messages with a reverse-ref pull on your agent. (my.message/send {...}) sends; ...
Use pull for a known entity's shape, nested refs, and reverse refs ...; q for filters, joins, and aggregates; ...
Define a function with its invoke contract: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1)). ...
A mistake returns :error data. Read the expected schema, offending value, and attribute candidates before retrying. ...
Each reply is one turn. (seon.turn/turns-left) reads your remaining turns; ... (my.agent/done) must be the last form ...
Tools: my.message, my.turn, seon.bootstrap, seon.db. Inspect one with dir.

my.agents.root=> ;; I should know my identity, namespace, and its steward.
;; (seon.cluster.status/agents {}) shows agent work and turn accounting on demand.
(seon.db/pull
  '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]
  [:seon.agent/id "root"])
#:seon.repl{:value #:seon.agent{:id "root", :namespace #:seon.ns{:name my.agents.root, :steward #:seon.agent{:id "root"}}}, :result result/e172d5f690409, :ms 6}

my.agents.root=> ;; My plan is my instructions. No step is selected. my.plan/current! selects; completing clears the selection.
(seon.plan/plan {})
{:seon.agent/id "root"
 :seon.plan/step-lines {}
 :seon.plan/update-example "(my.plan/add! {:my.plan.item/id \"root/first-step\" :my.plan.item/title \"My first step\"} )"}

my.agents.root=> ;; I should follow incoming messages with a reverse-ref pull on myself.
(seon.db/pull
  '[{:seon.message/_to
     [:seon.message/id :seon.message/content {:seon.message/from [:seon.agent/id]}]}]
  [:seon.agent/id "root"])
#:seon.repl{:value #:seon.message{:_to [#:seon.message{:content "Define a durable contracted function named largest ...", :id "e44d9f14"}]}, :result result/ed3cf5495e439, :ms 5}

my.agents.root=> ;; My setting overrides; (seon.agent/effective-settings) shows inherited cluster defaults on demand.
(seon.agent/settings)
#:seon.repl{:value {}, :result result/ec8d75e165e99, :ms 56}

my.agents.root=> ;; I should read my saved notes; an empty read will observe the first note I add.
(get (seon.db/pull '[{:my.note/_agent [:my.note/id :my.note/content {:my.note/about [:my.plan.item/id]}]}] [:seon.agent/id "root"]) :my.note/_agent [])
#:seon.repl{:value [], :result result/ede50b6467d29, :ms 4}

my.agents.root=> ; Inspect errors in the namespaces assigned to me and in my own turns.
(seon.db/q
  '[:find
    [(pull
       ?error
       [:db/id
        [:seon.error/declared-schema :limit nil]
        [:seon.error/exception-class :limit nil]
        ... ~2,100 more lines of attribute selectors ...
```

Of the opening, the parts that are data rendered by a render function are:

- every form, which a render function produces from the agent entity (the walk over the agent's neighbourhood);
- every value, which the value renderer prints.

The hand-assembled parts are:

- the `(help)` block;
- the one-sentence `;;` comment embedded in each form producer (`src/seon/cluster/agent.clj:212-235`, `src/seon/error.clj:1808`);
- the `turns left` line.

In the latest capture (basis 536872224, run `c0a2a5f1cd8e`, 5.8 ms to profile):

- 680,643 chars in 228 sections;
- the errors-query read appears in **5** sections of ≈109.7k each;
- the message reverse-pull diff repeats at 6.4k, 3.9k and 2.4k;
- there are 10 `seon.render/ambiguity` results and 389 handle mentions.

`docs/research/agent-platform/context-cost-2026-09-23.md` measured the same
681k prompt for time cost. This note supplies its composition.

### 2.5 Render-pair coverage and the default

These counts come from `(seon.schema/projection-from-database db)`, in
2.3–2.9 ms per probe. A schema counts as having a renderer if its own or its
immediate child's properties carry `:seon.render/ai`.

- There are 3,373 forms and 292 entity (`:seon.db/attributes`) schemas.
- 153 of those declare `:seon.render/ai`, and 153 declare `:seon.render/html`.
- There are 84 non-error entity schemas. **23 have AI renderers and 61 do not.** The 61 include:
  - `:seon.cluster.eval/receipt`
  - `:seon.context.contribution/contribution`
  - `:seon.fn.arity/row`
  - `:seon.fn.argument/row`
  - `:seon.fn.binding/row`
  - `:seon.fn.file/file`
  - `:seon.instrument.explanation/entity`
  - `:seon.error.occurrence/occurrence`
- Non-error renderer functions include:
  - `seon.cluster.agent/render-identity-ai`
  - `seon.plan/render-plan-ai`
  - `seon.note/render-note-ai`
  - `seon.cluster.message/render-ai`
  - `seon.issue/render-ai`
  - `seon.effect/render-ai`
  - `my.turn/render-namespace-ai`

The default for an entity without a renderer (11 ms) is:

- **source**: `seon.render/render-default-ai-source` → `render-form` (`src/seon/render.clj:1351-1376`) gives
  `"(seon.db/pull (quote [*]) [:seon.context.contribution/id \"418ca1784a2d\"])"`;
- **value**: `seon.render.value/render-ai` (`src/seon/render/value.clj:703`) gives the flat attribute map:
  `{:db/id 48841, :seon.context.contribution/hash "89f4…4f9f", :seon.context.contribution/id "418ca1784a2d", :seon.context.contribution/position 6, :seon.context.contribution/tokens 30641, :seon.render.block/name :walk}`.

## 3. Proposal: the smallest compositions

Each item edits one owner in place. None adds machinery. Estimates are net
source lines. All of this is UNVERIFIED until built, because implementation is
frozen.

1. **Render generated reads as short forms, and let data expand them.**
   - Change: `faults-form` emits `(seon.db/q '[:find [(pull ?error [*]) ...] ...] "root")`, or names an owned function such as `(seon.error/assigned "root")`, instead of splicing `observation-selector` into the source (`src/seon/error.clj:1812-1817`).
   - Uncapped cardinality stays the pull owner's decision, not text in the prompt.
   - Effect: removes ≈109k chars per occurrence, which is ≈95% of the opening and ≈80% of the latest prompt.
   - Size: −5 to −10 lines.
2. **Select renderers by declared schema name.**
   - Change: an error value carries `:seon.error/declared-schema`, so the value renderer should select that schema's `:seon.render/ai` and stop trying structural candidates.
   - Also narrow `mcp-prose` to values that actually carry a `:seon.dev.mcp/*` declared schema.
   - Effect: removes the ambiguity class (10 occurrences), so the agent sees its real Datalog error on the first try. This is the existing law "Match supplied defaults by declared schema name."
   - Size: ≈ ±10 lines in `seon.render` selection. The exact stage must be located first.
3. **Give every response a handle, including declared-renderer responses.**
   - Change: in `seon.repl/response` (`src/seon/repl.clj:220`), append `\n;; result/e…` or keep the `#:seon.repl{:value "<text>" :result …}` shape even when a renderer is declared.
   - Store the refusal's data. Evaluation `f34fde256734` already stores the `seon.test.accretion/*` attributes, so the handle can name data the agent can `get-in`.
   - Size: +3 lines.
4. **Store the Malli explanation as values.**
   - Change: in `boundary-refusal` (`src/seon/instrument.clj:617-624`), store the problem's schema form (a value, per "a token observed in text is a VALUE") next to, or instead of, the fingerprint.
   - Use `malli.error/humanize` for the message and delete the constant `humanization-unavailable` text.
   - Size: ≈ ±5 lines, plus a schema member change.
5. **Replay only what changed, once.** `latest-evaluations` and `system-plan` (`src/seon/turn.clj:1907-1960`) already compare shown values. The measured fact is that one failing read was replayed 23 times. Before designing this change, the owner should confirm whether unchanged erroring reads count as "changed" (see Q2). Size: likely ≤10 lines.
6. **Show composition instead of telling it.**
   - Change: have one opening producer consume an earlier opening result by its handle. For example, the notes read could follow the identity read with `(get-in result/e… [:seon.agent/namespace :seon.ns/name])`, and the plan could `->>` over messages.
   - Delete the two `(help)` sentences that describe handles.
   - The data (0 HOF uses, 1 handle use against 389 mentions) says instruction alone does not transfer. The brief's "pump priming" claim suggests demonstration might, but that is UNVERIFIED.
   - Size: ≈ +5/−2 lines.

Taken together: roughly −10 to +20 lines. The expected effect is a first
prompt of ≈6k chars instead of 115k, one error grammar the agent can see, and a
handle on every result.

## 4. Open questions for the owner

**Q1. Where should the uncapped error selector live?**

- (a) Keep `[*]` in the shown form and let `seon.db/pull` decide uncapped cardinality for `:seon.error/*` components. *Recommended: smallest, and the source shows intent.*
- (b) A named owner function `seon.error/assigned`, contracted, whose docstring says what it reads. This is clean, but it adds a function.
- (c) Keep the explicit selector but render the form with the value elider. This violates "apply presentation limits once."

**Q2. Should replays re-run reads that errored?**

- (a) Never replay an erroring read. The agent's next form is the retry. *Recommended: the 23 replays taught nothing.*
- (b) Replay only when its read evidence changed. This is the current intent, but it needs a probe of why it did not hold.
- (c) Replay but collapse identical shown text to a back-reference to the earlier handle.

**Q3. What shape should a declared-renderer response have?**

- (a) Keep the prose and add a trailing handle line. *Recommended: 3 lines, no grammar change.*
- (b) Always `#:seon.repl{:value <rendered> :result …}`. This is uniform, but prose inside a string reads worse.
- (c) Render refusals as data with no prose renderer. This is maximally composable, but it loses the "Fix …" guidance.

**Q4. Should `:seon.fn/spec` stay a string?**

- (a) Store the Malli form as an EDN value, so the agent can `get-in` its own contract. *Recommended, per "preserve native values".*
- (b) Keep the string and add a parsed view in the fn render pair.
- (c) Leave it as it is.

**Q5. Is the measurement representative?**

- (a) Run a varied dogfood task set, then repeat §2.2 by committing this probe as `test/`-adjacent measurement script. *Recommended before tuning (6).*
- (b) Accept the current sample.
