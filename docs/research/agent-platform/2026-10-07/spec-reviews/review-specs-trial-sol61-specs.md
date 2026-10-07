# Design review — trial-sol61-specs, 2026-10-07

Both directions are right, but neither spec is ready for its next implementation slice. Errors S1 held. The turn-free fold held in the implementing lane's probe. The errors sweep crosses dependencies deferred to S3–S6; the loop's proposed writer is not the existing writer with a different reference. Several proposed deletions remove duties or dependencies still required by the target.

Read-only review of HEAD `33fb994f2`, with source excerpts from the checkout. No MCP, tests, lint, contract compilation, provider calls, or JVM operations performed. Runtime installation, bootability, timings, memory, and the lane's numerical inventories are **unverified here**. The inventories and probe results are attributed to their notes, not independently reproduced. Existing foreign changes include `test/my/examples_test.clj`, `tmp/test-data-assertions/proofs.edn`, and the dirty `reference-code/http-kit` submodule; none was touched or used as an excuse to stop. This report is the only file written.

## Errors

Fine: the required data type key, one constructor, two carriers, per-returning-function `:multi`, Malli's report hook, refusal-only spelling suggestions, and a writer-serialized counted fault are good choices (`errors.md:16–18,51–71,169–188,296–303`). Do not restore metadata typing, steward/escalation, folded repeats, or branch-to-cluster copies. Datahike really does pass the mid-transaction database to `:db.fn/call` (`reference-code/datahike/src/datahike/db/transaction.cljc:1172–1173`); no new lock or occurrence committer is needed.

### E1 — HIGH: S2 cannot land independently of its future consumers

Spec: `docs/seon/specs/errors.md:124–126,259–267`. Lane evidence: `tmp/errors/s1-s2-notes.md:37–57`.

The 52 throwable-bearing diagnostics need the boundary conversion; the 120 literal maps need explicit producer types; dropping `at/layer` breaks recognition, fault storage, and signatures. Source confirms all three: `src/seon/error/refusal.clj:692–710,717–722`; `src/seon/error.clj:171–180`; `resources/seon/schemas/seon.error.edn:121–127,300–311`. “Take the type from the output alternative” is not an algorithm for a generic base or a multi-alternative function. A suffix search for `-error` is not proof that each registry entry is a concrete variant either.

This is primarily a bad slice boundary, not a reason to reject the target map. Move consumers and schemas before removing old production fields. Inventory producers by actual branch/semantics, including literals and throwable paths; name a concrete variant at each detection site. Boundary catch-all remains the base, per decision 3; it must not become a guessed producer union.

**Edit:** §7 S2 → replace “sweep 225 + 14; require type; drop at/layer” with “prepare type-aware readers and recorder first; convert inventoried producer families with their contracts and catches; require type/message only after all producers move; remove legacy at/layer production after the recorder and recognition paths no longer consume them.” Keep final maps free of `at`, as decision 6 requires.

### E2 — HIGH: the claimed validation of thrown variants does not exist

Spec: `errors.md:55–71,93–113,162–165,298`. The constructor returns the base; a throw skips the producer's output validator. The boundary's base contract deliberately cannot validate an arbitrary variant's required members. Malli output validation runs only after `(apply f args)` returns (`reference-code/malli/src/malli/core.cljc:2236–2240`). Thus the justification at lines 105–107 is false: `(fail! :my.fs/not-found-error ... {})` can cross a base-only boundary without the required path being checked. A direct variant schema also lacks a literal constraint tying its type to its name.

Keep the owner’s base-only boundary and exact returning unions. Validate the thrown error against its declared variant at the throwing carrier, using the operation's projection and Malli's cached explainer, without another registry. A malformed thrown error is a producer defect delivered with the attempted data, not a silently accepted map. The constructor itself need not duplicate returning output validation. Also make `error?` total: `(contains? v ...)` alone throws on ordinary scalar values, unlike today's map-guarded predicate (`src/seon/error/refusal.clj:717–722`).

**Edit:** §1 Variations → replace the variant example with base plus `[:seon.error/type [:= :my.fs/not-found-error]]` and its members. §2 validation paragraph → replace “boundary reply contract checks the thrown one” with “`fail!` checks the named variant before throwing; boundaries accept the base.” §4 → replace `contains?` alone with a map-guarded type-key test. Declare complete contracts for `fail!` and its failure conversion, omitted from the sample.

### E3 — HIGH: `caught` loses required evidence and deletes its own dependencies

Spec: `errors.md:73–84,130–158,261–262`. Clojure's `Throwable->map` has only the root's full trace, no suppressed links, and no cycle check (`reference-code/clojure/src/clj/clojure/core_print.clj:473–501`). A legal two-object cause cycle never finishes its loop. Seon already has a cycle-safe primary walk (`src/seon/error/refusal.clj:181–205`) and an existing regression preserving cleanup failures (`test/seon/error/refusal_test.clj:218`). Suppressed failures are therefore not an unproven hypothetical, and appending only the root's suppressed links loses suppression on outer causes and their causal descendants.

The retained `producer` depends on `root-frame`, `cause-links`, `authored-function?`, `authored-namespace?`, and contract-refusal's old keys (`src/seon/error/refusal.clj:562–637`). S3 deletes some of those dependencies. The sample additionally passes a frame vector to `frame-function`, which takes its class symbol (`:143–159`), and returns native `:via` links where the installed chain requires `:seon.error/throwable-class` (`resources/seon/schemas/seon.error.edn:269–283`).

**Edit:** §1 Cause chain and §3 → replace “one root-suppressed line if a test needs it” and the unconditional `Throwable->map` sample with “one cycle-safe cause/suppression traversal, native link data with referenced ex-data, preserving suppression relationships; root-first authored-frame selection with enclosing-frame fallback; declared native-link schema.” §7 S3 → replace blanket helper deletion with deletion after attribution's dependencies are converted. Reuse Clojure's frame conversion and native shapes; retain only traversal and attribution that Clojure does not supply. No second boundary normalizer.

### E4 — HIGH: the proposed report adapter mishandles three Malli reports and loses blame

Spec: `errors.md:169–182,207–212`. `invalid-guard` supplies `guard` and `[args value]`; `invalid-schema-at-call` supplies `arm`, that arm's schema, and the exception; multi-arity misses can supply no `input` (`reference-code/malli/src/malli/core.cljc:2219–2225,2233–2240,2308–2315`). Treating every non-output report as input/args either explains the wrong value, explains nil, or reruns the validator that just threw. The sample drops that original exception entirely.

“Producer/attributed stays” is insufficient: it recognizes `:seon.instrument/fn`, `arm`, and `digest`, while the replacement report supplies only operation and caller (`src/seon/error/refusal.clj:562–570,618–636`). Input blame can fall onto instrument machinery; output blame loses the digest that actually ran. Finally, `pr-str (me/humanize ...)` creates unbounded presentation inside a producer, contrary to the one-printer law. Malli already supplies structured messages (`reference-code/malli/src/malli/error.cljc:326–390`); another sentence grammar is unnecessary.

**Edit:** §5 report sample → replace the binary schema/value selection with a `case` over input, output, guard, arity, and schema-at-call. Explain only the relevant valid schema, retain report arities as Malli supplies them, and preserve schema-at-call's exception through `caught` without re-explaining it. Carry refusing function, actual armed digest, and caller as declared data, and update the blame rule in the same slice. Replace the `pr-str` headline with a short message; render structured problems at the value-printer edge. Preserve the existing reporter-failure boundary so a failure building suggestions is not relabeled as the original contract violation.

### E5 — HIGH: the new fault row does not support its required resolution/delivery semantics

Spec: `errors.md:216–237,301–302`. “Resolution stays” cannot be literal reuse: current resolution reads occurrence children and task proof, and permits a changed digest alone when no task exists (`src/seon/error.clj:1182–1219`). The target removes children but requires digest plus a green run. The deletion span also contains automatic producer-task creation (`src/seon/error.clj:1270–1289,1482–1484`); a message alone leaves no linked task for proof, contrary to `CLAUDE.md:88–91`. First-blob content must stay first content, while latest producer digest and recurrence time must track the latest failure.

The sample signature calls the 12-character default of `id/id`, but the signature schema requires 64 (`src/seon/id.clj:95–112`; `resources/seon/schemas/seon.error.edn:268`). Existing fault identities/readers also need a migration decision. There is no errors-spec ruling authorizing an independent reset. Removing cluster copies is binding, but requires delivery/task lookup to operate in the failing branch; root delivery cannot assume the cluster copy still exists.

**Edit:** §6 → replace “resolution stays if Q5 keeps openings” with “resolution and opening selection read the new latest digest/time and current positive test proof; task association remains in the fault transaction; unchanged/digest-only proof never resolves a surviving producer.” Specify deletion handling separately. Replace signature construction with explicit length 64 and name the discriminants for generic exceptions. Specify pure writer selection of recipient/message identity, count and opening, plus blob publication before acknowledgement. Add the branch-local/root-delivery case and the existing-data transition to §7; retire old row declarations only after readers move and root's authorized data reset/retirement occurs.

### E6 — HIGH: changing carriers needs producer control-flow conversion, not just deleting rethrows

Spec: `errors.md:110–119,263–265`. The filesystem example returns an error from a helper’s detection site but leaves the helper continuing into `.relativize` with no root (`src/seon/fs/jvm.clj:108–115`). Returning works only where the whole call chain propagates that value; the prescribed throw carrier is simpler for this helper. Catching only IO exceptions at the top would then miss those deliberately thrown maps.

Likewise, DB errors are not confined to the listed catches: `q` returns failures from alignment, projection and query-value validation (`src/seon/db.clj:2786–2838`). Deleting the catch and narrowing its output does not convert those branches into throws. `:seon.schema/value` can still admit an error map, so the output contract alone cannot prove migration. Removing read catches must also preserve failed-read evidence currently appended at `:2835–2837`.

**Edit:** §2 examples 1–3 and §7 S4 → replace “return at fs detection sites / delete DB helpers and catches” with “deep helpers `fail!`; each answering tool converts its own declared throws once and handles its declared host exceptions; migrate every explicit DB refusal return to the throw carrier while preserving read-evidence capture; remove caller rethrows only after that producer conversion.” Partition by public API and all reaching callers/contracts, not by error syntax.

### E7 — MEDIUM: the reference projection is underspecified and conflicts with later attribution

Spec: `errors.md:80–84,96–107,139–144`. “Nothing else in the map can hold a world” is false for problems' offending values and arbitrary cause data. Constructor input data is referenced, but `caught` then merges unprojected deepest ex-data and later attribution. Existing `references` explicitly does not enter sequences or records (`src/seon/error/refusal.clj:350–405`); it is not a general guarantee that arbitrary native Malli data or throwable ex-data contains no retained world. Nor does class-and-size evidence provide the retrievable blob required for a large value (`CLAUDE.md:256–265`, failure data law).

**Edit:** §1 References → replace the claimed whole-map guarantee with a declared evidence projection: complete final map, problem values, and every cause link; identity-only worlds use their projectors, large retained evidence uses one blob reference, places retain only class/identity. Run this once at each actual error boundary, after attribution, and declare the projected variant member shapes. Keep full evidence separate from bounded displayed output; do not rebuild the current irretrievable evidence clipping subsystem.

### Corrected errors slice order

1. **S1 already landed:** optional type key plus readers. Keep its proof; update “Today absent” at `errors.md:43` and metadata claims at `:204–205` to the actual S1 state.
2. **Compatibility/consumer slice:** prepare type recognition, new chain/report variant schemas and attribution inputs, and a recorder capable of taking final maps while the old producers still run. Keep old APIs operational; no new public constructor competing with the old one.
3. **Constructor/carrier conversion by producer family:** change its signature and all direct callers atomically; migrate throwable-bearing callers with `caught`, literals with explicit types, and answering tools with their top catches. Keep source cuts file-disjoint by ownership, not by splitting callers from the renamed function.
4. **Report adapter and fault writer:** land the corrected report with both compiled/interpreted blame proof; land fault schema, writer, task/proof/delivery consumers, old-data transition together. Then require type/message and remove `at/layer` production/recognition. Move the constructor to `seon.error` with every caller after S6, as decision 7 requires.
5. **Read APIs, one family at a time:** change refusal branches, catches, evidence capture, contracts, and callers in the same loadable commit.
6. **Render/deletion cleanup:** retain behavior tests, retire prose/shape tests with the behavior they described. Do not postpone the consumer conversions needed to make a preceding commit load.

These are dependency boundaries, not proven size/boot claims. Split a large family further only where no renamed API straddles the split. Every source/schema slice needs root's candidate archive lint, contract compilation, live-store adoption and boot proof; none was performed here.

## Agent loop

Fine: agent facts instead of open turns, evaluation→agent refs with writer ordinals, model/outside/test origin, no raw reply or context capture, task/inbox live reads, the existing fold, and crash loss of the unsettled reply (`agent-loop.md:16–20,28–46,279–285`). The probe supports a fresh call id as a value without a turn entity (`tmp/agent-loop/s1-notes.md:16–25`). Ordinals do not require another sequence manager: Datahike's writer suffices. Keep mailbox/schedule Flow procs and SCI's existing context/fork mechanisms; do not build another runner or private-state store.

### L1 — HIGH: evaluation identity and the complete execution world must exist before evaluation

Spec: `agent-loop.md:88–109,113–116`. Fold handles are minted from `(run-id, local-index)` before settlement (`src/seon/turn.clj:4906–4924`; `src/seon/id.clj:129–140`). Writer-assigned global ordinals cannot retrospectively name those handles. The lane's resolution is right (`tmp/agent-loop/s1-notes.md:37–41`): stable evaluation id from fresh call/submission id plus local index; stored ordinal is a separate per-agent ordering value.

The sample additionally omits the required SCI context and first ordinal that `evaluate-sources` uses (`src/seon/turn.clj:4883–4907`). Acquiring/forking the held private context, delivering unbound-result diagnostics, and carrying agent custody cannot disappear merely because the turn entity does. `fork-for-turn` handles those duties (`src/seon/sci/eval.clj:2842–2893`); SCI offers the underlying fork/intern operations (`reference-code/sci/src/sci/core.cljc:260–275,334–340`).

**Edit:** §1 and §2 → replace “ordinal assigned at writer” as implicit identity with “mint call/submission id before the fold; `id/evaluation id local-index` is immutable; writer assigns global ordinal separately.” Specify the acquired agent SCI context, namespace, profile, first local index 0, evaluation id and custody in the execution world and reply. Update error `world-reference` too: today it derives evaluation id from turn id/ordinal (`src/seon/error/refusal.clj:646–654`), which must keep using the local index, never the global ordinal.

### L2 — HIGH: the row writer and settlement seam are missing, and `settle` is not reused unchanged

Spec: `agent-loop.md:103–125`. `evaluation-facts` produces a settle request, not a complete evaluation row (`src/seon/turn.clj:97–187`). `receipt-settle-call` requires an existing run and started receipt (`:1468–1486`); it cannot create turn-free evaluations. The real needed transformations are namespace upserts before refs, source/comment/ns/at/id fields, terminal members, read-evidence component tempids, and settle-once/idempotence checks (`:598–613,1181–1254,1340–1364`).

`turn.batch/settle` writes `record-facts`, whose request is explicitly run-shaped (`src/seon/turn/batch.clj:632–648,683–725`). It does not publish staged cut-value/reasoning blobs. Product settlement does (`src/seon/turn.clj:3402–3438,3573–3585`; attempts also stage reasoning, `:4145–4153,4210–4218`). The stated reuse would drop these duties. The lane correctly identified an unavailable public seam, not a need to copy the private writer (`tmp/agent-loop/s1-notes.md:42–54`).

**Edit:** §2 “reused unchanged” and §2.1 → replace with “extract/generalize one shared settlement owner: batch entries→schema-derived complete evaluation rows, namespace/read-evidence rows, folded program, deliveries, measurement and staged blobs; publish blobs around the one commit; install committed definitions after it.” `record-call` only allocates ordinals and agent/call refs at writer authority. Declare the model call's stable tempid before the transaction function needs evaluation→call refs; a lookup ref to a row asserted later is not an ordering plan. Existing product/JVM/test callers use the same owner. Do not copy the terminal-attribute list into the new namespace.

### L3 — HIGH: refusal and crash claims ignore durable effects and unknown write outcomes

Spec: `agent-loop.md:127–142,267`. “Nothing is written before settlement” and T8's unchanged database are false for arbitrary evaluated forms: the fold supplies the real connection/writer, and agent tools can transact (`src/seon/turn.clj:4922–4936`). The crash ruling permits losing the in-flight reply; it does not roll back already acknowledged writes or external effects.

The proposed retry/call-alone fallback also lacks the distinction between a definite rejected transaction and an unknown outcome. The existing DB wait can return `:seon.db/transaction-outcome-unknown true` while Datahike still commits (`src/seon/db.clj:5684–5709`). Retrying that write or recording a second call is unsafe. In the proposed reusable settle, “rebase” even uses the head captured before the refusal rather than reacquiring it (`src/seon/turn/batch.clj:694,719–721`).

**Edit:** §2.1 → replace “nothing written before settlement” with “the loop writes no intent/capture/attempt before settlement; form effects remain ordinary durable operations.” Retry only a definite program-fact rejection, once, on a freshly acquired head; never reevaluate forms. Preserve all values/diagnostics. For unknown storage outcome, retain ownership and stop further work until actual writer outcome/exit is known. T8 → replace database-unchanged with “no loop settlement/answered advance; no reply replay; previously acknowledged form effects remain.” The call-alone fallback must use the original call identity and run only after definite rejection.

### L4 — HIGH: fault-window duty is dropped, and concurrent errors cuts require an explicit dependency

Spec: `agent-loop.md:111–125,192,202–204,286–287`. Existing windows attach branch faults since the previous settlement to the last evaluation (`src/seon/turn.clj:3443–3475`); transcript consumes those saved refs (`src/seon/render/transcript.clj:336–359`). Inbox messages and mutable counters do not recreate historical fault association. Removing turns changes how the lower bound is found, not whether this duty exists. The lane finding is a design omission.

The old folded-repeat drain is different: errors decision 5 explicitly deletes folding (`errors.md:301–302`). Do not rebuild it. But loop S1 runs before errors S6, when repeats are still in memory (`src/seon/fault.clj:81–136`); omitting the drain during that transition loses counts.

**Edit:** §2.1 item 6 → replace “measurement rows and staged blobs as settle already commits them” with an explicit settlement-duty list including immutable fault refs on the last evaluation, with a writer-derived previous evaluation settlement basis (first window uses the pass basis). Specify zero-form/call-only behavior. §5 sequencing → retain drain/unfold through the shared owner until errors S6 retires it, then delete the seam; do not make a permanent loop folding subsystem.

### L5 — HIGH: three product dials do not prove total awaits or termination

Spec: `agent-loop.md:74–100,132–142,240–244`. The pass has no reply/fault/finally protocol; a context/fold/settlement throw bypasses reply delivery. Today the pass answers escaped failures and closes reply ports even on stop (`src/seon/turn.clj:6013–6036`); stop closes queued requests too (`src/seon/cluster/agent.clj:1571–1577`). Removing the await dial without those duties can leave MCP waiting forever.

A per-form SCI limit does not bound an arbitrary number of forms, a host call ignoring interrupts, acquisition, printing outside the arm, or a stuck writer. Flow stop only sends a command and closes observation channels (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174–183`). Seon's real exit proof is its executor join; that join currently uses the dial being deleted (`src/seon/cluster/agent.clj:1545–1563`). Publishing the transform thread and clearing served interrupt state also remain necessary (`:511–557`).

**Edit:** §2 / §2.2 → replace “await no dial because the pass is bounded” with “one request owns one reply; all terminal paths answer or close it; stage faults are data beside values; cancellation requests interruption but retains ownership until actual exit.” Keep exactly the three owner-selected product dials; derive finite await/stop allowances from existing input/form bounds and caller deadline, and declare which host work cannot guarantee termination. Remove the redundant completion permit/backstop, not thread ownership, join, queue cleanup, or fault delivery. Specify pre-provider failure handling so unchanged `answered-t` cannot trigger an uncounted fault spin.

### L6 — HIGH: the new pass bypasses `no-provider`

Spec: `agent-loop.md:81–95,232–239`. Default's no-provider setting is acknowledged, but the new pass unconditionally invokes `ai/complete` for work. The old guard is outside that function (`src/seon/turn.clj:4567–4569`); `ai/complete` checks credentials/no-auth and invokes its HTTP leaf, not no-provider (`src/seon/ai.clj:1290–1329`). Wiring S2 as written can make paid calls on default.

**Edit:** §2 → replace unconditional provider invocation with an explicit no-provider policy before `ai/complete`, and define idle/submission behavior without fabricated attempts or unlimited empty continuations. Pass fixture `:seon.ai/send!` and credential environment through the explicit provider request. Keep the paid S1 proof only on the authorized disposable agent overlay. Assert that the leaf is never entered on default's no-provider path.

### L7 — HIGH: the live inbox cache omits an input; history keys assume more immutability than the code supplies

Spec: `agent-loop.md:149–170,260–262`. The inbox derivation closes over `answered-t` read outside `health/derived`, but its name is only `[::inbox agent-id]`. `derived` keys on that name and the attributes read inside its callback (`src/seon/health.clj:80–87,113–135`). Advancing only `answered-t` therefore can hit an old inbox. Include the threshold as a content input, or read it inside the callback.

The “evaluations never change” premise also needs qualification: pending code evidence represents owned work that has not exited (`resources/seon/schemas/seon.cluster.eval.edn:21–24`; `src/seon/turn.clj:5002–5003`). The emission resolves namespace and renderer refs (`src/seon/repl.clj:500–515`), and S1 still uses the turn-dependent `changed?` query removed only in S4 (`:516–531`). An eval id and profile id alone do not identify all those inputs; a global loaded-identity stamp also recomputes unrelated units after any reload.

**Edit:** §3 → replace the inbox callback/key with an explicit threshold input or captured agent read. Define the cached unit as the immutable historical projection actually rendered, keyed by its content plus profile content and relevant code/ref dependencies. Exclude mutable runtime evidence from that unit or include its content; remove the turn query before asserting the key is valid. Reuse `health/derived` and `seon.cache`; do not add another invalidation registry. T4 must cover answered-only writes, changed profile/ref content, pending completion, unrelated writes, and cache eviction, not only two reads without a write.

### L8 — MEDIUM: context composition omits owner-required identity and undercounts the whole budget

Spec: `agent-loop.md:146–160,175–181,283`. The sample adds task/inbox/history then a frame; it never renders identity as the first lines through the agent schema's own AI attachment. It budgets history alone and appends live units and frame afterward. `prompt/select` only prices the units it receives (`src/seon/cluster/prompt.clj:302–334`). A prompt can exceed its intended budget even when the history selection passes. Calibration's new attempt join is specified but deferred to reader conversion, whereas S1 already needs it (`:75–105`; lane notes `tmp/agent-loop/s1-notes.md:55–58`).

**Edit:** §3 → replace the standalone string sketch with the agent's declared AI render: identity first, task/current step, inbox since threshold, ordered whole history units, frame; price all composed parts and separators under the configured budget. Move the calibration join with the first new call rows. State the prior fallback and over-budget/newest-unit policy explicitly. T5 must compare complete sent text in the fixture, not just length, under unchanged render code/settings/history; `as-of` is not a guarantee after no-history source deletion or reset (`resources/seon/schemas/seon.cluster.eval.edn:28–40`). Do not restore capture storage.

### L9 — HIGH: the slices remove dependencies from code promised to stay loadable

Spec: `agent-loop.md:189–204,217–247,280,286–287`. S1 is restricted to its new namespace/test/accreted schema files but also moves `outside-wake-t` and needs the unavailable row/settle seam and calibration change. S2 changes the graph before S3 converts request entrances/replies and before S4 converts readers. New evaluations have no run while those readers still join through it. Default's old evaluations are intentionally reset only after S4; that is not permission for S2–S3 to expose empty histories as healthy.

S5 retains `system-turn` but deletes `open-for-agent`; `system-turn` uses that helper in its writer admission (`src/seon/turn.clj:2234–2242`). It retains `generate-turn` while removing `append-generated-*`, which it calls (`:5273–5376`). It retires `author`/turn situations while the retained generated-read path still reads them (`:4992–4998`). A loadable unused function still needs resolvable Vars and compilable contracts. “HEAD boots after each” is unsupported by this order.

**Edit:** §4 / §5 → replace deletion spans with the exact transitive dependencies of retained code and schema references. Owner-authorized retained selection code may remain unused and get no new tests; its dependencies must remain until it is deleted. Replace S1’s ownership list with a dependency schedule: start only new namespace/schema/design work concurrently with errors; root lands owning-file seams/calibration before S1's full-pass proof. Convert entrances and their result readers before routing production traffic. Reset default after S4, then activate the new graph on that clean data. Never backfill or add a second legacy reader.

### L10 — HIGH: the work predicate changes declared wake policy and assumes the wrong index order

Spec: `agent-loop.md:53–66`. “Newest listened wake” makes a schedule firing cause a model call. Existing declarations deliberately distinguish listened wakes (context and cap anchor) from call-opening wakes (`src/seon/cluster/wake.clj:156–174`; `src/seon/turn.clj:2610–2623,2645–2647`). No owner ruling retires this policy. Keep the one walk, but derive its two answers from different declared sets.

“Newest first” is also insufficient for finding the newest outside wake: `agent-wake-datoms` concatenates reversed AVET slices, ordered by entity within each attribute rather than globally by transaction (`src/seon/cluster/wake.clj:293–312`). The existing outside derivation uses a maximum, initialized to 0, rather than taking the first candidate (`src/seon/turn.clj:2652–2660`). An early-exit implementation can anchor the cap to an older wake and deny or admit calls incorrectly.

**Edit:** §1 `wake-ts` comment/work predicate → replace “newest listened wake, newest outside wake; newest first” with “maximum opening-wake transaction for work, maximum outside listened-wake transaction for the cap, both defaulting to 0; fold over all candidates without relying on AVET entity order.” §6 T1 → add schedule/non-opening wakes, multiple attributes, old entities with newer transaction times, and no outside wake. Do not rebuild a sorted wake log or add a stored work flag.

### Corrected loop slice order

1. **Dependency preparation:** owner of `turn.clj`/`turn.batch` exposes one shared complete-row/settlement path; schema accretion and identity/world semantics land; attempt-based calibration and turn-free emission are ready. Preserve the old production path. Errors owners provide the stable constructor/boundary seam; loop does not edit their held files.
2. **One isolated pass:** new namespace/test plus accreted schema; fixture proof of identities, all settlement duties, failure/zero-form paths, no-provider, budget and cache inputs. Then the already-authorized one paid call, only in its disposable overlay. Pure ordinal allocation is tested at writer authority; contexts/execution remain serialized per agent, including JVM entrance access to the same private SCI context.
3. **Entrances and readers:** convert model/outside/test requests, reply envelopes, JVM writer, observations, transcript/status/debug and disposition/delivery callers as loadable owner slices. In particular SCI queue serialization does not make the direct JVM path's shared-context mutations safe merely because ordinal allocation is serialized (`src/seon/mcp.clj:255–275`). Specify that custody/serialization explicitly.
4. **S4 completion and root reset:** retire old-data exposure by the mandated reset, with no backfill/dual reader. Activate the proc/armer and preserve interrupt/join/terminal-request duties together; prove continuing/completed and actual exit. This moves activation later, not the owner’s reset earlier.
5. **Deletion/dial rename:** remove only unreachable machinery, its callers and schema references; keep the closed dependency set of the owner-retained selection code. Remove temporary folded drains with errors S6. Retire behavior tests at the behavior cut, not as a bulk S5 cleanup.

Delete verified unused machinery instead of converting it: `seon.eval.drive` has no first-party caller according to `tmp/clojure-guide/map-repl-path.md:210–220` (**unverified full caller scan here**); if still unused, delete it instead of §4’s 29-reference migration. Similarly retire `row-tx`, unused `turn-request` and `read-only?` after their fixture users move. Reuse the existing fold, SCI context owner, Flow lifecycle, Datahike transaction expansion and central cache. No replacement turn entity, scheduler, counter atom, intent/recovery ledger, capture system, or occurrence folding system is needed.

## Focused verification list for root — not run

No code changed; this report itself requires no tests. Future implementation needs the following exact existing tests, adapted only where the owner intentionally changes the behavior:

- Errors: `seon.fs.jvm-test/an-absent-path-answers-the-not-found-error-type`; `seon.error.refusal-test/a-wrapped-cause-is-recorded-whole`; `seon.error.refusal-test/a-cleanup-failure-survives-beside-the-primary`; `seon.error.refusal-test/every-producer-references-the-worlds-it-was-handed`; `seon.error.attribution-test/blame-follows-the-producer`; `seon.error.attribution-test/a-batch-failure-names-the-definition-visible-when-it-ran`; `seon.instrument-test/jvm-and-interpreted-functions-arm-the-declared-guard`; `seon.instrument-test/an-output-refusal-names-the-function-that-returned-it`; `seon.instrument-test/a-reporter-failure-is-not-relabelled-as-a-contract-violation`; `seon.fault-test/an-unknown-write-outcome-stops-and-throws-without-retry`; `seon.error.resolution-test/a-fault-resolves-by-a-green-stored-run-or-a-deleted-producer`.
- Loop: `seon.run-test/item-1-a-stage-throw-is-a-diagnostic-beside-each-value`; `seon.run-test/item-18-work-in-flight-makes-evidence-pending-and-the-value-is-published`; `seon.run-test/item-19-a-write-after-the-observation-closed-is-refused-to-its-writer`; `seon.cluster.agent-test/disarm-drops-the-route-before-closing-it`; `seon.cluster.agent-test/system-source-submission-uses-the-ordinary-durable-run`; `seon.cluster.prompt-test/calibration-uses-the-agents-recent-attempts-and-config-prior`; `seon.cluster.prompt-test/the-history-path-never-refits-a-rendered-unit`; `seon.cluster.prompt-test/an-unread-usage-history-is-not-an-empty-one`.

New required behavior tests, **proposed names, not installed**: errors `caught-terminates-on-a-cyclic-cause`, `all-malli-report-kinds-preserve-their-evidence`, `malformed-thrown-variant-is-a-producer-fault`, `a-repeat-after-green-resolution-opens-one-message`; loop `handles-survive-global-ordinal-allocation`, `settlement-publishes-cut-values-and-reasoning`, `fault-window-is-fixed-at-settlement`, `an-unknown-settlement-never-retries`, `every-submission-terminal-path-answers-or-closes`, `no-provider-never-enters-send`, `answered-only-write-changes-the-inbox`, `non-opening-wakes-do-not-call-the-model`, `outside-anchor-is-the-maximum-transaction-across-attributes`, `a-crash-keeps-acknowledged-form-effects`, `model-outside-and-jvm-runs-share-agent-context-custody`.

Root should run one central focused list with real SCI, armed contracts, canonical worlds and injected provider leaves; inspect stored reds per owner. Candidate archive lint with `--cache false`, contract compilation, live-store schema adoption and boot proof remain required for implementation landings. No green, installation, performance, or boot claim follows from this review.

## Counts and files read

- Errors: **6 high, 1 medium, 0 low** (E1–E7).
- Agent loop: **9 high, 1 medium, 0 low** (L1–L10).

Files read (source/library/test files were relevant excerpts or declaration inventories, not a claim to read each file end to end):

`CLAUDE.md`; `docs/seon/specs/errors.md`; `docs/seon/specs/agent-loop.md`; `tmp/errors/s1-s2-notes.md`; `tmp/agent-loop/s1-notes.md`; `tmp/agent-loop/map.md`; `tmp/clojure-guide/map-data-contracts.md`; `tmp/clojure-guide/map-repl-path.md`; `.agents/skills/data-oriented-clojure/SKILL.md`; `.agents/skills/seon-flow-architecture/SKILL.md`; `.agents/skills/datahike/SKILL.md`; `.agents/skills/clojure-testing/SKILL.md`.

`src/seon/error/refusal.clj`; `src/seon/error.clj`; `src/seon/fault.clj`; `src/seon/instrument.clj`; `src/seon/db.clj`; `src/seon/fs/jvm.clj`; `src/seon/id.clj`; `src/seon/turn.clj`; `src/seon/turn/batch.clj`; `src/seon/cluster/agent.clj`; `src/seon/cluster/wake.clj`; `src/seon/cluster/message.clj`; `src/seon/cluster/prompt.clj`; `src/seon/mcp.clj`; `src/seon/ai.clj`; `src/seon/health.clj`; `src/seon/repl.clj`; `src/seon/sci/eval.clj`; `src/seon/sci/kernel.clj`; `src/seon/render/transcript.clj`; `src/seon/problems.clj` (search excerpts).

`resources/seon/schemas/seon.error.edn`; `resources/seon/schemas/seon.cluster.eval.edn`; `resources/seon/schemas/seon.ai.attempt.edn`; `resources/seon/schemas/my.fs.edn`.

`test/seon/fault_test.clj`; `test/seon/instrument_test.clj`; `test/seon/error/refusal_test.clj`; `test/seon/error/resolution_test.clj`; `test/seon/error/attribution_test.clj`; `test/seon/run_test.clj`; `test/seon/cluster/agent_test.clj`; `test/seon/cluster/wake_test.clj`; `test/seon/cluster/prompt_test.clj`; `test/seon/fs/jvm_test.clj` (test declaration inventories).

Pinned dependencies: Malli `43c6dca8d2d8df3162b13725939954807be4a4ed` (`reference-code/malli/src/malli/core.cljc`, `error.cljc`, `instrument.clj`); core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051` (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`, `flow/impl.clj`); SCI `ce0aa53b9bf35029e0bc27a962d799c196a37874` (`reference-code/sci/src/sci/core.cljc`); nREPL `f3a044153eaa6050e7447655dab5bc42cfdd68c2` (`reference-code/nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj`); Datahike `d9121bbbad955b383c421c796aceff4006016c9f` (`reference-code/datahike/src/datahike/db/transaction.cljc`); `reference-code/clojure/src/clj/clojure/core_print.clj` (working-tree excerpt; its gitlink was not recorded).
