# Jos Scholman Showroom - Web versie

Online versie van de showroom app. Draait via GitHub Pages, geen installatie nodig.

## Setup (eenmalig)

1. Maak een GitHub repo aan (bijv. `scholman-showroom`)
2. Push alle bestanden naar de `main` branch
3. Ga naar repo → **Settings → Pages → Source: GitHub Actions**
4. Wacht tot de Actions klaar zijn (1-2 min)
5. Je app draait op: `https://[username].github.io/scholman-showroom/`

## Media toevoegen

1. Ga naar je repo op GitHub.com
2. Open `media/infra/`, `media/groen/`, of `media/sport/`
3. Klik **Add file → Upload files**
4. Sleep je foto's (JPG/PNG) en video's (MP4) erin
5. Klik **Commit changes**
6. GitHub Action genereert automatisch de index — klaar!

### Bestandsnaam = Label
- `golfbaan-cromvoirt.jpg` → **Golfbaan Cromvoirt**
- `rotonde_centrum.mp4` → **Rotonde Centrum**

### Cover afbeelding
Zet een `cover.jpg` in elke map voor de preview op het homescherm.

## Op de TV draaien

Installeer **Fully Kiosk Browser** op het Android touchscreen:
1. Open Play Store → zoek "Fully Kiosk Browser"
2. Stel de URL in op je GitHub Pages URL
3. Zet kiosk mode aan → fullscreen, geen adresbalk

## Kiosk exit
3x tikken op het logo-gebied bovenaan het homescherm.

## Limieten
- GitHub repo max ~1GB totaal
- Enkel bestand max 100MB
- Voor grotere video's: gebruik kortere clips of lagere resolutie
