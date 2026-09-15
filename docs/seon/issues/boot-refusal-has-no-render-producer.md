---
type: issue
status: open
severity: friction
tags: [issue, render, runtime, class/n1, wave/operator-status-face]
---

# A boot refusal prints one ~9,000-character line that repeats itself four times

## Problem

When a cluster refuses during boot, `bin/seon start` prints the whole failure
as a single unbroken line of EDN: the same message four times, a full stack
trace, the entire boot instance (store object, socket object, executors,
connection), and the operator's own re-`pr-str` of the prepl event vector
around all of it.

Reading it requires copying the line into an editor. The one fact a reader
needs — WHICH LAYER refused and WHY — is present four times and findable none
of them.

## Evidence

2026-08-07, reproducing
[a-cohosted-second-cluster-cannot-boot](a-cohosted-second-cluster-cannot-boot.md)
on isolated root `tmp/cohost-operator`. The refusal printed
`#:seon.fresh-operator{:events [...]}` on one line containing:

- the message `seon.cluster/require-activation! violated its contract …`
  verbatim FOUR times (`:via` entry 1, `:via` entry 2, `:cause`, and the
  nested `:seon.error/message`);
- a 50-frame `:trace` including `clojure.lang.Compiler` and
  `clojure.core.server` frames that tell the reader nothing;
- `:seon.boot/instance` in full — `#object[java.net.ServerSocket …]`,
  `#object[java.util.concurrent.ThreadPoolExecutor …]`,
  `#datahike/Connection[…]`, `#object[sun.nio.ch.FileLockImpl …]`;
- the ENTIRE quoted `:form` the operator sent over the prepl, gensyms and
  all — roughly a third of the total bytes;
- a `:seon.instrument/problems` value already rendered into
  `:seon.print` face data and then `pr-str`'d back into a string, so the
  reader sees `#:seon.print{:face :seon.print/keyword, :value …}` wrappers
  around every single keyword of the real explanation.

The actual explanation, once extracted, is five short lines.

## Owner

`seon.cluster`'s boot refusal value and the operator's failure printing in
`script/seon/fresh_operator.clj`. Per the standing order that important
shapes declare their producers, a boot refusal is exactly such a shape: it
needs a declared `:seon.render/ai` producer (and an `:seon.render/html` one
for the page), naming the failed layer, the cluster, and the refusal's own
data — not a re-`pr-str` of an exception chain.

The doubly-rendered `:seon.print` face data inside
`:seon.instrument/problems` is a second, narrower defect: face data is a
render, so stringifying it into an error value hands the reader the
intermediate representation instead of the render.

## Acceptance criteria

- A boot refusal prints the failed layer, the cluster name, and the reason on
  their own lines, once each.
- The instance, the sent form, and the host stack trace are available for
  forensics but are not the default face.
- `:seon.instrument/problems` reaches a reader as rendered text, never as
  `pr-str`'d `:seon.print` face data.

## N1 disposition — 2026-08-12

Still open outside this lane. Declare named AI and HTML producers on the boot
refusal schema, implement them in the `seon.cluster` owner, and make
`script/seon/fresh_operator.clj` print that projection instead of exception or
prepl-event internals. The nested `:seon.instrument/problems` value must enter
the ordinary render call, not be stringified as print-node data.

## Verified at HEAD (2026-09-16, N1 verification)

**CONFIRMED — partially repaired; the operator face is the open half.**

Repaired: the boot refusal now HAS a declared render pair.
`resources/seon/schemas/seon.boot.edn:156` declares
`:seon.boot/refused-error` with `:seon.render/ai seon.error/render-ai` and
`:seon.render/html seon.error/render-html`. A representative refusal rendered
through the live value renderer on `default` (pid 69622) is one readable
value with no instance, no sent form and no host trace:

```text
{:seon.boot/refused :seon.boot/store,
 :seon.error/diagnostic-layer :store,
 :seon.error/diagnostic-operation seon.cluster/require-activation!,
 :seon.error/kind :seon.boot/refused,
 :seon.error/message "seon.cluster/require-activation! violated its contract."}
```

Still open: the operator never renders that value. `bin/seon`'s terminal
failure face is still exception-derived —
`script/seon/fresh_operator.clj:3151-3156` catches the throwable and prints
`✗ <ex-message>` followed by one `(prn data)` line of the raw `ex-data`, and
the boot path rethrows the original failure unchanged
(`script/seon/fresh_operator.clj:1683`). Whatever the failure carries —
`:seon.boot/instance`, the sent form, `:seon.instrument/problems` as
`pr-str`'d print-node data — still reaches the terminal on one line.

A live boot refusal was deliberately NOT induced: that needs starting or
failing a cluster, which this verification is not permitted to do. The
verdict above is source-exact plus the rendered-refusal probe.

surface: operator

Fix sketch: in `-main`'s catch, when the ex-data carries a declared error
class, print `seon.error/render-ai` of that value (layer, cluster, reason on
their own lines) and put the instance/form/trace behind `--verbose` or the
retained cluster log.
