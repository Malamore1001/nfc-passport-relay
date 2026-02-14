# 🛂 NFC Passport Relay System

Ett komplett system för att relay:a NFC-kommunikation mellan två Android-telefoner i realtid.

## 📱 Hur det fungerar

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                                                                                │
│   SVERIGE                        INTERNET                        LONDON        │
│   ────────                       ────────                        ──────        │
│                                                                                │
│   ┌─────────┐      ┌──────────┐      ┌──────────┐      ┌─────────────┐        │
│   │  PASS   │ NFC  │ READER   │  WS  │  SERVER  │  WS  │  EMULATOR   │  NFC   │
│   │   🛂    │◄────►│   📖     │◄────►│    🔄    │◄────►│     📡      │◄──────►│
│   │         │      │ (App)    │      │ (Node)   │      │  (HCE App)  │        │
│   └─────────┘      └──────────┘      └──────────┘      └─────────────┘        │
│                                                              │                 │
│                                                              ▼                 │
│                                                        ┌───────────┐          │
│                                                        │NFC READER │          │
│                                                        │  (t.ex.   │          │
│                                                        │ passport  │          │
│                                                        │  scanner) │          │
│                                                        └───────────┘          │
│                                                                                │
└────────────────────────────────────────────────────────────────────────────────┘
```

## 🚀 Quick Start

### 1. Starta servern

```powershell
cd C:\Users\PC\nfc-passport-relay\server
npm install
npm start
```

Servern startar på `http://localhost:3000`

### 2. Öppna Web UI (för test)

Gå till `http://localhost:3000` i webbläsaren för att testa.

### 3. Bygg Android-apparna

Öppna i Android Studio:
- `android-reader/` - För telefonen som har passet (Sverige)
- `android-emulator/` - För telefonen som behöver passet (London)

## 📱 Användning

### Steg 1: READER (Sverige - har passet)
1. Öppna **NFC Reader**-appen
2. Ange server-URL (t.ex. `ws://din-server:3000`)
3. Tryck **ANSLUT**
4. Tryck **SKAPA SESSION** → Du får en 6-teckens kod (t.ex. `ABC123`)
5. **Skanna MRZ** från passet (kamera eller manuellt)
6. Lägg passet på telefonen

### Steg 2: EMULATOR (London - behöver passet)
1. Öppna **NFC Emulator**-appen
2. Ange samma server-URL
3. Tryck **ANSLUT**
4. Ange session-koden (`ABC123`)
5. Tryck **GÅ MED I SESSION**
6. Lägg telefonen på NFC-läsaren

### Steg 3: Relay sker automatiskt! 🎉
- NFC-läsaren skickar kommando → Emulator → Server → Reader → Pass
- Pass svarar → Reader → Server → Emulator → NFC-läsare

## 📁 Projektstruktur

```
nfc-passport-relay/
├── server/                          # WebSocket relay-server
│   ├── package.json
│   ├── server.js                    # Huvudserver
│   └── public/
│       └── index.html               # Webb-UI för testning
│
├── android-reader/                  # Android-app för passet
│   └── app/src/main/
│       ├── java/.../
│       │   ├── MainActivity.kt      # Huvudaktivitet
│       │   ├── MrzParser.kt         # MRZ-tolkning + BAC-nycklar
│       │   └── BacProtocol.kt       # BAC-autentisering
│       └── res/
│           └── layout/activity_main.xml
│
└── android-emulator/                # Android-app för HCE
    └── app/src/main/
        ├── java/.../
        │   ├── MainActivity.kt      # Huvudaktivitet
        │   └── PassportHceService.kt # HCE-tjänst
        └── res/
            └── xml/apduservice.xml  # AID-konfiguration
```

## 🔐 MRZ & BAC

### Vad är MRZ?
Machine Readable Zone - de två raderna med text längst ner på passbildssidan.

```
P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<<
L898902C<3UTO6908061F9406236ZE184226B<<<<<10
```

### Vad är BAC?
Basic Access Control - kryptering som skyddar passdata. Nycklar härleds från:
- Dokumentnummer
- Födelsedatum
- Utgångsdatum

## ⚠️ Viktiga begränsningar

| Faktor | Beskrivning |
|--------|-------------|
| **Latens** | Pass-läsare har ofta 300-500ms timeout. Internet-latens kan vara för hög. |
| **HCE** | Emulator-telefonen måste vara inställd som standard NFC-app |
| **Android** | Båda telefoner måste vara Android (HCE stöds inte på iPhone) |
| **BAC** | MRZ måste skannas/anges innan passet kan läsas |

## 🔧 Felsökning

### "Connection failed"
- Kontrollera att servern körs
- Kontrollera brandväggsregler
- Använd samma nätverk eller exponera servern publikt

### "BAC authentication failed"
- Kontrollera att MRZ är korrekt inmatad
- Alla 44 tecken per rad måste vara rätt
- Använd VERSALER och < istället för mellanslag

### "HCE not working"
- Gå till Inställningar → NFC → Kontaktlösa betalningar
- Välj "NFC Emulator" som standard
- Se till att NFC är på

## 🌐 Hosting

För produktion, hosta servern med HTTPS:

```javascript
// Använd t.ex. Heroku, Railway, eller egen VPS
// Ändra server-URL till wss://din-domän.com
```

## 📜 Licens

MIT - Använd på egen risk. Författaren tar inget ansvar för missbruk.

---

**⚡ Built for learning purposes only ⚡**
