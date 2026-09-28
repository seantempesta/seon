---
type: research
title: Algorithmic fixes
date: 2026-09-28
status: partial
---

# Algorithmic fixes — 2026-09-28

## Read evidence: one pull dependency walk per selector

`seon.db/query-index-patterns` already asked Datahike to resolve the complete
collection input. It did that once per input binding, then walked every result
entity again. The pull part now resolves once and groups bindings by selector.
For I inputs, R matches and S distinct selectors, its cost changes from
O(I × (query + R × selector-work)) to O(query + I + S × R × selector-work).
Nested clause evidence retains its existing algorithm; this slice does not
claim to eliminate that separate repeated scope resolution. Uncaptured explicit
pulls now skip dependency derivation entirely. Contracts remain on the public
path; both changed private walkers now declare contracts too.

Live evidence: MCP JVM scratch namespace `scratch.astra.algorithmic`, default
pid 9778, loaded source `29bdfce8d85f9168cc62604017050b6f291382a8`, immutable
database basis 536888419. No default definitions or datoms changed. Query:
`[:find (pull ?e [:seon.fn/sym]) :in $ [?e ...] :where [?e :seon.fn/sym]]`,
using 1/10/100 real function entity ids from that database. Warm unarmed walker:

| Inputs | Before ms | Scratch after ms | Equal dependency sets |
| ---: | ---: | ---: | --- |
| 1 | 0.036 | 0.045 | yes |
| 10 | 0.176 | 0.055 | yes |
| 100 | 8.314 | 0.344 | yes |

The final scratch version also matched on collection pull without `:where`,
relation-bound variable selectors, empty inputs, wildcard fallback and negation.
The 100-input improvement is about 24× for this walker, not an end-to-end turn
claim. Allocation was not measured. A final replay measured 0.387 ms at 100.
The probe script is `tmp/astra-algorithmic-fixes-20260928/read-evidence-probe.clj`;
the candidate was evaluated only under the scratch namespace with the database
owner's aliases/imports, calling its unchanged pull helper.

Archive basis `8e9a5f32087e9aa3652c28bc52bd1e0e4d58f0a2` plus only this slice:
`clj-kondo --lint src script test --cache false`: 0 errors, 1,728 warnings,
6.328 seconds. Contracts compile: 497 files, 0 source/test findings; the same
10 pre-existing script findings as the audit (docstring.clj:285/318/334/360,
markdown.clj:1020/1050/1090/1124/1170/1215), 4.361 seconds in the checker.
Those foreign script contracts are untouched. No test request was started.
No publication/index/adoption/boot/arming implementation changed, so this slice
does not require a drill. Source/test delta: +67/−31 lines.

Root's focused tests:

- `seon.db-test/collection-pull-evidence-walks-each-selector-once` (new;
  exact pull-walker calls at 1/10/100 inputs, answer/dependency coverage,
  and zero calls for uncaptured pulls).
- `seon.db-test/collection-bound-query-evidence-covers-every-input-attribute`
- `seon.db-test/query-and-pull-append-evidence-only-when-captured`
- `seon.db-test/component-expanded-pull-evidence-detects-a-child-only-change`

Foreign boundary: `one-path-impl-2` owns turn/SCI/agent/batch/render work and
`resources/seon/schemas/seon.db.edn`. The live cluster was already faulted with
11 open faults (including turn settlement), 184 failed tests and 56 unknown
failed-test observations. None is treated as a pass or attributed to this fix.
Installed armed behavior and the new regressions await root's central adoption
and test round; scratch timing is not evidence of installed publication.

## Boot profile A: batch capability comparison's previous rows

The 7,161 single pulls in the supplied profile are reached from
`seon.fn/assert-capability-contracts!`: it compares each authored definition's
six capability fields with a separate `db/pull`. `file-entities` was already
batched. This change reads the authored batch's prior rows with one
`pull-many`, indexes by symbol (including missing rows), and retains the existing
fallback for referenced functions outside that batch. Work remains O(D) in
authored definitions, but read-owner/contract/projection entry becomes O(1)
instead of O(D). Capability admission and its contracts are unchanged.

MCP scratch `scratch.astra.capability`, same live immutable database as above:
canonical `database-manifest` artifact for `src/seon/db.clj`, restricted to
1/10/100 of its stored function rows. Before/after answers equal the supplied
artifacts in every case. Warm times in milliseconds: 0.700/0.169,
0.887/0.348, 5.037/2.315. These measure this comparison, not a claim that all
10 seconds of inclusive pull time disappear: the supplied full profile reports
1,441 ms in the 7,161 public single pulls, while its batched pulls also do real
entity decoding. Memory was not measured. Reproduction body:
`tmp/astra-algorithmic-fixes-20260928/capability-probe.clj`.

Archive HEAD `1d05b4f164bc84788defefa5fd74ef298ee2ace1` plus fn source/test only:
lint 0 errors, 1,725 warnings, 7.258 s; contracts 497 files, same 10 foreign
script findings, zero source/test findings, 5.142 s. No test request started.
`bin/seon drill --with src/seon/fn.clj test/seon/fn_test.clj` passed:
candidate `14d4f556154a65a19cf159584fe3ef22d4547f4a`; base from scratch ready
246.259 s, move ready 93.164 s, candidate from scratch ready 201.977 s;
total 673.434 s. The drill stopped its JVMs and removed its passing root.
This is the explicitly requested expensive drill, not ordinary warm latency.
Other lanes were independently drilling; no other lane's process was controlled.

Root's focused tests (not run by this lane):

- `seon.fn-test/capability-comparison-batches-prior-rows` (new, deterministic
  captured-read counts at 1/10/100 definitions: one batch, zero single pulls).
- `seon.fn-test/capability-metadata-is-one-program-graph-contract`
- `seon.fn-test/capability-indexing-refuses-every-malformed-declaration`
- `seon.fn-test/file-entities-read-in-batches-not-per-entity`

The foreign boundary remains the one-path lane and the pre-existing script
contract failures above. Only fn source/test and this evidence land in this slice.
