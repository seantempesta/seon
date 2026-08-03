---
type: research
status: complete
tags: [research, runtime, sci, architecture]
---

# Parse-primitives plan falsification (2026-07-29)

## Verdict

**DO NOT SEAL `ea9d4cea6` as written.**

| Rank | Count | Meaning |
|---|---:|---|
| **SEAL-BLOCKING** | 4 | The contract or slice cannot satisfy its own stated exit |
| **REVISION** | 3 | The design can stand after the plan names the actual mechanism |
| **NOTE** | 3 | Verified claim or conditional coherence, with no blocking defect |

The load-bearing SCI claim survives: caller opts really do override all five
named ctx/default options. The plan still fails its explicit-context test
because `:readers {}` does not refuse Edamame's built-in tags and `:read-eval`
is not made explicit. Its S3 recovery claim also lacks enough durable input to
reconstruct aliases/refers, and the read-event-only evaluator has no event for
the comment-only plan sources the revised splitter deliberately preserves.
Decision 11 leaves that input-side source behavior unchanged but forbids a
renderer from displaying the comment-only receipt as output.

Cross-plan answer: **one accepted-code reader can serve generate-code, eval/N5,
and agent-authored UI code without contradiction, but these three plans do not
compose yet.** The UI's inbound message body is plain message text and must
remain outside the code reader. Generate-code and parse-primitives agree on the
name and ref shape of `:seon.cluster.run.form/ns`, but disagree about what that
one fact is sufficient to reconstruct. The reader can become the one parser
only after the blockers below are resolved.

## Dependency ledger and reviewed state

| Dependency or mechanism | Reviewed revision | Source used |
|---|---|---|
| target plan | `ea9d4cea65da71fc199119ecd16fedd91fa8664a` | `plan/parse-primitives-plan-2026-07-29.md` |
| SCI | `8fac6e88f32d53a5fd82ebe80640881e317b84fd` | `reference-code/sci/src/sci/impl/parser.cljc:42-51,126-190`; `sci/core.cljc:352-400` |
| reply splitter | `7d32ecec535a5a94010f37204f8b7f7b5c89e293` | `src/seon/cluster/reply.cljc:111-319`; `test/seon/cluster/reply_test.clj` |
| generate-code v0 | `aa8f2c24f68abb3412d2ee8e214e4e5e0af1fce1` | `plan/generate-code-v0-plan-2026-07-29.md:120-176,289-352,461-474` |
| UI conversion | `4e9ca56ee8ca12b78b2b736fe4f9f9a2f096c97f` | `plan/ui-conversion-plan-2026-07-29.md:39-50,88-188,391-444` |
| live freeze/fold/resume | current worktree over the checked-out branch | `src/seon/cluster/run.cljc:365-411`; `cluster/loop.cljc:357-367,524-547,689-873`; `cluster/work.cljc:96-124,244-291`; `schema/run.edn:40-70,93-96` |
| live evaluator | `7bb7ccbfefbb2e183ee8cbcc60bddb8d56753e94` plus later unrelated callers | `src/seon/sci/eval.clj:292-390`; `src/seon/schema/eval.edn:7-22` |
| test discovery and dev readers | current worktree | `bin/test:51-88`; `bin/seon-hook:152-165`; `script/seon/dev/test_roots.clj:28-70`; `script/seon/dev/mcp.clj:332-361` |

The shared worktree changed during review. The only uncommitted
`cluster/loop.cljc` hunk observed was in streamed-partial terminal handling,
outside freeze/read/resume. No source file was edited by this review.

## Seal-blocking findings

### SB1 — the declared refusal set is not closed

The plan is correct about option precedence
(`parse-primitives-plan:29-32,46-53`): `parse-next` constructs ctx-derived
options and then executes `(merge opts)` last
(`sci/impl/parser.cljc:142-168`). A direct `clojure -M:dev` probe proved every
named override:

```clojure
{:features :from-clj
 :auto-resolve [:override.ns/k :alias.ns/k]
 :syntax-quote (quote override.ns/x)
 :readers-refused {:message "No reader function for tag foo/bar"}
 :readers-override [:opts 1]
 :read-cond-preserve #?(:clj :from-clj :cljs :from-cljs)}
```

That positive result does **not** prove the plan's stronger statement that
`":readers {} refuses every tag, everywhere"`
(`parse-primitives-plan:67,78-83`). The same probe with caller
`{:readers {}}` returned:

```clojure
{:inst #inst "2020-01-01T00:00:00.000-00:00"
 :uuid #uuid "00000000-0000-0000-0000-000000000000"
 :unknown {:message "No reader function for tag foo/bar"}
 :eval {:message "EvalReader not allowed when *read-eval* is false."}}
```

Edamame's built-in `inst` and `uuid` readers remain accepted. Supplying explicit
`inst`/`uuid` handlers overrides them; an empty map does not remove them.

`#=` is a second hole in the same closed-set claim. SCI derives
`:read-eval` from its dynamic `read-eval` var at
`sci/impl/parser.cljc:163-166`. Caller opts could override it, but the proposed
reading-context input does not carry that policy and the plan never says the
new reader installs a fixed rejecting function. Therefore "nothing about the
reading context is ambient" is false as specified.

Before seal, replace “empty readers means refuse all” with an explicit accepted
tag contract:

- rule on `inst` and `uuid` rather than inheriting Edamame's choice;
- install explicit handlers or explicit rejection for every built-in tag;
- always pass an explicit rejecting `:read-eval`; and
- put the complete accepted/refused set in the S1 property, including built-ins
  and a ctx whose readers/read-eval policy deliberately disagrees.

### SB2 — resume cannot reconstruct the same explicit context

The current durable and live path is narrower than S3 assumes:

1. `run/plan-call` commits form id, run, ordinal, and source only
   (`run.cljc:391-411`; `schema/run.edn:59-70,93-96`).
2. `work/next-ordinal` derives the first form without a terminal receipt
   (`work.cljc:96-124`).
3. `loop/turn :resume` creates a fresh `sci/fork`, queries only that ordinal's
   source, and calls `evaluate` with source plus agent id
   (`loop.cljc:689-749`).

S3 proposes to add only `:seon.cluster.run.form/ns`. That makes the evaluation
namespace queryable, but it does not reconstruct the reading context promised
at `parse-primitives-plan:61-74`: aliases, refers, features, accepted readers,
and the size policy. In particular, `::alias/kw` and syntax quote in form N
depend on aliases/refers established by an earlier namespace declaration. A
fresh read of form N using only its source and `/ns` can produce a different
form or a refusal.

The generate-code plan calls `/ns` “the entire schema delta” and says it is a
pure function of “the source”
(`generate-code-v0-plan:136-149`). Namespace in effect is not a function of one
form source; it is a function of the ordered plan prefix plus the starting
reading context. The plans agree on the fact's name and ref shape, but not on
the inputs required to derive it or resume from it.

Before seal, choose and specify one recovery construction:

- **Recommended:** persist sources plus `/ns`, then on cold resume re-read the
  ordered plan prefix through the same reader from a frozen starting context,
  selecting the target event. This preserves generate-code's one-new-fact
  claim and re-executes nothing.
- Alternative: persist the complete per-form reading context. That adds facts
  and contradicts generate-code's current “one fact” claim.

Either choice must say where the starting namespace and reader-policy version
come from after agent namespace reassignment or config/code change. “Same
reader” is an implementation identity; deterministic recovery also needs the
same declared inputs.

### SB3 — a preserved prose-only source has no read event

Commit `7d32ecec5` intentionally changed the contract:

- prose is preserved as `;` comments;
- trailing or pure prose becomes a comment-only plan source
  (`reply.cljc:23-32,206-225`);
- SCI reads that source as nil today, so it records a normal form receipt
  without resolving prose tokens (`reply_test.clj:108-125`).

Decision 11 subsequently settled the presentation half: the parser may retain
and evaluate that input-side source exactly as before, but transcript/lesson
display omits a comment-only pseudo-entry. The source/receipt question below
remains relevant to the historical read-event-only evaluator proposal; it is
not authority to render the entry.

The focused current suite is green: **7 tests, 25 assertions, 0 failures,
0 errors**.

The proposed reader instead returns `[]` for empty/comment-only input
(`parse-primitives-plan:152-156`), and D2 requires `evaluate` to accept one read
event rather than source. A direct probe of `"; prose only"` returned EOF and
the buffered comment, not a form event. There is therefore no value S3 can pass
to `evaluate`, while S2 promises the reply suite stays unchanged.

Before seal, choose an explicit durable shape for preserved prose:

- attach all prose to an executable event, with a separate answer for a
  pure-prose reply;
- define a non-evaluated plan entry whose presence is distinguishable without
  a kind label; or
- deliberately synthesize a nil event and revise the reader's `[]`/cardinality
  contract.

The current plan implicitly chooses all three incompatible answers.

### SB4 — S3 cannot land under its owned paths or keep `bin/test` green

S3 changes the public request of `seon.sci.eval/evaluate`, but its owned paths
omit its actual schema owner, `src/seon/schema/eval.edn`, whose closed request
map currently requires `:seon.cluster.run.form/source`
(`schema/eval.edn:7-14`).

It also omits live tests and fakes that call the old request:

- `test/seon/sci/eval_test.clj:25-82` calls `evaluate` with source and asserts
  the old request schema;
- `test/seon/cluster/agent_test.clj:49` and turn-test fakes inspect
  `:seon.cluster.run.form/source`;
- `test/seon/cluster/turn_test.clj` selects the real evaluator in multiple
  integration cases.

`bin/test` discovers every `*_test.clj[c]` under `test/` directly from the
filesystem (`bin/test:51-88`), so no later S5 discovery change hides or repairs
these failures. S3 would leave the recurring gate red before its stated live
proof.

Add `src/seon/schema/eval.edn` and every affected evaluator/turn test to S3's
owned paths and make focused `bin/test` selectors an S3 exit. The kill-9 proof
is necessary but cannot replace the recurring request-contract tests.

## Required revisions

### R1 — prose classification is reader-assisted, not pre-read normalization

The plan says prose normalization occurs before reading
(`parse-primitives-plan:55-57,170-173`). The revised splitter does the opposite
for valid prose tokens:

1. SCI reads the raw text into events (`reply.cljc:111-133`);
2. `structured-code-indexes` and `code-event-indexes` classify those events
   (`reply.cljc:161-197`);
3. gaps are rewritten as comments and attached to code events
   (`reply.cljc:199-225`);
4. reader failures may cause one line to be commented, followed by another
   read (`reply.cljc:227-276,297-313`).

This still satisfies the important ownership rule: English policy remains in
`seon.cluster.reply`, and the general reader remains English-unaware. It does
not fit “normalization before reading” or S2's stated call order. Revise the
flow to **read → reply-policy classify → normalize → same-reader re-read when
needed**. The generic reader must expose ordered spans and flat error position
data; it need not learn English.

### R2 — the span probe partially succeeds; select the fallback

The requested `clojure -M:dev` probe used `sci/source-reader` and
`sci/parse-next+string` with:

```clojure
{:source true :location? (constantly true) :end-location true}
```

For lists, vectors, maps, sets, and symbols, metadata includes:

```clojure
{:source "(def x [1 {:a 2}])"
 :line 2 :column 1 :end-row 2 :end-col 19}
```

Caller options therefore survive SCI's normalized defaults. But numbers,
strings, keywords, booleans, and nil cannot carry metadata; every one returned
`nil` metadata. Spans do **not** come out totally.

There is a second fidelity limit: `parse-next+string` trims its buffered string
at `sci/core.cljc:387-390`. A probe over leading spaces, a comment, trailing
spaces, and blank lines returned `"; c\n42"` rather than the complete consumed
slice. `:source` metadata excludes the leading comment. A counter based only on
the returned source string is therefore not an exact-input span.

S1 must use reader cursor positions plus the original input to slice total
character spans, or another source-grounded cursor mechanism. It must also say
whether `start`/`end` are UTF-16 character offsets or UTF-8 byte offsets;
`max-bytes` and the plan's “exact bytes” wording currently mix the two.

### R3 — no API carries the hot-path events into freeze

S2 is still described as migrating `seon.cluster.reply/sources`, whose public
result is a vector of strings. S3 says the fold uses the events from that same
pass when available and freezes `/ns`, but `run/plan-tx` accepts only
`::sources` and `loop/freeze!` receives only the result of `reply/sources`
(`loop.cljc:524-547`; `run.cljc:365-411`).

Name the in-memory boundary value that carries both durable plan sources and
their read events through reply → freeze → first fold. Otherwise S3 must parse
again at freeze to obtain `/ns`, contradicting the hot-path parse-once claim.
This revision must compose with SB3's prose-only answer rather than adding a
parallel reply API.

## Notes

### N1 — the requested caller-option falsifier passes

For `:features`, `:auto-resolve`, `:syntax-quote`, `:readers`, and
`:read-cond`, caller opts win over ctx/default-derived values exactly as the
plan claims. There is no hole in the `cond-> … (merge opts)` precedence itself.
SB1 is about omitted and built-in acceptance policy, not merge order.

### N2 — the remaining slice order does not break hook/MCP discovery

Apart from S3's gate break:

- S1 has no callers, and `bin/test` discovers its new test immediately by
  path.
- S2 can be a behavior-preserving substitution once R1/SB3 are resolved.
- S4 may migrate the indexer while hook, test-root, and MCP validation retain
  their old readers until S5; that leaves temporary duplication, not a broken
  consumer.
- S5 correctly owns hook/test-root/MCP convergence. The io-prepl receiver read
  remains a transport read, as the plan states.

The hook currently accepts any tag and both CLJ/CLJS features
(`bin/seon-hook:152-165`), while MCP uses the JVM reader with
`*read-eval* false` (`mcp.clj:332-361`). S5 will expose behavior changes and
needs focused hook/MCP tests, but neither must be repaired before S4 merely to
stay live.

### N3 — the UI plan is coherent only because it is not a code-reader client

UI slice 1 says its web boundary “parses and commits,” but the parsed value is
HTTP form data containing a human message. That text is model input, not
accepted Clojure, so sending it through `seon.sci.reader/read` would violate the
reader's code-bearing boundary. Slice 3 controls likewise cross as structured
function refs and argument data; they do not need another source parser.

Agent-authored renderer functions do need the same accepted-code reader through
eval/N5. Thus the UI plan does not demand a rival parser. Its coherence is
conditional on keeping HTTP/data decoding separate and routing only authored
Clojure through the reader.

## Seal criteria after revision

1. The S1 refusal property proves the complete accepted tag and `#=` policy,
   including Edamame built-ins and a hostile ctx.
2. The span property covers atomic and collection forms and slices the original
   input without `.indexOf`.
3. Reply's green 7/25 behavior is represented in the event/plan shape,
   including pure and trailing prose.
4. A cold-resume test proves an alias-qualified keyword and syntax quote parse
   identically after losing the in-memory events.
5. The generate-code plan either keeps its one `/ns` fact with prefix re-read
   or admits the additional context facts; both plans state the same derivation.
6. S3 includes `schema/eval.edn` and affected tests and exits with focused
   `bin/test` green before the kill-9 drive.
7. UI message text remains plain data; agent-authored UI code reaches the same
   reader through eval/N5.
