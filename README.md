# ClipTray

ClipTray is a lightweight desktop clipboard history manager for Windows and other graphical desktop environments. It runs in the system tray, monitors copied text in the background, and stores history locally in SQLite.

Built with **Java 17+**, **Swing**, **FlatLaf**, **SQLite JDBC**, and **JNativeHook**.

## Features

- **System tray access** to open history, view settings, or exit.
- **Global shortcut:** press **Ctrl + Alt + V** to show or hide clipboard history.
- **Background text monitoring** with low-frequency polling and consecutive duplicate handling.
- **Local SQLite storage** with search, copy, delete, and recent-history views.
- **Portable Windows app-image** generated with `jpackage`; it includes a Java runtime and launches without a console window.

> [!NOTE]
> **UI status:** The current interface—especially its buttons and overall styling—is temporary and still far from the polish we want. It was implemented under time constraints and is due for a complete overhaul soon. Feedback and contributions are welcome.

## Requirements

- Java 17 or newer
- Maven 3.8 or newer
- A graphical desktop session (AWT/Swing, system tray, clipboard, and global hotkey features are not available in headless environments)

To create the portable Windows app-image, use Windows with a JDK that includes `jpackage`.

## Build and run

Build the executable JAR and run it with Java:

```powershell
mvn clean package
java -jar target\cliptray-1.0.0.jar
```

The JAR includes SQLite JDBC, JNativeHook, and FlatLaf. Native global hotkey availability depends on OS permissions and desktop environment support.

## Package a portable Windows build

From PowerShell on Windows, run:

```powershell
.\package-exe.ps1
```

The script builds the application and creates:

- `target\portable\app-image\ClipTray\` — portable launcher and bundled Java runtime; distribute the whole directory.
- `target\portable\ClipTray-1.0.0-portable.zip` — ZIP archive of the app-image.

No installer or WiX Toolset is required. An optional application icon can be placed at `assets\cliptray.ico` or `src\main\resources\cliptray.ico`, or supplied explicitly:

```powershell
.\package-exe.ps1 -IconPath .\assets\cliptray.ico
```

## Local data

Clipboard history is stored on the current device. On Windows, ClipTray uses `%APPDATA%\ClipTray`; on other platforms, it uses `~/.cliptray`.

Only text is monitored. Repeated polls of the same text are ignored, and copying the most recent text again updates its timestamp rather than creating a duplicate consecutive row.

## License

ClipTray is distributed under the MIT License. See [LICENSE](LICENSE).

## Contributing

Issues and pull requests are welcome. Before opening a pull request, run:

```powershell
mvn verify
```
