package web

import (
	"context"
	"net/http"

	"github.com/labstack/echo/v4"
)

type routeParamsKey struct{}

// Wrap adapts the backend's plain net/http handlers to Echo while keeping
// route parameters available without framework types leaking into features.
func Wrap(handler http.HandlerFunc) echo.HandlerFunc {
	return func(c echo.Context) error {
		request := c.Request()
		names := c.ParamNames()
		if len(names) > 0 {
			params := make(map[string]string, len(names))
			for _, name := range names {
				params[name] = c.Param(name)
			}
			request = request.WithContext(context.WithValue(request.Context(), routeParamsKey{}, params))
			c.SetRequest(request)
		}
		handler(c.Response().Writer, request)
		return nil
	}
}

func URLParam(r *http.Request, name string) string {
	params, _ := r.Context().Value(routeParamsKey{}).(map[string]string)
	return params[name]
}
