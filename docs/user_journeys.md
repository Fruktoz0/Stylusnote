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

## 2. Lasszó Szellemkép-mentes Mozgatás és `✨ AI` Automatikus Formázás

```mermaid
flowchart LR
    A["Lasszó kijelölés (≥60% pont bent)"] --> M["Húzás közben excludedStrokeIds → eredeti hely üres, nincs szellemkép"]
    A --> AI{"✨ AI gomb (LassoSelectionActionBar)"}
    AI -- 1 vonás és alakzat (<10 ms helyi) --> SH["ShapeRecognizer → vektoros vonal / kör / háromszög / téglalap / nyíl"]
    AI -- Összetett kijelölés (768 px PNG) --> G{"Gemini Flash JSON besorolás"}
    G -- formula --> L["jlatexmath validálás → LatexObject"]
    G -- text --> T["FormattedTextObject (HandwritingFontFamily emberi íráshoz hasonló stílus)"]
    G -- shape --> SH2["Vektoros alakzat + ShapeMeta"]
    L & T & SH2 --> U["Atomikus CompositeAction + StrokeJournal → 1 lépéses Undo"]
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

## 4. Kétujjas Pan & Pinch-Zoom a 144 Hz-es GPU Kompozit Gyorsítótáron

```mermaid
flowchart LR
    A["2 ujjas érintés (ACTION_DOWN / POINTER_DOWN)"] --> B["InProgressStrokesView: requestDisallowInterceptTouchEvent(true)"]
    B --> C["StylusInputRouter.handleTwoFingerNavigation (centroid horgony + skála)"]
    C --> D["ADPF PerformanceHintManager (6,94 ms céltartam) + DryCanvasView.invalidate()"]
    D --> E{"CompositePageSnapshot érvényes?"}
    E -- Igen (Fix oldal aktív gesztus alatt) --> F["1x GPU Hardware Bitmap TextureOp blit (~4,47 ms P50 / 6,04 ms P95)"]
    E -- Nem (Végtelen vászon / lasszó húzás / túlnagyítva) --> G["PaperBackgroundRenderer kötegelt rajz + 512x512 LRU csempék"]
    C --> H["100 ms inaktivitás (settleRunnable)"]
    H --> I["Háttérszálas rebakeSettledScale → éles csempék + GPU Snapshot frissítése"]
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

## 6. Lebegő Eszköztár, 2 Lépcsős Ecsetfül és AI Formázó (Oldal / Munkafüzet)

```mermaid
flowchart LR
    T1["FloatingToolDock: globális (x,y) + összecsukott/nyitott állapot (ToolDockPreferencesStore)"] --> T2["Összecsukva: Toll | Radír | Vissza + Megnyitás"]
    T1 --> T3["Eszköz hosszú érintése → 2 lépcsős BrushSettingsPopover (312 dp lényegi fül ⇄ 472 dp teljes stúdió)"]
    A1["Bal felső AI gomb → AiCopilotSheet (ESC / backdrop zárás)"] --> A2["Hatókör: Csak adott oldal / Teljes munkafüzet"]
    A2 --> A3["1. Teljes formázás (HandwritingFontFamily szöveg + ábrák + képletek)"]
    A2 --> A4["2. Csak alakzatok és képletek (kézírásos folyószöveg érintetlen marad)"]
```

