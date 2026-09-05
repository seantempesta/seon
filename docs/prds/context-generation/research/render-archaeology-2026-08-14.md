---
type: research
status: complete
tags: [research, render, print, transcript, ui, archaeology, quarry]
---

# Render archaeology — what the FIRST implementation had

Mined 2026-08-14 for
[the One Renderer PRD](../plan/one-renderer-prd-2026-08-14.md) §0 (whole
pipeline + failure policy) and §3 (the iterate list). Read the PRD end to
end, including the same-day §0 scope correction (commit `efde4a65d`).

**How to read the quarry.** The first implementation is the tree deleted
by `099cdfa99` ("Delete the quarry from the working tree", owner sweep
ruling 2026-08-05). Its last content commit is **`9e44815f5`**, so every
citation below reads as:

```bash
git show 9e44815f5:src-old/seon/render/value.cljc
```

`src-old/` was itself the rename of the original `src/` at the tree split
(`f25e34594`, "R0: the tree split — the fresh tree IS the project"): 181
files before, 174 after. It IS the first implementation, not a curated
subset.

The render/print/transcript/UI surface, by size:

| Path (under `src-old/`) | Lines | What it is |
|---|---|---|
| `seon/render/value.cljc` | 1927 | The floor: sample → emit → drill. THE big find |
| `seon/agent/ctx/transcript.cljc` | 1612 | The agent's REPL transcript, `/ai` + `/html` twins |
| `seon/agent/ctx.cljc` | 1959 | Prompt assembly, eval rows, glyph grammar, KV chain keys |
| `seon/render.cljc` | 1132 | The pipeline: resolve → render → guard, with the strict dial |
| `seon/render/canvas.cljc` | 639 | Agent-authored canvas surfaces |
| `my/canvas.cljc` · `my/ui.cljc` | 303 · 272 | The agent-facing canvas protocol |
| `seon/ui/html.cljc` | 353 | Hiccup → HTML string, escaped by default |
| `seon/ai/tokens.cljc` | 253 | Token estimate + the **capped writer** |
| `seon/ui/markdown.cljc` | 226 | Markdown → hiccup subset |
| `seon/ui/clojure.cljc` | 192 | **Clojure syntax tokenizer → hiccup** |
| `seon/render/chat.cljc` | 130 | Chat bubbles |
| `seon/ui/agent_view.cljs` | 93 | The agent page shell |

---

## §3 item 1 — Form-aware fit

> *"the owner learns code-content awareness (whole forms as elision
> units). Archaeology: find its fit/pprint/truncation code and its
> lessons BEFORE writing this."*

### What existed: a two-phase **sample → emit**, not print-then-cut

`9e44815f5:src-old/seon/render/value.cljc`. The floor never printed a
value and then cut the string. Phase one (`sample`, :547-840) walked the
raw value under depth/breadth/string caps and produced a **skeleton of
plain data plus namespaced markers** — `:seon.render.value/pruned`,
`/elided`, `/string-len`, `:seon.eval/opaque`, `:seon.eval/datom`. Phase
two (`emit`, :975) rendered that skeleton with a **width-aware
inline-if-it-fits** rule:

```clojure
(defn- fits? [x depth width]
  (let [s (emit-inline x)]
    (and (not (str/includes? s "\n"))
         (<= (+ (count s) (* 2 depth)) width))))

(defn- emit
  "Render skeleton node `x` at `depth`; inline when it fits, else break one
   child per line."
  [x depth width] …)
```

That is a genuine pretty-printer — Oppen-style "fits on the line or
break every child", 30 lines, no dependency — operating on already-
bounded data. **Nothing is ever cut mid-structure, because nothing
oversized is ever printed.**

The character cap was a separate *last-resort gate* for pathological
scalars, and it too avoided materializing the value —
`9e44815f5:src-old/seon/ai/tokens.cljc` :166-207 prints through a capped
`java.io.Writer` proxy that throws a sentinel the moment the budget is
spent:

```clojure
(when (> length retained)
  (vreset! truncated? true)
  (throw bounded-print-sentinel))
```

`prepare-ai` (:1103) composed the two into an honest ladder: probe with a
verbatim-budget sample; **untruncated probe ⇒ print whole** (REPL
fidelity for ordinary values); truncated ⇒ the bounded skeleton view.

### The one genuinely clever heuristic — `dominant-string-entry`

`value.cljc:1028-1068`. When a small map's rendered size is ≥70 % ONE
plain string, that string is the map's payload and renders as a body
block with the small keys as its header, instead of the map collapsing
to a two-line stub:

```clojure
;; This is what makes a read function's own payload (`view`'s content, a
;; fetched body) VISIBLE instead of a stub, without any per-function
;; special-casing.
```

Shape-general, no function names, no allowlist. This is the answer to the
"file read renders as `{:content \"…\"}`" class of ugliness.

### Honest negative

**The old code was NOT form-aware either.** `clip-string`
(`value.cljc:583`) is a bare `subs` on a long string leaf, exactly like
today's `bounded-text` (`src/seon/print.cljc:834-839`). A Clojure form
handed to the floor as *source text* (a `:seon.eval/source` string, a
markdown fence) was clipped mid-token in both trees. The old system
dodged the problem rather than solving it: code content mostly travelled
as its own component with its own cap (`format-eval-row`'s split caps),
not through the value floor.

So §3 item 1 gets **no revivable form-aware fit** — it does not exist in
the archive. What it gets is the *architecture* that makes form-awareness
cheap: bound the structure first, print second, and let the character cap
be an exceptional guard rather than the mechanism.

**Verdict — revive-adapted.** Today's `seon.print` already has the
structure-first half (child-limit + elision nodes). Port two things:
(a) the **capped-writer print** so a huge value is never materialized to
be cut; (b) `dominant-string-entry` essentially verbatim. Write the
form-aware string-leaf fit fresh — the archive has no prior art, and the
old `subs` is the same defect we are removing.

---

## §3 item 2 — Pretty-data + highlighting

> *"did it already own a Clojure tokenizer/pretty-printer worth
> reviving?"*

### **Yes. `9e44815f5:src-old/seon/ui/clojure.cljc` — 192 lines, and it is the single best piece of deleted code found in this dig.**

Server-side single-pass tokenizer, source string → hiccup children. Its
own docstring makes the design argument the PRD is about to re-derive:

```clojure
"Why server-side (not the CDN highlight.js the old `/debug` shell loads):
 the new agent view's shim ships ONLY `datastar.js` — no hljs — so eval
 source there is unhighlighted, and any client pass races idiomorph after
 every SSE morph. A pure server tokenizer is morph-safe by construction,
 renders under `?t=` time-travel and `curl`, and keeps the agent view's JS
 = datastar-only."
```

and its robustness contract:

```clojure
"ROBUST by contract: agents emit partial / malformed forms (a stray `}`, an
 unterminated string). The tokenizer never throws — an unterminated string
 degrades to a string token to EOF, and any unexpected failure degrades the
 whole render to a plain `[:pre [:code …]]` with the raw source."
```

Mechanically it is exactly what design-doc decision 3 asks for: one
`loop` over the string, boundary-char set, dedicated readers for
comment / string / char-literal so a `;` inside a string cannot derail
the scan, coalesced plain runs into single text nodes, transient
accumulator, class per token kind. It never parses — it is a
highlighter, not a reader, and says so.

Everything transfers except the class names: it emits `hljs-*` to reuse
the old debug shell's palette. The fresh tree emits `seon-print-*`
(`src/seon/print.cljc`). That is a one-line change in `word-class` +
`span`.

**Verdict — revive VERBATIM** (modulo the class map and the `pre-class`
constant, which points at a deleted eval card). It is total, morph-safe,
dependency-free, and the fresh tree has **no highlighter at all**. Its
"degrade to plain `<pre>`" fallback needs re-reading against §0's
panic-in-dev policy: keep the total contract, but route the caught
failure through the strict dial (see §0 below) rather than swallowing.

Companion finds, same item:

- **`emit` / `fits?`** (value.cljc:970-1004) — the pretty-printer half.
  Revive-adapted; today's floor prints flat.
- **`seon/ui/html.cljc`** — hiccup→string, **escaped by default**, void
  elements, seq-splicing, `:class` collections, `:style` maps, explicit
  `(raw …)` opt-out, with a written argument for why no library was
  used. Lessons-only for the current tree (we render hiccup through
  `src/seon/render/hiccup.clj`), but the escaping default and the `raw`
  opt-out are the right contract and worth checking parity against.
- **`seon/ui/markdown.cljc`** — deliberately minimal, with
  `safe-link-url` refusing `javascript:`/`data:` hrefs from
  agent-authored content. Lessons-only; keep the safe-link rule if the
  chat page ever renders agent markdown.

---

## §3 item 3 — The chat page / transcript `/html`

> *"the old agent view / canvas / diffusion-era transcript UI — what
> worked, what was deliberately torn down, why."*

### The `/ai` transcript (`transcript.cljc`) — the strongest prior art in the dig

`format-transcript-block` (:1277) assembled: masthead → flat
**time-ordered event log** (messages and evals interleaved, oldest
first) → folded live readline. Its ruling is stated in the docstring and
is the same one the PRD's "one derivation, three faces" implies:

```clojure
"Turn boundaries are NOT containers — there are no per-turn headers or
 process-boundary rows."
```

The per-entry unit is `format-eval-row` (`ctx.cljc:513`): comment
preamble as `;` lines, the form verbatim, captured stdout, then the value
as a **bare, non-comment-shaped runtime line**:

```text
; add 1 and 2
(+ 1 2) ⟹ 3 ⟸ result/EVLabc-123
```

with the reasoning recorded (`ctx.cljc:433-487`): `⟹` `⟸` `⋘` `⋙` `❯`
are **reserved runtime glyphs**, each a single named `def` referenced by
every emit site and by the system text, so the grammar cannot drift; the
result is deliberately not comment-shaped because agents were observed
mimicking the old `; ⟹` form and fabricating results (T4 6/24). The
transcript stopped being re-evaluable Clojure and the owner took that
trade explicitly.

Three more mechanisms here are directly reusable:

1. **Wall coalescing** (:428-525). Content-free segments (a mis-split
   trailing `}`) are dropped; ≥3 consecutive same-signature errors
   collapse to one line that teaches:
   `⟹ ✗ 10× Unmatched delimiter — 10 consecutive failures collapsed; each
   DEFINED NOTHING. Fix the form once, not 10 times.` A thrash burst can
   never flood context. Pure derivation over the ordered stream.
2. **Result decay by age** (`decay-cap-for-offset`, :118). The per-eval
   result body cap shrinks with turn offset (`[0→4096, 2→1024, 5→512]`)
   and is **byte-stable inside a band**, so the prompt prefix does not
   churn every turn.
3. **`clock` fails loud on a missing stored `:at`** (:335) — *"a render
   fn must never silently inject a live `now` into transcript-body text
   (that would bust the cache prefix every turn)"*. A render-path
   invariant enforced by a throw, in 2026-06 code. This is §0's policy,
   already applied at one seam.

### The `/html` twin

`recent-html-events` / `format-transcript-html` (:1434-1612) rendered the
**same event stream** through each entity's `/html` converter — the same
identities, one derivation, two projections. `seon/render/chat.cljc` is
the human-facing bubble column (human / agent / peer / system kinds,
markdown server-side). Small, clean, no state.

### What the owner actually said

- **The divergence bug that started all of this**
  (memory `project_v2_context_render.md`, 2026-06-08): the agent's real
  prompt rendered ~22 chars while the inspector's left pane rendered
  6129 — *"The webview showed a context the agent never received."*
  `:seon.turn/prompt-text` was stored empty. The fix was to force both
  paths through one assembly fn and assert byte-identity. **This is
  precisely the failure mode PRD §1's "one derivation, three faces"
  exists to make unconstructable, and the archive says the guarantee has
  to be a test, not a diagram.**
- **The drill-down UI landed well**
  (memory `project_value_explorer_ui_gotchas_2026_06_27.md`): the
  value-explorer tile over `render-html-data` was browser-verified
  *"genuinely useful and beautiful"* — a `<details>`-native collapsible
  browser letting a human drill the agent's latest value the way the
  agent does with `(get-in result/<id> …)`.
- **The standing quality bar**
  (memory `feedback_flag_garbage_over_fake_optimization.md`, owner
  2026-06-21): *"I'd rather properly show 5–10 whitelisted namespaces and
  have a note on how to query for more than have 'optimized' context and
  it's all useless."*

**Verdict — lessons-only for the code, revive for the rulings.** The
transcript file is 1612 lines fused to a deleted world (pod-era async
acquisition, CLJS `^{:async}` metadata, `render-context` block entities,
per-block profiles). Porting it would import the block-entity machinery
the fresh tree deliberately dissolved. But four rulings should land in
the PRD as constraints, not be rediscovered: flat interleaved event log
with no turn containers; reserved runtime glyphs as single-source defs;
error-run coalescing; and byte-stability of everything above the cache
breakpoint. `render/chat.cljc`'s bubble shape is small enough to
**revive-adapted** for the chat page.

---

## §3 item 4 — Turn segmentation, block identity, cache invalidation

The PRD's rip-out #10 (retained render packages serving stale render functions)
has real prior art, and it is better than what we have.

`ctx.cljc:1856-1959` derives **per-block chain hashes**, mirroring vLLM's
automatic prefix cache verbatim (vendored at
`reference-code/vllm/vllm/v1/core/kv_cache_utils.py`, cited by line
number in the docstring):

```clojure
(defn- block-chain-hash
  [parent content salt]
  (sha256-hex (str parent "\u001f" content "\u001f" salt)))
```

The invariants it commits to:

```clojure
;; - identical block sequences + same agent ⇒ identical key vectors;
;; - two turns sharing a static prefix but differing in the tail share the
;;   prefix keys and diverge at exactly the first changed block;
;; - different `:seon.agent/id` ⇒ different keys even for identical blocks.
```

**The load-bearing lesson: the hash keys on the block's rendered OUTPUT
BYTES, not on its input identity or its render function's name.** A render function
change changes the text, which changes the hash, which invalidates from
exactly that block forward. Staleness is unconstructable rather than
patched — no invalidation hooks to remember to call. `chain-root-hash` is
a fixed constant, not `os.urandom`, so keys line up across process
restarts (`ctx.cljc:1876-1882`), and the `\u001f` separators make the
serialization injective — with a note that a raw NUL byte was tried first
and made the file look binary to `grep` (#83).

The complementary half is `rendered-blocks->context-text` (:1746): one
assembly owning ordering, bracketing, and the **cache breakpoint** that
splits stable from volatile blocks, with `rendered-child-blocks` (:1710)
rendering *once* into a single projection consumed by prompt, agent view,
and debug view — *"no parallel text/html collections to correlate."*

**Verdict — revive-adapted, high priority.** Chain-hash-on-output-bytes
is the answer to rip-out #10 and it is 80 lines. The cache-breakpoint
split and the render-once-consume-thrice assembly are the shape §0's
"assembly" stage should take.

Honest negative on this item: **turn segmentation itself is not in the
archive.** The old system had no contribution rows and no stored SHA
check; it had *block* identity, which is a different unit. §3's turn
segmentation is genuinely new work.

---

## §3 item 5 — Elision / pagination ("drill", windowing)

`value.cljc:1228-1450+` is a complete drill protocol, and it is stricter
than anything in the fresh tree:

- `admitted-drill-request?` (:1305) — the request must be a **closed** map
  of exactly `{::path ::offset ::effective-limits}`, path segments must
  belong to a **closed scalar codec** (`drillable-map-key?`, :589), and
  the request is bounded on *four* axes before any work happens: segment
  count, path **bytes**, realized items, and `offset + page-size`.
- `effective-limits-within?` (:1340) — a client may only ever **narrow**
  the trusted policy, never widen it. Requested vs trusted compared key by
  key.
- `map-key-projection` (:602) — a map key too large or not in the codec
  renders as an opaque display marker AND is recorded in
  `::non-drillable-key-indexes`, so the panel can reconstruct a valid
  `get-in` path from drillable positions only. The emitter then prints
  `"3 non-drillable keys shown safely"` rather than offering a path that
  cannot work.
- `paged-collection` / `bounded-map-window` (:1393-1407) — the
  take-`(inc page-size)`-and-`pop` trick, so `more?` is known without
  counting.
- Continuation is **taught, not implied** (`partial-continuation`,
  :1156):

```text
; ‹partial view of map 412 keys› — the COMPLETE value is result/EVLx
; keep: (my.blob/put! result/EVLx)  (get-in result/EVLx […]) · filter · count · take/drop
```

and the truncation markers are aggressively loud, because of an observed
failure (`ctx.cljc:378-394`):

```clojure
"Appends a LOUD truncation marker (shown of full tokens) so a
 clipped display can never pass for complete content — the observed
 failure mode is an agent summarizing INVENTED content from a
 silently-clipped render."
```

**Verdict — revive-adapted.** The elision *value* schema in the fresh
tree already carries count/path/requery identity, so the data model is
settled; what is missing is the **admission** half. The
closed-request + narrow-only-limits + non-drillable-key-indexes triad
should be ported nearly as-is onto the requery seam. The vocabulary
("drill") is legacy — AGENTS.md ruled `get-in`/path — but the mechanism
is sound.

Honest negative: the old caps were spread across seven
`:seon.config.render/value-*` dials that every caller re-read
individually. The fresh tree's single database-derived render profile is
**better**, and the ported code must not drag the dial spray back in.

---

## §0 — The whole pipeline and its failure policy

> *"did it panic, degrade, placeholder, or swallow? did its pipeline have
> stage contracts? the owner suspects the old system may have had a more
> coherent pipeline that the fresh tree's piecemeal render growth lost."*

**The owner's suspicion is correct.** The first implementation had the
R41 render dial the PRD is about to specify, built and shipped.

`9e44815f5:src-old/seon/render.cljc:327-380`:

```clojure
;; Fail-loud render dial — the ONE place every render swallow-guard routes
;; its caught exception. When `seon.config/render-strict?` is ON (dev / test
;; / benchmark), a render/converter failure RE-THROWS with the
;; offending block name + the full malli explain (a silent render failure
;; SCREAMS the moment it happens); when OFF (a live prod agent), it returns
;; nil so the caller falls back to today's graceful guard — no block ever
;; hard-crashes a prod turn.
```

```clojure
(defn strict-fail!
  [configuration where e]
  (when (rconfig/value configuration
                       :seon.config.render-context/render-strict? false)
    (throw (ex-info (loud-explain where e)
                    {:seon.render/strict?       true
                     :seon.render/where         where
                     :seon.render/cause-message (err/->message e)})))
  nil)
```

`loud-explain` (:337) attaches the **full humanized Malli explain** when
the exception is an instrumentation envelope — *"the string the strict-
mode throw carries and the graceful guard would otherwise hide behind a
bare `:malli.core/invalid-input`."*

Every catch site in the pipeline followed the same three-step order, in
`render` (:1109-1132) and `render-entity-html` (:404-426):

1. `err/record!` the failure as a **durable classified fact** —
   `err/fault-for` decides `:agent` (an agent-authored render symbol) vs
   `:core` (a first-party converter), so "who should fix this" is a
   query;
2. `strict-fail!` — panic in dev/test/benchmark;
3. only then the production face: `;; ⚠ [block-id] render failed: <msg>`
   for `/ai`, `canvas/error-card` for `/html`. Siblings render untouched,
   the page stays 200.

And the anti-placeholder rule the PRD wants was already written
(`render.cljc:1047`):

```clojure
(defn- missing-render
  "A legible, self-healing line for a slot symbol that resolves NOWHERE
   (neither SCI source nor a compiled var). Surfaces loudly instead of
   silently dropping the block — the agent sees what to fix; defining the
   fn self-heals the block next render."
  …)
```

That is the correct treatment of the banned `renderer unavailable` div:
name the missing symbol so the reader can fix it, and let the fix heal
the block on the next render.

### Stage contracts

- **One envelope unwrap** (`unwrap-response`, :312) — *"This is the ONLY
  place the envelope is unwrapped, so entity surfaces, context blocks,
  and recursive sections never leak a raw map into hiccup (the
  map-renders-empty bug). No second extraction site, no second
  contract."*
- **One resolution ladder** (`resolve-render`, :1058) — five ordered
  steps ending in the generic any-data default. The floor-first idea is
  step 5, and it existed.
- **One trust boundary** (`symbol-call`, :63) — agent-authored symbols go
  through `::invoke-authored!`, first-party symbols resolve compiled.
  Failure to resolve is a *value*, not a nil.
- **One recursion handle** — `render` injects `:seon.render/render` so a
  section renders children through the same dispatch; there is no second
  path down.
- **Assembly is honest about accounting** — `rendered-child-blocks`
  stamps `:seon.render/token-estimate` per block at render time, and
  `rendered-blocks->context-text` reuses those exact strings, *"so
  callers can reuse the same block strings for debug token accounting
  instead of invoking every AI renderer a second time."* Whole-prompt
  accounting was exact because it counted the bytes it shipped.

### Honest negatives on §0

1. **The dial defaulted OFF.** `strict-fail!` reads
   `:seon.config.render-context/render-strict?` with `false` as the
   default (`render.cljc:370-375`). Absent config ⇒ silent degrade. That
   is the project's own recurring failure class — absence of signal read
   as health — sitting inside the mechanism designed to prevent it. The
   revival must invert it: **strict by default, prod opts out.**
2. **Not every seam was on the dial.** `value.cljc` carries eleven
   `try`/`catch` pairs that never call `strict-fail!` — `prepare-ai`
   (:1151) catches and falls back to a skeleton render;
   `dominant-string-entry` (:1068) catches and returns nil;
   `ui/clojure.cljc:190` catches and degrades to plain source. Those are
   real swallows. Some are defensible (a poisoned lazy seq must not crash
   the turn) but each should have routed its caught exception through
   the one dial. The PRD's "no silent swallowing anywhere" needs to be
   enforced as a **graph query over catch sites**, not as a convention —
   the old tree proves the convention alone does not hold.
3. **`format-transcript-block` swallows structurally**, not by catch:
   `(if-let [error (::error input)] (str "[transcript] render failed: "
   (pr-str error)) …)` (:1301). An acquisition error becomes a string in
   the prompt — no fact recorded, no dial consulted. This is the exact
   "papered-over hole" the owner described.

**Verdict — revive-adapted, and it is the highest-value item in this
report after the tokenizer.** The dial, `loud-explain`'s Malli
attachment, the record-then-panic-then-face ordering, `missing-render`,
the single envelope unwrap, and the `:agent`/`:core` fault classification
are all directly portable and all missing from the fresh tree. Port them
with the default inverted and with the census enforced by query.

---

## Closing verdict — one page

### Revive (in descending value)

| What | Where | Note |
|---|---|---|
| **Clojure tokenizer → hiccup** | `9e44815f5:src-old/seon/ui/clojure.cljc` | **Verbatim**, swap `hljs-*` → `seon-print-*`. 192 lines. The fresh tree has no highlighter. Gates §3 item 2 |
| **The strict render dial** (`strict-fail!` + `loud-explain` + record-then-panic-then-face + `missing-render`) | `seon/render.cljc:327-426`, `:1047`, `:1109-1132` | Adapted, **default inverted to strict**. Gates §0 |
| **Block chain-hash on output bytes** | `seon/agent/ctx.cljc:1856-1959` | Adapted. Answers rip-out #10; staleness becomes unconstructable |
| **`emit`/`fits?` pretty-printer** | `seon/render/value.cljc:970-1004` | Adapted onto the current elision-node tree. Gates §3 items 1-2 |
| **Capped-writer print** | `seon/ai/tokens.cljc:166-207` | Adapted. Never materialize a huge value to cut it |
| **`dominant-string-entry`** | `seon/render/value.cljc:1028-1068` | Near-verbatim. Kills the payload-as-stub ugliness class |
| **Drill admission triad** (closed request, narrow-only limits, non-drillable key indexes) | `seon/render/value.cljc:1284-1360`, `:589-632` | Adapted onto the requery seam, single profile not seven dials. Gates §3 item 5 |
| **Chat bubble shapes** | `seon/render/chat.cljc` | Adapted for the chat page. Gates §3 item 3 |
| **One envelope unwrap** | `seon/render.cljc:290-325` | Adapted as the render output stage contract |

### Lessons only

- **The transcript's four rulings** — flat interleaved event log with no
  turn containers; reserved runtime glyphs as single-source `def`s
  (anti-fabrication, T4 6/24); error-run coalescing with a teaching line;
  byte-stability above the cache breakpoint (`clock` throws on a missing
  stored `:at`). The 1612-line file itself is fused to the deleted
  pod/block-entity world — do not port it.
- **The divergence guard** — the founding bug was a webview showing a
  context the agent never received. "One derivation, three faces" must
  ship with a byte-identity assertion, not a diagram.
- **`ui/html.cljc`'s escape-by-default + explicit `raw`** contract, and
  `ui/markdown.cljc`'s `safe-link-url` refusal of `javascript:`/`data:`
  in agent-authored content.
- **Result decay by turn age**, byte-stable within a band — a good idea
  worth re-deciding against the current profile, not porting blind.
- **The loud truncation marker's *reason*** — an agent was observed
  summarizing invented content from a silently clipped render.

### Correctly deleted

- **The seven `:seon.config.render/value-*` dials.** Every caller re-read
  them individually; the fresh tree's one database-derived render profile
  is strictly better. Do not let the ported drill code drag them back.
- **The `render-strict?` default of `false`.** Absence of config read as
  health — the project's own named failure class, inside the guard meant
  to prevent it.
- **The block-entity/profile machinery** (`:seon.agent.ctx/profile`,
  stored block rows, per-block `token-cap` with `:head`/`:tail` keep).
  Stored, hand-ordered, hand-capped context blocks are exactly the
  derive-don't-store violation the fresh tree dissolved.
- **`format-transcript-block`'s error-to-string branch** — a swallow with
  no fact and no dial.
- **`clip-string`'s bare `subs`** — the same defect the PRD is removing
  from `fit-projected`. There is no form-aware fit in the archive; §3
  item 1 is genuinely new work built on the archive's *architecture*.
- **Everything CLJS/pod-shaped** — `^{:async}` acquisition, the
  `CappedWriter` deftype, `render/system.cljs`, `ui/agent_view.cljs`.
  Read for intent, never ported.

### The single best piece of deleted code

**`9e44815f5:src-old/seon/ui/clojure.cljc`** — the 192-line server-side
Clojure tokenizer. Total by contract, morph-safe by construction,
dependency-free, single-pass, and it argues its own design in the
docstring against the exact client-side alternative the fresh tree would
otherwise reach for. It is the one file in this dig that should be
restored nearly byte-for-byte, and the fresh tree currently has nothing
in its place.
