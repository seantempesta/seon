---
title: Independent documentation audit of owner rulings
status: dated audit, not a plan
type: research
created: 2026-10-08
---

# Independent documentation audit

The integration corrected important owning specifications, but implementing agents still encounter conflicting
instructions in the ledger, cards and older plans. The most consequential remaining disputes are the test recording
count, where norms execute, durable repeat counts, and which pending tests a later eval request may discard.
These are decisions for root, not wording changes made by this lane.

## Basis and limits

Lane: `docs-steward-sol`, outside perspective. Authority: `review/owner-rulings.md`, reorganized by topic in
`a1e1eb399`. Initial source audit: `ebb7b00cb900ab9b352e3919795315326eaa690b`. Recheck after the integration released
its claims: `725e29c901df988d2e9f4779a1c5ddd61c674623`; later mechanical edits began before `c987d6e2c`.
References below describe those committed snapshots, not whichever concurrent edit happens to be in the checkout.
The source checks establish what HEAD says; this audit makes no claim that an API is installed in the running JVM.

Read-only phase covered AGENTS.md, the owning agent-loop/merge/errors/run/task-steps specifications, architecture,
skills, ledger and related cards. Claims were checked repeatedly. No claimed integration file was edited while
`integrate-rulings` held it; phase two began after `bin/lane status integrate-rulings` reported no claims.
The issue index and ledger were not edited. No other lane was messaged, resumed or reviewed through its session.

One ownership mistake was caught and reversed: a later batch claim was refused because `cards-review-sol` had
acquired RP-8 and README-recording; the following patch nevertheless ran. Only that attempted patch and its
automatic blank-line changes were reversed after inspecting the complete diff. No edit to either card is carried
by this slice. The errors spec was separately claimed successfully before its correction.

“Agrees” in the coverage table means the named owning passage states the ruling; it does not certify all its
implementation instructions or current installation. “Fixed” describes this lane's prose change, not a code change.
Numbered findings below rank the risk of misleading an implementing agent.

## Ranked remaining findings

1. **The approved test recording count still disagrees with the owning spec.** `agent-loop.md:172-173` says a test
   member records one eval request and the request records an aggregate plus home completion. Owner §2 approves
   `redo/entrants.md`: the test request commits twice and a member has no separate recording transaction (card EN-4).
   An implementer can retain the per-member writes the redesign is intended to remove. Root should settle the
   table and its relationship to automatic tests inside an eval request, then make the spec, EN-4 and transaction
   census say the same count. Also `agent-loop.md:100` promises the next request waits no longer than the step bound,
   whereas §3.5 requires a started member to reach its actual exit. A deadline cannot promise host-call exit.

2. **The norms design contradicts the accepted dependency answer.** `merge.md:169-171` and ledger MA-8c
   (`agent-loop-ledger.md:395`) compute norms outside the writer, forbid agent code there, and guard the pre-read.
   The same row's status and FK-DH (`:713`) say the accepted seam computes ordered norm transaction data inside the
   merge transaction. `cards/README-audits.md:190-197` marks the fork recommendation accepted. Both alternatives
   are reasonable compositions, but they are different mechanisms. Root must choose the interpretation of “inside
   the merge,” settle execution ownership, and remove the losing algorithm. Do not have two owners implement both.

3. **Repeats can answer before their durable count exists.** `cards/TX-3.md:22-31,56-57` makes a repeat write zero
   commits and buffers its count until a later incidental commit. Its proof deliberately observes an unchanged home
   basis after the second answer. A crash before that later commit loses the count. `errors.md:247-258,270-275`
   instead increments at the writer; AGENTS.md “Recover facts, not execution” requires recorded errors durable
   before answering. Root needs one durability rule and a proof that crashes cannot lose an answered occurrence.
   Changing the recurrence dial to one does not establish that guarantee.

4. **A parallel active plan still claims authority.** `docs/prds/agent-platform/plan/README.md:15-19,194-217,411-419`
   and its `AGENTS.md:15-22` call themselves the current design and proof/order authority. Their schedule and cache
   choices predate the ledger and the one Caffeine cache ruling. This lane redirected the architecture entry point
   and six skills, but many historical links remain in mechanism sections. Root should extract any surviving
   mechanism/evidence, convert incoming authority links, then delete the old schedule and local authority file.
   Do not delete dependency evidence merely because it is dated.

5. **The schedule README is another live schedule.** `docs/seon/schedule/README.md:1` calls itself “single plan”;
   its phase list orders work separately from the ledger, including approvals that are now granted. Its task census
   is useful dated evidence, but its live phases should not exist. Root owns the issue index: retain the census in
   dated research, make this page a ledger entry point, and remove the duplicate ordering.

6. **Ledger rows name the rejected unread API and relation.** CS-01c4 (`:311`) derives from `my.agent/inputs`;
   S4-05a (`:314`) proposes that new function and its files while its status explicitly rejects it in favor of
   `my.message/inbox`. S4-02a (`:316`) calls the assignment a ref while its status says a key stored as a value.
   The cards and spec mostly carry the answer. Root should rewrite the change/files columns, not append another
   qualification to the status.

7. **The alias question is still presented as unanswered.** `cards/README-recording.md:15-33,72` and
   `cards/RP-8.md:11-12,65-66` block a proof on Q-R1. Owner §1 and `agent-loop.md:227-229` now select Clojure's
   reload rule: stored source follows the new alias, may break, produces a retarget finding, and broken definitions
   cannot merge. RP-8's recording algorithm covers the pre-reload calls; its proof must also cover this answered
   reload/merge behavior. The card is not fully specified while that assertion and merge proof are deferred.

8. **The pending-test queue drops more work than the ruling permits.** `cards/F4c.md:29-32` drops the queue whenever
   the next eval request triggers the automatic test step. That now means any evaluated form, including a read-only
   form. Owner §4 and `agent-loop.md:345-348` supersede an old program's tests on a newer change; a read-only request
   does not establish that its selection covers unfinished reaching tests. Root should specify how pending work is
   carried forward without losing symbols, and prove a read-only next request still runs the unfinished tests.
   The card's cost sentence promises the request never waits beyond the dial, inconsistent with actual exit.

9. **The debug card's shared assembler is conditional.** `cards/DBG-01.md:26-35` hardcodes `:transcript`, while the
   approved wire choice is configurable. It says the model uses `sent-messages` only when the proc holds no
   exchanges. That leaves the usual held-history path free to assemble differently. A database basis also does
   not by itself identify the loaded prefix/printer/provider adapter used for an earlier call. Root should make
   the ordinary and debug paths call the same assembler with the same explicit inputs and prove each selected
   adapter, not just the transcript fixture. Do not store a second prompt to work around this.

10. **Memory guidance turns a reasonable example into a ceiling.** Ledger `:58` and gate C0 (`:417`) require a model
    request “within” 20–40 MB. Owner §7 explicitly allows more transient allocation when reasonable and collected,
    while refusing GB per operation. MEM-01 (`:425`) still says the 4 GB cap is not the owner's after §7 approved it.
    Keep the measured expectation and the 10× diagnostic; remove invented acceptance limits. The hard 100× P99.9
    rule in architecture decision 010 was mechanically removed by this lane.

11. **Committed fixes remain scheduled as defects.** Ledger LR-LANE1/2 (`:459-460`) says path release drops all claims
    and malformed paths are accepted; `494600ec7` fixed those behaviors and added refusal of multi-test command
    syntax. These rows should carry the commit and stop describing their cause as current. `S6-06a` is now landed
    (`d9789e22e`), so earlier proofless-assignment statements must not be read as installation evidence. The testing
    skill was corrected by integration; this audit did not overwrite that correction.

12. **Some “current” technical evidence is obsolete.** The former `errors.md:263-264` calls structural identity pending and
    says the installed hash uses printed data. `src/seon/id.clj:246-261` already hashes structure; equal maps/sets
    ignore order and vectors/lists differ. This lane corrected the vocabulary row and removed the false prerequisite
    in the errors spec. The data-oriented skill `:24-30` presents old Datahike/konserve revisions as the pins
    Seon records. At the audited HEAD they are `86c3301a00de8c49b2690083b2b1e69677bf0c8d` and
    `e4334756413917305968333bf305e658c177765d`. Re-verify affected library examples before changing their guarantees;
    do not merely substitute a newer hash beside an unread line citation.

13. **Living prose still teaches retired mechanisms and words.** Remaining examples include the flow skill's old
    B2 target section (`:68`), older mechanism links in the architecture guides, and `repl` skill references to the
    turn PRD for virtual-loop proof. Distinguish an installed legacy identifier from a target instruction. Owner
    §9 requires current targets to say eval request, recording, reply, printed value, error and test Var now.
    This audit did not globally rename code citations or erase truthful descriptions of the installed legacy path.
    A wholesale text replacement would make those references false.

## Mechanical corrections made after release

- Architecture README now points to the owning specs and the single ledger. The REPL, flow, UI, canvas, modeling
  and config skill introductions no longer nominate the old turn PRD as the current target.
- Agent-runtime context/history no longer instructs agents to append refresh evaluations, compact and regenerate
  an opening, or exempt HTML from the printer. Canvas guidance carries the same current target.
- UI and data-modeling guide no longer claim the pinned fork silently defaults pull to 1,000.
  `reference-code/datahike/src/datahike/pull_api.cljc:16` sets the default to nil; `:315-323` applies `take` only for
  an explicit limit. Unlimited pull still does not establish completeness or remove a query-work bound.
- Modeling guide and skill now use ordered bridge cells, not another ordinal representation. The bridge
  `src/seon/schema/datahike.clj:112-119,415-436` preserves order, duplicates and presence; older values without the
  cells retain their original representation. The false “28 vectors still lose order” open question is retired.
- REPL skill now reports installed repair delivery: `src/seon/repl.clj:227` renders
  `:seon.cluster.eval/delimiter-repairs` as `:seon.repl/repairs`. The old “not yet delivered” statement is removed.
- Lane skill now requires a lane's own focused proof, with broad tiers at root, matching `bin/codex-agent:56`.
  It distinguishes the installed documentation-only gate and the future REPL-first gate.
- Vocabulary now describes structural identity, data-returning renders and the form face, the static prefix without
  an opening, and the cache target while distinguishing installed SCI retention. Broken wiki links to program
  functions are symbol references. Decision 015 is explicitly superseded, not an active system-turn/result-storage
  design. Decision 010 no longer invents a 100× default and uses errors rather than steering/fault language.
- The errors spec now uses the existing structural identity seam; it no longer waits for an identity rewrite.
- CS-02 sets its printer budget in metadata, not a data member or schema member. SR-F uses the real
  `seon.cluster.eval.edn` resource with fully qualified `:seon.eval/*` attributes; those attributes do exist in
  that family (`seon.cluster.eval.edn:112-114,178-180`). F4c's commit drops `config/default.edn`, which its own
  design explicitly says it does not edit. These changes do not settle the queue or timeout design.
- Multi-test arguments were corrected mechanically in clean, claimed cards: each name has its own `--test`.
  Before the sweep, 52 other cards had 79 bad inline command fragments; 50 were clean and claimable. The dirty
  F4b and S4-07b3 cards were left with their owners. Four already-claimed cards were corrected separately.
  Test selections, implementation design and assertions were not altered by the syntax sweep.

## Every ruling, checked against its home

The ruling names and section numbers are those in the current owner-rulings page. Finding numbers refer to the
ranked list above. No mismatch found in a named home is not a proof that its proposed function exists.

| Owner section | Ruling | Owning document and result |
|---|---|---|
| 1 | Serial ordinary REPL behavior | agent-loop §1.1/§3.3 and AGENTS one path agree; no restriction on the agent's own effects |
| 1 | Agent branches admit REPL-valid changes; findings until merge | agent-loop §1.1 and merge intro/§2 agree; writer refusals are the RP-1w target, not installed proof |
| 1 | Alias retarget follows stored-source reload | agent-loop §3.3 agrees; cards still wait for the answered question (7) |
| 1 | Literals, qualified symbols, prose, trailing words, MCP, :clj, namespace after throw, contracts, empty nil | agent-loop §3.3 agrees; malformed-contract callable behavior is separate from recording it as valid program |
| 1 | Lightweight forgiveness, including MCP repair note | agent-loop §1.3/§3.3 agrees; REPL skill's installed-delivery claim fixed |
| 1 | SCI writes, kondo file analysis, Malli registry classify values | agent-loop §3.3 and ledger UL-1 agree; no new parser proposed by this audit |
| 1 | Nested definitions have their own spans; captured locals live-only | agent-loop §3.3 agrees; turn/batch.clj:241-275 already joins the observation and span and names captures-locals |
| 1 | Entrances are read stages | agent-loop §1.2/§3.2 agrees; member recording count is separate (1) |
| 1 | REPL lookups return data and pinned library source | agent-loop §3.7, S1/H-01 and context reference cards agree |
| 1 | *1, *2, *3, *e only if easy and cheap | agent-loop §3.3 and ledger CN-23 agree; lowest priority retained |
| 2 | One machinery recording transaction, unlimited agent effects | agent-loop §1.1/§3.1 and AGENTS agree |
| 2 | Approved recording and entrants, metadata, twice per test request | Approval is integrated; entrants table still conflicts (1) |
| 2 | Recording failure never hides values | AGENTS total path and agent-loop FINISH agree |
| 2 | Diagnostics use the same live/printed storage roles | agent-loop §3.4, SR-D/SR-I agree |
| 2 | Definition's producing-evaluation pointer disappears at merge | merge §4.5 and MA-11 agree |
| 2 | Shared cluster base advanced incrementally; rebuild fallback only | agent-loop §2 and PB-01/PB-03 agree; no claim the fallback has been measured here |
| 3 | Static prefix + own history + fresh block, no opening/input | agent-loop §1.4/§3.4 agree; architecture and canvas corrected; older authorities remain (4, 13) |
| 3 | Actual proof source/status and broken tests shown | agent-loop §3.4 and CS-01b/CS-01d agree |
| 3 | Task names resolve Seon, libraries and files with revisions | agent-loop §3.4 and CS-01c1–c3 agree |
| 3 | Extend inbox; no inputs function | Spec/cards agree; ledger change columns contradict (6) |
| 3 | One exact debug context, same assembly, HTML printer | Spec agrees; DBG-01's mechanism is incomplete (9) |
| 3 | Configured wire adapters; measured provider tokens | agent-loop §3.4 and CS-03 agree; debug card hardcodes transcript (9) |
| 4 | Tasks replace shelved steps | agent-loop agrees; task-steps is now explicitly shelved, not ready work |
| 4 | Assignment needs proofs and not all green | agent-loop §1.7/AGENTS agree; d9789e22e installed source check; skill updated by integration |
| 4 | Automatic relevant tests after any evaluated form; proofs first; current same-request state; bounded continuation | agent-loop §1.7/§3.5 agree; F4c drops too much queued work and promises bounded exit (8, 1) |
| 4 | send then wait; delete completion aliases; bail with reason; help explains ending | agent-loop §3.5/§3.7 and F7/H-01 agree |
| 4 | Task-declared answer schema, ordinary data, proof completion, live scratch Vars | agent-loop §1.7/§3.5, competency §4.5, A1/F6 agree |
| 4 | No-change completion checks tested main program | agent-loop §3.5 and F5 agree |
| 4 | Assignment key is a value read once | Ledger status agrees but change calls it a ref (6) |
| 4 | Competency ends through real merge; impossible/research/unanswerable/conflicting refactors | competency §1–§8 and agent-loop §3.10 agree |
| 5 | Git words and no invented agent-facing shorthand | merge Words/vocabulary agree; legacy prose remains (13) |
| 5 | One merge!, kept merge branch, resolve or abort, request's target | merge §1/§3/§4 and MA rows agree |
| 5 | Pre-existing red on main stops automatic merge and asks root | merge §4.4 explicitly agrees |
| 5 | Newer green updates same open request | merge §1 and F5 agree |
| 5 | Pure ordered norms once inside merge, recorded, 1s/10s handling | Pure/order/bounds agree; where executed contradicts accepted fork answer (2) |
| 5 | Root ordinary agent path entered from schedule | merge §5, agent-loop §3.10 and MA-12/17 agree |
| 6 | One full live value, small facts/printed text, larger blobs | agent-loop §3.4/SR rows agree; superseded decision 015 now marked |
| 6 | Live results byte-bounded cache, no count limit | agent-loop §3.4/SR-H agree; vocabulary distinguishes legacy installation |
| 6 | Datahike byte-bounded node cache | architecture §2/DH-4 agree as target |
| 6 | Delete finished branch after task and request close | merge §4.5/clusters item 9 agree; actual exit is additionally required |
| 6 | Fork cross-branch revisions feed content keys | LR-CACHEb states the ruling; no runtime verification here |
| 6 | Subtree counts maintained on every write | DH-1/DH-2 states the ruling; no fork performance proof here |
| 6 | Ordered cells, no transaction order or new value type | merge/ledger agree; guide and modeling skill false current claims fixed |
| 6 | Function source stays datoms; oversized function is defect | README-audits AU-Q21 recommendation and ledger agree |
| 6 | Guava encodedLength, direct pinned dependency | DEPS-1 card/ledger agree; no classpath installation claim here |
| 7 | No arbitrary targets; proportionality, 10×, every regression | AGENTS/spec cost sections agree; stricter ledger memory gate remains (10) |
| 7 | Budget breaker must be fixed now | agent-loop §1.8 agrees |
| 7 | Reasonable transient allocation may exceed 20–40MB, never GB | agent-loop §2 agrees; ledger turns example into ceiling (10) |
| 7 | Every bound a dial for 16GB; approved defaults, no host-RAM default | AGENTS/architecture/ledger dials agree; MEM-01 old proposal wording remains (10) |
| 7 | Aero file layer; Malli and database reconcile retained | AGENTS/architecture/DIAL-1 agree as target; not claimed installed |
| 7 | Raw-return bound, bulk exemption, dynamic var and metadata override, model ceiling, finding | agent-loop §2/PR-11 agree; CS-02 metadata corrected |
| 7 | Reviews judge reasonable measured work | Ledger review rows agree; older arbitrary default removed from decision 010 |
| 8 | One printer, general fixes only, no outside clipping | AGENTS/agent-loop agree; active architecture guidance corrected |
| 8 | Flat error values, cause conversion, no printed messages or exception mutation | errors §1/AGENTS agree; repeat-recording algorithm conflicts separately (3) |
| 8 | Plain lazy core elision forms on live symbol | AGENTS/agent-loop/vocabulary agree; legacy code spellings left as citations |
| 8 | Lean contractual values, top-level renders, plain sub-values, bulk declared | AGENTS/agent-loop/PR/RO rows agree |
| 8 | Short fixed Clojure messages, data and runnable fixes | agent-loop §1.9/MS/PR rows agree |
| 9 | Library concepts and names | AGENTS/vocabulary agree; stale identity claims corrected; old pin list remains (12) |
| 9 | Approved eval-request/repl/read/eval/print/caught renames; living prose now | agent-loop/vocabulary agree in principal definitions; older target prose remains (13) |
| 9 | Positive data/Malli composition, no entity kinds | AGENTS/agent-loop History/merge agree; cache “kind” is a cache partition, not an entity tag |
| 9 | Dependency first; own mechanism only with owner-approved gap | AGENTS/ledger library rows agree; MA-8c still chooses competing mechanism (2) |
| 9 | Accepted audit recommendations except two deferred questions | README-audits and ledger mark accepted; normals execution conflict noted (2) |
| 10 | Validated process/cluster maps; Flow/SCI agent state; named counter exceptions; recordStats; new-atom lint | AGENTS and data-oriented §8.1 agree after integration; cache.clj's current counters are an unlanded target, not a false install claim |
| 11 | Ordinary main-JVM branch proof/write-back; host-only isolation; exact evidence gate | RF cards state target; AGENTS transition explicitly waits for RF-2; lane skill's obsolete no-proof instruction fixed |
| 11 | Small fully specified roughly 15-minute slices | AGENTS and ledger ready rule agree; RP-8/F4c/DBG-01 leave decisions for implementers (7–9) |
| 11 | Follow the optimal planned order | Ledger §5 is the owner; duplicate schedules should be retired (4–5) |

### Accepted library answers

The audit checked the accepted-answer list in README-audits against the ledger homes, rather than treating an
accepted recommendation as installation evidence. Coverage includes all accepted questions:

| Accepted answers | Mechanism homes and result |
|---|---|
| 1–2 | Call preparation and Malli instrumentation; UL-1a/b and FK-MALLI state the accepted library composition |
| 3–5 | Fipp once per level, structural identity, Malli spell checking; UL-3a/b and UL-5 agree; identity is already source, stale prerequisite corrected (12) |
| 6 | Caffeine recordStats, no invented per-kind hit rate; AT-8 agrees as target |
| 7–8 | Hiccup and core.async fork/error/IO changes; UL-7, FK-ASYNC agree as target |
| 9–10 | Named EDN parse error and measured read evidence; UL-9, README-audits agree; obsolete refresh designs are not authority |
| 11–14 | Kondo binding observation, checked final writer once, Malli policy composition, one analysis with cache off; UL/PB/FK rows carry answers |
| 16 | Datahike retention fold for blob collection; UL-22/FK-DH carry answer |
| 18–19 | rewrite-clj tokenizer and SCI future-call; UL-25/UL-26 carry answer |
| 20 | Norms through maintained fork seam; spec/MA-8c still contradict (2) |
| 21–24 | Source in datoms, delete zero-hit query caches, cached-at basis unknown, disk floor/ratio; SR/UL/DIAL homes carry answers |
| 15, 17 | Deferred until their deleting rows land; no new answer invented by this audit |

## Documents to retire, retain, or decide

- **Should not remain living documents:** the old agent-platform plan's schedule and local authority file, and the
  phase plan in schedule/README. They duplicate the current ledger. Retire only after surviving evidence and
  incoming links are accounted for; root owns that disposition.
- **Resolved during integration:** `run.md` is now superseded and identifies its old measured basis; `task-steps.md`
  is explicitly shelved. Keep as evidence if needed, but do not link their bodies as current implementation specs.
- **Resolved here:** decision 015 is superseded. Its former system-turn, compaction and unlimited-HTML mechanism
  must not remain active beside agent-loop and AGENTS Presentation.
- **Keep as dated evidence:** the 2026-10-08 measurements, transaction census, dependency audits, vector study and
  this note. They do not own a delivery sequence. Do not replace precise source and performance evidence with a
  living document that loses the commit and harness.
- **Root decision:** approved redesign research still serves as a detailed implementation authority where
  agent-loop's summary is incomplete. Fold the durable mechanism into its owning spec before retiring that source;
  the contradictory test count demonstrates why deleting it immediately would lose an approved guarantee.

## Landing and verification

The mechanical slice committed through `bin/lane commit` as `29ddc7b6b`: **70 Markdown files**, **505 lines added**,
**207 removed**, **net +298**. The audit supplies most of the added prose. The lane gate confirmed documentation
only, skipped publication/boot, and committed the named claimed paths. This landing entry is a separate doc update.

Only Markdown changed: source/test lines added or removed: **0/0**. No program function, contract or runtime
configuration changed; no test reaches the prose. The installed `bin/lane` documentation-only gate expressly skips
publication and JVM boot when no `--test` is supplied. Applying program tests to this slice would contradict
AGENTS.md's rule that documentation never publishes or widens gates. The named tests in corrected cards are the
future implementation slices' proofs, not claimed runs of this audit.

Required checkout lint: `clj-kondo --lint src script test --cache false` completed in **7,853 ms**, **0 errors**,
**1,475 warnings** (exit 2 for warnings). This is not zero findings. Existing warnings include shadowed names,
printing outside the printer and unmanaged futures; they were not modified under another lane's ownership.
There is no hot-path timing or memory delta for a Markdown-only change, and no provider run, live publication,
reload, JVM restart, runtime API existence check or browser-paint claim.

Foreign boundary: concurrent modifications to `bin/lane`, configuration/dependencies, context, test runner, SCI,
turn execution and their tests belong to the active implementation lanes; the F4b and S4-07b3 cards were dirty and
excluded from the command sweep. This slice uses only its claimed Markdown paths. Root's ledger and issue index
were intentionally left for root, with contradictions recorded above. Owned scratch search output was removed.
