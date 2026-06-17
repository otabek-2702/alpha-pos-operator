/**
 * Type declarations for `react-native-call-detection` (the package ships no
 * types). Signature verified against v1.9.x.
 *
 * The constructor wires a native phone-state listener and invokes `handler`
 * with the platform event name and (on Android, when `readPhoneNumberAndroid`
 * is true) the raw phone number reported by the OS.
 *
 * Android emits: 'Incoming' | 'Offhook' | 'Disconnected' | 'Missed'.
 * iOS emits:     'Connected' | 'Dialing' | 'Incoming' | 'Disconnected'.
 */
declare module 'react-native-call-detection' {
  export type CallEvent =
    | 'Connected'
    | 'Dialing'
    | 'Incoming'
    | 'Offhook'
    | 'Disconnected'
    | 'Missed';

  export interface PermissionDialogConfig {
    title: string;
    message: string;
  }

  export default class CallDetectorManager {
    constructor(
      handler: (event: CallEvent, phoneNumber?: string | null) => void,
      readPhoneNumberAndroid?: boolean,
      permissionDeniedCallback?: () => void,
      permissionDialog?: PermissionDialogConfig
    );

    /** Tears down the native listener. Always call this on unmount. */
    dispose(): void;
  }
}
