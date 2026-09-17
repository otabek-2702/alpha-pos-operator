# Smart POS Operator 2.x — Samsung telefoniga o‘rnatish

Ushbu sozlash **Samsung SM-A037F/DS, Android 13, bitta faol SIM-karta** uchun tayyorlangan. Bitta Smart Food filialining barcha POSlari shu telefonga ulanadi. Ovoz yozuvlari va hisobot guruhlari uchun **bitta umumiy bot** ishlatiladi.

Samsung’ning avtomatik qo‘ng‘iroq yozish sozlamasi yoqilgan bo‘lsin. Operator telefon saqlagan audio fayllarni yuboradi. Telefon va POS kompyuterlari bir-biriga ulana oladigan mahalliy tarmoqda bo‘lishi, POS dasturi ochiq turishi kerak. Telegramga yuborish uchun internet ham kerak.

## Bir marta sozlash

1. Tayyorlangan **Operator 2.0.0 APK** faylini telefonga o‘rnating va ilovani oching.
2. **Ruxsatlar va batareya** bo‘limida **Asosiy ruxsatlarni berish** tugmasini bosing. Telefon holati, qo‘ng‘iroqlar tarixi, kamera va bildirishnomalar uchun ruxsat bering. **Fayllarga ruxsat berish**, **Batareya ruxsatini berish** va **O‘rnatish ruxsatini berish** tugmalaridan ham foydalaning. Oxirgisi ochgan oynada Operator uchun **Noma’lum ilovalarni o‘rnatish** ruxsatini yoqing — shunda ilova yangi versiyalarni o‘zi o‘rnatadi. Ilovaga qaytgach, ruxsatlar berilganini tekshiring. Qo‘ng‘iroqlar tarixi ruxsati berilmasa, ilova bu muammoni ko‘rsatadi; sozlash hali tugamagan hisoblanadi.
3. Samsung batareya sozlamalarida Operator uchun **Cheklanmagan** rejimini tanlang. Uni uyqudagi va chuqur uyqudagi ilovalardan chiqaring, **Hech qachon uyquga ketmaydigan ilovalar** ro‘yxatiga qo‘shing. Bo‘lim nomlari telefon modeli va tiliga qarab farq qilishi mumkin.
4. Har bir POS kompyuterida **Operator** rejimini yoqing. Shu tugmani bosib tursangiz, QR kod ochiladi. Telefonda **POS qo‘shish** orqali skanerlang. Har bir POS bir marta qo‘shiladi; keyingi ulanishlarda qayta skanerlash talab qilinmaydi. Eski versiyadan yangilaganda birinchi marta qayta qo‘shish kerak bo‘lishi mumkin.
5. **Telegram yozuvlari va hisobot** → **Telegram QR kodini skanerlash** ni bosing. Kompyuterda siz uchun tayyorlangan shaxsiy Telegram sozlash QR kodini ochib, telefon bilan skanerlang. Bu POS ulash QR kodidan alohida. QR ichida botning maxfiy tokeni bor: uni umumiy guruhga, ommaviy havolaga yoki APK bilan birga joylamang. QR tayyorlangan **Smart POS Operator** botini ikkala guruh uchun sozlaydi; boshqa bot yaratish kerak emas. Audio papka telefonda alohida tanlanadi.
6. Shu bo‘limda ilova Samsung qo‘ng‘iroq yozuvlari papkasini (**Ichki xotira/Recordings/Call**) o‘zi topadi va tanlaydi; papka ostida audio yozuvlar soni ko‘rinadi. Agar **Fayllarga ruxsat berish** tugmasi chiqsa, uni bosing, ochilgan oynada Operator uchun **Barcha fayllarga kirish** ruxsatini yoqing va ilovaga qayting. Papka topilmasa, **Yozuvlar papkasini avtomatik topish** ni qayta bosing yoki **Papkani qo‘lda tanlash** orqali Recordings → Call papkasini ochib, **Shu papkani tanlash** ni bosing. **Ovoz yozuvlarini yuborish** va kerak bo‘lsa **Qo‘ng‘iroqlar hisoboti** ni yoqing. **Sozlamalarni saqlash** ni bosing va **Sozlamalar saqlandi** xabarini kuting.

Ovoz fayllari **Smart Food ovoz yozuvlari** guruhiga, qo‘ng‘iroq tafsilotlari va javobsiz qo‘ng‘iroqlar **Smart Food qo'ng'iroqlar ma'lumotlari** guruhiga boradi.

## Qaysi yozuvlar yuboriladi?

Ovoz yuborish yoqilib saqlangan paytda papkada mavjud yozuvlar yuborilmaydi. Tekshirish uchun sozlash tugagandan keyin **yangi qo‘ng‘iroq** qiling. Yuborish qo‘ng‘iroq tugab, audio fayl tayyor bo‘lgach boshlanadi; internet bo‘lmasa navbat kutadi.

Yozuv darhol kelmasligi mumkin: yuborishdan oldin audio fayl kamida **60 soniya** o‘zgarmagan bo‘lishi va faol qo‘ng‘iroq bo‘lmasligi kerak. Yuborish holatini **Telegram yozuvlari va hisobot** bo‘limidan tekshiring.

Papka, yozuvlar guruhi yoki bot o‘zgartirilsa, shuningdek ovoz yuborish o‘chirib saqlanib, keyin qayta yoqilsa, o‘sha paytda mavjud fayllar yana eski deb belgilanadi. Avval navbatda turgan fayllar ham bundan mustasno emas. Vaqtinchalik internet uzilganda ovoz yuborishni yoqilgan holda qoldiring — navbatdagi fayllar ulanish tiklangach qayta yuboriladi.

## Avtomatik yangilanish

2.1.0 versiyadan boshlab ilova har 30 daqiqada GitHub’dagi yangi versiyani tekshiradi. Yangi versiya topilsa, u yuklab olinadi, tekshiriladi va **qo‘ng‘iroq bo‘lmagan paytda** (oxirgi qo‘ng‘iroqdan kamida 1 daqiqa o‘tgach) hech narsa bosmasdan o‘rnatiladi. O‘rnatish vaqtida xizmat bir necha soniyaga to‘xtaydi va o‘zi qayta ishga tushadi; POS va Telegram sozlamalari saqlanib qoladi. Qayta ishga tushgach, yuborilmay qolgan yozuvlar va hisobotlar darhol qayta yuboriladi. Yangilanish natijasi hisobot guruhiga xabar qilinadi.

Holatni **Yordam va ma‘lumot** sahifasidan ko‘rish va **Yangilanishni tekshirish** bilan darhol tekshirish mumkin. Agar Android tasdiq so‘rasa (masalan, “Noma’lum ilovalarni o‘rnatish” ruxsati o‘chirilgan bo‘lsa), bildirishnoma chiqadi — uni bosib o‘rnating.

2.0.1 dan 2.1.0 ga o‘tish bir marta qo‘lda qilinadi (APKni eski ilova ustidan o‘rnating). Keyingi versiyalar avtomatik keladi.

## 2.2.0: hisobotlar, smenalar, menejerlar va SMS

**Bir marta qo‘lda o‘rnatish.** 2.2.0 SMS yuborish ruxsatini qo‘shadi. Android bu ruxsatni faqat APK fayldan (Fayllar ilovasi orqali) o‘rnatilgan ilovaga beradi. Shuning uchun 2.2.0 ni guruhdagi APK fayldan eski ilova ustidan bir marta o‘rnating — sozlamalar saqlanib qoladi. Keyin **Ruxsatlar va batareya** bo‘limida **Asosiy ruxsatlarni berish** ni bosib, **SMS yuborish** va **Qo‘ng‘iroq qilish** ruxsatlarini bering.

**Zaxira guruhi.** Yangi Telegram sozlash QR kodini (**Telegram QR kodini skanerlash**) skanerlang yoki **Zaxira guruhi** maydoniga guruh ID sini kiriting. Bot yuborgan har bir xabarning nusxasi **Smart Food zaxira** guruhiga ham boradi, tahrirlar ham u yerda takrorlanadi.

**Qo‘ng‘iroq hisobotlari.** Har bir qo‘ng‘iroq bitta xabar bo‘lib keladi va holati o‘zgarganda o‘sha xabar tahrirlanadi:

- 🟢 javob berildi yoki hal qilindi (mijoz o‘zi qayta qo‘ng‘iroq qildi yoki operator qayta qo‘ng‘iroq qilib gaplashdi);
- 🟡 javobsiz, qayta qo‘ng‘iroq kutilmoqda;
- 🔴 yo‘qotilgan mijoz — 5 daqiqa ichida bog‘lanilmagan (alohida ogohlantirish xabari ham keladi);
- ⚫ bloklangan raqam (telefon jiringlamagan), ⚪ ish vaqtidan tashqari qo‘ng‘iroq.

Qidirish uchun heshteglar sanali bo‘ladi: holat (`#qabul170926`, `#otkazib170926`, `#hal_qilindi170926`, `#yoqotilgan170926`, `#chiquvchi170926`, `#bloklangan170926`, `#yopiq170926`), smena (`#smena1`, `#smena2`) va shu smenadagi menejer ismi (`#Ali_Valiyev`). Hisobotlar `#hisobot170926`, ogohlantirishlar `#ogohlantirish170926`.

**Ovoz yozuvlari** Telegram pleerida darhol eshitiladi (fayl o‘zgartirilmaydi, telefon qo‘shimcha yuklanmaydi). Fayl nomi qisqa: `+998901234567_260917_153114.m4a`. Izohda: smenadagi menejer, 📥 kiruvchi yoki 📤 chiquvchi, vaqt, suhbat davomiyligi, buyurtma raqami, `#ovoz170926` va eng pastda bo‘sh joysiz raqam hamda telefon kontaktidagi (bo‘lmasa POS dagi) mijoz ismi. Kontakt ismlari uchun **Ruxsatlar** bo‘limida **Kontaktlar** ruxsatini bering.

**Ish tartibi va SMS** (bosh sahifa). Smenalar: 08:00–17:00 va 17:00–02:00. Har smena tugaganda statistikali hisobot yuboriladi va guruhda qadab qo‘yiladi. 02:00–08:00 oralig‘idagi qo‘ng‘iroqlar “yopiq vaqt” deb belgilanadi, yo‘qotilgan hisoblanmaydi va ertalabki hisobotda ko‘rsatiladi. **Qo‘ng‘iroq qilganlarga SMS yuborish** yoqilsa, yopiq vaqtda qo‘ng‘iroq qilgan O‘zbekiston mobil raqamlariga har yopiq davrda bir marta SMS yuboriladi (kunlik chegara bilan). Menejerga xabar va “yo‘qotilgan” vaqtlarini shu yerda o‘zgartirish mumkin.

**Menejerlar** (bosh sahifa). **Menejer qo‘shish** → ism va raqam (**Kontaktlardan tanlash**) → ish jadvali: **Har hafta almashadi** (har yakshanba smena almashadi) yoki **Doimiy**. Javobsiz qo‘ng‘iroqdan keyin telefon bo‘sh turgan 2 daqiqa ichida (operator boshqa mijoz bilan gaplashayotgan vaqt hisoblanmaydi) qayta qo‘ng‘iroq qilinmasa, faqat shu smenadagi menejerlarga SMS va Telegram xabari boradi. “Yo‘qotilgan” uchun 5 daqiqa ham shu tartibda hisoblanadi. Telegram uchun menejerning shaxsiy havolasini unga yuboring; u botda **Start** ni bosganda ulanadi va faqat o‘z smenasidagi ogohlantirishlar, yo‘qotilgan mijozlar va smena hisobotini oladi.

**Qo‘shimcha:** javobsiz qo‘ng‘iroqda telefonda “Qo‘ng‘iroq qilish” tugmali eslatma chiqadi; bir kunda qayta-qayta qo‘ng‘iroq qilgan mijoz belgilanadi; POS 5 daqiqadan ko‘p uzilsa, internet uzilsa, batareya kamaysa yoki zaryadlovchi uzilsa va javob berilgan qo‘ng‘iroqning audiosi 10 daqiqada topilmasa, guruhga ogohlantirish boradi. Guruhda bot buyruqlari: `/holat` (hozirgi holat), `/hisobot` (joriy smena), `/raqam 901234567` (raqam tarixi).

**Telegram orqali boshqarish (egasi uchun).** **Menejerlar** sahifasidagi **Havolani yuborish** tugmasi bilan boshqaruv havolasini faqat o‘zingizga yuboring va botda **Start** ni bosing. Shundan keyin shaxsiy chatda:

- `/sozlamalar` — guruhlar, smenalar, vaqtlar, POS va menejerlar;
- `/menejer_qosh Ism, +998901234567, 1` — menejer qo‘shish (smena: `1`, `2` yoki `almashadi 1` — bu hafta 1-smena, keyin har yakshanba almashadi);
- `/menejer_ozgartir 1, raqam, +998901234567` — o‘zgartirish (`ism`, `raqam`, `smena`, `sms ha|yoq`, `telegram ha|yoq`);
- `/menejer_ochir 1`, `/havola 1` — o‘chirish va menejer havolasi;
- `/guruh ovoz|hisobot|zaxira -100…` — guruhni almashtirish (bot avval yangi guruhga sinov xabarini yozadi), `/guruh zaxira ochir`;
- `/yopiq_sms ha|yoq`, `/vaqt menejer 2`, `/vaqt yoqotilgan 5`, `/vaqt sms 50`.

O‘zgarish telefonga darhol yoziladi, ilova ochiq bo‘lsa ekranda ham yangilanadi. **Yangi havola** bosilsa, eski havola bilan ulangan barcha hisoblar boshqaruvdan uziladi.

**POS turi.** Bosh sahifada har bir POS uchun **Operator** yoki **Kassa** ni tanlang. Operator POS ida qo‘ng‘iroq oynasi ochiladi (buyurtma kiritilayotgan bo‘lsa, oyna o‘rniga yuqorida kichik xabar chiqadi). Kassa POS ida oyna ochilmaydi. Ikkalasida ham buyurtma oynasida hozirgi va so‘nggi qo‘ng‘iroq raqamlari tugma bo‘lib turadi — bir bosishda mijoz raqami kiritiladi. Buning uchun POS dasturi 0.0.17 yoki yangiroq bo‘lishi kerak.

## Tekshirish va kundalik ishlatish

- Bosh sahifada POSlar ulanishini tekshiring. **Sinov yuborish** POS ekranlarini tekshiradi; Telegram audiosi uchun alohida yangi qo‘ng‘iroq kerak.
- Javob berilgan qo‘ng‘iroq, javobsiz qo‘ng‘iroq va qayta qo‘ng‘iroqni sinang. Audio va hisobotni tegishli guruhlardan tekshiring. **Telegram** bo‘limida navbat va xatolar, **Ishlash tarixi** da xizmat ishlagan davrlar ko‘rinadi.
- Ekran qulflanganda va ilova yaqinda ochilgan ilovalar ro‘yxatidan olib tashlanganda ham sinang. Telefon qayta yoqilgach, PIN/parol bilan **birinchi marta qulfini oching**, so‘ng xizmat va ulanishlarni tekshiring.
- Android sozlamalaridagi **Majburan to‘xtatish / Force stop** dan keyin xizmat o‘zi qayta ishga tushmaydi. **Operator ilovasini qo‘lda ochish kerak.** Telefon o‘chgan paytda xizmat ishlamaydi.

Samsung’ning aynan shu modeli, yozuvlar papkasi va batareya sozlamalari haqiqiy telefonda tekshirilishi kerak. Avtomatik testlarning amaldagi holati [tekshiruv qaydlari](operator-2-validation.md) da berilgan.
