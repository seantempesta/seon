---
type: research
status: current
tags: [research, agent, namespace, render, datahike]
---

# Namespace stewardship landed; the agent's namespace is no longer unique

What the namespace-steward lane changed on 2026-09-07, what it proved, and
what a reader has to know before touching this seam again. The assignment is
the "Namespace is not identity" paragraph of
[the agent record and the REPL response PRD](../plan/agent-record-and-repl-response-prd-2026-09-07.md)
§3.

Commit: `daf551e6c` — "Make stewardship a namespace fact and un-unique the
agent's namespace".

## 1. The fact that moved

| Before | After |
|---|---|
| `:seon.cluster.agent/namespace` carried `{:seon.db/unique true}` — "the namespace this agent owns; at most one agent is assigned to a namespace" | the same ref, not unique — "the namespace this agent is assigned to work in by default; several agents may share one, and an agent may `in-ns` anywhere its REPL reaches" |
| ownership was DERIVED by inverting that edge (`agent/owner-of`) | ownership is DECLARED on the namespace: `:seon.ns/steward`, one ref, read by `agent/steward-of` |

`:seon.ns/steward` is a cardinality-one ref, listed optional in the
`:seon.ns/ns` entity map (`resources/seon/schemas/seon.ns.edn`). Its
description names what routes there: faults in the namespace's functions,
complaints, and feature requests from other agents.

The old name is gone, not aliased: `owner-of` has no definition at HEAD.
Callers moved with it — `src/seon/render/ns.clj:417` (the source-less
namespace stub's "it belongs to agent X" line) and three call sites in
`src/seon/render/web.clj` (`ensure-namespace-owner!` twice, and the
canonical `/ns/{ns}/debug` response).

`seon.sci.eval/agent-namespace` stays exactly as it was — it reads the
agent's own assignment — but its docstring no longer calls itself "the
forward read of `owner-of`", because assignment and stewardship are now two
facts and neither inverts the other.

## 2. The decision is inside the transaction

`creation-tx` gained one operation:

```clojure
[:db.fn/call #'steward-call agent-id namespace-name]
```

`steward-call` reads the mid-transaction database and asserts
`:seon.ns/steward` only when the namespace has none. It is the owner law
applied literally: nothing pre-reads "does this namespace have a steward"
and hands the answer to a writer that will re-decide it. The two behaviours
this buys, both proven in
`test/seon/cluster/agent_namespace_test.clj`:

- a fresh agent creating its own namespace becomes its steward — today's
  behaviour, unchanged;
- a second agent created on an existing namespace is admitted (no unique
  refusal) and does NOT displace the first agent's stewardship.

`:seon.cluster.agent/creation-tx` widened from `[:vector :map]` to the
declared `:seon.store/transaction-data` to carry that operation. That is the
one schema key this lane touched outside its literal assignment; every
caller passes the value straight to `transact!` or `into`s it, so nothing
downstream narrows.

## 3. Stewardship does not follow assignment

The regression that says so plainly
(`reassignment-is-an-ordinary-cardinality-one-transaction`): after `alice` is
reassigned from `my.agents.alice` to `my.agents.reassigned`, `steward-of`
still answers `"alice"` for `my.agents.alice` (the namespace's fact did not
move) and `nil` for `my.agents.reassigned` (an ordinary transaction created
it and named no steward). A namespace created outside `creation-tx` has no
steward until something declares one — which is what the web route does on
first visit.

## 4. What this means for an existing cluster

**A cluster forked before this commit needs a destructive refork; adoption
in place is not enough.** Measured on the development cluster
`juniper-context` (root `tmp/juniper-context-live`) after
`bin/seon --root tmp/juniper-context-live init --dev juniper-context`
converged (`:current-src` commit `6a9ef6ac-1f21-5c49-b37b-8bf56d2ce785`):

```clojure
{:seon.ns/steward           {:db/valueType :db.type/ref
                             :db/cardinality :db.cardinality/one}   ; installed
 :seon.cluster.agent/namespace {:db/unique :db.unique/value}}       ; STILL unique
```

The new attribute installs; the retracted `:db/unique` does not un-install,
because schema reconciliation accretes. And no pre-existing namespace has a
steward — `[:find [?n ...] :where [?ns :seon.ns/steward _] …]` returned `[]`
on that cluster, so `steward-of 'my.agents.juniper` is `nil` even though
`juniper` (entity 33770) is assigned to it.

Two consequences for anyone driving an un-reforked cluster:

- the namespace page's owner line is empty for every pre-existing namespace;
- `/ns/{ns}` (not `/debug`) would call `ensure-namespace-owner!`, see no
  steward, and try to create an agent named after the namespace — which the
  surviving unique constraint then refuses. `/ns/{ns}/debug` is read-only and
  unaffected.

Database data is disposable by ruling: the fix is a refork, not a migration.

## 5. Evidence

- `bin/test seon.cluster.agent-namespace-test seon.cluster.agent-test
  seon.render.ns-test` — 34 tests, 294 assertions, 3 failures:
  `seon.cluster.agent-test/install-gate-failure-settles-commits-and-cancels-the-turn-backstop`,
  `seon.render.ns-test/fitted-full-html-leads-with-description-and-function-summaries`,
  `seon.render.ns-test/schema-closure-is-database-derived-cycle-safe-and-budgeted`.
  All three were reproduced identically at HEAD with this lane's nine files
  reverted to their committed-before content, so none is attributable here.
- `http://127.0.0.1:7766/ns/my.agents.juniper/debug` returns 200 before and
  after adoption; the two responses differ only in the snapshot `:t` and
  commit id. Its SSE feed renders 45 091 bytes of namespace units
  (`:seon.ns/name`, `:seon.ns/refers`, `:seon.ns/requires`) with no error
  value in the output — the `:seon.error/value` matches in it are the text of
  rendered function contracts.
- `bin/test seon.render.web-test` — 60 tests, 441 assertions, 5 failures
  and 1 error, none of them a namespace-route test. The two tests this lane
  edited both pass:
  `namespace-routes-admit-by-reader-and-existing-corpus-row` (no steward for
  `seon.flow` before the first visit; `"seon.flow"` after, with the existing
  creation provenance intact) and
  `canonical-debug-inspects-without-creating-a-namespace-owner`. The
  failures are `a-fresh-cluster-debug-page-renders-a-prospective-prompt`,
  `a-never-run-agents-debug-context-is-labeled-prospective`,
  `an-unavailable-prospective-context-renders-its-diagnostic-data`,
  `data-caps-a-five-megabyte-attribute-through-the-shared-floor` and
  `the-message-appears-on-the-page-wire-test` — prospective-context, floor
  and wire surfaces that this change does not touch.
- That web-test attribution is NOT baselined, unlike the three above: with
  this lane's nine files reverted, seven consecutive `bin/test` invocations
  died in the dependency class-cache prepare, filed as
  [the class-cache prepare race](../../../seon/issues/dependency-class-cache-prepare-races-concurrent-jvm-launches.md)
  (root cause located there — `admit!` catches the wrong exception, and both
  colliding digest directories were pinned by live JVMs, so clearing them by
  hand was not available either).

## 6. Out of scope, filed

[The unowned-namespace oversight line still inverts assignment](../../../seon/issues/unowned-namespace-oversight-still-inverts-assignment.md)
— `seon.problems/unowned-namespaces` still derives ownership by inverting
`:seon.cluster.agent/namespace`, which no longer answers that question.
