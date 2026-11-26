# WaEnhancer-Rainbow

**Fork of [WaEnhancer](https://github.com/Waenhancer-Dev/WaEnhancer) (by Dev4Mod), maintained by [Lukigays](https://github.com/Lukigays).**
WhatsApp tools for Android (Root / LSPosed) — dengan tampilan **iOS Liquid Glass**.

> ⚠️ Ini **bukan** repo WaEnhancer asli. Update in-app & rilis hanya mengikuti repo ini:
> https://github.com/Lukigays/WaEnhancer-Rainbow/releases/latest

## ✨ Fitur khas fork ini

### 🫧 Liquid Glass Bar + Lensa Seleksi (ala iOS 26)
- Bar navigasi bawah: **kaca gelap frosted polos** (tanpa warna)
- **Lensa seleksi netral** yang:
  - **Membesarkan konten tab di bawahnya** (refraksi kaca asli — konten dirender ulang 1.32×)
  - **Mengikuti jari** saat kamu geser di atas tab bar — tanpa animasi saat digeser, bergerak hanya karena digeser
  - **Meluncur (slide) dengan jelly stretch** ke tab yang dipilih saat jari dilepas
  - Menonjol keluar dari bar, lengkap dengan dark inner ring tebal, rim terang atas/bawah, dan drop shadow lembut
  - **Tanpa rainbow / tanpa warna** — persis foto referensi iOS
- FAB: lingkaran kaca gelap polos dengan rim terang

### 💬 iOS Chat Style
Toggle **Customization → iOS Chat Style**: bubble chat dibulatkan penuh **tanpa ekor** seperti WhatsApp iOS:
- Mode gelap: keluar **teal green**, masuk **abu gelap**
- Mode terang: keluar **mint**, masuk **putih**

### 🔄 Update ngikut repo ini
- In-app updater **hanya** mengecek rilis repo ini (bukan upstream) dan **Update Now** langsung mengunduh APK dari aset rilis.
- Workflow **Build & Auto Release**: setiap push ke `master` otomatis build → rilis dengan tag `v1.6.6-build.<N>`.
- `versionCode` & `versionName` naik per build + **signature tetap** (keystore di repo) → **APK update bisa langsung timpa** tanpa uninstall, data aman.

## 📦 Instalasi
1. Unduh APK terbaru dari [Releases](https://github.com/Lukigays/WaEnhancer-Rainbow/releases/latest)
2. Install (update tinggal timpa — signature sama & versionCode naik)
3. Aktifkan modul di **LSPosed** → scope: **WhatsApp** (dan WhatsApp Business bila perlu)
4. Restart WhatsApp → buka **WaEnhancer → Customization**:
   - **Floating Bottom Bar** + **Liquid Glass Lens** → dock kaca + lensa iOS
   - **iOS Chat Style** → bubble ala iOS

Mendukung WhatsApp & WhatsApp Business 2.26.x (dites base upstream 2.26.45/46).

## 🛠 Build sendiri
```bash
git clone --recurse-submodules https://github.com/Lukigays/WaEnhancer-Rainbow.git
cd WaEnhancer-Rainbow
./gradlew assembleWhatsappRelease
```
Atau langsung fork + push: workflow **Build & Auto Release** akan build & rilis otomatis.

## 🙏 Kredit
- [Dev4Mod / Waenhancer-Dev](https://github.com/Waenhancer-Dev/WaEnhancer) — proyek asli WaEnhancer (GPL-3.0)
- Seluruh kontributor upstream

## 📄 Lisensi
GPL-3.0 — sesuai proyek asli. Lihat [LICENSE](LICENSE).
