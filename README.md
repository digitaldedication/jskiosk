# Jos Scholman Showroom

Touchscreen-kiosk voor de showroom. Bestaat uit drie delen:

| Onderdeel | Wat | Waar |
| --- | --- | --- |
| **Kiosk web app** | De fullscreen showroom (foto's, video's, swipe) | `index.html` → GitHub Pages |
| **Admin panel** | Web-UI om foto's/video's te uploaden | `admin/index.html` → `[pages-url]/admin/` |
| **Android APK** | Native kiosk-app met **offline cache** | `android/` → gebouwd door GitHub Actions |

## Hoe het werkt

1. Je uploadt media via de **admin panel** (in een browser, ook op je telefoon).
2. De admin push't bestanden via de GitHub API naar de repo.
3. De bestaande `generate-index.yml` Action genereert automatisch `index.json` per categorie.
4. GitHub Pages serveert alles als statische site.
5. De Android APK draait fullscreen op de TV. Op de achtergrond:
   - syncrhroniseert hij elke 15 minuten alle media naar **lokale opslag**;
   - speelt de kiosk altijd af vanaf die lokale cache → **werkt 100% offline**;
   - nieuwe uploads verschijnen vanzelf zodra de TV weer internet heeft.

## Eerste keer setup

### 1. Repo + Pages
1. Push de repo naar GitHub.
2. **Settings → Pages → Source: GitHub Actions**.
3. Wacht tot de "Deploy to GitHub Pages" Action klaar is.
4. Je kiosk draait op `https://[user].github.io/jskiosk/`.

### 2. APK bouwen
1. De Action **"Build Android APK"** loopt automatisch bij elke push naar `main` die de `android/` map raakt.
2. Resultaat verschijnt onder **Releases** (zoek naar `apk-v1.0.X`).
3. Eerste keer: ga zelf naar **Actions → Build Android APK → Run workflow** om hem handmatig te starten.

### 3. APK installeren op je Android
1. Download `.apk` van de laatste Release op je telefoon.
2. Sta "Apps van onbekende bronnen" toe voor de bestandsbeheerder.
3. Tik het bestand → installeren → klaar.
4. Start de app: hij toont de kiosk en begint media te syncen.

### 4. Admin panel gebruiken
1. Ga naar `https://[user].github.io/jskiosk/admin/`.
2. Maak een **GitHub Personal Access Token** (PAT):
   - Klik op de link in het loginscherm of ga naar [github.com/settings/tokens/new](https://github.com/settings/tokens/new?scopes=repo&description=JS%20Kiosk%20Admin).
   - Scope: alleen `repo`.
   - Kopieer de token (`ghp_…`).
3. Vul repo (`gebruiker/jskiosk`), branch (`main`) en token in.
4. Sleep foto's/video's per categorie. Klaar — de Pages site, en kort daarna de APK, ziet de update.

### Bestandsnaam = Label
- `golfbaan-cromvoirt.jpg` → **Golfbaan Cromvoirt**
- `rotonde_centrum.mp4` → **Rotonde Centrum**

### Cover afbeelding
Klik in de admin op "Als cover" bij een foto — die wordt dan de tile op het homescherm voor die categorie.

## Hoe de APK offline werkt

- Eerste start (met internet): downloadt index + alle media naar `<app-data>/kiosk-cache/media/<cat>/`.
- Daarna: WebView vraagt om bv. `…/media/infra/Almere.jpg`, en de app onderschept dat en serveert het bestand direct van schijf.
- Geen internet? Geen probleem — alle eerder gesyncte media speelt door.
- Internet terug? Volgende sync (max 15 min) trekt nieuwe / gewijzigde bestanden binnen en haalt verwijderde weg.

## Op de TV: kiosk-modus

De APK draait al fullscreen + landscape + zonder status/nav bars en is ook een **HOME launcher** — als je hem als default home zet (eenmalig "altijd" kiezen bij de home-knop), kan de gebruiker niets anders openen.

Eerder gebruikten we Fully Kiosk Browser; dat is met deze APK niet meer nodig.

## Snelheid

- **Foto's worden bij publicatie automatisch verkleind** naar max. 3840px
  (4K) en opnieuw gecomprimeerd. Je kunt dus gewoon originele camerafoto's
  (10-22 MB) uploaden; de kiosk en de APK krijgen een versie van ~1-2 MB.
- **In de browser** haalt een service worker (`sw.js`) bij elke start alle
  media vooraf binnen; daarna speelt alles direct van schijf, ook offline.
  Tijdens het binnenhalen zie je linksonder "Nieuwe media downloaden… x/y".
- **In de APK** doet de native cache hetzelfde (met byte-range-ondersteuning
  zodat video's direct starten).
- Het kiosk-scherm laadt per categorie alleen de huidige slide plus de twee
  buren, in plaats van alle media tegelijk.
- Een `.mov` met een gelijknamige `.mp4` wordt overgeslagen in de index
  (anders staat dezelfde video er twee keer in).

## Limieten

- GitHub repo max ~1 GB totaal.
- Enkel bestand max **100 MB** (admin panel weigert grotere uploads).
- Voor grotere video's: comprimeer naar 1080p/H.264.
