# Automata

An Android automation framework that compares ride prices across multiple ride-hailing apps in real-time and books the best option — all without touching a single API.

Built entirely with **Accessibility Services** and **on-device OCR**, Automata interacts with ride-hailing apps the same way a human would: by reading the screen, tapping buttons, and making decisions.

> **Disclaimer:** This is an educational project built to explore Android accessibility services, on-device OCR, and cross-app automation. It is not intended for commercial use or distribution. Use responsibly and at your own risk.

---

## What It Does

1. **Opens ride-hailing apps** (PickMe, Uber) one by one
2. **Enters your destination** and navigates to the pricing screen
3. **Reads prices using OCR** (ML Kit text recognition on screenshots)
4. **Compares prices** (or ETAs) and picks the best option
5. **Books the winning ride** automatically
6. **Shows results** via a floating overlay and notification

All of this happens in about 30–60 seconds with no manual input after you tap "Go".

---

## How It Works

Automata doesn't use any ride-hailing APIs. Instead, it uses two Android system features:

### Accessibility Service
Android's AccessibilityService gives apps the ability to observe and interact with the UI of other apps. Automata uses this to:
- Read UI element text and properties
- Tap buttons, enter text, scroll, and navigate
- Detect which screen is currently displayed

### On-Device OCR
Some apps (especially Flutter-based ones) don't expose useful text through accessibility nodes. For these, Automata takes a screenshot and runs **ML Kit Text Recognition** locally on the device to read prices, labels, and buttons directly from the screen pixels.

### Step-Based Automation Engine
Every automation task is broken down into a sequence of **steps**. Each step has:
- A **wait condition** — polls the accessibility tree until the expected screen appears
- An **action** — what to do once the condition is met (tap, read, enter text)
- **Timeout and retry logic** — handles slow loading, unexpected popups, and errors

```
Force-close apps
    → Launch PickMe → Enter destination → Read price
    → Launch Uber → Enter destination → Read price
    → Compare prices → Pick winner
    → Re-open winner → Book the ride
```

---

## Architecture

```
┌─────────────────────────────────────┐
│        UI (Jetpack Compose)         │
│   Dashboard · Task Config · Settings│
└──────────────┬──────────────────────┘
               │
       ┌───────▼────────┐
       │  MainViewModel  │
       └───┬─────────┬───┘
           │         │
   ┌───────▼───┐  ┌──▼──────────────┐
   │  Engine   │  │   Data Layer    │
   │ + Scripts │  │ Room · SharedPrefs│
   └───────┬───┘  └─────────────────┘
           │
   ┌───────▼──────────────┐
   │ Accessibility Service │
   │    + ML Kit OCR       │
   └───────────────────────┘
```

### Key Components

| Component | What it does |
|---|---|
| **AutomationEngine** | Manages the step sequencer, app lifecycle, and accessibility integration |
| **StepSequencer** | Executes steps sequentially with polling, timeouts, and retries |
| **ActionExecutor** | Low-level gestures — tap, type, scroll, press home/back |
| **ScreenReader** | Takes screenshots and runs OCR to extract text and positions |
| **NodeFinder** | Traverses the accessibility tree to find UI elements |
| **RideOrchestrator** | Composes app-specific scripts into a full comparison workflow |
| **PickMeScript / UberScript** | App-specific step sequences for navigating each ride app |

### Data Flow

```
User taps "Go"
    → ViewModel validates config (destination, network, apps installed)
    → RideOrchestrator builds step list from TaskConfig
    → AutomationEngine runs steps via StepSequencer
    → Each step polls accessibility tree → executes action → returns result
    → Collected data (prices, ETAs) flows through StepContext
    → Orchestrator compares and decides winner
    → Conditional booking steps run for the winning app only
    → Result shown via overlay + notification
```

---

## Tech Stack

| Category | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Database | Room (SQLite) |
| OCR | Google ML Kit Text Recognition (on-device) |
| System | Android Accessibility Service |
| Architecture | MVVM + StateFlow |
| Build | Gradle with KSP |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |

---

## Project Structure

```
com.jayathu.automata/
├── engine/              # Core automation engine
│   ├── AutomationEngine     # Main orchestrator
│   ├── StepSequencer         # Step execution with polling and retries
│   ├── AutomationStep        # Step definition (wait + action)
│   ├── ActionExecutor         # Gestures (tap, type, scroll)
│   ├── ScreenReader           # Screenshot capture + OCR
│   ├── NodeFinder             # Accessibility tree traversal
│   └── ErrorMapper            # User-friendly error messages
│
├── scripts/             # App-specific automation scripts
│   ├── PickMeScript          # PickMe navigation and price reading
│   ├── UberScript            # Uber navigation and price reading
│   └── RideOrchestrator      # Multi-app comparison workflow
│
├── service/             # Android system services
│   └── AutomataAccessibilityService
│
├── notification/        # Overlays and notifications
│   ├── AutomationNotificationManager  # Progress and result notifications
│   ├── ComparisonOverlay              # Floating price comparison card
│   └── AutomationControlOverlay      # Floating timer + stop button
│
├── data/                # Persistence
│   ├── db/                   # Room database, DAOs, entities
│   ├── model/                # TaskConfig, SavedLocation, RideApp, DecisionMode
│   ├── repository/           # Data access layer
│   └── PreferencesManager    # SharedPreferences wrapper
│
└── ui/                  # Presentation
    ├── screens/              # Compose screens (Dashboard, TaskConfig, Settings)
    ├── navigation/           # Navigation graph
    ├── theme/                # Colors, typography
    └── MainViewModel         # Central state management
```

---

## Features

- **Multi-app price comparison** — reads prices from PickMe and Uber
- **Two decision modes** — Cheapest (lowest price) or Fastest (shortest ETA)
- **Automatic booking** — books the winning ride without manual intervention
- **Floating control overlay** — always-visible timer and stop button during automation
- **Price comparison popup** — overlay showing both prices and savings
- **OCR price sanitization** — handles comma-as-period misreads, dropped decimals
- **Conditional booking** — only the winning app's booking steps execute
- **Saved tasks** — store destination/pickup/preferences for one-tap automation
- **Settings** — configurable overlay duration, notification sound, auto-close apps, debug mode

---

## Challenges and Learnings

Building this project involved solving several non-trivial problems:

- **Flutter apps and accessibility** — PickMe is built with Flutter, which has limited accessibility node support. OCR-based coordinate tapping was the only reliable way to interact with it.
- **OCR accuracy** — Commas read as periods (`7,733` → `7.733`), missing decimals, and digit misreads (`l` → `1`, `O` → `0`) required a multi-layered sanitization pipeline.
- **Cross-app state management** — Passing data (prices, winner decision) across steps that span two different apps, with the automation engine as the only shared context.
- **Overlay permissions** — `TYPE_ACCESSIBILITY_OVERLAY` lets you draw over other apps without requesting `SYSTEM_ALERT_WINDOW`, but touch handling (touchable vs pass-through) needs careful flag management.
- **Timing and reliability** — Apps load at different speeds, popups appear unpredictably, and UI layouts change between updates. The polling + retry + timeout pattern handles this gracefully.

---

## Installation

### Download the APK

1. Go to the [Releases](../../releases) page
2. Download the latest `automata-v*.apk` file
3. Transfer the APK to your Android device (or download directly on the device)
4. Open the APK file on your device
   - If prompted, allow installation from unknown sources: **Settings → Apps → Special access → Install unknown apps** → enable for your browser or file manager
5. Tap **Install**

### Build from Source

If you prefer to build it yourself:

```bash
git clone https://github.com/jayathuc/Automata.git
cd Automata
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Transfer it to your device and install.

> **Requirements:** Android Studio, JDK 11+, Android SDK 26+

---

## Getting Started

### Prerequisites

- Android 8.0 or higher
- Both **PickMe** and **Uber** must be installed and logged in on the device

### First-Time Setup

1. **Open Automata** after installing
2. **Enable the accessibility service** — the app will prompt you. Go to **Settings → Accessibility → Automata** and turn it on
3. **Grant notification permission** when prompted (Android 13+)

### Using the App

1. **Create a task** — tap the + button and enter:
   - Destination address (as you'd type it in the ride app)
   - Pickup address (optional — uses current location if left empty)
   - Which apps to compare (PickMe, Uber, or both)
   - Decision mode: **Cheapest** or **Fastest**
2. **Tap "Go"** on your saved task
3. **Switch to your home screen** — Automata will take over from here
4. **Watch it work** — a floating pill shows elapsed time and a stop button
5. **See results** — a popup overlay shows the price comparison and which app was booked

### Settings

Open Settings from the top bar to configure:

| Setting | What it does |
|---|---|
| Auto-enable location | Turns on GPS automatically if it's off when automation starts |
| Auto-bypass "someone else" prompt | Automatically taps "No, it's for me" if Uber asks |
| Show comparison overlay | Floating card showing both prices after comparison |
| Overlay duration | How long the comparison popup stays visible (5–15 seconds) |
| Notification sound | Play sound when results are ready |
| Auto-close apps | Close ride apps after booking is complete |
| Default decision mode | Cheapest or Fastest |

### Stopping a Running Automation

- Tap the **STOP** button on the floating pill (visible on any screen), or
- Open Automata and tap the stop button on the dashboard

---

## Disclaimer

This project is built purely for **educational and research purposes** — to explore what's possible with Android accessibility services, on-device ML, and cross-app automation.

- It is **not affiliated** with Uber, PickMe, or any ride-hailing company
- It may violate the Terms of Service of the apps it interacts with
- **Do not use** this for any commercial purpose
- Use at your own risk — automated interactions may result in unintended bookings

The code is shared to demonstrate Android development techniques. If you're a developer interested in accessibility services, OCR, or automation — this is a reference implementation, not a product.

---

## License

This project is provided as-is for educational purposes. See [LICENSE](LICENSE) for details.
