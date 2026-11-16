package com.finbridge.integration.odoo;

/**
 * Any failure talking to Odoo, already translated into a stable error code.
 * The message is safe to store and show: it never contains Odoo's "debug" traceback.
 */
public class OdooException extends RuntimeException {

    public enum Kind {
        /** The API key is not set; no call was made. */
        NOT_CONFIGURED("ODOO_NOT_CONFIGURED", false),
        /** Connect or read timeout. */
        TIMEOUT("ODOO_TIMEOUT", true),
        /** Connection refused, DNS failure, connection dropped. */
        UNREACHABLE("ODOO_UNREACHABLE", true),
        /** 401 or 403: wrong, expired or under-privileged API key. */
        AUTH("ODOO_AUTH", false),
        /** Any other 4xx: Odoo understood the request and rejected it. */
        REJECTED("ODOO_REJECTED", false),
        /** 5xx. */
        SERVER_ERROR("ODOO_SERVER_ERROR", true),
        /** 200 with a body FinBridge cannot read. */
        BAD_RESPONSE("ODOO_BAD_RESPONSE", false);

        private final String code;
        private final boolean retryable;

        Kind(String code, boolean retryable) {
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Kind kind;
    private final Integer httpStatus;

    public OdooException(Kind kind, Integer httpStatus, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public OdooException(Kind kind, Integer httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind getKind() {
        return kind;
    }

    public String getCode() {
        return kind.code();
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}
