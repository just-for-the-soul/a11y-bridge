# A11y Bridge

**Give your AI agent eyes and hands on Android — in 50ms, not 5 seconds.**

A 16KB Android Accessibility Service that exposes the live UI tree over HTTP (`localhost:7333`), enabling AI agents to read and interact with any Android app instantly.

## The Problem

The traditional way for AI agents to control Android:

```
screencap → pull screenshot → uiautomator dump → pull XML → parse → calculate coordinates → input tap x y
```

**Each cycle takes 3-5 seconds.** Controlling a complex app with 15+ steps? That's a minute of slow-motion replays.

## The Solution

A11y Bridge runs an Accessibility Service on the device that exposes a local HTTP API:

```bash
# Read the full UI tree (~50ms)
curl http://localhost:7333/screen

# Click by text — no coordinate math needed
curl -X POST http://localhost:7333/click -d '{"text":"Send"}'
```

**100x faster.** Same information, zero file transfers.

## How it Works

Android's [Accessibility Service](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) was designed for screen readers — it provides real-time access to the complete UI tree of any app. A11y Bridge wraps this in a lightweight HTTP server:

```
┌─────────────┐     HTTP      ┌──────────────────┐
│  AI Agent   │ ◄──────────► │  A11y Bridge APK  │
│  (curl/SDK) │  localhost    │  (16KB, on device) │
└─────────────┘   :7333      └────────┬─────────┘
                                       │
                              AccessibilityService
                                       │
                              ┌────────▼─────────┐
                              │   Any Android App  │
                              └────────────────────┘
```

## Quick Start

### Download

Grab the latest APK from [Releases](https://github.com/4ier/a11y-bridge/releases).

### Install

```bash
# Install
adb install openclaw-a11y.apk

# Enable the accessibility service
adb shell settings put secure enabled_accessibility_services \
  com.openclaw.a11y/.ClawAccessibilityService
adb shell settings put secure accessibility_enabled 1

# Forward port
adb forward tcp:7333 tcp:7333

# Test
curl http://localhost:7333/ping
# → {"status":"ok","service":"openclaw-a11y"}
```

### Build from Source

Requires: Android SDK (build-tools 34, platform android-34), JDK 11+

```bash
chmod +x build.sh
./build.sh
# → ✅ Build complete: openclaw-a11y.apk (20K)
```

## API

### `GET /ping`
Health check.

```json
{"status": "ok", "service": "openclaw-a11y"}
```

### `GET /screen`
Returns the full UI tree as JSON.

```bash
curl http://localhost:7333/screen
```

```json
{
  "package": "com.android.settings",
  "timestamp": 1707500000000,
  "nodes": [
    {"text": "Settings", "bounds": "0,0,1080,2340", "click": true},
    {"text": "Network & internet", "id": "android:id/title", "bounds": "0,200,1080,300", "click": true},
    {"text": "Search settings", "bounds": "100,50,980,150", "click": true, "edit": true}
  ],
  "count": 42
}
```

Add `?compact` to only return nodes with meaningful content (text, clickable, editable, etc).

### `POST /click`
Click an element by text, resource ID, or content description.

```bash
# By visible text (case-insensitive partial match)
curl -X POST http://localhost:7333/click \
  -d '{"text": "Settings"}'

# By resource ID
curl -X POST http://localhost:7333/click \
  -d '{"id": "com.app:id/send_button"}'

# By content description
curl -X POST http://localhost:7333/click \
  -d '{"desc": "Navigate up"}'
```

Response:
```json
{"clicked": true, "x": 540, "y": 960, "matchedText": "Settings"}
```

Uses `AccessibilityNodeInfo.performAction(ACTION_CLICK)` first (most reliable), falls back to gesture-based tap if the action isn't supported.

### `POST /tap`
Tap at exact coordinates (when you need pixel-level control).

```bash
curl -X POST http://localhost:7333/tap \
  -d '{"x": 540, "y": 960}'
```

## Node Properties

Each node in the `/screen` response can include:

| Field | Type | Description |
|-------|------|-------------|
| `text` | string | Visible text |
| `desc` | string | Content description (accessibility label) |
| `id` | string | Resource ID (`com.app:id/name`) |
| `cls` | string | View class name |
| `bounds` | string | `left,top,right,bottom` screen coordinates |
| `click` | bool | Element is clickable |
| `edit` | bool | Element is editable (text input) |
| `scroll` | bool | Element is scrollable |
| `checkable` | bool | Element is a checkbox/switch |
| `checked` | bool | Checkbox/switch is on |
| `focused` | bool | Element has focus |
| `selected` | bool | Element is selected |
| `depth` | int | Tree depth (full mode only) |

## Performance

| Operation | uiautomator dump | A11y Bridge |
|-----------|-----------------|-------------|
| Read UI tree | 3-5 seconds | ~50ms |
| Click element | Calculate bounds → `input tap` | `{"text": "OK"}` |
| Full interaction cycle | 5-8 seconds | 100-200ms |

## Compatibility

- **Minimum SDK**: Android 7.0 (API 24)
- **Target SDK**: Android 14 (API 34)
- **Tested on**: Pixel 4 XL (Android 13)
- **APK size**: ~16-20KB
- **Works with**: All apps that expose accessibility nodes (most apps do)

## Security Note

- The HTTP server binds to `127.0.0.1` only — not accessible from the network
- Access requires `adb forward` — only the connected computer can reach it
- The Accessibility Service can read all UI content — treat it like root access to the UI layer
- **Don't install on devices with sensitive data you don't control**

## Use with AI Agents

This was built for [OpenClaw](https://github.com/openclaw/openclaw) but works with any AI agent framework. The HTTP API is framework-agnostic:

```python
import requests

def read_screen():
    return requests.get("http://localhost:7333/screen").json()

def click(text):
    return requests.post("http://localhost:7333/click", json={"text": text}).json()

# Example: Open Settings and tap Wi-Fi
click("Settings")
time.sleep(1)
screen = read_screen()
click("Network & internet")
```

## How the "Surgery" Happened

This project started as a [blog post](https://x.com/karry_viber/status/2020997251699720611) about giving an AI agent running on an Android phone a "corrective eye surgery" — replacing the slow screencap + uiautomator dump cycle with a real-time accessibility bridge.

The analogy: the old approach was like a nearsighted person taking off their glasses and squinting at the screen every few seconds. The new approach gives the agent clear, always-on vision.

## License

MIT
