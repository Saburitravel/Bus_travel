const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const staticDir = path.join(__dirname, '../../main/resources/static');
const adminJs = fs.readFileSync(path.join(staticDir, 'admin.js'), 'utf8');
const adminHtml = fs.readFileSync(path.join(staticDir, 'admin.html'), 'utf8');

test('Stop Management exposes separate Add Stop and Add Fare actions', () => {
  assert.ok(adminHtml.includes('id="addStopBtn"'));
  assert.ok(adminHtml.includes('id="addFareBtn"'));
  assert.ok(adminHtml.includes('id="routeStopsList"'));
  assert.ok(adminHtml.includes('id="routeFaresList"'));
});

test('route list displays fixed origin and destination around editable intermediate stops', () => {
  assert.ok(adminJs.includes('Route origin'));
  assert.ok(adminJs.includes('Route destination'));
  assert.ok(adminJs.includes('Intermediate stop'));
  assert.ok(adminJs.includes('No intermediate stops yet'));
});

test('adding a stop does not request or submit a fare', () => {
  assert.ok(adminJs.includes('function stopFormHtml(stop)'));
  assert.ok(adminJs.includes('Adding a stop does not add or calculate any fare'));
  assert.ok(adminJs.includes("method:stop?'PUT':'POST',body:JSON.stringify(payload)"));
  assert.ok(!adminJs.includes('Fare from the previous point to ${name}'));
});

test('fares are entered manually for an exact boarding/destination pair', () => {
  assert.ok(adminJs.includes('function openFareDialog(fare=null)'));
  assert.ok(adminJs.includes("fromKey:String(fd.get('fromKey')||''),toKey:String(fd.get('toKey')||''),fare:Number(fd.get('fare'))"));
  assert.ok(adminJs.includes("'/admin/routes/'+encodeURIComponent(activeStopRoute.id)+'/fares'"));
  assert.ok(adminJs.includes("'/admin/fares/'+encodeURIComponent(id),{method:'DELETE'}"));
  assert.ok(adminJs.includes('No other fares were changed'));
});

test('schedule form has no fare input', () => {
  const start = adminJs.indexOf("schedule:{title:'New schedule'");
  const end = adminJs.indexOf("\n};", start);
  assert.ok(start >= 0 && end > start);
  assert.ok(!adminJs.slice(start, end).includes('name="fare"'));
});

test('schedule creation and editing do not send an editable fare', () => {
  const createStart = adminJs.indexOf("sbtFetch('/schedules',{method:'POST'");
  assert.ok(createStart >= 0);
  const createEnd = adminJs.indexOf('});', createStart);
  assert.ok(createEnd > createStart);
  assert.ok(!adminJs.slice(createStart, createEnd).includes('fare:'));
  assert.ok(adminJs.includes("id==='#scheduleTable'&&(i===4||i>=8)"));
  assert.ok(adminJs.includes('arrivalTime:row[3],status:row[6]'));
});
