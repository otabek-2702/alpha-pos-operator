import { NativeModules, PermissionsAndroid, Platform } from 'react-native';
import type { OperatorConfiguration, RuntimeSnapshot } from './operator-model';
import { EMPTY_TELEGRAM, safeTelegramSettings } from './operator-model';

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
}

function runtime(): RuntimeModule {
  const native = NativeModules.OperatorRuntime as RuntimeModule | undefined;
  if (Platform.OS !== 'android' || !native) {
    throw new Error('Yangi Android APKni o‘rnating. Bu imkoniyat Expo Go orqali ishlamaydi.');
  }
  return native;
}

export async function getOperatorConfiguration(): Promise<OperatorConfiguration> {
  const config = JSON.parse(await runtime().getConfiguration()) as OperatorConfiguration;
  return { targets: config.targets ?? [], telegram: safeTelegramSettings(config.telegram ?? EMPTY_TELEGRAM) };
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

export async function getRuntimeAccess(): Promise<{ allFiles: boolean; battery: boolean }> {
  return JSON.parse(await runtime().checkAccess());
}

export async function requestAllFilesAccess(): Promise<void> {
  if (Platform.OS === 'android' && Number(Platform.Version) <= 29) {
    await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE!);
  } else await runtime().requestAllFilesAccess();
}
export async function requestBatteryAccess(): Promise<void> { await runtime().requestBatteryAccess(); }
export async function openBatterySettings(): Promise<void> { await runtime().openBatterySettings(); }

export async function pickRecordingFolder(): Promise<{ uri: string; name: string } | null> {
  const uri = await runtime().pickRecordingFolder();
  if (!uri) return null;
  let readable = uri;
  try { readable = decodeURIComponent(uri); } catch { /* Keep provider URI. */ }
  const name = readable.split('/').pop()?.split(':').pop() || 'Ovoz yozuvlari';
  return { uri, name };
}
