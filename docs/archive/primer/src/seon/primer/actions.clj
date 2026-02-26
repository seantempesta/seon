(ns seon.primer.actions
  "Action handlers that mutate ctx."
  (:require [seon.primer.ctx :as ctx]))

;;; Demo Scenes - A short interactive story

(def demo-scenes
  [{:scene/id "welcome"
    :scene/template :narrative/page
    :scene/params {:text "Welcome, young explorer. The ancient library awaits, its corridors filled with whispered secrets and forgotten knowledge..."}
    :scene/actions [{:action/id :enter
                     :action/label "Enter the Library"}]}

   {:scene/id "library-entrance"
    :scene/template :narrative/page
    :scene/params {:text "Dust motes dance in shafts of golden light. Before you stand three towering bookcases, each radiating a different aura: one glows faintly blue, another pulses with amber warmth, and the third shimmers with silver starlight."}
    :scene/actions [{:action/id :blue
                     :action/label "Approach the Blue Shelf"}
                    {:action/id :amber
                     :action/label "Approach the Amber Shelf"}
                    {:action/id :silver
                     :action/label "Approach the Silver Shelf"}]}

   {:scene/id "blue-knowledge"
    :scene/template :narrative/page
    :scene/params {:text "The blue shelf holds books of logic and mathematics. As your fingers brush their spines, equations float before your eyes like fireflies. You feel your mind expand, seeing patterns in everything."}
    :scene/actions [{:action/id :continue
                     :action/label "Continue Exploring"}]}

   {:scene/id "amber-wisdom"
    :scene/template :narrative/page
    :scene/params {:text "The amber shelf radiates warmth and memory. These books contain stories from every age - tales of heroes and wanderers, of love and loss. You feel the weight of generations of human experience."}
    :scene/actions [{:action/id :continue
                     :action/label "Continue Exploring"}]}

   {:scene/id "silver-mystery"
    :scene/template :narrative/page
    :scene/params {:text "The silver shelf hums with potential. These books are not yet written - they contain the stories that could be, the knowledge waiting to be discovered. You sense infinite possibilities."}
    :scene/actions [{:action/id :continue
                     :action/label "Continue Exploring"}]}

   {:scene/id "deeper"
    :scene/template :narrative/page
    :scene/params {:text "Having touched the wisdom of the library, you notice a narrow passage between the shelves. Ancient runes glow faintly on its threshold, promising deeper mysteries within..."}
    :scene/actions [{:action/id :enter-passage
                     :action/label "Enter the Passage"}
                    {:action/id :return
                     :action/label "Return to the Entrance"}]}

   {:scene/id "heart"
    :scene/template :narrative/page
    :scene/params {:text "At the heart of the library, you find a single pedestal. Upon it rests an open book, its pages blank yet somehow filled with meaning. You realize: the greatest stories are those we have yet to tell."}
    :scene/actions [{:action/id :restart
                     :action/label "Begin Again"}]}])

(def scene-index
  "Map from scene-id to scene."
  (into {} (map (juxt :scene/id identity) demo-scenes)))

(def action-transitions
  "Map from [scene-id action-id] to next-scene-id."
  {["welcome" :enter] "library-entrance"
   ["library-entrance" :blue] "blue-knowledge"
   ["library-entrance" :amber] "amber-wisdom"
   ["library-entrance" :silver] "silver-mystery"
   ["blue-knowledge" :continue] "deeper"
   ["amber-wisdom" :continue] "deeper"
   ["silver-mystery" :continue] "deeper"
   ["deeper" :enter-passage] "heart"
   ["deeper" :return] "library-entrance"
   ["heart" :restart] "welcome"})

(defn initial-scene
  "Returns the first scene for new sessions."
  []
  (first demo-scenes))

(defn ensure-session!
  "Ensure session exists with initial scene. Returns session ctx."
  [session-id]
  (if-let [existing (ctx/get session-id)]
    existing
    (ctx/create! session-id {:primer/current-scene (initial-scene)})))

(defn handle-action
  "Handle an action for a session. SSE refresh happens automatically via ctx watch."
  [session-id action-id]
  ;; Ensure session exists
  (ensure-session! session-id)

  ;; Get current scene
  (let [session-ctx (ctx/get session-id)
        scene (:primer/current-scene session-ctx)
        current-id (:scene/id scene)
        ;; Look up the next scene based on transition map
        next-scene-id (action-transitions [current-id action-id])]
    (when-let [next-scene (scene-index next-scene-id)]
      ;; Update to new scene - SSE refresh happens automatically via sessions watch
      (ctx/assoc! session-id :primer/current-scene next-scene))))
