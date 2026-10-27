package com.arias.users;

/** State of the in-app Google review card for a user. NEVER wins over OPENED. */
public enum ReviewPromptStatus {
    PENDING,
    OPENED,
    NEVER
}
