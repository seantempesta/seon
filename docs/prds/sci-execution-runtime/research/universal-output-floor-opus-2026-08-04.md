---
type: research
status: complete
tags: [research, render, runtime, observability]
---

# The universal output floor — every crossing, one projection

## Verdict

The owner's frame is correct and the mechanism already exists. What does not
exist is the **claim**: no fact says which functions are consumer-visible text
crossings, so nothing can be checked, and eleven independent truncators grew in
the gap. Three structural defects explain every escape on record:

1. **Admission enters host reference values by structure.** A `datahike.db.DB`
   is a `defrecord`, so `seon.sci.admit` walks its index. One database value
   embedded in an ordinary map produced a **993,583-character print node**
   whose emitted display was only 589 characters — the display face hid a 1 MB
   stored artifact. This is the 2 MB transaction face, and it is one rule in
   one function, not a per-site stripper.
2. **Every cap is on the input value's STRUCTURE; nothing caps the output
   TEXT.** `:seon.print/options` has `length` (items), `level` (depth),
   `width` (wrap column) — no size. A top-level 1 MB string emits **262,147
   characters** through `length 32`/`level 8`, because a scalar has neither
   items nor depth. The one text-size mechanism (`print-node-window`) reduces
   the identical node to **2,051**; the MCP seam computes it and then discards
   it.
3. **Values are printed at throw time, before any consumer is known.**
   `seon.instrument/violation` computes a bounded semantic value and then
   stores three *strings* derived from it in `:seon.error/data`.

The design is one declared fact (`:seon.render/consumer`), one generalized
operation (`seon.print/fit`), one refusal rule in `project-node`, and two
accreted keys on the existing elision node. It **deletes** four truncators, two
serializers, one discarded-window bug, and the entire `default-report`
fallthrough. Every piece is strictly smaller than what it replaces.

I read end to end, before probing: the plan README's "Rulings 2026-08-04"
section and every block/render ruling (#12–#16 of the 2026-07-31 batches, #24,
#25, #26, #46, #47, #48, #50, #51, #52),
[render-pipeline-design-2026-07-29.md](render-pipeline-design-2026-07-29.md)
(all 904 lines), [ui.md](../../../seon/architecture/ui.md),
[context.md](../../../seon/architecture/context.md),
[seon.render.edn](../../../../resources/seon/schemas/seon.render.edn), and the
three dogfood reports
([data](repl-dogfood-data-2026-08-04.md),
[edges](repl-dogfood-edges-2026-08-04.md),
[code](repl-dogfood-code-2026-08-04.md)).

## Dependency ledger

| Dependency or owner | Selected revision / path | Boundary read |
|---|---|---|
| SCI fork | `2db3358cba913b6fbbe49c7b5b34d7ac72715924` | bounded evaluation, admission placement |
| Datahike fork | `574c5f0f0db9411d1982769f14512cb24ef719da` | `datahike.db.DB` is a `defrecord`; transaction report shape |
| Malli fork | `80138076960e7820523b4cb932c5b5d1936d4e7f` | `m/properties` carries arbitrary namespaced keys (ruling #47) |
| Print grammar | `src/seon/print.cljc`, `resources/seon/schemas/seon.print.edn` | sinks, options, node faces |
| Admission | `src/seon/sci/admit.clj` | `project-node`, `semantic-value`, caps |
| Floor | `src/seon/render/value.clj` | `prepare`, `print-node-window` |
| Selector | `src/seon/render.clj`, `src/seon/sci/kernel.clj` | producer chain, guarded invoke |
| Caps | `config/default.edn:42-75`, `src/seon/config.clj:99-114` | the one `result-caps` set |

Live probe environment: isolated operator root `tmp/floor-audit-0804`, cluster
`flooraudit`, `current-src` commit `6a726981-cbd8-57d7-9e84-ec0c08504e84`,
digest `1aba6e1c3256cc0eeb3890a6b969d5ef07d21006322f14aca6f016a5b0edb3d6`.
No production source was edited.

---

## 1. Inventory — every crossing where a value becomes consumer-visible text

"Routed" below means the value reaches text through
`seon.sci.admit/admit` → `seon.print/emit-text|emit-hiccup`. Everything else is
an independent path.

### 1.1 Routed today (the projection path)

| Crossing | file:line | Notes |
|---|---|---|
| Floor AI render | `src/seon/render/value.clj:205-245` | `prepare` admits once, tees to both sinks. The one correct shape. |
| Floor HTML render | `src/seon/render/value.clj:247-252` | same admitted node, hiccup sink |
| Guarded producer return | `src/seon/sci/kernel.clj:315-354` | producer output is admitted with the caller's caps before leaving |
| Receipt `result-edn` | `src/seon/sci/admit.clj:484-521` | canonical EDN of the finite node |
| Contract-violation evidence | `src/seon/instrument.clj:123-143` | admits correctly — then discards the value (§2.1) |
| MCP JVM evaluation output | `src/seon/cluster.clj:268-293` | `admit-value` + `print-node-window` — the correct arm |

### 1.2 Bypasses inside the render family (drift)

| Crossing | file:line | Mechanism | Bounded? |
|---|---|---|---|
| Transaction AI producer | `src/seon/db.clj:1010-1032` | `str/join "\n"` over `(map pr-str datoms)` | Only by the producer's own admission (262,144 chars) — up to 8,192 datoms of raw `pr-str` |
| Transaction HTML producer | `src/seon/db.clj:1034-1059` | `pr-str` per datom into `[:li [:code …]]` | same |
| Rejection HTML producer | `src/seon/db.clj:1068-1084` | `pr-str` on conflict attribute/value/owner | no |
| Error AI producer | `src/seon/error.clj:888-903` | `(str attribute "=" (pr-str evidence-value))` per evidence key | no |
| Error HTML producer | `src/seon/error.clj:936-955` | `pr-str` per evidence value into `[:dd …]` | no |
| Error notice evidence | `src/seon/error.clj:638-643` | `pr-str` on args, message, location | no |
| Walk failure text | `src/seon/render.clj:375-377,406-408` | hand `str` with a `;;` comment prefix | bounded, but violates decision 11 (2026-08-03): displays must be form-then-value, never comment-prefixed prose |

**Why this is drift, not constraint:** every one of these functions already
receives the admitted, finite value. Reaching for `pr-str` instead of
`seon.print/emit-text` is a habit, not a requirement — `emit-text` takes a node
and options and returns a string, which is exactly what these lines want.

### 1.3 Floorless surfaces (never touch the projection at all)

| Crossing | file:line | Mechanism | Bounded? |
|---|---|---|---|
| MCP wire | `script/seon/dev/mcp.clj:812-816` | `json/generate-string` → `println`, one line | **no** |
| MCP request log | `script/seon/dev/mcp.clj:831` | `pr-str` of the whole JSON-RPC request | no |
| MCP malformed-event data | `script/seon/dev/mcp.clj:462` | `pr-str event` into ex-data | no |
| Core-fault stderr | `src/seon/cluster.clj:1486-1523` | `bounded-fault-string` + `single-line-fault-text` | own truncator, 4,096 chars |
| Agent stop backstop | `src/seon/cluster/agent.clj:538-540` | `pr-str` on raw `ex-data` | **no** |
| Operator failure | `script/seon/fresh_operator.clj:2669-2671` | `println (ex-message)` then `(prn data)` on raw `ex-data` | **no** |
| Operator status table | `script/seon/fresh_operator.clj:2166-2211` | `format "%-22s %8s …"` + `str/join` | own formatter; the `112`-dash rule at `:2168` does not match the widths |
| Operator process census | `script/seon/fresh_operator.clj:2393-2408` | `println`+`str` per record | no |
| Operator start banner | `script/seon/fresh_operator.clj:1763` | `format "● %-20s %s prepl=%s log=%s"` | own formatter |
| `config apply` result | `script/seon/fresh_operator.clj:1908` | `str` on a remote prepl `terminal-value` | **no** |
| Remote prepl `:out` passthrough | `script/seon/fresh_operator.clj:1811` | `(print (:val event))` | **no**, unframed |
| Child roster / init line | `script/seon/fresh_operator.clj:812,2004-2006` | `pr-str` of a database-derived value on one line | **no** |
| Dependency-cache child output | `script/seon/fresh_operator.clj:290,304` | `println` per line and retained in a vector | no |
| Issue-index CLI | `script/seon/dev/issues.clj:195-214` | `pr-str` of the whole result and of ex-data | no |
| Test-runner `:fail` | `src/seon/test/runner.clj:151` | falls through to `clojure.test/report`, whose defmethod does `(println "expected:" (pr-str …))` with **no `*print-length*`/`*print-level*` in scope** | **no — this is the 478K single-line output** |
| Test-runner aggregate message | `src/seon/test/runner.clj:355-360` | `(str/join "\n\n" messages)`, each capped at 4,096, aggregate uncapped, then transacted | **no aggregate bound** |
| Test-runner thread dump | `src/seon/test/runner.clj:188-204,238-239` | `with-out-str`+`println` per frame per thread, no `take` | **no** — largest single unbounded emission in the tree |
| Test-runner progress | `src/seon/test/runner.clj:219` | `pr-str` on the progress atom | no |
| Edit-hook face | `script/seon/dev/changed_test.clj:466-521` | hand `str`, `(take 6)`, `(take 20)`, 2×4×180-char excerpts | yes — a fourth correct-but-independent truncator |

### 1.4 Independent truncators — the same idea, written five times

| Owner | file:line | Unit | Value |
|---|---|---|---|
| Admission caps | `config/default.edn:42-48` | depth / items / chars / nodes | 64 / 8,192 / 262,144 / 65,536 |
| Print options | `config/default.edn:70,75` | items / depth | 32 / 8 |
| Node-size fitter | `src/seon/render/value.clj:361-384` | **bytes of projected EDN** | caller-supplied |
| Fault string | `src/seon/cluster.clj:1381-1385` | characters | `blob-threshold` (4,096) |
| Test-runner text | `src/seon/test/runner.clj:26-45` | characters | `blob-threshold` (4,096) |
| Instrument evidence | `src/seon/instrument.clj:145-158` | narrowed caps | 8 / 4 / 256 / 32 |
| Transcript budget | `src/seon/render/transcript.clj:625-692` | **estimated tokens** | `::token-budget`, a private dial no producer sets ([issue](../../../seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md)) |

Seven owners, five different units, three different config attributes borrowed
across purposes. `blob-threshold` — a dial derived from *store economics*
(ruling #44, [blob-threshold-derivation-2026-08-03.md](blob-threshold-derivation-2026-08-03.md)) — is used as a *display* cap at two sites.

### 1.5 The one crossing with no total bound

`src/seon/cluster/prompt.clj:66-94` — the prompt is one `seon.render/walk`
result. `tokens/estimate` is recorded at `:64` as **forensic metadata only**.
There is no aggregate token budget on the agent's context. The only real
budget in the system lives inside one block (`transcript.clj`) and is
unreachable in production.

---

## 2. Taxonomy — every known escape, classified

### 2.1 Embedded-value bypass — a value carried inside another value escapes the projection

**E1 — the database value inside a transaction report (the 2 MB face).
NEW ROOT CAUSE, probed.**

`datahike.db.DB` is a `defrecord`, so `seon.sci.admit/project-node`
(`src/seon/sci/admit.clj:218-338`) classifies it as `:seon.print/record` and
walks its index entries. Probe, on `flooraudit`:

```clojure
(seon.sci.admit/admit-value
  {:seon.sci.admit/value {:report/db-after @conn} …caps…})
⟹ {:probe/db-class        "datahike.db.DB"
;;     :probe/node-edn-chars  993583
;;     :probe/emitted-chars   589
;;     :probe/capped?         true
;;     :probe/head "#:report{:db-after #datahike.db.DB{1 :db/cardinality, 1 :db/ident, …"}
```

**993,583 characters of stored print node behind a 589-character display.**
`capped?` was true and bought nothing. The display looked fine, which is
exactly why this survived: the artifact is what costs, and the artifact is what
nobody looked at.

`seon.db` already strips this — but at **one arity of one function**:
`agent-transaction-report` (`src/seon/db.clj:990-1002`) is applied only in
`transact!`'s 1-arity (`:1098-1102`). The 2-arity
(`:1103-1113`) returns `transact-call`'s raw report, and its declared output is
`[:or :map :seon.error/value]` — `:map` admits `:db-before`/`:db-after`
unchanged. Every other producer of a report has the same hole. A per-site
stripper cannot close a class.

**E2 — the print tree inside `:seon.error/data`.**
`seon.instrument/violation` computes `admitted-face`
(`src/seon/instrument.clj:123-143`), which returns *four* representations of
one value: `:seon.instrument/value` (bounded semantic data),
`:seon.instrument/edn`, `:seon.instrument/value-edn`, and
`:seon.instrument/text`. It then stores the **strings**:

- `:269` — `::problems (:seon.instrument/edn problem-face)` — the serialized
  print-node AST;
- `:263-265` — `::schema (:seon.instrument/text expected-face)`;
- `:270-272` — `::args (:seon.instrument/value-edn argument-face)`.

The value is right there and discarded. This is D6 verbatim
([issue](../../../seon/issues/contract-violation-serializes-print-tree-inside-error-data.md)) and it is why
`(my.fs/read 42)` shows an agent
`"#:seon.print{:face :seon.print/vector, :items [#:seon.print{…}]}"`.

**E3 — evidence `pr-str` inside declared producers.** `seon.error/render-ai`
and `render-html` (`src/seon/error.clj:888-903,936-955`) `pr-str` each evidence
value. A `:seon.error/data` carrying a large value re-inflates at render time,
inside a producer whose only bound is the 262,144-char admission cap.

**E4 — raw `ex-data` at operator and backstop crossings.**
`script/seon/fresh_operator.clj:2671` and `src/seon/cluster/agent.clj:538-540`
`prn`/`pr-str` whatever the exception carried. `cluster.clj`'s fault path right
beside the latter bounds itself; the backstop does not.

### 2.2 Floorless surface — a consumer-visible crossing that never had a floor

**F1 — the test-runner `:fail` branch.** `src/seon/test/runner.clj:151`:
`(default-report event)` for every `:fail`. Stock
`clojure.test/report`'s `:fail` defmethod prints
`(pr-str (:expected m))` and `(pr-str (:actual m))` with no print bindings.
The runner's own `printable` (`:37-45`) binds `*print-length*` 32 /
`*print-level*` 8 then caps at 4,096 — and is applied only to the
Throwable-`:error` branch (`:123-138`). One `(is (= expected-db actual-db))`
therefore emits the whole structure on one line. **This is the 478K output**,
and the fix already sits eight lines away.

**F2 — the operator's entire face set.** `fresh_operator.clj` prints through
`println`/`format`/`prn` at ~25 sites. None reaches `seon.print`. The status
table's column widths are padding minimums, so a long URL or drift list breaks
the row.

**F3 — the MCP wire.** `script/seon/dev/mcp.clj:816` writes
`json/generate-string` of the whole response as one line. Ruling #44 (2) put
oversized *results* into the blob tier — but the envelope around them, and
every non-result field, has no bound.

**F4 — the aggregate.** `runner.clj:359` joins N per-message 4,096-char blocks
with no total, then transacts the join. `prompt.clj` joins the walk with no
total. A bound per item is not a bound.

### 2.3 Cap-unit / layer mismatch — a cap exists, in the wrong unit, at the wrong layer

**M1 — the 262k-inline-despite-blob case. Layering bug verified by probe.**

Two probes on `flooraudit`, both against the real production seams:

```clojure
;; 1. the real MCP seam, end to end
(seon.cluster/project-next-prepl-value!)
(count (seon.cluster/mcp-valf "flooraudit" eff
         (seon.sci.eval/evaluate {… "(apply str (repeat 1048576 \"x\"))" …})))
⟹ {:probe/result-edn-count 262220
;;     :probe/capped?          true
;;     :probe/mcp-face-chars   262641      ; <-- the face
;;     :probe/blob-threshold   4096
;;     :probe/print-length     32
;;     :probe/print-level      8
;;     :probe/max-string       262144}

;; 2. the same node through each layer, in isolation
;; node face                                     :seon.print/truncated-string
;; node value chars                              262144
;; emit-text with {length 32, level 8}            262147
;; print-node-window node 32 4096 8, then emit      2051
```

The exact layering bug, in three sentences:

1. **Admission's cap is in the right unit but the wrong magnitude.**
   `max-string` = 262,144 chars produces a `:seon.print/truncated-string` node
   that is *already* "capped" — 262,144 characters is a cap for guarding
   realization, not for a consumer.
2. **The print sink has no cap in that unit at all.**
   `:seon.print/options` (`resources/seon/schemas/seon.print.edn:181-195`)
   declares `length`, `level`, `width`, `namespace-maps?`, `table?`.
   `visible-items` (`src/seon/print.cljc:222-227`) cuts *items*;
   `structural-cut?` (`:217-220`) cuts *depth*. A scalar string has neither, so
   `length 32`/`level 8` emit 262,147 characters. Both caps fire correctly and
   both are irrelevant.
3. **The one cap in the right unit is computed and thrown away.**
   `src/seon/cluster.clj:283-287` computes `projected-node` via
   `print-node-window` (bytes of projected EDN). `:290-293` then branches: if
   `evaluation-print-node` is present — i.e. **every SCI evaluation** — the
   face is built from `evaluation-print-node`, not `projected-node`.
   `text-face` (`:200-210`) emits it with only `length`/`level`.

So `windowed?` reports `true` while the whole window is returned inline. The
fix is a one-symbol substitution; the *design* lesson is that a value has to be
fitted to a budget **in the consumer's own unit**, and nothing in the system
declares what that unit is.

**M2 — `blob-threshold` used as a display cap.** `cluster.clj:1497-1498` and
`runner.clj:29` both take a store-economics dial (bytes of serialized EDN,
derived in [blob-threshold-derivation-2026-08-03.md](blob-threshold-derivation-2026-08-03.md)) and apply it as a
character cap on human-facing text. Two unrelated concerns, one number.

**M3 — human-visible sizes as characters.** Repository law: human-visible sizes
are estimated tokens through `seon.ai.tokens/estimate`. Every display cap above
is characters. Only `transcript.clj` uses tokens, and its dial is unset.

**M4 — the elision marker carries no unit at all.**
`:seon.print/elided` is `{:seon.print/face :seon.print/elided}` — no count, no
path, no reason (`resources/seon/schemas/seon.print.edn:174-179`). The hiccup
sink already computes `::path` and writes `data-seon-path`
(`src/seon/print.cljc:122-134`); the text sink drops it. Hence D7: drilling a
100,000-element blob reports total 8,193 and offset 8,192 returns
`["seon.sci.admit/elided"]`.

---

## 3. The root design

### 3.0 The claim, stated so it can be checked

> No consumer-visible text exists except as the `:seon.render/ai` projection of
> a value, and no page except `:seon.render/html`.

Today that sentence names no set. **The missing fact is the defect.** So:

### 3.1 Declare the crossing — `:seon.render/consumer`

Every function that hands text to a consumer declares which consumer, as a
Malli property on its contract — ruling #47's mechanism, unchanged:

```clojure
{:malli/schema
 [:=> {:seon.render/consumer :seon.render.consumer/operator}
  [:cat :seon.fresh-operator/status-request] :string]}
```

Consumers, as declared config-fact rows under a new
`resources/seon/schemas/seon.render.consumer.edn`:

| Consumer | Budget unit | Rationale |
|---|---|---|
| `:seon.render.consumer/agent` | estimated tokens | repository law; the prompt is priced in tokens |
| `:seon.render.consumer/tool` | estimated tokens | an MCP result lands in an orchestrator's context |
| `:seon.render.consumer/operator` | characters + a terminal line bound | a human at a terminal |
| `:seon.render.consumer/receipt` | bytes | store economics owns this one; `blob-threshold` finally means only this |
| `:seon.render.consumer/page` | none (HTML; the block bound is the render pipeline's) | ui.md's measured 1,000-event hot-unit bound |

The declaration is queryable, so §4's falsifier is a Datalog query and not a
list. It is also the answer to "which cap applies here?" — a question that
today has seven answers.

### 3.2 One fitting operation — `seon.print/fit`

`seon.render.value/print-node-window` (`src/seon/render/value.clj:361-384`) is
already the right algorithm: halve the string limit, then lower the level,
until the projected size fits a budget. **Move it into `seon.print` as `fit`,
generalized over the budget unit** (`:bytes`, `:chars`, `:tokens`), and make
the one emit call:

```clojure
(print/emit-text (print/fit node profile) (print/options profile))
```

`fit` is the missing cap in §2.3 M1 — a bound in the *output* unit, applied to
the *node*, before the sink. It composes with the existing structural caps
rather than replacing them: admission still guards realization, `fit` guards
the consumer.

### 3.3 Refuse to enter reference values — one rule in `project-node`

`seon.sci.admit` already refuses to deref atoms and realize sequences
(`safe-description`, `src/seon/sci/admit.clj:118-136`), and the docstring
already claims "reference types and arrays are never entered". A
`datahike.db.DB` **is** a reference value — an index root plus a store handle —
and it is entered because `record?` wins before that judgment is applied.

The rule, computed, no hand list: **a value satisfying a registered core
predicate whose schema declares `:seon.schema/identity-only true` projects as
an object node carrying its declared identity keys, never by structure.**
`seon.db` already registers `:seon.db/database-value`, and the repository
already mandates that value's identity vocabulary —
`{:db-name :t :as-of :since :history :datahike/commit-id}`. The same
declaration covers connections, stores, flow graphs, and channels, which are
the other things nobody wants walked.

This makes E1 unrepresentable rather than stripped. **It also deletes
`agent-transaction-report`'s reason to exist as a stripper** — what survives is
the honest bounded report shape, applied at both arities.

### 3.4 Errors carry values

`seon.instrument/violation` stores `:seon.instrument/value` (already computed)
instead of `:seon.instrument/edn` / `value-edn` / `text`. `admitted-face`
collapses from four representations to one. `seon.error/render-ai` and
`render-html` then print at render time through `emit-text`/`emit-hiccup`
instead of `pr-str`. That is where the consumer is finally known — which is the
whole point of print-late.

Net: `src/seon/instrument.clj:123-143` loses three of four keys;
`src/seon/error.clj:888-955` loses two `pr-str` sites.

### 3.5 Elisions carry count and requery identity

Accrete two OPTIONAL keys onto the existing node — accretion, not breakage,
so nothing that reads `:seon.print/elided` today changes:

```clojure
{:seon.print/face  :seon.print/elided
 :seon.print/count 91808          ; what was cut
 :seon.print/at    [:edge/rows 8192]}  ; a real get-in path
```

The path is already computed by the hiccup sink and dropped by the text sink;
teaching `node-description` to carry it into both is the change. Combined with
the blob digest the receipt and MCP envelope already carry,
`(get_value digest path offset)` becomes a genuine continuation, which closes
[the elision-marker issue](../../../seon/issues/elided-marker-carries-no-count-or-identity.md) and dogfood
finding 4 with no new mechanism.

`:seon.render.value/window` (`src/seon/render/value.clj:109-148`) already
returns `shown`/`total`/`more?`/`offset` — the vocabulary exists; it just never
reaches the node.

### 3.6 Seams to convert, ordered by blast radius

| # | Seam | file:line | Change | Blast radius |
|---|---|---|---|---|
| 1 | MCP SCI evaluation output | `src/seon/cluster.clj:283-293` | use `projected-node`; `text-face` takes a profile | every agent, every lane, every orchestrator probe |
| 2 | `project-node` reference rule | `src/seon/sci/admit.clj:218-338` | identity-only projection | every receipt, blob, error, and face in the system |
| 3 | Test-runner `:fail` | `src/seon/test/runner.clj:151` | route to `report-error!`'s bounded path; add an aggregate bound at `:359` | every lane's gate output |
| 4 | `seon.print/fit` + profiles | `src/seon/print.cljc`, new schema + config rows | the one fitting owner | all consumers |
| 5 | Instrument evidence | `src/seon/instrument.clj:123-143,247-272` | values not strings | every contract violation an agent sees |
| 6 | Error producers | `src/seon/error.clj:638-643,888-955` | `emit-text` not `pr-str` | every error render |
| 7 | `transact!` 2-arity | `src/seon/db.clj:1086-1113` | one report shape at both arities | first-party write sites |
| 8 | Transaction/rejection producers | `src/seon/db.clj:1010-1084` | `emit-text` per datom under the profile | agent database work |
| 9 | Core fault + agent backstop | `src/seon/cluster.clj:1486-1523`, `src/seon/cluster/agent.clj:538-540` | one operator-profile emit | operator visibility |
| 10 | Operator faces | `script/seon/fresh_operator.clj` (~25 sites) | operator-profile emit; declared consumer | human at a terminal |
| 11 | MCP wire + logs | `script/seon/dev/mcp.clj:462,812-816,831` | tool-profile emit on the envelope | orchestrator context |
| 12 | Prompt aggregate budget | `src/seon/cluster/prompt.clj:66-94` | agent-profile total token budget; wire `transcript.clj`'s dial | agent context cost |
| 13 | Walk failure text | `src/seon/render.clj:375-377,406-408` | drop `;;` prefixes (decision 11) | agent-visible correctness |

Seams 1–3 are independently valuable and land first; each is a small
path-limited commit.

### 3.7 What gets DELETED

- `seon.render.value/print-node-window` → **moves** to `seon.print/fit`
  (one owner, not two).
- `seon.cluster/bounded-fault-string`, `over-fault-inline-ceiling?`,
  `bounded-fault-transaction`, `single-line-fault-text`
  (`src/seon/cluster.clj:1381-1409,1486-1490`) — **deleted**.
- `seon.test.runner/bounded-text`, `printable`
  (`src/seon/test/runner.clj:26-45`) — **deleted**.
- The `default-report` fallthrough at `runner.clj:151` — **deleted**.
- `seon.instrument/admitted-face`'s `:seon.instrument/edn`,
  `/value-edn`, `/text` — **deleted** (three keys → one).
- `seon.db/agent-transaction-report`'s stripping role — **deleted**; it becomes
  the report's ordinary shape once §3.3 makes the database value unwalkable.
- `blob-threshold`'s two display uses — **deleted**; it reverts to meaning only
  what ruling #44 derived it to mean.
- Every `pr-str` in §1.2 — **deleted** in favour of `emit-text`.

### 3.8 Crossings that genuinely cannot share the floor

Stated explicitly, with the reason:

1. **Boot diagnostics before the printer exists.** ui.md already grants
   this: "a recursion-fence failure, overflow callback, development panic, or
   startup/export invariant may still write a brief direct stderr diagnostic
   because it reports the projection or durability machinery itself"
   (`ui.md:63-66`). These must stay literal, and the constraint is that they
   are *literals* — a diagnostic that interpolates a value is not in this class.
2. **The JSON-RPC framing itself.** `json/generate-string` owns the MCP
   envelope's *syntax*; the floor owns its *content*. Seam 11 bounds what goes
   in, not how it is framed. Same relationship as Datastar owning SSE framing
   while `seon.print` owns the hiccup.
3. **A subprocess's own stdout** (`fresh_operator.clj:290,1811`,
   `logs!` via `tail`). Bytes we did not produce are not values we hold. The
   honest treatment is line-bounded relay with an explicit marker, not
   projection — and `logs!`'s `tail -n 200` is already the right shape.
4. **The child-JVM roster/init protocol lines**
   (`fresh_operator.clj:812,2004-2006`). These are `pr-str`/`read-string`
   *transport* between two of our own processes, not consumer text. They should
   be moved off stdout entirely rather than bounded — but that is a separate
   defect (the parent parses a line the operator also displays).

Everything else in §1 can and should share the floor.

---

## 4. The falsifier

### 4.1 The standing computed check — no hand list

Two queries over the program graph, run as one recurring test. Neither
enumerates anything.

**Query A — completeness.** Every declared consumer seam produces a projection:

```clojure
;; Every fn declaring :seon.render/consumer must declare an output of
;; :seon.render/ai or :seon.render/html. Result must be empty.
'[:find ?sym
  :where
  [?f :seon.fn/sym ?sym]
  [?f :seon.fn/render-consumer _]
  [?f :seon.fn.arity/output-refs ?out]
  (not [?out :seon.schema/key :seon.render/ai])
  (not [?out :seon.schema/key :seon.render/html])]
```

**Query B — no bypass.** The set of first-party functions that call a raw
text-emitting core function must equal the set that declares itself a sink
owner. This is a **derived set compared against a declared set**, so a new
bypass fails the check until somebody declares it — the shape ruling #47
requires, and the opposite of a maintained list:

```clojure
;; callers of clojure.core/{pr-str prn println print-str format with-out-str}
;; and clojure.pprint/pprint, via :seon.fn/calls
(clojure.set/difference (raw-emitters db) (declared-sink-owners db))
;; must be empty
```

`:seon.fn/calls` already exists and is what ruling #32's purity derivation and
the workload classification both ride on. Nothing new is indexed.

**The missing fact this names**, stated the way the repository authority asks:
`:seon.fn/render-consumer` does not exist today, which is exactly why no query
can answer "is every crossing floored?". That absence is the defect; declaring
it is the fix.

### 4.2 One regression per escape class

| Class | Regression | Construction that makes the class unrepresentable |
|---|---|---|
| Embedded-value bypass (E1) | Admit a map holding a live `datahike.db.DB`; assert the node's projected EDN is under the receipt profile and the node's face is `:seon.print/object` carrying `:datahike/commit-id` | §3.3's identity-only rule — the walker cannot enter it |
| Embedded string in error data (E2) | Force a contract violation with a 1 MB argument; assert `:seon.error/data` values are all `map?`/`coll?`/scalar and **no value is a string containing `":seon.print/face"`** | §3.4 — the string representations no longer exist to store |
| Floorless surface (F1) | Run a test whose `(is (= …))` compares two 100,000-element vectors; assert the captured runner output is under the operator profile | §3.6 seam 3 — no path reaches stock `report` |
| Aggregate unbounded (F4) | 50 failures in one var; assert `:seon.test.failure/message` is under the receipt profile | §3.2 — `fit` applied to the join, not the parts |
| Cap-unit mismatch (M1) | The exact probe in §2.3: a top-level 1 MB string through the real MCP seam; assert the face is under the tool profile **and** `windowed?` implies a retrievable digest | §3.2 — one `fit` before one `emit`, in the consumer's unit |
| Elision without identity (M4) | Admit `(vec (range 100000))`; assert the elided node carries `:seon.print/count 91808` and that `get_value` at its `:seon.print/at` returns the next window | §3.5 — the count comes from the walk that made the cut |

**Generative totality, not enumeration.** `seon.print` already declares
generators (`sink-generator`, `hiccup-generator`, the node grammar). The
standing property is one: **for every generated node and every declared
consumer profile, `(emit-text (fit node profile) …)` is within that profile's
budget.** That property subsumes M1, M2, and M3, and it is the check the
seven independent truncators each approximated once.

---

## 5. Simplification test

Ruling: *is this simpler than it was?* Anything equally complex is a ported
defect.

| Piece | Replaces | Simpler? |
|---|---|---|
| `:seon.render/consumer` declaration | seven owners each deciding its own cap from three borrowed config attributes | **Yes.** One declared fact answers a question that today has seven answers and no query. It also converts §4 from an impossible audit into two Datalog queries. |
| `seon.print/fit` | `print-node-window` + `bounded-fault-string` + `runner/bounded-text` + `runner/printable` + `changed_test`'s hand caps | **Yes.** Five implementations → one, and it is a **move**, not a new function: the algorithm already exists and is already tested. |
| Consumer cap profiles | `result-caps` overloaded across eval/receipt/render/MCP/instrument, plus `blob-threshold` doing double duty | **Yes**, with one honest cost: five rows where there was one. But the current one row is a *false* unification — the same number cannot be right for a store artifact and a terminal line, and pretending it is caused M2. Five honest rows beat one dishonest one. |
| Identity-only projection rule | `agent-transaction-report`'s stripping + every future per-site stripper | **Yes, strongly.** One `cond` branch in `project-node` replaces an unbounded family of strippers, and it deletes a function rather than adding one. |
| Errors carry values | four representations computed, three stored | **Yes.** `admitted-face` returns one key instead of four; three `pr-str`/serialize call sites disappear. Strictly less code and strictly less data. |
| Elision count + path | a marker with no information, plus `:seon.render.value/window`'s parallel `shown`/`total`/`more?` vocabulary | **Yes.** Two optional keys on an existing node, sourced from a walk that already knows the numbers. It removes the *need* for a second windowing vocabulary rather than adding a third. |
| Prompt aggregate budget | no bound at all, plus a private dial no producer sets | **Neutral on code, required by law.** This adds a mechanism where none existed. It is admissible because the alternative is that the completeness claim is unbounded by construction — and it *deletes* `transcript.clj`'s orphan dial by giving it a real producer. |

**One piece fails the test and is therefore NOT proposed:** producer dispatch
inside admission, for "known monsters" at any depth. It sounds like the general
form of §3.3, but it would run agent-authored code (ruling #46's guarded
kernel) inside the codec that the guarded kernel calls — a re-entrancy the
crash model would have to reason about, and a second resolution chain beside
`seon.render/producer`. The identity-only refusal achieves the same outcome for
the actual escapes with one predicate and no re-entrancy. Recorded here so
nobody re-derives it as an improvement.

---

## 6. Also observed (reported per the ugly-output standing order)

- `src/seon/render.clj:375-377,406-408` — `walk-error` and `repl-state` emit
  `;;`-prefixed prose, which 2026-08-03 decision 11 deleted.
  Existing notes cover sibling sites
  ([walk](../../../seon/issues/render-walk-frames-values-as-comments.md),
  [transcript](../../../seon/issues/transcript-renderer-encodes-entries-as-comment-forms.md),
  [wrapper](../../../seon/issues/render-walk-wrapper-returns-comment-notices.md));
  `seon.render` itself is not yet named in one.
- `script/seon/fresh_operator.clj:2168` — the `112`-dash separator does not
  match the `format` string's own column widths at `:2166`.
- `src/seon/db.clj:1096-1097` — `transact!`'s 2-arity declares output `:map`,
  which is the schema-level expression of E1: a contract that promises nothing
  cannot refuse a database value.

## Verification boundary

Every numeric claim above is a probe transcript from `flooraudit` on
`current-src` commit `6a726981-cbd8-57d7-9e84-ec0c08504e84`, or a `file:line`
read in this checkout. The isolated root `tmp/floor-audit-0804` was used
throughout; no shared cluster was touched and no production source was edited.
The two design pieces I could not falsify by probe are the consumer-profile
budgets (they do not exist yet) and the aggregate prompt budget (§3.6 seam 12);
both are named as design, not evidence.
