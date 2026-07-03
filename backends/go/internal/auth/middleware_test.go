package auth

import (
	"slices"
	"testing"
)

func TestIdentityFromClaims(t *testing.T) {
	identity := IdentityFromClaims(map[string]any{
		"preferred_username": "admin",
		"name":               "Ada Admin",
		"email":              "admin@example.com",
		"realm_access": map[string]any{
			"roles": []any{"admin", "moderator", "offline_access", 42},
		},
	})
	if identity.Username != "admin" || identity.Name != "Ada Admin" || identity.Email != "admin@example.com" {
		t.Fatalf("unexpected identity: %+v", identity)
	}
	if !slices.Equal(identity.Roles, []string{"admin", "moderator", "offline_access"}) {
		t.Fatalf("roles must come from realm_access.roles, skipping non-strings; got %v", identity.Roles)
	}
	if !identity.HasRole("moderator") || identity.HasRole("ghost") {
		t.Fatal("HasRole must reflect the claim contents")
	}
}

func TestIdentityFromClaimsWithoutRoles(t *testing.T) {
	identity := IdentityFromClaims(map[string]any{"preferred_username": "demo"})
	if identity.Username != "demo" || len(identity.Roles) != 0 {
		t.Fatalf("a role-less token yields an empty role list, got %+v", identity)
	}
}
