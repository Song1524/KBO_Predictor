package com.playball.kbopredictor.auth.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class SecurityErrorResponse {
    private SecurityErrorResponse() {}

    public static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("X-Auth-Error", code);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
