---
type: notes
status: active
created: 2026-10-07
tags: [notes, review, errors, agent-loop, guide]
---
# review-final-reconcile: Opus patches against Astra's 783d163ea

Reconciles `review-final-opus-notes.md` (10 findings, all patches) with `review-final-codex-notes.md` (22 fixes in
`783d163ea`) for the files of `18c3d1286`, and applies the owner rulings D2-D5. Documentation only; no source, test or
restart. Agent-loop §3 got only its banner.

Live checks (MCP `eval_clj`, agent root, cluster default, JVM mode, `result/e88853e64e300`): `my.task/complete!`,
`seon.error/caught` and `seon.sci.eval/acquire-pass-context` resolve to nil; `seon.flow/var-process` at
`seon/flow.clj:323`, `seon.task/assign!` at `seon/task.clj:378`. Static reads at `HEAD` (`b461077c7`):
`var-process`'s three-argument arity is `(try (step-var state input message) (catch Throwable failure [(cond-> state
(failed! failure) closed) nil]))`, and `failed!` calls `proc-failed!`; `assign!`'s `cond` refuses only
`:unknown-task` and `:unassigned`; `seon.task.edn` `:closed-by` is `[:enum :seon.task/faults :seon.task/signal
:seon.task/outcome]`; `test/my/examples_test.clj` is absent at HEAD (`819a8ed4b`); `seon.test.runner` already requires
`seon.error.refusal`; `git grep -c polymorphic-boundary HEAD -- src resources` sums to 173.

## Opus findings: disposition

| # | Finding | Disposition | Evidence / change |
|---|---|---|---|
| 1 | Pass rethrow double-records through `seon.flow/var-process` | **Partly resolved by Astra, applied now.** Astra (fix 3) stopped claiming `caught` records, but kept "Forward the recorded error to the supervisor", which is the rethrow. | agent-loop §2 item 6: the pass is the entrant, answers and continues, throws only when recording failed. reference-code.md Agent loop row: upstream vs `var-process` behavior. errors §3: an entrant that recorded never rethrows. |
| 2 | AGENTS.md states uninstalled `complete!`, test-gated assignment and test runs as current law; rejected context design | **Partly resolved by Astra, applied now.** Astra replaced the context sentence with a pointer but left assignment, test runs and `complete!` in the present tense. | AGENTS.md "Derive work and completion" rewritten: one neutral context line (rendered live from the task and its references); D2-D4 and D3 launch rule under Target. Also marked task-home recording in "Deliver every error" Target (today `seon.fault`), which states the same uninstalled mechanism as current. reference-code.md: context producers named only as unwired. |
| 3 | Vacuous resolution of a proofless error task | **Resolved by Astra** (fix 8: nonempty proof set, `true?` of `verified?`, T10 empty proofs). **Applied now**: the workflow sentence ("adding the regression that names the task is part of fixing it") and T10's explicit digest-change case. Q1 stays open. | errors §6, §8 T10. |
| 4 | Where recording happens; what is "Seon's own" | **Partly resolved by Astra** (§3 now says `caught` does not record). **Applied now**: recording is the entrant's step, receipt as `:seon.error/recorded`, agent mistakes delivered only, rule marked `[U: owner question]`; home-write cost note beside the 100 ms bound. | errors §3, §6; AGENTS.md delivery bullet. |
| 5 | clojure-testing §2.12 target as current | **Resolved by Astra** (TARGET marker), but Astra added "A blanket refusal of assignments with green proofs is not an implemented or settled rule", which contradicts owner ruling D4. **Applied now**: D4 as TARGET; refactor-proof tension moved to an owner question. | clojure-testing §2.12. |
| 6 | `:seon.task/closed-by :completion` not in the enum | **Applied now.** | agent-loop §4 names `:seon.task/completion` and the installed enum; P0 adds it. |
| 7 | `acquire-pass-context` not installed | **Resolved by Astra** (fix 2 removed the sketch; P2 says "the acquired context with its diagnostic"). | Confirmed nil on default. |
| 8 | Stale caveats | **Applied now.** | data-oriented §3.3 load-order sentence cut; clojure-testing §2.11 "removed in `819a8ed4b`"; §5 shows `(my.test/check {:seon.test/changed ['ns/test-name]})` (supported by `seon.test` comment "A test named as changed is itself a member"). |
| 9 | Testing examples 2/3 duplicate existing tests | **Applied now.** | clojure-testing §4 heading note names both source tests (verified at `test/seon/reset_edges_test.clj:72`, `test/seon/sci/admit_test.clj:528`). Astra replaced only example 1. |
| 10 | Counts and accuracy | **Applied now**: "169 uses" count cut; errors §1 notes the world keys change in P2. Third bullet (`seon.error/message` private) needs no change. | data-oriented §10.1; errors §1. |

D2 (keep `my.task/complete!` for bailing with a reason): agent-loop §4 already said it; AGENTS.md now states it as Target.
D3 (launch only on assigned tasks with proofs; own `:work` ends by wait or cap): agent-loop §4 **Launch** bullet,
§8.5, AGENTS.md. D4 (`assign!` refuses all-green proofs): agent-loop §4 Admission (`:already-proven`), evidence
bullet, P5, T17, §8.5; AGENTS.md; clojure-testing §2.12. D5 (outside MCP runs excluded from model history): §3 already
says it; §3 untouched.

Owner rule for AGENTS.md applied: the system-message plus task-producer design is gone from AGENTS.md; one neutral line
remains. agent-loop §3 carries "Superseded: being redesigned (context-design notes)." under its heading; its body is
unchanged.

Rejected: none of Opus's patches. Opus's literal pass sketch (finding 1) was not pasted because Astra deliberately
replaced the sketches with obligations (fix 2, 22); the obligation was applied in that style instead.

## Do the reviews contradict each other or the rulings?

- **Astra vs D4 (contradiction, resolved toward the ruling).** Astra fix 16 and its owner question call the all-green
  refusal unsettled and incompatible with refactoring; the owner ruled D4. The ruling now governs every file; Astra's
  real concern (a refactor's proofs are green before it starts) becomes question O5 below.
- **Astra fix 3 vs Opus finding 1 (complementary).** Both fix the same comment; Astra's "forward to the supervisor"
  would reintroduce Opus's double record. Merged as: answer, continue, throw only on recording failure.
- **Astra fix 8 vs Opus finding 3 (agree).** Both require a nonempty proof set.
- **Astra fix 11 (cross-home completion) vs D3/D4 (independent).** The rulings do not address it; it stays open (O6).
- No other contradictions found between the two notes.

## Consolidated owner questions

**O1 (Opus Q1) A recorded error's task has no proofs when it opens.** Its error then cannot resolve, and under D4 it
cannot be assigned.
- (a) The task stays open and unassignable until someone adds a failing proof naming it; root writes the proof when
  triaging. **Recommended**: no machinery, and the proof is the fix's specification.
- (b) Opening auto-writes a proof stub that fails while the error recurs (a test re-running the signature's producer
  call). Automatic, but it generates tests and needs the failing input stored.
- (c) Exempt error tasks from D4 and resolve on digest change plus a clean period. Simple, but resolves by silence,
  which AGENTS.md forbids.

**O2 (Opus Q2) What makes an error "Seon's own" (recorded) versus an agent's mistake (delivered only)?**
- (a) Its producer (blamed definition) is first-party and not in the failing agent's own namespaces or branch-changed
  definitions. **Recommended**: derived from existing attribution, no new field.
- (b) A declared flag on each error variant's schema (`:seon.error/own? true`). Explicit, but one more fact per variant
  to keep right.
- (c) Record everything, deliver everything; the agent's mistakes open tasks too. Simplest, but floods root's home.

**O3 (D1, open) Who receives the opening/completion notification?**
- (a) Always root (task home is root's home). **Recommended** until assigners other than root exist.
- (b) The task's assigner, falling back to root. Correct when agents assign, needs the assignment read at the writer.
- (c) A listener on task facts decides (AGENTS.md "a listener on task facts decides who is notified"). Most general;
  more machinery now.

**O4 (D6, open) When is default reset for the agent-loop/errors cut?**
- (a) Once, at the dependency-complete cut, no migration (current spec text). **Recommended.**
- (b) Once per cut (errors cut, then loop cut). Smaller blast radius, two resets.
- (c) No reset; readers tolerate old rows. Avoids loss, keeps both shapes alive.

**O5 (Astra, with D4) How does a behavior-preserving refactor or deletion task satisfy "proofs must fail before"?**
- (a) It names one proof that fails before (a size, shape or absence test of the thing removed), plus existing green
  tests as guards. **Recommended**: keeps D4 uniform.
- (b) A task kind `:refactor` exempt from `:already-proven`, completed by its guards staying green. Needs a kind.
- (c) Refactors are not assigned tasks; root runs them as lanes. No change, but model agents cannot do them.

**O6 (Astra) P5 cross-home completion recovery.** Completion and its message are at the task home; disposition is on
the agent's home branch. How does a committed completion retire the branch's disposition after a crash without a
second notification?
- (a) Disposition is derived from the task home (completed assignment ⇒ not working), not stored on the agent.
  **Recommended**: no second write to recover.
- (b) The pass re-reads completion at admission and writes disposition idempotently; the message is keyed by
  assignment so a repeat is refused at the writer.
- (c) Put both on the task home in one transaction (agent facts move home).

**O7 (Astra) Structural identity for error signatures.** P4 needs order-independent structural `id/id`; installed
`seon.id/id` hashes printed data.
- (a) P4 waits for the identity owner's structural hash. **Recommended**, as the spec says.
- (b) P4 hashes a canonically sorted vector of `[path type]` pairs with a total comparator over mixed keys. Unblocks P4,
  a second canonicalization to delete later.

**O8 (Astra) Unknown-outcome writer seam (agent-loop §2.1).** Name the terminal-outcome owner before re-arming.
- (a) The settlement owner records `:seon.db/transaction-outcome-unknown` and the pass holds ownership until a read
  of the writer's committed log decides. **Recommended.**
- (b) Treat unknown as refused and resettle under the same pass id (writer refuses a duplicate). Simpler, relies on
  id uniqueness at the writer.

**O9 (D7, open) History order in model context.** Belongs to the context redesign
(`docs/research/agent-platform/2026-10-07/context-design/`); no options proposed here.

**O10 (Opus Q3) AGENTS.md context sentence removed until context-design closes.** Applied per the owner's instruction
today (one neutral line kept); confirm only if the neutral line should also go.

## Design check

The design held: both reviews were accurate on installed code, and the merge was mechanical except two places where
Astra's text cut against Opus's evidence (finding 1's forward-to-supervisor) or an owner ruling (D4). Awkward: AGENTS.md
mixed target and installed law in two bullets; marking Target is a stopgap until P5 and the errors cut land.
Verification limits: documentation only; no tests run; claims probed live where named above. No restart or reset
needed.
