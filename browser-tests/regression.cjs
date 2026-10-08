const { test } = require('node:test');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const baseURL = process.env.M3_TEST_URL || 'http://localhost:8080';
const file = path.join(__dirname, '../.m3/local-security.properties');
const properties = fs.existsSync(file) ? Object.fromEntries(fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(line => line.includes('=')).map(line => [line.slice(0,line.indexOf('=')),line.slice(line.indexOf('=')+1)])) : {};
const password = role => process.env[`M3_${role.toUpperCase()}_PASSWORD`] || properties[`m3.security.${role}-password`];
async function login(page, role) {
  assert.ok(password(role), `Configure a ${role} password before testing`);
  await page.goto(`${baseURL}/overview`);
  await page.getByRole('textbox', { name: 'Username', exact: true }).fill(role);
  await page.getByRole('textbox', { name: 'Password', exact: true }).fill(password(role));
  await page.getByRole('button', { name: 'Log in', exact: true }).click();
  await page.locator('.app-page-title').waitFor();
}
async function withPage(run) {
  const browser = await chromium.launch({ channel: 'chrome' });
  try {
    const context = await browser.newContext({ locale: 'en-US' });
    await run(await context.newPage(), context);
  } finally { await browser.close(); }
}
test('anonymous API access is rejected', async () => {
  const response = await fetch(`${baseURL}/api/v1/messages/1`);
  assert.equal(response.status, 401);
});
test('admin can open audit and sign out', async () => withPage(async page => {
  await login(page,'admin');
  await page.goto(`${baseURL}/audit`);
  await page.getByRole('columnheader', { name: 'Operation', exact: true }).waitFor();
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await page.getByRole('textbox', { name: 'Username', exact: true }).waitFor();
}));
test('viewer sees messages but cannot administer or mutate the API', async () => withPage(async (page,context) => {
  await login(page,'viewer');
  assert.equal(await page.getByRole('button',{name:'Administration',exact:true}).count(),0);
  await page.goto(`${baseURL}/channels`);
  await page.getByRole('button',{name:'Add Channel',exact:true}).waitFor({state:'hidden'});
  assert.equal(await page.getByRole('button',{name:'Save',exact:true}).count(),0);
  const response=await context.request.post(`${baseURL}/api/v1/messages/1/retry`,{
    headers:{Authorization:`Basic ${Buffer.from(`viewer:${password('viewer')}`).toString('base64')}`,'X-M3-Request':'1'}
  });
  assert.equal(response.status(),403);
}));
test('mutating API rejects browser-simple requests without the explicit marker',async()=>{
  const response=await fetch(`${baseURL}/api/v1/messages/1/retry`,{method:'POST',headers:{Authorization:`Basic ${Buffer.from(`admin:${password('admin')}`).toString('base64')}`}});
  assert.equal(response.status,403);
});
test('filters survive navigation and invalid links show an error',async()=>withPage(async page=>{
  await login(page,'admin');
  await page.goto(`${baseURL}/messages/inbound?source=browser-regression`);
  const source=page.getByRole('textbox',{name:'Source',exact:true});
  await source.waitFor(); assert.equal(await source.inputValue(),'browser-regression');
  await source.fill('another-source');
  await page.getByRole('button',{name:'Apply filters',exact:true}).click();
  await page.waitForURL(/source=another-source/);
  await page.goBack();
  await page.waitForURL(/source=browser-regression/);
  await page.waitForFunction(() => [...document.querySelectorAll('vaadin-text-field')].find(field => field.label==='Source')?.value==='browser-regression');
  assert.equal(await source.inputValue(),'browser-regression');
  await page.goto(`${baseURL}/messages/inbound?id=invalid`);
  await page.locator('.message-filters [role="alert"]').waitFor();
}));
test('discarding a new channel form does not save its changes',async()=>withPage(async page=>{
  await login(page,'admin');
  await page.goto(`${baseURL}/channels`);
  await page.getByRole('button',{name:'Add Channel',exact:true}).click();
  await page.getByRole('textbox',{name:'Name',exact:true}).fill('browser-unsaved-only');
  await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await page.getByRole('button',{name:'Keep editing',exact:true}).click();
  await page.getByRole('button',{name:'Keep editing',exact:true}).waitFor({state:'hidden'});
  assert.equal(await page.getByRole('textbox',{name:'Name',exact:true}).inputValue(),'browser-unsaved-only');
  await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await page.getByRole('button',{name:'Discard changes',exact:true}).click();
  await page.getByRole('textbox',{name:'Name',exact:true}).waitFor({state:'hidden'});
}));
