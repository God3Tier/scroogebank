import { describe, expect, it } from 'vitest'

import { ADMIN, AGENT, groupsOf, homeFor, inAnyGroup, landingPathFor } from './roles.js'

const user = (groups, state) => ({ profile: { 'cognito:groups': groups }, state })

describe('roles', () => {
  it('reads Cognito groups whatever their case', () => {
    expect(groupsOf(user(['Admin']))).toEqual([ADMIN])
    expect(inAnyGroup(user(['agent']), [AGENT])).toBe(true)
    expect(groupsOf(user(undefined))).toEqual([])
    expect(groupsOf(null)).toEqual([])
  })

  it("sends each role to its own home page", () => {
    expect(homeFor(user(['Admin']))).toBe('/admin')
    expect(homeFor(user(['Agent']))).toBe('/agent')
    expect(homeFor(user([]))).toBe('/')
  })

  it('returns to the page asked for, if it is in this app', () => {
    expect(landingPathFor(user(['Agent'], { returnTo: '/agent/alerts' }))).toBe('/agent/alerts')
    expect(landingPathFor(user(['Agent'], { returnTo: '//attacker.example/' }))).toBe('/agent')
    expect(landingPathFor(user(['Agent'], { returnTo: 'https://attacker.example/' }))).toBe('/agent')
    expect(landingPathFor(user(['Agent'], { returnTo: '/login' }))).toBe('/agent')
    expect(landingPathFor(user(['Admin'], { returnTo: '/agent/alerts' }), null)).toBe('/admin')
  })
})
