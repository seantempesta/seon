---
type: review
status: REVISE
created: 2026-09-27
design-pin: 409626a1e
source-pin: 789f07411685f8a8068ab1c5308f1795e53d9497
---

# Agent data-model design review — 2026-09-27

**Verdict: REVISE.** The agent → session → ordered turns → evaluations story,
observed namespace symbols, and settings-base relationship follow the owner's
rulings. The proposed B boundary reduces work substantially, but does not give
constant-cost session writes. Several schema/caller collisions also prevent the
phases from being independently loadable as written. Revise the design before
source edits; this review implements nothing.

Reviewed **409626a1e**, not the concurrent follow-up design **40c7b0727**. All
design line numbers below refer to that commit. Source citations refer to archive
**789f07411685f8a8068ab1c5308f1795e53d9497**. Read AGENTS.md, the three requested
skills, the modeling guide, the design, both prior-art/vocabulary inputs, the
config design and review, and `open-items.md:79–95`, in the requested order.
Also read the REPL skill and the turn PRD §§13–15 for diagnosis.

Default was reachable but **faulted**: pid 53231, start 2026-09-27T16:07:56Z,
source `f4af60b0f`, 10 open faults, 77 failed tests and 64 unknown failed-test
observations. The relevant db/turn/program source is unchanged between that
archive and the reviewed source. All probes below used MCP JVM mode, explicit
root `/Users/sean/src/seon`, cluster `default`, and `read_only true`. Temporary
database values came from `seon.db/with`; no transaction, adoption, reload,
arming, restart, provider request or test run was started. The MCP edge retained
oversized inspection results as artifacts. No installed target session API is
claimed.

## Findings

1. **B stops traversal at turns, but its session writes still grow with history.**
   Design §3, especially line 145, and R8 promise a bound they do not establish.
   `write-owned-values-error` selects only `:db/isComponent` attributes
   (`src/seon/db.clj:3556–3562`); `owners-of` seeks only those attributes
   (`:3610–3651`). **Owner discovery does not walk ordinary refs.** Expansion
   likewise follows only component edges (`:3678–3690`). A turn-only write under
   B therefore cannot discover the session through its ordinary turn ref.

   However, `write-entity-value` reduces **every EAVT datom on the session** into
   its row (`:3479–3494`), retaining the complete set of numeric turn refs. The
   resulting owning value contains all those IDs, although not the turn maps or
   their forms. `write-entity-error` validates the complete row (`:3502–3537`),
   using Malli `m/validator` (`:3274–3278`); Malli's collection validator traverses
   members (`reference-code/malli/src/malli/core.cljc:1532–1539`,
   `-collection-schema`). Appending a turn touches the session; a namespace write
   touches it; a plan/settings edit reaches the agent and expands its session.
   Even an idempotent namespace assertion is a seed because attempted datoms
   participate (`db.clj:3662–3669`). “Emits no datom” does not mean no validation.

   The live structural counterfactual below confirms the distinction. Changing
   only the native runtime→turns component flag in an immutable value made
   turn-only writes about 1.5–2.1 ms at both 61 and 2,061 turns, but session writes
   grew from 1.59–1.78 to 2.55–3.03 ms, with 61/2,061 IDs in the row. This is
   evidence about the ownership seam, **not implementation proof of B**: it keeps
   today's runtime schema/back-ref and has no proposed turn→forms components.

   **Required change:** price B as O(session turn refs + the affected component
   trees) for session/ancestor writes, and O(one turn's owned data) for an isolated
   turn write. Bound a turn by actual admitted forms/evidence, not “small.” Change
   R8 to exercise turn append, namespace, settings/plan and form settlement
   separately. No fixed number of turns follows from the 250,000 node limit:
   it counts distinct `[phase eid]` visits (`:3582–3587`), not refs, string size,
   decoded plan members or elapsed time. The 1.8 s row is a useful proxy for A,
   not a measured settlement under A. Q1's recommendation must be repriced;
   ~40 lines for an incremental complete-value validator is an unproved estimate,
   requiring its own reviewed invariants and cross-field/schema-change proof.

2. **The proposed “new” `:seon.turn/forms` already has a different contract.**
   Design lines 110/242/495 replace it with a set of component refs. It already
   denotes `[:vector :seon.turn/form]` (`resources/seon/schemas/seon.turn.edn:145`),
   required by `:seon.turn/system-result` (`:55–58`). `system-turn` returns actual
   form maps under it (`src/seon/turn.clj:2358–2362`), and declared-system-forms
   and preview paths consume it (`:2031`, `:2251`, `:5492`). A ref schema cannot
   validate those maps as the same value.

   **Required change:** choose a fresh stored relationship name, or explicitly
   rename the existing in-memory meaning and its entire schema/source/test closure
   in P3. Do not widen one key to mean both. Include `seon.eval.edn` (its entity
   still requires `:seon.cluster.eval/run`, line 10) in the retirement closure.
   Q1 can retain the intended ownership choice; its named schema is not ready.

3. **Removing back-ref identities needs an explicit empty-component lifecycle.**
   Deleting `:my.plan/agent`, `:seon.runtime/agent` and `:seon.config/agent` is
   directionally correct. Creation currently uses exactly those facts to give
   each initially empty component a row (`src/seon/cluster/agent.clj:234–240`).
   An empty map/empty many collection stores no child datoms. The complete-value
   writer refuses an attached child with an empty row (`src/seon/db.clj:3704–3705`).
   The target session permits both members absent (design lines 98–103); deletion
   of its sole namespace before it has a turn can make the same problem.

   **Required change:** state creation and last-fact-removal behavior. Omit an
   empty plan attachment and create/link its identity-less value with its first
   real fact; preserve its declared empty render. Initialize the session with a
   meaningful fact and specify how losing its last fact is handled. Settings'
   required base supplies its initial fact. Do not introduce an identity or
   existence stamp just to keep an empty component alive. Put all affected
   producers/consumers and adoption of existing empty plans in the owning slice.

4. **Required settings-base belongs to the stored concern, not every sparse input.**
   The base-ref composition is accepted, but P5 omits its schema authority.
   `:seon.config/agent-overlay` is generated by `derive-config-forms`, not owned
   by a hand-edited resource (`src/seon/schema/edn.clj:63–93`). It currently
   contains only optional per-agent dials. Both `settings!` and its transaction
   function accept that same schema (`src/seon/agent.clj:87–113`). Adding a
   required base there would reject `(settings! {<dial> <value>})` before the
   writer can resolve the agent's base. Merely adding an open-map key without
   declaring it required would fail to enforce the proposed invariant.

   **Required change:** distinguish the stored overlay entity with required base
   from the sparse override request, deriving the shared dial entries once. Add
   `schema/edn.clj` and affected contracts to P5. Preserve `config/effective`'s
   required-dial defaults and typed errors (`src/seon/config.clj:875–911`) when
   reading through the ref; specify the selector/arity at that owner rather than
   copying its semantics into AI. Retain the folded config design's A/B/C/D site
   classification, writer-side decisions, false/zero precedence and error
   propagation. Q3 can remain later work; removing the config identity would
   also change the current `effective` lookup, not just erase a duplicate string.

5. **The relationship audit misclassifies required refs and omits live concerns.**
   Design §4 calls firing→agent and capture→turn optional/sweeping. They are
   required in their stored entities (`seon.schedule.fire.edn:19`,
   `seon.context.capture.edn:9–10`); cluster→config is also required
   (`seon.cluster.edn:7`). Their surviving rows refuse an invalidating sweep.
   A declaration of `:seon.db/ref` alone does not decide optionality.

   The retained firing-agent edge has a real consumer, but “denormalized routing
   edge” leaves its meaning unresolved under the no-mirrors ruling. The writer
   resolves the owner in the firing transaction and records it once
   (`src/seon/schedule.clj:378–389`). It can mean **the recipient of this firing**,
   which is historical routing, not today's mutable task owner. Document that
   distinction and deletion policy; otherwise derive it and redesign its listener.

   Notes disappear from the nine-attachment picture without disposition:
   `:my.note/_agent` is a declared unit (`seon.agent.edn:41`), with an actual
   renderer and public reader (`src/seon/note.clj:58`, `:113`, `:291`);
   note→agent is required and note→about optional (`my.note.edn:15–17`). Listens
   move to the agent in the audit but are absent from the target picture/render
   inventory. Tasks and issues are grouped despite distinct readers. The retained
   `:seon.turn.work/situation` stamp needs its named follow-up task or same-slice
   retirement; “issue candidate” does not resolve a ruled deletion.

   **Required change:** correct the three required-ref rows, describe the firing
   as an immutable routing observation if kept, and disposition notes/listens and
   each task/issue relationship. For every retained ref, name requiredness,
   target-existence enforcement and source/target deletion separately. The rest
   of the listed classifications are consistent with their stated consumers:
   owned session/plan/settings/forms/evidence/attempts; optional current/home ns,
   steward, trigger/handled, sender/caused-by, oversight and occurrence refs;
   required recipient/base; historical namespace/renderer symbols. B's ordinary
   turn refs additionally need single-session membership/ordinal enforcement:
   component ownership will no longer prevent sharing one turn between sessions.
   Q5's declared schedules attachment remains viable without making schedules
   components. A message's two-party relationship alone does not mathematically
   preclude ownership by one party; its independent lifecycle is the reason to
   keep messages peers here.

6. **Forward attachment presentation is sound, but the shared graph contract is missing.**
   `walk/graph` is currently an entity graph with integer eid nodes/edges
   (`resources/seon/schemas/seon.render.walk.edn:151–169`). It walks reverse and
   observed-symbol edges (`src/seon/render/walk.clj:979–997`); it does not yet
   represent a query-result attachment such as “inbox (0)” as one node. The
   ordinary context acquisition is a separate consumer: it uses declared
   concerns/components (`:97–125`) and selects home namespace (`:603–612`).
   Changing `graph` alone does not change context selection. Conversely,
   globally removing reverse traversal loses namespace→definitions, function
   callers and evaluation→turn navigation unless those are declared concerns.

   The debug page accepts arbitrary roots and assumes numeric node ids
   (`src/seon/render/web.clj:3267–3295`, `:3325–3339`). P4 names neither that
   consumer nor graph schemas. Following every forward ref also expands
   `oversees` into other agents' sessions by default, contrary to the promised
   one-line summary. Current graph ordering uses opened-tx/eid, and width cuts
   use target eid, not the new ordinal (`walk.clj:1029`, `:1064–1068`).

   **Required change:** specify one derived attachment representation using the
   existing units, its count/empty/refusal/cut/requery behavior, and the schema,
   web and context caller closure. Declare inverse concerns needed for arbitrary
   roots; distinguish semantic attachment selection from expansion depth. Keep
   oversight shallow until requested. Order **before** selecting a bounded
   turn window. Include notes and the current namespace half. Do not store
   synthetic grouping entities or add a second graph/render pipeline.

7. **“Unanswered (no handling claim)” is the wrong predicate.**
   Design lines 297/382 conflate handling and answering. `close-call` explicitly
   records handling, never answering (`src/seon/turn.clj:480–501`);
   `latest-answering-turn-t` and `unanswered-wakes` derive answering from accepted
   reply/opening basis (`:3111–3147`). The existing inbox query filters handling
   (`src/seon/cluster/message.clj:548–554`). An accepted reply can cover a wake
   before a handling claim, so those sets need not coincide.

   **Required change:** choose and name the attachment's actual set: “unhandled
   inbox” may reuse the existing query; “unanswered messages” must use the wake
   predicate. Keep requery access to older incoming **and sent** messages after
   compaction removes the form that sent one. No second inbox/outbox stored edge
   is needed. R7 must cover the accepted-reply/no-claim and system-turn cases.

8. **Namespace observations are correct; the adoption and refusal transitions are incomplete.**
   Accept Q2(a): a historical namespace is an unqualified-or-qualified symbol
   value, while the session namespace is a live ref. Atomic identity upsert plus
   linking avoids the absent lookup-ref failure. Keep `ending-ns` in the in-memory
   evaluation until settlement; only its stored duplicate is retired.

   P2's “no reset” does not say how existing sessions acquire their last settled
   namespace or how old form refs become symbol observations before retirement.
   Source rows currently store namespace refs and ending symbols
   (`src/seon/turn.clj:152–156`, `:1556–1558`); retiring them without a conversion
   loses old prompts and resets continuing agents to home. Six schema resources
   name the old eval namespace, including `seon.eval.edn`, absent from P2's list.

   Identity-only namespace upsert is admitted by `seon.ns/ns` (`seon.ns.edn:16–19`)
   and omitted by digest-change discovery (`program.cljc:631–655`). That proves
   R3's narrow claim, **not** universal program compatibility: a live `with`
   probe produced `changed-identities #{}` but `digest-map` with that identity
   returned `:seon.program/definition-digest` missing evidence, as its full-scan
   path also requires (`program.cljc:563–608`). Merge currently scopes its digest
   reads (`cluster/source.clj:880`); distinguish that from unrestricted inspection.

   **Required change:** preserve existing current/history values during P2
   adoption, or explicitly price the root-coordinated reset instead of claiming
   accretion does the conversion. State behavior for no evaluated form, an
   explicit move followed by refusal, removal of the current/home namespace,
   and a settlement transaction that itself fails. Q4(a) is right when the
   refusal-recording transaction commits; an aborted transaction cannot publish
   the session update. Name the existing refusal/recovery boundary. Extend R3
   to the actual program consumers; do not manufacture a definition digest for
   an observed namespace token.

9. **Compaction needs a reachable writer and an accurate retention guarantee.**
   Removing the five listed no-history flags is appropriate for retained source,
   comment, output, diagnostics and reply. `:seon.eval/shown` already retains
   history (`seon.eval.edn:47–50`), and shipped keep-history is true
   (`config/default.edn:4`). The retention cost is **not one retraction datom
   per form** (design line 366): each retained attribute's old assertion and
   negative datom enter temporal EAVT/AEVT and indexed AVET as applicable
   (`reference-code/datahike/src/datahike/db/transaction.cljc:471–485`). Old
   retained commits may still contain no-history text; the defect is loss from
   the resulting head's temporal history, not erasure of every snapshot.
   Removing the flag cannot restore text already absent there.

   §8 describes an atomic replacement but §12 assigns no concrete compaction
   entrance, request, concurrency rule or producer. The old `context/compact-tx`
   only replaces contribution refs (`src/seon/context.clj:206–251`); P8 deletes it.
   P8's claim that contribution evaluations have no outside consumer is false:
   `src/seon/render/transcript.clj:701–703` reads them,
   `context.clj:340–355` still produces them for captures, and
   `test/seon/context_capture_test.clj:7` covers them. The contribution schema
   remains the target of `seon.context.capture/contributions`.

   **Required change:** assign the real compaction writer/entrance to a loadable
   slice, with writer-checked selected forms and one settlement of already
   evaluated replacement forms. Preserve attachments, old shown text and turn
   control/calibration facts; measure the many-turn compaction's own cost. Keep
   the capture family when deleting dead selection machinery, or convert its
   readers/writer in the same cut. Correct the retention accounting and test
   `as-of` on the **post-compaction database**, not merely a held old db value.

10. **Oversight must not discard agents whose admission closed but whose work has not exited.**
    Design lines 393–395 retract `oversees` at admission closure and call the
    remaining set “live.” `host/retire!` closes admission first, then joins work;
    a failed join deliberately retains execution for retry
    (`src/seon/cluster/host.clj:155–179`). Closing admission is not retirement
    completion. Root most needs to see the agent when that join fails.

    **Required change:** retain the oversight responsibility edge through actual
    exit/release, or retain the edge historically and derive a summary that
    explicitly includes closing/failed-exit agents. Define who writes it for
    hosted and issue-worker agents and which branch is authoritative. Do not
    infer “idle” from the parent row if the agent executes on another branch
    (`:seon.agent/branch` already names that custody). R11 must include a failed
    retirement join. This adds a lifecycle qualification to the owner's oversight
    ruling, not another stored status flag.

11. **The phase/reset and scripted-sweep claims are not yet a loadability plan.**
    Existing turns already have a forward runtime→turns component edge
    (`src/seon/turn.clj:464–467`, `resources/seon/schemas/seon.runtime.edn:3–6`).
    P3's reset rationale “only the retired reverse edge” is false. Ordinals,
    session links and evaluation membership still require deliberate conversion
    or a reset. P5's missing required base is a real conversion/reset boundary;
    P6's missing oversight edges are another. A reset may be the owner's
    disposable-data choice, but does not prove schema adoption on an existing
    store. P7's empty-component problem cannot be repaired by a reset.

    The archive has 76 test files mentioning `:seon.turn/agent` and 43 mentioning
    `:seon.cluster.eval/run`, **82 distinct files**, not ~120 distinct files.
    The union excludes unqualified auto-resolved keys and other renamed concepts,
    so it is a lower-bound inventory, not a complete closure. The pinned design
    contains a promise of a script, no script path/body or executable rewrite
    evidence. Searches of `script/` found no implemented session sweep.

    **Required change:** produce a reproducible syntax-aware rewrite/verification
    script and manifest spanning source, script, test, schemas, quoted forms and
    defaults; apply it once in the implementation lane, then hand-fix semantic
    exceptions. Rebuild the phase table around findings 2–4, 6, 8–9. Ensure the
    session namespace schema/renderer exists in P2, the new session resource is
    owned in P3, the graph/web contracts move together in P4, config derivation
    is in P5, and occurrence consumers gain turn→session→agent derivation when
    P7 stops writing direct occurrence agents (`error.clj:2058–2072` currently
    queries the direct agent). P1 and P3 also share `turn.clj`/`eval.clj`: state
    their dependency instead of promising parallel edits after only P2 releases.
    P7's dial rename includes `config/default.edn` and every manifest consumer.

12. **Most §11 stale-source citations are confirmed; two semantic claims need rewriting.**
    The following current definitions match the design's corrected locations;
    no source relocation was inferred from names alone:

    | Owner | Verified current locations |
    |---|---|
    | `src/seon/db.clj` | call-with-custody 390; read-evidence 928; q 2450; pull 2673; write-entity-error 3502; write-owned-values-error 3540; removed-definition-error 4152; deletion-error 4219; write-report-error 4436; transact! 5094 |
    | `src/seon/turn.clj` | system-plan 2099; system-turn 2222; latest-answering-turn-t 3111 |
    | `src/seon/instrument.clj` | collect-contracts! 767; apply! 796 |
    | `src/seon/sci/eval.clj` | regenerate-agent-context! 3246; fork-for-turn 3320; base-ctx 3514 |

    The guide's 1,000 default and the writer docstring's “pull's 1,000 cap” are
    false: pinned `pull_api.cljc:16` sets the default to nil; `:315`, `:323`
    honor an explicit limit. Guide §4's source ranges are stale too. Identity-less
    component partition treatment is correctly cited (`schema.clj:1517–1537`),
    and component reverse cardinality is correctly grounded (`pull_api.cljc:328–329`).
    The “namespace rows never merge” claim needs finding 8's narrower wording.

    “History is not a query” is not a useful correction: traversal, ordering and
    temporal reconstruction remain derived reads even with a stored membership
    edge. The old text needs updating to say **which relationship owns membership**,
    not to ban queries. Likewise update the PRD's obsolete back-ref prescription
    when removing those refs, under the newer owner authority.

    **Required change:** apply these precise documentation corrections in the
    slices that establish the behavior. Do not fix skill line numbers first and
    call target behavior installed. Q1/Q2 gain no new choice from a stale citation.

## Effect on the owner's §14 questions

| Question | Review disposition |
|---|---|
| Q1, turn ownership | Reprice before ruling: B avoids descendant expansion but keeps linear session membership work; A's 1.8 s is a proxy; C in §14 (incremental validation) needs a separate proof/design. The source cannot justify B's constant bound. |
| Q2, historical namespace | Accept symbol value. Preserve the live session ref, old observations during adoption, and program-consumer qualification in finding 8. No need to reopen statement versus token. |
| Q3, duplicate config name | Keep deferred. The base link is correct; resolve stored/input schemas now. Later identity removal must preserve `effective` and cluster selection semantics. |
| Q4, refused settlement namespace | Record where SCI ended when the refusal settlement commits; specify failed-recording/no-form/explicit-move behavior. “~3 lines” is not a complete guarantee. |
| Q5, schedules | Keep the declared reverse attachment. Correct required firing/capture/cluster refs and historical firing-recipient semantics; no forward schedule component is needed for presentation. |

## Reproducible probe evidence

Datahike pin `fe1d4e26edcc251f4471e720504b56353f4c2d09`; Malli pin
`43c6dca8d2d8df3162b13725939954807be4a4ed`. Default basis during the probes was
536879962, with 61 root turns, 121 evaluations and validation-node-limit 250,000.
The live basis remained unchanged after each hypothetical write. Timings are host
JVM observations of the installed writer, not an armed product-turn benchmark,
allocation measurement or parent/change acceptance result.

| Probe | Result |
|---|---|
| Existing turn field write, 61 turns | 7.838, 7.216, 6.044 ms |
| Existing ownership, 2,000 added empty turns | setup 189.388 ms; field writes 107.118, 108.239, 108.464 ms |
| Every root evaluation touched in one `with` | 121 forms, 1,868.948 ms; returned a database value |
| Native turn membership made non-component in a held value, 61 turns | turn writes 2.143, 1.665, 1.911 ms; session writes 1.785, 1.606, 1.587 ms |
| Same counterfactual, 2,061 turns | setup 192.646 ms; turn writes 1.554, 1.944, 1.544 ms; session writes 2.955, 3.031, 2.555 ms |
| Identity-only namespace | admitted; changed-identities `#{}`; explicit digest-map refused the missing definition digest; whole probe 26 ms |

The over-second probe intentionally visits the full evaluation population to
check the design's reported cost. Its proportional work is complete owned-value
reading/decoding/validation in `seon.db/with` → `write-owned-values-error` →
`write-entity-value`. It is evidence of the cost to fix, not an accepted ordinary
operation or proof allocating blame among individual armed calls. No per-function
profile or allocation measurement was taken. Runtime status reported central
cache weight 116,079,532 bytes of 512,000,000; that is not this review's memory cost.

The following is the exact structural experiment, with output reduced to timing
vectors for readability. Run in JVM mode with explicit root/cluster; it only
constructs immutable values. Use `base d` instead of the component-flag `with`
to reproduce the existing-ownership comparison. The synthetic closed turns are
deliberate finite shape probes, not production fixture/test construction.

```clojure
(let [d @(seon.cluster.boot/connection "default")
      a (seon.db/pull d [:db/id {:seon.agent/runtime [:db/id]}]
                     [:seon.agent/id "root"])
      ae (:db/id a)
      re (get-in a [:seon.agent/runtime :db/id])
      t (seon.db/q '[:find ?t . :in $ ?a
                     :where [?t :seon.turn/agent ?a]] d ae)
      base (seon.db/with d [{:db/ident :seon.runtime/turns
                            :db/isComponent false}])
      sample (fn [db tx]
               (let [s (System/nanoTime) v (seon.db/with db tx)]
                 [(/ (- (System/nanoTime) s) 1e6)
                  (seon.db/database-value? v)]))
      tx (into [] (mapcat
                   (fn [n]
                     (let [id (str "review-turn-" n)]
                       [{:db/id id :seon.turn/id id :seon.turn/agent ae
                         :seon.turn/opened-tx "datomic.tx"
                         :seon.turn/closed-tx "datomic.tx"}
                        [:db/add re :seon.runtime/turns id]])))
               (range 2000))
      grown (seon.db/with base tx)]
  (mapv (fn [[n db]]
          [n
           (mapv (fn [_] (sample db [[:db/add t :seon.turn/reply-size 99]])) (range 3))
           (mapv (fn [_] (sample db [[:db/add re :seon.runtime/agent ae]])) (range 3))
           (count (:seon.runtime/turns
                    (#'seon.db/write-entity-value
                     db (seon.db/carried-projection db) {} re)))])
        [[61 base] [2061 grown]]))
```

Full counterfactual result: MCP artifact
`473d73ba4d9384576e2848251e6cd2618568521a7c18ed72dfad3d821843b405`.
For the evaluation-population probe, query root's evaluation eids via
`agent/id → turn/agent → cluster.eval/run`, then time
`(seon.db/with d (mapv (fn [e] [:db/add e :seon.eval/duration-ms 1]) es))`.
For the namespace probe, let `scratch` be
`(seon.db/with d [{:seon.ns/name 'review.session.scratch}])`; call
`(seon.program/changed-identities d scratch {:seon.program/max-datoms 10000})`
and `(seon.program/digest-map scratch {:seon.program/max-datoms 10000
:seon.program/identities #{[:seon.ns/name 'review.session.scratch]}})`.
An initial inspection mistakenly checked `with` for a report's `:db-after`;
`with` returns the database itself (`db.clj:4902–4915`). That check was corrected
and the field pulled back. One mistyped probe failed parsing before evaluation;
the corrected form produced the population timing above.

## Landing and root's central verification

Only this review is changed; net source/test lines **0/0**. No worktree, another
lane's session, or another lane's files were modified. The in-flight graph edits
(`render/walk.clj`, `render/web.clj`, their graph schema/web tests and CSS/JS),
`.gitmodules`, CLM/http-kit work, `docs/prds/next-turn/`, `src-clm/`, and the deleted
tmp patch are foreign. They were excluded by `git archive`. The final check used
HEAD `40c7b0727ecdcaca30c2fc8f51da23a5649c5523` plus only this review; that HEAD
differs from the source pin only in the newer design, which this review does not
purport to approve.
Existing runtime faults and script contract findings below remain foreign
boundaries, not evidence against the review or reasons to stop it.

Archive checks (the review adds no executable declarations):

- `clj-kondo --cache false --lint src script test`: **0 errors, 963 existing
  warnings**, 6,747 ms on the final archive, exit 2 for warnings. This meets zero
  errors, not zero findings. Log:
  `tmp/repl-session-entity-review-astra-20260927/lint-final.txt`.
- Direct `seon.contracts-compile-test/check` on 485 archive src/script/test
  files: 1,833 ms on the final archive, **0 source/test contract findings; 10 existing script
  findings**. The checker was called as a function, not through `deftest` or a
  test request. The findings are schema-symbol canonicalization in
  `script/seon/dev/docstring.clj:285,318,334,360` and
  `script/seon/dev/markdown.clj:1020,1050,1090,1124,1170,1215`, also recorded in
  the earlier config-scope review. The initial archive check took 2,238 ms and
  returned the same findings; full evidence: MCP artifact
  `ec17d64e19e66cb25f7e95bf4b28f8f19ca212367f300aeb6173f99ed4a40d26`.
  The broader compile check is **not green**. No foreign source was changed to
  conceal it. There are no touched contracts/schema/config resources to compile.

No behavioral tests are needed for this documentation-only diff. After the
design is revised and implemented, root should run these existing exact members
centrally, plus the new R1–R11 behaviors (not yet installed test symbols):

- `seon.owned-value-test/owned-values-validate-child-only-writes-and-before-owners`
- `seon.owned-value-test/incomplete-unowned-cyclic-and-shared-components-refuse`
- `seon.owned-value-test/complete-values-include-the-1001st-child`
- `seon.owned-value-test/the-projection-carries-the-declared-work-bound`
- `seon.owned-value-test/every-canonical-owned-relation-declares-its-child-schema`
- `seon.cluster.agent-test/system-source-submission-uses-the-ordinary-durable-run`
- `seon.cluster.turn-test/generated-opening-preserves-one-provider-turn-budget`
- `seon.cluster.turn-test/a-run-prompts-from-its-opening-database-value`
- `seon.cluster.message-test/reverse-agent-concerns-are-schema-declarations`
- `seon.render.walk-test/a-truncated-connection-is-reported-whichever-bound-cut-it`
- `seon.render.web-debug-test/reverse-blocks-do-not-depend-on-the-graph-page`
- `seon.render.web-debug-test/agent-identity-groups-scalars-and-keeps-declared-components`
- `seon.render.web-debug-test/saved-history-preserves-shown-text-with-numeric-lookups`
- `seon.context-capture-test/context-contributions-reference-existing-evaluations`
- `seon.config-test/cluster-scoped-config-refuses-an-omitted-cluster`

The new regressions must additionally cover the forms-key collision, empty plan
and session lifecycle, sparse settings requests, required-ref sweeps, unhandled
versus unanswered messages, post-compaction temporal reads, namespace adoption
on old data, turn membership/ordinal integrity, and failed-exit oversight. Root
owns the armed timings and schema adoption/restart proof; none was run here.
