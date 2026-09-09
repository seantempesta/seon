---
type: research
status: active
date: 2026-09-09
tags: [research, operator, render, test]
---

# Platform tail

Entering HEAD `053a71446`. Read AGENTS.md and its copied PRD §10 lane
rules first. Default was alive at PID 40078, HTTP 7994, PREPL 63396;
MCP health answered. Inherited untracked `build/`, `workers/`, and
`config/virtual-turns.edn` are preserved. No foreign session is operated.
All test commands use `SEON_TEST_WORKERS=3`; no full suite is requested.

## Slice 1: advertise every web binding

Read the ephemeral-port issue end to end. Adoption now retains the server;
its older rebind is gone. Boot already advertised the bound address, but
the file write was outside `seon.cluster/serve!`. The binding owner now
accepts the instance, writes its actual URL/port before returning the updated
instance, and closes the new listener if publication fails. Boot publishes
that returned instance. This moves the existing mechanism; it adds no
second advertisement writer or port derivation.

Dependency ledger: http-kit `reference-code/http-kit/src/org/httpkit/server.clj:21`
and `:27` exposes the actual bound port; `:34` returns stop completion.
First-party binding is `seon.render.web/start!`, and cluster advertisement
serialization remains `seon.cluster/write-advertisement!`. The regression
uses `seon.test-support/with-database`, real SCI acquisition, armed contracts,
and two real listeners. Keeping the first listener bound forces a different
fallback port, then the test reads the file immediately after the second bind.

Fresh scratch root: `tmp/platform-tail-root`, cluster `platform-tail`.
The first start correctly refused an unpublished root; after root-scoped
down and `init`, start used the committed
`turn_schema_no_provider_2026_09_09.edn` manifest. The committed
[probe](platform_tail_web_probe_2026_09_09.clj) installs the canonical Juniper
fixture: no-provider **true**, provider attempts **0**.

MCP JVM probe `(platform-tail-web-probe-2026-09-09/rebind!)` completed in
**5 ms** and read these exact advertisement bytes after the bind:

```edn
{:seon.boot/cluster-name "platform-tail", :seon.boot/prepl-host "127.0.0.1", :seon.boot/prepl-port 65370, :seon.boot/pid 49469, :seon.boot/start-instant #inst "2026-09-09T11:52:08.550-00:00", :seon.render.web/url "http://127.0.0.1:65427", :seon.render.web/port 65427}
```

The file includes a trailing newline. Before rebind, HTTP was **65374**;
afterward **65427**, with the same PID and PREPL. GET
`/ns/my.agents.juniper/debug?prompt=true` at the advertised URL returned
**200 / 63,977 bytes / 1.214981 s**. This proves served HTML, not browser paint.
The live probe exercised the new function loaded at scratch boot, not a
default lifecycle change. No new RESET NEEDED: no schema or captured service
input changed. Default was never stopped, restarted, or reforked.

Fast snapshot and required namespace gate: **1 test / 9 assertions / zero
failures/errors**. Namespace command:
`SEON_TEST_WORKERS=3 bin/test --paths src/seon/cluster.clj test/seon/cluster/web_binding_test.clj -- seon.cluster.web-binding-test`.
The matching `bin/test --platform --paths` command passed **83 tests / 490
assertions / zero failures/errors**. Coordinator/test phases were **22 s**
and **49 s**. Successful roots `run.nklXSh` and `run.V3GdQe` removed themselves.
Logs: `tmp/platform-tail-web-gate.log`, `tmp/platform-tail-web-platform.log`.
All completed gate/operator launchers exited. Scratch remains live only for
the next assigned history observation; final cleanup is recorded below.

Default in-place adoption converged to published commit
`6aa14915-4985-5eb7-a0ad-7a0ada2f075f`; the loaded binding's arguments are
`[instance dials]`. An initial comparison probe incorrectly passed a database
to `source/current` and received a contract refusal; the corrected probe
passes the instance's store. Neither probe changed default's lifecycle.
There was no foreign load/gate boundary and no worktree was needed.

Slice 1 owns only `src/seon/cluster.clj`,
`test/seon/cluster/web_binding_test.clj`, the web probe, this landing note,
and the ephemeral-port issue's move into `archive/`. The index is untouched.
