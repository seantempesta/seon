// Verify useful namespace content through the actual paired debug render path.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');

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
    const start = performance.now();
    await page.goto(process.argv[2], { waitUntil: 'domcontentloaded' });
    await page.locator('.seon-debug-experiment-stages').waitFor();
    const evidence = await page.locator('.seon-debug-projection-column').evaluateAll(columns =>
      columns.map(column => ({
        output: column.querySelector('h4')?.textContent,
        text: column.innerText,
      })));
    assert(evidence.some(column => column.output === 'HTML' && column.text.includes('seon.flow/start-graph!')),
      'a function summary must remain visible in actual HTML output');
    assert(evidence.some(column => column.output === 'AI' && column.text.includes('seon.flow/start-graph!')),
      'AI projection must be present alongside HTML');
    assert.deepEqual(errors, []);
    if (process.argv[3]) await page.screenshot({path: process.argv[3], fullPage: false});
    console.log(JSON.stringify({readyMs: performance.now() - start,
      columns: evidence.map(({output, text}) => ({output, characters: text.length})), errors}));
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode = 1;});
