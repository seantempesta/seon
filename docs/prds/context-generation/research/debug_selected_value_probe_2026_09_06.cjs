// Verify that get-in navigation selects the actual renderer input.
// Argument: debug URL for plan item 32011 in the lab-browser-0906 fixture.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true, timeout: 15000,
  });
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const url = new URL(process.argv[2]);
    const subject = url.searchParams.get('subject');
    await page.goto(url.href, {waitUntil: 'domcontentloaded'});
    await page.locator('.seon-debug-structural-detail > summary').click();
    await page.locator('.seon-debug-evidence > summary').click();
    const titleLink = page.locator('.seon-debug-datom-page a').filter({hasText: '"After live update"'});
    assert.equal(await titleLink.count(), 1, 'the stored title exposes a value inspection link');
    await titleLink.click();
    await page.locator('.seon-debug-experiment-stages').waitFor();
    const choices = await page.locator('.seon-debug-projection-column').evaluateAll(columns =>
      columns.flatMap(column => [...column.querySelectorAll('.seon-debug-candidate')]
        .filter(candidate => candidate.querySelector('.seon-debug-section-line span')?.textContent === 'chosen')
        .map(candidate => ({
          output: column.querySelector('h4').textContent,
          producer: candidate.querySelector('.seon-debug-section-line a').textContent,
          text: candidate.querySelector('.seon-debug-candidate-preview').innerText,
        }))));
    assert.equal(choices.length, 2);
    for (const choice of choices) {
      assert.equal(choice.producer, 'seon.render.value/render-' + choice.output.toLowerCase(),
        'the scalar title must select its own renderer instead of the plan entity renderer');
      assert(choice.text.includes('After live update'));
      assert(!choice.text.includes('lab-browser-0906-item'), 'the enclosing entity must not be rendered');
    }
    const header = await page.locator('#debug-inspection-header').innerText();
    assert(header.includes('[:my.plan.item/title]'), 'the selected path must be visible');
    assert.equal(new URL(page.url()).searchParams.get('subject'), subject);
    const graph = await page.locator('[data-graph-model]').evaluate(node => JSON.parse(node.textContent));
    assert(graph['seon.graph/selected'].includes(subject), 'graph remains on the entity');
    await page.getByRole('link', {name: 'return to entity'}).click();
    await page.waitForFunction(() =>
      document.querySelector('#debug-html-inspection')?.innerText.includes('lab-browser-0906-item'));
    assert.equal(new URL(page.url()).searchParams.get('path'), '[]');
    assert.equal(new URL(page.url()).searchParams.get('subject'), subject);
    url.searchParams.set('path', '[:my.plan.item/missing]');
    await page.goto(url.href, {waitUntil: 'domcontentloaded'});
    await page.waitForFunction(() =>
      document.querySelector('#debug-html-inspection')?.innerText.includes('no-such-path'));
    assert(!(await page.locator('#debug-html-inspection').innerText()).includes('After live update'));
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({choices, header, valueLink: true, returnToEntity: true,
      missingPathDiagnostic: true, errors}));
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode = 1;});
