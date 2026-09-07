// Inspect the live Juniper context UI; save the actual browser output for review.
// Usage: node this-file.cjs URL OUTPUT_PREFIX [--lock] [--await-change] [--append-and-compact]
const {chromium} = require('playwright');
const fs = require('node:fs/promises');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
  });
  let page;
  try {
    page = await browser.newPage({viewport: {width: 1440, height: 1100}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const response = await page.goto(process.argv[2], {waitUntil: 'domcontentloaded', timeout: 30000});
    await page.locator('.seon-debug-selected-previews').first().waitFor({timeout: 15000});
    await page.getByText('Context selection', {exact: true}).waitFor({timeout: 15000});
    let readinessError;
    try {
      await page.getByRole('button', {name: 'Lock preview into context', exact: true})
        .waitFor({timeout: 30000});
    } catch (error) { readinessError = error.message; }
    let lockedRun;
    if (!readinessError && process.argv.includes('--lock')) {
      const button = page.getByRole('button', {name: 'Lock preview into context', exact: true});
      lockedRun = await button.locator('xpath=ancestor::form').locator('input[name="run"]').inputValue();
      const beforeCount = await page.getByText('Locked context', {exact: true}).count();
      await button.click();
      await page.getByText('Locked context', {exact: true}).nth(beforeCount).waitFor({timeout: 30000});
      await page.getByText('Unchanged', {exact: true}).first().waitFor({timeout: 30000});
      await page.getByText('Locked context', {exact: true}).last().scrollIntoViewIfNeeded();
    }
    if (process.argv.includes('--await-change')) {
      console.log(JSON.stringify({readyForDataChange: true, lockedRun}));
      await page.getByText('Changed since locking', {exact: true}).first().waitFor({timeout: 60000});
      await page.getByText('Changed since locking', {exact: true}).first().scrollIntoViewIfNeeded();
    }
    let compactedContribution;
    if (process.argv.includes('--append-and-compact')) {
      const append = page.getByRole('button', {name: 'Append updated context', exact: true}).first();
      await append.waitFor({timeout: 30000});
      const countBefore = await page.getByText('Locked context', {exact: true}).count();
      await append.click();
      await page.getByText('Locked context', {exact: true}).nth(countBefore).waitFor({timeout: 30000});
      const compact = page.getByRole('button', {name: 'Compact to current results', exact: true}).first();
      compactedContribution = await compact.locator('xpath=ancestor::form').locator('input[name="contribution"]').inputValue();
      await compact.click();
      await page.getByRole('button', {name: 'Compact to current results', exact: true}).waitFor({state: 'hidden', timeout: 30000});
      const countAfter = await page.getByText('Locked context', {exact: true}).count();
      if (countAfter !== countBefore + 1) throw new Error(`Append/compact changed block count unexpectedly: ${countBefore} -> ${countAfter}`);
      await page.getByText('Locked context', {exact: true}).last().scrollIntoViewIfNeeded();
    }
    const text = await page.locator('body').innerText();
    await fs.writeFile(`${process.argv[3]}.txt`, text);
    await page.screenshot({path: `${process.argv[3]}.png`, fullPage: false});
    console.log(JSON.stringify({status: response.status(), url: page.url(), errors,
      textFile: `${process.argv[3]}.txt`, screenshot: `${process.argv[3]}.png`,
      lockedRun, compactedContribution, forms: await page.locator('form').count(), buttons: await page.getByRole('button').allTextContents(),
      readinessError}));
    if (readinessError || errors.length) process.exitCode = 1;
  } catch (error) {
    if (page) {
      await fs.writeFile(`${process.argv[3]}.txt`, await page.locator('body').innerText());
      await page.screenshot({path: `${process.argv[3]}.png`, fullPage: false});
    }
    throw error;
  } finally {
    await browser.close();
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
