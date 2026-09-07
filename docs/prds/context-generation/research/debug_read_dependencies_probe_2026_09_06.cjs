// Inspect existing renderer read evidence; never execute a query from this UI.
// Argument: lab-browser-0906 plan-item debug URL.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true, timeout: 15000,
  });
  try {
    const page = await browser.newPage();
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const chosen = output => page.locator('.seon-debug-projection-column')
      .filter({has: page.locator('h4', {hasText: output})})
      .locator('.seon-debug-candidate')
      .filter({has: page.locator('.seon-debug-section-line span', {hasText: 'chosen'})});
    await page.goto(process.argv[2], {waitUntil: 'domcontentloaded'});
    const itemReads = chosen('HTML').locator('.seon-debug-read-dependencies');
    await itemReads.locator(':scope > summary').click();
    assert((await itemReads.innerText()).includes('No database read evidence was retained.'),
      'the plan item renderer uses its supplied value and retains zero reads');

    await page.goto(new URL('/ns/seon.flow/debug', process.argv[2]).href, {
      waitUntil: 'domcontentloaded',
    });
    const namespaceReads = chosen('HTML').locator('.seon-debug-read-dependencies');
    await namespaceReads.locator(':scope > summary').click();
    const entries = namespaceReads.locator(':scope > ol > li > details');
    const count = await entries.count();
    assert(count > 0, 'namespace rendering must positively expose actual database reads');
    for (const entry of await entries.all()) await entry.locator(':scope > summary').click();
    const content = await namespaceReads.innerText();
    assert(content.includes(':read-operation :q'), 'the retained query operation is visible');
    assert(content.includes(':seon.fn/ns'), 'the function query dependency is visible');
    assert(content.includes(':datahike.read/revision'), 'the dependency revision is inspectable');
    assert(content.includes('render basis transaction'));
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({itemReadEvidenceEmpty: true, namespaceReadEntries: count,
      actualQueryVisible: true, revisionsVisible: true, errors}));
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode = 1;});

