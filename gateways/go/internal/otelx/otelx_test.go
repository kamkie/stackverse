package otelx

import (
	"context"
	"testing"
)

func TestEnabledFollowsOTELSDKDisabledFlag(t *testing.T) {
	tests := []struct {
		name  string
		value string
		want  bool
	}{
		{name: "unset", value: "", want: false},
		{name: "disabled", value: "true", want: false},
		{name: "enabled lowercase", value: "false", want: true},
		{name: "enabled uppercase", value: "FALSE", want: true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv("OTEL_SDK_DISABLED", tt.value)
			if got := Enabled(); got != tt.want {
				t.Fatalf("Enabled = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestSetupDisabledReturnsNoopShutdown(t *testing.T) {
	t.Setenv("OTEL_SDK_DISABLED", "true")

	handler, shutdown, err := Setup(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if handler != nil {
		t.Fatalf("handler = %#v", handler)
	}
	if shutdown == nil {
		t.Fatalf("shutdown should not be nil")
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown error = %v", err)
	}
}
