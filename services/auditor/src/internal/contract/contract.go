// Package contract checks the service's HTTP responses in tests against its
// OpenAPI contracts in docs/api (ADR-0063). Only tests import it.
package contract

import (
	"bytes"
	"context"
	"io"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers"
	"github.com/gin-gonic/gin"
)

var (
	loaded sync.Map // spec file name -> *openapi3.T
	// A gin parameter, :name or *name, as an OpenAPI one.
	ginParam = regexp.MustCompile(`[:*](\w+)`)
)

// Check fails the test unless the spec lists the method and route (an OpenAPI
// path such as /api/corrupted-logs/{id}) with the response's status, and the
// body matches the schema given for its content type. Controller tests call
// handlers directly, so they name the route rather than send its URL.
func Check(t testing.TB, spec, method, route string, res *httptest.ResponseRecorder) {
	t.Helper()
	doc := load(t, spec)
	pathItem := doc.Paths.Value(route)
	if pathItem == nil {
		t.Errorf("%s %s is not in %s", method, route, spec)
		return
	}
	operation := pathItem.GetOperation(method)
	if operation == nil {
		t.Errorf("%s %s is not in %s", method, route, spec)
		return
	}
	input := &openapi3filter.ResponseValidationInput{
		RequestValidationInput: &openapi3filter.RequestValidationInput{
			Request: httptest.NewRequest(method, route, nil),
			Route:   &routers.Route{Spec: doc, Path: route, PathItem: pathItem, Method: method, Operation: operation},
		},
		Status:  res.Code,
		Header:  res.Header(),
		Body:    io.NopCloser(bytes.NewReader(res.Body.Bytes())),
		Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
	}
	if err := openapi3filter.ValidateResponse(context.Background(), input); err != nil {
		t.Errorf("%s %s -> %d breaks %s:\n%v\nbody: %s", method, route, res.Code, spec, err, res.Body.String())
	}
}

// CheckRoutes fails the test for each route the router serves that none of
// the specs lists. /health is not part of any contract.
func CheckRoutes(t testing.TB, routes gin.RoutesInfo, specs ...string) {
	t.Helper()
	for _, route := range routes {
		if route.Path == "/health" {
			continue
		}
		path := ginParam.ReplaceAllString(route.Path, "{$1}")
		found := false
		for _, spec := range specs {
			// Find ignores the names of path parameters.
			if item := load(t, spec).Paths.Find(path); item != nil && item.GetOperation(route.Method) != nil {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("%s %s is in none of %v", route.Method, path, specs)
		}
	}
}

func load(t testing.TB, spec string) *openapi3.T {
	t.Helper()
	if doc, ok := loaded.Load(spec); ok {
		return doc.(*openapi3.T)
	}
	loader := openapi3.NewLoader()
	doc, err := loader.LoadFromFile(filepath.Join(apiDir(t), spec))
	if err != nil {
		t.Fatalf("load %s: %v", spec, err)
	}
	if err := doc.Validate(loader.Context); err != nil {
		t.Fatalf("%s is not a valid OpenAPI document: %v", spec, err)
	}
	loaded.Store(spec, doc)
	return doc
}

// apiDir is OPENAPI_DIR, which the test containers set because they mount
// only this service, or else docs/api above the working directory.
func apiDir(t testing.TB) string {
	t.Helper()
	if dir := os.Getenv("OPENAPI_DIR"); dir != "" {
		return dir
	}
	dir, err := os.Getwd()
	if err != nil {
		t.Fatalf("working directory: %v", err)
	}
	for {
		candidate := filepath.Join(dir, "docs", "api")
		if info, err := os.Stat(candidate); err == nil && info.IsDir() {
			return candidate
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatalf("docs/api not found; set OPENAPI_DIR")
		}
		dir = parent
	}
}
