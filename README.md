# APK Builder Studio

A premium Material Design 3 Android app for building APKs from source code repositories.

## Features

- Material Design 3 (Material You) with dynamic color support
- Repository URL input with branch selection
- File upload functionality
- App configuration (package name, version, SDK settings)
- Debug and Release build types
- Real-time build progress with live log output
- Build history with status tracking
- Dark/Light theme with Material 3 color scheme
- Bottom navigation with 3 tabs (Home, Build, History)

## Tech Stack

- Kotlin
- Android Jetpack (Navigation, Lifecycle, ViewBinding, DataStore)
- Material Design 3 (Material You)
- Coroutines & Flow
- RecyclerView with ListAdapter
- GitHub Actions CI/CD

## Building

### Local Build

1. Open project in Android Studio
2. Sync Gradle
3. Click Run or Build APK

### GitHub Actions Build

1. Push code to GitHub repository
2. GitHub Actions workflow will automatically:
   - Set up JDK 17
   - Set up Gradle 8.5
   - Build Debug & Release APKs
3. Download APKs from the Actions > Artifacts section

## GitHub Actions Workflow

The workflow file is at `.github/workflows/build-apk.yml`.

It triggers on:
- Push to main/master branch
- Pull request to main/master branch
- Manual dispatch (from Actions tab)

After build, APKs are uploaded as artifacts with 30-day retention.

## Screens

### Home Screen
- App logo and tagline
- Build statistics (total builds, successful builds)
- Quick action cards for New Build and History

### Build Screen
- Repository URL input
- Branch name input
- App configuration fields:
  - App Name
  - Package Name
  - Version Name & Code
  - Min SDK & Target SDK
- File upload with file list
- Debug/Release toggle switch
- Start Build button
- Real-time build progress with percentage
- Live build log output (terminal style)

### History Screen
- List of all past builds
- Each item shows:
  - App name and package
  - Build type (Debug/Release)
  - Progress bar
  - Status (Idle, Building, Success, Failed)
  - Timestamp
- Delete individual builds

## Material Design 3

This app uses Material Design 3 with:
- Dynamic color support (Android 12+)
- M3 color system (primary, secondary, tertiary, error)
- M3 typography scale
- M3 component styles (Cards, Buttons, TextFields, Chips, Switches)
- Elevated cards with rounded corners (24dp)
- M3 navigation bar
- Dark/Light theme with proper color tokens

## License

MIT License - feel free to use and modify.
