package com.cpg.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }
}
