// Checks a client profile form before it is sent, with the same rules
// client-service applies (the project brief's Appendix 2 validations), so the
// agent sees the problem at once. client-service stays the authority.
// Returns the first problem as a sentence, or '' when the form is fine.

const NAME = /^[\p{L} ]+$/u
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const PHONE = /^\+?[0-9]{10,15}$/
const SINGAPORE_POSTAL = /^[0-9]{6}$/
const POSTAL = /^[A-Za-z0-9 -]{4,10}$/

// Singapore as engagement-service and client-service read the free text.
export function isSingapore(country) {
  const key = String(country ?? '').replace(/[^\p{L}\s]/gu, '').trim().replace(/\s+/g, ' ').toUpperCase()
  return ['SG', 'SGP', 'SINGAPORE', 'REPUBLIC OF SINGAPORE'].includes(key)
}

// Whole years on today's date in Singapore; dateOfBirth is YYYY-MM-DD.
export function ageOn(dateOfBirth, today) {
  const [year, month, day] = dateOfBirth.split('-').map(Number)
  const [nowYear, nowMonth, nowDay] = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Singapore' })
    .format(today).split('-').map(Number)
  let age = nowYear - year
  if (nowMonth < month || (nowMonth === month && nowDay < day)) age -= 1
  return age
}

const length = (value) => [...String(value ?? '').trim()].length

export function validateClient(values, today = new Date()) {
  for (const [field, label] of [['first_name', 'First name'], ['last_name', 'Last name']]) {
    const value = String(values[field] ?? '').trim()
    if (length(value) < 2) return `${label} must be at least 2 characters.`
    if (!NAME.test(value)) return `${label} can contain only letters and spaces.`
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(values.date_of_birth ?? '')) return 'Enter a date of birth.'
  const age = ageOn(values.date_of_birth, today)
  if (age < 18 || age > 100) return 'The client must be between 18 and 100 years old.'
  if (!EMAIL.test(String(values.email ?? '').trim())) return 'Enter a valid email address, such as name@example.com.'
  const phone = String(values.phone_number ?? '').trim().replace(/[\s\-.()]/g, '')
  if (!PHONE.test(phone)) return 'Enter the phone number with its country code: 10 to 15 digits, such as +6591234567.'
  if (length(values.address) < 5) return 'Address must be at least 5 characters.'
  for (const [field, label] of [['city', 'City'], ['state', 'State'], ['country', 'Country']]) {
    if (length(values[field]) < 2) return `${label} must be at least 2 characters.`
  }
  const postal = String(values.postal_code ?? '').trim()
  if (isSingapore(values.country)) {
    if (!SINGAPORE_POSTAL.test(postal)) return 'A Singapore postal code is 6 digits.'
  } else if (!POSTAL.test(postal)) {
    return 'Postal code must be 4 to 10 letters, digits, spaces or hyphens.'
  }
  return ''
}
