# StylusNote – Funkció-nyilvántartás (Feature Registry)

Ez a dokumentum a `stylusnote_rendszerterv_v2.txt` alapján nyilvántartja a StylusNote alkalmazás összes tervezett és megvalósított funkcióját, modulonkénti bontásban.

---

## Jelmagyarázat
- **Implementálva (Alfázis X):** A kód teljes egészében elkészült és integrálva van.
- **Tervezett (Lépés Y):** Még nincs implementálva; a megjelölt lépésben készül el.

---

## 1. Alaprendszer és Architektúra (Core & Display)

| Azonosító | Funkció neve | Leírás | Állapot | Érintett fájlok |
| :--- | :--- | :--- | :--- | :--- |
| `CORE-01` | **144 Hz Display Lock, Immerzív Teljes Képernyő, ADPF & Overlay** | A fizikai felbontáshoz tartozó legmagasabb frissítési mód zárolása (`preferredDisplayModeId`), valódi `WindowInsetsControllerCompat` immerzív teljes képernyő, API 35+ frame rate hint, ADPF `PerformanceHintManager` (`6,94 ms` target) és `DryCanvasView`. | **Implementálva (Alfázis 1a + Lépés 17–18)** | `MainActivity.kt`, `ui/canvas/StylusDrawingSurface.kt` |
| `CORE-02` | **Dokumentumtér (PDF pt) & `CanvasTransformMatrix`** | PDF pont (`1/72 in`) alapú koordinátatér, A4/B5/16:10 oldal-előbeállítások, `0,65×–5,0×` (`MIN_SCALE = 0.65f`) kétujjas zoom és pan az FPS-esés megelőzésére, inverz leképezés és közös `Matrix` szinkron a rétegek számára. | **Implementálva (Alfázis 1a + Lépés 21)** | `core/math/CanvasTransformMatrix.kt` |
| `CORE-03` | **StarNote Vizuális Rendszer, Ikonok & OLED Téma** | StarNote meleg papírfehér / pasztell palette + OLED `#000000` mélyfekete színséma, `HandwritingFontFamily` kézírásszerű tipográfia és egyedi `StylusIcons` vektoros ikonok. | **Implementálva (Alfázis 1a + Lépés 18)** | `ui/theme/Theme.kt`, `ui/theme/StylusIcons.kt` |
| `CORE-04` | **Villogásmentes Wet→Dry Átadás** | `InProgressStrokesFinishedListener` → `CanvasStateHolder` + `SpatialIndex` + `DryInkRasterCache` invalidálás + `StrokeJournal` → `removeFinishedStrokes` csak `DryCanvasView` rajzolása után. | **Implementálva (Alfázis 1c + Lépés 17)** | `ui/canvas/StylusDrawingSurface.kt` |
| `CORE-05` | **GPU Kompozit + 192 MB LRU `DryInkRasterCache`** | `CompositePageSnapshot` (`Bitmap.Config.HARDWARE`, max. `2048 px`) pan/zoom alatti egyetlen `TextureOp` blitteléshez + szálbiztos 512×512 px LRU csemperendszer háttérben történő `prewarmVisibleTiles` / `rebakeSettledScale` újrarendereléssel. | **Implementálva (Alfázis 1c + Lépés 17)** | `ui/canvas/DryInkRasterCache.kt`, `ui/canvas/PaperBackgroundLayer.kt` |

---

## 2. Modul A – Rajzmotor, Stabilizálás, Ecsetek

| Azonosító | Funkció neve | Leírás | Állapot | Érintett fájlok |
| :--- | :--- | :--- | :--- | :--- |
| `MODA-01` | **One-Euro (1€) Stabilizáló** | Sebességfüggő aluláteresztő szűrő, 0–100% csúszka `minCutoff` / `beta` leképezéssel (0% = szűrés nélkül). | **Implementálva (Alfázis 1b/1)** | `core/math/OneEuroFilter.kt` |
| `MODA-02` | **Térbeli Index (`SpatialIndex`)** | Memóriabeli R-fa / QuadTree a vonalak befoglaló dobozára (`Box`) viewport cullinghoz, radírhoz és lasszóhoz. | **Implementálva (Alfázis 1b/1)** | `core/math/SpatialIndex.kt` |
| `MODA-03` | **Stylus Input Pipeline (`StylusInputRouter`)** | Szigorú palm rejection (`TOOL_TYPE_STYLUS`/`ERASER`), két útvonal (0% vs. >0% historical batch + 1€), nyers `MotionEventPredictor` + `recycle()`, nyomásgörbe ($p' = p^\gamma$), szimultán kétujjas pinch-zoom + pan (`requestDisallowInterceptTouchEvent`), Focus Pen Pro gombhook és stylus-only tengely-kiolvasás. | **Implementálva (Alfázis 1b/2 + Lépés 17)** | `core/hardware/StylusInputRouter.kt`, `ui/canvas/StylusDrawingSurface.kt` |
| `MODA-04` | **Hover Kurzor (`HoverCursorOverlay`)** | 1 px körvonalú előnézet `ACTION_HOVER_MOVE` alapján, az aktuális ecsetméret × zoom átmérővel és színnel. | **Implementálva (Alfázis 1c)** | `ui/canvas/HoverCursorOverlay.kt` |
| `MODA-05` | **4 Natív Ecset (`BrushConfig`)** | Töltőtoll (nyomásérzékeny), Golyóstoll (marker), Ceruza (`TILT_IN_RADIANS`/`NORMALIZED_PRESSURE`), Highlighter (`SelfOverlap.DISCARD`, alpha 0,35, külön réteg); a lézeres mutató eltávolítva (`FormatMigrator` kompatibilitás megőrizve). | **Implementálva (Alfázis 2b + Lépés 18)** | `domain/model/BrushConfig.kt`, `data/local/FormatMigrator.kt` |
| `MODA-06` | **Vektoros és Precíziós Radír** | Vektoros (`PartitionedMesh.intersects` + kör-távolság teljes vonal törlés) és Precíziós (`@WorkerThread` szegmens–kör metszés + interpolált vágási határpontok, egyedi radír ikon). | **Implementálva (Alfázis 2a + Lépés 18)** | `domain/repository/CanvasStateHolder.kt`, `ui/theme/StylusIcons.kt` |
| `MODA-07` | **Delta Undo/Redo (`CanvasAction`)** | Minimum 50 lépéses és pontkeretes delta stack (`AddedStrokes`, `RemovedStrokes`, `TransformedStrokes`, `RecoloredStrokes`, `CompositeAction`). | **Implementálva (Alfázis 2a)** | `domain/model/CanvasAction.kt`, `domain/repository/CanvasStateHolder.kt` |
| `MODA-08` | **Globális Lebegő Eszköztár, 2 Lépcsős Ecsetpanel & Bal Felső Fejléc** | Globálisan (`ToolDockPreferencesStore` DataStore) szabadon mozgatható `(x, y)` és ütközésmentesített `FloatingToolDock` (összecsukva: Toll/Radír/Vissza; vízszintes/függőleges váltás), hosszan nyomással nyíló 2 lépcsős `BrushSettingsPopover` (kompakt fül → kihúzható teljes stúdió; ESC/backdrop zárás), valamint a bal felső sarokba zárt `CanvasTopToolbar`. | **Implementálva (Alfázis 2b + Lépés 21–22)** | `ui/components/ToolDockPreferencesStore.kt`, `ui/components/FloatingToolDock.kt`, `ui/components/BrushSettingsPopover.kt`, `ui/shelf/BookshelfScreen.kt` |
| `MODA-09` | **Hold-to-Snap (`ShapeRecognizer`)** | 300 ms megtartás (`< 4 pt` elmozdulás, `> 40 pt` hossz): egyenes, kör, ellipszis, háromszög, téglalap, nyíl + `ShapeMeta` utólagos szerkeszthetőség. | Implementálva (Alfázis 2c) | `core/math/ShapeRecognizer.kt` |
| `MODA-10` | **Szellemképmentes Lasszó Kijelölés & `✨ AI` Formázás** | SpatialIndex + 60% poligon-bennfoglaltság; mozgatás közben `excludedStrokeIds` az eredeti hely szellemképének eltüntetéséhez; lebegő `✨ AI` gomb (<10 ms helyi alakzat-kiegyenesítés + gyors 768 px Gemini `LASSO_AUTO_FORMAT` képletre, alakzatra vagy `FormattedTextObject`-re). | **Implementálva (Alfázis 2c + Lépés 23)** | `ui/canvas/DryInkRasterCache.kt`, `ui/canvas/StylusDrawingSurface.kt`, `ui/canvas/LatexOverlayNode.kt` |

---

## 3. Modul B – Matek és STEM Eszközök

| Azonosító | Funkció neve | Leírás | Állapot | Érintett fájlok |
| :--- | :--- | :--- | :--- | :--- |
| `MODB-01` | **Lasszó / AI → LaTeX, Kézírásos Szöveg & Alakzat** | Világos módú bitmap → Gemini JSON → `LatexObject`, `FormattedTextObject` (`HandwritingFontFamily`, `OBJECT_TAG_FORMATTED_TEXT = 5`) vagy vektoros alakzatcsere `CompositeAction`-nel. | **Implementálva (Alfázis 4c + Lépés 23)** | `ui/canvas/LatexOverlayNode.kt`, `domain/model/PageObject.kt`, `data/local/StrokeBinarySerializer.kt` |
| `MODB-02` | **Lépésről lépésre levezetés kártya** | Képlet alatti menüből kérhető strukturált Markdown + LaTeX kártya `ObjectRecord`-ként mentve. | Implementálva (Alfázis 4c) | `ui/canvas/LatexOverlayNode.kt`, `domain/model/PageObject.kt` |
| `MODB-03` | **Matematikai Értékelő (`FunctionEvaluator` Motor)** | Belső rekurzív-leszálló parser és PDF-görbe rajzoló háttér-kompatibilitás megőrzése (a felesleges UI dialógus kivezetve a Lépés 18-ban). | **Implementálva (Alfázis 4c + Lépés 18)** | `core/ai/FunctionEvaluator.kt`, `data/export/PdfExportManager.kt` |

---

## 4. Modul C – Hibrid AI Copilot és Offline OCR

| Azonosító | Funkció neve | Leírás | Állapot | Érintett fájlok |
| :--- | :--- | :--- | :--- | :--- |
| `MODC-01` | **AI Hozzájárulás és Keystore BYOK** | `AiConsentStore` (alapból kikapcsolt AI, EEA/UK vs. ingyenes szintű adatvédelmi tájékoztató), Keystore AES-GCM titkosított kulcs DataStore-ban. | Implementálva (Alfázis 4a) | `core/ai/AiConsentStore.kt`, `ui/components/ConsentDialog.kt` |
| `MODC-02` | **Gemini Kliens és `ModelCatalog`** | Dinamikus `/v1beta/models` Flash modellválasztás, központi rate limiter, 429 `Retry-After` exponenciális backoff, 404 frissítés. | Implementálva (Alfázis 4a) | `core/ai/ModelCatalog.kt`, `core/ai/GeminiClient.kt` |
| `MODC-03` | **Offline Háttér-OCR** | `OcrLineSegmenter` (sorokra bontás, rajzok/highlighter kiszűrése) + `BackgroundOcrWorker` (`ML Kit Digital Ink` `hu`/`en-US`, `RemoteModelManager`) → FTS4 `PageText`. | Implementálva (Alfázis 4b) | `core/ai/OcrLineSegmenter.kt`, `core/ai/BackgroundOcrWorker.kt` |
| `MODC-04` | **Egyszerűsített AI Formázó (`AiCopilotSheet`)** | Beállítások & BYOK státusz, hatókörválasztó (`Csak az adott oldal` / `Teljes munkafüzet`) és 2 célzott művelet: (1) teljes átalakítás kézírásszerű szövegre + alakzatra + képletre, vagy (2) csak alakzatok és képletek javítása. | **Implementálva (Alfázis 4d + Lépés 23)** | `ui/components/AiCopilotSheet.kt`, `ui/canvas/LatexOverlayNode.kt`, `ui/shelf/BookshelfScreen.kt` |

---

## 5. Modul D – Rendszerezés, Borítók, OLED mód, PDF, Adatbiztonság

| Azonosító | Funkció neve | Leírás | Állapot | Érintett fájlok |
| :--- | :--- | :--- | :--- | :--- |
| `MODD-01` | **Papírsablonok & Vászonmódok** | Üres, Pontozott (5 mm), Négyzethálós (5 mm), Vonalas (8 mm), Cornell, Kottapapír vektoros rajzolása; Fix (A4/B5/16:10) és Végtelenített vászon. | Implementálva (Alfázis 2c) | `domain/model/PaperTemplate.kt`, `ui/canvas/PaperBackgroundLayer.kt` |
| `MODD-02` | **OLED Smart Luminance Inversion** | HSL $L_{\text{new}} = 1.0 - L_{\text{old}}$ renderidejű színinverzió Hue és Saturation megtartásával (fájlban mindig az eredeti szín marad). | Implementálva (Alfázis 2c) | `core/math/ColorLuminanceInverter.kt` |
| `MODD-03` | **SNOT Bináris Formátum & `StrokeJournal`** | `SNOT` fejléc + `ink-storage` alapú bináris kódolás, CRC32 rekordok, $\le 1\text{ s}$ `fsync` append-only journal, `ATOMIC_MOVE` kompaktálás, crash recovery. | Implementálva (Alfázis 3a – 1. rész) | `data/local/PageFileFormat.kt`, `StrokeBinarySerializer.kt`, `StrokeJournal.kt`, `FormatMigrator.kt` |
| `MODD-04` | **Room Adatbázis & FTS4 Keresés** | `Folder`, `Notebook`, `Chapter`, `Page`, `Tag`, `Bookmark`, `PageText` (`@Fts4` `unicode61`), explicit migrációk, `exportSchema = true`. | Implementálva (Alfázis 3a – 2. rész) | `data/local/AppDatabase.kt`, `domain/model/NotebookCover.kt` |
| `MODD-05` | **StarNote Könyvespolc, Kategóriák (Drag & Drop), Borító Stúdió & Fix Oldalfiók** | Kétpaneles könyvespolc bal oldali FTS4 keresővel és `+` kategóriákkal, hosszan nyomásos Drag & Drop mozgatás, `MIND \| JEGYZET \| PDF` fülek, szaggatott `+ Létrehozás` csempe, Moleskine `CoverDesignerDialog`, `...` könyvmenü, valamint fix oldalmódú bal oldalsáv (`LeftPageDrawer`). | **Implementálva (Alfázis 3b + Lépés 18–21)** | `ui/shelf/BookshelfScreen.kt`, `ui/shelf/CoverDesignerDialog.kt`, `ui/canvas/LeftPageDrawer.kt`, `data/local/AppDatabase.kt` |
| `MODD-06` | **Szelektív Csempézett PDF Import (`PdfTileRenderer` & `SelectivePdfImportDialog`)** | `Mutex`/egyszálú `PdfRenderer`, oldalszám-/tartomány-alapú (`parsePageSelectionSpec`: pl. `1-3, 5, 8-10`) szelektív PDF import füzetbe vagy új jegyzetbe, opcionális OLED `ColorMatrix` inverzióval. | **Implementálva (Alfázis 3c + Lépés 20)** | `data/export/PdfTileRenderer.kt`, `ui/shelf/PdfAndNotebookLmDialogs.kt` |
| `MODD-07` | **Vektoros PDF Export & NotebookLM Megosztás** | Vektoros `Path` + `StaticLayout` kurzív szöveg + LaTeX `PdfExportManager`, kiegészítve a NotebookLM export párbeszédpanellel (`FileProvider` `Intent.ACTION_SEND`, teljes könyv vagy adott oldal). | **Implementálva (Alfázis 3c + Lépés 20, 23)** | `data/export/PdfExportManager.kt`, `ui/shelf/PdfAndNotebookLmDialogs.kt`, `res/xml/file_paths.xml` |
| `MODD-08` | **ZIP / SAF / WebDAV Backup (`BackupManager`)** | `.zip` mentés és visszaállítás manifesttel, SAF (helyi/Drive) és OkHttp WebDAV célokkal, API-kulcs kizárásával. | Implementálva (Alfázis 3c) | `data/export/BackupManager.kt` |

