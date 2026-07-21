---
type: prd
status: draft
tags: [prd, agent, web, architecture]
---

# Context Maps + Override Design — `panel`, `set-panels-provider!`, fixed system-text

## TL;DR

Rename the context unit from `section` to **`panel`** — one DB-derived data map
that is DUAL-RENDERED from a single source of truth into two faces:
`:seon.render/ai` (agent-facing prompt text) and `:seon.render/html` (a live UI
tile in the human dashboard). The map is `:seon.ctx/panel`, the per-agent vector
attr is `:seon.agent/panels`, the default producer is `default-panels`.

Third parties override the DEFAULT SET through ONE blessed boot hook —
`set-panels-provider!` — a `defonce ^:private` atom + installer + guarded read,
byte-for-byte the `seon.schema/set-tee-fn!` idiom. The single seam is the one
call site at `ctx.cljs:1849` inside `context-root`. acme installs its override at
preload in `acme/src/acme/overrides.cljs` (already `:require`d by `acme.pod`) — NO
`seon/` src edit, NO data hack, NO `(defonce orig)` capture-dance. SOME (keep
seon defaults, tweak a few) calls the stable public `(core-panels)` and conj/filters;
ALL returns a literal vector.

`system-text` stays a FIXED code `def` const — the one byte-stable `system` role.
We make "non-overridable" provably true by deleting the single existing runtime
override path (the per-request `:seon.ai/system-prompt` seam used by nothing in
seon/acme). After that, instrumentation rejects the dropped key loudly.

The rename is wide and load-bearing (`ctx.cljs`, `agent.cljs`, `web/tile.cljs`,
`web/inspector.cljs`, `agent/inspect.cljs`, ~8 symbol-fn files, tests, docs). It
must land as ONE atomic patch plus a `bin/seon cluster reset default`, with the
U-lane `web/**` Datalog sites flagged (they fail SILENTLY — empty result, not an
error — if a stored-attr rename is missed).

## The owner's constraints (verbatim)

The recommendation was tested against the owner's exact words. The constraints it
had to satisfy:

- Find a better NAME than `section` — the unit renders into BOTH a prompt block
  (agent-facing text) AND a dashboard tile (html), and `section` only names the
  prose face and undersells the tile twin.
- acme must override the default context set with **no `seon/` src edit** and
  **no data hack**.
- The override must be a **genuine one-liner** for both overriding SOME panels and
  overriding ALL panels.
- It must AVOID the `(defonce orig …)` self-recursion capture-dance that acme
  already does for `error-response` (`overrides.cljs:14`) — that small hack is
  exactly the "shitty hack" the owner forbids.
- `system-text` must be FIXED / non-overridable.
- The UI must stay dynamic-from-data — each panel's html resolves live from the
  program graph; per-agent panels are DB rows.

## Recommended names (+ alternatives)

### Concept: `panel`

A context **panel**: one DB-derived data map that is dual-rendered into two faces
from a single source of truth — `:seon.render/ai` (an agent-facing prompt text
block) AND `:seon.render/html` (a live UI tile that slots into the human
dashboard). `panel` names BOTH faces (a panel of prose in a document AND a
panel/tile in a dashboard), where `section` only named the prose face and
undersold the tile twin. It also dodges two existing collisions:
`:seon.db/component` (datahike component refs) and `seon.render.live-tile` /
`:live-tile` (already the name of the html-side feature), so `panel` is the
correct SUPERordinate term for the container of both renders.

### Map: `:seon.ctx/panel`

Replaces `:seon.ctx/section` (`ctx.cljs:108`); html-twin schema
`:seon.ctx/section-html` → `:seon.ctx/panel-html` (`ctx.cljs:1557`). Shape:

```clojure
{:seon.ctx/name     <kw>
 :seon.ctx/priority <int>
 :seon.render/ai    <symbol|string>
 :seon.render/html  <symbol|hiccup>}   ; optional

```

Registered in `seon.ctx` — the ns whose name the `:seon.ctx/` keyword carries.

### Vector attr: `:seon.agent/panels`

Replaces `:seon.agent/sections` (`agent.cljs:165`) —
`[:vector {:seon.db/component true} :seon.db/ref]` of `:seon.ctx/panel` maps the
AGENT owns, merged over the core set by one `:seon.ctx/priority` sort. Stays in
`:seon.agent/*` because it lives on the agent entity. Plural of the concept →
turtles all the way down: fn (`default-panels`) + map (`:seon.ctx/panel`) + attr
(`:seon.agent/panels`) share one root.

### Alternatives

If the owner dislikes `panel`, the swap is mechanical (`panel` → chosen word) at
every site. Runner-up concept words, in order:

- `card` — `:seon.ctx/card` / `:seon.agent/cards` (reads UI-native, slightly less
  natural for a prompt block).
- `view` — `:seon.ctx/view` / `:seon.agent/views` (reads as the rendered output
  more than the source map, mild ambiguity vs render keys).

For the VECTOR ATTR only, the owner's floated `:seon.agent/ctx` is a one-keyword
alternative — REJECTED as the primary because `ctx` already names the whole
namespace AND the render-input map (`{:seon.db/db :seon.agent/id}`) AND the
`:context` root node, so it would overload one word for three things; but it is a
clean drop-in if the owner prefers it (design is byte-identical, only the keyword
changes). Do NOT reuse `tile` — it is taken by `seon.render.live-tile` for the
html face specifically.

## Override mechanism (the exact code seam)

SEAM = the single call site `ctx.cljs:1849` inside `context-root`. Today it reads
`(gather-sections (core-default-ctx) (agent-sections entity))`. After:
`(gather-panels (default-panels) (agent-panels entity))`.

Split the one producer (`core-default-ctx`, `ctx.cljs:1604`) into THREE pieces in
`seon.ctx` (the active CLJS render runtime; the JVM/Integrant track is paused, so
this is a `defonce`-atom boot seam, NOT an Integrant init-key):

```clojure
;; (1) PUBLIC, stable, NEVER overridden — seon's hardcoded default vector.
;;     Third parties call this to EXTEND from the built-ins.
(defn core-panels
  {:malli/schema [:=> [:cat] [:vector :seon.ctx/panel]]}
  []  <the existing core-default-ctx body, symbol-wired panels>)

;; (2) the override SLOT — holds a (fn [] -> [:vector :seon.ctx/panel]) or nil.
(defonce ^:private !panels-provider (atom nil))

;; (3) the installer — the ONE function a third party 'overrides this function' through.
(defn set-panels-provider!
  "Install a fn returning the full vector of default context panels.
   Call at preload to override SOME or ALL panels; pass nil to restore
   seon defaults. Must never throw."
  {:malli/schema [:=> [:cat fn?] :nil]}
  [f] (reset! !panels-provider f) nil)

;; (4) the SEAM context-root reads — errors-as-values, never breaks assembly.
(defn default-panels
  {:malli/schema [:=> [:cat] [:vector :seon.ctx/panel]]}
  []
  (if-let [f @!panels-provider]
    (try (f)
         (catch :default e
           ;; loud + self-healing: a bad third-party fn can't kill EVERY render
           (conj (core-panels)
                 {:seon.ctx/name :panels-provider-error :seon.ctx/priority 1
                  :seon.render/ai (str ";; panels-provider threw: " (.-message e))})))
    (core-panels)))

```

WHY THIS over a raw `(set! seon.ctx/default-panels …)` (the seam the brief
floated): with one fn, overriding SOME means overriding the same fn you must also
CALL, forcing the `(defonce orig default-panels)` capture-dance acme already does
for `error-response` (`overrides.cljs:14`) — a small hack the owner explicitly
rules out. Splitting `core-panels` (callable, stable) from the seam removes that
dance: SOME just calls `(core-panels)`. It also makes the override DATA in an atom
(not a `set!` of a compiled var), so it survives `:advanced` builds AND the stale
re-export at `agent.cljs:126` stops mattering — late-binding lives in the atom
read, not the fn identity.

CONSISTENCY: this is byte-for-byte the `seon.schema/set-tee-fn!` idiom —
`defonce ^:private !tee-fn (atom nil)` [`schema.cljc:175`] + `set-tee-fn!`
installer [183] + production reads `(when-some [f @!tee-fn] …)` [232] — and the
same shape as `client/!extra-core-vars` [`client.cljs:936`] that `acme.pod`
ALREADY `reset!`s [`pod.cljs:24`]. It is the project's canonical boot-time
third-party supply hook, not a one-off.

REJECTED: candidate-1's `^:dynamic *context-provider*` + `set!`. Its own tradeoffs
admit the earmuffs are a trap here — CLJS dynamic bindings do NOT survive `await`,
and the agent turn is async, so the one feature that would justify earmuffs
(`binding` scoping) is unusable across a turn; reading earmuffs implies
binding-scope to a reviewer while acme uses a one-shot root `set!`. The atom seam
is the same single fn with none of that confusion.

CODE-SMELL FIXED IN THE SAME PATCH: `agent.cljs:126`
`(def core-default-ctx ctx/core-default-ctx)` captures the fn VALUE at load — it
would NOT see an override and defeats late binding. With the atom seam a re-export
is now technically safe, but it is unused → DELETE it.

## acme override snippets

### SOME — keep seon defaults, drop one, add one

```clojure
;; acme/src/acme/overrides.cljs — sibling of the EXISTING (set! live-canvas/error-response …)
;; override. acme.pod already (:require [acme.overrides]) at preload [pod.cljs:18], so this
;; fires BEFORE the first agent turn calls context-root. NO seon/ src edit, NO data hack.
(ns acme.overrides
  (:require [seon.ctx :as ctx]
            [seon.render.live-tile :as live-tile]))

;; SOME — keep ALL seon defaults, drop one core panel by name, add one acme panel.
;; A genuine one-liner: call the stable (ctx/core-panels), no (defonce orig) capture dance.
(ctx/set-panels-provider!
  (fn acme-panels []
    (-> (ctx/core-panels)
        (->> (remove (comp #{:inventory} :seon.ctx/name)))   ; optional: drop a default
        vec
        (conj {:seon.ctx/name     :acme-notes
               :seon.ctx/priority 30
               :seon.render/ai    'acme.widget/notes-ai       ; agent-text face
               :seon.render/html  'acme.widget/notes-html})))) ; live-tile face

;; NOTE: a PURE add (no removal) whose panel names its own render symbols needs NO provider
;; override at all — the symbols resolve late via seon.eval/lookup-value, so acme can instead
;; transact one panel into a specific agent's :seon.agent/panels. set-panels-provider! is for
;; changing the DEFAULT SET that every agent sees.

```

### ALL — replace the entire default set

```clojure
;; acme/src/acme/overrides.cljs — ALL: replace the entire default set, ignore seon's built-ins.
(ctx/set-panels-provider!
  (fn acme-panels []
    [{:seon.ctx/name :acme-soul :seon.ctx/priority 5
      :seon.render/ai 'acme.widget/soul-ai}
     {:seon.ctx/name :acme-body :seon.ctx/priority 20
      :seon.render/ai   'acme.widget/body-ai
      :seon.render/html 'acme.widget/body-html}
     ;; keep seon's transcript if wanted — just name its symbol; no fork:
     {:seon.ctx/name :transcript :seon.ctx/priority 100
      :seon.render/ai   'seon.ctx.transcript/transcript-panel
      :seon.render/html 'seon.ctx.transcript/transcript-panel-html}]))

;; Verify: bin/acme build && bin/acme restart pod, then on wire-REPL 7981 / HTTP 7980 confirm
;; the first render shows acme's panels (agent-text in the prompt + live tiles) and NOT the
;; dropped/omitted core panels.

```

## Fixed system-text handling

`seon.ctx/system-text` STAYS a plain code `def` const string
(`ctx.cljs:879–1112`) — the immutable stage, the one byte-stable LLM `system` role
for every agent and turn. It is NOT a panel, NOT in the `default-panels` vector,
NOT a hook var, NOT routed through `!panels-provider`.

To make "fixed / non-overridable" PROVABLY TRUE, remove the ONE existing runtime
override path (verified present today) in the same patch:

1. `ai.cljs:368–369` `effective-system-prompt` — change
   `(or system-prompt ctx/system-text)` → `ctx/system-text` UNCONDITIONALLY.
2. `ai.cljs:341` — drop `[::system-prompt {:optional true} ::system-prompt]` from
   `::prompt-request`.
3. `ai.cljs:358` — drop the same key from `::debug-prompt-request`
   (`debug-full-prompt` then uses `system-text` directly).
4. Drop `:seon.ai/system-prompt` (+ the "overrides the store-resident soul"
   docstrings) from both adapter request schemas: `openai_compat.cljs:68` & `327`,
   `anthropic.cljs:68` & `306`.

After this, `system-text` is reachable only as source. A CLJS `def` is technically
`set!`-able, but with the request key gone NOTHING in seon or acme can install an
override, and instrumentation now REJECTS a passed `:seon.ai/system-prompt` loudly
(unschema'd key) rather than silently honoring it. Clean split: system role =
FIXED code const; context panels = the one overridable vector (via
`set-panels-provider!`). This is a real, intended capability cut — the per-request
system override was a declared adapter seam used by nothing in seon/acme today.

## Migration plan (ordered)

1. `ctx.cljs` — schema renames (one atomic patch): `:seon.ctx/section` →
   `:seon.ctx/panel` (register 108; declare 79; docstrings 12,42,53,121,227,248,873)
   and `:seon.ctx/section-html` → `:seon.ctx/panel-html` (register 1557). The
   per-node render-INPUT key `:seon.ctx/section` (handed to slot fns, read at
   `ctx/warnings.cljs:21` `(:seon.ctx/section input)`) renames in lockstep →
   `:seon.ctx/panel`; grep `:seon.ctx/section` to catch every injection + read.
2. `ctx.cljs` — split the producer: `core-default-ctx` (1604) → PUBLIC
   `core-panels` (tighten `:malli` return to `[:vector :seon.ctx/panel]`). ADD
   `(defonce ^:private !panels-provider (atom nil))`, `set-panels-provider!`
   installer, and the guarded seam `default-panels` (try/catch → `core-panels` +
   loud `:panels-provider-error` panel). Update the symbol-wiring body +
   forward-declare 79.
3. `ctx.cljs` — flip the ONE behavioral seam at `context-root:1849` from
   `(gather-sections (core-default-ctx) (agent-sections entity))` to
   `(gather-panels (default-panels) (agent-panels entity))`. Rename helpers in
   lockstep: `gather-sections`→`gather-panels` (1746), `agent-sections`→
   `agent-panels` (1723, now reads `:seon.agent/panels`), `decode-section`→
   `decode-panel` (79,~1715), `section-bracket-ai`→`panel-bracket-ai` (1761),
   `rendered-section-texts`→`rendered-panel-texts` (1902; uses 1924,1975),
   `agent-section-char-budget`→`agent-panel-char-budget` (1701; uses 1781,1791,1806),
   `file-section(/-ai/-html)`→`file-panel(/-ai/-html)` (199,209,217). Fix the
   budget-marker text 1805–1809 ("agent sections" → "panels"; hint →
   `(seon.agent/add-panel! …)`). Update the pull at `ctx.cljs:864` & `1827`
   `{:seon.agent/sections [*]}` → `:seon.agent/panels`.
4. `agent.cljs` — DELETE the stale load-capture alias
   `(def core-default-ctx ctx/core-default-ctx)` (126). Rename the STORED attr
   `:seon.agent/sections` → `:seon.agent/panels` (register 165; tx/pull/retract
   535,549,551,647,649,682,685; docstrings 26,27,51,52,57,508,516,525,528,535,555,597).
   Rename agent verbs for consistency: `add-section!`→`add-panel!`,
   `remove-section!`→`remove-panel!`, `update-ctx!`/`reset-ctx!`→`update-panels!`/`reset-panels!`;
   schemas `::add-section-request`/`::remove-section-request`/`::section-response` →
   `::add-panel-request`/…; `default-section-priority`→`default-panel-priority`.
5. RENDER-OUTPUT keys (U-LANE WEB cross-cut — must move together; these read
   per-panel html directly, not via render-context): `:seon.render/section-html` →
   `:seon.render/panel-html` and `:seon.render/section-texts` →
   `:seon.render/panel-texts`. Sites: `ctx.cljs:1945–1984`;
   `agent/inspect.cljs:48,71,95,108` (+ `ctx-sections`→`ctx-panels` there);
   `web/tile.cljs:1001,1010,1040,1104,1406,1444`; `web/inspector.cljs:14,165`
   (comment "Mirrors core-default-ctx"→"core-panels"),268,279 +
   `stable-section-names`/`stable-section?`/`expand-namespaces-section` helpers
   (161–202).
6. U-LANE WEB tiles that DATALOG-query the attr (fail SILENTLY — empty result, not
   error — if missed; grep-verify zero remaining `:seon.agent/sections` before
   reset): `web/tile.cljs` `context-view` (548) + `narration-view` (579,590)
   `[?e :seon.agent/sections ?s]` → `:seon.agent/panels`; human copy +
   `:seon.ui/expects` strings (536,571,577,579,681,682,684,685);
   `decode-section-text`→`decode-panel-text` (521).
7. system-text lockdown (per Fixed system-text handling): `ai.cljs`
   `effective-system-prompt` unconditional (369), drop `::system-prompt` from
   `::prompt-request` (341) + `::debug-prompt-request` (358); drop
   `:seon.ai/system-prompt` + docstrings from `openai_compat.cljs:68,327` and
   `anthropic.cljs:68,306`.
8. Symbol-fn sweep (mechanical, its OWN commit after 1–7 are green): rename the
   per-panel symbol fns still carrying `-section` and the "Symbol-wired into
   core-default-ctx" docstrings — `namespaces-section`, `transcript-section(-html)`,
   `warnings-section`, `live-tile-section`, `inventory-section`,
   `open-todos-section`, `file-section` → `*-panel`; sites `my/kb/shared.cljs:97`,
   `ctx/namespaces.cljs:26`, `ctx/warnings.cljs:4`, `ctx/live_tile.cljs:4`,
   `ctx/inventory.cljs:6`, `ctx/transcript.cljs:21`, `agent/todo/internal.cljs:94`.
   Re-point the symbol wiring in `core-panels`.
9. Tests — UPDATE (don't delete) rename-broken assertions: `ctx_test.cljs`
   (`agent-panel-char-budget`, `file-panel`, `:seon.agent/panels` pull
   ~610,705,708); fix symbols in `agent_context_test.cljs.disabled:1306` + gym edn
   comment so a future re-enable works. Assert MECHANISM (panel appears/vanishes,
   override flows through `context-root`, byte-stable prefix) NEVER exact bracket
   strings. Reuse the proven `extra_core_test` late-binding pattern to cover
   `set-panels-provider!` → `context-root`.
10. acme test-bed: add the SOME and ALL `set-panels-provider!` calls to
    `acme/src/acme/overrides.cljs` (already preloaded by `acme.pod`).
    `bin/acme build && bin/acme restart pod`; verify on wire-REPL 7981 / HTTP 7980
    that the first render shows the acme panel in BOTH the agent prompt (text twin)
    and the live tile (html twin), and that no override path exists for system-text.
11. Fresh world: `bin/seon cluster reset default`. `:seon.agent/panels` is a NEW
    attribute name; old `:seon.agent/sections` datoms are orphaned — NO porting
    (house rule), agent-pinned panels are wiped (acceptable on this feature branch;
    flag before running on a precious store). Run `bin/test-cljs` ONCE at the end.
    Update `docs/prds/agent-fsm/context-render.md` + acme-harness note to the panel
    vocabulary + the `set-panels-provider!` recipe.

## Why this wins

Tested against the owner's exact words.

1. Names clearly beat `section`: `panel` names BOTH render faces (prompt block +
   dashboard tile) where `section` named only the prose; concept/map/attr are
   unambiguous and share one root (`default-panels` / `:seon.ctx/panel` /
   `:seon.agent/panels`).
2. acme override REALLY needs no `seon` src edit + no hack — the seam is the
   existing single call site `ctx.cljs:1849`, the override goes in
   `acme/src/acme/overrides.cljs` which `acme.pod` already requires at preload,
   exactly where acme already does `(reset! client/!extra-core-vars …)` and
   `(set! live-canvas/error-response …)`.
3. The override IS a genuine one-liner for both SOME (call the stable public
   `core-panels` and conj) and ALL (return a literal vector) — and crucially
   AVOIDS the `(defonce orig)` self-recursion capture-dance that a raw
   `set! default-panels` would force, which is precisely the "shitty hack" the
   owner forbids.
4. It is the project's blessed idiom, not a one-off: `defonce ^:private` atom +
   `set-*-fn!` installer + production reads `(or @atom default)` is verbatim
   `seon.schema/set-tee-fn!`, and the preload `reset!` is verbatim
   `client/!extra-core-vars` — both verified in source.
5. errors-as-values: a throwing provider falls back to `core-panels` + a loud
   visible error panel, matching `set-tee-fn!`'s never-throw contract.
6. system-text is provably FIXED — after removing the one verified override path
   (`ai.cljs:369` + four schema sites) there is NO supported runtime seam and
   instrumentation rejects the dropped key loudly.
7. The UI stays dynamic-from-data: each panel's `:seon.render/html` symbol resolves
   live from the program graph, per-agent panels are `:seon.agent/panels` DB rows.

## Risks

1. LATE-BINDING SCOPE: the atom seam is proven at `:none`/`:dev`/`:simple`
   (`extra_core_test` + acme.pod's live `!extra-core-vars` `reset!`); the pod and
   `:acme-client` both build `:dev` so `default-panels` reads the slot at call time
   and the override flows through. `:advanced` DCE is out of scope — same caveat as
   every existing seon hook, but the atom form survives `:advanced` where a raw
   `set!`-the-fn would NOT.
2. SINGLE-PROVIDER, LAST-WRITER-WINS: one `!panels-provider` slot — two third
   parties calling `set-panels-provider!` in one pod clobber (no merge), exactly
   like `!extra-core-vars`-as-a-single-`set!`. Fine for one-consumer-per-pod (acme
   is its own isolated cluster); if multi-tenant panel composition is ever needed,
   an additive `(defonce !extra-panels (atom []))` concatenated onto `core-panels`
   composes — but that is a different (additive) contract than "override the
   default SET" and not what was asked.
3. WHOLE-VECTOR CONTRACT: to change ONE core panel the provider must
   `(core-panels)`+conj/filter — a touch of ceremony, the deliberate price of total
   control + no hidden merge. A pure ADD needs no provider at all (symbol-wire a
   panel + agent `add-panel!`).
4. WIDE ATOMIC RENAME: `section` is load-bearing across `ctx.cljs`, `agent.cljs`,
   `web/tile.cljs`, `web/inspector.cljs`, `agent/inspect.cljs`, ~8 symbol-fn files,
   tests + docs. Must land as ONE patch + cluster reset — a half-done rename leaves
   `:seon.agent/sections` datoms unreadable by `:seon.agent/panels` queries, and the
   U-LANE web DATALOG queries (`tile.cljs:548,579,590`) fail SILENTLY (empty result,
   not an error) if missed → grep-verify zero remaining `:seon.agent/sections` and
   `:seon.ctx/section` before the reset; coordinate the U-lane edits since `web/**`
   is a separate lane.
5. STORED-ATTR RENAME COST: a live store's agent-pinned panels go invisible until
   re-pinned — accepted via reset (no data porting), but flag before running on a
   precious cluster.
6. SYSTEM-TEXT REMOVAL is a real capability cut (drops the per-request system
   override) — intended per owner directive; downstream code passing
   `:seon.ai/system-prompt` now hits a loud instrumentation rejection (key removed)
   rather than silent ignore, which is the correct fail-loud behavior.
7. SCOPE BOUNDARY: selecting the DEFAULT SET is a compiled-fn override, not a DB
   row — panel CONTENTS are data-driven (html symbol resolves live; per-agent
   panels are rows), but if the owner later wants the default-set membership itself
   transactable, that needs a data-seeded default (env-owns-the-row pattern), a
   different seam. Honest boundary, not a blocker for this request.
