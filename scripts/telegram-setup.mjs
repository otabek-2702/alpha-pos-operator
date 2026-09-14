/**
 * Local Telegram provisioning helper. Credentials never appear in log output.
 * Bot/group creation is performed in the owner's Telegram account first.
 * API reference: https://core.telegram.org/bots/api
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const envPath = path.join(root, '.env.telegram');
const titles = {
  recordings: 'Smart Food ovoz yozuvlari',
  stats: "Smart Food qo'ng'iroqlar ma'lumotlari",
};

function parseEnv(text) {
  return Object.fromEntries(text.split(/\r?\n/).flatMap((line) => {
    const match = /^([A-Z_]+)=(.*)$/.exec(line.trim());
    return match ? [[match[1], match[2].trim().replace(/^(['"])(.*)\1$/, '$2')]] : [];
  }));
}

function safeError(message) {
  return String(message).replace(/\b\d{5,}:[A-Za-z0-9_-]{20,}\b/g, '[protected token]');
}

async function run() {
  const command = process.argv[2] ?? 'help';
  if (command === 'help') {
    console.log('Usage: node scripts/telegram-setup.mjs inspect|brand-bot|brand-groups|test|setup-qr');
    console.log('inspect: verify bot and two exact groups; save discovered IDs locally. No messages sent.');
    console.log('brand-bot: apply the mobile app icon and Uzbek bot description.');
    console.log('brand-groups: apply approved group images (bot must be group administrator).');
    console.log('test: send labelled setup messages and a tiny synthetic silent WAV document.');
    console.log('setup-qr: save a private phone provisioning QR, without printing its contents.');
    return;
  }
  if (!['inspect', 'brand-bot', 'brand-groups', 'test', 'setup-qr'].includes(command)) throw new Error('Unknown command. Use help.');

  let envText;
  try { envText = await readFile(envPath, 'utf8'); }
  catch { throw new Error('Create local .env.telegram from .env.telegram.example and store the BotFather token there.'); }
  const settings = parseEnv(envText);
  const token = settings.TELEGRAM_BOT_TOKEN;
  if (!token || !/^\d+:[A-Za-z0-9_-]{20,}$/.test(token)) throw new Error('Valid TELEGRAM_BOT_TOKEN is missing from .env.telegram.');

  const api = async (method, payload = {}) => {
    let response;
    try {
      response = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
        method: 'POST',
        ...(payload instanceof FormData ? { body: payload } : { headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload) }),
        signal: AbortSignal.timeout(30000),
      });
    } catch { throw new Error(`${method}: Telegram could not be reached. Check the internet connection.`); }
    let result;
    try { result = await response.json(); }
    catch { throw new Error(`${method}: Telegram returned an unreadable response (HTTP ${response.status}).`); }
    if (!result.ok) {
      const error = new Error(`${method}: ${safeError(result.description || `HTTP ${response.status}`)}`);
      error.migrateToChatId = result.parameters?.migrate_to_chat_id;
      throw error;
    }
    return result.result;
  };

  const bot = await api('getMe');
  if (command !== 'setup-qr') console.log(`Bot verified: ${bot.first_name} (@${bot.username}).`);

  const saveSetting = async (key, value) => {
    const line = `${key}=${value}`;
    envText = new RegExp(`^${key}=.*$`, 'm').test(envText)
      ? envText.replace(new RegExp(`^${key}=.*$`, 'm'), line)
      : `${envText.trimEnd()}\n${line}\n`;
    settings[key] = String(value);
    await writeFile(envPath, envText, { encoding: 'utf8', mode: 0o600 });
  };

  const verifyGroup = async (key, expectedTitle, depth = 0) => {
    const id = settings[key];
    if (!id || !/^-\d+$/.test(id)) throw new Error(`${key} missing. Create the group with the bot and run inspect.`);
    try {
    const chat = await api('getChat', { chat_id: id });
    if (chat.title !== expectedTitle || !['group', 'supergroup'].includes(chat.type)) throw new Error(`${key} does not identify the expected group. No message or photo was sent.`);
    const [count, member] = await Promise.all([
      api('getChatMemberCount', { chat_id: id }),
      api('getChatMember', { chat_id: id, user_id: bot.id }),
    ]);
    if (count !== 2) throw new Error(`${expectedTitle}: expected only owner + bot, found ${count} members. No message or photo was sent.`);
    if (['left', 'kicked'].includes(member.status)) throw new Error(`${expectedTitle}: bot is not a member.`);
    if (member.status === 'restricted' && member.can_send_messages === false) throw new Error(`${expectedTitle}: bot has no permission to send messages.`);
    return { id, chat, member };
    } catch (error) {
      const migratedId = String(error.migrateToChatId ?? '');
      if (depth < 2 && /^-\d+$/.test(migratedId) && migratedId !== id) {
        await saveSetting(key, migratedId);
        return verifyGroup(key, expectedTitle, depth + 1);
      }
      throw error;
    }
  };

  if (command === 'inspect') {
    const webhook = await api('getWebhookInfo');
    if (webhook.url) throw new Error('The bot already uses a webhook. No configuration was changed; review this bot before provisioning.');
    // No offset: inspect without acknowledging or deleting existing updates.
    const updates = await api('getUpdates', { timeout: 0, limit: 100 });
    const seen = new Map();
    for (const update of updates) {
      const chat = update.my_chat_member?.chat ?? update.message?.chat;
      if (chat && ['group', 'supergroup'].includes(chat.type) && Object.values(titles).includes(chat.title)) seen.set(String(chat.id), chat);
    }
    for (const [kind, title] of Object.entries(titles)) {
      const key = kind === 'recordings' ? 'TELEGRAM_RECORDINGS_CHAT_ID' : 'TELEGRAM_STATS_CHAT_ID';
      const matches = [...seen.values()].filter((chat) => chat.title === title);
      if (matches.length > 1 && !settings[key]) throw new Error(`Multiple groups named ${title}; select the correct ID locally before continuing.`);
      if (!settings[key] && matches.length === 1) await saveSetting(key, String(matches[0].id));
      if (!settings[key]) { console.log(`${title}: not found yet; add this bot to the group, then run inspect again.`); continue; }
      const group = await verifyGroup(key, title);
      console.log(`${title}: verified owner + bot, chat ID ${group.id}, bot ${group.member.status}.`);
    }
    return;
  }

  if (command === 'brand-bot') {
    const file = await readFile(path.join(root, 'assets', 'telegram', 'bot-profile.jpg'));
    const body = new FormData();
    body.set('photo', JSON.stringify({ type: 'static', photo: 'attach://avatar' }));
    body.set('avatar', new Blob([file], { type: 'image/jpeg' }), 'bot-profile.jpg');
    await api('setMyProfilePhoto', body);
    await api('setMyName', { name: 'Smart POS Operator' });
    await api('setMyDescription', { description: 'Smart POS Operator telefon qo‘ng‘iroqlari haqidagi ma’lumotlarni va yangi ovoz yozuvlarini belgilangan Smart Food guruhlariga yuboradi.' });
    await api('setMyShortDescription', { short_description: 'Smart Food uchun qo‘ng‘iroqlar hisoboti va yangi ovoz yozuvlari.' });
    console.log('Bot profile image and Uzbek descriptions updated.');
    return;
  }

  // Validate both destinations before making either change or sending tests.
  const recordings = await verifyGroup('TELEGRAM_RECORDINGS_CHAT_ID', titles.recordings);
  const stats = await verifyGroup('TELEGRAM_STATS_CHAT_ID', titles.stats);
  if (recordings.id === stats.id) throw new Error('Audio recordings and call statistics must use separate groups.');

  if (command === 'setup-qr') {
    const require = createRequire(import.meta.url);
    let qrcode;
    try { qrcode = require('qrcode'); }
    catch {
      try { qrcode = require(path.join(root, '..', 'smart-pos-main', 'node_modules', 'qrcode')); }
      catch { throw new Error('QR generator missing. Install the desktop project dependencies or add qrcode locally.'); }
    }
    const privateDirectory = path.join(root, '.private');
    await mkdir(privateDirectory, { recursive: true, mode: 0o700 });
    const destination = path.join(privateDirectory, 'telegram-setup.png');
    const payload = { type: 'smart_pos_telegram', version: 1, botToken: token, chatId: recordings.id, statsChatId: stats.id };
    await qrcode.toFile(destination, JSON.stringify(payload), { width: 800, margin: 4, errorCorrectionLevel: 'M' });
    console.log(`Private phone setup QR saved: ${destination}`);
    return;
  }

  if (command === 'brand-groups') {
    for (const group of [recordings, stats]) {
      if (group.member.status !== 'administrator' || group.member.can_change_info === false) throw new Error('Bot needs administrator permission to change group images. Set the photos in Telegram or grant only the needed permission.');
    }
    const images = await Promise.all(['recordings-profile.jpg', 'stats-profile.jpg'].map((name) => readFile(path.join(root, 'assets', 'telegram', name))));
    for (const [index, group] of [recordings, stats].entries()) {
      const body = new FormData();
      body.set('chat_id', group.id);
      body.set('photo', new Blob([images[index]], { type: 'image/jpeg' }), 'group-profile.jpg');
      await api('setChatPhoto', body);
      console.log(`${group.chat.title}: profile image updated.`);
    }
    return;
  }

  const recordingMessage = await api('sendMessage', { chat_id: recordings.id, text: '✅ SOZLASH SINOVI\nSmart POS Operator ovoz yozuvlari guruhi tayyor. Faqat telefon ilovasi sozlangandan keyingi yangi audio yozuvlar yuboriladi. Bu xabar haqiqiy qo‘ng‘iroq emas.', disable_notification: true });
  console.log(`Recording-group setup test sent (message ${recordingMessage.message_id}).`);

  // A 250 ms mono PCM WAV containing only silence; no real phone recording.
  const samples = 2000;
  const wav = Buffer.alloc(44 + samples * 2);
  wav.write('RIFF', 0); wav.writeUInt32LE(wav.length - 8, 4); wav.write('WAVE', 8);
  wav.write('fmt ', 12); wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(1, 22); wav.writeUInt32LE(8000, 24); wav.writeUInt32LE(16000, 28);
  wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36);
  wav.writeUInt32LE(samples * 2, 40);
  const documentBody = new FormData();
  documentBody.set('chat_id', recordings.id);
  documentBody.set('document', new Blob([wav], { type: 'audio/wav' }), 'SOZLASH_SINOVI.wav');
  documentBody.set('caption', '🧪 SOZLASH SINOVI — sun’iy sokin audio fayli. Haqiqiy qo‘ng‘iroq yozuvi emas.');
  documentBody.set('disable_notification', 'true');
  const audioDocument = await api('sendDocument', documentBody);
  if (!audioDocument.document || audioDocument.document.file_size !== wav.length) throw new Error('Telegram did not confirm the expected synthetic audio document.');
  console.log(`Synthetic audio document confirmed (${wav.length} bytes, message ${audioDocument.message_id}).`);

  const statsMessage = await api('sendMessage', { chat_id: stats.id, text: '✅ SOZLASH SINOVI\nSmart POS Operator qo‘ng‘iroqlar hisoboti guruhi tayyor. Qo‘ng‘iroq tafsilotlari va javobsiz qo‘ng‘iroqlar shu yerga yuboriladi. Bu xabar haqiqiy qo‘ng‘iroq emas.', disable_notification: true });
  console.log(`Statistics-group setup test sent (message ${statsMessage.message_id}).`);
  const receiptDirectory = path.join(root, '.private');
  await mkdir(receiptDirectory, { recursive: true, mode: 0o700 });
  await writeFile(path.join(receiptDirectory, 'telegram-test-results.json'), JSON.stringify({
    verifiedAt: new Date().toISOString(), botUsername: bot.username,
    recordings: { chatId: recordings.id, title: titles.recordings, members: 2, setupMessageId: recordingMessage.message_id, audioMessageId: audioDocument.message_id, fileName: 'SOZLASH_SINOVI.wav', fileBytes: wav.length },
    stats: { chatId: stats.id, title: titles.stats, members: 2, setupMessageId: statsMessage.message_id },
  }, null, 2), { encoding: 'utf8', mode: 0o600 });
}

run().catch((error) => { console.error(safeError(error.message)); process.exitCode = 1; });
