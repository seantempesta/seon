---
type: research
status: active
tags: [database, datahike, research]
---

# Seon database call-site sweep plan — 2026-08-03

## Decision and scope

This is the mechanical execution plan for ruling #41's quiet-window call-site
sweep. It classifies the live worktree; it does not inherit the ruling-time
counts and it makes no production changes.

Census basis:

- Seon HEAD observed with the final census:
  7a09aaa310c69feb71b17ddf41e955ef8bf14314.
- Datahike root gitlink and checked-out revision:
  0e8601d7f2f68c01070e13a95483bc82be04cabc.
- Datahike interface authority:
  reference-code/datahike/src/datahike/api/specification.cljc and
  reference-code/datahike/src/datahike/api/impl.cljc.
- Seon boundary authority: src/seon/db.clj and test/seon/db_test.clj.
- Census command: clj-kondo var-usage analysis over src and test, selecting
  usages whose resolved target namespace is datahike.api, then de-duplicating
  the JVM/CLJS duplicate analysis entries for the one remaining .cljc file.
- Text-only dynamic resolution was checked separately because clj-kondo does
  not report quoted requiring-resolve targets.
- The worktree was dirty throughout this research. Every pre-existing dirty
  and untracked path was treated as protected. This report is the only path
  this lane writes.

The source result is 33 namespaces and 274 physical analyzer-resolved calls,
plus the quoted datahike.api/transact in seon.test.runner/record! at
src/seon/test/runner.clj:373. The effective source census is therefore 275
direct dependency calls. The analyzer function counts are: q 139, pull 43,
pull-many 4, datoms 6, entity 1, db 4, history 4, as-of 4, since 3, transact
16, and 50 lifecycle/internal calls. One transact is seon.db's implementation;
the quoted call makes 16 bypassing writes outside seon.db, exactly the current
write total that matters for the sweep.

The old 34-namespace count is now 33. The old q/pull figures are also stale:
outside seon.db there are 138 physical d/q sites and 43 d/pull sites. The raw
clj-kondo count before physical de-duplication was 280 because six
seon.reconcile .cljc usages were emitted once per platform analysis.

## Execution divergence log

- 2026-08-03 quiet-window preflight at Seon HEAD `8f8c4d72d` retained the
  pinned Datahike revision `0e8601d7f`. Source analysis found 273 direct calls
  rather than 274: `seon.render.walk/neighborhood` no longer calls `d/q`, so
  that row is 14 calls (`q` ×9) and the source totals are 138 `q` calls plus
  273 analyzer-resolved calls (274 including the quoted runner transaction).
- The same preflight found 947 test calls rather than 940.
  `seon.cluster.message-test` gained the HISTORY-OFF production-path falsifier
  (25 calls: `connect` ×1, `create-database` ×1, `delete-database` ×1,
  `pull` ×1, `q` ×10, `release` ×1, `transact` ×10), while
  `seon.render.walk-test` now has 28 calls (`pull` ×14, `q` ×3,
  `transact` ×11). No new semantic class was introduced: the added lifecycle
  calls remain raw fixture custody and its reads/writes follow the ordinary
  explicit-custody test rule.
- `src/seon/sci/eval.clj` carried the protected renderer-kernel prototype at
  execution start. Its one planned core-read row is deferred so no prototype
  hunk enters this lane's commits; the final census must report that exact
  non-exempt remainder.

## Current source census

Every physical analyzer-resolved source call follows. A line names the caller
function so repeated calls in one namespace remain distinguishable.

| Namespace / file | Direct call sites | Total |
|---|---|---:|
| seon.ai<br>src/seon/ai.clj | d/pull: [119](../../../../src/seon/ai.clj#L119) (agent-overlay) | 1 |
| seon.bootstrap<br>src/seon/bootstrap.clj | d/pull: [81](../../../../src/seon/bootstrap.clj#L81) (population-tx); d/q: [111](../../../../src/seon/bootstrap.clj#L111) (ordered-plan-rows), [164](../../../../src/seon/bootstrap.clj#L164) (agent-sources) | 3 |
| seon.bootstrap-drive<br>src/seon/bootstrap_drive.clj | d/q: [109](../../../../src/seon/bootstrap_drive.clj#L109) (objective-run-ids), [121](../../../../src/seon/bootstrap_drive.clj#L121) (candidate-functions), [191](../../../../src/seon/bootstrap_drive.clj#L191) (public-my-message-count), [212](../../../../src/seon/bootstrap_drive.clj#L212) (messages-between?), [224](../../../../src/seon/bootstrap_drive.clj#L224) (grade-o4); d/release: [371](../../../../src/seon/bootstrap_drive.clj#L371) (one-drive!) | 6 |
| seon.cluster<br>src/seon/cluster.clj | d/pull: [480](../../../../src/seon/cluster.clj#L480) (schema-row-changes), [998](../../../../src/seon/cluster.clj#L998) (ensure-cluster-entity!); d/q: [463](../../../../src/seon/cluster.clj#L463) (missing-process-rows), [494](../../../../src/seon/cluster.clj#L494) (instruction-row-changes), [507](../../../../src/seon/cluster.clj#L507) (instruction-row-changes), [635](../../../../src/seon/cluster.clj#L635) (count-installed), [649](../../../../src/seon/cluster.clj#L649) (program-currentness), [905](../../../../src/seon/cluster.clj#L905) (recover-runs!), [957](../../../../src/seon/cluster.clj#L957) (ensure-cluster-entity!), [1043](../../../../src/seon/cluster.clj#L1043) (ensure-entity-call), [1140](../../../../src/seon/cluster.clj#L1140) (tagged-run), [1686](../../../../src/seon/cluster.clj#L1686) (readiness); d/release: [1779](../../../../src/seon/cluster.clj#L1779) (stop!); d/transact: [528](../../../../src/seon/cluster.clj#L528) (accrete-schema-population!), [531](../../../../src/seon/cluster.clj#L531) (accrete-schema-population!), [534](../../../../src/seon/cluster.clj#L534) (accrete-schema-population!), [560](../../../../src/seon/cluster.clj#L560) (populate-source!), [569](../../../../src/seon/cluster.clj#L569) (populate-source!), [924](../../../../src/seon/cluster.clj#L924) (recover-runs!) | 19 |
| seon.cluster.agent<br>src/seon/cluster/agent.clj | d/as-of: [463](../../../../src/seon/cluster/agent.clj#L463) (await-turn-completion!); d/listen: [454](../../../../src/seon/cluster/agent.clj#L454) (await-turn-completion!); d/q: [107](../../../../src/seon/cluster/agent.clj#L107) (owner-of), [153](../../../../src/seon/cluster/agent.clj#L153) (held-run-id), [369](../../../../src/seon/cluster/agent.clj#L369) (arm!), [433](../../../../src/seon/cluster/agent.clj#L433) (provider-call-capture-basis), [582](../../../../src/seon/cluster/agent.clj#L582) (armer-step); d/unlisten: [500](../../../../src/seon/cluster/agent.clj#L500) (await-turn-completion!) | 8 |
| seon.cluster.export<br>src/seon/cluster/export.clj | d/branch!: [144](../../../../src/seon/cluster/export.clj#L144) (retransact!); d/branches: [135](../../../../src/seon/cluster/export.clj#L135) (retransact!); d/connect: [141](../../../../src/seon/cluster/export.clj#L141) (retransact!), [157](../../../../src/seon/cluster/export.clj#L157) (retransact!); d/create-database: [140](../../../../src/seon/cluster/export.clj#L140) (retransact!); d/release: [154](../../../../src/seon/cluster/export.clj#L154) (retransact!), [162](../../../../src/seon/cluster/export.clj#L162) (retransact!), [164](../../../../src/seon/cluster/export.clj#L164) (retransact!) | 8 |
| seon.cluster.instruction<br>src/seon/cluster/instruction.clj | d/q: [48](../../../../src/seon/cluster/instruction.clj#L48) (toolkit-namespaces) | 1 |
| seon.cluster.loop<br>src/seon/cluster/loop.clj | d/pull: [342](../../../../src/seon/cluster/loop.clj#L342) (capability-free-references?), [366](../../../../src/seon/cluster/loop.clj#L366) (exact-session-row-tx), [461](../../../../src/seon/cluster/loop.clj#L461) (session-image-tx), [1013](../../../../src/seon/cluster/loop.clj#L1013) (form-data), [1038](../../../../src/seon/cluster/loop.clj#L1038) (admitted-form), [1611](../../../../src/seon/cluster/loop.clj#L1611) (close-turn); d/q: [135](../../../../src/seon/cluster/loop.clj#L135) (available-functions), [472](../../../../src/seon/cluster/loop.clj#L472) (result-blob-threshold), [500](../../../../src/seon/cluster/loop.clj#L500) (result-window-page-size), [853](../../../../src/seon/cluster/loop.clj#L853) (attempts), [1006](../../../../src/seon/cluster/loop.clj#L1006) (form-data) | 11 |
| seon.cluster.message<br>src/seon/cluster/message.clj | d/q: [78](../../../../src/seon/cluster/message.clj#L78) (trigger), [89](../../../../src/seon/cluster/message.clj#L89) (caused-by), [127](../../../../src/seon/cluster/message.clj#L127) (sender), [190](../../../../src/seon/cluster/message.clj#L190) (agent-exists?), [218](../../../../src/seon/cluster/message.clj#L218) (identified-entities), [452](../../../../src/seon/cluster/message.clj#L452) (render-ai) | 6 |
| seon.cluster.registry<br>src/seon/cluster/registry.clj | d/branch!: [182](../../../../src/seon/cluster/registry.clj#L182) (branch!); d/branch-as-db: [303](../../../../src/seon/cluster/registry.clj#L303) (branch-blobs); d/branches: [111](../../../../src/seon/cluster/registry.clj#L111) (roster); d/delete-branch!: [279](../../../../src/seon/cluster/registry.clj#L279) (retire-branch!); d/gc-storage: [353](../../../../src/seon/cluster/registry.clj#L353) (collect!); d/q: [295](../../../../src/seon/cluster/registry.clj#L295) (blob-digest-attributes), [313](../../../../src/seon/cluster/registry.clj#L313) (branch-blobs); d/release-materialized-db: [319](../../../../src/seon/cluster/registry.clj#L319) (branch-blobs) | 8 |
| seon.cluster.run<br>src/seon/cluster/run.clj | d/datoms: [598](../../../../src/seon/cluster/run.clj#L598) (current-schema-data-attributes); d/pull: [178](../../../../src/seon/cluster/run.clj#L178) (current-run), [213](../../../../src/seon/cluster/run.clj#L213) (running-receipts), [247](../../../../src/seon/cluster/run.clj#L247) (open-call), [254](../../../../src/seon/cluster/run.clj#L254) (open-call), [365](../../../../src/seon/cluster/run.clj#L365) (close-call), [469](../../../../src/seon/cluster/run.clj#L469) (current-receipt), [676](../../../../src/seon/cluster/run.clj#L676) (program-row-tx), [692](../../../../src/seon/cluster/run.clj#L692) (program-row-tx), [694](../../../../src/seon/cluster/run.clj#L694) (program-row-tx), [854](../../../../src/seon/cluster/run.clj#L854) (receipt-refusal-call), [857](../../../../src/seon/cluster/run.clj#L857) (receipt-refusal-call), [931](../../../../src/seon/cluster/run.clj#L931) (recover-call); d/q: [209](../../../../src/seon/cluster/run.clj#L209) (running-receipts), [402](../../../../src/seon/cluster/run.clj#L402) (plan-call), [968](../../../../src/seon/cluster/run.clj#L968) (run-forms), [975](../../../../src/seon/cluster/run.clj#L975) (run-receipts) | 17 |
| seon.cluster.source<br>src/seon/cluster/source.clj | d/commit-id: [172](../../../../src/seon/cluster/source.clj#L172) (publish!); d/entity: [245](../../../../src/seon/cluster/source.clj#L245) (upsert!); d/force-branch!: [168](../../../../src/seon/cluster/source.clj#L168) (publish!), [256](../../../../src/seon/cluster/source.clj#L256) (upsert!); d/q: [235](../../../../src/seon/cluster/source.clj#L235) (upsert!); d/release: [183](../../../../src/seon/cluster/source.clj#L183) (publish!), [259](../../../../src/seon/cluster/source.clj#L259) (upsert!); d/transact: [151](../../../../src/seon/cluster/source.clj#L151) (publish!), [161](../../../../src/seon/cluster/source.clj#L161) (publish!), [246](../../../../src/seon/cluster/source.clj#L246) (upsert!) | 10 |
| seon.cluster.store<br>src/seon/cluster/store.clj | d/branches: [394](../../../../src/seon/cluster/store.clj#L394) (open-branch!); d/connect: [333](../../../../src/seon/cluster/store.clj#L333) (open-store!), [405](../../../../src/seon/cluster/store.clj#L405) (open-branch!); d/create-database: [262](../../../../src/seon/cluster/store.clj#L262) (create-store!); d/database-exists?: [302](../../../../src/seon/cluster/store.clj#L302) (open-store!); d/db: [340](../../../../src/seon/cluster/store.clj#L340) (open-store!); d/release: [342](../../../../src/seon/cluster/store.clj#L342) (open-store!), [368](../../../../src/seon/cluster/store.clj#L368) (release-store!) | 8 |
| seon.cluster.wake<br>src/seon/cluster/wake.clj | d/listen: [203](../../../../src/seon/cluster/wake.clj#L203) (route!); d/unlisten: [236](../../../../src/seon/cluster/wake.clj#L236) (unlisten!) | 2 |
| seon.cluster.work<br>src/seon/cluster/work.clj | d/pull: [300](../../../../src/seon/cluster/work.clj#L300) (form-settlement); d/q: [76](../../../../src/seon/cluster/work.clj#L76) (agent-run), [97](../../../../src/seon/cluster/work.clj#L97) (next-ordinal), [105](../../../../src/seon/cluster/work.clj#L105) (next-ordinal), [192](../../../../src/seon/cluster/work.clj#L192) (resume-artifact?), [210](../../../../src/seon/cluster/work.clj#L210) (form-owner), [218](../../../../src/seon/cluster/work.clj#L218) (form-owner), [236](../../../../src/seon/cluster/work.clj#L236) (form-receipt), [247](../../../../src/seon/cluster/work.clj#L247) (form-run-id), [259](../../../../src/seon/cluster/work.clj#L259) (assignment-facts), [266](../../../../src/seon/cluster/work.clj#L266) (assignment-facts), [273](../../../../src/seon/cluster/work.clj#L273) (assignment-facts), [283](../../../../src/seon/cluster/work.clj#L283) (assignment-facts), [341](../../../../src/seon/cluster/work.clj#L341) (plan-settlement), [375](../../../../src/seon/cluster/work.clj#L375) (outside-trigger?), [403](../../../../src/seon/cluster/work.clj#L403) (episode-runs), [414](../../../../src/seon/cluster/work.clj#L414) (episode-runs), [430](../../../../src/seon/cluster/work.clj#L430) (max-episode-runs), [446](../../../../src/seon/cluster/work.clj#L446) (latest-closed-run), [470](../../../../src/seon/cluster/work.clj#L470) (lint-refusal-continuation-trigger), [477](../../../../src/seon/cluster/work.clj#L477) (lint-refusal-continuation-trigger), [626](../../../../src/seon/cluster/work.clj#L626) (unanswered-triggers) | 22 |
| seon.config<br>src/seon/config.clj | d/pull: [281](../../../../src/seon/config.clj#L281) (effective) | 1 |
| seon.db<br>src/seon/db.clj | d/as-of: [59](../../../../src/seon/db.clj#L59) (database-value-generator), [674](../../../../src/seon/db.clj#L674) (as-of), [676](../../../../src/seon/db.clj#L676) (as-of); d/connect: [48](../../../../src/seon/db.clj#L48) (fresh-connection); d/create-database: [47](../../../../src/seon/db.clj#L47) (fresh-connection); d/datoms: [608](../../../../src/seon/db.clj#L608) (datoms-call); d/db: [98](../../../../src/seon/db.clj#L98) (resolve-database-value), [700](../../../../src/seon/db.clj#L700) (panic-on-core-error?); d/history: [61](../../../../src/seon/db.clj#L61) (database-value-generator), [661](../../../../src/seon/db.clj#L661) (history), [663](../../../../src/seon/db.clj#L663) (history); d/pull-many-with-evidence: [546](../../../../src/seon/db.clj#L546) (pull-many), [552](../../../../src/seon/db.clj#L552) (pull-many), [556](../../../../src/seon/db.clj#L556) (pull-many), [561](../../../../src/seon/db.clj#L561) (pull-many); d/pull-with-evidence: [508](../../../../src/seon/db.clj#L508) (pull), [514](../../../../src/seon/db.clj#L514) (pull), [518](../../../../src/seon/db.clj#L518) (pull), [523](../../../../src/seon/db.clj#L523) (pull), [570](../../../../src/seon/db.clj#L570) (entity-call); d/q: [698](../../../../src/seon/db.clj#L698) (panic-on-core-error?); d/q-with-evidence: [461](../../../../src/seon/db.clj#L461) (q); d/query-input-count: [401](../../../../src/seon/db.clj#L401) (aligned-query-arguments); d/query-source-bindings: [402](../../../../src/seon/db.clj#L402) (aligned-query-arguments); d/since: [60](../../../../src/seon/db.clj#L60) (database-value-generator), [687](../../../../src/seon/db.clj#L687) (since), [689](../../../../src/seon/db.clj#L689) (since); d/transact: [716](../../../../src/seon/db.clj#L716) (transact-call) | 28 |
| seon.error<br>src/seon/error.clj | d/q: [641](../../../../src/seon/error.clj#L641) (agent-exists?), [653](../../../../src/seon/error.clj#L653) (entity-exists?), [665](../../../../src/seon/error.clj#L665) (recurrence) | 3 |
| seon.eval.drive<br>src/seon/eval/drive.clj | d/commit-id: [291](../../../../src/seon/eval/drive.clj#L291) (run-episode!); d/listen: [58](../../../../src/seon/eval/drive.clj#L58) (await-fact!); d/pull: [171](../../../../src/seon/eval/drive.clj#L171) (model-attempts), [195](../../../../src/seon/eval/drive.clj#L195) (run-records); d/q: [34](../../../../src/seon/eval/drive.clj#L34) (bootstrap-complete?), [41](../../../../src/seon/eval/drive.clj#L41) (bootstrap-complete?), [92](../../../../src/seon/eval/drive.clj#L92) (inbound!), [104](../../../../src/seon/eval/drive.clj#L104) (objective-run-ids), [127](../../../../src/seon/eval/drive.clj#L127) (run-receipts), [165](../../../../src/seon/eval/drive.clj#L165) (model-attempts), [211](../../../../src/seon/eval/drive.clj#L211) (terminal-state); d/transact: [88](../../../../src/seon/eval/drive.clj#L88) (inbound!); d/unlisten: [73](../../../../src/seon/eval/drive.clj#L73) (await-fact!) | 13 |
| seon.fn<br>src/seon/fn.clj | d/pull: [664](../../../../src/seon/fn.clj#L664) (backfill-contract-facts!); d/q: [652](../../../../src/seon/fn.clj#L652) (backfill-contract-facts!), [733](../../../../src/seon/fn.clj#L733) (index!); d/transact: [681](../../../../src/seon/fn.clj#L681) (backfill-contract-facts!), [762](../../../../src/seon/fn.clj#L762) (index!) | 5 |
| seon.oversight<br>src/seon/oversight.clj | d/committed-value-identity: [53](../../../../src/seon/oversight.clj#L53) (connection-identity); d/q: [41](../../../../src/seon/oversight.clj#L41) (cluster-name), [81](../../../../src/seon/oversight.clj#L81) (current-run-id) | 3 |
| seon.problems<br>src/seon/problems.clj | d/q: [76](../../../../src/seon/problems.clj#L76) (error-signatures), [96](../../../../src/seon/problems.clj#L96) (wedged-runs), [113](../../../../src/seon/problems.clj#L113) (failed-runs), [128](../../../../src/seon/problems.clj#L128) (errored-receipts), [162](../../../../src/seon/problems.clj#L162) (form-problem), [180](../../../../src/seon/problems.clj#L180) (form-problem), [231](../../../../src/seon/problems.clj#L231) (deferred-agents), [247](../../../../src/seon/problems.clj#L247) (unowned-namespaces) | 8 |
| seon.reconcile<br>src/seon/reconcile.cljc | d/history: [119](../../../../src/seon/reconcile.cljc#L119) (first-assertion-transactions); d/pull: [339](../../../../src/seon/reconcile.cljc#L339) (plan); d/q: [106](../../../../src/seon/reconcile.cljc#L106) (current-identity-facts), [128](../../../../src/seon/reconcile.cljc#L128) (first-assertion-transactions), [141](../../../../src/seon/reconcile.cljc#L141) (process-by-transaction); d/transact: [423](../../../../src/seon/reconcile.cljc#L423) (reconcile!) | 6 |
| seon.render<br>src/seon/render.clj | d/db: [119](../../../../src/seon/render.clj#L119) (ambient-database-value); d/pull: [142](../../../../src/seon/render.clj#L142) (repl-state); d/q: [123](../../../../src/seon/render.clj#L123) (custody-cluster-name), [135](../../../../src/seon/render.clj#L135) (repl-state) | 4 |
| seon.render.agent<br>src/seon/render/agent.clj | d/pull: [94](../../../../src/seon/render/agent.clj#L94) (agent-header-html), [218](../../../../src/seon/render/agent.clj#L218) (transcript-entry); d/q: [105](../../../../src/seon/render/agent.clj#L105) (agent-header-html), [124](../../../../src/seon/render/agent.clj#L124) (agent-entity-id), [134](../../../../src/seon/render/agent.clj#L134) (transcript-entity-ids), [138](../../../../src/seon/render/agent.clj#L138) (transcript-entity-ids), [142](../../../../src/seon/render/agent.clj#L142) (transcript-entity-ids), [146](../../../../src/seon/render/agent.clj#L146) (transcript-entity-ids), [152](../../../../src/seon/render/agent.clj#L152) (transcript-entity-ids), [156](../../../../src/seon/render/agent.clj#L156) (transcript-entity-ids) | 10 |
| seon.render.block<br>src/seon/render/block.clj | d/pull: [301](../../../../src/seon/render/block.clj#L301) (entity-unit) | 1 |
| seon.render.ns<br>src/seon/render/ns.clj | d/pull: [44](../../../../src/seon/render/ns.clj#L44) (namespace-row), [81](../../../../src/seon/render/ns.clj#L81) (schema-row); d/q: [52](../../../../src/seon/render/ns.clj#L52) (function-rows), [65](../../../../src/seon/render/ns.clj#L65) (own-schema-rows) | 4 |
| seon.render.root<br>src/seon/render/root.clj | d/q: [79](../../../../src/seon/render/root.clj#L79) (header-html), [81](../../../../src/seon/render/root.clj#L81) (header-html), [82](../../../../src/seon/render/root.clj#L82) (header-html), [107](../../../../src/seon/render/root.clj#L107) (agents-html), [141](../../../../src/seon/render/root.clj#L141) (messages-html) | 5 |
| seon.render.transcript<br>src/seon/render/transcript.clj | d/pull: [223](../../../../src/seon/render/transcript.clj#L223) (about-identities); d/pull-many: [181](../../../../src/seon/render/transcript.clj#L181) (pulled-many), [211](../../../../src/seon/render/transcript.clj#L211) (about-identities); d/q: [65](../../../../src/seon/render/transcript.clj#L65) (message-count), [78](../../../../src/seon/render/transcript.clj#L78) (receipt-count), [95](../../../../src/seon/render/transcript.clj#L95) (recent-message-rows), [111](../../../../src/seon/render/transcript.clj#L111) (recent-receipt-rows), [128](../../../../src/seon/render/transcript.clj#L128) (pinned-receipt-ids), [167](../../../../src/seon/render/transcript.clj#L167) (form-sources), [477](../../../../src/seon/render/transcript.clj#L477) (reasoning-attempts) | 10 |
| seon.render.walk<br>src/seon/render/walk.clj | d/datoms: [245](../../../../src/seon/render/walk.clj#L245) (reverse-refs), [368](../../../../src/seon/render/walk.clj#L368) (entity-last-changed); d/pull: [192](../../../../src/seon/render/walk.clj#L192) (concrete-entity), [359](../../../../src/seon/render/walk.clj#L359) (eid-of); d/pull-many: [426](../../../../src/seon/render/walk.clj#L426) (transcript-member-eids); d/q: [274](../../../../src/seon/render/walk.clj#L274) (trigger-message-edges), [377](../../../../src/seon/render/walk.clj#L377) (transcript-entity-ids), [381](../../../../src/seon/render/walk.clj#L381) (transcript-entity-ids), [385](../../../../src/seon/render/walk.clj#L385) (transcript-entity-ids), [389](../../../../src/seon/render/walk.clj#L389) (transcript-entity-ids), [395](../../../../src/seon/render/walk.clj#L395) (transcript-entity-ids), [401](../../../../src/seon/render/walk.clj#L401) (transcript-entity-ids), [441](../../../../src/seon/render/walk.clj#L441) (assigned-namespace-eid), [681](../../../../src/seon/render/walk.clj#L681) (neighborhood) | 14 |
| seon.render.web<br>src/seon/render/web.clj | d/datoms: [401](../../../../src/seon/render/web.clj#L401) (direct-attribute), [418](../../../../src/seon/render/web.clj#L418) (generic-entity); d/pull: [522](../../../../src/seon/render/web.clj#L522) (unsettled-stream?), [1180](../../../../src/seon/render/web.clj#L1180) (data-response); d/q: [427](../../../../src/seon/render/web.clj#L427) (generic-entity), [508](../../../../src/seon/render/web.clj#L508) (coalesce-floor), [910](../../../../src/seon/render/web.clj#L910) (agent-exists?), [983](../../../../src/seon/render/web.clj#L983) (namespace-exists?), [990](../../../../src/seon/render/web.clj#L990) (agent-namespace), [1000](../../../../src/seon/render/web.clj#L1000) (current-cluster-name), [1334](../../../../src/seon/render/web.clj#L1334) (start!); d/transact: [944](../../../../src/seon/render/web.clj#L944) (inbound), [1338](../../../../src/seon/render/web.clj#L1338) (start!) | 13 |
| seon.schema<br>src/seon/schema.clj | d/q: [483](../../../../src/seon/schema.clj#L483) (admission-from-asserting-transaction), [1749](../../../../src/seon/schema.clj#L1749) (projection-from-database), [1755](../../../../src/seon/schema.clj#L1755) (projection-from-database), [1761](../../../../src/seon/schema.clj#L1761) (projection-from-database) | 4 |
| seon.sci.eval<br>src/seon/sci/eval.clj | d/pull: [969](../../../../src/seon/sci/eval.clj#L969) (install-program-row!), [1030](../../../../src/seon/sci/eval.clj#L1030) (install-program-row!), [1169](../../../../src/seon/sci/eval.clj#L1169) (acquire!), [1326](../../../../src/seon/sci/eval.clj#L1326) (install-session-image!); d/pull-many: [1321](../../../../src/seon/sci/eval.clj#L1321) (install-session-image!); d/q: [929](../../../../src/seon/sci/eval.clj#L929) (instrumentation-config), [1099](../../../../src/seon/sci/eval.clj#L1099) (program-documentation), [1152](../../../../src/seon/sci/eval.clj#L1152) (acquire!), [1181](../../../../src/seon/sci/eval.clj#L1181) (acquire!), [1193](../../../../src/seon/sci/eval.clj#L1193) (acquire!), [1317](../../../../src/seon/sci/eval.clj#L1317) (install-session-image!) | 11 |

One additional direct call is invisible to var-usage analysis:
src/seon/test/runner.clj:373 dynamically resolves datahike.api/transact from
seon.test.runner/record!.

## The transaction boundary being substituted

Datahike's synchronous transact dereferences the writer result and throws every
rejection at the call site
(reference-code/datahike/src/datahike/api/impl.cljc:30-48).
seon.db/transact! instead has four semantic results:

1. a successful Datahike transaction report;
2. a Seon transition's own flat error value;
3. a flat :seon.db/rejected value retaining Datahike's classified refusal;
4. a flat :seon.db/unknown-failure value.

The fourth result still rethrows in development when
:seon.config/on-core-error is :panic. That is the existing loud-development
exception to the ordinary never-throw boundary. The sweep must not describe
unknown core failures as guaranteed values in panic mode.

The migration hazard is not transaction shape: every current site already
uses a Datahike-supported vector/sequence or {:tx-data ... :tx-meta ...} map.
The hazard is control flow. Today every rejection aborts the caller. After a
token-only rename, a flat error map is truthy and many callers would continue
as if the commit succeeded. The following table is the required
caller-by-caller decision.

## Direct write-site classification and required control flow

| Current site | Class | Failure today | Required behavior with seon.db/transact! |
|---|---|---|---|
| src/seon/cluster.clj:528, 531, 534 — accrete-schema-population! | Boot | Any schema/process/schema-row rejection throws through stack-tower!; cluster/start! wraps it as :seon.boot/refused while preserving the degraded instance and live REPL. | Call db/transact! with the explicit connection and pass every result through the existing boot refusal mechanism. A flat error must become the same loud boot refusal before the next population phase runs. |
| src/seon/cluster.clj:560, 569 — populate-source! | Boot / initialization publication | A rejected bootstrap or instruction transaction throws through the population function into source/publish!, whose catch retires the scratch branch and rethrows. | Keep the explicit connection. Inspect each result and throw/refuse on any :seon.error/kind so source/publish!'s existing scratch cleanup runs. Never invoke seon.fn/index! after a failed population write. |
| src/seon/cluster.clj:924 — recover-runs! | Boot | Rejection throws through stack-tower! and aborts boot before config, root creation, or arming. | Keep the explicit connection. Convert any flat error into the same boot refusal. Do not return recovered counts after a refused recovery transaction. |
| src/seon/cluster/source.clj:151, 161 — publish! | Boot / explicit initialization | Rejection throws to publish!'s outer catch; retire-scratch! runs and the candidate head is never published. | Keep the explicit scratch connection. Check both results and throw/refuse before population or force-branch!. Preserve the outer catch as the one cleanup path. |
| src/seon/cluster/source.clj:246 — upsert! | Boot / incremental initialization | Rejection throws to upsert!'s outer catch; scratch retirement runs and force-branch! is skipped. | Keep the explicit scratch connection. Check the result and throw/refuse before force-branch!. Returning a published result after an error value is forbidden. |
| src/seon/fn.clj:681 — backfill-contract-facts! | Boot / initialization maintenance | Rejection throws to its caller; the function cannot return a false converged result. | Keep the explicit connection. A flat error must abort rather than return {:seon.reconcile/converged? false ...}. Preserve transaction metadata and the current no-op-on-empty behavior. |
| src/seon/fn.clj:762 — index!'s local transact! helper | Boot / initialization publication | Any of the helper's up to three ordered transactions throws; later identity-dependent phases do not run and source publication cleans the scratch branch. | Rename the local helper to avoid shadowing seon.db/transact!, call db/transact! explicitly, and throw/refuse on every error result. Preserve the three-phase identity-before-reference order. |
| src/seon/render/web.clj:944 — inbound | Runtime | A writer rejection escapes the Ring handler/http-kit call instead of returning 204; no success response is produced. The pre-read refusal path returns 422. | Keep the explicit service connection: this is system HTTP work, not an agent evaluation. A transition or Datahike refusal returns 422 with the flat error message; an unknown core failure returns 500 in production and still follows the panic dial in development. Return 204 only when the result is a transaction report. |
| src/seon/render/web.clj:1338 — start! process row | Boot | Rejection throws before the server is created; seon.cluster's serve layer fails and cluster/start! reports a boot refusal with the REPL alive. | Keep the explicit connection. Turn a flat error back into a boot failure before allocating the worker executor or binding a port. |
| src/seon/reconcile.cljc:423 — reconcile! | Runtime and boot (shared) | Rejection throws. At boot this aborts config application and the tower; during explicit config apply it throws to the operator caller. | Keep the explicit connection. Return the flat error value from reconcile! for runtime callers and widen its honest return contract; do not manufacture a converged result. The boot call in config/apply-compiled! must explicitly turn that error value into the existing boot refusal so boot cannot continue with unapplied config. |
| src/seon/eval/drive.clj:88 — inbound! | Fixture / Inspect evaluation harness | Rejection throws through run-episode! and run-sample!; the sample cleanup stops the instance and retires its branches. | Keep the explicit connection. Detect the flat error and throw an ex-info carrying it so the sample remains failed and the existing cleanup runs. Do not fall through to the identity query. |
| src/seon/test/runner.clj:373 — record! dynamic call | Fixture / test runner | Rejection throws; record!'s finally stops the cluster and the runner exits abnormally rather than claiming the run was recorded. | Replace requiring-resolve with the ordinary seon.db require and explicit db/transact!. Detect a flat error and throw so finally still stops the instance and no recorded-count success map is returned. |

There are 16 bypassing write expressions: six in seon.cluster, three in
seon.cluster.source, two in seon.fn, two in seon.render.web, and one each in
seon.reconcile, seon.eval.drive, and seon.test.runner. The seon.fn index helper
may invoke its one expression three times; the classification is per direct
site, while the control-flow rule applies on every invocation.

## Read-site migration table

| Current Datahike call | Replacement | Form to preserve | Custody rule | Material return change |
|---|---|---|---|---|
| d/q | db/q | Positional calls keep query and inputs. Argument-map calls keep Datahike's :query/:args/:offset/:limit keys. When the map already carries the database in :args, a token replacement is enough. | Preserve every explicit immutable database value. Only omit it when the current site already derives from ambient cluster custody. | Dependency failures become flat errors; successful result shape is unchanged and EDN-backed attributes are decoded. |
| d/pull | db/pull | Preserve positional selector/eid or {:selector ... :eid ...}. | Preserve the explicit database value at all current source sites. | Eager ordinary maps, with EDN-backed values decoded; no opaque process object. |
| d/pull-many | db/pull-many | Preserve selector/eids or {:selector ... :eids ...}. | Preserve the explicit database value. | Eager aligned vector with decoded values. |
| d/entity | db/entity | Preserve entity id / lookup ref. | Preserve the explicit database value. | This is intentionally not Datahike Entity: it is eager wildcard-pull ordinary data. seon.cluster.source/upsert! only reads one scalar attribute, so that site remains valid. |
| d/datoms | db/datoms | Preserve positional index/components or {:index ... :components ...}. | Preserve the explicit database value. | Eager vector of ordinary maps with :e, :a, :v, :tx, :added. Current consumers use those fields or sequence presence; none requires host Datom methods. |
| d/db | db/db | Explicit db/connection form, except the one ambient case below. | Preserve explicit connection in store/open validation. seon.render/ambient-database-value uses zero-argument db/db after its explicit walk database check. | Invalid/missing custody becomes a flat error rather than a throw. |
| d/history | db/history | Zero/one database arity. | Preserve explicit database outside seon.db. | Non-temporal databases return :seon.db/non-temporal-database. |
| d/as-of | db/as-of | Database plus time point, or ambient time point. | Preserve explicit database in current production/test sites. | Same database-view semantics; failures are values. |
| d/since | db/since | Database plus time point, or ambient time point. | Preserve explicit database in current production/test sites. | Same database-view semantics; failures are values. |
| d/transact | db/transact! | Preserve vector/sequence or {:tx-data ... :tx-meta ...}. | Every current system, boot, runtime, and fixture site keeps its explicit connection. None of the 16 writes occurs inside the guarded agent evaluation binding. | Four outcomes above; caller must discriminate transaction report from flat error. |

Custody tracing found exactly one production source read where elision is the
intended replacement: src/seon/render.clj:119. call-with-walk-context binds
seon.db/*conn* from the render context, and guarded evaluation binds the same
compiled dynamic Var around the complete evaluation. After checking the
explicit :seon.db/db first, ambient-database-value should call (db/db) with no
argument. Every other current source read receives a database value or
connection from its function arguments/local snapshot and must keep it. This
was resolved by source tracing; no live cluster was available, and no remaining
custody case required a stateful REPL probe.

## Non-core calls that are not read/write substitutions

These calls must not be accidentally rewritten to a similarly named seon.db
function that does not exist.

| Sites | Judgment for the sweep |
|---|---|
| seon.db's 28 direct Datahike calls | Exempt implementation boundary; exact functions are listed below. |
| seon.cluster.store and seon.cluster.registry custody calls | Exempt custody boundary; exact functions are listed below. |
| d/listen/d/unlisten in seon.cluster.agent/await-turn-completion!, seon.cluster.wake/route! and unlisten!, and seon.eval.drive/await-fact! | Ruling #41 keeps listeners system-side and out of the agent surface. Leave these six calls direct during the core read/write wave. They are not evidence that q/pull/transact remain bypassed. |
| d/commit-id in seon.cluster.source/publish! and seon.eval.drive/run-episode!, and d/committed-value-identity in seon.oversight/connection-identity | These are database-value reads but seon.db currently has no corresponding public function. Add the same thin errors-as-values functions at the start of the quiet window or explicitly leave them as a separately recorded residual; do not replace them with raw map field reads. |
| force-branch!/release in seon.cluster.source, release in seon.bootstrap-drive and seon.cluster, and create/connect/branches/branch!/release in seon.cluster.export | These are custody/branch lifecycle, not core data reads. Route them through the store/registry owner where an owner function exists. Where none exists, classify that exact caller as a custody owner rather than adding a seon.db function. |
| d/branch-as-db and d/release-materialized-db in seon.cluster.registry/branch-blobs; d/gc-storage in collect! | Registry branch/GC ownership; exempt. |

This table exposes one wording tension in acceptance item 3: the ruling
explicitly excludes listen! from the agent surface, while the issue also says
literal datahike.api requires remain only in seon.db/store/registry. The
mechanical core sweep can prove zero external q/pull/pull-many/entity/datoms/
db/history/as-of/since/transact calls. It cannot also erase system listeners
without a separate ownership decision. The freeze lane must use the core-call
zero as its completion grep and record listener/custody calls by the exact
allowlist above; it must not invent an agent-callable listen! merely to make a
require count zero.

## Exact exempt implementation and custody functions

### seon.db

All direct Datahike calls inside src/seon/db.clj are the implementation of the
one namespace and remain:

- fresh-connection — create-database and connect for the honest generators;
- database-value-generator — as-of, since, and history variants;
- resolve-database-value — db;
- aligned-query-arguments — query-input-count and query-source-bindings;
- q — q-with-evidence;
- pull — pull-with-evidence;
- pull-many — pull-many-with-evidence;
- entity-call — pull-with-evidence;
- datoms-call — datoms;
- history, as-of, and since — the matching Datahike database-view function;
- panic-on-core-error? — q and db, deliberately internal to unknown-failure
  handling;
- transact-call — transact.

### seon.cluster.store

These are the physical store/connection/flock custody owner and remain direct:

- create-store! — create-database;
- open-store! — database-exists?, connect, db for open validation, and release
  on failed open;
- release-store! — release;
- open-branch! — branches and connect.

The flock helpers use JDK/file primitives rather than datahike.api but remain
part of this same owner.

### seon.cluster.registry

Only branch/GC custody stays direct:

- roster — branches;
- branch! — branch!;
- retire-branch! — delete-branch!;
- branch-blobs — branch-as-db and release-materialized-db;
- collect! — gc-storage.

The q calls in blob-digest-attributes and branch-blobs are not exempt: migrate
them to db/q with their explicit database values.

## Test-file inventory

The ruling-time estimate of about 58 test files is exactly 58 on this worktree.
clj-kondo resolves 940 physical calls: transact 370, q 282, pull 117, db 26,
pull-many 2, datoms 4, history 6, as-of 5, since 3, and 125 lifecycle/internal
calls.

The default test rule is explicit custody: test bodies and
seon.test-support fixtures are not guarded agent evaluations, so all migrated
core calls retain the database value/connection already present. Raw
create/connect/release/delete/branch operations remain fixture lifecycle.
test/seon/db_test.clj also retains direct Datahike calls that deliberately
establish dependency parity, inject malformed storage below seon.db, or wrap
the dependency Var to count calls; those are tests of the boundary, not
bypassing application callers.

| Test file | Namespace | Calls by function | Total | Sweep action / custody |
|---|---|---:|---:|---|
| test/seon/ai_stream_fold_test.clj | seon.ai-stream-fold-test | d/pull ×3, d/transact ×2 | 5 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/ai_test.clj | seon.ai-test | d/transact ×1 | 1 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/blob_settlement_test.clj | seon.blob-settlement-test | d/release ×1, d/transact ×2 | 3 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/blob_test.clj | seon.blob-test | d/release ×1 | 1 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/bootstrap_test.clj | seon.bootstrap-test | d/pull ×2, d/q ×3, d/transact ×1 | 6 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/agent_namespace_test.clj | seon.cluster.agent-namespace-test | d/q ×1, d/transact ×6 | 7 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/agent_test.clj | seon.cluster.agent-test | d/as-of ×1, d/listen ×1, d/pull ×2, d/q ×31, d/transact ×31, d/unlisten ×1 | 67 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/armed_test.clj | seon.cluster.armed-test | d/listen ×1, d/pull ×2, d/q ×11, d/transact ×2, d/unlisten ×1 | 17 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/boot_test.clj | seon.cluster.boot-test | d/branch-as-db ×1, d/listen ×1, d/q ×20, d/release ×1, d/transact ×8, d/unlisten ×1 | 32 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/export_test.clj | seon.cluster.export-test | d/branch! ×1, d/branches ×2, d/q ×1, d/release ×2, d/transact ×5 | 11 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/instruction_test.clj | seon.cluster.instruction-test | d/pull ×3, d/q ×5, d/transact ×3 | 11 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/loop_test.clj | seon.cluster.loop-test | d/connect ×2, d/create-database ×2, d/db ×1, d/delete-database ×2, d/pull ×2, d/q ×2, d/release ×2, d/transact ×19 | 32 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/message_assignment_test.clj | seon.cluster.message-assignment-test | d/q ×4, d/transact ×5 | 9 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/message_test.clj | seon.cluster.message-test | d/connect ×1, d/create-database ×1, d/delete-database ×1, d/pull ×1, d/q ×10, d/release ×1, d/transact ×10 | 25 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/problem_routing_test.clj | seon.cluster.problem-routing-test | d/q ×2, d/transact ×9 | 11 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/program_restart_test.clj | seon.cluster.program-restart-test | d/pull ×9, d/q ×7, d/transact ×1 | 17 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/prompt_test.clj | seon.cluster.prompt-test | d/q ×1, d/transact ×6 | 7 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/registry_test.clj | seon.cluster.registry-test | d/pull ×1, d/q ×1, d/release ×15, d/transact ×7 | 24 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/resume_artifact_routing_test.clj | seon.cluster.resume-artifact-routing-test | d/transact ×4 | 4 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/run_test.clj | seon.cluster.run-test | d/connect ×1, d/create-database ×1, d/db ×5, d/delete-database ×1, d/pull ×10, d/q ×3, d/release ×1, d/transact ×30 | 52 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/source_test.clj | seon.cluster.source-test | d/branch-as-db ×4, d/parent-commit-ids ×2, d/q ×5, d/release ×4, d/transact ×5 | 20 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/store_test.clj | seon.cluster.store-test | d/as-of ×1, d/branch! ×1, d/create-database ×1, d/history ×3, d/q ×3, d/release ×2, d/since ×1, d/transact ×13 | 25 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/store_transact_test.clj | seon.cluster.store-transact-test | d/connect ×2, d/create-database ×2, d/delete-database ×2, d/q ×2, d/release ×2, d/transact ×3 | 13 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/turn_test.clj | seon.cluster.turn-test | d/pull ×28, d/q ×78, d/transact ×28 | 134 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/wake_test.clj | seon.cluster.wake-test | d/q ×2, d/transact ×13 | 15 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/cluster/work_test.clj | seon.cluster.work-test | d/connect ×1, d/create-database ×1, d/db ×9, d/delete-database ×1, d/release ×1, d/transact ×15 | 28 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/config_test.clj | seon.config-test | d/q ×1 | 1 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/context_capture_test.clj | seon.context-capture-test | d/pull ×1, d/transact ×4 | 5 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/custody_stability_test.clj | seon.custody-stability-test | d/q ×3 | 3 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/db_test.clj | seon.db-test | d/connect ×1, d/create-database ×1, d/db ×2, d/delete-database ×1, d/pull ×2, d/pull-many-with-evidence ×2, d/q ×2, d/release ×1, d/transact ×1 | 13 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/error_test.clj | seon.error-test | d/q ×5, d/transact ×4 | 9 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/flow/kill_child.clj | seon.flow.kill-child | d/connect ×1, d/create-database ×1, d/transact ×2 | 4 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/flow/loop_test.clj | seon.flow.loop-test | d/connect ×1, d/create-database ×1, d/delete-database ×1, d/pull ×2, d/q ×15, d/release ×1, d/since ×2, d/transact ×9 | 32 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/flow_test.clj | seon.flow-test | d/connect ×2, d/create-database ×1, d/database-exists? ×1, d/delete-database ×2, d/q ×5, d/release ×2, d/transact ×4 | 17 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/fn_test.clj | seon.fn-test | d/pull ×1, d/q ×10, d/transact ×2 | 13 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/gen/loop_test.clj | seon.gen.loop-test | d/q ×13, d/transact ×4 | 17 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/oversight_test.clj | seon.oversight-test | d/listen ×1, d/q ×1, d/unlisten ×1 | 3 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/problems_test.clj | seon.problems-test | d/q ×1, d/transact ×5 | 6 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/program_test.clj | seon.program-test | d/datoms ×1, d/pull ×4, d/transact ×6 | 11 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/public_contract_test.clj | seon.public-contract-test | d/release ×2 | 2 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/reconcile_test.clj | seon.reconcile-test | d/connect ×1, d/create-database ×1, d/delete-database ×1, d/history ×1, d/pull ×5, d/q ×1, d/release ×1, d/transact ×4 | 15 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/render/agent_test.clj | seon.render.agent-test | d/pull ×1, d/q ×1, d/transact ×1 | 3 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/render/block_test.clj | seon.render.block-test | d/connect ×1, d/create-database ×1, d/db ×8, d/delete-database ×1, d/pull ×2, d/release ×1, d/transact ×9 | 23 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/render/ns_test.clj | seon.render.ns-test | d/pull ×1, d/q ×1, d/transact ×4 | 6 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/render/root_test.clj | seon.render.root-test | d/transact ×1 | 1 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/render/transcript_test.clj | seon.render.transcript-test | d/pull ×1, d/pull-many ×2, d/transact ×10 | 13 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/render/walk_test.clj | seon.render.walk-test | d/pull ×14, d/q ×3, d/transact ×11 | 28 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/render/web_test.clj | seon.render.web-test | d/as-of ×1, d/datoms ×2, d/q ×5, d/transact ×18 | 26 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/schema/admission_test.clj | seon.schema.admission-test | d/connect ×1, d/create-database ×1, d/delete-database ×1, d/history ×1, d/q ×3, d/release ×2, d/transact ×4 | 13 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/schema/datahike_test.clj | seon.schema.datahike-test | d/db ×1, d/q ×1, d/transact ×1 | 3 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/schema_usage_guard_test.clj | seon.schema-usage-guard-test | d/as-of ×2, d/datoms ×1, d/history ×1, d/pull ×10, d/q ×10, d/transact ×17 | 41 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/sci/eval_instrumentation_test.clj | seon.sci.eval-instrumentation-test | d/listen ×1, d/pull ×1, d/q ×3, d/transact ×1, d/unlisten ×1 | 7 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/sci/eval_test.clj | seon.sci.eval-test | d/pull ×1, d/transact ×7 | 8 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/sci/session_image_child.clj | seon.sci.session-image-child | d/connect ×2, d/create-database ×1, d/release ×2, d/transact ×2 | 7 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/sci/session_image_test.clj | seon.sci.session-image-test | d/pull ×8, d/transact ×7 | 15 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/test_runner_test.clj | seon.test-runner-test | d/q ×1, d/transact ×2 | 3 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/test_support.clj | seon.test-support | d/branch! ×1, d/branches ×1, d/connect ×3, d/create-database ×2, d/delete-branch! ×1, d/delete-database ×2, d/q ×1, d/release ×3, d/transact ×3 | 17 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |
| test/seon/test_support_test.clj | seon.test-support-test | d/q ×4, d/transact ×1 | 5 | Core reads/writes use seon.db with the explicit fixture database/connection; lifecycle-only setup remains a raw dependency fixture. |

## Ordered quiet-window execution

The tree freeze begins only after every in-flight source owner has committed or
handed off coherent files. One lane then executes these waves in order; no
other source edits occur until the final census.

1. **Freeze census and seam preflight.** Record HEAD/status, rerun both
   clj-kondo censuses, and compare against this report. Resolve only genuine
   drift by updating the table before edits. Add/settle the three database
   identity reads if the issue is to claim literal require closure. No broad
   search-and-replace.
2. **Leaf read wave.** Migrate query-only leaves first: seon.ai,
   seon.bootstrap, seon.cluster.instruction, seon.config, seon.error,
   seon.oversight, seon.problems, and seon.schema. Commit those explicit paths.
   Run their focused tests plus seon.db-test.
3. **Runtime model read wave.** Migrate seon.cluster.run,
   seon.cluster.message, seon.cluster.work, seon.cluster.loop, and
   seon.cluster.agent. Keep listener calls system-side. Commit those explicit
   paths. Run cluster run/message/work/turn/agent/armed/wake focused gates.
4. **Render read wave.** Migrate seon.render and every seon.render.* owner.
   Apply the one ambient db/db decision only at
   seon.render/ambient-database-value; keep all other snapshots explicit.
   Commit the render paths. Run render block/ns/root/transcript/walk/web gates.
5. **Initialization/read wave.** Migrate seon.cluster,
   seon.cluster.source, seon.fn, seon.reconcile, seon.eval.drive, and
   seon.bootstrap-drive reads. Move or explicitly retain their custody
   lifecycle calls according to the non-core table. Commit explicit paths.
   Run source/fn/reconcile/config/eval-drive/bootstrap gates.
6. **Write wave — boot and publication first.** Apply the classified control
   flow to seon.cluster, seon.cluster.source, and seon.fn. The commit is
   path-limited to those three owners. Gate with db, cluster boot/source,
   fn, schema admission, and store transaction tests. A flat error reaching a
   later phase is a failed wave even if tests happen to stay green.
7. **Write wave — runtime and fixtures.** Migrate
   seon.render.web/inbound and start!, seon.reconcile/reconcile!,
   seon.eval.drive/inbound!, and seon.test.runner/record!. Preserve their
   distinct HTTP, runtime-value, sample-failure, and runner-exit semantics.
   Commit those explicit paths. Run render.web, reconcile, config,
   eval-drive/bootstrap-drive, and test-runner focused gates.
8. **Test sweep by owner family.** Migrate ordinary test reads/writes in the
   same family order as their source wave. Keep raw dependency fixtures only
   where the test is intentionally below seon.db or owns database lifecycle.
   Natural path-limited checkpoints: database/schema/config; cluster
   store/source/run; cluster loop/agent/message/work; render; SCI/session;
   remaining support/flow/eval tests. Run each family immediately after its
   commit.
9. **Authority and zero census.** Update AGENTS.md,
   docs/seon/architecture/data-model.md, docs/seon/architecture/toolkit.md,
   the issue, and the active roadmap only after the source/tests prove the
   landed reality. Run analyzer and text checks proving zero direct external
   core q/pull/pull-many/entity/datoms/db/history/as-of/since/transact calls.
   The only remaining production direct calls must match the exact
   implementation/custody/listener ledger in this report.
10. **Integrated checkpoint.** Run the changed-test selector for every source
    family, then the full bin/test gate on the frozen tree. Perform the
    reset/rebuild live proof required by ruling #49: freshly publish source,
    fork an isolated cluster, exercise an ambient agent q/pull/transact/history
    round trip and an undeclared-attribute rejection, and tear the root down.
    Only then release the freeze.

Each wave is independently reviewable and path-limited. No wave stages another
lane's file, and no commit uses git add -A. If the frozen census differs, the
lane stops at the first unmatched namespace/caller and updates this plan rather
than improvising a new semantic class in production.
