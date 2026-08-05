---
type: issue
status: open
severity: friction
tags: [issue, operator, rendering, storage, errors]
---

# Render adversarial roots outlive their fault experiment

## Problem

An intentional render-fault experiment can finish while its isolated operator
root remains unowned and unreaped.

## Evidence

`tmp/render-adversarial-root` survives at 31,370,312 allocated KiB (29.92 GiB):
30,366,868 KiB of store plus a 1,012,267,319-byte log. The log contains 132
core-fault records from the infinite renderer installed by
`tmp/render_adversarial_probe.clj:69-102`; 3.322 GiB landed at 07:39 and 25.611
GiB at 07:40. Commit `7f09f6569` subsequently bounded and deduplicated this
fault path, but the experiment root still has no declared lifecycle owner.

## Owner

The render verification harness and isolated operator-root lifecycle.

## Acceptance

- Intentional fault probes declare their operator-root claim and terminal
  evidence/reap disposition before boot.
- Repeated equal faults remain bounded and deduplicated by the current durable
  signature mechanism.
- Experiment exit stops and awaits the exact operator children before releasing
  and, when declared, deleting the root.
