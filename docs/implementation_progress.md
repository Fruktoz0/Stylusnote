# StylusNote – Megvalósítási Haladáskövető (Implementation Progress)

Ez a dokumentum a `stylusnote_rendszerterv_v2.txt` alapján készült **17 lépéses (Lépés 0 – Lépés 16)** ütemterv élő állapotnaplója. Minden szakasz befejezésekor frissül az elkészült elemek pipálásával és az **Aktuális Átadási Állapottal**, hogy a következő lépés pontosan onnan folytatódhasson, ahol az előző megállt.

---

## 1. Aktuális Átadási Állapot (Current Handoff State)

- **Utolsó befejezett lépés:** `Lépés 31 (Befejezve — Phase III teljesítve)`
- **Befejezés dátuma:** `2026-11-14`
- **Aktuális projektállapot:**
  - Mind a **I. Ütem (`Lépés 0–17`)**, a **II. Ütem (`Lépés 18–24`)**, és a **III. Ütem (`Lépés 25–31`)** elkészült és ellenőrizve lett (`25097RP43G`).
    - **`Lépés 25` `[x]`**: 1 ujjas pan zoom nélkül, normalizált `100%` nagyítás (`relativeZoomPercent`), bal felső sarokba simuló `CanvasTopToolbar`, és `Teljes képernyő` mód kapcsoló az eszköztáron (`CanvasTransformMatrixTest` + `StylusInputRouterTest` ✅).
    - **`Lépés 26` `[x]`**: 60s `OkHttpClient` olvasási időtúllépés + többlépcsős automatikus Flash modell-váltó sorozat (`gemini-2.5-flash` -> `gemini-2.0-flash` -> `gemini-2.5-flash-lite` -> `gemini-1.5-flash`), valamint a formázott szövegblokkok sticky kijelölésének megszüntetése és DPI-helyes betűméretezés (`GeminiClientAndCatalogTest` ✅).
    - **`Lépés 27` `[x]`**: Golyóstoll/Ceruza eltávolítva az eszköztárról, `ToolDockPreferencesStore` független eszközönkénti vastagság/szín memória (`perToolConfigs`), Töltőtoll `1, 2, 4, 8 pt` gyorsméretek, eszköztárhoz rögzített `BrushSettingsPopover`, felesleges alsó térközök és "Húzd szét..." csík eltávolítása, Átlátszóság csak a teljes stúdió nézetben (`ToolDockPlacementTest` ✅).
    - **`Lépés 28` `[x]`**: Oldal létrehozás és azonnali hiányzó 1. oldal perzisztálása (`AppDatabase.PageDao.getPagesForNotebook`), függőleges többoldalas lapelőnézet (`drawAdjacentFixedPagesIfVisible`) és 1 ujjas átgördülés (`onNavigationGestureEnd`), függőlegesen húzható `LeftPagePullTab`, Drag & Drop oldalátrendezés (`reorderPagesInNotebook`) és alsó oldalléptető sáv (`ToolDockPlacementTest` ✅).
    - **`Lépés 29` `[x]`**: `PaperTemplateType.GRID_SIMPLE_5MM` (5x5 erősített vonalak nélküli sima négyzetháló), vizuális miniatűr előnézetes `PaperSettingsDialog` aktív jelöléssel, és a Könyvespolc `"PDF import"` gomb elrendezésének javítása (`ToolDockPlacementTest` ✅).
    - **`Lépés 30` `[x]`**: Lasszó sarok-forgatás fogantyú (`StylusDrawingSurface`), Eszköztár `OCR` gomb (teljes füzet még nem formázott kézírásaira `ocrLanguage` alapján, `HandwritingToLatexConverter.formatUnformattedStrokesInPage`), és `Alakzatok` menü (`ShapePickerPopover`) + 24+8 2D/3D `GeometricShapeObject` + átméretezés/forgatás + SNOT `tag=6` + PDF export (`StrokeJournalRoundTripTest`, `testDebugUnitTest` ✅).
    - **`Lépés 31` `[x]`**: Perfetto-trace elemzéssel feltárt aktív gesztus alatti Compose zoom-állapot / offscreen layer és `HintSession` Binder IPC kivezetése (`StylusDrawingSurface.kt`, `BookshelfScreen.kt`), teljes JVM + műszerezett tesztek (`69/69` unit + `8/8` androidTest ✅), 144 Hz Xiaomi Pad 8 (`25097RP43G`) Macrobenchmark (`P95 = 6,05 ms ≤ 6,94 ms` ✅), dokumentáció frissítése és debug APK telepítése.
- **Következő végrehajtandó lépés:** `Phase III befejezve — eszközre telepítve és tesztekkel verifikálva`

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

- [x] **Frame time:** p95 ≤ 6,94 ms pan/zoom közben 10 000 vonalas oldalon Xiaomi Pad 8-on *(Mérve `25097RP43G` tableten a `panAndZoom10kStrokesCanvasFrameTiming` 5-iterációs 10 000 vonalas benchmarkkal a III. ütem lezárásakor: `frameDurationCpuMs` **P50 = `4,37 ms`**, **P90 = `5,51 ms`**, **P95 = `6,05 ms`**, **P99 = `8,42 ms`**; `frameOverrunMs` **P50 = `-7,31 ms`**, **P90 = `-5,80 ms`**, **P95 = `-4,98 ms`**, **P99 = `-3,25 ms`**, medián `120,0` képkocka ✅)*
- [ ] **Pontosság:** Tollhegy–tinta eltérés 400%-os nagyításnál ≤ 1 px *(Alap: `CanvasTransformMatrix` 400% teszt 8/8 `connectedDebugAndroidTest`-ben sikeres, `concat(matrix)` csempejavítás alkalmazva; kézi stylus / vizuális próba hátravan)*
- [ ] **Memória:** 50 000 vonallal sincs OOM; raszter cache ≤ 192 MB *(Készüléken ellenőrzendő)*
- [ ] **Betöltés:** 10 000 vonalas oldal megnyitása ≤ 1 s *(Készüléken ellenőrzendő)*
- [ ] **Adatbiztonság:** Automatizált process-kill teszt rajzolás közben ≤ 1 s adatvesztéssel, hibátlan journal visszajátszás *(`StrokeJournal` truncated-tail recovery `connectedDebugAndroidTest`-ben sikeres; élő UI process-kill próba hátravan)*
- [ ] **Round-trip:** Serializer, journal és backup/restore után azonos vonalak *(JVM `StrokeJournalRoundTripTest` 6/6 és `BackupAndExportTest` 3/3 sikeres; eszközön történő integrációs ellenőrzés hátravan)*
- [x] **JVM Unit tesztek (`testDebugUnitTest` — 16 osztály, 69 teszt, 0 hiba, 0 kihagyás):**
  - [x] `CanvasTransformMatrixTest` (7)
  - [x] `OneEuroFilterTest` (6)
  - [x] `SpatialIndexTest` (4, brute-force összevetés)
  - [x] `StylusInputRouterTest` (8)
  - [x] `DryInkRasterCacheTest` (3)
  - [x] `ShapeRecognizerTest` (5)
  - [x] `CanvasStateHolderTest` (4 tiszta Kotlin teszt; az 5 natív `libink.so` JNI teszt áthelyezve `StylusNoteInstrumentedValidationTest`-be)
  - [x] `StrokeJournalRoundTripTest` (6)
  - [x] `FtsAccentInsensitiveTest` (3)
  - [x] `BackupAndExportTest` (3)
  - [x] `GeminiClientAndCatalogTest` (4)
  - [x] `OcrLineSegmenterTest` (2)
  - [x] `FunctionEvaluatorTest` (3)
  - [x] `AiCopilotExamParserTest` (2)
  - [x] `ToolDockPlacementTest` (5)
  - [x] `PageSelectionParserTest` (4)
- [x] **Műszerezett (`androidTest`) tesztek (`:app:connectedDebugAndroidTest`):** 8/8 teszt sikeres (`25097RP43G - Android 16`, `0` hiba, `0` kihagyás, benne az 5 natív `libink.so` Ink JNI teszttel)
