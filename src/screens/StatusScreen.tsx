import { useEffect, useState } from 'react';
import { ScrollView, Text, TouchableOpacity, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import * as Clipboard from 'expo-clipboard';

import { Blink, ExpandingRing, Pulse, Spin } from '../components/anim';
import {
  ArrowIncoming,
  ArrowOutgoing,
  CallEnded,
  CheckRing,
  Clock,
  Copy,
  Database,
  DotsVertical,
  Globe,
  PhoneFill,
  PhoneOff,
  Send,
  SpinnerArc,
  Warning,
} from '../components/Icons';
import { Button, Label, Screen } from '../components/ui';
import type { ActiveCall, CallLogEntry } from '../hooks/useCallBridge';
import type { WsStatus } from '../hooks/useWebSocket';
import { LANG_SHORT, useT } from '../i18n';
import { colors, fonts, radius, space, tint } from '../theme';

export interface StatusScreenProps {
  url: string;
  status: WsStatus;
  log: CallLogEntry[];
  permissionGranted: boolean | null;
  queuedCount: number;
  activeCall: ActiveCall | null;
  onRepair: () => void;
  onSendTest: () => void;
  onFixPermission: () => void;
}

function pad(n: number) {
  return n.toString().padStart(2, '0');
}
function formatTime(at: number) {
  const d = new Date(at);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Live ticking elapsed seconds since `startedAt` (or null). */
function useElapsed(startedAt: number | null): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (startedAt == null) return;
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [startedAt]);
  if (startedAt == null) return 0;
  return Math.max(0, Math.floor((now - startedAt) / 1000));
}

export function StatusScreen(props: StatusScreenProps) {
  const { t, lang, cycleLang } = useT();
  const { url, status, log, permissionGranted, queuedCount, activeCall } = props;
  const connected = status === 'connected';
  const revoked = permissionGranted === false;

  return (
    <Screen>
      <Header lang={lang} onCycleLang={cycleLang} />

      <View style={{ paddingHorizontal: space.lg }}>
        {revoked ? <RevokedBanner /> : null}

        {activeCall ? (
          <ActiveCallBanner call={activeCall} connected={connected} />
        ) : revoked ? (
          <CompactConnected url={url} status={status} />
        ) : (
          <ConnectionCard url={url} status={status} queuedCount={queuedCount} />
        )}

        {revoked ? (
          <DetectionRow stopped onFix={props.onFixPermission} />
        ) : (
          <DetectionRow stopped={false} />
        )}
      </View>

      <EventList log={log} status={status} queuedCount={queuedCount} />

      <Footer
        connected={connected}
        onSendTest={props.onSendTest}
        onRepair={props.onRepair}
      />
    </Screen>
  );
}

/* ---------- Header ---------- */

function Header({ lang, onCycleLang }: { lang: 'uz' | 'ru' | 'en'; onCycleLang: () => void }) {
  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: space.lg,
        paddingTop: 6,
        paddingBottom: 10,
      }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 9 }}>
        <LinearGradient
          colors={[colors.brand, colors.brandDark]}
          start={{ x: 0, y: 0 }}
          end={{ x: 1, y: 1 }}
          style={{ width: 28, height: 28, borderRadius: 8, alignItems: 'center', justifyContent: 'center' }}
        >
          <PhoneFill size={15} />
        </LinearGradient>
        <Text style={{ color: colors.text, fontFamily: fonts.bold, fontSize: 15 }}>Operator Link</Text>
      </View>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <TouchableOpacity
          onPress={onCycleLang}
          activeOpacity={0.8}
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            gap: 4,
            height: 26,
            paddingHorizontal: 9,
            borderRadius: radius.pill,
            backgroundColor: colors.raised,
            borderWidth: 1,
            borderColor: colors.border,
          }}
        >
          <Globe size={12} />
          <Text style={{ color: colors.muted, fontFamily: fonts.semibold, fontSize: 11 }}>
            {LANG_SHORT[lang]}
          </Text>
        </TouchableOpacity>
        <DotsVertical size={22} />
      </View>
    </View>
  );
}

/* ---------- Revoked banner ---------- */

function RevokedBanner() {
  const { t } = useT();
  return (
    <View
      style={{
        flexDirection: 'row',
        gap: 11,
        padding: 13,
        borderRadius: 14,
        backgroundColor: tint.dangerBg,
        borderWidth: 1,
        borderColor: 'rgba(239,106,91,0.42)',
        marginBottom: 11,
      }}
    >
      <Warning size={22} color={colors.danger} />
      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={{ color: colors.danger, fontFamily: fonts.bold, fontSize: 14 }}>
          {t('status.revoked.title')}
        </Text>
        <Text style={{ color: '#e89aa2', fontFamily: fonts.regular, fontSize: 11.5, lineHeight: 17, marginTop: 3 }}>
          {t('status.revoked.desc')}
        </Text>
      </View>
    </View>
  );
}

/* ---------- Connection card ---------- */

const META: Record<
  WsStatus,
  { color: string; bg: string; border: string; labelKey: string }
> = {
  connected: { color: colors.connected, bg: tint.connectedBg, border: tint.connectedBorder, labelKey: 'status.connected' },
  connecting: { color: colors.warn, bg: tint.warnBg, border: tint.warnBorder, labelKey: 'status.connecting' },
  reconnecting: { color: colors.warn, bg: tint.warnBg, border: tint.warnBorder, labelKey: 'status.reconnecting' },
  disconnected: { color: colors.danger, bg: tint.dangerBg, border: tint.dangerBorder, labelKey: 'status.disconnected' },
};

function ConnectionCard({
  url,
  status,
  queuedCount,
}: {
  url: string;
  status: WsStatus;
  queuedCount: number;
}) {
  const { t } = useT();
  const m = META[status];
  const showBuffer = status !== 'connected' && queuedCount > 0;

  return (
    <LinearGradient
      colors={[m.bg, colors.inset]}
      start={{ x: 0, y: 0 }}
      end={{ x: 0.6, y: 1 }}
      style={{ borderRadius: radius.lg, borderWidth: 1, borderColor: m.border, padding: 16 }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 14 }}>
        <View style={{ width: 50, height: 50, alignItems: 'center', justifyContent: 'center' }}>
          {status === 'connected' ? (
            <>
              <ExpandingRing color="rgba(54,192,126,0.5)" />
              <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, borderRadius: 999, backgroundColor: tint.connectedBg }} />
              <CheckRing size={24} />
            </>
          ) : status === 'disconnected' ? (
            <>
              <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, borderRadius: 999, backgroundColor: 'rgba(239,106,91,0.14)' }} />
              <PhoneOff size={26} />
            </>
          ) : (
            <>
              <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, borderRadius: 999, backgroundColor: 'rgba(229,165,59,0.13)' }} />
              <Spin>
                <SpinnerArc size={34} />
              </Spin>
            </>
          )}
        </View>
        <View style={{ flex: 1, minWidth: 0 }}>
          <Label color={m.color} style={{ fontFamily: fonts.monoSemibold, fontSize: 10 }}>
            {t('status.label')}
          </Label>
          <Text style={{ color: m.color, fontFamily: fonts.bold, fontSize: 24, marginTop: 2 }}>
            {t(m.labelKey as never)}
          </Text>
        </View>
      </View>

      <View style={{ height: 1, backgroundColor: m.border, marginVertical: 12 }} />

      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}>
        <View style={{ flex: 1, minWidth: 0 }}>
          <Label color={colors.muted2} style={{ fontFamily: fonts.monoSemibold, fontSize: 9 }}>
            {t('status.pos')}
          </Label>
          <Text
            numberOfLines={1}
            style={{
              fontFamily: fonts.monoMedium,
              fontSize: 13,
              marginTop: 3,
              color: status === 'disconnected' ? colors.faint : colors.textSoft,
              textDecorationLine: status === 'disconnected' ? 'line-through' : 'none',
            }}
          >
            {url}
          </Text>
        </View>
        <CopyButton value={url} />
      </View>

      {showBuffer ? (
        <View
          style={{
            marginTop: 11,
            flexDirection: 'row',
            alignItems: 'center',
            gap: 9,
            paddingHorizontal: 11,
            paddingVertical: 9,
            borderRadius: 10,
            backgroundColor: tint.warnSoft,
            borderWidth: 1,
            borderColor: 'rgba(229,165,59,0.3)',
          }}
        >
          <Database size={18} />
          <Text style={{ flex: 1, color: '#e9cf94', fontFamily: fonts.regular, fontSize: 12 }}>
            {t('status.buffered')}
          </Text>
          <Text
            style={{
              fontFamily: fonts.monoBold,
              fontSize: 13,
              color: colors.warn,
              backgroundColor: 'rgba(229,165,59,0.16)',
              borderRadius: 7,
              paddingHorizontal: 9,
              paddingVertical: 2,
              overflow: 'hidden',
            }}
          >
            {queuedCount}
          </Text>
        </View>
      ) : null}
    </LinearGradient>
  );
}

function CopyButton({ value }: { value: string }) {
  return (
    <TouchableOpacity
      onPress={() => Clipboard.setStringAsync(value)}
      activeOpacity={0.7}
      style={{
        width: 34,
        height: 34,
        borderRadius: 9,
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.border,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Copy size={15} />
    </TouchableOpacity>
  );
}

/* ---------- Active call banner ---------- */

function ActiveCallBanner({ call, connected }: { call: ActiveCall; connected: boolean }) {
  const { t } = useT();
  const secs = useElapsed(call.startedAt);
  const timer = `${pad(Math.floor(secs / 60))}:${pad(secs % 60)}`;
  const Arrow = call.direction === 'in' ? ArrowIncoming : ArrowOutgoing;

  return (
    <LinearGradient
      colors={['rgba(110,139,255,0.22)', 'rgba(110,139,255,0.04)']}
      start={{ x: 0, y: 0 }}
      end={{ x: 1, y: 1 }}
      style={{ borderRadius: radius.xl, borderWidth: 1.5, borderColor: 'rgba(110,139,255,0.6)', padding: 18 }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 9 }}>
        <Blink>
          <View style={{ width: 9, height: 9, borderRadius: 5, backgroundColor: colors.brand }} />
        </Blink>
        <Text style={{ color: colors.brand, fontFamily: fonts.monoBold, fontSize: 11, letterSpacing: 1.2 }}>
          {(call.direction === 'in' ? t('call.incoming.live') : t('call.outgoing.live')).toUpperCase()}
        </Text>
      </View>

      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 13, marginTop: 14 }}>
        <View style={{ width: 54, height: 54, alignItems: 'center', justifyContent: 'center' }}>
          <ExpandingRing color="rgba(110,139,255,0.5)" duration={1800} />
          <ExpandingRing color="rgba(110,139,255,0.5)" duration={1800} delay={900} />
          <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, borderRadius: 999, backgroundColor: 'rgba(110,139,255,0.16)' }} />
          <Arrow size={26} color={colors.brand} />
        </View>
        <View style={{ flex: 1, minWidth: 0 }}>
          <Text style={{ color: colors.text, fontFamily: fonts.monoBold, fontSize: 21 }} numberOfLines={1}>
            {call.phone || t('call.noNumber')}
          </Text>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 5 }}>
            <Clock size={13} />
            <Text style={{ color: colors.brand, fontFamily: fonts.monoSemibold, fontSize: 14 }}>{timer}</Text>
          </View>
        </View>
      </View>

      {connected ? (
        <View
          style={{
            marginTop: 14,
            flexDirection: 'row',
            alignItems: 'center',
            gap: 7,
            paddingHorizontal: 11,
            paddingVertical: 8,
            borderRadius: 9,
            backgroundColor: tint.connectedBg,
            borderWidth: 1,
            borderColor: tint.connectedBorder,
          }}
        >
          <CheckRing size={16} />
          <Text style={{ color: '#9fdcc0', fontFamily: fonts.medium, fontSize: 12 }}>
            {t('call.sentToPos')}
          </Text>
        </View>
      ) : null}
    </LinearGradient>
  );
}

function CompactConnected({ url, status }: { url: string; status: WsStatus }) {
  const { t } = useT();
  const m = META[status];
  return (
    <LinearGradient
      colors={[tint.connectedSoft, colors.inset]}
      start={{ x: 0, y: 0 }}
      end={{ x: 0.6, y: 1 }}
      style={{
        borderRadius: radius.lg,
        borderWidth: 1,
        borderColor: 'rgba(54,192,126,0.28)',
        padding: 14,
        flexDirection: 'row',
        alignItems: 'center',
        gap: 11,
      }}
    >
      <CheckRing size={22} color={m.color} />
      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={{ color: m.color, fontFamily: fonts.bold, fontSize: 15 }}>{t(m.labelKey as never)}</Text>
        <Text numberOfLines={1} style={{ color: colors.muted2, fontFamily: fonts.monoMedium, fontSize: 11, marginTop: 1 }}>
          {url}
        </Text>
      </View>
    </LinearGradient>
  );
}

/* ---------- Detection row ---------- */

function DetectionRow({ stopped, onFix }: { stopped: boolean; onFix?: () => void }) {
  const { t } = useT();
  if (stopped) {
    return (
      <View
        style={{
          marginTop: 11,
          flexDirection: 'row',
          alignItems: 'center',
          gap: 10,
          paddingHorizontal: 13,
          paddingVertical: 11,
          borderRadius: radius.md,
          backgroundColor: tint.dangerSoft,
          borderWidth: 1,
          borderColor: 'rgba(239,106,91,0.38)',
        }}
      >
        <Blink>
          <View style={{ width: 9, height: 9, borderRadius: 5, backgroundColor: colors.danger }} />
        </Blink>
        <Text style={{ flex: 1, color: colors.danger, fontFamily: fonts.semibold, fontSize: 12.5 }}>
          {t('status.detect.stopped')}
        </Text>
        <TouchableOpacity
          onPress={onFix}
          activeOpacity={0.85}
          style={{
            height: 30,
            paddingHorizontal: 12,
            borderRadius: 8,
            backgroundColor: colors.danger,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Text style={{ color: '#2C1011', fontFamily: fonts.bold, fontSize: 12 }}>{t('status.fix')}</Text>
        </TouchableOpacity>
      </View>
    );
  }
  return (
    <View
      style={{
        marginTop: 11,
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10,
        paddingHorizontal: 13,
        paddingVertical: 10,
        borderRadius: radius.md,
        backgroundColor: colors.inset,
        borderWidth: 1,
        borderColor: colors.border,
      }}
    >
      <Pulse duration={1800}>
        <View
          style={{
            width: 9,
            height: 9,
            borderRadius: 5,
            backgroundColor: colors.connected,
            shadowColor: colors.connected,
            shadowOpacity: 0.7,
            shadowRadius: 8,
          }}
        />
      </Pulse>
      <Text style={{ flex: 1, color: colors.textSoft, fontFamily: fonts.medium, fontSize: 12.5 }}>
        {t('status.detect.active')}
      </Text>
    </View>
  );
}

/* ---------- Event list ---------- */

function EventList({
  log,
  status,
  queuedCount,
}: {
  log: CallLogEntry[];
  status: WsStatus;
  queuedCount: number;
}) {
  const { t } = useT();
  const meta =
    status === 'connected'
      ? { text: t('status.live'), color: colors.connected, live: true }
      : queuedCount > 0
      ? { text: `${t('status.bufferedShort')} · ${queuedCount}`, color: colors.warn, live: false }
      : { text: t('status.paused'), color: colors.muted2, live: false };

  // Approximate which recent entries are still buffered (newest N).
  const bufferedUntil = status !== 'connected' ? Math.min(queuedCount, log.length) : 0;

  return (
    <View style={{ flex: 1, marginTop: 13, minHeight: 0 }}>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          paddingHorizontal: space.lg,
          paddingBottom: 8,
        }}
      >
        <Label>{t('status.events')}</Label>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 5 }}>
          {meta.live ? (
            <Pulse duration={1600}>
              <View style={{ width: 6, height: 6, borderRadius: 3, backgroundColor: colors.connected }} />
            </Pulse>
          ) : null}
          <Text style={{ color: meta.color, fontFamily: fonts.mono, fontSize: 11 }}>{meta.text}</Text>
        </View>
      </View>

      {log.length === 0 ? (
        <EmptyState />
      ) : (
        <ScrollView contentContainerStyle={{ paddingHorizontal: space.lg, paddingBottom: 8, gap: 7 }}>
          {log.map((e, i) => (
            <EventRow key={e.id} entry={e} buffered={i < bufferedUntil} />
          ))}
        </ScrollView>
      )}
    </View>
  );
}

function EventRow({ entry, buffered }: { entry: CallLogEntry; buffered: boolean }) {
  const { t } = useT();
  const isIn = entry.type === 'call_start' && entry.direction === 'in';
  const isOut = entry.type === 'call_start' && entry.direction === 'out';
  const ended = entry.type === 'call_end';

  const iconBg = isIn ? tint.connectedSoft : isOut ? tint.outgoingBg : colors.raised;
  const title = ended ? t('call.ended') : isOut ? t('call.outgoing') : t('call.incoming');

  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        gap: 11,
        paddingHorizontal: 11,
        paddingVertical: 10,
        borderRadius: radius.md,
        backgroundColor: colors.inset,
        borderWidth: 1,
        borderColor: buffered ? colors.borderStrong : colors.border,
        borderStyle: buffered ? 'dashed' : 'solid',
        opacity: buffered ? 0.9 : 1,
      }}
    >
      <View style={{ width: 32, height: 32, borderRadius: 9, backgroundColor: iconBg, alignItems: 'center', justifyContent: 'center' }}>
        {isIn ? <ArrowIncoming /> : isOut ? <ArrowOutgoing /> : <CallEnded />}
      </View>
      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={{ color: ended ? colors.muted : colors.text, fontFamily: fonts.semibold, fontSize: 12.5 }}>
          {title}
        </Text>
        <Text style={{ color: ended ? colors.muted2 : colors.brand, fontFamily: fonts.monoMedium, fontSize: 13, marginTop: 1 }}>
          {entry.phone || t('call.noNumber')}
        </Text>
      </View>
      {buffered ? (
        <Text style={{ color: colors.warn, fontFamily: fonts.monoSemibold, fontSize: 10 }}>
          {t('status.bufferedShort')}
        </Text>
      ) : (
        <Text style={{ color: colors.muted2, fontFamily: fonts.mono, fontSize: 11 }}>{formatTime(entry.at)}</Text>
      )}
    </View>
  );
}

function EmptyState() {
  const { t } = useT();
  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 34, paddingBottom: 20 }}>
      <View
        style={{
          width: 72,
          height: 72,
          borderRadius: 20,
          backgroundColor: colors.inset,
          borderWidth: 1,
          borderColor: colors.borderStrong,
          borderStyle: 'dashed',
          alignItems: 'center',
          justifyContent: 'center',
          marginBottom: 18,
        }}
      >
        <PhoneFill size={34} color={colors.muted2} />
      </View>
      <Text style={{ color: colors.textSoft, fontFamily: fonts.semibold, fontSize: 16 }}>
        {t('status.empty.title')}
      </Text>
      <Text style={{ color: colors.muted, fontFamily: fonts.regular, fontSize: 12.5, lineHeight: 19, textAlign: 'center', marginTop: 6 }}>
        {t('status.empty.desc')}
      </Text>
    </View>
  );
}

/* ---------- Footer ---------- */

function Footer({
  connected,
  onSendTest,
  onRepair,
}: {
  connected: boolean;
  onSendTest: () => void;
  onRepair: () => void;
}) {
  const { t } = useT();
  return (
    <View
      style={{
        paddingHorizontal: space.lg,
        paddingTop: 10,
        paddingBottom: 10,
        borderTopWidth: 1,
        borderTopColor: colors.inset,
      }}
    >
      <Button
        label={connected ? t('status.test') : t('status.test.disabled')}
        variant={connected ? 'primary' : 'disabled'}
        height={46}
        icon={connected ? <Send size={17} /> : <Send size={17} color={colors.faint} />}
        onPress={onSendTest}
        disabled={!connected}
      />
      <Button
        label={t('status.repair')}
        variant="secondary"
        height={42}
        style={{ marginTop: 7 }}
        onPress={onRepair}
      />
    </View>
  );
}
