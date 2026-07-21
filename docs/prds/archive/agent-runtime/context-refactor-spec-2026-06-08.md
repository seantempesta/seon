---
type: research
status: active
tags: [research, agent, flow]
---

# Context refactor — validated spec (2026-06-08)

> Independent REPL validation of the rev-2 PRD claims against the LIVE V0 pod
> (port 7890, agent `LZL-2605271732`), plus the precise, in-place refactor to
> collapse the two divergent context paths into ONE derived `assemble-context`.
> No source was edited producing this spec; every claim below carries the exact
> eval + result used to confirm it.

## TL;DR

- **All 5 claims VALIDATED TRUE** (with two precise corrections to magnitudes —
  see table). The agent runs blind because `render-prompt` → `assemble-ctx`
  reads the agent's STORED `:seon.agent/ctx` (nil) and produces **0 chars**, not
  ~22. The PRD's "~22" is wrong; the real number is **0**.
- The **code-default section list** (`substrate-default-ctx`) composed through
  the same composer logic yields **12521 chars**, all six section symbols
  resolve. Fixing the layout source from "stored entities" to "code default"
  is the whole fix.
- The inspector path (`assemble-ai-context`) is a **completely different
  mechanism** (tx-log-as-context, 6129 chars, 13 chronological entities), not a
  variant of the composer. Collapsing the two means the inspector must call the
  composer path; `assemble-ai-context` does NOT become `assemble-context` —
  the section composer does.
- `current-ns-section` = 0 is **correct given the data**, not a code bug: the
  agent's home-ns `:seon.agent.LZL-2605271732` has NO `:seon.ns` entity because
  none of its 7 evals defined anything in that ns (4 failed; 3 were
  `println`/`current-agent-id` probes). Only the stale docstring is wrong.
- **Recommended collapse:** make `assemble-ctx` (rename in place to
  `assemble-context`) take its section list from `substrate-default-ctx` as a
  code default, fall back to stored `:seon.agent/ctx` only when present. Keep
  `:seon.agent/ctx` as an OPTIONAL override (it has live callers: `reset-ctx!`,
  `update-ctx!`, `ctx-entities`, the system-section cheat-sheet). Make BOTH
  `render-prompt` and the inspector resolve+call it.
- **Blast radius is small and contained to 3 files** (`agent.cljs`,
  `render.cljs` thin shim, `web/inspector.cljs` + `inspect.cljs`). Safe to
  hot-reload. The risky surface is the running agent loop's `render-prompt`;
  guarded by the regression test (no-stored-ctx → full context).

---

## 1. Validation table

| # | Claim | Verdict | Evidence (eval → result) |
|---|-------|---------|--------------------------|
| 1 | `render-prompt` (`agent.cljs:628`) resolves `:seon.render/ai` (default `'seon.agent/assemble-ctx`) → calls it; `assemble-ctx` (`agent.cljs:1298`) reads its section list from STORED `:seon.agent/ctx`. | **TRUE** | `(:seon.render/ai ent 'seon.agent/assemble-ctx)` → `seon.agent/assemble-ctx`, `:is-default? true`. `assemble-ctx` body pulls `{:seon.agent/ctx [...]}` and `sort-by :seon.ctx/priority (:seon.agent/ctx agent)` — confirmed by reading `agent.cljs:1303-1309`. |
| 2 | Agent `LZL-2605271732` has NO `:seon.agent/ctx` → `assemble-ctx` ~empty (~22 chars). | **TRUE (magnitude corrected: 0, not 22)** | `{:ctx-raw nil :ctx-entities-count 0}`. `(seon.agent/render-prompt id)` → **len 0**, sample `""`. `(assemble-ctx {...})` → `:assemble-ctx-len 0`. The "~22" in the PRD is wrong; it is exactly **0**. |
| 3 | The 6 default sections produce ~12.5K total when run directly; per-section lengths; `current-ns-section`=0. | **TRUE (exact)** | `{:system 1098, :messages 1510, :current-ns 0, :warnings 77, :recent-evals 9790, :prompt 38}` → sum **12513**. Composing the **code-default** list through the composer logic → **12521** chars, `:resolves-all? true`. (Tiny delta = the `"\n\n"` joins.) |
| 4 | Inspector left pane (`inspector.cljs:114`) renders via a DIFFERENT fn `render/assemble-ai-context`, which does NOT read stored ctx, ~6129 chars (divergence). | **TRUE (exact)** | `(assemble-ai-context {...})` → `:assemble-ai-len 6129, :n-entities 13, :token-est 1532`. Sample begins `;; You are a Clojure-fluent agent…` — a sticky preamble, NOT the `<system …>` composer header. Different content AND different mechanism (tx-log-as-context). |
| 5 | `:seon.turn/prompt-text` persisted EMPTY for this agent's turns; trace where it's written. | **TRUE** | Query over the agent's turns → 3 turns, all `:status :done`, all `:pt-len 0` (present-but-empty, NOT absent). Written in the **open-turn tx** inside `with-turn!` (`agent.cljs:670`) from the `prompt-text` arg, which `run-turn!` (`agent.cljs:743,764`) fills from `(render-prompt id)`. Because `render-prompt` returns `""`, the stored value is `""`. The wiring is correct; the source string is empty. |

### Corrections to the PRD

- **render-prompt is 0 chars, not ~22.** The PRD's rev-2 "~22 chars of context"
  is wrong. Measured 0. Minor, but the guard test should assert `> 0`, and the
  recon claim of "~22" should not be carried forward.
- **`current-ns-section` is NOT broken code.** It returns 0 because the data it
  reads doesn't exist for this agent (no `:seon.ns` entity for the home ns), not
  because detect-and-tee "hasn't shipped." Detect-and-tee HAS shipped
  (`eval.cljs:715 build-tee-entities`). The section docstring (`agent.cljs:1113`)
  is **stale** and should be corrected to say "empty until the agent defines a
  fn/schema/(ns …) in its home ns." See §5.

---

## 2. Caller inventory (blast radius)

### `assemble-ctx` (`agent.cljs:1298`)

- **Only caller:** `render-prompt` (`agent.cljs:634`), *indirectly* — it resolves
  the symbol `'seon.agent/assemble-ctx` from `:seon.render/ai` and dispatches via
  `render/ai-render`. There is **no direct code call**; it is invoked by symbol
  resolution. (It is also the documented default in docstrings at
  `agent.cljs:35,79,630`.)
- **No tests** reference `assemble-ctx` by name (grep of `test/` empty).

### `render-prompt` (`agent.cljs:628`)

- **Only caller:** `run-turn!` (`agent.cljs:743`) — `prompt (render-prompt id)`,
  then threaded into `with-turn!` and `ask-and-eval!` (`agent.cljs:764,769`).

### `assemble-ai-context` (`render.cljs:406`)

- **Callers (3):**
  - `web/inspector.cljs:114` (`snapshot` — the left-pane SSE render)
  - `inspect.cljs:53` (`ctx-preview` — agent-facing "what would I see next")
  - Referenced in docstrings only: `handlers/message.cljs:10`,
    `handlers/eval.cljs:9` (comments, no calls).
- **`renderable-entities` / sticky helpers** live in `render.cljs` and are used
  only by `assemble-ai-context` + `inspect.cljs/visible-entities` (entity list,
  no text). These are the tx-log-as-context machinery.

### `substrate-default-ctx` (`agent.cljs:1330`)

- **Only caller:** `reset-ctx!` (`agent.cljs:1356`) — seeds the six defaults into
  stored `:seon.agent/ctx`. **This is the function the new code default reuses.**

### Readers/writers of `:seon.agent/ctx`

- **Writers:** `reset-ctx!` (`agent.cljs:1354-1356`), `update-ctx!`
  (`agent.cljs:1369-1371`). Schema attr at `agent.cljs:235`; bootstrap-attr list
  `client.cljs:258`.
- **Readers:** `assemble-ctx` (`agent.cljs:1306`), `ctx-entities`
  (`agent.cljs:1047`), `root-pull` pull pattern (`agent.cljs:951`), system-section
  cheat-sheet text mentions the verbs (`agent.cljs:1092-1094`).
- **NOT seeded at agent creation.** `start-agent!` (client.cljs) never calls
  `reset-ctx!`; that is why the live agent has nil ctx. (Confirmed: `:ctx-raw
  nil` on a long-lived agent.)

### Readers/writers of `:seon.turn/prompt-text`

- **Writer:** `with-turn!` open-tx (`agent.cljs:670`), value from `run-turn!`
  (`agent.cljs:743,764`).
- **Readers:** none in `src/` today except schema/bootstrap registration
  (`agent.cljs:218`, `client.cljs:281`). It is write-only provenance — exactly
  the intended role. No code reads it back to build context (good; the principle
  forbids that).

**Net blast radius: `agent.cljs` (the composer + render-prompt + section docstring),
`render.cljs` (thin shim if we keep the symbol name), `web/inspector.cljs` +
`inspect.cljs` (switch to the composer). 4 files, ~5 call sites.**

---

## 3. The two paths, precisely — and the collapse

### What `assemble-ctx` computes (the composer — KEEP this mechanism)

`agent.cljs:1298`. Pulls `:seon.agent/ctx` (a vector of `:seon.ctx` entities
`{:seon.ctx/name :seon.ctx/priority :seon.ctx/fn}`), sorts by priority, resolves
each `:seon.ctx/fn` symbol via `seval/lookup-value`, calls it with
`{:seon.db/db :seon.agent/id :seon.agent/ctx-entity <section>}`, drops blanks,
joins with `"\n\n"`. Returns `{:seon.render/text "…"}`.
**Sole defect:** the section LIST comes from stored `:seon.agent/ctx`, which is
nil → 0 sections → "". The composer logic itself is correct (proven: feeding it
the code-default list yields 12521 chars).

### What `assemble-ai-context` computes (tx-log-as-context — a DIFFERENT thing)

`render.cljs:406`. Queries `renderable-entities` (every entity carrying a
`:seon.render/ai` symbol visible to the agent), splits sticky-prefix vs window,
sorts, takes last N (default 64), renders each entity via its own
`:seon.render/ai` symbol, joins. Returns
`{:seon.render/text :seon.render/entities :seon.render/token-estimate}`.
This is the "render the chronological tx log of program-graph + message + eval
entities" approach. It is NOT section-based and does NOT read `:seon.agent/ctx`.
It produces a real-but-different 6129-char context.

> These are two genuinely different context philosophies that both shipped. The
> PRD's locked principle (section layout = code) chooses the **composer**. So the
> collapse is: composer wins; the inspector stops calling `assemble-ai-context`.

### The single `assemble-context` (fix IN PLACE — no `-v2`)

Rename `assemble-ctx` → **`assemble-context`** in place (it is reached only by
symbol, so update the default symbol + docstrings in the same patch). Change the
section-list source:

```clojure
(defn assemble-context
  "Compose the LLM context. Section layout is CODE
   (substrate-default-ctx) by default; a stored :seon.agent/ctx, when
   present, overrides it. Pure function of the DB — stores nothing.
   Returns {:seon.render/text \"…\" :seon.server.context/sections [...]
            :seon.render/token-estimate <int>}."
  {:malli/schema [:=> [:cat :seon.server.context/assemble-request]
                       :seon.server.context/assemble-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id] :as input}]
  (let [stored   (sort-by :seon.ctx/priority
                          (:seon.agent/ctx
                            (db/pull {:seon.db/db db
                                      :seon.db/pull-pattern
                                      '[{:seon.agent/ctx
                                         [:seon.ctx/name :seon.ctx/priority :seon.ctx/fn]}]
                                      :seon.db/ref [:seon.agent/id id]})))
        sections (if (seq stored) stored (substrate-default-ctx)) ; CODE DEFAULT
        ctx-in   (assoc input :seon.agent/ctx-entity nil)
        rendered (->> sections
                      (map (fn [section]
                             (let [f (seval/lookup-value (:seon.ctx/fn section))
                                   in (assoc ctx-in :seon.agent/ctx-entity section)]
                               (if f (f in) (pretty-ai section)))))
                      (remove str/blank?))
        text     (str/join "\n\n" rendered)]
    {:seon.render/text            text
     :seon.server.context/sections (mapv :seon.ctx/name sections)
     :seon.render/token-estimate  (quot (count text) 4)}))

```

The only substantive change vs current `assemble-ctx` is the one line
`(if (seq stored) stored (substrate-default-ctx))`. Everything else (per-section
fn resolution, `:seon.agent/ctx-entity` threading, blank-drop, join) is unchanged
— it already works.

**Functions to change/add/delete:**

- **CHANGE in place** `agent.cljs assemble-ctx` → `assemble-context`
  (the section-list fallback above; richer return map). Update the default
  symbol literal `'seon.agent/assemble-ctx` → `'seon.agent/assemble-context` at
  `agent.cljs:634` and the three docstrings (`agent.cljs:35,79,630`).
- **CHANGE** `web/inspector.cljs:114` and `inspect.cljs:53` to call
  `assemble-context` (via the agent's `:seon.render/ai` slot, defaulting to
  `'seon.agent/assemble-context`) instead of `render/assemble-ai-context`.
  Prefer routing both through `render-prompt`-style resolution so a per-agent
  override fn is honored everywhere (one mechanism).
- **DELETE** `render.cljs assemble-ai-context` AND its now-orphaned helpers
  (`renderable-entities`, `sticky?`, `sort-prefix`, `sort-window`, `render-one`,
  `default-window-size`, the `:seon.render/assemble-ai-*` schemas) **iff** no
  other caller survives. Grep before deleting: after switching inspector +
  inspect, the only references are docstrings in `handlers/*.cljs`. `inspect.cljs/
  visible-entities` uses `renderable-entities` for an entity list — decide whether
  the inspector still wants the chronological entity cards (see §7 risk). If the
  two-pane webview's RIGHT pane (html cards) still wants per-entity cards, keep
  `renderable-entities` for the card list but DROP `assemble-ai-context` (text).
- **KEEP** `substrate-default-ctx`, `reset-ctx!`, `update-ctx!`, `ctx-entities`
  unchanged — they are the optional-override mechanism.

**Decision: keep `:seon.agent/ctx` as an OPTIONAL override, do NOT remove it.**
Reasoning: it has live writers (`reset-ctx!`, `update-ctx!`), a reader the agent
is told to use in the system-section cheat-sheet, and the PRD's own
"customization" story (an agent reshapes its layout). Removing it would orphan
three public verbs and a documented agent workflow. The bug is not "stored ctx
exists" — it is "stored ctx is the ONLY source and defaults to empty." Making it
optional (code default when absent) fixes the bug while preserving the override.
Per the strict principle "customization = a written render fn via
`:seon.render/ai`," the cleaner long-term story is per-agent override = a written
`assemble-context`-shaped fn; but the section-list override is a useful middle
ground and removing it is out of scope for this fix. **Recommend: keep stored
ctx as optional override; leave the "override = written fn" path as the
`:seon.render/ai` slot (already supported).**

---

## 4. prompt-text persistence

**Already wired — no new write site needed.** `with-turn!` open-tx stores
`:seon.turn/prompt-text` from the `prompt-text` arg (`agent.cljs:670`), fed by
`run-turn!` from `(render-prompt id)` (`agent.cljs:743 → 764`). The value is
empty ONLY because `render-prompt` returns "". Once `render-prompt` →
`assemble-context` produces the 12.5K default, the SAME unchanged open-tx will
persist it.

Required to make this concrete and correct:

1. **`render-prompt` must produce the composed text.** With `assemble-context`'s
   code-default fallback, `render-prompt` (unchanged) returns the 12.5K string.
   Confirm `ai-render-input` (`agent.cljs:404`) builds the right input map: for
   the default (non-per-agent) symbol it returns `{:seon.db/db db :seon.agent/id
   agent-id}` — exactly what `assemble-context` expects. No change needed there.
2. **Agent-id / tx-context already present.** The open-tx runs inside
   `run-turn!`'s `(db/with-agent id …)` + `(db/with-tx-context {…:turn-id…})`
   scope (`agent.cljs:752-758`), so the prompt-text datom is stamped with the
   agent-id and turn-id tx-meta. Provenance is complete.
3. **Guard:** add an assertion (test, §6b) that after a turn,
   `(:seon.turn/prompt-text turn)` is non-empty and `== (render-prompt id)` for
   the same db snapshot.

> No `prompt-text` write needs to be added or moved. The fix to `render-prompt`'s
> source string is the entire change; the persistence already captures it.

---

## 5. The six section functions

All six are pure `(input) → string` reading DB facts; **none store state.** Verified
by reading `agent.cljs:1068-1267` and running each against the live db:

| Section | fn | Reads | Pure? | Live len |
|---------|-----|-------|-------|----------|
| system | `system-section` 1068 | `current-ns`, clock, tz | yes (string template) | 1098 |
| messages | `messages-section` 1097 | `messages` query; `:seon.agent/n` from ctx-entity (default 50) | yes | 1510 |
| current-ns | `current-ns-section` 1110 | `:seon.ns/source` + reverse-ref `:seon.fn/_ns` `:seon.schema/_ns` | yes | **0** |
| warnings | `warnings-section` 1142 | failed/slow evals, failing tests (datalog, cross-agent) | yes | 77 |
| recent-evals | `recent-evals-section` 1247 | `evals` query; `:seon.agent/n` default 20 | yes | 9790 |
| prompt | `prompt-section` 1260 | `current-ns`, turn count | yes | 38 |

- **No section reads stored ctx as a SOURCE of content.** Three sections
  (`messages`, `recent-evals`) read `:seon.agent/n` *off the ctx-entity* as a
  per-section tunable — that is a legitimate optional knob, not a content
  source, and it survives the refactor (the code-default ctx-entities carry no
  `:seon.agent/n`, so the defaults 50/20 apply). No flag needed.
- **`current-ns-section` = 0 — ROOT CAUSE (not a code bug):** The agent's
  `current-ns` is `:seon.agent.LZL-2605271732`, but **no `:seon.ns` entity exists
  for it.** Live: `all-ns-names` = `[:seon.db :seon.schema :seon.test.runner]`
  (substrate-seeded only). The agent's 7 evals defined nothing in its home ns
  (`n-ok 3, n-fail 4`; the 3 successes were `(println …)` / `current-agent-id`
  probes). detect-and-tee (`eval.cljs:715`) only creates a `:seon.ns`/`:seon.fn`
  entity when a `(ns …)`/`(defn …)`/`(schema/register! …)` form is eval'd
  successfully. So the section correctly renders blank.
  **Action: fix ONLY the stale docstring** (`agent.cljs:1113-1115`) which claims
  "detect-and-tee … hasn't shipped" — it has. New text: "empty until the agent
  successfully evals a `(ns …)` / `(defn …)` / `(schema/register! …)` form in
  this ns; detect-and-tee then records the program-graph entities this pulls."
  No logic change.

(Incidental finding: `:seon.fn/ns` is a **ref** (`{:db/id 102}`), not a keyword.
The section's reverse-ref pull `:seon.fn/_ns` handles this correctly. A naive
`[?f :seon.fn/ns ?ns-kw]` query throws `Cannot compare 103 to :kw` — relevant
only to anyone writing a query against it, not to the section.)

---

## 6. Guard tests to write (the falsification net)

Location: `test/seon/agent_context_test.cljs` (new; CLJS, runs in the pod test
build). Use `db/with-agent` + a seeded in-memory conn fixture (seed an agent +
a session + a few messages + a couple of evals).

**(a) No-stored-ctx agent still gets full default context — THE regression.**
Seed an agent with **no** `:seon.agent/ctx`. Assert
`(count (:seon.render/text (assemble-context {:seon.db/db db :seon.agent/id id})))`
is `> 0` AND `:seon.server.context/sections` equals the six default names. This
is the exact bug that bit us (0 chars on nil ctx).

**(b) agent-path ≡ inspector-path ≡ persisted prompt-text for the same (db,id).**
Run a turn (or call `render-prompt` directly), then assert:
`(render-prompt id)` == `(:seon.render/text (inspector-context id db))` ==
`(:seon.turn/prompt-text <closed-turn>)`. All three resolve to the SAME
`assemble-context` output. (This is impossible to fail once the inspector calls
the composer; the test pins it against future divergence.)

**(c) Each section fn renders non-blank given seeded data.** For each of the six,
seed the minimal facts it reads (messages → seed 1 message; recent-evals → seed
1 eval; current-ns → seed a `:seon.ns` entity with source + one `:seon.fn`;
warnings → seed 1 failed eval; system/prompt → no seed needed) and assert
`(not (str/blank? (section input)))`. This specifically *would have caught* a
future regression where current-ns silently returns "" when data IS present.

**(d) Context non-empty when sections have content.** With messages+evals seeded,
assert the composed `assemble-context` text contains the `<system`, `<messages>`,
and `<recent-evals>` markers (substring checks), proving the composer actually
includes non-blank sections and drops blank ones.

Minimal fixture: one `(with-seeded-agent [id db] …)` macro/helper that transacts
agent + session + N messages + N evals into a fresh `:memory` conn, binds
`db/*conn*`, and yields. Reuse the existing test conn helper if one exists in
`test/seon/`.

---

## 7. Risk / blast-radius note

**This edits the running pod's core loop (`render-prompt` is called every turn
by `run-turn!`).**

- **What could break:**
  - If `assemble-context`'s new return map changes shape, `render-prompt`
    (`(:seon.render/text (render/ai-render sym input))`) still works — it only
    reads `:seon.render/text`. Keep that key. ✅ low risk.
  - `render/ai-render` validates against `:seon.render/ai-response`
    (`render.cljs:140` malli). **Check** that schema still accepts the new return
    map (it requires `:seon.render/text`; extra keys are fine for an open `:map`,
    but verify it's not `:closed`). If closed, widen it. This is the one schema
    to check before reload.
  - The inspector switch: `assemble-ai-context` returned `:seon.render/entities`
    used to build the RIGHT-pane html cards (`inspector.cljs:115-121`). The
    composer does NOT return entities. **Decide:** either (i) keep
    `renderable-entities` to feed the html-card list and only swap the LEFT-pane
    text source to the composer, or (ii) drop the chronological cards. Recommend
    (i) for this patch (smaller blast): left pane = composer text (matches what
    the agent sees), right pane = unchanged entity cards. That makes the panes
    show "what the agent sees" (left) vs "the entities behind it" (right) — a
    reasonable split — without deleting the entity machinery yet.
  - Deleting `assemble-ai-context` + helpers risks an orphan reference in
    `inspect.cljs/visible-entities`. Grep and keep `renderable-entities` if
    `visible-entities` survives.

- **Test before/after:**
  - BEFORE: capture `(render-prompt "LZL-2605271732")` len (0) and the inspector
    len (6129) as the baseline.
  - AFTER reload: `(render-prompt id)` should be ~12.5K; inspector left-pane text
    should EQUAL `(render-prompt id)`; run a real turn and confirm
    `:seon.turn/prompt-text` is now ~12.5K (not 0). Run the four guard tests.

- **Hot-reload safety:** YES, safe. All changes are fn redefs in `agent.cljs`,
  `render.cljs`, `web/inspector.cljs`, `inspect.cljs`. No schema attr changes
  (the schemas for `:seon.agent/ctx` / `:seon.turn/prompt-text` already exist).
  No tx-context or ALS wiring changes. The agent loop picks up the new
  `render-prompt`/`assemble-context` on the next turn with no restart. The one
  thing to verify post-reload is the `:seon.render/ai-response` schema acceptance
  (above) so instrumentation doesn't throw mid-turn.

- **One sequencing caution:** do the `assemble-ctx` → `assemble-context` rename
  AND the default-symbol literal update (`agent.cljs:634`) in the SAME edit. If
  the symbol literal still says `'seon.agent/assemble-ctx` after the fn is
  renamed, `seval/lookup-value` returns nil → `render/ai-render` falls back to
  `default/pretty-ai` → wrong (pretty-printed) context. Atomic rename.

---

## Appendix — verbatim REPL evidence

```clojure
;; Claim 1+2: slot + stored ctx
(let [id "LZL-2605271732" ent (seon.db/entity {:seon.db/ref [:seon.agent/id id]})]
  {:agent-exists? (some? ent) :render-ai-slot (:seon.render/ai ent)
   :ctx-raw (:seon.agent/ctx ent)
   :ctx-entities-count (count (seon.agent/ctx-entities {:seon.agent/id id}))})
;; => {:agent-exists? true, :render-ai-slot nil, :ctx-raw nil, :ctx-entities-count 0}

;; render-prompt + assemble-ctx
;; => {:render-prompt-len 0, :render-prompt-sample "", :assemble-ctx-len 0}

;; Claim 3: per-section lengths
;; => {:system 1098 :messages 1510 :current-ns 0 :warnings 77 :recent-evals 9790 :prompt 38}
;; code-default composed => {:default-composed-len 12521 :resolves-all? true}

;; Claim 4: inspector path
;; => {:assemble-ai-len 6129 :n-entities 13 :token-est 1532
;;     :sample ";; You are a Clojure-fluent agent running inside a CLJS pod on Node.…"}

;; Claim 5: persisted prompt-text per turn
;; => {:turn-count 3 :prompt-text-lengths [{:status :done :pt-len 0}{:status :done :pt-len 0}{:status :done :pt-len 0}]}

;; current-ns root cause
;; current-ns => :seon.agent.LZL-2605271732 ; ns-entity-exists? false
;; all-ns-names => [:seon.db :seon.schema :seon.test.runner]
;; agent evals => {:eval-count 7 :n-ok 3 :n-fail 4 ; all probes/queries, none define in home ns}

```
