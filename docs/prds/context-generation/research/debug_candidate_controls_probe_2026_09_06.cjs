// Browser controls over the ranked paired renderer UI. Argument: my.plan URL.
// Verified 2026-09-06 at basis 536871430: actual argument and contract
// disclosures, paired title output, function navigation, reference navigation,
// and zero page errors all passed.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
    timeout: 15000,
  });
  try {
    const page = await browser.newPage();
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(process.argv[2], { waitUntil: 'domcontentloaded' });
    await page.locator('.seon-debug-experiment-stages').waitFor();

    const namespaceStage = page.locator('.seon-debug-experiment-stages > li')
      .filter({ has: page.locator('h3', { hasText: ':namespace' }) });
    const column = label => namespaceStage.locator('.seon-debug-projection-column')
      .filter({ has: page.locator('h4', { hasText: label }) });
    const chosen = label => column(label).locator('.seon-debug-candidate')
      .filter({
        has: page.locator('.seon-debug-section-line span', { hasText: 'chosen' }),
      });

    const htmlChoice = chosen('HTML');
    await htmlChoice.locator('.seon-debug-call-evidence > summary').click();
    const argument = await htmlChoice.locator('.seon-debug-call-evidence').innerText();
    assert(argument.includes(':db/id 32011'));
    assert(argument.includes('After live update'));

    const contract = htmlChoice.locator('.seon-debug-renderer-evidence');
    await contract.locator('summary').click();
    const contractText = await contract.innerText();
    assert(contractText.includes(':my.plan.item/item'));
    assert(contractText.includes(':seon.render/hiccup'));

    const htmlPreview = await htmlChoice.locator('.seon-debug-candidate-preview').innerText();
    const aiPreview = await chosen('AI').locator('.seon-debug-candidate-preview').innerText();
    assert(htmlPreview.includes('After live update'));
    assert(aiPreview.includes('After live update'));

    const definition = await htmlChoice
      .locator('.seon-debug-section-line a').getAttribute('href');
    const definitionUrl = new URL(definition, page.url());
    assert(definitionUrl.searchParams.get('subject').includes('my.plan/render-item-html'));
    assert.equal(definitionUrl.searchParams.get('viewer'), 'my.plan');
    await htmlChoice.locator('.seon-debug-section-line a').click();
    await page.waitForFunction(() =>
      document.querySelector('#debug-inspection-header')
        ?.innerText.includes('my.plan/render-item-html'));
    assert.equal(new URL(page.url()).searchParams.get('viewer'), 'my.plan');

    await page.goBack({ waitUntil: 'domcontentloaded' });
    await page.locator('[data-graph-model]').waitFor({ state: 'attached' });
    const relatedHref = await page.locator('[data-graph-model]').evaluate(script => {
      const model = JSON.parse(script.textContent);
      return model.elements.nodes
        .find(node => node.data.id !== model['seon.graph/selected'])?.data.href;
    });
    assert(relatedHref, 'the reference graph must expose related navigation');
    await page.goto(new URL(relatedHref, page.url()).href, {
      waitUntil: 'domcontentloaded',
    });
    await page.locator('#debug-inspection-header').waitFor();
    assert.equal(new URL(page.url()).searchParams.get('viewer'), 'my.plan');
    assert.notEqual(new URL(page.url()).searchParams.get('subject'), '32011');
    assert.deepEqual(errors, []);

    console.log(JSON.stringify({
      argument,
      contractText,
      htmlPreview,
      aiPreview,
      definition,
      functionNavigation: true,
      referenceNavigation: true,
      errors,
    }));
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
