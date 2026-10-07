---
title: An or joined to a pattern costs a scan per binding (q-orjoin)
type: research
status: landed
date: 2026-10-07
lane: q-orjoin
---

# q-orjoin: an `or` joined to a pattern costs a scan per binding

Lane `q-orjoin`, 2026-10-07. Task `seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding` (5035789a467c).

## Where the cost was

The cost was in Seon's read-evidence walker `seon.db/query-index-patterns`, not in Datahike's query execution. It
runs only when `seon.db/*read-evidence-sink*` is bound. MCP JVM evaluations and turns bind it. A bare thread does
not, which explains why the same query was fast there.

For each input binding, `scope-patterns` resolved "candidate" rows for the nested `or`. It did this by running the
enclosing positive patterns as a separate `d/q`. Here that is `[?e :seon.program/definition-digest ?digest]`, which
does not mention `?sym`. So for every input binding the walker:

- re-read the whole attribute;
- emitted one entity-bound pattern per candidate per `or` branch;
- then ran `distinct` over the result.

Each run builds the same rows, since the top-level `extra` bindings are empty.

The stages, measured live on default in JVM mode, 10 symbols, 11,909 digest datoms:

| stage | ms | MB |
|---|---|---|
| parse (`memoized-parse-query`) | 0.21 | 0.06 |
| `query-input-values` | 0.05 | 0.05 |
| `read-declarations` | 0.02 | 0.04 |
| `query-value-type-error` | 0.09 | 0.10 |
| `encode-query-request` (query unchanged) | 0.06 | 0.14 |
| `vector-query-call` (`d/q-with-evidence`) | 5.5 | 21.4 |
| `query-call-valid?` guard, `aligned-query-arguments` | 0.03 | 0.05 |
| **`query-index-patterns`, 1 symbol** | **48.8** | **148.9** (23,819 patterns) |
| **`query-index-patterns`, 10 symbols** | **460.5** | **1,293.5** (238,181 patterns) |
| whole `seon.db/q`, sink bound, 10 symbols | 413-656 | 1,318-1,364 |
| whole `seon.db/q`, sink unbound, 10 symbols | 9.2 | 21.2 |
| raw `datahike.api/q`, cold inputs, 10 symbols | 5.2-6.3 | 21.0-21.2 |

A repeat of raw `d/q` with the same inputs hits Datahike's result cache (0.05 ms). Every raw number above uses fresh
inputs.

## Fix (BEHAVIOR CHANGE in evidence precision, sound)

`scope-patterns` now skips candidate resolution when every leaf pattern of the nested `not`/`or`/`and` is already
bound at its entity or its value by the enclosing bindings (`narrow?`). In that case each leaf names a narrow index
range on its own. A candidate row would only add an entity to that pattern. Dropping a bound field widens what a
pattern matches, so the evidence still covers every datom whose change can alter the answer.

When some leaf is unbound at both its entity and its value, candidates still narrow it. For example,
`(not [?m :seon.message/from])` keeps its per-entity patterns, as `nested-patterns-and-find-pulls-retain-scoped-index-evidence`
requires, and that test stays green.

Precision lost: a write of `[e2 :seon.fn/sym s]` for an entity without a digest now invalidates the read, where it
did not before. That is a rare spurious refresh, never a missed one.

## Before and after: the same probe in one throwaway JVM

Synthetic memory store with 12,000 digests, `walker-bench.clj`. The parent is HEAD's `db.clj` loaded into the same
JVM. Numbers are unarmed.

| symbols | parent walker | change walker | raw `d/q` |
|---|---|---|---|
| 1 | 79.9 ms / 125.9 MB / 24,001 patterns | 0.30 ms / 0.03 MB / 3 | 20-30 ms / 12.7-13.0 MB (cold) |
| 10 | 600.6 ms / 1,278 MB / 240,001 | 0.42 ms / 0.13 MB / 21 | 13-20 ms / 12.2-12.4 MB |
| 142 | 7,069 ms / 18,622 MB / 3,408,001 | 2.11 ms / 1.57 MB / 285 | 17 ms / 13.0 MB |

The evidence work is now 1 + 2n patterns, proportional to the inputs, and well within 2x of raw Datahike. The parent
at 142 symbols reproduces the live code-currency figure (9.6 s / 19 GB).

The rest of `seon.db/q` is Datahike's own cost plus about 3 ms / 0.5 MB of wrapper (sink-unbound row above). This
JVM probe was not repeated live on default after the change. The edit is not loaded there, and lanes may not load
uncommitted code into default. The live re-probe belongs after root's restart:
`(seon.db/q '[...] db syms)` under the MCP JVM sink.

## Datahike (for lane dh-fix2): the `or` relation join scans the joined attribute

Task `seon.db/datahike-joins-an-or-relation-by-scanning-the-joined-attribute` (f5b587ddd04f). The probe is
`raw-orjoin-bench.clj`: a memory store, the same JVM probe at each revision, with fresh inputs.

| rev | digests | n | `or`+join | `or` alone | `or-join`+join | digest pattern first | plain join |
|---|---|---|---|---|---|---|---|
| d9121bbb | 12,000 | 1 / 10 / 142 | 5.8-10.4 ms / 12.0-12.6 MB | | | | 2.2-5.1 ms / 0.2-0.6 MB |
| d9121bbb | 60,000 | 1 / 10 / 142 | 20.8-27.5 ms / 57.2-58.0 MB | | | | 1.2-2.0 ms / 0.1-0.8 MB |
| ee5ac0bd | 12,000 | 1 / 10 / 142 | 4.6-10.6 ms / 12.2-12.8 MB | | | | 2.6-4.4 ms / 0.2-0.6 MB |
| ee5ac0bd | 60,000 | 1 / 10 / 142 | 19.6-34.5 ms / 59.6-61.5 MB | 0.9-3.9 ms / 0.14-0.9 MB | 21.6-27.6 ms / 60.0-60.5 MB | 22.1-28.7 ms / 60.0-60.5 MB | 0.6-3.2 ms / 0.1-0.8 MB |

What the table shows:

- The query allocates about 1 KB per datom of the joined attribute, whatever the input count, clause order or
  `or`/`or-join`.
- The `or` alone is proportional to its inputs.
- The fork HEAD does not change this.

So the planner scans the pattern joined to the `or` relation over its whole attribute and hash-joins the result. It
should look the pattern up per bound `?e` from the `or`'s relation. Entry points to read:

- `reference-code/datahike/src/datahike/query.cljc` `-resolve-clause*` (the `or-join` branch), and `hash-join`;
- `src/datahike/query/plan.cljc` and `execute.cljc` (how a pattern after an `or` op is planned).

On default, this residual is 5-6 ms / 21 MB for 10 symbols. It is now the largest remaining cost of this query, and it
grows with the store.

## Other findings

- **Five `seon.read-evidence-test` tests are red at the parent da0ae05dd and unchanged by this slice.** They are the
  inbox family: the evaluated form's reads leave no patterns in the bound sink, and `as-seen-by` throws
  ClassCastException on `:other-lineage`. The parent check run is b611521179d3. Filed as
  `seon.sci.eval/an-evaluated-forms-reads-reach-no-bound-evidence-sink` (c721a1b18521).
- The top-level candidate query is the same for every input binding (`extra` is empty there). Where candidates are
  still needed, they are still recomputed per binding, for example `(not [?m :from])` over a collection of
  recipients. No live caller of that shape was measured, so it stays unfixed. Hoist it if a profile shows it.
- `seon.db/the-query-wrapper-costs-36x-datahike` is unchanged. This slice touches only the evidence walker.

## Design check

- **Did the design hold?** Yes. The cost was one algorithm: evidence work proportional to (inputs × candidate rows)
  instead of inputs. The fix is a guard in the existing walker. It adds no cache, no new path and no new state.
- **Awkward parts:**
  - The walker issues its own `d/q` beside the read it records. A Datahike-supplied dependency plan with bound
    patterns would delete it. That is TARGET, not installed.
  - The 128-pattern bound still sends 142-symbol reads to replay, as before.
- **One happy path.** This serves the read → evaluate → record step: recording a read's evidence. The existing owner
  is the walker. The slice deletes 3.4 M patterns of work per 142-symbol read and adds no mechanism.

## Proof

`bin/seon check src/seon/db.clj test/seon/read_evidence_test.clj` with `--test seon.read-evidence-test` plus
`seon.db-test/collection-bound-query-evidence-covers-every-input-attribute` and
`seon.db-test/collection-pull-evidence-walks-each-selector-once` (run 1ec52963941b):

- the new table test `a-disjunction-joined-to-a-pattern-keeps-evidence-proportional-to-its-inputs` is green, and is
  red on the parent (run b611521179d3);
- the nested-scope test and both db-test evidence tests are green;
- the only reds are the five pre-existing ones listed above.
