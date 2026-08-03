---
type: prd
status: active
tags: [prd, agent, architecture]
---

# What is not settled

**THE PROGRAM lives in README §3 ("THE PROGRAM, 2026-07-28 evening")**
— the agents-are-flows rebuild F0–F4 + everything re-sequenced + the
collected owner decisions. This file stays the working edge below.

[Every render/context statement in the dated blocks below — "blocks survive
only as static scaffold", "static blocks reduced to scaffold", the E/A/V
invalidation shape, slot redirect, and banded/hysteresis ordering — is
superseded by README "Ruling 2026-07-31" and "Rulings 2026-07-31 #2". The
dated blocks stay as the record of what was believed when.]

**ADDENDUM — 2026-08-03, RULING #51 FLOW-EDGE CLOSURE.** Four crash-model
edges are closed: the work launcher uses Flow's stock priority control
protocol; active provider-call teardown has a loud provider-derived backstop;
agent fault fan-out parks on virtual `:io` tasks; and every cluster owns its
launcher graph while sharing only the process-root executors. Commits:
`b22b58d33`, `d62561f24`, `655b2b004`, `53ca533cd`, `b50050968`, and
`d3e97d2ea`.
Measured 1,000-source fan-out changed from +1,017 platform threads to zero;
the integrated two-cluster boot gate passed 28 tests / 133 assertions. The
agent/turn combined rerun is presently unverified at the independent schema
lane's missing `:seon.db/trigger` fixture-base boundary; the focused stop gate
passed before that churn.

**ADDENDUM — 2026-08-01, RULING #35 IMPLEMENTED; LIVE PROOF PENDING.**
Provider reasoning now remains separate from visible text through one-shot and
streaming parsing and settles on the attempt row through ruling #25's existing
65,536-character inline/blob split. The same sliding-1 render input carries a
lossy reasoning prefix; the web UI uses one collapsed disclosure for streaming,
settled, and historical attempts. Attempts are transcript-owned, so the generic
walk cannot expose their raw reasoning facts to `:seon.render/ai`; HTML joins
reasoning only after transcript budgeting. Schema-derived blob discovery already
includes the new digest attribute automatically. Commits: `983121aa1` and
`7bd877df1`. Focused source loading is green. The recurring render gate and the
fresh private-root inline/blob/no-carry capture wait on the concurrently edited
bootstrap/transcript tree reaching a coherent source-index boundary.

**ADDENDUM — 2026-08-01, RULING #34 LIVE.** Uniform per-agent AI settings are
landed and proven through real DeepSeek calls in a fresh private operator root.
One agent inherited shipped `thinking :disabled`; one same-ident agent fact
selected `:high`, and their attempt rows recorded 0 versus 519 reasoning
tokens with the divergent effective settings. A sparse config apply changed
the same worker's next call from `deepseek-v4-flash` to `deepseek-v4-pro`
without changing PID or graph identity. Focused gate: 148 tests / 643
assertions. Full evidence:
`research/ai-settings-live-proof-2026-08-01.md`. The next boundary is the
owner's frozen full gate on the quiet tree.

**ADDENDUM 7 — 2026-08-01, 1C CHECKPOINT.** Stateless resume and its
required value/blob tier are landed at `92d2e39be`, `78b1e6eca`,
`c4002a83a`, and `319fc6ccb`: intern-all → bind faithful values → re-evaluate
only proven-pure forms; SCI analysis records host interop; oversized faithful
values use content-addressed blobs; schema-derived history reachability keeps
their konserve keys live. A fresh private operator root at
`tmp/session-1c-live-root` published current source and booted PID 30071.
The supplied phase A recorded the 200,000-element `big` value as a 1,288,891
character blob and `scale` as source; phase B returned
`[200000 40 "Ada, Grace"]` cross-agent. The focused gate passed 39 tests / 168
assertions. `a1100e9e1` adds the missing function-inside-a-map falsifier: the
value tier refuses the nested closure and cold restore reconstructs it from
the pure form. This closes 1C/1C-prime; the next Lane 1 boundary is 1D, not
another resume mechanism.

**ADDENDUM 21 — 2026-08-03 OVERNIGHT SESSION OPEN. THE OWNER-DECISION
BATCH FOR THE RENDER MODEL IS READY.**

Session state at open: ADDENDUM 20's three actions done. The four
evening lanes (open-maps, opaque-test-fix, renderer-kernel,
per-cluster-history) collected and verified — open-maps' zero
`{:closed true}` claim confirmed by direct count, `bin/seon init`
green on the quiet tree (commit `6a6ff268`). Full suite re-running in
the background at session open. Three lanes occupied per ruling #44:
`suite-speed` (tier 0, mid-flight — it owns the uncommitted
`src/seon/test/runner.clj` timestamp change), `union-codec` (tier 1A),
`any-audit` (tier 1B).

TREE NOTE: `src/seon/sci/admit.clj` and `src/seon/sci/eval.clj` carry
the renderer-kernel lane's DELIBERATE uncommitted prototype
(`invocation-arm`, `admit-value`) — referenced by
`research/render-model-2026-08-02.md` as "not landed evidence".
Preserved as `tmp/renderer-kernel-prototype-2026-08-02.patch`. Tier 2's
kernel group must take an explicit handoff of those files; no other
lane touches them.

OWNER-DECISION BATCH (tier 2 stays stopped until ruled):
`research/render-model-2026-08-02.md` was read whole by the
orchestrator. It carries EIGHT open decisions (§Open owner decisions,
each with options and a recommendation): (1) missing-declaration-with-
candidates behavior; (2) symbol-only producers vs in-process function
arms; (3) what the floor is; (4) declaration-time validation seam;
(5) whether `nil` omission stays legal; (6) per-call cache before or
after the kernel switch; (7) repair-run causality mechanism; (8) what
a non-owning agent sees. Plus ELEVEN behavior changes needing explicit
acceptance (§Behavior changes), the sharpest being: output-only
identity makes all 33 Hiccup producers renderer candidates (helpers
included); every first-party render pays guarded SCI admission; HTML
stops exposing internal error cards. Two findings escalate beyond
render: `error.clj:602` has a production regex (regex law); and
finding 12 confirms tier 1A's codec gap as the prerequisite for
decision (2). The orchestrator endorses every §recommended option —
they are each the simplest constraint consistent with rulings #46-#48
— but none is implemented ahead of the ruling.

**ADDENDUM 20 — 2026-08-02 SESSION CLOSE. START A FRESH SESSION FROM
HERE, THEN READ `plan/overnight-2026-08-03.md`.**

FIRST THREE ACTIONS, in order:
1. `bin/codex-agent summary open-maps` — the last lane of the session was
   still running at close, migrating `{:closed true}` out under ruling
   #48. Collect it, verify its claims, commit anything coherent it left.
2. `bin/seon init` then `bin/test` — publication was BROKEN mid-session
   by that migration and repaired; confirm both. VERIFIED at session
   close on the settled tree: **862 tests / 4,276 assertions, 3
   failures, 0 errors**, and all three failures are ONE known cause —
   `seon.config-test/the-default-document-has-one-canonical-complete-location`
   and `zero-overlay-compilation-resolves-every-registered-config-attribute`,
   filed at `issues/config-derivation-drops-one-backup-attribute.md`
   (the derived config attribute set drops
   `:seon.config.ai.backup/api-key-variable` even though all four
   backup keys are declared identically). No other failures. Fixing
   that one defect should give a clean suite.
   NOTE the run retained its failed isolated operator root at
   `tmp/test-runs/run.SmGhgm` for inspection — that is the liveness
   work behaving correctly, not litter, but clean it up once the
   config defect is understood.
3. Read `plan/overnight-2026-08-03.md` WHOLE. Its first standing
   condition — READ THE WHOLE SPEC, DO NOT GREP IT — is there because
   partial reads produced three wrong conclusions in one evening,
   including two by the orchestrator about a document it had already
   opened twice.

WHAT THIS SESSION SETTLED (rulings #41-#48 in README, all with their
reasoning): seon.db is the one database namespace with dual
positional/argument-map interfaces and elidable custody; agents receive
only public vars; custody derives from the cluster ctx and the write
door refuses a foreign connection; a corrupted provider stream fails the
turn rather than splicing agent code; maps are OPEN because the system
exists to accrete, and a key's meaning never changes once declared; no
naming conventions and no regexes without permission; everything is
declared and queryable, and a question you cannot answer by query means
a missing FACT, not a missing convention.

WHAT IS BUILT AND PROVEN: reachability closed and live-proven with a
real model turn; ctx-derived custody; the write-door custody fence;
stream integrity; suite isolation, progress output and a loud silence
backstop; `disarm!`'s readiness protocol (the real cause of an 87-minute
hang, and NOT the child JVMs everyone assumed); bounded Flow submission
with its control-priority issue DISSOLVED rather than patched; the
analysis-gate fix; 14 `.cljc`→`.clj` conversions; SCI copy-on-write
(fork SHA `72150fd44`) making candidate contexts viable; the store model
validated to 0.05%; seven `:db/noHistory` attributes; `created-at`
deleted; per-cluster history measured at 37.9% on a real GPQA replay.

WHAT IS OPEN: `plan/overnight-2026-08-03.md` has it in four dependency
tiers plus a "mixed models to collapse" section. The short version —
the union read-decoding codec is the unblocker; the render model needs
its ~200-reference vocabulary collapse and the guarded kernel; the
`seon.db` call-site sweep wants a quiet tree; MCP's value chain waits on
a protected owner.

THE WARNING THAT MATTERS MOST: five recorded figures were disproven in
one evening, four in storage alone, and ONE OF THOSE by our own fresh
measurement hours later — a census attributed 187 MB to an attribute
whose deletion saved 9.66 MB, because ATTRIBUTION IS NOT A
COUNTERFACTUAL. Three orchestrator claims were also wrong. Every number
you inherit in this repository is a hypothesis until you reproduce it.

**ADDENDUM 19 — 2026-08-02 night, THE CUSTODY/RELIABILITY WAVE LANDED.
START A NEW SESSION FROM HERE.**

LANDED AND VERIFIED TONIGHT, each independently re-checked by the
orchestrator before acceptance:

- REACHABILITY (phase 2 of ruling #43) — `0c3a3d535`, live-proven in
  `ca00eb12a`. The install seam publishes `ns-publics`, so agents no
  longer receive private implementation Vars and the route to every
  other cluster's connection, flock, flow graph, and ctx env atom is
  gone. LIVE, not fixture: fresh isolated root READY in 1,449 ms, a
  real DeepSeek turn evaluated `(+ 40 2)` and settled through
  `my.run/complete`, render context 5,529 tokens, all four page routes
  200, clean teardown. Ruling #20 is preserved, not narrowed —
  `:seon.fn/private?` was already the computed boundary every other
  agent-facing projection honored.
- STREAM INTEGRITY (ruling #45) — `cbaffa1f0`. An unparseable `data:`
  payload returns `:seon.ai/unparseable-body` and the fold STOPS, so
  text either side of a dropped chunk can never concatenate into a
  program the provider did not send. Terminal `:fail`, no retry, no
  failover. Presentation noise still passes silently. Covered the whole
  class (reasoning deltas, non-string fields, provider-error
  documents). Red 29 failures → green 98/463; proved through canned SSE
  on the production JDK body handler, no paid call. No loop change was
  needed — the existing error rail already records it.
- SUITE LIVENESS AND ISOLATION (ruling #45 part 2) — `6829c2db3`,
  `f2b2e26b0`. Per-run operator-root isolation with copy-on-write
  first-party roots, so lanes test concurrently; per-test progress and
  a loud 300 s silence backstop. THE ACTUAL HANG WAS NOT A CHILD JVM:
  two captured JVMs had no descendants and were parked at `<!!` inside
  `seon.cluster.agent/disarm!` during test cleanup — filed as its own
  blocker (`abbd6c4c2`).
- FLAKY TESTS — `flow/pause` is asynchronous from dependency source;
  the race reproduced 53/100 deterministically with NO machine load and
  is fixed with ordered `ping-proc` acknowledgement (100/100 quiet and
  under bounded load). Clocks replaced with observed events across
  Flow, agent, store, SCI, instrumentation, schema, source, and turn
  tests.
- ANALYSIS GATE — `9e635c7dc`. The phantom blocking finding was our
  PARALLEL clj-kondo pass cross-wiring an outer call's arity with an
  inner function's identity. Sequential analysis fixes it with no
  linter weakened: 152 files, 0 errors.
- `.cljc` HONESTY — 14 false portability claims converted to `.clj`,
  7 genuinely portable files left, CLJS lint clean on all 7.
- STORE ANATOMY — see ADDENDUM 18. Both quoted figures disproven, the
  replacement model validated to 0.05%.

FULL SUITE AT THIS CHECKPOINT: **840 tests / 4,164 assertions / 0
failures / 0 errors** (previous known green: 823/4,062). Caveat stated
honestly: this ran while five lanes were editing the tree, so it is a
green at a moment, not a frozen-tree green — judge a release green only
on a quiet tree. The run also demonstrates the liveness work: per-test
BEGIN/END progress lines, and the isolated operator root removed on
success.

OPEN, IN PRIORITY ORDER, none blocked by another:
1. The custody residue on
   `agent-evals-reach-every-cluster-and-the-runtime-roots.md`:
   `release-store!` is public and the store is process-root-wide; the
   four custody-returning public functions each need a decision; the
   process-root registry should move to a never-installed operator
   namespace so a future `defn-` → `defn` slip cannot reopen the hole.
2. `seon.cluster.agent/disarm!` unbounded wait (new blocker).
3. The 4,096-character blob threshold, lowered on the disproven
   reading and now needing a decision against the real model.
4. Ruling #43 phases 3-5: ctx-derived custody, then the `seon.db`
   namespace (ruling #41), then the call-site sweep (34 namespaces, 16
   direct writes, ~58 test files).
5. MCP implementation, now ratified by ruling #44 (three tools,
   blob-backed results, ping window as a config fact).
6. The remaining audit blockers: Flow submission can block before its
   time limit; agent renderers cannot enter the guarded SCI context.

**ADDENDUM 18 — 2026-08-02 night, THE STORE NUMBERS WERE WRONG AND ARE
NOW MODELLED.** `research/store-amplification-anatomy-2026-08-02.md`
disproves both figures this program has been quoting. "86x inline
payload amplification" was a misreading: growth is LINEAR in payload
size and QUADRATIC in sequential commit count while roots stay shallow
(`4 x (N(N+1)/2 + N)` over 40 retained snapshots). The model is
VALIDATED, not asserted — a held-out 16 KiB prediction of 56,648,465 B
against 56,618,147 B measured, 0.05% error. "~42 MB per eval sample"
summed overlapping shared-store intervals; the reconstruction is
9.793 MB/sample, 1.939 GB for the 198-sample run. Composition: history
47.25%, commit-record envelope 969 B/transaction, blob content EXACTLY
0 B — the blob tier is not exercised at eval scale at all.
LANDED: ordinary stores now set `:keep-history? true` explicitly
(`db4efb4fd`); the unused outer konserve LRU is removed from our
Datahike fork (`0e8601d7`, pinned by `ccde63a4c`). Fused roots and the
diff buffer were verified already landed (14 to 2 forced blobs,
73.967 to 18.113 ms). NO DISK SAVING IS CLAIMED from either landed
change — the lane refused to claim what it did not measure, and an
identical 198-sample run still costs ~1.939 GB.
NOT LANDED, each with a named blocker: history-off would cut the run to
5.166 MB/sample but boot-critical `d/history` and inherited branch
representation still block a safe toggle; cutoff GC reclaimed 95.3% in
an isolated cell but has no commit-ID/database-value retention
contract; the writer wait improved burst throughput 652 to 890 tx/s but
raised serial latency and has no valid configuration-acquisition seam.
OPEN CONSEQUENCE: the 4,096-character blob threshold was lowered on the
disproven reading and now needs deciding against the real model.

**ADDENDUM 17 — 2026-08-02 evening, THE CUSTODY/ISOLATION RESEARCH IS
COMPLETE AND FUSED.** Three research reports landed and agree:
`custody-isolation-design-2026-08-02.md` (§1-8 no-fork baseline, §9
fork options), `sci-var-semantics-2026-08-02.md`, and
`definition-seam-design-2026-08-02.md`. Orchestrator independently
re-probed every load-bearing claim.

SETTLED FACTS (probed, not argued): a per-cluster SCI var CANNOT feed
compiled `seon.db` functions (`copy-var*` copies dereferenced values),
so custody derives from the ctx and binds the compiled var — the
owner's "option C" injection is refuted. `sci/fork` isolates a NEW name
but a REDEFINITION leaks to the parent (`identical?` Var objects;
`eval-def` does `bindRoot` on the previous Var), AND our own
`install-function-contract!` calls `sci.vars/bindRoot` directly
(`src/seon/sci/eval.clj:790`), so contract installation inside a forked
candidate context would corrupt the real cluster. Compiled first-party
Vars are already exempt from that mutation (`utils/var?` tests
`sci.lang.Var`, so a `clojure.lang.Var` takes the new-Var branch) —
hot reload is safe under any copy-on-write scheme placed after that
branch. An agent can substitute arbitrary code into ANY other cluster
using `swap!` on the reachable env atom; no custody design touches it,
only reachability does. The compiled runtime canNOT be redefined
(`alter-var-root`/`with-redefs`/`var-set`/`intern` all throw), with one
exception: `alter-meta!` succeeds process-globally and instrumentation
reads var metadata.

MEASURED (orchestrator, live `default`, 2026-08-02): building a
candidate ctx via `cluster-ctx` = **636 ms**; `sci/fork` of the live
ctx = **0.705 µs**. Six orders of magnitude. This is the number the
candidate-context case rests on and it was previously unmeasured.

THE ORDER (each step independently valuable, earlier steps never
blocked by later ones):
1. `ns-publics` at the install seam — 708 private host Vars across 42
   namespaces stop being published. `:seon.fn/private?` is already a
   computed fact every other agent-facing projection filters on, so
   this makes the install seam agree with the graph rather than adding
   a restriction. Ruling-#20 argument recorded with it.
2. The four public custody-returning functions (`open-branch!`,
   `open-store!`, `build-base-ctx`, `cluster-ctx` — DERIVED by querying
   `:seon.fn.arity/output-refs`, so it stays a standing check) plus
   relocating `running-instances`/root-store-holder to a
   never-installed operator namespace. `release-store!` is public and
   the store is process-root-wide, so step 1 alone leaves a
   cross-cluster damage vector.
3. Ctx-derived custody: attach custody at `cluster-ctx` build, bind the
   compiled `seon.db/*conn*` from it in `evaluate`, delete
   `:seon.store/branch-connection` from the request schema. Strictly
   simpler than today — one fact instead of two that must agree.
4. The `seon.db` surface itself (ruling #41).
5. OWNER DECISION PENDING — the SCI fork changes: generation-stamped
   copy-on-write (one `assoc` in `fork`, one `cond` clause in
   `eval-def`, same check in `bindRoot`; reuses the read-only commit's
   own pattern) which makes candidate contexts free and unblocks
   test-before-install accretion; and `EnvBox` (a non-`IAtom` env,
   ~87 call sites across 15 files) which closes the `swap!` escalation
   but is defence in depth only — reachability remains required either
   way.

The stability suite pins thirteen properties, led by custody totality
and non-inheritance, cross-cluster write isolation, reachability
closure, foreign-context integrity, and the two guarantees that
currently hold by accident and must not be lost: no compiled-Var
mutation, and no concurrency primitives.

LEDGER DEFECT found: ruling numbers #20 and #30 each appear twice in
`plan/README.md`.

**ADDENDUM 16 — 2026-08-02 late afternoon, RULING #41 + THREE LANES
LANDED.** Ruling #41 sealed and twice amended (README): seon.db is the
one database namespace for all things Datahike — dual
positional/argument-map interfaces (Datahike's own keys), db/conn
elidable to the calling agent's cluster's current database, everything
first-party migrates, the word "facade" banned (vocab row added).
LANDED, orchestrator-verified: the ambient-custody blocker
(`643719904`, evaluate binds `seon.db/*conn*` per evaluation, archived)
and the MCP probe-surface fix (`816cbac0f`: root/degraded/namespace/
door modes, 20/144/0 re-run — MCP clients need a fresh session to load
the new schemas). The FULL-TREE AUDIT (`fc86cd1f5`,
`research/full-codebase-audit-2026-08-02.md`) filed 19 issues; 3 new
blockers: SSE malformed-chunk splice can alter agent code
(orchestrator re-confirmed at ai.cljc:429), Flow `submit!!` blocks
before its time limit, agent renderers cannot enter the SCI ctx.
FULL GATE at this state: 827/4,083 with 10F/2E, ALL inside the
schema-edn-consolidation lane's unfinished in-flight files (its 29
uncommitted modifications still sit in the tree; resuming it is the
gate's unblock). QUEUED on owner confirmation: the seon.db wave
(entity-return-shape decision pending), then the call-site sweep after
consolidation lands.

**ADDENDUM 15 — SESSION CLOSE (2026-08-02 afternoon). START A NEW
SESSION FROM HERE.**

TREE: publication REPAIRED and verified by the orchestrator after the
schema merge (`d99a0ec7f`; `bin/seon init` republishes — addendum 13's
red is CLOSED). Everything is committed and pushed. ONE LANE STILL
RUNNING at close: `schema-edn-consolidation` finishing its full-suite
acceptance — resume/collect it with `bin/codex-agent summary
schema-edn-consolidation`. The FULL GATE HAS NOT RUN since the merge +
PRD refactor: run `bin/test` FIRST in the new session (last known
green 823/4,062/0 before the merge) and treat any red as this wave's
newest seams.

THE ORCHESTRATOR OWES (do these before new work):
1. The 4-point consolidation verification: the rg proof PASSED (no
   `seon/schema/` path refs in live code); still owed are the loader
   shape read, a fresh `--root` READY boot, and the duplicate-refusal
   falsifier across sections.
2. Issue notes for the turn/evaluate PRD's §9 behavior questions (the
   `Thread/sleep` on an `:io` proc; the per-form full `:seon.ns` pull;
   the silent `schema/build-projection` fallback) — then that PRD's
   issue closes.
3. Notes for the two measured follow-ups below.

MEASURED FOLLOW-UPS (new, real, unfiled):
- ~42 MB store growth PER EVAL SAMPLE (2.04 GB per 198-sample run,
  history-on). R40's history-off is NOT a creation-seam toggle: live
  `d/history` calls block non-temporal boots. Eval-scale economics
  need a design answer before large matrices.
- Source load is 11.8s (July's 2.23s is a fossil; profile:
  konserve.tiered 1154ms, datahike.connector 851ms, superv.async
  588ms — vendored deps dominate, first-party `seon.cluster` 259ms).
  `9bb559df9` began the dev dependency-closure cache; the
  `load-time-incident` lane stopped mid-work — resume it.

PENDING THE OWNER: his bootstrap revision (the gpqa run is the sharp
input — 196/198 episodes tried to WORK a QA question instead of
answering it; the episode shape does not fit raw-QA tasks); firing the
full O1-O5 matrix (the A/B loop is proven: edit `resources/seon/
bootstrap.edn` or transact a plan edit, digests key the grades); the
PRD-wave review; commissioning the AGENT WRITE SURFACE design (still
the largest named hole).

QUEUE (no lanes may launch until the owner says so): ruling-#38
assigned-namespace slice (both edit sites are now small named
functions); the load-time cache; addendum-8's residue list; the
inspect slice-2 (`humaneval`) once the owner reviews slice 1.

**ADDENDUM 14 — THE PROVIDER-SEAM THESIS IS PROVED (gpqa 198/198).**
Inspect's choice() scored every sample through Seon episodes verbatim
(zero sample errors, labels 198/198, log SHA 38cc3c98…). AGENT
accuracy 0.5% — 196/198 stopped on malformed Clojure replies: the
episode shape does not yet fit raw-QA tasks (the bootstrap teaches
objective-work, not answer-by-completion protocol) — the FIRST
calibration datum the experiment loop exists for, not a seam failure.
Terminal-honesty gated correctly (2/198). MEASURED SCALE COST:
~42 MB median store growth PER SAMPLE (2.04 GB for the run,
history-on); R40 history-off is NOT a creation-seam toggle — live
d/history calls block non-temporal boots — recorded as the measured
follow-up. inspect-slice-1 RETIRES complete.

**ADDENDUM 13 — KNOWN RED AT HEAD (2026-08-02 afternoon): the schema
merge (4b1740cec) regressed the Datahike schema INSTALL on fresh
branches/fixtures** — declarations parse (one form, 652 keys verified)
but `bin/seon init` fails transacting provenance seeds
(:seon.db.process/id "not defined in current schema"; bootstrap-plan
same class in fixtures). PUBLICATION IS BROKEN until the consolidation
lane's fix lands (resumed with assembled evidence; load-time lane
yielded its blocked slot). Found by the orchestrator's independent
verification BEFORE acceptance — the check working as designed.

**ADDENDUM 12 — THE turn/evaluate REFACTOR IS COMPLETE.** Both PRD
lanes retired with full acceptance: loop-split S1-S6 (`f978179a8`…
`113e7e465` — `turn` is four situation arms; characterization tests
mutation-proven first; live receipt datoms byte-equal pre/post,
`:equal? true`; FULL SUITE 823/4,062/0) and eval-split S7-S9
(`85af34005`…`bfc8f520f` — exactly-once `eval-form!` falsifier proven
red under double-eval; live before/after receipts identical, nine
attributes + same result-edn). The PRD's issue closes when its §9
behavior questions get their notes. UNBLOCKED FOR NEXT SESSION (no new
lanes by owner order): the ruling-#38 assigned-namespace slice (both
its edit sites now small named functions). Still in flight:
inspect-slice-1 (gpqa), bootstrap-facts (final cut), consolidation
(merge), load-time (profile commits landing: `b963caa8f`,
`79367fcaa`).

**ADDENDUM 11 — 2026-08-02 midday, FULL HANDOFF (orchestrator near
token limit; next session resumes from THIS block + `bin/codex-agent
status` + git log).**

STATE: gate was GREEN 804/4003/0 pre-PRD-wave; PRD refactor mid-flight
so the next full gate comes at its close. Rulings #36-#40 sealed today
(Inspect+tests-as-grading; provider seam+guardrails; assigned-ns eval;
TOOL-LESS — forms are the interface; keep-history per-cluster dial) +
the NO-COINED-WORDS style order (call things by code names). Jar
artifact LANDED: cold 20.7s (12.1 load + 7.4 install, measured 3x),
reopen 13.4s (56ms idempotence), digest `e7a6f69…`, embedded prepped
rows (option 1), fusion+diff-buf on fresh stores. KEY CORRECTION: the
"2.23s source load" is a JULY fossil — matched measurement TODAY:
11.8s dev vs 12.1s jar (identical; both compile source; tree grew
10x). That BREACHES the ten-second law → `load-time-incident` lane
dispatched (profile per-namespace; likely fix = AOT class cache with
newer-wins source preference; CAVEAT: AOT'd go blocks compile to IOC
on our core.async pin — vthreads property inert, reverted upstream).
AOT-for-jar downsides recorded in conversation: build-time execution
of top-level forms; binary coupling; the go/IOC pin issue (advance pin
= prerequisite); ship classes+source to keep hot reload.

IN FLIGHT (7 lanes, all resumable by name): (1) `prd-loop-split`
S1-S3 committed (`f978179a8` chars-tests mutation-proven,
`f75623175`, `d1acee726`, `9e41255fa`), S4-S6 finishing + live
same-receipt-datoms proof owed. (2) `prd-eval-split` falsifier
`85af34005` + S7 `ed7d69f05` + S8 `0c5a657e0` committed, S9
implemented-green awaiting its commit + live proof (I unblocked its
gate by contracting seon.artifact/-main `897fc86d1`). (3)
`inspect-slice-1` THIRD gpqa attempt RUNNING (provider+task+guardrails
landed `c6d79f817`; the two prior blocks were operator --root launch
sites, class-dissolved at `b98728d5a` start-child-jvm!); report owes
verdict/accuracy/store-growth. (4) `bootstrap-facts-conversion`:
resources/seon/bootstrap.edn shipped default + per-cluster plan facts
(ordinal+source rows, the run.form pattern) + seed-tx reads facts +
digest-on-read; CORRECTED: schema block into existing run.edn, NO new
schema file. (5) `schema-edn-consolidation` executing ruling #14 (36
files → ONE resource; the GLOB MACHINERY DIES — single io/resource
read; ancestor digest hashes one file; ORCHESTRATOR OWES the 4-point
independent verification at landing: rg no-directory-refs, loader
shape, fresh-root boot, duplicate-refusal across sections). (6)
`load-time-incident` above. (7) `r1-deletion-wave` COMPLETE except
final full suite (blocked on PRD churn — rerun at wave close); closed
3 blockers; bin/acme repointed; bin/css verified live.

PENDING THE OWNER: his SPECIFIC bootstrap critique ("pretty terrible"
— known weaknesses listed in conversation: dense help prose, form-6
line noise, send never exercised, no pull example, one refusal class
of five); firing the full O1-O5 matrix (pennies; do after
bootstrap-facts lands so digests key the A/B); the PRD-wave completion
review. QUEUED NEXT: ruling #38 assigned-ns slice (behind PRD lanes —
edits agent-namespace in eval.clj + reply-freeze in loop.cljc);
AGENT WRITE SURFACE design (the named biggest hole — commission it);
keep-history dial implementation; addendum-8 residue queue.
ORCHESTRATOR PROCESS NOTES for the successor: launch/resume codex
lanes BARE (no tail/filters — wrapper stdout IS the owner's panel;
relapsed twice, memory updated); lane heartbeat = commits not process;
weather rule 60-90s x5 in every spec; independent verification before
accepting big returns.

**ADDENDUM 10 — 2026-08-02 morning.** Rulings #38-#40 sealed (assigned-
namespace eval; tool-less — forms are the interface; keep-history as a
per-cluster dial) + the no-coined-words style order. Ruling #27 is now
COMPLETE (sci fork `6de1568` marks the 17 stock Vars read-only, merged
to main + pushed + pointer bumped; the cross-cluster issue archived).
The turn/evaluate PRD landed (`35b5ce327`) and its implementation is
RUNNING as two lanes (prd-loop-split S1-S6, prd-eval-split S7-S9).
Isolation posture (owner, this morning): namespace discipline IS the
isolation for now — "just having them work in their respective
namespaces will prevent them from stepping on each other's toes";
deeper isolation later. SEQUENCING: the ruling-#38 slice (derive the
eval namespace from `:seon.cluster.agent/namespace`) queues DIRECTLY
BEHIND the two PRD lanes — it edits `agent-namespace` in eval.clj and
the reply-freeze site in loop.cljc, both PRD-owned right now, and gets
cheaper after the split. Also queued behind its blocker: inspect-slice-1
(paused on the operator --root classpath fix, running) then the gpqa
falsifier; r1 finishing the fresh artifact build.

**ADDENDUM 9 — THE GATE IS GREEN: 804 tests / 4,003 assertions / 0
failures / 0 errors (frozen tree, 2026-08-02, commits `feb1c30d9`…
`40617bc43`).** The red's root causes, all real: `page-of` had DROPPED
the owner-ruled root fleet caller in the one-walk conversion (the
triage lane initially tried to DELETE oversight for this — reverted by
the orchestrator; the diagnosis proved the mechanism was regressed,
not superseded); the print floor produced malformed table Hiccup
(fixed at the owner); width-minus-one fixtures converted to
width-means-width; boot tests converted to event-driven bootstrap
completion; two real caller bugs (ensure-entity-call's closed-input
violation, the program-restart fixture bypassing ensure-entity!).
Rulings #36 (Inspect + tests-as-grading) and #37 (the provider seam
with anti-washing guardrails) sealed; the three eval designs landed
(inspect-ai-adaptation, benchmark-mapping — 53% of inspect-evals
runnable today, runtime-impacted-tests — agent fns are graph islands,
31 ms reverse closure); the owner review brief is
`plan/inspect-review-brief-2026-08-02.md`. NEXT: doc-residue landing →
Inspect slice 1 (gpqa_diamond) + graph-edges slice 1 + the bootstrap
matrix, all owner-gated on his walkthrough; the fix queue below
continues draining.

**ADDENDUM 8 — THE COMPLETE FIX QUEUE (owner: "queue ALL fixes, don't
drop anything").** The issues index is the authority; this is the
ordered projection at write time. IN FLIGHT: bootstrap-impl (pinning +
harness + smoke drive), no-follow-deletion-fix (BLOCKER — the July
symlink class back in store.clj/export.clj), doc-rot-fix (arch commits
pending), skill-verify-independent (fixing clojure-testing +datahike
residue), config-ledger-census-fix (blob-threshold + max-collection
ledger rows), interrogator replication (t7 variance + instruction
variants). QUEUED NEXT, in order: (1) reasoning + skill lanes' deferred
live proofs once bootstrap-impl quiets; (2) final index reconciliation
and commit of the rot-audit report; (3) THE FROZEN FULL GATE; (4) the rot
DELETION wave from the audit's chains — old writer artifact in live
build aliases, inspect adapters launching the deleted pod, dead
parser/oracle tools + their downstream readers, and the refused-chain
owner calls (bin/css/package.json, shadow-cljs/externs, extra-src.md +
:cljs alias, third-party example); (5) the quality sweep's 10 filed
frictions — render failures collapsing to absence/silence FIRST (the
absence-as-health class), the 20 ms fleet ping clock,
session-image stored-derived prose, duplicate namespace-context state,
turn/evaluate kernel splits, + the report's other five; (6) the malli
lane's LAST handoff never yet assigned: render/ns.clj role-ref
conversion; (7) elided-marker count/identity (print follow-up);
(8) blob get callback shape (984fb4a38 note); (9) stale AI retry lease
proof (18f029dd1); (10) negative-import-masks admission note; (11) the
two MCP audit repairs (reused-pid fence, frame provenance source-root
authority) + old-writer-port consumers (seon-server-call/acme/
inspect); (12) acquire! per-row containment (cold path) + the 17-var
sci residue; (13) terminal-refusal settlement fix; (14) seon.db
remaining slices (entity/datoms/27-file migration); (15) the coupled
interop EXPANSION (observation landed — the wave is unblocked);
(16) option C model registry (upstream LiteLLM JSON as facts) then the
max-tokens floor; (17) gen.loop-test residue; (18) konserve multi-key
branch review + Datahike 28-commit upstream delta; (19) the
pre-existing index blockers (visual-QA wave, eval-attribution owner
gate, armed-agent platform thread — re-verify against the executor
fix). Refill lanes from this list top-down as slots free; nothing
leaves the list without an archived note or a landed commit.

**ADDENDUM 7 — gate + audit verdicts, repair lanes dispatched.** Frozen
full gate: 753 tests / 3,547 assertions / 8 RED — six render.web async
timeouts (one suspected cause: the floor migration meeting the full web
stack), one reader-surface regression, one canonical-schema census
drift. Wave-2 adversarial audit (`7a2141a5f`): print path CONFIRMED
(88 rows + 10 promotions independently recounted); 1A partially
falsified (17-var residue still crosses clusters); 1C FALSIFIED twice —
nondeterministic sci built-ins replay as "pure" (determinism is a
missing leg of ruling #32's proof) and failed-eval defs get no session
delta (silently vanish on restart); MCP partially falsified (frame
provenance a subtler hand list; onExit PID-reuse window). Four issues
filed by the audit. REPAIR LANES RUNNING: `gate-red-triage`
(render/reader/census, owns render+print+failing tests) and
`session-repair-contracts` (determinism leg computed-not-hand-listed,
failed-eval delta at the terminal seam, then ruling #33's agent
contract enforcement at the one ctx-install seam). Next after both:
gate rerun → the parsed-contract implementation from the malli
research → interop expansion.

**ADDENDUM 6 — print path COMPLETE (3A+3B+3C), 1C at checkpoint.**
Lane 3 finished: sealed grammar + sinks + floor migration + options
(52/220 focused, P-TOTAL/P-TEE standing) and 3C promotion `050fff5c7`
— 10 rows promoted (A1 A9 B1 B4 B9 B10 E4 H1 H4 H6), cardinality
sentinel added (exact identities + family counts), predicted-vs-actual
honest (8 misses with reasons: A2 float-inf admission, B2/3/5/6 print
bindings reset per form, B7 struct unresolved, B11 sci fn-name face,
D11 print-table unresolved). Tally: 88 rows, 45 passing executable,
24 known divergences, 23 pending Lane 1. Parity gate 69/72/0. Lane 3
RETIRED. 1C at checkpoint (78b1e6eca value-first restore, c4002a83a
fresh-JVM proof, 319f6ccb fail-closed calls; focused suites green);
its two-agent live proof TIMED OUT on the stale reset-era JVM —
diagnosis stale-JVM trap, being falsified by the fresh
`session-proof-1c` lane (fresh session = working MCP binding, own
operator root = current code), which then continues into 1C′. Fork
merge to seantempesta/sci main + pointer bump executes when 1C′ lands.

**ADDENDUM 5 — post-restart.** MCP fixes CONFIRMED live (bounded
structured envelope with retained/total evidence; alive-first
discovery). Owner instruction for the sci-fork observer (already
committed as sci `47f6c8b` on the fork's `seon` branch): at landing,
MERGE to the fork's `main`, push `seantempesta/sci`, and update the
dependency — sci is consumed via `:local/root reference-code/sci`, so
the dependency update is the root submodule-pointer bump (put the
submodule on the merged `main`). Same treatment applies to future fork
work: no long-lived feature branches on our forks.

**ADDENDUM 4 — 2026-08-01 night, RESTART BOUNDARY.** 1A ACCEPTANCE
BANKED on the fresh substrate: agent A defined a contracted fn, agent B
called it next turn through the live ctx; turn-path `acquire!` count
ZERO (cold comparison 269.97 ms); crash-walk rows 1-4 passed under
`kill -9` — receipt interrupted, ctx rebuilt from facts, no form
replayed. Print 3B partial banked (`9acc78cd9` floor migration,
`cbd6bd5a3` table face); its remainder resumes by name. 1C BLOCKED at a
real boundary and AUTHORIZED FORWARD (orchestrator, consistent with
rulings #32 + the coupled-interop constraint): sci's public API has no
analysis-time host-interop observation, so the purity proof cannot see
`(do (.toUpperCase "x") (fn [] 1))` touch the host — the fix is the
lane's option 1, a NARROW evaluation-scoped host-interop observer in
our maintained sci fork (`reference-code/sci`), carried onto the eval
record beside `fn-entries` — this IS the coupled-wave interop fact,
built early because 1C needs it. BOTH LANES STOPPED at coherent
commits for the owner's app restart; resume each with
`bin/codex-agent resume hot-ctx-lane1 | print-path-lane3` (session
state persists by name). First moves for the restarted session: read
this block, resume Lane 1 with the option-1 authorization (sci-fork
observer → 1C → 1C′), resume Lane 3 (finish 3B), then the frozen full
gate on the quiet tree → 3C.

**ADDENDUM 3 — 2026-08-01 night, HANDOFF STATE (owner restarting the
app for the MCP server fixes).** SYSTEM RESET owner-ordered and done:
store wiped (old test data discarded deliberately), current-src
republished from HEAD (commit 6a6e4857, digest 230eef81), fresh
`default` on a new JVM serving HEAD — corpus verified 1,556 fn / 678
test / 200 ns rows, page 200. LANDED since addendum 2: 1A live
per-cluster ctx (`ac9de46b9` + fixtures `f4529e542`); print 3A sealed
grammar (`94220a629`) + semantic-value restore (`f01d6d3f6`); both
cross-lane seams (`91decd350` page-size fact, `1376a601d` print-var
capture); ALL FOUR MCP audit fixes (`5a83efc2e`, `727e436b9`), issues
archived, one new reader-chase finding filed
(old-writer-port-consumers-survive-outside-mcp). NEW BLOCKER FILED:
agent-authored contracts do not enforce in the live ctx (live-probed;
its own slice after 1C). Ruling #32 sealed + value-first clarification;
print Amendment 3 recorded. IN FLIGHT: hot-ctx-lane1 (1A live
acceptance on the fresh substrate → 1C forms → 1C′ values);
print-path-lane3 (3B floor/options). NEXT after both: frozen full gate
on the quiet tree → 3C parity promotion → live-ctx contract slice →
the coupled interop wave. A restarted session re-derives from this
block + `git log` + `bin/seon status`; lane summaries in
`tmp/orchestrator/`; resume lanes with `bin/codex-agent resume <name>`.

**ADDENDUM 2 — 2026-08-01 night.** Rulings #31 and #32 recorded (gate
untouched + contracts-as-safety; the SEALED restore rule +
forms-then-values order + coupled interop wave + atom protocol CLOSED).
The durable-env research returned decisive
(`research/durable-env-structural-sharing-2026-08-01.md`): no atom
protocol — Datahike's psset indexes already are the structural-sharing
substrate; hybrid restore proven 11.3 ms/50 defs. Lane `hot-ctx-lane1`
running: slice 1.0 LANDED (`88ebbde51`, reload-safe arms), 1A in
flight, corrected mid-run with the env-blind-to-redefinition hazard
(diff by dereferenced values per name, never env identity). Resume
slices 1C/1C′ queue behind 1A in the same lane. NEXT after 1A: the
lane's discovery-step surprises reviewed → 1C forms → 1C′ values →
Lane 3 print path (independent, dispatchable when a slot frees).

**ADDENDUM — 2026-08-01 late evening.** The in-flight wave LANDED (all
six caps-blob-print steps through `67190f050`; the lane summary
claiming step 6 uncommitted was stale). Full `bin/test` exited 0 on the
quiet tree (counts not captured — orchestrator truncated its own
output; next frozen checkpoint re-produces them). The standing
adversarial audit RAN (`b114ac29d`,
`research/adversarial-audit-2026-08-01.md`): two blockers (agent evals
commit arbitrary facts via `store/transact!` — reframed by ruling #30
as the persistence gate's job; the 88-row parity gate silently passes
if a row disappears), a real-store GC proof that history-only blobs
are DELETED, eight issues filed, and calibration naming caps/blob,
`seon.db` reads, reader policy, and the parity mechanism genuinely
sound. Pod-rot cleanup landed five cuts and FALSIFIED five "verified
dead" claims (live readers named in
`issues/unlogged-findings-2026-08-01.md` §6). The refactor wave plan is
authored (`plan/refactor-wave-2026-08-01.md`: three lanes, crash walk,
the 20/14 print-vs-eval divergence split, seven frictions vs the design
docs). Ruling #30 recorded (faithful session, gated persistence, the
two-phase bootstrap evals). RESUME SLICE ORDER DELIBERATELY UNSETTLED —
owner wants design agreement first; nothing dispatches on the
forms-only lean.

**WORKING EDGE — 2026-08-01: THE REPL SESSION IS THE CONTEXT, AND THE
FLOOR IS BEING MADE HONEST.** The day's frame: an agent lives in a real
Clojure REPL rendered from facts; the debug page and the context are one
thing; the floor must never silently lie. Rulings #24-#28 recorded in
README (REPL session; caps to measured knees + blob tier; print path
sealed; one program graph PER CLUSTER; stateless resume).

LANDED TODAY (all committed, branch pushed at checkpoints):
- REPL-native door: arity errors read like Clojure's own, bare
  `dir`/`doc` resolve, `doc` derives from `:seon.fn` facts (c6db32f56).
- Contract-violation messages bounded by the ONE general printer, no
  literal problem limit (d69708a2c) — the take-3 headline was an owner
  veto.
- `seon.db` slice 1: `q` + `pull`, dual arities, dynamic custody
  (5599d72b2) — the exam query returns 7 uncapped through the real door.
  Remaining slices (`pull-many`, eager bounded `entity`, `datoms`, the
  27-file migration) IN FLIGHT.
- MCP toolset fixes 1-5 (bounded messages, cause+first-party-frame
  error envelopes, alive-first `runtime_status` excluding the store dir,
  trim-in-place truncation, positioned multi-form refusals).
- Admission `inst?` hotspot: 4,436 → 380 bytes/node (eed7cf53f).

IN FLIGHT at write time: the caps-blob-print wave (steps 2-6: options
wiring, cap raise, `seon.blob` + receipt accretion with the konserve-GC
reachability union, derived `capped?`, print-floor wiring); the REPL
parity gate (writing the 59-row mined checklist as tests that assert
STOCK behavior, so our failings show as named divergences); `seon.db`
slices (`pull-many` landed 6b5acdcce; `entity`/`datoms`/migration
paused until the wave frees the shared schema/config paths).

THE THREE DESIGN LANES RETURNED — all measured, all with slice 1s ready:
- `plan/per-cluster-base-context-2026-08-01.md`: per-cluster `sci/init`
  costs 0.1 ms / 20 KB (20 in one JVM = 2 ms), so ruling #27's boundary
  is nearly free. Corrected two recorded claims: `sci/fork` is NOT the
  sharing mechanism (host Vars do not propagate — the live per-cluster
  ctx is), and the "489 ms substrate" was `acquire!` (283 ms, 74% of it
  rebuilding the schema projection), not `sci/init`. NOTHING in
  `acquire!` is genuinely per-turn state ⇒ ruling #29's payoff is
  283 ms leaving EVERY turn for ~215 ms once at boot. A 17-var writable
  residue still crosses independent inits (metadata fix in our sci fork).
- `plan/stateless-resume-design-2026-08-01.md`: proven end to end in a
  fresh JVM; the `:seon.code.def` fact family; forms-are-truth; order
  irrelevance via pre-interning; the blob decision derived, not tuned.
- `plan/print-path-design-2026-08-01.md`: SEALED (ruling #26) and
  amended by the parity mining.

THE RESEARCH CORPUS FROM TODAY (read these before touching their seams):
`research/sci-repl-realism-audit-2026-08-01.md` (21 divergences),
`research/admission-caps-and-blob-fallback-2026-08-01.md` (measured
knees; the 80× store amplification), `research/repl-parity-test-mining-
2026-08-01.md` (59-row checklist mined from sci/clojure/babashka test
suites + the one-line reader-tag fix), `research/grader-mechanics-
grounding-2026-08-01.md` (fact-space rewriting proven; four hazards),
`research/sci-session-persistence-2026-08-01.md` (ctx anatomy, measured
park vs replay), `research/mcp-toolset-audit-2026-08-01.md`.
Plans: `plan/print-path-design-2026-08-01.md` (SEALED contract),
`plan/grader-in-fact-space-2026-08-01.md`,
`plan/repl-session-context-2026-08-01.md` (the day's design + owner
directions), `plan/stateless-resume-design-2026-08-01.md` (in flight),
`plan/per-cluster-base-context-2026-08-01.md` (in flight).

BLOCKERS AND HAZARDS OPEN AT WRITE TIME:
- `acquire!` has no per-row containment — one poisoned agent row bricks
  every eval on its branch (issue filed; generation zero blocks on it,
  and it bites ordinary agents today).
- The program graph is process-wide, so it crosses clusters (ruling #27
  violation; issue filed; blocks parked hot ctxs).
- `seon.sci.eval` is not hot-reloadable (issue filed).
- konserve GC sweeps anything outside Datahike reachability — the blob
  wave carries the reachability-union fix.
- Contracted `defn` rebuilds the whole schema projection (21-30 ms per
  agent defn; issue filed).
- Pre-existing: the walk test exceeding admission caps (the cap raise
  may close it).

NEXT (ordered): finish the in-flight wave → integration gate (full
suite + live proofs + a `seon.problems` derivation on a fresh cluster)
→ the standing adversarial audit of the day's tree → the print-path +
parity-gate implementation wave → compaction (prepared, deliberately
unwritten) → the grader/generation-zero minimal list.

**SUPERSEDED — 2026-07-31 END OF DAY: THE ONE-SYSTEM CONVERGENCE IS
LIVE.** `default` serves at 7994 on the walk architecture: namespace
pages (`/ns/{namespace}`, reitit, reverse routing, 404-writes-nothing),
the two-pane debug (`/agent/{id}/debug` — exact AI bytes beside
everything-walked), context = one visible walk (fresh agent 1,288 tokens,
own entity line 4, capture quine dead, elisions one line, seon.flow
owner sees 48/48 sources), agents call any function (ruling #20 live —
a real agent eval called `seon.render/walk`), refusal continuation
(ruling #22) landed, boot repaired twice over (roster-authority
existence + candidate-generation activation before instrumentation).
Twenty-three ruling batches recorded (README "Ruling(s) 2026-07-31"
#1–#23); the sealed spec + budget/W4-html/cache plans + graduation eval
in plan/; ~30 dated research reports; all forks published to
seantempesta/* (Actions disabled), the repo pushed with tracking (the
push-at-checkpoints law added after finding 4,665 unpushed commits).
IN FLIGHT at write time: the DeepSeek exit drive (refusal-continuation
lane), graduation-eval-impl, konserve-multi-assoc. NEXT: the drive's
exit verdict → the post-wave adversarial audit → the queued waves
(:my/* rename, schema-EDN consolidation, fusion+GC store config,
interpreted-corpus substrate unit 3, cache/invalidation program,
context budget implementation, Datahike query-fix cherry-picks).

**HISTORICAL — 2026-07-31 morning block (the design day's opening
state), superseded by the end-of-day block above:** The owner ruled the one-system redesign in seven
recorded batches (README "Rulings 2026-07-31" #1–#7): blocks are the one
render unit in both projections with NO static scaffold path; walk =
discovery over schema'd data; per-function-call render caching; attribute
commit-id + conservative + code-revision staleness; dumb last-changed
ordering with branch tie-clustering; instructions as explicit mutate-in-
place datoms; the sci door as the only agent-code execution path with the
safety guarantee and time-limit smart defaults; accept-and-warn base-var
redefinition under the distributed ownership protocol (message the
namespace's owner agent; unowned namespaces get an agent on demand). The
authored contract is `plan/context-render-data-model-spec.md`, REVISED on
two adversarial falsification verdicts (walk/ordering/cache +
invalidation) — seven research reports and the retirement wave plan are
dated 2026-07-31 in `research/`. Landed today: W0 free cuts (slot
redirects, dead seed-tx), doc reconciliation (21 conflicts), the kondo
shared-cache contamination fix (`9a073b146` — init/populate/with-database
repaired), and the sci time-limit ground truth (Seon's arm defect, not
sci's; S2 per-run thread-scoped guard implemented, final proof in
flight). Next: seal the spec after the sci lane lands, dispatch W1 (data
model) + W2 (renderers) implementation lanes per the retirement report's
wave plan.

**LIVE OPERATOR CHECKPOINT — 2026-07-31.** Dormant cluster existence now
derives from the persisted Datahike branch roster; runtime advertisements,
registrations, and open connections are only the liveness overlay. The real
operator restart regression crosses two JVM identities and preserves its
populated fact counts. A subsequent shared-root failure was not missing
published data: reforked `default` contained all 572 schema rows, including
`:seon.render.walk/units`, while the older anchor JVM had candidate=true and
active=false for that key. `seon.instrument/apply!` now activates a changed
loaded candidate generation before Malli collection and skips the rebuild
when already converged (`e80f9e92d`, `8544b5cc0`). Focused instrumentation
proof passed 10 tests / 40 assertions. Cold `default` then reached READY at
port 7994; status reported 1/1 with no orphan JVM, and `/`, `/ns/seon.flow`,
and `/agent/root/debug` returned HTTP 200 with the two debug projections.

**HISTORICAL WORKING EDGE — 2026-07-30, `current-src` publication. This
block supersedes every dated block below it.** Repository indexing is static: the pinned
clj-kondo analyzes the complete dependency classpath as resolution context,
but only first-party Clojure beneath `src/` and `test/` becomes namespace,
function, and test rows. Global schemas come from the registered schema EDN.
Repository source is never evaluated. A fresh projection compiled all 559
packaged schema forms; boot/current-source proof passed 23 tests / 109
assertions, and analyzer/runtime/restart proof passed 22 tests / 159
assertions, including a second agent calling a first agent's database-restored
function. A full gate advanced through the early cluster suites without a
failure, then was stopped in the long high-CPU message-test phase; it is not
claimed as complete and remains pending.

Runtime admission is per form inside the ordered REPL reduce. The exact source
plan freezes first; immediately before each execution, its candidate is
analyzed against the current committed namespace row and database program
graph. Each error-bearing form becomes a flat lint-refusal value carrying its
exact source and local findings, while independent clean forms remain
byte-exact and execute at their original ordinals. A computed `require` or
alias therefore changes both SCI and clj-kondo resolution for the next form.
Known cross-namespace functions and privacy come from the database program
graph, never an allowlist.
clj-kondo type-mismatch findings remain advisory because its local inference is
not a sound database admission proof; its syntax, resolution, privacy, and
arity errors remain blocking. Whole-build publication is still atomic:
malformed repository source cannot publish a partial base.

**Owner ruling: one published `current-src` branch and commit ID.** Delete the
digest-named ancestor collection and expose no source-synchronization
operation. The edit path incrementally analyzes changed files and advances the
one `current-src` branch. A removal, incompatible schema transition, missing
artifact, or uncertain projection falls back to a complete scratch build. The
maintained Datahike `force-branch!` publishes that completed scratch database
value onto `current-src` with `:expected-current-commit`; failed or stale builds
leave the prior head visible. New experiment clusters fork the published
commit (measured branch-off approximately 17 ms). Existing clusters remain
sovereign and are never updated from files; explicit `init NAME --force`
destroys and reforks one named cluster.

**Earliest unsettled contract:** restore agreement between admitted source,
published digest, and database rows before completing the fallback proof. The
ordinary edit path is now live:
an actual `apply_patch` ran clj-kondo, retained its advisory findings, and
advanced `current-src`. The proof exposed and removed four false restrictions
instead of working around them: process-root store identity now canonicalizes
the path; complete graph construction stays inside its instrumented sequence
and predicate contracts; complete refresh represents no changed paths as
`[]`; and branch retirement no longer refuses merely because a surviving
branch descends from the retired name. Datahike source and a live GC regression
prove that every remaining branch head independently retains its parent
commits.

The independent adversarial review found two blockers ahead of the remaining
proof. An unreported edit to X followed by a reported edit to Y can publish the
current whole-tree digest while retaining X's stale rows; complete publication
can then trust digest equality and preserve the lie. The report's separate
error-level-admission claim was stale: `git log -S` and the current regression
show commit `995ccec92` already made complete and file-artifact indexing reject
every error-level clj-kondo finding. The remaining durable reproduction and
additional ranked friction are in
`research/current-src-adversarial-review-2026-07-30.md`.

The live operator was then destroyed, `current-src` republished, and `default`
reforked with `init default --force`. The fresh cluster contains 1,367 function
rows, 559 global schema rows, 622 test rows, and zero messages; one JVM is live
and no orphan JVM remains. Focused boot/registry/schema/function proof passed
43 tests / 235 assertions. This proves the reset and repaired operational
seams; it does not seal source/database agreement until the two blockers above
are fixed. The isolated fresh-operator suite still has one
unrelated readiness-test defect: its child eventually publishes readiness but
crosses the test's hard 30-second socket timeout and survives cleanup. That
exact child was terminated; the existing readiness issue remains open and no
green claim is made for that suite. The remaining decisive edit-hook proof is:
a deletion selects complete fallback, and failed analysis preserves the prior
published commit. Existing-cluster sovereignty and exact refork from the
published source commit are now observed live.

Two measurements bound the work on this machine: rich first-party analysis of
123 files / 2,061 rows took 3.28 seconds initially and 1.76–1.99 seconds warm
inside one JVM; a separate JVM made the operation 16.58 seconds and is
therefore forbidden on the edit path. Warm individual source analysis measured
5–32 ms. The integrated file-store proof measured 9,337.9 ms for the complete
operation and 609.3 ms for warm one-file analysis plus database publication,
scratch retirement, and atomic artifact replacement. These are development
measurements, not service-level guarantees.

Everything below this paragraph is chronological evidence and may contain
superseded gates, lane state, and diagnoses. It is not current scheduling
authority.

**HISTORICAL WORKING EDGE — 2026-07-29, the owner-present design + restoration
day.** README rulings 7–24
(the 2026-07-29 midday/afternoon/evening batches) are the charter; read
them before designing anything. `docs/seon/issues/index.md` is THE
SCHEDULE (every open note carries a running lane, a named future wave,
or died with evidence). Verify any claim here with one live command
before acting on it.

## Landed and gated today

- **ROLLING BASELINE + CLUSTER PRIMING** (`41b9ba6a9`, `9ca8b0652`) —
  `bin/seon index` with no cluster refreshes the content-addressed ancestor
  baseline and explicitly does not select `default`; `index CLUSTER`
  exact-reconciles source-owned program rows through `seon.fn/index!`,
  preserves messages/runs/agents/agent-authored declarations, advances the
  digest with its synchronization meaning, and writes no transaction when
  converged. `reset [CLUSTER]` retains the process-store fence while stopping
  the exact instance, then destroys and reforks only that branch through
  `registry/reset-cluster!`. Startup denies incoherence (including the
  measured namespace-without-function state), but a complete older corpus
  starts normally and receives no boot-time program transaction. Ancestor
  identity now includes `resources/` schema EDN. Focused evidence:
  `seon.fn-test` 3/17/0, `seon.cluster.boot-test` 22/107/0,
  `seon.dev.fresh-operator-test` 10/54/0. Full `bin/test`:
  566 tests, 2,445 assertions, 0 failures, 0 errors.
- **THE LIVING CODE GRAPH** (`0fc110286`, `f9e587ec0`) — reader→rows with
  string identities; SELECTIVE ADMISSION (contracted fns/schemas/tests
  only; scratch defs/expressions get receipts, never rows); one parser
  (the evaluator's second `sci/parse-string` deleted); commit-first
  terminal transactions (SCI installation derives from `:db-after`);
  parse-time namespace attribution (D8) with divergence queryable; lazy
  acquisition from facts, never receipts; indexing ONLY at ancestor
  population, never boot. **Graduation proven: agent defn → cluster
  restart → a second agent called it → 42.**
- **THE CONFIG AUTHORITY WAVE** (nine commits, `42887d234`…`06068eb67`)
  — one registration derives manifest-admissibility + database
  installation + defaults (three hand-maintained maps and their
  cross-check tests DELETED); one manifest compiler (defaults + sparse
  overlay + explicit env → validated effective map + digest → one
  desired-config row); omission inherits the shipped default, explicit
  `:seon.config/absent` is the only "off"; `bin/seon start [cluster]
  [--config <path>]` and `bin/seon config apply` through the ONE
  `seon.config/apply!`; cluster name optional everywhere (absent =
  default); apply → launcher → arm ordering with the locked-state
  repair drill; all 30 configured entries proven CONSUMED by the
  running system with per-entry update modes documented; multi-cluster
  no-bleed; all global schema EDN consolidated in
  `resources/seon/schema.edn`.
- **THE UNIVERSAL VALUE RENDERER** (`263de0563`, ported per ruling 14)
  — the old structural skeleton renderer (bounded depth/breadth,
  navigation-preserving, lazy-safe, opaque handles, controllable caps)
  is the FLOOR for any data; router precedence lands as ruled: value's
  own render keys → namespace override defns → schema-attached default
  → floor. Caps derive from database facts (`08a436d02`), live-apply
  proven without restart.
- **THE HTTP-KIT WRITE-STATE FORK** (submodule `238a85c` + `875353668`)
  — additive per-channel pending-byte state + atomic drain-or-close
  completion (`send!`/`tryWrite` semantics preserved, JUnit covered);
  our SSE writer PARKS on it. Stalled-tab growth bounded (audit
  measured 239,188 bytes against the 524,288 bound; before: 12 MB
  unbounded). **Upstream PR against http-kit #180/#474 pending owner
  go** — the fork retires if upstream merges.
- **Fixes**: compute-door (evals through the bounded launcher on
  virtual threads, both startup-wait defects dead); CRLF exact source
  spans; Integer→Long coercion at the ONE transact choke point; failed
  stop stays addressable; S8 goal-chain routing scope; schema-cycle
  refusal; the 9-issue small-correctness batch; the 5-issue contracts
  batch; the 5-item hygiene batch; instrumentation ordering fixed AT
  THE CHOKE POINT (`b69310347` — `seon.instrument/apply!` loads the
  registry itself; per-site ordering knowledge deleted).
- **SKILLS + RUBRIC UPDATED (owner priority)** — datahike,
  data-modeling, data-oriented-clojure, clojure-testing now teach the
  current system (resources/ schema EDN, dial derivation,
  presence-not-kinds, selective admission, the omission ruling); the
  review hook's rubric corrected and PROVEN corrected (both documented
  false-positive classes re-reviewed clean).
- **Lane tooling hardened** (`74a65d53d`) — verified stops, refusal to
  resume a live session, atomic summary freshness, name-collision
  refusal; the doubled-session state is unrepresentable.

## Research corpus produced today (read before re-deriving anything)

`code-graph-end-to-end` (the living graph, three generations, five
gaps), `config-aero-quarry` (the workflow was the loss, not the files),
`parser-merge` (NOT merged; 24 bug classes; the repair pass is the
front door), `old-context-assembly` (rendering was derived, MEMBERSHIP
was hand-built — the scaling wall), `transcript-aging-quarry` (aging
was always render-derived; no compaction job ever existed),
`query-invalidation` (read-tracing FALSIFIED — misses absence; Datahike
inherits cached results across bases at ~22 µs; the old E/A/V
registration located), `workload-scheduling-truth` (`:mixed` is not a
splitting scheduler; the CPU-permit-across-io measurement),
`submit-probe` (the dream needs the code graph; boring-for-now wins),
`render-pipeline-design` (serialize-once/mult 1.17 ms at 50 tabs;
block morph 1.2–1.5 ms; explicit pull REJECTED — +24.8% bytes and a
race; five-generation history table), `httpkit-write-path` (a real
upstream gap, not our misunderstanding), `seondb-facade-quarry` (seven
generations; entity must be eager-bounded; datoms range evidence 20
wakes/0 false vs 60/40; two-agent dedupe proven),
`agent-flow-render-falsification` (the third proc SURVIVES),
`test-problems-triage` (8 class-dissolving waves, ~84 tests),
`tree-audit` + `checkpoint-audit` (the standing adversarial sweeps),
`context-walk/s0-baseline` + `s1-shadow` (six verbatim side-by-sides —
**await owner read**).

## Resolved blocker — one registration contract

Six-generation git archaeology (`research/registration-archaeology-2026-07-29.md`)
confirmed the owner's report that function, schema, and test registration has
already been rebuilt repeatedly. The fresh tree currently restates the same
contract three times: `seon.fn/durable-row`, `seon.sci.eval/program-row`, and
`seon.cluster.run/program-row-tx`. Their drift is observed, not hypothetical:
runtime schema rows contain unevaluated syntax, tests commit but are neither
installed nor acquired, `ns-unmap` deletes only functions, and one cluster's
schema projection replaces process-global registry state.

The ordered repair is:

1. one pure owner for declaration identity, canonical row shape, owned
   attributes, exact replacement, and typed deletion;
2. explicit producer admission around it — build indexes every function,
   runtime publishes only fully contracted functions;
3. runtime schema evaluation in an isolated registration delta, followed by
   the same canonicalizer and terminal commit;
4. function/schema/test materialization only from the terminal transaction
   report's `db-after`, with schema projection scoped to the cluster; and
5. the recurring matrix covering parity, redefinition, deletion, refusal,
   reopen, and two incompatible clusters in one JVM.

The first implementation slice landed in `52423e362`, `4c37aac33`, and
`80a4f0fdc`: `seon.program` is now the one pure owner of declaration identities,
owned attributes, canonical rows, deterministic exact replacement, explicit
build/runtime function policy, and typed function-plus-test deletion. Focused
gates are green; the independent recurring tests are in flight. The inherited
Claude-started `indexer-adversarial-review` remains read-only on source/test and
will be collected under the revised delegation law. Root
`AGENTS.md` now says Codex orchestrators use native collaboration tools while
Claude orchestrators use `bin/codex-agent` (`6e5b0a925`).

**Registration contract landed through schema lifecycle (2026-07-30).** The
shared row owner, independent per-file index census, runtime isolated schema
delta, commit-first materialization, exact test lifecycle, and cluster-scoped
projection are now joined by exact namespace state (`1135d8f39`): actual
requires, local→target aliases, and local→qualified-target refers persist as
separate facts and install through maintained SCI APIs (`2217449`, `98457e8`).
Renames, multiple aliases, `:as-alias`, plain require, refer-all, authored
dependency ordering, and alias-only cycles have recurring proofs.

Global schema change/removal now refuses current data across transitive schema
dependencies and derived entity attributes (`ef1cfa5c1`, `fcb50f7b4`), while
the maintained Datahike fork permits indexed attribute removal only after its
current AEVT is empty (`5cdbc88a`, `c0a74e12`). `schema/unregister!` is the SCI
surface for one global typed deletion (`913f8177c`): it stages only inside the
evaluation delta, refuses schema/function dependencies, commits the row and
Datahike schema change atomically, and rebuilds the run projection from
`db-after`. Historical proof `fe54f59ca` establishes that old datoms plus the
old `:seon.schema` row rebuild validation at the same `as-of` basis; Datahike's
schema map itself does not time-travel, and `:seon.db/no-history? true` is the
explicit old-value exception. An independent adversarial audit is the remaining
acceptance boundary before this blocker closes.

That audit (`8f17c0ec9`) found four blockers, so this contract is not yet
closed. Its shared-physical-attribute finding is repaired: schema lifecycle
transactions diff complete current and candidate global projections by
Datahike `:db/ident`, rather than treating one changed composite form as the
owner of every leaf it references. The recurring replacement-then-removal
test proves surviving global leaf rows retain installed attributes and accept
new data. The other three findings are implemented pending integrated
adversarial review. Sequential execution freezes unresolved exact source spans
and reads each form after its predecessor settles. Qualified `ns-unmap` with
computed arguments evaluates in an isolated SCI fork, derives typed deletion
from the actual intern delta, and mutates the run context only after the
terminal commit; fresh acquisition and a real process restart prove no
resurrection. The census repair makes declaration occurrences a reader signal
independent of durable identities; the build
refuses an unplaceable occurrence for every declaration family, and an
independent tools.reader census compares per-file function/schema/test
multiplicity plus exact function/test identities. Its focused gate is 24
tests / 212 assertions / 0 failures / 0 errors.

The sequential/deletion focus is 78 tests / 506 assertions / 0 failures / 0
errors, with the maintained SCI namespace suite at 38 tests / 153 assertions /
0 failures / 0 errors.

The second registration re-audit (`df346713a`) found one further runtime
namespace blocker: import-only `ns-unmap` changed SCI's isolated `:imports`
mask but produced neither an intern deletion nor a binding-row delta, so the
successful operation was discarded. The repair replaces Seon's partial
inference with maintained SCI `namespace-state` / `install-namespace-state!`
operations over SCI's complete namespace map. The evaluator still derives
typed function/test deletion from removed interns, but carries the exact
isolated namespace state through that deletion or the ordinary namespace
context request and materializes it only after terminal commit. Dynamic source
is not replayed. The recurring runtime pair proves success changes the next
form's supplied ctx and transaction refusal leaves that ctx unchanged; the SCI
owner suite is 39 tests / 159 assertions / 0 failures / 0 errors on Clojure
1.10.3 and the vendored `:clojure-1.11.0` alias's exact Clojure
1.11.0-alpha1 dependency. The other two re-audit blockers remain in their owning
lanes; integrated adversarial review remains the wave boundary.

The final audit (`b12924856`) found that the exact import mask above was still
only an in-process namespace snapshot: fresh acquisition reconstructed the
default `String` import because namespace facts represented requires, aliases,
and refers but not imports. Root `7713bb0bf` plus maintained SCI `1305a90`
extend the same exact binding representation with namespace-owned import
components. Each stores a local symbol and optional fully qualified class
symbol; an absent target is SCI's nil mask over a default import. SCI checks
the target against its installed class table at the install boundary, so no
Class object enters the database. Current-run commit/refusal, fresh
acquisition, explicit import addition, and a real cluster stop/reopen now recur
through the one binding-fact mechanism. Maintained SCI passes 40 tests / 160
assertions / 0 failures / 0 errors on both supported Clojure versions.

The final audit falsified that build admission: its finite direct-operation set
was another hand list, and `eval`/`apply` could mutate aliases or the current
namespace before a silently misattributed declaration. Build indexing now runs
the source inventory in one isolated JVM, reading and evaluating forms
sequentially with Clojure's actual namespace state and retaining exact
file/line source spans. One final snapshot of evaluated global schema forms,
namespace bindings, and Vars is the program state; there is no per-form delta
registry to reconcile. Computed schemas, indirect function/test definitions,
aliases, imports, refers, unmaps, unregisters, and `in-ns` attribution therefore
use the same final state that compilation produced. Process isolation contains
the inspector's process-local namespace/schema/test mutations; it does not
contain external file, subprocess, socket, or database effects, so this remains
trusted first-party source evaluation rather than a security boundary. A
content-keyed process-local cache makes repeated identical populations ordinary
map reads without risking stale results across JVMs. The child launches in the
existing `:test` dependency
environment because `test/` is one indexed root. Its exact input key derives
requested sources, schema resources, all repo-local files on the resolved
classpath (including vendored sources), repo-local dependency manifests, and
resolved external paths. A default `-M:dev` parent produced 116 namespace /
1,330 function / 552 schema / 608 test rows in 16,181 ms; an identical cached
call returned the same rows in 114 ms.

The post-fix audit then found that build evaluation had not entered a schema
registration delta: registration worked only by mutating the disposable child,
while a real `schema/unregister!` correctly refused the missing delta.
`aaac37105` now wraps the complete sequential population and its final snapshot
in the same delta mechanism runtime uses, with build retaining `:core` admission
and runtime retaining `:agent` admission. `5525f4f0d` completes exact
reconciliation expectations. The frozen-tree build falsifier retains a
computed schema and removes a register-then-unregister schema plus unmapped
function/test Vars. `seon.fn-test` passes 8 tests / 65 assertions / 0 failures /
0 errors. The final default-parent census is 116 namespace / 1,331 function /
552 schema / 608 test rows, including 867 private functions; all 953 serialized
contracts/forms EDN-read with zero object tags. Cold/cached calls measured
16,812.94 ms / 111.68 ms with identical rows.

The evaluated snapshot also revealed that Clojure Var metadata contains live
predicate roots, not their source symbols. The one schema owner now performs
the inverse of predicate binding before any contract becomes database EDN:
named roots become qualified symbols, raw predicates become explicit `[:fn
qualified-symbol]` schemas, already explicit `[:fn]` forms remain exact, and
anonymous roots refuse. The direct/indirect/third-namespace regression passes
in 21.46 s wall; the acquisition test that formerly failed on an unreadable
`#object` and then on bare `clojure.core/bytes?` passes in 24.64 s wall.

This is the prior platform's surviving design rather than a new scanner:
`87ac3f9c6` made analyzer state authoritative, `d33b29cf9` diffed evaluated
definitions and registry values, and `56ed96dd9` repaired computed cold-boot
schema parity. The discarded static scanner family remains `0c22f8363` /
`d7cd70bdd`. The narrow recurring counterexample covers `eval`/`apply` alias
mutation, computed schemas, evaluated `in-ns`, direct and evaluated function
registration, evaluated test registration, an existing third namespace that
returns to its caller, durable predicate canonicalization, and the explicit JVM
default-import nil mask. It passes with one child inspector; production
`src`+`test` census and cold/cached timing remain the integrated wave boundary.
The focused recurring suite now shares that immutable adversarial population
across its row-shape, binding, exact-REPL, and `index!` assertions while the
V1→V2→V3 source mutation test still launches a fresh inspector for every
changed file state. This preserves end-to-end cache-invalidation and deletion
proof but removes redundant process starts: `seon.fn-test` fell from the
observed 4m29 loop to 71 seconds, with 8 tests / 62 assertions / 0 failures /
0 errors (changed-test generation 1524).

The final complete-suite checkpoint found one last public-surface omission:
the isolated inspector's `seon.fn/-main` had no Malli contract. `a9974918d`
adds its exact three-string-to-nil contract; the focused public-contract plus
index gate passes 11 tests / 75 assertions / 0 failures / 0 errors. The frozen
full gate then passes 606 tests / 2,680 assertions / 0 failures / 0 errors,
including reset, schema reopen, registered restart, program restart, schema
history/no-history, runtime deletion, and exact build reconciliation. The
independent post-fix audit reports no remaining registration-lifecycle blocker.

## The checkpoint — both blockers cleared, attempt 6 running

Attempt 5 (Opus, `80b12d8f3`) judged the refusal seam **green under
every ruled scenario**: one refused terminal commit → exactly one
settled receipt + one durable error fact + closed run + the only wake
being the trigger commit itself; first-form and mid-plan refusals,
below-cap (next turn carries the refusal) and at-cap (episode ends
clean) all correct; `kill -9` recovery marks `interrupted-at`, nothing
re-executes, ready in 968 ms; one settlement path, three fenced
writers, no second mechanism. It found two blockers in front of the
claim — **both now fixed and landed**:

1. **`terminal-refusal-never-checks-its-own-settlement-commit`** —
   FIXED (`6ab646eb6`): settlement data bounded and validated before
   construction, the transaction's own outcome checked, refusal raises
   a named core fault instead of returning true, reboot recovery marks
   `interrupted-at`. Follow-on filed:
   `closed-agent-mailbox-turns-durable-fault-notice-into-core-fault`.
2. **`instrumented-assert-compilable-schema-refuses-every-agent-turn`**
   — FIXED (`6be7f1fb2`): a named bound-definition schema at the
   activation boundary (never `:any`), source-form validation binds
   predicate symbols before Malli compilation, and an instrumented
   `:panic` scratch cluster completes a real agent turn. This closed
   the class the green gate had never exercised.

Full gate **552 tests / 2,360 assertions / 0 failures**, tree clean.
**Attempt 6 (`1a804e342`) — STILL NOT GRADUATED.** Its recovery blocker is
now resolved under ruling 25: boot recovery marks the running receipt
interrupted, closes the run, removes custody and the agent pointer, and never
executes the unstarted suffix. The cold-resume blocker is superseded because
there is no recovery resume path. Everything else in attempt 6 passed:
refusal variants, episode-cap behavior, wake accounting, the
transaction-outcome fence, the three-writer census, gate 552/2360/0.

Attempt 6's other blocker is also resolved: malformed terminal-settlement
construction now translates an armed normalization contract violation into
`:seon.cluster.loop/terminal-refusal-settlement-refused`, the same named fault
the unarmed path raises. Both invalid construction and a refused settlement
close the affected mailbox and leave the running receipt for boot recovery.
The exact still-routed closed mailbox is derived as quarantine, so the durable
explanation message produces no second fault; closed render routes and
saturated live routes remain loud.

## Live lane state (2026-07-29 evening, end of the owner-present day)

Two Opus lanes died mid-implementation on upstream Anthropic API errors
(500, 529) — not their work's fault. Their reports COMMITTED
(`research/repl-workflows-2026-07-29.md`: cross-cluster workflows, the
observation harness, the cold-resume dissolution reasoning); their fixes
were left uncommitted and ENTANGLED in the tree, leaving the gate at
**557 tests / 8 failures**. A sol lane
(`finish-inherited-recovery-and-quarantine`) adopted the whole state
with orders to review it critically rather than adopt it. Ruling 25's
settle-and-close recovery landed as `811ec4356`. The inherited quarantine
classifier was narrowed during adoption: closedness alone is not benign; only
the agent owner's exact still-routed closed mailbox is a fence.

Also running: `skills-independent-verify` (the blast-radius law's
adversarial pass — trusts neither the orchestrator's authored skill nor
the authoring lane's corrections, executes what the skills teach, and
deletes what it cannot verify) and `skill-test-datahike-planner` (the
owner's skill evaluation: fix the vendored planner's
variable-symbol-dependent plan selection, alpha-renaming as the
falsifier, with a blunt per-skill critique as a first-class
deliverable).

Skills first pass LANDED (`e587c8b7a` + the authoring lane's sweep):
`seon-flow-architecture` authored with `references/`, the five pod-era
skills rewritten for the fresh JVM system, `clojurescript` retained as
explicitly historical quarry guidance with a narrowed trigger, one
stale `datahike` reference fixed. Corrections to the orchestrator's
draft worth remembering: the process-root executor pair serves ONLY the
work-launcher graph; evaluation tasks get their own virtual-thread
executor; current rendering is complete snapshots plus per-tab deltas
(packages/keyframes are TARGET); URLs come from cluster advertisements.
The topology measurement was wrong twice (~0.3 → 0.291 → 0.343 ms),
which is why the independent pass exists.

## THE CODE GRAPH IS REAL NOW (2026-07-29 night, `7340e2635`)

**121 fn rows → 1,242.** Namespaces with functions 21 → 105. Private helpers
0 → **808**. Per-file `defn`/`defn-` counts now EQUAL per-namespace row counts
with zero mismatches. Gate **568/2780/0**. Two silent defects were behind it:

1. **A hand-maintained allowlist killed namespace attribution.**
   `seon.sci.reader`'s `namespace-stable-operations` cleared parse-time
   attribution after ANY top-level form outside the list — permanently, until
   the next explicit `ns`/`in-ns`. So the FIRST ordinary call in a file erased
   every declaration below it: `(set! *warn-on-reflection* true)` cost
   `seon.flow` all 47 functions, `(schema.edn/load! {})` cost
   `cluster/agent.clj` 14, `register-core-predicate!` cost `cluster.clj`
   everything below line 65. Replaced by a property DERIVED from the form (a
   form mentioning `ns`/`in-ns` below its own head clears attribution); no
   list, no skip-set, no per-file case. This was `parser-merge`'s predicted S4
   conflict — the report foresaw the design problem but not that it was
   already destroying the corpus.
2. **A contract gated the graph itself.** `seon.fn/durable-row` required
   `:seon.fn/spec`, so private helpers were never rows even where attribution
   held — breaking `:seon.fn/calls` reachability through private helpers, which
   workload derivation and test selection depend on. Build-time now admits
   every declared function with `private?` and spec-presence as ordinary
   attributes. **The eval-time rule is UNCHANGED** — an agent-authored durable
   declaration still requires its contract (selective admission); a scratch
   defn still gets a receipt and no row.

The silence is fixed at the gate: a file whose declaration cannot be placed is
now REFUSED loudly with `:seon.fn/namespace-unproven` naming file, line,
source and reason. Recurring proofs added: the per-file coverage invariant
(counting `defn` names from the FORMS, not from the reader's own lifted facts,
so it cannot agree by sharing a bug) and the loud-refusal regression.

**A CAUTIONARY NOTE FOR WHOEVER READS THIS.** The orchestrator produced TWO
confident wrong diagnoses on this exact code in one evening — first measuring a
stale JVM (the reader fix was in the tree, the running JVM predated it), then
measuring the lane's uncommitted working tree and concluding "there was never
an attribution bug" when there was. `git log -S` settled it. Anything confident
about this area deserves verification; an adversarial review
(`indexer-adversarial-review`) ran against the whole unit for exactly that
reason, and its verdict is below.

### The adversarial review's verdict (`3317d95dc`) — read this before trusting the block above

**The inventory is right; two of the safety claims are false.** Report:
`research/indexer-review-2026-07-29.md`. Independently confirmed: 1,242 rows
across 105 namespaces, 808 complete private rows, gate 568/2,780/0, and the
build/eval boundary still distinct (eval-time admission still requires a
contract). Independently FALSIFIED, and **re-reproduced by the orchestrator on
HEAD before dispatching the fix**:

- **"No hand lists" is false.** The deleted allowlist was replaced by
  hand-maintained NAME MATCHING — `(name operator)` compared against literal
  strings, discarding qualification. So `(foo/defn ghost [] 1)` emits a
  PHANTOM function row, `(foo/ns audit.b)` a phantom namespace,
  `(foo/deftest ghost)` a phantom test, and `(other/in-ns 'audit.b)`
  attributes every following declaration to a namespace nothing moved to.
- **"Everything accounted for" is false, in both directions.** A real
  declaration inside a top-level `do` vanishes with no row AND no refusal,
  even though Clojure's compiler treats a top-level `do`'s children as
  top-level forms. And `namespace-changing-mention?` walks QUOTED data, so a
  file containing `'(in-ns 'x)` clears attribution and `seon.fn` then refuses
  the ENTIRE FILE — inert data making real source unindexable, the sharpest of
  the four.
- **The coverage invariant is not independent.** It shares the production
  reader's event stream and collapses declarations into SETS, so it agrees
  with both defects by construction. The claim in the paragraph above that it
  "cannot agree by sharing a bug" is wrong.

One root cause, one fix, dispatched as `reader-operator-identity`: every
recognizer resolves the operator's identity through the ns form's own
aliases/refers (`resolved-operation` already exists in that file and is
already used for `seon.schema/register!` — the other recognizers simply never
called it); the walk stops at inert `quote`; top-level `do` splices; and the
coverage proof takes its census from an independent reader with per-occurrence
multiplicity. Naming `clojure.core/defn` is not the banned pattern — the ban is
on lists of operations believed SAFE, which fail open; recognizing the one var
that defines functions fails closed.

**The lesson worth keeping past this fix:** the same evening produced a
hand-maintained allowlist, its replacement by hand-maintained name matching,
and a coverage test that could not see either. Every one of those passed a
gate. Reader-level recognition must resolve identity, and a coverage proof that
shares its subject's machinery is decoration.

**TWO NEW BLOCKERS, filed rather than papered over:**
- `eval-time-schema-and-test-rows-have-no-recurring-proof.md` — writing the
  test exposed why it is missing: `activate-program-schemas!` rebuilds the
  whole projection from one database's rows and calls a PROCESS-GLOBAL
  activation, so a fixture holding one agent-authored schema collapses the
  registry and kills four unrelated tests in the same JVM. That contradicts
  "clusters share no mutable state." The function half IS covered
  (publish/refuse/upsert/delete/attribution).
- `priming-indexes-with-the-live-jvms-loaded-code.md` — `bin/seon index` reads
  source FILES from disk but interprets them with the reader the target JVM
  loaded at boot, then records `:seon.ancestor/digest` from the disk files, so
  the recorded digest LIES about which code produced the corpus (this is what
  bit the orchestrator). Recommended fix: the JVM records the digest of the
  source its indexing namespaces actually loaded from and `index!` REFUSES on
  mismatch, naming both — readiness published rather than a reload the caller
  must remember. A `:reload`-inside-index one-liner is explicitly rejected
  (it re-runs schema activation against a running system), and per-cluster
  indexing cannot move to a subprocess because the live JVM holds the flock.

## THE OWNER'S CLUSTER IS PRIMED (2026-07-29 night, owner watching)

`bin/seon index default` ran against the live cluster on 7994. Before → after:
function rows **0 → 121**, namespace rows 69 → 149, test rows **0 → 492**;
messages 366 → 366, runs 229 → 229, agents [helper root] unchanged. **The live
cluster can query its own code for the first time**, and every fact it already
held survived — accretion, as ruling 28 required. A second run reported
`:converged? true, :operations 0`, so priming is idempotent and safe to repeat
(the destructive path stays the separately-named `bin/seon reset`).

It took three attempts, each informative rather than damaging, and all three
are now recorded as findings rather than folklore:
(1) `No such var: seon.cluster/index!` — the stale-JVM trap in person; the code
was correct in the tree and the JVM was from the morning.
(2) After reloading `seon.cluster`, a CONTRACT VIOLATION: the reloaded caller
passed `:seon.ancestor/digest` to a stale `seon.fn`'s closed schema. **Armed
instrumentation caught version skew that would otherwise have transacted
against an old contract.** Issue:
`partial-hot-reload-produces-mixed-code-with-no-warning.md`.
(3) Reloading both namespaces and re-applying `seon.instrument/apply!` worked —
**hot reload closed the gap with no bounce; the cluster never went down.**
Also filed: `cluster-reset-shadows-clojure-core-reset.md` (the new `reset!`
shadows `clojure.core/reset!` inside a namespace full of atoms).

Priming itself landed green at 566/2445/0 (`41b9ba6a9`, `9ca8b0652`,
`74d148c44`, `d12be8d59`): baseline refresh with no cluster named, per-cluster
priming when named, `bin/seon reset` per ruling 26, and startup denying
INCOHERENCE (ns rows without fn rows) while a complete older corpus still
starts normally.

## RECOVERED FROM A DATA-LOSS INCIDENT (2026-07-29 night) — read before resuming cluster-priming

A cluster-reset TEST FIXTURE followed repository symlinks out of its scratch
area and deleted **55 tracked paths**: all 45 files of `src/` plus 13 vendored
`reference-code/` submodule working trees, while a suite was running. The
symlinks it walked were the ones introduced hours earlier when the three skill
directories collapsed into one real directory plus two links (ruling 29) — a
change that killed one bug class handed a fixture a path out of its sandbox.

NOTHING WAS PERMANENTLY LOST. Recovery: `git checkout --` restricted to exactly
the 55 deleted paths (nothing was staged, so no in-flight work was overwritten),
then `git submodule update --init` for the 13 emptied submodules, then
`clojure -X:deps prep` to rebuild Datahike's prepared artifacts. The Datahike
FORK commit `19f5cdd9` survived in the submodule's own object store.

THE LANE BEHAVED CORRECTLY and that is why this cost ten minutes: it detected
its own damage, captured an exact inventory
(`tmp/accidental-deletions-20260729.txt`), refused to self-recover without
authorization, and reported the blocking boundary. Issue filed:
`a-test-fixture-deleted-tracked-files-through-symlinks.md`, with the law added
to AGENTS.md (recursive deletion never follows symlinks; the boundary derives
from the fixture's own root, never the process CWD; a symlinked sentinel must
survive the cleanup regression).

CLUSTER-PRIMING RESUMED AND LANDED. The detect-and-deny half survived before
the incident (`de8560cd9`); the fixture was repaired first (`80d38dbf0`), and
the missing baseline/prime/reset half then landed as `41b9ba6a9` +
`9ca8b0652`. The surviving test/operator edits were reviewed as intent rather
than preserved: their bare-index→default behavior was discarded because
ruling 28 makes bare index mean baseline, and their duplicate cleanup was
replaced by `seon.test-support/delete-recursively!`.

## Where the tree is (2026-07-29 night)

ORIENTATION CONSOLIDATED: `docs/TRANSFER_PROMPT.md` is now the one standing
orientation for anyone working on Seon — what it is, why archaeology precedes
design, which skills to load and why they can be trusted, the loop, the warts,
the mentality as standing rulings, and how the owner works. The plan handbook
was ABSORBED into it and deleted (one copy, not two — same reasoning as ruling
29), and it lives at top-level `docs/` deliberately because it outlives this
chunk. `AGENTS.md` and the plan README point at it.

SKILLS: one real directory (`.agents/skills`), the other two paths are
symlinks, and `bin/test` now REFUSES TO RUN if that is broken — the tooling
lane turned the ruling into a live gate. `browser-automation` deleted (built-in
browser instructions already cover it). All remaining skills independently
verified high-trust, with four unverifiable claims DELETED rather than hedged.

LANDED WHILE THE OWNER WATCHED: ruling 28's initial detect-and-deny half
(`de8560cd9`) plus its resumed rolling-baseline/prime/reset half
(`41b9ba6a9`, `9ca8b0652`) are real. The latter corrects the initial gate to
deny incoherence rather than age. The operator-reconciliation lane archived
the two operator failures it fixed (`110080420`). `bin/codex-agent status`
now reports named lanes with elapsed times instead of raw pids.

CLUSTER-PRIMING IS COMPLETE. Operator reconciliation is the landed
`26a5ef07f` derivation that every new index/reset command uses; no pre-landing
parallel truth path survived.

GRADUATION ATTEMPT 7 still waits for a quiet tree. Both attempt-6 blockers are
fixed; auditing a moving tree measures other lanes' half-finished work, which
is what wasted attempts 2 and 3.

## Superseded: where the tree was (late evening)

RULING 25 IS BUILT (`811ec4356`): interrupted runs close atomically,
custody and pointer clear, NO plan suffix resumes, unanswered messages
still start new episodes, and interruption evidence reaches the agent's
context. The cold-resume path is DELETED and both its issues archived.
Live `kill -9` proof: one receipt interrupted, run closed and unlinked,
no suffix receipt, no capability message. QUARANTINE RECOGNITION landed
too (`bd6e09402`) — and the adopting lane REJECTED the dead Opus lane's
"all closed channels are fenced" shape because it would have hidden
broken render delivery; only the exact still-routed closed mailbox is
benign, saturated mailboxes and closed render routes stay loud. Gate at
that commit: 554/2378/0.

SKILLS COLLAPSED TO ONE REAL DIRECTORY (ruling 29): `.agents/skills` is
real; `.claude/skills` and `seon-skills` are links. Drift is now
unrepresentable rather than detected — `script/seon/dev/skills.clj` and
the old operator's adapter check are obsolete and being deleted.
`browser-automation` is deleted everywhere (built-in browser
instructions already cover it). All remaining skills rated high-trust by
the independent verification pass, which DELETED four unverifiable
claims rather than hedging them (sub-millisecond memory reads, a
fabricated 494-assertion count, a no-history-overhead claim, a citation
to a document that does not exist) and corrected the scheduling probe
numbers the orchestrator had wrong.

THE TREE IS RED AGAIN and that is expected weather: three lanes are
mid-surgery (cluster-priming in `src/seon/fn.clj` + `schema.cljc`,
operator-reconciliation in `fresh_operator.clj`, tool-sharpening in
`bin/test` + deleting the obsolete skills generator). Each owes a green
gate at its own exit. GRADUATION ATTEMPT 7 WAITS FOR A QUIET TREE —
both attempt-6 blockers are fixed, and the lesson of attempts 2 and 3
is that auditing a moving tree measures other lanes' half-finished work.

## Awaiting the owner only

- **Read**: `render-pipeline-design-2026-07-29.md` (the composite
  package/keyframe design), `context-walk/s1-shadow/` (six verbatim
  outputs — the S2 gate), `plan/seondb-facade-contract-spec.md` (my
  authored spec; one embedded decision: reads return the BARE result,
  evidence flows only through the bound pass — override if wrong).
- **Go/no-go**: the upstream http-kit PR (a public action on the
  owner's identity).
- **Conversation**: `seon.ai/generate-code` design (parked at owner's
  request), and the render-proc §7 decisions (three already ruled in
  ruling 22; memory-bound ruled at-most-once).
- **Housekeeping**: the owner's live default JVM (pid 61316, port 7994)
  predates every landing today — a bounce is NON-DESTRUCTIVE (reopen
  accretes today's schema rows; helper's transcript and all facts
  survive) and brings it onto current code with instrumentation armed
  at boot.

## Next, in order

1. acquisition-fix commit → **re-audit attempt 6** → checkpoint closes.
2. The render/context implementation wave — contracts authored
   test-forward from `agent-flow-render-falsification` + the owner's
   §7 rulings; the third proc lands BEFORE the read seam.
3. The `seon.db` facade — port Generation G per the contract spec after
   owner review; then the 27-file migration in owner-lane groups.
4. Context-walk S2 (the live guinea-pig) — unfenced now that the code
   graph exists; needs the owner's S1 read first.
5. The parser merge wave (S1–S6 + the repair front door) and the
   test-dissolution waves (8 classes, ~84 tests) — both design-gated.
6. UI restoration stays TABLED until the context rendering system is
   proven (owner ruling 12).

**DELEGATION PRECONDITIONS P2 + P3 LANDED (2026-07-29 early).** Agent
namespace assignment is the unique-value
`:seon.cluster.agent/namespace` ref to `:seon.ns`; formal creation
commits the namespace and agent together, `seon.cluster.agent/owner-of`
is the pure namespace-symbol → agent-id query, ordinary cardinality-one
transact reassigns it, and `seon.problems` derives sourced namespaces
with no owner. Focused proof: 28 tests / 109 assertions / zero failures.
The opt-in `bin/test` sink refuses the default cluster and commits one run plus
per-test results referring to stable test rows. A live deliberately failing
run reopened from an isolated named cluster and joined its failing test through
the test namespace to `test-fixture-owner`. P1 queryable failure provenance and
P4 durable delegation delivery remain separate owners.

**PLANNING WAVE ARMED (owner, 2026-07-29 early): map ALL quarry
research onto the new rendering concept — DO NOT PORT THINGS EXACTLY.**
When each quarry lane returns, launch its planning agent (Opus)
immediately: (1) old-ui-quarry → the UI conversion plan — every old
component reconceived as blocks/renderers/boundaries under the
distance model, design language (Phosphor tokens) preserved as CSS +
idioms, mechanisms redesigned; the message-entry bar is priority one
if fresh has none. (2) generate-code-quarry → the v0 plan on the local
model, grounded in the old design'''s lessons + the delegation
preconditions landing tonight. Both plans then falsified before
sealing (the proven cycle). IMPLEMENTATION IS PRE-AUTHORIZED (owner,
same message): once the orchestrator has reviewed a falsified plan and
sealed its contracts, launch the implementation lanes without waiting —
"I want us to improve what the system can do through learning from the
past." The full loop runs autonomously: quarry → plan → falsify → seal
review → implement → live-prove → ledger; the owner sees results and
retains veto at every recorded ruling point. The quarry documents inventory and
lessons; the plans derive from the RULED architecture — reconceive or
retire-with-reason, never copy.

**[SUPERSEDED — historical] COMPACTION HANDOFF v2 (2026-07-29 midday).**
THE VERIFICATION HAPPENED: the owner answered the how-it-works check.
His reply is recorded VERBATIM-HEAVY in plan/README.md "Rulings
2026-07-29 late morning" — READ IT FIRST; it is next session's charter:
(1) agent MODES (chat vs goal-seeking program-call with limited turns;
goal = schema'd return or TEST-BASED: the namespace's tests all pass —
this SUPERSEDES the owner-fixed recommendation and answers
goal-completion); (2) context = MOSTLY TRANSCRIPT with age-varying
detail (blocks survive only as static scaffold: system message, REPL
instructions, AGENTS.md); (3) routed problems carry arbitrary context
(planner's markdown description); context renders in PARALLEL, sorted
by change timestamp (stable first, churn last) for caching; (4) root
uses the same context system + full-system view + BATCHED error wakes
(all queued errors = one wake; stop alarms, diagnose in-session);
(5) thinking budgets PER CALL (planner hard, repairers minimal,
no-thinking fast-local enables one-form-at-a-time repl style);
(6) from-less-outside is FRAGILE — modes replace message hacks.
LANES RUNNING: noise-fixes (helper lens nil-overrides + S8 routing
scope; helper is DISARMED on default until it lands);
dial-authority-fix (owner-scheduled, analyze-then-derive);
transcript-aging-quarry (+ my.plan status: NEVER ported — document).
LANE RULES: chunked mode always (<5min slices), never stack resumes,
big sessions relaunch fresh, one model server (Ollama, shared).
DEFAULT CLUSTER: live on 7994, Ollama via facts (max-tokens 32768 —
tighten per load table: thinking=99.24% of tokens, ~4 turns/min@10ag),
message bar + transcript live. The /goal Stop hook evaluates
retroactively and loops — the owner may clear it; do not fight it.
GATE ~481/2042/0+. The FRAMED QUEUE below stands; the owner's
verification flags are RESOLVED by his reply (routing scope: S8
confirmed by his generate-code enthusiasm + problems-with-context;
owner-fixed: superseded by test-based goals; root context: same
system + root-specific, ruled).

**[SUPERSEDED — historical] COMPACTION HANDOFF (2026-07-29 morning).** The owner is
PRESENT and was given a how-the-system-works explanation to verify
(the successor should NOT redo it — ask for the verdict): three
uncertainty flags await his answer: (1) should the GENERIC cluster
route historical reds at all, or only planner-scoped attempts (the
noise-fixes lane is implementing S8 caused-by scoping — confirm
direction); (2) the owner-fixed derivation ruling (recommendation:
derive problems from the OBSERVABLE CONDITION — var still unbound,
test still failing — so a real repair settles; declination remains the
only settle where no condition is computable); (3) root's birth
context (stays block-suppressed vs the namespace-view seed). ALSO
PENDING HIS EYES: the /goal Stop hook evaluates retroactively and
cannot be satisfied by any action — he may clear it. STATE: gate
481/2042/0+, tree green, default cluster live on 7994 with Ollama via
facts (max-tokens 32768 — consider tightening per the load table:
thinking = 99.24% of tokens, throughput 4 turns/min at 10 agents —
the budget is THE lever), helper DISARMED pending noise-fixes (broken
namespace lens + unscoped historical routing), message bar + transcript
live on the page. THE FRAMED QUEUE below is the ordered work, each
unit pre-thought. Lanes: noise-fixes running (chunked mode — resume
with small slices; never stack resumes; big sessions do not resume,
relaunch fresh). One model server at a time (Ollama), fleets share it.

**[SUPERSEDED by the schedule in docs/seon/issues/index.md] THE FRAMED QUEUE (2026-07-29 morning — every pending unit with its
pre-dispatch thought, written BEFORE dispatch per the /goal
discipline).** Format: unit — what we'd learn if unnecessary | the
dissolution preferred over completion.

1. owner-fixed derivation (OWNER RULING FIRST) — unnecessary would mean
   receipts aren't the problem source | dissolve: derive problems from
   the observable condition (var unbound / test failing), no new state.
2. historical-routing scope (in noise-fixes lane) — unnecessary would
   mean arming self-scopes | dissolve: S8 caused-by scoping, already
   designed, no filter bolted on.
3. dial-authority derivation — unnecessary would mean dials stop being
   added (false: 3 hand-syncs in 12h) | IS itself the dissolution of
   the cross-check test + 9 sync sites.
4. integer boundary coercion — unnecessary would mean no JDK API ever
   returns Integer again | dissolve: one coercion at the transact choke
   point, deletes per-site fixes.
5. planner prompting/eval (thinking-bound) — unnecessary would mean
   qwen plans well raw (disproven: prose drafts, false 'deployment
   ready') | dissolve FIRST via max-tokens budget + namespace-view
   context before any prompt engineering.
6. test-smell implementation (the 65-test dissolution) — unnecessary
   would mean the suite doesn't mind knowing runtime assembly |
   dissolve: expose the production handle construction (change 1 of 3).
7. N5 contracts authoring — unnecessary would mean rendering/ownership
   work without code-as-facts (they don't: discovery, code-hops,
   signals all wait on it) | the rung IS a dissolution (registries,
   second readers, hand lists all die into corpus queries).
8. UI slices 4-7 — unnecessary would mean old-system capabilities
   already live better (the quarry table says 60+ pieces don't yet) |
   per-slice: prefer walk/renderer reuse over new components (slice 2
   proved the walk gives most of it).
9. fleet-oversight extensions (cost/tokens per agent) — unnecessary
   would mean the fleet table suffices; WAIT for real fleet usage to
   argue need (no speculative columns).
10. audit cadence next wave — fires automatically after this landing
    wave completes; unnecessary never (owner-ruled standing).

**V0 SEAL REVIEW (orchestrator, ~04:15 — the loop is REAL, three finds
for morning).** Landed+accepted: parse-time ns attribution live at
freeze (the splitter now reads through the ONE reader — a second
reader retired); decline bound + taught via assignment-ai; settlement
renders beside the reply; live round-trip proven on Ollama through the
no-auth chain; provider-death path proven by accident (Ollama died
mid-drive; nothing hung or re-sent). Gate 479/2030/0. FINDS: (1)
BLOCKER, needs a design ruling — :owner-fixed is UNREACHABLE: the
problem derives from the immutable red receipt, so no repair ever
settles it; the clean fix candidate is rev-4'''s own words taken
seriously — derive the problem from the OBSERVABLE CONDITION (the var
still unbound, the test still failing) not from receipt redness; where
no condition is computable, declination is honestly the only settle.
Orchestrator recommendation drafted; owner confirms before the fix
lane. (2) D2'''s self-assignment refusal exists in the plan, not in
code (9/15 drive assignments were alpha→alpha) — small unit. (3) BOOT
CANNOT SELECT A CONFIG MANIFEST — two drives invented two workarounds;
the explicit manifest-selection seam (the old SEON_CONFIG idea,
reconceived) is a real unit. MODEL RESIDUE (evidence, not defect):
qwen planner messaged prose drafts + declared "ready for deployment"
with nothing defined — prompt/eval work for the namespace-view era.

**4AM STATE (for the morning resume).** Background codex wrappers are
being SIGTERM'''d (exit 143; machine load ~6-7) — three resumes died
but THE WORK SURVIVED: test-smell-audit report COMMITTED (top finding:
three design changes dissolve 65 awkward tests — exposing the
production handle construction to fixtures, event consumption over
pollers, the entity-walk property); generate-code v0 PROOF NOTE
committed; v0 lane (Agent-tool, alive) has landed four commits incl.
settlement-beside-the-reply. UNCOMMITTED lane work in tree (resume
owners, do not clobber): slice-2 css/input.css + context_pilot_test
(transcript, mid-suite), local-provider doc edit (load lane). MORNING:
resume ui-slice2-transcript, test-smell has its report (implementation
units next), load-testing (harness error triage + Ollama drives), seal
review v0 on its notification, THEN bounce default + apply the Ollama
override + the owner types to helper. Do not stack multiple resumes on
one lane; never chain resumes with &&.

**OVERNIGHT CHARTER (owner, winding down 2026-07-29): press on
autonomously, nothing crazy.** Keep going through ISSUES; keep AUDITING
TESTS for what we are not understanding — the simplifications that
dissolve complexity; awkward testing is a CODE SMELL (a design verdict,
not a test problem); hunt concept DUPLICATION; and COMPLETE THE UI —
the old system'''s functionality restored (never the same way, the same
capability, reconceived). Review every return, redirect early, think
between dispatches.

**WORKING EDGE (2026-07-29 ~03:00).** Gate 467/1953/0. LANDED since
midnight: F4 all five drives PASS (6 parallel/83ms, 100 parked at
2vt+50KiB, SIGKILL zero-replay, cap byte-equal, two clusters); the
independent audit CLOSED (both blockers fixed — stream clear deleted
per owner ruling, oversight throw); the MESSAGE BAR live (e7c02a483 —
POST /agent/{id}/message, identity probe 64/64, live Ollama round
trip); agent birth seeds the namespace view (df160158f); the sealed
READER (90338c62a — 42-file self-seeding suite); E3 about-refs
(4a9b9161d), E5 decline (200a447c0), E2-prime routing + seven-state
settlement + X2 (c7eecfb4f, 8e57347d2) — GENERATE-CODE SEAL MET, v0
IMPLEMENTATION DISPATCHED (Opus; live proof = staged two-namespace
goal + delegation round trip on Ollama, scratch cluster). UI slices 1+3
sealed; slice 2 (transcript) next after the owner types. IN FLIGHT:
config-chain-fixes (the three-layer no-auth trace: bridge := form,
manifest cross-check test, .env sourcing, dial literals — acceptance =
helper REPLIES on the owner'''s page), load-testing (Ollama parallel,
thinking-tokens policy: keep/bound/measure), v0 impl. RULINGS 2026-07-29
batches 3-4 + D10 + Ollama-is-the-project-server + audit cadence all in
README. Owner'''s cluster: default on 7994, agent helper created
(my.agents.helper), bar serving, awaiting the config fix for replies.

**AUTONOMOUS CHARTER (owner, night close): iterate, don'''t be
dogmatic.** No final solutions expected — keep the cycle moving
(falsify → revise → seal → implement → live-prove → ledger), surface
evidence that contradicts a ruling with a recommendation instead of
complying or deviating silently, iterate hardest on agent-facing
output quality (default renderers, prompts, error prose — read the
real output). CURRENT STATE for a cold start: F0-F2 landed (agents
are flows, gate 404/1612/0 at d28598214); distance accretion landed;
fleet-oversight block live on /; N5 plan revised post-falsification
(64a796b28) — SEAL GATE = owner rules the 12 decisions + name table
in its §8; context pilot in flight (before/after prompt evidence +
live Qwen drive); F4 parallel drives next after the pilot; §7
delegation preconditions are separate units with their own owners.

**WORKING EDGE (2026-07-28 midnight).** QUEUED NEXT (owner ruling #2):
the namespace+distance CONTEXT PILOT — one agent'''s prompt derived as
render(namespace, distance) over the entity graph, static blocks
reduced to scaffold; dispatch when the render-distance lane lands;
its evidence feeds the N5 plan revision. F2 COMPLETE (5daf05e24,
2e372027d, a468a92b1, 72019b7ae, 96a2ddfaf) — central loop deleted
(13 fns, 1 ns, 6 attrs, 0 new), live SSE proven, gate 396/1564/0.
THE SPINE F0-F2 IS LANDED: agents are flows end to end. MCP bridge
decoupled from source (ade7d2344) + verified live; boot reopen
accretion (546987dfc); default cluster RESET (owner-authorized; old
data in tmp/reset-proof), page 200. NO FABLE SUBAGENTS (owner ruling
— Agent tool inherits parent model; always pass model opus; in
auto-memory). IN FLIGHT: renderable-corpus N5 plan (Opus — the
namespace-centered/scoped-views/bisect-to-owners rulings, see README
late-night batches); fleet-oversight block (sol); operator
stop-fallback (sol). NEXT SPINE: F4 live drives (N-agent parallel,
two-cluster, 100-parked, kill -9), then N5 per the returning plan.
Known 500-class issue: stale-vocabulary rows on reopened branches —
boot should refuse loudly (filed by F2 lane).

**WORKING EDGE (2026-07-28 night).** QUEUED (owner direction, late night):
a fleet-oversight BLOCK over flow ping — one unit pinging the
cluster's graphs (parked/mid-turn, current-run-id, episode-runs,
buffer occupancy) with ai+html projections through the ONE router;
root carries it by default, capture snapshots it as the
live-processes trusted input. Dispatch after F2 lands (wants the
render proc + F1 ping states). Derive-don't-store; measure before
caching. Gate 425/1675/0 (context-blocks
landing; independent verification in flight). LANDED tonight:
context-blocks rung complete (082370df9/6d89d9107/f31d63e39/e377688e2
— schema, seon.context + pre-provider capture, membership/omission,
loop :call wiring, the :seon.render.block/* rename ruling, all 14
sealed deftests); F0(a) var step-fns + pinned workloads (1b72cc8da,
construction refuses :mixed); F0(b) codec totality regression
(ad7a488f7); no-auth provider state (1a8184a56); both audits
(b205f55fd trigger-conservation — P1 livelock + P2 duplicate-paid-call
REPL-proven, episode query ~34 µs zero new facts; 5c5b4fda1 zombie —
none constructible, presence subsumes epochs, deletion slice sized);
custody contract SEALED (ce3ae1c89); F1 blueprint contract SEALED
(e578c4125 + c0cd2705d — two seal corrections: recorder never resets
the episode; cap-hit selection skips deferred self-triggers).
CUSTODY LANDED (435b343ac + 100159309 + ce59f8ab4 — epochs/leases
deleted, probes red→green, prompt-refusal throw sealed; custody
suites independently verified 32/179/0; architecture docs purged of
epoch/lease/heartbeat 8da1560f7; episode-cap default RULED 100,
5c2d603f2). IN FLIGHT: F1 implementation (Opus — the agent-graph
blueprint per the sealed contract); test-constructions units 6/7/9
(render/schema/bench trees). AFTER F1: units 4/5/8 (cluster trees),
F2 transport conversions + central-loop deletion, the MCP live proof
(boot a cluster, owner restarts the app, verify eval_clj). Owner-gated: episode-cap default (F4 evidence), test units
4–9, N4 transcript page (messaging rulings done except verbatim-reply
confirm). Frictions queued from context-blocks: schema/digest helper
ownership; the load-order scar (register! in leaf require chains);
`:seon.block/count` boot residual rename.

**WORKING EDGE (2026-07-28 midday — the presence-not-kinds wave).**
Gate 403/1566/0, independently verified. The morning decision session
resolved into the "Rulings 2026-07-28" batch (README): omission =
nil-punning, state is presence (three stored discriminators DELETED,
sealed N3 receipt revision: presence + `interrupted-at`, settlement
fences on absence), context-blocks Decisions 2–4 per recommendation,
test units 1–3 approved. Landed and reviewed: the simplified fresh
operator (`6e7b03738`, de-hacked `7ccd1347a` — plain stop! var,
event-driven empty-JVM exit); render nil-unification (`12a5fef33` —
declaration?-on-get everywhere, omitted blocks keep their identified
wrapper element); presence-not-kinds (`7bb7ccbfe`+`447abce8e` — enums
deleted, wrong-trigger `:call` defect fixed with regression);
data-model.md third rule (`9a0fe5266`); context-blocks CONTRACT
SEALED (`b05a6b9f6` + seal correction `a236eefc6` — declaration? at
the selector). Research inputs: simplification-catalog-2026-07-28.md
(six collapse groups; group 1 = render unit everywhere),
state-without-kinds-2026-07-28.md (doctrine + audit).
IN FLIGHT: test-constructions sol lane (units 2–3 of the review;
unit 1 `44fb814f4` + fixture `08c18b305` landed).
NEXT READY: context-blocks IMPLEMENTATION (contract sealed, its lane-A
dependency landed); catalog group 4 (admission-codec D2); the
prompt-formatter → render-unit collapse (catalog group 1, after
context-blocks lands its census).
STILL OWNER-GATED: messaging 6 questions (my-message-proof §7 — gate
N4 package 5, the agent-transcript page); test units 4–9 (after 1–3);
no-auth provider admission (local-provider-2026-07-28.md); projection
outputs re-read; MCP restart (task #3, owner action). New issue:
a-nil-query-input-matches-anything-so-prompt-cannot-refuse.md.
The local Qwen server runs as a harness background task on :8090 —
it never exits by design; the owner knows.

**SEALED (owner, s3 close): schema definitions leave code files.**
Attribute/entity schemas are EDN data under `src/seon/schema/*.edn` —
the schema OWNER's folder, on the classpath so runtime inspection and
`(schema/reload!)` are first-class. The population is GLOBAL: file
boundaries are editorial convenience with zero semantic meaning; the
loader merges every file and refuses a duplicate attribute across files.
ONE validating admission gate (all references resolve, every `[:fn]`
names a registered core predicate, generative-honesty lint) shared by
both producers: our declared files at build/boot, and agents'
`register!` transactions at runtime — one gate, one registry, and the
build-time indexer READS THE DIRECTORY instead of loading namespaces to
scrape registrations (deleting the load-set-closure failure class at the
producer). Function contracts stay on defns; named predicates stay in
code. Conversion of N2's in-code registrations into the first schema EDN
happens when the nucleus-run-impl lane returns (never edit a sealed file
a lane holds); fold into the B2 contract at authoring.

**2026-07-26 s3 close — the nucleus era.** README's session-3 rulings and
the nucleus ladder (R0/B0-B3/N2-N6) supersede everything below that
contradicts them; rows below predate the pivot and survive only as
evidence pointers. **N2 IS GREEN** (verified 2026-07-26 s3 close: 7 tests / 24 assertions /
0 failures / 0 errors via the nucleus loop command; implementation
`d30a405f9`, contract revisions `1b03d80bb`+`a370b5e31`). The
construction loop is PROVEN: three friction cycles, every stop
legitimate, no schema or test weakened. First rung metrics: 1 namespace,
17 registered attributes, 8 pure functions, ~330 nucleus source lines.
Non-blocking friction on record: claim-tx's two observed takeover fields
are independently optional but required together — tighten to one
optional takeover map at the next contract revision, not before.
Corroborated 2026-07-27 by the first live Gemini hook review
(tmp/reviews/20260727T112009): a nil `observed-epoch` in takeover mode
emits `[:db.fn/cas ... ::claim-epoch nil 1]` against a non-nil epoch —
the takeover map must carry a REQUIRED epoch. Fold into the same
revision.

**R0 EXECUTED 2026-07-27** under the session-2 rulings (fresh tree IS the
project): fresh `src/` = seon.cluster.run + seon.schema{,.form,.internal,
.datahike} + seon.flow; fresh `test/` = run-test + flow.loop-test; root
`deps.edn` rewritten (fresh default + `:dev`/`:test`; old world behind
repointed `:writer`/`:writer-test`/`:cljs` aliases); `bin/test` is the
system gate (8.15 s full, selection via the edit hook in seconds); system
load 2.23 s (10-second ruling already satisfied pre-B0). Contract
revision on the sealed N2 test (author-owned): the malli→datahike bridge
moved to its proper owner `seon.schema.datahike` — the test no longer
requires the old `seon.db` facade. `seon.flow` owns its config dials
locally (the `config.resolve` seam cut). Verified: `bin/test` 11/55/0/0;
hook selects exactly the affected fresh suite per edited file.

**N2 REVISION GREEN (2026-07-27): 6/28/0 via bin/test** — transitions
inside the transaction (Opus implementation `ba5cb0c1e`, contract
`c65ddeeda`, suite harness fix by the author after the lane's correct
friction stop). Open contract decision for N3: a refusal's ex-data does
not survive Datahike's writer boundary (caller sees {}; the kind/rule
live in the message and cause chain) — the run loop's transact wrapper
is the ONE unwrap point to design when the loop consumes refusals.

**Quality-review-1 verdict (2026-07-27, `d91dab541`) — resolved by the
revision above; original text:** the N2 contract
revision is now FIRST, ahead of B0/B1 — two live-reproduced correctness
holes (takeover eligibility not fenced in the transition; agent pointer
not fenced at open/close) plus the property gaps that let them through
(no takeover/close/heartbeat coverage; terminal-preservation unproven)
and the Gemini-corroborated nil-epoch takeover. One revision wave,
orchestrator-authored: eligibility moves INTO the database transition
(the invalid state unrepresentable, per the quarry's run-fence), one
required takeover map, and a state-machine generative property over
transition sequences that observes durable facts. Separate bounded lane:
adopt the surviving flow suite out of `test-old/` (15/72 green against
fresh source, currently invisible to `bin/test`). B2 design input:
replace the `core-process-identities` allowlist with a computed rule.

**BOUNDARY 2026-07-27 EOD — the tower stands; B2 sealing is next.**
GREEN via bin/test (42/182/0 + the fcntl falsifier): N2 run model
(transactions-as-transitions, model-based state machine), B0 entry
(REPL-first, ten-second bound in-suite), B1 store (flock + genesis
repair + cross-process falsifiers), adopted flow suite, bridge
regressions. FOUR PLAN DOCS delivered and owner-reviewed under the
planning-agent workflow (plan → orchestrator fixes → seal → delegate):
b2-plan (branch-per-cluster ADOPTED, fork fix blocking+falsifier-first),
n3-plan (parser shed to sci reader, no lease clock, 4-line wake), n4-plan
(zero new attributes, mult-not-flow fanout, listener-on-commit-path
measured), test-selection-spec. ALL RULINGS in README ('Rulings
2026-07-27' ×3 batches — read every one before sealing).

**Quality-review-2 (`4bc02d33e`) triage — folds into the B2 wave:**
five reproduced blockers. Dissolve by design: lease expiry games (N3
deletes lease clocks entirely) and terminal-receipt reversion (receipt
writes become fenced transitions in the N3 contract). Fix in the wave:
failed d/release drops the flock while the connection lives
(store.clj:299 — keep the fence on failure, loudly), name-addressed
stop! kills replacement instances (cluster.clj:336 — stops become
instance-addressed), close tolerates a broken agent pointer silently
(run.cljc:419 — refuse, never omit). Plus the standing Gemini backlog
race and the issue-index lifecycle cleanup.

**B2 wave progress:** revision chunk SEALED (`78ddeb885` — store map
arg + open-branch! + fence-survives-failed-release; B0 store-dir/
ancestor-branch + instance-addressed stop!; N2 close
::agent-pointer-broken; falsifiers sealed for each) — implementation
lane b2-revisions-impl making it green. fork-roster-fix lane running
(falsifier-first, blocking registry work). Issue triage done
(`90a3cac60`+`865431fec`): 164 stale notes archived, 17 real open
issues mapped to rungs (see triage table in the lane summary /
index.md). Lease deletion + receipt fencing deferred to N3 sealing
(their consumer).

NEXT: **chunk 2 authoring — the six new B2 namespaces** (fresh-window
work; b2-plan §9 has seal-ready candidates + §7 the reconcile
algorithm quarried): seon.schema.edn, seon.reconcile (plan/reconcile!
— pure plan BEFORE transact, empty = NO transaction, :max-tx unchanged
= converged; reuses the N2 [:db.fn/call #'f] idiom), seon.config (+ THE
default manifest — honest dials = seon.flow's + on-core-error, never
State A's 39), seon.cluster.ancestor, seon.cluster.registry (UNBLOCKED — fork roster
fix landed 357ffc87/a6434ecee, falsifier-first, issue closed),
seon.cluster.export. Note for the ancestor contract: the BUILD half's
program-facts producer is N5's indexer — B2's ancestor seals with the
build population injected (schema facts now, program facts at N5) so
the fork mechanics don't wait. Then implementation lanes. Revision
chunk GREEN (5c95e259c); schema-EDN GREEN (b432bd07f — 103 declarations
converted, one gate live on both producers, honest generators
throughout). **IMMEDIATE WORKING EDGE (write-down before compaction, 2026-07-27
night).** The boot COMPOSITION (task #9) is sealed (f2fecffea: start!
threads the whole tower, REPL survives any later-layer failure, the
throw carries the degraded instance under :seon.boot/instance) with
seal-side EDN fixes at 35a938872 (boot.edn instance gains the three
optional tower keys; provenance.edn gains the :seon.db.process/id
identity plus :seon.db/index facets on the two refs). ACTIVATION FIXED (e02dcfd9b,
2026-07-27 night): the load-order coupling was real — ALL of
reconcile's registrations moved to seon/schema/reconcile.edn (one
authority; code register! block deleted), and provenance.edn's
`[:seon.db/ref {props}]` was invalid Malli — facet properties attach
to TYPES only, so the two refs use the one supported idiom
`[:and {:seon.db/index true} :seon.db/ref]` (the datahike bridge
recurses through :and heads). PROVEN: (require 'seon.cluster
'seon.config) clean; bin/test 87/359/7 where the 7 failures are
EXACTLY the sealed composition falsifiers awaiting implementation.
COMPOSITION GREEN (2d2655922, full gate 87/358/0 independently
re-verified; live probe: siblings share one store, last stop frees the
flock, failed tower leaves a working REPL, same name restarts clean).
Seal-side follow-ups b3b8d6a92 (honest crash walk, populate-ancestor!
contract, trust-list-inert + half-released-stop! issue evidence). The
GC experiment is ANSWERED (4a70900e1): retire ~50-60ms, reclaiming GC
~160-255ms, storage halved, survivors exact — retire casually at
teardown, coalesce full-store GC. N3 rulings recorded (858436ca8):
interrupted+adapt no auto-retry, my.run = complete/wait only, run-why
= trigger ref as tx-meta. ADMISSION SEALED (6caf0f5fe): the draft (884c0ca6e,
src/seon/sci/admit.clj + schema/admit.edn + admit_test.clj,
probe-grounded: realization calls the interrupt-fn at EVERY node;
IDeref never dereferenced; cycles unrepresentable by construction;
node budget primary) sealed with the activation seam KILLED AT THE
GATE — seon.schema.edn requiring-resolves a [:fn] predicate's owner
namespace before refusing (the computed rule; falsifier pair sealed,
edn_test_fixture.clj deliberately undiscovered). Four cap dials wired
into manifest/effective/entity + config/default.edn (12/64/4096/4096,
probe provenance). OPEN OWNER QUESTION (drafted choice stands):
projection failure = marker, never dev-panic — R41 tension flagged.
Full gate LOADS clean: 96/386, red = exactly the 11 admit
awaits-implementation stubs. sci on default classpath (858d6bf86).
TRIAGE DONE (2fef74665): 5 resolved archived, 10 open-current,
3 deferred, 2 N3-owned, one extracted
(cluster-stop-release-failure-becomes-unaddressable). TRUST DESIGN
DONE (0e71d42b5): core = first assertion precedes the ancestor
digest-seal tx, fail-closed :agent, literal list dies.
ADMIT GREEN (171a94e02, one file): suite 9/57/0 re-verified
independently; full gate 101/453/0 at the lane's run. The lane went
past the sealed suite (5 seeds × 200 trials + an idempotence property)
and fixed three self-found defects — depth-through-markers off-by-one,
node-budget overrun by marker cost, sci.lang require (caught BY the
new predicate-owner resolution). Documented tensions live in the
admit.clj docstring/risk notes: ::elided is scalar (capped? is the
honest signal), Instant normalizes to Date, host java.util.Map
projects opaque, record tag costs one width slot, test/prod node
accounting deliberately independent. TRUST DERIVATION LANDED (eda73bead, full gate 101/453/0
independently verified): admission derives core from d/history (first
assertion strictly precedes the unique ancestor seal tx), everything
fails closed :agent, the literal roster DELETED, ancestor population
reordered so canonical schema rows carry core provenance; issue
closed+archived (c2db0e61f). N3 PACKAGE 1 (pure derivation) DRAFTED (0f5f5607e: my.run,
seon.cluster.{reply,work,prompt}, five schema EDNs, sealed suites
with fixed seeds) and SEALED (6cf1d7317: :seon.db/trigger moved to
provenance.edn with the indexed :and wrap; work-namespace split,
interruption/next-work split, crashed-run answeredness all blessed as
drafted). Package-2 rulings sent: transact!/refusal accrete INTO
store.clj (new fns only); the flat :seon.error shape registers ONCE
in package 2. ALSO LANDED: bridge :and-unwrap fix at the choke point
(7012c595d, class-wide parity checks, issue archived);
dial-attributes shape fix + archive status fix (8372d64be);
bb-classpath fix BLOCKED on old-operator launchers (evidence
6a9b5a452, issue open — the seam dies with the operator). QUEUED
OWNER QUESTIONS: R41 vs marker-not-panic at admission; does the
[:maybe] ban extend to fn return contracts (next-work nil = idle,
read-advertisement)? PACKAGE 1 GREEN with one seal revision (0e1e9dc01): the fold's end is
its own FOURTH situation :close (ruled (b) — fold-vs-close visible in
the value, never a missing-key flag); the reply-level prose-vs-plan
verdict blessed (a per-form filter would silently eat trailing agent
forms). HOOK FIXED (8ac9a9c02, velocity incident closed): the hook no
longer loads retired seon.dev.config; correct selection proven on
three cases; bin/seon test changed still routes through the old
operator and dies with it. N5 PLAN REVIEWED (d500c8bfb): 19 findings
(6 confirmed defects incl. :seon.fn/schema-refs duplicate owner and
the namespace-row derivation, 6 stale-vs-today, 7 owner questions),
NOT ready to seal — revision lane running with the review as work
order. N3 PACKAGE 2 DRAFTED (997252038: seon.cluster.wake C1-C3 with both
prohibitions measured, seon.cluster.loop C9 with crash rows as
kill-positions-over-facts, seon.ai C10 with countable one-attempt +
credential as the one env read, store.clj transact!/refusal ACCRETION
below a banner, :seon.error registered once in error.edn; full gate
143/593 with 36 awaits-implementation red). SEAL RULINGS SENT (the
Opus agent applies as one bounded seal-revision commit, then
implements to green): (1) :seon.error/value re-point blessed
everywhere incl. sealed my.run/complete; (2) loop's turn/step may not
contract as bare [:map] — named shapes in loop.edn; (3) wake_test's
throwing-handler falsifier must assert fault DELIVERY (review-caught);
(4) wire pins: JDK java.net.http + org.clojure/data.json (landed
91d45256e), string JSON keys at the one :any boundary. The live
seon.ai call and the kill -9 process-boundary proof stay
ORCHESTRATOR-OWNED integration falsifiers, out of bin/test.
QUEUED MINE: delete seon.flow/database-proc + its testbed pins at a
lane-quiet point (n3-plan §4.1); the R41-vs-marker and [:maybe]-in-fn-
returns owner questions; task #3 MCP verify (needs Sean's restart).
N5 PLAN REVISED (aa6759fd3): all 19 review findings dispositioned —
12 fixed in place (schema-refs duplicate removed, acquisition
composes the landed projection/admission owners, ancestor population
matches today's provenance order, reset + process-kill proofs moved
IN-SUITE), 7 promoted to one "Owner decisions required before seal"
section (7 decisions, options + recommendation each) for a single
batch ruling when N5's rung opens. N3 PACKAGE 2 GREEN (seal revision 97e28e675 + implementation
93aa9d6de): FULL GATE 148/648/0 INDEPENDENTLY VERIFIED. wake's
offer!-false = closed-channel fault (honest, not manufactured);
author-written turn coverage drives [:open :call :resume :resume
:close] end to end with the evaluator injected as a qualified symbol
(the seon.sci.eval adoption plugs in untouched); my.run seal fixes
e167c6bc8 (honest close-only docstring, wrong-type guard on both
dispositions — review-caught ClassCastException). N3 REMAINING: the
ORCHESTRATOR-OWNED integration proofs (one live DeepSeek call proving
request/response shapes; a live flow graph driving step; the kill -9
child); the seon.sci.eval adoption rung; digest SHA-256 helper
triplicated (ancestor/config/loop — wants one owner, queue). N4 PLAN
REVIEWED (399e818b4): NOT ready to seal — revision lane running.
DATABASE-PROC DELETED (c7a93b075, -446 lines: proc + three private
helpers + four schema rows + pinning tests in the same commit per the
deletion doctrine; gate 147/640/0 INDEPENDENTLY VERIFIED; lost
incidental coverage named honestly in the commit — the surviving
owner is seon.cluster.loop + wake under their own suites).
THE LIVE DRIVE (tmp/n3-live-drive.clj, owner watching, four rounds):
(1) trigger REFUSED — the fixture-vs-live-boot class: message/eval/
form/agent families had no entity maps so canonical-database-
attributes never installed them live; FIXED 38ab48470 (four entity
maps + the non-vacuous class-killer: loop's declared write set ⊆
installable, plus a boot-derivation database test; gate 164/723/0).
(2) same refusal — the cluster branch pre-existed in the roster,
found never re-forked; drives now use a fresh root per run. (3) run
opened+claimed but stalled claimed-with-no-plan 120s: ai/complete
returned no-credential invisibly (DEEPSEEK_API_KEY lives in .env,
never in the tool shell — `set -o allexport; source .env` before
drives) and the error value DIED WITH THE TURN — durability gap
dispatched. (4) REAL TURN: claim 0.6s, plan frozen +2.7s (DeepSeek,
three forms), fold ran, receipts durable with full sci diagnostics,
run closed. Form 1 defn :done (Gauss!); forms 0/2 errored on two
base-ctx gaps — (in-ns): evaluator must evaluate IN my.agents.<id> by
construction + prompt says so; (println): sci *out*/*err* unbound in
the fork. Base-ctx fixes landed 020966ea4 (namespace-by-construction with ONE
derivation shared by prompt+eval; in-ns WORKS contained by the fork —
flagged, not forbidden; sci *out*/*err* captured as bounded
:seon.cluster.eval/output receipt evidence; credential nil guard;
model error closes the run WITH :seon.cluster.run/error in the same
tx; the class-killer caught that new attribute too; turn_test fixture
now installs canonical-database-attributes). **PHASE 1 COMPLETE
(97a0824b5, research/n3-live-proof-2026-07-27.md): first full live
turn — boot 1.28s, claim +0.65s, DeepSeek plan +2.68s, 4/4 receipts
:done, (my.run/complete "55"), run closed +2.41s, faults nil.**
PHASE 2 (kill -9 → interrupted+adapt) IN PREP: drill scripts
tmp/n3-crash-{child,verify}.clj; the Opus agent is correcting the
verify choreography (interruption requires an UNCLAIMED run — what
settles claimed-by-dead-pid, lease 60s, may interact with the
run-contract-hardening lane's lease-expiry fix). HARDENING LANDED (21215ce28: ::now threaded one-clock-per-pass,
held-run refuses ::lease-expired, takeover/terminal-preservation
acceptance; both issues archived). GAPS 1+3 LANDED (ba723b2d1:
start! recovers dead custody AT BOOT by fact — falsifier plants a
ten-minute future lease and boots in <10s; instance reports
:seon.boot/recovered-runs counts; prompt warning no longer shadowed —
excludes the run the agent pointer names; process-identity
<pid>-<start-millis> added, bare-pid recyclability named). Gate
171/777/0 verified. OWNER DIRECTION (evening): errors must surface
the exact problem — wire flow's error channel via fault-committer,
fault fact + explanation MESSAGE to the triggering agent in ONE tx
(eval errors stay receipt-only), escalation recipient as a config
dial, a `problems` derivation, malli instrumentation at dev boot
(currently NOWHERE enabled) with reload-reapply; model-call
resilience = primary/backup descriptor rows with instant failover on
error-class (backup's context says the primary failed and why),
backoff-without-secondary reconciled with the no-retry ruling.
**PHASE 2 COMPLETE (dd8a483db, evidence in
n3-live-proof-2026-07-27.md): kill -9 mid-model-call → reboot →
start! recovered dead custody BY FACT ({:recovered-runs 1}, lease
still ~50s future) → the LOOP buried the orphan itself and drove a
NEW run to completion (real DeepSeek) in 3.1s of reboot → receipts
only for the new run, crashed run never re-planned, warning in the
new prompt. Interrupted+adapt proven over real facts.** Pre-drill
unit a6d426983 (GAP 2 settle-before-derive; ONE holder string
cluster/process-identity everywhere; drill = production wiring only).
Gate 172/785/0. RESEARCH IN: error-handling-grounding-2026-07-27.md
(e8fdd3518 — flow's error chan is sliding-100 SILENT DROP and start!
wires NO consumer, every core fault vanishes live; three incompatible
error-report shapes, ::flow/state must go through admit never pr-str;
classification needs NO predicate — evaluate never throws so channel
= classification; message-to = delivery already built; malli :report
does NOT prevent the bad call, +129-175ns cost, re-eval silently
strips instrumentation → explicit idempotent apply!, hot fns are
defn- so "public + schema" needs no list; six defects D1-D6 → issue
lane binayawwx filing) and model-failover-2026-07-27.md (b44bd1527,
litellm-clj + again mined). ERROR-WIRING SLICE 1 LANDED (3bd147643, gate 172/789/0): fault
entity schema + :seon.cluster.message/about + two fault dials + the
completeness-rule fix (requiredness reads :seon.config/effective —
reading the manifest computed a VACUOUS #{} because every manifest
entry is optional by design; optional dials were unrepresentable
before). The drafting agent stopped honestly with context exhausted
rather than half-drafting four packages. ORCHESTRATOR RULING: the
rename stands — ONE owner `seon.error`, fault.edn's entity merges
into the error family BEFORE anything references :seon.fault/*
(agent's recommendation accepted; a one-file change today, expensive
after four namespaces reference it). OWNER DIRECTION (late evening):
one normalization function for EVERY error class (kinds computed
from sites, never a hand list; standing totality property: every
committed error fact validates :seon.error/value) + PROJECTIONS PER
CONSUMER exactly like the render contract — ai steering prose
(stored at commit time; it IS the failover "you are the backup"
message and the explanation message content), log line (derived),
html at N4 via the ONE render contract (design the fact to permit,
build nothing). STEP 1 DRAFTED (5ac6cf4ef, agent a072b05ef16cbadcd — the exhausted
predecessor was a96513c593d3a6a83): seon.error normalizer (four
families detected STRUCTURALLY, fail-closed :seon.error/unclassified;
kind from deepest ex-data, no enumeration; signature excludes the
message so recurrence is countable; THE RECORDER NEVER PANICS —
orchestrator-ruled: the dial governs the failing site, the error
system is the loudness mechanism, "the fire alarm doesn't burn" —
present to owner), seon.render generic router (kinds computed from
:seon.render/* qualified-symbol keys; late requiring-resolve of the
VAR; wrapped {kind,output}; undiscovered fixture proves
resolve-loads-owner), notice carries reason + projection keys
(derived), fault.edn merged into error.edn (and its bare-ref
message/about defect caught+fixed in-draft). All seven taste calls
ruled as drafted. Gate 198/822 with exactly 33 stub errors.
STEP 1 GREEN (44435f07b + seal revisions 74a8efb08 + ui.md accretion
de59da156; gate 199/873/0 INDEPENDENTLY VERIFIED): normalizer +
router implemented; the agent read ACTUAL outputs and fixed two
things tests missed (root-cause prose over the wrapper word;
capped? = elided-or-truncated asserted both halves); the ai prose
sample ends "Nothing will retry this for you: read error err-7f21
and decide from the current facts." OWNER RULINGS (night): boot =
live agent host with ZERO token cost (loop armed-idle; agents are
ROWS not processes — root agent seeded free at boot; models called
only on real triggers); two-cluster live proof right after step 2;
CONCURRENT AGENTS PER CLUSTER is the target end state — "the
database is the intermediary... design it correctly with flow and it
should just work" — safe dial-bounded version first, measured;
one-user-per-workspace, maybe multiple. STEP 2 GREEN (e1f7262c6, 203/913/0 — one 1-in-3 FLAKE filed as
full-gate-has-a-one-in-three-flake-post-step-2): boot arms fault
fan-out + root agent + armed-idle loop (dials-derived handle, four
:seon.config.ai/* dials, zero model calls); refusal moved to
seon.error PURE (returns tx-data — the caller commits); D1/D3/D4
closed+archived. THREE LIVE FINDINGS: (1) THE ERROR STORM — delivery
IS a wake, so one broken code path self-fed 6 faults/1.5s; bounded by
recurrence signature (limit → one :recurring escalation → silence;
facts keep committing, a fact alone wakes nobody); (2) who-is-told is
COMPUTED from the fact (only a Throwable messages the attributed
agent; refusals = fact + escalation only — messaging refusals made a
test drive open runs to DISCUSS refusals); (3) the newly-wired error
channel immediately caught a real defect (loop's ::turn-report
resolved no channel — invisible exactly as long as nobody read the
channel; now rides ::flow/report). OWNER-LOOK ITEM: root's
escalation message opens a real run for root (delivery-as-wake) —
bounded limit+1 per signature per process; revisit when root gets a
real prompt. NAMED NOT DONE: flow/stop mid-turn loses that
transaction (kill row; recover-tx settles next boot; honest fix = a
completion the proc publishes). DISPATCHER DESIGN IN (d6212af43):
the double-token-spend race is the ONE thing the database cannot
see — process-local active-agent set fences money, everything else
is transitions; lands after the two-cluster measurement.
STEP 3 GREEN (b2fefe9d5, 218/954/0, TEN consecutive gate runs — the
flake was the agent's own armed-test equality racing a still-storming
producer; de-flake lesson: equality on a producing producer is a race
BY CONSTRUCTION, upper bounds are monotone-safe; issue archived).
seon.problems: pure over (db, live-processes), four families keyed BY
family (no :type), healthy = {}, signatures grouped (100 recurrences
= 1 problem), drives print it on failure (live-verified). Stale
triggers DEFERRED correctly (threshold = a number standing in for an
unobservable event; derivable when the loop publishes a pass
boundary). Three more recorder-survives-our-mistakes fixes:
attribution read off the FACT (request/db divergence suppressed a
real notice), dangling ref dropped not emitted, nil-limit = fact
committed nothing mailed. STEP 4 GREEN (6215ff0bc, 225/976/0 verified): seon.instrument —
computed selection (public + :malli/schema; hot walkers are defn- by
construction), :panic throws OUR flat violation value which composes
into the wired fault path (violation → error fact → message →
problems), :record instruments NOTHING (judgment flagged,
one-line-reversible), wired at bin/repl + drives NOT start! (a
cluster dial must not mutate process-global var roots — ruled
accepted). FOUR never-compiled contracts caught on first collect!
(catn duplicate key, bare-symbol :fn, loop/cluster missing
::flow/pid, turn-report demanding an id :open lacks) — the step's
argument made empirical. Nineteen released-connection teardown
violations = ONE issue
(instrumentation-surfaces-released-connection-contracts) with the
real bar: gate green WITH apply! active. Two runaway probe JVMs from
2026-07-26 (99% CPU × 29h) found+killed during owner process-audit.
STEP 5 SLICE 1 GREEN (e3bc8c31f, 232/1001/0 verified): transport-
phase evidence (request-transmitted?/response-started?/
output-observed? from the JDK's OWN exception taxonomy — connect-
class exceptions prove nothing was transmitted; everything else
counts as transmitted) + seon.ai/disposition, pure,
:failover-now|:backoff|:fail with the backoff set COMPUTED.
ORCHESTRATOR RULING: the agent's departure from my brief is ACCEPTED
— a plain ::timeout is :fail, only a CONNECT timeout fails over
(a transmitted request is ambiguously paid; re-calling it is exactly
what the no-retry ruling forbids; my brief was looser than the
research and the research wins). STEP-5 REMAINDER (the drafting
agent a072b05ef16cbadcd is context-EXHAUSTED after five green
steps — a FRESH agent takes this, in dependency order, spec in its
final report + research/model-failover-2026-07-27.md §named
sections): (1) descriptor rows growing the :seon.config.ai/* dials
(primary + optional backup, reconcile not duplicate); (2)
per-attempt receipt facts (new schema family); (3) failover
execution in the loop's :call branch consuming disposition, backup
context = error/ai-prose over the primary's COMMITTED fact (commit
before the backup call — ordering constraint); (4) backoff strategy
as again-style config facts, no-backup path only. **TASK #10 COMPLETE — STEP 5 DONE (cd9f41fb3 + 4f93d6587, gate
246/1095/0 VERIFIED).** Descriptor rows: backup = OVERRIDES over the
primary (:seon.config.ai.backup/model decides existence; partial
backup unrepresentable; loop/provider DELETED — same map, second
name; deliberate departure from research option 2, recorded in
config.edn). Attempt facts: one row per call, role by CONNECTION
(failover-from/delay-ms, no :primary stamp); primary's error fact
commits BEFORE the backup call and record-attempt! returning nil
after a refusal makes that total (a paid call whose reason couldn't
commit doesn't happen); backup context = ai-prose over the READ-BACK
fact (ordering proven by equality); backoff schedule EMPTY whenever
a backup exists (held by data). Judgment calls accepted: problems
not extended (successful failover isn't a problem); no :running
attempt row (a second interruption mechanism for a fact nothing
reads); the failover prose carries operator-aimed noise → the
projection-review experiment's first exhibit. FILED:
a-turns-model-work-can-outlive-its-own-run-lease (60s deadline = 60s
lease; honest fix is a claim-contract interface change, N2/N3-owned);
stop-may-leave-the-prepl-server-name-registered (unverified Gemini
flag, same-JVM same-name restart). Datahike scar: :db.type/long is
EXACTLY java.lang.Long — an Integer refuses the WHOLE transaction.
OWNER RENDERING RULINGS (night, binding on N4): root is PER-CLUSTER
(one root agent each; multi-cluster root someday); PORT the old root
interface — "it's really just different context blocks that return
:seon.render/ai and :seon.render/html" so BLOCKS are central to N4
and root/agent views are one mechanism; translate the old UI's
interaction (don't reinvent), FASTER + MORE RESPONSIVE as named
goals; canvas unified — all renders through the guarded sci door now
(the old infinite-loop special-casing comes free from N3), target =
agent picks ANY function returning hiccup, order is orchestrator's
pick (problems → block pages → canvas); LOOK: port the old design
language + named polish acceptance rows (blinking cursors, paste-
friendly input box, a chat display that's easy to follow). NOTE: the
first N4 drafting agent was STOPPED BY THE OWNER mid-draft — do not
resume it; a fresh N4 launch bakes these rulings in, pending owner
go. AGENT MESSAGING: the substrate is live-proven (escalation
messages open real runs); the missing 5% is the agent-facing
my.message hands — one small rung, queue with the gold order.
QUARRY GOLD INVENTORY IN (7f403bace: 155 files/75k lines censused,
36-row crosswalk): the remaining gold is the continuity/composition
layer — turn evidence + blobs, derived context, plans + memory,
collaboration, canvas/tools, schedules, Inspect integration;
pod/self-host/CLJS stays lead. OWNER GO + PERFORMANCE BAR (night): "no N=1 attempts. This shit has
to be fast. Like 60fps fast for very dynamic rendering" — the 16ms
frame budget under churn is a DESIGN INPUT measured by a committed
benchmark harness, never asserted; PORT the tailwind-CSS build
system from the quarry (standalone if it rode the dead CLJS build);
two named exercise goals prove the design: LIVE TOKEN COUNTS and
STREAMING TOKENS to the interface (exposes the seam: seon.ai is
one-shot — the streaming SSE path is a named seal-side revision
composing with the failover work, partials land on the no-history
churn attribute, counts derive from the stream). OWNER ARCHITECTURAL RULING (night, error rendering): consumers reach
an error's renderings ONLY through the one router — :seon.render/ai
on the unit, never a bespoke seon.error/ai-prose call site (ai-prose
demotes to the DEFAULT implementation a key points at). GENERIC
default renderers per output kind + SPECIALIZED renderers selected
WHERE THE UNIT IS BUILT from the fact's own attributes (computed,
never consumer-side conditionals) — first specialist: malli
validation failures render detailed problem identification from the
full explanation. Both in-flight lanes corrected; N4's block
contract names the generic+specialist selection as a reusable shape.
PROJECTION REVIEW IN (ead99eb98: verbatim outputs of every error
family + critique + before/after rewrite proposals in
research/projection-review-2026-07-28.md; five consumer-visible
issues — noisy failover context, unclassified transition prose, lost
instrumentation evidence, unstable sci object identities in
receipts, duplicate storm-limit messages; each fix is one
hot-reloadable projection defn once the owner approves the revision
list — OWNER CRITIQUE SESSION is the next step on this thread).
N4 PACKAGE 1 DRAFTED + SEALED (6dcda1ab9 + 4fa0c96f7; map in
research/n4-contracts-2026-07-27.md): block.edn family +
seon.render.{hiccup,block} + two sealed suites + bench harness +
bin/css. HEADLINE (measured): the old UI morphed the WHOLE PAGE —
admitting a 250-event page = 7.5ms p50 of the 16ms frame before
serialization; BLOCK-TARGETED morphs (interest/suppression rekeyed by
block) are the 60fps thesis. Generic+specialist = select +
:seon.render/selection ("the consumer never branches and the
specialist's name never leaves its producer"). Streaming MINED (current
contract: `llm-adapters.md` §“Request and response contract”): streaming is a
transport choice, complete partial snapshots ride the injected sink, and
streamed/non-streamed calls return the same completion shape; interactions
settled — response-started? observable at first chunk, mid-stream failure =
transmitted = no-retry unchanged. SEAL RULINGS: block
naming stands (ui.md accretes); durable html slot = qualified symbol
ONLY (kills the pr-str codec); top-level derived; JetBrains Mono gets
BUNDLED (silent fallback is a lie about the design); D6 Option B
(seon.ai reopens as the one prefix producer). Adoptions D1-D8
recorded in the contracts doc. FILED:
a-self-referential-schema-overflows-the-stack (schema owner's, not
N4's). SEQUENCING: problems html + anything touching
error.clj/problems.clj/loop.cljc WAITS for the error-system lane's
commit; kind declarations live in block.edn ONCE — reference never
re-declare; issues index regenerated by whoever finishes last;
package-2 deps.edn/config revisions (Datastar coords out of :host,
resources path) are ORCHESTRATOR-owned on the agent's signal.
FONT LICENSE VERIFIED (2026-07-29,
research/jetbrains-mono-license-verification-2026-07-29.md): the packaged
binary is JetBrains Mono 2.211 Regular, OS/2 weight 400, SIL OFL 1.1,
despite its `jetbrains-mono-500.woff2` filename. It is licensable, so no
swap decision is open. The packaging exit remains source-controlled
weights with honest names plus the accompanying OFL text and a release
inventory fence.
UNIVERSALITY AUDIT IN (ca0b5aa5d): the CONTRACT is universal,
production use is not yet — three filed: prompt assembly bypasses
the router (converges with the context-blocks work — the prompt IS
an ai render of blocks), stderr presentations bypass the log kind,
program-graph render declarations name ABSENT functions (N5's).
The coverage shopping list (which units grow ai/html/log renders, in
order) is in the report — feeds N4 block pages directly.
ERROR LANE LANDED (1c7abb6a7 — projections through the render
contract + approved conciseness; N4's fence lifted, N4 is now
last-finisher for the issues index).
**THE OVERNIGHT PROGRAM (owner asleep, rulings recorded):** morning
goal = ALL of (browsable UI / subagents messaging / proofs),
sequenced by READINESS — if rendering logic isn't solid, HOLD the UI
milestone and advance the others; solidity outranks demos. TOKENS:
unlimited DeepSeek; Muse sparingly if DeepSeek struggles; UNLIMITED
LOCAL — Qwen 3.6 35B A3B named ("particularly good at agentic
workflows"). Guardrails: defaults (path-limited commits, no history
changes, deps.edn only the named Datastar/resources promotion, ui.md
only via sealed-review commits, never data/clusters/default or
ACME). RECURSIVE RENDERING (task #11, owner): pages are folds over
the entity graph — unit refs render as units, bounded
(depth/nodes/visited — entity graphs cycle); "the /data browser is
ESPECIALLY that" (the get-in drill = the purest case, joins N4's
page set). Task #12: the audit's coverage shopping list.
N4 PACKAGE 1 GREEN (306/1248 at its commit): expand was UNBOUNDED —
fan-out WITHOUT cycles OOM'd the JVM at 22 blocks (per-path visited
refuses the wrong thing); now bounded by the SAME admission caps
(node+depth separate budgets, depth-first so elisions are stable for
equality suppression). Fused-walk experiment: NO — the 7.5ms
admission was a bad predicate; reorder+index → 0.012ms p50 (670×);
"an expensive-looking stage is a reason to READ the stage." Block
thesis on surviving numbers: 287B/0.004ms block morph vs
82,893B/0.460ms whole-page (289×/115×). Problems page = one key +
two functions (live-processes REFUSES a default — #{} invents
problems, assume-alive hides them). CSS = semantic classes
(agent-authored html makes utility soup unreviewable). ui.md
accreted ×3. Font = filed LICENSING issue (release artifacts ship an
unlicensed woff2, weight mismatch — remedy in the note).
LOCAL QWEN LIVE (ee133634e+cd613e30d,
research/local-provider-2026-07-28.md): Qwen3.6-35B-A3B-4bit-DWQ via
MLX at 127.0.0.1:8090, 42.6 tok/s, real turn trigger-to-close 2.6s,
dummy LOCAL_LLM_API_KEY=LOCAL (no-auth provider admission = morning
decision). DEPS PROMOTED (b8601fabe): Datastar SDK + http-kit
adapter on :deps (http-kit rides the adapter's 2.9.0-beta2),
resources/ on :paths. Two config reds mid-flight = the my.message
lane's in-progress dial (theirs; gate-green is their bar).
CONTEXT-BLOCKS PLAN IN (b15d3c418: quarry findings, pre/post-N5
boundaries, prompt-router convergence, measured cache-gradient,
EIGHT batched morning decisions) — falsification review lane running
before any seal. ARCHITECTURE DOCS CURRENT (a237c51a2:
observability/ui/agent-runtime updated with tonight's settled
contracts, one durable law added, unsettled items correctly
excluded). TEST-DESIGN REVIEW IN (55a28e8d7: 334 tests / 33 namespaces + six
benchmarks swept; ELEVEN ranked findings with dissolution designs,
collapsing tests named, a nine-unit implementation order —
constructions implement AFTER the morning read, phase 2 of task
#13). CONTEXT PLAN REVIEWED (9540875ad): NOT ready — six blockers (cap
propagation, prompt seal revision + trigger selection,
problems-block live-process input, measure-before-caching, decision
batch REDUCTION, deterministic oracles); revision lane running.
MEASURED HONESTY FIX visible from the my.message lane: my.run/wait's
docstring promised "the run resumes on a later wake" — measured
false (custody releases, the next pass CLOSES the run; the AGENT
resumes on its next trigger with a fresh ctx, and THE NOTE IS THE
ONLY CONTINUITY — a delegating agent must put everything its next
run needs into the note). CONTEXT PLAN REVISED (cfb75bef3): all review findings dispositioned;
the morning batch properly REDUCED — eight decisions shrunk to FOUR
genuine ones (block omission semantics, invocation shape, collision
precedence, capture ownership); ready for the morning ruling, then
contract drafting. N4 PACKAGE 2, THREE SLICES SEALED (4 commits): **thesis proven on a
REAL socket — one-block change = 1 patch/102 bytes; no-projection
change = 0/0; initial paint 2/218** (every row a sealed test against
real http-kit on ephemeral loopback). Router literal accretion
proved the durable/runtime split AT THE BRIDGE (widening the schema
to literals broke every block transaction — the schema IS the
durable side; declaration? admits both in code). REF-FOLLOWING =
entity-slot IS slot (one traversal); a pulled entity IS a unit
(stored renderer routes, absent falls to the generic default — /data
works with ZERO authoring); task #11's falsifier SEALED over real
facts incl. the run↔form cycle. Web layer: server/routes/shell/
listen!-repaint/byte-comparing suppression/latest-wins/coalescing
floor dial. seon.render.web/not-yet ENUMERATES undone-by-design in
code (interest matching, shared registration, per-tab graph,
isolated sink — each designed, none open). Its suites 93/245/0;
full-gate reds = my.message's mid-flight turn_test (722adb18e).
**SUBAGENTS ARE LIVE — my.message COMPLETE (722adb18e..dc5efd7cc,
gate 368/1438/0 verified).** send is a VALUE (third agent-facing
shape reached through the first — the loop commits the message in
the SAME terminal tx as the form's receipt; delivery = the wake;
11 lines + a driver rule vs the quarry's 590-line effectful
message!). Composition: the fold reads EVERY form's value — send in
one form, complete in another; fan-out = a vector. The chain bound
is DERIVED (tx-meta caused-by walks; human messages terminate free;
dial max-chain 16 as backstop) — the stored hops integer and its
deadlock-prone reset rule DISSOLVED. Live proof (3 DeepSeek drives,
research/my-message-proof-2026-07-28.md): alice→bob→reply→alice
completed 166833 — a number none of HER forms computed — 8.2s, zero
error facts. Two protocol rules derived from failed drives: a
completed run's result delivers back to the trigger's sender; A
REPLY IS NOT A QUESTION (ours-by-caused-by = the real terminator).
BLOCKER FOUND+FIXED: my.run/wait LIVELOCKED the loop (close derived
for an unheld run, refuse, self-rewake — the old test was green
BECAUSE every close refused; archived with live proof). Filed:
failed-form-doesn't-stop-the-fold (bob answered with an unbound-var
string in perfect confidence — three candidate rulings in the note);
my.run error values omit :seon.error/kind. SIX MORNING QUESTIONS in
the proof doc §7 (bound currency, refusal visibility to sender, wait
semantics, self-messaging keep/refuse, verbatim replies, Math/sqrt
absent from base — N5's surface decision). STREAMING PROVEN LIVE (gate 385/1493/0 verified): both owner
exercises in a real browser — 18 counter morphs climbing, text
growing in place; 46 tokens → 18 morphs IS the coalescing design
(presentation lags/drops, the producer never waits). seon.ai owns
the wire, seon.ai.stream owns the database, one function between;
the sink does NO work on the provider thread (2000-call hammer
test); streamed and one-shot calls return the SAME completion shape
(failover cannot tell). ofInputStream not ofLines. Suite needs no
network (canned SSE over real http-kit). /DATA DRILL LIVE: windowed
paging ("1-6 of 130"), URL = state (a drilled position is a
sendable link), breadcrumbs = the path's prefixes,
pay-only-for-what-is-opened as a TIMED sealed property, paging
GENERATIVE (each entry exactly once), drilled pages carry NO feed
(repaint moves the reader's ground). LOCAL QWEN RESTARTED by the
orchestrator (the handoff PID died; command from
local-provider-2026-07-28.md; serving on :8090 again).
ROOT PAGE LANDED WITH ONE HONEST BLOCKER (gate 385/1497/0): start!
stacks the web layer as the last tower rung — a plain bin/repl
serves the root view, port dial defaults 0 (URL reported never
assumed), seon.render.root = four render fns + a vector (no root
route/template/branch anywhere), agents link to /data drills, bind
failure throws like any layer. **THE BLOCKER IS DEAD — a real DATAHIKE QUERY-PLANNER BUG in our
fork, fixed falsifier-first on both sides (fork 9a7a9ef1 + Seon
22440b5ca; gate 386/1502/0 VERIFIED).** Root cause: build-pipeline's
fused :sorted-merge path asked cardinality only of the MERGE ops —
a card-many attribute in the SCAN position emits N datoms with one
e, the forward cursor passes the key after the first, rows silently
drop. Costing put the card-many pattern in the scan exactly when the
store was big enough (why no fixture ever saw it — small stores plan
differently), and plan selection varying with variable-symbol hash
on cost ties is what made it read as probabilistic (filed, with two
more planner/cache defects, in
datahike-planner-and-caches-carry-three-smaller-defects.md). The
Seon-side regression BOOTS a real cluster (derivation == relation
find == raw :eavt). Ten boots: four blocks through every read — THE
MORNING DEMO IS WHOLE. Also filed from the boot loops:
cluster-stop-races-an-in-flight-transact. "Datahike is PART of
Seon" earned its keep tonight — the fix is ours, tested in the
fork's own suite, CHANGELOG'd per its convention.
The N4 agent stopped for the night; package 5 (agent-transcript
page) waits for morning rulings. LAST LANE OF THE NIGHT: the
stop-vs-in-flight-transact race is FIXED the event-driven way
(852ef9759 + archive 2d5a4f09b): the loop PUBLISHES its completion,
stop! awaits it before releasing the connection; fault-committer
shutdown awaits active commits and stops treating terminal-channel
nil as a core fault; no sleeps, no SPI changes; kill -9 keeps crash
semantics. **FINAL NIGHT GATE: 388/1509/0 VERIFIED.**
**MORNING RULINGS (owner, 2026-07-28): THE USAGE RUNG.** Ports:
derived-from-cluster-name hash into a fixed range (bookmarkable,
restart-stable; collision → ephemeral + loud report; manifest
override wins); names: unspecified → "default", else the given name,
throwaways generated. Architecture confirmed: one JVM = one store +
two executors; PER-CLUSTER web server stays (isolation is
load-bearing); the HOME CLUSTER (stable port, root page = blocks
over the advertisement inventory — every cluster listed/linked with
liveness + problems rollup) is the single front door, a named later
package. Operator v1 (babashka, ads as the ONLY truth, old bb
operator as quarry, lands as bin/seon-fresh):
start/status/open/stop/logs. Boot UX: full readiness banner (URL,
agents, blocks, problems, instrumented count, boot time).
**FLEET DIRECTION (owner): spin up as many clusters as needed — the
owner wants MANY clusters on the same problem independently
(experiments).** start takes multiple names + --count N (one JVM,
N instances); a seed command transacts the same trigger into each;
status readable at 40 clusters; funsearch/openevolve in
reference-code are quarry for the evolution harness later.
IN FLIGHT (two): the N4 agent (derived ports + URL-into-
advertisement + boot banner + home-cluster design note); the
fresh-operator sol lane (bin/seon-fresh with fleet semantics —
NOTE: a resume with backticks got eaten by shell substitution once;
single-quote resume payloads, scar recorded). MORNING BATCH accumulating:
context-blocks decisions (8, post-review), no-auth provider
admission, font licensing remedy, N4/N5 decision batches,
my.message's conversation-loop dial, R41-recorder carve-out
confirmation, projection-review outputs re-read. ORCHESTRATOR QUEUE (my successor
runs): review each return; two-cluster proof + failover live drive
(recipes in research/scripts/); context-blocks plan → independent
falsification → morning decision batch for the owner; the
released-connection family; gate verify after N4 lands. NEXT QUEUE: two-cluster proof (+
failover live drive rides it); released-connection family;
dispatcher lands post-measurement; my.message rung; then the gold
order (turn evidence + blobs, derived context, plans + memory,
collaboration, schedules, Inspect). THEN: two-cluster proof; projection-review experiment;
steps 3-5. Earlier step-1 note follows:
IN FLIGHT: the same agent applies authorized seal revisions
(:seon.config.fault/*→error dial rename across five files; admit
record {:optional true}; ui.md accretion drafted as a SEPARATE commit
for isolated review) then implements step 1 to full-gate 0/0.
OWNER DIRECTIONS (late night): FAIL LOUD ≠ FALL DOWN ruling recorded
in README (c174607ad — dev :panic halts the ACTIVITY, never the
tower/REPL/UI; dig-into-it is the point). ITERATE THE PROJECTIONS:
after step 1 greens, run a projection-review experiment — generate
real errors of every family, render every projection, put actual
outputs in front of the owner to critique and refine (hot-reloadable
defns behind symbol routing = free iteration). SIZE DISCIPLINE
confirmed: all fact data through the admit codec (bounded by
construction); stack traces NOT in the step-1 fact — D2's owner lands
a bounded frame projection, full traces go to blob storage with a ref
(three-tier rule); recurrence signature prevents crash-loop
duplication. My error-consumption surfaces: problems via REPL (task
#3 MCP restart unblocks live eval_clj), log-projection files, drives
print problems on failure, N4 html problems surface. The
orchestrator seals on its return. OWNER DIRECTION (night, routing):
the router's delivery substrate is COMMITTED FACTS + listen!
attribute interest (the wake mechanism generalized — subscribe to the
projection-key attribute set; any committed entity carrying one
routes; never a second channel, processes commit what they want
routed). Keys are the ROUTING authority (deterministic, no-kinds
rule); malli schema-matching (filter registered-schemas by
valid-candidate-value? — multiple matches always possible since open
shapes subsume) is a DIAGNOSTIC surface for inspector/steering, never
routing. Remaining order after step 1: (1) seon.error normalizer + totality property
and ai/log projections + fault.edn→error family rename; (2)
seon.error/commit! + boot wiring as a cluster.clj revision (D4);
(3) seon.problems; (4) seon.instrument (near-mechanical from the
measurements); (5) seon.ai failover rows + disposition reducer +
ai-projection notice. D1/D3 land WITH the error owner that consumes
the discarded values, not before. I seal each on return.
OWNER DIRECTION (night, loved the projections concept): GENERALIZE —
any entity/map may carry projection keys (output-kind → FULLY
QUALIFIED SYMBOL naming the projection fn); one router resolves
(requiring-resolve, var-backed for hot reload — the proven
populate/evaluate/predicate-owner idiom) and applies. The render
contract's ai+html pair becomes the special case; new output kinds
(log, sms, metrics…) are accretion — add a key, write the fn, no
router change. Seal note: ui.md currently fixes exactly two
projections — admitting the open kind set is a deliberate contract
accretion to write into ui.md at seal; each kind names its consumer.
N4 PLAN REVISED (ab2911caa): all 25 review findings dispositioned,
composed with the landed N3 owners, socket/reset/child-loss proofs
moved in-suite, EIGHT owner decisions consolidated — both N4 and N5
plans are now review-hardened and wait only on their owner-decision
batches. IN FLIGHT (two lanes): the Opus agent builds the seon.sci.eval
adoption — C7's evaluator owner, the LAST dependency before the N3
integration proof (never throws, deadline the only limit, admission
inside the armed boundary; proof = turn_test's injected symbol points
at the REAL evaluator, driving a real sci eval end to end with no
model call); digest-unify gives the triplicated SHA-256 helper one
owner (byte-identical proof required). NEXT SESSION'S SPINE, in order: (1) the N3
integration proof, orchestrator-owned — bin/repl or clojure -M:dev,
start! a cluster, install a live flow graph with the loop proc, drive
one REAL turn (one live DeepSeek call proving request/response
shapes), then kill -9 the child and prove interrupted+adapt; (2) the
seon.sci.eval adoption rung (the evaluator symbol injects into
turn/step untouched); (3) rule the N4/N5 owner-decision batches +
R41-vs-marker + [:maybe]-in-fn-returns with Sean; (4) digest SHA-256
helper triplication (ancestor/config/loop) wants one owner; (5) task
#3 MCP verify (needs Sean's restart). The Opus drafting agent
(SendMessage name a96513c593d3a6a83) holds the full N3 context. n5-plan 909394481 awaits deep review at its
rung; GC verdict recorded above. Its accepted design: process-local
store holder + refcount under ONE lock with running-instances;
stop! also releases branch connection, last instance releases the
store (stop!'s docstring needs that revision — seal owner's). Its
open risk: digest roots default ["src"] refuses on source-less
deployments (answer at the publish build). ALSO STANDING: stop!
docstring revision; the GC-cost experiment; issue
process-liveness-check-has-no-single-owner.

**B2 COMPLETE (2026-07-27 late): FULL GATE 85/345/0.** Every package
sealed AND green: config+reconcile (18a27e816, converged=zero-writes
proven by :max-tx), fork machinery (a35c95d0a — ancestor rename-at-end
builds, registry as the one branch-lifecycle owner, export with the
loud create+re-transact fallback proven by hand), provenance attrs,
THE defaults document with the computed concurrency default. Sealed
falsifiers cover fork+isolation+GC-survival in-suite against real
:file stores. NEXT UNIT: the boot COMPOSITION (task #9) — start!
threads resolve-bootstrap → open-store! → ancestor/ensure! →
ensure-cluster! → open-branch! → config/apply! → advertisement; B0
contract revision by the author, then a lane; falsifier = the full
tower via bin/repl <10s + near-instant second cluster + kill -9 reboot.
Then N3 from its reviewed plan (value-admission package first).
Open experiment owed: GC/retire cost over ten warm clusters — config→
facts (pure plan before transact; empty plan = NO transaction; :max-tx
unchanged = converged), schema-EDN loader + one admission gate,
branch-per-cluster (open-branch!, ancestor genesis, the ~15-line fork
roster fix WRITTEN FALSIFIER-FIRST, issue
datahike-branch-roster-read-modify-write-race), plus the B0/B1 author
revisions the verdict requires (store moves to the process root,
cluster-paths drops store-dir). Then implementation lanes (A/B: sol +
Opus both proven; effort medium default). Then N3 (value-admission gate
as its own small package first — owner-scoped: force + size-cap at the
choke point; allocation = O4 watermark), N4 behind it, N5 after B2.

WORKFLOW (proven today, keep the cadence): planning agent per rung →
orchestrator fixes plan → sealed contracts → implementation lane →
friction stops are usually AUTHOR defects (5 of 5 today) — fix the
defect, never relax the bar → quality-review lane at every rung
boundary (#2 in flight covering B0/B1) → Gemini hook reviews run
per-edit with 4 skills dynamically loaded. Owner wants rulings via
AskUserQuestion in PLAIN LANGUAGE with options. MCP eval_clj reaches
fresh instances after a client restart (bin/repl starts one).

Owner instruction, 2026-07-26: *"I feel like we are close to representing
everything witht he same primitves and composing them together but we aren't
there yet and be honest about what isn't done."*

This file is that honesty. [README.md](README.md) says what to do; this says
what we do not yet know. Nothing here is a task list — a row graduates out of
this file either into a plan step or into a `docs/seon/issues/` note, and a row
that stays vague is a row nobody has thought about hard enough yet.

Three categories, and the distinction matters:

- **UNDECIDED** — the owner must rule; we can state the trade but not resolve it.
- **UNKNOWN** — nobody has the evidence yet; there is a named experiment.
- **UNBUILT** — decided and understood, simply not done. These live in
  [README.md](README.md)'s steps and appear here only where the gap is bigger
  than the step admits.

## 1. UNDECIDED — needs an owner ruling

**Empty as of 2026-07-26 PM.** All four standing rulings landed the same day
— see README's "Rulings 2026-07-26 PM" section for the full text:

- **O14 dissolved**: web-render merges into the cluster JVM, so nothing
  rendered is stored — the commit-vs-derive debate was about *transport*,
  and same-memory serving removes the transport.
- **O4**: diagnostic + a process-heap watermark at heartbeat cadence; spikes
  remain the process boundary.
- **O2**: clusters never share a store; second open refuses via one `flock`
  assert.
- **Flow**: the non-adoption recommendation was REVERSED — adopted, Path A,
  `seon.flow` implements `flow.spi`, flow-monitor is the ops surface.

**Resolved 2026-07-26, recorded so it is not reopened.** Integrant is adopted
**narrowly and conditionally** (`bd8038419`): only when writer, driver and
web-render merge into one JVM, and only if that merge deletes the ~360 lines of
standalone lifecycle scaffolding it identifies. The operator's OS-process graph
stays separate — an OS process cannot be an `init-key` value. Shape: one root
system containing one nested Integrant system per cluster, so a single-cluster
reset halts only its own nested system. Strongest borrow from the archive: the
single derived `ig/assert-key :seon/component` Malli-validation choke point.
`suspend-key!`/`resume-key` are **rejected** until measurement proves a specific
restart resource is too slow. Biggest risk, and an acceptance condition rather
than a preference: a flat `refset` edge would make one cluster's halt traverse
shared resources and take down every cluster.

## 2. UNKNOWN — needs evidence, with the experiment named

**Answered 2026-07-26 (evidence in `research/`, kept here one line each):**
boot after the door deletion = **2,794 ms** mean artifact boot
(`boot-remeasure-2026-07-26.md`); the pod cut **does** lose needed coverage,
differentiated — 34 namespaces need fresh JVM invariants, 23 delete clean,
36 already covered (`pod-test-coverage-2026-07-26.md`); the three
`::calls` discard sites are **closed**, unknown targets fail toward
not-pure/not-`:compute` (`62bc86cb1`); the double-send experiment is
**unreachable until step 1's messaging binding exists** — the lifecycle
reply path commits message+receipt+closure atomically, so the reachable
window opens only with agent-authored sends; idempotency stays UNPROVEN and
is a step-1 acceptance item (`double-send-experiment-2026-07-26.md`). The
semaphore-replacement and three-turtles questions are now the flow
testbed's scenario matrix (`flow-testbed-2026-07-26.md`, in flight).

- **~~Does the submission-channel design replace the semaphore?~~ ANSWERED
  2026-07-26** (`3564882a3`): the semaphore is **deleted, not kept beside the
  channel** — `open!`/`available`/`permits` go; its queueing job becomes the
  channel's fixed buffer and its concurrency job becomes the launcher's slot
  count, both per-class config facts, with nothing outside the launcher able to
  acquire capacity. The launcher is one loop parked in `alts!!` over the three
  class channels. What remains UNKNOWN is only the measurement below. A
  bounded channel bounds the *queue* and parks puts (`async.clj:113-117`: "When
  full, puts will block/park"). It does **not** bound parallelism — the
  executor does. So `seon.sci.eval`'s semaphore is doing two jobs, and the
  replacement is (bounded channel = backpressure) + (bounded `:compute`
  executor = parallelism). Unverified: whether `:seon.eval/available`'s
  accounting survives that split, and what `newCachedThreadPool`'s removal does
  to the measured "a wedged eval degrades capacity by exactly one" property.
  **Experiment:** wedge N evals under the channel design and confirm capacity
  degrades by exactly N and a query still names the wedged step.
- **Agent messaging must be adapted, and the target shape is not settled.**
  Owner, 2026-07-26: *"we likely need to adapt agent messaing."* What is
  established: delivery is already pure derivation with no read/ack flag
  (`waking-inbound?`); the turn boundary is the take, so a message can never
  preempt a running eval; and the wake attribute must stay disjoint from
  attributes the wake path itself commits. What is **not** established: whether
  message identity derived from the sending receipt `(run, ordinal, epoch)` is
  sufficient to make delivery idempotent under re-execution, and what happens to
  a message whose sending form re-executes after a crash. Today's reply message
  takes a *freshly allocated* id, so re-execution can double-send. **Experiment:**
  kill a process after a send commits but before the run closes, and observe
  whether the recipient receives one message or two.
- **Whether the three turtles are genuinely one mechanism.** N cluster-writer
  flows, M agent drives, and every function call inside a turn are supposed to
  share one dispatch substrate. Partly verified: Datahike's writer is already a
  two-stage core.async pipeline, and on this JDK `go` expands to
  `(thread-call … :io)` (`async.clj:528-529`), so the database already rides
  `executor-for :io`. **Not** verified: that agent evals and function calls can
  join it without a second scheduler, and what the honest seam is. The
  scheduling design claims exactly one seam — agent interpreted code
  additionally carries `:interrupt-fn` + platform thread + permit, switched on by
  computed provenance. That claim has not been built or measured.
- **Whether workload can be derived soundly at all.** The derivation depends on
  `:seon.program.edge/calls`, which has three measured discard sites — a
  higher-order caller is a **silent false negative**, so `(map my-blocking-fn xs)`
  records only `clojure.core/map`. Until those close, any derived workload is
  wrong in the one direction that wedges a `:compute` thread. **Experiment:** fix
  the three sites, then assert that a call graph reaching a capability edge is
  never classified pure.
- **JVM boot after the door deletion.** The last pair is 10,293 → 3,886 ms
  (`-Xmx2g`, JDK 26.0.1, AOT 92.7% / AppCDS 7.3%). The residual was 63% three
  non-AOT namespaces including `seon.host.context` at ~900 ms — **which is now
  deleted**. Unmeasured. **Experiment:** re-run the boot breakdown at the same
  flags.
- **Whether the pod cut loses coverage we need.** It removes 98 CLJS test
  namespaces / 1,080 `deftest`s plus the CLJS branches of 24 `.cljc` namespaces.
  `bin/test-writer` must claim that ground, and nobody has enumerated which of
  those 1,080 assert a *surviving* mechanism versus a deleted one.

## 3. UNBUILT — understood, not done, and bigger than its step admits

- **An agent cannot act at all.** Its entire callable surface is
  `clojure.core`, `clojure.string`, and five `seon.agent.lifecycle` vars. No db,
  blob, fs, shell, web, messaging or LLM. Every demo, every load test and every
  proof of the design flows through the door that does not exist yet.
- **UPDATE 2026-07-26 PM: the gate is GREEN — 551 tests / 3,881 assertions,
  0 failures, 0 errors** (`tmp/plan-evidence/vector-order-test-writer-full-2026-07-26.log`).
  The registration fix, the frozen-prompt fixture fix, and the
  ordered-collection reshape (5 sets, 2 positions) landed same-day; the
  original note below is history.
- **The JVM gate is RESTORED and RED** *(2026-07-26 — see the 2026-07-26 gate evidence (state.md deleted 2026-07-27),
  regenerated from the retained log)*. **544 tests / 3,676 assertions, 3 failures,
  1 error.** All four are named and filed. Restoring it paid immediately: six
  stored attributes declaring ordered collections had been invisible for as long
  as the runner was broken. And a hypothesis worth testing before anything else —
  the one invalid registration
  ([[../../../seon/issues/sci-eval-evaluation-schema-does-not-resolve-its-predicate]])
  may be causing 3 of the 4 failures, which would put the suite one fix from
  green. Original note follows. `bin/test-writer` needs the compiled artifact. The
  freeze rebuilt it and **`writer` and `host` both reached ready** — live proof
  that the 58-line replacement `seon.host` main boots, which closes one of the
  owed proofs. The **pod** failed readiness on a release-digest mismatch
  (`this cluster was applied at release 596b6c1d; this artifact is dbdb10f7`,
  remedy `bin/seon cluster apply default`), which is expected: the pod is on the
  deletion list and the startgate is doing its job. Verify against the live tree for
  the suite count rather than trusting this bullet.
- **The wire is still on the agent path.** `seon.db.host/writer-session` opens a
  UDS session to a separate `writer` process, so every agent read and write
  crosses a socket — measured at 6-7 writer round-trips for one form containing
  one write. O1's co-location is the target, not the state.
- **Two blockers filed today that no step yet owns end to end.** A run opened
  before its plan commits is unrecoverable by either recovery query, in the
  window holding 78.5% of a turn
  ([[../../../seon/issues/run-is-unrecoverable-before-its-plan-commits]]); and
  agent-to-agent messages never wake anyone because the wake query requires
  `:origin :human`
  ([[../../../seon/issues/agent-messages-never-wake-the-jvm-driver]]).
- **The corpus round trip is broken in three places at once**: nothing writes
  `:seon.fn`/`:seon.ns`/`:seon.schema`, boot installs no corpus, and a `defn` in
  form 1 is invisible to form 2. Note the correction: `:load-fn` alone cannot
  resolve a bare same-namespace symbol, so this is not "add a `:load-fn`".

## 4. Where the primitives do not yet compose — the honest core of this file

The owner's read is that we are close to one set of primitives that compose.
That is true in four places and not yet true in three, and the three are worth
more attention than the four:

**Composing already.** A read is a pointer into a database value at a basis. A
change is a transaction whose report gives the next basis. Custody is CAS +
epoch + lease facts. Delivery, wake and render all derive from facts through one
predicate each, with no stored flag.

**Not yet composing:**

1. **Scheduling is not one mechanism — it is four expressions of one idea.** The
   eval bound is a `Semaphore`; the transaction bound is a Datahike queue size
   we never set; run admission and capability calls have **no** bound at all.
   The design says one bounded submission channel per workload class. Until that
   lands, "backpressure" is a property of one path and an absence in two.
2. **The corpus is a fact store without a resolver.** Code is committed as facts
   and nothing loads it back. So "code is data" is currently half a primitive —
   the write half. Until acquisition materializes a namespace from facts at a
   basis, the corpus composes with nothing.
3. **Containment has one hole that is not a policy choice.** A lazy value leaves
   the armed boundary unrealized and is realized later with no `:interrupt-fn`.
   Until realization happens inside the boundary at one choke point, "everything
   leaving is bounded" is aspiration, not a primitive.

The pattern in all three: **the write side of a primitive exists and the read
side does not.** Facts are committed but not resolved; work is scheduled but not
bounded; values are produced but not admitted. That is a more useful way to hold
the remaining work than a step list, and it is why the plan's step 1, 3 and 4
are ordered the way they are.

## 5. Things believed true that were wrong within a day — read before trusting a row

Recorded because the failure mode is systematic, not incidental:

- Six assumptions were tested in the previous session and **six were wrong**.
- Four of six defects in one plan row were already fixed at HEAD; the row was
  written from a document one day old.
- The plan's own `file:line` anchors went stale in a day because the plan's own
  work moved them. Prefer symbols.
- A "~1,160 zero-caller lines" deletion claim was false for two of its three
  units.
- Multi-agent messaging was assumed working by the ledger, the capability index
  and the plan. It is not, and it is a one-line query filter.

**So: re-grep a row's evidence before acting on it.** Every claim in this file
was verified on 2026-07-26 and may already be stale.
