---
type: issue
status: resolved
severity: blocker
tags: [issue, render, test, wave/render-producers]
---

# Rank only schemas that declare the requested render output

## Problem

`seon.render/schema-producers` ranked every schema matching a pulled entity by
required-attribute count before asking whether the schema declared the
requested render output. A more-specific schema with no producer could
therefore suppress a less-specific schema with the valid producer and send the
entity to the generic floor.

## Evidence

On 2026-09-06, the canonical schema population matched a fully pulled agent
against `:seon.cluster.agent/context-links`, whose required attributes are the
agent id and cluster ref but which declares no render properties. It also
matched `:seon.cluster.agent/agent`, whose required agent id declares the
identity AI, HTML, and form producers. The old specificity pass retained only
the non-rendering `context-links` schema.

The same path initially still failed outside an ambient fixture binding:
`storable-attribute-in?` resolves Malli forms through the handed projection,
so its caught missing-projection exception classified every required attribute
as non-storable. Public selection already had the explicit SCI-context
projection but had not handed it across this helper boundary.

This explains the confirmed full-agent identity floor. It may also explain an
empty generated opening entry source, but that downstream behavior still needs
a live proof after publication.

## Resolution

Candidate schemas are now filtered to schemas declaring the requested output
before pulled-entity specificity is computed. Specificity, deterministic
producer ordering, and ambiguity remain unchanged among schemas that can
actually answer that output. The helper hands its explicit projection across
the complete match, storable-attribute, and specificity operation.

`non-rendering-more-specific-schema-does-not-shadow-agent-identity` transacts a
real agent with its cluster ref through the canonical database fixture, pulls
the full entity, clears the fixture's ambient projection binding, and proves
the public selection boundary reaches
`seon.cluster.agent/render-identity-ai` rather than the generic floor.
