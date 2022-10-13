package com.hedera.hashgraph.web;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP Status return codes {@see https://www.w3.org/Protocols/rfc2616/rfc2616-sec6.html#sec6.1.1}
 */
public enum StatusCode {
    // 1xx: Informational - Request received, continuing process
    CONTINUE_100(100, "Continue"),
    SWITCHING_PROTOCOLS_101(101, "Switching Protocols"),
    // 2xx: Success - The action was successfully received, understood, and accepted
    OK_200(200, "Ok"),
    CREATED_201(201, "Created"),
    ACCEPTED_202(202, "Accepted"),
    NON_AUTHORITATIVE_INFORMATION_203(203, "Non Authoritative Information"),
    NO_CONTENT_204(204, "No Content"),
    RESET_CONTENT_205(205, "Reset Content"),
    PARTIAL_CONTENT_206(206, "Partial Content"),
    // 3xx: Redirection - Further action must be taken in order to complete the request
    MULTIPLE_CHOICES_300(300, "Multiple Choices"),
    MOVED_PERMANENTLY_301(301, "Moved Permanently"),
    FOUND_302(302, "Found"),
    SEE_OTHER_303(303, "See Other"),
    NOT_MODIFIED_304(304, "Not Modified"),
    USE_PROXY_305(305, "Use Proxy"),
    TEMPORARY_REDIRECT_307(307, "Temporary Redirect"),
    // 4xx: Client Error - The request contains bad syntax or cannot be fulfilled
    BAD_REQUEST_400(400, "Bad Request"),
    UNAUTHORIZED401(401, "Unauthorized"),
    PAYMENT_REQUIRED_402(402, "Payment Required"),
    FORBIDDEN_403(403, "Forbidden"),
    NOT_FOUND_404(404, "Not Found"),
    METHOD_NOT_ALLOWED_405(405, "Method Not Allowed"),
    NOT_ACCEPTABLE_406(406, "Not Acceptable"),
    PROXY_AUTHENTICATION_REQUIRED_407(407, "Proxy Authentication Required"),
    REQUEST_TIME_OUT_408(408, "Request Time Out"),
    CONFLICT_409(409, "Conflict"),
    GONE_410(410, "Gone"),
    LENGTH_REQUIRED_411(411, "Length Required"),
    PRECONDITION_FAILED_412(412, "Precondition Failed"),
    REQUEST_ENTITY_TOO_LARGE_413(413, "Request Entity Too Large"),
    REQUEST_URI_TOO_LARGE_414(414, "Request URI Too Large"),
    UNSUPPORTED_MEDIA_TYPE_415(415, "Unsupported Media Type"),
    REQUESTED_RANGE_NOT_SATISFIABLE_416(416, "Requested Range Not Satisfiable"),
    EXPECTATION_FAILED_417(417, "Expectation Failed"),
    UPGRADE_REQUIRED_426(426, "Upgrade Required"),
    // 5xx: Server Error - The server failed to fulfill an apparently valid request
    INTERNAL_SERVER_ERROR_500(500, "Internal Server Error"),
    NOT_IMPLEMENTED_501(501, "Not Implemented"),
    BAD_GATEWAY_502(502, "Bad Gateway"),
    SERVICE_UNAVAILABLE_503(503, "Service Unavailable"),
    GATEWAY_TIME_OUT_504(504, "Gateway Time Out"),
    HTTP_VERSION_NOT_SUPPORTED_505(505, "Http Version Not Supported");

    private final int code;
    private final String message;

    StatusCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    private static final Map<Integer, StatusCode> codeLookupMap;

    static {
        final Map<Integer, StatusCode> map = new HashMap<>(1000);
        final var values = values();
        for (final StatusCode value : values) {
            map.put(value.code, value);
        }
        codeLookupMap = Collections.unmodifiableMap(map);
    }

    /**
     * Get the StatusCode enum for code number
     *
     * @param code the code number
     * @return StatusCode enum if one exists or null if unknown code number
     */
    public static StatusCode forCode(int code) {
        return codeLookupMap.get(code);
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
