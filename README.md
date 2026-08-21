# Antigravity Player 🎵

Voice-enabled, AI-integrated, Hi-Fi Android music player.

## Structure

```
AntigravityPlayer/
├── app/                     -> Android app module (Kotlin + Jetpack Compose)
│   └── src/main/
│       ├── java/com/tensorix/antigravityplayer/
│       │   ├── player/      -> ExoPlayer engine, EQ/DSP, MediaSession
│       │   ├── data/        -> Room DB, repositories, models
│       │   ├── ui/          -> Jetpack Compose screens
│       │   ├── voice/       -> SpeechRecognizer, intent parsing
│       │   ├── ai/          -> BYOK LLM orchestrator (Gemini/OpenAI/Claude)
│       │   └── util/        -> helpers, extensions
│       └── res/             -> layouts, drawables, values
├── backend/                 -> Node.js YT extraction server (Phase 3)
├── docs/                    -> Project plan, architecture notes
├── build.gradle.kts
├── settings.gradle.kts
└── .gitignore
```

## Phase Status
- [x] Phase 1 — Core Music Player (Completed)
- [x] Phase 2 — Hi-Fi Audio + Equalizer (Completed)
- [ ] Phase 3 — YouTube Backend
- [ ] Phase 4 — Voice + AI (BYOK)
- [ ] Phase 5 — Polish

See `music-player-plan.md` for the full master plan.

## How to Test in Android Studio
1. Open **Android Studio**.
2. Click **Open** (or `File -> Open`) and select the project folder: `c:\Code\AntigravityPlayer\AntigravityPlayer`.
3. Android Studio will detect Gradle configuration, sync dependencies, and index the Kotlin/Compose sources.
4. Select an Android Emulator or connected physical device (API 26+).
5. Click the green **Run 'app'** button (or press `Shift + F10`).

## Notes
- Backend (`/backend`) is separate — run `npm start` inside `backend/` for Phase 3 API services.
- API keys (Gemini/OpenAI/Claude) are BYOK — stored securely via Android Keystore at runtime.

