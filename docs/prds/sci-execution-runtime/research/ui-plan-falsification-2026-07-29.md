---
type: research
status: complete
tags: [research, ui, render, datastar, datahike]
---

# UI conversion plan falsification review

## Verdict

**REJECT FOR SEAL: 1 SEAL-BLOCKING, 2 REVISION, 4 NOTE.**

The two plan-changing slice-1 claims survive direct falsification. A planted
from-less message is reached through the agent's reverse `to` ref at the page's
default distance 1 and is projected by
`seon.cluster.message/render-html`. A constant message-bar surface is absent
from the per-tab patch set when another surface changes. Datahike also makes
the proposed basis-derived inbound identity viable: `:max-tx` is readable
inside `:db.fn/call`, and the serial writer advances the transaction
function's predecessor database value between accepted transactions.

The plan is nevertheless not seal-ready. Its controls slice admits arbitrary
agent-authored Hiccup attributes and only rewrites recognized Clojure handler
slots. A crafted render can therefore emit a native form or raw Datastar
action that POSTs directly to `/agent/{victim}/message`, bypassing
`/agent/{id}/call` and its callback gate entirely. That produces a message
which the rest of the system correctly treats as from outside the agent
population. The controls contract needs a closed agent-authored browser-action
boundary before implementation.

D4 should also be revised: reitit is the target router, but it is not an
implementation dependency for one exact POST route. The existing Ring handler
can discriminate request method and exact URI without introducing a second
dispatcher.

## Result summary

| severity | count |
|---|---:|
| SEAL-BLOCKING | 1 |
| REVISION | 2 |
| NOTE | 4 |
| **total** | **7** |

## Review basis

Reviewed target:
`docs/prds/sci-execution-runtime/plan/ui-conversion-plan-2026-07-29.md`
at its introducing commit
`4e9ca56ee8ca12b78b2b736fe4f9f9a2f096c97f`.

The current-tree basis at the start of the decisive probes was
`60b34659a467c5ee7225e8acab2fa73e54186d1e`. The shared checkout carried
unrelated edits in render, loop, oversight, configuration, tests, and other
research reports. They were not modified by this review. The suppression
function itself was unchanged by the concurrent `render/web.clj` edit.

Dependency ledger:

| dependency | selected revision | source read |
|---|---|---|
| Clojure | 1.12.5, `deps.edn:15` | the focused JVM probes below |
| Datahike | `9a7a9ef10a95` | `reference-code/datahike/src/datahike/db/transaction.cljc`, `db.cljc`, `writer.cljc` |
| reitit | `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab` | `reference-code/reitit/modules/reitit-ring/src/reitit/ring.cljc`, `modules/reitit-core/src/reitit/core.cljc` |
| Datastar Clojure | `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | the first-party serializer, shell, and feed integration |
| http-kit | `70432d3ab3c9` | the existing first-party Ring handler |
| first-party owners | current tree | `src/seon/render/{walk,agent,block,web,hiccup}.clj`, `src/seon/cluster/{message,work,wake}.clj*`, their schemas and focused tests |
| quarry gate | condemned reference only | `src-old/seon/web/reactive/{call,transform}.cljs` plus its localized `AGENTS.md` |

## Priority disposition

| priority | attacked claim | verdict |
|---|---|---|
| reverse-ref echo | A sent message already appears on the agent page at distance 1 | **Verified.** The planted-message page contained `seon-message-entry` and the exact content through `:seon.cluster.message/to`. |
| bar equality suppression | A constant bar is never patched after initial paint | **Verified.** `web/changed` emitted only the changed sibling; the existing real-socket suite proves the same class. |
| inbound identity | Basis-derived identity can collide or cannot read `:max-tx` in `:db.fn/call` | **Falsified as a risk.** Sixty-four sequential inbound transactions produced 64 distinct predecessor basis values and ids. |
| episode classification | Tx provenance could make a from-less browser message autonomous rather than outside | **Verified outside.** With the dial absent, an older agent-sent trigger was deferred and the later provenance-bearing browser trigger was selected. |
| controls gate | A crafted render cannot escape the admitted action path | **Broken.** It can target another state-changing POST route without entering the callback gate. |
| D4 | reitit is required for slice 1's one POST | **Overstated.** It is architectural prepayment, not a slice-1 correctness dependency. |

## SEAL-BLOCKING finding

### SB-1 — arbitrary authored browser actions bypass the callback gate

**Claim attacked.** Slice 3 says the postwalk plus
`POST /agent/{id}/call` applies capability-door discipline to an
agent-authored control. The sealed postwalk test explicitly requires ordinary
attributes to pass through untouched.

**Falsifier.**

The fresh Hiccup grammar accepts every attribute map without classifying
browser behavior (`src/seon/render/hiccup.clj:89-147`). Its honest generator
even includes a raw Datastar action attribute
(`hiccup.clj:149-183`). Serialization emits those attributes
(`hiccup.clj:371-391`). The proposed postwalk only replaces recognized
`:on-*` fn-call or fn-ref slots; by plan and quarry design, an already-authored
`data-on:*`, native form `action`, `formaction`, inline `on*`, or other
executable/network attribute survives.

A direct JVM probe over the production grammar and serializer admitted:

```clojure
[:form
 {(keyword "data-on:submit")
  "@post(\"/agent/victim/message\", {contentType:\"form\"})"}
 [:input {:name "content" :value "agent-authored-spoof"}]
 [:button {:type "submit"} "send"]]
```

and serialized it with both `data-on:submit` and
`/agent/victim/message` intact:

```clojure
{:admitted? true
 :reaches-ungated-post? true}
```

A plain `[:form {:method "post"
:action "/agent/victim/message"} ...]` needs no Datastar expression at all.
When the human clicks it, the browser supplies the same-origin request that
the slice-1 middleware admits. The inbound handler then commits the row with
no `from`, so it is structurally indistinguishable from the human-message
origin the plan intends. Later `/agents` creates the same bypass class for
agent birth.

This does not show that `/agent/{id}/call` admits a forbidden function. It
shows something worse: the crafted control need not enter that route. The
callback gate cannot refuse a request it never receives.

**Required revision.** Define a separate closed grammar for agent-authored
Hiccup at the render boundary. Agent-authored output must not carry browser
execution or network authority except through declarative Clojure handler
slots which the trusted postwalk rewrites. After rewriting, revalidate the
result. At minimum the contract must cover raw `data-on:*`, native `on*`,
`action`/`formaction`, script-capable tags, and other request-producing
attributes as one failure class. Trusted core Hiccup may retain the full
serializer grammar.

The sealed proof must submit a crafted raw action and native form, assert that
neither serializes to an executable request, and independently prove that the
recognized Clojure handler slot still reaches the guarded `/call` boundary.

## REVISION findings

### R-1 — D4 is premature for one method-discriminated route

The plan accurately describes reitit's eventual value. The vendored
`reitit.ring/router` compiles method endpoints
(`ring.cljc:121-151`), `ring-handler` selects by request method and injects
path params (`ring.cljc:360-404`), and the core router rejects unresolved path
and name conflicts (`reitit/core.cljc:329-380`).

That does not make it necessary for slice 1. The current
`seon.render.web/handler` is already the one Ring dispatcher and receives
`:request-method` plus `:uri` (`src/seon/render/web.clj:531-614`). It currently
ignores the method because every live route is a GET. One exact branch before
the `/agent/` GET prefix is sufficient:

```clojure
(and (= :post (:request-method request))
     (= (str "/agent/" target-id "/message") (:uri request)))
```

or the equivalent exact parse. This does not introduce a second dispatcher;
it strengthens the existing one in place. The main dependency set currently
has no reitit coordinate; only the dead historical CLJS alias carries a Maven
coordinate (`deps.edn:168-188`).

Revise D4 to call reitit architectural prepayment rather than a slice-1
dependency, or defer it until `/call`, nested route data, and capability
middleware make the tree pay for itself. If it lands in slice 1 by owner
choice, the plan should state that cost honestly rather than claim the
existing handler cannot express a correct POST.

### R-2 — the callback-gate suite covers only part of its refusal surface

For a request which actually reaches `/agent/{id}/call`, the design is sound:
the control is not authority and refusal precedes invocation. The target
architecture deliberately makes public agent-authored functions shared
cluster capabilities, so caller and original author may differ
(`docs/seon/architecture/ui.md:564-577`). An ownership-equality check must not
be added.

The quarry gate and the target together define these refusal classes:

| boundary | refused condition | expected result |
|---|---|---|
| route middleware | cross-origin state-changing request | refused before handler |
| descriptor parse | missing route agent id or function symbol | 400 |
| route agent | missing or terminated/not live | 403 capability refusal |
| function row | unknown, private, or not registered | 403 capability refusal |
| source provenance | source transaction has no agent author or is not from the REPL process | 403 capability refusal |
| source identity | missing committed source fingerprint or complete schema | core-unavailable, never invoke |
| argument codec | malformed encoded arguments, a non-vector, or code-shaped/non-data values | 422 |
| exact contract | arguments do not satisfy the committed function schema, including a stale source/schema mismatch | 422 |
| durable admission | pending interaction cannot be committed | unavailable; no invoke |

The plan's sealed test names only a private function, schema-invalid args, and
non-REPL provenance. Expand it with crafted direct POSTs covering the other
classes and assert both rails every time: invocation count remains zero and no
pending interaction fact exists. This revision is distinct from SB-1: these
are the refusals after a request reaches the gate; SB-1 prevents authored
output from choosing another POST boundary.

## NOTE findings

### N-1 — reverse-ref walking really renders the planted message

The source chain composes:

- `reverse-refs` queries every source entity pointing at the target eid and
  retains `[attribute source]` (`src/seon/render/walk.clj:203-235`);
- `refs` appends those reverse connections after forward refs
  (`walk.clj:289-315`);
- `neighborhood` calls each neighbour at distance minus one and still renders
  the distance-0 node (`walk.clj:328-421`);
- `namespace-html` requests the agent neighbourhood in HTML and recursively
  emits every node's output (`src/seon/render/agent.clj:136-180`);
- the page supplies the one default distance of 1
  (`src/seon/render/block.clj:170-180,770-850`);
- the message entity schema declares
  `seon.cluster.message/render-html`
  (`src/seon/schema/message.edn:36-44`).

The focused probe created agent `bob`, installed the ordinary agent block set,
and committed a message whose `to` was bob and whose content was
`PLANTED-REVERSE-REF-ECHO`. `web/page-of` returned:

```clojure
{:surface-ids ("surface-namespace")
 :namespace-present? true
 :message-rendered? true
 :message-renderer-selected? true}
```

The actual HTML included:

```html
<span class="seon-neighborhood-connection">:seon.cluster.message/to</span>
<article class="seon-family-entry seon-message-entry">
  <p>From outside this cluster to bob: PLANTED-REVERSE-REF-ECHO</p>
</article>
```

The plan's zero-new-render-code echo claim is correct for slice 1.

### N-2 — byte equality keeps a constant bar out of later patches

`web/changed` compares each current `[surface-id html]` with the same id in the
tab's last-delivered map and emits only unequal bytes
(`src/seon/render/web.clj:232-256`). The tab retains that delivered map across
snapshots (`web.clj:433-505`). The render proc may suppress an entirely equal
page earlier, but when another block changes it still publishes a complete
snapshot and the tab performs the per-surface diff.

The plan's conceptual falsifier therefore reduces to:

```clojure
(web/changed
 {"surface-message-bar" constant-bar
  "surface-counter" "<div>1</div>"}
 {"surface-message-bar" constant-bar
  "surface-counter" "<div>2</div>"})
```

The probe returned only `surface-counter`; `bar-patched?` was false.
`test/seon/render/web_test.clj:327-343,591-604` already proves the same
failure class on a real socket and as a pure unit. Initial paint still sends
the bar once, and redefining its render correctly changes its bytes and
patches it. The narrower plan claim—unrelated morphs do not disturb a
constant bar—is correct.

### N-3 — the basis-derived identity risk does not materialize

Datahike's `:db.fn/call` applies the function to the transaction's current
database value (`reference-code/datahike/src/datahike/db/transaction.cljc:
1142-1143`). The DB record exposes `max-tx` as a field and lookup
(`datahike/db.cljc:307-342`). Transaction completion increments
`:db-after :max-tx` (`transaction.cljc:1195-1248`), and the serial writer
threads each successful report's `db-after` into the next accepted invocation
(`datahike/writer.cljc:100-117,171-183`). Batched durability does not reuse the
predecessor value: the processing loop advances its in-memory `old` before
the commit loop flushes the batch.

The seed-2026072901-shaped probe ran 64 transactions, each deriving
`inbound-<(:max-tx db)>-0` inside `:db.fn/call`:

```clojure
{:seed 2026072901
 :n 64
 :before-max-tx 536870916
 :after-max-tx 536870980
 :ids-count 64
 :distinct-count 64
 :max-tx-readable? true
 :basis-values-distinct? true
 :basis-range [536870916 536870979]}
```

The feared duplicate can occur only if two rows in the same transaction use
the same index, or if the one-writer-per-database invariant is violated.
Slice 1 emits one row per POST; an intra-transaction fan-out must retain the
proposed index. The fallback allocator is not needed for the stated case.

### N-4 — tx-meta provenance does not stop an outside episode reset

`outside-trigger?` classifies a message as non-outside only when the message
entity itself carries `:seon.cluster.message/from` or
`:seon.cluster.message/about`
(`src/seon/cluster/work.cljc:136-151`). `episode-runs` uses the same two
absence clauses (`work.cljc:153-189`). Neither query inspects
`:seon.db/user`, `:seon.db/process`, or any other transaction metadata.

The focused probe removed the episode dial so the derivation failed closed
for agent-sent triggers, committed an older message from alice to bob, then
committed a later from-less browser message with both provenance refs in
tx-meta. It returned:

```clojure
{:dial-absent? true
 :selected-situation :open
 :selected-message "browser-second"
 :deferred ["agent-first"]
 :browser-has-from? false
 :browser-tx-meta-attrs
 #{:db/txInstant :seon.db/process :seon.db/user}}
```

The inbound shape composes correctly with the episode derivation: provenance
stays on the transaction, and the browser message resets the episode as an
outside trigger.

## Required plan changes before seal

1. Add the closed agent-authored Hiccup/action boundary and its bypass
   falsifiers to slice 3 before any controls implementation.
2. Revise D4 as architectural prepayment or defer it; do not call reitit a
   correctness dependency for the first exact POST.
3. Expand the callback-gate suite to cover the complete refusal table and
   assert both no invocation and no pending fact.
4. Keep the slice-1 reverse-ref, equality, identity, and episode designs. The
   direct probes found no plan-changing defect in those four claims.
