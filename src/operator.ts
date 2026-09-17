import { NativeModules, PermissionsAndroid, Platform } from 'react-native';
import type { FolderListing, OperatorConfiguration, RecordingFolder, RuntimeSnapshot } from './operator-model';
import { normalizeConfiguration } from './operator-model';

export * from './operator-model';

interface RuntimeModule {
  configure(config: string): Promise<void>;
  getConfiguration(): Promise<string>;
  getSnapshot(): Promise<string>;
  sendTest(): Promise<number>;
  checkAccess(): Promise<string>;
  requestAllFilesAccess(): Promise<void>;
  requestBatteryAccess(): Promise<void>;
  openBatterySettings(): Promise<void>;
  pickRecordingFolder(): Promise<string | null>;
  findRecordingFolders(): Promise<string>;
  listFolders(path: string | null): Promise<string>;
  checkForUpdate(): Promise<void>;
  openInstallSettings(): Promise<void>;
  pickContact(): Promise<string | null>;
}

function runtime(): RuntimeModule {
  const native = NativeModules.OperatorRuntime as RuntimeModule | undefined;
  if (Platform.OS !== 'android' || !native) {
    throw new Error('Yangi Android APKni o‘rnating. Bu imkoniyat Expo Go orqali ishlamaydi.');
  }
  return native;
}

export async function getOperatorConfiguration(): Promise<OperatorConfiguration> {
  return normalizeConfiguration(JSON.parse(await runtime().getConfiguration()) as Partial<OperatorConfiguration>);
}

export async function configureOperator(config: OperatorConfiguration): Promise<void> {
  await runtime().configure(JSON.stringify(config));
}

export async function getRuntimeSnapshot(): Promise<RuntimeSnapshot> {
  return JSON.parse(await runtime().getSnapshot()) as RuntimeSnapshot;
}

export async function sendOperatorTest(): Promise<number> {
  return runtime().sendTest();
}

export async function getRuntimeAccess(): Promise<{ allFiles: boolean; battery: boolean; installs: boolean }> {
  return JSON.parse(await runtime().checkAccess());
}

export async function requestAllFilesAccess(): Promise<void> {
  if (Platform.OS === 'android' && Number(Platform.Version) <= 29) {
    await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE!);
  } else await runtime().requestAllFilesAccess();
}
export async function requestBatteryAccess(): Promise<void> { await runtime().requestBatteryAccess(); }
export async function openBatterySettings(): Promise<void> { await runtime().openBatterySettings(); }
/** Asks the service to check GitHub now (and retry an install waiting for confirmation). */
export async function checkForUpdate(): Promise<void> { await runtime().checkForUpdate(); }
/** Android's "Install unknown apps" page for this app; needed for silent self-updates. */
export async function openInstallSettings(): Promise<void> { await runtime().openInstallSettings(); }

/** Android's contact picker (no contacts permission needed). */
export async function pickContact(): Promise<{ name: string; phone: string } | null> {
  const raw = await runtime().pickContact();
  if (!raw) return null;
  const value = JSON.parse(raw) as { name?: string; phone?: string };
  return { name: value.name ?? '', phone: value.phone ?? '' };
}

/** `access: false` means "All files access" is not granted yet. */
export async function findRecordingFolders(): Promise<{ access: boolean; folders: RecordingFolder[] }> {
  const result = JSON.parse(await runtime().findRecordingFolders()) as { access?: boolean; folders?: RecordingFolder[] };
  return { access: result.access === true, folders: result.folders ?? [] };
}

/** Lists subfolders of `path` (internal storage root when null); null without "All files access". */
export async function listFolders(path: string | null): Promise<FolderListing | null> {
  const result = JSON.parse(await runtime().listFolders(path)) as FolderListing & { access?: boolean };
  return result.access ? result : null;
}

/** Audio count for a saved `file://` folder; null for Android-chooser folders. */
export async function describeRecordingFolder(uri: string): Promise<RecordingFolder | null> {
  if (!uri.startsWith('file://')) return null;
  let path: string;
  try { path = decodeURIComponent(uri.slice('file://'.length)); } catch { return null; }
  return listFolders(path);
}

/** Android's system folder chooser; a fallback to the in-app folder browser. */
export async function pickRecordingFolder(): Promise<{ uri: string; name: string } | null> {
  const uri = await runtime().pickRecordingFolder();
  if (!uri) return null;
  let readable = uri;
  try { readable = decodeURIComponent(uri); } catch { /* Keep provider URI. */ }
  const name = readable.split('/').pop()?.split(':').pop() || 'Ovoz yozuvlari';
  return { uri, name };
}
