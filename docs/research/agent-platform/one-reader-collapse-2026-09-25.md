---
type: research
status: "design lane design-one-reader-opus; REPL evidence, no production edits; awaiting Astra review"
created: 2026-09-25
scope: collapse src/seon/sci/reader.cljc onto edamame, SCI and clj-kondo; classify the scattered readers
pins: "seon HEAD e3cdffe76; edamame reference-code 63373df (local commit on v1.6.42); sci 409888f6; clj-kondo 57252e07"
---

# One reader: what is a parser, what is ours (2026-09-25)

`seon.sci.reader/read` is not a second parser. Every form it returns comes from edamame, called through
`sci/parse-next+string`. What its 919 lines duplicate is the work around that call:
- symbol resolution for syntax-quote, which SCI already does, and which ours gets **wrong** (broken first, below);
- function and test declaration facts, which clj-kondo already produces;
- namespace attribution across the forms of one reply, which SCI's own namespace table already tracks when the forms run;
- two outputs that nothing consumes;
- a workaround for an edamame end-cursor bug, which belongs in our edamame checkout.

What the libraries do not have, and we keep, is **recovery**: an unreadable span becomes its own error event and reading
continues at the next top-level anchor. The reply splitter and the Parinfer repair depend on this. Neither edamame nor
clj-kondo produces it (§2). The composition below turns reading into one edamame/SCI call with the real SCI context,
followed by a data transformation into the declared event shape. Recovery stays ours, and so does the Markdown-fence rule,
which moves to the reply splitter.

## 0. Broken first

1. **Syntax-quote resolves core Vars and classes into the current namespace.** This is live in default today, through MCP
   SCI mode, which calls `seon.sci.eval/evaluate`, then `one-event`, then `reader/read`:
   `` `(inc String join) `` → `(user/inc user/String user/join)`. Clojure gives
   `(clojure.core/inc java.lang.String user/join)`. In a scratch SCI context, a macro read by our reader,
   ``(defmacro m [] `(inc 1))``, fails when called with `Unable to resolve symbol: probe.b/inc`. The cause is
   `syntax-quote-resolver` (`reader.cljc:84-105`). It qualifies every unaliased, unreferred symbol with the current
   namespace. SCI's own `fully-qualify` (`reference-code/sci/src/sci/impl/parser.cljc:84-142`) checks refers, interns,
   `clojure.core` minus excludes, special forms and classes. It is bypassed because `read-events` builds a fresh empty
   `(sci/init {})` for every read (`reader.cljc:563`) and overrides `:syntax-quote`. Any agent macro that syntax-quotes a
   core function is broken. No issue exists (`rg -il syntax-quote docs/seon/issues` finds none). This lane writes one
   file, so **root should file** `docs/seon/issues/syntax-quote-qualifies-core-vars-into-the-current-namespace.md`.
   Slice 2 fixes it.
2. The same SCI-mode probe of a quoted form took **592 ms and allocated 1.30 GB**. That falls under the existing issue
   `docs/seon/issues/a-trivial-sci-evaluation-allocates-half-a-gigabyte.md`. It is not caused by the reader: the reader
   costs about 26 µs (§3).
3. Default was `:faulted` with `program-unavailable`: adoption of commit `6ab6e90e` was not recorded, and 20 faults were
   open. The probes ran in the JVM against the loaded reader. Its behaviour matched HEAD's `reader.cljc`: the `1 2`
   number-at-EOF fix is present.

## 1. Method

- Default pid 59566, JVM mode, scratch namespace `one-reader-probe`, session `one-reader`. Only scratch Vars were
  defined (`rd`, `ed-read`, `kondo-an`, `stored-replies`). Default's Vars were never redefined. clj-kondo ran through
  `seon.fn.analyzer/analyze` with `::sources` under `tmp/one-reader-probe/`. One read-only SCI-mode probe was run.
- Inputs:
  - the 35 distinct stored `:seon.turn/reply` values in default;
  - `1`, `1 2`, `(+ 1 2))`, `(let [x 1]\n(f x)\n}`, `(+ 1`, `(a) (b\n(c)`, `1 (+ 1`, `{:a}`;
  - `#?(:clj 1 :cljs 2)`, `` `(a ~b) ``, `#"a+"`, `#inst "2020-01-01"`, `#foo/bar 1`, `#=(+ 1 2)`, `1.5M 2N`, `:k`, `"s"`;
  - `(str "héllo 😀") 3`, `{:seon.repl/value 1}`, comment-led input, fenced input and prose-led input.

## 2. Evidence

**E1. Our reader (installed, armed)** on the list above:
- `1` → `"1"` at 0–1; `1 2` → `"1"`, `"2"` at 1:3.
- `(+ 1 2))` → `[:form :error(:stray-closer)]`, which is the P1-C ruling.
- `(let [x 1]\n(f x)\n}` → `[:error :form :error]`. `(a) (b\n(c)` → `[:form "(a)"] [:error " (b\n" :unclosed] [:form "(c)"]`.
- `Here is code:\n(+ 1 2)\nand then prose.` → symbols, an `:invalid-token` span, `(+ 1 2)`, then symbols.
- A fenced reply reads the code only. `{:a}` → `:odd-map`. `#foo/bar 1` → refused tag. `#=` → refused read-eval.
- `{:seon.repl/value 1}` → the fabricated-response error. Multibyte offsets are UTF-16 and exact: `(str "héllo 😀")`
  spans 0–16.

**E2. Raw edamame** (`e/source-reader`, `e/parse-next+string`, `:end-location true`, and a `:postprocess` that records
each value's `:loc` and re-attaches it as metadata when `iobj?`):
- It gives every top-level value, numbers and keywords included, a start and end loc
  (`reference-code/edamame/src/edamame/impl/parser.cljc:873-910`).
- It resolves `#?`, regex, `#inst`, syntax-quote and `::k`. It refuses `#foo/bar` ("No reader function for tag") and
  `#=`, through our `:read-eval` function.
- Its ex-data carries `:line`/`:column`, `:edamame/opened-delimiter`, `:edamame/expected-delimiter`, `:edamame/token` and
  `:edamame/containers` (`parser.cljc:44-60, 214-220, 285-306, 487-488`).
- **It stops at the first error**: `(a) (b\n(c)` → `(a)`, then EOF. `(defn f …)\n\n(defn g [] 2)` with the first form
  unclosed → one EOF error and no `g`.
- **Fences read as syntax-quote**: ```` ```clojure ```` becomes `` `(quote clojure) ``.
- **The end column of a number that ends the text is one short**, exactly as in the landing note: `1` ends at column 1,
  and the last `2` in `1 2` ends at 3. The producer is edamame `read-number`, which calls `(r/unread rdr ch)` with
  `ch` nil at EOF (`parser.cljc:190-193`). tools.reader's `unread` then steps the column back even for nil.
- Over the 35 stored replies, 3 have reader errors: prose tokens such as `shows:`, where edamame stops and ours recovers.
  Of the 32 clean replies, 15 give identical forms and positions. The other 17 differ for two explained reasons:
  - fences: ours skips them, while edamame reads them as extra syntax-quoted forms;
  - positions: our `::line`/`::column` is the start of the span, including the comments above the form, while edamame
    reports the form's own position.
  There was no other difference.

**E3. SCI with the real context** (a scratch context with `(ns probe.a (:require [foo.bar :as fb] [clojure.string :refer [join]]))`,
`sci/parse-next+string ctx rdr {:features #{:clj} :end-location true :postprocess … :read-eval …}` inside
`sci/binding [sci/ns …]`):
- `` `(inc String join fb/x) `` → `(clojure.core/inc java.lang.String clojure.string/join foo.bar/x)`. This is correct.
- `::fb/k` → `:foo.bar/k`, and `#?(:clj :jvm …)` → `:jvm`. Every value, including `42`, carries its loc.
- SCI merges caller opts over its defaults (`sci/impl/parser.cljc:186-203`), so our tag and `#=` policy still wins.
- The eval context has no `:features` (`src/seon/sci/eval.clj:184,280` call `sci/init` without them), so the caller must
  pass `#{:clj}`. Without it, `#?(:clj …)` reads as EOF. That was verified.

**E4. clj-kondo on broken input** (`seon.fn.analyzer/analyze`, 5.8 ms per text, against the reader's ~26 µs):

| Input | kondo findings |
|---|---|
| `(+ 1 2))` | `:syntax` 1:8 "Unmatched bracket: unexpected )" |
| `(let [x 1]\n(f x)\n}` | `:syntax` 1:1 and 3:1 "Mismatched bracket" |
| `(+ 1` | `:syntax` 1:1 "Found an opening ( with no matching )", 1:5 |
| `(a) (b\n(c)` | `:syntax` 1:5 and 2:4; `(c)` is **nested inside** `(b` (its rewrite-clj fork auto-closes at EOF, `parser/clj_kondo/impl/rewrite_clj/parser/core.clj:109-121`) |
| `{:a}` | `:missing-map-value`, not syntax |
| `#foo/bar 1` | nothing |

kondo recovers, but it recovers into a different tree than SCI would read. It reports only a start row and column for
syntax findings, gives no span, does not refuse tags, and costs about 200 times as much as a read. **kondo is the
analyzer, not the reader's error source.** Syntax errors must come from the same reader SCI evaluates with, which is
edamame's ex-data. Recovery spans cannot come from kondo without changing which forms an agent's reply contains.

**E5. clojure.edn applied to Clojure source**:
- `(def x 'a)` reads as `(def x 'a)`, where `'a` is **one symbol named `'a`**. That is a silent misread that prints
  identically.
- `#(inc %)` fails with "No dispatch macro for: (". `@a` fails with "Invalid leading character: @".

## 3. Inventory of reader.cljc

"Library" names the dependency value that already exists. "Consumer" is src only; tests are in §6.

| Lines | Function | Produces | Consumers | Library equivalent | Fate |
|---|---|---|---|---|---|
| 12 | `eof` | sentinel | read-events | edamame `:eof` | keep |
| 14-28 | `error-value` | declared `:seon.sci.reader/failure` shape | all refusals | none (our declared shape) | keep |
| 29-53 | `refusal-handler`, `accepted-reader`, `reject-read-eval` | tag and `#=` policy fns | parse-options | edamame `:readers`/`:read-eval` *are* the seam (`core.cljc:32-72`); these are our policy values | keep |
| 54-75 | `line-starts`, `cursor-offset`, `offset-cursor` | row/col ↔ UTF-16 offset | read-events, recovery, error-event | none: edamame locs are row/col only | keep |
| 76-83 | `qualified-symbol` | ns-qualified declaration symbol | declarations | kondo var-definition `:ns`/`:name` | keep until option B |
| 84-105 | `syntax-quote-resolver` | symbol resolution | parse-options | **SCI `fully-qualify`** (`sci/impl/parser.cljc:84-142`), and ours is wrong (§0) | **delete (S2)** |
| 106-125 | `parse-options` | edamame opts | read-events | SCI `parse-next` builds them from the ctx (`parser.cljc:186-203`) | shrink to features + policy + ctx-less defer (S2) |
| 126-250 | `alias-binding` … `literal-require-bindings` (10 fns, 126 lines) | `:seon.ns/*` rows from a conformed ns form | eval `row` (`eval.clj:386`), agent ns seed (`agent.clj:175`), reading context | already composed on `clojure.core.specs.alpha/ns-form` (Clojure's grammar). SCI's namespace table after evaluation holds the same bindings (`eval.clj:940-955` already derives `require` rows from it) | keep; option C |
| 251-314 | `function-declaration`, `test-declaration` | `:seon.fn/sym`, arglists, doc, private, spec; `:seon.test/sym` | only `turn/read-only-evaluation?` (`turn.clj:1966`) through `declaration-row :all`. Eval derives function rows after evaluation (`eval.clj:372-387`) | **kondo var-definitions** → `seon.fn/var-row` (`fn.clj:755-760`) | option B |
| 315-328 | `resolved-operation` | operator identity | declarations, context, `::call` | none | keep |
| 329-373 | `declaration-occurrence` | `::declaration-family/identity/refusal` | **none** (rg: only reader.cljc; fn_test mentions a deftest name) | — | **delete (S1)** |
| 374-405 | `schema-declaration`, `schema-unregister`, `namespace-unmap` | schema key/form, unregister key, unmap mark | eval `row`/`deletion-row` (`program.cljc:1376-1416`) | none: kondo has no seon semantics | keep |
| 406-428 | `declaration-facts` | merged facts | read-events | — | keep, minus occurrence |
| 429-450 | `nested-executable-declarations` | `::nested-declarations` | **none in src**; eval's `multiple-declarations` derives from SCI interns (`eval.clj:779-897`) | — | **delete (S1)** |
| 451-515 | `namespace-changing-mention?`, `next-reading-context` | per-form namespace attribution | reply plan `:seon.ns/name`, which the turn uses as each form's eval ns (`turn.clj:1928,2126`) | **SCI's current ns while forms evaluate** (`:seon.sci.eval/ending-ns` in every SCI reply) | option C |
| 516-557 | `fence-line?`, `skip-fences!` | Markdown fence lines skipped | reply (`reply.clj:181`) | none; reply grammar, not Clojure | **move to reply (S4)** |
| 524-535 | `advance-to!` | reader positioned at an offset | read-events (recovery resume) | tools.reader cursor | keep |
| 558-662 | `read-events` | event vector | read | edamame loop + locs | shrink (S2, S3) |
| 663-702 | `reader-error`, `cause-data` | flat error from edamame ex-data | recovery, read | edamame ex-data *is* the source; this is the transformation | keep |
| 703-823 | `closer?`, `parse-classification`, `line-start-anchor`, `preceding-comment-block`, `token-end`, `recovery-point`, `error-event` | error kind, recovery anchor, error event | recovering-events; `::error-kind` read by `turn.clj:3067`, `error.clj:981`; `::prose-span?` read by `error.clj:980` | **none** (E2, E4) | keep: genuinely ours |
| 824-859 | `recovering-events` | events around unreadable spans | read | none | keep |
| 860-919 | `read` | entry; text and bound checks | 13 call sites | — | keep |

## 4. The smallest composition

```
text ──► edamame via sci/parse-next+string ──► [form source loc] ──► event map ──► callers
          ctx = the evaluating SCI ctx (eval),        (declared shape: form, source, spans,
                or one shared empty ctx (reply)        line/column, ns, declaration facts)
          opts = {:features #{:clj} :end-location true
                  :postprocess record-loc :readers policy :read-eval refuse}
    on throw: edamame ex-data ──► classification ──► error event ──► resume at anchor (ours)
```

- **Reading for evaluation is one `sci/parse-next+string` call with the evaluating context.** SCI then resolves
  `::alias/k`, syntax-quote, refers, classes and core from the namespace table the form will run in. The
  `reader-context` projection in `eval.clj:940-955` stays, because namespace rows compare before and after with it. It
  no longer drives resolution.
- **Positions come from edamame locs.** A pass-through `:postprocess` gives every value, numbers included, its start and
  end. The event's `::line`/`::column` becomes the form's own position. The comment-inclusive span start is still
  available from the offsets.
- **Syntax errors come from edamame ex-data**, which already exists. clj-kondo syntax findings are not used for reading
  (E4).
- **Recovery stays ours**, and only reply reading needs it. Evaluation refuses on the first error event (`one-event`).
- **Fences belong to the reply grammar.** `seon.cluster.reply` blanks fence lines to spaces of the same length before
  reading, so offsets are unchanged and no reader code knows about Markdown.

## 5. Deletions and loadable slices (each under 100 added lines)

| Slice | Change | reader.cljc | Elsewhere | Tests |
|---|---|---:|---|---|
| **S1** dead outputs | delete `declaration-occurrence` (45), `nested-executable-declarations` (22), `::refer-all-targets` bookkeeping (~6), their merge/dissoc (~4) | **−77** | none | drop the `::nested-declarations` assertion (`reader_test.clj:764-769`) |
| **S2** SCI owns resolution | `read` takes an optional `:seon.sci.reader/ctx`. `one-event` passes its execution ctx inside `sci/binding [sci/ns …]`. Ctx-less callers share one `(delay (sci/init {}))` rather than one per read. Delete `syntax-quote-resolver`; shrink `parse-options` | **−22 −10 +10** | `eval.clj` one-event +3 | new class regression: `` `(inc String join) `` read under a ctx resolves to core, class and refer. `explicit-reader-policy-overrides-a-hostile-sci-context` stops using `with-redefs` on `sci/init` and passes a hostile ctx (removes a Var redefinition, as the testing law requires) |
| **S3** edamame owns positions | edamame fork: `read-number` calls `(when ch (r/unread rdr ch))` (`parser.cljc:192`, +1 −1, committed in `reference-code/edamame`, parent pin bumped). Reader: delete the `peek-char` end workaround (5), take start/end from the recorded loc | **−5 −8 +6** | `reply.clj` `form-start` (18 lines) uses the event's own line/column: **−14** | `a-number-ending-the-text-keeps-its-source-and-span` now proves the upstream fix; `every-form-shape-has-a-cursor-derived-span` is updated for form-own positions |
| **S4** fences and prose move to reply | delete `fence-line?` (8) and `skip-fences!` (22) and its call (1). Reply blanks fence lines (+6) and replaces the `prose-read-failure` message regex (`reply.clj:236-237`, `:284`) with the reader's `::error-kind :invalid-token` / `::token-kind` data (−2 +1) | **−31** | reply **+5** | `fences-are-whitespace-between-reader-forms` moves to `reply_test` unchanged in substance |
| **Total S1–S4** | | **≈ −137** (919 → ≈ 782) | ≈ −6 | |

Nothing is added beyond the ctx argument and the fence-blanking function. All four slices are deletions of duplicate
paths.

**Costs.** Measured warm over 2000 calls:

| Read | µs per call |
|---|---:|
| installed armed `read`, `(+ 1 2)` | 26.6 |
| installed armed `read`, two forms | 28.6 |
| installed armed `read`, `defn` with contract | 23.7 |
| installed armed `read`, `(a) (b\n(c)` with recovery | 84.2 |
| `sci/init {}` alone, paid on every read today | 3.55 |
| raw edamame loop | 7.7–12.8 |
| SCI parse loop with the real ctx | 8.3 (9.9 for two forms) |
| clj-kondo, per text | 5,800 |

Expected after S2/S3: the per-read `sci/init` disappears, and so does the per-form rebuild of the options map
(`parse-options` is merged per form at `reader.cljc:586-588`). Each slice's landing must time the same five inputs on
the parent and the change (hot-path rule).

## 6. Options that need an owner ruling (priced)

**A. Stop after S1–S4. Simplest.** Guarantee: one parse call, the correct resolver, no dead outputs, no Markdown in the
reader. Cost: 4 small slices, ≈ −137 lines. Sacrifice: function and test declaration facts (64 lines) and cross-form
namespace attribution (65 lines) remain parallel to kondo and SCI.

**B. (recommended) Declaration facts for functions and tests leave the reader.** `turn/read-only-evaluation?` is the
only src consumer (`turn.clj:1959-1972`). It asks whether a stored evaluation's source declares something. The
evaluation's settlement already analyzes that source through kondo (`seon.fn/analyze-forms`, `fn.clj:1182`) and writes
a receipt for every declaration row. The next slice's probe: over the stored evaluations, check whether "declares"
(reader) ever differs from "has a receipt transaction", which the function already checks at `:1967-1969`. If it never
differs, delete the clause and `function-declaration` + `test-declaration` + the `:seon.fn/*` half of `declaration-facts`.
- Cost: one probe and one slice, about −70 lines.
- Guarantee: the function row has one producer, kondo.
- Sacrifice: `declaration-facts-equal-the-current-var-lift` goes, because kondo's row is covered by the fn tests.
- If the two answers do differ, the evaluation should store its kondo answer rather than re-read the source.

**C. Namespace attribution and ns rows come from SCI's table.** A reply's forms evaluate in the namespace the previous
evaluation ended in (`:seon.sci.eval/ending-ns`). Today the reader predicts that namespace before execution. `ns`-form
rows then derive from SCI's namespace table after evaluation, exactly as `require` rows already do
(`eval.clj:1080-1095`).
- Deletes `next-reading-context`, `namespace-changing-mention?` and most of the 126-line ns-row block: about −190 in
  the reader, about +25 in turn and eval.
- Cost: hours of cross-owner work in `seon.turn` (plan freeze, `:seon.ns/name` on planned sources), in `seon.cluster.agent`
  (its namespace seed reads a printed `ns` form without evaluating it) and in the tests
  `namespace-tracking-has-repl-semantics-and-fails-closed`, `standalone-require-advances-the-next-forms-reading-context`
  and `renamed-and-refer-all-bindings-resolve-to-exact-targets`, which become eval/turn tests of the ending namespace.
- Sacrifice: a frozen plan no longer names each form's namespace before it runs. The execution receipt names it after.
  This changes a turn contract, so it needs the owner.

## 7. The scattered readers

"Legit" means EDN data read as EDN.

| File:line | Read | Verdict |
|---|---|---|
| `artifact.clj:51` | packaged EDN resource | legit |
| `blob.clj:51` | pr-str → edn round-trip fidelity probe | legit; this *is* the EDN check |
| `error.clj:715` | stored `:seon.error/data-edn` | legit |
| `error.clj:940` | stored `:seon.fn/spec` string | legit EDN of a stored Malli form |
| `fn.clj:2993,3000,3176,3185,3291` | stored schema/spec strings | legit. `fn.clj:1453,2553` `kondo.utils/parse-string-all` is kondo's own parser inside the analyzer owner: legit |
| `repl.clj:85` | shown text back to a value | legit: shown text is printed EDN or prose |
| `repl.clj:124` `def-note` | `edn/read-string` of **agent Clojure source** | **duplicate, unsafe**. E5: `'a` misreads silently; `#(…)` and `@` drop the note. Converge on the evaluation's reader-event form |
| `repl.clj:163` | stored triage EDN | legit |
| `schema.clj:750,1463,1787` | round-trip identity checks | legit |
| `schema.clj:1125` | `clojure.basis` file | legit |
| `schema.clj:2977` | stored form string | legit |
| `db.clj:4040` | stored schema encoding | legit |
| `print.cljc:107,1080` | re-read printer-emitted literal text to key a node or path | legit EDN, but the value is re-derived by printing then reading. Suspect: prefer the node's value. Not a reader duplicate |
| `turn.clj:1294,1301` | stored schema/spec strings | legit |
| `turn.clj:1858,2317,3048,4666` | calls of the one reader | converged. `source-events`, `evaluable-source?` and `disposition-rule-error` re-read stored sources each call (~26 µs each). Acceptable; reuse the planned event if profiling shows it |
| `program.cljc:88` | symbol round-trip predicate | legit |
| `program.cljc:691` `read-edn` | stored spec/schema strings | legit |
| `instrument.clj:236` | stored `:seon.fn/arglists` (kondo arglist text) | legit in practice. Arglists read as EDN, and `^long` is kept. Fragile by kind, because the source is Clojure text |
| `instrument.clj:518` | stored spec | legit |
| `ai.clj:646` | stored extra-body EDN config | legit |
| **also found** `fn/signature.cljc:17-31` | `clojure.core/read` of Clojure source with a hand `LispReader$Resolver` | **duplicate reader.** A third Clojure-source reader, beside ours and kondo's. Its alias resolver returns the alias itself (the parser-proves note, 5d). Converge on kondo's analysis, which already carries arglists and metadata, or on `seon.sci.reader`. Owner: `seon.fn.signature` |
| **also found** `sci/eval.clj:1258` `declaration-source-value` | `edn/read-string` of Clojure source to compare declarations | **duplicate, unsafe** (E5); it falls back to byte comparison. Use the event form |
| **also found** `script/seon/dev/mcp.clj` `require-single-clj-form!` | the MCP bridge pre-reads each form. This probe hit its `Alias an not found in :auto-resolve` on `::an/k` in the same form as `(require …)` | duplicate. Its deletion is already R7 in `lane-fix-one-reader-errors-2026-09-25.md` (about 80 lines) |

No read is dangerous in the `#=` sense. `clojure.edn` never evaluates, and edamame/SCI refuse `#=` by our policy. The
unsafe reads are *silent misreads* of Clojure source through the EDN reader.

## 8. Where our layer adds something the libraries lack

- **Recovery.** Isolated unreadable spans, top-level anchors that never land inside inner maps or vectors, and a
  terminating advance. E2 and E4 show that edamame stops at the first error and kondo auto-closes into a different tree.
  The reply splitter (`reply.clj:325-393`) and the Parinfer repair (`turn.clj:3056-3112`) are its consumers.
- **The declared error shape and classification** (`:stray-closer`, `:unclosed`, `:odd-map`, `:invalid-token`,
  `:bad-metadata`, `::prose-span?`). These are built from edamame's structured facts, never from message prose.
- **Seon declaration semantics** that kondo does not model: `seon.schema/register!`, `unregister!` and `ns-unmap`
  marks.
- **The fabricated-response refusal** (`{:seon.repl/… }` in a reply).

Everything else in the file either transforms library output into our shape, which is its job, or duplicates a library
or another Seon owner. §3 marks each case.

## 9. Open verification limits

- Not installed or tested: this is a design. S1–S4 were not run as slices. E3 is a scratch-ctx probe, not the agent's
  execution ctx.
- Option B's equivalence ("declares" versus "has a receipt") is unprobed. It is the first probe of that slice.
- The UTF-16 offsets from edamame locs were checked on one multibyte input only. The CRLF test
  `original-source-spans-preserve-crlf-and-utf16` must stay green through S3.
