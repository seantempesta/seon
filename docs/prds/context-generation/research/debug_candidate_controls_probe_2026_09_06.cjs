// Actual browser controls; accepts the current plan-item debug URL.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true, timeout: 15000});
  try {
    const page = await browser.newPage();
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(process.argv[2], {waitUntil: 'domcontentloaded'});
    await page.waitForFunction(() => document.querySelector('#debug-html-inspection')?.innerText.includes('After live update'));
    await page.getByText('supplied argument', {exact:true}).click();
    assert((await page.locator('.seon-debug-call-evidence').innerText()).includes('After live update'));
    const contract = page.locator('#debug-selection > div > .seon-debug-renderer-evidence');
    await contract.locator('summary').click();
    const contractText = await contract.innerText();
    assert(contractText.includes(':my.plan.item/item'));
    assert(contractText.includes(':seon.render/hiccup'));
    const definition = await page.locator('.seon-debug-definition-link a').getAttribute('href');
    const definitionUrl = new URL(definition, page.url());
    assert(definitionUrl.searchParams.get('subject').includes('my.plan/render-item-html'));
    assert.equal(definitionUrl.searchParams.get('viewer'), 'my.plan');
    await page.getByRole('link', {name:'AI', exact:true}).click();
    await page.waitForFunction(() => document.querySelector('.seon-debug-selected')?.innerText.includes('my.plan/render-item-ai'));
    assert.equal(new URL(page.url()).searchParams.get('subject'), '32011');
    assert.equal(new URL(page.url()).searchParams.get('viewer'), 'my.plan');
    assert((await page.locator('#debug-html-inspection').innerText()).includes('After live update'));
    await page.getByRole('link', {name:'HTML', exact:true}).click();
    await page.waitForFunction(() => document.querySelector('.seon-debug-selected')?.innerText.includes('my.plan/render-item-html'));
    await page.locator('.seon-debug-definition-link a').click();
    await page.waitForFunction(() => document.querySelector('#debug-inspection-header')?.innerText.includes('my.plan/render-item-html'));
    assert(new URL(page.url()).searchParams.get('subject').includes('my.plan/render-item-html'));
    assert.equal(new URL(page.url()).searchParams.get('viewer'), 'my.plan');
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({contractText, definition, aiHtmlRoundTrip:true, definitionNavigation:true, errors}));
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode=1;});
