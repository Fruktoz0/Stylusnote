# StylusNote – Megvalósítási Haladáskövető (Implementation Progress)

Ez a dokumentum a `stylusnote_rendszerterv_v2.txt` alapján készült **17 lépéses (Lépés 0 – Lépés 16)** ütemterv élő állapotnaplója. Minden szakasz befejezésekor frissül az elkészült elemek pipálásával és az **Aktuális Átadási Állapottal**, hogy a következő lépés pontosan onnan folytatódhasson, ahol az előző megállt.

---

## 1. Aktuális Átadási Állapot (Current Handoff State)

- **Utolsó befejezett lépés:** `Lépés 112 (XV. Ütem: 7 Napos Felhős Lomtár & Visszaállítás, Kategória Drive-Mappa PDF Export & Törlési Figyelmeztetés, Focus Pen Pro Radiális Kör-Menü)`
- **Befejezés dátuma:** `2026-11-27`
- **Aktuális projektállapot:**
  - Az **I–XV. Ütemek (`Lépés 0–112`)** teljes egészében implementálva és verifikálva lettek.
  - `.\gradlew testDebugUnitTest --no-daemon`: **155/155** JVM unit teszt sikeres **30** tesztosztályban (`0` hiba, `0` errors, `0` kihagyás).
  - `.\gradlew :app:connectedDebugAndroidTest --no-daemon` a fizikai Xiaomi Pad 8 (`886223b2` / `25097RP43G`, Android 17) táblagépen: **45/45** műszerezett teszt sikeres (`0` hiba, `0` errors, `0` kihagyás — benne a XV. fázis eszközös és `MIGRATION_4_5` migrációs validációjával; Espresso `3.7.0` Android 17 támogatással).
  - `.\gradlew :app:installDebug --no-daemon` sikeresen települt az eszközre, a hidegindítás (`TotalTime: 745 ms`, `Status: ok`, `LaunchState: COLD`) és a `logcat -d -b crash` tiszta.
- **Következő végrehajtandó lépés:** Kész a felhasználói kézi tesztelésre a táblagépen (`886223b2`).

---

## IV. Ütem (Lépés 32 – Lépés 38) — StarNote Finomhangolás, Tenyérvédelem, Papírhoz Rögzített Objektumok & Képbeszúrás

| Lépés | Leírás | Státusz | Érintett főbb fájlok |
|---|---|---|---|
| **Lépés 32** | Tenyérvédelem (palm-rest / kézfelemelés utáni 650 ms grace-window + hover-zár + `touchMajor`/slop szűrés), csak fel-le 1 ujjas pan (`dx = 0f`) & lebegő tollpötty (`HoverCursorOverlay`) kikapcsolása | `[x]` Kész | `StylusInputRouter.kt`, `StylusDrawingSurface.kt`, `StylusInputRouterTest.kt` |
| **Lépés 33** | Beszúrt alakzatok és objektumok (`PageObject`) papírhoz rögzítése pan/zoom közben (`viewportTransformTick` szinkron, 0-overhead üres objektumlistánál) | `[x]` Kész | `LatexOverlayNode.kt`, `StylusDrawingSurface.kt`, `BookshelfScreen.kt` |
| **Lépés 34** | Alakzatok menü (`ShapePickerPopover`) átrendezése (1. oldal egyszerű 2D, 2. oldal 3D) + `HorizontalPager` animáció & Ecsetmenü bal alsó sarok-jelölés (`◢`) | `[x]` Kész | `PageObject.kt`, `ShapePickerPopover.kt`, `BrushSettingsPopover.kt` |
| **Lépés 35** | AI formázás utáni visszaállítás eredeti kézírásra (`originalStrokes` mentés, `SNOT` perzisztencia, `"Eredeti"` gomb a kijelölés menüben) | `[x]` Kész | `PageObject.kt`, `StrokeBinarySerializer.kt`, `CanvasStateHolder.kt`, `LatexOverlayNode.kt`, `StrokeJournalRoundTripTest.kt` |
| **Lépés 36** | Képbeszúrás (`PageObject.ImageObject`) az eszköztárról, papírhoz rögzítve, méretezéssel, forgatással, `SNOT` & PDF export támogatással | `[x]` Kész | `PageObject.kt`, `StrokeBinarySerializer.kt`, `LatexOverlayNode.kt`, `PdfExportManager.kt`, `BookshelfScreen.kt` |
| **Lépés 37** | StarNote színes eszköztár ikonok (`IMG_2724.heic`) & `OCR` gomb eltávolítása, `Kép beszúrása` bekötése | `[x]` Kész | `StylusIcons.kt`, `FloatingToolDock.kt`, `BookshelfScreen.kt` |
| **Lépés 38** | Végső verifikáció (`testDebugUnitTest` 71/71 ✅, `connectedDebugAndroidTest` 8/8 ✅, 144 Hz Macrobenchmark `P95 = 5,95 ms ≤ 6,94 ms` ✅), dokumentáció és APK telepítés (`25097RP43G`) | `[x]` Kész | `docs/*`, `25097RP43G` |

---

## III. Ütem: Vászonfinomítás, Független Eszközmemória, Többoldalas Navigáció, Alakzatok & Füzet OCR (Lépés 25 – Lépés 31)

| Lépés | Leírás | Státusz | Érintett főbb fájlok |
|---|---|---|---|
| **Lépés 25** | 1 ujjas pan, normalizált `100%` nagyítás, bal felső sarokba zárt `CanvasTopToolbar` & Teljes képernyő kapcsoló | `[x]` Kész | `StylusInputRouter.kt`, `CanvasTransformMatrix.kt`, `BookshelfScreen.kt`, `FloatingToolDock.kt` |
| **Lépés 26** | AI 60s olvasási timeout + multi-Flash failover, nem-sticky `FormattedTextObject` kijelölés & DPI-helyes szövegméretezés | `[x]` Kész | `GeminiClient.kt`, `ModelCatalog.kt`, `LatexOverlayNode.kt`, `BookshelfScreen.kt` |
| **Lépés 27** | Golyóstoll/Ceruza elrejtése, eszközönkénti memória (`perToolConfigs`), Töltőtoll `1/2/4/8 pt` & rögzített kompakt `BrushSettingsPopover` | `[x]` Kész | `ToolDockPreferencesStore.kt`, `FloatingToolDock.kt`, `BrushSettingsPopover.kt`, `BookshelfScreen.kt` |
| **Lépés 28** | Többoldalas függőleges lapelőnézet + 1 ujjas átgördülés, függőlegesen húzható `LeftPagePullTab`, Drag & Drop oldalátrendezés | `[x]` Kész | `PaperBackgroundLayer.kt`, `LeftPageDrawer.kt`, `AppDatabase.kt`, `BookshelfScreen.kt` |
| **Lépés 29** | `GRID_SIMPLE_5MM` négyzetháló, vizuális előnézetes `PaperSettingsDialog` & `"PDF import"` gomb elrendezés javítása | `[x]` Kész | `PaperTemplate.kt`, `PaperBackgroundLayer.kt`, `PaperSettingsDialog.kt`, `BookshelfScreen.kt` |
| **Lépés 30** | Lasszó sarok-forgatás, füzet-szintű inkrementális `OCR` gomb, `ShapePickerPopover` + `GeometricShapeObject` (2D/3D, SNOT `tag=6`, PDF export) | `[x]` Kész | `StylusDrawingSurface.kt`, `PageObject.kt`, `GeometricShapeRenderer.kt`, `ShapePickerPopover.kt`, `LatexOverlayNode.kt`, `StrokeBinarySerializer.kt`, `PdfExportManager.kt` |
| **Lépés 31** | Perfetto-alapú 144 Hz finomhangolás, `69/69` unit + `8/8` androidTest, Macrobenchmark (`P95 = 6,05 ms`), docs & APK telepítés | `[x]` Kész | `StylusDrawingSurface.kt`, `PaperBackgroundLayer.kt`, `BookshelfScreen.kt`, `docs/*` |

---

## II. Ütem: StarNote UI/UX, Eszköztár, PDF/NotebookLM és AI Megújítás (Lépés 18 – Lépés 24)

| Lépés | Leírás | Státusz | Érintett főbb fájlok |
|---|---|---|---|
| **Lépés 18** | StarNote design rendszer, egyedi ikonok, valódi teljes képernyő + Lézer / Függvényrajzoló / Osztott nézet eltávolítása | `[x]` Kész | `Theme.kt`, `StylusIcons.kt`, `MainActivity.kt`, `BrushConfig.kt`, `FormatMigrator.kt`, `StylusDrawingSurface.kt`, `LatexOverlayNode.kt`, `PdfExportManager.kt`, `BookshelfScreen.kt` |
| **Lépés 19** | Könyvespolc teljes StarNote átalakítás, bal oldalsáv, kategória Drag & Drop, új füzet & borítótervező UI | `[x]` Kész | `NotebookCover.kt`, `CoverDesignerDialog.kt`, `BookshelfScreen.kt`, `AppDatabase.kt` |
| **Lépés 20** | Könyv `...` menü, szelektív PDF import (oldalszám/tartomány) és NotebookLM megosztás / export | `[x]` Kész | `AndroidManifest.xml`, `file_paths.xml`, `BookshelfScreen.kt`, `PdfAndNotebookLmDialogs.kt`, `PageSelectionParserTest.kt` |
| **Lépés 21** | Nyitott füzet bal felső fejléc sáv, bal oldali kihúzható oldalszerkesztő (Fix oldalmód) és zoom-out korlát (`0.65f`) | `[x]` Kész | `CanvasTransformMatrix.kt`, `CanvasTransformMatrixTest.kt`, `BookshelfScreen.kt`, `LeftPageDrawer.kt` |
| **Lépés 22** | Globális, szabadon mozgatható lebegő eszköztár, összecsukott 3 gomb + 2 lépcsős hosszan nyomható beállító fül | `[x]` Kész | `ToolDockPreferencesStore.kt`, `FloatingToolDock.kt`, `BrushSettingsPopover.kt`, `BookshelfScreen.kt` |
| **Lépés 23** | Lasszó szellemkép megszüntetése, Lasszó `AI` gomb + AI mód egyszerűsítése (Beállítások + 2 formázó funkció) | `[x]` Kész | `DryInkRasterCache.kt`, `StylusDrawingSurface.kt`, `PageObject.kt`, `StrokeBinarySerializer.kt`, `LatexOverlayNode.kt`, `PdfExportManager.kt`, `AiCopilotSheet.kt` |
| **Lépés 24** | Végleges tesztek, 144 Hz benchmark (`p95 ≤ 6,94 ms`), dokumentáció frissítés (`feature_registry/log/journeys`) és APK telepítés | `[x]` Kész | `docs/*`, unit / androidTest / benchmark, `25097RP43G` |

## 2. Részletes Megvalósítási Ellenőrzőlista (Lépés 0 – Lépés 16)

### Lépés 0 – Projekt-dokumentáció és Haladáskövető Rendszer
- [x] `docs/implementation_progress.md` – Élő check-mezős haladáskövető és átadási napló
- [x] `docs/feature_registry.md` – Funkciók nyilvántartása (Modul A–D)
- [x] `docs/feature_log.md` – Architektúra Mermaid diagram és mérföldkő napló
- [x] `docs/user_journeys.md` – Fő felhasználói folyamatok Mermaid diagramokkal

### 1. FÁZIS – Alapok és Rajzmotor (`1a – 1c`)

#### Lépés 1 (`Alfázis 1a – 1. rész`): Gradle alapok, Version Catalog és App váz
- [x] `.gitignore`
- [x] `settings.gradle.kts` (`google()` + `mavenCentral()`, `:app`, `:benchmark`)
- [x] `gradle.properties`
- [x] `gradle/libs.versions.toml` (rögzített verziók: Ink `1.1.0-alpha10`, MotionPrediction `1.0.0`, Room `2.8.5`, WorkManager `2.12.0`, DataStore `1.3.0`, ML Kit `19.0.0`, JLaTeXMath `0.2.0`, OkHttp `4.12.0`)
- [x] `build.gradle.kts` (root)
- [x] `app/build.gradle.kts` (KSP Room schema export, Compose, összes függőség)
- [x] `benchmark/build.gradle.kts` és `benchmark/src/main/AndroidManifest.xml`
- [x] `app/src/main/AndroidManifest.xml`
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/theme/Theme.kt` (Light, Dark, OLED Deep Black)

#### Lépés 2 (`Alfázis 1a – 2. rész`): 144 Hz Display Lock, Debug Overlay és `CanvasTransformMatrix`
- [x] `app/src/main/java/hu/bdev/stylusnote/core/math/CanvasTransformMatrix.kt`
- [x] `app/src/main/java/hu/bdev/stylusnote/MainActivity.kt` (144 Hz lock, API 35+ frame rate hint, `Choreographer` FPS/p95 overlay, Focus Pen Pro gombhook, üres vászon keret)
- [x] `app/src/test/java/hu/bdev/stylusnote/core/math/CanvasTransformMatrixTest.kt`

#### Lépés 3 (`Alfázis 1b – 1. rész`): `OneEuroFilter`, `SpatialIndex` és Unit tesztjeik
- [x] `app/src/main/java/hu/bdev/stylusnote/core/math/OneEuroFilter.kt` (0–100% leképezés `minCutoff`/`beta`-ra dokumentálva)
- [x] `app/src/main/java/hu/bdev/stylusnote/core/math/SpatialIndex.kt` (R-fa / QuadTree `insert`, `remove`, `update`, `queryRect`, `queryPolygon`)
- [x] `app/src/test/java/hu/bdev/stylusnote/core/math/OneEuroFilterTest.kt`
- [x] `app/src/test/java/hu/bdev/stylusnote/core/math/SpatialIndexTest.kt` (brute-force összevetéssel)

#### Lépés 4 (`Alfázis 1b – 2. rész`): `StylusInputRouter`
- [x] `app/src/main/java/hu/bdev/stylusnote/core/hardware/StylusInputRouter.kt` (szigorú palm rejection, 0% vs. >0% historical batch + 1€, nyers `MotionEventPredictor` + `finally { recycle() }`, nyomásgörbe $p' = p^\gamma$, kétujjas navigáció tiltása aktív vonás alatt, hover & pen button routing)
- [x] `app/src/test/java/hu/bdev/stylusnote/core/hardware/StylusInputRouterTest.kt`

#### Lépés 5 (`Alfázis 1c`): `DryInkRasterCache`, `HoverCursorOverlay` és `StylusDrawingSurface`
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/canvas/DryInkRasterCache.kt` (512×512 px csempék, 192 MB LRU, 100 ms debounce újrarenderelés)
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/canvas/HoverCursorOverlay.kt` (1 px körvonal, ecsetméret × zoom)
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/canvas/StylusDrawingSurface.kt` (`InProgressStrokesView`, `motionEventToViewTransform` szinkron, villogásmentes wet→dry átadás szerződéses interfészekkel)
- [x] `app/src/main/java/hu/bdev/stylusnote/MainActivity.kt` (1c rajzfelület integrálása)
- [x] `app/src/test/java/hu/bdev/stylusnote/ui/canvas/DryInkRasterCacheTest.kt`

---

### 2. FÁZIS – Ecsetek, Radírok, Lasszó, UI (`2a – 2c`)

#### Lépés 6 (`Alfázis 2a`): Domain modellek és `CanvasStateHolder`
- [x] `app/src/main/java/hu/bdev/stylusnote/domain/model/BrushConfig.kt` (Töltőtoll, Golyóstoll, Ceruza `BrushFamily` tilt, Highlighter `SelfOverlap.DISCARD`, Lézer)
- [x] `app/src/main/java/hu/bdev/stylusnote/domain/model/CanvasAction.kt` (`AddedStrokes`, `RemovedStrokes` teljes `Stroke`-okkal, `TransformedStrokes`, `RecoloredStrokes`, `CompositeAction`)
- [x] `app/src/main/java/hu/bdev/stylusnote/domain/repository/CanvasStateHolder.kt` (50+ lépéses + pontkeretes delta Undo/Redo, Vektoros radír, Precíziós radír `@WorkerThread` szegmens–kör vágás interpolált határpontokkal)
- [x] `app/src/test/java/hu/bdev/stylusnote/domain/CanvasStateHolderTest.kt` (4 tiszta Kotlin teszt lefutott; az 5 natív Ink JNI teszt áthelyezve `androidTest`-be)

#### Lépés 7 (`Alfázis 2b`): Lebegő eszköztár és Lézer réteg
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/components/FloatingToolDock.kt` (áthelyezhető, dokkolható fent/balra/jobbra)
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/components/BrushSettingsPopover.kt` (HSV színkerék, HEX, 6 DataStore preset, ESC/backdrop click)
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/canvas/LaserOverlay.kt` (1500 ms fade-out `ACTION_UP` után, Undo/fájl mentes)
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/canvas/StylusDrawingSurface.kt` & `MainActivity.kt` (radír `@WorkerThread` csatorna, lézer és Focus Pen Pro gomb integrálása)

#### Lépés 8 (`Alfázis 2c`): `ShapeRecognizer`, Lasszó, Papírsablonok és OLED Inverzió
- [x] `app/src/main/java/hu/bdev/stylusnote/core/math/ShapeRecognizer.kt` (Hold-to-Snap 300 ms, `< 4 pt`, `> 40 pt`, `ShapeMeta`)
- [x] `app/src/main/java/hu/bdev/stylusnote/core/math/ColorLuminanceInverter.kt` (HSL $L_{\text{new}} = 1.0 - L_{\text{old}}$)
- [x] `app/src/main/java/hu/bdev/stylusnote/domain/model/PaperTemplate.kt`
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/canvas/PaperBackgroundLayer.kt` és `ui/components/PaperSettingsDialog.kt` (6 sablon, Fix/Végtelen vászon)
- [x] Lasszó kijelölés (60% poligon-bennfoglaltság, élő transzformáció) integrálása + `ShapeRecognizerTest.kt`

---

### 3. FÁZIS – Adatbázis, Fájlformátum, Könyvespolc, PDF (`3a – 3c`)

#### Lépés 9 (`Alfázis 3a – 1. rész`): `SNOT` Bináris Konténer, `StrokeJournal` és Migrátor
- [x] `app/src/main/java/hu/bdev/stylusnote/domain/model/PageObject.kt`
- [x] `app/src/main/java/hu/bdev/stylusnote/data/local/PageFileFormat.kt` (`SNOT` magic, CRC32 rekordok)
- [x] `app/src/main/java/hu/bdev/stylusnote/data/local/StrokeBinarySerializer.kt` (`ink-storage` + `BrushFamily` fallback)
- [x] `app/src/main/java/hu/bdev/stylusnote/data/local/StrokeJournal.kt` (egyszálú író, ≤1s `fsync`, `ATOMIC_MOVE` kompaktálás, crash recovery)
- [x] `app/src/main/java/hu/bdev/stylusnote/data/local/FormatMigrator.kt` + round-trip unit teszt (`StrokeJournalRoundTripTest.kt`)

#### Lépés 10 (`Alfázis 3a – 2. rész`): Room Adatbázis és FTS4
- [x] `app/src/main/java/hu/bdev/stylusnote/domain/model/NotebookCover.kt`
- [x] `app/src/main/java/hu/bdev/stylusnote/data/local/AppDatabase.kt` (`Folder`, `Notebook`, `Chapter`, `Page`, `Tag`, `Bookmark`, `PageText` `@Fts4`, `exportSchema = true`) + `FtsAccentInsensitiveTest.kt`

#### Lépés 11 (`Alfázis 3b`): Könyvespolc, Borító Stúdió és Split-Screen
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/shelf/BookshelfScreen.kt` (mappa-fa, keresés, címkék, könyvjelzők, Split-Screen független példányokkal + `MainActivity.kt` navigáció)
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/shelf/CoverDesignerDialog.kt` (textúrák, galéria import, gerinc, ESC/backdrop click)

#### Lépés 12 (`Alfázis 3c`): PDF Import/Export és Backup
- [x] `app/src/main/java/hu/bdev/stylusnote/data/export/PdfTileRenderer.kt` (`Mutex`-védett `PdfRenderer`, jelszóvédelem hiba, OLED inverzió)
- [x] `app/src/main/java/hu/bdev/stylusnote/data/export/PdfExportManager.kt` (`PartitionedMesh` outline → `Path`, highlighter `saveLayerAlpha`, világos színek)
- [x] `app/src/main/java/hu/bdev/stylusnote/data/export/BackupManager.kt` (`.zip` manifest, SAF/Drive, WebDAV, `BackupAndExportTest.kt` – 3/3 sikeres)

---

### 4. FÁZIS – AI, OCR, Matek (`4a – 4d`)

#### Lépés 13 (`Alfázis 4a`): `AiConsentStore`, Keystore BYOK, `ModelCatalog`, `GeminiClient`
- [x] `app/src/main/java/hu/bdev/stylusnote/core/ai/AiConsentStore.kt` és `ui/components/ConsentDialog.kt`
- [x] `app/src/main/java/hu/bdev/stylusnote/core/ai/ModelCatalog.kt` (`/v1beta/models`, 404 kezelés)
- [x] `app/src/main/java/hu/bdev/stylusnote/core/ai/GeminiClient.kt` (Keystore AES-GCM + DataStore, rate limiter, 429 backoff, `GeminiClientAndCatalogTest.kt` – 3/3 sikeres)

#### Lépés 14 (`Alfázis 4b`): Offline Háttér-OCR
- [x] `app/src/main/java/hu/bdev/stylusnote/core/ai/OcrLineSegmenter.kt`
- [x] `app/src/main/java/hu/bdev/stylusnote/core/ai/BackgroundOcrWorker.kt` (`RemoteModelManager` `hu`/`en-US`, WorkManager → `PageText` FTS4)
- [x] `app/src/test/java/hu/bdev/stylusnote/core/ai/OcrLineSegmenterTest.kt` (2/2 sikeres)

#### Lépés 15 (`Alfázis 4c`): Matek és STEM Eszközök
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/canvas/LatexOverlayNode.kt` (Gemini JSON + natív `jlatexmath-android` validálás + `CompositeAction` csere + levezetés kártya)
- [x] `app/src/main/java/hu/bdev/stylusnote/core/ai/FunctionEvaluator.kt` (rekurzív-leszálló parser, adaptív mintavételezés, szakadási pontok, vektoros grafikon, `FunctionEvaluatorTest.kt` – 3/3 sikeres)

#### Lépés 16 (`Alfázis 4d` + Tesztek & Benchmark): `AiCopilotSheet`, `androidTest`, `benchmark`
- [x] `app/src/main/java/hu/bdev/stylusnote/ui/components/AiCopilotSheet.kt` (kézírás→Markdown, Magic Circle, Vizsgamód, `AiCopilotExamParserTest.kt` 2/2 sikeres + `BookshelfScreen.kt` bekötés)
- [x] `app/src/androidTest/java/hu/bdev/stylusnote/StylusNoteInstrumentedValidationTest.kt` (8/8 sikeres `25097RP43G` tableten: FTS4 ékezet-érzéketlen keresés, `StrokeJournal` crash-recovery, 400% zoom 1 px teszt + 5 `androidx.ink` `libink.so` JNI teszt)
- [x] `benchmark/src/main/java/hu/bdev/stylusnote/benchmark/CanvasFrameTimingBenchmark.kt` (`FrameTimingMetric` lefutott `25097RP43G` tableten, 1/1 sikeres tesztfutás: `frameDurationCpuMs` P50 = `10,72 ms`, P95 = `21,22 ms`, P99 = `48,73 ms`; `app/src/benchmark/AndroidManifest.xml` `<profileable>` + `benchmarkMacroJunit4 = "1.4.1"`)

---

## 3. Elfogadási Kritériumok (11. Fejezet)

- [x] **Frame time:** p95 ≤ 6,94 ms pan/zoom közben 10 000 vonalas oldalon Xiaomi Pad 8-on *(Mérve `25097RP43G` tableten a `panAndZoom10kStrokesCanvasFrameTiming` 5-iterációs 10 000 vonalas benchmarkkal a VI. ütem végezetén: `frameDurationCpuMs` **P50 = `4,55 ms`**, **P90 = `5,88 ms`**, **P95 = `6,48 ms`**, **P99 = `9,03 ms`**; `frameOverrunMs` **P50 = `-7,05 ms`**, **P90 = `-5,73 ms`**, **P95 = `-5,34 ms`**, **P99 = `-2,22 ms`**, medián `119,0` képkocka ✅)*
- [ ] **Pontosság:** Tollhegy–tinta eltérés 400%-os nagyításnál ≤ 1 px *(Alap: `CanvasTransformMatrix` 400% teszt 10/10 `connectedDebugAndroidTest`-ben sikeres, `concat(matrix)` csempejavítás alkalmazva; kézi stylus / vizuális próba hátravan)*
- [ ] **Memória:** 50 000 vonallal sincs OOM; raszter cache ≤ 192 MB *(Készüléken ellenőrzendő)*
- [ ] **Betöltés:** 10 000 vonalas oldal megnyitása ≤ 1 s *(Készüléken ellenőrzendő)*
- [ ] **Adatbiztonság:** Automatizált process-kill teszt rajzolás közben ≤ 1 s adatvesztéssel, hibátlan journal visszajátszás *(`StrokeJournal` truncated-tail recovery `connectedDebugAndroidTest`-ben sikeres; élő UI process-kill próba hátravan)*
- [ ] **Round-trip:** Serializer, journal és backup/restore után azonos vonalak *(JVM `StrokeJournalRoundTripTest` 7/7 és `BackupAndExportTest` 3/3 sikeres; eszközön történő integrációs ellenőrzés hátravan)*
- [x] **JVM Unit tesztek (`testDebugUnitTest` — 19 osztály, 95 teszt, 0 hiba, 0 kihagyás):**
  - [x] `CanvasTransformMatrixTest` (7)
  - [x] `OneEuroFilterTest` (6)
  - [x] `SpatialIndexTest` (4, brute-force összevetés)
  - [x] `StylusInputRouterTest` (12)
  - [x] `DryInkRasterCacheTest` (3)
  - [x] `ShapeRecognizerTest` (5)
  - [x] `CanvasStateHolderTest` (6 tiszta Kotlin teszt; az 5 natív `libink.so` JNI teszt áthelyezve `StylusNoteInstrumentedValidationTest`-be)
  - [x] `StrokeJournalRoundTripTest` (7)
  - [x] `FtsAccentInsensitiveTest` (3)
  - [x] `BackupAndExportTest` (6)
  - [x] `CloudSyncEngineTest` (3)
  - [x] `CloudSyncSchedulerTest` (4)
  - [x] `GeminiClientAndCatalogTest` (4)
  - [x] `OcrLineSegmenterTest` (2)
  - [x] `FunctionEvaluatorTest` (3)
  - [x] `AiCopilotExamParserTest` (2)
  - [x] `ToolDockPlacementTest` (6)
  - [x] `PageSelectionParserTest` (4)
  - [x] `FixedPageScrollNavigationTest` (8)
- [x] **Műszerezett (`androidTest`) tesztek (`:app:connectedDebugAndroidTest`):** 13/13 teszt sikeres (`TEST-25097RP43G - 16-_app-.xml`, `0` hiba, `0` kihagyás, benne az 5 natív `libink.so` Ink JNI teszttel, a `Room` migrációs tesztekkel, az `@Upsert` kaszkád-regressziós teszttel és a `CloudSyncUiAndDatabaseInstrumentedTest` Room v3 + UI tesztekkel)
- [x] **V. ÜTEM – Lépés 40–47 (nyomonkövetés):**
  - [x] Lépés 40 – Diagnózis tableten (új oldal `@Upsert`/`CASCADE` fix ellenőrizve `2/2` oldallal, tenyér + késő tinta kiváltó okai azonosítva)
  - [x] Lépés 41 – Új oldal javítás + oldalnevek (`PageEntity.title`, `Migration(1,2)`, átnevezés a fiókban, `Room` v2 séma)
  - [x] Lépés 42 – Tenyérvédelem (`FLAG_CANCELED`/palm, pan visszavonás) + azonnali nedves tinta (`eagerInit()`)
  - [x] Lépés 43 – Alakzat/kép/AI-blokk alapból nem interaktív (toll átír rajtuk; `PageObjectsOverlayLayer` pass-through + `DerivationCardNode` `Surface`-mentes konténer)
  - [x] Lépés 44 – Lasszó objektum-kijelölés (egyesített `CanvasStateHolder` `transformSelection`/`duplicateSelection`/`deleteSelection`/`recolorSelection` + élő előnézet + egykezes kijelölésnél azonnali sarok-méretezés/forgatás/`"Eredeti"`)
  - [x] Lépés 45 – Folyamatosan széthúzható ecsetpaletta (`Animatable` `312..472 dp` + `progressiveStudioReveal`), dinamikus külső alsó sarok-fogantyú
  - [x] Lépés 46 – Vektoros adaptív app ikon (1-es verzió: Swiss töltőtollhegy & tintaív `ic_launcher_*` vektorok + manifest bekötés)
  - [x] Lépés 47 – Verifikáció (`85/85` unit, `10/10` androidTest, `P95 = 6,47 ms` 144 Hz benchmark, APK telepítés + ikon vizuális ellenőrzés) & dokumentáció frissítve
- [x] **VI. ÜTEM – Lépés 48–52 (Felhőszinkron & Biztonsági Mentés nyomonkövetés):**
  - [x] Lépés 48 – `BackupManager v2` (`PageEntity.title` + FTS + tombstones) & `Room v3` (`sync_tombstones`, `cloud_synced_assets`, `MIGRATION_2_3`)
  - [x] Lépés 49 – `CloudSyncBackend` (Google Drive REST v3 `drive.file` + Play Services `AuthorizationClient` & WebDAV) + `CloudSyncEngine` (inkrementális delta feltöltés/letöltés)
  - [x] Lépés 50 – `CloudSyncScheduler` & `CloudSyncWorker` („Okos automata”: oldal/füzetváltás + `onStop` + 3 perces nyugalmi idő + választható 30p/kézi/Wi-Fi módok)
  - [x] Lépés 51 – Felhő UI integráció (`CanvasTopToolbar` füzetnév melletti felhő ikon hosszan nyitható beállító menüvel, Könyvespolc fejléc-visszaállítás, `.zip` SAF export/import)
  - [x] Lépés 52 – Teljes verifikáció (`95/95` unit, `13/13` androidTest, `P95 = 6,48 ms` 144 Hz benchmark, `installDebug` + tabletes dialógus/ESC vizuális ellenőrzés) + dokumentáció & Google Cloud OAuth beállítási útmutató
- [x] **VII. ÜTEM – Lépés 53–58 (AI Sebesség/Pontosság/Undo, Jegyzetbeli Kereső & Vonalas Sablon nyomonkövetés):**
  - [x] Lépés 53 – Vonalas füzet (`RULED_8MM`) bal oldali piros függőleges vonal eltávolítása (`PaperBackgroundRenderer` + `PaperSettingsDialog`)
  - [x] Lépés 54 – Undo / Redo tartós `StrokeJournal` szinkronizálása (bezárás és újranyitás után is megmaradó AI/kézírás visszavonás)
  - [x] Lépés 55 – Gyorsabb AI átalakítás (`thinkingBudget=0`, `1024 px`), pontos irányított nyilak (`startPoint`/`endPoint`), firka-kihagyás & félbehagyott szavak kiegészítése
  - [x] Lépés 56 – Élő kézírás- és objektumkereső motor (`InNotebookSearchCoordinator`: ML Kit Digital Ink + `FormattedTextObject`/`Latex`/cím + `DocRect` horgonyok)
  - [x] Lépés 57 – Jegyzetbeli keresősáv a `CanvasTopToolbar`-ban az `AI` gomb mellett (`1/N` léptetés, lapváltás, vászon-kiemelés & pan)
  - [x] Lépés 58 – Végső verifikáció (`104/104` unit [21 osztály], `17/17` androidTest `TEST-25097RP43G - 16-_app-.xml`, 144 Hz benchmark `P50 = 4,75 ms / P90 = 6,05 ms / P95 = 6,50 ms`, `frameOverrunMs P95 = -5,17 ms`, `installDebug` + tabletes `Vonalas (8 mm)` & `Keresés` vizuális ellenőrzés) & dokumentáció frissítve
- [x] **Lépés 39 (`Többoldalas oldallétrehozás, lapváltás és függőleges görgetés javítása`):** `NotebookPagePane` (`key(target.notebookId, target.pageId)`) és `AndroidView` (`key(transformMatrix, dryStrokeStore, dryInkRasterCache, journalCommitSink)`) állapotkötés javítása, `!usedComposite &&` eltávolítása a szomszédos lap-előnézetről (`StylusDrawingSurface.kt`), skálafüggő `resolveFixedPageScrollNavigation` küszöb, füzetszintű eszköz/teljes képernyő állapot kiemelése `NotebookWorkspaceScreen`-be, valamint frissített APK telepítése `25097RP43G`-re.

- [x] **VIII. ÜTEM – Lépés 59–65 (Folytonos többoldalas dokumentumtér, Sliding-Window Oldalpool & Valódi FrameMetrics Telemetria):**
  - [x] Lépés 59 – `ContinuousPageLayout` (vegyes oldalméret-barát folytonos dokumentum koordinátatér + láthatósági vágás + hit-test + unit tesztek) és `MainActivity.kt` `Window.OnFrameMetricsAvailableListener` valódi CPU+GPU render munkaidő P95 vs VSYNC P95 szétválasztása
  - [x] Lépés 60 – `MultiPageSessionManager` (`active ± 1` háttérbeli prefetch/warmup, `|index - active| > 2` aszinkron `Dispatchers.IO` journal lezárás főszál-blokkolás nélkül, közös `192 MB` tile költségvetés) + `NotebookUndoCoordinator` (oldalak közötti pontos Undo/Redo útválasztás) + unit tesztek
  - [x] Lépés 61 – `PaperBackgroundLayer` többoldalas folytonos lap/sablon/oldalszám-pill renderelés & `DryInkRasterCache` `pageOrigin` eltolás támogatás (compositor budget clamp)
  - [x] Lépés 62 – `StylusDrawingSurface` perzisztens (`key(notebookId)`) folytonos vászon (`FIXED_PAGE`: anchor-slot rajzolás/Hold-to-Snap/radír/lasszó `AndroidView` újragyártás nélkül, `INFINITE_CANVAS` változatlan)
  - [x] Lépés 63 – `BookshelfScreen` (`NotebookWorkspaceScreen`) folytonos session integráció, többoldalas `PageObject` & `SearchHighlight` koordináta-transzformáció, aszinkron kilépési/háttérbe küldési journal flush
  - [x] Lépés 64 – Folytonos többoldalas scroll & lapok közötti Undo/Redo / koordináta-pontosság unit & instrumentált tesztek (`MultiPageContinuousScrollInstrumentedTest`)
  - [x] Lépés 65 – Végső verifikáció (`115/115` unit [24 osztály], `19/19` androidTest `25097RP43G`-n, 144 Hz benchmark `frameDurationCpuMs P50 = 4,65 ms / P90 = 5,71 ms / P95 = 6,28 ms / P99 = 8,96 ms`, `frameOverrunMs P95 = -5,51 ms`, median `frameCount = 118,0`, `CV = 0,0060`, `installDebug` + tabletes 9 oldalas folytonos rajz/görgetés & `Display`/`VSYNC p95`/`Render p95` overlay ellenőrzés) & `docs/` (`feature_registry.md`, `feature_log.md`, `user_journeys.md`) frissítve
- [x] **IX. ÜTEM – Lépés 66–73 (Debug HUD Javítás, Gemini Modellek, PDF Háttér-Render, Képre Írás & Oldalfiók Miniatűr/Törlés):**
  - [x] Lépés 66 – `MainActivity` + `FrameTimingAccumulator` (`cpuWorkP50Ms`/`cpuWorkP95Ms`, `FrameMetrics.GPU_DURATION`, 4 s inaktivitási lejárat): izolált CPU és GPU renderidők megkülönböztetve a `Choreographer` `VSYNC` ritmustól (nincs hamis `36–39 ms`-es IDLE/compositor felfújás)
  - [x] Lépés 67 – `GeminiApiSupport` (`sanitizeApiKey`, `validateHeaderSafeApiKey`, `X-Android-Package` + `X-Android-Cert` fejlécek, REST hibabontás), `ModelCatalog.refreshCatalog` (`pageSize=1000` lapozás), `GeminiClient`, `AiConsentStore` & `ConsentDialog` (élő mezőbeli kulccsal is frissíthető `Modellek` lista)
  - [x] Lépés 68 – `PdfTileRenderer` (`prewarmBasePage`, `drawPdfPageForCanvasSyncOrRequest`, `computePdfAspectFitPlacement`, nagyítási részletcsempék, `renderPageForExport`) + `PaperBackgroundLayer` / `StylusDrawingSurface` / `MultiPageSessionManager` élő PDF háttér-renderelés (üres PDF-oldalak javítva)
  - [x] Lépés 69 – Tinta alatti `PageObject.ImageObject` (2b. réteg a `DryCanvasView`-ban és a `CanvasPageVectorRenderer`-ben) + `PersistedStrokeRecord.attachedObjectId` / `ObjectAttachmentGeometry` / `LiveObjectInteractionController` / `CanvasStateHolder.commitObjectInteractionWithAttachedStrokes` és `removePageObjectWithAttachedStrokes` (képre rajzolt tinta a képen marad húzáskor, átméretezéskor, forgatáskor és törléskor, 1 Undo/Redo lépésben)
  - [x] Lépés 70 – `PageThumbnailRenderer` (16 MB LRU háttér-kompozitor papír + PDF + tinta alatti kép + vonások + 5. réteg) & közös `CanvasPageVectorRenderer`, bekötve a `LeftPageDrawerOverlay` kártyáinak valós tartalmi miniatűr-előnézetébe
  - [x] Lépés 71 – Biztonságos oldaltörlés a `LeftPageDrawerOverlay`-ben (`resolveNextActivePageAfterDelete`, utolsó oldalt védő kuka gomb elrejtése, ESC + háttérrel zárható `"Oldal törlése"` dialógus helyes Compose `.onPreviewKeyEvent` fókusz-sorrenddel, `deletedActivePageId` dispose-védelem, `AppDatabase.deleteWithTombstone`, lemeztisztítás, `MultiPageSessionManager.notifyPageDeleted`)
  - [x] Lépés 72 – Új JVM unit tesztek (`StrokeJournalRoundTripTest` + `L68To71ValidationUnitTest`) és Android műszerezett tesztek (`L68To71ValidationInstrumentedTest`) létrehozva és futtatva
  - [x] Lépés 73 – Végső verifikáció (**125/125** JVM unit teszt [25 osztály], **24/24** androidTest `25097RP43G`-n, `installDebug`, hidegindítás + `logcat` hibamentesség + Debug HUD / LeftPageDrawer miniatűr / oldaltörlés / AI beállítások élő tabletes ellenőrzése) & `docs/` frissítve
- [x] **X. ÜTEM – Lépés 74–78 (PDF Központosítás, Pontos Oldal-/Szakasz-Visszatöltés Újranyitáskor & Stylus / Xiaomi Focus Pen Pro Váltás):**
  - [x] Lépés 74 – `AppDatabase v4` (`lastViewportPageId`, `lastViewportCenterXPt`, `lastViewportCenterYPt`, `lastViewportZoomFactor`, `MIGRATION_3_4`, `4.json`, `updatedAtEpochMs`-őrző `updateNotebookViewportState`/`updateLastOpenedPageId` lekérdezések) & `BackupManager` `MANIFEST_VERSION_V3` (`V1/V2/V3` visszafelé kompatibilis kódolás/dekódolás)
  - [x] Lépés 75 – PDF és füzet-középre igazítás & pontos szakasz-visszatöltés (`PdfTileRenderer.classifyPdfPageDimensionsPt` oldalankénti méretezéssel, `CanvasTransformMatrix.SavedViewportAnchor` + `applySavedPageLocalViewport` / `capturePageLocalViewportAnchor`, `StylusDrawingSurface` `isViewportMetadataReady` kapu + üres `session` nélküli `multiPageLayout` középre illesztés, `BookshelfScreen` debounced + `ON_PAUSE`/`ON_STOP`/kilépéskori mentés és `OpenedPageTarget.restoreSavedViewport` elkülönítés)
  - [x] Lépés 76 – Szabványos Android 14+ és Xiaomi Focus Pen Pro (`0x0022:0x5081`) hardverváltás (`StylusHardwareShortcutDetector`, `StylusToggleDebouncer` 180 ms, `StylusShortcutDebugBus` Debug HUD diagnosztika, `MainActivity.dispatchKeyEvent`/`dispatchGenericMotionEvent`, `StylusInputRouter.processGenericMotionButtonEvent`, `InProgressStrokesView` `isFocusable`/`isFocusableInTouchMode`/`setOnKeyListener`)
  - [x] Lépés 77 – Validációs tesztek (`XPhaseValidationUnitTest`, `XPhaseValidationInstrumentedTest`, valamint a `StylusInputRouterTest` repeat-kulcs viselkedésének ellenőrzése)
  - [x] Lépés 78 – Verifikáció (**131/131** JVM unit teszt [26 osztály], **28/28** androidTest `25097RP43G`-n, `installDebug` + tabletes élő smoke & Debug HUD ellenőrzés) & `docs/` frissítve
- [x] **XI. ÜTEM – Lépés 79–82 (Alacsony Késleltetésű Stylus Input Pipeline Optimalizálás):**
  - [x] Lépés 79 – `androidx.input:input-motionprediction` `1.0.1` (`AXIS_RZ`) frissítés (`libs.versions.toml`) + API 34+ `requestUnbufferedDispatch(InputDevice.SOURCE_STYLUS)` csatoláskor & `shouldRequestUnbufferedDispatchForEvent` stylus/rajz érintésnél (`StylusDrawingSurface.kt`)
  - [x] Lépés 80 – Zero-allocation nyers `ACTION_MOVE` gyorsítósáv (`tryProcessActiveRawMove`), `toStylusMotionFrame` feltételes történet/eszköz-allokáció, `toStrokeInputBatch(..., offsetXPt, offsetYPt)` listamásolás-mentes lapeltolás (`computeShiftedStrokeInputPoint`), valamint `p.eventTimeMs >= frame.eventTimeMs` prediktált időbélyeg-szűrés (`StylusInputRouter.kt`, `StylusDrawingSurface.kt`)
  - [x] Lépés 81 – `BrushConfig.DEFAULT_STABILIZATION_PERCENT = 0f` + 1. töltőtoll preset `0%` + `0% (min. késleltetés)` csúszkajelzés (`BrushConfig.kt`, `BrushSettingsPopover.kt`)
  - [x] Lépés 82 – Verifikáció (**134/134** JVM unit teszt [26 osztály], **29/29** androidTest `25097RP43G`-n, `installDebug` + hidegindítás `TotalTime: 831 ms` / `logcat` hibamentesség ellenőrizve) & `docs/` frissítve
- [x] **XII. ÜTEM – Lépés 83–94 (UI/UX Ergonomia, Kitakaró Szalag, Beágyazott Kódblokk Zónák & Élő LaTeX/Matek Motor):**
  - [x] Lépés 83–85 (`XII/A`) – Vékonyabb `0.5 pt` töltőtoll gyorsméret (`0.5, 1, 2, 4, 8 pt`), automatikus tollkövető szövegkiemelő-méretezés (`BrushConfig.computeRecommendedHighlighterSizePt`, manuális felülírással & `"Tollhoz igazítás (Auto)"` kapcsolóval), valamint automatikusan rejtődő bal felső `CanvasTopToolbar` (`topToolbarAutoHide` 3,5 s inaktivitás, PushPin kapcsoló `top_toolbar_autohide_toggle`, stylus hover & `top_toolbar_reveal_tab` felfedés)
  - [x] Lépés 86–88 (`XII/B`) – Kitakaró szalag (`BrushToolType.CLOZE_TAPE`, `PageObject.ClozeTapeObject`, `OBJECT_TAG_CLOZE_TAPE = 9`) 5b rétegben (`ClozeTapeCanvasRenderer`, `ClozeTapeOverlayNode`), szalaghúzás/téglalap gesztus + előnézet (`buildClozeTapeBoundsFromDrag`), 1-ujjas és Cloze-módú stylus koppintásos felfedés/visszazárás (`revealedClozeTapeIds` munkamenet-állapot), szín/magasság/mintázat (`SOLID | DIAGONAL_STRIPES | DOTS`) + `"Összes szalag felfedése / visszazárása"` és vektoros PDF/miniatűr export
  - [x] Lépés 89–91 (`XII/C`) – Beágyazott kódblokk zónák (`PageObject.CodeBlockObject`, `OBJECT_TAG_CODE_BLOCK = 8`, `CodeLanguage` = `SQL | PYTHON | JS_TS | CSHARP`), `CodeSyntaxHighlighter` + monospace 2b tinta alatti renderelés (`CodeBlockCanvasRenderer`) külső nyilak/megjegyzések elcsúszása nélküli méretkezeléssel, 1-gesztusos beszúrás az eszköztárról (`</>`), ESC/háttérkattintással zárható `CodeBlockEditorDialog`, tinta-horgonyzás (`ObjectAttachmentGeometry`) és FTS/kép-szinkron indexelés (`SearchTextIndexer`)
  - [x] Lépés 92–94 (`XII/D` + Verifikáció) – 1 koppintásos `∑ LaTeX` gomb (`LassoSelectionActionBar`) Gemini + offline ML Kit/basic LaTeX tartalékkal, ESC/háttérkattintással zárható `LatexEditDialog` (`JLatexMathDrawable` előnézet + sablon chipek) & `"Grafikon"` gomb (`LiveMathSuggestionEngine.extractPlotExpressionFromLatex`), valamint `EqualsGestureDetector` + `LiveMathSuggestionEngine` (600 ms debounced lebegő sugallat-pirula numeric/grafikon/opcionális `✨ AI megoldás` felkínálással); JVM unit tesztek (**141/141** [27 osztály], `0` hiba, `0` kihagyás) és APK + androidTest APK (`assembleDebug`, `assembleDebugAndroidTest`) sikeresen lefordítva (`connectedDebugAndroidTest` a XIII. ütemmel együtt futtatva)
- [x] **XIII. ÜTEM – Lépés 95–99 (Oldalközi Lasszó Áthelyezés & Vágólap, N × M Táblázat Beszúrása & Teljes Eszközös Verifikáció):**
  - [x] Lépés 95–96 (`Oldalközi lasszó & vágólap`) – `CanvasStateHolder` (`SelectionTransferSnapshot`, `SelectionImportResult`, `snapshotSelection`, `removeSelectionExternal`, `pasteSelectionSnapshot`, `insertSelectionSnapshotExternal`, `computeCrossPageTransferOffset`, `computeClipboardPasteOffset`), `MultiPageSessionManager` (`transactionId`-csoportosított `NotebookUndoCoordinator` `popUndoGroup`/`popRedoGroup`, `mutatedPageIds`, `tryTransferSelectionBetweenReadyPagesSync`, `transferSelectionBetweenPages`), valamint `StylusDrawingSurface` / `BookshelfScreen` átfedés-alapú oldalközi átdobás (`resolveDropDestinationSlot`), külön `Másolás` (`lasso_copy_button` → lebegő `Beillesztés` sáv `lasso_paste_bar`) és `Duplikálás` (`lasso_duplicate_button`) műveletek
  - [x] Lépés 97–98 (`N × M Táblázat beszúrása`) – `PageObject.TableObject` (`1..20 × 1..12`), `ObjectAttachmentGeometry.isUnderInkRenderedObject`, `StrokeBinarySerializer` `OBJECT_TAG_TABLE = 10`, `TableCanvasRenderer` Layer 2b tinta alatti vektoros rajzolás (`DryCanvasView` + `CanvasPageVectorRenderer`), `StylusIcons.TableGrid` + `FloatingToolDock` (`dock_table_button`), `TablePickerPopover` (`6 × 6` gyorsrács, `1..20 × 1..12` léptetők, fejléc kiemelés, vastagság/szín, testvér scrim ESC + háttér zárással) és `PendingTableDraftOverlay` (előzetes mozgatás & 4 sarokfogantyús méretezés → `"Lehelyezés"` után zárolt, nem kijelölhető rács)
  - [x] Lépés 99 (`Verifikáció & Telepítés`) – `TablePickerPopover` + `CodeBlockEditorDialog`/`LatexEditDialog` Compose fókusz/testvér-scrim javítás; `testDebugUnitTest` (**145/145** [28 tesztfájl], `0` hiba, `0` kihagyás), `:app:connectedDebugAndroidTest` (**37/37** sikeres `25097RP43G` tableten, `0` hiba, `0` kihagyás — benne a XII. és XIII. műszerezett validációval), `:app:installDebug` + hidegindítás (`TotalTime: 797 ms`, `Status: ok`, tiszta `logcat`)
- [x] **XIV. ÜTEM – Lépés 100–105 (Teljes Táblázat+Cella Ink Lasszó, `∑ LaTeX` Javítás & Finomítás, Teljes Szélességű Éles Eszköztár & Tehetetlenségi Görgetés):**
  - [x] Lépés 100 (`Teljes Táblázat + Cella-Tinta Lasszó`) – `PageObject.TableObject` csatolható (`isUnderInkAttachableObject = true`), `CanvasStateHolder.selectPageObjectsByLasso` perem (`>= 2`) vagy belső 3×3 háló (`>= 3`) találattal fogja be az egész táblázatot míg egyetlen cella szűk lasszója lokális marad, `strokeIdsInsideOrAttachedToObject` a csatolt + korábbi cella-tinta vonásokat is egybecsomagolja, `StylusDrawingSurface.drawLiveDraggedUnderInkObjects` vágatlan előnézetet rajzol laprések felett a mozgatás közben, táblázat kijelölésénél a forgatás tiltva (`canRotate = !selectedHasTable`), csoportos mozgatás/másolás/törlés/lapközi átvitel 1 Undo/Redo lépésben
  - [x] Lépés 101 (`∑ LaTeX Hiba Javítása & Képletszerkesztő Finomítása`) – `BookshelfScreen.kt` Lasszó→AI/LaTeX vonás-szerializálási eszközhibája javítva (`LASSO` helyett forrás toll/kiemelő átadása, így többé nem dobódnak el a vonások), kemény `"x^{2} + 1"` fallback eltávolítva, `MathFractionLayoutAnalyzer.detectTopLevelFraction` + `OcrLineSegmenter.buildMathLineForRecognition` + szükség szerinti `OcrModelDownloadManager.downloadModelIfNeeded` + bővített `LiveMathSuggestionEngine.toBasicLatex`, erősebb Gemini prompt; a `∑ LaTeX` kattintásra csak formáz és kijelöl (nem nyitja meg azonnal a dialógust), a `FloatingObjectActionPill` képernyőhatárra szorított + fent/lent billenő pozíciót kap (`computeClampedActionPillOffsetPx`), a `"Szerkesztés"` (`latex_pill_edit_button`) által nyitott `LatexEditDialog` pedig kurzortudatos `insertLatexSnippetAtCursor` beillesztést, 4 kategorizált szimbólumpalettát, színválasztót és nagyobb előnézetet biztosít
  - [x] Lépés 102 (`Éles Sarkú, Teljes Szélességű Eszköztár`) – `FloatingToolDock` külső kerete `RectangleShape` éles sarkú, vízszintesen szétnyitva `fillMaxWidth()` + `x = 0` + `.widthIn(min = minExpandedWidthDp)` teljes szélességet tölt ki `SpaceBetween` elrendezéssel és függőleges eltolással (`yFraction`) a felső sáv kikerülésével
  - [x] Lépés 103 (`Tehetetlenségi Fling Görgetés`) – `ScrollFlingPhysics.kt` zéró-allokációs `FingerVelocityTracker` + analitikus exponenciális lecsengésű `ScrollFlingState` (`friction = 2.35f`) 120/144 Hz `dryCanvasView.postOnAnimation`-ön, azonnali megállítás `ACTION_DOWN`-nál (`cancelActiveFling` szinkron `dryCanvasView.removeCallbacks`), valamint függőleges dokumentumhatár-ütközés (`CanvasTransformMatrix.panYWithBoundaryClamp`, `computeVerticalPanBoundsPx`)
  - [x] Lépés 104–105 (`Tesztek, Eszközös Verifikáció & Dokumentáció`) – XIII táblázat-tesztek frissítve, új `XivPhaseValidationUnitTest` (6) & `XivPhaseValidationInstrumentedTest` (4); `testDebugUnitTest` (**151/151** [29 tesztosztály], `0` hiba, `0` kihagyás), `:app:connectedDebugAndroidTest` (**41/41** `25097RP43G` tableten, `0` hiba, `0` kihagyás), `:app:installDebug` + hidegindítás (`TotalTime: 812 ms`, `Status: ok`, tiszta `logcat`) & `docs/` frissítve
- [x] **XV. ÜTEM – Lépés 106–112 (7 Napos Felhős Lomtár, Kategória Drive-Mappa Háttér PDF Export & Radiális Kör-Menü):**
  - [x] Lépés 106 (`Room v5 & Backup v4`) – `FolderEntity.drivePdfFolderId`/`drivePdfFolderName`, `CategoryPdfSyncRecordEntity`, `PendingDriveFolderDeletionEntity`, `MIGRATION_4_5` (`5.json`), `BackupManifest.MANIFEST_VERSION_V4` visszafelé kompatibilis (`V1–V4`) mentés/visszaállítás (`AppDatabase.kt`, `BackupManager.kt`)
  - [x] Lépés 107 (`7 Napos Felhős Lomtár & Visszaállítás Menü`) – `CloudTrashIndex.kt` (`cloud-trash.json`, tiszta Kotlin `MiniJsonObjectParser`/`RemoteSyncIndex.quoteJson` szerializáció), `CloudSyncEngine` puha törlés (törölt füzetek + blobjaik `7` napig védve maradnak, lejártakor automatikus távoli törlés), valamint `CloudBackupDialog` → `CloudTrashRestoreDialog` (`Visszaállít` / `Végleges törlés`, hátralévő nap/óra kijelzéssel, ESC/háttér zárással)
  - [x] Lépés 108–109 (`Kategória Google Drive Mappa Tallózó & Háttér PDF Szinkron`) – `DRIVE_FULL_SCOPE` + `GoogleDriveFolderClient` (`listSubfolders`, `createSubfolder`, `uploadOrReplacePdfInFolder`, `trashFolder`), `DriveFolderPickerDialog` (tallózás, almappa létrehozás, szétkapcsolás), `CategoryPdfSyncEngine` (`<SzűrtCím>_YYYY-MM-DD.pdf` felülírás/átnevezés meglévő `remoteDriveFileId`-n duplikáció nélkül) + `CategoryPdfSyncWorker`; kizárólag `MainActivity.onStop()` / periodikus háttérmunka alatt fut (aktív jegyzetelés közben megszakítva), kategória törlésekor pedig figyelmeztető `DeleteCategoryConfirmDialog` jelenik meg és a Drive-mappa is kukázódik (`PendingDriveFolderDeletionEntity` offline sorban állítással)
  - [x] Lépés 110 (`Cursor-Anchored Radiális Kör-Menü & Focus Pen Pro Gesztusok`) – `StylusButtonGestureStateMachine` (`LONG_PRESS >= 320 ms` / `DOUBLE_PRESS < 340 ms` / `OFF`, dual HID/Motion `< 35 ms` dedup; a rövid gombnyomás továbbra is `Toll ↔ Radír`), `StylusDrawingSurface.onCursorScreenPositionChanged`, `BrushSettingsPopover` trigger-mód választó, `RadialPieMenuOverlay` 8 szeletes gyűrű (`Toll`, `Kiemelő`, `Lasszó`, `Vastagság`, `Radír`, `Kék`, `Piros`, `Fekete/Fehér` + középső mégse), `MainActivity` & `BookshelfScreen` bekötés + Compose `VerifyError` elhárítás (`NotebookPageToolAndPieOverlays` / `NotebookPageWorkspaceDialogs` kiemelése)
  - [x] Lépés 111–112 (`Tesztek, Eszközös Verifikáció & Dokumentáció`) – `gradle/libs.versions.toml` AndroidX Test / Espresso `3.7.0` frissítés Android 17 kompatibilitáshoz; `testDebugUnitTest` (**155/155** [30 tesztosztály], `0` hiba, `0` kihagyás — benne `XvPhaseValidationUnitTest`), `:app:connectedDebugAndroidTest` (**45/45** `25097RP43G - 17` tableten, `0` hiba, `0` kihagyás — benne `XvPhaseValidationInstrumentedTest`), `:app:installDebug` + hidegindítás (`TotalTime: 745 ms`, `Status: ok`, `LaunchState: COLD`, tiszta crash `logcat`) & `docs/` frissítve
