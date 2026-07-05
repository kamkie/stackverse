package otelx

import (
	"context"
	"testing"
)

func TestEnabledOnlyWhenSDKDisabledIsExplicitlyFalse(t *testing.T) {
	cases := []struct {
		value string
		want  bool
	}{
		{"", false},
		{"true", false},
		{"FALSE", true},
		{"false", true},
	}

	for _, tt := range cases {
		t.Run(tt.value, func(t *testing.T) {
			t.Setenv("OTEL_SDK_DISABLED", tt.value)
			if got := Enabled(); got != tt.want {
				t.Fatalf("Enabled() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestSetupDisabledReturnsNoopShutdown(t *testing.T) {
	t.Setenv("OTEL_SDK_DISABLED", "true")

	handler, shutdown, err := Setup(context.Background())
	if err != nil {
		t.Fatalf("disabled setup must not fail: %v", err)
	}
	if handler != nil {
		t.Fatalf("disabled setup must not create a log handler: %T", handler)
	}
	if shutdown == nil {
		t.Fatal("disabled setup must still return a shutdown function")
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("disabled shutdown must be a no-op, got %v", err)
	}
}
