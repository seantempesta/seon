---
type: issue
status: open
severity: blocker
tags: [issue, schema, adoption, dev-cluster, fixture, class/p1]
---

# Re-declaring an agent's schema on an adopted JVM is refused: unresolved reference and a projection-advance contract violation

Observed 2026-09-09 21:16 on `default` (twelfth refork, then several
in-place adoptions of HEAD): reseeding the Juniper fixture through
`install!` failed at its schema declaration step with two errors at once:

```
Schema population refused :example/order-row (unresolved-reference).
seon.env/advance-projection! violated its contract (invalid-input):
must hold one immutable replacement environment
```

The same fixture installs cleanly on a fresh boot, and had reseeded on
this JVM earlier in the day. The difference is the sequence: in-place
adoptions after the seed. `:example/order-row` is the entity schema whose
attributes (`:example/order`, `/customer`, `/amount`) the fixture also
declares; on re-declaration the population sees the entity form before
its attributes resolve, or resolves them against a projection that the
adoptions replaced, and `advance-projection!` then receives something
other than the one immutable replacement environment it requires.

This is not a fixture defect: any agent that declares a schema in its
namespace after the cluster adopted new source hits the same seam. The
dev loop must let an agent (or a fixture) declare or re-declare a
schema family on an adopted JVM exactly as on a fresh boot.

Recovery used: thirteenth refork of `default`.
