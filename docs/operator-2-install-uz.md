# Smart POS Operator 2.0 — Samsung telefoniga o‘rnatish

Samsung’ning avtomatik qo‘ng‘iroq yozish sozlamasi yoqilgan bo‘lsin. Operator telefon saqlagan audio fayllarni yuboradi. Telefon va POS kompyuterlari bir-biriga ulana oladigan mahalliy tarmoqda bo‘lishi, POS dasturi ochiq turishi kerak. Telegramga yuborish uchun internet ham kerak.

## Bir marta sozlash

1. Tayyorlangan **Operator 2.0.0 APK** faylini telefonga o‘rnating va ilovani oching.
2. **Ruxsatlar va batareya** bo‘limida **Asosiy ruxsatlarni berish** tugmasini bosing. Telefon holati, qo‘ng‘iroqlar tarixi, kamera va bildirishnomalar uchun ruxsat bering. **Fayllarga ruxsat berish** va **Batareya ruxsatini berish** tugmalaridan ham foydalaning. Ilovaga qaytgach, ruxsatlar berilganini tekshiring. Qo‘ng‘iroqlar tarixi ruxsati berilmasa, ilova bu muammoni ko‘rsatadi; sozlash hali tugamagan hisoblanadi.
3. Samsung batareya sozlamalarida Operator uchun **Cheklanmagan** rejimini tanlang. Uni uyqudagi va chuqur uyqudagi ilovalardan chiqaring, **Hech qachon uyquga ketmaydigan ilovalar** ro‘yxatiga qo‘shing. Bo‘lim nomlari telefon modeli va tiliga qarab farq qilishi mumkin.
4. Har bir POS kompyuterida **Operator** rejimini yoqing. Shu tugmani bosib tursangiz, QR kod ochiladi. Telefonda **POS qo‘shish** orqali skanerlang. Har bir POS bir marta qo‘shiladi; keyingi ulanishlarda qayta skanerlash talab qilinmaydi. Eski versiyadan yangilaganda birinchi marta qayta qo‘shish kerak bo‘lishi mumkin.
5. **Telegram yozuvlari va hisobot** → **Telegram QR kodini skanerlash** ni bosing. Kompyuterda siz uchun tayyorlangan shaxsiy Telegram sozlash QR kodini ochib, telefon bilan skanerlang. Bu POS ulash QR kodidan alohida. QR ichida botning maxfiy tokeni bor: uni umumiy guruhga, ommaviy havolaga yoki APK bilan birga joylamang. QR bot va ikkita guruhni sozlaydi; audio papka telefonda alohida tanlanadi.
6. **Yozuvlar papkasini tanlash** orqali Samsung qo‘ng‘iroqlarni saqlaydigan haqiqiy papkani tanlang. Android papka oynasida undan foydalanishga ruxsat bering. **Ovoz yozuvlarini yuborish** va kerak bo‘lsa **Qo‘ng‘iroqlar hisoboti** ni yoqing. **Sozlamalarni saqlash** ni bosing va **Sozlamalar saqlandi** xabarini kuting.

Ovoz fayllari **Smart Food ovoz yozuvlari** guruhiga, qo‘ng‘iroq tafsilotlari va javobsiz qo‘ng‘iroqlar **Smart Food qo'ng'iroqlar ma'lumotlari** guruhiga boradi.

## Qaysi yozuvlar yuboriladi?

Ovoz yuborish yoqilib saqlangan paytda papkada mavjud yozuvlar yuborilmaydi. Tekshirish uchun sozlash tugagandan keyin **yangi qo‘ng‘iroq** qiling. Yuborish qo‘ng‘iroq tugab, audio fayl tayyor bo‘lgach boshlanadi; internet bo‘lmasa navbat kutadi.

Papka, yozuvlar guruhi yoki bot o‘zgartirilsa, shuningdek ovoz yuborish o‘chirib saqlanib, keyin qayta yoqilsa, o‘sha paytda mavjud fayllar yana eski deb belgilanadi. Avval navbatda turgan fayllar ham bundan mustasno emas. Vaqtinchalik internet uzilganda ovoz yuborishni yoqilgan holda qoldiring — navbatdagi fayllar ulanish tiklangach qayta yuboriladi.

## Tekshirish va kundalik ishlatish

- Bosh sahifada POSlar ulanishini tekshiring. **Sinov yuborish** POS ekranlarini tekshiradi; Telegram audiosi uchun alohida yangi qo‘ng‘iroq kerak.
- Javob berilgan qo‘ng‘iroq, javobsiz qo‘ng‘iroq va qayta qo‘ng‘iroqni sinang. Audio va hisobotni tegishli guruhlardan tekshiring. **Telegram** bo‘limida navbat va xatolar, **Ishlash tarixi** da xizmat ishlagan davrlar ko‘rinadi.
- Ekran qulflanganda va ilova yaqinda ochilgan ilovalar ro‘yxatidan olib tashlanganda ham sinang. Telefon qayta yoqilgach, PIN/parol bilan **birinchi marta qulfini oching**, so‘ng xizmat va ulanishlarni tekshiring.
- Android sozlamalaridagi **Majburan to‘xtatish / Force stop** dan keyin xizmat o‘zi qayta ishga tushmaydi. **Operator ilovasini qo‘lda ochish kerak.** Telefon o‘chgan paytda xizmat ishlamaydi.

Samsung’ning aynan shu modeli, yozuvlar papkasi va batareya sozlamalari haqiqiy telefonda tekshirilishi kerak. Avtomatik testlarning amaldagi holati [tekshiruv qaydlari](operator-2-validation.md) da berilgan.
