package com.yumg.starter.modules.integrations.closet.application;

/**
 * Closet username rules, kept identical to the closet backend (smart-closet-server #164).
 *
 * <p>New usernames must not consist only of dots or contain {@code ..}: such a name becomes a URL
 * dot segment ({@code ..} is not escaped by percent-encoding) and the account could no longer be
 * addressed by management routes. Existing accounts are still addressed with the wider rule so a
 * legacy name such as {@code a..b} stays manageable; only pure-dot names, which cannot be sent as a
 * path segment at all, are rejected up front.
 */
public final class ClosetUsernames {
    /** Usernames accepted when creating an account. {@code a.b} is fine, {@code ..} and {@code a..b} are not. */
    public static final String NEW = "^(?!.*\\.\\.)[A-Za-z0-9_.-]{2,80}$";
    /** Usernames accepted when addressing an existing account in a path segment. */
    public static final String EXISTING = "^(?!\\.+$)[A-Za-z0-9_.-]{2,80}$";

    private ClosetUsernames() {
    }
}
