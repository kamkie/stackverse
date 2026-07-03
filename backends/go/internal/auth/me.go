package auth

import (
	"net/http"
	"sort"

	"github.com/kamkie/stackverse/backends/go/internal/web"
)

// appRoles are the two application roles; everything else in
// `realm_access.roles` is Keycloak plumbing.
var appRoles = map[string]bool{"moderator": true, "admin": true}

type meResponse struct {
	Username string   `json:"username"`
	Name     string   `json:"name,omitempty"`
	Email    string   `json:"email,omitempty"`
	Roles    []string `json:"roles"`
}

// Me echoes the caller's identity as derived from the validated JWT
// (SPEC rule 6).
func Me(w http.ResponseWriter, r *http.Request) {
	identity := FromContext(r.Context())
	roles := []string{}
	for _, role := range identity.Roles {
		if appRoles[role] {
			roles = append(roles, role)
		}
	}
	sort.Strings(roles)
	web.WriteJSON(w, http.StatusOK, meResponse{
		Username: identity.Username,
		Name:     identity.Name,
		Email:    identity.Email,
		Roles:    roles,
	})
}
