package org.ledgerapi.domain;

/** A registered user. {@code passwordHash} is an encoded Argon2id string and is never returned. */
public record User(
    String userId, String loginName, String displayName, String passwordHash, String createdAt) {}
