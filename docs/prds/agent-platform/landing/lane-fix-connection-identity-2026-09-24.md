# Lane fix-connection-identity — landing (2026-09-24)

## Broken or unproven

- **The change is not installed on default.** Root moved default to HEAD and reset it
  while this lane was running. Proof comes from a `git archive` snapshot of HEAD
  `e8ce974b8` plus this diff, with `reference-code` and `.cpcache` linked, run under
  `clojure -M:dev:test`. **RESET NEEDED** (or `start --head` after this commit) before
  default runs the fix.
- **The installed request was unavailable.** `bin/test-check default --test …` answered
  "not eligible", because the new test had no program rows. `--ns seon.db-test` then hit
  "PREPL closed without a terminal result" while default was restarting. So the
  regression has not run through `seon.test/run`.
- **Contracts may not have been armed in the snapshot.** The regression ran through
  `clojure.test/test-vars` with the namespace's own fixtures, which install the fixture
  projection. It did not run through the Seon test runner.

## Defect and cause

`seon.db/connection-identity` tested released state with
`(map? (:config @connection))`. Dereferencing a released connection throws
`ExceptionInfo "Connection has been released." {:type :connection-has-been-released}`
in Datahike's `deref-conn` (`reference-code/datahike/src/datahike/connector.cljc:82-86`,
revision `620721d67`). The throw happens before the test, so the declared typed branch
could never run. `datahike.store/connection-id` (`store.cljc:44-55`) is a pure function
of the config and does not throw. The throw comes from the deref that produces that config.

The same REPL form on default (pid 90471) returned the live identity and then
`{:type :connection-has-been-released}` for the released connection, as the issue reports.

## Change

`src/seon/db.clj`: the released test is now `(not (connection? connection))`. The
existing liveness predicate reads the wrapped atom's `:released` state without
dereferencing the connection. No catch was added. The docstring now names the seam.

`test/seon/db_test.clj`: a new test,
`connection-identity-answers-a-released-connection-with-its-typed-value`, releases a
real in-memory connection. It asserts three things:

- The live connection's identity equals `datahike.store/connection-id` of its config.
- The released connection gives a valid `:seon.error/value` with
  `:seon.db/released-connection true`.
- The `:seon.error/operation` is `seon.db/connection-identity`.

It also requires `datahike.store`.

The issue `docs/seon/issues/connection-identity-throws-on-a-released-connection.md` is
now marked `fixed`.

## Evidence (snapshot JVM, one run)

| Probe | Result |
|---|---|
| Regression with the fix | `{:test 1, :pass 4, :fail 0, :error 0}` |
| Regression with HEAD's body restored in the disposable JVM | `{:pass 1, :error 1}`, the error being `Connection has been released.` (connector.cljc:85) |
| `connection-identity`, live memory connection, warm | 8.3 µs per call (1000 calls) |
| `connection-identity`, released connection | 2.9 µs per call (1000 calls) |
| Before (default, one call, live) | 0.014 ms |
| Snapshot load of `seon.db-test` (cold compile) | 65–106 s wall, peak RSS 3.69 GB |

The live path is unchanged: it still dereferences and calls `connection-id`. The released
path no longer dereferences.

## Size

- Source: +3 / −1 (a one-line change plus two docstring lines).
- Tests: +21 (the new test and one require).
- Net: +23.

## Out of scope

Private `connection-id` (db.clj:342) dereferences the whole connection, which reads the
stored branch head from konserve, only to take `:config`. Its other caller, db.clj:478,
has the same cost. Reading `(:config @(:wrapped-atom c))`, as Datahike's own
`print-method` does, would skip the store read. That helper is outside this lane's
ownership, and no issue has been filed.
