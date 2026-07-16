---
type: issue
status: closed
severity: high
tags: [issue, flow, database, web]
---

# Remove the replica topology from the Bun cutover roadmap

## Resolution

The Bun roadmap now matches the database-authority target: one cluster host
owns web and child supervision, each active agent child owns its loop and one
direct persistent authority session, and no Bun process owns a Datahike
replica, copied index, transaction feed, or database result cache. The native
socket package now replaces the publisher/replay path instead of preserving
it, and graduation proves selective interests and reconnect.

## Original problem

The roadmap had incorporated the newer shared-authority decision in one
section while an older topology still assigned a local replica to every Bun
cluster child and told the socket cut to preserve publish replay. That
contradiction could send the runtime cutover back toward the exact fixed memory,
broadcast, and compatibility costs the authority mesh removes.
