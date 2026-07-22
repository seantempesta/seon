---
type: research
status: active
tags: [research, architecture]
---

# WP-S1a live supervision transcript — 2026-07-22

## Scope

Default-cluster falsifier for source-checkout ownership of the JVM sci
execution host. The checked-in manifest begins and ends with
`:seon.config.execution/host-tier? false`.

## Cold boot with publication off

```text
$ bin/seon status
○ Seon down
  watcher  absent  not-ready
  writer  absent  not-ready
  host  absent  not-ready
  pod  absent  not-ready

$ bin/seon up
▶ reconcile watcher
  ● watcher ready
▶ reconcile writer
  ● writer ready
▶ reconcile host
  ● host ready
▶ reconcile pod
  ● pod ready
◆ Seon is ready

$ bin/seon status
● Seon ready
  watcher  alive  pid=53794
  writer  alive  pid=54928
  host  alive  pid=55078
  pod  alive  pid=55321

```

The host remained alive after the readiness probe. Its command carried the
descriptor-derived socket and writer selection:

```text
clojure -M:writer:host -m seon.host
{:seon.host/socket-path
 "/Users/sean/src/seon/tmp/seon-host-eval-default.sock",
 :seon.host.context/writer-socket-path
 "/Users/sean/src/seon/tmp/seon-cluster-default-req.sock",
 :seon.host.context/database-name "default"}

```

## Publication on and supervised-host invocation

The manifest was temporarily changed to declare the one execution-family
fact true, then explicitly applied:

```text
$ bin/seon config apply config/system.edn
◆ Config applied
  changed: true
  operations: 7

```

Before invocation the root agent had one existing child-lane execution
process, PID `55415`. A production `seon.execution.host/invoke-compiled!`
call issued an empty `seon.execution.runtime/eval-batch!` for root. The empty
batch still exercises the database tier lookup, digest-verified session
startup, UDS invocation frame, and result frame without involving a provider.

```clojure
(let [database (await ((deref (resolve 'seon.db/db))))
      invoke-fn (deref (resolve 'seon.execution.host/invoke-compiled!))]
  (await
   (invoke-fn database "root"
              'seon.execution.runtime/eval-batch!
              [{:seon.eval/parsed []
                :seon.eval/starting-ns 'my.root}])))

```

The result was successful:

```clojure
{:seon.execution/message :seon.execution.message/result
 :seon.execution/protocol-version 3
 :seon.execution/result
 {:seon.eval/ids []
  :seon.eval/n-ok 0
  :seon.eval/n-fail 0
  :seon.host/results []}}

```

After invocation, runtime process evidence contained both the unchanged
child PID `55415` and a ready host session with no PID and this coordinate:

```clojure
{:seon.execution/agent-id "root"
 :seon.execution.host/ready? true
 :seon.execution.host/eval-socket-path
 "/Users/sean/src/seon/tmp/seon-host-eval-default.sock"}

```

The OS Bun process list was identical before and after the invocation; no Bun
child was allocated. `bin/seon status` continued to report host PID `55078`
alive and Seon ready.

## Restore publication off and prove convergence

The manifest was restored to false before both applies:

```text
$ bin/seon config apply config/system.edn
◆ Config applied
  changed: true
  operations: 8

$ bin/seon config apply config/system.edn
◆ Config applied
  changed: false
  operations: 0

$ bin/seon status
● Seon ready
  watcher  alive  pid=53794
  writer  alive  pid=54928
  host  alive  pid=55078
  pod  alive  pid=55321

```

## Reverse stop and absence proof

```text
$ bin/seon down
○ Seon is down
  pod: clean
  host: forced reason=incomplete-application
  writer: clean

$ bin/seon status
○ Seon down
  watcher  absent  not-ready
  writer  absent  not-ready
  host  absent  not-ready
  pod  absent  not-ready

PASS: host eval socket absent
PASS: pid 53794 absent
PASS: pid 54928 absent
PASS: pid 55078 absent
PASS: pid 55321 absent

```

The operator attempted stop in pod → host → writer → watcher order. The host
containment result classified shutdown as forced/incomplete, but the host was
gone before writer shutdown completed, and no managed process or eval socket
remained.

## Final boot left ready

```text
$ bin/seon up
▶ reconcile watcher
  ● watcher ready
▶ reconcile writer
  ● writer ready
▶ reconcile host
  ● host ready
▶ reconcile pod
  ● pod ready
◆ Seon is ready

$ bin/seon status
● Seon ready
  watcher  alive  pid=57015
  writer  alive  pid=57093
  host  alive  pid=57127
  pod  alive  pid=57182

$ sleep 10; bin/seon status
● Seon ready
  watcher  alive  pid=57015
  writer  alive  pid=57093
  host  alive  pid=57127
  pod  alive  pid=57182

PASS: host eval socket is present and is a socket

```

Final state: default cluster ready, publication gate OFF, supervised host
ready at `tmp/seon-host-eval-default.sock`. No commit was made.
