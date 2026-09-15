---
type: issue
status: open
severity: friction
tags: [issue, adoption, schema, render]
---

# The long-lived cluster handle supplies an old environment projection

On default after adoption `6aa8b5af-69a7-5a00-92a1-04661375d322`, the database
declared `:seon.cluster.prompt/request` with optional `:seon.turn/id`, but
`render/acquire-context!` called with the cluster handle still refused a
missing turn id. `seon.instrument/supplied-projection` prefers the request's
environment projection over the current handed projection. The handle's
environment therefore selected the old contract even inside
`schema/call-with-projection` using the current SCI projection.

The same call with an explicit current `:seon.schema/projection` succeeded
and returned all 178,089 bytes, including Juniper's final result. The two
research helpers now carry that projection explicitly. No default restart,
refork, reseed, or agent message was used.

The remaining owner is development adoption's cluster environment custody:
advance the live handle's environment with its projection state, then prove
an accreted optional input works through that handle after adoption without
the caller supplying a replacement projection. This lane did not edit the
concurrently changing runtime/adoption owners.
