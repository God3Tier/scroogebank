package service

// Consider putting this on a seperate file
import "errors"

var (
	ErrBadInput = errors.New("bad input")
	ErrDuplicateClient = errors.New("duplicate client")
)

type ClientService interface {
	CreateClientProfile() error
	VerifyClientIdentity() error
	UpdateClientInformation() error
	GetClientProfile() error
	DeleteClientProfile() error
}

func CreateClientProfile() error {



	return nil;
}
