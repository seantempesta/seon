; One disposable SCI evaluation on default, after development adoption.
(do
  (require '[my.program] '[seon.program] '[seon.db] '[seon.operator])
  (let [connection (seon.operator/connection "default")
        before (seon.db/basis-t (seon.db/db connection))
        refused (my.program/ns-unmap! 'seon.turn/open?)
        after-refusal (seon.db/basis-t (seon.db/db connection))]
    (if (not= :seon.program/declaration-refused (:seon.error/kind refused))
      {:probe/refusal refused :probe/scratch-skipped true}
      (let [row (seon.program/declaration-row
                 {:seon.fn/sym "my.program/disposable-retraction-proof"
                  :seon.fn/ns [:seon.ns/name 'my.program]
                  :seon.fn/source "(defn disposable-retraction-proof [] 42)"
                  :seon.fn/arglists "([])"
                  :seon.fn/private? false
                  :seon.fn/spec "[:=> [:cat] :int]"}
                 :contracted :agent)
            admitted (seon.db/transact! [row])]
        (if (:seon.error/kind admitted)
          {:probe/refusal refused :probe/admission admitted}
          (do
            (in-ns 'my.program)
            (defn disposable-retraction-proof
              {:malli/schema [:=> [:cat] :int]} [] 42)
            (let [removed (my.program/ns-unmap! 'my.program/disposable-retraction-proof)]
              {:probe/callers (get-in refused [:seon.error/data :seon.program/callers])
               :probe/plan (:seon.program/plan refused)
               :probe/affected (:seon.program/affected refused)
               :probe/refusal-kind (:seon.error/kind refused)
               :probe/refusal-basis [before after-refusal]
               :probe/original-resolves? (boolean (resolve 'seon.turn/open?))
               :probe/retraction removed
               :probe/scratch-resolves? (boolean (resolve 'my.program/disposable-retraction-proof))
               :probe/scratch-row (seon.db/pull (seon.db/db connection) [:db/id]
                                  [:seon.fn/sym "my.program/disposable-retraction-proof"])})))))))
