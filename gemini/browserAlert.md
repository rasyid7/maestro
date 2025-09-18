# Handling Browser Alerts

This document summarizes the implementation of the `browserAlert` command in Maestro.

## Command

The `browserAlert` command is used to automatically handle JavaScript alerts (`alert`, `confirm`, `prompt`) that may appear during a web flow.

### Usage

```yaml
- browserAlert: accept
```

or

```yaml
- browserAlert: dismiss
```

This command sets a persistent mode for the web driver. Once set, any subsequent command that triggers a JavaScript alert will be handled automatically by either accepting or dismissing it, based on the action specified.

## Implementation Details

The implementation spans across three modules: `maestro-orchestra-models`, `maestro-client`, and `maestro-orchestra`.

### `maestro-orchestra-models`

-   **`Commands.kt`**:
    -   A new data class `BrowserAlertCommand` was created to represent the `browserAlert` command in the Maestro flow. It references `maestro.BrowserAlertAction`.
-   **`MaestroCommand.kt`**:
    -   The `MaestroCommand` class was updated to include the new `BrowserAlertCommand`.

### `maestro-client`

-   **`BrowserAlertAction.kt`**:
    -   A new file was created to hold the `BrowserAlertAction` enum with values `ACCEPT` and `DISMISS`. This was moved from `maestro-orchestra-models` to resolve a circular dependency.
-   **`Driver.kt`**:
    -   The `Driver` interface was updated with a new method `setBrowserAlertAction(action: maestro.BrowserAlertAction?)` with a default no-op implementation to ensure compatibility with other drivers.
-   **`CdpWebDriver.kt`**:
    -   This class now stores the selected `BrowserAlertAction`.
    -   The `setBrowserAlertAction` method is implemented to set the desired alert handling behavior.
    -   A new private method `handleAlert()` was added, which uses Selenium's `switchTo().alert()` to accept or dismiss an alert if it's present.
    -   The `tap()` method was modified to call `handleAlert()` after a click is performed.
-   **`Maestro.kt`**:
    -   A new method `setBrowserAlertAction` was added to act as a bridge between the `Orchestra` and the `Driver`.

### `maestro-orchestra`

-   **`Orchestra.kt`**:
    -   The main command execution logic in `executeCommand` was updated to handle the new `BrowserAlertCommand`.
    -   When a `BrowserAlertCommand` is encountered, it calls the `setBrowserAlertAction` method on the `Maestro` instance, which in turn configures the `CdpWebDriver`.
-   **`yaml/YamlFluentCommand.kt`**:
    -   The `YamlFluentCommand` data class was updated to include a `browserAlert` property.
    -   The `_toCommands` method was updated to parse the `browserAlert` command and create a `BrowserAlertCommand`.
-   **`yaml/YamlCommandReaderTest.kt`**:
    -   A new test file `browser_alert.yaml` was created.
    -   A new test `browserAlert` was added to verify the parsing of the `browserAlert` command.
-   **`MaestroCommandSerializationTest.kt`**:
    -   A new test `serialize BrowserAlertCommand` was added to ensure the command is serialized and deserialized correctly.