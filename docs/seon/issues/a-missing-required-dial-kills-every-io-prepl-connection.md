---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [prepl, config, mcp, bounded-boundaries, class]
---

# A missing required dial kills every io-prepl connection

## Problem

When a schema declares a new REQUIRED config dial and the cluster's
effective config does not yet carry its value (a lane's schema edit adopted
before `config apply`, or a commit whose default value has not been
reconciled), `seon.cluster/mcp-io-prepl` refuses `bootstrap-effective` at
connection time and the connection thread dies:

```
Exception in thread "Clojure Connection seon.cluster/default 268"
  seon.cluster/mcp-io-prepl refused bootstrap-effective at
  [:seon.config.agent/write-refusal-bound]: expected the required key …
```

Every client then sees only `Connection reset`: the MCP tools, `bin/seon
config apply` (the very command that would supply the value), the gate's
result recording, and every lane's in-process run. On 2026-09-17 (pid
63433, 14:00–14:45Z) this took default's REPL away from four lanes and the
gate for ~45 minutes and looked like a transport fault; an earlier drop
(12:55–13:09Z) had the same shape. Only a restart clears it, because boot
reconciles `config/default.edn` before the prepl opens.

## Fix shape

1. The connection seam is total: `mcp-io-prepl` serves the session and
   returns the missing-dial refusal as a typed VALUE on evaluations that
   need the dial (§2.4 — an unavailable observation is the typed unknown,
   never a dead socket); `bootstrap-effective` for the session is computed
   with the declared defaults where a decision is absent and the refusal
   attached.
2. `config apply` reaches the cluster through a path that does not require
   the effective config to be whole (it IS the repair).
3. A schema edit that adds a required dial is refused at publication unless
   the same publication carries its shipped decision (`config/default.edn`)
   — the intermediate-edit class, decided at the admission seam.

Related: `one-lanes-intermediate-edit-refuses-adoption-for-every-lane`,
`a-failing-turn-write-refires-without-bound-and-fills-the-store` (the dial
that exposed it).
