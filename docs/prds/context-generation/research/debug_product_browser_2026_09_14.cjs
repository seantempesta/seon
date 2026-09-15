// Read-only live ledger verification. NODE_PATH must include Playwright.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
(async () => {
 const browser = await chromium.launch({headless:true, channel:'chrome'});
 const prefix = process.argv[2] || 'ledger';
 const turn = process.argv[3];
 try {
  await fs.mkdir('tmp/debug-product', {recursive:true});
  for (const width of [1440,700]) {
   const page = await browser.newPage({viewport:{width,height:900}});
   page.setDefaultTimeout(30000);
   const errors=[]; page.on('pageerror',e=>errors.push(e.message));
   const start=Date.now();
   const response=await page.goto('http://127.0.0.1:7994/ns/my.agents.juniper/debug'+(turn?'?turn='+turn:''));
   assert.equal(response.status(),200);
   await page.locator('.seon-ledger').waitFor();
   const cards=page.locator('.seon-ledger-turn');
   assert.equal(await cards.count(),61);
   const selected=turn || await cards.last().getAttribute('data-turn-id');
   const card=page.locator(`.seon-ledger-turn[data-turn-id="${selected}"]`);
   await page.waitForFunction(id=>document.querySelector(`[data-turn-id="${id}"]`).style.scrollMarginTop,selected);
   await page.screenshot({path:`tmp/debug-product/${prefix}-${width}-selected.png`});
   await page.evaluate(()=>scrollTo(0,0));
   await page.screenshot({path:`tmp/debug-product/${prefix}-${width}.png`,fullPage:true});
   await page.screenshot({path:`tmp/debug-product/${prefix}-${width}-top.png`});
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),width);
   for (const system of await page.locator('.seon-ledger-turn[data-turn-kind="System"]').all())
    assert.equal(await system.locator('.seon-ledger-reply').count(),0);
   const bytes=await card.locator('[data-reply-bytes]').textContent();
   assert.equal(Buffer.byteLength(bytes),Number(await card.locator('[data-reply-bytes]').getAttribute('data-reply-bytes')));
   assert.equal(await card.locator('.seon-ledger-sent[data-author="seon"]').count(),1);
   assert.equal(await card.locator('.seon-ledger-reply[data-author="agent"]').count(),1);
   assert.equal(await card.locator('.seon-ledger-results[data-author="seon"]').count(),1);
   assert.equal(await card.locator('.seon-ledger-result').count(),
    Number(await card.locator('[data-evaluation-count]').getAttribute('data-evaluation-count')));
   await card.locator('.seon-ledger-full-context > summary').click();
   await card.locator('[data-context-loaded]').waitFor();
   const coloured=(await card.locator('[data-emission-bytes]').allTextContents()).join('');
   assert.ok(coloured.length);
   const raw=await browser.newPage({viewport:{width,height:900}});
   await raw.goto(`http://127.0.0.1:7994/ns/my.agents.juniper/debug?turn=${selected}&prompt=true`);
   await raw.locator('[data-prompt-bytes]').waitFor();
   assert.equal(await raw.locator('[data-prompt-bytes]').textContent(),coloured);
   await raw.getByRole('link',{name:'Turn ledger',exact:true}).click();
   await raw.locator('.seon-ledger').waitFor();
   await raw.close();
   const opening=cards.first();
   if (!(await opening.getAttribute('open'))) {
    await opening.locator(':scope > summary').click();
    await opening.locator('[data-ledger-loaded]').waitFor();
   }
   assert.equal(await opening.locator('.seon-ledger-reply').count(),0);
   assert.ok((await opening.locator('.seon-ledger-sent').textContent()).includes('WE GENERATED (opening)'));
   const strip=page.locator('[data-strip-turn]');
   assert.equal(await strip.count(),61);
   const target=await strip.nth(2).getAttribute('data-strip-turn');
   await strip.nth(2).click();
   await page.locator(`[data-strip-turn="${target}"][aria-current="step"]`).waitFor();
   await page.locator(`[data-turn-id="${target}"] [data-ledger-loaded]`).waitFor();
   await page.waitForFunction(id=>document.querySelector(`[data-turn-id="${id}"]`).style.scrollMarginTop,target);
   assert.ok(page.url().includes(target));
   await page.screenshot({path:`tmp/debug-product/${prefix}-${width}-navigation.png`});
   assert.deepEqual(errors,[]);
   console.log(JSON.stringify({width,selected,cards:61,replyBytes:Buffer.byteLength(bytes),promptBytes:Buffer.byteLength(coloured),elapsedMs:Date.now()-start}));
   await page.goto('http://127.0.0.1:7994/ns/my.agents.juniper');
   await page.locator('.seon-session-header').waitFor();
   await page.screenshot({path:`tmp/debug-product/${prefix}-agent-${width}.png`,fullPage:true});
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),width);
   await page.close();
  }
 } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
