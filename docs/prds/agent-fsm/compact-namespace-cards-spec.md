---
type: prd
status: draft
tags: [prd, agent, flow]
---

# Compact namespace cards + the one-line docstring convention

> **Target design** (present tense — the system as it is when built). Current
> code state + migration live in [[roadmap]]. Grounded in a live-corpus audit
> of the `default` cluster (671 documented fns) — see "What the audit found".

The agent's context renders each namespace at one of two detail levels, and a
single docstring convention is the seam between them:

- **`:compact`** — a CARD: the namespace's `register!` schema block plus every
  public fn condensed to a one-line head — `(defn name "<line-1 doc>" {:malli/schema …} [args] …)`
  with the body elided (`…`). This is what the agent sees for most namespaces.
- **`:full`** — the REAL full file source, unchanged: multiline docstrings, full
  bodies, tips, fix-comments, everything. The exemplars + the agent's own ns.

The convention that makes this clean: **a fn's docstring first line is a
complete, ≤72-char sentence that fully describes it.** The compact card shows
ONLY that first line. Every other line of the docstring — the teaching prose,
the gotchas, the "why", the worked notes — renders ONLY in the full view. So an
author expands a docstring freely (it enriches the full view and the exemplars)
without ever bloating the compact card. One rule, two views, no second
mechanism.

## Why — coverage, not token savings

Squeezing schema ceremony saves ~5% of the full-source budget: a dead end
(measured). The real prize is COVERAGE. A compact card is 3–5× smaller than full
source (`my.kb` 3719 → 664 tokens; `seon.agent.todo` 3696 → 1179). For the
budget currently spent rendering ~11 namespaces in full, the agent can instead
see its ENTIRE verb surface as cards — it stops being blind to 90% of its own
API. The card keeps everything needed to understand the data flows (the whole
`register!` model, every verb, its `:malli/schema` contract, its arglist); only
the implementation body and the deep prose drop away.

The hard constraint this design answers: **bare signatures drove 0× toolkit
adoption** (the render-prominence law, [[toolkit]]). Cards are NOT bare
signatures — they carry the full `register!` data model and the typed
`:malli/schema` contract for every verb. The hypothesis is that THAT is enough
where signatures starved. It is validated by a live drive, not by inspection (see
"Validation").

## What the audit found

Live query over the `default` cluster, 671 documented public fns:

- 91% have multiline docstrings; median full docstring ~76 tokens; 75.4k tokens
  of docstrings across the corpus.
- First lines are already disciplined: median 67 chars, p90 74, max 78 — the
  codebase already wraps at a ~72 fill-column. **The number was never the
  problem.**
- Only 19% (126) have a first line that is a COMPLETE sentence. 81% hard-wrap
  mid-sentence (`"…schema keywords. Used by"`), which reads broken when the card
  shows line 1 alone.
- Cleanup sizing: 111 good as-is (17%), 247 mechanical/light reflow (37%), 313
  real rewrites (47%). Many "rewrites" are really harvesting a good sentence
  already present in the body (e.g. `seon.derive/armable-agent-ids` already
  holds the line its `seon.agent/*` sibling lacks).

Conclusion: this is not "shorten docstrings". It is "make line 1 a complete
sentence within the width the codebase already uses", and render the rest only
in full view.

## The docstring convention (the keystone)

**First line: ONE complete sentence, ≤ 72 chars (hard cap 78), ending in
terminal punctuation.**

Rationale for 72: it is the fill-column already in use (median 67), where the
good exemplars already sit, and the universal convention (git subject, PEP257,
fill-column 70); ≈18 tokens keeps a card fn-head on one physical line. Agents
imitate what they see — a consistent ≤72 corpus teaches agents to write ≤72, so
consistency is the lever as much as the number.

Format rules (derived from the good 19%):

- Line 1 is a complete sentence, ≤72 chars, ends with `.` (or `?`/`!`).
- It states the ACTION and its DATA EFFECT / return — never the mechanism.
  Mechanism, gotchas, and worked notes go in the body.
- **Imperative** for side-effecting verbs (`Store…`, `Mint…`, `Retract…`);
  **noun-phrase** for pure queries (`Agent ids whose…`, `Snapshot of…`). The
  mood signals purity at a glance.
- Backtick-quote identifiers, attrs, keywords (`` `::owner` ``, `` `:idle` ``).
- Then a blank line, then the body — free-form, multiline, as rich as the author
  wants. The body renders in `:full` only.

Proven on real functions (before → after):

- `my.kb/remember`: `Store ONE finding as a durable, provenance-stamped
  knowledge row — the` (wraps) → `Store one verified claim as a durable,
  provenance-stamped fact.` (62)
- `seon.agent.todo/add!`: `Mint one OPEN work item (owner = calling agent; blank
  title refused).` — already optimal (68).
- `seon.schema/current-keys`: `Snapshot of all currently-registered schema
  keywords. Used by` (wraps) → `Snapshot of all currently-registered schema
  keywords.` (53)
- `seon.agent.ctx/current-turn`: `The agent's most-recent turn — the open one,
  else the last `` `:done` ``.` (66)
- `seon.agent/armable-agent-ids`: `Agent ids whose derived state is `` `:idle`
  `` — the ones a trigger can WAKE.` (70)

## The compact card format

Everything derived; the only human input is the (now one-line-clean) docstring.
Per namespace, inside the standard `;;; ┌─ / └─` demarcation:

- **`register!` block — KEPT verbatim.** Reconstructed from the registry for the
  keys the ns owns (`(namespace k)` = this ns), abbreviating ns-local keywords
  to `::`. Kept deliberately: it is the data model, it is real copy-pasteable
  Clojure, and it reinforces the registration pattern (worth more than the ~5%
  it costs — settled).
- **Fn heads.** Each public fn as `(defn name "<docstring line 1>"
  {:malli/schema <spec>} [arglist] …)` — real Clojure, body elided with `…`. The
  `:malli/schema` metadata IS the I/O contract (input → output schema names,
  resolvable in the `register!` block above), so no separate `in → out` summary
  and no fake call-shaped `(request-name)`.
- **No examples.** Considered and DROPPED (owner, 2026-07-01): real eval examples
  already live in the transcript, and harvesting them into the CACHED namespace
  block re-flows every turn → busts the prompt cache (cache-stability law). No
  eval-log harvest, no Malli-generated samples (they're semantically poison —
  `:from 3`, `ok? false`). The card is static: `register!` block + condensed heads.

Worked card (`my.kb`, ~664 tokens vs 3719 full):

```clojure
;;; ┌─ my.kb ─
(register! ::claim [:string {:seon.db/identity true}])
(register! ::confidence [:enum :verified :inferred])
(register! ::remember-request  [:map [::claim ::claim] [::source ::source] [::confidence ::confidence]])
(register! ::remember-response [:or ::remembered :seon.db/transact-response])
(register! ::source-summary [:map [::count :int] [::rating-total :int] [::topic-counts [:map-of :keyword :int]]])
;; … (id, remembered, source, source-line, source-path, verified-at)

; fns (body elided):
(defn remember "Store one verified claim as a durable, provenance-stamped fact." {:malli/schema [:=> [:cat ::remember-request] ::remember-response]} [{::keys [claim source confidence]}] …)
(defn source-stats "Aggregate the KB toward a question — counts, ratings, topics." {:malli/schema [:=> :cat ::source-summary]} [] …)
;; … (12 more)
;;; └─ end my.kb ─
```

## The rendering functions (code workstream)

The compact/full split is expressed in the **config-driven-agent-init** model as
datahike-native **attribute-presence sets** on the namespaces block entity — NOT a
map-of value and NOT a density enum (datahike has no map value type; a `{ns → set}`
would only serialize, killing per-ns reactivity). See decision 13/16 +
[[config-driven-agent-init-namespaces-additions-2026-07-01]]. This extends the
existing `:seon.agent.ctx/render-namespaces` (`[:vector :keyword]`) pattern.

- **Two cardinality-many attrs on the namespaces block** (colocated in
  `seon.agent.ctx.namespaces`): `::full-source` (`[:vector :seon.ns/name]`,
  default `[]`) and `::with-tests` (same). **Presence = config; compact = absence.**
  A ns in `::full-source` renders full; in neither → the card; in `::with-tests` →
  its tests append (composes with either density). No `:compact` token exists, so
  the `#{:full :compact}` conflict is unrepresentable. Current ns = two scalar
  bools (`::current-full?`/`::current-tests?`, default true). Reify to
  component-ref'd entry entities ONLY when a per-ns facet carries a VALUE (token
  cap, weight) — not for booleans.
- **`render-one-ns-compact`** (`seon.agent.ctx`) builds the card from indexed rows
  (`:seon.schema/_ns`, `:seon.fn/_ns` with `:seon.fn/spec`/`:seon.fn/doc`/
  `:seon.fn/arglists`) — NEVER a file read (code-as-data: the boot indexer is the
  one reader).
  - Docstring line 1 = `(first (str/split-lines doc))`. The renderer trusts the
    convention; the doc-lint enforces it. No clip is needed once the corpus
    complies (interim: soft-clip at 78 with `…`).
  - `::` abbreviation for keys whose namespace is the rendered ns.
- **The namespaces section** (`seon.agent.ctx.namespaces/namespaces-block`) reads
  the two presence-sets (`db/pull` → membership check per ns): in `::full-source`
  → full; else → card; `::with-tests` appends tests. Selection of WHICH nses render
  stays config-driven (#42); this adds the full-vs-compact axis as presence sets.
- **On-demand expansion.** `render-namespace` / `render-member` already give the
  agent a full drill of any compact ns or one of its fns. A compact card is never
  a dead end — the full source (with all the multiline prose) is one call away.
  This is the "expand to see tips/tricks/fix-comments" affordance.

## Enforcement

Add a doc-lint sibling to `seon.dev.markdown`, run by the dev hook on every
`.cljs`/`.clj` edit: WARN when a public fn's docstring line 1 is missing, > 78
chars, or does not end in terminal punctuation. This makes the standard
self-sustaining — new fns comply, agents see consistent cards and imitate, and
the compact format never silently regresses.

## Validation (non-negotiable)

The 0× lesson says server-side elegance ≠ agent uptake. Prove cards with a live
A/B drive + a dedicated observer ([[coordination]] observer protocol): two
clusters, same long-planning/DB-memory task, one full-source, one compact cards.
Measure whether the card agent correctly CALLS at least as many distinct verbs.
Cards match-or-beat → roll out; cards regress → we reconfirmed the law cheaply
and keep full source. Keep ≥1 exemplar (`my.kb`) `:full` regardless — it is the
"how to think in this system" anchor, not just an API reference.

## Cleanup — sizing + execution

560 fns need work (247 mechanical/light, 313 real rewrites); 111 are done. The
job is per-fn and parallelizable — one namespace per agent, each applying the
convention above, the doc-lint validating each edit. Order: build the doc-lint
FIRST (so the standard is enforceable and every edit is checked), then sweep.
Do NOT gate the code workstream on the full sweep — the renderer can ship against
today's docstrings (soft-clip at 78) and improve automatically as the corpus is
cleaned.

## Settled — do not re-litigate

- KEEP `register!` calls in the card (the ~5% buys pattern reinforcement + a real
  data model). [[data-model]].
- **NO examples** (owner, 2026-07-01) — eval examples live in the transcript;
  harvesting into the cached block busts the prompt cache. No harvest, no
  Malli-generated samples.
- Schemas are global; the card groups a schema under the ns of its KEYWORD
  (`(namespace k)`), which is already how `:seon.schema/ns` is derived — display
  is decoupled from registration site, so the agent may `register!` anywhere.
- Compact vs full is **attribute-presence** (a ns in `::full-source` or not), NOT
  a map-of value or a density enum — datahike has no map type, so the presence-set
  is the native + reactive shape (extends `:seon.agent.ctx/render-namespaces`).
- `:full-source` (not `:source`) is the name for the verbose density; `:with-tests`
  the additive one. No `:compact`/`:aspect`/`:example`/`:signatures` tokens.

## Open questions

- **Include-set axis.** Presence in `::full-source` sets DETAIL, not inclusion.
  Whether "compact-everywhere for the long tail" broadens the include set to all
  indexed nses (true coverage) or stays a curated set — an owner-gated flip AFTER
  the live A/B drive, decided with the config lane.
- **`register!` map noise.** `[::k ::k]` entries could collapse to `{::k ::k? …}`
  (bare keys, `?` = optional) to shrink the block ~40% — but then it is no longer
  literal runnable `register!`. Default: keep verbatim; revisit if the block
  dominates a card.
- **Reify-when-value-carrying.** Presence-sets are right for boolean facets; the
  day a per-ns facet carries a VALUE (token cap, weight), promote to
  component-ref'd entry entities. Tracked so the shape isn't locked in.
