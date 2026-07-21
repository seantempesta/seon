---
type: prd
status: draft
tags: [prd, agent, database]
---

# Reply hook (#27) + a first-class recorded home for downstream `my.*` code (#28)

## TL;DR

Two unbuilt downstream asks, designed build-ready against the live CLJS pod.
Both reuse mechanisms that already exist; neither needs new machinery, only a
new seam in a load-bearing path.

- **#27 — `on-reply` hook.** A substrate seam that fires on EVERY assistant
  reply (text + agent id), runs async, and transacts rows keyed to the turn —
  independent of whether the agent cooperates. The register API mirrors the
  JVM `seon.server.registry/register-on-ensure-db-hook!` registry shape (keyed,
  idempotent, ordered) but lives in the **pod** (CLJS), because the reply path
  is pod-side. The fire-site is the one place every assistant reply is
  materialized: `seon.agent/ask-and-eval-reply!` (`agent.cljs:1127-1134`), where
  the LLM's raw text is folded into the turn as a self-message. The panel beside
  the chat is **just a section fn** that reads those turn-keyed rows and returns
  hiccup (a radar/spectrum SVG) — registered with `add-section!` + a
  `:seon.render/html` twin (`ctx.cljs:1762`), the exact pattern the 6/18
  "right pane mirrors section html twins" work (`0b5b1f1`) made first-class.
  **No new render surface** (this is what superseded ask #31).

- **#28 — recorded `my.*` home.** A `SEON_SEED_DIR` env points at a
  consumer-owned dir of `my.*` source files. At boot, each file is read and
  evaluated through the **recording path** (`seon.eval/eval-batch!`) under a
  distinct `:seon.db/origin :seed-dir` tx-context, so detect-and-tee
  (`build-tee-entities`, `eval.cljs:1389`) persists replayable
  `:seon.ns`/`:seon.fn`/`:seon.schema`/`:seon.test` rows that reconstitute on
  every later boot via `replay-program-graph!` — first-class like the built-in
  `my.kb`/`my.soul`, but durable instead of compiled-in. This resolves the
  reserved-prefix tension: `SEON_EXTRA_SRC` REFUSES `my.*` (`client.cljs:942`)
  precisely because a *compiled* `my.*` ns would replay-skip what should be
  store rows; `SEON_SEED_DIR` is the opposite delivery vehicle — it MAKES those
  store rows, so `my.*` is exactly right.

The two compose: `SEON_SEED_DIR` is where a downstream's `my.virtue` ns lives,
and that ns's load-time `(register-on-reply! …)` call wires the #27 hook. A
consumer with no build of its own uses `SEON_SEED_DIR`; a consumer with a build
uses `SEON_EXTRA_SRC` for compiled `acme.*` code. (This supersedes the
`SEON_OVERRIDE_DIR`/`SEON_SEED_DIR` framing in
[[overridable-substrate-2026-06-17]], whose #27/#28 sections were never built;
this doc is the buildable spec for those two asks specifically. Per-function
override is a separate, larger ask and is out of scope here.)

---

## #27 — The `on-reply` hook + the panel beside the chat

### The seam (file:line)

Every assistant reply is materialized in exactly one place. `run-turn!`
(`agent.cljs:1219`) calls `ask-and-eval!` (`agent.cljs:1173`), whose success
branch calls `ask-and-eval-reply!` (`agent.cljs:1087`). That fn holds the
verbatim LLM text and folds it into the turn as a self→self message:

```clojure
;; agent.cljs:1097, 1127-1134  (ask-and-eval-reply!)
(let [reply-text (or (:text resp) "")
      ...]
  (cond-> {...}
    (not (str/blank? reply-text))
    (assoc :seon.agent.turn/messages
           [{:seon.agent.message/id   (db/new-id!)
             :seon.agent.message/from [:seon.agent/id id]
             :seon.agent.message/to   [[:seon.agent/id id]]
             :seon.agent.message/content reply-text ...}])))
```

This is the right fire-site, not `seon.agent.message/message!` (`message.cljs:264`):

- `ask-and-eval-reply!` fires **once per turn** with the FULL assistant text
  the model produced, and it carries `id` + `id-of-turn` + `turn-idx` as locals
  (captured before the LLM await), so the hook gets a clean (text, agent-id,
  turn-id) triple by construction.
- It fires **independent of agent cooperation** — it runs whether or not the
  agent ever called `reply!`/`message!`. That is the ask's hard requirement:
  ambient post-processing of every reply, "independent of whether the agent
  cooperates." (Hooking `message!` instead would MISS a turn whose answer was
  bare prose with no `reply!` call — exactly the #47/#50 failure class — and
  would DOUBLE-fire on agent-to-agent consults.)

**Fire AFTER the turn closes, not inside the fold.** `ask-and-eval-reply!`
returns a map that `ask-and-eval!` → `with-turn!`'s close-tx persists
(`agent.cljs:1040-1054`). The hook must not run inside that synchronous return
(it would block the turn and tangle the close-tx). Fire it from `run-turn!`
AFTER the close-tx lands and the turn entity is pulled
(`agent.cljs:1305-1309`), reading the just-closed turn's reply text. Concretely,
add one call right before `run-turn!` returns its pulled result
(`agent.cljs:1309`):

```clojure
;; run-turn!, just before the (assoc (db/pull …) :seon.agent/eval-count n-ok)
(fire-on-reply-hooks! {:seon.agent/id id
                       :seon.agent.turn/id-of-turn turn-id
                       :seon.agent.reply/text (turn-reply-text result)})
```

where `turn-reply-text` reads the self→self assistant message off the closed
turn record (it is already inlined by the pull pattern at `agent.cljs:1306`).
`fire-on-reply-hooks!` is **fire-and-forget**: it kicks each hook on a detached
async path (no `await` in `run-turn!`'s critical path) so a slow/failed hook
never delays or breaks the turn.

### The minimal API (mirror `register-on-ensure-db-hook!`)

The canonical registry to mirror is `seon.server.registry/register-on-ensure-db-hook!`
(`registry.clj:240-261`) — a `defonce` atom of `{::hook-key k ::hook-fn f}`
entries, idempotent-by-key (re-register replaces in place), fired in
first-registration order with a per-hook try/catch that LOGS LOUDLY (never
swallows). That registry is JVM-side (wire-server); #27's reply path is
pod-side, so the registry lives in the **pod** — a new `seon.agent.on-reply` ns
(or a small section of `seon.agent`), structurally identical:

```clojure
;; seon.agent.on-reply  (new, CLJS)
(schema/register! :seon.agent.reply/text :string)          ; shared with the panel rows
(schema/register! :seon.agent.on-reply/hook-key  :keyword)
(schema/register! :seon.agent.on-reply/hook-sym  :symbol)  ; late-resolved fn symbol

(schema/register! ::register-request
  [:map [:seon.agent.on-reply/hook-key :keyword]
        [:seon.agent.on-reply/hook-sym :symbol]])
(schema/register! ::register-response
  [:map [:seon.agent.on-reply/hook-count :int]])

(defonce ^:private !on-reply-hooks (atom []))   ; vector, first-registration order

(defn register-on-reply!
  "Register a `(fn [{:seon.agent/id :seon.agent.turn/id-of-turn
                     :seon.agent.reply/text}])` under hook-key, fired async
   after every assistant reply. Idempotent by key — re-register replaces in
   place. The fn is referenced by SYMBOL (late-resolved per fire via
   seon.eval/lookup-value), so a SEON_SEED_DIR my.* ns can register its own
   symbol at load and survive a recompile."
  {:malli/schema [:=> [:cat ::register-request] ::register-response]}
  [{:seon.agent.on-reply/keys [hook-key hook-sym]}] …)   ; same swap! shape as registry.clj:256

(defn unregister-on-reply! [{:seon.agent.on-reply/keys [hook-key]}] …)   ; retract = drop the entry
```

The fire-fn mirrors `run-on-ensure-db-hooks!` (`registry.clj:270-290`) — fail-soft per hook:

```clojure
(defn ^:async fire-on-reply-hooks!
  "Fire every registered on-reply hook for one reply. Each hook runs on a
   DETACHED async path (errors caught + logged, never propagated) so a slow or
   broken hook cannot delay or break the turn. The reply text is read off the
   just-closed turn (run-turn!)."
  [{:seon.agent/keys [id] :seon.agent.turn/keys [id-of-turn]
    :seon.agent.reply/keys [text] :as reply}]
  (doseq [{:seon.agent.on-reply/keys [hook-key hook-sym]} @!on-reply-hooks]
    (-> (js/Promise.resolve
          (let [f (seval/lookup-value hook-sym)]   ; eval.cljs:302 late-resolve
            (when f (f reply))))
        (.catch (fn [e]
                  (log/error-console! "seon.agent.on-reply"
                    (str "on-reply hook " hook-key " failed — reply post-processing skipped")
                    e))))))
```

Late symbol resolution (`seon.eval/lookup-value`, `eval.cljs:302`) is what lets
a `SEON_SEED_DIR` `my.*` hook fn be referenced before its ns is fully loaded
and survive a hot-recompile — the same indirection `:seon.render/ai` section
slots already use.

### The row schema (keyed to the turn)

The hook's job is to transact rows keyed to the turn it scored. Define ONE
generic envelope the panel reads; a downstream's compose-fn fills the payload:

```clojure
(schema/register! :seon.agent.reply.annotation/id        [:string {:seon.db/identity true}])
(schema/register! :seon.agent.reply.annotation/of-turn   :seon.db/ref)   ; → :seon.agent.turn
(schema/register! :seon.agent.reply.annotation/of-agent  :seon.db/ref)   ; → :seon.agent
(schema/register! :seon.agent.reply.annotation/kind      :keyword)       ; :moderation :sentiment :cost …
(schema/register! :seon.agent.reply.annotation/score     :double)        ; the spectrum value
(schema/register! :seon.agent.reply.annotation/label     {:optional true} :string)
(schema/register! :seon.agent.reply.annotation/at        :inst)
(schema/register! :seon.agent.reply.annotation
  [:map {:seon.db/entity true}
   [:seon.agent.reply.annotation/id]
   [:seon.agent.reply.annotation/of-turn]
   [:seon.agent.reply.annotation/of-agent]
   [:seon.agent.reply.annotation/kind]
   [:seon.agent.reply.annotation/score]
   [:seon.agent.reply.annotation/label {:optional true}]
   [:seon.agent.reply.annotation/at]])
```

A hook fn writes one (or N) annotation rows per reply via `db/transact!`,
linking `:of-turn`/`:of-agent` by lookup ref. The schema is intentionally
multi-axis (`:kind` + `:score`) so it generalizes — moderation, sentiment, cost
rollups, per-reply scoring all reuse it; a radar tile plots N kinds as N spokes.
This is the seon-core-generic shape; a downstream that wants richer payload
registers its own `:my.virtue.score/*` attrs and reads them in its own panel fn.

### The panel = a section fn (reactive-context, no new mechanism)

The panel beside the chat is a context section whose `:seon.render/html` twin
reads the annotation rows for the current agent and returns a radar/spectrum
SVG. This is exactly the "right pane mirrors section html twins" pattern
(`0b5b1f1`): a section map carries `:seon.render/ai` (text for the LLM) and an
optional `:seon.render/html` (symbol → hiccup), and the composer resolves the
html twin via `render-section-html` (`ctx.cljs:1762`); the inspector renders
each twin as a card (`inspector.cljs:179-183` builds `{::hiccup ::kind
::card-key}` cards, `inspector.cljs:347-360` renders each `section-card`). On
the human-facing consumer page (`GET /agent/<id>`), the live-tile pane already
renders beside the chat bubbles (`inspector.cljs` `consumer-shell` /
`tile-pane-fragment`); a downstream annotation panel rides the same right-pane
slot as another html-twin section.

A downstream registers it with one call from its `my.*` ns (or the agent does):

```clojure
(seon.agent/add-section!                ; agent.cljs:1616
  {:seon.ctx/name     :reply-spectrum
   :seon.render/ai     'my.virtue/reply-spectrum-ai     ; text summary the LLM sees
   :seon.render/html   'my.virtue/reply-spectrum-svg})  ; hiccup SVG the human sees
```

`reply-spectrum-svg` queries `:seon.agent.reply.annotation` rows for the agent
in scope and emits an `[:svg …]` radar. Because it is a pure fn of the DB at
render time, it is **self-healing** (no annotations → empty/placeholder; new
reply → new spoke; nothing stored that must be cleared — [[reactive-context]]).
The section validates at `add-section!` and `transact!` like everything else
(`ctx.cljs:107` `:seon.ctx/section` schema).

### Failure / edge cases

- **Hook throws or hangs.** Caught + logged per-hook in `fire-on-reply-hooks!`;
  the turn already closed, so nothing to roll back. A hung hook can't wedge the
  turn because the fire is detached (no `await` on the critical path).
- **Bare-prose turn (no `reply!`).** Still fires — the fire-site is the raw-text
  fold, not `message!`. (This is the whole point vs. hooking `message!`.)
- **Blank reply.** `ask-and-eval-reply!` stores no self-message when the reply
  is blank (`agent.cljs:1127`); `turn-reply-text` returns nil; `fire-on-reply-hooks!`
  is a no-op for that turn (don't fire on empty text).
- **LLM-error turn.** `ask-and-eval!`'s error branch (`agent.cljs:1197`) stores a
  `⚠ LLM call failed` self-message and closes `:error`; the hook should fire only
  on `:done` turns (guard on `(nil? (:seon.agent.turn/status result))`), so a
  transport failure is not scored as a real reply.
- **Agent-to-agent consult.** A turn whose only output is a `message!` to another
  agent still produced assistant text (the raw fold) — the hook fires on the text
  the model emitted, which is correct (the reply text IS the assistant's output).
  If a downstream wants user-replies-only, it filters on whether the turn's
  woken-by was the user inside its own hook fn (the data is on the turn).
- **Annotation panel before any reply.** Section fn returns empty rows → renders
  a calm placeholder, never an error (mirror `warn.cljs:865-869`).
- **Multiple hooks.** Compose cleanly — each is an independent keyed registration,
  fired in order; a retract drops just that key (no orphaned captures, unlike raw
  monkeypatch chaining).

### Effort estimate

~1 day. New `seon.agent.on-reply` ns (~80 lines: atom + register/unregister +
fire), one ~6-line wiring edit in `run-turn!` (`agent.cljs:1305-1309`) plus a
`turn-reply-text` helper, the annotation schema (~10 register! calls), and an
example `my.virtue/reply-spectrum-svg` section fn (lives in the SEON_SEED_DIR
example, not in core). Unit tests: register→fire→annotation-row-written→
unregister→no-fire; a fire on a bare-prose turn; a throwing hook doesn't break
the turn.

### Stability-risk assessment (#27 touches the reply/turn-completion path)

**What could regress.** `run-turn!`/`ask-and-eval-reply!` is THE hot path —
every turn flows through it. Risks: (1) firing the hook synchronously would block
the turn or tangle `with-turn!`'s close-tx (`agent.cljs:1040-1061`), which is
already fragile (the 2026-06-17 "deaf-after-one-message" close-tx bug); mitigated
by firing AFTER the pull, detached, never awaited. (2) Reading reply text off the
wrong place — must read the closed turn's self→self message, not re-derive from
`message!` (which #43/#47/#51 show is where re-processing/refusal bugs live).
(3) A hook that itself calls `reply!`/`message!` could re-enter the loop — the
schema/contract must forbid hooks from sending messages (they transact annotation
rows only; document + ideally assert).

**Falsification check (acme-harness acceptance).** With NO hook registered, a
clean single-`reply!` turn must produce byte-identical behavior to today: turn
closes `:done`, halts the wake, one assistant self-message, zero annotation rows.
THEN register a stub hook that transacts one `:seon.agent.reply.annotation` row;
drive one user message; assert (a) the reply still lands and the wake still halts
(the #43 invariant is NOT disturbed), (b) exactly one annotation row exists keyed
to that turn, (c) the section fn renders a non-empty SVG card. Negative: register
a hook that throws — assert the turn still closes `:done`, the reply lands, the
wake halts, and the error is logged once. This gates the merge: if registering a
hook changes turn-completion or wake-halt behavior, it's a regression.

---

## #28 — A recorded, replayable home for downstream `my.*` product code

### The problem precisely

Seon ships `my.kb` / `my.soul` / `my.kb.system` as COMPILED source — they are in
`core-vars` / `fn-less-compiled-roots` (`client.cljs:966-972`), so they live in
`core-ns-set`, are indexed for DISPLAY only, and are REPLAY-SKIPPED (re-evaling
their shipped source would re-run `register!` forms — `client.cljs:974-995`). A
downstream's durable product code (a `my.<feature>` ns) has nowhere first-class
to live:

- **Agent-authored `:seon.fn` rows** are snapshot-only and fragile — there's no
  source-of-truth file; a `cluster reset` wipes them; they exist only if an agent
  happened to eval them.
- **`SEON_EXTRA_SRC`** is the compiled path and LOUDLY REFUSES `my.*`/`seon.*`
  (`assert-extra-vars-unreserved!`, `client.cljs:942-956`):
  > "extra-core registration provides RESERVED-prefix nses: … — seon.* is the
  > core's and my.* is the human's store-replayed corpus; SEON_EXTRA_SRC code
  > must live under the downstream's own root prefix (e.g. acme.*)"
  The refusal is correct: a COMPILED `my.*` ns would join `core-ns-set` and
  replay-skip exactly the rows that should be the agent's store corpus.

### The mechanism: `SEON_SEED_DIR` — load `my.*` through the recording path

A consumer-owned dir of `my.*` `.cljs` files, read at boot and **evaluated
through `seon.eval/eval-batch!`** so detect-and-tee persists them as replayable
`:seon.ns`/`:seon.fn`/`:seon.schema`/`:seon.test` rows. The store IS the program
([[code-as-data-runtime]]): these rows reconstitute on every boot via
`replay-program-graph!` exactly like agent-authored code, but their source of
truth is a file the downstream owns and re-seeds idempotently.

This is NOT compiled-in and NOT `SEON_EXTRA_SRC` — it is the **store delivery
vehicle** for `my.*`. The reserved-prefix tension dissolves: `SEON_EXTRA_SRC`
refuses `my.*` because it makes COMPILED rows; `SEON_SEED_DIR` makes STORE rows,
which is what `my.*` is supposed to be.

### The env var + boot seam (file:line)

- **Env var:** `SEON_SEED_DIR` = absolute path to a dir whose `.cljs` files are
  `my.*` namespaces. Read via `seon.platform/env-val "SEON_SEED_DIR"`
  (`platform.cljs:86-94` — the canonical trimmed/blank-tolerant reader; unset =
  no-op, byte-identical default boot).

- **Boot seam:** a new `^:async seed-my-ns-dir!` step in `start-agent!`
  (`client.cljs:1910`), placed in the boot sequence AFTER per-agent boot and
  AFTER `boot-seed!` (`client.cljs:2040`) — so the conn, compile-state, handler
  schema, and core index are all live — but it must run such that its rows are
  present BEFORE the agents start taking turns. The cleanest placement: fold the
  seed step into `boot-seed!` itself (the ONE "make this store the world" path
  shared with the gym, `client.cljs:1729`), as a fifth seed transact, so the gym
  inherits it and the two can't drift. Because seeding EVALS (to record), it
  needs a compile-state + an agent scope; do it as its own step in `start-agent!`
  right after `boot-seed!` and before the loop is driveable, under
  `(db/with-agent primary …)`.

The step, per file:

1. `(platform/env-val "SEON_SEED_DIR")`; nil/blank → return `{::seeded 0}`.
2. List `*.cljs` (sorted, Node `fs.readdirSync`), read each (`fs.readFileSync`).
   Mirror `read-src-file`'s reader (`client.cljs:997`).
3. For each file, in sorted order, eval its WHOLE source through the recording
   path under a distinct seed origin:

   ```clojure
   (db/with-tx-context
     {:seon.db/origin :seed-dir
      :seon.db/agent-id primary}
     (fn ^:async []
       (seval/eval-batch! compile-state
                          (repl/parse-forms file-src)
                          'cljs.user            ; the (ns …) head re-homes
                          primary               ; owning agent id
                          (db/new-id!))))       ; a synthetic seed turn id
   ```

   `eval-batch!` (`eval.cljs:2522`, sig
   `[compile-state parsed agent-ns-sym agent-id turn-id]`, returns
   `{:seon.eval/ids _ :seon.eval/n-ok _ :seon.eval/n-fail _}`) runs each top-level
   form, and detect-and-tee (`build-tee-entities`, `eval.cljs:1389`) writes the
   `:seon.ns`/`:seon.fn`/`:seon.schema`/`:seon.test` rows — AND captures
   `:seon.ns/requires` automatically (`ns-requires-tx`, `eval.cljs:1547`, fired on
   every successful eval), which is what makes the topo-sort load order correct on
   the next boot.

### The recording path & origin (the load-bearing detail)

The seed eval MUST run under **`:seon.db/origin :seed-dir`**, NOT `:core-seed`,
for two reasons rooted in existing code:

1. **Not blocked from (re)definition.** The override guard
   `core-origin-fn-syms`/`reject-core-overrides` (`eval.cljs:1605-1653`) DROPS any
   `:seon.fn` row whose current source datom's tx carries
   `:seon.db/origin :core-seed`. If seed files were `:core-seed`, the FIRST file's
   defs would persist but any redefinition (or the agent extending the same ns)
   would be silently refused. `:seed-dir` is a non-core origin, so the rows persist
   and remain freely (re)definable — exactly agent-corpus behavior.

2. **Replayable, not display-only.** Replay membership is by `core-ns-set`, not by
   origin — but the two must agree. The fix below removes `my.*` seed nses from
   `core-ns-set`, so `replay-program-graph!` (`client.cljs:706`) reconstitutes them
   from the store on every boot. The `:seed-dir` origin is the provenance marker
   for audit + the idempotency check, distinct from `:agent` (so a downstream can
   tell its seeded corpus from agent-evolved corpus).

**The one required code change to `core-ns-set`.** Today `fn-less-compiled-roots`
(`client.cljs:972`) hardcodes `#{"my.kb" "my.soul"}` and `core-ns-set`
(`client.cljs:974`) treats every `my.*` owning ns from `core-vars` as compiled. A
seeded `my.<feature>` ns is NOT compiled — it has no var in `core-vars`, so it
already won't be in `core-ns-set` via the var path. The ONLY collision is the
hardcoded set, which lists just the two built-in compiled roots — a seeded
`my.virtue` is not in it, so it correctly falls into `agent-ns-set`
(`client.cljs:576`) and replays. **Verify (and add a test):** a seeded `my.virtue`
ns lands in `agent-ns-set`, NOT `core-ns-set`; the two built-in compiled
`my.kb`/`my.soul` stay in `core-ns-set` and stay replay-skipped. No change to
`fn-less-compiled-roots` is needed unless a downstream wants a COMPILED `my.*`
(it shouldn't — that's what `SEON_EXTRA_SRC` + `acme.*` is for).

### Idempotency / replay-skip semantics

Two boots, two regimes — and they must not double-eval or drift:

- **First boot (empty store).** `SEON_SEED_DIR` files are eval'd through
  `eval-batch!`; rows are recorded. The agent corpus now contains `my.virtue`.
- **Nth boot (populated store).** The rows already exist and `replay-program-graph!`
  reconstitutes them from the store (the normal agent-layer load). The seed step
  must therefore **skip re-evaling a file whose rows already reflect its current
  source** — otherwise it re-records on every boot. The dedup key: for each seed
  ns, compare the file's full text to the stored `:seon.ns/source` (the same
  source-equality check `core-index-tx` already uses for ns rows,
  `client.cljs:1434`). If equal → skip (replay already loaded it). If different
  (the downstream edited the file) → re-eval through the recording path, which
  upserts the changed fns (identity upsert on `:seon.fn/sym`) and diff-upserts
  `:seon.ns/requires`. This makes `SEON_SEED_DIR` editable: change a file, restart
  the pod, the new source records and the old fns upsert in place — no `v2` ns, no
  stale duplicate.

  Ordering note: the seed step runs AFTER `replay-program-graph!`, so on the Nth
  boot replay has ALREADY loaded the stored `my.virtue` into the live
  compile-state; the seed step's source-equality skip means it does nothing. Only
  a changed file triggers a re-record (which also re-evals into the live state,
  picking up the edit immediately).

### How it differs from / composes with `SEON_EXTRA_SRC`

| | `SEON_EXTRA_SRC` (built) | `SEON_SEED_DIR` (this) |
|---|---|---|
| Prefix | downstream's own (`acme.*`); `my.*`/`seon.*` REFUSED | `my.*` (the human's corpus) |
| Delivery | COMPILED into the bundle (shadow `:local/root` + preload) | SOURCE files read at boot |
| Persistence | replay-SKIP (compiled; rows are display-only) | RECORDED as replayable store rows |
| Source of truth | the compiled ns object | the file → store rows (store wins after first boot) |
| Editability | recompile (`restart cljs-watch`) | edit file + restart pod (source-equality re-record) |
| Use for | stable product code wanting compile-time checks + instrumentation | durable `my.*` product code with no build, first-class like `my.kb` |
| Override-guard | n/a (compiled) | `:seed-dir` origin — freely (re)definable, not `:core-seed`-blocked |

**Composition.** A consumer with no build uses ONLY `SEON_SEED_DIR` (its
`my.virtue` ns registers the #27 hook + the panel section at load). A consumer
with a build uses `SEON_EXTRA_SRC` for `acme.*` compiled code AND can still use
`SEON_SEED_DIR` for `my.*` store corpus — they're orthogonal (different prefixes,
different paths, no shared mutable state). The seed dir is where the #27
`(register-on-reply! …)` wiring naturally lives: `my.virtue`'s top-level form,
recorded once, replayed every boot, re-arms the hook.

### Failure / edge cases

- **Unparseable / throwing seed file.** `eval-batch!` isolates per-form errors
  (`:seon.eval/n-fail`), so one bad form in a file doesn't zero the rest. A file
  that fails to parse entirely logs LOUDLY and is skipped — the boot continues
  (a broken seed file must not brick the pod). Surface a reactive "seed file
  failed" section (derived, self-healing) so the downstream sees it.
- **A seed file declaring a `seon.*` or non-`my.*` ns.** Refuse it at the seed
  step with the SAME loud message shape as `assert-extra-vars-unreserved!` —
  `SEON_SEED_DIR` is for `my.*` only (a downstream's own prefix code belongs in
  `SEON_EXTRA_SRC`). This keeps the prefix discipline symmetric across the two
  vehicles.
- **A seed file redefining a built-in `my.kb`/`my.soul` fn.** Those are
  `:core-seed`-origin compiled rows; `reject-core-overrides` drops the tee row
  (with a warn). A seed file should not redefine the built-in compiled roots —
  document it; the warn already fires.
- **Collision with an agent-authored `my.*` ns of the same name.** Identity
  upsert on `:seon.fn/sym` means the seed's source wins on the boot it re-records;
  thereafter agent edits and seed edits both upsert the same rows. Document that
  the seed dir owns its ns names; agents extending a seeded ns is fine (additive),
  but a name clash on the same fn is last-writer-wins by tx-time.
- **`cluster reset`.** Wipes the store; the seed dir re-records on the next boot
  (its whole point — durable across resets via the file, not the store).
- **`:seon.ns/requires` to a core ns.** Resolved on-demand by the DB load-fn
  (`guarded-load`) during the ns's eval; only intra-agent edges order the topo
  load (`agent-ns-requires`, `client.cljs:587`), which now includes the seed nses.

### Effort estimate

~1.5–2 days. New `^:async seed-my-ns-dir!` (~70 lines: env read, dir list,
per-file source-equality dedup, eval-batch! under `:seed-dir` origin, loud
per-file error handling, `my.*`-prefix assertion), one wiring edit in
`start-agent!` (`client.cljs:2040`-adjacent) + fold into `boot-seed!` for gym
parity, a `core-ns-set` membership test confirming seeded `my.*` ∈ agent-ns-set,
and an example seed dir (`my_virtue.cljs`) for the acme harness. Reuses
`eval-batch!`, `build-tee-entities`, `replay-program-graph!`, `env-val`,
`read-src-file`'s reader, and `core-index-tx`'s source-equality dedup unchanged.

### Stability-risk assessment (#28 touches the boot-seed / index / replay path)

**What could regress.** The boot spine (`start-agent!` →
`prune-core-ghosts!` → `replay-program-graph!` → per-agent boot → `boot-seed!`)
is the most load-bearing path in the pod — a wrong move bricks every boot. Risks:
(1) **`core-ns-set` misclassification** — if a seeded `my.virtue` is wrongly
treated as a compiled core ns, replay SKIPS it and it silently vanishes after the
first boot (this is the precise failure class the `prune-core-ghosts!` comment at
`client.cljs:1965` warns about — "a DELETED core ns falls out of core-ns-set, so
its ghost rows would be misclassified as agent corpus and replayed"). The inverse
here: a seed ns wrongly IN core-ns-set is replay-skipped and lost. (2) **Double
recording** — a missing source-equality skip re-records on every boot, bloating
the store and re-running side effects (a top-level `register-on-reply!` would
double-register, though the keyed registry makes that idempotent — still, the
dedup is the correct fix). (3) **Origin tagging** — recording under `:core-seed`
instead of `:seed-dir` would make the seed fns un-redefinable
(`reject-core-overrides`); recording under no/`:agent` origin loses the audit
provenance. (4) **Boot ordering** — seeding before `boot-seed!`'s handler/schema
step would fail (no entity schemas installed); seeding before replay would record
then immediately have replay re-load the same nses redundantly.

**Falsification check (acme-harness acceptance).** With `SEON_SEED_DIR` UNSET,
boot must be byte-identical to today (no seed step observable; `core-ns-set`
unchanged; replay-n-total unchanged for an existing world). THEN point
`SEON_SEED_DIR` at an example dir containing `my_virtue.cljs` (one `defn`, one
`register-on-reply!`, one section fn) and:

1. **First boot (empty store):** assert `my.virtue` defs are queryable as
   `:seon.fn` rows whose source-tx origin is `:seed-dir` (NOT `:core-seed`,
   NOT `:agent`); assert `my.virtue` ∈ `(agent-ns-set db)` and ∉ `(core-ns-set)`;
   assert the agent can CALL `my.virtue/foo` in an eval (live in compile-state).
2. **Restart the pod (populated store):** assert `replay-program-graph!`
   reconstitutes `my.virtue` (it's in `replay-n-total`); assert the seed step
   SKIPPED re-recording (source unchanged) — no new tx, no duplicate rows; assert
   `my.virtue/foo` is still callable.
3. **Edit the file, restart:** assert the changed fn upserts in place (one
   `:seon.fn/sym`, new source) — no `my.virtue` v2, no stale duplicate.
4. **End-to-end with #27:** the seeded `register-on-reply!` is live after boot
   (drive one reply → one annotation row → panel SVG renders). This is the
   composition proof that closes both asks.

Negative gate: a `SEON_SEED_DIR` file declaring `(ns acme.foo …)` or
`(ns seon.foo …)` must be REFUSED loudly at the seed step (prefix discipline);
a throwing seed file must NOT brick the boot (logged, skipped, boot continues).
If UNSET-boot is not byte-identical, or a seeded `my.*` ns ever falls into
`core-ns-set`, that's a regression and gates the merge.

---

## Cross-references

- [[overridable-substrate-2026-06-17]] — the superseded `SEON_SEED_DIR`/
  `SEON_OVERRIDE_DIR` framing; this doc is the buildable #27/#28 spec.
- [[seon-as-artifact-design-2026-06-22]] — the packaging/extension model; the
  acme clean-room harness (`/Users/sean/src/seon/acme`) is the falsification
  target both asks gate against.
- [[docs/seon/components/extra-src]] — `SEON_EXTRA_SRC` (the compiled `acme.*`
  sibling vehicle this composes with).
- [[code-as-data-runtime]] — the store IS the program; seed rows reconstitute via
  the same source→analyzer→DB→source circle.
- [[reactive-context]] — the panel + "seed file failed" surfaces are section fns,
  self-healing.
- `src/seon/agent.cljs` — `ask-and-eval-reply!` (`:1087`, fire-site), `run-turn!`
  (`:1219`, wiring), `add-section!` (`:1616`).
- `src/seon/agent/message.cljs` — `message!` (`:264`), `reply!` (`:352`) — why the
  fire-site is the raw fold, not here.
- `src/seon/server/registry.clj` — `register-on-ensure-db-hook!` (`:244`),
  `run-on-ensure-db-hooks!` (`:270`) — the registry shape #27 mirrors (JVM; #27 is
  the pod-side analog).
- `src/seon/ctx.cljs` — `:seon.ctx/section` (`:107`), `full-source-ns?` (`:232`),
  `render-section-html` (`:1762`), `core-default-ctx` (`:1584`).
- `src/seon/eval.cljs` — `eval-batch!` (`:2522`), `eval` (`:823`), `lookup-value`
  (`:302`), `build-tee-entities` (`:1389`), `ns-requires-tx` (`:1547`),
  `core-origin-fn-syms`/`reject-core-overrides` (`:1605`-`:1653`).
- `src/seon/client.cljs` — `start-agent!` (`:1910`), `boot-seed!` (`:1729`),
  `replay-program-graph!` (`:706`), `core-ns-set` (`:974`),
  `fn-less-compiled-roots` (`:966`), `agent-ns-set` (`:576`), `core-index-tx`
  (`:1408`), `read-src-file` (`:997`), `assert-extra-vars-unreserved!` (`:942`).
- `src/seon/platform.cljs` — `env-val` (`:86`) — the canonical `process.env` read
  for `SEON_SEED_DIR`.
- `src/seon/web/inspector.cljs` — html-twin card render (`:179`, `:347`); consumer
  page (`GET /agent/<id>`) where the panel renders beside the chat.
