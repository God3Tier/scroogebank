import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { expect, it, vi } from 'vitest'
import { disableUser, listUsers } from '../api/users.js'
import UserList from './UserList.jsx'

vi.mock('react-oidc-context', () => ({
  useAuth: () => ({
    user: { profile: { sub: 'admin1-sub' } },
  }),
}))

vi.mock('../api/users.js', () => ({
  listUsers: vi.fn(),
  disableUser: vi.fn(),
}))

it('protects the root admin and yourself, but allows disabling another admin', async () => {
  const users = [
    {
      id: 'root-id',
      idpSub: 'root-sub',
      firstName: 'Root',
      lastName: 'Admin',
      role: 'ROOT_ADMIN',
      email: 'root@example.test',
      enabled: true,
      deletable: false,
    },
    {
      id: 'admin1-id',
      idpSub: 'admin1-sub',
      firstName: 'Admin',
      lastName: 'One',
      email: 'admin1@example.test',
      enabled: true,
      deletable: true,
    },
    {
      id: 'admin2-id',
      idpSub: 'admin2-sub',
      firstName: 'Admin',
      lastName: 'Two',
      email: 'admin2@example.test',
      enabled: true,
      deletable: true,
    },
  ]

  listUsers.mockResolvedValue({
    content: users,
    page: 0,
    size: 10,
    totalElements: 3,
    totalPages: 1,
  })
  disableUser.mockResolvedValue(null)

  render(
    <MemoryRouter>
      <UserList role="ADMIN" title="Manage Admins" />
    </MemoryRouter>
  )

  async function buttonFor(email) {
    const name = await screen.findByText(
      (text) => text.includes(email)
    )
    return within(name.closest('li')).getByRole('button', {
      name: 'Disable',
    })
  }

  const rootButton = await buttonFor('root@example.test')
  expect(await screen.findByText('Root Admin (root@example.test)')).toBeInTheDocument()
  const selfButton = await buttonFor('admin1@example.test')
  const otherButton = await buttonFor('admin2@example.test')

  expect(rootButton).toBeDisabled()
  expect(selfButton).toBeDisabled()
  expect(otherButton).toBeEnabled()

  fireEvent.click(rootButton)
  fireEvent.click(selfButton)
  expect(disableUser).not.toHaveBeenCalled()

  fireEvent.click(otherButton)
  expect(disableUser).toHaveBeenCalledWith('admin2-id')
})
