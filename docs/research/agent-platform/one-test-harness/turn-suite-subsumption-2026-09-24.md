---
type: research
status: evidence — read-only; no source/test edits, no JVM, no tests run
created: 2026-09-24
scope: every deftest in the seven overlapping turn suites named by design-2026-09-24.md §9a
pins: working tree of refactor/agent-platform at b2fcb5225 plus uncommitted lane edits (below)
---

# Turn-suite subsumption: behaviour → survivor → subsumed

**What exists.** Seven namespaces hold 152 tests and 7,437 deftest body lines (form start to close, measured
by a paren-aware script; shared helpers are not counted in the body). The survey (§"not claimed") left their
overlap unverified. **Method.** Every body was read in the working copy. Each row names the behaviour its
assertions prove, not its name. One survivor per behaviour class (CLAUDE.md, "Keep one regression per behavior
class"). SUBSUMED means that every load-bearing assertion either appears in the named survivor or is a
retirement guard or measurement (these are listed under *residue*). A subsumed test should be deleted, not
converted. DELETE means the body proves only a retired assumption or restates a declaration.

**Working copy.** Four of the seven files, not two, carry uncommitted `sweep-hand-built-ns-rows` edits:
`seon/turn_test` (863, 1649–1652), `cluster/turn_test` (1580–1614), `turn_loop_test` (1065–1071, 1287–1290)
and `loop_proof_test` (345–350). The diff is +38/−35. Every change swaps a literal `{:seon.ns/name …}` for
`support/namespace-row` or `program/declaration-row`. The bodies were read with those edits in place.

Key: **T** `test/seon/turn_test.clj`, **C** `test/seon/cluster/turn_test.clj`, **L** `test/seon/turn_loop_test.clj`,
**W** `test/seon/turn_work_test.clj`, **N** `test/seon/turn_continue_test.clj`, **P** `test/seon/loop_proof_test.clj`,
**G** `test/seon/gen/loop_test.clj`. The number after the name is its body lines. **HB** marks a survivor that
depends on hand-built agent, namespace, function or test rows (see §Fixture sweep).

## Totals

| verdict | tests | body lines |
|---|---:|---:|
| KEEP (survivor) | 98 | 5,528 |
| SUBSUMED (delete) | 51 | 1,861 |
| DELETE | 3 | 48 |
| PLATFORM | 0 | 0 |
| **removed** | **54** | **1,909 (25.7%)** |

By file, kept/removed tests: T 26/11, C 44/16, L 13/13, W 9/4, N 2/7, P 3/1, G 1/2. No test's subject is the
host. The two cases that come closest are L1186, which builds a raw store, and P237, which runs a concurrent
arm. Both are ordinary member work or are subsumed, so none are PLATFORM. The shared helpers stay because
each still has a surviving caller: `prove-session` (N, 175 lines), `virtual-turn-fixture` (T, 300 lines),
`with-gen-cluster` (G) and the kill-position `commit-run!` (L). Deleting all of G's callers except G483
leaves G's helpers in place.

## Mapping by behaviour class

### 1. Next-work derivation (situations, totality)

| test | proves | verdict |
|---|---|---|
| W379 the-derivation-is-total-over-every-state 20 | named crash-walk states → exact situation; `more-agent-work?` agrees; schema-valid | KEEP |
| W523 situation-totality-property 61 | generated states: only 4 situations or nil, resume = first unsettled ordinal, answered-closed continues | KEEP |
| L1426 kill-positions-per-agent-test 27 | the same six rows (trigger/call/resume/resume/close/completed) | SUBSUMED-BY W379 |
| W501 an-answered-subjectless-message-does-not-end-a-session 21 | closed planned turn → `:open` until disposition, then nil | SUBSUMED-BY W523 (answered-closed clause) + W379 row 10 |
| W400 a-generated-run-resumes-then-requests-one-more-form 29 | `:generate` run resumes ordinal 0, then derives `:generate` | KEEP |
| W430 comment-only-input-is-recorded-but-never-becomes-eval-work 34 | comment-only ordinal skipped by resume, never gets a terminal fact | KEEP |
| W465 a-lint-refusal-is-terminal-until-a-new-trigger-arrives 27 | refused-form turn answers its wake; only a new outside wake reopens | KEEP |
| W359 the-request-declares-exactly-the-dependencies… 19 | Malli open-map accepts extra `:now`, requires agent id | DELETE — restates the schema; guards the retired clock key |

### 2. Answeredness, wakes and handled claims

| test | proves | verdict |
|---|---|---|
| W585 answeredness-is-the-turns-own-transaction 38 | wake answered iff an answering turn's `:t` follows it; nothing stored | KEEP |
| W681 two-wakes-in-one-transaction-are-one-turn 31 | two wakes at one `:t` answered by one turn | SUBSUMED-BY W585 (same `:t` rule) |
| W713 a-wake-arriving-mid-turn-opens-the-next-turn 24 | wake after open `:t` stays unanswered, opens next | SUBSUMED-BY W585 ("asserted after the turn is unanswered again") |
| L301 one-wake-cannot-open-a-second-turn-after-the-first-closes 60 | closed answering turn → no second turn | SUBSUMED-BY W585 |
| W788 triggers-come-back-oldest-first 17 | unanswered triggers sort by wake datom `:t` | KEEP |
| W624 only-a-turn-whose-reply-came-from-a-model-attempt-answers 56 | no-attempt and failed-attempt turns answer nothing; failed attempt authorizes no turn | KEEP |
| T2295 settlement-claims-covered-messages-without-removing-their-wakes 49 | close writes `:seon.turn/handled` for covered messages; stale opens no-op; wakes never retracted | KEEP |
| T420 a-wake-meeting-an-open-turn-releases-without-a-fault 47 | open-turn fence reports `:released`, writes no refusal | KEEP HB |

### 3. Turn bound and budgets

| test | proves | verdict |
|---|---|---|
| W738 the-turn-bound-is-turns-since-the-latest-outside-wake 49 | inside wakes spend, never reset; agent settings change bound live; outside wake resets | KEEP |
| N232 continuation-stops-at-the-turn-bound 2 | self-continuation stops at bound 2 | SUBSUMED-BY W738 + N206 (asserts `turns-left`, outside-t unchanged) |
| T95 issue-budget-counts-a-call-that-closes-before-the-provider 38 | pre-provider closed turns spend issue budget; same-tx open/close counts once | KEEP |
| C3792 generated-opening-preserves-one-provider-turn-budget 35 | system opening spends no provider-turn budget | KEEP HB |

### 4. Turn writers and the receipt state machine

| test | proves | verdict |
|---|---|---|
| T1926 transitions-agree-with-the-model 44 | generated open/close/plan/start/settle/recover vs a pure model; pointer, closure and receipt invariants | KEEP HB (bare agent rows 1936) |
| T1141 one-run-lifecycle-teaches-the-call-shapes 47 | open → pointer, plan → ordered agent forms, close → no pointer | SUBSUMED-BY T1926 |
| T1088 a-frozen-run-holds-exactly-one-evaluation-per-ordinal… 48 | plan = one eval per ordinal; plus two retirement guards (form family, `:seon.turn/forms`) | SUBSUMED-BY T1926; residue = retirement guards |
| T990 state-is-derived-from-primitives 9 | `open?` = no closed-tx | SUBSUMED-BY T1926 |
| T1507 receipt-transitions-preserve-one-terminal-outcome 95 | duplicate start refused, re-settle refused, recover stamps running, re-exec refused | SUBSUMED-BY T1926 (note: "settle without terminal fact" and triage-edn retention appear nowhere else) |
| T2142 open-turn-is-derived-without-an-agent-pointer 17 | retired agent attributes absent; derivation part = T1926 invariant | DELETE — retirement guard |
| T2129 run-schema-admits-and-refuses 12 | `:seon.turn/turn` Malli schema requires a non-blank id | DELETE — restates the declaration |
| T323 open-run-tx-call-emits-only-while-its-run-is-open 23 | writer-time decision: open → payload; closed/absent/retracted → [] | KEEP HB |
| T304 compaction-refuses-an-open-turn-at-the-writer 18 | compact! refuses with open turn, commits nothing | KEEP HB |
| T1189 generated-system-runs-grow-only-after-their-settled-prefix 90 | append-generated refuses over an unsettled prefix | KEEP HB |
| T1280 run-derives-its-opening-database-and-starting-namespace 71 | opening-db as-of open; missing-opening refusal; system intent recorded | KEEP HB |
| T1352 system-run-refuses-a-concurrent-namespace-reassignment 33 | starting-ns change refuses the whole tx | KEEP HB |
| T1070 interrupted-warning-is-one-derived-value 17 | one warning naming first interrupted ordinal and missing tail | KEEP |

### 5. Recovery and crash

| test | proves | verdict |
|---|---|---|
| T1980 recovery-preserves-terminal-receipts-exactly 88 | generated receipts: terminals byte-identical, all terminal, closed, idempotent | KEEP |
| T2069 recovery-cannot-stamp-a-settled-receipt 36 | settled-then-recover leaves receipt untouched, closes | SUBSUMED-BY T1980 |
| T2106 recovery-closes-a-turn-with-no-evaluations 18 | zero-evaluation recover closes; renders "interrupted before the reply" | KEEP HB |
| C2791 turn-intent-is-the-complete-crash-falsifier 85 | cut after intent: reply stored, no eval; recovery closes, never re-executes prefix; wake unanswered | KEEP |

### 6. Settlement of program rows and observed names

| test | proves | verdict |
|---|---|---|
| T1386 settlement-keeps-unresolved-call-and-require-names-as-values 120 | unresolved calls/requires stay symbols; no minted fn/ns; receipt carries no calls | KEEP HB — declared 60 s bound (see findings) |
| T835 unresolved-test-subjects-remain-symbol-values 66 | test subject stored before its fn exists; no fabricated fn; gate-set reaches | KEEP |
| T902 batch-settlement-preserves-declaration-order 48 | same, test-before-fn inside one batch | SUBSUMED-BY T835 |
| T1603 settlement-refuses-a-divergent-program-change-since-opening 73 | older-basis divergent def refuses; identical is no-op | KEEP |
| T2345 an-unbound-result-settles-red-and-unreadable-shown-text-does-not 49 | unbound SCI value settles red; `#'var`/map green | KEEP HB |

### 7. Read refusals are values, never absence

| test | proves | verdict |
|---|---|---|
| T1000 an-open-turn-read-refusal-refuses-the-writer-verbatim 19 | refused read → same refusal, no commit | KEEP |
| T1020 an-episode-budget-read-refusal-reaches-next-work-verbatim 24 | same class at the budget read (via `with-redefs db/q`) | SUBSUMED-BY T1000 |
| T1045 a-history-read-refusal-cannot-suppress-declaration-divergence 24 | same class at history read (via `with-redefs-fn`) | SUBSUMED-BY T1000 |
| T2395 an-agent-dial-read-that-refuses-is-not-an-absent-dial 22 | missing config refuses; a carried dial wins without reading config | KEEP |

### 8. Refused terminal commits and settlement robustness

| test | proves | verdict |
|---|---|---|
| C846 refused-terminal-program-transactions-settle-and-do-not-refire 158 | refused deletion: one receipt, one error, no install, row unchanged; cap decides the next turn | KEEP HB (hand-built fn/ns rows 869–873) |
| C1249 runtime-declarations-install-only-from-a-successful-terminal-db-after 53 | refused schema tx never installs | SUBSUMED-BY C846 |
| C755 refused-import-only-ns-unmap-leaves-the-run-sci-ctx-unchanged 62 | refused ns mutation: no DB mask, base ctx unchanged | SUBSUMED-BY C846 + C1508 (base ctx) |
| C1508 a-refused-definition-stays-in-its-agents-defs 64 | refused shared def stays in agent ctx, not base; next turn calls it | KEEP |
| L1612 a-refused-batch-settlement-closes-the-turn-and-the-agent-turns-again 88 | batch commit throw/refusal: all ordinals settle, turn closes, next wake opens | KEEP |
| L1556 a-refused-terminal-commit-still-closes-the-run 23 | single-form commit throw → phase-failed, closed | SUBSUMED-BY L1612 + C846 |
| L1530 a-private-def-settles-without-staging-a-blob-and-closes 25 | private def stages no blob; vector crosses blob seam; closes | KEEP |
| T347 a-terminal-refusal-settles-when-its-run-has-vanished 72 | vanished run: refusal durable, nothing resurrected, no core fault | KEEP |
| T2200 a-refused-turn-write-is-bounded-and-commits-exactly-one-fault 94 | write-refusal bound parks the agent, one fault, no later tx | KEEP HB (bare agent 2215) |

### 9. Phase failures, escalation, prompt refusal

| test | proves | verdict |
|---|---|---|
| C3231 generated-phase-failures-converge-through-one-terminal-exit 115 | every phase failure: settle once, close once, one error; escalation to owner once per signature | KEEP HB |
| L986 a-refused-phase-escalates-once-per-signature-and-never-to-itself 28 | escalation at recurrence limit only; self-escalation silent | KEEP |
| L1357 install-gate-failure-settles-the-started-receipt-as-a-failure 60 | `:evaluate` phase failure settles receipt as error, closes | SUBSUMED-BY C3231 |
| C3176 generated-membership-failure-never-advances-the-run-to-call 54 | declared-sources failure closes with error | SUBSUMED-BY C3231 |
| L585 refused-generated-opening-captures-evidence-before-close 62 | prompt refusal: closed, zero provider calls, capture row with refusal | KEEP |
| C3347 a-prompt-refusal-is-a-recorded-error-value-never-a-throw 35 | prompt refusal: error outcome, no call/attempt, durable | SUBSUMED-BY L585 |

### 10. Generated openings, system turns, compaction, isolation

| test | proves | verdict |
|---|---|---|
| P327 virtual-loop-end-to-end 615 | root/creation openings, stable stored prompt, compaction regeneration, handles, elision requery, changed reads, boot intent, plan completion, reader fences | KEEP HB (agent rows 347–350) |
| P64 running-fixture-settles-its-seeded-wake 172 | fixture schema population = declared ∪ keys; no inert read evidence; lost permit faults at backstop | KEEP HB (root ns row 71–72) |
| T769 virtual-turns-use-the-proc-and-compaction-is-agent-scoped 6 (+300 fixture) | agent-scoped compaction; private SCI state per agent; peer message doesn't refresh inbox | KEEP HB (655–657); residue = datom-count asserts, double refork |
| C3120 generated-fixed-point-closes-the-run 55 | exhausted declared sources close the generated run | SUBSUMED-BY P327 (opening closes; unchanged refresh opens no turn) |
| T134 generated-read-evidence-rejects-turn-activity 63 | system read of `:seon.turn/*` faults; agent-authored/call reads allowed | KEEP |
| T198 a-refused-generated-form-records-its-refusal 57 | that refusal settles with shown+error, closes | KEEP |
| L1059 a-clean-last-form-without-a-disposition-is-loud-terminal-evidence 96 | undisposed close; drive verdict `:undisposed` | KEEP HB (1068–1071); residue = dissolved-notice guard |

### 11. Real SCI evaluation inside a turn

| test | proves | verdict |
|---|---|---|
| C445 a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end 53 | private def seen by later form; read basis/evidence tx; idle after | KEEP HB (with-cluster) |
| C1814 a-whole-turn-runs-from-trigger-to-closed-run 16 | trigger → two evals → idle | SUBSUMED-BY C445 |
| T256 a-run-no-message-caused-settles-its-forms 47 | untriggered run settles | SUBSUMED-BY N206 (continuation turns are untriggered) |
| C539 agent-code-with-defn-and-println-folds-green-without-in-ns 21 | output captured; default ns is agent ns | KEEP HB |
| C561 mixed-plan-publishes-only-the-contracted-function 42 | only contracted defn becomes a row; installed before next form | KEEP HB (566–569) |
| C379 a-prose-prefixed-contracted-defn-settles-and-doc-answers 37 | prose-prefixed contracted defn persists spec/doc | SUBSUMED-BY C561 |
| C418 function-install-reads-the-case-count-from-cluster-facts 26 | auto-check-cases dial reaches accretion | KEEP HB |
| C504 an-ordinary-turn-advances-no-program-attribute-revision 34 | ordinary turn moves no program attribute revision | KEEP HB |
| C640 reply-reading-follows-evaluated-alias-and-dynamic-require-state 20 | later sources read after alias/require effects | KEEP HB |
| C1005 evaluation-follows-the-readers-parse-time-namespace 27 | in-ns governs later forms; each eval records read ns | KEEP HB |
| L689 assigned-namespace-seeds-the-run-and-its-receipt 86 | starting ns from assignment reaches receipt | SUBSUMED-BY C1005 + T1280 |
| L401 committed-ending-namespace-seeds-a-resumed-fold 117 | resume uses committed ending ns without a fabricated ns row | KEEP |
| C1033 contracted-redefinition-exactly-replaces-the-row 25 | redefinition replaces source, drops doc/workload | KEEP HB |
| C1059 a-refused-contract-commits-a-receipt-and-no-row 21 | invalid contract → error receipt, no row | KEEP HB |
| C1219 refused-runtime-schema-registration-mutates-neither-row-nor-projection 29 | invalid schema → error, no row/attr, global untouched | SUBSUMED-BY C1059 + C1332 |
| C1711 a-real-evaluation-that-runs-away-is-stopped-and-recorded 20 | time limit interrupts and records | KEEP HB |
| L236 evaluation-request-projects-the-admitted-form-and-cluster-controls 27 | private request map carries ns/caps/limit | SUBSUMED-BY C1711 + C1005 |
| C1732 a-red-form-routes-to-its-namespace-owner-and-the-fold-continues 81 | red form flat error; sibling runs; one settle tx; no assignment | KEEP HB (1742–1758) |
| G331 a-goal-is-a-message-and-the-attempt-retains-its-own-failures 122 | two in-ns attribution; fold continues; one tx; unsettled plan | SUBSUMED-BY C1732 + C1005 + G483 |
| G454 a-computation-using-a-failed-definition-retains-its-error 28 | failed def and its use both red | SUBSUMED-BY C1732 |
| G483 completion-prose-does-not-erase-failed-evaluations 34 | completion reply delivered to root; reds stay unrouted; plan unsettled | KEEP |
| L264 asked-value-preserves-reply-and-problem-precedence 36 | completion reply outranks problem assignment (all redefs) | SUBSUMED-BY G483 |
| C3025 a-batched-turn-commits-only-queryable-definition-facts 65 | tests-reaching, calls, contract/child refs from one batch | KEEP HB; residue = removed `:seon.db/read-result` guard |
| C2877 delimiter-repair-is-span-local-and-precedes-intent 147 | repair bounded/idempotent; repaired form evaluated; odd map stays an error | KEEP HB; residue = <300 ms timing asserts |

### 12. Namespace mutation and runtime declarations

| test | proves | verdict |
|---|---|---|
| C604 ns-unmap-retracts-the-owned-function-after-the-terminal-commit 35 | ns-unmap retracts fn row after commit; `committed-row?` flips | KEEP HB |
| C661 qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context 26 | dynamic ns-unmap retracts; fresh ctx can't resurrect | SUBSUMED-BY C604 |
| C709 import-only-ns-unmap-installs-exactly-after-its-context-commit 45 | import mask committed as data; run ctx and fresh ctx see it | KEEP HB |
| C688 absent-foreign-ns-unmap-commits-and-mutates-the-run-sci-ctx 20 | foreign ns-unmap reaches run ctx (evaluate spy) | SUBSUMED-BY C709 |
| C818 import-addition-is-ordinary-data-and-reacquires-exactly 27 | import addition stored as symbols, reacquired | SUBSUMED-BY C709 |
| C1081 runtime-schema-registration-commits-the-evaluated-form-and-attribute 64 | evaluated EDN row, one commit with receipt, attribute only when persistent | KEEP HB |
| C1146 runtime-schema-key-changes-pass-the-one-usage-guarded-decision 39 | unused key refines; run stays open | KEEP HB |
| C1186 runtime-schema-unregister-removes-one-unused-global-schema 32 | unregister retracts row/attr/projection | KEEP HB |
| C1303 runtime-tests-install-run-redefine-and-delete-exactly 28 | deftest install/redefine/unmap retracts test row | KEEP HB |
| C1332 incompatible-clusters-alternate-runtime-schema-validation-without-bleed 62 | two worlds: A/B/A projections validate independently | KEEP HB (genuine sibling) |
| C1395 accepted-first-party-definition-reaches-an-existing-agent-next-turn 68 | accepted core redef reaches author and peer; restoration reaches both | KEEP HB |
| C1464 another-agent-sees-a-flat-contract-violation-after-live-install 43 | peer call violating a live-installed contract is a flat error | KEEP HB |
| C1573 acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles 81 | SCI acquisition orders refers, ignores alias cycles | KEEP HB (fn rows 1618–1631; working-copy ns rows); misplaced (SCI owner) |
| C3091 singleton-enum-uses-are-members-of-their-declared-enums 28 | packaged registry lint | KEEP; misplaced (schema owner) |

### 13. Replies, dispositions and continuation

| test | proves | verdict |
|---|---|---|
| C1832 an-unreadable-reply-is-a-settled-form-with-paid-attempt-evidence 47 | unreadable/prose reply: one terminal eval, attempt usage, no core fault, in history | KEEP HB |
| N209 prose-refusal-arrives-before-done / N212 empty-reply… / N215 comment-only… 2+2+2 | no-form replies error and show in next prompt | SUBSUMED-BY C1832 |
| C1880 a-prose-only-provider-reply-settles-without-parking-the-turn-proc 52 | reader refusal leaves proc unparked, zero write refusals | KEEP HB; residue = `:seon.error/kind` guard |
| C1951 a-combined-evaluation-projects-every-terminal-receipt-datom 51 | shown, output, declaration, closure in one commit; one install | KEEP HB |
| C1933 a-completing-disposition-closes-in-the-terminal-transaction 17 | complete closes in terminal tx (fake evaluator) | SUBSUMED-BY C1951 |
| N224 completion-replies-end-the-session 2 | `seon.run/complete` → `:completed`, idle | SUBSUMED-BY C1951 |
| C2003 a-waiting-disposition-frees-the-agent-and-keeps-its-note 40 | wait closes in one commit, no errors, note kept | KEEP HB |
| N218 done-ends-the-session 2 | done → `:wait`, idle | SUBSUMED-BY N206 |
| L1048 a-disposition-is-read-only-when-it-really-is-one 10 | only real disposition values read | KEEP |
| N206 read-results-arrive-before-done 2 (+175 helper) | continuation shows read result in next prompt; no outside wake, bound unchanged | KEEP HB (helper 39–40) |
| N221 provider-progress-has-its-own-bound 2 | slow provider not cut by eval limit | KEEP HB — sleeps 2×10 s (see findings) |

### 14. Provider attempts, settings and failure

| test | proves | verdict |
|---|---|---|
| C2638 generated-model-attempt-traces-preserve-presence-and-episode-laws 8 | generated failures × backup × retries: rows = oracle, prefixes committed before each call | KEEP HB |
| C2150 one-successful-call-leaves-exactly-one-attempt-fact 32 | one success → one row, no error/failover/disposition | SUBSUMED-BY C2638 |
| C2726 a-held-runs-paid-call-is-never-duplicated 39 | open→call interleaving dispatches once | SUBSUMED-BY C2638 |
| L108 attempt-request-assembles-evidence-and-optional-provenance 34 | private request map carries failover-from/delay | SUBSUMED-BY C2638 |
| L143 provider-targets-resolves-once-and-omits-backoff-for-a-backup 50 | backup → empty schedule (all spies) | SUBSUMED-BY C2638 |
| C2377 an-unpaid-failure-with-a-backup-makes-exactly-two-calls 67 | backup keeps prompt; system segment = failover notice of committed fact | KEEP HB |
| C2183 successful-call-persists-the-providers-open-usage-document 41 | usage document, cache tokens, retained reasoning, finish reason | KEEP HB |
| C2288 reasoning-starvation-persists-usage-finish-and-the-named-error 41 | starvation persists usage/finish/reasoning, named error | KEEP HB |
| C2330 reasoning-only-time-limit-persists-its-flat-diagnostic 46 | time-limit diagnostic on the error fact | SUBSUMED-BY C2288 |
| L92 attempt-evidence-prefers-completion-and-falls-back-to-error-data 15 | private evidence merge | SUBSUMED-BY C2288 + C2183 |
| C2225 a-partial-stream-truncation-is-a-durable-nonfailure-attempt-fact 62 | truncation fact, attempt not failed, plan settles | KEEP HB |
| L194 attempt-settlement-updates-the-registered-model-gauges 41 | model gauges with attempt | KEEP |
| T41 attempt-model-identity-survives-descriptor-retraction 41 | attempt keeps model value after descriptor retraction | KEEP |
| L776 prompt-and-call-resolve-once-record-settings-and-see-next-turn-config 152 | settings-edn on attempts; config apply seen next turn (overlay-count spies) | KEEP; residue = call-count spies |
| L519 two-agents-resolve-one-config-row-with-ordinary-inheritance 35 | agent overlay inherits cluster | KEEP; misplaced (config owner) |
| C1655 a-lost-model-call-leaves-a-durable-readable-reason 55 | provider failure closes run, occurrence message, next prompt shows it | KEEP HB |
| P290 terminal-provider-refusal-is-durable 36 | missing credential settles, closes, error fact | SUBSUMED-BY C1655 |
| N227 provider-refusal-defers-the-trigger 4 | refusal defers trigger | SUBSUMED-BY W624 + C1655 |

### 15. Messages, prompt, streaming, projection, boot coverage, render, arming

| test | proves | verdict |
|---|---|---|
| C2651 a-turn-delivers-what-a-form-asks-to-send-and-still-finishes 46 | send inside `do` delivers before settlement; chain depth; both idle | KEEP HB |
| C2699 a-refused-delivery-becomes-a-durable-error-fact 26 | unknown recipient is a returned value; no message | KEEP HB |
| L362 delivery-rows-projects-rows-and-every-refusal-transaction 38 | private delivery map (redefs) | SUBSUMED-BY C2651 + C2699 |
| C3387 a-run-prompts-from-its-opening-database-value 50 | prompt excludes later message; next run sees it | KEEP HB |
| C3522 streaming-writes-zero-datoms-test 96 | sink handed; partial observed; no stream datoms; reply exact; repaint | KEEP HB; residue = stream-family guard |
| C3621 concurrent-streams-share-one-conn-test 105 | two agents settle from facts; sliding-1 never parks | SUBSUMED-BY C3522 (non-parking is core.async's guarantee) |
| C3727 a-turn-hands-its-clusters-projection-to-every-database-call 64 | bare-thread turn rebuilds no projection | KEEP HB |
| L67 read-evidence-settlement-reuses-the-outer-transaction-codec 24 | 1,024 evidence rows without projection rebuild | KEEP; residue = <200 ms assert |
| L1172 everything-the-loop-writes-is-installable-by-boot 13 | committed ∪ wake attributes ⊆ canonical install | KEEP |
| L1015 the-committed-set-is-computed-and-covers-what-the-loop-writes 13 | committed set has turn/eval/attempt families, not `:to` | SUBSUMED-BY L1172 |
| L1029 the-committed-set-extracts-map-entries-by-shape 18 | extraction ignores map properties | KEEP |
| L1186 a-boot-built-database-takes-every-row-the-turn-writes 83 | raw store accepts hand-written turn/attempt rows | SUBSUMED-BY L1172 + every real-turn survivor (canonical schema) |
| T776 evaluation-ai-is-only-the-repl-session 51 | REPL render: one datum, interrupted, triage | KEEP; misplaced (repl owner) |
| T2163 fault-unit-lists-agent-faults-newest-first-with-run-links 32 | faults HTML newest first with run links | KEEP HB; misplaced (error owner) |
| P237 concurrent-arms-share-one-graph 52 | two concurrent arms get one graph entry | KEEP (creation-tx given `:seon.agent/branch`) |

## Fixture sweep: survivors that depend on hand-built rows

The sweep needs to touch only survivors. The sites that are dropped together with their subsumed tests are
listed so that no work is wasted on them.

- **T `registry/cluster-branch` (15 sites).** Nine sit in survivors: 309 (T304), 329 (T323), 655 (T769
  fixture), 1196 (T1189), 1287 (T1280), 1360 (T1352), 1397 (T1386), 2109 (T2106) and 2167 (T2163). Six go
  away with their tests: 909 (T902), 1096 (T1088), 1144 (T1141), 1515 (T1507), 2076 (T2069) and 2145 (T2142).
  Bare `{:seon.agent/id …}` rows with no branch sit in survivors T1926 (1936) and T2200 (2215). The local
  `namespace-row` (T33) is still called from 657, 1194, 1285, 1300, 1357–58, 1395 and 2353. Its only other
  caller, 907, is deleted.
- **C `agent-row` (138, cluster-branch) and `agent-evaluations` (109, queries by that branch).** Every
  `with-cluster` survivor inherits these through fixture seed 239–243, which also writes a bare
  `{:seon.ns/name 'seon.schema}` at 241. `agent-row` is also called directly by C846 (866), C1464 (1489),
  C2651 (2656) and C3231 (3238). Its caller at 3637 goes away with C3621. Other hand-built rows: C846
  (bare ns row 869 and fn row 870–873), C561 (566–569), C1732 (1742–1748, 1758), C1395 (namespace reassigned
  at 1406) and C1573 (fn rows 1618–1631).
- **Other files.** L1059 (1068–1071, agent row with no branch). L's `with-database` (1283–1292) is used by
  survivors L1530 and L1612. N's `prove-session` (39–40: root agent with a literal ns map). P64 (71–72, root
  ns map not yet converted). P327 (347–350, agent rows with no branch). W and G use only `agent-tx` or
  `creation-tx`. They also hand-write turn, attempt and evaluation rows, which are observations rather than
  program rows.

## Residue inside survivors (strip during conversion, not separate tests)

- **Retirement guards.** C3025 `:seon.db/read-result`, C3522 `:seon.ai.stream/*`, C1880 `:seon.error/kind`
  and L1059 "dissolved notice not re-introduced".
- **Measurements asserted as behaviour.** T769's `[16 6 2]` and `[30 16 2]` datom counts, and its second
  refork run. C2877's install/bookkeeping < 300 ms. L67 < 200 ms. These should move to committed measurement
  scripts, as §9a S12 does for the survey's measurements.
- **Spies to drop (H6).** L776 overlay/resolution counts, C688/C709 evaluate-ctx capture and C1951/C846
  install spies. Assert the durable facts instead.

## Findings outside this document's scope (no issue filed; this lane may edit only this file)

1. T1386 declares `:seon.test/long-ms 60000`, citing a measured 20.54 s. Under CLAUDE.md, anything over 10 s
   needs explicit owner authorization and an issue. No issue for it was found under `docs/`.
2. N221 sleeps `2 × 10,000 ms` inside the provider stub (N:107–110). That exceeds the 5 s default bound by
   design.
3. The passive Read/Edit hook ran publication on the shared default during this read-only work. It reported
   up to 14.9 s, 2.8 s and 2.5 s (`seon.schema/call-with-projection`, `seon.db/carry-projection-state`),
   plus `refresh-source!` refusals (`cluster-connected`). The design's §10 already notes the same class. This
   is foreign to this lane.
4. Misplaced survivors (C3091, C1573, T776, T2163, L519) belong to their owners' test namespaces.

Unavailable evidence: no JVM was started, no tests were run, and the MCP was not queried. Recorded
green/red state for these files is as the survey reports it, and these verdicts do not rely on it.
