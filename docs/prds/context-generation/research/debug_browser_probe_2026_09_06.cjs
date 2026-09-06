// Live-browser inspection. NODE_PATH must resolve the bundled Playwright package.
// Usage: node this-file.cjs URL SCREENSHOT_PATH
const { chromium } = require('playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
    timeout: 15000,
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const started = performance.now();
    await page.goto(process.argv[2], { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => {
      const canvas = document.querySelector('[data-graph-canvas]');
      return canvas?._cyreg?.cy?.nodes().length > 0;
    });
    const readyMs = performance.now() - started;
    const initial = await page.evaluate(() => {
      const canvas = document.querySelector('[data-graph-canvas]');
      const cy = canvas._cyreg.cy;
      const model = JSON.parse(document.querySelector('[data-graph-model]').textContent);
      return {
        nodes: cy.nodes().length,
        edges: cy.edges().length,
        modelNodes: model.elements.nodes.length,
        modelEdges: model.elements.edges.length,
        graphText: document.querySelector('#debug-graph').innerText,
        header: document.querySelector('#debug-inspection-header').innerText,
        output: document.querySelector('#debug-html-inspection').innerText.slice(0, 400),
        canvas: { width: canvas.clientWidth, height: canvas.clientHeight },
        containerTag: canvas.tagName,
        drawingCanvases: canvas.querySelectorAll('canvas').length,
      };
    });
    assert(initial.nodes > 0);
    assert(initial.edges > 0, 'a populated reference graph is required');
    assert.equal(initial.nodes, initial.modelNodes);
    assert.equal(initial.edges, initial.modelEdges);
    assert.equal(initial.containerTag, 'DIV', 'Cytoscape needs a visible container, not canvas fallback content');
    assert(initial.drawingCanvases > 0);
    assert(initial.graphText.includes('reference assertions; outgoing'));
    await page.getByText('selection evidence', { exact: true }).click();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.screenshot({ path: process.argv[3], fullPage: true });
    // Client-only reconciliation proof: does not claim a database transaction occurred.
    const reconciliation = await page.evaluate(async () => {
      const canvas = document.querySelector('[data-graph-canvas]');
      const cy = canvas._cyreg.cy;
      const script = document.querySelector('[data-graph-model]');
      const original = script.textContent;
      const model = JSON.parse(original);
      const edge = cy.edges().first();
      edge.select();
      edge.emit('tap');
      const status = document.querySelector('[data-graph-status]').textContent;
      const detail = document.querySelector('[data-graph-detail]').textContent;
      cy.viewport({ zoom: 0.7, pan: { x: 43, y: 29 } });
      const position = cy.nodes().first().position();
      model.elements.nodes[0].data.label += ' (browser probe)';
      script.textContent = JSON.stringify(model);
      await new Promise(resolve => requestAnimationFrame(resolve));
      const result = {
        sameInstance: canvas._cyreg.cy === cy,
        zoom: cy.zoom(), pan: cy.pan(),
        positionPreserved: JSON.stringify(position) === JSON.stringify(cy.nodes().first().position()),
        edgeSelected: edge.selected(),
        labelUpdated: cy.getElementById(model.elements.nodes[0].data.id).data('label') === model.elements.nodes[0].data.label,
        statusPreserved: status === document.querySelector('[data-graph-status]').textContent,
        edgeDetail: detail.includes(edge.data('attribute')),
      };
      script.textContent = original;
      return result;
    });
    console.log(JSON.stringify({ readyMs, initial, reconciliation, errors }, null, 2));
    assert(reconciliation.sameInstance && reconciliation.positionPreserved);
    assert(reconciliation.edgeSelected && reconciliation.labelUpdated);
    assert(reconciliation.statusPreserved && reconciliation.edgeDetail);
    assert.equal(reconciliation.zoom, 0.7);
    assert.deepEqual(reconciliation.pan, { x: 43, y: 29 });
    const target = await page.evaluate(() => {
      const container = document.querySelector('[data-graph-canvas]');
      const cy = container._cyreg.cy;
      const model = JSON.parse(document.querySelector('[data-graph-model]').textContent);
      const node = cy.nodes().filter(n => n.id() !== model['seon.graph/selected']).first();
      cy.zoom(1);
      cy.center(node);
      const position = node.renderedPosition();
      const rect = container.getBoundingClientRect();
      return { href: new URL(node.data('href'), location.href).href,
        x: rect.x + position.x, y: rect.y + position.y };
    });
    await page.mouse.click(target.x, target.y);
    await page.waitForURL(target.href);
    await page.waitForSelector('[data-graph-model]', { state: 'attached' });
    const navigation = { url: page.url(), header: await page.locator('#debug-inspection-header').innerText() };
    assert.equal(new URL(navigation.url).pathname, new URL(process.argv[2]).pathname,
      're-rooting must preserve the viewing namespace');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => {
      const container = document.querySelector('[data-graph-canvas]');
      const drawing = container?.querySelector('canvas');
      return drawing && drawing.getBoundingClientRect().width <= container.clientWidth;
    });
    const mobile = await page.evaluate(() => ({ width: innerWidth,
      documentWidth: document.documentElement.scrollWidth,
      overflow: [...document.querySelectorAll('body *')].filter(el => el.getBoundingClientRect().right > innerWidth + 1)
        .slice(0, 8).map(el => ({tag: el.tagName, class: el.className, width: el.getBoundingClientRect().width})) }));
    console.log(JSON.stringify({ navigation, mobile }, null, 2));
    assert(mobile.documentWidth <= mobile.width, 'mobile layout must not overflow horizontally');
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
