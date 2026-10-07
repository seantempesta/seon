---
title: S2b-1 — the reader rules in the shared stepper (design v2)
type: design
status: gate 1, design only (no src/test edits); v2 after the dual review and root's rulings
date: 2026-10-07
---

# S2b-1: the reader rules of spec §4.3 in `seon.sci.forgive/next-form` (design v2)

Lane `s2b1-reader`. The spec is `docs/seon/specs/agent-loop.md` §4.3; this commit amends it in place per root's
rulings (failed collections, regions, no-value constructs, fence matching, Q1, deferred imitation report, slicing
and order).

- Reviews: `review-s2b1-opus-notes.md` (e80131ef1) and `review-s2b1-codex-notes.md` (c62786efd). §12 gives the
  disposition of each finding.
- Line numbers are at HEAD `25bb94be0`, after s5a-reply landed (`7b5535cf6`).
- Pins: edamame `196ea5e4`, sci `ce0aa53b`.

**What exists and the approach.**
- A model reply is read twice:
  1. `seon.turn/planned-sources` → `seon.sci.forgive/segments` (`:reply`), a structural pass that applies repairs
     (prose → `;` edits, prompt markers stripped, Parinfer);
  2. `seon.turn/read-row` reads each row again live (`next-form :code`).
- MCP SCI uses the same two passes in `:code`; MCP JVM uses the host reader and stops at its first read error.
- The design keeps both passes and puts every §4.3 rule into the ONE stepper they share (`step`, called by
  `next-form`):
  - the reply is read region by region (the text between fence lines);
  - each region gets one reader that ends at the region's end;
  - outside fences, a line's first character decides;
  - a failed read is its own step, and reading continues after it, in every mode;
  - nothing is repaired, so the repairs machinery goes entirely.
- The reserved `#:seon.repl` shape becomes an ordinary read error.
- S6 inherits the stepper unchanged through `seon.turn.batch/turn-request` and deletes `segments` and the row re-read.

**Which step of the one path this serves.** READ (text in → forms). It adds no owner, path or mechanism. It deletes:
- Parinfer (the `parinferish` dependency's only caller);
- the structural trials, the code-suffix search, the marker repair and the thrown-error prose detection;
- the repair edits and the `:seon.batch/repairs` family;
- the `:seon.batch/refused` carrier.

## 1. What exists (file:line at `25bb94be0`)

| Piece | Where | Today |
|---|---|---|
| `segments` | `src/seon/sci/forgive.clj:443-480` | `forms` over `structural-ctx`, `comment-of` per form; recursion on `(subs repaired end)` after a balanced unreadable form; an unbalanced rest is `:seon.batch/unread` |
| `forms` / `next-form` | `:398-423` / `:282-358` | `:reply` repairs: marker regex `:261`, Parinfer (`structural-failure?` `:177`, `form-lines-end` `:219`, `delimiter-repair` `:240`), shape tests (`structure?` `:263`, `structured?` `:276`), `code-suffix` `:197` (bare edamame `structural-forms` `:122`), prose found by a thrown read error (`prose-read-failure` `:135`); the reserved-shape refusal in every mode `:333-337`; edits applied once (`repair-edits` `:367`, `apply-edits` `:382`) |
| failed-span scan | `collection-open` `:137`, `string-end` `:149`, `balanced-end` `:159` | used by `segments` after an unread rest, and by the Parinfer gate |
| reply callers | `seon.turn/segmented-sources` `turn.clj:2982` ← `planned-sources` `:3009` ← `freeze!` `:4442`, generated opening blocks `:1770`, `preview-sources` `:4995`/`:5019` (← `seon.render.web/render-source-call` `web.clj:1398`), `seon.cluster.agent/segmenter` default | |
| code callers | `code-sources` `turn.clj:3025` (MCP SCI); `read-row` `:4659-4703` (live re-read, `:code`; `carried` `:4673`); `seon.sci.batch/read-next` `sci/batch.clj:23` (`code-request`, `turn-request`); `read-code` `:29` (no caller) | |
| JVM read | `seon.turn.batch/jvm-read-next` `turn/batch.clj:869-886` | host `read+string` after `forgive/skip-to`; a failure is `:seon.batch/unread-from`, which ends the batch |
| refusal carrier | `seon.batch/evaluate-step` `batch.clj:411`, `:415`, `:427-428` | `(ex-info "Only the REPL writes responses: this map was not evaluated." {:seon.batch/refused refused})`, no `:seon.batch/error-value` |
| nil fault | `seon.turn/show-row` `turn.clj:4741` | `(sci.eval/shown-failure (or (:seon.batch/error-value (ex-data error)) value) …)` gets nil |
| duplicate check | `seon.sci.reader/read-events` `reader.cljc:491`, `:570-579` | builds `::response-keys` by hand (no `:seon.error/type`); `reader-error` `:585` is private, and `read-row` reaches it through `#'reader/reader-error` |

**Reproduced now, at HEAD on default.**
- MCP SCI, agent `s2b1-reader-v2`, `(+ 1 2)` / `[1 bad: (+ 2 3)]` / `#:seon.repl{:value 3}` / `(* 2 3)`:
  - form 2 answers its read error as a value; continuation works in the MCP code path today through
    `balanced-end`;
  - form 3 answers the bare string `"Only the REPL writes responses: this map was not evaluated."`;
  - form 4 (`6`) carries the `shown-failure` "expected a map, got nil … seon.turn/show-row (turn.clj:4741)" error.
  - This is task `56e06f9e410a` (the delivery on the next form) and task `fe8f23d6095c`. `7f5dfb6ad7a2` is not in
    the database; the review (F5) was right.
- MCP JVM, `(+ 1 1)` / `(vector ::nope.alias/k)` / `(+ 2 2)` answers only `2` (task `e333444a896c`).

## 2. Root's rulings, applied

| # | Ruling | Where in this design |
|---|---|---|
| 1 | Imitation report deferred to S4/S6 | No `:imitation` kind, regex, variant, `:seon.batch/imitation` or turn hunk. Imitation lines are prose comments. A prose-only reply keeps the no-form refusal (unchanged path) |
| 2 | `preview-sources` and generated opening blocks read with `code-sources` | §4.5 |
| 3 | Continuation in the one stepper, every mode; a failed collection resumes after its closer; JVM the same; spec amended | §4.2, §4.4; spec §4.3 "Read errors" |
| 4 | `#_` and no-value constructs stay in their region; classification resumes after them; a fence closes only with its match; a non-code fence is all prose | §4.1 (regions, `enter`, `region-end`), §4.2 (`read-at`); spec §4.3 |
| 5 | Armed allocation under 5 MB; one reader per text, not per prose line; no reserved-shape error in the segmenting pass | §9: rr ×3 4.94 MB armed. One reader per region (§4.1); error values only in `next-form`, never in `segments` |
| 6 | The reserved shape is the existing `:seon.batch/read-error` step; delete `:seon.batch/refused` | §4.3 |
| 7 | The nil show-row regression drives MCP SCI | §10 |
| 8 | S2b-1 lands before S1; S1 writes the fence rules into `(help)` | spec §4.3 slicing and §5 row |
| 9 | Q1: `#` + a non-whitespace character other than `#` starts a form; `#1 priority` becomes a visible read error | `form-start?`; spec §4.3; §8 |

## 3. Library seams

- **Reading stays SCI `parse-next`**: the structural `structural-ctx` in `segments`, and the agent's context in
  `read-row`. `forgive.clj` keeps no bare edamame call, so the `edamame.core` and `parinferish.core` requires go.
- **Failure spans come from edamame's own data.** `parse-to-delimiter` (edamame `impl/parser.cljc:275-315`) adds
  each enclosing collection to `:edamame/containers` (row, column, opened delimiter) as the failure unwinds. SCI keeps
  that data on its wrapper.
  - Probed in a throwaway JVM: `[1 bad: (+ 2 3)]` gives containers `["["]`; `(defn f [x] (let [y #1] …))` gives
    `["[" "(" "("]`.
  - The OUTERMOST container (the last) gives the failed form's opener exactly, with no prefix guessing.
  - One scan from that opener to its matching closer gives the span.
  - The reader's own stop position is not enough for collections. For `#1` the reader stopped past the `]` it had
    consumed, so counting closers from there overshoots into the next form; that was observed with the first
    prototype.
  - For failures with no open collection (a stray closer, which edamame consumes before throwing at `:768-792`; a
    token; an odd map, thrown after its `}` was read) the reader's stop position is the span end.
- **`#_` is handled by edamame inside `parse-next`.** Its `continue` recursion (`:568-578`, `:836-837`) would read
  the next value from wherever it is, so the stepper reads `#_`'s operand itself. `:uneval` also reads the next
  value (Codex P1), so it is no seam.
- A reader conditional is read with `:read-cond :preserve` in the structural pass. Whether it selects a value is
  plain data: `(:form rc)` against the live features.
- `edamame.core/source-reader` accepts a `java.io.Reader`, so a region's reader is
  `(java.io.StringReader. (subs text start end))`. The region copies are disjoint, so the copying is linear.

## 4. The smallest composition

### 4.1 Regions and the line rules (`reply-step`)

The version carries its region: `:seon.batch/bound` (the region's end), plus `:seon.batch/fence` (`:code`/`:prose`)
and `:seon.batch/ticks` inside a fence. Entering a region makes its reader once; inside the region the reader is
advanced, never rebuilt. The prototype (scratchpad `s2b1-reader/src/s2b1/forgive2.clj`, run only in throwaway
JVMs) reads:

```clojure
(defn- enter
  [text pos fence ticks]
  (let [bound (region-end text pos ticks)]
    (cond-> (assoc (version text pos bound) :seon.batch/bound bound)
      fence (assoc :seon.batch/fence fence :seon.batch/ticks ticks))))

(defn- reply-step
  [ctx opts v last-code-end]
  (let [^String text (:seon.batch/text v) n (count text)]
    (loop [v (if (:seon.batch/bound v) v (enter text (:seon.batch/pos v) nil nil))
           p (:seon.batch/pos v) code-end last-code-end]
      (let [{fence :seon.batch/fence bound :seon.batch/bound} v
            s (min (skip-blank text p) bound)]
        (cond
          (>= s n) {:seon.batch/eof true :seon.batch/version v}
          ;; a region ends at a fence line: close the open fence, or open one by its tag
          (>= s bound)
          (let [le (line-end text bound) [t tag] (fence-line text bound le) start (min n (inc le))]
            (recur (if fence (enter text start nil nil)
                       (enter text start (if (contains? code-tags tag) :code :prose) t))
                   start code-end))
          (= :prose fence) (recur v bound code-end)
          (= :code fence)
          (if (= \; (.charAt text (int s)))
            (recur v (inc (line-end text s)) code-end)
            (let [r (read-at ctx opts v s)]
              (if (find r ::skip) (recur (:seon.batch/version r) (or (::skip r) bound) code-end) r)))
          :else
          (let [ls (line-start text s)]
            (cond
              (and code-end (<= ls code-end)) (recur v (inc (line-end text s)) code-end) ; rest of a form's line
              (and (= s ls) (form-start? text s))
              (let [r (read-at ctx opts v s)]
                (if (find r ::skip) (recur (:seon.batch/version r) (or (::skip r) bound) (or (::skip r) bound)) r))
              :else (recur v (inc (line-end text s)) code-end))))))))
```

- `region-end` scans lines from a line start with `.indexOf`. It calls `fence-line` only on a line whose first
  non-blank characters are backticks.
  - Outside a fence the region ends at the next fence line.
  - Inside a fence of `ticks` backticks it ends at the first untagged line of at least `ticks` backticks.
- `form-start?` is `(`, `[`, `{`, `'`, `@`, `^`, or `#` followed by a non-whitespace character other than `#`.
- `code-tags` is `#{"" "clojure" "clj" "cljc" "edn"}`.

### 4.2 One read and its span (`read-one`, `read-at`)

(§9.1 amends this: the read stage is a value, structural token and stray-closer spans take no read, and one
non-logging reader serves each region.)

- `read-one` keeps today's end computation (line and column deltas from the read's start) and adds the failure
  span:
  - **opener present:** the end is `(or (closer-end text opener) (count text))`;
  - **no opener:** the end is where the reader stopped.
- `read-at` clamps every end to the region's bound, so an unclosed collection is unfinished at its region's end.
- `closer-end` is today's `balanced-end` loop started at a known opener, so `collection-open` goes.

```clojure
(defn- read-at
  [ctx opts v s]
  (let [^String text (:seon.batch/text v)
        discard? (and (< (inc s) (count text)) (= \# (.charAt text (int s))) (= \_ (.charAt text (int (inc s)))))
        r (read-one ctx opts (advance v (if discard? (+ s 2) s)))]
    (cond
      (:eof r) {::skip nil :seon.batch/version v}
      (:error r)
      (let [end (min (max (:end r) (:stop r) (inc s)) (:seon.batch/bound v (count text)))]
        {:seon.batch/read-error (:error r) :seon.batch/start s :seon.batch/end end
         :seon.batch/version (advance (:v r) end)})
      (or discard? (no-value? (:form r) (:features opts #{:clj}))) {::skip (:end r) :seon.batch/version (:v r)}
      :else {:seon.batch/form (:form r) :seon.batch/start s :seon.batch/end (:end r) :seon.batch/version (:v r)})))
```

- **A no-value construct** is `#_` with its operand, or a preserved reader conditional selecting none of the
  features. It is skipped: in a code fence reading continues after it; outside fences its line's rest is prose.
  - Probed: `#_ (+ 1 2)\nDone.\n(+ 3 4)` runs only `(+ 3 4)` (task `c98bb97a06a3`'s input).
  - Probed: `` ```clj\n#_ (+ 1 2)\n```\nDone.\n(+ 3 4) `` runs only `(+ 3 4)`.
  - Probed: `#?(:cljs 1)\nDone.\n(+ 3 4)` runs only `(+ 3 4)`.
- **`code-step`** (MCP SCI, `code-request`): today's `skip-to` (fence lines, `~~~` included, and `;` comments
  skipped) plus `read-at` over one whole-text region. A failed read continues there too (ruling 3).

### 4.3 The live read builds the error value; `segments` never does

`next-form` = `step` plus the error value, built only in the live paths (`read-row`, `sci.batch/read-next`):

```clojure
(defn next-form
  [ctx opts mode state]
  (let [r (step ctx opts mode state)
        source #(subs (:seon.batch/text (:seon.batch/version r)) (:seon.batch/start r) (:seon.batch/end r))
        carried (fn [value] (ex-info (:seon.error/message value) {:seon.batch/error-value value}))]
    (cond
      (:seon.batch/read-error r) (update r :seon.batch/read-error #(carried (reader/reader-error (source) %)))
      (fabricated? (:seon.batch/form r))
      (-> r (dissoc :seon.batch/form) (assoc :seon.batch/read-error (carried (reader/response-error (source) (:seon.batch/form r)))))
      :else r)))
```

- `seon.sci.reader/reader-error` becomes public; `read-row` already calls it through a private Var.
- New `seon.sci.reader/response-error`, `:clj`, builds the existing `:seon.sci.reader/fabricated-response-error`
  through `seon.error.refusal/error`.
  - Message: "Only the REPL writes responses: this form was not evaluated; send forms and wait for their results."
  - `read-events` (`reader.cljc:570-579`) calls it too, so the duplicate check goes (task `83b10f8c64d2`).
  - `seon.error.refusal` requires only `seon.id`, so there is no cycle.
- `batch.clj:411`, `:415`, `:427-428`: the `refused` binding and branch are deleted. The existing `read-error`
  branch (`:429`) carries the value, and `show-row`/`publish-row` read `:seon.batch/error-value`, so the nil fault
  is gone at its producer.
- `read-row` (`turn.clj:4659`) passes a `:seon.batch/read-error` step through as it passes a form. Its `cause`
  branch goes, and it keeps its own `carried`, bound and empty-row refusals.

### 4.4 JVM mode: the same rule

When the host `read+string` fails at `pos`, `jvm-read-next` asks the stepper where that construct ends:
- new public `(forgive/construct-end text pos)` → the `:seon.batch/end` of `(step @structural-ctx opts :code …)` at
  `pos`;
- it answers a `:seon.batch/read-error` step whose carrier holds `seon.sci.reader/reader-error` of that span, with
  the version at the end.

The fold then continues (`batch.clj:601-606` ends only on eof or unread), so the reply answers 2, the read error, 4
(task `e333444a896c`). Probed structurally: `(vector ::nope.alias/k)` reads whole with the structural any-alias
resolver, so its span ends at its `)`.

### 4.5 Callers and deletions

- `turn.clj:1770` (generated opening blocks) and `:5019` (`preview-sources`) call `code-sources` (ruling 2).
- `segmented-sources` loses its `:seon.batch/unread` branch: the stepper never answers an unread rest. Its
  `max-source` path becomes one segment whose source is the whole text, which `read-row` refuses with the reader's
  bound as today.
- **Deleted from `forgive.clj`:**
  - `structural-options`, `suffix-options`, `structural-forms`, `prose-read-failure`, `collection-open`,
    `balanced-end`, `structural-failure?`, `code-suffix`, `prose-repair`, `form-lines-end`, `delimiter-repair`,
    `prompt`, `structure?`, `code-line?`, `structured?`, `repair-edits`, `apply-edits`;
  - the edamame and parinferish requires.
- **Deleted elsewhere:** `seon.sci.batch/read-code` (no caller); `parinferish` from `deps.edn` (root's
  dependency path).
- **Deleted from `seon.batch.edn`:** the repairs family is dead once nothing repairs. Scripted sweep:
  - keys `:repair-kind`, `:prose`, `:at`, `:through`, `:repair`, `:repairs`, `:structured?`, `:refused`;
  - `:repairs` members in `:read-state`, `:read-step` and the two reply maps (`:221`, `:272`);
  - consumers: `turn.clj:4657`, `:4668`, `:4680`, `:4693`; `batch.clj:238`, `:740`; `turn/batch.clj:815`, `:881-885`,
    `:925`, `:947-952`; `test/seon/run_test.clj:24-25`, `:37`, `:81-82`, `:93`, `:208-209`.
- **Added to `seon.batch.edn`:** `:text-version` gains optional `:seon.batch/bound` (nat-int?),
  `:seon.batch/fence` `[:enum :code :prose]` and `:seon.batch/ticks` (pos-int?). The `:read-error` step variant is
  unchanged.
- **Added functions** (each with a one-line contract): `version`'s three-argument arity, `line-end`, `line-start`,
  `fence-line`, `region-end`, `form-start?`, `skip-blank`, `closer-end`, `outermost-opener`, `no-value?`,
  `read-at`, `enter`, `reply-step`, `code-step`, `step`, `construct-end`.
- **`comment-of`** derives the comment from the text between two forms: prose lines → `; line`, the agent's `;`
  lines kept, blank and fence lines dropped. So no repair edits are needed and the text read is the text sent.
- **Size:** prototype v3 `forgive3.clj` is 361 lines, about 380 with `next-form` and `construct-end`, against 480 today (about −100). The `seon.batch.edn` and consumer
  sweep is net negative.

### 4.6 Why no owner or library already does it

- `next-form` is the stepper every reader entrant already shares.
- Edamame supplies the failure containers, SCI the read, and Java the bounded reader.
- No CommonMark library is needed for a first-character rule plus fence matching (both reviews agree).

## 5. Alternatives

- **A. Rules in a separate line scanner inside `segments`** (v1 prototype `proto.clj`): rejected, because it creates
  two rule sets and S6 would have to port it.
- **B. One reader per text, with a crossing check after each read** (v1 and v2 first form): rejected. 100 fences
  each holding an unclosed form re-read to the text's end each time: 569 ms, 618 MB armed. Readers bounded by
  region make it 7.21 ms, 6.95 MB with no crossing check.
- **C. Span from the reader's stop plus open-container count:** rejected, because it overshoots (`#1` consumed a
  `]`). The outermost opener plus one closer scan is exact on every probe.
- **D. Keep the repairs vector, always empty:** rejected. Every armed call would validate it, and a key with no
  producer has no consumer.

## 6. The stopped patch (`stopped-wip/reader-prose.patch`)

Unchanged verdict from v1:
- Its `fenced` rewrite, prompt-line refusal in every mode, Parinfer and `:fenced` mode are all rejected. On
  loop-pass call 2 it refuses 5 of 6 forms.
- Taken from it: reading the fence info string (first word, lowercased).

## 7. Open questions for root

None blocking. Two consequences to confirm on reading:
1. **An unclosed form inside a fence is unfinished at its closing fence** (the regions rule), where §4.3 previously
   said EOF. The spec text is amended to match. No real reply changes, because the three truncated defns have no
   closing fence.
2. **A mismatched closer** (`(foo]`) spans to the first closer that balances the opener, so `(foo]` is one error and
   the next form runs (probed). A Clojure REPL would keep waiting for `)`.

## 8. Every real DeepSeek reply, before → after (prototype v2, throwaway JVM)

| Reply | chars | before | after | What changes for the agent |
|---|---|---|---|---|
| live 01 | 2,042 | 24 | 24 | none |
| live 02 | 0 | 0 | 0 | none (empty reply; turn path) |
| live 03 | 424 | 2 | 2 | none |
| live 04 | 120 | 0 + unread | 1 | the truncated `(defn- reply-map "…` is one unfinished source: "EOF while reading" (was the unread rest, same message) |
| live 05 | 1,420 | 3 | 3 | none |
| live 06 | 1,518 | 3 | 1 | `` `reply-map` `` and `` ` joins`` (line-start backticks) no longer run as syntax-quote |
| live 07 | 1,531 | 1 | 1 | none |
| live 08 | 1,352 | 2 | 2 | none |
| live 09 | 2,889 | 3 + unread | 4 | the truncated defn is the 4th source, unfinished |
| live 10 | 448 | 1 + unread | 2 | same |
| live 11 | 247 | 0 + unread `## What I read …` | 0 | heading and prose are prose: the turn's no-form refusal instead of "Invalid token: ##What" |
| loop 1 | 729 | 4 | 7 | 3 `scratch.add2=>` markers in fences run as symbols → "Unable to resolve symbol: scratch.add2=>"; `#:seon.repl{…}` is its own read error, with no fault on the next form |
| loop 2 | 959 | 6 | 11 | 5 marker symbols, each an error; the forms after `(in-ns 'scratch.add2)` run there as before |
| run4 e2e89497c3c2 | 558 | 1 | 1 | none |
| run4 d3597309628b | 279 | 0 + unread `)\n```` | 1 | the lone `)` is its own read error, "Unmatched delimiter: )" |
| run4 94f7a885645b, b329b5a17aa4, cbfbcf937f35 | 247-740 | 1 each | 1 each | none |
| run4 7b254915ca94 | 12,176 | 48 | 43 | an imitated transcript: unfenced `my.agents.juniper=> (form)` lines are prose, so 2 queries and 1 deftest no longer run; bare `nil`, `"diagnostic"` and `)` lines are prose; the last lone `(` is unfinished (Parinfer made `()` before); 24 column-0 `#:seon.repl{…}` lines are each a read error |
| run4 f3b3fda9b944 | 6,727 | 3 | 3 | none |
| fence 94f7a885645b, e6628e29a42b, 59cf96d21042 | 90-955 | 1 each | 1 each | none |
| help trial (`loop_proof_test`) | — | 2 | 1 | unfenced `my.agents.juniper=> (seon.db/q …)` is prose, so only the reserved map remains (a read error) |

**Probed rule table** (each row is a proposed table row, §10):

| Input | Sources after |
|---|---|
| `[1 bad: (+ 2 3)]` in a fence | the whole vector, one error; `(+ 2 3)` never runs |
| `(defn f [x] (let [y #1] (inc y)))` then `(+ 1 2)` in a fence | 2 |
| `(+ 1 2))` then `(+ 3 4)` in a fence | `(+ 1 2)`, `)`, `(+ 3 4)` |
| `(foo]` then `(+ 1 2)` | 2 |
| `(str "abc` then a closing fence, then `(+ 1 2)` | unfinished at the fence, then `(+ 1 2)` runs |
| ```` ```text ```` / ```` ```clj ```` (inside a prose fence) | prose |
| ```` ````clojure ```` with an inner ```` ``` ```` | the inner line is code (no match) |
| `#inst "…"`, `#uuid`, `#'x`, `#:a{}` at column 0 | forms |
| `#1 priority is (launch!)` | `#1 priority` is a read error; the rest of its line is prose |
| `# Heading`, `## h2` | prose |
| `#_` alone before a closing fence | no source; the fence closes |

## 9. Cost

**Method.** Throwaway JVM on the checkout classpath.
- Armed = `seon.instrument/apply!` over every contracted Var of both namespaces (51 Vars), with the packaged
  projection; counts come from a `seon.batch/sink`.
- Median of 5 runs after 3 warm runs.
- `rr` = live 01, 05-08 and loop 1-2 joined.
- The prototype is v3 (scratchpad `s2b1-reader/src/s2b1/forgive3.clj`, 361 lines), with §9.1's changes.

| Input | chars | installed `:reply` armed | new `:reply` armed | new unarmed |
|---|---|---|---|---|
| rr ×1 | 9,557 | 15.2 ms, 5.10 MB, 2,119 calls | 1.6 ms, 1.10 MB, 1,132 calls | — |
| rr ×3 | 28,671 | 14.1 ms, 15.15 MB, 6,307 calls | **4.5 ms, 3.45 MB, 3,606 calls** | 2.0 ms, 2.01 MB |
| rr ×8 | 76,456 | 39.3 ms, 40.01 MB, 16,777 calls | 5.8 ms, 9.09 MB, 9,791 calls | — |
| 400 prose/form pairs | 8,400 | 41.1 ms, 29.16 MB, 12,410 calls | 2.8 ms, 4.15 MB, 6,406 calls | — |
| 1,000 prose lines | 56,000 | 7.7 ms, 6.21 MB (reads ~65 lines, then stops¹) | 1.0 ms, 1.32 MB, 4,007 calls | 0.2 ms, 0.22 MB |
| 100 fences each holding an unclosed form | 3,300 | 14.0 ms, 9.83 MB | 6.4 ms, 4.22 MB | — |
| 1,000 stray `)` in a fence | 9,011 | 51.2 ms, 66.52 MB (drops them silently) | 8.1 ms, 10.72 MB, 2,000 sources | 1.5 ms, 4.33 MB |
| run4 7b254915ca94 | 12,176 | 4.5 ms, 3.56 MB | 1.3 ms, 1.05 MB | — |

`:code` (MCP), armed:

| Input | installed | new (v2) | new (v3) | v3 unarmed |
|---|---|---|---|---|
| rr ×3 | 2.75 ms, 2.95 MB (stops at the first prose word) | 3.50 ms, 4.41 MB | 3.56 ms, 1.42 MB, 234 sources | 2.2 ms, 0.83 MB |
| 1,000 prose lines | 0.38 ms, 0.42 MB (stops) | 163 ms, 188 MB | **27.2 ms, 43.8 MB**, 10,000 sources, 83,000 calls | **6.2 ms, 12.7 MB** |
| 1,000 stray `)` in a fence | 0.23 ms, 0.18 MB (stops) | — | 6.9 ms, 10.5 MB | 1.6 ms, 4.3 MB |

¹ The installed guard stops after 64 repairs. The new number covers the whole input, so it is not like-for-like.

- **Budget:** the realistic reply mix is now 3.45 MB armed at 28.7 KB (was 4.94 MB in v2), and linear.
- **Not measured:**
  - the armed `next-form` live read;
  - the JVM path (only the structural span is probed);
  - the S0 probe.
- **Store:** the read writes nothing.

### 9.1 What a failed read cost, and the fix (root's follow-up on the 188 MB row)

**Measured in throwaway JVMs, per read, by stack depth** (the MCP and evaluation paths run about 190-200 frames
deep):

| One read | depth 10 | depth 200 |
|---|---|---|
| `(ex-info "x" {})` alone | 6.0 KB | **62.2 KB** |
| SCI `parse-next`, failing token `Note:` (edamame's ex-info plus SCI's rewrap) | 21.5 KB | **134.9 KB** |
| edamame `parse-next`, same failure (one ex-info) | 9.2 KB | 65.7 KB |
| SCI `parse-next`, successful symbol `here` | 5.4 KB | 5.3 KB |
| edamame `parse-next` with options normalized once, same symbol | 0.9 KB | 0.9 KB |
| `sci/source-reader` construction | 1.5 KB | 1.5 KB |

**Causes, in order:**
1. **Every failed read throws twice.** edamame's `throw-reader` (`impl/parser.cljc:44-60`) throws an ex-info, and
   SCI rewraps it (sci `impl/parser.cljc:180-189`). An ex-info's stack-trace capture costs about 0.3 KB per frame on
   this JDK, so a failure at depth 200 is 135 KB. The prose line has 3 failing tokens (`Note:`, `left:`, `6.`-like),
   about 400 MB at depth 200 for 1,000 lines.
2. **Every successful read assembles SCI's options per call** (sci `impl/parser.cljc:150-169`: `assoc` of six keys,
   three closures, `merge`): 4.5 KB per form on top of edamame's 0.9 KB.
3. **The source-logging reader** (`sci/source-reader`) appends every character read to a buffer, so advancing over a
   span allocates; the structural pass needs no source log.
4. **Location metadata** (`:location?`, `:end-location`) is attached to every form the structural pass reads and
   then drops.

None of these is a stack-trace or message problem in Seon's own error value: the structural pass builds no error
value (ruling 5), so the cost is the reads themselves.

**Fix: a failed read is no more expensive than a successful one.** These are changes to §4.2 and §4.3, measured as
v3.

- **The read stage is a value.** The stepper calls `(:seon.batch/read opts)`.
  - The live read (`next-form` from `read-row` and `sci.batch/read-next`) passes SCI `parse-next` with the agent's
    live options, as today.
  - `segments`' structural pass passes edamame `parse-next` with options normalized once in a `def`: any alias, tags
    unread, `:read-cond :preserve`, no location metadata.
  - This follows the law that an entrant supplies its read stage as a value (AGENTS.md "There is one REPL path"). The
    structural pass is not the live read and is deleted in S6. Spec §4.3's "Reading" line is amended to say so.
- **Spans that need no read take none.** In the structural pass, a bare token's span is the reader's own token rule:
  to the next whitespace, comma or terminating character (`" ; @ ^ ` ~ ( ) [ ] { } \`).
  - A stray closer at top level is one character.
  - No read is done, so nothing is thrown. The live read of that row reports its error once, as before.
  - Collections, strings, characters and dispatch or quote forms are still read, so their spans and failure
    containers stay exact.
- **One reader per region, never rebuilt, never logging.** Built with `sci/reader` (edamame's indexing reader) over
  a `java.io.CharArrayReader` window `[start, bound)` of the text's one character array, with no copy per region.
  It is advanced past tokens and gaps instead of being rebuilt.
- **`skip-to` is one primitive scan** (whitespace, commas, `;` comments, fence lines), with no line strings.
- **`comment-of` runs only when** the text between two forms holds something other than whitespace and commas.

**Results** (v3 against v2, the same corpus and §8 cases, both modes):
- Sources are identical except:
  - `:code` reads `1:` as one token where v2 read `1` then `:` (the reader's token rule);
  - v2's spurious `; ,` comments in `:code` are gone (v2 called a lone comma prose).
- 56 KB of prose through `:code`:
  - **unarmed:** 319 MB → **12.7 MB** at depth 200 (≈1.3 KB per source, the same as a successful token; linear);
  - **armed:** 188 MB → **43.8 MB**.
- 1,000 stray closers: 141 MB → 4.3 MB unarmed, 10.5 MB armed.

**Not met: under 10 MB for 56 KB, armed.** 56 KB of one-word lines is 10,000 sources.
- The segment map and source string alone are about 0.5 KB each.
- Each source makes about 8 armed calls (`step`, `code-step`, `skip-to`, `read-at`, `token-end`, `at`, `advance`,
  and `skip-blank` in `segments`) at about 0.4 KB each.

Options, priced:
- **(a)** Fold the per-token helpers into `read-at`'s loop (no contracted helper per token): about 3 calls per source,
  an estimated 25-30 MB armed. Fewer named functions.
- **(b)** Task `seon.instrument/hot-internals-pay-armed-validation` (`ee57516cbaef`): about 13 MB armed with no code
  change here.
- **(c) Recommended: accept 12.7 MB unarmed, 43.8 MB armed** for this adversarial row, because the read is not what
  makes it expensive. The same 56 KB through MCP today already evaluates every valid word as a form, and a pass costs
  5-11 MB and 3-4 ms per form. Measured on default with `seon.pass-probe/mcp!`, agent `s2b1-reader-v3`:
  - 100 unresolved words: 417 ms, 633 MB;
  - 400 unresolved words: 1,481 ms, 2.37 GB;
  - 100 `(+ n 1)`: 469 ms, 1.15 GB.
  - So 10,000 forms is tens of GB whatever the reader does. Filed as task `seon.turn/each-form-of-a-pass-costs-megabytes`
    (`38a047eb9495`; `seon.db/with-declarations` 863 calls / 514 ms and `seon.db/q` about 3 per form). That is
    where the multi-hundred-MB rule bites for pasted prose, and it belongs to S5/S6's single settlement.

## 10. Tests

| Behavior | Test |
|---|---|
| recorded replies | `seon.sci.forgive-test/recorded-replies-read-into-the-same-segments`: regenerate `forgive_replies.edn`, each changed row checked against §8 |
| the rules | `seon.sci.forgive-test/a-delimiter-repair-is-judged-on-the-forms-own-lines` → DELETED (subject deleted). Replaced by one table, `the-reader-reads-a-reply-by-its-line-rules`: the §8 probe rows plus Q1 (`#inst`, `#uuid`, `#1`, `# `, `##`), same line vs fence, discard and conditional (task `c98bb97a06a3`), fence matching, an odd map and a token in a nested collection. It asserts the sources, as data |
| linear, armed | `seon.sci.forgive-test/a-reply-reads-in-allocation-linear-in-its-length` (kept) |
| nil fault, at the entrant that faulted | NEW row-table test in `seon.cluster.evaluate-source-test`: an MCP SCI run of `(+ 1 2)` / `#:seon.repl{:value 3}` / `(* 2 3)` answers 3; then the `:seon.error/type` `:seon.sci.reader/fabricated-response-error` on form 2; then 6 with no `:errors`. Names `fe8f23d6095c` and `56e06f9e410a` |
| continuation in both modes | same test namespace, table over `:mode` `sci` and `jvm`: `(+ 1 1)` / `(vector ::nope.alias/k)` / `(+ 2 2)` answers 2, a read error, 4. Names `e333444a896c` |
| refused map settles | `seon.turn-test/a-refused-response-map-settles-as-an-unrun-evaluation` (`turn_test.clj:245-277`): converts its message substring to the error type |
| help trial | `seon.loop-proof-test` testing block at `:808`: converts (the query is prose; the map is a read error) |
| `#inst`, trailing `Done.` | `seon.turn-test/a-reply-keeps-its-dispatch-macro-forms-as-code`: `#inst` stays a form under Q1, and `["(+ 1 2)" "Done."]` becomes `["(+ 1 2)"]` |
| same line | `seon.repl-parity-test` C7: input moved into a fence |
| run4 | `seon.ai-test/captured-run4-replies-stop-before-fabricated-responses` |
| repair test of deleted machinery | `seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent` → DELETED |
| reply keys | `seon.run-test` (`run_test.clj:93`) asserts the reply's keys: `:seon.batch/repairs` leaves the set |
| traced by code only, expected unaffected: UNVERIFIED until run | `seon.cluster.evaluate-sources-test`, `seon.cluster.host-test`, `seon.render.transcript-test`, `seon.run4-install-test`, `seon.cluster.prompt-test`, `seon.render-simplification-test` (preview callers) |

Schema resources change, so the slice is proven by publication and adoption on a branch of the live store, then the
armed probe of §9 repeated on the installed landing.

## 11. Claims needed

- **Source:** `src/seon/sci/forgive.clj`, `src/seon/sci/reader.cljc`, `src/seon/sci/batch.clj`, `src/seon/batch.clj`,
  `src/seon/turn.clj`, `src/seon/turn/batch.clj`, `resources/seon/schemas/seon.batch.edn`.
- **Tests:** `test/seon/sci/forgive_test.clj`, `test/seon/sci/forgive_replies.edn`,
  `test/seon/cluster/evaluate_source_test.clj`, `test/seon/turn_test.clj`, `test/seon/cluster/turn_test.clj`,
  `test/seon/repl_parity_test.clj`, `test/seon/loop_proof_test.clj`, `test/seon/run_test.clj`.
- **Root:** `deps.edn` (parinferish).
- **Collision check at claim time:** `src/seon/sci/eval.clj` and `contracts_compile_test.clj` are held by other
  lanes. This design edits neither.

## 12. Review findings, disposition

| Finding | Disposition |
|---|---|
| Opus F1, imitation report | removed (ruling 1) |
| Opus F2 / Codex P2, `preview-sources` caller | `code-sources` (ruling 2) |
| Opus F3, file claims | moot: s5a-reply landed; §11 |
| Opus F4, help | S1 owns it (ruling 8) |
| Opus F5 / Codex P2, regression entrant | MCP SCI test (ruling 7); `7f5dfb6ad7a2` replaced by `56e06f9e410a`, `fe8f23d6095c` |
| Opus F6 / Codex P1, continuation outside the stepper and the guessed closer | the stepper answers `:seon.batch/read-error` steps; the span comes from edamame's outermost container plus one scan; spec amended (ruling 3) |
| Opus F7, line numbers and non-like-for-like rows | re-located at `25bb94be0`; rows footnoted |
| Opus F8, Q1 wording | ruling 9 |
| Opus F9 / Codex P1, a reader per prose line and refusals built in segmenting | one reader per region; no error value in `segments` |
| Opus F10, smaller carrier | taken (ruling 6) |
| Codex P1, `#_` crosses prose and fences | regions plus no-value skip (ruling 4) |
| Codex P1, armed budget | 4.94 MB at 28.7 KB (§9) |
| Codex P2, fence reopening | a fence closes only with its match; a non-code fence is all prose |
| Codex P2, `seon.sci.reader/read` survives | correct: only its duplicate response check moves to `response-error`; link `83b10f8c64d2` |
| Codex P2, `skip-to`'s JVM caller | kept (code mode) |

## 13. Findings and tasks

- **Reproduced at HEAD:** the nil fault (`56e06f9e410a`, `fe8f23d6095c`) and the JVM drop (`e333444a896c`).
  Results `result/e031448f1175a` and `result/e1148fde1ed83`; agent `s2b1-reader-v2`. Its self-retirement
  answered "run transition refused: admission-closed" (existing task `bc5e022a68e6`), so root's
  `retire-branch!` retired `:agent-be6bc235ecd9`.
- **v1 incident**, kept for the record: a JVM-mode prototype looped in default's JVM, and root restarted it. Every
  probe since ran in throwaway JVMs.
- **New task `38a047eb9495`** (`seon.turn/each-form-of-a-pass-costs-megabytes`): see §9.1. Agent
  `s2b1-reader-v3` was retired by root's `retire-branch!` (`:agent-37c293d91581`).
- **Task `fd7455e6e6ed`** (selected error map prints "Error evidence is unavailable."): open, outside this slice.
- **`81773872798e`** (trailing prose dropped): still TARGET S4.
- **`c9f69d5aaa31`** (marker stripped): closed by this slice.
- **`56dc5f9cb734` and `61c1346b6ef2`**: their machinery is deleted.

## Design check

- **The v1 design was wrong in three places the reviews found:**
  - continuation lived in `forms` rather than in the stepper;
  - `#_` crossed lines and fences;
  - its budget claim was unarmed.
- **v2 fixes each at the owner:** the stepper answers failure steps for every entrant, regions bound every read,
  and the measurement is armed.
- **Surprises at the REPL:**
  - the reader's stop position is wrong for collection failures (edamame's container rows are exact);
  - a single reader per text made unclosed fenced forms quadratic (618 MB), which forced per-region readers;
  - deleting the repairs vector removed both armed validation work and about 20 consumer lines.
- **What remains awkward:**
  - the armed margin at 28.7 KB (4.94 MB);
  - the pathological rows that are worse in ms (stray closers) or explode in `:code` (MCP prose). They are reported
    rather than hidden.
