---
type: research
status: active
tags: [research, test, flow, database]
---

# Fixtures and events: required design decision — 2026-09-15

## Result and stop boundary

No production edits or issue closures. This is an investigation checkpoint,
not a completed class repair. The assignment explicitly says: "if the kill
would take hours of cross-owner work or its guarantee cannot be stated in one
sentence, STOP and write three priced options in your landing note and report."
That boundary applies to closing the entire supplied class membership.

The 2026-08-11 class rows name distinct constructions: P2 requires producers
to expose events, P3 requires complete constructor outputs, and N2 requires
nonempty production subjects and retained counterexamples. A test-support
change cannot by itself repair the remaining driver lifecycle, source branch
publication, message ordering, and non-installed program-analysis subjects.
Combining them behind another generic fixture would conceal those authorities.

This is not a stop for foreign gate breakage. No gate has run or failed in
this investigation. The protected edits below were preserved.

## Three concrete options

Costs are engineering estimates from the seams inspected here, excluding
machine-wide test-slot contention; they are not measured execution times.

| Option | Guarantee | Cost | What we give up |
|---|---|---|---|
| **1. Repair the two runner failures first — recommended** | Each worker owns every store that connect may mutate, and result recording reapplies the same completion to a newer source head under a declared bound without overwriting that head. | 4–8 hours: fixture base acquisition plus `seon.cluster.source/record-results!`, canonical concurrent acquisition and controlled head-advance regressions, serial gates. | P2/P3/N2 remain open; this closes two concrete shared-infrastructure failures rather than claiming a global class repair. |
| 2. Finish the fixture/event subset as separate coherent slices | Agent tests get production-shaped handles and wait for named completion facts/events; every repaired property proves a nonempty subject and retains its counterexample. | 1–3 engineer-days, including option 1, agent graph/report ownership where needed, and conversion of the current agent/history fixture observations. | Production driver, message ordering, and program-analysis members stay explicitly open; no whole-class closure. |
| 3. Close every current P2/P3 member and the complete N2 class | Every surviving member has either its owning construction repaired and proven or an evidence-backed supersession with its residual named. | 3–7 engineer-days across source publication, Flow handles, SCI settlement, render/feed, message transactions, operator, and program analysis; coordinate currently protected owners first. | Gives up the bounded fixture lane and fast isolated delivery; requires reviewing class tags that do not describe the class construction. |

For option 1 the smallest existing mechanism is private cloning, already used
by `populate-published-root!` and `populate-published-operator-root!`; extend
that ownership to the tiered fixture backend. Do not add a second store cache.
The result race belongs at `record-results!`, where the expected head is
checked, rather than a runner-side pre-read or removal of the expected-head
guard. The exact contention bound and retry shape remain to be designed in a
canonical fixture after this decision; no retry count has been invented here.

## Authorities and inherited state

Read end to end: `AGENTS.md`,
[issues README](../../../seon/issues/README.md),
[N2 class issue](../../../seon/issues/class-proofs-pass-without-exercising-their-premise.md),
every current tagged member listed below, the archived original members
listed below, both named runner notes, and the complete
[class-mining report](../../sci-execution-runtime/research/issue-class-mining-2026-08-11.md).
No separate P2 or P3 class note was found among tracked issue files; their
class statements and structural changes are in the mining report.
Loaded data-oriented-clojure, repl, clojure-testing, datahike, and
seon-flow-architecture skills.

Observed branch: `steward-platform`; recorded HEAD during investigation:
`22893b71383cec23c8763df7da841524259a1774`. The shared checkout is changing;
the live observations below do not establish equality between that HEAD and
the adopted program. No hot reload, adoption, stop, refork, or restart was
performed by this lane.

`bin/seon status`: default alive, PID 69622, prepl 55914, URL
`http://127.0.0.1:7994`, 404.82 GiB usable. MCP runtime status answered:
2 agents, 18 errored evaluation observations, all three reported plumbing
procs answered. The errored observations are not attributed to this lane.

Protected at the last inventory: `resources/seon/schemas/seon.sci.admit.edn`,
`src/seon/cluster.clj`, `src/seon/render/value.clj`, `src/seon/sci/admit.clj`,
`test/seon/cluster/mcp_test.clj`, `test/seon/effect_test.clj`,
`test/seon/repl_parity_test.clj`, `test/seon/search_test.clj`.
No foreign lane was contacted or operated.

Both `bin/issues-index --class class/p2` and `--class class/p3` exited 1:
the authority has eight missing schedule rows. This is the existing owner
index-reconciliation boundary, not evidence of an empty class. Reading
frontmatter directly found 8 P2 members, 8 P3 members, and 4 N2 members plus
its class note. These are dated counts, not a new maintained roster.

## Dependency ledger and source evidence

| Dependency / authority | Inspected seam | Consequence |
|---|---|---|
| Konserve | `reference-code/konserve/src/konserve/tiered.cljc:88–115` | `sync-on-connect` enumerates backend keys even when writes are frontend-only. |
| Konserve filestore | `reference-code/konserve/src/konserve/filestore.clj:672–727,814–853` | Enumeration may invoke migration; v1 migration explicitly deletes its old data path. A connect is not structurally read-only. This identifies a reachable deleting mechanism, not the actor in a historical failure. |
| Canonical fixture | `test/seon/test_support.clj:206–241` | `create-base` points each JVM's tiered backend at the shared published `base/data/store`. `:write-policy :frontend-only` does not prohibit migration during backend enumeration. |
| Existing private clone | `test/seon/test_support.clj:56–119` | Other file fixtures already clone before opening. Reuse this owner; its current subprocess wait is unbounded and needs the declared event bound when touched. |
| Published cache | `src/seon/test/cache.clj:80–126` | Cache preparation has a lock and launcher references; these do not serialize or isolate worker backend connects. |
| Source publication | `src/seon/cluster/source.clj:297–326` | Results commit on a scratch branch and force the expected head; a changed head refuses. |
| Existing race regression | `test/seon/cluster/source_test.clj:511–588` | A controlled publication during recording preserves the newer head, omits unpublished evidence, retires scratch, and a second explicit recording succeeds. Extend this production-seam proof. |
| Agent test events | `test/seon/cluster/agent_test.clj:250–279,1142–1188,1410–1450` | The shared event bound surrounds a future that still sleeps 25 ms and polls. Named database events already have a helper; pass-count/Var-reload tests need their actual completion observation. |

Git history inspected for the owners: fixture `7f484d4bb`; result publisher
`0d1f72cd0`; schedule and walk tests `d6d399561`; SCI and transcript tests
`ae0e54841`; oversight and driver `6acd8818e`; message owner `be4e3fe00`.
These are last-touch evidence, not an assertion that each commit fixes its note.

## Live probe and exact result

The retained [probe form](fixtures_events_probe_2026_09_15.clj) was evaluated
through MCP **JVM mode**, root `/Users/sean/src/seon`, cluster `default`,
with explicit `(seon.operator/connection "default")`. It reads only.
The successful MCP evaluation reported **1,497 ms**, no exception, and:

```clojure
{:probe/activation-counts
 {:seon.activation/config-defaults 24
  :seon.activation/config-required 69
  :seon.activation/executable-symbols 4637
  :seon.activation/required-attributes 310
  :seon.activation/schema-keys 70}
 :probe/basis 536871495
 :probe/catalog-count 70
 :probe/message-count 5
 :probe/missing-ping
 {:seon.oversight/ping :unknown :seon.oversight/proc :probe/absent}
 :probe/old-ordinal-installed? false
 :probe/ping-timeout-ms 20}
```

The first attempt omitted the handed projection for `config/effective` and
returned `:seon.config/missing-projection`, with a 569 ms database projection
fallback warning. Supplying the projection through the documented REPL owner
made the probe succeed. This was a refused probe input, not tool downtime.

The live result **falsifies** an initial source-reading suspicion that the
activation filter necessarily produces zero keys. The current catalog still
provides 70 matching shapes. It also proves that a missing plumbing pong is
explicitly unknown. Neither observation proves the complete historical
acceptance criteria or fresh publication behavior.

## Per-member disposition at the design boundary

All currently open notes remain open. “Source changed” below is deliberately
weaker than a completed armed regression or a live end-to-end proof.

### P2

| Member under `docs/seon/issues/` | Evidence and remaining verification |
|---|---|
| `concurrent-eval-test-calibrates-interpreted-work-to-wall-time.md` | The old finite-spin oracle is replaced: `test/seon/sci/eval_test.clj:1710–1765` arms both threads, awaits readiness, and observes one cut before the sibling check. Still has raw latch awaits and a spin loop; needs bounded completion and a focused regression before closure. |
| `eval-drives-duplicate-a-four-minute-run-clock.md` | Confirmed source premise: `src/seon/eval/drive.clj:372–373` and `src/seon/bootstrap_drive.clj:401` still derive `run-cap * 240000`; `drive.clj:329` also uses a separate bootstrap bound. These are production driver owners. |
| `observable-graph-transitions-are-polled-in-tests.md` | Confirmed current 25 ms sleep at `agent_test.clj:258`, with seven call sites in park/wake and Var-reload tests. Wrapping the poll in `await-event!` does not dissolve the construction. |
| `oversight-treats-a-20ms-ping-absence-as-state.md` | Partial premise dissolved: live missing pong is `:unknown`; `oversight.clj:223` derives mid-turn from a durable turn ID. The configured bound remains 20 ms. Busy/scheduler/stopped distinction and delayed-scheduling regression not verified. |
| `confirmation-parallel-failure-blocks-reading-worker-protocol.md` | Read the complete record. Current end-to-end confirmation exchange has not been reproduced; remains pending at `seon.test.runner`, not silently declared healthy. |
| `operator-root-inference-guesses-from-directory-names.md` | The recorded construction is root custody/naming, not clock substitution. Requires owner classification review and HEAD probe before changing the note. |
| `reset-deletes-a-bloated-store-one-lstat-at-a-time.md` | Note already records progress support in `bdceb7915`, 5,755 → 5,508 ms on 100k files, and unverified representative-scale behavior/operator wiring. No destructive drill run; no new timing claim. |
| `unowned-namespace-oversight-still-inverts-assignment.md` | Recorded construction is stewardship query semantics, not a clock. Requires owner classification review and current query verification. |

Original P2 member `archive/cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md`
is already resolved; its complete record attributes the remaining progress
repair to `61cbb93ed`. No second-boot timing was repeated here.

### P3

| Member under `docs/seon/issues/` | Evidence and remaining verification |
|---|---|
| `activation-closure-records-no-schema-keys.md` | Live requirements are nonempty (70 schemas / 310 attributes). Fresh seal equality and per-category refusal remain unverified. Protected `cluster.clj` was not edited. |
| `agent-flow-fixture-omits-render-interest.md` | The note records multiple earlier fixture corrections. The broader current agent fixture failures have their own note below; a constructor enumeration regression and full named namespaces are still needed. |
| `dynamic-in-ns-cannot-persist-definition-namespace.md` | Static reader tests include nested movement (`reader_test.clj:501`), but that is not terminal definition settlement. No shared-SCI mutation or falsely equivalent JVM probe was used. Remains unverified at the settlement owner; `sci/admit.clj` is protected. |
| `feed-writer-casts-an-absent-package-number.md` | `web.clj:2737` still casts the package basis, but its caller now uses `await-feed-package!`. Reachable absence must be tested through that producer before attributing a current failure. No browser/feed mutation was performed. |
| `render-token-budgets-are-private-dials-no-producer-supplies.md` | Source changed: namespace reads the profile (`ns.clj:417`), `render.clj:1653` carries it, transcript derives it (`transcript.clj:1880`); old private token key search found no remaining match. Production budget measurement still required before closure. |
| `schedule-graph-test-constructs-a-handle-without-an-environment.md` | Current test uses `with-database`, `environment`, `cluster-handle`, `env/carry`, then the production graph constructor. Source premise dissolved; focused armed gate not run. |
| `system-generated-messages-omit-arrival-ordinals.md` | Old ordinal attribute is absent from the live schema (5 actual messages present). Current `message-order` (`message.clj:485`) sorts instant then ID; `error.clj:1144` constructs the current message family. Restoring an old field alone would not prove current transaction ordering. Needs explicit current ordering design and regression. |
| `turn-consumer-fixtures-read-retired-result-storage.md` | Some fixture work landed: `seed-cluster!` (`test_support.clj:625`) now invokes config application. Source still contains legacy history fixture identifiers (`transcript_test.clj:1026`); no broad pass claimed. The note's dated agent failures require current canonical reproduction. |

Original P3 members `archive/loop-settlement-consumer-reads-a-key-no-producer-writes.md`
and `archive/web-config-dials-ship-without-shipped-defaults.md` are already
closed; read both complete records. Their historical closures are not new
verification in this lane.

### N2 and the two runner notes

| Member under `docs/seon/issues/` | Evidence and remaining verification |
|---|---|
| `render-wave-properties-cannot-produce-their-failing-cases.md` | Old P1/P5 names and floor helpers are gone. Current walk uses production acquisition and explicit subject assertions; transcript still has a property at `:1016` plus legacy fixtures. Replacement alone does not prove retained counterexamples; needs current property audit and gate. |
| `bootstrap-o4-stops-before-causal-delegation-settles.md` | `drive.clj:110–119` still derives directly triggered turns. The production causal closure is outside fixture-only scope. No paid drive run. |
| `context-mvp-drive-can-false-green-after-cross-agent-delivery.md` | The note says its temporary driver was deleted; acceptance depends on the same causal closure as O4. Do not recreate the old script or close on target-only quiescence. |
| `output-sink-query-excludes-operator-and-mcp-scripts.md` | Complete note read. Requires a production non-installed analysis subject, not a test-side concatenation; no current complete sink census was run. |
| `parallel-test-base-connect-can-lose-a-filestore-key.md` | Concrete unsafe ownership path identified in the dependency ledger. Concurrent fixture reproduction and immutable-source verification remain to be implemented; historical deleting actor still unproven. |
| `test-result-recording-refuses-after-branch-head-change.md` | Existing controlled race test and expected-head seam read. No automatic recovery exists in the inspected function. Preserve the guard and immutable completion provenance. |

Archived original N2 records read end to end: transport taxonomy
(`270a66fd4`), public-contract census (`dac16b297`), and oversight fleet roster
(`5bc903010`). The N2 class note also records assertionless-test enforcement
in `ad3d13e9b`. None establishes complete class closure.

## Verification and cleanup

- Production tests/gates: **not run**; stop is before production edits under
  the assignment's design gate. No green canonical-harness claim.
- Read-only live evidence: successful MCP JVM probe above; default unchanged.
- No worker, background shell, scratch root, worktree, provider call, or
  destructive operation was created by this lane.
- Deliverables in this checkpoint: this note and its retained read-only form.
- Next step requires selecting the scope above. Class notes cannot honestly
  be closed on this evidence.
- Checkpoint commit: `6960ee37a`. Probe lint passed with zero errors and zero
  warnings; path-limited whitespace check passed. The Markdown hook reported
  an unrelated current-gitlink citation in
  `docs/prds/context-generation/research/refusal-grammar-2026-09-15.md:70`
  (recorded SCI hash `38e627467daa3f6f1e5a8eb6421f702d2a940b7f`, checked-out
  gitlink `fcbd8862800e638dc0f8f5521111f999279cbcd2`). That foreign document
  was left untouched; this is not a claim of a green repository Markdown gate.
