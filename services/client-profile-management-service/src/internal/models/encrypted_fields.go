package models

// The names that a client's encrypted fields are bound to. Each is its
// column's name, so a ciphertext only decrypts in the column it was written
// to (ADR-0030). Every package that encrypts or decrypts a client field must
// use these.
const (
	FieldFirstName   = "first_name"
	FieldLastName    = "last_name"
	FieldDateOfBirth = "date_of_birth"
	FieldGender      = "gender"
	FieldEmail       = "email"
	FieldPhoneNumber = "phone_number"
	FieldAddress     = "address"
	FieldCity        = "city"
	FieldState       = "state"
	FieldCountry     = "country"
	FieldPostalCode  = "postal_code"
)
