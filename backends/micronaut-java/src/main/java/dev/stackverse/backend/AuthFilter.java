package dev.stackverse.backend;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Singleton
@Filter("/**")
final class AuthFilter implements HttpServerFilter {
    static final String IDENTITY = "stackverse.identity";
    private static final Logger LOG = LoggerFactory.getLogger(AuthFilter.class);

    private final JwtVerifier verifier;
    private final AccountService accounts;
    private final ProblemHandler problems;

    AuthFilter(JwtVerifier verifier, AccountService accounts, ProblemHandler problems) {
        this.verifier = verifier;
        this.accounts = accounts;
        this.problems = problems;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String header = request.getHeaders().get("Authorization");
        if (header == null || header.isBlank()) {
            return chain.proceed(request);
        }
        if (!header.startsWith("Bearer ")) {
            return problem(request, Problems.unauthorized("Missing or invalid bearer token."));
        }
        Identity identity;
        try {
            identity = verifier.verify(header.substring("Bearer ".length()));
        } catch (IllegalArgumentException ex) {
            EventLog.info(LOG, "jwt_validation_failed", "failure", "Rejected a bearer token",
                    Map.of("error_code", "invalid_token"));
            return problem(request, Problems.unauthorized("Missing or invalid bearer token."));
        }
        request.setAttribute(IDENTITY, identity);
        Account account = accounts.recordSeen(identity.username());
        if (Models.USER_BLOCKED.equals(account.status())) {
            EventLog.warn(LOG, "blocked_user_rejected", "denied", "Refused a request from a blocked account",
                    Map.of("actor", identity.username()));
            return problem(request, Problems.forbiddenKey("error.account.blocked"));
        }
        return chain.proceed(request);
    }

    private Publisher<MutableHttpResponse<?>> problem(HttpRequest<?> request, ProblemException problem) {
        return Publishers.just(problems.response(request, problem));
    }
}
