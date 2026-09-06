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
      };
    });
    assert(initial.nodes > 0);
    assert(initial.edges > 0, 'a populated reference graph is required');
    assert.equal(initial.nodes, initial.modelNodes);
    assert.equal(initial.edges, initial.modelEdges);
    await page.getByText('selection evidence', { exact: true }).click();
    await page.screenshot({ path: process.argv[3], fullPage: true });
    console.log(JSON.stringify({ readyMs, initial, errors }, null, 2));
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
