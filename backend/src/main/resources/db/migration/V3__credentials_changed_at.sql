-- Phase 7 (hardening): when a user's password is reset, access tokens issued before that
-- moment must stop working. The backend compares a token's "issued at" with this column.
-- NULL means the password has never been changed since the account was created.

ALTER TABLE users
    ADD COLUMN credentials_changed_at DATETIME(3) NULL AFTER locked_until;
