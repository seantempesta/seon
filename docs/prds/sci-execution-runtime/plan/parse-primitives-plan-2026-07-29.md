---
type: prd
status: active
tags: [prd, runtime, sci, architecture, render]
---

# Parse primitives — one reader, classification at the parse (2026-07-29)

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

## 0. Dependency ledger

| Dependency or mechanism | Selected revision | Contract used here |
|---|---|---|
| SCI | vendored `8fac6e88f32d53a5fd82ebe80640881e317b84fd`; `deps.edn:39-44` | `source-reader` + `parse-next+string` return `[form source-buffer]`; `eval-form` takes an already-parsed form; `parse-string` reads only the next form (`reference-code/sci/src/sci/core.cljc:352-402`) |
| SCI parser | same revision, Edamame underneath | **`parse-next` merges caller `opts` LAST over its ctx-derived options** — `parse-opts (cond-> (assoc default-opts …) opts (merge opts))`, so `:features`, `:auto-resolve`, `:readers`, `:syntax-quote`, `:read-cond` are all supplyable as explicit parameters (`reference-code/sci/src/sci/impl/parser.cljc:142-190`). This is the mechanism that makes the audit's named risk a parameter instead of ambient ctx state |
| SCI default parse opts | same | `{:all true :row-key :line :col-key :column :read-cond :allow :location? seq? :end-location false}` (`parser.cljc:42-50`) — **locations only on seqs, no end locations by default**; symbols get line/column patched by hand in `parse-next` |
| SCI refusal | same | `*read-eval*` defaults false → `#=` throws `EvalReader not allowed…` with `:type :sci.error/parse`; an unknown tag falls through `readers` → `*data-readers*` → record constructors → `*default-data-reader-fn*` and then throws. **Acceptance is a function of the ctx**, which is why `:readers` must be a parameter |
| landed splitter | `7d32ecec5` (`src/seon/cluster/reply.cljc`) | prose→`;` comments attached to the next line-leading form; fence stripping; `parsed-events` (span by `.indexOf`); flat `::unreadable`/`::refused-tag`/`::no-forms`; the prose-failure retry loop |
| quarry comparison | `research/reply-parser-quarry-2026-07-29.md` | prose is comment DATA, not narration attributes; no rewrite-clj, no parinferish, no execution-time repair |
| landed evaluator | `src/seon/sci/eval.clj:292-360` | parses INSIDE the armed ctx today, evaluates, admits, disarms |
| generate-code v0 | `plan/generate-code-v0-plan-2026-07-29.md:120-165` | the per-form **namespace in effect at parse** is the one new fact; `:seon.cluster.run.form/ns` upserted at plan freeze; a form whose namespace cannot be determined gets NO ref (absence, never a guess) |
| classification | `research/workload-classification-2026-07-28.md:71-148` | leaf `^{:seon.workload …}` metadata + reachability over call edges; probed fold; only-compute→`:compute`, only-io→`:io`, both or unresolved→`:mixed` |
| N5 corpus | `research/renderable-corpus-plan-2026-07-28.md:100-200` | the `:seon.fn` row: sym/ns/source/fingerprint/arglists/doc/private?/spec/read-attrs landed, plus `calls`, `uncertainties` (eight members, all fail-closed), `workload`, arity component rows |
| workload consumption seams | `src/seon/flow.clj:386-420`; F3 in `plan/README.md:822-828` | proc workload tags and `submit!!` — two seams, never per frame |
| quarry analyzer | `src-old/seon/program/edge.cljc:346-516` | the shape to learn from (calls, effect, eight uncertainties). **Not to port**: its resolution depends on loaded namespaces and macro inventories |

## 1. The reader contract

### 1.1 Three sentences

`seon.sci.reader/read` takes code-bearing text plus an **explicit reading
context** — starting namespace, aliases, reader features, accepted reader tags,
size bound — and returns an ordered vector of read events, each carrying the
ordinary parsed form, its exact source, its span and line/column, the namespace
in effect for it, and the facts the form itself declares. Every parameter that
changes what SCI accepts is passed through to `sci/parse-next` as opts, which
SCI merges last over its ctx-derived defaults, so **nothing about the reading
context is ambient**. Failure is one flat `:seon.error` value and never a
throw; cardinality ("exactly one form", "read to EOF") is a caller policy over
the returned vector, not a different parser; and prose classification is
model-completion normalization that runs on the text BEFORE reading, owned by
`seon.cluster.reply` and never by the reader.

### 1.2 Input

```clojure
(read {:seon.sci.reader/text     "…"                      ; required
       :seon.sci.reader/ns       'my.agents.alpha         ; ns in effect at offset 0
       :seon.sci.reader/aliases  {'str 'clojure.string}   ; seeds ::kw auto-resolution
       :seon.sci.reader/refers   {'inc 'clojure.core/inc} ; seeds syntax-quote resolution
       :seon.sci.reader/features #{:clj}                  ; reader conditionals
       :seon.sci.reader/readers  {}                       ; accepted tags; {} = refuse all
       :seon.sci.reader/max-bytes 1048576})               ; the ONLY parse bound
```

Every key but `text` is optional with a stated default: `ns` defaults to
`user`, `aliases`/`refers`/`readers` to `{}`, `features` to `#{:clj}`,
`max-bytes` to a config fact. Absent means absent — no `[:maybe …]`, no stored
nil.

Four notes that are the whole point of the contract:

- **`:readers {}` refuses every tag, everywhere.** Today `reply` parses in a
  throwaway `(sci/init {})` while `sci/eval` parses in the armed base ctx —
  two different acceptance surfaces for the same text, and the second one grows
  silently as the base gains records. Making the tag set a parameter with a
  refuse-all default makes the D7 scar unrepresentable at every entry point,
  not just at the one that remembered.
- **`features` is a parameter, and reading one `.cljc` twice (once per tier) is
  legitimate.** Reading it twice per consumer is not.
- **Aliases and refers are parameters** because `::kw` auto-resolution and
  syntax-quote resolution read them from the ctx's current namespace
  (`parser.cljc:142-153` `auto-resolve`, `:syntax-quote {:resolve-symbol
  #(fully-qualify ctx %)}`). Supplying them in opts overrides both. A reader
  that inherits them from whatever ctx it happens to hold is the relocation the
  audit warned about.
- **`max-bytes` is the only parse bound and it must exist.** The
  `:interrupt-fn` fires on fn-body entrance during evaluation; the reader never
  enters a fn body, so arming does not bound parsing. Today's parse-inside-the-
  armed-ctx is therefore *already* unbounded — moving it out loses nothing, and
  the size bound is the first real bound parsing has ever had.

### 1.3 Output — the read event

```clojure
{:seon.sci.reader/form   (defn f "doc" {:malli/schema […]} [x] …)
 :seon.sci.reader/source "(defn f \"doc\" …)"     ; exact bytes, verbatim
 :seon.sci.reader/start 128 :seon.sci.reader/end 214
 :seon.sci.reader/line 7   :seon.sci.reader/column 1
 :seon.sci.reader/ns     'my.agents.alpha          ; ns IN EFFECT for this form

 ;; declaration facts — present exactly when the form declares them,
 ;; named with the attributes the corpus already registers
 :seon.fn/sym 'my.agents.alpha/f  :seon.fn/doc "doc"
 :seon.fn/arglists "([x])"        :seon.fn/private? true
 :seon.fn/spec "[:=> [:cat :int] :int]"   ; the literal :malli/schema form, verbatim EDN
 :seon.fn/workload :io                    ; the literal leaf annotation
 :seon.ns/name 'my.agents.alpha  :seon.ns/doc "…"  :seon.ns/require-edges [ … ]
 :seon.test/sym 'my.agents.alpha/f-test}
```

**Presence is the state.** There is no `:kind` on an event; a namespace event
is one carrying `:seon.ns/name`, a function event one carrying `:seon.fn/sym`.
The attribute names are the N5 corpus names, so the indexer's projection is
`select-keys` — not a translation layer, which is what makes "N5 facts are a
pure projection of the read event" checkable rather than aspirational.

**Spans come from the reader, not from string search.** The landed splitter
recovers a span with `(.indexOf source form-source search-from)` and throws
when the slice is not found verbatim (`reply.cljc:111-133`). Edamame carries
locations; `:end-location true` and a total `:location?` predicate supplied in
opts give end line/column directly. **S1 must probe this before sealing**: SCI
builds `default-opts` through `edamame/normalize-opts` and then merges raw
caller opts over the normalized map, so raw `:location?`/`:end-location` keys
surviving that merge is an assumption, not a read fact. If the probe fails, the
reader keeps a buffer-offset counter over `parse-next+string` — never the
`.indexOf` search, which is the defect either way.

**Namespace in effect** is tracked as the read proceeds: it starts at the
supplied `ns` and changes at each `(ns …)`/`(in-ns 'x)` form, which also
updates the aliases/refers used for the *following* forms. A malformed
declaration yields **absence** of `:seon.sci.reader/ns` on subsequent events —
never inheritance of the previous namespace. That is generate-code v0's fencing
safety restated as a property of the reader, and it is the only place the
quarry's 1,517-line fencing parser survives.

### 1.4 Error shape

One flat `:seon.error/value`, three kinds, closed:

| kind | when | data |
|---|---|---|
| `:seon.sci.reader/unreadable` | any `:sci.error/parse` — unbalanced, invalid token, bad conditional | the reader's own `:line`/`:column`/`:phase`, plus the offending text |
| `:seon.sci.reader/refused-tag` | `#=` or a tag outside the supplied `:readers` | the tag or SCI's message, named |
| `:seon.sci.reader/oversize` | input exceeds `max-bytes` | size and bound |

`::no-forms` is **not** a reader error. An empty read is `[]`. Emptiness is a
caller policy: `reply` refuses it (the agent is told), the edit hook accepts it
(an empty file is valid). Likewise "exactly one form" is a caller assertion
over `(count events)` — which closes the audit's hole where `parse-string` and
the MCP validator each assume the first readable value is the whole input.

Prose classification is **not** in the reader. The landed splitter recognizes
English by matching reader-error messages and retries the substring
(`reply.cljc:227-276`). That is a policy about one model's completions; putting
it in the general reader would make file, hook, indexer, and operator parsing
depend on English heuristics. `reply` keeps `unfenced`, `prose-line`,
`comment-source`, `comment-prose-failure` and its retry loop, and calls `read`
each pass. The reader gains nothing English-aware, ever.

### 1.5 Who migrates, at what cost, in what order

Costs are the audit's, restated per site.

| # | Site | Migration | Cost |
|---|---|---|---|
| 1 | `seon.cluster.reply/sources` (`reply.cljc:111-319`) | `parsed-events` deleted; `unfenced` → prose normalization → `read` → `plan-sources` over events. Spans stop being `.indexOf`. Prose retry re-reads normalized text through the same reader | **low-medium.** Pure substitution; the landed suite is the falsifier |
| 2 | `seon.sci.eval/evaluate` (`eval.clj:300-354`) | accepts a read event; `sci/parse-string` deleted; asserts single-form cardinality; source kept separately for receipts | **medium — the riskiest slice.** §1.6 |
| 3 | `program_indexer` ns discovery, ns facts, fn/test scan, Var-metadata lift (four routes, `script/seon/dev/program_indexer.clj:54-90,151-190,204-227,297-308`) | one `read` per file; facts are `select-keys` over events. Deletes the `seon.ns.source` quarry call and its silent empty-requires catch, and deletes the loaded-Var lift | **high, highest payoff.** Fixes a real bug class: the Var lift describes loaded code at a possibly different basis than the source it is filed under, and it cannot see `:seon.workload` at all |
| 4 | call/effect analysis (`src-old/seon/program/edge.cljc` via the indexer) | fresh `seon.sci.reader/edges` over a read event + a resolution basis. **Not a port** — the quarry resolves through loaded namespaces and macro inventories | **high.** Eight uncertainty members, every one fail-closed |
| 5 | edit-hook syntax validation (`bin/seon-hook:152-165`) | `read`, discard events, keep the error value | **low code, medium blast radius.** The hook currently accepts any reader tag while runtime refuses them; convergence will newly refuse files the tree tolerates today (owner decision D4) |
| 6 | changed-test root discovery (`script/seon/dev/test_roots.clj:28-70`) | `read` + `:seon.ns/name`; exceptions become values | **low** |
| 7 | MCP `eval_clj` validation (`script/seon/dev/mcp.clj:332-361`) | `read` + cardinality assert, replacing two core reads. io-prepl still reads the text on the far side — that is transport, §3 | **low** |
| 8 | docstring hook (`src-old/seon/dev/docstring.clj` via `bin/seon-hook:294-306`) | deleted; `:seon.fn/doc` + line come from the same events | **medium** — removes the last rewrite-clj dependency from the hook path |
| 9 | changed-test clj-kondo dependency analysis | **not migrated now.** Replaced by N5 namespace facts when they are total | deferred, named |

### 1.6 The riskiest point, ruled

The audit names it: parse-once across durable plan facts, evaluation, and
indexing. Here is the honest resolution, because a wrong one relocates the
duplication instead of deleting it.

A parsed form **cannot be a durable fact**. The plan freeze commits ordered
*source strings*; the fold evaluates each at a later basis, possibly in a
different process after a crash. The crash model says nothing re-executes and
everything re-derives from facts. So a design in which the fold depends on
in-memory forms surviving the freeze is wrong by construction.

The transport law already answers this. **The read events ride in memory as
in-flight values** — losable for free, because re-reading the durable source
with the same reader and the same explicit context is deterministic. The fold
uses the events from the same pass when it has them and calls `read` on the
durable source when it does not (resume, recovery, a second process).

Therefore the falsifier is refined, and this is the ruling to review:

> **Parse-once means one reader implementation, not one parse per byte for
> all time.** The condensation fails if accepted source is handed to a *second
> Clojure reader*. It does not fail when the *same* reader re-reads a durable
> source at a basis crossing — that re-read is required by the crash model.

Concretely: `evaluate` takes a read event, never a bare string
(owner decision D2). Everything that evaluates does `read` → `eval-form`. The
resume path is not a second door; it is the same door entered again. What dies
is the *unconditional* second parse inside `evaluate` on the hot path, and with
it the situation where the splitter's acceptance and the evaluator's acceptance
are two different grammars.

Two further constraints fall out and must hold in S3:

- the event carries its exact source, so receipts, digests, and error positions
  keep using text — the durable half is unchanged;
- `evaluate` still runs on a `:compute` platform thread and still arms before
  evaluating; only the parse moves out. Since the interrupt-fn never bounded
  parsing, no bound is lost — `max-bytes` is gained.

## 2. Classification at the parse area

### 2.1 Two pure functions, one call site

The reader lifts what a form *declares about itself* — cheap, total, one
inspection of the head and metadata: `:seon.fn/workload`, `:seon.fn/spec`,
doc, arglists, privacy, test identity, namespace and require edges.

Call and uncertainty edges are a **separate pure function**,
`seon.sci.reader/edges`, over a read event plus a resolution input (aliases,
refers, known macro heads at a basis). They are separate because they need an
input the reader does not: resolution. They are in the same area — same
namespace family, same call site in the indexer and at eval — which is what the
ruling asks for. Making the reader itself take a resolution basis would drag
corpus state into file reading and hook validation, which is the complication
the ruling is trying to delete.

No lift dial. There is no `:lift #{…}` option selecting which facts to
compute; a dial would be a second parser with extra steps.

### 2.2 The query

`seon.fn/workload` is a Datalog reachability query over `:seon.fn/calls` +
`:seon.fn/workload` at a database basis, memoized per basis — the fold already
probed in `workload-classification-2026-07-28.md:95-148`:

- only `:compute` reachable → `:compute`;
- only `:io` reachable → `:io`;
- both in one chain → `:mixed`;
- any uncertainty or unresolved edge → `:mixed`.

Home: beside the N5 function facts, named for the attribute it queries. If the
queued `seon.code.fn` rename lands, namespace and attribute move together. **The
reader never owns scheduling vocabulary.**

### 2.3 Pre-N5, when the corpus is empty

The query is **total from day one and returns `:mixed` for every root without
facts** — fail-closed per the ruling, and honest: an empty corpus knows
nothing, and `:mixed` is the fail-closed answer core.async already defines
(its own platform thread — safe, expensive, the incentive to annotate). It is
not an error, not a nil, not an exception. Consumers can therefore be written
and shipped before N5, and they will simply get the conservative answer until
facts exist.

Consumption is at **two seams only**: flow proc workload tags, and the
eval/effect submission door (`seon.flow/submit!!`). Never per frame, never per
call. Migrating execution at every function frame does not exist on the JVM and
would be exactly the complication the owner rejected.

The implicit-`:compute`-for-proven-pure refinement stays an owner decision
(README decision 2, restated here as D3) and is inert until edges exist.

## 3. What is NOT condensed

A condensation that complicates two simple things to unify them fails. Each of
these was judged against that bar and rejected — with the one convention worth
sharing named, since a shared convention is not a shared mechanism.

| Not condensed | Why | Shared convention |
|---|---|---|
| **The EDN schema loader** (`src/seon/schema/edn.clj:125-146`) | Different grammar and a *stronger* trust boundary: `clojure.edn/read` accepts no reader tags and no code at all. Routing it through an execution reader would mean adding a "data only" mode — a dial — to make a simple thing reachable from a more powerful thing. That is strictly worse than two callers of two correct primitives | **prove EOF.** The loader currently accepts a trailing second EDN form silently; it adopts the reader's cardinality rule (assert exactly one value) and its flat error shape. No shared code |
| **The Markdown validator** (`src-old/seon/dev/markdown.clj`) | Entirely different grammar. There is nothing to share but diagnostics | violation values, same flat error shape |
| **The oracle stack** — structural parse, incomplete-context read, delimiter repair, clj-kondo (`bin/oracle-server:60-505`) | Deliberately *upstream of acceptance*: it reads code that is not yet valid, which is the opposite of the reader's job. The quarry's 1,517-line parser must not become the core (already ruled) | its **output** must pass `read` before anything is accepted; repair stays a pure pre-plan transformation, never a mid-fold splice |
| **clj-kondo changed-test analysis** (`script/seon/dev/changed_test.clj:225-252`) | A real semantic analyzer over the host corpus; N5 namespace facts replace its first-party half later, not now | its conservative-on-unavailable behavior is correct and stays |
| **Operator / prepl / server-call transport reads** (`script/seon/fresh_operator.clj`, `bin/seon-server-call`, io-prepl) | EDN protocol data over a trusted channel, not agent code entering the program graph | receiver reads stay strict-cardinality EDN. A later slice can send the producer's form instead of `pr-str` text; it is not part of this wave |
| **Ordinary EDN decoders** (config, advertisements, process descriptors, artifacts) | Data codecs. Moving them under an execution reader mixes trust with grammar | strict EOF |
| **The delivery half — `admit` and `render` bypasses** | Real condensation, but it is the *serve* wave, not the parse wave. CUT FIRST, SEAM-FIX SECOND: interleaving them makes one seam's perfection gate the next cut | §8 S7 names it; the audit's §"Condensation candidates" 3 and 4 are its spec |

## 4. Sealed-suite sketch

One file, `test/seon/sci/reader_test.clj`, plus one repo-wide surface. **Classes,
not cases** — each entry is the construction that makes a failure class
unrepresentable.

1. **Round-trip totality (generative, seeded).** Over every `src/**/*.clj[c]`:
   reading yields events whose `source` re-reads to an equal form, and whose
   spans are non-overlapping and ordered. This is the property that proves the
   reader can replace the indexer's three reads.
2. **Context is a parameter (one class per parameter).** Same text, different
   `:features` → different forms. `::alias/kw` resolves under supplied
   `aliases` and stays unresolved-and-refused without them. Syntax-quote
   resolves against the supplied `ns`/`refers`, not against any ambient ctx.
   This is the audit's named risk, tested directly.
3. **Refusal totality (generative).** Mutate corpus source — delete one closing
   delimiter, inject `#=`, inject an unknown tag, exceed `max-bytes` — and
   assert a flat `:seon.error` value every time, never a throw, with a position
   present for `::unreadable`.
4. **Namespace in effect.** A multi-namespace text attributes each form; a
   malformed declaration produces **absence** on subsequent events, never
   inheritance.
5. **Fact lift equals the Var lift.** For a sample of real `src/` defns, the
   reader's `:seon.fn/*` facts equal what `ns-resolve` + `meta` produce today,
   with the intended exceptions enumerated. This is the test that *licenses*
   deleting the loaded-Var lift; without it S4 is a leap.
6. **Cardinality.** A two-form text in a one-form request is a caller-side flat
   error, never a silent first-form read (the MCP/`parse-string` hole).
7. **Classification.** Empty corpus → `:mixed` for every root. Seeded facts →
   the four probed answers (`:io`, `:compute`, `:mixed` by mixture, `:mixed` by
   uncertainty).
8. **The standing surface: no second reader.** One recurring test asserting
   that `src/`, `script/`, and `bin/` contain no `read-string`, `read`,
   `parse-string`, `parse-forms`, `parse-string-all`, or `tools.reader` call on
   accepted Clojure source outside `seon.sci.reader` — with the §3 exemptions
   named as data in the test, each with its reason. This replaces N point tests
   with the one construction that keeps the class dead, and it is what makes
   "the condensation is complete" a query rather than an opinion.

The landed `test/seon/cluster/reply_test.clj` keeps asserting reply behavior
(prose, fences, mixed replies) and stops asserting parse mechanics.

## 5. Name table

Grounded on both sides, per the vocabulary rule. Proposed rows for the
maintained table in `CLAUDE.md` after owner review.

| Say | Never | Meaning, and the source on both sides |
|---|---|---|
| `seon.sci.reader/read` | parser, tokenizer, lexer, reply parser | the one accepted-code reader. SCI calls its own the reader (`sci/reader`, `sci/source-reader`, `sci/parse-next`); `read` is Clojure's verb. Requires `(:refer-clojure :exclude [read])` |
| read event | AST node, parse result, token | one `{form, source, span, line/column, ns-in-effect, declared facts}` value ↔ `sci/parse-next+string`'s `[form source]` plus edamame locations |
| reading context | sandbox, reader config blob, parse opts | the explicit `{ns, aliases, refers, features, readers, max-bytes}` parameter ↔ SCI's `:features`, `:auto-resolve`, `:syntax-quote`, `:readers` opts (`parser.cljc:142-190`) |
| namespace in effect | namespace fence, scope, current-ns | the reader-tracked namespace per form ↔ `:seon.cluster.run.form/ns` at plan freeze |
| `seon.sci.reader/edges` | analyzer, walker, AST pass | calls + uncertainties over a read event ↔ `:seon.fn/calls`, `:seon.fn/uncertainties` |
| `seon.fn/workload` | scheduler, pool selector, dispatcher | the reachability query ↔ `:seon.fn/workload` and core.async's `:io`/`:compute`/`:mixed` |
| prose normalization | reply parsing, prose classifier | `seon.cluster.reply`'s model-completion policy applied to text BEFORE reading |
| `:seon.sci.reader/unreadable` / `refused-tag` / `oversize` | parse exception, syntax error | the three flat error kinds ↔ SCI's `:type :sci.error/parse` |

The audit's four primitive names hold: `seon.sci.reader/read`,
`seon.fn/workload`, `seon.sci.admit/admit` (landed), `seon.render/render`
(landed). No `codec`, `serve`, `deliver`, or `project` namespace is added.

## 6. Owner decisions

| # | Decision | Recommendation |
|---|---|---|
| D1 | `:readers {}` — refuse every reader tag by default at every entry point, including the evaluator, which today parses in the armed base ctx and would accept whatever that ctx accepts | **Yes.** Uniform acceptance is the D7 scar made unrepresentable; the base ctx growing a record type must not silently widen what agent text may contain |
| D2 | `evaluate` takes a **read event**, never a bare source string; resume re-reads the durable source through the same reader (§1.6) | **Yes.** One reader, one door entered twice; the alternative is either two acceptance grammars or a durable parsed form the crash model forbids |
| D3 | Implicit `:compute` for computed-pure functions vs always-explicit metadata (README decision 2, landing here) | **Implicit.** Purity is already computed; explicit tags stay for genuinely ambiguous leaves |
| D4 | The edit hook converges on the runtime grammar, which may newly refuse files the tree currently tolerates | **Yes, loudly.** A file the runtime would refuse should not be committable quietly |
| D5 | `max-bytes` default as a config fact | Propose 1 MiB with a provenance comment; F-wave drives may argue otherwise with evidence |
| D6 | Prose normalization stays in `seon.cluster.reply`; the reader is never English-aware | **Yes** |
| D7 | Land as `seon.fn` now, or wait for the queued `seon.code.fn` rename | **Land as `seon.fn`**, matching the attributes that exist; move with the rename when it comes |

## 7. Review points

- **After S1** — contract sealed, suite green, **zero callers migrated**. Review
  the reader in isolation: the location probe result, the refusal set, the
  error values. This is where a wrong contract is cheap.
- **After S3** — the second parse is deleted. Review a live drive: one real
  model reply through split → freeze → fold, plus a `kill -9` mid-fold and a
  resume that re-reads the durable source. This is the riskiest slice's proof.
- **After S4** — the indexer's rows produced by the reader are **diffed against
  the rows the current four-route indexer produces over the whole tree**. Every
  difference is either an enumerated intended fix (the Var-lift basis skew,
  the silent empty-requires catch, `:seon.workload` newly visible) or a defect.
- **After S6** — the workload query answers at two seams with a real corpus.

## 8. Slice order

| Slice | Content | Owned paths | Exit |
|---|---|---|---|
| **S1** | `seon.sci.reader` + `src/seon/schema/reader.edn` + the sealed suite. No callers. Includes the edamame location probe | `src/seon/sci/reader.clj`, `src/seon/schema/reader.edn`, `test/seon/sci/reader_test.clj` | suite green; probe result recorded in this plan |
| **S2** | `reply` migrates: `parsed-events` deleted, spans from the reader, prose policy unchanged | `src/seon/cluster/reply.cljc`, `test/seon/cluster/reply_test.clj` | landed reply suite green unchanged |
| **S3** | **riskiest.** `evaluate` takes a read event; `sci/parse-string` deleted; the fold carries events and re-reads on resume; `:seon.cluster.run.form/ns` lands at freeze (generate-code v0's one fact) | `src/seon/sci/eval.clj`, `src/seon/cluster/run.cljc`, `src/seon/cluster/loop.cljc`, `src/seon/schema/run.edn` | live drive + kill-9 resume proof |
| **S4** | Indexer: four routes → `read` + `edges`; `seon.ns.source` and the loaded-Var lift deleted | `script/seon/dev/program_indexer.clj`, `src/seon/sci/reader.clj` | whole-tree row diff reviewed |
| **S5** | Hook, test-roots, MCP, docstring hook; the standing no-second-reader surface goes green | `bin/seon-hook`, `script/seon/dev/test_roots.clj`, `script/seon/dev/mcp.clj` | the surface test passes with its exemption list |
| **S6** | `seon.fn/workload` query + the two consumption seams | `src/seon/fn.clj`, `src/seon/flow.clj` | pre-N5 `:mixed`; post-N5 the four answers |
| **S7** | *Separate wave, not interleaved.* Delivery: the boundary admission step, the two message formatters, `render.walk/prose`'s `pr-str` fallback, the data drill's leaves | render family + `seon.context` | its own plan |

S1–S2 may run as one lane. S3 is a single lane and nothing else touches
`eval.clj`/`loop.cljc` while it does. S4 and S5 are file-disjoint from S3 and
may run in parallel with it. S6 waits on N5 facts to be *useful* but not to be
*correct* — it can land before them and answer `:mixed`.

## 9. Falsifiers

- **Reader:** accepted source is handed to a second Clojure *reader
  implementation* after `read` returned its events. Re-reading a durable source
  through the same reader at a basis crossing is not a failure (§1.6).
- **Context:** any acceptance difference between two entry points reading the
  same text with the same declared context — differing tags, features, or
  auto-resolution — means the context is still partly ambient.
- **Classification:** a consumer that stores a workload answer instead of
  querying it at a basis, or a scheduling decision made anywhere but the two
  seams.
- **Condensation bar:** any §3 item that becomes reachable only by adding a mode
  or dial to the reader. If unifying it needs a flag, it was correctly excluded.
