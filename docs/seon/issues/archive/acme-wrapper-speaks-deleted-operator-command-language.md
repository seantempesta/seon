---
type: issue
status: resolved
severity: friction
tags: [issue, operator, deletion]
---

# Make the ACME wrapper speak the fresh operator command language

## Problem

`bin/acme` documented and delegated commands from the deleted pod operator and
composed pod, Shadow, writer-port, capability-grant, and branding environment
state that the fresh operator does not read.

## Evidence

- Before `a4a94ba07`, the wrapper advertised `up`, `restart`, `status --edn`,
  named pod logs, cluster reset, and `gym-diffusion` while exporting the
  deleted pod/Shadow inputs.
- Repository-wide reader chasing found the wrapper in old downstream ACME
  documentation and Inspect source locks/tests, but no live fresh caller that
  required its old command language or environment composition.

## Owner

The downstream ACME wrapper boundary; no consumer-specific mechanism belongs
in fresh Seon core.

## Resolution

Commit `a4a94ba07` made `bin/acme` a strict selector for cluster `acme` under an
isolated operator root. It now maps only to fresh `start`, `config apply`,
`init`, `status`, `open`, `stop`, `down`, and `logs` forms. `--root PATH`
selects an existing isolated root; without it the wrapper uses the explicit
gitignored `tmp/acme-operator-root` default.

The old pod/Shadow/config/grant/branding environment composition and
`gym-diffusion` delegation were deleted. Unsupported legacy arguments fail
with the wrapper's fresh usage.

## Proof

- `bash -n bin/acme` passed.
- `bin/acme --root tmp/acme-wrapper-smoke status` selected only that scratch
  root and reported `0/0 clusters alive` with no orphan JVMs.
- `bin/acme --root tmp/acme-wrapper-smoke status --edn` failed with the current
  `use: bin/acme [--root PATH] status` guidance.
- A post-cut search of `bin/acme` found no old port, process, writer, request
  socket, extra-source, preload, artifact-descriptor, client-output, config,
  branding, shell/web grant, gym, pod, or Shadow composition.

## Acceptance

- Every documented wrapper command maps to one current `bin/seon` operation.
- The operator root and cluster identity are explicit.
- No deleted runtime environment or command survives in the wrapper.
