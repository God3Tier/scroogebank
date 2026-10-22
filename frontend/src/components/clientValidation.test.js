import { describe, expect, it } from 'vitest'

import { ageOn, isSingapore, validateClient } from './clientValidation.js'

// 22 October 2026 in Singapore (still the 21st in UTC).
const TODAY = new Date('2026-10-21T18:00:00Z')

const VALID = {
  first_name: 'Synthetic',
  last_name: 'Client',
  date_of_birth: '1990-05-10',
  email: 'synthetic.client@example.test',
  phone_number: '+6560000101',
  address: '1 Example Street',
  city: 'Singapore',
  state: 'Singapore',
  country: 'Singapore',
  postal_code: '018956',
}

const problem = (changes) => validateClient({ ...VALID, ...changes }, TODAY)

describe('validateClient', () => {
  it('accepts a complete Singapore profile', () => {
    expect(problem({})).toBe('')
    expect(problem({ first_name: 'Mei Ling', last_name: 'Núñez', phone_number: '+65 6000-0101' })).toBe('')
  })

  it.each([
    [{ first_name: 'A' }, 'First name must be at least 2 characters.'],
    [{ last_name: 'Smith2' }, 'Last name can contain only letters and spaces.'],
    [{ date_of_birth: '' }, 'Enter a date of birth.'],
    [{ date_of_birth: '2008-10-23' }, 'between 18 and 100'],
    [{ date_of_birth: '1925-10-22' }, 'between 18 and 100'],
    [{ email: 'synthetic@example' }, 'valid email address'],
    [{ phone_number: '91234567' }, '10 to 15 digits'],
    [{ address: '1 Rd' }, 'Address must be at least 5 characters.'],
    [{ city: 'S' }, 'City must be at least 2 characters.'],
    [{ postal_code: '12345' }, 'A Singapore postal code is 6 digits.'],
    [{ country: 'United Kingdom', postal_code: 'SW1A 1AA!' }, '4 to 10 letters'],
  ])('rejects %o', (changes, message) => {
    expect(problem(changes)).toContain(message)
  })

  it('accepts an 18th birthday on the day and another country’s postal code', () => {
    expect(problem({ date_of_birth: '2008-10-22' })).toBe('')
    expect(problem({ country: 'United Kingdom', postal_code: 'SW1A 1AA' })).toBe('')
  })
})

describe('helpers', () => {
  it('reads Singapore however it is written', () => {
    for (const country of ['Singapore', ' singapore ', 'S.G.', 'SGP', 'Republic of  Singapore']) {
      expect(isSingapore(country)).toBe(true)
    }
    expect(isSingapore('Malaysia')).toBe(false)
  })

  it('counts age on the Singapore date', () => {
    expect(ageOn('2008-10-22', TODAY)).toBe(18)
    expect(ageOn('2008-10-23', TODAY)).toBe(17)
  })
})
