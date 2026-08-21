# 🎵 Voice-Enabled AI Music Player — Master Plan
### Codename: "Antigravity Player" (Poweramp Killer)

---

## 1. Vision & Scope

**Goal:** Ekta premium Android music player banano jeta:
- Poweramp-er cheye better Hi-Fi audio engine + Equalizer diবে
- YouTube backend theke gaan search/stream/download korte parbe (jodi local DB te na thake)
- Voice command diye control hobe ("play Tumi Robe Nirobe")
- User nijer LLM API key (Gemini / OpenAI / Claude / others) diye AI feature enable korte parbe (BYOK model — Bring Your Own Key)

**Approach:** 4 Phase e banano hobe. Age solid music player, tarpor upore AI layer bosano.

---

## 2. Tech Stack Decision

| Layer | Recommendation | Reason |
|---|---|---|
| Platform | **Native Android (Kotlin)** | Poweramp-level audio latency/control er jonno native must. Flutter/RN e low-level audio engine (ExoPlayer + AudioEffect chaining) control kora painful hoy. |
| Audio Engine | **Media3 (ExoPlayer)** + custom `AudioProcessor` chain | Google-er official, gapless playback, hi-res support, custom DSP inject kora jay |
| Equalizer/DSP | Android `AudioEffect`, `Equalizer`, `BassBoost`, `Virtualizer`, `LoudnessEnhancer` APIs + custom native DSP (C++ via NDK for advanced preset/EQ curve) | System-level EQ built-in, kintu true Hi-Fi feel er jonno custom 10/20-band parametric EQ banano lagbe |
| Local DB | **Room (SQLite)** | Song metadata, playlists, download cache |
| Background Playback | **MediaSessionService (Media3)** + Foreground Service | Notification controls, lock-screen, Android Auto ready |
| YT Backend | Self-hosted backend (Node.js/Python) using `yt-dlp` | Direct YT API teke audio extract kora ToS-wise risky, tai nijer backend server e yt-dlp diye extract kore app ke stream URL / file dibe |
| Voice Command | Android `SpeechRecognizer` (on-device) + fallback cloud STT | Offline command ("play", "pause", "next") + complex query LLM e pathano hobe |
| AI Layer (BYOK) | Direct client-side API call user-er nijer key diye (Gemini/OpenAI/Claude/Groq etc.) | Key kokhono tomar server e store hobe na — শুধু encrypted local (Android Keystore) e thakbe |
| UI | Jetpack Compose | Modern, smooth animation, custom EQ visualizer banano easy |

---

## 3. System Architecture (High-level)

```
[Android App]
   ├── Player Core (ExoPlayer + DSP Chain)
   ├── Local Library (Room DB) — scanned device songs
   ├── Voice Module (SpeechRecognizer → Intent Parser)
   ├── AI Orchestrator (BYOK: routes to Gemini/OpenAI/Claude based on user setting)
   │       └── decides: "gaan ache local e?" / "search YT?" / "just chat?"
   └── YT Bridge (API calls to your backend)
            │
            ▼
   [Your Backend Server] (Node.js/Python)
       ├── yt-dlp based extractor
       ├── Search endpoint (YT search → title/thumbnail/duration)
       ├── Stream endpoint (returns audio stream URL, no full download needed for online play)
       └── Download endpoint (extract + convert to file, send to app to cache locally)
```

**Flow example:** User voice e bole "Ayesha gaanta chalao"
1. SpeechRecognizer → text convert
2. AI Orchestrator (user-selected LLM) intent বুঝে: song name extract kore
3. Local DB e search → paile সরাসরি play
4. না পেলে → Backend-এ YT search request → user কে option দেখায় → select korle stream/download

---

## 4. Development Phases

### **Phase 1 — Core Music Player (MVP)**
- [ ] Project setup (Kotlin + Jetpack Compose)
- [ ] Device storage scan (MediaStore API) — sob local gaan list korbe
- [ ] Basic player UI (play/pause/seek/next/prev, queue, mini-player + full player)
- [ ] Playlist management (create/edit/delete)
- [ ] Background playback + notification controls (MediaSession)
- [ ] Album art fetch (embedded tag theke, na thakle fallback)
- **Output:** Ekta basic kintu smooth-running local music player

### **Phase 2 — Hi-Fi Audio + Equalizer**
- [ ] Custom 10-band Equalizer UI + engine
- [ ] Presets (Rock, Pop, Jazz, Bass Boost, Vocal, Flat, Custom)
- [ ] BassBoost, Virtualizer, Loudness Enhancer integration
- [ ] Replay Gain / volume normalization
- [ ] Hi-Res audio format support (FLAC, WAV, ALAC, DSD if possible)
- [ ] Crossfade + gapless playback
- **Output:** Audiophile-grade sound engine, Poweramp-comparable ba better

### **Phase 3 — YouTube Backend Integration**
- [ ] Backend server setup (yt-dlp based, hosted on VPS)
- [ ] Search API (query → results with thumbnail/title/duration)
- [ ] Stream API (direct online play without full download)
- [ ] Download API (extract audio, convert to mp3/m4a, send to app)
- [ ] App-side: "Not found locally → search YT" flow
- [ ] Local caching system (downloaded YT songs DB te add hobe, duplicate check)
- **Output:** App-e chaile jekono gaan paওয়া যাবে, local na thakleও

### **Phase 4 — Voice + AI Layer (BYOK System)**
- [ ] Settings page: "Add your AI Provider" (Gemini / OpenAI / Claude / Groq / others)
- [ ] Secure key storage (Android Keystore encryption, never sent to your server)
- [ ] Voice command capture (SpeechRecognizer, wake-word optional later)
- [ ] Intent parser (LLM prompt template: extract action + song/artist/mood)
- [ ] AI Orchestrator: route command → play/search/recommend/create playlist by mood
- [ ] Smart features: "mon kharap, emon gaan chalao" → mood-based playlist via LLM
- **Output:** Full voice-controlled AI music assistant, user's own API cost e chalbe

### **Phase 5 (Optional Polish)**
- [ ] Custom themes / dark-amoled UI
- [ ] Lyrics sync (LRC support)
- [ ] Sleep timer, tag editor
- [ ] Android Auto / Wear OS support
- [ ] Multi-language voice support (Bangla + English)

---

## 5. Important Considerations (Age theke জেনে রাখা ভালো)

1. **YouTube ToS risk:** yt-dlp দিয়ে audio extract/download YouTube-এর Terms of Service violate করে। Personal/educational use e generally issue hoy na, kintu public release korle (Play Store) risk ache — copyright strike/takedown hote pare. Backend nijer control e রাখলে risk কমে, কিন্তু zero na.
2. **BYOK security:** User-er API key Android Keystore-e encrypted store korte hobe, kokhono plaintext e na, ar tomar server-e kokhono log/store na kora — eta trust issue.
3. **Backend cost:** yt-dlp backend চালাতে VPS lagbe (bandwidth cost ache, especially download endpoint e).
4. **Storage:** Downloaded YT songs local storage e rakhle app size grow করবে — cache management (auto-clear old/unused) দরকার হবে।

---

## 6. Immediate Next Step

Ready to start **Phase 1**: Android project structure + basic player scaffold (ExoPlayer + MediaStore scan + basic UI).

Bolo, next step e Phase 1 er detailed file/module structure ar starter code banabo?
