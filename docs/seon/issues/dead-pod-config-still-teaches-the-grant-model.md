---
type: issue
status: open
tags: [issue, config, agent]
---

# `config/system.edn` is dead pod config that still teaches per-agent grants

`config/system.edn` is the DELETED pod's Aero-style manifest (the
`seon-context-config` skill says so explicitly: treat it, `SEON_CONFIG`,
and `src-old/seon/config.cljs` as dead). It still sits beside the live
`config/default.edn`, and its contents teach the per-agent grant model
that ruling #20 overturned — `:seon.eval/home-requires` described as
"the DEFAULT toolbelt every agent's home ns is" plus root's "ADDITIONAL
capability namespaces". Anyone (human or agent) reading `config/` for
current truth finds a curated-grants world that no longer exists.

It was not deleted in the 2026-08-01 grant-model sweep because two
paths still reference it as a default: `script/seon/dev/config.clj:201`
and `bin/seon-hook:849` (`SEON_CONFIG` fallback). Deleting the file
without fixing those readers would break the hook.

Also in the same dead surface: `config/minimal*.edn` (five files,
pod-era variants) — verify and remove with the same change.

Fix: repoint or delete the two readers (the fresh system's config
authority is `config/default.edn` reconciled into database facts), then
delete the dead manifests. Git is the archive.

Acceptance: `config/` contains only live manifests; `rg home-requires`
returns nothing outside `src-old/` and dated research; the hook and dev
config path still work.
