---
type: research
status: active
tags: [research, parser]
---

# Parser merge audit

## Verdict

The best ideas of the old parser and the fresh SCI reader have **not** yet been
merged into one robust reader.

The fresh reader is the correct surviving owner. It reads with SCI, fixes the
old reader-conditional and read-eval boundary mistakes, attributes declarations
while parsing, and lifts function and workload facts. However, it regressed the
old parser's most expensive lessons:

- one malformed form currently replaces every successful event with one
  whole-input error;
- recovery no longer continues at a proven top-level boundary;
- malformed metadata, orphan closers, and invalid tokens can discard valid
  forms on both sides;
- exact source text is taken from SCI's normalized reader buffer rather than
  sliced from the original input; and
- parse-time namespace attribution uses a hand-maintained operation allowlist
  which deliberately forgets a namespace after an ordinary top-level call.

The exact-source regression is already observable. With CRLF input, SCI returns
normalized LF source while cursor offsets still address the original string.
`seon.cluster.reply/sources` consequently invents a trailing `"; )"` plan item
for `"; note\r\n(+ 1 2)\r\n"`. The old parser's original-source spans and CRLF
tests covered this class.

The merge should therefore strengthen `seon.sci.reader` in place. It should not
restore `seon.repl.parse`, create a compatibility parser, or move reply policy
and repair heuristics into the reader.

## Scope and method

This audit inspected:

- `src-old/seon/repl/parse.cljc` and
  `src-old/seon/repl/parse/repair.cljc`;
- their three test namespaces, containing 68 `deftest` forms;
- every behavior-bearing commit in `git log --follow` for the old parser;
- `src/seon/sci/reader.cljc` and `test/seon/sci/reader_test.clj`;
- all 42 current `src/**/*.clj[c]` files used as the fresh reader's dynamic
  fixture corpus;
- current reply consumption in `src/seon/cluster/reply.cljc`;
- parser-related notes under `docs/seon/issues/`, including the archive;
- the 2026-07-29 Gemini reviews under `tmp/reviews/`; and
- focused executable comparisons of the old parser, fresh reader, and current
  reply source extraction.

The focused fresh-reader suite passed with 10 tests and 132 assertions:

```text
bin/test seon.sci.reader-test
Ran 10 tests containing 132 assertions.
0 failures, 0 errors.
```

That green result does not cover the old recovery corpus or CRLF exact-source
behavior.

## Dependency ledger

| Mechanism | Selected revision | Maintained source | Seon use |
|---|---|---|---|
| SCI | `8fac6e88f32d` | `reference-code/sci/src/sci/core.cljc:352-400`, `reference-code/sci/src/sci/impl/parser.cljc:44-51,142-190` | `sci/source-reader` and `sci/parse-next+string` are the surviving read mechanism |
| Edamame | `1.6.42`, selected by SCI | SCI parser dependency above | Clojure/CLJS reader conditionals, location data, tags, and SCI parse failures |
| rewrite-clj | `60782e501aaf312cb90c9ff0bee05d5da5125563` | `reference-code/rewrite-clj/src/rewrite_clj/parser.cljc:17-42` | Old parser's lossless node source and whitespace-preserving traversal |

The relevant first-party seams are:

- `src/seon/sci/reader.cljc:298-422` — one read loop and public error boundary;
- `src/seon/cluster/reply.cljc:65-104` — reply presentation normalization;
- `src/seon/cluster/reply.cljc` — the only current production consumer of
  `seon.sci.reader`;
- `test/seon/sci/reader_test.clj:355-403` — standing proof that the remaining
  reader sites are enumerated rather than silently proliferating.

## The old parser

### Shape and behavior

`src-old/seon/repl/parse.cljc` is not merely a Clojure reader. It accumulated
four responsibilities:

1. lossless tokenization and source spans through rewrite-clj;
2. localized parse-error classification and recovery;
3. reply-language policy separating executable forms from prose; and
4. program projection by namespace and dependency order.

Only the first two belong in the surviving general reader. The third belongs at
the reply-to-plan boundary, and the fourth belongs in program analysis or plan
execution.

The robustness-bearing regions are:

| Old source | Learned behavior |
|---|---|
| `src-old/seon/repl/parse.cljc:98-130` | Strip complete Markdown fence pairs without treating their contents as ordinary prose |
| `src-old/seon/repl/parse.cljc:132-379` | Rewrite heredoc syntax while maintaining a source-segment map |
| `src-old/seon/repl/parse.cljc:520-534` | Recognize comments without confusing their contents with code |
| `src-old/seon/repl/parse.cljc:542-621` | Back over comment preambles, choose recovery points by error class, exclude `[` and `{` as unsafe anchors, and require strict cursor advance |
| `src-old/seon/repl/parse.cljc:623-659` | Recover at token granularity so malformed prose cannot swallow a same-line form |
| `src-old/seon/repl/parse.cljc:759-798` | Preserve exact rewrite-clj node source; distinguish comments, whitespace, commas, and `#_` uneval nodes |
| `src-old/seon/repl/parse.cljc:804-849` | Classify EOF, unmatched delimiter, odd map, bad metadata, invalid token, and generic read failures, including nil and case-varied messages |
| `src-old/seon/repl/parse.cljc:851-862` | Suppress closer-only artifacts |
| `src-old/seon/repl/parse.cljc:868-895` | Find form source without truncating character, regex, or string literals |
| `src-old/seon/repl/parse.cljc:941-978` | Structurally read recovered forms with auto-resolved keyword support |
| `src-old/seon/repl/parse.cljc:1003-1159` | Emit successful forms and localized read events in order, then continue when a safe boundary exists |
| `src-old/seon/repl/parse.cljc:1241-1517` | Project declarations under the last explicit valid namespace and retain continuity across ordinary top-level forms |

The old test suite is a mined regression corpus, not incidental legacy
coverage:

| Test source | Coverage |
|---|---|
| `test-old/seon/repl/parse_test.cljc:27-313` | Basic forms, comments, namespace projection, dependency order, exact source, and spans |
| `test-old/seon/repl/parse_test.cljc:315-592` | Forms-versus-prose policy and reader macros |
| `test-old/seon/repl/parse_test.cljc:593-830` | Recovery, error classes, accepted reader forms, orphan closers, no inner-form leakage, and termination |
| `test-old/seon/repl/parse_test.cljc:837-1002` | Same-line token recovery and literal-aware character, regex, string, and comment handling |
| `test-old/seon/repl/parse_test.cljc:1015-1088` | Real agent-output failures and form-source regression cases |
| `test-old/seon/repl/parse_test.cljc:1091-1175` | Fence properties |
| `test-old/seon/repl/parse_test.cljc:1188-1362` | Heredoc segmentation, Unicode, and byte-faithful CRLF payloads |
| `test-old/seon/repl/parse_test.cljc:1364-1390` | Closed option-schema regressions |

### Repair is a separate mechanism

`src-old/seon/repl/parse/repair.cljc:1-28,292-334` implements an intentionally
narrow, Parinfer-like indentation repair. It accepts a repair only when the
source changed and the result re-reads. Its tests preserve real failed
episodes, key order, idempotence, and rejection of unchanged or still-invalid
source (`test-old/seon/repl/parse/repair_test.cljc:58-189`).

Symbol repair and repair-candidate generation are likewise speculative
pre-processing, not reading. They must not become an implicit fallback inside
`seon.sci.reader`. If any repair capability survives, it should remain an
explicit pre-plan transform whose result is ordinary source passed through the
one reader again.

### Bug-fix history

Each behavior-bearing commit below records a failure class the old parser
learned to handle. Pure moves and checkpoint commits are omitted.

| Commit | Bug class learned |
|---|---|
| `676baf051` | Replace stop-at-first-error and lossy `pr-str` reconstruction with lossless per-form rewrite-clj parsing |
| `dff66511e` | Markdown code fences leaked presentation syntax into parsing |
| `2093b0ea8` | Bare narration atoms, commas, and odd quote/prose sequences poisoned later forms |
| `7c7a9ff00` | Multi-form replies needed localized error classification and delimiter-aware continuation |
| `9dc4848a6` | Comments and prose in real transcripts needed distinct treatment |
| `a0ca1cc10` | Data literals and form/prose policy produced false executable forms |
| `3a4d761dc` | Error spans and `#_` discard nodes needed explicit handling |
| `226718fde` | Error classification depended on exception-message casing and platform-specific token text |
| `a900b3474` | Orphan closing delimiters became executable artifacts |
| `3639d1685` | Broad recovery anchors shredded malformed input; only proven `(` or comment anchors were safe |
| `b5287550b` | Hand source scanning truncated character, regex, and string literals |
| `5c8a324fa` | A nil exception message crashed error classification |
| `bde2c6a8d` | Backtick prose and EOF recovery could leak an inner form from an unclosed outer form |
| `e182c02b7` | Recovery without a strict-advance invariant could loop forever |
| `1fe6a5e23` | Standalone result references and delimiter-like comment text were misclassified |
| `fb2340169` | Structural re-read failed on auto-resolved `::` keywords |
| `4ed0f793b` | Heredoc rewriting lost exact source and spans |
| `804758181` | Provider reply normalization altered raw source evidence |
| `824575c40` | Namespace projection needed one parse and dependency-aware grouping |
| `c292ee2d8` | Multiple eval namespaces required deterministic dependency order |
| `3a0dbd313` | Parser schemas admitted shapes the implementation did not actually support |
| `f49268cdb` | A qualified option key was rejected at the boundary |
| `793a8ea67` | Forwarding non-parser options caused complete live batch failure |
| `6b38f1569` | Line-level recovery let prose such as `/etc/hosts` swallow a valid same-line form |

The important lesson is not to port rewrite-clj or its message-string
classifier. It is to preserve the semantic invariants and the regression
examples while implementing them through SCI/Edamame.

## The fresh SCI reader

### What it handles well

`src/seon/sci/reader.cljc` establishes the right single-reader foundation:

- size admission defaults to 1 MiB (`:8-10`);
- tag admission is explicit and total, and `#=` is refused rather than
  evaluated (`:20-39,98-114`);
- aliases, refers, syntax quote, reader features, and current namespace are
  explicit reading context (`:63-114`);
- `ns` declarations and require clauses become ordinary facts
  (`:116-186`);
- `defn` and `defn-` declarations lift arglists, documentation, privacy,
  schema, and direct `:io`/`:compute` workload metadata (`:196-241`);
- test declarations become facts (`:243-262`);
- every successful event carries consumed and source positions
  (`:298-355`); and
- the public function returns flat error values rather than throwing
  (`:357-422`).

These are deliberate improvements over the old parser:

- SCI/Edamame, rather than a second Clojure reader, decides valid syntax.
- `:read-cond :allow` and explicit `:features` preserve reader conditionals.
  The old top-level reply policy could demote valid `#?` forms.
- tagged literals cross one admitted tag function.
- read-time evaluation is explicitly refused, closing the failure documented
  in `docs/seon/issues/archive/tools-reader-evaluates-agent-source-at-read-time.md`.
- namespace and declaration attribution happens during the single read rather
  than through a later reparse.
- workload metadata is lifted at the declaration boundary. This is direct
  metadata only; reachability-derived workload classification has not yet
  landed elsewhere in fresh source.

### What the self-seeding suite actually proves

`test/seon/sci/reader_test.clj` has 10 tests. Its first test dynamically
enumerates every `src/**/*.clj[c]` file; the current corpus contains 42 files.
There is no separate 42-file fixture directory.

| Fresh test region | Proven property |
|---|---|
| `test/seon/sci/reader_test.clj:71-83` | Every current source file reads deterministically, successful event sources re-read, and consumed spans are gapless |
| `test/seon/sci/reader_test.clj:85-118` | Atom events, basic spans, and UTF-16 cursor behavior with emoji |
| `test/seon/sci/reader_test.clj:120-172` | Admitted/refused tags and refusal of read-time evaluation |
| `test/seon/sci/reader_test.clj:174-203` | Explicit aliases, refers, syntax quote, and reader features |
| `test/seon/sci/reader_test.clj:205-255` | Flat oversize, unreadable, refused-tag, and invalid-request values |
| `test/seon/sci/reader_test.clj:257-306` | Parse-time namespace transitions and deliberate fail-closed attribution |
| `test/seon/sci/reader_test.clj:308-311` | Empty-source cardinality |
| `test/seon/sci/reader_test.clj:313-353` | Declaration-fact lift for one real Var and synthetic namespace/function/test forms |
| `test/seon/sci/reader_test.clj:355-403` | Enumerated remaining reader sites prevent an unnoticed second general reader |

This suite proves valid-source breadth, but its live-source corpus contains no
malformed forms. It also compares successful event source by re-reading the
returned text, which cannot detect that CRLF was normalized. The explicit
Unicode test covers surrogate-pair cursor columns but not CRLF or lone CR.

## Robustness diff

| Failure class | Old parser | Fresh reader | Merge status |
|---|---|---|---|
| Malformed form between valid forms | Local read event; later safe forms retained | One flat whole-input error; all events discarded | Missing |
| Unclosed form at EOF | Tail isolated; tests prevent execution of an inner form | One flat whole-input error | Safety outcome retained, localized evidence missing |
| Orphan closer | Suppressed while valid neighboring forms survive | One flat whole-input error | Missing |
| Invalid token or odd map | Classified and localized; safe continuation | Generic unreadable result | Missing |
| Malformed metadata | `:bad-metadata` read event with neighbors retained | Generic failure; some Edamame/JVM failures lack line and column | Missing |
| Reader conditionals | Historically vulnerable to demotion by reply policy | Explicit `:read-cond :allow` and features | Fresh behavior is better |
| Read-time evaluation | Separate readers could execute `#=` while inspecting | Explicit refusal | Fresh behavior is better |
| Accepted tags | Mixed parser and policy behavior | One explicit admitted tag function | Fresh behavior is better |
| `#_`, commas, chars, regexes, strings, comments | Broad mined regression corpus and exact-node source | SCI accepts them, but the old adversarial corpus was not carried forward | Behavior likely present; proof missing |
| Prose token before same-line form | Token-granular recovery | Reply has a second prose recovery heuristic; reader itself fails | Split mechanism; not fully merged |
| Partial forms | Local evidence and guarded recovery; strict progress | Whole-input failure | Missing |
| Exact original source | rewrite-clj node strings and mapped heredoc segments | SCI buffer source, which normalizes CRLF | Regressed |
| Unicode positions | Covered with source and heredoc regressions | Explicit UTF-16 emoji test | Fresh proof is clearer |
| CRLF/lone-CR positions | Byte-faithful payload tests | No test; direct CRLF failure | Regressed and recurring |
| Namespace continuity | Last valid explicit namespace persists across ordinary forms | A hard-coded stable-operation set invalidates attribution after any other call | Deliberately different; design conflict |
| Declaration/workload attribution | Later projection; no workload lift | Parse-time facts and direct workload metadata | Fresh behavior is better |
| Termination | Strict cursor advance under recovery | SCI loop terminates, but no recovery path exists | Fresh valid path is sound; recovery invariant must return |

## Recurrences and independent findings

### Direct executable comparison

| Input | Old parser | Fresh reader/current reply |
|---|---|---|
| `"(a)\n(+ 1 3x)\n(b)"` | Form, localized `:invalid-token`, form | One unreadable result; both forms lost |
| `"Got denial /etc/hosts now.(good)"` | Recovers `(good)` at token granularity | Fresh reader fails; reply's separate heuristic happens to recover `(good)` |
| `"(defn foo []\n;; do the thing\n  (bar)"` | One read failure; does not leak `(bar)` | One unreadable result; no leak |
| `"(a)\n}\n(b)"` | Two forms; orphan closer suppressed | One unreadable result; both forms lost |
| `"(a)\n^123 (foo)\n(b)"` | Form, localized `:bad-metadata`, form | One unreadable result, without reliable line/column in the observed JVM failure |
| `"#?(:clj (a) :cljs (b))"` | Old reply policy emitted no executable form | Fresh reader correctly emits the selected `(a)` form |
| `"; note\r\n(+ 1 2)\r\n"` | Old source machinery preserved exact line endings | Fresh event source is LF-normalized while offsets address CRLF; current reply adds a bogus `"; )"` source |

The CRLF result is a current defect, not a theoretical gap. The reader reports
the original consumed end offset but supplies normalized source. Reply code
then computes a source end from the normalized string length, leaving original
characters to be interpreted as another fragment.

### Archived issue evidence

- `docs/seon/issues/archive/recovery-anchor-leaks-inner-form-from-broken-form.md`
  records the critical rule that EOF recovery must never execute a form nested
  inside a broken outer form.
- `docs/seon/issues/archive/prose-token-line-recovery-swallowed-same-line-forms.md`
  records the live `/etc/hosts` token-granularity failure.
- `docs/seon/issues/archive/client-paren-balancer-vs-parse-forms.md` records
  truncation of character, regex, and string forms by a second scanner.
- `docs/seon/issues/archive/portable-namespace-metadata-parser-dropped-reader-conditionals.md`
  records a second parser dropping CLJC reader conditionals.
- `docs/seon/issues/archive/tools-reader-evaluates-agent-source-at-read-time.md`
  records read-time execution through the wrong reader boundary.
- `docs/seon/issues/archive/parse-forms-closed-options-broke-batch-turns.md`
  records total batch failure caused by forwarding the wrong option map.
- `docs/seon/issues/host-base-agent-surface-parity.md:165-181` still identifies
  the historical three-reader split being deleted.

No existing issue note found by this audit names the fresh reader's CRLF
source/span mismatch.

### Gemini review evidence

The recent independent reviews found two of the same seams:

- `tmp/reviews/20260729T020651.341Z.md:5-13` reports namespace loss after an
  ordinary call and a final-event source/end mismatch.
- `tmp/reviews/20260729T020700.646Z.md:3-8` repeats the namespace-attribution
  concern.
- `tmp/reviews/20260729T021750.348Z.md:30-34` identifies the fail-closed
  namespace allowlist as brittle.

The reported source/end concern is confirmed and sharpened here as a CRLF
normalization defect. A review claim that the initial reader dropped `::ns`
was not reproducible in either its original commit or the current source and
is treated as a false positive. Another review accepted the Unicode span test
but did not exercise CRLF.

## Exact merge list

### S1 — Make source and spans original-input exact

**Old evidence:** `src-old/seon/repl/parse.cljc:759-798,868-895,1003-1159`;
`test-old/seon/repl/parse_test.cljc:255-313,972-1002,1188-1232`.

Keep SCI's parsed value and cursor metadata, but derive every public
`::source`, `::source-start`, and `::source-end` from the original input.
Never use the normalized `parse-next+string` buffer as exact source evidence.
Make consumed versus source spans explicit; do not synthesize the final
consumed end by mutating an otherwise unrelated event.

Proposed tests in `test/seon/sci/reader_test.clj`:

- `original-source-preserves-crlf`: read
  `"; note\r\n(+ 1 2)\r\n"` and assert exact substrings, gapless original
  offsets, and no extra reply source;
- `original-source-preserves-lone-cr`: repeat with lone CR separators;
- `original-source-spans-compose-with-utf16`: combine emoji, CRLF, comments,
  strings, and two forms, then assert that every source equals the original
  substring at its declared offsets;
- a generative property joining all consumed slices recreates the original
  input exactly.

### S2 — Return localized read-error events without discarding valid events

**Old evidence:** `src-old/seon/repl/parse.cljc:804-849,941-978,1003-1159`;
`test-old/seon/repl/parse_test.cljc:593-830`.

Move the exception boundary inside the read loop. Retain already-read events,
emit one ordinary error event with exact original span and structured reason,
and continue only when recovery proves another top-level boundary. Refused
tags, read-time evaluation, oversize input, and an invalid request remain
whole-request refusals because they are admission failures, not localized
syntax mistakes.

The structured reason should preserve semantic classes:

- `:eof`;
- `:unmatched-delimiter`;
- `:odd-map`;
- `:bad-metadata`;
- `:invalid-token`; and
- `:read`.

Derive these from Edamame/SCI exception data wherever possible. Do not port the
old exception-message string table as the primary mechanism. The fallback
classifier must be total for nil or platform-varied messages.

Proposed tests:

- `malformed-middle-form-retains-neighbors` using
  `"(a)\n(+ 1 3x)\n(b)"`;
- `malformed-metadata-retains-neighbors` using
  `"(a)\n^123 (foo)\n(b)"`;
- `odd-map-retains-following-form`;
- `localized-error-always-has-original-span`;
- `nil-or-unknown-parser-message-is-generic-read-error`.

### S3 — Port the recovery invariants, not the old parser implementation

**Old evidence:** `src-old/seon/repl/parse.cljc:542-621,623-659,851-862`;
`test-old/seon/repl/parse_test.cljc:703-913`;
`docs/seon/issues/archive/recovery-anchor-leaks-inner-form-from-broken-form.md`;
`docs/seon/issues/archive/prose-token-line-recovery-swallowed-same-line-forms.md`.

Recovery must:

- advance strictly or terminate;
- suppress an isolated unmatched closer without converting it to a form;
- never treat `[` or `{` as a top-level restart anchor;
- never execute a form that may be nested inside an unclosed outer form;
- include an immediately preceding comment preamble with the recovered form
  when its boundary is proven; and
- skip only the failing token when Edamame supplies a precise token extent,
  so later same-line forms remain visible.

For EOF/unbalanced input, consuming the uncertain tail is safer than copying
the old column-zero heuristic blindly. The essential old behavior is absence
of inner-form leakage, not aggressive salvage.

Proposed tests:

- `unclosed-outer-form-never-leaks-inner-form`;
- `orphan-closer-does-not-hide-neighboring-forms`;
- `square-and-map-openers-are-not-recovery-anchors`;
- `recovery-cursor-strictly-advances` as a generative termination property;
- `invalid-token-does-not-swallow-same-line-form`, including `/etc/hosts`;
- port the real mined inputs at
  `test-old/seon/repl/parse_test.cljc:1015-1069` as reader event assertions.

### S4 — Settle namespace continuity without a hand list

**Old evidence:** `src-old/seon/repl/parse.cljc:1241-1517`;
`test-old/seon/repl/parse_test.cljc:105-245`.

**Fresh conflict:** `src/seon/sci/reader.cljc:264-296` and
`test/seon/sci/reader_test.clj:257-306` deliberately clear attribution after
an operation outside `namespace-stable-operations`.

The fresh reader is right to attribute declarations during the one parse, but
the hard-coded stable-operation set is a computed-truth violation and loses
the old parser's continuity after ordinary top-level forms such as
`schema/register!`. Static parse-time attribution should follow the last
explicit valid `ns` or literal quoted `in-ns`. A malformed explicit namespace
transition may clear attribution. An arbitrary call should not be presumed to
change namespace merely because its operator is absent from a hand list.

Evaluator namespace receipts remain the runtime truth and can detect a dynamic
namespace change that syntax alone cannot prove. This recommendation conflicts
with the currently sealed fail-closed test and therefore requires an explicit
owner ruling before implementation; it must not be slipped in as a test-only
change.

Proposed tests:

- `(ns a)(schema/register! ...)(defn x ...)` attributes `x` to `a`;
- `(ns a)(arbitrary-call)(defn x ...)` retains static attribution to `a`;
- literal `(in-ns 'b)` changes attribution to `b`;
- computed or malformed `in-ns` produces no invented namespace;
- evaluator receipt disagreement is observable without reparsing source.

### S5 — Carry the old lexical and metadata corpus into the one reader

**Old evidence:** `test-old/seon/repl/parse_test.cljc:681-695,972-1002,1071-1088`.

SCI already appears to handle valid `#_`, commas, character literals, regexes,
strings, comments, namespaced metadata, and reader conditionals. Preserve SCI
as the implementation and port the adversarial examples as one table-driven
test. This is proof consolidation, not a second scanner.

Extend declaration-fact coverage beyond one Var:

- metadata before and after a function name;
- namespaced `:malli/schema`;
- `defn-` privacy;
- direct `:seon.workload :io` and `:compute`;
- invalid workload values omitted or rejected according to the declaration
  schema;
- reader-conditionals around declarations and inside `ns :require`;
- metadata containing strings, characters, regexes, and discarded forms.

The expected result is one SCI read, exact original source, and facts from the
selected reader feature only.

### S6 — Make the event contract exact and generated

**Old evidence:** `src-old/seon/repl/parse.cljc` history at `3a0dbd313`,
`f49268cdb`, and `793a8ea67`; archived closed-option issue evidence.

Define a closed request schema and a closed event/error schema in the owning
reader namespace. The schemas should distinguish admission errors from
localized read-error events and require original-source spans where meaningful.
Exercise them through the existing instrumentation/generative surface rather
than copying parser option maps into callers.

Proposed tests:

- generated request values either conform and return conforming events or
  return the one invalid-request value;
- every emitted span is ordered and within the original source;
- every successful declaration fact has a schema-valid ordinary-data shape;
- caller-only keys cannot leak into SCI/Edamame options.

## Behaviors that should not move into the reader

The merge is simplification only if the surviving reader stays a reader.

- **Reply prose classification and result references:** keep these at the
  reply-to-plan boundary. They decide what executes, not what Clojure syntax
  means.
- **Markdown fence policy:** current paired-fence normalization in
  `src/seon/cluster/reply.cljc:65-104` is a better presentation-boundary form
  of the old behavior. Do not teach the general reader Markdown.
- **Heredoc rewriting:** the old textual transform and segment map are not
  Clojure grammar. If heredoc survives, express it as an explicitly admitted
  tagged literal or remove it.
- **Streaming balance scanners:** do not restore the hand scanner that already
  failed on characters, regexes, and strings. SCI owns Clojure grammar.
- **Parinfer and symbol repair:** keep any surviving repair explicit,
  pre-plan, and re-read its result through `seon.sci.reader`.
- **Namespace dependency reordering and schema-first execution:** these are
  program-analysis or execution-plan responsibilities, not read semantics.
- **Eager CLJS/wire materialization:** this is old execution-boundary residue
  and has no place in the CLJ-only reader.
- **rewrite-clj as a second reader:** preserve original-source slices and
  recovery behavior without reviving the dependency as another syntax
  authority.

## Ordered implementation and proof boundary

The dependency order is:

1. exact original-source spans;
2. localized error-event contract;
3. safe progress-guaranteed recovery;
4. owner ruling and implementation for namespace continuity;
5. ported lexical, metadata, and real-failure corpus;
6. exact schemas and recurring generative properties;
7. delete reply-local parsing heuristics made redundant by reader events.

The final graduation gate is one `seon.sci.reader` call that:

- reads all current 42 source files through SCI;
- preserves every original byte/code unit in its consumed span partition,
  including LF, CRLF, lone CR, and Unicode;
- retains valid forms around a localized malformed form;
- cannot leak an inner form, loop, or invent a closer artifact;
- handles the old lexical and metadata regression corpus;
- preserves fresh reader conditionals, tag admission, read-eval refusal,
  namespace facts, and workload lift;
- supplies enough structured evidence for reply policy without a second
  Clojure scanner; and
- is the only general reader asserted by the standing source inventory.

Until those properties pass, the owner directive is not satisfied: the fresh
reader is the one owner, but it has not yet inherited the old parser's robust
behavior.
