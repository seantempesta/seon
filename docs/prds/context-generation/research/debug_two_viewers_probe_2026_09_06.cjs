// Read-only browser comparison. Arguments are two debug URLs for one subject.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true, timeout: 15000,
  });
  try {
    const observations = [];
    for (const url of process.argv.slice(2)) {
      const page = await browser.newPage();
      page.setDefaultTimeout(15000);
      await page.goto(url, { waitUntil: 'domcontentloaded' });
      await page.waitForFunction(() =>
        document.querySelector('#debug-html-inspection')?.innerText.includes('After live update'));
      await page.getByText('selection evidence', { exact: true }).click();
      observations.push(await page.evaluate(() => ({
        url: location.href,
        header: document.querySelector('#debug-inspection-header').innerText,
        selection: document.querySelector('#debug-selection').innerText,
        output: document.querySelector('#debug-html-inspection').innerText,
        model: JSON.parse(document.querySelector('[data-graph-model]').textContent),
      })));
      await page.close();
    }
    assert.equal(observations.length, 2);
    assert.notEqual(observations[0].header, observations[1].header);
    assert.equal(observations[0].output, observations[1].output);
    assert.equal(observations[0].model['seon.graph/selected'], observations[1].model['seon.graph/selected']);
    assert.deepEqual(observations[0].model['seon.graph/snapshot'], observations[1].model['seon.graph/snapshot']);
    assert.notEqual(observations[0].selection, observations[1].selection);
    console.log(JSON.stringify(observations.map(({model, selection, ...rest}) => ({
      ...rest, selected: model['seon.graph/selected'],
      selection: selection.slice(0, 1600),
      references: model.elements.edges.length,
    })), null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
