(ns my.kb
  "Your knowledge base — SCHEMA'D DATA, never a pile of text.

   Knowledge lives in `my.kb.<domain>` sub-namespaces, each with a REAL
   schema for that kind of knowledge (`my.kb.codebase.fn/*`,
   `my.kb.paper/*`, …) — designing one is the same skill as modeling
   your human's data. Do NOT build a general memory-markdown structure;
   storing large text is allowed when your human wants it, but it is
   never the default. `my.kb.instruction` (sibling file) is the worked
   example of a domain.

   Reference the shared provenance attrs below from your domain schemas
   instead of re-inventing source-path/line/confidence per domain:

     (schema/register! :my.kb.codebase.fn/name  [:string {:seon.db/identity true}])
     (schema/register! :my.kb.codebase.fn/claim :string)
     ;; provenance: REFERENCE the shared shapes, don't redefine them
     ;; — :my.kb/source-path, :my.kb/source-line, :my.kb/verified-at,
     ;; :my.kb/confidence are already registered; just transact them
     ;; on your rows.

   Consulting = the schema-catalog + datalog, FIRST, before research:
   the catalog lists every `my.kb.*` attr that exists; query those
   exact keywords. There is no store!/consult API — `seon.db/transact!`
   and `seon.db/query` over your domain schemas ARE the knowledge base."
  (:require
    [seon.schema :as schema]))

;; --- The shared provenance shapes, registered ONCE (the
;; --- register-once rule: every my.kb.<domain> schema references these
;; --- instead of inlining its own copy).

(schema/register! ::source-path :string)  ; repo-relative or absolute file path
(schema/register! ::source-line :int)     ; 1-based line the fact was read from
(schema/register! ::verified-at :inst)    ; when the fact was last verified
(schema/register! ::confidence  [:enum :verified :inferred])
