# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PiliPlus is a third-party BiliBili client written in Flutter/Dart, targeting Android, iOS, Windows, Linux, and macOS. It is a fork lineage of pilipala → PiliPalaX → PiliPlus. Code comments, UI strings, and commit style are predominantly Chinese (primary locale: `zh_CN`). API knowledge comes from the bilibili-API-collect community documentation.

## Commands

Flutter is pinned to an exact version (3.47.6) via `pubspec.yaml` `environment:` and `.fvmrc` — use FVM (`fvm flutter ...`) if installed. Many dependencies are git forks pinned to specific refs, so `pubspec.lock` matters.

```bash
flutter pub get                 # install dependencies
flutter analyze                 # lint / static analysis
flutter test                    # run all tests (test/ is minimal)
flutter test test/utils/accounts/deleted_account_test.dart   # single test file
flutter run -d windows          # run on desktop (or -d <android-device-id>)
flutter build apk --release     # android; see CI for full release flags
```

Code generation:
- `*.g.dart` files (json_serializable via `json_annotation`): `dart run build_runner build --delete-conflicting-outputs`
- JNI bindings for the Android Java helpers: `dart run tool/jnigen.dart` → regenerates `lib/utils/android/bindings.g.dart`

## Critical: Flutter SDK patching

`lib/scripts/patch.ps1` applies `.patch` files from `lib/scripts/` **directly to the local Flutter SDK framework code** (plus `lib/scripts/cupertino/` and `lib/scripts/material/`), fixing framework bugs this app hits. CI runs it before every release build. Consequences:

- A plain local `flutter run` uses an unpatched SDK — behaviors fixed by patches (bottom sheets, text selection, scroll, navigator, etc.) may differ from release builds.
- If framework-related behavior seems wrong, check whether a patch in `lib/scripts/` already addresses it before working around it in app code.
- `lib/scripts/build.ps1` stamps the version (from git history + pubspec) into `pubspec.yaml` and `pili_release.json`, which feeds `lib/build_config.dart` via `--dart-define-from-file`. It writes to `GITHUB_ENV` and expects git history; don't run it casually.

## Architecture

### State management & routing — GetX (forked)

Every page follows `lib/pages/<feature>/controller.dart` + `view.dart`: a `GetxController` subclass and a widget. Routes are declared in `lib/router/app_pages.dart` consumed by `GetMaterialApp` in `lib/main.dart`. Dependencies are resolved with `Get.put`/`Get.lazyPut`/`Get.find` (e.g. `AccountService`, `DownloadService` in `main.dart`, plus `lib/services/service_locator.dart` for audio/session).

### Network layer — `lib/http/`

- `Request` (singleton in `lib/http/init.dart`) wraps a `Dio` instance: optional HTTP/2 adapter, gzip/brotli decoding, retry interceptor, and the `AccountManager` cookie interceptor. **All** API calls go through `Request().get/post`, which never throw — errors return a `Response` with a `message` map.
- Each domain has one module (`video.dart`, `live.dart`, `reply.dart`, `dynamics.dart`, ...) containing an `abstract final class XxxHttp` with static methods. Endpoints are defined in `lib/http/api.dart`.
- The universal result type is the sealed class `LoadingState<T>` (`lib/http/loading_state.dart`): `Loading` / `Success<T>` / `Error`. Controllers hold `LoadingState` in Rx fields; views `switch` on it. Follow this pattern for any new API.
- Signing: `lib/utils/wbi_sign.dart` (WBI signature), `lib/utils/app_sign.dart` (app API signing) are required by many endpoints.

### gRPC layer — `lib/grpc/` (WIP refactor)

`lib/grpc/grpc_req.dart` implements gRPC-over-HTTP using Dio (`application/grpc` content type, gzip-compressed protobuf, decoded in isolates). Generated protobuf message classes live under `lib/grpc/bilibili/` (excluded from the analyzer — never hand-edit).

### Models

Two generations coexist: `lib/models/` (older) and `lib/models_new/` (current — one directory per API response, e.g. `models_new/video/video_detail/data.dart`). Prefer `models_new` style for new endpoints.

### Storage — Hive CE

`GStorage` (`lib/utils/storage.dart`) opens all boxes (`setting`, `localCache`, `userInfo`, `watchProgress`, per-account boxes via `Accounts`). Typed access goes through `Pref` (`lib/utils/storage_pref.dart`); every key is declared in `lib/utils/storage_key.dart`. New settings must be added in all three places as needed.

### Multi-account

`lib/utils/accounts/` implements multi-account: `AccountManager` manages `Account`s (each with its own cookie jar and tokens) and acts as the Dio interceptor that attaches credentials per request. gRPC requests carry account headers via `grpc_headers.dart`.

### Player

Video playback uses media-kit (mpv) wrapped by `lib/plugin/pl_player/` (`PlPlayerController` is shared/singleton-like across the app). The video detail page `lib/pages/video/` is the largest feature (replies/comments live in `pages/video/reply*/`). Live danmaku uses a raw TCP socket (`lib/tcp/live.dart`); video danmaku renders via the `canvas_danmaku` fork.

## Conventions

- **Imports**: `always_use_package_imports` is enforced — always `package:PiliPlus/...`, never relative lib imports.
- **Dart 3 syntax**: the codebase leans heavily on modern features — sealed classes, exhaustive `switch` expressions, records, null-aware elements, and dot-shorthands (e.g. `const [.a, .b]`, `.light`). Match this style; do not rewrite to older idioms.
- `analysis_options.yaml`: `avoid_print` (use `debugPrint` guarded by `kDebugMode`, or the `logger` service), `prefer_const_constructors`, `cascade_invocations`, `trailing_commas: preserve` (don't add/remove trailing commas when editing).
- UI uses Material 3 via the `material_ui` package and a fork of smart_dialog for toasts/dialogs.
