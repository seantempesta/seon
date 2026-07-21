---
type: prd
status: active
tags: [prd, agent, schema, flow, cljs]
---

# Coherent code-as-data bootstrap / indexing (2026-06-08)

> When the compiled CLJS pod starts, whatever is in the substrate must be
> INDEXED into the code-as-data corpus and turned into a RESUMABLE experience
> with every function / spec / test present CORRECTLY. This doc is the single
> authoritative, implementation-ready design. It consolidates the prior design
> pass and the two REPL-verified research notes (`research/cljs-bootstrap-
> cache-2026-06-08.md`, `research/cljs-runtime-introspection-2026-06-08.md`)
> into one mechanism + a per-step implementation sequence. All claims are
> verified live against the running `:client` pod (MCP session "default",
> shadow port 7889) on 2026-06-08.

## 1. TL;DR — the resolved mechanism

1. **Substrate fns ARE fully introspectable at runtime** — the same way
   ClojureScript's own `cljs.repl/source-fn` (`repl.cljc:1508`) does it: read the
   source FILE at the var's `:file`/`:line`. The analyzer cache stores NO per-fn
   source (by design), but the pod is Node, cwd IS the repo root, and `:file`/
   `:line` SURVIVE instrumentation even when `:arglists` is mangled.
2. **One hybrid indexer (`index-substrate!`)** over a compile-time `#'`-literal
   var-list. Per var: `:seon.fn/spec` ← `(some-> (:malli/schema (meta v)) m/form
   pr-str)` (ABSENT = unspecced); `:seon.fn/doc` ← var meta; `:seon.fn/source` ←
   file-read at `:file`/`:line` (paren-balance); `:seon.fn/arglists` ← parsed from
   that REAL source, not the mangled var meta.
3. This **DELETES** `core-fn-curated` + `synthesize-fn-source` + the dead
   `_compile-state` arg, replaces the WRONG hardcoded `:seon.fn/specced? false`
   boolean with the real `:seon.fn/spec` form, and makes **bootstrap-cache
   expansion UNNECESSARY** (file-read covers both source AND real arglists
   without pulling datahike-cljs's analyzer subtree into `out/bootstrap`).
4. **`:seon.test` becomes a real entity kind** (~70% already wired) so tests land
   in `entity-schema-keys`, persist as `:seon.schema`, and render per-kind.
5. **Permissive persistence + honest gaps**: any valid Clojure that EVALS
   persists (no spec/test gate); "no spec" = `:seon.fn/spec` absent; "untested" =
   no `:seon.test` row — both queryable. The substrate becomes an accurate,
   self-describing, RESUMABLE corpus read from live source.

The same hybrid serves agent-authored fns: detect-and-tee already has live
analyzer state and gets source from the submitted eval string. Two source
channels (file-read for substrate, eval-string for agent fns), ONE `:seon.fn`
shape — downstream readers never branch on origin.

---

## 2. Current state — the two indexing paths and their problems

### Path A — agent-fn indexing (the GOOD path: detect-and-tee)

`build-tee-entities` (`src/seon/eval.cljs:715`) runs after every agent eval. It
diffs the bootstrap analyzer state before/after the form and, for each new/redef
`def`, calls `analyzer-info/var-projection` (`src/seon/analyzer_info.cljs:152`).

Captured accurately from the analyzer var-map: `:seon.fn/sym`,
`:seon.fn/arglists` (quote-stripped, `analyzer_info.cljs:168`), `:seon.fn/doc`,
`:seon.fn/private?`, `:seon.fn/fn-var?`, and `:seon.fn/source` (the exact eval'd
form text — REAL and replayable). It also tees `:seon.test/sym`+`ns`+`source`
for every new `deftest` (`eval.cljs:781`).

**The one gap on this path:** `:seon.fn/specced?` keeps a BOOLEAN
(`analyzer_info.cljs:176`, `(some? (:malli/schema meta))`) and **discards the
schema FORM**. The exact contract never enters the corpus.

### Path B — substrate-fn indexing (the HACKY path: curated table)

`seed-core-fns!` (`client.cljs:660`) seeds substrate fns at boot from a
hand-written `core-fn-curated` table of 7 entries (`client.cljs:611`). For each:

- `:seon.fn/source` = `synthesize-fn-source` (`client.cljs:644`) — a `(defn …)`
  shell with a `,,,` placeholder body. NOT real source, NOT replayable.
- `:seon.fn/arglists`, `:seon.fn/doc` = whatever was typed in the table.
- `:seon.fn/specced?` = **hardcoded `false`** (`client.cljs:689`) for ALL 7.

**The spec bug (REPL-verified live, 2026-06-08):**

```clojure
(:malli/schema (meta #'seon.db/transact!))  ; => [:=> [:cat :seon.db/transact-request] :seon.db/transact-response]
(:malli/schema (meta #'seon.db/query))      ; => [:=> [:cat :seon.db/query-request] :any]
(:malli/schema (meta #'seon.schema/register!)) ; => nil   ; correctly unspecced

```

So `transact!`/`query`/`pull`/`entity` ARE specced — the curated table mislabels
all of them as unspecced. The readers (`handlers/fn.cljs:108,166`; `fn-block-ai`
at `agent.cljs:1349,1357`; the `pull-ns-data` pattern at `agent.cljs:1327`)
therefore mis-report every core DB fn. (Note: `capabilities-section`,
`agent.cljs:1164`, reads only `:sym`/`:arglists`/`:doc` — it is NOT a `specced?`
reader. The prior PRD pass overstated it.)

### Why only 7 fns / the bootstrap-cache constraint

The substrate `.cljs` compiles into the `:client` pod bundle
(`out/client/main.js`); its functions RUN but its ANALYZER STATE is not in the
runtime compile-state unless the ns is a `:bootstrap :entries` member. Live
`:cljs.analyzer/namespaces` has 55 nses; the only seon ones are `seon.schema`,
`seon.dynamic`, and the agent's own home ns. **`seon.db` and `seon.agent` are
absent** — so `seed-core-fns!`'s `_compile-state` arg is dead (`client.cljs:673`)
and the analyzer cache can't see `seon.db`.

The build-side research (`research/cljs-bootstrap-cache-2026-06-08.md`) priced
the alternative: adding `seon.db` to `:bootstrap :entries` (`shadow-cljs.edn`,
the `:bootstrap` build) pulls its ENTIRE transitive subtree (datahike-cljs,
konserve, persistent-sorted-set, hitchhiker-tree…) into `out/bootstrap` — a
heavy build/boot cost for a PARTIAL win (real arglists + whole-ns source, but
still no per-fn source, and the spec STILL has to come from var meta). **The
file-read mechanism below makes this expansion entirely unnecessary** (§3.4).

### Source IS recoverable — the resolved fact (REPL-verified live)

The analyzer cache has no source key (confirmed in
`cljs.analyzer/analyze-def`, 1.12.145 `analyzer.cljc:2122-2175`: the init-expr
AST is analyzed for summary fields then DISCARDED). But var meta carries
`:file`+`:line` for every substrate fn, surviving instrumentation:

```clojure
;; live, 2026-06-08:
(:file (meta #'seon.db/transact!))  ; => "seon/db.cljs"
(:line (meta #'seon.db/transact!))  ; => 878   (instrumented; :arglists is ([arg]))
(:arglists (meta #'seon.db/query))  ; => ([{:seon.db/keys [query args db conn] :or {conn *conn* args []}}])  (intact)

;; pod is Node; cwd is repo root; the file is there; :line lands on the defn:
(let [fs (js/require "fs") m (meta #'seon.db/transact!)
      file (str (.cwd js/process) "/src/" (:file m))]
  [(.cwd js/process) (.existsSync fs file)
   (nth (clojure.string/split-lines (.readFileSync fs file "utf8")) (dec (:line m)))])
;; => ["/Users/sean/src/seon" true "(defn ^:async transact!"]

;; spec round-trips cleanly to the :seon.fn/spec string:
(malli.core/form (malli.core/schema (:malli/schema (meta #'seon.db/transact!))))
;; => [:=> [:cat :seon.db/transact-request] :seon.db/transact-response]

```

This is the `cljs.repl/source-fn` pattern: resolve var → take `:file`+`:line` →
read the file → extract one form. `cljs.repl/source-fn` (`repl.cljc:1508-1531`)
reads from the FILE, not the cache, precisely because the cache has no source.
`(resolve sym)` on a runtime symbol does NOT work in self-host (it's a
compile-time macro) — so the indexer must use `#'`-literal var refs.

### Resume path (`client.cljs:495`) and its fragility

`query-program-graph-entries` (`client.cljs:438`) reads currently-asserted
`:seon.ns/source`/`:seon.fn/source`/`:seon.schema/source` (or-join, tx-sorted);
`replay-one!` (`client.cljs:464`) RE-EVALS each. It does NOT handle `:seon.test`.
For curated substrate fns it would re-eval the `,,,` stub and DEFINE A BROKEN
`transact!` shadowing the compiled one. The real fn survives today only because
replay runs BEFORE substrate atoms re-win a last-write race (the ordering hack at
`client.cljs:506-509`). Once substrate fns carry real-but-non-replayable source
and are skipped in replay, that hack goes away.

### `:seon.test` — confirmed ~70% wired

- Attrs ARE registered (`seon.test.runner.cljs:120-131`): `sym` (identity),
  `last-passed-at`, `last-failed-at`, `last-failure-summary`, `last-run-id`,
  `source`, `ns` (`:seon.db/ref`), `created-at`. (`(schema/registered?
  :seon.test/sym)` => true.)
- Result ROWS already exist at runtime (written by `seon.test.runner/run!`).
- Detect-and-tee already WRITES `:seon.test/sym`+`ns`+`source` for agent
  deftests (`eval.cljs:781`).
- **MISSING:** the `:seon.test` entity-KIND `:map` schema (with `:seon.render/ai`
  / `:seon.render/html`). `(schema/registered? :seon.test)` => false; not in
  `entity-schema-keys` (`schema.cljc:345`); no per-kind render handler; and the
  `pull-ns-data` test-pull is defensively try/catch-wrapped (`agent.cljs:
  1330-1340`) with a STALE comment claiming `:seon.test/ns` "isn't in the conn's
  schema" — it IS registered.

---

## 3. The resolved design — ONE indexing mechanism

**Principle (CLAUDE.md "Code as data"):** there is exactly one way an entity
enters the corpus — a structured projection plus a source string. For agent fns
the source is the submitted eval string + analyzer var-map. For compiled
substrate fns the analyzer state is absent, so source + arglists come from a FILE
READ keyed on var meta `:file`/`:line`, and spec + doc from var meta. Both feed
the SAME `:seon.fn` shape; no third representation, no hand-typed table.

### 3.1 `:seon.fn/spec` (string) replaces `:seon.fn/specced?` (boolean)

`:seon.fn/spec` = the `pr-str` of `(m/form (m/schema (:malli/schema (meta v))))`.
PRESENT = specced (and the exact contract is in the corpus); ABSENT = unspecced.
`:seon.fn/schema-error` stays — orthogonal (an unparseable schema is present);
when set, omit `:seon.fn/spec`.

### 3.2 The hybrid indexer `index-substrate!`

Replaces `core-fn-curated` + `synthesize-fn-source` + `seed-core-fns!`. Over a
compile-time `#'`-literal var-list, per var build a `:seon.fn` row:

| `:seon.fn/*` field | Source | Reliable? |
| --- | --- | --- |
| `sym` | `(str (:ns m) "/" (:name m))` from `(meta v)` | YES |
| `spec` | `(some-> (:malli/schema m) m/schema m/form pr-str)` | YES — exact form; ABSENT = unspecced |
| `doc` | `(:doc m)` | YES |
| `source` | file-read + paren-balance at `(:file m)`/`(:line m)` | YES — REAL source, handles instrumented fns |
| `arglists` | parsed from the extracted source (the real arglists live in the text) | YES — even when var-meta arglists are mangled |
| `fn-var?` / `private?` | `(meta v)` | YES |

The paren-balance extractor (reader-free, tracks string/escape state so docstring
parens don't unbalance; tested live in the introspection research §App) walks
from the `:line` start to the matching close paren. Production hardening: also
skip `;`-to-EOL comments and `\(`/`\)` char literals; assert `balanced?` once per
var (the var-list is a fixed small set).

### 3.3 `:seon.test` entity kind

Register the `:seon.test` `:map` (only `:sym` required; the rest optional so both
result rows and detect-tee source rows validate, merging by `:seon.test/sym`
identity) with `:seon.render/ai`/`:seon.render/html`. Add a `handlers/test.cljs`
render handler mirroring `handlers/fn.cljs`. This lands `:seon.test` in
`entity-schema-keys`, persists it as a `:seon.schema`, and renders it per-kind.

### 3.4 Permissive persistence + honest gaps + cache deferred

Detect-and-tee ALREADY persists any evaling `def`/`defn`/`register!`/`deftest`
(`eval.cljs:728-792`) with no spec/test precondition — keep that. The corpus
marks "missing" by ABSENCE: no `:seon.fn/spec` → `⚠ unspecced` (derived
`(nil? spec)`); no `:seon.test` row referencing the fn → untested. Self-healing
per reactive-context: add the spec → next index carries `:seon.fn/spec` → the
marker vanishes. Nothing to acknowledge.

**Bootstrap-cache expansion is moot.** The file-read path recovers BOTH real
source and real arglists for substrate fns without adding nses to `:bootstrap
:entries`. Mark it explicitly deferred/unneeded (§9).

---

## 4. Step 1 — `:seon.fn/specced?` → `:seon.fn/spec` (DEMO-CRITICAL)

**The change.** Swap the boolean attr for the schema-form string everywhere; no
legacy boolean may remain in `src/`.

Schema:

- DELETE `(schema/register! :seon.fn/specced? :boolean)` (`agent.cljs:264`).
- ADD `(schema/register! :seon.fn/spec :string)` — the `m/form` string.
- In the `:seon.fn` `:map` (`agent.cljs:335`, the entry at `:348`), replace
  `[:seon.fn/specced? {:optional true} :seon.fn/specced?]` with
  `[:seon.fn/spec {:optional true} :seon.fn/spec]`.
- Update the `::var-projection` schema (`analyzer_info.cljs:50,57`):
  `[:specced? :boolean]` → `[:spec {:optional true} :string]`.

Writers:

- `var-projection` (`analyzer_info.cljs:152,176`): `:specced? (some? schema)` →
  `:spec (some-> schema m/schema m/form pr-str)`; on parse failure emit no
  `:spec` and let the caller set `schema-error`.
- `build-tee-entities` (`eval.cljs:715,731-769`): derive `:seon.fn/spec` from the
  projection; keep the `schema-error` guard; when `schema-error`, OMIT
  `:seon.fn/spec`. Replace `:seon.fn/specced? effective-specced?` (`eval.cljs:
  761`) with the conditional `:seon.fn/spec`.
- `index-substrate!` (Step 2) sets `:seon.fn/spec` from `(meta v)`.

Readers:

- `handlers/fn.cljs:108,166`: `specced (boolean (:seon.fn/specced? entity))` →
  `spec (:seon.fn/spec entity)` / `specced (some? spec)`. Status pill
  (`fn.cljs:178`) and `:ai` status-line (`fn.cljs:117-123`) keep glyph logic on
  `(some? spec)`; the `:ai` render MAY additionally show the real spec form.
- `fn-block-ai` (`agent.cljs:1349,1357`): destructure `spec` instead of
  `specced?`; `(not specced?)` → `(nil? spec)`; render the spec form when present.
- `pull-ns-data` pull-pattern (`agent.cljs:1327`): swap `:seon.fn/specced?` for
  `:seon.fn/spec`.
- Test fixture `test/seon/agent_render_namespace_test.cljs:53`
  (`:seon.fn/specced? false`) → drop the key (absence = unspecced) or assert
  `:seon.fn/spec`.

**Acceptance criteria (verifier-checkable):**

- `grep -rn 'specced?' src/` returns ZERO hits except the local `let`-binding
  name inside `build-tee-entities` if any remains (preferably none).
- A freshly tee'd specced agent fn (e.g. `(defn ^{:malli/schema [:=> [:cat :int]
  :int]} f [x] x)` via `seon.eval/eval`) has `:seon.fn/spec` =
  `"[:=> [:cat :int] :int]"` on its persisted row; an unspecced fn has the key
  ABSENT.
- `(schema/registered? :seon.fn/spec)` => true; `(schema/registered?
  :seon.fn/specced?)` => false.

**Single most important criterion:** zero `:seon.fn/specced?` in `src/`, and a
specced tee'd fn carries the exact `m/form` string.

---

## 5. Step 2 — `index-substrate!` (the heart) (DEMO-CRITICAL)

**The change.** Delete `core-fn-curated` (`client.cljs:611-642`),
`synthesize-fn-source` (`client.cljs:644-658`), and `seed-core-fns!`
(`client.cljs:660-691`, incl. the dead `_compile-state` arg). Add:

- `substrate-vars` — a `def ^:private` vector of `#'`-literals:
  `[#'seon.db/transact! #'seon.db/query #'seon.db/pull #'seon.db/entity
  #'seon.db/current-agent-id #'seon.db/new-id! #'seon.schema/register!
  #'seon.test.runner/run!]` (grow as vocabulary grows).
- `extract-form-at-line` — the reader-free paren-balance extractor (introspection
  research §App), tracking string/escape state.
- `index-substrate!` — for each var: `(meta v)` → `:seon.fn/spec` (via `m/form`),
  `:doc`, `:sym`, `:fn-var?`, `:private?`; file-read at `:file`/`:line` →
  `:seon.fn/source` + `:seon.fn/arglists` (parsed from the extracted text). Emit a
  `:seon.ns/name`+`:seon.ns/source` row per owning ns so the `[:seon.ns/name kw]`
  lookup-ref on `:seon.fn/ns` resolves. Returns tx-data; caller transacts.
- Wire into `start-agent!` (replace the `seed-core-fns!` call) — runs at boot
  BEFORE `replay-program-graph!`.

Resolve `:file` ("seon/db.cljs") against `(str (.cwd js/process) "/src/" file)`.
Mark substrate rows with the existing `:seon.db/origin :substrate-seed` tx-meta
(consumed by Step 4's replay skip).

**Acceptance criteria:**

- `core-fn-curated`, `synthesize-fn-source`, `seed-core-fns!` are GONE from
  `client.cljs`; no `,,,` stub string is persisted anywhere.
- After boot, pulling `[:seon.fn/sym "seon.db/transact!"]` shows `:seon.fn/spec`
  = `"[:=> [:cat :seon.db/transact-request] :seon.db/transact-response]"`,
  `:seon.fn/source` starting `"(defn ^:async transact!"`, and `:seon.fn/arglists`
  reflecting the REAL destructuring map-in shape (NOT `([arg])`).
- `seon.db/query`/`pull`/`entity` carry their real `:seon.fn/spec`;
  `seon.schema/register!` and `seon.db/current-agent-id` carry NO `:seon.fn/spec`.
- The persisted `:seon.fn` shape is identical (same keys) to a detect-and-tee
  row; `handlers/fn.cljs`/`render-namespace` render substrate and agent fns with
  no origin branch.

**Single most important criterion:** `transact!`'s persisted row has the real
`:seon.fn/spec` form AND real source/arglists (`(defn ^:async transact!` …, not
`([arg])` / not `,,,`).

---

## 6. Step 3 — `:seon.test` entity kind (DEMO-CRITICAL)

**The change.** Next to the `:seon.test/*` attrs (`test/runner.cljs:120-131`)
register the entity kind:

```clojure
(schema/register! :seon.test
  [:map {:seon.render/ai   'seon.handlers.test/render-ai
         :seon.render/html 'seon.handlers.test/render-html}
   [:seon.test/sym :seon.test/sym]
   [:seon.test/ns                   {:optional true} :seon.test/ns]
   [:seon.test/source               {:optional true} :seon.test/source]
   [:seon.test/last-passed-at       {:optional true} :seon.test/last-passed-at]
   [:seon.test/last-failed-at       {:optional true} :seon.test/last-failed-at]
   [:seon.test/last-failure-summary {:optional true} :seon.test/last-failure-summary]
   [:seon.test/last-run-id          {:optional true} :seon.test/last-run-id]
   [:seon.test/created-at           {:optional true} :seon.test/created-at]])

```

Add `src/seon/handlers/test.cljs` mirroring `handlers/fn.cljs`: `render-ai` =
`[test sym] <pass/fail glyph>` + clipped source; `render-html` = a card with sym,
pass/fail pill, collapsible source. Prefer this per-kind handler over the inline
`test-block-ai` (`agent.cljs:1383`) to avoid divergence (delegate or remove the
inline one).

Agent deftests already tee `:seon.test/sym`+`ns`+`source` (`eval.cljs:781`); once
the kind exists they render and attach to their ns — no new write code.

**Acceptance criteria:**

- `(schema/registered? :seon.test)` => true; `:seon.test` appears in
  `(entity-schema-keys)`.
- A tee'd agent `deftest` (via `seon.eval/eval`) renders in `render-namespace`
  output for its ns and persists a `:seon.schema` entity for `:seon.test`.
- `src/seon/handlers/test.cljs` exists with `render-ai`/`render-html`; CLJS build
  has 0 warnings.

**Single most important criterion:** `:seon.test` is in `entity-schema-keys` and
an agent deftest renders under its namespace via the per-kind handler.

---

## 7. Step 4 — resume + cleanup (DEMO-CRITICAL)

**The change.**

- **Replay agent `:seon.test` source.** Add a `:seon.test/sym`+`:seon.test/source`
  branch to the `or-join` in `query-program-graph-entries` (`client.cljs:445-456`)
  and a `:test` case to `target-ns-for-entry`/`replay-one!` (`client.cljs:464`),
  so agent deftests reconstitute on resume alongside fns/schemas. Result rows
  (`last-*`) are NOT source and are NOT replayed.
- **Substrate fns are no-replay.** Their `:seon.fn/source` is real but
  descriptive — re-evaling it would shadow the compiled fn. Skip substrate rows
  in `query-program-graph-entries` (filter rows whose owning ns is a
  compiled-substrate ns, or whose `:seon.db/origin` is `:substrate-seed`).
  Substrate is re-indexed by `index-substrate!` on every boot — no replay needed.
- **Drop the last-write-race hack.** With substrate fns excluded from replay, the
  `,,,`/ordering hack (`client.cljs:506-509` docstring + the boot-ordering it
  describes) is no longer load-bearing — remove/simplify it.
- **Fix the stale comment.** `pull-ns-data` (`agent.cljs:1330-1340`) — drop the
  defensive try/catch wrapping (or keep one narrow try for empty-result
  cleanliness) and fix the comment: `:seon.test/ns` IS registered.

**Acceptance criteria:**

- A same-pod replay probe (or stop+restart) reconstitutes agent fns AND agent
  deftests from real source; substrate fns remain the compiled originals (NOT
  shadowed by replayed stubs — there are no stubs).
- `query-program-graph-entries` returns no substrate `:seon.fn` rows for replay.
- The `,,,`/race comment+hack is gone from `client.cljs`; `pull-ns-data`'s
  comment no longer claims `:seon.test/ns` is unregistered.

**Single most important criterion:** after resume, agent deftests are back AND
substrate `transact!` is still the real compiled fn (no broken stub, no race).

---

## 8. Step 5 — `run!` writes `:seon.test/ns` (NICE-TO-HAVE)

**The change.** `seon.test.runner/run!` upserts pass/fail onto the
`:seon.test/sym` identity but omits `:seon.test/ns`. Have it also assert
`:seon.test/ns` (derivable from the test sym's namespace) so runner result rows
attach under their namespace in `render-namespace`, merging with detect-tee
source rows on identity.

**Acceptance criterion:** after a `run!`, the result row carries `:seon.test/ns`
and appears under its ns in `render-namespace`.

---

## 9. Step 6 — bootstrap-cache expansion (DEFERRED / UNNEEDED)

The build-side research priced adding `seon.db`/`seon.agent` to `:bootstrap
:entries` (`shadow-cljs.edn`) as a heavy datahike-subtree rebuild that still
yields no per-fn source and still needs var meta for specs. **The file-read
mechanism (Step 2) makes this entirely unnecessary** — it recovers real source
AND real arglists for substrate fns at near-zero cost. Mark moot. Revisit only if
the corpus ever needs whole-ns `:seon.ns/source` for substrate nses on a rendered
view; if so, also rebuild the currently-stale `out/bootstrap` (last built May 24;
`seon.schema.cljc` is ~3 weeks ahead) and measure boot cost first.

---

## 10. Risks

- **Instrumentation / arglists.** Var-meta `:arglists` is mangled (`([arg])`) for
  instrumented fns; the design SIDESTEPS this by reading arglists from the
  file-extracted source, not var meta. Risk reduces to "paren-balance extracts a
  correct, balanced form" — mitigated by asserting `balanced?` per var over the
  fixed var-list, and by the extractor's string/escape handling for docstring
  parens. Hardening: skip `;`-comments and `\(`/`\)` char literals.
- **Stale on-disk bootstrap cache.** `out/bootstrap` is ~3 weeks stale. The
  design AVOIDS depending on it (var meta + file-read are always current), so the
  staleness is not a correctness hazard for this work — but it is a latent hazard
  for any OTHER consumer trusting the cache; flag for a separate rebuild.
- **Blast radius.** The `:seon.fn/specced?` → `:seon.fn/spec` swap touches schema
  registration, two writers, three reader sites, and a test fixture across
  `agent.cljs`, `analyzer_info.cljs`, `eval.cljs`, `handlers/fn.cljs`,
  `client.cljs`, and one test. It's an atomic in-place rename (no parallel attr) —
  do it in one patch, grep `specced?` to zero, run targeted tests, then the full
  suite once at the end. `:file`/`:line` are runtime-resolved per boot; if a
  substrate fn moves, re-indexing picks up the new location automatically (no
  stored line numbers to drift).

---

## 11. Success criteria (whole effort)

- `transact!`/`query`/`pull`/`entity` carry the REAL `:seon.fn/spec` form +
  REAL source/arglists; `register!`/`current-agent-id` carry no spec (ABSENT =
  unspecced). No `:seon.fn/specced?` remains in `src/`.
- ONE indexing mechanism: `index-substrate!` (file-read) + detect-and-tee
  (eval-string) feed the SAME `:seon.fn` shape; `core-fn-curated`/
  `synthesize-fn-source`/`seed-core-fns!` deleted; no `,,,` stub.
- `:seon.test` is an entity kind in `entity-schema-keys`, persists as a
  `:seon.schema`, renders per-kind; agent deftests index + render under their ns.
- Permissive persistence unchanged; "no spec"/"no test" shown by absence,
  self-healing.
- Resume reconstitutes agent fns + tests from real source; substrate fns
  re-index from var meta + file; the last-write race hack is gone. Full suite
  green, 0 CLJS warnings.
