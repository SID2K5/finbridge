import type { LoginResponse, Role, User } from '../types/api';
import { getData, getPage, send } from './api';

export const authService = {
  login: (email: string, password: string) => send<LoginResponse>('post', '/auth/login', { email, password }),
  me: () => getData<User>('/auth/me'),
};

export interface UserFilter {
  q?: string;
  role?: string;
  enabled?: string;
}

export const userService = {
  list: (filter: UserFilter, page: number, size: number, sort?: string) =>
    getPage<User>('/users', { ...filter, page, size, sort }),
  roles: () => getData<Role[]>('/roles'),
  create: (body: { email: string; fullName: string; password: string; roles: Role[] }) =>
    send<User>('post', '/auth/register', body),
  update: (id: number, body: { fullName?: string; enabled?: boolean }) => send<User>('patch', `/users/${id}`, body),
  replaceRoles: (id: number, roles: Role[]) => send<User>('put', `/users/${id}/roles`, { roles }),
  resetPassword: (id: number, newPassword: string) =>
    send<void>('post', `/users/${id}/reset-password`, { newPassword }),
};
