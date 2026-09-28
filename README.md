# yr2-logic-monitor

High-performance real-time interactive debugger and monitor for Mindustry v160.5.

A refurbished, modernized evolution of `yaddrx2`'s classic mod, fully upgraded for **Mindustry v160.5** (Java 17, with unified desktop and Android support).

QQ Community Group: **615081546** (Mindustry Logic & Hardware Research Center)

---

## Compatibility & Specifications

| Item | Details |
|---|---|
| Original Target | Mindustry v146 |
| Current Target | Mindustry **v160.5** (Build 160.5, compatible with MindustryX and custom forks) |
| Mod Version | **1.6.0** |
| Supported Platforms | Windows / Linux / macOS (Desktop) & Android (Unified JAR containing `classes.dex`) |
| UI & Visual Style | Pure symbolic Zero-i18n design (no language pack barriers), orthogonal semantic palette |

---

## Features & Controls Manual

### 1. Main Manager Window (`yr2lm`)
* **`add`**: Pick an in-world building to attach and open a floating monitor.
* **`copy`**: Copy target building configuration and logic code to the clipboard.
* **`paste`**: Apply clipboard configuration to target building.
* **Building List**: Hover to highlight world position and draw a screen connecting line.
  * **[Eye]**: Toggle window visibility.
  * **[Refresh]**: Manually refresh monitor data.
  * **[Trash]**: Close and remove the monitor.

---

### 2. Logic Processor Monitor

#### (1) Variable Table Tools
* **[Rotate]**: Reset the `@counter` instruction pointer to `0`.
* **[Trash]**: Full debug session reset: reset instructions to line 0, clear trace history, execution heat, and snapshots.
* **[Eye]**: Toggle permanent in-world target tracer line for object variables (Units / Buildings).
* **[Pencil]**: Toggle variable table / code editor view.
* **Search Field**: Live variable name filter (updates instantly without layout delays).
* **[Cc]**: Toggle case-sensitive variable search.
* **[W]**: Toggle whole-word matching.
* **`textBuffer`**: Monitored print buffer strictly left-aligned with variable names.

#### (2) Code Editor Tools
* **[Refresh]**: Reload source code from the processor.
* **[Save]**: Upload and assemble modified code to the processor.
* **[Tree]**: Toggle dual-column view (Variables + Code Editor) on wide screens.
* **[Pause / Play]**: Pause or resume code execution.
* **[Left Arrow]**: Single-step execution (advances 1 instruction and records trace).
* **[Undo Arrow]**: Run to next breakpoint.
* **[List]**: Toggle the Trace Timeline panel.

#### (3) Trace Timeline Panel
* **[Copy]**: Export complete trace history log to system clipboard.
* **[Trash]**: Clear trace history and step counter.
* **Sequential Step Collapsing**: Normal steps with no jumps or data changes are auto-collapsed into compact `[L.. -> L..] (N steps)`.
* **Milestone Tracking**: Captures control-flow jumps (`@counter` assignment/jump) and composite multi-variable mutations (`var: old -> new`).
* **Time-Travel Snapshots**: Click any historical step to focus code with an emerald searchlight, rewind the variable table to that snapshot, and mark altered values with `[diff]`.
* **Snapshot Rollback Bar**: Shows `<< Step #...` with a `[↺]` button to immediately exit snapshot view and restore live real-time values.

#### (4) Code Lines & Breakpoints
* **Click Line Number Margin**: Set or clear breakpoints.
* **Running Indicator**: Real-time `>>` text pointer tracks current execution during normal play without visual laser distraction; paused mode activates the emerald green indicator bar and heat track.
* **5 Inline Micro-Buttons** (Available when Trace mode is off):
  * **[Pencil]**: Inline code line edit.
  * **[Add]**: Insert a blank code line below.
  * **[Down Clipboard]**: **Import code block with automatic `jump` destination index adjustment**.
  * **[Refresh]**: Revert line to its original code.
  * **[Cancel]**: Delete current line.

---

### 3. Memory Bank & Cell Monitor
* **Index Range (Start / End)**: Customize monitored memory indices.
* **Decimals Slider (0-10)**: Real-time precision adjustment with automatic trailing zero truncation.
* **Columns Slider (1-16)**: Adjust display column count.
* **[Edit / Save]**: In-place memory value editing and save mode.
* **Data Mutation Flash**: Values modified by logic trigger a smooth warm-golden fading flash.
* **Entity Tracer**: Hovering over stored objects (Units / Buildings) displays in-world visual tracer lines.

---

### 4. Message Monitor
* **[Pause / Play]**: Pause or resume message capturing.
* **[Refresh]**: Manually refresh messages from the message block.
* **[Trash]**: Clear message history.
* **128-Log Ring Buffer**: Prevents memory leaks; supports one-click copying and deleting of individual logs.

---

### 5. Global Interaction & UX
* **[Lock] Pin to World**: Anchors window to target building (Top-Center anchor), zooms smoothly with map camera, positioned beneath HUD to never block player controls.
* **[Zoom] Scaling Mode Toggle**: When pinned, toggle between **Holographic Camera Follow** and **Locked 1:1 UI Pixel Scale** (pure icon toggle, no black borders).
* **[Eye] Capsule Collapse**: Collapses window into a 30px title capsule with accurate indicator anchoring.
* **[Resize] Corner Drag-Resize**: Micro grip at bottom-right corner of both floating windows and embedded panels for fluid resizing and automatic size preference memory.
* **In-Game Gear Injection**: Clicking logic/memory/message blocks adds a gear icon:
  * **Click**: Expands lightweight embedded panel directly below the config bar.
  * **Drag**: Displays 1:1 preview rectangle and spawns a floating window upon release.
* **Bottom-Right "y" Button**: Injected into building bar to reopen the main manager when hidden.
* **Dual-Platform Unified Package**: Contains `classes.dex` for seamless plug-and-play on both PC and Android.

---

## Build & Developer Guide

### Prerequisites (Windows Java 17+ NIO Loopback Socket Fix)
Java 17+ on Windows fails with `Unable to establish loopback connection` when `%TEMP%` contains short 8.3 paths (`~`).
**Set temporary environment variables before building**:
```powershell
$env:TEMP = "D:\tmp"; $env:TMP = "D:\tmp"
```

### Build Commands
* **Desktop JAR Build**:
  ```bash
  ./gradlew jar --offline
  # Output: build/libs/yr2-logic-monitorDesktop.jar
  ```
* **Desktop + Android Unified JAR Build**:
  ```bash
  ./gradlew deploy --offline
  # Output: build/libs/yr2-logic-monitor.jar (includes classes.dex)
  ```

---

## Source Architecture

| File | Responsibility |
|---|---|
| `src/yr2lm/Yr2Vars.java` | Global emerald theme color (`#00e5a3`) and singleton instances |
| `src/yr2lm/Yr2lmain.java` | Mod entry point, block configurable injection and event hooks |
| `src/yr2lm/graphics/DrawExt.java` | In-world entity tracing, screen lines, bounding boxes |
| `src/yr2lm/ui/Yrailiuxa2.java` | Window base class (Top-Center world anchoring, layer management, corner resize) |
| `src/yr2lm/ui/Monitor.java` | Monitor base class (lifecycle entity binding, hover highlights) |
| `src/yr2lm/ui/Combination.java` | Main window manager (Add/Copy/Paste, monitor tracking) |
| `src/yr2lm/ui/ConfigInjector.java` | In-game config bar gear injection, embedded pane, drag-spawn |
| `src/yr2lm/ui/LogicMonitor.java` | Logic processor debugger (variable table, dual-track editor, trace timeline) |
| `src/yr2lm/ui/MemoryMonitor.java` | Memory bank/cell debugger (multi-column sliders, data flash, entity lines) |
| `src/yr2lm/ui/MessageMonitor.java` | Message block debugger (ring log buffer, copy, pause) |
| `src/yr2lm/util/LogicHighlight.java` | Dynamic mlog syntax highlighting based on active block registries |
| `src/yr2lm/util/MemUtil.java` | Memory bridge (double private array support, precision formatting) |
