---
type: issue
status: resolved
severity: blocker
tags: [issue, toolkit, fs, agent-surface]
---

# `my.fs/read` refuses a bounded window of a file over the read ceiling

## Problem

`my.fs/read` exists to read a WINDOW: its docstring says "Read a bounded
window of one file… Use it before editing **or when a whole file may be too
large**." It cannot do that. `:seon.config.fs/max-read-bytes` (16 MiB by
default) is compared against the WHOLE FILE, not against the requested
window, so every file larger than the ceiling is completely unreadable —
the affordance that exists for exactly this case is the one that fails.

The cause is at `src/seon/fs/jvm.clj:239-265`. `read-pass` streams the whole
file so it can report `:my.fs/digest` of the whole file, and refuses as soon
as the running total crosses the ceiling:

```clojure
(> (+ total read-count) read-limit)
(refuse! :my.fs/read-limit
         "The file exceeds the configured read ceiling."
         {:seon.config.fs/max-read-bytes read-limit})
```

`byte-offset` and `max-bytes` only select bytes to COPY out of that stream;
they never bound how much is read. So the whole-file digest promised by the
result makes the window promised by the request unachievable.

An agent that meets a 32 MiB file has no move left inside `my.fs`: the whole
read refuses, a 64-byte window refuses identically, and a tail window
refuses identically.

## Evidence

Tool-exercise lane, 2026-08-07, cluster `tools` in an isolated operator root,
driven through a real run (sci eval → effect door → `:io` → receipt). Fixture:
a 33,554,432-byte ASCII file. Complete result:
`docs/prds/sci-execution-runtime/research/probes/tool-exercise/fs-read-bounds.edn`.

Three requests, three identical refusals:

```text
request  #:my.fs{:path ".../large-32mib.txt"}
result   #:seon.error{:kind :my.fs/read-limit,
                      :message "The file exceeds the configured read ceiling.",
                      :data #:seon.config.fs{:max-read-bytes 16777216}}

request  #:my.fs{:path ".../large-32mib.txt", :byte-offset 1048576, :max-bytes 64}
result   (identical refusal)

request  #:my.fs{:path ".../large-32mib.txt", :byte-offset 33554400, :max-bytes 4096}
result   (identical refusal)
```

Each refusal cost 104–125 ms because the handler streamed 16 MiB before
giving up — a bounded 64-byte window pays the full ceiling in IO.

## Second defect in the same value: the refusal names nothing useful

The refusal reports only the CONFIGURED CEILING. It does not name the path,
and it does not name the file's actual size — the two facts an agent needs
to decide what to do next. Its own declaration disagrees with it:
`resources/seon/schemas/my.fs.edn:133` declares
`:my.fs/read-limit-error` with `:error/message "must identify the path whose
read exceeded its limit"`, yet the declared map has no path key and the
produced value carries no path. Under the loud-failure ethos a refusal
"names what was missing"; this one names only the wall it hit.

## Expected

A window request is bounded by the window. Reading `:my.fs/max-bytes` bytes
at `:my.fs/byte-offset` succeeds regardless of file size, and the ceiling
applies to the RETURNED WINDOW. The whole-file digest is the thing to give
up — it is an artifact of the current implementation, not something a window
reader needs; a windowed result can carry the window's own digest plus
`:my.fs/file-bytes`, and reserve the whole-file digest for reads that
actually cover the whole file.

The refusal that remains (a windowless read of an over-ceiling file) names
the path, the file's size, and the ceiling, and says which key to pass to
read a window instead.

## Acceptance

- `(my.fs/read {:my.fs/path <32 MiB file> :my.fs/byte-offset 33554400
  :my.fs/max-bytes 4096})` returns those 4096 bytes.
- The refusal for a windowless over-ceiling read carries the path and the
  observed file size, and `my.fs.edn`'s declared error shape matches the
  value produced.
- One regression drives both through a real capability request, not a
  direct handler call.

## Resolution (tool-repairs lane, 2026-08-08, `06c338d10`)

The class — a bound measured against something other than the thing it bounds
— is unrepresentable at this seam now. `window-pass` takes no ceiling
parameter at all: it seeks to `:my.fs/byte-offset`, reads at most
`:my.fs/max-bytes`, and digests exactly what it returns, so a window of a file
of any size is an ordinary read. `whole-file-pass` is the one arm whose result
genuinely IS the whole file (a complete read, a write precondition's digest),
so it is the only arm the ceiling bounds and the only one that can refuse for
a file's size.

`:my.fs/digest` keeps its one meaning, the whole file's digest, and is present
only when the window WAS the whole file; `:my.fs/window-digest` always digests
what was returned. Two names for two facts. The read-limit refusal now names
the path, the observed size, and the key that reads a window instead — its own
declaration always said it must identify the path and never did.

Live proof, cluster `x` in isolated operator root `tmp/repairs-check`, driven
as a REAL capability request through the full path (sci eval → effect door →
`:io` → receipt), on a 20,971,520-byte file with a 16 MiB ceiling:

```clojure
(my.fs/read {:my.fs/path "…/large-32mib.txt"
             :my.fs/byte-offset 20967424 :my.fs/max-bytes 4096})
;; settled effect result, 76 ms:
#:my.fs{:path "…", :window-digest "f67519bc…ae73", :file-bytes 20971520,
        :byte-offset 20967424, :bytes-read 4096, :eof? true, :text "st0123…"}
```

That request was an outright refusal costing 125 ms of wasted IO before this.

Recurring regression: `a-window-is-bounded-by-the-window-not-by-the-file` in
`test/seon/fs/jvm_test.clj` (handler level, fast tier), which also asserts the
refusal's path, observed size, ceiling, and remedial key.

Deviation from this note's acceptance, recorded deliberately: a WINDOWLESS
`my.fs/read` of an over-ceiling file no longer refuses either. It returns the
first `:seon.config.fs/max-inline-bytes` bytes with `:my.fs/eof? false` and
the true `:my.fs/file-bytes`, exactly as it already did for a 1 MB file — the
old refusal was the anomaly, since every `read` is inline-bounded anyway. The
refusal this note asked for survives on `read-complete`, the arm that genuinely
demands the whole file, and carries the path and observed size as specified.