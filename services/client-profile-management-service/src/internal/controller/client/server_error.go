package client

import (
	"log"
	"net/http"
)

// writeServerError answers a 500 without the error's text, which can name the
// database's tables and SQL, and logs the error for the operators instead.
func writeServerError(w http.ResponseWriter, r *http.Request, err error) {
	log.Printf("%s %s: %v", r.Method, r.URL.Path, err)
	http.Error(w, "internal server error", http.StatusInternalServerError)
}
