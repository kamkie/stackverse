package auth

import (
	"slices"
	"testing"
)

func TestIdentityFromClaims(t *testing.T) {
	identity, err := IdentityFromClaims(map[string]any{
		"preferred_username": "admin",
		"name":               "Ada Admin",
		"email":              "admin@example.com",
		"realm_access": map[string]any{
			"roles": []any{"admin", "moderator", "offline_access", 42},
		},
	})
	if err != nil {
		t.Fatalf("valid preferred_username must build an identity: %v", err)
	}
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
	identity, err := IdentityFromClaims(map[string]any{"preferred_username": "demo"})
	if err != nil {
		t.Fatalf("valid preferred_username must build a role-less identity: %v", err)
	}
	if identity.Username != "demo" || len(identity.Roles) != 0 {
		t.Fatalf("a role-less token yields an empty role list, got %+v", identity)
	}
}

func TestIdentityFromClaimsRequiresPreferredUsername(t *testing.T) {
	tests := []struct {
		name   string
		claims map[string]any
	}{
		{
			name:   "missing",
			claims: map[string]any{},
		},
		{
			name:   "non-string",
			claims: map[string]any{"preferred_username": 42},
		},
		{
			name:   "empty",
			claims: map[string]any{"preferred_username": ""},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			identity, err := IdentityFromClaims(tt.claims)
			if err == nil {
				t.Fatalf("expected invalid preferred_username to be rejected, got identity %+v", identity)
			}
			if identity != nil {
				t.Fatalf("invalid preferred_username must not build an identity, got %+v", identity)
			}
		})
	}
}
