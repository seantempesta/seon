---
title: "Namespace agents / steward: deletion inventory"
type: research
status: active
---

# Namespace agents / steward: deletion inventory (2026-10-14)

Gathering only. Nothing edited, deleted or run. Owner ruling 2026-10-14: the namespace agent / steward concept
is retired; tasks supersede it. Kept: `:seon.agent/namespace` (an agent's current REPL namespace), the
agent-namespace REPL behavior, and assignment (`assigned-to`). V = verified this session by rg or reading the
code; U = unverified (not run, or not resolved).

## Totals

- Pattern: `steward|ns/agents|namespace.agent|responsible.agent|unowned-namespace`.
- Code, schema, test, config, bench, skills: 206 lines (src 86, test 88, resources 22, config 2, bench 1 line, .agents/skills 8). V
- docs/seon: 228 lines. 166 are only the path `docs/prds/steward-platform/...` (a dead PRD link); 62 substantive. V
- Runtime writer: one (`steward-call`, inside `creation-tx`). V
- Runtime readers of the steward fact: `steward` (error.clj), `steward-of` (cluster/agent.clj, **zero src callers**, tests only), `agent-faults-query` (error.clj), `unstewarded-namespaces` (problems.clj), identity pulls (cluster/agent.clj, seon/agent.clj). V
- `:seon.ns/agents` is declared nowhere in code or schema (docs only, vocabulary row marked [TARGET]). V
- `:seon.error/steward` is NOT a declared attribute, yet wake.clj, turn_test.clj and wake_test.clj treat it as one. V
- Tests: no test carrying `:seon/tasks` names a steward test (checked across the test tree). V
- Task entries naming the concept: 4 task files (`seon.error`, `seon.sci.eval`, `seon.fault`, `seon.render.ns`). One names a test that does not exist. V

## 1. Schema (resources/seon/schemas)

| Where (key) | Lines | Quote | Class | Stored? | V/U |
|---|---|---|---|---|---|
| seon.ns.edn `:ns` map, `[:seon.ns/steward ...]` with `:seon.program/written-by seon.cluster.agent/steward-call` | 34-37 | "written-by seon.cluster.agent/steward-call" | RETIRED | yes, ref datom on namespace | V |
| seon.ns.edn top-level `:steward` (= `:seon.ns/steward`) | 41-44 | "The one agent that stewards this namespace" | RETIRED | yes | V |
| seon.agent.edn `:agent` render units `:seon.error/of-steward` | 49 | render unit list | RETIRED | derived | V |
| seon.agent.edn `:identity-view` `[:seon.ns/steward ...]` optional | 240-244 | "its steward" (description) | RETIRED | derived | V |
| seon.error.edn `:of-steward` (`:seon.render/derived true`) | 227-230 | "namespace-steward relations" | RETIRED | derived, no datoms | V |
| my.agent.edn `:my.agent/steward` and `:my.agent/identity` key | 3, 7 | "The agent responsible for that namespace." | RETIRED | derived | V |
| seon.problems.edn `:unowned-namespace`, `:seon.problems/unowned-namespaces` | 63-65, 86, 129-131 | derived finding family | RETIRED | derived | V |
| seon.wake.edn description | 20 | "a fault routed to its steward" | RETIRED (text) | no | V |
| `:seon.ns/agents` | - | not declared | (target only) | no | V |
| `:seon.error/steward` | - | not declared | (stale refs only) | no | V |
| seon.agent.edn `:seon.agent/namespace` | - | render unit | STAYS | yes | V |

## 2. Runtime code (src)

| Site (ns/var) | Lines | Quote | Class | Read / written | V/U |
|---|---|---|---|---|---|
| `seon.cluster.agent/steward-call` | 147-177 | "Make `agent-id` the steward of `namespace-name` only when it has none" | RETIRED | WRITES `:seon.ns/steward` (176) | V |
| `seon.cluster.agent/creation-tx` | 223-251 | "stewardship is decided inside the transaction by `steward-call`" (231) | MIXED: call at 251 RETIRED, namespace assignment 240-248 STAYS | writer | V |
| `seon.cluster.agent/identity-selector` | 263-265 | `{:seon.ns/steward [:seon.agent/id]}` | RETIRED | READ (pull) | V |
| `seon.cluster.agent/identity-form` | 271 | ";; I should know my identity, namespace, and its steward." | RETIRED (text) | - | V |
| `seon.cluster.agent/render-identity-ai` | 299 | "my namespace and its steward" (docstring) | RETIRED (text) | - | V |
| `seon.cluster.agent/render-identity-html` | 317-345 | pull 326, destructure 333, `(entry "Steward" ...)` 345 | RETIRED | READ, UI label | V |
| `seon.cluster.agent/assigned-to` | 434-450 | "Assignment is not stewardship" (438) | STAYS (docstring names steward) | READ | V |
| `seon.cluster.agent/steward-of` | 455-474 | "The agent id stewarding `namespace-name`" | RETIRED | READ (Datalog 469); **src callers: none** | V |
| `seon.agent/identity` | 19, 32-33 | pull 19; `(assoc :my.agent/steward ...)` 33 | RETIRED | READ, feeds MCP identity | V |
| `my.agent/identity` (docstring) | 13-15 | "its steward" / ":my.agent/steward" | RETIRED (text) | - | V |
| `seon.error/steward` | 1079-1088 | "The steward of the namespace of the function that produced a fault" | RETIRED | READ (`:seon.ns/steward`); callers: `commit-call` 1326, tests | V |
| `seon.error/commit-call` (routing branch) | 1323-1435 | "the steward of its function, else the escalation owner" (1423) | RETIRED branch; escalation branch STAYS | writes message-tx to steward (1435) | V |
| `seon.error/agent-faults-query` | 2530-2560 | "A fault in a namespace it stewards" (2530); or-join clause 2543 | MIXED: steward clause RETIRED; occurrence and message clauses STAY | READ | V |
| `seon.error/faults-form` / `render-faults-ai` / `render-faults-html` | 2580-2620 | "its stewarded namespaces"; "Emit the steward's read"; "routed to a steward" | RETIRED (text, plus steward clause in the reader) | READ | V |
| `seon.error.refusal` (ns docstring) | 11-12 | "routes the fault to the steward of the checker" | RETIRED (text) | - | V |
| `seon.problems/unstewarded-namespaces` | 337-353 | `(not [?namespace :seon.ns/steward _])` | RETIRED | READ; registered 499; rendered 671-672 | V |
| `seon.render.web/ensure-namespace-owner!` (2902-2912, comment 2910) | 2902-2912 | "stewardship only routes that namespace's faults" | RETIRED (owner-creation write path; the `assigned-to` read STAYS) | WRITE via create-owner | V |
| `seon.render.web/create-owner-form` | 2930-2945 | "Create an agent for ... (it starts taking turns)" | RETIRED UI | - | V |
| `seon.render.web` unowned page and owner POST | 3106-3107, 3142-3148, 3193-3207, 3228-3236 | "an unowned one renders the agentless namespace inspection with the explicit create control" | RETIRED UI and route action `create-owner` | WRITE (3207) | V |
| `seon.render.web` `agent-namespace` declare, 3034 `viewer-namespace agent-id` | 199, 3034 | (`agent-namespace` fn name) | STAYS | - | V |
| `seon.cluster.wake/inside-attributes` docstring | 180 | "a fault routed for repair carries `:seon.error/steward`" | RETIRED (stale: attribute undeclared) | - | V |
| `seon.turn/outside-wake-t` docstring | 1888 | "a fault routed to its steward" | RETIRED (text) | - | V |
| `seon.fn/reached-entities`, `published-index-entities` comments | 3853, 3907 | "a data entity (a steward agent, whose runtime...)" | RETIRED (text) | - | V |
| `seon.sci.eval/agent-namespace` comment | 459-461 | "It is not the inverse of `seon.cluster.agent/steward-of`" | RETIRED (text; name STAYS) | - | V |
| `seon.db/namespace-digest-of` comment | 5542 | "`:seon.ns/steward`, is no part of the definition" | RETIRED (text) | - | V |
| `seon.test/` comment | 1926 | "a namespace's steward agent" | RETIRED (text) | - | V |
| `seon.bootstrap` | 133 | `(:seon.agent/namespace agent-entity)` | STAYS | READ | V |
| `seon.eval.drive/default-agent-namespace` | 21, 27 | `my.agents.<id>` default | STAYS | - | V |
| `seon.cluster` (escalation owner, root) | 3050-3051 | "escalation has somewhere honest to go" | STAYS (separate concept, and the fallback when no steward) | - | V |
| `seon.cluster.wake` / `seon.turn` other "inside" wake attributes | - | - | STAYS | - | V |

## 3. Tests

| File (deftest or fixture) | Lines | Class | Note | V/U |
|---|---|---|---|---|
| test/seon/cluster/agent_namespace_test.clj (whole file: `creation-assigns-a-namespace-and-stewards-it` 15-26; reassignment 58-62; shared 92-93; steward-of 116-120; `source-bearing-namespaces-without-a-steward-derive-one-problem-line` 123-156) | 2-3, 15-26, 58-62, 92-93, 116-156 | MIXED | Assignment asserts STAY (sharing, reassignment, `:seon.agent/namespace`). Every steward-of assertion and stewardship line is RETIRED. The file name says namespace, so keep its assignment half. | V |
| test/seon/render/ns_test.clj `identity-reads-stewardship-from-the-namespace` | 14-31 | RETIRED | Asserts steward on identity pull | V |
| test/seon/cluster/wake_test.clj `a-fault-wakes-the-steward-of-the-failing-functions-namespace` | 428-462 | RETIRED | Asserts `error/steward`, `:seon.error/steward` absence (458, 462) on an undeclared attr | V |
| test/seon/error_test.clj `error-graph-steward` fixture and asserts | 126-188, 229, 1807 | RETIRED | 151 `error/steward`; 185-188 `for-agent` for steward agent id; 1807 `:seon.error/of-steward` | V |
| test/seon/fn_test.clj `index-entities-name-a-data-entity-by-its-identity` (steward-cycle) | 3062-3074 | RETIRED | Cycle test whose ref is `:seon.ns/steward`; needs a new ref after deletion | V |
| test/seon/problems_test.clj `unstewarded-namespaces` helper and unowned filter | 65-80 | RETIRED | Tests the derived unowned-namespaces problem | V |
| test/seon/render/web_test.clj unowned page and create-owner tests (`a-get-of-an-unowned-namespace-page-never-writes` and neighbours) | 537-661 | RETIRED | `steward-of` at 615, 657, 661; create-owner | V |
| test/seon/cluster/agent_identity_test.clj | 49, 125-126 | RETIRED | "Steward" card; steward in identity | V |
| test/seon/html_views_test.clj | 93-97 | RETIRED | "Steward" label | V |
| test/seon/render/value_test.clj | 687-694 | RETIRED | pull shape with `:seon.ns/steward` | V |
| test/seon/schema_test.clj | 167, 373-377 | RETIRED | `:my.agent/steward` fixture; `{:seon.ns/steward 2}` selector | V |
| test/seon/db_test.clj | 1537 | RETIRED (comment) | Says `:seon.ns/steward` is a different attribute | V |
| test/seon/turn_test.clj | 349 | RETIRED (comment) | "wakes the steward through `:seon.error/steward`" | V |
| test/seon/turn_work_test.clj | 680 | RETIRED (comment) | "A fault routed to a steward" | V |
| test/seon/render_coverage_test.clj | 304 | RETIRED (comment) | "steward errors" | V |
| test/seon/fault_test.clj `a-fault-stores-the-whole-fault-and-wakes-the-responsible-agent` | 59 | AMBIGUOUS | Name says responsible agent. Body has no steward token, so the name is the only residue. | V |
| test/seon/sci/eval_test.clj `empty-namespace-agent` | 1278 | STAYS | Namespace-agent REPL behavior | V |
| test/seon/owned_value_test.clj, test/seon/db_test.clj 2816 (`unowned` = component ownership) | 73; 2816 | STAYS | Different concept | V |
| test/seon/turn_loop_test.clj 691; test/seon/instrument_test.clj 536; test/seon/test_cache_test.clj 11; test/seon/error_test.clj 530 | - | PATH-ONLY | Literals or comments naming `docs/prds/steward-platform`. test_cache_test passes the path as a string and never opens it. | V |
| test/seon/schema/datahike_parity.edn (line 1) | 1 | RETIRED (baseline data) | Parity baseline lists `:seon.ns/steward`, `:seon.error/of-steward`, `:seon.problems/unowned-namespace`, `:my.agent/steward` | V |
| test/seon/run4_replies.edn | 1 | RETIRED (frozen transcript text) | "Read my agent identity, namespace and steward." | V |
| bench/seon/contracts_corpus.edn (single long line) | 1 | RETIRED (data) | Contract rows for `seon.cluster.agent/steward-call`, `steward-of`, `seon.error/steward` | V |

## 4. Config and skills

| Where | Lines | Class | Quote / note | V/U |
|---|---|---|---|---|
| config/default.edn (comment above `escalate-to` dial) | 349-353 | RETIRED (text) | "with no namespace steward is mailed to" / "A namespace steward still gets its fault." Dial `:seon.config.error/escalate-to "root"` (360) STAYS. | V |
| .agents/skills/datahike/SKILL.md | 99, 140, 251, 293 | PATH-ONLY | Links into docs/prds/steward-platform (broken if the PRD folder is deleted) | V |
| .agents/skills/data-modeling/SKILL.md | 87, 129 | PATH-ONLY | Same | V |
| .agents/skills/datahike/references/fork-maintenance.md | 50 | PATH-ONLY | Link into docs/prds/steward-platform | V |
| .agents/skills/seon-flow-architecture/references/decisions.md | 164 | STAYS | "namespace/agent debug variants" (route names) | V |

## 5. Tasks (resources/seon/tasks)

| File and task | Lines | Class | Names what | V/U |
|---|---|---|---|---|
| seon.error.edn (intent of `for-agent`) | 4 | RETIRED (text) | "reaches the escalation owner of a fault no steward answers for" | V |
| seon.sci.eval.edn (intent) | 170 | RETIRED (ref example) | Cites `:seon.ns/steward` as an example ref id | V |
| seon.fault.edn (intent) | 4 | AMBIGUOUS | Names test `a-fault-stores-the-whole-fault-and-wakes-the-responsible-agent` (fault_test.clj:59); body has no steward token | V |
| seon.render.ns.edn (intent) | 5 | RETIRED, DANGLING | Names test `identity-reads-stewardship-without-an-agent-cluster-attribute`. No such test exists anywhere (rg). Live test is `identity-reads-stewardship-from-the-namespace` (ns_test.clj:14). | V |
| Any task `:seon/tasks` tag naming a steward test | - | none | Steward tests carry no task tag | V |

## 6. Docs (docs/seon, excluding PRD-path-only lines)

| File | Hits | Class | Quote (short) | V/U |
|---|---|---|---|---|
| architecture/vocabulary.md:123 | 1 | RETIRED (the [TARGET] "namespace agent" row; its retired-words column lists steward, `:seon.ns/steward`, `steward-call`) | "Responsibility is many-to-many, a cardinality-many ref set on the namespace" | V |
| architecture/vocabulary.md:155 | 1 | STAYS | `my.agents.<id>` default namespace | V |
| architecture/architecture.md:216 | 1 | RETIRED | "Namespace responsibility is many-to-many `:seon.ns/agents` ([TARGET]...)" | V |
| architecture/ui.md:74 | 1 | RETIRED | "B3's target `:seon.ns/agents`" | V |
| architecture/data-modeling-guide.md:246 | 1 | RETIRED | "Namespace agents express responsibility, not assignment or identity." | V |
| specs/errors.md:17, 273, 311, 426 | 4 | RETIRED | "assigned to the producing function's steward if one is held, else to root" | V |
| schedule/cards/RT-12.md | 7 | RETIRED (whole card) | "A new error signature asks its steward or root"; names test `a-steward-is-asked-instead-of-root` (no such test in test/) | V |
| schedule/cards/RT-7.md:15 | 1 | RETIRED | "the steward of the function the work is about" | V |
| schedule/cards/README-root-tasks.md 63, 94, 115 | 3 | RETIRED | "a question to the function's steward if one is held" | V |
| schedule/agent-loop-ledger.md:910 | 1 | RETIRED | RT-12 row | V |
| issues/unowned-namespace-oversight-still-inverts-assignment.md | 15 | RETIRED | Issue is about the unstewarded problem line | V |
| issues/whole-program-index-rows-overflow-on-an-agent-steward-cycle.md | 5 | RETIRED | "agent steward cycle" in title | V |
| issues/namespace-responsibility-and-issue-workers-are-singular.md | 3 | RETIRED | "several namespace agents may work" | V |
| issues/raw-identity-projection-hides-selected-steward.md | 4 | RETIRED | title | V |
| issues/debug-feed-backstop-fault-wakes-the-viewed-agent.md | 3 | RETIRED (tags) | tag `steward` | V |
| issues/historical-function-values-do-not-identify-an-error-entity.md | 2 | RETIRED | "steward fault queries" | V |
| issues/a-throwable-fault-keeps-no-inline-evidence-at-any-plausible-bound.md | 2 | RETIRED | "the part a steward reads" | V |
| issues/provider-failure-diagnostic-is-absent-from-next-prompt.md | 1 | RETIRED | "steward-namespace query (src/seon/error.clj:1640)" | V |
| issues/generated-issues-carry-no-tests-so-start-refuses-them.md | 1 | RETIRED (tags) | tag `steward` | V |
| issues/db-test-still-expects-a-unique-agent-namespace.md | 1 | RETIRED (historical) | "Make stewardship a namespace..." | V |
| issues/an-unrelated-fixture-transaction-mints-a-half-agent.md | 1 | RETIRED | "a steward call" | V |
| issues/a-generic-attribute-scoped-render-answers-with-the-owning-entity.md | 1 | RETIRED | pull with `:seon.ns/steward` | V |
| issues/the-edit-hook-load-files-the-contract-checker-into-default.md | 1 | STAYS | file name `namespace_agent_loop_test.clj` | V |
| issues/issue-worker-opening-turn-closes-and-no-reply-turn-opens.md | 1 | STAYS | test `seon.namespace-agent-loop-test/...` (agent loop, not steward) | V |
| issues/recomputed-program-digest-disagrees-with-the-stored-run-digest.md | 2 | RETIRED (text, line 31 "steward platform" PRD wording) | U on whether line 31 counts as concept use | U |
| Other 40-odd docs/seon files | 166 lines | PATH-ONLY | Links into docs/prds/steward-platform | V |

## 7. Stored-data implications (reset follows the restart batch; datoms disposable)

- `:seon.ns/steward`: one ref datom per namespace, written only by `steward-call` in `creation-tx`. Removing the attribute (seon.ns.edn 34-37, 41-44) drops the datoms. V
- Derived only, no datoms: `:seon.error/of-steward`, `:my.agent/steward`, the identity-view steward key, `:seon.problems/unowned-namespaces`. Removing them changes schemas and renders, not stored facts. V
- Stored messages: steward-routed fault messages (`message-tx` to a steward id, error.clj 1435) are ordinary `:seon.message/to` facts and persist until reset. No schema removal needed. V
- Parity baseline: test/seon/schema/datahike_parity.edn lists the retired attributes, so it must be regenerated with the schema change. V
- Contracts corpus: bench/seon/contracts_corpus.edn carries three steward function rows. Remove with the functions. V
- `:seon.error/steward` is not a stored attribute (never declared), so no datoms to drop. V

## 8. Things that look like the concept but STAY

- `:seon.agent/namespace` (assignment; current REPL namespace), `assigned-to`, the assignment half of `creation-tx`.
- `agent-namespace` (sci/eval.clj, render/web.clj), `default-agent-namespace` (eval/drive.clj), `my.agents.<id>` default namespace.
- Escalation owner (root; `:seon.config.error/escalate-to "root"`), the fallback when no steward exists; it keeps its own tests (error_test 935-1012, fault_test 84-97, turn_test 2432-2462).
- `:seon.db/unowned-entity` and `owned-value` tests (component ownership, unrelated).
- `seon.namespace-agent-loop-test` (agent loop test namespace name).

## 9. Open questions for the deletion (U)

- The routing fallback when `steward` is absent is escalation (error.clj 1431-1435). Deletion changes only which recipient is chosen; confirm no test depends on the steward recipient count beyond error_test 229 and wake_test 428.
- fn_test 3062 (steward cycle) needs a different ref to keep the cycle-indexing guarantee.
- Tests were not run. Behavior of each retired use is inferred from source, not executed.
