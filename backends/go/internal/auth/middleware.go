package auth

import (
	"context"
	"log/slog"
	"net/http"
	"slices"
	"strings"

	"github.com/golang-jwt/jwt/v5"

	"github.com/kamkie/stackverse/backends/go/internal/logx"
	"github.com/kamkie/stackverse/backends/go/internal/web"
)

// Identity is the caller as derived from the validated JWT — never from
// request data (SPEC rule 6).
type Identity struct {
	Username string
	Name     string
	Email    string
	Roles    []string
}

func (i *Identity) HasRole(role string) bool {
	return slices.Contains(i.Roles, role)
}

type contextKey struct{}

// FromContext returns the authenticated caller, or nil for anonymous requests.
func FromContext(ctx context.Context) *Identity {
	identity, _ := ctx.Value(contextKey{}).(*Identity)
	return identity
}

// Middleware authenticates bearer tokens and enforces per-route requirements.
type Middleware struct {
	keys      *Keys
	issuer    string
	audience  string
	localizer web.Localizer
	logger    *slog.Logger
}

func NewMiddleware(keys *Keys, issuer, audience string, localizer web.Localizer, logger *slog.Logger) *Middleware {
	return &Middleware{keys: keys, issuer: issuer, audience: audience, localizer: localizer, logger: logger}
}

// Authenticate validates a bearer token when one is presented and stores the
// identity in the context. Requests without a token pass through anonymously —
// whether anonymity is acceptable is each route's decision (RequireAuth /
// RequireRole / the public surface).
func (m *Middleware) Authenticate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		if header == "" {
			next.ServeHTTP(w, r)
			return
		}
		raw, ok := strings.CutPrefix(header, "Bearer ")
		identity, err := m.validate(r.Context(), raw)
		if !ok || err != nil {
			// a presented-and-rejected token is an expected 401 and a security
			// signal, never above INFO (docs/LOGGING.md §3)
			logx.Event(r.Context(), m.logger, slog.LevelInfo, "jwt_validation_failed", "failure",
				"Rejected a bearer token",
				slog.String("error_code", "invalid_token"),
			)
			web.WriteProblem(w, r, m.localizer, m.logger, web.Unauthorized("Missing or invalid bearer token."))
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), contextKey{}, identity)))
	})
}

// RequireAuth rejects anonymous callers with a 401 problem document.
func (m *Middleware) RequireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if FromContext(r.Context()) == nil {
			web.WriteProblem(w, r, m.localizer, m.logger, web.Unauthorized("Authentication is required."))
			return
		}
		next.ServeHTTP(w, r)
	})
}

// RequireRole asks for the single role an endpoint needs (`moderator` or
// `admin`); the hierarchy is Keycloak's composite role, never re-implemented
// here (backends/README.md).
func (m *Middleware) RequireRole(role string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			identity := FromContext(r.Context())
			if identity == nil {
				web.WriteProblem(w, r, m.localizer, m.logger, web.Unauthorized("Authentication is required."))
				return
			}
			if !identity.HasRole(role) {
				logx.Event(r.Context(), m.logger, slog.LevelInfo, "authz_denied", "denied",
					"Denied a request lacking the required role",
					slog.String("actor", identity.Username),
				)
				web.WriteProblem(w, r, m.localizer, m.logger,
					web.Forbidden("You do not have the role required for this operation."))
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func (m *Middleware) validate(ctx context.Context, raw string) (*Identity, error) {
	claims := jwt.MapClaims{}
	_, err := jwt.ParseWithClaims(raw, claims,
		func(token *jwt.Token) (any, error) {
			kid, _ := token.Header["kid"].(string)
			return m.keys.Key(ctx, kid)
		},
		jwt.WithValidMethods([]string{"RS256", "RS384", "RS512"}),
		jwt.WithIssuer(m.issuer),
		jwt.WithAudience(m.audience),
		jwt.WithExpirationRequired(),
	)
	if err != nil {
		return nil, err
	}
	return IdentityFromClaims(claims), nil
}

// IdentityFromClaims maps the token claims onto the caller: identity =
// `preferred_username`, roles = `realm_access.roles` (SPEC rule 6).
func IdentityFromClaims(claims map[string]any) *Identity {
	identity := &Identity{
		Username: stringClaim(claims, "preferred_username"),
		Name:     stringClaim(claims, "name"),
		Email:    stringClaim(claims, "email"),
	}
	if realmAccess, ok := claims["realm_access"].(map[string]any); ok {
		if roles, ok := realmAccess["roles"].([]any); ok {
			for _, role := range roles {
				if name, ok := role.(string); ok {
					identity.Roles = append(identity.Roles, name)
				}
			}
		}
	}
	return identity
}

func stringClaim(claims map[string]any, name string) string {
	value, _ := claims[name].(string)
	return value
}
