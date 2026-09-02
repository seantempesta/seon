---
type: research
status: current
tags: [research, context, render, database, performance, teaching]
---

# REPL-first probes — what an agent dropped into its namespace actually meets

*Live probes on scratch cluster `ctxprobe` (HEAD `43e5e2fff`, booted
2026-09-02 19:54Z; door mode = the cluster's shared SCI ctx, jvm mode =
the host prepl with explicit `(seon.operator/connection "ctxprobe")`).
Every number below is one measured call at THIS population: 322
namespaces, 4,033 `:seon.fn` rows (core name-only rows included), 2,362
schema rows, 1 message, 6 evals, 1 run. The owner's reframe this probe
serves: the agent is dropped into a Clojure REPL in its own namespace,
discovers its neighbourhood through ordinary code and names, learns from
`doc`/`dir`, and writes its own render functions that the priority
chain prefers.*

## 1. The first thing a competent agent would type

| Form (door, ns `my.agents.root`) | Result | ms |
|---|---|---|
| `(seon.db/pull '[*] [:seon.ns/name 'my.agents.root])` | ns row: `:seon.ns/refers` (help/dir/doc → seon.bootstrap), `:seon.ns/requires` as FOUR bare `#:db{:id N}` refs | 141 |
| `(seon.db/q '[:find [(pull ?m [*]) ...] :where [?m :seon.cluster.message/to ?a] [?a :seon.cluster.agent/id "root"]])` | the one message; `:to` is a bare `#:db{:id 30962}` | 139 |
| `(seon.db/pull (seon.db/since 536870929) '[*] [:seon.cluster.message/id "bootstrap-task:root"])` | the message (it is newer than the basis); `since` composes with the elided-db arities | 24 |
| `(seon.db/q '[:find [?id ...] :where [?m :seon.cluster.message/id ?id]] (seon.db/since 536870931))` | `[]` — nothing newer | (same call) |
| `(seon.db/q … (seon.db/as-of 536870929))` count | `nil` — Datahike's own empty-scalar answer | (same call) |
| `(dir my.message)` | `decline inbox read send` | 6 |
| `(dir seon.db)` | 31 names, one line each — compact, teachable | 6 |
| `(doc my.message/inbox)` | docstring + arglists + per-arity contract lines (see §3) | 8 |
| `(doc my.message)`, `(doc :seon.cluster.message/message)` | `nil` — doc accepts only function symbols today | 25 |
| `(help)` / `(my.message/inbox "root")` in DOOR mode | `:seon.call-preparation/unavailable` — the door ctx carries no agent id; expected for the door, but it means the zero-arg reader spelling is only exercisable by a real turn | 113 / 136 |

Findings: **(a)** `[*]` pulls hand the agent bare `:db/id` refs — the
naive first query yields nothing to follow; the identity attribute on
every ref leaf is a property of the WALK's generated selector, not of the
agent's own pull. **(b)** The `since`/`as-of` idiom is Datomic-shaped,
works with elided db, and is the honest "what is new since basis t"
answer with no new mechanism. **(c)** `doc` is single-function only; the
owner's direction (2026-09-02) is that `doc` takes anything and lists of
anythings. **(d)** There is NO agent-facing render function: an agent
cannot render a value itself today (`seon.render/render-ai` needs a full
call-request with ctx, caps, profile, time limit).

## 2. Finding the face for a value — two mechanisms measured

For the pulled message map:

| Mechanism | ms (first / second) | result |
|---|---|---|
| `seon.schema/matching-shapes-in` over the projection (2,362 shape forms; today's `schema-producer`) | 0.32 / 0.018 | `[:seon.cluster.message/message]` |
| Datalog family-from-identity-attribute: `[?r :seon.schema/key :seon.cluster.message/id] [?s :seon.schema/references ?r] [?s :seon.db/attributes true] [?s :seon.schema/key ?key]`, **raw `datahike.api/q`** | 0.11 / 0.024 | same |
| the same query through **`seon.db/q`, jvm mode (no handed projection)** | **2,373** (repeat 587–651) | same |
| the same query through `seon.db/q`, **door mode (projection handed)** | 48 | same |

Decomposition of the jvm-mode `seon.db/q` call: `read-declarations`
0.02 ms (a delay) → **forcing it = `schema/projection-from-database`
5,680 ms**; `query-attribute-error` 0.9 ms; `d/q-with-evidence` 0.68 ms;
`decode-query-result` 2.5 ms. THE WRAPPER REBUILDS THE WHOLE SCHEMA
PROJECTION PER CALL when none is handed — the fetch-at-call-time class
(AGENTS.md §2.1, "217 s vs 6.2 s"), filed as
[seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed](../../../seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md).
Even the handed path is ~500× the raw query; the residue is unmeasured.

The projection match is fast because Malli's shape index is already a
required-key index; the Datalog route is equally fast raw. Either is a
sound base for "best render function for this value"; the choice is
about WHERE the rule lives, not cost.

## 3. What `doc` shows — the teaching surface at the bytes

`(doc seon.db/pull)` prints, per arity, every input ref with its FULL
schema form: `:seon.db/database-value  [:fn {:error/message "must be an
immutable Datahike database value", :gen/gen
seon.db/database-value-generator, :seon.schema/identity-only true,
:seon.schema/identity-projection seon.db/database-value-identity}
seon.db/database-value?]` — twice; `:seon.error/value` listed under
`in:` (a nested alternative's ref, flattened); arity 3 `out:` shows only
`:seon.error/value` (the success shape is not a registered ref, so it is
dropped). Filed:
[doc-contract-lines-print-schema-bodies-and-flatten-arity-alternatives](../../../seon/issues/doc-contract-lines-print-schema-bodies-and-flatten-arity-alternatives.md).
Ground: `seon.sci.eval/role-contract-lines` derives from the arity's
flat `input-refs` SET, while the graph already stores the ordered
`:seon.fn.arity/arguments` with per-argument schemas (verified on the
`seon.cluster.message/render-ai` arity row).

## 4. Faces at HEAD — what the registry knows

- 364 `:seon.render/ai` and 359 `:seon.render/html` declarations across
  schema rows; by function: `seon.error/render-ai` 285, `ai-prose` 20,
  `index-refusal-prose` 11, then ~30 singletons (message, run, cluster,
  config, plan ×3, note ×2, …). The error family is 90% of all faces.
- Face values are EDN-encoded strings at the datom (`datahike.api/q`
  sees `java.lang.String` ×364) and decode to symbols through
  `seon.db`'s read declarations (`decode-attribute-value-in`) — a
  declared codec, not a defect; raw Datalog joins against
  `:seon.fn/sym` must account for it until the identity retype (47/48).
- The face function's contract is `[:=> [:cat :seon.render/unit]
  [:maybe :string]]` (message): input is the generic render UNIT, output
  is `[:maybe :string]`, so "functions whose output is `:seon.render/ai`
  and input is family X" is NOT expressible as a query today — the
  owner's proposal (a render fn declares input = the data's schema,
  output = `:seon.render/ai`) requires the face contract shape to change.
- `:seon.fn.arity/output-refs :seon.render/ai` matches ZERO rows.

## 5. Eval evidence weight

6 evals carry 163 read-evidence rows; `pr-str` sizes summed per
attribute: `:seon.db/read-result` 223,182 chars (152 rows),
`:seon.db/read-request` 72,359, `:datahike.read/revision` 61,870,
`:datahike.read/dependency-plan` 39,285. The FULL read result is stored
inside evidence (used by `read-evidence-current?`'s replay comparison,
db.clj:447) in addition to the eval's `result-edn` — ~400 KB of
evidence for six evals. Not filed yet; a store-growth member to weigh
when the eval family is redesigned (48b).

## 6. Tool health met on the way

- `mcp__seon__runtime_status`: the known `seon.config/missing-projection`
  smell (lane `mcp-status-2` running). `eval_clj` jvm + door and
  `get_value` all answered correctly.
- `GET /agent/root/debug` on the fresh cluster: "The prospective agent
  context is unavailable." with the cause swallowed (lane `debug-page`
  died to the network; relaunch owed).
- Bare `bin/test` with a live cluster: the runner's persistent-results
  write opens the shared store, is refused by the flock, THROWS BEFORE
  the tally, exits 1 (lane `gate-evidence`, same fate).
- `bin/test --all` at HEAD (tmp/gate-all-2026-09-02.log): platform tier
  72 tests, 1 error = `seon.cluster.cohost-boot-test/a-second-cluster-…`
  hit the 270 s worker-exchange bound under load (9 workers + six lane
  gates + one live cluster on one machine) and PASSED its isolated
  confirmation in 202 s; five platform tests each take 200–244 s
  (`test-support-test/a-canonical-database-…` 244 s). Slow is a bug: the
  platform tier's own duration is the next measurement.

## 7. Since with reverse refs; render selection at the bytes (added later the same day)

- `(seon.db/pull (seon.db/since 536870929) '[:seon.cluster.agent/id {:seon.cluster.message/_to […]}] [:seon.cluster.agent/id "root"])`
  returns the message (newer than the basis); the same pull on
  `(seon.db/since 536870931)` returns `nil` — nothing new. The
  since-shaped re-run composes with the walk's reverse-ref selector
  shape, 14 ms for three pulls. `seon.db/basis-t` has NO elided arity
  (the agent must type `(seon.db/basis-t (seon.db/db))`) — the one
  `seon.db` read without the elision convention; small discoverability
  defect.
- `seon.render/render-ai` on the pulled message, jvm mode under the
  cluster's projection-state: family face selected, 4.9 ms first call,
  3.5–4.0 ms repeats; output "From outside this cluster to root: …" —
  a narration face (census cat. 3), the text the agent gets today.
- An agent-namespace render function defined AT THE DOOR
  (`my.agents.root/inbox-view`, contract `[:=> [:cat :seon.render/unit]
  [:maybe :string]]`) was NOT selected with `:seon.render/namespace
  'my.agents.root`: `render/candidates` reads
  `sci.kernel/public-functions-in` from the ctx's PROGRAM SNAPSHOT and
  `schema/function-accepts-in?` from the projection's
  `function-contracts` — both program-row facts. A door def mints no
  row, so it is invisible by construction; a real turn's contracted
  `defn` settles as a row and would be found. Consistent with facts over
  inference (§2.2): the render function has to be a FACT before the
  generator may prefer it. Trial P-OWN-RENDER-WINS must therefore run
  through a settled turn, never a door def.
