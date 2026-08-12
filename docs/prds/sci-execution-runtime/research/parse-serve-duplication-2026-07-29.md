---
type: research
status: complete
tags: [research, runtime, render]
---

# Parse and serve duplication in the fresh tree

## Verdict

The fresh tree does not have a reply-parser problem. It has eleven accepted
Clojure read routes in eight entry points, with the same source commonly read
twice and the build corpus read three times before metadata is taken from a
loaded host Var. The current model-reply path is the shortest proof:
`seon.cluster.reply/sources` uses SCI to split text into source strings, commits
those strings, and `seon.sci.eval/evaluate` asks SCI to parse each string again
(`src/seon/cluster/reply.cljc:111-133,282-319`;
`src/seon/cluster/loop.cljc:530-552,730-754`;
`src/seon/sci/eval.clj:300-354`).

Serving is closer to the target but still forks around its primitives.
`seon.render/render` is the one generic projection router and
`seon.sci.admit/admit` is the one bounded ordinary-value codec. Prompt and web
rendering use the router, but trigger messages, the root message list, render
walk's non-string fallback, and the data drill each reproduce part of
"project a value for this consumer." Seven explicit `admit` calls live in five
owners, while five consumer owners still serialize or interpolate values
outside one general admission step.

The condensation is therefore four primitives, not one large parser/renderer:

1. `seon.sci.reader/read` — code-bearing text to ordered
   `{form, source, span, lifted facts}` values or one flat error.
2. `seon.fn/workload` — a query over function call, uncertainty, effect, and
   leaf-workload facts to `:io`, `:compute`, or `:mixed`.
3. `seon.sci.admit/admit` — the existing ordinary-value projection and bounds
   owner, used once at every value boundary.
4. `seon.render/render` — the existing unit-plus-kind projection motion;
   consumers reduce or serialize its admitted result instead of re-projecting.

The riskiest cut is passing one SCI-read form from admission through durable
plan facts into evaluation and corpus indexing. SCI parsing is contextual:
reader conditionals, auto-resolved keywords, syntax quote, aliases, and custom
readers depend on the supplied context
(`reference-code/sci/src/sci/impl/parser.cljc:42-50,142-190`). Parse-once is
correct only if the reader's context is explicit and its ordinary parsed form
is the durable/trusted value; shipping an opaque SCI context or silently
re-reading source at the far end would merely relocate the duplication.

## Question, scope, and counting rule

Owner directive, recorded in the active plan at `f6797cee7`:

> the eval parse is NOT a "reply parser"

The requested primitive must read code wherever code arrives, lift the facts
needed to classify it for the existing scheduled lanes, and let evaluation,
the program graph, tests, hooks, and delivery boundaries consume those same
values.

This audit read every match for `read`, `read-string`, `read+string`,
`edamame`, `tools.reader`, the SCI reader, rewrite-clj, clj-kondo, and
code-producing `pr-str` under `src/`, `bin/`, and `script/`. Fresh entry points
that still load a quarry namespace through `bb.edn` count because they are
live fresh-tree callers. Ordinary EDN state/config/protocol decoders are listed
only where they establish a boundary; they are not counted as Clojure code
readers.

The counts below count independently maintained motions, not textual call
expressions:

| Group | Duplicate or bypass count | Meaning |
|---|---:|---|
| accepted Clojure reading | 11 routes in 8 entry points | source intended for execution, indexing, validation, or source metadata is independently read |
| specialist parsing | 6 routes | incomplete-code repair/analyzers, schema EDN, and Markdown have different grammars and should not be forced through the execution reader |
| classification/fact lifting | 3 passes plus 1 missing query | namespace facts, loaded-Var metadata, and call/effect facts are separate; workload propagation is not implemented |
| admission | 7 calls in 5 owners; 5 bypass owners | the codec exists, but boundary callers repeat its request setup and several serializers never call it |
| delivery | 3 parallel message/value formatters beside the router | trigger prose, root message HTML, and render-walk fallback repeat projections already owned by message or render families |

The 11 accepted routes include the receiver-side reads of generated remote
forms. The six specialist routes count oracle structural parsing, oracle
context reading, oracle delimiter repair, clj-kondo analysis, schema EDN, and
Markdown parsing. The rewrite-clj docstring pass is counted among the eleven
because it reads accepted source solely to lift a fact N5 already needs.

## Dependency ledger

| Dependency or mechanism | Selected revision or version | Source-grounded contract used here |
|---|---|---|
| SCI | vendored `8fac6e88f32d`; `deps.edn:39-44` | `source-reader` plus `parse-next+string` return a parsed form and buffered source; `parse-string` reads only the next form; `eval-form` accepts an already parsed form (`reference-code/sci/src/sci/core.cljc:352-402`) |
| SCI parser substrate | SCI revision above, Edamame underneath | default options enable locations and reader conditionals; context supplies features, readers, auto-resolution, and syntax-quote resolution; parse failures carry `:type :sci.error/parse`, phase, file, line, and column (`reference-code/sci/src/sci/impl/parser.cljc:42-50,142-190`) |
| Clojure | `1.12.5`; `deps.edn:12` | `read`, `read+string`, `clojure.edn/read`, and receiver-side REPL readers currently form separate paths |
| rewrite-clj | vendored `60782e501aaf312cb90c9ff0bee05d5da5125563` in the quarry alias | preserves syntax nodes and source for the old parser and current docstring hook; it is not the accepted execution reader |
| Datahike | vendored `9a7a9ef10a95`; `deps.edn:18-22` | the N5 parse result becomes `:seon.fn`, `:seon.ns`, `:seon.schema`, and `:seon.test` facts; workload is derived at a database basis |
| workload ruling | `research/workload-classification-2026-07-28.md:71-148` | lift optional `^{:seon.workload :io\|:compute}` at the leaf; derive classification by reachability over calls, effects, and uncertainties |
| N5 corpus target | `research/renderable-corpus-plan-2026-07-28.md:127-161` | functions require calls, uncertainties, workload, arities, doc, arglists, source, privacy, and contract facts |
| current reply cut | base commit `2a49cbd75`; current in-flight working copy read on 2026-07-29 | current copy retries SCI parsing after rewriting prose as comments; it remains protected and was not edited (`src/seon/cluster/reply.cljc:64-100,111-319`) |
| fresh script classpath | `bb.edn:1` | `script`, `src`, `src-old`, and `test` are all visible, so fresh scripts can accidentally retain quarry parser owners |

## Parsing inventory

### Accepted and runnable Clojure

| Site | Reader and disposition of forms | Lifted metadata/facts | Error shape | Condensation finding |
|---|---|---|---|---|
| model completion to plan | SCI `source-reader` and `parse-next+string` in a throwaway context (`src/seon/cluster/reply.cljc:111-133,282-319`) | retains source slices and computed character spans; lifts no doc, schema, call, or workload fact | catches all failures and returns a flat `:seon.error`; tag refusal is recognized by matching SCI messages | first parse of the same plan form; current in-flight prose recovery can invoke `parsed-events` repeatedly (`:235-276`) |
| plan source to eval | SCI `parse-string` then `eval-form` inside the armed per-run context (`src/seon/sci/eval.clj:300-354`) | none; the form is immediately evaluated and its result admitted | all throwables become a flat error value and an eval record; admission is repeated for success and failure | second parse; `parse-string` reads one form and does not itself prove EOF, relying on the splitter's cardinality |
| indexer namespace discovery | core `read`, `*read-eval* false`, `:read-cond :allow`, `#{:clj}` until `ns` (`script/seon/dev/program_indexer.clj:54-67`) | namespace symbol only | throws to the build caller; missing `ns` gets an `ex-info` at `:80-84` | first read of each indexed source |
| indexer namespace facts | `clojure.tools.reader/read-string` through quarry `seon.ns.source` (`src-old/seon/ns/source.cljc:53-113`), called at `script/seon/dev/program_indexer.clj:75-90,160` | namespace doc, first-line summary, require targets, aliases, refers, and refer-all | catches every failure and silently returns empty require edges | second read; silence can convert an unreadable namespace into a dependency-free one |
| indexer function/test scan | core `read+string`, `*read-eval* false`, `:read-cond :allow`, `#{:clj}` (`script/seon/dev/program_indexer.clj:151-190`) | preserves one source slice; selects literal `defn`/`defn-` and `deftest` heads | reader/build exception throws | third read; the already parsed namespace form is not reused |
| indexer metadata lift | not a text reader: `ns-resolve` and host Var `meta` after the namespace was loaded (`script/seon/dev/program_indexer.clj:204-227,297-308`) | `:malli/schema`, doc, arglists, private, and capability effect; **not** `:seon.workload` | malformed Malli schema is caught and silently omitted (`:204-209`) | fact lift is detached from the parsed source and can describe loaded code at a different source/basis |
| indexer call/effect analysis | quarry `seon.program.edge/analyze-function` walks the parsed form (`script/seon/dev/program_indexer.clj:331-366`; `src-old/seon/program/edge.cljc:346-516`) | calls, read/write attributes, effects, terminal requests, and eight uncertainty cases | uncertainties are facts; unexpected analyzer failures escape the build | the right general shape, but still a quarry owner and not connected to a workload query |
| edit hook syntax validation | Edamame `parse-string-all`, permissive readers, both `:clj` and `:cljs` features (`bin/seon-hook:152-165`) | discards every form; returns only validity | pre-edit blocks with a string; post-edit is advisory (`bin/seon-hook:192-225,702-745`) | an entire parse is paid only to throw its result away before changed-test/indexing parses again |
| changed-test root discovery | core `read` until `ns`, selected separately for CLJ/CLJS (`script/seon/dev/test_roots.clj:28-70`) | namespace and require targets | exceptions escape; missing/duplicate namespaces are later `ex-info` values | repeats namespace parsing and may parse one test file more than once |
| changed-test dependency analysis | external clj-kondo parses the host corpus and emits EDN (`script/seon/dev/changed_test.clj:225-252`) | namespace definitions/usages used for reverse closure | unavailable or malformed analysis becomes an explicit unavailable map and selects conservatively | semantic analyzer remains useful, but N5 namespace/call facts can replace its first-party dependency inventory |
| MCP `eval_clj` validation | core `read` twice with `*read-eval* false` (`script/seon/dev/mcp.clj:332-361`) | none; returns the original string | throws categorized `ex-info`, converted to an MCP error map at `:410-462` | validates one form, then io-prepl reads the unchanged text again |
| generated operator forms | syntax-quoted ordinary forms are `pr-str`'d for `clojure -e` or io-prepl (`script/seon/fresh_operator.clj:181-219,225-275,284-300,428-435`) | none | remote events are EDN; exceptional returns call `fail!` | the producer already owns a form value, converts it to opaque text, and delegates parsing to the receiver |
| raw server-call expression | `bin/seon-server-call` sends caller text directly to a socket REPL (`bin/seon-server-call:88-117,152-155`) | none | transport exceptions or an unrecognized final result exit nonzero; returned events/data are EDN (`:131-146`) | another receiver-side Clojure reader with neither the general admission rule nor parse facts |
| docstring hook | rewrite-clj `parse-string-all`, then syntax-node inspection (`src-old/seon/dev/docstring.clj:192-235,305-335`), loaded by `bin/seon-hook:294-306` | namespace, public function name, source line, and docstring only | parse failure becomes clean/skipped with no diagnostic | a fourth source fact lift; N5 needs the same doc/name/line from the accepted reader |

### Specialist and code-like readers

These sites should not all call SCI directly. They establish the line between
one accepted-code reader and consumers whose input is intentionally not
accepted runnable code.

| Site | Reader and use | Metadata | Error shape | Relation to the core reader |
|---|---|---|---|---|
| schema resource loader | `clojure.edn/read` from a `PushbackReader`; requires a map (`src/seon/schema/edn.clj:125-146`) | schema keys and literal Malli forms are later contributed; no code metadata | throws `ex-info` with `:user-input` and file | keep a strict EDN-map policy over a shared low-level read primitive if useful; do not allow reply fences, symbols as prose, or execution readers. It currently does not reject a trailing second EDN form |
| old structural/oracle parser | rewrite-clj token/source parser `seon.repl.parse/parse-forms`, called by `bin/oracle-server:60-120,481-505` and `bin/test-parser` | form/source/span/narration plus a namespace projection | read failures are in-band entries with classified spans | draft/oracle consumer only; accepted output must still pass the SCI reader. Its 1,517-line parser must not become the new core |
| oracle context read | rewritten incomplete map/set text then core `read-string` (`bin/oracle-server:187-203`) | cursor context only | returns nil | deliberate incomplete-code heuristic, never an accepted form |
| oracle delimiter repair | Edamame parse plus a custom delimiter scanner (`bin/oracle-server:304-355`) | repaired draft and append count | in-band clean/error map | deliberate repair upstream of acceptance; not evaluation semantics |
| oracle semantic analysis | clj-kondo pod over a temporary repaired draft (`bin/oracle-server:357-415`) | locals/usages for cursor suggestions | nil/degraded when unavailable | keep as editor intelligence until N5 facts can answer the accepted-source portion |
| Markdown validator | hand-written frontmatter, fence, heading, link, and section scans (`src-old/seon/dev/markdown.clj:191-313,921-969`), loaded by `bin/seon-hook:263-284` | Markdown structure and validation violations | tolerant parser; validation returns violations and the hook may auto-fix | different grammar; share diagnostic conventions, not the SCI reader |

### Deliberate non-code decoders

The remaining `edn/read-string` matches in fresh source decode manifests,
advertisements, process descriptors, cursors, schema strings, transaction
artifacts, and io-prepl events. Examples include `src/seon/config.cljc`,
`src/seon/cluster.clj:1217-1260`, `src/seon/render/data.clj:52`,
`script/seon/dev/process.clj:184`, and
`script/seon/dev/artifact.clj:300-778`. They are ordinary data codecs, not
program reads. Moving them under an execution reader would mix trust and
grammar boundaries. The one useful shared constraint is strict cardinality:
data files and single-form requests should explicitly prove EOF rather than
assuming that the first readable value is the whole input.

`script/seon/dev/artifact.clj` also constructs a JavaScript `-e` expression for
Bun identity. It is foreign generated code, not a Clojure program-graph input,
and is outside the SCI condensation.

## Serving and delivery inventory

### The landed primitives

`seon.render/render` already owns late resolution and invocation of a unit's
declared boundary projection. It accepts literal projections, invokes a
qualified Var, and returns either `{kind, output}` or a flat error
(`src/seon/render.clj:117-174`). This is the general delivery motion the owner
asked for; no second `deliver`, `serve`, or renderer registry is needed.

`seon.sci.admit/admit` already realizes, bounds, converts, and prints ordinary
values. The seven calls are:

- eval success and eval failure (`src/seon/sci/eval.clj:346-354,363-372`);
- prompt text bounding (`src/seon/cluster/prompt.cljc:62-73`);
- core-error source normalization (`src/seon/error.clj:287-325`);
- instrumentation failure values (`src/seon/instrument.clj:140-162`); and
- generic HTML and AI data floors (`src/seon/render/block.clj:925-975,999-1035`).

Those seven calls repeat the same unarmed `(fn [])`, cap threading, mode, and
record extraction in five owners. That is call-shape duplication around the
right codec, not evidence for another codec.

### Consumer motions and bypasses

| Consumer boundary | Current value motion | What is shared | Duplication or bypass |
|---|---|---|---|
| prompt reduction | block membership to unit, `render/render` as `:seon.render/ai`, validate text/error/nil, `admit`, join contributions (`src/seon/cluster/prompt.cljc:133-207`) | correct router and codec | reduction is valid consumer work; the repeated admission request should become the standard post-render boundary step |
| web surfaces | block to unit, `render/render` as HTML, Hiccup validation, surface, expand, serialize, equality suppress (`src/seon/render/block.clj:365-429`; `src/seon/render/web.clj:174-230,307-375`) | correct router and kind grammar | generic surface serialization has no general admission/bounds pass; specialist projections can return unbounded strings/collections |
| durable message delivery | admitted `my.message` values become transaction rows (`src/seon/cluster/message.cljc:201-290`) | correctly commits facts, not a render | no duplicate renderer here; input is already admitted by eval and delivery should remain a fact transformation |
| message family projection | message facts to AI or HTML (`src/seon/cluster/message.cljc:296-339`; declarations in `src/seon/schema/message.edn:36-44`) | intended one family owner | two live consumers bypass it |
| trigger prompt block | queries message content/from and hand-builds sender prose (`src/seon/context.clj:114-147`) | none of the message render | duplicates message AI projection and its sender semantics |
| root message list | queries message content/to and hand-builds Hiccup (`src/seon/render/root.clj:124-154`) | ordinary root block shell only | duplicates message HTML projection and omits the family projection's `from` semantics |
| neighbourhood prose | routes each node through `render/render`, then raw `pr-str` for any non-string output (`src/seon/render/walk.clj:382-421,427-460`) | correct router | bypasses both boundary grammar and admission for the fallback |
| data drill | pages collections, but summaries, path steps, and scalar leaves use raw `pr-str` (`src/seon/render/data.clj:150-164,170-221`) | collection window is bounded | scalar/string output does not pass through admission; caps are present but not applied to leaves |
| generic data floors | select a value, call `admit`, then produce Hiccup or prose (`src/seon/render/block.clj:925-975,999-1035`) | correct codec | two nearly identical value-selection and admission preparations should be one boundary helper or one shared pure selection, not two codecs |
| error render/log | normalizes source through `admit`, then routes the error unit through `render/render` (`src/seon/error.clj:287-325,461`) | correct | later `pr-str` in log lines describes already admitted stored fields; it is serialization, not a codec bypass |
| operator/prepl transport | generated forms and events cross as text/EDN (`script/seon/fresh_operator.clj:181-219,242-275`; `script/seon/dev/mcp.clj:373-462`) | protocol data | trusted operator transport is not an agent render, but it still benefits from the one accepted-code parse result before transport |

The five admission-bypass owners counted in the summary are web surface
serialization, trigger prompt formatting, root message formatting,
neighbourhood fallback, and data drill. The two direct message formatters and
the neighbourhood fallback are the three parallel delivery motions beside
`seon.render/render`; web and data are codec/bounds bypasses but still use the
generic render topology.

## Condensation candidates

### One reader: `seon.sci.reader/read`

**What exists.** SCI already exposes the required substrate:
`source-reader`, `parse-next+string`, and `eval-form` over the returned
ordinary form (`reference-code/sci/src/sci/core.cljc:364-402`). Its parser
already carries location metadata and flatly identifiable parse data. The
current reply splitter proves source slicing, refused `#=`, unknown-tag
refusal, and bounded failure. The N3 plan already ruled "parser shed to SCI
reader."

**Who duplicates it.** Reply split, eval, three indexer reads, edit-hook
syntax validation, test-root discovery, MCP validation plus io-prepl, operator
and socket-REPL receiver reads, and rewrite-clj doc extraction all read
accepted code independently.

**Home and contract.** The home should be `seon.sci.reader`, because the
accepted syntax and trust semantics are SCI's, and SCI itself calls the
mechanism a reader. `read` should return an ordered vector of open event maps
containing the ordinary form, exact source, start/end position, reader
location, and lifted facts, or one registered flat error value. Cardinality
(`one`, `all`, `one map`) is an explicit caller policy over that result, not a
different parser.

The reply namespace may normalize Markdown fences before calling it, but prose
classification must not be embedded in the core reader. The current in-flight
`cluster/reply.cljc` recognizes English by reader-error message regexes and
retries substrings (`:227-276,297-313`). That is a model-completion
normalization policy, not Clojure reading, and putting it in the general reader
would make file, hook, and operator parsing depend on English heuristics.

**Cost and breakage.**

- `seon.sci.eval/evaluate` must accept the already parsed form while retaining
  source separately for receipts and errors. If it calls `parse-string`, the
  condensation has failed.
- A durable parsed form must have a schema-projected ordinary representation
  acceptable to the transaction boundary, or parsing must occur at the single
  durable-admission owner immediately before the transaction. An SCI context
  object cannot become a fact.
- Reader context must evolve without evaluation. Namespace declarations and
  require aliases affect `::keywords` and syntax quote; a throwaway empty
  context is not sufficient for arbitrary corpus files.
- CLJC has two projections. The reader must preserve original source while
  producing the selected `:clj` form and facts once; parsing once per target
  tier is legitimate, parsing repeatedly per consumer is not.
- Hook validation currently accepts any reader tag while runtime SCI refuses
  unknown tags. Convergence will intentionally make the hook enforce the same
  accepted grammar, which may expose currently tolerated files.
- Schema EDN should share only strict reading/cardinality/error helpers, not
  executable reader tags or reply normalization.

### One classification query: `seon.fn/workload`

**What exists.** The quarry analyzer already emits direct calls and
uncertainties (`src-old/seon/program/edge.cljc:480-516`), the indexer already
lifts selected metadata, and the settled rule is a reachability query:
only-compute to `:compute`, only-io to `:io`, both or unresolved to `:mixed`;
proven pure is implicit compute
(`research/workload-classification-2026-07-28.md:95-148`).

**Who duplicates or omits it.** `seon.ns.source` derives lexical edges, the
indexer reads loaded Var metadata, and `seon.program.edge` derives calls and
effects in separate passes. No fresh owner lifts `:seon.workload`, and no
fresh query propagates it. Flow proc declarations therefore remain hand-set
at their call sites even where N5 facts could prove the answer.

**Home and contract.** Put the public query beside the N5 function facts:
`seon.fn/workload` over a database value and root function symbol. That name is
grounded in `:seon.fn/workload` and the current N5 ledger. If the queued
program-graph attribute rename to `seon.code.fn` lands, the namespace and
attribute move together; the parser must not own scheduling vocabulary.

The reader's role is limited but simultaneous: lift literal
`:seon.workload`, direct calls, uncertainties, and effect evidence from the
same parsed form it supplies to eval/indexing. The database query derives the
transitive answer at a basis. This satisfies "detection in the same area"
without asking a text parser to choose an executor.

**Cost and breakage.**

- N5 must land direct call and uncertainty facts before the query can be
  total. Unknown/dynamic calls must fail closed to `:mixed`.
- The quarry edge walker cannot simply be copied. Its source-resolution rules
  depend on loaded namespaces and macro inventories
  (`script/seon/dev/program_indexer.clj:263-320`); the fresh reader must produce
  explicit lexical facts or record uncertainty.
- Classification changes proc-workload evidence and `plan-execution`; it must
  not migrate execution at every function frame. Scheduling stays at Flow
  proc and effect submission seams.
- Classification is derived and memoized by database basis. Storing a second
  consumer-specific result would recreate drift.

### One admission/projection codec: `seon.sci.admit/admit`

**What exists.** The codec is landed and used by eval, prompt, error,
instrumentation, and generic data floors. It realizes lazy values, refuses
opaque/cyclic shapes, applies the one cap set, and returns both ordinary value
and printable EDN.

**Who duplicates or bypasses it.** Five owners repeat request assembly around
seven calls. Web surfaces, trigger prose, root messages, render walk, and the
data drill place derived values into consumer output without one uniform
admission step.

**Home and contract.** Keep `seon.sci.admit/admit`; it is already the grounded
name and owner. Add no generic `codec`, `project`, or `serve` namespace.
Boundary motion should supply the value, caps, interrupt function when armed,
and core-error mode once, then carry the admitted ordinary value to the
boundary grammar.

**Cost and breakage.**

- Admission can elide a collection. Applying it blindly after a Hiccup
  projection may destroy Hiccup grammar, so HTML needs an ordering proof:
  admit the values a projection consumes, or define a structure-preserving
  admitted Hiccup boundary and validate it before serialization.
- Message content is already inside an admitted eval result before transaction
  delivery. Re-admitting the durable row is wasted work; the missing step is
  routing its later AI/HTML projections consistently.
- Specialist renderers may currently depend on unbounded strings. Turning the
  existing caps on at every boundary will surface truncation and tests that
  asserted complete text.
- The unarmed call shape should be factored without hiding
  `:seon.config/on-core-error`; R41 requires that mode to travel with the
  request.

### One delivery motion: `seon.render/render`

**What exists.** The router already resolves each unit's declared kind late
and isolates errors. Prompt and web callers already use it. Family defaults
already give messages both AI and HTML projections.

**Who duplicates it.** `seon.context/trigger-ai` and
`seon.render.root/messages-html` query and format message facts independently.
`seon.render.walk/prose` invents a raw `pr-str` projection for non-string
outputs. Generic data floors separately repeat value selection and admission.

**Home and contract.** Keep `seon.render/render` as the only delivery motion:
`unit + boundary kind -> projected value or flat error`. Render once per
boundary because AI prose and browser Hiccup are different consumer
projections. Prompt reduction, HTML serialization, SSE change suppression,
and durable message transaction creation remain their consumers' distinct
work; they are not alternative renderers.

**Cost and breakage.**

- Trigger rendering needs a unit for the exact message that caused the held
  run. The current code queries only content and sender; conversion must pull
  the message unit and then invoke the family AI render without changing
  trigger identity.
- Root message rendering needs ordered message units and the message family's
  HTML output. The root may still wrap them in a cluster list, but it must not
  restate sender/content semantics.
- `render.walk/prose` must treat a non-text AI projection as a flat grammar
  error, not silently stringify it. This is observable breakage for malformed
  projections and is desirable.
- Web serialization must retain nil omission, stable surface IDs, error cards,
  and equality over final bytes. Condensation must not move SSE, paging, or
  change suppression into `seon.render`.

## What N5 needs from the same read

One read event must be sufficient for evaluation, corpus facts, and workload
classification. The minimum output is:

| Reader output | Eval customer | N5/index customer | Scheduling customer |
|---|---|---|---|
| parsed ordinary form | `sci/eval-form` | literal form/body analysis | direct call/effect analysis |
| exact original source | plan digest, receipt, error | `:seon.fn/source`, test source, namespace source | provenance/debug evidence |
| start/end plus line/column | parse error and steering | source locations and edit-hook findings | explanation of an uncertain edge |
| selected reader features and lexical namespace | correct SCI interpretation | CLJ/CLJC projection and namespace identity | qualified call targets |
| namespace declaration facts | namespace changes during a multi-form read | doc, summary, requires, aliases, refers | call resolution |
| defn identity and visibility | durable def installation | symbol, private, source fingerprint | root identity |
| docstring and arglists | none | renderable function signature | none |
| literal `:malli/schema` | invocation admission | contract and arity rows | purity/admissibility input |
| literal `:seon.workload` | none | optional leaf fact | direct `:io`/`:compute` evidence |
| direct calls, effects, and uncertainties | none | program graph | reachability classification |
| test identity | none | `:seon.test` row and runner selection | none |

Docstring, arglists, `:malli/schema`, and `:seon.workload` must come from the
defn form's symbol metadata, docstring, attr map, and methods—not from a loaded
host Var at a potentially different basis. Macro-expanded semantics may add
facts later, but absence of a statically resolved edge must be recorded as
uncertainty, never filled from a hand list.

The parse result should be open data rather than an enum-tagged entity. A form
event is identified by the presence of its form/source attributes; namespace,
function, schema, and test facts are projections of those attributes. Error
events are the existing flat `:seon.error` shape.

## Honest migration order

This report does not authorize implementation. The dependency-safe order for
the later condensation wave is:

1. seal the reader event and context contract against SCI source, including
   CLJC features, aliases, syntax quote, tags, EOF/cardinality, and flat errors;
2. make N5 index facts a pure projection of that event, including call,
   uncertainty, contract, and workload leaf facts;
3. let eval accept the already parsed ordinary form, then delete the reply/eval
   second parse together;
4. move hook, test-root, docstring, and first-party changed-test consumers to
   the same facts and delete their redundant accepted-source readers;
5. land the workload query over the settled facts; and
6. route message/value consumers through `seon.render/render` and the one
   admission step per boundary, preserving the web byte contract.

The shortest falsifier is simple: if the same accepted source text is handed
to any second Clojure reader after the core reader returned its event, the
reader condensation is not complete. For delivery, if a consumer queries a
family's raw facts and restates their prose/HTML while that family declares
the requested render kind, the delivery condensation is not complete.
