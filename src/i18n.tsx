import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';

/**
 * Lightweight i18n. Uzbek is primary (matches the design); Russian and English
 * are provided. The chosen language is persisted and cycles via the header chip.
 */

export type Lang = 'uz' | 'ru' | 'en';
export const LANGS: Lang[] = ['uz', 'ru', 'en'];
export const LANG_LABEL: Record<Lang, string> = {
  uz: "O'zbekcha",
  ru: 'Русский',
  en: 'English',
};
export const LANG_SHORT: Record<Lang, string> = { uz: 'UZ', ru: 'RU', en: 'EN' };

type Dict = Record<string, string>;

const uz: Dict = {
  'app.tagline': 'Operatorni kassa bilan bog‘lash',
  'app.name': 'Operator Link',
  'splash.loading': 'Modullar yuklanmoqda…',

  'perm.title': 'Ruxsatlar',
  'perm.subtitle': 'Ilova ishlashi uchun zarur',
  'perm.subtitle.partial': 'Bitta majburiy ruxsat qoldi',
  'perm.subtitle.ready': 'Hammasi tayyor — ulanish mumkin',
  'perm.subtitle.blocked': 'Bitta ruxsat butunlay rad etilgan',
  'perm.required': 'MAJB.',
  'perm.optional': 'IXT.',
  'perm.camera': 'Kamera',
  'perm.camera.desc': 'Kassa QR-kodini skanerlash',
  'perm.phone': 'Telefon holati',
  'perm.phone.desc': 'Boshlanishi va tugashi',
  'perm.calllog': 'Qo‘ng‘iroqlar tarixi',
  'perm.calllog.desc': 'Qo‘ng‘iroq raqamini o‘qish',
  'perm.notif': 'Bildirishnomalar',
  'perm.notif.desc': 'Fonda bildirishnoma',
  'perm.battery.title': 'Batareya optimizatsiyasi',
  'perm.battery.desc': 'O‘chirilmasa, tizim ilovani fonda yopib qo‘yadi.',
  'perm.battery.fix': 'Tuzatish',
  'perm.battery.done': 'Batareya optimizatsiyasi o‘chirildi. Ilova fonda ishlaydi.',
  'perm.grant': 'Ruxsat berish',
  'perm.recheck': 'Qayta tekshirish',
  'perm.continue': 'Davom etish',
  'perm.continue.disabled': 'Avval majburiy ruxsatlarni bering',
  'perm.denied.badge': 'RAD ETILGAN',
  'perm.denied.row': 'Rad etilgan — sozlamalarda yoqing',
  'perm.denied.hint':
    'Ruxsatni qayta so‘rab bo‘lmaydi. Tizim sozlamalarini ochib, uni qo‘lda yoqing.',
  'perm.openSettings': 'Tizim sozlamalarini ochish',

  'pair.title': 'Kassaga ulanish',
  'pair.hint': 'Kamerani kassa (POS) ekranidagi QR-kodga to‘g‘rilang',
  'pair.searching': 'QR-kod qidirilmoqda…',
  'pair.invalid.title': 'Noto‘g‘ri kod',
  'pair.invalid.desc': 'Kutilayotgan manzil ws://. Skanerlandi:',
  'pair.rescan': 'Qayta skanerlash',
  'pair.cam.title': 'Kameraga ruxsat kerak',
  'pair.cam.desc':
    'Kamera faqat kassa QR-kodini skanerlash uchun ishlatiladi. Video hech qayerga uzatilmaydi.',
  'pair.cam.grant': 'Kameraga ruxsat berish',

  'status.label': 'Aloqa holati',
  'status.connected': 'Ulangan',
  'status.connecting': 'Ulanmoqda…',
  'status.reconnecting': 'Qayta ulanmoqda…',
  'status.disconnected': 'Aloqa yo‘q',
  'status.pos': 'Kassa (POS)',
  'status.reconnect': 'Qayta ulanish',
  'status.detect.active': 'Qo‘ng‘iroq aniqlash faol',
  'status.detect.stopped': 'Qo‘ng‘iroq aniqlash to‘xtatildi',
  'status.attempt': 'urinish',
  'status.retryIn': 's dan keyin',
  'status.buffered': 'Hodisalar yuborishni kutmoqda',
  'status.events': 'So‘nggi hodisalar',
  'status.live': 'jonli',
  'status.bufferedShort': 'buferda',
  'status.notSynced': 'sinx. emas',
  'status.paused': 'pauzada',
  'status.empty.title': 'Hozircha hodisalar yo‘q',
  'status.empty.desc':
    'Hodisalar birinchi kiruvchi yoki chiquvchi qo‘ng‘iroqda avtomatik paydo bo‘ladi.',
  'status.test': 'Kassaga test yuborish',
  'status.test.disabled': 'Test uchun ulaning',
  'status.repair': 'Yangi QR skanerlash',
  'status.revoked.title': 'Qo‘ng‘iroqqa ruxsat bekor qilindi',
  'status.revoked.desc':
    'Qo‘ng‘iroq raqamlari endi o‘qilmaydi. Davom etish uchun ruxsatni tiklang.',
  'status.fix': 'Tuzatish',

  'call.incoming': 'Kiruvchi qo‘ng‘iroq',
  'call.outgoing': 'Chiquvchi qo‘ng‘iroq',
  'call.ended': 'Qo‘ng‘iroq tugadi',
  'call.incoming.live': 'Kiruvchi qo‘ng‘iroq bormoqda',
  'call.outgoing.live': 'Chiquvchi qo‘ng‘iroq bormoqda',
  'call.sentToPos': 'Raqam kassaga uzatildi',
  'call.now': 'hozir',
  'call.noNumber': '(raqam yo‘q)',
};

const ru: Dict = {
  'app.tagline': 'Связь оператора с кассой',
  'app.name': 'Operator Link',
  'splash.loading': 'Загрузка модулей…',

  'perm.title': 'Разрешения',
  'perm.subtitle': 'Необходимы для работы',
  'perm.subtitle.partial': 'Осталось одно обязательное',
  'perm.subtitle.ready': 'Всё готово — можно подключаться',
  'perm.subtitle.blocked': 'Одно разрешение полностью отклонено',
  'perm.required': 'ОБЯЗ.',
  'perm.optional': 'ОПЦ.',
  'perm.camera': 'Камера',
  'perm.camera.desc': 'Сканирование QR-кода кассы',
  'perm.phone': 'Состояние телефона',
  'perm.phone.desc': 'Начало и конец звонка',
  'perm.calllog': 'Журнал звонков',
  'perm.calllog.desc': 'Чтение номера звонящего',
  'perm.notif': 'Уведомления',
  'perm.notif.desc': 'Фоновое уведомление',
  'perm.battery.title': 'Оптимизация батареи',
  'perm.battery.desc': 'Без отключения система закроет приложение в фоне.',
  'perm.battery.fix': 'Исправить',
  'perm.battery.done': 'Оптимизация батареи отключена. Приложение работает в фоне.',
  'perm.grant': 'Дать разрешения',
  'perm.recheck': 'Проверить снова',
  'perm.continue': 'Продолжить',
  'perm.continue.disabled': 'Сначала выдайте обязательные разрешения',
  'perm.denied.badge': 'ОТКЛОНЕНО',
  'perm.denied.row': 'Отклонено — включите в настройках',
  'perm.denied.hint':
    'Разрешение нельзя запросить снова. Откройте настройки системы и включите вручную.',
  'perm.openSettings': 'Открыть настройки системы',

  'pair.title': 'Подключение к кассе',
  'pair.hint': 'Наведите камеру на QR-код на экране кассы (POS)',
  'pair.searching': 'Поиск QR-кода…',
  'pair.invalid.title': 'Неверный код',
  'pair.invalid.desc': 'Ожидается адрес ws://. Отсканировано:',
  'pair.rescan': 'Сканировать снова',
  'pair.cam.title': 'Нужен доступ к камере',
  'pair.cam.desc':
    'Камера используется только для сканирования QR-кода. Видео никуда не передаётся.',
  'pair.cam.grant': 'Разрешить камеру',

  'status.label': 'Состояние связи',
  'status.connected': 'Подключено',
  'status.connecting': 'Подключение…',
  'status.reconnecting': 'Переподключение…',
  'status.disconnected': 'Нет связи',
  'status.pos': 'Касса (POS)',
  'status.reconnect': 'Переподключить',
  'status.detect.active': 'Определение звонков активно',
  'status.detect.stopped': 'Определение звонков остановлено',
  'status.attempt': 'попытка',
  'status.retryIn': 'через с',
  'status.buffered': 'Ожидают отправки',
  'status.events': 'Последние события',
  'status.live': 'live',
  'status.bufferedShort': 'в буфере',
  'status.notSynced': 'не синхр.',
  'status.paused': 'пауза',
  'status.empty.title': 'Пока нет событий',
  'status.empty.desc':
    'События появятся автоматически при первом входящем или исходящем звонке.',
  'status.test': 'Тест на кассу',
  'status.test.disabled': 'Подключитесь для теста',
  'status.repair': 'Сканировать новый QR',
  'status.revoked.title': 'Доступ к звонкам отозван',
  'status.revoked.desc':
    'Номера звонков больше не читаются. Восстановите разрешение, чтобы продолжить.',
  'status.fix': 'Исправить',

  'call.incoming': 'Входящий звонок',
  'call.outgoing': 'Исходящий звонок',
  'call.ended': 'Звонок завершён',
  'call.incoming.live': 'Идёт входящий звонок',
  'call.outgoing.live': 'Идёт исходящий звонок',
  'call.sentToPos': 'Номер передан на кассу',
  'call.now': 'сейчас',
  'call.noNumber': '(нет номера)',
};

const en: Dict = {
  'app.tagline': 'Linking the operator to the POS',
  'app.name': 'Operator Link',
  'splash.loading': 'Loading modules…',

  'perm.title': 'Permissions',
  'perm.subtitle': 'Required for the app to work',
  'perm.subtitle.partial': 'One required permission left',
  'perm.subtitle.ready': 'All set — ready to connect',
  'perm.subtitle.blocked': 'One permission was permanently denied',
  'perm.required': 'REQ.',
  'perm.optional': 'OPT.',
  'perm.camera': 'Camera',
  'perm.camera.desc': 'Scan the POS pairing QR',
  'perm.phone': 'Phone state',
  'perm.phone.desc': 'Detect call start and end',
  'perm.calllog': 'Call log',
  'perm.calllog.desc': 'Read the caller number',
  'perm.notif': 'Notifications',
  'perm.notif.desc': 'Background service notice',
  'perm.battery.title': 'Battery optimization',
  'perm.battery.desc': 'Without this, Android may kill the app in the background.',
  'perm.battery.fix': 'Fix',
  'perm.battery.done': 'Battery optimization disabled. The app runs in the background.',
  'perm.grant': 'Grant permissions',
  'perm.recheck': 'Re-check',
  'perm.continue': 'Continue',
  'perm.continue.disabled': 'Grant the required permissions first',
  'perm.denied.badge': 'DENIED',
  'perm.denied.row': 'Denied — enable it in settings',
  'perm.denied.hint':
    'The permission cannot be requested again. Open system settings and enable it manually.',
  'perm.openSettings': 'Open system settings',

  'pair.title': 'Pair with POS',
  'pair.hint': 'Point the camera at the pairing QR on the POS screen',
  'pair.searching': 'Searching for QR code…',
  'pair.invalid.title': 'Invalid code',
  'pair.invalid.desc': 'Expecting a ws:// address. Scanned:',
  'pair.rescan': 'Scan again',
  'pair.cam.title': 'Camera access needed',
  'pair.cam.desc':
    'The camera is only used to scan the POS QR code. Video is never sent anywhere.',
  'pair.cam.grant': 'Grant camera access',

  'status.label': 'Connection status',
  'status.connected': 'Connected',
  'status.connecting': 'Connecting…',
  'status.reconnecting': 'Reconnecting…',
  'status.disconnected': 'Disconnected',
  'status.pos': 'POS desktop',
  'status.reconnect': 'Reconnect',
  'status.detect.active': 'Call detection active',
  'status.detect.stopped': 'Call detection stopped',
  'status.attempt': 'attempt',
  'status.retryIn': 's until retry',
  'status.buffered': 'Waiting to be sent',
  'status.events': 'Recent events',
  'status.live': 'live',
  'status.bufferedShort': 'buffered',
  'status.notSynced': 'not synced',
  'status.paused': 'paused',
  'status.empty.title': 'No events yet',
  'status.empty.desc':
    'Events appear automatically on the first incoming or outgoing call.',
  'status.test': 'Send test to POS',
  'status.test.disabled': 'Connect to send a test',
  'status.repair': 'Scan a new QR',
  'status.revoked.title': 'Call permission revoked',
  'status.revoked.desc':
    'Caller numbers can no longer be read. Restore the permission to continue.',
  'status.fix': 'Fix',

  'call.incoming': 'Incoming call',
  'call.outgoing': 'Outgoing call',
  'call.ended': 'Call ended',
  'call.incoming.live': 'Incoming call in progress',
  'call.outgoing.live': 'Outgoing call in progress',
  'call.sentToPos': 'Number sent to the POS',
  'call.now': 'now',
  'call.noNumber': '(no number)',
};

const DICTS: Record<Lang, Dict> = { uz, ru, en };
const LANG_KEY = 'alphapos.operatorlink.lang';

interface I18nValue {
  lang: Lang;
  setLang: (l: Lang) => void;
  cycleLang: () => void;
  t: (key: keyof typeof uz) => string;
}

const I18nContext = createContext<I18nValue | null>(null);

export function I18nProvider({ children }: { children: React.ReactNode }) {
  const [lang, setLangState] = useState<Lang>('uz');

  useEffect(() => {
    AsyncStorage.getItem(LANG_KEY)
      .then((saved) => {
        if (saved === 'uz' || saved === 'ru' || saved === 'en') setLangState(saved);
      })
      .catch(() => {});
  }, []);

  const setLang = useCallback((l: Lang) => {
    setLangState(l);
    AsyncStorage.setItem(LANG_KEY, l).catch(() => {});
  }, []);

  const cycleLang = useCallback(() => {
    setLangState((prev) => {
      const next = LANGS[(LANGS.indexOf(prev) + 1) % LANGS.length]!;
      AsyncStorage.setItem(LANG_KEY, next).catch(() => {});
      return next;
    });
  }, []);

  const value = useMemo<I18nValue>(() => {
    const dict = DICTS[lang];
    return {
      lang,
      setLang,
      cycleLang,
      t: (key) => dict[key as string] ?? (uz[key as string] ?? (key as string)),
    };
  }, [lang, setLang, cycleLang]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useT(): I18nValue {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error('useT must be used within I18nProvider');
  return ctx;
}
