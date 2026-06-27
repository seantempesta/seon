// packetstar.js — the whole browser client for Seon tiles.
//
// Two jobs, no framework:
//   1. Every element with [data-tile] opens its OWN EventSource to the URL in
//      that attribute and replaces its innerHTML with each streamed message.
//      One stream per tile → tiles are independent: one dead stream is one dead
//      tile (auto-retried), never a black screen, and a tile updating never
//      disturbs a sibling.
//   2. Any element with [data-action] POSTs that URL on click. The re-render
//      arrives over the tile's SSE stream, so the response body is ignored.
//
// This replaces datastar for the tile surface. Views are plain hiccup→HTML;
// interactivity is a bare URL. The server holds all the logic.
(function () {
  "use strict";

  var RETRY_MIN = 500;
  var RETRY_MAX = 8000;

  function openTile(el) {
    var url = el.getAttribute("data-tile");
    if (!url) return;
    var backoff = RETRY_MIN;
    // Last html applied to this tile. Skip the innerHTML replace when a new
    // payload is byte-identical — an unconditional replace would WIPE in-tile
    // interactive state the server can't know about (a <details> the user
    // expanded, scroll position, text selection, focus). Scoped here (not in
    // `connect`) so it persists across reconnects — the DOM still holds it.
    var lastHtml = null;

    function connect() {
      var es = new EventSource(url);

      es.onopen = function () { backoff = RETRY_MIN; };

      es.onmessage = function (e) {
        // One stream per tile, so the target is implicit — the payload is just
        // this tile's inner HTML.
        if (e.data === lastHtml) return;
        lastHtml = e.data;
        el.innerHTML = e.data;
      };

      es.onerror = function () {
        es.close();
        // Independent retry with capped backoff — the rest of the wall keeps
        // streaming while this one reconnects.
        setTimeout(connect, backoff);
        backoff = Math.min(backoff * 2, RETRY_MAX);
      };
    }

    connect();
  }

  // A [data-console] element opens ONE EventSource for a whole console; each
  // "patch" event carries {id, html} and updates #tile-<id>. One connection for
  // N tiles → the HTTP/1.1 pool stays free for POSTs (no starvation).
  function openConsole(el) {
    var url = el.getAttribute("data-console");
    if (!url) return;
    var backoff = RETRY_MIN;
    // Last html applied per tile-id. `console-payload` re-renders and sends
    // EVERY tile on EVERY tx, so without this an unconditional replace would
    // wipe in-tile interactive state (an expanded <details>, scroll, focus) on
    // every tx even for tiles whose content didn't change — and most txs change
    // only one or two tiles. Re-render a tile only when its html actually
    // changed. Scoped outside `connect` so it survives reconnects.
    var lastPatch = Object.create(null);

    function connect() {
      var es = new EventSource(url);
      es.onopen = function () { backoff = RETRY_MIN; };
      es.addEventListener("patch", function (e) {
        try {
          var p = JSON.parse(e.data);
          if (lastPatch[p.id] === p.html) return;
          lastPatch[p.id] = p.html;
          var t = document.getElementById("tile-" + p.id);
          if (t) t.innerHTML = p.html;
        } catch (err) { /* ignore a malformed patch */ }
      });
      es.onerror = function () {
        es.close();
        setTimeout(connect, backoff);
        backoff = Math.min(backoff * 2, RETRY_MAX);
      };
    }

    connect();
  }

  function openAll(root) {
    var r = root || document;
    r.querySelectorAll("[data-tile]").forEach(openTile);
    r.querySelectorAll("[data-console]").forEach(openConsole);
  }

  // Interactions: a bare data-action URL is POSTed; the tile re-renders over SSE.
  document.addEventListener("click", function (ev) {
    var t = ev.target.closest("[data-action]");
    if (!t) return;
    ev.preventDefault();
    fetch(t.getAttribute("data-action"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}"
    }).catch(function () { /* the SSE stream is the source of truth */ });
  });

  // Input tile (the REPL prompt): a [data-send] form POSTs its text on submit.
  // A value starting with "(" is a Clojure FORM → /eval (introspective, quiet);
  // anything else is prose → /chat (a message that wakes the agent). The input is
  // never server-overwritten, so focus/typing survive live updates around it.
  document.addEventListener("submit", function (ev) {
    var form = ev.target.closest("[data-send]");
    if (!form) return;
    ev.preventDefault();
    var input = form.querySelector("[name=text]");
    if (!input) return;
    var text = input.value;
    if (!text.trim()) return;
    var agent = encodeURIComponent(form.getAttribute("data-agent") || "");
    var isForm = text.trim().charAt(0) === "(";
    var url = (isForm ? "/eval?agent=" : "/chat?agent=") + agent;
    var field = isForm ? "form" : "text";
    fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: field + "=" + encodeURIComponent(text)
    }).catch(function () { /* the tiles reflect the result over SSE */ });
    input.value = "";
  });

  // Debug overlay: a full-viewport layer over the console (an iframe onto
  // /tile/debug/<id>). Toggled by the header "⚙ debug" button or backtick
  // (focus-guarded — never while typing in the input); Esc, the ✕ button, or
  // a click on the dim backdrop (outside the iframe panel) closes it. Opening
  // points the iframe at its data-src (its own live SSE inside); closing
  // resets it to about:blank so that stream tears down.
  function debugOverlay(open) {
    var o = document.getElementById("seon-debug-overlay");
    if (!o) return;
    var f = document.getElementById("seon-debug-frame");
    var isOpen = o.classList.contains("open");
    if (open === undefined) open = !isOpen;
    if (open === isOpen) return;
    if (open) {
      if (f) f.src = f.getAttribute("data-src");
      o.classList.add("open");
    } else {
      o.classList.remove("open");
      if (f) f.src = "about:blank";
    }
  }

  document.addEventListener("click", function (e) {
    var t = e.target.closest ? e.target.closest("#seon-debug-toggle") : null;
    if (t) { e.preventDefault(); debugOverlay(); return; }
    if (e.target.closest && e.target.closest("#seon-debug-close")) {
      e.preventDefault(); debugOverlay(false); return;
    }
    // A click on the overlay element ITSELF (the dim backdrop, not the iframe
    // panel inside its padding) closes it.
    if (e.target && e.target.id === "seon-debug-overlay") debugOverlay(false);
  });

  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") { debugOverlay(false); return; }
    if (e.key === "`") {
      var a = document.activeElement, tag = a && a.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" ||
          (a && a.isContentEditable)) return;
      e.preventDefault();
      debugOverlay();
    }
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () {
      openAll();
      // Deep-link / screenshot: #debug auto-opens the overlay on load.
      if (location.hash === "#debug") debugOverlay(true);
    });
  } else {
    openAll();
    if (location.hash === "#debug") debugOverlay(true);
  }

  // Expose for tiles added after first paint (a future composer/app-loader).
  window.packetstar = { openAll: openAll, openTile: openTile };
})();
