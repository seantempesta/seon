# Implementation Notes: Datalevin Migration

**Last Updated:** 2026-02-19

---

## Phase B2: Switch AI reads to Datalevin, stop XTDB dual-write

### What was done

- Flipped `seon.ai.datalevin/read-from` atom default from `:xtdb` to `:datalevin`
- Made Datalevin the primary write target in `seon.ai/start-session!`, `end-session!`, `add-message!`
- Made Datalevin the primary write target in `seon.ai.claude/persist-message!`
- XTDB writes kept as **fallback** when Datalevin connection is unavailable (e.g., in tests)
- Updated docstrings to reflect Datalevin as primary store

### Key Design Decision: XTDB Fallback

Rather than completely removing XTDB writes, we kept them as a fallback:

```clojure
;; Pattern used in all write functions:
(when-not (datalevin-dual-write! :save-session entity)
  (db/put! node :ai_sessions entity))
```

This means:

- **Production** (Datalevin available): writes go to Datalevin only
- **Tests** (no Datalevin): writes fall back to XTDB, tests pass unchanged
- **Degraded mode**: if Datalevin crashes, XTDB catches writes

---

## Gotchas

### dl-read? must check connection availability

The `dl-read?` function checks both `read-from` atom AND whether the Integrant system has a `:seon/connection-manager`. Without this check, tests (which have no Integrant system) would try to read from Datalevin and get nil.

### end-session! reads before writing

`end-session!` does a read-modify-write. When Datalevin is primary, it reads the existing session from Datalevin (via `dl-get-session`). If Datalevin is unavailable, it falls back to reading from XTDB.

### web/agents.clj still queries XTDB directly

The Observatory web handlers in `seon.web.agents` bypass `seon.ai` functions and query XTDB directly with `db/q`. This needs separate migration work. For now, the Observatory will show data from XTDB (which only gets writes as fallback).

---

## Future Improvements

1. **Migrate web/agents.clj to Datalevin** - Replace direct XTDB queries with Datalevin read functions
2. **Remove XTDB fallback** - Once confident, remove XTDB write fallback entirely
3. **Remove XTDB AI tables** - Stop creating ai_sessions/ai_messages tables in XTDB
