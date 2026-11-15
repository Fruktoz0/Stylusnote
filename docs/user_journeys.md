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

## 2. Lasszó Szellemkép-mentes Mozgatás, Sarok-Forgatás és `✨ AI` Automatikus Formázás

```mermaid
flowchart LR
    A["Lasszó kijelölés (≥60% pont bent)"] --> M["Húzás / Sarok-forgatás közben excludedStrokeIds → eredeti hely üres, nincs szellemkép"]
    M --> R["Élő preScale / preRotate előnézet → ACTION_UP: AffineTransform2D véglegesítés"]
    A --> AI{"✨ AI gomb (LassoSelectionActionBar)"}
    AI -- 1 vonás és alakzat (<10 ms helyi) --> SH["ShapeRecognizer → vektoros vonal / kör / háromszög / téglalap / nyíl"]
    AI -- Összetett kijelölés (768 px PNG) --> G{"Gemini Flash + Multi-Flash Failover (60s timeout)"}
    G -- formula --> L["jlatexmath validálás → LatexObject"]
    G -- text --> T["FormattedTextObject (nem-sticky kijelölés, HandwritingFontFamily)"]
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
    A["1-ujjas függőleges pan (650 ms tenyérvédelem + 14 px slop) vagy 2-ujjas pinch-zoom"] --> B["InProgressStrokesView: requestDisallowInterceptTouchEvent(true)"]
    B --> C["StylusInputRouter (dx=0f 1-ujjnál; centroid + skála 2-ujjnál) → DryCanvasView.invalidate() & viewportTransformTick (PageObject esetén)"]
    C --> E{"CompositePageSnapshot érvényes?"}
    E -- Igen (Fix oldal aktív gesztus alatt) --> F["1x GPU Hardware Bitmap TextureOp blit (4,37 ms P50 / 6,05 ms P95)"]
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
    T1 --> T3["Eszköz hosszú érintése → eszközhöz igazított 2 lépcsős BrushSettingsPopover (bal alsó '◢' sarok-fogantyú → teljes stúdió + átlátszóság)"]
    A1["Bal felső sarokba simuló CanvasTopToolbar AI gomb → AiCopilotSheet"] --> A2["Hatókör: Csak adott oldal / Teljes munkafüzet"]
    A2 --> A3["1. Teljes formázás (HandwritingFontFamily szöveg + ábrák + képletek)"]
    A2 --> A4["2. Csak alakzatok és képletek (kézírásos folyószöveg érintetlen marad)"]
```

---

## 7. Geometriai Alakzatok (2D→3D), Képbeszúrás & Oldalak Drag & Drop Átrendezése

```mermaid
flowchart TD
    S1["FloatingToolDock: Alakzatok gomb → animált HorizontalPager ShapePickerPopover (1. oldal 2D → 2. oldal 3D)"] --> S2["Papírhoz rögzített GeometricShapeObject (SNOT tag=6) pan/zoom szinkronnal"]
    S2 --> S3["4 sarok méretező + felső forgató fogantyú + kitöltés/szín/törlés menü → PdfExportManager vektoros export"]
    I1["FloatingToolDock: Kép beszúrása gomb → galériakép (ImageDecoder)"] --> I2["Papírhoz rögzített PageObject.ImageObject (SNOT tag=7, pages/<id>/images/)"]
    I2 --> I3["4 sarok méretező + felső forgató fogantyú + zoom/forgatás/törlés menü → PdfExportManager"]
    P1["Bal oldalon függőlegesen eltolható LeftPagePullTab → LeftPageDrawer"] --> P2["Drag & Drop oldalsorrend módosítás (reorderPagesInNotebook) & + Új oldal létrehozása"]
```

