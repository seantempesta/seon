(ns seon.schema.edn
  "Schema definitions as EDN data: the classpath loader and the ONE
  admission gate.

  CONTRACT LAYER (orchestrator-authored, 2026-07-27 — B2 wave, from the
  sealed schema-EDN ruling and b2-plan §6). The schemas and function
  contracts are SEALED: the implementation lane fills the stub bodies
  until test/seon/schema/edn_test.clj is green and may not loosen a
  schema or a test.

  The model (the sealed ruling, verbatim where it matters):

  - Attribute/entity schemas live as EDN maps (registry key → schema
    form) in `seon/schema/*.edn` resources on the classpath. The
    population is GLOBAL: file boundaries are editorial convenience
    with zero semantic meaning; the loader merges every file and
    REFUSES a duplicate attribute across files, naming the key and
    both files.
  - `register!` already guarantees forms are readable, round-tripping
    EDN — moving them to `.edn` files is a relocation of the same
    values, not a new format. The loader contributes the merged
    population through the same candidate route `register!` uses; it
    never activates.
  - ONE admission gate, `admit`, shared by both producers: the loader's
    merged files at boot/build, and agents' `register!` at runtime.
    It validates a COMPLETE candidate population: every reference
    resolves; every `[:fn]` names a registered core predicate; every
    `[:fn]` carries an honest generator (`:gen/schema` or `:gen/gen` —
    an opaque platform predicate is honest by constructing a real
    instance). Refusals name the offending key and, for loaded forms,
    the contributing file.
  - LOAD ORDER IS NOT A HAZARD BY CONSTRUCTION: the `[:fn]` predicate
    and honesty checks run at ACTIVATION over the whole population,
    never per-file at load — namespaces register their core predicates
    first, then `load!` merges, then activation admits everything at
    once. Sixteen registrations stay in code by a COMPUTED rule, never
    a list: exactly the schemas the `seon.schema.*` namespaces
    themselves need before any EDN file can be validated.
  - Classpath enumeration handles both `file:` (dev source classpath)
    and `jar:` (publish) resource URLs.

  Crash walk: `load!` and `admit` are pure reads over classpath
  resources and in-memory populations — no durable state, nothing to
  recover. Committing admitted schema FACTS to a store is the
  ancestor build's job, not this namespace's."
  (:require [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

; where the loader looks; overridable so suites use fixture directories
(schema/register! ::resource-dir [:string {:min 1}])
(schema/register! ::files [:vector [:string {:min 1}]])
(schema/register! ::keys [:int {:min 0}])

(schema/register!
 ::load-request
 [:map {:closed true}
  [::resource-dir {:optional true} ::resource-dir]])

(schema/register!
 ::loaded
 [:map {:closed true}
  [::files ::files]
  [::keys ::keys]])

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defn load!
  "Merge every `<resource-dir>/*.edn` on the classpath into candidates.
  Default resource-dir is \"seon/schema\". Each
  file is one EDN map of registry key → schema form. Refuses
  `::duplicate-attribute` (one key contributed by two files, both
  named), `::unreadable-file` (not EDN, file named), and
  `::not-a-map` (a file whose top level is not a map). Contributes
  candidates exactly as `register!` does; never activates — activation
  admits the whole population through `admit`. Returns what was
  loaded."
  {:malli/schema [:=> [:cat ::load-request] ::loaded]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `load!})))

(defn admit
  "THE one admission gate over a complete candidate population.
  The population is one map of registry key → schema form. Called by activation over the loader's
  merged population, and by `register!` over the population plus its
  one new candidate — one gate, two producers. Returns the vector of
  admitted attribute declarations. Refuses, naming the key (and file
  when known): `::unresolved-reference` (a referenced registry key
  absent from the population), `::unregistered-predicate` (a `[:fn]`
  symbol with no `register-core-predicate!`), and
  `::dishonest-generator` (a `[:fn]` form carrying neither
  `:gen/schema` nor `:gen/gen`)."
  {:malli/schema [:=> [:cat [:map [:seon.schema/forms :map]]]
                  [:vector :map]]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `admit})))
