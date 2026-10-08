---
title: Implementation cards, outside design review
type: research
status: reviewed, changes still required before the affected slices launch
date: 2026-10-08
lane: cards-review-sol
---

# Implementation cards, outside design review

The critical path still has a dependency cycle, a gate that can mistake compiled execution for branch proof,
and context/history designs that cannot reproduce all the data they promise. Resolve findings 1–8 before
launching those slices. Many deletion and transaction slices already choose the right small composition.

Authority read: [owner rulings](owner-rulings.md), checkout AGENTS.md,
[agent loop](../../../../seon/specs/agent-loop.md), [merge](../../../../seon/specs/merge.md),
the cards and their recording/entrants, REPL-first, references and storage-point designs. The rulings take
precedence over older approved design text. This review reads a moving checkout; the last source checkpoint
inspected was `168f499f8155f34dd51ea2b8188b4fa8742cdb33`. Findings identify card sections and concrete instructions,
so they remain checkable when line numbers move. No implementation is certified by this design review.

For every card in the coverage table I considered: smallest correct composition; dependency reuse; work later
deleted; data assertions through the real entrance; proportional cost; consistency with rulings and other cards;
and decisions still left to the implementer. “Continue” means no separate design blocker found, subject to the
cross-cutting findings named in that row. It does not mean installed, tested, or green.

## Findings, highest impact first

### 1. RP-7, RP-11b, RP-11c, EN-8b, RP-14: make the recording order acyclic

RP-7 explicitly waits for RP-11c for file serialization; RP-11c waits for RP-11b; RP-11b needs RP-7's
zero-entry call recording. Serialization became a semantic dependency cycle. EN-8b also consumes the attempt
facts builder attributed to RP-14b, while RP-14 waits for EN-8b. The latter is a missing prerequisite even
though EN-8b does not list RP-14 in its Dependencies section.

**Change:** extract the zero-entry call recording change from RP-7 as a small prerequisite of RP-11b; keep
RP-7's remaining deletion after RP-11c. Introduce the pure attempt-facts builder with EN-2/EN-8b, retaining the
existing attempt commit until RP-14b removes it. Revise the index and owning design's order together. File
ownership is a wait condition, not a reason to make the prerequisite depend on its consumer.

**Proof/cost:** a prose-only fixture-model reply records one call and zero evaluations through the ordinary
entrance; a provider failure records its call and attempt/error facts. Later RP-14 proves the single recording
transaction. Work follows the reply's facts; the prerequisite needs neither the cursor redesign nor all the
recording failure machinery.

### 2. RF-2, RF-3: green reaching tests can prove the unchanged compiled body

RF-3 itself documents the gap: SCI can interpret a declaration while its reaching tests call the compiled
platform caller, leaving the branch definition unexecuted. `:seon.fn/host-bound? false` and a current green reach
digest therefore cannot establish that the proposed body ran. RF-3 still offers three options; RF-2 cannot safely
treat it as an optional later improvement. The older REPL-first design's namespace classification also conflicts
with AGENTS.md's per-declaration rule.

**Change:** settle RF-3's execution evidence before RF-2 admits this case. Require positive evidence of the
tested branch body and its definition digest for an executable body change; otherwise take the existing
throwaway check. State separately how declaration-only changes and deletion are proven: an absent definition
has no body to execute. Reaching selection must still include deletions and their former callers. RF-2's
in-flight owner already has deletion handling; update the card rather than implement another selector.

**Proof/cost:** a host caller returning the old compiled behavior cannot prove a branch redefinition; a direct
branch call can. Also cover deletion, contract/doc-only changes and unchanged green reuse. Attach executed
digests to the existing test result, proportional to reached calls; no namespace roster or second evidence store.

### 3. F4c, F4d: continuation can lose pending work and promises a false wall-clock bound

F4c discards the queue whenever the next eval request triggers a step. The ruling now triggers after every
request evaluating a form, including `(+ 1 2)`. Rebuilding the selection is safe only if it removes current
verified results and preserves all still-unverified work; that behavior is not specified by the card. Proofs
that repeatedly consume the step can starve reaching tests. F4d additionally says a program equal to its base
has an empty queue, although a task's unanswered proofs or changed answer can still be due.

**Change:** derive due proofs independently of code changes; continue unverified reaching tests against the
current program, reusing content-valid green evidence. Supersede stale evidence, not the work identity. Budget
the complete serial step, including selection/acquisition, and describe the runner's actual-exit guarantee:
“The next pass waits no longer than the dial” is false when a started host member has not exited. A timeout
does not release its resources or permit overlap. Add `seon.turn.loop.edn` to F4c's Files list.

**Proof/cost:** replace the 1 ms timing lottery with an injected controlled external effect and a declared
bound. Prove progress across non-changing requests, proof reuse, newer changes, restart with unchanged code
and a changed answer, and a started member whose actual exit comes after the admission deadline. Reuse the
runner and Flow state; no new timer/queue service.

### 4. CS-01d, F4b: the task home's current value is not the merge base

CS-01d says `base` is the merge base “read as the task home's current value.” Main can advance after the branch
forked. Comparing two full function-digest maps also misses identities absent on the branch and changes to
tests, schemas or namespace declarations. Its “red-at-base” proof never advances home after the fork, so it
cannot expose the wrong baseline. The test name says such a test is “not blocking,” whereas merge.md requires
root to handle a test already red on main before the source branch existed.

**Change:** obtain the actual comparison base through the existing branch/merge owner and reuse its change
value, including removed identities. Share that value with F4b's reaching selection. Record current positive
baseline evidence; missing/stale evidence is unknown, not red. Change the proof to advance home after forking
and distinguish the old baseline from the new home. Keep the root blocker explicit.

**Proof/cost:** also cover a deletion with a surviving caller and a schema/namespace change. Work should follow
changed identities and selected tests, not scan the whole program on every rendered block. CS-01d already
admits the two queries are whole-program work; do not wait until they exceed one second to choose the existing
change owner. Datahike owns commit lineage; this review does not claim a new upstream merge-base API exists.

### 5. DBG-01, CS-03a, S4-02b: one branch basis cannot reproduce the context as sent

DBG-01 replays `current-state` with `db/as-of` of one branch. Its current caller reads the task home's live
connection (`src/seon/context.clj:416` at the inspected checkpoint). Prefix construction, printer/render profile
and wire-format configuration can also change after the call. DBG-01 hardcodes `:transcript`; CS-03a makes the
adapter configurable. Equality immediately after one fixture call proves none of these replay cases.

**Change:** assemble live and replay messages from the same explicit immutable input bundle. Record the
identities/bases of all inputs that must survive: branch and home reads, assignment, loaded prefix/program,
render/printer profile and chosen wire adapter. Preserve historical bytes or requeryable content identities
where a current loaded Var is insufficient. Keep the ruling's basis storage and same assembler, rather than
storing a second prompt or reading proc memory.

**Proof/cost:** after a captured provider request, change the home task/message, program/prefix and adapter,
then open the real debug route for that call and compare its data with the captured request. One immutable
branch basis is only sufficient if all assembly reads are proven to come from it. Reconstruction follows that
call's history and selected block, not current global state.

### 6. S4-03, S4-04, SR-F: history discovered through evaluations loses zero-form exchanges

S4-03 starts with `of-agent` evaluations, groups by their run and then finds model call rows. A prose-only reply
or a provider failure can have a call row and no evaluation; it is invisible to that algorithm. Reading every
evaluation before filtering assignment also makes reacquisition follow the agent's lifetime, not the selected
assignment. The exchange schema only carries source and shown text, while its proof promises errors, output
and diagnostics. Blob-backed text needs its declared selectors/read inputs after SR-F.

**Change:** select the assignment's ordered model call rows first, then attach their evaluations in ordinal
order through the existing readers. Define the zero-evaluation exchange and the complete finished exchange
shape, and append that same value in S4-04. Derive selectors from schemas and read inline/blob text through
the one stored-text owner. Do not add a second transcript printer.

**Proof/cost:** include a prose-only reply, provider failure, error, output, diagnostic and blob-backed shown
text; restart/reacquire after earlier assignments have large histories. Compare captured and reacquired
exchange data. Work follows the assignment's calls and displayed text, with each completed exchange appended
once; an all-agent scan is a different cost.

### 7. S6-05, RP-14a: count calls against the wake they consumed

The spec says the cap counts against the outside wake whose anchor the call consumed. S6-05 instead counts
call recording transactions at or after the latest outside anchor. A new wake arriving during the provider
call precedes the old call's recording, so that old call is charged to the new wake's budget. The answered-basis
proof correctly covers this race for answeredness, but not for the cap.

**Change:** carry the consumed outside anchor as call data read at admission, and have the writer count by
that anchor. Preserve the presented basis separately. Specify admission versus recording when the provider
was already called; refusing a bookkeeping transaction must still return the evaluated values.

**Proof/cost:** extend the wake-during-provider test to assert both budgets and the next admitted call. Include
self-wakes, MCP requests and failure calls. Indexed calls for one agent/anchor replace all-history timestamp
classification; no new counter atom.

### 8. A1, F6: answer validation and proof invalidation lack their owning input

A1 validates against a pre-read task schema, then writes a generic `:seon.db/ref` component with no declared
whole-answer schema relationship. The card does not specify how the checked writer proves that component
still satisfies the held task's K, or checks that the held assignment/schema did not change. F6 records a
digest only of the pulled answer, but names a proof that a changed answer source invalidates green. Source
refs and source names are separate agent attributes and do not enter that digest.

**Change:** specify the answer's owning schema/ref and writer decision, including assignment/schema identity
and replacement/retraction. Validate the final owning value against K at the authority, or check the basis of
the supplied decision there. Define F6's observed input as answer plus whichever source identities/content its
proof consumes, and include those reads in the existing evidence validity mechanism. Confirm the member's
tested-branch supply before calling the card ready, as F6 already requires.

**Proof/cost:** task reassignment/schema change between validation and write must refuse stale custody; a
source-only change must invalidate the named proof; replacement leaves exactly one valid component. State
whether a `result/e…` made earlier in the same unrecorded request is an admissible source—A1 currently resolves
only durable evaluation rows. Validation follows the answer's owned datoms and observed source inputs.

### 9. SR-H, SR-I, RP-12: displayed tokens do not bound retained live heap

SR-H weighs the whole live result as four times displayed tokens. A large vector elided to 2,000 tokens can
retain far more than that weight. SR-I and RP-12 repeat the same estimate for whole diagnostic/finding values.
The cache test supplies artificial weights, so it proves Caffeine's eviction, not the producer's byte claim.
Interning referenced handles into a persistent SCI namespace also keeps values alive unless those roots are
retired; “only handles its forms name” does not specify that retirement. “Before a pass … its read forms” risks
a second parse before the serial reader.

**Change:** settle a defensible retained-value weight at the producer using a verified measurement/library
seam, and stop claiming the cache budget bounds heap until that is proven. Resolve handles through the existing
SCI reader/resolver at use, with request-lifetime roots explicitly retired; preserve agent-created references
as the agent's own state. Verify the SCI seam rather than invent another parser or evaluator. Remove the
kind byte counter if it duplicates Caffeine's statistics (owner rulings §10).

**Proof/cost:** real large values with small shown text, repeated handle reads across requests, eviction and
root retirement; distinguish cache-owned retention from an agent explicitly retaining a value. State and
measure any weighting cost rather than call every put O(1) unconditionally.

### 10. RP-3, RP-6a, RP-6d: freeze observations before arming and stop rereading live Vars

RP-3 captures the resolver before `arm!` but obtains metadata in `write-event` later. RP-6d explicitly classifies
with `(fn? @v)` and `(:test (meta v))` during derivation. A held Var is a mutable place, not evidence of what
that write observed. This recreates the time-of-observation bug these cards intend to remove.

**Change:** capture metadata and root classification together with the resolver at the write hook, before
arming. Carry those immutable observations through derivation. Use final namespace rows only where the
final-state digest requires them; never recover historical function/test status by dereferencing the Var.

**Proof/cost:** define a function, replace it with a value, then redefine a function/test in one request;
change metadata too. Assert per-write observations and the one final stored declaration. One capture per
write plus analysis already scheduled; remove the later metadata/root reads.

### 11. RP-4: `requiring-resolve` hides a schema-to-recording dependency cycle

The card resolves `seon.turn.batch/commit-registration!` and `seon.batch/*world*` from `seon.schema/register!`
because the recording namespace already requires schema. Delaying a namespace edge does not separate the
owners, and the world remains an ambient lookup rather than a declared supplied input.

**Change:** reuse the schema transaction builder at its existing lower owner, or pass the commit operation,
connection and writer as declared evaluation inputs. Keep outside-evaluation registration unchanged. Do not
add a schema registry, alternate writer or lazy namespace escape. List the producer's complete success/error
contract when returning a refused commit.

**Proof/cost:** the existing register/write/read proof is right; add a refusing injected writer and verify
the value/diagnostic plus later visibility through MCP. Cost follows one key and its actual dependents. The
existing registration-cost task is real evidence, not permission for another whole-program derivation.

### 12. RP-6e: keep authored contract data without another serializer or broad error catch

The proposed fallback explicitly calls `(pr-str contract)` and treats any error keyword in `malli.core` as
a malformed schema. Both choices are broader than the ruling permits. “Existing storage unchanged” does not
authorize recreating a forbidden printer. The proof only creates a new bad contract; it does not replace an
old valid contract whose arities/wrapper could survive.

**Change:** retain the authored spec already captured from the source/span through its owning path. Name the
exact Malli compilation variants translated and retain the cause; unrelated failures propagate to the entrant.
Retract stale contract facts/wrappers when malformed authored data replaces a valid contract.

**Proof/cost:** valid→malformed redefinition remains callable under its new body before and after reacquisition,
stores the authored spec, has no stale arities, and is refused at merge. No ordinary-path extra printing or
value walk. Malli owns schema compilation; no namespace-wide exception classifier.

### 13. TX-3: repeated counts need a durable owner, not “the next home commit”

TX-3a folds the second occurrence into a home count buffered until one more unrelated home commit. A crash
after the reply can lose that count. The approved recording redesign §2.1 instead folds a repeat into the
current eval request's commit on the agent branch, with home untouched. TX-3b says readers read only home,
without specifying how those durable origin counts are observed. The card and its authority disagree.

**Change:** choose the already approved recording-transaction owner for repeats, and define the home reader's
aggregation of those references/counts; if root changes the owner to home, state the extra durable transaction.
Delete reliance on a future unrelated commit. A recurrence-limit default of 1 is not by itself enforcement
of “one occurrence per signature” when an operator changes the dial; define what that dial still controls.

**Proof/cost:** second occurrence is durable/readable immediately after its reply, with no subsequent commit,
including restart. Count one new signature/task/notification, then repeats without copied payloads. The
approved cost is zero *extra* repeat commits, not zero durable repeat data.

### 14. TX-6a: target branch and task home may differ

The card puts the merge request's outcome and the source agent's cluster closure into the merge transaction
on the target. merge.md permits an explicit target branch, while the request and authoritative cluster agent
live at the task home. One transaction on a different branch cannot atomically update the home as well.
The proof's “transactions after the merge base number 1” also counts unrelated target writes.

**Change:** specify the same-home case and the distinct-target case. Use one program commit on the target,
then idempotent home outcome/closure with the committed target identity; recover a crash between them without
merging twice. Do not copy home entities into an arbitrary target to satisfy a transaction count. Count the
merge's transaction by its merge-request provenance, not every transaction since the base.

**Proof/cost:** merge into a target distinct from home, advance it before merge, and exercise the crash window.
Transaction cost depends on the number of authoritative branches, not a universal “5 → 2.” TX-6b's request
and message already share one home and can remain one transaction.

### 15. S4-02b: the prefix cache key changes on every recording

The prefix is specified per loaded program, but the proposed key is `(db/commit-id database)`. Evaluation
recording changes that identity even when help's program inputs do not change. Byte-identical system messages
can therefore conceal a miss and reprint every call.

**Change:** key the existing cache by the content help actually reads and namespace until H-01 makes it literal.
Use the loaded program's existing identity where sufficient. Assert cache misses/recomputations, not just
equal output. An unrelated message/evaluation must not rederive help; a changed help input must.

**Cost:** one lookup per call, one derivation per changed help input. This is the content-valid cache ruling,
not an optimization deferred until a timing threshold is crossed.

### 16. CS-01c2, CS-01c3: reference handling is incomplete and can throw before reporting a problem

CS-01c2 calls `find-var` before its namespace fallback, although namespace names are unqualified symbols;
the references design itself notes unknown namespaces need `find-ns` + `ns-resolve`. Its `Paths/get URI` does
not handle jar resources such as `clojure.core/map`, and it associates a nil pin despite the absence rule.
CS-01c3 recognizes only `reference-code/` paths, treats `#heading` as part of the filename, rereads the pins
manifest per path, and offers `slurp` despite RF-6's measured SCI unresolvability.

**Change:** distinguish namespace from qualified Var lookup before calling core functions; use loaded resource
metadata and the immutable pin manifest with an explicit unpinned/jar case. Add checkout-relative paths and
heading fragments per the current references ruling. Offer the installed file-read owner, verified at the
agent entrance, and show unresolved/anchor-missing data locally. Parse the manifest once per loaded archive.

**Proof/cost:** loaded namespace, unknown namespace, jar Var, missing pin/project/file, checkout document,
present/missing heading; evaluate offered operations through MCP. Work follows named references and file
existence/heading reads, not repeated full manifest parsing.

### 17. SP-1a, SP-1b, SP-1c, SP-2: tighten proofs and make the byte refusal total

SP-1a/b's content-preservation proofs should inspect the full live value and stored fact, rather than string
containment in a separately displayed rendering. SP-1c says `mr/schema` throws on a missing key; it returns nil
for an ordinary missing registry entry. SP-2 says a missing dial silently means nothing is checked, despite
already defining a schema default. Its added-datom check does not specify an idempotent assertion of an existing
oversized value after the dial decreases.

**Change:** assert original data via the live result handle, exactly one stored text form, and inline/blob
digest/size facts. Use `nil?` for the registry lookup (or `m/schema` only when testing compilation refusal).
Resolve the configured/default dial or return a typed unavailable-policy error; do not equate missing config
with “no evaluation has written text.” State whether every proposed assertion or only changed stored datoms is
checked, then align the “refuses a re-copy” promise with that rule.

**Proof/cost:** lowering the dial followed by idempotent and changed assertions, absent config with declared
default, non-ASCII bytes and the boundary. Keep Guava's approved byte count and Malli-derived attribute rules;
per-write work follows checked text, with no second inline/blob decision. SP-3/SP-4 choose the right producer.

### 18. S4-07b1, S4-07b3: retire functions and all surviving callers together

S4-07b1 deletes `compact!`/`virtual-turn!` and the schema read-evidence family; S4-07b3 separately owns the wider
capture/compaction caller cleanup. “Every caller converted” in the first card needs the concrete callers and
proofs now assigned to the later card. A deletion list alone does not establish that the intervening slice loads.

**Change:** move surviving caller conversions into the same loadable slice as their function deletion. Remove
the schema only after no surviving producer/consumer needs it; then shrink b3 to the remaining capture cut.
Keep DBG-01 before deleting `prompt`, as b2 already requires. Apply incremental live-store adoption; the two
cards' “root resets default” instructions need the precise adoption failure, not a reset as proof.

**Proof/cost:** boot the intermediate slice and run each named surviving caller, plus the debug route. Search
data, quoted symbols, schemas and tests as well as direct calls. Deletion should remove work rather than tune
machinery the next slice deletes.

### 19. RF-4, RF-6, F7: these still leave design decisions to a Sonnet implementer

RF-4 extracts only changed Var definitions and sends `in-ns`; an edited `ns` form/alias change and a deleted
declaration have no such new form. The file and branch can disagree while the one-defn hook proof stays green.
RF-6 is a useful friction census but offers alternatives rather than exact slices. F7b uses `<requester>` in
a mechanical rewrite without identifying that value at each call site. F7a's `[:string {:min 1}]` accepts a
whitespace-only reason although the card promises blank refusal; no-task `:unassigned` and `:my.turn/bailed`
also need their complete declared result shapes. Writing a reason then sending a separate message leaves a
failure window for giving the task back.

**Change:** specify RF-4's namespace/deletion handling using the existing namespace/edit owners and prove
file equivalence. Split RF-6 into selected owner operations/contracts/tests before launch. Give F7b a complete
call-site table for requester and message value; specify F7a's terminal assignment transition and atomic or
idempotent home notification. Either validate nonblank reasons with a registered predicate or narrow the promise
to nonempty. Declare every result variant and test missing/no-task/repeated-bail cases through MCP.

**Cost:** hook work follows changed forms and removed identities, not a full program evaluator. Giving back a
task follows one home assignment/message. A 37-file sweep is not a fully specified 15-minute slice until its
inputs and ownership are enumerated.

### 20. RP-3, RP-6b, RP-8, recording index: alias examples must follow FK-SCI

FK-SCI restores Clojure's refusal of a silent alias retarget. The executable RP-3/RP-8 proofs still require
`clojure.set :as s` over the existing string alias. Q-R1 was also still presented as open in the recording
index. Furthermore, `clojure.set/join` exists; the example breaks because that function has arities 2 and 3,
whereas `g` supplies one argument.

**Applied:** explicitly unalias in executable proofs and the explanatory index, preserve historical probes
as measurements predating FK-SCI, replace the open Q-R1 options with the settled reload/merge rule, and correct
the arity explanation. RP-8 now requires reaching reload/merge tests to be named before implementation; this
review does not invent tests that are not installed.

### 21. Cost sections: delete arbitrary targets and measure the operation actually performed

Several RP cost sections inherit the redo's retired “20% or 50 ms” rule or small fixed targets; RF-6 similarly
proposes a 200 ms ceiling. Owner rulings §7 supersedes these. The declared ordinary 1 s/10 s boundaries,
approved dials and measured before/after evidence remain valid. EN-0's measurement of one explicit test run
will also change once F4 automatically runs tests during the preceding definition requests: the subsequent
check may only measure reuse.

**Change:** keep measured baselines, state the input size and work, report every regression with its numbers,
and remove invented acceptance ceilings. For EN-0 explicitly control the approved automatic-step dial in the
fixture, distinguish acquisition/execution/reuse, and assert that the measured sample actually executed its
member. Do not use “cold” or a pre-existing slow task as the cost guarantee.

## Card-by-card coverage

Rows name every requested critical-path card and the additional cards read. Finding numbers apply to the
relevant criterion above. “Continue” still requires the card's ordinary contracts and installed proof. Larger
families are shown as separate slices where their one file defines those slices.

| Card | Design assessment and concrete dependency |
|---|---|
| CS-01c1 | Continue: edamame and existing entity readers; references ruling is the authority. 16 applies to downstream names. |
| CS-01c2 | Revise lookup/resource cases; 16. |
| CS-01c3 | Revise paths, fragments, offered reads and pin reuse; 16. |
| CS-01c4 | Continue: extend the existing inbox; no parallel inputs API. |
| CS-01d | Revise base, selection and baseline proof; 4. |
| CS-02 | Continue under per-evaluation budget ruling; put overrides in collection metadata, not a returned map member. |
| F4b | Share complete change selection and positive evidence; 2–4. |
| F4c | Revise continuation and deadline guarantee; 3. |
| F4d | Revise due proofs independent of changed code; 3. |
| S4-01 | Continue: stored ordinal and existing agent reader; no transaction-order inference. |
| S4-02a | Continue: assignment key is a call-row value read once. |
| S4-02b1 | Revise prefix cache identity and cache proof; 5, 15. |
| S4-02b2 | Continue opening deletion; coordinate every caller with b1/b2; 18. |
| S4-03 | Revise call-first history and complete exchange shape; 6. |
| S4-04 | Use the same complete exchange value for append/reacquire; 6. Window refusal before provider is the right owner. |
| S4-05a | Continue: existing inbox extended with unread subjects, once. |
| S4-06 | Continue: delete refresh with its readers; no replacement invalidation service. |
| S4-07a | Continue: remove compaction controls under dynamic-context ruling; 18 governs deletion order. |
| S4-07b1 | Revise same-slice callers/schema adoption; 18. |
| S4-07b2 | Continue after actual DBG conversion; 5, 18. |
| S4-07b3 | Revise split with b1; 18. Held/dirty caller expansion was not edited. |
| CS-03a | Continue adapters over one assembly; preserve adapter input for replay, 5. |
| DBG-01 | Revise immutable assembly inputs and delayed replay proof; 5. |
| RF-1 | Continue: expose existing branch write-back owner, not another writer. |
| RF-2 | Hold unsafe evidence admission until 2 is resolved. |
| RF-3 | Hold until execution evidence is decided with declaration/deletion cases; 2. |
| RF-4 | Revise namespace and deletion handling; 19. |
| RF-5 | Continue: delete disabled paid-review batching and every caller. |
| RF-6 | Split and decide friction fixes before launch; 19, 21. |
| SP-1a | Continue producer deletion; tighten data proof, 17. |
| SP-1b | Continue admitted-copy deletion; tighten data proof, 17. |
| SP-1c | Mechanical registry-miss proof correction needed; 17. Held, not edited. |
| SP-2 | Revise missing-policy/assertion edge cases; 17. |
| SP-3 | Continue: consume staged descriptor; do not recreate threshold decision. |
| SP-4 | Continue: byte decision at inline-or-stage; adopts attribute with producer. |
| TX-0 | Continue: transaction provenance at writer; live-store schema/consumer adoption required. |
| TX-1 | Continue: fold the existing writer data, retaining authoritative serialization. |
| TX-2 | Continue after TX-1 builder; no separate branch/store mechanism. |
| TX-3a | Revise durable repeat count and dial semantics; 13. |
| TX-3b | Revise repeat reader with the approved origin recording owner; 13. |
| TX-4 | Continue: recording operation/agent provenance, rather than run-open identity. |
| TX-5 | Continue: existing checkpoint writer, fewer transactions. |
| TX-6a | Revise distinct target/home and transaction proof; 14. |
| TX-6b | Continue: request and message share their home writer. |
| TX-7 | Continue producer-specific folding; deletion must enumerate remaining maintenance consumers. |
| TX-8a | Continue after provenance installation; convert every ref reader. |
| TX-8b | Continue with open/instant retirement; 7 and 18 govern intermediate loadability. |
| TX-8c | Continue ordered evaluation reads; 6 governs history selection. |
| RP-1w | Continue: branch integrity findings; root merge retains integrity enforcement. |
| RP-1 | Continue core unmap/remove behavior and writer findings through real entrance. |
| RP-2 | Continue: one analyzer invocation partitioned by captured namespace row. |
| RP-3 | Revise observation timing, 10; mechanical alias proof corrected, 20. |
| RP-4 | Revise hidden cycle/world acquisition; 11. |
| RP-5 | Continue analyzer/span reuse, consuming frozen observations; 10. |
| RP-6a1 | Continue one end-stage derivation with frozen write data; 10. |
| RP-6a2 | Continue value-preserving finish; 1 determines prerequisite order. |
| RP-6b | Continue total value/diagnostic separation; historical alias note corrected, 20. |
| RP-6c | Continue final value-def retirement with former callers converted. |
| RP-6d | Revise live Var classification; 10. |
| RP-6e | Revise serializer, exact translation and replacement proof; 12. |
| RP-10 | Continue model submission reuse; 1 determines recording dependency order. |
| RP-11a1 | Continue SCI serial cursor; no preread collection; 21 governs cost. |
| RP-11a2 | Continue one cursor for MCP code; preserve namespace after a throw. |
| RP-11b | Revise zero-entry prerequisite; 1. |
| RP-11c | Revise cycle, then delete readers with every caller; 1, 18. |
| RP-14a | Continue only after consumed-anchor/answered basis fix; 7. |
| RP-14b | Move pure builder prerequisite before EN-8b; 1. |
| RP-14c | Continue retirement after all receipt/open readers convert; 18. |
| A1 | Revise final owning schema/custody/source case; 8. |
| F6 | Revise observed answer/source validity and confirm supply; 8. |
| F7a | Revise transition, notification and complete error union; 19. |
| F7b | Enumerate requester values and disjoint files; 19. |
| F7c | Continue only after caller conversion and reaching tests; 19. |
| RP-7 | Revise dependency cycle; 1. Overwrite finding still needs authoritative basis evidence. |
| RP-8 | Corrected alias/reload rule; 20. Final digest and observed call edges remain distinct. |
| RP-9 | Continue own-span storage and live-only closure finding; current card agrees with ruling. |
| RP-12 | Continue printed-result storage pattern after real weight/handle design; 9. |
| RP-13 | Continue code input with zero forms returns nil through same cursor. |
| RP-15a | Continue facts-only recovery; crash proof requires actual exit of isolated owned execution. |
| RP-15b | Continue fixture conversion with real entrance, not a replacement fixture evaluator. |
| RP-15c | Same assessment as RP-15b, file ownership remains separate. |
| RP-15d | Same assessment as RP-15b, convert each retired helper caller. |
| RP-16 | Continue serial DATA read; reuse structural scan and SCI cursor, not a new EDN evaluator. |
| RP-17a | Continue caller conversion before reader deletion. |
| RP-17b | Continue declaration-reader deletion after caller conversion. |
| EN-0 | Revise measurement when automatic step lands; 21. |
| EN-1 | Continue effect provenance; durable effects remain distinct from recording. |
| EN-2 | Continue shared call-facts input; move early builders here, 1. |
| EN-3 | Continue explicit entrant read/eval values on one path. |
| EN-4 | Continue test body through one entrance, evidence on member result. |
| EN-5 | Continue member-world/evidence composition; 2 requires actual executed branch digest. |
| EN-6 | Continue one test-request recording; distinguish checkpoint branch writes (TX-5). |
| EN-7 | Continue canonical writer/fixture consolidation, not another store. |
| EN-8a | Continue MCP recording through existing settle owner. |
| EN-8b | Revise missing attempt-facts prerequisite; 1. |
| EN-9 | Continue recording refusal beside values; unknown outcome retains ownership. |
| S6-05 | Revise cap anchor, preserve presented answered basis; 7. |
| PB-03 | Continue SCI base advancement using existing SCI ownership; measure affected callers only. |
| PB-04 | Diagnosis/probe first is appropriate; not an implementation-ready fix until measured cause is known. |
| SR-C | Continue captured output producer/staged storage; one decision, no second print. |
| SR-E | Continue thrown failure through value printer; no canonical artifact copy. |
| SR-F | Continue stored shown-text reader; include blob selector/inputs in history, 6. |
| SR-H | Hold retained-weight and reader/root design; 9. |
| SR-I | Continue diagnostic result storage only after SR-H weight/handle ownership; 9. |
| SR-T | Superseded by SP-1a/SP-3/SP-4 in storage-point design; retire rather than duplicate implementation. |
| FK-MALLI | Continue maintained fork/library contract seam; consumers land with the pinned change. |
| FK-SCI | Continue dependency fix; update consumer alias proofs, 20. |
| FK-ASYNC | Continue dependency seam rather than Seon execution copy; pin and prove consumers together. |
| UL-10 | Continue library-owned exception triage; no independent Seon exception printer. |
| UL-11 | Continue library composition/deletion; preserve entrant error delivery. |
| DEPS-1 | Continue explicit Guava dependency at resolved version; UTF-8 count avoids array copy. |
| AT-8 | Continue Caffeine-owned statistics; audit any counter surviving SR-H, 9. |
| BLOB-K | Continue konserve owner; no second durable store/reclamation mechanism. |

## Evidence, mechanical edits and landing boundary

Started with `bin/seon status` and MCP `runtime_status` on cluster `default`. Default was degraded before this
review: 14 over-bound warnings, 23 errored receipts, one unowned namespace, no missing readiness layers.
The running source was `406bd11d396628b7919d4a3200f3bb92e9707c72`, not the checkout. Reported readiness was
3,759 ms; heap cap 4 GB, live heap about 1.38 GB, and weighted cache about 511.47 MB of 512 MB. Those figures
are status observations, not allocation measurements or proof of the card designs. Existing schema/context
cost findings were already recorded; this review neither resets default nor calls that health green.

Read-only installed JVM probe, explicit agent `root`, cluster `default`:

```clojure
{:registry-miss (malli.registry/schema (malli.registry/registry {}) :cards.review/missing)
 :loaded-source (seon.fs/source-directory)
 :reference-pin (str (clojure.java.io/resource "malli/core.cljc"))}
```

The complete reply had result handle `result/e018aa7dde479`, `:seon.repl/ms 1`, registry miss nil, the source
above, and Malli resource pin `43c6dca8d2d8df3162b13725939954807be4a4ed`; no diagnostic/error was delivered.
This falsifies SP-1c's throwing lookup assumption. It does not exercise any proposed card implementation.

Dependency source inspected: Malli `registry/schema`, `reference-code/malli/src/malli/registry.cljc:97`,
checkout pin `418df4417827b66846b799b5e87267a344dffc29`; SCI `parse-next` at `src/sci/core.cljc:390` and
`namespace-bindings` at `:701`, pin `010c86df4668b57bc16e50bce7a5a3f93e4d01ff`; Clojure `set/join` at
`src/clj/clojure/set.clj:115`, pin `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`; Datahike commit lineage/
materialization in `src/datahike/versioning.cljc:461–494`, pin `86c3301a00de8c49b2690083b2b1e69677bf0c8d`.
Guarantees used are registry lookup, serial context-aware reading, resolver bindings, native join arities,
and immutable commit identity/parents. They do not supply a new evaluator, merge algorithm or heap estimator.

Claimed this report and RP-3, RP-6b, RP-8, README-recording before edits. Only those four cards/index were
mechanically corrected. At the latest `bin/lane status`, `docs-steward-sol` held 70 paths; `cs01c1`, `f4b`,
`impl-fk-sci`, `impl-rf2`, `impl-sp3` and other live owners held their implementation/review paths. Their cards
and sessions were not edited or messaged. SP-1c's nil correction and the other held-card findings are left
here as concrete edits for their owners. The issue index was not changed.

Pre-commit `clj-kondo --lint src script test --cache false` completed in 11,160 ms with **0 errors, 1,477
warnings**, exit 2. The requested zero-error requirement is met; AGENTS.md's zero-findings condition is not.
Warnings belong to the shared source/test tree and are outside these documentation-only paths. Source and
test changes in this lane: zero lines. No new tests mirror documentation edits.

Commit command names `seon.id-test/map-and-set-order-never-changes-an-identity` as an existing var-level smoke
check through the required throwaway gate. No executable test reaches Markdown-only edits; that test proves
the boot/check path and its own structural identity behavior, not these designs. The card-specific proposed
proofs remain unimplemented and were not reported green. The gate archives HEAD plus exactly these five
paths; unrelated in-flight source/schema/test edits are the foreign boundary. Broad tiers remain root's work.

The first gate rehearsal passed on candidate `5877b57d44ab4aff3017cf9a9682750ae556e235`: run
`79e3a5c4deff`, one executed, one pass, zero fail/error, 1,913 ms for the run. Total check 155,060 ms,
including 92,147 ms slot wait and 45,233 ms to readiness; publication 866 ms, adoption 192 ms, 36 identities
on disposable branches (committed HEAD differs from the older running archive). Those are gate costs,
not card performance measurements. The named seeded property checks 500 bounded inputs; the summary
cannot attribute its 1,913 ms among member acquisition, body and recording. That timing remains a
verification limit, not a sub-second claim. The lane guard then refused the commit because the final
Q-R1 corrections changed RP-8 and the index after the snapshot. Nothing was committed by that attempt;
the gate must rerun on the final bytes. The accepted commit and final gate result are recorded by Git
and the runner, without amending a commit.

The coverage table is the review boundary. Remaining DIAL, PB, SR, UL, merge and other schedule cards were
not comprehensively reviewed. Owned transient gate resources are cleaned by `bin/lane`; no worktree,
uncommitted source load, default restart, paid provider call or outside-lane session action was performed.
