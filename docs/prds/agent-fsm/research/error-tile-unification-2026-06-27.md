---
type: research
status: active
tags: [research, web, agent]
---

# Error-tile unification — one overridable error seam

## TL;DR

Seon renders an error tile in **four** hardcoded places (the prompt said three;
it missed one and its line refs are stale — see §1). A downstream consumer can
override only ONE of them today (`seon.render.live-tile/error-response`, which
acme brands), so acme's calm card carries to the legacy live-tile hero but
**no-ops on the new world page** (`/agent/{id}`) — proven by `eb04736b`.

**Core is already mid-fix in its working tree (uncommitted, +80 lines on
`render.cljs`).** Core chose to route `slot` + `render` errors *through the
existing* `live-tile/error-response` via a private `error-tile-hiccup` helper
(render.cljs:679-692) — the right instinct (reuse the one overridable seam, no
new parallel var), but it (a) leaves `render-entity-html`'s catch hardcoded, and
(b) **overloads** `error-response` (a *live-tile*-specific html-response that
builds a "YOUR LIVE TILE IS BROKEN" ai render and takes a wired `::content`
param) as the generic slot/world error source, discarding its ai render and
feeding it a synthetic minimal error.

**Recommended design (B): promote the seam, don't overload `error-response`.**
Introduce ONE pure overridable seam `seon.render.live-tile/error-tile`
(`(fn [:seon/error] → hiccup)`, late-bindable by `set!` exactly like
`error-response` is today). `error-response` *delegates* its hiccup to it; the
four render sites call it directly; acme `set!`s `error-tile` instead of
`error-response`. Net: one error renderer, error-response keeps its single
job (the live-tile html-response), and acme's single override carries to EVERY
surface — entity tiles, world slots, the live-tile hero.

The seam **must** live in `seon.render.live-tile`, NOT `seon.render`: `seon.render`
requires `live-tile`, and `error-response` (in `live-tile`) has to reach the seam
without a back-require cycle. The dependency floor is `live-tile`. (This is the
one place the prompt's proposed `seon.render/error-tile` name is impossible —
grounded in the require graph, render.cljs:36.)

A lower-churn **fallback (A)** that keeps Core's `error-tile-hiccup` is in §6 —
it reaches the same "acme card on every surface" outcome with a ~6-line diff, at
the cost of the `error-response`-overload smell. Both are given.

---

## 1. The error renderers today (ground truth, current working tree)

`git diff` shows `src/seon/render.cljs` is dirty (Core's WIP); `live_tile.cljs`
is clean at HEAD. Line numbers below are the **current on-disk** state.

| # | Site | File:line | Shape | Overridable? |
|---|---|---|---|---|
| 1 | `render-entity-html` catch | `render.cljs:375-381` | hardcoded `[:div …border-error/40…] "⚠ render error"` | **NO** |
| 2 | `render` catch (html view) | `render.cljs:716-720` → `error-tile-hiccup` (WIP) | now routes through `error-response` | yes (via error-response) |
| 3 | `slot` missing/threw | `render.cljs:776,783` → `error-tile-hiccup` (WIP) | now routes through `error-response` | yes (via error-response) |
| 4 | `live-tile/error-response` | `live_tile.cljs:559-591` | the calm card acme overrides | **yes** (acme `set!`s it) |
| — | `error-tile-hiccup` floor | `render.cljs:688-692` (WIP) | last-resort hardcoded div if error-response itself throws | n/a (never-crash floor) |

Prompt-vs-reality corrections (report these to the orchestrator):

- The prompt's item 1 ("`render`'s html-view catch → `[:div …border-error…]
  ⚠ render error`, render.cljs:342-348") is actually **`render-entity-html`'s
  catch**, now at **375-381**. `render`'s OWN html catch is a *separate* site
  (item 2) that the prompt's "three" missed — and it is the one the **world page
  actually hits** for a throwing block (verified: `world-layout` →
  `render/slot` → `render :seon.render/html`; a block fn throw is caught by
  `render`'s inner try at 715, not slot's outer try). Any unification that omits
  `render`'s own catch leaves the world page broken.
- `slot-error-tile` (the old private fn) is **gone** — Core already replaced it
  with `error-tile-hiccup`. The prompt's "render.cljs:692 `slot-error-tile`" is
  stale.

The **ai-view** error renders — `render-entity-ai` catch (`render.cljs` ~540,
`"[render error — sym threw …]"`) and `render`'s ai branch (`717`, `";; ⚠ […]
render failed"`) — are **out of scope** for this seam (the seam is the human
error *tile* = hiccup). They are legible agent-facing lines, not branded cards;
a sibling `error-ai` seam is a sensible later follow-up but not needed for the
override-carries goal. Flagged, not fixed.

### Why acme's override no-ops on the world page (the `eb04736b` proof)

`acme.overrides` does `(set! live-tile/error-response …)`. Before Core's WIP,
sites 2 + 3 were hardcoded divs that never called `error-response`, so on the
world page (`/agent/{id}`, which renders blocks via `render/slot`) a throwing
block produced the stock div, not acme's card. acme.world already installs
`acme.widget/broken-tile` two ways — onto a `:acme-broken` ctx block (the new
slot path) AND onto `:seon.render.live-tile/content` (the error-response path) —
and the commit's own finding records: "the new world/slot error path does NOT
route through the overridable `live-tile/error-response`."

---

## 2. The `:seon/error` shape the seam takes

Per `data-model.md` §6, every error is ONE base `:seon/error` value — shared core
`:seon.error/message` (required) plus optional `:seon.error/where` (the failing
block/route/fn name), `:seon.error/symbol` (the offending fn), `:seon.error/hint`
(the actionable fix), `:seon.error/data` (the malli explain map). No `:kind`
discriminator; the carrier attribute identifies the error (§6.2).

What is **registered today** (not the full §6 yet): `:seon.db/error`
(`db.cljs:143-152`, requires `:seon.error/message`, OPEN map) and
`:seon.render/error` = `:seon.db/error` (`render.cljs:113`). The base `:seon/error`
and the standalone `:seon.error/where|symbol|hint` field schemas are §6 *design*,
not yet registered.

**Implication for the diff (applies cleanly TODAY):** spec the default seam fn's
input as `:seon.db/error` — it is registered, guarantees `:seon.error/message`,
and is an OPEN map, so the extra `:seon.error/where|symbol|hint` keys the call
sites add ride through un-validated (malli open maps don't check undeclared
keys). When §6 lands the base `:seon/error`, widen the input spec to `:seon/error`
(strictly more general; same required field). Do **not** register the §6 field
schemas inside `seon.render`/`live-tile` — wrong owner; that is §6's job.

---

## 3. The seam (recommended design B)

```clojure
;; seon.render.live-tile  (the dependency FLOOR — render.cljs requires this ns)

(defn default-error-tile
  "The core default html render of a :seon/error value — the ONE error
   tile, shared by every surface that surfaces a failure (entity render,
   slot, the live tile). Reads only the shared error core, so it renders
   ANY error. Override the whole look by `set!`-ing `error-tile`."
  {:malli/schema [:=> [:cat :seon.db/error] ::hiccup]}
  [{:seon.error/keys [message where symbol hint]}]
  [:div {:class (str "seon-tile flex flex-col gap-1 p-3 border "
                     "border-error/40 bg-error/10 rounded")}
   [:div {:class "text-xs text-error font-mono font-bold"}
    (str "⚠ " (when where (str (name where) " — ")) "render error")]
   [:div {:class "text-xs font-mono text-text-300 break-all"} message]
   (when symbol [:div {:class "text-[10px] font-mono text-text-500"} (str symbol)])
   (when hint   [:div {:class "text-xs text-text-400 italic"} hint])])

(def error-tile
  "THE one overridable error-tile seam — `(fn [:seon/error] → hiccup)`,
   called by every failure surface (entity render, slot, live tile). A
   consumer `set!`s this var (acme does) and the override carries to EVERY
   surface. Defaults to `default-error-tile`. One error renderer, no forks."
  default-error-tile)
```

**Why a `def`-of-fn var, not a registered block:** errors are TRANSIENT in-memory
`:seon/error` values (data-model §6.1), not installed entities — a block seam
would be the wrong layer. A late-bindable var is exactly the override mechanism
acme already proves on `error-response` (`acme/overrides.cljs`: "`set!` the
callee's global var slot; an EXISTING compiled caller reads that slot at call
time"). `seon.client.extra-core-test` is the standing proof this pattern works.

**Why it lives in `live-tile`, not `seon.render`:** `seon.render` requires
`seon.render.live-tile` (render.cljs:36) and calls `live-tile/error-response`
(render.cljs:478/686). `error-response` lives in `live-tile`; for it to delegate
its hiccup to the seam without a require cycle, the seam must be at-or-below
`live-tile`. The prompt's `seon.render/error-tile` name would force
`error-response` to late-resolve the var by symbol (`eval/lookup-value`) — strictly
worse than a direct ref at the dependency floor. The var name is the only place
I diverge from the prompt; it is forced by the require graph.

**Override model (acme):** `(set! seon.render.live-tile/error-tile branded-fn)`.
One `set!` carries to all four sites because each reads the var at call time.
acme's existing `error-response` override is **deleted** — `error-response` keeps
building the agent-facing `:seon.render/ai` (core), and only its *hiccup* flows
through the now-overridden `error-tile`, so the agent signal is preserved for
free with zero acme code.

### Default-behavior note (intentional)

`error-response`'s current default hiccup is the calm "Updating this tile…"
placeholder. Under design B its hiccup becomes `(error-tile error)` →
`default-error-tile` → the informative "⚠ render error: …" tile. This MATCHES the
target architecture (`ui.md` "Errors render as tiles": *"friendly message, the
offending block/route name and symbol, an actionable hint"*) — the bespoke calm
card was the OLD live-tile-only behavior, and killing it is exactly "the
parallelism dies." Consumers (and seon's own product layer) who want a calm card
`set!` `error-tile` — acme does. `error-response`'s docstring prose ("THE HUMAN
sees a calm placeholder") must be updated to "the unified error tile (or a
consumer's branded override)".

---

## 4. The lane split

| Half | Lives in | What |
|---|---|---|
| **Core (render engine — gated, diff only)** | `src/seon/render/live_tile.cljs`, `src/seon/render.cljs` | the `error-tile` var + `default-error-tile`; `error-response` hiccup → `(error-tile error)`; migrate the 4 render sites to call `live-tile/error-tile`; **delete the WIP `error-tile-hiccup`** |
| **UI / acme (Phase 8)** | `acme/src/acme/overrides.cljs`, `docs/prds/agent-fsm/ui.md` | swap acme's `set!` from `error-response` → `error-tile`; fix the "Total override" table's error row to name `live-tile/error-tile` |

Deviation from the prompt's lane guess: the prompt put "the default error-tile
html render fn" in UI's half. The require cycle forces the seam **and its
default** into `live-tile` (Core's lane) — a UI-ns default would be a forward
reference at `live-tile` load time, and two defaults (minimal core + polished UI)
would reintroduce the very parallelism we are killing. So `default-error-tile`
is Core's; UI's half is the override + the doc-table fix. acme MAY later install a
richer seon-product default through the same `set!` seam, but one default is the
clean state.

---

## 5. The exact diff (recommended — design B)

Apply Core's half FIRST (one atomic patch across the two files — it builds green
on its own and preserves behavior), THEN the UI/acme half.

### 5a. Core — `src/seon/render/live_tile.cljs`

**(i) Add the seam.** Insert in the "Errors are legible" section, immediately
BEFORE `(defn error-response …)` (currently line 559), after the
`;; ====… Errors are legible …` banner. (`::hiccup` is registered above at 307;
`:seon.db/error` comes from `seon.db`, already required.)

```clojure
(defn default-error-tile
  "The core default html render of a :seon/error value — the ONE error
   tile, shared by every surface that surfaces a failure (entity render,
   slot, the live tile). Reads only the shared error core (message +
   optional where/symbol/hint), so it renders ANY error. Override the whole
   look by `set!`-ing [[error-tile]]."
  {:malli/schema [:=> [:cat :seon.db/error] ::hiccup]}
  [{:seon.error/keys [message where symbol hint]}]
  [:div {:class (str "seon-tile flex flex-col gap-1 p-3 border "
                     "border-error/40 bg-error/10 rounded")}
   [:div {:class "text-xs text-error font-mono font-bold"}
    (str "⚠ " (when where (str (name where) " — ")) "render error")]
   [:div {:class "text-xs font-mono text-text-300 break-all"} message]
   (when symbol [:div {:class "text-[10px] font-mono text-text-500"} (str symbol)])
   (when hint   [:div {:class "text-xs text-text-400 italic"} hint])])

(def error-tile
  "THE one overridable error-tile seam — a fn `(fn [:seon/error] → hiccup)`
   every failure surface calls (entity render, slot, the live tile). A
   consumer `set!`s this var to a branded card and the override carries to
   EVERY surface (the same late-binding `set!` pattern acme already uses).
   Defaults to [[default-error-tile]]. One error renderer, no forks."
  default-error-tile)
```

**(ii) Delegate `error-response`'s hiccup to the seam.** In `error-response`
(currently 559-591) replace the inline calm-card hiccup:

OLD (the `:seon.render/hiccup` value):
```clojure
     :seon.render/hiccup
     [:div {:class "seon-tile"}
      [:div {:class "seon-tile-compact flex flex-col gap-1 p-3"}
       [:div {:class "text-sm text-text-200"} "Updating this tile…"]]
      [:div {:class "seon-tile-expanded flex flex-col gap-2 p-4"}
       [:div {:class "text-base text-text-100"} "Updating this tile…"]
       [:div {:class "text-xs text-text-400 italic"}
        "I'm refining what I show here."]]]
```
NEW:
```clojure
     :seon.render/hiccup (error-tile error)
```
(`error` is already destructured: `[{error :seon.db/error wired ::content}]`.)
Also update the docstring line "THE HUMAN sees a calm … placeholder" → "THE HUMAN
sees the unified error tile (`default-error-tile`, or a consumer's branded
override via `error-tile`)".

### 5b. Core — `src/seon/render.cljs`

**(i) Delete the WIP `error-tile-hiccup` helper** (currently 679-692, plus its
`;; ====… The ONE overridable error tile …` banner at 668-677 — replace the
banner's wording or drop it, since the seam now lives in `live-tile`).

**(ii) `render-entity-html` catch** (375-381) — the lone never-overridable site:

OLD:
```clojure
        (catch :default e
          [:div {:class (str "flex flex-col gap-1 p-3 border "
                             "border-error/40 bg-error/10 rounded")}
           [:div {:class "text-xs text-error font-mono font-bold"}
            "⚠ render error"]
           [:div {:class "text-xs font-mono text-text-300 break-all"}
            (str sym " threw: " (or (.-message e) (str e)))]])
```
NEW:
```clojure
        (catch :default e
          (live-tile/error-tile
            {:seon.error/message (str sym " threw: " (or (.-message e) (str e)))
             :seon.error/symbol  sym}))
```

**(iii) `render` catch, html branch** (716-720):

OLD:
```clojure
        (catch :default e
          (if (= view :seon.render/ai)
            (str ";; ⚠ [" (renderable-id node) "] render failed: " (ex-message e))
            (error-tile-hiccup
              (str (renderable-id node) " — " (ex-message e)))))
```
NEW:
```clojure
        (catch :default e
          (if (= view :seon.render/ai)
            (str ";; ⚠ [" (renderable-id node) "] render failed: " (ex-message e))
            (live-tile/error-tile
              {:seon.error/message (str (renderable-id node) " — " (ex-message e))})))
```

**(iv) `slot` — both error paths** (775-784):

OLD:
```clojure
        body  (if (nil? block)
                (error-tile-hiccup
                  (str "no block named " block-name " on "
                       (or id "this agent")
                       " — install! it (or fix the slot name)"))
                (try
                  (render :seon.render/html (assoc ctx :seon.db/db db) block)
                  (catch :default e
                    (error-tile-hiccup
                      (str block-name " render failed: " (err/->message e))))))]
```
NEW:
```clojure
        body  (if (nil? block)
                (live-tile/error-tile
                  {:seon.error/message (str "no block named " block-name " on "
                                            (or id "this agent"))
                   :seon.error/where   block-name
                   :seon.error/hint    "install! it (or fix the slot name)"})
                (try
                  (render :seon.render/html (assoc ctx :seon.db/db db) block)
                  (catch :default e
                    (live-tile/error-tile
                      {:seon.error/message (str block-name " render failed: "
                                                (err/->message e))
                       :seon.error/where   block-name}))))]
```

No new requires: `render.cljs` already aliases `live-tile` (36) and `err` (33).
Calling `live-tile/error-tile` from `render-entity-html` (line ~375, above the
deleted helper) is a cross-ns ref to a fully-loaded ns — no forward-reference
warning, the reason the seam at the dependency floor is cleaner than Core's
in-ns `error-tile-hiccup` (which forced ordering gymnastics for site (ii)).

### 5c. UI / acme — `acme/src/acme/overrides.cljs`

Replace the whole body (drop `orig-error-response` + the `error-response`
override):

OLD:
```clojure
(defonce ^:private orig-error-response live-tile/error-response)

(set! live-tile/error-response
      (fn acme-error-response [req]
        (assoc (orig-error-response req)
               :seon.render/hiccup
               [:div {:class "seon-tile"}
                [:div {:class "seon-tile-compact p-3 text-xs text-text-300 italic"}
                 "Acme is preparing this view…"]])))
```
NEW:
```clojure
(set! live-tile/error-tile
      (fn acme-error-tile [_error]
        [:div {:class "seon-tile"}
         [:div {:class "seon-tile-compact p-3 text-xs text-text-300 italic"}
          "Acme is preparing this view…"]]))
```
Update the ns docstring to note the override now carries to EVERY error surface
(entity tiles, world slots, the live-tile hero), and that the agent-facing
`:seon.render/ai` signal is preserved by core `error-response` (acme no longer
touches it). The override fn carries no `:malli/schema` — same as the proven
`error-response` override (a `set!` replacement is uninstrumented by design).

### 5d. UI — `docs/prds/agent-fsm/ui.md` "Total override" table

The error layer is currently absent from the table (and coordination.md notes the
table "OVERSELLS — fix when #12 lands"). Add the row:

```markdown
| **error tile (any surface)** | `set!` `seon.render.live-tile/error-tile` | seon's `default-error-tile` |
```

---

## 6. Fallback (design A) — keep Core's `error-tile-hiccup`, ~6 lines

If Core prefers minimal churn over the cleaner factoring, the override-carries
goal is reachable by completing Core's existing approach. `error-tile-hiccup`
already routes sites 2 + 3 through `error-response` (acme-overridable). The only
gaps:

1. **Migrate `render-entity-html`'s catch** (render.cljs:375-381) to
   `error-tile-hiccup` (the §5b-ii change, but calling `error-tile-hiccup` instead
   of `live-tile/error-tile`). Because `error-tile-hiccup` is defined LATER in the
   ns (679) than `render-entity-html` (344), add `error-tile-hiccup` to a
   top-of-ns `declare` (there is already `(declare render slot)` at 586 — move it
   up or add a second), OR relocate `error-tile-hiccup` to just after
   `unwrap-response` (332). Relocation is preferred (no forward ref).
2. acme's existing `error-response` override is left UNCHANGED — it already
   carries to all four once site 1 is migrated.

Cost of A vs B: `error-response` stays overloaded (a *live-tile* fn used as the
generic slot/world error source — its ai render discarded, fed a synthetic
`{:seon.error/message …}`), and the seam is a private helper, not a discoverable
public var. B removes both smells for ~3× the diff. Recommend B unless Core's
sprint can't absorb the churn this cycle.

---

## 7. Migration order

1. **Core's atomic patch (§5a + §5b).** One commit across `live_tile.cljs` +
   `render.cljs`. Builds green standalone: `default-error-tile` preserves the
   informative-tile behavior; deleting `error-tile-hiccup` is safe because every
   caller is migrated in the same patch. (Coordinate with Core — `render.cljs` is
   their actively-dirty file; this patch SUPERSEDES their uncommitted
   `error-tile-hiccup` WIP, so it should land as part of the same Core change, not
   on top of it.)
2. **UI/acme override swap (§5c).** After the seam exists. acme's `bin/acme build`
   would fail if it `set!`s `error-tile` before Core defines it — hence order.
3. **Doc-table fix (§5d).**

Interim safety: between step 1 and step 2, acme's OLD `error-response` override
still works (error-response still exists, its hiccup now flows through
`default-error-tile`); acme just hasn't yet extended coverage to slots. No
breakage window.

---

## 8. How to verify (live, server-side)

acme is the harness (pod 7980, wire-REPL 7981, store `data/clusters/acme`).
acme.world already installs `acme.widget/broken-tile` onto the `:acme-broken`
ctx block (the new world/slot path) AND onto `:seon.render.live-tile/content`
(the error-response path).

1. `bin/acme build && bin/acme restart pod` (acme bundle is not watched).
2. For an acme agent id, fetch the world page server-side (a streamed surface
   503s through a browser agent — per `ui.md`, verify over plain HTTP/the wire):
   `curl -s localhost:7980/agent/{id}` (or gunzip the `/feed` stream).
3. **Falsify, don't confirm.** Assert the page CONTAINS `Acme is preparing this
   view…` in the `:acme-broken` slot's `#tile-acme-broken` div, AND does NOT
   contain `⚠ render error` / `border-error/40` / `slot error` anywhere. Before
   the fix the world slot shows the hardcoded `default-error-tile`/`error-tile-
   hiccup` floor; after, it shows acme's branded card — proving the single
   `error-tile` override carries to the NEW world page.
4. Confirm the live-tile hero (`/agent/{id}`, the `:seon.render.live-tile/content`
   path) ALSO shows the acme card (regression check — error-response delegates to
   the same overridden seam), and that the agent's awareness/live-tile context
   block still carries the core `:seon.render/ai` "YOUR LIVE TILE IS BROKEN" line
   (preserved by core `error-response`, not overridden).

Failure mode to watch: if `default-error-tile` is instrumented (`:seon.db/error`
input, message required) and ANY call site forgets `:seon.error/message`,
instrumentation throws *on the error path*. All four sites + `err/->map`
invariantly set `:seon.error/message`, so this can't fire — but it is the one
thing to grep for if a fresh error site is added later.

---

## 9. Pointer to apply to `coordination.md` task #12

(Per the deliverable; not applied here — RULES restrict edits to this research
doc. Paste this under the task #12 recommendation at `coordination.md:128-132`.)

> **DESIGN LANDED →** `research/error-tile-unification-2026-06-27.md`. The "or"
> in this rec is resolved: do NOT add a new `seon.render/error-tile` var (the
> require cycle forbids it — `render` requires `live-tile`, where `error-response`
> lives). Promote Core's WIP `error-tile-hiccup` into ONE public overridable seam
> **`seon.render.live-tile/error-tile`** (`(fn [:seon/error] → hiccup)`, `set!`-
> overridable). `error-response` delegates its hiccup to it; the FOUR render
> sites (note: 4, not 3 — `render-entity-html:375-381`, `render:716-720`,
> `slot:776/783`, `error-response`) call it directly; acme swaps its `set!` from
> `error-response` → `error-tile` (one override, carries to every surface,
> agent ai preserved). Exact diff + a lower-churn fallback in the doc §5/§6. The
> "Total override" table gets the missing error-tile row (§5d). #12 is then
> DONE; unblocks #6.

---

## See also

- `data-model.md` §6 — the base `:seon/error` shape the seam consumes.
- `ui.md` "Total override" / "Errors render as tiles" — the override thesis +
  the target informative-tile default.
- `coordination.md` task #12 — the gap this closes (blocks #6 convergence).
- `eb04736b` — the acme override-proof that surfaced the bypass.
- `acme/src/acme/{overrides,world,widget}.cljs` — the override mechanism + the
  throwing-block test fixtures.
