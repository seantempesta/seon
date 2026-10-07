---
type: notes
status: active
created: 2026-10-07
tags: [notes, errors, agent-loop, guide]
---
# final-revision — notes (lane final-revision, 2026-10-07)

Documentation-only lane (Opus). Revised in place: `docs/seon/specs/errors.md`, `docs/seon/specs/agent-loop.md`,
`AGENTS.md` (= `CLAUDE.md`), `.agents/skills/data-oriented-clojure/SKILL.md`, `.agents/skills/clojure-testing/SKILL.md`,
`docs/seon/architecture/reference-code.md`. Built on the uncommitted edits of the earlier revision lanes; nothing
discarded. No source, schema or test edits.

## Broken or degraded first

- **Default is `:faulted`** (`bin/seon status` and MCP `runtime_status`, 2026-10-07, running `42e569d9d`): 14
  `:seon.problems/open-fault`, 173 errored receipts, 43 unknown tests, 3 definition regressions (top:
  `seon.db/pull-call` 65 → 262 µs, `seon.db/pull-logical` 65 → 131 µs), cache discipline warning on
  `:seon.test.runner/reach-declarations` (71% of cache bytes, 0% hits, 782 ms load). Not this lane's to fix; recorded
  here as observed. Heap used 2.49 GB of 11 GB.
- **`seon.agent.loop/system-message` costs 446 ms per build** (JVM probe, default, 15,676 characters, about 3.9k
  estimated tokens). Under one second, but it would be per pass if wired naively; the loop spec now makes it a
  `seon.cache` kind keyed by the toolkit's program identity (agent-loop §3).
- **Live-run defects with no task**, created on default with `seon.task/create!` (runtime tasks at the task home;
  a reset loses them, so root should carry them into task files once lane task-tags releases them):
  - `seon.ai/the-shipped-stop-sequence-cuts-replies-that-contain-it` (config/default.edn:438 ships
    `:seon.config.ai/stop ["#:seon.repl"]`; live value `"#:seon.repl"` [V]).
  - `seon.turn/a-markdown-heading-in-a-reply-is-read-as-code`: probe
    `(mapv :seon.cluster.eval/source (seon.turn/planned-sources "The fix is in `data-text`.\n## What\n(+ 1 2)" 'user 100000))`
    → `["## What\n(+ 1 2)"]` (the prose line became a comment; the heading stayed as source).
- **AGENTS.md cited a section that does not exist** ("Use focused verification"); now points to "Prove each slice
  before it lands".

## Probes (default, JVM mode, agent `final-revision`, read-only)

| Form | Value | ms |
|---|---|---|
| `(seon.agent.loop/system-message db "final-revision")` | 15,676 chars | 446 |
| stored `:seon.config.ai/stop` | `"#:seon.repl"` | — |
| `:seon.cluster.eval/origin` installed? | no | — |
| `:seon.eval/origin` | `:db.type/string` | — |
| `my.task/complete!` resolves? | no | — |
| `my.test/check` resolves? | yes | — |
| `planned-sources` on a Markdown heading | heading kept as source | — |

`git merge-base --is-ancestor 8e42e0889 42e569d9d`: the context producers are in the default JVM's source.

## Folded items: where each went

### Round-2 spec reviews (Opus `review-specs2-opus.md`, Astra `review-specs2-review2-astra-specs.md`)

| Finding | Disposition | Where |
|---|---|---|
| E-H1 P1 typing visible and before its schemas | accepted: P1 accrete schemas, P2 arities, P3 typing by family as declared BEHAVIOR CHANGE | errors §7 |
| E-H2 home message cannot reach a branch agent | resolved by design: the opening's one message goes to root (root's home branch IS the task home, `seon.task/home` [V]); the causing agent reads every occurrence in its own reply | errors §6; decision D1 |
| E-H3 new `links` rebuilds `refusal/chain` | accepted; `chain` is now whole data after printers-round2 [V], so no bounded variants remain | errors §1, §3 |
| E-M1 cut too large | accepted: constructor arities are prep P2 on the same defn | errors §7 |
| E-M2 interval between cuts | accepted: row schema rewritten in the errors cut; default reset after it | errors §7, §9.6; D6 |
| E-M3 / Astra E5 guide prescribes `Throwable->map` | fixed in guide §3.3, §14 and reference-code.md | skills |
| E-M4 signature merges distinct failures | accepted: problem-shape `[:in :type]` set and exception class added | errors §6 |
| E-L1..L4 stale citations, private `task-call` | citations replaced by function names; `adopt-tasks-tx` on the in-transaction db | errors |
| Astra E1 resolution not unwired; readers left behind | accepted: P4 is pure calculations only; the cut converts transcript and MCP observation | errors §7 |
| Astra E3 caller digest, reporter failure | accepted: blame and digest captured at the report via `calling-definition`/`var-digest` (as `boundary-refusal` does [V]); reporter failure is the primary `:seon.error/exception` | errors §5 |
| Astra E4 nil 4th arg, unqualified op, suppressed retype | accepted: `{}` default, `'example/f`, primary-path selection, `producer` gets the chosen map | errors §2, §3, T8, T9 |
| Astra E5 durable evidence safety | superseded by ruling round 6: stored evidence is the printer's shown text | errors §1 |
| L-H1 task read from branch db | accepted: context reads the task at the home | agent-loop §3 |
| L-M1 which agent row | accepted: the home-branch row | agent-loop §1 |
| L-M2 / Astra L3 id arity | accepted: installed two-argument `id/evaluation` kept | agent-loop §1, errors §1 |
| L-M3 / Astra L1 pass sketch | accepted: reply bound before `try`, call identity before context, context/provider failure is call data, `when reply`, settled batch, total settlement | agent-loop §2 |
| L-M4 JVM mode | accepted: evaluate stage is a request value | agent-loop §2 |
| L-M5 / Astra L6 deadlines | accepted: every `disarm!` caller passes its deadline | agent-loop §2.2 |
| L-M6 prep invisibility | accepted: context-inert attributes; P1 keeps old duties | agent-loop §1, §2.1 |
| L-M7 completion without proofs | superseded by rulings (test-based completion, success tests before launch); own `:work` tasks need a decision | agent-loop §4; D3 |
| L-M8 / Astra L2 budget | accepted: system message, mandatory, frame and separators priced first; selector takes `[:int {:min 0}]`, may keep zero units | agent-loop §3, T13 |
| L-L1 cost misattributed | accepted: O(lifetime wake datoms) | agent-loop §1 |
| L-L2 arities, dial names | accepted | agent-loop §2.2, §3 |
| L-L3 ordering | accepted: P2 after the errors cut; P4 depends on it | agent-loop §6, §8.9 |
| Opus P6 readers switch, P7 dial rename | accepted | agent-loop §6 |
| Astra L4 completion must close the task | accepted: `task-resolution` derives closed from the completion row; assignment basis validated | agent-loop §4 |
| Astra L5 cache key | accepted: no global loaded identity; key by eval id, profile, renderer symbol, printer digest; stored renderer symbol | agent-loop §3, T4 |
| Astra L6 unknown outcome | accepted as a requirement, seam unverified | agent-loop §2.1 [U] |
| Astra P0 `:seon.eval/entity` requires run | accepted | agent-loop §1, P0 |

### Round-2 guide reviews (Opus `review-guide2-opus.md`, Astra `review-guide2-review2-astra-guide.md`)

| Finding | Where |
|---|---|
| H1/H4 `Throwable->map` | guide §3.3 (cycle-safe walk; runner rewrite is BEHAVIOR CHANGE), §14 row, reference-code.md errors row |
| H2 completion law | AGENTS.md "Derive work and completion"; testing skill §1, §2.12 |
| H3 crash law | AGENTS.md "Recover facts, not execution" |
| H4/H5 tool catch swallows typed errors | guide worked flow step 3 (`tool-error?` validator) |
| H5 task references | AGENTS.md "Documentation is data"; guide §9.3-9.4; testing §2.12 (`:seon/tasks`, handle today, `ns/task-name` symbol TARGET) |
| M1 fault vocabulary | AGENTS.md (5 sites), guide §3, both specs |
| M2 "Nothing crashes" target as current | AGENTS.md: read carve-out, three catches named, `:multi` marked target |
| M3 turn-model sentences | AGENTS.md (pass reacquires, render caching, history re-render) |
| M4 pull-many row | guide §14 (BEHAVIOR CHANGE), §12.2 |
| M5 §3.4 target, member rename | guide §3.4, worked flow step 1 |
| M6 debug-diagnostic caveat | guide §2.5 |
| M7 rulings vs specs | guide header line; specs revised to the rulings |
| Astra M3 decomposition purity, contracts | guide §1.1 intro and `:or` round shape |
| Astra M4 absence over-statement | guide §0 |
| Astra M6 / Opus L1 aliases and arglist | guide §2.1 |
| L2 select-keys mechanism | guide §2.4 |
| L3 message docstring | guide §9.1 |
| L4 / Astra L2 call-reach key | guide §12.1 (full key composition; docstring caveat) |
| L5 babashka `*defaults*` | guide §8.2 now cites the options map (`process.cljc:250-263`) |
| L6 require-open-run | guide §5.1 now `my.program/caller-data` |
| L7 levenshtein range | guide §3.5 `256-260` |
| L8 stale first-party lines | reference-code.md: function names |
| L9 `ns seon.id-test` | testing §4 example 1 |
| L10 agent-value policy root | guide worked flow step 5 |
| L11 counts | "about N"; recount at HEAD: 119 rethrows, 151 `:seon.db/error-result` mentions [V]; `diagnostic` call count depends on the pattern (225 lane count, 245 by a broader grep) |
| L12 contract ≠ error-union proof | AGENTS.md contracts law |
| Astra L1 SCI per form | reference-code.md REPL row |
| Astra L2 docstring absolutes | guide §9.1, §9.2 |
| Merge arg-map arity | reference-code.md merge row |

### Owner rulings (memory `project_rulings_errors_docs_context_2026_10_07.md`)

| Ruling | Where |
|---|---|
| No fault concept; errors recorded once per signature | errors header, §6, §9.5; AGENTS.md deliver law; guide §3 |
| Test-based completion; `complete!` only to bail | agent-loop §4, §8.5, T7, T18; AGENTS.md; testing §2.12 |
| Tasks need success tests before launch | agent-loop §4 admission, T17; AGENTS.md (completion, launch lanes); testing §1, §2.12 |
| `:seon.cluster.eval/origin` enum replaces `:seon.eval/origin` | agent-loop §1, §8.1, cut |
| Deleted producer's error resolves | errors §6, §9.5, T10; AGENTS.md |
| Every error in `:errors`/`:diagnostics` has a result handle | errors §6, §9.8, T2 |
| Results runtime only | AGENTS.md REPL path; errors §6, §9.8; agent-loop §2.1 item 2 and staged-blob paragraph; guide §6.3 |
| Task ids derived from ns+name | AGENTS.md, guide §9.3-9.4, testing §2.12 (TARGET wording while task-tags is in flight) |
| Functions print whole | AGENTS.md "Three points" |
| Stored copies are shown text plus ids | AGENTS.md "Separate storage from observation"; errors §1 |
| Context = system message + task-generated context | agent-loop §3 (installed producers in `src/seon/agent/loop.clj`), §8.7; AGENTS.md completion law |
| Owner reads complete example outputs first | agent-loop §3 last paragraph, §8.7 |
| Dual review | AGENTS.md already said it; specs say "dual-reviewed" per slice |

### Live-run evidence (`live-run/report.md`)

| Item | Where |
|---|---|
| Stop sequence `#:seon.repl` | agent-loop §2 (no stop sequence; a cut is an error value), P7, T22; task created |
| No protocol in context | agent-loop §3 system message |
| Tests not runnable from SCI | testing §5 (`my.test/check`); agent-loop §3; the loop runs proofs itself (§4) |
| Markdown read as code | agent-loop §2; task created |
| Outside runs in model context | agent-loop §3 history = origin `:model`; T21; D5 |
| Contract message "string … got a string" | errors §5 |
| Error maps consumed by `sort-by` | errors §7 S-reads; guide §3.2 |
| Stale green task open | agent-loop §4 evidence; D4 |

### Stale items named in the assignment

- Guide §6.3 value-blob example: removed; `seon.turn/settlement-projection` no longer stages a value (its docstring:
  "An evaluation's value is never stored" [V]); §1.2's SEON line about it removed too.
- agent-loop.md:143 ("Staged blobs (cut values, reasoning)"): rewritten: today the run's reply text over the blob
  threshold and recorded-error evidence are staged [V: `seon.turn/stage-reply!`, `seon.error` staging]; after the loop
  cut only shown text above the threshold remains.

### Coordinator reminder (guide-reminder.txt)

Items 1-8 are stated in the skills (§0 and the label rules; §3; §8.1-8.3 and §12.1; §15 printer question plus
CLAUDE.md; §4; §9; clojure-testing; §6.2, §12.2-12.3). Item 9 (lane mistakes) is the new guide §16 and AGENTS.md
"Probe" and "Change one loadable slice" sentences. Testing §4 example 1 is marked TARGET-dependent on structural
hashing; §2.11 and the §3 discuss-list no longer name `test/my/examples_test.clj`.

## Owner decisions needed (options; recommendation marked)

**D1 Who receives a recorded error's one notification.**
- **A (recommended):** root, in the same task-home transaction as the row and task (root's home branch is the task
  home); the causing agent reads every occurrence in its own reply. One transaction, no cross-branch write.
- B: also a message to the failing agent's home branch, a second write before the reply. Duplicates what its reply
  already shows.
- C: no message; root sees it in the task queue only. Loses the "one notification" of the ruling.

**D2 Name of the bail function.**
- **A (recommended, as ruled):** keep `my.task/complete!` with a required reason.
- B: rename it `my.task/give-back!`: "complete" no longer means success, and agents may read the name as the success
  path.

**D3 An agent's own `:work` task, which has no proofs** (`my.task/own-task`).
- **A (recommended):** a model agent is launched only on an assigned task with proofs; `own-task` stays for outside
  agents' tracking and never completes by tests (the agent ends by `my.turn/wait` or the cap).
- B: `plan!` requires the agent to write a deftest naming its task first.
- C: `:work` tasks complete by `wait`, outside the test rule.

**D4 A task whose proofs are all green at assignment** (task 18c37812b5bc; none of the bucket-D tasks checked had a red
proof).
- **A (recommended):** `assign!` refuses it (close it, or fix the test so it fails before the work).
- B: allow it; the first pass that changes reached code completes it.
- C: no rule.

**D5 Outside runs under an agent's id in its model history.**
- **A (recommended):** history is origin `:model` only; outside agents reach a model agent through messages.
- B: include `:outside` evaluations as history.

**D6 Reset at the errors cut.**
- **A (recommended):** root resets default after the errors cut and again after the loop cut (data is disposable).
- B: one reset at the loop cut, with the errors cut keeping old rows readable until then (a translation layer to write
  and delete).

**D7 History order.** Ruling 7 says "history newest-first"; `prompt/compose` emits newest-last.
- **A (recommended):** select newest-first (keep the newest under the budget), display chronologically, newest nearest
  the end of the prompt.
- B: display newest-first.

## Could not verify (unavailable evidence is not a pass)

- The `seon.db` seam that can report the terminal outcome of a transaction answered outcome-unknown (agent-loop §2.1
  [U]): not found by reading; may need a small addition at the writer.
- Load order for the runner rewrite `(refusal/cause-links t)` from `seon.test.runner` (guide §3.3 says to check).
- Malli `:multi` usability for a new returning function before P1 (not probed).
- That `my.test/check` with a test symbol runs exactly that test: taken from the context-examples lane's run
  (aea4a5e888cc), not re-run here.
- Every [U] in both specs; no tests were run (documentation only).

## Design check

The rulings held against the code with two adjustments that needed no new machinery: the task home is root's home
branch, so the "same transaction" row + task + message works by sending the notification to root rather than to a
branch agent; and the installed two-argument `id/evaluation` already gives the pass identity, so no id formula
changes. Awkward: "complete!" now names the failure path (D2); the agent-loop P4 paid pass still depends on the
per-agent no-provider overlay task. The specs grew slightly (new tests T17-T22, decision lists) but lost the cause-chain
rebuild, the evidence-blob walk and the id-formula change.

No restart or reset is needed for these documentation changes.
