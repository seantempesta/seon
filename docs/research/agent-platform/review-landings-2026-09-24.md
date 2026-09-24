---
type: research
status: read-only diff review; five P1 and three P2 findings; no execution approval
created: 2026-09-24
---

# Astra review of 2026-09-24 landings

**Five P1 findings, three P2 findings; no P0 identified.** This is a diff review, not merge approval.
Read CLAUDE.md end to end and the data-oriented Clojure, REPL and testing skills. Reviewed actual `git show` changes, including superseded intermediate fixes, rather than treating landing claims as proof.
Scope is the initial 134-commit source inventory below, ending at `d6f3cc77d`; additionally reviewed config-only `df3b03f6d` (no-provider). Later commits and other lanes' uncommitted changes are excluded.
**All source/test file:line citations below refer to tree `d6f3cc77d`**, not the moving checkout. Landing notes are under `docs/prds/agent-platform/landing/`; names below abbreviate `lane-<name>-2026-09-24.md`.

## Broken things and evidence limits

Read-only MCP `runtime_status` reported PID 39156, started 2026-09-24T21:55:31Z, health faulted, 16 fault signatures, 568 failed tests and 134 unknown. Readiness took 12,341 ms; reported status/problems profile maxima were 2,077/1,960 ms. These observations establish degraded health, not attribution to any particular diff.
No JVM started, test run, reload, publication, configuration apply, branch mutation or paid provider call was performed. No per-landing installation claim follows from that status read. Counterexamples below are static execution traces to test later, not falsely reported reproductions.
Reviewed matching landing evidence, especially harness S0/S1/S3, projection/analyzer keys, SCI caller bindings, faults, custody, render selection and the fork pin. Several notes explicitly report unadopted bytes or unrun regressions; those remain unproven.
Only this report is changed. Source/test delta: 0/0. No new performance or memory benchmark was authorized; existing lane measurements are assessed below. Other lanes' dirty files and sessions were left alone.
Document validation: frontmatter added; the editor's remaining 66 lint diagnostics cite historical dependency pins in other documents. Those files were not edited. No repository-wide check was run.

## Ranked findings, most consequential first

### F1 — P1: a member's own adoption bypasses other active members

`da9910fec`, retained by `d77a9461a`: `src/seon/cluster.clj:2268` invokes `marked` without the write lock whenever the dynamic member lock matches. Two concurrent read-side members A/B suffice: A adopts while B is still executing; B's compiled Vars can change mid-body. Bound child threads inherit this escape.
The nested read path at `cluster.clj:2234` also skips validity: catch a failed nested adoption, then start nested work, and that work enters an unknown program. This violates exclusion and “no reload under an evaluation.”
Smallest fix: refuse/defer member-initiated adoption to after member exit; route adoption drills through the existing isolated platform owner. Do not upgrade a held read lock. Check validity on nested entry too.
Regression assessment: `test/seon/cluster/adoption_exclusion_test.clj:58` explicitly accepts own adoption but has no second active reader. Existing external-writer test would fail without the ordinary lock, yet passes this bypass. Add two readers plus nested adoption, and failed-adoption/nested-entry cases. Notes: fix-adoption-excludes-tests, harness-s3-program-validity.

### F2 — P1: recorded loaded provenance can describe a different program from the body

`389d2d265` / `548d7b6f4`: `src/seon/test.clj:1053` and `:1076` seal loaded identity at admission; `:1496` acquires the captured branch before member exclusion; `:1342` resolves core tests to current host Vars inside the later exclusion.
Counterexample: capture/admit A with a core test `(is false)`, adopt B changing that test to `(is true)` before member acquisition, then acquire captured A against loaded B and execute. The core resolver still chooses B's host test, so B's pass can be recorded with A's reach/provenance. `sci/eval.clj:2661` does check whether an acquisition's loaded program is still current: it catches adoption after acquisition, but B is already current in this ordering. It does not establish that the core test Var matches captured A. Reuse at `test.clj:870` only requires some loaded commit to exist.
Smallest fix: under the existing member exclusion, refuse if execution's loaded program is incompatible with the admitted reach; seal actual execution provenance at that boundary. A new stamp at admission cannot establish execution identity.
Regression assessment: the missing-provenance and loaded-drift selection tests would fail without their respective checks, but do not interleave adoption between admission and member acquisition. S3b's scratch-Var test intentionally sees the new Var without checking recorded evidence. The S3 note acknowledges the admission/execution timing gap; the core-test provenance case needs a real request regression.

### F3 — P1: projection builder key is taken from the read database, not the loaded JVM

`d8951c477`: `src/seon/db.clj:1473` selects the first source-commit datom in the supplied database; `:1439` hashes that historical program. The projection itself is built by current compiled code.
Counterexample: cache a branch's projection under builder A, reload builder B in the shared JVM, then read that unchanged branch. Its adoption record still names A, so A's cached projection is returned under B. During load-before-record, B can also populate an A key. Multiple cluster records make “first” an additional authority ambiguity.
Smallest fix: carry the actual loaded builder identity from the existing process/adoption owner into this derivation, and refuse mixed-program construction. Retain the one cache; do not add a writer stamp or another cache.
Regression assessment: `test/seon/db_test.clj:2436` changes row digests and supplies the resulting digest directly to private `content-projection`. It proves key separation but would pass with the public authority wiring wrong. Exercise public `carried-projection` on a retained old branch across a builder adoption. Note: fix-projection-key-omits-builder.

### F4 — P1: panic is swallowed and the failing graph continues

`4fd59fa87`, retained by `d8d80331d`: `src/seon/flow.clj:210` catches the panic from `fault!`, prints it and returns nil; `:271` / `:274` return the old state to Flow. `src/seon/fault.clj:224` throws precisely to enforce panic, including failed storage.
Counterexample: a transform fails under :panic, then a second input is queued. The wrapper supplies a normal transition result and the loop can process that input. A stop-transition requirement has been applied to every transition/transform.
Smallest fix: distinguish stop acknowledgement from an ordinary panic; use the Flow/graph owner to stop the affected graph without self-joining on its proc thread. Preserve synchronous durable recording and cause evidence.
Regression assessment: recorded-fault storm coverage proves no dropped records, not panic cessation. The panic-print regression even asserts nil return. Add a real graph assertion that the second input never executes after panic or failed storage. Notes: fix-fault-route, fix-panic-print-sink. “Panic visibly” remains violated.

### F5 — P1: shell discards evidence that termination did not finish

`f5023c0f9`: `src/seon/cluster/process.clj:187` returns false if a captured process survives. `src/seon/shell/jvm.clj:344`, `:362`, `:387` discard that boolean, then report an exit/deadline disposition or unwind.
Counterexample: a captured descendant survives the cleanup deadline (including delayed observation after force). The timeout result nevertheless says “The process was terminated,” and ownership can end while it lives. The old shell helper surfaced its wait failure.
Smallest fix: consume the owner's result and retain execution/resource ownership until observed exit; report the missing terminal event, never successful termination. Keep the single termination owner.
Regression assessment: TERM trap, TERM-resistant child and normal-parent-exit regressions discriminate against the old implementation, but all use children that exit inside the cleanup bound. None covers false from the owner. The separately disclosed 50-ms double-fork sampling gap is also not a guarantee of complete process-tree capture. Note: fix-process-tree.

### F6 — P2: S0's regression assumes an empty inherited problems population

`e33009381`: `test/seon/test/interrupted_request_test.clj:102` / `:103` compare the complete failed/unknown lists with exactly one fixture symbol each.
Counterexample: the canonical branch contains any unrelated failed/unknown current test, as today's live branch does. Correct S0 production behavior still fails these assertions. This conflicts with the canonical inherited-world test model.
Smallest fix: select the fixture's own symbols/run in assertions, retaining checks that its red and unknown remain visible. The earlier per-symbol assertions are useful and would fail without S0; these global assertions cannot establish its regression status. Notes: harness-s0-unknown-results, fix-problems-prose-bound.

### F7 — P2: macro classification depends on an unkeyed loaded namespace

`55af9e04b`: `src/seon/fn.clj:813` derives macro status from `find-ns` / `ns-resolve`; `:857` turns it into stored host-bound facts. Identical source/index inputs before and after loading an external macro namespace can therefore produce different facts.
Counterexample: index a caller of an external &env macro before its namespace loads; later load it and acquire an affected caller. The unchanged row remains interpretable. `c3a225dc7`'s analyzer-code digest does not change merely because that dependency namespace loaded.
Smallest fix: obtain the macro fact from dependency analysis at the publication boundary, or refuse unresolved classification there. Do not add another namespace roster or silently equate absence with false.
Regression assessment: the go-loop/function distinction is a useful parent-sensitive test, but requires the macro namespace already loaded. It misses load-order determinism. This limitation is explicitly disclosed in fix-env-macro-callers; the analyzer seal does not close it.

### F8 — P2: top-level require :rename is silently misrepresented

`a2bf4d8ed` / `a209c6d3f`: `src/seon/fn/analyzer.clj:559` accepts top-level requires/refers, while `:562` still detects rename only within the ns form. `src/seon/fn.clj:1178` can refuse only renames it receives.
Counterexample: `(require '[clojure.set :refer [union] :rename {union join}])` outside ns. The row cannot represent local `join` and does not refuse; SCI reconstruction differs from the loaded namespace.
Smallest fix: attribute rename options to the containing require through the analyzer's form ownership, then issue the existing named refusal. Do not scan every bare :rename keyword or restore JVM alias copying.
Regression assessment: added top-level alias/refer coverage is parent-sensitive, but its unrelated-body-keyword case is not an actual top-level rename. Add this counterexample. No current checkout caller uses it; priority remains P2. Note: fix-ns-usages-span-filter.

## Area verdicts and regression sensitivity

“Clean” below means no additional concrete defect found in the reviewed diff, not installed/green. All inventory commits belong to these area assessments; superseded fixes are assessed in their final composition.
- **Harness:** S0's value/error split and count-based green judgment are clean in source (F6 affects proof). S1a/b routing ownership and stop/join-before-branch-release are the right smaller composition, with no second graph registry. Its removal drill reportedly gives 4 failures/3 errors; fresh-store fixtures and nested/concurrent lifetimes remain limits. S3 is blocked by F1/F2. Unstarted-member reasons (`cb632bd81`) correctly preserve unknown; its rerun-C assertion does not prove an unexited B cannot overlap.
- **Runner storage/output:** `62d97cfcc`, `f46e9939b`, `10d629c54`, `daa833e32`, `abc4591a2`, `7e18d08f3`, `8be073cfb`, `bb8adf9ad`, `6ac1b356a` are clean: keep whole durable assertion evidence, bound presentation, read counts without rendering, refuse missing digests. Huge-message/blob assertions discriminate against the parent; a namespace run/reused green alone does not. Restoring single-member recording (`a9cb654d7`) adds no parallel recorder.
- **Publication:** analyzer seal `c3a225dc7` and gitlink pin recognition `2614d0353` are clean within declared inputs. Digest-change tests prove the derivation, not the complete publish/reanalyze/reseal cycle; landing live convergence is stronger evidence. Index pull-many and steward partition (`36abe8bef`, `f4c56ad12`) use existing owners. Whole reanalysis remains 16 s; not sub-second acceptance. Builder key is F3.
- **SCI:** unloaded host-bound refusal `f2bc7088a`, unchanged caller host bindings `b855a8a17`, branch identity `57d89df55`, holder ownership `bc86def40`, private-def scope `88289e006` are clean. Branch execution assertions exercise resolved behavior, but digest-only perturbations are weaker than changing a callee body. Producer fix `a2bf4d8ed` correctly retires `4f880a823`'s second alias path; F7/F8 remain.
- **SCI entry:** `37f733c2d`, `7a28ffb0e` followed by `8a531008a`, and streamed printing `fbbc9c596` are clean under the explicit option-A ruling in fix-entry-eval-2. Nested definitions/all-contract arming and over-bound output tests distinguish the parent. Same-form calls precede checking; other effects survive refusal; no-database requests cannot gate rows. These are accepted limitations, not a claim of pre-evaluation gating.
- **Fault/error facts:** branch occurrence identity, branch-local windows and removal of the MCP cursor (`8e7721d0c`, `9ce7e0992`, `378f2d77c`, `0e4f438b8`, `43aa341b6`) are clean compositions. Resolution-derived delivery/status (`f65e906d1`, `5d1c0f592`, `de13aa02c`, `6b4f88d2f`) avoid a second open-state registry. Typed refusal propagation changes are clean; several have only live examples, not a discriminating regression.
- **Error evidence/printing:** `5c968dd34`, `addab5ae5`, `8b696b15d` improve capped evidence and preserve each cause's raising frame without full stacks. Blob tail-marker tests distinguish the old recorder; chain tests distinguish huge-ex-data loss. Direct commit-tx paths and evidence too large even for the classifying partition remain outside that proof. `d8d80331d` fixes huge throwable data, but its note explicitly leaves the ordinary message/receipt print unbounded. It is not a general bounded-panic proof; F4 addresses execution.
- **Configuration:** explicit no-provider `df3b03f6d` and absent escalate-to `872281634` are clean, authorized policy changes using existing dials. Do not manufacture a delivery-law violation against the owner's express exception. Toggle tests are sensitive to the default, but the original global occurrence counts inherit faults; the landing reports follow-up repairs. File changes do not prove effective live config reconciliation. Cluster-specific config reads and absent-versus-refused handling are clean.
- **Deletions:** `d2604ede9` + `bff4fac4b`, `4d0e15d23` + `6a6db1862`, `53a038419` + `104239f33`, dead aliases/analyzer/test.arm and task removals are clean retirements. They remove duplicate paths instead of replacing them. Test deletions (`e56bf63ef`) are not positive proof of surviving behavior; final load/caller checks remain pending under the load rule.
- **Datahike:** `d6f3cc77d` pins `4157916b`, including implementation `fa0cc1a7`: seed existing fused-root storage after flushing each index, rather than invent a Seon cache. Clean. The repaired fork regression clears soft references, uses a fresh node store and establishes unwritten roots; that arm would fail without the pin. A forced-write branch alone would not. The final regression was not run. Earlier variable-attribute planner fixes `e45e27d73` / `b626390b2` support retirement `ebfa81ace` of Seon's grounding workaround; clean upstream composition.
- **Custody:** `b2fcb5225` chooses branch at the transaction writer; `5a60f51d8` reads the exact [branch agent-id] acquisition entry. Clean, no second custody authority. Reported parent failure and live/isolated sibling assertions discriminate against label inference. They do not prove arm/release; S1 is separate.
- **Render/prose:** `14a9291ad` is a small clean shape-selection change; positive optional-map and negative empty/invalid cases distinguish its parent. `0039ff531` uses the existing value bound with counted elision/requery; its 600-member direct-render test distinguishes unbounded prose. It did not prove ordinary selected rendering under a caller's profile; builder cache F3 blocks general adoption claims. Request-profile and explicit-world fixes (`c3c48bde2`, `c1cd41b25`, `f6a81c932`) are clean.
- **Other source landings:** admission/nil/undefined-slot consistency, reader-to-kondo clause succession, read-replay refusal, native query values, fixed identity printing, log rotation monitors, operator reader/error output and named refusal propagation are clean on diff inspection. Boundary/negative assertions are the relevant parent falsifiers; docstrings, removals and same-green aggregate counts are not independent regression evidence. No additional registry/cache/stamp is justified by these changes.

Existing measurements do not establish a clean performance gate: projection hits 0.012→0.028 ms, namespace clause attachment roughly 12→35 ms, and render matching 0.005→0.077 ms exceed the literal 20% rule despite small absolute costs; notes mostly appeal to the 50-ms alternative. Root should clarify/apply the stated “20% or 50 ms” rule with comparable probes. Allocation and installed timing evidence is absent for several paused landings; unavailable is not a pass.

## Source inventory

Exact initial command: `git log --since="2026-09-24 00:00" --format='%h %s' -- src resources script reference-code/datahike`.
The config-only no-provider commit above is deliberately additional. Inventory is newest-first; review judgments are grouped by area above.

```text
d6f3cc77d datahike: pin fused index roots on commit! (fork fa0cc1a7 + test 4157916b)
8b696b15d error: an over-bound fault's kept chain holds each cause's raising frame only
548d7b6f4 test runs: record the loaded program's adopted commit at the writer; evidence naming none is unknown (S3c)
872281634 config: a core fault no agent answers for wakes no root turn by default
389d2d265 test: a member resolves its test inside the adoption exclusion; an unknown program stops the request unknown (S3b)
053a91b92 turn: agent-eid throws a refused agent lookup whole instead of handing its map to the wake seeks
addab5ae5 error: an over-bound fault's evidence keeps its cause chain
76e4e0ab6 error: a fault no agent answers for escalates to root with or without a throwable
d8951c477 db: key carried projections by the loaded builder (digest of definition digests reached from seon.schema/load-projection; bases from the same builder only)
a209c6d3f fn: namespace-context docstring names top-level requires
a2bf4d8ed analyzer: a namespace's requires, aliases and refers are every usage clj-kondo attributes to it (:from), not those inside the ns form's span; top-level (require '[seon.effect :as effect]) reaches my.edit/my.fs/my.shell rows, so SCI's caller-bindings copy of JVM aliases is deleted
cb632bd81 test runs: a stopped request records why; its unstarted members stay unknown
d2604ede9 flow: delete the work launcher's compute half (submit!!, execute-work!, capacity observer)
43aa341b6 mcp: a new branch's error window opens at its fork basis, not at 0
bff4fac4b turn: delete the uncalled submit-evaluation!! and submission-time-limit-evaluation (dead compute-launcher callers, -45)
d77a9461a cluster: an adoption failing after roots change keeps test members out until one records (S3a)
4f880a823 sci: an interpreted caller binds the aliases its loaded namespace made outside the ns form (my.edit effect/request! no longer refuses branch acquisition)
14a9291ad schema: a map declaring a render pair is a shape without a required attribute; problems selects ai-prose
d6e93ce72 agent: release stops and joins every owned graph before it closes the branch (S1b)
2a543c27a problems: failed-tests counts only symbols that are current test rows
aed1b3838 agent: the execution handle owns the routing its graphs arm in (S1a)
0039ff531 problems: ai-prose is bounded once by the value renderer; elision names count and requery
e56bf63ef test: S12 + turn-suite deletions (56 tests, -2,023 lines); cost measurements to a script
104239f33 schema: delete build-projection's pure-data+delta branch, compose-projection-data and reverse-dependencies; nothing produces a delta after 53a038419
e33009381 test results: an incomplete member is its own typed unknown; green? judges captured counts
e9f6dc805 schema: the per-agent display-label rule judges admission, never a projection build
53a038419 schema: delete projection-delta; no src caller produces a divergence delta after 4d0e15d23
f4c56ad12 fn: index rows name a data entity (steward agent) by its identity; the partition stops the walk
ce149b23d operator: a thrown refusal reports its own message; file the unpublished script-namespace gap
1a3a9acbe operator: read prepl values with Clojure's reader so refusals carrying Vars and objects arrive whole
4d0e15d23 db: delete carry-derived-projection; its last test caller reads the connection's database (F1, projection-call-cost 36af5c620)
36abe8bef fn: read index rows with one pull-many per ref depth, not one pull per entity (file-rows all paths 2374 -> 700 ms; full reanalysis 24.8 -> 16.0 s)
6a6db1862 publication: previous database is the plain branch value; carried-projection derives it (F1 caller)
c3a225dc7 publication: key program rows by the loaded analyzer (seal :seon.source/analyzer-digest; a changed analyzer reach reanalyzes every file once)
55af9e04b fn: a JVM macro from a namespace SCI does not interpret makes its caller host-bound (go-loop callers no longer refuse branch acquisition)
c3c48bde2 render: request-profile refuses a carried non-profile at entry and names a carried refusal as the profile member's
5a60f51d8 my.agent/branch: mode is the acquisition's own decision read from its context-state entry, not a cluster-label comparison
b2fcb5225 agent: creation records the transacting connection's branch; isolated worlds hold their own context state
57d89df55 sci: a 3-arity fork keeps its base context's cluster instead of the invented current-src; the realities test compares against its parent's interpreted set
d8d80331d flow, store: an unstored panic prints its causes through render.value/floor, not whole Throwable->map values
b855a8a17 sci: interpreted affected callers resolve every unchanged name to its compiled host Var; unchanged rows in unloaded namespaces refuse by name when called
10d629c54 runner: an assertion message prints bounded; the whole message stays on the stored failure
f2bc7088a sci: a host-bound row with no compiled Var to override refuses by name when called, not the acquisition
eb14a2f22 turn: a refused next-work derivation escapes as its own refusal, never as a situation
a70688cc8 schema: the stored-nil rule judges admission, never a projection build
5c968dd34 error: a capped fault keeps its whole evidence in the blob tier on every recording path
2bd861329 test: member bodies hold the program-change read lock, so no adoption reloads under a running member (patch from lane fix-adoption-excludes-tests)
da9910fec cluster: adoption excludes running test members; a changed core test adopts before its gate
0e4f438b8 repl: an evaluation's response carries its branch's new open errors
147c59aec schema.datahike: the storage refusal says a stored attribute needs a storable type
e86043f81 schema: [:or :nil X] value schemas get the same stored-nil refusal as [:maybe X]
e75295fee issue: status shows a red test's failure body from its reports through seon.test/failure-message
f6a81c932 transcript: the ledger prefix check throws a refused render profile instead of carrying it
6ac1b356a test: an admission refusal names which evidence disagreed
f46e9939b runner: a recorded result read builds no failure text
62d97cfcc test: an assertion message above the blob threshold is stored as a blob, never inline
6b4f88d2f operator schema: declare the :faulted health state status now reports (de13aa02c added it without the enum)
9bba9e000 fn: a file's ns clauses from the publication's clj-kondo analysis, not the reader conform
f1c876882 boot: an uncaught throwable without its world is printed, never stored on the sole instance
9ce7e0992 fault: record a core fault on its branch and copy it to the cluster branch
410191cd9 turn: an agent dial whose read refuses is not an absent dial
48251302b turn: an unbound result settles red at its producer, never from shown text
97b17141a prompt: an unread usage history is not an empty one
d1ac87720 schema: a predicate namespace that fails to load is one declared error
a1f1d80ce schema: an undefined :any/:some slot is advice at every entrance, never a refusal
c1cd41b25 render: an evaluation renders with the profile and projection its caller hands
90b7a13dd test: delete dead seon.test.arm; its two instrument tests use the surviving owner
393846eb2 instrument: a refusal that cannot be built keeps the report's expected schema and offending value
f65e906d1 error: delivery once per opening and the render's open state derive from the resolution
883cc29d8 schema.admission: a core undefined slot must justify itself
bb8adf9ad runner: reach-digests refuses a declaration without a stored digest by name
ab7cc7190 schema: bootstrap admission compiles each referenced declaration in its own registry
a7768c195 db: connection-identity answers a released connection with its typed value
78161085b fn: namespace clauses from the one reader, not a second parser
931a45005 mcp: resolve the operator's running instances by their owner, not through seon.cluster's refer
378f2d77c error: each evaluation shows its own branch's errors by basis window; the MCP cursor is deleted
97f9d9451 call-preparation: argument-validators declares its contract
39ca6521a sci.eval: an unreadable cluster config refuses arming, never defaults :record
becc992e9 call-preparation: the row basis falls back only on the declared no-history refusal
b7d4582cf agent: steward-of refuses an unreadable database with its invalid read
9411e8640 schema: a predicate namespace that fails to load is not an unresolved predicate
f053f9477 dev.state: delete the two uncalled process aliases
f2e164bdb cluster: delete read-advertisement, which has no caller
732b34072 cluster: an unreadable on-core-error dial panics; the fault committer never defaults to :record
c68666dba call-preparation: state why hook calls three definitions unarmed, with today's cost
7e18d08f3 runner: a reported throwable renders its whole bounded cause chain
8be073cfb runner: a reach leaf is the declaration's stored definition digest
802dbd926 runner: the drift snapshot reads the running clusters from their owner Var
19f21d9a1 call-preparation: hook refuses a call it cannot prepare instead of running it bare
8adf64a56 agent: assigned-to refuses an unreadable database instead of answering none
68a23991f admit: refuse an over-bound string leaf from its own size, never by printing it whole
72ae5a2c1 call-preparation: plan-for returns its read error, never "not contracted"
d0ca6de68 process: an unknown start instant refuses; an exited member is absent, never a nil generation
a9cb654d7 test: revert checkpoint ab7e4a643's batch recording and its test
9b5eefbb7 db: a mistyped query value refuses as a read and as a replay (fix-read-replay)
4fd59fa87 fault route: every proc throw is one fault! on its own thread; a failed write always panics; no first-ever dedup
7c761e0c9 fn: delete span-refused!, whose race no analysis can reach
30b570d91 instrument: instrumented reads each namespace's mappings without copying them
daa833e32 test: an execution failure's stored text is the value renderer's bounded floor
abc4591a2 runner: store each testing label as its printed string
2614d0353 publication: a changed gitlink is named by its pin, never by following its archive link
91c9079a9 fix-config-read-2: oversight, agent-history, render profile and web read the caller's named cluster
1b10216c6 sci.eval: refusal recording keys on seon.db/connection-identity
8e7721d0c error: each occurrence records the branch where it happened
bc86def40 sci.eval: acquisition attributes live on the projection's own holder
fa13c41a8 fn: a file deleted between listing and read is the declared refusal
f5023c0f9 process: shell terminates through the one owner — TERM, grace, verified force, awaited exit; normal exit reaps orphaned descendants
8a531008a sci.eval: gate and arm every definition the evaluation made, not the reader's top-level defn
c01809720 schema.admission: one completeness rule — delegate to schema.internal through register!'s calls; fix the :inst crash
ebfa81ace fn: retire ground-attribute-rules; the Datahike fork plans bound-attribute variables (f4614c9d, 620721d6)
f077a06b6 fix-config-read: prompt and transcript calibration read the caller's named cluster
b5a76953a reader: namespace clauses through clojure.core.specs.alpha ns grammar
b626390b2 datahike: filter a variable-attribute scan by a value bound elsewhere (fork 620721d6)
43b3497eb turn: a refused disposition settles through the one unrun-evaluation constructor
ad6316c02 maintenance: rotate-logs! copies and truncates under System/out and System/err's monitors, losing no line
c45e98f60 seon.id: hash printed identity under fixed print bindings; stored ids unchanged
0cb5fec13 analyzer: delete the dead analyze-forms family and its two tests
e45e27d73 datahike: a bound variable attribute reads one AEVT slice per attribute (fork f4614c9d)
fbbc9c596 sci.eval: stream printed output under max-bytes; a crossing print refuses with admission's byte-bound error
efe39880e analyzer: a source changed during analysis is the declared refusal
e868e5ec5 mcp bridge: every tool response carries the open faults this session has not seen
de13aa02c cluster: status lists each open fault and derives health from them
6780e926a tasks: retire the v0.1 toy task files (seon.note examples); code tasks move onto the code they are about
5d1c0f592 problems: open faults are derived from the one resolution writer
43addd46c cluster: a declared refusal carries the disposition request! reads
35585cac6 fn: ground declared attributes so edge rules read one index slice, not the store
88289e006 sci.eval: find agent-private defs by namespace identity, not a whole-program walk
7a28ffb0e sci.eval: refuse a defn without :malli/schema before evaluation
37f733c2d sci.eval: arm a validated contract at evaluation, not at commit
0dce6e31c sci.eval: every program namespace row installs its aliases and imports
b29c2ac77 db: native q results skip the decode walk (fix-db-decode)
dcd76fc21 schema.internal: admit :nil in agent contracts; [:or :nil X] follows :maybe rules; contract-error! accepts symbol identity
fef94ab7a error: the fault opener calls seon.error/for-agent; capped occurrences render their message
3dbf90999 mcp: results carry printed EDN, the caller's source and no generated form
```
