// Seon Debug Panel - SSE and Datastar debugging
// Include via [:script {:src "/js/seon-debug.js"}]
//
// IMPORTANT: Datastar uses Fetch API for SSE, NOT native EventSource!
// This panel tracks the 'datastar-fetch' custom event that Datastar dispatches.
//
// Console debugging commands:
//   window.SEON_DEBUG.rawEvents.slice(-5)  // Last 5 raw SSE events
//   window.SEON_DEBUG.sse                  // Current SSE status
//   window.SEON_DEBUG.events.slice(-10)    // Last 10 logged events

(function() {
  'use strict';

  // Global debug state - accessible via window.SEON_DEBUG
  var DEBUG = {
    sse: {
      status: 'idle', // idle, connecting, connected, error
      url: null,
      lastEvent: null,
      eventCount: 0,
      patchCount: 0,
      errors: []
    },
    datastar: {
      loaded: false,
      version: 'unknown'
    },
    events: [],
    maxEvents: 100,
    rawEvents: [] // Store raw SSE event data for inspection
  };

  window.SEON_DEBUG = DEBUG;

  // Create debug panel
  function createPanel() {
    var panel = document.createElement('div');
    panel.id = 'seon-debug-panel';
    panel.style.cssText = [
      'position: fixed',
      'bottom: 10px',
      'right: 10px',
      'width: 360px',
      'max-height: 500px',
      'overflow-y: auto',
      'background: #1a1a1a',
      'color: #d4c5a9',
      'font-family: monospace',
      'font-size: 11px',
      'padding: 10px',
      'border: 1px solid #3d3d3d',
      'border-radius: 4px',
      'z-index: 99999',
      'box-shadow: 0 4px 12px rgba(0,0,0,0.5)'
    ].join(';');

    panel.innerHTML = [
      '<div style="display:flex;justify-content:space-between;margin-bottom:8px">',
      '  <strong style="color:#d4a574">SEON DEBUG</strong>',
      '  <span id="seon-debug-toggle" style="cursor:pointer;color:#888">[_]</span>',
      '</div>',
      '<div id="seon-debug-content">',
      '  <div style="margin-bottom:8px;padding-bottom:8px;border-bottom:1px solid #333">',
      '    <div style="margin-bottom:4px">',
      '      <span style="color:#888">SSE Status:</span> ',
      '      <span id="seon-sse-status" style="color:#666">idle</span>',
      '    </div>',
      '    <div style="margin-bottom:4px">',
      '      <span style="color:#888">SSE URL:</span> ',
      '      <span id="seon-sse-url" style="color:#666">-</span>',
      '    </div>',
      '    <div style="margin-bottom:4px">',
      '      <span style="color:#888">Patch Events:</span> ',
      '      <span id="seon-patch-count" style="color:#81c784">0</span>',
      '    </div>',
      '    <div style="margin-bottom:4px">',
      '      <span style="color:#888">Last Event:</span> ',
      '      <span id="seon-last-event" style="color:#666">none</span>',
      '    </div>',
      '  </div>',
      '  <div id="seon-errors" style="color:#e57373;margin-bottom:8px"></div>',
      '  <div style="margin-bottom:4px;color:#888">Event Log:</div>',
      '  <div id="seon-event-log" style="max-height:250px;overflow-y:auto;padding:4px;background:#111;border-radius:2px"></div>',
      '</div>'
    ].join('');

    document.body.appendChild(panel);

    // Toggle collapse
    var isCollapsed = false;
    document.getElementById('seon-debug-toggle').onclick = function() {
      var content = document.getElementById('seon-debug-content');
      if (isCollapsed) {
        content.style.display = 'block';
        this.textContent = '[_]';
        isCollapsed = false;
      } else {
        content.style.display = 'none';
        this.textContent = '[+]';
        isCollapsed = true;
      }
    };

    return panel;
  }

  function updatePanel() {
    var sseStatus = document.getElementById('seon-sse-status');
    var sseUrl = document.getElementById('seon-sse-url');
    var patchCount = document.getElementById('seon-patch-count');
    var lastEvent = document.getElementById('seon-last-event');
    var errors = document.getElementById('seon-errors');
    var eventLog = document.getElementById('seon-event-log');

    if (!sseStatus) return;

    // SSE status with color coding
    var statusColors = {
      idle: '#888',
      connecting: '#ffd54f',
      connected: '#81c784',
      error: '#e57373',
      finished: '#666'
    };
    sseStatus.textContent = DEBUG.sse.status;
    sseStatus.style.color = statusColors[DEBUG.sse.status] || '#888';

    // SSE URL
    sseUrl.textContent = DEBUG.sse.url || '-';

    // Patch count
    patchCount.textContent = DEBUG.sse.patchCount;

    // Last event
    if (DEBUG.sse.lastEvent) {
      var ago = Math.round((Date.now() - DEBUG.sse.lastEvent.time) / 1000);
      lastEvent.textContent = DEBUG.sse.lastEvent.type + ' (' + ago + 's ago)';
      lastEvent.style.color = '#d4c5a9';
    }

    // Errors
    if (DEBUG.sse.errors.length > 0) {
      errors.innerHTML = DEBUG.sse.errors.slice(-3).map(function(e) {
        return '<div style="margin-bottom:2px">' + escapeHtml(e) + '</div>';
      }).join('');
    } else {
      errors.innerHTML = '';
    }

    // Event log - show most recent first
    eventLog.innerHTML = DEBUG.events.slice(-20).reverse().map(function(e) {
      var typeColors = {
        'started': '#ffd54f',
        'finished': '#666',
        'error': '#e57373',
        'retrying': '#ffd54f',
        'datastar-patch-elements': '#81c784',
        'datastar-patch-signals': '#81c784',
        'fetch:post': '#64b5f6',
        'fetch:stream': '#64b5f6',
        'info': '#888'
      };
      var color = typeColors[e.type] || '#888';
      var time = new Date(e.time).toLocaleTimeString();
      return '<div style="margin-bottom:4px;border-bottom:1px solid #222;padding-bottom:4px">' +
             '<div><span style="color:#555">' + time + '</span> ' +
             '<span style="color:' + color + '">' + escapeHtml(e.type) + '</span></div>' +
             (e.detail ? '<div style="color:#666;font-size:10px;white-space:pre-wrap;word-break:break-all">' +
              escapeHtml(truncate(e.detail, 200)) + '</div>' : '') +
             '</div>';
    }).join('');
  }

  function escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function truncate(str, len) {
    if (!str) return '';
    return str.length > len ? str.substring(0, len) + '...' : str;
  }

  function logEvent(type, detail) {
    var entry = {
      time: Date.now(),
      type: type,
      detail: detail || ''
    };
    DEBUG.events.push(entry);
    if (DEBUG.events.length > DEBUG.maxEvents) {
      DEBUG.events.shift();
    }
    DEBUG.sse.lastEvent = entry;
    // Only log important events to console to reduce noise
    // Patch events are tracked in the panel, no need to spam console
    if (type === 'error' || type === 'retrying' || type === 'started') {
      console.log('[seon-debug]', type, detail || '');
    }
    updatePanel();
  }

  // Listen for Datastar's 'datastar-fetch' event
  // This is the CORRECT way to track Datastar SSE - it uses Fetch API internally
  function setupDatastarTracking() {
    document.addEventListener('datastar-fetch', function(e) {
      var detail = e.detail || {};
      var type = detail.type;
      var argsRaw = detail.argsRaw || {};

      // Track event
      DEBUG.sse.eventCount++;
      DEBUG.sse.lastEvent = { type: type, time: Date.now() };

      // Process different event types
      switch(type) {
        case 'started':
          DEBUG.sse.status = 'connecting';
          logEvent('started', 'SSE request initiated');
          break;

        case 'finished':
          DEBUG.sse.status = 'finished';
          logEvent('finished', 'SSE connection closed');
          break;

        case 'error':
          DEBUG.sse.status = 'error';
          DEBUG.sse.errors.push('SSE error: ' + (argsRaw.status || 'unknown'));
          logEvent('error', 'Status: ' + (argsRaw.status || 'unknown'));
          break;

        case 'retrying':
          DEBUG.sse.status = 'connecting';
          logEvent('retrying', argsRaw.message || '');
          break;

        case 'retries-failed':
          DEBUG.sse.status = 'error';
          DEBUG.sse.errors.push('Max retries exceeded');
          logEvent('error', 'Max retries exceeded');
          break;

        case 'datastar-patch-elements':
          DEBUG.sse.status = 'connected';
          DEBUG.sse.patchCount++;
          // Store the raw elements data for inspection
          var elements = argsRaw.elements || '';
          var selector = argsRaw.selector || '(auto)';
          var mode = argsRaw.mode || 'outer';
          var useVT = argsRaw.useViewTransition || false;
          var receiveTime = performance.now();
          DEBUG.rawEvents.push({ type: type, argsRaw: argsRaw, time: Date.now() });
          if (DEBUG.rawEvents.length > 20) DEBUG.rawEvents.shift();
          // Measure DOM patch duration — requestAnimationFrame fires after morph completes
          requestAnimationFrame(function() {
            var patchMs = (performance.now() - receiveTime).toFixed(1);
            DEBUG.lastPatchMs = parseFloat(patchMs);
            if (DEBUG.lastPatchMs > 100) {
              console.warn('[seon-debug] slow patch: ' + patchMs + 'ms, viewTransition=' + useVT);
            }
          });
          logEvent('datastar-patch-elements',
                   'selector=' + selector + ' mode=' + mode +
                   (useVT ? ' viewTransition=true' : '') + '\n' +
                   truncate(elements, 150));
          break;

        case 'datastar-patch-signals':
          DEBUG.sse.status = 'connected';
          var signals = argsRaw.signals || '';
          logEvent('datastar-patch-signals', truncate(signals, 100));
          break;

        default:
          logEvent(type, JSON.stringify(argsRaw).substring(0, 100));
      }

      updatePanel();
    });

    logEvent('info', 'Listening for datastar-fetch events');
  }

  // Track fetch requests to see what URLs are being called
  var originalFetch = window.fetch;
  window.fetch = function(url, options) {
    var urlStr = typeof url === 'string' ? url : (url.url || url.toString());
    var method = (options && options.method) || 'GET';

    // Only log POST requests (Datastar actions)
    if (method === 'POST') {
      logEvent('fetch:post', urlStr);
      DEBUG.sse.url = urlStr;
    }

    return originalFetch.apply(this, arguments).then(function(response) {
      // Check if it's an SSE response
      var contentType = response.headers.get('content-type') || '';
      if (contentType.includes('text/event-stream')) {
        DEBUG.sse.status = 'connected';
        logEvent('fetch:stream', urlStr + ' (Content-Type: ' + contentType + ')');
      }
      return response;
    }).catch(function(err) {
      DEBUG.sse.status = 'error';
      DEBUG.sse.errors.push('Fetch error: ' + err.message);
      logEvent('error', 'Fetch failed: ' + urlStr + ' - ' + err.message);
      throw err;
    });
  };

  // Check if Datastar is loaded by looking for its script
  function checkDatastar() {
    var scripts = document.querySelectorAll('script[src*="datastar"]');
    if (scripts.length > 0) {
      DEBUG.datastar.loaded = true;
      logEvent('info', 'Datastar script found');
    }
  }

  // Initialize
  function init() {
    createPanel();
    setupDatastarTracking();
    checkDatastar();
    logEvent('info', 'Debug panel initialized');

    // Update panel periodically
    setInterval(updatePanel, 2000);
  }

  // Start when DOM ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
