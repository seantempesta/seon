(ns seon.config
  "Every dial of the platform, read once from the environment when this namespace loads
  (SEON_<NAME>; proto/flow f2-final's PROTO_<NAME> dials, collected in one place). A
  shipped default fits a 16 GB machine running many agents beside the user's work.")

(defn- dial [k default] (Long/parseLong (or (System/getenv k) (str default))))

(def MAX-AGENTS
  "Live agents at most (root holds a socket per agent)."
  (dial "SEON_MAX_AGENTS" 1024))
(def WRITER-BATCH
  "Most requests one writer step drains and commits together (group commit)."
  (dial "SEON_WRITER_BATCH" 256))
(def WRITER-BUF
  "The writer's in-port. Each agent has at most one write in flight, so a buffer above the
  live agents never fills from agents; beyond it senders park (back-pressure)."
  (dial "SEON_WRITER_BUF" MAX-AGENTS))
(def AGENT-BUF
  "Each agent channel's buffer. Small on purpose: a full channel pushes back on its sender
  (the reader leaves frames in the socket)."
  (dial "SEON_AGENT_BUF" 64))
(def STOP-BOUND-MS
  "How long an agent may take to exit after :stop before it is SIGKILLed."
  (dial "SEON_STOP_BOUND_MS" 2000))
(def OPEN-BRANCHES
  "Branch connections the writer keeps open (least recently used released past it)."
  (dial "SEON_OPEN_BRANCHES" 32))
(def PAR-COMMIT
  "Experiment (writer design B, notes/option3-F1-flow.md section 4): commit the branches of
  a batch in parallel. Off by default."
  (= "1" (System/getenv "SEON_PAR_COMMIT")))
(def STANDBY-EVERY
  "Main commits after which the fork server forks a fresh standby (bounds the log of
  commits a promoted standby is sent)."
  (dial "SEON_STANDBY_EVERY" 256))
(def API-WRITES
  "Writer requests from the api in flight at most. At the bound the api stops reading its
  entrance (Flow's input filter) and reads only answers, so callers are pushed back and
  the writer's answers always have room: the api <-> writer cycle cannot wedge."
  (dial "SEON_API_WRITES" 512))
(def API-BATCH
  "Most messages one api step takes before it applies its graph change (spawns and ends
  become one update!)."
  (dial "SEON_API_BATCH" 256))
