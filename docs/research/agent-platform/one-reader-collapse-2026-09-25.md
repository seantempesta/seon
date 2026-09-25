---
type: research
status: "design lane design-one-reader-opus; revised after Astra REVISE 9b78e96c4 and ACCEPT-AFTER-FIXES 5407f1d65 (five fixes folded); option A (S1-S3) authorized for implementation"
created: 2026-09-25
scope: collapse src/seon/sci/reader.cljc onto edamame, SCI and clj-kondo; classify the scattered source readers
pins: "seon e6066f091 (reader.cljc, reply.clj, sci/eval.clj, turn.clj unchanged since f29fa7634); edamame 63373df6 (local commit on v1.6.42); sci 409888f6; clj-kondo 57252e07; tools.reader 1.6.0 jar"
---

# One reader: what is a parser, what is ours (2026-09-25)

**What exists.** Every parsed form in `seon.sci.reader/read` comes from edamame: `reader.cljc:584` calls
`sci/core.cljc:411-419`, which calls `sci/impl/parser.cljc:168`. The placeholder forms made at `reader.cljc:813` are
error events, not parsed forms. Around that single parse call sit about 800 lines of our own interpretation:
- resolution: syntax-quote and aliases;
- declaration facts;
- a prediction of the namespace each form will run in;
- error classification;
- recovery;
- Markdown fences.

**Approach.** Each of those parts is kept only where its consumer's guarantee needs it and no library or existing Seon
producer supplies the value. The owner's "another parser" concern holds for the parts that interpret or predict, not
for the parse itself.

**What this design approves now (option A):**
1. delete the dead outputs (S1);
2. repair the end-of-text number bug where edamame produces it (S2);
3. read for execution with the evaluating SCI context, so that SCI resolves symbols (S3).

Everything else stays until its consumer is converted (§6). That covers the coordinate change, the fence move, the
declaration facts, namespace attribution and recovery. For each part the table says what the consumer needs and what
would have to change first.

## 0. Broken first

1. **Syntax-quote qualifies core Vars and classes into the current namespace** in SCI execution, and in any context-free
   read that lacks the needed mappings. The JVM MCP entrant supplies host mappings for Vars and classes
   (`script/seon/dev/mcp.clj:357`), which the hand resolver checks first (`reader.cljc:97`). Misqualification there is
   therefore **unproven**. JVM compatibility stays a regression requirement, and the bridge is not changed.
   - **Source.** `syntax-quote-resolver` (`reader.cljc:84-105`, installed by `parse-options:106-124`) qualifies every
     bare symbol that is neither aliased nor referred with the current namespace. Special forms are the exception, and
     edamame handles those (`edamame/impl/syntax_quote.cljc:88-103`).
   - **Bypass.** SCI's resolver (`sci/impl/parser.cljc:60-120`, merged in `:126-167`) checks refers, interns,
     `clojure.core` minus excludes, and classes. It is bypassed because `read-events` builds a fresh `(sci/init {})`
     for every read (`reader.cljc:563`) and overrides `:syntax-quote`.
   - **What was observed.** Through the installed SCI entrance, `` `(inc String join) `` *evaluated to*
     `(user/inc user/String user/join)`. That value comes from evaluating the expansion. The read form is construction
     code (`edamame/impl/syntax_quote.cljc:141-177`) whose quoted symbols are `user/inc`, `user/String` and `user/join`.
     A scratch context produced `Unable to resolve symbol: probe.b/inc` when it called the macro
     ``(defmacro m [] `(inc 1))`` after our reader had read it. Default was then nuked, so this is a reported
     observation, not current proof. §9 R1 repeats it.
   - No issue exists (`rg -il syntax-quote docs/seon/issues` finds none). This lane writes one file, so **root should
     file** `docs/seon/issues/syntax-quote-qualifies-core-vars-into-the-current-namespace.md`.
2. The same SCI-mode probe took 592 ms and allocated 1.30 GB, a reported observation. That belongs to the open issue
   `docs/seon/issues/a-trivial-sci-evaluation-allocates-half-a-gigabyte.md`, not to the reader.
3. Default was faulted with `program-unavailable` during the probes. It has since been nuked for a fresh store, so none
   of §2's live values is current proof.

## 1. Method

All probes ran in default pid 59566 in JVM mode, in the scratch namespace `one-reader-probe` (session `one-reader`),
which was removed afterwards. None of default's Vars was redefined. clj-kondo ran through `seon.fn.analyzer/analyze`
with `::sources` under `tmp/one-reader-probe/`, also deleted afterwards. §10 keeps the two helper definitions as run and
*sketches* of the rest. E3 and E4 invocations, whole result envelopes, per-input comparison outputs and the timing
harness were **not retained**, so they are unavailable. R1–R5 and the committed measurement script are future evidence,
not satisfied. R3's equivalence keeps regex flags as well as the pattern text, and compares metadata and positions
separately. The hot-path landing records comparable armed parent/change timings, allocation and memory.

## 2. Evidence (reported observations, 2026-09-25 ~21:35Z)

**E1. The installed reader.** Results for the inputs in §10:

| Input | Events |
|---|---|
| `1` | `"1"` at 0–1 |
| `1 2` | `"1"`, and `"2"` at 1:3 |
| `(+ 1 2))` | `[:form :error(:stray-closer)]` |
| `(let [x 1]\n(f x)\n}` | `[:error :form :error]` |
| `(a) (b\n(c)` | `[:form "(a)"] [:error " (b\n" :unclosed] [:form "(c)"]` |
| `{:a}` | `:odd-map` |
| `#foo/bar 1` | refused tag |
| `#=` | refused |
| `{:seon.repl/value 1}` | fabricated-response error |
| `(str "héllo 😀")` | spans 0–16 in UTF-16 units |

**E2. Raw edamame.** The probe used `e/source-reader`, `e/parse-next+string` and `:end-location true`, plus a
`:postprocess` that recorded the outermost location and re-attached it as metadata when the value was `iobj?`.
- **Locations.** Every top-level value gets a location, scalars included (`edamame/impl/parser.cljc:863-905`). The
  callback also sees every nested value, so the top-level location is whatever the last call recorded.
- **Errors.** Error data carries `:line`/`:column`, `:edamame/opened-delimiter`, `:edamame/expected-delimiter`,
  `:edamame/token` and `:edamame/containers`. Reading stops at the first error.
- **Fences.** Fence lines read as syntax-quote.
- **End of text.** A number that ends the text gets an end column one short. The cause is in edamame `read-number`
  (`parser.cljc:176-195`), which unreads `nil` at EOF. tools.reader 1.6.0 then decrements the column even for nil,
  both in the indexing reader (`reader_types.clj:137-159`) and in the source-logging reader (`:296-304`).

**E2b. The 35 stored replies.** The comparison used `=` on the vector of top-level forms and `=` on the vector of
`[line column]`. Result:

| Replies | Result |
|---:|---|
| 3 | carry a reader error |
| 15 | equal on both forms and positions |
| 17 | differ |

For the 17 that differ, only the **first** difference in each reply was inspected. Those first differences had two
causes:
- **Fences.** Ours skips them; edamame reads them as extra syntax-quoted forms.
- **Coordinates.** Our position is the start of a span that includes the comment above the form; edamame's is the
  form's own start.

`=` is not a sound equivalence for forms containing regex objects or auto-gensyms, and none was checked. §9 R3 repeats
the comparison with per-input results kept and an explicit equivalence relation.

**E3. SCI with a real context.** The scratch context was `(ns probe.a (:require [foo.bar :as fb] [clojure.string :refer [join]]))`.
The read was `sci/parse-next+string ctx rdr {:features #{:clj} :end-location true :postprocess … :read-eval …}` inside
`sci/binding [sci/ns …]`.
- The read form of `` `(inc String join fb/x) `` quotes `clojure.core/inc`, `java.lang.String`,
  `clojure.string/join` and `foo.bar/x`. What was printed was the form itself (construction code), not an evaluated
  value.
- `::fb/k` → `:foo.bar/k`. `#?(:clj :jvm …)` → `:jvm`, but only when `:features` is passed: the eval context has no
  features (`sci/eval.clj:184,280`). `42` carries its location.
- This covers one namespace, one alias and one refer. Excludes, renamed refers, two namespaces and imports are
  unproven (§9 R1).

**E4. clj-kondo on broken input** (5.8 ms per text):

| Input | kondo findings |
|---|---|
| `(+ 1 2))` | `:syntax` at 1:8 |
| `(let [x 1]\n(f x)\n}` | `:syntax` at 1:1 and at 3:1 |
| `(a) (b\n(c)` | `:syntax` at 1:5 and at 2:4; `(c)` is **nested inside** `(b` |
| `{:a}` | `:missing-map-value` |
| `#foo/bar 1` | none |

kondo's parser inserts the expected closer and keeps going (`parser/clj_kondo/impl/rewrite_clj/parser/core.clj:84-124`),
so the tree it repairs is not the one SCI would read. Its syntax findings are points, not spans. Errors at read time
therefore stay with edamame. This does not make every other kondo output spanless: its definition ranges have spans.

**E5. `clojure.edn` on Clojure source.**
- `(def x 'a)` reads with `'a` as **one symbol named `'a`**, and prints the same, so the misread is silent.
- `#(inc %)` fails with "No dispatch macro". `@a` fails with "Invalid leading character".
- `^long` metadata survives (`EdnReader.java:558-593`).

## 3. Inventory: every function, its consumer's guarantee, and the library seam

"Consumer" means every src site that reads the key directly, found by `rg` over event keys. It is not limited to the
`program/declaration-row` callers.

| Lines | Function(s) | Produces | Consumers and the guarantee they need | Library or existing producer | Fate |
|---|---|---|---|---|---|
| 12 | `eof` | sentinel | read-events | edamame `:eof` (`core.cljc:126-154`) | keep |
| 14-28 | `error-value` | declared failure shape | every refusal | none | keep |
| 29-53 | `refusal-handler`, `accepted-reader`, `reject-read-eval` | tag and `#=` policy functions | parse options; the JVM MCP caller supplies `*data-readers*` as tags (`script/seon/dev/mcp.clj:357-365`) | edamame `:readers`/`:read-eval` are the seam; SCI merges caller policy last (`sci/impl/parser.cljc:150-167`) | keep |
| 54-75 | `line-starts`, `cursor-offset`, `offset-cursor` | row/col ↔ UTF-16 offset | read-events, recovery, error-event | none: SCI exposes line and column, not an offset (`sci/get-line-number`) | keep |
| 76-83 | `qualified-symbol` | qualified declaration symbol | declarations | kondo definition `:ns`/`:name` | keep |
| 84-105 | `syntax-quote-resolver` | symbol resolution | parse-options; the JVM MCP caller relies on it with **host** aliases and refers and no SCI context | SCI's resolver, for execution reads only | **replace for execution reads only (S3)**; keep for context-free callers, with a narrowed promise |
| 106-125 | `parse-options` | edamame options, rebuilt every form (`reader.cljc:586-588`) | read-events | SCI builds and merges options on every `parse-next` too (`parser.cljc:146-167`) | S3 builds ours once per read. The library's own per-call work stays |
| 126-250 | ns-row block (10 functions, 126 lines) | `:seon.ns/*` rows from a conformed `ns` form | eval `row` (`eval.clj:386`); agent namespace seed at a transaction boundary with no evaluation (`agent.clj:162-184`); reading context | `clojure.core.specs.alpha/ns-form` is already used. **edamame `parse-ns-form`** (`core.cljc:161-166`, `impl/ns_parser.cljc:20-89`) returns `:current`, aliases, libspecs with refer and rename, and imports. It does not validate or expand `:refer :all` to publics. SCI's table after evaluation (`sci/core.cljc:712-730`, mapped at `eval.clj:1021-1094`) holds effective bindings but no doc or source | keep; compare with `parse-ns-form` before option C (§6 C) |
| 251-314 | `function-declaration`, `test-declaration` | `:seon.fn/sym` and function facts, `:seon.test/sym` | **(1)** `eval.clj:4463` `evaluate-for-install`: `:seon.fn/sym` on the event decides whether to fork a **candidate context**, so a function declaration evaluates in isolation. **(2)** `turn/read-only-evaluation?` (`turn.clj:1959-1973`) conservatively leaves declaring evaluations out of generated reads | kondo analysis records ns, name, doc, privacy, test and arglists (`clj-kondo/impl/analysis.clj:87-124`); `seon.fn/var-row` shapes them (`fn.clj:684-785`). They do not match exactly: kondo recognizes more forms, and final rows add runtime Var facts (`eval.clj:817-858`) | keep (option B is blocked, §6) |
| 315-328 | `resolved-operation` | operator identity | declarations, context, `::call` | none | keep |
| 329-373 | `declaration-occurrence` | `::declaration-family/identity/refusal` | none (rg) | — | **delete (S1)** |
| 374-405 | `schema-declaration`, `schema-unregister`, `namespace-unmap` | Seon declaration marks | eval `row` and `deletion-row` (`program.cljc:1376-1419`) | none: these are Seon semantics | keep |
| 406-428 | `declaration-facts` | merged facts | read-events | — | keep, minus occurrence |
| 429-450 | `nested-executable-declarations` | `::nested-declarations` | none in src; eval's multiple-declaration refusal uses SCI interns and analyzer rows (`eval.clj:880-901`) | — | **delete (S1)** |
| 451-515 | `namespace-changing-mention?`, `next-reading-context` | the namespace each planned form is predicted to run in | reply plan `:seon.ns/name`, which the turn uses as each form's evaluation namespace (`turn.clj:1928,2126`) | SCI's ending namespace exists only *after* a form runs. A plan frozen before execution cannot know it | keep (option C is a redesign) |
| 516-557 | `fence-line?`, `skip-fences!` | fence delimiters skipped **only between forms**; literals are consumed whole by the library | reply | none | keep (the rejected S4, below) |
| 524-535 | `advance-to!` | reader moved to an offset | recovery resume | an adapter; no library seek | keep |
| 558-662 | `read-events` | event vector with the declared shape | read | edamame loop and locations | S2 and S3 shrink it |
| 663-702 | `reader-error`, `cause-data` | flat error from edamame data | recovery, read | edamame error data | keep. It selects a few facts plus the message; it does **not** carry the whole cause chain (§6 E) |
| 703-755 | `closer?`, `parse-classification` | error kind and `::prose-span?` | `turn.clj:3067` (repair of `:unclosed`/`:stray-closer`); `error.clj:980-981`; reply's prose recovery | edamame delimiter and token facts. Two kinds are **guessed**: `:odd-map` from position plus `{`, which lumps in duplicate-key failures (edamame has separate producers, `parser.cljc:631-644`), and `:bad-metadata` from any `IllegalArgumentException`, which the docstring at `:710-712` contradicts | keep; fix the guesses (§6 E) |
| 756-859 | `line-start-anchor`, `preceding-comment-block`, `token-end`, `recovery-point`, `error-event`, `recovering-events` | error spans and resumption | reply splitting (`reply.clj:325-393`); Parinfer repair (`turn.clj:3056-3112`) | none: edamame throws at EOF (`parser.cljc:298-305`), and kondo repairs the tree | keep as **declared heuristic policy** (§5) |
| 860-919 | `read` | entry point | 13 call sites | — | keep |

## 4. The composition this design approves

```
execution:  source ─► sci/parse-next+string evaluating-ctx rdr
                        {:features #{:clj} :readers policy :read-eval refuse :eof sentinel}
                        (inside one-event, under sci/binding [sci/ns target-ns]) ─► event
reply/JVM:  text   ─► same loop, no SCI ctx: supplied aliases/refers, hand resolver kept,
                        deferred aliases, fences skipped between forms, recovery ─► events
errors:     edamame ex-data ─► classification ─► error event (evaluation refuses the first)
```

The **context-free promise** is written down. A read without an SCI context keeps today's SCI wrapper
(`sci/parse-next+string` over a per-read empty context) and today's state-dependent options. Those options are rebuilt
for every form from the evolving reading state (`reader.cljc:586,658`), so `(ns probe.a)` followed by `::k` resolves
in `probe.a`. The read resolves syntax-quote only from the aliases and refers its caller supplies. The JVM MCP caller
supplies the host namespace's aliases, Vars and classes. Reply planning supplies none and defers unknown aliases. The
read is correct for exactly those inputs.

Replacing that wrapper with direct edamame calls is **deferred**. SCI's defaults (`:all`, `:read-cond :allow`, its
location keys and predicate, end locations off; `sci/impl/parser.cljc:44`), its top-level symbol metadata (`:171`) and
its failure wrapping (`:180`) would all have to be reproduced first. No shared mutable context and no mutable resolver
cell is created.

**Supplied events.** `evaluate` takes `(or (:seon.sci.eval/event request) (one-event …))` (`eval.clj:4203`). Today's
suppliers are:
- `evaluate-for-install`, which reads through `one-event` once (`:4452`) and hands that event to `evaluate` (`:4464`);
- the no-forms error built at `turn.clj:4444`, carried through `:4944`, which must stay an error.

The reply path does not supply events. It carries source, comment and namespace (`reply.clj:214`), and the evaluator
reads that source. An executable supplied event must already have been read for the valid execution context. A
planning event is not one merely because it has the same shape. No unconditional reread, provenance registry or cache
is added.

## 5. Recovery is heuristic policy, and now says so

Recovery resynchronizes on a line that starts at column 0 with `(`, or with `;` for localized errors. It is **not**
proof of top-level structure. Counterexamples predicted from source: in `(broken [\n(inner)` the anchor `(inner)` sits
inside the unclosed vector, and an unclosed multiline string can contain a column-0 `(`. The existing tests
(`reader_test.clj:443-465`) exclude anchors that begin with `{` or `[` and inner forms that are indented. They do not
cover these cases.

The guarantee we choose is local: **emitted spans are ordered, and recovery strictly advances**. Trivia and gaps stay
recoverable from the original text. They are not partitioned into events: comment-only input yields no events
(`reader.cljc:606`), and a fence skipped before `::failure-start` leaves a gap before the error event (`:581,595,833,846`). An agent sees each malformed span as an error at its position, and the forms after it
run only if they read at that anchor. The alternative is to refuse the rest of the text as a single error. It is
simpler, but it gives up the prose-plus-code replies and the per-span Parinfer repair that `reply.clj` and `turn.clj`
provide today. The choice is the owner's; §6 D prices it.

**Cost.** Every resume builds a new reader over the **whole** text, recomputes `line-starts` and advances from offset 0
(`reader.cljc:563-567, 850-857`). With k broken spans in n characters that is O(k·n), plus a line scan for each anchor.

The bound on input is the `:seon.config.eval.result/max-source` dial; its default value is unverified here. Several
callers pass `(count source)` as the bound, which makes it vacuous (`turn.clj:1860,2318`; `reply.clj` in its
one-argument arity). §9 R4 measures scaling on adversarial input: one broken span per line, 1–64 KB. The reader
cannot simply be reused. For an EOF failure, edamame has already consumed to the end of the text (`parser.cljc:298`)
before Seon chooses an earlier anchor, and `advance-to!` only moves forward (`reader.cljc:528`). Sharing the line-start
index would remove only the repeated index construction. Recovery is **unchanged in A**. Any later optimization names a
real dependency seam and adds no seek abstraction. The docstring at `reader.cljc:825` ("proven top-level anchors") is
corrected in S1.

## 6. Slices and options

**Option A, approved scope, with slices sized under the 100-added-lines rule:**

| Slice | Change | Lines | Guarantee kept, and the proof it needs |
|---|---|---|---|
| **S1** dead outputs | Delete `declaration-occurrence` (45), `nested-executable-declarations` (22), the `::refer-all-targets` bookkeeping (about 6) and their merge and dissoc (about 4). Drop the assertion at `reader_test.clj:764-769` about the deleted output. | reader −77, tests −6 | Every surviving behavior keeps its regression. HEAD must load. |
| **S2** EOF producer fix | In the maintained edamame fork, `read-number` changes to `(when ch (r/unread rdr ch))` at `parser.cljc:192`, matching `read-token`'s guard at `:108-113`. Commit the fork, bump the gitlink, and prove the loaded classpath uses it. Only then delete the `peek-char` workaround in `read-events`. | edamame +1 −1; reader −5 | `a-number-ending-the-text-keeps-its-source-and-span` stays green. §9 R2 covers EOF numbers, delimiters, multiline/CRLF input and source logging. |
| **S3** execution reads use the SCI context | `one-event` (`eval.clj:957-1000`) binds `sci/ns` to the target namespace and calls `read` with `:seon.sci.reader/ctx`. `read` threads the context through its explicit reading-context reconstruction (`reader.cljc:867`). With a context, `read-events` calls `sci/parse-next+string` on it without the `:syntax-quote`/`:auto-resolve` overrides, keeping `:features #{:clj}`, the EOF sentinel and the tag/`#=` policy, and it creates no per-read `sci/init`. Context-free reads keep the SCI wrapper and state-dependent options (§4). Supplied events keep their current handoffs (§4). | reader about +8 −2; eval +3 | New class regression: `` `(inc String join fb/x) `` read under a context, plus excludes, a renamed refer, an alias and two namespaces. `explicit-reader-policy-overrides-a-hostile-sci-context` stops using `with-redefs` on `sci/init` and passes a hostile context argument (the no-Var-redefinition law). The JVM MCP read path is untouched in code. Its compatibility stays a regression requirement. Later evidence also covers multi-form namespace and alias planning. |
| **Total** | | reader ≈ −76 (919 → ≈ 843) | |

The earlier −137 is withdrawn, because S3's old form and S4 were not equivalent.

**Rejected as specified (S4, fences and prose moved to reply).** Blanking fence lines across the whole text would
corrupt literals. The existing test (`reader_test.clj:56-59`) keeps ```` "before\n```clojure\n(+ 1 2)\n```\nafter" ````
inside a string. Replacing the prose regex with `:invalid-token` would also treat tokens inside code as prose. What
stays: `skip-fences!` runs only at library-proven boundaries between forms, and `reply.clj`'s `prose-read-failure`
regex (`:236-237, :284`). The smallest later improvement uses the existing positive evidence: reply decides prose from
`::prose-span?` (token kind plus no enclosing container), not from `:invalid-token`. Moving fence ownership to reply is
reasonable only once reply orchestrates the between-form loop and calls the same skip operation. That is not a second
scanner. It is deferred.

**Rejected as specified (the old S3, form-own coordinates).** `eval.clj:4319-4321` passes the comment-inclusive
`::source` together with `::line`/`::column` as the source origin, and `source-offset`/`program-change` (`:508-559`)
subtract that origin to match SCI Var positions to kondo's UTF-8 form spans. Moving `::line` to the form's own start
breaks provenance for `; note\n(defn f …)`. If form positions are wanted, they must be **new keys**
(`::form-line`/`::form-column` from the edamame location), leaving the origin as it is. Only then can `reply.clj`
`form-start` (18 lines) read them instead of re-deriving them from metadata. The proof is the actual admission of a
comment-led declaration, CRLF input and multibyte input. That is roughly +4 −14, in a later slice.

**Option B, blocked (function and test facts leave the reader).** There is a live caller:
- `eval.clj:4463` uses `:seon.fn/sym` to choose candidate-context isolation.
- `turn.clj:950-971` analyzes only settlements that already hold a program row, so no evaluation gets a general kondo
  "declares?" answer.

Before the facts can go:
1. `evaluate-for-install` must decide from an authoritative, already-held declaration fact. Candidate isolation stays
   in its acceptance evidence.
2. The writer must guarantee "declares ⇒ has a receipt" for exactly the successful evaluations that carry read
   evidence and are selected at `turn.clj:1959-1973`, including unchanged declarations. Agreement on stored samples
   does not show this.

Until then, the conservative exclusion stays. Cost: two consumer conversions plus a writer proof, several hours. The
reader loses about 64 lines. Sacrifice: none, if the conversions land. Any `signature.cljc` step must keep comparing
actual bindings against advertised arglists: kondo arglists can be metadata overrides
(`clj-kondo/impl/analyzer.clj:1948-1954`, and `signature.cljc:124-144` allows a declared override).

**Option C, a redesign (namespace attribution and ns rows).**
- A plan frozen before execution cannot know SCI's future ending namespace, so pre-run attribution would be lost.
- SCI bindings carry no authored `ns` doc or source.
- The agent namespace seed runs with no evaluation.

First compare edamame's pure `parse-ns-form` with the 126-line block. It could remove the libspec decomposition while
Seon keeps validation, `:refer :all` expansion, dynamic `require`/`in-ns` and fail-closed attribution. Do not use its
`:auto-resolve-ns` state: it lives in an options atom (`impl/parser.cljc:975`) and would carry between independent
replies. The earlier −190/+25 estimate is withdrawn; it is unpriced until that comparison is done. Cost: hours of
cross-owner work in turn, agent and eval, and the owner rules on losing pre-run attribution.

**D. Recovery policy (owner).**
- (1) Keep the declared heuristic, with the O(k·n) fix once R4 has measured it. **Recommended.**
- (2) Refuse the rest of the text as one error after the first failure. This deletes about 100 lines and loses prose
  replies and per-span repair.

**E. Error fidelity (small, owner of reader.cljc).**
- Classifications without distinguishing data (`:odd-map` versus duplicate key, and "any IAE" as `:bad-metadata`)
  become `:other`, or edamame distinguishes them at its producer.
- `reader-error` carries the whole cause chain (class, message, `ex-data`, cause), as AGENTS.md requires, instead of a
  few selected facts.
- About +10 −8.

## 7. Scattered source reads (classified by what the stored producer encodes)

"Legit" means EDN that was written as EDN and is read as EDN.

| Site | Reads | Verdict |
|---|---|---|
| `artifact.clj:51`, `ai.clj:646`, `schema.clj:1125` | packaged or configured EDN | legit |
| `blob.clj:51`, `schema.clj:750,1463,1787`, `program.cljc:88` | round-trip fidelity checks of `pr-str` output | legit |
| `error.clj:715,940`, `db.clj:4040`, `fn.clj:2993,3000,3176,3185,3291`, `schema.clj:2977`, `turn.clj:1294,1301`, `program.cljc:691`, `instrument.clj:518` | stored schema, spec, data or error EDN | legit |
| `repl.clj:85` `shown-value` | shown text, read as complete EDN or kept as text | legit; it does not recover the live value |
| `repl.clj:163` | stored triage EDN | legit |
| `print.cljc:107,1080` | reading back literals the printer emitted | EDN is legit, but the value was already held. A value-flow cleanup, not a reader question |
| `instrument.clj:236`, `fn/analyzer.clj:804-812`, `sci/eval.clj:2275,2305` | `:seon.fn/arglists`: kondo arglist *source text* | **Clojure source read as EDN.** Default values in destructuring can contain `'`, `@` or `#(`, and these fail or misread (E5). Route them to the held analysis form, or store arglists as data |
| `repl.clj:124` `def-note` | agent source | **unsafe misread** (E5). Use the evaluation's held form |
| `bootstrap.clj:753-771` `calls-symbol?`, `contains-history-query?` | stored evaluation source, where a parse failure means `false` | **unsafe**: it decides supervision (`:773-803`) from a silent EDN misread. Route to the held form or the analysis |
| `sci/eval.clj:1251-1282` `declaration-source-value` | executable source for requested **and** historical committed rows | **Clojure source read as EDN.** The event form covers only the requested side. The committed side needs its stored row's own encoding (for example, the analysis row's declaration value). Keep the comparison's contract, and do not swap in one side's form |
| `fn/signature.cljc:17-31` | `clojure.core/read` with a hand-written `LispReader$Resolver` | a third source reader. Its alias resolver returns the alias itself (parser-proves 5d). First pass it the **held form**, and keep its binding-versus-arglist comparison |
| `fn.clj:1453,2553` `kondo.utils/parse-string-all` | a second kondo parse after analysis | one implementation, **not** one parse. Reuse the analyzed tree keyed by the source digest and resolver inputs, not a second cache |
| `turn.clj:1858,2317,3048,4666`; `reply.clj:351,359`, then the evaluator's read | repeated calls to the one reader over the same source | one implementation, **not** parse-once. Retirement is **not** established by A. Reusing a planning form needs valid resolver inputs and source coordinates at the authority (AGENTS.md: validate a pre-read's basis). Until then these rereads are named repeated reads, not parse-once |
| `script/seon/dev/mcp.clj:347-430` | JVM mode calls `seon.sci.reader/read` with host resolver inputs; SCI mode calls the evaluation entrance | converged. `require-single-clj-form!` was already deleted in 2828bc808; installation is unverified. The earlier `Alias an not found` observation came from reading a whole `(do (require …) ::an/k)` form before evaluating it, which is correct reader behaviour, not a defect |

Nothing reads dangerously in the `#=` sense. The defects are silent misreads of Clojure source through the EDN reader,
and reads repeated across stages.

## 8. What our layer genuinely adds

- **Recovery policy**, which §5 declares as a heuristic.
- **The declared error shape** and the classification built on edamame facts, apart from the two guesses §6 E fixes.
- **Seon declaration semantics**: `register!`, `unregister!` and `ns-unmap`.
- **The fabricated-response refusal.**
- **Namespace prediction for frozen plans** (option C).
- **The context-free resolver** for JVM and planning callers, with its narrowed promise.

Everything else transforms library output into our shape, or duplicates another owner, as §3 marks row by row.

## 9. Pending re-checks (after root reports default is up on the fresh store)

- **R1.** Reproduce §0.1 at the installed SCI entrance and record the read form separately from the evaluated value.
  Then run the S3 regression matrix in a scratch context: core, class, exclude, renamed refer, alias, two namespaces.
- **R2.** The S2 repair on a scratch edamame load: EOF numbers, numbers before delimiters, multiline and CRLF input,
  and the source-logging `parse-next+string` text.
- **R3.** The 35-reply comparison (or the fresh store's replies), keeping per-input results. The equivalence relation:
  equal after replacing regex patterns by their source strings and auto-gensyms by their position of first occurrence.
- **R4.** Recovery scaling: time and allocation for k = 1…512 broken spans in inputs of 1–64 KB, plus the
  `max-source` default.
- **R5.** Admission of a comment-led declaration, to pin the origin semantics before any coordinate slice.
- **Measurement script.** The re-checks need a committed script, `test/seon/sci/reader_probe.clj` or `tmp/` per root's
  ruling. This lane is limited to one file, so root assigns that file.

## 10. Retained helper definitions and probe sketches

```clojure
(defn rd [t]
  (let [ev (r/read {:seon.sci.reader/text t :seon.config.eval.result/max-source 10000})]
    (if (map? ev) [:failure (:seon.error/message ev)]
      (mapv (fn [x] (if (:seon.sci.reader/error x)
                      [:error (:seon.sci.reader/source x) (get-in x [:seon.sci.reader/error :seon.error/data :seon.sci.reader/error-kind]) (:seon.sci.reader/line x) (:seon.sci.reader/column x)]
                      [:form (:seon.sci.reader/source x) (:seon.sci.reader/line x) (:seon.sci.reader/column x) (:seon.sci.reader/source-start x) (:seon.sci.reader/source-end x)])) ev))))

(defn ed-read [t]
  (let [rdr (e/source-reader t) last-loc (volatile! nil)
        opts (e/normalize-opts {:all true :read-cond :allow :features #{:clj}
                                :row-key :line :col-key :column :end-row-key :end-line :end-col-key :end-column
                                :auto-resolve {:current 'user}
                                :readers (fn [tag] (get default-data-readers tag))
                                :read-eval (fn [_] (throw (ex-info "no #=" {})))
                                :postprocess (fn [{:keys [obj loc]}] (vreset! last-loc loc)
                                               (if (e/iobj? obj) (vary-meta obj merge loc) obj))})]
    (loop [acc []]
      (let [v (try (e/parse-next+string rdr opts)
                   (catch Exception ex {::error (ex-message ex) ::data (dissoc (ex-data ex) :edamame/containers)}))]
        (cond (= (first v) :edamame.core/eof) acc
              (::error v) (conj acc v)
              :else (recur (conj acc {:form (first v) :source (second v) :loc @last-loc})))))))

;; E3: (sci/binding [sci/ns ns-obj] (sci/parse-next+string ctx (sci/source-reader t)
;;       {:features #{:clj} :end-location true :end-row-key :end-line :end-col-key :end-column
;;        :postprocess <as above> :read-eval <refuse>}))
;; E4: (seon.fn.analyzer/analyze {::an/sources {"<tmp>/one-reader-probe/x.clj" t}}) → ::an/findings
;; E2b: stored-replies = distinct :seon.turn/reply values; compare (mapv :seon.sci.reader/form events)
;;      with (mapv :form (ed-read t)) by =, and [line column] vectors by =.
;; Timing: 500 warm-up calls, then the mean over 2000 calls (µs): armed read 26.6 / 28.6 (two forms) /
;;      23.7 (defn) / 84.2 (recovery); sci/init 3.55; raw edamame 7.7–12.8; SCI with a real ctx 8.3–9.9;
;;      kondo 5,800 per text. Reported observations; the SCI and edamame loops were unarmed while read was armed.
```
