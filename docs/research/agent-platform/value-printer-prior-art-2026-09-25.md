---
type: research
status: current
created: 2026-09-25
scope: Prior art for a budgeted, shape-spreading, path-carrying floor value printer, read from source; the smallest composition for Seon's floor.
---
# Floor value printer — prior art (2026-09-25)

Seon's floor today is two passes: `seon.render.value/value-node*` walks the value depth first
under `max-children`/`max-depth` and a shared node counter (`src/seon/render/value.clj:446-564`).
Then `seon.print/fit` repeatedly re-emits the whole tree and halves limits until
`tokens/estimate` fits (`src/seon/print.cljc:1234-1296`). This note asks how other printers
spread **one** budget across a value's shape, keep **paths** for fetching more, and stay safe
against laziness and user code. It ends with the smallest composition for Seon.

Method: static source reading only. No JVM, no tests and no `bin/seon`. Only this file was edited.
Every citation is `repo@revision path:line` at the revision below. Submodules are under
`reference-code/`. The other repos were shallow or sparse clones in `tmp/research-printers/`,
deleted after reading.

| Source | Revision | Location |
|---|---|---|
| Clerk | `4c2599946256592858ca2d65e8d453f9f77489ba` | `reference-code/clerk` |
| Portal | `7f124943ebfa865ac4976aa60ff39342ef97fe64` | `reference-code/portal` |
| Reveal | `66c2d5e37996f4f135059efbb1f46f9c6886783c` | `reference-code/reveal` |
| Morse | `ff79378a5e21aed21587bd137f0e8994a7644b56` | `reference-code/morse` |
| Orchard | `da8b9de0e1323a4f4119052b662ae0acc4930c02` | `reference-code/orchard` |
| nREPL | `f3a044153eaa6050e7447655dab5bc42cfdd68c2` | `reference-code/nrepl` |
| fipp | `255a19061fdc20d67eca09a17e9af282aa8bf909` | `reference-code/fipp` (classpath has 0.6.29) |
| puget | `f959e034ddf9c4910e290efe6b0eb49048ff1bec` | `reference-code/puget` (classpath has 1.1.2) |
| Clay / Kindly | `5ec4efe0…` / `f7fe1b6e…` | `reference-code/clay`, `reference-code/kindly` |
| clojure/clojure | `07adae6d24a0efcb4e6b39577c473b61df9d5f5b` | tmp clone; **candidate submodule** |
| erlang/otp (`io_lib_pretty.erl`, `io_lib.erl`) | `237030f6583ced4bb9aa4de54912e56e0fbfde51` | tmp sparse; **candidate submodule** (the closest algorithm) |
| elixir-lang/elixir | `96afa7ab739d80670aa3c56cdfc3e961a5b1402a` | tmp clone; candidate |
| cider-nrepl | `d23d8a32518d9dd3b282ea1d2c9221fad2642c43` | tmp clone; wiring only, not needed |
| zprint | `118d10b10df0b061d94c02cf1881a399b3cbc6c0` | tmp clone; not needed |
| CPython `Lib/reprlib.py`, Node `lib/internal/util/inspect.js`, Julia `base/show.jl`,`arrayshow.jl` | `b9546455`, `a3bb551e`, `b883b468` | tmp sparse; not needed |

I did **not** read Maria.cloud, Gorilla REPL, Oz, Notespace, Calva, Conjure, Cursive (closed) or R
`str()` from source. The table says so. Calva and Conjure print through nREPL's `wrap-print`, so
the nREPL row covers their bound. That is an inference from their documentation, not a source read.

## 1. Comparison

"User-code safe" asks: can printing run a value's `toString`, custom `print-method`, `deref` or
`count` on a host object?

| Project | Bound kind | Lazy safety | User-code safety | Cycles | Path / drill-down | Per-type viewers |
|---|---|---|---|---|---|---|
| **Clerk** | Global **node** budget (default 200, `viewer.cljc:1718-1719`), decremented depth first (`:1749-1750`). Per-viewer `:page-size` (map 10, vector/seq/set 20, string 80 chars: `:882-888,924-933`) | Pages with `drop`/`take` (`:1590-1597`). `bounded-count` to n+1000 (`:1576-1585`), which realizes about 1,020 members of `(range)` | **No.** `fallback-viewer` is `pr-str` (`:1040-1041`); `ideref-viewer` derefs (`:1022-1028`) | None needed: the budget terminates | **Yes.** Each elision carries `{:path :offset :n :total/:unbounded?}` (`:1668-1705`). The server keeps `path→wrapped-value` (`:1845-1856`) and re-presents from the offset with a fresh budget (`:1731-1734`, `webserver.clj:103-108`) | **Yes.** An ordered `:pred` list; the first match wins (`:1481`, `:1520-1545`); `:transform-fn` chains |
| **Portal** | None on the wire. The whole value is serialized; `realize-value!` walks up to 100,000 per collection (`runtime.cljc:382-390`). The UI renders lazily (`ui/lazy.cljc:7-47`) | `safe-seq` takes 100,000 of a LazySeq/Iterate/Range (`lazy.cljc:22-31`) | **No.** `pr-str` inside try, derefs skipped (`runtime.cljc:242-248`) | Identity ids and refs (`runtime.cljc:216-232,320-331`) | UI expansion by value id | **Yes.** A viewer registry in the UI |
| **Reveal** | Streaming transducer. Summaries use a **char** limit with `reduced` (`stream.clj:379-460`, `str-summary` 48) | Emission is lazy and `reduced` stops it | **No.** `:default` calls `(str x)` (`stream.clj:635-646`) | None | Selection over a live value; actions nav | **Yes.** `defstream` multimethod (`:628`) |
| **Morse / REBL** | `*print-length* 50`, `*print-level* 2`, then `finite-str 100` for list labels (`ui.clj:180-189`). Tables sample 101 (`fx.clj:20,110-146`) | By print-length | **No.** `pr-str` | print-level | datafy/nav browse pane | **Yes.** Viewer registry |
| **Clay / Kindly** | **None.** `pp/pprint` of the whole value (`clay item.clj:89-98`) | No | No | No | No | Kinds by metadata (Kindly) |
| **Orchard inspector** | Page of 32 (`inspect.clj:44`) per level. Inside a page: `max-atom-length` 150, `max-coll-size` 5, optional `max-nested-depth`, `max-value-length` 10,000 **chars** per rendered value (`:44-53`, `print.clj:118-124`) | `transduce (drop start) (take (inc page))` (`inspect.clj:130-141`). Unknown length gives `last-page MAX_VALUE` (`:145-148`) | Partial. Pending derefs are not forced (`print.clj:282-301`). Custom `print-method` is honored (`:60-82`); `:default` goes to `print-method` (`:384-385`), which calls `toString` | `*print-level*` forced to 100 (`print.clj:394-395`) | **Yes.** Path of `(nth i)`/`(get k)` (`inspect.clj:31-41`), down/up/sibling (`:177-257`) | Multimethod by `object-type` |
| **nREPL `wrap-print`** (CIDER, Calva, Conjure) | Global **char** quota per value. `QuotaBoundWriter` throws `QuotaExceeded` (`print.clj:50-57`, `QuotaBoundWriter.java:27-36`) and the reply gets `::truncated` (`:87-108`) | Only through `*print-length*` | Whatever `print-fn` does | print-level | **No.** It keeps a prefix only | pluggable `print-fn` |
| **clojure.core** | Per-collection `*print-length*`, per-depth `*print-level*` (`core_print.clj:48-70`) | `[x & xs]` destructuring realizes n+1 members, one chunk for chunked seqs | **No.** `print-object` uses `(str o)` (`:117-121`) | print-level only | No | `print-method` multimethod |
| **fipp** | print-length/level only (`edn.cljc:9-20`) | `(seq (drop n xs))` realizes n+1 | **No.** `ednize` Object gives `(str x)` (`ednize.clj:45-49`). You can override it with your own `IVisitor/visit-unknown` (`visit.cljc:32-52`) | No | No | protocol visitor |
| **puget** | `:seq-limit`/`:coll-limit` per collection (`printer.clj:40-50,562-590`) | `take (inc limit)` on seqs. `coll-limit` calls `count` (`:178-183`) | **No** by default (`format-unknown` `(str value)`, `:217-220`). But `:print-fallback` accepts a fn (`:715-741`) | No | No | `:print-handlers` by class (`:235-243`) |
| **zprint** | `:max-length` per level (can be a vector per depth), `:max-depth` (`zprint.cljc:640-648`, `config.cljc:712-724`) | per-level take | No | No | No | options |
| **Erlang `io_lib_pretty`** | **Global chars** `chars_limit`, combined with depth. **Iterative deepening:** double the depth, then binary-search the largest depth that fits (`io_lib_pretty.erl:839-893`) | Terms are strict; n/a | Yes (no user code) | n/a (immutable) | The `More` continuation per `dots` expands in place (`:809-831,1407-1441`) but does not reach the user | records via callback |
| **Elixir `Inspect.Algebra`** | `limit` 200 items (`algebra.ex:93`). **Shared** across nesting only when a nested `inspect` returns `{doc, opts}`; otherwise each level gets `limit - 1` (`:602-617`). `printable_limit` 4096 chars per string | Streams are structs, inspected opaquely | protocol impls are user code | No | No | `Inspect` protocol |
| **Python `reprlib`** | Per-type counts (`maxlist`, `maxdict`, `maxlevel`, `maxstring`…) (`reprlib.py:54-67`) | `islice` (`:113-126`) but calls `len` | Calls `repr` of unknowns | `recursive_repr` decorator (`:9-18`) | No | `repr_<type>` methods |
| **Node `util.inspect`** | `depth` 2, `maxArrayLength` 100 **per array**, `maxStringLength` 10,000 (`inspect.js:225-234`). The message is `"... n more items"` (`:2198`) | n/a | `customInspect` toggle, `getters: false` (`:227,234`) | `[Circular *n]` via `ctx.seen` (`:1173-1185`) | No | `[util.inspect.custom]` |
| **Julia `show`** | `IOContext :limit` fits the screen: head and tail halves with `⋮` (`arrayshow.jl:174-195`). Dict entries are truncated to the width (`show.jl:136-154,213-221`) | n/a | `show` methods are user code | `:SHOWN_SET` (`show.jl:165,472-476`) | No | multiple dispatch |
| **R `str()`** | Documented only (not read): `max.level`, `vec.len`, `list.len`, `nchar.max` | — | — | — | — | S3 methods |

**The headline.** No project combines all three of Seon's needs: one global budget, spreading
across the shape, and every cut naming a re-queryable path.
- Clerk has the path/offset elision and per-type viewers. Its budget is a depth-first node counter;
  the per-viewer page sizes do the spreading.
- Erlang has one global character budget that spreads across the shape. It has no paths.
- Orchard and nREPL have a character quota writer and an inspector with paging paths, but as
  separate tools.

## 2. The three designs closest to Seon

### 2a. Erlang `io_lib_pretty:intermediate/7` — one character budget, spread by depth

This is the only printer found that spreads **one character budget** across the whole term.
It builds an intermediate tree at depth 1. Every elided subterm is `{dots, 3, 3, More}`, where
`More` is a closure that can expand it deeper. Then it **doubles the depth** until the text
exceeds `T`, and **binary-searches** the largest depth that still fits
(`otp@237030f io_lib_pretty.erl:839-893`):

```erlang
intermediate(Term, D, T, RF, Enc, Str, Ord) when T > 0 ->
    D0 = 1,
    If = print_length(Term, D0, T, RF, Enc, Str, Ord),
    case If of
        {_, Len, Dots, _} when Dots =:= 0; Len > T; D =:= 1 -> If;
        {_, Len, _, _} -> find_upper(If, Term, T, D0, 2, D, RF, Enc, Str, Ord, Len)
    end.
find_upper(Lower, Term, T, Dl, Dd, D, RF, Enc, Str, Ord, LastLen) ->
    Dd2 = Dd * 2,
    D1 = case D < 0 of true -> Dl+Dd2; false -> min(Dl+Dd2, D) end,
    If = expand(Lower, T, D1 - Dl),
    ...
        {_, Len, _, _} when Len =< T, D1 < D orelse D < 0 ->
            find_upper(If, Term, T, D1, Dd2, D, RF, Enc, Str, Ord, Len);
        _ -> search_depth(Lower, If, Term, T, Dl, D1, RF, Enc, Str, Ord)
```

**Siblings share what is left.** Inside one depth, a list passes the remainder left to right:
`tsub(T1, Len1)` (`:1119-1130`, `expand_list` `:1435-1441`; `tsub` clamps at 0 `:1446-1448`).
Separators and brackets are counted (`tsub(T, 2)`, `tsub(T, 1)`). Each `dots` is priced at 3
characters. The documented result for `chars_limit 20` is `{[1,2|...],[4|...],...}`
(`io_lib.erl:605-612`): every part of the shape shows a snippet. That is the owner's sentence
almost exactly.

- **Cost:** O(log depth) expansions. An expansion reuses the lower tree (`expand` only calls
  `More` on dots, `:1407-1427`), so shown work is never repeated. `io_lib:write/2` with
  `chars_limit` calls the same function (`io_lib.erl:628-650`).
- **Missing for Seon:** paths in the output, laziness (Erlang terms are strict), user code (Erlang
  has none to run), and fairness across width (a very wide first list still eats the level).

### 2b. Clerk `present*` — budgeted viewers, elisions that carry fetch coordinates

`present` walks the value once. `apply-viewers*` picks the first viewer whose `:pred` matches,
and `:transform-fn` may rewrite the value before the viewer's own walk (`viewer.cljc:1520-1545`).
That is Seon's "declared render function, else floor", with the floor as the last entries in the
same list. Collections page through `present+paginate-children`. The shared `!budget` atom lowers
the page size:

```clojure
(defn present+paginate-children [{:as wrapped-value :nextjournal/keys [viewers preserve-keys-fn] :keys [!budget]}]
  (let [{:as fetch-opts :keys [offset n]} (->fetch-opts wrapped-value)
        xs (->value wrapped-value)
        paginate? (and (number? n) (not preserve-keys-fn))
        fetch-opts' (cond-> fetch-opts
                      (and paginate? !budget (not (map-entry? xs))) (update :n min @!budget))
        children (into [] (comp (if paginate? (drop+take-xf fetch-opts') identity)
                                (map-indexed (fn [i x] (present* (inherit-opts wrapped-value x (+ i (or offset 0))))))
                                (remove nil?))
                       (ensure-sorted xs))
        {:as elision :keys [total unbounded?]} (and paginate? (get-elision wrapped-value))
        new-offset (when paginate? (or (some-> children peek :path peek inc) 0))]
    (cond-> children
      (and paginate? (or unbounded? (< new-offset total)))
      (conj (make-elision viewers (assoc elision :offset new-offset))))))
```

(`viewer.cljc:1681-1705`, with the non-pagination branch omitted.)

- **How budget, path and fetch fit together.**
  - The budget decrements once per non-presented node, depth first: `present*`
    `(swap! !budget #(max (dec %) 0))` (`:1749-1750`).
  - The path is positional: `inherit-opts` conjoins the child's index after `ensure-sorted`
    (`:949-953`, `:1463-1468`).
  - Every stored wrapped value is kept by path (`store!-wrapped-value`, `:1845-1846`).
  - A click sends `{:path :offset :n}`. `present-elision*` finds the value at that path and
    presents it again from `offset` with a **fresh** budget (`:1731-1734`, `webserver.clj:103-108`).
  - Strings page by characters: `(subs value offset new-offset)` plus an elision whose
    `:replace-path` is `(conj path new-offset)` (`:1707-1716`).
  - The client shows `(- total offset)`, with `+` when `unbounded?` (`render.cljs:380-389`).
  - The layout pass prices an elision at 6 characters (`:1773-1780`).
- **What Seon should take:** the elision's data shape, positional paths over one canonical order,
  and re-presentation at `path` from `offset` with a fresh budget. Seon already has most of this:
  `:seon.render.data/path`, `next-offset`, `total`, and the requery form `seon.print/value-at`
  (`print.cljc:444-455`, `982-1011`).
- **What Seon should not take:**
  - The depth-first budget. The first deep child can spend it all; the per-type page sizes, not
    the budget, give the spreading.
  - `ensure-sorted`, which sorts whole maps and sets.
  - `bounded-count` to n+1000 on lazy seqs.
  - `pr-str`/`deref` fallbacks.
  - The server-side cache of live values by path. Seon's result handle plus `value-at` replaces it.

### 2c. Orchard + nREPL — a character-quota writer, and an inspector with paging paths

The quota writer is the smallest correct total-size guard in Clojure land.
`TruncatingStringWriter` (Java, `orchard/java/.../TruncatingStringWriter.java`) truncates any
single write above `max-atom-length`. Past the total it writes `...` and throws a
`TotalLimitExceeded` **`Error`**. `print-str` catches it (`orchard print.clj:387-398`):

```clojure
(defn print-str [x]
  (let [writer (TruncatingStringWriter. *max-atom-length* *max-total-length*)]
    ;; Protect against self-referencing collections by ensuring
    ;; print-level is always set to at least an unreasonably large value.
    (binding [*print-level* (or *print-level* 100)]
      (try (print x writer)
           (catch TruncatingStringWriter$TotalLimitExceeded _)))
    (.toString writer)))
```

- **The quota stops work early.** The traversal stops when the quota is hit, so cost is O(quota),
  not O(value). nREPL does the same per reply value (`QuotaBoundWriter`, `nrepl print.clj:87-108`)
  and marks it `::truncated`. Seon already has this writer: `bounded-text` (`value.clj:65-88`)
  over `datum` (`:47-63`).
- **But a quota writer is a prefix cut.** It gives one snippet of the *first* part, not snippets
  of each part.
- **Orchard's inspector** fills the path half. `pagination-info`
  (`inspect.clj:113-152`) takes `(drop start-idx) (take (inc page-size))`. The one extra member is
  how it knows there is more without counting. The length is known only for `Counted` or small
  collections (`:104-111`); otherwise `last-page` is `Integer/MAX_VALUE`, meaning possibly
  infinite. `down*` records `(nth i)`/`(get k)` steps (`:31-41`, `:197-215`) and the page it came
  from (`:pages-stack`).
- **Orchard's printer** is the only Clojure printer found that deliberately avoids forcing
  pending refs (`print.clj:282-301`). It still calls `print-method`/`toString` for unknown types
  (`:384-385`, `:204-205`).

### Also relevant, briefly

- **Reveal** shows how to make any emitter safe for lazy output: emit as a reducing function and
  return `reduced` when the character limit is hit (`stream.clj:217-232`, `424-460`). Seon's
  `bounded-text` throw is the imperative form of the same thing.
- **puget's `order-collection`** sorts only when the map is `counted?` and at most a threshold in
  size (`printer.clj:185-195`). It is the only prior art that bounds the cost of canonical ordering.
- **Elixir** shows the difference between a *shared* counter and a per-level count: a child that
  returns `{doc, opts}` hands back its remaining limit (`algebra.ex:602-617`).

## 3. Recommendation — "shell-order" allocation, then the existing emitter

**The algorithm.** Generalize Erlang's iterative deepening from depth alone to depth *and*
breadth, and give it Clerk's elision coordinates.

1. Visit value nodes in order of the key `[(max depth ordinal) depth ordinal]` from a priority
   queue. `depth` is the nesting level; `ordinal` is the member's position in its parent's
   canonical order.
2. Charge each visited node its emitted characters from the **one** character budget `B`: the
   scalar literal, or the brackets, or a string chunk, plus its separator.
3. Stop at the first node the budget cannot pay for.
4. Every unvisited child of a visited collection becomes one tail elision
   `{path, next-offset, total or unbounded}`.

**Why this order.** The visited set is exactly what `clojure.core` would print with
`*print-length* = *print-level* = k` for the largest `k` that fits `B`, plus a partial next
shell. So every part of the shape shows a k-member, k-deep snippet before anything goes deeper
or wider. That is the owner's "snippets of each part". It is explainable in one Clojure sentence,
and it needs no search loop: one pass, O(V log V) with V ≤ B/2 visited nodes.

For the owner's probe `{:sample/a {…500 a} :b … :c …}` (design §"historical live evidence"), all
three keys, their `:deep` keys and a snippet of each string appear before any string grows. The
current floor showed only `:sample/a`.

**Budget mechanics:**
- **Strings are collections of fixed-width chunks.** This is Clerk's string paging
  (`viewer.cljc:1707-1716`): chunk `j` has ordinal `j`, so long strings grow one chunk per shell
  like every other collection. Its elision `next-offset` is a character offset.
- **Map keys are coordinates.** A key is paid whole when its entry is visited, or the entry and
  all later ones are elided (the current rule, `print.cljc:1103-1120`). The value is a child one
  level deeper, so `{:a …}` with the path `[:a]` is a legal snippet.
- **Every elision is priced before it is emitted.** Opening a collection reserves one tail-elision
  face, and a node is admitted only if the reserve still fits (Erlang prices `dots` at 3, Clerk
  at 6). Seon's AI elision is a whole EDN map (`render-elision-ai`, `print.cljc:457-489`). Priced
  honestly, it will crowd out content. Emit a compact inline marker and put the path, offset,
  total and requery in one legend at the root. The slice2 review already found the shared legend
  sound (`render-floor-slice2-review-2026-09-25.md`, finding 5).

**Safety rules:**
- **Lazy safety (stricter than all prior art).** Step a sequence cell by cell with `seq`/`next`,
  and only while the next cell is not an unrealized `clojure.lang.IPending` (`realized?`). An
  unrealized tail becomes an elision with `unbounded` and a requery like `(take n …)`. This
  follows Orchard's pending-deref rule (`print.clj:282-301`) and `clojure.core`'s `deref-as-map`
  (`core_print.clj:438-461`), applied to seq cells. Nothing lazy is ever realized by the printer.
- **User-code safety (keep Seon's rule).** Walk only `clojure.lang` persistent collections. Print
  every other object as `#object[Class 0xhash]` (`datum`, `value.clj:47-63`). Count only
  `clojure.lang.Counted` persistent classes. No `pr-str`, `str`, `deref` or `print-method` on an
  unknown value; this is stricter than every row in §1.
- **Canonical order.** Take puget's rule (`printer.clj:185-195`). A counted map or set whose keys
  are all keywords, symbols, strings or numbers takes its first members with the existing
  `ordered-head` (`value.clj:425-444`), with n = the members the budget could reach, at most B/2.
  Every other map or set keeps iteration order. Never order by `pr-str` or by recursive
  `compare-values` of composite keys. That path is what hashed `(range)` and OOM-killed default
  (open-items incident 2026-09-25 03:09Z).
- **Cycles.** Persistent values cannot cycle. The printer never derefs, and host objects are
  opaque, so it has no cycle to detect. The budget terminates regardless.

**Declared render functions** stay upstream exactly as now: a reached value with a declared pair
renders less and returns a node (`value.clj:470-480`). The floor charges that node's emitted
size like a scalar. This matches Clerk's `:render-fn` taking precedence over structural walking.

**Drill-down** is `prepare` at the cursor's `path` and `offset` with a fresh `B`, as in Clerk's
`present-elision*`. That removes `window` and `get_value`'s second path (clipping-sites #13).

**Composition sketch** (value side only; nodes are the existing `seon.print` grammar; this is
illustrative, not code to paste):

```clojure
(defn- allocate [root budget]
  (loop [queue (conj (priority-queue) (visit-key [] 0 0 root)) room budget shown {}]
    (if-let [{:keys [path depth value] :as next} (peek queue)]
      (let [cost (face-cost value)]              ; literal chars | brackets+reserve | chunk
        (if (> cost room)
          (settle shown queue)                   ; every queued child → tail elision at its parent
          (recur (into (pop queue) (members next)) ; realized, canonical, ≤ room/2 members
                 (- room cost) (assoc shown path (face value)))))
      (settle shown queue))))
;; members: seq-step while realized; ordered-head for scalar-keyed counted maps; chunks for strings.
;; settle: assemble path→face into a :seon.print node tree; collection tails → print/elision.
```

**Estimated size:** about 90–110 lines, in `seon.render.value`:
- queue loop ≈ 25 lines;
- `members` (seq stepping, ordered head, string chunks) ≈ 30;
- `face-cost` ≈ 10;
- `settle` and assembly into nodes ≈ 25;
- the compact elision face plus the root legend in `seon.print` ≈ 15.

It reuses `print/elision`, `emit-text`/`emit-hiccup`, `ordered-head`, `datum`'s object rule and
`value-at`.

**What it deletes (static line counts at HEAD `f99cf6a22`):**
- `src/seon/print.cljc`:
  - The whole fit family: `preserve-requery`, `fit-entry`, `fit-children`, `projected-text`,
    `fit-text`, `structural-elision`, `fit-node`, `fit` (`:1054-1296`, about 240 lines),
    including the token search loop and the second sort of emitted keys (`:1189-1203`).
  - `elision-node`'s budget-name defaults (it stays as a coordinate check or folds into `elision`).
  - The `seon.ai.tokens` require, if nothing else needs it.
- `src/seon/render/value.clj`:
  - `value-node*`'s collection branch and the `remaining` volatile (`:446-564`, about 70 of
    those lines); its selected-pair and scalar branches become `face`.
  - `window` and `stable-entries` (`:297-356`, about 60 lines, after `get_value` calls
    `prepare` at an offset).
  - `display-value` (`:357-366`).
  - `prepare`'s `print/fit` call and budget volatile (`:626-633`).
- **Net:** about −380 source lines against +100.
- **Kept:**
  - `bounded-text`/`datum`/`floor`, the projection-free core for when the projection is what broke.
  - `compare-values` for `seon.db` diffs (`db.clj:2998`).

  A later slice can make `bounded-text` the same allocator with no projection, so there is one
  walker.
- **Config.** `:seon.render.profile/token-budget` becomes a character budget. Converting at the
  one boundary, `tokens/estimate-chars`, keeps one number. `max-children`/`max-depth`/
  `max-string-length` go away, as the design's "one budget" ruling requires.

**Simpler alternatives considered:**
- **(a) Iterative deepening over `clojure.core` printing.** Bind
  `*print-length* = *print-level* = k` into a quota writer and binary-search `k`, as Erlang does
  (about 25 lines). This is the smallest possible code. But it is **rejected**: `…` cuts carry no
  path, and `print-method` runs user code on host objects, which violates both hard requirements.
- **(b) fipp or puget with handlers.** puget's `:print-fallback` fn and `:print-handlers` can
  block user code, and fipp's `visit-unknown` is overridable. But both bound per collection, not
  globally, and neither emits paths. They would add a layout engine that Seon's single-line AI
  emission (`value.clj:639-645`, `:seon.print/width 0`) does not need. **Rejected** as the floor;
  a possible later swap for HTML pretty layout only.
- **(c) Clerk's algorithm as is.** Its depth-first node budget reproduces today's failure (the
  first key eats the budget); its spreading comes from fixed page sizes, which the owner ruled
  out. Take its elision shape and fetch protocol, not its budget.

## 4. What no prior art covers

1. **Paths inside agent-facing text.** Clerk keeps the path in data behind a UI click. Every text
   printer (Erlang, nREPL, Orchard `print-str`, core, Node) prints anonymous `...`. A text legend
   of path, offset, total and requery form is Seon-specific. So is its cost inside the budget.
2. **Width fairness under one character budget.** Erlang spreads by depth; within a level the
   first wide sibling still spends first (`tsub` left to right). The shell order is my
   generalization, not something any project ships. It is unproven here and needs its regression
   (a value like the probe, plus one wide-first and one deep-first case).
3. **Never realizing laziness.** Every printer read realizes at least n+1 members or a chunk
   (`core_print.clj:55-64`, fipp `edn.cljc:18`, Clerk `bounded-count`, Orchard `take (inc page)`,
   Portal 100,000). None survives `(filter (constantly false) (range))`, whose first `seq` never
   returns. Seon's current `datum` has the same hang (`bounded-count (inc items) x`,
   `value.clj:60`). A realized-prefix rule is the only static guarantee. A deadline cannot stop
   arbitrary host code (CLAUDE.md "Bound admission").
4. **Canonical order without touching every member.** Clerk, puget and Orchard sort whole maps
   (Orchard with try and opt-in). No one bounds ordering of composite keys. Seon needs a
   documented rule: scalar-key ordered head, else iteration order. It also needs its requery
   semantics: an ordinal path into iteration order is only stable for the same value, which a
   result handle provides.
5. **Zero user code on host objects.** Every Clojure printer read falls back to
   `pr-str`/`str`/`print-method`. Only Node offers `customInspect: false`. Seon's `#object[Class
   0xhash]` rule has no Clojure precedent; keep it.
6. **Budget in tokens.** Everyone budgets characters, bytes, nodes or items. Seon's token budget
   should convert to characters once, at the profile, with no estimate loop.
