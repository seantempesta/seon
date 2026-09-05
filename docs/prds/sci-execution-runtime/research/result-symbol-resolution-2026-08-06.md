---
type: research
status: complete
tags: [research, sci, repl, database]
---

# Result-symbol resolution in SCI

## Read record and scope

I read the following required authorities in full before designing or probing:

- the complete root `AGENTS.md` supplied with this task;
- the complete **Call preparation r2 IS RULED** block in
  `docs/prds/sci-execution-runtime/plan/README.md:704-731`;
- all of
  `docs/prds/sci-execution-runtime/plan/ambient-injection-prd-2026-08-05-r2-draft.md`;
  and
- the localized
  `docs/prds/sci-execution-runtime/AGENTS.md` plus the applicable
  `data-oriented-clojure`, `datahike`, `repl`, and
  `seon-flow-architecture` skills.

Production source and dependencies remained read-only. The only authored probe
is `tmp/result_symbol_resolution_2026_08_06.clj`. This report is the only
committed path.

## Verdict

The ruled spelling has a reader-level blocker before SCI resolution:
**`result/88` is not a valid Clojure/Edamame symbol.** On the selected SCI pin,
the Seon reader returns:

```clojure
#:seon.error{:kind :seon.sci.reader/unreadable
             :message "Invalid symbol: result/88"
             :data #:seon.sci.reader{:text "result/88"
                                     :phase "parse"
                                     :line 1
                                     :column 10}}
```

`seon.sci.eval/evaluate` preserves that kind as its flat error value and records
zero function entries. SCI analysis and its symbol resolver never run.

The recommendation is therefore one mechanism with one spelling prerequisite:

1. render a standard readable symbol such as **`result/eid-88`**;
2. on the turn's `:io` workload, read the source once, walk that parsed event
   without entering `quote`, query each referenced receipt from the form's
   pinned database value, read any blob, and intern only those requested names
   into the fresh turn fork; and
3. submit that same parsed event to the existing `:compute` evaluation, where
   SCI resolves an ordinary Var and every later mechanism—including ambient
   call preparation—sees only the ordinary stored value.

This needs **no SCI fork change**. It composes before the ambient r2 seam rather
than competing with it. It covers literal handle occurrences in every
evaluated position. It deliberately does not reinterpret quoted or
syntax-quoted handles, nor handles constructed by a macro or `symbol` after the
preparation pass.

Changing Edamame to accept a multi-digit numeric name segment would be a tiny
predicate edit but a large language break: `pr-str` would emit source the host
Clojure reader cannot read back. A raw-text repair before the reader would be a
second lexical grammar. Neither is recommended.

## Dependency ledger

| Owner | Selected revision | Relevant seam |
|---|---|---|
| SCI fork | `2db3358cba913b6fbbe49c7b5b34d7ac72715924` | parser, analyzer resolution, context fork, transient intern |
| Edamame checkout | `38e627467daa3f6f1e5a8eb6421f702d2a940b7f` | token-to-symbol grammar used by SCI |
| Datahike fork | `56f1c62105b7087f0cac13162f9fd54b1690986e` | immutable database value and explicit-eid pull |
| Konserve fork | `07377c27c8288b7484f0aa7b82e8158b415985be` | synchronous `bget` beneath `seon.blob/get` |
| core.async fork | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `:io` versus `:compute` workload contract |

First-party owners are `src/seon/sci/reader.cljc`,
`src/seon/sci/eval.clj`, `src/seon/sci/kernel.clj`, `src/seon/db.clj`,
`src/seon/blob.clj`, and the turn submission at
`src/seon/cluster/loop.clj`.

## 1. Current parse and resolution path

### `result/88` fails before analysis

SCI delegates parsing to Edamame at
`reference-code/sci/src/sci/impl/parser.cljc:142-190`. Edamame's
`parse-symbol` rejects a qualified symbol whose name begins with a digit at
`reference-code/edamame/src/edamame/impl/parser.cljc:139-161`; the token reader
raises `Invalid symbol` at
`reference-code/edamame/src/edamame/impl/parser.cljc:465-478`.

There is an odd one-digit exception for array-dimension syntax at
`reference-code/edamame/src/edamame/impl/parser.cljc:133-158`, mirrored by
SCI's array-class resolution attempt at
`reference-code/sci/src/sci/impl/resolve.cljc:56-61`. Thus `result/3` may
parse while `result/88` does not. Receipt eids are unbounded, so this exception
cannot define the handle grammar.

Seon's one reader calls `sci/parse-next+string` at
`src/seon/sci/reader.cljc:498-525`, catches the parse failure, and returns the
flat `:seon.sci.reader/unreadable` value at
`src/seon/sci/reader.cljc:621-638`. `one-event` raises that value into the
total evaluation boundary at `src/seon/sci/eval.clj:528-543`; `evaluate`
then catches it and converts it back into the admitted flat value at
`src/seon/sci/eval.clj:1762-1788`.

The exact `seon.sci.eval/evaluate` probe observed:

```clojure
{:seon.sci.admit/value
 #:seon.error{:kind :seon.sci.reader/unreadable
              :message "Invalid symbol: result/88"
              :data {... :seon.sci.reader/phase "parse" ...}}
 :seon.cluster.eval/error "Invalid symbol: result/88"
 :seon.sci.admit/record
 #:seon.eval{:fn-entries 0 :host-interop-count 0 :outcome :error ...}}
```

### What a readable but absent qualified symbol does

For a readable stand-in such as `result/e88`, a value-position symbol reaches
`resolve/resolve-symbol` from
`reference-code/sci/src/sci/impl/analyzer.cljc:2296-2325`. A call-position
operator reaches the same resolver from
`reference-code/sci/src/sci/impl/analyzer.cljc:1909-1923`.

Qualified lookup resolves the current namespace's alias and then directly
looks in `[:namespaces resolved-ns simple-name]` at
`reference-code/sci/src/sci/impl/resolve.cljc:40-81`. With no `result`
namespace or name, `resolve-symbol` throws:

```clojure
Unable to resolve symbol: result/e88
```

with `{:phase "analysis" :sci.impl/symbol result/e88}` from
`reference-code/sci/src/sci/impl/resolve.cljc:323-334`. Seon's total boundary
classifies that ordinary throwable through
`src/seon/sci/kernel.clj:256-302` and returns
`:seon.sci.eval/evaluation-failed` from the catch at
`src/seon/sci/eval.clj:1762-1788`.

SCI has no dynamic symbol-resolution option today. The public option list has
namespace maps, aliases, interrupt and observation functions, but no
`:resolve` hook (`reference-code/sci/src/sci/core.cljc:277-312`). The internal
context fields and option plumbing likewise contain no resolver callback
(`reference-code/sci/src/sci/impl/opts.cljc:207-235,244-284`). `:load-fn`
serves explicit namespace loading; qualified-symbol lookup does not call it.

## 2. Candidate seams

### A. Lazy hook at SCI symbol resolution

**Mechanics.** Add an optional callback after ordinary `resolve-symbol*`
returns nil and before the analysis error at
`reference-code/sci/src/sci/impl/resolve.cljc:323-334`. A complete public
implementation would thread one option through `sci.core`, `opts/Ctx`,
`init`, and `merge-opts`. Estimated fork size is roughly 25–40 production
lines plus tests and option documentation.

**Coverage.** It sees all symbols SCI analyzes: bare value position,
collection children (collections recursively analyze children at
`reference-code/sci/src/sci/impl/analyzer.cljc:2215-2266`), function
arguments, destructuring initializer expressions, direct call position, and
symbols introduced by macro expansion. It does not see quoted content because
`analyze-quote` returns the second form as a constant without walking it
(`reference-code/sci/src/sci/impl/analyzer.cljc:1757-1761`). Syntax quote of
a qualified symbol also becomes quoted data through the reader resolver at
`src/seon/sci/reader.cljc:80-116`.

**No-result cost.** If placed only after normal lookup fails, successfully
resolved symbols pay no callback. The exact fork path cannot be benchmarked
without implementing the fork change; it would still require the ambient-r2
style retained benchmark before acceptance.

**Verdict.** Not recommended for result values. `evaluate` runs synchronously
on the bounded `:compute` task and promises never to block
(`src/seon/sci/eval.clj:1558-1576`). A blob-backed handle would execute
synchronous `bget` and `read-string` during analysis. A callback that hops to
`:io` and waits still blocks `:compute`; one that returns a future is not
transparent ordinary-value semantics.

### B. Ambient r2 call-preparation primitive

The primitive is ruled but not present on the selected SCI pin. Current calls
resolve the operator during `analyze-call`, evaluate arguments, and invoke the
callable through `return-call`/`fn-call` at
`reference-code/sci/src/sci/impl/analyzer.cljc:1717-1753` and
`reference-code/sci/src/sci/impl/evaluator.cljc:384-420`.

The ruled hook receives an already resolved callable plus already evaluated
arguments. Therefore it cannot resolve `result/e88` as an argument: analysis
fails before call preparation. It also never sees a bare handle, a handle in a
vector or map literal, or a destructuring initializer. Quote remains data.

Its no-hook/empty-plan cost is intentionally an ambient-r2 graduation gate,
but no current implementation exists to benchmark. Even a zero-cost call hook
would not satisfy result handles because the missing positions are semantic,
not performance edge cases.

**Verdict:** reject for this job. The result preparation pass completes before
analysis, after which ambient call preparation sees ordinary evaluated
arguments and composes normally.

### C. One-reader parsed-form preparation

**Mechanics.** Read once on the turn's `:io` workload, collect executable
handle symbols from the parsed form, fetch each distinct eid from the form's
pinned database value, load and parse blobs there, and use SCI's ordinary
`intern` operation (`reference-code/sci/src/sci/core.cljc:259-270`) to bind
only those names in the fresh turn fork. Carry the same parsed event into
`evaluate`; do not call a second reader.

Today `evaluate` obtains its reader event at
`src/seon/sci/eval.clj:1647-1655` immediately before `sci/eval-form` at
`:1668-1680`. The necessary scheduling refinement is to perform that one read
and preparation before `submit-evaluation!!`: the turn captures
`db-before-evaluation` at `src/seon/cluster/loop.clj:1560-1569` and submits
the compute task at `:1573-1588`.

Do not rewrite a handle directly to its stored Clojure data: a stored list or
symbol would be reinterpreted as source. Transient interning makes the analyzed
form unchanged and lets SCI's existing Var read produce the ordinary value.

**Coverage.** Bare value, vector/map/set child, function argument, callable
ordinary collection in function position, and destructuring initializer all
work. Once resolved, passing the value to another agent is an ordinary
`my.message/send` argument. `quote` and syntax quote deliberately retain the
symbol as data. The first version also deliberately excludes handles created
after preparation by macro expansion, runtime `symbol`, or nested `eval`.

**No-result cost.** The probe measured the complete no-handle preparation walk
plus SCI evaluation against baseline SCI evaluation. Conditions: OpenJDK
26.0.1, SCI pin above, parsed `(+ 1 2)`, 20,000 evaluations per sample, four
warmups, nine paired samples, three fresh JVMs, and consumed results.

| Metric | Three-JVM result |
|---|---:|
| baseline | 619–631 ns/eval |
| prepared | 671–682 ns/eval |
| added | 47.8–57.5 ns/eval |
| median added | 53.0 ns/eval |
| median ratio | 1.086× |

The 8.6% relative number is inflated by the deliberately tiny 0.62 µs form;
the absolute cost is the useful datum. It is still material enough to retain
as a paired benchmark rather than declare free. The existing reader-event
construction can collect handles during its already-required parsed-form work
to avoid a second generic walk if the retained benchmark justifies that
refinement.

**Verdict:** recommended, after adopting a readable handle spelling.

### D. Eager `result` namespace interning

Interning every receipt eid when a turn fork is built would make later SCI
lookup ordinary and would cover the same evaluated syntactic positions as C.
Quote still returns symbol data. Per-form no-handle cost would be zero.

The cost merely moves to every turn: query every historical receipt, allocate
one Var per eid, and either load every blob or retain a non-transparent lazy
placeholder. Eids and receipts are unbounded, while most forms refer to none.
The fresh fork currently rehydrates only the selected agent's def facts at
`src/seon/sci/eval.clj:1309-1367`; adding every result would invert that
selectivity.

**Verdict:** reject. Interning only handles detected in the current parsed
form is bounded preparation, not eager namespace population.

## 3. REPL probe

The reproducible probe is
`tmp/result_symbol_resolution_2026_08_06.clj`. Each invocation uses
`clojure -M:dev`, creates a fresh in-memory Datahike database, stores one
receipt, constructs the context with `seon.sci.eval/build-base-ctx`, forks it
with `sci/fork`, reads with `seon.sci.reader/read`, detects executable handles,
pulls the receipt from the immutable `db-after`, interns the value, and calls
`sci/eval-form`.

Because `result/88` cannot be parsed, the mechanics probe uses the readable
stand-in `result/e3`. Exact results:

| Source | Detected | Value |
|---|---|---|
| `result/e3` | `#{result/e3}` | `{:answer 42 :items [1 2 3]}` |
| `[result/e3 :tail]` | `#{result/e3}` | `[{:answer 42 :items [1 2 3]} :tail]` |
| `(get result/e3 :answer)` | `#{result/e3}` | `42` |
| `(let [{:keys [answer]} result/e3] answer)` | `#{result/e3}` | `42` |
| `'result/e3` | `#{}` | `result/e3` |
| `` `result/e3 `` | `#{}` | `result/e3` |
| `result/e999999999` | requested missing eid | flat `:seon.result/not-found` value |

This demonstrates bare position, vector literal, function argument, and
destructuring as requested. It also falsifies accidental resolution inside
quote and syntax quote.

## 4. Resume and pinned database value

A handle lookup is a pure read from one immutable database value. Seon exposes
that value's branch, basis transaction, and commit ID at
`src/seon/db.clj:132-140`, and explicit `db/pull` uses the supplied value at
`src/seon/db.clj:632-670`. No connection reread is necessary.

The turn already captures `db-before-evaluation` once at
`src/seon/cluster/loop.clj:1560-1569`. That exact value should be supplied to
result preparation. A transaction committed after capture is invisible;
repeating the preparation in a fresh fork or fresh JVM at the same database
value returns the same result. This is L9 snapshot isolation, not a result
cache.

Fresh-turn restoration already proves the structural precedent: `fork-for-turn`
forks the program base, queries the agent's def IDs from its explicit database argument,
and rehydrates values without relying on prior interns at
`src/seon/sci/eval.clj:1309-1367`. Result preparation uses the same context and
database-value relationship but creates no durable `:seon.def` row.

When the eid is absent at that basis, `db/pull` returns nil. Preparation must
bind a flat value such as:

```clojure
{:seon.error/kind :seon.result/not-found
 :seon.error/message "No receipt 88 exists at this database basis."
 :seon.error/data {:db/id 88}}
```

It must never bind nil and must not throw into analysis. The probe demonstrates
that behavior. The existing total boundary's canonical conversion of thrown
failures to flat values is `src/seon/sci/kernel.clj:256-302`, called by the
evaluation catch at `src/seon/sci/eval.clj:1762-1788`; result absence should
construct the flat value directly because it is expected agent-visible data,
not a core throwable.

## 5. Blob-backed results and admission

The receipt's inline value is the admitted canonical `result-edn`. Admission
realizes and caps the semantic projection, then prints that same finite node at
`src/seon/sci/admit.clj:469-540`. For a large result, settlement stages the
**full admitted result-edn** as a content-addressed blob and stores a smaller
window plus digest and size on the receipt at
`src/seon/cluster/loop.clj:529-552`.

Reading the full value is synchronous `konserve/bget`, digest verification,
UTF-8 decoding, then `edn/read-string`: `src/seon/blob.clj:314-335` plus the
same parse shape used by restoring the agent's defs at
`src/seon/sci/eval.clj:1297-1307`. The measured resume precedent records
7–15 ms for `bget` plus 38–45 ms for `read-string`, or 45–60 ms total for
1.29 MB.

If this work is placed in SCI resolution or the present `evaluate` body, all
45–60 ms lands on the bounded `:compute` task, contradicting
`src/seon/sci/eval.clj:1558-1576`. Under the recommended preparation seam it
lands before compute submission on the turn proc's declared `:io` workload.
Database pull and blob read complete before the form is analyzed.

Do **not** re-run result admission on ingress. The blob is already the source
evaluation's bounded semantic value, including explicit elision values where
caps applied. Re-admission under later caps could change the handle's ordinary
value and violate same-basis identity. restoring the agent's defs likewise performs
`blob/get` plus `edn/read-string` and interns directly
(`src/seon/sci/eval.clj:1297-1307,1338-1361`). The current form's caps still
apply normally when any value leaves that evaluation at
`src/seon/sci/eval.clj:1739-1752`, and message/effect boundaries retain their
own admission.

## 6. Recommended implementation boundary

The implementation boundary, after the owner rules on the spelling, is:

1. make the result handle a valid standard symbol, recommended
   `result/eid-<eid>`;
2. extend the one reader event with the distinct executable result symbols,
   structurally excluding `quote`;
3. before `submit-evaluation!!`, resolve those symbols from
   `db-before-evaluation`, including synchronous blob reads, returning a flat
   value for every expected absence/refusal;
4. add the `result` namespace only when the set is nonempty and transiently
   intern those resolved values into the fresh turn fork;
5. carry the same reader event into `evaluate` so source is read once; and
6. retain the paired no-result benchmark and the bare/vector/argument/
   destructuring/quote/missing-eid/fresh-JVM proofs.

SCI fork change size: **zero**. The result mechanism is a bounded
database-value preparation step at the existing one-reader/turn/eval boundary.
The maintained SCI fork remains focused on the separately ruled ambient
call-preparation primitive.

Deliberate exclusions are quoted and syntax-quoted handle symbols,
macro-generated or dynamically constructed handles, eager installation of
unreferenced receipts, and any redefinition of ordinary Clojure symbol
grammar. Those exclusions preserve the claim: a handle written literally in
an evaluated position is an ordinary value everywhere it evaluates.
