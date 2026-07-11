---
type: research
status: active
tags: [research, agent, architecture, flow]
---

# Design-Soundness Audit (2026-06-26)

A 6-lens adversarial audit of the agent runtime: hunt smells + edge-case
behaviors, then cluster them into ROOT design errors with the UNIFYING fix
(not a band-aid). Owner directive: smells/edges are clues to design errors;
fix the design, do not patch. Lens tallies: [{'lens': 'derive-everything', 'n': 3, 'real': 3}, {'lens': 'one-mechanism', 'n': 3, 'real': 3}, {'lens': 'schema-contract', 'n': 6, 'real': 6}, {'lens': 'run-model-edges', 'n': 4, 'real': 4}, {'lens': 'errors-capability', 'n': 4, 'real': 4}, {'lens': 'wire-bus-lifecycle', 'n': 4, 'real': 4}].

**6 root design errors** (ranked). Raw findings (F#) + full per-lens
detail are in the run transcript; this is the synthesis.

## DE-1 — Run-model atomicity is enforced at the wrong granularity, on stale snapshots, and through duplicate driver paths

**Severity:** blocker · **Confidence:** high

**Root cause:** There is no single atomic primitive that owns 'open-or-renew a run + drive it + fence its work'. Instead the run lifecycle is spread across several code paths (wake-handler's snapshot-bound case dispatch, drive-run!/run-loop!, bootstrap-turn!) and the run-id 'fencing token' guards only a privileged subset of lifecycle bookkeeping writes (beat!/renew!/pause!/resume!/close-run!) rather than the unit of work (open-turn! + eval-batch!, which takes no run-id at all). Decisions are computed from a tx listener's snapshot and then executed in a deferred macrotask, so the live DB can move between decide and act. The result is the run model's stated invariants ('every inbound either renews a live run or opens a new one', 'one driver per run', 'a superseded/overrun run stops mutating the corpus') are each only partially true.

**Symptoms:**
- F13: wake-handler :running->renew defers renew-current-run! which no-ops if the run closed in the window — inbound message silently dropped, agent goes idle until some unrelated future tx (loop.cljs:245-264, renew-current-run! :182-189)
- F14: run-id fences only lifecycle datoms; open-turn! (turn.cljs:176) and eval-batch! (eval.cljs:2656, no run param) mutate the corpus unfenced, so a watchdog-closed run still lands its full eval batch; drive-run! has no single-driver guard so two loops can fold one run (run.cljs owns-run? :169-178, loop.cljs:312-329)
- F15: bootstrap-turn! is a second hand-rolled open-run->open-turn->drive that ignores open-run!'s error envelope, producing detached turn-0 / parking a run it never owned on a CAS-loss (client.cljs:2102-2127)
- F16: next-event collapses :deadline-exceeded/:turn-limit/:crashed onto :superseded via an else catch-all, mislabeling why the loop stopped vs the stored closed-reason fact (loop.cljs:95-105)

**Unifying fix:** Make ONE atomic primitive own the run lifecycle: a 'open-or-renew' that re-derives state inside the with-agent scope (always attempt open-run! CAS, on CAS-loss renew!), with terminated-at participating in the open guard; a driver-lease so run-loop!/drive-run! is a no-op when a live driver already owns the run; carry the run-id through run-turn!->eval-batch! so the per-eval tx itself rejects (with the standard fencing-error envelope) when the agent no longer owns the run — fencing the WORK, not five chosen writes. Wake-handler, bootstrap-turn!, and drive-run! all call this one primitive instead of re-deriving open/renew/drive. Map closed-reason->event 1:1 (no lossy default) so the loop's log matches the stored fact.

**Avoid the band-aid:** Adding a no-op-safe guard to renew, sprinkling owns-run? onto one more write, patching bootstrap-turn! to check the envelope, and adding one more case to next-event — each fixes a single path and leaves the other open-run/drive/fence paths to drift independently.

## DE-2 — The tx-feed wire is not one coherent bus — delivery, own-write dedup, and provenance each have a separate partial path that the substrate's own activity falls through

**Severity:** blocker · **Confidence:** high

**Root cause:** The wire is modeled three incompatible ways at once. (1) Delivery: the server treats feed drops as best-effort 'recover via basis-t on next read', but the pod treats the feed as the authoritative wakeup bus with no catch-up — reconnect re-subscribes with no since-basis-t cursor, so any foreign tx during the 2s outage (or dropped on overflow) never fires the wake handler. (2) Dedup: the pod's own writes already fire local listeners (datahike.writer fires unconditionally) AND echo on the feed, so a leaking per-write !own-write-ids set stitches the two firing paths — write-ids leak forever when a write errors or commits before the adapter subscribed. (3) Provenance: tx-meta is filled only from an active ALS scope, so the substrate's own writes (ticker watchdog, boot crash-recovery, cron firing) land with an empty causality bundle. Across all three, the single coherent mechanism has holes the substrate/own writes fall through.

**Symptoms:**
- F21: tx-feed reconnect/overflow drops wakeups silently — no since-t replay, no reconciliation; an idle agent a message targeted stays idle forever; the read-lag ryow-deref! 10-spin throw self-triggers the 2s re-subscribe (wire.cljs:386-411,197-206; boot.clj:77-125)
- F22: own-write listeners fire via two paths deduped by the leaking !own-write-ids registry — violates 'nothing stored that needs clearing' and the 'one bus' claim (wire.cljs:215-223,245,342-362; datahike/writer.cljc:258-259)
- F23: substrate's own writes (watchdog/crash-recovery/cron) carry no tx-meta provenance — :seon.db/origin :system is defined but never auto-assigned, so d/history/inspector origin joins silently miss them (db/internal.cljs:1010-1033; loop.cljs:436-461; run.cljs:429-499; client.cljs:2187-2189)

**Unifying fix:** Make the feed the genuine single bus and treat every writer uniformly: (a) re-subscribe with a since-basis-t cursor (server replays the gap from the pod's already-tracked :last-db) OR add a DB-derived wake-reconciliation pass to the one ticker (scan idle agents with un-acted-on inbound + no open run) — the same self-healing derive-over-DB shape recover-crashed-runs! already uses; (b) route ALL listener firing (own + foreign) through the feed pump (or suppress echoes server-side using the write-id the pod already sends), deleting !own-write-ids and the own/foreign branch entirely; (c) default :seon.db/origin :system in merge-tx-context-into-opts when no ALS scope is active, so every tx including substrate writes is tagged.

**Avoid the band-aid:** Bumping max-queued-events, manually disj-ing leaked write-ids on a timer, adding await/yield to ryow-deref! to dodge the throw, or wrapping only the boot-recovery path in with-tx-context — each patches one hole and leaves the bus three-mechanisms-stitched-together.

## DE-3 — No single acyclic owner for DB-derived state — derivations and projections are copied or stored per-consumer instead of owned by one leaf, so they drift

**Severity:** major · **Confidence:** high

**Root cause:** The architecture's premise is 'everything is a fn of the DB, derived; projections co-written with their fact'. But there is no leaf namespace that owns the DB-read half of each derivation, so consumers (a) re-implement the derivation logic locally 'to avoid the require cycle', (b) materialize projections on a separate refresh path (boot-only) from the source that should co-write them, or (c) cache a derivable value in a process-local atom. Each copy is a place the value drifts — and several already have.

**Symptoms:**
- F1: entity-kind render-dispatch decomposition (id-attr/required-attrs/render-fn) is written ONLY by the boot seed; runtime register! writes source only, so agent-registered kinds never render via their render-fn until reboot (schema.cljc:263-313; eval.cljs:1318-1339,1580,1760; render.cljs:201-272)
- F2: dead third copy of the required-attrs projection (*schema-required-counts atom + schema-required-count, zero readers) plus a false 'on every subsequent register!' docstring that hides the divergence (schema.cljc:261,286,315-319; render.cljs:206-209)
- F3: run heartbeat :last-beat-at is a stored per-turn projection of max turn :at, costing one awaited write per turn for a display field (run.cljs:343-356; agent.cljs:503-511)
- F4: current-run + the derive-state envelope re-implemented in 5+ namespaces, each justified by dodging a require edge (ctx.cljs:286,297; run.cljs:155; render/default.cljs:218; agent.cljs:484,350; schedule.cljs:266)
- F5: turn-count Datalog hand-written in 4 sites; the two run-scoped copies already diverged (agent.cljs:401 guards installed-schema, loop.cljs:77 does not) (loop.cljs:77; agent.cljs:376,401; render/default.cljs:196; render/chat.cljs:141)
- F11: activity-log re-derives :seon.agent/state with a partial cond that omits :paused, reporting :running for a paused open run instead of routing through fsm/derive-state (loop.cljs activity-log)
- F24 (derivable half): !runs-this-process atom marks 'this pod's runs' — DB-derivable from a per-boot marker (run.cljs:113-120)

**Unifying fix:** Establish the acyclic leaves and make every derivation a named fn there, referenced everywhere: seon.agent.run (already requires only db+schema) owns current-run, derive-state-from-db, run-turn-count, agent-turn-count, last-beat (= max turn :at), and a boot-id marker for 'this process'; the in-memory seon.schema registry owns kind/render-fn/required-attrs derivation (or schema-tee-row co-writes the full decomposition exactly as :seon.fn rows co-write spec/arglists/doc). Fix the require cycle that drove the copying instead of copying. armable-agent-ids/agent-idle?/activity-log become FILTERS over the one derive-state, not re-encodings of the rule. Delete *schema-required-counts and the !runs-this-process atom.

**Avoid the band-aid:** Patching each copy individually (add :paused to activity-log's cond, add the installed-schema guard to loop.cljs's count, re-run all-entity-schemas-tx-data at runtime, fix the one false docstring) — every such patch leaves the other N copies and the next reader re-derives a fresh one.

## DE-4 — No canonical result/error envelope — every layer invents its own ok?/error shape, so failures leak, lose their discriminator namespace, and lose their type

**Severity:** major · **Confidence:** high

**Root cause:** 'Errors are values with one envelope' is asserted but never realized as a single constructor. db.internal/error-envelope guarantees a :seon.error/kind, but agent-layer fns hand-build bare failure literals (no kind), return the lower layer's envelope verbatim (discriminator key namespace flips between success and failure branches), and stuff wrong-typed values into the envelope. So 'did it fail' is encoded ~5 incompatible ways and callers must probe multiple keys or rely on nil-is-falsey.

**Symptoms:**
- F6: agent-lifecycle precondition/fencing failures hand-build {:seon.db/ok? false ...} with NO :seon.error/kind, so caller-fault cases ship untagged and are mis-classified by readers that branch on kind (run.cljs:206,244,301,400; message.cljs:184,192,281; lifecycle.cljs:46; internal.cljs:17)
- F9: message! success carries :seon.agent.message/ok? but failure carries :seon.db/ok? — no single key discriminates; serve.cljs survives only via absence-is-falsey (message.cljs:103-111,233-240; serve.cljs:449-453)
- F18: 'did it fail' encoded 5 incompatible ways; run-turn! returns 3 differently-shaped maps with no shared discriminator, forcing run-loop!'s errored? to OR three structural probes (message.cljs; turn.cljs:307-389; loop.cljs run-loop!)
- F20: run-turn! stores a STRING under :seon.error/data which is registered :map — silent because run-turn! is uninstrumented (turn.cljs:375-389; db.cljs:141)

**Unifying fix:** Define ONE result-envelope shape with a single discriminator attr + a typed error attr, and one constructor pair (e.g. db/ok, db/fail with {:seon.error/kind ...}) in seon.db. Require every fn to TRANSLATE a lower-layer failure up into its own namespace rather than leaking the callee's envelope, so success and failure share a discriminator key and every failure carries a uniform kind. Once the producer can only emit the typed shape, F20's string-under-:map and F6's missing-kind become structurally impossible and run-turn!'s three shapes collapse to one.

**Avoid the band-aid:** Hand-tagging each precondition site with :user-input, teaching serve.cljs/run-loop! to probe yet more keys, or reusing :seon.db/error ad hoc at run-turn! — each leaves the next new fn free to invent its own shape.

## DE-5 — Boundaries re-name and re-type values instead of referencing the one registered shape; bridge gaps are inlined per-site

**Severity:** major · **Confidence:** high

**Root cause:** Registered Malli shapes exist, but boundary/glue code does not reference them: it re-keys a namespaced value to a bare key, types the same logical argument three different ways, invents synonym keys for an attr that already exists, leaves the central pipeline uncontracted, and — where the Malli->datahike bridge lacks a type — papers over it with a per-attr pr-str/read-string workaround. The 'register once, reference everywhere; fix the bridge, never inline' rule is violated at exactly the boundaries that matter.

**Symptoms:**
- F7: LLM reply text is re-wrapped from registered :seon.ai/text to a bare :text key at every agent-adapter (anthropic.cljs:356; openai_compat.cljs:405-407; client.cljs:1874; consumed turn.cljs:249,302-307) — drops the namespace AND escapes the registered schema
- F8: agent-id argument typed three ways across request schemas (:seon.agent/id 14-char vs :seon.db/id vs bare :string), so the same id passing one fn is rejected by another (agent.cljs:303,452; run.cljs:153,169,218; loop.cljs:484; ctx.cljs:1486,1811)
- F10: map-valued attrs (:llm-usage, :llm-meta, :seon.eval/error-data) registered :string and round-tripped by hand because the bridge has no :db.type/map (turn.cljs:75-76,318-319; usage.cljs:40-48; agent.cljs:144-150)
- F12: turn pipeline public fns carry no :malli/schema and thread unregistered keys + needless id synonyms (:id-of-run/:id-of-turn, :turn-idx, :llm-fn, :compile-state) (turn.cljs:109-389)

**Unifying fix:** Reference the one registered shape end-to-end: carry :seon.ai/text from complete through the llm-fn contract and delete the adapter re-wrap; register one canonical agent-id-arg schema and reference it in every request map (and stop using the identity attr as an arg-slot type); add ONE map/edn-blob storage type to the Malli->datahike bridge so map attrs declare their real shape with the round-trip in one place; register the turn pipeline's keys (closures included, as schedule already proves), drop the -of-run/-of-turn synonyms, and add request/response schemas so the central loop obeys the same contract discipline as run/message/todo.

**Avoid the band-aid:** Adding a bare-:text passthrough, widening one request schema to accept both id shapes, copy-pasting pr-str/read-string to the next map attr, or leaving the turn loop uncontracted because 'closures can't be specced' — each re-types a value at a boundary instead of pointing it at the registered shape.

## DE-6 — One sandboxed-exec service with multiple doors, but only the interaction door was hardened — the render door mints interactions and trusts a fn-symbol's namespace as both author-identity and run-as-identity

**Severity:** major · **Confidence:** medium

**Root cause:** The exec model is 'one sandboxed-exec service, three doors (eval/render/interaction)', but the security invariants were applied to only one door. /call was hardened to resolve-and-apply-by-value and authorizes by the fn's namespace — yet the RENDER door, which MINTS the interactions /call later authorizes, neither enforces that the interaction's AUTHOR owns the target fn nor avoids interpolating agent-controlled symbol text into an eval-string. So namespace is overloaded to mean both 'who authored this' and 'whose authority it runs under', and the data-into-source pattern the /call fix removed still lives in the render door.

**Symptoms:**
- F17: render-agent-tile transforms a tile's content symbol with no check that its namespace is the rendering agent's home ns, so agent A's tile can emit [:button {:on-click 'B/foo}] which /call then runs AS B with A-supplied args — cross-agent confused deputy (render.cljs:445-451; transform.cljs:140-161; call.cljs:63-115)
- F19: SCI invoke-bounded builds an eval-string by str-concatenating agent-controlled (namespace value) and (name sym), allowing form injection via a crafted symbol — the exact data-into-source anti-pattern /call removed, left in the render door (render/sci.cljs:404-416)

**Unifying fix:** Establish ONE capability invariant shared by every door: an interaction may only target a fn the AUTHORING agent owns. Bind the authoring-agent identity (the canvas/section owner) through transform into the /call URL and refuse any handler symbol whose namespace differs from the authoring ns; and make the render door resolve-and-call-by-value exactly like the interaction door — invoke the resolved var, never build the call site from agent-controlled symbol text. Author-identity and run-as-identity become two distinct, explicitly-bound facts instead of one overloaded namespace.

**Avoid the band-aid:** Escaping/sanitizing the SCI eval-string, or adding a namespace-equality check only inside /call — leaves the render door minting interactions under a different rule than the door that authorizes them.

## Loose bugs (do not cluster into a design error)

- F24 (runtime-home half): the genuinely-non-derivable runtime artifacts (!loop-input agent->closure, !ticker, !adapter, !agent-conn) live as scattered private atoms across four namespaces with no single 'runtime/process state' home, making the full live-state surface hard to see or reset. Not a design error — an organizational smell; fix is to gather them into one named runtime-state holder (the derivable !runs-this-process belongs to the derive-everything cluster, not here). src/seon/agent/run.cljs:113-120, loop.cljs:69, store/wire.cljs:308-310,380, client.cljs.
