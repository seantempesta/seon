---
title: "Dig: content keys of every derivation"
type: research
status: gathered; no design, no source edits
date: 2026-10-14
---

# Dig: content keys of every derivation (2026-10-14)

Gathering only. No recommendations. Source read at HEAD 86beed937 (working tree has uncommitted edits in other lanes; line numbers are from the tree as read). Live numbers are from default JVM mode, `(seon.cache/status)`, read at about 14:40Z.

Legend: VERIFIED = key expression and producer read in source or measured live. UNVERIFIED = stated in a note or not traced to the producer body.

## Totals first

- **Live default cache:** 31 kinds held (not the 48 in the 2026-10-14 cache-fill note), 506,023,779 of 512,000,000 bytes weighted (99%). Measured VERIFIED.
- **Two kinds are 61% of the budget:** `seon.db/projection` 171.1 MB and `seon.db/value-projection` 139.2 MB. Next: `seon.sci.eval/program-context` 30.3 MB, `seon.schema/declaration-projection` 29.0 MB, `seon.schema/projection` 21.4 MB, `seon.cluster/file-derivation-key` 4.7 MB, `seon.fn.analyzer/parsed-facts` 76.9 MB. Measured VERIFIED.
- **Duplicate population entries have gone since the cache-fill note.** That note recorded 62 `packaged-population` entries and 90 MB. Live now shows 4.2 MB across 3 derivations (misses = 3). The arithmetic is consistent with 3 entries at about 1.4 MB. Cause of the change UNVERIFIED.
- **Eviction is live for one kind only:** `seon.schema/projection` has 1 eviction. Every other kind has 0.
- **Keys by what they name:** most held kinds key by source text, a digest, or a declaration value (content). A few name a commit, a store, a Datahike revision, or a branch scope; those are marked "More" in section 3.
- **Red test results are never retained across branches.** `retain-greens!` records only greens (runner.clj:2313). Reds are re-derived per request (ctx-cost.md). This is the asymmetry against the owner's example: a green crosses branches, a red does not, though the same content argument applies to both. VERIFIED from code and ctx-cost.md.
- **Program-context key is content plus plan digest, but its base is the commit.** `cluster-base-key` names the loaded store and commit (sci/eval.clj:3880 and 3955). VERIFIED.

## 1. Every held `seon.cache` kind on default

Columns: bytes, hits, misses (= derivations run since boot), evictions. Measured VERIFIED. Key = the key expression as written at the producer call site. Weigher named as written; its calibration is not measured unless stated.

| Kind | bytes | hits | misses | evict | Producer (file:symbol) | Key as written | Content? | Weigher |
|---|---:|---:|---:|---:|---|---|---|---|
| seon.db/projection | 171,132,346 | 10 | 16 | 0 | db.clj `content-projection` | `[::declaration-content builder (declaration-content-key database)]` | builder digest + declaration content | `projection-bytes` (not read) |
| seon.db/value-projection | 139,234,378 | 1 | 13 | 0 | db.clj `content-projection` (alias kind) | same key, other kind | same | same |
| seon.fn.analyzer/parsed-facts | 76,883,866 | 44 | 572 | 0 | fn/analyzer.clj:710 `parsed-facts` | `source` (the text) | content (text) | 512 + 6·chars + 160·symbols |
| seon.sci.eval/program-context | 30,345,737 | 2 | 4 | 0 | sci/eval.clj:3955 `derive-program-context` | `base-key` (cluster base) or `program-key` | commit + plan digest + projection-delta + armed-dials (3880) | `context-weight` (not read) |
| seon.schema/declaration-projection | 29,042,974 | 23 | 4 | 0 | schema.clj:1291 | `[forms predicates]` | content (form map value) | 2550 B/form, measured ~7.7 MB vs 9.6 MB weighed (cache-fill, arithmetic) |
| seon.schema.datahike/vector-pull | 65,904 | 6,145 | 51 | 0 | schema/datahike.clj:824 | `[projection-identity cells-installed? selector]` | identity of projection (not content) | 48 B/node |
| seon.ref/document | 2,407,680 | 3,259 | 5,496 | 0 | ref.clj:109 | `(schema/sha-256 [bytes of text])` | content | 64·(headings+spans) |
| seon.program/source-resolver-names | 8,192,514 | 8,229 | 10,041 | 0 | program.cljc:513 | `(id/id [source qualifiers symbols] 64)` | content | ~128 B/name |
| seon.cluster/file-derivation-key | 4,679,936 | 1,678 | 1,678 | 0 | cluster.clj:1731 | `[code kondo input (::key world) start]` | content of inputs + world | 512 + 64·|start| |
| seon.cluster/file-schema-keys | 4,382,080 | 0 | 1,118 | 0 | cluster.clj:1647 | `key` (per file; not traced) | UNVERIFIED | 384 + 64·n |
| seon.schema.edn/packaged-population | 4,236,408 | 18,380 | 3 | 0 | schema/edn.clj:402 | `(::digest read)` of URL digest | content digest | 376 B/form |
| seon.task/file-declarations | 6,378,922 | 0 | 303 | 0 | task.clj:366 | `[forms path text]` | content (text) + forms | 256 + 2·text + 512·n |
| seon.program/authored-shapes | 731,568 | 18,294 | 3 | 0 | program.cljc:401 | `(schema.edn/declaration-digest)` | content digest | per family |
| seon.schema/projection | 21,429,662 | 3 | 3 | 1 | schema.clj:2429 | `[forms function-contracts options]` | content (whole maps; hashed per lookup) | 2850 B/form + per-contract, measured ~8.9 MB vs 10.7 MB weighed |
| seon.schema/projection-alias (db) | 20,736 | 68,627 | 86 | 0 | db.clj:1502 | `alias-key` | alias of content key | `alias-bytes` |
| seon.cluster/derivation-world | 1,820,448 | 3 | 3 | 0 | cluster.clj:1715 | `[forms dependencies row-shapes]` | content | `derivation-world-weight` |
| seon.sci.eval/loaded-digests | 3,517,440 | 0 | 2 | 0 | sci/eval.clj:1365 | `[(store-identity loaded) (db/commit-id loaded)]` | store and commit (not content) | 256/entry |
| seon.sci.eval/overlay-plan | 48,708 | 0 | 2 | 0 | sci/eval.clj:3014 | `[(store-identity database) basis (db/commit-id loaded)]` | store, revision basis, commit | 128·|basis| + 256·rows |
| seon.schema/declaration-predicates | 17,656 | 23 | 4 | 0 | schema.clj:1280 | `forms` | content | 4·|forms| |
| seon.schema/population-predicate-symbols | 25,792 | 6 | 4 | 0 | schema.clj:2186 | `[forms function-contracts]` | content | per entry |
| seon.schema/canonical-keys | 361,024 | 111 | 1 | 0 | schema.clj:900 | `names` | content | 96·|names| |
| seon.fn/population-targets | 66,752 | 15 | 4 | 0 | fn.clj:1516 | `[forms attributes]` | content | per entry |
| seon.program/program-attributes | 54,912 | 4 | 5 | 0 | program.cljc:80 | `[(forms) (schema-admissions)]` | content | 64·n |
| seon.program/cluster-attributes | not held | | | | program.cljc:110 | `[forms schema-admissions]` | content | 96·n |
| seon.db/builder-digest | 2,048 | 61,691 | 8 | 0 | db.clj:1744, 1763 | `commit` or `k` | commit id (not content) | `digest-bytes` |
| seon.config/effective | 36,480 | 25 | 3 | 0 | config.clj:945 | `[commit cluster-name (schema.edn/declaration-digest)]` | commit (branch-named by commit) + declaration digest | 128·n |
| seon.error.refusal/authored-namespace | 2,560 | 50 | 8 | 0 | error/refusal.clj:46 | UNVERIFIED | | |
| seon.task/task-basis | 899,584 | 1 | 2 | 0 | task.clj:202 | `[(:forms projection) content names]` | content | `task-basis-weight` |
| seon.task/tasks-tx | 1,536 | 2 | 3 | 0 | task.clj:393 | `[(:forms projection) content files basis]` | content + basis | `tasks-tx-weight` |
| seon.sci.eval/program-key | 2,560 | 2 | 2 | 0 | sci/eval.clj:1801 (`program-key-input` on snapshot) | `program-key-input` | snapshot-held input, not a digest | UNVERIFIED |

Kinds in source but not held on default (from `seon.cache/lookup` call sites, section 2 covers the test runner and renders): `seon.test.runner/green`, `reach-attribute`, `reach-attribute-head`, `reach-index-by-revision`, `reach-head`, `reach-graph`, `reach-index`, `reaching`, `reach-node`, `reach-schema-node`, `seon.render/call-reach`, `seon.render/invocation`, `seon.health/{reads,derived,replaced-roots,untested,problems}`, `seon.test/{edge-attributes,destructive-reach,commit-facts,changes}`, `seon.schema.edn/packaged-digest` (held, 960 B), `seon.ref/...`, `seon.db/behavior-digest`, `seon.schema/contract-products` (per-projection memo, `schema.clj:2797`, `4296`), `seon.schema/projection`'s `declaration-projection`, `seon.cluster/branch-attribute-changes` (held, 608 B).

Keys for these not-held kinds are in section 2 and section 3.

## 2. Other repeated derivations, and where they are cached or not

| Derivation | Where | Cached? | Key / mechanism | Status |
|---|---|---|---|---|
| Test green reuse | test.clj:2592 `carried-greens`; runner.clj:2313 `retain-greens!` | Yes, `seon.test.runner/green` | `[test-symbol reach-digest input-digest evaluator]`, no branch name | VERIFIED. Retained only when a run records it. Cross-branch: falls back to `seon.task/main-head` for a missing green. |
| Test red reuse | ctx-cost.md (astra-ctx-cost) | No (runner.clj:2313: reds never retained) | n/a | VERIFIED from code comment and doc. Each request re-selects reds from stored runs. |
| Reach per attribute | runner.clj:871 `reach-attribute` | Yes | `[attribute shape revision]`; head keyed `[owner attribute shape]` | VERIFIED. `revision` is a Datahike attribute revision, so content-equal branches with different histories do not share. UNVERIFIED whether that case arises. |
| Reach node | runner.clj:979 | Yes | the whole declaration map plus `::leaf` digest (behavior digest when a doc is present) | VERIFIED. Key hashes every declared attribute, content-valid across branches. |
| Reach index and graph | runner.clj:1007, 1240, 1254, 1261 | Yes | `revisions` map; `[test-symbols nodes schemas]` | VERIFIED. Keyed by revisions, so branch-local. |
| Program context (overlay) | sci/eval.clj:3935–3965 | Yes | `program-content-key` (3880) | VERIFIED. Key is content + plan digest; the base is `cluster-base-key` (commit). |
| Overlay re-interpretation of every value row on each program change | install-row-reruns.md (lane lane-install-row); overlay-hybrid.md | Not avoided | whole-plan re-interpretation through `install-row!` | DOC-REPORTED, reproduction in the note. Not re-traced here. |
| Render invocation | render.clj:987–1004 | Yes | `[branch-scope selected output hash(program-evidence) hash(projection-evidence) hash(selection-input)]` | VERIFIED. Includes `branch-scope`, so not shared across branches. |
| Render call reach | render.clj:878 | Yes | `(into [selected] content)` | VERIFIED. |
| Health: derived problems and reads | health.clj:113–135, 364, 421, 441 | Yes | `derivation-key database name reads`; `committed` + weight | VERIFIED. `::reads` keyed by name only (a latest-pointer); `::derived` keyed by derived key. |
| Behavior digest | db.clj:5541 `behavior-digest` | Yes | stored definition digest | VERIFIED. |
| Definition digest (writer) | db.clj:5509 `definition-digest-of` | No cache: computed per write | `program-definition-digest` of canonical row + namespace context | VERIFIED. Cost UNMEASURED. |
| Definition digest (SCI loaded program) | sci/eval.clj:1352, 1365 | Yes | `[store-identity commit-id]` | VERIFIED. Keyed by commit, not content. |
| Schema projection | schema.clj:2429 | Yes | `[forms function-contracts options]` | VERIFIED. Content key. |
| Schema contract product | schema.clj:2797, 4296 | Yes, per projection | `(cache/lookup (projection-cache projection) ::contract-products {})`, then by `form` | VERIFIED. Memo lives in the projection's cache value, so it is per projection, not shared. |
| Packaged population | schema/edn.clj:402–415 | Yes | URL string → digest → population | VERIFIED. Key is resource URL (archive path), not content. Two URLs with equal content are two entries unless the second lookup dedupes (it does, by digest). |
| Analysis (kondo) per file | cluster.clj:1715–1734; boot-source-current.md | Yes, `file-derivation-key` | `[code kondo input world start]` | VERIFIED key. Doc reports re-analysis of the same files on recursion (88 then 559 files) and rehearsal copies `.clj-kondo/.cache` (perf-analysis.md). Doc-reported, not re-traced. |
| Source publication (boot) | boot-source-current.md | No across calls | recursion in `full-source-refresh!` discards pass 1 | DOC-REPORTED. Proposed local continuation exists in boot-source-current.patch (not applied). |
| Config effective | config.clj:945 | Yes | `[commit cluster-name declaration-digest]` | VERIFIED. Commit-keyed. |
| Context units / current-state | ctx-cost.md | No, rebuilt per request | n/a | DOC-REPORTED: ~1,180 reads and 6 transactions on a first request; 821 reads on repeat. |
| Config (dials) | seon.config | Yes (effective above) | | |

## 3. What each key reads, and whether it contains exactly that

Format: reads (from the producer body) versus key. "Exact" = key covers the reads. "More" = key names something the producer does not read. "Fewer" = producer reads something the key does not hold. UNVERIFIED where the producer body was not traced.

| Kind | Reads | Key | Verdict |
|---|---|---|---|
| parsed-facts | the text only (analysis of text) | the text | Exact. Filename is stamped after (fn/analyzer.clj:716). |
| db/projection, value-projection | the declaration content of a builder commit and nearest base | `[::declaration-content builder declaration-content-key]` | UNVERIFIED whether the content key covers every input of `load-projection`. Duplicate measurement: 10 + 1 entries of similar size. |
| schema/projection | forms, function contracts, options (projection build) | the three values as passed; the key's `options` is the one with `:seon.schema/predicate-functions` filled from forms (schema.clj:2423–2426) | Exact. The key holds the derived predicate functions, which are a function of forms and contracts, so no hidden input. |
| sci/program-context (cluster base) | the loaded commit's program via `derive-base-ctx` | `base-key` = `cluster-base-key loaded` (commit + store) | More: commit and store, not content. Equal content in two commits gets two entries. |
| sci/program-context (overlay) | base context, plan, predecessor, projection-delta, armed dials | `program-content-key` = base-key + plan digest + projection-delta + armed-dials | Exact for what is listed (3880). Predecessor is an input, not in the key (overlay-hybrid.md design). |
| config/effective | effective-in: config entity of the commit, carried projection, declared projection | `[commit cluster-name declaration-digest]` | Fewer: `carried` projection is taken from the commit, so commit covers it. Exact by design, UNVERIFIED in detail. |
| render invocation | branch scope, selected, output, program evidence, projection evidence, selection input | exactly those, plus the branch scope | More: `branch-scope` (branch name) — content would suffice if evidence is complete. |
| test green | run of one test over its reach, inputs, evaluator | `[sym reach-digest input-digest evaluator]` | Exact if input-digest covers fixtures (UNVERIFIED: input-digest not traced). No branch name. |
| reach-attribute | attribute datoms at the revision | `[attribute shape revision]` | More than content: a Datahike revision. Content-equal, revision-different values do not share. |
| reach-node | declaration attributes; behavior digest if doc | the full declaration map plus leaf digest | Exact, possibly over-keyed (every attribute, including ones not read). |
| health derivations | per-name reads plus derivation key | `derivation-key database name reads` | UNVERIFIED in detail. |
| file-derivation-key | file code, kondo analysis, input, world, start | `[code kondo input world start]` | Exact per the derivation's own inputs; `world` is a key value (`::key world`). |
| packaged-population | resource URL, then population of its digest | URL → digest → population | More: the URL (archive sha) as the first hop. Content dedupes at the second hop. |
| task file-declarations | file path and text | `[forms path text]` | More: the path (a derived name). Exact otherwise. |
| task-basis, tasks-tx | forms, content, names or files, basis | those values | Exact by inspection of key. |

## 4. Identities Seon already has to key by

| Identity | Computed at | Cost | Used by | Status |
|---|---|---|---|---|
| Definition digest (writer) | db.clj:5509 `definition-digest-of`: canonical row + namespace context | UNMEASURED; computed on write | writer check; `seon.test/select` green key | VERIFIED |
| Definition digest (loaded program) | sci/eval.clj:1352, `function-digests` | cached by `[store commit]` (1365) | overlay derivation | VERIFIED |
| Behavior digest | db.clj:5541 `behavior-digest`, cached by definition digest | 256 B/entry | reach nodes with docs | VERIFIED |
| Declaration digest | schema/edn.clj:419 `declaration-digest` = packaged population digest | cached | config, shapes | VERIFIED |
| Reach digest | runner reach-node leaf digest | cached per content (reach-node) | green key | VERIFIED |
| Input digest | test run inputs | not traced | green key | UNVERIFIED |
| Evaluator identity | test run evaluator | not traced | green key | UNVERIFIED |
| Program content key | sci/eval.clj:3880 | per program context | overlay | VERIFIED |
| Commit id | `db/commit-id` | free | base keys, config, loaded digests | VERIFIED |
| Store identity | `store-identity` (sci/eval.clj, loaded digests) | UNVERIFIED | base keys | UNVERIFIED |
| Read evidence | db.clj:1357 `read-evidence-current?`: attribute revisions first (cheapest), then dependency plans | per read | render invocation reuse | VERIFIED |
| Attribute revision | Datahike; advances once per changed attribute (db.clj:1372 comment citing datahike/query.cljc:2568) | free | reach-attribute, read evidence | VERIFIED (by comment) |
| Source text / file bytes SHA-256 | `schema/sha-256` (ref.clj:109), file capture (cluster) | per file | parsed-facts (text), file keys | VERIFIED |
| Projection content key | db.clj:1608–1614 `[::declaration-content builder declaration-content-key]` | per projection | db/projection | VERIFIED |
| Projection fingerprint | schema.clj `projection-fingerprint` (fingerprint from data) | UNMEASURED | identity checks | UNVERIFIED |

## 5. Open items (not gathered)

- Trace `schema/projection` duplicate content: 10 `db/projection` and 1 `db/value-projection` entries on default; whether their forms are equal (`projection-fingerprint`) is UNVERIFIED.
- Retained size of projections (`clj-memory-meter` is on the `:measure` alias only, deps.edn:197–199). UNMEASURED.
- `seon.test` input-digest and evaluator identity, needed to judge the green key for owner's cross-branch example.
- `program-key-input` and `program-key` shape (sci/eval.clj:1801–1865).
- Why packaged-population went from 62 entries to 3 since the cache-fill note (UNVERIFIED).
