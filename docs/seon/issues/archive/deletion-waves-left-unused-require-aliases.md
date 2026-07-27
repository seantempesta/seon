---
type: issue
status: superseded
severity: cleanup
tags: [issue, cleanup, runtime]
---

# Remove unused require aliases left by the deletion waves

## Problem

Thirty-eight require aliases are never referenced in their files. Some require
the namespace only for schema or load-time inclusion and should become bare
requires; the rest are dead declarations. They obscure which namespaces still
have real callers after the execution cuts.

## Evidence

A string/comment/character-aware alias scan over 417 Clojure, ClojureScript,
CLJC, and EDN files measured 38 unused aliases: 23 under `src/` and 15 under
`test/`. It found zero requires targeting a missing local namespace.

Source aliases:

- `src/my/blob/host.clj:4` `io`; `src/my/blob.cljc:15` `tokens`;
  `src/my/canvas.cljc:10` `str`.
- `src/seon/agent/ctx.cljc:16` `config.resolve`;
  `src/seon/agent/shell/internal.cljs:8,13` `str`, `tokens`;
  `src/seon/ai/dispatch.cljs:9` `ai`.
- `src/seon/client.cljs:46,63,69,71,99` `home`, `ai.dispatch`, `id`, `derive`,
  `recovery`.
- `src/seon/config/resolve.cljc:5` and `src/seon/config.cljs:50` `str`.
- `src/seon/db/writer.clj:16,19,20,28` `datahike.connector`, `datahike.db`,
  `datahike.entity`, `program`; `src/seon/db.cljc:13` `db.branch`.
- `src/seon/diffusion/scaffold.cljs:43` `retrieval`;
  `src/seon/render.cljc:39` `html`;
  `src/seon/web/datastar.cljs:34` `render`;
  `src/seon/web/serve.cljs:40` `derive`.

Test aliases:

- `test/my/blob_test.cljc:13` `db`; `test/my/ns_test.cljs:7` `ctx`;
  `test/seon/agent/fs_test.cljs:33` `code`;
  `test/seon/agent/multiagent_test.cljs:7` `ctx`;
  `test/seon/agent/testrun_test.cljs:15` `str`;
  `test/seon/ai/generate_code_test.cljs:8` `ctx`;
  `test/seon/config_test.cljs:21` `agent`.
- `test/seon/db/portable_test.cljc:6` `m`;
  `test/seon/db/writer_integration_test.clj:8` `id`;
  `test/seon/db/writer_read_ceiling_test.clj:7` `uds`;
  `test/seon/schema_projection_writer_test.clj:6-7` `tokens`, `protocol`.
- `test/seon/web/serve_test.cljs:13,20,23` `gobj`, `derive`, `render.value`.

## Owner

Each requiring namespace owns the mechanical cleanup. A require retained for
schema registration or compile-time inclusion becomes a bare require instead
of disappearing. `seon.db.program` remains owned by the O15/O16 compile-time
index wave even though its current alias is unused.

## Acceptance

- Every listed alias is either used, removed, or converted to a documented bare
  require.
- The alias scan reports zero unused aliases.
- Namespace load and schema-registration tests prove that bare-require cleanup
  did not remove intentional load-time behavior.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
