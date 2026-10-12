package service

import (
	"errors"
	"fmt"
)

var (
	InvalidServiceError       = errors.New("Unknown service request")
	UnimplementedServiceError = errors.New("TODO: implement service")
)

func InvalidChainError(message string) error {
	return fmt.Errorf("Invalid chain: %s\n", message)
}
