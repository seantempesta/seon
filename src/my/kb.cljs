(ns my.kb
  "Your knowledge base — SCHEMA'D DATA, never a pile of text.

   Knowledge lives in `my.kb.<domain>` sub-namespaces, each with a REAL
   schema for that kind of knowledge (`my.kb.codebase.fn/*`,
   `my.kb.paper/*`, …) — designing one is the same skill as modeling
   your human's data. Do NOT build a general memory-markdown structure;
   storing large text is allowed when your human wants it, but it is
   never the default. `my.kb.system` (sibling file) is the worked
   example of a domain — the system-wide instruction singleton.

   Reference the shared provenance attrs below from your domain schemas
   instead of re-inventing source-path/line/confidence per domain.
   Provenance ALWAYS carries :my.kb/source-line — the 1-based line you
   read the fact from; a fact spanning lines adds :my.kb/source-line-end
   (the inclusive last line — a range is TWO ints on the same shared
   attrs, never a \"460-470\" string and never a forked plural attr); a
   single-line fact just omits the end. The FULL move — domain attrs
   registered, then ONE row mixing the domain attrs with the shared
   :my.kb/* provenance attrs (never fork your own parallel
   source-path/confidence/source-lines):

     (seon.schema/register! :my.kb.codebase/question [:string {:seon.db/identity true}])
     (seon.schema/register! :my.kb.codebase/answer   :string)
     (seon.db/transact!
       {:seon.db/tx-data
        [{:my.kb.codebase/question \"what does seon.db/transact! return on failure?\"
          :my.kb.codebase/answer   \"an envelope value with :seon.db/ok? false — it never rejects\"
          :my.kb/source-path       \"src/seon/db.cljs\"
          :my.kb/source-line       287
          :my.kb/source-line-end   296
          :my.kb/verified-at       (js/Date.)
          :my.kb/confidence        :verified}]})

   Consulting = (seon.db/store-inventory) + datalog, FIRST, before
   research: the inventory lists every attr namespace with live rows;
   datalog those exact keywords. There is no store!/consult API —
   `seon.db/transact!` and `seon.db/query` over your domain schemas ARE
   the knowledge base."
  (:require
    [seon.schema :as schema]))

;; --- The shared provenance shapes, registered ONCE (the
;; --- register-once rule: every my.kb.<domain> schema references these
;; --- instead of inlining its own copy).

(schema/register! ::source-path :string)     ; repo-relative or absolute file path
(schema/register! ::source-line :int)        ; 1-based line the fact was read from
                                             ; (the FIRST line, when citing a range)
(schema/register! ::source-line-end :int)    ; inclusive last line of a multi-line
                                             ; fact; single-line facts omit it
(schema/register! ::verified-at :inst)       ; when the fact was last verified
(schema/register! ::confidence  [:enum :verified :inferred])
