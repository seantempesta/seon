---
type: research
status: complete
tags: [research, repl, database, schema]
---

# Agent-facing REPL data dogfood — 2026-08-04

## Verdict

The ordinary happy path is usable: registry discovery, `q`, `pull`, `entity`,
lookup refs, a small ambient transaction, and the owner-named unique-conflict
rejection all returned bounded ordinary data through the real SCI door. The
session found six agent-facing defects, three newly filed and three added to
existing notes. The highest-risk failure is silent false absence on invalid
reads. The ugliest face is a 10,000-row transaction whose entire result became
bare `...` with no success evidence or retrieval identity.

No production source was edited. All database writes were confined to scratch
branch `codex-repl-dogfood-0804`. Every evaluation below used MCP `eval_clj`
with `mode="door"`; no raw JVM evaluation was used.

## Grounding and dependency ledger

The localized runbook, active working edge, current 2026-08-04 rulings,
architecture map, data model, toolkit, issue convention/index, and the four
selected repository skills were read before the session. In particular:

- [SCI execution-runtime runbook](docs/prds/sci-execution-runtime/AGENTS.md)
- [runtime plan](docs/prds/sci-execution-runtime/plan/README.md), including the
  2026-08-04 per-run fork and curation rulings
- [working edge](docs/prds/sci-execution-runtime/plan/unsettled.md)
- [runtime architecture](docs/seon/architecture/architecture.md)
- [data model](docs/seon/architecture/data-model.md)
- [agent toolkit](docs/seon/architecture/toolkit.md)
- [issue authority](docs/seon/issues/README.md)

Selected dependency revisions and boundaries:

| Dependency or owner | Selected revision / path | Boundary exercised |
|---|---|---|
| Datahike fork | `574c5f0f0db9` | `reference-code/datahike/src/datahike/api/impl.cljc:30-48`, accepted transaction shapes |
| Malli fork | `80138076960e7820523b4cb932c5b5d1936d4e7f` | registered request/entity contracts |
| SCI fork | `2db3358cba913b6fbbe49c7b5b34d7ac72715924` | guarded door evaluation and admission |
| Seon database owner | `src/seon/db.clj:540-711,862-1113` | ambient reads, writes, bounded transaction projection, rejections |
| Schema admission | `src/seon/schema.clj:913-950,1774-1786` | one top-level runtime declaration and projection validation |
| Door and print owners | `src/seon/sci/eval.clj`, `src/seon/sci/admit.clj`, `src/seon/print.cljc`, `script/seon/dev/mcp.clj:532-570` | actual value face, elision, MCP blob retention |
| Existing proof | `test/seon/db_test.clj:326-436` | bounded ambient report and owner-named rejection expectations |

The selected Datahike skill still names an older gitlink. That authority drift
is already tracked in
[Update the Datahike skill after every selected fork commit](docs/seon/issues/datahike-skill-pin-drifted-after-cache-cleanup.md)
and was not duplicated here.

## Session shape

`bin/seon start codex-repl-dogfood-0804` reached ready in 29,166 ms inside the
already-running PID 3885. The cluster had one root agent and zero recovered
runs. `runtime_status` also reported 15 stale Vars; that fact became material
to the config check below.

Registry discovery happened before declaration and writes. A query over
`:seon.schema/key` and `:seon.schema/form` established that no `my.kb` or
`my.dogfood` family existed and enumerated the installed `seon.db` contracts.
A second registry query selected entity schemas by their declared
`:seon.db/entity true` property. The session chose the already admitted
`:seon.test.run/*` family for scratch rows because it has a string identity,
instant, and SHA string and does not alter agent lifecycle or configuration.

The new schema declaration used the real top-level form:

```clojure
(seon.schema/register! :my.dogfood/score [:int {:min 0 :max 100}])
```

It passed the isolated agent-admission projection and returned
`:my.dogfood/score`. MCP door evaluation intentionally creates no run, receipt,
or terminal transaction, so this declaration was not published as a durable
program row. The session did not fake publication with raw Datahike schema
maps. Durable data exercises therefore used the discovered, already admitted
`:seon.test.run/*` declarations.

Three representative rows were committed with `id`, `at`, and `git-sha`. The
read-back then exercised:

```clojure
(seon.db/q '[:find ?id ?at ?sha
             :where [?e :seon.test.run/id ?id]
                    [?e :seon.test.run/at ?at]
                    [?e :seon.test.run/git-sha ?sha]])

(seon.db/pull '[*] [:seon.test.run/id "dogfood-run-002"])

(seon.db/entity [:seon.test.run/id "dogfood-run-003"])
```

All returned the expected values. `q` rendered three compact tuples; `pull`
and `entity` each rendered one readable four-key map.

## Ranked findings

### 1. Invalid database identities silently look absent

**Frequency:** very high. Misspelled attributes and wrong lookup-ref value
types are normal data-session mistakes.

```clojure
(seon.db/q '[:find ?e :where [?e :seon.test.run/idd _]])
;; observed: #{}

(seon.db/pull '[*] [:seon.test.run/id 'dogfood-run-001])
;; observed: nil
```

The elegant face distinguishes caller error from valid absence: name the
unknown attribute or the identity attribute's stored string type, show the
supplied value, and offer registry-derived candidates. Filed as
[Refuse invalid database read identities instead of returning absence](docs/seon/issues/database-read-admission-treats-invalid-identities-as-absence.md).

### 2. Database request-shape errors name dependency internals

**Frequency:** high. Missing request keys and malformed clauses happen while an
agent learns the API.

Observed bounded faces:

```clojure
(seon.db/q {:args []})
;; "Cannot parse :find ...", :fragment nil

(seon.db/pull {:eid [:seon.test.run/id "dogfood-run-001"]})
;; "Cannot parse pull pattern ...", operation :datahike.pull/result

(seon.db/transact! {:not-tx-data []})
;; seon.schema.datahike/encode-transaction violated its contract ...
```

The transaction error duplicated the bad value inside serialized print-node
AST strings. A six-position Datalog data pattern returned only `Pattern
mismatch` plus `resolve-pattern-lookup-entity-id` internals. The elegant faces
name the invoked public operation, its missing key or malformed clause, and the
accepted shape. Filed as
[Make database request errors name the public operation](docs/seon/issues/database-request-shape-errors-bypass-public-contracts.md).

### 3. One schema declaration allocates about 4.65 GB

**Frequency:** medium, but it hits the first step of every new data domain and
shares a process heap with other clusters.

Two independent valid forms returned clean keywords but reported:

| Declaration | Duration | Allocated bytes |
|---|---:|---:|
| `:my.dogfood/score` | 919 ms | 4,652,159,248 |
| `:my.dogfood/label` | 907 ms | 4,652,146,872 |

The elegant behavior validates the new declaration and affected dependency
closure without rebuilding unrelated registry state. Filed as
[Stop rebuilding gigabytes of schema state for one declaration](docs/seon/issues/schema-declaration-rebuilds-four-gigabytes-per-form.md).

### 4. Large transaction and query elision loses the result's identity

**Frequency:** medium for data/import work; impact is high because the face can
erase success evidence.

A 10,000-row, 30,001-datom ambient write committed after 9.648 seconds, but
the complete visible value was only:

```text
...
```

There was no transaction ID, commit ID, datom count, success statement, or
digest. The corresponding 10,000-row sorted query showed 32 IDs then `...`.
MCP retained a digest, but drilling it reported total 8,193—the admitted 8,192
IDs plus the marker—not the original 10,000. The missing rows were recoverable
only by independently counting and rerunning a deterministic query:

```clojure
(seon.db/q
 {:query '[:find ?id
           :where [?e :seon.test.run/id ?id]
                  [(clojure.string/starts-with? ?id "dogfood-bulk-")]]
  :order-by '[?id :asc]
  :offset 8190
  :limit 20})
```

The elegant face says retained/total, names the cut reason, and supplies either
the full-value identity or a concrete continuation. Updated
[The elision marker tells an agent nothing about what it lost](docs/seon/issues/elided-marker-carries-no-count-or-identity.md).

### 5. A newly booted cluster can still expose pre-fix config faces

**Frequency:** medium in the current shared development loop.

Current source returns a bounded `:seon.config/missing-effective` error for an
unknown cluster. The newly booted branch, hosted in the old PID, returned:

```clojure
(seon.config/effective (seon.db/db) "dogfood-missing-cluster")
;; => {}
```

This is not a source regression: `runtime_status` already identified 15 stale
Vars. It proves that a successful branch boot does not mean recent faces reach
the agent surface. Updated
[Partial hot reload leaves a live JVM running mixed old and new code](docs/seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md).

### 6. Expected pull errors print twice

**Frequency:** lower than silent query typos, but every misspelled pull selector
hits it.

```clojure
(seon.db/pull '[:seon.test.run/idd]
              [:seon.test.run/id "dogfood-run-001"])
```

Datahike first wrote a timestamped `:error datahike.db.utils` line with
`resolve-datom` and raw entity ID `14194` to the REPL output, then `seon.db`
returned the same implementation message as `:seon.db/invalid-read`. The
elegant face is one flat error value; expected caller mistakes do not also
occupy stdout. Expanded
[Keep expected Datahike errors from logging beside returned values](docs/seon/issues/datahike-expected-rejections-log-full-writer-exceptions.md)
to cover the read sibling.

## Faces that passed

- The small transaction result no longer exposed `db-before` or `db-after`.
  Its bounded projection carried transaction ID, commit ID, total datom count,
  committed datoms, and tempids. The generic structural table was readable for
  ten datoms. Calling `seon.db/render-transaction-ai` produced the intended
  concise `Committed transaction ...` sentence.
- A unique namespace conflict named the real existing owner:

  ```text
  Transaction rejected: :seon.cluster.agent/namespace value
  [:seon.ns/name my.agents.root] is already held by
  [:seon.cluster.agent/id "root"].
  ```

  `seon.db/render-rejection-ai` returned exactly that concise sentence.
- An undeclared write attribute returned one bounded `:seon.db/rejected` value
  naming `:my.dogfood/unknown`; no whole writer stack entered the door result.
- `seon.config/compile-manifest` with `:seon.config/not-real` returned a compact
  structured refusal carrying `:seon.config/unknown-key` and the offending key.
- Correct `q`, `pull`, `entity`, and string-valued lookup refs were compact and
  readable. Counting the large domain returned the scalar `10000`; paged,
  ordered reruns returned precise slices including the final 12 rows.

## Verification boundary

`bin/issues-index --check` was already red before these issue edits on a
foreign lane's state:

```text
dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md: invalid-status
expected ["open"], actual "resolved", location :open
dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md:
scheduled-note-is-not-open
```

That note and its schedule entry were not edited. This report's new issue rows
were added to the index, but a clean authority-gate claim must wait for the
foreign note to be moved or reopened by its owner.
