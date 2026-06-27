---
type: research
status: active
tags: [research, agent, flow]
---

# Result paging + "show beyond the limit" — explicit verb design

> Design research for an EXPLICIT agent-facing mechanism to view a large eval
> result beyond the default render skeleton, plus a token-indexed paging utility
> to walk a huge result in bounded chunks WITHOUT re-running the eval. DESIGN
> ONLY — no code in this doc. Every claim is grounded in the real source
> (`file:line`); the external-LLM consultation was blocked by a hard Gemini quota
> (429, resets in ~18h) — see the appendix for the verbatim blocker + a
> source-grounded prior-art synthesis.

## TL;DR (the recommended design in 5 lines)

1. Two agent-owned verbs `my.code/show` and `my.code/page` (thin wrappers; floor
   = `seon.render.value` + `seon.eval`). Each takes the live VALUE — the agent
   writes `result/<id>`, the REPL dereferences it to the value, passed as a
   normal fn arg. ZERO form-string parsing → robust by construction.
2. They return a self-describing VIEW-MARKER map
   `{:seon.render.value/view :full|:page :seon.render.value/text "…" …}`. The
   renderer gains ONE new top-level branch that emits `:text` VERBATIM at a high
   drill cap (`SEON_RESULT_DRILL_CAP` ≈ 200000 chars ≈ 50k tok) — bypassing the
   bounded skeleton, so the verb's output is never re-skeletonized into uselessness.
3. `show` renders a value fully up to the drill cap; the agent SCOPES what it
   shows by composing ordinary Clojure — `(show (->> result/x (drop 100) (take 50)))`,
   `(show (get-in result/x [:a :b]))`. `take`/`drop`/`get-in`/`filter` ARE the
   structural pager; the language is the harness.
4. `page` is the residual primitive for one value too big for a single drill
   window: a PURE char-window over the reader-safe `project-plain` projection,
   line-snapped, page n = the lines in char-band `[(n-1)·size, n·size)`;
   token-index = char/4; NO stored cursor (pure fn of `(value, n, size)`).
5. Storage stays OOM-safe: `record-eval!` detects the marker by VALUE-SHAPE and
   persists only a COMPACT projection + a tiny `:seon.eval/view` flag (never the
   200k text); `format-eval-row` RE-DERIVES the full body from the live var at
   render, and only for the CURRENT turn — older / dead-var → compact + "re-run
   to view." Default eval render is unchanged.

## Why a new verb at all (and what it is NOT)

The bounded skeleton (`seon.render.value/render-ai`, value.cljs:393 → `sample`,
value.cljs:248, depth 3 / items 8 / keys 8 / string 80, capped ~16384 chars ≈ 4k
tok by `result-body-render-cap`, ctx.cljs:385) is the correct DEFAULT — it stops
an accidental huge result (the 9.7M `pull [*]` blow-up, eval.cljs:1891-1896) from
flooding context. It is NOT being changed.

The agent ALSO already has the best DATA-ACCESS surface: the full value lives
uncapped in the live var `result/<id>` (`stash-result-raw!`, eval.cljs:962;
`bind-result-var!`, eval.cljs:1051), drilled with ordinary Clojure
(`get-in`/`filter`/`count`/`take`/`drop`). The new verb is NOT a data-access
mechanism — Clojure-on-the-var is. The new verb is a RENDER-FOR-READING aid: "let
me EYEBALL this large (already-scoped) slice in full readable form." Framing it as
a viewing aid is what makes the whole design fall out cleanly (the agent does its
own structural windowing; the verb only lifts the render cap).

The rejected approach — broaden `result-var-ref?` (eval.cljs:750, today an exact
bare-symbol predicate) to "the form's source TEXT references a `result/<id>`
token" and auto-lift the cap — is fragile (false-positives a `result/x` inside a
string/comment, misses `(let [r result/x] (frobnicate r))`, couples render policy
to source-string matching). The owner called it fragile; this design replaces it
with an EXPLICIT verb whose argument is a live value, never a parsed form.

## The recommended VERB

### Names, home, floor

| Verb | Home (agent-owned, editable) | Floor (protected, `:core-seed`) |
|---|---|---|
| `my.code/show` | `my.code` (thin wrapper; toolkit.md:424) | `seon.render.value/render-full` (new) + the drill cap + marker recognition in `seon.eval` |
| `my.code/page` | `my.code` | `seon.render.value/render-page` (new) + the same drill cap |

`my.code` already owns the define→redefine→`forget!` code-lifecycle verbs and is
backed by `seon.eval` + `seon.db` (toolkit.md:424-477) — show/page are
eval-adjacent render verbs and belong there. Per the two-tier rule (toolkit.md:50-84)
the correctness-critical render + cap logic is the protected FLOOR
(`seon.render.value`, `seon.eval`); `my.code/show`/`page` are the thin,
agent-editable wrappers over it.

### Signatures

```clojure
(my.code/show value)
(my.code/show value {:seon.code/cap N})          ; N overrides SEON_RESULT_DRILL_CAP

(my.code/page value n)                            ; n is 1-based
(my.code/page value n {:seon.code/page-size N})
```

`value` is the live value (a `result/<id>` var derefs to it; or any in-flight
value the agent already holds). The agent SCOPES the view by composing Clojure
BEFORE the call — `show` renders whatever it is handed:

```clojure
(my.code/show result/auC-2606271530)                       ; whole value
(my.code/show (get-in result/auC-2606271530 [:rows 0]))    ; one sub-tree
(my.code/show (->> result/auC-2606271530 (drop 100) (take 50)))  ; a row window
(my.code/show (map #(select-keys % [:a :b]) result/auC-2606271530)) ; chosen columns
```

### The cap

```
SEON_RESULT_DRILL_CAP   default 200000 chars  ≈ 50000 tok   (env-tunable)
```

A NEW, separate tier from the existing two 16384 caps — distinct concerns, kept
distinct (the same discipline the existing `result-body-render-cap` ↔
`store-edn-cap` cross-reference keeps, ctx.cljs:394-400):

| Cap | Value | Layer | Concern |
|---|---|---|---|
| `eval-render-cap` (ctx.cljs:372) | 1500 | render | echoed source + stdout (not citable) |
| `result-body-render-cap` (ctx.cljs:385 / eval.cljs:1984) | 16384 | render | DEFAULT skeleton body |
| `store-edn-cap` (eval.cljs:1886) | 16384 | persist | OOM-safety on a datom — UNCHANGED, never raised |
| **`SEON_RESULT_DRILL_CAP` (new)** | **200000** | **render only** | explicit show/page body |

The drill cap is a RENDER cap only; the STORE cap (16384) is never raised (see
Storage, below) — that is what keeps the OOM-safety invariant intact.

### What it RETURNS (the crux — must not get re-skeletonized)

The verb runs at the REPL, so its RETURN is itself rendered by the SAME pipeline.
Resolve the three failure modes:

- Return a raw STRING → `sample` clips strings to `max-string` 80 via `clip-string`
  (value.cljs:190-194) → the agent sees 80 chars + `⟨N chars⟩`. USELESS.
- Return the raw VALUE → `render-ai` → `sample` re-skeletonizes at depth 3 / items
  8 (value.cljs:216-261). USELESS (defeats the purpose).
- **Return a self-describing VIEW-MARKER map** → the renderer recognizes the
  marker and emits its `:text` VERBATIM at the drill cap. CORRECT.

```clojure
;; SUCCESS value (the view IS the answer — same category as my.shell / my.test,
;; toolkit.md:199-206):
{:seon.code/ok?               true
 :seon.render.value/view      :full          ; or :page
 :seon.render.value/text      "<pre-formatted readable text, already capped>"
 :seon.render.value/page      1              ; present on :full too (always 1 of pages)
 :seon.render.value/pages     1              ; total pages at the chosen page-size
 :seon.render.value/page-size 200000
 :seon.render.value/tokens    12345          ; ≈ (count text)/4 of THIS view
 :seon.render.value/total-chars 49380}

;; FAILURE (e.g. a dead / pruned var, or an unprintable value): the never-throw
;; envelope (toolkit.md:181-197). No :view key → renders as a small skeleton, fine.
{:seon.code/ok? false :seon/error <error value>}
```

`:seon.render.value/view` (a reserved namespaced keyword) is the DISCRIMINATOR —
the renderer keys on its presence, never on the source string. This is the
"namespaced keyword IS the discriminator" rule (memory:
`feedback_no_kind_type_namespace_discriminator`).

### Schemas to register

In-memory VALUE shapes (never transacted as entity attrs → not bridge-bound; OK to
be `:map`/`:string`, per library-grounding.md:104-110):

```clojure
(schema/register! :seon.render.value/view        [:enum :full :page])
(schema/register! :seon.render.value/text        :string)
(schema/register! :seon.render.value/page        :int)
(schema/register! :seon.render.value/pages       :int)
(schema/register! :seon.render.value/page-size   :int)
(schema/register! :seon.render.value/tokens      :int)
(schema/register! :seon.render.value/total-chars :int)
(schema/register! :seon.code/cap                 :int)
(schema/register! :seon.code/show-response
  [:or [:map [:seon.code/ok? [:= true]] [:seon.render.value/view :seon.render.value/view]
             [:seon.render.value/text :seon.render.value/text]]
       [:map [:seon.code/ok? [:= false]] [:seon/error :seon/error]]])
;; :seon.code/page-response is :show-response + the page-count keys.
```

The ONE STORED attr (a tiny flag on the eval row, bridges to `:db.type/keyword`):

```clojure
(schema/register! :seon.eval/view [:enum :full :page])   ; bridge-storable
```

## The recommended PAGING design

### Mechanism — char-window over the reader-safe projection (primary)

`page` is the residual primitive: ONE value too big for a single drill window
(e.g. a 600k-char single string, or a value the agent does not want to think about
how to slice). It is a PURE char-window over the value's reader-safe text:

1. **Project** `value` → reader-safe text. Reuse `project-plain` (value.cljs:161,
   UNBOUNDED, opaque handles → compact markers, never throws), then pretty-print
   at the existing `width` (value.cljs:269) for line structure. The result is
   deterministic for a given value (maps keep natural order, value.cljs:233-234).
2. **Partition by LINE into page-size bands.** Accumulate lines into page `n`
   until adding the next line would cross `n·page-size` chars; never cut a line.
   So page `n` = the lines whose cumulative-char offset falls in
   `[(n-1)·page-size, n·page-size)`. This is a deterministic fold from the start
   → page `n` is a PURE function of `(value, n, page-size)`, and pages never break
   mid-line (mitigating the mid-token / lost-path-context boundary problem).
3. **Total pages** `N = ⌈total-chars / page-size⌉` (line-snapping makes each page
   ≤ page-size + one line; `N` is computed by the same fold).

`show` = "page 1 at the drill cap": if `total-chars ≤ cap` the value renders whole
(`:view :full`); else it is page 1 of `N` (`:view :page`, with the next-page hint).

### Token-indexing math

There is NO tokenizer dependency — token ≈ chars/4 (the
`:seon.render/token-estimate` convention, CLAUDE.md). So:

```
page-size (chars)   = SEON_RESULT_DRILL_CAP            ; default 200000 ≈ 50000 tok
char-offset(page n) = (n-1) * page-size                ; band start
N (total pages)     = ceil(total-chars / page-size)
tokens(view)        = (count text) / 4                 ; reported "≈T tok"
```

"Token indexing" is therefore char-offset indexing under the /4 estimate; a "page"
is a char-band, line-snapped.

### Return shape + the next-page hint

```clojure
{:seon.code/ok? true
 :seon.render.value/view      :page
 :seon.render.value/text      "<page n's lines>"
 :seon.render.value/page      n
 :seon.render.value/pages     N
 :seon.render.value/page-size 200000
 :seon.render.value/tokens    49998
 :seon.render.value/total-chars 612345}
```

The RENDERED body the agent reads ends with one legible, actionable hint line
(comment prose, `;` per the comment convention):

```
; ‹page 2 of 4 · ≈50k tok · 612k chars total› — next: (my.code/page <your result/… var> 3)
; or scope it: (my.code/show (->> <var> (drop …) (take …)))  ·  full value: result/<id>
```

Because the verb receives the VALUE not the id (the robustness invariant), it
cannot recover the source var name — the hint uses a `<your result/… var>`
placeholder (the agent typed the var on the same line, so it knows it). This is a
deliberate tradeoff: robustness (no parsing) over a fully-spelled hint. OPTIONALLY
the verb may ALSO accept an explicit id (`(my.code/page :auC-2606271530 3)`,
deref'd via `lookup-result`, eval.cljs:975) for a precise hint — but value-first is
the robust default.

### Where paging STATE lives — nowhere (pure)

A page is a PURE function of `(value, page-n, page-size)`; there is NO stored
cursor. Justification (derive-everything, CLAUDE.md "Reactive context"):

- The value has a STABLE address — the volatile live var `result/<id>` (globalThis
  stash, three-tier storage rule). The agent re-supplies it each call (a cheap
  deref of a value that already exists).
- Pages are a deterministic line-partition of a deterministic projection → page
  `n` is reproducible from `(var, n, size)`. Storing a cursor would bifurcate the
  architecture into stored-fast-path + derived-slow-path for no gain.
- The only stateful artifact is the live var itself — exactly the category the
  reactive-context rule permits (a genuine volatile runtime value), not derivable
  state masquerading as storage.

**Lazy / infinite seqs.** `project-plain` is eager, so the lazy-safe path projects
INCREMENTALLY — accumulate the line-stream only until the requested page's end
offset is reached, never forcing the whole seq. The common case (a finite, already
realized value sitting in the var) is trivially fine; the incremental path covers
the rare live-infinite-seq value. Still pure (deterministic from `(value, n)`),
still no cursor.

## Storage — store compact, derive full from the live var (OOM-safe)

The render pipeline today: eval value → `render-result-edn` (eval.cljs:2043, =
`render-ai` + `clip-result-body` cap 16384) → `cap-edn` (store-edn-cap 16384) →
stored `:seon.eval/result-edn` datom → at render `format-eval-row` (ctx.cljs:565)
re-caps at 16384. Three 16384 caps in series.

A 200k-char view body must NOT enter that store path — `store-edn-cap` exists
because a 9.7M body OOM-killed the pod on a whole-DB `[?e ?a ?v]` scan
(eval.cljs:1891-1896); persisting N×200k bodies re-creates exactly that failure.
So:

1. **`record-eval!` (eval.cljs:2074, the `:seon.eval/result-edn` assoc ~2141)**
   detects the view-marker by VALUE-SHAPE (`(:seon.render.value/view value)`) and
   persists only a COMPACT projection — the skeleton of the marker WITHOUT its big
   `:text` (just `{:view :page :page 2 :pages 5 :tokens 50000 :total-chars 612345}`,
   tiny + store-safe) — PLUS the flag `:seon.eval/view`. The full `:text` is NOT
   stored. The full VALUE is already in the live var (`stash-result-raw!`,
   eval.cljs:962, uncapped by design).
2. **`format-eval-row` (ctx.cljs:565)**, seeing `:seon.eval/view` on a row,
   RE-DERIVES the body from the live var at the drill cap: `lookup-result eid`
   (eval.cljs:975, graceful-miss built in) → `render-full` / `render-page`. If the
   var is live → the full / paged body; if dead (prior session / pruned past
   `result-vars-cap` 200, eval.cljs:740) → the compact stored projection + the
   existing prior-session "re-run its form" guidance (eval.cljs:1016-1019).
3. **Current-turn only.** Re-derive the FULL body only for eval rows in the ACTIVE
   turn (`current-turn`, ctx.cljs:779; the transcript builder marks the latest
   turn's nodes, transcript.cljs:395). Older turns render the COMPACT one-liner
   (`⟨showed result/x · page 2/5 · ≈50k tok⟩`). The agent saw the full view on the
   turn it asked; later turns stay lean. This is the honest derive-everything
   answer — the view is a function of (live value, turn-recency) at render time,
   nothing big is stored, and the rolling transcript window (deferred, roadmap)
   bounds even the current-turn cost.

This respects OOM-safety (no big datom ever written), keeps context lean
(compact-after-this-turn), and honors derive-everything (re-derive from the
volatile live value, store only a flag + tiny projection).

## How it composes with the default (unchanged)

```
incidental eval value ─→ render-ai skeleton (depth3/items8, ≤16384) + result/<id> hint   [DEFAULT, unchanged]
agent calls (my.code/show v) ─→ view-marker ─→ render-ai NEW branch emits :text @ 200k cap [this turn]
                              └─→ stored compact + :seon.eval/view flag                     [durable, OOM-safe]
                              └─→ next turn: compact one-liner, full re-derivable on demand  [lean]
value too big for one window ─→ (my.code/page v n) ─→ pure line-band page n of N
agent scopes a slice first   ─→ (my.code/show (->> v (drop k) (take m)))                    [language IS the pager]
```

The skeleton renderer gains exactly ONE new branch (recognize the top-level
view-marker, value.cljs:393-413); everything else — `sample`, the `result/<id>`
mechanism, the three existing caps, the store path — is untouched.

## Alternatives considered + why rejected

1. **Broaden `result-var-ref?` to substring-match a `result/<id>` token in the
   form, auto-lift the cap** (eval.cljs:750). REJECT: string-parsing the form is
   fragile — false-positives a `result/x` inside a string/comment, misses
   `(let [r result/x] …)`, couples render policy to source matching. Owner called
   it fragile; the explicit verb takes a live VALUE, never a parsed form.
2. **`show` returns a raw String.** REJECT: `clip-string` truncates strings to 80
   chars (value.cljs:190-194) → 80 chars + `⟨N chars⟩`. Useless.
3. **`show` returns the raw value.** REJECT: re-skeletonized at depth 3 / items 8
   (value.cljs:216-261). Defeats the purpose.
4. **`println` the full value to stdout.** REJECT: captured stdout caps at
   `eval-render-cap` 1500 (ctx.cljs:372, 644-645) — smaller than the skeleton —
   and loses the structured `;=>` channel.
5. **Store the full 200k text in the datom at a raised store cap.** REJECT:
   violates OOM-safety — `store-edn-cap` 16384 exists because a 9.7M body OOM-killed
   the pod on a whole-DB scan (eval.cljs:1891-1902). N×200k bodies recreate it.
6. **Blob the full text (content-addressed `my.blob` tier) + hash in the datom.**
   REJECT as the primary path: a view is a TRANSIENT one-turn affordance tied to
   the volatile var's session lifetime; a persistent disk blob over-persists it and
   adds I/O on every render. The live-var re-derive is leaner. (`my.blob`,
   toolkit.md:558, remains the agent's EXPLICIT tool for content it WANTS durable —
   orthogonal.)
7. **A first-class collection-cursor verb (take/drop window + a page pointer).**
   REJECT as a separate verb: the agent ALREADY has `take`/`drop`/`get-in`/`filter`
   on the live var; `(my.code/show (->> result/x (drop 100) (take 50)))` IS the
   collection pager — composing the language with the view verb (the Bitter-Lesson
   "merge the language into the harness" note, memory). A dedicated cursor verb
   duplicates `take`/`drop` and would store a pointer (anti-derive). `page` covers
   only the residual single-huge-value case.
8. **Structural node-by-node expansion as the TEXT pager** (the value-explorer
   approach, value.cljs:420-424). REJECT for text (KEEP for html): expanding one
   pruned node deeper is ideal for an INTERACTIVE GUI but awkward as a LINEAR text
   "page"; the text agent wants readable bytes, and Clojure-on-the-var already gives
   targeted structural access. Per-node expand stays U's drill-DEEPER axis; `page`
   is the orthogonal page-ACROSS axis.

## The html half — note for UI lane U (the DATA contract, not hiccup)

`render-html-data` (value.cljs:427) returns `{eval-id summary truncated? tree}`.
Extend it with a PAGING contract so U's collapsible value-explorer gains a second,
orthogonal axis. U then has TWO axes:

- **drill DEEPER** (existing) — per-node: re-sample `(get-in result/<id> path)` one
  level deeper (value.cljs:420-424).
- **page ACROSS** (new) — linear next-chunk over the same value.

Extended data map (PLAIN DATA only — styling/interactivity are U's):

```clojure
{:seon.render.value/eval-id     "auC-2606271530"
 :seon.render.value/summary     "map 12 keys · 980k chars · ≈245k tok"
 :seon.render.value/truncated?  true
 :seon.render.value/tree        <sample skeleton>      ; page 1 / the bounded view
 :seon.render.value/paged?      true                   ; value exceeds one page
 :seon.render.value/page        1
 :seon.render.value/pages       5
 :seon.render.value/page-size   200000
 :seon.render.value/total-chars 980000
 :seon.render.value/total-tokens 245000}
```

U's explorer renders the collapsible skeleton (page 1) plus a "page 2 of 5"
control. The control issues a fresh server `/call` to a NEW floor fn
`seon.render.value/render-html-page` `(eval-id, n)` → `{:tree | :text :page :pages
:tokens …}` for the n-th page. The page math is the SAME pure line-partition the
text `page` uses — ONE mechanism, two surfaces (text + html), no second pager. The
server resolves `eval-id` → the live value via `lookup-result` (eval.cljs:975),
same graceful-miss semantics.

## Integration points (`file:line` against the real code)

New floor fns + ONE new render branch:

- `seon.render.value/render-full` (NEW, value.cljs near 413) — value → view-marker
  `:full`, capped at `SEON_RESULT_DRILL_CAP`, built on `project-plain` (161).
- `seon.render.value/render-page` (NEW) — `(value, n, page-size)` → view-marker
  `:page`; the pure line-band partition.
- `seon.render.value/render-ai` (value.cljs:393) — add a TOP-LEVEL branch: a
  view-marker value → emit `:text` verbatim at the drill cap, bypass `sample`.
- `seon.render.value/render-html-page` (NEW) — the html paging fetch (Q5).
- `seon.render.value/render-html-data` (value.cljs:427) — extend the data-contract
  with the `:paged? :page :pages :page-size :total-chars :total-tokens` keys.
- `seon.render.value/sample` / `sample*` (value.cljs:248 / 216) — UNCHANGED; the
  view-marker is intercepted above it in `render-ai`.

Caps:

- `SEON_RESULT_DRILL_CAP` (NEW def, value.cljs or eval.cljs) — 200000 default.
- `seon.eval/result-body-render-cap` (eval.cljs:1984) + `clip-result-body`
  (eval.cljs:1992) — recognize a view-marker, cap at the drill cap not 16384.
- `seon.eval/store-edn-cap` (eval.cljs:1886) — UNCHANGED (the OOM ceiling the
  design respects by never storing the full text).
- `seon.ctx/result-body-render-cap` (ctx.cljs:385) + `cap-result-body`
  (ctx.cljs:439) — add a drill-cap path for `:seon.eval/view` rows.

Eval pipeline:

- `seon.eval/record-eval!` (eval.cljs:2074; the result-edn assoc ~2141) — detect
  the view-marker by value-shape; store the COMPACT projection + `:seon.eval/view`
  flag, NOT the full text.
- `seon.eval/lookup-result` (eval.cljs:975) — the API the render path calls to
  re-derive (graceful miss for a dead var, eval.cljs:999-1019).
- `seon.eval/bind-result-var!` / `stash-result-raw!` (eval.cljs:1051 / 962) —
  UNCHANGED; the live var the re-derive reads.
- `seon.eval/result-var-ref?` (eval.cljs:750) — UNCHANGED (the verb makes
  broadening it unnecessary; the rejected fragile path).

Context / transcript render:

- `seon.ctx/format-eval-row` (ctx.cljs:565; the `ok?` branch 662-685) — when a row
  carries `:seon.eval/view` AND it is the active turn AND the live var resolves,
  re-derive the full/page body via `render-full`/`render-page`; else compact.
- `seon.ctx/current-turn` (ctx.cljs:779) — the active-turn signal for
  current-turn-only full render.
- `seon.ctx.transcript/transcript-section` (transcript.cljs:341) + the eval-node
  fold (transcript.cljs:395) + `node->row` (transcript.cljs:180-181) — mark the
  active turn's eval nodes for full-view rendering; pass the flag to
  `format-eval-row`.

Schemas + verbs:

- `:seon.render.value/*`, `:seon.code/cap`, `:seon.code/show-response`,
  `:seon.code/page-response` (in-memory value shapes) + `:seon.eval/view` (the one
  stored, bridge-storable, attr).
- `my.code/show` + `my.code/page` — the agent-owned wrappers (toolkit.md `my.code`
  catalog entry, 424-477).

Dependency direction (no cycle): `ctx → eval → render.value`; `ctx → render.value`.
`ctx` already requires `eval` (`sanitize-result-edn`, ctx.cljs:663) and the new
re-derive reuses `lookup-result` + `render-full`.

---

## Appendix A — external consultation: BLOCKED by Gemini quota (verbatim)

The research policy requires ONE external-LLM consultation. It was ATTEMPTED and
BLOCKED — recorded honestly rather than faked.

- `agy -p …` (Gemini, the sanctioned CLI) returned empty (exit 0) for every prompt
  including a trivial `PONG` probe. The `--log-file` capture shows the cause
  VERBATIM:

```
E0627 17:15:09.310902 ... RESOURCE_EXHAUSTED (code 429): Individual quota reached.
  Please upgrade your subscription to increase your limits. Resets in 18h43m8s.
E0627 17:15:11.110192 ... agent executor error: model unreachable: RESOURCE_EXHAUSTED
  (code 429): Individual quota reached. ... Resets in 18h43m5s.
W0627 17:15:08.013774 ... ignoring invalid allow entry "escalate_admin(*)"
I0627 17:15:08.773748 ... Propagating selected model override: label="Gemini 3.5 Flash (Medium)"
```

- The alternative Gemini path (`(user/ask …)` / `(user/search …)` in the JVM
  `user` ns) was also unavailable: the JVM nREPL (port 7888, the PAUSED track) is
  not running ("Seon server is not running ... cannot connect to nREPL on port
  7888").

The quota resets in ~18h. RECOMMENDATION: a follow-up agent re-runs the Appendix-B
prompt (saved at
`/private/tmp/.../scratchpad/paging-prompt.txt`, reproduced below) once quota
returns, and appends the verbatim Gemini response here. The design above does not
depend on it — its load-bearing grounding is the real Seon source (cited
throughout). The prior-art the consultation would have surfaced is synthesized
from first-hand knowledge in Appendix C (clearly labeled as the agent's synthesis,
NOT Gemini output).

## Appendix B — the prompt that was sent (for re-run when quota returns)

The full prompt is preserved at
`scratchpad/paging-prompt.txt`. It sets out the system (bounded skeleton +
`result/<id>` + OOM-safe store cap + derive-everything), the problem, the proposed
design (show/page, the view-marker return, store-compact/derive-full), and asks 6
questions: (1) char-window-vs-collection-cursor primary; (2) what real coding
agents do for big file/shell output windowing + page size vs context window +
mid-token boundary harm; (3) Clojure prior art (nREPL print middleware
quota/`*print-length*`/`*print-level*`, cider-inspector paging, Reveal/REBL/Morse/
Portal value browsing, datafy/nav, puget/zprint bounded printing); (4) critique of
store-compact/derive-full vs a blob; (5) char-windowing pitfalls (lost path
context, mid-token cuts, unstable boundaries); (6) lazy-seq window+cursor patterns
and maximally-legible "page K of N" hints.

## Appendix C — prior-art synthesis (the AGENT's, source-grounded; NOT Gemini)

Recorded so the design is not left un-cross-checked. Clearly the agent's own
synthesis, to be confirmed/extended by the Gemini re-run.

- **Clojure print-bounding primitives — `*print-length*` / `*print-level*`.** The
  canonical "bound the VIEW not the value" knobs map exactly onto Seon's
  `sample` (`max-items` ≈ `*print-length*`, `max-depth` ≈ `*print-level*`,
  value.cljs:72-77). Confirms the skeleton is the right default and that the
  ESCAPE from it is "raise the bounds," which is what `show`'s drill cap +
  loosened/`project-plain` path does.
- **nREPL print middleware (`nrepl.middleware.print`) + `:nrepl.middleware.print/quota`.**
  nREPL caps printed values at a char QUOTA and streams the rest — i.e. a
  CHAR-budget on rendered output plus continuation, precisely the
  `result-body-render-cap` / `clip-result-body` model (eval.cljs:1992) and the
  paging continuation. Validates char-window paging as the idiomatic Clojure
  answer for a TEXT (non-GUI) consumer.
- **cider-inspector paging.** CIDER's value inspector pages a large collection with
  a fixed page-size and explicit next-page/prev-page navigation over a STABLE value
  handle — exactly `(my.code/page value n)` with no stored cursor (the handle is
  stable, the page is derived). Confirms "pure page over a stable handle" is
  battle-tested.
- **Reveal / REBL / Morse / Portal + `datafy`/`nav`.** These are INTERACTIVE GUI
  browsers that lazily expand nodes on demand (datafy/nav) — the structural
  drill-DEEPER axis. This is precisely why Seon keeps per-node expansion for the
  HTML lane (value.cljs:420-424, U's explorer) and does NOT use it as the text
  pager: GUI = expand-node, text/LLM = linear char-window. The split in this design
  mirrors the ecosystem's own GUI-vs-stream split.
- **puget / zprint (bounded pretty-printing).** Deterministic, width-bounded
  pretty-printers — the right tool to produce the deterministic, line-structured
  text that the pure line-band partition windows over (the `width` knob already
  exists, value.cljs:269). Reinforces newline-snapping over mid-token cuts.
- **Coding-agent big-output windowing (Claude Code / Aider / OpenHands lineage).**
  The dominant pattern for oversized file reads / shell output is OFFSET+LIMIT LINE
  windows ("read lines 200-400", "first N + last N lines") with an explicit "N more
  lines, request the next window" continuation — a LINE-granular char window with a
  visible total and an actionable next-step. This is exactly `page`'s line-snapped
  band + the "page K of N · next: (my.code/page …)" hint. The consistent lesson:
  break at LINE boundaries (never mid-token), always show the TOTAL and a concrete
  next action, and keep page size a meaningful fraction of the context window (50k
  tok against a large window is a reasonable single chunk; the agent scopes smaller
  with Clojure when it wants less).
