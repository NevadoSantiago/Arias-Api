package com.arias.auth;

/** What the login email step tells the client about an email; serialized by name. */
public enum AccountStatus {
    /** No (non-deleted) account for the email. */
    NOT_FOUND,
    /** The account signs in with a password (also the answer for inactive accounts). */
    PASSWORD,
    /** B2C account that only signs in with Google. */
    GOOGLE,
    /** Whitelisted company employee that has not set a password yet. */
    FIRST_LOGIN
}
