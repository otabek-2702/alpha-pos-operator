import { useEffect, useRef, useState } from 'react';
import { PermissionsAndroid, Platform } from 'react-native';
import CallDetectorManager, { type CallEvent } from 'react-native-call-detection';

import type { OutboundMessage } from './useWebSocket';

/**
 * Bridges native phone-call events to the wire protocol.
 *
 * Android reports states without an explicit direction, so direction is
 * inferred by tracking the event sequence:
 *
 *   - "Incoming" (with number)   -> ring; emit call_start direction "in".
 *   - "Offhook"  (with number)   -> if preceded by "Incoming" it is that call
 *                                   being answered (emit nothing); otherwise it
 *                                   is an OUTGOING call -> emit call_start "out".
 *   - "Disconnected" / "Missed"  -> emit call_end and reset tracking flags.
 *
 * `readPhoneNumberAndroid` is enabled so the OS supplies the number. On
 * Android 9+ this requires BOTH READ_PHONE_STATE and READ_CALL_LOG — without
 * READ_CALL_LOG the number arrives empty.
 */

export interface CallLogEntry {
  id: string;
  at: number;
  type: OutboundMessage['type'];
  phone: string;
  direction?: 'in' | 'out';
}

/** Keep only the most recent N events for the live status log. */
const MAX_LOG = 10;

async function requestCallPermissions(): Promise<boolean> {
  if (Platform.OS !== 'android') return false;
  // These constants are always defined on Android at runtime; the RN typings
  // mark them optional, so assert their presence.
  const READ_PHONE_STATE = PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE!;
  const READ_CALL_LOG = PermissionsAndroid.PERMISSIONS.READ_CALL_LOG!;
  try {
    const result = await PermissionsAndroid.requestMultiple([
      READ_PHONE_STATE,
      READ_CALL_LOG,
    ]);
    return (
      result[READ_PHONE_STATE] === PermissionsAndroid.RESULTS.GRANTED &&
      result[READ_CALL_LOG] === PermissionsAndroid.RESULTS.GRANTED
    );
  } catch {
    return false;
  }
}

export interface UseCallBridgeResult {
  /** Last ~10 events emitted to the desktop, newest first. */
  log: CallLogEntry[];
  /** null = not yet resolved, true = granted, false = denied / unsupported. */
  permissionGranted: boolean | null;
}

export function useCallBridge(
  send: (message: OutboundMessage) => boolean
): UseCallBridgeResult {
  const [log, setLog] = useState<CallLogEntry[]>([]);
  const [permissionGranted, setPermissionGranted] = useState<boolean | null>(null);

  // Always call through the latest `send` even though the detector is created
  // once. (`send` from useWebSocket changes identity when the URL changes.)
  const sendRef = useRef(send);
  sendRef.current = send;

  // Sequence-tracking state for direction inference. Refs, not state, because
  // the native callback must read/write them synchronously without re-renders.
  const incomingInProgress = useRef(false);
  const outgoingInProgress = useRef(false);
  const activePhone = useRef('');
  const logSeq = useRef(0);

  useEffect(() => {
    // Call detection is Android-only. On other platforms the bridge is inert.
    if (Platform.OS !== 'android') {
      setPermissionGranted(false);
      return;
    }

    let detector: CallDetectorManager | null = null;
    let disposed = false;

    const pushLog = (entry: CallLogEntry) =>
      setLog((prev) => [entry, ...prev].slice(0, MAX_LOG));

    const emit = (message: OutboundMessage) => {
      sendRef.current(message);
      pushLog({
        id: `${Date.now()}-${logSeq.current++}`,
        at: Date.now(),
        type: message.type,
        phone: message.phone,
        direction: message.type === 'call_start' ? message.direction : undefined,
      });
    };

    const handleCall = (event: CallEvent, rawNumber?: string | null) => {
      const phone = (rawNumber ?? '').toString().trim();

      switch (event) {
        case 'Incoming': {
          // Incoming ring. The number is required to be useful to the POS.
          if (!phone) break;
          incomingInProgress.current = true;
          outgoingInProgress.current = false;
          activePhone.current = phone;
          emit({ type: 'call_start', phone, direction: 'in' });
          break;
        }

        case 'Offhook': {
          if (incomingInProgress.current) {
            // The earlier incoming call was answered — already announced.
            if (phone) activePhone.current = phone;
          } else {
            // No preceding ring => the operator placed an outgoing call.
            outgoingInProgress.current = true;
            const outPhone = phone || activePhone.current;
            activePhone.current = outPhone;
            emit({ type: 'call_start', phone: outPhone, direction: 'out' });
          }
          break;
        }

        case 'Disconnected':
        case 'Missed': {
          // End the call only if one was actually being tracked.
          if (
            incomingInProgress.current ||
            outgoingInProgress.current ||
            activePhone.current
          ) {
            emit({ type: 'call_end', phone: phone || activePhone.current });
          }
          incomingInProgress.current = false;
          outgoingInProgress.current = false;
          activePhone.current = '';
          break;
        }

        // 'Connected' / 'Dialing' are iOS-only and never reach Android.
        default:
          break;
      }
    };

    (async () => {
      const granted = await requestCallPermissions();
      if (disposed) return;
      setPermissionGranted(granted);
      if (!granted) return;

      detector = new CallDetectorManager(
        handleCall,
        true, // readPhoneNumberAndroid — supply the number on Android
        () => {
          // Native-side permission denial.
          setPermissionGranted(false);
        },
        {
          title: 'Phone access required',
          message:
            'AlphaPOS Operator Link needs phone state and call-log access to ' +
            'read the caller number and send it to the POS desktop.',
        }
      );
    })();

    return () => {
      disposed = true;
      if (detector) {
        try {
          detector.dispose();
        } catch {
          // Ignore disposal errors during teardown.
        }
        detector = null;
      }
    };
  }, []);

  return { log, permissionGranted };
}
