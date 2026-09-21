---
type: issue
status: open
severity: blocker
tags: [issue, boot, classpath, testing]
---

# Scratch boot cannot load the cluster namespace

At `ae6a0cdd4`, the test-system lane created the empty directory
`tmp/test-system-root` and ran:

```sh
bin/seon --root tmp/test-system-root start test-system
```

The operator reported a current dependency cache (373 namespaces), launched
PID 22206, then failed in phase `namespaces`:

```text
Could not locate seon/cluster__init.class, seon/cluster.clj or seon/cluster.cljc on classpath.
```

The start phase took 2473 ms; config was never reached. This prevents the
requested before/after config boot measurement. No classpath cause has been
established. The launch seam is `script/seon/fresh_operator.clj:2130`; child
classpath assembly is `:495`, using `seon.test.cache/classpath` at
`src/seon/test/cache.clj:259` when a test classpath is supplied.

Raw evidence is `tmp/test-system-config-before.log`; the operator's failure
envelope named `:seon.fresh-operator/phase :start`, cluster `test-system`,
PID 22206, and no error kind. MCP runtime status for the explicit scratch
root returned empty clusters and sessions. Root-scoped `down` exited zero,
and no scratch JVM remained before cleanup. The private root was deleted;
the shared default was never operated.

Acceptance: the ordinary explicit-root start command loads the cluster
namespace and reaches config, with a regression over the launcher's actual
classpath construction. The test-system lane has not modified the operator.
