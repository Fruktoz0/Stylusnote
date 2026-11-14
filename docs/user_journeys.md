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

## 2. Lasszó → Natív LaTeX Konverzió és Undo

```mermaid
flowchart LR
    A["Lasszó kijelölés (≥60% pont bent)"] --> B["Világos módú nagyfelbontású Bitmap renderelés (OLED módtól függetlenül)"]
    B --> C{"AiConsentStore engedélyezve?"}
    C -- Nem --> D["ConsentDialog (EEA/UK vs. free tier tájékoztató)"]
    D -- Elfogadva --> E
    C -- Igen --> E["GeminiClient (Flash modell, JSON {latex})"]
    E --> F["Kódkerítés tisztítás + jlatexmath-android validálás"]
    F -- Hibás LaTeX --> G["Hibaüzenet; eredeti kézírás érintetlen"]
    F -- Érvényes --> H["LatexOverlayNode beszúrása CompositeAction(RemovedStrokes + AddedObject)"]
    H --> I["Undo esetén egyetlen lépésben visszaáll az eredeti kézírás"]
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

