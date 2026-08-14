---
type: research
status: current
tags: [research, render, print, prior-art, vendored]
---

# Value printer / browser prior art — vendored survey

Read 2026-08-14 in `reference-code/` at the vendored commits. Companion to
[the archaeology note](value-printer-archaeology-2026-08-14.md), which says
what Seon HAD; this says what the neighbours BUILT. No dependency is being
added — this is vocabulary and mechanism grounding for our own one printer,
per the vocabulary law (take the seam's names and semantics).

Everything below was read as source. Citations are `file:line` relative to
`reference-code/`.

---

## 1. reveal (Vlad Protsenko) — `reveal/src/vlaaad/reveal/`

Reveal is a JavaFX value browser. Its printer half is a **streaming emitter
of ops**, not a string builder, and that single decision is what makes every
other mechanism below possible.

### 1.1 The emitter is a transducer over ops, not a tree

`stream.clj:61-88` — every printed thing is a function `(fn [rf acc] …)`
that pushes ops into a reducing function. The op vocabulary is exactly seven:
`::push-value` / `::pop-value` (`stream.clj:65-68`), `::push-block` /
`::pop-block` (`stream.clj:84-87`), `::string` with a style
(`stream.clj:79-82`), `::separator` (`stream.clj:70-71`), `::newline` /
`::newrow` (`stream.clj:73-77`). `=>` (`stream.clj:40-59`) composes them and
**propagates `reduced?` at every step** — so a downstream consumer can halt
the emitter mid-value, at any depth, without an exception.

Verdict: **adopt the semantics, keep our Sink names.** Our `Sink` protocol
(`src/seon/print.cljc:187-207`) is the same idea with `-open`/`-token`/
`-close` instead of ops, and our tee is strictly better than reveal's
`override-style` re-wrapping. What we lack is reveal's **short-circuit
channel**: a sink cannot currently say "stop, I am full". That is the single
most valuable import from this codebase.

### 1.2 Bounded projection by short-circuiting, not by pre-sizing

`stream.clj:418-456` (`str-summary`) and `stream.clj:373-416` (`fx-summary`)
run the SAME emitter through a character budget: each `::string` op
decrements a remaining count, and when it goes negative the reducing function
returns `reduced` after trimming the last chunk and appending `…`
(`stream.clj:440-449`). `oneduce` (`stream.clj:369-371`) runs it once.

This is a bounded print of an **arbitrary, possibly infinite** value with no
sampling pass, no size pre-computation, and no re-emission. It terminates on
`(range)` because the emitter is pull-driven and the budget reduces.

Verdict: **adopt.** This is the direct replacement for our `fit` convergence
loop (`src/seon/print.cljc:908-943`), which re-emits the entire tree per
halving iteration. A budget-carrying sink that returns `reduced` gives one
traversal and a hard bound; the archaeology note's "sample→emit, in that
order" and this are the same guarantee reached from opposite ends, and this
end is cheaper.

### 1.3 `fits?` as a short-circuiting probe over the emitter

`stream.clj:234-247` — `sf-wider-than?` reduces the emitter counting
`::separator` as 1 and `::string` as its length, `::newline` as reset to 0,
and returns `reduced` **the moment** the width is exceeded. Cost is O(width),
not O(value). `sf-multi-line?` (`stream.clj:249-255`) is the same trick for
"does this contain a newline".

Verdict: **adopt.** This is the missing primitive behind archaeology item 6
(inline-when-fits). Contrast with orchard's pp (§2.4), which answers the same
question by fully printing the subform to a string — correct but quadratic.
Reveal's version is the design we should copy.

### 1.4 Layout: two block types, indentation derived from nesting

`stream.clj:191-195` — only `:horizontal` and `:vertical` blocks exist (plus
`:paragraph` for raw strings, `stream.clj:181`). The formatter
(`stream.clj:549-568`) computes indentation structurally: a `:horizontal`
block indents to **the current line length** (hanging indent under whatever
preceded it); a `:vertical` block **inherits** its parent's indent; a
top-level block indents 0. `::newline` emits a blank segment of that width
(`stream.clj:570-572`).

The inline-vs-break decision is a per-collection predicate, not a width
solver: `horizontal-item?` / `horizontal-coll?` (`stream.clj:307-330`) — in
"slim" mode a collection stays on one line only if every item is a non-coll
scalar whose rendered width is ≤ 20 chars (`slim-value-character-limit`,
`stream.clj:30`); in default mode simply "no child is a collection".
`entries` (`stream.clj:257-282`) adds the map rule: if the VALUE is
multi-line and the KEY is wider than 6 chars (`slim-key-character-limit`,
`stream.clj:29`), break the pair onto two lines with a two-separator indent;
otherwise `key <sep> value` inline.

Verdict: **adapt.** The block model (two kinds, hanging indent for
horizontal, inherited for vertical) is simpler than a Wadler document algebra
and covers everything we render. Take it. The magic constants 6 and 20 are
exactly the "tuned constant standing in for an observable event" our own
rules ban — replace them with the `fits?` probe of §1.3 against the profile's
width, which is the observable.

### 1.5 Deref-ables are never forced

`stream.clj:1044-1054` (Delay), `1066-1104` (promise / future / other
`IBlockingDeref`), `1026-1034` (`IRef`), `1106-1114` (Volatile). A `Delay`
prints `(delay ...)` unless `realized?`; a future prints `cancelled`,
`pending`, or — if realized — the value, with a `try` that renders a thrown
deref as an error-styled block (`stream.clj:1091-1095`). Every opaque object
carries an identity token: `#_0x1f3a` as a comment
(`identity-hash-code-comment`, `stream.clj:597-598`), or a live selectable
`0x…` (`stream.clj:591-594`).

Verdict: **adopt wholesale.** This is a complete answer to "what does the
printer do with a thing that could block". `realized?`-gated, state-named,
identity-tagged. Our admission layer should carry the same three states, and
the identity-hash comment is a better opaque token than a bare `#‹…›` because
it round-trips as readable Clojure.

### 1.6 Erroring values: partial output plus a typed tail

`stream.clj:355-367` — `emit-xf` wraps the per-value stream in `try`, and on
`Throwable` streams `(-> ex Throwable->map (assoc :phase :print-eval-result)
m/ex-triage m/ex-str)` as an `:error`-filled block **onto the same
accumulator**. Output already produced is kept; the failure is appended in
place, using `clojure.main`'s own triage vocabulary.

Verdict: **adopt.** Our printer must never lose the prefix it already
emitted when a lazy seq throws at element 4001. Note this is a weaker
guarantee than the archive printer's `sample` promise ("never throws"): it
catches at the top of one emit, not per element. We want BOTH — the guard at
realization (archaeology item 3) and this catch as the backstop.

### 1.7 Boundedness in the panel is cancellation, not a limit — be honest

`view.clj:62-76`, `86-97`, `114-143`, `159-185` — the panel runs
`stream/stream-xf` through `(partition-all 128)` and
`(take-while (fn [_] @*running))`, on a daemon future, feeding lines into the
canvas. An infinite value streams forever in 128-line chunks until the user
navigates away or a newer value arrives (`view.clj:130-132` even cancels the
in-flight render when the watched ref changes again).

Verdict: **ignore for the agent-text sink, note for the HTML sink.** Reveal
can be unbounded because a human closes the window; our agent text has a
token budget and no human. But the *chunked, cancellable, latest-wins*
render is precisely the shape of our HTML block feed, and it is the same
sliding-1 semantics we already ruled for render packages.

### 1.8 Face vocabulary — eight fills, semantic not visual

`style.clj:20-27`: `:util :symbol :object :string :error :success :scalar
:keyword`. Each is defined per theme; the OP carries the semantic name and
the theme resolves the color at paint time. `override-style`
(`stream.clj:332-340`) rewrites fills over a whole substream (used to paint
an entire ex-data red, `stream.clj:816`).

Verdict: **adopt the vocabulary, near-verbatim.** These eight names cover
everything our HiccupSink needs classes for, and `:util` (delimiters,
ellipses, comments, line noise) is the name we are missing — it is exactly
"chrome that the reader's eye should skip", which is what an elision marker
and a closing bracket have in common.

### 1.9 Navigation: value identity travels with the emitted text

`with-value` (`stream.clj:65-68`) pushes an `AnnotatedValue`
(`stream.clj:27`) — the value plus an annotation map. The formatter
(`stream.clj:539-547`, `501-516`) assigns each pushed value a fresh id and
tags each emitted region with `{:ids … :start-row …}`; `nav.clj:44-110`
folds those regions into `id->grid` / `id->coordinate` / `id->parent` /
`cursor->id`, giving 2D cursor movement over the *structure* while the text
stays flat.

Crucially, the annotation is not a path — it is `{:nav/coll c :nav/key k}` or
`{:nav/coll c :nav/val v}` attached at each entry (`stream.clj:267-270`,
`289-295`). Actions (`action.clj:65-72`) then call `clojure.datafy/nav` on
the CONTAINING collection. Available actions are computed per value by
running every registered predicate against it (`action.clj:37-58`) — a
navigation surface derived from the value, never a fixed menu.

Verdict: **adapt the region/id model, ignore the datafy-nav coupling.**
Reveal keeps the live value in memory, so `(coll, key)` suffices. Our HTML
sink serves a browser and our agent text serves a later turn, so we need what
the archive printer had — a real `get-in` path on every retained position
(archaeology item 4). Adopt: emitted regions carry a structural id, and the
*set of available actions is derived from the value*, not enumerated. That
derivation is the honest way to build the drill browser's affordances.

### 1.10 Truncation markers

`memory.clj:127-128`: `"..." + (- (count truncated) 100) + " more"` and,
notably, the truncated bucket is itself **navigable** — `:branch?` is true
for it and `:children` returns the next 100 (`memory.clj:139-145`). The
elision is a node you can open, which is our `elision-node` idea reached
independently. Stack traces elide as `"... N more"` (`stream.clj:849`, `930`)
after a 32-frame (or 1-frame, when there is a cause) window
(`stream.clj:894`).

Verdict: **adopt** the "elision is an openable node carrying its own
continuation" framing; we already have the node (`print.cljc:694-707`), and
this is independent evidence that the bare-`"..."` default is the defect.

---

## 2. orchard — `orchard/src/orchard/` (inspect.clj, print.clj, pp.clj)

Orchard is the closest thing in the ecosystem to what we are building: a
bounded printer plus a paged, path-tracking browser, driven over a wire by a
thin middleware. Read: `inspect.clj` (1287 lines), `print.clj` (304),
`pp.clj` (408), `TruncatingStringWriter.java` (90).

### 2.1 The whole limit vocabulary, in one map

`inspect.clj:42-53`:

```
:page-size 32        ; "= Clojure's default chunked sequences chunk size"
:max-atom-length 150 ; longest single atom written in one call
:max-value-length 10000 ; total printed size of one value
:max-coll-size 5     ; bound to *print-length*
:max-nested-depth nil ; bound to *print-level*
:analytics-size-cutoff 100000
```

Five orthogonal limits, each with a distinct failure it prevents; validated
as positive ints (`inspect.clj:243-254`) and bound as dynamic vars only at
the render boundary (`inspect.clj:1217-1229`). Note `:page-size 32` is
justified by a real observable (the chunk size a lazy seq realizes anyway) —
that is the correct grammar for a constant.

Verdict: **adopt the vocabulary and the separation.** Our profile currently
carries a token budget plus max-children/max-depth and derives a string limit
from the budget (`print.cljc:918-921`), then destroys strings first when it
overflows. Orchard's `max-atom-length` (per-atom) and `max-value-length`
(total) are two DIFFERENT bounds and neither is derived from the other; that
separation is what stops the "halve the payload first" inversion the
archaeology note flags. `:page-size` is the name for what a drill window is.

### 2.2 The total budget is enforced at the sink and aborts the traversal

`TruncatingStringWriter.java:37-89` — a `StringWriter` with two limits. Every
write decrements the total; on exhaustion it appends `"..."` and **throws
`TotalLimitExceeded` (an `Error`, not an `Exception`)**, which
`print-str` catches at the top (`print.clj:296-304`). A single write longer
than `singleWriteLimit` is clipped and gets its own ellipsis
(`TruncatingStringWriter.java:50-56`).

Verdict: **adapt.** The mechanism — budget owned by the SINK, traversal
aborted the instant it is exhausted, one traversal — is right and is the same
guarantee as reveal's `reduced`. Use reveal's `reduced` rather than orchard's
thrown `Error`: we have a total-boundary law and a printer that must never
throw, and a control-flow `Error` through arbitrary user `print-method`
implementations is the kind of thing that escapes. Deliberately choosing
`reduced` over `throw` here is a design decision worth recording.

### 2.3 Realization guards throughout

- `counted-length` (`inspect.clj:96-103`): use `count` only for `Counted` /
  `Map` / array; for anything else `bounded-count (inc page-size)` and return
  **nil** — a typed unknown — when it exceeds. Absence of a count is
  represented, not faked.
- `pagination-info` (`inspect.clj:105-141`): grab `page-size + 1` items via
  `(comp (drop start-idx) (take (inc page-size)))`. The +1 is the entire
  "is there more?" test — no counting required. When length is unknown,
  `last-page` becomes `Integer/MAX_VALUE` and the UI prints `"?"`
  (`inspect.clj:134-137`, `575-576`).
- `print-coll` (`print.clj:85-89`): `RT/iter` is itself wrapped in `try`;
  a collection whose `.iterator` throws renders as `<<exception>>` instead of
  killing the print.

Verdict: **adopt all three.** The `n+1` fetch is the cheapest possible
"more exists" signal and it works on infinite seqs. The nil-count-means-
unknown rule is our own typed-unknown law, independently arrived at. And note
what orchard does NOT do: it never guards *element realization* itself, so a
poisoned lazy seq still throws out of `print-coll` — the archive printer's
head+1 guard (archaeology item 3) remains ours alone and remains necessary.

### 2.4 pp: linear-vs-miser, decided by printing the subform

`pp.clj` is Eero Helenius' `pp` (Goldstein 1973), vendored into orchard.
`print-mode` (`pp.clj:160-173`) decides per form: linear-print the subform to
a string, and if its length ≤ `(remaining writer) - reserve-chars`, print
inline; else "miser" — one child per line at the block's indentation.
Indentation is derived from the open delimiter's width
(`pp.clj:205-213`: `(` → 1, `#{` → 2, `#:ns{` → its own width), so children
align under the first one. `reserve-chars` (`pp.clj:254`, `307-310`) reserves
columns for the closing delimiters of ANCESTOR forms and for the map-entry
separator — the detail that stops a line from overflowing by exactly the
brackets. `CountKeepingWriter` (`pp.clj:69-108`) tracks column, resetting at
newline. Map entries get their own decision (`pp.clj:263-279`): if the value
fits after the key, same line; else indent and break.

Depth is `*print-level*` compared against a carried `:level`
(`pp.clj:145-149`), emitting a bare `#`.

Verdict: **adopt the algorithm shape, with reveal's probe.** This is the
inline-when-fits logic the archaeology note wants back (item 6), stated
completely, including the two details we would have missed: delimiter-width
indentation and `reserve-chars`. Replace `print-mode`'s full linear print
with reveal's short-circuiting `sf-wider-than?` (§1.3) and it is both correct
and cheap. Ignore the `#` depth marker — that is precisely our bare-cut
defect, and an elision node carrying path + count is strictly better.

### 2.5 The browser: page/window, stack, path, index

The inspector is a **plain map**, threaded through pure render functions
(`inspect.clj:344-354`), holding:

- `:value`, `:stack`, `:pages-stack`, `:view-modes-stack`, `:path`,
  `:current-page`, `:index`, `:rendered` (`inspect.clj:1246-1252`).
- **Path is Clojure navigation forms, not opaque keys**:
  `push-item-to-path` (`inspect.clj:31-40`) conjes `(nth i)` for a seq item,
  the keyword itself or `(get k)` for a map value, and — critically —
  `'<unknown>` when the role is unknown, after which `render-path`
  **refuses to display the path at all** (`inspect.clj:1177-1186`). A path is
  shown only when it is a genuine, re-executable navigation.
- `:index` is a flat vector: `render-value` (`inspect.clj:397-411`) appends
  `{:value v :role r :key k}` and emits `[:value <display-string> <idx>]`, so
  the client refers to a rendered position by integer and `down`
  (`inspect.clj:205-211`) looks it up. The wire never carries values.
- `down*` / `up*` (`inspect.clj:166-203`) push and pop `:value`, page, and
  view-mode together, so returning to a parent restores the page you left it
  on.
- Siblings (`inspect.clj:213-241`): only defined when the last path segment
  is `(nth i)`; recompute `i ± 1` from the PARENT, and if `nth` throws or the
  index is absent, **return the inspector untouched** ("so that the UI remains
  untouched", `inspect.clj:228`).
- `render-value-maybe-expand` (`inspect.clj:588-594`): a sub-collection is
  expanded inline if its counted length ≤ page-size, else rendered as one
  compact drillable value. One rule, applied everywhere (meta, datafy,
  analytics, ex-data).
- Leading/trailing page ellipses (`inspect.clj:596-605`) mark that you are
  mid-collection in BOTH directions.
- View modes (`inspect.clj:305-340`): `:hex :normal :table :object`, each with
  a `view-mode-supported?` method computed from the value
  (`:table` iff the chunk's items are maps/lists/arrays,
  `inspect.clj:316-319`); `:normal` is supported iff a non-fallthrough
  renderer exists (`inspect.clj:309-312`) — support derived from the method
  table, never a list.

Verdict: **adopt the state model almost entirely.** `:index` + integer
reference is exactly what our `data-seon-path` HTML attributes are reaching
for, and the stack-of-pages restore-on-return behaviour is what makes a drill
browser feel like a browser. Adopt the path law verbatim: **a path is either
a real navigation or it is not shown.** Adopt derived view-mode support — our
table face (`table-data`/`emit-table`) should advertise itself the same way.
Adapt the sibling rule (its "no-op rather than lie" posture is our
total-boundary law). Ignore `:hex`.

### 2.6 Analytics: summarize instead of truncate

`inspect/analytics.clj` computes frequencies, ratios, and shape heuristics
over the first `*size-cutoff*` (100k) elements (`analytics.clj:13, 22`), with
`count-pred` (`analytics.clj:39-45`) returning both a count and a ratio under
a hard limit, and heuristics like `list-of-tuples?` (`analytics.clj:50-56`:
"≥20 of the first 100, or ≥30%") deciding a collection's SHAPE from a bounded
sample.

Verdict: **adapt.** This is the general form of the archive printer's
"sampled columns `{:a :b :c}`" shape hint (archaeology item 5): when a
collection is too big to show, show what it IS. The bounded-sample-then-
characterize pattern is the mechanism; the specific statistics are not our
business yet.

---

## 3. cider-nrepl — `cider-nrepl/src/cider/nrepl/middleware/inspect.clj`

The middleware is 179 lines and adds **no rendering** — which is the finding.

- The inspector lives in **session metadata**, mutated with `alter-meta!`
  (`inspect.clj:20-24`), and every op is `(swap-inspector! msg f & args)` →
  `inspector-response`. Navigation state is server-side and per-session; the
  client holds only integers.
- The response is two strings: `:value` = `(pr-str (seq (:rendered …)))` and
  `:path` = `(pr-str (seq (:path …)))`, printed with `*print-length*` and
  `*print-level*` **explicitly unbound** (`inspect.clj:31-33`) so the render
  instructions themselves are never truncated. The bound value was already
  reduced upstream; the transport of the reduced form must be total.
- The op surface IS the navigation protocol: `push` (with `:idx`), `pop`,
  `next/previous-sibling`, `next/prev-page`, `refresh`, `toggle-view-mode`,
  `def-current-value`, `tap-indexed` (`inspect.clj:130-178`).
- Config arrives per-message and is merged, not replaced
  (`msg->inspector-config`, `inspect.clj:41-50`; `refresh`,
  `inspect.clj:256-271`), and changing `:page-size` resets `:current-page`
  (`inspect.clj:267-269`).
- All the `set-max-*` ops are deprecated aliases of one `refresh`
  (`inspect.clj:104-107`) — one mechanism, accreted in place.
- `def-current-value` (`inspect.clj:273-278`) interns the drilled value as a
  Var in a namespace: the escape hatch from browsing back into evaluating.

Verdict: **adopt two things, ignore the rest.** (a) The **print-limits-off
transport rule**: whatever we send the browser or persist as a package must
not be re-truncated in transit — bounding happens once, at the printer. (b)
`def-current-value` is the archive printer's live `result/<id>` var
(archaeology item 4) as an explicit operation; it is the affordance that
makes a drill hint honest, because the agent can actually reach the value.
The nrepl op table itself is not a model for us — our navigation is HTTP
routes and database facts, not ops on session metadata.

---

## 4. malli — `malli/src/malli/dev/virhe.cljc`, `dev/pretty.cljc`, `error.cljc`

Malli's pretty printing is the cleanest example of **layering a
schema-aware renderer over a general one**.

### 4.1 Three layers, strictly separated

1. **Document algebra** — fipp (`[:group …] [:span …] [:align n …] :line
   :break`). Malli does not implement layout at all; it emits documents and
   `fipp.engine/pprint-document` decides the breaks (`virhe.cljc:119-128`).
2. **Visitor** — `EdnPrinter` implements `fipp.visit/IVisitor`
   (`virhe.cljc:37-106`), one method per value kind, each returning document
   nodes. Unknown values route through `visit-unknown` (`virhe.cljc:41-44`),
   which tries a caller-supplied `unknown` fn **inside a try/catch** and
   falls back to `fipp.ednize/edn` — an extension seam that cannot break the
   printer. Malli passes `(fn [x] (when (m/schema? x) (m/form x)))`
   (`pretty.cljc:15`): a schema object renders as its form.
3. **Formatters** — `-format` is a multimethod on `(-> e ex-data :type)`
   (`virhe.cljc:183`), with one method per error class
   (`pretty.cljc:41-158`), each composing `-block` / `-section` / `-visit` /
   `-link` (`virhe.cljc:151-177`). The `::default` method walks the exception
   **class hierarchy** looking for a method (`virhe.cljc:186`) — dispatch by
   real type relationships, not a lookup table.

Verdict: **adopt the layering; we already have the pieces.** Our
`:seon.render/ai` / `/html` / `/form` projections are layer 3, and our node
grammar is layer 1. What is missing is the explicit **layer-2 seam**: one
place where "how do I turn an unknown object into something printable" is
answered, guarded, and overridable per profile. `visit-unknown` is the name
and the shape.

### 4.2 Faces are zero-width pass-through nodes

`-color` (`virhe.cljc:25-31`) wraps a body as
`[:span [:pass "\033[38;5;45m"] body [:pass "[0m"]]` — fipp's `:pass`
means "emit but do not count toward width". Under CLJS the same call returns
`[:span body]` with no color at all.

Verdict: **adopt as a hard rule.** Styling must never enter the width
computation. Our tee already gets this structurally (the text sink receives
`face` and can ignore it) — this names the invariant so it stays true when
someone adds ANSI to the terminal sink.

Face vocabulary (`virhe.cljc:15-23`): `:title :title-dark :text :link
:string :constant :type :error`. Smaller than reveal's and more
document-oriented (`:title`, `:link`); reveal's is the better fit for values,
malli's `:link` is the one we would add.

### 4.3 Relevance elision: mask what is VALID

`error.cljc:227-239` — `-error-value` rebuilds the value keeping only the
paths that carry errors (`-replace-in`, `error.cljc:223-225`), then `-masked`
(`error.cljc:234-239`) walks the original against the reconstruction and
replaces every valid position with the mask value, which
`pretty.cljc:17` sets to the symbol `...`. Sets collapse to a count
difference; sequences are padded with the mask to the original length
(`-fill`, `error.cljc:187`) so INDICES STAY TRUE.

Verdict: **adopt the idea, it is the missing axis.** Every mechanism in this
survey elides by SIZE. Malli elides by RELEVANCE, and preserves positional
truth while doing it — a masked vector still has the right length, so the
error's index means what it says. Our elision node has `path`, `omitted`,
`total`; the concept of "omitted because uninteresting, not because too big"
is a second `elision-unit` we do not have and clearly want the moment context
generation starts pulling values that are 99% scaffolding around one
interesting leaf. It is also the principled version of the archive printer's
`dominant-string-entry` (archaeology item 2): promote what matters, mask the
rest.

`-push-in` (`error.cljc:195-210`) has the complementary rule: **"error
present, let's not go deeper"** (`error.cljc:199-200`) — once a position is
marked, stop descending. A depth-cut driven by findings rather than by a
counter.

---

## 5. datastar / hyperlith — server-rendered progressive disclosure

Brief, as scoped.

- `hyperlith/src/hyperlith/extras/ui/virtual_scroll.clj` — a complete
  server-driven virtual scroller. The server computes, from scroll position
  and item size, an `offset-items` window plus a buffer
  (`virtual_scroll.clj:26-77`), renders ONLY the window, and emits a spacer
  div plus a CSS `translate` to fake the full height
  (`virtual_scroll.clj:239-245`). The client re-requests only when scroll
  crosses a server-computed `threshold-low`/`threshold-high`
  (`virtual_scroll.clj:13-24`), and the thresholds are set at 50% of the
  buffer (`virtual_scroll.clj:56`) so a fetch is in flight before the buffer
  is consumed. The buffer size is derived from **scroll speed** (items per
  4000px), not visible count (`virtual_scroll.clj:33-41`) — the comment says
  so explicitly, and it is the right derivation.
- `datastar/library/src/plugins/attributes/onIntersect.ts:14-52` —
  `data-on-intersect` with `once` / `half` / `full` / `threshold`
  modifiers, backed by `IntersectionObserver` and self-disconnecting on
  `once`. The minimal "render this when it scrolls into view" primitive.

Verdict: **adapt when the drill browser meets a big collection; ignore for
now.** Our `<details>` structural browser is the right first surface — it is
free, needs no JS, and matches "elision node you can open". The moment one
elision node's expansion is itself 10k items, this is the mechanism, and it
composes with orchard's page/window model directly: `offset-items` IS
`start-idx`, `rendered-items` IS `page-size`. Worth noting now so the elision
node's `next-offset` field is designed to be the thing a scroll handler
posts back.

---

## What the survey changes about our synthesis

1. **The budget belongs to the sink, and the sink can say "stop".** Both
   mature printers (reveal via `reduced`, orchard via a throwing writer)
   enforce the total bound at the write seam in ONE traversal. Our `fit`
   convergence loop (`print.cljc:908-943`) is the outlier and should be
   deleted in favour of a budget-carrying sink that short-circuits. Choose
   `reduced` over `throw` — a printer that must never throw cannot use
   control-flow exceptions through third-party `print-method`.
2. **`fits?` is a short-circuiting probe, not a re-render.** reveal's
   `sf-wider-than?` (`stream.clj:234-247`) costs O(width). Adopt it as the
   primitive, then orchard's `pp` linear/miser algorithm
   (`pp.clj:160-173, 205-213, 254`) becomes cheap — including the two details
   we would have missed: indent by open-delimiter width, and reserve columns
   for ancestor closing delimiters.
3. **Two independent length bounds, not one derived from the other.**
   orchard's `max-atom-length` (per atom) vs `max-value-length` (total)
   (`inspect.clj:44-46`). This structurally prevents our current
   "halve the payload first" inversion, and pairs with the archive's
   dominant-string promotion.
4. **`n+1` fetch replaces counting.** `(take (inc page-size))` answers "is
   there more" on any seq including infinite ones
   (`inspect.clj:120-133`); an uncountable collection reports **nil** length
   and the UI prints `"?"`. Typed unknown, not a guessed number.
5. **A path is shown only when it is a real navigation.** orchard poisons
   the path with `'<unknown>` and then refuses to render it
   (`inspect.clj:31-40, 1177-1186`). Our elision nodes and `data-seon-path`
   must adopt the same discipline: display-only positions are marked
   undrillable, never silently pathed.
6. **Elision has a second axis: relevance.** malli's `mask-valid-values`
   (`error.cljc:227-239`) elides the UNINTERESTING while preserving indices
   and lengths. Add an `elision-unit` for it; it generalizes the archive's
   `dominant-string-entry` and it is what context generation actually needs.
7. **Never force a deref-able; name its state.** reveal's Delay / promise /
   future / IRef handling (`stream.clj:1026-1114`) is complete: `realized?`
   gate, state name (`pending` / `cancelled` / `<failed>`), identity token as
   a readable `#_0x…` comment. Adopt as-is. Keep the archive's head+1
   realization guard on top — no surveyed printer has it, and it is the one
   thing standing between us and a poisoned lazy seq.
8. **A failed print keeps its prefix and appends a typed tail.** reveal's
   `emit-xf` (`stream.clj:355-367`) and orchard's `<<exception>>` iterator
   guard (`print.clj:85-89`) both refuse to lose already-emitted output.
9. **Faces are semantic and zero-width.** Take reveal's eight fills
   (`style.clj:20-27`) verbatim as our face vocabulary — `:util` is the name
   we were missing for delimiters, ellipses, and comments — and take malli's
   `:pass` rule (`virhe.cljc:25-31`) as the invariant that styling never
   enters width.
10. **The browser state model is a plain map with a stack.** orchard's
    `:stack` / `:pages-stack` / `:path` / `:index`
    (`inspect.clj:166-211, 1246-1252`) plus one integer index per rendered
    position is the whole drill browser, and returning to a parent restores
    the page you left. Adopt the shape; our `:index` is a `get-in` path and
    our transport is routes plus facts rather than nrepl ops.
11. **Support for a view is derived, never listed.** orchard computes
    `view-mode-supported?` from the method table and the value's shape
    (`inspect.clj:305-340`). Our table face should advertise itself the same
    way, which also gives the HTML sink its mode toggle for free.
12. **Bound the transport once, at the printer.** cider-nrepl explicitly
    unbinds `*print-length*`/`*print-level*` when serialising the already-
    reduced render instructions (`inspect.clj:31-33`). Double truncation is
    how a bounded skeleton becomes soup.
13. **The drill hint needs a reachable target.** `def-current-value`
    (`inspect.clj:273-278`) interns the drilled value as a Var. This is the
    archive printer's `result/<id>` affordance, and it is what makes a
    trailing drill hint in agent text honest rather than decorative.
14. **The HTML sink's growth path is a server-computed window.** hyperlith's
    virtual scroller (`virtual_scroll.clj:26-77`) and datastar's
    `data-on-intersect` are the mechanism for expanding an elision node whose
    contents are themselves huge; design `next-offset` now as the value a
    scroll/intersect handler posts back.
