import { useState } from 'react';
import { DataTable, type Column } from '../components/DataTable';
import { StatusBadge } from '../components/StatusBadge';
import { Card, ConfirmDialog, Field, FilterBar, InlineError, Modal, PageHeader } from '../components/ui';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { usePagedQuery } from '../hooks/usePagedQuery';
import { useQuery } from '../hooks/useQuery';
import { dateTime, roleName } from '../lib/format';
import { userService, type UserFilter } from '../services/auth';
import type { Role, User } from '../types/api';

const EMPTY: UserFilter = {};

type Dialog =
  | { kind: 'create' }
  | { kind: 'roles'; user: User }
  | { kind: 'toggle'; user: User }
  | { kind: 'password'; user: User };

export function UsersPage() {
  const toast = useToast();
  const { user: me } = useAuth();
  const [filter, setFilter] = useState<UserFilter>(EMPTY);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const roles = useQuery(() => userService.roles(), []);
  const table = usePagedQuery((page, size, sort) => userService.list(filter, page, size, sort), JSON.stringify(filter));
  const set = (patch: Partial<UserFilter>) => setFilter((current) => ({ ...current, ...patch }));
  const active = Object.values(filter).some((value) => value !== undefined && value !== '');

  const done = (message: string) => {
    setDialog(null);
    toast.success(message);
    table.reload();
  };

  const columns: Column<User>[] = [
    {
      key: 'name',
      header: 'Name',
      sortField: 'fullName',
      render: (row) => (
        <>
          <span className="font-medium text-slate-800">{row.fullName}</span>
          {row.id === me?.id && <span className="ml-2 text-xs text-slate-500">(you)</span>}
        </>
      ),
    },
    { key: 'email', header: 'Email', sortField: 'email', render: (row) => row.email },
    { key: 'roles', header: 'Roles', render: (row) => row.roles.map(roleName).join(', ') },
    {
      key: 'state',
      header: 'State',
      render: (row) =>
        row.lockedUntil && new Date(row.lockedUntil) > new Date() ? (
          <StatusBadge status="LOCKED" tone="amber" />
        ) : (
          <StatusBadge status={row.enabled ? 'ENABLED' : 'DISABLED'} tone={row.enabled ? 'green' : 'slate'} />
        ),
    },
    { key: 'login', header: 'Last sign-in', sortField: 'lastLoginAt', className: 'whitespace-nowrap', render: (row) => (row.lastLoginAt ? dateTime(row.lastLoginAt) : 'Never') },
    {
      key: 'actions',
      header: 'Actions',
      render: (row) => (
        <span className="flex flex-wrap gap-x-3 gap-y-1 text-sm font-medium text-navy-700">
          <button type="button" className="underline" onClick={() => setDialog({ kind: 'roles', user: row })}>
            Roles
          </button>
          <button type="button" className="underline" onClick={() => setDialog({ kind: 'password', user: row })}>
            Reset password
          </button>
          <button type="button" className="underline" onClick={() => setDialog({ kind: 'toggle', user: row })}>
            {row.enabled ? 'Disable' : 'Enable'}
          </button>
        </span>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Users"
        subtitle="Accounts are created here by an administrator; there is no self-registration."
        actions={
          <button type="button" className="btn-primary" onClick={() => setDialog({ kind: 'create' })}>
            New user
          </button>
        }
      />
      <Card>
        <FilterBar onReset={() => setFilter(EMPTY)} active={active}>
          <Field label="Name or email" className="w-64">
            <input className="input" type="search" placeholder="Contains…" value={filter.q ?? ''} onChange={(event) => set({ q: event.target.value })} />
          </Field>
          <Field label="Role" className="w-56">
            <select className="input" value={filter.role ?? ''} onChange={(event) => set({ role: event.target.value })}>
              <option value="">Any role</option>
              {(roles.data ?? []).map((role) => (
                <option key={role} value={role}>
                  {roleName(role)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="State" className="w-40">
            <select className="input" value={filter.enabled ?? ''} onChange={(event) => set({ enabled: event.target.value })}>
              <option value="">Any</option>
              <option value="true">Enabled</option>
              <option value="false">Disabled</option>
            </select>
          </Field>
        </FilterBar>
        <DataTable
          caption="Users"
          columns={columns}
          rows={table.items}
          rowKey={(row) => row.id}
          loading={table.loading}
          error={table.error}
          onRetry={table.reload}
          emptyTitle="No user matches these filters"
          meta={table.meta}
          onPage={table.setPage}
          sort={table.sort}
          onSort={table.toggleSort}
        />
      </Card>

      {dialog?.kind === 'create' && <CreateUserDialog roles={roles.data ?? []} onClose={() => setDialog(null)} onDone={(user) => done(`User ${user.email} was created.`)} />}
      {dialog?.kind === 'roles' && <RolesDialog user={dialog.user} roles={roles.data ?? []} onClose={() => setDialog(null)} onDone={(user) => done(`Roles of ${user.email} were updated.`)} />}
      {dialog?.kind === 'password' && <PasswordDialog user={dialog.user} onClose={() => setDialog(null)} onDone={() => done(`The password of ${dialog.user.email} was reset.`)} />}
      {dialog?.kind === 'toggle' && <ToggleDialog user={dialog.user} onClose={() => setDialog(null)} onDone={(user) => done(`${user.email} is now ${user.enabled ? 'enabled' : 'disabled'}.`)} />}
    </>
  );
}

function RoleCheckboxes({ all, selected, onChange, disabled }: { all: Role[]; selected: Role[]; onChange: (roles: Role[]) => void; disabled: boolean }) {
  return (
    <fieldset>
      <legend className="label">Roles</legend>
      <div className="space-y-1.5">
        {all.map((role) => (
          <label key={role} className="flex items-center gap-2">
            <input
              type="checkbox"
              disabled={disabled}
              checked={selected.includes(role)}
              onChange={(event) => onChange(event.target.checked ? [...selected, role] : selected.filter((item) => item !== role))}
            />
            {roleName(role)}
          </label>
        ))}
      </div>
    </fieldset>
  );
}

function CreateUserDialog({ roles, onClose, onDone }: { roles: Role[]; onClose: () => void; onDone: (user: User) => void }) {
  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');
  const [password, setPassword] = useState('');
  const [selected, setSelected] = useState<Role[]>([]);
  const create = useAsyncAction(() => userService.create({ email: email.trim(), fullName: fullName.trim(), password, roles: selected }));
  const valid = email.trim() !== '' && fullName.trim() !== '' && password.length >= 12 && password.length <= 72 && selected.length > 0;

  const submit = async () => {
    const user = await create.run();
    if (user) {
      onDone(user);
    }
  };

  return (
    <Modal
      title="New user"
      onClose={onClose}
      busy={create.running}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose} disabled={create.running}>
            Cancel
          </button>
          <button type="button" className="btn-primary" onClick={() => void submit()} disabled={!valid || create.running}>
            {create.running ? 'Creating…' : 'Create user'}
          </button>
        </>
      }
    >
      <Field label="Full name">
        <input className="input" value={fullName} maxLength={120} onChange={(event) => setFullName(event.target.value)} autoFocus />
      </Field>
      <Field label="Email">
        <input className="input" type="email" value={email} maxLength={190} autoComplete="off" onChange={(event) => setEmail(event.target.value)} />
      </Field>
      <Field label="Initial password (12 to 72 characters)">
        <input className="input" type="password" value={password} maxLength={72} autoComplete="new-password" onChange={(event) => setPassword(event.target.value)} />
      </Field>
      <RoleCheckboxes all={roles} selected={selected} onChange={setSelected} disabled={create.running} />
      <p className="text-xs text-slate-500">Give the password to the person through a separate channel. It is stored only as a hash.</p>
      <InlineError error={create.error} />
    </Modal>
  );
}

function RolesDialog({ user, roles, onClose, onDone }: { user: User; roles: Role[]; onClose: () => void; onDone: (user: User) => void }) {
  const [selected, setSelected] = useState<Role[]>(user.roles);
  const save = useAsyncAction(() => userService.replaceRoles(user.id, selected));
  const submit = async () => {
    const updated = await save.run();
    if (updated) {
      onDone(updated);
    }
  };
  return (
    <ConfirmDialog title={`Roles of ${user.fullName}`} confirmLabel="Save roles" onConfirm={() => void submit()} onCancel={onClose} running={save.running} error={save.error} confirmDisabled={selected.length === 0}>
      <RoleCheckboxes all={roles} selected={selected} onChange={setSelected} disabled={save.running} />
      <p className="text-xs text-slate-500">The change applies to the user's very next request. The last enabled administrator cannot be demoted.</p>
    </ConfirmDialog>
  );
}

function PasswordDialog({ user, onClose, onDone }: { user: User; onClose: () => void; onDone: () => void }) {
  const [password, setPassword] = useState('');
  const save = useAsyncAction(async () => {
    await userService.resetPassword(user.id, password);
    return true;
  });
  const submit = async () => {
    if (await save.run()) {
      onDone();
    }
  };
  return (
    <ConfirmDialog
      title={`Reset the password of ${user.fullName}`}
      confirmLabel="Set new password"
      onConfirm={() => void submit()}
      onCancel={onClose}
      running={save.running}
      error={save.error}
      confirmDisabled={password.length < 12 || password.length > 72}
    >
      <Field label="New password (12 to 72 characters)">
        <input className="input" type="password" value={password} maxLength={72} autoComplete="new-password" onChange={(event) => setPassword(event.target.value)} autoFocus />
      </Field>
      <p className="text-xs text-slate-500">The old password stops working at once. A locked account is unlocked.</p>
    </ConfirmDialog>
  );
}

function ToggleDialog({ user, onClose, onDone }: { user: User; onClose: () => void; onDone: (user: User) => void }) {
  const save = useAsyncAction(() => userService.update(user.id, { enabled: !user.enabled }));
  const submit = async () => {
    const updated = await save.run();
    if (updated) {
      onDone(updated);
    }
  };
  return (
    <ConfirmDialog
      title={user.enabled ? `Disable ${user.fullName}` : `Enable ${user.fullName}`}
      confirmLabel={user.enabled ? 'Disable user' : 'Enable user'}
      onConfirm={() => void submit()}
      onCancel={onClose}
      running={save.running}
      error={save.error}
      danger={user.enabled}
    >
      {user.enabled ? (
        <p>
          <strong>{user.email}</strong> will be signed out at their next request and will not be able to sign in. Nothing they
          did is deleted.
        </p>
      ) : (
        <p>
          <strong>{user.email}</strong> will be able to sign in again with their existing password and roles.
        </p>
      )}
    </ConfirmDialog>
  );
}
