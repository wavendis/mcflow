# Mc'Flow

So you won't have to spend a fiver on a bum ass mod. Big thanks to my boy Claude.

Synced lyrics for whatever you're listening to, shown inside Minecraft. Play a song in Spotify, YouTube, or any other app, and Mc'Flow picks it up, finds the lyrics and writes them in the sky word by word, in time with the music.

It's a client-side mod, so it runs on any server or in singleplayer. Nothing needs to be installed on the server.

| Version | Loader | Folder |
|---|---|---|
| Minecraft 26.3 | NeoForge 26.3.0.97+ | [`mod/`](mod) |
| Minecraft 1.21.11 | Fabric Loader 0.20.1+ with Fabric API | [`fabric/`](fabric) |

> **Windows only (for now).** Mc'Flow finds out what's playing and hears the beat through Windows features. On other systems the mod loads but shows nothing.

## Features

**Lyrics**
- **Automatic detection.** Whatever Windows' media controls report is used: Spotify, browsers (YouTube, SoundCloud, …), music apps. Song changes are noticed immediately.
- **Synced lyrics for almost any song** from [LRCLIB](https://lrclib.net), a free and open lyrics database. Messy video titles like `Katy Perry - California Gurls (Official Video) ft. Snoop Dogg` uploaded by some channel are cleaned up, and the version whose length matches the playing track is picked.
- **Word-by-word reveal.** Short words travel with the word after them ("in · the light", "I can't · go on"), punctuation never joins two sentences, and repeats like "bam-bam-bam" appear one at a time.
- **Timing offset** with the `[` and `]` keys if a video's intro throws the sync off.

**In the world**
- **Around you:** text is painted on a sphere around the player. It stays where it appeared when you turn, curves like writing on a dome and never flies past you.
- **Pinned:** each line is a flat sign standing in the world, seen in perspective. It bends gently around you as you walk up to it.
- **On screen:** fixed at a spot on the screen, like a classic HUD.
- **Block detection:** walls hide the text behind them; glass, ice and water let it through, dimmed and tinted (stained glass tints it its colour, water turns it blue).
- **Parallax:** the rows slide against each other as you move or look around.
- Words that have faded out stay gone, even if you look back.

**Music effects**
- **Beat detection** from the bass of what's actually playing. Nouns (or all words) pop and change colour on every beat, and sung sounds like *oh* and *ah* always do.
- A **now-playing pill** shows the title and artist, with an equalizer that moves to the music.

**Looks**
- Everything is drawn at your screen's real resolution with smooth, anti-aliased text, so none of Minecraft's pixel font. Handwritten [Caveat](https://github.com/googlefonts/caveat) is built in, and any font installed on your PC can be used.
- Fade in and out, word by word or whole lines; the old line slides up out of the way of the new one.
- Lyrics blur and dim while a menu (inventory, chest, …) is open.

## Settings

Press **`K`** in game, or use **Mods → Mc'Flow → Config** on NeoForge, to open the settings panel. The game keeps running behind it so every change shows live. If nothing with lyrics is playing, a demo line loops so you can see what you're changing.

| Section | What you can change |
|---|---|
| Lyrics | Font, colour, size, shadow, letter spacing, uppercase, word-by-word or whole-line reveal, fade in, fade out time and style, float-in / drift-away |
| Placement | Around you / Pinned / On screen; staggered, centered or one-line layout; which side the first row is on and the gap from the centre; left/centre/right; angle and height (or screen position); pin distance; warp; parallax; block detection |
| Menus | Blur and dim behind menus |
| Beat effects | Pulse on nouns, all words or off; strength; colour |
| Now playing pill | Show/hide, top/bottom, font, accent colour, background opacity, equalizer |
| Timing | Lyrics offset |

Settings are saved to `config/mcflow.json`.

**Keys**, all rebindable under Controls → Mc'Flow:

| Key | Action |
|---|---|
| `K` | Open settings |
| `]` | Lyrics earlier (+100 ms) |
| `[` | Lyrics later (−100 ms) |

## Installing

1. Install NeoForge for Minecraft 26.3, **or** Fabric Loader plus [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11.
2. Put `mcflow-<version>.jar` in your `mods` folder.
3. Play some music and join a world.

Works with shaders: the lyrics are drawn as an overlay, so Iris/Sodium and shader packs don't change how they look.

## How it works

| Part | File |
|---|---|
| What's playing: a small PowerShell script reads Windows' media session (title, artist, position) a few times a second | `assets/mcflow/nowplaying.ps1`, `NowPlaying.java` |
| The beat: a second script records what your speakers play (loopback, like screen recorders do), keeps only the bass, and reports its energy every 20 ms; a beat is a sudden jump | `assets/mcflow/audiolevel.ps1`, `Beat.java` |
| Lyrics: LRCLIB search, title clean-up, word timing and grouping | `Lyrics.java` |
| Nouns and *oh/ah* sounds: simple rules plus a word list, no language model | `Nouns.java` |
| Drawing: text and the pill are painted with Java2D and uploaded as smooth textures; each letter is placed and projected per frame | `TextArt.java`, `GpuImage.java`, `LyricsHud.java` |
| What's in the way: walks the blocks along the line of sight | `Occlusion.java` |
| Settings panel | `SettingsPanel.java`, `SettingsScreen.java`, `Settings.java` |

The two builds share almost all their code; only the screen, HUD hooks and entry point differ between loaders.

### Privacy

- The **song title, artist and length** are sent to [lrclib.net](https://lrclib.net) to look up lyrics. Nothing else leaves your PC.
- The audio capture for beat detection stays on your PC. It only measures how loud the bass is and is never recorded or sent anywhere.
- The two helper scripts are written to `config/mcflow/` and run with PowerShell while the game is open. They close when Minecraft does.

### Known limits

- Windows only.
- Text isn't hidden by blocks unless block detection is on. Even with it on, the text is drawn as an overlay rather than as a real object in the world.
- Word timing within a line is estimated, because LRCLIB only has timings per line.
- Songs that aren't on LRCLIB, or have no synced lyrics there, show only the pill.
- Beat detection listens to the whole output device, so very loud bassy game sounds can trigger a pulse.
- Antivirus software with aggressive script blocking can stop the PowerShell helpers. If nothing shows up, check its logs.

## Building

- **NeoForge (`mod/`):** any JDK 21+ can run Gradle; it downloads Java 25 for compiling by itself.
- **Fabric (`fabric/`):** run Gradle with JDK 25, because the Fabric build tooling requires it.

```bash
# NeoForge 26.3
cd mod && ./gradlew build          # -> mod/build/libs/mcflow-1.0.0.jar
./gradlew runClient                # test client

# Fabric 1.21.11
cd fabric && ./gradlew build       # -> fabric/build/libs/mcflow-1.0.0.jar
./gradlew runClient                # test client (includes Iris + Sodium for shader testing)
```

Each module has a quick self-check you can run without the game, for example:

```bash
java -ea -cp build/classes/java/main;<gson.jar> dev.mcflow.Lyrics   # also Nouns, Beat
```

## Credits

- Lyrics from [LRCLIB](https://lrclib.net).
- [Caveat](https://github.com/googlefonts/caveat) font, SIL Open Font License 1.1 (see `OFL-Caveat.txt`).
- Built on [NeoForge](https://neoforged.net) and [Fabric](https://fabricmc.net).

## License

[MIT](LICENSE). The bundled Caveat font keeps its own SIL Open Font License (`OFL-Caveat.txt`).
