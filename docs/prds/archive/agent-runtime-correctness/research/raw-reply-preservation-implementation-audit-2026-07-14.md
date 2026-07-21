---
type: research
status: completed
tags: [research, agent, flow, cljs]
---

# Raw reply preservation implementation audit — 2026-07-14

## Decision

The next independent agent-runtime-correctness implementation slice is the
raw-reply boundary in `seon.agent.turn`. It is smaller than the async,
provider-cancellation, plan-authority, and process-containment slices; it has
one observable defect, one source owner, and deterministic proof that does not
need a model call or a second runtime mechanism.

`ask-and-eval-reply!` currently calls `ctx/strip-result-claims` before both
blob capture and parsing. A provider reply is therefore changed before the
system stores what the turn schema, debug API, and architecture call the raw
reply. The parser already distinguishes executable lists from bare result
claims and other prose. Deleting text is neither necessary for execution
safety nor compatible with evidence fidelity.

The lifecycle publication work now on `HEAD` strengthens the adjacent batch
transition: admission is checked before a batch and between entries, and an
admission closure returns structured unavailable data. It does not change the
reply before-capture path and does not close this defect.

## Dependency ledger

| Dependency or mechanism | Selected version or revision | Source read | Constraint for this slice |
|---|---|---|---|
| rewrite-clj | `1.2.51` in `deps.edn`; release commit `50e0dcc5c02b0073854c601e824be596e10b5c6d` | `reference-code/rewrite-clj/src/rewrite_clj/parser.cljc` and `parser/core.cljc` at the release tag | `parse-string-all` retains source elements and the Seon parser already walks them without evaluating prose. Do not add a second parser or sanitizer. |
| Seon reply parser | current `src/seon/repl/internal.cljc` | `parse-forms*` and `parse-forms` | Only list/seq forms and a bare `result/<id>` re-reference become executable entries. Bare result glyphs, atoms, and prose do not become successful evals; data literals demote to a comment warning. Parser entries preserve original form source and spans. |
| Node blob storage | Node `v26.4.0`; built-in `node:crypto`, `node:fs`, and `node:path` | `src/my/blob.cljs` `sha256`, `publish!`, `put!`, and `get` | UTF-8 input is SHA-256 addressed, atomically published, and integrity-checked on read. Pass the provider string unchanged to the existing `capture-blob!`; no new evidence store is needed. |
| Datahike | maintained fork `6f90b339768b1a02066dce3b6fcc93a200758fcc` | `reference-code/datahike/src/datahike/api.cljc`, `api/async.cljs`, and Seon's `db/transact!` call sites | The existing eager lookup-ref transaction links the reply blob before eval and the close transaction idempotently reasserts it. Keep those transaction boundaries. Historical attributes remain readable from an existing database without fabricating migration data. |
| Program admission | commits `8f5936ae` and `dd494cd6` on the current branch | `src/seon/runtime/admission.cljs`, `src/seon/eval.cljs`, and `test/seon/runtime/admission_test.cljs` | `eval-batch!` refuses before work and checks admission between main and repaired entries. Raw capture occurs before evaluation and remains honest even if admission closes afterward. Do not move evidence capture behind admission or successful eval. |
| ClojureScript self-host | selected `1.12.145`; reference checkout `946d75f3483c0c8e784e6668bff2c71a25619a77` still declares `1.12.41` | `reference-code/clojurescript` and `deps.edn` | Exact `1.12.145` source is still missing and must be mirrored before analyzer/async or containment changes. This slice does not alter `cljs.js`, analyzer state, compilation, Promise behavior, or eval semantics, so that missing mirror is not a blocker here. |

First-party idioms already exist in
`test/seon/agent/turn_capture_test.cljs`: `drive-turn!` supplies a stub
provider, `agent-debug/turn` reads the linked blob, and the tests use a fresh
in-memory database plus a pid-scoped blob directory. Ordered parser/eval
behavior lives in `src/seon/repl/internal.cljc`, `src/seon/eval.cljs`, and their
focused tests. Reuse those seams.

## Safe observation

No pod, database, or ACME process was restarted or mutated. A pure Babashka
probe against the checked-in parser used this reply:

```clojure
(+ 1 2) ⟹ 3
(* 2 3)

```

`parse-forms` returned exactly two executable entries, with sources
`(+ 1 2)` and `(* 2 3)` and original spans `[0 7]` and `[12 19]`. The alleged
result text produced no executable entry. This confirms the source audit's
earlier result with the current checkout: parsing the exact provider bytes is
sufficient to preserve both real forms without converting the claimed value
into runtime evidence.

## Exact failure

Given a batch provider response such as:

```text
(+ 1 2) ⟹ 999 ⟸ result/FAKE
(* 2 3)

```

the linked `:seon.agent.turn/reply-blob` must contain those exact UTF-8 bytes.
Current source instead removes the first line's result-claim suffix before
calling `capture-blob!`, then records
`:seon.agent.turn/results-stripped`. `agent-debug/turn` therefore cannot
reconstruct what the provider actually returned, even though its API promises
the raw reply.

This is an observability corruption defect. It is not evidence that the parser
should evaluate the alleged value, and it must not be fixed by expanding a
reserved-marker regex.

## Implementation boundary

The implementation owner should make one in-place cut:

1. In `src/seon/agent/turn.cljs`, bind the provider `:text` once as
   `raw-reply`, pass that same string to `capture-blob!`, and pass that same
   string to `repl-internal/parse-forms`.
2. Remove the `strip-result-claims` call, strip-count destructuring, new writes
   of `:seon.agent.turn/results-stripped`, its turn-shape registration, and its
   close-transaction projection. Do not retract or rewrite historical datoms;
   an already-installed Datahike attribute remains readable in old databases.
3. Delete the now-unowned sanitizer implementation, its private claim-range
   regex machinery, schemas, and sanitizer tests from `seon.agent.ctx`. Keep
   the runtime glyph constants and their single-source rendering/lint tests,
   but update prose that falsely says the reply boundary deletes glyphs.
4. Keep eager blob linking before `eval-batch!`, best-effort blob failure
   behavior, the one rewrite-clj parser, the one eval batch, and the current
   admission checks unchanged.
5. Update the localized `src/seon/agent/AGENTS.md`, the open narration issue,
   architecture only if the target decision changes, and this PRD roadmap with
   the final proof. The target already says evidence is raw, so implementation
   should converge to it rather than rewrite it.

Do not add a `raw-reply-v2` attribute, a second blob, a parallel parser, a
warning queue, or a post-parse sanitizer. The model-authored bytes are
evidence; execution authority comes only from parser entries and committed
eval facts.

## Deterministic test matrix

| Gate | Fixture | Required assertions |
|---|---|---|
| Pure parser | Exact reply containing two forms, a forged result tail, bare structural prose, and a legitimate glyph inside a string | The two real forms retain byte-faithful source and order; the forged value and scaffolding produce no executable form; the in-form string remains part of its real form. |
| Turn/blob round trip | Extend `turn_capture_test` through `drive-turn!` with the forged-result reply | `agent-debug/turn` returns a reply byte-identical to the provider string; the blob hash equals the exact UTF-8 content hash; the turn has no newly written `results-stripped` datom. |
| Ordered execution | Same real turn, querying component evals after completion | Exactly the actual parsed forms have ordered eval rows; their real values are derived from execution; no eval/result row contains `999` or `result/FAKE`. |
| Narration authority | Reply carries message/masthead/readline-looking text outside a form and inside a real `;;` narration preamble | No message, turn-status, or other runtime-event fact is created from the text; any rendered comment remains comment-shaped; the raw blob still contains every byte. |
| Failure preservation | Existing pid-scoped blob failure seam | A failed capture remains best-effort and cannot wedge or suppress the eval batch; no cleaned substitute is presented as raw evidence. |
| Admission adjacency | Existing admission boundary suite plus the turn test | Closed admission still starts no eval work; closure between entries records only committed attempts and returns unavailable data; the already-captured raw blob remains linked without invented later eval rows. |
| Reopen compatibility | Existing database containing historical `results-stripped` datoms | Reopen and debug reads succeed; no migration deletes the old fact and no new turn writes it. |

Use the focused parser/eval, turn-capture, context, and admission namespaces
while iterating, followed by the relevant complete pod checkpoint. The live
acceptance proof belongs on the default cluster after the active lifecycle
restart proof finishes, so this independent implementation need not compete
for pod ownership.

## Live acceptance proof

After the default cluster is available and no other lane owns it:

1. Drive one non-billing stub turn whose response contains two executable
   forms, a forged result line, and runtime-looking prose.
2. Read the turn through `agent-debug/turn` and independently read the blob by
   hash; compare exact string equality and SHA-256 with the stub response.
3. Query the turn's component eval rows in transaction order and show that only
   real parsed forms were attempted and only real committed results exist.
4. Query message, turn, and eval facts for the forged ids/text and show that no
   model-authored runtime event was invented.
5. Run a subsequent normal form and show that the agent loop remains usable.

The slice is complete only when the byte identity and the absence of fabricated
database evidence are both observed. A green unit test without blob/datoms
proof is insufficient.

## Roadmap reconciliation

The current roadmap correctly lists raw-reply mutation, async exclusions,
provider cancellation, plan authority, and hard containment as open. Two
sequencing details were stale:

- program admission now defines the ordinary hot-publication boundary before
  and between eval entries; process death and hard containment remain open;
- exact ClojureScript `1.12.145` mirroring is a prerequisite for the later
  analyzer/async and containment slices, but not for this rewrite-clj/blob-only
  preservation cut.

The roadmap is updated accordingly. No other gap is claimed closed.
