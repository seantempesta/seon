---
type: prd
status: active
tags: [prd, runtime, sci, architecture, render]
---

# Parse primitives — one reader, classification at the parse (2026-07-29)

**Rev 2 — 2026-07-29.** Rev 1 (`ea9d4cea6`) was refused seal by
`research/parse-plan-falsification-2026-07-29.md` (`b94f91263`): 4 seal-blocking,
3 revision, 3 note. Every finding is accepted; none is refuted. Two mechanisms
are dead and redesigned rather than patched — refuse-all-by-empty-map, and the
in-memory event handoff from split to fold. A third, per-form namespace
attribution, was withdrawn mid-revision and then **restored by owner ruling**
with REPL semantics (§0.3), which is also what makes this plan and generate-code
v0 name one mechanism. Rev 2 markers are inline; §0.2 carries the per-finding
dispositions.

Owner ruling this plan implements (`plan/README.md`, 2026-07-29):

> the eval parse is NOT a reply parser — one GENERAL parser turns any
> code-bearing text into runnable forms anywhere in the system, and the
> `:io`/`:compute` workload detection happens IN THE SAME AREA (the parse pass
> lifts classification facts; forms go to the right scheduled lanes from
> there). Find and condense every duplicated parse/serve path into a core
> primitive set that works everywhere.

The audit is `research/parse-serve-duplication-2026-07-29.md` (`eaf55f358`):
eleven accepted Clojure read routes in eight entry points, three detached fact
lifts, one missing classification query, five admission bypasses, three
delivery motions beside the router. This plan seals the one reader, places
classification beside it, judges what must NOT be condensed, and orders the
cuts. It does not authorize the delivery wave beyond naming its boundary.

**Seal (orchestrator, 2026-07-29): D8 RULED YES — the starting reading
context freezes on the run at plan freeze (the mechanism the owner's
REPL-semantics ruling requires; recovery re-reads from it, re-executing
nothing). D9 RULED JOINT — E1 + D2 land as ONE evaluator revision
(owner batch 4: one mechanism, two complementary facts; v0-B was
implicitly rejected by the same ruling). S1 SEALED and dispatching now;
S2+S3 sequential in one lane once the generate-code rev agrees in
writing on the freeze/receipt facts, per this plan's own coordination
duty.**

## 0. Dependency ledger

| Dependency or mechanism | Selected revision | Contract used here |
|---|---|---|
| SCI | vendored `8fac6e88f32d53a5fd82ebe80640881e317b84fd`; `deps.edn:39-44` | `source-reader` + `parse-next+string` return `[form source-buffer]`; `eval-form` takes an already-parsed form; `parse-string` reads only the next form (`reference-code/sci/src/sci/core.cljc:352-402`) |
| SCI parser | same revision, Edamame underneath | **`parse-next` merges caller `opts` LAST over its ctx-derived options** — `parse-opts (cond-> (assoc default-opts …) opts (merge opts))`, so `:features`, `:auto-resolve`, `:readers`, `:syntax-quote`, `:read-cond`, `:read-eval` are all supplyable as explicit parameters (`reference-code/sci/src/sci/impl/parser.cljc:142-190`). Falsification N1 independently probed all five and they hold |
| SCI default parse opts | same | `{:all true :row-key :line :col-key :column :read-cond :allow :location? seq? :end-location false}` (`parser.cljc:42-50`) — **locations only on seqs, no end locations by default**; symbols get line/column patched by hand in `parse-next` |
| Edamame built-in tags | via SCI, not vendored under `reference-code/` | `#inst` and `#uuid` are Edamame's own and survive `:readers {}`. **Probed rev 2** (`research/scripts/reader-refusal-2026-07-29.clj`): a `:readers` **function** is consulted for every tag including built-ins; a `:readers` **map** is not (§1.2) |
| SCI refusal | same | `*read-eval*` defaults false → `#=` throws `EvalReader not allowed…`. Probed rev 2: an explicit `:read-eval` in opts **replaces** that ambient policy, so the reader never inherits a dynamic var |
| landed splitter | `7d32ecec5` (`src/seon/cluster/reply.cljc`) | reads raw text FIRST, then classifies events as code/prose, rewrites prose gaps as `;` comments, and may re-read after commenting a failing line; fence stripping; flat `::unreadable`/`::refused-tag`/`::no-forms`; suite green 7 tests / 25 assertions |
| quarry comparison | `research/reply-parser-quarry-2026-07-29.md` | prose is comment DATA, not narration attributes; no rewrite-clj, no parinferish, no execution-time repair |
| landed evaluator | `src/seon/sci/eval.clj:292-390`; `src/seon/schema/eval.edn:7-22` | parses INSIDE the armed ctx today; the request map is **closed and requires `:seon.cluster.run.form/source`** |
| live freeze / fold / resume | `src/seon/cluster/run.cljc:365-411`; `cluster/loop.cljc:689-749`; `cluster/work.cljc:96-124`; `schema/run.edn:40-70,93-96` | the plan commits form id, run, ordinal and **source only**; cold resume creates a fresh `sci/fork` and calls `evaluate` with source plus agent id |
| **generate-code v0 rev 2** | `6ed8fc3ea`; `plan/generate-code-v0-plan-2026-07-29.md:150-200,583-601` | Withdrew `:seon.cluster.run.form/ns` because the evaluator rebinds `sci/ns` per form, so a parse-time attribution would contradict the runtime. **Superseded by the owner's batch-4 ruling (§0.3)**, which removes the contradiction at its source: the evaluator honours the parse-time namespace. What survives unchanged is precondition **E1** — run-stateful namespace plus evaluated-namespace on the receipt, owner `seon.sci.eval` |
| classification | `research/workload-classification-2026-07-28.md:71-148` | leaf `^{:seon.workload …}` metadata + reachability over call edges; probed fold; only-compute→`:compute`, only-io→`:io`, both or unresolved→`:mixed` |
| N5 corpus | `research/renderable-corpus-plan-2026-07-28.md:100-200` | the `:seon.fn` row: sym/ns/source/fingerprint/arglists/doc/private?/spec/read-attrs landed, plus `calls`, `uncertainties` (eight members, all fail-closed), `workload`, arity component rows |
| workload consumption seams | `src/seon/flow.clj:386-420`; F3 in `plan/README.md:822-828` | proc workload tags and `submit!!` — two seams, never per frame |
| test discovery | `bin/test:51-88` | every `*_test.clj[c]` under `test/` is discovered from the filesystem; no slice can hide a red request-contract test |
| quarry analyzer | `src-old/seon/program/edge.cljc:346-516` | the shape to learn from (calls, effect, eight uncertainties). **Not to port**: its resolution depends on loaded namespaces and macro inventories |

### 0.1 Rev-2 probe, recorded

`research/scripts/reader-refusal-2026-07-29.clj`, run with `clojure -M:dev` on
2026-07-29. This is the evidence for SB1 and R2, and it is reproducible:

```text
inst/{}   #inst "2020-01-01T00:00:00.000-00:00"     ; :readers {} does NOT refuse
uuid/{}   #uuid "00000000-0000-0000-0000-000000000000"
inst/fn   REFUSED: refused tag inst                  ; :readers as a FUNCTION does
uuid/fn   REFUSED: refused tag uuid
foo/fn    REFUSED: refused tag foo/bar
plain/fn  (+ 1 2)                                    ; ordinary code unaffected
readeval-default   REFUSED: EvalReader not allowed when *read-eval* is false.
readeval-explicit  REFUSED: refused #=               ; opts :read-eval replaces the var
span (def x [1 {:a 2}]) before [1 1] after [2 19] buf "; c\n(def x [1 {:a 2}])"
span 42                 before [2 19] after [3 3]    buf "42"
```

Three facts follow. **The refusal set is closed only by a total function**, not
by an empty map. **`:read-eval` is a live parameter**, so `#=` policy is
declared rather than inherited. **Reader cursors are total** — the second
form's `before` equals the first's `after`, so consecutive cursor pairs
partition the input exactly, including the leading `"  ; c\n"` that the trimmed
buffer drops and that atoms could never carry as metadata.

### 0.2 Rev-2 dispositions

| Finding | Disposition |
|---|---|
| **SB1** — `:readers {}` does not close the refusal set; `:read-eval` not explicit | **ACCEPTED, mechanism replaced.** The reading context declares an accepted-tag map and the reader installs a **total `:readers` function** that serves a declared handler or refuses by name — built-ins included — plus an always-explicit rejecting `:read-eval`. Probed §0.1. Rev 1's "empty map refuses all" sentence is deleted, not softened (§1.2) |
| **SB2** — resume cannot reconstruct the same reading context | **ACCEPTED, recommended construction adopted.** The fold's reading context is rebuilt by **re-reading the ordered plan prefix, read-only, from a starting context frozen on the run**; the starting context becomes one per-run fact (owner decision D8). This re-executes nothing. It is a *reading-context* fact, distinct from the per-form namespace attribution the batch-4 ruling restored (§0.3, §1.6) |
| **SB3** — a preserved prose-only source has no read event | **ACCEPTED, one answer chosen.** The reader returns `[]`; **the fold, not the evaluator, owns the zero-event case** and commits a terminal receipt without calling `evaluate`. D2 is unviolated because the evaluator is never handed a non-event. Rev 1 implicitly chose all three incompatible answers (§1.7) |
| **SB4** — S3 cannot land under its owned paths or keep `bin/test` green | **ACCEPTED in full.** `src/seon/schema/eval.edn`, `src/seon/schema/run.edn`, `test/seon/sci/eval_test.clj`, `test/seon/cluster/agent_test.clj`, `test/seon/cluster/turn_test.clj` join S3's owned paths; **focused `bin/test` green is an S3 exit that precedes the kill-9 drive** (§8) |
| **R1** — prose classification is reader-assisted, not pre-read normalization | **ACCEPTED.** The flow is **read → reply-policy classify → normalize → same-reader re-read when needed**. Only fence stripping is genuinely pre-read (line surgery on text). The ownership rule is unchanged: English policy stays in `seon.cluster.reply`; the reader stays English-unaware (§1.4) |
| **R2** — spans are not total from metadata; `parse-next+string` trims | **ACCEPTED, mechanism named.** Spans come from **reader cursors** (`sci/get-line-number` / `get-column-number` before and after each `parse-next+string`) resolved against a line-start index of the original input. Not metadata, not `.indexOf`. Offsets are **character offsets** (JVM UTF-16 code units, `subs`-compatible); `max-bytes` is renamed `max-chars` and "exact bytes" becomes "exact source text" (§1.3) |
| **R3** — no API carries hot-path events into freeze | **ACCEPTED, boundary value named.** The owner's batch-4 ruling (§0.3) makes freeze need the parse-time namespace, so this does *not* dissolve. `reply/sources` returns **plan rows** — `{:seon.cluster.run.form/source, :seon.cluster.run.form/ns}` — instead of bare strings: the value freeze commits, produced by the pass that read it. No parallel reply API, no event transport (§1.6) |
| **N1** — caller-option precedence holds | Noted; the load-bearing claim survives and is now double-probed |
| **N2** — slice order does not break hook/MCP discovery | Noted; S4/S5 temporary duplication is accepted and named |
| **N3** — the UI plan is not a code-reader client | **Adopted as a boundary rule** (§3): HTTP form data and control refs are data decoding and never enter the reader; agent-authored renderer code reaches it through eval/N5 |

### 0.3 Owner ruling — parse-time attribution with REPL semantics **[rev 2, batch 4]**

Received mid-revision (`plan/README.md` batch 4), and it revises rev 2's own
first draft of §1.3:

> namespace attribution is PARSE-TIME with REPL semantics — the reader
> associates each form with the namespace in effect, exactly like pasting
> `(ns a) (defn x) (ns b) (defn y)` into a REPL; the owner confirmed the OLD
> parser did this and it is the wanted behavior.

So the per-event namespace in effect is **load-bearing for the generate-code
loop**, whose planner replies span namespaces — not merely an indexer
convenience. Three consequences, and they compose with generate-code rev 2
rather than reversing it:

1. **Parse-time namespace is attribution, and the reader owns it.** REPL
   semantics are exactly what §1.3's tracking already describes: a top-level
   `(ns a)` or `(in-ns 'a)` changes the namespace for every following top-level
   form until the next one.
2. **The evaluator receives it.** D2 already hands `evaluate` the whole event, so
   the parse-time namespace arrives for free and the evaluator establishes *that*
   namespace for the form — replacing today's rebind-to-the-agent-namespace per
   form, which is precisely the behavior that made a `def` after
   `(in-ns 'my.gen.alpha)` land in `my.agents.planner`.
3. **E1's evaluated namespace is the complement, not a rival.** The receipt
   records where the form *actually* ran. In the ordinary case the two agree by
   construction; where they diverge the comparison is a **free derived query**
   over facts that already exist — no flag, no stored comparison.

The honest boundary between the two, which is why both are wanted: the reader
sees **top-level** forms only. A namespace switch nested inside a `do`, produced
by a macro, or computed at runtime is **not** visible at parse. Rev 2's rule
holds unchanged for those: **absence, never a guess** (R34) — no `/ns` on that
form — and the receipt's evaluated namespace is what catches it. Parse-time
attribution is therefore total for the code agents actually write and explicitly
silent where only evaluation can know.

Rev 2's earlier line "`:seon.sci.reader/ns` is a read-event field, never a
committed run fact" is **withdrawn**. It is committed as attribution; §1.3
carries the corrected rule, and ns-tracking fidelity becomes a first-class
sealed property with adversarial cases (§4.6).

### 0.4 Coherence with generate-code v0 rev 2 — E1 and D2 are ONE revision

Both plans change the same seam: `seon.sci.eval/evaluate`'s closed request and
its receipt. Rev 2 states the dependency as a single mechanism rather than two
overlapping edits.

| | generate-code rev 2 (E1) | this plan (D2) |
|---|---|---|
| wants | the run's namespace **stateful across forms**, and each receipt recording the namespace the form **actually evaluated in** | `evaluate` takes a **read event**, so the second parse dies, acceptance is one grammar, and the form's **parse-time namespace** arrives with it |
| touches | `seon.sci.eval` request/response, `schema/eval.edn`, `schema/run.edn` | the same three |
| conflicts? | **No — they are the two halves of one fact.** The event supplies the namespace the form was *written* in; the evaluator establishes it, and the receipt records the namespace it *ran* in. Their comparison is a derived query (§0.3) |

**The answer: S3 is one slice that lands E1 and D2 together, owned by
`seon.sci.eval`.** Splitting them means two breaking changes to one closed
request map and two rounds of the same test churn, for no gain — and the
closed-map schema makes a half-migration unrepresentable anyway. The revised
request is:

```clojure
{:seon.sci.reader/event   { … :seon.sci.reader/ns 'my.gen.alpha … }  ; D2 — form, source, PARSE-TIME ns
 :seon.cluster.run/ns     'my.gen.alpha   ; E1 — the namespace the run carried in
 :seon.sci.admit/caps …  :seon.sci.eval/ctx … :seon.cluster.agent/id …}
```

The evaluator establishes the event's parse-time namespace for the form
(replacing the per-form rebind to the agent namespace), and the evaluation
returns the namespace it ended in, which the fold threads to the next form and
commits on the receipt (E1's fact, from E1's owner). The run-carried namespace
remains in the request because only evaluation can know about a switch the
reader could not see (§0.3); when the event carries a namespace it wins, and
when it does not the run's carried namespace continues.

Two consequences this plan accepts:

- **`:seon.cluster.run.form/ns` survives as parse-time attribution**
  [rev 2, batch 4]. Generate-code rev 2 withdrew it because a parse-time guess
  would contradict the evaluator's per-form rebind; the owner ruling removes that
  contradiction at the source by making the evaluator *honour* the parse-time
  namespace instead of overriding it. The fact is committed at freeze, and E1's
  evaluated namespace on the receipt is its complement. Freezing it needs the
  events at freeze — see §1.6, which is revised accordingly.
- **If the owner declines E1** (generate-code's v0-B fallback), D2 still lands
  alone; the request keeps the event's parse-time namespace and simply records no
  evaluated-namespace complement. This plan is not blocked on E1, but S3's shape
  is decided by it, so D8/D9 must be ruled before S3 starts.

## 1. The reader contract

### 1.1 Three sentences

`seon.sci.reader/read` takes code-bearing text plus an **explicit reading
context** — starting namespace, aliases, refers, reader features, the accepted
tag map, `#=` policy, size bound — and returns an ordered vector of read events,
each carrying the ordinary parsed form, its exact source text, its character
span and line/column, the namespace in effect for it, and the facts the form
itself declares. Every parameter that changes what SCI accepts is passed to
`sci/parse-next` as opts, which SCI merges last over its ctx-derived defaults,
and the accepted-tag policy is installed as a **total function** so that
Edamame's built-in `#inst`/`#uuid` and the ambient `*read-eval*` are decided
here rather than inherited. Failure is one flat `:seon.error` value and never a
throw; cardinality and emptiness are caller policies over the returned vector,
not different parsers; and prose classification is `seon.cluster.reply`'s policy
applied to the reader's *output*, so the general reader never learns English.

### 1.2 Input **[rev 2 — SB1]**

```clojure
(read {:seon.sci.reader/text     "…"                      ; required
       :seon.sci.reader/ns       'my.agents.alpha         ; ns in effect at offset 0
       :seon.sci.reader/aliases  {'str 'clojure.string}   ; seeds ::kw auto-resolution
       :seon.sci.reader/refers   {'inc 'clojure.core/inc} ; seeds syntax-quote resolution
       :seon.sci.reader/features #{:clj}                  ; reader conditionals
       :seon.sci.reader/tags     {}                       ; ACCEPTED tags; {} accepts none
       :seon.sci.reader/max-chars 1048576})               ; the ONLY parse bound
```

Every key but `text` is optional with a stated default: `ns` defaults to `user`,
`aliases`/`refers`/`tags` to `{}`, `features` to `#{:clj}`, `max-chars` to a
config fact. Absent means absent — no `[:maybe …]`, no stored nil.

**The accepted-tag mechanism, named.** `:seon.sci.reader/tags` is a map from
tag symbol to handler. The reader does **not** hand it to SCI. It installs a
total function:

```clojure
:readers (fn [tag] (or (get tags tag) (refusing-handler tag)))
:read-eval (fn [_] (refusal :seon.sci.reader/refused-tag "#=" …))
```

Rev 1 said "`:readers {}` refuses every tag, everywhere." **That was false and
is deleted.** A `:readers` *map* leaves Edamame's built-in `#inst` and `#uuid`
in place; only a *function* is consulted for every tag (§0.1). `#=` is the same
class: SCI derives `:read-eval` from a dynamic var (`parser.cljc:163-166`), so
the reader passes its own rejecting function unconditionally and inherits no
ambient policy. Both are what "nothing about the reading context is ambient"
actually requires.

**Owner decision D1 restated:** `#inst` and `#uuid` are now a *ruling*, not an
inheritance. Recommendation: **not accepted by default** — an agent that wants
an instant writes a call, and a durable literal that only some readers accept is
exactly the drift this reader exists to delete. Callers that genuinely need them
(a data path, if any survives §3) declare them in `tags`.

Three further notes:

- **`features` is a parameter**, and reading one `.cljc` twice (once per tier)
  is legitimate; reading it twice per consumer is not.
- **Aliases and refers are parameters** because `::kw` auto-resolution and
  syntax-quote resolution otherwise read the ctx's current namespace
  (`parser.cljc:142-153`). A reader that inherits them from whatever ctx it
  happens to hold is the relocation the audit warned about.
- **`max-chars` is the only parse bound and it must exist.** The
  `:interrupt-fn` fires on fn-body entrance during evaluation; the reader never
  enters a fn body, so arming does not bound parsing. Today's
  parse-inside-the-armed-ctx is therefore *already* unbounded — moving it out
  loses nothing, and the size bound is the first real bound parsing has had.

### 1.3 Output — the read event **[rev 2 — R2; `/ns` per the batch-4 ruling]**

```clojure
{:seon.sci.reader/form   (defn f "doc" {:malli/schema […]} [x] …)
 :seon.sci.reader/source "(defn f \"doc\" …)"   ; SCI's trimmed consumed slice
 :seon.sci.reader/start 128 :seon.sci.reader/end 214   ; CONSUMED span, character offsets
 :seon.sci.reader/source-start 130                     ; where ::source begins in the input
 :seon.sci.reader/line 7   :seon.sci.reader/column 1
 :seon.sci.reader/ns     'my.agents.alpha       ; ns in effect — REPL semantics, load-bearing

 ;; declaration facts — present exactly when the form declares them,
 ;; named with the attributes the corpus already registers
 :seon.fn/sym 'my.agents.alpha/f  :seon.fn/doc "doc"
 :seon.fn/arglists "([x])"        :seon.fn/private? true
 :seon.fn/spec "[:=> [:cat :int] :int]"   ; the literal :malli/schema form, verbatim EDN
 :seon.fn/workload :io                    ; the literal leaf annotation
 :seon.ns/name 'my.agents.alpha  :seon.ns/doc "…"  :seon.ns/require-edges [ … ]
 :seon.test/sym 'my.agents.alpha/f-test}
```

**Presence is the state.** There is no `:kind` on an event; a namespace event is
one carrying `:seon.ns/name`, a function event one carrying `:seon.fn/sym`. The
attribute names are the N5 corpus names, so the indexer's projection is
`select-keys` — which is what makes "N5 facts are a pure projection of the read
event" checkable rather than aspirational.

**Spans come from reader cursors.** Rev 1 proposed edamame location metadata
with a buffer-offset fallback. R2 falsified both halves: numbers, strings,
keywords, booleans and nil **cannot carry metadata** and returned nil in the
probe, and `parse-next+string` **trims** its buffer (`sci/core.cljc:387-390`) so
the returned string is not the consumed slice. The mechanism is therefore the
reader's own cursor: take `(get-line-number, get-column-number)` before and
after each `parse-next+string` and resolve both against a line-start index of
the original text. Probed §0.1: consecutive cursor pairs partition the input
exactly, including leading whitespace and comments. This is total for every form
type and it deletes the landed `.indexOf` search, which was the defect either
way.

Three definitions that R2 required and rev 1 blurred:

- `::start`/`::end` are the **consumed span** — a gapless ordered partition of
  the input. Leading trivia belongs to the form that follows it.
- `::source` is SCI's trimmed slice of that span (so a comment immediately above
  a form travels with it — the landed splitter already calls that wanted).
- `::source-start` is where `::source` begins in the input, so a caller can
  recover the trivia as `(subs text start source-start)` without re-scanning.
  Locating the bare form inside `::source` — skipping leading comment lines — is
  `seon.cluster.reply`'s comment-grammar policy, computable from `::source`
  alone.

Offsets are **character offsets** (JVM UTF-16 code units, `subs`-compatible),
and `max-chars` is counted in the same unit. Rev 1's "exact bytes" wording is
deleted; nothing here is byte-addressed.

**`:seon.sci.reader/ns` is REPL semantics, and it is attribution
[rev 2, batch 4].** The owner ruling (§0.3) settles what rev 2's first draft got
backwards: the reader associates each form with the namespace in effect exactly
as a REPL does when `(ns a) (defn x) (ns b) (defn y)` is pasted into it, and
that association is the attribution the generate-code loop reads. It is
committed at freeze as `:seon.cluster.run.form/ns` and complemented — never
replaced — by E1's evaluated namespace on the receipt.

The tracking rule, stated as the sealed property (§4.6):

- the namespace starts at the supplied `ns` and changes at each **top-level**
  `(ns a)` or `(in-ns 'a)`, taking effect for every following top-level form;
- the same forms update the aliases and refers used to read following forms, so
  `::alias/kw` and syntax quote resolve as they would at a REPL;
- a switch the reader **cannot see** — nested inside a `do`, produced by a
  macro, or computed at runtime — yields **absence** on the following events.
  No `/ns` key, no inheritance, no guess (R34). Evaluation is the only thing
  that can know, and E1's receipt is where it is recorded;
- a malformed declaration is the same: absence, not the previous namespace.
  That is the quarry's fencing-safety lesson, kept.

The reader is total for the code agents actually write and explicitly silent
exactly where only evaluation can answer — which is why both facts exist and why
their comparison is a free query rather than a reconciliation.

### 1.4 Error shape, cardinality, and where prose lives **[rev 2 — R1]**

One flat `:seon.error/value`, three kinds, closed:

| kind | when | data |
|---|---|---|
| `:seon.sci.reader/unreadable` | any `:sci.error/parse` — unbalanced, invalid token, bad conditional | the reader's own `:line`/`:column`/`:phase`, plus the offending text |
| `:seon.sci.reader/refused-tag` | `#=`, or a tag outside the declared `tags` map — **including Edamame built-ins** | the tag, named |
| `:seon.sci.reader/oversize` | input exceeds `max-chars` | length and bound |

`::no-forms` is **not** a reader error. An empty read is `[]`. Emptiness is a
caller policy: `reply` refuses a reply with neither forms nor prose, the edit
hook accepts an empty file. "Exactly one form" is likewise a caller assertion
over `(count events)` — which closes the audit's hole where `parse-string` and
the MCP validator each assume the first readable value is the whole input.

**The prose flow, corrected.** Rev 1 said normalization runs *before* reading.
R1 falsified that against the landed splitter, which reads first and classifies
after. The real order, and the order S2 implements:

1. **fence stripping** — line surgery on text, genuinely pre-read, the one idea
   that survives the quarry's parser;
2. **`read`** — the general reader, English-unaware;
3. **reply-policy classification** over the returned events — structured forms
   at line start are code, everything else is prose (`reply.cljc:161-197`);
4. **normalization** — prose gaps rewritten as `;` comments using `::start` /
   `::source-start`;
5. **same-reader re-read** when a reader failure was classified as a prose line
   and commented out (`reply.cljc:227-276`).

The ownership rule is unchanged and is the point: English policy lives in
`seon.cluster.reply`; the reader exposes ordered spans and flat error positions
and learns nothing about prose. What the reader must therefore guarantee is that
step 5's re-read is the *same* reader, so the retry loop cannot drift into a
second grammar.

### 1.5 Who migrates, at what cost, in what order

| # | Site | Migration | Cost |
|---|---|---|---|
| 1 | `seon.cluster.reply/sources` (`reply.cljc:111-319`) | `parsed-events` deleted; the five-step flow of §1.4 over reader events; spans stop being `.indexOf`; **the result becomes plan rows carrying the parse-time namespace** **[rev 2 — R3, batch 4]** | **medium.** No longer a pure substitution: `plan-tx` and `freeze!` move with it (S2's owned paths) |
| 2 | `seon.sci.eval/evaluate` (`eval.clj:292-390`) | takes a read event; `sci/parse-string` deleted; single-form cardinality asserted; **lands together with E1's stateful namespace** (§0.4) | **medium — the riskiest slice.** §1.6, §8 |
| 3 | `program_indexer` ns discovery, ns facts, fn/test scan, Var-metadata lift (four routes, `script/seon/dev/program_indexer.clj:54-90,151-190,204-227,297-308`) | one `read` per file; facts are `select-keys` over events. Deletes the `seon.ns.source` quarry call with its silent empty-requires catch, and deletes the loaded-Var lift | **high, highest payoff.** Fixes a real bug class: the Var lift describes loaded code at a possibly different basis than the source it is filed under, and cannot see `:seon.workload` at all |
| 4 | call/effect analysis (`src-old/seon/program/edge.cljc` via the indexer) | fresh `seon.sci.reader/edges` over a read event + a resolution basis. **Not a port** | **high.** Eight uncertainty members, every one fail-closed |
| 5 | edit-hook syntax validation (`bin/seon-hook:152-165`) | `read`, discard events, keep the error value | **low code, medium blast radius.** The hook accepts any tag and both CLJ/CLJS features today; convergence will newly refuse files the tree tolerates (D4) |
| 6 | changed-test root discovery (`script/seon/dev/test_roots.clj:28-70`) | `read` + `:seon.ns/name`; exceptions become values | **low** |
| 7 | MCP `eval_clj` validation (`script/seon/dev/mcp.clj:332-361`) | `read` + cardinality assert, replacing two core reads. io-prepl still reads on the far side — transport, §3 | **low** |
| 8 | docstring hook (`src-old/seon/dev/docstring.clj` via `bin/seon-hook:294-306`) | deleted; `:seon.fn/doc` + line come from the same events | **medium** — removes the last rewrite-clj dependency from the hook path |
| 9 | changed-test clj-kondo dependency analysis | **not migrated now.** Replaced by N5 namespace facts when they are total | deferred, named |

### 1.6 Parse-once, honestly — what rev 2 gives up **[rev 2 — SB2, R3]**

Rev 1 claimed the fold would use the events from the splitting pass and re-read
only on cold resume. Two falsification findings kill that, and the result is
simpler than the claim it replaces.

**R3: the boundary value is the plan row.** `reply/sources` returns strings and
`run/plan-tx` accepts strings, while freeze must now commit the parse-time
namespace (§0.3). The answer is not to transport read events and not to re-read
at freeze: **`reply/sources` returns the plan rows themselves** —

```clojure
[{:seon.cluster.run.form/source "(ns my.gen.alpha)\n(defn primes …)"
  :seon.cluster.run.form/ns     my.gen.alpha}    ; upserted by name at freeze
 { … }]
```

— which is exactly the value `plan-tx` commits, produced by the one pass that
read the text. Nothing new is invented: the vector of strings becomes a vector
of the rows those strings were always about, and a row whose namespace could not
be determined simply carries no `/ns` key. Cost: `reply/sources`,
`run/plan-tx`, `loop/freeze!` and `reply_test` move together in S2 — named in
S2's owned paths rather than discovered during S3.

**So the hot path reads twice: once to split, once at the fold.** That is a real
weakening of rev 1's wording and it is stated plainly rather than buried. The
ruling that survives:

> **Parse-once means one reader implementation, not one parse per byte.** The
> condensation fails if accepted source reaches a *second Clojure reader*. It
> does not fail when the *same* reader reads a durable source again at a basis
> crossing — the plan is committed between the two reads, and re-derivation from
> facts is the crash model working.

What actually dies is the situation the audit found: a splitter and an evaluator
with *two different acceptance grammars*, one of which silently widens as the
base ctx gains records. After S3 there is one grammar, declared.

**SB2: the fold's reading context.** `::alias/kw` and syntax quote in form N
depend on aliases and refers established by an earlier form. Reading form N from
its source plus a namespace alone can therefore produce a different form or a
refusal. Rev 2 adopts the falsification's recommended construction:

- the fold **reads the ordered plan prefix through the same reader, read-only**,
  accumulating the reading context, and carries that context forward in memory
  for the rest of the pass. Nothing is evaluated; nothing re-executes.
- The prefix read costs one pass per fold entry, not one per form. A cold resume
  at ordinal N pays exactly one prefix read.
- The **starting** context — namespace, features, and the reader-policy version
  the plan was frozen under — becomes **one per-run fact** committed at freeze
  (owner decision D8). Recovery must use the context the plan was frozen under,
  not the current one, or an agent namespace reassignment or a config change
  silently re-reads old text under new rules.

This is a per-**run** reading-context fact, not a per-**form** attribution fact.
It does not resurrect `/ns` and it does not contradict generate-code rev 2,
whose objection was specifically that a parse-time namespace would contradict
the evaluator's own `sci/ns`.

**One pre-existing gap this exposes and does not fix.** Cold resume already
creates a fresh `sci/fork` (`loop.cljc:689-749`), so *evaluation* context —
defs, requires, aliases installed by earlier forms — is already lost today,
independently of reading. Restoring the **reading** context is necessary and not
sufficient: a resumed form that calls a def from an earlier form still fails at
eval time. That is a live defect of the resume path, it predates this plan, and
S3 must **file it rather than appear to fix it**. E1's stateful namespace
narrows it; it does not close it.

### 1.7 Prose-only plan sources **[rev 2 — SB3]**

`7d32ecec5` deliberately preserves pure and trailing prose as a comment-only
plan source, and SCI reads such a source as no form at all. Rev 1 implicitly
chose three incompatible answers at once. Rev 2 chooses one:

> **The reader returns `[]` for comment-only input. The FOLD owns the
> zero-event case:** it reads the plan source, finds no event, and commits the
> terminal receipt directly — without calling `evaluate`.

Why this one:

- **D2 is unviolated.** The evaluator is never handed a synthesized nil event.
  There is no lie about a form that does not exist.
- **No kind label.** The distinguishing fact is ordinary presence: a terminal
  receipt carrying its source and carrying **no** evaluation result. That is the
  same presence-is-the-state rule the receipt already follows.
- **The reader's `[]` contract stays total** — no special empty-input case, no
  cardinality exception.

Behavior change to test at S3: today a comment-only source evaluates `nil` and
the receipt carries a nil result; after S3 the receipt carries no result. The
reply suite asserts *sources*, not receipt shape, so it stays green (S2's
promise holds); the receipt change is S3's own test. This must also compose with
generate-code's E2 — a comment-only source is never red and never stops the fold.
It remains input/source history only: the display emits no output or pseudo-entry
for it. Leading comments attached to a following form remain that form's submitted
source; this display rule does not change the parser.

## 2. Classification at the parse area

### 2.1 Two pure functions, one call site

The reader lifts what a form *declares about itself* — cheap, total, one
inspection of the head and metadata: `:seon.fn/workload`, `:seon.fn/spec`, doc,
arglists, privacy, test identity, namespace and require edges.

Call and uncertainty edges are a **separate pure function**,
`seon.sci.reader/edges`, over a read event plus a resolution input (aliases,
refers, known macro heads at a basis). They are separate because they need an
input the reader does not: resolution. They are in the same area — same
namespace family, same call site in the indexer and at eval — which is what the
ruling asks for. Making the reader itself take a resolution basis would drag
corpus state into file reading and hook validation, which is the complication
the ruling exists to delete.

No lift dial. There is no `:lift #{…}` option selecting which facts to compute;
a dial would be a second parser with extra steps.

### 2.2 The query

`seon.fn/workload` is a Datalog reachability query over `:seon.fn/calls` +
`:seon.fn/workload` at a database basis, memoized per basis — the fold already
probed in `workload-classification-2026-07-28.md:95-148`:

- only `:compute` reachable → `:compute`;
- only `:io` reachable → `:io`;
- both in one chain → `:mixed`;
- any uncertainty or unresolved edge → `:mixed`.

Home: beside the N5 function facts, named for the attribute it queries. **The
reader never owns scheduling vocabulary.**

### 2.3 Pre-N5, when the corpus is empty

The query is **total from day one and returns `:mixed` for every root without
facts** — fail-closed per the ruling, and honest: an empty corpus knows nothing,
and `:mixed` is the fail-closed answer core.async already defines (its own
platform thread — safe, expensive, the incentive to annotate). It is not an
error, not a nil, not an exception. Consumers can therefore ship before N5 and
will simply get the conservative answer until facts exist.

Consumption is at **two seams only**: flow proc workload tags, and the
eval/effect submission door (`seon.flow/submit!!`). Never per frame, never per
call. Migrating execution at every function frame does not exist on the JVM and
would be exactly the complication the owner rejected.

The implicit-`:compute`-for-proven-pure refinement stays an owner decision
(README decision 2, restated as D3) and is inert until edges exist.

## 3. What is NOT condensed

A condensation that complicates two simple things to unify them fails. Each was
judged against that bar and rejected — with the one convention worth sharing
named, since a shared convention is not a shared mechanism.

| Not condensed | Why | Shared convention |
|---|---|---|
| **The EDN schema loader** (`src/seon/schema/edn.clj:125-146`) | Different grammar and a *stronger* trust boundary: `clojure.edn/read` accepts no reader tags and no code. Routing it through an execution reader would mean adding a "data only" dial to make a simple thing reachable from a more powerful one | **prove EOF.** The loader currently accepts a trailing second EDN form silently; it adopts the cardinality rule and the flat error shape. No shared code |
| **The Markdown validator** (`src-old/seon/dev/markdown.clj`) | Entirely different grammar; nothing to share but diagnostics | violation values, same flat error shape |
| **The oracle stack** (`bin/oracle-server:60-505`) | Deliberately *upstream of acceptance*: it reads code that is not yet valid, the opposite of the reader's job. The quarry's 1,517-line parser must not become the core | its **output** must pass `read` before acceptance; repair stays a pure pre-plan transformation, never a mid-fold splice |
| **clj-kondo changed-test analysis** (`script/seon/dev/changed_test.clj:225-252`) | A real semantic analyzer; N5 facts replace its first-party half later, not now | its conservative-on-unavailable behavior is correct and stays |
| **Operator / prepl / server-call transport reads** | EDN protocol data over a trusted channel, not agent code entering the program graph | receiver reads stay strict-cardinality EDN |
| **Ordinary EDN decoders** (config, advertisements, process descriptors, artifacts) | Data codecs; moving them under an execution reader mixes trust with grammar | strict EOF |
| **The web UI's inbound message body** **[rev 2 — N3]** | UI slice 1's boundary "parses and commits" HTTP form data carrying a **human message**. That is model input, not accepted Clojure; sending it through the reader would violate the code-bearing boundary. Slice 3 controls likewise cross as structured function refs and argument data | none. Agent-authored *renderer functions* do reach this reader — through eval/N5, like any other authored code |
| **The delivery half — `admit` and `render` bypasses** | Real condensation, but it is the *serve* wave. CUT FIRST, SEAM-FIX SECOND | §8 S7; the audit's condensation candidates 3 and 4 are its spec |

## 4. Sealed-suite sketch **[rev 2 — every seal criterion has a property]**

One file, `test/seon/sci/reader_test.clj`, plus one repo-wide surface.
**Classes, not cases.**

1. **Round-trip totality (generative, seeded).** Over every `src/**/*.clj[c]`:
   events' `::source` re-reads to an equal form, and consumed spans form a
   **gapless ordered partition** of the input — `(apply str (map slice events))`
   reconstructs the text exactly.
2. **Span totality over atoms [SB/R2].** Numbers, strings, keywords, booleans,
   nil, symbols, and every collection type get non-nil spans. Metadata is never
   the source of a span. Leading comments and whitespace land in the following
   event's consumed span, and `::source-start` recovers the boundary.
3. **The closed refusal set [SB1].** `#=`, an unknown tag, **`#inst`, `#uuid`**,
   and a declared-then-undeclared tag are each refused by name with `tags {}`;
   each is accepted when declared; and the property runs **against a hostile
   ctx** whose own `:readers` and `read-eval` deliberately disagree, proving the
   parameter wins.
4. **Context is a parameter (one class per parameter).** Same text, different
   `:features` → different forms. `::alias/kw` resolves under supplied aliases
   and refuses without them. Syntax quote resolves against the supplied
   `ns`/`refers`, not any ambient ctx.
5. **Refusal totality (generative).** Mutate corpus source — drop a closing
   delimiter, inject `#=`, inject a tag, exceed `max-chars` — and assert a flat
   error every time, never a throw, with a position for `::unreadable`.
6. **Namespace tracking fidelity — first-class, adversarial [rev 2, batch 4].**
   REPL semantics are load-bearing for the generate-code loop, so this is its
   own property class, not a line in another test. The seeded cases:
   `(ns a) (defn x) (ns b) (defn y)` attributes each defn to its own namespace;
   `(in-ns 'a)` behaves identically to `(ns a)`; a namespace declared **after
   prose** in a model reply still takes effect; two top-level forms on one line
   attribute correctly; and each of these is **absent, never inherited or
   guessed** — a switch nested in a `do`, one produced by a macro, one whose
   argument is computed (`(in-ns (symbol s))`), a malformed `(ns)` with no name,
   and a `(ns a)` that appears only inside a comment or a string. The property
   also asserts that aliases and refers follow the same rule, so an
   alias-qualified keyword after a switch reads as it would at a REPL. The
   committed `:seon.cluster.run.form/ns` is asserted to equal the event's
   namespace, and E1's evaluated namespace is asserted to be recorded
   independently — the two are compared, never conflated.
7. **Prose representation [SB3].** Reply's green 7/25 behavior is expressed over
   events: pure prose, trailing prose, mixed prose, and a comment-only plan
   source that the fold terminates without evaluating.
8. **Cold resume [SB2].** A plan whose form N uses an alias-qualified keyword
   and a syntax quote established by form 1 parses **identically** after the
   in-memory context is discarded and the prefix is re-read from the frozen
   starting context.
9. **Fact lift equals the Var lift.** For a sample of real `src/` defns the
   reader's `:seon.fn/*` facts equal what `ns-resolve` + `meta` produce today,
   with intended exceptions enumerated. This is the test that *licenses*
   deleting the Var lift; without it S4 is a leap.
10. **Cardinality.** A two-form text in a one-form request is a caller-side flat
    error, never a silent first-form read.
11. **Classification.** Empty corpus → `:mixed` for every root; seeded facts →
    the four probed answers.
12. **The standing surface: no second reader.** One recurring test asserting
    that `src/`, `script/`, and `bin/` contain no `read-string`, `read`,
    `parse-string`, `parse-forms`, `parse-string-all`, or `tools.reader` call on
    accepted Clojure source outside `seon.sci.reader` — with the §3 exemptions
    named as data, each with its reason. This replaces N point tests with the
    one construction that keeps the class dead.

`test/seon/cluster/reply_test.clj` keeps asserting reply behavior and stops
asserting parse mechanics.

## 5. Name table

| Say | Never | Meaning, and the source on both sides |
|---|---|---|
| `seon.sci.reader/read` | parser, tokenizer, lexer, reply parser | the one accepted-code reader. SCI calls its own the reader (`sci/reader`, `sci/source-reader`, `sci/parse-next`); `read` is Clojure's verb. Requires `(:refer-clojure :exclude [read])` |
| read event | AST node, parse result, token | one `{form, source, consumed span, source-start, line/column, ns-in-effect, declared facts}` value ↔ `sci/parse-next+string`'s `[form source]` plus reader cursors |
| reading context | sandbox, reader config blob, parse opts | the explicit `{ns, aliases, refers, features, tags, max-chars}` parameter ↔ SCI's `:features`, `:auto-resolve`, `:syntax-quote`, `:readers`, `:read-eval` opts (`parser.cljc:142-190`) |
| consumed span | span, extent | the gapless partition unit from reader cursors ↔ `sci/get-line-number` / `get-column-number` |
| accepted tags | reader whitelist, blocklist | the declared `tags` map installed as a total `:readers` function; **built-ins are declared, never inherited** |
| namespace in effect | namespace fence, scope, current-ns | the reader-tracked namespace per form with **REPL semantics** — what a REPL would be in after the preceding top-level forms. Committed as `:seon.cluster.run.form/ns`; its complement is E1's **evaluated namespace** on the receipt (`sci/ns` as the evaluator actually bound it). Two facts, one comparison, never merged |
| `seon.sci.reader/edges` | analyzer, walker, AST pass | calls + uncertainties over a read event ↔ `:seon.fn/calls`, `:seon.fn/uncertainties` |
| `seon.fn/workload` | scheduler, pool selector, dispatcher | the reachability query ↔ `:seon.fn/workload` and core.async's `:io`/`:compute`/`:mixed` |
| prose classification | reply parsing, prose normalizer | `seon.cluster.reply`'s policy applied to the reader's **output** (§1.4) |
| `:seon.sci.reader/unreadable` / `refused-tag` / `oversize` | parse exception, syntax error | the three flat error kinds ↔ SCI's `:type :sci.error/parse` |

The audit's four primitive names hold: `seon.sci.reader/read`,
`seon.fn/workload`, `seon.sci.admit/admit` (landed), `seon.render/render`
(landed). No `codec`, `serve`, `deliver`, or `project` namespace is added.

## 6. Owner decisions

| # | Decision | Recommendation |
|---|---|---|
| D1 **[rev 2]** | The accepted-tag set is **declared**, installed as a total `:readers` function, and covers Edamame's built-ins. Are `#inst`/`#uuid` accepted by default? | **No.** Rev 1's "empty map refuses all" was false (§0.1); now that the set is a ruling, the honest default is the smallest one. Callers that need them declare them |
| D2 **[rev 2, batch 4]** | `evaluate` takes a **read event**, never a bare source string — landing **jointly with generate-code's E1** (§0.4). The event carries the form's parse-time namespace, which the evaluator **establishes** for that form instead of rebinding to the agent namespace | **Yes, jointly.** The ruling makes this free: the namespace the evaluator needs already rides in the event, and the receipt's evaluated namespace becomes a comparison rather than a substitute |
| D3 | Implicit `:compute` for computed-pure functions vs always-explicit metadata (README decision 2) | **Implicit.** Purity is already computed |
| D4 | The edit hook converges on the runtime grammar and may newly refuse tolerated files | **Yes, loudly.** A file the runtime would refuse should not be committable quietly |
| D5 **[rev 2]** | `max-chars` default as a config fact (renamed from `max-bytes`; character offsets throughout) | 1,048,576 characters with a provenance comment; F-wave drives may argue otherwise |
| D6 **[rev 2]** | Prose classification stays in `seon.cluster.reply` and runs on the reader's **output**, not before it (R1) | **Yes** |
| D7 | Which namespace owns the workload query? | **`seon.fn`**, beside the function facts it queries |
| D8 **[rev 2 — new, SB2]** | Freeze the **starting reading context** (namespace, features, reader-policy version) as one per-run fact so recovery re-reads under the rules the plan was frozen under | **Yes.** Without it a namespace reassignment or config change silently re-reads old text under new rules. Per-run, not per-form — it does not resurrect `/ns` |
| D9 **[rev 2 — new]** | If the owner declines E1 (generate-code's v0-B), does D2 still land alone? | **Yes** — the request simply gains no namespace field. But D8/D9 must both be ruled **before S3 starts**, because they decide S3's shape |

## 7. Review points

- **After S1** — contract sealed, suite green, **zero callers migrated**. Review
  the refusal-set property against a hostile ctx, the span property over atoms,
  and the error values. A wrong contract is cheap here and expensive later.
- **After S3** — the second parse is deleted and E1 has landed. Review focused
  `bin/test` green **first**, then a live drive: one real model reply through
  split → freeze → fold, a comment-only source terminating without evaluation, a
  cold resume proving the prefix re-read, and a `kill -9` mid-fold.
- **After S4** — the reader's indexer rows are **diffed against the current
  four-route indexer's rows over the whole tree**. Every difference is either an
  enumerated intended fix (Var-lift basis skew, the silent empty-requires catch,
  `:seon.workload` newly visible) or a defect.
- **After S6** — the workload query answers at two seams with a real corpus.

## 8. Slice order

| Slice | Content | Owned paths | Exit |
|---|---|---|---|
| **S1** | `seon.sci.reader` + `src/seon/schema/reader.edn` + the sealed suite. No callers | `src/seon/sci/reader.clj`, `src/seon/schema/reader.edn`, `test/seon/sci/reader_test.clj` | suite green including the refusal-set and atom-span properties |
| **S2** **[rev 2 — R3, batch 4]** | `reply` migrates to the five-step flow (§1.4); `parsed-events` and the `.indexOf` span deleted; **`sources` returns plan rows carrying the parse-time namespace** and freeze commits them | `src/seon/cluster/reply.cljc`, `src/seon/cluster/run.cljc`, `src/seon/cluster/loop.cljc`, `src/seon/schema/reply.edn`, `src/seon/schema/run.edn`, `test/seon/cluster/reply_test.clj`, `test/seon/cluster/run_test.clj` | the landed 7/25 behavior green in the row shape; the ns-fidelity property (§4.6) green |
| **S3** **[rev 2 — SB4, and joint with E1]** | `evaluate` takes a read event **and** E1's run-stateful namespace lands in the same revision; `sci/parse-string` deleted; the fold owns the zero-event case (§1.7) and the prefix re-read (§1.6); the frozen starting reading context (D8) is committed; the pre-existing cold-resume ctx loss is **filed as an issue, not fixed** | `src/seon/sci/eval.clj`, **`src/seon/schema/eval.edn`**, **`src/seon/schema/run.edn`**, `src/seon/cluster/run.cljc`, `src/seon/cluster/loop.cljc`, **`test/seon/sci/eval_test.clj`**, **`test/seon/cluster/agent_test.clj`**, **`test/seon/cluster/turn_test.clj`** | **focused `bin/test` selectors green FIRST**, then the live drive + comment-only + cold-resume + kill-9 proofs |
| **S4** | Indexer: four routes → `read` + `edges`; `seon.ns.source` and the loaded-Var lift deleted | `script/seon/dev/program_indexer.clj`, `src/seon/sci/reader.clj` | whole-tree row diff reviewed |
| **S5** | Hook, test-roots, MCP, docstring hook; the standing no-second-reader surface goes green | `bin/seon-hook`, `script/seon/dev/test_roots.clj`, `script/seon/dev/mcp.clj` | the surface test passes with its exemption list; focused hook/MCP tests |
| **S6** | `seon.fn/workload` query + the two consumption seams | `src/seon/fn.clj`, `src/seon/flow.clj` | pre-N5 `:mixed`; post-N5 the four answers |
| **S7** | *Separate wave.* Delivery: the boundary admission step, the two message formatters, `render.walk/prose`'s `pr-str` fallback, the data drill's leaves | render family + `seon.context` | its own plan |

S1–S2 may run as one lane. **S3 is a single lane, blocked on D8/D9 being ruled,
and nothing else touches `eval.clj`/`loop.cljc`/`schema/eval.edn` while it
runs** — it is also generate-code v0's E1, so the two plans must not both
dispatch it. S4 and S5 are file-disjoint from S3 and may run in parallel. S6 can
land before N5 facts exist and answer `:mixed`.

## 9. Falsifiers

- **Reader:** accepted source reaches a second Clojure *reader implementation*.
  Re-reading a durable source through the same reader at a basis crossing is not
  a failure (§1.6).
- **Refusal set:** any tag — built-in included — accepted or refused because of
  what the ctx happened to hold rather than what the reading context declared.
- **Spans:** any form type whose span comes from metadata, or a slice recovered
  by searching the input for its own text.
- **Attribution [rev 2, batch 4]:** a form attributed to a namespace the reader
  could not see declared at top level — an inherited previous namespace, a
  prefix guess, or a value reconstructed from anything but the preceding
  top-level forms. Absence is the correct answer there, and E1's evaluated
  namespace is where evaluation-only switches are recorded. Equally a failure:
  the evaluated namespace *overwriting* the parse-time fact instead of standing
  beside it for comparison.
- **Recovery:** a cold resume that reads a form under different declared inputs
  than the freeze did.
- **Classification:** a consumer that stores a workload answer instead of
  querying it at a basis, or a scheduling decision anywhere but the two seams.
- **Condensation bar:** any §3 item that becomes reachable only by adding a mode
  or dial to the reader. If unifying it needs a flag, it was correctly excluded.

## 10. Seal-readiness **[rev 2]**

Against the falsification's seven seal criteria: 1 (refusal property) §4.3;
2 (span property) §4.2; 3 (reply's 7/25 in the event shape) §4.7 + §1.7;
4 (cold-resume property) §4.8 + §1.6; 5 (both plans state the same derivation)
**resolved by the owner's batch-4 ruling, not by withdrawal** — parse-time
namespace is attribution with REPL semantics, E1's evaluated namespace is its
complement, and the two plans now name one mechanism with two facts and a free
comparison (§0.3, §0.4, §1.3); 6 (S3 owns its schema and tests, focused
`bin/test` before the drive) §8; 7 (UI message text stays data) §3.

**This revision does not claim seal.** Two owner decisions gate it — D8 (the
frozen starting reading context) and D9 (E1 joint or D2 alone) — and both change
S3's shape, so S3 must not start before they are ruled. S1 is unaffected by
either and is ready for implementation review now. **S2 is no longer
independent**: the batch-4 ruling moved the plan-row shape and the freeze
commit into it, so S2 and S3 share `run.cljc`, `loop.cljc` and
`schema/run.edn` and must run sequentially in one lane, S2 first.

One coordination duty this revision creates: generate-code v0 is mid-revision
under the same ruling. Its rev 2 killed `:seon.cluster.run.form/ns`; the ruling
restores it. **Neither plan may dispatch S3/E1 until both records agree in
writing** that the parse-time fact is committed at freeze and the evaluated
namespace is recorded on the receipt.
