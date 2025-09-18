package maestro

/** [yamlValue] is the word written in YAML (`browserAlert: accept`); the parser upper-cases it into the constant. */
enum class BrowserAlertAction(val yamlValue: String) {
    ACCEPT("accept"),
    DISMISS("dismiss"),
}
