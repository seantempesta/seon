---
type: issue
status: verified
severity: friction
tags: [issue, agent]
---

# `tmp/seon-port` was a single non-namespaced file shared by every cluster's pod

## Symptom (observed live, 2026-06-28)

With BOTH clusters up, `bin/seon status` AND `bin/acme status` printed the SAME
`pod port: 7980` — the acme pod's port — regardless of which cluster you asked
about. The live default pod was genuinely bound to 7890 (curl `http://127.0.0.1:7890/`
→ 200), yet its supervisor reported 7980.

## Root cause — one shared port file, last writer wins

Both clusters' pods wrote their bound HTTP port to the SAME hardcoded path
`tmp/seon-port` on boot (`seon.web.serve/write-port-file!`). acme is otherwise a
fully isolated second cluster — its processes live in `tmp/proc-acme`, its store
in `data/clusters/acme`, its sockets in `tmp/acme-cluster-*.sock` — but the port
file was NOT namespaced. So whichever pod booted last clobbered the file, and
both `bin/seon status` and `ready_check pod` (which curls
`http://127.0.0.1:$(cat tmp/seon-port)/`) read the OTHER cluster's port. A latent
isolation leak: `ready_check` for one cluster's pod could false-pass against the
other's. (Inside `cluster_reset` it was harmless — the pod is already gated on
its own wire-server socket and the port check is only confirmation — but it was
real cross-talk waiting to bite.)

## Fix — per-cluster `SEON_PORT_FILE`, exactly like the proc dir + sockets

The pod ALREADY read `SEON_PORT_FILE` (env, default `tmp/seon-port`) at both its
write site and idempotent-reuse site in `seon.web.serve` — so NO `src/` change
was needed. The leak was purely in the supervisor, which hardcoded
`tmp/seon-port` at every read site.

`bin/seon`: define + export the default and route every site through it.

```bash
SEON_PORT_FILE="${SEON_PORT_FILE:-tmp/seon-port}"
export SEON_PORT_FILE
```

Read/write sites rerouted (all now `"$SEON_PORT_FILE"`):

| site | what it does |
|---|---|
| `process_ready_hint pod` | the "ready:" hint line |
| `ready_check pod` (`[ -f … ]`) | gate: port file exists |
| `ready_check pod` (`curl …$(cat …)`) | gate: HTTP answers on the bound port |
| `cmd_status` | the "pod port:" status line |
| `cmd_nuke` (`rm -f`) | drop the default cluster's port file |

The pod itself is the sole WRITER (`write-port-file!` → `or SEON_PORT_FILE
"tmp/seon-port"`); `export SEON_PORT_FILE` makes it inherit the SAME path through
nohup.

`bin/acme`: one new export beside its other isolation vars.

```bash
export SEON_PORT_FILE=tmp/seon-port-acme
```

The default cluster is byte-identical: `SEON_PORT_FILE` resolves to
`tmp/seon-port` exactly as before.

## Live proof (both clusters up)

```
tmp/seon-port       = 7890
tmp/seon-port-acme  = 7980

bin/seon status  → ● pod pid=63340 … pod port: 7890 → http://127.0.0.1:7890
bin/acme status  → ● pod pid=76119 … pod port: 7980 → http://127.0.0.1:7980
```

- `bin/acme restart pod` (with the fix) wrote ONLY `tmp/seon-port-acme`; the
  default's `tmp/seon-port` stayed `7890` — acme's boot no longer clobbers it.
- Each cluster's `ready_check` now reads a DIFFERENT file pointing at its OWN
  port, so it can no longer false-pass against the other cluster's pod.
- Live DEFAULT cluster UNTOUCHED: pod pid `63340` + wire-server pid `63196`
  identical before and after; HTTP 7890 → 200, HTTP 7980 → 200.

Note: the stale `7980` that `tmp/seon-port` held at the start of this fix was the
bug's own damage (acme's pre-fix boot clobbered the default's 7890); it was
restored to the default pod's genuine bound port without restarting the live pod.
