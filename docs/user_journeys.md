# StylusNote – Felhasználói Folyamatok (User Journeys)

Ez a dokumentum a főbb felhasználói és adatfeldolgozási folyamatokat ábrázolja Mermaid diagramokkal.

---

## 1. 144 Hz Tollvonás és Villogásmentes Wet→Dry Átadás

```mermaid
sequenceDiagram
    actor User as Felhasználó (Focus Pen Pro)
    participant Router as StylusInputRouter
    participant Pred as MotionEventPredictor
    participant Filter as OneEuroFilter
    participant Wet as InProgressStrokesView
    participant State as CanvasStateHolder + SpatialIndex
    participant Cache as DryInkRasterCache
    participant Journal as StrokeJournal

    User->>Router: ACTION_DOWN / ACTION_MOVE (TOOL_TYPE_STYLUS)
    Router->>Pred: record(nyers MotionEvent)
    alt Stabilizálás == 0%
        Router->>Wet: közvetlen addToStroke(event, prediction)
    else Stabilizálás > 0%
        Router->>Filter: history pontok + aktuális pont szűrése
        Filter-->>Router: simított StrokeInputBatch
        Router->>Wet: batch addToStroke + szűretlen predicted pontok
    end
    User->>Router: ACTION_UP
    Wet-->>State: onStrokesFinished(kész Stroke-ok)
    State->>State: hozzáadás állapothoz és SpatialIndex-hez
    State->>Cache: érintett 512x512 csempék invalidálása
    State->>Journal: ADD rekord írása (≤1s kötegelt fsync)
    Cache-->>Wet: Következő képkocka kirajzolva → removeFinishedStrokes(ids)
```

---

## 2. Lasszó Szellemkép-mentes Mozgatás (Vonások + Objektumok), Sarok-Forgatás és `✨ AI` Automatikus Formázás

```mermaid
flowchart LR
    A["Lasszó kijelölés (≥60% vonás vagy ≥2 elforgatott objektumpont; PDF kihagyva)"] --> O["1 objektum + 0 vonás → azonnali sarok-méretezés/forgatás/'Eredeti' menü"]
    A --> M["Mozgatás / Skálázás / Forgatás → excludedStrokeIds + élő lassoPreview* objektumok (nincs szellemkép)"]
    M --> R["ACTION_UP → CanvasStateHolder egyesített CompositeAction + StrokeJournal (1 lépéses Undo/Redo)"]
    A --> AI{"✨ AI gomb (csak ha van kijelölt vonás)"}
    AI -- 1 vonás és alakzat (<10 ms helyi) --> SH["ShapeRecognizer → vektoros vonal / kör / háromszög / téglalap / nyíl"]
    AI -- Összetett kijelölés (768 px PNG) --> G{"Gemini Flash + Multi-Flash Failover (60s timeout)"}
    G -- formula --> L["jlatexmath validálás → LatexObject"]
    G -- text --> T["FormattedTextObject (nem-sticky, átírható pass-through mód, HandwritingFontFamily)"]
    G -- shape --> SH2["Vektoros alakzat + ShapeMeta"]
    L & T & SH2 --> U["Atomikus CompositeAction (+ originalStrokes) + StrokeJournal → 1 lépéses Undo / 'Eredeti' gomb"]
```

---

## 3. Adatbiztonság: Append-Only Journal, Atomikus Snapshot és Crash Recovery

```mermaid
flowchart TD
    A["Oldal megnyitása"] --> B{"Létezik page.snote?"}
    B -- Igen --> C["SNOT fejléc + formatVersion ellenőrzése"]
    C -- Ismeretlen magasabb verzió --> D["Csak olvasható mód (soha nem írható felül)"]
    C -- Ismert verzió --> E["Snapshot betöltése + StrokeJournal visszajátszás"]
    E --> F["Csonka / CRC32-hibás utolsó rekord eldobása"]
    B -- Nem --> G["Üres oldal létrehozása"]
    F --> H["Aktív szerkesztés: minden ACTION_UP → append page.journal (≤1s fsync)"]
    G --> H
    H --> I{"Journal rekordok ≥ 500 vagy oldalbezárás?"}
    I -- Igen --> J["Snapshot írása temp fájlba → fsync → Files.move(ATOMIC_MOVE) → journal csonkolása"]
    I -- Nem --> H
```

---

## 4. 1-ujjas Függőleges Pan & 2-ujjas Pinch-Zoom a 144 Hz-es GPU Kompozit Gyorsítótáron

```mermaid
flowchart LR
    A["1-ujjas függőleges pan (850 ms grace + FLAG_CANCELED + tenyér-visszagörgetés) vagy 2-ujjas pinch-zoom"] --> B["InProgressStrokesView (eagerInit előmelegítéssel): requestDisallowInterceptTouchEvent(true)"]
    B --> C["StylusInputRouter (dx=0f 1-ujjnál; centroid + skála 2-ujjnál) → DryCanvasView.invalidate() & viewportTransformTick (PageObject esetén)"]
    C --> E{"CompositePageSnapshot érvényes?"}
    E -- Igen (Fix oldal aktív gesztus alatt) --> F["1x GPU Hardware Bitmap TextureOp blit (4,50 ms P50 / 6,47 ms P95)"]
    E -- Nem (Végtelen vászon / lasszó / többoldalas szél) --> G["PaperBackgroundRenderer kötegelt rajz + 512x512 LRU csempék"]
    C --> H["onNavigationGestureEnd + 100 ms settleRunnable → zoom-% frissítés & éles csempék"]
```

---

## 5. Könyvespolc Drag & Drop Rendszerezés, Szelektív PDF és NotebookLM Megosztás

```mermaid
flowchart TD
    B1["Könyvespolc (2 panel, bal oldali Kereső + Kategóriák)"] --> B2["Füzet hosszan nyomása → Drag & Drop kategóriára (NotebookEntity.folderId)"]
    B1 --> B3["Könyv '...' menü → Feltöltés NotebookLM-be..."]
    B3 --> B4["Teljes könyv / Csak adott oldal → PdfExportManager → FileProvider → Intent.ACTION_SEND"]
    B1 --> B5["Szelektív PDF Import (parsePageSelectionSpec: pl. '1-3, 5')"]
    B5 --> B6["PdfTileRenderer → kiválasztott oldalak füzetbe vagy új jegyzetbe"]
```

---

## 6. Lebegő Eszköztár, StarNote Színes Ikonok, Eszközmemória & AI Formázó

```mermaid
flowchart LR
    T1["FloatingToolDock: StarNote színes ikonok, globális (x,y), összecsukva Toll|Radír|Vissza + Teljes képernyő gomb"] --> T2["Eszközváltás → független perToolConfigs vastagság/szín/átlátszóság visszaállítás"]
    T1 --> T3["Eszköz hosszú érintése → folyamatosan széthúzható BrushSettingsPopover (Animatable 312..472 dp + dokk-oldali külső sarok-fogantyú)"]
    A1["Bal felső sarokba simuló CanvasTopToolbar AI gomb → AiCopilotSheet"] --> A2["Hatókör: Csak adott oldal / Teljes munkafüzet"]
    A2 --> A3["1. Teljes formázás (HandwritingFontFamily szöveg + ábrák + képletek)"]
    A2 --> A4["2. Csak alakzatok és képletek (kézírásos folyószöveg érintetlen marad)"]
```

---

## 7. Geometriai Alakzatok (2D→3D), Képbeszúrás & Oldalak Drag & Drop Átrendezése

```mermaid
flowchart TD
    S1["FloatingToolDock: Alakzatok gomb → animált HorizontalPager ShapePickerPopover (1. oldal 2D → 2. oldal 3D)"] --> S2["Papírhoz rögzített GeometricShapeObject (SNOT tag=6, pass-through: toll átrajzol rajta)"]
    S2 --> S3["Lasszós visszajelölésre 4 sarok méretező + szabad forgató + kitöltés/szín/törlés menü → PdfExportManager"]
    I1["FloatingToolDock: Kép beszúrása gomb → galériakép (ImageDecoder)"] --> I2["Papírhoz rögzített PageObject.ImageObject (SNOT tag=7, pass-through: toll átrajzol rajta)"]
    I2 --> I3["Lasszós visszajelölésre 4 sarok méretező + szabad forgató + zoom/törlés menü → PdfExportManager"]
    P1["Bal oldalon függőlegesen eltolható LeftPagePullTab → LeftPageDrawer"] --> P2["Drag & Drop oldalsorrend, oldal átnevezése (Room v2 title) & + Új oldal létrehozása"]
```

## 8. Inkrementális Felhőszinkron (Google Drive / WebDAV) és .zip Visszaállítás
```mermaid
flowchart LR
    U["Füzet / Könyvespolc"] --> B1["Rövid koppintás a bal felső felhő ikonon<br/>(Első alkalommal vagy beállítás után azonnali szinkron)"]
    U --> B2["Hosszú nyomás a füzetnév melletti<br/>vagy könyvespolc fejlécben lévő felhő ikonon"]
    B2 --> D["CloudBackupDialog<br/>(ESC / háttérkattintás is zárja)"]
    D --> GD["Google Drive (drive.file)<br/>Play Services OAuth → StylusNote Backups mappa"]
    D --> WD["WebDAV (NAS / Nextcloud)<br/>URL + felhasználó + Keystore AES-GCM jelszó"]
    D --> ZIP["Helyi / SAF .zip<br/>Export (.zip) & Import (.zip)"]
    GD & WD --> SCH["CloudSyncScheduler<br/>Okos automata: lap/füzetváltás + onStop + 3p nyugalmi idő<br/>vagy Energiatakarékos (30p) / Kézi (+ Wi-Fi opció)"]
    SCH --> ENG["CloudSyncEngine<br/>Room v3 tombstone + SHA-256 delta szinkron<br/>Csak módosult oldalak és fájlok feltöltése/letöltése"]
```

---

## 9. Élő Jegyzetbeli Kézírás- és Objektumkereső + Tartós AI Visszavonás

```mermaid
flowchart LR
    K1["CanvasTopToolbar: AI gomb mellett Keresés pirula"] --> K2["InNotebookSearchCoordinator (RAM élő vonások + zárt oldalak + Room FTS4)"]
    K2 --> K3{"ML Kit Digital Ink modell"}
    K3 -- Hiányzik és van kézírás --> K4["Automatikus on-demand modell letöltés"]
    K3 -- Kész / letöltve --> K5["Sorok OCR + FormattedText / Latex / Cím egyeztetés"]
    K4 --> K5
    K5 --> K6["Szószintű DocRect kiemelés (SearchHighlightCanvasOverlay) + 1/N léptetés + Oldalváltás és Auto-Pan"]
    K5 --> K7["Opcionális kézi BYOK AI mélyindexelés gomb (vonások módosítása nélkül)"]
    K6 --> K8["ESC / Vissza / X bezárás → kiemelés törlése"]
    AIU["AI formázás → Undo → Jegyzet bezárása"] --> SJ["StrokeJournal enqueueCanvasActionInverse + compactBeforeClose szinkron"]
    SJ --> RE["Újranyitás → eredeti színes tintavonások megmaradnak"]
```

---

## 10. Folytonos Többoldalas Dokumentumtér, Sliding-Window Oldalpool & Valódi FrameMetrics Telemetria

```mermaid
flowchart LR
    U1["9+ oldalas füzet megnyitása"] --> K1["1 db perzisztens AndroidView per notebookId (nincs View újragyártás lapváltáskor)"]
    K1 --> L1["ContinuousPageLayout: vegyes oldalméretek közös PDF pt térben + láthatósági vágás"]
    U1 --> M1["MultiPageSessionManager + SlidingWindowPagePool"]
    M1 --> W1["Aktív ± 1 oldal: előmelegítés háttérben, közös 192 MB tile keret"]
    M1 --> E1["Aktív ± 2 felett: aszinkron Dispatchers.IO journal lezárás és RAM felszabadítás"]
    S1["1 ujjas görgetés vagy stylus rajz bármelyik lapra"] --> H1["findSlotAtWorkspacePoint → lokális oldal-koordináták & StrokeJournal"]
    H1 --> UN["NotebookUndoCoordinator → lapok közötti pontos Undo/Redo (szükség esetén lemezes visszatöltés)"]
    K1 --> FM["MainActivity FrameMetrics + Choreographer → szétválasztott VSYNC p95 vs CPU/GPU Render p95 HUD"]
```

---

## 11. Élő PDF Háttér, Tinta Alatti Kép Horgonyzással, Valós Miniatűr Oldalfiók & Oldaltörlés

```mermaid
flowchart LR
    PDF1["Könyvespolc: PDF import"] --> PDF2["PdfTileRenderer.importPdf + PageEntity (pdfAssetId, pdfPageIndex)"]
    PDF2 --> PDF3["Vászon 2. réteg: prewarmBasePage + drawPdfPageForCanvasSyncOrRequest (aszinkron nagyítási részletcsempék + OLED opció)"]
    IMG1["Kép beszúrása → PageObject.ImageObject"] --> IMG2["DryCanvasView 2b. réteg (tinta alatt) + toll/kiemelő a 3–4. rétegen felette"]
    IMG2 --> IMG3["ObjectAttachmentGeometry: attachedObjectId rögzítése rajzoláskor"]
    IMG3 --> IMG4["Kép húzás/méretezés/forgatás/törlés → LiveObjectInteractionController + 1 lépéses Undo/Redo elengedéskor"]
    DR1["Bal oldalfiók: LeftPageDrawerOverlay"] --> DR2["PageThumbnailRenderer (16 MB LRU): papír + PDF + képek + vonások + objektumok kisképe"]
    DR1 --> DR3["Kuka ikon (>1 oldal) → ESC/háttérrel zárható megerősítés → deleteWithTombstone + szomszédos lapra váltás"]
    AI1["ConsentDialog: Gemini API-kulcs & Modellek"] --> AI2["GeminiApiSupport (sanitizeApiKey + X-Android-* fejléc + érthető REST hiba) → ModelCatalog /v1beta/models"]
```

