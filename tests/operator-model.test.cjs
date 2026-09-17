const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');

const filename = path.resolve(__dirname, '../src/operator-model.ts');
const loaded = new Module(filename, module);
loaded._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText, filename);
const { parsePairingCode, parseTelegramSetupCode, upsertPos, inspectPosUrl, safeTelegramSettings, recordingFolderLabel,
  normalizeConfiguration, isUzbekMobile, formatUzPhone, weekStart, managerShiftForWeek, managerInviteLink, isClock, DEFAULT_SHIFTS } = loaded.exports;
const qr = (id, ip, token = id) => JSON.stringify({
  version: 2, id, name: `POS ${id}`, url: `ws://${ip}:8765?token=${token}`, discoveryPort: 8766,
});

test('two scanned POS machines remain separate recipients and one can be removed', () => {
  let targets = upsertPos([], parsePairingCode(qr('first', '192.168.1.10')));
  targets = upsertPos(targets, parsePairingCode(qr('second', '192.168.1.20')));
  assert.deepEqual(targets.map(t => t.id), ['first', 'second']);
  assert.deepEqual(targets.filter(t => t.id !== 'first').map(t => t.id), ['second']);
});

test('rescanning stable machine identity updates its address rather than duplicating calls', () => {
  const original = parsePairingCode(qr('machine', '192.168.1.10'));
  const updated = parsePairingCode(qr('machine', '192.168.1.99'));
  const result = upsertPos([original], updated);
  assert.equal(result.length, 1);
  assert.equal(result[0].url, updated.url);
  assert.equal(result[0].discoveryPort, 8766);
});

test('existing single-POS URLs migrate and upgrade to a named v2 target', () => {
  const legacy = parsePairingCode('ws://192.168.1.3:8765?token=retained-secret');
  assert.ok(!legacy.id.includes('retained-secret'));
  const upgraded = parsePairingCode(qr('stable-id', '192.168.1.3', 'retained-secret'));
  assert.deepEqual(upsertPos([legacy], upgraded), [upgraded]);
});

test('invalid scanned payloads never include QR secrets in errors', () => {
  const invalid = [
    'https://example.com/?token=private-secret',
    'ws://user:password@example.com:8765?token=private-secret',
    'ws://192.168.1.2:70000?token=private-secret',
    'ws://192.168.1.2:8765?token=x&token=private-secret',
    'ws://192.168.1.2:8765?token=%broken',
    'ws://192.168.1.2:8765?token=',
    '{"version":2,"id":"bad","name":"bad","url":"ws://example:8765?token=private-secret","discoveryPort":-1}',
    'null', '{}', '[]', '{"version":3}',
  ];
  for (const value of invalid) assert.throws(() => parsePairingCode(value), error => {
    assert.ok(!error.message.includes('private-secret'));
    return true;
  });
});

test('WSS and IPv6 pairing retain the desktop address', () => {
  assert.equal(inspectPosUrl('wss://pos.example:8765/operator?token=abc').host, 'pos.example');
  assert.equal(inspectPosUrl('ws://[fe80::1234]:8765?token=abc').host, '[fe80::1234]');
});

test('view-model configuration retains independent groups and strips the bot token', () => {
  const saved = safeTelegramSettings({ enabled: false, sendCallStats: true, statsChatId: ' -100222 ',
    chatId: '-100111', botToken: '123:private', folderUri: 'content://recordings', folderName: 'Calls' });
  assert.equal(saved.sendCallStats, true);
  assert.equal(saved.enabled, false);
  assert.equal(saved.statsChatId, '-100222');
  assert.equal(saved.chatId, '-100111');
  assert.ok(!('botToken' in saved));
});

test('native POS-only configuration loads when Telegram has not been configured', () => {
  for (const partial of [{}, { enabled: false, sendCallStats: false }]) {
    assert.deepEqual(safeTelegramSettings(partial), {
      enabled: false, chatId: '', folderUri: '', folderName: '',
      sendMissedCalls: false, statsChatId: '', sendCallStats: false, backupChatId: '',
    });
  }
});

test('private Telegram setup QR imports separate destinations without enabling audio prematurely', () => {
  const setup = parseTelegramSetupCode(JSON.stringify({ type: 'smart_pos_telegram', version: 1,
    botToken: '12345:abcdefghijklmnopqrstuvwx', chatId: '-100111', statsChatId: '-100222' }));
  assert.equal(setup.chatId, '-100111');
  assert.equal(setup.statsChatId, '-100222');
  assert.ok(!('enabled' in setup));
  assert.ok(!('botToken' in safeTelegramSettings({ ...setup, enabled: false, folderUri: '', folderName: '' })));
});

test('Telegram import rejects a shared destination and never echoes the token', () => {
  const token = '12345:abcdefghijklmnopqrstuvwx';
  assert.throws(() => parseTelegramSetupCode(JSON.stringify({ type: 'smart_pos_telegram', version: 1,
    botToken: token, chatId: '-100111', statsChatId: '-100111' })), error => !error.message.includes(token));
});

test('recording folders are labelled relative to internal storage', () => {
  assert.equal(recordingFolderLabel({ name: 'Recordings/Call' }), 'Ichki xotira/Recordings/Call');
  assert.equal(recordingFolderLabel({ name: '' }), 'Ichki xotira');
});

test('setup QR version 2 adds the backup group; version 1 still works', () => {
  const token = '12345:abcdefghijklmnopqrstuvwx';
  const v2 = parseTelegramSetupCode(JSON.stringify({ type: 'smart_pos_telegram', version: 2, botToken: token,
    chatId: '-100111', statsChatId: '-100222', backupChatId: '-100333' }));
  assert.equal(v2.backupChatId, '-100333');
  const v1 = parseTelegramSetupCode(JSON.stringify({ type: 'smart_pos_telegram', version: 1, botToken: token, chatId: '-1', statsChatId: '-2' }));
  assert.ok(!('backupChatId' in v1));
  assert.throws(() => parseTelegramSetupCode(JSON.stringify({ type: 'smart_pos_telegram', version: 2, botToken: token,
    chatId: '-100111', statsChatId: '-100222', backupChatId: '-100111' })), (error) => !error.message.includes(token));
});

test('stored configuration is normalised with safe defaults', () => {
  const config = normalizeConfiguration({ targets: [{ id: 'a', name: 'A', url: 'ws://1.2.3.4:8765?token=x' }, { id: 'b', name: 'B', url: 'ws://1.2.3.5:8765?token=y', role: 'cashier' }],
    telegram: { enabled: true, chatId: '-1', folderUri: '', folderName: '', botToken: 'secret' },
    shifts: [{ index: 1, name: 'Tong', start: '25:00', end: '17:00' }], alerts: { managerAfterMinutes: 0, lostAfterMinutes: 7, smsDailyCap: 20 },
    managers: [{ id: 'm1', name: 'Aziz', schedule: { type: 'fixed', shift: 2 } }, { name: 'no id' }] });
  assert.deepEqual(config.targets.map((t) => t.role), ['operator', 'cashier']);
  assert.deepEqual(config.shifts, DEFAULT_SHIFTS);
  assert.deepEqual(config.alerts, { managerAfterMinutes: 2, lostAfterMinutes: 7, smsDailyCap: 20 });
  assert.equal(config.closedSms.enabled, false);
  assert.ok(config.closedSms.text.includes('08:00 dan 02:00 gacha'));
  assert.equal(config.managers.length, 1);
  assert.equal(config.managers[0].sms, true);
  assert.ok(!('botToken' in config.telegram));
});

test('phone and clock helpers mirror the native rules', () => {
  assert.ok(isUzbekMobile('+998 90 123 45 67') && isUzbekMobile('901234567'));
  assert.ok(!isUzbekMobile('+998 71 200 00 00') && !isUzbekMobile('+7 901 234 56 78') && !isUzbekMobile(''));
  assert.equal(formatUzPhone('998901234567'), '+998 90 123 45 67');
  assert.ok(isClock('8:05') && isClock('23:59') && !isClock('24:00') && !isClock('17:60'));
});

test('weekly manager rotation alternates shifts from Sunday', () => {
  const thursday = new Date(2026, 8, 17, 12).getTime();
  const sunday = weekStart(thursday);
  assert.equal(new Date(sunday).getDay(), 0);
  assert.equal(new Date(sunday).getDate(), 13);
  const schedule = { type: 'rotating', anchorWeekStart: sunday, anchorShift: 1 };
  const week = 7 * 86_400_000;
  assert.equal(managerShiftForWeek(schedule, sunday, DEFAULT_SHIFTS), 1);
  assert.equal(managerShiftForWeek(schedule, sunday + week, DEFAULT_SHIFTS), 2);
  assert.equal(managerShiftForWeek(schedule, sunday - week, DEFAULT_SHIFTS), 2);
  assert.equal(managerShiftForWeek(schedule, sunday + 2 * week, DEFAULT_SHIFTS), 1);
  assert.equal(managerShiftForWeek({ type: 'fixed', shift: 2 }, sunday, DEFAULT_SHIFTS), 2);
});

test('manager invite links need the bot name and the code', () => {
  assert.equal(managerInviteLink('smart_pos_operator_bot', 'abcDEF1234567890'), 'https://t.me/smart_pos_operator_bot?start=abcDEF1234567890');
  assert.equal(managerInviteLink('', 'abc'), null);
  assert.equal(managerInviteLink('bot', undefined), null);
});
