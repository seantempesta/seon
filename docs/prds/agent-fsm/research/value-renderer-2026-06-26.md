---
type: research
status: draft
tags: [research, agent, web]
---

# Eval-result value renderer — structural sampling + the render-twin per value (2026-06-26)

## TL;DR

Every eval result should render on two surfaces, both fns of the raw value:

- `render-ai` — a CLIPPED but STRUCTURE-REVEALING text skeleton the agent
  navigates with ordinary Clojure (`(get-in result/<id> …)`, filter, count)
  WITHOUT re-querying. It keeps every map key + vector index intact, annotates
  each node with type + count, marks elision (`… +129 more`), is lazy-safe, and
  projects opaque handles to compact tokens.
- `render-html-data` — a PLAIN-DATA contract the interactive HTML drill-down
  panel (UI session U's lane) consumes. Same skeleton + the `eval-id` handle so
  the panel can expand a node by path against the live `result/<id>` value.

The current live path (`seon.eval/render-result-edn`) does `project-agent-safe`
→ `pr-str` → CHAR-clip. The char-clip is the defect: it slices EDN mid-token
(invalid, unparseable) and destroys the navigation metadata the agent needs to
build a `get-in` path. The fix is a depth- AND breadth-bounded structural
SAMPLER, exactly the blueprint in the source research.

Prototype shipped + tested (22 behavioral tests, green under `bin/test-cljs`):

- `src/seon/render/value.cljs` — `sample` / `render-ai` / `render-html-data`
- `test/seon/render/value_test.cljs`
- `scratchpad/sampler.clj` — the babashka iteration harness (before/after below)

DESIGN + PROTOTYPE only. NOT cut over the live eval path yet (reviewed first).

---

## 1. The source research, mined

`Clojure REPL Data Sampling.md` compares the battle-tested approaches. The
techniques worth adopting, and seon's current gap:

| Technique (from the research) | What it buys | seon today |
| --- | --- | --- |
| **Non-destructive structural sampler** (depth + breadth bounded skeleton, `*print-level*`-style but PRESERVING types/keys) | valid output, nav paths intact, bounded tokens | ❌ char-clip slices EDN mid-token |
| **Depth limit → typed marker** (`{…}`/`[…]` not `#`) | agent still sees "a 12-key map lives here", can drill | ❌ no depth bound at all (full `pr-str`) |
| **Breadth limit at EVERY level** + "N more" elision | bounds wide colls anywhere in the tree | ⚠️ only ONE level (`result-row-cap` top-level / map-of-coll) |
| **Per-node type + count annotation** | shape obvious without reading values | ❌ none |
| **Lazy-safe head sampling** (`take n+1`, never realize the tail) | infinite/huge seqs safe | ⚠️ `pr-str` realizes whatever it's handed |
| **Long-string clip with length** (`"head…"⟨len⟩`) | size visible, no wall of text | ⚠️ char-clip only, length lost |
| **Metadata retention** | reflection/dispatch survive | n/a for display |
| **Schema inference** (`malli.provider`) — abstract a huge homogeneous payload to its shape | extreme token efficiency for uniform data | ❌ — BUT it DROPS values + paths |
| **Interactive navigator** (`datawalk`) — drill-down + path tracking | human/agent exploration | the HTML panel's job (U) |

Key decision the research forces: **schema inference (`malli.provider`) is the
most token-efficient, but it abstracts away the concrete values AND the
navigation paths** — which is exactly what requirement (2) needs to keep. So the
PRIMARY technique is the non-destructive sampler (paths preserved). We adopt the
schema-inference idea in a SCOPED form: for a homogeneous collection of maps, the
elision marker carries the shared KEY-SET — `… +129 more each {:a :b :c}` — the
"column set" of a 137-row query result without scrolling 137 rows. Best of both:
concrete sample rows AND the aggregate shape, paths intact.

Not adopted: `*print-level*`/`*print-length*` (destructive — replaces nodes with
`#`, kills types/paths, and is a global dynamic var that cross-wires tooling);
`zprint`/`fipp` (a prettier printer of the WHOLE value — doesn't bound it;
orthogonal, could format the skeleton later); `specter`/`meander` (query engines,
require knowing the shape first — that's what we're trying to reveal).

---

## 2. The two-fn design

### `sample` — value → bounded skeleton (the shared core)

Depth + breadth bounded walk producing PLAIN DATA + reserved-namespace marker
maps. Bounds (all env-overridable for token economy):

```
:max-depth 3   :max-keys 8   :max-items 8   :max-string 80   :shape-sample 8

```

Marker vocabulary (this IS the html data contract):

```clojure
{:seon.render.value/kind :vector|:set|:seq
 :seon.render.value/shown  [...]              ; bounded element sample
 :seon.render.value/elided n | :more          ; tail count (:more = lazy/unknown)
 :seon.render.value/shape  [:k …]}            ; shared keys IF homogeneous maps
{<k> <v> … :seon.render.value/elided-keys n}  ; map with elided key tail
{:seon.render.value/pruned :map|:vector|:set|:seq
 :seon.render.value/count  n}                  ; depth-limit prune
{:seon.render.value/string-len n :seon.render.value/head "…"}
{:seon.eval/opaque "datahike/DB" :seon.eval/summary "max-tx=42"}   ; reuse eval's keys
{:seon.eval/datom [e a v]}

```

Opaque detection is per VISITED node only (a giant value is never fully walked):
datahike DB/Entity (ILookup `:max-tx`/`:db/id`), Datom (e/a/v shape), record, raw
JS object, fn → compact token. Lazy safety: `(take (inc max-items) coll)` realizes
at most `max-items`+1 elements; counted colls report the exact elided tail,
uncounted report `:more`.

### `render-ai` — skeleton → agent text

Emits the skeleton with **inline-if-fits** layout (small collections on one line,
large ones break one child per line — the single biggest readability win). The
skeleton goes FIRST so it composes cleanly behind the transcript's `;=>` prefix
(no `;=> ;;` double-comment). ONLY when the view is partial, ONE trailing `;`
hint folds the top-level type/count + a drill pointer at `result/<id>`. Whole
output is valid Clojure comment prose — no backticks, no fences.

### `render-html-data` — skeleton → U panel data contract

```clojure
{:seon.render.value/eval-id    "<id>"        ; the live-var handle
 :seon.render.value/summary    "map 12 keys" ; one-line header
 :seon.render.value/truncated? true          ; partial view?
 :seon.render.value/tree       <skeleton>}   ; same plain-data skeleton

```

PLAIN DATA only — no hiccup, no web classes (U's lane). See §5 for the U ask.

---

## 3. Before / after on real shapes

Prototyped + run in babashka (`scratchpad/sampler.clj`), shown as they land in the
transcript (`;=> <first-line> ; result/<id>` + continuation lines).

### Deep nested map (logs seq, roles set, long bio, top-level meta)

BEFORE (current `pr-str`, then char-clipped mid-token):

```
#:api{:response-id #uuid "97bda…", :status :stable, :results [#:user{:id 1, :name
"John Doe", :bio "A senior systems developer specializing in the integration of
asynchronous telemetry services.", :roles #{:admin :moderator :billing :auditor
:editor}, :logs ({:event "login", :ip "127.0.0.1"} {:event "update", … …[790 chars]

```

AFTER:

```clojure
;=> {:api/debug-log "Initializing database connection pool... Successful. Establ…"⟨98 chars⟩ ; result/auC
  :api/response-id #uuid "97bda55b-6175-4c39-9e04-7c0205c709dc"
  :api/results [{:user/bio "A senior systems developer specializing in the integr…"⟨94 chars⟩
      :user/id 1
      :user/logs (…3 items)
      :user/name "John Doe"
      :user/roles #{…5 items}}
    {:user/bio "A database administrator and expert in persistent trie conf…"⟨70 chars⟩
      :user/id 2
      :user/logs (…1 items)
      :user/name "Jane Smith"
      :user/roles #{…1 items}}
    {:user/bio "A cloud infrastructure engineer."
      :user/id 3
      :user/logs ()
      :user/name "Alice Johnson"
      :user/roles #{…1 items}}]
  :api/status :stable}
; ‹partial view of map 4 keys› — the COMPLETE value is result/auC  (get-in result/auC […]) · filter · count · take/drop

```

The agent can read off `[:api/results 0 :user/logs]` and run
`(get-in result/auC [:api/results 0 :user/logs])` to expand the pruned seq — the
path is exact because every key survived.

### Query result — 137 homogeneous maps (the common `db/query` case)

BEFORE: `[#:seon.fn{:name fn-0, :ns :seon.agent, :arity 0} … …[6878 chars]` — 137
rows, char-clipped after ~50.

AFTER:

```clojure
;=> [{:seon.fn/arity 0, :seon.fn/name fn-0, :seon.fn/ns :seon.agent} ; result/qrY
  {:seon.fn/arity 1, :seon.fn/name fn-1, :seon.fn/ns :seon.agent}
  {:seon.fn/arity 2, :seon.fn/name fn-2, :seon.fn/ns :seon.agent}
  … (8 shown) …
  … +129 more each {:seon.fn/arity :seon.fn/name :seon.fn/ns}]
; ‹partial view of vector 137 items› — the COMPLETE value is result/qrY  (get-in result/qrY […]) · filter · count · take/drop

```

`… +129 more each {…}` — the agent sees the COLUMN SET of all 137 rows without
reading 137 rows. This is the scoped schema-inference win.

### Other shapes (all verified, none hang)

| Shape | After (condensed) |
| --- | --- |
| set of 80 tuples | `#{[178 :a78] [169 :a69] … (8) … … +72 more}` |
| long string (400) | `"xxxx…"⟨400 chars⟩` |
| heterogeneous vec | `[1 "two" :three four 5.0 nil true [:nested 1] … +1 more]` |
| deep vec (depth 7) | `[1 [2 [3 […2 items]]]]` |
| wide map (15 keys) | 8 entries + `… +9 more keys` |
| **infinite `(range)`** | `(0 1 2 3 4 5 6 7 … +more)` — head+1 realized, **no hang** |
| datahike DB handle | `#‹datahike/DB max-tx=42›` |
| vector of datoms | `[#datom[42 :user/name "Jane"] #datom[43 :user/age 30]]` |
| small `[1 2 3]` | `[1 2 3]` — verbatim, **no hint** (fully shown) |
| all-empty colls | `{:items [], :meta {}, :tags #{}}` — verbatim, no hint |

The scaffolding (type banner folded into the hint, drill pointer) appears
PRECISELY when something is clipped — a fully-shown small value reads exactly like
a REPL echo.

---

## 4. Integration point

`seon.eval/render-result-edn` (src/seon/eval.cljs ~L2128) is THE write-side
producer of `:seon.eval/result-edn` — the AI text every eval row stores. The
read-side composer `seon.ctx/format-eval-row` (src/seon/ctx.cljs ~L565) renders it
as `;=> <body> ; result/<id>` and is the place the `result/<id>` handle is
attached.

Cutover (single in-place edit, no v2):

1. `render-result-edn` internals — `project-agent-safe` → row-cap-preview →
   `pr-str` → `clip-result-body` — are REPLACED by `(seon.render.value/render-ai
   eval-id value)`. Same signature, same return (a string), same caller.
2. The opaque-detection helpers duplicated in the prototype
   (`datahike-handle?` / `opaque-marker`, mirroring eval's private
   `datahike-handle?` / `opaque-summary`) collapse to ONE copy: they MOVE into
   `seon.render.value` and `seon.eval`'s `project-agent-safe` /
   `sanitize-result-edn` require them. Dependency edge is one-way
   (`seon.eval` → `seon.render.value`), no cycle (verified: `seon.render.value`
   requires only `clojure.string` + `seon.platform`).
3. `result/<id>` stays CLEARLY shown — `format-eval-row` is UNTOUCHED; it still
   appends ` ; result/<id>` to the `;=>` line, and the new body's first line is
   the skeleton's opening (no banner steals the `;=>` slot).
4. HTML: register `seon.render.value/render-html-data` as the `:seon.eval`
   entity-kind's render path so the inspector's eval rows get the drill-down
   panel (U builds the widget from the data contract).

The store-time `cap-edn` (anti-OOM per-datom ceiling) and the read-time
`cap-result-body` (display cap, names `result/<id>`) stay as BACKSTOPS — the
sampler keeps the body tiny, so they become no-ops in the common case, but they
still bound a pathological scalar.

One open item to confirm at cutover: `:seon.eval/result-edn` is currently a
near-EDN string consumers may re-read (`sanitize-result-edn` re-reads legacy
`#datahike/...` dumps). The structural skeleton is NOT round-trip EDN (markers use
glyphs). That is fine for the agent (it reads it as a comment) but the
`sanitize-result-edn` re-read net only fires on the legacy-dump substring screen,
so new skeleton strings pass through untouched. Confirm no other consumer parses
`:seon.eval/result-edn` as data (grep clean as of this note).

---

## 5. Needs → UI/UX (session U): the interactive value panel

`render-html-data` returns the DATA CONTRACT above; the interactive widget is U's
lane (do not touch `src/seon/web/**`). The ask:

- **Build a collapsible drill-down browser** over `:seon.render.value/tree`. Each
  marker is a collapsible affordance:
  - `:seon.render.value/pruned` / `:elided` / `:elided-keys` → an expand control;
  - `:seon.eval/opaque` / `:datom` → a compact tagged chip (DB/Datom/Entity);
  - `:string-len` → a clamped string with a "show full (N chars)" toggle.
- **Path-based lazy expansion.** A node's `get-in` PATH is reconstructable from its
  position in the tree (map keys + `:shown` index). Expanding a pruned/elided node
  is a fresh server call `(seon.render.value/sample (get-in result/<id> path)
  {deeper opts})` — i.e. U needs a `/call`-style endpoint that takes `{eval-id,
  path}` and returns a one-level-deeper sub-tree. The live value is the
  `result/<id>` stash (`seon.eval/lookup-result`); for a PRIOR-SESSION eval the
  live value is gone — fall back to the persisted skeleton (no deeper expansion).
- **Header** from `:seon.render.value/summary`; show `:truncated?` as a "partial
  view" badge with a "copy `result/<id>`" affordance so the human can hand the
  handle back to the agent.
- **Phosphor Terminal theme** (warm blacks, amber accents, monospace, density over
  whitespace) per `docs/prds/namespace-ui/design-system.md`.

Coordination: this ns commits only PLAIN-DATA producers; U owns the hiccup, the
SSE wiring, and the expansion endpoint. The contract (the marker vocabulary) is
the interface — change it in lockstep.

---

## 6. Files

- `src/seon/render/value.cljs` — `sample`, `render-ai`, `render-html-data`
  (self-contained; deps: `clojure.string`, `seon.platform`).
- `test/seon/render/value_test.cljs` — 22 behavioral tests (bounds, paths,
  lazy-safety, homogeneity, opaque projection, hint contract). Green under
  `bin/test-cljs` (full suite 618 tests / 2752 assertions, 0 failures).
- The before/after in §3 was generated by a throwaway babashka harness (a 1:1
  port of the algorithm now in `value.cljs`); regenerate by feeding the §3 shapes
  to `seon.render.value/render-ai` in any CLJS REPL.

## Open questions / smells flagged

- `cap-result-body` and `cap-edn` share the same 16384 default but live in
  different nses (`seon.ctx` / `seon.eval`) with cross-referencing docstrings.
  Post-cutover the structural sampler is the real bound; these two could be
  reconsidered (not in this task's scope — flagged).
- The opaque-detection logic exists THREE places after this prototype
  (`seon.eval/datahike-handle?`, `seon.eval/opaque-summary`, and the prototype's
  copies). The cutover MUST dedupe to one (§4.2) — do not leave three.
