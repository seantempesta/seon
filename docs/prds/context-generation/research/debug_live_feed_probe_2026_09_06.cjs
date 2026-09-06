// Usage: NODE_PATH=... node this-file.cjs URL SCREENSHOT_PATH
// Waits for the orchestrator's explicit transaction on its own scratch cluster.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const beforeText = process.argv[4] || 'Before live update';
const afterText = process.argv[5] || 'After live update';
(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true, timeout: 15000,
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    let navigations = 0;
    page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigations++; });
    await page.goto(process.argv[2], { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(expected => {
      const output = document.querySelector('#debug-html-inspection')?.innerText || '';
      return (output.includes(expected) || output.includes(':seon.error/')) &&
        document.querySelector('[data-graph-canvas]')?._cyreg?.cy?.edges().length > 0;
    }, beforeText);
    const firstOutput = await page.locator('#debug-html-inspection').innerText();
    assert(firstOutput.includes(beforeText), firstOutput);
    const before = await page.evaluate(() => {
      const container = document.querySelector('[data-graph-canvas]');
      const cy = container._cyreg.cy;
      cy.viewport({ zoom: 0.8, pan: { x: 75, y: 85 } });
      window.seonBrowserProof = { container, cy };
      return { header: document.querySelector('#debug-inspection-header').innerText,
        edges: cy.edges().length, nodes: cy.nodes().length };
    });
    console.log(JSON.stringify({ readyForTransaction: true, before }));
    await page.waitForFunction(expected =>
      document.querySelector('#debug-html-inspection')?.innerText.includes(expected) &&
      document.querySelector('[data-graph-canvas]')?._cyreg?.cy?.edges().length === 2,
    afterText, { timeout: 60000 });
    const after = await page.evaluate(() => {
      const container = document.querySelector('[data-graph-canvas]');
      const cy = container._cyreg.cy;
      return { header: document.querySelector('#debug-inspection-header').innerText,
        output: document.querySelector('#debug-html-inspection').innerText,
        graphStatus: document.querySelector('[data-graph-status]').textContent,
        edges: cy.edges().length, nodes: cy.nodes().length,
        sameContainer: window.seonBrowserProof.container === container,
        sameInstance: window.seonBrowserProof.cy === cy,
        zoom: cy.zoom(), pan: cy.pan() };
    });
    console.log(JSON.stringify({ before, after, navigations, errors }, null, 2));
    assert.notEqual(after.header, before.header);
    assert(after.sameContainer && after.sameInstance);
    assert.equal(after.zoom, 0.8);
    assert.deepEqual(after.pan, { x: 75, y: 85 });
    assert.equal(navigations, 1, 'updates must arrive without page navigation');
    assert.deepEqual(errors, []);
    await page.screenshot({ path: process.argv[3], fullPage: true });
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
