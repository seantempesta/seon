(ns seon.ctx.inventory
  "The `<data-inventory>` context section — a cheap, reactive map of what
   the shared store holds RIGHT NOW (one line per stored KIND with each
   attr's live row count). Symbol-wired into the composer layout
   (`seon.ctx/core-default-ctx`) as `'seon.ctx.inventory/inventory-section`;
   loaded at boot so the symbol resolves for `seon.eval/lookup-value`."
  (:require
    [clojure.string :as str]
    [seon.db :as db]))

(def ^:private inventory-header
  (str ";; stored data — what this cluster holds RIGHT NOW, one line per\n"
       ";; KIND (attr namespace), then each attr NAME with its live row\n"
       ";; count. Consult this BEFORE researching or registering: a kind\n"
       ";; that already exists means prior agents stored rows you can\n"
       ";; query. Read any kind's rows with the LISTED attrs, e.g.:\n"
       ";;   (seon.db/query {:seon.db/query\n"
       ";;     '[:find ?v :where [?e :my.kb.codebase/answer ?v]]})\n"
       ";; (post-bootstrap data only; the full system index is one call\n"
       ";;  away — (seon.db/store-inventory {:seon.db/system? true}))"))

(defn inventory-section
  "The `<data-inventory>` discovery surface (always-changing volatile
   tail): a CHEAP map of what the shared store holds RIGHT NOW, derived
   from [[seon.db/store-inventory]] (user-domain kinds first). ONE line
   per kind — the kind (attr namespace) is the line label, then
   space-separated `attr-name count` pairs with the namespace stripped
   off each attr name (the line label already carries it). Pure fn of
   the db; stores nothing; recomputed each render so a newly-stored
   kind appears next turn and a fully-retracted one vanishes (see
   docs/seon/concepts/reactive-context).

   REACTIVE: returns \"\" (composer drops the section) when the store
   holds no post-bootstrap data — no empty shell. The whole section for
   a typical store is only a few hundred chars (~300 tokens), so it
   stays out of the cacheable prefix and rides near the prompt tail."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [rows (db/store-inventory {:seon.db/db db})]
    (if (seq rows)
      (let [lines (map (fn [{kind :seon.db/kind attrs :seon.db/attrs}]
                         (str (name kind) ": "
                              (str/join " "
                                (map (fn [[a c]] (str (name a) " " c))
                                     attrs))))
                       rows)]
        (str "<data-inventory>\n"
             inventory-header "\n\n"
             (str/join "\n" lines)
             "\n</data-inventory>"))
      "")))
