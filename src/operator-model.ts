/** Shared, token-safe view models for the native operator service. */
export interface SavedPos {
  id: string;
  name: string;
  url: string;
  discoveryPort?: number;
}

export interface TelegramSettings {
  enabled: boolean;
  /** Only present while submitting a changed token. Native storage encrypts it. */
  botToken?: string;
  chatId: string;
  folderUri: string;
  folderName: string;
  sendMissedCalls?: false;
  statsChatId?: string;
  sendCallStats?: boolean;
}

export interface OperatorConfiguration {
  targets: SavedPos[];
  telegram: TelegramSettings;
}

export interface RuntimePeriod {
  startedAt: number;
  endedAt: number | null;
  endReason?: string;
  approximate?: boolean;
}

export interface RuntimeSnapshot {
  running: boolean;
  startedAt: number | null;
  lastHeartbeatAt: number | null;
  lastError?: string;
  phonePermission?: boolean;
  targets: (SavedPos & {
    status: 'connected' | 'connecting' | 'disconnected';
    lastConnectedAt?: number;
    error?: string;
  })[];
  periods: RuntimePeriod[];
  telegram: {
    enabled: boolean;
    configured: boolean;
    hasToken?: boolean;
    statsChatId?: string;
    sendCallStats?: boolean;
    statsPending?: number;
    statsError?: string;
    statsLastSentAt?: number;
    folderUri: string;
    folderName: string;
    pending: number;
    sent: number;
    lastSentAt?: number;
    lastError?: string;
  };
}

export const EMPTY_TELEGRAM: TelegramSettings = {
  enabled: false, chatId: '', folderUri: '', folderName: '', sendMissedCalls: false,
};

const INVALID_QR = 'Bu POS ulanish kodi emas. POSdagi operator tugmasini bosib turing va QR kodni skanerlang.';

/** Validate without the incomplete URL implementation on older React Native. */
export function inspectPosUrl(value: string): { host: string; token: string } {
  const match = /^(wss?):\/\/(\[[0-9a-f:]+\]|[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)(?::([0-9]{1,5}))?(\/[^?#\s]*)?\?([^#\s]+)$/i.exec(value);
  if (!match || value.length > 2048) throw new Error(INVALID_QR);
  if (match[3] && (Number(match[3]) < 1 || Number(match[3]) > 65535)) throw new Error(INVALID_QR);
  const parts = match[5]!.split('&');
  const tokenParts = parts.filter((part) => part.split('=')[0] === 'token');
  if (tokenParts.length !== 1) throw new Error(INVALID_QR);
  let token: string;
  try { token = decodeURIComponent(tokenParts[0]!.slice(6)); } catch { throw new Error(INVALID_QR); }
  if (!/^[A-Za-z0-9._~-]{1,256}$/.test(token)) throw new Error(INVALID_QR);
  return { host: match[2]!, token };
}

/** Legacy IDs are opaque: never show a pairing token as an identifier. */
function legacyId(value: string): string {
  let a = 2166136261;
  let b = 5381;
  for (let i = 0; i < value.length; i += 1) {
    a = Math.imul(a ^ value.charCodeAt(i), 16777619);
    b = Math.imul(b, 33) ^ value.charCodeAt(i);
  }
  return `legacy-${(a >>> 0).toString(16)}${(b >>> 0).toString(16)}`;
}

export function parsePairingCode(scanned: string): SavedPos {
  const raw = scanned.trim();
  if (raw.length > 4096) throw new Error(INVALID_QR);
  if (/^wss?:\/\//i.test(raw)) {
    const { host, token } = inspectPosUrl(raw);
    return { id: legacyId(token), name: `POS · ${host}`, url: raw };
  }
  let value: Record<string, unknown>;
  try { value = JSON.parse(raw); } catch { throw new Error(INVALID_QR); }
  if (!value || value.version !== 2 || typeof value.id !== 'string' ||
      !/^[A-Za-z0-9_-]{1,128}$/.test(value.id) || typeof value.name !== 'string' ||
      !value.name.trim() || value.name.length > 128 || typeof value.url !== 'string') {
    throw new Error(INVALID_QR);
  }
  inspectPosUrl(value.url);
  if (value.discoveryPort !== undefined &&
      (!Number.isInteger(value.discoveryPort) || Number(value.discoveryPort) < 1 || Number(value.discoveryPort) > 65535)) {
    throw new Error(INVALID_QR);
  }
  return {
    id: value.id, name: value.name.trim(), url: value.url,
    ...(value.discoveryPort === undefined ? {} : { discoveryPort: Number(value.discoveryPort) }),
  };
}

/** Rescanning updates a POS instead of making a second recipient. */
export function upsertPos(targets: SavedPos[], next: SavedPos): SavedPos[] {
  const token = inspectPosUrl(next.url).token;
  const index = targets.findIndex((target) =>
    target.id === next.id || inspectPosUrl(target.url).token === token);
  if (index < 0) return [...targets, next];
  return targets.map((target, i) => i === index ? next : target);
}

export function safeTelegramSettings(settings: TelegramSettings): TelegramSettings {
  return {
    enabled: settings.enabled === true,
    chatId: settings.chatId.trim(),
    folderUri: settings.folderUri,
    folderName: settings.folderName,
    sendMissedCalls: false,
    statsChatId: (settings.statsChatId ?? '').trim(),
    sendCallStats: settings.sendCallStats === true,
  };
}

/** Private provisioning QR. Credentials go straight to encrypted native storage. */
export function parseTelegramSetupCode(scanned: string): Pick<TelegramSettings, 'botToken' | 'chatId' | 'statsChatId'> {
  const invalid = 'Telegram sozlash QR kodi noto‘g‘ri.';
  let value: Record<string, unknown>;
  try { value = JSON.parse(scanned); } catch { throw new Error(invalid); }
  if (!value || value.type !== 'smart_pos_telegram' || value.version !== 1 ||
      typeof value.botToken !== 'string' || !/^[0-9]{5,}:[A-Za-z0-9_-]{20,}$/.test(value.botToken) ||
      typeof value.chatId !== 'string' || !/^-[0-9]+$/.test(value.chatId) ||
      typeof value.statsChatId !== 'string' || !/^-[0-9]+$/.test(value.statsChatId) || value.chatId === value.statsChatId) {
    throw new Error(invalid);
  }
  return { botToken: value.botToken, chatId: value.chatId, statsChatId: value.statsChatId };
}
