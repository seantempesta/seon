---
type: issue
status: open
severity: blocker
tags: [issue, operator, build]
---

# Page-plan config digest drift blocks fresh default apply

## Problem

After resetting the default database, `bin/seon cluster apply default` refuses
the current published page plan because the page-plan sidecar names a different
config-manifest digest than the operator-selected launch manifest.

This is downstream of watcher readiness: the watcher successfully builds only
`client` and `test`, publishes artifact manifest v14, and the writer becomes
ready before apply starts.

## Evidence

- Failed apply result:
  `tmp/seon-operator/cluster-apply/b908aec1-24f6-402f-a72f-9737d5cbabe3.edn`
- Selected config-manifest digest:
  `607f6793ef3e3123d626e7b69b21cc231a5a209571725b09d62f86b873e29ca0`
- Page-plan config-manifest digest:
  `adc1e407f04100f1412700f33c482d21a2f017eb340dcecfa37ec5bc64908630`
- The refusal reproduced after the old default database was unable to converge
  non-destructively and the pre-authorized default reset completed.

## Diagnosis

The page plan was current. Resolving `config/system.edn` under the selected
operator environment produced `adc1e407…`, exactly the digest published in
`out/client/page-plan.edn`. The `607f6793…` launch digest came from
`data/clusters/default/config/applied.edn`, retained across database reset from
an older successful application.

Reset deleted only the database directory. Once the writer recreated that
directory, manifest selection treated the cluster as born and selected the
retained applied manifest instead of `config/system.edn`. The publisher and
operator also carried duplicate SHA-256 functions even though both intended to
hash the same canonical `pr-str` bytes.

## Fix

Commit `07af12c73` makes reset delete the exact retained applied-manifest file
after confirmed process shutdown and before selecting the fresh configuration.
`seon.dev.config/config-manifest-digest` is now the one digest owner used by
operator selection and page-plan publication. Artifact reuse and publication
also compare the selected launch digest with the digest embedded in the page
plan, so a manifest-only input change cannot silently reuse the wrong sidecar.

The strict apply verification remains unchanged.

## Partial live proof

- Focused operator proof: 93 tests, 394 assertions, zero failures or errors.
- Fresh reset preparation: 76.29 seconds.
- After reset, the selected resolved manifest and published page plan both
  named `adc1e407f04100f1412700f33c482d21a2f017eb340dcecfa37ec5bc64908630`.
- Fresh apply passed page-plan admission and began applying its 95
  initialization pages. It no longer produced the digest refusal.
- After the transaction-boundary fix, fresh apply also passed the former
  `:seon.eval/home-requires` storage-schema failure. Its next refusal is the
  independently invalid root-context component recorded in
  [[transaction-validation-precedes-edn-slot-encoding-blocks-fresh-config-reconcile]].
  Full apply/boot acceptance remains pending that owner repair.

## Owner

The preprocessing/page-plan publication owner must make the flush hook consume
the exact operator-selected resolved manifest used by the launch descriptor.

## Acceptance

- A source-frozen default watcher flush publishes a page plan whose
  config-manifest digest equals the selected launch manifest digest.
- `bin/seon cluster apply default` succeeds against a fresh default database.
- A subsequent `bin/seon up` passes the applied-identity start gate and reaches
  pod and web-render readiness without regenerating the page plan.
