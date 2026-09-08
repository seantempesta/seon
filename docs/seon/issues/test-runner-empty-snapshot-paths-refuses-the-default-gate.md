---
type: issue
status: open
severity: blocker
tags: [issue, wave/test-fixture]
---

# The default test gate expands an unset empty array

Observed by turn-cut on 2026-09-08 while runner-paths was active. The invocation
`bin/test seon.repl-test seon.cluster.agent-test seon.cluster.turn-test` failed
before test startup with `snapshot_paths[@]: unbound variable` (the captured
script reported line 334). Commit `cd42689b2` contains the same expansion in
`populate_checkout_root`, at line 354 when inspected.

The lane independently reproduced the shell behavior:

```sh
/bin/bash -uc 'snapshot_paths=(); printf "%s\n" "${snapshot_paths[@]}"'
```

Exit 127, `snapshot_paths[@]: unbound variable`. An explicit namespace selection,
bare gate, and platform gate all omit `--paths` and take the empty-array path.
The reproduction is the element expansion, not `${#snapshot_paths[@]}`: the
length expression independently returned zero.

Turn-cut did not change the runner or another lane's session. Its explicit
assignment requires stopping when another lane's work blocks its gate.
Acceptance: the shipped shell runs default, explicit, and platform selections
without supplying an artificial path merely to avoid an empty array.
