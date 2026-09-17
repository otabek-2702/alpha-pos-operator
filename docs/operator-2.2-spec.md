# Operator 2.2 — agreed behaviour (2026-09-17)

Phone app 2.2.0 (self-updating) and desktop POS 0.0.17. Uzbek is the language of
every user-facing text. Numbers below are defaults editable in the phone app.

## Telegram destinations

| Chat | Content |
|---|---|
| Ovoz yozuvlari (`chatId`) | audio recordings |
| Qo‘ng‘iroqlar ma’lumotlari (`statsChatId`) | call reports, alerts, pinned shift reports, health alerts, bot commands |
| Smart Food zaxira (`backupChatId`) | an exact copy of everything above; edits mirrored |
| Manager private chats | alerts and shift reports for the manager on duty |

* Copies, not forwards. Audio is re-sent by Telegram `file_id` (no second upload).
* Every destination has its own durable outbox row; nothing is dropped while offline.
* A `migrate_to_chat_id` error (basic group upgraded to supergroup) updates the
  configured chat ID and retries automatically.
* The setup QR becomes version 2: `{type, version: 2, botToken, chatId, statsChatId, backupChatId}`.
  Version 1 stays accepted.
* Messages use `parse_mode=HTML`; only status circles carry colour:
  🟢 good · 🟡 waiting · 🔴 lost/danger · ⚪ neutral/closed · ⚫ blocked.

## Call lifecycle (reports group)

One message per call, edited when the call's state changes.

| Status | Rule |
|---|---|
| 🟢 Qabul qilindi | incoming answered |
| 🟡 O‘tkazib yuborildi — kutilmoqda | missed/rejected in working hours, no conversation yet |
| 🟡 Qayta terildi, javob yo‘q | outgoing attempt to the number without a conversation |
| 🟢 Hal qilindi | a later conversation with the number: operator callback **or** the client called again and was answered |
| 🔴 YO‘QOTILGAN MIJOZ | still unresolved **5 min** after the missed call |
| ⚪ Kafe yopiq edi | the call happened outside every shift (never "lost") |
| ⚪ Chiquvchi | outgoing call (not a callback) |
| ⚫ Bloklangan raqam | Android call log type BLOCKED; the phone did not ring |

Extra lines: customer name (from POS), tappable number, ring/answer/talk
durations, "Operator band edi: <number>" for calls missed while another call was
active, "Bugun N-qo‘ng‘iroq" for repeat callers (≥2 missed today = urgent),
"🧾 Buyurtma #id" when the POS reports an order for the number.

Timeline for a missed call in working hours:
1. 0 min — report 🟡.
2. **1 min** without a callback — notification on the operator phone with a
   "Qo‘ng‘iroq qilish" action; SMS + Telegram alert to managers on duty.
3. **5 min** without a conversation — report becomes 🔴, a reply alert is posted
   in the reports group (mirrored) and sent to managers on duty.
4. A later conversation turns the report 🟢 at any time (also after 🔴).

## Closed hours
* Default shifts: **1-smena 08:00–17:00**, **2-smena 17:00–02:00** (shift date = start date).
* Outside shifts the cafe is closed: calls are ⚪, never lost, no manager alert.
* Closed-hours callers (Uzbek mobile numbers only, not hidden) get **one SMS per closed period**:
  "Smart Food kafesi hozir ishlamayapti. Ish vaqtimiz: har kuni 08:00 dan 02:00 gacha. Qo‘ng‘iroq qilganingiz uchun rahmat!"
* Closed-period calls are listed in the next (morning) shift report.

## Shift report
Sent when a shift ends, pinned in the reports group (and backup), sent to
managers on duty: totals by status, average answer time, callbacks with average
delay, lost clients with links to their messages (supergroups), outgoing calls,
blocked, total talk time, calls per hour (busiest hours), calls that led to an
order, service uptime %, POS connection %, closed-period calls (morning report).

## Hashtags (Uzbek, no apostrophes)
Status: `#qabul` `#otkazib` `#hal_qilindi` `#yoqotilgan` `#chiquvchi`
`#bloklangan` `#yopiq` `#ovoz` `#hisobot` `#ogohlantirish`.
Dated variants append `ddmmyy`: `#yoqotilgan170926`, `#hisobot170926`,
plus `#kun170926` on every message and `#smena1` / `#smena2`.

## Managers
* Added in the phone app: name, phone (Android contact picker or typed),
  schedule = fixed shift or **weekly rotation** (week starts Sunday; anchor week + shift).
* Telegram: the app creates a private invite link `https://t.me/<bot>?start=<code>`
  (shared via Android share sheet). When the manager presses Start, the phone
  (polling `getUpdates`) links that chat to the manager and replies with a greeting.
* A manager receives SMS + Telegram for alerts of their own shift only.
* Daily SMS cap (default 50) protects the SIM balance.

## Bot commands (answered by the phone)
`/holat` (version, battery, charging, POS connections, internet, last sync),
`/hisobot` (current shift so far), `/raqam 901234567` (history of a number),
`/start <code>` (manager registration). Only in the reports/backup groups and
registered manager chats.

## Health alerts (reports group + backup + managers on duty)
POS disconnected > 5 min during a shift; internet restored after an outage
(with its duration); battery ≤ 20 % or charging stopped; an answered call with
no audio file 10 min later ("Samsung yozib olish o‘chgan bo‘lishi mumkin").

## Phone ↔ POS protocol 3
Phone → POS:
* `operator_hello {protocol: 3, role: "operator"|"cashier", app}` after connecting and when the role changes.
* `call_state {calls: [{id, phone, direction, state: ringing|active|waiting|ended, since, customerName?}]}`
  on every change; ended calls stay listed for 10 min (max 5) for quick fill.
* Legacy `call_start`/`call_end` only to **operator** POS and never for a call
  that is waiting behind an active one (old desktops then behave correctly).

POS → phone: existing `customer_name`, `call_record_ack`, and new
`order_created {phone, orderId, at}`.

Desktop 0.0.17: role from `operator_hello` (default operator); operator POS shows
the modal only when no order entry is open, otherwise a non-blocking banner;
cashier POS never shows the modal; both show quick-fill chips (current, waiting,
recent callers) in the order phone field; customer lookup via `/clients/lookup`
with a cache instead of downloading 200 orders; the popup keeps the call
direction after hang-up; `order_created` is sent after an order with a phone is
saved. The owner's KDS changes (oldest orders first) are included.

## Phone reliability
Wi-Fi lock while the service runs; 15 s handshake watchdog; connection log per
POS; SEND_SMS and CALL_PHONE permissions (verify whether a self-update can grant
the restricted SMS permission; otherwise install 2.2.0 once by hand).
