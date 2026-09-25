---
type: review
status: REVISE
created: 2026-09-25
reviewed: docs/research/agent-platform/one-reader-collapse-2026-09-25.md
reviewed-commit: f29fa7634b245660d2690aa49e09c8e6134f262f
scope: read-only source and design review; no production edits or test execution
---

# One-reader collapse review

**Verdict: REVISE.** The execution reader already delegates Clojure parsing to
edamame through SCI. Delete duplicated resolution and declaration work where the
library or existing producer supplies the required value. The proposed cut,
however, misses an execution-isolation consumer, changes source-coordinate
semantics without converting admission, and replaces context-sensitive fence
handling with a transformation that corrupts literals. Recovery is a local
resynchronization heuristic, not the proven top-level boundary finder claimed.

The owner's “another parser” concern is therefore substantive. Calling edamame
does not establish that our surrounding namespace prediction, declaration
interpretation, token classification and recovery are necessary. Nor does using
the same reader function several times establish AGENTS.md's parse-once rule.
Lean on the libraries, but identify each consumer's guarantee before deleting its
producer. S1 and the narrow edamame EOF repair are supportable; S2–S4 and B need
the corrections below before implementation approval.

All proposal line numbers below refer to **f29fa7634**. Seon source citations also
refer to that commit; the relevant unchanged files were inspected in the checkout,
and changed files were checked with `git show`. Dependency sources were inspected
at the recorded gitlinks: edamame **63373df6**, SCI **409888f6**, clj-kondo
**57252e07**, and Clojure **3bc2b3e9**. tools.reader source was read directly from
the local **1.6.0 jar**, not inferred from a landing note. The upstream edamame
**38e6274 / v1.6.42** source was also inspected locally. HTTP retrieval of that
tag failed; no web result is used as evidence. This is static review, not renewed
proof of the loaded JVM or the proposal's timings.

## Required revisions

1. **B misses a live consumer that selects execution isolation.** Proposal
   `:133` and `:208–217` say `turn/read-only-evaluation?` is the only consumer of
   reader function facts. `src/seon/sci/eval.clj:4463` directly checks
   `(:seon.fn/sym event)` to create the candidate context; `:4464–4467` evaluates
   in that context and returns its handle. Deleting `function-declaration`
   without converting this caller makes function declarations take the retained
   context path. A receipt-equivalence probe cannot prove this safe.

   **Smallest fix:** retain the function identity needed by this caller until
   its decision uses an already-held authoritative declaration fact. Inventory
   direct event-key consumers, not only `program/declaration-row` callers. Reprice
   B and preserve candidate isolation in its acceptance evidence. Separately,
   `src/seon/turn.clj:950–971` analyzes only settlements that already contain a
   program row; it does not give every evaluation a kondo declaration answer.
   Establish the writer's receipt implication for the exact successful,
   read-evidence-bearing evaluations selected at `turn.clj:1959–1973`, including
   unchanged declarations, rather than treating agreement in stored samples as
   a universal equivalence. Retain the conservative exclusion until then.

2. **S3 changes a coordinate that admission uses as the source origin.** Proposal
   `:163–165,178` changes event `::line/::column` from the beginning of its
   comment-inclusive source to the form's own location. But
   `src/seon/sci/eval.clj:4319–4321` passes the comment-inclusive `::source` and
   those coordinates together to admission. `source-offset` at `:508–525` and
   `program-change` at `:549–559` subtract that origin to match SCI Var locations
   to kondo byte spans. For `; note\n(defn f ...)`, subtracting line 2 from the
   declaration's line 2 gives offset zero into a string that starts with the
   comment. The analyzed declaration starts after the comment: provenance no
   longer matches.

   **Smallest fix:** keep the source origin and form location distinct. Either
   retain the existing event coordinate semantics and add/use edamame's form
   location, or convert admission to derive the source origin from
   `::source-start` in the same slice. Preserve original source bytes, nested
   metadata, CRLF, UTF-16 substring offsets and UTF-8 analyzer spans. A reply-only
   `form-start` edit does not convert every consumer. Include a comment-led
   declaration's actual admission among the later implementation proofs.

3. **S4's whole-text fence blanking is not equivalent.** Proposal `:169–170,179`
   loses the condition in `src/seon/sci/reader.cljc:536–555`: skip a delimiter
   only between forms, while the library consumes each complete literal. The
   existing test at `test/seon/sci/reader_test.clj:56–59` explicitly preserves
   `(identity "before\n```clojure\n(+ 1 2)\n```\nafter")`. Blanking those
   lines changes the value even if every offset stays the same. It can also
   change regexes and discarded forms. S4's proposed moved test already contains
   the counterexample.

   **Smallest fix:** defer fence deletion until reply owns the between-form
   orchestration and invokes the existing skip operation only at library-proven
   boundaries. Do not invent another literal scanner. Retaining the current
   operation temporarily is smaller than a second grammar. Moving ownership is
   reasonable; the six-line global replacement and its deletion estimate are not.

4. **S2 needs separate guarantees for execution, planning and JVM callers.**
   The defect diagnosis is correct: `src/seon/sci/reader.cljc:84–124` overrides
   SCI resolution, qualifying unrecognized bare symbols into the current
   namespace. SCI's actual seam is
   `reference-code/sci/src/sci/impl/parser.cljc:60–120,126–167`, not the cited
   `:186–203`. Its resolver consults the context and the dynamically bound
   current namespace; its options merge allows explicit tag/read-eval policy.
   `sci/init {}` alone does not carry the caller's namespace, aliases, refers,
   imports, excludes or interned Vars.

   Proposal `:131,151–162,177` leaves the context-less path underspecified.
   Reply reading deliberately predicts namespace changes and defers unknown
   aliases (`reader.cljc:462–514,106–118`). More importantly, the JVM MCP caller
   at `script/seon/dev/mcp.clj:357–365` passes *host* aliases, Vars, classes and
   data readers without an SCI context. Removing those supplied resolver inputs
   in favor of a shared empty SCI context regresses that entrant. Execution also
   accepts a pre-read event at `sci/eval.clj:4203`, so not every path necessarily
   calls the newly corrected `one-event`.

   **Smallest fix:** scope the immediate syntax-quote correction to reading with
   the evaluating SCI context, with `sci/ns` bound inside `one-event` so all its
   callers get the same behavior. Keep `#{:clj}`, the EOF sentinel and explicit
   tag/`#=` refusal. Specify what context-less reads promise and which resolver
   inputs they retain; do not let a shared mutable context acquire per-caller
   namespace state. Account for the JVM stage and supplied-event path before
   claiming the hand resolver can disappear everywhere. Later proof must cover
   core symbols, classes, renamed refers, aliases, excludes and two namespaces,
   not only one scratch macro. The same-form `(require ...)` followed by an
   auto-resolved keyword is not itself proof of a reader defect: reading precedes
   evaluation of that entire form.

5. **Recovery is heuristic and its cost is not bounded by one linear read.**
   Proposal `:19–23,144–145,267–269` claims proven top-level anchors outside inner
   collections. `src/seon/sci/reader.cljc:756–762,790–807` merely finds a later
   column-zero `(` (or, for another class, `;`). For the source
   `(broken [\n(inner)`, it selects `(inner)` even though that form is inside
   the unclosed vector. An unclosed multiline string can likewise contain a
   column-zero parenthesis. The existing tests at
   `test/seon/sci/reader_test.clj:443–465` exclude anchors beginning with `{` or
   `[` and *indented* inner forms; they do not prove the stronger claim.

   edamame's `parse-to-delimiter` throws at EOF
   (`reference-code/edamame/src/edamame/impl/parser.cljc:298–305`); kondo's parser
   inserts an expected closing delimiter and continues
   (`reference-code/clj-kondo/parser/clj_kondo/impl/rewrite_clj/parser/core.clj:113–124`).
   Neither supplies Seon's chosen “split a malformed span and resume at this
   line” policy. That supports local *policy*, not this algorithm's correctness
   or necessity. Accepting kondo's repaired tree would change semantics; refusing
   the unresolved remainder needs no recovery parser.

   **Smallest fix:** explicitly choose and document the heuristic guarantee, or
   retain one error for an ambiguous remainder. Do not describe indentation as
   proof of top-level structure. Keep required error events and the reply/repair
   consumers; identify any behavior sacrificed by simpler refusal. Also price
   recovery: every restart constructs a reader over the entire input, recomputes
   line starts and advances from zero (`reader.cljc:563–567,850–857`). With many
   broken spans that is potentially quadratic, plus repeated line scans. State
   the input bound and measure adversarial scaling before promising ordinary
   sub-second behavior. No new delimiter parser is justified by this review.

6. **The dependency inventory omits an upstream namespace parser.** Proposal
   `:132,139,219–230` compares the namespace block only with post-evaluation SCI.
   Upstream edamame v1.6.42 already exposes `parse-ns-form` and
   `:auto-resolve-ns` (`reference-code/edamame/src/edamame/core.cljc:64–68,161–166`).
   Its implementation handles prefix libspecs, aliases, require options,
   doc/metadata and imports (`impl/ns_parser.cljc:20–89`); the pinned parser
   tracks explicit refers/renames and imports at `impl/parser.cljc:840–862`.
   Inspect the returned `:current`, not the stale `:name` in its API docstring.

   **Smallest fix:** add this dependency seam to the comparison before retaining
   the 126-line block or pricing C. It is not a complete replacement: it does
   not reproduce Seon's validation, dynamic `require`/`in-ns`, public-name
   expansion for `:refer :all`, or fail-closed attribution. Its automatic state
   lives in an options atom (`impl/parser.cljc:975`), so it must not leak between
   independent replies. Prefer the pure `parse-ns-form` result where it removes
   actual duplicated decomposition, retaining validation and Seon row shaping.
   SCI's post-evaluation namespace table remains the execution authority.

7. **Structured error facts do not establish S4's proposed prose equivalence.**
   Proposal `:179,270–271` proposes replacing message matching with
   `:invalid-token`/`:token-kind`. Those facts also describe tokens inside code.
   The reader already produces the more specific `::prose-span?` using container
   evidence (`src/seon/sci/reader.cljc:752–753`), and reply consumes error events
   at `src/seon/cluster/reply.clj:359–363`. Use that distinction when deciding
   whether to comment prose; an invalid-token enum alone cannot replace it.
   Current classification also infers `:odd-map` from position and `{`, which
   conflates duplicate-key failures, and infers `:bad-metadata` from any
   `IllegalArgumentException` (`reader.cljc:741–748`). The docstring at `:710–712`
   contradicts that implementation. The dependency has distinct odd-map and
   duplicate-key producers (`edamame/impl/parser.cljc:631–644`).

   **Smallest fix:** narrow the equivalence claim and use existing positive
   prose/container evidence. For classifications lacking distinguishing data,
   retain unknown or improve the dependency's diagnostic at its producer; do
   not label arbitrary exceptions as metadata errors. Preserve complete causes
   when transforming failures, as AGENTS.md requires. `reader-error` currently
   selects a few facts/message (`reader.cljc:663–701`), not a complete cause-chain
   contract. Keeping that transformation is not a proof of total error fidelity.

8. **The scattered-source-read inventory is incomplete and partly stale.**
   Proposal `:244,259` correctly rejects EDN as the grammar for arbitrary
   Clojure source. The same issue exists in `src/seon/bootstrap.clj:753–771`:
   `calls-symbol?` and `contains-history-query?` read stored evaluation source
   as EDN and turn parse failures into `false`. They affect the supervision
   decision at `:773–803`. Arglists are another producer/consumer family, not
   merely `instrument.clj:236`: `src/seon/fn/analyzer.clj:804–812` and
   `src/seon/sci/eval.clj:2275,2305` read them too. Clojure EDN does accept and
   retain `^long` metadata (`EdnReader.java:558–593`), so that particular claim
   is correct; arbitrary destructuring defaults may still contain quote,
   dereference or function-literal syntax. Classify by the stored producer's
   encoding, not by the word “arglists.”

   Proposal `:260` also lists `require-single-clj-form!` as present, although
   commit **2828bc808**, already included in f29fa7634, deleted it. The actual
   bridge calls the shared reader in JVM mode and the evaluation entrance in
   SCI mode (`script/seon/dev/mcp.clj:347–430`). A historical probe of another
   bridge version cannot establish the reviewed source's installed behavior.

   **Smallest fix:** complete the source-read inventory, mark the bridge deletion
   as already landed with installation unverified, and route source consumers
   to held forms/analysis. For `declaration-source-value`, distinguish schema
   EDN from executable source: `sci/eval.clj:1284–1305` compares historical
   committed rows as well as the current request, so “use the event form” alone
   does not supply both values. Preserve that comparison's producer contract.

9. **Neither kondo reuse nor existing rereads automatically satisfies “once.”**
   Proposal `:242,252,258` calls secondary kondo parses and repeated shared-reader
   calls acceptable. `src/seon/fn.clj:1453,2553` explicitly calls
   `kondo.utils/parse-string-all` again after analysis. Reply itself reads at
   `src/seon/cluster/reply.clj:351,359`, then evaluation reads its planned source.
   Reusing a library is good, but this is not one parse of the original form.
   Additionally, kondo's advertised arglists can be metadata overrides
   (`reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:1948–1954`).
   `src/seon/fn/signature.cljc:124–144` deliberately compares actual bindings
   with analyzed arglists, permitting a declared override. Replacing that
   comparison with the same exported arglist on both sides loses its guarantee.

   **Smallest fix:** separate the claim “one parsing implementation per stage”
   from “parse once”; name the remaining repeated reads and their retirement.
   Reuse the held library form/tree or a dependency export keyed to source and
   resolver inputs. Preserve signature disagreement/override behavior; first
   remove its duplicate read by supplying the held form, not by discarding
   the check. Do not add a second result cache or treat 26 microseconds as an
   exception to AGENTS.md's data-flow law.

10. **The probe report must distinguish read forms from evaluated values.**
    Proposal `:89–96` attributes a list of qualified symbols to
    `sci/parse-next+string` on syntax-quoted input. That API returns a pair of
    parsed form and source (`sci/core.cljc:411–419`); the syntax-quoted list is
    expanded into construction code (`edamame/impl/syntax_quote.cljc:141–177`).
    Evaluating that expansion can produce the displayed list. The resolver
    conclusion is supported by source, but the displayed result is not a
    literal parse envelope. Likewise the 35-reply comparison supplies neither
    the exact comparison function nor retained per-input results.

    **Smallest fix:** label normalized or evaluated results explicitly and link
    the exact probe forms, complete result envelopes, comparison criteria and
    measurement script. Keep reported observations distinct from fresh proof;
    do not require a new run merely to complete this read-only review. Repeated
    source reads involving gensyms or regex objects especially need an explicit
    equivalence relation rather than an unexplained “identical forms.”

## Equivalences that survive source inspection

| Claim / inventory group | Source-backed judgment |
|---|---|
| The returned Clojure forms originate in edamame | Yes: `reader.cljc:584` → `sci/core.cljc:411–419` → `sci/impl/parser.cljc:168`. Recovery placeholder forms at `reader.cljc:813` are explicitly error events, not parsed forms. This does not make all 919 lines library-owned. |
| EOF, reader policy and option composition | Valid seams. edamame `core.cljc:126–154` owns EOF and source reading; SCI `impl/parser.cljc:150–167` merges explicit caller policy last. Retain Seon's declared refusal shape and policy values. |
| Syntax-quote / class / refer resolution | SCI is the right execution owner. Special forms and gensyms are also handled by edamame `impl/syntax_quote.cljc:88–103`; the proposal incorrectly attributes every part to SCI's resolver. The class result depends on the admitted SCI context. See revision 4. |
| Scalar locations and `:postprocess` | Yes: edamame `impl/parser.cljc:863–905` supplies locations even when values cannot carry metadata. A pass-through must preserve authored metadata; the callback also sees nested values. Keep source spans separate from form locations and use the outer callback's location, not a metadata guess or equality lookup. |
| UTF-16 offsets, advance and event shaping | Loc-to-original-offset transformation remains local. `sci/get-line-number`/`get-column-number` expose coordinates, not an original UTF-16 source index. `advance-to!` is an adapter, not a library offset-seek API. Preserve terminal trivia independently of the last form's end. |
| S1 dead occurrence, nested-declaration and refer-all bookkeeping | Supported by the src key-consumer search. Runtime multiple-declaration admission uses SCI intern changes and analyzer rows (`sci/eval.clj:880–901`). Delete obsolete assertions about these outputs, not the surviving behavior's regressions. This does not justify deleting `:seon.fn/sym`. |
| Function/test rows from kondo | Exported facts exist: `clj-kondo/impl/analysis.clj:87–124` records ns/name, doc, privacy, test, arglists and optional user metadata; Seon `fn.clj:684–785` shapes them. They are not identical to the reader's narrower `defn`/`deftest` recognition, and final rows also incorporate runtime Var facts (`sci/eval.clj:817–858`). “One producer, kondo” overstates the resulting composition. |
| Namespace rows from SCI | `sci/core.cljc:712–730` is the exact pinned serializable binding seam; it is a maintained-fork API, not evidence that every SCI distribution exposes it. Seon `sci/eval.clj:1021–1094` already maps it. It supplies effective bindings, not source/doc metadata or pre-execution attribution. |
| Schema registration, unregistration, ns-unmap, fabricated-response policy | These are Seon semantics (`reader.cljc:374–427,648–656`; `program.cljc:1376–1419`), not kondo syntax facts. Keeping small transformations is justified; resolving the operator still needs the correct world. |
| kondo syntax diagnostics equal evaluation-reader errors | No. Its mismatch/EOF recovery at parser `core.clj:84–124` repairs the tree and reports point findings. Its analyzed definition ranges do not turn those findings into Seon recovery spans. Keep evaluator read failures sourced from edamame; do not generalize “no spans” to all kondo output. |
| EDN data reads | Appropriate for explicitly encoded schema/spec/resource/config/triage/error data and fidelity checks in the listed artifact, blob, error, schema, db, program and ai owners. `repl/shown-value` deliberately attempts complete EDN and otherwise returns text (`repl.clj:78–89`); it does not recover the original live value. Printer node rereads deserve separate value-flow cleanup, not a Clojure-source parser replacement. |

The **edamame fork repair is correct in scope**. `impl/parser.cljc:176–195`
unreads `nil` after the number's EOF read. tools.reader 1.6.0
`clojure/tools/reader/reader_types.clj:296–304` shows the source-logging reader
decrementing the column even for nil; the indexing reader has the same issue at
`:137–159`. `(when ch (r/unread rdr ch))` matches edamame's existing `read-token`
guard at `impl/parser.cljc:108–113` and preserves pushback for actual delimiters.
It is a minimal producer fix enabling removal of the downstream workaround.
Commit the maintained fork, bump its gitlink and prove the loaded classpath uses
it before removing the workaround. EOF numbers, delimiters, multiline/CRLF and
source logging need later implementation evidence. This review did not run them.
That approval does **not** approve S3's independent coordinate change.

## Options A, B and C

| Option | Judgment, guarantee, cost and sacrifice |
|---|---|
| **A — smallest immediate scope; recommended after revision** | Keep S1, the narrow EOF producer repair, and the SCI execution-resolution correction with all entrant contracts preserved. Defer global fence blanking and unconverted coordinate changes. Guarantees library-owned execution parsing and removal of demonstrated duplicates; sacrifices immediate removal of planning and declaration adapters. Recalculate the estimate: the stated −137 lines is not established for an equivalent implementation. |
| **B — blocked by an actual missed caller, not only an unrun probe** | Desirable removal of rich reader function/test rows once candidate selection and refresh exclusion consume authoritative facts. Cost includes the execution-isolation caller and writer proof in revision 1, not merely one stored-evaluation comparison. Preserve actual signature bindings where advertised arglists differ. Sacrifice/replacement must be named before the declaration output disappears. |
| **C — a semantic redesign requiring the owner** | Sequential execution can use SCI's ending namespace, but a pre-execution frozen plan cannot know that future value. Keep the stated loss of pre-run attribution explicit. SCI bindings do not carry the authored `ns` doc/source, and agent namespace seeding (`cluster/agent.clj:162–184`) currently runs at a database transaction boundary without evaluation. Specify those replacements and failure/rollback behavior; compare edamame's pure namespace parser before claiming −190/+25. Hours of cross-owner work is plausible, but +25 lines is unsupported. |

The proposal's warm measurements are useful reported observations, not proofs of
semantic equivalence, recovery scaling or installation. Its “per-form options
map disappears” claim also needs narrowing: SCI itself builds and merges options
on each `parse-next` (`sci/impl/parser.cljc:146–167`). Removing Seon's duplicate
construction is worthwhile; it does not remove the library's work.

## Review evidence and limits

Read the complete proposal, AGENTS.md, the data-oriented Clojure and REPL skills,
and the relevant dependency producers and first-party consumers. Checked Git
history for the reader, dependency pins and bridge retirement. The neighboring
parser proof and reader-error landing are historical reports, not installation
proof. No Clojure evaluation, tests, provider calls, reload, adoption, stop or
reset was performed. No source file was edited. Runtime timing, allocation and
memory were **not measured** in this review; the proposal's live fault report
and quoted benchmark numbers were not independently revalidated.

The source-derived counterexamples above are predictions, not fabricated test
results. Later implementation must record their actual values through the
authorized agent entrance. No shared-tree load was attempted, so no foreign
breakage was used as a reason to stop. Concurrent changes observed in
`src/seon/db.clj` and `script/seon/operator.clj`, and the pre-existing
`reference-code/http-kit` state, were left untouched.

Changed path: this review only. Net production source/test lines: **0 / 0**.
No publication occurred. Release this document to root for the design ruling;
the review does not authorize implementation or an option's semantic sacrifice.
