package com.cpg.gateway.session;

import com.cpg.common.ApiException;
import com.cpg.gateway.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API behind the hosted payment page. Public, but every call needs the session's checkout token (from
 * the payment URL's fragment, sent as {@code X-Checkout-Token}); an unknown session and a wrong token look the same.
 * The page can only display: payment is proven on chain, never by the browser.
 */
@RestController
public class CheckoutController {

    private static final int REQUESTS_PER_SECOND_PER_IP = 5;

    private final PaymentSessionService sessions;
    private final SessionViews views;
    private final CheckoutTokens tokens;
    private final RateLimiter rateLimiter;

    public CheckoutController(PaymentSessionService sessions, SessionViews views, CheckoutTokens tokens,
                              RateLimiter rateLimiter) {
        this.sessions = sessions;
        this.views = views;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/checkout/v1/sessions/{id}")
    public Map<String, Object> get(@PathVariable String id,
                                   @RequestHeader(name = "X-Checkout-Token", required = false) String token,
                                   HttpServletRequest request) {
        if (rateLimiter.tryAcquire("checkout:" + request.getRemoteAddr(), REQUESTS_PER_SECOND_PER_IP) > 0) {
            throw new ApiException(429, "rate_limited", "Too many requests");
        }
        if (!tokens.valid(id, token)) {
            throw ApiException.notFound("Payment session");
        }
        return views.checkoutView(sessions.find(id).orElseThrow(() -> ApiException.notFound("Payment session")));
    }
}
