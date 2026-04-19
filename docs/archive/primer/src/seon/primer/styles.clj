(ns seon.primer.styles
  "CSS styles for Primer - inline for simplicity.")

(def base-css "
/* === Layout === */
.primer-view {
  font-family: Georgia, 'Times New Roman', serif;
  max-width: 100vw;
  min-height: 100vh;
  background: #1a1a2e;
  color: #eee;
}

.scene {
  position: relative;
  width: 100%;
  min-height: 100vh;
}

/* === Layers (stacked via z-index) === */
.layer {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  pointer-events: none;
}

.layer-bg { z-index: 0; }
.layer-content { z-index: 10; display: flex; align-items: center; justify-content: center; }
.layer-content * { pointer-events: auto; user-select: text; }
.layer-actions { z-index: 20; display: flex; align-items: flex-end; justify-content: center; padding-bottom: 3rem; }
.layer-actions * { pointer-events: auto; }

/* === Content === */
.narrative {
  max-width: 600px;
  padding: 2rem;
  text-align: center;
}

.story-text {
  font-size: 1.8rem;
  line-height: 1.8;
  text-shadow: 2px 2px 4px rgba(0,0,0,0.5);
}

/* === Actions === */
.action-bar {
  display: flex;
  gap: 1rem;
}

.action-btn {
  font-family: inherit;
  font-size: 1.2rem;
  padding: 0.75rem 1.5rem;
  background: rgba(255,255,255,0.1);
  border: 1px solid rgba(255,255,255,0.3);
  color: #fff;
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn:hover {
  background: rgba(255,255,255,0.2);
  transform: translateY(-2px);
}

/* === Background === */
.background {
  width: 100%;
  height: 100%;
  background-size: cover;
  background-position: center;
  opacity: 0.6;
}
")
