;; The memory measurements of notes/design-agent-loop-2026-10-25.md section 2, as evaluated
;; (2026-10-25, this worktree's root on 8140, each form one :eval request to a fresh agent via bin/ask).
;; Optional first, in root: (deref (seon.root/call! sys {:op :fs :req {:op :static}}))  ; the static fork server
(seon.root/spawn! sys {:branch :mem-lab5})                      ; in root
(def G '(vec (for [g (range 5)] (long (/ (jolt.scheme/eval-string (str "(bytes-allocated " g ")")) 1048576)))))
{:mb (seon.lib/real-mb)}                                         ; fresh
(do (def keep-live (atom []))
    (dotimes [i 400]
      (let [xs (vec (for [j (range 1000)] {:i i :j j :s (str "row " i " " j " " (apply str (repeat 20 "x")))}))]
        (when (zero? (mod i 10)) (swap! keep-live conj xs))))
    :ok)                                                          ; the work
{:mb (seon.lib/real-mb)}                                         ; after the work
(let [t0 (System/nanoTime)]
  (jolt.scheme/eval-string "(begin (heap-reserve-ratio 0.0) (collect 4 4) (heap-reserve-ratio 1.0))")
  {:ms (/ (- (System/nanoTime) t0) 1e6) :mb (seon.lib/real-mb)}) ; one full collect
