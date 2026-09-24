# Lane fix-runner-failure-print-bound — 2026-09-24

## Not yet proved
- The change is NOT loaded into default. The LOAD RULE forbids loading a namespace that
  redefines default's Vars and running test requests, so the regression and the affected
  suites have not run. Root runs the commands listed below.
- The value renderer's cost is still proportional to the size of each MAP it visits,
  because it sorts the whole membership before keeping 32 children
  (`src/seon/render/value.clj:482-488`). Issue:
  `docs/seon/issues/the-value-renderer-sorts-every-visited-map-whole-before-choosing-children.md`
  (a 100,000-entry map takes 959 ms). The regression uses a >200 MB vector, which is
  bounded. A million-entry overlay map would still take seconds, but it no longer builds a
  string proportional to the value.
- `:seon.test.failure/message` is still stored as `(str message)`, as the 10d629c54 ruling
  said: "the whole message stays". If a message is a huge non-string value, `str` prints
  the whole value. This is out of scope and unchanged.

## What changed (src/seon/test/runner.clj)
- `failure-report` takes `options` and stores `report-value` text for expected/actual.
  This is the same bounded text the gate log shows. Each elision names its bound
  (`:seon.print/omitted`, `:seon.print/bound-by`). A throwable is stored as its whole
  cause chain under `value/floor`, where before only `Class: message` was kept. The whole
  message is still kept for the blob tier. The whole values stay on the event in memory.
- `report-identity` (new, contracted) is the SHA-256 of the test symbol, type, bounded
  message (`message-text`), the report's bounded expected/actual text and the signature.
  It costs the render bound and never prints a whole value. `failure-identity` keeps its
  3-arity and delegates to it.
- `capture-event!` renders a failure's report, identity and message once, before the
  `swap!`, so a retried swap never renders again.
- `printable` (whole-value `pr-str`) is deleted.
- Both fns carry contracts (`:seon.test.failure/report`, declared in seon.test.failure.edn).
- Net: runner.clj +83/-75, or +45/-37 ignoring whitespace (the let re-indents the swap); the
  test adds +47.

## Evidence (default JVM, scratch ns `lane-fix-runner-print-probe`, read-only probes)
- Before: one `pr-str` under print-length 32 / level 8 of `(vec (repeat 20 <1 MiB string>))`
  took 713 ms, allocated 112 MB and produced 20,971,581 bytes. The old path did this four
  times per failure event (identity ×2, report ×2), plus a render for the message. At
  200 MB that is about 7 s and more than 1 GB per print. At 2 GB it exceeds the array
  limit, which is the observed OOM.
- After: `report-value` for expected `(vec (repeat 200 <1 MiB>))` plus actual
  `{:seon.test/overlay <same>}`, plus the identity digest, took 232 ms and allocated 67 MB
  in total. The texts were 25,626 and 14,297 chars (token budget 15000).
- Most of the remaining allocation is the renderer estimating tokens over each shown
  1 MiB string. It grows with the strings shown, not with the value.
- A 5000-char "é" string still renders whole (5002 chars), so the blob-tier test keeps its
  content. `{:seon.test/time-limit-ms 5000}` renders as `#:seon.test{:time-limit-ms 5000}`,
  which is still EDN-readable for duration_test.

## Regression (test/seon/test_runner_test.clj)
`a-failure-over-huge-values-reports-and-identifies-within-the-render-bound` drives
`capture-and-report-event!` with expected/actual that each print as more than 200 MB. It
asserts:
- the capture takes under 1000 ms (measured about 350 ms expected: three renders);
- the capture allocates less than 256 MB of thread memory (one whole print is over 400 MB);
- the log and each stored field are under 4 × token-budget chars, and each field names
  `:seon.print/omitted`;
- the identity is stable across renders and differs for a claim that renders differently.

## Root runs (serially, quiet window)
- `bin/test-check default --test seon.test-runner-test/a-failure-over-huge-values-reports-and-identifies-within-the-render-bound`
- `bin/test-check default --ns seon.test.runner-test` (bounded report, huge message, identity equality)
- `bin/test-check default --ns seon.test-failure-facts-test` (blob tier, site id over the stored texts)
- `bin/test-check default --ns seon.test.duration-test` (stored expected/actual still EDN-readable)
- `bin/test-check default --changed seon.test.runner/failure-report` and
  `--changed seon.test.runner/report-identity`

## Also seen
- `bin/seon status` printed 1.5 MB to the terminal. This is the same class of ugly
  output; it is not filed here.
