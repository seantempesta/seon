---
title: S1 lookups and help, design review (Opus)
type: research
status: review
date: 2026-10-07
lane: review-s1-opus
---

# Review of `s1-lookups/design-notes.md` (lane review-s1-opus)

Reviewed at HEAD `e843f1d52`. Default is pid 75294, started 11:57, `:faulted`. Its top health item is
`seon.schema/call-with-projection-state` at 145 s max, tasks `d27179bab996` and `c4ece4f4eec9`. Probes ran as agent
`review-s1-opus` in SCI (namespaces `scratch.review-s1-opus` and `-fresh`) and in JVM mode for reads only. Root's
rulings on the design's §5 are applied: (2) qualified `clojure.repl/*` lookups return data; (4) the declared contract
in `doc` becomes its own slice; (5) `seon.agent.loop` is deleted in S1.

**Verdict: revise before approval.** The data functions and the use of SCI's walk are right, and the measured costs
hold. The installation section rests on a wrong trace: there is a fifth installer, and it is the one agents
actually hit. Rulings (2), (4) and (5) also invalidate parts of §4.1, §4.6 and §7. None of this needs a new design,
only a revised §1.1/§4.1/§4.6/§6.

## Findings, ranked

### 1. HIGH: the trace of a bare lookup is wrong; a fifth installer stores the declarations on namespace entities

§1.1 says that bare `doc` goes through `clojure.core/doc`, then the injected `seon.bootstrap/doc`, then
`program-doc-var`, and that four installers exist. Probes show two different resolutions:

- In a namespace that has a stored entity, `(get (ns-refers *ns*) 'doc)` is the `seon.bootstrap/doc` Var. The
  namespace's stored `:seon.ns/refers` holds `deftest`, `dir`, `doc`, `help` and `is` targeting
  `seon.bootstrap`/`clojure.test` (pull of `scratch.review-s1-opus`). Seven namespaces on default carry them:
  `my.agents.root`, `user`, and the `scratch.*` namespaces.
- In a fresh namespace, `(in-ns 'scratch.review-s1-opus-fresh) (meta (resolve 'doc))` answers
  `{:ns clojure.repl :name doc}`. That is `program-doc-var`, because `install-program-doc!` (`eval.clj:2146`) put it
  into `clojure.core` at acquisition. `clojure.core/doc` is therefore NOT the `seon.bootstrap` copy after acquisition.

The producer: `seon.fn/runtime-analysis-batch` adds `interpreter-refer-entities` to the analysis `ns` form
(`fn.clj:976-1001`). The analysed namespace entity stores them (`fn.clj:252`). Namespace installation then refers
them (`eval.clj:825-848`, which installs a refusing Var for a missing target). The result is a stored, per-namespace
snapshot of `seon.sci.binding.edn`.

Consequences for S1:

- `refer-declared-bindings!` into `clojure.core` is not what most agent namespaces resolve through.
- The three new declarations will be missing from every existing namespace's stored refers until it is re-analysed.
- If any target is renamed or deleted (finding 2's option B, or S4 emptying `seon.bootstrap`), bare `doc` in
  `user`/`my.agents.root` becomes a refusing Var.

Fix at the producer: never store interpreter refers on the namespace entity, and use them for analysis only. Then
the `clojure.core` refer really is the one installer. Task filed:
`seon.fn/analysis-stores-interpreter-refers-on-namespace-entities` (`19fd54137a74`). Whether write-back also writes
these refers into a file's `ns` form is UNVERIFIED.

### 2. HIGH: ruling (2) removes the premise for §5.1's rejection; reconsider where the lookup Vars live

The design keeps qualified `clojure.repl/doc` as SCI's printing form. It puts the data forms in `seon.bootstrap` so
that the analysis edge (`seon.bootstrap/doc`) matches evaluation. Under ruling (2), `clojure.repl/doc` must also be
the data Var. Two options:

- **A (design + one `assoc-in` per name):** one Var with two names. The bare call analyses as `seon.bootstrap/doc`;
  the qualified call analyses as `clojure.repl/doc` (clj-kondo's knowledge of Clojure's macro) but runs Seon's.
- **B:** the lookup macros are SCI macro Vars in SCI's own `clojure.repl` (SCI already owns that namespace, and
  `install-program-doc!` does exactly this today). They are installed once in `build-base-ctx`'s `sci/init`
  `:namespaces` and no longer per acquisition. The declarations target `clojure.repl/*`. Bare and qualified are then
  one name, as in a Clojure REPL where `user` refers `clojure.repl`. `seon.bootstrap/doc` and `dir` are deleted.
  Cost: `base-context-injected-symbols` copies the host printing macros only for them to be overwritten. Option B
  either accepts that or skips targets in a namespace SCI provides (a few lines).

B also decouples S1 from `seon.bootstrap`, whose other contents (`situation`, `run-id`, `task-message`, seeds) are
the system turn that S4 deletes. Under the design, S4 would have to move the lookups again and retarget the
declarations, hitting finding 1's stale stored refers. B requires finding 1's fix first, or a reset after landing.
Root's call. I lean B.

### 3. HIGH: ruling (4) invalidates the `render.ns/directory` conversion and its cost claim

§4.6 converts `seon.render.ns/directory` to `documentation-value` per public, "≈ N × 1 ms; contract compile gone".
That holds only with §4.4. With the split, each row's `:contract`/`:in`/`:out` is still
`agent-documentation-contract`. JVM measurements on default (`directory-value db 'seon.task true`, 31 functions):

| call | ms | allocated |
|---|---|---|
| first | 62 | 90 MB |
| second | 41 | 48 MB |

A per-public `documentation-value` loop keeps that cost and adds N pulls: a DB read in a loop (lane-rules
mistake 8).

Simplest: in S1, the namespace render drops contracts. `seon.repl/render-directory-ai` already discards them and says
"Contracts are one `(doc sym)` away". The render reads `[:sym :arglists :summary]` from ONE pull, by extending
`public-functions`' existing reverse pull (`ns.clj:219-232`) with `:seon.fn/doc :seon.fn/arglists`. §7's
`(doc seon.task/create!)` row ("< 2 ms first, no compile") also no longer applies to S1. Restate it as unchanged
(first 20 ms / 9,407 calls, warm 2-3 ms).

### 4. MEDIUM: `doc` of a program function keeps a second arglists source and a second read

`dir` takes arglists from SCI var metadata. `doc` keeps `function-doc-map`, whose `arglists-reader` re-reads the
stored `:seon.fn/arglists` text through `seon.sci.reader/read` on every call (`eval.clj:1906-1921`): a second read of
text the analysis already read.

SCI metadata already carries `:doc` and `:arglists` for compiled program Vars, library Vars and an agent's own
`defn`. Probe: `(meta #'r1-probe)` → `:arglists ([x])`. JVM equality of host and DB arglists for
`seon.task/create!` is `true`. Proposal: `doc` takes summary, body, example and arglists from SCI metadata for every
Var. It reads the DB only for what SCI lacks: the contract, supplied keys, the override note, and a test's or task's
status. That deletes `arglists-reader`'s use in `doc`.

Note for the split contract slice: `sci/copy-var*` drops `:malli/schema` (`sci/core.cljc:~120-136` keeps only
doc/arglists/macro/dynamic/private/tag/file/line/column). Compiled program Vars therefore show no contract in SCI
metadata (`(keys (meta #'seon.task/create!))` → `(:ns :name :doc :arglists :file :line :column)`), while an agent's
own `defn` keeps `:malli/schema`. The DB `:seon.fn/spec` read stays the contract source unless `copy-host-var`
carries the schema. That is a question for that slice, not S1.

### 5. MEDIUM: `(help)` as a vector of strings escapes every example; forms are data

Probe: a help line naming `(apropos "text")` prints as `(apropos \"text\")`, and the `my.message/send` line prints
`{:my.message/to \"root\" …}`. The same content as quoted forms prints clean and runnable:
`{:lookups [(doc x) (source x) (dir ns) (apropos "text") (find-doc "regex")] …}`.

The design's class regression ("every call head in help's lines resolves") would also have to parse prose strings in
a test, which is a second reader. Proposal: `help-value` returns data whose examples are quoted forms, with short
prose only where a sentence is needed. The regression then walks the forms and resolves each head with no parsing.
This is CLAUDE.md "Return data, not comments", and it costs the same.

### 6. MEDIUM: production producers emit `(dir 'ns)`, which fails today and under the design

`(dir 'seon.task)` refuses on default ("expected a symbol, got a sequence", 19 ms). It still has the
`:example RW` garbage (existing task in `seon.print.value.edn`). Two producers emit it:

- `seon.render.ns/namespace-form`, `ns.clj:81`: `(list 'dir (list 'quote ns))`, the namespace render's form face;
- `seon.run`, `run.clj:84`: `'(dir 'my.turn)`.

Existing task: `seon.turn/generated-opening-reads-fail-when-evaluated-as-written`. Clojure's `dir` also rejects a
quoted name, so fix the producers, not `dir`. S1 already touches `render/ns.clj`, so convert both in the slice and
add a row asserting that the namespace form evaluates. Tests asserting the quoted shape:
`my/turn_test.clj:82` and `render/history_test.clj:68,167`.

### 7. MEDIUM: ruling (5) needs more deletions than listed, and contradicts walk-delete's seam

Deleting `seon.agent.loop` (230 + 75 test lines) also requires:

- `resources/seon/schemas/seon.agent.loop.edn`, a schema retirement adopted on a live-store branch per spec §6;
- the task intents naming it (`resources/seon/tasks/seon.task.edn:15`, `seon.bootstrap.edn:13`);
- `docs/seon/architecture/reference-code.md:48`.

`walk-delete/design-notes.md:24,108,134` and `walk-delete-notes.md:12` still say "loop-pass's seam puts
`seon.agent.loop/context` where the prompt was". Root should mark that stale in walk-delete when S1 lands; the
approved spec's S4 seam is the proc-held transcript.

### 8. MEDIUM: `(doc task)` adds a third "task as data" shape

§4.3 adds `task-doc {:sym :intent :subjects :proofs}`. `:seon.task/task` already declares an AI render pair,
`seon.render.ns/task-ai` (`seon.task.edn:24-27`; id, sym, kind, ns, step summary, intent, must-read, steps), plus
`task-form` (the `task-edges` requery). `held-task-ai` is a second shape. Simplest: `(doc task-sym)` returns the
pulled task value with its proofs' status. The schema's declared pair renders it. Any field an opening needs is added
to `task-ai` once, not built beside it. Question for root: does a task's `doc` need `:subjects` at all, given that
`task-edges`' `or`+join cost (5-15 ms, up to 14.8 MB cold) is what puts the opening over budget (§7)?

### 9. LOW: `source` output is still escaped inside the string

Probe: `(clojure.repl/source-fn 'seon.id/symbol-in)` prints multi-line, with the source-display printer change
installed, but docstring quotes show as `\"` and nested escapes as `\\\"result\\\"`. This is the opening's
references' `source`, so every assignment sees it.

It is a general printer question (strings that are source), not S1's. `function-ai`'s docstring claims its
`:source-lines` are "text the printer shows unescaped". No printer code does that (`rg source-lines|unescaped`
finds only hiccup's raw wrapper), so that claim is false today. Root should rule before S1 teaches `(source x)` as
the way to read code.

### 10. LOW: the test list misses reaching tests and fixtures

Not in §1.4/§6:

- `agent_situation_test.clj:49-53` (`(macroexpand '(seon.bootstrap/help))`);
- `cluster/program_restart_test.clj:324` (`(macroexpand-1 '(help))`);
- `turn_work_test.clj:390`, `rereads_test.clj:140` (`(doc my.task/done!)`);
- `sci/render_source_test.clj:55`, `my/turn_test.clj:82`, `render/history_test.clj`;
- the fixtures `reader_fences.edn`, `run4_replies.edn`, `forgive_replies.edn`, `schema/datahike_parity.edn`;
- `resources/seon/tasks/seon.documentation.edn:4` (must-read `seon.bootstrap/help-value`, whose signature changes).

Cross-design: S2a (`s2a-views/design-notes.md:189-191`) converts
`web_debug_test/passive-ledger-never-reconstructs-directory` around `directory-value`/`render-directory-ai`, which
S1 deletes. Root should order the two slices or give that test to one of them.

## Verified as stated

- Installer lines `eval.clj:348-433`, `2079-2150`, `2391`, `2566-2568`. The `seon.bootstrap` re-add at :425-429
  duplicates the `injected-namespaces` entry.
- `help-value`'s three reads: 19.7 ms and 20.2 MB on JVM (design: 17 ms, 19.6 MB).
- SCI `dir-fn`, `apropos`, `find-doc`, `source-fn` and `doc` at the cited `namespaces.cljc` lines, with the behaviour
  described. `apropos "a"` returns 1,255 symbols in 4.2 ms in SCI.
- `my.program/source`, `nearest` and `file-line` have no `src` caller except help's text. `my.program/declaration`
  stays.
- No load cycle: `seon.bootstrap` → `seon.turn` → `seon.sci.eval` already.
- Test vars do not resolve in SCI (`(resolve 'seon.help-test/help-evidence-tracks-its-code-version)` → nil while
  the namespace exists), so `doc` of a test needs the DB as designed.
- Every head in the proposed help lines resolves (`my.test/check`, `my.message/send`, `seon.db/q|pull|transact!`).
  Bare `source`/`apropos`/`find-doc` do not resolve yet.

## Concrete simplifications (summary)

1. Stop storing interpreter refers on namespaces; then one `clojure.core` refer installs all bare names (finding 1).
2. Option B: the lookup Vars live in SCI's `clojure.repl`; declarations target them; `seon.bootstrap/doc|dir` are
   deleted (finding 2).
3. Namespace render: one pull, no contracts, no `documentation-value` loop (finding 3).
4. `doc` metadata from SCI for every Var; DB only for contract, supplied keys, tests and tasks (finding 4).
5. Help as data with quoted forms (finding 5).
6. `(doc task)` returns the task value under its declared render pair (finding 8).

## Questions for root/owner

1. Option A or B for where the lookup Vars live (finding 2)?
2. Land the stored-refers fix (`19fd54137a74`) before S1, or reset after S1 lands?
3. Does a task's `doc` carry `:subjects` (the `task-edges` cost) or only intent, must-read and proofs?
4. Printer ruling on source strings (finding 9) before help teaches `(source x)`?

## Health and limits

- Created task `seon.fn/analysis-stores-interpreter-refers-on-namespace-entities` (`19fd54137a74`). Linked tasks:
  `seon.turn/generated-opening-reads-fail-when-evaluated-as-written` and the `seon.print.value` generated-example
  task.
- This lane ran no prototype code in default's JVM: JVM mode was used only for reads and the task creation. SCI
  probes defined `r1-probe` and `r1-contracted` on my own branch.
- Retiring my branch failed. `(my.agent/retire-branch! {:seon.store/branch :agent-919d428a7feb})` answered the
  message-only `"run transition refused: admission-closed"`, and `d/branches` still lists the branch. Existing
  tasks `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` and
  `a-refused-retire-leaves-the-agent-admission-closed` cover this. Root should retire `:agent-919d428a7feb`.
- Unverified: write-back of stored interpreter refers; whether option B's per-var merge in `sci/init` needs the
  injected-symbols skip. No restart or reset needed from this lane.
