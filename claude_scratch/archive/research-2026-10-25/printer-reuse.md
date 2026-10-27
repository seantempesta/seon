# Reusing seon.print for budgeted view hiccup (read-only study)

## 1. Selection (src/seon/print/select.clj)
- Entry: print.clj:39 print-value -> select.clj:732 show -> select (658) -> tree (724) -> emit/emit-both (emit.clj:415).
- Budget: tokens -> chars (estimate-chars :17); context :budget chars, :max-children (inc (quot chars 2)), :max-depth, :breaks? (show :732+).
- classify (156-218): value -> one row: :leaf (scalar w/ text), :string, :name, :map, :coll(vector/set/list), :scalar(var/class/object). Tied to Clojure classes (map-classes, vector-classes, set-classes :40-45) - but only here; the rest is generic over :kind.
- make-node/node-of (359-379): classified row -> alloc node {:kind :depth :path :indent :pending(members) :total ...}; placed (352) adds :requery-id from context.
- child (430): member i -> node, extends :path ([:seon.render.data/nth i] or plain index for plain vectors; map: key or [:entry i :val]).
- sized (402): :flat (one-line width) and :ub (charge incl. indentation) from kids; flat-width 297.
- whole (467): node shown fully iff charge <= cap. minimal/collapsed (501-522): least form.
- allot (643): whole if fits else fill-members (557; sequential: whole members in order, first non-fitting one gets the rest via recursive allot, then ONE cut) / fill-map (627; every key+value at least first, then grow-values 602 smallest/strings first = breadth before depth) / fill-string (524).
- Floor: select (658) errors (insufficient-budget 327) if first member can't fit.
- elisions (589): walk counting nodes with more? (291) ; tree-node (690) builds print-node tree, cut-node (675) makes the cut: {:face :elided :elision-unit :children|:characters|:pending :omitted n :continuation form}.
- Cut's read-form: node-continuation (230) -> emit/continuation (emit.clj:55): path-form (34) builds plain clojure.core `(get-in result/h3 [..])`, `(nth (seq x) i)`, then `(drop n target)` / `(subs target n)`. Needs :requery-id (handle) from opts :handle; else cut says what was omitted and "requery-refused". Text of cut: emit/elided-text (84), cut-words (75): `…N form`, `"prefix"…+N chars form`.
- `bounded`/`more` (print.clj:124-139): NOT the selection; just `+N more: where` for fixed config-bounded lists (take n). Not for views (it is a clipping fn by §9 standards).
- "One selection for text and hiccup": emit-both runs one op stream (fipp-style begin/nest/line/text), ops-text lays it out, ops-hiccup (emit.clj:383) folds same ops into <span class="seon-print-*"> hiccup. So hiccup = a syntax-highlighted rendering OF A VALUE (dashboard value panel, [:div {:class "seon-data-panel"} ...] at select.clj:769), NOT hiccup views. It is not a view-writer.
- Existing nested hook: :render (classify :163-168) only for map-classes: nested map whose schema declares a render shows as its render's one-line {:text :html} leaf (:seon.print/html). Docs: docs/render.md:5, 104-106.

## 2. Schemas / config
- resources/seon/schemas/seon.print.edn:21 :seon.print/alloc = [:map [:kind [:enum :leaf :string :name :map :coll :entry]]] (loose); :22 :seon.print/context = [:map [:budget number?] [:max-depth number?]] (loose); :seon.print/node :map, /op, /opts (:handle :budget :max-depth :width :outputs :render), /result, /hiccup.
- Context really holds: :budget :max-depth :max-children :breaks? :width :requery :requery-id :render :root + limits.
- Config: :seon.config.render.agent/{whole-width 24, max-name 256, max-number-digits 32, sort-bound 64, token-budget 6144, max-depth 8}; :seon.config.ai/chars-per-token-prior; :seon.config.render/{width,part-budget,form-budget,context-budget}.

## 3. Generic over a tree?
- Core (sized/whole/allot/fill-members/elisions/tree-node/cut-node) is generic over alloc :kind + :pending members + :total + :path/:requery-id. Only classify (+ held-order/list-row) touches classes.
- View hiccup IS already a value the printer handles: an element is a vector, [tag {props} & kids] -> :coll vector (members = tag keyword leaf, props map, children); strings -> :string nodes (cut with `…+N chars (subs ..)`), data children -> prior classification. Breadth before depth applies. Cuts read via handle path: (drop k (get-in result/v [2 1])).
- Gaps: (a) it prints as Clojure code text (prop maps verbose; class strings) - acceptable for LLM but not "HTML-like"; (b) fn-headed call vectors [f props & kids] print f as var/symbol and recurse into kids: a unit is not a leaf, its cut form would be a path, not `show unit`; (c) no per-unit cached size/summary.
- Smallest adapter: a classify hook beside :render: context :unit (fn [x] -> nil | {:text summary :form read-form}) applied to vectors with fn/symbol head (or whatever marks a unit), returning (assoc (scalar :seon.print/unit x text) :seon.print/continuation form) i.e. a LEAF whose text is `[unit id "summary"…] (seon.view/show 'id)` - never cut, sized by its text (cached summary length). Generalise :render's guard (map-classes only) to "pred in context". Emit: leaf-op already prints a leaf's :seon.print/text; the continuation text on leaf needs a 3-line leaf-op case (emit.clj:141).
- Do NOT convert hiccup to a bespoke value shape; hiccup is already a vector value.

## 4. Unit cut forms
- Path-handle cuts already cover everything inside a view that is plain data: strings, children vectors, props maps, values (as long as :handle is the view value, e.g. result/vN or `(seon.view/tree 'id)`). Handle may be a form (show :handle accepts seq).
- For a unit boundary the right read is the unit's own: `(seon.view/show 'unit-id)` (or `(seon.view/show 'id {:budget n})`), because the unit renders lazily and has its own budget/cache; path cut would need the unexpanded call vector (fine for the handle-of-hiccup case but expands f again).
- Hand-built via emit/continuation: it just needs {:requery-id :path :next-offset}; for units supply the form directly in the leaf (as above), as emit/elided-text does with :seon.print/continuation.

## 5. Recommendation
- Reuse: print-value over the view hiccup value with :handle = view's result/handle, plus ONE new context hook (:unit) in classify (~15-25 lines select.clj, ~5 emit.clj leaf-op, ~5 schema/opts + config none), and a seon.view fn that calls print-value (~10 lines). Total ~35-45 lines. Tests: cut at unit, cut in string, cut at children (3 tests).
- If the LLM must read real HTML-ish text rather than Clojure hiccup text: that is only a layout (emit) change (open/close text ops), still the same selection; defer until the owner wants it. Existing text of `[:div {:class "x"} ...]` as EDN is what a REPL would show - preferred (§4 'what would a plain REPL do').
- Do NOT: write a new elision/budget walker or `take n` + "[more]" helper (bounded/more at print.clj:124 are fixed-count lists, a clipping fn for this use); do not make :seon.print/hiccup panel output the LLM view (it is span markup of a value); do not use ops-hiccup; do not add a second sizer (estimate :17) or second continuation builder (emit/continuation); do not store cached rendered sizes keyed by time; do not widen :render's map-only guard by copying it - generalise it.
