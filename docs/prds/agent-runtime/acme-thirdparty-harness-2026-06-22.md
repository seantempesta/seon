---
type: prd
status: draft
tags: [prd, agent, flow]
---

# Acme third-party harness — root-cause two bugs + a port-isolated reproduction

## TL;DR

Two bugs a third-party consumer ("Acme") reports were SUPPOSEDLY fixed. Live
read-only probing of this machine's downstream pod (90 `:seon.ns` rows;
`acme.widget` indexed; `@seon.client/!extra-core-vars` empty) settled both:

- **BUG A — "live tile rendering via SCI isn't working."** The originally
  proposed root cause (a third-party tile fn's required nses are absent from
  `globalThis`, so `seon.eval/lookup-value` returns nil and SCI throws
  "Unable to resolve symbol") **does NOT hold in steady state** — I verified
  `(js/goog.getObjectByName "acme.widget")` ⇒ **true** and
  `(seon.eval/lookup-value 'acme.widget/set-location!)` ⇒ **true** right now;
  boot replay landed it. The actual third-party-fragile seam is the
  **cljs.js boot-replay path choking on `:refer :all`** (live `replay-n-fail`
  is `:my.refertest` — `:all is not ISeqable`): an Acme ns that uses
  `:refer :all` is silently dropped from `globalThis` at boot, and ONLY THEN
  does the proposed symptom (pending-html "Preparing this view…") appear via
  the `globalThis`-miss path. Plus a confirmed-but-cosmetic `:seon.ns/source`
  **double-store** (reconstitute duplicates member defns). Severity: **low**
  for the demo path (literal hiccup + correctly-`:require`-d symbol tiles
  render correctly today), **medium** for any consumer using `:refer :all`.

- **BUG B — "third-party source isn't loaded into context (maybe not even
  indexed)."** **Root cause HOLDS.** A third party's OWN pre-existing source
  files are boot-indexed ONLY via the `SEON_EXTRA_SRC` path, whose sole hook
  is the consumer's preload ns running `(reset! seon.client/!extra-core-vars
  …)`. `!extra-core-vars` is a `(defonce … (atom []))` (client.cljs:912) that
  **nothing in seon core ever populates**. Live: `@!extra-core-vars` count 0,
  `(core-ns-set)` excludes `:acme.widget`, env `SEON_EXTRA_SRC`/`PRELOAD`
  unset — so Acme's own source is invisible to indexing → context → embedding
  retrieval, with **zero error**. The lone `:acme.widget` row in the store is
  an agent-eval TEE artifact, not boot-indexed own source. Severity: **high**
  (silent total invisibility of a consumer's product source). The proposed
  seon-side auto-register helper is **fatally flawed** (macro closure-direction
  — see §1) and is corrected below to a loud boot WARN + a sugar macro the
  consumer invokes.

This doc specs a **port-isolated Acme harness** (SEON_PORT=7990 /
WRITER_REPL=7991 / its own cluster dir + sockets, `SEON_EMBED=1`) that boots
alongside the live default cluster (7890/7891/`data/clusters/default`) with
zero overlap, plus falsification acceptance checks mapping one-to-one to each
bug. Sibling design doc (artifact packaging / extension-without-fork strategy):
`docs/prds/agent-runtime/seon-as-artifact-design-2026-06-22.md` — referenced,
not duplicated. Path A vs Path B mechanics:
`docs/seon/components/extra-src.md`.

---

## 1. The two bugs — verified root causes

### Method / live evidence (read-only)

Probed the LIVE downstream pod via `mcp__seon_cljs__eval` against
`@seon.db/*conn*`. **No suite run, no transact, no restart.** Key reads:

```clojure
{:ns-count 90
 :acme-on-globalthis true            ; (js/goog.getObjectByName "acme.widget")
 :acme-lookup true                   ; (lookup-value 'acme.widget/set-location!)
 :core-lookup true                   ; (lookup-value 'seon.render.chat/last-reply)
 :extra-core-vars-count 0            ; @seon.client/!extra-core-vars
 :acme-in-core-ns-set false          ; (contains? (core-ns-set) :acme.widget)
 :acme-source "(ns acme.widget)\n(defn set-location! [loc] loc)"
 :env {:extra-src false :extra-preload false}}
```

```clojure
{:agent-ns-count 17
 :agent-nses-NOT-on-globalthis
   [:seon.handler.match :seon.agent.turn :seon.fn :seon.effect
    :seon.error.malli :seon.test.probe :seon.test.fin :seon.flow
    :seon.user :seon.ns :seon.test.reliability :seon.agent.session
    :my.agent.gwM-2606211132]                ; acme.widget is NOT here → it IS loaded
 :acme-reconstituted
   "(ns acme.widget)\n(defn set-location! [loc] loc)\n\n(defn set-location! [loc] loc)"
 :setlocation-occurrences 2}          ; the defn appears TWICE → double-store CONFIRMED
```

Interpretation: `acme.widget` IS resolvable today (steady state) — the
original BUG-A globalThis-miss hypothesis is **falsified** for the wired path.
The `NOT-on-globalthis` list is core stub-nses (sourceless upserts that
reconstitute to empty — harmless) plus the agent-state ns; acme is absent from
it. The reconstituted acme source duplicates `set-location!` — the double-store
is real. `@!extra-core-vars` is empty and `:acme.widget ∉ core-ns-set` — BUG B
confirmed.

### BUG A — SCI live-tile rendering (CORRECTED root cause)

**Entry / wired path** (all verified by reading source):

- `seon.render/render-agent-tile` — `src/seon/render.cljs:377-466`. Pulls the
  agent entity, resolves the wired value via `live-canvas/wired-content`
  (render.cljs:401-405), and for an agent-authored symbol
  (`render-sci/agent-authored-sym?` true) calls `render-sci/invoke-bounded`
  (render.cljs:413-428).
- `render-sci/invoke-bounded` — `src/seon/render/sci.cljs:283-375`. Reads the
  tile fn's `:seon.fn/source`, reads the agent ns `:seon.ns/source`, parses
  `:require` `:as`/`:refer`/`:refer :all` (sci.cljs:319-322 via `ns-requires`
  at sci.cljs:225-250), `expose-ns`'s each required/own ns
  (sci.cljs:324-345), builds a fresh SCI ctx and `eval-string*`'s the source
  (sci.cljs:348-358). On any non-interrupt throw → `warn-fallback-once!` +
  `{:seon.render.sci/fallthrough true}` (sci.cljs:365-371).
- `expose-ns` — `src/seon/render/sci.cljs:252-277`. Enumerates a ns's members
  from the `:seon.fn` index and resolves EACH via
  `seon.eval/lookup-value` (sci.cljs:271).
- `seon.eval/lookup-value` — `src/seon/eval.cljs:288-322`. Resolves ONLY by
  walking `js/globalThis` at `cljs.core/munge`'d paths (eval.cljs:315-322).
  **A ns indexed in the DB but absent from `globalThis` returns nil here.**
- Fallthrough lands on the compiled path `html-render value input`
  (render.cljs:426-427). `html-render` — `src/seon/render.cljs:167-183` —
  also does `(eval/lookup-value slot)` (render.cljs:175); nil ⇒
  `(default/pending-html slot)` (render.cljs:177) — the
  "Preparing this view…/isn't loaded yet" card.

**Why the original root cause is FALSIFIED (live):** In steady state the
required nses ARE on `globalThis` (verified: `acme.widget` true; `seon.db`'s
14 fns resolve; core sections resolve). Boot `replay-program-graph!`
(client.cljs:706-790) DID land `acme.widget`. So `lookup-value`/`expose-ns`
succeed and SCI renders the real tile. The pending-html the prior agent saw
came from a **hand-run probe** — calling `html-render`/`lookup-value` on
`'acme.widget/set-location!` BEFORE on-demand-loading it, and from an Acme tile
whose `(ns acme.widget)` form declared **no `:require`** while the body used a
`db/` alias (a USER ERROR identical on the compiled path, not a Seon defect:
`ns-requires` returns empty aliases ⇒ SCI correctly fails `db`).

**The ACTUAL third-party-fragile seam:** the **cljs.js boot-replay path fails
on `:refer :all`.** Live: the single boot `replay-n-fail` is `:my.refertest`
with `:all is not ISeqable`. `replay-program-graph!` topo-sorts agent-ns-set,
`reconstitute-ns-source` (eval.cljs:437-484) emits the stored `(ns … (:require
… :refer :all))` form verbatim, and `seval/eval` through `cljs.js`'s load-fn
chokes on `:refer :all`. The per-ns try/catch (client.cljs:758-769) swallows it
into a `:seon.log` :warn and continues — so that ns is **silently absent from
`globalThis` after every boot**. An Acme ns that uses `:refer :all` (or
requires one) THEN hits the proposed symptom via the genuine globalThis-miss
path. This is third-party-specific because seon core tile fns
(`seon.render.live-canvas/welcome`, `seon.render.chat/last-reply`) are COMPILED
into the bundle and are on `globalThis` from module-load regardless of replay.

**Confirmed cosmetic defect — `:seon.ns/source` double-store:**
`build-tee-entities` (`src/seon/eval.cljs:1487-1490`) stores the WHOLE eval
string as `:seon.ns/source` (`ns-sym (ns-form-name source)` but
`:seon.ns/source source` — the entire multi-form string, not just the
`(ns …)` form). `reconstitute-ns-source` (eval.cljs:437-484) then concatenates
that PLUS the separate `:seon.fn/source` rows ⇒ member defns appear twice
(verified: `set-location!` ×2). Harmless for a single-defn ns (re-`defn`
shadows), but a latent corruption the moment a ns body interleaves
`(import …)`/`(require …)` forms.

**Confirmed cosmetic defect — agent-ns-set pollution:** sourceless core
stub-nses (`:seon.fn :seon.flow :seon.user :seon.ns :seon.effect
:seon.error.malli …`) carry a `:seon.ns/name` upsert but no
`:seon.ns/source` and are NOT in `core-ns-set`, so `agent-ns-set`
(client.cljs:576-585) includes them and `replay` iterates them. Harmless
(they reconstitute to empty), but wasteful.

**Severity: low** (demo path) / **medium** (`:refer :all` consumers).

**Minimal fix** (read the disease, not the prior agent's symptom):

1. **Fix the `:refer :all` boot-replay failure** — the ACTUAL seam. Either (a)
   make the `cljs.js` reconstitute/load path in
   `seon.eval/reconstitute-ns-source` (+ the load-fn `guarded-load` DB branch)
   handle `:refer :all`, or (b) reject `:refer :all` LOUDLY at tee time
   (`build-tee-entities`, eval.cljs:~1487) with a legible agent-facing error so
   a third-party ns using it is never silently dropped. Files:
   `src/seon/eval.cljs`, `src/seon/client.cljs`.
2. **Fix the `:seon.ns/source` double-store** —
   `build-tee-entities` (eval.cljs:1487-1490) should store ONLY the `(ns …)`
   form text for `:seon.ns/source` (`(pr-str <the read ns form>)`), not the
   whole eval string, so `reconstitute-ns-source` stops duplicating members.
   File: `src/seon/eval.cljs`.
3. **(Defense-in-depth, NOT load-bearing)** In `expose-ns`/`invoke-bounded`
   (sci.cljs:252-277,324-345), when an indexed-but-unloaded required ns is
   encountered, trigger `reconstitute-ns-source` + `seval/eval` before
   `lookup-value` — closes a same-turn race (a tile wired to a symbol in the
   SAME turn its ns is first eval'd, before replay lands it). File:
   `src/seon/render/sci.cljs`.
4. **(Non-blocking)** Exclude sourceless stub-nses from `agent-ns-set` so
   replay stops iterating core nses (client.cljs:576-585 / core-ns-set
   976-995). File: `src/seon/client.cljs`.

**Confidence: high.** Live-verified acme IS on globalThis + resolvable, the
real `replay-n-fail` is the `:refer :all` ns, and the double-store reproduces.
**Still UNVERIFIED:** the exact `cljs.js` failure mode for `:refer :all`
through the load-fn (the `:all is not ISeqable` site) — settle by reading
`logs/pod.log` for the `load of ns :my.refertest failed` warn and tracing the
`reconstitute → eval` call for that ns. Do NOT ship fixes #3/#1 as a "demo
unblock": the demo path is not broken today.

### BUG B — third-party source indexing → context → retrieval (root cause HOLDS)

**Boot indexing path of a third party's OWN source** (all verified):

- `bin/seon` injects `SEON_EXTRA_SRC` as a `:local/root` dep
  (`extra_src_sdeps`, bin/seon:141-145) and `SEON_EXTRA_PRELOAD` onto
  `:devtools :preloads` (`extra_preload_merge`, bin/seon:147-151).
- The downstream preload ns is SUPPOSED to run, at load time,
  `(reset! seon.client/!extra-core-vars (filterv … (seon.indexing/specced-fn-vars)))`
  — documented ONLY in a code comment (`src/seon/client.cljs:899-912`) and
  `docs/seon/components/extra-src.md`.
- Boot: `seed-or-resume!` → `core-index-tx` → `index-core!`
  (`src/seon/client.cljs:1269`+) calls `extra-core-vars*`
  (client.cljs:914-926), which reads `@!extra-core-vars`, builds
  `:seon.fn` rows + FULL-SOURCE `:seon.ns` rows for the downstream nses, and
  `core-ns-set` (client.cljs:974-995) joins them via `@!extra-core-vars`.

**THE BREAK:** `!extra-core-vars` is `(defonce … (atom []))`
(`src/seon/client.cljs:912`) that **nothing in seon core populates**. If the
consumer's preload omits the `reset!` (or no extra-src is used), the atom stays
`[]`, `extra-core-vars*` returns `()`, ZERO downstream rows are boot-indexed,
and the ns is absent from `core-ns-set`. **Live proof:** `@!extra-core-vars` =
0, `(core-ns-set)` excludes `:acme.widget`, `SEON_EXTRA_SRC`/`PRELOAD` unset.

**Why third-party-specific (structural):** seon core is indexed by
CONSTRUCTION — `core-vars` (client.cljs:876-890) =
`curated-core-vars` PLUS `(specced-fn-vars)` (the macro, indexing.clj:86-104)
expanded INSIDE `seon.client`, whose transitive require closure IS the whole
`seon.*` build. A third party's nses are STRUCTURALLY outside that closure
(`acme` requires `seon`, never the reverse), so the compile-time roster can
never enumerate them. The ONLY hook is `!extra-core-vars` — and seon ships no
code that fires it. (The agent-eval TEE — `build-tee-entities` — DOES index any
ns an agent eval's at runtime, which is why `:acme.widget/set-location!` exists
in the store at all; but that is AGENT-AUTHORED code captured by the tee, landed
non-full-source and outside `core-ns-set`, NOT the third party's pre-existing
own source files.)

**Downstream consumers are unaffected:** ctx needs NO change —
`seon.ctx/included-ns?` (`src/seon/ctx.cljs:184-195`) renders EVERY indexed
`:seon.ns` row with no prefix filter (the library gate is on the INDEX side via
`indexing/first-party-file?`), and `seon.ctx.relevant/relevant-source-section`
renders any KNN hit generically. Both are strictly downstream of indexing.
Embedding retrieval likewise: the pod's `seon.embed` is QUERY-ONLY
(`src/seon/embed.cljs:1-38` — the pod carries no Proximum/Gemini; KNN lives on
the JVM wire-server `seon.embed.clj`). Once acme rows are boot-indexed,
`:seon.fn/source` is embeddable by default, and newly-indexed rows post-date
the standing registration so no backfill is needed for them.

**Severity: high.** A consumer's ENTIRE product source is silently invisible to
the agent (no context, no retrieval) with zero error; the only signal is a
buried code comment. Workaround exists (the `reset!`), so it is not a hard
blocker — but the SILENCE is the high-severity defect.

**Minimal fix** (corrects the originally proposed fix, which was fatally
flawed):

- **DO NOT** add a seon-side `seon.dev.extra-preload` helper that calls
  `(specced-fn-vars)` to enumerate acme vars. **FATAL FLAW:**
  `specced-fn-vars` is a MACRO that expands against the CALLING ns's require
  closure (`indexing.clj:92-93`, `(-> &env :ns :name)`). A SEON ns's closure
  can NEVER include `acme.*`. "Requiring the helper from acme.pod" does NOT
  relocate where the macro expands — it still expands at the helper's site with
  the helper's closure ⇒ zero acme vars. The macro MUST expand in the
  CONSUMER's own entry ns.
- **The real fix is observability + ergonomics, in seon core:**
  1. **Loud boot WARN/section** in `index-core!` (client.cljs:1269+): when
     `SEON_EXTRA_SRC` is non-empty at index time AND `extra-core-vars*` is
     empty, emit a specific, actionable message:
     `"SEON_EXTRA_SRC=<x> set but no extra vars registered — your
     SEON_EXTRA_PRELOAD entry ns must (reset! seon.client/!extra-core-vars
     (filterv #(str/starts-with? (str (:ns (meta %))) \"<prefix>.\")
     (specced-fn-vars))) with (:require-macros [seon.indexing :refer
     [specced-fn-vars]])"`. File: `src/seon/client.cljs`.
  2. **Optional sugar macro** `seon.indexing/extra-core-vars-for` (the
     `filterv` + `specced-fn-vars` one-liner) so the consumer writes one line —
     but it STILL must be invoked from `acme.pod`, not seon. File:
     `src/seon/indexing.clj`.
  3. **Document** the closure-direction requirement and the
     `(:require-macros [seon.indexing :refer [specced-fn-vars]])` in
     `docs/seon/components/extra-src.md`.
- **ctx: NO change.** Confirmed — `included-ns?`/`namespaces-section` have no
  prefix filter; once indexed, acme renders like any ns.
- **Embedding:** the one real gap to VERIFY (not assume) is whether the
  wire-server `register-embeddable!` BACKFILLS rows that pre-date the
  registration (memory P2 "backfill rows that pre-date the embeddable attr").
  Newly-boot-indexed acme rows are fine; pre-existing tee rows may need a
  backfill. File: JVM `src/seon/embed.clj` (NOT the pod's `embed.cljs`).

**Confidence: high.** Static path (client.cljs:912/1269, indexing.clj:92-93,
ctx.cljs:184-195) and live read agree. **Still UNVERIFIED:** the Acme repro on
a CLEAN store (the live machine already has a tee row) — settle by a fresh
`cluster reset acme` with extra-src set + preload WITHOUT the reset, asserting
`index-core!` emits zero acme rows, then adding the consumer's `reset!` and
asserting acme rows appear at boot.

---

## 2. The Acme third-party test harness — REAL, port-isolated

### Directory layout

A sibling `acme/` world dir at repo root (matches the Path B recipe in
`extra-src.md` and the example name in client.cljs:906). It is the consumer's
OWN deps.edn project pointed at by `SEON_EXTRA_SRC`. **No seon `src/` edits.**

```
/Users/sean/src/seon/acme/
├── deps.edn                  ; {:paths ["src"] :deps {…acme's own deps…}}
│                             ; (NO seon dep — seon supplies the classpath via
│                             ;  bin/seon's :local/root injection; acme is the
│                             ;  :local/root)
├── package.json              ; acme npm deps (optional; feeds SEON_EXTRA_NPM)
├── src/acme/
│   ├── pod.cljs              ; ENTRY ns (SEON_EXTRA_PRELOAD=acme.pod). At load
│   │                         ; time runs the reset! — THE crux of BUG B:
│   │                         ;   (ns acme.pod
│   │                         ;     (:require [seon.client]
│   │                         ;               acme.widget acme.brand)
│   │                         ;     (:require-macros
│   │                         ;       [seon.indexing :refer [specced-fn-vars]]))
│   │                         ;   (reset! seon.client/!extra-core-vars
│   │                         ;     (filterv #(clojure.string/starts-with?
│   │                         ;                 (str (:ns (meta %))) "acme.")
│   │                         ;              (specced-fn-vars)))
│   │                         ; NOTE: macro expands HERE, in acme.pod, whose
│   │                         ; closure pulls in acme.widget/acme.brand — so it
│   │                         ; CAN see acme vars (the seon-side helper could NOT).
│   ├── widget.cljs           ; acme.widget — a DOWNSTREAM source ns to prove
│   │                         ; indexing+context: a public :malli/schema fn, e.g.
│   │                         ;   (defn set-location! …) AND a live-tile fn:
│   │                         ;   (defn dash [in]
│   │                         ;     {:seon.render/hiccup
│   │                         ;        [:div (str (count (seon.db/installed-schema
│   │                         ;                            (:seon.db/db in))))]
│   │                         ;      :seon.render/ai "acme dash"})
│   │                         ; ns form MUST (:require [seon.db :as db]) so SCI
│   │                         ; resolves db/ (the prior agent's user-error class).
│   ├── brand.cljs            ; acme.brand — branding layer: title/accent/tagline
│   │                         ; constants the inspector/soul can read (proves a
│   │                         ; second downstream ns indexes + renders).
│   └── embed.cljs            ; acme.embed — registers a CUSTOM embeddable attr,
│                             ; e.g. (register-embeddable! :acme.widget/note) —
│                             ; the JVM-side call; on the pod side this is the
│                             ; consumer's own seon.embed query usage. Proves the
│                             ; custom-embeddable + KNN path on an acme entity.
└── README.md                ; how to boot the Acme harness (the commands below)
```

Existing precedent to mirror: `test/acme/extra_fixture.cljs` (the committed
`acme.extra-fixture` that registers a specced fn into `!extra-core-vars` by
hand — the exact shape `acme.pod` automates) and
`test/seon/client/extra_core_test.cljs`.

### Isolated env block (zero overlap with the live default cluster)

```bash
# Acme harness — distinct from live default (7890/7891/data/clusters/default/
#   tmp/seon-cluster-default-{req,pub}.sock).
export SEON_PORT=7990                                   # pod HTTP
export SEON_WRITER_REPL_PORT=7991                       # wire-server loopback REPL
export SEON_CLUSTER_DIR=data/clusters/acme              # store at .../acme/store
export SEON_REQ_SOCK=tmp/acme-cluster-req.sock          # wire-server req UDS
export SEON_PUB_SOCK=tmp/acme-cluster-pub.sock          # wire-server pub UDS
export SEON_EMBED=1                                      # embeddings on
export GEMINI_API_KEY=…                                  # for query-embed + KNN
export SEON_AI_PROVIDER=deepseek                         # Acme's own AI provider
                                                         #   (pre-authorized, cheap)
# Path B wiring — the crux of BUG B:
export SEON_EXTRA_SRC=/Users/sean/src/seon/acme          # acme's deps.edn project
export SEON_EXTRA_PRELOAD=acme.pod                       # entry ns (runs the reset!)
# export SEON_EXTRA_NPM=/Users/sean/src/seon/acme/node_modules   # if npm deps
```

Every one of these is already read by `bin/seon` (verified): env-overridable
defaults at bin/seon:116-119, `SEON_PORT` read by the pod, `extra_src_sdeps`
(141-145) + `extra_preload_merge` (147-151) inject Path B.

### Can `bin/seon` target Acme via env alone? Mostly yes; a thin `bin/acme` wrapper is warranted for `cluster reset`

- **start/stop/status/tail**: WORK purely via env. A second
  wire-server+pod boots on the Acme set with no code change — the writer's CLI
  args are built from `$SEON_CLUSTER_DIR/$SEON_REQ_SOCK/$SEON_PUB_SOCK/
  $SEON_WRITER_REPL_PORT` (bin/seon:194) and the pod reads `$SEON_PORT`.
- **`bin/seon cluster reset acme`**: does NOT do what you want. `cluster_reset`
  (bin/seon:735-790) only bounces processes for the literal name `"default"`
  (`bounce=1` iff name == default, 757); for any other name it wipes
  `data/clusters/<name>/store` and stops there ("no processes registered").
  BUT — if you set `SEON_CLUSTER_DIR=data/clusters/acme` and run `bin/seon
  cluster reset default`, it wipes `$SEON_CLUSTER_DIR/store` (= acme's store,
  748) AND bounces the Acme processes (because the env-pointed default IS the
  Acme cluster in that shell). That is the env-only path: **`cluster reset
  default` with the Acme env = a full Acme reset.**
- **Recommendation: ship a tiny `bin/acme` wrapper** that exports the Acme env
  block and delegates to `bin/seon` (`exec bin/seon "$@"`), so an operator
  types `bin/acme start pod` / `bin/acme cluster reset default` without a
  risky "remember to export the env first." It is purely ergonomic — no seon
  code change. This mirrors the artifact-doc's "extension without fork" stance
  (sibling doc §packaging).

### Boot order + exact commands

wire-server FIRST (sole writer; the pod's boot is ping-gated fail-loud against
the req socket), pod SECOND.

```bash
# (with the Acme env block exported, or via bin/acme)
bin/acme start wire-server      # binds tmp/acme-cluster-req.sock; REPL on 7991
bin/acme start pod              # Node out/client/main.js on :7990; boot re-seeds
bin/acme status                 # PIDs + pod port 7990
bin/acme tail pod               # boot + replay-n-ok/fail + agent roster

# Fresh world (clean store — needed for the BUG-B clean-store repro):
bin/acme cluster reset default  # wipes data/clusters/acme/store, bounces both
```

### How the harness LOADS Acme's own source (the BUG-B crux)

`SEON_EXTRA_SRC=/Users/sean/src/seon/acme` makes `bin/seon` add acme as a
`:local/root` dep (bin/seon:141-145) → shadow runs in deps mode so acme's
`src/` joins the build classpath → `acme.*` is COMPILED into the pod bundle. The
INDEX hook is `SEON_EXTRA_PRELOAD=acme.pod` (appended to `:preloads`,
bin/seon:147-151): at pod load `acme.pod` runs `(reset!
seon.client/!extra-core-vars (filterv … (specced-fn-vars)))` with the macro
expanded IN acme.pod (closure includes acme.widget/acme.brand). Then boot
`index-core!` reads `@!extra-core-vars` and emits acme `:seon.fn`/full-source
`:seon.ns` rows; `core-ns-set` includes them; ctx renders them; embeddings
index them. **The harness must do exactly this `reset!` to reproduce the FIX
state — and a variant `acme.pod` that OMITS the reset! to reproduce the BUG
state** (the realistic third-party omission). `first-party-file?`
(indexing.clj:66-84) already admits `SEON_EXTRA_SRC` files, so no further
change is needed once the var list is captured.

---

## 3. Acceptance checks — falsification harness (each maps to a bug)

Run read-only against the Acme writer/conn (`@seon.db/*conn*` on the Acme pod,
or `nc -U tmp/acme-cluster-req.sock` for writer-side). Each check is a concrete
pass/fail observation on the RUNNING Acme pod — not inferred from tests.

### BUG B — indexing → context → retrieval (the high-severity one)

- **B1 (BUG-state falsification).** Fresh `cluster reset default` (Acme env)
  with `SEON_EXTRA_SRC` set + a preload that OMITS the `reset!`, BEFORE any
  agent turn:
  `(d/q '[:find ?sym :where [?f :seon.fn/sym ?sym]] db)` contains ZERO syms
  starting `"acme."` AND `(seon.client/core-ns-set)` excludes `:acme.widget`
  AND `(count @seon.client/!extra-core-vars)` = 0. **This is the bug** — must
  reproduce before fixing.
- **B1-loud (fix #1).** With `SEON_EXTRA_SRC` set + the omitting preload, the
  pod boot log (`bin/acme tail pod`) contains the new loud WARN naming
  `SEON_EXTRA_SRC` and the required `reset!` one-liner. PASS = the WARN fires;
  FAIL = silent (today's behavior).
- **B2 (FIX state — indexing).** Fresh `cluster reset default` with the REAL
  `acme.pod` (runs the `reset!`), BEFORE any agent turn:
  `(d/q '[:find ?sym ?src :where [?f :seon.fn/sym ?sym][?f :seon.fn/source ?src]] db)`
  contains `"acme.widget/set-location!"` with source containing
  `"defn set-location!"` and the fn has a non-nil `:seon.fn/spec`; and
  `(contains? (seon.client/core-ns-set) :acme.widget)` is true with a
  full-source `:seon.ns` row.
- **B3 (FIX state — context).** `(seon.ctx/included-ns? :acme.widget)` is true
  AND the rendered `<namespace>` body for the agent contains `acme.widget` and
  `acme.brand` (compact for non-current nses; full when acme.widget is the
  agent's current ns). NOTE: do NOT assert `full-source-ns?` — it is false by
  design for non-`my.*` nses; full-source for extra nses is decided at INDEX
  time via `extra-core-ns-strs` (client.cljs:958-964).
- **B4 (FIX state — retrieval).** With `SEON_EMBED=1`+`GEMINI_API_KEY`, an
  embeddings preflight returns a **1536-vector** for an Acme entity and a top-1
  KNN: `(seon.embed/search-pull {:seon.embed/query "<text matching the acme
  fn>" :seon.embed/k 1 :seon.embed/where '[[?e :seon.fn/sym
  "acme.widget/set-location!"]]})` returns a hit whose `:seon.embed/entity`
  carries the acme fn source, with `:seon.embed/distance` small. (If pre-tee
  rows are involved, first confirm the wire-server backfilled them — see §1
  embedding gap.)

### BUG A — SCI live-tile rendering

- **A1 (steady-state PASS, today).** As an Acme agent, eval ONE string
  `(ns acme.widget (:require [seon.db :as db]))\n(defn dash [in]
  {:seon.render/hiccup [:div (str (count (seon.db/installed-schema
  (:seon.db/db in))))] :seon.render/ai "acme dash"})`; transact
  `{:seon.agent/id <id> :seon.render.live-canvas/content 'acme.widget/dash}`;
  `bin/acme restart pod`; then
  `(seon.render/render-agent-tile {:seon.db/db @seon.db/*conn*
  :seon.agent/id <id>})`. ASSERT the returned `:seon.render/hiccup` contains
  the schema-count div (real render), `:seon.render/error` is nil, and it is
  NOT the "Preparing this view…/isn't loaded yet" placeholder. ALSO:
  `(seon.eval/lookup-value 'acme.widget/dash)` non-nil after boot, and
  `(seon.render.sci/invoke-bounded 'acme.widget/dash input)` returns a real
  render map (not `{:seon.render.sci/fallthrough true}`). **This PASSES today**
  — it is the regression guard, not the bug.
- **A2 (the ACTUAL bug — `:refer :all` falsification).** Eval an Acme ns that
  uses `:refer :all`, e.g.
  `(ns acme.r (:require [seon.db :refer :all]))\n(defn t [in]
  {:seon.render/hiccup [:div "r"] :seon.render/ai "r"})`; wire it; restart pod.
  ASSERT (bug, pre-fix): the boot log shows `replay-n-fail` INCLUDING
  `:acme.r` with `:all is not ISeqable`, `(js/goog.getObjectByName "acme.r")`
  is false, and the tile renders pending-html. ASSERT (post-fix #1):
  `:acme.r` either loads (globalThis true, real render) or the agent received
  a LEGIBLE `:refer :all`-rejected error at eval time — never a silent drop.
- **A3 (double-store fix).** After eval'ing acme.widget (multi-form string),
  `(re-seq #"defn set-location!" (seon.eval/reconstitute-ns-source
  @seon.db/*conn* :acme.widget))` has length 1 (post-fix #2), NOT 2 (today).
- **A4 (SCI bounding works for an Acme fn — optional isolation).** Wire
  `'acme.widget/spin` where `(defn spin [_] (loop [] (recur)))` (ns
  correctly `:require`-d so it resolves); ASSERT
  `render-agent-tile` returns the welcome fallback within ~budget (≈250ms +
  slack) and a 50ms `setTimeout` canary scheduled before the render STILL
  FIRED — proving SCI's interrupt aborts an Acme interpreted loop in-process
  without freezing the event loop.

---

## 4. Fix + build order

1. **Harness first (no seon code).** Create `acme/` (deps.edn, `src/acme/{pod,
   widget,brand,embed}.cljs`, README) + optional `bin/acme` wrapper. Boot it
   on the isolated env. GATE: **A1 PASSES** (proves the wired SCI path works
   today) and **B1 reproduces the bug** (zero acme rows with the omitting
   preload). This establishes the falsification baseline before any fix.
2. **BUG B fix (high severity, do next).** Loud boot WARN in `index-core!`
   (fix #1) + sugar macro `seon.indexing/extra-core-vars-for` (fix #2) +
   `extra-src.md` doc (fix #3). GATE: **B1-loud fires**, then with the REAL
   `acme.pod` **B2 + B3 PASS** (acme indexed + in context). Verify the
   wire-server embedding backfill, then **B4 PASS**.
3. **BUG A fix — `:refer :all` (medium).** Handle-or-reject-loudly in the
   reconstitute/load path + tee (fix #1). GATE: **A2** flips from silent-drop
   to load-or-legible-error.
4. **BUG A fix — double-store (low, cosmetic).** `:seon.ns/source` stores only
   the `(ns …)` form (fix #2). GATE: **A3** (occurrence count 1).
5. **Cleanups (non-blocking).** SCI on-demand load (A-fix #3), agent-ns-set
   stub exclusion (A-fix #4). GATE: A4 still PASSES (no regression).

Rationale: BUG B is the genuinely high-severity, root-cause-HOLDS defect and
gates the consumer's whole product surface — fix it first. BUG A's wired path
is NOT broken today; only `:refer :all` and the cosmetic double-store warrant
changes, after the harness proves A1.

---

## 5. Open risks / still-unverified (need a live spike + safety notes)

- **UNVERIFIED — exact `:refer :all` failure site.** The `:all is not ISeqable`
  origin in the `cljs.js` load-fn path is inferred from the live `replay-n-fail`
  name, not traced. Spike: read `logs/pod.log` for the `:my.refertest` warn and
  step `reconstitute-ns-source → seval/eval` for a `:refer :all` ns. Settles
  whether fix is "handle" vs "reject at tee."
- **UNVERIFIED — BUG-B clean-store repro.** This live machine already has a
  tee'd `:acme.widget` row, so B1/B2 must be reproduced on a FRESH
  `cluster reset default` (Acme env) to isolate "unfired reset! on a clean
  store" from "tee row already exists." Needs the harness booted.
- **UNVERIFIED — wire-server embedding backfill.** Whether
  `register-embeddable!` backfills rows that pre-date the registration is a
  known P2 gap (memory). B4 on PRE-EXISTING rows depends on it; newly-indexed
  acme rows are fine. Spike: JVM `src/seon/embed.clj` register-embeddable! path.
- **Path A vs Path B mismatch risk.** If the consumer intended Path A
  (store/`my.*` prefix + `:seon.ctx/included-prefixes`) rather than Path B
  (`SEON_EXTRA_SRC`), the fix is a `:seon.ctx/config` change, not the boot
  WARN. Confirm the consumer's intended path before shipping (extra-src.md
  §"When to use which").
- **Classpath staleness.** `SEON_EXTRA_SRC` is fixed at `cljs-watch` launch
  (extra-src.md). If set after starting cljs-watch and only the pod restarts,
  acme is not compiled and `specced-fn-vars` can't see it. The harness must
  start cljs-watch (or rebuild) WITH the Acme env, not after.

### Live-pod safety notes (HARD)

- All §1 evidence was gathered with `mcp__seon_cljs__eval` READ-ONLY against
  the LIVE default pod: small reads only, **no suite run, no transact, no
  restart** (a careless eval wedges the single-threaded pod's async
  continuation).
- The Acme harness is **fully port/socket/store isolated** (7990/7991/
  `data/clusters/acme`/`tmp/acme-cluster-*.sock`) — it boots ALONGSIDE the live
  default cluster (7890/7891/`data/clusters/default`/
  `tmp/seon-cluster-default-*.sock`) with zero overlap. `cluster reset
  default` is safe ONLY in a shell with the Acme env exported (it wipes
  `$SEON_CLUSTER_DIR`); the `bin/acme` wrapper removes that footgun.
- Restarting the Acme pod (`bin/acme restart pod`) NEVER touches the live pod —
  different supervisor lock name space only if processes are distinct; since
  bin/seon's mkdir-mutex keys on process name, run the Acme cluster from a
  distinct working directory OR accept that `pod`/`wire-server` lock names are
  shared — **do NOT run `bin/acme restart pod` if it would bounce the LIVE
  pod.** (This is the one operational sharp edge: verify the wrapper targets a
  distinct lock/pid space, e.g. by a name suffix, before relying on it.)
