(ns tmp.floor-printer.stub
  "A render selection stand-in for the proof: the root's render answers the
  Var render's real data face; every call is counted, so a re-dispatch shows.")

(def calls (atom 0))
(def face (atom nil))

(defn declared-projection
  "The Var render's data face as the producer's projected AI node."
  [_request _value output]
  (swap! calls inc)
  {:seon.print/node {:seon.print/face :seon.print/projected :seon.render/output output
                     :seon.print/value @face
                     :seon.render.call/selected-producer 'seon.render.var/render-ai}})
