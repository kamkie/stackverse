package session

import (
	"encoding/hex"
	"testing"
)

func TestNewOpaqueIDReturnsRandomHexToken(t *testing.T) {
	first, err := NewOpaqueID()
	if err != nil {
		t.Fatal(err)
	}
	second, err := NewOpaqueID()
	if err != nil {
		t.Fatal(err)
	}

	if len(first) != 64 {
		t.Fatalf("id length = %d", len(first))
	}
	if _, err := hex.DecodeString(first); err != nil {
		t.Fatalf("id should be hex: %v", err)
	}
	if first == second {
		t.Fatalf("opaque ids should be unique")
	}
}

func TestRedisOptionsAcceptsURLAndBareAddressForms(t *testing.T) {
	t.Run("url", func(t *testing.T) {
		options, err := redisOptions("redis://:secret@cache:6380/2")
		if err != nil {
			t.Fatal(err)
		}
		if options.Addr != "cache:6380" {
			t.Fatalf("Addr = %q", options.Addr)
		}
		if options.Password != "secret" {
			t.Fatalf("Password = %q", options.Password)
		}
		if options.DB != 2 {
			t.Fatalf("DB = %d", options.DB)
		}
	})

	t.Run("bare address", func(t *testing.T) {
		options, err := redisOptions("cache:6381")
		if err != nil {
			t.Fatal(err)
		}
		if options.Addr != "cache:6381" {
			t.Fatalf("Addr = %q", options.Addr)
		}
		if options.DB != 0 {
			t.Fatalf("DB = %d", options.DB)
		}
	})
}
