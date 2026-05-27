package com.termux.shared.termux.models;

public enum UserAction {

    CRASH_REPORT("crash report"),
    PLUGIN_EXECUTION_COMMAND("plugin execution command"),
    REPORT_ISSUE_FROM_TRANSCRIPT("report issue from transcript");

    private final String name;

    UserAction(final String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

}
